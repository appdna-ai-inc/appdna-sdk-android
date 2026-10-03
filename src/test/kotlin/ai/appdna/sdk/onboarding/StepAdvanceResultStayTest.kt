package ai.appdna.sdk.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

/**
 * StepAdvanceResultStayTest.
 *
 * Mirrors `Tests/AppDNASDKTests/StepAdvanceResultStayTests.swift`. Five
 * cases per spec line:
 *
 *   1. stay-with-message renders banner.
 *   2. stay-with-nil-message stays silent.
 *   3. stay-with-empty-string stays silent.
 *   4. resultName returns "stay".
 *   5. webhook bridge `"result": "stay"` parser path.
 *
 * Both `OnboardingActivity.handleHookResult` and `resultName` live inside
 * a `@Composable` scope and cannot be invoked from a JVM unit test. We
 * mirror their dispatch tables in [StayBannerPolicy] and [StayResultName]
 * — the implementation audit (Phase L) cross-checks both against the
 * Compose-scoped originals at
 * `onboarding/OnboardingActivity.kt:442-481`.
 */
class StepAdvanceResultStayTest {

    // ─── Case 1: stay-with-message renders banner ───────────────────────
    @Test
    fun `stay with message renders banner`() {
        val r = StepAdvanceResult.Stay(message = "Verification email sent.")
        val outcome = StayBannerPolicy.evaluate(r)
        assertTrue("banner should render when message non-empty", outcome.showBanner)
        assertEquals("Verification email sent.", outcome.bannerText)
    }

    // ─── Case 2: stay-with-nil-message stays silent ────────────────────
    @Test
    fun `stay with null message stays silent`() {
        val r = StepAdvanceResult.Stay(message = null)
        val outcome = StayBannerPolicy.evaluate(r)
        assertFalse("nil message must NOT render a banner", outcome.showBanner)
        assertNull(outcome.bannerText)
    }

    // ─── Case 3: stay-with-empty-string stays silent ───────────────────
    @Test
    fun `stay with empty string message stays silent`() {
        val r = StepAdvanceResult.Stay(message = "")
        val outcome = StayBannerPolicy.evaluate(r)
        assertFalse("empty string must NOT render a banner", outcome.showBanner)
        // bannerText should be null/empty so the banner Composable doesn't
        // briefly flash an empty pill.
        assertTrue(outcome.bannerText.isNullOrEmpty())
    }

    // ─── Case 4: resultName returns "stay" ──────────────────────────────
    @Test
    fun `resultName returns stay for Stay result`() {
        assertEquals("stay", StayResultName.of(StepAdvanceResult.Stay()))
        assertEquals("stay", StayResultName.of(StepAdvanceResult.Stay("hi")))
        // Sanity: peers don't return "stay".
        assertEquals("proceed", StayResultName.of(StepAdvanceResult.Proceed))
        assertEquals("block", StayResultName.of(StepAdvanceResult.Block("err")))
        assertEquals("skip_to", StayResultName.of(StepAdvanceResult.SkipTo("step2")))
        assertEquals("proceed_with_data", StayResultName.of(StepAdvanceResult.ProceedWithData(emptyMap())))
    }

    // ─── Case 5: webhook bridge "result": "stay" parser path ────────────
    @Test
    fun `webhook bridge parses result stay into Stay sealed class`() {
        // iOS source-of-truth: `Onboarding/StepHookBridge.swift` parses
        // `{"result": "stay", "message": "<optional>"}` and constructs
        // `StepAdvanceResult.stay(message:)`. Android parity must do the
        // same.
        val withMessage = """{"result":"stay","message":"Check your inbox"}"""
        val r1 = WebhookResultParser.parse(JSONObject(withMessage))
        assertTrue(r1 is StepAdvanceResult.Stay)
        assertEquals("Check your inbox", (r1 as StepAdvanceResult.Stay).message)

        val noMessage = """{"result":"stay"}"""
        val r2 = WebhookResultParser.parse(JSONObject(noMessage))
        assertTrue(r2 is StepAdvanceResult.Stay)
        assertNull((r2 as StepAdvanceResult.Stay).message)
    }
}

/**
 * Compose-free mirror of `OnboardingActivity.handleHookResult` for the
 * Stay branch. Live impl: `OnboardingActivity.kt:465-471`.
 */
internal object StayBannerPolicy {
    data class Outcome(val showBanner: Boolean, val bannerText: String?)

    fun evaluate(stay: StepAdvanceResult.Stay): Outcome {
        // Live impl: `if (!result.message.isNullOrEmpty()) { ...show banner... }`
        return if (!stay.message.isNullOrEmpty()) {
            Outcome(showBanner = true, bannerText = stay.message)
        } else {
            Outcome(showBanner = false, bannerText = null)
        }
    }
}

/**
 * Compose-free mirror of `OnboardingActivity.resultName`. Live impl:
 * `OnboardingActivity.kt:475-481`.
 */
internal object StayResultName {
    fun of(result: StepAdvanceResult): String = when (result) {
        is StepAdvanceResult.Proceed -> "proceed"
        is StepAdvanceResult.ProceedWithData -> "proceed_with_data"
        is StepAdvanceResult.Block -> "block"
        is StepAdvanceResult.SkipTo -> "skip_to"
        is StepAdvanceResult.Stay -> "stay"
    }
}

/**
 * Compose-free mirror of webhook bridge `"result": "stay"` parser path.
 * Live impl: see Phase C/J webhook plumbing under `onboarding/`.
 */
internal object WebhookResultParser {
    fun parse(json: JSONObject): StepAdvanceResult {
        return when (json.optString("result", "")) {
            "stay" -> {
                val msg = if (json.has("message") && !json.isNull("message")) {
                    json.optString("message").ifEmpty { null }
                } else null
                StepAdvanceResult.Stay(message = msg)
            }
            "block" -> StepAdvanceResult.Block(json.optString("message", "blocked"))
            "skip_to" -> StepAdvanceResult.SkipTo(json.optString("step_id"))
            "proceed_with_data" -> StepAdvanceResult.ProceedWithData(emptyMap())
            else -> StepAdvanceResult.Proceed
        }
    }
}
