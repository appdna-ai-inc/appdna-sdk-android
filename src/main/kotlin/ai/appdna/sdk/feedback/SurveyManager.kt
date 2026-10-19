package ai.appdna.sdk.feedback

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.widget.EditText
import android.widget.FrameLayout
import ai.appdna.sdk.AppDNA
import ai.appdna.sdk.Log
import ai.appdna.sdk.events.EventTracker
import ai.appdna.sdk.network.ApiClient
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages survey trigger evaluation, display queue, frequency tracking, and presentation.
 */
internal class SurveyManager(
    private val context: Context,
    private val eventTracker: EventTracker,
    internal var apiClient: ApiClient? = null,
    // Consulted per-survey (inside the present path) for a
    // running survey experiment targeting the survey being shown.
    private val experimentManager: ai.appdna.sdk.config.ExperimentManager? = null,
) {
    private val frequencyTracker = SurveyFrequencyTracker(context)
    @Volatile private var surveyConfigs: Map<String, SurveyConfig> = emptyMap()
    private val isPresenting = AtomicBoolean(false)
    private var currentSurveyId: String? = null

    /**
     * `true` means "no feedback form is outstanding", which is the state before one is ever shown.
     *
     * 🔴 #920 fixed this on iOS and the comment there said Android "gets this from its activity
     * lifecycle" — true of the CLEANUP and not of the EVENT. `onActivityDestroyed` dismissed the
     * dialog and recorded nothing, and neither did a back press or an outside tap, so
     * `feedback_form_dismissed` under-counted on exactly the paths the user never chose. Worse,
     * the three outcomes were indistinguishable: a user who read the form and closed it, one who
     * backed out of it, and one whose form was taken off the screen by the host.
     */
    internal var feedbackFormResolved = true

    /**
     * Record that the feedback form has ended, ONCE, with why. `user` and `presenter_released`
     * are iOS's own words (`SurveyManager.swift` `resolveFeedbackForm`) — one vocabulary, because
     * a customer reads one funnel across both platforms.
     */
    internal fun resolveFeedbackForm(reason: String) {
        if (feedbackFormResolved) return
        feedbackFormResolved = true
        eventTracker.track("feedback_form_dismissed", mapOf("reason" to reason))
    }
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    fun updateConfigs(configs: Map<String, SurveyConfig>) {
        surveyConfigs = configs
    }

    /**
     * Evaluate all surveys against an event. Called on every tracked event.
     */
    fun onEvent(eventName: String, properties: Map<String, Any>?) {
        if (isPresenting.get()) return

        // Iterate in ASCENDING surveyId order for a DETERMINISTIC winner when multiple
        // surveys match one event (mirrors iOS SurveyManager + MessageManager's id-asc tie-break).
        // The unordered map + `break` previously picked a nondeterministic survey that diverged
        // from iOS. SurveyConfig has no priority field, so surveyId is the tie-break.
        for ((surveyId, config) in surveyConfigs.toSortedMap()) {
            // 1. Event match
            if (config.triggerRules.event != eventName) continue

            // 2. Conditions
            if (!evaluateConditions(config.triggerRules.conditions, properties ?: emptyMap())) continue

            // 3. Frequency
            if (!frequencyTracker.canShow(surveyId, config.triggerRules.frequency, config.triggerRules.maxDisplays)) continue

            // 4. Love score range
            if (!meetsLoveScoreRange(config.triggerRules.loveScoreRange)) continue

            // 5. Min sessions
            if (!meetsMinSessions(config.triggerRules.minSessions)) continue

            // 5. Present with delay
            val delay = config.triggerRules.delaySeconds?.toLong() ?: 0L
            scope.launch {
                if (delay > 0) delay(delay * 1000)
                presentSurvey(surveyId, config, eventName)
            }
            break // Only one survey per event
        }
    }

    /**
     * Present a specific survey by ID, bypassing trigger evaluation.
     */
    fun present(surveyId: String) {
        val config = surveyConfigs[surveyId]
        if (config == null) {
            Log.warning("Survey config not found for id: $surveyId")
            return
        }
        presentSurvey(surveyId, config, "manual")
    }

    fun resetSession() {
        frequencyTracker.resetSession()
    }

    /**
     * Cancel all pending presentation/network coroutines so
     * survey work doesn't outlive [AppDNA.shutdown]. Safe to call multiple
     * times; the scope is supervisor-jobbed.
     */
    internal fun shutdown() {
        scope.cancel()
        currentSurveyId = null
        isPresenting.set(false)
    }

    /**
     * Called by SurveyActivity when a question is answered.
     */
    internal fun onQuestionAnswered(questionId: String, questionType: String, answer: Any) {
        val surveyId = currentSurveyId ?: return
        eventTracker.track("survey_question_answered", mapOf(
            "survey_id" to surveyId,
            "question_id" to questionId,
            "question_type" to questionType,
            "answer" to answer
        ))
    }

    private fun presentSurvey(surveyId: String, activeConfig: SurveyConfig, triggerEvent: String) {
        // Pause new survey presentation while the SDK is
        // backend-locked (per-key suspended day 20+ OR org cancelled). No
        // analytics event, no delegate fire. Check BEFORE isPresenting flip.
        if (ai.appdna.sdk.AppDNA.runtimeLock != null) {
            ai.appdna.sdk.Log.debug("[Surveys] $surveyId suppressed — SDK in runtime-locked mode")
            return
        }

        if (!isPresenting.compareAndSet(false, true)) return
        currentSurveyId = surveyId

        // Experiment-aware presentation, attached inside the
        // present path (surveys are event-auto-triggered). A running survey
        // experiment targeting this survey + a treatment bucket renders the
        // treatment payload; control / none / old-doc → active.
        // resolveSurfacePresentation records an experiment_exposure as a side effect, so it
        // must run AFTER every synchronous suppression gate (runtimeLock above + isPresenting here): a
        // survey never shown must never count as exposed. Was above the isPresenting gate, so a
        // concurrent already-presenting call over-recorded exposures.
        var config = activeConfig
        val resolution = experimentManager?.resolveSurfacePresentation("survey", surveyId)
        if (resolution is ai.appdna.sdk.config.ExperimentManager.SurfaceResolution.RenderTreatment) {
            val treatment = SurveyConfig.fromMap(resolution.payload)
            if (treatment != null) {
                ai.appdna.sdk.Log.debug("[Surveys] $surveyId rendering experiment treatment variant")
                config = treatment
            }
        }

        eventTracker.track("survey_shown", mapOf(
            "survey_id" to surveyId,
            "survey_type" to config.surveyType,
            "trigger_event" to triggerEvent
        ))

        // Fire onSurveyPresented to the host's
        // AppDNASurveyDelegate. Mirrors iOS Feedback/SurveyManager.swift:100.
        fireOnSurveyPresented(surveyId)

        SurveyActivity.questionAnsweredCallback = { qId, qType, answer ->
            onQuestionAnswered(qId, qType, answer)
        }
        SurveyActivity.launch(context, surveyId, config) { result ->
            SurveyActivity.questionAnsweredCallback = null
            isPresenting.set(false)

            when (result) {
                is SurveyResult.Completed -> {
                    frequencyTracker.recordDisplay(surveyId)
                    trackCompleted(surveyId, config, result.answers)
                    submitResponse(surveyId, config, result.answers)
                    executeFollowUp(config, result.answers)
                    // Fire onSurveyCompleted with the typed responses.
                    fireOnSurveyCompleted(surveyId, result.answers)
                }
                is SurveyResult.Dismissed -> {
                    frequencyTracker.recordDisplay(surveyId)
                    eventTracker.track("survey_dismissed", mapOf(
                        "survey_id" to surveyId,
                        "questions_answered" to result.answeredCount
                    ))
                    // Fire onSurveyDismissed.
                    fireOnSurveyDismissed(surveyId)
                }
            }
        }
    }

    // Main-thread fan-out helpers. Read the host's delegate
    // fresh on every call so a delegate registered after configure still
    // receives callbacks. Mirrors iOS SurveyManager.swift dispatch pattern.

    private fun fireOnSurveyPresented(surveyId: String) {
        try {
            val delegate = AppDNA.surveys.surveyListener ?: return
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try { delegate.onSurveyPresented(surveyId) } catch (e: Throwable) {
                    Log.warning("AppDNASurveyDelegate.onSurveyPresented threw: ${e.message}")
                }
            }
        } catch (_: Throwable) { /* best-effort */ }
    }

    private fun fireOnSurveyCompleted(surveyId: String, answers: List<SurveyAnswer>) {
        try {
            val delegate = AppDNA.surveys.surveyListener ?: return
            val responses = answers.map {
                ai.appdna.sdk.SurveyResponse(
                    questionId = it.questionId,
                    answer = it.answer,
                    metadata = null,
                )
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try { delegate.onSurveyCompleted(surveyId, responses) } catch (e: Throwable) {
                    Log.warning("AppDNASurveyDelegate.onSurveyCompleted threw: ${e.message}")
                }
            }
        } catch (_: Throwable) { /* best-effort */ }
    }

    private fun fireOnSurveyDismissed(surveyId: String) {
        try {
            val delegate = AppDNA.surveys.surveyListener ?: return
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try { delegate.onSurveyDismissed(surveyId) } catch (e: Throwable) {
                    Log.warning("AppDNASurveyDelegate.onSurveyDismissed threw: ${e.message}")
                }
            }
        } catch (_: Throwable) { /* best-effort */ }
    }

    private fun trackCompleted(surveyId: String, config: SurveyConfig, answers: List<SurveyAnswer>) {
        eventTracker.track("survey_completed", mapOf(
            "survey_id" to surveyId,
            "survey_type" to config.surveyType,
            "answers" to answers.map { it.toMap() }
        ))
    }

    private fun submitResponse(surveyId: String, config: SurveyConfig, answers: List<SurveyAnswer>) {
        // Backend `/api/v1/feedback/responses` canonical
        // schema requires `user_id` + ISO8601 `completed_at`; the device
        // field is `device_type` (not `device`). Coordinated with iOS to
        // emit the same shape.
        // Go through the public
        // `AppDNA.getCurrentUserId()` accessor instead of reading the
        // plaintext SharedPreferences directly. Once LocalStorage migrates
        // identity into the encrypted store (`ai.appdna.sdk.secure`), the
        // plaintext file is wiped and the direct read returned null even
        // for identified users, which made `submitResponse` post
        // `"user_id": "anonymous"` for paying customers. iOS reads via
        // `AppDNA.currentUserId` (Keychain-backed); Android now mirrors.
        val userId = AppDNA.getCurrentUserId()
        val completedAt = run {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
            sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
            sdf.format(java.util.Date())
        }
        // Align body shape to iOS
        // SurveyManager.swift:154-167. iOS does NOT emit top-level
        // `survey_type` (that lives on the `survey_completed` event) nor
        // `sdk_version`/`platform` inside `context`; instead `platform` is
        // top-level. Drop the extras and move `platform` outward so backend
        // dedup/parity logic sees identical JSON across both natives.
        val body = JSONObject().apply {
            put("survey_id", surveyId)
            put("user_id", userId ?: "anonymous")
            put("platform", "android")
            put("answers", JSONArray(answers.map { JSONObject(it.toMap()) }))
            put("completed_at", completedAt)
            put("context", JSONObject().apply {
                put("app_version", getAppVersion())
                // `device_type` (not `device`) per backend schema.
                put("device_type", Build.MODEL)
                // Read the REAL per-install session counter (SessionManager.sessionsThisInstall), not the
                // dead `ai.appdna.sdk`/`session_count` pref that nothing writes — which reported 0 on every
                // response. Mirrors ScreenManager.evaluateTriggers.
                put("session_count", AppDNA.sessionManager?.sessionsThisInstall()?.toInt() ?: 0)
                put("days_since_install", daysSinceInstall())
            })
        }

        scope.launch(Dispatchers.IO) {
            try {
                apiClient?.post("/api/v1/feedback/responses", body.toString())
                Log.debug("Survey response submitted for $surveyId")
            } catch (e: Exception) {
                Log.error("Failed to submit survey response: ${e.message}")
            }
        }
    }

    private fun getAppVersion(): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"
        } catch (_: Exception) {
            "0.0.0"
        }
    }

    @Suppress("DEPRECATION")
    private fun daysSinceInstall(): Int {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val installTime = packageInfo.firstInstallTime
            val elapsed = System.currentTimeMillis() - installTime
            maxOf(0, (elapsed / 86_400_000).toInt())
        } catch (_: Exception) {
            0
        }
    }

    private fun executeFollowUp(config: SurveyConfig, answers: List<SurveyAnswer>) {
        val sentiment = determineSentiment(config, answers)
        val actions = config.followUpActions ?: return

        val action = when (sentiment) {
            SurveySentiment.POSITIVE -> actions.onPositive
            SurveySentiment.NEGATIVE -> actions.onNegative
            SurveySentiment.NEUTRAL -> actions.onNeutral
        } ?: return

        val surveyId = currentSurveyId ?: config.name

        /*
         * #897 — A STORE REVIEW PROMPT IS NEVER CONDITIONED ON THE ANSWER.
         *
         * The sentiment branch above is the right shape for every OTHER follow-up: a feedback form
         * for unhappy users and a thank-you for happy ones are ordinary product decisions. A store
         * review is not, and both stores say so in almost the same words.
         *
         *   Apple   don't ask the customer a question before, or while, presenting the rating
         *           prompt, and don't filter who sees it by how they answered
         *   Google  "Don't ask the user any questions before presenting the rating button or
         *           card, including questions about their opinion"
         *
         * `on_positive: {action: "prompt_review"}` is exactly that filter, and three production
         * surveys were authored that way — because the console invited it. So if ANY branch asks
         * for a review, every respondent who finishes the survey is eligible and the OS quota
         * decides the rest. The respondent's own branch still runs below, unchanged.
         *
         * Compliant by construction rather than by configuration: rows already written stay
         * useful instead of becoming broken, and a new one cannot reintroduce the gate.
         */
        val anyBranchAsksForReview = listOf(actions.onPositive, actions.onNegative, actions.onNeutral)
            .any { it?.action == "prompt_review" }
        if (anyBranchAsksForReview && action.action != "prompt_review") {
            ReviewPromptManager.triggerReview(context)
            eventTracker.track("survey_followup_prompt_review", mapOf(
                "survey_id" to surveyId,
                "sentiment" to sentiment.name.lowercase(),
            ))
        }

        // Gap #22 — Wire follow-up actions with event tracking so the host app
        // can respond to each follow-up regardless of native SDK availability.
        when (action.action) {
            "prompt_review" -> {
                // Attempt the native Google Play In-App Review flow first.
                ReviewPromptManager.triggerReview(context)
                // Also fire a trackable event so the host app can handle the case where
                // the native review flow is unavailable (e.g., emulator, debug builds).
                eventTracker.track("survey_followup_prompt_review", mapOf(
                    "survey_id" to surveyId,
                    "sentiment" to sentiment.name.lowercase(),
                ))
            }
            "show_feedback_form" -> {
                // Present the built-in feedback dialog.
                presentFeedbackForm(action.message, surveyId)
                // Fire a trackable event for host app awareness.
                eventTracker.track("survey_followup_feedback_form", mapOf(
                    "survey_id" to surveyId,
                    "sentiment" to sentiment.name.lowercase(),
                ))
            }
            "trigger_winback" -> {
                // Fire a trackable event for the host app to launch a winback campaign.
                eventTracker.track("survey_followup_winback", mapOf(
                    "survey_id" to surveyId,
                    "sentiment" to sentiment.name.lowercase(),
                    "message" to (action.message ?: ""),
                ))
            }
            "dismiss" -> { /* No-op */ }
        }
    }

    /**
     * The survey follow-up feedback form.
     *
     * 🔴 `surveyId` is a PARAMETER because `survey_feedback_submitted` declares `survey_id`
     * required (`src/modules/feedback-loop/events.ts`) and sent only `feedback` — so a funnel
     * grouped by survey came back empty for every Android respondent who typed something. The
     * catalog is what a customer reads to build that funnel; a required property that never
     * arrives is worse than an absent one, because the query looks right.
     */
    private fun presentFeedbackForm(message: String?, surveyId: String?) {
        scope.launch(Dispatchers.Main) {
            val activity = getCurrentActivity() ?: run {
                Log.warning("No activity available for feedback form presentation")
                return@launch
            }

            val input = EditText(activity).apply {
                hint = "Tell us what you think..."
                maxLines = 4
            }
            val container = FrameLayout(activity).apply {
                val padding = (16 * activity.resources.displayMetrics.density).toInt()
                setPadding(padding, 0, padding, 0)
                addView(input)
            }

            val dialog = AlertDialog.Builder(activity)
                .setTitle(message ?: "We'd love your feedback")
                .setMessage("What could we do better?")
                .setView(container)
                .setPositiveButton("Submit") { _, _ ->
                    val feedback = input.text.toString()
                    eventTracker.track("survey_feedback_submitted", mapOf(
                        "survey_id" to (surveyId ?: ""),
                        "feedback" to feedback
                    ))
                    // Emit canonical `feedback_form_submitted`
                    // event so backend Growth Memory + reports surface it
                    // independently of the survey-followup tracking shape.
                    // Payload shape mirrors iOS
                    // SurveyManager.swift:223-225 exactly (`feedback` only) so
                    // backend dedup/parity logic sees identical events
                    // cross-platform.
                    eventTracker.track("feedback_form_submitted", mapOf("feedback" to feedback))
                    // Submitting ENDS the form, so the dismiss listener below must not also
                    // record it as a dismissal (mirrors iOS `feedbackFormResolved = true`).
                    feedbackFormResolved = true
                }
                .setNegativeButton("Cancel") { _, _ -> resolveFeedbackForm("user") }
                .create()

            /*
             * #898 — the dialog goes when its activity goes.
             *
             * Skipping a finishing activity above stops the common case; this stops the race that
             * remains. An activity can finish between `show()` and the user's next tap — the host
             * navigating away, a deep link, the system reclaiming it — and a dialog still attached
             * then leaks the whole Activity, its views and whatever the survey captured.
             *
             * The callback unregisters itself, so nothing accumulates across surveys.
             */
            val app = activity.application
            val lifecycle = object : android.app.Application.ActivityLifecycleCallbacks {
                override fun onActivityDestroyed(destroyed: Activity) {
                    if (destroyed !== activity) return
                    // BEFORE `dismiss()`: dismissing fires the listener below, and whichever
                    // reason reaches the latch first is the one recorded. The form was taken
                    // away, not dismissed, so this reason has to win.
                    resolveFeedbackForm("presenter_released")
                    if (dialog.isShowing) dialog.dismiss()
                    app.unregisterActivityLifecycleCallbacks(this)
                }
                override fun onActivityCreated(a: Activity, b: android.os.Bundle?) {}
                override fun onActivityStarted(a: Activity) {}
                override fun onActivityResumed(a: Activity) {}
                override fun onActivityPaused(a: Activity) {}
                override fun onActivityStopped(a: Activity) {}
                override fun onActivitySaveInstanceState(a: Activity, b: android.os.Bundle) {}
            }
            app.registerActivityLifecycleCallbacks(lifecycle)
            dialog.setOnDismissListener {
                // A back press and a tap outside the dialog reach NEITHER button — on iOS an
                // alert cannot be dismissed that way, so those two paths were Android-only and
                // recorded nothing at all.
                resolveFeedbackForm("user")
                app.unregisterActivityLifecycleCallbacks(lifecycle)
            }
            feedbackFormResolved = false
            dialog.show()
        }
    }

    private fun getCurrentActivity(): Activity? {
        // Use reflection to get the current activity from ActivityThread, matching iOS's
        // pattern of getting the topmost view controller via UIApplication.shared
        return try {
            val activityThread = Class.forName("android.app.ActivityThread")
            val currentMethod = activityThread.getMethod("currentActivityThread")
            val thread = currentMethod.invoke(null)
            val activitiesField = activityThread.getDeclaredField("mActivities")
            activitiesField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val activities = activitiesField.get(thread) as? android.util.ArrayMap<Any, Any>
            /*
             * #898 — PICK THE BEST ACTIVITY, in two passes. `paused` alone is not enough, and
             * neither is simply rejecting a finishing one.
             *
             * A follow-up runs as the SurveyActivity is going away, and its pause had TIMED OUT
             * rather than completed ("Activity pause timeout for … SurveyActivity" in logcat), so
             * the record still read un-paused and the feedback dialog was built on an activity
             * already finishing. Its window then outlived it: `android.view.WindowLeaked …
             * DecorView[SurveyActivity]`.
             *
             * 🔴 Rejecting a finishing activity and stopping there is WORSE, and the device said
             * so within the minute: at that moment the only un-paused record IS the dying survey
             * activity — the host underneath is paused because the survey was in front — so the
             * filter left nothing and the log read "No activity available for feedback form
             * presentation". The leak was gone because the feature was gone.
             *
             * So: prefer un-paused AND alive; fall back to any ALIVE activity, which is the
             * paused host about to be resumed. A dialog shown on it is correct — that is where a
             * post-survey form belongs. Only a process with no live activity at all gets null.
             */
            val live = mutableListOf<Pair<Boolean, Activity>>() // (isPaused, activity)
            activities?.values?.forEach { record ->
                val pausedField = record.javaClass.getDeclaredField("paused")
                pausedField.isAccessible = true
                val activityField = record.javaClass.getDeclaredField("activity")
                activityField.isAccessible = true
                val activity = activityField.get(record) as? Activity ?: return@forEach
                if (activity.isFinishing || activity.isDestroyed) return@forEach
                live += pausedField.getBoolean(record) to activity
            }
            live.firstOrNull { !it.first }?.second ?: live.firstOrNull()?.second
        } catch (e: Exception) {
            Log.warning("Failed to get current activity: ${e.message}")
            null
        }
    }

    private fun determineSentiment(config: SurveyConfig, answers: List<SurveyAnswer>): SurveySentiment {
        val first = answers.firstOrNull() ?: return SurveySentiment.NEUTRAL
        val scale = config.questions
            .firstOrNull { it.id == first.questionId }
            ?.emojiConfig?.emojis
        return SurveySentimentRule.resolve(config.surveyType, first.answer, scale)
    }

    private fun evaluateConditions(conditions: List<TriggerCondition>, properties: Map<String, Any>): Boolean {
        if (conditions.isEmpty()) return true
        return conditions.all { condition ->
            val propValue = properties[condition.field] ?: return@all false
            evaluateOperator(condition.operator, propValue, condition.value)
        }
    }

    private fun evaluateOperator(op: String, propValue: Any, condValue: Any?): Boolean {
        condValue ?: return false
        return when (op) {
            "eq" -> "$propValue" == "$condValue"
            "gte" -> toDouble(propValue)?.let { p -> toDouble(condValue)?.let { c -> p >= c } } ?: false
            "lte" -> toDouble(propValue)?.let { p -> toDouble(condValue)?.let { c -> p <= c } } ?: false
            "gt" -> toDouble(propValue)?.let { p -> toDouble(condValue)?.let { c -> p > c } } ?: false
            "lt" -> toDouble(propValue)?.let { p -> toDouble(condValue)?.let { c -> p < c } } ?: false
            "contains" -> "$propValue".contains("$condValue")
            else -> false
        }
    }

    private fun toDouble(value: Any): Double? {
        return when (value) {
            is Number -> value.toDouble()
            is String -> value.toDoubleOrNull()
            else -> null
        }
    }

    private fun meetsLoveScoreRange(range: ScoreRange?): Boolean {
        range ?: return true
        val prefs = context.getSharedPreferences("ai.appdna.sdk", Context.MODE_PRIVATE)
        val loveScore = prefs.getInt("love_score", 0)
        return loveScore in range.min..range.max
    }

    private fun meetsMinSessions(minSessions: Int?): Boolean {
        val min = minSessions ?: return true
        if (min <= 0) return true
        // Read the REAL per-install session counter, not the dead `session_count` pref that nothing
        // writes — which made every `min_sessions >= 1` survey trigger permanently unsatisfiable.
        return (AppDNA.sessionManager?.sessionsThisInstall()?.toInt() ?: 0) >= min
    }
}

sealed class SurveyResult {
    data class Completed(val answers: List<SurveyAnswer>) : SurveyResult()
    data class Dismissed(val answeredCount: Int) : SurveyResult()
}
