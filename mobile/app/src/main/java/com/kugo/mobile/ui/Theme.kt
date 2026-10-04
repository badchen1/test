package com.kugo.mobile.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.kugo.mobile.data.ThemeMode

/**
 * 应用配色。
 *
 * 采用深色为主的音乐工具风格：主色取酷狗系的青绿，
 * 容器色压暗以保证长时间操作不刺眼。
 *
 * 两套 ColorScheme 提升为公开常量，是为了让「圆形主题切换」能在切换瞬间
 * 拿到**旧主题**的背景色去铺遮盖层——否则动画期间会看到新旧的硬切。
 */
private val Accent = Color(0xFF1DB954)
private val AccentDim = Color(0xFF14833B)

val KugoDarkColors: ColorScheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF00210C),
    primaryContainer = AccentDim,
    onPrimaryContainer = Color(0xFFB8F5CE),
    secondary = Color(0xFF8FD8FF),
    onSecondary = Color(0xFF00344A),
    background = Color(0xFF0F1113),
    onBackground = Color(0xFFE3E4E6),
    surface = Color(0xFF15181B),
    onSurface = Color(0xFFE3E4E6),
    surfaceVariant = Color(0xFF22262A),
    onSurfaceVariant = Color(0xFFBFC4C9),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF2B0000),
    outline = Color(0xFF3A4046),
)

val KugoLightColors: ColorScheme = lightColorScheme(
    primary = AccentDim,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB8F5CE),
    onPrimaryContainer = Color(0xFF00210C),
    secondary = Color(0xFF00658F),
    onSecondary = Color.White,
    background = Color(0xFFF7F9FA),
    onBackground = Color(0xFF191C1E),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF191C1E),
    surfaceVariant = Color(0xFFE6EAED),
    onSurfaceVariant = Color(0xFF41484D),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    outline = Color(0xFFC3C8CC),
)

@Composable
fun KugoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) KugoDarkColors else KugoLightColors,
        content = content,
    )
}

/** 把用户选择的主题模式解析成「当前是否深色」。 */
fun ThemeMode.resolveDark(isSystemDark: Boolean): Boolean = when (this) {
    ThemeMode.System -> isSystemDark
    ThemeMode.Light -> false
    ThemeMode.Dark -> true
}

/** 当前模式对应的配色，用于取遮盖层的颜色。 */
fun schemeFor(isDark: Boolean): ColorScheme = if (isDark) KugoDarkColors else KugoLightColors
