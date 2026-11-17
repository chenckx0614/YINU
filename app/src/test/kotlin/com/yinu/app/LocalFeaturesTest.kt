package com.yinu.app

import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import androidx.room.Room
import com.yinu.app.reminders.ReminderScheduler
import com.yinu.data.RecordRepository
import com.yinu.database.ReminderEntity
import com.yinu.database.YinuDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
class LocalFeaturesTest {
    @Before
    fun allowNotificationPermission() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    @Test
    fun exactPermissionChangesRearmTheSameReminderWithoutDuplicates() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, YinuDatabase::class.java).build()
        try {
            val repository = RecordRepository(context, db)
            repository.addText("准时提醒", messageId = "exact")
            val at = System.currentTimeMillis() + 60_000
            repository.setReminder("exact", at)
            val scheduler = ReminderScheduler(context, repository)
            val manager = shadowOf(context.getSystemService(AlarmManager::class.java))
            org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(false)
            scheduler.restore()
            check(manager.scheduledAlarms.single().windowLengthMs == org.robolectric.shadows.ShadowAlarmManager.WINDOW_HEURISTIC)
            org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
            scheduler.restore()
            check(manager.scheduledAlarms.single().windowLengthMs == org.robolectric.shadows.ShadowAlarmManager.WINDOW_EXACT)
            check(manager.scheduledAlarms.single().isAllowWhileIdle)
            org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(false)
            scheduler.restore()
            check(manager.scheduledAlarms.single().windowLengthMs == org.robolectric.shadows.ShadowAlarmManager.WINDOW_HEURISTIC)
        } finally { org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(false); db.close() }
    }

    @Test
    fun missingRuntimePermissionNeverClaimsOrPostsAReminder() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, YinuDatabase::class.java).build()
        try {
            val repository = RecordRepository(context, db)
            repository.addText("通知权限关闭", messageId = "permission")
            val due = System.currentTimeMillis() - 1_000
            db.records().putReminder(ReminderEntity("permission", due))
            shadowOf(context).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
            ReminderScheduler(context, repository).deliver("permission", due)
            check(!repository.record("permission")!!.reminderDelivered)
            check(shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.isEmpty())
        } finally { db.close() }
    }

    @Test
    fun pendingDueAlarmIsNotCancelledByAnUnrelatedRecordRefresh() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, YinuDatabase::class.java).build()
        try {
            val repository = RecordRepository(context, db)
            repository.addText("等待系统送达", messageId = "late")
            val now = System.currentTimeMillis()
            val at = now + 60_000
            val row = ReminderEntity("late", at)
            val scheduler = ReminderScheduler(context, repository)
            val alarms = shadowOf(context.getSystemService(AlarmManager::class.java))
            scheduler.reconcile(listOf(row), now)
            check(alarms.scheduledAlarms.size == 1)
            scheduler.reconcile(listOf(row), at + 30_000)
            check(alarms.scheduledAlarms.size == 1) { "A pending due alarm must survive a data refresh" }
            scheduler.reconcile(emptyList(), at + 30_000)
            check(alarms.scheduledAlarms.isEmpty())
        } finally { db.close() }
    }

    @Test
    fun disabledNotificationsDoNotClaimAReminderAsDelivered() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, YinuDatabase::class.java).build()
        try {
            val repository = RecordRepository(context, db)
            repository.addText("未开启通知", messageId = "disabled")
            val due = System.currentTimeMillis() - 1_000
            db.records().putReminder(ReminderEntity("disabled", due))
            val manager = context.getSystemService(NotificationManager::class.java)
            shadowOf(manager).setNotificationsEnabled(false)
            ReminderScheduler(context, repository).deliver("disabled", due)
            check(!repository.record("disabled")!!.reminderDelivered)
            check(shadowOf(manager).allNotifications.isEmpty())
        } finally { db.close() }
    }

    @Test
    fun alarmsReplaceAndDeletedOrOldRemindersNeverNotify() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, YinuDatabase::class.java).build()
        try {
            val repository = RecordRepository(context, db)
            val scheduler = ReminderScheduler(context, repository)
            scheduler.createChannel()
            repository.addText("提醒内容", messageId = "reminder")
            val first = System.currentTimeMillis() + 60_000
            val second = first + 60_000
            repository.setReminder("reminder", first)
            scheduler.restore()
            check(shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.size == 1)
            repository.setReminder("reminder", second)
            scheduler.restore()
            check(shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.size == 1)
            scheduler.deliver("reminder", first)
            val manager = context.getSystemService(NotificationManager::class.java)
            check(shadowOf(manager).allNotifications.isEmpty())
            val due = System.currentTimeMillis() - 1_000
            db.records().putReminder(ReminderEntity("reminder", due))
            scheduler.deliver("reminder", due)
            check(shadowOf(manager).allNotifications.size == 1)
            val notification = shadowOf(manager).allNotifications.single()
            check(shadowOf(notification.contentIntent).savedIntent.getStringExtra(LocateRecordExtra) == "reminder")
            scheduler.deliver("reminder", due)
            check(shadowOf(manager).allNotifications.size == 1)
            repository.deleteRecord("reminder")
            scheduler.cancelDisplayed(listOf("reminder"))
            scheduler.deliver("reminder", due)
            check(shadowOf(manager).allNotifications.isEmpty())
        } finally { db.close() }
    }

    @Test
    fun widgetButtonsOpenDistinctQuickActions() {
        val context = RuntimeEnvironment.getApplication()
        val manager = android.appwidget.AppWidgetManager.getInstance(context)
        val id = shadowOf(manager).createWidget(QuickRecordWidget::class.java, R.layout.quick_record_widget)
        val layout = shadowOf(manager).getViewFor(id)
        layout.findViewById<android.view.View>(R.id.quick_text).performClick()
        check(shadowOf(context).nextStartedActivity.getStringExtra(QuickActionExtra) == "text")
        layout.findViewById<android.view.View>(R.id.quick_image).performClick()
        check(shadowOf(context).nextStartedActivity.getStringExtra(QuickActionExtra) == "image")
    }
}
