package com.kugo.mobile.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * QQ 会话的本地持久化。
 *
 * 与同目录下的 `kgg.txt` / `qmc.txt`（明文）不同，这里是**加密落盘**。
 * 理由是敏感级别不同：ekey 只能解密已经躺在用户设备上的某个文件，
 * 而 QQ Cookie 等价于账号通行证——拿到它就能以用户身份调用 QQ音乐的全部接口。
 * 一旦设备被 root 或应用目录被备份带走，明文存储的后果远大于前者。
 *
 * 方案：AndroidKeyStore 生成 AES-256-GCM 密钥，密钥材料永不离开 TEE/StrongBox，
 * 应用只能请求它做加解密，无法导出。文件被拷走也解不开。
 *
 * 降级：极少数机型（部分模拟器、Keystore 异常的 ROM）无法完成加密，
 * 此时退回明文文件并做标记，保证功能可用，同时在诊断信息里标明状态。
 */
class QQSessionStore(context: Context) {

    companion object {
        private const val TAG = "QQSessionStore"
        private const val KEY_ALIAS = "kugo.qq.session.v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val FORMAT_VERSION = 1
        private const val PLAIN_MARKER = "#plain#"
    }

    private val dir: File = File(context.filesDir, "keys").apply { runCatching { mkdirs() } }
    private val encryptedFile = File(dir, "qq_session.bin")
    private val plainFile = File(dir, "qq_session.txt")

    /** 是否存在可加载的会话。 */
    fun exists(): Boolean = encryptedFile.isFile || plainFile.isFile

    /** 是否采用了加密存储（供诊断信息展示）。 */
    fun isEncryptedAtRest(): Boolean =
        encryptedFile.isFile && encryptedFile.length() > 0

    fun save(session: QQSession) {
        val payload = encode(session)
        val cipherText = runCatching { encrypt(payload) }.getOrNull()

        if (cipherText != null) {
            writeAtomically(encryptedFile, cipherText)
            runCatching { plainFile.delete() }
        } else {
            Log.w(TAG, "Keystore 不可用，降级为明文存储")
            writeAtomically(plainFile, (PLAIN_MARKER + payload).toByteArray(Charsets.UTF_8))
            runCatching { encryptedFile.delete() }
        }
    }

    fun load(): QQSession? {
        val payload = readEncrypted() ?: readPlain() ?: return null
        return runCatching { decode(payload) }
            .onFailure { Log.w(TAG, "会话解析失败：${it.message}") }
            .getOrNull()
    }

    fun clear() {
        runCatching { encryptedFile.delete() }
        runCatching { plainFile.delete() }
    }

    // --- 编解码 ---

    private fun encode(s: QQSession): String = buildString {
        appendLine("v=$FORMAT_VERSION")
        appendLine("uin=${b64(s.uin)}")
        appendLine("musicKey=${b64(s.musicKey)}")
        appendLine("cookie=${b64(s.cookieHeader)}")
        appendLine("capturedAt=${s.capturedAt}")
        appendLine("expiresAt=${s.expiresAt}")
        s.fields.forEach { (k, v) -> appendLine("f.$k=${b64(v)}") }
    }

    private fun decode(payload: String): QQSession? {
        var uin = ""
        var musicKey = ""
        var cookie = ""
        var capturedAt = 0L
        var expiresAt = 0L
        val fields = LinkedHashMap<String, String>()

        payload.lineSequence().forEach { line ->
            val idx = line.indexOf('=')
            if (idx <= 0) return@forEach
            val key = line.substring(0, idx)
            val value = line.substring(idx + 1)
            when {
                key == "uin" -> uin = unb64(value)
                key == "musicKey" -> musicKey = unb64(value)
                key == "cookie" -> cookie = unb64(value)
                key == "capturedAt" -> capturedAt = value.toLongOrNull() ?: 0L
                key == "expiresAt" -> expiresAt = value.toLongOrNull() ?: 0L
                key.startsWith("f.") -> fields[key.removePrefix("f.")] = unb64(value)
            }
        }

        if (cookie.isEmpty() || musicKey.isEmpty()) return null
        return QQSession(
            uin = uin,
            musicKey = musicKey,
            cookieHeader = cookie,
            capturedAt = capturedAt,
            expiresAt = expiresAt,
            fields = fields,
        )
    }

    private fun b64(s: String): String =
        Base64.encodeToString(s.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    private fun unb64(s: String): String =
        runCatching { String(Base64.decode(s, Base64.NO_WRAP), Charsets.UTF_8) }.getOrDefault("")

    // --- 加解密 ---

    /** 输出格式：[版本 1B][IV 长度 1B][IV][密文+Tag]。 */
    private fun encrypt(plain: String): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return ByteArray(2 + iv.size + body.size).also { out ->
            out[0] = FORMAT_VERSION.toByte()
            out[1] = iv.size.toByte()
            System.arraycopy(iv, 0, out, 2, iv.size)
            System.arraycopy(body, 0, out, 2 + iv.size, body.size)
        }
    }

    private fun decrypt(data: ByteArray): String? {
        if (data.size < 3) return null
        val ivLen = data[1].toInt()
        if (data.size < 2 + ivLen) return null
        val iv = data.copyOfRange(2, 2 + ivLen)
        val body = data.copyOfRange(2 + ivLen, data.size)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(body), Charsets.UTF_8)
    }

    /** 取（或首次生成）AndroidKeyStore 中的 AES 密钥。 */
    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // 不要求用户认证：后台续期时不应弹出指纹框。
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    // --- IO ---

    private fun readEncrypted(): String? {
        if (!encryptedFile.isFile) return null
        val data = runCatching { encryptedFile.readBytes() }.getOrNull() ?: return null
        return runCatching { decrypt(data) }
            .onFailure { Log.w(TAG, "解密失败（密钥可能已被系统吊销）：${it.message}") }
            .getOrNull()
    }

    private fun readPlain(): String? {
        if (!plainFile.isFile) return null
        val text = runCatching { plainFile.readText() }.getOrNull() ?: return null
        return text.takeIf { it.startsWith(PLAIN_MARKER) }?.removePrefix(PLAIN_MARKER)
    }

    /** 先写临时文件再改名，避免写一半被系统杀掉进程造成文件损坏。 */
    private fun writeAtomically(target: File, bytes: ByteArray) {
        runCatching {
            val tmp = File(target.parentFile, "${target.name}.tmp")
            tmp.writeBytes(bytes)
            if (target.exists()) target.delete()
            tmp.renameTo(target)
        }.onFailure { Log.w(TAG, "写入 ${target.name} 失败：${it.message}") }
    }
}
