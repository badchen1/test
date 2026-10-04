package com.kugo.mobile.auth

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException

/** 在线校验的结论。 */
sealed interface ValidateResult {
    /** 凭据有效。 */
    data object Valid : ValidateResult

    /** 明确判定失效（服务端回了「未登录」）。 */
    data object Expired : ValidateResult

    /** 网络不可用，无法判定——此时**不能**登出，否则断网就会把用户踢下线。 */
    data class NetworkError(val message: String) : ValidateResult

    /** 通了但读不懂响应（接口改版 / 风控拦截）。同样倾向于保留登录态。 */
    data class Unknown(val message: String) : ValidateResult
}

/**
 * 登录态有效性校验。
 *
 * 依赖的接口是 QQ音乐 Web 端自己用的 musicu 网关（`GetLoginUserInfo`）。它不是公开 API，
 * 因此这里的判定策略是**宁可信其有**：
 *  - 只有服务端明确回「未登录」才判 Expired；
 *  - 网络错误、响应无法解析一律判 NetworkError / Unknown，交由上层按本地过期时间决定。
 *
 * 这样做的直接目的是避免「地铁里断网 → App 把用户登出 → 用户重新登录」这种最差体验。
 */
object QQSessionValidator {

    private const val TAG = "QQSessionValidator"
    private const val ENDPOINT = "https://u.y.qq.com/cgi-bin/musicu.fcg"

    private const val PAYLOAD =
        "{\"comm\":{\"ct\":24,\"cv\":0,\"format\":\"json\"}," +
            "\"req\":{\"module\":\"music.UserInfo.userInfoServer\"," +
            "\"method\":\"GetLoginUserInfo\",\"param\":{}}}"

    /** 服务端明确表示未登录的响应特征。命中任一即判失效。 */
    private val EXPIRED_HINTS = listOf(
        "\"code\":1000",
        "\"code\": 1000",
        "请先登录",
        "登录已失效",
        "not login",
        "login required",
        "\"subcode\":4001",
    )

    /** 纯本地判定：不联网，只看有效期。用于 UI 静态展示与离线场景。 */
    fun validateLocally(session: QQSession, now: Long = System.currentTimeMillis()): ValidateResult =
        if (session.isExpired(now)) ValidateResult.Expired else ValidateResult.Valid

    /** 联网校验。调用方负责在 IO 之外处理结果，本方法自身已切到 IO。 */
    suspend fun validate(session: QQSession): ValidateResult = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            val url = URL("$ENDPOINT?format=json&data=${URLEncoder.encode(PAYLOAD, "UTF-8")}")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = QQAuthConfig.HTTP_CONNECT_TIMEOUT_MS
                readTimeout = QQAuthConfig.HTTP_READ_TIMEOUT_MS
                instanceFollowRedirects = false
                setRequestProperty("Cookie", session.cookieHeader)
                setRequestProperty("Referer", QQAuthConfig.HOME_URL)
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36",
                )
                setRequestProperty("Accept", "application/json, text/plain, */*")
            }

            val code = conn.responseCode
            if (code !in 200..299) {
                return@withContext ValidateResult.Unknown("HTTP $code")
            }

            val body = conn.inputStream.bufferedReader().use(BufferedReader::readText)
            interpret(body)
        } catch (e: UnknownHostException) {
            ValidateResult.NetworkError("无法解析域名：${e.message}")
        } catch (e: SocketTimeoutException) {
            ValidateResult.NetworkError("连接超时")
        } catch (e: Throwable) {
            Log.w(TAG, "校验请求失败：${e.message}")
            ValidateResult.NetworkError(e.message ?: e.javaClass.simpleName)
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    /**
     * 解析响应。
     *
     * musicu 的响应形如 `{"code":0,"req":{"code":0,"data":{...}}}`。
     * 先判显式的「未登录」特征，再取第一个 `code` 字段作为外层状态码。
     */
    internal fun interpret(body: String): ValidateResult {
        if (body.isBlank()) return ValidateResult.Unknown("空响应")

        if (EXPIRED_HINTS.any { body.contains(it, ignoreCase = true) }) {
            return ValidateResult.Expired
        }

        val code = Regex("\"code\"\\s*:\\s*(-?\\d+)")
            .find(body)?.groupValues?.get(1)?.toIntOrNull()
            ?: return ValidateResult.Unknown("响应中找不到 code 字段")

        return when (code) {
            0 -> ValidateResult.Valid
            1000, 1001, 2000 -> ValidateResult.Expired
            else -> ValidateResult.Unknown("接口返回 code=$code")
        }
    }
}
