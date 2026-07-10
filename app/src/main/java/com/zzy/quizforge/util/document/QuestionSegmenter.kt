package com.zzy.quizforge.util.document

/**
 * 确定性 multi-signal QuestionSegmenter。
 *
 * 输入：[StructuredDocument]
 * 输出：[SegmentationResult]
 *
 * 只负责判断哪些 Document IR source nodes 属于同一道候选题。
 * 不判断题型、不推导答案、不调用 AI、不修改文本。
 */
object QuestionSegmenter {

    // ═══════════════════════════════════════════════════════════
    // Public API
    // ═══════════════════════════════════════════════════════════

    fun segment(document: StructuredDocument): SegmentationResult {
        val warnings = mutableListOf<String>()
        val signals = mutableListOf<SegmentSignal>()
        val segments = mutableListOf<QuestionSegment>()
        val unassigned = mutableListOf<String>()

        val topBlocks = document.blocks // only top-level blocks
        if (topBlocks.isEmpty()) {
            return SegmentationResult(emptyList(), emptyList(), emptyList(), 0)
        }

        val ctx = SegmentContext(
            numberingDefs = document.numberingDefinitions,
            warnings = warnings,
            signals = signals,
        )

        // State machine: track current segment's block accumulation
        var segmentIdCounter = 0
        var currentSourceIds = mutableListOf<String>()
        var currentSourceOrders = mutableListOf<Int>()
        var currentStartOrder = -1
        var currentQuestionNum: Int? = null
        var currentSignals = mutableListOf<SegmentSignal>()
        var consumed = mutableSetOf<Int>()
        var inQuestion = false

        fun emitSegment(endOrder: Int) {
            if (currentSourceIds.isEmpty()) return
            val seg = QuestionSegment(
                segmentId = "q$segmentIdCounter",
                sourceIds = currentSourceIds.toList(),
                sourceOrders = currentSourceOrders.toList(),
                startSourceOrder = currentStartOrder,
                endSourceOrder = endOrder,
                originalQuestionNumber = currentQuestionNum,
                signals = currentSignals.toList(),
            )
            segments += seg
            segmentIdCounter++
            // Reset
            currentSourceIds = mutableListOf()
            currentSourceOrders = mutableListOf<Int>()
            currentStartOrder = -1
            currentQuestionNum = null
            currentSignals = mutableListOf()
            inQuestion = false
        }

        for (block in topBlocks) {
            val order = block.sourceOrder
            if (order in consumed) {
                warnings += "sourceOrder $order 被重复消费"
                continue
            }

            val qStart = detectQuestionStart(block, ctx)

            if (qStart != null) {
                // Strong question-start signal: close previous, start new
                if (inQuestion) {
                    emitSegment(order - 1)
                }
                inQuestion = true
                currentStartOrder = order
                currentQuestionNum = qStart.questionNumber
                if (currentSourceIds.isEmpty()) {
                    // Could be either a fresh start or residual from emit
                }
                // Absorb this block as the question's first block
                currentSourceIds += block.sourceId
                currentSourceOrders += order
                consumed += order
                signals += SegmentSignal.QuestionStart(block.sourceId, order, qStart.reason)
                currentSignals += SegmentSignal.QuestionStart(block.sourceId, order, qStart.reason)
                continue
            }

            if (!inQuestion) {
                // Check if this block has option/answer markers — could be a question
                // without explicit numbering (implicit start)
                val hasOption = detectOptionMarkers(block).isNotEmpty()
                val hasAnswer = detectAnswerMarker(block)
                if (hasOption || hasAnswer) {
                    inQuestion = true
                    currentStartOrder = order
                    currentSourceIds += block.sourceId
                    currentSourceOrders += order
                    consumed += order
                    classifyBlockSignals(block, signals, currentSignals)
                    continue
                }
                // No question signal — unassigned
                unassigned += block.sourceId
                consumed += order
                signals += SegmentSignal.UnassignedBlock(block.sourceId, order, "无题目信号")
                continue
            }

            // Already in a question — absorb this block
            currentSourceIds += block.sourceId
            currentSourceOrders += order
            consumed += order
            classifyBlockSignals(block, signals, currentSignals)
        }

        // Emit final segment
        if (inQuestion || currentSourceIds.isNotEmpty()) {
            emitSegment((topBlocks.lastOrNull()?.sourceOrder ?: 0))
        }

        // Any unconsumed blocks become unassigned
        for (block in topBlocks) {
            if (block.sourceOrder !in consumed) {
                unassigned += block.sourceId
                signals += SegmentSignal.UnassignedBlock(block.sourceId, block.sourceOrder, "未消费")
            }
        }

        return SegmentationResult(
            segments = segments,
            unassignedSourceIds = unassigned,
            warnings = warnings,
            signalCount = signals.size,
        )
    }

    // ═══════════════════════════════════════════════════════════
    // Signal classifiers
    // ═══════════════════════════════════════════════════════════

    private fun classifyBlockSignals(
        block: DocumentBlock,
        allSignals: MutableList<SegmentSignal>,
        currentSignals: MutableList<SegmentSignal>,
    ) {
        val options = detectOptionMarkers(block)
        if (options.isNotEmpty()) {
            val sig = SegmentSignal.OptionMarker(block.sourceId, options.map { it.key })
            allSignals += sig; currentSignals += sig
        }
        if (detectAnswerMarker(block)) {
            val sig = SegmentSignal.AnswerMarker(block.sourceId)
            allSignals += sig; currentSignals += sig
        }
        if (detectExplanationMarker(block)) {
            val sig = SegmentSignal.ExplanationMarker(block.sourceId)
            allSignals += sig; currentSignals += sig
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Question start detection
    // ═══════════════════════════════════════════════════════════

    internal data class QuestionStartInfo(
        val questionNumber: Int?,
        val reason: String,
    )

    internal fun detectQuestionStart(
        block: DocumentBlock,
        ctx: SegmentContext,
    ): QuestionStartInfo? {
        if (block !is ParagraphBlock) return null
        val text = extractParagraphText(block)

        // 1. Explicit numbering patterns
        // "1.", "1、", "1．", "1)", "1）"
        val explicitPatterns = listOf(
            Regex("""^\s*(\d{1,4})\s*[\.\．]\s*"""),
            Regex("""^\s*(\d{1,4})\s*[、]\s*"""),
            Regex("""^\s*(\d{1,4})\s*[\)）]\s*"""),
            Regex("""^\s*[（(]\s*(\d{1,4})\s*[）)]\s*"""),
            Regex("""^\s*第\s*(\d{1,4})\s*题\s*"""),
        )
        for (regex in explicitPatterns) {
            regex.find(text)?.let { match ->
                val num = match.groupValues[1].toIntOrNull()
                if (num != null) {
                    return QuestionStartInfo(num, "显式题号 $num")
                }
            }
        }

        // 2. Word NumberingRef
        val numbering = block.numbering
        if (numbering != null) {
            return QuestionStartInfo(null, "Word numId=${numbering.numId} ilvl=${numbering.level}")
        }

        return null
    }

    // ═══════════════════════════════════════════════════════════
    // Option marker detection
    // ═══════════════════════════════════════════════════════════

    private data class DetectedOption(val key: String)

    private fun detectOptionMarkers(block: DocumentBlock): List<DetectedOption> {
        if (block !is ParagraphBlock) return emptyList()
        val text = extractParagraphText(block)
        val markerRegex = Regex("""^\s*([A-Ha-h])\s*[\.\．、:：\)）]""")
        val match = markerRegex.find(text)
        return if (match != null) {
            listOf(DetectedOption(match.groupValues[1].uppercase()))
        } else {
            // Check for inline multi-option: "A. xxx B. yyy" etc.
            val inlineMatches = Regex("""([A-Ha-h])\s*[\.\．、:：\)）]\s*""").findAll(text).toList()
            if (inlineMatches.size >= 2) {
                inlineMatches.map { DetectedOption(it.groupValues[1].uppercase()) }
            } else {
                emptyList()
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Answer / Explanation markers
    // ═══════════════════════════════════════════════════════════

    private fun detectAnswerMarker(block: DocumentBlock): Boolean {
        if (block !is ParagraphBlock) return false
        val text = extractParagraphText(block)
        return Regex("""^\s*(答案|正确答案|参考答案|标准答案)\s*[:：]?""").containsMatchIn(text)
    }

    private fun detectExplanationMarker(block: DocumentBlock): Boolean {
        if (block !is ParagraphBlock) return false
        val text = extractParagraphText(block)
        return Regex("""^\s*(解析|解释|题解)\s*[:：]?""").containsMatchIn(text)
    }

    // ═══════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════

    private fun extractParagraphText(block: ParagraphBlock): String =
        block.content.filterIsInstance<TextContent>().joinToString("") { it.text }

    internal class SegmentContext(
        val numberingDefs: Map<String, NumberingDefinition>,
        val warnings: MutableList<String>,
        val signals: MutableList<SegmentSignal>,
    )
}
