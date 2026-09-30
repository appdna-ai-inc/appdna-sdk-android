package ai.appdna.sdk.billing

import ai.appdna.sdk.AppDNA
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** SPEC-497 D-R40-1 — the durable delivery queue and its drain (Q1–Q6), Android. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeliveryQueueTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private var sc: BillingScenario? = null

    @After fun tearDown() { sc?.teardown() }

    private fun scenario(json: String) = BillingScenario(ctx, JSONObject(json)).also { sc = it }
    private fun delivered(s: BillingScenario) = s.delegateLog.count { it.first == "onPurchaseCompleted" }
    private val oneUntagged = """{"pending_deliveries":[{"token":"t1","ownerToken":null,"productId":"p1"}]}"""

    @Test fun `setDelegate(null) does not drain`() {
        val s = scenario(oneUntagged)
        AppDNA.billing.setDelegate(null); s.idle()
        assertEquals(listOf("t1"), s.queueTokens())
    }

    @Test fun `a deliversPurchases=false delegate is never called by the drain`() {
        val s = scenario(oneUntagged)
        s.setDelegate(delivers = false); s.idle()
        s.mgr.triggerDrain(); s.idle()
        assertEquals(0, delivered(s))
        assertEquals(listOf("t1"), s.queueTokens())
    }

    @Test fun `the delegate throws - the entry stays queued`() {
        val s = scenario(oneUntagged)
        s.delegateThrows = true
        s.setDelegate(); s.idle()
        assertEquals(1, delivered(s))
        assertEquals(listOf("t1"), s.queueTokens())
    }

    @Test fun `an identity mismatch is not delivered, an untagged entry goes to the first identified user`() {
        val tokenA = AppAccountTokenResolver.token("user_a").toString()
        val s = scenario("""{"current_user_id":"user_b","first_identified_user_id":"user_b",
          "pending_deliveries":[{"token":"ta","ownerToken":"$tokenA","productId":"p"},{"token":"tu","ownerToken":null,"productId":"p"}]}""")
        s.setDelegate(); s.idle()
        assertEquals(listOf("ta"), s.queueTokens())
        s.currentUser = "user_a"
        s.mgr.triggerDrain(); s.idle()
        assertEquals(emptyList<String>(), s.queueTokens())
    }

    @Test fun `two concurrent drains deliver an entry once`() {
        val s = scenario(oneUntagged)
        AppDNA.billing.setDelegate(s.delegate, false)
        AppDNA.billing.assignBillingDelegate(s.delegate, delivers = true)
        s.mgr.scope.launch { s.mgr.completion.drainDeliveries() }
        s.mgr.scope.launch { s.mgr.completion.drainDeliveries() }
        s.idle()
        assertEquals(1, delivered(s))
    }

    @Test fun `delegate set before billing is ready - delivered once the drain is ready (trigger iv)`() {
        val s = scenario(oneUntagged)
        s.mgr.completion.drainReady = false
        s.setDelegate(); s.idle()
        assertEquals(0, delivered(s))
        s.mgr.completion.drainReady = true
        s.mgr.triggerDrain(); s.idle()
        assertEquals(1, delivered(s))
    }

    @Test fun `the queue is capped at 100 and purges entries older than 30 days`() {
        val s = scenario("{}")
        val now = System.currentTimeMillis()
        val many = (0 until 105).map { PendingDelivery("t$it", "t$it", "p", 0, 1, null, now) }
        s.ledger.write(deliveries = many)
        assertEquals(100, s.queueTokens().size)
        assertEquals("t5", s.queueTokens().first())
        val old = PendingDelivery("old", "old", "p", 0, 1, null, now - PurchaseLedger.DELIVERY_TTL_MS - 1)
        s.ledger.write(deliveries = listOf(old))
        assertEquals(emptyList<String>(), s.queueTokens())
    }

    @Test fun `a PENDING purchase approved with no continuation is queued once and delivered once on setDelegate`() {
        val s = scenario("""{"product_details":{"p":{"price_micros":1000000,"currency":"USD","product_type":"inapp"}},
           "verify_replies":{"tok_pa":{"status":200,"consume":false,"expires_at":null,"is_trial":false}}}""")
        s.fake.deliver(FakeBillingClient.purchase("p", "tok_pa"), "inapp")
        s.idle()
        assertEquals(listOf("tok_pa"), s.queueTokens())
        s.setDelegate(); s.idle()
        assertEquals(1, delivered(s))
        assertEquals(emptyList<String>(), s.queueTokens())
    }
}
