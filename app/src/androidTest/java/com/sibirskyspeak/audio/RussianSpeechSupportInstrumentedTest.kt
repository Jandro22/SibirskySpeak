package com.sibirskyspeak.audio

import android.content.Intent
import android.os.Build
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class RussianSpeechSupportInstrumentedTest {
    @Test
    fun deviceRussianModelSupportCanBeClassifiedOrDegradesToCapabilityError() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(RussianSpeechRecognizer.isAvailable(context))
        val latch = CountDownLatch(1)
        val state = AtomicReference<RussianModelState?>()
        val capabilityError = AtomicInteger(Int.MIN_VALUE)
        val recognizer = AtomicReference<SpeechRecognizer>()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }

        instrumentation.runOnMainSync {
            val engine = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            recognizer.set(engine)
            engine.checkRecognitionSupport(intent, context.mainExecutor, object : RecognitionSupportCallback {
                override fun onSupportResult(support: RecognitionSupport) {
                    state.set(russianModelState(
                        support.installedOnDeviceLanguages,
                        support.pendingOnDeviceLanguages,
                        support.supportedOnDeviceLanguages
                    ))
                    latch.countDown()
                }

                override fun onError(error: Int) {
                    capabilityError.set(error)
                    latch.countDown()
                }
            })
        }

        assertTrue("speech capability query timed out", latch.await(10, TimeUnit.SECONDS))
        instrumentation.runOnMainSync { recognizer.getAndSet(null)?.destroy() }
        Log.i("RussianSpeechSupport", "state=${state.get()} error=${capabilityError.get()}")
        assertTrue(state.get() != null || capabilityError.get() != Int.MIN_VALUE)
    }
}
