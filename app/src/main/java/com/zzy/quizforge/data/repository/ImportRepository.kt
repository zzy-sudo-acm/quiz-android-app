package com.zzy.quizforge.data.repository

import android.content.Context
import android.net.Uri
import com.zzy.quizforge.data.remote.DeepSeekApi
import com.zzy.quizforge.domain.model.QuizQuestion
import com.zzy.quizforge.util.DocumentContent
import com.zzy.quizforge.util.DocxParser
import com.zzy.quizforge.util.ImportedImage
import com.zzy.quizforge.util.JsonValidator
import com.zzy.quizforge.util.OriginalQuestionParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

class ImportRepository(
    context: Context,
    private val api: DeepSeekApi,
    private val quizRepository: QuizRepository,
    private val settingsStore: SettingsStore,
) {
    private val docxParser = DocxParser(context)

    fun extractDocx(uri: Uri): DocumentContent = docxParser.extractDocument(uri)

    /**
     * 导入流程：
     *  1. 本地 OriginalQuestionParser 解析整个 Word 文本
     *  2. 解析成功的题目直接入库（主流程）
     *  3. failedBlocks 逐段交给 DeepSeek API 修复格式（兜底）
     *     - 每次只发送 1 段，绝不发送整篇文档
     *     - API 不允许新增题目、不允许改写
     *     - 返回严格校验，失败一律跳过
     *  4. 上报本地识别 / API 修复 / 跳过段数
     */
    fun generateQuizBank(
        name: String,
        documentContent: DocumentContent,
    ): Flow<ImportProgress> = flow {
        require(documentContent.text.isNotBlank()) { "文档内容为空" }

        emit(ImportProgress.Log("> 正在本地识别 Word 原题...\n", 0))
        val parsed = OriginalQuestionParser.parse(documentContent.text)
        val localQuestions = parsed.questions.attachImportedImages(documentContent.images)
        val failedBlocks = parsed.failedBlocks

        emit(
            ImportProgress.Log(
                "> 本地识别 ${localQuestions.size} 道，疑似失败 ${failedBlocks.size} 段\n",
                0,
            ),
        )

        val apiQuestions = mutableListOf<QuizQuestion>()
        var skipped = 0

        if (failedBlocks.isNotEmpty()) {
            val apiKey = settingsStore.getApiKey()
            if (apiKey.isBlank()) {
                emit(ImportProgress.Log("> 未配置 DeepSeek API Key，跳过 API 修复\n", 0))
                skipped = failedBlocks.size
            } else {
                emit(ImportProgress.Log("> 开始逐段调用 DeepSeek API 修复格式...\n", 0))
                failedBlocks.forEachIndexed { index, block ->
                    val current = index + 1
                    emit(
                        ImportProgress.Segment(
                            current = current,
                            total = failedBlocks.size,
                            generatedSoFar = localQuestions.size + apiQuestions.size,
                        ),
                    )

                    val rawResponse = runCatching {
                        api.repairBlock(apiKey, block)
                    }.onFailure { error ->
                        emit(
                            ImportProgress.Log(
                                "  ! 第 $current 段 API 调用失败：${error.message ?: "未知错误"}\n",
                                0,
                            ),
                        )
                    }.getOrNull()

                    val repaired = rawResponse?.let { raw ->
                        runCatching { JsonValidator.parseRepairedQuestion(raw) }.getOrNull()
                    }

                    if (repaired != null) {
                        val withImages = listOf(repaired).attachImportedImages(documentContent.images)
                        apiQuestions += withImages.first()
                        emit(
                            ImportProgress.SegmentDone(
                                current = current,
                                total = failedBlocks.size,
                                generatedInSegment = 1,
                                generatedSoFar = localQuestions.size + apiQuestions.size,
                            ),
                        )
                    } else {
                        skipped += 1
                        emit(
                            ImportProgress.SegmentDone(
                                current = current,
                                total = failedBlocks.size,
                                generatedInSegment = 0,
                                generatedSoFar = localQuestions.size + apiQuestions.size,
                            ),
                        )
                    }
                }
            }
        }

        val allQuestions = localQuestions + apiQuestions
        require(allQuestions.isNotEmpty()) { "没有识别到有效原题" }

        val bankId = quizRepository.createBank(name, allQuestions)
        val message = "本地识别 ${localQuestions.size} 道，API 修复 ${apiQuestions.size} 道，跳过 $skipped 段"
        emit(
            ImportProgress.Done(
                bankId = bankId,
                count = allQuestions.size,
                skipped = skipped,
                message = message,
                localCount = localQuestions.size,
                apiCount = apiQuestions.size,
            ),
        )
    }.catch { error ->
        emit(ImportProgress.Error(error.message ?: "生成题库失败"))
    }.flowOn(Dispatchers.IO)

    private fun List<QuizQuestion>.attachImportedImages(images: List<ImportedImage>): List<QuizQuestion> {
        if (images.isEmpty()) return this
        return map { question ->
            val questionImage = images.firstOrNull { question.question.contains(it.marker) }?.uri
            question.copy(
                imageUri = question.imageUri ?: questionImage,
                options = question.options.map { option ->
                    val optionImage = images.firstOrNull { option.text.contains(it.marker) }?.uri
                    option.copy(imageUri = option.imageUri ?: optionImage)
                },
            )
        }
    }
}

sealed interface ImportProgress {
    data class Log(val text: String, val tokenCount: Int) : ImportProgress
    data class Segment(val current: Int, val total: Int, val generatedSoFar: Int) : ImportProgress
    data class SegmentDone(
        val current: Int,
        val total: Int,
        val generatedInSegment: Int,
        val generatedSoFar: Int,
    ) : ImportProgress
    data class Done(
        val bankId: Long,
        val count: Int,
        val partial: Boolean = false,
        val skipped: Int = 0,
        val message: String = "✓ 已生成 $count 道题",
        val localCount: Int = 0,
        val apiCount: Int = 0,
    ) : ImportProgress
    data class Error(val message: String) : ImportProgress
}
