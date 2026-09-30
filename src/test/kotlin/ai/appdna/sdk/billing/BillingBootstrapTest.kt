package ai.appdna.sdk.billing

import ai.appdna.sdk.AppDNA
import ai.appdna.sdk.BillingProvider
import ai.appdna.sdk.Environment
import ai.appdna.sdk.network.BASE_URL_OVERRIDE_META_KEY
import ai.appdna.sdk.network.TestHttpServer
import ai.appdna.sdk.storage.LocalStorage
import android.app.Activity
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * SPEC-497 §3.2 rule 3 (R33, R65–R68, R72) — billing across the REAL `AppDNA.configure()` bootstrap,
 * with the SDK's HTTP pointed at a loopback [TestHttpServer] through the §3.11 test-only base-URL
 * override (a debuggable sandbox build), so `/sdk/bootstrap` can be answered — or held pending.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BillingBootstrapTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private var server: TestHttpServer? = null
    private var pipe: BillingTestPipeline? = null

    @After fun tearDown() {
        AppDNA.shutdown()
        pipe?.close()
        server?.close()
        LocalStorage(ctx).remove("user_id")
    }

    private fun pointSdkAt(url: String) {
        val pkg = shadowOf(ctx.packageManager).getInternalMutablePackageInfo(ctx.packageName)
        pkg.applicationInfo!!.flags = pkg.applicationInfo!!.flags or ApplicationInfo.FLAG_DEBUGGABLE
        pkg.applicationInfo!!.metaData = Bundle().apply { putString(BASE_URL_OVERRIDE_META_KEY, url) }
    }

    /** Idle Main and wait (real time — the bootstrap runs on Dispatchers.IO) until [cond] holds. */
    private fun await(what: String, seconds: Long = 60, cond: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (!cond()) {
            if (System.nanoTime() > deadline) throw AssertionError("timed out waiting for: $what — ${TestHttpServer.workerDiagnostics()}")
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    private val bootstrapReply = """{"orgId":"org_t","appId":"app_t","firestorePath":"orgs/org_t/apps/app_t"}"""

    @Test fun `a cold configure with a persisted identity registers the entitlement listener once the bootstrap resolves`() {
        TestHttpServer.settleIoDispatcher()
        val srv = TestHttpServer { path, _ ->
            if (path.startsWith("/api/v1/sdk/bootstrap")) 200 to bootstrapReply else 200 to "{}"
        }.also { server = it }
        LocalStorage(ctx).setString("user_id", "user_cold") // the identity a previous session persisted
        pointSdkAt(srv.baseUrl)

        AppDNA.configure(ctx, "adn_test_cold_listener", Environment.SANDBOX)
        await("the entitlement listener registration") {
            (AppDNA.billing.manager?.entitlementCache?.observeStarts ?: 0) >= 1
        }
        // Give any second (duplicate) registration the chance to happen.
        repeat(50) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
        val cache = AppDNA.billing.manager!!.entitlementCache
        assertEquals("registered exactly once", 1, cache.observeStarts)
        assertEquals("orgs/org_t/apps/app_t/users/user_cold/entitlements/current", cache.lastObservedPath)
        assertTrue(srv.requests.any { it.first.contains("/api/v1/sdk/bootstrap") })
    }

    @Test fun `a purchase while the bootstrap is still pending uses storeKit2 once billing has its manager`() {
        // /sdk/bootstrap never answers: configure() has returned, the bootstrap is in flight.
        TestHttpServer.settleIoDispatcher()
        val srv = TestHttpServer { path, _ ->
            if (path.startsWith("/api/v1/sdk/bootstrap")) null else 200 to "{}"
        }.also { server = it }
        pointSdkAt(srv.baseUrl)

        AppDNA.configure(ctx, "adn_test_mid_bootstrap", Environment.SANDBOX)
        // R68: wait for billing's manager (built at the start of the launched bootstrap), not a race.
        await("billing's manager") { AppDNA.billing.manager != null }
        assertTrue("the bootstrap is still pending", srv.awaitRequest(30))
        assertEquals(BillingOwnership.policy(BillingProvider.StoreKit2), AppDNA.billing.ownershipPolicy)

        pipe = BillingTestPipeline(ctx, "mid_bootstrap")
        var error: Throwable? = null
        MainScope().launch {
            try {
                AppDNA.billing.purchase(Robolectric.buildActivity(Activity::class.java).setup().get(), "lifetime_unlock")
            } catch (t: Throwable) {
                error = t
            }
        }
        repeat(20) { shadowOf(Looper.getMainLooper()).idle() }
        // The storeKit2 path: the manager started the purchase (one purchase_started) — not the
        // "not configured yet" `unknown` failure and not providerNotAvailable.
        assertEquals(1, pipe!!.count("purchase_started"))
        assertTrue(pipe!!.events().none { it.first == "purchase_failed" })
        assertTrue("no error yet (the Play connection wait is in flight): $error", error == null)
    }

    @Test fun `after shutdown a purchase fails unknown with the not-configured message`() {
        TestHttpServer.settleIoDispatcher()
        val srv = TestHttpServer { path, _ ->
            if (path.startsWith("/api/v1/sdk/bootstrap")) 200 to bootstrapReply else 200 to "{}"
        }.also { server = it }
        pointSdkAt(srv.baseUrl)
        AppDNA.configure(ctx, "adn_test_after_shutdown", Environment.SANDBOX)
        await("billing's manager") { AppDNA.billing.manager != null }

        AppDNA.shutdown()
        assertEquals(BillingOwnershipPolicy.NotConfigured, AppDNA.billing.ownershipPolicy)
        runBlocking {
            try {
                AppDNA.billing.purchase(Robolectric.buildActivity(Activity::class.java).setup().get(), "lifetime_unlock")
                fail("a purchase after shutdown must throw")
            } catch (e: IllegalStateException) {
                assertEquals(BillingOwnershipPolicy.NOT_CONFIGURED_MESSAGE, e.message)
                assertEquals("unknown", billingErrorType(e))
            }
            try {
                AppDNA.billing.restorePurchases()
                fail("a restore after shutdown must throw")
            } catch (e: IllegalStateException) {
                assertEquals("unknown", billingErrorType(e))
            }
        }
    }
}
