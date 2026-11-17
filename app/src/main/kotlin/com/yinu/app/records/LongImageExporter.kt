package com.yinu.app.records

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.yinu.data.Record
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal data class ExportPage(val file: File, val width: Int, val height: Int)

/** Bounded, one-page-at-a-time raster export. Original attachments are only read. */
internal suspend fun exportLongImages(context: Context, records: List<Record>): List<ExportPage> {
    var rendered: List<ExportPage>? = null
    try {
        return withContext(Dispatchers.IO) { renderLongImages(context, records).also { rendered = it } }
    } catch (error: Throwable) {
        // Cancellation can arrive while switching back to the caller's dispatcher.
        rendered?.let(::removeExport)
        throw error
    }
}

private suspend fun renderLongImages(context: Context, records: List<Record>): List<ExportPage> {
    require(records.isNotEmpty())
    val directory = File(context.cacheDir, "long_exports/${UUID.randomUUID()}")
    check(directory.mkdirs())
    val pages = mutableListOf<ExportPage>()
    val width = 840
    val height = 7_200
    var bitmap: Bitmap? = null
    var canvas: Canvas? = null
    var y = 0f
    val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    fun begin() {
        bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        canvas = Canvas(bitmap!!)
        canvas!!.drawColor(Color.rgb(247, 247, 247))
        paint.textSize = 32f
        paint.color = Color.rgb(26, 26, 26)
        paint.isFakeBoldText = true
        canvas!!.drawText("一隅 · 记录", 40f, 62f, paint)
        paint.isFakeBoldText = false
        y = 94f
    }
    fun finish() {
        val source = bitmap ?: return
        val used = (y + 65).toInt().coerceAtMost(height).coerceAtLeast(180)
        paint.textSize = 20f
        paint.color = Color.GRAY
        canvas!!.drawText("一隅  ·  ${pages.size + 1}", 40f, used - 24f, paint)
        val cropped = Bitmap.createBitmap(source, 0, 0, width, used)
        try {
            val file = File(directory, "YINU-${pages.size + 1}.png")
            file.outputStream().use { check(cropped.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            pages += ExportPage(file, width, used)
        } finally {
            if (cropped !== source) cropped.recycle()
            source.recycle()
            bitmap = null
        }
    }
    fun space(amount: Float) {
        require(amount < height - 160)
        if (y + amount > height - 70) { finish(); begin() }
    }
    suspend fun text(value: String, secondary: Boolean = false) {
        // UTF-16 chunks end outside a surrogate pair and remain below one page.
        var start = 0
        do {
            currentCoroutineContext().ensureActive()
            var end = (start + 1_200).coerceAtMost(value.length)
            if (end < value.length && end > start && Character.isHighSurrogate(value[end - 1])) end--
            val chunk = value.substring(start, end)
            paint.textSize = if (secondary) 24f else 32f
            paint.color = if (secondary) Color.rgb(105, 105, 105) else Color.rgb(26, 26, 26)
            val layout = StaticLayout.Builder.obtain(chunk, 0, chunk.length, paint, width - 112)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).setLineSpacing(5f, 1f).build()
            // Explicit newlines can produce a taller layout than ordinary wrapped text.
            var first = 0
            while (first < layout.lineCount) {
                var last = first
                val top = layout.getLineTop(first)
                while (last + 1 < layout.lineCount && layout.getLineBottom(last + 1) - top < height - 260) last++
                val blockHeight = (layout.getLineBottom(last) - top).toFloat()
                space(blockHeight + 34f)
                fill.color = if (secondary) Color.rgb(235, 235, 235) else Color.rgb(234, 242, 255)
                canvas!!.drawRoundRect(RectF(32f, y, width - 32f, y + blockHeight + 24f), 24f, 24f, fill)
                canvas!!.save()
                canvas!!.clipRect(56f, y + 12f, width - 56f, y + 12f + blockHeight)
                canvas!!.translate(56f, y + 12f - top)
                layout.draw(canvas!!)
                canvas!!.restore()
                y += blockHeight + 34f
                first = last + 1
            }
            start = end
        } while (start < value.length)
    }
    return try {
        begin()
        val dateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        for (record in records.distinctBy { it.id }.sortedBy { it.createdAt }) {
            currentCoroutineContext().ensureActive()
            space(75f)
            y += 20f
            paint.textSize = 22f
            paint.color = Color.GRAY
            canvas!!.drawText(dateFormat.format(Date(record.createdAt)), 40f, y + 24f, paint)
            y += 42f
            record.content?.let { text(it) }
            for (attachment in record.attachments) {
                currentCoroutineContext().ensureActive()
                val file = attachment.localFile?.takeIf { it.isFile }
                if (attachment.isImage && file != null) {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(file.path, bounds)
                    val options = BitmapFactory.Options().apply {
                        var sample = 1
                        while (bounds.outWidth / sample > width * 2 || bounds.outHeight / sample > 3_600) sample *= 2
                        inSampleSize = sample
                    }
                    val image = BitmapFactory.decodeFile(file.path, options)
                    if (image == null) text("[图片无法读取] ${attachment.fileName}", true)
                    else try {
                        val scale = minOf((width - 80f) / image.width, 1_800f / image.height)
                        val imageWidth = image.width * scale
                        val imageHeight = image.height * scale
                        space(imageHeight + 16f)
                        canvas!!.save()
                        canvas!!.clipPath(android.graphics.Path().apply {
                            addRoundRect(RectF(40f, y, 40f + imageWidth, y + imageHeight), 24f, 24f, android.graphics.Path.Direction.CW)
                        })
                        canvas!!.drawBitmap(image, null, RectF(40f, y, 40f + imageWidth, y + imageHeight), fill)
                        canvas!!.restore()
                        y += imageHeight + 16f
                    } finally { image.recycle() }
                } else text("[${if (attachment.isImage) "图片" else "附件"}] ${attachment.fileName}\n" +
                    if (file == null) "本地副本缺失" else android.text.format.Formatter.formatShortFileSize(context, attachment.fileSize), true)
            }
            (listOfNotNull(record.quote) + record.links).distinctBy { it.messageId }.forEach { text("关联 · ${it.summary}", true) }
        }
        currentCoroutineContext().ensureActive()
        finish()
        pages.toList()
    } catch (error: Throwable) {
        bitmap?.recycle()
        directory.listFiles()?.forEach { it.delete() }
        directory.delete()
        throw error
    }
}

internal fun removeExport(pages: List<ExportPage>) {
    pages.forEach { it.file.delete() }
    pages.firstOrNull()?.file?.parentFile?.delete()
}
