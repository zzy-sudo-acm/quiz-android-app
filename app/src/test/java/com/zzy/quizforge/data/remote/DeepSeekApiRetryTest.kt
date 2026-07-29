package com.zzy.quizforge.data.remote

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.zzy.quizforge.util.document.SmartRecognitionStage
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.test.assertFailsWith

class DeepSeekApiRetryTest {
    @Test
    fun `smart completion uses JSON non-thinking mode and preserves completion metadata`() = runTest {
        val attempts = AtomicInteger()
        val requestBodies = mutableListOf<JsonObject>()
        val client = scriptedClient(attempts) { _, request ->
            val buffer = Buffer()
            requireNotNull(request.body).writeTo(buffer)
            requestBodies += JsonParser.parseString(buffer.readUtf8()).asJsonObject
            response(
                request,
                200,
                """{
                  "choices":[{"finish_reason":"length","message":{"content":"{\"questions\":["}}],
                  "usage":{"prompt_tokens":11,"completion_tokens":22,"total_tokens":33}
                }""".trimIndent(),
            )
        }
        val api = DeepSeekApi(client, client)

        val completion = api.completeSmartImport("key", SmartRecognitionStage.BOUNDARY, "{}")
        api.testConnection("key")

        assertEquals("{\"questions\":[", completion.content)
        assertEquals("length", completion.finishReason)
        assertEquals(11L, completion.usage?.promptTokens)
        assertEquals(22L, completion.usage?.completionTokens)
        assertEquals(33L, completion.usage?.totalTokens)
        assertEquals("disabled", requestBodies[0].getAsJsonObject("thinking").get("type").asString)
        assertEquals("json_object", requestBodies[0].getAsJsonObject("response_format").get("type").asString)
        assertEquals(false, requestBodies[0].get("stream").asBoolean)
        assertEquals("disabled", requestBodies[1].getAsJsonObject("thinking").get("type").asString)
        assertEquals(false, requestBodies[1].has("response_format"))
    }

    @Test
    fun `authentication and payment failures are requested only once`() = runTest {
        listOf(401, 402, 403).forEach { code ->
            val attempts = AtomicInteger()
            val client = scriptedClient(attempts) { _, request ->
                response(request, code)
            }
            val api = DeepSeekApi(client, client) {
                error("HTTP $code must not enter retry backoff")
            }

            val error = assertFailsWith<IOException> {
                api.testConnection("invalid-key")
            }

            assertEquals("DeepSeek API 修复请求失败：HTTP $code", error.message)
            assertEquals("HTTP $code request count", 1, attempts.get())
        }
    }

    @Test
    fun `429 and server failures are retried with exponential backoff`() = runTest {
        val attempts = AtomicInteger()
        val delays = mutableListOf<Long>()
        val client = scriptedClient(attempts) { attempt, request ->
            when (attempt) {
                1 -> response(request, 429)
                2 -> response(request, 503)
                else -> response(request, 200, successPayload("OK"))
            }
        }
        val api = DeepSeekApi(client, client) { delays += it }

        assertEquals("OK", api.testConnection("key"))
        assertEquals(3, attempts.get())
        assertEquals(listOf(1_000L, 2_000L), delays)
    }

    @Test
    fun `persistent server failure stops after three attempts`() = runTest {
        val attempts = AtomicInteger()
        val delays = mutableListOf<Long>()
        val client = scriptedClient(attempts) { _, request -> response(request, 500) }
        val api = DeepSeekApi(client, client) { delays += it }

        val error = assertFailsWith<IOException> {
            api.testConnection("key")
        }

        assertEquals("DeepSeek API 修复请求失败：HTTP 500", error.message)
        assertEquals(3, attempts.get())
        assertEquals(listOf(1_000L, 2_000L), delays)
    }

    @Test
    fun `network IO failures are retried up to three attempts`() = runTest {
        val attempts = AtomicInteger()
        val delays = mutableListOf<Long>()
        val client = scriptedClient(attempts) { attempt, request ->
            if (attempt < 3) throw SocketTimeoutException("test timeout")
            response(request, 200, successPayload("RECOVERED"))
        }
        val api = DeepSeekApi(client, client) { delays += it }

        assertEquals("RECOVERED", api.testConnection("key"))
        assertEquals(3, attempts.get())
        assertEquals(listOf(1_000L, 2_000L), delays)
    }

    @Test
    fun `cancellation during retry backoff propagates without another request`() = runTest {
        val attempts = AtomicInteger()
        val delayStarted = CompletableDeferred<Long>()
        val neverCompletes = CompletableDeferred<Unit>()
        val client = scriptedClient(attempts) { _, request -> response(request, 503) }
        val api = DeepSeekApi(client, client) { millis ->
            delayStarted.complete(millis)
            neverCompletes.await()
        }

        val result = async { api.testConnection("key") }
        assertEquals(1_000L, delayStarted.await())
        result.cancel(CancellationException("test cancellation"))

        assertFailsWith<CancellationException> { result.await() }
        assertEquals(1, attempts.get())
    }

    private fun scriptedClient(
        attempts: AtomicInteger,
        responseForAttempt: (attempt: Int, request: Request) -> Response,
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            responseForAttempt(attempts.incrementAndGet(), chain.request())
        }
        .build()

    private fun response(
        request: Request,
        code: Int,
        body: String = "{}",
    ): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("test response")
        .body(body.toResponseBody(JSON_MEDIA_TYPE))
        .build()

    private fun successPayload(content: String): String =
        """{"choices":[{"message":{"content":"$content"}}]}"""

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
