package ai.appdna.sdk

import ai.appdna.sdk.onboarding.AppDNAOnboardingDelegate
import ai.appdna.sdk.onboarding.StepConfigOverride
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The published docs snippet for host-supplied data, compiled.
 *
 * RN has `check:rn-docs-types`, which compiles every documented TypeScript block against the
 * shipped `.d.ts` — "the errors a developer gets when they paste the docs into their app". Kotlin
 * had no equivalent, so a snippet naming a method that does not exist, or passing the wrong type,
 * would ship to docs.appdna.ai unnoticed.
 *
 * The body below is the block published at `docs/sdks/android/onboarding.mdx#host-supplied-data`,
 * verbatim apart from the host's own backend, stubbed here as the RN gate stubs host modules.
 * If this file stops compiling, the published snippet is wrong.
 *
 * 🔴 KEEP THE KEYS AND THE DATA IDENTICAL TO THE PUBLISHED BLOCK, AND SYNTHETIC. This file had
 * drifted to a different key and a real-sounding name, so it was compiling something the docs do
 * not say — and this package is force-pushed to the public SDK mirrors, where a customer-flavoured
 * name is a Rule 11 breach that `pnpm check:no-customer-identifiers` fails on. Describe the shape.
 *
 * (And do not write a glob with two stars inside a KDoc: Kotlin block comments NEST, so the `/`
 * plus `**` opens one and this comment's own close ends it, leaving the file unterminated. That
 * is one build, spent.)
 */
class DocsSnippetCompileTest {

    private data class Booking(val unitPrice: Int, val venueName: String)

    /** The customer's own service — stubbed. */
    private object myBackend {
        suspend fun booking(stepId: String): Booking =
            Booking(unitPrice = 4500, venueName = "Example Venue")
    }

    private class DocDelegate : AppDNAOnboardingDelegate {
        // ── BEGIN published snippet ────────────────────────────────────────────────────────
        override suspend fun onBeforeStepRender(
            flowId: String, stepId: String, stepIndex: Int, stepType: String,
            responses: Map<String, Any>,
        ): StepConfigOverride? {
            val booking = myBackend.booking(stepId)   // your client, your auth
            return StepConfigOverride(dataContext = mapOf(
                "booking" to mapOf("unitPrice" to booking.unitPrice, "venue" to booking.venueName),
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
        assertEquals("Example Venue", booking?.get("venue"))
    }
}
