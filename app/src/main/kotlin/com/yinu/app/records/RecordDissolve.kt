package com.yinu.app.records

import android.animation.ValueAnimator
import android.graphics.Bitmap
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay

internal const val RecordDissolveMillis = 920

@Composable
internal fun recordDissolve(id: String, deleting: Boolean): Modifier {
    val progress by animateFloatAsState(if (deleting) 1f else 0f,
        tween(if (deleting) RecordDissolveMillis - 60 else 160, easing = LinearEasing), label = "Message removal")
    val host = LocalParticleDust.current
    val animated = ValueAnimator.areAnimatorsEnabled()
    if (!deleting && progress <= 0f) return Modifier
    if (!animated) return Modifier.graphicsLayer { alpha = if (deleting) 0f else 1f }
    val gpu = host?.supported == true
    var captured by remember(id) { mutableStateOf(false) }
    var fallback by remember(id) { mutableStateOf(!gpu) }
    var bounds by remember(id) { mutableStateOf(Rect.Zero) }
    val source = rememberGraphicsLayer()
    DisposableEffect(host, id) { onDispose { host?.release(id) } }
    LaunchedEffect(deleting) {
        if (!deleting) { host?.cancel(id); captured = false }
    }
    LaunchedEffect(captured) {
        if (!captured || !deleting || !gpu) return@LaunchedEffect
        var bitmap: Bitmap? = null
        try {
            withFrameNanos { }
            delay(140) // Let the confirmation sheet clear before the dust rises.
            val raw = source.toImageBitmap().asAndroidBitmap()
            withContext(Dispatchers.Default) {
                try { bitmap = raw.copy(Bitmap.Config.ARGB_8888, false) } finally { raw.recycle() }
            }
            val pixels = bitmap ?: error("Cannot copy message pixels")
            if (host?.submit(id, pixels, bounds) == true) bitmap = null else fallback = true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: RuntimeException) { fallback = true }
        catch (_: OutOfMemoryError) { fallback = true }
        finally { bitmap?.recycle() }
    }
    return Modifier.onGloballyPositioned {
        bounds = it.boundsInWindow()
        if (!bounds.isEmpty) host?.update(id, bounds)
    }.graphicsLayer {
        compositingStrategy = CompositingStrategy.ModulateAlpha
        if (fallback || !gpu || !deleting) {
            val t = progress.coerceIn(0f, 1f)
            alpha = 1f - t * t * (3f - 2f * t)
            translationY = -6f * density * t
        }
    }.drawWithContent {
        if (fallback || !gpu || !deleting) { drawContent(); return@drawWithContent }
        // Read back only one bounded snapshot. Large records use the cheap fade.
        if (!captured) {
            if (size.width * size.height > 1_100_000f || size.width > 4_096 || size.height > 4_096) {
                fallback = true
                drawContent()
            } else {
                source.record { this@drawWithContent.drawContent() }
                captured = true
                drawLayer(source)
            }
        } else if (host?.isDrawing(id) != true) drawLayer(source)
    }
}
