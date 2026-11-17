package com.yinu.app

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.provider.Settings
import android.Manifest
import android.os.Build
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.haooz.chedule.ui.basic.NexioSearchFilter
import com.haooz.chedule.ui.basic.NexioSearchStatus
import com.yinu.app.records.LibraryState
import com.yinu.app.records.RecordsViewModel
import com.yinu.app.records.ReminderDialog
import com.yinu.app.records.previewSummary
import com.yinu.data.Record
import com.yinu.app.reminders.rememberReminderAccess
import com.yinu.app.reminders.openExactReminderSettings
import com.yinu.app.reminders.showReminderTestNotification
import java.text.DateFormat
import java.util.Date
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.preference.ArrowPreference

@Composable
internal fun QuickRecordSettings(onBack: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    var result by remember { mutableStateOf<String?>(null) }
    SettingsPage("桌面快捷记录", onBack, modifier) { minimumBodyHeight ->
        item {
            Column(Modifier.heightIn(min = minimumBodyHeight), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SettingsGroup("从桌面开始") {
                    BasicComponent(title = "记文字", summary = "打开后直接聚焦输入框，确认发送才会保存")
                    BasicComponent(title = "存图片", summary = "直接打开图片选择器，取消选择不会新增记录")
                    ArrowPreference(title = "添加到桌面", summary = "添加「一隅 · 快捷记录」小组件", onClick = {
                        val manager = AppWidgetManager.getInstance(context)
                        result = if (manager.isRequestPinAppWidgetSupported) {
                            if (manager.requestPinAppWidget(ComponentName(context, QuickRecordWidget::class.java), null, null))
                                "请在桌面弹出的窗口中确认添加" else "请从桌面的小组件列表手动添加"
                        } else "当前桌面不支持直接添加，请长按桌面，找到小组件中的「一隅」"
                    })
                }
                SettingsNote(result ?: "也可以长按桌面空白处，在小组件列表中选择「一隅 · 快捷记录」。")
            }
        }
    }
}

@Composable
internal fun ReminderSettings(model: RecordsViewModel, locate: (String) -> Unit, onBack: () -> Unit, modifier: Modifier) {
    val state by model.reminderRecords.collectAsStateWithLifecycle(initialValue = LibraryState.Loading)
    val context = LocalContext.current
    var selected by remember { mutableStateOf<Record?>(null) }
    var dialog by remember { mutableStateOf(false) }
    val modify: (Record) -> Unit = { selected = it; dialog = true }
    SettingsPage("记录提醒", onBack, modifier, overlay = { backdrop ->
        selected?.let { record -> ReminderDialog(record, model, dialog, backdrop, { dialog = false }, { selected = null }) }
    }) { minimumBodyHeight ->
        val entries = (state as? LibraryState.Loaded)?.entries.orEmpty()
        // Group only a bounded, short body. Larger lists keep lazy composition.
        if (entries.size <= (minimumBodyHeight / 80.dp).toInt().coerceAtLeast(1)) {
            item {
                Column(Modifier.heightIn(min = minimumBodyHeight), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ReminderSettingsHeader()
                    when (state) {
                        LibraryState.Loading -> NexioSearchStatus("正在读取提醒…")
                        LibraryState.Failed -> NexioSearchStatus("无法读取提醒，请稍后重试")
                        is LibraryState.Loaded -> {
                            if (entries.isEmpty()) NexioSearchStatus("还没有记录提醒")
                            entries.forEach { ReminderSettingsCard(it, locate, modify) }
                        }
                    }
                }
            }
        } else {
            item { ReminderSettingsHeader() }
            items(entries, key = { it.id }) { ReminderSettingsCard(it, locate, modify) }
        }
    }
}

@Composable
private fun ReminderSettingsHeader() {
    val context = LocalContext.current
    val access = rememberReminderAccess()
    var testResult by remember { mutableStateOf<String?>(null) }
    val permissionRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { access.refresh() }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingsGroup("通知与时间") {
            ArrowPreference(title = "通知设置", summary = if (access.notifications) "通知已开启" else "通知未开启，点击设置", onClick = {
                if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                    permissionRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
                else context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            })
            ArrowPreference(title = "准时提醒", summary = if (access.exact) "已开启，按设定时间调度" else "未开启，系统可能延迟送达",
                onClick = { openExactReminderSettings(context) })
            ArrowPreference(title = "测试通知", summary = "检查通知栏能否收到一隅的通知", onClick = {
                testResult = if (showReminderTestNotification(context)) "已发出测试通知，请查看通知栏" else "通知未开启，请先检查通知权限和提醒渠道"
            })
            ArrowPreference(title = "系统应用设置", summary = "若仍收不到提醒，请检查后台运行和省电限制", onClick = {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
            })
        }
        testResult?.let { SettingsNote(it) }
        SettingsNote("长按消息可设置提醒。开启准时提醒后按设定时间调度，未开启时可能延迟。强行停止应用后需重新打开；已过期且未送达的旧提醒可重新设置时间。")
    }
}

@Composable
private fun ReminderSettingsCard(record: Record, locate: (String) -> Unit, modify: (Record) -> Unit) {
    SettingsGroup(if (record.reminderDelivered) "已结束" else if ((record.reminderAt ?: 0) <= System.currentTimeMillis()) "时间已到 · 未触发" else "待提醒") {
        BasicComponent(title = record.previewSummary(), summary = record.reminderAt?.let {
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it))
        }, onClick = { locate(record.id) })
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NexioSearchFilter("查看记录", false) { locate(record.id) }
            NexioSearchFilter("修改提醒", false) { modify(record) }
        }
    }
}
