package com.zzy.quizforge.util.document

/**
 * Deterministic StructureLabeler.
 *
 * Labels each source block within a QuestionSegment with STEM / OPTION / ANSWER / EXPLANATION
 * using exact character offsets on SourceProjection.text.
 *
 * Never copies question text. Annotations reference sourceId + offset range only.
 */
object StructureLabeler {

    /**
     * Label a single QuestionSegment deterministically.
     *
     * @param segment the segment to label
     * @param document the full StructuredDocument (for block lookup)
     * @return SegmentLabelingResult with status and annotations
     */
    fun label(segment: QuestionSegment, document: StructuredDocument): SegmentLabelingResult {
        val warnings = mutableListOf<String>()
        val annotations = mutableListOf<StructureAnnotation>()
        val projections = mutableMapOf<String, SourceProjection>()

        val blockMap = document.blocks.associateBy { it.sourceId }

        for (sourceId in segment.sourceIds) {
            val block = blockMap[sourceId] ?: continue
            val proj = SourceProjection.from(block)
            projections[sourceId] = proj
            if (proj.text.isBlank() && block is ParagraphBlock && block.content.any { it is ImageContent }) {
                // Image-only paragraph — keep as OTHER for now
                continue
            }

            val blockAnnotations = labelBlock(proj, block, warnings)
            annotations += blockAnnotations
        }

        val status = when {
            hasOptions(annotations) && hasAnswerOrExplanation(annotations) && hasStem(annotations, projections) ->
                LabelingStatus.COMPLETE
            hasOptions(annotations) && hasAnswerOrExplanation(annotations) ->
                LabelingStatus.COMPLETE // implicit stem from option-block leading text
            hasOptions(annotations) ->
                LabelingStatus.AMBIGUOUS // no answer found
            else ->
                LabelingStatus.REJECTED
        }

        return SegmentLabelingResult(segment.segmentId, status, annotations, projections, warnings)
    }

    // ═══════════════════════════════════════════════════════════
    // Block-level labeling
    // ═══════════════════════════════════════════════════════════

    private fun labelBlock(
        proj: SourceProjection,
        block: DocumentBlock,
        warnings: MutableList<String>,
    ): List<StructureAnnotation> {
        if (block !is ParagraphBlock) return emptyList()
        val text = proj.text
        val sourceId = proj.sourceId
        val order = proj.sourceOrder

        // 1. Answer marker detection
        val answerMatch = answerRegex.find(text)
        if (answerMatch != null) {
            val markerEnd = answerMatch.range.last + 1
            val contentStart = if (markerEnd < text.length && text[markerEnd] in ":：") markerEnd + 1 else markerEnd
            val remainder = text.substring(contentStart).trim()
            if (remainder.isNotEmpty()) {
                return listOf(
                    StructureAnnotation(sourceId, order, AnnotationLabel.ANSWER, contentStart, text.length)
                )
            }
        }

        // 2. Explanation marker detection
        val explMatch = explRegex.find(text)
        if (explMatch != null) {
            val contentStart = explMatch.range.last + 1
            val remainder = text.substring(contentStart).trimStart(':').trimStart('：').trim()
            val offset = text.length - remainder.length
            return if (remainder.isNotEmpty())
                listOf(StructureAnnotation(sourceId, order, AnnotationLabel.EXPLANATION, offset, text.length))
            else emptyList()
        }

        // 3. Question number detection
        val qNumMatch = questionNumberRegex.find(text)
        val stemStart = if (qNumMatch != null) qNumMatch.range.last + 1 else 0

        // 4. Option marker detection
        val optionMarkers = findOptionSpans(text)
        if (optionMarkers.isNotEmpty()) {
            val result = mutableListOf<StructureAnnotation>()

            // Only add STEM if there is meaningful text before the first option marker.
            // Exclude blocks that are just a bare option marker (e.g., "A. TCP" → no stem).
            val preOptionText = text.substring(0, optionMarkers.first().contentStart).trim()
            val isBareOption = preOptionText.matches(Regex("""^[A-Ha-h]\s*[\.\．、:：\)）]?\s*$"""))
            val hasStemPrefix = preOptionText.isNotBlank() && !isBareOption && preOptionText.length > 2

            if (stemStart < optionMarkers.first().contentStart && hasStemPrefix) {
                result += StructureAnnotation(
                    sourceId, order, AnnotationLabel.STEM,
                    stemStart, optionMarkers.first().contentStart
                )
            }

            // Add OPTION annotations
            for ((j, om) in optionMarkers.withIndex()) {
                val endOffset = if (j + 1 < optionMarkers.size) optionMarkers[j + 1].contentStart else text.length
                val optText = text.substring(om.contentStart, endOffset.coerceAtMost(text.length)).trim()
                if (optText.isBlank()) continue
                result += StructureAnnotation(
                    sourceId, order, AnnotationLabel.OPTION,
                    om.contentStart, endOffset.coerceAtMost(text.length),
                    optionKey = om.key
                )
            }
            return result
        }

        // 5. No markers — treat entire block as STEM (if non-blank), skipping question number prefix
        if (text.isNotBlank()) {
            return listOf(
                StructureAnnotation(sourceId, order, AnnotationLabel.STEM, stemStart, text.length)
            )
        }

        return emptyList()
    }

    // ═══════════════════════════════════════════════════════════
    // Option span detection
    // ═══════════════════════════════════════════════════════════

    private data class OptionSpan(val key: String, val contentStart: Int, val markerEnd: Int)

    private fun findOptionSpans(text: String): List<OptionSpan> {
        val spans = mutableListOf<OptionSpan>()
        val markerRegex = Regex("""([A-Ha-h])\s*[\.\．、:：\)）]\s*""")
        for (match in markerRegex.findAll(text)) {
            val key = match.groupValues[1].uppercase()
            val markerEnd = match.range.last + 1
            val contentStart = markerEnd
            spans += OptionSpan(key, contentStart, markerEnd)
        }
        // Single leading option marker is sufficient (e.g., "A. TCP" in its own paragraph)
        if (spans.size == 1 && spans[0].markerEnd <= 4) return spans
        // Multiple markers always recognized
        if (spans.size >= 2) return spans
        return emptyList()
    }

    // ═══════════════════════════════════════════════════════════
    // Completeness checks
    // ═══════════════════════════════════════════════════════════

    private fun hasOptions(annotations: List<StructureAnnotation>): Boolean =
        annotations.any { it.label == AnnotationLabel.OPTION }

    private fun hasAnswerOrExplanation(annotations: List<StructureAnnotation>): Boolean =
        annotations.any { it.label == AnnotationLabel.ANSWER || it.label == AnnotationLabel.EXPLANATION }

    private fun hasStem(annotations: List<StructureAnnotation>, projections: Map<String, SourceProjection>): Boolean {
        val stemAnn = annotations.filter { it.label == AnnotationLabel.STEM }
        return stemAnn.any { a ->
            val proj = projections[a.sourceId] ?: return@any false
            proj.substring(a.startOffset, a.endOffset).isNotBlank()
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Regex constants
    // ═══════════════════════════════════════════════════════════

    private val answerRegex = Regex("""^\s*(答案|正确答案|参考答案|标准答案)\s*[:：]?""")
    private val explRegex = Regex("""^\s*(解析|解释|题解)\s*[:：]?""")
    private val questionNumberRegex = Regex("""^\s*(?:第\s*)?\d{1,4}\s*(?:[\.\．、\)）]|题)\s*""")
}
