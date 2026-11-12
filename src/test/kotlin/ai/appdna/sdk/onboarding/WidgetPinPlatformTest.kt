package ai.appdna.sdk.onboarding

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Provider discovery on the two API paths (24–25: `getInstalledProviders` filtered by package;
 * 26+: `getInstalledProvidersForPackage`), the choice among several, the success-callback receiver,
 * and the debug-only launcher simulation.
 */
@RunWith(RobolectricTestRunner::class)
class WidgetPinPlatformTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()


    private fun info(pkg: String, cls: String, category: Int = AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN) =
        AppWidgetProviderInfo().apply {
            provider = ComponentName(pkg, cls)
            widgetCategory = category
        }

    private fun install(vararg infos: AppWidgetProviderInfo) {
        val shadow = shadowOf(AppWidgetManager.getInstance(ctx))
        // API 24–25 read `getInstalledProviders()`; API 26+ `getInstalledProvidersForPackage(pkg, null)`,
        // which the shadow keys by profile (null = the calling user, as the SDK passes it).
        infos.forEach { shadow.addInstalledProvider(it); shadow.addInstalledProvidersForProfile(null, it) }
    }

    private fun discoveryScenarios() {
        val pkg = ctx.packageName
        // 0: only another app's widget and a lock-screen-only one.
        install(
            info("com.other.app", "com.other.app.Widget"),
            info(pkg, "$pkg.LockOnly", AppWidgetProviderInfo.WIDGET_CATEGORY_KEYGUARD),
        )
        val p = AndroidWidgetPinPlatform(ctx)
        assertEquals(emptyList<String>(), p.homeScreenProviders())
        assertNull(WidgetProviderDiscovery.choose(p.homeScreenProviders(), null))
        // 1
        install(info(pkg, "$pkg.widget.ZetaWidget"))
        assertEquals(listOf("$pkg.widget.ZetaWidget"), p.homeScreenProviders())
        assertEquals("$pkg.widget.ZetaWidget", WidgetProviderDiscovery.choose(p.homeScreenProviders(), "Unrelated"))
        // several: sorted, first unless named
        install(info(pkg, "$pkg.widget.AlphaWidget"))
        val several = p.homeScreenProviders()
        assertEquals(setOf("$pkg.widget.AlphaWidget", "$pkg.widget.ZetaWidget"), several.toSet())
        assertEquals("$pkg.widget.AlphaWidget", WidgetProviderDiscovery.choose(several, null))
        assertEquals("$pkg.widget.ZetaWidget", WidgetProviderDiscovery.choose(several, "ZetaWidget"))
        assertEquals("$pkg.widget.ZetaWidget", WidgetProviderDiscovery.choose(several, "$pkg.widget.ZetaWidget"))
        assertEquals("$pkg.widget.AlphaWidget", WidgetProviderDiscovery.choose(several, "NoSuchWidget"))
        // A suffix that is not a whole simple name does not match.
        assertEquals("$pkg.widget.AlphaWidget", WidgetProviderDiscovery.choose(several, "etaWidget"))
    }

    @Test @Config(sdk = [24]) fun `discovery on API 24 filters getInstalledProviders by package`() = discoveryScenarios()

    @Test @Config(sdk = [26]) fun `discovery on API 26 uses getInstalledProvidersForPackage`() = discoveryScenarios()

    @Test @Config(sdk = [24]) fun `API 24 never requests a pin`() {
        val p = AndroidWidgetPinPlatform(ctx)
        assertFalse(p.isRequestPinSupported())
        assertFalse(p.requestPin("x.Y", "t"))
        assertEquals(24, p.sdkInt)
    }

    @Test @Config(sdk = [34]) fun `the success callback reaches the live attempt through the receiver, and only it`() {
        val got = mutableListOf<String?>()
        val cb: (String?) -> Unit = { got += it }
        fun broadcast(token: String?) = AppDNAWidgetPinReceiver().onReceive(
            ctx, Intent(AppDNAWidgetPinReceiver.ACTION_PINNED).putExtra(AppDNAWidgetPinReceiver.EXTRA_TOKEN, token),
        )
        // No live attempt (process death while the dialog was up): dropped.
        assertFalse(WidgetPinRegistry.deliver("t1"))
        broadcast("t1")
        assertTrue(got.isEmpty())
        WidgetPinRegistry.register(cb)
        broadcast("t2")
        assertEquals(listOf<String?>("t2"), got)
        WidgetPinRegistry.unregister(cb)
        broadcast("t3")
        assertEquals(listOf<String?>("t2"), got)
    }

    @Test @Config(sdk = [34]) fun `the request carries an immutable broadcast to the SDK receiver with the token`() {
        val pkg = ctx.packageName
        install(info(pkg, "$pkg.widget.ZetaWidget"))
        val shadow = shadowOf(AppWidgetManager.getInstance(ctx))
        shadow.setRequestPinAppWidgetSupported(true)
        val p = AndroidWidgetPinPlatform(ctx)
        assertTrue(p.isRequestPinSupported())
        assertTrue(p.requestPin("$pkg.widget.ZetaWidget", "tok-1"))
    }

    @Test fun `the simulation is never read in a non-debuggable app`() {
        var reads = 0
        val release = ApplicationInfo().apply { flags = 0 }
        assertNull(WidgetPinSimulation.mode(release) { reads++; "refused" })
        assertEquals(0, reads)
        val debug = ApplicationInfo().apply { flags = ApplicationInfo.FLAG_DEBUGGABLE }
        assertEquals("refused", WidgetPinSimulation.mode(debug) { reads++; " refused\n" })
        assertEquals(1, reads)
        assertNull(WidgetPinSimulation.mode(debug) { "bogus" })
        assertNull(WidgetPinSimulation.mode(debug) { "" })
    }

    @Test fun `each simulated launcher drives its branch`() {
        val real = object : WidgetPinPlatform {
            var requests = 0
            override val sdkInt = 34
            override fun homeScreenProviders() = listOf("a.B")
            override fun isRequestPinSupported() = true
            override fun requestPin(providerClass: String, token: String): Boolean { requests++; return true }
            override fun widgetIds(providerClass: String) = emptySet<Int>()
        }
        assertFalse(WidgetPinSimulation.wrap(real, "unsupported").isRequestPinSupported())
        assertFalse(WidgetPinSimulation.wrap(real, "refused").requestPin("a.B", "t"))
        assertTrue(runCatching { WidgetPinSimulation.wrap(real, "throws").requestPin("a.B", "t") }.exceptionOrNull() is IllegalStateException)
        assertTrue(WidgetPinSimulation.wrap(real, "silent").requestPin("a.B", "t"))
        assertEquals("no simulated mode reaches the real launcher", 0, real.requests)
        assertTrue(WidgetPinSimulation.wrap(real, null).requestPin("a.B", "t"))
        assertEquals(1, real.requests)
    }
}
