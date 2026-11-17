package com.yinu.app.records

import top.yukonga.miuix.kmp.utils.overScrollVertical

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

private const val TextByteLimit = 512 * 1024

private sealed interface TextPreviewState {
    data object Loading : TextPreviewState
    data object Failed : TextPreviewState
    data class Loaded(val blocks: List<String>, val truncated: Boolean) : TextPreviewState
}

@Composable
internal fun PlainTextPreview(file: File) {
    val state by produceState<TextPreviewState>(TextPreviewState.Loading, file) {
        value = withContext(Dispatchers.IO) {
            try {
                readPreview(file)
            } catch (error: CancellationException) {
                throw error
            } catch (_: IOException) {
                TextPreviewState.Failed
            }
        }
    }
    when (val result = state) {
        TextPreviewState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        TextPreviewState.Failed -> Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
            Text("无法读取这份文本，可尝试「外部打开」")
        }
        is TextPreviewState.Loaded -> SelectionContainer {
            LazyColumn(
                Modifier.fillMaxSize().overScrollVertical(),
                overscrollEffect = null,
                contentPadding = PaddingValues(horizontal = 22.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (result.blocks.isEmpty()) item { Text("这是一个空文件") }
                items(result.blocks) { block -> Text(block, style = MaterialTheme.typography.bodyLarge) }
                if (result.truncated) item {
                    Text(
                        "仅预览前 512 KB，可选择「外部打开」查看全文",
                        Modifier.padding(top = 12.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private suspend fun readPreview(file: File): TextPreviewState.Loaded {
    val bytes = ByteArray(TextByteLimit + 1)
    val count = file.inputStream().use { input ->
        var offset = 0
        while (offset < bytes.size) {
            currentCoroutineContext().ensureActive()
            val length = input.read(bytes, offset, bytes.size - offset)
            if (length < 0) break
            offset += length
        }
        offset
    }
    val size = count.coerceAtMost(TextByteLimit)
    val truncated = count > TextByteLimit
    fun startsWith(vararg prefix: Int) =
        count >= prefix.size && prefix.indices.all { (bytes[it].toInt() and 0xff) == prefix[it] }

    fun decode(charset: Charset, offset: Int = 0): String {
        val output = CharBuffer.allocate(size)
        val status = charset.newDecoder().decode(ByteBuffer.wrap(bytes, offset, size - offset), output, !truncated)
        if (status.isError) status.throwException()
        return output.flip().toString()
    }

    val text = when {
        startsWith(0xef, 0xbb, 0xbf) -> decode(Charsets.UTF_8, 3)
        startsWith(0xff, 0xfe) -> decode(Charsets.UTF_16LE, 2)
        startsWith(0xfe, 0xff) -> decode(Charsets.UTF_16BE, 2)
        else -> try {
            decode(Charsets.UTF_8)
        } catch (_: CharacterCodingException) {
            decode(Charset.forName("GB18030"))
        }
    }
    if ('\u0000' in text) throw IOException("Binary data")
    val blocks = buildList {
        var start = 0
        while (start < text.length) {
            val end = (start + 4_096).coerceAtMost(text.length)
            val lineBreak = if (end < text.length) text.lastIndexOf('\n', end - 1) else -1
            val boundary = when {
                lineBreak > start -> lineBreak
                end < text.length && text[end - 1].isHighSurrogate() -> end - 1
                else -> end
            }
            add(text.substring(start, boundary))
            start = if (lineBreak == boundary) boundary + 1 else boundary
        }
    }
    return TextPreviewState.Loaded(blocks, truncated)
}
