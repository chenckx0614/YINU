package com.yinu.app.reminders

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

internal fun canSendReminders(context: Context): Boolean {
    if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
    val manager = context.getSystemService(NotificationManager::class.java)
    return NotificationManagerCompat.from(context).areNotificationsEnabled() &&
        manager.getNotificationChannel("record-reminders")?.importance != NotificationManager.IMPORTANCE_NONE
}

internal fun canUseExactReminders(context: Context): Boolean = Build.VERSION.SDK_INT < 31 ||
    context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

internal fun openExactReminderSettings(context: Context) {
    try {
        val intent = if (Build.VERSION.SDK_INT >= 31)
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
        else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        context.startActivity(intent)
    } catch (_: android.content.ActivityNotFoundException) {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
    }
}

internal class ReminderAccessState(private val context: Context) {
    var notifications by mutableStateOf(canSendReminders(context))
        private set
    var exact by mutableStateOf(canUseExactReminders(context))
        private set
    fun refresh() { notifications = canSendReminders(context); exact = canUseExactReminders(context) }
}

@Composable
internal fun rememberReminderAccess(): ReminderAccessState {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val state = remember(context) { ReminderAccessState(context) }
    DisposableEffect(lifecycle, state) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) state.refresh() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return state
}
