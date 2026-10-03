package ai.appdna.sdk.billing

import ai.appdna.sdk.AppDNA
import ai.appdna.sdk.Log
import ai.appdna.sdk.network.ApiClient
import ai.appdna.sdk.network.ApiResult
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * One verified purchase as the server's reply DTO describes it
 * (`{entitled, product_id, product_type, store, status, expires_at, is_trial, original_transaction_id,
 * consume}`). The public [Entitlement] is unchanged; the extra facts ride beside it.
 */
internal data class VerifiedPurchase(
    val entitlement: Entitlement,
    /** `"subs"` / `"inapp"`, or null when an older server did not say. */
    val productType: String?,
    /** C1: the server's `Product.type == 'consumable'` decision. */
    val consume: Boolean,
    val originalTransactionId: String?,
    val entitled: Boolean,
    /** The purchase token the item belongs to (restore items), when the reply carries it. */
    val token: String? = null,
    /**
     * `"production"` / `"sandbox"` as the server decided it from the store (Play `testPurchase` on a
     * subscription, `purchaseType == 0` on a one-time product); null when an older server did not say.
     */
    val environment: String? = null,
)

/** One owned purchase sent to `/billing/restore`. */
internal data class RestoreTransaction(val token: String, val productId: String, val productType: String?)

/**
 * Handles server-side receipt verification and purchase restoration.
 *
 * - POST /api/v1/billing/verify — verify one purchase
 * - POST /api/v1/billing/restore — verify the owned purchases
 * - GET  /api/v1/billing/entitlements — the server's view of the user's entitlements
 *
 * Every call sends `billing_owner` (from the ownership policy this verifier is built
 * with) and `product_type`; failures keep their HTTP status and the server's error code
 * ([VerificationException.httpStatus] / [VerificationException.serverError]) so `PurchaseCompletion`
 * can tell a terminal refusal from a retryable one.
 */
internal class ReceiptVerifier(
    private val apiClient: ApiClient?,
    private val billingOwner: String = "sdk",
    private val post: suspend (String, String) -> ApiResult = { path, body ->
        apiClient?.postForResult(path, body) ?: ApiResult(null, null, null)
    },
    private val currentUserId: () -> String? = { AppDNA.getIdentityRef()?.userId },
) {

    /**
     * Verify a purchase and return the server's reply.
     *
     * @throws VerificationException for every non-2xx reply (with `httpStatus` and the server's code)
     *   and for a network error after the retries (`httpStatus == null`).
     */
    suspend fun verify(
        purchaseToken: String,
        productId: String,
        productType: String? = null,
        platform: String = "android",
        paywallId: String? = null,
        experimentId: String? = null,
    ): VerifiedPurchase {
        val body = JSONObject().apply {
            put("platform", platform)
            put("transaction", JSONObject().apply {
                put("token", purchaseToken)
                put("productId", productId)
            })
            if (productType != null) put("product_type", productType)
            put("billing_owner", billingOwner)
            if (paywallId != null) put("paywall_id", paywallId)
            if (experimentId != null) put("experiment_id", experimentId)
            // The server decodes the purchase's obfuscatedAccountId and compares it with the derived
            // token of this user (tagged + match → grant; tagged + mismatch → 403 unless no other user
            // owns it; untagged → grant and claim). Anonymous → no app_user_id.
            currentUserId()?.let { put("app_user_id", it) }
        }
        Log.debug("Verifying receipt for product: $productId")
        val result = post("/api/v1/billing/verify", body.toString())
        if (!result.isSuccess) {
            throw VerificationException(
                message = if (result.status == null) "No response from server during verification"
                else "Verification failed (HTTP ${result.status})${result.errorCode?.let { ": $it" } ?: ""}",
                httpStatus = result.status,
                serverError = result.errorCode,
            )
        }
        return try {
            parseVerifyReply(JSONObject(result.body ?: ""))
        } catch (e: Exception) {
            Log.error("Failed to parse verification response: ${e.message}")
            throw VerificationException("Failed to parse verification response: ${e.message}", e, result.status)
        }
    }

    /**
     * Send the owned purchases to `/billing/restore` and return the server's items.
     *
     * Error typing ("Restore error contract"): a network error → [BillingError.NetworkError];
     * a non-2xx reply (503 `store_unavailable`, 422 `store_credentials_missing`, …) or an unparseable
     * body → [BillingError.ServerError].
     */
    suspend fun restore(transactions: List<RestoreTransaction>): List<VerifiedPurchase> {
        if (transactions.isEmpty()) return emptyList()
        val body = JSONObject().apply {
            put("platform", "android")
            put("transactions", JSONArray().apply {
                transactions.forEach { tx ->
                    put(JSONObject().apply {
                        put("token", tx.token)
                        put("productId", tx.productId)
                        if (tx.productType != null) put("product_type", tx.productType)
                    })
                }
            })
            put("billing_owner", billingOwner)
            currentUserId()?.let { put("app_user_id", it) }
        }
        Log.debug("Restoring ${transactions.size} purchases")
        val result = post("/api/v1/billing/restore", body.toString())
        if (result.status == null) throw BillingError.NetworkError(IOException("no response"))
        if (!result.isSuccess) {
            throw BillingError.ServerError("restore failed (HTTP ${result.status})${result.errorCode?.let { ": $it" } ?: ""}")
        }
        return try {
            val dataArray = JSONObject(result.body ?: "").optJSONArray("data")
                ?: throw IllegalStateException("Missing 'data' in restore response")
            val out = mutableListOf<VerifiedPurchase>()
            for (i in 0 until dataArray.length()) {
                val item = dataArray.optJSONObject(i) ?: continue
                out.add(parseItem(item))
            }
            Log.info("Restored ${out.size} items")
            out
        } catch (e: Exception) {
            throw BillingError.ServerError("restore reply unparseable: ${e.message}")
        }
    }

    /**
     * Fetch the server's current entitlements for the identified user. No user → empty (the server's
     * SDK path answers `{has_active_subscription:false, subscriptions:[]}` without a user anyway).
     */
    suspend fun fetchEntitlements(): List<Entitlement> {
        val client = apiClient ?: return emptyList()
        val userId = currentUserId() ?: return emptyList()
        val path = "/api/v1/billing/entitlements?app_user_id=" + java.net.URLEncoder.encode(userId, "UTF-8")
        val response = client.get(path) ?: return emptyList()
        return try {
            val data = response.optJSONObject("data") ?: return emptyList()
            val subscriptions = data.optJSONArray("subscriptions") ?: return emptyList()
            (0 until subscriptions.length()).mapNotNull { subscriptions.optJSONObject(it) }
                .map { parseItem(it).entitlement }
        } catch (e: Exception) {
            Log.error("Failed to parse entitlements response: ${e.message}")
            emptyList()
        }
    }

    companion object {
        /**
         * The `/billing/verify` reply → [VerifiedPurchase]. Accepts the DTO under `data`, and the
         * legacy `{ data: { entitled, subscription: <entity> } }` shape of older servers.
         */
        fun parseVerifyReply(json: JSONObject): VerifiedPurchase {
            val data = json.optJSONObject("data")
                ?: throw VerificationException("Missing 'data' in verification response")
            val legacy = data.optJSONObject("subscription")
            val item = parseItem(legacy ?: data)
            return if (legacy != null && data.has("entitled")) item.copy(entitled = data.optBoolean("entitled", true)) else item
        }

        /** One reply item (verify `data`, a restore `data[]` element, an `/entitlements` subscription). */
        fun parseItem(json: JSONObject): VerifiedPurchase {
            val productId = json.optStringOrNull("product_id") ?: json.optStringOrNull("productId") ?: ""
            val store = json.optStringOrNull("store") ?: "google_play"
            val status = json.optStringOrNull("status") ?: "unknown"
            val expiresAt = json.optStringOrNull("expires_at") ?: json.optStringOrNull("currentPeriodEnd")
            val isTrial = when {
                json.has("is_trial") && !json.isNull("is_trial") -> json.optBoolean("is_trial", false)
                json.has("isTrialPeriod") -> json.optBoolean("isTrialPeriod", false)
                else -> status == "trialing"
            }
            val offerType = json.optJSONObject("offerApplied")?.optStringOrNull("offer_type")
                ?: json.optStringOrNull("offer_type")
            val productType = json.optStringOrNull("product_type")?.takeIf { it == "subs" || it == "inapp" }
            return VerifiedPurchase(
                entitlement = Entitlement(
                    productId = productId,
                    store = store,
                    status = status,
                    expiresAt = expiresAt,
                    isTrial = isTrial,
                    offerType = offerType,
                ),
                productType = productType,
                consume = json.optBoolean("consume", false),
                originalTransactionId = json.optStringOrNull("original_transaction_id")
                    ?: json.optStringOrNull("originalTransactionId"),
                entitled = if (json.has("entitled")) json.optBoolean("entitled", true) else true,
                token = json.optStringOrNull("token") ?: json.optStringOrNull("purchase_token"),
                environment = json.optStringOrNull("environment")?.takeIf { it == "production" || it == "sandbox" },
            )
        }
    }
}

/**
 * `JSONObject.optString` returns the literal string "null" when a JSON `null` value is present — and
 * an empty string when the key is missing. This turns both into a real Kotlin `null`.
 */
private fun JSONObject.optStringOrNull(key: String): String? {
    if (!has(key) || isNull(key)) return null
    val v = optString(key, "")
    return if (v.isEmpty()) null else v
}

/**
 * Exception thrown when receipt verification fails.
 *
 * [httpStatus] (null = no response) and [serverError] (the server's error code, e.g.
 * `store_credentials_missing`, `provider_owned`, `token_owned_by_other_app`) are kept so the caller can
 * classify the failure; both default to null so every existing constructor call compiles.
 */
class VerificationException @JvmOverloads constructor(
    message: String,
    cause: Throwable? = null,
    val httpStatus: Int? = null,
    val serverError: String? = null,
) : Exception(message, cause) {
}

/**
 * "Failure handling" — the per-status class of a verify outcome.
 * `Terminal`: 400, 403, 409, 422 and any other 4xx except 401/429 — never retried.
 * `Retryable`: 401, 429, 5xx, a network error (no status) or a timeout — retried at the next ON_START.
 */
internal enum class VerifyOutcomeClass { Ok, Terminal, Retryable }

internal fun classifyVerifyStatus(status: Int?): VerifyOutcomeClass = when {
    status == null -> VerifyOutcomeClass.Retryable
    status in 200..299 -> VerifyOutcomeClass.Ok
    status == 401 || status == 429 -> VerifyOutcomeClass.Retryable
    status >= 500 -> VerifyOutcomeClass.Retryable
    status in 400..499 -> VerifyOutcomeClass.Terminal
    else -> VerifyOutcomeClass.Retryable
}
