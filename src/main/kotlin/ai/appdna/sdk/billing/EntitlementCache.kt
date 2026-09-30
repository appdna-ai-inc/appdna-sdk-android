package ai.appdna.sdk.billing

import android.content.Context
import ai.appdna.sdk.Log
import ai.appdna.sdk.AppDNA
import com.google.firebase.firestore.ListenerRegistration
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Caches entitlements locally using SharedPreferences and optionally
 * observes real-time entitlement updates from Firestore.
 *
 * The cache provides instant access to the last known entitlements on app launch
 * (before the server can be reached), while the Firestore listener ensures
 * entitlements stay in sync when changes occur server-side (e.g., renewal,
 * cancellation, refund).
 *
 * Firestore path: orgs/{orgId}/apps/{appId}/users/{userId}/entitlements
 */
internal class EntitlementCache(
    private val context: Context,
    /** The current identity's user id (null = anonymous). Injectable for tests. */
    private val currentUserId: () -> String? = { AppDNA.getIdentityRef()?.userId },
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Current cached entitlements. Round-36 — @Volatile: written from the Firestore listener thread + purchase/restore, read from getters on other threads. */
    @Volatile var entitlements: List<Entitlement> = emptyList()
        private set

    /** Whether the user has any active subscription. */
    val hasActiveSubscription: Boolean
        get() = entitlements.any { it.status in ACTIVE_STATUSES }

    /** Listeners notified when entitlements change. */
    private val changeListeners = CopyOnWriteArrayList<(List<Entitlement>) -> Unit>()

    /** Firestore real-time listener registration. */
    private var firestoreListener: ListenerRegistration? = null

    /**
     * SPEC-497 §13b.2 (R30/R31) — the internal product type of each entry (`"subs"` / `"inapp"`), kept
     * BESIDE the public [Entitlement] (whose constructor, `copy` and `componentN` are unchanged) and
     * persisted as `product_type`. A Firestore snapshot replaces only the `subs` entries.
     */
    private val productTypes = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * R34/R35 — "the listener is active": a snapshot carrying `subscriptions` (an empty array counts),
     * not `metadata.isFromCache`, has been applied for the current identity since the last
     * [startObserving]. [startObserving] and [stopObserving] reset it.
     */
    @Volatile internal var listenerActive: Boolean = false

    /** The product type recorded for [productId], or null. */
    internal fun productTypeOf(productId: String): String? = productTypes[productId]

    /** Active product-id set at the last change fan-out — diff-guards spurious callbacks (iOS parity). */
    @Volatile private var lastNotifiedProductIds: Set<String> = emptySet()
    /** Guards the diff-guard compare-and-set; setEntitlements runs from the Firestore listener thread AND purchase/restore callbacks. */
    private val notifyLock = Any()

    init {
        loadFromDisk()
    }

    // -- Public API --

    /**
     * Update the cache with a single new or updated entitlement.
     * Merges by productId (replaces existing entry for the same product).
     */
    fun update(entitlement: Entitlement, productType: String?) {
        val updated = entitlements
            .filter { it.productId != entitlement.productId }
            .plus(entitlement)
        if (productType != null) productTypes[entitlement.productId] = productType
        setEntitlements(updated)
    }

    /**
     * Replace all cached entitlements. Only the non-owning local build (§3.2 rule 3) uses this; the
     * owning path merges ([mergeRestoreReply]).
     */
    fun replaceAll(newEntitlements: List<Entitlement>, types: Map<String, String> = emptyMap()) {
        productTypes.clear()
        productTypes.putAll(types)
        setEntitlements(newEntitlements)
    }

    /**
     * SPEC-497 §13b.2 — THE one merge rule for `/restore` and the refresh (R32/R33, R35):
     * `cache = (cache − {google_play inapp entries whose token is not excluded or skipped}) ∪ reply`.
     * The reply only upserts; a Firestore snapshot owns `subs`. While the listener is NOT active, a
     * merge also removes any `google_play` `subs` entry whose product is missing from the OK SUBS query
     * ([okSubsProducts]), unless its token is excluded or skipped. Entries of other stores are left alone.
     *
     * @param protectedProducts products of owned purchases whose token was excluded or skipped — their
     *   entries are kept. An entry with no owned purchase has no token and is not protected.
     */
    fun mergeRestoreReply(
        reply: List<Pair<Entitlement, String?>>,
        protectedProducts: Set<String>,
        okSubsProducts: Set<String>?,
    ) {
        var kept = entitlements.filterNot { e ->
            e.store == "google_play" && typeOf(e) == "inapp" && e.productId !in protectedProducts
        }
        if (!listenerActive && okSubsProducts != null) {
            kept = kept.filterNot { e ->
                e.store == "google_play" && typeOf(e) == "subs" &&
                    e.productId !in okSubsProducts && e.productId !in protectedProducts
            }
        }
        val replyIds = reply.map { it.first.productId }.toSet()
        val merged = kept.filter { it.productId !in replyIds } + reply.map { it.first }
        for ((e, t) in reply) if (t != null) productTypes[e.productId] = t
        for (id in productTypes.keys.toList()) if (merged.none { it.productId == id }) productTypes.remove(id)
        setEntitlements(merged)
    }

    /**
     * A Firestore snapshot: replaces the `subs` entries (and entries of unknown type from other stores)
     * and KEEPS cached `inapp` entries — the doc lists only subscriptions, so replacing the whole cache
     * wiped the lifetime entitlement on every snapshot (R30).
     */
    internal fun applySnapshot(subscriptions: List<Entitlement>) {
        val inapp = entitlements.filter { typeOf(it) == "inapp" && subscriptions.none { s -> s.productId == it.productId } }
        for (s in subscriptions) productTypes[s.productId] = "subs"
        setEntitlements(inapp + subscriptions)
    }

    private fun typeOf(e: Entitlement): String = productTypes[e.productId] ?: inferType(e)

    /**
     * Remove a specific entitlement by product ID.
     */
    fun remove(productId: String) {
        setEntitlements(entitlements.filter { it.productId != productId })
    }

    /**
     * Clear all cached entitlements.
     */
    fun clear() {
        productTypes.clear()
        setEntitlements(emptyList())
    }

    /**
     * Check if a specific product has an active entitlement.
     */
    fun isEntitled(productId: String): Boolean {
        return entitlements.any { it.productId == productId && it.status in ACTIVE_STATUSES }
    }

    /**
     * Get a specific entitlement by product ID.
     */
    fun getEntitlement(productId: String): Entitlement? {
        return entitlements.find { it.productId == productId }
    }

    /**
     * Get all cached entitlements.
     */
    fun getAll(): List<Entitlement> = entitlements

    /**
     * Register a listener for entitlement changes.
     */
    fun addChangeListener(listener: (List<Entitlement>) -> Unit) {
        changeListeners.add(listener)
    }

    /**
     * Remove a previously registered change listener.
     */
    fun removeChangeListener(listener: (List<Entitlement>) -> Unit) {
        changeListeners.remove(listener)
    }

    // -- Firestore Real-Time Sync --

    /** How many times a listener registration was attempted for the current identity, and where (tests). */
    @Volatile internal var observeStarts: Int = 0
    @Volatile internal var lastObservedPath: String? = null

    /**
     * Start observing entitlement changes from Firestore.
     *
     * SPEC-070-A A.23 — Listens to a single document at
     * `orgs/{orgId}/apps/{appId}/users/{userId}/entitlements/current` and parses
     * its `subscriptions[]` array, mirroring iOS `EntitlementCache.swift:30-39,63-64`.
     *
     * Backend writes the doc at this path — see
     * `src/modules/monetization/services/EntitlementSyncService.ts:95`.
     *
     * Previously this code treated the same path as a *collection* and called
     * `db.collection(path).addSnapshotListener` — which never matched the
     * backend's document layout, so subscription state never streamed in.
     *
     * @param orgId The organization ID.
     * @param appId The app ID.
     * @param userId The user ID.
     */
    @Synchronized
    fun startObserving(orgId: String, appId: String, userId: String) {
        stopObserving()
        // R35: never observe a user who is no longer the current identity (a stale re-point).
        if (userId != currentUserId()) {
            Log.debug("EntitlementCache: not observing $userId — not the current identity")
            return
        }

        val basePath = "orgs/$orgId/apps/$appId/users/$userId"
        val docPath = "$basePath/entitlements/current"
        Log.debug("EntitlementCache: observing Firestore at $docPath")
        observeStarts++
        lastObservedPath = docPath

        val db = AppDNA.firestoreDB ?: run {
            Log.warning("EntitlementCache: Firestore not available — skipping real-time sync")
            return
        }
        firestoreListener = db
            .document(docPath)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.error("EntitlementCache Firestore error: ${error.message}")
                    return@addSnapshotListener
                }

                // SPEC-070-A H.25: empty-snapshot debounce. A null/missing
                // snapshot can happen during a reconnect or when the doc has
                // not yet been provisioned for this user — both are
                // **transient** states that must NOT clobber a non-empty
                // cache. We only treat the snapshot as authoritative when:
                //   - the doc EXISTS and
                //   - it explicitly carries a `subscriptions` field (even
                //     an empty array means "the server says no entitlements").
                //
                // Previously, an empty snapshot during reconnect would clear
                // the cache and stamp out an active subscription locally,
                // briefly toggling paywalls back on for the user.
                val data = snapshot?.data
                if (snapshot == null || !snapshot.exists() || data == null) {
                    Log.debug("EntitlementCache: empty/missing snapshot — preserving cached entitlements")
                    return@addSnapshotListener
                }
                if (!data.containsKey("subscriptions")) {
                    Log.debug("EntitlementCache: snapshot has no `subscriptions` field — preserving cached entitlements")
                    return@addSnapshotListener
                }

                val newEntitlements = parseSubscriptions(data)
                Log.debug("EntitlementCache: received ${newEntitlements.size} entitlements from Firestore")
                // The snapshot owns `subs`; cached in-app entries survive it (R30).
                applySnapshot(newEntitlements)
                if (!snapshot.metadata.isFromCache) listenerActive = true
            }
    }

    /**
     * Parse the `subscriptions[]` array from a Firestore document map into
     * [Entitlement] instances. Mirrors iOS `parseFirestoreData`
     * (EntitlementCache.swift:63-81).
     *
     * Visible to tests via `internal` (delegates to the static helper in
     * the companion object so unit tests can invoke without a Context).
     */
    internal fun parseSubscriptions(data: Map<String, Any?>): List<Entitlement> =
        parseSubscriptionsImpl(data)

    /**
     * Stop observing Firestore entitlement changes.
     */
    @Synchronized
    fun stopObserving() {
        firestoreListener?.remove()
        firestoreListener = null
        listenerActive = false
    }

    // -- Persistence --

    /**
     * Load cached entitlements from SharedPreferences.
     */
    private fun loadFromDisk() {
        val json = prefs.getString(KEY_ENTITLEMENTS, null) ?: return
        // R35: the persisted cache belongs to `owner_user_id`. Another user's cache is discarded; an
        // anonymous (null) owner — and a legacy cache with no owner key — is adopted by the current identity.
        if (prefs.contains(KEY_OWNER)) {
            val owner = prefs.getString(KEY_OWNER, null)
            if (owner != null && owner != currentUserId()) {
                Log.debug("EntitlementCache: discarding a persisted cache owned by another user")
                prefs.edit().remove(KEY_ENTITLEMENTS).remove(KEY_OWNER).apply()
                return
            }
        }
        try {
            val array = JSONArray(json)
            val loaded = mutableListOf<Entitlement>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val ent = Entitlement(
                    productId = obj.getString("product_id"),
                    store = obj.optString("store", "google_play"),
                    status = obj.optString("status", "unknown"),
                    expiresAt = if (obj.isNull("expires_at")) null else obj.optString("expires_at", "").ifEmpty { null },
                    isTrial = obj.optBoolean("is_trial", false),
                    offerType = if (obj.isNull("offer_type")) null else obj.optString("offer_type", "").ifEmpty { null },
                )
                loaded.add(ent)
                // R31 SDK minor 1: a legacy entry without a stored type is inferred.
                val t = obj.optString("product_type", "").takeIf { it == "subs" || it == "inapp" } ?: inferType(ent)
                productTypes[ent.productId] = t
            }
            entitlements = loaded
            // Round-36 — seed the notify diff-guard from the disk-loaded set so the FIRST Firestore
            // snapshot that merely CONFIRMS the persisted entitlements doesn't fire a spurious
            // onEntitlementsChanged. loadFromDisk bypasses setEntitlements, so without this the guard
            // stayed empty and `empty != {persisted}` fired one no-op callback on every cold start.
            // Runs at init (single-threaded, before any listener); @Volatile makes it visible.
            lastNotifiedProductIds = loaded.map { it.productId }.toSet()
            Log.debug("EntitlementCache: loaded ${loaded.size} entitlements from disk")
        } catch (e: Exception) {
            Log.warning("EntitlementCache: failed to load from disk: ${e.message}")
        }
    }

    /**
     * Persist entitlements to SharedPreferences.
     */
    private fun saveToDisk(items: List<Entitlement>) {
        val array = JSONArray().apply {
            items.forEach { ent ->
                put(JSONObject().apply {
                    put("product_id", ent.productId)
                    put("store", ent.store)
                    put("status", ent.status)
                    if (ent.expiresAt != null) put("expires_at", ent.expiresAt)
                    put("is_trial", ent.isTrial)
                    if (ent.offerType != null) put("offer_type", ent.offerType)
                    put("product_type", productTypes[ent.productId] ?: inferType(ent))
                })
            }
        }
        val owner = currentUserId()
        prefs.edit()
            .putString(KEY_ENTITLEMENTS, array.toString())
            .apply { if (owner != null) putString(KEY_OWNER, owner) else remove(KEY_OWNER) }
            .apply()
    }

    /**
     * Internal setter that updates in-memory cache, persists, and notifies listeners.
     *
     * SPEC-070-A B.7 — fan out to:
     *   1. all callbacks registered via [addChangeListener] (host onEntitlementsChanged
     *      callback via `BillingModule.onEntitlementsChanged(callback)`)
     *   2. the typed `AppDNABillingDelegate.onEntitlementsChanged(...)` registered
     *      on the BillingModule (`AppDNA.billing.setDelegate(...)`).
     * Mirrors iOS `EntitlementCache.swift` `NotificationCenter .entitlementsChanged`
     * fan-out.
     */
    private fun setEntitlements(newEntitlements: List<Entitlement>) {
        entitlements = newEntitlements
        saveToDisk(newEntitlements)
        // Round-35/36 — diff-guard the change fan-out (listeners + billing delegate) on the active
        // product-id set, matching iOS BillingModule.refreshEntitlementCache (posts only when
        // newIds != lastKnownEntitlementIds). Android previously notified UNCONDITIONALLY on every
        // update()/replaceAll(), so re-purchasing an already-held entitlement or a restore that
        // returned the already-cached set fired a spurious onEntitlementsChanged that iOS never did.
        // The cache (entitlements + disk) still updates every call so a same-id expiry change persists;
        // only the notification is gated. Round-36 — the compare-and-set runs under `notifyLock`
        // because setEntitlements is reachable from the Firestore listener thread AND purchase/restore
        // callbacks concurrently; notify OUTSIDE the lock (like iOS releases its lock before posting).
        val newIds = newEntitlements.map { it.productId }.toSet()
        val changed = synchronized(notifyLock) {
            if (newIds == lastNotifiedProductIds) {
                false
            } else {
                lastNotifiedProductIds = newIds
                true
            }
        }
        if (!changed) return
        notifyListeners()
        notifyBillingDelegate()
    }

    /**
     * Test seam: fire the change listeners without a real purchase.
     *
     * `internal`, so it is invisible to hosts. The alternative was to leave the pre-init-listener
     * regression untestable, and an untestable fix is a fix that comes back.
     */
    internal fun notifyChangeListenersForTesting(values: List<Entitlement>) {
        for (listener in changeListeners) listener(values)
    }

    private fun notifyListeners() {
        for (listener in changeListeners) {
            try {
                listener(entitlements)
            } catch (e: Throwable) {
                Log.warning("EntitlementCache: listener threw: ${e.message}")
            }
        }
    }

    /**
     * SPEC-070-A B.7 — fire the typed delegate's onEntitlementsChanged. Read
     * fresh on every call so a delegate registered after configure() still
     * receives updates. Posted to main thread so host code never runs on
     * the Firestore listener thread.
     */
    private fun notifyBillingDelegate() {
        val delegate = try {
            AppDNA.billing.billingListener
        } catch (_: Throwable) {
            return
        } ?: return
        val current = entitlements
        try {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try {
                    delegate.onEntitlementsChanged(current)
                } catch (e: Throwable) {
                    Log.warning("AppDNABillingDelegate.onEntitlementsChanged threw: ${e.message}")
                }
            }
        } catch (e: Throwable) {
            Log.warning("EntitlementCache: delegate fan-out failed: ${e.message}")
        }
    }

    companion object {
        private const val PREFS_NAME = "ai.appdna.sdk.billing"
        private const val KEY_ENTITLEMENTS = "entitlements_cache"
        private const val KEY_OWNER = "entitlements_cache_owner_user_id"

        /**
         * R31 SDK minor 1 — a legacy entry without a stored type counts as `inapp` when it is a
         * `google_play` entry with no expiry and `status == "active"` (a subscription always carries
         * `expires_at`), else `subs`. A wrong guess self-heals at the next refresh (known).
         */
        internal fun inferType(e: Entitlement): String =
            if (e.store == "google_play" && e.expiresAt == null && e.status == "active") "inapp" else "subs"

        /**
         * SPEC-497 §13b.2 "Clearing" — drop the persisted cache (and its owner), the same pattern as
         * `NativeBillingManager.clearPersistedSnapshot`, so it works even when no manager exists.
         */
        fun clearPersisted(context: Context) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .remove(KEY_ENTITLEMENTS).remove(KEY_OWNER).commit()
        }

        /**
         * Statuses considered "active" for entitlement checks — the server's entitled set
         * (`ACTIVE_STATUSES` in the server's entitlement rules): a subscription in `billing_retry`
         * (the store is still retrying the renewal payment) is entitled. `on_hold`, `pending` and
         * `paused` are not. A canceled subscription still in its paid period arrives as `active`.
         */
        internal val ACTIVE_STATUSES = setOf("active", "trialing", "grace_period", "billing_retry")

        /**
         * Static parser for Firestore subscription documents. Pure — no I/O,
         * safe to call from unit tests without a `Context`.
         */
        @Suppress("UNCHECKED_CAST")
        internal fun parseSubscriptionsImpl(data: Map<String, Any?>): List<Entitlement> {
            val raw = data["subscriptions"] as? List<Map<String, Any?>> ?: return emptyList()
            return raw.mapNotNull { sub ->
                try {
                    val productId = sub["product_id"] as? String ?: return@mapNotNull null
                    val store = sub["store"] as? String ?: return@mapNotNull null
                    val status = sub["status"] as? String ?: return@mapNotNull null
                    Entitlement(
                        productId = productId,
                        store = store,
                        status = status,
                        expiresAt = sub["expires_at"] as? String,
                        isTrial = (sub["is_trial"] as? Boolean) ?: false,
                        offerType = sub["offer_type"] as? String,
                    )
                } catch (_: Exception) {
                    null
                }
            }
        }
    }
}
