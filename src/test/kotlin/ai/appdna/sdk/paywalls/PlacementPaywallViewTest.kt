package ai.appdna.sdk.paywalls

import ai.appdna.sdk.AppDNA
import ai.appdna.sdk.config.RemoteConfigManager
import ai.appdna.sdk.events.EventTracker
import ai.appdna.sdk.storage.LocalStorage
import android.app.Activity
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A paywall presented BY PLACEMENT reported `placement=unknown` on its `paywall_view`, because
 * `presentByPlacement` forwarded the host's context (usually null) and `present` fell back to "unknown". iOS rebuilds
 * the context with the requested placement (`PlacementPaywallContext.make`). Drives the REAL `PaywallManager` and reads
 * the event the host's observer receives.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PlacementPaywallViewTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val seen = mutableListOf<Pair<String, Map<String, Any?>>>()

    @After fun tearDown() { AppDNA.lifecycleDelegateOrNull = null }

    private fun manager(): PaywallManager {
        val remoteConfig = RemoteConfigManager(firestorePath = null, storage = LocalStorage(ctx), configTTL = 3600L)
        remoteConfig.loadBundledConfig(mapOf("paywalls" to mapOf("pw_upgrade" to mapOf(
            "id" to "pw_upgrade", "name" to "pw_upgrade", "placement" to "upgrade",
            "layout" to mapOf("type" to "stack"), "sections" to emptyList<Map<String, Any>>(),
        ))))
        val tracker = EventTracker(ai.appdna.sdk.IdentityManager(LocalStorage(ctx)), "1.0", "sandbox")
        AppDNA.lifecycleDelegateOrNull = object : ai.appdna.sdk.generated.AppDNALifecycleDelegate {
            override fun onEventTracked(name: String, properties: Map<String, Any?>) { seen += name to properties }
        }
        return PaywallManager(remoteConfigManager = remoteConfig, eventTracker = tracker)
    }

    /** `paywall_view` fires after the store-price lookup, in the manager's Main-dispatcher coroutine: run the looper. */
    private fun viewProps(): Map<String, Any?> {
        repeat(20) { if (seen.none { it.first == "paywall_view" }) shadowOf(Looper.getMainLooper()).idle() }
        return seen.last { it.first == "paywall_view" }.second
    }

    private fun viewPlacement(): Any? = viewProps()["placement"]

    @Test
    fun `paywall_view carries the requested placement when the host passes no context`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        manager().presentByPlacement(activity = activity, placement = "upgrade")
        assertEquals("upgrade", viewPlacement())
    }

    @Test
    fun `the requested placement wins and the host's experiment and custom data are kept`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val host = PaywallContext(placement = "settings", experiment = "exp_q3", variant = "b", customData = mapOf("source" to "menu"))
        manager().presentByPlacement(activity = activity, placement = "upgrade", context = host)
        val props = viewProps()
        assertEquals("upgrade", props["placement"])
        assertEquals("menu", props["source"])
    }
}
