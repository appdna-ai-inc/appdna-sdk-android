package ai.appdna.sdk.billing

import ai.appdna.sdk.network.ApiResult
import ai.appdna.sdk.network.decodeErrorCode
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** SPEC-497 §13a.2 "Verify result plumbing" / §13b.4 — ReceiptVerifier with canned ApiResults. */
class ReceiptVerifierTest {

    private val dto = JSONObject().put("data", JSONObject().apply {
        put("entitled", true); put("product_id", "monthly_sub"); put("product_type", "subs")
        put("store", "google_play"); put("status", "trialing"); put("expires_at", "2026-10-07T12:00:00Z")
        put("is_trial", true); put("original_transaction_id", "GPA.1"); put("consume", false)
    }).toString()

    private var lastBody: JSONObject? = null

    private fun verifier(result: ApiResult, owner: String = "sdk") = ReceiptVerifier(
        apiClient = null, billingOwner = owner,
        post = { _, body -> lastBody = JSONObject(body); result },
        currentUserId = { "user_1" },
    )

    private fun failure(status: Int?, code: String? = null): VerificationException {
        try {
            runBlocking { verifier(ApiResult(status, "{}", code)).verify("t", "p", "inapp") }
        } catch (e: VerificationException) {
            return e
        }
        fail("expected a VerificationException for $status"); throw AssertionError()
    }

    @Test fun `200 parses the DTO - consume, product_type, expires_at, is_trial, original_transaction_id`() = runBlocking {
        val v = verifier(ApiResult(200, dto, null)).verify("tok", "monthly_sub", "subs")
        assertEquals("monthly_sub", v.entitlement.productId)
        assertEquals("2026-10-07T12:00:00Z", v.entitlement.expiresAt)
        assertTrue(v.entitlement.isTrial)
        assertEquals("subs", v.productType)
        assertEquals(false, v.consume)
        assertEquals("GPA.1", v.originalTransactionId)
    }

    @Test fun `the request carries product_type, billing_owner from the policy, and app_user_id`() = runBlocking {
        verifier(ApiResult(200, dto, null), owner = "sdk").verify("tok", "monthly_sub", "subs")
        assertEquals("subs", lastBody!!.getString("product_type"))
        assertEquals("sdk", lastBody!!.getString("billing_owner"))
        assertEquals("user_1", lastBody!!.getString("app_user_id"))
    }

    @Test fun `per-status classes`() {
        assertEquals(VerifyOutcomeClass.Terminal, classifyVerifyStatus(failure(400).httpStatus))
        assertEquals(VerifyOutcomeClass.Retryable, classifyVerifyStatus(failure(401).httpStatus))
        assertEquals(VerifyOutcomeClass.Terminal, classifyVerifyStatus(failure(403).httpStatus))
        val owned = failure(409, "provider_owned")
        assertEquals(VerifyOutcomeClass.Terminal, classifyVerifyStatus(owned.httpStatus)); assertEquals("provider_owned", owned.serverError)
        val otherApp = failure(409, "token_owned_by_other_app")
        assertEquals(VerifyOutcomeClass.Terminal, classifyVerifyStatus(otherApp.httpStatus)); assertEquals("token_owned_by_other_app", otherApp.serverError)
        val creds = failure(422, "store_credentials_missing")
        assertEquals(VerifyOutcomeClass.Terminal, classifyVerifyStatus(creds.httpStatus)); assertEquals("store_credentials_missing", creds.serverError)
        assertEquals(VerifyOutcomeClass.Terminal, classifyVerifyStatus(failure(422, "purchase_not_completed").httpStatus))
        assertEquals(VerifyOutcomeClass.Retryable, classifyVerifyStatus(failure(429).httpStatus))
        assertEquals(VerifyOutcomeClass.Retryable, classifyVerifyStatus(failure(500).httpStatus))
        val offline = failure(null)
        assertNull(offline.httpStatus)
        assertEquals(VerifyOutcomeClass.Retryable, classifyVerifyStatus(offline.httpStatus))
        assertEquals(VerifyOutcomeClass.Terminal, classifyVerifyStatus(404))
        assertEquals("verificationFailed", billingErrorType(creds))
    }

    @Test fun `restore - network error is NetworkError, non-2xx is ServerError`() = runBlocking {
        val tx = listOf(RestoreTransaction("t", "p", "inapp"))
        try { verifier(ApiResult(null, null, null)).restore(tx); fail() } catch (e: BillingError.NetworkError) { }
        try { verifier(ApiResult(503, "{}", "store_unavailable")).restore(tx); fail() } catch (e: BillingError.ServerError) { }
        try { verifier(ApiResult(422, "{}", "store_credentials_missing")).restore(tx); fail() } catch (e: BillingError.ServerError) { }
        val items = verifier(ApiResult(200, JSONObject().put("data", org.json.JSONArray().put(JSONObject(dto).getJSONObject("data"))).toString(), null)).restore(tx)
        assertEquals(listOf("monthly_sub"), items.map { it.entitlement.productId })
        assertEquals("subs", items.single().productType)
    }

    @Test fun `decodeErrorCode reads error_code, a string error, and error dot code`() {
        assertEquals("x", decodeErrorCode("""{"error_code":"x"}"""))
        assertEquals("x", decodeErrorCode("""{"error":"x"}"""))
        assertEquals("x", decodeErrorCode("""{"error":{"code":"x","message":"m"}}"""))
        assertNull(decodeErrorCode("not json"))
    }
}
