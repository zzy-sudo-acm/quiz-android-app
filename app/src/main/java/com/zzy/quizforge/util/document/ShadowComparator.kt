package com.zzy.quizforge.util.document

import com.zzy.quizforge.domain.model.QuizQuestion

data class ShadowComparisonResult(
    val legacyCount: Int,
    val newCount: Int,
    val orderMatch: Boolean,
    val originalIdMismatches: List<String>,
    val stemMismatches: List<String>,
    val answerMismatches: List<String>,
    val optionKeyMismatches: List<String>,
    val newRejectedCount: Int,
    val newUnassignedCount: Int,
    val lossyCount: Int,
    val warningsSummary: List<String>,
)

object ShadowComparator {
    fun compare(
        legacyQuestions: List<QuizQuestion>,
        newResult: NewPipelineResult,
        legacySkipped: Int = 0,
    ): ShadowComparisonResult {
        val orderMatch = legacyQuestions.size == newResult.questions.size
        val idMismatches = mutableListOf<String>()
        val stemMismatches = mutableListOf<String>()
        val answerMismatches = mutableListOf<String>()
        val optMismatches = mutableListOf<String>()

        for (i in 0 until minOf(legacyQuestions.size, newResult.questions.size)) {
            val lq = legacyQuestions[i]; val nq = newResult.questions[i]
            if (lq.originalId != nq.originalId) idMismatches += "idx=$i: legacy=${lq.originalId} new=${nq.originalId}"
            if (lq.question.trim() != nq.question.trim()) stemMismatches += "idx=$i"
            if (lq.answer != nq.answer) answerMismatches += "idx=$i"
            if (lq.options.map { it.key } != nq.options.map { it.key }) optMismatches += "idx=$i"
        }

        return ShadowComparisonResult(
            legacyCount = legacyQuestions.size,
            newCount = newResult.questions.size,
            orderMatch = orderMatch,
            originalIdMismatches = idMismatches,
            stemMismatches = stemMismatches,
            answerMismatches = answerMismatches,
            optionKeyMismatches = optMismatches,
            newRejectedCount = newResult.rejectedCount,
            newUnassignedCount = newResult.unassignedCount,
            lossyCount = newResult.lossyCount,
            warningsSummary = newResult.warnings.take(20),
        )
    }
}
