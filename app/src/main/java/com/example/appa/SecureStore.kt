package com.example.appa

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the service configuration encrypted with an Android Keystore-backed
 * AES/GCM key. Secrets never leave the device except to the configured
 * services themselves.
 */
object SecureStore {
    /** True when the stored config had to be wiped (key permanently invalidated). */
    @Volatile var wasWiped: Boolean = false
        private set

    private const val PREFS = "appa_secure"
    private const val KEY_ALIAS = "appa_config_key"
    private const val CONFIG_KEY = "config_enc"

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        ks.getKey(KEY_ALIAS, null)?.let { return it as SecretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + ct, Base64.NO_WRAP)
    }

    private fun decrypt(b64: String): String {
        val raw = Base64.decode(b64, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, raw, 0, 12))
        return String(cipher.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
    }

    fun saveConfig(context: Context, cfg: AppConfig) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putString(CONFIG_KEY, encrypt(cfg.normalized().toJson())).apply()
        wasWiped = false // an explicit save clears the wiped flag
    }

    fun loadConfig(context: Context): AppConfig {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val enc = prefs.getString(CONFIG_KEY, null) ?: return AppConfig()
        return runCatching { AppConfig.fromJson(decrypt(enc)) }.getOrElse {
            // key permanently invalidated (e.g. after backup restore): wipe the
            // stale ciphertext and flag it instead of silently returning empty
            wasWiped = true
            prefs.edit().remove(CONFIG_KEY).apply()
            AppConfig()
        }
    }
}
