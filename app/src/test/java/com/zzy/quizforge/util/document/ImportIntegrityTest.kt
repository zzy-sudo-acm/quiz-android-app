package com.zzy.quizforge.util.document

import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导入完整性校验：针对“静默丢失题目、重复生成题目、部分失败连带丢弃成功题目”
 * 等根因的回归测试。测试的是数据流与账本行为，而不是界面文字。
 */
class ImportIntegrityTest {

    private val standardParser = StandardFormatParser(
        clock = { 1_000L },
        reportId = { "integrity-standard" },
    )

    // ---------- 智能识别：边界合并不得静默丢弃题目 ----------

    @Test
    fun `distinct questions sharing one source and the same boundary number are never merged away`() = runTest {
        val combined = source(
            "combined",
            0,
            "1. 第一题？ 2. 第二题？ A. 甲 B. 乙 答案：A",
        )
        val response = """{"questions":[
            ${structuredQuestion("one", "combined", 1, "第一题？", "甲", "乙")},
            ${structuredQuestion("two", "combined", 1, "第二题？", "甲", "乙")}
        ]}""".trimIndent()

        val result = pipeline(ScriptedSmartClient { response }).recognize("collision.docx", listOf(combined), "key")

        // 两道不同的题撞上同一个边界键（同 source、同题号），必须同时保留而不是合并成一道。
        assertEquals(2, result.questions.size)
        assertEquals(
            listOf("第一题？", "第二题？"),
            result.questions.map { it.question.question },
        )
        assertEquals(2, result.report.acceptedQuestionCount)
        assertEquals(0, result.report.rejectedQuestionCount)
        assertEquals(0, result.report.duplicateQuestionCount)
        assertTrue(result.report.ledgerComplete)
    }

    @Test
    fun `identical boundary mentions in one response merge into a single question`() = runTest {
        val single = source("s1", 0, "1. 边界重复题？ A. 甲 B. 乙 答案：A")
        val response = """{"questions":[
            ${structuredQuestion("one", "s1", 1, "边界重复题？", "甲", "乙")},
            ${structuredQuestion("one", "s1", 1, "边界重复题？", "甲", "乙")}
        ]}""".trimIndent()

        val result = pipeline(ScriptedSmartClient { response }).recognize("same-mention.docx", listOf(single), "key")

        // 同一道题的两次边界提及属于重叠去重，不是“重复题目”。
        assertEquals(1, result.questions.size)
        assertEquals(1, result.report.acceptedQuestionCount)
        assertEquals(0, result.report.duplicateQuestionCount)
        assertTrue(result.report.ledgerComplete)
    }

    // ---------- 智能识别：重复题目必须被拒收、可见且可计数 ----------

    @Test
    fun `model duplicate is rejected with a visible record and counted in the report`() = runTest {
        val sources = listOf(
            source("q1", 0, "1. 同一道题？ A. 甲 B. 乙 答案：A"),
            source("q2", 1, "2. 同一道题？ A. 甲 B. 乙 答案：A"),
        )
        val response = """{"questions":[
            ${structuredQuestion("one", "q1", 1, "同一道题？", "甲", "乙")},
            ${structuredQuestion("two", "q2", 2, "同一道题？", "甲", "乙")}
        ]}""".trimIndent()

        val result = pipeline(ScriptedSmartClient { response }).recognize("dup.docx", sources, "key")

        assertEquals(1, result.questions.size)
        assertEquals(1, result.report.acceptedQuestionCount)
        assertEquals(1, result.report.rejectedQuestionCount)
        assertEquals(1, result.report.duplicateQuestionCount)
        assertEquals(2, result.report.candidateQuestionCount)
        val duplicateRecord = result.report.records.single {
            it.status == SourceLedgerStatus.REJECTED_QUESTION
        }
        assertEquals(ImportFailureReason.DUPLICATE_QUESTION, duplicateRecord.reasonCode)
        assertEquals(listOf("q2"), duplicateRecord.sourceIds)
        assertTrue(result.report.hasUncertainContent)
        assertTrue(result.report.ledgerComplete)
    }

    // ---------- 智能识别：部分失败不得连带丢弃已成功题目 ----------

    @Test
    fun `a failed structure request cannot discard an already accepted question`() = runTest {
        val sources = listOf(
            source("q1", 0, "1. JVM 运行哪种字节码？ A. Java bytecode B. Native code 答案：A"),
            source("q2", 1, "2. Kotlin 默认运行在哪个平台？ A. JVM B. BIOS 答案：A"),
        )
        val client = ScriptedSmartClient { request ->
            when (request.stage) {
                SmartRecognitionStage.BOUNDARY -> """{"questions":[
                    {"tempId":"one","sourceIds":["q1"],"originalQuestionNumber":1},
                    {"tempId":"two","sourceIds":["q2"],"originalQuestionNumber":2}
                ]}"""
                SmartRecognitionStage.STRUCTURE -> {
                    if ("q1" in request.candidateSourceIds) {
                        """{"questions":[${structuredQuestion("one", "q1", 1, "JVM 运行哪种字节码？", "Java bytecode", "Native code")}]}"""
                    } else {
                        throw IOException("provider down")
                    }
                }
            }
        }

        val result = pipeline(client).recognize("partial-failure.docx", sources, "key")

        assertEquals(1, result.questions.size)
        assertEquals("JVM 运行哪种字节码？", result.questions.single().question.question)
        assertEquals(1, result.report.acceptedQuestionCount)
        assertEquals(1, result.report.rejectedQuestionCount)
        assertEquals(2, result.report.candidateQuestionCount)
        val failed = result.report.records.single { it.status == SourceLedgerStatus.REJECTED_QUESTION }
        assertEquals(ImportFailureReason.API_REQUEST_FAILED, failed.reasonCode)
        assertEquals(listOf("q2"), failed.sourceIds)
        assertTrue(result.report.ledgerComplete)
    }

    @Test
    fun `empty structure response keeps the other accepted question and fails only that boundary`() = runTest {
        val sources = listOf(
            source("q1", 0, "1. JVM 运行哪种字节码？ A. Java bytecode B. Native code 答案：A"),
            source("q2", 1, "2. Kotlin 默认运行在哪个平台？ A. JVM B. BIOS 答案：A"),
        )
        val client = ScriptedSmartClient { request ->
            when (request.stage) {
                SmartRecognitionStage.BOUNDARY -> """{"questions":[
                    {"tempId":"one","sourceIds":["q1"],"originalQuestionNumber":1},
                    {"tempId":"two","sourceIds":["q2"],"originalQuestionNumber":2}
                ]}"""
                SmartRecognitionStage.STRUCTURE -> {
                    if ("q1" in request.candidateSourceIds) {
                        """{"questions":[${structuredQuestion("one", "q1", 1, "JVM 运行哪种字节码？", "Java bytecode", "Native code")}]}"""
                    } else {
                        """{"questions":[]}"""
                    }
                }
            }
        }

        val result = pipeline(client).recognize("empty-response.docx", sources, "key")

        assertEquals(1, result.questions.size)
        assertEquals(1, result.report.acceptedQuestionCount)
        assertEquals(1, result.report.rejectedQuestionCount)
        assertEquals(ImportFailureReason.API_RETURNED_NULL, result.report.records.single {
            it.status == SourceLedgerStatus.REJECTED_QUESTION
        }.reasonCode)
        assertTrue(result.report.ledgerComplete)
    }

    @Test
    fun `malformed element inside a structure questions array cannot corrupt accepted questions`() = runTest {
        val single = source("q1", 0, "1. JVM 运行哪种字节码？ A. Java bytecode B. Native code 答案：A")
        val client = ScriptedSmartClient { request ->
            when (request.stage) {
                SmartRecognitionStage.BOUNDARY ->
                    """{"questions":[{"tempId":"one","sourceIds":["q1"],"originalQuestionNumber":1}]}"""
                SmartRecognitionStage.STRUCTURE ->
                    """{"questions":[${structuredQuestion("one", "q1", 1, "JVM 运行哪种字节码？", "Java bytecode", "Native code")}, 42]}"""
            }
        }

        val result = pipeline(client).recognize("malformed-entry.docx", listOf(single), "key")

        assertEquals(1, result.questions.size)
        assertEquals(1, result.report.acceptedQuestionCount)
        assertEquals(0, result.report.rejectedQuestionCount)
        assertEquals(result.report.candidateQuestionCount, result.report.acceptedQuestionCount + result.report.rejectedQuestionCount)
        assertTrue(result.report.ledgerComplete)
    }

    // ---------- 标准解析：重复题目检测 ----------

    @Test
    fun `exact duplicate questions in standard mode are rejected as duplicates and counted`() {
        val result = standardParser.parse(
            "standard-duplicate.docx",
            listOf(
                source("first", 0, "1. 同一道题？\nA. 甲\nB. 乙\n答案：A"),
                source("second", 1, "2. 同一道题？\nA. 甲\nB. 乙\n答案：A"),
            ),
        )

        assertEquals(1, result.questions.size)
        assertEquals(1, result.report.acceptedQuestionCount)
        assertEquals(1, result.report.rejectedQuestionCount)
        assertEquals(1, result.report.duplicateQuestionCount)
        assertEquals(2, result.report.candidateQuestionCount)
        val duplicateRecord = result.report.records.single {
            it.status == SourceLedgerStatus.REJECTED_QUESTION
        }
        assertEquals(ImportFailureReason.DUPLICATE_QUESTION, duplicateRecord.reasonCode)
        assertEquals(listOf("second"), duplicateRecord.sourceIds)
        assertTrue(result.report.hasUncertainContent)
        assertTrue(result.report.ledgerComplete)
    }

    @Test
    fun `standard mode still accepts identical stems with different options`() {
        val result = standardParser.parse(
            "same-stem-different-options.docx",
            listOf(
                source("first", 0, "1. 相同题干？\nA. 甲\nB. 乙\n答案：A"),
                source("second", 1, "2. 相同题干？\nA. 丙\nB. 丁\n答案：B"),
            ),
        )

        assertEquals(2, result.questions.size)
        assertEquals(2, result.report.acceptedQuestionCount)
        assertEquals(0, result.report.rejectedQuestionCount)
        assertEquals(0, result.report.duplicateQuestionCount)
        assertTrue(result.report.ledgerComplete)
    }

    @Test
    fun `tagged document containing an exact duplicate fails the complete local preflight`() {
        val sources = listOf(
            source("first-header", 0, "[单选题]"),
            source("first", 1, "1. 同一道题？\nA. 甲\nB. 乙\n答案：A"),
            source("second-header", 2, "[单选题]"),
            source("second", 3, "2. 同一道题？\nA. 甲\nB. 乙\n答案：A"),
        )

        // 有重复项的文档不能声称“完全本地识别成功”，必须交给更保守的流程。
        assertNull(standardParser.parseTaggedIfComplete("tagged-dup.docx", sources))
        val visible = standardParser.parse("tagged-dup.docx", sources)
        assertEquals(1, visible.report.acceptedQuestionCount)
        assertEquals(1, visible.report.duplicateQuestionCount)
    }

    // ---------- 共享重复判定键 ----------

    @Test
    fun `duplicate key normalizes whitespace and full width punctuation`() {
        val base = com.zzy.quizforge.domain.model.QuizQuestion(
            originalId = 1,
            type = com.zzy.quizforge.domain.model.QuestionType.SINGLE,
            question = "同一道题？",
            options = listOf(
                com.zzy.quizforge.domain.model.QuestionOption("A", "甲"),
                com.zzy.quizforge.domain.model.QuestionOption("B", "乙"),
            ),
            answer = listOf("A"),
        )
        val whitespaceVariant = base.copy(
            options = base.options.map { option -> option.copy(text = " ${option.text} ") },
        )
        val differentQuestion = base.copy(question = "不同的题？")

        assertEquals(
            QuestionDuplicateKey.canonical(base),
            QuestionDuplicateKey.canonical(whitespaceVariant),
        )
        assertFalse(
            QuestionDuplicateKey.canonical(base) == QuestionDuplicateKey.canonical(differentQuestion),
        )
    }

    // ---------- helpers ----------

    private fun pipeline(
        client: SmartImportModelClient,
        maxEstimatedTokens: Int = 6_000,
        overlapBlocks: Int = 2,
    ) = SmartImportPipeline(
        client = client,
        cache = SmartRequestCache(),
        maxEstimatedTokens = maxEstimatedTokens,
        overlapBlocks = overlapBlocks,
        clock = { 1_000L },
        reportId = { "integrity-smart" },
    )

    private fun source(id: String, order: Int, text: String) = ImportSourceBlock(
        sourceId = id,
        sourceOrder = order,
        sourceType = SourceBlockType.PARAGRAPH,
        rawText = text,
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
}

private class ScriptedSmartClient(
    private val response: suspend (SmartModelRequest) -> String,
) : SmartImportModelClient {
    val requests = mutableListOf<SmartModelRequest>()
    val calls: Int get() = requests.size

    override suspend fun complete(apiKey: String, request: SmartModelRequest): SmartModelCompletion {
        requests += request
        return SmartModelCompletion(response(request))
    }
}
