package com.yinu.app.records

import android.content.ClipData
import android.content.Intent
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import coil3.compose.AsyncImage
import com.haooz.chedule.ui.basic.NexioOverlayDialog
import com.haooz.chedule.ui.basic.NexioSearchFilter
import com.kyant.backdrop.Backdrop
import com.yinu.data.Record
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun LongExportDialog(records: List<Record>, show: Boolean, backdrop: Backdrop, onDismiss: () -> Unit, onFinished: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pages by remember { mutableStateOf<List<ExportPage>>(emptyList()) }
    var failure by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var shared by remember { mutableStateOf(false) }
    LaunchedEffect(records) {
        try { pages = exportLongImages(context, records) }
        catch (e: CancellationException) { throw e }
        catch (_: OutOfMemoryError) { failure = "可用内存不足，请减少选择的记录" }
        catch (_: Exception) { failure = "长图生成失败，请检查存储空间" }
    }
    DisposableEffect(Unit) { onDispose { if (!shared) removeExport(pages) } }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null && pages.isNotEmpty() && !busy) {
            busy = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val created = mutableListOf<android.net.Uri>()
                        try {
                            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                            val stamp = System.currentTimeMillis()
                            pages.forEachIndexed { index, page ->
                                val uri = DocumentsContract.createDocument(context.contentResolver, parent, "image/png", "YINU-$stamp-${index + 1}.png")
                                    ?: error("Cannot create document")
                                created += uri
                                context.contentResolver.openOutputStream(uri, "wt")!!.use { output ->
                                    page.file.inputStream().use { it.copyTo(output) }
                                }
                            }
                        } catch (e: Throwable) {
                            created.forEach { runCatching { DocumentsContract.deleteDocument(context.contentResolver, it) } }
                            throw e
                        }
                    }
                    failure = "已保存 ${pages.size} 张图片"
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { failure = "保存失败，请重新选择文件夹" }
                finally { busy = false }
            }
        }
    }
    NexioOverlayDialog(show = show, title = "长图预览", backdrop = backdrop,
        onDismissRequest = { if (!busy) onDismiss() }, onDismissFinished = onFinished) {
        Text("${records.size} 条记录 · ${if (pages.isEmpty()) "正在生成…" else "${pages.size} 张图片"}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = dialogBodyMaxHeight(350.dp, extra = 50.dp)), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(pages, key = { it.file.path }) { page ->
                AsyncImage(page.file, "导出图片预览", Modifier.fillMaxWidth().aspectRatio(page.width.toFloat() / page.height))
            }
        }
        failure?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NexioSearchFilter(if (busy) "保存中…" else "保存", false, enabled = pages.isNotEmpty() && !busy) {
                try { save.launch(null) } catch (_: Exception) { failure = "无法打开文件夹选择器" }
            }
            NexioSearchFilter("分享", false, enabled = pages.isNotEmpty() && !busy) {
                try {
                    val uris = ArrayList(pages.map { FileProvider.getUriForFile(context, "${context.packageName}.attachments", it.file) })
                    val intent = Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).setType("image/png")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    if (uris.size == 1) intent.putExtra(Intent.EXTRA_STREAM, uris[0]) else intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                    intent.clipData = ClipData.newUri(context.contentResolver, "一隅长图", uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
                    context.startActivity(Intent.createChooser(intent, "分享记录长图"))
                    shared = true // Keep grants usable after the preview closes; old exports are cleaned on startup.
                } catch (_: Exception) { failure = "无法分享图片" }
            }
            NexioSearchFilter("关闭", false, enabled = !busy, onClick = onDismiss)
        }
    }
}
