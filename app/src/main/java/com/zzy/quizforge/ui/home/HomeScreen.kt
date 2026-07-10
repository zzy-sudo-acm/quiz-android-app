package com.zzy.quizforge.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zzy.quizforge.R
import com.zzy.quizforge.domain.model.QuizMode
import com.zzy.quizforge.ui.components.SurfaceCard
import com.zzy.quizforge.ui.theme.ErrorRed
import com.zzy.quizforge.ui.theme.TerminalBackground
import com.zzy.quizforge.ui.theme.TextMuted
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onImport: () -> Unit,
    onSettings: () -> Unit,
    onStartQuiz: (Long, QuizMode) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.deleteError) {
        state.deleteError?.let { error ->
            snackbarHostState.showSnackbar("删除题库失败：$error")
            viewModel.clearDeleteError()
        }
    }

    Scaffold(
        containerColor = TerminalBackground,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = TerminalBackground),
                actions = {
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onImport,
                icon = { Icon(Icons.Default.Add, contentDescription = "导入") },
                text = { Text("导入文档") },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(text = "题库", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text(text = "选择题库开始刷题，或导入 Word 生成新题库。", color = TextMuted)
                }
            }

            if (state.isLoading) {
                item {
                    SurfaceCard {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text("加载中...", modifier = Modifier.padding(top = 12.dp))
                    }
                }
            } else if (state.banks.isEmpty()) {
                item {
                    SurfaceCard {
                        Text("暂无题库", fontWeight = FontWeight.Bold)
                        Text("点击右下角「导入文档」添加题库。", color = TextMuted, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }

            items(state.banks, key = { it.id }) { bank ->
                BankCard(bank = bank, onStartQuiz = onStartQuiz, onDeleteBank = { viewModel.deleteBank(bank.id) })
            }

            item { Spacer(modifier = Modifier.padding(bottom = 72.dp)) }
        }
    }
}

@Composable
private fun BankCard(
    bank: QuizBankSummaryUi,
    onStartQuiz: (Long, QuizMode) -> Unit,
    onDeleteBank: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    SurfaceCard {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = bank.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(text = "${bank.questionCount} 题 · 正确率 ${bank.accuracyText} · 错题 ${bank.wrongCount}", color = TextMuted, modifier = Modifier.padding(top = 4.dp))
                Text(text = bank.lastPracticedAt?.let { "最近练习 ${formatDate(it)}" } ?: "还没有练习记录", color = TextMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
            }
            IconButton(onClick = { menuExpanded = true }) { Icon(Icons.Default.MoreVert, contentDescription = "更多", tint = TextMuted) }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(
                    text = { Text("删除题库", color = ErrorRed) },
                    onClick = { menuExpanded = false; showDeleteDialog = true },
                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = ErrorRed) },
                )
            }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onStartQuiz(bank.id, QuizMode.SEQUENTIAL) }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Text(bank.sequentialActionText)
            }
            Button(onClick = { onStartQuiz(bank.id, QuizMode.RANDOM) }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Shuffle, contentDescription = null); Text("随机")
            }
            TextButton(onClick = { onStartQuiz(bank.id, QuizMode.WRONG) }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Warning, contentDescription = null); Text("错题")
            }
        }
    }
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("确认删除") },
            text = { Text("删除「${bank.name}」？\n\n将同时删除该题库、答题记录和学习进度。\n此操作无法撤销。") },
            confirmButton = { TextButton(onClick = { showDeleteDialog = false; onDeleteBank() }) { Text("删除", color = ErrorRed) } },
            dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text("取消") } },
        )
    }
}

private fun formatDate(time: Long): String = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(time))
