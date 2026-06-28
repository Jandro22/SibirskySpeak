package com.sibirskyspeak

import android.app.Application
import androidx.room.withTransaction
import com.sibirskyspeak.data.AppDatabase
import com.sibirskyspeak.data.AssetBootstrap
import com.sibirskyspeak.data.BackupManager
import com.sibirskyspeak.data.LearningConfig
import com.sibirskyspeak.data.LearningRepository
import com.sibirskyspeak.data.PrefsSettingsStore
import com.sibirskyspeak.data.SettingsStore
import com.sibirskyspeak.scheduler.FsrsScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SibirskySpeakApp : Application() {
    val settings: SettingsStore by lazy { PrefsSettingsStore(this) }
    private val backup: BackupManager by lazy { BackupManager(this) }

    val repository: LearningRepository by lazy {
        val database = AppDatabase.get(this)
        val assets = AssetBootstrap(this)
        LearningRepository(
            noteDao = database.noteDao(),
            cardDao = database.cardDao(),
            reviewLogDao = database.reviewLogDao(),
            confusablePairDao = database.confusablePairDao(),
            readerTextDao = database.readerTextDao(),
            readingScheduleDao = database.readingScheduleDao(),
            readerEncounterDao = database.readerEncounterDao(),
            readingActivityDao = database.readingActivityDao(),
            telemetryDao = database.telemetryDao(),
            scheduler = FsrsScheduler(
                desiredRetentionProvider = { settings.desiredRetention },
                intervalModifierProvider = { settings.intervalModifier },
                weightsProvider = { settings.fsrsWeights },
                enableFuzz = true
            ),
            bootstrapNotes = { assets.readTextAsset("bootstrap_notes.jsonl") },
            bootstrapReaderTexts = { assets.readTextAsset("bootstrap_reader_texts.jsonl") },
            transactionRunner = { block -> database.withTransaction(block) },
            config = { LearningConfig(dailyGoal = settings.dailyGoal, sessionSize = settings.sessionSize, newCardsPerDay = settings.newCardsPerDay) },
            restoreBackup = { withContext(Dispatchers.IO) { backup.read() } },
            writeBackup = { content -> withContext(Dispatchers.IO) { backup.write(content) } }
        )
    }
}
