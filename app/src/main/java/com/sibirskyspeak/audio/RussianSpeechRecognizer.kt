package com.sibirskyspeak.audio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/**
 * Thin wrapper around Android's on-device [SpeechRecognizer] for Russian speaking
 * practice (the SPEAK card type). Mirrors [RussianTextToSpeech]: created with an
 * application context, used from the UI, and shut down when the screen is gone.
 *
 * Recognition is best-effort: it prefers an on-device model (no network) and falls
 * back to whatever the platform recognizer provides. Callers must hold RECORD_AUDIO
 * and should check [isAvailable] first; everything degrades gracefully (an error
 * callback, never a crash) so the rest of the review flow is unaffected when speech
 * isn't available on a given device.
 */
class RussianSpeechRecognizer(context: Context) {
    private val appContext = context.applicationContext
    private var recognizer: SpeechRecognizer? = null
    private var listening = false

    fun startListening(
        // confidence is the recognizer's own certainty in the transcript (0f-1f), or
        // null when the recognizer didn't supply one — on-device/offline models
        // (preferred here via EXTRA_PREFER_OFFLINE) frequently omit it entirely, so
        // callers must treat absence as "no signal", not "low confidence".
        onResult: (String, Float?) -> Unit,
        onPartial: (String) -> Unit = {},
        onError: (String) -> Unit = {},
        onReadyForSpeech: () -> Unit = {},
        onEndOfSpeech: () -> Unit = {}
    ) {
        if (!isAvailable(appContext)) {
            onError("Speech recognition isn't available on this device.")
            return
        }
        // One recognizer per attempt keeps state clean across rapid retries.
        stop()
        val engine = SpeechRecognizer.createSpeechRecognizer(appContext).also { recognizer = it }
        engine.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = onReadyForSpeech()
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                listening = false
                onEndOfSpeech()
            }

            override fun onError(error: Int) {
                listening = false
                onError(errorMessage(error))
            }

            override fun onResults(results: Bundle?) {
                listening = false
                val (hypothesis, confidence) = bestHypothesisWithConfidence(results)
                onResult(hypothesis.orEmpty(), confidence)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                bestHypothesis(partialResults)?.let(onPartial)
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, RU)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, RU)
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, RU)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // Prefer an offline model so practice works without a network connection.
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        listening = true
        runCatching { engine.startListening(intent) }
            .onFailure {
                listening = false
                onError("Couldn't start listening: ${it.message ?: "unknown error"}")
            }
    }

    fun stop() {
        recognizer?.let {
            runCatching { it.stopListening() }
            runCatching { it.cancel() }
            runCatching { it.destroy() }
        }
        recognizer = null
        listening = false
    }

    fun shutdown() = stop()

    private fun bestHypothesis(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull { it.isNotBlank() }
            ?.trim()

    // CONFIDENCE_SCORES (API 14+) is a float[] parallel to RESULTS_RECOGNITION, values
    // 0f-1f or -1f for "unavailable" per-entry. It's optional and many on-device
    // recognizers never populate it, so both a missing array and a -1 entry map to a
    // null confidence rather than being read as "low".
    private fun bestHypothesisWithConfidence(bundle: Bundle?): Pair<String?, Float?> {
        val hypotheses = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        val index = hypotheses?.indexOfFirst { it.isNotBlank() } ?: -1
        if (hypotheses == null || index < 0) return null to null
        val confidence = bundle?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
            ?.getOrNull(index)
            ?.takeIf { it >= 0f }
        return hypotheses[index].trim() to confidence
    }

    private fun errorMessage(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "Audio recording error."
        SpeechRecognizer.ERROR_CLIENT -> "Recognition cancelled."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is needed."
        SpeechRecognizer.ERROR_NETWORK -> "Network error (no offline model?)."
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timed out."
        SpeechRecognizer.ERROR_NO_MATCH -> "Didn't catch that. Try again."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy. Try again."
        SpeechRecognizer.ERROR_SERVER -> "Recognition server error."
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech heard. Try again."
        else -> "Couldn't recognize speech. Try again."
    }

    companion object {
        private val RU = Locale("ru", "RU").toString()

        fun isAvailable(context: Context): Boolean =
            runCatching { SpeechRecognizer.isRecognitionAvailable(context.applicationContext) }
                .getOrDefault(false)
    }
}
