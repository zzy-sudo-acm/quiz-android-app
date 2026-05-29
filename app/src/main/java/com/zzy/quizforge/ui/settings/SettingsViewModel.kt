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
)

class SettingsViewModel(
    private val settingsStore: SettingsStore,
    private val quizRepository: QuizRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(SettingsUiState(apiKey = settingsStore.getApiKey()))
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    fun updateApiKey(value: String) {
        _uiState.update { it.copy(apiKey = value, savedMessage = null) }
    }

    fun saveApiKey() {
        settingsStore.saveApiKey(_uiState.value.apiKey)
        _uiState.update { it.copy(savedMessage = "API Key 已保存") }
    }

    fun clearAllData() {
        viewModelScope.launch {
            _uiState.update { it.copy(isClearing = true, savedMessage = null) }
            quizRepository.clearAllData()
            _uiState.update { it.copy(isClearing = false, savedMessage = "已清除数据，并重新导入预置题库") }
        }
    }
}
