package ai.appdna.sdk.feedback.views

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ai.appdna.sdk.feedback.SurveyAnswer
import ai.appdna.sdk.feedback.SurveyQuestion
import androidx.compose.ui.text.TextStyle

@Composable
fun FreeTextView(
    question: SurveyQuestion,
    answer: SurveyAnswer?,
    onAnswer: (SurveyAnswer) -> Unit,
    // Gap #20 — question text style token
    questionTextStyle: TextStyle = TextStyle.Default
) {
    val maxLength = question.freeTextConfig?.maxLength ?: 500
    val placeholder = question.freeTextConfig?.placeholder ?: "Type your answer..."
    var text by remember { mutableStateOf((answer?.answer as? String) ?: "") }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = question.text,
            style = questionTextStyle.takeIf { it != TextStyle.Default } ?: MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        OutlinedTextField(
            value = text,
            onValueChange = { newValue ->
                // Truncate paste-overflow instead of
                // silently rejecting. iOS FreeTextView.swift:42-44 uses
                // `String(newValue.prefix(maxLength))` so a 600-char paste into
                // a 500-char field yields the first 500 chars. Android was
                // dropping the entire input when length>max, leaving the
                // textfield blank after a long paste.
                val truncated = if (newValue.length > maxLength) newValue.take(maxLength) else newValue
                text = truncated
                onAnswer(SurveyAnswer(question.id, truncated))
            },
            placeholder = { Text(placeholder) },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 120.dp),
            // Mirror iOS FreeTextView.swift:36-51 — RoundedRectangle
            // cornerRadius:8 stroke. Material3 default outlined textfield
            // uses asymmetric rounding; force symmetric 8dp corners.
            shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
            // Text keyboard with `Done` IME action so the
            // soft keyboard exposes a dismiss key. Mirrors iOS
            // `submitLabel(.done)` used by SurveyRenderer's free-text view.
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Done,
            ),
            maxLines = 6
        )

        Text(
            text = "${text.length}/$maxLength",
            style = MaterialTheme.typography.labelSmall,
            color = if (text.length >= maxLength) Color.Red else Color.Gray,
            modifier = Modifier.align(Alignment.End).padding(top = 4.dp)
        )
    }
}
