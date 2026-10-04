package com.kugo.mobile.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * 主界面：队列 + 操作入口。
 *
 * 这一屏同时承载了三套交互，它们的优先级是明确的：
 *
 * 1. **多选模式**（长按进入）—— 一旦进入，其它手势全部让位，顶栏整体换成选择工具栏；
 * 2. **排序**（拖右侧手柄）—— 只在没有筛选、没有运行时开放。筛选状态下"第 3 项"
 *    在完整队列里未必是第 3 项，允许拖动会导致排序结果与用户预期不符，所以直接禁用；
 * 3. **单卡片删除**（左滑）—— 默认能力，多选模式下关闭，避免和横向选择手势混淆。
 */
@Composable
fun MainScreen(
    tasks: List<TaskItem>,
    stats: QueueStats,
    banner: Banner?,
    onDismissBanner: () -> Unit,
    onPickFiles: () -> Unit,
    onStart: () -> Unit,
    onRemove: (Long) -> Unit,
    onRemoveMany: (Set<Long>) -> Unit,
    onClearFinished: () -> Unit,
    onMove: (Int, Int) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val spacingPx = with(density) { KmcDimens.ItemSpacing.toPx() }

    var selection by remember { mutableStateOf(emptySet<Long>()) }
    val selectionMode = selection.isNotEmpty()
    var filter by remember { mutableStateOf<MusicPlatform?>(null) }

    // 队列增删后，把已经不存在的 id 从选中集合里摘掉，
    // 否则「已选 N 项」会数出不存在的条目，删除按钮还算得出来。
    //
    // 用 remember(tasks) 缓存：这个键和下面的平台计数都只跟「队列成员」有关，
    // 而解密过程中进度变化会让 tasks 每秒变几十次、带着整屏重组，
    // 不缓存的话每次重组都要现拼一遍几十个 id 的字符串、现建两张哈希表。
    val idKey = remember(tasks) { tasks.joinToString(",") { it.id.toString() } }
    LaunchedEffect(idKey) {
        selection = selection.intersect(tasks.map { it.id }.toSet())
    }

    val platformCounts = remember(tasks) {
        tasks.groupingBy { platformOf(it.displayName) }.eachCount()
    }
    LaunchedEffect(platformCounts.keys) {
        // 筛选中的平台被全部删完后，自动回到「全部」，避免停在一个空列表上。
        if (filter != null && platformCounts[filter] == null) filter = null
    }

    val visible = remember(tasks, filter) {
        if (filter == null) tasks else tasks.filter { platformOf(it.displayName) == filter }
    }

    val canReorder = !selectionMode && !stats.running && filter == null && tasks.size > 1

    var draggingId by remember { mutableStateOf<Long?>(null) }
    var dragOffset by remember { mutableStateOf(0f) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            // 多选模式下收起飞入按钮：此时用户的目标是「处理已选」，不是「再加文件」。
            // 用缩放 + 淡出收起而不是直接 if 掉，否则按钮会「啪」地凭空消失。
            AnimatedVisibility(
                visible = !selectionMode,
                enter = fadeIn(animationSpec = Motion.fade<Float>(180)) +
                    scaleIn(initialScale = 0.86f, animationSpec = Motion.snappy<Float>()),
                exit = fadeOut(animationSpec = Motion.fade<Float>(140)) +
                    scaleOut(targetScale = 0.86f, animationSpec = Motion.fade<Float>(140)),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (tasks.isNotEmpty()) {
                        ExtendedFloatingActionButton(
                            onClick = onStart,
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, Modifier.size(20.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("开始解密", fontWeight = FontWeight.Medium)
                        }
                    }
                    FloatingActionButton(
                        onClick = onPickFiles,
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "添加文件")
                    }
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = KmcDimens.ScreenPadding),
        ) {
            Spacer(Modifier.height(12.dp))

            // 顶栏整体切换：常规头部 ⇄ 多选工具栏。用 AnimatedContent 做交叉过渡，
            // 否则长按进入多选时上面这一条会瞬间换脸，和下面卡片的错峰进场完全不是一个节奏。
            AnimatedContent(
                targetState = selectionMode,
                transitionSpec = {
                    val entering = targetState
                    (
                        fadeIn(animationSpec = Motion.page<Float>()) +
                            slideInVertically(animationSpec = Motion.page<IntOffset>()) { h ->
                                if (entering) -h / 4 else h / 4
                            }
                        ) togetherWith (
                        fadeOut(animationSpec = Motion.page<Float>()) +
                            slideOutVertically(animationSpec = Motion.page<IntOffset>()) { h ->
                                if (entering) h / 4 else -h / 4
                            }
                        )
                },
                label = "topBar",
            ) { inSelection ->
                if (inSelection) {
                    SelectionTopBar(
                        count = selection.size,
                        total = visible.size,
                        onExit = { selection = emptySet() },
                        onToggleAll = {
                            selection = if (selection.size == visible.size) {
                                emptySet()
                            } else {
                                visible.map { it.id }.toSet()
                            }
                        },
                        onDelete = {
                            onRemoveMany(selection)
                            selection = emptySet()
                        },
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AppHeader(
                            running = stats.running,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Default.Settings, contentDescription = "设置")
                        }
                        if (tasks.isNotEmpty()) {
                            IconButton(onClick = onClearFinished) {
                                Icon(Icons.Default.DeleteSweep, contentDescription = "清理已完成")
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            if (tasks.isNotEmpty()) {
                StatsRow(stats)
                Spacer(Modifier.height(12.dp))
            }

            if (tasks.size > 1) {
                PlatformFilterRow(
                    counts = platformCounts,
                    total = tasks.size,
                    selected = filter,
                    onSelect = { filter = it },
                )
                Spacer(Modifier.height(10.dp))
            }

            // 提示文案只在「可排序」这个条件上显隐。
            //
            // 早先它还额外要求 draggingId == null，结果是手指一按上手柄、这行字就消失，
            // 下面整列内容跟着往上跳一格，被拖的那张卡片会在指下突然错位。
            // 用 AnimatedVisibility 做高度收缩，并且拖动期间保持显示。
            AnimatedVisibility(
                visible = canReorder,
                enter = fadeIn(animationSpec = Motion.page<Float>()) +
                    expandVertically(
                        animationSpec = Motion.page<IntSize>(),
                        expandFrom = Alignment.Top,
                    ),
                exit = fadeOut(animationSpec = Motion.fade<Float>(140)) +
                    shrinkVertically(
                        animationSpec = Motion.fade<IntSize>(180),
                        shrinkTowards = Alignment.Top,
                    ),
            ) {
                Column {
                    Text(
                        text = "长按卡片可多选 · 拖动右侧手柄调整解密顺序",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }

            Box(Modifier.fillMaxSize()) {
                when {
                    tasks.isEmpty() -> EmptyState(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 120.dp),
                    )

                    visible.isEmpty() -> Text(
                        text = "当前分类下没有文件",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 24.dp),
                    )

                    else -> LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 110.dp),
                        verticalArrangement = Arrangement.spacedBy(KmcDimens.ItemSpacing),
                    ) {
                        itemsIndexed(visible, key = { _, item -> item.id }) { index, item ->
                            TaskCard(
                                item = item,
                                index = index,
                                selectionMode = selectionMode,
                                selected = item.id in selection,
                                dragEnabled = canReorder,
                                dragging = draggingId == item.id,
                                dragOffsetY = if (draggingId == item.id) dragOffset else 0f,
                                onToggleSelect = {
                                    selection = if (item.id in selection) {
                                        selection - item.id
                                    } else {
                                        selection + item.id
                                    }
                                },
                                onEnterSelection = { selection = selection + item.id },
                                onRemove = { onRemove(item.id) },
                                onDragStart = {
                                    draggingId = item.id
                                    dragOffset = 0f
                                },
                                onDrag = { delta -> dragOffset += delta },
                                onDragEnd = {
                                    // 列表在拖动期间不变动，所以「位移 / 行高」可以稳定地换算成跨越了几格。
                                    // 这样比边拖边重排简单得多，也不会出现重排后手指与卡片脱节的问题。
                                    val rowHeight = listState.layoutInfo.visibleItemsInfo
                                        .firstOrNull { it.key == item.id }?.size ?: 0
                                    val step = (rowHeight + spacingPx).coerceAtLeast(1f)
                                    val steps = (dragOffset / step).roundToInt()
                                    val target = (index + steps).coerceIn(0, visible.lastIndex)
                                    draggingId = null
                                    dragOffset = 0f
                                    if (target != index) onMove(index, target)
                                },
                                // 让队列的位置变化也有过渡：
                                //  · 左滑删除 / 清理完成后，剩下的卡片是「滑上来」而不是瞬间补齐；
                                //  · 拖动松手后，其它卡片让位的节奏和拖动卡片自身回位的节奏一致。
                                // 这里显式关掉 fadeOut——卡片已经在飞出去的动画里淡出了，
                                // 再来一次淡出只会让它在消失瞬间又「浮」一下。
                                modifier = Modifier.animateItem(
                                    fadeInSpec = Motion.fade<Float>(200),
                                    placementSpec = Motion.reorder<IntOffset>(),
                                    fadeOutSpec = null,
                                ),
                            )
                        }
                    }
                }

                // 提示条浮在内容顶部。
                // 退出动画期间 banner 已经变成 null，所以要留一份「最后一次的内容」给动画画，
                // 否则它会先消失、再播一段空动画。
                val lastBanner = remember { mutableStateOf<Banner?>(null) }
                LaunchedEffect(banner) {
                    if (banner != null) {
                        lastBanner.value = banner
                        kotlinx.coroutines.delay(4200)
                        onDismissBanner()
                    }
                }

                AnimatedBanner(
                    visible = banner != null,
                    banner = lastBanner.value,
                    onDismiss = onDismissBanner,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }
        }
    }
}

/** 多选模式顶栏：替换掉常规头部，把「已选数量」和批量动作放在拇指最顺手的位置。 */
@Composable
private fun SelectionTopBar(
    count: Int,
    total: Int,
    onExit: () -> Unit,
    onToggleAll: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onExit) {
                Icon(Icons.Default.Close, contentDescription = "退出多选")
            }
            Text(
                text = "已选 $count 项",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onToggleAll) {
                Text(if (count == total) "取消全选" else "全选")
            }
            IconButton(onClick = onDelete, enabled = count > 0) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "删除所选",
                    tint = if (count > 0) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    },
                )
            }
        }
    }
}

/**
 * 提示条浮层。
 *
 * 单独包一层，而不是在 Box 里直接写 AnimatedVisibility：BoxScope 自己没有
 * AnimatedVisibility 重载，Kotlin 会顺着隐式接收者找到外层 Column 的
 * ColumnScope 重载，然后报「cannot be called in this context with an
 * implicit receiver」。放进一个没有作用域接收者的顶层函数就没这个问题。
 */
@Composable
private fun AnimatedBanner(
    visible: Boolean,
    banner: Banner?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = Motion.page<Float>()) +
            slideInVertically(animationSpec = Motion.page<IntOffset>()) { -it },
        exit = fadeOut(animationSpec = Motion.fade<Float>(200)) +
            slideOutVertically(animationSpec = Motion.fade<IntOffset>(200)) { -it },
        modifier = modifier,
    ) {
        banner?.let { BannerBar(banner = it, onDismiss = onDismiss) }
    }
}
