package com.sibirskyspeak.audio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.speech.ModelDownloadListener
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi
import java.util.Locale

internal enum class RussianModelState { INSTALLED, DOWNLOADABLE, PENDING, UNSUPPORTED }

private fun Iterable<String>.containsRussianLocale(): Boolean = any { locale ->
    locale.equals("ru", ignoreCase = true) ||
        locale.startsWith("ru-", ignoreCase = true) ||
        locale.startsWith("ru_", ignoreCase = true)
}

internal fun russianModelState(
    installed: List<String>,
    pending: List<String>,
    downloadable: List<String>
): RussianModelState = when {
    installed.containsRussianLocale() -> RussianModelState.INSTALLED
    pending.containsRussianLocale() -> RussianModelState.PENDING
    downloadable.containsRussianLocale() -> RussianModelState.DOWNLOADABLE
    else -> RussianModelState.UNSUPPORTED
}

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
        onEndOfSpeech: () -> Unit = {},
        onPreparation: (String) -> Unit = {}
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || !isAvailable(appContext)) {
            onError(SpeechRecognitionPolicy.unavailableMessage())
            return
        }
        val callbacks = RecognitionCallbacks(
            onResult, onPartial, onError, onReadyForSpeech,
            onBeginningOfSpeech, onEndOfSpeech, onPreparation
        )
        val intent = recognitionIntent()
        // One recognizer per attempt keeps state clean across rapid retries and
        // also gives the support/download API an engine tied to this request.
        stop()
        val engine = runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext) }
            .getOrElse {
                onError(SpeechRecognitionPolicy.unavailableMessage())
                return
            }
            .also { recognizer = it }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            prepareRussianModel(engine, intent, callbacks)
        } else {
            beginListening(engine, intent, callbacks)
        }
    }

    private fun beginListening(engine: SpeechRecognizer, intent: Intent, callbacks: RecognitionCallbacks) {
        engine.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = callbacks.onReadyForSpeech()
            override fun onBeginningOfSpeech() = callbacks.onBeginningOfSpeech()
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                listening = false
                callbacks.onEndOfSpeech()
            }

            override fun onError(error: Int) {
                listening = false
                callbacks.onError(SpeechRecognitionPolicy.errorMessage(error))
            }

            override fun onResults(results: Bundle?) {
                listening = false
                callbacks.onResult(hypothesesWithConfidence(results))
            }

            override fun onPartialResults(partialResults: Bundle?) {
                bestHypothesis(partialResults)?.let(callbacks.onPartial)
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        listening = true
        runCatching { engine.startListening(intent) }
            .onFailure {
                listening = false
                callbacks.onError("Couldn't start listening. Try again.")
            }
    }

    private fun recognitionIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, RU)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, RU)
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, RU)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // Keep this hint for engines that expose an on-device model.
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun prepareRussianModel(
        engine: SpeechRecognizer,
        intent: Intent,
        callbacks: RecognitionCallbacks
    ) {
        callbacks.onPreparation("Checking the offline Russian speech model…")
        runCatching {
            engine.checkRecognitionSupport(intent, appContext.mainExecutor, object : RecognitionSupportCallback {
                override fun onSupportResult(support: RecognitionSupport) {
                    when (russianModelState(
                        support.installedOnDeviceLanguages,
                        support.pendingOnDeviceLanguages,
                        support.supportedOnDeviceLanguages
                    )) {
                        RussianModelState.INSTALLED -> beginListening(engine, intent, callbacks)
                        RussianModelState.DOWNLOADABLE -> downloadRussianModel(engine, intent, callbacks)
                        RussianModelState.PENDING -> callbacks.onError(
                            "Android is still preparing the Russian speech model. Use tiles now or retry later."
                        )
                        RussianModelState.UNSUPPORTED -> callbacks.onError(
                            "Offline Russian speech is not supported by this recognizer. Use the tile fallback."
                        )
                    }
                }

                // Some recognizers can listen but do not implement capability
                // queries. In that case preserve the working best-effort path.
                override fun onError(error: Int) = beginListening(engine, intent, callbacks)
            })
        }.onFailure { beginListening(engine, intent, callbacks) }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun downloadRussianModel(
        engine: SpeechRecognizer,
        intent: Intent,
        callbacks: RecognitionCallbacks
    ) {
        callbacks.onPreparation("Preparing the offline Russian speech model…")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            runCatching { engine.triggerModelDownload(intent) }
                .onSuccess {
                    callbacks.onError(
                        "Android is downloading the Russian speech model. Use tiles now or retry the mic shortly."
                    )
                }
                .onFailure { callbacks.onError("Could not request the Russian speech model. Use the tile fallback.") }
            return
        }
        downloadRussianModelWithProgress(engine, intent, callbacks)
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun downloadRussianModelWithProgress(
        engine: SpeechRecognizer,
        intent: Intent,
        callbacks: RecognitionCallbacks
    ) {
        runCatching {
            engine.triggerModelDownload(intent, appContext.mainExecutor, object : ModelDownloadListener {
                override fun onProgress(completedPercent: Int) {
                    callbacks.onPreparation("Downloading offline Russian speech: ${completedPercent.coerceIn(0, 100)}%")
                }

                override fun onSuccess() = beginListening(engine, intent, callbacks)

                override fun onScheduled() = callbacks.onError(
                    "Android scheduled the Russian speech model download. Use tiles now or retry later."
                )

                override fun onError(error: Int) = callbacks.onError(
                    "Could not download the Russian speech model. Use the tile fallback."
                )
            })
        }.onFailure {
            callbacks.onError("Could not request the Russian speech model. Use the tile fallback.")
        }
    }

    private data class RecognitionCallbacks(
        val onResult: (List<SpeechHypothesis>) -> Unit,
        val onPartial: (String) -> Unit,
        val onError: (String) -> Unit,
        val onReadyForSpeech: () -> Unit,
        val onBeginningOfSpeech: () -> Unit,
        val onEndOfSpeech: () -> Unit,
        val onPreparation: (String) -> Unit
    )

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
