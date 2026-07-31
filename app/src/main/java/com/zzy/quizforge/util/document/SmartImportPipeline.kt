package com.zzy.quizforge.util.document

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.zzy.quizforge.domain.model.QuestionOption
import com.zzy.quizforge.domain.model.QuestionType
import com.zzy.quizforge.domain.model.QuizQuestion
import com.zzy.quizforge.util.AnswerNormalizer
import kotlinx.coroutines.CancellationException
import java.security.MessageDigest
import java.text.Normalizer
import java.util.UUID

enum class SmartRecognitionStage { BOUNDARY, STRUCTURE }

data class SourceBlockSlice(
    val sourceId: String,
    val sourceOrder: Int,
    val sourceType: String,
    val rawText: String,
    val charStart: Int,
    val charEnd: Int,
    val numbering: SourceNumbering?,
    val table: SourceTablePosition?,
    val images: List<String>,
)

data class SmartModelRequest(
    val stage: SmartRecognitionStage,
    val chunkId: String,
    val sourceBlocks: List<SourceBlockSlice>,
    val candidateSourceIds: List<String> = emptyList(),
    val answerSectionSourceIds: List<String> = emptyList(),
)

data class SmartModelUsage(
    val promptTokens: Long? = null,
    val completionTokens: Long? = null,
    val totalTokens: Long? = null,
)

data class SmartModelCompletion(
    val content: String,
    val finishReason: String? = null,
    val usage: SmartModelUsage? = null,
)

/** Provider-neutral interface; no model name or endpoint leaks into the pipeline. */
fun interface SmartImportModelClient {
    suspend fun complete(apiKey: String, request: SmartModelRequest): SmartModelCompletion
}

class SmartRequestCache {
    private val successful = mutableMapOf<String, String>()

    fun get(key: String): String? = successful[key]
    fun put(key: String, response: String) {
        successful[key] = response
    }
}

data class SmartPipelineProgress(
    val stage: SmartRecognitionStage,
    val current: Int,
    val total: Int,
    val apiRequestCount: Int,
)

/**
 * Document-level smart recognition. Every non-empty source block is included in a boundary
 * request; local parsing is not used as a gate and there is no failedBlocks path.
 */
class SmartImportPipeline(
    private val client: SmartImportModelClient,
    private val cache: SmartRequestCache = SmartRequestCache(),
    private val maxEstimatedTokens: Int = 6_000,
    private val overlapBlocks: Int = 2,
    private val clock: () -> Long = System::currentTimeMillis,
    private val reportId: () -> String = { UUID.randomUUID().toString() },
) {
    private val gson = Gson()

    /**
     * Re-runs recognition for one failed fragment plus its known concentrated-answer context.
     * A source may already have produced one question while another question in the same source
     * is still unresolved, so overlap with accepted provenance is intentional and deduplicated
     * when the retry result is merged. A fresh request cache prevents an unusable old response
     * from making retries permanently return the same failure.
     */
    suspend fun retryFailedRecord(
        fileName: String,
        sourceBlocks: List<ImportSourceBlock>,
        previous: ImportRecognitionResult,
        failedRecord: ImportReportRecord,
        apiKey: String,
        onProgress: (SmartPipelineProgress) -> Unit = {},
    ): ImportRecognitionResult {
        val allSources = sourceBlocks.filter { it.isNonEmpty }.sortedBy { it.sourceOrder }
        val sourceById = allSources.associateBy { it.sourceId }
        require(failedRecord.status.isRetryableFailure()) { "只能重试失败或不支持的报告记录" }
        require(previous.report.records.any { it == failedRecord }) { "所选片段不属于当前导入报告" }
        val requestedIds = failedRecord.sourceIds.distinct().toSet()
        require(requestedIds.isNotEmpty()) { "失败片段没有可重试的原文" }
        require(requestedIds.all(sourceById::containsKey)) { "失败片段引用了不存在的原文" }

        val contextIds = (requestedIds + previous.report.answerSectionSourceIds).toSet()
        val retryResult = SmartImportPipeline(
            client = client,
            cache = SmartRequestCache(),
            maxEstimatedTokens = maxEstimatedTokens,
            overlapBlocks = overlapBlocks,
            clock = clock,
            reportId = reportId,
        ).recognize(
            fileName = fileName,
            sourceBlocks = allSources.filter { it.sourceId in contextIds },
            apiKey = apiKey,
            onProgress = onProgress,
        )
        return mergeRetryResult(allSources, previous, retryResult, requestedIds)
    }

    suspend fun recognize(
        fileName: String,
        sourceBlocks: List<ImportSourceBlock>,
        apiKey: String,
        onProgress: (SmartPipelineProgress) -> Unit = {},
    ): ImportRecognitionResult {
        val startedAt = clock()
        val sources = sourceBlocks.filter { it.isNonEmpty }.sortedBy { it.sourceOrder }
        val sourceById = sources.associateBy { it.sourceId }
        val ledger = SourceLedger(sources)
        val records = mutableListOf<ImportReportRecord>()
        val accepted = mutableListOf<RecognizedQuestion>()
        val answerSectionIds = linkedSetOf<String>()
        val pending = linkedMapOf<String, BoundaryCandidate>()
        val failures = mutableMapOf<String, Failure>()
        val warnings = mutableListOf<String>()
        var requestCount = 0

        if (apiKey.isBlank()) {
            sources.forEach { source ->
                ledger.mark(listOf(source.sourceId), SourceLedgerStatus.REJECTED_QUESTION)
                records += rejectedRecord(source, ImportFailureReason.API_KEY_MISSING, "智能识别需要先配置 API Key", false)
            }
            return result(fileName, startedAt, sources, ledger, accepted, records, warnings, requestCount, usedApi = false)
        }

        val chunks = SourceBlockChunker(maxEstimatedTokens, overlapBlocks).chunk(sources)
        chunks.forEachIndexed { index, chunk ->
            onProgress(SmartPipelineProgress(SmartRecognitionStage.BOUNDARY, index + 1, chunks.size, requestCount))
            val request = SmartModelRequest(
                stage = SmartRecognitionStage.BOUNDARY,
                chunkId = chunk.id,
                sourceBlocks = chunk.slices,
            )
            val scopedResponses = callBoundaryAdaptive(request, apiKey) { requestCount++ }
            scopedResponses.forEach { scoped ->
                val response = scoped.call
                val responseSourceIds = scoped.request.sourceBlocks.map { it.sourceId }.distinct()
                when (response) {
                    is ModelCall.Failed -> responseSourceIds.forEach { sourceId ->
                        failures.putIfAbsent(sourceId, Failure(ImportFailureReason.API_REQUEST_FAILED, response.message, true))
                    }
                    is ModelCall.Truncated -> responseSourceIds.forEach { sourceId ->
                        failures.putIfAbsent(
                            sourceId,
                            Failure(ImportFailureReason.API_RESPONSE_TRUNCATED, response.message, true),
                        )
                    }
                    is ModelCall.Null -> responseSourceIds.forEach { sourceId ->
                        failures.putIfAbsent(sourceId, Failure(ImportFailureReason.API_RETURNED_NULL, "模型返回 null", true))
                    }
                    is ModelCall.Success -> {
                        try {
                            val parsed = SmartResponseParser.parse(response.raw)
                            val scopeError = if (parsed.error == null) {
                                responseScopeError(parsed, responseSourceIds.toSet())
                            } else null
                            if (parsed.error != null) {
                                responseSourceIds.forEach { sourceId ->
                                    failures.putIfAbsent(sourceId, Failure(ImportFailureReason.API_INVALID_JSON, parsed.error, true))
                                }
                            } else if (scopeError != null) {
                                responseSourceIds.forEach { sourceId ->
                                    failures.putIfAbsent(
                                        sourceId,
                                        Failure(ImportFailureReason.SOURCE_NOT_COVERED, scopeError, true),
                                    )
                                }
                            } else {
                            val unresolvedIds = parsed.unresolvedSourceIds.toSet()
                            (classifiedSourceIds(parsed) - unresolvedIds).forEach(failures::remove)
                            parsed.nonQuestionSourceIds.filter(sourceById::containsKey).forEach { sourceId ->
                                if (ledger.status(sourceId) != null) return@forEach
                                ledger.mark(listOf(sourceId), SourceLedgerStatus.NON_QUESTION_CONTENT)
                                records += ImportReportRecord(
                                    sourceIds = listOf(sourceId),
                                    originalQuestionNumber = null,
                                    rawText = sourceById.getValue(sourceId).rawText,
                                    status = SourceLedgerStatus.NON_QUESTION_CONTENT,
                                    reasonMessage = "模型明确标记为标题、章节或非题目内容",
                                    apiAttempted = true,
                                )
                            }
                            parsed.unsupportedSourceIds.filter(sourceById::containsKey).forEach { sourceId ->
                                if (ledger.status(sourceId) != null) return@forEach
                                ledger.mark(listOf(sourceId), SourceLedgerStatus.UNSUPPORTED_CONTENT)
                                records += ImportReportRecord(
                                    sourceIds = listOf(sourceId),
                                    originalQuestionNumber = null,
                                    rawText = sourceById.getValue(sourceId).rawText,
                                    status = SourceLedgerStatus.UNSUPPORTED_CONTENT,
                                    reasonCode = ImportFailureReason.SOURCE_NOT_COVERED,
                                    reasonMessage = "模型确认该 Word 结构暂不支持",
                                    apiAttempted = true,
                                )
                            }
                            parsed.answerSectionSourceIds.filter(sourceById::containsKey).forEach { sourceId ->
                                if (answerSectionIds.add(sourceId) && ledger.status(sourceId) == null) {
                                    ledger.mark(listOf(sourceId), SourceLedgerStatus.NON_QUESTION_CONTENT)
                                    records += ImportReportRecord(
                                        sourceIds = listOf(sourceId),
                                        originalQuestionNumber = null,
                                        rawText = sourceById.getValue(sourceId).rawText,
                                        status = SourceLedgerStatus.NON_QUESTION_CONTENT,
                                        reasonMessage = "模型识别为集中答案区",
                                        apiAttempted = true,
                                    )
                                }
                            }
                            parsed.questions.forEach { candidate ->
                                val key = candidate.identityKey()
                                val existing = pending[key]
                                when {
                                    existing == null -> pending[key] = candidate
                                    // Only merge when both sides clearly denote the same question.
                                    // Distinct questions that collide on the same boundary key must
                                    // stay separate, otherwise one of them silently disappears.
                                    existing.sameQuestionAs(candidate) -> pending[key] = existing.merge(candidate)
                                    else -> {
                                        var uniqueKey = "$key#conflict-${candidate.tempId}"
                                        var suffix = 1
                                        while (pending.containsKey(uniqueKey)) {
                                            uniqueKey = "$key#conflict-${candidate.tempId}-${suffix++}"
                                        }
                                        pending[uniqueKey] = candidate
                                    }
                                }
                            }
                            parsed.unresolvedSourceIds.forEach { sourceId ->
                                if (sourceId in sourceById) {
                                    failures[sourceId] = Failure(
                                        ImportFailureReason.SOURCE_NOT_COVERED,
                                        "模型无法确认该原文的题目归属",
                                        true,
                                    )
                                }
                            }
                        }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Throwable) {
                            // A response-level unexpected failure must not abort the whole run:
                            // sources stay visible in the report and earlier successes survive.
                            responseSourceIds.forEach { sourceId ->
                                failures.putIfAbsent(
                                    sourceId,
                                    Failure(
                                        ImportFailureReason.INTERNAL_PROCESSING_ERROR,
                                        "边界处理内部错误，原文已保留在报告中：${error.message ?: error::class.simpleName}",
                                        true,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }

        val candidates = pending.values.toList()
        candidates.forEachIndexed { index, boundary ->
            try {
                val direct = boundary.structured?.let {
                    validateAndConvert(
                        candidate = it,
                        sourceById = sourceById,
                        answerSectionIds = answerSectionIds,
                        allowedSourceIds = boundary.sourceIds.toSet(),
                    )
                }
                if (direct is CandidateValidation.Accepted) {
                    acceptCandidate(direct.value, accepted, records, ledger, sourceById, apiAttempted = true)
                    warnings += direct.warnings
                    return@forEachIndexed
                }

                val sourceIds = boundary.sourceIds.filter(sourceById::containsKey).distinct()
                val contextIds = (sourceIds + answerSectionIds).distinct()
                val request = SmartModelRequest(
                    stage = SmartRecognitionStage.STRUCTURE,
                    chunkId = "structure-${boundary.tempId.ifBlank { index.toString() }}",
                    sourceBlocks = contextIds.mapNotNull { sourceById[it] }.map(::wholeSlice),
                    candidateSourceIds = sourceIds,
                    answerSectionSourceIds = answerSectionIds.toList(),
                )
                onProgress(SmartPipelineProgress(SmartRecognitionStage.STRUCTURE, index + 1, candidates.size, requestCount))
                val response = call(request, apiKey) { requestCount++ }
                when (response) {
                    is ModelCall.Failed -> rejectBoundary(boundary, ImportFailureReason.API_REQUEST_FAILED, response.message, records, ledger, sourceById)
                    is ModelCall.Truncated -> rejectBoundary(
                        boundary,
                        ImportFailureReason.API_RESPONSE_TRUNCATED,
                        response.message,
                        records,
                        ledger,
                        sourceById,
                    )
                    is ModelCall.Null -> rejectBoundary(boundary, ImportFailureReason.API_RETURNED_NULL, "模型返回 null", records, ledger, sourceById)
                    is ModelCall.Success -> {
                        val parsed = SmartResponseParser.parse(response.raw)
                        val scopeError = if (parsed.error == null) {
                            responseScopeError(
                                parsed = parsed,
                                allowedSourceIds = request.sourceBlocks.map { it.sourceId }.toSet(),
                                requiredCandidateSourceIds = request.candidateSourceIds.toSet(),
                            )
                        } else null
                        if (parsed.error != null) {
                            rejectBoundary(boundary, ImportFailureReason.API_INVALID_JSON, parsed.error, records, ledger, sourceById)
                        } else if (scopeError != null) {
                            rejectBoundary(
                                boundary,
                                ImportFailureReason.SOURCE_NOT_COVERED,
                                scopeError,
                                records,
                                ledger,
                                sourceById,
                            )
                        } else if (parsed.questions.isEmpty()) {
                            rejectBoundary(boundary, ImportFailureReason.API_RETURNED_NULL, "模型没有返回结构化题目", records, ledger, sourceById)
                        } else {
                            var acceptedFromResponse = 0
                            parsed.questions.forEach { item ->
                                try {
                                    val structured = item.structured
                                    if (structured == null) {
                                        rejectBoundary(item, ImportFailureReason.API_INVALID_JSON, "题目缺少结构化字段", records, ledger, sourceById)
                                        return@forEach
                                    }
                                    when (
                                        val validated = validateAndConvert(
                                            candidate = structured,
                                            sourceById = sourceById,
                                            answerSectionIds = answerSectionIds,
                                            allowedSourceIds = request.sourceBlocks.map { it.sourceId }.toSet(),
                                        )
                                    ) {
                                        is CandidateValidation.Accepted -> {
                                            if (isDuplicate(validated.value, accepted)) {
                                                rejectBoundary(item, ImportFailureReason.DUPLICATE_QUESTION, "模型重复生成同一道题", records, ledger, sourceById)
                                            } else {
                                                acceptCandidate(validated.value, accepted, records, ledger, sourceById, apiAttempted = true)
                                                warnings += validated.warnings
                                                acceptedFromResponse++
                                            }
                                        }
                                        is CandidateValidation.Rejected -> rejectBoundary(
                                            item,
                                            validated.reason,
                                            validated.message,
                                            records,
                                            ledger,
                                            sourceById,
                                        )
                                    }
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (error: Throwable) {
                                    // A single unexpected failure must never discard questions that
                                    // were already accepted earlier in this same response.
                                    rejectBoundary(
                                        item,
                                        ImportFailureReason.INTERNAL_PROCESSING_ERROR,
                                        "结构化处理内部错误，原文已保留在报告中：${error.message ?: error::class.simpleName}",
                                        records,
                                        ledger,
                                        sourceById,
                                    )
                                }
                            }
                            if (acceptedFromResponse == 0 && parsed.questions.isEmpty()) {
                                rejectBoundary(boundary, ImportFailureReason.API_RETURNED_NULL, "模型没有返回可接受题目", records, ledger, sourceById)
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                // A single candidate's unexpected internal failure must never wipe out the
                // questions that were already accepted by earlier candidates.
                rejectBoundary(
                    boundary,
                    ImportFailureReason.INTERNAL_PROCESSING_ERROR,
                    "结构化处理内部错误，原文已保留在报告中：${error.message ?: error::class.simpleName}",
                    records,
                    ledger,
                    sourceById,
                )
            }
        }

        // ACCEPTED has higher source-ledger priority, so an unresolved part of the same source
        // must also get its own visible report row instead of being hidden by that status.
        failures.forEach { (sourceId, failure) ->
            if (ledger.status(sourceId) != null && records.none {
                    it.status == SourceLedgerStatus.REJECTED_QUESTION && sourceId in it.sourceIds
                }
            ) {
                records += rejectedRecord(
                    sourceById.getValue(sourceId),
                    failure.reason,
                    failure.message,
                    failure.apiAttempted,
                )
            }
        }
        reportPossiblePartialMultiQuestionSources(sources, accepted, records)

        // Unsupported local structures are visible even if the model did not classify them.
        sources.filter { it.sourceType == SourceBlockType.UNSUPPORTED && ledger.status(it.sourceId) == null }
            .forEach { source ->
                ledger.mark(listOf(source.sourceId), SourceLedgerStatus.UNSUPPORTED_CONTENT)
                records += ImportReportRecord(
                    sourceIds = listOf(source.sourceId),
                    originalQuestionNumber = null,
                    rawText = source.rawText,
                    status = SourceLedgerStatus.UNSUPPORTED_CONTENT,
                    reasonCode = if (source.unsupportedReason.orEmpty().contains("文本框")) {
                        ImportFailureReason.TEXTBOX_UNSUPPORTED
                    } else ImportFailureReason.EMBEDDED_OBJECT_UNSUPPORTED,
                    reasonMessage = source.unsupportedReason,
                    apiAttempted = true,
                )
            }

        // Nothing can disappear: failures and otherwise-uncovered source blocks become report rows.
        ledger.uncoveredIds().forEach { sourceId ->
            val source = sourceById.getValue(sourceId)
            val failure = failures[sourceId] ?: Failure(
                ImportFailureReason.SOURCE_NOT_COVERED,
                "模型响应没有为该非空原文分配状态",
                true,
            )
            ledger.mark(listOf(sourceId), SourceLedgerStatus.REJECTED_QUESTION)
            records += rejectedRecord(source, failure.reason, failure.message, failure.apiAttempted)
        }

        return result(
            fileName,
            startedAt,
            sources,
            ledger,
            accepted,
            records,
            warnings,
            requestCount,
            usedApi = requestCount > 0,
            answerSectionIds = answerSectionIds,
        )
    }

    private suspend fun call(
        request: SmartModelRequest,
        apiKey: String,
        onNetworkRequest: () -> Unit,
    ): ModelCall {
        val key = sha256(gson.toJson(request))
        cache.get(key)?.let { return if (it.trim().equals("null", true)) ModelCall.Null else ModelCall.Success(it) }
        onNetworkRequest()
        val completion = try {
            client.complete(apiKey, request)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return ModelCall.Failed(error.message ?: "网络请求失败")
        }
        val raw = completion.content.trim()
        if (completion.finishReason.equals("length", ignoreCase = true)) {
            return ModelCall.Truncated("模型输出达到长度上限，已尝试缩小原文范围")
        }
        if (!completion.finishReason.isNullOrBlank() && !completion.finishReason.equals("stop", ignoreCase = true)) {
            return ModelCall.Failed("模型响应未正常结束：${completion.finishReason}")
        }
        if (isLikelyTruncatedJson(raw)) {
            return ModelCall.Truncated("模型返回的 JSON 在结束前被截断，已尝试缩小原文范围")
        }
        if (raw.isEmpty() || raw.equals("null", true)) return ModelCall.Null
        // Only syntactically valid, request-scoped protocol responses are reusable. Invalid or
        // out-of-scope JSON must reach the provider again instead of becoming a cached failure.
        val parsed = SmartResponseParser.parse(raw)
        if (
            parsed.error == null &&
            responseScopeError(
                parsed = parsed,
                allowedSourceIds = request.sourceBlocks.map { it.sourceId }.toSet(),
                requiredCandidateSourceIds = request.candidateSourceIds.toSet(),
            ) == null &&
            responseCoverageComplete(parsed, request)
        ) {
            cache.put(key, raw)
        }
        return ModelCall.Success(raw)
    }

    private suspend fun callBoundaryAdaptive(
        request: SmartModelRequest,
        apiKey: String,
        depth: Int = 0,
        onNetworkRequest: () -> Unit,
    ): List<ScopedModelCall> {
        val response = call(request, apiKey, onNetworkRequest)
        if (response !is ModelCall.Truncated || depth >= MAX_ADAPTIVE_SPLIT_DEPTH) {
            return listOf(ScopedModelCall(request, response))
        }
        val children = splitBoundaryRequest(request)
            ?: return listOf(ScopedModelCall(request, response))
        val results = mutableListOf<ScopedModelCall>()
        children.forEach { child ->
            results += callBoundaryAdaptive(child, apiKey, depth + 1, onNetworkRequest)
        }
        return results
    }

    private fun splitBoundaryRequest(request: SmartModelRequest): List<SmartModelRequest>? {
        if (request.stage != SmartRecognitionStage.BOUNDARY) return null
        val slices = request.sourceBlocks
        if (slices.size > 1) {
            val midpoint = slices.size / 2
            val boundary = (1 until slices.size)
                .filter { index -> startsQuestionBoundary(slices[index]) }
                .minByOrNull { index -> kotlin.math.abs(index - midpoint) }
            if (boundary != null) {
                return listOf(
                    request.copy(chunkId = "${request.chunkId}-a", sourceBlocks = slices.take(boundary)),
                    request.copy(chunkId = "${request.chunkId}-b", sourceBlocks = slices.drop(boundary)),
                )
            }
            if (slices.size <= 2) return null
            // Share the pivot paragraph so a question crossing the approximate split remains whole
            // in at least one child whenever it spans only adjacent source blocks.
            return listOf(
                request.copy(chunkId = "${request.chunkId}-a", sourceBlocks = slices.take(midpoint + 1)),
                request.copy(chunkId = "${request.chunkId}-b", sourceBlocks = slices.drop(midpoint)),
            )
        }
        val slice = slices.singleOrNull() ?: return null
        if (slice.rawText.length < MIN_ADAPTIVE_SPLIT_CHARS) return null
        val midpoint = slice.rawText.length / 2
        val questionBoundary = QUESTION_MARKER.findAll(slice.rawText)
            .map { it.range.first }
            .filter { it in MIN_ADAPTIVE_SPLIT_CHARS / 4..slice.rawText.length - MIN_ADAPTIVE_SPLIT_CHARS / 4 }
            .minByOrNull { index -> kotlin.math.abs(index - midpoint) }
        val leftEnd: Int
        val rightStart: Int
        if (questionBoundary != null) {
            leftEnd = questionBoundary
            rightStart = questionBoundary
        } else {
            val overlap = (slice.rawText.length / 10).coerceIn(100, 400)
            leftEnd = (midpoint + overlap).coerceAtMost(slice.rawText.length - 1)
            rightStart = (midpoint - overlap).coerceAtLeast(1)
        }
        val left = slice.copy(
            rawText = slice.rawText.substring(0, leftEnd),
            charEnd = slice.charStart + leftEnd,
        )
        val right = slice.copy(
            rawText = slice.rawText.substring(rightStart),
            charStart = slice.charStart + rightStart,
        )
        return listOf(
            request.copy(chunkId = "${request.chunkId}-a", sourceBlocks = listOf(left)),
            request.copy(chunkId = "${request.chunkId}-b", sourceBlocks = listOf(right)),
        )
    }

    private fun startsQuestionBoundary(slice: SourceBlockSlice): Boolean {
        val text = slice.rawText.trimStart()
        if (QUESTION_PREFIX.containsMatchIn(text) || TAGGED_QUESTION_HEADER.containsMatchIn(text)) return true
        val numbering = slice.numbering ?: return false
        if (numbering.level != 0) return false
        return numbering.displayText?.trim()?.let { display ->
            Regex("""^[（(]?\s*\d{1,6}""").containsMatchIn(display)
        } == true
    }

    private fun classifiedSourceIds(parsed: ParsedSmartResponse): Set<String> = buildSet {
        addAll(parsed.questions.flatMap { it.sourceIds })
        addAll(parsed.answerSectionSourceIds)
        addAll(parsed.nonQuestionSourceIds)
        addAll(parsed.unsupportedSourceIds)
        addAll(parsed.unresolvedSourceIds)
    }

    private fun responseCoverageComplete(parsed: ParsedSmartResponse, request: SmartModelRequest): Boolean {
        val required = if (request.stage == SmartRecognitionStage.BOUNDARY) {
            request.sourceBlocks.map { it.sourceId }.toSet()
        } else {
            request.candidateSourceIds.toSet()
        }
        return required.isNotEmpty() && classifiedSourceIds(parsed).containsAll(required)
    }

    private fun isLikelyTruncatedJson(raw: String): Boolean {
        val value = raw.trim()
        if (value.firstOrNull() !in setOf('{', '[')) return false
        var braces = 0
        var brackets = 0
        var inString = false
        var escaped = false
        value.forEach { char ->
            if (inString) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> inString = false
                }
            } else {
                when (char) {
                    '"' -> inString = true
                    '{' -> braces++
                    '}' -> braces--
                    '[' -> brackets++
                    ']' -> brackets--
                }
            }
        }
        return inString || escaped || braces > 0 || brackets > 0
    }

    private fun validateAndConvert(
        candidate: StructuredCandidate,
        sourceById: Map<String, ImportSourceBlock>,
        answerSectionIds: Set<String>,
        allowedSourceIds: Set<String>,
    ): CandidateValidation {
        val provenance = candidate.provenance
        val claimed = provenance.sourceIds.distinct()
        if (claimed.isEmpty() || claimed.any { it !in sourceById || it !in allowedSourceIds }) {
            return CandidateValidation.Rejected(
                ImportFailureReason.SOURCE_NOT_COVERED,
                "题目引用了不存在或未包含在本次请求中的 sourceId",
            )
        }
        val fieldSources = provenanceFieldSources(provenance)
        val outsideProvenance = fieldSources.flatMap { (field, ids) ->
            ids.filterNot(claimed::contains).map { sourceId -> "$field=$sourceId" }
        }
        if (outsideProvenance.isNotEmpty()) {
            return CandidateValidation.Rejected(
                ImportFailureReason.SOURCE_NOT_COVERED,
                "字段来源必须属于 provenance.sourceIds：${outsideProvenance.distinct().joinToString()}",
            )
        }
        if (candidate.question.isBlank() || provenance.questionSource.isEmpty()) {
            return CandidateValidation.Rejected(ImportFailureReason.MISSING_STEM, "题干或 questionSource 为空")
        }
        if (!traceable(candidate.question, provenance.questionSource, sourceById, stripQuestionNumber = true)) {
            return CandidateValidation.Rejected(ImportFailureReason.API_HALLUCINATED_CONTENT, "题干无法在 questionSource 原文中逐字追溯")
        }
        val type = QuestionType.fromRawStrict(candidate.type)
            ?: return CandidateValidation.Rejected(ImportFailureReason.INVALID_QUESTION_TYPE, "模型返回了不支持的题型")
        if (candidate.options.isEmpty()) {
            return CandidateValidation.Rejected(ImportFailureReason.MISSING_OPTIONS, "模型没有返回选项")
        }
        val optionKeys = candidate.options.map { it.key.uppercase() }
        if (optionKeys.size != optionKeys.distinct().size) {
            return CandidateValidation.Rejected(ImportFailureReason.DUPLICATE_OPTION, "模型返回重复选项标号")
        }
        if (optionKeys.any { !it.matches(Regex("[A-H]")) }) {
            return CandidateValidation.Rejected(ImportFailureReason.MISSING_OPTIONS, "选项标号必须是 A-H")
        }
        when (type) {
            QuestionType.SINGLE, QuestionType.MULTIPLE -> if (candidate.options.size < 2) {
                return CandidateValidation.Rejected(ImportFailureReason.MISSING_OPTIONS, "选择题至少需要两个明确选项")
            }
            QuestionType.TRUE_FALSE -> if (optionKeys.toSet() != setOf("A", "B") || candidate.options.size != 2) {
                return CandidateValidation.Rejected(ImportFailureReason.MISSING_OPTIONS, "判断题必须只有 A/B 两个选项")
            }
        }
        val visibleOptionKeys = claimed.flatMap { sourceId ->
            val source = sourceById.getValue(sourceId)
            buildList {
                OPTION_MARKER.findAll(source.rawText).forEach { add(it.groupValues[1].uppercase()) }
                source.numbering?.displayText?.let { numbering ->
                    OPTION_NUMBERING.matchEntire(numbering.trim())?.groupValues?.get(1)?.uppercase()?.let(::add)
                }
            }
        }.toSet()
        if (!optionKeys.containsAll(visibleOptionKeys)) {
            return CandidateValidation.Rejected(
                ImportFailureReason.SOURCE_NOT_COVERED,
                "模型遗漏了原文中的选项：${(visibleOptionKeys - optionKeys.toSet()).sorted().joinToString()}",
            )
        }
        for (option in candidate.options) {
            val sources = provenance.optionSources[option.key.uppercase()].orEmpty()
            val hasSourceImage = sources.flatMap { sourceById[it]?.images.orEmpty() }.isNotEmpty()
            if (sources.isEmpty() || (option.text.isBlank() && !hasSourceImage)) {
                return CandidateValidation.Rejected(ImportFailureReason.MISSING_OPTIONS, "选项 ${option.key} 缺少原文来源")
            }
            if (option.text.isNotBlank() && !traceable(option.text, sources, sourceById, stripOptionMarker = true)) {
                return CandidateValidation.Rejected(ImportFailureReason.API_HALLUCINATED_CONTENT, "选项 ${option.key} 无法在原文中逐字追溯")
            }
        }
        val answers = AnswerNormalizer.normalize(candidate.answer.joinToString(","))
        if (answers.isEmpty() || provenance.answerSource.isEmpty()) {
            return CandidateValidation.Rejected(ImportFailureReason.MISSING_ANSWER, "答案或 answerSource 为空，禁止猜答案")
        }
        if (!answers.all { it in optionKeys }) {
            return CandidateValidation.Rejected(ImportFailureReason.ANSWER_NOT_IN_OPTIONS, "答案不在选项集合中")
        }
        if (!answerTraceable(
                answers,
                provenance.answerSource,
                sourceById,
                candidate.originalQuestionNumber,
                provenance.answerSource.any(answerSectionIds::contains),
            )
        ) {
            return CandidateValidation.Rejected(ImportFailureReason.API_HALLUCINATED_CONTENT, "答案无法在答案原文中追溯")
        }
        when (type) {
            QuestionType.SINGLE, QuestionType.TRUE_FALSE -> if (answers.size != 1) {
                return CandidateValidation.Rejected(ImportFailureReason.INVALID_QUESTION_TYPE, "单选或判断题答案数量不为 1")
            }
            QuestionType.MULTIPLE -> if (answers.size < 2) {
                return CandidateValidation.Rejected(ImportFailureReason.INVALID_QUESTION_TYPE, "多选题答案不足 2 个")
            }
        }
        candidate.explanation?.takeIf(String::isNotBlank)?.let { explanation ->
            if (provenance.explanationSource.isEmpty() || !traceable(explanation, provenance.explanationSource, sourceById)) {
                return CandidateValidation.Rejected(ImportFailureReason.API_HALLUCINATED_CONTENT, "解析无法在原文中追溯")
            }
        }
        candidate.knowledge?.takeIf(String::isNotBlank)?.let { knowledge ->
            if (provenance.knowledgeSource.isEmpty() || !traceable(knowledge, provenance.knowledgeSource, sourceById)) {
                return CandidateValidation.Rejected(ImportFailureReason.API_HALLUCINATED_CONTENT, "知识点无法在原文中追溯")
            }
        }

        val stemImages = imagesOwnedBy(provenance.questionSource, sourceById, IMAGE_OWNER_STEM)
        val options = candidate.options.map { option ->
            val refs = imagesOwnedBy(
                provenance.optionSources[option.key.uppercase()].orEmpty(),
                sourceById,
                imageOptionOwner(option.key),
            )
            QuestionOption(
                key = option.key.uppercase(),
                text = option.text.trim(),
                imageUri = refs.mapNotNull { it.localPath }.firstOrNull(),
                imageUris = refs.mapNotNull { it.localPath }.distinct(),
            )
        }
        val warnings = buildList {
            val allRefs = stemImages + provenance.optionSources.values.flatten().flatMap { sourceById[it]?.images.orEmpty() }
            if (allRefs.isNotEmpty()) add("图片内容未发送给文字模型；图片决定答案时需要人工确认")
            if (allRefs.any { !it.supportedForDisplay }) add("包含 EMF、WMF 或未知图片格式，无法直接显示")
        }
        val question = QuizQuestion(
            originalId = candidate.originalQuestionNumber,
            type = type,
            question = candidate.question.trim(),
            options = options,
            answer = answers,
            explanation = candidate.explanation?.trim()?.takeIf(String::isNotBlank),
            knowledge = candidate.knowledge?.trim()?.takeIf(String::isNotBlank),
            imageUri = stemImages.mapNotNull { it.localPath }.firstOrNull(),
            imageUris = stemImages.mapNotNull { it.localPath }.distinct(),
        )
        return CandidateValidation.Accepted(RecognizedQuestion(question, provenance, candidate.originalQuestionNumber), warnings)
    }

    /**
     * Treat model-provided source ids as untrusted references. A response may only name blocks
     * that were serialized into that exact request, even when the id happens to exist elsewhere
     * in the same document. Provenance fields are additionally constrained to the candidate's
     * declared provenance set so a field cannot borrow a convenient answer or stem source.
     */
    private fun responseScopeError(
        parsed: ParsedSmartResponse,
        allowedSourceIds: Set<String>,
        requiredCandidateSourceIds: Set<String> = emptySet(),
    ): String? {
        val issues = mutableListOf<String>()

        fun check(label: String, sourceIds: List<String>, requireNonEmpty: Boolean = false) {
            if (requireNonEmpty && sourceIds.isEmpty()) issues += "$label 为空"
            val outside = sourceIds.distinct().filterNot(allowedSourceIds::contains)
            if (outside.isNotEmpty()) {
                issues += "$label 引用了本次请求之外的 sourceId：${outside.joinToString()}"
            }
        }

        check("answerSections.sourceIds", parsed.answerSectionSourceIds)
        check("nonQuestionSourceIds", parsed.nonQuestionSourceIds)
        check("unsupportedSourceIds", parsed.unsupportedSourceIds)
        check("unresolvedSourceIds", parsed.unresolvedSourceIds)
        parsed.questions.forEachIndexed { index, candidate ->
            val prefix = "questions[$index]"
            check("$prefix.sourceIds", candidate.sourceIds, requireNonEmpty = true)
            if (
                requiredCandidateSourceIds.isNotEmpty() &&
                candidate.sourceIds.none(requiredCandidateSourceIds::contains)
            ) {
                issues += "$prefix.sourceIds 未引用当前待结构化题目的原文"
            }
            candidate.structured?.let { structured ->
                val provenance = structured.provenance
                check("$prefix.provenance.sourceIds", provenance.sourceIds, requireNonEmpty = true)
                if (
                    requiredCandidateSourceIds.isNotEmpty() &&
                    provenance.questionSource.none(requiredCandidateSourceIds::contains)
                ) {
                    issues += "$prefix.provenance.questionSource 未引用当前待结构化题目的原文"
                }
                provenanceFieldSources(provenance).forEach { (field, ids) ->
                    check("$prefix.provenance.$field", ids)
                }
                val outsideProvenance = provenanceFieldSources(provenance).flatMap { (field, ids) ->
                    ids.filterNot(provenance.sourceIds::contains).map { sourceId -> "$field=$sourceId" }
                }
                if (outsideProvenance.isNotEmpty()) {
                    issues += "$prefix 字段来源不属于 provenance.sourceIds：${outsideProvenance.distinct().joinToString()}"
                }
            }
        }
        return issues.distinct().takeIf { it.isNotEmpty() }?.joinToString("；")
    }

    private fun provenanceFieldSources(provenance: QuestionProvenance): List<Pair<String, List<String>>> = buildList {
        add("questionSource" to provenance.questionSource)
        provenance.optionSources.forEach { (key, ids) -> add("optionSources.$key" to ids) }
        add("answerSource" to provenance.answerSource)
        add("explanationSource" to provenance.explanationSource)
        add("knowledgeSource" to provenance.knowledgeSource)
    }

    private fun acceptCandidate(
        value: RecognizedQuestion,
        accepted: MutableList<RecognizedQuestion>,
        records: MutableList<ImportReportRecord>,
        ledger: SourceLedger,
        sourceById: Map<String, ImportSourceBlock>,
        apiAttempted: Boolean,
    ) {
        if (isDuplicate(value, accepted)) {
            rejectBoundary(
                BoundaryCandidate("duplicate", value.provenance.sourceIds, value.originalQuestionNumber, null),
                ImportFailureReason.DUPLICATE_QUESTION,
                "模型重复生成同一道题",
                records,
                ledger,
                sourceById,
            )
            return
        }
        val index = accepted.size
        accepted += value
        ledger.mark(value.provenance.sourceIds, SourceLedgerStatus.ACCEPTED_QUESTION)
        records += ImportReportRecord(
            sourceIds = value.provenance.sourceIds,
            originalQuestionNumber = value.originalQuestionNumber,
            rawText = value.provenance.sourceIds.mapNotNull { sourceById[it]?.rawText }.joinToString("\n"),
            status = SourceLedgerStatus.ACCEPTED_QUESTION,
            createdQuestionIndexes = listOf(index),
            apiAttempted = apiAttempted,
        )
    }

    private fun imagesOwnedBy(
        sourceIds: List<String>,
        sourceById: Map<String, ImportSourceBlock>,
        expectedOwner: String,
    ): List<SourceImageRef> = sourceIds.flatMap { sourceId ->
        val source = sourceById[sourceId] ?: return@flatMap emptyList()
        source.images.filter { image ->
            val owner = sourceImageOwner(source.rawText, image.charOffset)
            owner == null || owner == expectedOwner
        }
    }.distinctBy { listOf(it.mediaId, it.relationshipId, it.localPath, it.charOffset) }

    private fun rejectBoundary(
        boundary: BoundaryCandidate,
        reason: ImportFailureReason,
        message: String,
        records: MutableList<ImportReportRecord>,
        ledger: SourceLedger,
        sourceById: Map<String, ImportSourceBlock>,
    ) {
        val ids = boundary.sourceIds.filter(sourceById::containsKey).distinct()
        if (ids.isEmpty()) return
        ledger.mark(ids, SourceLedgerStatus.REJECTED_QUESTION)
        records += ImportReportRecord(
            sourceIds = ids,
            originalQuestionNumber = boundary.originalQuestionNumber,
            rawText = ids.mapNotNull { sourceById[it]?.rawText }.joinToString("\n"),
            status = SourceLedgerStatus.REJECTED_QUESTION,
            reasonCode = reason,
            reasonMessage = message,
            apiAttempted = true,
        )
    }

    private fun result(
        fileName: String,
        startedAt: Long,
        sources: List<ImportSourceBlock>,
        ledger: SourceLedger,
        accepted: List<RecognizedQuestion>,
        records: List<ImportReportRecord>,
        warnings: List<String>,
        requestCount: Int,
        usedApi: Boolean,
        answerSectionIds: Collection<String> = emptyList(),
    ): ImportRecognitionResult {
        val normalizedRecords = records.distinctBy { record ->
            listOf(
                record.status.name,
                record.sourceIds.sorted().joinToString("|"),
                record.originalQuestionNumber?.toString().orEmpty(),
                record.reasonCode?.name.orEmpty(),
                record.createdQuestionIndexes.joinToString("|"),
            ).joinToString("#")
        }
        val acceptedRows = normalizedRecords.count { it.status == SourceLedgerStatus.ACCEPTED_QUESTION }
        val rejectedRows = normalizedRecords.count { it.status == SourceLedgerStatus.REJECTED_QUESTION }
        val counts = ledger.counts()
        return ImportRecognitionResult(
            accepted,
            ImportReport(
                reportId = reportId(),
                fileName = fileName,
                importMode = ImportMode.SMART,
                startedAt = startedAt,
                finishedAt = clock(),
                totalSourceBlocks = sources.size,
                candidateQuestionCount = acceptedRows + rejectedRows,
                acceptedQuestionCount = acceptedRows,
                rejectedQuestionCount = rejectedRows,
                nonQuestionCount = counts[SourceLedgerStatus.NON_QUESTION_CONTENT] ?: 0,
                unsupportedCount = counts[SourceLedgerStatus.UNSUPPORTED_CONTENT] ?: 0,
                imageCount = sources.sumOf { it.images.size },
                tableCount = sources.mapNotNull { it.table?.tableSourceId }.distinct().size,
                usedApi = usedApi,
                apiRequestCount = requestCount,
                warnings = warnings.distinct(),
                records = normalizedRecords,
                ledgerComplete = ledger.isComplete(),
                answerSectionSourceIds = answerSectionIds.distinct(),
            ),
        )
    }

    private fun mergeRetryResult(
        allSources: List<ImportSourceBlock>,
        previous: ImportRecognitionResult,
        retried: ImportRecognitionResult,
        requestedIds: Set<String>,
    ): ImportRecognitionResult {
        val sourceById = allSources.associateBy { it.sourceId }
        val retainedRecords = previous.report.records.mapNotNull { record ->
            if (!record.status.isRetryableFailure()) return@mapNotNull record
            val remainingIds = record.sourceIds.filterNot(requestedIds::contains)
            when {
                remainingIds.size == record.sourceIds.size -> record
                remainingIds.isEmpty() -> null
                else -> record.copy(
                    sourceIds = remainingIds,
                    rawText = remainingIds.mapNotNull { sourceById[it]?.rawText }.joinToString("\n"),
                )
            }
        }
        val keptRetryQuestions = mutableListOf<RecognizedQuestion>()
        val retryIndexMap = mutableMapOf<Int, Int>()
        val duplicateRetryIndexes = mutableSetOf<Int>()
        retried.questions.forEachIndexed { index, question ->
            if (isDuplicate(question, previous.questions + keptRetryQuestions)) {
                duplicateRetryIndexes += index
            } else {
                retryIndexMap[index] = previous.questions.size + keptRetryQuestions.size
                keptRetryQuestions += question
            }
        }
        val retryRecords = retried.report.records.mapNotNull { record ->
            if (record.status == SourceLedgerStatus.ACCEPTED_QUESTION) {
                val remapped = record.createdQuestionIndexes.mapNotNull(retryIndexMap::get)
                if (remapped.isEmpty() && record.createdQuestionIndexes.any(duplicateRetryIndexes::contains)) {
                    val resolvedByNovelQuestion = keptRetryQuestions.any { question ->
                        question.provenance.sourceIds.any(record.sourceIds::contains)
                    }
                    val alreadyHasVisibleFailure = retried.report.records.any { failure ->
                        failure.status.isRetryableFailure() &&
                            failure.sourceIds.any(record.sourceIds::contains)
                    }
                    when {
                        // Re-sending a shared source normally returns the already accepted item
                        // together with the newly recovered one. That expected duplicate is not
                        // a failure once this retry produced a novel question for the same scope.
                        resolvedByNovelQuestion || alreadyHasVisibleFailure -> null
                        else -> record.copy(
                            status = SourceLedgerStatus.REJECTED_QUESTION,
                            reasonCode = ImportFailureReason.DUPLICATE_QUESTION,
                            reasonMessage = "片段重试只返回了已成功识别的重复题目",
                            createdQuestionIndexes = emptyList(),
                        )
                    }
                } else record.copy(createdQuestionIndexes = remapped)
            } else record
        }.filterNot { record ->
            record.status == SourceLedgerStatus.NON_QUESTION_CONTENT && record.sourceIds.none(requestedIds::contains)
        }
        val records = retainedRecords + retryRecords
        val questions = previous.questions + keptRetryQuestions
        val ledger = SourceLedger(allSources)
        records.forEach { record -> ledger.mark(record.sourceIds, record.status) }
        val ledgerCounts = ledger.counts()
        val acceptedRows = records.count { it.status == SourceLedgerStatus.ACCEPTED_QUESTION }
        val rejectedRows = records.count { it.status == SourceLedgerStatus.REJECTED_QUESTION }
        val report = previous.report.copy(
            finishedAt = clock(),
            totalSourceBlocks = allSources.size,
            candidateQuestionCount = acceptedRows + rejectedRows,
            acceptedQuestionCount = acceptedRows,
            rejectedQuestionCount = rejectedRows,
            nonQuestionCount = ledgerCounts[SourceLedgerStatus.NON_QUESTION_CONTENT] ?: 0,
            unsupportedCount = ledgerCounts[SourceLedgerStatus.UNSUPPORTED_CONTENT] ?: 0,
            imageCount = allSources.sumOf { it.images.size },
            tableCount = allSources.mapNotNull { it.table?.tableSourceId }.distinct().size,
            usedApi = previous.report.usedApi || retried.report.usedApi,
            apiRequestCount = previous.report.apiRequestCount + retried.report.apiRequestCount,
            warnings = (previous.report.warnings + retried.report.warnings).distinct(),
            records = records,
            ledgerComplete = ledger.isComplete(),
            answerSectionSourceIds = (previous.report.answerSectionSourceIds + retried.report.answerSectionSourceIds).distinct(),
        )
        return ImportRecognitionResult(questions = questions, report = report)
    }

    private fun rejectedRecord(
        source: ImportSourceBlock,
        reason: ImportFailureReason,
        message: String,
        apiAttempted: Boolean,
    ) = ImportReportRecord(
        sourceIds = listOf(source.sourceId),
        originalQuestionNumber = null,
        rawText = source.rawText,
        status = SourceLedgerStatus.REJECTED_QUESTION,
        reasonCode = reason,
        reasonMessage = message,
        apiAttempted = apiAttempted,
    )

    private fun traceable(
        value: String,
        sourceIds: List<String>,
        sourceById: Map<String, ImportSourceBlock>,
        stripQuestionNumber: Boolean = false,
        stripOptionMarker: Boolean = false,
    ): Boolean {
        var normalizedValue = normalize(value)
        if (stripQuestionNumber) normalizedValue = normalizedValue.replace(QUESTION_PREFIX, "")
        if (stripOptionMarker) normalizedValue = normalizedValue.replace(OPTION_PREFIX, "")
        val source = normalize(sourceIds.mapNotNull { sourceById[it]?.rawText }.joinToString("\n"))
        return normalizedValue.isNotBlank() && source.contains(normalizedValue)
    }

    private fun answerTraceable(
        answers: List<String>,
        sourceIds: List<String>,
        sourceById: Map<String, ImportSourceBlock>,
        originalQuestionNumber: Int?,
        concentratedAnswerSection: Boolean,
    ): Boolean {
        val text = sourceIds.mapNotNull { sourceById[it]?.rawText }.joinToString("\n")
        val values = ANSWER_VALUE.findAll(text).flatMap { match ->
            AnswerNormalizer.normalize(match.groupValues[1]).asSequence()
        }.toSet()
        if (!concentratedAnswerSection && answers.all { it in values }) return true

        // Concentrated answer sections commonly use "答案汇总：4.A 5.B". The original
        // question number is mandatory so another question's answer cannot be borrowed.
        val number = originalQuestionNumber ?: return false
        val keyedValues = Regex(
            """(?<!\d)${Regex.escape(number.toString())}\s*(?:题)?(?:\s*[.．、:：)）=\-]\s*|\s+)(?:答案\s*[:：]?\s*)?([A-Ha-h,，、\s]+|对|错|正确|错误|√|×)""",
        ).findAll(text).flatMap { match ->
            AnswerNormalizer.normalize(match.groupValues[1]).asSequence()
        }.toSet()
        return answers.all { it in keyedValues }
    }

    private fun reportPossiblePartialMultiQuestionSources(
        sources: List<ImportSourceBlock>,
        accepted: List<RecognizedQuestion>,
        records: MutableList<ImportReportRecord>,
    ) {
        sources.forEach { source ->
            if (source.sourceType == SourceBlockType.UNSUPPORTED || "答案汇总" in source.rawText) return@forEach
            val visibleCandidates = QUESTION_MARKER.findAll(source.rawText).count()
            if (visibleCandidates < 2) return@forEach
            val accounted = accepted.count { source.sourceId in it.provenance.questionSource } +
                records.count { it.status == SourceLedgerStatus.REJECTED_QUESTION && source.sourceId in it.sourceIds }
            if (accounted >= visibleCandidates) return@forEach
            records += ImportReportRecord(
                sourceIds = listOf(source.sourceId),
                originalQuestionNumber = null,
                rawText = source.rawText,
                status = SourceLedgerStatus.REJECTED_QUESTION,
                reasonCode = ImportFailureReason.MULTIPLE_QUESTIONS_MERGED,
                reasonMessage = "同一原文块检测到 $visibleCandidates 个题号，但只确认了 $accounted 个候选题；请人工核对",
                apiAttempted = true,
            )
        }
    }

    private fun wholeSlice(source: ImportSourceBlock) = SourceBlockSlice(
        sourceId = source.sourceId,
        sourceOrder = source.sourceOrder,
        sourceType = source.sourceType.wireValue,
        rawText = source.rawText,
        charStart = 0,
        charEnd = source.rawText.length,
        numbering = source.numbering,
        table = source.table,
        images = source.images.mapIndexed { index, image -> image.mediaId ?: image.relationshipId ?: "unresolved-$index" },
    )

    private fun isDuplicate(value: RecognizedQuestion, existing: List<RecognizedQuestion>): Boolean {
        val key = QuestionDuplicateKey.canonical(value.question)
        return existing.any { QuestionDuplicateKey.canonical(it.question) == key }
    }

    private fun normalize(value: String): String = QuestionDuplicateKey.normalize(value)

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

    private sealed interface ModelCall {
        data class Success(val raw: String) : ModelCall
        data class Failed(val message: String) : ModelCall
        data class Truncated(val message: String) : ModelCall
        data object Null : ModelCall
    }

    private data class ScopedModelCall(val request: SmartModelRequest, val call: ModelCall)

    private sealed interface CandidateValidation {
        data class Accepted(val value: RecognizedQuestion, val warnings: List<String>) : CandidateValidation
        data class Rejected(val reason: ImportFailureReason, val message: String) : CandidateValidation
    }

    private data class Failure(val reason: ImportFailureReason, val message: String, val apiAttempted: Boolean)

    companion object {
        private const val MAX_ADAPTIVE_SPLIT_DEPTH = 6
        private const val MIN_ADAPTIVE_SPLIT_CHARS = 800
        private val QUESTION_PREFIX = Regex("""^(?:(?:第)?\d{1,6}(?:题|[.、)）])|[（(]\d{1,6}[）)])""")
        private val QUESTION_MARKER = Regex(
            """(?:^|[\r\n\s])(?:(?:第\s*)?\d{1,6}\s*题|[（(]\s*\d{1,6}\s*[）)]|\d{1,6}\s*[.．、)）])""",
        )
        private val TAGGED_QUESTION_HEADER = Regex("""^\[(判断题|单选题|多选题)](?:\[[^]\r\n]*])*\s*$""")
        private val OPTION_PREFIX = Regex("""^[A-Ha-h][.．、:：)）]""")
        private val OPTION_MARKER = Regex("""(?:^|[\r\n\s])([A-Ha-h])\s*[.．、:：)）]""")
        private val OPTION_NUMBERING = Regex("""^([A-Ha-h])(?:[.．、:：)）]|$)""")
        private val ANSWER_VALUE = Regex("""(?:答案|正确答案|参考答案|标准答案)\s*[:：]?\s*([A-Ha-h,，、\s]+|对|错|正确|错误|√|×)""")
    }
}

private fun SourceLedgerStatus.isRetryableFailure(): Boolean =
    this == SourceLedgerStatus.REJECTED_QUESTION || this == SourceLedgerStatus.UNSUPPORTED_CONTENT

class SourceBlockChunker(
    private val maxEstimatedTokens: Int,
    private val overlapBlocks: Int,
    private val maxSlicesPerChunk: Int = 48,
) {
    fun chunk(sources: List<ImportSourceBlock>): List<SourceChunk> {
        if (sources.isEmpty()) return emptyList()
        val slices = sources.flatMap(::slice)
        val chunks = mutableListOf<SourceChunk>()
        var cursor = 0
        while (cursor < slices.size) {
            val selected = mutableListOf<SourceBlockSlice>()
            var tokens = 0
            var index = cursor
            while (index < slices.size && selected.size < maxSlicesPerChunk.coerceAtLeast(1)) {
                val cost = estimateTokens(slices[index])
                if (selected.isNotEmpty() && tokens + cost > maxEstimatedTokens) break
                selected += slices[index]
                tokens += cost
                index++
            }
            if (selected.isEmpty()) {
                selected += slices[index]
                index++
            }
            chunks += SourceChunk("chunk-${chunks.size}", selected)
            if (index >= slices.size) break
            cursor = (index - overlapBlocks.coerceAtLeast(0)).coerceAtLeast(cursor + 1)
        }
        return chunks
    }

    private fun slice(source: ImportSourceBlock): List<SourceBlockSlice> {
        val maxChars = (maxEstimatedTokens * 2).coerceAtLeast(500)
        if (source.rawText.length <= maxChars) return listOf(toSlice(source, 0, source.rawText.length))
        val ranges = mutableListOf<IntRange>()
        var start = 0
        while (start < source.rawText.length) {
            val hardEnd = (start + maxChars).coerceAtMost(source.rawText.length)
            val searchStart = (start + maxChars / 2).coerceAtMost(hardEnd)
            val softEnd = source.rawText.lastIndexOfAny(charArrayOf('\n', '。', '；', ';'), hardEnd - 1)
                .takeIf { it >= searchStart }
                ?.plus(1)
                ?: hardEnd
            ranges += start until softEnd
            start = softEnd
        }
        return ranges.map { range -> toSlice(source, range.first, range.last + 1) }
    }

    private fun toSlice(source: ImportSourceBlock, start: Int, end: Int) = SourceBlockSlice(
        sourceId = source.sourceId,
        sourceOrder = source.sourceOrder,
        sourceType = source.sourceType.wireValue,
        rawText = source.rawText.substring(start, end),
        charStart = start,
        charEnd = end,
        numbering = source.numbering,
        table = source.table,
        images = source.images.mapIndexed { index, image -> image.mediaId ?: image.relationshipId ?: "unresolved-$index" },
    )

    private fun estimateTokens(slice: SourceBlockSlice): Int =
        (slice.rawText.length + 1) / 2 + 80 + slice.images.size * 8
}

data class SourceChunk(val id: String, val slices: List<SourceBlockSlice>) {
    val sourceIds: List<String> = slices.map { it.sourceId }.distinct()
}

private data class BoundaryCandidate(
    val tempId: String,
    val sourceIds: List<String>,
    val originalQuestionNumber: Int?,
    val structured: StructuredCandidate?,
) {
    fun identityKey(): String {
        val sources = sourceIds.sorted().joinToString("|")
        val discriminator = originalQuestionNumber?.let { "number:$it" }
            ?: structured?.question?.takeIf(String::isNotBlank)?.let { question ->
                "stem:" + Normalizer.normalize(question, Normalizer.Form.NFKC).replace(Regex("\\s+"), "")
            }
            ?: "temp:$tempId"
        return "$sources#$discriminator"
    }

    /**
     * Returns true only when both sides unambiguously denote the same question boundary.
     * When both sides carry a structured question they must be canonically equal; a boundary
     * mention without a stem merges with anything that shares the same boundary key.
     * Distinct stems colliding on one key must never merge, or one question silently disappears.
     */
    fun sameQuestionAs(other: BoundaryCandidate): Boolean {
        val mine = structured
        val theirs = other.structured
        if (mine != null && theirs != null) {
            val mineKey = QuestionDuplicateKey.canonical(mine.question, mine.options.map { it.key to it.text })
            val theirsKey = QuestionDuplicateKey.canonical(theirs.question, theirs.options.map { it.key to it.text })
            return mineKey == theirsKey
        }
        return true
    }

    fun merge(other: BoundaryCandidate): BoundaryCandidate = copy(
        sourceIds = (sourceIds + other.sourceIds).distinct(),
        originalQuestionNumber = originalQuestionNumber ?: other.originalQuestionNumber,
        structured = structured ?: other.structured,
    )
}

private data class StructuredCandidate(
    val type: String,
    val question: String,
    val options: List<StructuredOption>,
    val answer: List<String>,
    val explanation: String?,
    val knowledge: String?,
    val originalQuestionNumber: Int?,
    val provenance: QuestionProvenance,
)

private data class StructuredOption(val key: String, val text: String)

private data class ParsedSmartResponse(
    val questions: List<BoundaryCandidate> = emptyList(),
    val answerSectionSourceIds: List<String> = emptyList(),
    val nonQuestionSourceIds: List<String> = emptyList(),
    val unsupportedSourceIds: List<String> = emptyList(),
    val unresolvedSourceIds: List<String> = emptyList(),
    val error: String? = null,
)

private object SmartResponseParser {
    fun parse(raw: String): ParsedSmartResponse {
        val root = runCatching { JsonParser.parseString(stripFence(raw)) }.getOrElse {
            return ParsedSmartResponse(error = "非法 JSON：${it.message}")
        }
        if (!root.isJsonObject) return ParsedSmartResponse(error = "模型响应根节点必须是 JSON 对象")
        val obj = root.asJsonObject
        val questionsElement = obj.get("questions")
        if (questionsElement != null && !questionsElement.isJsonArray) {
            return ParsedSmartResponse(error = "questions 必须是数组")
        }
        val questions = questionsElement?.asJsonArray?.mapIndexedNotNull { index, element ->
            parseQuestion(element, index)
        }.orEmpty()
        return ParsedSmartResponse(
            questions = questions,
            answerSectionSourceIds = parseAnswerSections(obj.get("answerSections")),
            nonQuestionSourceIds = stringList(obj.get("nonQuestionSourceIds")),
            unsupportedSourceIds = stringList(obj.get("unsupportedSourceIds")),
            unresolvedSourceIds = stringList(obj.get("unresolvedSourceIds")),
        )
    }

    private fun parseQuestion(element: JsonElement, index: Int): BoundaryCandidate? {
        if (!element.isJsonObject) return null
        val obj = element.asJsonObject
        val sourceIds = stringList(obj.get("sourceIds"))
        val number = obj.intOrNull("originalQuestionNumber")
        val structured = if (obj.has("type") || obj.has("question")) parseStructured(obj, sourceIds, number) else null
        return BoundaryCandidate(obj.stringOrNull("tempId") ?: "q$index", sourceIds, number, structured)
    }

    private fun parseStructured(obj: JsonObject, fallbackSources: List<String>, number: Int?): StructuredCandidate? {
        val type = obj.stringOrNull("type") ?: return null
        val question = obj.stringOrNull("question") ?: return null
        val options = obj.get("options")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { optionElement ->
            if (!optionElement.isJsonObject) return@mapNotNull null
            val option = optionElement.asJsonObject
            val key = option.stringOrNull("key") ?: return@mapNotNull null
            StructuredOption(key, option.stringOrNull("text").orEmpty())
        }.orEmpty()
        val answer = when (val value = obj.get("answer")) {
            null -> emptyList()
            else -> if (value.isJsonArray) stringList(value) else listOfNotNull(value.asStringOrNull())
        }
        val optionSources = linkedMapOf<String, List<String>>()
        obj.get("optionSources")?.takeIf { it.isJsonObject }?.asJsonObject?.entrySet()?.forEach { (key, value) ->
            optionSources[key.uppercase()] = stringList(value)
        }
        val allSources = stringList(obj.get("sourceIds")).ifEmpty { fallbackSources }
        val provenance = QuestionProvenance(
            sourceIds = allSources,
            questionSource = stringList(obj.get("questionSource")),
            optionSources = optionSources,
            answerSource = stringList(obj.get("answerSource")),
            explanationSource = stringList(obj.get("explanationSource")),
            knowledgeSource = stringList(obj.get("knowledgeSource")),
        )
        return StructuredCandidate(
            type = type,
            question = question,
            options = options,
            answer = answer,
            explanation = obj.stringOrNull("explanation"),
            knowledge = obj.stringOrNull("knowledge"),
            originalQuestionNumber = obj.intOrNull("originalQuestionNumber") ?: number,
            provenance = provenance,
        )
    }

    private fun parseAnswerSections(element: JsonElement?): List<String> {
        if (element == null || !element.isJsonArray) return emptyList()
        return element.asJsonArray.flatMap { section ->
            if (section.isJsonObject) stringList(section.asJsonObject.get("sourceIds")) else emptyList()
        }
    }

    private fun stringList(element: JsonElement?): List<String> {
        if (element == null || element.isJsonNull) return emptyList()
        return when {
            element.isJsonArray -> element.asJsonArray.mapNotNull { it.asStringOrNull() }
            else -> listOfNotNull(element.asStringOrNull())
        }
    }

    private fun JsonElement.asStringOrNull(): String? =
        takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun JsonObject.stringOrNull(key: String): String? = get(key)?.asStringOrNull()
    private fun JsonObject.intOrNull(key: String): Int? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt

    private fun stripFence(raw: String): String = raw.trim()
        .removePrefix("```json")
        .removePrefix("```JSON")
        .removePrefix("```")
        .removeSuffix("```")
        .trim()
}
