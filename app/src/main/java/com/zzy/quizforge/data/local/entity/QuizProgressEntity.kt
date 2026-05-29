package com.zzy.quizforge.data.local.entity

import androidx.room.Entity

@Entity(
    tableName = "quiz_progress",
    primaryKeys = ["bankId", "mode"],
)
data class QuizProgressEntity(
    val bankId: Long,
    val mode: String,
    val currentIndex: Int,
    val updatedAt: Long,
)
