package ai.appdna.sdk.paywalls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `onPaywallPurchaseFailed` carried no `productId`, so a paywall selling two
 * plans told the host only "something failed on paywall X": it could not retry the right product,
 * attribute the failure, or tell which plan the card was declined on. `onPaywallPurchaseStarted`
 * and `onPaywallPurchaseCompleted` both carried it; only the failure path dropped it.
 *
 * The fix follows the SAME default-implemented-overload pattern `errorType` used, so every existing
 * conformer keeps compiling AND keeps receiving the callback. These tests pin the whole chain:
 * 4-arg (what the SDK calls) → 3-arg → 2-arg.
 */
class PurchaseFailedProductIdTest {

    @Test
    fun `the SDK's 4-arg call reaches a host that only overrides the 4-arg form`() {
        var seen: Triple<String, String, String?>? = null
        val delegate = object : AppDNAPaywallDelegate {
            override fun onPaywallPurchaseFailed(
                paywallId: String,
                error: Throwable,
                errorType: String,
                productId: String?,
            ) {
                seen = Triple(paywallId, errorType, productId)
            }
        }

        delegate.onPaywallPurchaseFailed(
            "pw_1", RuntimeException("declined"), "serverError", "com.app.annual",
        )

        assertEquals(Triple("pw_1", "serverError", "com.app.annual"), seen)
    }

    @Test
    fun `a legacy conformer that only overrides the 2-arg form still receives the failure`() {
        // The whole point of the defaulted overloads: hosts written before productId existed must
        // not break, and must not go silent.
        var seen: Pair<String, String>? = null
        val legacy = object : AppDNAPaywallDelegate {
            override fun onPaywallPurchaseFailed(paywallId: String, error: Throwable) {
                seen = paywallId to (error.message ?: "")
            }
        }

        // What the SDK now calls.
        legacy.onPaywallPurchaseFailed("pw_1", RuntimeException("declined"), "serverError", "com.app.annual")

        assertEquals("pw_1" to "declined", seen)
    }

    @Test
    fun `a conformer that overrides the 3-arg errorType form still receives the failure`() {
        var seen: Pair<String, String>? = null
        val mid = object : AppDNAPaywallDelegate {
            override fun onPaywallPurchaseFailed(paywallId: String, error: Throwable, errorType: String) {
                seen = paywallId to errorType
            }
        }

        mid.onPaywallPurchaseFailed("pw_1", RuntimeException("nope"), "userCancelled", "com.app.monthly")

        assertEquals("pw_1" to "userCancelled", seen)
    }

    @Test
    fun `two products on one paywall are distinguishable at the failure callback`() {
        // The bug, stated as a test: before productId, these two failures were indistinguishable.
        val failures = mutableListOf<String?>()
        val delegate = object : AppDNAPaywallDelegate {
            override fun onPaywallPurchaseFailed(
                paywallId: String,
                error: Throwable,
                errorType: String,
                productId: String?,
            ) {
                failures.add(productId)
            }
        }

        delegate.onPaywallPurchaseFailed("pw", RuntimeException("a"), "networkError", "com.app.monthly")
        delegate.onPaywallPurchaseFailed("pw", RuntimeException("b"), "networkError", "com.app.annual")
        // A pre-purchase failure (e.g. the paywall config itself is missing) has no product at all —
        // null means "no product was selected", never "unknown product".
        delegate.onPaywallPurchaseFailed("pw", RuntimeException("c"), "unknown", null)

        assertEquals(listOf("com.app.monthly", "com.app.annual", null), failures)
        assertNull(failures.last())
    }
}
