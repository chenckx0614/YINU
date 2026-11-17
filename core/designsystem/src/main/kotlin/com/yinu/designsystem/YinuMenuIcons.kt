package com.yinu.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.ChevronBackward
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.icon.extended.Copy
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Folder
import top.yukonga.miuix.kmp.icon.extended.Image
import top.yukonga.miuix.kmp.icon.extended.Remove
import top.yukonga.miuix.kmp.icon.extended.Reply
import top.yukonga.miuix.kmp.icon.extended.Share
import top.yukonga.miuix.kmp.icon.extended.Search
import top.yukonga.miuix.kmp.icon.extended.Send

object YinuMenuIcons {
    val voice = ImageVector.Builder("Voice", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(3f, 10f)
            lineTo(5.5f, 10f)
            lineTo(9f, 7f)
            lineTo(9f, 17f)
            lineTo(5.5f, 14f)
            lineTo(3f, 14f)
            close()
        }
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.9f, strokeLineCap = StrokeCap.Round) {
            moveTo(12f, 8.5f)
            quadTo(15.4f, 12f, 12f, 15.5f)
            moveTo(16f, 5.5f)
            quadTo(22.3f, 12f, 16f, 18.5f)
        }
    }.build()
    val keyboard = ImageVector.Builder("Keyboard", 24.dp, 24.dp, 24f, 24f).apply {
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.7f, strokeLineCap = StrokeCap.Round) {
            moveTo(5f, 6f); lineTo(19f, 6f); quadTo(21f, 6f, 21f, 8f)
            lineTo(21f, 16f); quadTo(21f, 18f, 19f, 18f); lineTo(5f, 18f)
            quadTo(3f, 18f, 3f, 16f); lineTo(3f, 8f); quadTo(3f, 6f, 5f, 6f); close()
            moveTo(7f, 10f); lineTo(8f, 10f); moveTo(11.5f, 10f); lineTo(12.5f, 10f)
            moveTo(16f, 10f); lineTo(17f, 10f); moveTo(7f, 14f); lineTo(17f, 14f)
        }
    }.build()
    val add get() = MiuixIcons.Add
    val back get() = MiuixIcons.ChevronBackward
    val down = ImageVector.Builder("ArrowDown", 24.dp, 24.dp, 24f, 24f).apply {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.9f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(12f, 4.5f)
            lineTo(12f, 19f)
            moveTo(6.5f, 13.5f)
            lineTo(12f, 19f)
            lineTo(17.5f, 13.5f)
        }
    }.build()
    val calendar = ImageVector.Builder("Calendar", 24.dp, 24.dp, 24f, 24f).apply {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(6f, 5f)
            lineTo(18f, 5f)
            quadTo(20f, 5f, 20f, 7f)
            lineTo(20f, 18f)
            quadTo(20f, 20f, 18f, 20f)
            lineTo(6f, 20f)
            quadTo(4f, 20f, 4f, 18f)
            lineTo(4f, 7f)
            quadTo(4f, 5f, 6f, 5f)
            close()
            moveTo(4f, 10f)
            lineTo(20f, 10f)
            moveTo(8f, 3.5f)
            lineTo(8f, 6.5f)
            moveTo(16f, 3.5f)
            lineTo(16f, 6.5f)
        }
    }.build()
    val send get() = MiuixIcons.Send
    val ai = ImageVector.Builder("AI", 24.dp, 24.dp, 24f, 24f).apply {
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round) {
            moveTo(3f, 17f)
            lineTo(8f, 7f)
            lineTo(13f, 17f)
            moveTo(5f, 13.5f)
            lineTo(11f, 13.5f)
            moveTo(18f, 7f)
            lineTo(18f, 17f)
        }
    }.build()
    val search get() = MiuixIcons.Search
    val image get() = MiuixIcons.Image
    val file get() = MiuixIcons.Folder
    val open get() = MiuixIcons.ChevronForward
    val externalOpen = ImageVector.Builder("ExternalOpen", 24.dp, 24.dp, 24f, 24f).apply {
        val ink = SolidColor(Color.Black)
        path(stroke = ink, strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(12f, 5f)
            lineTo(6.5f, 5f)
            quadTo(4.5f, 5f, 4.5f, 7f)
            lineTo(4.5f, 17.5f)
            quadTo(4.5f, 19.5f, 6.5f, 19.5f)
            lineTo(17f, 19.5f)
            quadTo(19f, 19.5f, 19f, 17.5f)
            lineTo(19f, 12f)
            moveTo(14.5f, 4.5f)
            lineTo(19.5f, 4.5f)
            lineTo(19.5f, 9.5f)
            moveTo(19.5f, 4.5f)
            lineTo(11.5f, 12.5f)
        }
    }.build()
    val share get() = MiuixIcons.Share
    val copy get() = MiuixIcons.Copy
    val edit = ImageVector.Builder("Edit", 24.dp, 24.dp, 24f, 24f).apply {
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(4f, 20f)
            lineTo(5.2f, 15.1f)
            lineTo(15.8f, 4.5f)
            quadTo(17f, 3.3f, 18.2f, 4.5f)
            lineTo(19.5f, 5.8f)
            quadTo(20.7f, 7f, 19.5f, 8.2f)
            lineTo(8.9f, 18.8f)
            close()
            moveTo(14.3f, 6f)
            lineTo(18f, 9.7f)
        }
    }.build()
    val multiSelect = ImageVector.Builder("MultiSelect", 24.dp, 24.dp, 24f, 24f).apply {
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(4f, 7f)
            lineTo(6.5f, 9.5f)
            lineTo(10.5f, 5.5f)
            moveTo(4f, 16f)
            lineTo(6.5f, 18.5f)
            lineTo(10.5f, 14.5f)
            moveTo(14f, 8f)
            lineTo(20f, 8f)
            moveTo(14f, 17f)
            lineTo(20f, 17f)
        }
    }.build()
    val selectText = ImageVector.Builder("SelectText", 24.dp, 24.dp, 24f, 24f).apply {
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(7f, 4f)
            lineTo(4f, 4f)
            lineTo(4f, 20f)
            lineTo(7f, 20f)
            moveTo(17f, 4f)
            lineTo(20f, 4f)
            lineTo(20f, 20f)
            lineTo(17f, 20f)
            moveTo(9f, 7f)
            lineTo(15f, 7f)
            moveTo(12f, 7f)
            lineTo(12f, 17f)
            moveTo(9f, 17f)
            lineTo(15f, 17f)
        }
    }.build()
    val close get() = MiuixIcons.Close
    val reply get() = MiuixIcons.Reply
    val link = ImageVector.Builder("RecordLink", 24.dp, 24.dp, 24f, 24f).apply {
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(10f, 14f)
            curveTo(11.2f, 15.2f, 13.1f, 15.2f, 14.3f, 14f)
            lineTo(19f, 9.3f)
            curveTo(22.3f, 6f, 18f, 1.7f, 14.7f, 5f)
            lineTo(12f, 7.7f)
            moveTo(14f, 10f)
            curveTo(12.8f, 8.8f, 10.9f, 8.8f, 9.7f, 10f)
            lineTo(5f, 14.7f)
            curveTo(1.7f, 18f, 6f, 22.3f, 9.3f, 19f)
            lineTo(12f, 16.3f)
        }
    }.build()
    val delete get() = MiuixIcons.Delete
    val removeCopy get() = MiuixIcons.Remove
    val pin = ImageVector.Builder("Pin", 24.dp, 24.dp, 24f, 24f).apply {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(8f, 4f)
            lineTo(16f, 4f)
            moveTo(9f, 4f)
            lineTo(9f, 9f)
            lineTo(6.5f, 12.5f)
            lineTo(6.5f, 14f)
            lineTo(17.5f, 14f)
            lineTo(17.5f, 12.5f)
            lineTo(15f, 9f)
            lineTo(15f, 4f)
            moveTo(12f, 14f)
            lineTo(12f, 21f)
        }
    }.build()
    val favorite = star(filled = false)
    val unfavorite = star(filled = true)
}

private fun star(filled: Boolean) = ImageVector.Builder("Favorite", 24.dp, 24.dp, 24f, 24f).apply {
    val ink = SolidColor(Color.Black)
    path(fill = if (filled) ink else null, stroke = ink, strokeLineWidth = 1.6f) {
        moveTo(12f, 3f)
        lineTo(14.8f, 8.8f)
        lineTo(21.2f, 9.7f)
        lineTo(16.6f, 14.2f)
        lineTo(17.7f, 20.5f)
        lineTo(12f, 17.5f)
        lineTo(6.3f, 20.5f)
        lineTo(7.4f, 14.2f)
        lineTo(2.8f, 9.7f)
        lineTo(9.2f, 8.8f)
        close()
    }
}.build()
