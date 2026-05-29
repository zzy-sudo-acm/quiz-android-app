package com.zzy.quizforge.ui.importdoc

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zzy.quizforge.data.repository.ImportProgress
import com.zzy.quizforge.data.repository.ImportRepository
import com.zzy.quizforge.util.DocumentContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ImportUiState(
    val fileName: String = "",
    val bankName: String = "",
    val documentContent: DocumentContent? = null,
    val isReading: Boolean = false,
    val isGenerating: Boolean = false,
    val statusText: String = "请选择 .docx 文件",
    val generatedBankId: Long? = null,
    val generatedCount: Int = 0,
    val localCount: Int = 0,
    val apiCount: Int = 0,
    val skippedCount: Int = 0,
    val repairProgress: String = "",
    val error: String? = null,
) {
    val canGenerate: Boolean
        get() = documentContent?.text?.isNotBlank() == true && bankName.isNotBlank() && !isGenerating && !isReading
}

class ImportViewModel(
    private val repository: ImportRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ImportUiState())
    val uiState: StateFlow<ImportUiState> = _uiState.asStateFlow()

    fun updateBankName(value: String) {
        _uiState.update { it.copy(bankName = value) }
    }

    fun readDocument(uri: Uri, displayName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update {
                it.copy(
                    fileName = displayName,
                    isReading = true,
                    statusText = "正在解析文档...",
                    error = null,
                    generatedBankId = null,
                )
            }
            runCatching { repository.extractDocx(uri) }
                .onSuccess { text ->
                    _uiState.update {
                        it.copy(
                            isReading = false,
                            documentContent = text,
                            bankName = it.bankName.ifBlank { displayName.substringBeforeLast('.') },
                            statusText = "文档解析完成：${text.text.length} 字，${text.images.size} 张图片",
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isReading = false,
                            error = error.message ?: "读取文档失败",
                            statusText = "文档解析失败",
                        )
                    }
                }
        }
    }

    fun generate() {
        val state = _uiState.value
        if (!state.canGenerate) return
        val documentContent = state.documentContent ?: return

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isGenerating = true,
                    generatedBankId = null,
                    error = null,
                    statusText = "正在本地识别原题...",
                    localCount = 0,
                    apiCount = 0,
                    skippedCount = 0,
                    repairProgress = "",
                )
            }

            repository.generateQuizBank(
                name = state.bankName.ifBlank { "导入题库" },
                documentContent = documentContent,
            ).collect { progress ->
                when (progress) {
                    is ImportProgress.Log -> _uiState.update {
                        it.copy(statusText = progress.text.trim().ifBlank { it.statusText })
                    }
                    is ImportProgress.Segment -> _uiState.update {
                        it.copy(
                            statusText = "API 修复第 ${progress.current}/${progress.total} 段...",
                            repairProgress = "已入库 ${progress.generatedSoFar} 道",
                        )
                    }
                    is ImportProgress.SegmentDone -> _uiState.update {
                        val accepted = if (progress.generatedInSegment > 0) "✓ 通过" else "✗ 跳过"
                        it.copy(
                            statusText = "API 修复第 ${progress.current}/${progress.total} 段 $accepted",
                            repairProgress = "已入库 ${progress.generatedSoFar} 道",
                        )
                    }
                    is ImportProgress.Done -> _uiState.update {
                        it.copy(
                            isGenerating = false,
                            generatedBankId = progress.bankId,
                            generatedCount = progress.count,
                            localCount = progress.localCount,
                            apiCount = progress.apiCount,
                            skippedCount = progress.skipped,
                            statusText = progress.message,
                            repairProgress = "",
                        )
                    }
                    is ImportProgress.Error -> _uiState.update {
                        it.copy(
                            isGenerating = false,
                            error = progress.message,
                            statusText = "生成失败",
                        )
                    }
                }
            }
        }
    }
}
