package com.sibirskyspeak.data

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupManagerInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun interruptedStreamingWriteKeepsLastValidatedGeneration() {
        val manager = BackupManager(context)
        val first = """{"russian":"дом","lemma":"дом","translation":"house","pos":"noun"}"""
        manager.write(first)
        runCatching {
            manager.writeLines(sequence {
                yield("""{"russian":"мир","lemma":"мир","translation":"world","pos":"noun"}""")
                error("injected interruption")
            })
        }
        assertTrue(manager.read().orEmpty().contains("\"lemma\":\"дом\""))
    }

    @Test fun revokedSafGrantCannotInvalidateSuccessfulLocalBackup() {
        context.getSharedPreferences("sibirsky_settings", Context.MODE_PRIVATE).edit()
            .putString("backup_tree_uri", "content://invalid/revoked").commit()
        val manager = BackupManager(context)
        manager.write("""{"russian":"тест","lemma":"тест","translation":"test","pos":"noun"}""")
        assertTrue(manager.read().orEmpty().contains("\"lemma\":\"тест\""))
    }
}
