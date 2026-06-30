package com.sibirskyspeak.learning

import com.sibirskyspeak.data.Card
import com.sibirskyspeak.data.CardState
import com.sibirskyspeak.data.CardType
import com.sibirskyspeak.data.Note
import com.sibirskyspeak.data.Queue
import com.sibirskyspeak.data.SentenceCandidate
import com.sibirskyspeak.review.AnswerMode
import com.sibirskyspeak.review.ReviewPrompt
import com.sibirskyspeak.review.buildPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LearningEfficiencyTest {
    private fun note(id: Long, ru: String, en: String, lemma: String = ru) = Note(
        id = id, russian = ru, translation = en, partOfSpeech = "noun", lemma = lemma
    )

    private fun prompt(card: Card, note: Note, mode: AnswerMode = AnswerMode.ENGLISH) = ReviewPrompt(
        card = card, note = note, prompt = note.russian, expectedAnswer = note.translation,
        answerMode = mode, intervalPreview = emptyMap()
    )

    @Test fun `miner ranks true i plus one and anchors a single sense`() {
        val target = note(1, "по", "along, by, according to, per")
        val candidates = listOf(
            SentenceCandidate(1, "Я иду́ по доро́ге.", "Я иду по дороге.", "I walk along the road.", 4, false, .8, 2, "PREP"),
            SentenceCandidate(2, "Мы говори́м по-ру́сски до́ма.", "Мы говорим по-русски дома.", "We speak Russian at home.", 5, true, .9, 2, "PREP")
        )
        val ranked = ExampleMiner.rank(target, candidates, setOf("я", "иду", "дороге"), 3)
        assertEquals(1L, ranked.first().example.sentenceId)
        assertTrue(ranked.first().isIPlusOne)
        assertEquals("along", ranked.first().example.anchoredGloss)
    }

    @Test fun `mined cloze uses exact attested inflection not declension json`() {
        val target = note(1, "книга", "book").copy(
            exampleSentence = "Я читаю книгу сейчас.",
            exampleTranslation = "I am reading a book now.",
            declensionJson = "{\"cases\":{\"ACC_SG\":\"WRONG\"}}"
        )
        val card = Card(id = 1, noteId = 1, cardType = CardType.CLOZE, queue = Queue.VOCAB)
        val built = buildPrompt(card, target, emptyMap(), minedTargetPos = 2)
        assertEquals("книгу", built.expectedAnswer)
        assertTrue(built.prompt.contains("____"))
        assertFalse(built.prompt.contains("книгу"))
    }

    @Test fun `quick blueprint is exactly its at risk set`() {
        val now = 20L * 86_400_000
        val due = (1L..4L).map { id -> Card(
            id = id, noteId = id, cardType = CardType.RU_TO_MEANING, queue = Queue.VOCAB,
            state = CardState.REVIEW, stability = 2.0, lastReview = now - 10 * 86_400_000
        ) }
        val fresh = Card(id = 9, noteId = 9, cardType = CardType.RU_TO_MEANING, queue = Queue.VOCAB)
        val blueprint = BlueprintBuilder.build(due + fresh, now, .9, 10, 20, false, .9, SessionMode.QUICK)
        assertEquals(0, blueprint.newBudget)
        assertEquals(blueprint.atRiskCardIds.size, blueprint.totalBudget)
        assertEquals(due.map { it.id }.toSet(), blueprint.atRiskCardIds)
    }

    @Test fun `selector enforces sibling lesson and lapse spacing constraints`() {
        val now = 10L * 86_400_000
        val last = note(1, "дом", "house")
        val sibling = prompt(Card(id = 2, noteId = 1, cardType = CardType.CLOZE, queue = Queue.VOCAB), last)
        val locked = prompt(Card(id = 3, noteId = 2, cardType = CardType.CASE_FILL, queue = Queue.GRAMMAR, gramConcept = "ACC"), note(2, "стол", "table"))
        val lapsed = prompt(Card(id = 4, noteId = 3, cardType = CardType.RU_TO_MEANING, queue = Queue.VOCAB), note(3, "вода", "water"))
        val eligible = prompt(Card(id = 5, noteId = 4, cardType = CardType.RU_TO_MEANING, queue = Queue.VOCAB), note(4, "хлеб", "bread"))
        val blueprint = SessionBlueprint(SessionMode.FULL, emptySet(), 4, 0, 0, 0, emptyList(), .88)
        val live = LiveSessionState(shown = 3, recentNoteIds = listOf(1), lapsedShownAt = mapOf(4L to 1), introducedConcepts = emptySet())
        assertEquals(5L, NextCardSelector.select(listOf(sibling, locked, lapsed, eligible), blueprint, live, now)?.card?.id)
    }

    @Test fun `load smoothing alternates against the actual previous card difficulty`() {
        val now = 10L * 86_400_000
        val easy = prompt(
            Card(id = 1, noteId = 1, cardType = CardType.RU_TO_MEANING, queue = Queue.VOCAB, state = CardState.REVIEW, stability = 5.0, lastReview = now - 86_400_000),
            note(1, "дом", "house"), AnswerMode.ENGLISH
        )
        val hard = prompt(
            Card(id = 2, noteId = 2, cardType = CardType.MEANING_TO_RU, queue = Queue.VOCAB, state = CardState.REVIEW, stability = 5.0, lastReview = now - 86_400_000),
            note(2, "стол", "table"), AnswerMode.RUSSIAN_TYPED
        )
        val blueprint = SessionBlueprint(SessionMode.FULL, emptySet(), 4, 0, 0, 0, emptyList(), .88)
        // Previous card was hard -> prefer the easy card next (and vice versa). The old
        // bug compared against `recentNoteIds.size >= 2`, so it never actually alternated.
        val afterHard = LiveSessionState(shown = 2, recentHard = listOf(true))
        assertEquals(1L, NextCardSelector.select(listOf(easy, hard), blueprint, afterHard, now)?.card?.id)
        val afterEasy = LiveSessionState(shown = 2, recentHard = listOf(false))
        assertEquals(2L, NextCardSelector.select(listOf(easy, hard), blueprint, afterEasy, now)?.card?.id)
    }

    @Test fun `session lookahead finds an interior optimum, not always the cap`() {
        val choice = SessionLookahead.choose(cap = 50, dueForecast = List(7) { 20 }, retention = .9)
        assertTrue("should add some new cards", choice.newCards > 0)
        assertTrue("must not blindly return the cap (old monotonic bug)", choice.newCards < 50)
    }

    @Test fun `adaptive models move in the expected direction`() {
        val elo = TrueSkill.update(Gaussian(1000.0, TrueSkill.SIGMA0), Gaussian(1000.0, TrueSkill.SIGMA0), MatchOutcome.WIN)
        assertTrue(elo.a.mu > 1000.0)
        assertTrue(elo.b.mu < 1000.0)
        assertTrue(MasteryModel.update(.2, true) > .2)
        assertTrue(ReviewControl.optimalRetention(.2) in 0.85..0.90)
        assertTrue(CognateDetector.isCognate("телефон", "telephone"))
    }
}
