package ai.appdna.sdk.billing

import ai.appdna.sdk.AppDNA
import ai.appdna.sdk.Log
import ai.appdna.sdk.TransactionInfo
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesResult
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.queryPurchasesAsync
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/** The decision for one purchase (§13a.2 "Purchase completion plan"). */
internal data class CompletionPlan(
    val verify: Boolean,
    val finalize: Finalize?,
    val emitCompleted: Boolean,
    val updateEntitlements: Boolean,
    /** Which precedence (1)–(5) matched — for logs and tests. */
    val precedence: Int,
)

/**
 * SPEC-497 §13a.2 — the pure, two-phase decision. `serverConsume == null` means "not verified yet": the
 * first call returns `verify` or a finalize-only plan; after the verify a second call with the reply's
 * `consume` returns the finalize plan. Precedence, first match wins:
 *   (1) `!ownsTransactions` → nothing;
 *   (2) reported with a finalize pending → retry that finalize only, regardless of anything else;
 *   (3) not reported and acknowledged (and never verify-attempted) → no emit, no entitlement change;
 *       an INAPP purchase is classified (a silent verify) and a consumable is consumed silently;
 *   (4) not reported and (unacknowledged or verify-attempted) → verify, finalize, emit;
 *   (5) reported and finalized → nothing.
 */
internal object PurchaseCompletionPlan {
    private val NOTHING = CompletionPlan(false, null, false, false, 0)

    fun decide(
        productType: String,
        serverConsume: Boolean?,
        ownsTransactions: Boolean,
        alreadyReported: Boolean,
        isAcknowledged: Boolean,
        pendingFinalize: Finalize?,
        verifyAttempted: Boolean,
    ): CompletionPlan {
        if (!ownsTransactions) return NOTHING.copy(precedence = 1)
        if (alreadyReported && pendingFinalize != null) {
            return CompletionPlan(verify = false, finalize = pendingFinalize, emitCompleted = false, updateEntitlements = false, precedence = 2)
        }
        if (!alreadyReported && isAcknowledged && !verifyAttempted) {
            // SUBS are never classified (never consumable); INAPP learns `consume` from a silent verify.
            if (productType != "inapp") return NOTHING.copy(precedence = 3)
            if (serverConsume == null) return CompletionPlan(verify = true, finalize = null, emitCompleted = false, updateEntitlements = false, precedence = 3)
            return CompletionPlan(false, if (serverConsume) Finalize.Consume else null, false, false, 3)
        }
        if (!alreadyReported) {
            if (serverConsume == null) return CompletionPlan(verify = true, finalize = null, emitCompleted = false, updateEntitlements = false, precedence = 4)
            val finalize = when {
                serverConsume -> Finalize.Consume // consuming also acknowledges
                !isAcknowledged -> Finalize.Acknowledge
                else -> null
            }
            return CompletionPlan(verify = false, finalize = finalize, emitCompleted = true, updateEntitlements = !serverConsume, precedence = 4)
        }
        return NOTHING.copy(precedence = 5)
    }
}

/** A Play purchase with the query arm that returned it (`"subs"` / `"inapp"`). */
internal data class OwnedPurchase(val purchase: Purchase, val productType: String) {
    val token: String get() = purchase.purchaseToken
    val productId: String get() = purchase.products.firstOrNull() ?: ""
}

/**
 * SPEC-497 §13a.2 (LD-R19-1, D-R40-1) — THE one owner of Android purchase completion.
 *
 * Only this class verifies (`/billing/verify`, `/billing/restore`), acknowledges and consumes an owned
 * purchase, and emits the report (`purchase_completed`, plus `subscription_started` for SUBS). Every
 * entry point — the purchase path, ON_START, `identify`'s refresh, `restorePurchases`,
 * `ITEM_ALREADY_OWNED` — calls [run]. [run] and [drainDeliveries] are the only methods that take
 * [purchaseCompletionMutex] (non-reentrant): `runLocked` never calls [run], [drainDeliveries],
 * `restorePurchases()` or `refreshEntitlementCache()`; a drain and the `ITEM_ALREADY_OWNED` relaunch
 * happen after [run] has returned.
 */
internal class PurchaseCompletion(
    private val mgr: NativeBillingManager,
    internal val ledger: PurchaseLedger,
    /** Wall clock for `queuedAt` and the drain budget. `var` so a test can drive the 60 s budget. */
    internal var clock: () -> Long = System::currentTimeMillis,
) {
    enum class Entry { PURCHASE, ON_START, IDENTIFY_REFRESH, RESTORE, ITEM_ALREADY_OWNED }

    /** What `ITEM_ALREADY_OWNED` found for the pending product. */
    sealed class ItemOutcome {
        object NoMatch : ItemOutcome()
        object Pending : ItemOutcome()
        object ReportedLive : ItemOutcome()
        object Consumed : ItemOutcome()
        /** A consumable (pending consume / classified) whose consume failed again: nothing to relaunch. */
        object ConsumeFailed : ItemOutcome()
        /** Precedence (4)'s verify failed; the live caller was already resolved `Failed` (R80). */
        object VerifyFailed : ItemOutcome()
        data class Owned(val purchase: Purchase, val entitlement: Entitlement) : ItemOutcome()
    }

    class CompletionResult(val entry: Entry) {
        var queryOk: Boolean = false
        var queryError: BillingError? = null
        val skippedTokens = LinkedHashSet<String>()
        /** Tokens precedence (4) did not complete (retryable or terminal) — excluded from `/restore`. */
        val notCompleted = LinkedHashSet<String>()
        val consumed = LinkedHashSet<String>()
        val reportedTokens = ArrayList<String>()
        val reportedProducts = LinkedHashSet<String>()
        var queued: Int = 0
        /** The owned list read inside the lock (identity-filtered + reported-since-identity + listener). */
        val owned = ArrayList<OwnedPurchase>()
        /** Products of the raw OK SUBS query, or null when it failed. */
        var okSubsProducts: Set<String>? = null
        /** Identity-filtered SUBS purchases (skipped ones included) — the lifecycle snapshot's input. */
        val subsForSnapshot = ArrayList<OwnedPurchase>()
        var restored: List<VerifiedPurchase> = emptyList()
        var restoreError: Throwable? = null
        var itemOutcome: ItemOutcome? = null
        /** Both queries OK and nothing owned (the refresh re-runs at the next ON_START). */
        var ownedEmpty: Boolean = false
    }

    val purchaseCompletionMutex = Mutex()

    /** In-memory in-flight set of the drain (Q3). Guarded by [purchaseCompletionMutex]. */
    private val inFlight = HashSet<String>()

    /**
     * Tokens the purchase path reported since the last identity change, kept in the owned list whatever
     * `filterOwnedPurchases` returns (R21-V1). In memory only; cleared on an identity change.
     *
     * Concurrent (I4 minor 4): `identify` / `reset` clear it from the caller's thread while `runLocked`
     * reads it on Main, so a plain set could throw `ConcurrentModificationException`.
     */
    private val reportedSinceIdentity: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * Test seam (null in production): runs right after step 3's persisted write and before the emit —
     * where a crash, or a caller's cancellation, lands between "decided + persisted" and "emitted".
     */
    @Volatile internal var afterPersistHook: (() -> Unit)? = null

    /**
     * Test seam (null in production): the drain awaits it after marking an entry in-flight and before
     * calling the delegate — i.e. while the delegate call is in flight — so a test can hold one drain
     * there and start another (Q3).
     */
    @Volatile internal var delegateCallGate: (suspend (token: String) -> Unit)? = null

    /** How many drain delegate calls ran past the 30 s timeout (counted delivered). Observed by tests. */
    internal val delegateTimeouts = AtomicInteger(0)

    /** An empty OK owned list marks the refresh to re-run at the next ON_START (R32 SDK minor 1). */
    @Volatile var refreshRerunPending: Boolean = false

    /** A drain is a no-op until `configure` has loaded the identity and the queue (trigger (iv)). */
    @Volatile var drainReady: Boolean = false

    /** Silent-verify entitlements learnt during a run, by token (ITEM_ALREADY_OWNED reuses them). */
    private val classificationEntitlements = HashMap<String, VerifiedPurchase>()

    // ---------------------------------------------------------------------------------------------
    // run
    // ---------------------------------------------------------------------------------------------

    suspend fun run(
        entry: Entry,
        filter: String? = null,
        listenerPurchase: Purchase? = null,
        anonymousToFirstUser: Boolean = false,
    ): CompletionResult = purchaseCompletionMutex.withLock {
        runLocked(entry, filter, listenerPurchase, anonymousToFirstUser)
    }

    private suspend fun runLocked(
        entry: Entry,
        filter: String?,
        listenerPurchase: Purchase?,
        anonymousToFirstUser: Boolean,
    ): CompletionResult {
        val result = CompletionResult(entry)
        if (!mgr.ownsTransactions) return result
        classificationEntitlements.clear()

        val client = withTimeoutOrNull(WAIT_MS) { mgr.connectionManager.awaitConnectedClient() }
        if (client == null) {
            result.queryError = BillingError.ServerError("billing unavailable")
            return result
        }

        // -- input, read inside the lock ------------------------------------------------------------
        val subs = queryOwned(client, BillingClient.ProductType.SUBS)
        val inapp = queryOwned(client, BillingClient.ProductType.INAPP)
        val subsOk = subs?.billingResult?.responseCode == BillingClient.BillingResponseCode.OK
        val inappOk = inapp?.billingResult?.responseCode == BillingClient.BillingResponseCode.OK
        result.queryOk = subsOk && inappOk
        if (!result.queryOk) result.queryError = queryErrorFor(if (!subsOk) subs?.billingResult else inapp?.billingResult)
        if (subsOk) result.okSubsProducts = subs!!.purchasesList.flatMap { it.products }.toSet()

        val unfiltered = LinkedHashMap<String, OwnedPurchase>()
        if (subsOk) subs!!.purchasesList.forEach { unfiltered.putIfAbsent(it.purchaseToken, OwnedPurchase(it, "subs")) }
        if (inappOk) inapp!!.purchasesList.forEach { unfiltered.putIfAbsent(it.purchaseToken, OwnedPurchase(it, "inapp")) }
        val listener: OwnedPurchase? = listenerPurchase?.let { p ->
            unfiltered[p.purchaseToken]?.copy(purchase = p)
                ?: OwnedPurchase(p, mgr.pendingProductType?.takeIf { p.products.contains(mgr.pendingProductId) } ?: guessType(p))
        }
        if (listener != null) unfiltered[listener.token] = listener
        result.ownedEmpty = result.queryOk && unfiltered.isEmpty()

        fun inScope(op: OwnedPurchase) = filter == null || op.purchase.products.contains(filter)

        // -- pending-purchase rule: other entries skip the in-flight product --------------------------
        val purchasePath = entry == Entry.PURCHASE || entry == Entry.ITEM_ALREADY_OWNED
        val inFlightProduct = if (mgr.hasLiveContinuation()) mgr.pendingProductId else null
        if (!purchasePath && inFlightProduct != null) {
            unfiltered.values.filter { it.purchase.products.contains(inFlightProduct) }.forEach { result.skippedTokens += it.token }
        }

        // -- precedence (2): retry a pending finalize, against the UNFILTERED list --------------------
        retryPendingFinalizes(client, unfiltered, result, filter, ::inScope)

        // -- the identity-filtered list (+ reported since identity + the listener's own purchase) -----
        val identityToken = mgr.currentUserToken()
        val kept = mgr.filterOwnedPurchases(unfiltered.values.map { it.purchase }, identityToken, "PurchaseCompletion.$entry")
            .map { it.purchaseToken }.toMutableSet()
        kept += reportedSinceIdentity.filter { unfiltered.containsKey(it) }
        listener?.let { kept += it.token }
        for (op in unfiltered.values) {
            if (op.token !in kept) continue
            if (op.productType == "subs" && op.purchase.purchaseState == Purchase.PurchaseState.PURCHASED) result.subsForSnapshot += op
            if (!inScope(op)) continue
            result.owned += op
        }

        // -- per purchase ---------------------------------------------------------------------------
        for (op in result.owned.toList()) {
            if (op.token in result.skippedTokens) continue
            if (op.purchase.purchaseState != Purchase.PurchaseState.PURCHASED) continue
            val reported = ledger.reported().firstOrNull { it.token == op.token }
            val isListener = listener?.token == op.token
            if (reported != null) {
                // (5), or (2) already retried above. Safety net: the purchase path finding its own
                // token already reported resolves Purchased from the cache — it never hangs, never re-emits.
                if (isListener && isLiveFor(entry, op)) resolveFromCache(op)
                continue
            }
            val attempted = op.token in ledger.verifyAttempted()
            if (op.purchase.isAcknowledged && !attempted && !isListener) {
                applyPrecedence3(client, op, result)
                continue
            }
            applyPrecedence4(client, op, entry, isListener, result)
        }

        if (entry == Entry.ITEM_ALREADY_OWNED) result.itemOutcome = itemOutcome(client, result, filter)

        // -- restore / refresh (and a pending refresh re-run at ON_START), still inside the lock -------
        val refresh = entry == Entry.RESTORE || entry == Entry.IDENTIFY_REFRESH ||
            (entry == Entry.ON_START && refreshRerunPending)
        if (refresh) restoreAndMerge(result, unfiltered, anonymousToFirstUser)
        return result
    }

    // ---------------------------------------------------------------------------------------------
    // precedences
    // ---------------------------------------------------------------------------------------------

    private suspend fun retryPendingFinalizes(
        client: BillingClient,
        unfiltered: Map<String, OwnedPurchase>,
        result: CompletionResult,
        filter: String?,
        inScope: (OwnedPurchase) -> Boolean,
    ) {
        if (!mgr.ownsTransactions) return
        for (r in ledger.reported()) {
            val pending = r.pendingFinalize ?: continue
            // R22-S2: a token the in-flight purchase owns is processed by no precedence — not even (2).
            if (r.token in result.skippedTokens) continue
            val op = unfiltered[r.token]
            if (op == null) {
                // Nothing left to finalize — cleared ONLY when both queries answered OK and the run is
                // unfiltered (a failed query keeps the pending finalize, R61). The token stays reported.
                if (result.queryOk && filter == null) setPending(r.token, null)
                continue
            }
            if (!inScope(op)) continue
            val done = when (pending) {
                Finalize.Consume -> consumeAsyncAwait(client, r.token)
                Finalize.Acknowledge -> acknowledgeAwait(client, r.token)
            }
            if (done) {
                if (pending == Finalize.Consume) {
                    result.consumed += r.token
                    val cache = ledger.classCache().apply { remove(r.token) }
                    ledger.write(reported = ledger.reported().map { if (it.token == r.token) it.copy(pendingFinalize = null) else it }, classCache = cache)
                } else {
                    setPending(r.token, null)
                }
            }
        }
    }

    /** (3): acknowledged, unreported, never verify-attempted. INAPP only is classified. */
    private suspend fun applyPrecedence3(client: BillingClient, op: OwnedPurchase, result: CompletionResult) {
        if (!mgr.ownsTransactions) return
        val cls = classify(op) ?: return
        val plan = PurchaseCompletionPlan.decide(
            productType = op.productType, serverConsume = cls == PurchaseLedger.CLASS_CONSUMABLE,
            ownsTransactions = true, alreadyReported = false, isAcknowledged = true,
            pendingFinalize = null, verifyAttempted = false,
        )
        if (plan.finalize == Finalize.Consume && consumeAsyncAwait(client, op.token)) {
            result.consumed += op.token
            ledger.write(classCache = ledger.classCache().apply { remove(op.token) })
        }
    }

    /**
     * The purchase's class (`consumable` / `non_consumable` / `unknown`), in order: a pending consume in
     * the reported set, the persisted class cache, an inline SILENT verify (no emit, no entitlement
     * change; never recorded as verify-attempted). A retryable verify failure returns null and writes
     * nothing (the next ON_START tries again); a terminal one caches `unknown`. SUBS are never classified.
     */
    private suspend fun classify(op: OwnedPurchase): String? {
        if (!mgr.ownsTransactions) return null
        if (op.productType != "inapp") return null
        ledger.reported().firstOrNull { it.token == op.token }?.let { if (it.pendingFinalize == Finalize.Consume) return PurchaseLedger.CLASS_CONSUMABLE }
        ledger.classCache()[op.token]?.let { return it }
        val outcome = try {
            withTimeoutOrNull(WAIT_MS) {
                mgr.receiptVerifier.verify(op.token, op.productId, op.productType, "android", null, null)
            }
        } catch (e: VerificationException) {
            if (classifyVerifyStatus(e.httpStatus) == VerifyOutcomeClass.Terminal) {
                ledger.write(classCache = ledger.classCache().apply { put(op.token, PurchaseLedger.CLASS_UNKNOWN) })
                return PurchaseLedger.CLASS_UNKNOWN
            }
            return null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // a cancelled scope is not a retryable verify failure (A9)
        } catch (e: Exception) {
            Log.warning("PurchaseCompletion: classification verify failed for ${op.productId}: ${e.message}")
            return null
        } ?: return null // a timeout is retryable
        classificationEntitlements[op.token] = outcome
        val cls = if (outcome.consume) PurchaseLedger.CLASS_CONSUMABLE else PurchaseLedger.CLASS_NON_CONSUMABLE
        ledger.write(classCache = ledger.classCache().apply { put(op.token, cls) })
        return cls
    }

    /** Does [op] have a live `purchase()` caller in this entry? (the purchase path, for `pendingProductId`) */
    private fun isLiveFor(entry: Entry, op: OwnedPurchase): Boolean =
        (entry == Entry.PURCHASE || entry == Entry.ITEM_ALREADY_OWNED) &&
            mgr.pendingProductId != null && op.purchase.products.contains(mgr.pendingProductId) &&
            mgr.hasLiveContinuation()

    /** (4), exactly once, in order: price → verify → decide the live caller + persist → emit → delegates → finalize. */
    private suspend fun applyPrecedence4(
        client: BillingClient,
        op: OwnedPurchase,
        entry: Entry,
        isListener: Boolean,
        result: CompletionResult,
    ) {
        if (!mgr.ownsTransactions) return
        val productId = op.productId
        val live = isLiveFor(entry, op)

        // 1. price — PriceResolver is memory-only, so a cache miss queries the product first (R21-S3).
        if (mgr.priceResolver.cachedPriceInfo(productId) == null) {
            try {
                withTimeoutOrNull(WAIT_MS) { mgr.queryProductDetails(client, productId, op.productType) }
                    ?.let { mgr.priceResolver.cacheFromPurchase(it.productDetails) }
            } catch (e: Exception) {
                Log.debug("PurchaseCompletion: price lookup for $productId failed: ${e.message}")
            }
        }

        // 2. verify (C2), bounded so the lock is never held beyond 30 s per token (R79).
        val unacknowledged = !op.purchase.isAcknowledged
        if (unacknowledged && op.token !in ledger.verifyAttempted()) {
            ledger.write(verifyAttempted = ledger.verifyAttempted() + op.token)
        }
        val verified: VerifiedPurchase = try {
            withTimeoutOrNull(WAIT_MS) {
                mgr.receiptVerifier.verify(
                    purchaseToken = op.token,
                    productId = productId,
                    productType = op.productType,
                    platform = "android",
                    paywallId = if (live) mgr.currentPaywallId else null,
                    experimentId = if (live) mgr.currentExperimentId else null,
                )
            } ?: throw BillingError.NetworkError(java.io.IOException("verification timed out after 30 s"))
        } catch (e: kotlinx.coroutines.CancellationException) {
            // A cancelled scope is not a verify failure (impl audit round 2, A9): no purchase_failed, no
            // Failed for the caller — the token stays for the next rescue, as after a process death.
            throw e
        } catch (e: Exception) {
            result.notCompleted += op.token
            Log.warning("PurchaseCompletion: verify failed for $productId (${e.message}) — left for the next rescue")
            // R80: on the purchase path — the listener's own purchase, or the ITEM_ALREADY_OWNED owner of
            // pendingProductId — the live caller resolves Failed (a timeout: networkError) with one
            // purchase_failed; the token is left for the next ON_START / identify rescue.
            if (isListener || live) mgr.reportVerificationFailure(productId, e, live)
            return
        }
        val plan = PurchaseCompletionPlan.decide(
            productType = op.productType, serverConsume = verified.consume, ownsTransactions = true,
            alreadyReported = false, isAcknowledged = op.purchase.isAcknowledged, pendingFinalize = null,
            verifyAttempted = true,
        )
        val productType = verified.productType ?: op.productType
        val orderId = op.purchase.orderId?.takeIf { it.isNotBlank() }
        val txId = orderId ?: op.token

        // 3. decide the live caller, THEN persist — one write (Q1): reported + pending finalize, the
        //    verify-attempted removal, the class cache, and (no live caller) the queue entry.
        val continuation = if (live) mgr.takeContinuation() else null
        val paywallId = if (continuation != null) mgr.currentPaywallId else null
        val experimentId = if (continuation != null) mgr.currentExperimentId else null
        val metadata = if (continuation != null) mgr.currentPaywallMetadata else emptyMap()
        val delivery = PendingDelivery(
            purchaseToken = op.token,
            transactionId = txId,
            productId = productId,
            purchaseTime = op.purchase.purchaseTime,
            quantity = op.purchase.quantity,
            ownerToken = ownerTokenFor(op, entry, continuation != null),
            queuedAt = clock(),
        )
        val classCache = ledger.classCache()
        if (op.productType == "inapp") {
            classCache[op.token] = if (verified.consume) PurchaseLedger.CLASS_CONSUMABLE else PurchaseLedger.CLASS_NON_CONSUMABLE
        }
        ledger.write(
            reported = ledger.reported().filter { it.token != op.token } + ReportedToken(op.token, plan.finalize),
            verifyAttempted = ledger.verifyAttempted() - op.token,
            classCache = classCache,
            deliveries = if (continuation == null) ledger.deliveries().filter { it.purchaseToken != op.token } + delivery else null,
        )
        reportedSinceIdentity += op.token
        afterPersistHook?.invoke()
        if (plan.updateEntitlements) mgr.entitlementCache.update(verified.entitlement, productType)

        // 4. emit.
        mgr.trackPurchaseCompleted(
            productId = productId,
            isTrial = verified.entitlement.isTrial,
            transactionId = orderId,
            isSubscription = op.productType == "subs",
            isConsumable = verified.consume,
            originalTransactionId = verified.originalTransactionId ?: orderId?.let { stripRenewalSuffix(it) },
            quantity = op.purchase.quantity,
            paywallId = paywallId,
            experimentId = experimentId,
            paywallMetadata = metadata,
        )
        result.reportedTokens += op.token
        result.reportedProducts += productId
        if (continuation == null) result.queued++

        // 5. the live caller (Q6) — resumed with Purchased; exactly one delivery between the inline call
        //    and the cancellation handler's enqueue.
        if (continuation != null) {
            val txInfo = TransactionInfo(transactionId = txId, productId = productId, purchaseDate = op.purchase.purchaseTime.toString())
            val decided = AtomicInteger(0) // 1 = inline call, 2 = enqueued by the cancellation handler
            continuation.resume(PurchaseResult.Purchased(verified.entitlement, txId)) { _ ->
                if (decided.compareAndSet(0, 2)) {
                    mgr.scope.launch {
                        purchaseCompletionMutex.withLock {
                            ledger.write(deliveries = ledger.deliveries().filter { it.purchaseToken != op.token } + delivery)
                        }
                        drainDeliveries()
                    }
                }
            }
            mgr.clearPurchaseScope()
            if (decided.compareAndSet(0, 1)) mgr.postPurchaseDelegates(productId, txInfo, paywallId)
        }

        // 6. finalize — a consumable is consumed immediately (LD-R23-3), anything else acknowledged.
        when (plan.finalize) {
            Finalize.Consume -> if (consumeAsyncAwait(client, op.token)) {
                result.consumed += op.token
                ledger.write(
                    reported = ledger.reported().map { if (it.token == op.token) it.copy(pendingFinalize = null) else it },
                    classCache = ledger.classCache().apply { remove(op.token) },
                )
            }
            Finalize.Acknowledge -> if (acknowledgeAwait(client, op.token)) setPending(op.token, null)
            null -> Unit
        }
    }

    /** The purchase path found its token already reported: resolve from the cache, never re-emit. */
    private fun resolveFromCache(op: OwnedPurchase) {
        val cont = mgr.takeContinuation() ?: return
        val ent = mgr.entitlementCache.getEntitlement(op.productId) ?: localEntitlement(op.productId)
        cont.resume(PurchaseResult.Purchased(ent, op.purchase.orderId?.takeIf { it.isNotBlank() } ?: op.token)) { _ -> }
        mgr.clearPurchaseScope()
    }

    // ---------------------------------------------------------------------------------------------
    // ITEM_ALREADY_OWNED
    // ---------------------------------------------------------------------------------------------

    private suspend fun itemOutcome(client: BillingClient, result: CompletionResult, filter: String?): ItemOutcome {
        if (!mgr.ownsTransactions) return ItemOutcome.NoMatch
        val matching = result.owned.filter { filter == null || it.purchase.products.contains(filter) }
        if (matching.isEmpty()) return ItemOutcome.NoMatch
        if (matching.any { it.token in result.reportedTokens } && !mgr.hasLiveContinuation()) return ItemOutcome.ReportedLive
        if (matching.any { it.token in result.consumed }) return ItemOutcome.Consumed
        val purchased = matching.firstOrNull { it.purchase.purchaseState == Purchase.PurchaseState.PURCHASED }
        if (purchased != null) {
            // R80: precedence (4)'s verify failed — the caller already resolved Failed.
            if (purchased.token in result.notCompleted) return ItemOutcome.VerifyFailed
            // I4 minor 6: a consumable (a pending consume, or classified consumable) that is STILL owned
            // — its consume failed again — cannot be re-bought; it is never reported as a restore.
            val pendingConsume = ledger.reported().any { it.token == purchased.token && it.pendingFinalize == Finalize.Consume }
            if (pendingConsume || ledger.classCache()[purchased.token] == PurchaseLedger.CLASS_CONSUMABLE) {
                return ItemOutcome.ConsumeFailed
            }
            // Non-consumable / subscription already reported or acknowledged: the entitlement comes from
            // the cache or a single-token verify — never a restore, so no per-product purchase_restored.
            val ent = mgr.entitlementCache.getEntitlement(purchased.productId)
                ?: classificationEntitlements[purchased.token]?.entitlement
                ?: try {
                    withTimeoutOrNull(WAIT_MS) {
                        mgr.receiptVerifier.verify(purchased.token, purchased.productId, purchased.productType, "android", null, null)
                    }?.entitlement
                } catch (e: Exception) {
                    null
                }
                ?: localEntitlement(purchased.productId) // R79: no cache entry and a failed verify
            return ItemOutcome.Owned(purchased.purchase, ent)
        }
        return ItemOutcome.Pending
    }

    /**
     * SPEC-497 §13a.2 `ITEM_ALREADY_OWNED` — from the listener or the synchronous `launchBillingFlow`
     * result, with `pendingProductId` still set. Never calls `restorePurchases()` and never takes the
     * lock itself: it calls [run] (`filter = pendingProductId`, never `firstOrNull()`), then acts on the
     * outcome after [run] has returned (the relaunch included).
     *
     * SPEC-497 R9 — [owner] is the purchase scope's owner when Play answered. [run] can wait up to 30 s
     * per step; if that purchase's caller is cancelled meanwhile and a newer purchase takes the scope, this
     * handler must not resolve, relaunch or clear the newer one. So it acts only while [owner] still owns
     * the scope; otherwise it tracks nothing (as for a lost scope in `purchase()`) and only drains — or, when
     * the scope was lost before [run], runs the owned-purchase rescue (I4 m2).
     */
    suspend fun handleItemAlreadyOwned(owner: Long) {
        if (!mgr.ownsScope(owner)) {
            // I4 m2 — nothing to resolve, but the owned purchase may be unreported: rescue it now.
            Log.warning("ITEM_ALREADY_OWNED: the purchase scope changed before the handler ran — running the owned-purchase rescue")
            mgr.rescueOwnedPurchases()
            return
        }
        if (!mgr.ownsTransactions) {
            mgr.resumePurchase(PurchaseResult.Failed("item_already_owned", "unknown"))
            return
        }
        val productId = mgr.pendingProductId ?: run {
            mgr.resumePurchase(PurchaseResult.Unknown)
            return
        }
        val result = run(Entry.ITEM_ALREADY_OWNED, filter = productId)
        if (!mgr.ownsScope(owner)) {
            // Resolved by precedence (4) inside run (ReportedLive / VerifyFailed), cancelled, or taken over
            // by a newer purchase: nothing is left for this handler to do.
            mgr.triggerDrain()
            return
        }
        when (val outcome = result.itemOutcome ?: ItemOutcome.NoMatch) {
            ItemOutcome.ReportedLive -> Unit // precedence (4) resolved the pending purchase() with it
            ItemOutcome.Pending -> {
                mgr.trackPurchasePending(productId, live = true)
                mgr.resumePurchase(PurchaseResult.Pending)
            }
            ItemOutcome.Consumed -> {
                // The old unit was consumed silently; buy again ONCE if the Activity is still alive.
                if (!mgr.relaunchUsed && mgr.relaunchBillingFlow()) Unit
                else {
                    mgr.trackPurchaseFailed(reason = "item_already_owned", debugMessage = "item_already_owned", productId = productId, errorType = "unknown")
                    mgr.resumePurchase(PurchaseResult.Failed("item_already_owned", "unknown"))
                }
            }
            ItemOutcome.ConsumeFailed -> {
                // §13a.2: the consumable could not be consumed, so a relaunch would only answer
                // ITEM_ALREADY_OWNED again — Failed("item_already_owned"), never a restore.
                mgr.trackPurchaseFailed(reason = "item_already_owned", debugMessage = "item_already_owned", productId = productId, errorType = "unknown")
                mgr.resumePurchase(PurchaseResult.Failed("item_already_owned", "unknown"))
            }
            ItemOutcome.VerifyFailed -> Unit // resolved Failed (with purchase_failed) by precedence (4)
            is ItemOutcome.Owned -> {
                // A re-tap of an owned non-consumable / subscription: ONE purchase_restored with no price
                // or currency (books no revenue), no purchase_completed, no onPurchaseCompleted.
                val orderId = outcome.purchase.orderId?.takeIf { it.isNotBlank() }
                AppDNA.trackBillingEvent("purchase_restored", buildMap {
                    put("product_id", productId)
                    orderId?.let { put("transaction_id", it) }
                    put("reason", "item_already_owned")
                })
                mgr.resumePurchase(PurchaseResult.Purchased(outcome.entitlement, orderId ?: outcome.purchase.purchaseToken))
            }
            ItemOutcome.NoMatch -> {
                mgr.trackPurchaseFailed(reason = "item_already_owned_no_entitlement", debugMessage = null, productId = productId)
                mgr.resumePurchase(PurchaseResult.Unknown)
            }
        }
        mgr.triggerDrain()
    }

    // ---------------------------------------------------------------------------------------------
    // restore / refresh
    // ---------------------------------------------------------------------------------------------

    private suspend fun restoreAndMerge(
        result: CompletionResult,
        unfiltered: Map<String, OwnedPurchase>,
        anonymousToFirstUser: Boolean,
    ) {
        if (!mgr.ownsTransactions) return
        if (!result.queryOk) return // a failed query leaves the cache unchanged
        if (result.entry == Entry.ON_START || result.entry == Entry.IDENTIFY_REFRESH) refreshRerunPending = false
        if (result.ownedEmpty) refreshRerunPending = true

        val excludedOrSkipped = result.notCompleted + result.skippedTokens
        val send = result.owned.filter {
            it.purchase.purchaseState == Purchase.PurchaseState.PURCHASED &&
                it.token !in result.consumed && it.token !in excludedOrSkipped
        }
        val userBefore = mgr.currentUserId()
        val reply = if (send.isEmpty()) emptyList() else try {
            // Bounded like every other wait in runLocked: the lock is never held past 30 s by /restore.
            withTimeoutOrNull(WAIT_MS) {
                mgr.receiptVerifier.restore(send.map { RestoreTransaction(it.token, it.productId, it.productType) })
            } ?: throw BillingError.NetworkError(java.io.IOException("restore timed out after 30 s"))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // a cancelled scope is not a restore failure (A9)
        } catch (e: Exception) {
            result.restoreError = e
            return
        }
        if (mgr.currentUserId() != userBefore) {
            // R35: a reply that started under another identity is dropped.
            result.restoreError = BillingError.ServerError("identity changed")
            return
        }
        val kept = reply.filter { !it.consume } // a consumable is finalized by (3)/(4), never granted
        result.restored = kept
        val protected = unfiltered.values.filter { it.token in excludedOrSkipped }.flatMap { it.purchase.products }.toSet()
        if (anonymousToFirstUser) dropOtherTaggedEntries(unfiltered)
        mgr.entitlementCache.mergeRestoreReply(
            reply = kept.map { it.entitlement to it.productType },
            protectedProducts = protected,
            okSubsProducts = result.okSubsProducts,
        )
    }

    /** R36 SDK minor 2: on the anonymous → first user transition, drop entries tagged for someone else. */
    private fun dropOtherTaggedEntries(unfiltered: Map<String, OwnedPurchase>) {
        val current = mgr.currentUserToken()?.toString()?.lowercase() ?: return
        val foreign = unfiltered.values.filter { op ->
            val tag = op.purchase.accountIdentifiers?.obfuscatedAccountId?.lowercase()
            tag != null && tag != current
        }.flatMap { it.purchase.products }.toSet()
        for (p in foreign) {
            val e = mgr.entitlementCache.getEntitlement(p) ?: continue
            if (e.store == "google_play") mgr.entitlementCache.remove(p)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // the delivery queue (D-R40-1)
    // ---------------------------------------------------------------------------------------------

    /**
     * Queue rule 4 — the owner of a report. A `PURCHASE`-entry report carries the derived token of the
     * user current at report time, except a no-live-caller report whose `obfuscatedAccountId` is one of
     * the identified-users tokens (it keeps that owner). A rescue carries its `obfuscatedAccountId` only
     * when it is the derived token of a user identified on this device; otherwise it is untagged.
     */
    private fun ownerTokenFor(op: OwnedPurchase, entry: Entry, live: Boolean): String? {
        val current = mgr.currentUserToken()?.toString()?.lowercase()
        val tag = op.purchase.accountIdentifiers?.obfuscatedAccountId?.lowercase()
        val identified = identifiedTokens()
        return if (entry == Entry.PURCHASE) {
            if (!live && tag != null && tag in identified) tag else current
        } else {
            if (tag != null && tag in identified) tag else null
        }
    }

    /** The identified-users tokens: the persisted set plus the current and first-identified users. */
    private fun identifiedTokens(): Set<String> {
        val out = LinkedHashSet<String>()
        ledger.identifiedTokens()?.let { out += it.map { t -> t.lowercase() } }
        mgr.currentUserToken()?.let { out += it.toString().lowercase() }
        AppAccountTokenResolver.firstIdentifiedToken()?.let { out += it.toString().lowercase() }
        return out
    }

    /**
     * Record an identified user's derived token (bounded 50). The first call seeds the set with the
     * current and the first-identified users' tokens (the upgrade seed, R62).
     */
    suspend fun noteIdentified(userId: String, previousUserId: String?) {
        purchaseCompletionMutex.withLock {
            val token = AppAccountTokenResolver.token(userId)?.toString()?.lowercase() ?: return@withLock
            val existing = ledger.identifiedTokens()
            // The upgrade seed (R62): the first-identified user AND the user current before this
            // identify (I4 minor 3) — so that user's tagged rescues keep their owner after an upgrade.
            val seeded = existing ?: listOfNotNull(
                AppAccountTokenResolver.firstIdentifiedToken()?.toString()?.lowercase(),
                previousUserId?.let { AppAccountTokenResolver.token(it)?.toString()?.lowercase() },
            )
            ledger.write(identifiedTokens = (seeded - token) + token)
            if (previousUserId != userId) reportedSinceIdentity.clear()
        }
    }

    /** `reset()` / an identity change: the in-memory reported-since-identity list is dropped. */
    fun noteIdentityCleared() {
        reportedSinceIdentity.clear()
    }

    private fun deliverable(d: PendingDelivery): Boolean {
        val current = mgr.currentUserToken()?.toString()?.lowercase()
        val owner = d.ownerToken?.lowercase()
        if (owner != null) return current != null && current == owner
        // Untagged: an anonymous user, or the device's first-identified user.
        return current == null || current == AppAccountTokenResolver.firstIdentifiedToken()?.toString()?.lowercase()
    }

    /**
     * The drain (Q3): one pass over a snapshot of the queued ids, entry by entry — (a) lock, skip
     * in-flight entries, mark the next deliverable one in-flight, unlock; (b) on Main, re-check the
     * entry is still deliverable to the current identity, re-read the delegate and call it synchronously;
     * (c) lock again: on success (or a late return) remove the entry; on a THROW, or when (b) found the
     * identity changed or no delivering delegate, unmark it (it stays queued, and is not revisited in this
     * pass). Total budget 60 s.
     * A no-op until `configure` has loaded the identity and the queue.
     */
    suspend fun drainDeliveries() {
        if (!drainReady || !mgr.ownsTransactions) return
        val started = clock()
        val snapshot = purchaseCompletionMutex.withLock {
            val all = ledger.deliveries()
            val fresh = ledger.purgeExpired(all)
            if (fresh.size != all.size) ledger.write(deliveries = fresh)
            fresh.map { it.purchaseToken }
        }
        for (token in snapshot) {
            if (clock() - started > DRAIN_BUDGET_MS) break
            val entry = purchaseCompletionMutex.withLock {
                val d = ledger.deliveries().firstOrNull { it.purchaseToken == token }
                if (d == null || token in inFlight || !deliverable(d)) null else { inFlight += token; d }
            } ?: continue
            val call = DelegateCall()
            var outcome: DelegateOutcome? = null
            try {
                delegateCallGate?.invoke(token)
                // A cheap early exit only — the delegate and the identity are re-checked ON MAIN, in the
                // same turn as the call (I4 minors 1+2), because an `identify` / `reset` / `setDelegate`
                // can land between here and the posted block.
                if (AppDNA.billing.deliveringDelegate() == null) {
                    return // no delivering delegate: everything stays queued for the next trigger
                }
                outcome = callDelegateBounded(entry, call)
            } finally {
                // Impl audit round 2 (A5): ALWAYS unmark — a cancelled drain (or a throwing gate) used to
                // leave the token in-flight, so no later drain in this process would deliver it. A drain
                // ended before an outcome abandons a call that has not started (compare-and-set: the
                // posted call can no longer run, the entry stays queued); a call that already started
                // counts as delivered — removed — unless it threw or was skipped on Main (identity changed
                // or no delivering delegate), so it is never ALSO left queued.
                val delivered = outcome == DelegateOutcome.DELIVERED ||
                    (outcome == null && !call.abandonIfNotStarted() && !call.notDelivered())
                withContext(NonCancellable) {
                    purchaseCompletionMutex.withLock {
                        inFlight -= token
                        if (delivered) {
                            ledger.write(deliveries = ledger.deliveries().filter { it.purchaseToken != token })
                        }
                    }
                }
            }
            if (outcome == DelegateOutcome.NOT_STARTED) return // Main is wedged: the next trigger retries
        }
    }

    /**
     * SKIPPED: the posted block found the identity changed (the entry is no longer deliverable) or no
     * delivering delegate on Main — not delivered, the entry stays queued (iOS `PurchaseDeliveryQueue`
     * step (b) does the same).
     */
    private enum class DelegateOutcome { DELIVERED, THREW, SKIPPED, NOT_STARTED }

    /** One drain delegate call's state, shared between the drain and the call posted to Main. */
    private class DelegateCall {
        val state = AtomicInteger(0) // 0 = not started, 1 = started, 2 = abandoned
        /** DELIVERED, THREW or SKIPPED — completed by the posted block. */
        val done = kotlinx.coroutines.CompletableDeferred<DelegateOutcome>()

        /** True when the call had not started and now never will. */
        fun abandonIfNotStarted(): Boolean = state.compareAndSet(0, 2)

        /** The started call has already finished without delivering (it threw, or was skipped on Main). */
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        fun notDelivered(): Boolean = done.isCompleted && done.getCompleted() != DelegateOutcome.DELIVERED
    }

    /**
     * Q3(b): call `onPurchaseCompleted` on Main, bounded by 30 s. On Main, in the same turn as the call,
     * the entry's identity is re-checked (`deliverable`) and the delivering delegate re-read — an
     * `identify(B)` that landed while this drain was suspended must never hand A's purchase to B (I4
     * minors 1+2); either failing → SKIPPED (stays queued). Success, a timeout and a late return all
     * count as DELIVERED (the call started; the docs say to grant idempotently by `transactionId`); a
     * throw keeps the entry queued. A call that never even started (Main busy for 30 s) is abandoned —
     * it can no longer run — and stays queued.
     */
    private suspend fun callDelegateBounded(
        entry: PendingDelivery,
        call: DelegateCall,
    ): DelegateOutcome {
        val state = call.state
        val done = call.done
        mainHandler.post {
            if (!state.compareAndSet(0, 1)) return@post
            val delegate = AppDNA.billing.deliveringDelegate()
            if (delegate == null || !deliverable(entry)) {
                done.complete(DelegateOutcome.SKIPPED)
                return@post
            }
            val result = try {
                delegate.onPurchaseCompleted(
                    entry.productId,
                    TransactionInfo(transactionId = entry.transactionId, productId = entry.productId, purchaseDate = entry.purchaseTime.toString()),
                )
                DelegateOutcome.DELIVERED
            } catch (t: Throwable) {
                Log.warning("AppDNABillingDelegate.onPurchaseCompleted threw — the delivery stays queued: ${t.message}")
                DelegateOutcome.THREW
            }
            done.complete(result)
        }
        val finished = withTimeoutOrNull(WAIT_MS) { done.await() }
        if (finished != null) return finished
        if (state.compareAndSet(0, 2)) {
            Log.warning("AppDNABillingDelegate.onPurchaseCompleted did not start within 30 s — the delivery stays queued")
            return DelegateOutcome.NOT_STARTED
        }
        delegateTimeouts.incrementAndGet()
        Log.warning("AppDNABillingDelegate.onPurchaseCompleted ran past 30 s — counted delivered (grant idempotently by transactionId)")
        return DelegateOutcome.DELIVERED
    }

    private val mainHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    // ---------------------------------------------------------------------------------------------
    // Play plumbing — every wait bounded by 30 s (a timeout is a retryable failure)
    // ---------------------------------------------------------------------------------------------

    private suspend fun queryOwned(client: BillingClient, type: String): PurchasesResult? = try {
        withTimeoutOrNull(WAIT_MS) {
            client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(type).build())
        }
    } catch (e: Exception) {
        Log.warning("PurchaseCompletion: queryPurchasesAsync($type) threw: ${e.message}")
        null
    }

    /** `consumeAsync`; `ITEM_NOT_OWNED` counts as done (nothing left to consume). */
    private suspend fun consumeAsyncAwait(client: BillingClient, token: String): Boolean {
        if (!mgr.ownsTransactions) return false
        val code = withTimeoutOrNull(WAIT_MS) {
            suspendCancellableCoroutine<BillingResult> { c ->
                client.consumeAsync(ConsumeParams.newBuilder().setPurchaseToken(token).build()) { br, _ ->
                    if (c.isActive) c.resume(br)
                }
            }
        }?.responseCode
        val ok = code == BillingClient.BillingResponseCode.OK || code == BillingClient.BillingResponseCode.ITEM_NOT_OWNED
        if (!ok) Log.warning("PurchaseCompletion: consume failed ($code) — retried at the next run")
        return ok
    }

    private suspend fun acknowledgeAwait(client: BillingClient, token: String): Boolean {
        if (!mgr.ownsTransactions) return false
        val code = withTimeoutOrNull(WAIT_MS) {
            suspendCancellableCoroutine<BillingResult> { c ->
                client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(token).build()) { br ->
                    if (c.isActive) c.resume(br)
                }
            }
        }?.responseCode
        val ok = code == BillingClient.BillingResponseCode.OK
        if (!ok) Log.warning("PurchaseCompletion: acknowledge failed ($code) — retried at the next run")
        return ok
    }

    private fun setPending(token: String, pending: Finalize?) {
        ledger.write(reported = ledger.reported().map { if (it.token == token) it.copy(pendingFinalize = pending) else it })
    }

    private fun guessType(p: Purchase): String {
        val pid = p.products.firstOrNull() ?: return "inapp"
        return mgr.entitlementCache.productTypeOf(pid)
            ?: if (mgr.priceResolver.isKnownSubscriptionProduct(pid)) "subs" else "inapp"
    }

    companion object {
        const val WAIT_MS = 30_000L
        const val DRAIN_BUDGET_MS = 60_000L

        /** Play's `NETWORK_ERROR` (12) / `SERVICE_UNAVAILABLE` (2) → `NetworkError`; anything else → `ServerError`. */
        fun queryErrorFor(br: BillingResult?): BillingError = when (br?.responseCode) {
            BillingClient.BillingResponseCode.NETWORK_ERROR,
            BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE ->
                BillingError.NetworkError(java.io.IOException("purchase query failed (${br.responseCode})"))
            else -> BillingError.ServerError("purchase query failed")
        }

        /** `GPA.1234-5678-9012-34567..2` → `GPA.1234-5678-9012-34567` (the base order id). */
        fun stripRenewalSuffix(orderId: String): String = orderId.replace(Regex("\\.\\.\\d+$"), "")

        /** An entitlement built from the Play purchase alone (no expiry; Play's client API has none). */
        fun localEntitlement(productId: String): Entitlement = Entitlement(
            productId = productId, store = "google_play", status = "active",
            expiresAt = null, isTrial = false, offerType = null,
        )
    }
}
