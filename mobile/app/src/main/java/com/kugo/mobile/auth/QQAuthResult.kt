package com.kugo.mobile.auth

/**
 * 登录流程的终态。
 *
 * 用密封接口而不是「布尔 + 错误码」的原因：调用方（Activity / ViewModel）需要
 * 对每种分支给出不同的后续动作——取消要静默返回、超时要给重试按钮、
 * 网络异常要提示检查网络、凭据缺失要引导用户重登。用 when 穷举可以保证不漏分支。
 */
sealed interface LoginOutcome {

    /** 登录成功且会话已落盘。 */
    data class Success(val session: QQSession, val silent: Boolean = false) : LoginOutcome

    /** 用户主动取消（返回键 / 关闭按钮），UI 不应报错，静默返回即可。 */
    data object UserCancelled : LoginOutcome

    /** 页面上明确返回了失败（例如账号被限制、密码错误重试次数用尽）。 */
    data class Failed(val code: String, val message: String) : LoginOutcome

    /** 超过用户操作时限仍无结果。 */
    data object Timeout : LoginOutcome

    /** 网络层不可用：无连接、DNS 失败、连接被重置。 */
    data class NetworkError(val message: String) : LoginOutcome

    /** WebView 本身不可用：内核缺失、被系统禁用、渲染进程崩溃。 */
    data class WebViewUnavailable(val message: String) : LoginOutcome

    /** 静默续期失败（旧 Cookie 已死），需要用户交互式重登。 */
    data object SilentRefreshFailed : LoginOutcome
}

/** 异常分支的错误码。集中定义便于埋点与文案映射。 */
object LoginErrorCode {
    const val NO_NETWORK = "no_network"
    const val TIMEOUT = "timeout"
    const val HTTP_ERROR = "http_error"
    const val SSL_ERROR = "ssl_error"
    const val RENDER_GONE = "render_process_gone"
    const val WEBVIEW_MISSING = "webview_missing"
    const val COOKIE_MISSING = "cookie_missing"
    const val USER_CANCELLED = "user_cancelled"
    const val UNKNOWN = "unknown"
}
