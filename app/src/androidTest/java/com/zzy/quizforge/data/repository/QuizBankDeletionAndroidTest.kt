package com.zzy.quizforge.data.repository

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.zzy.quizforge.data.local.AppDatabase
import com.zzy.quizforge.data.local.entity.AnswerRecordEntity
import com.zzy.quizforge.data.local.entity.QuestionEntity
import com.zzy.quizforge.data.local.entity.QuizBankEntity
import com.zzy.quizforge.data.local.entity.QuizProgressEntity
import com.zzy.quizforge.domain.model.QuestionOption
import com.zzy.quizforge.domain.model.QuizMode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class QuizBankDeletionAndroidTest {
    private lateinit var database: AppDatabase
    private lateinit var testFilesDir: File

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        testFilesDir = File(context.cacheDir, "bank-deletion-${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        database.close()
        testFilesDir.deleteRecursively()
    }

    @Test
    fun deletingBankRemovesRowsAndOwnedImagesButKeepsSharedImage() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = QuizRepository(database, context.assets, testFilesDir)
        val now = System.currentTimeMillis()
        val deletedBankId = database.quizBankDao().insert(QuizBankEntity(name = "待删除", createdAt = now, updatedAt = now))
        val retainedBankId = database.quizBankDao().insert(QuizBankEntity(name = "保留", createdAt = now, updatedAt = now))

        val ownedImage = createFile("docx-images/owned.png")
        val sharedImage = createFile("docx-images-ir/shared.png")
        val bankDirectoryOrphan = createFile("quiz-banks/$deletedBankId/images/orphan.png")
        val optionsJson = Gson().toJson(listOf(QuestionOption("A", "选项", imageUri = sharedImage.absolutePath)))

        database.questionDao().insertAll(
            listOf(
                question(deletedBankId, ownedImage.absolutePath, optionsJson),
                question(retainedBankId, sharedImage.absolutePath, "[]"),
            ),
        )
        val deletedQuestionId = database.questionDao().getQuestions(deletedBankId).single().id
        database.answerRecordDao().insert(
            AnswerRecordEntity(
                questionId = deletedQuestionId,
                bankId = deletedBankId,
                selectedAnswerJson = "[\"A\"]",
                isCorrect = true,
                answeredAt = now,
                correctCount = 1,
                wrongCount = 0,
            ),
        )
        database.quizProgressDao().save(
            QuizProgressEntity(
                bankId = deletedBankId,
                mode = QuizMode.SEQUENTIAL.routeValue,
                currentIndex = 1,
                updatedAt = now,
            ),
        )

        repository.deleteBank(deletedBankId)

        assertNull(database.quizBankDao().getBank(deletedBankId))
        assertTrue(database.questionDao().getQuestions(deletedBankId).isEmpty())
        assertNull(database.answerRecordDao().getRecord(deletedQuestionId))
        assertNull(database.quizProgressDao().getCurrentIndex(deletedBankId, QuizMode.SEQUENTIAL.routeValue))
        assertFalse(ownedImage.exists())
        assertFalse(bankDirectoryOrphan.exists())
        assertTrue(sharedImage.exists())
    }

    private fun question(bankId: Long, imageUri: String?, optionsJson: String): QuestionEntity =
        QuestionEntity(
            bankId = bankId,
            type = "single",
            question = "题干",
            optionsJson = optionsJson,
            answerJson = "[\"A\"]",
            imageUri = imageUri,
        )

    private fun createFile(relativePath: String): File =
        File(testFilesDir, relativePath).also { file ->
            file.parentFile?.mkdirs()
            file.writeText("fixture")
        }
}
