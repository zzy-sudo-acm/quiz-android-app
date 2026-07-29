package com.zzy.quizforge.ui.settings

import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.filled.Wifi
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zzy.quizforge.BuildConfig
import com.zzy.quizforge.R
import com.zzy.quizforge.ui.components.SurfaceCard
import com.zzy.quizforge.ui.theme.ErrorRed
import com.zzy.quizforge.ui.theme.TerminalBackground
import com.zzy.quizforge.ui.theme.TextMuted
import com.zzy.quizforge.data.remote.ModelTier

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showClearDialog by remember { mutableStateOf(false) }
    val leaveScreen = { if (!state.isClearing) onBack() }
    BackHandler(onBack = leaveScreen)

    Scaffold(
        containerColor = TerminalBackground,
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = TerminalBackground),
                navigationIcon = {
                    IconButton(onClick = leaveScreen, enabled = !state.isClearing) {
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
                Text("DeepSeek API Key", fontWeight = FontWeight.Bold)
                Text(
                    "仅“智能识别混乱格式”需要用户自己的 API Key；标准格式导入完全离线，不读取 Key，也不会产生模型费用。",
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
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    label = { Text("sk-...") },
                    enabled = state.isEncrypted,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = viewModel::saveApiKey,
                        modifier = Modifier.weight(1f),
                        enabled = state.isEncrypted,
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null)
                        Text("保存")
                    }
                    OutlinedButton(
                        onClick = viewModel::deleteApiKey,
                        modifier = Modifier.weight(1f),
                        enabled = state.isEncrypted && state.apiKey.isNotBlank(),
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null)
                        Text("删除 Key")
                    }
                }
                Text("模型档位", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ModelTier.entries.forEach { tier ->
                        if (state.modelTier == tier) {
                            Button(onClick = { viewModel.selectModelTier(tier) }, modifier = Modifier.weight(1f)) {
                                Text(tier.label)
                            }
                        } else {
                            OutlinedButton(onClick = { viewModel.selectModelTier(tier) }, modifier = Modifier.weight(1f)) {
                                Text(tier.label)
                            }
                        }
                    }
                }
                Text(
                    "使用 ${state.modelTier.modelName}；" +
                        if (state.modelTier == ModelTier.QUICK) "速度更快、费用更低。" else "适合更复杂的混乱文档。",
                    color = TextMuted,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
                OutlinedButton(
                    onClick = viewModel::testConnection,
                    enabled = state.isEncrypted && state.apiKey.isNotBlank() && !state.isTestingConnection,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                ) {
                    Icon(Icons.Default.Wifi, contentDescription = null)
                    Text(if (state.isTestingConnection) "正在测试…" else "测试连接（不上传题库）")
                }
                state.savedMessage?.let {
                    Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
                }
            }

            SurfaceCard {
                Text("如何获取 DeepSeek API Key", fontWeight = FontWeight.Bold)
                Text(
                    "API Key 需要由你自行申请和管理：",
                    color = TextMuted,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
                StepRow("1", "打开 platform.deepseek.com，按平台提示注册并登录")
                StepRow("2", "按平台当前流程开通 API 服务并创建 API Key")
                StepRow("3", "复制 sk- 开头的字符串，粘贴到上方输入框")
                StepRow("4", "API 费用以 DeepSeek 当前公开计费规则为准")

                Text(
                    "费用说明",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 14.dp),
                )
                Text(
                    "智能识别产生的 API 费用由你的 DeepSeek 账户承担，QuizForge 不代收模型费用。",
                    color = TextMuted,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Text(
                    "数据发送说明",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 14.dp),
                )
                Text(
                    "使用智能识别时，App 会把从所选 Word 提取出的题库文字和必要结构信息分段发送给 DeepSeek；不会上传原始 DOCX 文件或图片二进制。标准格式导入不会发送任何题库内容。",
                    color = TextMuted,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Text(
                    if (state.isEncrypted) {
                        "💡 API Key 加密保存在本机，不会上传至 QuizForge 自有服务器。\n" +
                            "调用 API 时，Key 仅直接发送给 DeepSeek 用于认证，并已排除在系统云备份和设备迁移之外。"
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
                Text("GitHub: zzy-sudo-acm/quiz-android-app", color = TextMuted)
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
