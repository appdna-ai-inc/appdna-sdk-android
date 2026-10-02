package ai.appdna.sdk

import ai.appdna.sdk.events.BatchSizeCapGate
import ai.appdna.sdk.events.RuntimeSettings
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The bootstrap answer's `settings` reach the running SDK — the event queue's cap and flush interval, the
 * WorkManager uploader's cap and the config TTL — from a first bootstrap and from a recovered one; a host
 * option always wins, and a non-positive value is ignored. iOS `RuntimeSettingsTests`, same contract.
 *
 * NEGATIVE CONTROLS (build Mac, patched sources — status file round 33): `applyRuntimeSettings` not called
 * from `applyBootstrapAnswer` → every test but the defaults one fails; the bootstrap value beating the host
 * option → the explicit test fails; a non-positive bootstrap value accepted → the non-positive test fails.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RuntimeSettingsBootstrapTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private var server: TestHttpServer? = null
    private val plan = Collections.synchronizedMap(mutableMapOf<Int, () -> Pair<Int, String>?>())
    private val bootstraps = AtomicInteger(0)

    @Before fun setUp() {
        TestHttpServer.settleIoDispatcher()
        AppDNA.lastInitError = null
        AppDNA.bootstrapRetryBackoffForTest = { 200L }
        AppDNA.bootstrapRetryOnlineForTest = { true }
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
        BatchSizeCapGate.set(ctx, null)
        server?.close()
    }

    private fun body(settings: JSONObject) = 200 to JSONObject()
        .put("orgId", "org_rt").put("appId", "app_rt")
        .put("firestorePath", "orgs/org_rt/apps/app_rt").put("settings", settings).toString()

    private fun await(what: String, seconds: Long = 30, cond: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (!cond()) {
            if (System.nanoTime() > deadline) throw AssertionError("timed out waiting for: $what — ${TestHttpServer.workerDiagnostics()}")
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    private fun configureAndWait(options: AppDNAOptions = AppDNAOptions()) {
        AppDNA.configure(ctx, "adn_test_runtime", Environment.SANDBOX, options)
        await("bootstrap applied") { AppDNA.bootstrapOrgIdForTest() == "org_rt" }
    }

    private fun assertApplied(flush: Long, cap: Int?, ttl: Long) {
        assertEquals(RuntimeSettings.Resolved(flush, cap, ttl), AppDNA.runtimeSettingsForTest)
        val q = AppDNA.eventQueueForTest!!
        assertEquals("queue cap", cap, q.batchSizeCapForTesting)
        assertEquals("queue interval", flush, q.flushIntervalForTesting)
        assertEquals("worker cap", cap, BatchSizeCapGate.cap(ctx))
        assertEquals("config TTL", ttl, AppDNA.remoteConfigManagerForTest!!.currentConfigTTL)
    }

    @Test fun theBootstrapSettingsApplyWhenTheHostSetNone() {
        plan[0] = { body(JSONObject().put("flushInterval", 45).put("batchSize", 7).put("configTTL", 900)) }
        configureAndWait()
        assertApplied(45L, 7, 900L)
    }

    @Test fun aHostOptionBeatsTheBootstrapValue() {
        plan[0] = { body(JSONObject().put("flushInterval", 45).put("batchSize", 7).put("configTTL", 900)) }
        configureAndWait(AppDNAOptions(flushInterval = 12L, batchSize = 4, configTTL = 120L))
        assertApplied(12L, 4, 120L)
    }

    @Test fun nonPositiveBootstrapValuesAreIgnored() {
        plan[0] = { body(JSONObject().put("flushInterval", 0).put("batchSize", 0).put("configTTL", -1)) }
        configureAndWait()
        assertApplied(30L, null, 3600L)
    }

    /** The bootstrap the server sends today (30 / 100 / 3600) changes nothing a device does. */
    @Test fun theServersCurrentValuesAreTheDefaults() {
        plan[0] = { body(JSONObject().put("flushInterval", 30).put("batchSize", 100).put("configTTL", 3600)) }
        configureAndWait()
        assertApplied(30L, 100, 3600L)
        assertEquals(100, RuntimeSettings.effectiveBatchSize(100, 100))
        assertEquals(20, RuntimeSettings.effectiveBatchSize(20, 100))
    }

    @Test fun aRecoveredBootstrapAppliesItsSettings() {
        plan[1] = { body(JSONObject().put("flushInterval", 50).put("batchSize", 6).put("configTTL", 700)) }
        AppDNA.configure(ctx, "adn_test_runtime", Environment.SANDBOX)
        await("recovered") { AppDNA.bootstrapOrgIdForTest() == "org_rt" }
        assertApplied(50L, 6, 700L)
    }
}
