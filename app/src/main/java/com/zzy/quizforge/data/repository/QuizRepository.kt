package com.zzy.quizforge.data.repository

import android.content.res.AssetManager
import androidx.room.withTransaction
import com.google.gson.Gson
import com.zzy.quizforge.data.local.AppDatabase
import com.zzy.quizforge.data.local.QuizBankSummaryRow
import com.zzy.quizforge.data.local.answersEqual
import com.zzy.quizforge.data.local.entity.AnswerRecordEntity
import com.zzy.quizforge.data.local.entity.QuizBankEntity
import com.zzy.quizforge.data.local.entity.QuizProgressEntity
import com.zzy.quizforge.data.local.toDomain
import com.zzy.quizforge.data.local.toEntity
import com.zzy.quizforge.domain.model.QuizMode
import com.zzy.quizforge.domain.model.QuizQuestion
import com.zzy.quizforge.util.JsonValidator
import kotlinx.coroutines.flow.Flow
import java.io.InputStreamReader
import kotlin.random.Random

class QuizRepository(
    private val database: AppDatabase,
    private val assets: AssetManager,
) {
    private val gson = Gson()

    fun observeBankSummaries(): Flow<List<QuizBankSummaryRow>> =
        database.quizBankDao().observeSummaries()

    suspend fun seedDefaultBankIfNeeded() {
        if (database.quizBankDao().countBanks() > 0) return
        val questions = assets.open("questions.json").use { input ->
            InputStreamReader(input, Charsets.UTF_8).use { reader ->
                JsonValidator.parseQuestions(reader.readText())
            }
        }
        createBank(name = "网络互联", questions = questions)
    }

    suspend fun createBank(name: String, questions: List<QuizQuestion>): Long =
        database.withTransaction {
            val now = System.currentTimeMillis()
            val bankId = database.quizBankDao().insert(
                QuizBankEntity(
                    name = name.ifBlank { "新题库" },
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            database.questionDao().insertAll(questions.map { it.toEntity(bankId) })
            bankId
        }

    suspend fun createEmptyBank(name: String): Long {
        val now = System.currentTimeMillis()
        return database.quizBankDao().insert(
            QuizBankEntity(
                name = name.ifBlank { "新题库" },
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    suspend fun appendQuestions(bankId: Long, questions: List<QuizQuestion>) {
        if (questions.isEmpty()) return
        database.withTransaction {
            database.questionDao().insertAll(questions.map { it.toEntity(bankId) })
            database.quizBankDao().touchUpdated(bankId, System.currentTimeMillis())
        }
    }

    suspend fun deleteBank(bankId: Long) {
        database.quizBankDao().delete(bankId)
    }

    suspend fun getBankName(bankId: Long): String =
        database.quizBankDao().getBank(bankId)?.name ?: "题库"

    suspend fun getQuestions(bankId: Long, mode: QuizMode): List<QuizQuestion> {
        val entities = when (mode) {
            QuizMode.WRONG -> database.questionDao().getWrongQuestions(bankId)
            else -> database.questionDao().getQuestions(bankId)
        }
        val questions = entities.map { it.toDomain() }
        return if (mode == QuizMode.RANDOM) {
            questions.shuffled(Random(System.nanoTime()))
        } else {
            questions
        }
    }

    suspend fun getProgress(bankId: Long, mode: QuizMode, total: Int): Int {
        if (mode == QuizMode.WRONG || mode == QuizMode.RANDOM || total <= 0) return 0
        val saved = database.quizProgressDao().getCurrentIndex(bankId, mode.routeValue) ?: 0
        return saved.coerceIn(0, (total - 1).coerceAtLeast(0))
    }

    suspend fun saveProgress(bankId: Long, mode: QuizMode, index: Int) {
        if (mode == QuizMode.WRONG || mode == QuizMode.RANDOM) return
        database.quizProgressDao().save(
            QuizProgressEntity(
                bankId = bankId,
                mode = mode.routeValue,
                currentIndex = index.coerceAtLeast(0),
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun submitAnswer(question: QuizQuestion, selectedAnswer: Set<String>): Boolean {
        val correct = answersEqual(selectedAnswer, question.answer)
        val previous = database.answerRecordDao().getRecord(question.id)
        val now = System.currentTimeMillis()

        database.withTransaction {
            database.answerRecordDao().insert(
                AnswerRecordEntity(
                    questionId = question.id,
                    bankId = question.bankId,
                    selectedAnswerJson = gson.toJson(selectedAnswer.map { it.uppercase() }.sorted()),
                    isCorrect = correct,
                    answeredAt = now,
                    correctCount = (previous?.correctCount ?: 0) + if (correct) 1 else 0,
                    wrongCount = (previous?.wrongCount ?: 0) + if (correct) 0 else 1,
                ),
            )
            database.quizBankDao().touchPracticed(question.bankId, now)
        }

        return correct
    }

    suspend fun clearAllData() {
        database.clearAllTables()
        seedDefaultBankIfNeeded()
    }
}
