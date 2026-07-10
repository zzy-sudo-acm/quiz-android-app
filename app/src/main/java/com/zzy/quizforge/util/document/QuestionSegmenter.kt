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

        val topBlocks = document.blocks
        if (topBlocks.isEmpty()) {
            return SegmentationResult(emptyList(), emptyList(), emptyList(), 0)
        }

        val ctx = SegmentContext(
            numberingDefs = document.numberingDefinitions,
            warnings = warnings,
            signals = signals,
        )

        var segmentIdCounter = 0
        var currentSourceIds = mutableListOf<String>()
        var currentSourceOrders = mutableListOf<Int>()
        var currentQuestionNum: Int? = null
        var currentSignals = mutableListOf<SegmentSignal>()
        var consumed = mutableSetOf<Int>()
        var inQuestion = false
        val pendingNeutral = mutableListOf<DocumentBlock>()

        fun flushPendingToUnassigned() {
            for (b in pendingNeutral) {
                unassigned += b.sourceId
                consumed += b.sourceOrder
                signals += SegmentSignal.UnassignedBlock(b.sourceId, b.sourceOrder, "无法绑定到任何题目")
            }
            pendingNeutral.clear()
        }

        fun emitSegment() {
            if (currentSourceIds.isEmpty()) return
            val orders = currentSourceOrders.toList()
            val seg = QuestionSegment(
                segmentId = "q$segmentIdCounter",
                sourceIds = currentSourceIds.toList(),
                sourceOrders = orders,
                startSourceOrder = orders.first(),
                endSourceOrder = orders.last(),
                originalQuestionNumber = currentQuestionNum,
                signals = currentSignals.toList(),
            )
            segments += seg
            segmentIdCounter++
            currentSourceIds = mutableListOf()
            currentSourceOrders = mutableListOf<Int>()
            currentQuestionNum = null
            currentSignals = mutableListOf()
            inQuestion = false
        }

        fun isStemCandidate(block: DocumentBlock): Boolean {
            if (block !is ParagraphBlock) return false
            if (block.content.isEmpty()) return false
            val text = extractParagraphText(block)
            if (text.isBlank()) return false
            if (detectAnswerMarker(block)) return false
            if (detectExplanationMarker(block)) return false
            if (detectOptionMarkers(block).isNotEmpty()) return false
            return true
        }

        for (block in topBlocks) {
            val order = block.sourceOrder
            if (order in consumed) {
                warnings += "sourceOrder $order 被重复消费"
                continue
            }

            val qStart = detectQuestionStart(block, ctx)

            if (qStart != null) {
                // Strong question start: flush pending, close previous, start new
                flushPendingToUnassigned()
                if (inQuestion) emitSegment()
                inQuestion = true
                currentQuestionNum = qStart.questionNumber
                currentSourceIds += block.sourceId
                currentSourceOrders += order
                consumed += order
                signals += SegmentSignal.QuestionStart(block.sourceId, order, qStart.reason)
                currentSignals += SegmentSignal.QuestionStart(block.sourceId, order, qStart.reason)
                classifyBlockSignals(block, signals, currentSignals)
                continue
            }

            if (!inQuestion) {
                val hasOption = detectOptionMarkers(block).isNotEmpty()
                val hasAnswer = detectAnswerMarker(block)

                if (hasAnswer && !hasOption) {
                    // Answer alone cannot start a question — keep as pending/unassigned
                    pendingNeutral += block
                    continue
                }

                if (hasOption) {
                    // Implicit question start via option markers
                    inQuestion = true
                    // Try to bind the most recent pending stem candidate
                    val stemIdx = pendingNeutral.indexOfLast(::isStemCandidate)
                    if (stemIdx >= 0) {
                        val stem = pendingNeutral.removeAt(stemIdx)
                        // Flush all remaining pending (before the stem) to unassigned
                        flushPendingToUnassigned()
                        // Absorb stem
                        currentSourceIds += stem.sourceId
                        currentSourceOrders += stem.sourceOrder
                        consumed += stem.sourceOrder
                        classifyBlockSignals(stem, signals, currentSignals)
                    } else {
                        // No stem to bind — flush everything
                        flushPendingToUnassigned()
                    }
                    currentSourceIds += block.sourceId
                    currentSourceOrders += order
                    consumed += order
                    classifyBlockSignals(block, signals, currentSignals)
                    continue
                }

                // Neutral block — defer decision
                pendingNeutral += block
                continue
            }

            // Already in a question — absorb
            currentSourceIds += block.sourceId
            currentSourceOrders += order
            consumed += order
            classifyBlockSignals(block, signals, currentSignals)
        }

        // End of blocks: flush remaining
        if (inQuestion) emitSegment()
        flushPendingToUnassigned()

        // Any unconsumed → unassigned
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

        // 1. Explicit numbering patterns: "1.", "1、", "1．", "1)", "1）", "（1）", "第1题"
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
                if (num != null) return QuestionStartInfo(num, "显式题号 $num")
            }
        }

        // 2. Word NumberingRef — only strong question-like formats at level 0
        val numbering = block.numbering
        if (numbering != null) {
            val def = ctx.numberingDefs[numbering.numId]
            if (def == null) {
                ctx.warnings += "NumberingRef numId=${numbering.numId} 未在 numbering.xml 中找到定义"
                return null
            }
            val levelDef = def.levels[numbering.level]
            if (levelDef == null) {
                ctx.warnings += "NumberingRef numId=${numbering.numId} level=${numbering.level} 在定义中不存在"
                return null
            }
            // Only level 0 numbering counts as strong question start
            if (numbering.level != 0) return null
            // Only numeric formats: decimal, decimalZero
            val fmt = levelDef.numFmt?.lowercase()
            if (fmt in setOf("decimal", "decimalzero")) {
                return QuestionStartInfo(null, "Word numbering numId=${numbering.numId} decimal level=0")
            }
            // Other formats (upperLetter, bullet, etc.) are NOT question starts
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
