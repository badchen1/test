package com.kugo.mobile.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kugo.mobile.data.ThemeMode

/**
 * KMC 视觉令牌。
 *
 * 颜色采用偏冷的翡翠绿，浅色界面保持柔和留白，深色界面降低长时间操作时的眩光。
 * 字体、圆角与核心间距都在这里集中定义，页面和组件不再各自形成一套视觉语言。
 */
private val AccentLight = Color(0xFF08783C)
private val AccentDark = Color(0xFF54DE8A)

object KmcDimens {
    val ScreenPadding = 16.dp
    val SectionSpacing = 14.dp
    val ItemSpacing = 8.dp
    val CardPadding = 14.dp
    val ContentMaxWidth = 840.dp
    val ReadableContentMaxWidth = 720.dp
}

private val DefaultTypography = Typography()
private val KmcTypography = Typography(
    titleLarge = DefaultTypography.titleLarge.copy(
        fontSize = 24.sp,
        lineHeight = 30.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    titleMedium = DefaultTypography.titleMedium.copy(
        fontSize = 18.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    titleSmall = DefaultTypography.titleSmall.copy(
        fontSize = 15.sp,
        lineHeight = 21.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    bodyLarge = DefaultTypography.bodyLarge.copy(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = DefaultTypography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = DefaultTypography.bodySmall.copy(fontSize = 13.sp, lineHeight = 19.sp),
    labelLarge = DefaultTypography.labelLarge.copy(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Medium,
    ),
    labelMedium = DefaultTypography.labelMedium.copy(fontSize = 12.sp, lineHeight = 17.sp),
    labelSmall = DefaultTypography.labelSmall.copy(fontSize = 11.sp, lineHeight = 16.sp),
)

private val KmcShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(22.dp),
)

val KugoDarkColors: ColorScheme = darkColorScheme(
    primary = AccentDark,
    onPrimary = Color(0xFF003919),
    primaryContainer = Color(0xFF0A5C31),
    onPrimaryContainer = Color(0xFFC1F8D5),
    secondary = Color(0xFF8ED6C1),
    onSecondary = Color(0xFF00382E),
    background = Color(0xFF0B100E),
    onBackground = Color(0xFFE1E8E3),
    surface = Color(0xFF121915),
    onSurface = Color(0xFFE1E8E3),
    surfaceVariant = Color(0xFF1E2924),
    onSurfaceVariant = Color(0xFFBAC7BF),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF370001),
    outline = Color(0xFF46544C),
)

val KugoLightColors: ColorScheme = lightColorScheme(
    primary = AccentLight,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD2F7E0),
    onPrimaryContainer = Color(0xFF00210D),
    secondary = Color(0xFF256B5C),
    onSecondary = Color.White,
    background = Color(0xFFF5F8F6),
    onBackground = Color(0xFF18201B),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF18201B),
    surfaceVariant = Color(0xFFE4EEE8),
    onSurfaceVariant = Color(0xFF3E4A43),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    outline = Color(0xFFB8C6BD),
)

@Composable
fun KugoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) KugoDarkColors else KugoLightColors,
        typography = KmcTypography,
        shapes = KmcShapes,
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
