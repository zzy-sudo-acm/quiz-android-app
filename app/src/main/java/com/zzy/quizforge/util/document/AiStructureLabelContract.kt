package com.zzy.quizforge.util.document

/**
 * AI Structure Label API contract.
 *
 * AI returns ONLY annotations — never question text, option text, answer text, or QuizQuestion JSON.
 * This is completely separate from DeepSeekApi.repairBlock() (which returns full question JSON).
 */

/**
 * Snapshot of one QuestionSegment sent to AI.
 */
data class SegmentSnapshot(
    val segmentId: String,
    val originalQuestionNumber: Int?,
    val sourceBlocks: List<BlockSnapshot>,
    val tableCount: Int,
    val imageCount: Int,
)

data class BlockSnapshot(
    val sourceId: String,
    val sourceOrder: Int,
    val blockType: String, // "paragraph" or "table"
    val text: String,
    val imageRefCount: Int,
    val tableSummary: String?,
)

/** AI response: a list of annotations on source blocks. */
data class AiAnnotationResponse(
    val annotations: List<RawAiAnnotation>,
)

/** A single annotation as returned by AI. */
data class RawAiAnnotation(
    val sourceId: String,
    val label: String,
    val startOffset: Int,
    val endOffset: Int,
    val optionKey: String? = null,
)

/** AI annotation validation rejections. Do NOT fuzzy-fix — reject on any violation. */
enum class AiAnnotationRejection {
    UNKNOWN_SOURCE_ID,
    SOURCE_ID_NOT_IN_SEGMENT,
    INVALID_LABEL,
    START_OFFSET_NEGATIVE,
    END_OFFSET_PAST_TEXT,
    START_GE_END,
    OVERLAPPING_SEMANTIC_RANGE,
    DUPLICATE_ANNOTATION,
    OPTION_MISSING_KEY,
    NON_OPTION_HAS_KEY,
    OPTION_KEY_NOT_A_H,
    FORBIDDEN_FIELD_PRESENT,
}

data class AiAnnotationValidationResult(
    val accepted: List<StructureAnnotation>,
    val rejections: List<Pair<RawAiAnnotation, AiAnnotationRejection>>,
    val warnings: List<String>,
)
