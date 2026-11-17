package com.yinu.designsystem

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.ui.platform.LocalContext
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.kyant.backdrop.AppThemeSnapshot

private val YinuColors = lightColorScheme(
    background = Color(0xFFF7F7F7),
    onBackground = Color(0xFF1A1A1A),
    primary = Color(0xFF3482FF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEAF2FF),
    onPrimaryContainer = Color(0xFF1A1A1A),
    surface = Color.White,
    onSurface = Color(0xFF1A1A1A),
    onSurfaceVariant = Color(0xFF777777),
    outline = Color(0xFF999999),
)

private val YinuDarkColors = darkColorScheme(
    background = Color(0xFF101010),
    onBackground = Color(0xFFE8E8E8),
    surface = Color(0xFF242424),
    onSurface = Color(0xFFE8E8E8),
    surfaceVariant = Color(0xFF333333),
    onSurfaceVariant = Color(0xFFB5B5B5),
    surfaceContainer = Color(0xFF242424),
    surfaceContainerHigh = Color(0xFF333333),
    primary = Color(0xFF77AAFF),
    onPrimary = Color(0xFF001B3F),
    primaryContainer = Color(0xFF22334F),
    onPrimaryContainer = Color(0xFFD6E5FF),
    secondaryContainer = Color(0xFF333333),
    onSecondaryContainer = Color(0xFFE0E0E0),
    outline = Color(0xFF999999),
    outlineVariant = Color(0xFF444444),
    error = Color(0xFFFFABA3),
    onError = Color(0xFF541C19),
    inverseSurface = Color(0xFFE6E6E6),
    inverseOnSurface = Color(0xFF242424),
    inversePrimary = Color(0xFF3482FF),
    surfaceTint = Color(0xFF77AAFF),
)

internal val LocalYinuDarkTheme = staticCompositionLocalOf { false }
internal val LocalLightweightBlur = staticCompositionLocalOf { false }

fun yinuColorScheme(darkTheme: Boolean) = if (darkTheme) YinuDarkColors else YinuColors

@Composable
fun YinuTheme(darkTheme: Boolean, lightweightBlur: Boolean = false, monet: Boolean = false, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colors = if (monet && Build.VERSION.SDK_INT >= 31) {
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else yinuColorScheme(darkTheme)
    SideEffect { AppThemeSnapshot.isDark.value = darkTheme }
    MaterialTheme(colorScheme = colors) {
        CompositionLocalProvider(
            LocalYinuDarkTheme provides darkTheme,
            LocalLightweightBlur provides lightweightBlur,
            LocalContentColor provides colors.onBackground,
            content = content,
        )
    }
}
