package com.sibirskyspeak.data

import dagger.Lazy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

internal const val PRIMARY_BACKUP_DATA_VERSION = 1
internal const val PRIMARY_BACKUP_INTERVAL_MS = 20L * 60 * 60 * 1000

internal fun learnerBackupDue(lastBackupAt: Long, backupDataVersion: Int, now: Long): Boolean =
    backupDataVersion < PRIMARY_BACKUP_DATA_VERSION ||
        now - lastBackupAt >= PRIMARY_BACKUP_INTERVAL_MS

/**
 * Owns the primary tutor's boundary with durable learner storage.
 *
 * Bootstrap/recovery and full-state checkpointing still delegate to the proven
 * storage pipeline, but the tutor never depends on the legacy card/session
 * orchestrator directly. Checkpoints run in an application-lifetime scope so
 * leaving the Tutor screen cannot cancel a large backup. A failed/interrupted
 * attempt leaves both success markers untouched and is retried on next launch.
 */
@Singleton
class LearnerDataLifecycle @Inject constructor(
    private val storagePipeline: Lazy<LearningRepository>,
    private val settings: SettingsStore
) {
    @Volatile private var initialized = false
    private val initializationMutex = Mutex()
    private val checkpointMutex = Mutex()
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun initialize() {
        if (!initialized) {
            initializationMutex.withLock {
                if (!initialized) {
                    storagePipeline.get().seedIfEmpty(runMaintenance = false)
                    initialized = true
                }
            }
        }
        requestCheckpoint()
    }

    /** Queue a non-blocking checkpoint. Repeated requests collapse behind the
     * mutex and re-check the throttle after the first writer succeeds. */
    fun requestCheckpoint(force: Boolean = false, now: Long = System.currentTimeMillis()) {
        applicationScope.launch { runCatching { checkpointIfDue(force, now) } }
    }

    /** Synchronous form used by verification and explicit durability flows. */
    suspend fun checkpointIfDue(force: Boolean = false, now: Long = System.currentTimeMillis()): Boolean =
        checkpointMutex.withLock {
            if (!force && !learnerBackupDue(settings.lastBackupAt, settings.backupDataVersion, now)) {
                return@withLock false
            }
            val saved = storagePipeline.get().backupNow()
            if (saved) {
                settings.lastBackupAt = now
                settings.backupDataVersion = PRIMARY_BACKUP_DATA_VERSION
            }
            saved
        }
}
