package com.zzy.quizforge.util.document

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartImportPipelineTest {

    @Test
    fun `boundary request carries the complete source block projection`() = runTest {
        val source = ImportSourceBlock(
            sourceId = "table-2-r3-c4",
            sourceOrder = 17,
            sourceType = SourceBlockType.TABLE_CELL,
            rawText = "第7题 根据图片选择 A. 甲 B. 乙 答案：A",
            numbering = SourceNumbering("num-questions", 2, "7."),
            table = SourceTablePosition("table-2", 3, 4),
            images = listOf(
                SourceImageRef(
                    mediaId = "media-7",
                    relationshipId = "rId21",
                    localPath = "/tmp/media-7.png",
                    contentType = "image/png",
                    supportedForDisplay = true,
                ),
            ),
            sourceOrderStart = 15,
            sourceOrderEnd = 19,
        )
        val client = RecordingSmartClient { request ->
            assertEquals(SmartRecognitionStage.BOUNDARY, request.stage)
            """{"questions":[],"nonQuestionSourceIds":["${request.sourceBlocks.single().sourceId}"]}"""
        }

        val result = pipeline(client).recognize("rich.docx", listOf(source), "secret-key")

        assertEquals(listOf("secret-key"), client.apiKeys)
        val request = client.requests.single()
        assertEquals("chunk-0", request.chunkId)
        assertTrue(request.candidateSourceIds.isEmpty())
        assertTrue(request.answerSectionSourceIds.isEmpty())
        val sent = request.sourceBlocks.single()
        assertEquals(source.sourceId, sent.sourceId)
        assertEquals(source.sourceOrder, sent.sourceOrder)
        assertEquals(source.sourceType.wireValue, sent.sourceType)
        assertEquals(source.rawText, sent.rawText)
        assertEquals(0, sent.charStart)
        assertEquals(source.rawText.length, sent.charEnd)
        assertEquals(source.numbering, sent.numbering)
        assertEquals(source.table, sent.table)
        assertEquals(listOf("media-7"), sent.images)
        assertEquals(1, result.report.nonQuestionCount)
        assertTrue(result.report.ledgerComplete)
    }

    @Test
    fun `boundary response cannot guess a source id from another chunk`() = runTest {
        val first = source("chunk-one", 0, "第一块" + "甲".repeat(300))
        val second = source("chunk-two", 1, "第二块" + "乙".repeat(300))
        val client = RecordingSmartClient { request ->
            val sentIds = request.sourceBlocks.map { it.sourceId }.toSet()
            if ("chunk-one" in sentIds) {
                // chunk-two exists in the document, but was not serialized into this request.
                """{"questions":[{"tempId":"guessed","sourceIds":["chunk-two"]}]}"""
            } else {
                """{"questions":[],"nonQuestionSourceIds":["chunk-two"]}"""
            }
        }

        val result = pipeline(client, maxEstimatedTokens = 250, overlapBlocks = 0)
            .recognize("cross-chunk.docx", listOf(first, second), "key")

        assertEquals(2, client.calls)
        assertEquals(listOf("chunk-one"), client.requests.first().sourceBlocks.map { it.sourceId }.distinct())
        assertTrue(result.questions.isEmpty())
        val rejected = result.report.records.single { it.status == SourceLedgerStatus.REJECTED_QUESTION }
        assertEquals(listOf("chunk-one"), rejected.sourceIds)
        assertEquals(ImportFailureReason.SOURCE_NOT_COVERED, rejected.reasonCode)
        assertTrue(rejected.reasonMessage.orEmpty().contains("本次请求之外"))
        assertEquals(1, result.report.nonQuestionCount)
        assertTrue(result.report.ledgerComplete)
    }

    @Test
    fun `structure provenance cannot borrow a document source omitted from its request`() = runTest {
        val question = source("q1", 0, "1. JVM 运行哪种字节码？ A. Java bytecode B. Native code")
        val donor = source("donor", 1, "答案：A")
        val client = RecordingSmartClient { request ->
            when (request.stage) {
                SmartRecognitionStage.BOUNDARY -> """{
                  "questions":[{"tempId":"one","sourceIds":["q1"],"originalQuestionNumber":1}],
                  "nonQuestionSourceIds":["donor"]
                }"""
                SmartRecognitionStage.STRUCTURE -> """{"questions":[{
                  "tempId":"one","sourceIds":["q1","donor"],"originalQuestionNumber":1,
                  "type":"single","question":"JVM 运行哪种字节码？",
                  "options":[{"key":"A","text":"Java bytecode"},{"key":"B","text":"Native code"}],
                  "answer":["A"],"questionSource":["q1"],
                  "optionSources":{"A":["q1"],"B":["q1"]},"answerSource":["donor"]
                }]}"""
            }
        }

        val result = pipeline(client).recognize("borrow.docx", listOf(question, donor), "key")

        assertEquals(2, client.calls)
        assertEquals(listOf("q1"), client.requests.last().sourceBlocks.map { it.sourceId }.distinct())
        assertTrue(result.questions.isEmpty())
        val rejected = result.report.records.single { it.status == SourceLedgerStatus.REJECTED_QUESTION }
        assertEquals(listOf("q1"), rejected.sourceIds)
        assertEquals(ImportFailureReason.SOURCE_NOT_COVERED, rejected.reasonCode)
        assertTrue(rejected.reasonMessage.orEmpty().contains("donor"))
        assertTrue(result.report.ledgerComplete)
    }

    @Test
    fun `field provenance must belong to declared provenance even when source is request context`() = runTest {
        val question = source("q1", 0, "1. JVM 运行哪种字节码？ A. Java bytecode B. Native code")
        val answers = source("answers", 1, "答案汇总：1.A")
        val client = RecordingSmartClient { request ->
            when (request.stage) {
                SmartRecognitionStage.BOUNDARY -> """{
                  "questions":[{"tempId":"one","sourceIds":["q1"],"originalQuestionNumber":1}],
                  "answerSections":[{"sourceIds":["answers"]}]
                }"""
                SmartRecognitionStage.STRUCTURE -> """{"questions":[{
                  "tempId":"one","sourceIds":["q1"],"originalQuestionNumber":1,
                  "type":"single","question":"JVM 运行哪种字节码？",
                  "options":[{"key":"A","text":"Java bytecode"},{"key":"B","text":"Native code"}],
                  "answer":["A"],"questionSource":["q1"],
                  "optionSources":{"A":["q1"],"B":["q1"]},"answerSource":["answers"]
                }]}"""
            }
        }

        val result = pipeline(client).recognize("field-scope.docx", listOf(question, answers), "key")

        assertEquals(setOf("q1", "answers"), client.requests.last().sourceBlocks.map { it.sourceId }.toSet())
        assertTrue(result.questions.isEmpty())
        val rejected = result.report.records.single { it.status == SourceLedgerStatus.REJECTED_QUESTION }
        assertEquals(ImportFailureReason.SOURCE_NOT_COVERED, rejected.reasonCode)
        assertTrue(rejected.reasonMessage.orEmpty().contains("不属于 provenance.sourceIds"))
        assertTrue(result.report.ledgerComplete)
    }

    @Test
    fun `multiple questions in one JSON questions array are all imported`() = runTest {
        val sources = listOf(
            source(
                "q1",
                0,
                "1. JVM 运行哪种字节码？\nA. Java bytecode\nB. Native code\n答案：A",
            ),
            source(
                "q2",
                1,
                "2. Kotlin 默认运行在哪个平台？\nA. JVM\nB. BIOS\n答案：A",
            ),
        )
        val response = """{"questions":[
            ${structuredQuestion("one", "q1", 1, "JVM 运行哪种字节码？", "Java bytecode", "Native code")},
            ${structuredQuestion("two", "q2", 2, "Kotlin 默认运行在哪个平台？", "JVM", "BIOS")}
        ]}""".trimIndent()
        val client = RecordingSmartClient { response }

        val result = pipeline(client).recognize("two.docx", sources, "key")

        assertEquals(1, client.calls)
        assertEquals(2, result.questions.size)
        assertEquals(
            listOf("JVM 运行哪种字节码？", "Kotlin 默认运行在哪个平台？"),
            result.questions.map { it.question.question },
        )
        assertEquals(listOf(1, 2), result.questions.map { it.originalQuestionNumber })
        assertEquals(2, result.report.acceptedQuestionCount)
        assertEquals(0, result.report.rejectedQuestionCount)
        assertTrue(result.report.ledgerComplete)
    }

    @Test
    fun `multiple questions sharing one source block remain distinct`() = runTest {
        val combined = source(
            "combined",
            0,
            "1. 第一题？ A. 甲 B. 乙 答案：A 2. 第二题？ A. 丙 B. 丁 答案：A",
        )
        val response = """{"questions":[
            ${structuredQuestion("one", "combined", 1, "第一题？", "甲", "乙")},
            ${structuredQuestion("two", "combined", 2, "第二题？", "丙", "丁")}
        ]}""".trimIndent()

        val result = pipeline(RecordingSmartClient { response }).recognize("combined.docx", listOf(combined), "key")

        assertEquals(2, result.questions.size)
        assertEquals(listOf(1, 2), result.questions.map { it.originalQuestionNumber })
        assertEquals(0, result.report.rejectedQuestionCount)
        assertTrue(result.report.ledgerComplete)
    }

    @Test
    fun `concentrated answer section is traced by original question number`() = runTest {
        val question = source("q4", 0, "4. 集中答案题？ A. 甲 B. 乙")
        val answers = source("answers", 1, "答案汇总：4.A 5.B")
        val response = """{"questions":[{
          "tempId":"four","sourceIds":["q4","answers"],"originalQuestionNumber":4,
          "type":"single","question":"集中答案题？",
          "options":[{"key":"A","text":"甲"},{"key":"B","text":"乙"}],"answer":["A"],
          "questionSource":["q4"],"optionSources":{"A":["q4"],"B":["q4"]},
          "answerSource":["answers"]
        }],"answerSections":[{"sourceIds":["answers"]}]}"""

        val result = pipeline(RecordingSmartClient { response })
            .recognize("answers.docx", listOf(question, answers), "key")

        assertEquals(1, result.questions.size)
        assertEquals(listOf("A"), result.questions.single().question.answer)
        assertEquals(0, result.report.rejectedQuestionCount)
    }

    @Test
    fun `partially recognized multi question source is visible in report`() = runTest {
        val combined = source(
            "partial",
            0,
            "1. 第一题？ A. 甲 B. 乙 答案：A 2. 第二题？ A. 丙 B. 丁 答案：A",
        )
        val response = """{"questions":[
            ${structuredQuestion("one", "partial", 1, "第一题？", "甲", "乙")}
        ]}""".trimIndent()

        val result = pipeline(RecordingSmartClient { response }).recognize("partial.docx", listOf(combined), "key")

        assertEquals(1, result.questions.size)
        assertTrue(result.report.records.any { it.reasonCode == ImportFailureReason.MULTIPLE_QUESTIONS_MERGED })
        assertTrue(result.report.hasUncertainContent)
    }

    @Test
    fun `single option and omitted visible option are rejected`() = runTest {
        val oneOption = source("one-option", 0, "1. 不完整选择题？ A. 唯一选项 答案：A")
        val oneOptionResponse = """{"questions":[{
          "tempId":"one","sourceIds":["one-option"],"originalQuestionNumber":1,
          "type":"single","question":"不完整选择题？",
          "options":[{"key":"A","text":"唯一选项"}],"answer":["A"],
          "questionSource":["one-option"],"optionSources":{"A":["one-option"]},"answerSource":["one-option"]
        }]}"""
        val oneResult = pipeline(RecordingSmartClient { oneOptionResponse })
            .recognize("one-option.docx", listOf(oneOption), "key")
        assertSingleFailure(oneResult, ImportFailureReason.MISSING_OPTIONS)

        val omitted = source("omitted", 0, "2. 三选一？ A. 甲 B. 乙 C. 丙 答案：A")
        val omittedResponse = structuredQuestion("omitted", "omitted", 2, "三选一？", "甲", "乙")
        val omittedResult = pipeline(RecordingSmartClient { """{"questions":[$omittedResponse]}""" })
            .recognize("omitted.docx", listOf(omitted), "key")
        assertSingleFailure(omittedResult, ImportFailureReason.SOURCE_NOT_COVERED)
    }

    @Test
    fun `inline image offsets keep stem and option ownership separate`() = runTest {
        val text = "1. 看题干图选择？ A. 甲 B. 乙 答案：A"
        val richSource = ImportSourceBlock(
            sourceId = "images",
            sourceOrder = 0,
            sourceType = SourceBlockType.PARAGRAPH,
            rawText = text,
            images = listOf(
                image("stem.png", text.indexOf(" A.")),
                image("a.png", text.indexOf(" B.")),
                image("b.png", text.indexOf(" 答案")),
            ),
        )
        val response = structuredQuestion("images", "images", 1, "看题干图选择？", "甲", "乙")

        val result = pipeline(RecordingSmartClient { """{"questions":[$response]}""" })
            .recognize("images.docx", listOf(richSource), "key")

        val question = result.questions.single().question
        assertEquals(listOf("stem.png"), question.imageUris)
        assertEquals(listOf("a.png"), question.options.first { it.key == "A" }.imageUris)
        assertEquals(listOf("b.png"), question.options.first { it.key == "B" }.imageUris)
    }

    @Test
    fun `invalid JSON is reported for every covered boundary source`() = runTest {
        val client = RecordingSmartClient { "this is not JSON" }

        val result = pipeline(client).recognize("invalid.docx", listOf(source("invalid", 0, "原文")), "key")

        assertSingleFailure(result, ImportFailureReason.API_INVALID_JSON)
        assertEquals(1, result.report.apiRequestCount)
    }

    @Test
    fun `truncated boundary JSON is bisected and successful leaves are cached`() = runTest {
        val requests = mutableListOf<SmartModelRequest>()
        val client = SmartImportModelClient { _, request ->
            requests += request
            if (request.chunkId == "chunk-0") {
                SmartModelCompletion("{\"questions\":[", finishReason = "length")
            } else {
                val ids = request.sourceBlocks.map { it.sourceId }.distinct()
                    .joinToString(prefix = "[\"", postfix = "\"]", separator = "\",\"")
                SmartModelCompletion(
                    """{"questions":[],"nonQuestionSourceIds":$ids}""",
                    finishReason = "stop",
                )
            }
        }
        val cache = SmartRequestCache()
        val smart = pipeline(client, cache, overlapBlocks = 0)
        val sources = listOf(source("left", 0, "1. 左侧内容"), source("right", 1, "2. 右侧内容"))

        val first = smart.recognize("truncated.docx", sources, "key")
        val second = smart.recognize("truncated.docx", sources, "key")

        assertEquals(4, requests.size)
        assertEquals(listOf("chunk-0", "chunk-0-a", "chunk-0-b", "chunk-0"), requests.map { it.chunkId })
        assertEquals(3, first.report.apiRequestCount)
        assertEquals(1, second.report.apiRequestCount)
        assertEquals(2, first.report.nonQuestionCount)
        assertEquals(0, first.report.rejectedQuestionCount)
        assertTrue(first.report.ledgerComplete)
        assertTrue(second.report.ledgerComplete)
    }

    @Test
    fun `unclosed JSON without finish reason also triggers bounded split`() = runTest {
        var calls = 0
        val client = SmartImportModelClient { _, request ->
            calls++
            if (request.chunkId == "chunk-0") {
                SmartModelCompletion("{\"questions\":[{\"tempId\":\"cut")
            } else {
                val id = request.sourceBlocks.single().sourceId
                SmartModelCompletion("""{"questions":[],"nonQuestionSourceIds":["$id"]}""")
            }
        }

        val result = pipeline(client, overlapBlocks = 0).recognize(
            "eof.docx",
            listOf(source("one", 0, "1. 第一段"), source("two", 1, "2. 第二段")),
            "key",
        )

        assertEquals(3, calls)
        assertEquals(2, result.report.nonQuestionCount)
        assertEquals(0, result.report.rejectedQuestionCount)
        assertTrue(result.report.ledgerComplete)
    }

    @Test
    fun `incomplete coverage response is not cached`() = runTest {
        var calls = 0
        val client = RecordingSmartClient {
            calls++
            if (calls == 1) {
                """{"questions":[]}"""
            } else {
                """{"questions":[],"nonQuestionSourceIds":["recoverable"]}"""
            }
        }
        val smart = pipeline(client, SmartRequestCache())
        val sources = listOf(source("recoverable", 0, "章节说明"))

        val first = smart.recognize("coverage-cache.docx", sources, "key")
        val second = smart.recognize("coverage-cache.docx", sources, "key")

        assertSingleFailure(first, ImportFailureReason.SOURCE_NOT_COVERED)
        assertEquals(2, calls)
        assertEquals(1, second.report.nonQuestionCount)
        assertEquals(0, second.report.rejectedQuestionCount)
    }

    @Test
    fun `successful overlap classification clears an earlier transport failure`() = runTest {
        val sources = listOf(
            source("first", 0, "甲".repeat(60)),
            source("overlap", 1, "乙".repeat(60)),
            source("last", 2, "丙".repeat(60)),
        )
        var calls = 0
        val client = RecordingSmartClient { request ->
            calls++
            if (calls == 1) {
                throw IllegalStateException("temporary failure")
            }
            val ids = request.sourceBlocks.map { it.sourceId }.distinct()
                .joinToString(prefix = "[\"", postfix = "\"]", separator = "\",\"")
            """{"questions":[],"nonQuestionSourceIds":$ids}"""
        }

        val result = pipeline(client, maxEstimatedTokens = 250, overlapBlocks = 1)
            .recognize("overlap-recovery.docx", sources, "key")

        assertEquals(2, calls)
        assertEquals(listOf("first"), result.report.records.filter {
            it.status == SourceLedgerStatus.REJECTED_QUESTION
        }.flatMap { it.sourceIds })
        assertEquals(2, result.report.nonQuestionCount)
        assertTrue(result.report.ledgerComplete)
    }

    @Test
    fun `invalid JSON never enters the shared request cache`() = runTest {
        var responseIndex = 0
        val original = source(
            "invalid-cache",
            0,
            "1. JVM 运行哪种字节码？\nA. Java bytecode\nB. Native code\n答案：A",
        )
        val client = RecordingSmartClient {
            responseIndex++
            if (responseIndex == 1) "not-json" else {
                """{"questions":[${structuredQuestion("valid", "invalid-cache", 1, "JVM 运行哪种字节码？", "Java bytecode", "Native code")}] }"""
            }
        }
        val pipeline = pipeline(client, SmartRequestCache())

        val first = pipeline.recognize("invalid-cache.docx", listOf(original), "key")
        val second = pipeline.recognize("invalid-cache.docx", listOf(original), "key")

        assertSingleFailure(first, ImportFailureReason.API_INVALID_JSON)
        assertEquals(2, client.calls)
        assertEquals(1, second.questions.size)
        assertEquals(1, second.report.apiRequestCount)
    }

    @Test
    fun `literal null model response is reported and is not cached`() = runTest {
        val cache = SmartRequestCache()
        val client = RecordingSmartClient { "null" }
        val pipeline = pipeline(client, cache)
        val sources = listOf(source("null-source", 0, "原文"))

        val first = pipeline.recognize("null.docx", sources, "key")
        val second = pipeline.recognize("null.docx", sources, "key")

        assertSingleFailure(first, ImportFailureReason.API_RETURNED_NULL)
        assertSingleFailure(second, ImportFailureReason.API_RETURNED_NULL)
        assertEquals(2, client.calls)
    }

    @Test
    fun `model exception becomes an API request failure`() = runTest {
        val client = RecordingSmartClient { throw IllegalStateException("provider unavailable") }

        val result = pipeline(client).recognize("exception.docx", listOf(source("failed", 0, "原文")), "key")

        assertSingleFailure(result, ImportFailureReason.API_REQUEST_FAILED)
        assertTrue(result.report.records.single().reasonMessage.orEmpty().contains("provider unavailable"))
    }

    @Test
    fun `model cancellation propagates instead of becoming an API failure`() = runTest {
        val client = RecordingSmartClient { throw CancellationException("cancel import") }
        var propagated: CancellationException? = null

        try {
            pipeline(client).recognize("cancel.docx", listOf(source("cancelled", 0, "原文")), "key")
        } catch (cancelled: CancellationException) {
            propagated = cancelled
        }

        assertEquals("cancel import", propagated?.message)
        assertEquals(1, client.calls)
    }

    @Test
    fun `hallucinated structured content is rejected even when source ids exist`() = runTest {
        val client = RecordingSmartClient { request ->
            when (request.stage) {
                SmartRecognitionStage.BOUNDARY ->
                    """{"questions":[{"tempId":"candidate","sourceIds":["q1"],"originalQuestionNumber":1}]}"""
                SmartRecognitionStage.STRUCTURE ->
                    """{"questions":[${structuredQuestion("candidate", "q1", 1, "火星上有液态海洋吗？", "Java bytecode", "Native code")}]}"""
            }
        }
        val original = source(
            "q1",
            0,
            "1. JVM 运行哪种字节码？\nA. Java bytecode\nB. Native code\n答案：A",
        )

        val result = pipeline(client).recognize("hallucination.docx", listOf(original), "key")

        assertEquals(2, client.calls)
        assertTrue(result.questions.isEmpty())
        assertSingleFailure(result, ImportFailureReason.API_HALLUCINATED_CONTENT)
        assertTrue(result.report.ledgerComplete)
    }

    @Test
    fun `oversized single block slices are contiguous and lossless`() {
        val original = buildString {
            repeat(1_307) { index -> append(('a'.code + index % 26).toChar()) }
        }
        val chunks = SourceBlockChunker(maxEstimatedTokens = 250, overlapBlocks = 0)
            .chunk(listOf(source("long", 0, original)))
        val slices = chunks.flatMap { it.slices }

        assertTrue(slices.size > 1)
        assertEquals(0, slices.first().charStart)
        assertEquals(original.length, slices.last().charEnd)
        slices.zipWithNext().forEach { (left, right) ->
            assertEquals(left.charEnd, right.charStart)
        }
        slices.forEach { slice ->
            assertEquals("long", slice.sourceId)
            assertEquals(original.substring(slice.charStart, slice.charEnd), slice.rawText)
        }
        assertEquals(original, slices.joinToString(separator = "") { it.rawText })
    }

    @Test
    fun `unclassified source is retained as source not covered report row`() = runTest {
        val client = RecordingSmartClient { """{"questions":[]}""" }

        val result = pipeline(client).recognize(
            "uncovered.docx",
            listOf(source("uncovered", 0, "不能悄悄消失的原文")),
            "key",
        )

        assertTrue(result.questions.isEmpty())
        assertSingleFailure(result, ImportFailureReason.SOURCE_NOT_COVERED)
        assertEquals(listOf("uncovered"), result.report.records.single().sourceIds)
        assertEquals("不能悄悄消失的原文", result.report.records.single().rawText)
        assertTrue(result.report.ledgerComplete)
    }

    @Test
    fun `successful response is reused by the same request cache`() = runTest {
        val cache = SmartRequestCache()
        val client = RecordingSmartClient {
            """{"questions":[],"nonQuestionSourceIds":["cache-source"]}"""
        }
        val pipeline = pipeline(client, cache)
        val sources = listOf(source("cache-source", 0, "章节标题"))

        val first = pipeline.recognize("cached.docx", sources, "key")
        val second = pipeline.recognize("cached.docx", sources, "key")

        assertEquals(1, client.calls)
        assertEquals(1, first.report.apiRequestCount)
        assertEquals(0, second.report.apiRequestCount)
        assertTrue(first.report.usedApi)
        assertFalse(second.report.usedApi)
        assertEquals(1, second.report.nonQuestionCount)
        assertTrue(second.report.ledgerComplete)
    }

    @Test
    fun `retry of a partial multi question source keeps old question and accepts the new one`() = runTest {
        val combined = source(
            "combined-retry",
            0,
            "1. 第一题？ A. 甲 B. 乙 答案：A 2. 第二题？ A. 丙 B. 丁 答案：A",
        )
        var call = 0
        val client = RecordingSmartClient {
            call++
            if (call == 1) {
                """{"questions":[${structuredQuestion("one", "combined-retry", 1, "第一题？", "甲", "乙")}] }"""
            } else {
                """{"questions":[
                  ${structuredQuestion("one-again", "combined-retry", 1, "第一题？", "甲", "乙")},
                  ${structuredQuestion("two", "combined-retry", 2, "第二题？", "丙", "丁")}
                ]}"""
            }
        }
        val smart = pipeline(client)
        val first = smart.recognize("partial-retry.docx", listOf(combined), "key")
        val failed = first.report.records.single { it.reasonCode == ImportFailureReason.MULTIPLE_QUESTIONS_MERGED }

        val retried = smart.retryFailedRecord("partial-retry.docx", listOf(combined), first, failed, "key")

        assertEquals(2, client.calls)
        assertEquals(listOf("combined-retry"), client.requests.last().sourceBlocks.map { it.sourceId }.distinct())
        assertEquals(listOf(1, 2), retried.questions.map { it.originalQuestionNumber })
        assertEquals(2, retried.report.acceptedQuestionCount)
        assertEquals(0, retried.report.rejectedQuestionCount)
        assertFalse(retried.report.records.any { it.reasonCode == ImportFailureReason.DUPLICATE_QUESTION })
        assertTrue(retried.report.ledgerComplete)
    }

    @Test
    fun `retry returning only the old question keeps a visible shared source failure`() = runTest {
        val combined = source(
            "combined-old-only",
            0,
            "1. 第一题？ A. 甲 B. 乙 答案：A 2. 第二题？ A. 丙 B. 丁 答案：A",
        )
        val client = RecordingSmartClient {
            """{"questions":[${structuredQuestion("old", "combined-old-only", 1, "第一题？", "甲", "乙")}] }"""
        }
        val smart = pipeline(client)
        val first = smart.recognize("old-only.docx", listOf(combined), "key")
        val failed = first.report.records.single { it.reasonCode == ImportFailureReason.MULTIPLE_QUESTIONS_MERGED }

        val retried = smart.retryFailedRecord("old-only.docx", listOf(combined), first, failed, "key")

        assertEquals(1, retried.questions.size)
        assertTrue(retried.report.rejectedQuestionCount > 0)
        assertTrue(retried.report.records.any {
            it.status == SourceLedgerStatus.REJECTED_QUESTION && "combined-old-only" in it.sourceIds
        })
        assertTrue(retried.report.ledgerComplete)
    }

    @Test
    fun `retry sends only failed source and preserves successful question without another charge`() = runTest {
        val sources = listOf(
            source(
                "q1",
                0,
                "1. JVM 运行哪种字节码？\nA. Java bytecode\nB. Native code\n答案：A",
            ),
            source(
                "q2",
                1,
                "2. Kotlin 默认运行在哪个平台？\nA. JVM\nB. BIOS\n答案：A",
            ),
        )
        val client = RecordingSmartClient { request ->
            if (request.sourceBlocks.any { it.sourceId == "q1" }) {
                """{"questions":[${structuredQuestion("one", "q1", 1, "JVM 运行哪种字节码？", "Java bytecode", "Native code")}],"unresolvedSourceIds":["q2"]}"""
            } else {
                """{"questions":[${structuredQuestion("two", "q2", 2, "Kotlin 默认运行在哪个平台？", "JVM", "BIOS")}] }"""
            }
        }
        val pipeline = pipeline(client)

        val first = pipeline.recognize("retry.docx", sources, "key")
        val failedRecord = first.report.records.single { it.status == SourceLedgerStatus.REJECTED_QUESTION }
        val retried = pipeline.retryFailedRecord("retry.docx", sources, first, failedRecord, "key")

        assertEquals(2, client.calls)
        assertEquals(listOf("q2"), client.requests.last().sourceBlocks.map { it.sourceId }.distinct())
        assertEquals(2, retried.questions.size)
        assertEquals(2, retried.report.acceptedQuestionCount)
        assertEquals(0, retried.report.rejectedQuestionCount)
        assertEquals(2, retried.report.apiRequestCount)
        assertEquals(2, retried.report.totalSourceBlocks)
        assertEquals(first.report.reportId, retried.report.reportId)
        assertEquals(
            listOf(listOf(0), listOf(1)),
            retried.report.records
                .filter { it.status == SourceLedgerStatus.ACCEPTED_QUESTION }
                .map { it.createdQuestionIndexes },
        )
        assertTrue(retried.report.ledgerComplete)
    }

    @Test
    fun `retry bypasses a cached invalid response`() = runTest {
        var responseIndex = 0
        val original = source(
            "retry-source",
            0,
            "1. JVM 运行哪种字节码？\nA. Java bytecode\nB. Native code\n答案：A",
        )
        val client = RecordingSmartClient {
            responseIndex++
            if (responseIndex == 1) "not-json" else {
                """{"questions":[${structuredQuestion("retry", "retry-source", 1, "JVM 运行哪种字节码？", "Java bytecode", "Native code")}] }"""
            }
        }
        val pipeline = pipeline(client, SmartRequestCache())

        val first = pipeline.recognize("retry-cache.docx", listOf(original), "key")
        val failedRecord = first.report.records.single { it.status == SourceLedgerStatus.REJECTED_QUESTION }
        val retried = pipeline.retryFailedRecord(
            "retry-cache.docx",
            listOf(original),
            first,
            failedRecord,
            "key",
        )

        assertEquals(2, client.calls)
        assertEquals(1, retried.questions.size)
        assertEquals(0, retried.report.rejectedQuestionCount)
        assertEquals(2, retried.report.apiRequestCount)
        assertTrue(retried.report.ledgerComplete)
    }

    @Test
    fun `retry includes known concentrated answer context`() = runTest {
        var call = 0
        val question = source("q4", 0, "4. 集中答案题？ A. 甲 B. 乙")
        val answers = source("answers", 1, "答案汇总：4.A")
        val structured = """{
          "tempId":"four","sourceIds":["q4","answers"],"originalQuestionNumber":4,
          "type":"single","question":"集中答案题？",
          "options":[{"key":"A","text":"甲"},{"key":"B","text":"乙"}],"answer":["A"],
          "questionSource":["q4"],"optionSources":{"A":["q4"],"B":["q4"]},"answerSource":["answers"]
        }"""
        val client = RecordingSmartClient { request ->
            call++
            when (call) {
                1 -> """{"questions":[{"tempId":"four","sourceIds":["q4"],"originalQuestionNumber":4}],"answerSections":[{"sourceIds":["answers"]}]}"""
                2 -> "null"
                else -> """{"questions":[$structured],"answerSections":[{"sourceIds":["answers"]}]}"""
            }
        }
        val smart = pipeline(client)
        val first = smart.recognize("answer-retry.docx", listOf(question, answers), "key")
        val failed = first.report.records.first { it.status == SourceLedgerStatus.REJECTED_QUESTION }

        val retried = smart.retryFailedRecord(
            "answer-retry.docx",
            listOf(question, answers),
            first,
            failed,
            "key",
        )

        assertEquals(setOf("q4", "answers"), client.requests.last().sourceBlocks.map { it.sourceId }.toSet())
        assertEquals(1, retried.questions.size)
        assertEquals(0, retried.report.rejectedQuestionCount)
    }

    @Test
    fun `retry permits a failed question to share an accepted concentrated answer source`() = runTest {
        val firstQuestion = source("q1", 0, "1. 第一题？ A. 甲 B. 乙")
        val secondQuestion = source("q2", 1, "2. 第二题？ A. 丙 B. 丁")
        val answers = source("shared-answers", 2, "答案汇总：1.A 2.B")
        val acceptedOne = """{
          "tempId":"one","sourceIds":["q1","shared-answers"],"originalQuestionNumber":1,
          "type":"single","question":"第一题？",
          "options":[{"key":"A","text":"甲"},{"key":"B","text":"乙"}],"answer":["A"],
          "questionSource":["q1"],"optionSources":{"A":["q1"],"B":["q1"]},
          "answerSource":["shared-answers"]
        }"""
        val acceptedTwo = """{
          "tempId":"two","sourceIds":["q2","shared-answers"],"originalQuestionNumber":2,
          "type":"single","question":"第二题？",
          "options":[{"key":"A","text":"丙"},{"key":"B","text":"丁"}],"answer":["B"],
          "questionSource":["q2"],"optionSources":{"A":["q2"],"B":["q2"]},
          "answerSource":["shared-answers"]
        }"""
        var call = 0
        val client = RecordingSmartClient {
            call++
            when (call) {
                1 -> """{
                  "questions":[
                    $acceptedOne,
                    {"tempId":"two","sourceIds":["q2","shared-answers"],"originalQuestionNumber":2}
                  ],
                  "answerSections":[{"sourceIds":["shared-answers"]}]
                }"""
                2 -> "null"
                else -> """{
                  "questions":[$acceptedTwo],
                  "answerSections":[{"sourceIds":["shared-answers"]}]
                }"""
            }
        }
        val smart = pipeline(client)
        val sources = listOf(firstQuestion, secondQuestion, answers)
        val first = smart.recognize("shared-answer-retry.docx", sources, "key")
        val failed = first.report.records.single { it.status == SourceLedgerStatus.REJECTED_QUESTION }

        assertEquals(setOf("q2", "shared-answers"), failed.sourceIds.toSet())
        assertTrue("shared-answers" in first.questions.single().provenance.sourceIds)

        val retried = smart.retryFailedRecord("shared-answer-retry.docx", sources, first, failed, "key")

        assertEquals(3, client.calls)
        assertEquals(setOf("q2", "shared-answers"), client.requests.last().sourceBlocks.map { it.sourceId }.toSet())
        assertEquals(listOf(1, 2), retried.questions.map { it.originalQuestionNumber })
        assertEquals(2, retried.report.acceptedQuestionCount)
        assertEquals(0, retried.report.rejectedQuestionCount)
        assertTrue(retried.report.ledgerComplete)
    }

    @Test
    fun `retry cannot append a duplicate of an existing successful question`() = runTest {
        val q1 = source("q1", 0, "1. 重复题？ A. 甲 B. 乙 答案：A")
        val q2 = source("q2", 1, "2. 重复题？ A. 甲 B. 乙 答案：A")
        val client = RecordingSmartClient { request ->
            if (request.sourceBlocks.any { it.sourceId == "q1" }) {
                """{"questions":[${structuredQuestion("one", "q1", 1, "重复题？", "甲", "乙")}],"unresolvedSourceIds":["q2"]}"""
            } else {
                """{"questions":[${structuredQuestion("two", "q2", 2, "重复题？", "甲", "乙")}] }"""
            }
        }
        val smart = pipeline(client)
        val first = smart.recognize("duplicate-retry.docx", listOf(q1, q2), "key")
        val failed = first.report.records.first { it.status == SourceLedgerStatus.REJECTED_QUESTION }

        val retried = smart.retryFailedRecord("duplicate-retry.docx", listOf(q1, q2), first, failed, "key")

        assertEquals(1, retried.questions.size)
        assertTrue(retried.report.records.any { it.reasonCode == ImportFailureReason.DUPLICATE_QUESTION })
    }

    private fun pipeline(
        client: SmartImportModelClient,
        cache: SmartRequestCache = SmartRequestCache(),
        maxEstimatedTokens: Int = 6_000,
        overlapBlocks: Int = 2,
    ) = SmartImportPipeline(
        client = client,
        cache = cache,
        maxEstimatedTokens = maxEstimatedTokens,
        overlapBlocks = overlapBlocks,
        clock = { 1_000L },
        reportId = { "smart-report" },
    )

    private fun source(id: String, order: Int, text: String) = ImportSourceBlock(
        sourceId = id,
        sourceOrder = order,
        sourceType = SourceBlockType.PARAGRAPH,
        rawText = text,
    )

    private fun image(path: String, offset: Int) = SourceImageRef(
        mediaId = path,
        relationshipId = null,
        localPath = path,
        contentType = "image/png",
        supportedForDisplay = true,
        charOffset = offset,
    )

    private fun structuredQuestion(
        tempId: String,
        sourceId: String,
        number: Int,
        stem: String,
        optionA: String,
        optionB: String,
    ) = """
        {
          "tempId":"$tempId",
          "sourceIds":["$sourceId"],
          "originalQuestionNumber":$number,
          "type":"single",
          "question":"$stem",
          "options":[{"key":"A","text":"$optionA"},{"key":"B","text":"$optionB"}],
          "answer":["A"],
          "questionSource":["$sourceId"],
          "optionSources":{"A":["$sourceId"],"B":["$sourceId"]},
          "answerSource":["$sourceId"]
        }
    """.trimIndent()

    private fun assertSingleFailure(result: ImportRecognitionResult, reason: ImportFailureReason) {
        assertTrue(result.questions.isEmpty())
        assertEquals(1, result.report.rejectedQuestionCount)
        assertEquals(reason, result.report.records.single().reasonCode)
        assertTrue(result.report.records.single().apiAttempted)
        assertTrue(result.report.ledgerComplete)
    }
}

private class RecordingSmartClient(
    private val response: suspend (SmartModelRequest) -> String,
) : SmartImportModelClient {
    val requests = mutableListOf<SmartModelRequest>()
    val apiKeys = mutableListOf<String>()
    val calls: Int get() = requests.size

    override suspend fun complete(apiKey: String, request: SmartModelRequest): SmartModelCompletion {
        apiKeys += apiKey
        requests += request
        return SmartModelCompletion(response(request))
    }
}
