package com.zzy.quizforge.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "answer_records",
    foreignKeys = [
        ForeignKey(
            entity = QuestionEntity::class,
            parentColumns = ["id"],
            childColumns = ["questionId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = QuizBankEntity::class,
            parentColumns = ["id"],
            childColumns = ["bankId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("bankId"), Index("questionId")],
)
data class AnswerRecordEntity(
    @PrimaryKey
    val questionId: Long,
    val bankId: Long,
    val selectedAnswerJson: String,
    val isCorrect: Boolean,
    val answeredAt: Long,
    val correctCount: Int,
    val wrongCount: Int,
)
