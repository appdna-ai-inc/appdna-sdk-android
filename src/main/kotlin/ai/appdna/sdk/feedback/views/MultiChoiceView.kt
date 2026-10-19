package ai.appdna.sdk.feedback.views

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ai.appdna.sdk.feedback.SurveyAnswer
import ai.appdna.sdk.feedback.SurveyQuestion
import ai.appdna.sdk.core.ElementStyleConfig
import ai.appdna.sdk.core.StyleEngine
import ai.appdna.sdk.core.IconView

@Composable
fun MultiChoiceView(
    question: SurveyQuestion,
    answer: SurveyAnswer?,
    onAnswer: (SurveyAnswer) -> Unit,
    // Gap #20 — question text style token
    questionTextStyle: TextStyle = TextStyle.Default,
    // Gap #21 — option card container style token
    optionStyle: ElementStyleConfig? = null
) {
    @Suppress("UNCHECKED_CAST")
    val selectedIds = (answer?.answer as? List<String>) ?: emptyList()

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = question.text,
            style = questionTextStyle.takeIf { it != TextStyle.Default } ?: MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        question.options?.forEach { option ->
            val isSelected = option.id in selectedIds
            // Single click target via
            // `Modifier.toggleable(role = Role.Checkbox)` mirrors iOS
            // `Button { … }` wrapping the row. Inner Checkbox has
            // `onCheckedChange = null` so the entire row is the single tap
            // target with one TalkBack focus stop reading
            // "Checkbox, checked/unchecked, <option text>".
            if (optionStyle != null) {
                // Gap #21 — apply full style engine token to option card
                Surface(
                    modifier = with(StyleEngine) {
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .applyContainerStyle(optionStyle)
                            .toggleable(
                                value = isSelected,
                                role = Role.Checkbox,
                                onValueChange = { checked ->
                                    val updated = if (checked) selectedIds + option.id else selectedIds - option.id
                                    onAnswer(SurveyAnswer(question.id, updated))
                                },
                            )
                    },
                    color = Color.Transparent
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = isSelected,
                            onCheckedChange = null,
                        )
                        option.resolvedIcon?.let { IconView(it, modifier = Modifier.padding(end = 8.dp), defaultSize = 20f) }
                        Text(option.text)
                    }
                }
            } else {
                // Default: original border-only card style
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .toggleable(
                            value = isSelected,
                            role = Role.Checkbox,
                            onValueChange = { checked ->
                                val updated = if (checked) selectedIds + option.id else selectedIds - option.id
                                onAnswer(SurveyAnswer(question.id, updated))
                            },
                        ),
                    shape = RoundedCornerShape(8.dp),
                    // Honor the survey theme's resolved accent_color for the selected
                    // border (console parity). `MaterialTheme.colorScheme.primary` is seeded
                    // from SurveyTheme.accent_color (SurveyActivity.kt:152), matching the
                    // Checkbox tick (also primary) instead of the global brand accent.
                    border = BorderStroke(1.dp, if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.3f))
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = isSelected,
                            onCheckedChange = null,
                        )
                        option.resolvedIcon?.let { IconView(it, modifier = Modifier.padding(end = 8.dp), defaultSize = 20f) }
                        Text(option.text)
                    }
                }
            }
        }
    }
}
