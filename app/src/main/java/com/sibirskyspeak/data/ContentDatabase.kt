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

    @Query("SELECT * FROM root_family WHERE root = (SELECT root FROM root_family WHERE lemma = :lemma LIMIT 1) ORDER BY lemma LIMIT :limit")
    suspend fun familyForLemma(lemma: String, limit: Int = 16): List<ContentRootFamily>

    @Query("SELECT emoji FROM emoji_map WHERE lemma = :lemma")
    suspend fun emojiForLemma(lemma: String): String?

    @Query("SELECT * FROM semantic_neighbor WHERE lemma = :lemma ORDER BY similarity DESC LIMIT :limit")
    suspend fun neighborsForLemma(lemma: String, limit: Int = 8): List<SemanticNeighbor>

    @Query("SELECT value FROM meta WHERE `key` = :key")
    suspend fun metadata(key: String): String?
}

@Database(
    entities = [ContentSentence::class, LemmaIndex::class, ContentCollocation::class,
        ContentRootFamily::class, ContentEmoji::class, SemanticNeighbor::class, ContentMeta::class],
    version = 1,
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
