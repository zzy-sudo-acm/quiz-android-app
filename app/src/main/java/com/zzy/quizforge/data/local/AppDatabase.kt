package com.zzy.quizforge.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.migration.Migration
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.zzy.quizforge.data.local.entity.AnswerRecordEntity
import com.zzy.quizforge.data.local.entity.QuestionEntity
import com.zzy.quizforge.data.local.entity.QuizBankEntity
import com.zzy.quizforge.data.local.entity.QuizProgressEntity

@Database(
    entities = [
        QuizBankEntity::class,
        QuestionEntity::class,
        AnswerRecordEntity::class,
        QuizProgressEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun quizBankDao(): QuizBankDao
    abstract fun questionDao(): QuestionDao
    abstract fun answerRecordDao(): AnswerRecordDao
    abstract fun quizProgressDao(): QuizProgressDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE questions ADD COLUMN imageUri TEXT")
            }
        }

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "quizforge.db",
            )
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
