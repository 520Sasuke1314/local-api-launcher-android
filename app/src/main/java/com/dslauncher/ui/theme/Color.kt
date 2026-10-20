package com.dslauncher.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/**
 * 应用配色。
 *
 * 全部颜色都是「按当前模式取值」的 getter：深色沿用原配色，浅色是为其设计的
 * 亮色板。模式只由系统设置决定（跟随设备深浅色，应用内不提供开关），
 * [DeepSeekApiLauncherTheme] 在组合时把结果写进 [ThemeMode.isDark]。
 *
 * getter 内部读取 snapshot 状态，模式切换时所有读到这些颜色的组合点都会自动
 * 重组 —— 现有 UI 代码保持 `TextPrimary`、`CardBg` 这样的裸引用不变。
 */
object ThemeMode {
    /** 当前是否深色。仅由主题入口写入，其余代码只读。 */
    var isDark by mutableStateOf(true)
}

/** 一套完整的模式配色。深/浅两个实例共用此结构，方便按模式统一取值。 */
private data class Palette(
    val PrimaryBlue: Color,
    val PrimaryBlueLight: Color,
    val AccentGreen: Color,
    val ErrorRed: Color,
    val BgTop: Color,
    val BgBottom: Color,
    val CardBg: Color,
    val CardBorder: Color,
    val TextPrimary: Color,
    val TextSecondary: Color
)

private val Dark = Palette(
    PrimaryBlue = Color(0xFF2563EB),
    PrimaryBlueLight = Color(0xFF58A6FF),
    AccentGreen = Color(0xFF10B981),
    ErrorRed = Color(0xFFEF4444),
    BgTop = Color(0xFF0B1727),
    BgBottom = Color(0xFF0A111C),
    CardBg = Color(0xFF161E2D).copy(alpha = 0.75f),
    CardBorder = Color(0xFF58A6FF).copy(alpha = 0.18f),
    TextPrimary = Color(0xFFE6EDF7),
    TextSecondary = Color(0xFF9BB0C9)
)

private val Light = Palette(
    // 浅色背景上强调蓝用更深的值，保证图标/链接文字的对比度
    PrimaryBlue = Color(0xFF2563EB),
    PrimaryBlueLight = Color(0xFF1D6FE8),
    AccentGreen = Color(0xFF059669),
    ErrorRed = Color(0xFFDC2626),
    BgTop = Color(0xFFE4EDFA),
    BgBottom = Color(0xFFF5F8FE),
    CardBg = Color(0xFFFFFFFF).copy(alpha = 0.80f),
    CardBorder = Color(0xFF2563EB).copy(alpha = 0.16f),
    TextPrimary = Color(0xFF17233A),
    TextSecondary = Color(0xFF5B6C85)
)

private fun modePalette(dark: Boolean): Palette = if (dark) Dark else Light

val PrimaryBlue: Color get() = modePalette(ThemeMode.isDark).PrimaryBlue
val PrimaryBlueLight: Color get() = modePalette(ThemeMode.isDark).PrimaryBlueLight
val AccentGreen: Color get() = modePalette(ThemeMode.isDark).AccentGreen
val ErrorRed: Color get() = modePalette(ThemeMode.isDark).ErrorRed

val BgTop: Color get() = modePalette(ThemeMode.isDark).BgTop
val BgBottom: Color get() = modePalette(ThemeMode.isDark).BgBottom

val CardBg: Color get() = modePalette(ThemeMode.isDark).CardBg
val CardBorder: Color get() = modePalette(ThemeMode.isDark).CardBorder

val TextPrimary: Color get() = modePalette(ThemeMode.isDark).TextPrimary
val TextSecondary: Color get() = modePalette(ThemeMode.isDark).TextSecondary

/** Material 色板：深浅两套，随系统模式切换。 */
fun appColorScheme(dark: Boolean): ColorScheme {
    val c = modePalette(dark)
    return if (dark) {
        darkColorScheme(
            primary = c.PrimaryBlue,
            onPrimary = c.TextPrimary,
            secondary = c.AccentGreen,
            onSecondary = c.TextPrimary,
            error = c.ErrorRed,
            onError = c.TextPrimary,
            background = c.BgBottom,
            onBackground = c.TextPrimary,
            surface = c.CardBg,
            onSurface = c.TextPrimary,
            surfaceVariant = c.CardBg,
            onSurfaceVariant = c.TextSecondary,
            outline = c.CardBorder
        )
    } else {
        lightColorScheme(
            primary = c.PrimaryBlue,
            onPrimary = Color.White,
            secondary = c.AccentGreen,
            onSecondary = Color.White,
            error = c.ErrorRed,
            onError = Color.White,
            background = c.BgBottom,
            onBackground = c.TextPrimary,
            surface = c.CardBg,
            onSurface = c.TextPrimary,
            surfaceVariant = c.CardBg,
            onSurfaceVariant = c.TextSecondary,
            outline = c.CardBorder
        )
    }
}
