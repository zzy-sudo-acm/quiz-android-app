package com.zzy.quizforge.ui.quiz

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zzy.quizforge.data.repository.QuizRepository
import com.zzy.quizforge.domain.model.QuizMode
import com.zzy.quizforge.domain.model.QuizQuestion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class QuizUiState(
    val isLoading: Boolean = true,
    val bankName: String = "",
    val mode: QuizMode = QuizMode.SEQUENTIAL,
    val questions: List<QuizQuestion> = emptyList(),
    val currentIndex: Int = 0,
    val selected: Set<String> = emptySet(),
    val submitted: Boolean = false,
    val lastCorrect: Boolean? = null,
    val sessionAnswered: Int = 0,
    val sessionCorrect: Int = 0,
    val finished: Boolean = false,
    val error: String? = null,
) {
    val currentQuestion: QuizQuestion?
        get() = questions.getOrNull(currentIndex)

    val total: Int
        get() = questions.size

    val progressText: String
        get() = if (total == 0) "0/0" else "${currentIndex + 1}/$total"

    val accuracyText: String
        get() = if (sessionAnswered == 0) "正确率 --" else "正确率 ${sessionCorrect * 100 / sessionAnswered}%"
}

class QuizViewModel(
    private val repository: QuizRepository,
    private val bankId: Long,
    private val mode: QuizMode,
) : ViewModel() {
    private val _uiState = MutableStateFlow(QuizUiState(mode = mode))
    val uiState: StateFlow<QuizUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun toggleOption(key: String) {
        val state = _uiState.value
        if (state.submitted) return
        val question = state.currentQuestion ?: return

        if (question.type.isMultipleChoice) {
            _uiState.update {
                val next = if (key in it.selected) it.selected - key else it.selected + key
                it.copy(selected = next)
            }
        } else {
            _uiState.update { it.copy(selected = setOf(key)) }
            submit()
        }
    }

    fun submit() {
        val state = _uiState.value
        val question = state.currentQuestion ?: return
        if (state.submitted || state.selected.isEmpty()) return

        viewModelScope.launch {
            val correct = repository.submitAnswer(question, state.selected)
            _uiState.update {
                it.copy(
                    submitted = true,
                    lastCorrect = correct,
                    sessionAnswered = it.sessionAnswered + 1,
                    sessionCorrect = it.sessionCorrect + if (correct) 1 else 0,
                )
            }
        }
    }

    fun next() {
        val state = _uiState.value
        if (!state.submitted) return
        val nextIndex = state.currentIndex + 1
        if (nextIndex >= state.questions.size) {
            if (mode == QuizMode.RANDOM) {
                viewModelScope.launch {
                    val shuffled = repository.getQuestions(bankId, mode)
                    _uiState.update {
                        it.copy(
                            questions = shuffled,
                            currentIndex = 0,
                            selected = emptySet(),
                            submitted = false,
                            lastCorrect = null,
                            finished = false,
                        )
                    }
                }
                return
            }
            _uiState.update { it.copy(finished = true) }
            return
        }

        viewModelScope.launch {
            repository.saveProgress(bankId, mode, nextIndex)
            _uiState.update {
                it.copy(
                    currentIndex = nextIndex,
                    selected = emptySet(),
                    submitted = false,
                    lastCorrect = null,
                )
            }
        }
    }

    fun restart() {
        viewModelScope.launch {
            val questions = if (mode == QuizMode.RANDOM) {
                repository.getQuestions(bankId, mode)
            } else {
                _uiState.value.questions
            }
            repository.saveProgress(bankId, mode, 0)
            _uiState.update {
                it.copy(
                    questions = questions,
                    currentIndex = 0,
                    selected = emptySet(),
                    submitted = false,
                    lastCorrect = null,
                    finished = false,
                    sessionAnswered = 0,
                    sessionCorrect = 0,
                )
            }
        }
    }

    private fun load() {
        viewModelScope.launch {
            runCatching {
                val bankName = repository.getBankName(bankId)
                val questions = repository.getQuestions(bankId, mode)
                val index = repository.getProgress(bankId, mode, questions.size)
                _uiState.value = QuizUiState(
                    isLoading = false,
                    bankName = bankName,
                    mode = mode,
                    questions = questions,
                    currentIndex = index,
                )
            }.onFailure { error ->
                _uiState.value = QuizUiState(
                    isLoading = false,
                    mode = mode,
                    error = error.message ?: "加载题库失败",
                )
            }
        }
    }
}
