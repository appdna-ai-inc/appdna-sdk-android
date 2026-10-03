package ai.appdna.sdk.feedback.views

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ai.appdna.sdk.feedback.SurveyAnswer
import ai.appdna.sdk.feedback.SurveyQuestion
import androidx.compose.ui.text.TextStyle

@Composable
fun YesNoView(
    question: SurveyQuestion,
    answer: SurveyAnswer?,
    onAnswer: (SurveyAnswer) -> Unit,
    // Gap #20 — question text style token
    questionTextStyle: TextStyle = TextStyle.Default,
    // Honor the survey theme's resolved colors (was hardcoded brandAccentColor() fill +
    // Color.White label, which ignored SurveyTheme.accent_color/button_text_color). Defaults
    // preserve prior behavior for callers (e.g. SurveyPreview) that do not pass them.
    accentColor: Color = ai.appdna.sdk.AppDNA.brandAccentColor(),
    buttonTextColor: Color = Color.White,
    textColor: Color = Color.Unspecified
) {
    val selected = answer?.answer as? String

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = question.text,
            style = questionTextStyle.takeIf { it != TextStyle.Default } ?: MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Button(
                onClick = { onAnswer(SurveyAnswer(question.id, "yes")) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (selected == "yes") accentColor else Color.Gray.copy(alpha = 0.1f),
                    contentColor = if (selected == "yes") buttonTextColor else textColor
                )
            ) { Text("Yes") }

            Button(
                onClick = { onAnswer(SurveyAnswer(question.id, "no")) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (selected == "no") accentColor else Color.Gray.copy(alpha = 0.1f),
                    contentColor = if (selected == "no") buttonTextColor else textColor
                )
            ) { Text("No") }
        }
    }
}
