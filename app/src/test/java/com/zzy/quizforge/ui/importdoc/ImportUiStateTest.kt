package com.zzy.quizforge.ui.importdoc

import com.zzy.quizforge.domain.model.QuestionOption
import com.zzy.quizforge.domain.model.QuestionType
import com.zzy.quizforge.domain.model.QuizQuestion
import com.zzy.quizforge.util.document.ImportMode
import com.zzy.quizforge.util.document.ImportRecognitionResult
import com.zzy.quizforge.util.document.ImportReport
import com.zzy.quizforge.util.document.QuestionProvenance
import com.zzy.quizforge.util.document.RecognizedQuestion
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportUiStateTest {
    @Test
    fun `uncertain report blocks commit until user explicitly ignores failures`() {
        val recognition = recognition(rejectedCount = 1)

        assertFalse(ImportUiState(bankName = "题库", recognition = recognition).canCommit)
        assertTrue(
            ImportUiState(
                bankName = "题库",
                recognition = recognition,
                ignoredFailuresConfirmed = true,
            ).canCommit,
        )
    }

    @Test
    fun `fully covered report can commit without an ignore confirmation`() {
        assertTrue(ImportUiState(bankName = "题库", recognition = recognition(rejectedCount = 0)).canCommit)
    }

    private fun recognition(rejectedCount: Int): ImportRecognitionResult {
        val provenance = QuestionProvenance(
            sourceIds = listOf("q1"),
            questionSource = listOf("q1"),
            optionSources = mapOf("A" to listOf("q1"), "B" to listOf("q1")),
            answerSource = listOf("q1"),
        )
        val question = QuizQuestion(
            type = QuestionType.SINGLE,
            question = "题干",
            options = listOf(QuestionOption("A", "甲"), QuestionOption("B", "乙")),
            answer = listOf("A"),
        )
        return ImportRecognitionResult(
            questions = listOf(RecognizedQuestion(question, provenance, 1)),
            report = ImportReport(
                reportId = "report",
                fileName = "fixture.docx",
                importMode = ImportMode.STANDARD,
                startedAt = 1,
                finishedAt = 2,
                totalSourceBlocks = 1,
                candidateQuestionCount = 1 + rejectedCount,
                acceptedQuestionCount = 1,
                rejectedQuestionCount = rejectedCount,
                nonQuestionCount = 0,
                unsupportedCount = 0,
                duplicateQuestionCount = 0,
                imageCount = 0,
                tableCount = 0,
                usedApi = false,
                apiRequestCount = 0,
                warnings = emptyList(),
                records = emptyList(),
                ledgerComplete = true,
            ),
        )
    }
}
