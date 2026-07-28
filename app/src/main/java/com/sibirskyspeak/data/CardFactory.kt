package com.sibirskyspeak.data

import org.json.JSONObject
import java.util.Locale

/**
 * Decides which [Card] rows a [Note] needs — the curriculum/pedagogy rule engine
 * that used to live as private members on [LearningRepository]. Pure: every
 * function here operates only on its [Note] argument (plus static reference data
 * like [GrammarConcepts]/[ConceptDrills]/[RussianForms]), with no DAO or I/O
 * dependency, so it's usable — and independently testable — without a database.
 * Extracted verbatim from LearningRepository as the first step of splitting that
 * god object into cooperating pieces (see CLAUDE.md's architecture note).
 */
object CardFactory {
    // Kept in sync manually with LearningRepository.CEFR_LEVELS (this object is
    // deliberately DAO/dependency-free, so it can't reference that private constant).
    private val CEFR_ORDER = listOf("A1", "A2", "B1", "B2", "C1", "C2")
    private fun cefrOrdinal(level: String?): Int = CEFR_ORDER.indexOf(level).let { if (it < 0) 0 else it }

    fun cardsFor(note: Note): List<Card> = buildList {
        // A lesson note (pos = "lesson") teaches one grammar concept and produces a
        // single LESSON card — no vocab/drill cards. Seeing it is what unlocks the
        // concept's drills (concept gating).
        if (note.partOfSpeech.equals("lesson", ignoreCase = true)) {
            add(Card(noteId = note.id, cardType = CardType.LESSON, queue = Queue.GRAMMAR, due = 0L, gramConcept = note.conceptId))
            ConceptDrills.forConcept(note.conceptId).forEach { drill ->
                add(
                    Card(
                        noteId = note.id,
                        cardType = CardType.CONCEPT_DRILL,
                        queue = Queue.GRAMMAR,
                        due = 0L,
                        gramContextCue = drill.id,
                        gramConcept = drill.conceptId
                    )
                )
            }
            return@buildList
        }
        // A "chunk" note (minted by syncMissingChunkCards from a raw collocation, e.g.
        // "на диване") is deliberately translation="" with no example/declension data —
        // it exists only to drive its own CardType.CHUNK production card, which is
        // inserted directly by that sync, not through here. Every other branch below
        // assumes a real vocabulary note (RU_TO_MEANING needs an actual English
        // translation to expect, etc.), so falling through for a chunk note used to
        // mint unanswerable RU_TO_MEANING/MEANING_TO_RU/AUDIO_TO_RU/SPEAK cards with no
        // valid expected answer — softlocking the wrong-answer correction UI, which has
        // nothing real to rebuild.
        if (note.partOfSpeech.equals("chunk", ignoreCase = true)) return@buildList
        // The frequency "reading-matrix" layer (tag contains "matrix") gets rich vocab
        // and comprehension study cards AND keeps its declension tables — but ONLY to
        // feed the reader coverage index, never to generate morphology drills. Its
        // tables are rule-engine output (decline_noun(animate=False), oblique cases
        // unvalidated against the deck), so case/gender/agreement/aspect drills built
        // from them would teach wrong forms. Morphology drilling stays restricted to
        // the verified curated course (tier 0) and the domain corpus. We key on the
        // "matrix" tag, not tier, because the default tier (1) also covers imported /
        // test notes that legitimately need grammar drills.
        val isReadingMatrix = note.tags.contains("matrix")
        // A "recognition_only" note (e.g. a textbook word recovered in an oblique
        // form — "университе́та = university (gen.)") is honest for *recognition* and
        // reader coverage, but reverse-production would wrongly ask the learner to
        // type that exact inflected form. Such notes get recognition + listening only.
        val recognitionOnly = note.tags.contains("recognition_only")
        add(Card(noteId = note.id, cardType = CardType.RU_TO_MEANING, queue = Queue.VOCAB))
        if (!isAmbiguousFunctionNote(note) && !recognitionOnly) {
            add(Card(noteId = note.id, cardType = CardType.MEANING_TO_RU, queue = Queue.VOCAB))
        }
        // Cloze blanks a word inside the example sentence — only useful if the learner
        // can read that sentence, i.e. it ships with a real sentence-level translation.
        if (hasReadableExample(note) && !isAmbiguousFunctionNote(note) && !recognitionOnly) {
            add(Card(noteId = note.id, cardType = CardType.CLOZE, queue = Queue.VOCAB))
            // SENTENCE_BUILD and DICTATION make the learner handle a whole sentence.
            // Keep them to the hand-authored spine with SHORT, controlled sentences —
            // not the promoted/reading-matrix layer's arbitrary (often long, hard)
            // deck sentences, which would be a brutal typing grind.
            if (!isReadingMatrix && note.hasShortExample()) {
                add(Card(noteId = note.id, cardType = CardType.SENTENCE_BUILD, queue = Queue.GRAMMAR, gramConcept = note.conceptId))
                add(Card(noteId = note.id, cardType = CardType.DICTATION, queue = Queue.VOCAB))
            }
        }
        // Word dictation is useful phonological decoding, but making it a universal
        // facet for a 40k-word reading lexicon creates enormous low-semantic review
        // debt. Keep it for the active course, recognition-only forms, and the most
        // frequent reading vocabulary; recognition cards still auto-play audio.
        val frequencyRank = listOfNotNull(note.domainFreqRank, note.generalFreqRank).minOrNull()
        if (note.tier == 0 || recognitionOnly || (frequencyRank != null && frequencyRank <= 3_000)) {
            add(Card(noteId = note.id, cardType = CardType.AUDIO_TO_RU, queue = Queue.VOCAB))
        }
        // Speaking: the learner says the word aloud and on-device speech recognition
        // checks it — the only card that trains production/pronunciation. Restricted to
        // the curated course (tier 0) so it stays focused on active study vocabulary.
        if (note.tier == 0 && !recognitionOnly) {
            add(Card(noteId = note.id, cardType = CardType.SPEAK, queue = Queue.VOCAB))
        }
        // STRESS_MARK was retired in the database migration. Keep the old enum and
        // prompt builder importable for backups, but never mint new stress cards.
        if (!isReadingMatrix) caseCards(note).forEach(::add)
        if (!isReadingMatrix && !isExistentialHomograph(note)) verbFormCards(note).forEach(::add)
        if (!isReadingMatrix) adjectiveAgreementCards(note).forEach(::add)
        if (!isReadingMatrix) genderCard(note)?.let(::add)
        // ASPECT_SELECT requires a verified Aktionsart (design F8): the drill's
        // whole point is reasoning from inherent temporal structure, so a verb
        // without Aktionsart never produces a half-formed aspect card.
        val isAspectDrillable = !isReadingMatrix &&
            note.aspect != "BI" &&
            !note.tags.contains("no_aspect_pair") &&
            !note.aktionsart.isNullOrBlank() &&
            note.aspectPartner != null &&
            !note.aspect.isNullOrBlank()
        if (isAspectDrillable) {
            ASPECT_CONTEXT_CUES.forEach { cue ->
                add(Card(noteId = note.id, cardType = CardType.ASPECT_SELECT, queue = Queue.GRAMMAR, due = 0L, gramContextCue = cue, gramConcept = GrammarConcepts.ASPECT.id))
            }
        }
    }

    /** Also used outside card generation — e.g. to suspend production drills for
     * function words whose translation is genuinely multi-sense. */
    fun isAmbiguousFunctionNote(note: Note): Boolean {
        val pos = note.partOfSpeech.lowercase()
        val functionWord = pos in setOf("preposition", "conjunction", "particle", "pronoun", "conj.", "prep.")
        return functionWord && note.translation.split(',', ';', '/').count { it.isNotBlank() } > 1
    }

    /** `есть = there is/are` is not the infinitive `есть = to eat`. */
    fun isExistentialHomograph(note: Note): Boolean =
        RussianForms.normalize(note.lemma) == RussianForms.normalize("есть") &&
            note.translation.trim().lowercase(Locale.ENGLISH).startsWith("there is")

    /** Also used outside card generation — e.g. to decide whether a note qualifies
     * for a retroactively-added CLOZE card once it gains a usable example. */
    fun hasReadableExample(note: Note): Boolean {
        val sentence = note.exampleSentence?.trim().orEmpty()
        val gloss = note.exampleTranslation?.trim().orEmpty()
        if (sentence.isBlank() || gloss.isBlank()) return false
        if (gloss.equals(note.translation.trim(), ignoreCase = true)) return false
        // A real translation of a sentence has multiple words.
        return gloss.split(Regex("\\s+")).size >= 2
    }

    /** True when the note's example is a short (2-7 word) sentence, suitable for
     *  sentence-building / dictation without becoming a typing grind. */
    private fun Note.hasShortExample(): Boolean {
        val s = exampleSentence ?: return false
        val words = Regex("""\p{IsCyrillic}+""").findAll(s).count()
        return words in 2..7
    }

    // Adjective–noun agreement: produce the feminine, neuter, and plural nominative
    // forms (the masculine is the citation form). Russian agreement is one of the
    // highest-frequency grammar skills and the forms already ship in the data, but
    // they were previously only used for reader matching, never drilled.
    private fun adjectiveAgreementCards(note: Note): List<Card> {
        if (!note.partOfSpeech.equals("adjective", ignoreCase = true)) return emptyList()
        val json = note.declensionJson ?: return emptyList()
        val table = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        val source = if (table.has("cases")) table.getJSONObject("cases") else table
        val masc = RussianForms.normalize(source.optString("NOM_SG").ifBlank { note.lemma })
        return listOf("FEM" to "FEM_NOM", "NEUT" to "NEUT_NOM", "PL" to "PL_NOM").mapNotNull { (cue, key) ->
            val form = source.optString(key)
            if (form.isBlank() || RussianForms.normalize(form) == masc) return@mapNotNull null
            Card(noteId = note.id, cardType = CardType.ADJ_AGREE, queue = Queue.GRAMMAR, gramContextCue = cue, gramConcept = GrammarConcepts.ADJ_AGREE.id)
        }
    }

    // Noun gender recall. Gender drives every agreement choice but was stored and
    // never tested; one fast card per noun makes the pattern explicit.
    private fun genderCard(note: Note): Card? {
        if (!note.partOfSpeech.equals("noun", ignoreCase = true)) return null
        val gender = note.gender?.uppercase(Locale.ROOT) ?: return null
        if (gender !in NOUN_GENDERS) return null
        return Card(noteId = note.id, cardType = CardType.GENDER_ID, queue = Queue.GRAMMAR, gramGender = gender, gramConcept = GrammarConcepts.GENDER.id)
    }

    // Russian case pedagogy pacing, keyed to the note's own cefrLevel (not tier):
    // nominative+accusative singular are the A1 "core" (subject / direct object);
    // the rest of the singular paradigm (genitive/dative/instrumental/prepositional)
    // is the standard A2 milestone — real CEFR-Russian guidance is "by the end of A2
    // you should have met all six cases" — and any plural declension, across every
    // case, is deferred to B1, once longer sentences with plural referents are
    // expected. This scopes an A1-tagged note's drills to what an A1 learner should
    // actually be asked to produce; a B2+-tagged note (e.g. tier-2 domain vocabulary)
    // still gets the full paradigm immediately, since B2/C1 production is expected to
    // already command all of it.
    private fun minCefrOrdinalForCase(gramCase: String, gramNumber: String): Int = when {
        gramNumber == "PL" -> cefrOrdinal("B1")
        gramCase == "ACC" -> cefrOrdinal("A1")
        else -> cefrOrdinal("A2")
    }

    private fun caseCards(note: Note): List<Card> {
        val json = note.declensionJson ?: return emptyList()
        val gender = note.gender ?: return emptyList()
        val table = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        val source = if (table.has("cases")) table.getJSONObject("cases") else table
        val noteCefrOrdinal = cefrOrdinal(note.cefrLevel)
        val nominativeByNumber = mapOf(
            "SG" to source.optString("NOM_SG"),
            "PL" to source.optString("NOM_PL")
        )
        return source.keys().asSequence()
            .map { key -> key.uppercase(Locale.ROOT) }
            .mapNotNull { key ->
                val parts = key.split("_")
                val gramCase = parts.getOrNull(0)?.takeIf { it in CASES } ?: return@mapNotNull null
                if (gramCase == "NOM") return@mapNotNull null
                val gramNumber = parts.getOrNull(1)?.takeIf { it in NUMBERS } ?: if (gender == "PL") "PL" else "SG"
                if (noteCefrOrdinal < minCefrOrdinalForCase(gramCase, gramNumber)) return@mapNotNull null
                val answer = source.optString(key)
                val nominative = nominativeByNumber[gramNumber].orEmpty()
                if (answer.isBlank() || RussianForms.normalize(answer) == RussianForms.normalize(nominative)) return@mapNotNull null
                // Legacy rule-generated tables contain known bad forms. Only turn a
                // form into a graded card when it is attested in one of this note's
                // curated Russian examples; the sentence then acts as both evidence
                // and a meaningful carrier for the exercise.
                val examples = listOfNotNull(note.exampleSentence, note.exampleSentence2, note.exampleSentence3)
                val answerPattern = Regex(
                    "(?<![\\p{L}\\p{N}])${Regex.escape(answer)}(?![\\p{L}\\p{N}])",
                    RegexOption.IGNORE_CASE
                )
                if (examples.none { answerPattern.containsMatchIn(it) }) return@mapNotNull null
                Card(
                    noteId = note.id,
                    cardType = CardType.CASE_FILL,
                    queue = Queue.GRAMMAR,
                    gramCase = gramCase,
                    gramGender = gender,
                    gramNumber = gramNumber,
                    gramConcept = gramCase
                )
            }
            .toList()
    }

    // Past tense can be derived safely; present/future conjugations appear only
    // when the note ships an explicit verified table in declensionJson. This
    // keeps productive practice for пишу/люблю/вижу without teaching guesses.
    private fun verbFormCards(note: Note): List<Card> {
        if (!note.partOfSpeech.equals("verb", ignoreCase = true)) return emptyList()
        return RussianForms.verbForms(note).keys
            .filter { key -> key in VERB_FORM_KEYS }
            .map { key ->
                Card(
                    noteId = note.id,
                    cardType = CardType.VERB_FORM,
                    queue = Queue.GRAMMAR,
                    gramContextCue = key,
                    gramConcept = verbFormConcept(note, key)
                )
            }
    }

    private fun verbFormConcept(note: Note, key: String): String =
        when {
            key.startsWith("PRES_") && note.aspect == "PF" -> GrammarConcepts.FUTURE.id
            key.startsWith("PRES_") -> GrammarConcepts.PRESENT.id
            else -> GrammarConcepts.PAST.id
        }

    private val CASES = setOf("NOM", "ACC", "GEN", "DAT", "INS", "PREP")
    private val NUMBERS = setOf("SG", "PL")
    private val NOUN_GENDERS = setOf("M", "F", "N", "PL")
    // Only cues with a defensible default bias. RESULT/SINGLE_EVENT used generic
    // carriers that were often semantically invalid and taught "быстро = PF" as
    // a false deterministic rule.
    private val ASPECT_CONTEXT_CUES = listOf("PROCESS", "HABITUAL", "COMPLETED")
    private val VERB_FORM_KEYS = setOf(
        "PAST_M", "PAST_F", "PAST_N", "PAST_PL",
        "PRES_1SG", "PRES_2SG", "PRES_3SG", "PRES_1PL", "PRES_2PL", "PRES_3PL"
    )
}
