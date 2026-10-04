package com.kugo.mobile.auth

/**
 * 从 Cookie 串中抽取 QQ 登录凭据。
 *
 * 纯函数、无 Android 依赖，方便单测。
 */
object QQCookieExtractor {

    /** 解析 `k=v; k=v` 形式的 Cookie 串。 */
    fun parse(header: String): Map<String, String> {
        if (header.isBlank()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        header.split(';').forEach { part ->
            val idx = part.indexOf('=')
            if (idx <= 0) return@forEach
            val key = part.substring(0, idx).trim()
            val value = part.substring(idx + 1).trim()
            if (key.isNotEmpty()) out[key] = value
        }
        return out
    }

    /**
     * 合并多个域名下的 Cookie，后出现的覆盖先出现的。
     *
     * 调用方应按「域越具体越靠后」的顺序传入，这样 `y.qq.com` 上的
     * `qqmusic_key` 会覆盖 `.qq.com` 上的旧值。
     */
    fun mergeOrdered(headers: List<String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        headers.forEach { h -> parse(h).forEach { (k, v) -> out[k] = v } }
        return out
    }

    /** 展开为 HTTP `Cookie` 请求头。 */
    fun toHeader(fields: Map<String, String>): String =
        fields.entries.filter { it.value.isNotEmpty() }
            .joinToString("; ") { "${it.key}=${it.value}" }

    /** 是否含登录标记 Cookie。 */
    fun hasLoginMarker(fields: Map<String, String>): Boolean =
        QQAuthConfig.LOGIN_MARKER_COOKIES.any { !fields[it].isNullOrEmpty() }

    fun pickUin(fields: Map<String, String>): String =
        sequenceOf("uin", "qqmusic_uin", "wxuin", "euin")
            .mapNotNull { fields[it]?.takeIf { v -> v.isNotEmpty() && v != "0" } }
            .firstOrNull()
            .orEmpty()

    fun pickMusicKey(fields: Map<String, String>): String =
        QQAuthConfig.LOGIN_MARKER_COOKIES
            .mapNotNull { fields[it]?.takeIf { v -> v.isNotEmpty() } }
            .firstOrNull()
            .orEmpty()

    /**
     * 由 Cookie 字段构造会话；缺少关键字段时返回 null（表示「还没登录成功」）。
     */
    fun buildSession(
        cookieHeader: String,
        fields: Map<String, String> = parse(cookieHeader),
        now: Long = System.currentTimeMillis(),
    ): QQSession? {
        if (!hasLoginMarker(fields)) return null
        val musicKey = pickMusicKey(fields)
        if (musicKey.isEmpty()) return null

        val keep = LinkedHashMap<String, String>()
        fields.forEach { (k, v) ->
            if (k in QQAuthConfig.LOGIN_MARKER_COOKIES || k in QQAuthConfig.IMPORTANT_COOKIES) {
                keep[k] = v
            }
        }

        return QQSession(
            uin = pickUin(fields),
            musicKey = musicKey,
            cookieHeader = cookieHeader,
            capturedAt = now,
            expiresAt = resolveExpiresAt(fields, now),
            fields = keep,
        )
    }

    /**
     * 推断失效时刻。
     *
     * 取值优先级（都拿到的话取最早的那个，保守估计）：
     *  1. `psrf_musickey_createtime` + 24h —— 音乐密钥自身的生命周期；
     *  2. `psrf_access_token_expiresAt` —— QQ互联 access token 的到期时间戳；
     *  3. 兜底：抓取时刻 + 24h。
     */
    fun resolveExpiresAt(fields: Map<String, String>, now: Long): Long {
        val candidates = mutableListOf<Long>()

        epochMillis(fields["psrf_musickey_createtime"])
            ?.let { candidates += it + QQAuthConfig.MUSIC_KEY_TTL_MS }

        epochMillis(fields["psrf_access_token_expiresAt"])
            ?.let { candidates += it }

        return candidates.filter { it > now }.minOrNull()
            ?: (now + QQAuthConfig.DEFAULT_TTL_MS)
    }

    /** QQ 返回的时间戳有的是秒、有的是毫秒，用数量级做判定。 */
    private fun epochMillis(raw: String?): Long? {
        val v = raw?.trim()?.takeIf { it.isNotEmpty() }?.toLongOrNull() ?: return null
        return if (v > 1_000_000_000_000L) v else v * 1000L
    }

    /**
     * 脱敏：保留字段名，值只露头尾。
     * 用于 UI 与日志，避免完整 Cookie（等价于账号密码）被截图泄露。
     */
    fun mask(cookieHeader: String): String =
        parse(cookieHeader).entries.joinToString("; ") { (k, v) ->
            "$k=${maskValue(v)}"
        }

    private fun maskValue(v: String): String = when {
        v.isEmpty() -> ""
        v.length <= 6 -> "*".repeat(v.length)
        else -> v.take(3) + "*".repeat(6) + v.takeLast(2)
    }
}
