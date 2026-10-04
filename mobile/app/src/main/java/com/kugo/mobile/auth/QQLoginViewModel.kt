package com.kugo.mobile.auth

import android.webkit.WebViewClient
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 登录页所处的阶段。UI 依据它决定显示进度条、提示文案还是结果浮层。 */
enum class QQLoginPhase {
    /** 尚未开始。 */
    Idle,

    /** 页面加载中。 */
    Loading,

    /** 页面已就绪，等待用户输入账号密码 / 扫码。 */
    AwaitingUser,

    /** 落在验证码 / 二次验证流程里。 */
    RiskChallenge,

    /** 页面跳转完成，正在从 CookieManager 抓取凭据（含多次重试）。 */
    Capturing,

    /** 已抓到凭据，正在做有效性校验。 */
    Verifying,

    /** 流程结束，[QQLoginUiState.outcome] 已确定。 */
    Done,
}

data class QQLoginUiState(
    val phase: QQLoginPhase = QQLoginPhase.Idle,
    val progress: Int = 0,
    val pageTitle: String? = null,
    val hint: String = "正在加载 QQ 登录页…",
    val outcome: LoginOutcome? = null,
    /** 是否为「用旧凭据静默续期」模式。 */
    val silentMode: Boolean = false,
    /** 用户已在页面里操作过（用于区分「还没开始就超时」与「操作到一半超时」）。 */
    val userInteracted: Boolean = false,
    /** 自增令牌；变化时 UI 重新加载 [QQLoginViewModel.currentUrl]。 */
    val reloadToken: Int = 0,
    /** 需要展示给用户的环境告警（WebView 内核过旧等）。 */
    val environmentWarning: String? = null,
) {
    val isBusy: Boolean get() = phase == QQLoginPhase.Loading || phase == QQLoginPhase.Capturing
    val isFinished: Boolean get() = outcome != null
}

/**
 * 登录流程状态机。
 *
 * 设计要点：
 *  - **不持有 WebView**。ViewModel 只接收回调、产出状态与「重新加载」指令，
 *    这样旋转屏幕、Activity 重建都不会牵扯 WebView 的生命周期，也不会内存泄漏。
 *  - **所有终态都走 [finish]**，保证 sessionTimeoutJob / captureJob 一定被取消，
 *    不会出现「界面已关闭但协程还在轮询 Cookie」的悬挂。
 */
class QQLoginViewModel : ViewModel() {

    private val _ui = MutableStateFlow(QQLoginUiState())
    val ui: StateFlow<QQLoginUiState> = _ui.asStateFlow()

    /** 当前该加载的地址，供 UI 初始化与重试时读取。 */
    var currentUrl: String = QQAuthConfig.LOGIN_URL
        private set

    private var timeoutJob: Job? = null
    private var captureJob: Job? = null
    private var startedOnce = false

    // --- 生命周期 ---

    /** Activity 重建（进程被杀后恢复）时不重复启动，避免把用户刚填的页面冲掉。 */
    fun ensureStarted(silent: Boolean) {
        if (startedOnce) return
        startedOnce = true
        start(silent)
    }

    fun start(silent: Boolean) {
        currentUrl = QQAuthConfig.LOGIN_URL
        _ui.value = QQLoginUiState(
            phase = QQLoginPhase.Loading,
            silentMode = silent,
            hint = if (silent) "正在用已保存的凭据尝试静默续期…" else "正在加载 QQ 登录页…",
        )
        armTimeout(if (silent) QQAuthConfig.SILENT_REFRESH_TIMEOUT_MS else QQAuthConfig.SESSION_TIMEOUT_MS)
    }

    /** 用户重试：清掉终态，重新走一次。 */
    fun retry(silent: Boolean = false) {
        captureJob?.cancel()
        timeoutJob?.cancel()
        start(silent)
        _ui.update { it.copy(reloadToken = it.reloadToken + 1) }
    }

    /** 静默续期失败后转入交互式登录：不关页面，直接把当前登录页交给用户。 */
    fun continueInteractively() {
        captureJob?.cancel()
        _ui.update {
            it.copy(
                outcome = null,
                silentMode = false,
                phase = QQLoginPhase.AwaitingUser,
                hint = "自动续期失败，请在页面内重新登录",
                reloadToken = it.reloadToken + 1,
            )
        }
        armTimeout(QQAuthConfig.SESSION_TIMEOUT_MS)
    }

    /** 用户主动取消。 */
    fun onUserCancel() {
        finish(if (_ui.value.silentMode) LoginOutcome.SilentRefreshFailed else LoginOutcome.UserCancelled)
    }

    // --- WebView 回调 ---

    fun onPageStarted(url: String?) {
        if (_ui.value.isFinished) return
        captureJob?.cancel()
        _ui.update {
            it.copy(
                phase = QQLoginPhase.Loading,
                hint = if (QQAuthConfig.isVerifyUrl(url)) "正在跳转安全验证…" else "正在加载…",
                userInteracted = it.userInteracted || url?.contains("ptlogin2") == true,
            )
        }
    }

    fun onPageFinished(url: String?) {
        if (_ui.value.isFinished) return

        if (QQAuthConfig.isVerifyUrl(url)) {
            _ui.update {
                it.copy(
                    phase = QQLoginPhase.RiskChallenge,
                    hint = "检测到安全验证，请在页面内完成（可能需要滑块或短信）",
                    userInteracted = true,
                )
            }
        } else {
            _ui.update {
                it.copy(
                    phase = QQLoginPhase.AwaitingUser,
                    progress = 100,
                    hint = if (it.silentMode) "凭据仍然有效，正在确认…" else "请在页面内登录 QQ 账号",
                )
            }
        }
        scheduleCapture()
    }

    fun onProgressChanged(progress: Int) {
        if (_ui.value.isFinished) return
        _ui.update { it.copy(progress = progress.coerceIn(0, 100)) }
    }

    fun onTitleChanged(title: String?) {
        _ui.update { it.copy(pageTitle = title?.takeIf { t -> t.isNotBlank() }) }
    }

    fun onUserInteraction() {
        if (_ui.value.userInteracted) return
        _ui.update { it.copy(userInteracted = true) }
    }

    /** 主文档加载失败。子资源失败由 UI 层过滤掉，不进入这里。 */
    fun onPageError(code: Int, description: String?) {
        if (_ui.value.isFinished) return
        val outcome = if (isNetworkErrorCode(code)) {
            LoginOutcome.NetworkError(description?.takeIf { it.isNotBlank() } ?: networkMessage(code))
        } else {
            LoginOutcome.Failed(LoginErrorCode.HTTP_ERROR, description?.takeIf { it.isNotBlank() } ?: "页面加载失败（$code）")
        }
        finish(outcome)
    }

    /** HTTP 状态码异常。只做提示，不判终态——登录页自身也会发出 4xx 的埋点请求。 */
    fun onHttpError(statusCode: Int) {
        if (_ui.value.isFinished) return
        _ui.update {
            it.copy(
                hint = if (statusCode >= 500) "服务器暂时不可用（$statusCode），仍在等待重试…" else it.hint,
                environmentWarning = if (statusCode >= 500) "QQ 服务器返回 $statusCode" else it.environmentWarning,
            )
        }
    }

    fun onSslError(url: String?) {
        finish(
            LoginOutcome.Failed(
                LoginErrorCode.SSL_ERROR,
                "证书校验失败：${url ?: "未知地址"}。为防中间人攻击已中止登录，请检查网络代理设置。",
            ),
        )
    }

    /** 渲染进程崩溃（多进程 WebView 常见）。此时旧实例已不可用，必须整页重来。 */
    fun onRenderProcessGone() {
        finish(LoginOutcome.WebViewUnavailable("WebView 渲染进程异常退出，请重试"))
    }

    fun onEnvironmentWarning(warning: String?) {
        if (warning.isNullOrBlank()) return
        _ui.update { it.copy(environmentWarning = warning) }
    }

    fun onWebViewUnavailable(detail: String) {
        finish(LoginOutcome.WebViewUnavailable(detail))
    }

    // --- 内部 ---

    private fun armTimeout(limitMs: Long) {
        timeoutJob?.cancel()
        timeoutJob = viewModelScope.launch {
            delay(limitMs)
            if (_ui.value.isFinished) return@launch
            if (_ui.value.silentMode) {
                finish(LoginOutcome.SilentRefreshFailed)
            } else if (!_ui.value.userInteracted) {
                // 用户压根没动过页面，更可能是网络问题而不是「忘了操作」。
                finish(LoginOutcome.NetworkError("登录页长时间无响应，请检查网络后重试"))
            } else {
                finish(LoginOutcome.Timeout)
            }
        }
    }

    /**
     * 抓取 Cookie。
     *
     * 时机上做了两件事：
     *  1. 在 onPageFinished / shouldOverrideUrlLoading 后触发；
     *  2. 用一串递增延时（0/400/1200/2500/4000ms）反复重试。
     *
     * 第 2 点是必需的：登录成功的 Set-Cookie 由 `ptlogin2` 的跨域 iframe 或站点的
     * XHR 写回，onPageFinished 触发时它可能还没落进 CookieManager，
     * 只在页面完成回调里抓一次会稳定地漏抓。
     */
    private fun scheduleCapture() {
        captureJob?.cancel()
        captureJob = viewModelScope.launch {
            for (delayMs in QQAuthConfig.CAPTURE_DELAYS_MS) {
                if (delayMs > 0) delay(delayMs)
                if (_ui.value.isFinished) return@launch

                _ui.update { it.copy(phase = QQLoginPhase.Capturing) }
                val fields = QQWebCookieJar.mergedFields()
                val header = QQCookieExtractor.toHeader(fields)
                val session = QQCookieExtractor.buildSession(header, fields) ?: continue

                completeLogin(session)
                return@launch
            }
            // 所有重试都没抓到，退回等待用户操作。
            if (!_ui.value.isFinished) {
                _ui.update { it.copy(phase = QQLoginPhase.AwaitingUser, hint = "请在页面内完成登录") }
            }
        }
    }

    private suspend fun completeLogin(session: QQSession) {
        _ui.update { it.copy(phase = QQLoginPhase.Verifying, hint = "已获取登录凭据，正在校验…") }

        // 先落盘并同步给 Go 层，保证即使后面校验超时也不会白登录一次。
        QQSessionManager.accept(session, silent = _ui.value.silentMode)

        when (QQSessionManager.verify(force = true)) {
            VerifyFlag.Expired -> finish(
                LoginOutcome.Failed(
                    LoginErrorCode.COOKIE_MISSING,
                    "已抓取到凭据但校验未通过，可能是账号被限制或登录未真正完成，请重试。",
                ),
            )

            else -> finish(LoginOutcome.Success(session, silent = _ui.value.silentMode))
        }
    }

    private fun finish(outcome: LoginOutcome) {
        captureJob?.cancel()
        timeoutJob?.cancel()
        _ui.update { it.copy(phase = QQLoginPhase.Done, outcome = outcome) }
    }

    override fun onCleared() {
        captureJob?.cancel()
        timeoutJob?.cancel()
        super.onCleared()
    }

    companion object {
        /** WebView 的「主文档」网络类错误码。 */
        fun isNetworkErrorCode(code: Int): Boolean = code == WebViewClient.ERROR_HOST_LOOKUP ||
            code == WebViewClient.ERROR_CONNECT ||
            code == WebViewClient.ERROR_TIMEOUT ||
            code == WebViewClient.ERROR_IO

        private fun networkMessage(code: Int): String = when (code) {
            WebViewClient.ERROR_HOST_LOOKUP -> "无法解析域名，请检查网络或 DNS"
            WebViewClient.ERROR_CONNECT -> "连接服务器失败，请检查网络"
            WebViewClient.ERROR_TIMEOUT -> "连接超时，请检查网络"
            WebViewClient.ERROR_IO -> "网络读写失败，请重试"
            else -> "网络错误（$code）"
        }
    }
}
