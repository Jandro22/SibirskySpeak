package com.sibirskyspeak.audio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/**
 * Thin wrapper around Android's on-device [SpeechRecognizer] for Russian speaking
 * practice (the SPEAK card type). Mirrors [RussianTextToSpeech]: created with an
 * application context, used from the UI, and shut down when the screen is gone.
 *
 * Recognition is intentionally on-device only. EXTRA_PREFER_OFFLINE is merely a
 * preference and does not prevent some platform engines from sending audio to a
 * provider, so devices without an on-device implementation are disabled gracefully.
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
        onResult: (List<SpeechHypothesis>) -> Unit,
        onPartial: (String) -> Unit = {},
        onError: (String) -> Unit = {},
        onReadyForSpeech: () -> Unit = {},
        onBeginningOfSpeech: () -> Unit = {},
        onEndOfSpeech: () -> Unit = {}
    ) {
        if (!isAvailable(appContext)) {
            onError(SpeechRecognitionPolicy.unavailableMessage())
            return
        }
        // One recognizer per attempt keeps state clean across rapid retries.
        stop()
        val engine = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
        } else {
            onError(SpeechRecognitionPolicy.unavailableMessage())
            return
        }.also { recognizer = it }
        engine.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = onReadyForSpeech()
            override fun onBeginningOfSpeech() = onBeginningOfSpeech()
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                listening = false
                onEndOfSpeech()
            }

            override fun onError(error: Int) {
                listening = false
                onError(SpeechRecognitionPolicy.errorMessage(error))
            }

            override fun onResults(results: Bundle?) {
                listening = false
                onResult(hypothesesWithConfidence(results))
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
            // Keep this hint for engines that expose an on-device model.
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        listening = true
        runCatching { engine.startListening(intent) }
            .onFailure {
                listening = false
                onError("Couldn't start listening. Try again.")
            }
    }

    /** Ask the active recognizer to finish the utterance without cancelling it. */
    fun finishListening() {
        if (!listening) return
        runCatching { recognizer?.stopListening() }
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
        hypothesesWithConfidence(bundle).firstOrNull()?.transcript

    // CONFIDENCE_SCORES (API 14+) is a float[] parallel to RESULTS_RECOGNITION, values
    // 0f-1f or -1f for "unavailable" per-entry. It's optional and many on-device
    // recognizers never populate it, so both a missing array and a -1 entry map to a
    // null confidence rather than being read as "low".
    private fun hypothesesWithConfidence(bundle: Bundle?): List<SpeechHypothesis> {
        val hypotheses = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        if (hypotheses == null) return emptyList()
        val confidences = bundle.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
        return hypotheses.mapIndexedNotNull { index, value ->
            value.trim().takeIf(String::isNotBlank)?.let {
                SpeechHypothesis(
                    transcript = it,
                    confidence = confidences?.getOrNull(index)?.takeIf { score -> score >= 0f }
                )
            }
        }
    }

    companion object {
        private val RU = Locale("ru", "RU").toString()

        fun isAvailable(context: Context): Boolean =
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) false
            else runCatching {
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context.applicationContext)
            }.getOrDefault(false)
    }
}

data class SpeechHypothesis(val transcript: String, val confidence: Float?)
