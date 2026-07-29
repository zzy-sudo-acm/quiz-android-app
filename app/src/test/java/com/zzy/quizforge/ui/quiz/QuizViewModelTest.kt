package com.zzy.quizforge.ui.quiz

import com.zzy.quizforge.data.repository.QuizSessionRepository
import com.zzy.quizforge.data.repository.resumeIndex
import com.zzy.quizforge.domain.model.QuestionOption
import com.zzy.quizforge.domain.model.QuestionType
import com.zzy.quizforge.domain.model.QuizMode
import com.zzy.quizforge.domain.model.QuizQuestion
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QuizViewModelTest {
    private lateinit var dispatcher: TestDispatcher

    @Before
    fun setUp() {
        dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `rapid single-choice taps persist only first answer once`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val repository = FakeQuizSessionRepository(listOf(singleQuestion(1))).apply {
            submitGate = gate
        }
        val viewModel = QuizViewModel(repository, BANK_ID, QuizMode.SEQUENTIAL)
        advanceUntilIdle()

        viewModel.toggleOption("A")
        assertTrue(viewModel.uiState.value.isSubmitting)
        viewModel.toggleOption("B")
        viewModel.submit()
        runCurrent()

        assertEquals(1, repository.submitCalls)
        assertEquals(setOf("A"), repository.submittedAnswers.single())
        assertEquals(0, viewModel.uiState.value.sessionAnswered)

        gate.complete(Unit)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isSubmitting)
        assertTrue(viewModel.uiState.value.submitted)
        assertEquals(1, viewModel.uiState.value.sessionAnswered)
        assertEquals(1, viewModel.uiState.value.sessionCorrect)
    }

    @Test
    fun `multiple-choice options lock immediately after submit`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val repository = FakeQuizSessionRepository(listOf(multipleQuestion(2))).apply {
            submitGate = gate
        }
        val viewModel = QuizViewModel(repository, BANK_ID, QuizMode.SEQUENTIAL)
        advanceUntilIdle()

        viewModel.toggleOption("A")
        viewModel.toggleOption("B")
        viewModel.submit()
        viewModel.toggleOption("C")
        viewModel.submit()
        runCurrent()

        assertTrue(viewModel.uiState.value.isSubmitting)
        assertEquals(setOf("A", "B"), viewModel.uiState.value.selected)
        assertEquals(1, repository.submitCalls)

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.sessionAnswered)
        assertEquals(1, viewModel.uiState.value.sessionCorrect)
    }

    @Test
    fun `database failure unlocks submission without counting answer`() = runTest(dispatcher) {
        val repository = FakeQuizSessionRepository(listOf(singleQuestion(3))).apply {
            submitFailure = IllegalStateException("database unavailable")
        }
        val viewModel = QuizViewModel(repository, BANK_ID, QuizMode.SEQUENTIAL)
        advanceUntilIdle()

        viewModel.toggleOption("A")
        advanceUntilIdle()

        val failed = viewModel.uiState.value
        assertFalse(failed.isSubmitting)
        assertFalse(failed.submitted)
        assertEquals(0, failed.sessionAnswered)
        assertEquals(0, failed.sessionCorrect)
        assertEquals(setOf("A"), failed.selected)
        assertNotNull(failed.submissionError)

        repository.submitFailure = null
        viewModel.submit()
        advanceUntilIdle()
        assertEquals(2, repository.submitCalls)
        assertEquals(1, viewModel.uiState.value.sessionAnswered)
    }

    @Test
    fun `wrong-answer restart requeries and removes newly corrected question`() = runTest(dispatcher) {
        val wrongQuestions = mutableListOf(singleQuestion(4))
        val repository = FakeQuizSessionRepository(emptyList()).apply {
            questionProvider = { mode ->
                check(mode == QuizMode.WRONG)
                wrongQuestions.toList()
            }
            onSuccessfulSubmit = { question, correct ->
                if (correct) wrongQuestions.removeAll { it.id == question.id }
            }
        }
        val viewModel = QuizViewModel(repository, BANK_ID, QuizMode.WRONG)
        advanceUntilIdle()

        assertEquals(1, repository.questionQueries)
        assertEquals(1, viewModel.uiState.value.total)
        viewModel.toggleOption("A")
        advanceUntilIdle()
        viewModel.next()
        assertTrue(viewModel.uiState.value.finished)

        viewModel.restart()
        advanceUntilIdle()

        assertEquals(2, repository.questionQueries)
        assertEquals(0, viewModel.uiState.value.total)
        assertFalse(viewModel.uiState.value.finished)
        assertEquals(0, viewModel.uiState.value.sessionAnswered)
    }

    @Test
    fun `random next round requeries questions and resets round statistics`() = runTest(dispatcher) {
        val rounds = listOf(listOf(singleQuestion(5)), listOf(singleQuestion(6)))
        val repository = FakeQuizSessionRepository(emptyList()).apply {
            questionProvider = { rounds[questionQueries.coerceAtMost(rounds.lastIndex)] }
        }
        val viewModel = QuizViewModel(repository, BANK_ID, QuizMode.RANDOM)
        advanceUntilIdle()

        assertEquals(5L, viewModel.uiState.value.currentQuestion?.id)
        viewModel.toggleOption("A")
        advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.sessionAnswered)

        viewModel.next()
        advanceUntilIdle()

        assertEquals(2, repository.questionQueries)
        assertEquals(6L, viewModel.uiState.value.currentQuestion?.id)
        assertEquals(0, viewModel.uiState.value.sessionAnswered)
        assertFalse(viewModel.uiState.value.submitted)
    }

    @Test
    fun `sequential completion saves end sentinel`() = runTest(dispatcher) {
        val repository = FakeQuizSessionRepository(listOf(singleQuestion(7)))
        val viewModel = QuizViewModel(repository, BANK_ID, QuizMode.SEQUENTIAL)
        advanceUntilIdle()

        viewModel.toggleOption("A")
        advanceUntilIdle()
        viewModel.next()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.finished)
        assertEquals(listOf(1), repository.savedProgress)
        assertEquals(0, resumeIndex(repository.savedProgress.last(), total = 1))
    }

    private class FakeQuizSessionRepository(
        private val initialQuestions: List<QuizQuestion>,
    ) : QuizSessionRepository {
        var questionQueries = 0
        var submitCalls = 0
        var submitGate: CompletableDeferred<Unit>? = null
        var submitFailure: Exception? = null
        var questionProvider: ((QuizMode) -> List<QuizQuestion>)? = null
        var onSuccessfulSubmit: ((QuizQuestion, Boolean) -> Unit)? = null
        val submittedAnswers = mutableListOf<Set<String>>()
        val savedProgress = mutableListOf<Int>()

        override suspend fun getBankName(bankId: Long) = "测试题库"

        override suspend fun getQuestions(bankId: Long, mode: QuizMode): List<QuizQuestion> {
            val questions = questionProvider?.invoke(mode) ?: initialQuestions
            questionQueries += 1
            return questions
        }

        override suspend fun getProgress(bankId: Long, mode: QuizMode, total: Int): Int = 0

        override suspend fun saveProgress(bankId: Long, mode: QuizMode, index: Int) {
            if (mode == QuizMode.SEQUENTIAL) savedProgress += index
        }

        override suspend fun submitAnswer(question: QuizQuestion, selectedAnswer: Set<String>): Boolean {
            submitCalls += 1
            submittedAnswers += selectedAnswer
            submitGate?.await()
            submitFailure?.let { throw it }
            val correct = selectedAnswer == question.answer.toSet()
            onSuccessfulSubmit?.invoke(question, correct)
            return correct
        }
    }

    companion object {
        private const val BANK_ID = 7L

        private fun singleQuestion(id: Long) = QuizQuestion(
            id = id,
            bankId = BANK_ID,
            type = QuestionType.SINGLE,
            question = "单选题 $id",
            options = listOf(
                QuestionOption("A", "正确"),
                QuestionOption("B", "错误"),
            ),
            answer = listOf("A"),
        )

        private fun multipleQuestion(id: Long) = QuizQuestion(
            id = id,
            bankId = BANK_ID,
            type = QuestionType.MULTIPLE,
            question = "多选题 $id",
            options = listOf(
                QuestionOption("A", "正确一"),
                QuestionOption("B", "正确二"),
                QuestionOption("C", "错误"),
            ),
            answer = listOf("A", "B"),
        )
    }
}
