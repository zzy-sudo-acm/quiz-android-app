package com.zzy.quizforge.ui.importdoc

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zzy.quizforge.BuildConfig
import com.zzy.quizforge.ui.components.SurfaceCard
import com.zzy.quizforge.ui.theme.ErrorRed
import com.zzy.quizforge.ui.theme.TerminalBackground
import com.zzy.quizforge.ui.theme.TextMuted

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(
    viewModel: ImportViewModel,
    onBack: () -> Unit,
    onOpenBank: (Long) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val docxPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            viewModel.readDocument(uri, getDisplayName(context, uri))
        }
    }

    Scaffold(
        containerColor = TerminalBackground,
        topBar = {
            TopAppBar(
                title = { Text("导入文档") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = TerminalBackground),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SurfaceCard {
                Text(
                    "📄 导入说明",
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "本地解析 Word 原题为主，识别失败的段落可由 DeepSeek API 兜底修复格式。" +
                        "未配置 API Key 也可使用，只是失败段会被跳过。",
                    color = TextMuted,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            SurfaceCard {
                Button(
                    onClick = {
                        docxPicker.launch(
                            arrayOf("application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Description, contentDescription = null)
                    Text(if (state.fileName.isBlank()) "选择 .docx 文件" else state.fileName)
                }

                OutlinedTextField(
                    value = state.bankName,
                    onValueChange = viewModel::updateBankName,
                    label = { Text("题库名称") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    singleLine = true,
                )
            }

            Button(
                onClick = viewModel::generateV2,
                enabled = state.canGenerateV2,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Text(if (state.isGenerating) "正在生成..." else "生成题库")
            }

            SurfaceCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (state.isReading || state.isGenerating) {
                        CircularProgressIndicator()
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = state.statusText,
                            fontWeight = FontWeight.Bold,
                            color = if (state.error == null) MaterialTheme.colorScheme.onSurface else ErrorRed,
                        )
                        state.documentContent?.let {
                            Text(
                                text = "已提取 ${it.text.length} 字，${it.images.size} 张图片",
                                color = TextMuted,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        if (state.repairProgress.isNotBlank()) {
                            Text(
                                text = state.repairProgress,
                                color = TextMuted,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        if (state.generatedBankId != null) {
                            Text(
                                text = "本地识别 ${state.localCount} 道",
                                color = TextMuted,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                            Text(
                                text = "API 修复 ${state.apiCount} 道",
                                color = TextMuted,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                text = "跳过 ${state.skippedCount} 段",
                                color = TextMuted,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Text(
                            text = "构建：${BuildConfig.VERSION_NAME} / ${com.zzy.quizforge.util.document.ImportRuntimeConfig.displayName}",
                            color = TextMuted,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }

            state.error?.let {
                Text(text = it, color = ErrorRed)
            }
            state.generatedBankId?.let { bankId ->
                Button(
                    onClick = { onOpenBank(bankId) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("进入新题库（${state.generatedCount} 题）")
                }
            }
        }
    }
}

private fun getDisplayName(context: Context, uri: Uri): String {
    val fallback = uri.lastPathSegment ?: "导入文档.docx"
    return context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (cursor.moveToFirst() && index >= 0) cursor.getString(index) else fallback
    } ?: fallback
}
