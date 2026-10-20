package com.ashchan.cloudsaver.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// ============ 主题模式：自动跟随系统 / 强制亮 / 强制暗 ============

const val THEME_AUTO = 0
const val THEME_LIGHT = 1
const val THEME_DARK = 2

// ============ 暖橘奶油 · M3 Expressive 配色 ============

private val WarmOrangeLight = lightColorScheme(
    primary = Color(0xFFB5480A),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDCC2),
    onPrimaryContainer = Color(0xFF3A1500),
    inversePrimary = Color(0xFFFFB68A),

    secondary = Color(0xFF77574B),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF8DDC9),
    onSecondaryContainer = Color(0xFF2C160B),

    tertiary = Color(0xFF6B5733),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF2E0BC),
    onTertiaryContainer = Color(0xFF241A04),

    background = Color(0xFFFCF7F1),
    onBackground = Color(0xFF231A14),
    surface = Color(0xFFFCF7F1),
    onSurface = Color(0xFF231A14),

    surfaceVariant = Color(0xFFF1DED1),
    onSurfaceVariant = Color(0xFF52453B),
    outline = Color(0xFF85756A),
    outlineVariant = Color(0xFFD7C4B8),
    scrim = Color(0xFF000000),

    inverseSurface = Color(0xFF362B23),
    inverseOnSurface = Color(0xFFFCEFE5),

    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),

    surfaceTint = Color(0xFFB5480A),

    // 容器层级色：弹窗/菜单/卡片抬起时的底色。缺了这套 M3 会回退到冷灰紫默认色，
    // 导致弹窗与主界面暖奶油色调割裂（色调不一致）。
    surfaceContainerLowest = Color(0xFFFCF7F1),
    surfaceContainerLow = Color(0xFFF8F1E6),
    surfaceContainer = Color(0xFFF3E8DA),
    surfaceContainerHigh = Color(0xFFEEE2D2),
    surfaceContainerHighest = Color(0xFFE9DCCB),
)

private val WarmOrangeDark = darkColorScheme(
    primary = Color(0xFFFFB68A),
    onPrimary = Color(0xFF5A1900),
    primaryContainer = Color(0xFF8C4100),
    onPrimaryContainer = Color(0xFFFFDCC2),
    inversePrimary = Color(0xFFB5480A),

    secondary = Color(0xFFE0BFAC),
    onSecondary = Color(0xFF43291D),
    secondaryContainer = Color(0xFF5C4135),
    onSecondaryContainer = Color(0xFFF8DDC9),

    tertiary = Color(0xFFD9C59E),
    onTertiary = Color(0xFF3B2D08),
    tertiaryContainer = Color(0xFF534311),
    onTertiaryContainer = Color(0xFFF2E0BC),

    background = Color(0xFF1B1410),
    onBackground = Color(0xFFEDE0D8),
    surface = Color(0xFF1B1410),
    onSurface = Color(0xFFEDE0D8),

    surfaceVariant = Color(0xFF3B322C),
    onSurfaceVariant = Color(0xFFD1C2B6),
    outline = Color(0xFF9A8B7F),
    outlineVariant = Color(0xFF4E433B),
    scrim = Color(0xFF000000),

    inverseSurface = Color(0xFFEDE0D8),
    inverseOnSurface = Color(0xFF362B23),

    error = Color(0xFFFFB4A9),
    onError = Color(0xFF680003),
    errorContainer = Color(0xFF930006),
    onErrorContainer = Color(0xFFFFDAD6),

    surfaceTint = Color(0xFFFFB68A),

    surfaceContainerLowest = Color(0xFF1B1410),
    surfaceContainerLow = Color(0xFF231A14),
    surfaceContainer = Color(0xFF2C231B),
    surfaceContainerHigh = Color(0xFF362B22),
    surfaceContainerHighest = Color(0xFF413428),
)

// 圆角统一偏大，营造 M3 Expressive 的柔软感
val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

// 统一的「成功 / 已登录」绿色（与暖橘奶油主题协调，替代硬编码的冷绿）
val SuccessGreen = Color(0xFF4FA864)

// 「有新版本」提示红点（固定亮红，深浅主题下都醒目）
val UpdateDotRed = Color(0xFFE53935)

// 状态栏背景：贴合各自 surface，避免突兀的黑白条
val StatusBarLight = 0xFFF3E7DB.toInt()
val StatusBarDark = 0xFF1B1410.toInt()

@Composable
fun CloudSaverTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) WarmOrangeDark else WarmOrangeLight,
        shapes = AppShapes,
        content = content,
    )
}
