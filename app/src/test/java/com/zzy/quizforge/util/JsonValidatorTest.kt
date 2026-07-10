package com.zzy.quizforge.util

import com.zzy.quizforge.domain.model.QuestionOption
import com.zzy.quizforge.domain.model.QuestionType
import com.zzy.quizforge.domain.model.QuizQuestion
import org.junit.Assert.*
import org.junit.Test

class JsonValidatorTest {

    // ── SINGLE + ["A","B"] → 拒绝 ──
    @Test
    fun `SINGLE with two answers is rejected`() {
        val json = """
            {
                "type": "single",
                "question": "测试题",
                "options": [
                    {"key": "A", "text": "选项A"},
                    {"key": "B", "text": "选项B"},
                    {"key": "C", "text": "选项C"},
                    {"key": "D", "text": "选项D"}
                ],
                "answer": ["A", "B"]
            }
        """.trimIndent()

        val result = JsonValidator.parseRepairedQuestion(json)
        assertNull("SINGLE with 2 answers should be rejected", result)
    }

    // ── MULTIPLE + ["A"] → 拒绝 ──
    @Test
    fun `MULTIPLE with one answer is rejected`() {
        val json = """
            {
                "type": "multiple",
                "question": "多选题",
                "options": [
                    {"key": "A", "text": "选项A"},
                    {"key": "B", "text": "选项B"},
                    {"key": "C", "text": "选项C"}
                ],
                "answer": ["A"]
            }
        """.trimIndent()

        val result = JsonValidator.parseRepairedQuestion(json)
        assertNull("MULTIPLE with 1 answer should be rejected", result)
    }

    // ── 未知 type → 拒绝 ──
    @Test
    fun `unknown type is rejected`() {
        val json = """
            {
                "type": "mutiple",
                "question": "测试题",
                "options": [
                    {"key": "A", "text": "选项A"},
                    {"key": "B", "text": "选项B"}
                ],
                "answer": "A"
            }
        """.trimIndent()

        val result = JsonValidator.parseRepairedQuestion(json)
        assertNull("Unknown type 'mutiple' should be rejected", result)
    }

    @Test
    fun `foo type is rejected`() {
        val json = """
            {
                "type": "foo",
                "question": "测试题",
                "options": [
                    {"key": "A", "text": "选项A"},
                    {"key": "B", "text": "选项B"}
                ],
                "answer": "A"
            }
        """.trimIndent()

        val result = JsonValidator.parseRepairedQuestion(json)
        assertNull("Unknown type 'foo' should be rejected", result)
    }

    // ── TRUE_FALSE + answer.size != 1 → 拒绝 ──
    @Test
    fun `TRUE_FALSE with two answers is rejected`() {
        val json = """
            {
                "type": "truefalse",
                "question": "判断题",
                "options": [
                    {"key": "A", "text": "对"},
                    {"key": "B", "text": "错"}
                ],
                "answer": ["A", "B"]
            }
        """.trimIndent()

        val result = JsonValidator.parseRepairedQuestion(json)
        assertNull("TRUE_FALSE with 2 answers should be rejected", result)
    }

    // ── 正常的 SINGLE ──
    @Test
    fun `valid SINGLE is accepted`() {
        val json = """
            {
                "type": "single",
                "question": "测试题",
                "options": [
                    {"key": "A", "text": "选项A"},
                    {"key": "B", "text": "选项B"}
                ],
                "answer": "A"
            }
        """.trimIndent()

        val result = JsonValidator.parseRepairedQuestion(json)
        assertNotNull("Valid SINGLE should be accepted", result)
        assertEquals(QuestionType.SINGLE, result!!.type)
        assertEquals(listOf("A"), result.answer)
    }

    // ── 正常的 MULTIPLE ──
    @Test
    fun `valid MULTIPLE is accepted`() {
        val json = """
            {
                "type": "multiple",
                "question": "多选题",
                "options": [
                    {"key": "A", "text": "选项A"},
                    {"key": "B", "text": "选项B"},
                    {"key": "C", "text": "选项C"}
                ],
                "answer": ["A", "C"]
            }
        """.trimIndent()

        val result = JsonValidator.parseRepairedQuestion(json)
        assertNotNull("Valid MULTIPLE should be accepted", result)
        assertEquals(QuestionType.MULTIPLE, result!!.type)
        assertEquals(listOf("A", "C"), result.answer)
    }

    // ── 正常的 TRUE_FALSE ──
    @Test
    fun `valid TRUE_FALSE is accepted`() {
        val json = """
            {
                "type": "truefalse",
                "question": "判断题",
                "options": [
                    {"key": "A", "text": "对"},
                    {"key": "B", "text": "错"}
                ],
                "answer": "A"
            }
        """.trimIndent()

        val result = JsonValidator.parseRepairedQuestion(json)
        assertNotNull("Valid TRUE_FALSE should be accepted", result)
        assertEquals(QuestionType.TRUE_FALSE, result!!.type)
    }

    // ── answer 中的 "AB" 展开问题（回归：parseAnswer 应正确处理 "AB"） ──
    @Test
    fun `answer AB is expanded to A and B`() {
        val json = """
            {
                "type": "multiple",
                "question": "多选题",
                "options": [
                    {"key": "A", "text": "选项A"},
                    {"key": "B", "text": "选项B"},
                    {"key": "C", "text": "选项C"}
                ],
                "answer": "AB"
            }
        """.trimIndent()

        val result = JsonValidator.parseRepairedQuestion(json)
        assertNotNull("AB answer should be expanded", result)
        assertEquals(listOf("A", "B"), result!!.answer)
    }

    // ── 题干为 null → 拒绝 ──
    @Test
    fun `null literal is rejected`() {
        val result = JsonValidator.parseRepairedQuestion("null")
        assertNull(result)
    }

    // ── 空字符串 → 拒绝 ──
    @Test
    fun `empty string is rejected`() {
        val result = JsonValidator.parseRepairedQuestion("")
        assertNull(result)
    }
}
