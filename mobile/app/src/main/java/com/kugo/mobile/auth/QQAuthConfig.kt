package com.kugo.mobile.auth

import android.content.Context
import android.os.Build
import android.webkit.WebSettings
import java.net.URLEncoder

/**
 * QQ 网页登录的全部可调常量。
 *
 * 把 URL、appid、Cookie 字段名、超时策略集中在这里，是为了在 QQ 侧调整接口时
 * 只改一处就能适配，而不是全工程搜索散落的字符串字面量。
 */
object QQAuthConfig {

    // --- 登录入口 ---

    /**
     * 登录页 appid。
     *
     * 注意：这不是本应用的 appid，而是 QQ互联 的「网页登录」固定 appid（716027609），
     * 所有走 ptlogin2 免密登录的第三方站点用的都是它。
     */
    const val PT_APPID = "716027609"

    /** 目标站点的 QQ互联第三方应用 id。100497308 = QQ音乐。 */
    const val PT_3RD_AID = "100497308"

    /** 登录成功后的落点（s_url）。必须是目标站点自己的页面，否则拿不到站点的登录 Cookie。 */
    const val PROFILE_URL = "https://y.qq.com/portal/profile.html"

    /** 兜底入口：直接把首页给用户，让他在页面内点登录。 */
    const val HOME_URL = "https://y.qq.com/"

    /**
     * 免密登录页。用户此前在 QQ 侧留过登录态的话，这里会直接 302 回 s_url 完成静默续期。
     */
    val LOGIN_URL: String = buildString {
        append("https://xui.ptlogin2.qq.com/cgi-bin/xlogin")
        append("?appid=").append(PT_APPID)
        append("&daid=383&style=33&hide_title_bar=1&hide_border=1&target=self")
        append("&s_url=").append(urlEncode(PROFILE_URL))
        append("&pt_3rd_aid=").append(PT_3RD_AID)
    }

    /** 版本升级路径（QQ互联的新版登录页），主入口失败时兜底。 */
    val LOGIN_URL_FALLBACK: String = buildString {
        append("https://graph.qq.com/oauth2.0/show?which=Login&display=pc")
        append("&response_type=code&client_id=").append(PT_3RD_AID)
        append("&redirect_uri=").append(urlEncode(PROFILE_URL))
    }

    // --- Cookie 抓取 ---

    /**
     * 读取 Cookie 时逐个查询的 URL。
     *
     * 为什么是多个：浏览器 Cookie 是按 domain 隔离的，`qqmusic_key` 可能只写在
     * `.y.qq.com`，而 `skey` / `p_skey` 写在 `.qq.com`。CookieManager.getCookie(url)
     * 只返回「domain 能匹配该 url」的那些，因此必须对每个业务域名各查一次再合并。
     */
    val COOKIE_URLS: List<String> = listOf(
        "https://qq.com/",
        "https://graph.qq.com/",
        "https://y.qq.com/",
        "https://u.y.qq.com/",
        "https://c.y.qq.com/",
        "https://i.y.qq.com/",
    )

    /** 登录成功的充分条件：以下任一个 Cookie 存在即视为已登录。 */
    val LOGIN_MARKER_COOKIES: List<String> = listOf("qqmusic_key", "qm_keyst")

    /** 需要落盘的业务字段（除标记 Cookie 外）。缺失不阻断登录，但会记录在会话里备用。 */
    val IMPORTANT_COOKIES: List<String> = listOf(
        "uin", "qqmusic_uin", "euin", "wxuin",
        "skey", "p_skey",
        "psrf_qqunionid", "psrf_qqopenid",
        "psrf_access_token_expiresAt", "psrf_musickey_createtime",
        "psrf_qqrefresh_token", "psrf_qqnew_uin",
        "wxopenid", "wxrefresh_token", "qm_keyst",
    )

    /**
     * 抓取时机。onPageFinished 触发时，登录成功的 Set-Cookie 可能还在
     * 子 iframe / XHR 里没落盘，因此用一串递增延时反复重试。
     */
    val CAPTURE_DELAYS_MS: LongArray = longArrayOf(0L, 400L, 1200L, 2500L, 4000L)

    /** 用户操作时限：超过则判定超时，避免 WebView 页面挂死时永远转圈。 */
    const val SESSION_TIMEOUT_MS: Long = 5 * 60 * 1000L

    /** 静默续期时限：复用旧 Cookie 走免密登录，超过这个时间还没跳走就认为 Cookie 已死。 */
    const val SILENT_REFRESH_TIMEOUT_MS: Long = 12 * 1000L

    /** 网络请求超时（校验接口）。 */
    const val HTTP_CONNECT_TIMEOUT_MS = 8_000
    const val HTTP_READ_TIMEOUT_MS = 8_000

    // --- 会话有效期 ---

    /** 拿不到任何过期信息时的兜底有效期。 */
    const val DEFAULT_TTL_MS: Long = 24 * 60 * 60 * 1000L

    /** `psrf_musickey_createtime` 之后的音乐密钥有效期。 */
    const val MUSIC_KEY_TTL_MS: Long = 24 * 60 * 60 * 1000L

    /** 剩余有效期低于此值时提前提示续期。 */
    const val RENEW_AHEAD_MS: Long = 60 * 60 * 1000L

    // --- 风控 / 二次验证域名 ---

    /**
     * 命中这些域名说明用户正处在验证码 / 二次验证流程中。
     * 我们不做任何拦截，只是在 UI 上换一句提示，让用户安心在页面内完成。
     */
    val VERIFY_HOSTS: List<String> = listOf(
        "captcha.qq.com",
        "t.captcha.qq.com",
        "ssl.ptlogin2.qq.com",
        "verify.qq.com",
        "accounts.qq.com",
        "imgcache.qq.com/ptlogin",
    )

    fun isVerifyUrl(url: String?): Boolean =
        !url.isNullOrEmpty() && VERIFY_HOSTS.any { url.contains(it, ignoreCase = true) }

    // --- User-Agent ---

    /**
     * 构造登录用 UA。
     *
     * 依据：ptlogin2 会根据 UA 判断客户端类型，命中 "MicroMessenger" / "QQ/" 会走
     * 唤起 App 的分支；命中 "; wv)" 又被识别为「嵌入式 WebView」，风控等级更高。
     * 因此策略是「以系统自带 Chrome 内核 UA 为底，剥掉 WebView 标记」，
     * 这样品牌型号与 Chrome 版本都跟真实浏览器一致，触发滑块的概率最低。
     */
    fun userAgent(context: Context): String {
        val raw = runCatching { WebSettings.getDefaultUserAgent(context) }.getOrNull()
        if (raw.isNullOrBlank()) return FALLBACK_UA
        return raw
            .replace("; wv)", ")")
            .replace("Version/4.0 ", "")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
    }

    private val FALLBACK_UA =
        "Mozilla/5.0 (Linux; Android ${Build.VERSION.RELEASE}) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"

    private fun urlEncode(s: String): String =
        runCatching { URLEncoder.encode(s, "UTF-8") }.getOrDefault(s)
}
