package com.zzy.quizforge.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.zzy.quizforge.data.local.entity.QuizBankEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface QuizBankDao {
    @Query(
        """
        SELECT
            b.id AS id,
            b.name AS name,
            (SELECT COUNT(*) FROM questions q WHERE q.bankId = b.id) AS questionCount,
            (SELECT COUNT(*) FROM answer_records r WHERE r.bankId = b.id) AS answeredCount,
            (SELECT COUNT(*) FROM answer_records r WHERE r.bankId = b.id AND r.isCorrect = 1) AS correctCount,
            (SELECT COUNT(*) FROM answer_records r WHERE r.bankId = b.id AND r.isCorrect = 0) AS wrongCount,
            b.lastPracticedAt AS lastPracticedAt
        FROM quiz_banks b
        ORDER BY COALESCE(b.lastPracticedAt, b.updatedAt) DESC
        """,
    )
    fun observeSummaries(): Flow<List<QuizBankSummaryRow>>

    @Query("SELECT COUNT(*) FROM quiz_banks")
    suspend fun countBanks(): Int

    @Query("SELECT * FROM quiz_banks WHERE id = :bankId")
    suspend fun getBank(bankId: Long): QuizBankEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(bank: QuizBankEntity): Long

    @Query("UPDATE quiz_banks SET lastPracticedAt = :time, updatedAt = :time WHERE id = :bankId")
    suspend fun touchPracticed(bankId: Long, time: Long)

    @Query("UPDATE quiz_banks SET updatedAt = :time WHERE id = :bankId")
    suspend fun touchUpdated(bankId: Long, time: Long)

    @Query("DELETE FROM quiz_banks WHERE id = :bankId")
    suspend fun delete(bankId: Long)
}
