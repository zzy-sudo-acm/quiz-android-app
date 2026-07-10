package com.zzy.quizforge.util.document

object QuestionAssembler {

    fun assemble(
        segment: QuestionSegment,
        document: StructuredDocument,
        labeling: SegmentLabelingResult,
    ): StructuredQuestionDraft {
        val warnings = mutableListOf<String>()
        val blockMap = document.blocks.associateBy { it.sourceId }
        val projections = labeling.sourceProjections
        val annotations = labeling.annotations
        val mediaById: Map<String, DocumentMedia> = document.media.associateBy { it.mediaId }

        // Collect slices
        val stemSlices = annotations.filter { it.label == AnnotationLabel.STEM }.sortedBy { it.sourceOrder }.mapNotNull { a ->
            val p = projections[a.sourceId] ?: return@mapNotNull null
            val t = p.substring(a.startOffset, a.endOffset)
            if (t.isNotBlank()) TextSlice(a.sourceId, a.sourceOrder, t, a.label) else null
        }

        val optionSlices = annotations.filter { it.label == AnnotationLabel.OPTION }
            .sortedWith(compareBy({ it.sourceOrder }, { it.startOffset })).map { a ->
                val p = projections[a.sourceId] ?: return@map OptionSlice("",-1,"?","")
                OptionSlice(a.sourceId, a.sourceOrder, a.optionKey ?: "?", p.substring(a.startOffset, a.endOffset).trim())
            }

        val answerSlices = annotations.filter { it.label == AnnotationLabel.ANSWER }.sortedBy { it.sourceOrder }.map { a ->
            val p = projections[a.sourceId] ?: return@map TextSlice("",-1,"",a.label)
            TextSlice(a.sourceId, a.sourceOrder, p.substring(a.startOffset, a.endOffset), a.label)
        }

        val explanationSlices = annotations.filter { it.label == AnnotationLabel.EXPLANATION }.sortedBy { it.sourceOrder }.map { a ->
            val p = projections[a.sourceId] ?: return@map TextSlice("",-1,"",a.label)
            TextSlice(a.sourceId, a.sourceOrder, p.substring(a.startOffset, a.endOffset), a.label)
        }

        // Resolve image ownership
        val imageRefs = mutableListOf<ImageRef>()
        val tableRefs = mutableListOf<TableRef>()
        val segmentSourceIds = segment.sourceIds.toSet()

        for (sourceId in segment.sourceIds) {
            val block = blockMap[sourceId] ?: continue
            val proj = projections[sourceId] ?: SourceProjection.from(block)
            val sourceAnns = annotations.filter { it.sourceId == sourceId }

            when (block) {
                is ParagraphBlock -> {
                    for ((idx, inline) in block.content.withIndex()) {
                        if (inline !is ImageContent) continue
                        val offset = proj.inlineOffsets.getOrNull(idx)
                        val charStart = offset?.charStart ?: -1
                        val owner = resolveOwner(charStart, sourceAnns)
                        val localPath = inline.mediaId?.let { mediaById[it]?.localPath }
                        imageRefs += ImageRef(
                            inline.mediaId, inline.relationshipId, sourceId,
                            sourceOrder = block.sourceOrder, inlineIndex = idx,
                            charOffset = charStart, owner = owner,
                            resolvedLocalPath = localPath,
                        )
                    }
                }
                is TableBlock -> {
                    tableRefs += TableRef(sourceId, block.sourceOrder)
                    for (row in block.rows) for (cell in row.cells) for (cb in cell.blocks) {
                        if (cb is ParagraphBlock) {
                            for ((idx, inline) in cb.content.withIndex()) {
                                if (inline is ImageContent) {
                                    imageRefs += ImageRef(inline.mediaId, inline.relationshipId, cb.sourceId,
                                        sourceOrder = cb.sourceOrder, inlineIndex = idx,
                                        owner = ImageOwner.TableCell,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Fill OptionSlice imageRefs
        val optionSlicesWithImages = optionSlices.map { slice ->
            val imgs = imageRefs.filter { it.owner is ImageOwner.Option && (it.owner as ImageOwner.Option).key == slice.key }
            slice.copy(imageRefs = imgs)
        }

        // Representability
        val hasUnbound = imageRefs.any { it.owner is ImageOwner.Unbound }
        val stemImageCount = imageRefs.count { it.owner is ImageOwner.Stem }
        val representability = when {
            tableRefs.isNotEmpty() -> Representability.LOSSY
            hasUnbound -> Representability.LOSSY
            stemImageCount > 1 -> Representability.LOSSY
            else -> Representability.REPRESENTABLE
        }

        return StructuredQuestionDraft(
            segmentId = segment.segmentId,
            originalQuestionNumber = segment.originalQuestionNumber,
            stemSlices = stemSlices, optionSlices = optionSlicesWithImages,
            answerSlices = answerSlices, explanationSlices = explanationSlices,
            typeHint = null, sourceIdsConsumed = segment.sourceIds,
            imageRefs = imageRefs, tableRefs = tableRefs,
            warnings = warnings, representability = representability,
        )
    }

    private fun resolveOwner(charOffset: Int, annotations: List<StructureAnnotation>): ImageOwner {
        if (charOffset < 0) return ImageOwner.Unbound
        val matching = annotations.filter { charOffset >= it.startOffset && charOffset < it.endOffset }
        return when {
            matching.size == 1 -> when (matching[0].label) {
                AnnotationLabel.STEM -> ImageOwner.Stem
                AnnotationLabel.OPTION -> ImageOwner.Option(matching[0].optionKey ?: "?")
                else -> ImageOwner.Unbound
            }
            // Zero-width image at annotation boundary: use the annotation that starts at this position
            matching.isEmpty() -> {
                val atBoundary = annotations.filter { it.startOffset == charOffset }
                if (atBoundary.size == 1) when (atBoundary[0].label) {
                    AnnotationLabel.STEM -> ImageOwner.Stem
                    AnnotationLabel.OPTION -> ImageOwner.Option(atBoundary[0].optionKey ?: "?")
                    else -> ImageOwner.Unbound
                } else ImageOwner.Unbound
            }
            else -> ImageOwner.Unbound
        }
    }
}
