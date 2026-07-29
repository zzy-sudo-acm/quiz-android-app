package com.zzy.quizforge.ui.quiz

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zzy.quizforge.data.repository.QuizSessionRepository
import com.zzy.quizforge.domain.model.QuizMode
import com.zzy.quizforge.domain.model.QuizQuestion
import kotlinx.coroutines.CancellationException
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
    val isSubmitting: Boolean = false,
    val submitted: Boolean = false,
    val lastCorrect: Boolean? = null,
    val sessionAnswered: Int = 0,
    val sessionCorrect: Int = 0,
    val finished: Boolean = false,
    val error: String? = null,
    val submissionError: String? = null,
) {
    val currentQuestion: QuizQuestion?
        get() = questions.getOrNull(currentIndex)

    val total: Int
        get() = questions.size

    val progressText: String
        get() = if (total == 0) "0/0" else "${currentIndex + 1}/$total"

    val accuracyText: String
        get() = if (sessionAnswered == 0) "掌握率 --" else "掌握率 ${sessionCorrect * 100 / sessionAnswered}%"
}

class QuizViewModel(
    private val repository: QuizSessionRepository,
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
        if (state.submitted || state.isSubmitting) return
        val question = state.currentQuestion ?: return

        if (question.type.isMultipleChoice) {
            _uiState.update {
                val next = if (key in it.selected) it.selected - key else it.selected + key
                it.copy(selected = next, submissionError = null)
            }
        } else {
            _uiState.update { it.copy(selected = setOf(key), submissionError = null) }
            submit()
        }
    }

    fun submit() {
        val submission = lockSubmission() ?: return

        viewModelScope.launch {
            try {
                val correct = repository.submitAnswer(submission.question, submission.selected)
                _uiState.update {
                    it.copy(
                        isSubmitting = false,
                        submitted = true,
                        lastCorrect = correct,
                        sessionAnswered = it.sessionAnswered + 1,
                        sessionCorrect = it.sessionCorrect + if (correct) 1 else 0,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                _uiState.update {
                    it.copy(
                        isSubmitting = false,
                        submissionError = "答案保存失败，请重试",
                    )
                }
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
                            sessionAnswered = 0,
                            sessionCorrect = 0,
                            submissionError = null,
                        )
                    }
                }
                return
            }
            if (mode == QuizMode.SEQUENTIAL) {
                viewModelScope.launch {
                    repository.saveProgress(bankId, mode, state.questions.size)
                    _uiState.update { it.copy(finished = true) }
                }
            } else {
                _uiState.update { it.copy(finished = true) }
            }
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
                    submissionError = null,
                )
            }
        }
    }

    fun restart() {
        viewModelScope.launch {
            val questions = if (mode == QuizMode.RANDOM || mode == QuizMode.WRONG) {
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
                    isSubmitting = false,
                    submitted = false,
                    lastCorrect = null,
                    finished = false,
                    sessionAnswered = 0,
                    sessionCorrect = 0,
                    submissionError = null,
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

    private fun lockSubmission(): PendingSubmission? {
        while (true) {
            val state = _uiState.value
            val question = state.currentQuestion ?: return null
            if (state.submitted || state.isSubmitting || state.selected.isEmpty()) return null
            val locked = state.copy(isSubmitting = true, submissionError = null)
            if (_uiState.compareAndSet(state, locked)) {
                return PendingSubmission(question, state.selected)
            }
        }
    }

    private data class PendingSubmission(
        val question: QuizQuestion,
        val selected: Set<String>,
    )
}
