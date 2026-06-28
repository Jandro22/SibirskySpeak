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
    LESSON
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
    GRAMMAR_DRILL
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
