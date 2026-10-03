package ai.appdna.sdk.onboarding

/**
 * The decision seam for a `permission` CTA.
 *
 * WHY THIS FILE EXISTS: the permission action had NO observable seam on Android. The whole
 * behavior lived in `runPermissionPipeline()`, a closure inside `@Composable OnboardingFlowHost`
 * that read the type straight out of `layout.permission_type` and went off to talk to the OS. Two
 * consequences:
 *
 *  1. The host could not observe that a permission element had been ACTED ON at all. Every other
 *     button on a step emits `{action, action_value, ...inputValues}` through `onNext` — which is
 *     what `onBeforeStepAdvance` receives, and what the cross-platform fixtures call `onAction`
 *     (see [emitAuthAction]). The permission button emitted nothing, so a host that wanted to log,
 *     branch on, or A/B the permission ask had no callback to hang it on. iOS's `handleBlockAction`
 *     (`OnboardingRenderer.swift` `case "permission"`) routes the tap through the same
 *     action-dispatch surface as every other CTA; Android didn't.
 *
 *  2. The SAFE FALLBACK — an unsupported / unauthorable permission type must still ADVANCE rather
 *     than dead-end the flow on a button that does nothing — was unreachable from a unit test, so
 *     nothing proved it. A dead CTA in an onboarding flow is a total funnel stop.
 *
 * The decision (which type, is it actionable, do we prompt or advance) is now pure and lives here.
 * The OS work (status, prompt, settings fallback) stays in the composable, which is the only place
 * that can own an `ActivityResultLauncher`.
 */

/**
 * The action string a permission CTA reports to the host. Matches iOS
 * (`OnboardingRenderer.swift` `case "permission"`) exactly — it is the console-authored
 * `button.action` value, so it is the contract, never localized and never renamed.
 */
internal const val PERMISSION_ACTION = "permission"

/** The key the resolved permission type travels under, matching [emitAuthAction]'s payload shape. */
internal const val PERMISSION_ACTION_VALUE_KEY = "action_value"

/** What the caller must do after a permission CTA is tapped. */
internal sealed class PermissionActionDecision {
    /**
     * The type is real and supported → run the OS pipeline (host pre-hook → status → prompt).
     * The pipeline owns the advance, because it must only happen once the OS has answered.
     */
    data class RunPipeline(val type: String) : PermissionActionDecision()

    /**
     * The type is missing, unauthorable or unsupported on Android (e.g. `att`-style iOS-only asks,
     * or a typo'd `notifications` where the supported spelling is `notification`) → there is
     * nothing to prompt for. SAFE FALLBACK: advance anyway. The caller has already emitted the
     * host-observable action, so the host sees exactly what was asked for and can prompt itself.
     */
    data class SafeFallbackAdvance(val type: String) : PermissionActionDecision()
}

/**
 * Type source of truth, in the same precedence order iOS uses
 * (`effectiveConfig.permission_type ?? layout["permission_type"]`), with one addition: the button's
 * own `action_value`. A `permission` CTA authored as `{action: "permission", value: "notification"}`
 * carries the type on the button rather than on the step layout; before this, that flow resolved to
 * the empty string and the button was inert.
 */
internal fun resolvePermissionType(
    configType: String?,
    layoutType: String?,
    actionValue: String?,
): String = configType?.takeIf { it.isNotBlank() }
    ?: layoutType?.takeIf { it.isNotBlank() }
    ?: actionValue?.takeIf { it.isNotBlank() }
    ?: ""

/** Pure: is there anything to prompt for? Reuses [PermissionManager]'s own support list. */
internal fun decidePermissionAction(type: String): PermissionActionDecision =
    if (type.isNotBlank() && PermissionManager.isSupported(type)) {
        PermissionActionDecision.RunPipeline(type)
    } else {
        PermissionActionDecision.SafeFallbackAdvance(type)
    }

/**
 * The whole permission-CTA decision, Compose-free.
 *
 * Emits the host-observable action — `{action: "permission", action_value: <resolved type>,
 * ...inputValues, toggle_*}` — through [onNext], the same channel [emitAuthAction] uses, and
 * returns what the caller must do next.
 *
 * On the [PermissionActionDecision.SafeFallbackAdvance] path the emission IS the advance (onNext
 * completes the step), so an unsupported permission can never strand the user. On the
 * [PermissionActionDecision.RunPipeline] path the emission is deferred to the pipeline's own
 * advance (`advancePermissionStep`), which carries the same action keys plus the grant result —
 * so the host sees the action exactly ONCE per tap either way, never zero times and never twice.
 */
internal fun emitPermissionAction(
    configType: String?,
    layoutType: String?,
    actionValue: String?,
    toggleValues: Map<String, Boolean>,
    inputValues: Map<String, Any>,
    onNext: (Map<String, Any>?) -> Unit,
): PermissionActionDecision {
    val type = resolvePermissionType(configType, layoutType, actionValue)
    val decision = decidePermissionAction(type)
    if (decision is PermissionActionDecision.SafeFallbackAdvance) {
        onNext(permissionActionPayload(type, toggleValues, inputValues))
    }
    return decision
}

/**
 * The `onAction`-shaped payload for a permission CTA. Identical in shape to [emitAuthAction]'s:
 * step inputs first, so the SDK-controlled `action` / `action_value` keys win on collision.
 */
internal fun permissionActionPayload(
    type: String,
    toggleValues: Map<String, Boolean> = emptyMap(),
    inputValues: Map<String, Any> = emptyMap(),
): Map<String, Any> = buildMap {
    putAll(inputValues)
    for ((k, v) in toggleValues) put("toggle_$k", v)
    put("action", PERMISSION_ACTION)
    if (type.isNotBlank()) put(PERMISSION_ACTION_VALUE_KEY, type)
}
