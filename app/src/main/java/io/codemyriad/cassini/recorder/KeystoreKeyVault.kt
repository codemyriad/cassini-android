package io.codemyriad.cassini.recorder

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * [KeyVault] over the Android Keystore: an AES-256-GCM key that never leaves the Keystore encrypts the recorder's
 * binding key. The stored form is Base64 of the 12-byte IV and the ciphertext with its tag. Nothing here logs a key.
 */
class KeystoreKeyVault(private val alias: String = ALIAS) : KeyVault {
    override fun wrap(key: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        if (iv.size != IV_BYTES) throw GeneralSecurityException("Unexpected IV length")
        return Base64.getEncoder().encodeToString(iv + cipher.doFinal(key))
    }

    override fun unwrap(wrapped: String): ByteArray {
        val bytes = try {
            Base64.getDecoder().decode(wrapped)
        } catch (error: IllegalArgumentException) {
            throw GeneralSecurityException("The wrapped key is not Base64", error)
        }
        if (bytes.size <= IV_BYTES) throw GeneralSecurityException("The wrapped key is too short")
        val key = existingKey() ?: throw GeneralSecurityException("The Keystore key is gone")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
        return cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES)
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    private fun existingKey(): SecretKey? = keyStore().getKey(alias, null) as? SecretKey

    private fun secretKey(): SecretKey = existingKey() ?: newKey()

    private fun newKey(): SecretKey {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_BITS)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).apply { init(spec) }.generateKey()
    }

    private companion object {
        const val ALIAS = "cassini.recorder.binding"
        const val PROVIDER = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        const val KEY_BITS = 256
    }
}
