package com.yinu.app.reminders

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import com.yinu.app.LocateRecordExtra
import com.yinu.app.MainActivity
import com.yinu.app.R
import com.yinu.data.RecordRepository
import com.yinu.database.ReminderEntity
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val ChannelId = "record-reminders"

internal fun showReminderTestNotification(context: Context): Boolean {
    if (!canSendReminders(context)) return false
    val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java)
        .setData(Uri.parse("yinu://notification-test")), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    val notice = NotificationCompat.Builder(context, ChannelId).setSmallIcon(R.drawable.ic_reminder)
        .setContentTitle("一隅 · 测试通知").setContentText("通知通道正常，记录提醒会通过这里显示。")
        .setContentIntent(open).setAutoCancel(true).build()
    return try { context.getSystemService(NotificationManager::class.java).notify("yinu-notification-test", 0, notice); true }
    catch (_: SecurityException) { false }
}

@Singleton
class ReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val records: RecordRepository,
) {
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val scheduled = mutableMapOf<String, Long>()
    private val exactModes = mutableMapOf<String, Boolean>()
    private val mutex = Mutex()

    fun createChannel() {
        notifications.createNotificationChannel(NotificationChannel(ChannelId, "记录提醒", NotificationManager.IMPORTANCE_HIGH)
            .apply { description = "你为记录设置的本地提醒" })
    }

    fun notificationsEnabled(): Boolean = canSendReminders(context)

    fun watch(scope: CoroutineScope) {
        createChannel()
        scope.launch {
            records.pendingReminders.collect { rows ->
                try { reconcile(rows) } catch (e: Exception) { android.util.Log.e("YinuReminders", "Unable to schedule reminders", e) }
            }
        }
    }

    suspend fun restore() = reconcile(records.pendingReminders.first(), force = true)

    private fun alarmIntent(id: String, at: Long): PendingIntent = PendingIntent.getBroadcast(
        context, 0, Intent(context, ReminderReceiver::class.java)
            .setData(Uri.parse("yinu://reminder/${Uri.encode(id)}"))
            .putExtra("id", id).putExtra("at", at),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    internal suspend fun reconcile(rows: List<ReminderEntity>, now: Long = System.currentTimeMillis(), force: Boolean = false) = mutex.withLock {
        val pending = rows.associate { it.messageId to it.atMillis }
        // A due alarm may still be waiting for Android to deliver it. Cancel only
        // reminders that were removed or changed, never merely because time passed.
        scheduled.keys.toList().filter { pending[it] != scheduled[it] }.forEach { id ->
            alarms.cancel(alarmIntent(id, scheduled.getValue(id)))
            scheduled.remove(id)
            exactModes.remove(id)
        }
        val exact = canUseExactReminders(context)
        for ((id, at) in pending) {
            if (at <= now) continue
            if (force || scheduled[id] != at || exactModes[id] != exact) {
                val operation = alarmIntent(id, at)
                var usedExact = exact
                if (exact) {
                    try { alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation) }
                    catch (_: SecurityException) { usedExact = false; alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation) }
                } else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation)
                scheduled[id] = at
                exactModes[id] = usedExact
            }
        }
    }

    suspend fun cancelDisplayed(ids: Collection<String>) = mutex.withLock {
        ids.forEach { id ->
            alarms.cancel(alarmIntent(id, 0))
            scheduled.remove(id)
            exactModes.remove(id)
            notifications.cancel(id, 0)
        }
    }

    suspend fun deliver(id: String, at: Long) {
        createChannel()
        if (!notificationsEnabled()) return
        val record = records.record(id) ?: return
        val claimedAt = System.currentTimeMillis()
        if (!records.claimReminder(id, at, claimedAt)) return
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java)
            .setData(Uri.parse("yinu://record/${Uri.encode(id)}"))
            .putExtra(LocateRecordExtra, id).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val text = record.content?.takeIf { it.isNotBlank() } ?: record.attachments.firstOrNull()?.fileName ?: "查看这条记录"
        val notice = NotificationCompat.Builder(context, ChannelId)
            .setSmallIcon(R.drawable.ic_reminder).setContentTitle("一隅 · 记录提醒")
            .setContentText(text.take(180)).setStyle(NotificationCompat.BigTextStyle().bigText(text.take(1_000)))
            .setContentIntent(open).setAutoCancel(true).setCategory(NotificationCompat.CATEGORY_REMINDER).build()
        if (!notificationsEnabled() || records.record(id) == null) {
            records.releaseReminderClaim(id, at, claimedAt)
            return
        }
        try { notifications.notify(id, 0, notice) }
        catch (_: SecurityException) { records.releaseReminderClaim(id, at, claimedAt) }
    }
}

private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

@AndroidEntryPoint
class ReminderReceiver : BroadcastReceiver() {
    @Inject lateinit var scheduler: ReminderScheduler
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("id") ?: return
        val at = intent.getLongExtra("at", -1)
        if (at <= 0) return
        val pending = goAsync()
        receiverScope.launch {
            try { scheduler.deliver(id, at) }
            catch (e: Exception) { android.util.Log.e("YinuReminders", "Unable to deliver reminder", e) }
            finally { pending.finish() }
        }
    }
}

@AndroidEntryPoint
class ReminderRestoreReceiver : BroadcastReceiver() {
    @Inject lateinit var scheduler: ReminderScheduler
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED,
                Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_MY_PACKAGE_REPLACED,
                AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)) return
        val pending = goAsync()
        receiverScope.launch {
            try { scheduler.restore() }
            catch (e: Exception) { android.util.Log.e("YinuReminders", "Unable to restore reminders", e) }
            finally { pending.finish() }
        }
    }
}
