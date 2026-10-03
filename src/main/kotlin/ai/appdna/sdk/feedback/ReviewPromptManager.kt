package ai.appdna.sdk.feedback

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import ai.appdna.sdk.AppDNA
import ai.appdna.sdk.Log
import com.google.android.play.core.review.ReviewManagerFactory

/**
 * Manages review prompt presentation using Google Play In-App Review API.
 * Tracks prompt frequency via SharedPreferences to enforce rate limits,
 * matching the iOS ReviewPromptManager's UserDefaults-based tracking.
 */
object ReviewPromptManager {

    private const val PREFS_NAME = "ai.appdna.sdk.review"
    private const val KEY_PROMPT_COUNT = "review_prompt_count"
    private const val KEY_LAST_DATE = "review_prompt_last_date"
    private const val MAX_PROMPTS_PER_YEAR = 3
    private const val MIN_DAYS_BETWEEN_PROMPTS = 90

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Check whether a review prompt can be shown based on rate limits.
     * Enforces max 3 prompts per year and minimum 90 days between prompts.
     */
    fun canShowReviewPrompt(context: Context): Boolean {
        val prefs = getPrefs(context)
        val count = prefs.getInt(KEY_PROMPT_COUNT, 0)
        val lastDate = prefs.getLong(KEY_LAST_DATE, 0L)

        // Max prompts per year
        if (count >= MAX_PROMPTS_PER_YEAR) {
            Log.debug("Review prompt rate-limited: $count/$MAX_PROMPTS_PER_YEAR prompts used this period")
            return false
        }

        // Min days between prompts
        if (lastDate > 0) {
            val daysSince = (System.currentTimeMillis() - lastDate) / 86_400_000
            if (daysSince < MIN_DAYS_BETWEEN_PROMPTS) {
                Log.debug("Review prompt rate-limited: only $daysSince days since last prompt (min $MIN_DAYS_BETWEEN_PROMPTS)")
                return false
            }
        }

        return true
    }

    /**
     * Record that a review prompt was shown. Increments count and updates last shown date.
     */
    private fun recordPromptShown(context: Context) {
        val prefs = getPrefs(context)
        val count = prefs.getInt(KEY_PROMPT_COUNT, 0)
        prefs.edit()
            .putInt(KEY_PROMPT_COUNT, count + 1)
            .putLong(KEY_LAST_DATE, System.currentTimeMillis())
            .apply()
    }

    /**
     * Trigger the Google Play In-App Review flow.
     * Respects rate limits and records prompt display.
     */
    fun triggerReview(context: Context) {
        if (!canShowReviewPrompt(context)) {
            Log.info("Review prompt suppressed due to rate limiting")
            return
        }
        launchNativeReview(context, emitDirectShown = true)
    }

    /**
     * Launch the Play in-app review flow. When [emitDirectShown] is false the caller already emitted the
     * `review_prompt_shown` event (the two-step path emits `{two_step}` when its dialog appears), so this
     * must NOT emit a second `{direct}` one. The two-step positive handler previously called
     * the public [triggerReview], which re-emitted `review_prompt_shown{direct}` → the two-step-accept
     * path logged TWO shown events (two_step + direct) vs iOS's one, and mis-labelled the second `direct`
     * inside a two-step interaction. Mirrors iOS's private `requestNativeReview()`.
     */
    private fun launchNativeReview(context: Context, emitDirectShown: Boolean) {
        val reviewManager = ReviewManagerFactory.create(context)
        val request = reviewManager.requestReviewFlow()

        request.addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val reviewInfo = task.result
                if (context is Activity) {
                    val flow = reviewManager.launchReviewFlow(context, reviewInfo)
                    flow.addOnCompleteListener {
                        Log.info("Review flow completed")
                    }
                }
                recordPromptShown(context)
                if (emitDirectShown) {
                    AppDNA.track("review_prompt_shown", mapOf("prompt_type" to "direct"))
                }
            } else {
                Log.warning("Failed to request review flow: ${task.exception?.message}")
            }
        }
    }

    /**
     * Two-step review prompt: show dialog first, then trigger native review on positive response.
     * Respects rate limits and records prompt display.
     */
    fun triggerTwoStepReview(activity: Activity, appName: String? = null) {
        if (!canShowReviewPrompt(activity)) {
            Log.info("Two-step review prompt suppressed due to rate limiting")
            return
        }

        val name = appName ?: activity.applicationInfo.loadLabel(activity.packageManager).toString()

        val dialog = android.app.AlertDialog.Builder(activity)
            .setTitle("Enjoying $name?")
            .setMessage("We'd love to hear your feedback!")
            .setPositiveButton("Yes! \uD83D\uDE0A") { _, _ ->
                AppDNA.track("review_prompt_accepted")
                // Launch WITHOUT re-emitting review_prompt_shown \u2014 the `{two_step}` event already fired
                // when this dialog appeared (matches iOS's private requestNativeReview path).
                launchNativeReview(activity, emitDirectShown = false)
            }
            .setNegativeButton("Not really") { _, _ ->
                AppDNA.track("review_prompt_declined")
            }
            .create()

        dialog.show()
        AppDNA.track("review_prompt_shown", mapOf("prompt_type" to "two_step"))
    }
}
