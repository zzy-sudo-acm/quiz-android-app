package com.zzy.quizforge.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.zzy.quizforge.data.local.entity.QuizProgressEntity

@Dao
interface QuizProgressDao {
    @Query("SELECT currentIndex FROM quiz_progress WHERE bankId = :bankId AND mode = :mode")
    suspend fun getCurrentIndex(bankId: Long, mode: String): Int?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(progress: QuizProgressEntity)
}
