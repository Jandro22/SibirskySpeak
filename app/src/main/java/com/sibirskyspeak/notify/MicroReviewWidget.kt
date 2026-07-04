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
import kotlinx.coroutines.launch

class MicroReviewWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AppDatabase.get(context)
                val due = db.cardDao().countDue(System.currentTimeMillis())
                val card = db.cardDao().getDueCards(System.currentTimeMillis(), 1).firstOrNull()
                val russian = card?.let { db.noteDao().getById(it.noteId)?.russian }.orEmpty()
                ids.forEach { id ->
                    val views = RemoteViews(context.packageName, R.layout.micro_review_widget)
                    views.setTextViewText(R.id.widget_due, "$due due")
                    views.setTextViewText(R.id.widget_russian, russian.ifBlank { "Russian is ready" })
                    val intent = Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_MICRO, true)
                    views.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                    manager.updateAppWidget(id, views)
                }
            } finally { pending.finish() }
        }
    }
}
