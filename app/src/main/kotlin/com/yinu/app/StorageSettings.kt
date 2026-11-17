package com.yinu.app

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.haooz.chedule.ui.basic.NexioDeleteDialogActions
import com.haooz.chedule.ui.basic.NexioOverlayDialog
import com.haooz.chedule.ui.basic.NexioSearchFilter
import com.haooz.chedule.ui.basic.NexioSearchStatus
import com.yinu.app.records.LibraryState
import com.yinu.app.records.RecordsViewModel
import com.yinu.data.Attachment
import com.yinu.data.TrashDeletion
import com.yinu.data.TrashRecord
import com.yinu.data.TrashRetentionDays
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.preference.ArrowPreference

private enum class StorageDestination { Attachments, Trash }

@Composable
internal fun StorageSettings(
    records: RecordsViewModel,
    onLocateRecord: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var destination by rememberSaveable { mutableStateOf<StorageDestination?>(null) }
    var backupSource by rememberSaveable { mutableStateOf<Uri?>(null) }
    val progress = remember { Animatable(if (destination == null) 0f else 1f) }
    val scope = rememberCoroutineScope()
    fun open(page: StorageDestination) {
        if (destination != null) return
        destination = page
        scope.launch { progress.animateTo(1f, PagePushEnter) }
    }
    fun goBack() {
        scope.launch {
            progress.animateTo(0f, PagePushExit)
            destination = null
        }
    }
    BackHandler(enabled = destination != null, onBack = ::goBack)
    Box(modifier.fillMaxSize().clipToBounds()) {
        SettingsPage(
            "数据与存储",
            onBack,
            Modifier.pagePushSource { progress.value }.then(
                if (destination == null) Modifier else Modifier.clearAndSetSemantics { hideFromAccessibility() },
            ),
            overlay = { backdrop ->
                backupSource?.let { source ->
                    BackupInspectionDialog(source, records, backdrop) { backupSource = null }
                }
            },
        ) { minimumBodyHeight ->
            item {
                Column(
                    Modifier.fillMaxWidth().heightIn(min = minimumBodyHeight),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    StorageUsage(records) { open(StorageDestination.Attachments) }
                    LocalBackupSettings(records) { backupSource = it }
                    SettingsNote("备份 ZIP 含有记录原文和附件。换手机时，可把 ZIP 传到新手机再恢复。")
                    SettingsGroup("记录管理") {
                        ArrowPreference(
                            title = "最近删除",
                            summary = "查看或恢复 ${TrashRetentionDays} 天内删除的记录",
                            onClick = { open(StorageDestination.Trash) },
                        )
                    }
                }
            }
        }
        destination?.let { target ->
            PagePushScrim { progress.value }
            val page = Modifier.pagePushDestination { progress.value }
            when (target) {
                StorageDestination.Attachments -> AttachmentManagement(records, onLocateRecord, ::goBack, page)
                StorageDestination.Trash -> TrashSettings(records, ::goBack, page)
            }
        }
    }
}

@Composable
private fun StorageUsage(records: RecordsViewModel, onOpenAttachments: () -> Unit) {
    val summary by records.storageUsage.collectAsStateWithLifecycle(initialValue = null)
    val context = LocalContext.current
    SettingsGroup("本地用量") {
        BasicComponent(
            title = "记录",
            endActions = { SettingValue(summary?.let { "${it.records} 条" } ?: "—") },
        )
        ArrowPreference(
            title = "附件管理",
            endActions = {
                SettingValue(summary?.let { "${it.files} 个 · ${Formatter.formatShortFileSize(context, it.bytes)}" } ?: "—")
            },
            onClick = onOpenAttachments,
        )
    }
}

@Composable
private fun AttachmentManagement(
    records: RecordsViewModel,
    onLocateRecord: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier,
) {
    val state by records.managedAttachments.collectAsStateWithLifecycle(initialValue = LibraryState.Loading)
    val saving by records.savingAttachment.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var pickedId by rememberSaveable { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val id = pickedId
        pickedId = null
        val destination = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && id != null && destination != null) {
            records.saveAttachment(id, destination)
        }
    }
    fun saveAs(attachment: Attachment) {
        if (pickedId != null || saving != null) return
        val name = attachment.fileName.replace('/', '_').replace('\\', '_')
        val type = attachment.mimeType.takeIf { '/' in it } ?: "application/octet-stream"
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(type)
            .putExtra(Intent.EXTRA_TITLE, name)
        pickedId = attachment.id
        try {
            picker.launch(intent)
        } catch (_: ActivityNotFoundException) {
            pickedId = null
            records.notify("找不到系统文件保存界面")
        }
    }
    SettingsPage("附件管理", onBack, modifier) {
        item { SettingsNote("按大小从大到小排列，显示仍保存在设备上的附件。") }
        when (val current = state) {
            LibraryState.Loading -> item { NexioSearchStatus("正在读取附件…") }
            LibraryState.Failed -> item { NexioSearchStatus("附件读取失败，点此重试", onRetry = records::retry) }
            is LibraryState.Loaded -> {
                if (current.entries.isEmpty()) item { NexioSearchStatus("还没有本地附件") }
                items(current.entries, key = { it.attachment.id }) { entry ->
                    val file = entry.attachment
                    ManagementCard(file.fileName, Formatter.formatShortFileSize(context, file.fileSize)) {
                        NexioSearchFilter("定位记录", active = false) { onLocateRecord(entry.recordId) }
                        NexioSearchFilter(
                            label = if (saving == file.id) "正在另存…" else "另存附件",
                            active = false,
                            enabled = saving == null && pickedId == null,
                        ) { saveAs(file) }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrashSettings(records: RecordsViewModel, onBack: () -> Unit, modifier: Modifier) {
    val state by records.trash.collectAsStateWithLifecycle(initialValue = LibraryState.Loading)
    var selected by remember { mutableStateOf<TrashRecord?>(null) }
    var confirming by remember { mutableStateOf(false) }
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    SettingsPage(
        "最近删除",
        onBack,
        modifier,
        overlay = { backdrop ->
            selected?.let { entry ->
                NexioOverlayDialog(
                    show = confirming,
                    title = "彻底删除这条记录？",
                    summary = "记录和保留的附件将从本机清理。此操作无法撤销。",
                    backdrop = backdrop,
                    onDismissRequest = { confirming = false },
                    onDismissFinished = { selected = null },
                ) {
                    NexioDeleteDialogActions(
                        confirmLabel = "彻底删除",
                        enabled = confirming,
                        onCancel = { confirming = false },
                        onConfirm = {
                            confirming = false
                            records.discardTrash(entry)
                        },
                    )
                }
            }
        },
    ) {
        item { SettingsNote("记录和附件保留 ${TrashRetentionDays} 天，期间仍占用本机空间。") }
        when (val current = state) {
            LibraryState.Loading -> item { NexioSearchStatus("正在读取最近删除…") }
            LibraryState.Failed -> item { NexioSearchStatus("读取失败，点此重试", onRetry = records::retry) }
            is LibraryState.Loaded -> {
                if (current.entries.isEmpty()) item { NexioSearchStatus("没有最近删除的记录") }
                items(current.entries, key = { it.record.id }) { entry ->
                    val record = entry.record
                    val title = record.content?.lineSequence()?.firstOrNull { it.isNotBlank() }?.take(96)
                        ?: record.attachments.firstOrNull()?.fileName ?: "记录"
                    val date = dateFormat.format(Date(entry.deletedAt))
                    val description = if (record.attachments.isEmpty()) date else "$date · ${record.attachments.size} 个附件"
                    ManagementCard(title, description) {
                        NexioSearchFilter("恢复记录", active = false) {
                            records.restoreTrash(TrashDeletion(listOf(record.id), entry.deletedAt))
                        }
                        NexioSearchFilter("彻底删除", active = false) {
                            if (selected == null) {
                                selected = entry
                                confirming = true
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ManagementCard(title: String, summary: String, actions: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
        BasicComponent(title = title, summary = summary)
        FlowRow(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) { actions() }
    }
}
