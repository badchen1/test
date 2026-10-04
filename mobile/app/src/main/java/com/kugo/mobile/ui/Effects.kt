package com.kugo.mobile.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * 一组统一的动效参数。
 *
 * 集中在一处是为了让「回弹手感」在全应用保持一致——散落各处的魔法数字
 * 会让列表、开关、步骤条各弹各的，看起来像三个人写的。
 */
object Motion {
    /** 列表重排 / 状态切换：有一点回弹，但不夸张。 */
    fun <T> settle() = spring<T>(
        dampingRatio = Spring.DampingRatioLowBouncy,
        stiffness = Spring.StiffnessMediumLow,
        visibilityThreshold = null,
    )

    /** 勾选、按下这类「要立刻有反馈」的动作：快、脆、几乎不晃。 */
    fun <T> snappy() = spring<T>(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessMedium,
        visibilityThreshold = null,
    )

    /** 纯过场，不要回弹。 */
    fun <T> fade(durationMillis: Int = 180) = tween<T>(
        durationMillis = durationMillis,
        easing = FastOutSlowInEasing,
    )

    /**
     * 持续进度跟随（解密百分比、细进度条）。
     *
     * 刻意用**无过冲**的减速曲线而不是弹簧：进度值每秒会更新几十次，
     * 每次都重启一次弹簧会让弹簧永远处于加速段，看起来是持续发抖而不是「回弹」。
     */
    fun <T> progress(durationMillis: Int = 240) = tween<T>(
        durationMillis = durationMillis,
        easing = LinearOutSlowInEasing,
    )

    /**
     * 元素归位（列表重排、拖动松手回位）。
     *
     * 与 `Modifier.animateItem` 的 placementSpec 用同一组参数，
     * 两段位移才能叠加成一条完整的路径，而不是「先跳回原位再飞过去」。
     */
    fun <T> reorder(durationMillis: Int = 220) = tween<T>(
        durationMillis = durationMillis,
        easing = LinearOutSlowInEasing,
    )

    /** 页面级转场（主界面 ⇄ 设置页、顶栏整体切换）。 */
    fun <T> page(durationMillis: Int = 260) = tween<T>(
        durationMillis = durationMillis,
        easing = FastOutSlowInEasing,
    )

    /**
     * 错峰进场的延时。
     *
     * 必须封顶：早先写成 `index * 32ms` 无上限，队列有 30 项时最后一张卡片
     * 要等将近 1 秒才出现，用户会以为界面卡住了。超过 [maxMillis] 之后
     * 后面的一起进场，整体观感是「一波铺开」而不是「排队报到」。
     */
    fun staggerDelay(
        index: Int,
        perItemMillis: Long = 32L,
        maxMillis: Long = 192L,
    ): Long = (index.coerceAtLeast(0) * perItemMillis).coerceAtMost(maxMillis)

    /**
     * 圆形主题切换的时长。
     *
     * 这是一次全屏换色，需要「有物理感」但不能拖沓：420ms 刚好覆盖
     * 从点击点扩散到屏幕最远角的时间，再长会显得钝。
     */
    const val THEME_REVEAL_MILLIS = 420

    /** 开关联动涟漪的总时长。 */
    const val RIPPLE_MILLIS = 560
}

/**
 * 开关联动用的涟漪脉冲：触发时返回 1，然后衰减到 0。
 *
 * 注意首次组合**不播**：这个组件挂在设置页的常驻行上，
 * 若在 LaunchedEffect 的首次执行里就 snapTo(1f)，每次进入设置页
 * 都会看到「发布后删除私有副本」那行无端闪一圈描边——
 * 反馈动画只在用户真的改了状态时才该出现。
 */
@Composable
fun rememberRipplePulse(trigger: Any?): Float {
    val pulse = remember { Animatable(0f) }
    var firstPass by remember { mutableStateOf(true) }

    LaunchedEffect(trigger) {
        if (firstPass) {
            firstPass = false
            return@LaunchedEffect
        }
        pulse.snapTo(1f)
        pulse.animateTo(
            targetValue = 0f,
            animationSpec = tween(
                durationMillis = Motion.RIPPLE_MILLIS,
                easing = FastOutSlowInEasing,
            ),
        )
    }
    return pulse.value
}
