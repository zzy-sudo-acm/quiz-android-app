package com.zzy.quizforge.util.document

/**
 * ============================================================================
 * QuestionSegment — 分段模型
 * ============================================================================
 *
 * QuestionSegmenter 的输出。标识哪些 Document IR source nodes 属于同一道候选题。
 * 不包含 single/multiple/truefalse 判断、答案推导或 QuizQuestion 语义。
 */

/** 一道路候选题段。 */
data class QuestionSegment(
    val segmentId: String,
    /** 当前段所包含的 top-level source block IDs（有序）。 */
    val sourceIds: List<String>,
    /** 当前段所包含的 top-level source block 的 sourceOrder（递增）。 */
    val sourceOrders: List<Int>,
    val startSourceOrder: Int,
    val endSourceOrder: Int,
    /** 从显式题号或 Word numbering 中提取的题号，纯 metadata，不与 sourceId/sourceOrder 复用。 */
    val originalQuestionNumber: Int?,
    /** 诊断信号摘要。 */
    val signals: List<SegmentSignal>,
)

/** 分段结果。 */
data class SegmentationResult(
    val segments: List<QuestionSegment>,
    /** 未被任何 segment 消费的 top-level source block IDs。 */
    val unassignedSourceIds: List<String>,
    val warnings: List<String>,
    /** 触发的信号总数（用于调试）。 */
    val signalCount: Int,
)

/** 分段过程中触发的诊断信号。 */
sealed interface SegmentSignal {
    data class QuestionStart(
        val sourceId: String,
        val sourceOrder: Int,
        val reason: String,
    ) : SegmentSignal

    data class OptionMarker(
        val sourceId: String,
        val keys: List<String>,
    ) : SegmentSignal

    data class AnswerMarker(
        val sourceId: String,
    ) : SegmentSignal

    data class ExplanationMarker(
        val sourceId: String,
    ) : SegmentSignal

    data class UnassignedBlock(
        val sourceId: String,
        val sourceOrder: Int,
        val reason: String,
    ) : SegmentSignal
}
