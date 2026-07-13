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
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.sibirskyspeak.MainActivity
import com.sibirskyspeak.R
import com.sibirskyspeak.data.LearningRepository
import com.sibirskyspeak.data.PrefsSettingsStore
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.Calendar
import java.util.concurrent.TimeUnit
import android.content.BroadcastReceiver

object Reminders {
    const val CHANNEL_ID = "daily_reminders"
    private const val WORK_NAME = "daily_reminder"
    private const val NOTIFICATION_ID = 4201

    fun ensureChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.notification_channel_description)
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
        // Recalculate the next local wall-clock occurrence after every run so
        // DST and timezone changes do not permanently shift the reminder.
        val request = OneTimeWorkRequestBuilder<DailyReminderWorker>()
            .setInitialDelay(millisUntilNextReminder(settings.reminderHour), TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    fun scheduleWeekly(context: Context) {
        if (!PrefsSettingsStore(context).reminderEnabled) {
            WorkManager.getInstance(context).cancelUniqueWork("weekly_letter")
            return
        }
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
            builder.addAction(NotificationCompat.Action.Builder(0, context.getString(R.string.notification_action_answer), replyPending)
                .addRemoteInput(RemoteInput.Builder("answer").setLabel(context.getString(R.string.notification_remote_input_label)).build())
                .build())
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
        Reminders.postNotification(
            applicationContext,
            applicationContext.getString(R.string.notification_weekly_title),
            applicationContext.getString(R.string.notification_weekly_body)
        )
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
        val title = applicationContext.getString(
            if (info.studiedToday) R.string.notification_studied_title else R.string.notification_ready_title
        )
        val streakDay = info.currentStreak + if (info.studiedToday) 0 else 1
        val body = applicationContext.resources.getQuantityString(
            R.plurals.notification_due_reviews,
            info.dueToday,
            info.dueToday,
            info.estimatedMinutes,
            streakDay
        )
        Reminders.postNotification(applicationContext, title, body, inline?.card?.id)
        Reminders.schedule(applicationContext)
        return Result.success()
    }

}

class InlineReviewReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val answer=RemoteInput.getResultsFromIntent(intent)?.getCharSequence("answer")?.toString() ?: return
        val cardId=intent.getLongExtra("cardId",-1L); if(cardId<0)return
        val request = OneTimeWorkRequestBuilder<InlineReviewWorker>()
            .setInputData(workDataOf(InlineReviewWorker.CARD_ID to cardId, InlineReviewWorker.ANSWER to answer))
            .build()
        // KEEP makes repeated broadcasts for the same card idempotent while a
        // review is in flight; completed work can be queued again later.
        WorkManager.getInstance(context).enqueueUniqueWork(
            "inline_review_$cardId",
            ExistingWorkPolicy.KEEP,
            request
        )
    }
}

@HiltWorker
class InlineReviewWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val repository: LearningRepository
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val cardId = inputData.getLong(CARD_ID, -1L)
        val answer = inputData.getString(ANSWER)?.takeIf(String::isNotBlank)
            ?: return Result.success()
        if (cardId < 0L) return Result.success()
        val correct = runCatching { repository.gradeInlineEnglish(cardId, answer) }
            .getOrElse { return Result.retry() }
        if (correct != null) {
            Reminders.postNotification(
                applicationContext,
                applicationContext.getString(if (correct) R.string.notification_correct_title else R.string.notification_saved_title),
                applicationContext.getString(if (correct) R.string.notification_correct_body else R.string.notification_saved_body)
            )
        }
        return Result.success()
    }

    companion object {
        const val CARD_ID = "card_id"
        const val ANSWER = "answer"
    }
}

class ReminderTimeChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // TIME_SET and TIMEZONE_CHANGED are protected broadcasts. Still verify
        // the action because malformed explicit intents must not trigger a
        // reschedule on platform versions that dispatch them to this receiver.
        if (intent.action != Intent.ACTION_TIME_CHANGED &&
            intent.action != Intent.ACTION_TIMEZONE_CHANGED
        ) {
            return
        }
        Reminders.schedule(context.applicationContext)
    }
}
