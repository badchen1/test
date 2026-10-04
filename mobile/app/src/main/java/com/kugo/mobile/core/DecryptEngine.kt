package com.kugo.mobile.core

import gocore.Gocore
import gocore.Progress
import java.io.File/**
 * 解密结果。
 *
 * 用密封类表达三种终态，UI 层可直接 when 分支渲染，避免到处判空。
 */
sealed interface DecryptResult {
    /** 解密成功，[outPath] 为产物绝对路径。 */
    data class Success(val outPath: String) : DecryptResult

    /** 需要用户先导入密钥。 */
    data class NeedKey(val reason: String) : DecryptResult

    /** 其他失败。 */
    data class Failure(val code: Int, val message: String) : DecryptResult
}

/** 一种受支持的加密格式。 */
data class AudioFormat(
    val ext: String,
    val displayName: String,
    val platform: String,
    val needExtraKey: Boolean,
)

/**
 * Go 核心库的 Kotlin 门面。
 *
 * 职责：
 *  1. 把 gocore 返回的 int32 错误码翻译成语义化结果；
 *  2. 屏蔽 gomobile 的 Progress 接口细节，对上层暴露 lambda；
 *  3. 集中处理凭据导入。
 *
 * 本类所有方法都是阻塞的，调用方负责切到 IO 线程。
 */
object DecryptEngine {

    // 与 gocore/decryptor.go 顶部的 Code* 常量严格一致。
    //
    // 这里刻意本地定义而不用 Gocore.CodeOK：生成类的常量名会随 gomobile
    // 版本变化，本地定义可避免无谓的编译期耦合。若 Go 侧调整码值，
    // 此处必须同步（verify-bindings.sh 无法覆盖这一层，靠测试兜底）。
    private const val CODE_OK = 0
    private const val CODE_UNSUPPORTED_FORMAT = 1
    private const val CODE_MISSING_KEY = 2
    private const val CODE_DECRYPT_FAILED = 3
    private const val CODE_IO_ERROR = 4
    private const val CODE_CANCELLED = 5

    /** 返回受支持的格式列表。失败时降级为空列表。 */
    fun supportedFormats(): List<AudioFormat> =
        runCatching { JsonMini.parseFormats(Gocore.supportedFormats()) }
            .getOrElse { emptyList() }

    /** 判断文件名或扩展名是否受支持。 */
    fun isSupported(nameOrExt: String): Boolean =
        runCatching { Gocore.isSupported(nameOrExt) }.getOrDefault(false)

    /** 嗅探真实音频扩展名，如 ".flac"；无法识别返回空串。 */
    fun probeAudioExt(path: String): String =
        runCatching { Gocore.probeAudioExt(path) }.getOrDefault("")

    /**
     * 解密单个文件。
     *
     * @param inPath  加密文件绝对路径
     * @param outDir  输出目录；为空则输出到源文件同目录
     * @param onProgress 进度回调 (已处理字节, 总字节)，总字节为 -1 表示未知
     */
    fun decrypt(
        inPath: String,
        outDir: String?,
        onProgress: ((Long, Long) -> Unit)? = null,
    ): DecryptResult {
        val cb = onProgress?.let { fn ->
            object : Progress {
                override fun onProgress(done: Long, total: Long) {
                    fn(done, total)
                }
            }
        }

        return try {
            // gomobile 生成的 Java 方法在 Kotlin 里可直接用属性语法访问字段，
            // 但方法本身声明了 throws Exception，必须包在 try 里。
            val r = if (outDir.isNullOrBlank()) {
                Gocore.decryptToSameDir(inPath, cb)
            } else {
                Gocore.decryptToDir(inPath, outDir, cb)
            }
            mapResult(r.code.toInt(), r.outPath ?: "", r.message ?: "")
        } catch (t: Throwable) {
            DecryptResult.Failure(CODE_DECRYPT_FAILED, t.message ?: t.javaClass.simpleName)
        }
    }

    private fun mapResult(code: Int, outPath: String, message: String): DecryptResult =
        when (code) {
            CODE_OK -> DecryptResult.Success(outPath)
            CODE_MISSING_KEY -> DecryptResult.NeedKey(message)
            else -> DecryptResult.Failure(code, message)
        }

    // --- 凭据导入 ---

    /** 导入酷狗 kgg.key 文件。 */
    fun importKggKeyFile(path: String): Result<Unit> =
        runCatching { Gocore.setKGGKeyFile(path) }
            .map { }

    /** 导入酷狗 KGMusicV3.db 数据库。 */
    fun importKggDatabase(path: String): Result<Unit> =
        runCatching { Gocore.setKGGDatabaseFile(path) }
            .map { }

    /** 直接粘贴酷狗密钥文本（每行 `hash$ekey`）。 */
    fun importKggKeysText(text: String) {
        runCatching { Gocore.setKGGKeys(text) }
    }

    /** 粘贴 QQ 音乐 ekey（每行 `mediaMid$ekey` 或 `文件名$ekey`）。 */
    fun importQmcKeysText(text: String) {
        runCatching { Gocore.setQMCKeys(text) }
    }

    /** 保存 QQ 音乐 Cookie 备用。 */
    fun saveQmcCookie(cookie: String) {
        runCatching { Gocore.setQMCCookie(cookie) }
    }

    fun clearCredentials() {
        runCatching { Gocore.clearCredentials() }
    }

    fun hasKggKeys(): Boolean = runCatching { Gocore.hasKGGKeys() }.getOrDefault(false)

    fun hasQmcKeys(): Boolean = runCatching { Gocore.hasQMCKeys() }.getOrDefault(false)

    /** 校验文件是否存在且可读，避免把无效路径喂给 Go 层。 */
    fun ensureReadable(path: String): Boolean {
        val f = File(path)
        return f.isFile && f.canRead()
    }
}
