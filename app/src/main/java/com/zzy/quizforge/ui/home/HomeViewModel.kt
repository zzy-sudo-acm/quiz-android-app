package com.zzy.quizforge.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zzy.quizforge.data.local.QuizBankSummaryRow
import com.zzy.quizforge.data.repository.QuizRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class QuizBankSummaryUi(
    val id: Long,
    val name: String,
    val questionCount: Int,
    val answeredCount: Int,
    val correctCount: Int,
    val wrongCount: Int,
    val lastPracticedAt: Long?,
) {
    val accuracyText: String
        get() = if (answeredCount == 0) "未开始" else "${correctCount * 100 / answeredCount}%"
}

data class HomeUiState(
    val banks: List<QuizBankSummaryUi> = emptyList(),
)

class HomeViewModel(repository: QuizRepository) : ViewModel() {
    val uiState: StateFlow<HomeUiState> = repository.observeBankSummaries()
        .map { rows -> HomeUiState(rows.map { it.toUi() }) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = HomeUiState(),
        )

    private fun QuizBankSummaryRow.toUi(): QuizBankSummaryUi =
        QuizBankSummaryUi(
            id = id,
            name = name,
            questionCount = questionCount,
            answeredCount = answeredCount,
            correctCount = correctCount,
            wrongCount = wrongCount,
            lastPracticedAt = lastPracticedAt,
        )
}
