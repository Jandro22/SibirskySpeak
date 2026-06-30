package com.sibirskyspeak.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** Learner-specific cache over the immutable sentence corpus. Authored examples on
 * [Note] always win; this row only fills an example gap. */
@Entity(
    tableName = "mined_examples",
    primaryKeys = ["noteId"],
    foreignKeys = [
        ForeignKey(
            entity = Note::class,
            parentColumns = ["id"],
            childColumns = ["noteId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("sentenceId"), Index("createdAt")]
)
data class MinedExample(
    val noteId: Long,
    val ru: String,
    val en: String,
    val sentenceId: Long,
    val anchoredGloss: String,
    val score: Double,
    val source: String = SOURCE_TATOEBA,
    val knownAtMine: Int,
    val targetPos: Int = -1,
    val unknownCount: Int = 1,
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val SOURCE_TATOEBA = "tatoeba"
        const val SOURCE_READER = "reader"
        const val SOURCE_SYNTHETIC = "synthetic"
    }
}

/** Compact persistent learner models used by the optimizer. */
@Entity(tableName = "item_difficulty")
data class ItemDifficulty(
    @androidx.room.PrimaryKey val cardId: Long,
    val elo: Double = 25.0,
    val sigma: Double = 8.3333,
    val observations: Int = 0,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "concept_mastery")
data class ConceptMastery(
    @androidx.room.PrimaryKey val concept: String,
    val probability: Double = 0.20,
    val observations: Int = 0,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "optimizer_parameters")
data class OptimizerParameter(
    @androidx.room.PrimaryKey val key: String,
    val value: Double,
    val observations: Int = 0,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "skill_rating")
data class SkillRating(
    @androidx.room.PrimaryKey val skill: String,
    val muGlobalShare: Double = 0.6,
    val mu: Double = 0.0,
    val sigma: Double = 8.3333,
    val observations: Int = 0,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "capacity_state")
data class CapacityState(
    @androidx.room.PrimaryKey val id: Int = 0,
    val mu: Double = 12.0,
    val sigma: Double = 8.0,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "willingness_state")
data class WillingnessState(
    @androidx.room.PrimaryKey val id: Int = 0,
    val habit: Double = 0.0,
    val coeffsJson: String = "",
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "rival_state")
data class RivalState(
    @androidx.room.PrimaryKey val id: Int = 0,
    val mu: Double = 25.0,
    val sigma: Double = 8.3333,
    val handicap: Double = 0.0,
    val winStreak: Int = 0,
    val persona: String = "rival",
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "ghost_snapshot")
data class GhostSnapshot(
    @androidx.room.PrimaryKey val takenAt: Long,
    val muGlobal: Double,
    val sigma: Double
)

@Entity(tableName = "match_history")
data class MatchHistory(
    @androidx.room.PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long,
    val opponent: String,
    val perfYou: Double,
    val perfOpp: Double,
    val outcome: String,
    val ratingBefore: Double,
    val ratingAfter: Double
)

@Entity(tableName = "pace_log")
data class PaceLog(
    @androidx.room.PrimaryKey val at: Long,
    val T: Double,
    val N: Int,
    val rho: Double,
    val debtRatio: Double,
    val pReturn: Double,
    val doctrine: String,
    val modeChosen: String
)

@Entity(tableName = "bandit_pending")
data class BanditPending(
    @androidx.room.PrimaryKey val showAt: Long,
    val itemId: Long,
    val action: String,
    val contextJson: String,
    val p0: Double
)

@Entity(tableName = "bandit_arm_state")
data class BanditArmState(
    @androidx.room.PrimaryKey val action: String,
    val rewardJson: String,
    val precisionJson: String,
    val pulls: Int = 0,
    val updatedAt: Long = System.currentTimeMillis()
)
