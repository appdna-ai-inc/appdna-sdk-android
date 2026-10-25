package ai.appdna.sdk.billing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #1062 — WHICH PHASE OF A PLAY OFFER IS A FREE TRIAL.
 *
 * The iOS side of this promise is proven against a real StoreKit with a manufactured INELIGIBLE
 * user (#933). Play has no `SKTestSession` equivalent — no API writes a transaction into a test
 * store from inside a test process — so this platform's trial rule was proven only by SHAPE, by a
 * static gate reading the source. This exercises the half we actually own: Play decides which
 * offers this user may buy, we decide which of them is a free trial.
 */
class StoreTrialPhaseTest {

    private fun phase(micros: Long, period: String?) = StoreTrialPhase.Phase(micros, period)

    @Test fun `a zero-price phase is the trial, and its period is what is phrased`() {
        val offers = listOf(listOf(phase(0L, "P1W"), phase(4_990_000L, "P1M")))
        assertEquals("P1W", StoreTrialPhase.firstFreeTrialPeriod(offers))
    }

    @Test fun `an introductory PRICE is not a trial`() {
        /*
         * 🔴 The case that makes this rule worth having. "£1 for the first month" rendered as
         * "1 month free" is the paywall lying about money — a promise the purchase sheet refuses.
         */
        val offers = listOf(listOf(phase(1_000_000L, "P1M"), phase(4_990_000L, "P1M")))
        assertNull(StoreTrialPhase.firstFreeTrialPeriod(offers))
    }

    @Test fun `a trial on a LATER offer still counts`() {
        // A product can carry several offers, and a trial on any of them is one this user can take.
        val offers = listOf(
            listOf(phase(4_990_000L, "P1M")),
            listOf(phase(0L, "P3D"), phase(4_990_000L, "P1M")),
        )
        assertEquals("P3D", StoreTrialPhase.firstFreeTrialPeriod(offers))
    }

    @Test fun `no offers at all is no trial`() {
        // What Play returns for a user who has already consumed the trial: the offer is simply
        // absent from the filtered list. This is the state #933 manufactures on iOS.
        assertNull(StoreTrialPhase.firstFreeTrialPeriod(emptyList()))
        assertNull(StoreTrialPhase.firstFreeTrialPeriod(listOf(emptyList())))
    }

    @Test fun `a zero-price phase with no period is no trial`() {
        /*
         * There is nothing to phrase: a free-trial sentence with no duration reads as a bug. Both
         * a null and a blank period must answer the same way, because `billingPeriod` is a
         * platform String that can arrive either way.
         */
        assertNull(StoreTrialPhase.firstFreeTrialPeriod(listOf(listOf(phase(0L, null)))))
        assertNull(StoreTrialPhase.firstFreeTrialPeriod(listOf(listOf(phase(0L, "")))))
        assertNull(StoreTrialPhase.firstFreeTrialPeriod(listOf(listOf(phase(0L, "   ")))))
        // control: the same shape WITH a period is a trial, so the three above are about the period
        // and not about the zero price.
        assertEquals("P1W", StoreTrialPhase.firstFreeTrialPeriod(listOf(listOf(phase(0L, "P1W")))))
    }

    @Test fun `the FIRST free phase wins when a product offers two`() {
        val offers = listOf(listOf(phase(0L, "P1W")), listOf(phase(0L, "P1M")))
        assertEquals("P1W", StoreTrialPhase.firstFreeTrialPeriod(offers))
    }

    // #1113 — the OFFER the purchase uses is the one the sentence was read from.

    @Test fun `the trial offer is the one the period came from`() {
        val offers = listOf(
            listOf(phase(4_990_000L, "P1M")),
            listOf(phase(0L, "P1W"), phase(4_990_000L, "P1M")),
        )
        assertEquals(1, StoreTrialPhase.firstFreeTrialOffer(offers))
        assertEquals("P1W", StoreTrialPhase.firstFreeTrialPeriod(offers))
    }

    @Test fun `no free phase anywhere - no trial offer`() {
        val offers = listOf(listOf(phase(4_990_000L, "P1M")), listOf(phase(1_000_000L, "P1M"), phase(4_990_000L, "P1M")))
        assertNull(StoreTrialPhase.firstFreeTrialOffer(offers))
    }
}
