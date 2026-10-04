package com.kugo.mobile.auth

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 一次成功的 QQ 登录所产出的会话快照。
 *
 * [cookieHeader] 是展开后的 `k=v; k=v` 串，可直接作为 HTTP `Cookie` 请求头使用，
 * 也是注入 Go 层（`Gocore.setQMCCookie`）的唯一入参。
 */
data class QQSession(
    /** 登录账号，取 `uin` / `qqmusic_uin` / `wxuin` 中第一个非空值。 */
    val uin: String,
    /** 音乐密钥，`qqmusic_key` 或 `qm_keyst`。 */
    val musicKey: String,
    /** 完整 Cookie 串（含所有已抓到的字段）。 */
    val cookieHeader: String,
    /** 抓取时刻（毫秒）。 */
    val capturedAt: Long,
    /** 预估失效时刻（毫秒）。 */
    val expiresAt: Long,
    /** 结构化后的全部字段，便于 UI 展示与排查。 */
    val fields: Map<String, String> = emptyMap(),
) {

    fun isExpired(now: Long = System.currentTimeMillis()): Boolean = now >= expiresAt

    fun remainingMs(now: Long = System.currentTimeMillis()): Long = expiresAt - now

    /** 是否已进入「建议提前续期」窗口。 */
    fun shouldRenewSoon(now: Long = System.currentTimeMillis()): Boolean =
        remainingMs(now) <= QQAuthConfig.RENEW_AHEAD_MS

    /** 脱敏后的 Cookie，用于 UI / 日志，避免完整凭据出现在截图里。 */
    val maskedCookie: String get() = QQCookieExtractor.mask(cookieHeader)

    val capturedAtText: String get() = format(capturedAt)

    val expiresAtText: String get() = format(expiresAt)

    /** 剩余有效期的中文描述。 */
    fun remainingText(now: Long = System.currentTimeMillis()): String {
        val ms = remainingMs(now)
        if (ms <= 0) return "已过期"
        val minutes = ms / 60_000
        return when {
            minutes < 60 -> "剩余 ${minutes} 分钟"
            minutes < 60 * 24 -> "剩余 ${minutes / 60} 小时"
            else -> "剩余 ${minutes / (60 * 24)} 天"
        }
    }

    companion object {
        private val FMT = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
        private fun format(ms: Long): String =
            runCatching { FMT.format(Date(ms)) }.getOrDefault("-")
    }
}
