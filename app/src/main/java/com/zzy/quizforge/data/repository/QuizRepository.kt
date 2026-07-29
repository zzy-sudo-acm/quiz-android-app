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
import com.zzy.quizforge.data.local.entity.ImportReportEntity
import com.zzy.quizforge.data.local.entity.ImportReportRecordEntity
import com.zzy.quizforge.data.local.toDomain
import com.zzy.quizforge.data.local.toEntity
import com.zzy.quizforge.domain.model.QuizMode
import com.zzy.quizforge.domain.model.QuizQuestion
import com.zzy.quizforge.util.JsonValidator
import com.zzy.quizforge.util.document.ImportReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStreamReader

class QuizRepository(
    private val database: AppDatabase,
    private val assets: AssetManager,
    private val filesDir: File,
) : QuizSessionRepository {
    private val gson = Gson()
    private val imageCleaner = BankImageCleaner(filesDir)

    fun observeBankSummaries(sequentialMode: String = QuizMode.SEQUENTIAL.routeValue): Flow<List<QuizBankSummaryRow>> =
        database.quizBankDao().observeSummaries(sequentialMode)

    suspend fun seedDefaultBankIfNeeded() {
        if (database.quizBankDao().countBanks() > 0) return
        val questions = assets.open("questions.json").use { input ->
            InputStreamReader(input, Charsets.UTF_8).use { reader ->
                JsonValidator.parseQuestions(reader.readText())
            }
        }
        createBank(name = "网络互联", questions = questions)
    }

    suspend fun clearStaleImportTasks(taskNames: Collection<String>) = withContext(NonCancellable + Dispatchers.IO) {
        imageCleaner.clearStaleImportTasks(taskNames)
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

    suspend fun appendQuestions(bankId: Long, questions: List<QuizQuestion>): List<Long> {
        if (questions.isEmpty()) return emptyList()
        return database.withTransaction {
            val ids = database.questionDao().insertAll(questions.map { it.toEntity(bankId) })
            database.quizBankDao().touchUpdated(bankId, System.currentTimeMillis())
            ids
        }
    }

    suspend fun saveImportReport(bankId: Long?, report: ImportReport, questionIds: List<Long> = emptyList()) {
        database.withTransaction {
            database.importReportDao().insertReport(
                ImportReportEntity(
                    reportId = report.reportId,
                    bankId = bankId,
                    fileName = report.fileName,
                    importMode = report.importMode.dbValue,
                    startedAt = report.startedAt,
                    finishedAt = report.finishedAt,
                    totalSourceBlocks = report.totalSourceBlocks,
                    candidateQuestionCount = report.candidateQuestionCount,
                    acceptedQuestionCount = report.acceptedQuestionCount,
                    rejectedQuestionCount = report.rejectedQuestionCount,
                    nonQuestionCount = report.nonQuestionCount,
                    unsupportedCount = report.unsupportedCount,
                    imageCount = report.imageCount,
                    tableCount = report.tableCount,
                    usedApi = report.usedApi,
                    apiRequestCount = report.apiRequestCount,
                    warningsJson = gson.toJson(report.warnings),
                    ledgerComplete = report.ledgerComplete,
                ),
            )
            database.importReportDao().insertRecords(
                report.records.map { record ->
                    val resolvedIds = (record.createdQuestionIds + record.createdQuestionIndexes.mapNotNull(questionIds::getOrNull)).distinct()
                    ImportReportRecordEntity(
                        reportId = report.reportId,
                        sourceIdsJson = gson.toJson(record.sourceIds),
                        originalQuestionNumber = record.originalQuestionNumber,
                        rawText = record.rawText,
                        status = record.status.name,
                        reasonCode = record.reasonCode?.name,
                        reasonMessage = record.reasonMessage,
                        createdQuestionIdsJson = gson.toJson(resolvedIds),
                        apiAttempted = record.apiAttempted,
                    )
                },
            )
        }
    }

    suspend fun deleteBank(bankId: Long) {
        val (deletedImagePaths, retainedImagePaths) = database.withTransaction {
            val deletedImagePaths = referencedImagePaths(database.questionDao().getQuestions(bankId))
            val retainedImagePaths = referencedImagePaths(database.questionDao().getQuestionsExcept(bankId))

            database.answerRecordDao().deleteByBankId(bankId)
            database.questionDao().deleteByBankId(bankId)
            database.quizProgressDao().deleteByBankId(bankId)
            database.quizBankDao().delete(bankId)
            deletedImagePaths to retainedImagePaths
        }
        runCatching {
            withContext(NonCancellable + Dispatchers.IO) {
                imageCleaner.deleteBankImages(bankId, deletedImagePaths, retainedImagePaths)
            }
        }.getOrElse { error ->
            throw IllegalStateException("题库数据已删除，但部分图片未能清理：${error.message}", error)
        }
    }

    override suspend fun getBankName(bankId: Long): String =
        database.quizBankDao().getBank(bankId)?.name ?: "题库"

    override suspend fun getQuestions(bankId: Long, mode: QuizMode): List<QuizQuestion> {
        val entities = when (mode) {
            QuizMode.WRONG -> database.questionDao().getWrongQuestions(bankId)
            else -> database.questionDao().getQuestions(bankId)
        }
        val questions = entities.map { it.toDomain() }
        return orderQuestionsForMode(questions, mode)
    }

    override suspend fun getProgress(bankId: Long, mode: QuizMode, total: Int): Int {
        if (mode == QuizMode.WRONG || mode == QuizMode.RANDOM || total <= 0) return 0
        val saved = database.quizProgressDao().getCurrentIndex(bankId, mode.routeValue)
        return resumeIndex(saved, total)
    }

    override suspend fun saveProgress(bankId: Long, mode: QuizMode, index: Int) {
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

    override suspend fun submitAnswer(question: QuizQuestion, selectedAnswer: Set<String>): Boolean {
        val correct = answersEqual(selectedAnswer, question.answer)
        val now = System.currentTimeMillis()

        return database.withTransaction {
            val previous = database.answerRecordDao().getRecord(question.id)
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
            correct
        }
    }

    suspend fun clearAllData() = withContext(NonCancellable + Dispatchers.IO) {
        database.clearAllTables()
        val cleanupFailure = runCatching { imageCleaner.clearAllImportedFiles() }.exceptionOrNull()
        val seedFailure = runCatching { seedDefaultBankIfNeeded() }.exceptionOrNull()
        if (seedFailure != null) {
            throw IllegalStateException("数据已清除，但预置题库恢复失败：${seedFailure.message}", seedFailure)
        }
        if (cleanupFailure != null) {
            throw IllegalStateException("数据已清除且预置题库已恢复，但部分导入图片仍待清理：${cleanupFailure.message}", cleanupFailure)
        }
    }
}
