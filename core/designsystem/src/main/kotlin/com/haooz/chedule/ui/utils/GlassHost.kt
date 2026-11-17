package com.haooz.chedule.ui.utils

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.edgelight.EdgeLight
import com.kyant.backdrop.edgelight.EdgeLightStyle
import com.kyant.backdrop.isRenderEffectSupported
import com.yinu.designsystem.LocalYinuDarkTheme

internal val AppThemeSnapshot = com.kyant.backdrop.AppThemeSnapshot
internal val PageBackdropSnapshot = com.kyant.backdrop.PageBackdropSnapshot

@Composable
internal fun isAppDarkTheme() = LocalYinuDarkTheme.current

/** App-side material policy for NexioSchedule's original glass components. */
internal object AppMaterialSettings {
    const val level = "balanced"

    fun chromeLensEnabled() = false

    fun glassEffectsEnabled() = isRenderEffectSupported()

    fun glassBlurDp() = if (glassEffectsEnabled()) 8f else 0f

    fun solidSurfaceAlpha(alpha: Float) = if (glassEffectsEnabled()) alpha else 1f

    fun resolveEdgeLight(source: EdgeLight, isLightTheme: Boolean, baseColor: Color?): EdgeLight {
        if (glassEffectsEnabled()) return source
        val stroke = baseColor?.let {
            Color(it.red + (1f - it.red) * 0.2f, it.green + (1f - it.green) * 0.2f, it.blue + (1f - it.blue) * 0.2f)
        } ?: if (isLightTheme) Color.White else Color(0xFF333333)
        return EdgeLight(
            width = 0.8.dp,
            blurRadius = 0.dp,
            intensity = source.intensity,
            style = EdgeLightStyle.Uniform(stroke, BlendMode.SrcOver),
        )
    }
}
