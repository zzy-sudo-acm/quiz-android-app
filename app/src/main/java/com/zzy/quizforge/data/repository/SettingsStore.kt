package com.zzy.quizforge.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SettingsStore(context: Context) {
    private val TAG = "SettingsStore"

    private val encryptedResult: Result<SharedPreferences> = runCatching {
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
    }

    /** 当前是否使用加密存储。false 表示已回退到明文 SharedPreferences。 */
    val isEncrypted: Boolean = encryptedResult.isSuccess

    private val prefs: SharedPreferences = encryptedResult
        .onFailure { error ->
            Log.w(TAG, "EncryptedSharedPreferences 初始化失败，安全存储不可用", error)
        }
        .getOrElse {
            context.getSharedPreferences("quizforge_settings_fallback", Context.MODE_PRIVATE)
        }

    init {
        if (!isEncrypted) {
            // 清理旧版本可能在 fallback 中遗留的明文 API Key
            val hadLegacyKey = prefs.contains(KEY_DEEPSEEK_API_KEY)
            if (hadLegacyKey) {
                prefs.edit().remove(KEY_DEEPSEEK_API_KEY).apply()
                Log.w(TAG, "已清除历史明文存储的 API Key")
            }
        }
    }

    /**
     * 返回当前 API Key。
     *
     * 安全策略：
     * - [isEncrypted] == true  → 返回加密存储中保存的 Key
     * - [isEncrypted] == false → 始终返回空字符串，绝不复用历史明文 Key
     */
    fun getApiKey(): String =
        if (isEncrypted) prefs.getString(KEY_DEEPSEEK_API_KEY, "").orEmpty()
        else ""

    private val _apiKey = MutableStateFlow(getApiKey())
    val apiKey: StateFlow<String> = _apiKey.asStateFlow()

    fun saveApiKey(value: String) {
        if (!isEncrypted) return
        prefs.edit().putString(KEY_DEEPSEEK_API_KEY, value.trim()).apply()
        _apiKey.value = value.trim()
    }

    companion object {
        private const val KEY_DEEPSEEK_API_KEY = "deepseek_api_key"
    }
}
