package com.sibirskyspeak

import android.app.Application
import androidx.room.withTransaction
import com.sibirskyspeak.data.AppDatabase
import com.sibirskyspeak.data.AssetBootstrap
import com.sibirskyspeak.data.LearningRepository
import com.sibirskyspeak.scheduler.FsrsScheduler

class SibirskySpeakApp : Application() {
    val repository: LearningRepository by lazy {
        val database = AppDatabase.get(this)
        val assets = AssetBootstrap(this)
        LearningRepository(
            noteDao = database.noteDao(),
            cardDao = database.cardDao(),
            reviewLogDao = database.reviewLogDao(),
            confusablePairDao = database.confusablePairDao(),
            readerTextDao = database.readerTextDao(),
            scheduler = FsrsScheduler(),
            bootstrapNotes = { assets.readTextAsset("bootstrap_notes.jsonl") },
            bootstrapReaderTexts = { assets.readTextAsset("bootstrap_reader_texts.jsonl") },
            transactionRunner = { block -> database.withTransaction(block) }
        )
    }
}
