package com.zzy.quizforge.util.document

/**
 * Structure annotation — label + position on a SourceProjection.
 *
 * Annotations reference sourceId + exact character range.
 * A single sourceId can have multiple annotations (e.g., inline A/B/C/D).
 * Ranges must be non-overlapping for semantic labels.
 */
data class StructureAnnotation(
    val sourceId: String,
    val sourceOrder: Int,
    val label: AnnotationLabel,
    val startOffset: Int,
    val endOffset: Int,
    /** Only meaningful for OPTION label. Must be null for all other labels. */
    val optionKey: String? = null,
) {
    init {
        require(startOffset >= 0) { "startOffset must be >= 0, got $startOffset" }
        require(endOffset >= startOffset) { "endOffset >= startOffset: $startOffset..$endOffset" }
        if (label == AnnotationLabel.OPTION) {
            require(!optionKey.isNullOrBlank()) { "OPTION must have optionKey" }
            require(optionKey!!.single().uppercaseChar() in 'A'..'H') { "optionKey must be A-H, got $optionKey" }
        } else {
            require(optionKey == null) { "Non-OPTION label $label must not have optionKey" }
        }
    }

    val length: Int get() = endOffset - startOffset
}

enum class AnnotationLabel {
    STEM,
    OPTION,
    ANSWER,
    EXPLANATION,
    TYPE_HINT,
    OTHER,
}

/**
 * Labeling completeness status for a question segment.
 */
enum class LabelingStatus {
    /** Deterministic labeling produced a complete, conflict-free annotation set. */
    COMPLETE,
    /** Structure is ambiguous — AI labeling may help. */
    AMBIGUOUS,
    /** Cannot form a valid question (missing stem, no options, etc.). */
    REJECTED,
}

/** Result of labeling a single QuestionSegment. */
data class SegmentLabelingResult(
    val segmentId: String,
    val status: LabelingStatus,
    val annotations: List<StructureAnnotation>,
    val sourceProjections: Map<String, SourceProjection>,
    val warnings: List<String>,
)
