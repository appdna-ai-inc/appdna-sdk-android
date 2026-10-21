package ai.appdna.sdk.feedback

import android.content.Context
import android.content.SharedPreferences

/**
 * Tracks survey display frequency using SharedPreferences and in-memory session tracking.
 */
internal class SurveyFrequencyTracker(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("ai.appdna.sdk.survey_freq", Context.MODE_PRIVATE)
    private val sessionShownIds = mutableSetOf<String>()

    fun canShow(surveyId: String, frequency: String, maxDisplays: Int?): Boolean {
        return when (frequency) {
            "once" -> !prefs.getBoolean("$surveyId.shown", false)
            "once_per_session" -> surveyId !in sessionShownIds
            "every_time" -> true
            "max_times" -> {
                val max = maxDisplays ?: return true
                prefs.getInt("$surveyId.count", 0) < max
            }
            /*
             * 🔴 `once`, NOT `true`. An unrecognised frequency used to be the MOST permissive
             * option, which made the two platforms opposites: iOS decodes an unknown value to
             * `.once` (`MessageFrequency(rawValue:) ?? .once`), so the console's `recurring` — a
             * value no SDK implements, carried by 7 live surveys when #1064 measured it — was shown
             * once ever on iOS and on EVERY trigger event here.
             *
             * `once` is the console's own documented default, so an unrecognised value now behaves
             * as if the author had set nothing, on both platforms. Nothing is ever shown MORE often
             * than the author could have asked for.
             */
            else -> !prefs.getBoolean("$surveyId.shown", false)
        }
    }

    fun recordDisplay(surveyId: String) {
        sessionShownIds.add(surveyId)
        val count = prefs.getInt("$surveyId.count", 0)
        prefs.edit()
            .putBoolean("$surveyId.shown", true)
            .putInt("$surveyId.count", count + 1)
            .apply()
    }

    fun resetSession() {
        sessionShownIds.clear()
    }
}
