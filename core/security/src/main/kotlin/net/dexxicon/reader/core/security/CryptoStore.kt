package net.dexxicon.reader.core.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AES-GCM encryption backed by a non-exportable AndroidKeyStore key. Produces a single
 * opaque Base64 string ("iv:ciphertext") that callers persist wherever they like.
 */
@Singleton
class CryptoStore @Inject constructor() {

    private val keyStore: KeyStore =
        KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

    private val secretKey: SecretKey
        get() = (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
            ?: generateKey()

    private fun generateKey(): SecretKey {
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEY_STORE,
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return encode(iv) + SEPARATOR + encode(cipherText)
    }

    fun decrypt(payload: String): String {
        val (ivPart, cipherPart) = payload.split(SEPARATOR, limit = 2)
            .takeIf { it.size == 2 }
            ?: error("Malformed encrypted payload")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey,
            GCMParameterSpec(GCM_TAG_LENGTH_BITS, decode(ivPart)),
        )
        return String(cipher.doFinal(decode(cipherPart)), Charsets.UTF_8)
    }

    private fun encode(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun decode(text: String): ByteArray =
        Base64.decode(text, Base64.NO_WRAP)

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val KEY_ALIAS = "dexxicon.credentials.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_LENGTH_BITS = 128
        const val SEPARATOR = ":"
    }
}
