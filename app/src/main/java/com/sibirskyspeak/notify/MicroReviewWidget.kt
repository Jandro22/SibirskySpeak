package com.sibirskyspeak.notify

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.sibirskyspeak.MainActivity
import com.sibirskyspeak.R
import com.sibirskyspeak.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MicroReviewWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val snapshot = runCatching {
                    val db = AppDatabase.get(context)
                    val now = System.currentTimeMillis()
                    val due = db.cardDao().countDue(now)
                    val card = db.cardDao().getDueCards(now, 1).firstOrNull()
                    due to card?.let { db.noteDao().getById(it.noteId)?.russian }.orEmpty()
                }.getOrElse { 0 to "" }
                val due = snapshot.first
                val russian = snapshot.second
                val dueLabel = context.resources.getQuantityString(R.plurals.widget_due, due, due)
                ids.forEach { id ->
                    val views = RemoteViews(context.packageName, R.layout.micro_review_widget)
                    views.setTextViewText(R.id.widget_due, dueLabel)
                    views.setTextViewText(R.id.widget_russian, russian.ifBlank { context.getString(R.string.widget_russian_ready) })
                    val intent = Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_MICRO, true)
                    views.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                    manager.updateAppWidget(id, views)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
