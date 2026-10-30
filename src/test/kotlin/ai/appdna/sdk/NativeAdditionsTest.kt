package ai.appdna.sdk

import ai.appdna.sdk.core.URLSafety
import ai.appdna.sdk.core.VetoTimeoutCounter
import ai.appdna.sdk.paywalls.PaywallContext
import ai.appdna.sdk.storage.ConsentStore
import ai.appdna.sdk.storage.EventDatabase
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * PN — the Android native additions, each asserted against the behavior it exists to
 * produce. Every test here was checked to go RED with its fix reverted.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NativeAdditionsTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun setUp() {
        ConsentStore.reset(context)
        VetoTimeoutCounter.reset()
    }

    // ---- Row 6 (N1): BillingProvider wire contract, as the shared fixture pins it ----

    @Test
    fun `bare strings decode to the value-less cases`() {
        assertEquals(BillingProvider.StoreKit2, BillingProvider.fromWire("storeKit2"))
        assertEquals(BillingProvider.RevenueCat, BillingProvider.fromWire("revenueCat"))
        assertEquals(BillingProvider.None, BillingProvider.fromWire("none"))
    }

    @Test
    fun `adapty crosses as a tagged map and round-trips losslessly`() {
        val wire = mapOf("type" to "adapty", "apiKey" to "public_live_abc123XYZ")
        val decoded = BillingProvider.fromWire(wire)
        assertTrue(decoded is BillingProvider.Adapty)
        assertEquals("public_live_abc123XYZ", (decoded as BillingProvider.Adapty).apiKey)
        assertEquals(wire, decoded.toWire())
    }

    @Test
    fun `a bare adapty string carries no apiKey so it is refused, not silently keyless`() {
        assertNull(BillingProvider.fromWire("adapty"))
        assertNull(BillingProvider.fromWire(mapOf("type" to "adapty")))
        assertNull(BillingProvider.fromWire("nonsense"))
        assertNull(BillingProvider.fromWire(null))
    }

    @Test
    fun `value-less cases re-encode to their bare string`() {
        assertEquals("storeKit2", BillingProvider.StoreKit2.toWire())
        assertEquals("none", BillingProvider.None.toWire())
    }

    @Test
    fun `the default billing provider matches iOS`() {
        assertEquals(BillingProvider.StoreKit2, AppDNAOptions().billingProvider)
    }

    // ---- Row 14 / AC-36: the consent decision persists ----

    @Test
    fun `no decision yet reads as null, not as false`() {
        assertNull(ConsentStore.decision(context))
    }

    @Test
    fun `a denial survives and is not re-granted on the next cold start`() {
        ConsentStore.setDecision(context, false)
        assertEquals(false, ConsentStore.decision(context))
        assertFalse(ConsentStore.effectiveConsent(context, requireConsent = false))
        assertFalse(ConsentStore.effectiveConsent(context, requireConsent = true))
    }

    @Test
    fun `an un-asked user is granted by default and denied under requireConsent`() {
        assertTrue(ConsentStore.effectiveConsent(context, requireConsent = false))
        assertFalse(ConsentStore.effectiveConsent(context, requireConsent = true))
    }

    @Test
    fun `requireConsent and vetoTimeout carry the documented defaults`() {
        assertFalse(AppDNAOptions().requireConsent)
        assertEquals(5L, AppDNAOptions().vetoTimeout)
    }

    // ---- Row 18 / W11: the config-URL scheme allowlist ----

    @Test
    fun `https and the system schemes are allowed`() {
        assertTrue(URLSafety.isAllowed(Uri.parse("https://appdna.ai/terms")))
        assertTrue(URLSafety.isAllowed(Uri.parse("mailto:hi@appdna.ai")))
        assertTrue(URLSafety.isAllowed(Uri.parse("tel:+15551234")))
        assertTrue(URLSafety.isAllowed(Uri.parse("market://details?id=ai.appdna")))
    }

    @Test
    fun `dangerous and cleartext schemes are refused`() {
        assertFalse(URLSafety.isAllowed(Uri.parse("javascript:alert(1)")))
        assertFalse(URLSafety.isAllowed(Uri.parse("file:///data/data/x")))
        assertFalse(URLSafety.isAllowed(Uri.parse("content://media/external/images/1")))
        assertFalse(
            URLSafety.isAllowed(Uri.parse("http://insecure.example.com")),
            )
    }

    @Test
    fun `sanitized returns null for a refused or blank url`() {
        assertNull(URLSafety.sanitized("javascript:alert(1)"))
        assertNull(URLSafety.sanitized(""))
        assertNull(URLSafety.sanitized(null))
        assertEquals("https://appdna.ai/", URLSafety.sanitized("https://appdna.ai/").toString())
    }

    // ---- Row 19 / W14: the clock-jump clamp ----

    @Test
    fun `a fresh event is not stale and a genuinely old one is`() {
        val now = 1_000_000_000_000L
        val horizon = 7L * 24 * 60 * 60 * 1000
        assertFalse(EventDatabase.isStale(now - 1000, now, horizon))
        assertTrue(EventDatabase.isStale(now - horizon - 1, now, horizon))
    }

    @Test
    fun `a forward clock jump does not prune unsent events`() {
        val ts = 1_000_000_000_000L
        val horizon = 7L * 24 * 60 * 60 * 1000
        val now = ts + 365L * 24 * 60 * 60 * 1000
        assertFalse(
            EventDatabase.isStale(ts, now, horizon),
        )
    }

    @Test
    fun `a backward clock jump does not prune`() {
        val ts = 1_000_000_000_000L
        val horizon = 7L * 24 * 60 * 60 * 1000
        assertFalse(EventDatabase.isStale(ts, ts - 60_000, horizon))
    }

    // ---- Row 4 / D-s: PaywallContext.customData ----

    @Test
    fun `paywall context carries customData and names its reserved keys`() {
        val ctx = PaywallContext(placement = "home", customData = mapOf("cohort" to "b"))
        assertEquals("b", ctx.customData?.get("cohort"))
        assertTrue("paywall_id" in PaywallContext.RESERVED_EVENT_KEYS)
        assertTrue("placement" in PaywallContext.RESERVED_EVENT_KEYS)
    }

    // ---- Row 16 / W12: the veto timeout is observable ----

    @Test
    fun `veto timeouts are counted so diagnose can surface them`() {
        assertEquals(0, VetoTimeoutCounter.count)
        VetoTimeoutCounter.increment()
        VetoTimeoutCounter.increment()
        assertEquals(2, VetoTimeoutCounter.count)
    }
}
