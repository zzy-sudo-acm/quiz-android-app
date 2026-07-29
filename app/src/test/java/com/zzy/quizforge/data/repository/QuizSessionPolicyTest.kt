package com.zzy.quizforge.data.repository

import com.zzy.quizforge.domain.model.QuestionOption
import com.zzy.quizforge.domain.model.QuestionType
import com.zzy.quizforge.domain.model.QuizMode
import com.zzy.quizforge.domain.model.QuizQuestion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class QuizSessionPolicyTest {
    @Test
    fun `completed sequential progress resumes from first question`() {
        assertEquals(57, resumeIndex(savedIndex = 57, total = 100))
        assertEquals(0, resumeIndex(savedIndex = 100, total = 100))
        assertEquals(0, resumeIndex(savedIndex = 999, total = 100))
        assertEquals(0, resumeIndex(savedIndex = -1, total = 100))
    }

    @Test
    fun `wrong and random modes create a newly shuffled round`() {
        val questions = listOf(question(1), question(2), question(3))
        var shuffleCalls = 0
        val reverse: (List<QuizQuestion>) -> List<QuizQuestion> = {
            shuffleCalls += 1
            it.reversed()
        }

        assertEquals(questions.reversed(), orderQuestionsForMode(questions, QuizMode.WRONG, reverse))
        assertEquals(questions.reversed(), orderQuestionsForMode(questions, QuizMode.RANDOM, reverse))
        assertEquals(2, shuffleCalls)

        val sequential = orderQuestionsForMode(questions, QuizMode.SEQUENTIAL, reverse)
        assertSame(questions, sequential)
        assertEquals(2, shuffleCalls)
    }

    private fun question(id: Long) = QuizQuestion(
        id = id,
        bankId = 7,
        type = QuestionType.SINGLE,
        question = "题目 $id",
        options = listOf(
            QuestionOption("A", "正确"),
            QuestionOption("B", "错误"),
        ),
        answer = listOf("A"),
    )
}
