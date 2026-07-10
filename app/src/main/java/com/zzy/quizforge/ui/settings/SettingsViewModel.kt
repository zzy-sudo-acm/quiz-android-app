package com.zzy.quizforge.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zzy.quizforge.data.repository.QuizRepository
import com.zzy.quizforge.data.repository.SettingsStore
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
)

class SettingsViewModel(
    private val settingsStore: SettingsStore,
    private val quizRepository: QuizRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        SettingsUiState(
            apiKey = settingsStore.getApiKey(),
            isEncrypted = settingsStore.isEncrypted,
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
        settingsStore.saveApiKey(_uiState.value.apiKey)
        _uiState.update { it.copy(savedMessage = "API Key 已保存（加密存储）") }
    }

    fun clearAllData() {
        viewModelScope.launch {
            _uiState.update { it.copy(isClearing = true, savedMessage = null) }
            runCatching { quizRepository.clearAllData() }
                .onSuccess { _uiState.update { it.copy(isClearing = false, savedMessage = "已清除数据，并重新导入预置题库") } }
                .onFailure { e -> _uiState.update { it.copy(isClearing = false, savedMessage = "清除失败：${e.message ?: "未知错误"}") } }
        }
    }
}
