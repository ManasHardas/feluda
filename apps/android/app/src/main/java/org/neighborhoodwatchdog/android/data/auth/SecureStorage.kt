package org.neighborhoodwatchdog.android.data.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.neighborhoodwatchdog.android.domain.auth.AuthTokens
import org.neighborhoodwatchdog.android.domain.auth.TokenStore
import org.neighborhoodwatchdog.android.domain.model.WatchdogJson
import java.io.File
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM with a key that lives in the Android Keystore and never leaves it.
 * Output layout: 12-byte nonce followed by ciphertext and tag.
 */
class KeystoreCipher(private val alias: String = "watchdog_local_store_v1") {

    @Synchronized
    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return cipher.iv + cipher.doFinal(plain)
    }

    @Throws(GeneralSecurityException::class)
    fun decrypt(blob: ByteArray): ByteArray {
        if (blob.size <= NONCE_BYTES) throw GeneralSecurityException("Encrypted value is too short")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, blob, 0, NONCE_BYTES))
        return cipher.doFinal(blob, NONCE_BYTES, blob.size - NONCE_BYTES)
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val NONCE_BYTES = 12
        const val TAG_BITS = 128
    }
}

/** Small encrypted files in app-private storage (`files/secure/`). */
class EncryptedFileStore(context: Context, private val cipher: KeystoreCipher) {
    private val directory = File(context.filesDir, "secure")

    @Synchronized
    fun read(name: String): ByteArray? {
        val file = AtomicFile(File(directory, name))
        if (!file.baseFile.exists()) return null
        return try {
            cipher.decrypt(file.readFully())
        } catch (_: GeneralSecurityException) {
            // The key was lost (for example after a restore to another device): the value is gone.
            file.delete()
            null
        } catch (_: java.io.IOException) {
            null
        }
    }

    @Synchronized
    fun write(name: String, plain: ByteArray) {
        directory.mkdirs()
        val file = AtomicFile(File(directory, name))
        val stream = file.startWrite()
        try {
            stream.write(cipher.encrypt(plain))
            file.finishWrite(stream)
        } catch (e: Exception) {
            file.failWrite(stream)
            throw e
        }
    }

    @Synchronized
    fun delete(name: String) {
        AtomicFile(File(directory, name)).delete()
    }
}

class EncryptedTokenStore(private val files: EncryptedFileStore) : TokenStore {
    @Volatile private var cached: AuthTokens? = null
    @Volatile private var loaded = false

    @Synchronized
    override fun load(): AuthTokens? {
        if (!loaded) {
            cached = files.read(FILE)?.let { bytes ->
                runCatching { WatchdogJson.decodeFromString(AuthTokens.serializer(), bytes.decodeToString()) }.getOrNull()
            }
            loaded = true
        }
        return cached
    }

    @Synchronized
    override fun save(tokens: AuthTokens) {
        files.write(FILE, WatchdogJson.encodeToString(AuthTokens.serializer(), tokens).encodeToByteArray())
        cached = tokens
        loaded = true
    }

    @Synchronized
    override fun clear() {
        files.delete(FILE)
        cached = null
        loaded = true
    }

    private companion object {
        const val FILE = "session.bin"
    }
}
