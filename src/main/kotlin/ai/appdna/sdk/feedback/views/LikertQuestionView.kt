package ai.appdna.sdk.feedback.views

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.appdna.sdk.feedback.LikertScaleRule
import ai.appdna.sdk.feedback.SurveyAnswer
import ai.appdna.sdk.feedback.SurveyQuestion

/**
 * Likert scale question.
 *
 * Renders a horizontal numeric scale (default 1..5; configurable via
 * `likertConfig.min` / `likertConfig.max`) with optional left/right anchor
 * labels (e.g. "Strongly Disagree" → "Strongly Agree"). Mirrors the visual
 * style of [NpsQuestionView] (rounded radio-style buttons) so the existing
 * survey appearance theme applies cleanly. Selected button uses the
 * MaterialTheme primary color so the survey-resolved theme accent flows
 * through automatically.
 *
 * Records the answer as the selected integer rating via
 * `SurveyAnswer(question.id, ratingInt)` — same pattern as NPS / CSAT /
 * rating views, so the upload + show_if branching pipelines work without
 * additional changes.
 */
@Composable
fun LikertQuestionView(
    question: SurveyQuestion,
    answer: SurveyAnswer?,
    onAnswer: (SurveyAnswer) -> Unit,
    // Gap #20 — question text style token
    questionTextStyle: TextStyle = TextStyle.Default,
) {
    val cfg = question.likertConfig
    // Through LikertScaleRule, which the shared fixture drives — never re-stated here.
    val b = LikertScaleRule.bounds(cfg?.min, cfg?.max)
    val safeMin = b.lo
    val safeMax = b.hi
    val selectedScore = answer?.answer as? Int

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = question.text,
            style = questionTextStyle.takeIf { it != TextStyle.Default }
                ?: MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 16.dp),
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (score in safeMin..safeMax) {
                val isSelected = selectedScore == score
                Box(
                    modifier = Modifier
                        .size(width = 40.dp, height = 44.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            // Match iOS Likert/NPS hardcoded #6366F1
                            // brand color for selected background. Material3
                            // primary defaults to purple (#6750A4), visibly
                            // off-brand vs iOS at default theme.
                            if (isSelected) ai.appdna.sdk.AppDNA.brandAccentColor()
                            else Color.Gray.copy(alpha = 0.1f)
                        )
                        .clickable { onAnswer(SurveyAnswer(question.id, score)) }
                        .semantics {
                            role = Role.RadioButton
                            selected = isSelected
                            contentDescription = "Rating $score of $safeMax"
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "$score",
                        color = if (isSelected) Color.White else Color.Unspecified,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }

        // Optional left/right anchor labels (e.g. "Strongly Disagree" →
        // "Strongly Agree"). Hidden when neither label is provided so a
        // bare numeric likert doesn't reserve empty space.
        val low = cfg?.lowLabel
        val high = cfg?.highLabel
        if (LikertScaleRule.showsAnchors(low, high)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = low ?: "",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray,
                )
                Text(
                    text = high ?: "",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray,
                )
            }
        }
    }
}
