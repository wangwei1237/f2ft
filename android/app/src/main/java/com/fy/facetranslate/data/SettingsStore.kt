package com.fy.facetranslate.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class AppSettings(
    val apiKey: String = "",
    val model: String = DEFAULT_MODEL,
    val voice: String = DEFAULT_VOICE,
    val silenceMs: Int = DEFAULT_SILENCE_MS,
    val ownerLanguage: Language = Language.ZH,
) {
    companion object {
        const val DEFAULT_MODEL = "gemini-3.8-live"
        const val DEFAULT_VOICE = "Kore"
        const val DEFAULT_SILENCE_MS = 800

        /** Live API models that can be picked in the settings page; any other model ID may be typed in. */
        val PRESET_MODELS = listOf(
            "gemini-3.8-live",
            "gemini-3.1-flash-live-preview",
            "gemini-3.5-live-translate-preview",
            "gemini-2.5-flash-native-audio-preview-12-2025",
        )
        val PRESET_VOICES = listOf("Kore", "Puck", "Aoede", "Charon", "Zephyr")
    }
}

/**
 * Persists settings in private SharedPreferences. The API key is encrypted with an
 * AES-GCM key held in the Android Keystore, so it never sits on disk in plain text.
 */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(): AppSettings = AppSettings(
        apiKey = prefs.getString(KEY_API, null)?.let { decrypt(it) }.orEmpty(),
        model = prefs.getString(KEY_MODEL, null) ?: AppSettings.DEFAULT_MODEL,
        voice = prefs.getString(KEY_VOICE, null) ?: AppSettings.DEFAULT_VOICE,
        silenceMs = prefs.getInt(KEY_SILENCE, AppSettings.DEFAULT_SILENCE_MS),
        ownerLanguage = runCatching { Language.valueOf(prefs.getString(KEY_OWNER, "ZH")!!) }
            .getOrDefault(Language.ZH),
    )

    fun save(settings: AppSettings) {
        prefs.edit()
            .putString(KEY_API, if (settings.apiKey.isBlank()) null else encrypt(settings.apiKey.trim()))
            .putString(KEY_MODEL, settings.model.trim().ifEmpty { AppSettings.DEFAULT_MODEL })
            .putString(KEY_VOICE, settings.voice.trim().ifEmpty { AppSettings.DEFAULT_VOICE })
            .putInt(KEY_SILENCE, settings.silenceMs.coerceIn(300, 3000))
            .putString(KEY_OWNER, settings.ownerLanguage.name)
            .apply()
    }

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + body, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String? = runCatching {
        val bytes = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, bytes, 0, IV_LEN))
        String(cipher.doFinal(bytes, IV_LEN, bytes.size - IV_LEN), Charsets.UTF_8)
    }.getOrNull()

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "facetranslate_api_key"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_LEN = 12
        const val KEY_API = "api_key_enc"
        const val KEY_MODEL = "model"
        const val KEY_VOICE = "voice"
        const val KEY_SILENCE = "silence_ms"
        const val KEY_OWNER = "owner_language"
    }
}
