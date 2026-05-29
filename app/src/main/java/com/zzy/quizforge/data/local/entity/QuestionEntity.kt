package com.zzy.quizforge.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "questions",
    foreignKeys = [
        ForeignKey(
            entity = QuizBankEntity::class,
            parentColumns = ["id"],
            childColumns = ["bankId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("bankId")],
)
data class QuestionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val bankId: Long,
    val originalId: Int? = null,
    val type: String,
    val question: String,
    val optionsJson: String,
    val answerJson: String,
    val explanation: String? = null,
    val knowledge: String? = null,
    val image: String? = null,
    val imageUri: String? = null,
)
