package com.kugo.mobile.ui

import android.graphics.Bitmap
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kugo.mobile.auth.LoginOutcome
import com.kugo.mobile.auth.QQAuthConfig
import com.kugo.mobile.auth.QQLoginPhase
import com.kugo.mobile.auth.QQLoginViewModel
import com.kugo.mobile.auth.QQLoginWebViewFactory
import kotlinx.coroutines.delay

/**
 * QQ 登录页。
 *
 * 页面职责被刻意压到最少：所有判定（登录成功 / 失败 / 取消 / 超时）都在
 * [QQLoginViewModel] 里完成，这里只负责「把状态画出来」和「把 WebView 回调转发给 VM」。
 */
@Composable
fun QQLoginScreen(
    vm: QQLoginViewModel,
    silent: Boolean,
    onResult: (LoginOutcome) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by vm.ui.collectAsStateWithLifecycle()

    var webView by remember { mutableStateOf<WebView?>(null) }
    var triedFallback by remember { mutableStateOf(false) }

    // 结果收敛：成功的自动关闭，取消的直接返回，静默续期失败则切回交互式登录。
    LaunchedEffect(state.outcome) {
        when (val outcome = state.outcome) {
            null -> Unit

            is LoginOutcome.Success -> {
                delay(650)
                onResult(outcome)
            }

            is LoginOutcome.UserCancelled -> onResult(outcome)

            // 静默续期失败不是「失败」，而是「需要用户接管」——退回到登录页继续。
            is LoginOutcome.SilentRefreshFailed -> vm.continueInteractively()

            // 其余分支停在错误卡片上，等用户选择重试或关闭。
            else -> Unit
        }
    }

    // 重试 / 静默失败接管时重新加载页面。
    LaunchedEffect(state.reloadToken) {
        if (state.reloadToken > 0) webView?.loadUrl(vm.currentUrl)
    }

    BackHandler {
        val wv = webView
        // 优先让页面自己回退（用户在登录页点了「下一步」），到底了才算取消。
        if (wv != null && wv.canGoBack()) wv.goBack() else vm.onUserCancel()
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.let { wv ->
                wv.stopLoading()
                wv.loadUrl("about:blank")
                wv.destroy()
            }
            webView = null
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .systemBarsPadding()
            .imePadding(),
    ) {
        LoginTopBar(
            title = state.pageTitle ?: "QQ 登录",
            canGoBack = webView?.canGoBack() == true,
            onBack = { webView?.goBack() },
            onClose = { vm.onUserCancel() },
        )

        // 进度条：加载中显示不确定进度，其余时段用百分比。
        if (state.phase == QQLoginPhase.Loading || state.phase == QQLoginPhase.Capturing) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            Spacer(Modifier.height(4.dp))
        }

        Box(modifier = Modifier.weight(1f)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    val client = object : WebViewClient() {

                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            super.onPageStarted(view, url, favicon)
                            vm.onPageStarted(url)
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            // 页面跳转完成后立刻试一次抓取；抓不到 VM 会自动延迟重试。
                            vm.onPageFinished(url)
                        }

                        /**
                         * 拦截页面跳转。
                         *
                         * 这里只在「跳回业务域名」时触发一次补抓，其余一律放行——
                         * 拦截登录跳转会破坏 ptlogin2 的票据传递，是常见踩坑点。
                         */
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?,
                        ): Boolean {
                            val url = request?.url?.toString().orEmpty()
                            if (url.startsWith(QQAuthConfig.HOME_URL) ||
                                url.contains("y.qq.com")
                            ) {
                                vm.onPageFinished(url)
                            }
                            return false
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: WebResourceError?,
                        ) {
                            // 子资源（图片、埋点）失败不能判定整体失败，只看主文档。
                            if (request?.isForMainFrame != true || view == null) return
                            val code = error?.errorCode ?: -1

                            // 主入口不通时先换备用登录地址，而不是直接报错。
                            if (!triedFallback &&
                                QQLoginViewModel.isNetworkErrorCode(code) &&
                                vm.currentUrl == QQAuthConfig.LOGIN_URL
                            ) {
                                triedFallback = true
                                view.loadUrl(QQAuthConfig.LOGIN_URL_FALLBACK)
                                return
                            }
                            vm.onPageError(code, error?.description?.toString())
                        }

                        override fun onReceivedHttpError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            errorResponse: WebResourceResponse?,
                        ) {
                            if (request?.isForMainFrame != true) return
                            vm.onHttpError(errorResponse?.statusCode ?: -1)
                        }

                        override fun onReceivedSslError(
                            view: WebView?,
                            handler: SslErrorHandler?,
                            error: android.net.http.SslError?,
                        ) {
                            // 一律拒绝。放行证书错误会让登录凭据暴露给中间人。
                            handler?.cancel()
                            vm.onSslError(error?.url)
                        }

                        override fun onRenderProcessGone(
                            view: WebView?,
                            detail: RenderProcessGoneDetail?,
                        ): Boolean {
                            vm.onRenderProcessGone()
                            // 返回 true 表示「已处理」，避免系统直接杀掉整个进程。
                            return true
                        }
                    }

                    val chrome = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            vm.onProgressChanged(newProgress)
                        }

                        override fun onReceivedTitle(view: WebView?, title: String?) {
                            vm.onTitleChanged(title)
                        }

                        override fun onCreateWindow(
                            view: WebView?,
                            isDialog: Boolean,
                            isUserGesture: Boolean,
                            resultMsg: android.os.Message?,
                        ): Boolean = false
                    }

                    QQLoginWebViewFactory.create(ctx, client, chrome).also { wv ->
                        webView = wv
                        wv.loadUrl(vm.currentUrl)
                        vm.onEnvironmentWarning(QQLoginWebViewFactory.versionWarning())
                    }
                },
            )

            // 静默续期期间盖一层遮罩，避免用户看到登录页闪一下。
            AnimatedOverlay(visible = state.silentMode && !state.isFinished) {
                Surface(
                    color = MaterialTheme.colorScheme.background,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(34.dp))
                        Spacer(Modifier.height(14.dp))
                        Text("正在自动续期登录态…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            // 非成功终态的浮层同样走淡入淡出，而不是「啪」地盖满屏。
            // 退出动画期间 outcome 可能已经被清掉（顺带也就不会再踩到 !! 空断言），
            // 所以留一份最后的内容交给动画去画。
            val failure = state.outcome?.takeIf {
                state.isFinished &&
                    it !is LoginOutcome.Success &&
                    it !is LoginOutcome.UserCancelled
            }
            val lastFailure = remember { mutableStateOf<LoginOutcome?>(null) }
            LaunchedEffect(failure) {
                if (failure != null) lastFailure.value = failure
            }

            AnimatedOverlay(visible = failure != null) {
                lastFailure.value?.let { pending ->
                    ResultOverlay(
                        outcome = pending,
                        onRetry = { vm.retry(silent = false) },
                        onClose = { onResult(pending) },
                    )
                }
            }
        }

        LoginStatusBar(
            phase = state.phase,
            hint = state.hint,
            warning = state.environmentWarning,
        )
    }
}

@Composable
private fun LoginTopBar(
    title: String,
    canGoBack: Boolean,
    onBack: () -> Unit,
    onClose: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.width(8.dp))
            // 返回键随网页栈深出现/消失。原先是一边是 48dp 按钮、一边是 8dp 占位，
            // 切换时标题会瞬间横跳 40dp；改成横向展开/收缩，标题被平滑推开。
            AnimatedVisibility(
                visible = canGoBack,
                enter = fadeIn(animationSpec = Motion.fade<Float>(140)) +
                    expandHorizontally(animationSpec = Motion.page<IntSize>()),
                exit = fadeOut(animationSpec = Motion.fade<Float>(140)) +
                    shrinkHorizontally(animationSpec = Motion.fade<IntSize>(160)),
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "上一页")
                }
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp),
            )
            TextButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("取消")
            }
        }
    }
}

/** 底部状态条：常驻显示当前阶段，让用户知道 App 正在做什么。 */
@Composable
private fun LoginStatusBar(phase: QQLoginPhase, hint: String, warning: String?) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (phase == QQLoginPhase.RiskChallenge) {
                        Icons.Default.Lock
                    } else {
                        Icons.Default.Info
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = hint,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!warning.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = warning,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFE8A33D),
                )
            }
        }
    }
}

/** 非成功终态的结果浮层。 */
@Composable
private fun ResultOverlay(
    outcome: LoginOutcome,
    onRetry: () -> Unit,
    onClose: () -> Unit,
) {
    val (icon, tint, title, detail) = when (outcome) {
        is LoginOutcome.Failed ->
            ResultContent(Icons.Default.ErrorOutline, MaterialTheme.colorScheme.error, "登录失败", outcome.message)

        is LoginOutcome.Timeout ->
            ResultContent(Icons.Default.WarningAmber, Color(0xFFE8A33D), "登录超时", "长时间没有完成登录，请重试。")

        is LoginOutcome.NetworkError ->
            ResultContent(Icons.Default.WarningAmber, Color(0xFFE8A33D), "网络异常", outcome.message)

        is LoginOutcome.WebViewUnavailable ->
            ResultContent(Icons.Default.ErrorOutline, MaterialTheme.colorScheme.error, "无法打开登录页", outcome.message)

        is LoginOutcome.Success ->
            ResultContent(Icons.Default.CheckCircle, MaterialTheme.colorScheme.primary, "登录成功", "")

        LoginOutcome.UserCancelled, LoginOutcome.SilentRefreshFailed ->
            ResultContent(Icons.Default.Close, MaterialTheme.colorScheme.onSurfaceVariant, "已取消", "")
    }

    Surface(
        color = MaterialTheme.colorScheme.background.copy(alpha = 0.94f),
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(46.dp))
            Spacer(Modifier.height(16.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (detail.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onRetry) {
                    Icon(Icons.Default.Refresh, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("重试")
                }
                OutlinedButton(onClick = onClose) { Text("关闭") }
            }
        }
    }
}

private data class ResultContent(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val tint: Color,
    val title: String,
    val detail: String,
)

/**
 * 整屏浮层的淡入淡出外壳。
 *
 * 和 MainScreen 的 AnimatedBanner 同样的原因：这两个浮层挂在 Box 里，
 * 而 BoxScope 没有 AnimatedVisibility 重载，直接写会被解析到外层 Column 的
 * ColumnScope 重载并报「implicit receiver」错误，所以包一层无作用域的顶层函数。
 */
@Composable
private fun AnimatedOverlay(
    visible: Boolean,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = Motion.page<Float>()),
        exit = fadeOut(animationSpec = Motion.fade<Float>(160)),
        modifier = Modifier.fillMaxSize(),
    ) {
        content()
    }
}
