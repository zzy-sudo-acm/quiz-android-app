package com.zzy.quizforge.domain.model

data class QuestionOption(
    val key: String,
    val text: String,
    val image: String? = null,
    val imageUri: String? = null,
)

data class QuizQuestion(
    val id: Long = 0,
    val bankId: Long = 0,
    val originalId: Int? = null,
    val type: QuestionType,
    val question: String,
    val options: List<QuestionOption>,
    val answer: List<String>,
    val explanation: String? = null,
    val knowledge: String? = null,
    val image: String? = null,
    val imageUri: String? = null,
)
