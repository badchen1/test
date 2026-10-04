package com.kugo.mobile.core

/**
 * 极简 JSON 解析器，仅用于解析 Go 侧返回的格式列表。
 *
 * 之所以不用 org.json 或 Gson：
 *  - org.json 在 JVM 单元测试环境不可用（Android stub 会抛异常）；
 *  - Gson / Moshi 会为这点用途引入额外依赖。
 * 输入格式固定且简单，手写解析更可控、零依赖、可测。
 */
internal object JsonMini {

    /**
     * 解析形如以下的数组：
     * [{"ext":".ncm","displayName":"网易云 NCM","platform":"网易云音乐","needExtraKey":false}, ...]
     */
    fun parseFormats(json: String): List<AudioFormat> {
        val out = mutableListOf<AudioFormat>()
        var i = skipWs(json, 0)
        if (i >= json.length || json[i] != '[') return emptyList()
        i++

        while (i < json.length) {
            i = skipWs(json, i)
            if (i >= json.length) break
            when (json[i]) {
                ']' -> return out
                ',' -> { i++; continue }
                '{' -> {
                    val res = parseFormatObject(json, i)
                    i = res.next
                    res.format?.let(out::add)
                }
                else -> i++ // 容错：跳过无法识别的字符
            }
        }
        return out
    }

    private data class ObjResult(val format: AudioFormat?, val next: Int)

    private fun parseFormatObject(json: String, start: Int): ObjResult {
        var i = start + 1 // 跳过 '{'
        var ext = ""
        var displayName = ""
        var platform = ""
        var needExtraKey = false

        while (i < json.length) {
            i = skipWs(json, i)
            if (i >= json.length) break
            when (json[i]) {
                '}' -> {
                    i++
                    return ObjResult(
                        AudioFormat(ext, displayName, platform, needExtraKey),
                        i,
                    )
                }
                ',' -> { i++; continue }
                '"' -> {
                    val keyRes = parseString(json, i) ?: return ObjResult(null, json.length)
                    i = skipWs(json, keyRes.next)
                    if (i >= json.length || json[i] != ':') return ObjResult(null, json.length)
                    i = skipWs(json, i + 1)

                    when (keyRes.value) {
                        "ext" -> parseString(json, i)?.let { ext = it.value; i = it.next }
                        "displayName" -> parseString(json, i)?.let { displayName = it.value; i = it.next }
                        "platform" -> parseString(json, i)?.let { platform = it.value; i = it.next }
                        "needExtraKey" -> {
                            val b = parseBool(json, i)
                            needExtraKey = b.first
                            i = b.second
                        }
                        else -> i = skipValue(json, i)
                    }
                }
                else -> i++
            }
        }
        return ObjResult(null, i)
    }

    private data class StrResult(val value: String, val next: Int)

    private fun parseString(json: String, start: Int): StrResult? {
        if (start >= json.length || json[start] != '"') return null
        val sb = StringBuilder()
        var i = start + 1
        while (i < json.length) {
            when (val c = json[i]) {
                '"' -> return StrResult(sb.toString(), i + 1)
                '\\' -> {
                    i++
                    if (i >= json.length) return null
                    when (json[i]) {
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        'r' -> sb.append('\r')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'u' -> {
                            if (i + 4 >= json.length) return null
                            val hex = json.substring(i + 1, i + 5)
                            sb.append(hex.toIntOrNull(16)?.toChar() ?: '?')
                            i += 4
                        }
                        else -> sb.append(json[i])
                    }
                    i++
                }
                else -> { sb.append(c); i++ }
            }
        }
        return null
    }

    private fun parseBool(json: String, start: Int): Pair<Boolean, Int> {
        if (json.startsWith("true", start)) return true to (start + 4)
        if (json.startsWith("false", start)) return false to (start + 5)
        return false to start
    }

    /** 跳过一个未知字段的值（字符串 / 数字 / 对象 / 数组 / 字面量）。 */
    private fun skipValue(json: String, start: Int): Int {
        val i = skipWs(json, start)
        if (i >= json.length) return i
        return when (json[i]) {
            '"' -> parseString(json, i)?.next ?: (i + 1)
            '{', '[' -> {
                var depth = 0
                var j = i
                while (j < json.length) {
                    when (json[j]) {
                        '{', '[' -> depth++
                        '}', ']' -> {
                            depth--
                            if (depth == 0) return j + 1
                        }
                        '"' -> {
                            val s = parseString(json, j)
                            if (s != null) { j = s.next; continue }
                        }
                    }
                    j++
                }
                j
            }
            else -> {
                var j = i
                while (j < json.length && json[j] !in ",}]") j++
                j
            }
        }
    }

    private fun skipWs(json: String, start: Int): Int {
        var i = start
        while (i < json.length && json[i].isWhitespace()) i++
        return i
    }
}
