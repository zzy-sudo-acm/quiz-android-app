package com.zzy.quizforge.util.document

import com.zzy.quizforge.domain.model.QuizQuestion

data class ShadowComparisonResult(
    val legacyCount: Int, val newCount: Int,
    val orderMatch: Boolean,
    val idSequenceMatch: Boolean,
    val idMismatches: List<String>,
    val stemMismatches: List<String>,
    val answerMismatches: List<String>,
    val optionKeyMismatches: List<String>,
    val imagePresenceMismatches: List<String>,
    val newRejectedCount: Int, val newUnassignedCount: Int, val lossyCount: Int,
    val warningsSummary: List<String>,
)

object ShadowComparator {
    fun compare(legacy: List<QuizQuestion>, newResult: NewPipelineResult): ShadowComparisonResult {
        val new = newResult.questions
        val idMismatches = mutableListOf<String>()
        val stemMismatches = mutableListOf<String>()
        val answerMismatches = mutableListOf<String>()
        val optMismatches = mutableListOf<String>()
        val imgMismatches = mutableListOf<String>()

        // Real order: compare originalId sequences
        val legacyIds = legacy.map { it.originalId }
        val newIds = new.map { it.originalId }
        val idSequenceMatch = legacyIds == newIds

        for (i in 0 until minOf(legacy.size, new.size)) {
            val l = legacy[i]; val n = new[i]
            if (l.originalId != n.originalId) idMismatches += "idx=$i: L=${l.originalId} N=${n.originalId}"
            if (l.question.trim() != n.question.trim()) stemMismatches += "idx=$i"
            if (l.answer != n.answer) answerMismatches += "idx=$i"
            val lk = l.options.map { it.key }.toSet(); val nk = n.options.map { it.key }.toSet()
            if (lk != nk) optMismatches += "idx=$i: L=$lk N=$nk"
            val lImg = l.imageUri != null || l.options.any { it.imageUri != null }
            val nImg = n.imageUri != null || n.options.any { it.imageUri != null }
            if (lImg != nImg) imgMismatches += "idx=$i"
        }

        return ShadowComparisonResult(
            legacyCount = legacy.size, newCount = new.size,
            orderMatch = legacy.size == new.size && idSequenceMatch,
            idSequenceMatch = idSequenceMatch,
            idMismatches = idMismatches, stemMismatches = stemMismatches,
            answerMismatches = answerMismatches, optionKeyMismatches = optMismatches,
            imagePresenceMismatches = imgMismatches,
            newRejectedCount = newResult.rejectedCount,
            newUnassignedCount = newResult.unassignedCount,
            lossyCount = newResult.lossyCount,
            warningsSummary = newResult.warnings.take(20),
        )
    }
}
