package com.zzy.quizforge.util.document

/**
 * QuestionAssembler: builds StructuredQuestionDraft from validated annotations + SourceProjections.
 *
 * Does NOT re-segment. Does NOT guess question boundaries.
 * Input annotations and segment structures are assumed valid.
 */
object QuestionAssembler {

    fun assemble(
        segment: QuestionSegment,
        document: StructuredDocument,
        deterministic: SegmentLabelingResult,
        aiAnnotations: List<StructureAnnotation> = emptyList(),
    ): StructuredQuestionDraft {
        val warnings = mutableListOf<String>()
        val blockMap = document.blocks.associateBy { it.sourceId }
        val projections = deterministic.sourceProjections +
            aiAnnotations.associate { it.sourceId to blockMap[it.sourceId] }.mapValues {
                SourceProjection.from(it.value ?: return@mapValues SourceProjection("", emptyList(), it.key, -1))
            }

        val allAnnotations = deterministic.annotations + aiAnnotations

        // Collect stem slices
        val stemSlices = mutableListOf<TextSlice>()
        for (ann in allAnnotations.filter { it.label == AnnotationLabel.STEM }.sortedBy { it.sourceOrder }) {
            val proj = projections[ann.sourceId] ?: continue
            val text = proj.substring(ann.startOffset, ann.endOffset)
            if (text.isNotBlank()) {
                stemSlices += TextSlice(ann.sourceId, ann.sourceOrder, text, ann.label)
            }
        }

        // Collect option slices by key
        val optionSlices = mutableListOf<OptionSlice>()
        for (ann in allAnnotations.filter { it.label == AnnotationLabel.OPTION }.sortedWith(compareBy({ it.sourceOrder }, { it.startOffset }))) {
            val proj = projections[ann.sourceId] ?: continue
            val text = proj.substring(ann.startOffset, ann.endOffset).trim()
            optionSlices += OptionSlice(
                sourceId = ann.sourceId,
                sourceOrder = ann.sourceOrder,
                key = ann.optionKey ?: "?",
                text = text,
            )
        }

        // Collect answer slices
        val answerSlices = allAnnotations.filter { it.label == AnnotationLabel.ANSWER }
            .sortedBy { it.sourceOrder }
            .map { ann ->
                val proj = projections[ann.sourceId] ?: return@map TextSlice("", -1, "", ann.label)
                TextSlice(ann.sourceId, ann.sourceOrder, proj.substring(ann.startOffset, ann.endOffset), ann.label)
            }

        // Collect explanation slices
        val explanationSlices = allAnnotations.filter { it.label == AnnotationLabel.EXPLANATION }
            .sortedBy { it.sourceOrder }
            .map { ann ->
                val proj = projections[ann.sourceId] ?: return@map TextSlice("", -1, "", ann.label)
                TextSlice(ann.sourceId, ann.sourceOrder, proj.substring(ann.startOffset, ann.endOffset), ann.label)
            }

        // Collect image refs
        val imageRefs = mutableListOf<ImageRef>()
        val tableRefs = mutableListOf<TableRef>()
        for (sourceId in segment.sourceIds) {
            val block = blockMap[sourceId] ?: continue
            when (block) {
                is ParagraphBlock -> {
                    for (inline in block.content) {
                        if (inline is ImageContent) {
                            imageRefs += ImageRef(inline.mediaId, inline.relationshipId, sourceId)
                        }
                    }
                }
                is TableBlock -> {
                    tableRefs += TableRef(sourceId, block.sourceOrder)
                    for (row in block.rows) for (cell in row.cells) {
                        for (cb in cell.blocks) {
                            if (cb is ParagraphBlock) {
                                for (inline in cb.content) {
                                    if (inline is ImageContent) {
                                        imageRefs += ImageRef(inline.mediaId, inline.relationshipId, cb.sourceId,
                                            belongsTo = "cell")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Determine representability
        val stemImageCount = imageRefs.filter { it.belongsTo != "cell" }.count { it.belongsTo == null || it.belongsTo == "stem" }
        val representability = when {
            tableRefs.isNotEmpty() -> Representability.LOSSY
            stemImageCount > 1 -> Representability.LOSSY
            else -> Representability.REPRESENTABLE
        }

        return StructuredQuestionDraft(
            segmentId = segment.segmentId,
            originalQuestionNumber = segment.originalQuestionNumber,
            stemSlices = stemSlices,
            optionSlices = optionSlices,
            answerSlices = answerSlices,
            explanationSlices = explanationSlices,
            typeHint = null,
            sourceIdsConsumed = segment.sourceIds,
            imageRefs = imageRefs,
            tableRefs = tableRefs,
            warnings = warnings,
            representability = representability,
        )
    }
}
