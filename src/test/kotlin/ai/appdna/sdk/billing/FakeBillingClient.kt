package ai.appdna.sdk.billing

import android.app.Activity
import android.os.Handler
import android.os.Looper
import com.android.billingclient.api.*
import org.json.JSONArray
import org.json.JSONObject

/**
 * A scripted Play [BillingClient] for JVM / Robolectric tests. Records every
 * `acknowledgePurchase` / `consumeAsync` / `launchBillingFlow` call and serves scripted
 * `queryPurchasesAsync` / `queryProductDetailsAsync` results. Injected through [BillingClientFactory].
 *
 * The purchase a launch "sells" ([listenerPurchase]) is hidden from the owned queries until Play
 * delivers it through the [PurchasesUpdatedListener] — the way a real purchase appears.
 */
internal class FakeBillingClient(private val listener: PurchasesUpdatedListener) : BillingClient() {

    /** Purchases `queryPurchasesAsync` returns, by product type (`"subs"` / `"inapp"`). */
    val owned = LinkedHashMap<String, MutableList<Purchase>>().apply {
        put(ProductType.SUBS, mutableListOf())
        put(ProductType.INAPP, mutableListOf())
    }
    /** Response code per query type (default OK). */
    val queryCodes = HashMap<String, Int>()
    /** ProductDetails by product id; the type is read from the details. */
    val productDetails = LinkedHashMap<String, ProductDetails>()

    val acknowledgeCalls = mutableListOf<String>()
    val consumeCalls = mutableListOf<String>()
    val consumeFailures = HashSet<String>()
    val acknowledgeFailures = HashSet<String>()
    /** Play never answers these tokens' acknowledge / consume (the FIRST attempt; a retry answers). */
    val acknowledgeHangs = HashSet<String>()
    val consumeHangs = HashSet<String>()
    /** Consume answers ITEM_NOT_OWNED for these tokens. */
    val consumeNotOwned = HashSet<String>()

    /** One response code per launch (incl. a relaunch). Default: OK. */
    val launchScript = ArrayDeque<Int>()
    /** "listener": a non-OK code comes back through the listener; "launch": the FIRST code is synchronous. */
    var launchSource: String = "listener"
    var launchCount = 0
        private set
    /** Delivered through the listener when a launch answers OK. */
    var listenerPurchase: Purchase? = null
    var listenerPurchaseType: String = ProductType.INAPP
    /** When false, a launch never answers (the purchase stays in flight). */
    var autoDeliver: Boolean = true

    private var connected = false
    private val main = Handler(Looper.getMainLooper())

    fun addOwned(type: String, purchase: Purchase) {
        owned.getOrPut(type) { mutableListOf() }.add(purchase)
    }

    /** Make [purchase] owned (visible to queries) and deliver it through the listener. */
    fun deliver(purchase: Purchase, type: String) {
        owned.values.forEach { l -> l.removeAll { it.purchaseToken == purchase.purchaseToken } }
        addOwned(type, purchase)
        main.post { listener.onPurchasesUpdated(result(BillingResponseCode.OK), listOf(purchase)) }
    }

    /** Deliver [purchase] through the listener WITHOUT making it visible to the owned queries (Play lag). */
    fun deliverWithoutOwning(purchase: Purchase) {
        main.post { listener.onPurchasesUpdated(result(BillingResponseCode.OK), listOf(purchase)) }
    }

    /** Play answers the listener with a bare [code] (no purchases) — e.g. a late USER_CANCELED. */
    fun answer(code: Int) {
        main.post { listener.onPurchasesUpdated(result(code), null) }
    }

    /** Play finished consuming a purchase: it is no longer owned. */
    private fun dropOwned(token: String) {
        owned.values.forEach { l -> l.removeAll { it.purchaseToken == token } }
    }

    // -- connection ----------------------------------------------------------------------------

    /** The setup answer (BILLING_UNAVAILABLE simulates a device without Play billing). */
    var setupCode: Int = BillingResponseCode.OK

    /** When true, [startConnection] keeps its listener in [heldSetupListener] and does not answer. */
    @Volatile var holdSetup: Boolean = false
    @Volatile var heldSetupListener: BillingClientStateListener? = null

    /** How many times a connection was attempted, so a backoff can be counted. */
    @Volatile var setupAttempts: Int = 0
        private set

    override fun startConnection(listener: BillingClientStateListener) {
        setupAttempts++
        if (holdSetup) { heldSetupListener = listener; return }
        connected = setupCode == BillingResponseCode.OK
        listener.onBillingSetupFinished(result(setupCode))
    }

    /** Runs inside [endConnection] (on the caller's thread), e.g. to interleave Main work. */
    @Volatile var onEndConnection: (() -> Unit)? = null

    override fun endConnection() {
        connected = false
        onEndConnection?.invoke()
    }
    override fun isReady(): Boolean = connected
    override fun getConnectionState(): Int = if (connected) ConnectionState.CONNECTED else ConnectionState.DISCONNECTED
    override fun isFeatureSupported(feature: String): BillingResult = result(BillingResponseCode.OK)

    // -- queries -------------------------------------------------------------------------------

    /**
     * A query callback that PARKS: while [queryGate] is set and not completed, every
     * `queryPurchasesAsync` answer is held and delivered (with the owned list AS OF the release) on
     * Main once it completes. [queriesParked] counts the held calls.
     */
    @Volatile var queryGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    @Volatile var queriesParked = 0

    /**
     * One gate PER SUBS query, taken in call order: the query answers with the owned list AS OF
     * THE CALL (what Play returned then), on Main once its own gate completes. Lets a test release two
     * reconciles' reads in the opposite order to the one they were made in.
     */
    val subsQueryGates = ArrayDeque<kotlinx.coroutines.CompletableDeferred<Unit>>()
    @Volatile var subsQueriesStarted = 0

    override fun queryPurchasesAsync(params: QueryPurchasesParams, listener: PurchasesResponseListener) {
        val type = stringsOf(params).firstOrNull { it == ProductType.SUBS || it == ProductType.INAPP } ?: ProductType.INAPP
        if (type == ProductType.SUBS) subsQueriesStarted++
        val perCall = if (type == ProductType.SUBS) subsQueryGates.removeFirstOrNull() else null
        if (perCall != null) {
            val code = queryCodes[type] ?: BillingResponseCode.OK
            val list = if (code == BillingResponseCode.OK) owned[type].orEmpty().toList() else emptyList()
            perCall.invokeOnCompletion { main.post { listener.onQueryPurchasesResponse(result(code), list) } }
            return
        }
        val answer = {
            val code = queryCodes[type] ?: BillingResponseCode.OK
            val list = if (code == BillingResponseCode.OK) owned[type].orEmpty().toList() else emptyList()
            listener.onQueryPurchasesResponse(result(code), list)
        }
        val gate = queryGate
        if (gate != null && !gate.isCompleted) {
            queriesParked++
            gate.invokeOnCompletion { main.post { answer() } }
            return
        }
        answer()
    }

    /**
     * A product query that PARKS: while [productQueryGate] is set and not completed, every
     * `queryProductDetailsAsync` answer is held and delivered on Main once it completes.
     */
    @Volatile var productQueryGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    @Volatile var productQueriesParked = 0

    override fun queryProductDetailsAsync(params: QueryProductDetailsParams, listener: ProductDetailsResponseListener) {
        val requested = requestedProducts(params)
        val answer = {
            val out = requested.mapNotNull { (id, type) -> productDetails[id]?.takeIf { it.productType == type } }
            // Play Billing 8 answers with a QueryProductDetailsResult; nothing is "unfetched"
            // here — a product the fake does not know is simply absent, as before.
            listener.onProductDetailsResponse(result(BillingResponseCode.OK), QueryProductDetailsResult.create(out, emptyList()))
        }
        val gate = productQueryGate
        if (gate != null && !gate.isCompleted) {
            productQueriesParked++
            gate.invokeOnCompletion { main.post { answer() } }
            return
        }
        answer()
    }

    // -- purchase ------------------------------------------------------------------------------

    /** The params of the last `launchBillingFlow` — read with [stringsOf] (Play's getters are obfuscated). */
    @Volatile var lastFlowParams: BillingFlowParams? = null

    override fun launchBillingFlow(activity: Activity, params: BillingFlowParams): BillingResult {
        launchCount++
        lastFlowParams = params
        val code = launchScript.removeFirstOrNull() ?: BillingResponseCode.OK
        if (code == BillingResponseCode.OK) {
            val p = listenerPurchase
            if (p != null && autoDeliver) deliver(p, listenerPurchaseType)
            return result(BillingResponseCode.OK)
        }
        if (launchSource == "launch" && launchCount == 1) return result(code)
        main.post { listener.onPurchasesUpdated(result(code), null) }
        return result(BillingResponseCode.OK)
    }

    override fun acknowledgePurchase(params: AcknowledgePurchaseParams, listener: AcknowledgePurchaseResponseListener) {
        val token = params.purchaseToken
        acknowledgeCalls += token
        if (acknowledgeHangs.remove(token)) return // never answers
        // A scripted failure fails the FIRST attempt only (the retry then succeeds).
        val ok = !acknowledgeFailures.remove(token)
        listener.onAcknowledgePurchaseResponse(result(if (ok) BillingResponseCode.OK else BillingResponseCode.SERVICE_UNAVAILABLE))
    }

    override fun consumeAsync(params: ConsumeParams, listener: ConsumeResponseListener) {
        val token = params.purchaseToken
        consumeCalls += token
        if (consumeHangs.remove(token)) return // never answers
        val code = when (token) {
            in consumeNotOwned -> BillingResponseCode.ITEM_NOT_OWNED
            in consumeFailures -> BillingResponseCode.SERVICE_UNAVAILABLE.also { consumeFailures.remove(token) }
            else -> BillingResponseCode.OK
        }
        if (code == BillingResponseCode.OK) dropOwned(token)
        listener.onConsumeResponse(result(code), token)
    }

    // -- unused surface ------------------------------------------------------------------------

    // queryPurchaseHistoryAsync and querySkuDetailsAsync are gone from Play Billing 8.

    override fun showInAppMessages(activity: Activity, params: InAppMessageParams, listener: InAppMessageResponseListener): BillingResult =
        result(BillingResponseCode.FEATURE_NOT_SUPPORTED)

    override fun getBillingConfigAsync(params: GetBillingConfigParams, listener: BillingConfigResponseListener) {
        listener.onBillingConfigResponse(result(BillingResponseCode.FEATURE_NOT_SUPPORTED), null)
    }

    override fun isAlternativeBillingOnlyAvailableAsync(listener: AlternativeBillingOnlyAvailabilityListener) {
        listener.onAlternativeBillingOnlyAvailabilityResponse(result(BillingResponseCode.FEATURE_NOT_SUPPORTED))
    }

    override fun createAlternativeBillingOnlyReportingDetailsAsync(listener: AlternativeBillingOnlyReportingDetailsListener) {
        listener.onAlternativeBillingOnlyTokenResponse(result(BillingResponseCode.FEATURE_NOT_SUPPORTED), null)
    }

    override fun showAlternativeBillingOnlyInformationDialog(activity: Activity, listener: AlternativeBillingOnlyInformationDialogListener): BillingResult =
        result(BillingResponseCode.FEATURE_NOT_SUPPORTED)

    override fun isExternalOfferAvailableAsync(listener: ExternalOfferAvailabilityListener) {
        listener.onExternalOfferAvailabilityResponse(result(BillingResponseCode.FEATURE_NOT_SUPPORTED))
    }

    override fun createExternalOfferReportingDetailsAsync(listener: ExternalOfferReportingDetailsListener) {
        listener.onExternalOfferReportingDetailsResponse(result(BillingResponseCode.FEATURE_NOT_SUPPORTED), null)
    }

    override fun showExternalOfferInformationDialog(activity: Activity, listener: ExternalOfferInformationDialogListener): BillingResult =
        result(BillingResponseCode.FEATURE_NOT_SUPPORTED)

    companion object {
        fun result(code: Int): BillingResult = BillingResult.newBuilder().setResponseCode(code).setDebugMessage("").build()

        /**
         * A Play [Purchase] from the fields tests care about. `purchaseState` 0 = PURCHASED, 4 = PENDING
         * (the Play JSON encoding).
         */
        fun purchase(
            productId: String,
            token: String,
            orderId: String? = "GPA.$token",
            acknowledged: Boolean = false,
            pending: Boolean = false,
            obfuscatedAccountId: String? = null,
            purchaseTime: Long = 1_788_256_800_000L,
            autoRenewing: Boolean = false,
            quantity: Int = 1,
        ): Purchase {
            val json = JSONObject().apply {
                if (orderId != null) put("orderId", orderId)
                put("packageName", "ai.appdna.test")
                put("productIds", JSONArray(listOf(productId)))
                put("productId", productId)
                put("purchaseTime", purchaseTime)
                put("purchaseState", if (pending) 4 else 0)
                put("purchaseToken", token)
                put("acknowledged", acknowledged)
                put("autoRenewing", autoRenewing)
                put("quantity", quantity)
                if (obfuscatedAccountId != null) put("obfuscatedAccountId", obfuscatedAccountId)
            }
            return Purchase(json.toString(), "sig")
        }

        /** A [ProductDetails] (its constructor is package-private — reached reflectively). */
        /** One subscription offer: its `offerId` (null = the base plan), token, and phases as (micros, period). */
        data class Offer(val offerId: String?, val token: String, val phases: List<Pair<Long, String>>)

        fun productDetails(
            productId: String,
            type: String,
            priceMicros: Long,
            currency: String,
            offers: List<Offer>? = null,
        ): ProductDetails {
            val price = JSONObject().apply {
                put("priceAmountMicros", priceMicros)
                put("priceCurrencyCode", currency)
                put("formattedPrice", "$currency ${priceMicros / 1_000_000.0}")
            }
            val json = JSONObject().apply {
                put("productId", productId)
                put("type", type)
                put("title", productId)
                put("name", productId)
                put("description", productId)
                put("localizedIn", JSONArray(listOf("en-US")))
                put("skuDetailsToken", "sdt_$productId")
                if (type == ProductType.SUBS && offers != null) {
                    put("subscriptionOfferDetails", JSONArray().apply {
                        offers.forEach { o ->
                            put(JSONObject().apply {
                                put("offerIdToken", o.token)
                                put("basePlanId", "base")
                                o.offerId?.let { put("offerId", it) }
                                put("offerTags", JSONArray())
                                put("pricingPhases", JSONArray().apply {
                                    o.phases.forEach { (micros, period) ->
                                        put(JSONObject().apply {
                                            put("priceAmountMicros", micros)
                                            put("priceCurrencyCode", currency)
                                            put("formattedPrice", "$currency ${micros / 1_000_000.0}")
                                            put("billingPeriod", period)
                                            put("recurrenceMode", if (micros == 0L) 2 else 1)
                                            put("billingCycleCount", if (micros == 0L) 1 else 0)
                                        })
                                    }
                                })
                            })
                        }
                    })
                } else if (type == ProductType.SUBS) {
                    put("subscriptionOfferDetails", JSONArray().put(JSONObject().apply {
                        put("offerIdToken", "offer_$productId")
                        put("basePlanId", "base")
                        put("offerTags", JSONArray())
                        put("pricingPhases", JSONArray().put(JSONObject(price.toString()).apply {
                            put("billingPeriod", "P1M")
                            put("recurrenceMode", 1)
                            put("billingCycleCount", 0)
                        }))
                    }))
                } else {
                    put("oneTimePurchaseOfferDetails", price)
                }
            }
            val ctor = ProductDetails::class.java.getDeclaredConstructor(String::class.java)
            ctor.isAccessible = true
            return ctor.newInstance(json.toString())
        }

        /** Every String reachable from a Play params object (its getters are obfuscated). */
        fun stringsOf(obj: Any?, depth: Int = 0): List<String> {
            if (obj == null || depth > 3) return emptyList()
            if (obj is String) return listOf(obj)
            if (obj is Iterable<*>) return obj.flatMap { stringsOf(it, depth + 1) }
            if (!obj.javaClass.name.startsWith("com.android.billingclient") &&
                !obj.javaClass.name.startsWith("com.google.android.gms.internal")
            ) return emptyList()
            val out = mutableListOf<String>()
            var c: Class<*>? = obj.javaClass
            while (c != null && c != Any::class.java) {
                for (f in c.declaredFields) {
                    if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                    f.isAccessible = true
                    out += stringsOf(f.get(obj), depth + 1)
                }
                c = c.superclass
            }
            return out
        }

        /** (productId, productType) pairs a [QueryProductDetailsParams] asks for. */
        fun requestedProducts(params: QueryProductDetailsParams): List<Pair<String, String>> {
            val out = mutableListOf<Pair<String, String>>()
            var c: Class<*>? = params.javaClass
            while (c != null && c != Any::class.java) {
                for (f in c.declaredFields) {
                    if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                    f.isAccessible = true
                    val v = f.get(params) as? Iterable<*> ?: continue
                    for (item in v) {
                        val strings = stringsOf(item, 1)
                        val type = strings.firstOrNull { it == ProductType.SUBS || it == ProductType.INAPP } ?: continue
                        val id = strings.firstOrNull { it != type } ?: continue
                        out += id to type
                    }
                }
                c = c.superclass
            }
            return out
        }
    }
}
