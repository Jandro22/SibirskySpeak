package com.sibirskyspeak.data

import java.util.Locale

/**
 * Scores the shipped content against the design doc's minimum-viable-course
 * thresholds — pure: only reads its [Note]/[ReaderRecommendation] arguments plus
 * the caller-supplied coverage threshold, no DAO access. Extracted from
 * LearningRepository alongside [CardFactory] as part of splitting that god
 * object into cooperating pieces (see CLAUDE.md's architecture note).
 */
object ImportQualityReporter {
    private const val DESIGN_DOC_MIN_NOMINAL_ROWS = 200
    private const val DESIGN_DOC_MIN_VERB_ROWS = 100

    fun report(notes: List<Note>, recommendations: List<ReaderRecommendation>, authenticReadyCoverage: Double): ImportQualityReport {
        val counts = NoteQualityCounts(
            totalNotes = notes.size,
            readyNominalRows = notes.count { it.isNominalReady() },
            aspectReadyVerbRows = notes.count { it.isAspectReadyVerb() },
            verifiedAktionsartVerbRows = notes.count { it.isAspectReadyVerb() && it.hasVerifiedAktionsart() },
            domainRankedRows = notes.count { it.domainFreqRank != null },
            exampleRows = notes.count { CardFactory.hasReadableExample(it) }
        )
        return report(counts, recommendations, authenticReadyCoverage)
    }

    fun report(counts: NoteQualityCounts, recommendations: List<ReaderRecommendation>, authenticReadyCoverage: Double): ImportQualityReport {
        val readyNominals = counts.readyNominalRows
        val aspectReadyVerbs = counts.aspectReadyVerbRows
        val verifiedAktionsartVerbs = counts.verifiedAktionsartVerbRows
        val targetReady = recommendations.count { it.text.source.startsWith("target:", ignoreCase = true) && it.coverage >= authenticReadyCoverage }
        val warnings = buildList {
            if (readyNominals < DESIGN_DOC_MIN_NOMINAL_ROWS) add("Need $DESIGN_DOC_MIN_NOMINAL_ROWS noun/adjective rows with declension, gender, domain rank, and example.")
            if (aspectReadyVerbs < DESIGN_DOC_MIN_VERB_ROWS) add("Need $DESIGN_DOC_MIN_VERB_ROWS verb rows with aspect partner, Aktionsart, domain rank, and example.")
            if (verifiedAktionsartVerbs < DESIGN_DOC_MIN_VERB_ROWS) add("Need $DESIGN_DOC_MIN_VERB_ROWS aspect-ready verbs with high/manual Aktionsart verification.")
            if (targetReady == 0) add("Need at least one target-source reader text at 90%+ coverage.")
        }
        return ImportQualityReport(
            totalNotes = counts.totalNotes,
            readyNominalRows = readyNominals,
            aspectReadyVerbRows = aspectReadyVerbs,
            verifiedAktionsartVerbRows = verifiedAktionsartVerbs,
            domainRankedRows = counts.domainRankedRows,
            exampleRows = counts.exampleRows,
            targetTextsAtOrAbove90 = targetReady,
            minNominalRows = DESIGN_DOC_MIN_NOMINAL_ROWS,
            minVerbRows = DESIGN_DOC_MIN_VERB_ROWS,
            meetsDesignDocMinimum = warnings.isEmpty(),
            warnings = warnings
        )
    }

    private fun Note.isNominalReady(): Boolean =
        partOfSpeech.lowercase(Locale.ROOT) in setOf("noun", "adjective") &&
            !declensionJson.isNullOrBlank() &&
            !gender.isNullOrBlank() &&
            domainFreqRank != null &&
            CardFactory.hasReadableExample(this)

    private fun Note.isAspectReadyVerb(): Boolean =
        partOfSpeech.lowercase(Locale.ROOT) == "verb" &&
            aspectPartner != null &&
            !aspect.isNullOrBlank() &&
            !aktionsart.isNullOrBlank() &&
            domainFreqRank != null &&
            CardFactory.hasReadableExample(this)

    private fun Note.hasVerifiedAktionsart(): Boolean =
        aktionsartConfidence?.lowercase(Locale.ROOT) in setOf("high", "manual", "verified")
}
