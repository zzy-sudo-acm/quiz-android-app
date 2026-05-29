package com.zzy.quizforge.domain.model

enum class QuestionType(
    val dbValue: String,
    val label: String,
) {
    SINGLE("single", "单选题"),
    MULTIPLE("multiple", "多选题"),
    TRUE_FALSE("truefalse", "判断题");

    val isMultipleChoice: Boolean
        get() = this == MULTIPLE

    companion object {
        fun fromRaw(raw: String?): QuestionType = when (raw?.lowercase()) {
            "multiple", "multi" -> MULTIPLE
            "judge", "truefalse", "true_false", "boolean" -> TRUE_FALSE
            else -> SINGLE
        }
    }
}
