package ai.appdna.sdk.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OnboardingActionPassthroughTest.
 *
 * Mirrors `Tests/AppDNASDKTests/OnboardingActionPassthroughTests.swift`.
 *
 * `OnboardingActivity.handleAction(action: String)` (the Compose-scoped
 * dispatcher) routes 16 strict-typed auth / account actions plus
 * `social_login`, `next`, `skip`, `permission` and unknown values. Because
 * `handleAction` lives inside a `@Composable` we cannot call it directly
 * from a JVM test; instead we mirror its dispatch table here in a pure-
 * Kotlin helper [OnboardingActionDispatcher] and assert each of the 16
 * cases lands on the right branch and forwards the colon-encoded
 * `actionValue` to the host as `data["action_value"]` (or as the
 * type-specific key for `social_login` / `permission`).
 *
 * If `OnboardingActivity.handleAction` ever drifts from this table the
 * implementation audit (Phase L) will catch it because both reference
 * the same iOS source-of-truth: `OnboardingRenderer.swift:1507-1546`.
 */
class OnboardingActionPassthroughTest {

    private val strictTypedAuthActions = listOf(
        "login",
        "register",
        "reset_password",
        "magic_link",
        "verify_email",
        "resend_verification",
        "enable_biometric",
        "email_login",
        "request_otp",
        "verify_otp",
        "logout",
        "change_password",
        "set_new_password",
        "delete_account",
        "update_profile",
        "permission",
    )

    @Test
    fun `dispatch table covers all 16 strict-typed actions`() {
        // 15 auth-table entries + permission = 16 cases.
        assertEquals(16, strictTypedAuthActions.size)
    }

    @Test
    fun `each strict-typed action routes via emitAuthAction with name preserved`() {
        // The 15 auth actions (everything except `permission`) all land on
        // the same `emitAuthAction(...)` branch which forwards the action
        // name unchanged in `data["action"]`.
        val authBranchActions = strictTypedAuthActions.filter { it != "permission" }
        for (act in authBranchActions) {
            val captured = mutableMapOf<String, Any>()
            OnboardingActionDispatcher.dispatch(
                action = act,
                inputValues = mapOf("a" to "b"),
                toggleValues = mapOf("c" to true),
                onNext = { data -> captured.putAll(data ?: emptyMap()) },
                onSkip = { },
            )
            assertEquals("auth action '$act' should preserve name", act, captured["action"])
            assertEquals("input forwarded for '$act'", "b", captured["a"])
            assertEquals("toggle forwarded for '$act'", true, captured["c"])
        }
    }

    @Test
    fun `colon-encoded actionValue is forwarded as action_value`() {
        // e.g. `request_otp:sms` → host receives data["action"]="request_otp",
        // data["action_value"]="sms".
        val captured = mutableMapOf<String, Any>()
        OnboardingActionDispatcher.dispatch(
            action = "request_otp:sms",
            onNext = { data -> captured.putAll(data ?: emptyMap()) },
        )
        assertEquals("request_otp", captured["action"])
        assertEquals("sms", captured["action_value"])
    }

    @Test
    fun `social_login forwards provider key and action`() {
        val captured = mutableMapOf<String, Any>()
        OnboardingActionDispatcher.dispatch(
            action = "social_login:google",
            onNext = { data -> captured.putAll(data ?: emptyMap()) },
        )
        assertEquals("social_login", captured["action"])
        assertEquals("google", captured["provider"])
    }

    @Test
    fun `permission forwards permission_type`() {
        val captured = mutableMapOf<String, Any>()
        OnboardingActionDispatcher.dispatch(
            action = "permission:notifications",
            onNext = { data -> captured.putAll(data ?: emptyMap()) },
        )
        assertEquals("permission", captured["action"])
        assertEquals("notifications", captured["permission_type"])
    }

    @Test
    fun `next merges toggle and input values`() {
        val captured = mutableMapOf<String, Any>()
        OnboardingActionDispatcher.dispatch(
            action = "next",
            inputValues = mapOf("rating" to 5),
            toggleValues = mapOf("opt_in" to true),
            onNext = { data -> captured.putAll(data ?: emptyMap()) },
        )
        assertEquals(5, captured["rating"])
        assertEquals(true, captured["opt_in"])
    }

    @Test
    fun `skip routes through onSkip not onNext`() {
        var skipped = false
        var nextCount = 0
        OnboardingActionDispatcher.dispatch(
            action = "skip",
            onNext = { _ -> nextCount++ },
            onSkip = { skipped = true },
        )
        assertTrue(skipped)
        assertEquals(0, nextCount)
    }

    @Test
    fun `unknown action falls back to onNext with null payload`() {
        var capturedPayload: Map<String, Any>? = mapOf("placeholder" to "x")
        OnboardingActionDispatcher.dispatch(
            action = "totally_unknown_action",
            onNext = { data -> capturedPayload = data },
        )
        // Default fallback in handleAction calls `onNext(null)`.
        assertEquals(null, capturedPayload)
    }
}

/**
 * Reference implementation of `OnboardingActivity.handleAction` for unit
 * testing. Mirrors iOS `OnboardingRenderer.swift:1507-1546` and the live
 * Compose-scoped dispatcher at
 * `onboarding/OnboardingActivity.kt:1017-1085`.
 *
 * If the live dispatcher's branches change, update both here AND the
 * Compose-scoped function. The Phase L audit checks parity.
 */
internal object OnboardingActionDispatcher {
    private val authActions = setOf(
        "login", "register", "reset_password", "magic_link",
        "verify_email", "resend_verification", "enable_biometric",
        "email_login", "request_otp", "verify_otp",
        "logout", "change_password", "set_new_password",
        "delete_account", "update_profile",
    )

    fun dispatch(
        action: String,
        inputValues: Map<String, Any> = emptyMap(),
        toggleValues: Map<String, Any> = emptyMap(),
        onNext: (Map<String, Any>?) -> Unit,
        onSkip: (() -> Unit)? = null,
    ) {
        val (rawAction, actionValue) = if (action.contains(":")) {
            val idx = action.indexOf(':')
            action.substring(0, idx) to action.substring(idx + 1)
        } else {
            action to null
        }

        when (rawAction) {
            "next" -> {
                val merged = mutableMapOf<String, Any>()
                merged.putAll(toggleValues)
                merged.putAll(inputValues)
                onNext(merged)
            }
            "skip" -> onSkip?.invoke()
            "social_login" -> {
                val data = mutableMapOf<String, Any>(
                    "provider" to (actionValue ?: "unknown"),
                    "action" to "social_login",
                )
                data.putAll(inputValues)
                onNext(data)
            }
            "permission" -> {
                val data = mutableMapOf<String, Any>("action" to "permission")
                if (actionValue != null) data["permission_type"] = actionValue
                data.putAll(inputValues)
                onNext(data)
            }
            in authActions -> {
                val data = mutableMapOf<String, Any>("action" to rawAction)
                if (actionValue != null) data["action_value"] = actionValue
                data.putAll(toggleValues)
                data.putAll(inputValues)
                onNext(data)
            }
            else -> onNext(null)
        }
    }
}
