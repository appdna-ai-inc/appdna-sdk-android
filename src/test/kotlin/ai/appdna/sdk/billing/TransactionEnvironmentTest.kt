package ai.appdna.sdk.billing

import ai.appdna.sdk.AppDNA
import ai.appdna.sdk.TransactionInfo
import android.app.Activity
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `TransactionInfo.environment` is the server-verified environment (the verify reply's `environment`:
 * Play `testPurchase` / `purchaseType == 0` → `"sandbox"`), on the value `AppDNA.billing.purchase`
 * returns, on the live `onPurchaseCompleted`, and on a queued delivery. It used to be `"production"`
 * everywhere, sandbox included. Unknown (an older server) stays `"production"`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TransactionEnvironmentTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private var sc: BillingScenario? = null
    private var pipe: BillingTestPipeline? = null

    @After fun tearDown() { sc?.teardown(); pipe?.close() }

    private fun setup(environment: String?, listener: Boolean) = JSONObject("""{
      "current_user_id":"user_a","first_identified_user_id":"user_a",
      "product_details":{"lifetime_unlock":{"price_micros":4990000,"currency":"USD","product_type":"inapp"}},
      "owned_purchases":[{"product_id":"lifetime_unlock","token":"tok_x","order_id":"GPA.x","acknowledged":false,"product_type":"inapp","purchase_state":"PURCHASED"}],
      ${if (listener) "\"listener_purchase_token\":\"tok_x\"," else ""}
      "verify_replies":{"tok_x":{"status":200,"consume":false,"expires_at":null,"is_trial":false
        ${if (environment != null) ",\"environment\":\"$environment\"" else ""}}}
    }""")

    private fun scenario(json: JSONObject): BillingScenario {
        pipe = BillingTestPipeline(ctx, "tx_env")
        return BillingScenario(ctx, json).also { sc = it }
    }

    private fun activity(): Activity = Robolectric.buildActivity(Activity::class.java).setup().get()

    /** NEGATIVE CONTROL: with `environment = "production"` back in `BillingModule.purchase`, this fails. */
    @Test fun `a sandbox purchase returns and delivers environment sandbox`() {
        val s = scenario(setup("sandbox", listener = true))
        s.setDelegate()
        val tx = s.drive { AppDNA.billing.purchase(activity(), "lifetime_unlock") }.getOrThrow()
        assertEquals("sandbox", tx.environment)
        assertEquals(listOf("sandbox"), s.deliveredTransactions.map { it.environment })
    }

    @Test fun `a verify reply without environment keeps production`() {
        val s = scenario(setup(null, listener = true))
        s.setDelegate()
        val tx = s.drive { AppDNA.billing.purchase(activity(), "lifetime_unlock") }.getOrThrow()
        assertEquals(TransactionInfo.DEFAULT_ENVIRONMENT, tx.environment)
        assertEquals(listOf("production"), s.deliveredTransactions.map { it.environment })
    }

    /**
     * A purchase reported with no live caller (ON_START) is queued; the queued entry keeps the
     * environment through the ledger and the drain delivers it. NEGATIVE CONTROL: without
     * `environment` in the ledger's delivery JSON, the drained delivery reads "production".
     */
    @Test fun `a queued delivery keeps the sandbox environment through the ledger`() {
        val s = scenario(setup("sandbox", listener = false))
        s.setDelegate()
        s.drive { s.mgr.reconcileSubscriptionState() }
        s.idle(200)
        assertEquals(listOf("sandbox"), s.deliveredTransactions.map { it.environment })
    }

    @Test fun `the ledger round-trips a delivery's environment`() {
        val ledger = PurchaseLedger(null)
        val d = PendingDelivery("t", "GPA.t", "p", 1L, 1, null, System.currentTimeMillis(), environment = "sandbox")
        ledger.write(deliveries = listOf(d))
        assertEquals("sandbox", ledger.deliveries().single().environment)
        ledger.write(deliveries = listOf(d.copy(environment = null)))
        assertEquals(null, ledger.deliveries().single().environment)
    }
}
