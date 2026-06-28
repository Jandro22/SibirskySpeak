package com.sibirskyspeak.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/** Warm, synthesized earcons with an amplitude envelope instead of telephone tones. */
class AnswerSoundEffects {
    private val correct = earcon(
        durationMs = 280,
        notes = listOf(0 to 523.25, 85 to 659.25, 165 to 783.99),
        volume = 0.28
    )
    private val incorrect = earcon(
        durationMs = 310,
        notes = listOf(0 to 246.94, 125 to 196.00),
        volume = 0.22
    )

    @Synchronized
    fun play(correct: Boolean) {
        val track = if (correct) this.correct else incorrect
        runCatching {
            if (track.playState != AudioTrack.PLAYSTATE_STOPPED) track.stop()
            track.setPlaybackHeadPosition(0)
            track.play()
        }
    }

    fun release() {
        correct.release()
        incorrect.release()
    }

    private fun earcon(durationMs: Int, notes: List<Pair<Int, Double>>, volume: Double): AudioTrack {
        val sampleRate = 44_100
        val count = durationMs * sampleRate / 1_000
        val samples = ShortArray(count)
        for ((startMs, frequency) in notes) {
            val start = startMs * sampleRate / 1_000
            val noteLength = minOf(count - start, 155 * sampleRate / 1_000)
            for (i in 0 until noteLength) {
                val seconds = i.toDouble() / sampleRate
                val attack = (i / (0.012 * sampleRate)).coerceIn(0.0, 1.0)
                val release = exp(-4.2 * i / noteLength.toDouble())
                val fundamental = sin(2.0 * PI * frequency * seconds)
                val overtone = 0.18 * sin(2.0 * PI * frequency * 2.0 * seconds)
                val mixed = samples[start + i] / Short.MAX_VALUE.toDouble() +
                    (fundamental + overtone) * attack * release * volume
                samples[start + i] = (mixed.coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort()
            }
        }
        return AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build())
            .setBufferSizeInBytes(samples.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
            .also { it.write(samples, 0, samples.size) }
    }
}
