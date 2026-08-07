package com.sibirskyspeak.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

class RussianTextToSpeech(context: Context) : TextToSpeech.OnInitListener {
    private var engine: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    private var ready = false
    private data class PendingSpeech(val text: String, val rate: Float, val pitch: Float, val voiceVariant: Int, val condition: String?)
    private var pendingSpeech: PendingSpeech? = null
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
                speak(queued.text, queued.rate, queued.pitch, queued.voiceVariant, queued.condition)
            }
        } else {
            pendingSpeech = null
        }
    }

    fun speak(text: String, rate: Float = 1.0f, pitch: Float = 1.0f, voiceVariant: Int = 0, condition: String? = null) {
        val cleaned = normalizeRussianSpeech(text)
        if (cleaned.isBlank()) return
        // A normal utterance interrupts reader sequence mode. Resolve its callbacks
        // immediately so UI highlighting cannot remain stuck on an old sentence.
        if (sequenceOnDone != null) finishSequence()
        if (!ready) {
            pendingSpeech = PendingSpeech(cleaned, rate, pitch, voiceVariant, condition)
            return
        }
        engine?.setSpeechRate(rate.coerceIn(0.75f, 1.25f))
        engine?.setPitch(pitch.coerceIn(0.85f, 1.15f))
        selectRussianVoice(voiceVariant)
        val chunks = chunkRussianSpeech(cleaned)
        if (chunks.isEmpty()) return
        if (condition == "controlled background noise") playControlledMaskingNoise(cleaned.length)
        chunks.forEachIndexed { index, chunk ->
            engine?.speak(
                chunk,
                if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
                null,
                "ru-${System.currentTimeMillis()}-$index"
            )
        }
    }

    /** Low-level, deterministic masking noise for advanced listening. It is quiet
     * enough to preserve intelligibility and is never used unless the authored
     * listening profile explicitly requests it. */
    private fun playControlledMaskingNoise(textLength: Int) {
        val sampleRate = 8_000
        val durationMs = (1_500 + textLength * 55).coerceIn(1_500, 8_000)
        val sampleCount = sampleRate * durationMs / 1_000
        val pcm = ByteArray(sampleCount * 2)
        var state = 0x51F15EED
        repeat(sampleCount) { index ->
            state = state * 1_103_515_245 + 12_345
            val sample = ((state ushr 16) and 0x1ff) - 256
            pcm[index * 2] = (sample and 0xff).toByte()
            pcm[index * 2 + 1] = (sample shr 8).toByte()
        }
        runCatching {
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size)
                .build()
            track.write(pcm, 0, pcm.size)
            track.play()
            Thread {
                try { Thread.sleep(durationMs.toLong()) } finally { runCatching { track.stop() }; track.release() }
            }.apply { name = "russian-listening-noise"; isDaemon = true }.start()
        }
    }

    /**
     * Speak a list of sentences in order, invoking [onSentenceStart] with the index
     * of each sentence as it begins (for karaoke-style highlighting) and [onDone]
     * after the last one. Used by the reader's sentence-by-sentence "Listen" mode.
     */
    fun speakSentences(sentences: List<String>, onSentenceStart: (Int) -> Unit, onDone: () -> Unit) {
        val cleaned = sentences.map(::normalizeRussianSpeech)
        if (cleaned.all { it.isBlank() }) {
            onDone()
            return
        }
        if (!ready) {
            // Queue the joined text. There is no reliable sentence-boundary callback
            // before TTS initialization, so finish the visual sequence immediately.
            speak(cleaned.joinToString(" "))
            onDone()
            return
        }
        sequenceOnSentence = onSentenceStart
        sequenceOnDone = onDone
        sequenceLast = cleaned.indices.last { cleaned[it].isNotBlank() }
        selectRussianVoice((System.currentTimeMillis() / 86_400_000L).toInt())
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
        var firstQueued = true
        cleaned.forEachIndexed { index, sentence ->
            if (sentence.isBlank()) return@forEachIndexed
            engine?.speak(
                sentence,
                if (firstQueued) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
                null,
                index.toString()
            )
            firstQueued = false
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

    /** Android engines may expose several voices for ru-RU. Prefer downloaded,
     * offline voices and rotate among them; never select a network-only voice in
     * this offline app. Voice names/gender are engine-specific and not standardized. */
    private fun selectRussianVoice(variant: Int) {
        val tts = engine ?: return
        val voicesByName = tts.voices.orEmpty().associateBy { it.name }
        val names = rankedRussianVoiceNames(voicesByName.values.map { voice ->
            TtsVoiceDescriptor(
                name = voice.name,
                language = voice.locale.language,
                country = voice.locale.country,
                quality = voice.quality,
                latency = voice.latency,
                requiresNetwork = voice.isNetworkConnectionRequired
            )
        })
        if (names.isNotEmpty()) tts.voice = voicesByName.getValue(names[Math.floorMod(variant, names.size)])
    }

    fun shutdown() {
        finishSequence()
        mainHandler.removeCallbacksAndMessages(null)
        engine?.shutdown()
        engine = null
        ready = false
    }

    private fun String.cleanForSpeech(): String =
        replace("\u0301", "")
            .replace(Regex("_{3,}"), " ")
            .dropNonRussianWords()
            .replace(Regex("\\s+"), " ")
            .trim()

    /**
     * Drop any whitespace-separated token that contains a Latin letter, so the
     * ru-RU engine never tries to pronounce English (e.g. a translation or a
     * parenthetical gloss that leaked into the text). Cyrillic words, digits, and
     * punctuation are kept; a fully-English string becomes empty (silent), which is
     * preferable to mangled English.
     */
    private fun String.dropNonRussianWords(): String =
        split(Regex("\\s+"))
            .filterNot { token -> token.any { it in 'a'..'z' || it in 'A'..'Z' } }
            .joinToString(" ")

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

internal data class TtsVoiceDescriptor(
    val name: String,
    val language: String,
    val country: String,
    val quality: Int,
    val latency: Int,
    val requiresNetwork: Boolean
)

/** Engine-neutral policy, kept pure so voice selection is testable off-device. */
internal fun rankedRussianVoiceNames(voices: Collection<TtsVoiceDescriptor>): List<String> = voices
    .asSequence()
    .filter { it.language.equals("ru", ignoreCase = true) && !it.requiresNetwork }
    .sortedWith(compareByDescending<TtsVoiceDescriptor> { it.quality }.thenBy { it.latency }.thenBy { it.name })
    .map { it.name }
    .toList()

/** Removes stress marks and Latin glosses before text reaches a Russian TTS engine. */
internal fun normalizeRussianSpeech(input: String): String =
    input.replace("\u0301", "")
        .replace(Regex("_{3,}"), " ")
        .split(Regex("\\s+"))
        .filterNot { token -> token.any { it in 'a'..'z' || it in 'A'..'Z' } }
        .joinToString(" ")
        .replace(Regex("\\s+"), " ")
        .trim()

internal fun chunkRussianSpeech(input: String, maxLength: Int = 3500): List<String> {
    if (input.isBlank()) return emptyList()
    if (input.length <= maxLength) return listOf(input)
    val chunks = mutableListOf<String>()
    var remaining = input
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
