package com.voicecontrol.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class ApiKeyManager(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val sharedPreferences: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        PREFS_FILE,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun saveApiKey(key: String) {
        sharedPreferences.edit().putString(KEY_GEMINI_API, key).apply()
    }

    fun getApiKey(): String? {
        return sharedPreferences.getString(KEY_GEMINI_API, null)
    }

    fun clearApiKey() {
        sharedPreferences.edit().remove(KEY_GEMINI_API).apply()
    }

    companion object {
        private const val PREFS_FILE = "encrypted_prefs"
        private const val KEY_GEMINI_API = "gemini_api_key"
    }
}
