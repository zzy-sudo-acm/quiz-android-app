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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zzy.quizforge.R
import com.zzy.quizforge.domain.model.QuizMode
import com.zzy.quizforge.ui.components.SurfaceCard
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

    Scaffold(
        containerColor = TerminalBackground,
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
                    Text(
                        text = "题库",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "选择题库开始刷题，或导入 Word 生成新题库。",
                        color = TextMuted,
                    )
                }
            }

            if (state.banks.isEmpty()) {
                item {
                    SurfaceCard {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text("正在导入预置题库...", modifier = Modifier.padding(top = 12.dp))
                    }
                }
            }

            items(state.banks, key = { it.id }) { bank ->
                BankCard(bank = bank, onStartQuiz = onStartQuiz)
            }

            item {
                Spacer(modifier = Modifier.padding(bottom = 72.dp))
            }
        }
    }
}

@Composable
private fun BankCard(
    bank: QuizBankSummaryUi,
    onStartQuiz: (Long, QuizMode) -> Unit,
) {
    SurfaceCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = bank.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "${bank.questionCount} 题 · 正确率 ${bank.accuracyText} · 错题 ${bank.wrongCount}",
                    color = TextMuted,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    text = bank.lastPracticedAt?.let { "最近练习 ${formatDate(it)}" } ?: "还没有练习记录",
                    color = TextMuted,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { onStartQuiz(bank.id, QuizMode.SEQUENTIAL) },
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Text("顺序")
            }
            Button(
                onClick = { onStartQuiz(bank.id, QuizMode.RANDOM) },
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Default.Shuffle, contentDescription = null)
                Text("随机")
            }
            TextButton(
                onClick = { onStartQuiz(bank.id, QuizMode.WRONG) },
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Default.Warning, contentDescription = null)
                Text("错题")
            }
        }
    }
}

private fun formatDate(time: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(time))
