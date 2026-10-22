package ai.appdna.sdk.paywalls

import android.app.Activity
import ai.appdna.sdk.AppDNA
import ai.appdna.sdk.Log
import ai.appdna.sdk.PurchaseCancelledException
import ai.appdna.sdk.PurchaseFailedException
import ai.appdna.sdk.PurchasePendingException
import ai.appdna.sdk.config.RemoteConfigManager
import ai.appdna.sdk.events.EventTracker
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Selection algorithm, lifted out of [PaywallManager.presentByPlacement] so it can
 * be exercised without an Activity or a RemoteConfigManager.
 *
 * Fix: `audience_rules` was cast to `Map` only. The console also writes the LIST
 * shape (`[{trait, operator, value}, ...]`) — for those paywalls the cast produced `null`, which
 * this code read as "no targeting" (matches EVERY user) and as priority 0 (so ordering between
 * candidates was arbitrary/insertion-ordered). [AudienceRuleSet.fromAny] accepts both shapes,
 * which is what OnboardingFlowManager already did.
 */
internal fun selectPaywallForPlacement(
    all: Collection<PaywallConfig>,
    placement: String,
    traits: Map<String, Any>,
): PaywallConfig? {
    // Filter by placement, then sort by audience_rules.priority desc.
    val candidates = all
        .filter { it.placement == placement }
        .sortedByDescending { pw ->
            ai.appdna.sdk.core.AudienceRuleSet.fromAny(pw.audience_rules)?.priority ?: 0
        }

    // First candidate whose rules evaluate true wins. No rules == match (fallback paywall).
    return candidates.firstOrNull { pw ->
        val rules = pw.audience_rules ?: return@firstOrNull true
        try {
            val ruleSet = ai.appdna.sdk.core.AudienceRuleSet.fromAny(rules)
                ?: return@firstOrNull true
            ai.appdna.sdk.core.AudienceRuleEvaluator.evaluate(ruleSet, traits)
        } catch (_: Throwable) {
            true
        }
    }
}

/** What a SUCCESSFUL restore must produce: analytics, the delegate payload, and the dismiss call. */
internal data class RestoreOutcome(
    val events: List<Pair<String, Map<String, Any>>>,
    /** Fire `AppDNAPaywallDelegate.onPaywallRestoreCompleted(paywallId, this)`. */
    val restoredProductIds: List<String>,
    /** Auto-dismiss the live paywall. */
    val shouldDismiss: Boolean,
)

/**
 * The restore success mapping, lifted out of [PaywallManager.handleRestore] so the events + the
 * dismiss decision are assertable without a Play `BillingClient`.
 *
 * An EMPTY restore ("the call worked, but you own nothing") deliberately leaves the paywall up so
 * the user can still buy; a host that wants to drive dismissal itself sets
 * `skipNextAutoDismissOnRestore` inside its delegate body ([hostRequestedSkip]).
 */
internal fun restoreOutcome(
    paywallId: String,
    productIds: List<String>,
    hostRequestedSkip: Boolean,
    // The products this same restore just REPORTED (purchase_completed) are
    // not counted as restored.
    justReported: Set<String> = emptySet(),
): RestoreOutcome = RestoreOutcome(
    events = listOf(
        "purchase_restored" to ai.appdna.sdk.billing.BillingEventProps.marked(mapOf(
            "paywall_id" to paywallId,
            "restored_count" to productIds.count { it !in justReported },
        )),
    ),
    restoredProductIds = productIds,
    shouldDismiss = productIds.isNotEmpty() && !hostRequestedSkip,
)

/**
 * Manages paywall presentation, purchase flow, restore flow, and event tracking (Android).
 * Mirrors iOS [Paywalls/PaywallManager.swift].
 *
 * Owns:
 *   - Plan-tap → BillingModule.purchase → purchase events + delegate fires
 *     + post_purchase.on_success dispatch.
 *   - Plan-tap failure → purchase_failed event + delegate fire +
 *     post_purchase.on_failure dispatch.
 *   - Restore-tap → restore lifecycle (started/completed/failed) on
 *     [AppDNAPaywallDelegate].
 *   - handlePostPurchaseSuccess (4 actions) + handlePostPurchaseFailure
 *     (3 actions) parity with iOS.
 */
internal class PaywallManager(
    private val remoteConfigManager: RemoteConfigManager,
    private val eventTracker: EventTracker,
    // Consulted at present-time for a running paywall
    // experiment targeting the entity being shown.
    private val experimentManager: ai.appdna.sdk.config.ExperimentManager? = null,
) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /**
     * Present the highest-priority paywall whose
     * `placement` matches and whose audience rules evaluate true for the
     * current user.  Mirrors iOS
     * `Paywalls/PaywallManager.swift:28-58 presentByPlacement(...)`.
     *
     * Selection algorithm (must match iOS):
     *   1. filter `getAllPaywalls()` by `placement == placement`,
     *   2. sort descending by `audience_rules.priority` (default 0),
     *   3. pick first whose `audience_rules` evaluates true (no rules == match).
     *
     * Returns silently when no paywall matches — host can fall back to a
     * static experience or omit the placement.
     */
    /**
     * Would [present] find something to show? The lookup, without the presentation.
     *
     * Exists so `AppDNA.presentPaywall` can RETURN whether anything will happen. It used to return
     * `Unit`, so every wrapper resolved its promise successfully on a paywall id that does not exist —
     * `await AppDNA.paywall.present('typo_id')` reported success and showed nothing, forever.
     */
    fun hasPaywall(id: String): Boolean = remoteConfigManager.getPaywallConfig(id) != null

    /**
     * Would [presentByPlacement] find something to show? Runs the SAME selector the presentation runs
     * — not a lookalike — so the two can never disagree about what "no paywall here" means.
     */
    fun hasPaywallForPlacement(placement: String): Boolean =
        selectPaywallForPlacement(
            all = remoteConfigManager.getAllPaywalls().values.toList(),
            placement = placement,
            traits = AppDNA.getUserTraits(),
        ) != null

    fun presentByPlacement(
        activity: Activity,
        placement: String,
        context: PaywallContext? = null,
        listener: AppDNAPaywallDelegate? = null,
    ) {
        val match = selectPaywallForPlacement(
            all = remoteConfigManager.getAllPaywalls().values.toList(),
            placement = placement,
            traits = AppDNA.getUserTraits(),
        )
        if (match == null) {
            Log.warning("No paywall found for placement: $placement")
            return
        }
        present(activity = activity, id = match.id, context = context, listener = listener)
    }

    /**
     * Present a paywall. Must be called with a valid Activity.
     */
    fun present(
        activity: Activity,
        id: String,
        context: PaywallContext? = null,
        listener: AppDNAPaywallDelegate? = null,
    ) {
        val activeConfig = remoteConfigManager.getPaywallConfig(id)
        if (activeConfig == null) {
            Log.error("Paywall config not found for id: $id")
            // No product was ever selected here (the paywall never rendered), so
            // `productId` is genuinely null rather than unknown.
            val configError = IllegalStateException("Paywall config not found")
            listener?.onPaywallPurchaseFailed(
                paywallId = id,
                error = configError,
                errorType = ai.appdna.sdk.billing.billingErrorType(configError),
                productId = null,
            )
            return
        }

        // Experiment-aware presentation. If a `running`
        // paywall experiment targets this entity and the user buckets into the
        // treatment, render the treatment `payload` config instead of the
        // active one. Control / non-bucketed / old-doc → active (cohort
        // isolation).
        var config = activeConfig
        val resolution = experimentManager?.resolveSurfacePresentation("paywall", id)
        if (resolution is ai.appdna.sdk.config.ExperimentManager.SurfaceResolution.RenderTreatment) {
            val treatment = PaywallConfigParser.parseSinglePaywall(id, resolution.payload)
            if (treatment != null) {
                Log.info("Paywall $id rendering experiment treatment variant")
                config = treatment
            }
        }

        // Track view event. `customData` is merged in here — this is its
        // only consumer, and a parameter with no consumer is a parameter that does nothing.
        val viewProps = mutableMapOf<String, Any>(
            "paywall_id" to id,
            "placement" to (context?.placement ?: "unknown"),
        )
        context?.customData?.forEach { (key, value) ->
            if (key in PaywallContext.RESERVED_EVENT_KEYS) {
                Log.warning("PaywallContext.customData key '$key' is reserved and was dropped")
            } else {
                viewProps[key] = value
            }
        }
        /*
         #923 — the view event fires AFTER the store lookup (below), not here, so it describes the
         screen the user actually saw. It carries `price_source` per plan: `store`, or one of
         `authored_opt_out` / `authored_no_product` / `authored_lookup_failed`. That is what
         answers "why is my paywall showing the old price" — without it the question has no answer
         in the data, and the three authored reasons are indistinguishable from outside.
        */

        // Prefetch all paywall image URLs
        // before launching Activity, mirroring iOS PaywallManager.swift
        // :96-148 + collectImageURLs at :154-198. Was missing — every
        // paywall presentation showed AsyncImage placeholder flash on
        // background, hero, plan icons, testimonial avatars while iOS
        // rendered fully loaded. Walks background, layout.background, all
        // sections (header image_url, reviews avatar_urls, plans
        // image_urls, testimonials, items[].image_url).
        try {
            val urls = collectImageURLs(config)
            if (urls.isNotEmpty()) {
                ai.appdna.sdk.core.ImagePreloader(activity.applicationContext).prefetch(urls)
            }
        } catch (_: Throwable) { /* never block presentation on prefetch */ }

        /*
         #923 — ASK PLAY WHAT THESE PRODUCTS COST, before the Activity starts.

         Until this, `price_display` was a free-text field and the only thing a user ever saw: a
         price change in Play Console did not reach the paywall, so Play charged the new price
         while the screen showed the old one, in the author's currency whatever the user's own was.
         `PriceResolver` could do the lookup all along; its callers were both post-purchase
         analytics.

         🔴 BOUNDED, AND IT ALWAYS LAUNCHES. The lookup gets 1.5s — the same budget iOS gives it —
         and a failure, an empty answer or a deadline all leave the map empty, which falls every
         plan back to its authored price. The resolver's in-memory cache answers a second
         presentation for free, so the wait is paid once per session at most.

         The launch moved INSIDE a coroutine, which is the one behavioural cost: this presentation
         is no longer synchronous with the call. Everything after it already was — the Activity
         starts asynchronously regardless — and `scope` is the manager's own, cancelled by
         `shutdown()`, so a paywall cannot start after the SDK has gone.
        */
        // A snapshot, because `config` above is a captured `var` and Kotlin will not smart-cast one.
        val presented = config
        scope.launch {
            // #933 — price and period resolved together, inside the same budget, so a paywall
            // cannot show this month's price against last month's cadence.
            val facts = ai.appdna.sdk.billing.StorePriceLookup.facts(
                resolver = ai.appdna.sdk.AppDNA.billing.manager?.priceResolver,
                productIds = (presented.plans ?: emptyList()).map { it.product_id },
            )
            val prices = facts.mapNotNull { (id, f) -> f.price?.let { id to it } }.toMap()
            val periods = facts.mapNotNull { (id, f) -> f.periodIso?.let { id to it } }.toMap()
            // #933 slice 3 — present ONLY for a product Play returned a free-trial offer for, which
            // is Play saying this user is eligible (it filters the offer list). A user who has
            // already used the trial yields no entry and therefore no sentence.
            val trials = facts.mapNotNull { (id, f) -> f.trialIso?.let { id to it } }.toMap()
            // Every place plans live, not only the root array — see `applyingStorePrices`.
            val withPrices = presented.applyingStorePrices(prices)
                .applyingStorePeriods(periods)
                .applyingStoreTrials(trials)
            val sources = (withPrices.plans ?: emptyList()).map { it.priceSource }
            if (sources.isNotEmpty()) {
                viewProps["price_source"] = sources.joinToString(",")
                viewProps["store_prices_resolved"] = sources.count { it == "store" }
            }
            eventTracker.track("paywall_view", viewProps)
            launchPaywallActivity(activity, id, withPrices, context, listener)
        }
    }

    /**
     * The Activity launch, lifted out of [present] so the store-price lookup above can await
     * before it without burying the whole call in one coroutine body (#923).
     */
    private fun launchPaywallActivity(
        activity: Activity,
        id: String,
        config: PaywallConfig,
        context: PaywallContext?,
        listener: AppDNAPaywallDelegate?,
    ) {
        PaywallActivity.launch(
            context = activity,
            paywallId = id,
            config = config,
            paywallContext = context,
            onAppear = {
                listener?.onPaywallPresented(paywallId = id)
            },
            onDismiss = { reason ->
                eventTracker.track(
                    "paywall_close",
                    mapOf(
                        "paywall_id" to id,
                        "dismiss_reason" to reason.value,
                    ),
                )
                listener?.onPaywallDismissed(paywallId = id)
            },
            /*
             * #1074 — CTA_TAPPED MOVED OUT OF HERE, to the tap site.
             *
             * It used to fire from this closure, which the renderer reaches only once its
             * `plan != null` guard has passed — so a tap that could not become a purchase told the
             * host nothing at all, and "the SDK never heard it" and "the store refused" were the
             * same silence (#1072). The action means "the user tapped the CTA", so it belongs
             * wherever they did.
             */
            onAction = { action ->
                listener?.onPaywallAction(paywallId = id, action = action)
            },
            onPlanSelected = { plan, metadata ->
                // Invoke BillingModule + dispatch post-purchase.
                handlePurchase(
                    activity = activity,
                    paywallId = id,
                    plan = plan,
                    config = config,
                    metadata = metadata,
                    listener = listener,
                )
            },
            // Wire delegate promo code callback
            onPromoCodeSubmit = if (listener != null) {
                { code, completion ->
                    listener.onPromoCodeSubmit(paywallId = id, code = code, completion = completion)
                }
            } else null,
            // Wire restore lifecycle
            onRestore = {
                handleRestore(paywallId = id, listener = listener)
            },
        )
    }

    // MARK: - Purchase flow

    /**
     * Mirrors iOS `PaywallManager.handlePurchase`. Calls
     * [AppDNA.billing.purchase] and on success dispatches
     * [handlePostPurchaseSuccess]; on failure dispatches
     * [handlePostPurchaseFailure].
     *
     * ⚠️ THIS LAYER EMITS NO PURCHASE EVENTS. It used to emit `purchase_started` /
     * `purchase_completed` / `purchase_canceled` / `purchase_pending` / `purchase_failed` — and so
     * does [NativeBillingManager], which sits underneath it on the very same purchase. Every Android
     * paywall purchase therefore produced TWO of each event, with two different property shapes (so
     * dbt could not dedup them), and Android paywall conversion + purchase volume read ~2× iOS for
     * identical user behaviour. The billing manager owns the purchase-event family now, because it is
     * the only layer on BOTH this path and the direct `AppDNA.billing.purchase()` path, and the only
     * one that sees Play's terminal outcome. What this layer contributes — the paywall's AC-038
     * metadata (toggle states, promo_code) — is handed DOWN to the manager instead of being emitted
     * a second time.
     *
     * `internal` (not private) so a test can drive the whole paywall→billing purchase chain and count
     * what the two layers emit together — see `PurchaseEventsEmittedOnceTest`.
     */
    internal fun handlePurchase(
        activity: Activity,
        paywallId: String,
        plan: PaywallPlan,
        config: PaywallConfig,
        metadata: Map<String, Any>,
        listener: AppDNAPaywallDelegate?,
    ) {
        // Evaluated BEFORE anything else: with no billing manager, or a policy
        // under which the SDK cannot purchase (RevenueCat, Adapty, none), the tap fails loudly — one
        // purchase_failed, onPaywallPurchaseFailed(providerNotAvailable), the failure routing — and NO
        // purchase_started / onPaywallPurchaseStarted (the purchase never started).
        // Before `configure` has chosen a policy — including a tap
        // between `configure()` and the launched bootstrap's billing init — the tap fails with the same
        // `unknown` "not configured yet" error as `AppDNA.billing.purchase`, not providerNotAvailable.
        if (!AppDNA.billing.ownershipPolicy.isConfigured) {
            val message = ai.appdna.sdk.billing.BillingOwnershipPolicy.NOT_CONFIGURED_MESSAGE
            eventTracker.track(
                "purchase_failed",
                ai.appdna.sdk.billing.BillingEventProps.marked(mapOf(
                    "paywall_id" to paywallId,
                    "product_id" to plan.product_id,
                    "reason" to "not_configured",
                    "error" to message,
                    "error_type" to "unknown",
                )),
            )
            listener?.onPaywallPurchaseFailed(
                paywallId = paywallId,
                error = IllegalStateException(message),
                errorType = "unknown",
                productId = plan.product_id,
            )
            handlePostPurchaseFailure(
                config = config.post_purchase?.on_failure,
                paywallId = paywallId,
                listener = listener,
            )
            return
        }
        val mgrForPolicy = AppDNA.billing.manager
        if (mgrForPolicy == null || !AppDNA.billing.ownershipPolicy.sdkCanPurchase) {
            // The one refusal message (iOS `BillingOwnershipPolicy.refusalMessage`).
            val message = ai.appdna.sdk.billing.BillingOwnership.refusalMessage(mgrForPolicy?.providerName ?: "none")
            val error = ai.appdna.sdk.billing.BillingError.ProviderNotAvailable(message)
            eventTracker.track(
                "purchase_failed",
                ai.appdna.sdk.billing.BillingEventProps.marked(mapOf(
                    "paywall_id" to paywallId,
                    "product_id" to plan.product_id,
                    "error" to message,
                    "error_type" to "providerNotAvailable",
                )),
            )
            listener?.onPaywallPurchaseFailed(
                paywallId = paywallId,
                error = error,
                errorType = "providerNotAvailable",
                productId = plan.product_id,
            )
            handlePostPurchaseFailure(
                config = config.post_purchase?.on_failure,
                paywallId = paywallId,
                listener = listener,
            )
            return
        }
        listener?.onPaywallPurchaseStarted(paywallId = paywallId, productId = plan.product_id)
        // 🔴 The ids must travel WITH the metadata. When the purchase emits moved down to the billing
        // manager (so they stopped firing twice), `currentPaywallId` / `currentExperimentId` were left
        // read-only: the manager read them into every purchase event and NOTHING in production ever
        // wrote them. So `paywall_id` shipped as "" on every Android purchase — blanking paywall
        // conversion, MTPU-by-paywall and every paywall experiment breakdown — while iOS still sent the
        // real id, so the two platforms disagreed on the same column.
        //
        // It was green only because the TEST assigned the field itself. A test driving a path
        // production cannot reach is not a test.
        // Never overwrite the attribution of a purchase already in flight (the second
        // purchase() then fails with purchase_in_progress).
        AppDNA.billing.manager?.takeIf { !it.purchaseInFlight }?.let { mgr ->
            mgr.currentPaywallMetadata = metadata
            mgr.currentPaywallId = paywallId
        }

        scope.launch {
            try {
                val tx = AppDNA.billing.purchase(activity, plan.product_id)

                listener?.onPaywallPurchaseCompleted(
                    paywallId = paywallId,
                    productId = tx.productId,
                    transaction = tx,
                )
                handlePostPurchaseSuccess(
                    config = config.post_purchase?.on_success,
                    paywallId = paywallId,
                    listener = listener,
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                // A cancelled paywall scope (`shutdown()` mid-purchase) is not a failed
                // purchase — no onPaywallPurchaseFailed, no failure routing. The purchase itself is not lost:
                // if Play completes it, reports and delivers it through the delivery queue.
                // A paywall still on screen re-enables its CTA.
                PaywallActivity.signalPurchaseEnded()
                throw e
            } catch (e: PurchaseCancelledException) {
                // No `purchase_canceled` emit here — NativeBillingManager's USER_CANCELED branch
                // already emitted exactly one, with these same property names.
                listener?.onPaywallPurchaseFailed(
                    paywallId = paywallId,
                    error = e,
                    errorType = ai.appdna.sdk.billing.billingErrorType(e),
                    productId = plan.product_id,
                )
                handlePostPurchaseFailure(
                    config = config.post_purchase?.on_failure,
                    paywallId = paywallId,
                    listener = listener,
                )
            } catch (e: PurchasePendingException) {
                // No `purchase_pending` emit here — handleSuccessfulPurchase already emitted exactly
                // one when Play reported PurchaseState.PENDING.
                listener?.onPaywallPurchaseFailed(
                    paywallId = paywallId,
                    error = e,
                    errorType = ai.appdna.sdk.billing.billingErrorType(e),
                    productId = plan.product_id,
                )
                handlePostPurchaseFailure(
                    config = config.post_purchase?.on_failure,
                    paywallId = paywallId,
                    listener = listener,
                )
            } catch (e: Exception) {
                // No `purchase_failed` emit here — every terminal failure of the manager's purchase()
                // emits exactly one (incl. the early returns: no client, unknown product, launch
                // rejected), carrying the same `paywall_id` / `product_id` / `error` / `error_type`
                // keys iOS uses (PurchaseFailedProps.build, PaywallManager.swift:515).
                // `error_type` is still needed HERE for the delegate callback.
                val errorType = ai.appdna.sdk.billing.billingErrorType(e)
                val purchaseError = if (e is PurchaseFailedException) e else Exception(e.message, e)
                listener?.onPaywallPurchaseFailed(
                    paywallId = paywallId,
                    error = purchaseError,
                    errorType = errorType,
                    productId = plan.product_id,
                )
                handlePostPurchaseFailure(
                    config = config.post_purchase?.on_failure,
                    paywallId = paywallId,
                    listener = listener,
                )
            }
        }
    }

    // MARK: - Post-purchase actions

    /**
     * Mirrors iOS `PaywallManager.handlePostPurchaseSuccess` (4 actions:
     * dismiss / show_message / deep_link / next_step). The delegate's
     * `onPaywallDismissed` fires for every branch except `dismiss` (the
     * Activity's own onDismiss already fires there); deep_link / next_step
     * additionally fire `onPostPurchaseDeepLink` / `onPostPurchaseNextStep`.
     */
    private fun handlePostPurchaseSuccess(
        config: PostPurchaseSuccessConfig?,
        paywallId: String,
        listener: AppDNAPaywallDelegate?,
    ) {
        if (config == null) {
            // Legacy: delegate-only behavior — the paywall stays on screen for the host to close, so
            // re-enable its CTA (without this it kept spinning after a success).
            PaywallActivity.signalPurchaseEnded()
            return
        }
        val delayMs = (config.delay_ms ?: 2000).toLong()
        scope.launch {
            // After a successful purchase, iOS DISMISSES the paywall in EVERY
            // success action (PaywallManager.swift:355-391 `viewController.dismiss`). Android fired the
            // delegates but NEVER finished the Activity, so the paywall stayed on screen.
            // Fix — dismiss with `suppressDismissCallback = true` so the Activity's onDestroy
            // backstop does NOT re-run the onDismiss lambda (which would emit `paywall_close` AND
            // onPaywallDismissed). iOS fires neither a paywall_close nor a second onPaywallDismissed on
            // the post-purchase path — it just dismisses and fires onPaywallDismissed ONCE (in the
            // completion) for show_message/deep_link/next_step, and NOTHING for plain `dismiss`. This
            // mirrors that exactly; the explicit delegate calls below are the single source.
            when (config.action) {
                "dismiss" -> {
                    delay(delayMs)
                    PaywallActivity.dismissCurrent(suppressDismissCallback = true)
                }
                "show_message" -> {
                    // Surface confetti + lottie overlay through the companion
                    // slot watched by the live PaywallActivity composition
                    // (mirrors iOS NotificationCenter `.paywallPurchaseSuccess`).
                    PaywallActivity.postPurchaseOverlay = PostPurchaseOverlayState(
                        // Default to "Welcome to Premium!" like iOS (PaywallManager.swift). A
                        // show_message action with a blank message (the console field is optional and its
                        // placeholder IS this string) rendered a blank overlay on Android; iOS shows the
                        // default success copy.
                        message = config.message?.takeIf { it.isNotBlank() } ?: "Welcome to Premium!",
                        confetti = config.confetti ?: false,
                        lottieUrl = config.lottie_url,
                    )
                    delay(delayMs)
                    PaywallActivity.dismissCurrent(suppressDismissCallback = true)
                    listener?.onPaywallDismissed(paywallId = paywallId)
                }
                "deep_link" -> {
                    delay(delayMs)
                    PaywallActivity.dismissCurrent(suppressDismissCallback = true)
                    listener?.onPaywallDismissed(paywallId = paywallId)
                    config.deep_link_url?.let { url ->
                        listener?.onPostPurchaseDeepLink(paywallId = paywallId, url = url)
                    }
                }
                "next_step" -> {
                    delay(delayMs)
                    PaywallActivity.dismissCurrent(suppressDismissCallback = true)
                    listener?.onPaywallDismissed(paywallId = paywallId)
                    listener?.onPostPurchaseNextStep(paywallId = paywallId)
                }
                // An unknown action leaves the paywall up (nothing dismisses it), so re-enable
                // its CTA as the no-config branch does; it kept spinning before.
                else -> PaywallActivity.signalPurchaseEnded()
            }
        }
    }

    /**
     * Mirrors iOS `PaywallManager.handlePostPurchaseFailure`. 3 actions:
     * `show_error`, `retry`, `dismiss`. Surface via the same companion slot
     * the success path uses but with `confetti=false` so the overlay only
     * shows the message text. (Listener gets the actual purchase error via
     * `onPaywallPurchaseFailed` already fired in handlePurchase.)
     */
    private fun handlePostPurchaseFailure(
        config: PostPurchaseFailureConfig?,
        paywallId: String,
        listener: AppDNAPaywallDelegate?,
    ) {
        // Every failed / cancelled / pending purchase re-enables the paywall's CTA,
        // whatever `on_failure` says (with no config, nothing else ever did).
        PaywallActivity.signalPurchaseEnded()
        if (config == null) return
        when (config.action) {
            "dismiss" -> {
                // Actually finish the
                // PaywallActivity matching iOS PaywallManager.swift:402-403
                // `viewController.dismiss(animated: true)`. Was only firing
                // delegate but leaving Activity on-screen.
                // Match iOS's failure-dismiss exactly: NO delegate, no paywall_close. Suppress
                // the onDestroy backstop (which would emit paywall_close + onPaywallDismissed) so this
                // path doesn't double-fire onPaywallDismissed + a spurious paywall_close — the same fix
                // applied to the SUCCESS branches in f2b524ff, previously left unfixed here.
                PaywallActivity.dismissCurrent(suppressDismissCallback = true)
            }
            "show_error", "retry" -> {
                PaywallActivity.postPurchaseOverlay = PostPurchaseOverlayState(
                    message = config.message ?: "Payment failed. Please try again.",
                    confetti = false,
                    lottieUrl = null,
                    // Propagate
                    // retry_text and action so the failure overlay can render
                    // a labelled retry CTA. Mirrors iOS PaywallManager.swift:
                    // 309-313 + PaywallRenderer.swift:351-353. Without this,
                    // console-configured retry button text + retry-vs-dismiss
                    // action were silently dropped.
                    retryText = config.retry_text,
                    action = config.action,
                    allowDismiss = config.allow_dismiss ?: true,
                )
            }
            else -> { /* unknown action — no-op */ }
        }
    }

    // MARK: - Restore flow

    /**
     * Mirrors iOS `PaywallManager.handleRestore`. Fires
     * [AppDNAPaywallDelegate.onPaywallRestoreStarted] before kicking off
     * [AppDNA.billing.restorePurchases]; on completion fires
     * `onPaywallRestoreCompleted(productIds)`; on failure fires
     * `onPaywallRestoreFailed(error)`.
     *
     * A restore the SDK cannot run is refused BEFORE `onPaywallRestoreStarted`, as [handlePurchase]
     * refuses a purchase before `onPaywallPurchaseStarted`:
     * - before `configure` has chosen a policy (or after `shutdown()`): the `unknown` "not configured
     *   yet" error;
     * - no billing, or a provider that owns restoring (`revenueCat` / `adapty` / `none`):
     *   `providerNotAvailable` (iOS never fired Started here, Android did).
     *
     * `internal` (not private) so a test can drive the refusal paths directly.
     */
    internal fun handleRestore(paywallId: String, listener: AppDNAPaywallDelegate?) {
        val policy = AppDNA.billing.ownershipPolicy
        val nativeMgr = AppDNA.billing.manager
        val refusal: Exception? = when {
            !policy.isConfigured -> IllegalStateException(ai.appdna.sdk.billing.BillingOwnershipPolicy.NOT_CONFIGURED_MESSAGE)
            nativeMgr == null -> ai.appdna.sdk.billing.BillingError.ProviderNotAvailable("No billing provider configured")
            !policy.sdkCanRestore -> ai.appdna.sdk.billing.BillingError.ProviderNotAvailable(
                ai.appdna.sdk.billing.BillingOwnership.refusalMessage(nativeMgr.providerName),
            )
            else -> null
        }
        if (refusal != null || nativeMgr == null) {
            val e = refusal ?: ai.appdna.sdk.billing.BillingError.ProviderNotAvailable("No billing provider configured")
            val props = mutableMapOf<String, Any>(
                "paywall_id" to paywallId,
                "error" to (e.message ?: "unknown"),
                "error_type" to ai.appdna.sdk.billing.billingErrorType(e),
            )
            if (!policy.isConfigured) props["reason"] = "not_configured"
            eventTracker.track("purchase_restore_failed", ai.appdna.sdk.billing.BillingEventProps.marked(props))
            listener?.onPaywallRestoreFailed(paywallId = paywallId, error = e)
            AppDNA.paywall.skipNextAutoDismissOnRestore = false
            Log.error("Restore refused for paywall $paywallId: ${e.message}")
            return
        }
        listener?.onPaywallRestoreStarted(paywallId = paywallId)
        scope.launch {
            try {
                // Reach the underlying NativeBillingManager via the
                // BillingModule's `internal var manager` (same Kotlin
                // module so internal visibility applies). The public
                // `BillingModule.restorePurchases()` is fire-and-forget
                // (Unit); for the delegate completion we want the typed
                // `List<Entitlement>` so we can pass productIds.
                // trackFailure = false: this path emits its own purchase_restore_failed (with paywall_id),
                // so a failure is tracked exactly once.
                val detail = nativeMgr.restoreDetailed(trackFailure = false)
                val productIds = detail.entitlements.map { it.productId }
                val outcome = restoreOutcome(paywallId, productIds, hostRequestedSkip = false, justReported = detail.reportedProducts)
                outcome.events.forEach { (name, props) -> eventTracker.track(name, props) }
                listener?.onPaywallRestoreCompleted(
                    paywallId = paywallId,
                    productIds = outcome.restoredProductIds,
                )
                Log.info("Restore completed for paywall $paywallId with ${productIds.size} products")
                // /B — clear the public
                // `skipNextAutoDismissOnRestore` flag on EVERY restore
                // terminal event (success-with-products, empty-success,
                // and the failure path below) so the one-shot flag can't
                // leak from one paywall presentation into the next.
                // Captured outside the early-return below.
                // The delegate body above may have set the flag synchronously, so the dismiss
                // decision is only valid once it has run — re-derive it with the real flag.
                val hostRequestedSkip = AppDNA.paywall.skipNextAutoDismissOnRestore
                AppDNA.paywall.skipNextAutoDismissOnRestore = false
                // Auto-dismiss the live PaywallActivity
                // when restore actually found entitlements. The delegate
                // forward above runs FIRST so a host that wants to handle
                // dismiss itself can flip `skipNextAutoDismissOnRestore`
                // synchronously inside its delegate body before we reach
                // this line. Empty restored array = "restore call worked
                // but user has no entitlements to restore" — leave paywall
                // up so user can either close manually or attempt a fresh
                // purchase.
                if (restoreOutcome(paywallId, productIds, hostRequestedSkip).shouldDismiss) {
                    PaywallActivity.activeInstance(paywallId)?.dismissAfterRestore()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // A cancelled paywall scope (`shutdown()`) is not a failed restore —
                // no purchase_restore_failed, no onPaywallRestoreFailed. The one-shot skip flag is still
                // cleared: the restore it was set for is over, so it must not leak into the next.
                AppDNA.paywall.skipNextAutoDismissOnRestore = false
                throw e
            } catch (e: Exception) {
                eventTracker.track(
                    "purchase_restore_failed",
                    ai.appdna.sdk.billing.BillingEventProps.marked(mapOf(
                        "paywall_id" to paywallId,
                        "error" to (e.message ?: "unknown"),
                        "error_type" to ai.appdna.sdk.billing.billingErrorType(e),
                    )),
                )
                listener?.onPaywallRestoreFailed(paywallId = paywallId, error = e)
                // Symmetric clear on failure too so
                // a host who set the flag for a restore that errored
                // doesn't carry the flag into the next paywall's restore.
                AppDNA.paywall.skipNextAutoDismissOnRestore = false
                Log.error("Restore failed: ${e.message}")
            }
        }
    }

    /**
     * Cancel the private scope so
     * pending paywall metadata fetches and post-purchase dispatches don't
     * outlive [AppDNA.shutdown]. Mirrors the equivalent tear-down already
     * present on EventQueue, BillingModule, etc.
     */
    internal fun shutdown() {
        scope.cancel()
    }

    /**
     * Gather every image URL referenced by a
     * paywall config, mirroring iOS PaywallManager.swift:154-198
     * `collectImageURLs`. Includes background, layout.background, all
     * sections (header image_url, plans image_urls, reviews avatar_urls,
     * items[].image_url, plans[].image_url at section + carousel pages
     * children). Filters to http(s) URLs.
     */
    private fun collectImageURLs(config: ai.appdna.sdk.paywalls.PaywallConfig): List<String> {
        val urls = mutableListOf<String>()
        fun addIfHttp(s: String?) {
            val u = s?.trim().orEmpty()
            if (u.startsWith("http://") || u.startsWith("https://")) urls.add(u)
        }
        addIfHttp(config.background?.image_url)
        addIfHttp(config.layout.background?.image_url)
        config.sections.forEach { sec ->
            val d = sec.data
            addIfHttp(d?.image_url)
            d?.reviews?.forEach { addIfHttp(it.avatar_url) }
            d?.plans?.forEach { addIfHttp(it.image_url) }
            d?.items?.forEach { item -> addIfHttp(item.icon) }
        }
        return urls.distinct()
    }
}
