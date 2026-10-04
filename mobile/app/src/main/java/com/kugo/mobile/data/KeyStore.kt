package com.kugo.mobile.data

import android.content.Context
import java.io.File

/**
 * 密钥本地持久化。
 *
 * 存在应用私有目录（/data/data/<pkg>/files/keys），其他应用无法读取。
 * 不加密是刻意的取舍：密钥本身已经是对称密钥材料，再套一层固定密钥的
 * 本地加密只是「混淆」，反而给人虚假的安全感；真正的边界是 Android 的
 * 应用沙箱。若设备已 root，任何方案都挡不住。
 *
 * 文件布局：
 *   kgg.txt   —— 每行 `hash$ekey`
 *   qmc.txt   —— 每行 `mediaMid$ekey`
 *   qmc_cookie.txt —— QQ 音乐 Cookie（可选）
 */
class KeyStore(context: Context) {

    private val dir: File = File(context.filesDir, "keys").apply { mkdirs() }

    private val kggFile get() = File(dir, "kgg.txt")
    private val qmcFile get() = File(dir, "qmc.txt")
    private val cookieFile get() = File(dir, "qmc_cookie.txt")

    /** 酷狗 KGMusicV3.db 的本地副本路径。不存在时返回路径但文件未创建。 */
    fun kggDbPath(): String = File(dir, "KGMusicV3.db").absolutePath

    /** 返回已保存的酷狗数据库文件，若无则返回 null。 */
    fun kggDatabase(): File? = File(kggDbPath()).takeIf { it.isFile }

    /** 保存酷狗密钥文本（覆盖写入）。 */
    fun saveKgg(text: String) = writeAtomically(kggFile, text)

    /** 保存 QQ 音乐 ekey 文本（覆盖写入）。 */
    fun saveQmc(text: String) = writeAtomically(qmcFile, text)

    fun saveCookie(text: String) = writeAtomically(cookieFile, text)

    fun loadKgg(): String = readOrEmpty(kggFile)

    fun loadQmc(): String = readOrEmpty(qmcFile)

    /** 清空全部持久化凭据。 */
    fun clear() {
        listOf(kggFile, qmcFile, cookieFile).forEach { runCatching { it.delete() } }
    }

    fun hasAny(): Boolean = kggFile.exists() || qmcFile.exists()

    // --- 内部 ---

    /**
     * 原子写入：先写临时文件再 rename。
     *
     * 避免写入过程中进程被杀导致密钥文件半截损坏——那种情况下
     * 用户会遇到「密钥明明导入过却解不开」的诡异问题。
     */
    private fun writeAtomically(target: File, content: String) {
        runCatching {
            val tmp = File(target.parentFile, "${target.name}.tmp")
            tmp.writeText(content)
            if (target.exists()) target.delete()
            tmp.renameTo(target)
        }
    }

    private fun readOrEmpty(f: File): String =
        runCatching { if (f.isFile) f.readText() else "" }.getOrDefault("")
}
