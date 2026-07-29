package com.zzy.quizforge.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zzy.quizforge.data.repository.QuizRepository
import com.zzy.quizforge.data.repository.SettingsStore
import com.zzy.quizforge.data.repository.ImportRepository
import com.zzy.quizforge.data.remote.ModelTier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val apiKey: String = "",
    val savedMessage: String? = null,
    val isClearing: Boolean = false,
    val isEncrypted: Boolean = true,
    val modelTier: ModelTier = ModelTier.QUICK,
    val isTestingConnection: Boolean = false,
)

class SettingsViewModel(
    private val settingsStore: SettingsStore,
    private val quizRepository: QuizRepository,
    private val importRepository: ImportRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        SettingsUiState(
            apiKey = settingsStore.getApiKey(),
            isEncrypted = settingsStore.isEncrypted,
            modelTier = settingsStore.getModelTier(),
        ),
    )
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    fun updateApiKey(value: String) {
        _uiState.update { it.copy(apiKey = value, savedMessage = null) }
    }

    /**
     * 保存 API Key。
     *
     * 安全策略：
     * - 加密存储可用时正常保存
     * - 加密不可用时（[SettingsStore.isEncrypted] == false），UI 层禁止调用此方法；
     *   如果因竞态条件仍然到达此处，拒绝保存并设置错误消息
     */
    fun saveApiKey() {
        if (!settingsStore.isEncrypted) {
            _uiState.update {
                it.copy(savedMessage = "安全存储不可用，为保护 API Key 已拒绝保存")
            }
            return
        }
        if (_uiState.value.apiKey.isBlank()) {
            _uiState.update { it.copy(savedMessage = "请输入 API Key") }
            return
        }
        settingsStore.saveApiKey(_uiState.value.apiKey)
        _uiState.update { it.copy(savedMessage = "API Key 已保存（加密存储）") }
    }

    fun deleteApiKey() {
        settingsStore.deleteApiKey()
        _uiState.update { it.copy(apiKey = "", savedMessage = "API Key 已删除") }
    }

    fun selectModelTier(tier: ModelTier) {
        settingsStore.saveModelTier(tier)
        _uiState.update { it.copy(modelTier = tier, savedMessage = "已切换为${tier.label}") }
    }

    fun testConnection() {
        val key = _uiState.value.apiKey.trim()
        if (key.isBlank()) {
            _uiState.update { it.copy(savedMessage = "请先输入 API Key") }
            return
        }
        if (!settingsStore.isEncrypted) {
            _uiState.update { it.copy(savedMessage = "安全存储不可用，无法测试") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isTestingConnection = true, savedMessage = null) }
            importRepository.testModelConnection(key)
                .onSuccess {
                    _uiState.update { it.copy(isTestingConnection = false, savedMessage = "连接成功；测试请求未上传题库") }
                }
                .onFailure { error ->
                    val raw = error.message.orEmpty()
                    val message = when {
                        "401" in raw || "403" in raw -> "认证失败，请检查 API Key"
                        "402" in raw -> "账户余额不足，请到模型平台充值"
                        "429" in raw -> "请求过于频繁，请稍后重试"
                        "HTTP" in raw -> "模型服务暂时不可用：$raw"
                        else -> "网络连接失败：${raw.ifBlank { "请检查网络" }}"
                    }
                    _uiState.update { it.copy(isTestingConnection = false, savedMessage = message) }
                }
        }
    }

    fun clearAllData() {
        viewModelScope.launch {
            _uiState.update { it.copy(isClearing = true, savedMessage = null) }
            runCatching { quizRepository.clearAllData() }
                .onSuccess { _uiState.update { it.copy(isClearing = false, savedMessage = "已清除数据，并重新导入预置题库") } }
                .onFailure { e ->
                    val message = e.message ?: "清除失败：未知错误"
                    _uiState.update {
                        it.copy(
                            isClearing = false,
                            savedMessage = if (message.startsWith("数据已清除")) message else "清除失败：$message",
                        )
                    }
                }
        }
    }
}
