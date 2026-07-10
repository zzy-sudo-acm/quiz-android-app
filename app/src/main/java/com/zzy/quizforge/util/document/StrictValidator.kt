package com.zzy.quizforge.util.document

import com.zzy.quizforge.domain.model.QuestionType
import com.zzy.quizforge.util.AnswerNormalizer

/**
 * New-pipeline StrictValidator for StructuredQuestionDraft.
 *
 * Reuses AnswerNormalizer and QuestionType.fromRawStrict invariants from Phase 1.
 * Does NOT copy logic from JsonValidator or OriginalQuestionParser.
 */
object StrictValidator {

    data class ValidationResult(
        val isValid: Boolean,
        val inferredType: QuestionType?,
        val normalizedAnswer: List<String>,
        val errors: List<String>,
    )

    fun validate(draft: StructuredQuestionDraft): ValidationResult {
        val errors = mutableListOf<String>()

        // 1. Stem non-empty
        val stemText = draft.stemText.trim()
        if (stemText.isBlank()) errors += "题干为空"

        // 2. Option keys unique
        val keys = draft.optionSlices.map { it.key }
        if (keys.size != keys.toSet().size) errors += "选项 key 重复: $keys"

        // 3. At least 2 options (non-truefalse)
        if (keys.size < 2) errors += "选项数量不足: ${keys.size}"

        // 4. Answer non-empty
        val answerText = draft.answerText.trim()
        if (answerText.isBlank()) {
            errors += "答案为空"
        }

        // 5. Normalize answer and check against option keys
        val normalizedAnswer = AnswerNormalizer.normalize(answerText)
        if (normalizedAnswer.isEmpty() && answerText.isNotBlank()) {
            errors += "答案标准化后为空: $answerText"
        }

        // 6. Answer keys must exist in option keys
        for (ak in normalizedAnswer) {
            if (ak !in keys) errors += "答案 key $ak 不在选项 keys $keys 中"
        }

        // 7. Infer type and check invariants
        val type = if (draft.typeHint != null) {
            QuestionType.fromRawStrict(draft.typeHint)
        } else {
            inferType(normalizedAnswer, keys)
        }

        if (type == null) {
            errors += "无法推断题型"
        } else {
            when (type) {
                QuestionType.SINGLE -> {
                    if (normalizedAnswer.size != 1) errors += "单选题答案数量必须为 1，实际 ${normalizedAnswer.size}"
                    if (keys.size < 2) errors += "单选题至少需要 2 个选项"
                }
                QuestionType.MULTIPLE -> {
                    if (normalizedAnswer.size < 2) errors += "多选题答案数量至少为 2，实际 ${normalizedAnswer.size}"
                }
                QuestionType.TRUE_FALSE -> {
                    if (normalizedAnswer.size != 1) errors += "判断题答案数量必须为 1，实际 ${normalizedAnswer.size}"
                    if (keys.size != 2) errors += "判断题必须恰好 2 个选项，实际 ${keys.size}"
                }
            }
        }

        // Canonical TRUE_FALSE: A must be 对/正确/√ and B must be 错/错误/×
        val optionA = draft.optionSlices.find { it.key == "A" }
        val optionB = draft.optionSlices.find { it.key == "B" }
        val isTrueFalse = draft.optionSlices.size == 2 &&
            optionA != null && optionB != null &&
            optionA.text in setOf("对", "正确", "√") &&
            optionB.text in setOf("错", "错误", "×")

        val finalType = if (isTrueFalse) QuestionType.TRUE_FALSE else type

        return ValidationResult(
            isValid = errors.isEmpty(),
            inferredType = if (errors.isEmpty()) finalType else null,
            normalizedAnswer = normalizedAnswer,
            errors = errors,
        )
    }

    private fun inferType(answer: List<String>, optionKeys: List<String>): QuestionType? {
        if (optionKeys.size < 2) return null
        return when {
            answer.size == 1 -> QuestionType.SINGLE
            answer.size >= 2 -> QuestionType.MULTIPLE
            else -> null
        }
    }
}
