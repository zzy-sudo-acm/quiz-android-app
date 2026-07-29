package com.zzy.quizforge.util.document

import com.zzy.quizforge.domain.model.QuestionOption
import com.zzy.quizforge.domain.model.QuestionType
import com.zzy.quizforge.domain.model.QuizQuestion
import com.zzy.quizforge.util.AnswerNormalizer
import com.zzy.quizforge.util.OptionTextSplitter
import java.util.UUID

/** Strict, deterministic and network-free parser for the advertised standard Word format. */
class StandardFormatParser(
    private val clock: () -> Long = System::currentTimeMillis,
    private val reportId: () -> String = { UUID.randomUUID().toString() },
) {
    fun parse(fileName: String, sourceBlocks: List<ImportSourceBlock>): ImportRecognitionResult {
        val startedAt = clock()
        val nonEmpty = sourceBlocks.filter { it.isNonEmpty }.sortedBy { it.sourceOrder }
        val byId = nonEmpty.associateBy { it.sourceId }
        val ledger = SourceLedger(nonEmpty)
        val records = mutableListOf<ImportReportRecord>()
        val recognized = mutableListOf<RecognizedQuestion>()
        val warnings = mutableListOf<String>()
        var current: Builder? = null

        fun finalizeCurrent() {
            val builder = current ?: return
            current = null
            val finalized = builder.finish(byId)
            if (finalized.question != null && finalized.provenance != null) {
                val index = recognized.size
                recognized += RecognizedQuestion(finalized.question, finalized.provenance, builder.number)
                ledger.mark(builder.sourceIds, SourceLedgerStatus.ACCEPTED_QUESTION)
                records += ImportReportRecord(
                    sourceIds = builder.sourceIds.toList(),
                    originalQuestionNumber = builder.number,
                    rawText = builder.rawText(byId),
                    status = SourceLedgerStatus.ACCEPTED_QUESTION,
                    createdQuestionIndexes = listOf(index),
                )
            } else {
                val reason = finalized.reason ?: ImportFailureReason.SOURCE_NOT_COVERED
                ledger.mark(builder.sourceIds, SourceLedgerStatus.REJECTED_QUESTION)
                records += ImportReportRecord(
                    sourceIds = builder.sourceIds.toList(),
                    originalQuestionNumber = builder.number,
                    rawText = builder.rawText(byId),
                    status = SourceLedgerStatus.REJECTED_QUESTION,
                    reasonCode = reason,
                    reasonMessage = finalized.message,
                )
            }
            warnings += finalized.warnings
        }

        for (block in nonEmpty) {
            if (block.sourceType == SourceBlockType.UNSUPPORTED) {
                finalizeCurrent()
                ledger.mark(listOf(block.sourceId), SourceLedgerStatus.UNSUPPORTED_CONTENT)
                records += ImportReportRecord(
                    sourceIds = listOf(block.sourceId),
                    originalQuestionNumber = null,
                    rawText = block.rawText,
                    status = SourceLedgerStatus.UNSUPPORTED_CONTENT,
                    reasonCode = unsupportedReason(block),
                    reasonMessage = block.unsupportedReason ?: "Word 内容暂不支持",
                )
                continue
            }

            val lines = block.rawText.lines().map(String::trim).filter(String::isNotBlank)
            val manualQuestion = lines.firstOrNull()?.let(::questionPrefix)
            val automaticQuestion = automaticQuestion(block)
            val startsQuestion = manualQuestion != null || automaticQuestion != null

            if (startsQuestion) {
                finalizeCurrent()
                val info = manualQuestion ?: automaticQuestion!!
                current = Builder(info.number).also { builder ->
                    builder.sourceIds += block.sourceId
                    builder.stemSources += block.sourceId
                    val firstLine = lines.firstOrNull().orEmpty()
                    val stem = if (manualQuestion != null) {
                        firstLine.substring(info.prefixLength).trim()
                    } else {
                        firstLine
                    }
                    if (stem.isNotBlank()) builder.stemParts += stem
                    lines.drop(1).forEach { builder.consumeLine(it, block.sourceId) }
                    builder.attachImages(block.images, block.rawText)
                    builder.checkImageFormats(block.images)
                }
                continue
            }

            if (block.numbering != null && block.numbering.displayText == null) {
                if (current == null) current = Builder(null)
                current?.apply {
                    sourceIds += block.sourceId
                    addError(
                        ImportFailureReason.AUTO_NUMBERING_UNRESOLVED,
                        "Word 自动编号无法还原，请在该题补充可见题号",
                    )
                }
                continue
            }

            val builder = current
            if (builder == null) {
                // Titles/instructions are allowed, but are explicitly covered rather than discarded.
                ledger.mark(listOf(block.sourceId), SourceLedgerStatus.NON_QUESTION_CONTENT)
                records += ImportReportRecord(
                    sourceIds = listOf(block.sourceId),
                    originalQuestionNumber = null,
                    rawText = block.rawText,
                    status = SourceLedgerStatus.NON_QUESTION_CONTENT,
                    reasonMessage = "题目前的标题或说明文字",
                )
                continue
            }

            builder.sourceIds += block.sourceId
            if (lines.isEmpty() && block.images.isNotEmpty()) {
                builder.attachImages(block.images, block.rawText)
            } else {
                lines.forEach { builder.consumeLine(it, block.sourceId, automaticOption(block)) }
                builder.attachImages(block.images, block.rawText)
            }
            builder.checkImageFormats(block.images)
        }
        finalizeCurrent()

        // A complete report is mandatory. Any missed source is converted into a visible rejection.
        ledger.uncoveredIds().forEach { sourceId ->
            val source = byId.getValue(sourceId)
            ledger.mark(listOf(sourceId), SourceLedgerStatus.REJECTED_QUESTION)
            records += ImportReportRecord(
                sourceIds = listOf(sourceId),
                originalQuestionNumber = null,
                rawText = source.rawText,
                status = SourceLedgerStatus.REJECTED_QUESTION,
                reasonCode = ImportFailureReason.SOURCE_NOT_COVERED,
                reasonMessage = "原文没有确定归属",
            )
        }

        val accepted = records.count { it.status == SourceLedgerStatus.ACCEPTED_QUESTION }
        val rejected = records.count { it.status == SourceLedgerStatus.REJECTED_QUESTION }
        val report = ImportReport(
            reportId = reportId(),
            fileName = fileName,
            importMode = ImportMode.STANDARD,
            startedAt = startedAt,
            finishedAt = clock(),
            totalSourceBlocks = nonEmpty.size,
            candidateQuestionCount = accepted + rejected,
            acceptedQuestionCount = accepted,
            rejectedQuestionCount = rejected,
            nonQuestionCount = ledger.counts()[SourceLedgerStatus.NON_QUESTION_CONTENT] ?: 0,
            unsupportedCount = ledger.counts()[SourceLedgerStatus.UNSUPPORTED_CONTENT] ?: 0,
            imageCount = nonEmpty.sumOf { it.images.size },
            tableCount = nonEmpty.mapNotNull { it.table?.tableSourceId }.distinct().size,
            usedApi = false,
            apiRequestCount = 0,
            warnings = warnings.distinct(),
            records = records,
            ledgerComplete = ledger.isComplete(),
        )
        return ImportRecognitionResult(recognized, report)
    }

    private fun unsupportedReason(block: ImportSourceBlock): ImportFailureReason = when {
        block.unsupportedReason.orEmpty().contains("文本框") -> ImportFailureReason.TEXTBOX_UNSUPPORTED
        block.unsupportedReason.orEmpty().contains("对象") -> ImportFailureReason.EMBEDDED_OBJECT_UNSUPPORTED
        else -> ImportFailureReason.SOURCE_NOT_COVERED
    }

    private fun automaticQuestion(block: ImportSourceBlock): PrefixInfo? {
        val numbering = block.numbering ?: return null
        if (numbering.level != 0) return null
        val display = numbering.displayText?.trim().orEmpty()
        val number = Regex("""^[（(]?\s*(\d{1,6})""").find(display)?.groupValues?.get(1)?.toIntOrNull()
            ?: return null
        return PrefixInfo(number, 0)
    }

    private fun automaticOption(block: ImportSourceBlock): String? {
        val display = block.numbering?.displayText?.trim().orEmpty()
        return Regex("""^([A-Ha-h])(?:[.．、:：)）]|$)""")
            .find(display)?.groupValues?.get(1)?.uppercase()
    }

    private fun questionPrefix(text: String): PrefixInfo? {
        val match = QUESTION_PREFIX.find(text) ?: return null
        val number = match.groupValues.drop(1).firstOrNull(String::isNotBlank)?.toIntOrNull()
        return PrefixInfo(number, match.range.last + 1)
    }

    private data class PrefixInfo(val number: Int?, val prefixLength: Int)

    private class Builder(val number: Int?) {
        val sourceIds = linkedSetOf<String>()
        val stemParts = mutableListOf<String>()
        val stemSources = linkedSetOf<String>()
        val optionParts = linkedMapOf<String, MutableList<String>>()
        val optionSources = linkedMapOf<String, LinkedHashSet<String>>()
        val optionImages = linkedMapOf<String, MutableList<String>>()
        val stemImages = mutableListOf<String>()
        val answerSources = linkedSetOf<String>()
        val explanationSources = linkedSetOf<String>()
        val knowledgeSources = linkedSetOf<String>()
        val errors = mutableListOf<Pair<ImportFailureReason, String>>()
        val warnings = mutableListOf<String>()
        var rawAnswer: String? = null
        var explanation: String? = null
        var knowledge: String? = null
        var explicitType: QuestionType? = null
        var currentOption: String? = null
        var lastField: Field = Field.STEM

        fun consumeLine(line: String, sourceId: String, automaticOptionKey: String? = null) {
            TYPE_LINE.matchEntire(line)?.let { match ->
                explicitType = when (match.groupValues[1]) {
                    "单选题" -> QuestionType.SINGLE
                    "多选题" -> QuestionType.MULTIPLE
                    "判断题" -> QuestionType.TRUE_FALSE
                    else -> null
                }
                if (explicitType == null) addError(ImportFailureReason.INVALID_QUESTION_TYPE, "题型只支持单选题、多选题和判断题")
                currentOption = null
                lastField = Field.TYPE
                return
            }
            ANSWER_LINE.matchEntire(line)?.let { match ->
                rawAnswer = match.groupValues[1].trim()
                answerSources += sourceId
                currentOption = null
                lastField = Field.ANSWER
                return
            }
            EXPLANATION_LINE.matchEntire(line)?.let { match ->
                explanation = match.groupValues[1].trim()
                explanationSources += sourceId
                currentOption = null
                lastField = Field.EXPLANATION
                return
            }
            KNOWLEDGE_LINE.matchEntire(line)?.let { match ->
                knowledge = match.groupValues[1].trim()
                knowledgeSources += sourceId
                currentOption = null
                lastField = Field.KNOWLEDGE
                return
            }

            OptionTextSplitter.splitInlineOptions(line)?.let { split ->
                if (split.leadingText.isNotBlank()) {
                    if (optionParts.isEmpty()) {
                        stemParts += split.leadingText
                        stemSources += sourceId
                    } else {
                        addError(ImportFailureReason.SOURCE_NOT_COVERED, "选项前存在无法归属的文字")
                    }
                }
                split.options.forEach { option -> addOption(option.key, option.text, sourceId) }
                currentOption = split.options.last().key
                lastField = Field.OPTION
                return
            }

            val optionMatch = OPTION_LINE.matchEntire(line)
            if (optionMatch != null || automaticOptionKey != null) {
                val key = automaticOptionKey ?: optionMatch!!.groupValues[1].uppercase()
                val text = if (automaticOptionKey != null) line else optionMatch!!.groupValues[2].trim()
                addOption(key, text, sourceId)
                currentOption = key
                lastField = Field.OPTION
                return
            }

            when {
                lastField == Field.EXPLANATION -> {
                    explanation = listOfNotNull(explanation, line).filter(String::isNotBlank).joinToString("\n")
                    explanationSources += sourceId
                }
                lastField == Field.KNOWLEDGE -> {
                    knowledge = listOfNotNull(knowledge, line).filter(String::isNotBlank).joinToString("\n")
                    knowledgeSources += sourceId
                }
                currentOption != null && rawAnswer == null -> {
                    optionParts.getValue(currentOption!!).add(line)
                    optionSources.getValue(currentOption!!).add(sourceId)
                }
                optionParts.isEmpty() && rawAnswer == null -> {
                    stemParts += line
                    stemSources += sourceId
                }
                else -> addError(ImportFailureReason.SOURCE_NOT_COVERED, "答案后的文字缺少“解析：”或“知识点：”标识")
            }
        }

        fun attachImages(images: List<SourceImageRef>, rawText: String) {
            images.forEach { image ->
                val path = image.localPath ?: return@forEach
                when (val owner = sourceImageOwner(rawText, image.charOffset)) {
                    IMAGE_OWNER_STEM -> stemImages += path
                    IMAGE_OWNER_OTHER -> addError(ImportFailureReason.IMAGE_OWNER_UNKNOWN, "图片位于答案或说明字段附近，无法确定归属")
                    null -> when (lastField) {
                        Field.OPTION -> currentOption?.let { key -> optionImages.getOrPut(key) { mutableListOf() }.add(path) }
                            ?: stemImages.add(path)
                        Field.ANSWER, Field.EXPLANATION, Field.KNOWLEDGE, Field.TYPE ->
                            addError(ImportFailureReason.IMAGE_OWNER_UNKNOWN, "图片位于答案或说明字段附近，无法确定归属")
                        Field.STEM -> stemImages.add(path)
                    }
                    else -> if (owner.startsWith("option:")) {
                        val key = owner.substringAfter(':')
                        optionImages.getOrPut(key) { mutableListOf() }.add(path)
                    } else {
                        addError(ImportFailureReason.IMAGE_OWNER_UNKNOWN, "图片无法确定题干或选项归属")
                    }
                }
            }
        }

        fun checkImageFormats(images: List<SourceImageRef>) {
            if (images.any { !it.supportedForDisplay }) {
                warnings += "第${number ?: "?"}题包含 EMF、WMF 或未知图片格式，需人工确认"
            }
            if (images.any { it.localPath == null }) {
                addError(ImportFailureReason.IMAGE_OWNER_UNKNOWN, "图片引用存在，但媒体文件无法读取")
            }
        }

        fun addError(reason: ImportFailureReason, message: String) {
            if (errors.none { it.first == reason && it.second == message }) errors += reason to message
        }

        fun finish(sourceById: Map<String, ImportSourceBlock>): Finalized {
            val stem = stemParts.joinToString("\n").trim()
            val answers = rawAnswer?.let(AnswerNormalizer::normalize).orEmpty()
            val options = optionParts.map { (key, parts) ->
                QuestionOption(
                    key = key,
                    text = parts.joinToString("\n").trim(),
                    imageUri = optionImages[key]?.firstOrNull(),
                    imageUris = optionImages[key].orEmpty().distinct(),
                )
            }
            val duplicateKeys = optionParts.keys.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
            if (duplicateKeys.isNotEmpty()) addError(ImportFailureReason.DUPLICATE_OPTION, "选项标号重复：${duplicateKeys.joinToString()}")
            if (stem.isBlank()) addError(ImportFailureReason.MISSING_STEM, "第${number ?: "?"}题缺少题干")
            if (answers.isEmpty()) addError(ImportFailureReason.MISSING_ANSWER, "第${number ?: "?"}题缺少明确答案")

            val explicitJudgmentAnswer = rawAnswer?.trim() in setOf("对", "错", "正确", "错误", "√", "×")
            val inferredTrueFalse = explicitType == QuestionType.TRUE_FALSE || (options.isEmpty() && explicitJudgmentAnswer)
            val finalOptions = if (inferredTrueFalse && options.isEmpty()) {
                listOf(QuestionOption("A", "对"), QuestionOption("B", "错"))
            } else options
            if (finalOptions.isEmpty() || (!inferredTrueFalse && finalOptions.size < 2)) {
                addError(ImportFailureReason.MISSING_OPTIONS, "第${number ?: "?"}题缺少至少两个明确选项")
            }
            val keys = finalOptions.map { it.key }.toSet()
            if (answers.isNotEmpty() && !answers.all { it in keys }) {
                addError(ImportFailureReason.ANSWER_NOT_IN_OPTIONS, "答案 ${answers.joinToString()} 不在现有选项中")
            }
            val inferredType = when {
                inferredTrueFalse -> QuestionType.TRUE_FALSE
                answers.size > 1 -> QuestionType.MULTIPLE
                else -> QuestionType.SINGLE
            }
            explicitType?.let { declared ->
                if (declared == QuestionType.SINGLE && answers.size > 1) {
                    addError(ImportFailureReason.INVALID_QUESTION_TYPE, "题型标为单选题，但答案包含多个选项")
                }
                if (declared == QuestionType.MULTIPLE && answers.size < 2) {
                    addError(ImportFailureReason.INVALID_QUESTION_TYPE, "题型标为多选题，但答案不足两个选项")
                }
            }
            if (errors.isNotEmpty()) {
                val first = errors.first()
                return Finalized(null, null, first.first, first.second, warnings + errors.drop(1).map { it.second })
            }

            val question = QuizQuestion(
                originalId = number,
                type = explicitType ?: inferredType,
                question = stem,
                options = finalOptions,
                answer = answers,
                explanation = explanation?.takeIf(String::isNotBlank),
                knowledge = knowledge?.takeIf(String::isNotBlank),
                imageUri = stemImages.firstOrNull(),
                imageUris = stemImages.distinct(),
            )
            val provenance = QuestionProvenance(
                sourceIds = sourceIds.toList(),
                questionSource = stemSources.toList(),
                optionSources = optionSources.mapValues { it.value.toList() },
                answerSource = answerSources.toList(),
                explanationSource = explanationSources.toList(),
                knowledgeSource = knowledgeSources.toList(),
            )
            return Finalized(question, provenance, warnings = warnings)
        }

        fun rawText(sourceById: Map<String, ImportSourceBlock>): String =
            sourceIds.mapNotNull { sourceById[it]?.rawText }.joinToString("\n").trim()

        private fun addOption(key: String, text: String, sourceId: String) {
            val normalized = key.uppercase()
            if (normalized in optionParts) {
                addError(ImportFailureReason.DUPLICATE_OPTION, "选项 $normalized 重复")
                optionParts.getValue(normalized).add(text)
            } else {
                optionParts[normalized] = mutableListOf(text)
            }
            optionSources.getOrPut(normalized) { linkedSetOf() }.add(sourceId)
        }

        private enum class Field { STEM, OPTION, ANSWER, EXPLANATION, KNOWLEDGE, TYPE }
    }

    private data class Finalized(
        val question: QuizQuestion?,
        val provenance: QuestionProvenance?,
        val reason: ImportFailureReason? = null,
        val message: String? = null,
        val warnings: List<String> = emptyList(),
    )

    companion object {
        private val QUESTION_PREFIX = Regex(
            """^\s*(?:(?:第\s*(\d{1,6})\s*题)|(?:[（(]\s*(\d{1,6})\s*[）)])|(?:(\d{1,6})\s*[.．、)）]))\s*""",
        )
        private val OPTION_LINE = Regex("""^\s*([A-Ha-h])\s*[.．、:：)）]\s*(.+?)\s*$""")
        private val ANSWER_LINE = Regex("""^\s*(?:答案|正确答案|参考答案|标准答案)\s*[:：]?\s*(.*?)\s*$""")
        private val EXPLANATION_LINE = Regex("""^\s*(?:解析|解释|题解)\s*[:：]?\s*(.*?)\s*$""")
        private val KNOWLEDGE_LINE = Regex("""^\s*(?:知识点|考点)\s*[:：]?\s*(.*?)\s*$""")
        private val TYPE_LINE = Regex("""^\s*题型\s*[:：]?\s*(\S+)\s*$""")
    }
}
