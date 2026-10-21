package ai.appdna.sdk.billing

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** "Android keeps in-app entitlements" + "Clearing and ownership". */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EntitlementCacheRulesTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private var user: String? = "user_a"

    @Before fun clean() { EntitlementCache.clearPersisted(ctx) }

    private fun cache() = EntitlementCache(ctx) { user }
    private fun ent(id: String, expires: String? = null, store: String = "google_play") = Entitlement(id, store, "active", expires, false, null)

    @Test fun `a snapshot without in-app items keeps the cached lifetime entitlement`() {
        val c = cache()
        c.update(ent("lifetime"), "inapp")
        c.update(ent("monthly", "2026-10-30T00:00:00Z"), "subs")
        c.applySnapshot(emptyList())
        assertEquals(listOf("lifetime"), c.getAll().map { it.productId })
    }

    @Test fun `hasActiveSubscription is true when the only entry is a lifetime`() {
        val c = cache()
        c.update(ent("lifetime"), "inapp")
        assertTrue(c.hasActiveSubscription)
    }

    /**
     * The entitled statuses are the server's (`ACTIVE_STATUSES` in the server's entitlement rules):
     * a subscription in `billing_retry` is entitled; `on_hold` / `pending` / `paused` and the terminal
     * statuses are not. NEGATIVE CONTROL: without `billing_retry` in `EntitlementCache.ACTIVE_STATUSES`,
     * `hasActiveSubscription` is false for the billing-retry user — this test fails.
     */
    @Test fun `billing_retry is entitled like the server, on_hold and the terminal statuses are not`() {
        val entitled = listOf("active", "trialing", "grace_period", "billing_retry")
        val notEntitled = listOf("on_hold", "pending", "paused", "expired", "canceled", "revoked", "refunded")
        for (status in entitled + notEntitled) {
            EntitlementCache.clearPersisted(ctx)
            val c = cache()
            c.update(Entitlement("monthly", "google_play", status, "2026-10-30T00:00:00Z", false, null), "subs")
            val expected = status in entitled
            assertEquals("hasActiveSubscription for $status", expected, c.hasActiveSubscription)
            assertEquals("isEntitled for $status", expected, c.isEntitled("monthly"))
        }
    }

    @Test fun `a restore reply that omits the lifetime removes it, and product_type round-trips on disk`() {
        val c = cache()
        c.update(ent("lifetime"), "inapp")
        c.update(ent("monthly", "2026-10-30T00:00:00Z"), "subs")
        assertEquals("inapp", cache().productTypeOf("lifetime"))
        c.mergeRestoreReply(emptyList(), protectedProducts = emptySet(), okSubsProducts = setOf("monthly"))
        assertEquals(listOf("monthly"), c.getAll().map { it.productId })
    }

    @Test fun `an excluded token keeps its in-app entry`() {
        val c = cache()
        c.update(ent("lifetime"), "inapp")
        c.mergeRestoreReply(emptyList(), protectedProducts = setOf("lifetime"), okSubsProducts = emptySet())
        assertEquals(listOf("lifetime"), c.getAll().map { it.productId })
    }

    @Test fun `a legacy typeless google_play entry without expiry loads as inapp`() {
        ctx.getSharedPreferences("ai.appdna.sdk.billing", Context.MODE_PRIVATE).edit().putString(
            "entitlements_cache",
            JSONArray().put(JSONObject().put("product_id", "lifetime").put("store", "google_play").put("status", "active")).toString(),
        ).commit()
        assertEquals("inapp", cache().productTypeOf("lifetime"))
    }

    @Test fun `a persisted cache owned by another user is discarded, an anonymous one is adopted`() {
        user = "user_a"
        cache().update(ent("lifetime"), "inapp")
        user = "user_b"
        assertEquals(emptyList<Entitlement>(), cache().getAll())
        EntitlementCache.clearPersisted(ctx)
        user = null
        cache().update(ent("lifetime"), "inapp")
        user = "user_c"
        assertEquals(listOf("lifetime"), cache().getAll().map { it.productId })
    }

    @Test fun `clear fires onEntitlementsChanged with an empty list`() {
        val c = cache()
        c.update(ent("lifetime"), "inapp")
        var seen: List<Entitlement>? = null
        c.addChangeListener { seen = it }
        c.clear()
        assertEquals(emptyList<Entitlement>(), seen)
    }

    /**
     * A RENEWAL IS A CHANGE. The diff-guard compared product ids alone, so the same subscription with a
     * later expiry — what a renewal IS — fired nothing, and a host listening for entitlement changes
     * heard about the first purchase and then silence for the life of the subscription. iOS compares
     * `productId|isActive|expiry`.
     *
     * NEGATIVE CONTROL: the first assertion. Re-applying the IDENTICAL entitlement must still be
     * silent, or this test would pass against a guard that was simply removed.
     */
    @Test fun `a renewal and a status change each fire, an identical re-apply does not`() {
        val c = cache()
        c.update(ent("monthly", "2026-11-01T00:00:00Z"), "subs")
        val seen = mutableListOf<List<Entitlement>>()
        c.addChangeListener { seen.add(it) }

        // control: the same product, same status, same expiry — no change, no callback.
        c.update(ent("monthly", "2026-11-01T00:00:00Z"), "subs")
        assertEquals(0, seen.size)

        // a renewal: same product and status, the expiry moves out a month.
        c.update(ent("monthly", "2026-12-01T00:00:00Z"), "subs")
        assertEquals(1, seen.size)
        assertEquals("2026-12-01T00:00:00Z", seen.last().single().expiresAt)

        // a status change: same product, same expiry, now in its billing grace period.
        c.update(Entitlement("monthly", "google_play", "grace_period", "2026-12-01T00:00:00Z", false, null), "subs")
        assertEquals(2, seen.size)
        assertEquals("grace_period", seen.last().single().status)
    }

    /**
     * THE LAMBDA FAN-OUT RUNS ON THE MAIN THREAD. `notifyBillingDelegate` always posted to the main
     * looper — "so host code never runs on the Firestore listener thread", its own words — and
     * `notifyListeners`, the path behind the public `onEntitlementsChanged { }` lambda, did not: it
     * ran the host's lambda on whichever thread wrote the entitlements. That is the Firestore
     * listener thread for a server update and a Play callback thread for a purchase, and a lambda
     * touching a View from there throws `CalledFromWrongThreadException`.
     *
     * Driven from a real background thread, because that is the only place the two behaviours
     * differ: on the main thread the fan-out fires directly (deliberately — see `notifyListeners`),
     * so a test that stayed on the test thread would pass either way.
     *
     * NEGATIVE CONTROL: the callback must still ARRIVE. "It did not run on the background thread"
     * is also true of a fan-out that was dropped entirely.
     */
    @Test fun `a listener fires on the main thread even when the write comes from a background thread`() {
        val c = cache()
        val threads = java.util.Collections.synchronizedList(mutableListOf<String>())
        c.addChangeListener { threads.add(Thread.currentThread().name) }

        val worker = Thread({ c.update(ent("monthly", "2026-11-01T00:00:00Z"), "subs") }, "appdna-test-worker")
        worker.start()
        worker.join()

        // Nothing ran on the worker: the post is still sitting on the main looper's queue.
        assertEquals(emptyList<String>(), threads)

        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        // control: it ARRIVED — and on the main thread, which under Robolectric is the test thread.
        assertEquals(1, threads.size)
        assertEquals(Thread.currentThread().name, threads.single())
    }

    /**
     * `identify(user_a)` → `identify(user_b)` with a snapshot of user_a's entitlement document already handed
     * to the main thread: `stopObserving()` cannot take it back, and it used to land in user_b's cleared cache.
     * NEGATIVE CONTROL: without the identity check at the top of `onSnapshot`, user_a's `monthly` is cached
     * (and persisted) for user_b — this test fails.
     */
    @Test fun `a snapshot of the previous user after a switch is dropped`() {
        val c = cache()
        val subs = mapOf<String, Any?>("subscriptions" to listOf(mapOf(
            "product_id" to "monthly", "store" to "google_play", "status" to "active",
            "expires_at" to "2026-10-30T00:00:00Z", "is_trial" to false,
        )))
        c.onSnapshot("user_a", subs, fromCache = false)
        assertEquals(listOf("monthly"), c.getAll().map { it.productId })   // control: the current user's applies

        user = "user_b"                       // identify(user_b): stop → clear
        c.stopObserving(); c.clear()
        c.onSnapshot("user_a", subs, fromCache = false)
        assertEquals(emptyList<Entitlement>(), c.getAll())
        assertEquals(emptyList<Entitlement>(), cache().getAll())             // nothing persisted for user_b
    }
}
