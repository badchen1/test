package com.kugo.mobile.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.kugo.mobile.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max

/**
 * 队列中的单个任务卡片。
 *
 * 一张卡片上叠了五种手势/动效，各自的触发条件被刻意错开，避免互相打架：
 *
 * | 手势 | 触发区域 | 生效条件 |
 * |---|---|---|
 * | 长按 → 进入多选 | 标题区域 | 非多选模式 |
 * | 单击 → 勾选/取消 | 标题区域 | 多选模式 |
 * | 横向拖动 → 曲线删除 | 整张卡片 | 非多选、非运行中 |
 * | 竖向拖动 → 排序 | 右侧拖拽手柄 | 队列可排序 |
 * | 点击「展开」 | 错误详情 | 文案够长 |
 *
 * 之所以长按给多选、手柄给排序：两者都需要「按住」，共用长按会让用户无法预期，
 * 而拖拽手柄是 iOS/Android 上「重排」的通用心智模型（只有它可拖）。
 */
@Composable
fun TaskCard(
    item: TaskItem,
    index: Int,
    selectionMode: Boolean,
    selected: Boolean,
    dragEnabled: Boolean,
    dragging: Boolean,
    dragOffsetY: Float,
    onToggleSelect: () -> Unit,
    onEnterSelection: () -> Unit,
    onRemove: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = when (item.state) {
        TaskState.Success -> MaterialTheme.colorScheme.primary
        TaskState.Failed -> MaterialTheme.colorScheme.error
        TaskState.NeedKey -> Color(0xFFE8A33D)
        TaskState.Decrypting, TaskState.Materializing -> MaterialTheme.colorScheme.secondary
        TaskState.Queued -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val shape = MaterialTheme.shapes.large
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current

    // --- 曲线删除的状态 ---
    val swipeX = remember { Animatable(0f) }
    val exit = remember { Animatable(0f) }
    var exiting by remember { mutableStateOf(false) }
    var exitDir by remember { mutableStateOf(-1f) }
    var exitStart by remember { mutableStateOf(0f) }
    var exitTarget by remember { mutableStateOf(0f) }
    var cardWidth by remember { mutableStateOf(1f) }

    // 排序位移。拖动中必须零延迟跟手；松手后用与列表 reorder 相同的时长收回 0，
    // 这样「卡片自身的位移」和「animateItem 给的布局位移」是同步衰减的两段，
    // 叠起来是一条连续路径，而不是先跳回原位、再飞到新位置。
    val dragY by animateFloatAsState(
        targetValue = if (dragging) dragOffsetY else 0f,
        animationSpec = if (dragging) snap<Float>() else Motion.reorder<Float>(),
        label = "dragOffsetY",
    )

    val swipeEnabled = !selectionMode && !item.isRunning && !exiting

    Box(modifier = modifier.fillMaxWidth().zIndex(if (dragging) 1f else 0f)) {

        // 卡片背后露出的删除提示，透明度跟着滑动比例走。
        val hintAlpha = if (exiting) 1f else (abs(swipeX.value) / cardWidth).coerceIn(0f, 1f)
        if (hintAlpha > 0.02f) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.error.copy(alpha = 0.14f * hintAlpha)),
                contentAlignment = if (swipeX.value < 0f) Alignment.CenterEnd else Alignment.CenterStart,
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.error.copy(alpha = hintAlpha),
                    modifier = Modifier.padding(horizontal = 18.dp).size(20.dp),
                )
            }
        }

        Surface(
            shape = shape,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = if (dragging) 8.dp else 1.dp,
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { cardWidth = it.size.width.toFloat().coerceAtLeast(1f) }
                .graphicsLayer {
                    val p = exit.value
                    translationX = if (p > 0f) {
                        exitStart + (exitTarget - exitStart) * p
                    } else {
                        swipeX.value
                    }
                    // 二次曲线：越往外飞，下沉越快，形成「被甩出去」的弧线。
                    translationY = 130f * p * p + dragY
                    rotationZ = exitDir * 5f * p
                    val s = 1f - 0.15f * p
                    scaleX = s
                    scaleY = s
                    alpha = 1f - 0.85f * p
                }
                .pointerInput(item.id, swipeEnabled) {
                    if (!swipeEnabled) return@pointerInput
                    val tracker = VelocityTracker()
                    detectHorizontalDragGestures(
                        onDragStart = { tracker.resetTracking() },
                        onHorizontalDrag = { change, delta ->
                            change.consume()
                            tracker.addPosition(change.uptimeMillis, change.position)
                            scope.launch { swipeX.snapTo(swipeX.value + delta) }
                        },
                        onDragCancel = {
                            scope.launch { swipeX.animateTo(0f, Motion.settle<Float>()) }
                        },
                        onDragEnd = {
                            val vx = tracker.calculateVelocity().x
                            val w = size.width.toFloat().coerceAtLeast(1f)
                            val dir = if (swipeX.value <= 0f) -1f else 1f
                            val far = abs(swipeX.value) > w * 0.30f
                            val fast = abs(vx) > 1000f
                            if (far || fast) {
                                scope.launch {
                                    exiting = true
                                    exitDir = dir
                                    exitStart = swipeX.value
                                    // 至少飞出 1.2 倍卡宽，避免慢速小幅滑动时「刚滑一点就消失」的突兀感。
                                    exitTarget = dir * max(w * 1.2f, abs(swipeX.value) + w * 0.6f)
                                    exit.snapTo(0f)
                                    exit.animateTo(
                                        targetValue = 1f,
                                        animationSpec = tween(300, easing = LinearOutSlowInEasing),
                                    )
                                    onRemove()
                                }
                            } else {
                                scope.launch { swipeX.animateTo(0f, Motion.settle<Float>()) }
                            }
                        },
                    )
                },
        ) {
            Column(Modifier.padding(KmcDimens.CardPadding)) {
                Row(verticalAlignment = Alignment.CenterVertically) {

                    SelectionIndicator(visible = selectionMode, selected = selected, index = index)

                    StatusBadge(item.state, accent)
                    Spacer(Modifier.width(12.dp))

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .pointerInput(item.id, selectionMode, dragEnabled) {
                                detectTapGestures(
                                    onLongPress = {
                                        if (!selectionMode && dragEnabled) {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            onEnterSelection()
                                        }
                                    },
                                    onTap = { if (selectionMode) onToggleSelect() },
                                )
                            },
                    ) {
                        Text(
                            text = item.displayName,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(3.dp))
                        SecondaryLine(item, accent)
                    }

                    // 拖拽手柄：只有它可拖，避免与横向删除、纵向滚动抢手势。
                    AnimatedVisibility(
                        visible = dragEnabled && !selectionMode && !exiting,
                        enter = fadeIn(animationSpec = Motion.fade<Float>(140)),
                        exit = fadeOut(animationSpec = Motion.fade<Float>(140)),
                    ) {
                        Icon(
                            imageVector = Icons.Default.DragHandle,
                            contentDescription = "拖动排序",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                            modifier = Modifier
                                .size(34.dp)
                                .padding(6.dp)
                                .pointerInput(item.id, dragEnabled) {
                                    if (!dragEnabled) return@pointerInput
                                    detectVerticalDragGestures(
                                        onDragStart = { onDragStart() },
                                        onVerticalDrag = { change, delta ->
                                            change.consume()
                                            onDrag(delta)
                                        },
                                        onDragEnd = { onDragEnd() },
                                        onDragCancel = { onDragEnd() },
                                    )
                                },
                        )
                    }

                    AnimatedVisibility(
                        visible = !selectionMode && !exiting,
                        enter = fadeIn(animationSpec = Motion.fade<Float>(140)),
                        exit = fadeOut(animationSpec = Motion.fade<Float>(140)),
                    ) {
                        IconButton(
                            onClick = onRemove,
                            enabled = !item.isRunning,
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "移除",
                                modifier = Modifier.size(16.dp),
                                tint = if (item.isRunning) {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                }

                // 三阶段步骤条：读取 → 解密 → 输出。
                // 只在「进行中」和「已成功」时出现——失败时停在哪一步我们无从得知，
                // 与其画一条满格的假进度，不如把位置让给错误详情。
                if (item.isRunning || item.state == TaskState.Success) {
                    Spacer(Modifier.height(10.dp))
                    SpringStepper(item, accent)
                }

                if (item.isRunning) {
                    Spacer(Modifier.height(8.dp))
                    val target = (item.percent ?: 0) / 100f
                    val animated by animateFloatAsState(
                        targetValue = target,
                        animationSpec = Motion.progress<Float>(),
                        label = "taskProgress",
                    )
                    ThinProgress(if (item.percent != null) animated else null)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = buildProgressText(item),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // 失败 / 缺密钥：原因默认折成两行，避免一条长栈信息把列表撑开。
                if ((item.state == TaskState.Failed || item.state == TaskState.NeedKey) &&
                    !item.error.isNullOrBlank()
                ) {
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = accent.copy(alpha = 0.10f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        ExpandableText(
                            text = item.error,
                            collapsedMaxLines = 2,
                            style = MaterialTheme.typography.bodySmall,
                            color = accent,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 多选指示器 —— 批量勾选（Staggered Bulk Selection）。
 *
 * 「错峰」体现在两处：
 *  1. 每一张卡片的进场动画按 [index] 递增延时，视觉上从下往上依次铺开；
 *  2. 指示器的**占位宽度**跟进场进度联动，所以卡片内容是被推着让位的，
 *     而不是先留出空位再淡入——后者看起来像「凭空出现」。
 *
 * 延时必须封顶（见 [Motion.staggerDelay]）：不封顶时队列越长，
 * 最后几张卡片出现得越晚，几十项时会拖到近一秒，用户会以为界面没响应。
 */
@Composable
private fun SelectionIndicator(visible: Boolean, selected: Boolean, index: Int) {
    val appear = remember { Animatable(if (visible) 1f else 0f) }

    LaunchedEffect(visible) {
        if (visible) {
            delay(Motion.staggerDelay(index))
            appear.animateTo(1f, Motion.snappy<Float>())
        } else {
            appear.animateTo(0f, Motion.fade<Float>(140))
        }
    }

    val checkScale by animateFloatAsState(
        targetValue = if (selected) 1f else 0.4f,
        animationSpec = Motion.snappy<Float>(),
        label = "checkScale",
    )

    Box(
        modifier = Modifier
            .width((appear.value * 32f).dp)
            .height(24.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (appear.value > 0.02f) {
            Surface(
                shape = RoundedCornerShape(50),
                color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                border = if (selected) null else BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier
                    .size(21.dp)
                    .alpha(appear.value),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (selected) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(14.dp).scale(checkScale),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 三阶段步骤条 —— 步骤条回弹（Spring Stepper Progress）。
 *
 * 「阶段推进」用低阻尼弹簧：活动段**略微越过目标再回落**，这个过冲是回弹的全部来源。
 * 阻尼比取 0.42——再低会晃得让人分心，再高就没有手感了。
 *
 * 但「活动段内部的进度」不能也用弹簧：解密百分比每秒更新几十次，
 * 每次更新都会把弹簧重启一次，弹簧永远停在加速段，看起来是**持续发抖**而不是在推进。
 * 所以活动段改用无过冲的缓动跟随（[Motion.progress]），
 * 只在它真的被推进成「已完成」、规格切回弹簧的那一刻才给出回弹。
 */
@Composable
private fun SpringStepper(item: TaskItem, accent: Color) {
    val labels = listOf("读取", "解密", "输出")
    val active = when (item.state) {
        TaskState.Queued -> -1
        TaskState.Materializing -> 0
        TaskState.Decrypting -> 1
        else -> 2
    }
    val stepProgress = when (item.state) {
        TaskState.Materializing -> 0.55f // 落地阶段拿不到细粒度进度，给个中间值表示「在动」
        TaskState.Decrypting -> ((item.percent ?: 0) / 100f).coerceIn(0f, 1f)
        else -> 1f
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        labels.forEachIndexed { i, label ->
            val target = when {
                i < active -> 1f
                i == active -> if (i == 2) 1f else stepProgress
                else -> 0f
            }
            val liveProgress = i == active && item.state == TaskState.Decrypting
            val fill by animateFloatAsState(
                targetValue = target,
                animationSpec = if (liveProgress) {
                    Motion.progress<Float>()
                } else {
                    spring(dampingRatio = 0.42f, stiffness = 380f)
                },
                label = "step$i",
            )

            Column(Modifier.weight(1f)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fill.coerceIn(0f, 1f))
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(2.dp))
                            .background(if (i <= active) accent else accent.copy(alpha = 0.55f)),
                    )
                }
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                            alpha = if (i <= active) 1f else 0.5f,
                        ),
                    )
                    if (i == active && item.isRunning && item.state == TaskState.Decrypting) {
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "${(stepProgress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = accent,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(state: TaskState, accent: Color) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = accent.copy(alpha = 0.14f),
        modifier = Modifier.size(38.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            when (state) {
                TaskState.Queued -> Icon(
                    Icons.Default.MusicNote, null,
                    tint = accent, modifier = Modifier.size(19.dp),
                )
                TaskState.Materializing, TaskState.Decrypting -> CircularProgressIndicator(
                    modifier = Modifier.size(19.dp),
                    strokeWidth = 2.dp,
                    color = accent,
                )
                TaskState.Success -> Icon(
                    Icons.Default.CheckCircle, null,
                    tint = accent, modifier = Modifier.size(20.dp),
                )
                TaskState.Failed -> Icon(
                    Icons.Default.ErrorOutline, null,
                    tint = accent, modifier = Modifier.size(20.dp),
                )
                TaskState.NeedKey -> Icon(
                    Icons.Default.Key, null,
                    tint = accent, modifier = Modifier.size(19.dp),
                )
            }
        }
    }
}

@Composable
private fun SecondaryLine(item: TaskItem, accent: Color) {
    val text = when (item.state) {
        TaskState.Queued -> item.sizeBytes.toReadableSize()
        TaskState.Materializing -> "正在读取文件…"
        TaskState.Decrypting -> "解密中"
        TaskState.Success -> item.outPath?.let { "已输出：${it.substringAfterLast('/')}" } ?: "完成"
        TaskState.Failed -> "失败"
        TaskState.NeedKey -> "需要导入密钥"
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (item.state == TaskState.Success || item.state == TaskState.Failed || item.state == TaskState.NeedKey) {
            accent.copy(alpha = 0.85f)
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

private fun buildProgressText(item: TaskItem): String {
    val pct = item.percent
    val total = if (item.totalBytes > 0) item.totalBytes else item.sizeBytes
    return if (pct != null && total > 0) {
        "$pct%   ${item.processedBytes.toReadableSize()} / ${total.toReadableSize()}"
    } else {
        item.processedBytes.toReadableSize()
    }
}

/** 顶部的品牌栏，含波形动画。 */
@Composable
fun AppHeader(running: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
            modifier = Modifier.size(40.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                WaveformIndicator(
                    active = running,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(20.dp).height(16.dp),
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "酷狗 · 网易云 · 酷我 · QQ音乐",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 五条竖柱组成的波形动画，运行时持续上下起伏。
 *
 * 用 [Animatable] 手写往返循环，而不是 `rememberInfiniteTransition`：
 * 后者一旦创建就永远在跑，而 [active] 为 false 时（也就是应用大部分时间）
 * 它依然每帧请求一次重组，把整条主界面一起拖着刷——纯浪费。
 * 这里在非运行状态下直接停住，空闲时不再有任何动画帧。
 */
@Composable
fun WaveformIndicator(
    active: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val wave = remember { Animatable(0f) }

    LaunchedEffect(active) {
        if (!active) {
            wave.snapTo(0f)
            return@LaunchedEffect
        }
        // 一个来回 900ms，和原先 infiniteRepeatable(900ms, Reverse) 的节奏一致。
        while (true) {
            wave.animateTo(1f, tween(durationMillis = 450, easing = LinearEasing))
            wave.animateTo(0f, tween(durationMillis = 450, easing = LinearEasing))
        }
    }

    val t = wave.value
    val baseHeights = listOf(0.45f, 0.75f, 1.00f, 0.65f, 0.50f)
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        baseHeights.forEachIndexed { index, base ->
            // 相邻柱子相位相反，看起来像声波在跳动。
            val shift = if (index % 2 == 0) t else 1f - t
            val factor = if (active) {
                (0.45f + 0.55f * shift) * base
            } else {
                0.30f
            }
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .height((16f * factor).dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(color.copy(alpha = if (active) 1f else 0.45f)),
            )
        }
    }
}
