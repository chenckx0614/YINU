package com.yinu.app.records

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.haooz.chedule.ui.basic.NexioOverlayDialog
import com.haooz.chedule.ui.basic.NexioPickerActions
import com.kyant.backdrop.Backdrop
import com.kyant.capsule.ContinuousRoundedRectangle
import com.yinu.data.Record
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun RecordEditDialog(
    record: Record,
    show: Boolean,
    backdrop: Backdrop,
    onDismiss: () -> Unit,
    onDismissFinished: () -> Unit,
    onSave: suspend (String) -> Boolean,
) {
    val original = record.content.orEmpty()
    var text by remember(record.id) { mutableStateOf(original) }
    var saving by remember(record.id) { mutableStateOf(false) }
    var failure by remember(record.id) { mutableStateOf<String?>(null) }
    val colors = MaterialTheme.colorScheme
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val canSave = text.trim() != original.trim() && (text.isNotBlank() || record.attachments.isNotEmpty())
    val dismiss = {
        if (!saving) {
            focus.clearFocus(force = true)
            onDismiss()
        }
    }
    NexioOverlayDialog(
        show = show,
        title = if (record.attachments.isEmpty()) "编辑记录" else "编辑附件说明",
        backdrop = backdrop,
        onDismissRequest = dismiss,
        onDismissFinished = onDismissFinished,
    ) {
        BasicTextField(
            value = text,
            onValueChange = {
                text = it
                failure = null
            },
            enabled = show && !saving,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
            cursorBrush = SolidColor(colors.primary),
            modifier = Modifier.fillMaxWidth().heightIn(min = 88.dp, max = 240.dp)
                .background(colors.onSurface.copy(alpha = 0.055f), ContinuousRoundedRectangle(16.dp))
                .padding(14.dp)
                .semantics { contentDescription = "编辑正文" },
            decorationBox = { field ->
                Box {
                    if (text.isEmpty()) Text("写点什么…", color = colors.onSurfaceVariant)
                    field()
                }
            },
        )
        failure?.let { Text(it, Modifier.padding(top = 8.dp), color = colors.error) }
        NexioPickerActions(
            confirmLabel = if (saving) "正在保存…" else "保存修改",
            enabled = show && !saving,
            confirmEnabled = canSave,
            onCancel = dismiss,
            onConfirm = {
                if (!saving && canSave) {
                    saving = true
                    scope.launch {
                        try {
                            if (onSave(text)) {
                                focus.clearFocus(force = true)
                                onDismiss()
                            } else {
                                failure = "这条记录已无法编辑"
                            }
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            failure = "保存失败，请重试"
                        } finally {
                            saving = false
                        }
                    }
                }
            },
        )
    }
}
