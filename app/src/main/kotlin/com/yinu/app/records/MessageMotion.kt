package com.yinu.app.records

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// UI-only geometry associated with a newly committed record; never persisted.
internal data class MessageOrigin(val x: Float, val y: Float, val width: Float, val height: Float)
internal data class PendingMessageMotion(
    val origin: MessageOrigin?,
    val attachment: Boolean,
    val committedAt: Long = 0,
    val voice: VoiceFlight? = null,
)
internal const val MessageFlightMillis = 320

private val FlightVertical = CubicBezierEasing(0.20f, 0.01f, 0.28f, 0.91f)
private val FlightHorizontal = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)
private val VoiceTravel = CubicBezierEasing(0.36f, 0f, 0.22f, 1f)
private val VoiceHorizontal = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
private const val VoiceFlightMillis = 260

internal data class FlightFrame(val x: Float, val y: Float, val scale: Float, val alpha: Float)
internal fun flightFrame(origin: Offset, destination: Offset, time: Float, attachment: Boolean): FlightFrame {
    val t = time.coerceIn(0f, 1f)
    val horizontal = FlightHorizontal.transform(t)
    val vertical = FlightVertical.transform(t)
    return FlightFrame(
        origin.x + (destination.x - origin.x) * horizontal,
        origin.y + (destination.y - origin.y) * vertical,
        (if (attachment) 0.88f else 0.98f) + (if (attachment) 0.12f else 0.02f) * horizontal,
        0.82f + 0.18f * (t / 0.22f).coerceAtMost(1f),
    )
}

private class MessageFlight(val layer: GraphicsLayer, val spec: PendingMessageMotion, var source: Rect,
    var anchor: Rect, val progress: Animatable<Float, androidx.compose.animation.core.AnimationVector1D>)

internal class MessageFlightHost {
    private val flights = mutableStateMapOf<String, MessageFlight>()
    var bounds = Rect.Zero
    val activeVoiceFlight get() = flights.values.firstNotNullOfOrNull { it.spec.voice }
    fun update(id: String, bounds: Rect, anchor: Rect) {
        flights[id]?.let { it.source = bounds; it.anchor = anchor }
    }
    suspend fun animate(id: String, layer: GraphicsLayer, spec: PendingMessageMotion, bounds: Rect, anchor: Rect) {
        val progress = Animatable(0f)
        flights[id] = MessageFlight(layer, spec, bounds, anchor, progress)
        val duration = if (spec.voice == null) MessageFlightMillis else VoiceFlightMillis
        try { progress.animateTo(1f, tween(duration, easing = LinearEasing)) }
        finally { flights.remove(id) }
    }
    internal fun draw(scope: androidx.compose.ui.graphics.drawscope.DrawScope, textInset: Offset, voiceFill: Color, voiceInk: Color) = with(scope) {
        for (flight in flights.values) {
            val source = flight.spec.origin
            val anchor = flight.anchor
            val destination = flight.source.topLeft - bounds.topLeft
            val voice = flight.spec.voice
            if (source != null && voice != null && !anchor.isEmpty) {
                val t = flight.progress.value
                val target = anchor.translate(-bounds.topLeft)
                val start = Rect(source.x, source.y, source.x + source.width, source.y + source.height)
                    .translate(-bounds.topLeft)
                val frame = voiceFlightFrame(start, target, t)
                val reveal = voicePhase(t, 0.16f, 0.86f)
                val rings = 1f - voicePhase(t, 0f, 0.60f)
                val orb = 1f - voicePhase(t, 0.70f, 0.99f)
                val radius = frame.width / 2f
                if (reveal > 0f) {
                    flight.layer.alpha = reveal
                    val trailingOffset = (frame.center - target.center) * (1f - reveal)
                    translate(destination.x + trailingOffset.x, destination.y + trailingOffset.y) {
                        drawLayer(flight.layer)
                    }
                }
                drawVoicePulse(frame.center, radius, voiceFill, voice.amplitude, rings)
                if (orb > 0f) {
                    drawCircle(voiceFill.copy(alpha = orb), radius, frame.center)
                    drawVoiceMic(frame.center, radius, voiceInk, orb * (1f - voicePhase(t, 0.28f, 0.72f)))
                    drawVoicePlay(frame.center, radius, voiceInk, orb * voicePhase(t, 0.40f, 0.74f))
                }
                continue
            }
            val offset = if (source == null) Offset(0f, 28.dp.toPx()) else {
                val targetAnchor = anchor.topLeft + if (flight.spec.attachment) Offset.Zero else textInset
                Offset(source.x, source.y) - targetAnchor
            }
            val frame = flightFrame(destination + offset, destination, flight.progress.value, flight.spec.attachment)
            flight.layer.alpha = frame.alpha
            translate(frame.x, frame.y) {
                scale(frame.scale, frame.scale, anchor.center - flight.source.topLeft) { drawLayer(flight.layer) }
            }
        }
    }
}

internal fun voiceFlightFrame(start: Rect, target: Rect, time: Float): Rect {
    val fraction = time.coerceIn(0f, 1f)
    if (fraction == 0f) return start
    if (fraction == 1f) return target
    val horizontal = VoiceHorizontal.transform(fraction)
    val vertical = VoiceTravel.transform(fraction)
    val shape = lerp(start, target, vertical)
    val x = start.center.x + (target.center.x - start.center.x) * horizontal
    return shape.translate(Offset(x - shape.center.x, 0f))
}

private fun voicePhase(time: Float, start: Float, end: Float): Float {
    val fraction = ((time - start) / (end - start)).coerceIn(0f, 1f)
    return fraction * fraction * (3f - 2f * fraction)
}

internal val LocalMessageFlightHost = staticCompositionLocalOf<MessageFlightHost?> { null }

@Composable
internal fun MessageFlightOverlay(host: MessageFlightHost, topPadding: Dp) {
    val inset = with(LocalDensity.current) { Offset(13.dp.toPx(), 10.dp.toPx()) }
    val colors = MaterialTheme.colorScheme
    Canvas(Modifier.fillMaxSize().onGloballyPositioned { host.bounds = it.boundsInRoot() }) {
        clipRect(top = topPadding.toPx()) { host.draw(this, inset, colors.primary, colors.onPrimary) }
    }
}

@Composable
internal fun messageFlight(id: String, records: RecordsViewModel, anchor: () -> Rect): Modifier {
    val host = LocalMessageFlightHost.current ?: return Modifier
    val pending = remember(id) { records.pendingBubbleMotion(id) } ?: return Modifier
    // Claim at composition, even when the first frame has not been drawn yet.
    // Recreated pages must not pick up the same pending send a second time.
    LaunchedEffect(id) { records.consumeBubbleAnimation(id) }
    val fresh = pending.committedAt == 0L || SystemClock.uptimeMillis() - pending.committedAt < 1_500
    var completed by remember(id) { mutableStateOf(!fresh) }
    if (completed || !android.animation.ValueAnimator.areAnimatorsEnabled()) {
        return Modifier
    }
    var captured by remember(id) { mutableStateOf(false) }
    var failed by remember(id) { mutableStateOf(false) }
    var bounds by remember(id) { mutableStateOf(Rect.Zero) }
    val layer = rememberGraphicsLayer()
    layer.compositingStrategy = CompositingStrategy.ModulateAlpha
    LaunchedEffect(captured, failed) {
        if (!captured && !failed) return@LaunchedEffect
        if (!failed && !bounds.isEmpty) {
            host.animate(id, layer, pending, bounds, anchor().takeUnless { it.isEmpty } ?: bounds)
        }
        completed = true
    }
    return Modifier.onGloballyPositioned { bounds = it.boundsInRoot() }.drawWithContent {
        if (captured) host.update(id, bounds, anchor().takeUnless { it.isEmpty } ?: bounds)
        if (!captured && !failed) {
            try {
                if (size.width * size.height > 1_500_000f || size.height > host.bounds.height * 0.95f) {
                    failed = true
                } else { layer.record { this@drawWithContent.drawContent() }; captured = true }
            }
            catch (_: RuntimeException) { failed = true }
        }
        if (failed) drawContent()
        // The overlay replays this display list above the composer during flight.
    }
}
