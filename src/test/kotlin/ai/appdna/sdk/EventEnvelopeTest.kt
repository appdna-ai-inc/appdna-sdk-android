package ai.appdna.sdk

import ai.appdna.sdk.events.EventSchema
import ai.appdna.sdk.events.ExperimentExposure
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verify event envelope no longer hard-codes the SDK
 * version and now carries `context.experiment_exposures` when provided.
 */
class EventEnvelopeTest {

    private val identity = DeviceIdentity(anonId = "anon_test", userId = "user_test")

    @Test
    fun `A_6 EventSchema SDK_VERSION tracks AppDNA sdkVersion (no stale literal)`() {
        // The whole point of A.6 is "stop letting these drift". They MUST be the same value.
        assertEquals(AppDNA.sdkVersion, EventSchema.SDK_VERSION)
        // And device.sdk_version in the actual envelope must agree.
        val envelope = EventSchema.buildEnvelope(
            eventName = "x",
            properties = null,
            identity = identity,
            sessionId = "s1",
            appVersion = "9.9.9",
            analyticsConsent = true
        )
        assertEquals(AppDNA.sdkVersion, envelope.getJSONObject("device").getString("sdk_version"))
    }

    @Test
    fun `A_14 envelope omits experiment_exposures when none provided`() {
        val envelope = EventSchema.buildEnvelope(
            eventName = "no_exp",
            properties = null,
            identity = identity,
            sessionId = "s1",
            appVersion = "1.0.0",
            analyticsConsent = true,
            experimentExposures = null
        )
        val ctx = envelope.getJSONObject("context")
        assertFalse(
            "context.experiment_exposures must be omitted when no exposures (matches iOS Codable nil)",
            ctx.has("experiment_exposures")
        )
    }

    @Test
    fun `A_14 envelope omits experiment_exposures when list is empty`() {
        val envelope = EventSchema.buildEnvelope(
            eventName = "empty_exp",
            properties = null,
            identity = identity,
            sessionId = "s1",
            appVersion = "1.0.0",
            analyticsConsent = true,
            experimentExposures = emptyList()
        )
        assertFalse(envelope.getJSONObject("context").has("experiment_exposures"))
    }

    @Test
    fun `A_14 envelope serializes exposures with iOS-parity exp + variant keys`() {
        val exposures = listOf(
            ExperimentExposure(experimentId = "exp_paywall", variantId = "treatment_a"),
            ExperimentExposure(experimentId = "exp_pricing", variantId = "control")
        )

        val envelope = EventSchema.buildEnvelope(
            eventName = "purchase_completed",
            properties = mapOf("amount" to 9.99),
            identity = identity,
            sessionId = "s1",
            appVersion = "1.0.0",
            analyticsConsent = true,
            experimentExposures = exposures
        )

        val ctx = envelope.getJSONObject("context")
        assertTrue(ctx.has("experiment_exposures"))

        val arr = ctx.getJSONArray("experiment_exposures")
        assertEquals(2, arr.length())

        val first = arr.getJSONObject(0)
        // iOS source-of-truth shape: { exp, variant } — NOT { experimentId, variantId }
        assertEquals("exp_paywall", first.getString("exp"))
        assertEquals("treatment_a", first.getString("variant"))

        val second = arr.getJSONObject(1)
        assertEquals("exp_pricing", second.getString("exp"))
        assertEquals("control", second.getString("variant"))
    }

    @Test
    fun `A_14 envelope still includes session_id alongside experiment_exposures`() {
        val envelope = EventSchema.buildEnvelope(
            eventName = "x",
            properties = null,
            identity = identity,
            sessionId = "session_xyz",
            appVersion = "1.0.0",
            analyticsConsent = true,
            experimentExposures = listOf(ExperimentExposure("e", "v"))
        )
        val ctx = envelope.getJSONObject("context")
        assertEquals("session_xyz", ctx.getString("session_id"))
        assertNotNull(ctx.getJSONArray("experiment_exposures"))
    }
}
