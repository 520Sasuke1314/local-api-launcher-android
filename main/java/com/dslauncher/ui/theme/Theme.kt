package com.dslauncher.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect

/**
 * 应用主题入口。
 *
 * 深浅色**完全跟随系统设置**：设备深色 → 深色主题，设备浅色 → 浅色主题，
 * 应用内不提供切换开关。结果写入 [ThemeMode.isDark]，供全应用的颜色 getter
 * 与 Material 色板共同使用。
 */
@Composable
fun DeepSeekApiLauncherTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    SideEffect { ThemeMode.isDark = dark }
    MaterialTheme(
        colorScheme = appColorScheme(dark),
        typography = AppTypography,
        content = content
    )
}
