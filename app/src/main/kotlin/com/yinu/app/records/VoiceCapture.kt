package com.yinu.app.records

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.haooz.chedule.ui.basic.NexioOverlayDialog
import com.haooz.chedule.ui.basic.NexioPickerActions
import com.haooz.chedule.ui.basic.NexioSearchFilter
import com.kyant.backdrop.Backdrop
import com.yinu.app.ai.AiServiceError
import com.yinu.app.ai.LocalAiService
import com.yinu.app.ai.speechEndpoint
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.sqrt

private const val VoiceMinimumMillis = 500L
private const val VoiceMaxMillis = 300_000
private const val VoiceCaptureDiameter = 56

internal enum class VoiceRelease { Send, Cancel, Transcribe }

internal fun voiceReleaseAt(point: Offset, width: Float, cancelAbove: Float): VoiceRelease =
    when {
        point.y >= -cancelAbove -> VoiceRelease.Send
        point.x < width / 2 -> VoiceRelease.Cancel
        else -> VoiceRelease.Transcribe
    }

internal data class VoiceFlight(val seconds: Int, val amplitude: Float)
internal val LocalVoiceCapture = staticCompositionLocalOf<VoiceCapture?> { null }

internal class VoiceCapture(
    private val context: Context,
    private val scope: CoroutineScope,
    private val records: RecordsViewModel,
    private val playback: VoicePlayback,
) {
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L
    private var attempt = 0
    private var opening = false
    var finishing by mutableStateOf(false)
        private set
    private var flight: VoiceFlight? = null
    var pendingCard by mutableStateOf<VoiceFlight?>(null)
        private set
    var recording by mutableStateOf(false)
        private set
    var reviewFile by mutableStateOf<File?>(null)
        private set
    var sending by mutableStateOf(false)
        private set
    var release by mutableStateOf(VoiceRelease.Send)
    var elapsedMillis by mutableLongStateOf(0L)
        private set
    var amplitude by mutableFloatStateOf(0f)
        private set
    var origin: MessageOrigin? = null
    var composerBounds by mutableStateOf(Rect.Zero)
    var displayedAmplitude = 0f
    val active get() = recording || finishing || reviewFile != null || sending
    val showCard get() = recording || (finishing && release == VoiceRelease.Send) || pendingCard != null

    fun clearPendingCard(card: VoiceFlight) {
        if (pendingCard === card) pendingCard = null
    }

    fun start(): Boolean {
        if (active || opening) return false
        pendingCard = null
        playback.stop()
        release = VoiceRelease.Send
        elapsedMillis = 0L
        amplitude = 0f
        displayedAmplitude = 0f
        origin = null
        val generation = ++attempt
        recording = true
        opening = true
        scope.launch {
            var created: Pair<MediaRecorder, File>? = null
            try {
                withContext(Dispatchers.IO + NonCancellable) { created = openRecorder() }
                val (input, output) = checkNotNull(created)
                if (generation == attempt && recording) {
                    recorder = input
                    file = output
                    startedAt = SystemClock.elapsedRealtime()
                    created = null
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (generation == attempt) {
                    recording = false
                    android.util.Log.w("YinuVoice", "Unable to start recorder", error)
                    records.notify("无法开始录音，请检查麦克风权限")
                }
            } finally {
                opening = false
                created?.let { (input, output) ->
                    withContext(Dispatchers.IO + NonCancellable) {
                        runCatching { input.release() }
                        output.delete()
                    }
                }
            }
        }
        return true
    }

    private fun openRecorder(): Pair<MediaRecorder, File> {
        val output = File.createTempFile("voice-", ".m4a", context.cacheDir)
        var media: MediaRecorder? = null
        try {
            @Suppress("DEPRECATION")
            val input = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
            media = input
            input.setAudioSource(MediaRecorder.AudioSource.MIC)
            input.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            input.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            input.setMaxDuration(VoiceMaxMillis)
            input.setOnInfoListener { source, what, _ ->
                if (recorder === source && what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                    finish(VoiceRelease.Transcribe)
                }
            }
            input.setOnErrorListener { source, _, _ ->
                if (recorder === source) {
                    cancelRecording()
                    records.notify("录音中断，请重试")
                }
            }
            input.setOutputFile(output.absolutePath)
            input.prepare()
            input.start()
            return input to output
        } catch (error: Exception) {
            media?.release()
            output.delete()
            throw error
        }
    }

    suspend fun sample() {
        val input = recorder ?: return
        elapsedMillis = (SystemClock.elapsedRealtime() - startedAt) / 1_000 * 1_000
        val level = withContext(Dispatchers.IO) { runCatching { input.maxAmplitude }.getOrDefault(0) }
        if (recorder === input) amplitude = sqrt(level / 32767f).coerceIn(0f, 1f)
    }

    fun finish(action: VoiceRelease) {
        if (!recording) return
        release = action
        recording = false
        attempt++
        val input = recorder
        recorder = null
        val output = file
        file = null
        if (input == null || output == null) {
            if (action != VoiceRelease.Cancel) records.notify("录音太短，请再按住一会儿")
            return
        }
        val elapsed = SystemClock.elapsedRealtime() - startedAt
        val keep = action != VoiceRelease.Cancel && elapsed >= VoiceMinimumMillis
        finishing = true
        if (!keep && action == VoiceRelease.Send) release = VoiceRelease.Cancel
        scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            var retained = false
            try {
                val valid = withContext(Dispatchers.IO + NonCancellable) {
                    val stopped = keep && runCatching { input.stop() }.isSuccess
                    runCatching { input.release() }
                    stopped && output.length() > 0
                }
                if (!valid) {
                    if (action != VoiceRelease.Cancel) records.notify("录音太短或录制失败，请重试")
                    return@launch
                }
                file = output
                flight = VoiceFlight(voiceSeconds(elapsed), displayedAmplitude)
                retained = true
                if (action == VoiceRelease.Transcribe) reviewFile = output else send("")
            } finally {
                finishing = false
                if (!retained) output.delete()
            }
        }
    }

    fun cancelRecording() {
        if (recording) finish(VoiceRelease.Cancel)
    }

    fun discard() {
        if (sending) return
        reviewFile?.let { playback.stopIf(it.path) }
        reviewFile = null
        file?.delete()
        file = null
        flight = null
    }

    fun send(caption: String) {
        val output = file ?: return
        if (sending || recording) return
        playback.stopIf(output.path)
        val from = origin.takeIf { reviewFile == null }
        val motion = flight.takeIf { from != null }
        pendingCard = motion
        sending = true
        scope.launch {
            try {
                records.saveVoice(output, caption, from, motion)
                reviewFile = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                pendingCard = null
                records.notify("无法保存语音，请检查存储空间")
                reviewFile = output
            } finally {
                sending = false
                if (reviewFile == null) discard()
            }
        }
    }

    fun saveTranscript(text: String) {
        if (text.isBlank() || sending || reviewFile == null) return
        sending = true
        scope.launch {
            try {
                records.saveVoiceTranscript(text)
                reviewFile?.let { playback.stopIf(it.path) }
                reviewFile = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                records.notify("无法保存转写文字，请重试")
            } finally {
                sending = false
                if (reviewFile == null) discard()
            }
        }
    }

    fun dispose() {
        cancelRecording()
        // A pending send owns the file until its coroutine completes.
        if (!sending) discard()
        reviewFile = null
    }
}

@Composable
internal fun rememberVoiceCapture(records: RecordsViewModel, playback: VoicePlayback): VoiceCapture {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val capture = remember(context, records, scope, playback) { VoiceCapture(context, scope, records, playback) }
    DisposableEffect(capture) { onDispose(capture::dispose) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { capture.cancelRecording(); playback.stop() }
    LaunchedEffect(capture.recording) {
        while (capture.recording) {
            capture.sample()
            delay(65)
        }
    }
    return capture
}

internal fun Modifier.holdToRecord(
    capture: VoiceCapture,
    enabled: Boolean,
    permitted: Boolean,
    requestPermission: () -> Unit,
): Modifier = pointerInput(capture, enabled, permitted) {
    if (!enabled) return@pointerInput
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val held = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
        if (!permitted) {
            requestPermission()
            return@awaitEachGesture
        }
        if (!capture.start()) return@awaitEachGesture
        try {
            while (capture.recording) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == held.id } ?: break
                if (change.isConsumed) break
                val shift = change.position - held.position
                capture.release = if (shift.x <= -100.dp.toPx() && shift.y < 28.dp.toPx()) VoiceRelease.Cancel
                    else voiceReleaseAt(change.position, size.width.toFloat(), 54.dp.toPx())
                change.consume()
                if (capture.release == VoiceRelease.Cancel) {
                    capture.finish(VoiceRelease.Cancel)
                    break
                }
                if (!change.pressed) {
                    capture.finish(capture.release)
                    break
                }
            }
        } finally {
            capture.cancelRecording()
        }
    }
}

@Composable
internal fun VoiceCaptureOverlay(capture: VoiceCapture, playback: VoicePlayback, backdrop: Backdrop, activeFlight: VoiceFlight?) {
    val colors = androidx.compose.material3.MaterialTheme.colorScheme
    val pending = capture.pendingCard
    val handedOff = pending != null && activeFlight === pending
    LaunchedEffect(pending, capture.sending, handedOff) {
        if (pending != null) {
            if (handedOff) capture.clearPendingCard(pending)
            else if (!capture.sending) {
                delay(350)
                capture.clearPendingCard(pending)
            }
        }
    }
    val visible = capture.showCard && !handedOff && !capture.composerBounds.isEmpty
    val amplitude = animateFloatAsState(capture.amplitude, tween(95), label = "录音振幅")
    val scale = animateFloatAsState(if (visible) 1f else 0f,
        if (visible) spring(dampingRatio = 0.77f, stiffness = Spring.StiffnessMedium)
        else tween(if (activeFlight == null) 155 else 0), label = "录音圆钮")
    var rootOffset by remember { mutableStateOf(Offset.Zero) }
    BackHandler(enabled = capture.recording) { capture.cancelRecording() }
    Canvas(Modifier.fillMaxSize().onGloballyPositioned { rootOffset = it.boundsInRoot().topLeft }) {
        val bounds = capture.composerBounds
        if (bounds.isEmpty || scale.value <= 0f || handedOff) return@Canvas
        val center = Offset(bounds.right - 24.dp.toPx(), bounds.center.y) - rootOffset
        val radius = (VoiceCaptureDiameter / 2).dp.toPx() * scale.value
        val level = amplitude.value
        if (capture.recording) {
            val rootCenter = center + rootOffset
            capture.origin = MessageOrigin(rootCenter.x - radius, rootCenter.y - radius, radius * 2, radius * 2)
            capture.displayedAmplitude = level
        }
        drawVoicePulse(center, radius, colors.primary, level, scale.value.coerceAtMost(1f))
        drawCircle(colors.primary, radius, center)
        drawVoiceMic(center, radius, colors.onPrimary, 1f)
    }
    capture.reviewFile?.let { file ->
        VoiceTranscriptionDialog(capture, file, playback, backdrop)
    }
}

@Composable
private fun VoiceTranscriptionDialog(capture: VoiceCapture, file: File, playback: VoicePlayback, backdrop: Backdrop) {
    val service = LocalAiService.current
    val settings = service.speechOptions
    val host = remember(settings.url) { speechEndpoint(settings.url).host }
    val scope = rememberCoroutineScope()
    var text by remember(file) { mutableStateOf<String?>(null) }
    var status by remember(file) { mutableStateOf<String?>(null) }
    var showing by remember(file) { mutableStateOf(true) }
    var request by remember(file) { mutableStateOf<Job?>(null) }

    fun close() {
        if (capture.sending) return
        request?.cancel()
        showing = false
    }

    fun transcribe() {
        request = scope.launch {
            status = null
            try {
                val result = service.transcribe(file)
                if (result.isBlank()) status = "没有识别到清晰的文字，可以重试或发送原语音。"
                else text = result
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: AiServiceError) {
                status = error.message
            } catch (_: Exception) {
                status = "联网转写失败，可以重试或发送原语音。"
            } finally {
                request = null
            }
        }
    }

    val available = showing && request == null && !capture.sending
    val result = text
    val summary = when {
        request != null -> "正在联网转写…"
        status != null -> status
        !settings.hasKey -> "请先到「设置 → AI 服务 → 联网语音转写」配置服务。"
        result != null -> "可以编辑识别结果，再保存为文字记录。"
        else -> "确认后将这一段录音上传至 $host 进行转写。"
    }
    NexioOverlayDialog(showing, "语音转文字", summary, backdrop, ::close, capture::discard) {
        Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NexioSearchFilter(if (playback.current == file.path) "停止试听" else "试听", false,
                enabled = showing && !capture.sending) { playback.toggle(file) }
            NexioSearchFilter("发送原语音", false, enabled = available) { capture.send("") }
        }
        if (result != null) CaptureField(result, { text = it }, "识别结果可编辑", available)
        val action = when {
            capture.sending -> "正在保存…"
            result != null -> "保存文字"
            status != null -> "重试转写"
            else -> "联网转写"
        }
        NexioPickerActions(action, showing && !capture.sending,
            confirmEnabled = available && (result?.isNotBlank() ?: settings.hasKey),
            onCancel = ::close,
            onConfirm = {
                if (available) {
                    if (result != null) capture.saveTranscript(result)
                    else if (settings.hasKey) transcribe()
                }
            })
    }
}

internal fun DrawScope.drawVoicePulse(center: Offset, radius: Float, color: Color, level: Float, strength: Float) {
    if (strength <= 0f) return
    val spread = (5f + 9f * level).dp.toPx() * strength
    drawCircle(color.copy(alpha = 0.10f * strength), radius + spread * 1.7f, center)
    drawCircle(color.copy(alpha = 0.19f * strength), radius + spread, center)
}

internal fun DrawScope.drawVoiceMic(center: Offset, radius: Float, ink: Color, alpha: Float) {
    if (alpha <= 0f || radius <= 0f) return
    val color = ink.copy(alpha = alpha)
    val weight = radius * 0.095f
    drawRoundRect(color, center + Offset(-radius * 0.16f, -radius * 0.44f),
        androidx.compose.ui.geometry.Size(radius * 0.32f, radius * 0.59f), CornerRadius(radius * 0.16f))
    drawArc(color, 0f, 180f, false, center + Offset(-radius * 0.31f, -radius * 0.26f),
        androidx.compose.ui.geometry.Size(radius * 0.62f, radius * 0.58f),
        style = androidx.compose.ui.graphics.drawscope.Stroke(weight, cap = StrokeCap.Round))
    drawLine(color, center + Offset(0f, radius * 0.33f), center + Offset(0f, radius * 0.47f), weight, StrokeCap.Round)
    drawLine(color, center + Offset(-radius * 0.15f, radius * 0.47f),
        center + Offset(radius * 0.15f, radius * 0.47f), weight, StrokeCap.Round)
}

internal fun DrawScope.drawVoiceSurface(rect: Rect, color: Color, radius: Float, tail: Float, sideTail: Float, trailing: Boolean = true) {
    if (rect.isEmpty) return
    val inset = tail * sideTail
    val body = if (trailing) rect.copy(right = rect.right - inset) else rect.copy(left = rect.left + inset)
    drawRoundRect(color, body.topLeft, body.size, CornerRadius(radius))
    val x = body.center.x
    val y = body.top + body.height * 0.48f
    val edge = if (trailing) body.right else body.left
    val inner = edge + if (trailing) -1f else 1f
    val outer = if (trailing) rect.right else rect.left
    val a = lerp(Offset(x - tail, body.bottom - 1f), Offset(inner, y + tail), sideTail)
    val b = lerp(Offset(x + tail, body.bottom - 1f), Offset(inner, y - tail), sideTail)
    val tip = lerp(Offset(x, body.bottom + tail - 1f), Offset(outer, y), sideTail)
    drawPath(Path().apply {
        moveTo(a.x, a.y)
        lineTo(b.x, b.y)
        lineTo(tip.x, tip.y)
        close()
    }, color)
}

internal fun voiceSeconds(millis: Long): Int = ((millis + 999) / 1000).coerceAtLeast(1).toInt()

internal fun formatVoiceTime(millis: Long): String {
    val seconds = millis / 1000
    return "${(seconds / 60).toString().padStart(2, '0')}:${(seconds % 60).toString().padStart(2, '0')}"
}
