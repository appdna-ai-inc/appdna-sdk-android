package ai.appdna.sdk.onboarding

import ai.appdna.sdk.Log
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

/**
 * What the user has entered on a step and not submitted yet: the values of its `input_*` blocks and
 * toggles, or the fields of a `form` step.
 *
 * These used to live in composition `remember` state, which is rebuilt when the activity is
 * recreated — a rotation, or a restore after the OS ended the process — so the fields came back
 * empty. A draft lives in the ViewModel instead, which survives a configuration change, and the
 * draft of the step on screen is written into the saved-state Bundle beside the resume state, so it
 * also survives process death.
 *
 * A draft is NOT an answer. It never reaches `responses`, events or the session store; the step
 * submits its values the usual way when the user continues.
 */
internal class StepDraft(val stepId: String) {
    /** The block-based step's `inputValues`. */
    val inputs: SnapshotStateMap<String, Any> = mutableStateMapOf()

    /** The block-based step's toggle values. */
    val toggles: MutableMap<String, Boolean> = mutableMapOf()

    /** A `form` step's field values. */
    val form: SnapshotStateMap<String, Any?> = mutableStateMapOf()

    /**
     * False until the step seeded [inputs] and [toggles] from its submitted answers on first show. A
     * draft brought back after a recreation is already seeded: it holds the seeds and the edits.
     */
    var seeded: Boolean = false

    /** Compositions currently showing this draft. */
    internal var holders = 0
}

/**
 * The drafts of the steps being shown. A draft is kept while its step is on screen, and across a
 * configuration change; it is dropped when its step leaves the screen any other way, so a step the
 * user comes back to starts from its submitted answers, as before.
 */
internal class StepDrafts {
    private val drafts = mutableMapOf<String, StepDraft>()

    /**
     * The draft for [stepId]: the kept one when there is one, else a new, unseeded one. Registered at
     * once, so two compositions of the same step in one frame (a transition, a measuring pass) share
     * one draft rather than each typing into its own.
     */
    fun draftFor(stepId: String): StepDraft = drafts.getOrPut(stepId) { StepDraft(stepId) }

    internal fun hold(draft: StepDraft) {
        drafts.putIfAbsent(draft.stepId, draft)
        draft.holders += 1
    }

    /**
     * A composition showing [draft] left. [keep] is true when the activity is being recreated for a
     * configuration change: the next composition picks the draft up again.
     */
    internal fun release(draft: StepDraft, keep: Boolean) {
        draft.holders = (draft.holders - 1).coerceAtLeast(0)
        if (draft.holders == 0 && !keep && drafts[draft.stepId] === draft) drafts.remove(draft.stepId)
    }

    fun peek(stepId: String): StepDraft? = drafts[stepId]

    fun clear() = drafts.clear()

    /**
     * Write the draft of [step], the step on screen, for a process that may be ended. Values that
     * are secrets (the step's password and one-time-code fields) are left out: the saved state
     * leaves the process. So are values too large for the Bundle, such as a drawn signature.
     */
    fun writeTo(out: Bundle, step: OnboardingStep) {
        val draft = drafts[step.id] ?: return
        encode(draft, AuthSecretRedactor.secretFieldIds(step))?.let { out.putString(KEY_STEP_DRAFT, it) }
    }

    /** Put back a draft saved by [writeTo], when it belongs to [stepId]. */
    fun restore(json: String?, stepId: String) {
        val draft = decode(json, stepId) ?: return
        drafts[stepId] = draft
    }

    companion object {
        const val KEY_STEP_DRAFT = "appdna_onboarding_step_draft"

        /** One value larger than this is not saved for process death. */
        internal const val MAX_VALUE_CHARS = 8 * 1024

        /** The whole draft is kept below this, smallest values first. */
        internal const val MAX_DRAFT_CHARS = 32 * 1024

        internal fun encode(draft: StepDraft, secrets: Set<String>): String? {
            val inputs = bounded(draft.inputs.filterKeys { it !in secrets }, draft.stepId)
            val form = bounded(draft.form.filterKeys { it !in secrets }, draft.stepId)
            if (inputs.length() == 0 && form.length() == 0 && draft.toggles.isEmpty()) return null
            val obj = org.json.JSONObject()
            obj.put("step", draft.stepId)
            obj.put("inputs", inputs)
            obj.put("toggles", org.json.JSONObject(draft.toggles.toMap()))
            obj.put("form", form)
            return obj.toString()
        }

        private fun bounded(values: Map<String, Any?>, stepId: String): org.json.JSONObject {
            val encoded = values.mapNotNull { (k, v) ->
                if (v == null) return@mapNotNull null
                val wrapped = org.json.JSONObject.wrap(v) ?: return@mapNotNull null
                Triple(k, wrapped, org.json.JSONObject().put(k, wrapped).toString().length)
            }.sortedBy { it.third }
            val out = org.json.JSONObject()
            var total = 0
            val dropped = mutableListOf<String>()
            for ((k, wrapped, size) in encoded) {
                if (size > MAX_VALUE_CHARS || total + size > MAX_DRAFT_CHARS) {
                    dropped += k
                    continue
                }
                out.put(k, wrapped)
                total += size
            }
            if (dropped.isNotEmpty()) {
                Log.info("Onboarding step $stepId: ${dropped.size} unsubmitted value(s) too large to keep if the app is closed: $dropped")
            }
            return out
        }

        @Suppress("UNCHECKED_CAST")
        internal fun decode(json: String?, stepId: String): StepDraft? {
            if (json.isNullOrEmpty()) return null
            return try {
                val obj = org.json.JSONObject(json)
                if (obj.optString("step") != stepId) return null
                val draft = StepDraft(stepId)
                (fromJsonAny(obj.optJSONObject("inputs")) as? Map<String, Any?>)?.forEach { (k, v) ->
                    if (v != null) draft.inputs[k] = v
                }
                (fromJsonAny(obj.optJSONObject("form")) as? Map<String, Any?>)?.forEach { (k, v) ->
                    if (v != null) draft.form[k] = v
                }
                (fromJsonAny(obj.optJSONObject("toggles")) as? Map<String, Any?>)?.forEach { (k, v) ->
                    if (v is Boolean) draft.toggles[k] = v
                }
                draft.seeded = true
                draft
            } catch (e: Exception) {
                Log.warning("Onboarding step $stepId: the unsubmitted values could not be read back (${e.message}); showing the step without them.")
                null
            }
        }
    }
}

/** The drafts of the flow on screen; null where no activity hosts the flow (previews, tests). */
internal val LocalStepDrafts = staticCompositionLocalOf<StepDrafts?> { null }

/**
 * The draft of [stepId] for this composition, or null when no [LocalStepDrafts] is provided — the
 * caller then keeps its values in composition, as before.
 */
@Composable
internal fun rememberStepDraft(stepId: String): StepDraft? {
    val drafts = LocalStepDrafts.current ?: return null
    val activity = LocalContext.current.findActivity()
    val draft = remember(drafts, stepId) { drafts.draftFor(stepId) }
    DisposableEffect(drafts, draft) {
        drafts.hold(draft)
        onDispose { drafts.release(draft, keep = activity?.isChangingConfigurations == true) }
    }
    return draft
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
