package com.yinu.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.yinu.app.records.VoiceRelease
import com.yinu.app.records.flightFrame
import com.yinu.app.records.voiceFlightFrame
import com.yinu.app.records.voiceReleaseAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageMotionTest {
    @Test
    fun voiceCircleMovesFromMeasuredRecorderToPlaybackButton() {
        val overlay = Rect(80f, 210f, 136f, 266f)
        val message = Rect(236f, 644f, 262f, 670f)
        assertEquals(overlay, voiceFlightFrame(overlay, message, 0f))
        assertEquals(message, voiceFlightFrame(overlay, message, 1f))
        val halfway = voiceFlightFrame(overlay, message, 0.5f)
        assertTrue(halfway.width in message.width..overlay.width)
        assertTrue(halfway.top in overlay.top..message.top)
        val first = voiceFlightFrame(overlay, message, 0.1f)
        val last = voiceFlightFrame(overlay, message, 0.9f)
        assertTrue(first.center.x - overlay.center.x > (message.center.x - overlay.center.x) * 0.1f)
        assertTrue(first.top - overlay.top < (message.top - overlay.top) * 0.1f)
        assertTrue(message.top - last.top < (message.top - overlay.top) * 0.1f)
    }

    @Test
    fun voiceGestureSendsFromComposerAndChoosesActionAboveIt() {
        assertEquals(VoiceRelease.Send, voiceReleaseAt(Offset(20f, 10f), 200f, 54f))
        assertEquals(VoiceRelease.Cancel, voiceReleaseAt(Offset(-10f, -100f), 200f, 54f))
        assertEquals(VoiceRelease.Transcribe, voiceReleaseAt(Offset(180f, -100f), 200f, 54f))
        assertEquals(VoiceRelease.Send, voiceReleaseAt(Offset(180f, -30f), 200f, 54f))
    }

    @Test
    fun flightStartsAtTheMeasuredInputAndEndsAtTheBubble() {
        val input = Offset(42f, 710f)
        val bubble = Offset(286f, 564f)
        val start = flightFrame(input, bubble, 0f, false)
        val end = flightFrame(input, bubble, 1f, false)
        assertEquals(input.x, start.x, 0.001f)
        assertEquals(input.y, start.y, 0.001f)
        assertEquals(bubble.x, end.x, 0.001f)
        assertEquals(bubble.y, end.y, 0.001f)
        assertEquals(1f, end.alpha, 0.001f)
        assertEquals(1f, end.scale, 0.001f)
    }

    @Test
    fun flightSettlesWithoutPositionOrScaleOvershootInEitherDirection() {
        for (destination in listOf(Offset(260f, 340f), Offset(10f, 800f))) {
            val origin = Offset(80f, 650f)
            for (index in -1..21) {
                val frame = flightFrame(origin, destination, index / 20f, true)
                assertTrue(frame.x in minOf(origin.x, destination.x)..maxOf(origin.x, destination.x))
                assertTrue(frame.y in minOf(origin.y, destination.y)..maxOf(origin.y, destination.y))
                assertTrue(frame.scale in 0.879f..1.001f)
                assertTrue(frame.alpha in 0f..1f)
            }
        }
    }
}
