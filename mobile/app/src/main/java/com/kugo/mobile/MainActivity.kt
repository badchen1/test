package com.kugo.mobile

import android.net.Uri
import android.os.Bundle
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kugo.mobile.auth.QQAuthEvent
import com.kugo.mobile.auth.QQLoginActivity
import com.kugo.mobile.auth.QQSessionManager
import com.kugo.mobile.data.AppPrefs
import com.kugo.mobile.data.ThemeMode
import com.kugo.mobile.ui.DisclaimerGate
import com.kugo.mobile.ui.DisclaimerScreen
import com.kugo.mobile.ui.KugoTheme
import com.kugo.mobile.ui.MainScreen
import com.kugo.mobile.ui.MainViewModel
import com.kugo.mobile.ui.Motion
import com.kugo.mobile.ui.RadialThemeVeil
import com.kugo.mobile.ui.SettingsScreen
import com.kugo.mobile.ui.resolveDark
import com.kugo.mobile.ui.schemeFor

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val prefs = AppPrefs.get(this)

        setContent {
            KugoRoot(
                prefs = prefs,
                window = window,
                // 免责声明的唯二出口：同意，或者退出应用。
                onDeclineDisclaimer = { finish() },
            )
        }
    }
}

/**
 * 应用根布局。
 *
 * 主题状态放在这一层（而不是 MainViewModel）的两个原因：
 *  1. 它同时决定了「内部 Compose 配色」和「系统状态栏图标明暗」，后者需要 `Window`，
 *     属于纯粹的界面职责；
 *  2. 圆形主题切换的遮盖层必须挂在根 Box 上才能全屏覆盖，
 *     状态留在根组件里可以少一层参数透传。
 */
@Composable
private fun KugoRoot(
    prefs: AppPrefs,
    window: Window,
    onDeclineDisclaimer: () -> Unit,
) {
    val vm: MainViewModel = viewModel()
    val systemDark = isSystemInDarkTheme()

    var mode by remember { mutableStateOf(prefs.themeMode) }
    val dark = mode.resolveDark(systemDark)

    // 免责声明：首次启动必须过一遍，同意后写进偏好，之后不再打扰。
    // 状态放在根这一层，是因为它要盖住包括设置页在内的一切，
    // 而且必须在主题遮盖层之上——否则切主题时会把声明页盖掉。
    var disclaimerAccepted by remember { mutableStateOf(prefs.disclaimerAccepted) }

    // 遮盖层要用的「上一套配色」与切换圆心。
    var veilScheme by remember { mutableStateOf(schemeFor(dark)) }
    var revealOrigin by remember { mutableStateOf<Offset?>(null) }

    // 每次切换 +1，让 veil 以 0f 重新创建。
    //
    // 这一步是「不闪帧」的关键：`Animatable.value` 是只读的，想把它拨回「遮罩铺满」
    // 只能靠 suspend 的 snapTo，而 suspend 调用没法跟同一帧的状态写入对齐。
    // 用 key 重建实例，就把「铺满」变成了 remember 的初始值，可以和
    // 「换配色 / 换主题」写在同一个事件回调里，Compose 合批成一次重组。
    var switchSeq by remember { mutableStateOf(0) }
    val veil = remember(switchSeq) { Animatable(0f) }

    // 系统栏图标明暗跟随应用主题，否则浅色主题下状态栏图标会看不见。
    LaunchedEffect(dark) {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }

    // 扩散动画：0f = 遮罩铺满（满屏旧配色）→ 1f = 半径扩到画布对角线（挖空，露出新配色）。
    //
    // RadialThemeVeil 画的是「整块画布 - 以点击点为圆心的圆」，所以 progress 越大、洞越大。
    // 早先这里写成了 snapTo(1f) → animateTo(0f)，方向正好是反的：
    // 先瞬间跳到新主题，再让旧主题从四周压过来，动画结束时遮罩移除又硬跳回新主题。
    LaunchedEffect(switchSeq) {
        if (switchSeq == 0) return@LaunchedEffect
        veil.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = Motion.THEME_REVEAL_MILLIS,
                easing = FastOutSlowInEasing,
            ),
        )
        // 走到这里 progress 已经是 1，遮罩什么都画不出来，移除它是零视觉影响。
        revealOrigin = null
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(schemeFor(dark).background),
    ) {
        KugoTheme(darkTheme = dark) {
            Surface(modifier = Modifier.fillMaxSize()) {
                KugoApp(
                    vm = vm,
                    prefs = prefs,
                    themeMode = mode,
                    onThemeModeChange = { target, origin ->
                        if (target != mode) {
                            // 顺序很重要：先把「切换前」的配色和圆心备好，再改 mode。
                            // 四个状态写在同一帧里，所以换色那一帧已经被旧配色铺满盖住，
                            // 不会闪出一帧没有遮盖层的新主题。
                            veilScheme = schemeFor(dark)
                            revealOrigin = origin
                            mode = target
                            prefs.themeMode = target
                            switchSeq++
                        }
                    },
                )
            }
        }

        RadialThemeVeil(
            origin = revealOrigin,
            veilBackground = veilScheme.background,
            veilSurface = veilScheme.surface,
            progress = veil.value,
            modifier = Modifier.fillMaxSize(),
        )

        // 放在根 Box 的最后一个子项 = 压在最上层。
        // 未同意之前它盖住整个界面并吞掉所有指针事件，下面那层虽然已经组合好，用户也碰不到。
        if (!disclaimerAccepted) {
            // 这个节点在 KugoTheme 之外。不单独套一层的话，MaterialTheme.colorScheme
            // 会退回 Material 的默认配色（紫），和整个应用的青绿主题对不上。
            KugoTheme(darkTheme = dark) {
                DisclaimerGate(
                    onAccept = {
                        prefs.disclaimerAccepted = true
                        disclaimerAccepted = true
                    },
                    onDecline = onDeclineDisclaimer,
                )
            }
        }
    }
}

@Composable
private fun KugoApp(
    vm: MainViewModel = viewModel(),
    prefs: AppPrefs,
    themeMode: ThemeMode = ThemeMode.System,
    onThemeModeChange: (ThemeMode, Offset) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current

    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()
    val banner by vm.banner.collectAsStateWithLifecycle()
    val formats by vm.formats.collectAsStateWithLifecycle()
    val outputTreeName by vm.outputTreeName.collectAsStateWithLifecycle()
    val outputToCustomDir by vm.outputToCustomDir.collectAsStateWithLifecycle()
    val deletePrivateCopy by vm.deletePrivateCopy.collectAsStateWithLifecycle()
    val hasKgg by vm.hasKggKeys.collectAsStateWithLifecycle()
    val hasQmc by vm.hasQmcKeys.collectAsStateWithLifecycle()
    val qqAccount by vm.qqAccount.collectAsStateWithLifecycle()

    var screen by remember { mutableStateOf(Screen.Main) }
    var pasteTarget by remember { mutableStateOf<PasteTarget?>(null) }

    // --- SAF 选择器 ---

    // 多选加密音频
    val pickFiles = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris: List<Uri> -> vm.addFiles(uris) }

    // 选择输出目录（持久授权，重启后仍可写）
    val pickOutputTree = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        if (uri != null) {
            // 没有持久授权的话，App 重启后会失去写权限。
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            vm.enableCustomOutput(uri)
        }
        // 用户取消选择时不改开关状态：开关是「意图」，没有目标目录就不该被打开。
    }

    // 选择密钥文件：kgg.key 是文本，KGMusicV3.db 是二进制，统一用 */* 过滤
    val pickKggKey = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? -> uri?.let(vm::importKggKeyFile) }

    val pickKggDb = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? -> uri?.let(vm::importKggDatabase) }

    // QQ 登录页（独立 Activity），只回传「是否成功 + 文案」。
    val qqLogin = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val (ok, message) = QQLoginActivity.readOutcome(result.data)
        vm.onQQLoginResult(ok, message)
    }

    // --- 事件 ---

    // 登录态失效时给用户一个明确的提示，而不是让他对着「解密失败」猜原因。
    LaunchedEffect(Unit) {
        QQSessionManager.events.collect { event ->
            when (event) {
                is QQAuthEvent.NeedRelogin -> vm.onQQLoginResult(false, "QQ 登录态已失效：${event.reason}")
            }
        }
    }

    // --- 布局 ---

    // 返回键只在非主界面时接管，避免主界面上误吞返回。
    BackHandler(enabled = screen != Screen.Main) { screen = Screen.Main }

    // 页面之间加一层方向感知的转场：往前走时新页从右侧淡入推进，返回时反向。
    // 原先的 if/else 是整屏硬切，点齿轮的瞬间画面会「跳」一下。
    AnimatedContent(
        targetState = screen,
        transitionSpec = {
            // 用枚举序号判断方向：Main(0) < Settings(1) < Disclaimer(2)。
            val forward = targetState.ordinal > initialState.ordinal
            val shift = { width: Int -> width / 10 }
            (
                slideInHorizontally(animationSpec = Motion.page<IntOffset>()) { w ->
                    if (forward) shift(w) else -shift(w)
                } + fadeIn(animationSpec = Motion.page<Float>())
                ) togetherWith (
                slideOutHorizontally(animationSpec = Motion.page<IntOffset>()) { w ->
                    if (forward) -shift(w) else shift(w)
                } + fadeOut(animationSpec = Motion.page<Float>())
                )
        },
        label = "kugoPages",
    ) { current ->
        when (current) {
            Screen.Main -> MainScreen(
                tasks = tasks,
                stats = stats,
                banner = banner,
                onDismissBanner = vm::dismissBanner,
                onPickFiles = { pickFiles.launch(arrayOf("*/*")) },
                onStart = vm::startAll,
                onRemove = vm::removeTask,
                onRemoveMany = vm::removeTasks,
                onClearFinished = vm::clearFinished,
                onMove = vm::moveTask,
                onOpenSettings = { screen = Screen.Settings },
            )

            Screen.Settings -> SettingsScreen(
                formats = formats,
                outputTreeName = outputTreeName,
                privateOutputPath = vm.privateOutputPath(),
                outputToCustomDir = outputToCustomDir,
                deletePrivateCopy = deletePrivateCopy,
                hasKggKeys = hasKgg,
                hasQmcKeys = hasQmc,
                qqAccount = qqAccount,
                themeMode = themeMode,
                onBack = { screen = Screen.Main },
                onPickOutputTree = { pickOutputTree.launch(null) },
                onToggleCustomOutput = { enable ->
                    // 打开开关必须先选到目录；选不到就保持关闭，不让开关撒谎。
                    if (enable) pickOutputTree.launch(null) else vm.disableCustomOutput()
                },
                onToggleDeletePrivateCopy = vm::setDeletePrivateCopy,
                onThemeModeChange = onThemeModeChange,
                // 已登录时用静默续期模式打开：能续就不用让用户再输一次密码。
                onQQLogin = { qqLogin.launch(QQLoginActivity.intent(context, silent = qqAccount.session != null)) },
                onQQVerify = vm::refreshQQSession,
                onQQLogout = vm::logoutQQ,
                onImportKggKeyFile = { pickKggKey.launch(arrayOf("*/*")) },
                onImportKggDatabase = { pickKggDb.launch(arrayOf("*/*")) },
                onPasteText = { forQmc -> pasteTarget = if (forQmc) PasteTarget.Qmc else PasteTarget.Kgg },
                onClearCredentials = vm::clearCredentials,
                onShowDisclaimer = { screen = Screen.Disclaimer },
            )

            // 声明页挂在设置之上：返回时退回设置，符合「从哪进来回哪去」。
            Screen.Disclaimer -> DisclaimerScreen(onBack = { screen = Screen.Settings })
        }
    }

    // --- 粘贴密钥对话框 ---

    pasteTarget?.let { target ->
        PasteKeyDialog(
            title = if (target == PasteTarget.Qmc) "粘贴 QQ音乐 ekey" else "粘贴酷狗密钥",
            hint = if (target == PasteTarget.Qmc) {
                "每行一条：mediaMid\$ekey\n也可写：文件名\$ekey"
            } else {
                "每行一条：音频哈希\$密钥"
            },
            onConfirm = { text ->
                vm.importTextKeys(text, forQmc = target == PasteTarget.Qmc)
                pasteTarget = null
            },
            onDismiss = { pasteTarget = null },
        )
    }
}

/** 顶层页面。枚举序号同时用来决定转场方向（前进 / 返回）。 */
private enum class Screen { Main, Settings, Disclaimer }

private enum class PasteTarget { Kgg, Qmc }

@Composable
private fun PasteKeyDialog(
    title: String,
    hint: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("密钥内容") },
                placeholder = { Text(hint) },
                minLines = 5,
                maxLines = 12,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) {
                Text("导入")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
