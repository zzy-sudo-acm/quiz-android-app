package com.zzy.quizforge.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.zzy.quizforge.data.remote.ModelTier

class SettingsStore(context: Context) {
    private val TAG = "SettingsStore"
    private val fallbackPrefs = context.getSharedPreferences("quizforge_settings_fallback", Context.MODE_PRIVATE)

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

    /** 当前是否可使用加密存储；false 时只允许保存非敏感设置。 */
    val isEncrypted: Boolean = encryptedResult.isSuccess

    private val prefs: SharedPreferences = encryptedResult
        .onFailure { error ->
            Log.w(TAG, "EncryptedSharedPreferences 初始化失败，安全存储不可用", error)
        }
        .getOrElse {
            fallbackPrefs
        }

    init {
        // Old releases could write the API key to the fallback preferences when the Keystore
        // was unavailable. Remove that plaintext residue even after encrypted storage recovers.
        if (fallbackPrefs.contains(KEY_DEEPSEEK_API_KEY)) {
            fallbackPrefs.edit().remove(KEY_DEEPSEEK_API_KEY).commit()
            Log.w(TAG, "已清除旧版本遗留的明文 API Key")
        }
        if (!isEncrypted) {
            // Fallback stores only non-sensitive settings. API keys are never written here.
            prefs.edit().remove(KEY_DEEPSEEK_API_KEY).commit()
        }
    }

    /**
     * 返回当前 API Key。
     *
     * 安全策略：
     * - [isEncrypted] == true  → 返回加密存储中保存的 Key
     * - [isEncrypted] == false → 始终返回空字符串，绝不复用历史明文 Key
     */
    fun getApiKey(): String = if (isEncrypted) {
        runCatching { prefs.getString(KEY_DEEPSEEK_API_KEY, "").orEmpty() }
            .onFailure { Log.w(TAG, "加密 API Key 读取失败，按未配置处理", it) }
            .getOrDefault("")
    } else ""

    fun saveApiKey(value: String) {
        if (!isEncrypted) return
        val trimmed = value.trim()
        if (trimmed.isEmpty()) {
            deleteApiKey()
            return
        }
        prefs.edit().putString(KEY_DEEPSEEK_API_KEY, trimmed).apply()
    }

    fun deleteApiKey() {
        prefs.edit().remove(KEY_DEEPSEEK_API_KEY).apply()
    }

    fun getModelTier(): ModelTier = runCatching {
        ModelTier.fromStorage(prefs.getString(KEY_MODEL_TIER, null))
    }.onFailure {
        Log.w(TAG, "模型档位读取失败，使用默认档位", it)
    }.getOrDefault(ModelTier.QUICK)

    fun saveModelTier(tier: ModelTier) {
        prefs.edit().putString(KEY_MODEL_TIER, tier.storageValue).apply()
    }

    companion object {
        private const val KEY_DEEPSEEK_API_KEY = "deepseek_api_key"
        private const val KEY_MODEL_TIER = "model_tier"
    }
}
