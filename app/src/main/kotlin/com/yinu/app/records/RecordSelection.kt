package com.yinu.app.records

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.haooz.chedule.ui.utils.rememberDefaultEdgeLight
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.BackdropEffectScope
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.edgelight.edgeLight
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.isRenderEffectSupported
import com.kyant.capsule.ContinuousCapsule
import com.kyant.capsule.ContinuousRoundedRectangle
import com.yinu.designsystem.YinuMenuIcons
import com.yinu.data.Record
import com.yinu.data.TrashRetentionDays

@Stable
internal class RecordSelectionHost {
    var active by mutableStateOf(false)
        private set
    var deleting by mutableStateOf(false)
        private set
    var selected by mutableStateOf<Map<String, Record>>(emptyMap())
        private set

    fun start(record: Record) {
        selected = mapOf(record.id to record)
        active = true
    }

    fun toggle(record: Record) {
        if (!active || deleting) return
        selected = if (record.id in selected) selected - record.id else selected + (record.id to record)
    }

    fun setSelected(record: Record, value: Boolean) {
        if (!active || deleting) return
        selected = if (value) selected + (record.id to record) else selected - record.id
    }

    fun cancel() {
        if (deleting) return
        active = false
        selected = emptyMap()
    }

    fun confirmDelete(dialog: RecordDeleteDialogHost, records: RecordsViewModel) {
        val picked = selected
        if (picked.isEmpty() || deleting) return
        dialog.open(DeletePrompt(
            title = "删除选中的 ${picked.size} 条记录？",
            summary = "记录和附件将移到「最近删除」，可在 ${TrashRetentionDays} 天内恢复。",
            confirmLabel = "删除",
            onConfirm = {
                deleting = true
                records.deleteRecords(picked.keys) { success ->
                    deleting = false
                    if (success) cancel()
                }
            },
        ))
    }
}

internal val LocalRecordSelection = staticCompositionLocalOf<RecordSelectionHost> { error("Selection host is missing") }

internal val RecordSelectionBarHeight = 96.dp
private val SelectionPanel = ContinuousRoundedRectangle(26.dp)
private val SelectionCapsule = ContinuousCapsule()
private val SelectionGlass: BackdropEffectScope.() -> Unit = { blur(16.dp.toPx()) }

@Composable
internal fun RecordSelectionBar(
    selection: RecordSelectionHost,
    backdrop: Backdrop,
    onDelete: () -> Unit,
    onExport: () -> Unit,
    onMerge: () -> Unit,
    onOrganize: () -> Unit,
    onLink: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val glass = isRenderEffectSupported()
    val enabled = selection.selected.isNotEmpty() && !selection.deleting
    val tint = colors.onSurface
    Column(
        modifier.fillMaxWidth().height(RecordSelectionBarHeight)
            .then(
                if (glass) Modifier.drawBackdrop(
                    backdrop = backdrop, shape = { SelectionPanel }, effects = SelectionGlass,
                    highlight = null, shadow = null,
                ) else Modifier,
            )
            .edgeLight(shape = SelectionPanel, edgeLight = rememberDefaultEdgeLight())
            .clip(SelectionPanel)
            .background(colors.surface.copy(alpha = if (glass) 0.86f else 1f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().height(32.dp).padding(start = 8.dp, end = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (selection.deleting) "处理中…" else "已选 ${selection.selected.size} 条",
                color = tint, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            val haptics = LocalHapticFeedback.current
            Box(
                Modifier.clip(SelectionCapsule).background(colors.onSurface.copy(alpha = 0.07f))
                    .clickable(enabled = !selection.deleting, role = Role.Button) {
                        haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                        selection.cancel()
                    }.padding(horizontal = 12.dp, vertical = 5.dp),
            ) {
                Text("取消", color = colors.onSurfaceVariant, fontSize = 12.sp)
            }
        }
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            SelectionAction("长图", YinuMenuIcons.image, tint, enabled, Modifier.weight(1f), onExport)
            SelectionAction("关联", YinuMenuIcons.link, tint, enabled, Modifier.weight(1f), onLink)
            SelectionAction("合并", YinuMenuIcons.multiSelect, tint, enabled && selection.selected.size >= 2, Modifier.weight(1f), onMerge)
            SelectionAction("AI 整理", YinuMenuIcons.ai, tint, enabled, Modifier.weight(1f), onOrganize)
            SelectionAction("删除", YinuMenuIcons.delete, colors.error, enabled, Modifier.weight(1f), onDelete,
                description = "删除所选消息")
        }
    }
}

@Composable
private fun SelectionAction(
    label: String,
    icon: ImageVector,
    tint: Color,
    enabled: Boolean,
    modifier: Modifier,
    action: () -> Unit,
    description: String? = null,
) {
    val haptics = LocalHapticFeedback.current
    val color = tint.copy(alpha = if (enabled) 1f else 0.38f)
    Column(
        modifier.clip(ContinuousRoundedRectangle(14.dp))
            .clickable(enabled = enabled, role = Role.Button) {
                haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                action()
            }
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
    ) {
        Icon(icon, contentDescription = null, Modifier.size(19.dp), tint = color)
        Text(label, color = color, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
internal fun RecordSelectionMark(selected: Boolean) {
    val colors = MaterialTheme.colorScheme
    Canvas(
        Modifier.size(23.dp)
            .background(if (selected) colors.primary else Color.Transparent, SelectionCapsule)
            .border(1.5.dp, if (selected) colors.primary else colors.outline, SelectionCapsule),
    ) {
        if (selected) {
            val start = Offset(size.width * 0.26f, size.height * 0.52f)
            val middle = Offset(size.width * 0.43f, size.height * 0.68f)
            val end = Offset(size.width * 0.74f, size.height * 0.35f)
            drawLine(colors.onPrimary, start, middle, 2.dp.toPx(), StrokeCap.Round)
            drawLine(colors.onPrimary, middle, end, 2.dp.toPx(), StrokeCap.Round)
        }
    }
}
