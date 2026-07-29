package com.zzy.quizforge.data.repository

import com.zzy.quizforge.domain.model.QuizMode
import com.zzy.quizforge.domain.model.QuizQuestion

/**
 * 刷题页面所需的最小数据接口。
 *
 * 将状态机与完整题库仓储解耦，便于用 JVM 测试覆盖快速重复点击和数据库失败等时序。
 */
interface QuizSessionRepository {
    suspend fun getBankName(bankId: Long): String

    suspend fun getQuestions(bankId: Long, mode: QuizMode): List<QuizQuestion>

    suspend fun getProgress(bankId: Long, mode: QuizMode, total: Int): Int

    suspend fun saveProgress(bankId: Long, mode: QuizMode, index: Int)

    suspend fun submitAnswer(question: QuizQuestion, selectedAnswer: Set<String>): Boolean
}
