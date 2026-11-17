package com.yinu.app

import android.net.Uri
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.progressSemantics
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yinu.app.records.BackupStatus
import com.yinu.app.records.RecordsViewModel
import com.yinu.data.BackupProgress
import com.yinu.data.BackupPreview
import com.haooz.chedule.ui.basic.NexioOverlayDialog
import com.haooz.chedule.ui.basic.NexioPickerActions
import com.kyant.backdrop.Backdrop
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun LocalBackupSettings(records: RecordsViewModel, onInspect: (Uri) -> Unit) {
    val status by records.backupStatus.collectAsStateWithLifecycle()
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) {
        it?.let(records::exportBackup)
    }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        it?.let(onInspect)
    }

    SettingsGroup("本地备份") {
        ArrowPreference(
            title = "导出备份",
            summary = "将记录和附件保存到 ZIP 文件",
            enabled = status == null,
            onClick = { export.launch("YINU-备份-${LocalDate.now()}.zip") },
        )
        ArrowPreference(
            title = "从备份恢复",
            summary = "检查备份后确认恢复",
            enabled = status == null,
            onClick = { restore.launch(arrayOf("*/*")) },
        )
        status?.let { BackupProgressRow(it, records::cancelBackup) }
    }
}

@Composable
internal fun BackupInspectionDialog(
    source: Uri,
    records: RecordsViewModel,
    backdrop: Backdrop,
    onDismissFinished: () -> Unit,
) {
    var showing by remember(source) { mutableStateOf(true) }
    var preview by remember(source) { mutableStateOf<BackupPreview?>(null) }
    var errorMessage by remember(source) { mutableStateOf<String?>(null) }
    LaunchedEffect(source, showing) {
        if (showing) {
            try {
                preview = records.inspectBackup(source)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                errorMessage = "无法检查此文件。请选择可读取且完整的一隅 ZIP 备份。"
            }
        }
    }
    val inspected = preview
    val summary = when {
        inspected != null -> "备份完整性检查通过\n${inspected.records} 条记录 · ${inspected.attachments} 个附件（含 ${inspected.storedAttachments} 个文件副本）\n预计新增 ${inspected.newRecords} 条记录，补回 ${inspected.repairableAttachments} 个附件"
        errorMessage != null -> errorMessage
        else -> "正在检查备份和附件，请稍候…"
    }
    NexioOverlayDialog(
        show = showing,
        title = "备份检查",
        summary = summary,
        backdrop = backdrop,
        onDismissRequest = { showing = false },
        onDismissFinished = onDismissFinished,
    ) {
        NexioPickerActions(
            confirmLabel = "确认恢复",
            enabled = showing,
            confirmEnabled = inspected != null,
            onCancel = { showing = false },
            onConfirm = {
                if (showing && inspected != null) {
                    showing = false
                    records.restoreBackup(source, inspected)
                }
            },
        )
    }
}

@Composable
private fun BackupProgressRow(status: BackupStatus, onCancel: () -> Unit) {
    val context = LocalContext.current
    val progress = status.progress
    val title = when {
        status.cancelling -> "正在取消…"
        progress == BackupProgress.Finishing -> if (status.restoring) "正在写入记录…" else "正在完成备份…"
        status.restoring -> "正在恢复备份…"
        else -> "正在导出备份…"
    }
    val summary = if (progress is BackupProgress.Transferring) {
        val done = Formatter.formatShortFileSize(context, progress.bytesDone)
        val total = Formatter.formatShortFileSize(context, progress.bytesTotal)
        "${progress.filesDone}/${progress.filesTotal} 个附件 · $done / $total"
    } else null
    BasicComponent(title = title, summary = summary)
    if (progress is BackupProgress.Transferring && progress.bytesTotal > 0L) {
        val fraction = (progress.bytesDone.toDouble() / progress.bytesTotal).toFloat().coerceIn(0f, 1f)
        val colors = MiuixTheme.colorScheme
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
            .height(4.dp).progressSemantics(fraction)
            .background(colors.onSurface.copy(alpha = 0.12f), CircleShape)) {
            Box(Modifier.fillMaxWidth(fraction).height(4.dp).background(colors.primary, CircleShape))
        }
    }
    if (status.canCancel) {
        BasicComponent(title = if (status.restoring) "取消恢复" else "取消导出", onClick = onCancel)
    }
}
