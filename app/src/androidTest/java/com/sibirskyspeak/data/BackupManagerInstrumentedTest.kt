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
    @Test fun tamperedLatestGenerationFallsBackToPreviousValidatedCopy() {
        context.filesDir.resolve("backups").deleteRecursively()
        val manager = BackupManager(context)
        manager.write("""{"russian":"first","lemma":"first","translation":"first","pos":"adj"}""")
        manager.write("""{"russian":"second","lemma":"second","translation":"second","pos":"adj"}""")
        val latest = context.filesDir.resolve("backups").resolve("full_state_latest.jsonl")
        latest.appendText("\n{\"russian\":\"tampered\"}\n")
        assertTrue(manager.read().orEmpty().contains("\"lemma\":\"first\""))
    }

    @Test fun publicMirrorCanBeDisabledWithoutDisablingLocalValidation() {
        context.filesDir.resolve("backups").deleteRecursively()
        context.getSharedPreferences("sibirsky_settings", Context.MODE_PRIVATE).edit()
            .putBoolean("automatic_public_backup_enabled", false)
            .remove("backup_last_saf_at")
            .commit()
        val manager = BackupManager(context)
        manager.write("""{"russian":"private","lemma":"private","translation":"private","pos":"noun"}""")
        assertTrue(manager.read().orEmpty().contains("\"lemma\":\"private\""))
        assertTrue(context.getSharedPreferences("sibirsky_settings", Context.MODE_PRIVATE).getLong("backup_last_saf_at", 0L) == 0L)
        context.getSharedPreferences("sibirsky_settings", Context.MODE_PRIVATE).edit()
            .putBoolean("automatic_public_backup_enabled", true).commit()
    }

    @Test fun encryptedExternalBackupRoundTripsAndRejectsTampering() {
        val manager = BackupManager(context)
        val secretPrefs = context.getSharedPreferences("sibirsky_backup_secrets", Context.MODE_PRIVATE)
        secretPrefs.edit().clear().commit()
        val recovery = manager.configureExternalEncryption("correct horse battery")
        assertTrue(recovery.length >= 20)
        assertTrue(manager.externalEncryptionConfigured())
        val plain = "{\"lemma\":\"house\"}\n".toByteArray()
        val encrypted = BackupEncryptionCodec.encrypt(plain, "correct horse battery")
        assertTrue(!encrypted.contentEquals(plain))
        assertTrue(manager.decryptExternalBackup(encrypted, "correct horse battery").contentEquals(plain))
        val withRecovery = BackupEncryptionCodec.encrypt(plain, "correct horse battery", recovery)
        assertTrue(manager.decryptExternalBackup(withRecovery, recovery).contentEquals(plain))
        runCatching { manager.decryptExternalBackup(encrypted, "wrong password") }
            .onSuccess { error("wrong password unexpectedly decrypted a backup") }
        val tampered = encrypted.copyOf().also { it[it.lastIndex] = (it[it.lastIndex].toInt() xor 1).toByte() }
        runCatching { manager.decryptExternalBackup(tampered, "correct horse battery") }
            .onSuccess { error("tampered backup unexpectedly decrypted") }
        manager.clearExternalEncryption()
        assertTrue(!manager.externalEncryptionConfigured())
    }

    @Test fun encryptRejectsAPasswordShorterThanEightCharacters() {
        val plain = "{\"lemma\":\"house\"}\n".toByteArray()
        runCatching { BackupEncryptionCodec.encrypt(plain, "short") }
            .onSuccess { error("a 5-character password unexpectedly passed the minimum-length check") }
    }

    @Test fun decryptRejectsAPayloadWithNoHeaderSeparator() {
        runCatching { BackupEncryptionCodec.decrypt("no newline anywhere in this payload".toByteArray(), "correct horse battery") }
            .onSuccess { error("a payload with no header separator unexpectedly decrypted") }
    }

    @Test fun decryptRejectsAPayloadWithACorruptedOrForeignHeader() {
        val bogusHeader = "{\"magic\":\"NOT_A_SIBIRSKY_BACKUP\",\"format\":1}".toByteArray()
        val payload = bogusHeader + "\n".toByteArray() + "irrelevant ciphertext".toByteArray()
        runCatching { BackupEncryptionCodec.decrypt(payload, "correct horse battery") }
            .onSuccess { error("a payload with a foreign/corrupted magic header unexpectedly decrypted") }
    }
}
