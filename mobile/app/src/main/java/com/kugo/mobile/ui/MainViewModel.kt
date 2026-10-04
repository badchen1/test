package com.kugo.mobile.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kugo.mobile.auth.QQAccountState
import com.kugo.mobile.auth.QQSessionManager
import com.kugo.mobile.auth.VerifyFlag
import com.kugo.mobile.core.AudioFormat
import com.kugo.mobile.core.DecryptEngine
import com.kugo.mobile.core.DecryptResult
import com.kugo.mobile.data.AppPrefs
import com.kugo.mobile.data.FileBridge
import com.kugo.mobile.data.KeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** 单个任务的运行状态。 */
enum class TaskState { Queued, Materializing, Decrypting, Success, Failed, NeedKey }

/** 队列中的一项。 */
data class TaskItem(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val sizeBytes: Long,
    val state: TaskState = TaskState.Queued,
    val processedBytes: Long = 0L,
    val totalBytes: Long = -1L,
    val outPath: String? = null,
    val publishedUri: String? = null,
    val error: String? = null,
) {
    /** 进度百分比，未知总量时返回 null 表示不确定进度。 */
    val percent: Int?
        get() {
            val total = if (totalBytes > 0) totalBytes else sizeBytes
            if (total <= 0) return null
            return ((processedBytes * 100) / total).toInt().coerceIn(0, 100)
        }

    val isRunning: Boolean
        get() = state == TaskState.Materializing || state == TaskState.Decrypting

    val isFinished: Boolean
        get() = state == TaskState.Success || state == TaskState.Failed || state == TaskState.NeedKey
}

/** 整体统计。 */
data class QueueStats(
    val total: Int = 0,
    val done: Int = 0,
    val failed: Int = 0,
    val needKey: Int = 0,
    val running: Boolean = false,
) {
    val finished: Int get() = done + failed + needKey
}

/** 顶部提示条。 */
data class Banner(val text: String, val tone: Tone = Tone.Info, val id: Long = System.nanoTime()) {
    enum class Tone { Info, Success, Warning, Error }
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val bridge = FileBridge(app)

    /** 密钥的本地持久化存储（应用私有目录，其他应用无法读取）。 */
    private val keyStore = KeyStore(app)

    /** 界面偏好（主题、输出策略），与凭据分开存。 */
    private val prefs = AppPrefs.get(app)

    private val _tasks = MutableStateFlow<List<TaskItem>>(emptyList())
    val tasks: StateFlow<List<TaskItem>> = _tasks.asStateFlow()

    private val _stats = MutableStateFlow(QueueStats())
    val stats: StateFlow<QueueStats> = _stats.asStateFlow()

    private val _banner = MutableStateFlow<Banner?>(null)
    val banner: StateFlow<Banner?> = _banner.asStateFlow()

    private val _formats = MutableStateFlow<List<AudioFormat>>(emptyList())
    val formats: StateFlow<List<AudioFormat>> = _formats.asStateFlow()

    /** 用户选定的输出目录树 URI；为空表示输出到应用私有目录。 */
    private val _outputTree = MutableStateFlow<Uri?>(null)
    val outputTree: StateFlow<Uri?> = _outputTree.asStateFlow()

    private val _outputTreeName = MutableStateFlow<String>("应用私有目录")
    val outputTreeName: StateFlow<String> = _outputTreeName.asStateFlow()

    /**
     * 「输出到自定义目录」开关的状态。
     *
     * 和 [outputTree] 的区别：输出目录 URI 可能是用户上次选完就再没动过的，
     * 而开关是用户当前的**意图**。两者分开存，关掉开关时不必清掉 URI，
     * 下次打开还能沿用上次选的目录。
     */
    private val _outputToCustomDir = MutableStateFlow(false)
    val outputToCustomDir: StateFlow<Boolean> = _outputToCustomDir.asStateFlow()

    /** 发布到自定义目录后是否删掉私有目录里的副本。 */
    private val _deletePrivateCopy = MutableStateFlow(prefs.deletePrivateCopyAfterPublish)
    val deletePrivateCopy: StateFlow<Boolean> = _deletePrivateCopy.asStateFlow()

    private val _hasKggKeys = MutableStateFlow(false)
    val hasKggKeys: StateFlow<Boolean> = _hasKggKeys.asStateFlow()

    private val _hasQmcKeys = MutableStateFlow(false)
    val hasQmcKeys: StateFlow<Boolean> = _hasQmcKeys.asStateFlow()

    /**
     * QQ 账号登录态。
     *
     * 直接复用 [QQSessionManager] 的 StateFlow，不做二次拷贝：
     * 登录态可能被 WebView 侧（新登录）与业务侧（发现失效）同时改写，
     * 单一数据源能避免「设置页显示已登录、实际已经失效」这类不一致。
     */
    val qqAccount: StateFlow<QQAccountState> = QQSessionManager.state

    private var nextId = 1L

    init {
        // 先恢复 QQ 登录态：它会在冷启动时把 Cookie 重新注入 Go 层。
        QQSessionManager.init(app)
        refreshFormats()
        restorePersistedKeys()
        refreshCredentialState()
        // 已恢复出会话的话，后台静默确认一次有效性；失败也只是打个标记，不打断用户。
        if (QQSessionManager.currentSession() != null) {
            viewModelScope.launch { QQSessionManager.verify(force = false) }
        }
    }

    /**
     * 启动时把上次保存的密钥重新注入 Go 层。
     *
     * Go 侧凭据只活在进程内存里，App 每次冷启动都必须重新喂一遍，
     * 否则用户会遇到「昨天导入的密钥今天失效了」的困惑。
     */
    private fun restorePersistedKeys() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                // 优先恢复数据库（密钥更全），再叠加文本密钥做补充。
                keyStore.kggDatabase()?.let { db ->
                    runCatching { DecryptEngine.importKggDatabase(db.absolutePath) }
                }
                val kgg = keyStore.loadKgg()
                if (kgg.isNotBlank()) DecryptEngine.importKggKeysText(kgg)

                val qmc = keyStore.loadQmc()
                if (qmc.isNotBlank()) DecryptEngine.importQmcKeysText(qmc)
            }
            refreshCredentialState()
        }
    }

    private fun refreshFormats() {
        viewModelScope.launch {
            _formats.value = withContext(Dispatchers.IO) { DecryptEngine.supportedFormats() }
        }
    }

    private fun refreshCredentialState() {
        viewModelScope.launch {
            val (kgg, qmc) = withContext(Dispatchers.IO) {
                DecryptEngine.hasKggKeys() to DecryptEngine.hasQmcKeys()
            }
            _hasKggKeys.value = kgg
            _hasQmcKeys.value = qmc
        }
    }

    // --- QQ 账号 ---

    /**
     * 登录页返回后的收口。Activity 只回传「成功/失败 + 文案」，
     * 真正的状态已经在 [QQSessionManager.accept] 里落过盘了。
     */
    fun onQQLoginResult(ok: Boolean, message: String?) {
        val text = message?.takeIf { it.isNotBlank() }
            ?: if (ok) "QQ 账号登录成功" else "QQ 账号登录未完成"
        showBanner(text, if (ok) Banner.Tone.Success else Banner.Tone.Warning)
        if (ok) {
            // 有了 Cookie 之后 ekey 可能能自动换取，刷新一下凭据状态。
            refreshCredentialState()
        }
    }

    /** 主动校验一次登录态（设置页的「校验」按钮）。 */
    fun refreshQQSession() {
        if (QQSessionManager.currentSession() == null) {
            showBanner("尚未登录 QQ 账号", Banner.Tone.Info)
            return
        }
        viewModelScope.launch {
            val flag = QQSessionManager.verify(force = true)
            val text = when (flag) {
                VerifyFlag.Valid -> "QQ 登录态有效"
                VerifyFlag.Expired -> "QQ 登录态已失效，请重新登录"
                VerifyFlag.Unknown -> "无法确认登录态（网络或接口异常），已按本地有效期判断"
                VerifyFlag.Unchecked -> "尚未校验"
            }
            showBanner(text, if (flag == VerifyFlag.Valid) Banner.Tone.Success else Banner.Tone.Warning)
        }
    }

    /** 退出 QQ 登录：清 Cookie、清落盘会话、清 Go 层凭据。 */
    fun logoutQQ() {
        QQSessionManager.logout()
        refreshCredentialState()
        showBanner("已退出 QQ 账号", Banner.Tone.Info)
    }

    // --- 队列操作 ---

    /**
     * 添加一批待解密文件。
     *
     * 自动过滤不支持的文件，并给出提示，避免用户把无关文件丢进来。
     */
    fun addFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return

        val accepted = mutableListOf<TaskItem>()
        val rejected = mutableListOf<String>()

        uris.forEach { uri ->
            val picked = bridge.query(uri)
            val name = picked?.displayName ?: uri.lastPathSegment ?: "未知文件"
            if (picked == null || !DecryptEngine.isSupported(name)) {
                rejected += name
                return@forEach
            }
            accepted += TaskItem(
                id = nextId++,
                uri = uri,
                displayName = name,
                sizeBytes = picked.size,
            )
        }

        if (accepted.isNotEmpty()) {
            _tasks.update { (it + accepted).distinctBy { t -> t.uri } }
            recomputeStats()
        }

        when {
            rejected.isEmpty() && accepted.isNotEmpty() ->
                showBanner("已添加 ${accepted.size} 个文件", Banner.Tone.Success)

            accepted.isEmpty() && rejected.isNotEmpty() ->
                showBanner("没有可处理的文件：${rejected.size} 个格式不受支持", Banner.Tone.Warning)

            rejected.isNotEmpty() ->
                showBanner("已添加 ${accepted.size} 个，跳过 ${rejected.size} 个不支持的格式", Banner.Tone.Warning)
        }
    }

    fun removeTask(id: Long) {
        _tasks.update { list -> list.filterNot { it.id == id } }
        recomputeStats()
    }

    /** 批量删除（多选模式下触发）。比逐个 removeTask 少 n-1 次列表重建与统计重算。 */
    fun removeTasks(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        val removing = ids.toSet()
        val removedRunning = _tasks.value.any { it.id in removing && it.isRunning }
        _tasks.update { list -> list.filterNot { it.id in removing } }
        recomputeStats()
        showBanner(
            if (removedRunning) "已移除 ${ids.size} 项（其中包含正在运行的任务）" else "已移除 ${ids.size} 项",
            Banner.Tone.Info,
        )
    }

    /**
     * 调整解密顺序。
     *
     * 队列是串行处理的，因此**顺序就是优先级**——把重要的文件拖到最前面，
     * 比「全部解密完再挑」省事得多。
     *
     * 注意：调用方传的是**完整队列**的下标。界面在有筛选时禁用了拖动，
     * 所以这里不需要做「可见下标 → 真实下标」的换算。
     */
    fun moveTask(from: Int, to: Int) {
        if (from == to) return
        _tasks.update { list ->
            if (from !in list.indices || to !in list.indices) {
                list
            } else {
                list.toMutableList().apply { add(to, removeAt(from)) }
            }
        }
    }

    fun clearFinished() {
        _tasks.update { list -> list.filterNot { it.isFinished } }
        recomputeStats()
    }

    fun clearAll() {
        _tasks.value = emptyList()
        recomputeStats()
        bridge.cleanupWorkDir()
    }

    /**
     * 依次解密队列中所有待处理项。
     *
     * 串行执行是刻意选择：解密是纯 CPU 密集型任务，并发只会争抢资源并
     * 让进度回调互相干扰，用户体验反而变差。
     */
    fun startAll() {
        val pending = _tasks.value.filter { it.state == TaskState.Queued || it.state == TaskState.Failed || it.state == TaskState.NeedKey }
        if (pending.isEmpty()) {
            showBanner("没有待处理的文件", Banner.Tone.Info)
            return
        }

        viewModelScope.launch {
            _stats.update { it.copy(running = true) }
            var ok = 0
            var failed = 0
            var needKey = 0

            for (task in pending) {
                val result = runOne(task)
                when (result) {
                    TaskState.Success -> ok++
                    TaskState.NeedKey -> needKey++
                    else -> failed++
                }
            }

            _stats.update { it.copy(running = false) }
            bridge.cleanupWorkDir()

            val summary = buildString {
                append("完成：成功 $ok")
                if (failed > 0) append("，失败 $failed")
                if (needKey > 0) append("，缺少密钥 $needKey")
            }
            showBanner(
                summary,
                if (failed == 0 && needKey == 0) Banner.Tone.Success else Banner.Tone.Warning,
            )
        }
    }

    /** 解密单个任务，返回终态。 */
    private suspend fun runOne(item: TaskItem): TaskState {
        updateTask(item.id) { it.copy(state = TaskState.Materializing, processedBytes = 0, error = null) }

        // 1) 落地为真实文件
        val localFile: File = withContext(Dispatchers.IO) {
            bridge.materialize(item.uri).getOrElse { e ->
                updateTask(item.id) {
                    it.copy(state = TaskState.Failed, error = "读取文件失败：${e.message}")
                }
                return@withContext null
            }
        } ?: return TaskState.Failed

        updateTask(item.id) {
            it.copy(state = TaskState.Decrypting, totalBytes = localFile.length())
        }

        // 2) 调用 Go 核心解密
        val outDir: String? = _outputTree.value?.let { null } // 有目录树时先落私有目录，再发布
        val result = withContext(Dispatchers.IO) {
            DecryptEngine.decrypt(localFile.absolutePath, outDir) { done, total ->
                updateTask(item.id) { it.copy(processedBytes = done, totalBytes = total) }
            }
        }

        return when (result) {
            is DecryptResult.Success -> {
                val produced = File(result.outPath)
                val published = publishIfNeeded(produced)
                updateTask(item.id) {
                    it.copy(
                        state = TaskState.Success,
                        outPath = result.outPath,
                        processedBytes = produced.length(),
                        totalBytes = produced.length(),
                        publishedUri = published,
                    )
                }
                TaskState.Success
            }

            is DecryptResult.NeedKey -> {
                updateTask(item.id) {
                    it.copy(state = TaskState.NeedKey, error = result.reason)
                }
                // Go 侧报「缺密钥」时，若是 QQ 音乐的加密格式且当前处于登录态，
                // 大概率是 Cookie 已失效——提前把登录态标脏，
                // 用户下次进设置页就会看到「需要重新登录」，而不是反复解密失败。
                val isQmcFile = item.displayName.contains("mflac", ignoreCase = true) ||
                    item.displayName.contains("mgg", ignoreCase = true)
                if (isQmcFile && QQSessionManager.isUsable) {
                    QQSessionManager.invalidate("QQ 音乐密钥获取失败，登录态可能已失效")
                }
                TaskState.NeedKey
            }

            is DecryptResult.Failure -> {
                updateTask(item.id) {
                    it.copy(state = TaskState.Failed, error = result.message)
                }
                TaskState.Failed
            }
        }
    }

    /**
     * 若用户选了输出目录，把产物发布过去；返回发布后的 URI 描述。
     *
     * 发布成功后按用户的偏好决定是否删掉私有目录里的那份。
     */
    private suspend fun publishIfNeeded(src: File): String? {
        val tree = _outputTree.value ?: return null
        return withContext(Dispatchers.IO) {
            bridge.publishToTree(tree, src, FileBridge.mimeOf(src.name))
                .map { published ->
                    if (_deletePrivateCopy.value) {
                        // 删失败不算错误：产物已经落到用户选的目录了，私有目录里的副本
                        // 会在 cleanupWorkDir 时被一并清掉。
                        runCatching { src.delete() }
                    }
                    published
                }
                .getOrElse { e ->
                    showBanner("写入所选目录失败：${e.message}", Banner.Tone.Error)
                    null
                }
        }
    }

    // --- 输出目录 ---

    /** 用户选好了自定义目录：登记 URI 并打开开关。 */
    fun enableCustomOutput(uri: Uri) {
        _outputTree.value = uri
        _outputToCustomDir.value = true
        _outputTreeName.value = FileBridge.treeDisplayName(uri)
        showBanner("输出目录已设为：${_outputTreeName.value}", Banner.Tone.Success)
    }

    /** 关闭「输出到自定义目录」开关。保留已授权的 URI，下次打开可以直接沿用。 */
    fun disableCustomOutput() {
        _outputToCustomDir.value = false
        _outputTreeName.value = "应用私有目录"
        showBanner("已切回应用私有目录", Banner.Tone.Info)
    }

    fun setDeletePrivateCopy(enabled: Boolean) {
        prefs.deletePrivateCopyAfterPublish = enabled
        _deletePrivateCopy.value = enabled
    }

    /** 应用私有输出目录的绝对路径，供 UI 展示。 */
    fun privateOutputPath(): String = bridge.outputDir.absolutePath

    // --- 凭据 ---

    fun importKggKeyFile(uri: Uri) = viewModelScope.launch {
        val file = withContext(Dispatchers.IO) { bridge.materialize(uri).getOrNull() }
        if (file == null) {
            showBanner("读取密钥文件失败", Banner.Tone.Error)
            return@launch
        }
        val r = withContext(Dispatchers.IO) { DecryptEngine.importKggKeyFile(file.absolutePath) }
        r.onSuccess {
            // 把内容存起来，下次启动自动恢复。
            runCatching { file.readText() }
                .onSuccess { text -> withContext(Dispatchers.IO) { keyStore.saveKgg(text) } }
            refreshCredentialState()
            showBanner("酷狗密钥已导入", Banner.Tone.Success)
        }.onFailure {
            showBanner("导入失败：${it.message}", Banner.Tone.Error)
        }
    }

    fun importKggDatabase(uri: Uri) = viewModelScope.launch {
        val file = withContext(Dispatchers.IO) { bridge.materialize(uri).getOrNull() }
        if (file == null) {
            showBanner("读取数据库失败", Banner.Tone.Error)
            return@launch
        }
        val r = withContext(Dispatchers.IO) { DecryptEngine.importKggDatabase(file.absolutePath) }
        r.onSuccess {
            // 数据库解密后的密钥表会把 kggMap 填进 Go 层；这里持久化原始 db 副本，
            // 保证冷启动后仍能重新解出密钥。
            withContext(Dispatchers.IO) {
                runCatching {
                    val dst = java.io.File(keyStore.kggDbPath())
                    file.copyTo(dst, overwrite = true)
                }
            }
            refreshCredentialState()
            showBanner("酷狗密钥库已导入", Banner.Tone.Success)
        }.onFailure {
            showBanner("导入失败：${it.message}", Banner.Tone.Error)
        }
    }

    fun importTextKeys(text: String, forQmc: Boolean) = viewModelScope.launch {
        if (text.isBlank()) {
            showBanner("内容为空", Banner.Tone.Warning)
            return@launch
        }
        withContext(Dispatchers.IO) {
            if (forQmc) {
                DecryptEngine.importQmcKeysText(text)
                keyStore.saveQmc(text)
            } else {
                DecryptEngine.importKggKeysText(text)
                // 与已有密钥合并，避免覆盖掉之前导入的条目。
                val merged = mergeKeyText(keyStore.loadKgg(), text)
                keyStore.saveKgg(merged)
            }
        }
        refreshCredentialState()
        showBanner(if (forQmc) "QQ音乐 ekey 已导入" else "酷狗密钥已导入", Banner.Tone.Success)
    }

    fun clearCredentials() = viewModelScope.launch {
        withContext(Dispatchers.IO) {
            DecryptEngine.clearCredentials()
            keyStore.clear()
        }
        refreshCredentialState()
        showBanner("凭据已清空", Banner.Tone.Info)
    }

    /** 合并两份密钥文本，后者覆盖前者的同名条目。 */
    private fun mergeKeyText(existing: String, incoming: String): String {
        val map = LinkedHashMap<String, String>()
        listOf(existing, incoming).forEach { blob ->
            blob.lineSequence().forEach { raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEach
                val idx = line.indexOf('$')
                if (idx <= 0) return@forEach
                map[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
            }
        }
        return map.entries.joinToString("\n") { "${it.key}\$${it.value}" }
    }

    // --- 工具 ---

    fun dismissBanner() {
        _banner.value = null
    }

    /**
     * 导出诊断信息。出问题时让用户贴这段文本，便于定位。
     */
    fun diagnostics(): String = buildString {
        appendLine("== 运行诊断 ==")
        appendLine("支持格式数：${_formats.value.size}")
        appendLine("队列：${_stats.value}")
        appendLine("酷狗密钥：${if (_hasKggKeys.value) "已导入" else "未导入"}")
        appendLine("QQ音乐 ekey：${if (_hasQmcKeys.value) "已导入" else "未导入"}")
        appendLine("输出目录：${_outputTreeName.value}")
        appendLine()
        appendLine(QQSessionManager.describe())
        appendLine()
        appendLine("== 任务明细 ==")
        _tasks.value.forEach { t ->
            appendLine(
                "#${t.id} ${t.displayName} [${t.state}] " +
                    "${t.sizeBytes.toReadableSize()}" +
                    (t.error?.let { " err=$it" } ?: "") +
                    (t.outPath?.let { " -> $it" } ?: ""),
            )
        }
    }

    private fun updateTask(id: Long, transform: (TaskItem) -> TaskItem) {
        _tasks.update { list ->
            list.map { if (it.id == id) transform(it) else it }
        }
        recomputeStats()
    }

    private fun recomputeStats() {
        val list = _tasks.value
        _stats.update { prev ->
            QueueStats(
                total = list.size,
                done = list.count { it.state == TaskState.Success },
                failed = list.count { it.state == TaskState.Failed },
                needKey = list.count { it.state == TaskState.NeedKey },
                running = prev.running,
            )
        }
    }

    private fun showBanner(text: String, tone: Banner.Tone) {
        _banner.value = Banner(text, tone)
    }
}

/** 把字节数格式化为可读体积。 */
fun Long.toReadableSize(): String {
    if (this < 0) return "未知"
    if (this < 1024) return "${this} B"
    val kb = this / 1024.0
    if (kb < 1024) return String.format(Locale.ROOT, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.ROOT, "%.1f MB", mb)
    return String.format(Locale.ROOT, "%.2f GB", mb / 1024.0)
}
