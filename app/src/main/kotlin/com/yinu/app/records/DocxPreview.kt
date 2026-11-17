package com.yinu.app.records

import top.yukonga.miuix.kmp.utils.overScrollVertical

import android.util.Xml
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.util.zip.ZipFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser

private const val MAX_XML_BYTES = 6 * 1024 * 1024
private const val MAX_BLOCKS = 1800
private const val MAX_BLOCK_CHARS = 12_000
private const val WORD_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
private const val STRICT_WORD_NS = "http://purl.oclc.org/ooxml/wordprocessingml/main"
private const val REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
private const val STRICT_REL_NS = "http://purl.oclc.org/ooxml/officeDocument/relationships"
private const val PACKAGE_REL_NS = "http://schemas.openxmlformats.org/package/2006/relationships"
private const val DRAW_NS = "http://schemas.openxmlformats.org/drawingml/2006/main"
private const val STRICT_DRAW_NS = "http://purl.oclc.org/ooxml/drawingml/main"
private const val MAX_IMAGE_BYTES = 12 * 1024 * 1024
private const val MAX_IMAGE_SIDE = 1280

internal sealed interface WordPart {
    data class Text(val value: AnnotatedString) : WordPart
    data class Picture(val entryName: String, val description: String) : WordPart
}

internal sealed interface WordBlock {
    data class Paragraph(
        val parts: List<WordPart>, val heading: Boolean, val alignment: TextAlign, val listItem: Boolean,
    ) : WordBlock
    data class TableRow(val cells: List<String>) : WordBlock
}

internal sealed interface WordPreviewState {
    data object Loading : WordPreviewState
    data object Unreadable : WordPreviewState
    data object TooLarge : WordPreviewState
    data class Loaded(val blocks: List<WordBlock>, val truncated: Boolean) : WordPreviewState
}

@Composable
internal fun DocxPreview(file: File) {
    val state by produceState<WordPreviewState>(WordPreviewState.Loading, file) {
        value = withContext(Dispatchers.IO) {
            try {
                readWordPreview(file)
            } catch (error: CancellationException) {
                throw error
            } catch (_: PreviewLimitException) {
                WordPreviewState.TooLarge
            } catch (_: Exception) {
                WordPreviewState.Unreadable
            }
        }
    }

    when (val preview = state) {
        is WordPreviewState.Loaded -> WordContent(file, preview)
        else -> Box(
            Modifier.fillMaxSize().padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            when (preview) {
                WordPreviewState.Loading -> CircularProgressIndicator()
                WordPreviewState.TooLarge -> Text("文档内容较大，请选择「外部打开」")
                WordPreviewState.Unreadable -> Text("无法读取此 Word 文档，请尝试「外部打开」")
                is WordPreviewState.Loaded -> Unit
            }
        }
    }
}

@Composable
private fun WordContent(file: File, preview: WordPreviewState.Loaded) {
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    SelectionContainer {
        LazyColumn(
            modifier = Modifier.fillMaxSize().overScrollVertical(),
            overscrollEffect = null,
            contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 12.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { Text("Word 文档 · 快速预览", color = colors.primary, style = typography.labelMedium) }
            if (preview.blocks.isEmpty()) {
                item { Text("没有可预览的文字，可选择「外部打开」查看原文档") }
            }
            items(preview.blocks) { block ->
                when (block) {
                    is WordBlock.Paragraph -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        block.parts.forEachIndexed { index, part ->
                            when (part) {
                                is WordPart.Text -> Text(
                                    text = if (index == 0 && block.listItem) AnnotatedString("•  ") + part.value else part.value,
                                    modifier = Modifier.fillMaxWidth(),
                                    style = if (block.heading) typography.titleMedium else typography.bodyLarge,
                                    fontWeight = if (block.heading) FontWeight.SemiBold else FontWeight.Normal,
                                    textAlign = block.alignment,
                                )
                                is WordPart.Picture -> WordPicture(file, part)
                            }
                        }
                    }
                    is WordBlock.TableRow -> Row(
                        Modifier.fillMaxWidth().background(colors.surface, RoundedCornerShape(8.dp)).padding(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (block.cells.size > 5) {
                            Text(block.cells.joinToString("  │  "), style = typography.bodyMedium)
                        } else {
                            block.cells.forEach { Text(it, Modifier.weight(1f), style = typography.bodyMedium) }
                        }
                    }
                }
            }
            if (preview.truncated) {
                item { Text("仅显示部分内容，可选择「外部打开」查看全文", color = colors.onSurfaceVariant) }
            }
        }
    }
}

internal suspend fun readWordPreview(file: File): WordPreviewState.Loaded {
    val job = currentCoroutineContext()
    return ZipFile(file).use { archive ->
        val images = readImageRelationships(archive)
        val entry = archive.getEntry("word/document.xml") ?: throw IOException("Missing document body")
        if (entry.size > MAX_XML_BYTES) throw PreviewLimitException()
        PreviewInput(archive.getInputStream(entry)).use { input ->
            val parser = wordParser(input)
            while (parser.next() != XmlPullParser.END_DOCUMENT && !parser.isWordStart("body")) {
                job.ensureActive()
            }
            if (!parser.isWordStart("body")) throw IOException("Missing document body")

            val blocks = mutableListOf<WordBlock>()
            var truncated = false
            val bodyDepth = parser.depth
            while (parser.advanceInside(bodyDepth)) {
                job.ensureActive()
                if (blocks.size >= MAX_BLOCKS) {
                    truncated = true
                    break
                }
                when {
                    parser.isWordStart("p") -> {
                        val paragraph = parser.readWordParagraph(images)
                        if (paragraph.parts.isNotEmpty()) blocks += paragraph
                    }
                    parser.isWordStart("tbl") -> {
                        val tableDepth = parser.depth
                        while (blocks.size < MAX_BLOCKS && parser.advanceInside(tableDepth)) {
                            job.ensureActive()
                            if (parser.isWordStart("tr")) {
                                val row = parser.readWordRow()
                                if (row.cells.any(String::isNotBlank)) blocks += row
                            }
                        }
                        if (blocks.size >= MAX_BLOCKS) {
                            truncated = true
                            break
                        }
                    }
                }
            }
            WordPreviewState.Loaded(blocks, truncated)
        }
    }
}

private fun XmlPullParser.readWordParagraph(images: Map<String, String> = emptyMap()): WordBlock.Paragraph {
    val paragraphDepth = depth
    var heading = false
    var alignment = TextAlign.Start
    var listItem = false
    val parts = mutableListOf<WordPart>()
    var text = AnnotatedString.Builder()
    var remaining = MAX_BLOCK_CHARS
    fun flushText() {
        val value = text.toAnnotatedString()
        if (value.isNotBlank()) parts += WordPart.Text(value)
        text = AnnotatedString.Builder()
    }
    while (advanceInside(paragraphDepth)) {
        when {
            isWordStart("pStyle") -> {
                val style = getAttributeValue(namespace, "val").orEmpty()
                heading = style.startsWith("Heading", ignoreCase = true) || style.equals("Title", ignoreCase = true)
            }
            isWordStart("outlineLvl") -> heading = true
            isWordStart("jc") -> alignment = when (getAttributeValue(namespace, "val")) {
                "center" -> TextAlign.Center
                "right", "end" -> TextAlign.End
                "both", "distribute" -> TextAlign.Justify
                else -> TextAlign.Start
            }
            isWordStart("numPr") -> listItem = true
            isWordStart("r") -> readRun(images).forEach { part ->
                when (part) {
                    is WordPart.Picture -> if (parts.size < 64) {
                        flushText()
                        parts += part
                    }
                    is WordPart.Text -> if (remaining > 0) {
                        val count = minOf(remaining, part.value.length)
                        text.append(part.value.subSequence(0, count))
                        remaining -= count
                        if (count < part.value.length) text.append('…')
                    }
                }
            }
        }
    }
    flushText()
    return WordBlock.Paragraph(parts, heading, alignment, listItem)
}

private fun XmlPullParser.readRun(images: Map<String, String>): List<WordPart> {
    val runDepth = depth
    var bold = false
    var italic = false
    var underline = false
    var strike = false
    val parts = mutableListOf<WordPart>()
    val text = StringBuilder()
    fun flushText() {
        if (text.isEmpty()) return
        val decoration = when {
            underline && strike -> TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
            underline -> TextDecoration.Underline
            strike -> TextDecoration.LineThrough
            else -> null
        }
        parts += WordPart.Text(buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold.takeIf { bold }, fontStyle = FontStyle.Italic.takeIf { italic },
                textDecoration = decoration)) { append(text.toString()) }
        })
        text.clear()
    }
    while (advanceInside(runDepth)) {
        when {
            isWordStart("b") -> bold = wordToggle()
            isWordStart("i") -> italic = wordToggle()
            isWordStart("u") -> underline = wordToggle()
            isWordStart("strike") -> strike = wordToggle()
            isWordStart("t") -> text.appendForPreview(nextText())
            isWordStart("tab") -> text.appendForPreview("\t")
            isWordStart("br") || isWordStart("cr") -> text.appendForPreview("\n")
            isWordStart("drawing") || isWordStart("pict") -> {
                val picture = readDrawing(images)
                if (picture != null) {
                    flushText()
                    parts += picture
                }
            }
        }
    }
    flushText()
    return parts
}

private fun XmlPullParser.wordToggle(): Boolean = getAttributeValue(namespace, "val") !in setOf("0", "false", "off", "none")

private fun XmlPullParser.readDrawing(images: Map<String, String>): WordPart.Picture? {
    val drawingDepth = depth
    var entry: String? = null
    var description = "文档内嵌图片"
    while (advanceInside(drawingDepth)) {
        if (eventType != XmlPullParser.START_TAG) continue
        if (name == "docPr") {
            description = (getAttributeValue(null, "descr") ?: getAttributeValue(null, "title"))
                ?.takeIf { it.isNotBlank() }?.take(160) ?: description
        }
        if (name == "blip" && (namespace == DRAW_NS || namespace == STRICT_DRAW_NS) ||
            name == "imagedata" && namespace == "urn:schemas-microsoft-com:vml"
        ) {
            val attribute = if (name == "blip") "embed" else "id"
            val id = getAttributeValue(REL_NS, attribute) ?: getAttributeValue(STRICT_REL_NS, attribute)
            entry = entry ?: images[id]
        }
    }
    return entry?.let { WordPart.Picture(it, description) }
}

private fun readImageRelationships(archive: ZipFile): Map<String, String> {
    val entry = archive.getEntry("word/_rels/document.xml.rels") ?: return emptyMap()
    return PreviewInput(archive.getInputStream(entry)).use { input ->
        val parser = wordParser(input)
        buildMap {
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType != XmlPullParser.START_TAG || parser.name != "Relationship" ||
                    parser.namespace != PACKAGE_REL_NS || parser.getAttributeValue(null, "TargetMode") == "External"
                ) continue
                val type = parser.getAttributeValue(null, "Type")
                if (type != "$REL_NS/image" && type != "$STRICT_REL_NS/image") continue
                val id = parser.getAttributeValue(null, "Id") ?: continue
                val target = parser.getAttributeValue(null, "Target") ?: continue
                val path = wordImagePath(target) ?: continue
                if (size < MAX_BLOCKS && archive.getEntry(path)?.isDirectory == false) put(id, path)
            }
        }
    }
}

private fun wordImagePath(target: String): String? {
    val uri = runCatching { URI(target.replace(" ", "%20")) }.getOrNull() ?: return null
    if (uri.isAbsolute || uri.authority != null || uri.query != null || uri.fragment != null) return null
    val resolved = URI("/word/document.xml").resolve(uri).normalize().path.removePrefix("/")
    if (resolved.split('/').any { it == ".." || '\\' in it }) return null
    return resolved.takeIf { it.substringAfterLast('.').lowercase() in setOf("png", "jpg", "jpeg", "gif", "webp", "bmp") }
}

private fun wordParser(input: InputStream): XmlPullParser = Xml.newPullParser().apply {
    setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
    setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
    setInput(input, null)
}

@Composable
private fun WordPicture(file: File, part: WordPart.Picture) {
    val bitmap by produceState<Bitmap?>(null, file, part.entryName) {
        value = withContext(Dispatchers.IO) {
            try {
                ZipFile(file).use { archive ->
                    val entry = archive.getEntry(part.entryName) ?: return@use null
                    if (entry.size !in 1..MAX_IMAGE_BYTES.toLong()) return@use null
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    PreviewInput(archive.getInputStream(entry), MAX_IMAGE_BYTES).use {
                        BitmapFactory.decodeStream(it, null, options)
                    }
                    val side = maxOf(options.outWidth, options.outHeight)
                    if (side <= 0 || side > 65_536) return@use null
                    var sample = 1
                    while (side.toLong() > MAX_IMAGE_SIDE.toLong() * sample) sample *= 2
                    options.inJustDecodeBounds = false
                    options.inSampleSize = sample
                    PreviewInput(archive.getInputStream(entry), MAX_IMAGE_BYTES).use {
                        BitmapFactory.decodeStream(it, null, options)
                    }
                }
            } catch (_: IOException) {
                null
            }
        }
    }
    val image = bitmap
    if (image != null) {
        Image(image.asImageBitmap(), part.description, Modifier.fillMaxWidth().heightIn(max = 420.dp),
            contentScale = ContentScale.Fit)
    } else Text(part.description, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
}

private fun XmlPullParser.readWordRow(): WordBlock.TableRow {
    val rowDepth = depth
    val cells = mutableListOf<String>()
    while (advanceInside(rowDepth)) {
        if (!isWordStart("tc")) continue
        val cellDepth = depth
        val text = StringBuilder()
        while (advanceInside(cellDepth)) {
            if (isWordStart("p")) {
                if (text.isNotEmpty()) text.appendForPreview("\n")
                val paragraph = readWordParagraph()
                paragraph.parts.filterIsInstance<WordPart.Text>().forEach { text.appendForPreview(it.value.text) }
            }
        }
        if (cells.size < 24) cells += text.toString()
        else if (cells.size == 24) cells += "…"
    }
    return WordBlock.TableRow(cells)
}

private fun XmlPullParser.isWordStart(element: String): Boolean =
    eventType == XmlPullParser.START_TAG && name == element && (namespace == WORD_NS || namespace == STRICT_WORD_NS)

private fun XmlPullParser.advanceInside(parentDepth: Int): Boolean = when (next()) {
    XmlPullParser.END_DOCUMENT -> throw IOException("Unexpected end of document")
    XmlPullParser.END_TAG -> depth > parentDepth
    else -> true
}

private fun StringBuilder.appendForPreview(value: String) {
    if (length > MAX_BLOCK_CHARS) return
    val remaining = MAX_BLOCK_CHARS - length
    append(value, 0, minOf(value.length, remaining))
    if (value.length > remaining) append('…')
}

private class PreviewLimitException : IOException()

private class PreviewInput(private val source: InputStream, limit: Int = MAX_XML_BYTES) : InputStream() {
    private var remaining = limit

    override fun read(): Int = source.read().also { if (it >= 0) count(1) }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        source.read(buffer, offset, minOf(length, remaining + 1)).also { if (it > 0) count(it) }

    private fun count(bytes: Int) {
        remaining -= bytes
        if (remaining < 0) throw PreviewLimitException()
    }

    override fun close() = source.close()
}
