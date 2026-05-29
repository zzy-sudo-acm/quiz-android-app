package com.zzy.quizforge.data.repository

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SettingsStore(context: Context) {
    private val prefs: SharedPreferences = runCatching {
        val key = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "quizforge_secure_settings",
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrElse {
        context.getSharedPreferences("quizforge_settings_fallback", Context.MODE_PRIVATE)
    }

    private val _apiKey = MutableStateFlow(prefs.getString(KEY_DEEPSEEK_API_KEY, "").orEmpty())
    val apiKey: StateFlow<String> = _apiKey.asStateFlow()

    fun getApiKey(): String = _apiKey.value

    fun saveApiKey(value: String) {
        prefs.edit().putString(KEY_DEEPSEEK_API_KEY, value.trim()).apply()
        _apiKey.value = value.trim()
    }

    companion object {
        private const val KEY_DEEPSEEK_API_KEY = "deepseek_api_key"
    }
}
