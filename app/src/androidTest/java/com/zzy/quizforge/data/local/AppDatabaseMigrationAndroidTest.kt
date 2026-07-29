package com.zzy.quizforge.data.local

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationAndroidTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databases = mutableListOf<String>()

    @After
    fun cleanUp() {
        databases.forEach(context::deleteDatabase)
    }

    @Test
    fun migration1To3PreservesBanksQuestionsAnswersAndProgress() = verifyUpgradeFrom(1)

    @Test
    fun migration2To3PreservesBanksQuestionsAnswersAndProgress() = verifyUpgradeFrom(2)

    private fun verifyUpgradeFrom(version: Int) {
        val name = "migration-$version-to-3.db"
        databases += name
        createLegacyDatabase(name, version)

        val room = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3)
            .build()
        try {
            val sqlite = room.openHelper.writableDatabase
            assertEquals("旧题库", scalarString(sqlite, "SELECT name FROM quiz_banks WHERE id = 1"))
            assertEquals("旧题干", scalarString(sqlite, "SELECT question FROM questions WHERE id = 10"))
            assertEquals("[\"A\"]", scalarString(sqlite, "SELECT selectedAnswerJson FROM answer_records WHERE questionId = 10"))
            assertEquals(4L, scalarLong(sqlite, "SELECT currentIndex FROM quiz_progress WHERE bankId = 1"))
            assertEquals("[]", scalarString(sqlite, "SELECT imageUrisJson FROM questions WHERE id = 10"))
            assertEquals(if (version >= 2) "/data/old.png" else null, scalarNullableString(sqlite, "SELECT imageUri FROM questions WHERE id = 10"))
            assertTrue(tableExists(sqlite, "import_reports"))
            assertTrue(tableExists(sqlite, "import_report_records"))
            val migratedQuestion = runBlocking { room.questionDao().getQuestions(1).single().toDomain() }
            assertTrue(migratedQuestion.options.single().imageUris.isEmpty())
            assertEquals(listOfNotNull(migratedQuestion.imageUri), migratedQuestion.imageUris)
        } finally {
            room.close()
        }
    }

    private fun createLegacyDatabase(name: String, version: Int) {
        context.deleteDatabase(name)
        val callback = object : SupportSQLiteOpenHelper.Callback(version) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `quiz_banks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `lastPracticedAt` INTEGER)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `questions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `bankId` INTEGER NOT NULL, `originalId` INTEGER, `type` TEXT NOT NULL, `question` TEXT NOT NULL, `optionsJson` TEXT NOT NULL, `answerJson` TEXT NOT NULL, `explanation` TEXT, `knowledge` TEXT, `image` TEXT${if (version >= 2) ", `imageUri` TEXT" else ""}, FOREIGN KEY(`bankId`) REFERENCES `quiz_banks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_questions_bankId` ON `questions` (`bankId`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `answer_records` (`questionId` INTEGER NOT NULL, `bankId` INTEGER NOT NULL, `selectedAnswerJson` TEXT NOT NULL, `isCorrect` INTEGER NOT NULL, `answeredAt` INTEGER NOT NULL, `correctCount` INTEGER NOT NULL, `wrongCount` INTEGER NOT NULL, PRIMARY KEY(`questionId`), FOREIGN KEY(`questionId`) REFERENCES `questions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`bankId`) REFERENCES `quiz_banks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_answer_records_bankId` ON `answer_records` (`bankId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_answer_records_questionId` ON `answer_records` (`questionId`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `quiz_progress` (`bankId` INTEGER NOT NULL, `mode` TEXT NOT NULL, `currentIndex` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`bankId`, `mode`))")
                db.execSQL("INSERT INTO quiz_banks(id, name, createdAt, updatedAt, lastPracticedAt) VALUES(1, '旧题库', 1, 2, 3)")
                val imageUriColumn = if (version >= 2) ", imageUri" else ""
                val imageUriValue = if (version >= 2) ", '/data/old.png'" else ""
                db.execSQL("INSERT INTO questions(id, bankId, originalId, type, question, optionsJson, answerJson, explanation, knowledge, image$imageUriColumn) VALUES(10, 1, 7, 'single', '旧题干', '[{\"key\":\"A\",\"text\":\"旧选项\"}]', '[\"A\"]', '旧解析', '旧知识点', NULL$imageUriValue)")
                db.execSQL("INSERT INTO answer_records(questionId, bankId, selectedAnswerJson, isCorrect, answeredAt, correctCount, wrongCount) VALUES(10, 1, '[\"A\"]', 1, 4, 2, 1)")
                db.execSQL("INSERT INTO quiz_progress(bankId, mode, currentIndex, updatedAt) VALUES(1, 'sequential', 4, 5)")
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(callback)
                .build(),
        ).also { helper ->
            helper.writableDatabase
            helper.close()
        }
    }

    private fun scalarString(db: SupportSQLiteDatabase, sql: String): String =
        db.query(sql).use { cursor ->
            check(cursor.moveToFirst()) { "查询无结果：$sql" }
            cursor.getString(0)
        }

    private fun scalarLong(db: SupportSQLiteDatabase, sql: String): Long =
        db.query(sql).use { cursor ->
            check(cursor.moveToFirst()) { "查询无结果：$sql" }
            cursor.getLong(0)
        }

    private fun scalarNullableString(db: SupportSQLiteDatabase, sql: String): String? =
        db.query(sql).use { cursor ->
            check(cursor.moveToFirst()) { "查询无结果：$sql" }
            if (cursor.isNull(0)) null else cursor.getString(0)
        }

    private fun tableExists(db: SupportSQLiteDatabase, tableName: String): Boolean =
        db.query("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf(tableName)).use { it.moveToFirst() }
}
