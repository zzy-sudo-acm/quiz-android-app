package com.zzy.quizforge.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zzy.quizforge.data.local.QuizBankSummaryRow
import com.zzy.quizforge.data.repository.QuizRepository
import com.zzy.quizforge.domain.model.QuizMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class QuizBankSummaryUi(
    val id: Long,
    val name: String,
    val questionCount: Int,
    val answeredCount: Int,
    val correctCount: Int,
    val wrongCount: Int,
    val lastPracticedAt: Long?,
    val sequentialProgressIndex: Int?,
) {
    val accuracyText: String
        get() = if (answeredCount == 0) "未开始" else "${correctCount * 100 / answeredCount}%"

    val sequentialActionText: String
        get() {
            if (questionCount <= 0) return "顺序"
            val index = sequentialProgressIndex ?: 0
            if (index <= 0) return "顺序"
            val position = (index + 1).coerceIn(1, questionCount)
            return "继续 $position/$questionCount"
        }
}

data class HomeUiState(
    val banks: List<QuizBankSummaryUi> = emptyList(),
    val deleteError: String? = null,
    val isLoading: Boolean = true,
)

class HomeViewModel(private val repository: QuizRepository) : ViewModel() {
    private val _deleteError = MutableStateFlow<String?>(null)

    val uiState: StateFlow<HomeUiState> = combine(
        repository.observeBankSummaries(QuizMode.SEQUENTIAL.routeValue),
        _deleteError,
    ) { rows, deleteError ->
        HomeUiState(
            banks = rows.map { it.toUi() },
            deleteError = deleteError,
            isLoading = false,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    fun clearDeleteError() {
        _deleteError.value = null
    }

    fun deleteBank(bankId: Long) {
        viewModelScope.launch {
            runCatching { repository.deleteBank(bankId) }
                .onFailure { _deleteError.value = it.message ?: "删除题库失败" }
        }
    }

    private fun QuizBankSummaryRow.toUi(): QuizBankSummaryUi =
        QuizBankSummaryUi(
            id = id,
            name = name,
            questionCount = questionCount,
            answeredCount = answeredCount,
            correctCount = correctCount,
            wrongCount = wrongCount,
            lastPracticedAt = lastPracticedAt,
            sequentialProgressIndex = sequentialProgressIndex,
        )
}
