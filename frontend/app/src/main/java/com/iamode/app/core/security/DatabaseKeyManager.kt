package com.iamode.app.core.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Generates a random database passphrase once and stores it encrypted with an
 * Android Keystore key that never leaves the device's secure hardware.
 */
@Singleton
class DatabaseKeyManager @Inject constructor(@ApplicationContext private val context: Context) {

    private val prefs = context.getSharedPreferences("iamode_keys", Context.MODE_PRIVATE)

    fun passphrase(): ByteArray {
        val stored = prefs.getString(PREF_DATA, null)
        val iv = prefs.getString(PREF_IV, null)
        if (stored != null && iv != null) {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            return cipher.doFinal(Base64.decode(stored, Base64.NO_WRAP))
        }
        // A new key can't open an old database file, so remove any leftover one first.
        context.deleteDatabase(DB_NAME)
        val passphrase = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keystoreKey())
        prefs.edit()
            .putString(PREF_DATA, Base64.encodeToString(cipher.doFinal(passphrase), Base64.NO_WRAP))
            .putString(PREF_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
        return passphrase
    }

    /** Forgets the stored passphrase and the Keystore key. */
    fun reset() {
        prefs.edit().clear().commit()
        runCatching { KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(ALIAS) }
    }

    private fun keystoreKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "iamode_db_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val PREF_DATA = "db_pass"
        const val PREF_IV = "db_iv"
        const val DB_NAME = "iamode.db"
    }
}
