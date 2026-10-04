package com.kugo.mobile.auth

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.os.Build
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * 登录 WebView 的构造与初始化。
 *
 * ## 选型
 *
 * 候选有四类，逐一排除：
 *
 * | 方案 | 结论 |
 * |---|---|
 * | **系统 WebView（Chrome/Blink）** | ✅ 采用。随 Chrome 升级，TLS 指纹与 JS 引擎最新，ptlogin2 兼容性最好；无需额外体积。 |
 * | 腾讯 X5/TBS 内核 | ❌ 官方已停止对第三方应用的独立分发，需商业授权；且与本项目「无第三方 SDK」的定位冲突。 |
 * | GeckoView | ❌ 体积 +60MB，且 QQ 登录页针对 Blink 优化，兼容性未知。 |
 * | Chrome Custom Tabs / 系统浏览器 | ❌ 运行在独立进程，宿主无法读取其 Cookie，直接违背「抓 Cookie」的需求。 |
 *
 * ## 三个必须做对的配置
 *
 * 1. **UA**：见 [QQAuthConfig.userAgent]。保留 Chrome 版本、剥掉 `wv` 标记，
 *    否则 ptlogin2 会走「唤起 QQ 客户端」分支或直接提高风控等级。
 * 2. **第三方 Cookie**：ptlogin2 的登录结果由跨域 iframe 写回，
 *    关掉它会出现「授权页显示成功、站点却拿不到 key」这种最难查的现场。
 * 3. **多窗口**：默认 `onCreateWindow` 返回 true 会弹出一个不受控的新窗口，
 *    导致登录结果写在另一个 WebView 上。必须关掉，让流程在同一实例内完成。
 */
object QQLoginWebViewFactory {

    @SuppressLint("SetJavaScriptEnabled", "DEPRECATION")
    fun create(
        context: Context,
        client: WebViewClient,
        chromeClient: WebChromeClient,
    ): WebView {
        val webView = WebView(context)
        webView.settings.apply {
            // --- 登录页必需 ---
            javaScriptEnabled = true                     // ptlogin2 是纯 JS 应用，关掉直接白屏
            domStorageEnabled = true                     // 登录页用 sessionStorage 存流程状态
            databaseEnabled = true
            defaultTextEncodingName = "UTF-8"

            // --- 身份伪装 ---
            userAgentString = QQAuthConfig.userAgent(context)

            // --- 视口 ---
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            textZoom = 100                               // 强制 100%，避免系统字号把登录页挤变形

            // --- 安全收紧：登录页不需要任何本地文件能力 ---
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            // 用显式方法调用而非属性语法：getGeolocationEnabled() 不在公开 API 里，
            // Kotlin 无法合成属性名。登录页不需要定位，且我们也没实现
            // WebChromeClient.onGeolocationPermissionsShowPrompt，默认即拒绝。
            setGeolocationEnabled(false)
            mediaPlaybackRequiresUserGesture = true

            // --- 弹窗 ---
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false

            // --- 缓存 ---
            // 登录流程对缓存极度敏感：陈旧的 ptlogin 页面会拿着过期的会话票据去换 key，
            // 表现为「明明输了密码却提示参数错误」。因此登录期间不读缓存、只走网络。
            cacheMode = WebSettings.LOAD_NO_CACHE
        }

        webView.isVerticalScrollBarEnabled = true
        webView.setBackgroundColor(Color.WHITE)          // 深色主题下避免白屏闪黑
        webView.webViewClient = client
        webView.webChromeClient = chromeClient

        if (isDebuggable(context)) {
            // 只在 Debug 包开启，可用 chrome://inspect 直接调试登录页。
            WebView.setWebContentsDebuggingEnabled(true)
        }

        QQWebCookieJar.configure(webView)
        return webView
    }

    /**
     * WebView 是否可用。极少数定制 ROM 会裁掉 WebView 内核，
     * 此时 new WebView() 会抛 AndroidRuntimeException，需要提前探到并给出可读提示。
     */
    fun isWebViewAvailable(context: Context): Boolean =
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WebView.getCurrentWebViewPackage() != null
            } else {
                // API < 26 没有 getCurrentWebViewPackage，退化为尝试实例化。
                WebView(context).also { it.destroy() }
                true
            }
        }.getOrDefault(false)

    /** 系统 WebView 的版本号，低于 86 的旧内核在 ptlogin2 上会出各种诡异问题。 */
    fun webViewVersion(): String =
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WebView.getCurrentWebViewPackage()?.let { "${it.packageName} ${it.versionName}" }
            } else {
                null
            }
        }.getOrNull() ?: "未知"

    private fun isDebuggable(context: Context): Boolean =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /** 供 UI 提示用：内核过旧时的文案。 */
    fun versionWarning(): String? {
        val v = webViewVersion()
        val major = Regex("(\\d+)\\.").find(v)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        return when {
            major < 86 -> "系统 WebView 内核过旧（$v），登录页可能显示异常，建议在应用商店更新 Android System WebView。"
            Build.VERSION.SDK_INT < Build.VERSION_CODES.N -> "系统版本较低（API ${Build.VERSION.SDK_INT}），登录页兼容性可能受影响。"
            else -> null
        }
    }
}
