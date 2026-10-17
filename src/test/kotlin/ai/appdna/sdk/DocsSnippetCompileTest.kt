package ai.appdna.sdk

import ai.appdna.sdk.onboarding.AppDNAOnboardingDelegate
import ai.appdna.sdk.onboarding.StepConfigOverride
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * SPEC-452 — the docs snippet for host-supplied data, compiled.
 *
 * RN has `check:rn-docs-types`, which compiles every documented TypeScript block against the
 * shipped `.d.ts` — "the errors a developer gets when they paste the docs into their app". Kotlin
 * had no equivalent, so a snippet naming a method that does not exist, or passing the wrong type,
 * would ship to docs.appdna.ai unnoticed.
 *
 * The body below is the block published at `docs/sdks/android/onboarding.mdx#host-supplied-data`,
 * verbatim apart from the host's own backend, stubbed here as the RN gate stubs host modules.
 * If this file stops compiling, the published snippet is wrong.
 */
class DocsSnippetCompileTest {

    private data class Booking(val unitPrice: Int, val wineryName: String)

    /** The customer's own service — stubbed. */
    private object myBackend {
        suspend fun booking(stepId: String): Booking =
            Booking(unitPrice = 4500, wineryName = "Château Margaux Estate")
    }

    private class DocDelegate : AppDNAOnboardingDelegate {
        // ── BEGIN published snippet ────────────────────────────────────────────────────────
        override suspend fun onBeforeStepRender(
            flowId: String, stepId: String, stepIndex: Int, stepType: String,
            responses: Map<String, Any>,
        ): StepConfigOverride? {
            val booking = myBackend.booking(stepId)   // your client, your auth
            return StepConfigOverride(dataContext = mapOf(
                "booking" to mapOf("unitPrice" to booking.unitPrice, "winery" to booking.wineryName),
            ))
        }
        // ── END published snippet ──────────────────────────────────────────────────────────
    }

    @Test
    fun `the documented snippet produces a dataContext`() = runBlocking {
        val override = DocDelegate().onBeforeStepRender("f", "s", 0, "custom", emptyMap())
        @Suppress("UNCHECKED_CAST")
        val booking = override?.dataContext?.get("booking") as? Map<String, Any>
        assertEquals(4500, booking?.get("unitPrice"))
        assertEquals("Château Margaux Estate", booking?.get("winery"))
    }
}
