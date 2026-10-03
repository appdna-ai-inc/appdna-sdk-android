package ai.appdna.sdk.billing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies that the static `subscriptions[]` parser
 * produces the same Entitlement shape as iOS
 * (`Billing/EntitlementCache.swift:63-81`).
 *
 * The on-disk Firestore document at
 * `orgs/{orgId}/apps/{appId}/users/{userId}/entitlements/current` contains a
 * `subscriptions[]` array of objects; iOS reads `subscriptions` and Android
 * was previously reading the path as a *collection*. Test that the new
 * document-shape parser handles backend payloads correctly.
 */
class EntitlementCacheParseSubscriptionsTest {

    @Test
    fun `parses single active subscription`() {
        val data = mapOf<String, Any?>(
            "subscriptions" to listOf(
                mapOf(
                    "product_id" to "premium_monthly",
                    "store" to "google_play",
                    "status" to "active",
                    "expires_at" to "2026-12-01T00:00:00Z",
                    "is_trial" to false,
                ),
            ),
        )
        val out = EntitlementCache.parseSubscriptionsImpl(data)
        assertEquals(1, out.size)
        val e = out.first()
        assertEquals("premium_monthly", e.productId)
        assertEquals("google_play", e.store)
        assertEquals("active", e.status)
        assertEquals("2026-12-01T00:00:00Z", e.expiresAt)
        assertEquals(false, e.isTrial)
        assertNull(e.offerType)
    }

    @Test
    fun `parses trialing entitlement with offer_type`() {
        val data = mapOf<String, Any?>(
            "subscriptions" to listOf(
                mapOf(
                    "product_id" to "premium_yearly",
                    "store" to "google_play",
                    "status" to "trialing",
                    "is_trial" to true,
                    "offer_type" to "free_trial",
                ),
            ),
        )
        val out = EntitlementCache.parseSubscriptionsImpl(data)
        assertEquals(1, out.size)
        assertEquals("free_trial", out.first().offerType)
        assertTrue(out.first().isTrial)
        assertEquals("trialing", out.first().status)
    }

    @Test
    fun `missing required field skips entry`() {
        val data = mapOf<String, Any?>(
            "subscriptions" to listOf(
                // missing product_id — entire entry should be dropped.
                mapOf("store" to "google_play", "status" to "active"),
                mapOf(
                    "product_id" to "p2",
                    "store" to "google_play",
                    "status" to "active",
                ),
            ),
        )
        val out = EntitlementCache.parseSubscriptionsImpl(data)
        assertEquals(1, out.size)
        assertEquals("p2", out.first().productId)
    }

    @Test
    fun `empty subscriptions returns empty list`() {
        val data = mapOf<String, Any?>("subscriptions" to emptyList<Map<String, Any?>>())
        assertTrue(EntitlementCache.parseSubscriptionsImpl(data).isEmpty())
    }

    @Test
    fun `missing subscriptions key returns empty list`() {
        val data = mapOf<String, Any?>("other_key" to "value")
        assertTrue(EntitlementCache.parseSubscriptionsImpl(data).isEmpty())
    }

    @Test
    fun `parses multiple subscriptions`() {
        val data = mapOf<String, Any?>(
            "subscriptions" to listOf(
                mapOf(
                    "product_id" to "p1",
                    "store" to "google_play",
                    "status" to "active",
                ),
                mapOf(
                    "product_id" to "p2",
                    "store" to "google_play",
                    "status" to "grace_period",
                ),
            ),
        )
        val out = EntitlementCache.parseSubscriptionsImpl(data)
        assertEquals(2, out.size)
        assertEquals(setOf("p1", "p2"), out.map { it.productId }.toSet())
    }
}
