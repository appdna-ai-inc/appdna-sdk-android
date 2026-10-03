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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A 429 at configure holds the FIRST bootstrap retry back for its `Retry-After`: the recovery loop starts from
 * the configure-time outcome. Before, only a rate-limited RETRY's `Retry-After` was honoured, and the first retry
 * ran after the ordinary backoff (here 200 ms). iOS `RuntimeSettingsTests`
 * `testARateLimitedConfigureHoldsTheFirstRetryForItsRetryAfter`, same rule; fixture `resilience/bootstrap_recovery`
 * (`configure_failure_status`).
 *
 * NEGATIVE CONTROL (build Mac): `startBootstrapRecovery` without the configure-time outcome → the second
 * bootstrap request arrives ~200 ms after the first (2 requests at the 1.2 s check, not 1).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BootstrapRetryAfterAtConfigureTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private var server: TestHttpServer? = null
    private val bootstraps = AtomicInteger(0)

    @Before fun setUp() {
        TestHttpServer.settleIoDispatcher()
        AppDNA.lastInitError = null
        AppDNA.bootstrapRetryBackoffForTest = { 200L }
        AppDNA.bootstrapRetryOnlineForTest = { true }
        // Every answer carries `Retry-After: 2`; only the 429 reads it.
        val srv = TestHttpServer(responseHeaders = mapOf("Retry-After" to "2")) { path, _ ->
            if (path.startsWith("/api/v1/sdk/bootstrap")) {
                if (bootstraps.getAndIncrement() == 0) 429 to "{}"
                else 200 to JSONObject().put("orgId", "org_ra").put("appId", "app_ra")
                    .put("firestorePath", "orgs/org_ra/apps/app_ra").put("settings", JSONObject()).toString()
            } else 200 to "{}"
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
        server?.close()
        ctx.getSharedPreferences("appdna_dropped_events", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun idleFor(ms: Long) {
        val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ms)
        while (System.nanoTime() < end) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
    }

    private fun await(what: String, seconds: Long = 30, cond: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (!cond()) {
            if (System.nanoTime() > deadline) throw AssertionError("timed out waiting for: $what — ${TestHttpServer.workerDiagnostics()}")
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    @Test fun aRateLimitedConfigureHoldsTheFirstRetryForItsRetryAfter() {
        val start = System.nanoTime()
        AppDNA.configure(ctx, "adn_test_retry_after", Environment.SANDBOX)
        await("the configure-time bootstrap") { bootstraps.get() >= 1 }
        idleFor(1_200)
        assertEquals("the first retry did not wait for the configure-time Retry-After", 1, bootstraps.get())
        await("recovered") { AppDNA.bootstrapOrgIdForTest() == "org_ra" }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue("recovered after $elapsedMs ms, before the 2 s Retry-After", elapsedMs >= 2_000)
    }
}
