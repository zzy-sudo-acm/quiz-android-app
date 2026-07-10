package com.zzy.quizforge.data.remote

import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class DeepSeekApi(
    private val streamingClient: OkHttpClient,
    private val repairClient: OkHttpClient,
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
        const val DEFAULT_MODEL = "deepseek-v4-flash"

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
            .url("https://api.deepseek.com/chat/completions")
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
                .url("https://api.deepseek.com/chat/completions")
                .header("Authorization", "Bearer $apiKey")
                .post(body)
                .build()

            executeWithRetry(request)
        }

    private fun executeWithRetry(request: Request): String {
        var lastException: Exception? = null

        for (attempt in 0..MAX_REPAIR_RETRIES) {
            try {
                repairClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val raw = response.body?.string().orEmpty()
                        val content = runCatching {
                            val root = JsonParser.parseString(raw).asJsonObject
                            root.getAsJsonArray("choices")
                                ?.firstOrNull()
                                ?.asJsonObject
                                ?.getAsJsonObject("message")
                                ?.get("content")
                                ?.asString
                                .orEmpty()
                        }.getOrDefault("")
                        return content
                    }

                    val code = response.code
                    val message = "DeepSeek API 修复请求失败：HTTP $code"
                    response.close()

                    if (attempt < MAX_REPAIR_RETRIES && isRetryable(code)) {
                        val delay = RETRY_BASE_DELAY_MS * (1L shl attempt)
                        Thread.sleep(delay)
                        lastException = IOException(message)
                        continue
                    }
                    throw IOException(message)
                }
            } catch (e: IOException) {
                lastException = e
                if (attempt < MAX_REPAIR_RETRIES && isRetryableException(e)) {
                    val delay = RETRY_BASE_DELAY_MS * (1L shl attempt)
                    Thread.sleep(delay)
                    continue
                }
                // 不可重试的异常直接抛出，由调用方 catch 处理
                if (attempt >= MAX_REPAIR_RETRIES || !isRetryableException(e)) {
                    throw e
                }
            }
        }

        throw lastException ?: IOException("DeepSeek API 修复请求失败：未知错误")
    }

    private fun isRetryable(httpCode: Int): Boolean =
        httpCode == 429 || httpCode in 500..599

    private fun isRetryableException(e: IOException): Boolean {
        // 网络层 I/O 异常（连接超时、连接重置等）适合重试
        // 4xx 参数/认证错误不在这个方法处理（由 HTTP code 分支处理）
        return true
    }

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
                    "temperature" to 0.0,
                    "max_tokens" to 4096,
                    "messages" to listOf(
                        mapOf("role" to "user", "content" to prompt),
                    ),
                ),
            ).toRequestBody("application/json; charset=utf-8".toMediaType())

            val request = Request.Builder()
                .url("https://api.deepseek.com/chat/completions")
                .header("Authorization", "Bearer $apiKey")
                .post(body)
                .build()

            executeWithRetry(request)
        }

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
