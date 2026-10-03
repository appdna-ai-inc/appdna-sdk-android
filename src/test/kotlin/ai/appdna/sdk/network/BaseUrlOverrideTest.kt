package ai.appdna.sdk.network

import ai.appdna.sdk.AppDNA
import ai.appdna.sdk.Environment
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The test-only base-URL override. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BaseUrlOverrideTest {

    private val prod = "https://api.appdna.ai"

    private fun info(debuggable: Boolean) = ApplicationInfo().apply {
        flags = if (debuggable) ApplicationInfo.FLAG_DEBUGGABLE else 0
    }

    private fun meta(value: String?) = Bundle().apply { if (value != null) putString(BASE_URL_OVERRIDE_META_KEY, value) }

    @After
    fun tearDown() {
        AppDNA.shutdown()
        ai.appdna.sdk.background.EventUploadWorker.scheduleProbe = null
    }

    @Test
    fun theBackgroundUploadWorkerGetsTheOverriddenBase() {
        // The input the worker reads its host from.
        val data = ai.appdna.sdk.background.EventUploadWorker.inputData("k", "https://tunnel.example")
        assertEquals("https://tunnel.example", data.getString(ai.appdna.sdk.background.EventUploadWorker.KEY_BASE_URL))
        assertEquals("k", data.getString(ai.appdna.sdk.background.EventUploadWorker.KEY_API_KEY))

        // …and the SDK hands the worker the resolved override, not `environment.baseUrl`.
        val seen = java.util.Collections.synchronizedList(mutableListOf<String>())
        ai.appdna.sdk.background.EventUploadWorker.scheduleProbe = { seen += it }
        val ctx: Context = ApplicationProvider.getApplicationContext()
        val pkg = shadowOf(ctx.packageManager).getInternalMutablePackageInfo(ctx.packageName)
        pkg.applicationInfo!!.flags = pkg.applicationInfo!!.flags or ApplicationInfo.FLAG_DEBUGGABLE
        pkg.applicationInfo!!.metaData = meta("https://tunnel.example/")
        AppDNA.configure(ctx, "adn_test_override_worker", Environment.SANDBOX)
        AppDNA.shutdown() // the final upload attempt schedules the background upload with the resolved base
        assertTrue("the final upload attempt did not finish",
            AppDNA.lastShutdownEventQueueForTest!!.awaitShutdownFlush(30_000))
        assertTrue("the worker was scheduled", seen.isNotEmpty())
        assertEquals(listOf("https://tunnel.example"), seen.distinct())
    }

    @Test
    fun appliesOnlyToADebuggableSandboxBuild() {
        assertEquals("https://x.example", resolveBaseUrl(info(true), meta("https://x.example"), Environment.SANDBOX))
        assertEquals(prod, resolveBaseUrl(info(false), meta("https://x.example"), Environment.SANDBOX))
        assertEquals(prod, resolveBaseUrl(info(true), meta("https://x.example"), Environment.PRODUCTION))
        assertEquals(prod, resolveBaseUrl(info(true), null, Environment.SANDBOX))
    }

    @Test
    fun valueValidation() {
        assertEquals(prod, resolveBaseUrl(info(true), meta(""), Environment.SANDBOX))
        assertEquals(prod, resolveBaseUrl(info(true), meta("   "), Environment.SANDBOX))
        assertEquals("https://x.example", resolveBaseUrl(info(true), meta("https://x.example/"), Environment.SANDBOX))
        assertEquals("http://10.0.2.2:3000", resolveBaseUrl(info(true), meta("http://10.0.2.2:3000"), Environment.SANDBOX))
        assertEquals(prod, resolveBaseUrl(info(true), meta("x.example"), Environment.SANDBOX))
        assertEquals(prod, resolveBaseUrl(info(true), meta("ftp://x.example"), Environment.SANDBOX))
    }

    @Test
    fun apiClientBuildsFromTheBaseItIsGiven() {
        assertEquals("https://x.example", ApiClient("k", "https://x.example").baseUrl)
        assertEquals(prod, ApiClient("k", Environment.SANDBOX).baseUrl)
    }

    @Test
    fun configureResolvesOnceAndDiagnoseReportsIt() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        val pkg = shadowOf(ctx.packageManager).getInternalMutablePackageInfo(ctx.packageName)
        pkg.applicationInfo!!.flags = pkg.applicationInfo!!.flags or ApplicationInfo.FLAG_DEBUGGABLE
        pkg.applicationInfo!!.metaData = meta("https://tunnel.example/")
        AppDNA.configure(ctx, "adn_test_override", Environment.SANDBOX)
        assertEquals("https://tunnel.example", AppDNA.getApiBaseUrl())
        assertTrue(AppDNA.diagnose().contains("base_url: https://tunnel.example"))
    }
}
