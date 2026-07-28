package com.sibirskyspeak.audio

import android.speech.SpeechRecognizer

/** Pure platform-error policy shared by the recognizer wrapper and tests. */
internal object SpeechRecognitionPolicy {
    fun unavailableMessage(): String = "Speech recognition isn't available on this device."

    fun errorMessage(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "Audio recording error."
        SpeechRecognizer.ERROR_CLIENT -> "Recognition cancelled."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is needed."
        SpeechRecognizer.ERROR_NETWORK -> "Network error (no offline model?)."
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timed out."
        SpeechRecognizer.ERROR_NO_MATCH -> "Didn't catch that. Try again."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy. Try again."
        SpeechRecognizer.ERROR_SERVER -> "Recognition server error."
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech heard. Try again."
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "Russian recognition is not supported by this device."
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "The Russian speech model is not installed."
        else -> "Couldn't recognize speech. Try again."
    }
}
