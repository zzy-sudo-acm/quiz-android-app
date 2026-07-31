package com.zzy.quizforge.ui.importdoc

import android.content.Context
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zzy.quizforge.ui.components.SurfaceCard
import com.zzy.quizforge.ui.theme.BorderColor
import com.zzy.quizforge.ui.theme.CardBackground
import com.zzy.quizforge.ui.theme.ErrorRed
import com.zzy.quizforge.ui.theme.PrimaryGreen
import com.zzy.quizforge.ui.theme.TerminalBackground
import com.zzy.quizforge.ui.theme.TextMuted
import com.zzy.quizforge.util.document.ImportMode
import com.zzy.quizforge.util.document.SourceLedgerStatus

private const val DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(
    viewModel: ImportViewModel,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenBank: (Long) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var showFormat by remember { mutableStateOf(false) }
    var showDataInfo by remember { mutableStateOf(false) }
    var showSmartConsent by remember { mutableStateOf(false) }
    var showFailures by remember { mutableStateOf(false) }
    var templateMessage by remember { mutableStateOf<String?>(null) }
    val leaveScreen = {
        if (!state.isCommitting) {
            viewModel.cancelCurrentImport()
            onBack()
        }
    }
    BackHandler(onBack = leaveScreen)

    LaunchedEffect(Unit) { viewModel.refreshApiStatus() }

    val docxPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            viewModel.readDocument(uri, getDisplayName(context, uri))
        }
    }
    val templateSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(DOCX_MIME)) { uri ->
        if (uri != null) {
            templateMessage = runCatching {
                context.assets.open("quizforge-standard-template.docx").use { input ->
                    context.contentResolver.openOutputStream(uri, "w")?.use(input::copyTo)
                        ?: error("无法写入所选位置")
                }
                "标准 Word 模板已保存"
            }.getOrElse { "模板保存失败：${it.message ?: "未知错误"}" }
        }
    }

    Scaffold(
        containerColor = TerminalBackground,
        topBar = {
            TopAppBar(
                title = { Text("导入 Word 题库") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = TerminalBackground),
                navigationIcon = {
                    IconButton(onClick = leaveScreen, enabled = !state.isCommitting) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("选择一种导入方式", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("两种方式相互独立，标准导入不会自动调用 AI。", color = TextMuted)

            ImportModeCard(
                selected = state.mode == ImportMode.STANDARD,
                icon = { Icon(Icons.Default.Lock, contentDescription = null, tint = PrimaryGreen) },
                title = "标准格式导入",
                description = "按照模板整理，完全离线，无需 API，速度快、识别最稳定。",
                badges = listOf("完全离线", "无需 API", "推荐使用"),
                onSelect = { viewModel.selectMode(ImportMode.STANDARD) },
            ) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showFormat = true }, modifier = Modifier.weight(1f)) {
                        Text("查看标准格式")
                    }
                    OutlinedButton(onClick = { templateSaver.launch("QuizForge-标准题库模板.docx") }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Download, contentDescription = null)
                        Text("保存模板")
                    }
                }
                Button(
                    onClick = { viewModel.selectMode(ImportMode.STANDARD); docxPicker.launch(arrayOf(DOCX_MIME)) },
                    enabled = !state.isBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Description, contentDescription = null)
                    Text("选择标准格式 Word")
                }
            }

            ImportModeCard(
                selected = state.mode == ImportMode.SMART,
                icon = { Icon(Icons.Default.Cloud, contentDescription = null) },
                title = "智能识别混乱格式",
                description = "适合已有的复杂 Word，需要配置模型 API，会产生调用费用。",
                badges = listOf("复杂格式", "需要 API", "可能产生费用"),
                onSelect = { viewModel.selectMode(ImportMode.SMART) },
            ) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showDataInfo = true }, modifier = Modifier.weight(1f)) {
                        Text("查看数据说明")
                    }
                    OutlinedButton(onClick = onOpenSettings, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Settings, contentDescription = null)
                        Text("配置 API")
                    }
                }
                Button(
                    onClick = { viewModel.selectMode(ImportMode.SMART); docxPicker.launch(arrayOf(DOCX_MIME)) },
                    enabled = !state.isBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Description, contentDescription = null)
                    Text("选择复杂格式 Word")
                }
            }

            templateMessage?.let { Text(it, color = if (it.startsWith("模板保存失败")) ErrorRed else PrimaryGreen) }

            if (state.prepared != null || state.recognition != null || state.isBusy) {
                SurfaceCard {
                    if (state.isBusy) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator()
                            Text(state.statusText, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Text(state.statusText, fontWeight = FontWeight.Bold)
                    }
                    state.prepared?.let { prepared ->
                        Text(
                            "${prepared.fileName} · ${prepared.sourceBlocks.count { it.isNonEmpty }} 段 · " +
                                "${prepared.imageCount} 张图片 · ${prepared.tableCount} 个表格",
                            color = TextMuted,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    if (state.progressTotal > 0) {
                        Text("进度 ${state.progressCurrent}/${state.progressTotal}", color = TextMuted)
                    }
                }
            }

            if (state.mode == ImportMode.SMART && state.prepared != null && state.recognition == null) {
                if (!state.apiConfigured) {
                    Text("尚未配置 API Key。选择文件不会调用 API；请先到设置页配置。", color = ErrorRed)
                }
                Button(
                    onClick = { showSmartConsent = true },
                    enabled = state.canRecognizeSmart,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("确认并开始智能识别")
                }
            }

            state.recognition?.report?.let { report ->
                SurfaceCard {
                    Text("导入报告", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "原文 ${report.totalSourceBlocks} 段 · 候选 ${report.candidateQuestionCount} · 成功 ${report.acceptedQuestionCount} · " +
                            "失败 ${report.rejectedQuestionCount} · 非题目 ${report.nonQuestionCount} · " +
                            "不支持 ${report.unsupportedCount}" +
                            (if (report.duplicateQuestionCount > 0) " · 重复 ${report.duplicateQuestionCount}" else ""),
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Text(
                        if (report.usedApi) "已使用 API，共 ${report.apiRequestCount} 个模型批次" else "未使用 API",
                        color = TextMuted,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    if (report.warnings.isNotEmpty()) {
                        Text(
                            "需要人工确认",
                            color = MaterialTheme.colorScheme.tertiary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        report.warnings.forEach { warning ->
                            Text("• $warning", color = MaterialTheme.colorScheme.tertiary)
                        }
                    }
                    if (report.hasUncertainContent) {
                        Text(
                            "识别已完成，但部分原文没有确定归属。创建前请查看失败原文。",
                            color = ErrorRed,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        OutlinedButton(onClick = { showFailures = true }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                            Text("查看失败原文")
                        }
                        if (state.ignoredFailuresConfirmed) {
                            Text(
                                "已确认忽略失败内容；创建时只保存成功识别的题目。",
                                color = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        } else {
                            TextButton(
                                onClick = viewModel::acknowledgeFailedContent,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("忽略失败内容，只创建成功题目")
                            }
                        }
                    } else if (report.warnings.isEmpty()) {
                        Row(modifier = Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = PrimaryGreen)
                            Text("所有非空原文均已有明确归属", color = PrimaryGreen)
                        }
                    } else {
                        Text(
                            "原文均已有归属，但请先完成上述人工核对。",
                            color = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }

                OutlinedTextField(
                    value = state.bankName,
                    onValueChange = viewModel::updateBankName,
                    label = { Text("题库名称") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Button(onClick = viewModel::commit, enabled = state.canCommit, modifier = Modifier.fillMaxWidth()) {
                    Text(if (state.isCommitting) "正在创建…" else "确认创建题库（${report.acceptedQuestionCount} 道）")
                }
                TextButton(onClick = viewModel::cancelCurrentImport, enabled = !state.isBusy, modifier = Modifier.fillMaxWidth()) {
                    Text("返回修改 Word / 取消本次导入")
                }
            }

            state.error?.let { Text(it, color = ErrorRed, fontWeight = FontWeight.Bold) }
            state.generatedBankId?.let { bankId ->
                Button(onClick = { onOpenBank(bankId) }, modifier = Modifier.fillMaxWidth()) {
                    Text("进入新题库")
                }
            }
        }
    }

    if (showFormat) {
        AlertDialog(
            onDismissRequest = { showFormat = false },
            title = { Text("标准格式说明") },
            text = { Text(STANDARD_EXAMPLE) },
            confirmButton = {
                TextButton(onClick = { clipboard.setText(AnnotatedString(STANDARD_EXAMPLE)); showFormat = false }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null)
                    Text("复制标准示例")
                }
            },
            dismissButton = { TextButton(onClick = { showFormat = false }) { Text("关闭") } },
        )
    }
    if (showDataInfo) {
        AlertDialog(
            onDismissRequest = { showDataInfo = false },
            title = { Text("智能识别数据说明") },
            text = {
                Text(
                    "需要使用你自己的 API Key，并可能产生模型费用。QuizForge 会把从 Word 提取出的题库文字、" +
                        "自动编号、表格坐标和图片占位分段发送给所选模型服务；不会上传 DOCX 二进制或图片内容。" +
                        "复杂格式不保证全部成功，无法确认的原文会写入导入报告。",
                )
            },
            confirmButton = { TextButton(onClick = { showDataInfo = false }) { Text("我知道了") } },
        )
    }
    if (showSmartConsent) {
        AlertDialog(
            onDismissRequest = { showSmartConsent = false },
            title = { Text("确认调用模型 API") },
            text = {
                Text(
                    "接下来会把已提取的题库文字和必要结构信息分段发送给 DeepSeek，使用你自己的 API Key，" +
                        "可能产生费用。识别结果不保证全部成功；失败原文会保留在报告中。是否继续？",
                )
            },
            confirmButton = {
                Button(onClick = { showSmartConsent = false; viewModel.recognizeSmart() }) { Text("确认调用 API") }
            },
            dismissButton = { TextButton(onClick = { showSmartConsent = false }) { Text("取消") } },
        )
    }
    if (showFailures) {
        val failed = state.recognition?.report?.records.orEmpty().filter {
            it.status == SourceLedgerStatus.REJECTED_QUESTION || it.status == SourceLedgerStatus.UNSUPPORTED_CONTENT
        }
        AlertDialog(
            onDismissRequest = { showFailures = false },
            title = { Text("失败原文（${failed.size}）") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    failed.forEachIndexed { index, record ->
                        Column {
                            Text("${index + 1}. ${record.reasonCode?.name ?: "UNSUPPORTED"}", fontWeight = FontWeight.Bold, color = ErrorRed)
                            Text(record.reasonMessage.orEmpty(), color = TextMuted)
                            Text(record.rawText.ifBlank { "（无可显示文字，可能为嵌入对象）" }, modifier = Modifier.padding(top = 4.dp))
                            TextButton(onClick = { clipboard.setText(AnnotatedString(record.rawText)) }) { Text("复制原文") }
                            if (state.mode == ImportMode.SMART) {
                                TextButton(
                                    onClick = { viewModel.retryFailedFragment(record) },
                                    enabled = !state.isBusy && record.sourceIds.isNotEmpty(),
                                ) {
                                    Text("重新识别此片段（可能产生费用）")
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showFailures = false }) { Text("关闭") } },
        )
    }
}

@Composable
private fun ImportModeCard(
    selected: Boolean,
    icon: @Composable () -> Unit,
    title: String,
    description: String,
    badges: List<String>,
    onSelect: () -> Unit,
    actions: @Composable () -> Unit,
) {
    Card(
        onClick = onSelect,
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) PrimaryGreen else BorderColor),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                icon()
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            Text(description, color = TextMuted)
            Text(badges.joinToString(" · "), color = if (selected) PrimaryGreen else TextMuted, style = MaterialTheme.typography.labelMedium)
            actions()
        }
    }
}

private fun getDisplayName(context: Context, uri: android.net.Uri): String {
    val fallback = uri.lastPathSegment ?: "导入文档.docx"
    return context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (cursor.moveToFirst() && index >= 0) cursor.getString(index) else fallback
    } ?: fallback
}

private val STANDARD_EXAMPLE = """
每道题必须有明确题号；每个选项建议单独一段；答案必须放在对应题目后。解析和知识点可以省略。

1. 下列关于操作系统的说法正确的是？

A. 操作系统只负责运行应用程序
B. 操作系统负责管理计算机软硬件资源
C. 操作系统不管理内存
D. 操作系统只存在于电脑中

答案：B
解析：操作系统负责管理和调度计算机软硬件资源。
知识点：操作系统基础

2. 下列属于传输层协议的是？
A. TCP
B. UDP
C. IP
D. ARP
答案：AB

3. 进程和程序是完全相同的概念。
答案：错
""".trimIndent()
