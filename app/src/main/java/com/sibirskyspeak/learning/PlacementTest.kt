package com.sibirskyspeak.learning

/**
 * A short, fixed diagnostic quiz that estimates which CEFR level a learner
 * already knows, so onboarding isn't limited to "place after X" buttons the
 * learner has to guess at themselves. Two questions per level (A1..C2),
 * ordered easiest-first; [suggestedLevel] finds the highest level the learner
 * can demonstrate securely and stops advancing past the first level that stumps them.
 *
 * This is deliberately not adaptive/CAT-style (no branching, no item response
 * theory) — a fixed 12-question staircase is simple to reason about, simple to
 * test, and good enough to save a learner from re-doing material they already
 * know, which is the actual goal (see LearningRepository.placeAfterLevel).
 */
data class PlacementQuestion(
    val level: String,
    val prompt: String,
    val choices: List<String>,
    val correctIndex: Int
)

object PlacementTest {
    /** Ordered A1..C2, matching LearningRepository.CEFR_LEVELS. */
    val LEVELS = listOf("A1", "A2", "B1", "B2", "C1", "C2")

    val QUESTIONS: List<PlacementQuestion> = listOf(
        // A1: basic vocabulary recognition + present-tense conjugation.
        PlacementQuestion(
            "A1", "Which word means \"book\"?",
            listOf("кни́га", "стол", "окно́", "вода́"), 0
        ),
        PlacementQuestion(
            "A1", "Я ___ кни́гу. (\"I am reading a book.\")",
            listOf("чита́ешь", "чита́ю", "чита́ет", "чита́ем"), 1
        ),
        // A2: future tense + modal predicates.
        PlacementQuestion(
            "A2", "За́втра я ___ рабо́тать. (\"Tomorrow I will work.\")",
            listOf("есть", "был", "бу́ду", "рабо́таю"), 2
        ),
        PlacementQuestion(
            "A2", "What does \"нельзя́\" mean?",
            listOf("it is necessary", "it is possible", "it is easy", "it is forbidden"), 3
        ),
        // B1: relative clause case agreement + conditional бы.
        PlacementQuestion(
            "B1", "Кни́га, ___ я чита́ю, интере́сная. (\"The book that I'm reading is interesting.\")",
            listOf("кото́рый", "кото́рая", "кото́рую", "кото́рое"), 2
        ),
        PlacementQuestion(
            "B1", "Я ___ помо́г, е́сли бы знал. (\"I would have helped if I had known.\")",
            listOf("бу́ду", "был", "есть", "бы"), 3
        ),
        // B2: active participle formation + reflexive passive.
        PlacementQuestion(
            "B2", "челове́к, ___ кни́гу (\"the person reading a book\")",
            listOf("чита́вший", "чита́ющий", "чита́емый", "чита́нный"), 1
        ),
        PlacementQuestion(
            "B2", "Дом ___ рабо́чими. (\"The house is being built by workers.\")",
            listOf("стро́ит", "постро́ил", "стро́ится", "стро́ю"), 2
        ),
        // C1: bookish connectors + register vocabulary.
        PlacementQuestion(
            "C1", "Which connector means \"despite the fact that\"?",
            listOf("поэ́тому", "одна́ко", "и́бо", "несмотря́ на то что"), 3
        ),
        PlacementQuestion(
            "C1", "Which formal-register word means \"to carry out\"?",
            listOf("осуществля́ть", "де́лать", "рабо́тать", "игра́ть"), 0
        ),
        // C2: discourse particles + concessive subordination.
        PlacementQuestion(
            "C2", "In reported speech, what does the particle \"мол\" signal?",
            listOf(
                "strong certainty",
                "a casual, distancing hedge on a quote (\"so they say\")",
                "a direct question",
                "a command"
            ), 1
        ),
        PlacementQuestion(
            "C2", "Which phrase means \"no matter what happens\"?",
            listOf("что́бы", "что э́то", "что бы ни", "чтоб"), 2
        )
    )

    /**
     * [correctByLevel] maps each level to how many of its 2 questions were
     * answered correctly. Walks A1..C2; a level counts as known only at 2/2.
     * With four-choice items, accepting 1/2 gives a random guesser a 43.75%
     * chance of skipping each level. Placement is allowed to be conservative:
     * an underestimated learner can advance quickly, while an overestimated one
     * silently loses prerequisite instruction.
     * correct. Returns the last known level in the unbroken streak from A1, or
     * null if even A1 wasn't mostly correct (recommend starting at the true
     * beginning — no placement).
     */
    fun suggestedLevel(correctByLevel: Map<String, Int>): String? {
        var last: String? = null
        for (level in LEVELS) {
            val correct = correctByLevel[level] ?: 0
            if (correct >= 2) {
                last = level
            } else {
                break
            }
        }
        return last
    }

    /** Convenience: score a flat list of per-question booleans (same order as [QUESTIONS]). */
    fun suggestedLevel(answeredCorrectly: List<Boolean>): String? {
        val byLevel = LinkedHashMap<String, Int>()
        QUESTIONS.forEachIndexed { index, question ->
            val correct = answeredCorrectly.getOrElse(index) { false }
            if (correct) byLevel[question.level] = (byLevel[question.level] ?: 0) + 1
        }
        return suggestedLevel(byLevel)
    }
}
