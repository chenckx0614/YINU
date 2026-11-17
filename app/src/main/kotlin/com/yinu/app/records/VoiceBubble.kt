package com.yinu.app.records

import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yinu.data.Attachment
import com.yinu.designsystem.YinuMenuIcons
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal val Attachment.isVoiceRecording: Boolean
    get() = mimeType == "audio/mp4" && fileName == "语音记录.m4a"

internal val LocalVoicePlayback = staticCompositionLocalOf<VoicePlayback?> { null }

internal class VoicePlayback(private val notify: (String) -> Unit) {
    private var player: MediaPlayer? = null
    var current by mutableStateOf<String?>(null)
        private set

    fun toggle(file: File) {
        val wasCurrent = current == file.path
        stop()
        if (wasCurrent) return
        val media = MediaPlayer()
        try {
            media.setDataSource(file.path)
            media.setOnPreparedListener {
                if (player === it) runCatching { it.start() }.onFailure { stop(); notify("无法播放语音") }
            }
            media.setOnCompletionListener { if (player === it) stop() }
            media.setOnErrorListener { source, _, _ ->
                if (player === source) { stop(); notify("无法播放语音") }
                true
            }
            player = media
            current = file.path
            media.prepareAsync()
        } catch (_: Exception) {
            if (player === media) stop() else media.release()
            notify("无法播放语音")
        }
    }

    fun stopIf(path: String) { if (current == path) stop() }

    fun stop() {
        val previous = player
        player = null
        current = null
        previous?.release()
    }
}

@Composable
internal fun VoiceBubble(
    attachment: Attachment,
    initialSeconds: Int?,
    modifier: Modifier,
    trailing: Boolean,
    onPlayBounds: ((Rect) -> Unit)? = null,
) {
    val file = attachment.localFile
    val player = LocalVoicePlayback.current
    val playing = file != null && player?.current == file.path
    val seconds by produceState(initialSeconds, file) {
        if (file != null && initialSeconds == null) withContext(Dispatchers.IO) {
            val duration = runCatching {
                val reader = MediaMetadataRetriever()
                try {
                    reader.setDataSource(file.path)
                    reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                } finally { reader.release() }
            }.getOrNull()
            duration?.takeIf { it > 0 }?.let(::voiceSeconds)
        }.let { value = it }
    }
    val extraSeconds = ((seconds ?: 1) - 1).coerceIn(0, 59)
    val label = when {
        playing -> "停止播放语音"
        file == null -> "语音，本地副本已移除"
        seconds == null -> "播放语音"
        else -> "播放 ${seconds} 秒语音"
    }
    val colors = MaterialTheme.colorScheme
    val color = if (file == null) colors.surfaceVariant else colors.primaryContainer
    Box(
        modifier.widthIn(min = (80 + extraSeconds * 1.3f).dp).height(40.dp)
            .drawBehind {
                drawVoiceSurface(Rect(Offset.Zero, size), color, 9.dp.toPx(), 5.dp.toPx(), sideTail = 1f, trailing = trailing)
            }
            .semantics { contentDescription = label },
        contentAlignment = if (trailing) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            val ink = if (file == null) colors.onSurfaceVariant else colors.onPrimaryContainer
            Text(seconds?.let { "${it}″" } ?: "语音", color = ink, fontSize = 14.sp, maxLines = 1)
            if (playing) {
                val pulse by rememberInfiniteTransition(label = "语音播放")
                    .animateFloat(0.45f, 1f, infiniteRepeatable(tween(300), RepeatMode.Reverse), label = "声波")
                Icon(YinuMenuIcons.voice, null, Modifier.size(18.dp), tint = ink.copy(alpha = pulse))
            } else Icon(YinuMenuIcons.voice, null, Modifier.size(18.dp), tint = ink)
            Canvas(Modifier.size(26.dp).then(
                if (onPlayBounds == null) Modifier else Modifier.onGloballyPositioned {
                    onPlayBounds(it.boundsInRoot())
                },
            )) {
                val radius = size.width / 2f
                drawCircle(if (file == null) colors.onSurfaceVariant else colors.primary)
                val controlInk = if (file == null) colors.surfaceVariant else colors.onPrimary
                if (playing) {
                    val bar = radius * 0.22f
                    for (x in listOf(-0.28f, 0.06f)) {
                        drawRoundRect(controlInk, center + Offset(radius * x, -radius * 0.33f),
                            Size(bar, radius * 0.66f), CornerRadius(bar / 3f))
                    }
                } else drawVoicePlay(center, radius, controlInk, 1f)
            }
        }
    }
}

internal fun DrawScope.drawVoicePlay(center: Offset, radius: Float, ink: Color, alpha: Float) {
    if (alpha <= 0f) return
    drawPath(Path().apply {
        moveTo(center.x - radius * 0.23f, center.y - radius * 0.38f)
        lineTo(center.x + radius * 0.40f, center.y)
        lineTo(center.x - radius * 0.23f, center.y + radius * 0.38f)
        close()
    }, ink.copy(alpha = alpha))
}
