package com.yinu.app.records

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.haooz.chedule.ui.basic.NexioOverlayDialog
import com.haooz.chedule.ui.basic.NexioPickerActions
import com.kyant.backdrop.Backdrop
import com.kyant.capsule.ContinuousRoundedRectangle

@Composable
internal fun RecordTextSelectionDialog(
    text: String,
    show: Boolean,
    backdrop: Backdrop,
    onDismiss: () -> Unit,
    onDismissFinished: () -> Unit,
    onCopy: (String) -> Unit,
) {
    var value by remember(text) { mutableStateOf(TextFieldValue(text)) }
    val colors = MaterialTheme.colorScheme
    val selectionColors = remember(colors.primary) {
        TextSelectionColors(colors.primary, colors.primary.copy(alpha = 0.24f))
    }
    NexioOverlayDialog(
        show = show,
        title = "选择文字",
        summary = "长按文字，拖动手柄调整范围",
        backdrop = backdrop,
        onDismissRequest = onDismiss,
        onDismissFinished = onDismissFinished,
    ) {
        CompositionLocalProvider(LocalTextSelectionColors provides selectionColors) {
            BasicTextField(
                value = value,
                onValueChange = { value = it },
                readOnly = true,
                enabled = show,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
                cursorBrush = SolidColor(colors.primary),
                modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp, max = 240.dp)
                    .background(colors.onSurface.copy(alpha = 0.055f), ContinuousRoundedRectangle(16.dp))
                    .padding(14.dp)
                    .semantics { contentDescription = "消息正文" },
            )
        }
        NexioPickerActions(
            confirmLabel = "复制选中",
            enabled = show,
            confirmEnabled = !value.selection.collapsed,
            onCancel = onDismiss,
            onConfirm = {
                val selection = value.selection
                if (!selection.collapsed) {
                    onCopy(text.substring(selection.min, selection.max))
                    onDismiss()
                }
            },
        )
    }
}
