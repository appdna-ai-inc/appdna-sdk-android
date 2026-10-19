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

        // NPS: 9-10 promoter, 0-6 detractor, 7-8 passive.
        if (type == "nps") {
            val score = intValue(answer) ?: return SurveySentiment.NEUTRAL
            return when {
                score >= 9 -> SurveySentiment.POSITIVE
                score <= 6 -> SurveySentiment.NEGATIVE
                else -> SurveySentiment.NEUTRAL
            }
        }

        // CSAT / rating out of five.
        if (type == "csat" || type == "rating") {
            val rating = intValue(answer) ?: return SurveySentiment.NEUTRAL
            return when {
                rating >= 4 -> SurveySentiment.POSITIVE
                rating <= 2 -> SurveySentiment.NEGATIVE
                else -> SurveySentiment.NEUTRAL
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
