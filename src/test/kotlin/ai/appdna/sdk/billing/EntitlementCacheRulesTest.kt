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

/** SPEC-497 §13b.2 "Android keeps in-app entitlements" + "Clearing and ownership" (R30–R36). */
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
}
