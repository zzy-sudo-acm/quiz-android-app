package com.zzy.quizforge.ui.quiz

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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zzy.quizforge.ui.components.OptionButton
import com.zzy.quizforge.ui.components.QuizImage
import com.zzy.quizforge.ui.components.SurfaceCard
import com.zzy.quizforge.ui.theme.ErrorRed
import com.zzy.quizforge.ui.theme.SuccessGreen
import com.zzy.quizforge.ui.theme.TerminalBackground
import com.zzy.quizforge.ui.theme.TextMuted
import com.zzy.quizforge.ui.theme.TypeAccent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuizScreen(
    viewModel: QuizViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = TerminalBackground,
        topBar = {
            TopAppBar(
                title = { Text(state.mode.label) },
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
            when {
                state.isLoading -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                state.error != null -> Text(state.error.orEmpty(), color = ErrorRed)
                state.total == 0 -> EmptyQuizState(state.mode.label)
                state.finished -> FinishedState(state = state, onRestart = viewModel::restart, onBack = onBack)
                else -> QuestionState(state = state, viewModel = viewModel)
            }
        }
    }
}

@Composable
private fun QuestionState(
    state: QuizUiState,
    viewModel: QuizViewModel,
) {
    val question = state.currentQuestion ?: return
    val progress = if (state.total == 0) 0f else (state.currentIndex + 1f) / state.total

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(state.bankName, fontWeight = FontWeight.Bold)
            Text("${state.progressText} · ${state.accuracyText}", color = TextMuted)
        }
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())

        SurfaceCard {
            Text(
                text = question.type.label,
                color = TypeAccent,
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                text = question.question,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 8.dp),
            )
            QuizImage(
                image = question.image,
                imageUri = question.imageUri,
                imageUris = question.imageUris,
                modifier = Modifier.padding(top = 12.dp),
            )
            question.knowledge?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = "知识点：$it",
                    color = TextMuted,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        question.options.forEach { option ->
            OptionButton(
                option = option,
                selected = option.key in state.selected,
                correct = option.key in question.answer,
                submitted = state.submitted,
                enabled = !state.isSubmitting,
                onClick = { viewModel.toggleOption(option.key) },
            )
        }

        if (state.isSubmitting) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text("正在保存答案…", color = TextMuted)
        }

        state.submissionError?.let { message ->
            Text("提交失败：$message", color = ErrorRed)
        }

        if (question.type.isMultipleChoice && !state.submitted) {
            Button(
                onClick = viewModel::submit,
                enabled = state.selected.isNotEmpty() && !state.isSubmitting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.isSubmitting) "提交中…" else "确认提交")
            }
        }

        if (state.submitted) {
            SurfaceCard {
                Text(
                    text = if (state.lastCorrect == true) "回答正确" else "回答错误",
                    color = if (state.lastCorrect == true) SuccessGreen else ErrorRed,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "正确答案：${question.answer.joinToString("")}",
                    modifier = Modifier.padding(top = 6.dp),
                )
                question.explanation?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = "解析：$it",
                        color = TextMuted,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
            Button(onClick = viewModel::next, modifier = Modifier.fillMaxWidth()) {
                Text(
                    when {
                        state.currentIndex + 1 < state.total -> "下一题"
                        state.mode == com.zzy.quizforge.domain.model.QuizMode.RANDOM -> "开始下一轮"
                        else -> "完成"
                    },
                )
            }
        }
    }
}

@Composable
private fun EmptyQuizState(label: String) {
    SurfaceCard {
        Text(label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("这里暂时没有题目。", color = TextMuted, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun FinishedState(
    state: QuizUiState,
    onRestart: () -> Unit,
    onBack: () -> Unit,
) {
    SurfaceCard {
        Text("本轮完成", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            text = "已答 ${state.sessionAnswered} 题，正确 ${state.sessionCorrect} 题，${state.accuracyText}。",
            color = TextMuted,
            modifier = Modifier.padding(top = 8.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = onRestart, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Text("再来一遍")
            }
            TextButton(onClick = onBack, modifier = Modifier.weight(1f)) {
                Text("返回")
            }
        }
    }
}
