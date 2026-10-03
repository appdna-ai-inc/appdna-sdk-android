package ai.appdna.sdk.feedback.views

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ai.appdna.sdk.R
import ai.appdna.sdk.feedback.SurveyAnswer
import ai.appdna.sdk.feedback.SurveyQuestion
import androidx.compose.ui.text.TextStyle

@Composable
fun RatingQuestionView(
    question: SurveyQuestion,
    answer: SurveyAnswer?,
    onAnswer: (SurveyAnswer) -> Unit,
    // Gap #20 — question text style token
    questionTextStyle: TextStyle = TextStyle.Default,
    // Filled rating icons honor the survey theme's resolved accent_color, matching the
    // console SurveyPreview (which fills rating icons with accentColor for every style). Was
    // hardcoded per-style (heart red / thumb #6366F1 / star gold), ignoring accent_color.
    accentColor: Color = ai.appdna.sdk.AppDNA.brandAccentColor()
) {
    // Prefer Firestore-canonical `max`
    // over legacy `max_rating`. `resolvedMax` already does this resolution.
    val maxRating = question.ratingConfig?.resolvedMax ?: 5
    // Prefer Firestore-canonical `icon` over
    // legacy `style` via `resolvedIcon`. iOS RatingQuestionView.swift:13 uses
    // `rating_config?.resolvedIcon`. Android was reading `.style` directly so
    // server-side `rating_config.icon: "heart"` rendered as legacy default
    // "star" when only `icon` was set.
    val style = question.ratingConfig?.resolvedIcon ?: "star"
    val selectedRating = answer?.answer as? Int ?: 0

    val filledIcon = when (style) {
        "heart" -> Icons.Filled.Favorite
        "thumb" -> Icons.Filled.ThumbUp
        else -> Icons.Filled.Star
    }
    val outlinedIcon = when (style) {
        "heart" -> Icons.Outlined.FavoriteBorder
        "thumb" -> Icons.Outlined.ThumbUp
        else -> Icons.Outlined.Star
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = question.text,
            style = questionTextStyle.takeIf { it != TextStyle.Default } ?: MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (rating in 1..maxRating) {
                // Localized "Rating N of M".
                val ratingCd = stringResource(R.string.appdna_a11y_rating_n_of_m, rating, maxRating)
                Icon(
                    imageVector = if (selectedRating >= rating) filledIcon else outlinedIcon,
                    contentDescription = ratingCd,
                    tint = if (selectedRating >= rating) accentColor else Color.Gray.copy(alpha = 0.3f),
                    modifier = Modifier
                        .size(36.dp)
                        .clickable { onAnswer(SurveyAnswer(question.id, rating)) }
                )
            }
        }
    }
}
