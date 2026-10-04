package com.kugo.mobile.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.util.Locale

/**
 * 文件访问桥接层。
 *
 * Android 用 content:// URI 表示用户选中的文件，而 Go 核心库需要真实文件路径。
 * 本类负责把 URI 落地到应用缓存目录，得到可传给 Go 的绝对路径。
 *
 * 采用「拷贝到缓存」策略而非 SAF 直读，原因：
 *  1. Go 侧大量使用 os.Open + Seek 随机访问，SAF 的 ParcelFileDescriptor
 *     在部分实现上不支持随机定位；
 *  2. 解密模块（尤其 KGG / QMC）需要多次 seek 读头部，用真实文件最稳妥；
 *  3. 缓存文件在任务结束后立即清理，不会长期占用空间。
 */
class FileBridge(private val context: Context) {

    /** 工作缓存目录，所有待解密文件先拷贝到这里。 */
    private val workDir: File
        get() = File(context.cacheDir, "kugo_work").apply { mkdirs() }

    /** 解密结果中转目录（当用户未指定输出目录时使用）。 */
    val outputDir: File
        get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "output").apply { mkdirs() }

    data class PickedFile(
        val uri: Uri,
        val displayName: String,
        val size: Long,
    )

    /** 查询 URI 的展示名与大小。 */
    fun query(uri: Uri): PickedFile? {
        val name = displayNameOf(uri) ?: return null
        val size = sizeOf(uri)
        return PickedFile(uri, name, size)
    }

    fun displayNameOf(uri: Uri): String? {
        // content:// 走 OpenableColumns
        if (uri.scheme == "content") {
            runCatching {
                context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && c.moveToFirst()) {
                        return c.getString(idx)
                    }
                }
            }
            // SAF 文档树
            runCatching {
                DocumentFile.fromSingleUri(context, uri)?.name?.let { return it }
            }
            return uri.lastPathSegment
        }
        return uri.path?.let { File(it).name }
    }

    private fun sizeOf(uri: Uri): Long {
        if (uri.scheme == "content") {
            runCatching {
                context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val idx = c.getColumnIndex(OpenableColumns.SIZE)
                    if (idx >= 0 && c.moveToFirst() && !c.isNull(idx)) {
                        return c.getLong(idx)
                    }
                }
            }
        }
        return -1L
    }

    /**
     * 把 URI 拷贝到工作缓存并返回真实路径。
     *
     * 若内容已在私有目录内且可读，直接复用原路径，避免无谓拷贝。
     */
    fun materialize(uri: Uri): Result<File> = runCatching {
        val name = sanitizeName(displayNameOf(uri) ?: "input_${System.currentTimeMillis()}")

        // 已是本地文件且可读，直接使用。
        if (uri.scheme == "file") {
            val f = File(uri.path ?: error("file uri 缺少 path"))
            if (f.isFile && f.canRead()) return@runCatching f
        }
        if (uri.scheme == null) {
            val f = File(uri.toString())
            if (f.isFile && f.canRead()) return@runCatching f
        }

        val target = uniqueFile(workDir, name)
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output, DEFAULT_BUFFER_SIZE) }
        } ?: error("无法打开输入流: $uri")

        check(target.length() > 0) { "文件内容为空: $name" }
        target
    }

    /**
     * 把解密产物写回用户选定的输出目录（SAF 目录树）。
     *
     * @param treeUri 用户通过 ACTION_OPEN_DOCUMENT_TREE 授权的目录
     * @param src     本地已存在的产物文件
     * @param mimeType 目标 MIME，用于让系统正确归类
     */
    fun publishToTree(treeUri: Uri, src: File, mimeType: String): Result<String> = runCatching {
        val tree = DocumentFile.fromTreeUri(context, treeUri)
            ?: error("无法访问输出目录，请重新授权")

        // 同名文件自动加序号，避免覆盖用户已有文件
        var destName = src.name
        if (tree.findFile(destName) != null) {
            val base = destName.substringBeforeLast('.', destName)
            val ext = destName.substringAfterLast('.', "")
            val suffix = if (ext.isNotEmpty()) ".$ext" else ""
            var i = 1
            while (tree.findFile("${base}_$i$suffix") != null) i++
            destName = "${base}_$i$suffix"
        }

        val dest = tree.createFile(mimeType, destName)
            ?: error("无法在目标目录创建文件（可能无写入权限）")

        context.contentResolver.openOutputStream(dest.uri)?.use { output ->
            src.inputStream().use { input -> input.copyTo(output, DEFAULT_BUFFER_SIZE) }
        } ?: error("无法打开输出流")

        dest.uri.toString()
    }

    /** 任务完成后清理工作缓存。 */
    fun cleanupWorkDir() {
        runCatching { workDir.listFiles()?.forEach { it.deleteRecursively() } }
    }

    // --- 内部工具 ---

    private fun sanitizeName(raw: String): String {
        // 去掉路径分隔符与可能引发问题的字符，同时限制长度。
        val cleaned = raw.replace(Regex("[/\\\\:*?\"<>|]"), "_").trim()
        val safe = cleaned.ifBlank { "input_${System.currentTimeMillis()}" }
        return if (safe.length > 120) {
            val ext = safe.substringAfterLast('.', "")
            val head = safe.substring(0, 100)
            if (ext.isNotEmpty()) "$head.$ext" else head
        } else {
            safe
        }
    }

    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        val suffix = if (ext.isNotEmpty()) ".$ext" else ""
        var i = 1
        while (f.exists()) {
            f = File(dir, "${base}_$i$suffix")
            i++
        }
        return f
    }

    companion object {
        /** 由扩展名推断 MIME，用于 SAF 写入时让系统正确归类。 */
        fun mimeOf(fileName: String): String = when (fileName.lowercase(Locale.ROOT).substringAfterLast('.', "")) {
            "mp3" -> "audio/mpeg"
            "flac" -> "audio/flac"
            "wav" -> "audio/x-wav"
            "ogg" -> "audio/ogg"
            "m4a" -> "audio/mp4"
            "aac" -> "audio/aac"
            else -> "application/octet-stream"
        }

        /** 从树 URI 提取一个可读的目录名，用于 UI 展示。 */
        fun treeDisplayName(treeUri: Uri): String =
            runCatching {
                DocumentsContract.getTreeDocumentId(treeUri)
                    .substringAfterLast(':')
                    .ifBlank { treeUri.lastPathSegment ?: "已选目录" }
            }.getOrDefault("已选目录")
    }
}
