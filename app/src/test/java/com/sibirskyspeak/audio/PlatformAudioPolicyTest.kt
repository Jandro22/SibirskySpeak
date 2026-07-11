package com.sibirskyspeak.audio

import android.speech.SpeechRecognizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformAudioPolicyTest {
    @Test
    fun recognizerErrorsAreActionableAndStable() {
        assertEquals("Microphone permission is needed.", SpeechRecognitionPolicy.errorMessage(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS))
        assertEquals("No speech heard. Try again.", SpeechRecognitionPolicy.errorMessage(SpeechRecognizer.ERROR_SPEECH_TIMEOUT))
        assertEquals("Couldn't recognize speech. Try again.", SpeechRecognitionPolicy.errorMessage(-999))
    }

    @Test
    fun ttsNormalizationDropsGlossesAndPreservesRussianText() {
        assertEquals("Привет мир!", normalizeRussianSpeech("Приве́т мир! hello"))
        assertTrue(normalizeRussianSpeech("hello world").isEmpty())
    }

    @Test
    fun ttsChunksPreferSentenceBoundaries() {
        val chunks = chunkRussianSpeech("Первое предложение. Второе предложение.", maxLength = 24)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.first().endsWith("."))
        assertEquals("Второе предложение.", chunks.last())
    }
}
