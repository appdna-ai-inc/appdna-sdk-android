package ai.appdna.sdk

import ai.appdna.sdk.network.BASE_URL_OVERRIDE_META_KEY
import ai.appdna.sdk.network.TestHttpServer
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
 * A bootstrap that finishes after its configure has ended must change nothing (iOS
 * `StaleBootstrapEpochTests`, same contract).
 *
 * Cancelling the coroutine scope in `shutdown()` does not stop a bootstrap that already has its
 * response: the body has no suspension point after the network call. A `shutdown()` landing there —
 * here from inside the runtime-lock delegate the bootstrap calls synchronously — used to run on to
 * `isConfigured = true` on the shut-down SDK, which then rejected every later `configure()`. Across
 * `shutdown(); configure()` the same late bootstrap marked the NEW configure ready before ITS bootstrap
 * had answered.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StaleBootstrapEpochTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private var server: TestHttpServer? = null

    /** Bootstrap answers, by arrival index; a bootstrap without one is held until it is set. */
    private val answers = Collections.synchronizedMap(mutableMapOf<Int, CountDownLatch>())
    private val bodies = Collections.synchronizedMap(mutableMapOf<Int, String>())
    private val bootstrapsSeen = AtomicInteger(0)

    @Before fun setUp() {
        resetRuntimeLock()
        TestHttpServer.settleIoDispatcher()
        val srv = TestHttpServer { path, _ ->
            if (path.startsWith("/api/v1/sdk/bootstrap")) {
                val index = bootstrapsSeen.getAndIncrement()
                val gate = answers.getOrPut(index) { CountDownLatch(1) }
                gate.await(60, TimeUnit.SECONDS)
                200 to (bodies[index] ?: "{}")
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
        AppDNA.setLifecycleDelegate(null)
        synchronized(answers) { answers.values.forEach { it.countDown() } }
        AppDNA.shutdown()
        server?.close()
        resetRuntimeLock()
    }

    private fun resetRuntimeLock() {
        val f = AppDNA::class.java.getDeclaredField("runtimeLock")
        f.isAccessible = true
        f.set(null, null)
    }

    private fun bootstrapBody(orgId: String, locked: Boolean = false): String =
        org.json.JSONObject().apply {
            put("orgId", orgId)
            put("appId", "app_epoch")
            put("firestorePath", "orgs/$orgId/apps/app_epoch")
            if (locked) {
                put("runtime_lock", org.json.JSONObject().apply {
                    put("reason", "manual_admin")
                    put("locked_at", "2026-10-01T00:00:00Z")
                })
            }
        }.toString()

    private fun answer(index: Int, body: String) {
        bodies[index] = body
        answers.getOrPut(index) { CountDownLatch(1) }.countDown()
    }

    private fun await(what: String, seconds: Long = 60, cond: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (!cond()) {
            if (System.nanoTime() > deadline) throw AssertionError("timed out waiting for: $what — ${TestHttpServer.workerDiagnostics()}")
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    private fun settled(): Int = AppDNA.bootstrapOutcomesForTest().let { it.first + it.second }

    /** A delegate that parks the bootstrap inside `onSdkRuntimeLocked` until the test lets it go. */
    private class ParkingDelegate : ai.appdna.sdk.generated.AppDNALifecycleDelegate {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        override fun onSdkRuntimeLocked(reason: String, lockedAt: String) {
            entered.countDown()
            release.await(60, TimeUnit.SECONDS)
        }
    }

    @Test fun `a bootstrap that completes after shutdown leaves the SDK shut down and re-configurable`() {
        val parking = ParkingDelegate()
        AppDNA.setLifecycleDelegate(parking)
        val staleOrg = "org_stale_${System.nanoTime()}"
        answer(0, bootstrapBody(staleOrg, locked = true))

        AppDNA.configure(ctx, "adn_test_epoch_shutdown", Environment.SANDBOX)
        assertTrue("the bootstrap reached the runtime-lock delegate", parking.entered.await(60, TimeUnit.SECONDS))
        assertFalse(AppDNA.isReadyForTest())

        AppDNA.shutdown()
        val readyFired = AtomicInteger(0)
        AppDNA.onReady { readyFired.incrementAndGet() }
        val appliedBefore = AppDNA.bootstrapOutcomesForTest().first
        val before = settled()

        parking.release.countDown()
        await("the late bootstrap result was handled") { settled() > before }
        repeat(20) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5) }

        assertEquals("a bootstrap that finished after shutdown() was applied",
            appliedBefore, AppDNA.bootstrapOutcomesForTest().first)
        assertFalse("shutdown() then a late bootstrap left the SDK ready", AppDNA.isReadyForTest())
        assertEquals("onReady fired for a bootstrap that finished after shutdown()", 0, readyFired.get())
        assertNotEquals(staleOrg, AppDNA.bootstrapOrgIdForTest())

        // The SDK is still usable: the next configure is accepted and comes up with ITS bootstrap.
        AppDNA.setLifecycleDelegate(null)
        val freshOrg = "org_fresh_${System.nanoTime()}"
        answer(1, bootstrapBody(freshOrg))
        AppDNA.configure(ctx, "adn_test_epoch_shutdown", Environment.SANDBOX)
        await("the re-configure reached ready") { readyFired.get() == 1 }
        assertTrue(AppDNA.isReadyForTest())
        assertEquals(freshOrg, AppDNA.bootstrapOrgIdForTest())
    }

    @Test fun `a stale bootstrap finishing during the re-configure neither readies nor overwrites it`() {
        val parking = ParkingDelegate()
        AppDNA.setLifecycleDelegate(parking)
        val oldOrg = "org_old_${System.nanoTime()}"
        val newOrg = "org_new_${System.nanoTime()}"
        answer(0, bootstrapBody(oldOrg, locked = true))

        AppDNA.configure(ctx, "adn_test_epoch_reconfigure", Environment.SANDBOX)
        assertTrue("the bootstrap reached the runtime-lock delegate", parking.entered.await(60, TimeUnit.SECONDS))

        AppDNA.shutdown()
        AppDNA.configure(ctx, "adn_test_epoch_reconfigure", Environment.SANDBOX)
        await("the second bootstrap reached the server (held)") { bootstrapsSeen.get() == 2 }
        val readyFired = AtomicInteger(0)
        AppDNA.onReady { readyFired.incrementAndGet() }
        val appliedBefore = AppDNA.bootstrapOutcomesForTest().first
        val before = settled()

        // The OLD bootstrap finishes while the new one is still in flight.
        parking.release.countDown()
        await("the stale bootstrap result was handled") { settled() > before }
        repeat(20) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5) }

        assertEquals("the first configure's bootstrap was applied to the second configure",
            appliedBefore, AppDNA.bootstrapOutcomesForTest().first)
        assertFalse("a stale bootstrap marked the re-configured SDK ready before its own bootstrap answered",
            AppDNA.isReadyForTest())
        assertEquals("onReady fired for the stale bootstrap", 0, readyFired.get())
        assertNotEquals(oldOrg, AppDNA.bootstrapOrgIdForTest())

        // Only the new configure's bootstrap applies.
        answer(1, bootstrapBody(newOrg))
        await("the re-configure reached ready") { readyFired.get() == 1 }
        assertEquals(appliedBefore + 1, AppDNA.bootstrapOutcomesForTest().first)
        assertEquals(newOrg, AppDNA.bootstrapOrgIdForTest())
        assertTrue(AppDNA.isReadyForTest())
    }

    @Test fun `shutdown while the bootstrap request is in flight drops its answer`() {
        val staleOrg = "org_inflight_${System.nanoTime()}"
        AppDNA.configure(ctx, "adn_test_epoch_inflight", Environment.SANDBOX)
        await("the bootstrap reached the server (held)") { bootstrapsSeen.get() == 1 }

        AppDNA.shutdown()
        val readyFired = AtomicInteger(0)
        AppDNA.onReady { readyFired.incrementAndGet() }
        answer(0, bootstrapBody(staleOrg))
        // The answered request has to be read back by the client before its result can matter.
        repeat(100) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }

        assertFalse("shutdown() then a late bootstrap left the SDK ready", AppDNA.isReadyForTest())
        assertEquals(0, readyFired.get())
        assertNotEquals(staleOrg, AppDNA.bootstrapOrgIdForTest())
    }
}
