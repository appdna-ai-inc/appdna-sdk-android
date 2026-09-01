package ai.appdna.sdk.onboarding

/**
 * A CTA that records the user's choice without leaving the flow.
 *
 * WHY THIS EXISTS: a summary or upsell step offers something the app must act on *later* — "book a
 * tasting", "start the trial", "talk to a human". Routing there the moment the button is tapped
 * abandons an onboarding the user is halfway through, and the host then has to rebuild the flow's
 * position by hand to bring them back. A flag CTA instead writes one key, advances exactly like
 * `next`, and lets the host route once, at `onOnboardingCompleted`, when the flow is finished.
 *
 * AUTHORING: the console writes the pair into the button's existing `action_value` — the same
 * single-parameter slot `link` uses for its URL and `permission` for its type. One flag per CTA;
 * a step with three offers uses three CTAs.
 *
 * Mirrors iOS `OnboardingCTAFlag.swift` exactly, and the shared fixtures
 * `onboarding/cta_flag_*.fixture.json` assert both produce the same pair — a divergence in the
 * split rule has to break a test rather than reach a device.
 */
internal object OnboardingCTAFlag {

    /** The `action` value the console writes and every SDK switches on. */
    const val ACTION_NAME = "flag"

    /**
     * Where an aggregated copy of every flag set during the flow is placed in the completion
     * `responses` map.
     *
     * Step answers are namespaced by step id (`responses["step9"]["wants_upsell"]`), which is right
     * for answers and wrong for flags: a host routing after completion would have to walk every step
     * of every flow to discover whether any CTA was flagged. Flags are therefore ALSO collected flat
     * under this one well-known key. The per-step copy is still written — nothing is moved — so
     * next-step rules and `{{responses.*}}` templates see the flag where they see every other answer.
     */
    const val RESPONSES_KEY = "flags"

    data class Flag(val key: String, val value: String)

    /**
     * Parse the authored `action_value`.
     *
     * - `"wants_upsell"` -> key `wants_upsell`, value `"true"`
     * - `"upsell_choice=booking"` -> key `upsell_choice`, value `"booking"`
     *
     * Splits on the FIRST `=` only, so a value may itself contain one (`utm=a=b` -> value `a=b`).
     * Returns null for a missing or key-less string; the caller advances anyway rather than leaving
     * the user on a button that appears to do nothing.
     */
    fun parse(actionValue: String?): Flag? {
        val raw = actionValue?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val sep = raw.indexOf('=')
        if (sep < 0) {
            // No value authored. `"true"` and not a Boolean: the value crosses to iOS, Flutter and
            // React Native through JSON and lands in analytics props, and a String is the one
            // representation all four agree on. A host reads `== "true"`.
            return Flag(raw, "true")
        }
        val key = raw.substring(0, sep).trim()
        val value = raw.substring(sep + 1).trim()
        if (key.isEmpty()) return null
        // `"key="` — an author who cleared the value field means the plain flag, not an empty string
        // that every `flags["k"] != null` check would still treat as set while `== "true"` fails.
        return Flag(key, value.ifEmpty { "true" })
    }

    /**
     * THE fold: given the flow-level responses, the step that just completed and the data it
     * produced, return the responses with any flag that step's CTAs set collected flat under
     * `flags`.
     *
     * One function rather than "scan here, merge there" because both the live flow host and the
     * shared-fixture runner must exercise the SAME logic — a fixture that reimplemented the scan
     * would assert its own wiring and stay green with the SDK's copy deleted.
     *
     * Which keys count as flags comes from the step's CTA CONFIG, not from the data map. Reading the
     * map would let a form field named `flags` — or one whose id happened to match a flag key —
     * write into the bucket the host makes routing decisions on.
     */
    fun applyTo(
        responses: Map<String, Any>,
        step: OnboardingStep,
        stepData: Map<String, Any>,
    ): Map<String, Any> {
        val flagKeys = step.config.content_blocks.orEmpty()
            .filter { it.action == ACTION_NAME }
            .mapNotNull { parse(it.action_value)?.key }
            .toSet()
        if (flagKeys.isEmpty()) return responses
        return merge(responses, stepData.filterKeys { it in flagKeys })
    }

    /**
     * Fold a step's flag keys into the flat `flags` bucket of the flow-level responses map.
     * Internal to [applyTo]; exposed only because the iOS mirror is, and the fixtures pin both.
     */
    fun merge(responses: Map<String, Any>, flags: Map<String, Any>): Map<String, Any> {
        if (flags.isEmpty()) return responses
        val out = responses.toMutableMap()
        @Suppress("UNCHECKED_CAST")
        val bucket = (out[RESPONSES_KEY] as? Map<String, Any>)?.toMutableMap() ?: mutableMapOf()
        bucket.putAll(flags)
        out[RESPONSES_KEY] = bucket
        return out
    }
}

/**
 * The input key a Summary Screen stat reads and writes.
 *
 * A stat authored with an `input` but no `field_id` used to render as an EMPTY CARD, and its
 * `required` was silently dropped with it: the renderer and the required gate both skipped the stat
 * on the missing id, so the author saw a blank box where a control belonged and a requirement that
 * gated nothing. Deriving a stable id from the block and the stat's position makes the control
 * appear and the requirement real.
 *
 * The renderer and the gate MUST derive this identically. If they drift, the gate blocks on a key
 * nothing writes and the step cannot be advanced at all. iOS `summaryStatFieldId` is the same rule.
 */
internal fun summaryStatFieldId(blockId: String, index: Int, stat: Map<*, *>): String {
    val authored = stat["field_id"] as? String
    if (!authored.isNullOrEmpty()) return authored
    return "${blockId}_stat_${index}"
}
