package com.yinu.app.records

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.haooz.chedule.ui.basic.NexioOverlayDialog
import com.haooz.chedule.ui.basic.NexioSearchCard
import com.haooz.chedule.ui.basic.NexioSearchFilter
import com.kyant.backdrop.Backdrop
import com.yinu.data.Record
import com.yinu.data.RecordQuote

@Composable
internal fun RecordRelationsDialog(record: Record?, model: RecordsViewModel, locate: (String) -> Unit,
    show: Boolean, backdrop: Backdrop, onDismiss: () -> Unit, onFinished: () -> Unit) {
    val draft by model.links.collectAsStateWithLifecycle()
    val current = if (record == null) null else remember(record.id) { model.observeRecord(record.id) }
        .collectAsStateWithLifecycle(initialValue = record).value
    val incoming = if (record == null) emptyList() else remember(record.id) { model.backlinks(record.id) }
        .collectAsStateWithLifecycle(initialValue = emptyList()).value
    val outgoing = if (record == null) draft else current?.links.orEmpty()
    NexioOverlayDialog(show = show, title = if (record == null) "待发送的关联" else "关联记录",
        backdrop = backdrop, onDismissRequest = onDismiss, onDismissFinished = onFinished) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = dialogBodyMaxHeight(380.dp)), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Text(if (record == null) "这些关联会随下一条记录保存。" else "长按消息进入多选，可将多条记录关联到新消息。",
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item { Text("关联到 · ${outgoing.size}", color = MaterialTheme.colorScheme.primary) }
            if (outgoing.isEmpty()) item { Text("没有关联的记录") }
            items(outgoing, key = { "out-${it.messageId}" }) { link ->
                LinkedRecordCard(link, model, onOpen = { onDismiss(); locate(link.messageId) }, onRemove = {
                    if (record == null) model.removeLink(link.messageId) else model.unlink(record.id, link.messageId)
                })
            }
            if (record != null) {
                item { Text("被关联 · ${incoming.size}", color = MaterialTheme.colorScheme.primary) }
                if (incoming.isEmpty()) item { Text("还没有其他记录关联这里") }
                items(incoming, key = { "in-${it.id}" }) { entry ->
                    NexioSearchCard(onClick = { onDismiss(); locate(entry.id) }) { Text(entry.previewSummary()) }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            if (record != null) NexioSearchFilter("关联到新记录", false) { model.setLinks(listOf(record)); onDismiss() }
            NexioSearchFilter("完成", false, onClick = onDismiss)
        }
    }
}

@Composable
private fun LinkedRecordCard(link: RecordQuote, model: RecordsViewModel, onOpen: () -> Unit, onRemove: () -> Unit) {
    val status by produceState("正在读取…", link.messageId) {
        value = try { model.linkState(link.messageId) } catch (_: Exception) { "暂时无法读取" }
    }
    NexioSearchCard(onClick = if (status == "可查看") onOpen else null) {
        Text(link.summary)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant)
            NexioSearchFilter("移除关联", false, onClick = onRemove)
        }
    }
}
