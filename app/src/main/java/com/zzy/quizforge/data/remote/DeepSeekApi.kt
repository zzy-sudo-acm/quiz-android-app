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

class DeepSeekApi(private val client: OkHttpClient) {
    private val gson = Gson()

    fun streamQuestions(apiKey: String, prompt: String): Flow<String> = flow {
        val body = gson.toJson(
            mapOf(
                "model" to "deepseek-chat",
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

        client.newCall(request).execute().use { response ->
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
                    "model" to "deepseek-chat",
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

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("DeepSeek API 修复请求失败：HTTP ${response.code}")
                }
                val raw = response.body?.string().orEmpty()
                runCatching {
                    val root = JsonParser.parseString(raw).asJsonObject
                    root.getAsJsonArray("choices")
                        ?.firstOrNull()
                        ?.asJsonObject
                        ?.getAsJsonObject("message")
                        ?.get("content")
                        ?.asString
                        .orEmpty()
                }.getOrDefault("")
            }
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
}
