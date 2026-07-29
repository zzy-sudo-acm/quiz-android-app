package com.zzy.quizforge.domain.model

data class QuestionOption(
    val key: String,
    val text: String,
    val image: String? = null,
    val imageUri: String? = null,
    /** All images owned by this option. [imageUri] is retained for old databases. */
    val imageUris: List<String> = emptyList(),
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
    /** All stem images in source order. [imageUri] remains the legacy first-image field. */
    val imageUris: List<String> = emptyList(),
)
