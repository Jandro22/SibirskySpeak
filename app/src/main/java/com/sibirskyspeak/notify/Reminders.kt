package com.sibirskyspeak.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.sibirskyspeak.MainActivity
import com.sibirskyspeak.R
import com.sibirskyspeak.SibirskySpeakApp
import com.sibirskyspeak.data.PrefsSettingsStore
import java.util.Calendar
import java.util.concurrent.TimeUnit

object Reminders {
    const val CHANNEL_ID = "daily_reminders"
    private const val WORK_NAME = "daily_reminder"
    private const val NOTIFICATION_ID = 4201

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Daily study reminders",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Nudges to keep your streak alive and clear due reviews."
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /**
     * Schedule (or refresh) the recurring daily reminder at the user's chosen hour.
     * If reminders are disabled in settings, cancels any pending work instead.
     */
    fun schedule(context: Context) {
        val settings = PrefsSettingsStore(context)
        if (!settings.reminderEnabled) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<DailyReminderWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(millisUntilNextReminder(settings.reminderHour), TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    private fun millisUntilNextReminder(reminderHour: Int): Long {
        val now = Calendar.getInstance()
        val next = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, reminderHour)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (before(now)) add(Calendar.DAY_OF_MONTH, 1)
        }
        return next.timeInMillis - now.timeInMillis
    }

    fun postNotification(context: Context, title: String, body: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ensureChannel(context)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_reminder)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }
}

class DailyReminderWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val repo = (applicationContext as SibirskySpeakApp).repository
        val info = runCatching { repo.reminderInfo() }.getOrNull()
        val (title, body) = composeMessage(
            streak = info?.currentStreak ?: 0,
            studiedToday = info?.studiedToday ?: false,
            dueToday = info?.dueToday ?: 0
        )
        Reminders.postNotification(applicationContext, title, body)
        return Result.success()
    }

    private fun composeMessage(streak: Int, studiedToday: Boolean, dueToday: Int): Pair<String, String> {
        // Already studied today: a lighter, congratulatory nudge.
        if (studiedToday) {
            val msgs = listOf(
                "Nicely done today. Your streak is safe. A few extra cards never hurt.",
                "Today's goal is in the bag. Want to push your level a little higher?",
                "Great work today. Reading a short text now would lock it in."
            )
            return "Nice work today" to msgs.random()
        }
        // Streak at risk: emphasize the streak.
        if (streak > 0) {
            return "Keep your $streak-day streak" to
                if (dueToday > 0) "$dueToday cards are waiting. Two minutes keeps your streak alive."
                else "Keep the momentum. A quick review keeps your streak going."
        }
        // No active streak: invite to start.
        val openers = listOf(
            "Time for Russian",
            "Your daily Russian is ready",
            "A few minutes of Russian today?"
        )
        val body = when {
            dueToday > 0 -> "$dueToday cards are due. Start a quick session and begin a new streak."
            else -> "Read a short text or learn a few new words to start a streak."
        }
        return openers.random() to body
    }
}
