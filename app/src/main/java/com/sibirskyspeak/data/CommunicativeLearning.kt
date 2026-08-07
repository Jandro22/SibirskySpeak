package com.sibirskyspeak.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update

/**
 * Durable knowledge, independent of the exercise used to observe it.
 *
 * Card rows are retained as migration/source records, but the tutor schedules these
 * components. A tile reconstruction, a dialogue response, and a reader check can all
 * update the same component with different evidence weights instead of creating three
 * unrelated memories for one fact.
 */
@Entity(
    tableName = "knowledge_components",
    indices = [
        Index("noteId"),
        Index("capabilityKey"),
        Index("due"),
        Index(value = ["kind", "due"])
    ]
)
data class KnowledgeComponent(
    @PrimaryKey val key: String,
    val kind: String,
    val capabilityKey: String,
    val band: String,
    val unit: Int,
    val noteId: Long? = null,
    val conceptId: String? = null,
    val due: Long = 0L,
    val stabilityDays: Double = 0.0,
    val difficulty: Double = 5.0,
    val confidence: Double = 0.0,
    val reps: Int = 0,
    val lapses: Int = 0,
    val lastEvidenceAt: Long? = null,
    val retired: Boolean = false
)

/** One observation with its cueing explicitly represented. */
@Entity(
    tableName = "capability_evidence",
    foreignKeys = [
        ForeignKey(
            entity = KnowledgeComponent::class,
            parentColumns = ["key"],
            childColumns = ["componentKey"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("componentKey"),
        Index("episodeId"),
        Index("observedAt"),
        Index(value = ["episodeId", "taskId", "componentKey"], unique = true)
    ]
)
data class CapabilityEvidence(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val componentKey: String,
    val episodeId: String,
    /** Stable within an episode, making retry/resume writes idempotent. */
    val taskId: String,
    val observedAt: Long,
    val taskKind: String,
    val outcome: String,
    /** 0 = unsupported; 1 = light cue; 2 = tiles/word bank; 3 = shown answer. */
    val supportLevel: Int,
    /** Credibility of this observation for durable capability, in [0, 1]. */
    val evidenceWeight: Double,
    val responseMs: Long? = null,
    val novelContext: Boolean = false,
    val source: String = "EPISODE"
)

/** Progress is expressed as communicative capability, not cards completed. */
@Entity(tableName = "capability_progress", indices = [Index("band"), Index("unit")])
data class CapabilityProgress(
    @PrimaryKey val capabilityKey: String,
    val band: String,
    val unit: Int,
    val canDo: String,
    val completedEpisodes: Int = 0,
    val successfulTransferProbes: Int = 0,
    val attemptedTransferProbes: Int = 0,
    val lastTransferScore: Double? = null,
    val lastEpisodeAt: Long? = null,
    val certifiedAt: Long? = null
)

/** Route advancement is earned by repeated transfer, not mere screen completion. */
internal fun CapabilityProgress.isRouteReady(requiredCompletions: Int = 3): Boolean =
    completedEpisodes >= requiredCompletions &&
        attemptedTransferProbes >= 2 &&
        successfulTransferProbes.toDouble() / attemptedTransferProbes >= 2.0 / 3.0

@Dao
interface CommunicativeLearningDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertComponent(component: KnowledgeComponent)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertComponents(components: List<KnowledgeComponent>)

    @Update
    suspend fun updateComponent(component: KnowledgeComponent)

    @Query("DELETE FROM knowledge_components WHERE `key` = :key")
    suspend fun deleteComponent(key: String)

    @Query("SELECT * FROM knowledge_components WHERE `key` = :key")
    suspend fun component(key: String): KnowledgeComponent?

    @Query("SELECT * FROM knowledge_components WHERE capabilityKey = :capabilityKey AND retired = 0 ORDER BY due, `key`")
    suspend fun componentsForCapability(capabilityKey: String): List<KnowledgeComponent>

    @Query("SELECT * FROM knowledge_components WHERE capabilityKey = :capabilityKey ORDER BY due, `key`")
    suspend fun allComponentsForCapability(capabilityKey: String): List<KnowledgeComponent>

    @Query("SELECT * FROM knowledge_components WHERE retired = 0 AND due <= :now ORDER BY due, difficulty DESC LIMIT :limit")
    suspend fun dueComponents(now: Long, limit: Int = 40): List<KnowledgeComponent>

    @Query("SELECT COUNT(*) FROM knowledge_components")
    suspend fun componentCount(): Int

    @Query("SELECT * FROM knowledge_components ORDER BY `key`")
    suspend fun allComponents(): List<KnowledgeComponent>

    @Query("UPDATE knowledge_components SET retired = 1 WHERE noteId = :noteId")
    suspend fun retireComponentsForNote(noteId: Long): Int

    @Query("""
        UPDATE knowledge_components
        SET retired = 0,
            due = CASE WHEN due > :now THEN :now ELSE due END
        WHERE noteId = :noteId
    """)
    suspend fun reactivateComponentsForNote(noteId: Long, now: Long): Int

    @Query("""
        UPDATE knowledge_components
        SET retired = 1
        WHERE noteId IN (SELECT id FROM notes WHERE status IN ('KNOWN', 'IGNORED'))
    """)
    suspend fun retireComponentsForKnownNotes(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEvidence(evidence: CapabilityEvidence): Long

    @Query("DELETE FROM capability_evidence WHERE episodeId = :episodeId AND taskId = :taskId AND componentKey = :componentKey")
    suspend fun deleteEvidenceForTaskComponent(episodeId: String, taskId: String, componentKey: String)

    @Query("SELECT * FROM capability_evidence WHERE episodeId = :episodeId AND taskId = :taskId AND componentKey = :componentKey LIMIT 1")
    suspend fun evidenceForTaskComponent(episodeId: String, taskId: String, componentKey: String): CapabilityEvidence?

    @Query("SELECT * FROM capability_evidence WHERE componentKey = :componentKey ORDER BY observedAt DESC LIMIT :limit")
    suspend fun recentEvidence(componentKey: String, limit: Int = 20): List<CapabilityEvidence>

    @Query("""
        SELECT e.* FROM capability_evidence e
        INNER JOIN knowledge_components c ON c.`key` = e.componentKey
        WHERE c.capabilityKey = :capabilityKey
        ORDER BY e.observedAt DESC, e.id DESC
        LIMIT :limit
    """)
    suspend fun recentEvidenceForCapability(capabilityKey: String, limit: Int = 60): List<CapabilityEvidence>

    @Query("SELECT * FROM capability_evidence ORDER BY observedAt, id")
    suspend fun allEvidence(): List<CapabilityEvidence>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProgress(progress: CapabilityProgress)

    @Query("SELECT * FROM capability_progress WHERE capabilityKey = :capabilityKey")
    suspend fun progress(capabilityKey: String): CapabilityProgress?

    @Query("SELECT * FROM capability_progress ORDER BY CASE band WHEN 'A1' THEN 0 WHEN 'A2' THEN 1 WHEN 'B1' THEN 2 WHEN 'B2' THEN 3 WHEN 'C1' THEN 4 WHEN 'C2' THEN 5 ELSE 99 END, unit")
    suspend fun allProgress(): List<CapabilityProgress>
}

object ComponentKeys {
    fun capability(band: String, unit: Int): String = "${band.uppercase()}:$unit"
    fun meaning(noteId: Long): String = "MEANING:$noteId"
    fun form(noteId: Long): String = "FORM:$noteId"
    fun sound(noteId: Long): String = "SOUND:$noteId"
    fun construction(conceptId: String, band: String, unit: Int): String =
        "CONSTRUCTION:${band.uppercase()}:$unit:$conceptId"
}
