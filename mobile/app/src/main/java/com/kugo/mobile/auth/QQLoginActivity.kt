package com.kugo.mobile.auth

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.kugo.mobile.ui.KugoTheme
import com.kugo.mobile.ui.QQLoginScreen

/**
 * 承载 QQ 登录 WebView 的独立 Activity。
 *
 * 为什么单独开一个 Activity 而不是在设置页里弹 Dialog：
 *  1. 登录页有键盘输入、页面回退、横竖屏切换，需要一个完整的窗口生命周期；
 *  2. 独立 Activity 可以在 `onDestroy` 里精确地销毁 WebView，避免长期占用内存；
 *  3. 结果通过 `setResult` 回传给调用方，职责边界清晰。
 *
 * 对外只暴露 [intent] 与 [readOutcome] 两个入口，调用方不需要知道内部结构。
 */
class QQLoginActivity : ComponentActivity() {

    companion object {
        const val EXTRA_SILENT = "extra_silent"
        const val EXTRA_OK = "extra_ok"
        const val EXTRA_UIN = "extra_uin"
        const val EXTRA_CODE = "extra_code"
        const val EXTRA_MESSAGE = "extra_message"

        private const val RETURN_CODE = "return_code"

        /** 构造启动 Intent。[silent] 为 true 时用已保存的凭据尝试静默续期。 */
        fun intent(context: Context, silent: Boolean = false): Intent =
            Intent(context, QQLoginActivity::class.java).putExtra(EXTRA_SILENT, silent)

        /** 从返回的 Intent 中读取结果。 */
        fun readOutcome(data: Intent?): Pair<Boolean, String> {
            if (data == null) return false to "登录已取消"
            val ok = data.getBooleanExtra(EXTRA_OK, false)
            val message = data.getStringExtra(EXTRA_MESSAGE).orEmpty()
            return ok to message
        }

        /** 读取登录成功的 QQ 号，供 UI 展示。 */
        fun readUin(data: Intent?): String = data?.getStringExtra(EXTRA_UIN).orEmpty()
    }

    private val vm: QQLoginViewModel by viewModels()
    private var delivered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val silent = intent.getBooleanExtra(EXTRA_SILENT, false)

        // WebView 内核缺失（部分裁剪 ROM）时不要尝试实例化，直接给出可读的失败结果。
        if (!QQLoginWebViewFactory.isWebViewAvailable(this)) {
            deliverResult(LoginOutcome.WebViewUnavailable("系统 WebView 不可用，请安装或启用 Android System WebView"))
            return
        }

        vm.ensureStarted(silent)

        setContent {
            KugoTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    QQLoginScreen(
                        vm = vm,
                        silent = silent,
                        onResult = ::deliverResult,
                    )
                }
            }
        }
    }

    /**
     * 统一出口。Activity 只把 [LoginOutcome] 翻译成 `setResult` 的字段，
     * 不给调用方暴露 auth 包的内部类型。
     */
    private fun deliverResult(outcome: LoginOutcome) {
        if (delivered) return
        delivered = true

        val data = Intent().putExtra(RETURN_CODE, codeOf(outcome))
        when (outcome) {
            is LoginOutcome.Success -> {
                data.putExtra(EXTRA_OK, true)
                data.putExtra(EXTRA_UIN, outcome.session.uin)
                data.putExtra(
                    EXTRA_MESSAGE,
                    if (outcome.silent) "已自动续期登录态" else "登录成功",
                )
            }

            LoginOutcome.UserCancelled -> {
                data.putExtra(EXTRA_OK, false)
                data.putExtra(EXTRA_MESSAGE, "已取消登录")
            }

            LoginOutcome.SilentRefreshFailed -> {
                data.putExtra(EXTRA_OK, false)
                data.putExtra(EXTRA_MESSAGE, "自动续期失败")
            }

            is LoginOutcome.Failed -> {
                data.putExtra(EXTRA_OK, false)
                data.putExtra(EXTRA_MESSAGE, outcome.message)
            }

            LoginOutcome.Timeout -> {
                data.putExtra(EXTRA_OK, false)
                data.putExtra(EXTRA_MESSAGE, "登录超时，请重试")
            }

            is LoginOutcome.NetworkError -> {
                data.putExtra(EXTRA_OK, false)
                data.putExtra(EXTRA_MESSAGE, "网络异常：${outcome.message}")
            }

            is LoginOutcome.WebViewUnavailable -> {
                data.putExtra(EXTRA_OK, false)
                data.putExtra(EXTRA_MESSAGE, outcome.message)
            }
        }

        setResult(if (outcome is LoginOutcome.Success) Activity.RESULT_OK else Activity.RESULT_CANCELED, data)
        finish()
    }

    private fun codeOf(outcome: LoginOutcome): String = when (outcome) {
        is LoginOutcome.Success -> "ok"
        LoginOutcome.UserCancelled -> LoginErrorCode.USER_CANCELLED
        LoginOutcome.SilentRefreshFailed -> "silent_refresh_failed"
        is LoginOutcome.Failed -> outcome.code
        LoginOutcome.Timeout -> LoginErrorCode.TIMEOUT
        is LoginOutcome.NetworkError -> LoginErrorCode.NO_NETWORK
        is LoginOutcome.WebViewUnavailable -> LoginErrorCode.WEBVIEW_MISSING
    }
}
