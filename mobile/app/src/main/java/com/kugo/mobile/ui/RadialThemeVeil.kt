package com.kugo.mobile.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import kotlin.math.hypot

/**
 * 圆形主题切换（Radial Theme Transition）的遮盖层。
 *
 * 原理：主题切换的瞬间，底层 UI 已经换成新配色；这一层用**旧主题的背景色**
 * 盖住「以点击点为圆心的圆之外」的全部区域，并让半径从最大收缩到 0。
 * 视觉上就是新主题从手指按下的位置扩散开来。
 *
 * 为什么用「旧背景色」而不是旧界面的截图：
 *  1. 截图需要 PixelCopy / GraphicsLayer.toImageBitmap，每帧成本高且 API 24 上不稳定；
 *  2. 本应用的视觉主体就是背景色 + 卡片面，用背景→面色做的竖向渐变已经足够像；
 *  3. 合成一个纯色路径，任意低端机上都是满帧。
 *
 * @param origin 点击位置（root 坐标系）；为 null 时不绘制。
 * @param progress 1 → 遮罩铺满；0 → 遮罩完全消失。
 */
@Composable
fun RadialThemeVeil(
    origin: Offset?,
    veilBackground: Color,
    veilSurface: Color,
    progress: Float,
    modifier: Modifier = Modifier,
) {
    if (origin == null) return

    Box(
        modifier = modifier
            .fillMaxSize()
            .drawWithContent {
                // 本层不画自己的内容，只画遮盖路径。
                drawContent()

                val p = progress.coerceIn(0f, 1f)
                if (p <= 0.001f) return@drawWithContent

                // 半径上限取对角线：保证 p=1 时能盖住任意角。
                val maxRadius = hypot(size.width.toDouble(), size.height.toDouble()).toFloat()
                val radius = maxRadius * p

                val veil = Path().apply {
                    // 整块画布
                    addRect(Rect(Offset.Zero, Size(size.width, size.height)))
                    // 挖掉圆心处的圆（EvenOdd 让第二个形状变成镂空）
                    addOval(
                        Rect(
                            left = origin.x - radius,
                            top = origin.y - radius,
                            right = origin.x + radius,
                            bottom = origin.y + radius,
                        ),
                    )
                    fillType = PathFillType.EvenOdd
                }

                drawPath(
                    path = veil,
                    brush = Brush.verticalGradient(
                        colors = listOf(veilBackground, veilSurface),
                        startY = 0f,
                        endY = size.height,
                    ),
                )
            },
    )
}
