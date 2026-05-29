package com.zzy.quizforge.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "quiz_banks")
data class QuizBankEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val lastPracticedAt: Long? = null,
)
