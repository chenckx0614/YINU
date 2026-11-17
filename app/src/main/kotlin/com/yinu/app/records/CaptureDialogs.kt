package com.yinu.app.records

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.haooz.chedule.ui.basic.NexioOverlayDialog
import com.haooz.chedule.ui.basic.NexioPickerActions
import com.haooz.chedule.ui.basic.NexioSearchFilter
import com.kyant.backdrop.Backdrop
import com.yinu.app.IncomingShare
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun CaptureField(value: String, onChange: (String) -> Unit, hint: String, enabled: Boolean = true, secret: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    BasicTextField(value, onChange, Modifier.fillMaxWidth()
        .background(colors.onSurface.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
        .padding(12.dp).semantics { contentDescription = hint },
        enabled = enabled, maxLines = 4, singleLine = secret,
        keyboardOptions = if (secret) KeyboardOptions(keyboardType = KeyboardType.Password) else KeyboardOptions.Default,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        textStyle = TextStyle(color = colors.onSurface, fontSize = 16.sp),
        cursorBrush = SolidColor(colors.primary), decorationBox = { input ->
            Box {
                if (value.isEmpty()) Text(hint, color = colors.onSurfaceVariant)
                input()
            }
        })
}

@Composable
internal fun IncomingShareDialog(share: IncomingShare, model: RecordsViewModel, backdrop: Backdrop) {
    var note by rememberSaveable { mutableStateOf("") }
    var showing by rememberSaveable { mutableStateOf(true) }
    var saveNote by rememberSaveable { mutableStateOf<String?>(null) }
    val summary = listOfNotNull(
        share.text?.take(240)?.let { if (share.text.length > 240) "$it…" else it },
        share.files.size.takeIf { it > 0 }?.let { "$it 个附件" },
    ).joinToString("\n")
    NexioOverlayDialog(showing, "保存分享", summary, backdrop,
        onDismissRequest = { showing = false },
        onDismissFinished = { model.finishIncomingShare(share, saveNote) },
    ) {
        CaptureField(note, { note = it }, "补一句说明（可选）", showing)
        if (note.isNotBlank()) {
            Box(Modifier.padding(top = 8.dp)) {
                NexioSearchFilter("直接保存", false, enabled = showing) {
                    saveNote = ""
                    showing = false
                }
            }
        }
        NexioPickerActions(if (note.isBlank()) "直接保存" else "保存", showing,
            onCancel = { showing = false }, onConfirm = { saveNote = note; showing = false })
    }
}

@Composable
internal fun MergeRecordDialog(menu: RecordMenu.Merge, show: Boolean, backdrop: Backdrop, dismiss: () -> Unit, finished: () -> Unit) {
    val scope = rememberCoroutineScope()
    var caption by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    NexioOverlayDialog(show, "合并记录", "将 ${menu.ids.size} 条记录的正文和附件合成一条新记录，方便一起查看。", backdrop,
        { if (!busy) dismiss() }, finished) {
        CaptureField(caption, { caption = it }, "给合并记录加一句说明", !busy)
        if (error) Text("合并失败，请确认原记录仍在，并检查剩余存储空间。")
        NexioPickerActions(if (busy) "正在合并…" else "合并", show && !busy,
            onCancel = dismiss, onConfirm = {
                if (!busy) {
                    busy = true
                    error = false
                    scope.launch {
                        try {
                            menu.model.mergeRecords(menu.ids, caption)
                            menu.done()
                            dismiss()
                            menu.model.notify("已生成合并记录")
                        } catch (c: CancellationException) {
                            throw c
                        } catch (_: Exception) {
                            error = true
                        } finally {
                            busy = false
                        }
                    }
                }
            })
    }
}

@Composable
internal fun FavoriteGroupDialog(menu: RecordMenu.FavoriteGroup, show: Boolean, backdrop: Backdrop, dismiss: () -> Unit, finished: () -> Unit) {
    val scope = rememberCoroutineScope()
    val groups by menu.model.favoriteGroups.collectAsStateWithLifecycle(initialValue = emptyList())
    var group by rememberSaveable { mutableStateOf(menu.record.favoriteGroup.orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    NexioOverlayDialog(show, "收藏分组", "选择已有分组，或输入名称创建分组。", backdrop,
        { if (!busy) dismiss() }, finished) {
        CaptureField(group, { group = it.take(32).replace("\n", "") }, "分组名称", !busy)
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NexioSearchFilter("不分组", group.isBlank(), enabled = !busy) { group = "" }
            groups.forEach { name ->
                NexioSearchFilter(name, name == group, enabled = !busy) { group = name }
            }
        }
        NexioPickerActions("保存", show && !busy, onCancel = dismiss, onConfirm = {
            if (!busy) {
                busy = true
                scope.launch {
                    try {
                        if (menu.model.setFavoriteGroup(menu.record.id, group)) dismiss()
                        else menu.model.notify("找不到这条记录")
                    } catch (c: CancellationException) {
                        throw c
                    } catch (_: Exception) {
                        menu.model.notify("无法保存收藏分组")
                    } finally {
                        busy = false
                    }
                }
            }
        })
    }
}
