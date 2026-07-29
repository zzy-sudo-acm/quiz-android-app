package com.zzy.quizforge.data.remote

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.zzy.quizforge.util.document.SmartRecognitionStage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

data class DeepSeekUsage(
    val promptTokens: Long?,
    val completionTokens: Long?,
    val totalTokens: Long?,
)

data class DeepSeekCompletion(
    val content: String,
    val finishReason: String?,
    val usage: DeepSeekUsage?,
)

class DeepSeekApi(
    private val streamingClient: OkHttpClient,
    private val repairClient: OkHttpClient,
    private val retryDelay: suspend (Long) -> Unit = { delay(it) },
) {
    private val gson = Gson()

    companion object {
        /**
         * DeepSeek API 模型名称。
         *
         * 使用 DeepSeek V4 系列 flash 模型。
         * repair 属于结构整理任务，flash 模型成本更低、速度更快。
         *
         * 所有 API 调用方法必须通过此常量引用模型名，不得分别硬编码。
         */
        val DEFAULT_MODEL: String = DeepSeekModelCatalog.defaultTier.modelName

        /** 单次 repair 请求最大重试次数（不含首次调用）。 */
        const val MAX_REPAIR_RETRIES = 2

        /** 重试退避基值（毫秒）。 */
        const val RETRY_BASE_DELAY_MS = 1000L
    }

    fun streamQuestions(apiKey: String, prompt: String): Flow<String> = flow {
        val body = gson.toJson(
            mapOf(
                "model" to DEFAULT_MODEL,
                "stream" to true,
                "thinking" to mapOf("type" to "disabled"),
                "temperature" to 0.2,
                "max_tokens" to 65536,
                "messages" to listOf(
                    mapOf(
                        "role" to "user",
                        "content" to prompt,
                    ),
                ),
            ),
        ).toRequestBody("application/json; charset=utf-8".toMediaType())

        val request = Request.Builder()
            .url(DeepSeekModelCatalog.BASE_URL)
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "text/event-stream")
            .post(body)
            .build()

        streamingClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("DeepSeek API 请求失败：HTTP ${response.code}")
            }
            val source = response.body?.source() ?: throw IOException("DeepSeek API 没有返回内容")
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: continue
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data == "[DONE]") break
                val token = parseContentDelta(data)
                if (token.isNotEmpty()) emit(token)
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 修复单个本地解析失败的题目段落。
     *
     * - 仅发送 1 个 failedBlock 的纯文本，不发送整篇文档。
     * - Prompt 严格要求：不改写原题、不新增题目，无法识别返回 null。
     * - 期望返回单题 JSON 对象（或字面量 null）。
     * - 非流式，温度 0，max_tokens 受限，避免长文本截断 EOFException。
     */
    suspend fun repairBlock(apiKey: String, blockText: String): String =
        withContext(Dispatchers.IO) {
            val prompt = buildRepairPrompt(blockText)
            val body = gson.toJson(
                mapOf(
                    "model" to DEFAULT_MODEL,
                    "stream" to false,
                    "thinking" to mapOf("type" to "disabled"),
                    "temperature" to 0.0,
                    "max_tokens" to 2048,
                    "messages" to listOf(
                        mapOf(
                            "role" to "user",
                            "content" to prompt,
                        ),
                    ),
                ),
            ).toRequestBody("application/json; charset=utf-8".toMediaType())

            val request = Request.Builder()
                .url(DeepSeekModelCatalog.BASE_URL)
                .header("Authorization", "Bearer $apiKey")
                .post(body)
                .build()

            executeWithRetry(request).content
        }

    /**
     * Executes one logical model request batch, with at most three HTTP attempts.
     *
     * The import report's apiRequestCount is the number of logical model request batches;
     * transport retries performed here are intentionally not counted as additional batches.
     */
    private suspend fun executeWithRetry(request: Request): DeepSeekCompletion {
        var lastException: IOException? = null

        for (attempt in 0..MAX_REPAIR_RETRIES) {
            try {
                repairClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val raw = response.body?.string().orEmpty()
                        val completion = runCatching {
                            val root = JsonParser.parseString(raw).asJsonObject
                            val choice = root.getAsJsonArray("choices")
                                ?.firstOrNull()
                                ?.asJsonObject
                            val content = choice
                                ?.getAsJsonObject("message")
                                ?.get("content")
                                ?.asString
                                .orEmpty()
                            val usageObject = root.get("usage")
                                ?.takeIf { it.isJsonObject }
                                ?.asJsonObject
                            DeepSeekCompletion(
                                content = content,
                                finishReason = choice?.get("finish_reason")
                                    ?.takeUnless { it.isJsonNull }
                                    ?.asString,
                                usage = usageObject?.let { usage ->
                                    DeepSeekUsage(
                                        promptTokens = usage.longOrNull("prompt_tokens"),
                                        completionTokens = usage.longOrNull("completion_tokens"),
                                        totalTokens = usage.longOrNull("total_tokens"),
                                    )
                                },
                            )
                        }.getOrDefault(DeepSeekCompletion("", null, null))
                        return completion
                    }

                    throw HttpStatusException(response.code)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpStatusException) {
                lastException = e
                if (!isRetryable(e.code) || attempt >= MAX_REPAIR_RETRIES) {
                    throw e
                }
            } catch (e: IOException) {
                lastException = e
                if (attempt >= MAX_REPAIR_RETRIES) {
                    throw e
                }
            }

            retryDelay(RETRY_BASE_DELAY_MS * (1L shl attempt))
        }

        throw lastException ?: IOException("DeepSeek API 修复请求失败：未知错误")
    }

    private fun isRetryable(httpCode: Int): Boolean =
        httpCode == 429 || httpCode in 500..599

    private fun com.google.gson.JsonObject.longOrNull(name: String): Long? =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong

    private class HttpStatusException(
        val code: Int,
    ) : IOException("DeepSeek API 修复请求失败：HTTP $code")

    private fun buildRepairPrompt(blockText: String): String =
        """
        你是一个原题格式整理助手。下面是从 Word 文档中复制出来的一道原题的纯文本片段。

        任务：把它整理为严格的 JSON 单题对象。

        严格要求：
        1. 请把以下原题整理为 JSON
        2. 不要改写题干，照搬原文
        3. 不要改写选项，照搬原文
        4. 不要改写答案，照搬原文
        5. 不要新增题目，不要编造题目
        6. 不要润色、不要翻译、不要意译
        7. 如果原文不是一道完整可识别的题，请只输出字面量 null
        8. 只输出 JSON 对象或 null，不要使用 Markdown 代码块，不要输出 ```json 或 ```
        9. 输出必须是单题对象，不要数组
        10. 如果 A/B/C/D 选项在同一行，必须按选项标号拆成多条 options，禁止把 B/C/D 合并进 A 的文本
        11. 选择题少于 2 个可识别选项时，请输出 null

        输出 JSON 字段（单题对象）：
        {
          "type": "single" 或 "multiple" 或 "truefalse",
          "question": "题干",
          "options": [
            {"key": "A", "text": "选项A"},
            {"key": "B", "text": "选项B"},
            {"key": "C", "text": "选项C"},
            {"key": "D", "text": "选项D"}
          ],
          "answer": "A" 或 ["A","B"],
          "explanation": "解析（可为空字符串）"
        }

        判断题必须严格写成：
          "type": "truefalse"
          "options": [{"key": "A", "text": "对"}, {"key": "B", "text": "错"}]
          "answer": "A" 或 "B"

        原文：
        ---
        $blockText
        ---
        """.trimIndent()

    private fun parseContentDelta(data: String): String =
        runCatching {
            val root = JsonParser.parseString(data).asJsonObject
            root.getAsJsonArray("choices")
                ?.firstOrNull()
                ?.asJsonObject
                ?.getAsJsonObject("delta")
                ?.get("content")
                ?.asString
                .orEmpty()
        }.getOrDefault("")

    /**
     * AI Structure Label — 单 QuestionSegment 的语义标注。
     *
     * 与 repairBlock() 完全独立：
     * - 不返回完整题目 JSON
     * - 只返回 annotations（sourceId + label + offset）
     * - 禁止返回 question/options/answer/text/content
     */
    suspend fun labelStructure(apiKey: String, snapshotJson: String): String =
        withContext(Dispatchers.IO) {
            val prompt = buildLabelPrompt(snapshotJson)
            val body = gson.toJson(
                mapOf(
                    "model" to DEFAULT_MODEL,
                    "stream" to false,
                    "thinking" to mapOf("type" to "disabled"),
                    "response_format" to mapOf("type" to "json_object"),
                    "temperature" to 0.0,
                    "max_tokens" to 4096,
                    "messages" to listOf(
                        mapOf("role" to "user", "content" to prompt),
                    ),
                ),
            ).toRequestBody("application/json; charset=utf-8".toMediaType())

            val request = Request.Builder()
                .url(DeepSeekModelCatalog.BASE_URL)
                .header("Authorization", "Bearer $apiKey")
                .post(body)
                .build()

            executeWithRetry(request).content
        }

    /**
     * Document-level QuizForge import endpoint. The payload is structured text/metadata only;
     * image bytes and the DOCX binary are never attached.
     */
    suspend fun completeSmartImport(
        apiKey: String,
        stage: SmartRecognitionStage,
        requestJson: String,
        model: String = DEFAULT_MODEL,
    ): DeepSeekCompletion = withContext(Dispatchers.IO) {
        val prompt = if (stage == SmartRecognitionStage.BOUNDARY) {
            buildBoundaryPrompt(requestJson)
        } else {
            buildStructurePrompt(requestJson)
        }
        val body = gson.toJson(
            mapOf(
                "model" to model,
                "stream" to false,
                "thinking" to mapOf("type" to "disabled"),
                "response_format" to mapOf("type" to "json_object"),
                "temperature" to 0.0,
                "max_tokens" to 8192,
                "messages" to listOf(mapOf("role" to "user", "content" to prompt)),
            ),
        ).toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(DeepSeekModelCatalog.BASE_URL)
            .header("Authorization", "Bearer $apiKey")
            .post(body)
            .build()
        executeWithRetry(request)
    }

    /** Minimal connection test. It never includes quiz content. */
    suspend fun testConnection(apiKey: String, model: String = DEFAULT_MODEL): String = withContext(Dispatchers.IO) {
        val body = gson.toJson(
            mapOf(
                "model" to model,
                "stream" to false,
                "thinking" to mapOf("type" to "disabled"),
                "temperature" to 0.0,
                "max_tokens" to 2,
                "messages" to listOf(mapOf("role" to "user", "content" to "只回复 OK")),
            ),
        ).toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(DeepSeekModelCatalog.BASE_URL)
            .header("Authorization", "Bearer $apiKey")
            .post(body)
            .build()
        executeWithRetry(request).content
    }

    private fun buildBoundaryPrompt(requestJson: String): String = """
你是 QuizForge 的 Word 题库边界识别器。输入是按原文顺序排列的 SourceBlock JSON；它包含段落、表格单元格坐标、Word 自动编号、图片占位和字符范围。图片二进制没有发送，禁止猜图片内容。

任务：只识别本批次中所有题目边界、集中答案区、非题目内容和无法确认内容。一个 source block 可以包含多道题，questions 必须允许返回多个对象。不要改写、润色或补写任何原文。

只返回严格 JSON：
{
  "questions": [{
    "tempId": "q1",
    "sourceIds": ["p1", "p2"],
    "originalQuestionNumber": 1
  }],
  "answerSections": [{"sourceIds":["p100"]}],
  "nonQuestionSourceIds": ["p0"],
  "unsupportedSourceIds": [],
  "unresolvedSourceIds": []
}

第一阶段禁止返回 type、question、options、answer、explanation、knowledge 或字段来源；QuizForge 会在第二阶段逐题结构化。不得根据常识猜答案，不得生成原文没有的选项。

输入：
$requestJson
""".trimIndent()

    private fun buildStructurePrompt(requestJson: String): String = """
你是 QuizForge 的题目结构化器。输入包含已识别候选题的完整 SourceBlock，以及可能位于文档末尾的答案汇总区。图片仅为占位符，禁止猜图片内容。

从输入中返回零道、一 道或多道题。所有输出文字必须逐字来自所声明的 sourceIds；只允许去题号、统一标点/空白和答案标号。不得改写题干、补选项、凭知识纠正原题或猜答案。缺少明确答案时不要伪造成功，应将相关 sourceId 放进 unresolvedSourceIds。

只返回严格 JSON，questions 每项必须含：tempId、sourceIds、originalQuestionNumber、type、question、options、answer、explanation、knowledge、questionSource、optionSources、answerSource、explanationSource、knowledgeSource。还可返回 nonQuestionSourceIds、unsupportedSourceIds、unresolvedSourceIds；格式与第一阶段相同。

输入：
$requestJson
""".trimIndent()

    private fun buildLabelPrompt(snapshotJson: String): String = """
你是一个文档结构标注助手。下面是一个题目片段的结构化快照。

任务：为每个 source block 标注语义类型和精确字符范围。

严格要求：
1. 只返回 annotations JSON，不返回 question/options/answer/text/content 字段
2. 不要改写任何 source text
3. startOffset/endOffset 基于 snapshot 中提供的 text 字段
4. 每个 sourceId 可以有多个 annotation（如 A.xxx B.xxx 在同一段中）
5. OPTION 必须携带 optionKey（A-H 单字母）
6. STEM 的 range 从题号之后开始（不包含 "1. " 等题号前缀）

返回格式（严格 JSON，无 markdown 代码块）：
{
  "annotations": [
    {"sourceId": "p3", "label": "STEM", "startOffset": 3, "endOffset": 10},
    {"sourceId": "p4", "label": "OPTION", "startOffset": 3, "endOffset": 12, "optionKey": "A"},
    {"sourceId": "p5", "label": "ANSWER", "startOffset": 0, "endOffset": 1}
  ]
}

快照：
$snapshotJson
""".trimIndent()
}
