package com.sibirskyspeak.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "notes")
data class Note(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val russian: String,
    val translation: String,
    val partOfSpeech: String,
    val lemma: String,
    val audioPath: String? = null,
    val exampleSentence: String? = null,
    val exampleTranslation: String? = null,
    val aspectPartner: Long? = null,
    val aspect: String? = null,
    val aktionsart: String? = null,
    val aktionsartConfidence: String? = null,
    val declensionJson: String? = null,
    val gender: String? = null,
    val generalFreqRank: Int? = null,
    val domainFreqRank: Int? = null,
    val encounterCount: Int = 0,
    val tags: String = ""
)
