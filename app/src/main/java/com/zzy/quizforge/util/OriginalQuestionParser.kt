package com.zzy.quizforge.util

import com.zzy.quizforge.domain.model.QuestionOption
import com.zzy.quizforge.domain.model.QuestionType
import com.zzy.quizforge.domain.model.QuizQuestion

data class FailedBlock(
    val originalIndex: Int,
    val text: String,
)

data class OriginalQuestionParseResult(
    val questions: List<QuizQuestion>,
    val failedBlocks: List<FailedBlock>,
)

object OriginalQuestionParser {
    private val questionStartRegex = Regex(
        pattern = """^\s*(?:第\s*)?(?:\d{1,4}|[一二三四五六七八九十百]+)\s*(?:题|[\.．、\)、)])\s*""",
    )
    private val optionRegex = Regex("""^\s*([A-Ha-h])\s*${OptionTextSplitter.OPTION_MARKER_CLASS}\s*(.+)\s*$""")
    private val answerRegex = Regex(
        pattern = """(?:答案|正确答案|参考答案|标准答案)\s*[:：]?\s*([A-Ha-h,\s，、]+|对|错|正确|错误|√|×)""",
    )
    private val explanationRegex = Regex("""^\s*(?:解析|解释|题解)\s*[:：]?\s*(.*)$""")
    private val knowledgeRegex = Regex("""^\s*(?:知识点|考点)\s*[:：]?\s*(.*)$""")

    fun parse(text: String): OriginalQuestionParseResult {
        val blocks = splitBlocks(text)
        val questions = mutableListOf<QuizQuestion>()
        val failed = mutableListOf<FailedBlock>()

        blocks.forEachIndexed { index, block ->
            val parsed = parseBlock(block, index + 1)
            if (parsed != null) {
                questions += parsed
            } else if (block.isNotBlank()) {
                // 保留完整原始 block，不截断。长度限制由 ImportRepository 的 repair 层单独处理。
                failed += FailedBlock(originalIndex = index, text = block)
            }
        }

        return OriginalQuestionParseResult(
            questions = questions,
            failedBlocks = failed,
        )
    }

    private fun splitBlocks(text: String): List<String> {
        val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }
        val blocks = mutableListOf<StringBuilder>()
        var current = StringBuilder()

        lines.forEach { line ->
            if (questionStartRegex.containsMatchIn(line) && current.isNotBlank()) {
                blocks += current
                current = StringBuilder()
            }
            current.appendLine(line)
        }
        if (current.isNotBlank()) blocks += current

        return if (blocks.isNotEmpty()) blocks.map { it.toString().trim() } else listOf(text)
    }

    private fun parseBlock(block: String, originalId: Int): QuizQuestion? {
        val lines = block.lines().map { it.trim() }.filter { it.isNotBlank() }
        if (lines.isEmpty()) return null

        val questionLines = mutableListOf<String>()
        val options = linkedMapOf<String, StringBuilder>()
        var answer: List<String>? = null
        var explanation: String? = null
        var knowledge: String? = null
        var currentOptionKey: String? = null
        var inExplanation = false

        lines.forEachIndexed { lineIndex, rawLine ->
            val line = if (lineIndex == 0) {
                rawLine.replace(questionStartRegex, "").trim()
            } else {
                rawLine
            }

            answerRegex.find(line)?.let { match ->
                answer = normalizeAnswer(match.groupValues[1])
                currentOptionKey = null
                inExplanation = false
                return@forEachIndexed
            }

            explanationRegex.find(line)?.let { match ->
                explanation = match.groupValues[1].trim()
                currentOptionKey = null
                inExplanation = true
                return@forEachIndexed
            }

            knowledgeRegex.find(line)?.let { match ->
                knowledge = match.groupValues[1].trim()
                currentOptionKey = null
                inExplanation = false
                return@forEachIndexed
            }

            OptionTextSplitter.splitInlineOptions(line)?.let { inlineOptions ->
                if (inlineOptions.leadingText.isNotBlank()) {
                    questionLines += inlineOptions.leadingText
                }
                inlineOptions.options.forEach { option ->
                    options[option.key] = StringBuilder(option.text)
                }
                currentOptionKey = inlineOptions.options.lastOrNull()?.key
                inExplanation = false
                return@forEachIndexed
            }

            optionRegex.find(line)?.let { match ->
                val key = match.groupValues[1].uppercase()
                options[key] = StringBuilder(match.groupValues[2].trim())
                currentOptionKey = key
                inExplanation = false
                return@forEachIndexed
            }

            when {
                inExplanation -> explanation = listOfNotNull(explanation, line).joinToString("\n")
                currentOptionKey != null -> options[currentOptionKey]?.apply {
                    append("\n")
                    append(line)
                }
                line.isNotBlank() -> questionLines += line
            }
        }

        val parsedOptions = OptionTextSplitter.normalizeOptions(
            options.map { (key, value) ->
                QuestionOption(key = key, text = value.toString().trim())
            },
        )

        val finalAnswer = answer ?: inferTrueFalseAnswer(block)
        val normalizedOptions = if (parsedOptions.isEmpty() && finalAnswer?.singleOrNull() in setOf("A", "B")) {
            listOf(
                QuestionOption(key = "A", text = "对"),
                QuestionOption(key = "B", text = "错"),
            )
        } else {
            parsedOptions
        }
        val questionText = questionLines.joinToString("\n").trim()

        if (questionText.isBlank() || normalizedOptions.isEmpty() || finalAnswer.isNullOrEmpty()) {
            return null
        }

        val optionKeys = normalizedOptions.map { it.key }.toSet()
        if (!finalAnswer.all { it in optionKeys }) return null

        val type = when {
            isTrueFalse(normalizedOptions) -> QuestionType.TRUE_FALSE
            finalAnswer.size > 1 -> QuestionType.MULTIPLE
            else -> QuestionType.SINGLE
        }
        if (type != QuestionType.TRUE_FALSE && normalizedOptions.size < 2) return null

        return QuizQuestion(
            originalId = originalId,
            type = type,
            question = questionText,
            options = normalizedOptions,
            answer = finalAnswer.sorted(),
            explanation = explanation?.takeIf { it.isNotBlank() },
            knowledge = knowledge?.takeIf { it.isNotBlank() },
        )
    }

    private fun normalizeAnswer(raw: String): List<String> = AnswerNormalizer.normalize(raw)

    private fun inferTrueFalseAnswer(block: String): List<String>? {
        val compact = block.replace("\\s".toRegex(), "")
        return when {
            compact.contains("答案对") || compact.contains("答案正确") -> listOf("A")
            compact.contains("答案错") || compact.contains("答案错误") -> listOf("B")
            else -> null
        }
    }

    private fun isTrueFalse(options: List<QuestionOption>): Boolean {
        if (options.size != 2) return false
        val texts = options.map { it.text.trim() }.toSet()
        return ("对" in texts || "正确" in texts) && ("错" in texts || "错误" in texts)
    }
}
