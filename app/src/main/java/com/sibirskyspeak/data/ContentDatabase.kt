package com.sibirskyspeak.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

@Entity(tableName = "sentence")
data class ContentSentence(
    @PrimaryKey val id: Long,
    @ColumnInfo(name = "ru_stressed") val ruStressed: String,
    @ColumnInfo(name = "ru_plain") val ruPlain: String,
    val en: String,
    @ColumnInfo(name = "n_tokens") val tokenCount: Int,
    val audio: Boolean,
    val rating: Double
)

@Entity(tableName = "lemma_index", primaryKeys = ["lemma", "sentence_id", "target_pos"], indices = [Index("lemma"), Index("sentence_id")])
data class LemmaIndex(
    val lemma: String,
    @ColumnInfo(name = "sentence_id") val sentenceId: Long,
    @ColumnInfo(name = "target_pos") val targetPos: Int,
    val pos: String
)

data class SentenceCandidate(
    val id: Long,
    @ColumnInfo(name = "ru_stressed") val ruStressed: String,
    @ColumnInfo(name = "ru_plain") val ruPlain: String,
    val en: String,
    @ColumnInfo(name = "n_tokens") val tokenCount: Int,
    val audio: Boolean,
    val rating: Double,
    @ColumnInfo(name = "target_pos") val targetPos: Int,
    val pos: String
)

@Entity(tableName = "collocation", primaryKeys = ["lemma", "chunk"], indices = [Index("lemma")])
data class ContentCollocation(val lemma: String, val chunk: String, val freq: Int)

@Entity(tableName = "root_family", primaryKeys = ["root", "lemma"], indices = [Index("lemma")])
data class ContentRootFamily(val root: String, val lemma: String, val prefix: String, val suffix: String)

@Entity(tableName = "emoji_map")
data class ContentEmoji(@PrimaryKey val lemma: String, val emoji: String)

@Entity(tableName = "semantic_neighbor", primaryKeys = ["lemma", "neighbor"], indices = [Index("lemma")])
data class SemanticNeighbor(val lemma: String, val neighbor: String, val similarity: Double)

@Entity(tableName = "meta")
data class ContentMeta(@PrimaryKey val key: String, val value: String)

@Entity(
    tableName = "paradigm",
    primaryKeys = ["lemma", "feats", "surface"],
    indices = [Index("lemma"), Index("surface")]
)
data class ParadigmForm(
    val lemma: String,
    val pos: String,
    val feats: String,
    val surface: String,
    val stressed: String
)

@Entity(
    tableName = "analysis",
    primaryKeys = ["surface_norm", "lemma", "feats"],
    indices = [Index("surface_norm"), Index("lemma")]
)
data class MorphAnalysisRow(
    @ColumnInfo(name = "surface_norm") val surfaceNorm: String,
    val lemma: String,
    val pos: String,
    val feats: String
)

@Entity(tableName = "sentence_bank", indices = [Index("unit_min"), Index("band")])
data class SentenceBankRow(
    @PrimaryKey @ColumnInfo(name = "sent_id") val sentenceId: Long,
    @ColumnInfo(name = "unit_min") val unitMin: Int,
    val band: String,
    @ColumnInfo(name = "token_count") val tokenCount: Int,
    @ColumnInfo(name = "grammar_feats") val grammarFeats: String,
    val source: String
)

data class BankSentence(
    @ColumnInfo(name = "sent_id") val sentenceId: Long,
    @ColumnInfo(name = "ru_stressed") val ruStressed: String,
    @ColumnInfo(name = "ru_plain") val ruPlain: String,
    val en: String,
    @ColumnInfo(name = "unit_min") val unitMin: Int,
    val band: String,
    @ColumnInfo(name = "token_count") val tokenCount: Int,
    @ColumnInfo(name = "grammar_feats") val grammarFeats: String
)

/**
 * A curated, reusable clause template (P4.1). Slots and template strings are
 * authored/validated at build time in tools/preprocess/frames.json; the
 * runtime only fills them in (see generation/FrameRealizer.kt).
 */
@Entity(tableName = "frame", indices = [Index("concept"), Index("domain")])
data class ContentFrame(
    @PrimaryKey val id: String,
    val concept: String,
    val band: String,
    @ColumnInfo(name = "slots_json") val slotsJson: String,
    @ColumnInfo(name = "ru_frame") val ruFrame: String,
    @ColumnInfo(name = "en_frame") val enFrame: String,
    val domain: String = "general",
    val register: String = "neutral",
    val minStage: Int = 1,
    val tier: Int = 1,
    val requiresAudioPack: Boolean = false,
    val contrastConcept: String? = null
)

/**
 * A short scripted conversation (P6.2), authored per curriculum function
 * ("introducing yourself", ...) in tools/preprocess/dialogues.json.
 */
@Entity(tableName = "dialogue")
data class ContentDialogue(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "unit_min") val unitMin: Int,
    val function: String,
    val title: String
)

/**
 * One turn of a dialogue. [nextIdsJson] lists the possible following node ids
 * (usually one for an NPC turn, sometimes several branching choices after a
 * learner turn). [acceptableJson] is null for NPC turns and a JSON array of
 * acceptable learner responses for learner turns (near-miss graded, see
 * generation/DialogueEngine.kt).
 */
@Entity(tableName = "dialogue_node", indices = [Index("dialogueId")])
data class ContentDialogueNode(
    @PrimaryKey val id: String,
    val dialogueId: String,
    val speaker: String,
    val ru: String,
    val en: String,
    @ColumnInfo(name = "acceptable_json") val acceptableJson: String?,
    @ColumnInfo(name = "next_ids_json") val nextIdsJson: String
)

@Dao
interface ContentDao {
    @Query("""
        SELECT s.*, li.target_pos, li.pos FROM lemma_index li
        JOIN sentence s ON s.id = li.sentence_id
        WHERE li.lemma = :lemma
        ORDER BY s.rating DESC, s.audio DESC, s.n_tokens ASC
        LIMIT :limit
    """)
    suspend fun candidatesForLemma(lemma: String, limit: Int = 24): List<SentenceCandidate>

    @Query("SELECT * FROM collocation WHERE lemma = :lemma ORDER BY freq DESC LIMIT :limit")
    suspend fun chunksForLemma(lemma: String, limit: Int = 8): List<ContentCollocation>

    /** Real corpus sentences containing an exact collocation phrase (P4.4 L1: the
     * chunk's carrier and English gloss come from an authentic sentence, not
     * authored content — "index and compose, don't hand-write"). */
    @Query("SELECT * FROM sentence WHERE ru_plain LIKE '%' || :chunk || '%' ORDER BY rating DESC, n_tokens ASC LIMIT :limit")
    suspend fun sentencesContaining(chunk: String, limit: Int = 3): List<ContentSentence>

    @Query("SELECT * FROM root_family WHERE root = (SELECT root FROM root_family WHERE lemma = :lemma LIMIT 1) ORDER BY lemma LIMIT :limit")
    suspend fun familyForLemma(lemma: String, limit: Int = 16): List<ContentRootFamily>

    /** Legacy heuristic rows built by stripping ambiguous one-letter prefixes.
     * They are ignored for transfer and removed from persisted learner mastery. */
    @Query("SELECT DISTINCT root FROM root_family WHERE LENGTH(prefix) = 1")
    suspend fun singleLetterPrefixRoots(): List<String>

    @Query("SELECT emoji FROM emoji_map WHERE lemma = :lemma")
    suspend fun emojiForLemma(lemma: String): String?

    @Query("SELECT * FROM semantic_neighbor WHERE lemma = :lemma ORDER BY similarity DESC LIMIT :limit")
    suspend fun neighborsForLemma(lemma: String, limit: Int = 8): List<SemanticNeighbor>

    @Query("SELECT value FROM meta WHERE `key` = :key")
    suspend fun metadata(key: String): String?

    @Query("SELECT * FROM paradigm WHERE lemma = :lemma AND feats = :feats ORDER BY surface LIMIT 1")
    fun inflection(lemma: String, feats: String): ParadigmForm?

    @Query("SELECT * FROM paradigm WHERE lemma = :lemma ORDER BY feats, surface")
    fun paradigm(lemma: String): List<ParadigmForm>

    @Query("SELECT * FROM analysis WHERE surface_norm = :surfaceNorm ORDER BY lemma, feats")
    fun analyses(surfaceNorm: String): List<MorphAnalysisRow>

    @Query("""
        SELECT b.sent_id, s.ru_stressed, s.ru_plain, s.en, b.unit_min, b.band, b.token_count, b.grammar_feats
        FROM sentence_bank b JOIN sentence s ON s.id = b.sent_id
        WHERE b.unit_min <= :unitMax
          AND (CASE b.band
                WHEN 'A1' THEN 0 WHEN 'A2' THEN 1 WHEN 'B1' THEN 2
                WHEN 'B2' THEN 3 WHEN 'C1' THEN 4 WHEN 'C2' THEN 5
                ELSE 99 END) <=
              (CASE :bandMax
                WHEN 'A1' THEN 0 WHEN 'A2' THEN 1 WHEN 'B1' THEN 2
                WHEN 'B2' THEN 3 WHEN 'C1' THEN 4 WHEN 'C2' THEN 5
                ELSE 5 END)
          AND (:requiredFeat IS NULL OR b.grammar_feats LIKE '%' || :requiredFeat || '%')
          AND (:requiredLemma IS NULL OR EXISTS (SELECT 1 FROM lemma_index li WHERE li.sentence_id=b.sent_id AND li.lemma=:requiredLemma))
        ORDER BY b.unit_min DESC, s.rating DESC LIMIT :limit
    """)
    suspend fun sentencesFor(unitMax: Int, bandMax: String = "C2", requiredLemma: String? = null, requiredFeat: String? = null, limit: Int = 20): List<BankSentence>

    @Query("SELECT * FROM frame WHERE concept = :conceptId ORDER BY id")
    suspend fun framesForConcept(conceptId: String): List<ContentFrame>

    @Query("SELECT * FROM frame ORDER BY id")
    suspend fun allFrames(): List<ContentFrame>

    @Query("SELECT * FROM dialogue WHERE unit_min <= :unitMax ORDER BY unit_min")
    suspend fun dialoguesFor(unitMax: Int): List<ContentDialogue>

    @Query("SELECT * FROM dialogue_node WHERE dialogueId = :dialogueId")
    suspend fun nodesForDialogue(dialogueId: String): List<ContentDialogueNode>
}

@Database(
    entities = [ContentSentence::class, LemmaIndex::class, ContentCollocation::class,
        ContentRootFamily::class, ContentEmoji::class, SemanticNeighbor::class, ContentMeta::class,
        ParadigmForm::class, MorphAnalysisRow::class, SentenceBankRow::class, ContentFrame::class,
        ContentDialogue::class, ContentDialogueNode::class],
    // Content is immutable learner-independent data. Bump this whenever the
    // bundled corpus is regenerated so createFromAsset + destructive fallback
    // replaces stale installed content.db files without touching AppDatabase.
    version = 7,
    exportSchema = true
)
abstract class ContentDatabase : RoomDatabase() {
    abstract fun contentDao(): ContentDao

    companion object {
        @Volatile private var instance: ContentDatabase? = null

        fun get(context: Context): ContentDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, ContentDatabase::class.java, "content.db")
                .createFromAsset("tatoeba.db")
                .fallbackToDestructiveMigration()
                .build()
                .also { instance = it }
        }
    }
}
