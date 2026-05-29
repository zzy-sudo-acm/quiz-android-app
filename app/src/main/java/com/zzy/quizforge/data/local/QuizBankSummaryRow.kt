package com.zzy.quizforge.data.local

data class QuizBankSummaryRow(
    val id: Long,
    val name: String,
    val questionCount: Int,
    val answeredCount: Int,
    val correctCount: Int,
    val wrongCount: Int,
    val lastPracticedAt: Long?,
)
