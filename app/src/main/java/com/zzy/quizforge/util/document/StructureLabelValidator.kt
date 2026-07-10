package com.zzy.quizforge.util.document

/**
 * Validates AI-returned annotations against a QuestionSegment's SourceProjections.
 * Any violation → rejected. No fuzzy fixing, no guessing.
 */
object StructureLabelValidator {

    /** Forbidden field names in AI response. */
    private val FORBIDDEN_FIELDS = setOf(
        "question", "questions", "options", "option", "answer", "answers",
        "explanation", "text", "content", "rewrittenText", "rewritten_text",
    )

    fun validate(
        rawAnnotations: List<RawAiAnnotation>,
        segmentSources: Set<String>,
        projections: Map<String, SourceProjection>,
        existingAnnotations: List<StructureAnnotation>,
    ): AiAnnotationValidationResult {
        val accepted = mutableListOf<StructureAnnotation>()
        val rejections = mutableListOf<Pair<RawAiAnnotation, AiAnnotationRejection>>()
        val warnings = mutableListOf<String>()

        for (raw in rawAnnotations) {
            // Check forbidden fields
            if (raw.label in FORBIDDEN_FIELDS) {
                rejections += raw to AiAnnotationRejection.FORBIDDEN_FIELD_PRESENT
                continue
            }

            // sourceId must belong to segment
            if (raw.sourceId !in segmentSources) {
                rejections += raw to AiAnnotationRejection.SOURCE_ID_NOT_IN_SEGMENT
                continue
            }

            val proj = projections[raw.sourceId]
            if (proj == null) {
                rejections += raw to AiAnnotationRejection.UNKNOWN_SOURCE_ID
                continue
            }

            // Validate label
            val label = parseLabel(raw.label)
            if (label == null) {
                rejections += raw to AiAnnotationRejection.INVALID_LABEL
                continue
            }

            // Range checks
            if (raw.startOffset < 0) {
                rejections += raw to AiAnnotationRejection.START_OFFSET_NEGATIVE
                continue
            }
            if (raw.endOffset > proj.text.length) {
                rejections += raw to AiAnnotationRejection.END_OFFSET_PAST_TEXT
                continue
            }
            if (raw.startOffset >= raw.endOffset) {
                rejections += raw to AiAnnotationRejection.START_GE_END
                continue
            }

            // optionKey rules
            if (label == AnnotationLabel.OPTION) {
                val key = raw.optionKey?.uppercase()?.trim()
                if (key.isNullOrBlank() || key.length != 1 || key[0] !in 'A'..'H') {
                    rejections += raw to AiAnnotationRejection.OPTION_KEY_NOT_A_H
                    continue
                }
            } else {
                if (raw.optionKey != null) {
                    rejections += raw to AiAnnotationRejection.NON_OPTION_HAS_KEY
                    continue
                }
            }

            val ann = StructureAnnotation(
                sourceId = raw.sourceId,
                sourceOrder = proj.sourceOrder,
                label = label,
                startOffset = raw.startOffset,
                endOffset = raw.endOffset,
                optionKey = if (label == AnnotationLabel.OPTION) raw.optionKey?.uppercase()?.trim() else null,
            )

            // Check overlap with existing annotations AND already-accepted from this batch
            val allExisting = existingAnnotations + accepted
            val overlapping = allExisting.filter { it.sourceId == ann.sourceId }.any {
                rangesOverlap(it.startOffset, it.endOffset, ann.startOffset, ann.endOffset)
            }
            if (overlapping) {
                rejections += raw to AiAnnotationRejection.OVERLAPPING_SEMANTIC_RANGE
                continue
            }

            // Check duplicate
            if (existingAnnotations.any { it == ann } || accepted.any { it == ann }) {
                rejections += raw to AiAnnotationRejection.DUPLICATE_ANNOTATION
                continue
            }

            accepted += ann
        }

        return AiAnnotationValidationResult(accepted, rejections, warnings)
    }

    private fun parseLabel(raw: String): AnnotationLabel? = when (raw.uppercase().trim()) {
        "STEM" -> AnnotationLabel.STEM
        "OPTION" -> AnnotationLabel.OPTION
        "ANSWER" -> AnnotationLabel.ANSWER
        "EXPLANATION" -> AnnotationLabel.EXPLANATION
        "TYPE_HINT" -> AnnotationLabel.TYPE_HINT
        "OTHER" -> AnnotationLabel.OTHER
        else -> null
    }

    private fun rangesOverlap(s1: Int, e1: Int, s2: Int, e2: Int): Boolean =
        s1 < e2 && s2 < e1
}
