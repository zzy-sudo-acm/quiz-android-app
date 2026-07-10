package com.zzy.quizforge.ui.settings

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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zzy.quizforge.BuildConfig
import com.zzy.quizforge.R
import com.zzy.quizforge.ui.components.SurfaceCard
import com.zzy.quizforge.ui.theme.ErrorRed
import com.zzy.quizforge.ui.theme.TerminalBackground
import com.zzy.quizforge.ui.theme.TextMuted

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showClearDialog by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = TerminalBackground,
        topBar = {
            TopAppBar(
                title = { Text("设置") },
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
            // ===== API Key 输入 =====
            SurfaceCard {
                Text("DeepSeek API Key", fontWeight = FontWeight.Bold)
                Text(
                    "本地解析失败的题目会自动交给 DeepSeek 修复格式。不配置也能用，本地解析通常已能识别 90% 以上的原题。",
                    color = TextMuted,
                    modifier = Modifier.padding(top = 6.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (!state.isEncrypted) {
                    Text(
                        "⚠ 安全存储不可用，API Key 无法加密保存，已禁用保存功能。请检查设备安全设置后重试。",
                        color = ErrorRed,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                OutlinedTextField(
                    value = state.apiKey,
                    onValueChange = viewModel::updateApiKey,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    label = { Text("sk-...") },
                    enabled = state.isEncrypted,
                )
                Button(
                    onClick = viewModel::saveApiKey,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    enabled = state.isEncrypted,
                ) {
                    Icon(Icons.Default.Save, contentDescription = null)
                    Text("保存")
                }
                state.savedMessage?.let {
                    Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
                }
            }

            // ===== 新增：API 申请引导 =====
            SurfaceCard {
                Text("如何获取 DeepSeek API Key", fontWeight = FontWeight.Bold)
                Text(
                    "第一次用？按下面步骤 3 分钟搞定：",
                    color = TextMuted,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
                StepRow("1", "打开 platform.deepseek.com，手机号注册并实名")
                StepRow("2", "在「充值」页面充值，最低 1 元起，支持微信/支付宝")
                StepRow("3", "进入「API Keys」页面，点「创建 API Key」")
                StepRow("4", "复制 sk- 开头的字符串，粘贴到上方输入框")

                Text(
                    "费用说明",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 14.dp),
                )
                Text(
                    "API 费用由 DeepSeek 按其当前公开计费标准收取。\n" +
                        "QuizForge 不收取 API 费用。",
                    color = TextMuted,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Text(
                    "当前版本处于解析引擎兼容性验证模式。\n" +
                        "导入结果仍由稳定解析器生成；新解析引擎会同步进行校验。\n" +
                        "如配置 API Key，歧义题目片段可能调用 DeepSeek 辅助识别，\n" +
                        "并产生少量 API 费用。",
                    color = TextMuted,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Text(
                    if (state.isEncrypted) {
                        "💡 API Key 加密保存在本机，不会上传至 QuizForge 自有服务器。\n" +
                            "调用 DeepSeek API 时，Key 会直接发送给 DeepSeek 用于认证。\n" +
                            "AI 仅处理本地解析失败的单题片段或新解析引擎判定为歧义的题目片段，\n" +
                            "不会主动把整篇 Word 文档发送给 DeepSeek。"
                    } else {
                        "⚠ 安全存储不可用，App 不会保存 API Key。\n" +
                            "当前需要 DeepSeek 的 AI 辅助功能将不可用。"
                    },
                    color = TextMuted,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            SurfaceCard {
                Text("数据", fontWeight = FontWeight.Bold)
                Text(
                    "清除后会重新导入预置题库，导入的题库和作答记录会被移除。",
                    color = TextMuted,
                    modifier = Modifier.padding(top = 6.dp),
                )
                OutlinedButton(
                    onClick = { showClearDialog = true },
                    enabled = !state.isClearing,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, tint = ErrorRed)
                    Text(if (state.isClearing) "正在清除..." else "清除所有数据", color = ErrorRed)
                }
            }

            SurfaceCard {
                Text("关于", fontWeight = FontWeight.Bold)
                Text(
                    "${stringResource(R.string.app_name)} ${BuildConfig.VERSION_NAME}",
                    color = TextMuted,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Text("导入策略：${com.zzy.quizforge.util.document.ImportRuntimeConfig.displayName}", color = TextMuted)
                Text("GitHub: zzy-sudo-acm/quiz-app", color = TextMuted)
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("确认清除？") },
            text = { Text("这个操作会删除本机题库和作答记录。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearDialog = false
                        viewModel.clearAllData()
                    },
                ) {
                    Text("清除", color = ErrorRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text("取消")
                }
            },
        )
    }
}

@Composable
private fun StepRow(num: String, text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = num,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = text,
            color = TextMuted,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
    }
}
