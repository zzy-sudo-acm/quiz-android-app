package com.zzy.quizforge.util

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.zzy.quizforge.domain.model.QuestionOption
import com.zzy.quizforge.domain.model.QuestionType
import com.zzy.quizforge.domain.model.QuizQuestion

data class QuestionParseResult(
    val questions: List<QuizQuestion>,
    val partial: Boolean,
)

object JsonValidator {
    fun parseQuestions(raw: String): List<QuizQuestion> =
        parseQuestionsResult(raw).questions

    /**
     * 严格解析 DeepSeek API 返回的"修复后的单题"。
     *
     * 接受：
     *   - 字面量 "null" 或空字符串 → 返回 null
     *   - 一个完整的 JSON 对象 {...}
     *   - 仅取数组 [...] 的第一个对象
     *   - 自动剥离 Markdown 代码块包装
     *
     * 严格校验：
     *   - question 非空
     *   - options 非空且文本非空
     *   - answer 非空
     *   - answer 中所有 key 必须存在于 options 的 key 集合中
     *   - truefalse 必须仅有 A/B 两个选项，文本须为 对/正确/√ 与 错/错误/×
     *   - 校验失败一律返回 null（调用方决定跳过）
     */
    fun parseRepairedQuestion(raw: String): QuizQuestion? {
        val stripped = stripMarkdownFence(raw).trim()
        if (stripped.isEmpty()) return null
        if (stripped.equals("null", ignoreCase = true)) return null

        val objectJson = extractFirstJsonObject(stripped) ?: return null

        val item = runCatching {
            JsonParser.parseString(objectJson).asJsonObject
        }.getOrNull() ?: return null

        val question = item.stringOrNull("question")?.trim().orEmpty()
        if (question.isBlank()) return null

        val type = QuestionType.fromRawStrict(item.stringOrNull("type")) ?: return null
        val options = runCatching {
            parseOptions(type, item.get("options"))
        }.getOrDefault(emptyList())
        if (options.isEmpty()) return null
        if (type != QuestionType.TRUE_FALSE && options.size < 2) return null

        val answer = runCatching { parseAnswer(item.get("answer")) }.getOrDefault(emptyList())
        if (answer.isEmpty()) return null

        val optionKeys = options.map { it.key }.toSet()
        if (!answer.all { it in optionKeys }) return null

        // 严格不变量：题型与答案数量必须匹配
        when (type) {
            QuestionType.SINGLE -> if (answer.size != 1) return null
            QuestionType.MULTIPLE -> if (answer.size < 2) return null
            QuestionType.TRUE_FALSE -> if (answer.size != 1) return null
        }

        if (type == QuestionType.TRUE_FALSE) {
            if (options.size != 2) return null
            if (options.map { it.key }.toSet() != setOf("A", "B")) return null
            val acceptedTrue = setOf("对", "正确", "√")
            val acceptedFalse = setOf("错", "错误", "×")
            val textByKey = options.associate { it.key to it.text.trim() }
            val textA = textByKey["A"].orEmpty()
            val textB = textByKey["B"].orEmpty()
            if (textA !in acceptedTrue || textB !in acceptedFalse) return null
        }

        val normalizedOptions = if (type == QuestionType.TRUE_FALSE) {
            listOf(QuestionOption("A", "对"), QuestionOption("B", "错"))
        } else {
            options
        }

        return QuizQuestion(
            originalId = item.intOrNull("id"),
            type = type,
            question = question,
            options = normalizedOptions,
            answer = answer,
            explanation = item.stringOrNull("explanation")?.takeIf { it.isNotBlank() },
            knowledge = item.stringOrNull("knowledge")?.takeIf { it.isNotBlank() },
            image = item.stringOrNull("image"),
            imageUri = item.stringOrNull("imageUri"),
        )
    }

    private fun extractFirstJsonObject(raw: String): String? {
        val text = raw.trim()
        val start = text.indexOf('{')
        if (start < 0) return null

        var inString = false
        var escaped = false
        var depth = 0
        for (index in start until text.length) {
            val char = text[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> inString = false
                }
                continue
            }
            when (char) {
                '"' -> inString = true
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) {
                        return text.substring(start, index + 1)
                    }
                }
            }
        }
        return null
    }

    fun parseQuestionsResult(raw: String): QuestionParseResult {
        val normalized = stripMarkdownFence(raw)
        val candidates = buildList {
            extractCompleteJsonArray(normalized)?.let(::add)
            extractRecoverableJsonArray(normalized)?.let(::add)
            extractByLastObjectBoundary(normalized)?.let(::add)
        }.distinct()

        candidates.forEach { candidate ->
            val questions = runCatching {
                JsonParser.parseString(candidate).asJsonArray.mapIndexedNotNull { index, element ->
                    runCatching { parseQuestion(index, element.asJsonObject) }.getOrNull()
                }
            }.getOrDefault(emptyList())
            if (questions.isNotEmpty()) {
                return QuestionParseResult(
                    questions = questions,
                    partial = !isCompleteJsonArray(normalized) || candidate != extractCompleteJsonArray(normalized),
                )
            }
        }

        val objectQuestions = extractCompleteQuestionObjects(normalized).mapIndexedNotNull { index, objectJson ->
            runCatching {
                parseQuestion(index, JsonParser.parseString(objectJson).asJsonObject)
            }.getOrNull()
        }
        if (objectQuestions.isNotEmpty()) {
            return QuestionParseResult(questions = objectQuestions, partial = true)
        }

        throw IllegalArgumentException("没有解析到有效题目")
    }

    private fun stripMarkdownFence(raw: String): String {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("```")) return trimmed
        return trimmed
            .removePrefix("```json")
            .removePrefix("```JSON")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
    }

    private fun parseArrayCandidate(candidate: String): QuestionParseResult {
        val array = JsonParser.parseString(candidate).asJsonArray
        val questions = array.mapIndexedNotNull { index, element ->
            runCatching { parseQuestion(index, element.asJsonObject) }.getOrNull()
        }
        require(questions.isNotEmpty()) { "没有解析到有效题目" }
        return QuestionParseResult(
            questions = questions,
            partial = false,
        )
    }

    private fun parseQuestion(index: Int, item: JsonObject): QuizQuestion {
        val type = QuestionType.fromRaw(item.stringOrNull("type"))
        val options = parseOptions(type, item.get("options"))
        val answer = parseAnswer(item.get("answer"))
        val question = item.stringOrNull("question")?.trim().orEmpty()
        require(question.isNotBlank()) { "第 ${index + 1} 题缺少题干" }
        require(options.isNotEmpty()) { "第 ${index + 1} 题缺少选项" }
        require(type == QuestionType.TRUE_FALSE || options.size >= 2) { "第 ${index + 1} 题选择题选项不足" }
        require(answer.isNotEmpty()) { "第 ${index + 1} 题缺少答案" }
        val optionKeys = options.map { it.key }.toSet()
        require(answer.all { it in optionKeys }) { "第 ${index + 1} 题答案包含未解析出的选项" }

        return QuizQuestion(
            originalId = item.intOrNull("id"),
            type = type,
            question = question,
            options = options,
            answer = answer,
            explanation = item.stringOrNull("explanation"),
            knowledge = item.stringOrNull("knowledge"),
            image = item.stringOrNull("image"),
            imageUri = item.stringOrNull("imageUri"),
        )
    }

    private fun parseOptions(type: QuestionType, element: JsonElement?): List<QuestionOption> {
        if (element == null || element.isJsonNull) {
            return if (type == QuestionType.TRUE_FALSE) {
                listOf(QuestionOption("A", "对"), QuestionOption("B", "错"))
            } else {
                emptyList()
            }
        }

        val parsedOptions = when {
            element.isJsonArray -> element.asJsonArray.mapIndexedNotNull { index, option ->
                parseOptionElement(option, ('A'.code + index).toChar().toString())
            }
            element.isJsonObject -> parseOptionObject(element.asJsonObject)
            element.isJsonPrimitive -> listOf(OptionTextSplitter.optionFromRaw(element.asString, "A"))
            else -> emptyList()
        }
        return OptionTextSplitter.normalizeOptions(parsedOptions)
    }

    private fun parseOptionElement(element: JsonElement, fallbackKey: String): QuestionOption? {
        if (element.isJsonNull) return null

        val option = if (element.isJsonObject) {
            val obj = element.asJsonObject
            QuestionOption(
                key = obj.stringOrNull("key")?.uppercase() ?: fallbackKey,
                text = obj.stringOrNull("text") ?: obj.stringOrNull("label") ?: "",
                image = obj.stringOrNull("image"),
                imageUri = obj.stringOrNull("imageUri"),
            )
        } else {
            OptionTextSplitter.optionFromRaw(element.asString, fallbackKey)
        }
        return option.takeIf { it.text.isNotBlank() }
    }

    private fun parseOptionObject(obj: JsonObject): List<QuestionOption> {
        val singleOption = parseOptionElement(obj, "A")
        if (singleOption != null && obj.has("key")) return listOf(singleOption)

        return obj.entrySet().mapNotNull { (key, value) ->
            val optionKey = key.trim().uppercase().takeIf { it.matches(Regex("[A-H]")) } ?: return@mapNotNull null
            QuestionOption(
                key = optionKey,
                text = value.takeUnless { it.isJsonNull }?.asString.orEmpty(),
            ).takeIf { it.text.isNotBlank() }
        }
    }

    private fun parseAnswer(element: JsonElement?): List<String> {
        require(element != null && !element.isJsonNull) { "缺少答案" }
        val values = if (element.isJsonArray) {
            element.asJsonArray.map { it.asString }
        } else {
            listOf(element.asString)
        }
        return AnswerNormalizer.normalizeFromJsonStrings(values)
    }

    private fun extractCompleteJsonArray(raw: String): String? {
        val text = raw.trim()
        val start = text.indexOf('[')
        if (start < 0) return null

        var inString = false
        var escaped = false
        var arrayDepth = 0
        for (index in start until text.length) {
            val char = text[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> inString = false
                }
                continue
            }

            when (char) {
                '"' -> inString = true
                '[' -> arrayDepth += 1
                ']' -> {
                    arrayDepth -= 1
                    if (arrayDepth == 0) {
                        return text.substring(start, index + 1)
                    }
                }
            }
        }
        return null
    }

    private fun extractRecoverableJsonArray(raw: String): String? {
        val text = raw.trim()
        val start = text.indexOf('[')
        if (start < 0) return null

        var inString = false
        var escaped = false
        var objectDepth = 0
        var arrayDepth = 0
        var lastCompleteObjectEnd = -1

        for (index in start until text.length) {
            val char = text[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> inString = false
                }
                continue
            }

            when (char) {
                '"' -> inString = true
                '[' -> arrayDepth += 1
                ']' -> {
                    arrayDepth -= 1
                    if (arrayDepth == 0 && lastCompleteObjectEnd > start) {
                        return text.substring(start, index + 1)
                    }
                }
                '{' -> objectDepth += 1
                '}' -> {
                    if (objectDepth > 0) objectDepth -= 1
                    if (objectDepth == 0 && arrayDepth == 1) {
                        lastCompleteObjectEnd = index
                    }
                }
            }
        }

        if (lastCompleteObjectEnd <= start) return null
        return text.substring(start, lastCompleteObjectEnd + 1) + "]"
    }

    private fun extractByLastObjectBoundary(raw: String): String? {
        val text = raw.trim()
        val start = text.indexOf('[')
        if (start < 0) return null

        val commaBoundary = text.lastIndexOf("},")
        val objectBoundary = text.lastIndexOf('}')
        val end = maxOf(commaBoundary, objectBoundary)
        if (end <= start) return null

        val inclusiveEnd = if (commaBoundary >= objectBoundary) commaBoundary else objectBoundary
        return text.substring(start, inclusiveEnd + 1) + "]"
    }

    private fun extractCompleteQuestionObjects(raw: String): List<String> {
        val text = raw.trim()
        val start = text.indexOf('[')
        if (start < 0) return emptyList()

        val objects = mutableListOf<String>()
        var inString = false
        var escaped = false
        var arrayDepth = 0
        var objectDepth = 0
        var objectStart = -1

        for (index in start until text.length) {
            val char = text[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> inString = false
                }
                continue
            }

            when (char) {
                '"' -> inString = true
                '[' -> arrayDepth += 1
                ']' -> if (arrayDepth > 0) arrayDepth -= 1
                '{' -> {
                    if (arrayDepth == 1 && objectDepth == 0) {
                        objectStart = index
                    }
                    objectDepth += 1
                }
                '}' -> {
                    if (objectDepth > 0) objectDepth -= 1
                    if (arrayDepth == 1 && objectDepth == 0 && objectStart >= 0) {
                        objects += text.substring(objectStart, index + 1)
                        objectStart = -1
                    }
                }
            }
        }

        return objects
    }

    private fun isCompleteJsonArray(raw: String): Boolean =
        runCatching {
            val json = extractCompleteJsonArray(raw) ?: return@runCatching false
            JsonParser.parseString(json).isJsonArray
        }.getOrDefault(false)

    private fun JsonObject.stringOrNull(name: String): String? =
        get(name)?.takeUnless { it.isJsonNull }?.asString

    private fun JsonObject.intOrNull(name: String): Int? =
        get(name)?.takeUnless { it.isJsonNull }?.asInt
}
