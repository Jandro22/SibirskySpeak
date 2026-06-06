package com.sibirskyspeak

import android.app.Application
import androidx.room.withTransaction
import com.sibirskyspeak.data.AppDatabase
import com.sibirskyspeak.data.AssetBootstrap
import com.sibirskyspeak.data.LearningConfig
import com.sibirskyspeak.data.LearningRepository
import com.sibirskyspeak.data.SettingsStore
import com.sibirskyspeak.scheduler.FsrsScheduler

class SibirskySpeakApp : Application() {
    val settings: SettingsStore by lazy { SettingsStore(this) }

    val repository: LearningRepository by lazy {
        val database = AppDatabase.get(this)
        val assets = AssetBootstrap(this)
        LearningRepository(
            noteDao = database.noteDao(),
            cardDao = database.cardDao(),
            reviewLogDao = database.reviewLogDao(),
            confusablePairDao = database.confusablePairDao(),
            readerTextDao = database.readerTextDao(),
            scheduler = FsrsScheduler(desiredRetentionProvider = { settings.desiredRetention }, enableFuzz = true),
            bootstrapNotes = { assets.readTextAsset("bootstrap_notes.jsonl") },
            bootstrapReaderTexts = { assets.readTextAsset("bootstrap_reader_texts.jsonl") },
            transactionRunner = { block -> database.withTransaction(block) },
            config = { LearningConfig(dailyGoal = settings.dailyGoal, sessionSize = settings.sessionSize, newCardsPerDay = settings.newCardsPerDay) }
        )
    }
}
