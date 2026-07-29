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
import com.zzy.quizforge.data.local.entity.ImportReportEntity
import com.zzy.quizforge.data.local.entity.ImportReportRecordEntity

@Database(
    entities = [
        QuizBankEntity::class,
        QuestionEntity::class,
        AnswerRecordEntity::class,
        QuizProgressEntity::class,
        ImportReportEntity::class,
        ImportReportRecordEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun quizBankDao(): QuizBankDao
    abstract fun questionDao(): QuestionDao
    abstract fun answerRecordDao(): AnswerRecordDao
    abstract fun quizProgressDao(): QuizProgressDao
    abstract fun importReportDao(): ImportReportDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE questions ADD COLUMN imageUri TEXT")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE questions ADD COLUMN imageUrisJson TEXT NOT NULL DEFAULT '[]'")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS import_reports (
                        reportId TEXT NOT NULL PRIMARY KEY,
                        bankId INTEGER,
                        fileName TEXT NOT NULL,
                        importMode TEXT NOT NULL,
                        startedAt INTEGER NOT NULL,
                        finishedAt INTEGER NOT NULL,
                        totalSourceBlocks INTEGER NOT NULL,
                        candidateQuestionCount INTEGER NOT NULL,
                        acceptedQuestionCount INTEGER NOT NULL,
                        rejectedQuestionCount INTEGER NOT NULL,
                        nonQuestionCount INTEGER NOT NULL,
                        unsupportedCount INTEGER NOT NULL,
                        imageCount INTEGER NOT NULL,
                        tableCount INTEGER NOT NULL,
                        usedApi INTEGER NOT NULL,
                        apiRequestCount INTEGER NOT NULL,
                        warningsJson TEXT NOT NULL,
                        ledgerComplete INTEGER NOT NULL,
                        FOREIGN KEY(bankId) REFERENCES quiz_banks(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_import_reports_bankId ON import_reports(bankId)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS import_report_records (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        reportId TEXT NOT NULL,
                        sourceIdsJson TEXT NOT NULL,
                        originalQuestionNumber INTEGER,
                        rawText TEXT NOT NULL,
                        status TEXT NOT NULL,
                        reasonCode TEXT,
                        reasonMessage TEXT,
                        createdQuestionIdsJson TEXT NOT NULL,
                        apiAttempted INTEGER NOT NULL,
                        FOREIGN KEY(reportId) REFERENCES import_reports(reportId) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_import_report_records_reportId ON import_report_records(reportId)")
            }
        }

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "quizforge.db",
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
    }
}
