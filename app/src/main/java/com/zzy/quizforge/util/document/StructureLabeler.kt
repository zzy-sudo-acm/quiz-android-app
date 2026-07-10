package com.zzy.quizforge.util.document

object StructureLabeler {

    fun label(segment: QuestionSegment, document: StructuredDocument): SegmentLabelingResult {
        val warnings = mutableListOf<String>()
        val allAnnotations = mutableListOf<StructureAnnotation>()
        val projections = mutableMapOf<String, SourceProjection>()
        val blockMap = document.blocks.associateBy { it.sourceId }

        for (sourceId in segment.sourceIds) {
            val block = blockMap[sourceId] ?: continue
            val proj = SourceProjection.from(block)
            projections[sourceId] = proj
            if (proj.text.isBlank() && block is ParagraphBlock && block.content.any { it is ImageContent }) continue
            allAnnotations += labelBlock(proj, block, warnings)
        }

        val status = computeStatus(allAnnotations, projections)
        return SegmentLabelingResult(segment.segmentId, status, allAnnotations.toList(), projections, warnings)
    }

    fun chosenAnnotations(
        status: LabelingStatus,
        deterministic: SegmentLabelingResult,
        aiAnnotations: List<StructureAnnotation>?,
    ): List<StructureAnnotation> {
        return when (status) {
            LabelingStatus.COMPLETE -> deterministic.annotations
            LabelingStatus.AMBIGUOUS -> aiAnnotations ?: deterministic.annotations
            LabelingStatus.REJECTED -> emptyList()
        }
    }

    // ═══════════════════════════════════════════════
    // Block labeling
    // ═══════════════════════════════════════════════

    private fun labelBlock(proj: SourceProjection, block: DocumentBlock, warnings: MutableList<String>): List<StructureAnnotation> {
        if (block !is ParagraphBlock) return emptyList()
        val text = proj.text; val sid = proj.sourceId; val ord = proj.sourceOrder

        val ansMatch = answerRegex.find(text)
        if (ansMatch != null) {
            val contentStart = text.indexOf(ansMatch.value)
            val markerEnd = contentStart + ansMatch.value.length
            val remainder = text.substring(markerEnd).trimStart(':').trimStart('：').trim()
            if (remainder.isNotEmpty()) {
                val offset = text.length - remainder.length
                return listOf(StructureAnnotation(sid, ord, AnnotationLabel.ANSWER, offset, text.length))
            }
        }

        val explMatch = explRegex.find(text)
        if (explMatch != null) {
            val contentStart = text.indexOf(explMatch.value)
            val markerEnd = contentStart + explMatch.value.length
            val remainder = text.substring(markerEnd).trimStart(':').trimStart('：').trim()
            if (remainder.isNotEmpty()) {
                val offset = text.length - remainder.length
                return listOf(StructureAnnotation(sid, ord, AnnotationLabel.EXPLANATION, offset, text.length))
            }
        }

        val qNumMatch = questionNumberRegex.find(text)
        val stemStart = if (qNumMatch != null) qNumMatch.range.last + 1 else 0

        val optionMarkers = findOptionSpans(text)
        if (optionMarkers.isNotEmpty()) {
            val result = mutableListOf<StructureAnnotation>()
            val preOptionText = text.substring(0, optionMarkers.first().markerStart).trim()
            val isBare = preOptionText.matches(Regex("""^[A-Ha-h]\s*[\.\．、:：\)）]?\s*$"""))

            if (stemStart < optionMarkers.first().markerStart && preOptionText.isNotBlank() && !isBare) {
                result += StructureAnnotation(sid, ord, AnnotationLabel.STEM, stemStart, optionMarkers.first().markerStart)
            }

            for ((j, om) in optionMarkers.withIndex()) {
                val endOffset = if (j + 1 < optionMarkers.size) optionMarkers[j + 1].markerStart else text.length
                val fullText = text.substring(om.contentStart, endOffset.coerceAtMost(text.length))
                // Trim trailing whitespace from annotation range
                val trimmedEnd = om.contentStart + fullText.trimEnd().length
                if (trimmedEnd <= om.contentStart) continue
                result += StructureAnnotation(sid, ord, AnnotationLabel.OPTION, om.contentStart, trimmedEnd, om.key)
            }
            return result
        }

        if (text.isNotBlank()) {
            return listOf(StructureAnnotation(sid, ord, AnnotationLabel.STEM, stemStart, text.length))
        }
        return emptyList()
    }

    // ═══════════════════════════════════════════════
    // Option spans — markerStart + markerEnd + contentStart
    // ═══════════════════════════════════════════════

    private data class OptionSpan(val key: String, val markerStart: Int, val markerEnd: Int, val contentStart: Int)

    private fun findOptionSpans(text: String): List<OptionSpan> {
        val spans = mutableListOf<OptionSpan>()
        val r = Regex("""([A-Ha-h])\s*[\.\．、:：\)）]\s*""")
        for (m in r.findAll(text)) {
            val key = m.groupValues[1].uppercase()
            spans += OptionSpan(key, m.range.first, m.range.last + 1, m.range.last + 1)
        }
        if (spans.size == 1 && spans[0].markerStart <= 4) return spans
        if (spans.size >= 2) return spans
        return emptyList()
    }

    // ═══════════════════════════════════════════════
    // Completeness
    // ═══════════════════════════════════════════════

    private fun computeStatus(annotations: List<StructureAnnotation>, projections: Map<String, SourceProjection>): LabelingStatus {
        val hasOpt = annotations.any { it.label == AnnotationLabel.OPTION }
        val optKeys = annotations.filter { it.label == AnnotationLabel.OPTION }.map { it.optionKey }.filterNotNull()
        val hasDupKeys = optKeys.size != optKeys.toSet().size
        val hasAns = annotations.any { it.label == AnnotationLabel.ANSWER }
        val ansNonEmpty = annotations.filter { it.label == AnnotationLabel.ANSWER }.any { a ->
            (projections[a.sourceId]?.substring(a.startOffset, a.endOffset) ?: "").isNotBlank()
        }
        val hasStemNonEmpty = annotations.filter { it.label == AnnotationLabel.STEM }.any { a ->
            (projections[a.sourceId]?.substring(a.startOffset, a.endOffset) ?: "").isNotBlank()
        }

        if (!hasOpt) return LabelingStatus.REJECTED
        if (optKeys.toSet().size < 2) return LabelingStatus.AMBIGUOUS
        if (hasDupKeys) return LabelingStatus.AMBIGUOUS
        if (!hasAns || !ansNonEmpty) return LabelingStatus.AMBIGUOUS
        if (!hasStemNonEmpty) return LabelingStatus.AMBIGUOUS
        return LabelingStatus.COMPLETE
    }

    // ═══════════════════════════════════════════════
    // Regex
    // ═══════════════════════════════════════════════

    private val answerRegex = Regex("""(答案|正确答案|参考答案|标准答案)\s*[:：]""")
    private val explRegex = Regex("""(解析|解释|题解)\s*[:：]""")
    private val questionNumberRegex = Regex("""^\s*(?:第\s*)?\d{1,4}\s*(?:[\.\．、\)）]|题)\s*""")
}
