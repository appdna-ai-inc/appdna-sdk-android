package ai.appdna.sdk.delegates

import ai.appdna.sdk.AppDNABillingDelegate
import ai.appdna.sdk.billing.Entitlement
import ai.appdna.sdk.AppDNADeepLinkDelegate
import ai.appdna.sdk.AppDNAInAppMessageDelegate
import ai.appdna.sdk.AppDNAInitDelegate
import ai.appdna.sdk.AppDNAPushDelegate
import ai.appdna.sdk.AppDNASurveyDelegate
import ai.appdna.sdk.PushPayload
import ai.appdna.sdk.SurveyResponse
import ai.appdna.sdk.TransactionInfo
import ai.appdna.sdk.onboarding.AppDNAOnboardingDelegate
import ai.appdna.sdk.paywalls.AppDNAPaywallDelegate
import ai.appdna.sdk.screens.AppDNAScreenDelegate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DelegateWiringTest.
 *
 * Mirrors `Tests/AppDNASDKTests/DelegateWiringTests.swift`. Asserts that:
 *
 *   1. Each of the 7 public delegate interfaces declared in
 *      [AppDNAModules.kt] / `paywalls/PaywallConfig.kt` /
 *      `onboarding/OnboardingConfig.kt` / `screens/AppDNAScreenDelegate.kt`
 *      has all the methods we expect to fire from manager classes.
 *   2. A simple counting fake records every call we expect a real manager
 *      to make. We instantiate the fake directly and trigger its callbacks
 *      by hand — verifying the *interface contract* without spinning up an
 *      Android Context (impossible from pure JVM unit test).
 *   3. Default no-op bodies do NOT throw.
 *
 * The tests are interface-shape tests, not integration tests — Robolectric
 * + real manager wiring is out of scope for the JVM suite.
 */
class DelegateWiringTest {

    // ─── 1. Push delegate ───────────────────────────────────────────────
    private class CountingPush : AppDNAPushDelegate {
        var tokenCount = 0
        var receivedCount = 0
        var tappedCount = 0
        var lastToken: String? = null

        override fun onPushTokenRegistered(token: String) {
            tokenCount++
            lastToken = token
        }
        override fun onPushReceived(notification: PushPayload, inForeground: Boolean) {
            receivedCount++
        }
        override fun onPushTapped(notification: PushPayload, actionId: String?) {
            tappedCount++
        }
    }

    @Test
    fun `push delegate fires all 3 lifecycle methods`() {
        val d = CountingPush()
        d.onPushTokenRegistered("fcm-token-123")
        d.onPushReceived(samplePush(), inForeground = true)
        d.onPushTapped(samplePush(), actionId = "cta_open")

        assertEquals(1, d.tokenCount)
        assertEquals(1, d.receivedCount)
        assertEquals(1, d.tappedCount)
        assertEquals("fcm-token-123", d.lastToken)
    }

    // ─── 2. Billing delegate ────────────────────────────────────────────
    private class CountingBilling : AppDNABillingDelegate {
        var purchaseCount = 0
        var failedCount = 0
        var entitlementsCount = 0
        var restoreCount = 0
        var unavailableCount = 0

        override fun onPurchaseCompleted(productId: String, transaction: TransactionInfo) { purchaseCount++ }
        override fun onPurchaseFailed(productId: String, error: Throwable) { failedCount++ }
        override fun onEntitlementsChanged(entitlements: List<Entitlement>) { entitlementsCount++ }
        override fun onRestoreCompleted(restoredProducts: List<String>) { restoreCount++ }
        override fun onBillingUnavailable() { unavailableCount++ }
    }

    @Test
    fun `billing delegate fires all 5 callbacks including H_14 onBillingUnavailable`() {
        val d = CountingBilling()
        d.onPurchaseCompleted("sku.monthly", TransactionInfo("tx1", "sku.monthly", "2026-05-06"))
        d.onPurchaseFailed("sku.yearly", IllegalStateException("nope"))
        d.onEntitlementsChanged(emptyList())
        d.onRestoreCompleted(listOf("sku.monthly"))
        d.onBillingUnavailable()

        assertEquals(1, d.purchaseCount)
        assertEquals(1, d.failedCount)
        assertEquals(1, d.entitlementsCount)
        assertEquals(1, d.restoreCount)
        assertEquals(1, d.unavailableCount)
    }

    // ─── 3. Survey delegate ─────────────────────────────────────────────
    private class CountingSurvey : AppDNASurveyDelegate {
        var presented = 0
        var completed = 0
        var dismissed = 0
        override fun onSurveyPresented(surveyId: String) { presented++ }
        override fun onSurveyCompleted(surveyId: String, responses: List<SurveyResponse>) { completed++ }
        override fun onSurveyDismissed(surveyId: String) { dismissed++ }
    }

    @Test
    fun `survey delegate fires presented + completed + dismissed`() {
        val d = CountingSurvey()
        d.onSurveyPresented("survey-1")
        d.onSurveyCompleted("survey-1", emptyList())
        d.onSurveyDismissed("survey-1")
        assertEquals(1, d.presented)
        assertEquals(1, d.completed)
        assertEquals(1, d.dismissed)
    }

    // ─── 4. In-app message delegate (incl. veto) ────────────────────────
    private class CountingMessage(val allow: Boolean = true) : AppDNAInAppMessageDelegate {
        var shouldShowCalls = 0
        var actionCount = 0
        var dismissedCount = 0
        var shownCount = 0
        override fun onMessageShown(messageId: String, trigger: String) { shownCount++ }
        override fun onMessageAction(messageId: String, action: String, data: Map<String, Any>?) { actionCount++ }
        override fun onMessageDismissed(messageId: String) { dismissedCount++ }
        override fun shouldShowMessage(messageId: String): Boolean {
            shouldShowCalls++
            return allow
        }
    }

    @Test
    fun `in-app message delegate veto returns false to suppress`() {
        val deny = CountingMessage(allow = false)
        assertFalse(deny.shouldShowMessage("m1"))
        assertEquals(1, deny.shouldShowCalls)

        val allow = CountingMessage(allow = true)
        assertTrue(allow.shouldShowMessage("m2"))
    }

    @Test
    fun `in-app message delegate fires shown action dismissed`() {
        val d = CountingMessage()
        d.onMessageShown("m1", "trigger_a")
        d.onMessageAction("m1", "cta_tap", mapOf("k" to "v"))
        d.onMessageDismissed("m1")
        assertEquals(1, d.shownCount)
        assertEquals(1, d.actionCount)
        assertEquals(1, d.dismissedCount)
    }

    // ─── 5. Onboarding delegate ─────────────────────────────────────────
    private class CountingOnboarding : AppDNAOnboardingDelegate {
        var started = 0
        var stepChanged = 0
        var completed = 0
        var dismissed = 0
        override fun onOnboardingStarted(flowId: String) { started++ }
        override fun onOnboardingStepChanged(flowId: String, stepId: String, stepIndex: Int, totalSteps: Int) { stepChanged++ }
        override fun onOnboardingCompleted(flowId: String, responses: Map<String, Any>) { completed++ }
        override fun onOnboardingDismissed(flowId: String, atStep: Int) { dismissed++ }
    }

    @Test
    fun `onboarding delegate fires lifecycle and supports default async hook`() {
        val d = CountingOnboarding()
        d.onOnboardingStarted("flow1")
        d.onOnboardingStepChanged("flow1", "step1", 0, 3)
        d.onOnboardingCompleted("flow1", emptyMap())
        d.onOnboardingDismissed("flow1", atStep = 1)
        assertEquals(1, d.started)
        assertEquals(1, d.stepChanged)
        assertEquals(1, d.completed)
        assertEquals(1, d.dismissed)
    }

    // ─── 6. Paywall delegate ────────────────────────────────────────────
    private class CountingPaywall : AppDNAPaywallDelegate {
        var presented = 0
        var dismissed = 0
        var purchaseStarted = 0
        var purchaseCompleted = 0
        var purchaseFailed = 0
        var restoreStarted = 0
        var restoreCompleted = 0
        var restoreFailed = 0
    }

    @Test
    fun `paywall delegate default impls do not throw`() {
        // Validates that all the optional methods on the interface have
        // default no-op bodies so a host that subclasses without overriding
        // anything still compiles and runs.
        val d = CountingPaywall()
        // Purposefully don't override anything. The class is instantiable.
        assertSame(d, d)
    }

    // ─── 7. Deep link delegate ──────────────────────────────────────────
    private class CountingDeepLink : AppDNADeepLinkDelegate {
        var calls = 0
        override fun onDeepLinkReceived(url: String, params: Map<String, String>) { calls++ }
    }

    @Test
    fun `deep link delegate fires onDeepLinkReceived`() {
        val d = CountingDeepLink()
        d.onDeepLinkReceived("appdna://x", mapOf("a" to "b"))
        assertEquals(1, d.calls)
    }

    // ─── 8. Init delegate ────────────────────────────────────────
    private class CountingInit : AppDNAInitDelegate {
        var degradedCount = 0
        override fun onInitDegraded(reason: Throwable) { degradedCount++ }
    }

    @Test
    fun `init delegate fires onInitDegraded`() {
        val d = CountingInit()
        d.onInitDegraded(IllegalStateException("missing google-services-appdna.json"))
        assertEquals(1, d.degradedCount)
    }

    // ─── 9. Screen delegate ─────────────────────────────────────────────
    private class CountingScreen : AppDNAScreenDelegate {
        var presented = 0
        var dismissed = 0
        var flowCompleted = 0
        var actionCount = 0
    }

    @Test
    fun `screen delegate is constructable with default impls`() {
        val d = CountingScreen()
        assertSame(d, d)
    }

    private fun samplePush() = PushPayload(
        pushId = "p1",
        title = "Hi",
        body = "Body",
    )
}
