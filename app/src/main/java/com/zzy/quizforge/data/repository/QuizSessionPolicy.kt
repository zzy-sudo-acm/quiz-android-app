package com.zzy.quizforge.data.repository

import com.zzy.quizforge.domain.model.QuizMode
import com.zzy.quizforge.domain.model.QuizQuestion

/** 已完成的顺序练习会保存 total；再次进入时应从第一题开始。 */
internal fun resumeIndex(savedIndex: Int?, total: Int): Int {
    if (total <= 0) return 0
    return savedIndex?.takeIf { it in 0 until total } ?: 0
}

/** 随机练习和错题重练每次查询后都生成新一轮顺序。 */
internal fun orderQuestionsForMode(
    questions: List<QuizQuestion>,
    mode: QuizMode,
    shuffle: (List<QuizQuestion>) -> List<QuizQuestion> = { it.shuffled() },
): List<QuizQuestion> = when (mode) {
    QuizMode.RANDOM, QuizMode.WRONG -> shuffle(questions)
    QuizMode.SEQUENTIAL -> questions
}
