package com.yinu.app

import android.app.Application
import android.graphics.BitmapFactory
import com.yinu.app.records.exportLongImages
import com.yinu.app.records.removeExport
import com.yinu.app.records.ImageMark
import com.yinu.app.records.MarkupTool
import com.yinu.app.records.writeMarkedImage
import androidx.compose.ui.geometry.Offset
import com.yinu.data.Attachment
import com.yinu.data.Record
import com.yinu.data.RecordQuote
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LongImageExporterTest {
    @Test
    fun markedPngCoversPixelsAndLeavesTheSourceIntact() {
        val source = android.graphics.Bitmap.createBitmap(120, 80, android.graphics.Bitmap.Config.ARGB_8888)
        source.eraseColor(android.graphics.Color.BLUE)
        try {
            val output = java.io.ByteArrayOutputStream()
            writeMarkedImage(source, listOf(ImageMark(MarkupTool.Cover, listOf(Offset(0.2f, 0.25f), Offset(0.8f, 0.75f)))), output)
            val bytes = output.toByteArray()
            val exported = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            try {
                check(exported.width == 120 && exported.height == 80)
                check(exported.getPixel(60, 40) == android.graphics.Color.BLACK)
                check(exported.getPixel(2, 2) == android.graphics.Color.BLUE)
                check(source.getPixel(60, 40) == android.graphics.Color.BLUE)
            } finally { exported.recycle() }
        } finally { source.recycle() }
    }

    @Test
    fun exportedImageKeepsItsAspectRatioAndIsReadable() = runBlocking<Unit> {
        val context = RuntimeEnvironment.getApplication()
        val fixture = java.io.File(context.cacheDir, "export-fixture.png")
        val image = android.graphics.Bitmap.createBitmap(800, 400, android.graphics.Bitmap.Config.ARGB_8888)
        image.eraseColor(android.graphics.Color.BLUE)
        fixture.outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
        val record = Record("picture", null, 1, false,
            listOf(Attachment("photo", fixture.name, "image/png", fixture.length(), fixture, false)),
            links = listOf(RecordQuote("old", "之前的灵感记录")))
        val pages = exportLongImages(context, listOf(record))
        try {
            check(pages.size == 1)
            val bitmap = BitmapFactory.decodeFile(pages.single().file.path)
            try {
                check(bitmap.getPixel(60, 170) == android.graphics.Color.BLUE)
                check(bitmap.getPixel(60, 530) == android.graphics.Color.BLUE)
                check(bitmap.getPixel(60, 545) != android.graphics.Color.BLUE)
            } finally { bitmap.recycle() }
            val preview = java.io.File("build/qa/long-export.png")
            preview.parentFile.mkdirs()
            pages.single().file.copyTo(preview, overwrite = true)
        } finally { removeExport(pages); fixture.delete() }
    }

    @Test
    fun longContentSplitsIntoBoundedPngsAndMissingImagesDoNotAbortExport() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val records = listOf(
            Record("first", "第一条记录 🌙\n" + "完整保留的长段落\n".repeat(500), 1, false, emptyList()),
            Record("second", "第二条记录", 2, false, listOf(Attachment("missing", "找不到的图片.jpg", "image/jpeg", 10, null, false)),
                links = listOf(RecordQuote("first", "第一条记录"))),
        )
        val pages = exportLongImages(context, records.reversed())
        try {
            check(pages.size >= 3)
            pages.forEach { page ->
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(page.file.path, bounds)
                check(bounds.outWidth == 840 && bounds.outHeight in 180..7_200)
                check(page.file.length() > 0)
            }
        } finally { removeExport(pages) }
        check(pages.none { it.file.exists() })
    }
}
