package ai.appdna.sdk.feedback

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.appdna.sdk.feedback.views.CsatQuestionView
import ai.appdna.sdk.feedback.views.FreeTextView
import ai.appdna.sdk.feedback.views.LikertQuestionView
import ai.appdna.sdk.feedback.views.MultiChoiceView
import ai.appdna.sdk.feedback.views.NpsQuestionView
import ai.appdna.sdk.feedback.views.SingleChoiceView
import kotlinx.collections.immutable.toImmutableList

/**
 * Compose `@Preview` functions for survey question
 * renderers. Designers can iterate on individual question types inside
 * Android Studio without running the SDK on a device.
 *
 * Covers all six P0 question types (NPS, CSAT, Likert, Free-Text,
 * Single-Choice, Multi-Choice). Each type ships a Light + Dark preview.
 *
 * Production composable signatures are NOT changed by this file —
 * fixtures are constructed via the existing public [SurveyQuestion]
 * constructor and routed through the existing public per-type
 * Composables (NpsQuestionView / CsatQuestionView / etc.).
 */

// MARK: - Fixtures

private fun npsQuestion(): SurveyQuestion = SurveyQuestion(
    id = "q-nps",
    type = "nps",
    text = "How likely are you to recommend us to a friend?",
    required = true,
    showIf = null,
    npsConfig = NPSConfig(lowLabel = "Not likely", highLabel = "Extremely likely"),
    csatConfig = null,
    ratingConfig = null,
    options = null,
    emojiConfig = null,
    freeTextConfig = null,
)

private fun csatQuestion(): SurveyQuestion = SurveyQuestion(
    id = "q-csat",
    type = "csat",
    text = "How satisfied are you with the app?",
    required = true,
    showIf = null,
    npsConfig = null,
    csatConfig = CSATConfig(maxRating = 5, style = "star"),
    ratingConfig = null,
    options = null,
    emojiConfig = null,
    freeTextConfig = null,
)

private fun likertQuestion(): SurveyQuestion = SurveyQuestion(
    id = "q-likert",
    type = "likert",
    text = "I find the new dashboard easier to use.",
    required = true,
    showIf = null,
    npsConfig = null,
    csatConfig = null,
    ratingConfig = null,
    options = null,
    emojiConfig = null,
    freeTextConfig = null,
    likertConfig = LikertConfig(
        min = 1,
        max = 5,
        lowLabel = "Strongly disagree",
        highLabel = "Strongly agree",
    ),
)

private fun freeTextQuestion(): SurveyQuestion = SurveyQuestion(
    id = "q-free",
    type = "free_text",
    text = "Anything else you'd like us to know?",
    required = false,
    showIf = null,
    npsConfig = null,
    csatConfig = null,
    ratingConfig = null,
    options = null,
    emojiConfig = null,
    freeTextConfig = FreeTextConfig(
        placeholder = "Type your thoughts here...",
        maxLength = 280,
    ),
)

private fun singleChoiceQuestion(): SurveyQuestion {
    val options = listOf(
        QuestionOption("opt-monthly", "Monthly subscription", icon = null),
        QuestionOption("opt-yearly", "Yearly subscription", icon = null),
        QuestionOption("opt-lifetime", "One-time purchase", icon = null),
    ).toImmutableList()
    return SurveyQuestion(
        id = "q-single",
        type = "single_choice",
        text = "Which plan best fits your needs?",
        required = true,
        showIf = null,
        npsConfig = null,
        csatConfig = null,
        ratingConfig = null,
        options = options,
        emojiConfig = null,
        freeTextConfig = null,
    )
}

private fun multiChoiceQuestion(): SurveyQuestion {
    val options = listOf(
        QuestionOption("opt-speed", "Faster performance", icon = null),
        QuestionOption("opt-design", "Better design", icon = null),
        QuestionOption("opt-features", "More features", icon = null),
        QuestionOption("opt-price", "Lower price", icon = null),
    ).toImmutableList()
    return SurveyQuestion(
        id = "q-multi",
        type = "multi_choice",
        text = "What would make you upgrade? (pick any)",
        required = false,
        showIf = null,
        npsConfig = null,
        csatConfig = null,
        ratingConfig = null,
        options = options,
        emojiConfig = null,
        freeTextConfig = null,
    )
}

// MARK: - Wrapper

/**
 * Preview-only frame: renders the question with a label so multi-question
 * preview panels in Studio identify which fixture is which. Wraps everything
 * in [MaterialTheme] so Material colors resolve.
 */
@Composable
private fun SurveyPreviewFrame(
    label: String,
    isDark: Boolean,
    body: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (isDark)
            androidx.compose.material3.darkColorScheme()
        else
            androidx.compose.material3.lightColorScheme(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .padding(16.dp),
        ) {
            Text(
                text = label,
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            body()
        }
    }
}

// MARK: - Light previews

@Preview(name = "Survey NPS — Light", showBackground = true, widthDp = 360)
@Composable
private fun SurveyNpsLightPreview() {
    SurveyPreviewFrame("NPS (0–10)", isDark = false) {
        NpsQuestionView(
            question = npsQuestion(),
            answer = SurveyAnswer("q-nps", 9),
            onAnswer = { },
        )
    }
}

@Preview(name = "Survey CSAT — Light", showBackground = true, widthDp = 360)
@Composable
private fun SurveyCsatLightPreview() {
    SurveyPreviewFrame("CSAT (1–5 stars)", isDark = false) {
        CsatQuestionView(
            question = csatQuestion(),
            answer = SurveyAnswer("q-csat", 4),
            onAnswer = { },
        )
    }
}

@Preview(name = "Survey Likert — Light", showBackground = true, widthDp = 360)
@Composable
private fun SurveyLikertLightPreview() {
    SurveyPreviewFrame("Likert (1–5 with anchors)", isDark = false) {
        LikertQuestionView(
            question = likertQuestion(),
            answer = SurveyAnswer("q-likert", 4),
            onAnswer = { },
        )
    }
}

@Preview(name = "Survey FreeText — Light", showBackground = true, widthDp = 360, heightDp = 320)
@Composable
private fun SurveyFreeTextLightPreview() {
    SurveyPreviewFrame("Free-Text", isDark = false) {
        FreeTextView(
            question = freeTextQuestion(),
            answer = null,
            onAnswer = { },
        )
    }
}

@Preview(name = "Survey SingleChoice — Light", showBackground = true, widthDp = 360, heightDp = 360)
@Composable
private fun SurveySingleChoiceLightPreview() {
    SurveyPreviewFrame("Single-Choice", isDark = false) {
        SingleChoiceView(
            question = singleChoiceQuestion(),
            answer = SurveyAnswer("q-single", "opt-yearly"),
            onAnswer = { },
        )
    }
}

@Preview(name = "Survey MultiChoice — Light", showBackground = true, widthDp = 360, heightDp = 400)
@Composable
private fun SurveyMultiChoiceLightPreview() {
    SurveyPreviewFrame("Multi-Choice", isDark = false) {
        MultiChoiceView(
            question = multiChoiceQuestion(),
            answer = SurveyAnswer("q-multi", listOf("opt-speed", "opt-features")),
            onAnswer = { },
        )
    }
}

// MARK: - Dark previews

@Preview(
    name = "Survey NPS — Dark",
    showBackground = true,
    widthDp = 360,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun SurveyNpsDarkPreview() {
    SurveyPreviewFrame("NPS (0–10)", isDark = true) {
        NpsQuestionView(
            question = npsQuestion(),
            answer = SurveyAnswer("q-nps", 9),
            onAnswer = { },
        )
    }
}

@Preview(
    name = "Survey CSAT — Dark",
    showBackground = true,
    widthDp = 360,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun SurveyCsatDarkPreview() {
    SurveyPreviewFrame("CSAT (1–5 stars)", isDark = true) {
        CsatQuestionView(
            question = csatQuestion(),
            answer = SurveyAnswer("q-csat", 4),
            onAnswer = { },
        )
    }
}

@Preview(
    name = "Survey Likert — Dark",
    showBackground = true,
    widthDp = 360,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun SurveyLikertDarkPreview() {
    SurveyPreviewFrame("Likert (1–5 with anchors)", isDark = true) {
        LikertQuestionView(
            question = likertQuestion(),
            answer = SurveyAnswer("q-likert", 4),
            onAnswer = { },
        )
    }
}

@Preview(
    name = "Survey FreeText — Dark",
    showBackground = true,
    widthDp = 360,
    heightDp = 320,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun SurveyFreeTextDarkPreview() {
    SurveyPreviewFrame("Free-Text", isDark = true) {
        FreeTextView(
            question = freeTextQuestion(),
            answer = null,
            onAnswer = { },
        )
    }
}

@Preview(
    name = "Survey SingleChoice — Dark",
    showBackground = true,
    widthDp = 360,
    heightDp = 360,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun SurveySingleChoiceDarkPreview() {
    SurveyPreviewFrame("Single-Choice", isDark = true) {
        SingleChoiceView(
            question = singleChoiceQuestion(),
            answer = SurveyAnswer("q-single", "opt-yearly"),
            onAnswer = { },
        )
    }
}

@Preview(
    name = "Survey MultiChoice — Dark",
    showBackground = true,
    widthDp = 360,
    heightDp = 400,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun SurveyMultiChoiceDarkPreview() {
    SurveyPreviewFrame("Multi-Choice", isDark = true) {
        MultiChoiceView(
            question = multiChoiceQuestion(),
            answer = SurveyAnswer("q-multi", listOf("opt-speed", "opt-features")),
            onAnswer = { },
        )
    }
}

// Used to silence Color unused-import warning when Color is referenced
// only for default fallback in some preview frames.
@Suppress("unused")
private val previewBlack: Color = Color.Black
