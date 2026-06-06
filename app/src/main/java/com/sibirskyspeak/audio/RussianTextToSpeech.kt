package com.sibirskyspeak.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

class RussianTextToSpeech(context: Context) : TextToSpeech.OnInitListener {
    private var engine: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    private var ready = false

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            engine?.language = Locale("ru", "RU")
        }
    }

    fun speak(text: String) {
        if (!ready || text.isBlank()) return
        engine?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ru-${System.currentTimeMillis()}")
    }

    fun shutdown() {
        engine?.shutdown()
        engine = null
        ready = false
    }
}
