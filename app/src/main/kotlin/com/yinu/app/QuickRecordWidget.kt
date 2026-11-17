package com.yinu.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews

internal const val QuickActionExtra = "com.yinu.app.QUICK_ACTION"
internal const val LocateRecordExtra = "com.yinu.app.LOCATE_RECORD"

class QuickRecordWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { id ->
            val views = RemoteViews(context.packageName, R.layout.quick_record_widget)
            for ((view, action) in listOf(R.id.quick_text to "text", R.id.quick_image to "image")) {
                val intent = Intent(context, MainActivity::class.java)
                    .setData(Uri.parse("yinu://quick/$action"))
                    .putExtra(QuickActionExtra, action)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                views.setOnClickPendingIntent(view, PendingIntent.getActivity(context, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            }
            manager.updateAppWidget(id, views)
        }
    }
}
