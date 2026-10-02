package ai.appdna.sdk

import ai.appdna.sdk.network.BASE_URL_OVERRIDE_META_KEY
import ai.appdna.sdk.network.TestHttpServer
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A failed bootstrap no longer degrades the whole session (iOS `BootstrapRecoveryTests`, same contract,
 * and the shared fixture `resilience/bootstrap_recovery`).
 *
 * The SDK retries it — when the network comes back, on foreground, and after a bounded backoff — and
 * applies the first answer exactly as a first-time success: org / app ids, the Firestore path. `onReady`
 * fires once (at the failure), `lastInitError` holds the failed bootstrap until it recovers and is null
 * after, and a retry whose configure has ended applies nothing.
 *
 * NEGATIVE CONTROLS (build Mac, patched sources — status file round 30):
 *  - no `startBootstrapRecovery` call → the recovery tests fail (org id stays null);
 *  - the retry loop ignoring `isOnline()` → the offline test fails (attempts made offline);
 *  - the loop run on a scope `shutdown()` does not cancel, not stopped by `shutdown()`, AND the epoch checks
 *    removed from the retry and from `applyBootstrapAnswer` → the stale-retry test fails (the old configure's
 *    org replaces the new one's). The three guards are redundant: with any one left the test passes (a
 *    stopped or cancelled loop's held request returns into a cancelled coroutine).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BootstrapRecoveryTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private var server: TestHttpServer? = null

    /** What each bootstrap request gets, by arrival index; default: 401 (a failed bootstrap, not retried within the attempt). */
    private val plan = Collections.synchronizedMap(mutableMapOf<Int, () -> Pair<Int, String>?>())
    private val bootstraps = AtomicInteger(0)
    @Volatile private var online = true

    @Before fun setUp() {
        TestHttpServer.settleIoDispatcher()
        AppDNA.lastInitError = null
        AppDNA.bootstrapRetryBackoffForTest = { 200L }
        AppDNA.bootstrapRetryOnlineForTest = { online }
        val srv = TestHttpServer { path, _ ->
            if (path.startsWith("/api/v1/sdk/bootstrap")) {
                val i = bootstraps.getAndIncrement()
                plan[i]?.invoke() ?: (401 to "{}")
            } else {
                401 to "{}"
            }
        }
        server = srv
        val pkg = shadowOf(ctx.packageManager).getInternalMutablePackageInfo(ctx.packageName)
        pkg.applicationInfo!!.flags = pkg.applicationInfo!!.flags or ApplicationInfo.FLAG_DEBUGGABLE
        pkg.applicationInfo!!.metaData = Bundle().apply { putString(BASE_URL_OVERRIDE_META_KEY, srv.baseUrl) }
    }

    @After fun tearDown() {
        AppDNA.shutdown()
        AppDNA.bootstrapRetryBackoffForTest = null
        AppDNA.bootstrapRetryOnlineForTest = null
        AppDNA.lastInitError = null
        AppDNA.setInitDelegate(null)
        server?.close()
    }

    private fun body(org: String) = 200 to JSONObject()
        .put("orgId", org).put("appId", "app_recovery")
        .put("firestorePath", "orgs/$org/apps/app_recovery").toString()

    private fun await(what: String, seconds: Long = 30, cond: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (!cond()) {
            if (System.nanoTime() > deadline) throw AssertionError("timed out waiting for: $what — ${TestHttpServer.workerDiagnostics()}")
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    private fun idle(ms: Long) {
        val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ms)
        while (System.nanoTime() < end) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
    }

    /** Requests the client makes per failed bootstrap attempt (`getWithRetry` does not retry a 401). */
    private val perAttempt = 1

    @Test fun aFailedBootstrapIsRetriedAndAppliedLikeAFirstSuccess() {
        val ready = AtomicInteger(0)
        val degraded = Collections.synchronizedList(mutableListOf<Throwable>())
        AppDNA.setInitDelegate(object : AppDNAInitDelegate {
            override fun onInitDegraded(reason: Throwable) { degraded += reason }
        })
        // The first attempt (3 requests) fails; the first retry answers.
        plan[perAttempt] = { body("org_recovered") }
        val recoveredBefore = AppDNA.bootstrapsRecoveredForTest()

        AppDNA.configure(ctx, "adn_test_recovery", Environment.SANDBOX)
        AppDNA.onReady { ready.incrementAndGet() }
        await("ready after the failed bootstrap") { ready.get() == 1 }
        await("the failed bootstrap was reported") { degraded.any { it is AppDNAInitError.BootstrapFailed } }

        await("the retry was applied") { AppDNA.bootstrapOrgIdForTest() == "org_recovered" }
        idle(300)
        assertEquals("onReady fired again on recovery", 1, ready.get())
        assertNull("lastInitError still holds the bootstrap failure after it recovered", AppDNA.lastInitError)
        assertEquals("one recovery", recoveredBefore + 1, AppDNA.bootstrapsRecoveredForTest())
        assertEquals("the app id of the recovered bootstrap", "app_recovery", AppDNA.getCurrentAppId())
        val attemptsAfter = bootstraps.get()
        idle(800)
        assertEquals("the SDK kept retrying after it recovered", attemptsAfter, bootstraps.get())
    }

    @Test fun offlineTheSdkWaitsForTheNetworkInsteadOfUsingUpItsAttempts() {
        online = false
        plan[perAttempt] = { body("org_back_online") }
        val ready = CountDownLatch(1)
        AppDNA.configure(ctx, "adn_test_offline", Environment.SANDBOX)
        AppDNA.onReady { ready.countDown() }
        await("ready") { ready.count == 0L }
        idle(1_500) // 7 backoff periods
        assertEquals("a retry was made while offline", perAttempt, bootstraps.get())
        assertEquals(0, AppDNA.bootstrapRecoveryForTest()?.attempts)

        online = true
        AppDNA.bootstrapRecoveryForTest()!!.trigger() // the connectivity monitor's "network is back"
        await("the retry after the network came back was applied") { AppDNA.bootstrapOrgIdForTest() == "org_back_online" }
    }

    @Test fun aRetryIsBoundedWhenTheServerKeepsFailing() {
        val ready = CountDownLatch(1)
        AppDNA.configure(ctx, "adn_test_bounded", Environment.SANDBOX)
        AppDNA.onReady { ready.countDown() }
        await("ready") { ready.count == 0L }
        val max = BootstrapRecovery.DEFAULT_MAX_ATTEMPTS
        await("all retries made", seconds = 60) { bootstraps.get() == perAttempt * (1 + max) }
        idle(1_000)
        assertEquals("more retries than the bound", perAttempt * (1 + max), bootstraps.get())
        assertTrue(AppDNA.lastInitError is AppDNAInitError.BootstrapFailed)
    }

    @Test fun aRetryForAConfigureThatEndedIsDropped() {
        // The first retry of configure #1 is held at the server until the second configure is ready.
        val hold = CountDownLatch(1)
        plan[perAttempt] = { hold.await(30, TimeUnit.SECONDS); body("org_stale_retry") }
        val ready = AtomicInteger(0)
        AppDNA.configure(ctx, "adn_test_stale_retry", Environment.SANDBOX)
        AppDNA.onReady { ready.incrementAndGet() }
        await("the retry reached the server (held)") { bootstraps.get() == perAttempt + 1 }

        AppDNA.shutdown()
        plan[perAttempt + 1] = { body("org_second_configure") }
        AppDNA.configure(ctx, "adn_test_stale_retry", Environment.SANDBOX)
        AppDNA.onReady { ready.incrementAndGet() }
        await("the second configure is ready") { AppDNA.bootstrapOrgIdForTest() == "org_second_configure" }

        hold.countDown()
        idle(1_000)
        assertEquals("a retry of the ended configure overwrote the new one", "org_second_configure", AppDNA.bootstrapOrgIdForTest())
    }
}
