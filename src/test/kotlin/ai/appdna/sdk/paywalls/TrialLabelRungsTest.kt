package ai.appdna.sdk.paywalls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #989 — THE FOUR RUNGS OF A TRIAL SENTENCE, in order.
 *
 * `trial.duration_days` was required by the console schema, decoded by both SDKs, and read by
 * NONE of the three rungs that existed — so a plan carrying the object with no `label` drew no
 * trial text at all while carrying an authored trial length. 2 of 19 plans on dev are that shape.
 *
 * 🔴 The ORDER is the assertion, not the formatting. A new rung placed above an authored one would
 * silently reword every published paywall that already says something, which is the failure this
 * pins against: each case below sets the rungs it wants to lose as well as the one it expects to
 * win.
 *
 * The Swift twin is `TrialLabelRungsTests.swift`; both drive the real `trialLabel` property.
 */
class TrialLabelRungsTest {

    private fun plan(
        label: String? = null,
        trialDuration: String? = null,
        durationDays: Int? = null,
        storeTrial: String? = null,
    ) = PaywallPlan(
        id = "p1",
        product_id = "app.premium.monthly",
        name = "Monthly",
        price = "$9.99",
        trial = if (label != null || durationDays != null) {
            PaywallPlanTrial(duration_days = durationDays, label = label)
        } else {
            null
        },
        trial_duration = trialDuration,
        resolvedStoreTrial = storeTrial,
    )

    @Test
    fun `rung 1 — an authored label is verbatim and beats everything below it`() {
        val p = plan(label = "First week on us", trialDuration = "P7D", durationDays = 7, storeTrial = "P1M")
        assertEquals("First week on us", p.trialLabel)
    }

    @Test
    fun `rung 2 — trial_duration is phrased, and beats duration_days and the store`() {
        val p = plan(trialDuration = "P7D", durationDays = 30, storeTrial = "P1M")
        assertEquals("7 days free trial", p.trialLabel)
    }

    @Test
    fun `rung 3 — duration_days is phrased when nothing above it is set`() {
        val p = plan(durationDays = 7, storeTrial = "P1M")
        assertEquals("7 days free trial", p.trialLabel)
    }

    @Test
    fun `rung 4 — the store's trial is last`() {
        val p = plan(storeTrial = "P1W")
        assertEquals("1 week free trial", p.trialLabel)
    }

    /** The control: nothing authored and no store answer draws nothing, rather than "P0D". */
    @Test
    fun `nothing authored and no store trial draws nothing`() {
        assertNull(plan().trialLabel)
    }

    /** A zero or negative duration is not a trial — it must not become "0 days free trial". */
    @Test
    fun `a non-positive duration_days is ignored`() {
        assertNull(plan(durationDays = 0).trialLabel)
    }
}
