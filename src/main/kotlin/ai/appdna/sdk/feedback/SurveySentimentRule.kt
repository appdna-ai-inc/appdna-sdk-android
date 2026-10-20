package ai.appdna.sdk.feedback

/**
 * WHICH follow-up branch a completed survey takes — in one place both platforms are held to.
 *
 * 🔴 #1011 — this lived inline in both renderers and classified an emoji answer against a
 * HARD-CODED literal of the five default emoji. The Console's own default scale is not that list
 * (it writes 😟 and 🙂 where the SDKs had 😕 and 😊), so a survey created in the Console and
 * answered with either middle face came back NEUTRAL, and any custom scale came back neutral for
 * every answer — `follow_up_actions.on_positive` / `on_negative` silently never ran.
 *
 * It is a pure function so a shared fixture can drive the REAL decision on both platforms rather
 * than re-implementing it (CLAUDE.md Rule 25).
 *
 * 🔴 The sentiment is read from the SURVEY's `surveyType`, not from the question's own type. A
 * survey left as `custom` is always neutral whatever its questions are — documented on all four
 * SDK pages, and deliberately not changed here: inferring it would silently re-route every
 * existing survey.
 */
object SurveySentimentRule {

    /** The scale an emoji question falls back to when none is authored — the SDK's historical five. */
    val DEFAULT_EMOJI_SCALE = listOf("😡", "😕", "😐", "😊", "😍")

    /** `POSITIVE` / `NEGATIVE` / `NEUTRAL`, as the three `follow_up_actions` branches. */
    fun resolve(surveyType: String?, answer: Any?, emojiScale: List<String>?): SurveySentiment {
        val type = surveyType ?: ""

        /*
         * 🔴 #1037 — A NUMERIC TYPE WITH A NON-NUMERIC ANSWER FALLS THROUGH. It must not stop here.
         *
         * iOS reads `if ["csat","rating"].contains(type), let rating = intValue(answer)`, where the
         * binding is PART of the condition: a `csat` survey answered with an emoji drops out of the
         * branch and is classified by its scale below. This file is the shared rule and was written
         * as `val rating = intValue(answer) ?: return NEUTRAL`, which is a different function — the
         * early return made the fall-through unreachable.
         *
         * So a `csat` survey whose first question is an emoji scale — a thoroughly ordinary thing to
         * author, "rate your satisfaction" with five faces — classified EVERY answer as neutral on
         * Android and correctly on iOS. With no `on_neutral` branch authored, `executeFollowUp`
         * returned at its second `?: return` and the feedback form never appeared, silently and
         * with nothing in logcat. That is #1037, and it was invisible until a survey was answered
         * on a device.
         *
         * The shared FILE existed on both platforms and the two still disagreed, because a Swift
         * optional-binding condition and a Kotlin elvis-return are not the same control flow. Only
         * a fixture that drives both can see that, which is why `survey_sentiment_scale` now
         * carries the numeric-type-with-an-emoji-answer cases.
         */

        // NPS: 9-10 promoter, 0-6 detractor, 7-8 passive.
        if (type == "nps") {
            val score = intValue(answer)
            if (score != null) {
                return when {
                    score >= 9 -> SurveySentiment.POSITIVE
                    score <= 6 -> SurveySentiment.NEGATIVE
                    else -> SurveySentiment.NEUTRAL
                }
            }
        }

        // CSAT / rating out of five.
        if (type == "csat" || type == "rating") {
            val rating = intValue(answer)
            if (rating != null) {
                return when {
                    rating >= 4 -> SurveySentiment.POSITIVE
                    rating <= 2 -> SurveySentiment.NEGATIVE
                    else -> SurveySentiment.NEUTRAL
                }
            }
        }

        /*
         * Emoji: ABOVE the middle of the AUTHORED scale is positive, below it negative, the middle
         * itself neutral. The midpoint reproduces the old thresholds exactly for a five-emoji scale
         * and is defined for every other length — a three-emoji scale had no positive branch at all.
         */
        if (type == "emoji_scale" || answer is String) {
            val emoji = answer as? String
            if (emoji != null) {
                val scale = emojiScale?.takeIf { it.isNotEmpty() } ?: DEFAULT_EMOJI_SCALE
                val idx = scale.indexOf(emoji)
                if (idx >= 0) {
                    val middle = (scale.size - 1) / 2.0
                    return when {
                        idx > middle -> SurveySentiment.POSITIVE
                        idx < middle -> SurveySentiment.NEGATIVE
                        else -> SurveySentiment.NEUTRAL
                    }
                }
            }
        }

        return SurveySentiment.NEUTRAL
    }

    /** An answer arrives as whatever JSON held — a number or a numeric string. */
    private fun intValue(answer: Any?): Int? = when (answer) {
        is Number -> answer.toInt()
        is String -> answer.toIntOrNull()
        else -> null
    }
}
