package com.yinu.app.records

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.haooz.chedule.ui.basic.NexioNumberPicker
import com.haooz.chedule.ui.basic.NexioOverlayDialog
import com.haooz.chedule.ui.basic.NexioPickerActions
import com.haooz.chedule.ui.basic.NexioSearchFilter
import com.kyant.backdrop.Backdrop
import com.yinu.data.Record
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import com.yinu.app.reminders.rememberReminderAccess
import com.yinu.app.reminders.openExactReminderSettings

@Composable
internal fun ReminderDialog(record: Record, model: RecordsViewModel, show: Boolean, backdrop: Backdrop,
    onDismiss: () -> Unit, onFinished: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val access = rememberReminderAccess()
    val permission = access.notifications
    val permissionRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { access.refresh() }
    var time by remember(record.id) { mutableStateOf(Instant.ofEpochMilli(
        record.reminderAt?.takeIf { it > System.currentTimeMillis() } ?: System.currentTimeMillis() + 3_600_000)
        .atZone(ZoneId.systemDefault()).toLocalDateTime().withSecond(0).withNano(0)) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val dismiss = { if (!saving) onDismiss() }
    NexioOverlayDialog(show = show, title = "记录提醒", backdrop = backdrop, onDismissRequest = dismiss, onDismissFinished = onFinished) {
        Column(Modifier.heightIn(max = dialogBodyMaxHeight(410.dp)).verticalScroll(androidx.compose.foundation.rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (access.exact) "已开启准时提醒。请保持通知和系统后台运行权限开启。"
                else "未开启准时提醒，系统可能推迟送达。可在下面开启「闹钟和提醒」权限。",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            if (!access.exact && Build.VERSION.SDK_INT >= 31) {
                NexioSearchFilter("开启准时提醒", false, enabled = !saving) { openExactReminderSettings(context) }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                NexioSearchFilter("下一分钟", false) { time = LocalDateTime.now().plusMinutes(1).withSecond(0).withNano(0) }
                NexioSearchFilter("10 分钟后", false) { time = LocalDateTime.now().plusMinutes(10).withSecond(0) }
                NexioSearchFilter("1 小时后", false) { time = LocalDateTime.now().plusHours(1).withSecond(0) }
            }
            Row(Modifier.fillMaxWidth()) {
                TimeWheel(time.year, LocalDateTime.now().year..2100, "年份", { "${it}年" }, Modifier.weight(1.25f)) { time = time.withYear(it) }
                TimeWheel(time.monthValue, 1..12, "月份", { "${it}月" }, Modifier.weight(1f)) { time = time.withMonth(it) }
                key(time.toLocalDate().lengthOfMonth()) {
                    TimeWheel(time.dayOfMonth, 1..time.toLocalDate().lengthOfMonth(), "日期", { "${it}日" }, Modifier.weight(1f)) { time = time.withDayOfMonth(it) }
                }
            }
            Row(Modifier.fillMaxWidth()) {
                TimeWheel(time.hour, 0..23, "小时", { "%02d 时".format(it) }, Modifier.weight(1f)) { time = time.withHour(it) }
                TimeWheel(time.minute, 0..59, "分钟", { "%02d 分".format(it) }, Modifier.weight(1f)) { time = time.withMinute(it) }
            }
            if (!permission) {
                Text("通知未开启，开启后才能收到提醒。", color = MaterialTheme.colorScheme.error)
                NexioSearchFilter("开启通知", false) {
                    if (Build.VERSION.SDK_INT >= 33 && !androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS).let { it == android.content.pm.PackageManager.PERMISSION_GRANTED })
                        permissionRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                }
                NexioSearchFilter("前往通知设置", false) {
                    context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                }
            }
            if (record.reminderAt != null) {
                NexioSearchFilter("取消此提醒", false, enabled = !saving) {
                    saving = true
                    scope.launch {
                        try { model.cancelReminder(record.id); onDismiss() }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { error = "取消失败，请重试" }
                        finally { saving = false }
                    }
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        NexioPickerActions(confirmLabel = if (saving) "保存中…" else "保存提醒", enabled = show && !saving,
            confirmEnabled = permission, onCancel = dismiss, onConfirm = {
                val at = time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                if (at <= System.currentTimeMillis()) { error = "请选择未来的时间" }
                else {
                    saving = true
                    scope.launch {
                        try { model.setReminder(record.id, at); onDismiss() }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { error = "提醒保存失败，记录可能已删除" }
                        finally { saving = false }
                    }
                }
            })
    }
}

@Composable
private fun TimeWheel(value: Int, range: IntRange, label: String, format: (Int) -> String, modifier: Modifier, change: (Int) -> Unit) {
    NexioNumberPicker(value = value, onValueChange = change, range = range, label = format,
        accessibilityLabel = label, modifier = modifier, visibleItemCount = 3, itemHeight = 40.dp,
        textStyle = TextStyle(fontSize = 20.sp))
}
