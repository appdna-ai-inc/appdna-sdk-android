package ai.appdna.sdk.billing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

/**
 * Pure unit tests for the cross-account-leak ownership filter — the single
 * source of truth for the decision matrix that defends every site reading
 * `BillingClient.queryPurchasesAsync`. The QA reproducer (User A buys →
 * User B signs in → B sees A's subscription) lives or dies on this decision
 * table being correct; if any case here flips wrong, the whole defence
 * silently breaks.
 *
 * Decision matrix re-stated for clarity:
 *   expectedToken null                                                  → GrantAnonymousPolicy   (pre-identify pass-through)
 *   expectedToken set, purchaseToken == match                           → Grant                  (current user's purchase)
 *   expectedToken set, purchaseToken mismatch                           → DenyOtherUser          (tagged-cross-account leak guard)
 *   expectedToken set, purchaseToken null, firstIdentifier == expected  → GrantUntaggedMigration (legitimate self-claim)
 *   expectedToken set, purchaseToken null, firstIdentifier != expected  → DenyUntaggedOtherUser  (untagged-cross-account leak guard — THE v1.0.63 FIX)
 *   expectedToken set, purchaseToken null, firstIdentifier null         → DenyUntaggedOtherUser  (no anchor recorded — no migration grant)
 *
 * Mirrors iOS `EntitlementOwnerFilterTests` byte-for-byte.
 */
class EntitlementOwnerFilterTest {

    private val tokenA = UUID.randomUUID()
    private val tokenB = UUID.randomUUID()

    // ─── The six cases of the decision matrix ───────────────────────────────

    @Test fun `no expected token returns anonymous-policy grant`() {
        // No identified user (host hasn't called identify yet) — any
        // purchase token, including null, is accepted under the legacy
        // pass-through. Preserves first-launch / pre-identify flows.
        // `firstIdentifiedToken` is irrelevant in this branch.
        assertEquals(
            EntitlementOwnershipDecision.GrantAnonymousPolicy,
            EntitlementOwnerFilter.decide(purchaseToken = tokenA, expectedToken = null, firstIdentifiedToken = null),
        )
        assertEquals(
            EntitlementOwnershipDecision.GrantAnonymousPolicy,
            EntitlementOwnerFilter.decide(purchaseToken = null, expectedToken = null, firstIdentifiedToken = null),
        )
        assertEquals(
            EntitlementOwnershipDecision.GrantAnonymousPolicy,
            EntitlementOwnerFilter.decide(purchaseToken = tokenA, expectedToken = null, firstIdentifiedToken = tokenB),
        )
    }

    @Test fun `tagged and matching token grants regardless of first-identifier`() {
        // Identified user, purchase tagged with the same user's token → grant.
        assertEquals(
            EntitlementOwnershipDecision.Grant,
            EntitlementOwnerFilter.decide(purchaseToken = tokenA, expectedToken = tokenA, firstIdentifiedToken = tokenA),
        )
        // First-identifier irrelevant on the matched-token path.
        assertEquals(
            EntitlementOwnershipDecision.Grant,
            EntitlementOwnerFilter.decide(purchaseToken = tokenA, expectedToken = tokenA, firstIdentifiedToken = tokenB),
        )
        assertEquals(
            EntitlementOwnershipDecision.Grant,
            EntitlementOwnerFilter.decide(purchaseToken = tokenA, expectedToken = tokenA, firstIdentifiedToken = null),
        )
    }

    @Test fun `tagged for a different user is denied regardless of first-identifier`() {
        // Identified user A, purchase tagged for user B → DENY.
        // The tagged-mismatch path (v1.0.62 already handled this correctly;
        // we keep the test to lock the behaviour).
        assertEquals(
            EntitlementOwnershipDecision.DenyOtherUser,
            EntitlementOwnerFilter.decide(purchaseToken = tokenB, expectedToken = tokenA, firstIdentifiedToken = tokenA),
        )
        assertEquals(
            EntitlementOwnershipDecision.DenyOtherUser,
            EntitlementOwnerFilter.decide(purchaseToken = tokenA, expectedToken = tokenB, firstIdentifiedToken = tokenA),
        )
    }

    // ─── The v1.0.63 fix — first-identifier-scoped migration ────────────────

    @Test fun `untagged historical purchase granted to first-identifier`() {
        // Identified user A IS the device's first-identifier; purchase has
        // no obfuscatedAccountId (purchased before SDK started tagging OR
        // by the SDK paywall onboarding flow before identify(...) was
        // called). Migration-tolerant grant is preserved for this case.
        assertEquals(
            EntitlementOwnershipDecision.GrantUntaggedMigration,
            EntitlementOwnerFilter.decide(purchaseToken = null, expectedToken = tokenA, firstIdentifiedToken = tokenA),
        )
    }

    @Test fun `untagged historical purchase denied to later identifier`() {
        // THE FIX — the QA repro. User B identifies on a device where
        // user A is the first-identifier. An untagged purchase (most
        // commonly the SDK-paywall onboarding purchase made BEFORE A
        // identified) MUST NOT be inherited by B.
        assertEquals(
            EntitlementOwnershipDecision.DenyUntaggedOtherUser,
            EntitlementOwnerFilter.decide(purchaseToken = null, expectedToken = tokenB, firstIdentifiedToken = tokenA),
        )
    }

    @Test fun `untagged historical purchase denied when no first-identifier anchored`() {
        // Defensive case: expectedToken is set but no first-identifier
        // anchor exists yet. Untagged purchase is denied — better safe
        // than leaking on an unanchored first read.
        assertEquals(
            EntitlementOwnershipDecision.DenyUntaggedOtherUser,
            EntitlementOwnerFilter.decide(purchaseToken = null, expectedToken = tokenA, firstIdentifiedToken = null),
        )
    }

    @Test fun `QA reproducer at decision-table level`() {
        // End-to-end repro encoded against the filter only. Simulates the
        // SDK-paywall onboarding flow (anonymous purchase) → user A
        // identifies → user B identifies → B taps Restore. Pins that B
        // sees nothing because the untagged purchase is scoped to A (the
        // device's first identifier).
        val untaggedPurchase: UUID? = null
        val firstIdentifier: UUID? = tokenA

        // User A taps Restore → expected = tokenA, first = tokenA
        // → grant (legitimate self-claim).
        assertEquals(
            "User A is the first identifier — their own untagged purchase MUST be granted",
            EntitlementOwnershipDecision.GrantUntaggedMigration,
            EntitlementOwnerFilter.decide(untaggedPurchase, tokenA, firstIdentifier),
        )

        // User A signs out, user B signs in. Anchor does NOT change. B
        // taps Restore.
        assertEquals(
            "User B is NOT the first identifier — A's untagged purchase MUST NOT be inherited",
            EntitlementOwnershipDecision.DenyUntaggedOtherUser,
            EntitlementOwnerFilter.decide(untaggedPurchase, tokenB, firstIdentifier),
        )
    }

    // ─── parseObfuscatedAccountId ───────────────────────────────────────────

    @Test fun `parses a valid uuid string`() {
        val uuid = UUID.randomUUID()
        assertEquals(uuid, EntitlementOwnerFilter.parseObfuscatedAccountId(uuid.toString()))
    }

    @Test fun `returns null for empty or null input (treated as untagged)`() {
        assertNull(EntitlementOwnerFilter.parseObfuscatedAccountId(null))
        assertNull(EntitlementOwnerFilter.parseObfuscatedAccountId(""))
    }

    @Test fun `returns null for malformed input (treated as untagged)`() {
        // Malformed obfuscatedAccountId on a purchase routes through the
        // first-identifier-scoped grant/deny branch rather than throwing
        // — safer than failing the whole restore for one bad row.
        assertNull(EntitlementOwnerFilter.parseObfuscatedAccountId("not-a-uuid"))
        assertNull(EntitlementOwnerFilter.parseObfuscatedAccountId("12345"))
    }
}
