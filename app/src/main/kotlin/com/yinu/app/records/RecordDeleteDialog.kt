package com.yinu.app.records

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.haooz.chedule.ui.basic.NexioDeleteDialogActions
import com.haooz.chedule.ui.basic.NexioOverlayDialog
import com.kyant.backdrop.Backdrop

internal data class DeletePrompt(
    val title: String,
    val summary: String,
    val confirmLabel: String,
    val onConfirm: () -> Unit,
)

internal class RecordDeleteDialogHost {
    var prompt by mutableStateOf<DeletePrompt?>(null)
        private set
    var visible by mutableStateOf(false)
        private set

    fun open(value: DeletePrompt) {
        prompt = value
        visible = true
    }

    fun dismiss() {
        visible = false
    }

    fun removeClosed(value: DeletePrompt) {
        if (!visible && prompt === value) prompt = null
    }
}

internal val LocalRecordDeleteDialog = staticCompositionLocalOf<RecordDeleteDialogHost> {
    error("Delete dialog host is missing")
}

@Composable
internal fun RecordDeleteDialogOverlay(host: RecordDeleteDialogHost, backdrop: Backdrop) {
    val prompt = host.prompt ?: return
    NexioOverlayDialog(
        show = host.visible,
        title = prompt.title,
        summary = prompt.summary,
        backdrop = backdrop,
        onDismissRequest = host::dismiss,
        onDismissFinished = { host.removeClosed(prompt) },
    ) {
        NexioDeleteDialogActions(
            confirmLabel = prompt.confirmLabel,
            enabled = host.visible,
            onCancel = host::dismiss,
            onConfirm = {
                if (host.visible) {
                    host.dismiss()
                    prompt.onConfirm()
                }
            },
        )
    }
}
