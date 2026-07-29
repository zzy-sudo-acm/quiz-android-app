package com.zzy.quizforge.ui.components

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zzy.quizforge.domain.model.QuestionOption
import com.zzy.quizforge.ui.theme.BorderColor
import com.zzy.quizforge.ui.theme.CardBackground
import com.zzy.quizforge.ui.theme.ErrorRed
import com.zzy.quizforge.ui.theme.ErrorBackground
import com.zzy.quizforge.ui.theme.OptionBackground
import com.zzy.quizforge.ui.theme.PrimaryGreen
import com.zzy.quizforge.ui.theme.SelectedBackground
import com.zzy.quizforge.ui.theme.SuccessBackground
import com.zzy.quizforge.ui.theme.SuccessGreen
import com.zzy.quizforge.ui.theme.TextMuted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun SurfaceCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        border = BorderStroke(1.dp, BorderColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        content = {
            Column(modifier = Modifier.padding(16.dp)) {
                content()
            }
        },
    )
}

@Composable
fun OptionButton(
    option: QuestionOption,
    selected: Boolean,
    correct: Boolean,
    submitted: Boolean,
    enabled: Boolean = !submitted,
    onClick: () -> Unit,
) {
    val background = when {
        submitted && correct -> SuccessBackground
        submitted && selected && !correct -> ErrorBackground
        selected -> SelectedBackground
        else -> OptionBackground
    }
    val border = when {
        submitted && correct -> SuccessGreen
        submitted && selected && !correct -> ErrorRed
        selected -> PrimaryGreen
        else -> BorderColor
    }

    OutlinedButton(
        onClick = onClick,
        enabled = enabled && !submitted,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = background,
            disabledContainerColor = background,
            contentColor = MaterialTheme.colorScheme.onSurface,
            disabledContentColor = MaterialTheme.colorScheme.onSurface,
        ),
        border = BorderStroke(1.dp, border),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = option.key,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = if (selected || (submitted && correct)) Color.White else PrimaryGreen,
                modifier = Modifier
                    .size(28.dp)
                    .background(
                        color = if (selected || (submitted && correct)) PrimaryGreen else Color(0xFFE8EEEA),
                        shape = RoundedCornerShape(6.dp),
                    )
                    .padding(top = 3.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = option.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                QuizImage(
                    image = option.image,
                    imageUri = option.imageUri,
                    imageUris = option.imageUris,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            if (submitted && correct) {
                Icon(Icons.Default.Check, contentDescription = "正确", tint = SuccessGreen)
            } else if (submitted && selected && !correct) {
                Icon(Icons.Default.Close, contentDescription = "错误", tint = ErrorRed)
            }
        }
    }
}

@Composable
fun QuizImage(
    image: String?,
    imageUri: String?,
    imageUris: List<String> = emptyList(),
    modifier: Modifier = Modifier,
) {
    val sources = (imageUris + listOfNotNull(imageUri)).filter(String::isNotBlank).distinct()
    if (sources.isEmpty() && image.isNullOrBlank()) return
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (sources.isNotEmpty()) {
            sources.forEach { source -> AsyncQuizImage(image = null, imageUri = source) }
        } else {
            AsyncQuizImage(image = image, imageUri = null)
        }
    }
}

@Composable
private fun AsyncQuizImage(image: String?, imageUri: String?) {
    val context = LocalContext.current
    val unsupported = imageUri?.substringAfterLast('.', "")?.lowercase() in setOf("emf", "wmf")
    if (unsupported) {
        Text(
            text = "该图片为 EMF/WMF，Android 暂不支持显示，请在 Word 中转为 PNG 或 JPEG",
            color = ErrorRed,
            modifier = Modifier.fillMaxWidth().background(ErrorBackground, RoundedCornerShape(8.dp)).padding(10.dp),
            fontWeight = FontWeight.Bold,
        )
        return
    }
    val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, image, imageUri) {
        value = withContext(Dispatchers.IO) { loadBitmapSampled(context, image, imageUri) }
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = "题目图片",
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, BorderColor, RoundedCornerShape(8.dp))
                .padding(4.dp),
            contentScale = ContentScale.Fit,
        )
    } else {
        Text(
            text = "图片加载失败",
            color = ErrorRed,
            modifier = Modifier
                .fillMaxWidth()
                .background(ErrorBackground, RoundedCornerShape(8.dp))
                .padding(10.dp),
            fontWeight = FontWeight.Bold,
        )
    }
}

private fun loadBitmapSampled(
    context: Context,
    image: String?,
    imageUri: String?,
) = runCatching {
    fun open() = when {
        !imageUri.isNullOrBlank() && imageUri.startsWith("content://") ->
            context.contentResolver.openInputStream(android.net.Uri.parse(imageUri))
        !imageUri.isNullOrBlank() -> java.io.File(imageUri).inputStream()
        !image.isNullOrBlank() -> context.assets.open(image.removePrefix("assets/").trimStart('/'))
        else -> null
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    open()?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample.coerceAtLeast(1) }
    open()?.use { BitmapFactory.decodeStream(it, null, options) }
}.getOrNull()
