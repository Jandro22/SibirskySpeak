package com.sibirskyspeak.audio

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

class RussianTextToSpeech(context: Context) : TextToSpeech.OnInitListener {
    private var engine: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    private var ready = false
    private var pendingSpeech: String? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var sequenceOnSentence: ((Int) -> Unit)? = null
    private var sequenceOnDone: (() -> Unit)? = null
    private var sequenceLast = -1

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            engine?.language = Locale("ru", "RU")
            pendingSpeech?.let { queued ->
                pendingSpeech = null
                speak(queued)
            }
        } else {
            pendingSpeech = null
        }
    }

    fun speak(text: String) {
        val cleaned = text.cleanForSpeech()
        if (cleaned.isBlank()) return
        if (!ready) {
            pendingSpeech = cleaned
            return
        }
        val chunks = cleaned.chunkForSpeech()
        if (chunks.isEmpty()) return
        chunks.forEachIndexed { index, chunk ->
            engine?.speak(
                chunk,
                if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
                null,
                "ru-${System.currentTimeMillis()}-$index"
            )
        }
    }

    /**
     * Speak a list of sentences in order, invoking [onSentenceStart] with the index
     * of each sentence as it begins (for karaoke-style highlighting) and [onDone]
     * after the last one. Used by the reader's sentence-by-sentence "Listen" mode.
     */
    fun speakSentences(sentences: List<String>, onSentenceStart: (Int) -> Unit, onDone: () -> Unit) {
        val cleaned = sentences.map { it.cleanForSpeech() }
        if (cleaned.all { it.isBlank() }) {
            onDone()
            return
        }
        if (!ready) {
            // Engine not ready yet: fall back to a single flush of the joined text.
            speak(cleaned.joinToString(" "))
            onDone()
            return
        }
        sequenceOnSentence = onSentenceStart
        sequenceOnDone = onDone
        sequenceLast = cleaned.indices.last { cleaned[it].isNotBlank() }
        engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                val idx = utteranceId?.toIntOrNull() ?: return
                mainHandler.post { sequenceOnSentence?.invoke(idx) }
            }

            override fun onDone(utteranceId: String?) {
                val idx = utteranceId?.toIntOrNull() ?: return
                if (idx >= sequenceLast) mainHandler.post { finishSequence() }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                mainHandler.post { finishSequence() }
            }
        })
        cleaned.forEachIndexed { index, sentence ->
            if (sentence.isBlank()) return@forEachIndexed
            engine?.speak(
                sentence,
                if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
                null,
                index.toString()
            )
        }
    }

    /** Stop any in-progress speech (single or sentence sequence). */
    fun stopSpeaking() {
        engine?.stop()
        finishSequence()
    }

    private fun finishSequence() {
        val done = sequenceOnDone
        sequenceOnSentence = null
        sequenceOnDone = null
        sequenceLast = -1
        done?.invoke()
    }

    fun shutdown() {
        engine?.shutdown()
        engine = null
        ready = false
    }

    private fun String.cleanForSpeech(): String =
        replace("\u0301", "")
            .replace(Regex("_{3,}"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun String.chunkForSpeech(maxLength: Int = 3500): List<String> {
        if (isBlank()) return emptyList()
        if (length <= maxLength) return listOf(this)
        val chunks = mutableListOf<String>()
        var remaining = this
        while (remaining.length > maxLength) {
            val splitAt = remaining.lastIndexOfAny(charArrayOf('.', '!', '?', '\n', ' '), startIndex = maxLength)
                .takeIf { it > maxLength / 2 }
                ?: maxLength
            chunks += remaining.substring(0, splitAt).trim()
            remaining = remaining.substring(splitAt).trim()
        }
        if (remaining.isNotBlank()) chunks += remaining
        return chunks
    }
}
