package ai.appdna.sdk

import ai.appdna.sdk.onboarding.AuthActionPolicy
import ai.appdna.sdk.onboarding.StepAdvanceResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** SPEC-497 §4.9 — the sign-in bridge timeout floor. */
class StepAdvanceResultFloorTest {

    @Test
    fun everyBridgeFloorActionGets120Seconds() {
        assertEquals(16, AuthActionPolicy.bridgeFloorActions.size)
        for (action in AuthActionPolicy.bridgeFloorActions) {
            assertEquals(action, 120_000L, StepAdvanceResult.minimumBridgeTimeoutMs(mapOf("action" to action)))
        }
    }

    @Test
    fun socialLoginIsOnlyInTheFloorSet() {
        assertTrue("social_login" in AuthActionPolicy.bridgeFloorActions)
        assertTrue("social_login" !in AuthActionPolicy.delegateRequiredActions)
        assertEquals(15, AuthActionPolicy.delegateRequiredActions.size)
    }

    @Test
    fun nonAuthActionOrMissingDataHasNoFloor() {
        assertNull(StepAdvanceResult.minimumBridgeTimeoutMs(mapOf("action" to "next")))
        assertNull(StepAdvanceResult.minimumBridgeTimeoutMs(mapOf("other" to "x")))
        assertNull(StepAdvanceResult.minimumBridgeTimeoutMs(mapOf("action" to 5)))
        assertNull(StepAdvanceResult.minimumBridgeTimeoutMs(null))
        assertEquals(120_000L, StepAdvanceResult.AUTH_BRIDGE_TIMEOUT_MS)
    }
}
