package com.sibirskyspeak.data

enum class CardType {
    RU_TO_MEANING,
    MEANING_TO_RU,
    CLOZE,
    AUDIO_TO_RU,
    CASE_FILL,
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
