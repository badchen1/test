package com.kugo.mobile.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import java.util.Locale

/** 音频来源平台。筛选标签按它归类。 */
enum class MusicPlatform(val key: String, val label: String) {
    Kugou("kugou", "酷狗"),
    Netease("netease", "网易云"),
    Kuwo("kuwo", "酷我"),
    QQMusic("qqmusic", "QQ音乐"),
    Other("other", "其他"),
}

/**
 * 按扩展名推断来源平台。
 *
 * 为什么不问 Go 层：`DecryptEngine.supportedFormats()` 返回的是「支持哪些格式」，
 * 而队列项只有一个文件名。扩展名在四个平台上都是唯一的，直接映射最省事，
 * 也避免了为每个文件跨语言调用一次。
 */
fun platformOf(fileName: String): MusicPlatform {
    val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
    return when (ext) {
        "kgg", "kgm", "kgma", "vpr" -> MusicPlatform.Kugou
        "ncm" -> MusicPlatform.Netease
        "kwm" -> MusicPlatform.Kuwo
        "mflac", "mflac0", "mgg", "mgg1", "mggl", "qmc0", "qmc2", "qmc3",
        "qmcflac", "qmcogg", "tkm" -> MusicPlatform.QQMusic
        else -> MusicPlatform.Other
    }
}

/**
 * 标签展开筛选（Expanding Tag Selection）。
 *
 * 交互：
 *  - 标签只在队列里**确实存在**该平台时才出现，不出现空标签；
 *  - 选中时标签横向展开，尾部滑出该类目数量——数量是「选它值不值」的决策依据；
 *  - 再点一次取消筛选，回到全部。
 *
 * 为什么要这个：队列混了四个平台的加密文件时，用户常想「先只处理 QQ 音乐这批」，
 * 但队列本身不能删（删了还得重新挑）。筛选是无损的。
 */
@Composable
fun PlatformFilterRow(
    counts: Map<MusicPlatform, Int>,
    total: Int,
    selected: MusicPlatform?,
    onSelect: (MusicPlatform?) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (counts.size <= 1) return

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(
            label = "全部",
            count = total,
            selected = selected == null,
            onClick = { onSelect(null) },
        )
        MusicPlatform.values().forEach { platform ->
            val count = counts[platform] ?: return@forEach
            FilterChip(
                label = platform.label,
                count = count,
                selected = selected == platform,
                onClick = { onSelect(if (selected == platform) null else platform) },
            )
        }
    }
}

@Composable
private fun FilterChip(
    label: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    // 选中态用弹簧推过去，避免硬切。
    val fill by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = Motion.snappy<Float>(),
        label = "chipFill",
    )

    val container = lerpColor(
        from = MaterialTheme.colorScheme.surfaceVariant,
        to = MaterialTheme.colorScheme.primary,
        fraction = fill,
    )
    val content = lerpColor(
        from = MaterialTheme.colorScheme.onSurfaceVariant,
        to = MaterialTheme.colorScheme.onPrimary,
        fraction = fill,
    )

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = container,
        modifier = Modifier
            .animateContentSize(animationSpec = Motion.settle<IntSize>())
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = content,
            )
            // 数量只在选中时占位，收起时不占宽度 —— 这就是「标签展开」。
            AnimatedVisibility(
                visible = selected,
                enter = expandHorizontally(animationSpec = Motion.settle<IntSize>()) +
                    fadeIn(animationSpec = Motion.fade<Float>(120)),
                exit = shrinkHorizontally(animationSpec = Motion.fade<IntSize>(120)) +
                    fadeOut(animationSpec = Motion.fade<Float>(120)),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.22f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = count.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
            }
        }
    }
}

/** 线性插值两个颜色，用于自定义过渡（Compose 自带 lerp 在 ui.graphics 里，这里显式封装一下更可读）。 */
private fun lerpColor(from: Color, to: Color, fraction: Float): Color =
    androidx.compose.ui.graphics.lerp(from, to, fraction.coerceIn(0f, 1f))
