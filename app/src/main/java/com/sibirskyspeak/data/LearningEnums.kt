package com.sibirskyspeak.data

enum class CardType {
    RU_TO_MEANING,
    MEANING_TO_RU,
    CLOZE,
    AUDIO_TO_RU,
    // Pronunciation/production practice: the learner says the Russian aloud and
    // on-device speech recognition checks it (the only card that trains speaking).
    SPEAK,
    DICTATION,
    SENTENCE_BUILD,
    // Legacy compatibility: no longer generated, but retained so existing databases
    // and full-state backups containing older stress cards remain readable.
    STRESS_MARK,
    CASE_FILL,
    VERB_FORM,
    ADJ_AGREE,
    GENDER_ID,
    ASPECT_SELECT,
    // Authored grammar practice for concepts that are not safely derivable from a
    // single word form (upper-level syntax, participles, register, idiom, etc.).
    CONCEPT_DRILL,
    // A teaching card: shows a grammar concept's explanation and a worked example
    // BEFORE any drill on that concept. Has no real answer ("Got it" only). Seeing
    // it flips the concept to "introduced", which is what lets its drills surface.
    LESSON,
    // One per GrammarConcept, scheduled independently of any single note. The prompt
    // is a freshly composed sentence realized at display time from tools/preprocess/
    // frames.json via generation/FrameRealizer.kt — a novel carrier every time rather
    // than one frozen example, so the FSRS state belongs to the concept itself
    // ("GEN after нет") instead of to any one lexeme. See CLAUDE.md Phase 4 (P4.3).
    CONCEPT_APPLY,
    // Production ladder L1 (P4.4): typed production of a real collocation ("на
    // диване", not just the bare lemma) mined from the on-device collocation table
    // and gated on the parent word's own recognition maturity (see
    // LearningRepository.syncMissingChunkCards / isChunkBeforeParentRecognitionMatures).
    CHUNK,
    // Production ladder L2 (P4.4): rewrite a real sentence-bank sentence containing
    // this verb under an instruction (currently: negate) with a single grammatically
    // forced answer, derived by transform/Transformer.kt — infinite, novel, and
    // exactly gradeable with zero new authored content.
    TRANSFORM,
    // Production ladder L3, the ladder's payoff (P4.4): only an English cue is
    // shown (from FrameRealizer, same frames as CONCEPT_APPLY); the learner types a
    // full novel Russian sentence from scratch. Graded word-order-free
    // (review/AnswerNormalizer.evaluateWordOrderFreeRussianAnswer) since Russian
    // word order is genuinely free — see CardPedagogy for why this is PRACTICE, not
    // STRONG, evidence.
    NOVEL_PRODUCE,
    // Elicited imitation (P6.1): TTS plays a real sentence-bank sentence; the
    // learner repeats it from memory. Among the best-validated proficiency probes
    // in SLA — you cannot repeat a sentence above your competence, so it forces
    // parsing, not parroting. Graded order-aware, per-token (see
    // review/AnswerNormalizer.evaluateElicitedImitation), at PRACTICE strength
    // (ASR noise means a miss is weaker evidence than a typed miss).
    SPEAK_SENTENCE
}

enum class Queue {
    VOCAB,
    GRAMMAR
}

enum class CardState {
    NEW,
    LEARNING,
    REVIEW,
    RELEARNING,
    GRADUATED
}

enum class Rating(val value: Int) {
    AGAIN(1),
    HARD(2),
    GOOD(3),
    EASY(4)
}

enum class ReviewSource {
    SRS_REVIEW,
    READER_LOOKUP,
    GRAMMAR_DRILL,
    READING,
    LISTENING,
    PRODUCTION
}

/**
 * Explicit per-word reading status, LingQ-style. [NEW] words are highlighted as
 * unfamiliar, [LEARNING] words are actively being studied, [KNOWN] words read as
 * plain text and count toward coverage, and [IGNORED] words (names, numbers) are
 * never highlighted but are treated as readable.
 */
enum class WordStatus {
    NEW,
    LEARNING,
    KNOWN,
    IGNORED
}
