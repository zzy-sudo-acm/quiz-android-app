package com.zzy.quizforge.ui.importdoc

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zzy.quizforge.data.repository.ImportRepository
import com.zzy.quizforge.util.document.ImportMode
import com.zzy.quizforge.util.document.ImportRecognitionResult
import com.zzy.quizforge.util.document.ImportReportRecord
import com.zzy.quizforge.util.document.PreparedImport
import com.zzy.quizforge.util.document.SmartRecognitionStage
import com.zzy.quizforge.util.document.SourceLedgerStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

data class ImportUiState(
    val mode: ImportMode? = null,
    val fileName: String = "",
    val bankName: String = "",
    val prepared: PreparedImport? = null,
    val recognition: ImportRecognitionResult? = null,
    val isReading: Boolean = false,
    val isRecognizing: Boolean = false,
    val isCommitting: Boolean = false,
    val statusText: String = "请选择导入方式",
    val generatedBankId: Long? = null,
    val apiConfigured: Boolean = false,
    val progressCurrent: Int = 0,
    val progressTotal: Int = 0,
    val ignoredFailuresConfirmed: Boolean = false,
    val error: String? = null,
) {
    val isBusy: Boolean get() = isReading || isRecognizing || isCommitting
    val canChooseFile: Boolean get() = mode != null && !isBusy
    val canRecognizeSmart: Boolean
        get() = mode == ImportMode.SMART && prepared != null && recognition == null && apiConfigured && !isBusy
    val canCommit: Boolean
        get() = recognition?.questions?.isNotEmpty() == true &&
            (recognition.report.hasUncertainContent.not() || ignoredFailuresConfirmed) &&
            bankName.isNotBlank() && !isBusy && generatedBankId == null
}

class ImportViewModel(
    private val repository: ImportRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ImportUiState())
    val uiState: StateFlow<ImportUiState> = _uiState.asStateFlow()
    private var activeJob: Job? = null
    private var operationGeneration = 0L

    fun selectMode(mode: ImportMode) {
        if (_uiState.value.isBusy || _uiState.value.mode == mode) return
        _uiState.value.prepared?.let(repository::discardImport)
        _uiState.value = ImportUiState(
            mode = mode,
            apiConfigured = mode == ImportMode.SMART && repository.hasApiKey(),
            statusText = if (mode == ImportMode.STANDARD) {
                "标准格式：完全离线，选择 Word 后立即预检"
            } else {
                "智能识别：先在本机提取结构，确认后才调用 API"
            },
        )
    }

    fun updateBankName(value: String) {
        _uiState.update { it.copy(bankName = value) }
    }

    fun acknowledgeFailedContent() {
        _uiState.update { state ->
            if (state.recognition?.report?.hasUncertainContent == true && !state.isBusy) {
                state.copy(ignoredFailuresConfirmed = true)
            } else state
        }
    }

    fun refreshApiStatus() {
        val smartMode = _uiState.value.mode == ImportMode.SMART
        _uiState.update { it.copy(apiConfigured = smartMode && repository.hasApiKey()) }
    }

    fun readDocument(uri: Uri, displayName: String) {
        val mode = _uiState.value.mode ?: return
        if (_uiState.value.isBusy) return
        _uiState.value.prepared?.let(repository::discardImport)
        _uiState.update {
            it.copy(
                fileName = displayName,
                bankName = displayName.substringBeforeLast('.').ifBlank { "导入题库" },
                prepared = null,
                recognition = null,
                isReading = true,
                generatedBankId = null,
                error = null,
                statusText = "正在完整读取 Word 结构…",
                progressCurrent = 0,
                progressTotal = 0,
                ignoredFailuresConfirmed = false,
            )
        }
        launchImportJob { operation ->
            try {
                val prepared = repository.prepareImport(uri, displayName, mode)
                if (!isCurrentOperation(operation)) {
                    repository.discardImport(prepared)
                    return@launchImportJob
                }
                val result = prepared.standardPreflight
                updateIfCurrent(operation) {
                    it.copy(
                        prepared = prepared,
                        recognition = result,
                        isReading = false,
                        statusText = when {
                            mode == ImportMode.STANDARD -> result!!.summaryText()
                            result != null -> result.summaryText() + "；已本地识别，未调用 API"
                            else -> {
                                "已提取 ${prepared.sourceBlocks.count { source -> source.isNonEmpty }} 段、" +
                                    "${prepared.imageCount} 张图片、${prepared.tableCount} 个表格；确认后才会调用 API"
                            }
                        },
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                updateIfCurrent(operation) {
                    it.copy(
                        isReading = false,
                        error = error.message ?: "读取 Word 失败",
                        statusText = "文档读取失败",
                    )
                }
            }
        }
    }

    fun recognizeSmart() {
        val state = _uiState.value
        val prepared = state.prepared ?: return
        if (!state.canRecognizeSmart) return
        _uiState.update {
            it.copy(
                isRecognizing = true,
                error = null,
                statusText = "正在智能识别完整文档…",
                progressCurrent = 0,
                progressTotal = 0,
                ignoredFailuresConfirmed = false,
            )
        }
        launchImportJob { operation ->
            try {
                val result = repository.recognizeSmart(prepared) { progress ->
                    updateIfCurrent(operation) {
                        it.copy(
                            progressCurrent = progress.current,
                            progressTotal = progress.total,
                            statusText = if (progress.stage == SmartRecognitionStage.BOUNDARY) {
                                "正在识别题目边界 ${progress.current}/${progress.total}"
                            } else {
                                "正在结构化待确认题目 ${progress.current}/${progress.total}"
                            },
                        )
                    }
                }
                updateIfCurrent(operation) {
                    it.copy(
                        recognition = result,
                        isRecognizing = false,
                        ignoredFailuresConfirmed = false,
                        statusText = result.summaryText(),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                updateIfCurrent(operation) {
                    it.copy(
                        isRecognizing = false,
                        error = error.message ?: "智能识别失败",
                        statusText = "智能识别失败；原文仍保留在当前任务中",
                    )
                }
            }
        }
    }

    fun retryFailedFragment(record: ImportReportRecord) {
        val state = _uiState.value
        val prepared = state.prepared ?: return
        val previous = state.recognition ?: return
        if (
            state.mode != ImportMode.SMART || state.isBusy || state.generatedBankId != null ||
            record.sourceIds.isEmpty() ||
            (record.status != SourceLedgerStatus.REJECTED_QUESTION &&
                record.status != SourceLedgerStatus.UNSUPPORTED_CONTENT)
        ) return
        val previousAccepted = previous.report.acceptedQuestionCount
        _uiState.update {
            it.copy(
                isRecognizing = true,
                error = null,
                statusText = "正在重新识别所选失败片段…",
                progressCurrent = 0,
                progressTotal = 0,
                ignoredFailuresConfirmed = false,
            )
        }
        launchImportJob { operation ->
            try {
                val result = repository.retrySmartRecord(prepared, previous, record) { progress ->
                    updateIfCurrent(operation) {
                        it.copy(
                            progressCurrent = progress.current,
                            progressTotal = progress.total,
                            statusText = if (progress.stage == SmartRecognitionStage.BOUNDARY) {
                                "正在重新判断片段边界 ${progress.current}/${progress.total}"
                            } else {
                                "正在重新结构化片段 ${progress.current}/${progress.total}"
                            },
                        )
                    }
                }
                val added = result.report.acceptedQuestionCount - previousAccepted
                updateIfCurrent(operation) {
                    it.copy(
                        recognition = result,
                        isRecognizing = false,
                        ignoredFailuresConfirmed = false,
                        statusText = if (added > 0) {
                            "片段重试成功，新增识别 $added 道；${result.summaryText()}"
                        } else {
                            "片段重试完成，但仍无法确认；可查看更新后的失败原因"
                        },
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                updateIfCurrent(operation) {
                    it.copy(
                        isRecognizing = false,
                        error = error.message ?: "片段重试失败",
                        statusText = "片段重试失败；已有成功内容未受影响",
                    )
                }
            }
        }
    }

    fun commit() {
        val state = _uiState.value
        val prepared = state.prepared ?: return
        val recognition = state.recognition ?: return
        if (!state.canCommit) return
        _uiState.update { it.copy(isCommitting = true, error = null, statusText = "正在创建题库并保存图片…") }
        launchImportJob { operation ->
            try {
                val bankId = repository.commitPreparedImport(prepared, recognition, state.bankName)
                updateIfCurrent(operation) {
                    it.copy(
                        isCommitting = false,
                        generatedBankId = bankId,
                        prepared = null,
                        statusText = "题库已创建：${recognition.questions.size} 道",
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                updateIfCurrent(operation) {
                    it.copy(
                        isCommitting = false,
                        error = error.message ?: "创建题库失败",
                        statusText = "创建失败，临时文件已清理",
                        prepared = null,
                    )
                }
            }
        }
    }

    fun cancelCurrentImport() {
        val current = _uiState.value
        if (current.isCommitting) return
        operationGeneration++
        activeJob?.cancel()
        activeJob = null
        current.prepared?.let(repository::discardImport)
        val mode = current.mode
        _uiState.value = ImportUiState(
            mode = mode,
            apiConfigured = mode == ImportMode.SMART && repository.hasApiKey(),
            statusText = "已取消本次导入",
        )
    }

    override fun onCleared() {
        val current = _uiState.value
        operationGeneration++
        activeJob?.cancel()
        if (!current.isCommitting) current.prepared?.let(repository::discardImport)
        super.onCleared()
    }

    private fun launchImportJob(block: suspend (Long) -> Unit) {
        val operation = ++operationGeneration
        val job = viewModelScope.launch { block(operation) }
        activeJob = job
        job.invokeOnCompletion {
            if (activeJob === job) activeJob = null
        }
    }

    private fun isCurrentOperation(operation: Long): Boolean = operation == operationGeneration

    private fun updateIfCurrent(operation: Long, transform: (ImportUiState) -> ImportUiState) {
        if (isCurrentOperation(operation)) _uiState.update(transform)
    }

    private fun ImportRecognitionResult.summaryText(): String {
        val report = report
        return if (report.hasUncertainContent) {
            "成功识别 ${report.acceptedQuestionCount} 道，另有 ${report.rejectedQuestionCount} 段无法确认" +
                (if (report.duplicateQuestionCount > 0) "（含 ${report.duplicateQuestionCount} 道重复）" else "") +
                "；请查看导入报告"
        } else {
            "预检完成：成功识别 ${report.acceptedQuestionCount} 道" +
                (if (report.duplicateQuestionCount > 0) "（跳过 ${report.duplicateQuestionCount} 道重复）" else "")
        }
    }
}
