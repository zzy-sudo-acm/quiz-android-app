package com.zzy.quizforge.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.zzy.quizforge.data.local.entity.AnswerRecordEntity

@Dao
interface AnswerRecordDao {
    @Query("SELECT * FROM answer_records WHERE questionId = :questionId")
    suspend fun getRecord(questionId: Long): AnswerRecordEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: AnswerRecordEntity)
}
