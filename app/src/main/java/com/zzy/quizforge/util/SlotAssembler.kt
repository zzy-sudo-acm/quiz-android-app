package com.zzy.quizforge.util

import com.zzy.quizforge.domain.model.QuizQuestion

/**
 * 将本地解析结果 + AI 修复结果按 DOCX 原文顺序组装为最终题目列表。
 *
 * 规则：
 * - [OriginalQuestionParseResult.questions] 中已带有正确的 [QuizQuestion.originalId]
 * - AI 修复的题目在回填 [QuizQuestion.originalId] 后传入 [repairedQuestions]
 * - 最终结果按 [QuizQuestion.originalId] 升序排列
 * - 未修复（跳过）的位置不会出现在结果中
 * - [QuizQuestion.originalId] 为 null 的题目排在末尾
 *
 * ## 第一阶段设计债说明
 *
 * 当前 [QuizQuestion.originalId] 同时承担了 sourceOrder / sourceId / originalQuestionNumber
 * 三重语义。这在只有一种导入来源时可行，但语义不精确。
 *
 * 第二阶段 Document IR 重构时应拆分为独立字段：
 * - sourceOrder: Int     文档内绝对位置（用于排序）
 * - sourceId: String     来源标识（块 hash 或行号，用于去重和诊断）
 * - originalNumber: Int? 用户可见的原始题号（可能为 null）
 */
object SlotAssembler {

    /**
     * 按原始文档位置组装最终题目列表。
     *
     * @param parseResult 来自 [OriginalQuestionParser.parse] 的完整解析结果
     * @param repairedQuestions AI 修复成功的题目，每个题目必须已通过
     *   `repaired.copy(originalId = failedBlock.originalIndex + 1)` 设置正确的 originalId
     * @return 按 DOCX 原文顺序排列的题目列表
     */
    fun assemble(
        parseResult: OriginalQuestionParseResult,
        repairedQuestions: List<QuizQuestion>,
    ): List<QuizQuestion> {
        val all = parseResult.questions + repairedQuestions
        return all.sortedBy { it.originalId ?: Int.MAX_VALUE }
    }
}
