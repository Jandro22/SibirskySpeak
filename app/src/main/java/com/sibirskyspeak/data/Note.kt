package com.sibirskyspeak.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "notes",
    indices = [
        Index("lemma"),
        Index("status"),
        Index("encounterCount"),
        Index("domainFreqRank"),
        Index("generalFreqRank"),
        Index("tier"),
        Index("unit")
    ]
)
data class Note(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val russian: String,
    val translation: String,
    val partOfSpeech: String,
    val lemma: String,
    val audioPath: String? = null,
    val exampleSentence: String? = null,
    val exampleTranslation: String? = null,
    val exampleSentence2: String? = null,
    val exampleTranslation2: String? = null,
    val exampleSentence3: String? = null,
    val exampleTranslation3: String? = null,
    val aspectPartner: Long? = null,
    val aspect: String? = null,
    val aktionsart: String? = null,
    val aktionsartConfidence: String? = null,
    val declensionJson: String? = null,
    val gender: String? = null,
    val generalFreqRank: Int? = null,
    val domainFreqRank: Int? = null,
    val encounterCount: Int = 0,
    val status: WordStatus = WordStatus.NEW,
    val tags: String = "",
    // Curriculum scaffold. [tier] orders the whole course: 0 = A1 starter (concrete,
    // high-frequency, readable), 1 = general reading matrix, 2 = formal/political
    // domain. New material is introduced tier by tier, so a beginner meets everyday
    // words before Kremlin vocabulary. [unit] is the A1 lesson index within tier 0
    // (null for uncurated tiers). [conceptId] tags lesson notes (pos = "lesson")
    // with the grammar concept they teach (e.g. "ACC", "GENDER"); null otherwise.
    val tier: Int = 1,
    val unit: Int? = null,
    val conceptId: String? = null,
    // CEFR level this curated note belongs to ("A1".."C2"), for the curriculum
    // course (tier 0). Null for the general/domain tiers. Drives the level label in
    // the UI; sequencing is by [unit], which is monotonic across levels.
    val cefrLevel: String? = null,
    val mnemonic: String? = null,
    // A second, unrelated dictionary sense for genuinely polysemous words (e.g.
    // "мир" = world/peace, not near-synonyms) — distinct from exampleSentence2/3,
    // which rotate through *additional context for the same sense*. Null for the
    // vast majority of notes; only populated where a second sense is curated and
    // worth its own retrieval practice. See ReviewPrompt.secondSenseExposure.
    val secondSense: String? = null,
    val secondSenseExample: String? = null,
    val secondSenseExampleTranslation: String? = null,
    // Set only on chunk notes (partOfSpeech = "chunk", generated at runtime from the
    // on-device collocation table — see LearningRepository.syncMissingChunkCards, P4.4
    // L1). Points back at the vocabulary note this collocation was mined for, so the
    // chunk's CHUNK card can gate on that parent's recognition maturity rather than
    // its own (a fresh chunk note has no review history of its own to judge).
    val chunkParentNoteId: Long? = null
)
