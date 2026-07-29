package com.zzy.quizforge.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.zzy.quizforge.data.local.entity.QuestionEntity

@Dao
interface QuestionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(questions: List<QuestionEntity>): List<Long>

    @Query("SELECT * FROM questions WHERE bankId = :bankId ORDER BY id ASC")
    suspend fun getQuestions(bankId: Long): List<QuestionEntity>

    @Query("SELECT * FROM questions WHERE bankId != :bankId")
    suspend fun getQuestionsExcept(bankId: Long): List<QuestionEntity>

    @Query("DELETE FROM questions WHERE bankId = :bankId")
    suspend fun deleteByBankId(bankId: Long)

    @Query(
        """
        SELECT q.*
        FROM questions q
        INNER JOIN answer_records r ON r.questionId = q.id
        WHERE q.bankId = :bankId AND r.isCorrect = 0
        ORDER BY r.answeredAt DESC
        """,
    )
    suspend fun getWrongQuestions(bankId: Long): List<QuestionEntity>
}
