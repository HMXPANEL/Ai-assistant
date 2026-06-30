package com.voicecontrol.app.security

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object SecureKeyStore {

    private const val TAG = "SecureKeyStore"
    private const val PREFS_NAME = "voicecontrol_secure"

    private const val KEY_GEMINI_API_KEY = "gemini_api_key"

    private fun getEncryptedPrefs(context: Context) = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        Log.e(TAG, "Failed to create EncryptedSharedPreferences", e)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun saveGeminiApiKey(context: Context, apiKey: String) {
        getEncryptedPrefs(context).edit().putString(KEY_GEMINI_API_KEY, apiKey).apply()
    }

    fun getGeminiApiKey(context: Context): String? {
        return getEncryptedPrefs(context).getString(KEY_GEMINI_API_KEY, null)
    }

    fun hasGeminiApiKey(context: Context): Boolean = !getGeminiApiKey(context).isNullOrEmpty()

    fun clearGeminiApiKey(context: Context) {
        getEncryptedPrefs(context).edit().remove(KEY_GEMINI_API_KEY).apply()
    }
}
