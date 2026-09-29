package ai.appdna.sdk.core

import ai.appdna.sdk.AppDNA

/**
 * Context for variable resolution across all SDK modules (SPEC-088).
 */
data class TemplateContext(
    val userTraits: Map<String, Any>?,
    val remoteConfig: (String) -> String?,
    val onboardingResponses: Map<String, Map<String, Any>>,
    val computedData: Map<String, Any>,
    val sessionData: Map<String, Any>,
    val deviceInfo: Map<String, String>
)

/**
 * Shared template interpolation engine for all SDK modules (SPEC-088).
 * Resolves `{{namespace.key}}` and `{{namespace.key | fallback}}` variables.
 */
object TemplateEngine {

    private val regex = Regex("\\{\\{([^}|]+)(?:\\|([^}]*))?\\}\\}")

    /**
     * Build a TemplateContext from current SDK state.
     */
    fun buildContext(): TemplateContext {
        val identity = AppDNA.getIdentityRef()
        val sessionStore = SessionDataStore.instance

        return TemplateContext(
            userTraits = identity?.traits,
            remoteConfig = { key -> AppDNA.getRemoteConfigFlag(key) },
            onboardingResponses = sessionStore?.onboardingResponses ?: emptyMap(),
            computedData = sessionStore?.computedData ?: emptyMap(),
            sessionData = sessionStore?.sessionData ?: emptyMap(),
            deviceInfo = deviceInfo()
        )
    }

    /**
     * Interpolate all `{{...}}` variables in a string.
     */
    fun interpolate(value: String, context: TemplateContext): String {
        if (!value.contains("{{")) return value // Fast path

        return regex.replace(value) { match ->
            val varPath = match.groupValues[1].trim()
            val fallback = match.groupValues.getOrNull(2)?.trim() ?: ""
            resolveVariable(varPath, context) ?: fallback
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
     * SPEC-496 §A5 — the per-token entry point the onboarding raw host-data pass routes every
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
                    context.onboardingResponses[subParts[0]]?.get(subParts[1])?.let(::stringify)
                } else {
                    context.onboardingResponses[parts[1]]?.get(parts[2])?.let(::stringify)
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
                // SPEC-070-A finalization parity audit R5 — `{{input.fieldId}}`
                // shorthand. Mirrors iOS TemplateEngine.swift:107-116:
                // searches all onboardingResponses step dicts for the
                // first matching fieldId; first match wins. Used for
                // cross-step references in console copy
                // (e.g. `Welcome, {{input.first_name}}!`).
                if (parts.size < 2) return null
                val fieldId = parts[1]
                for ((_, stepData) in context.onboardingResponses) {
                    stepData[fieldId]?.let { return stringify(it) }
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

    private fun stringify(value: Any): String {
        return when (value) {
            is String -> value
            is Boolean -> value.toString()
            is Number -> {
                // Round-28/29 — match iOS NSNumber.stringValue: a whole-valued Double/Float renders
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
            // SPEC-070-A G.15 sibling — match the EventSchema 3-part dotted
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
 * SPEC-087: Convenience extension to interpolate {{variables}} in onboarding text fields.
 */
fun String.interpolated(): String {
    if (!this.contains("{{")) return this
    val ctx = TemplateEngine.buildContext()
    return TemplateEngine.interpolate(this, ctx)
}
