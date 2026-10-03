package ai.appdna.sdk.billing

import ai.appdna.sdk.AppDNA
import ai.appdna.sdk.AppDNABillingDelegate
import ai.appdna.sdk.TransactionInfo
import ai.appdna.sdk.network.ApiResult
import ai.appdna.sdk.storage.LocalStorage
import android.content.Context
import android.os.Looper
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.Purchase
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.robolectric.Shadows.shadowOf

/**
 * A [NativeBillingManager] wired to a [FakeBillingClient] and a fake verifier, built
 * from the fixture `setup` shape (`owned_purchases`, `reported_tokens`, `verify_replies`, …). Shared by
 * the `purchase_completion_plan` / `delivery_queue` / `entitlement_snapshot_merge` / `trial_price`
 * fixture drivers and the Robolectric tests, so both exercise the same production code.
 */
internal class BillingScenario(
    val ctx: Context,
    val setup: JSONObject = JSONObject(),
    ownsTransactions: Boolean = true,
    emitsLifecycleEvents: Boolean = true,
    /** Everything the billing delegate saw, in order (a runner passes its own spy list). */
    val delegateLog: MutableList<Pair<String, Map<String, Any?>>> = mutableListOf(),
) {
    var currentUser: String? = setup.optStr("current_user_id")
    val verifyCalls = mutableListOf<String>()
    val restoreCalls = mutableListOf<List<String>>()
    lateinit var fake: FakeBillingClient
        private set

    val storage = LocalStorage(ctx)
    val cache = EntitlementCache(ctx) { currentUser }
    /**
     * A verify that PARKS: when set, every `/billing/verify` suspends in it (with the token)
     * before the scripted reply, so a test can hold `run` inside the lock and race another entry.
     */
    @Volatile var verifyGate: (suspend (String) -> Unit)? = null

    val verifier = ReceiptVerifier(
        apiClient = null,
        billingOwner = if (ownsTransactions) "sdk" else "revenueCat",
        post = { path, body ->
            if (path.endsWith("/billing/verify")) {
                verifyGate?.invoke(JSONObject(body).getJSONObject("transaction").getString("token"))
            }
            fakePost(path, body)
        },
        currentUserId = { currentUser },
    )
    val mgr = NativeBillingManager(
        context = ctx,
        receiptVerifier = verifier,
        entitlementCache = cache,
        storage = storage,
        ownsTransactions = ownsTransactions,
        emitsLifecycleEvents = emitsLifecycleEvents,
        billingClientFactory = BillingClientFactory { _, listener -> FakeBillingClient(listener).also { fake = it; seedPlay(it) } },
        providerName = if (ownsTransactions) "storeKit2" else "revenueCat",
    )
    val ledger: PurchaseLedger get() = mgr.completion.ledger

    init {
        mgr.currentUserIdProvider = { currentUser }
        AppAccountTokenResolver.setPrefsForTesting(ctx.getSharedPreferences("spec497_anchor_test", Context.MODE_PRIVATE))
        AppAccountTokenResolver.clearFirstIdentifiedUserId()
        setup.optStr("first_identified_user_id")?.let { AppAccountTokenResolver.recordFirstIdentifiedUserIdIfNeeded(it) }
        seedLedger()
        seedCache()
        // The connection manager only — NOT initialize(): that would register the process lifecycle
        // observer, whose ON_START would run a reconcile the scenario did not ask for.
        mgr.connectionManager.initialize()
        mgr.completion.drainReady = true
        AppDNA.billing.manager = mgr
        AppDNA.billing.ownershipPolicy =
            BillingOwnership.policy(if (ownsTransactions) ai.appdna.sdk.BillingProvider.StoreKit2 else ai.appdna.sdk.BillingProvider.RevenueCat)
    }

    // -- the delegates ---------------------------------------------------------------------------

    var delegateThrows: Boolean = setup.optBoolean("delegate_throws", false)
    /** Runs inside `onPurchaseCompleted` (on Main), e.g. to advance a clock or run long. */
    var delegateHook: (() -> Unit)? = null

    /** Every TransactionInfo `onPurchaseCompleted` received, in order. */
    val deliveredTransactions = mutableListOf<TransactionInfo>()
    val delegate = object : AppDNABillingDelegate {
        override fun onPurchaseCompleted(productId: String, transaction: TransactionInfo) {
            delegateLog += "onPurchaseCompleted" to mapOf("productId" to productId, "transactionId" to transaction.transactionId)
            deliveredTransactions += transaction
            delegateHook?.invoke()
            if (delegateThrows) throw IllegalStateException("host delegate threw")
        }
        override fun onRestoreCompleted(restoredProducts: List<String>) {
            delegateLog += "onRestoreCompleted" to mapOf("productIds" to restoredProducts)
        }
    }

    fun setDelegate(delivers: Boolean = true) = AppDNA.billing.setDelegate(delegate, delivers)

    fun teardown() {
        AppDNA.billing.setDelegate(null)
        AppDNA.billing.manager = null
        AppDNA.billing.ownershipPolicy = BillingOwnershipPolicy.NotConfigured
        AppAccountTokenResolver.resetPrefsForTesting()
        runCatching { mgr.destroy() }
    }

    // -- driving -----------------------------------------------------------------------------------

    fun idle(times: Int = 60) {
        repeat(times) { shadowOf(Looper.getMainLooper()).idle() }
    }

    /** Run [block] on the manager's Main scope and idle until it (and what it posted) has finished. */
    fun <T> drive(block: suspend () -> T): Result<T> {
        var out: Result<T>? = null
        mgr.scope.launch { out = runCatching { block() } }
        for (i in 0 until 400) {
            shadowOf(Looper.getMainLooper()).idle()
            if (out != null) break
        }
        idle()
        return out ?: Result.failure(AssertionError("the scenario did not finish (a wait never resumed)"))
    }

    // -- seeds -------------------------------------------------------------------------------------

    fun ownedPurchases(): List<Pair<Purchase, String>> =
        setup.optJSONArray("owned_purchases").objects().map { o ->
            FakeBillingClient.purchase(
                productId = o.getString("product_id"),
                token = o.getString("token"),
                orderId = o.optStr("order_id"),
                acknowledged = o.optBoolean("acknowledged", false),
                pending = o.optString("purchase_state", "PURCHASED") == "PENDING",
                obfuscatedAccountId = o.optStr("obfuscated_account_id"),
            ) to (if (o.optString("product_type", "inapp") == "subs") BillingClient.ProductType.SUBS else BillingClient.ProductType.INAPP)
        }

    private fun seedPlay(fake: FakeBillingClient) {
        setup.optStr("billing_setup_code")?.let { fake.setupCode = code(it) }
        val listenerToken = setup.optStr("listener_purchase_token")
        for ((p, type) in ownedPurchases()) {
            if (p.purchaseToken == listenerToken) {
                fake.listenerPurchase = p
                fake.listenerPurchaseType = type
            } else {
                fake.addOwned(type, p)
            }
        }
        setup.optJSONObject("product_details")?.let { pd ->
            for (id in pd.keys()) {
                val d = pd.getJSONObject(id)
                val type = if (d.optString("product_type") == "subs") BillingClient.ProductType.SUBS else BillingClient.ProductType.INAPP
                fake.productDetails[id] = FakeBillingClient.productDetails(id, type, d.getLong("price_micros"), d.getString("currency"))
            }
        }
        setup.optJSONArray("consume_failures").strings().forEach { fake.consumeFailures += it }
        setup.optJSONArray("acknowledge_failures").strings().forEach { fake.acknowledgeFailures += it }
        setup.optJSONObject("query_response_codes")?.let { q ->
            q.optStr("subs")?.let { fake.queryCodes[BillingClient.ProductType.SUBS] = code(it) }
            q.optStr("inapp")?.let { fake.queryCodes[BillingClient.ProductType.INAPP] = code(it) }
        }
        if (setup.has("query_ok") && !setup.optBoolean("query_ok", true)) {
            fake.queryCodes[BillingClient.ProductType.SUBS] = BillingClient.BillingResponseCode.ERROR
            fake.queryCodes[BillingClient.ProductType.INAPP] = BillingClient.BillingResponseCode.ERROR
        }
        setup.optJSONArray("play_response_codes").strings().forEach { fake.launchScript.addLast(code(it)) }
        fake.launchSource = setup.optString("play_response_source", "listener")
    }

    private fun seedLedger() {
        val reported = setup.optJSONArray("reported_tokens").objects().map {
            ReportedToken(it.getString("token"), Finalize.fromWire(it.optStr("pending_finalize")))
        }
        val cls = LinkedHashMap<String, String>()
        setup.optJSONObject("purchase_class_cache")?.let { o -> for (k in o.keys()) cls[k] = o.getString(k) }
        val deliveries = setup.optJSONArray("pending_deliveries").objects().map {
            PendingDelivery(
                purchaseToken = it.getString("token"),
                transactionId = it.optStr("transactionId") ?: it.getString("token"),
                productId = it.getString("productId"),
                purchaseTime = 0L,
                quantity = 1,
                ownerToken = it.optStr("ownerToken"),
                queuedAt = System.currentTimeMillis(),
            )
        }
        mgr.completion.ledger.write(
            reported = reported,
            verifyAttempted = setup.optJSONArray("verify_attempted").strings(),
            classCache = cls,
            deliveries = deliveries,
        )
    }

    private fun seedCache() {
        EntitlementCache.clearPersisted(ctx)
        cache.clear()
        for (o in setup.optJSONArray("cached_entitlements").objects()) {
            cache.update(
                Entitlement(
                    productId = o.getString("product_id"),
                    store = o.optString("store", "google_play"),
                    status = o.optString("status", "active"),
                    expiresAt = o.optStr("expires_at"),
                    isTrial = false,
                    offerType = null,
                ),
                o.optStr("product_type"),
            )
        }
        if (setup.has("listener_active")) cache.listenerActive = setup.optBoolean("listener_active", true)
        else cache.listenerActive = true
    }

    // -- the fake server -------------------------------------------------------------------------

    private fun typeOf(productId: String, token: String?): String {
        ownedPurchases().firstOrNull { it.first.purchaseToken == token }?.let { return if (it.second == BillingClient.ProductType.SUBS) "subs" else "inapp" }
        return setup.optJSONObject("product_details")?.optJSONObject(productId)?.optString("product_type", "inapp") ?: "inapp"
    }

    private fun dto(token: String, productId: String, reply: JSONObject): JSONObject = JSONObject().apply {
        put("entitled", true)
        put("product_id", productId)
        put("product_type", typeOf(productId, token))
        put("store", "google_play")
        put("status", if (reply.optBoolean("is_trial", false)) "trialing" else "active")
        put("expires_at", reply.opt("expires_at") ?: JSONObject.NULL)
        put("is_trial", reply.optBoolean("is_trial", false))
        put("original_transaction_id", reply.opt("original_transaction_id") ?: JSONObject.NULL)
        put("consume", reply.optBoolean("consume", false))
        reply.optStr("environment")?.let { put("environment", it) }
        put("token", token)
    }

    private fun fakePost(path: String, body: String): ApiResult {
        val json = JSONObject(body)
        val replies = setup.optJSONObject("verify_replies") ?: JSONObject()
        if (path.endsWith("/billing/verify")) {
            val tx = json.getJSONObject("transaction")
            val token = tx.getString("token")
            verifyCalls += token
            val reply = replies.optJSONObject(token) ?: return ApiResult(null, null, null)
            val status = if (reply.isNull("status")) null else reply.optInt("status")
            if (status == null) return ApiResult(null, null, null)
            if (status !in 200..299) {
                val code = reply.optStr("error_code")
                val err = JSONObject().put("error", JSONObject().put("code", code ?: "error").put("message", "m")).toString()
                return ApiResult(status, err, code)
            }
            return ApiResult(status, JSONObject().put("data", dto(token, tx.getString("productId"), reply)).toString(), null)
        }
        if (path.endsWith("/billing/restore")) {
            val sent = json.getJSONArray("transactions").objects().map { it.getString("token") to it.getString("productId") }
            restoreCalls += sent.map { it.first }
            val restoreStatus = setup.optInt("restore_status", 200)
            if (restoreStatus !in 200..299) return ApiResult(restoreStatus, "{}", null)
            val items = JSONArray()
            val scripted = setup.optJSONArray("restore_reply")
            if (scripted != null) {
                for (o in scripted.objects()) {
                    val token = o.getString("token")
                    if (sent.none { it.first == token }) continue
                    items.put(JSONObject().apply {
                        put("entitled", true)
                        put("product_id", o.getString("product_id"))
                        put("product_type", o.optString("product_type", "inapp"))
                        put("store", "google_play")
                        put("status", if (o.optBoolean("is_trial", false)) "trialing" else "active")
                        put("expires_at", o.opt("expires_at") ?: JSONObject.NULL)
                        put("is_trial", o.optBoolean("is_trial", false))
                        put("consume", o.optBoolean("consume", false))
                        put("token", token)
                    })
                }
            } else {
                for ((token, productId) in sent) {
                    val reply = replies.optJSONObject(token) ?: continue
                    if (reply.optInt("status", 0) !in 200..299) continue
                    items.put(dto(token, productId, reply))
                }
            }
            return ApiResult(200, JSONObject().put("data", items).toString(), null)
        }
        return ApiResult(404, "{}", null)
    }

    // -- observations ------------------------------------------------------------------------------

    fun reportedState(): List<Map<String, Any?>> =
        ledger.reported().map { mapOf("token" to it.token, "pending_finalize" to it.pendingFinalize?.wire) }

    fun queueTokens(): List<String> = ledger.deliveries().map { it.purchaseToken }

    fun entitlementIds(): List<String> = cache.getAll().map { it.productId }.sorted()

    companion object {
        fun code(name: String): Int = when (name) {
            "OK" -> BillingClient.BillingResponseCode.OK
            "ITEM_ALREADY_OWNED" -> BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED
            "ITEM_NOT_OWNED" -> BillingClient.BillingResponseCode.ITEM_NOT_OWNED
            "USER_CANCELED" -> BillingClient.BillingResponseCode.USER_CANCELED
            "NETWORK_ERROR" -> BillingClient.BillingResponseCode.NETWORK_ERROR
            "SERVICE_UNAVAILABLE" -> BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE
            "BILLING_UNAVAILABLE" -> BillingClient.BillingResponseCode.BILLING_UNAVAILABLE
            else -> BillingClient.BillingResponseCode.ERROR
        }
    }
}

private fun JSONObject.optStr(key: String): String? = if (!has(key) || isNull(key)) null else optString(key, "").ifEmpty { null }

private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it, "").ifEmpty { null } }
