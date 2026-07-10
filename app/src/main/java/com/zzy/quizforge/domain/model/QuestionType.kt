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
        /**
         * 宽松解析：未知/无效类型默认为 [SINGLE]。
         *
         * 用于本地老数据兼容路径（例如 [com.zzy.quizforge.data.local.QuestionMappers.toDomain]）
         * 和多策略 JSON 恢复路径。
         */
        fun fromRaw(raw: String?): QuestionType = when (raw?.lowercase()) {
            "multiple", "multi" -> MULTIPLE
            "judge", "truefalse", "true_false", "boolean" -> TRUE_FALSE
            else -> SINGLE
        }

        /**
         * 严格解析：仅接受已知类型，未知/无效返回 null。
         *
         * 用于 AI repair 严格校验路径（[com.zzy.quizforge.util.JsonValidator.parseRepairedQuestion]）。
         * AI 返回了无法识别的 type 值时不应猜测默认类型，而应拒绝该题。
         */
        fun fromRawStrict(raw: String?): QuestionType? = when (raw?.lowercase()) {
            "single" -> SINGLE
            "multiple", "multi" -> MULTIPLE
            "judge", "truefalse", "true_false", "boolean" -> TRUE_FALSE
            else -> null
        }
    }
}
