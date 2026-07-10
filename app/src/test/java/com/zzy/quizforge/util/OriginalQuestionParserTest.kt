package com.zzy.quizforge.util

import org.junit.Assert.*
import org.junit.Test

class OriginalQuestionParserTest {

    // ── 基本三题场景：Q1 成功, Q2 失败, Q3 成功 ──
    @Test
    fun `three questions Q1 success Q2 failed Q3 success`() {
        val text = """
            1. 以下哪个是传输层协议？
            A. IP
            B. TCP
            C. HTTP
            D. FTP
            答案：B

            2. 这是一道格式损坏无法解析的题
            没有选项标记

            3. 以下哪个是网络层协议？
            A. TCP
            B. UDP
            C. IP
            D. HTTP
            答案：C
        """.trimIndent()

        val result = OriginalQuestionParser.parse(text)
        assertEquals(2, result.questions.size)
        assertEquals(1, result.failedBlocks.size)

        // Q1 成功
        assertEquals(1, result.questions[0].originalId)
        assertEquals("以下哪个是传输层协议？", result.questions[0].question)
        assertEquals(listOf("B"), result.questions[0].answer)

        // Q3 成功
        assertEquals(3, result.questions[1].originalId)
        assertEquals("以下哪个是网络层协议？", result.questions[1].question)
        assertEquals(listOf("C"), result.questions[1].answer)

        // Q2 失败：检查 FailedBlock 含有完整原文和正确的 originalIndex
        val failedQ2 = result.failedBlocks.single()
        assertEquals(1, failedQ2.originalIndex) // 0-based, block index 1
        assertTrue(failedQ2.text.contains("格式损坏无法解析"))
    }

    // ── failedBlock 不截断 ──
    @Test
    fun `failed block preserves full text beyond 500 chars`() {
        val longGarbage = "垃圾文本".repeat(300) // ~1200 chars
        val text = """
            1. 正常题
            A. 选项A
            B. 选项B
            答案：A

            2. $longGarbage
            没有选项格式
            答案：X
        """.trimIndent()

        val result = OriginalQuestionParser.parse(text)
        assertEquals(1, result.questions.size)
        assertEquals(1, result.failedBlocks.size)

        val failedBlock = result.failedBlocks.single()
        assertTrue(
            "Failed block should preserve full text beyond 500 chars, got ${failedBlock.text.length}",
            failedBlock.text.length > 500,
        )
        assertTrue(failedBlock.text.contains("垃圾文本"))
    }

    // ── 全角括号选项 A）B）C）D） ──
    @Test
    fun `fullwidth parenthesis options A）B）C）D）`() {
        val text = """
            1. 以下哪些是传输层协议？
            A）TCP
            B）UDP
            C）ICMP
            D）ARP
            答案：AB
        """.trimIndent()

        val result = OriginalQuestionParser.parse(text)
        assertEquals(1, result.questions.size)
        val q = result.questions.single()
        assertEquals(4, q.options.size)
        assertEquals("A", q.options[0].key)
        assertEquals("TCP", q.options[0].text)
        assertEquals("B", q.options[1].key)
        assertEquals("UDP", q.options[1].text)
    }

    // ── 六种选项标记逐一验证 ──
    @Test
    fun `all six option markers`() {
        val markers = listOf(
            "A)" to "A)",
            "A）" to "A）",
            "A." to "A.",
            "A．" to "A．",
            "A、" to "A、",
            "A:" to "A:",
            "A：" to "A：",
        )

        for ((label, marker) in markers) {
            val text = """
                1. 测试题
                ${marker}选项内容
                B. 另一个选项
                答案：A
            """.trimIndent()

            val result = OriginalQuestionParser.parse(text)
            assertEquals("Marker '$label' should parse", 1, result.questions.size)
            assertEquals("Marker '$label' should have 2 options", 2, result.questions[0].options.size)
        }
    }

    // ── 判断题 ──
    @Test
    fun `true false question`() {
        val text = """
            1. TCP是面向连接的协议。
            答案：对
        """.trimIndent()

        val result = OriginalQuestionParser.parse(text)
        assertEquals(1, result.questions.size)
        val q = result.questions.single()
        assertEquals(com.zzy.quizforge.domain.model.QuestionType.TRUE_FALSE, q.type)
        assertEquals(listOf("A"), q.answer)
        assertEquals(2, q.options.size)
        assertEquals("对", q.options[0].text)
        assertEquals("错", q.options[1].text)
    }
}
