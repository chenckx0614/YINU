package com.yinu.app

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.yinu.app.records.WordBlock
import com.yinu.app.records.WordPart
import com.yinu.app.records.readWordPreview
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DocxPreviewTest {
    @Test
    fun preservesTextStyleAndImageOrderAndIgnoresExternalMedia() = runBlocking {
        val file = File.createTempFile("formatted", ".docx", RuntimeEnvironment.getApplication().cacheDir)
        try {
            val rel = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
            val contents = """
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
                    xmlns:r="$rel" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">
                    <w:body><w:p><w:pPr><w:jc w:val="center"/></w:pPr>
                        <w:r><w:rPr><w:b/></w:rPr><w:t>图片之前</w:t></w:r>
                        <w:r><w:drawing><a:blip r:embed="picture"/></w:drawing></w:r>
                        <w:r><w:drawing><a:blip r:embed="remote"/></w:drawing></w:r>
                        <w:r><w:t>图片之后</w:t></w:r>
                    </w:p></w:body>
                </w:document>
            """.trimIndent()
            val relationships = """
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                    <Relationship Id="picture" Type="$rel/image" Target="media/a.png"/>
                    <Relationship Id="remote" Type="$rel/image" TargetMode="External" Target="https://example.org/a.png"/>
                </Relationships>
            """.trimIndent()
            ZipOutputStream(file.outputStream()).use { zip ->
                for ((name, value) in mapOf("word/document.xml" to contents,
                    "word/_rels/document.xml.rels" to relationships, "word/media/a.png" to "image data")) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(value.toByteArray())
                    zip.closeEntry()
                }
            }
            val paragraph = readWordPreview(file).blocks.single() as WordBlock.Paragraph
            val (before, picture, after) = paragraph.parts
            assertEquals(TextAlign.Center, paragraph.alignment)
            assertEquals(3, paragraph.parts.size)
            assertEquals(FontWeight.Bold, (before as WordPart.Text).value.spanStyles.single().item.fontWeight)
            assertEquals("word/media/a.png", (picture as WordPart.Picture).entryName)
            assertEquals("图片之后", (after as WordPart.Text).value.text)
        } finally {
            file.delete()
        }
    }
}
