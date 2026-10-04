package com.kugo.mobile.auth

import android.content.Context
import android.util.Log
import com.kugo.mobile.core.DecryptEngine
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** 登录态的校验结论（面向 UI 的三态，比 [ValidateResult] 更适合展示）。 */
enum class VerifyFlag { Unchecked, Valid, Expired, Unknown }

/**
 * QQ 账号状态。
 *
 * [session] 不为空不代表可用——它可能已经过期。判断可用请用 [isUsable]。
 */
data class QQAccountState(
    val session: QQSession? = null,
    val verify: VerifyFlag = VerifyFlag.Unchecked,
    val message: String? = null,
    /** 会话是否以加密形式落盘，供诊断信息展示。 */
    val encryptedAtRest: Boolean = false,
) {
    val isUsable: Boolean
        get() = session != null &&
            verify != VerifyFlag.Expired &&
            !session.isExpired()

    val isExpired: Boolean
        get() = session != null && (verify == VerifyFlag.Expired || session.isExpired())
}

/** 需要上层响应的登录态事件。 */
sealed interface QQAuthEvent {
    /** 登录态失效，建议引导用户重新登录。 */
    data class NeedRelogin(val reason: String) : QQAuthEvent
}

/**
 * 登录态的唯一持有者。
 *
 * 职责边界：
 *  - 持有一份内存态会话，并负责落盘 / 恢复；
 *  - 把 Cookie 同步给 Go 核心（`Gocore.setQMCCookie`），让解密链路能自动换 ekey；
 *  - 对外暴露「是否可用」「是否过期」以及失效事件，UI 与业务层只读不写。
 *
 * 刻意不做的事：**不静默自动重登**。QQ音乐没有给第三方可用的 refresh 通道，
 * 老 Cookie 死了就是死了，唯一可靠的续期手段是让 WebView 带着旧 Cookie 再走一次
 * 免密登录（见 QQLoginActivity 的静默续期模式）。那需要 WebView 实例，
 * 不适合塞在一个后台单例里，因此这里只抛出事件，由 UI 决定什么时候发起。
 */
object QQSessionManager {

    private const val TAG = "QQSessionManager"

    private var store: QQSessionStore? = null

    private val _state = MutableStateFlow(QQAccountState())
    val state: StateFlow<QQAccountState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<QQAuthEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<QQAuthEvent> = _events.asSharedFlow()

    /** 幂等初始化，在 Application / 首个 ViewModel 创建时调用一次即可。 */
    fun init(context: Context) {
        if (store != null) return
        val s = QQSessionStore(context.applicationContext)
        store = s

        val restored = s.load()
        if (restored == null) {
            _state.value = QQAccountState(encryptedAtRest = s.isEncryptedAtRest())
            return
        }

        val verify = when (QQSessionValidator.validateLocally(restored)) {
            is ValidateResult.Valid -> VerifyFlag.Unchecked
            else -> VerifyFlag.Expired
        }
        _state.value = QQAccountState(
            session = restored,
            verify = verify,
            message = if (verify == VerifyFlag.Expired) "本地凭据已过期" else null,
            encryptedAtRest = s.isEncryptedAtRest(),
        )

        if (verify != VerifyFlag.Expired) {
            // Go 层的凭据只存在进程内存里，每次冷启动都必须重新喂一遍。
            pushToGo(restored)
        }
        Log.i(TAG, "恢复会话：uin=${restored.uin} verify=$verify")
    }

    fun currentSession(): QQSession? = _state.value.session

    val isUsable: Boolean get() = _state.value.isUsable

    /** 登录成功（或静默续期成功）后写入新会话。 */
    fun accept(session: QQSession, silent: Boolean = false) {
        val encrypted = runCatching { store?.save(session) }.isSuccess
        QQWebCookieJar.flush()
        pushToGo(session)
        _state.value = QQAccountState(
            session = session,
            verify = VerifyFlag.Valid,
            message = if (silent) "已静默续期" else "登录成功",
            encryptedAtRest = encrypted && (store?.isEncryptedAtRest() ?: false),
        )
        Log.i(TAG, "接受会话：uin=${session.uin} 有效期至 ${session.expiresAtText}")
    }

    /**
     * 在线校验当前会话。仅在 [force] 或本地判定为过期时才会发请求，
     * 避免每次进设置页都打一次网络。
     */
    suspend fun verify(force: Boolean = false): VerifyFlag {
        val session = _state.value.session ?: return VerifyFlag.Expired

        if (!force && _state.value.verify == VerifyFlag.Valid && !session.shouldRenewSoon()) {
            return VerifyFlag.Valid
        }

        val result = QQSessionValidator.validate(session)
        val flag = when (result) {
            is ValidateResult.Valid -> VerifyFlag.Valid
            is ValidateResult.Expired -> VerifyFlag.Expired
            is ValidateResult.NetworkError -> VerifyFlag.Unknown
            is ValidateResult.Unknown -> VerifyFlag.Unknown
        }

        val detail = when (result) {
            is ValidateResult.NetworkError -> "网络异常，暂用本地有效期判断"
            is ValidateResult.Unknown -> "服务端响应异常，暂用本地有效期判断"
            is ValidateResult.Expired -> "登录态已失效"
            is ValidateResult.Valid -> null
        }

        _state.value = _state.value.copy(verify = flag, message = detail)

        if (flag == VerifyFlag.Expired) {
            // 失效时**保留**会话记录：静默续期要用它，用户也能看到上次登录的是哪个号。
            _events.tryEmit(QQAuthEvent.NeedRelogin(detail ?: "登录态已失效"))
        }
        return flag
    }

    /**
     * 业务层发现凭据失效时调用（例如换 ekey 返回未登录）。
     * 只改本地状态并发事件，不打网络——调用方此刻通常正处在错误处理路径上。
     */
    fun invalidate(reason: String) {
        val s = _state.value
        if (s.session == null) return
        _state.value = s.copy(verify = VerifyFlag.Expired, message = reason)
        _events.tryEmit(QQAuthEvent.NeedRelogin(reason))
    }

    /** 退出登录：清 Cookie、清 WebStorage、清落盘会话、清 Go 层凭据。 */
    fun logout() {
        QQWebCookieJar.clearAll()
        QQWebCookieJar.clearWebStorage()
        runCatching { store?.clear() }
        runCatching { DecryptEngine.saveQmcCookie("") }
        _state.value = QQAccountState(encryptedAtRest = store?.isEncryptedAtRest() ?: false)
        Log.i(TAG, "已退出登录")
    }

    /** 诊断信息，供主界面「复制诊断」使用。 */
    fun describe(): String {
        val s = _state.value.session
            ?: return "QQ 账号：未登录"
        return buildString {
            appendLine("QQ 账号：${if (s.uin.isEmpty()) "未知 uin" else s.uin}")
            appendLine("校验状态：${_state.value.verify}")
            appendLine("抓取时间：${s.capturedAtText}")
            appendLine("预估失效：${s.expiresAtText}（${s.remainingText()}）")
            append("存储方式：${if (_state.value.encryptedAtRest) "AndroidKeyStore 加密" else "明文（Keystore 不可用）"}")
        }
    }

    private fun pushToGo(session: QQSession) {
        runCatching { DecryptEngine.saveQmcCookie(session.cookieHeader) }
            .onFailure { Log.w(TAG, "同步 Cookie 到 Go 层失败：${it.message}") }
    }
}
