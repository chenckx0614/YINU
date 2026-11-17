package com.yinu.app.records

import android.graphics.SurfaceTexture
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.haooz.chedule.ui.basic.NexioSearchFilter
import com.kyant.capsule.ContinuousRoundedRectangle
import java.io.File
import java.util.Locale
import kotlinx.coroutines.delay

@Composable
internal fun MediaPreview(file: File, video: Boolean) {
    val context = LocalContext.current
    val player = remember(file) { PreviewPlayer(context.getSystemService(AudioManager::class.java)) }
    var seek by remember(file) { mutableStateOf<Float?>(null) }
    DisposableEffect(player) {
        player.prepare(file)
        onDispose(player::release)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { player.pause() }
    LaunchedEffect(player.playing) {
        while (player.playing) {
            player.updatePosition()
            delay(250)
        }
    }

    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            if (video && !player.failed) {
                AndroidView(
                    factory = { viewContext ->
                        TextureView(viewContext).apply { surfaceTextureListener = player.textureListener() }
                    },
                    modifier = Modifier.aspectRatio(player.aspectRatio)
                        .clip(ContinuousRoundedRectangle(18.dp)).background(Color.Black),
                )
            }
            when {
                player.failed -> Text("设备无法播放此文件，可尝试「外部打开」")
                !player.ready -> CircularProgressIndicator()
                !video -> Text("音频预览", color = colors.onSurfaceVariant)
            }
        }
        if (player.ready) {
            val duration = player.duration
            if (duration > 0) {
                Slider(
                    value = seek ?: player.position.toFloat().coerceIn(0f, duration.toFloat()),
                    onValueChange = { seek = it },
                    onValueChangeFinished = {
                        seek?.let { player.seekTo(it.toInt()) }
                        seek = null
                    },
                    valueRange = 0f..duration.toFloat(),
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(mediaTime(seek?.toInt() ?: player.position), color = colors.onSurfaceVariant)
                NexioSearchFilter(
                    label = if (player.playing) "暂停" else "播放",
                    active = player.playing,
                    onClick = player::toggle,
                )
                Text(mediaTime(duration), color = colors.onSurfaceVariant)
            }
            if (player.focusDenied) Text("暂时无法播放音频，请稍后重试", color = colors.onSurfaceVariant)
        }
    }
}

private class PreviewPlayer(private val audioManager: AudioManager?) {
    private val player = MediaPlayer()
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build()
    private val audioFocus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener { change -> if (change < 0) pause() }
        .build()
    private var hasFocus = false
    private var released = false
    var ready by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set
    var playing by mutableStateOf(false)
        private set
    var focusDenied by mutableStateOf(false)
        private set
    var duration by mutableIntStateOf(0)
        private set
    var position by mutableIntStateOf(0)
        private set
    var aspectRatio by mutableFloatStateOf(16f / 9f)
        private set

    fun prepare(file: File) {
        try {
            player.setAudioAttributes(attributes)
            player.setOnPreparedListener {
                if (!released) {
                    duration = it.duration.coerceAtLeast(0)
                    ready = true
                }
            }
            player.setOnVideoSizeChangedListener { _, width, height ->
                if (width > 0 && height > 0 && !released) aspectRatio = width.toFloat() / height
            }
            player.setOnCompletionListener {
                if (!released) {
                    position = duration
                    playing = false
                    abandonFocus()
                }
            }
            player.setOnErrorListener { _, _, _ ->
                if (!released) fail()
                true
            }
            file.inputStream().use { player.setDataSource(it.fd) }
            player.prepareAsync()
        } catch (_: Exception) {
            fail()
        }
    }

    fun toggle() {
        if (!ready || released) return
        if (playing) {
            pause()
            return
        }
        try {
            hasFocus = audioManager?.requestAudioFocus(audioFocus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            focusDenied = !hasFocus
            if (!hasFocus) return
            if (duration > 0 && position >= duration) seekTo(0)
            if (!ready) return
            player.start()
            playing = true
        } catch (_: IllegalStateException) {
            fail()
        }
    }

    fun pause() {
        if (!playing || released) return
        try {
            player.pause()
            updatePosition()
            playing = false
            abandonFocus()
        } catch (_: IllegalStateException) {
            fail()
        }
    }

    fun updatePosition() {
        if (!ready || released) return
        try {
            position = player.currentPosition.coerceAtLeast(0)
        } catch (_: IllegalStateException) {
            fail()
        }
    }

    fun seekTo(position: Int) {
        if (!ready || released) return
        try {
            player.seekTo(position.toLong(), MediaPlayer.SEEK_CLOSEST)
            this.position = position
        } catch (_: IllegalStateException) {
            fail()
        }
    }

    fun textureListener() = object : TextureView.SurfaceTextureListener {
        private var surface: Surface? = null

        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
            val target = Surface(texture)
            surface = target
            setSurface(target)
        }

        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
            setSurface(null)
            surface?.release()
            surface = null
            return true
        }

        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit
        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
    }

    private fun setSurface(surface: Surface?) {
        if (!released && !failed) {
            try {
                player.setSurface(surface)
            } catch (_: IllegalStateException) {
                fail()
            }
        }
    }

    private fun abandonFocus() {
        if (hasFocus) audioManager?.abandonAudioFocusRequest(audioFocus)
        hasFocus = false
    }

    private fun fail() {
        ready = false
        failed = true
        playing = false
        abandonFocus()
    }

    fun release() {
        released = true
        abandonFocus()
        player.release()
    }
}

private fun mediaTime(milliseconds: Int): String {
    val seconds = milliseconds / 1_000
    return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
}
