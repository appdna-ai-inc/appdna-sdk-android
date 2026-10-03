package ai.appdna.sdk.billing

import org.junit.Assert.assertEquals
import org.junit.Test

/** The two-phase pure decision, precedence (1)–(5). */
class PurchaseCompletionPlanTest {

    private fun decide(
        type: String = "inapp", consume: Boolean? = null, owns: Boolean = true, reported: Boolean = false,
        ack: Boolean = false, pending: Finalize? = null, attempted: Boolean = false,
    ) = PurchaseCompletionPlan.decide(type, consume, owns, reported, ack, pending, attempted)

    @Test fun `(1) not owning - nothing, whatever else is true`() {
        val p = decide(owns = false, reported = true, pending = Finalize.Consume)
        assertEquals(CompletionPlan(false, null, false, false, 1), p)
    }

    @Test fun `(2) reported with a pending finalize - retry only that finalize, regardless of anything else`() {
        assertEquals(Finalize.Consume, decide(reported = true, pending = Finalize.Consume, ack = true, attempted = true).finalize)
        val p = decide(reported = true, pending = Finalize.Acknowledge, consume = false)
        assertEquals(CompletionPlan(false, Finalize.Acknowledge, false, false, 2), p)
    }

    @Test fun `(3) acknowledged and unreported - an INAPP is classified silently, a consumable consumed silently`() {
        assertEquals(CompletionPlan(true, null, false, false, 3), decide(ack = true))
        assertEquals(CompletionPlan(false, Finalize.Consume, false, false, 3), decide(ack = true, consume = true))
        assertEquals(CompletionPlan(false, null, false, false, 3), decide(ack = true, consume = false))
        // SUBS are never classified.
        assertEquals(CompletionPlan(false, null, false, false, 3), decide(type = "subs", ack = true))
    }

    @Test fun `(4) unacknowledged and unreported - verify first, then consume or acknowledge and emit`() {
        assertEquals(CompletionPlan(true, null, false, false, 4), decide())
        assertEquals(CompletionPlan(false, Finalize.Consume, true, false, 4), decide(consume = true))
        assertEquals(CompletionPlan(false, Finalize.Acknowledge, true, true, 4), decide(consume = false))
    }

    @Test fun `(4) an acknowledged purchase in the verify-attempted set is reported, not silenced (R22-S1)`() {
        assertEquals(4, decide(ack = true, attempted = true).precedence)
        val p = decide(ack = true, attempted = true, consume = false)
        assertEquals(CompletionPlan(false, null, true, true, 4), p)
    }

    @Test fun `(5) reported and finalized - nothing`() {
        assertEquals(CompletionPlan(false, null, false, false, 5), decide(reported = true, ack = true))
    }
}
