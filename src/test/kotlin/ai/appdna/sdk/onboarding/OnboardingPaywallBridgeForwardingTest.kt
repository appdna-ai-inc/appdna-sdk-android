package ai.appdna.sdk.onboarding

import ai.appdna.sdk.AppDNA
import ai.appdna.sdk.TransactionInfo
import ai.appdna.sdk.paywalls.AppDNAPaywallDelegate
import ai.appdna.sdk.paywalls.PaywallAction
import android.os.Looper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * A paywall opened from an onboarding step must forward EVERY lifecycle callback to
 * the host's registered paywall delegate. A host that wires analytics onto `AppDNA.paywall.listener`
 * and then opens a paywall mid-onboarding must not silently get a hole in its funnel.
 *
 * 🔴 THIS TEST ASSERTED NOTHING, TWICE OVER.
 *
 *   1. It was `@Ignore`d, justified as "bridge verified live on every commit via Mac build bridge".
 *      Nothing verifies it on every commit. It simply never ran.
 *   2. Un-ignoring it would not have helped: it declared its own `ForwardingPaywallBridge` INSIDE the
 *      test file and asserted that the hand-written mirror forwarded 13 callbacks. Its own doc comment
 *      conceded the consequence — "if the live forwarder drops a callback this test will not catch it
 *      directly." The production class was never touched.
 *
 * It drives the REAL [OnboardingPaywallBridge] now. That class takes three lambdas and was always
 * constructible; the only thing standing in the way was `Handler(Looper.getMainLooper())`, and
 * Robolectric has had a main looper the whole time.
 */
@RunWith(RobolectricTestRunner::class)
class OnboardingPaywallBridgeForwardingTest {

    private val log = mutableListOf<String>()

    /** The host on the far side of the bridge — reaching it is the thing under test. */
    private val host = object : AppDNAPaywallDelegate {
        override fun onPaywallPresented(paywallId: String) { log += "presented:$paywallId" }
        override fun onPaywallAction(paywallId: String, action: PaywallAction) {
            log += "action:$paywallId:${action.name}"
        }
        override fun onPaywallPurchaseStarted(paywallId: String, productId: String) {
            log += "purchaseStarted:$paywallId:$productId"
        }
        override fun onPaywallPurchaseCompleted(
            paywallId: String,
            productId: String,
            transaction: TransactionInfo,
        ) { log += "purchaseCompleted:$paywallId:$productId" }

        // Overriding ONLY the 4-arg form is the realistic case for a host that wants the typed reason.
        // If the bridge forwards a narrower overload, the protocol default fires and errorType +
        // productId are erased before the host ever sees them.
        override fun onPaywallPurchaseFailed(
            paywallId: String,
            error: Throwable,
            errorType: String,
            productId: String?,
        ) { log += "purchaseFailed:$paywallId:$errorType:$productId" }

        override fun onPaywallRestoreStarted(paywallId: String) { log += "restoreStarted:$paywallId" }
        override fun onPaywallRestoreCompleted(paywallId: String, productIds: List<String>) {
            log += "restoreCompleted:$paywallId:${productIds.joinToString(",")}"
        }
        override fun onPaywallRestoreFailed(paywallId: String, error: Throwable) {
            log += "restoreFailed:$paywallId:${error.message}"
        }
        override fun onPostPurchaseDeepLink(paywallId: String, url: String) {
            log += "deepLink:$paywallId:$url"
        }
        override fun onPostPurchaseNextStep(paywallId: String) { log += "nextStep:$paywallId" }
        override fun onPaywallDismissed(paywallId: String) { log += "dismissed:$paywallId" }
        override fun onPromoCodeSubmit(paywallId: String, code: String, completion: (Boolean) -> Unit) {
            log += "promo:$paywallId:$code"
            completion(true)
        }
    }

    @After
    fun tearDown() {
        AppDNA.paywall.listener = null
    }

    /** The bridge forwards on the main looper; drain it so the assertions see the calls land. */
    private fun drainMainLooper() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun `the REAL bridge forwards every paywall callback through to the host delegate`() {
        AppDNA.paywall.listener = host
        var purchased = 0
        val bridge = OnboardingPaywallBridge(
            onPurchased = { purchased++ },
            onFailed = {},
            onDismissedWithoutPurchase = {},
        )
        val tx = TransactionInfo(transactionId = "txid", productId = "sku.month", purchaseDate = "2026-05-06")

        bridge.onPaywallPresented("pw1")
        bridge.onPaywallAction("pw1", PaywallAction.CTA_TAPPED)
        bridge.onPaywallPurchaseStarted("pw1", "sku.month")
        bridge.onPaywallPurchaseCompleted("pw1", "sku.month", tx)
        bridge.onPaywallRestoreStarted("pw1")
        bridge.onPaywallRestoreCompleted("pw1", listOf("sku.month"))
        bridge.onPaywallRestoreFailed("pw1", IllegalStateException("net"))
        bridge.onPostPurchaseDeepLink("pw1", "myapp://home")
        bridge.onPostPurchaseNextStep("pw1")

        var promoOk = false
        bridge.onPromoCodeSubmit("pw1", "FREE2026") { ok -> promoOk = ok }

        // Routing is deliberately DEFERRED to dismissal: a completed purchase only continues the flow
        // once the paywall actually closes, so the user is never yanked out from under a success state.
        bridge.onPaywallDismissed("pw1")
        drainMainLooper()

        assertTrue("the host's promo verdict must be the one the SDK acts on", promoOk)
        assertEquals(
            "the REAL bridge dropped a callback — the host's funnel has a hole",
            listOf(
                "presented:pw1",
                "action:pw1:CTA_TAPPED",
                "purchaseStarted:pw1:sku.month",
                "purchaseCompleted:pw1:sku.month",
                "restoreStarted:pw1",
                "restoreCompleted:pw1:sku.month",
                "restoreFailed:pw1:net",
                "deepLink:pw1:myapp://home",
                "nextStep:pw1",
                "promo:pw1:FREE2026",
                "dismissed:pw1",
            ).sorted(),
            log.sorted(),
        )
        assertEquals("a purchased-then-dismissed paywall must continue the flow exactly once", 1, purchased)
    }

    /**
     * The typed failure must survive the crossing. A wrapper host (RN/Flutter) receives the `error` as
     * an opaque platform object, so `errorType` and `productId` are the only way it can tell a user
     * cancel from a declined card, or know WHICH of two products failed.
     */
    @Test
    fun `a typed purchase failure reaches the host with errorType and productId intact`() {
        AppDNA.paywall.listener = host
        var failed = 0
        val bridge = OnboardingPaywallBridge(
            onPurchased = {}, onFailed = { failed++ }, onDismissedWithoutPurchase = {},
        )

        bridge.onPaywallPurchaseFailed("pw1", RuntimeException("boom"), "userCancelled", "pro_yearly")
        drainMainLooper()

        assertEquals(listOf("purchaseFailed:pw1:userCancelled:pro_yearly"), log)
        assertEquals(1, failed)
    }

    /** Dismissal with neither a purchase nor a failure is an abandonment; the flow must be told. */
    @Test
    fun `dismissing without purchasing routes the onboarding continuation as an abandonment`() {
        AppDNA.paywall.listener = host
        var purchased = 0
        var abandoned = 0
        val bridge = OnboardingPaywallBridge(
            onPurchased = { purchased++ },
            onFailed = {},
            onDismissedWithoutPurchase = { abandoned++ },
        )

        bridge.onPaywallPresented("pw1")
        bridge.onPaywallDismissed("pw1")
        drainMainLooper()

        assertEquals(0, purchased)
        assertEquals(1, abandoned)
        assertTrue("the host must still be told the paywall closed", log.contains("dismissed:pw1"))
    }
}
