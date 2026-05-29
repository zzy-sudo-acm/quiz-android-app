package com.zzy.quizforge.domain.model

enum class QuizMode(
    val routeValue: String,
    val label: String,
) {
    SEQUENTIAL("sequential", "顺序刷题"),
    RANDOM("random", "随机刷题"),
    WRONG("wrong", "错题本");

    companion object {
        fun fromRoute(routeValue: String?): QuizMode =
            entries.firstOrNull { it.routeValue == routeValue } ?: SEQUENTIAL
    }
}
