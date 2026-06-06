package com.sibirskyspeak.data

enum class CardType {
    RU_TO_MEANING,
    MEANING_TO_RU,
    CLOZE,
    AUDIO_TO_RU,
    CASE_FILL,
    VERB_FORM,
    ADJ_AGREE,
    GENDER_ID,
    ASPECT_SELECT
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
