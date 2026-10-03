package ai.appdna.sdk.feedback.views

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.appdna.sdk.feedback.SurveyAnswer
import ai.appdna.sdk.feedback.SurveyQuestion
import androidx.compose.ui.text.TextStyle

@Composable
fun EmojiScaleView(
    question: SurveyQuestion,
    answer: SurveyAnswer?,
    onAnswer: (SurveyAnswer) -> Unit,
    // Gap #20 — question text style token
    questionTextStyle: TextStyle = TextStyle.Default
) {
    val emojis = question.emojiConfig?.emojis
        ?: listOf("\uD83D\uDE21", "\uD83D\uDE15", "\uD83D\uDE10", "\uD83D\uDE0A", "\uD83D\uDE0D")
    val selectedEmoji = answer?.answer as? String

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = question.text,
            style = questionTextStyle.takeIf { it != TextStyle.Default } ?: MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            emojis.forEach { emoji ->
                // `Spring.StiffnessHigh` (~10000)
                // approximates iOS `.spring(response: 0.3)` (≈ stiffness 438
                // critical, but the iOS critically-damped feel reads as
                // "snappy" matching `StiffnessHigh`). Compose default
                // `spring()` uses `StiffnessMedium` (~1500) with `dampingRatio`
                // `NoBouncy` (1.0) which feels visibly slower than the iOS
                // emoji bounce on `EmojiScaleView.swift:29`.
                val scale by animateFloatAsState(
                    targetValue = if (selectedEmoji == emoji) 1.3f else 1.0f,
                    animationSpec = spring(stiffness = Spring.StiffnessHigh),
                    label = "emojiScale"
                )

                Text(
                    text = emoji,
                    fontSize = 36.sp,
                    modifier = Modifier
                        .scale(scale)
                        .clickable { onAnswer(SurveyAnswer(question.id, emoji)) }
                )
            }
        }
    }
}
