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
import androidx.core.app.RemoteInput
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.sibirskyspeak.MainActivity
import com.sibirskyspeak.R
import com.sibirskyspeak.data.LearningRepository
import com.sibirskyspeak.data.PrefsSettingsStore
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.Calendar
import java.util.concurrent.TimeUnit
import android.content.BroadcastReceiver
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object Reminders {
    const val CHANNEL_ID = "daily_reminders"
    private const val WORK_NAME = "daily_reminder"
    private const val NOTIFICATION_ID = 4201

    fun ensureChannel(context: Context) {
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

    fun scheduleWeekly(context: Context) {
        val request = PeriodicWorkRequestBuilder<WeeklyReportWorker>(7, TimeUnit.DAYS).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("weekly_letter", ExistingPeriodicWorkPolicy.UPDATE, request)
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

    fun postNotification(context: Context, title: String, body: String, inlineCardId: Long? = null) {
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
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_reminder)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        if (inlineCardId != null) {
            val replyIntent=Intent(context,InlineReviewReceiver::class.java).putExtra("cardId",inlineCardId)
            val replyPending=PendingIntent.getBroadcast(context,inlineCardId.toInt(),replyIntent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            builder.addAction(NotificationCompat.Action.Builder(0,"Answer",replyPending).addRemoteInput(RemoteInput.Builder("answer").setLabel("English meaning").build()).build())
        }
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build())
    }
}

@HiltWorker class WeeklyReportWorker @AssistedInject constructor(
    @Assisted appContext: Context, @Assisted params: WorkerParameters,
    private val repository: LearningRepository
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        if (runCatching { repository.createWeeklyReport() }.getOrElse { return Result.retry() } == null) return Result.success()
        Reminders.postNotification(applicationContext, "Your weekly Russian letter", "Retention, time, and the next useful adjustment are ready in Lab.")
        return Result.success()
    }
}

@HiltWorker
class DailyReminderWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val repository: LearningRepository
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val settings = PrefsSettingsStore(applicationContext)
        if (!settings.reminderEnabled) return Result.success()
        val info = runCatching { repository.reminderInfo() }.getOrElse { return Result.retry() }
        val inline = runCatching { repository.nextPrompt() }.getOrNull()?.takeIf { it.answerMode == com.sibirskyspeak.review.AnswerMode.ENGLISH }
        if (settings.reminderHour != info.preferredHour) settings.reminderHour = info.preferredHour
        val title = if (info.studiedToday) "Today's contract is kept" else "Russian is ready"
        val body = "${info.dueToday} reviews, ~${info.estimatedMinutes} min — streak day ${info.currentStreak + if (info.studiedToday) 0 else 1}"
        Reminders.postNotification(applicationContext, title, body, inline?.card?.id)
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

@EntryPoint @InstallIn(SingletonComponent::class) interface InlineReviewEntryPoint { fun repository(): LearningRepository }

class InlineReviewReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val answer=RemoteInput.getResultsFromIntent(intent)?.getCharSequence("answer")?.toString() ?: return
        val cardId=intent.getLongExtra("cardId",-1L); if(cardId<0)return
        val pending=goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repo=EntryPointAccessors.fromApplication(context.applicationContext,InlineReviewEntryPoint::class.java).repository()
                val correct=repo.gradeInlineEnglish(cardId,answer)
                Reminders.postNotification(context,if(correct==true)"Correct" else "Review saved",if(correct==true)"That retrieval counts." else "The card will return sooner.")
            } finally { pending.finish() }
        }
    }
}
