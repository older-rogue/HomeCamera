package com.zx.homecamera.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColorScheme = lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40,
    background = BackgroundLight,
    surface = Color.White,
    onSurface = OnSurfaceDark,
    onSurfaceVariant = OnSurfaceVariant,
    surfaceContainer = SurfaceContainer,
    error = ErrorColor,
    errorContainer = ErrorContainer,
    outline = OutlineColor,
)

@Composable
fun HomeCameraTheme(
    content: @Composable () -> Unit,
) {
    // 统一使用浅色主题，不跟随系统暗色模式，
    // 避免在 Android 16 等暗色系统下 item 背景变黑。
    MaterialTheme(
        colorScheme = LightColorScheme,
        typography = Typography,
        content = content,
    )
}