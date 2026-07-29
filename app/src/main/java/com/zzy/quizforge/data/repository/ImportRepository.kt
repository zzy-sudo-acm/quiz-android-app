package com.zzy.quizforge.data.repository

import android.content.Context
import android.net.Uri
import com.zzy.quizforge.data.remote.DeepSeekApi
import com.zzy.quizforge.data.remote.DeepSeekSmartImportClient
import com.zzy.quizforge.domain.model.QuizQuestion
import com.zzy.quizforge.util.document.DocxArchiveLoader
import com.zzy.quizforge.util.document.ImportMode
import com.zzy.quizforge.util.document.ImportRecognitionResult
import com.zzy.quizforge.util.document.ImportReportRecord
import com.zzy.quizforge.util.document.OoXmlDocumentReader
import com.zzy.quizforge.util.document.PreparedImport
import com.zzy.quizforge.util.document.SmartImportPipeline
import com.zzy.quizforge.util.document.SmartPipelineProgress
import com.zzy.quizforge.util.document.SmartRequestCache
import com.zzy.quizforge.util.document.SourceBlockExtractor
import com.zzy.quizforge.util.document.StandardFormatParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.io.File
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

class ImportRepository(
    context: Context,
    private val api: DeepSeekApi,
    private val quizRepository: QuizRepository,
    private val settingsStore: SettingsStore,
) {
    private val appContext = context.applicationContext
    private val importMutex = Mutex()
    private val documentReader = OoXmlDocumentReader()
    private val standardParser = StandardFormatParser()
    private val smartClient = DeepSeekSmartImportClient(api) { settingsStore.getModelTier().modelName }
    private val smartCaches = mutableMapOf<String, SmartRequestCache>()
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Reads a DOCX exactly once into an import task. Standard preflight is performed immediately
     * and never reads the API key. Smart preparation also accepts a fully verified local tagged
     * format result; all other smart documents remain untouched until explicit API consent.
     */
    suspend fun prepareImport(uri: Uri, fileName: String, mode: ImportMode): PreparedImport =
        importMutex.withLock {
            val taskId = UUID.randomUUID().toString()
            val tempDir = File(appContext.filesDir, "import-temp/$taskId")
            try {
                withContext(Dispatchers.IO) {
                    check(tempDir.mkdirs() || tempDir.isDirectory) { "无法创建导入临时目录" }
                    val entries = DocxArchiveLoader.load(appContext.contentResolver, uri)
                    val document = documentReader.read(entries, File(tempDir, "images"))
                    val sources = SourceBlockExtractor.extract(document)
                    require(sources.any { it.isNonEmpty }) { "Word 文档没有可识别的非空内容" }
                    val localResult = when (mode) {
                        ImportMode.STANDARD -> standardParser.parse(fileName, sources)
                        ImportMode.SMART -> standardParser.parseTaggedIfComplete(fileName, sources)
                    }
                    val preflight = localResult?.let { result ->
                        result.copy(
                            report = result.report.copy(
                                importMode = mode,
                                warnings = (document.warnings.map { it.message } + result.report.warnings).distinct(),
                            ),
                        )
                    }
                    preflight?.let { quizRepository.saveImportReport(bankId = null, report = it.report) }
                    PreparedImport(taskId, fileName, mode, tempDir, document, sources, preflight)
                }
            } catch (error: Throwable) {
                withContext(NonCancellable + Dispatchers.IO) { tempDir.deleteRecursively() }
                throw error
            }
        }

    /** User-confirmed smart recognition. No call is made from [prepareImport]. */
    suspend fun recognizeSmart(
        prepared: PreparedImport,
        onProgress: (SmartPipelineProgress) -> Unit = {},
    ): ImportRecognitionResult = importMutex.withLock {
        require(prepared.mode == ImportMode.SMART) { "当前任务不是智能识别模式" }
        require(prepared.tempDir.isDirectory) { "导入任务已取消或过期" }
        prepared.standardPreflight?.let { return@withLock it }
        val key = settingsStore.getApiKey()
        val cache = smartCaches.getOrPut(prepared.taskId) { SmartRequestCache() }
        val result = SmartImportPipeline(smartClient, cache).recognize(
            fileName = prepared.fileName,
            sourceBlocks = prepared.sourceBlocks,
            apiKey = key,
            onProgress = onProgress,
        ).let { result ->
            result.copy(
                report = result.report.copy(
                    warnings = (prepared.document.warnings.map { it.message } + result.report.warnings).distinct(),
                ),
            )
        }
        quizRepository.saveImportReport(bankId = null, report = result.report)
        result
    }

    /** Retries only the selected failed source fragment and merges it into the full report. */
    suspend fun retrySmartRecord(
        prepared: PreparedImport,
        previous: ImportRecognitionResult,
        failedRecord: ImportReportRecord,
        onProgress: (SmartPipelineProgress) -> Unit = {},
    ): ImportRecognitionResult = importMutex.withLock {
        require(prepared.mode == ImportMode.SMART) { "当前任务不是智能识别模式" }
        require(prepared.tempDir.isDirectory) { "导入任务已取消或过期" }
        val key = settingsStore.getApiKey()
        val cache = smartCaches.getOrPut(prepared.taskId) { SmartRequestCache() }
        val result = SmartImportPipeline(smartClient, cache).retryFailedRecord(
            fileName = prepared.fileName,
            sourceBlocks = prepared.sourceBlocks,
            previous = previous,
            failedRecord = failedRecord,
            apiKey = key,
            onProgress = onProgress,
        ).let { result ->
            result.copy(
                report = result.report.copy(
                    warnings = (prepared.document.warnings.map { it.message } + result.report.warnings).distinct(),
                ),
            )
        }
        quizRepository.saveImportReport(bankId = null, report = result.report)
        result
    }

    /**
     * Commits an already computed preview. Images are moved only after the user confirms creation;
     * failures roll back the bank and remove the temporary directory.
     */
    suspend fun commitPreparedImport(
        prepared: PreparedImport,
        recognition: ImportRecognitionResult,
        bankName: String,
    ): Long = importMutex.withLock {
        require(recognition.questions.isNotEmpty()) { "没有可创建的有效题目" }
        require(prepared.tempDir.isDirectory) { "导入任务已取消或过期" }
        var bankId: Long? = null
        try {
            val createdBankId = quizRepository.createEmptyBank(bankName)
            bankId = createdBankId
            val bankDir = File(appContext.filesDir, "quiz-banks/$createdBankId")
            val destinationImages = File(bankDir, "images")
            val sourceImages = File(prepared.tempDir, "images")
            withContext(Dispatchers.IO) {
                if (sourceImages.isDirectory) {
                    check(destinationImages.mkdirs() || destinationImages.isDirectory) { "无法创建题库图片目录" }
                    check(sourceImages.copyRecursively(destinationImages, overwrite = false)) { "题库图片复制不完整" }
                }
            }
            val relocated = recognition.questions.map { recognized ->
                recognized.question.relocateImages(prepared.tempDir, destinationImages)
            }
            val questionIds = quizRepository.appendQuestions(createdBankId, relocated)
            quizRepository.saveImportReport(createdBankId, recognition.report, questionIds)
            withContext(Dispatchers.IO) { prepared.tempDir.deleteRecursively() }
            smartCaches.remove(prepared.taskId)
            createdBankId
        } catch (error: Throwable) {
            withContext(NonCancellable) {
                bankId?.let { runCatching { quizRepository.deleteBank(it) } }
                withContext(Dispatchers.IO) { prepared.tempDir.deleteRecursively() }
                runCatching { quizRepository.saveImportReport(bankId = null, report = recognition.report) }
                smartCaches.remove(prepared.taskId)
            }
            throw error
        }
    }

    suspend fun cancelImport(prepared: PreparedImport) = withContext(NonCancellable) {
        importMutex.withLock {
            withContext(Dispatchers.IO) { prepared.tempDir.deleteRecursively() }
            smartCaches.remove(prepared.taskId)
        }
    }

    /** Lifecycle cleanup is detached from the cancelled UI job and never blocks the main thread. */
    fun discardImport(prepared: PreparedImport) {
        cleanupScope.launch { cancelImport(prepared) }
    }

    fun hasApiKey(): Boolean = settingsStore.getApiKey().isNotBlank()

    suspend fun testModelConnection(candidateApiKey: String): Result<Unit> {
        val key = candidateApiKey.trim()
        if (key.isBlank()) return Result.failure(IllegalStateException("请先输入 API Key"))
        return runCatching { api.testConnection(key, settingsStore.getModelTier().modelName); Unit }
    }

    private fun QuizQuestion.relocateImages(tempDir: File, destinationImages: File): QuizQuestion {
        fun relocate(path: String?): String? {
            if (path.isNullOrBlank()) return null
            val file = File(path)
            return if (file.absolutePath.startsWith(tempDir.absolutePath)) {
                File(destinationImages, file.name).absolutePath
            } else path
        }
        val stem = (imageUris + listOfNotNull(imageUri)).mapNotNull(::relocate).distinct()
        return copy(
            imageUri = stem.firstOrNull(),
            imageUris = stem,
            options = options.map { option ->
                val paths = (option.imageUris + listOfNotNull(option.imageUri)).mapNotNull(::relocate).distinct()
                option.copy(imageUri = paths.firstOrNull(), imageUris = paths)
            },
        )
    }
}
