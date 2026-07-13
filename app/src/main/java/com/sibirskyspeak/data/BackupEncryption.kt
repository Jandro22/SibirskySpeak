package com.sibirskyspeak.data

import android.content.Context
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

/**
 * Password-based encryption for backups that leave the app's private storage.
 * The payload is authenticated with AES-GCM; the password is never written to
 * the backup itself. A fresh salt and IV are used for every mirror snapshot.
 */
internal object BackupEncryptionCodec {
    private const val MAGIC = "SIBIRSKYSPEAK_ENCRYPTED_BACKUP"
    private const val FORMAT = 2
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val KEY_BITS = 256
    private const val PBKDF2_ROUNDS = 120_000

    fun encrypt(plain: ByteArray, password: String, recoveryKey: String? = null): ByteArray {
        require(password.length >= 8) { "Backup password must be at least 8 characters" }
        val random = SecureRandom()
        val dataKeyBytes = ByteArray(KEY_BITS / 8).also(random::nextBytes)
        val dataKey = SecretKeySpec(dataKeyBytes, "AES")
        val dataIv = ByteArray(IV_BYTES).also(random::nextBytes)
        val dataCipher = Cipher.getInstance("AES/GCM/NoPadding")
        dataCipher.init(Cipher.ENCRYPT_MODE, dataKey, GCMParameterSpec(128, dataIv))
        val encrypted = dataCipher.doFinal(plain)
        val passwordSalt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val passwordWrapIv = ByteArray(IV_BYTES).also(random::nextBytes)
        val passwordWrap = wrap(dataKeyBytes, deriveKey(password, passwordSalt), passwordWrapIv)
        val header = JSONObject()
            .put("magic", MAGIC)
            .put("format", FORMAT)
            .put("dataIv", Base64.encodeToString(dataIv, Base64.NO_WRAP))
            .put("passwordSalt", Base64.encodeToString(passwordSalt, Base64.NO_WRAP))
            .put("passwordWrapIv", Base64.encodeToString(passwordWrapIv, Base64.NO_WRAP))
            .put("passwordWrap", Base64.encodeToString(passwordWrap, Base64.NO_WRAP))
        if (!recoveryKey.isNullOrBlank()) {
            val recoverySalt = ByteArray(SALT_BYTES).also(random::nextBytes)
            val recoveryWrapIv = ByteArray(IV_BYTES).also(random::nextBytes)
            val recoveryWrap = wrap(dataKeyBytes, deriveKey(recoveryKey, recoverySalt), recoveryWrapIv)
            header.put("recoverySalt", Base64.encodeToString(recoverySalt, Base64.NO_WRAP))
                .put("recoveryWrapIv", Base64.encodeToString(recoveryWrapIv, Base64.NO_WRAP))
                .put("recoveryWrap", Base64.encodeToString(recoveryWrap, Base64.NO_WRAP))
        }
        val headerBytes = header
            .toString()
            .toByteArray(StandardCharsets.UTF_8)
        return ByteArrayOutputStream(headerBytes.size + encrypted.size + 1).apply {
            write(headerBytes)
            write('\n'.code)
            write(encrypted)
        }.toByteArray()
    }

    fun decrypt(payload: ByteArray, password: String): ByteArray {
        val separator = payload.indexOf('\n'.code.toByte())
        require(separator > 0) { "Encrypted backup header is missing" }
        val header = JSONObject(String(payload, 0, separator, StandardCharsets.UTF_8))
        require(header.optString("magic") == MAGIC && header.optInt("format") == FORMAT) {
            "Unsupported encrypted backup format"
        }
        val dataIv = Base64.decode(header.getString("dataIv"), Base64.DEFAULT)
        require(dataIv.size == IV_BYTES) { "Invalid encrypted backup parameters" }
        val dataKeyBytes = runCatching {
            unwrap(
                Base64.decode(header.getString("passwordWrap"), Base64.DEFAULT),
                deriveKey(password, Base64.decode(header.getString("passwordSalt"), Base64.DEFAULT)),
                Base64.decode(header.getString("passwordWrapIv"), Base64.DEFAULT)
            )
        }.recoverCatching {
            val recoverySalt = Base64.decode(header.getString("recoverySalt"), Base64.DEFAULT)
            unwrap(
                Base64.decode(header.getString("recoveryWrap"), Base64.DEFAULT),
                deriveKey(password, recoverySalt),
                Base64.decode(header.getString("recoveryWrapIv"), Base64.DEFAULT)
            )
        }.getOrElse { throw IllegalArgumentException("Incorrect backup password or recovery key", it) }
        require(dataKeyBytes.size == KEY_BITS / 8) { "Invalid encrypted backup key" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(dataKeyBytes, "AES"), GCMParameterSpec(128, dataIv))
        return cipher.doFinal(payload.copyOfRange(separator + 1, payload.size))
    }

    private fun wrap(value: ByteArray, key: SecretKey, iv: ByteArray): ByteArray =
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            doFinal(value)
        }

    private fun unwrap(value: ByteArray, key: SecretKey, iv: ByteArray): ByteArray =
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            doFinal(value)
        }

    private fun deriveKey(password: String, salt: ByteArray): SecretKey {
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ROUNDS, KEY_BITS)
        return try {
            // SHA-256 is available on current Android releases; the SHA-1
            // fallback keeps encrypted mirrors usable on older API-26 providers.
            val factory = runCatching { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256") }
                .getOrElse { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1") }
            SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }
}

/**
 * Stores the automatic-mirror credential wrapped by an Android Keystore key.
 * The wrapped values are deliberately excluded from portable JSON backups: a
 * different device has a different Keystore and must use the password/recovery
 * key supplied by the learner instead.
 */
internal class BackupSecretStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val random = SecureRandom()

    fun setPassword(password: String) {
        require(password.length >= 8) { "Backup password must be at least 8 characters" }
        put(SECRET_PASSWORD, password)
    }

    fun password(): String? = get(SECRET_PASSWORD)

    fun recoveryKey(): String? = get(SECRET_RECOVERY)

    fun ensureRecoveryKey(): String {
        recoveryKey()?.let { return it }
        val bytes = ByteArray(18).also(random::nextBytes)
        val value = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            .chunked(6).joinToString("-").uppercase()
        put(SECRET_RECOVERY, value)
        return value
    }

    fun clear() {
        // Clearing is an explicit security action; commit so the credential is
        // definitely gone before the caller reports encryption as disabled.
        prefs.edit()
            .remove("${SECRET_PASSWORD}_cipher")
            .remove("${SECRET_PASSWORD}_iv")
            .remove("${SECRET_RECOVERY}_cipher")
            .remove("${SECRET_RECOVERY}_iv")
            .commit()
    }

    private fun put(name: String, value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        prefs.edit()
            .putString("${name}_cipher", Base64.encodeToString(cipher.doFinal(value.toByteArray()), Base64.NO_WRAP))
            .putString("${name}_iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
    }

    private fun get(name: String): String? = runCatching {
        val encoded = prefs.getString("${name}_cipher", null) ?: return null
        val iv = Base64.decode(prefs.getString("${name}_iv", null) ?: return null, Base64.DEFAULT)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        String(cipher.doFinal(Base64.decode(encoded, Base64.DEFAULT)), StandardCharsets.UTF_8)
    }.getOrNull()

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
            init(android.security.keystore.KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(false)
                .build())
        }.generateKey()
    }

    private companion object {
        const val PREFS = "sibirsky_backup_secrets"
        const val KEY_ALIAS = "sibirsky_backup_secret_v1"
        const val SECRET_PASSWORD = "password"
        const val SECRET_RECOVERY = "recovery"
    }
}
