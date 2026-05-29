package com.zzy.quizforge.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

val AppBackground = Color(0xFFF7F5EF)
val AppBackgroundTop = Color(0xFFFFFDF8)
val CardBackground = Color(0xFFFFFFFF)
val BorderColor = Color(0xFFDFE5DE)
val PrimaryGreen = Color(0xFF1F6F55)
val NeutralButton = Color(0xFFE7EBE8)
val OptionBackground = Color(0xFFFBFCFB)
val SelectedBackground = Color(0xFFEEF8F2)
val SuccessGreen = Color(0xFF177245)
val SuccessBackground = Color(0xFFEAF6EE)
val ErrorRed = Color(0xFFB6422C)
val ErrorBackground = Color(0xFFFCEDEA)
val TextPrimary = Color(0xFF202124)
val TextMuted = Color(0xFF6C706F)
val TypeAccent = Color(0xFF8A4B12)

val TerminalBackground = AppBackground
val PrimaryBlue = PrimaryGreen

private val LightColors = lightColorScheme(
    primary = PrimaryGreen,
    secondary = SuccessGreen,
    tertiary = ErrorRed,
    background = AppBackground,
    surface = CardBackground,
    surfaceVariant = NeutralButton,
    outline = BorderColor,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(8.dp),
    extraLarge = RoundedCornerShape(12.dp),
)

@Composable
fun QuizForgeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = LightColors,
        typography = MaterialTheme.typography,
        shapes = AppShapes,
        content = content,
    )
}
