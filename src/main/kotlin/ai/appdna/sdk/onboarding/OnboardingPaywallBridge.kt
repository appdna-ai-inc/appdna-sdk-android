package ai.appdna.sdk.onboarding

import ai.appdna.sdk.AppDNA
import ai.appdna.sdk.Log
import ai.appdna.sdk.TransactionInfo
import ai.appdna.sdk.paywalls.AppDNAPaywallDelegate
import ai.appdna.sdk.paywalls.PaywallAction
import android.os.Handler
import android.os.Looper

/**
 * OB-5 — port of iOS [OnboardingPaywallBridge].
 *
 * iOS source-of-truth: `OnboardingRenderer.swift:1707-1811`.
 *
 * When an onboarding flow's `next_step_rules` evaluator targets a
 * `paywall_trigger_<id>` graph node, the renderer must:
 *   1. Resolve the paywall id from the trigger.
 *   2. Present the paywall via [AppDNA.paywall.present] with THIS bridge
 *      registered as the [AppDNAPaywallDelegate].
 *   3. Forward every paywall lifecycle event to the host's globally-
 *      registered `AppDNA.paywall.listener` so a host that has wired up
 *      paywall analytics / overlays sees the SAME callbacks whether the
 *      paywall is presented standalone OR via an onboarding trigger.
 *   4. Track per-instance `didPurchase` / `didFail` so dismissal can
 *      route to the correct outcome target ("purchased" → on_success,
 *      "dismissed without purchase" → on_dismiss, "failed but stayed
 *      visible" → on_fail).
 *
 * Without this, Android's onboarding paywall_trigger nodes simply
 * emitted a `__paywall_trigger` marker into the responses payload and
 * called `onFlowCompleted()` — the host had to re-implement all the
 * chain routing, and `AppDNA.paywall.listener` was never invoked for
 * onboarding-presented paywalls (visible bug: hosts using the global
 * paywall delegate for telemetry saw a gap on onboarding-flow paywalls).
 *
 * One bridge instance per paywall presentation. Stateful: reads
 * `AppDNA.paywall.listener` fresh on every callback (not at init) so
 * a host that registers its delegate AFTER the bridge instance is
 * constructed still receives forwarded events.
 */
internal class OnboardingPaywallBridge(
    private val onPurchased: () -> Unit,
    private val onFailed: () -> Unit,
    private val onDismissedWithoutPurchase: () -> Unit,
) : AppDNAPaywallDelegate {

    // Per-instance routing state. These flags drive the dismissal
    // outcome decision in [onPaywallDismissed].
    @Volatile private var didPurchase = false
    @Volatile private var didFail = false

    private val mainHandler = Handler(Looper.getMainLooper())

    // ---- 12-method forwarding to AppDNA.paywall.listener -----------------

    override fun onPaywallPresented(paywallId: String) {
        forwardOnMain { it.onPaywallPresented(paywallId) }
    }

    override fun onPaywallAction(paywallId: String, action: PaywallAction) {
        forwardOnMain { it.onPaywallAction(paywallId, action) }
    }

    override fun onPaywallPurchaseStarted(paywallId: String, productId: String) {
        forwardOnMain { it.onPaywallPurchaseStarted(paywallId, productId) }
    }

    override fun onPaywallPurchaseCompleted(
        paywallId: String,
        productId: String,
        transaction: TransactionInfo,
    ) {
        forwardOnMain { it.onPaywallPurchaseCompleted(paywallId, productId, transaction) }
        didPurchase = true
    }

    override fun onPaywallPurchaseFailed(paywallId: String, error: Throwable) {
        onPaywallPurchaseFailed(
            paywallId, error, ai.appdna.sdk.billing.billingErrorType(error), productId = null,
        )
    }

    override fun onPaywallPurchaseFailed(paywallId: String, error: Throwable, errorType: String) {
        onPaywallPurchaseFailed(paywallId, error, errorType, productId = null)
    }

    // The SDK calls the widest overload; forward `errorType` AND `productId` on to the
    // host listener so neither the discriminator nor the failed product is dropped by the
    // onboarding→paywall bridge. Every narrower overload funnels through here, so the bridge's
    // dismissal bookkeeping (didFail/onFailed) runs exactly once per failure whichever arity the
    // caller used.
    override fun onPaywallPurchaseFailed(
        paywallId: String,
        error: Throwable,
        errorType: String,
        productId: String?,
    ) {
        forwardOnMain { it.onPaywallPurchaseFailed(paywallId, error, errorType, productId) }
        // iOS convention: paywall stays on screen after a failure (error
        // toast + retry allowed). Mark the intent so the host's
        // `on_fail_target` routing config can fire if the failure
        // ultimately leads to dismissal — but don't navigate away yet.
        didFail = true
        onFailed()
    }

    override fun onPaywallDismissed(paywallId: String) {
        forwardOnMain { it.onPaywallDismissed(paywallId) }
        if (didPurchase) {
            onPurchased()
        } else {
            // Failed-then-dismissed is treated as a dismiss for routing
            // purposes — the user closed the paywall without paying,
            // regardless of whether a transient error occurred earlier.
            onDismissedWithoutPurchase()
        }
    }

    override fun onPromoCodeSubmit(
        paywallId: String,
        code: String,
        completion: (Boolean) -> Unit,
    ) {
        // Synchronous forward — the SDK depends on the completion handler
        // being called. When no host delegate exists, mirror the protocol
        // default `completion(false)` so onboarding-embedded and standalone
        // paywall flows behave identically.
        val host = try { AppDNA.paywall.listener } catch (_: Throwable) { null }
        if (host != null) {
            host.onPromoCodeSubmit(paywallId, code, completion)
        } else {
            completion(false)
        }
    }

    override fun onPaywallRestoreStarted(paywallId: String) {
        forwardOnMain { it.onPaywallRestoreStarted(paywallId) }
    }

    override fun onPaywallRestoreCompleted(paywallId: String, productIds: List<String>) {
        forwardOnMain { it.onPaywallRestoreCompleted(paywallId, productIds) }
        // Treat a non-empty restore as equivalent to a
        // successful purchase so the subsequent dismiss routes via
        // on_success instead of on_dismiss. Mirrors the existing
        // onPaywallPurchaseCompleted pattern at line 75: just flip the
        // flag here, let the dismiss path call onPurchased() once.
        // Do NOT call onPurchased() directly here
        // PaywallActivity.dismissAfterRestore fires
        // snapshotOnDismiss which fires onPaywallDismissed on the
        // listener; the bridge's onPaywallDismissed at line 90 reads
        // didPurchase and routes once. Calling onPurchased() here too
        // would route twice. Empty productIds means "restore call
        // succeeded but found no entitlements" — leave didPurchase=false.
        if (productIds.isNotEmpty()) {
            didPurchase = true
        }
    }

    override fun onPaywallRestoreFailed(paywallId: String, error: Throwable) {
        forwardOnMain { it.onPaywallRestoreFailed(paywallId, error) }
    }

    override fun onPostPurchaseDeepLink(paywallId: String, url: String) {
        forwardOnMain { it.onPostPurchaseDeepLink(paywallId, url) }
    }

    override fun onPostPurchaseNextStep(paywallId: String) {
        forwardOnMain { it.onPostPurchaseNextStep(paywallId) }
    }

    // ---- internals -------------------------------------------------------

    /**
     * Read `AppDNA.paywall.listener` fresh on every call (not captured at
     * init) and dispatch to the host on the main thread. iOS does the
     * equivalent at `OnboardingRenderer.swift:1802-1809`; the main-thread
     * dispatch avoids data races against `AppDNA.paywall.setDelegate(...)`
     * which is a non-atomic mutable slot.
     */
    private inline fun forwardOnMain(crossinline block: (AppDNAPaywallDelegate) -> Unit) {
        // iOS reads `AppDNA.paywall.delegate` INSIDE the
        // `DispatchQueue.main.async` closure (OnboardingRenderer.swift
        // :1804-1807). Mirror that exactly: read the listener slot at
        // dispatch time, not at call time. Otherwise a host that
        // calls `setDelegate(null)` between the off-main capture and
        // the main-thread execution would still see the stale ref.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            val host = try { AppDNA.paywall.listener } catch (_: Throwable) { null } ?: return
            try {
                block(host)
            } catch (e: Throwable) {
                Log.warning("OnboardingPaywallBridge fan-out threw: ${e.message}")
            }
        } else {
            mainHandler.post {
                val host = try { AppDNA.paywall.listener } catch (_: Throwable) { null } ?: return@post
                try {
                    block(host)
                } catch (e: Throwable) {
                    Log.warning("OnboardingPaywallBridge fan-out threw: ${e.message}")
                }
            }
        }
    }
}
