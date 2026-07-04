package com.sibirskyspeak.di

import android.content.Context
import androidx.room.withTransaction
import com.sibirskyspeak.data.AppDatabase
import com.sibirskyspeak.data.AssetBootstrap
import com.sibirskyspeak.data.BackupManager
import com.sibirskyspeak.data.CardDao
import com.sibirskyspeak.data.CheckpointResultDao
import com.sibirskyspeak.data.ConfusablePairDao
import com.sibirskyspeak.data.ConfusionEventDao
import com.sibirskyspeak.data.ContentDao
import com.sibirskyspeak.data.ContentDatabase
import com.sibirskyspeak.data.LearningConfig
import com.sibirskyspeak.data.LearningModelDao
import com.sibirskyspeak.data.LearningRepository
import com.sibirskyspeak.data.MinedExampleDao
import com.sibirskyspeak.data.NoteDao
import com.sibirskyspeak.data.PrefsSettingsStore
import com.sibirskyspeak.data.ReaderEncounterDao
import com.sibirskyspeak.data.ReaderTextDao
import com.sibirskyspeak.data.ReadingActivityDao
import com.sibirskyspeak.data.ReadingScheduleDao
import com.sibirskyspeak.data.ReviewLogDao
import com.sibirskyspeak.data.SettingsStore
import com.sibirskyspeak.data.TelemetryDao
import com.sibirskyspeak.data.WeeklyReportDao
import com.sibirskyspeak.scheduler.FsrsScheduler
import com.sibirskyspeak.morph.MorphologyEngine
import com.sibirskyspeak.generation.FrameRealizer
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Singleton

/**
 * Everything here mirrors what SibirskySpeakApp.kt used to build by hand via `by lazy`.
 * Kept as one module (not split by concern) because it's a direct 1:1 port — see
 * CLAUDE.md's "Manual DI via by lazy" architecture note for why LearningRepository's
 * still-large constructor is the thing worth decomposing next, not this wiring.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase = AppDatabase.get(context)

    @Provides
    @Singleton
    fun provideContentDatabase(@ApplicationContext context: Context): ContentDatabase = ContentDatabase.get(context)

    @Provides fun provideNoteDao(db: AppDatabase): NoteDao = db.noteDao()
    @Provides fun provideCardDao(db: AppDatabase): CardDao = db.cardDao()
    @Provides fun provideReviewLogDao(db: AppDatabase): ReviewLogDao = db.reviewLogDao()
    @Provides fun provideConfusablePairDao(db: AppDatabase): ConfusablePairDao = db.confusablePairDao()
    @Provides fun provideReaderTextDao(db: AppDatabase): ReaderTextDao = db.readerTextDao()
    @Provides fun provideReadingScheduleDao(db: AppDatabase): ReadingScheduleDao = db.readingScheduleDao()
    @Provides fun provideReaderEncounterDao(db: AppDatabase): ReaderEncounterDao = db.readerEncounterDao()
    @Provides fun provideReadingActivityDao(db: AppDatabase): ReadingActivityDao = db.readingActivityDao()
    @Provides fun provideTelemetryDao(db: AppDatabase): TelemetryDao = db.telemetryDao()
    @Provides fun provideMinedExampleDao(db: AppDatabase): MinedExampleDao = db.minedExampleDao()
    @Provides fun provideLearningModelDao(db: AppDatabase): LearningModelDao = db.learningModelDao()
    @Provides fun provideWeeklyReportDao(db: AppDatabase): WeeklyReportDao = db.weeklyReportDao()
    @Provides fun provideCheckpointResultDao(db: AppDatabase): CheckpointResultDao = db.checkpointResultDao()
    @Provides fun provideContentDao(db: ContentDatabase): ContentDao = db.contentDao()
    @Provides @Singleton fun provideMorphologyEngine(dao: ContentDao): MorphologyEngine = MorphologyEngine(dao)

    @Provides @Singleton fun provideFrameRealizer(morph: MorphologyEngine): FrameRealizer = FrameRealizer(morph)

    @Provides
    @Singleton
    fun provideAssetBootstrap(@ApplicationContext context: Context): AssetBootstrap = AssetBootstrap(context)

    @Provides
    @Singleton
    fun provideSettingsStore(@ApplicationContext context: Context): SettingsStore = PrefsSettingsStore(context)

    @Provides
    @Singleton
    fun provideBackupManager(@ApplicationContext context: Context): BackupManager = BackupManager(context)

    // Backs ReviewViewModel's CPU-bound work (the FSRS weight fit); JVM tests bypass
    // this binding entirely by constructing ReviewViewModel directly with a test
    // dispatcher, so this only matters for the real, Hilt-driven app.
    @Provides
    fun provideComputeDispatcher(): CoroutineDispatcher = Dispatchers.Default

    @Provides
    @Singleton
    fun provideFsrsScheduler(settings: SettingsStore): FsrsScheduler = FsrsScheduler(
        desiredRetentionProvider = { settings.desiredRetention },
        intervalModifierProvider = { settings.intervalModifier },
        weightsProvider = { settings.fsrsWeights },
        enableFuzz = true
    )

    @Provides
    @Singleton
    fun provideLearningRepository(
        appDatabase: AppDatabase,
        noteDao: NoteDao,
        cardDao: CardDao,
        reviewLogDao: ReviewLogDao,
        confusablePairDao: ConfusablePairDao,
        readerTextDao: ReaderTextDao,
        readingScheduleDao: ReadingScheduleDao,
        readerEncounterDao: ReaderEncounterDao,
        readingActivityDao: ReadingActivityDao,
        telemetryDao: TelemetryDao,
        minedExampleDao: MinedExampleDao,
        learningModelDao: LearningModelDao,
        weeklyReportDao: WeeklyReportDao,
        confusionEventDao: ConfusionEventDao,
        checkpointResultDao: CheckpointResultDao,
        contentDao: ContentDao,
        morphologyEngine: MorphologyEngine,
        frameRealizer: FrameRealizer,
        assets: AssetBootstrap,
        settings: SettingsStore,
        scheduler: FsrsScheduler,
        backup: BackupManager
    ): LearningRepository = LearningRepository(
        noteDao = noteDao,
        cardDao = cardDao,
        reviewLogDao = reviewLogDao,
        confusablePairDao = confusablePairDao,
        readerTextDao = readerTextDao,
        readingScheduleDao = readingScheduleDao,
        readerEncounterDao = readerEncounterDao,
        readingActivityDao = readingActivityDao,
        telemetryDao = telemetryDao,
        minedExampleDao = minedExampleDao,
        learningModelDao = learningModelDao,
        weeklyReportDao = weeklyReportDao,
        confusionEventDao = confusionEventDao,
        checkpointResultDao = checkpointResultDao,
        contentDao = contentDao,
        morphologyEngine = morphologyEngine,
        frameRealizer = frameRealizer,
        corpusLemmaProvider = { assets.readTextAsset("deck_lemma.json") },
        scheduler = scheduler,
        bootstrapNotes = { assets.readTextAsset("bootstrap_notes.jsonl") },
        bootstrapReaderTexts = { assets.readTextAsset("bootstrap_reader_texts.jsonl") },
        transactionRunner = { block -> appDatabase.withTransaction(block) },
        config = {
            LearningConfig(
                dailyGoal = settings.dailyGoal,
                sessionSize = settings.sessionSize,
                newCardsPerDay = settings.newCardsPerDay,
                desiredRetention = settings.desiredRetention,
                doctrine = settings.doctrine
                , restDayCredits = settings.restDayCredits
            )
        },
        decayProvider = { FsrsScheduler.decayOf(settings.fsrsWeights) },
        restoreBackup = { withContext(Dispatchers.IO) { backup.read() } },
        writeBackup = { content -> withContext(Dispatchers.IO) { backup.write(content) } }
    )

    @Provides fun provideConfusionEventDao(db: AppDatabase): ConfusionEventDao = db.confusionEventDao()
}
