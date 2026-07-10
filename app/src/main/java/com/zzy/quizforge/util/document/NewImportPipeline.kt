package com.zzy.quizforge.util.document

import com.zzy.quizforge.domain.model.QuizQuestion

/** Progress stages for UI consumption. */
sealed interface NewPipelineProgress {
    data object ReadingDocument : NewPipelineProgress
    data object Segmenting : NewPipelineProgress
    data object LocalLabeling : NewPipelineProgress
    data class AiLabeling(val current: Int, val total: Int) : NewPipelineProgress
    data object Validating : NewPipelineProgress
    data class Rejected(val segmentId: String, val reason: String) : NewPipelineProgress
    data class Done(val result: NewPipelineResult) : NewPipelineProgress
    data class Error(val message: String) : NewPipelineProgress
}

data class NewPipelineResult(
    val questions: List<QuizQuestion>,
    val segmentCount: Int,
    val deterministicCompleteCount: Int,
    val aiAttemptedCount: Int,
    val aiAcceptedCount: Int,
    val rejectedCount: Int,
    val lossyCount: Int,
    val unassignedCount: Int,
    val warnings: List<String>,
    val perSegmentDiagnostics: List<SegmentDiagnostic>,
)

data class SegmentDiagnostic(
    val segmentId: String,
    val originalQuestionNumber: Int?,
    val status: LabelingStatus,
    val aiUsed: Boolean,
    val conversionStatus: QuizQuestionAdapter.ConversionStatus?,
    val warnings: List<String>,
)

class NewImportPipeline(
    private val labelClient: StructureLabelClient? = null,
    private val lossyPolicy: LossyPolicy = LossyPolicy.STRICT,
    private val documentReader: OoXmlDocumentReader = OoXmlDocumentReader(),
) {
    fun execute(
        entries: Map<String, ByteArray>,
        apiKey: String = "",
        mediaDir: java.io.File? = null,
        onProgress: (NewPipelineProgress) -> Unit = {},
    ): NewPipelineResult {
        onProgress(NewPipelineProgress.ReadingDocument)

        val doc = documentReader.read(entries, mediaDir)

        onProgress(NewPipelineProgress.Segmenting)
        val segResult = QuestionSegmenter.segment(doc)

        onProgress(NewPipelineProgress.LocalLabeling)
        val questions = mutableListOf<QuizQuestion>()
        val diagnostics = mutableListOf<SegmentDiagnostic>()
        var detComplete = 0; var aiAttempted = 0; var aiAccepted = 0; var rejected = 0; var lossy = 0

        for ((idx, seg) in segResult.segments.withIndex()) {
            val labeling = StructureLabeler.label(seg, doc)
            val status = labeling.status

            val chosenAnns = when (status) {
                LabelingStatus.COMPLETE -> {
                    detComplete++
                    labeling.annotations
                }
                LabelingStatus.AMBIGUOUS -> {
                    val client = labelClient
                    if (client != null && apiKey.isNotBlank()) {
                        aiAttempted++
                        onProgress(NewPipelineProgress.AiLabeling(idx + 1, segResult.segments.size))
                        val snapshot = buildSnapshot(seg, doc)
                        val rawResponse = runCatching {
                            kotlinx.coroutines.runBlocking { client.labelStructure(apiKey, snapshot) }
                        }.getOrElse { e ->
                            diagnostics += SegmentDiagnostic(seg.segmentId, seg.originalQuestionNumber, status, true, null, listOf("AI error: ${e.message}"))
                            rejected++
                            onProgress(NewPipelineProgress.Rejected(seg.segmentId, "AI error: ${e.message}"))
                            continue
                        }

                        val parseResult = AiResponseParser.parse(rawResponse)
                        if (parseResult.errors.isNotEmpty()) {
                            diagnostics += SegmentDiagnostic(seg.segmentId, seg.originalQuestionNumber, status, true, null, parseResult.errors)
                            rejected++
                            onProgress(NewPipelineProgress.Rejected(seg.segmentId, parseResult.errors.joinToString("; ")))
                            continue
                        }

                        val validationResult = StructureLabelValidator.validate(
                            parseResult.annotations, seg.sourceIds.toSet(), labeling.sourceProjections,
                            labeling.annotations.filter { it.label == AnnotationLabel.ANSWER || it.label == AnnotationLabel.EXPLANATION }
                        )

                        if (validationResult.rejections.isNotEmpty()) {
                            diagnostics += SegmentDiagnostic(seg.segmentId, seg.originalQuestionNumber, status, true, null, validationResult.rejections.map { "${it.second}" })
                            rejected++
                            onProgress(NewPipelineProgress.Rejected(seg.segmentId, "AI validation rejected"))
                            continue
                        }

                        aiAccepted++
                        StructureLabeler.chosenAnnotations(status, labeling, validationResult.accepted)
                    } else {
                        // No AI client available — use deterministic as-is
                        labeling.annotations
                    }
                }
                LabelingStatus.REJECTED -> {
                    diagnostics += SegmentDiagnostic(seg.segmentId, seg.originalQuestionNumber, status, false, null, listOf("REJECTED"))
                    rejected++
                    onProgress(NewPipelineProgress.Rejected(seg.segmentId, "REJECTED"))
                    continue
                }
            }

            // Assemble draft
            val draft = QuestionAssembler.assemble(seg, doc, labeling.copy(annotations = chosenAnns))

            // Lossy policy
            if (draft.representability != Representability.REPRESENTABLE) {
                lossy++
                if (lossyPolicy == LossyPolicy.STRICT) {
                    diagnostics += SegmentDiagnostic(seg.segmentId, seg.originalQuestionNumber, status, chosenAnns != labeling.annotations, QuizQuestionAdapter.ConversionStatus.SKIPPED_UNSUPPORTED, listOf("STRICT policy: ${draft.representability}"))
                    rejected++
                    continue
                }
            }

            // Validate + convert
            val validation = StrictValidator.validate(draft)
            val conv = QuizQuestionAdapter.convert(draft, validation)
            if (conv.question != null) {
                questions += conv.question
            } else {
                rejected++
            }

            diagnostics += SegmentDiagnostic(seg.segmentId, seg.originalQuestionNumber, status,
                chosenAnns != labeling.annotations, conv.status,
                conv.warnings + validation.errors)
        }

        onProgress(NewPipelineProgress.Validating)
        onProgress(NewPipelineProgress.Done(NewPipelineResult(
            questions, segResult.segments.size, detComplete, aiAttempted, aiAccepted,
            rejected, lossy, segResult.unassignedSourceIds.size,
            segResult.unassignedSourceIds.map { "Unassigned: $it" }, diagnostics
        )))

        return NewPipelineResult(questions, segResult.segments.size, detComplete, aiAttempted, aiAccepted,
            rejected, lossy, segResult.unassignedSourceIds.size,
            segResult.unassignedSourceIds.map { "Unassigned: $it" }, diagnostics)
    }

    private fun buildSnapshot(seg: QuestionSegment, doc: StructuredDocument): SegmentSnapshot {
        val blockMap = doc.blocks.associateBy { it.sourceId }
        val sourceBlocks = seg.sourceIds.mapNotNull { sid ->
            val b = blockMap[sid] ?: return@mapNotNull null
            val proj = SourceProjection.from(b)
            BlockSnapshot(sid, b.sourceOrder, if (b is ParagraphBlock) "paragraph" else "table", proj.text,
                if (b is ParagraphBlock) b.content.count { it is ImageContent } else 0, null)
        }
        return SegmentSnapshot(seg.segmentId, seg.originalQuestionNumber, sourceBlocks, 0, sourceBlocks.sumOf { it.imageRefCount })
    }
}
