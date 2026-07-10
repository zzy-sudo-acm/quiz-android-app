package com.zzy.quizforge.util

import com.zzy.quizforge.domain.model.QuestionOption
import com.zzy.quizforge.domain.model.QuestionType
import com.zzy.quizforge.domain.model.QuizQuestion
import org.junit.Assert.*
import org.junit.Test

/**
 * 验证 SlotAssembler（ImportRepository 实际调用的组件）的排序行为。
 *
 * 测试覆盖真实生产路径：
 *   OriginalQuestionParser.parse() → OriginalQuestionParseResult(questions, failedBlocks)
 *   → AI 修复 failedBlocks → 回填 originalId
 *   → SlotAssembler.assemble() → 排序
 */
class ImportOrderingTest {

    private fun makeQuestion(originalId: Int, label: String): QuizQuestion =
        QuizQuestion(
            originalId = originalId,
            type = QuestionType.SINGLE,
            question = "题目$label",
            options = listOf(
                QuestionOption("A", "选项A"),
                QuestionOption("B", "选项B"),
            ),
            answer = listOf("A"),
        )

    // ═══════════════════════════════════════════════════════════
    // 通过 OriginalQuestionParser 的真实 parse 结果测试
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `real parse result Q1 success Q2 failed Q3 success assembled in order`() {
        val text = """
            1. 以下哪个是传输层协议？
            A. TCP
            B. UDP
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

        val parseResult = OriginalQuestionParser.parse(text)

        // 验证 parser 产生正确的数据结构
        assertEquals(2, parseResult.questions.size)
        assertEquals(1, parseResult.failedBlocks.size)
        assertEquals(1, parseResult.questions[0].originalId) // block 0 → Q1
        assertEquals(3, parseResult.questions[1].originalId) // block 2 → Q3

        val failedQ2 = parseResult.failedBlocks.single()
        assertEquals(1, failedQ2.originalIndex) // block 1 → Q2

        // 模拟 AI 修复：为 failedBlock 创建修复后的题目
        val repaired = makeQuestion(
            originalId = failedQ2.originalIndex + 1, // 2
            label = "Q2-repaired",
        )

        // 调用生产组件
        val allQuestions = SlotAssembler.assemble(parseResult, listOf(repaired))

        assertEquals(3, allQuestions.size)
        assertEquals(listOf(1, 2, 3), allQuestions.map { it.originalId })
        assertEquals("以下哪个是传输层协议？", allQuestions[0].question)
        assertEquals("题目Q2-repaired", allQuestions[1].question)
        assertEquals("以下哪个是网络层协议？", allQuestions[2].question)
    }

    @Test
    fun `all local parse success no repair needed`() {
        val text = """
            1. Q1题干
            A. 选项A
            B. 选项B
            答案：A

            2. Q2题干
            A. 选项A
            B. 选项B
            答案：B

            3. Q3题干
            A. 选项A
            B. 选项B
            答案：A
        """.trimIndent()

        val parseResult = OriginalQuestionParser.parse(text)
        assertEquals(3, parseResult.questions.size)
        assertEquals(0, parseResult.failedBlocks.size)

        val allQuestions = SlotAssembler.assemble(parseResult, emptyList())
        assertEquals(3, allQuestions.size)
        assertEquals(listOf(1, 2, 3), allQuestions.map { it.originalId })
    }

    @Test
    fun `all failed then all repaired preserves order`() {
        // 三题全部解析失败，全部被 AI 成功修复
        val text = """
            1. 损坏题一
            没有选项
            2. 损坏题二
            没有选项
            3. 损坏题三
            没有选项
        """.trimIndent()

        val parseResult = OriginalQuestionParser.parse(text)
        assertEquals(0, parseResult.questions.size)
        assertEquals(3, parseResult.failedBlocks.size)
        assertEquals(0, parseResult.failedBlocks[0].originalIndex)
        assertEquals(1, parseResult.failedBlocks[1].originalIndex)
        assertEquals(2, parseResult.failedBlocks[2].originalIndex)

        // AI 修复全部成功，回填 originalId
        val repaired = parseResult.failedBlocks.map { fb ->
            makeQuestion(
                originalId = fb.originalIndex + 1,
                label = "Q${fb.originalIndex + 1}-repaired",
            )
        }

        val allQuestions = SlotAssembler.assemble(parseResult, repaired)
        assertEquals(3, allQuestions.size)
        assertEquals(listOf(1, 2, 3), allQuestions.map { it.originalId })
        assertEquals("题目Q1-repaired", allQuestions[0].question)
        assertEquals("题目Q2-repaired", allQuestions[1].question)
        assertEquals("题目Q3-repaired", allQuestions[2].question)
    }

    @Test
    fun `partial repair skipped block leaves position gap`() {
        val text = """
            1. Q1题干
            A. 选项A
            B. 选项B
            答案：A

            2. 损坏题二无法修复
            没有选项

            3. Q3题干
            A. 选项A
            B. 选项B
            答案：B
        """.trimIndent()

        val parseResult = OriginalQuestionParser.parse(text)
        assertEquals(2, parseResult.questions.size)
        assertEquals(1, parseResult.failedBlocks.size)
        // Q2 在 block 1 失败，但 AI 也无法修复 → 不产生 repaired question

        val allQuestions = SlotAssembler.assemble(parseResult, emptyList())
        assertEquals(2, allQuestions.size)
        assertEquals(listOf(1, 3), allQuestions.map { it.originalId })
        // Q2 位置被跳过
    }

    // ═══════════════════════════════════════════════════════════
    // 边界情况
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `null originalId placed at end`() {
        val parseResult = OriginalQuestionParseResult(
            questions = listOf(
                makeQuestion(originalId = 1, label = "Q1"),
                makeQuestion(originalId = 2, label = "Q2").copy(originalId = null), // 缺少 originalId
            ),
            failedBlocks = emptyList(),
        )

        val allQuestions = SlotAssembler.assemble(parseResult, emptyList())
        assertEquals(2, allQuestions.size)
        assertEquals(1, allQuestions[0].originalId)
        assertNull(allQuestions[1].originalId)
    }
}
