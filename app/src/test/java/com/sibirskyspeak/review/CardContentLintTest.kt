package com.sibirskyspeak.review

import com.sibirskyspeak.data.*
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CardContentLintTest {
    @Test fun realBootstrapSampleBuildsHonestNonBlankCards() {
        val file=sequenceOf(File("src/main/assets/bootstrap_notes.jsonl"),File("app/src/main/assets/bootstrap_notes.jsonl")).first { it.exists() }
        val rows=file.readLines().filter { it.isNotBlank() }.shuffled(kotlin.random.Random(20260704)).take(500)
        rows.forEachIndexed { index,line ->
            val j=JSONObject(line)
            fun clean(key:String)=if(!j.has(key)||j.isNull(key))null else j.get(key).let { if(it is JSONObject) it.toString() else it.toString() }.takeIf { it.isNotBlank()&&it!="null" }
            val note=Note(id=index+1L,russian=j.getString("russian"),lemma=j.getString("lemma"),translation=j.getString("translation"),partOfSpeech=j.getString("pos"),
                exampleSentence=clean("exampleSentence"),exampleTranslation=clean("exampleTranslation"),exampleSentence2=clean("exampleSentence2"),exampleTranslation2=clean("exampleTranslation2"),
                declensionJson=clean("declensionJson"),gender=clean("gender"),aspect=clean("aspect"),aktionsart=clean("aktionsart"),tags=j.optString("tags",""),tier=j.optInt("tier",1),
                unit=j.optInt("unit").takeIf { j.has("unit") },conceptId=clean("conceptId") ?: clean("concept"),cefrLevel=clean("cefrLevel"),mnemonic=clean("mnemonic"))
            CardFactory.cardsFor(note).forEach { card ->
                val prompt=buildPrompt(card,note,Rating.entries.associateWith { 1 })
                if(prompt.answerMode!=AnswerMode.AUDIO_ONLY) assertFalse("blank prompt for ${note.lemma}/${card.cardType}",prompt.prompt.isBlank())
                assertFalse("blank answer for ${note.lemma}/${card.cardType}",prompt.expectedAnswer.isBlank())
                if(prompt.answerMode==AnswerMode.CHOICE) assertTrue("choice underflow",prompt.choices.distinct().size>=2)
                if(card.cardType==CardType.CLOZE && prompt.prompt.contains(note.russian,ignoreCase=true))
                    assertTrue("cloze leaked whole answer: ${note.lemma}", prompt.prompt != note.exampleSentence)
            }
        }
    }
}
