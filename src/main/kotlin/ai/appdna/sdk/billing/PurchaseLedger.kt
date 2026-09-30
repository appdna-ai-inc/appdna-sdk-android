package ai.appdna.sdk.billing

import ai.appdna.sdk.Log
import ai.appdna.sdk.storage.LocalStorage
import org.json.JSONArray
import org.json.JSONObject

/** The finalize action still owed on a reported purchase (§13a.2 precedence (2)). */
internal enum class Finalize(val wire: String) {
    Consume("consume"),
    Acknowledge("acknowledge");

    companion object {
        fun fromWire(v: String?): Finalize? = values().firstOrNull { it.wire == v }
    }
}

/** A token for which `purchase_completed` was emitted, with the finalize it may still owe. */
internal data class ReportedToken(val token: String, val pendingFinalize: Finalize?)

/**
 * One undelivered `onPurchaseCompleted` (D-R40-1, §13a.2 queue rules 1–7). Keyed and deduped by
 * [purchaseToken]; [ownerToken] null = untagged.
 */
internal data class PendingDelivery(
    val purchaseToken: String,
    val transactionId: String,
    val productId: String,
    val purchaseTime: Long,
    val quantity: Int,
    val ownerToken: String?,
    val queuedAt: Long,
)

/**
 * SPEC-497 §13a.2 — the persisted billing ledger: the reported set, the verify-attempted set, the
 * purchase-class cache, the delivery queue and the identified-users set.
 *
 * Every write goes through [write], which lands all changed keys in ONE [LocalStorage.setStringsSync]
 * (`commit()`), so the reported set and a queue entry are on disk together (Q1). Callers hold
 * `purchaseCompletionMutex`; this class does no locking of its own. With no storage (a bare test
 * manager) the ledger lives in memory only.
 */
internal class PurchaseLedger(
    private val storage: LocalStorage?,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val memory = HashMap<String, String?>()

    private fun read(key: String): String? = if (storage != null) storage.getString(key) else memory[key]

    // -- reads --

    fun reported(): List<ReportedToken> = parseArray(read(KEY_REPORTED)) { o ->
        val t = o.optString("token", "")
        if (t.isEmpty()) null else ReportedToken(t, Finalize.fromWire(o.optString("pending_finalize", "").ifEmpty { null }))
    }

    fun verifyAttempted(): List<String> = parseStrings(read(KEY_VERIFY_ATTEMPTED))

    fun classCache(): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        val raw = read(KEY_CLASS_CACHE) ?: return out
        try {
            val o = JSONObject(raw)
            for (k in o.keys()) out[k] = o.optString(k)
        } catch (e: Exception) {
            Log.debug("PurchaseLedger: class cache unreadable (${e.message})")
        }
        return out
    }

    fun deliveries(): List<PendingDelivery> = parseArray(read(KEY_DELIVERIES)) { o ->
        val t = o.optString("purchaseToken", "")
        if (t.isEmpty()) null else PendingDelivery(
            purchaseToken = t,
            transactionId = o.optString("transactionId", t).ifEmpty { t },
            productId = o.optString("productId", ""),
            purchaseTime = o.optLong("purchaseTime", 0L),
            quantity = o.optInt("quantity", 1),
            ownerToken = if (o.isNull("ownerToken")) null else o.optString("ownerToken", "").ifEmpty { null },
            queuedAt = o.optLong("queuedAt", 0L),
        )
    }

    fun identifiedTokens(): List<String>? = read(KEY_IDENTIFIED)?.let { parseStrings(it) }

    // -- the one writer --

    /** Persist every non-null argument in ONE synchronous write. Returns false if the commit failed. */
    fun write(
        reported: List<ReportedToken>? = null,
        verifyAttempted: List<String>? = null,
        classCache: Map<String, String>? = null,
        deliveries: List<PendingDelivery>? = null,
        identifiedTokens: List<String>? = null,
    ): Boolean {
        val values = LinkedHashMap<String, String?>()
        reported?.let { list ->
            values[KEY_REPORTED] = JSONArray().apply {
                list.takeLast(CAP_REPORTED).forEach { r ->
                    put(JSONObject().apply {
                        put("token", r.token)
                        put("pending_finalize", r.pendingFinalize?.wire ?: JSONObject.NULL)
                    })
                }
            }.toString()
        }
        verifyAttempted?.let { values[KEY_VERIFY_ATTEMPTED] = JSONArray(it.distinct().takeLast(CAP_VERIFY_ATTEMPTED)).toString() }
        classCache?.let { m ->
            val kept = m.entries.toList().takeLast(CAP_CLASS_CACHE)
            values[KEY_CLASS_CACHE] = JSONObject().apply { kept.forEach { (k, v) -> put(k, v) } }.toString()
        }
        deliveries?.let { list ->
            val fresh = purgeExpired(list)
            val capped = if (fresh.size > CAP_DELIVERIES) {
                Log.warning("PurchaseLedger: delivery queue over $CAP_DELIVERIES entries — dropping ${fresh.size - CAP_DELIVERIES} oldest (a manual grant is needed for those)")
                fresh.takeLast(CAP_DELIVERIES)
            } else fresh
            values[KEY_DELIVERIES] = JSONArray().apply {
                capped.forEach { d ->
                    put(JSONObject().apply {
                        put("purchaseToken", d.purchaseToken)
                        put("transactionId", d.transactionId)
                        put("productId", d.productId)
                        put("purchaseTime", d.purchaseTime)
                        put("quantity", d.quantity)
                        put("ownerToken", d.ownerToken ?: JSONObject.NULL)
                        put("queuedAt", d.queuedAt)
                    })
                }
            }.toString()
        }
        identifiedTokens?.let { values[KEY_IDENTIFIED] = JSONArray(it.distinct().takeLast(CAP_IDENTIFIED)).toString() }
        if (values.isEmpty()) return true
        return if (storage != null) storage.setStringsSync(values) else { memory.putAll(values); true }
    }

    /** Entries older than 30 days are purged (the manual-grant note in the docs covers them). */
    fun purgeExpired(list: List<PendingDelivery>): List<PendingDelivery> {
        val cutoff = clock() - DELIVERY_TTL_MS
        val (keep, drop) = list.partition { it.queuedAt >= cutoff }
        if (drop.isNotEmpty()) Log.warning("PurchaseLedger: purged ${drop.size} delivery queue entries older than 30 days")
        return keep
    }

    /** Test seam: wipe the ledger. */
    internal fun clearForTesting() {
        write(emptyList(), emptyList(), emptyMap(), emptyList())
        if (storage != null) storage.setStringsSync(mapOf(KEY_IDENTIFIED to null)) else memory.remove(KEY_IDENTIFIED)
    }

    private fun <T> parseArray(raw: String?, map: (JSONObject) -> T?): List<T> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(map) }
        } catch (e: Exception) {
            Log.debug("PurchaseLedger: unreadable entry list (${e.message})")
            emptyList()
        }
    }

    private fun parseStrings(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i -> arr.optString(i, "").ifEmpty { null } }
        } catch (_: Exception) {
            emptyList()
        }
    }

    companion object {
        const val KEY_REPORTED = "appdna.reported_purchase_tokens"
        const val KEY_VERIFY_ATTEMPTED = "appdna.verify_attempted_v1"
        const val KEY_CLASS_CACHE = "appdna.purchase_class_cache_v1"
        const val KEY_DELIVERIES = "appdna.pending_deliveries_v1"
        const val KEY_IDENTIFIED = "appdna.identified_user_tokens_v1"

        const val CAP_REPORTED = 500
        const val CAP_VERIFY_ATTEMPTED = 500
        const val CAP_CLASS_CACHE = 500
        const val CAP_DELIVERIES = 100
        const val CAP_IDENTIFIED = 50
        const val DELIVERY_TTL_MS = 30L * 24 * 60 * 60 * 1000

        const val CLASS_CONSUMABLE = "consumable"
        const val CLASS_NON_CONSUMABLE = "non_consumable"
        const val CLASS_UNKNOWN = "unknown"
    }
}
