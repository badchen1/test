package com.kugo.mobile.auth

import android.os.Build
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView

/**
 * CookieManager / WebStorage 的统一门面。
 *
 * 为什么单独抽一层：
 *  1. CookieManager 在 WebView 尚未初始化时调用会抛异常，集中兜底比到处 try 干净；
 *  2. 「合并多域名 Cookie」的逻辑只应有一处实现，登录判定、会话构建、退出清理都复用它；
 *  3. 所有方法都必须在主线程调用（WebView 系 API 的硬性约束），集中标注便于审查。
 *
 * 注意：CookieManager 是**进程级单例**，同一 App 内所有 WebView 共享同一份 Cookie。
 * 如果将来引入其他 WebView（例如内嵌帮助页），要么接受共享，要么各自用独立的
 * Cookie 注入方案（androidx.webkit 的 WebViewCompat + 自定义拦截）。
 */
object QQWebCookieJar {

    private fun manager(): CookieManager? = runCatching { CookieManager.getInstance() }.getOrNull()

    /** 在 WebView 创建后调用，打开 Cookie 与第三方 Cookie。 */
    fun configure(webView: WebView) {
        val cm = manager() ?: return
        runCatching {
            // 登录页依赖 Cookie，必须开。
            cm.setAcceptCookie(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                // 关键项：ptlogin2 的登录结果是由跨域 iframe 写回的，
                // 关掉第三方 Cookie 会导致「授权成功但站点拿不到 key」。
                cm.setAcceptThirdPartyCookies(webView, true)
            }
        }
    }

    /** 单个 URL 可见的 Cookie 串。 */
    fun rawCookie(url: String): String =
        runCatching { manager()?.getCookie(url).orEmpty() }.getOrDefault("")

    /**
     * 合并所有业务域名下的 Cookie。
     * 按 [QQAuthConfig.COOKIE_URLS] 的顺序覆盖，越具体的域越靠后、优先级越高。
     */
    fun mergedFields(): Map<String, String> =
        QQCookieExtractor.mergeOrdered(QQAuthConfig.COOKIE_URLS.map(::rawCookie))

    /** 合并后的 HTTP Cookie 头。 */
    fun mergedHeader(): String = QQCookieExtractor.toHeader(mergedFields())

    /**
     * 把内存中的 Cookie 落盘。
     * WebView 的 Cookie 有内存态，进程被系统杀死时未落盘的部分会丢，
     * 抓到凭据后必须立刻 flush。
     */
    fun flush() {
        runCatching { manager()?.flush() }
    }

    /** 清空全部 Cookie（退出登录）。 */
    fun clearAll() {
        runCatching {
            manager()?.removeAllCookies(null)
            manager()?.flush()
        }
    }

    /** 清空 localStorage / sessionStorage / IndexedDB，避免残留登录痕迹。 */
    fun clearWebStorage() {
        runCatching { WebStorage.getInstance().deleteAllData() }
    }

    /** 清空表单记录与 HTTP 缓存中的应用私有部分（登录后调用，避免把账号密码留在磁盘上）。 */
    fun clearFormData(webView: WebView?) {
        runCatching {
            webView?.clearFormData()
            webView?.clearHistory()
        }
    }
}
