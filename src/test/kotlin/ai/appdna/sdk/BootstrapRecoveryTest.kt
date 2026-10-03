package ai.appdna.sdk

import ai.appdna.sdk.network.ApiClient
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
import org.junit.Assert.assertFalse
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
 * NEGATIVE CONTROLS (build Mac, patched sources):
 *  - no `startBootstrapRecovery` call → the recovery tests fail (org id stays null);
 *  - the retry loop ignoring `isOnline()` → the offline test fails (attempts made offline);
 *  - the loop run on a scope `shutdown()` does not cancel, not stopped by `shutdown()`, AND the epoch checks
 *    removed from the retry and from `applyBootstrapAnswer` → the stale-retry test fails (the old configure's
 *    org replaces the new one's). The three guards are redundant: with any one left the test passes (a
 *    stopped or cancelled loop's held request returns into a cancelled coroutine).
 *
 *  - the `stopped` check removed from `start()` → the start-after-stop test fails (a loop is launched; it makes
 *    no attempt even then — the closed trigger channel ends it: two guards);
 *  - `end()` not removing the foreground observer → the observer tests fail;
 *  - `outcomeForFailure` answering Retry for 401 → the 401 tests fail (retries continue);
 *  - RetryAfter treated as Retry → the Retry-After test fails (the next attempt comes too early);
 *  - the trigger delay removed → the trigger-delay test fails.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BootstrapRecoveryTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private var server: TestHttpServer? = null

    /** What each bootstrap request gets, by arrival index; default: 500 (a failed bootstrap, retried within the attempt). */
    private val plan = Collections.synchronizedMap(mutableMapOf<Int, () -> Pair<Int, String>?>())
    private val bootstraps = AtomicInteger(0)
    private val arrivals = Collections.synchronizedMap(mutableMapOf<Int, Long>())
    @Volatile private var online = true

    @Before fun setUp() {
        TestHttpServer.settleIoDispatcher()
        AppDNA.lastInitError = null
        AppDNA.bootstrapRetryBackoffForTest = { 200L }
        AppDNA.bootstrapRetryOnlineForTest = { online }
        ApiClient.getRetryBaseDelayMsForTest = 5L
        val srv = TestHttpServer { path, _ ->
            if (path.startsWith("/api/v1/sdk/bootstrap")) {
                val i = bootstraps.getAndIncrement()
                arrivals[i] = System.nanoTime()
                plan[i]?.invoke() ?: (500 to "{}")
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
        ApiClient.getRetryBaseDelayMsForTest = null
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

    /** Requests the client makes per failed bootstrap attempt (`getWithRetryResult` tries a 5xx 3 times). */
    private val perAttempt = 3

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

    // MARK: -

    @Test fun aStartAfterStopStartsNothingAndObservesNothing() {
        val made = AtomicInteger(0)
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
        val recovery = BootstrapRecovery(isOnline = { true }, backoffMs = { 50L }, triggerDelayMaxMs = 0L)
        recovery.stop() // shutdown() between storing the loop and starting it
        recovery.start(scope) { made.incrementAndGet(); BootstrapRecovery.Outcome.Retry }
        assertFalse("start() after stop() launched a loop", recovery.wasLaunchedForTest)
        recovery.observeForeground()
        recovery.trigger()
        idle(500)
        assertEquals("a loop started after stop()", 0, made.get())
        assertFalse("a foreground observer for a loop that never runs", recovery.isObservingForeground)
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    @Test fun theLoopRemovesItsForegroundObserverWhenItGivesUpOrIsRefused() {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
        val ended = AtomicInteger(0)
        val gaveUp = BootstrapRecovery(isOnline = { true }, backoffMs = { 300L }, maxAttempts = 2, triggerDelayMaxMs = 0L)
        gaveUp.onEnded = { ended.incrementAndGet() }
        gaveUp.start(scope) { BootstrapRecovery.Outcome.Retry }
        gaveUp.observeForeground()
        await("the foreground observer was registered") { gaveUp.isObservingForeground || gaveUp.attempts == 2 }
        await("both attempts made") { gaveUp.attempts == 2 }
        await("the observer was removed after the last attempt") { !gaveUp.isObservingForeground && ended.get() == 1 }

        val made = AtomicInteger(0)
        val refused = BootstrapRecovery(isOnline = { true }, backoffMs = { 300L }, triggerDelayMaxMs = 0L)
        refused.start(scope) { made.incrementAndGet(); BootstrapRecovery.outcomeForFailure(401, null) }
        refused.observeForeground()
        await("the refused attempt") { made.get() == 1 }
        await("the observer was removed after the refusal") { !refused.isObservingForeground }
        idle(800)
        assertEquals("retries continued after a 401", 1, made.get())
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    @Test fun a401EndsTheRetries() {
        plan[perAttempt] = { 401 to "{}" }
        val ready = CountDownLatch(1)
        AppDNA.configure(ctx, "adn_test_refused_retry", Environment.SANDBOX)
        AppDNA.onReady { ready.countDown() }
        await("ready") { ready.count == 0L }
        await("the retry was refused") { bootstraps.get() == perAttempt + 1 }
        AppDNA.bootstrapRecoveryForTest()?.trigger()
        idle(1_500) // seven backoff periods
        assertEquals("the SDK kept retrying after the server refused the key", perAttempt + 1, bootstraps.get())
        assertTrue(AppDNA.lastInitError is AppDNAInitError.BootstrapFailed)
    }

    @Test fun a401AtConfigureStartsNoRetries() {
        plan[0] = { 401 to "{}" }
        val ready = CountDownLatch(1)
        AppDNA.configure(ctx, "adn_test_refused", Environment.SANDBOX)
        AppDNA.onReady { ready.countDown() }
        await("ready") { ready.count == 0L }
        idle(1_000)
        assertEquals("a refused key was retried", 1, bootstraps.get())
        assertNull("a retry loop for a refused key", AppDNA.bootstrapRecoveryForTest())
    }

    @Test fun aRateLimitedRetryWaitsForTheRetryAfter() {
        // The loop, driven with the outcome the client builds from a 429 + `Retry-After: 2`
        // (`ApiClient.GetResult.Failed(429, 2)` → `outcomeForFailure`; the client half is asserted below).
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
        val times = Collections.synchronizedList(mutableListOf<Long>())
        val recovery = BootstrapRecovery(isOnline = { true }, backoffMs = { 200L }, triggerDelayMaxMs = 0L)
        recovery.start(scope) {
            times += System.nanoTime()
            if (times.size == 1) BootstrapRecovery.outcomeForFailure(429, 2L) else BootstrapRecovery.Outcome.Done
        }
        await("the rate-limited attempt") { times.size == 1 }
        recovery.trigger() // a foreground does not shorten the server's wait
        await("the attempt after the Retry-After") { times.size == 2 }
        val waitedMs = (times[1] - times[0]) / 1_000_000
        assertTrue("the next attempt did not wait for the Retry-After (${waitedMs} ms)", waitedMs >= 1_900)
        recovery.stop()
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    @Test fun aTriggeredAttemptWaitsAShortRandomDelay() {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
        val started = AtomicInteger(0)
        val recovery = BootstrapRecovery(isOnline = { true }, backoffMs = { 3_600_000L }, random = { 0.5 }, triggerDelayMaxMs = 800L)
        recovery.start(scope) { started.incrementAndGet(); BootstrapRecovery.Outcome.Done }
        idle(100)
        val t0 = System.nanoTime()
        recovery.trigger()
        await("the triggered attempt") { started.get() == 1 }
        assertTrue("a triggered attempt started without its delay", (System.nanoTime() - t0) / 1_000_000 >= 350)
        recovery.stop()
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    @Test fun everyWaitIsJittered() {
        assertEquals(7_500L, BootstrapRecovery.jittered(10_000L, 0.0))
        assertEquals(10_000L, BootstrapRecovery.jittered(10_000L, 0.5))
        assertTrue(BootstrapRecovery.jittered(10_000L, 0.999_999) <= 12_500L)
        val samples = (0 until 50).map { BootstrapRecovery.jittered(10_000L, kotlin.random.Random.nextDouble()) }.toSet()
        assertTrue("the jitter is not random", samples.size > 10)
    }

    @Test fun aClientResultKeepsTheStatusAndTheRetryAfter() {
        // `getWithRetryResult` keeps what the recovery needs: a 401 as Failed(401) (which ends it).
        plan[0] = { 401 to "{}" }
        val got = kotlinx.coroutines.runBlocking {
            ApiClient("adn_test_result", server!!.baseUrl).getWithRetryResult("/api/v1/sdk/bootstrap")
        }
        assertEquals(ApiClient.GetResult.Failed(401), got)
        assertEquals(BootstrapRecovery.Outcome.Stop,
            BootstrapRecovery.outcomeForFailure((got as ApiClient.GetResult.Failed).status, got.retryAfterSeconds))

        // …and a 429 with its Retry-After (which holds the next attempt back).
        TestHttpServer(responseHeaders = mapOf("Retry-After" to "2")) { _, _ -> 429 to "{}" }.use { limited ->
            val r = kotlinx.coroutines.runBlocking {
                ApiClient("adn_test_result", limited.baseUrl).getWithRetryResult("/api/v1/sdk/bootstrap")
            }
            assertEquals(ApiClient.GetResult.Failed(429, 2L), r)
            assertEquals(BootstrapRecovery.Outcome.RetryAfter(2_000L), BootstrapRecovery.outcomeForFailure(429, 2L))
        }
    }
}
