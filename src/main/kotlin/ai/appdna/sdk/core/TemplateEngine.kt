package ai.appdna.sdk.core

import ai.appdna.sdk.AppDNA
import ai.appdna.sdk.onboarding.LocationAnswer

/**
 * Which surface a template is drawn on. It decides what a Google location answer resolves to.
 *
 * Google's terms require attribution in the same visual container wherever a Google answer's content
 * is shown without a Google map. An onboarding step draws that attribution itself, in its container
 * (`GoogleCredit`), so onboarding keeps the answer's values. No other surface can: a push has no
 * container at all, and a paywall, message or survey would need attribution UI of its own. So
 * everywhere else a Google answer resolves to `raw_query`, the text the user typed, which is not
 * Google content.
 */
enum class TemplateSurface {
    /** An onboarding step's own text: the answer's values, with the step's credit. */
    ONBOARDING,

    /** Every other surface, and anything sent off the device: a Google answer's typed text. */
    OTHER,
}

/**
 * Context for variable resolution across all SDK modules.
 */
data class TemplateContext(
    val userTraits: Map<String, Any>?,
    val remoteConfig: (String) -> String?,
    val onboardingResponses: Map<String, Map<String, Any>>,
    val computedData: Map<String, Any>,
    val sessionData: Map<String, Any>,
    val deviceInfo: Map<String, String>,
    /** Where the template is drawn. A context built by hand defaults to the restrictive surface. */
    val surface: TemplateSurface = TemplateSurface.OTHER,
)

/**
 * Shared template interpolation engine for all SDK modules.
 * Resolves `{{namespace.key}}` and `{{namespace.key | fallback}}` variables.
 */
object TemplateEngine {

    private val regex = Regex("\\{\\{([^}|]+)(?:\\|([^}]*))?\\}\\}")

    /**
     * The variable path of every `{{…}}` token [interpolate] resolves in [value], trimmed as it trims
     * them. Wider than the raw host-data pass's scanner: a path may hold a space.
     */
    internal fun tokenPaths(value: String): List<String> =
        if (!value.contains("{{")) emptyList() else regex.findAll(value).map { it.groupValues[1].trim() }.toList()

    /**
     * Build a TemplateContext from current SDK state.
     *
     * [surface] has no default on purpose: every caller states where its text goes. Only an
     * onboarding step's own text passes [TemplateSurface.ONBOARDING]; a paywall, message, survey,
     * push, or a value sent to the customer's server (a hook header) passes [TemplateSurface.OTHER].
     */
    fun buildContext(surface: TemplateSurface): TemplateContext {
        val identity = AppDNA.getIdentityRef()
        val sessionStore = SessionDataStore.instance

        return TemplateContext(
            userTraits = identity?.traits,
            remoteConfig = { key -> AppDNA.getRemoteConfigFlag(key) },
            onboardingResponses = sessionStore?.onboardingResponses ?: emptyMap(),
            computedData = sessionStore?.computedData ?: emptyMap(),
            sessionData = sessionStore?.sessionData ?: emptyMap(),
            deviceInfo = deviceInfo(),
            surface = surface,
        )
    }

    /**
     * Interpolate all `{{...}}` variables in a string.
     */
    @JvmOverloads
    fun interpolate(value: String, context: TemplateContext, key: String? = null, isOption: Boolean = false): String {
        if (!value.contains("{{")) return value // Fast path
        // On an onboarding step a token the typed-text rule covers (a stored value, a media URL off
        // Google's hosts, a markdown link's target) resolves as on every other surface: a Google answer
        // to its typed text. [key] / [isOption]: where the string sits.
        val rule = if (context.surface == TemplateSurface.ONBOARDING) {
            ai.appdna.sdk.onboarding.HostDataResolver.typedTextRule(value, key, isOption)
        } else {
            null
        }
        val typedContext by lazy { context.copy(surface = TemplateSurface.OTHER) }

        return regex.replace(value) { match ->
            val varPath = match.groupValues[1].trim()
            val fallback = match.groupValues.getOrNull(2)?.trim() ?: ""
            val tokenContext = if (rule?.covers(match.range.first) == true) typedContext else context
            resolveVariable(varPath, tokenContext) ?: fallback
        }
    }

    /**
     * Interpolate multiple string fields at once.
     */
    fun interpolateFields(fields: List<String?>, context: TemplateContext): List<String?> {
        return fields.map { field ->
            field?.let { interpolate(it, context) }
        }
    }

    /**
     * The per-token entry point the onboarding raw host-data pass routes every
     * NON-block root through (`device`, `computed`, `remote_config`, `input`, bare names), so an old
     * published flow keeps rendering exactly as it did when `loc()` interpolated it. Same semantics as
     * [interpolate] for one token: null on a miss (the caller applies the `| fallback`, else "").
     */
    internal fun resolveToken(path: String, context: TemplateContext): String? =
        resolveVariable(path.trim(), context)

    // MARK: - Variable Resolution

    private fun resolveVariable(path: String, context: TemplateContext): String? {
        val parts = path.split(".", limit = 3)
        val namespace = parts.firstOrNull() ?: return null

        return when (namespace) {
            "user" -> {
                if (parts.size < 2) return null
                context.userTraits?.get(parts[1])?.let(::stringify)
            }

            "remote_config" -> {
                if (parts.size < 2) return null
                context.remoteConfig(parts[1])
            }

            "onboarding", "responses" -> {
                // onboarding.stepId.fieldId
                if (parts.size < 3) {
                    // Try two-part path with dot in remaining
                    if (parts.size < 2) return null
                    val remaining = path.removePrefix("onboarding.")
                    val subParts = remaining.split(".", limit = 2)
                    if (subParts.size < 2) return null
                    context.onboardingResponses[subParts[0]]?.get(subParts[1])?.let { answerText(it, context) }
                } else {
                    val step = context.onboardingResponses[parts[1]] ?: return null
                    step[parts[2]]?.let { return answerText(it, context) }
                    // #1158 — `{{onboarding.<step>.<field>.city}}`: the path is split at most three ways, so a key
                    // inside a structured answer (a location selection) arrives as `field.city`. Walk it.
                    val keys = parts[2].split(".")
                    var value: Any? = step[keys[0]]
                    for (key in keys.drop(1)) {
                        // A key inside a Google answer, off onboarding: the user's typed text instead.
                        if (context.surface == TemplateSurface.OTHER && LocationAnswer.carriesPlaceId(value)) {
                            return typedText(value)
                        }
                        value = (value as? Map<*, *>)?.get(key)
                    }
                    value?.let { answerText(it, context) }
                }
            }

            "computed" -> {
                if (parts.size < 2) return null
                context.computedData[parts[1]]?.let(::stringify)
            }

            "session" -> {
                if (parts.size < 2) return null
                context.sessionData[parts[1]]?.let(::stringify)
            }

            "device" -> {
                if (parts.size < 2) return null
                context.deviceInfo[parts[1]]
            }

            "input" -> {
                // `{{input.fieldId}}`
                // shorthand. Mirrors iOS TemplateEngine.swift:107-116:
                // searches all onboardingResponses step dicts for the
                // first matching fieldId; first match wins. Used for
                // cross-step references in console copy
                // (e.g. `Welcome, {{input.first_name}}!`).
                if (parts.size < 2) return null
                val fieldId = parts[1]
                for ((_, stepData) in context.onboardingResponses) {
                    stepData[fieldId]?.let { return answerText(it, context) }
                }
                null
            }

            else -> {
                // Legacy: bare variable name → remote config (backward compat)
                context.remoteConfig(path)
            }
        }
    }

    // MARK: - Helpers

    /**
     * An onboarding answer as template text. Off onboarding, a Google answer (one carrying
     * `place_id`) is its `raw_query`, and a step or list holding one has it replaced the same way
     * before it is stringified — so the whole-answer token never prints Google content either.
     */
    private fun answerText(value: Any, context: TemplateContext): String? {
        if (context.surface != TemplateSurface.OTHER) return stringify(value)
        if (LocationAnswer.carriesPlaceId(value)) return typedText(value)
        return stringify(typedTextForGoogleAnswers(value))
    }

    /** A Google answer's `raw_query`, or null when it has none (the token then takes its fallback). */
    internal fun typedText(answer: Any?): String? =
        ((answer as? Map<*, *>)?.get("raw_query") as? String)?.takeIf { it.isNotEmpty() }

    /** [value] with every Google answer inside it, at any depth, replaced by its `raw_query`. */
    internal fun typedTextForGoogleAnswers(value: Any): Any = when {
        LocationAnswer.carriesPlaceId(value) -> typedText(value) ?: ""
        value is Map<*, *> -> value.mapValues { (_, v) -> v?.let { typedTextForGoogleAnswers(it) } }
        value is List<*> -> value.map { v -> v?.let { typedTextForGoogleAnswers(it) } }
        else -> value
    }

    private fun stringify(value: Any): String {
        return when (value) {
            is String -> value
            is Boolean -> value.toString()
            is Number -> {
                // Match iOS NSNumber.stringValue: a whole-valued Double/Float renders
                // WITHOUT the trailing ".0" ("30", not "30.0") in interpolated copy (paywall/message/
                // onboarding titles like "{{input.goal_weight}} lbs"). An onboarding number answer is
                // stored as a Double, so this was Android-only ".0" on conversion copy in the first session.
                // ONLY Double/Float — integer types (Int/Long) must NOT round-trip through Double: a Long
                // beyond 2^53 (a snowflake id / ns timestamp in {{session.*}}) would lose precision. iOS
                // never round-trips int64 through Double either.
                if (value is Double || value is Float) {
                    val d = value.toDouble()
                    if (d.isFinite() && d == d.toLong().toDouble()) d.toLong().toString() else value.toString()
                } else {
                    value.toString()
                }
            }
            else -> value.toString()
        }
    }

    private fun deviceInfo(): Map<String, String> {
        val info = mutableMapOf(
            "platform" to "android",
            // Sibling — match the EventSchema 3-part dotted
            // shape ("14.0.1") iOS emits, not the bare integer ("14") that
            // Build.VERSION.RELEASE returns. Without this, console copy
            // referencing {{device.os_version}} renders as "14" on Android
            // and "14.0.1" on iOS.
            "os_version" to ai.appdna.sdk.events.EventSchema.normalizeOsVersion(
                android.os.Build.VERSION.RELEASE,
            ),
            "locale" to java.util.Locale.getDefault().language,
        )
        java.util.Locale.getDefault().country.takeIf { it.isNotEmpty() }?.let {
            info["country"] = it
        }
        return info
    }
}

/**
 * Convenience extension to interpolate {{variables}} in onboarding text fields. Internal: it draws on
 * the onboarding surface, where the step's container credits Google, so no host code may call it for
 * text drawn anywhere else.
 */
internal fun String.interpolated(): String = interpolated(key = null)

/**
 * [key]: the field this text is, for the typed-text rule; null for drawn text, where only a markdown
 * link's target takes a Google answer's typed text.
 */
internal fun String.interpolated(key: String?): String {
    if (!this.contains("{{")) return this
    // An onboarding step's own text: the step's container draws the Google credit (`GoogleCredit`).
    return interpolated(key, TemplateEngine.buildContext(TemplateSurface.ONBOARDING))
}

/** [interpolated] against [context] — the SDK state it reads, as one value. */
internal fun String.interpolated(key: String?, context: TemplateContext): String =
    if (!this.contains("{{")) this else TemplateEngine.interpolate(this, context, key)
