package ai.appdna.sdk.billing

import android.app.Activity
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * THE PURCHASE BUYS THE TRIAL THE PAYWALL STATED.
 *
 * Measured live on a first Play run: a paywall drew "1 week free trial" from Play's eligible
 * offers, and the CTA opened Play's sheet for the BASE PLAN — "Starting today 4,99 US$/month". The
 * purchase always picked the offer with no `offerId`, and Play applies no offer on its own: a
 * subscription purchase gets exactly the offer whose token it is handed.
 *
 * So these read the token the SDK actually hands Play (`launchBillingFlow`'s params), for the three
 * shapes Play returns: a trial this user may take, no trial (already used — Play stops returning
 * it), and an introductory PRICE, which is not a trial and must not be bought as one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrialOfferIsPurchasedTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private var sc: BillingScenario? = null
    private var pipe: BillingTestPipeline? = null

    @After fun tearDown() { sc?.teardown(); pipe?.close() }

    private fun activity(): Activity = Robolectric.buildActivity(Activity::class.java).setup().get()

    /** A subscription whose offers are exactly [offersJson]; Play never answers, so only the launch is read. */
    private fun launchedTokensFor(offersJson: String): List<String> {
        pipe = BillingTestPipeline(ctx, "trial-offer")
        val s = BillingScenario(ctx, JSONObject("""{
          "current_user_id":"user_a","first_identified_user_id":"user_a",
          "product_details":{"monthly_sub":{"price_micros":4990000,"currency":"USD","product_type":"subs","offers":$offersJson}}
        }""")).also { sc = it }
        s.setDelegate()
        s.fake.autoDeliver = false
        s.mgr.scope.launch { s.mgr.purchase(activity(), "monthly_sub") }
        s.idle(200)
        assertEquals("the purchase reached Play exactly once", 1, s.fake.launchCount)
        return FakeBillingClient.stringsOf(s.fake.lastFlowParams).filter { it.startsWith("tok_") }
    }

    @Test fun `an eligible free trial is the offer the purchase hands Play`() {
        val tokens = launchedTokensFor("""[
          {"offer_id":null,"token":"tok_base","phases":[{"micros":4990000,"period":"P1M"}]},
          {"offer_id":"freetrial-1w","token":"tok_trial","phases":[{"micros":0,"period":"P1W"},{"micros":4990000,"period":"P1M"}]}
        ]""")
        assertTrue("the trial offer was not purchased — the paywall promised a trial and the sheet charges from day one: $tokens", "tok_trial" in tokens)
        assertFalse("the base plan was purchased instead of the trial: $tokens", "tok_base" in tokens)
    }

    @Test fun `no trial offer returned (already used) - the base plan`() {
        // Play stops returning the trial to a user who consumed it; the base plan is what is left.
        val tokens = launchedTokensFor("""[
          {"offer_id":null,"token":"tok_base","phases":[{"micros":4990000,"period":"P1M"}]}
        ]""")
        assertEquals(listOf("tok_base"), tokens)
    }

    @Test fun `an introductory PRICE is not a trial - the base plan`() {
        // "£1 for the first month" is a different promise; the paywall states no trial for it, so the
        // purchase must not quietly buy it either.
        val tokens = launchedTokensFor("""[
          {"offer_id":null,"token":"tok_base","phases":[{"micros":4990000,"period":"P1M"}]},
          {"offer_id":"intro-1","token":"tok_intro","phases":[{"micros":1000000,"period":"P1M"},{"micros":4990000,"period":"P1M"}]}
        ]""")
        assertEquals(listOf("tok_base"), tokens)
    }
}
