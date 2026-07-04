package com.sibirskyspeak

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Dependency wiring itself now lives in di/AppModule.kt (Hilt) instead of the `by lazy`
 * properties this class used to hold directly — see CLAUDE.md's DI note. Still needed as
 * the @HiltAndroidApp entry point, and to hand WorkManager a Hilt-aware worker factory so
 * DailyReminderWorker (notify/Reminders.kt) can be constructor-injected instead of reaching
 * for a manual `(applicationContext as SibirskySpeakApp)` cast.
 */
@HiltAndroidApp
class SibirskySpeakApp : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
