package ai.appdna.sdk.screens

import ai.appdna.sdk.AppDNA
import ai.appdna.sdk.Environment
import ai.appdna.sdk.events.ClientSeqCounter
import ai.appdna.sdk.events.DroppedEventsCounter
import ai.appdna.sdk.events.EventQueue
import ai.appdna.sdk.events.EventTracker
import ai.appdna.sdk.network.ApiClient
import ai.appdna.sdk.storage.EventDatabase
import ai.appdna.sdk.storage.LocalStorage
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A published screen never reached a device. The console writes each screen to
 * `config/screen_index/screens/{id}`, and nothing on Android read it: `showScreen`, slots, interceptions and
 * triggers all ended in `screen_not_found` unless the host had called `previewScreen` first.
 *
 * These drive the REAL entry points (`AppDNA.showScreen`, `AppDNA.showFlow`, `updateIndex` → `screenForSlot`)
 * with the document reader `RemoteConfigManager` installs replaced by one that serves console-shaped documents.
 *
 * Red control: with the cache-miss fetch removed from `showScreen`, the first test fails (no Activity starts, the
 * host gets `SCREEN_NOT_FOUND`); with `prefetch` removed from `updateIndex`, the slot test fails.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ServedScreenLoadingTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: EventDatabase
    private val requests = mutableListOf<String>()

    private fun section(id: String) = mapOf("id" to id, "type" to "text", "data" to mapOf("text" to "Hello"))

    /** The shape `ScreenConfigSyncService.buildScreenConfig` writes. */
    private fun screenDoc(id: String, name: String, sections: Int = 1) = mapOf(
        "id" to id, "version" to 2L, "name" to name, "default_locale" to "en",
        "updated_at" to "2026-11-20T10:00:00.000Z",
        "config" to mapOf(
            "presentation" to "modal", "layout" to mapOf("type" to "scroll"),
            "sections" to (1..sections).map { section("s$it") },
        ),
    )

    private val docs = mutableMapOf<String, Map<String, Any?>>()

    @Before
    fun setUp() {
        val storage = LocalStorage(ctx)
        val identity = ai.appdna.sdk.IdentityManager(storage)
        db = EventDatabase(ctx, 10_000, "served_screen_loading_test.db")
        ClientSeqCounter.init(ctx)
        DroppedEventsCounter.init(ctx)
        db.clearAll()
        val tracker = EventTracker(identity, "1.0", "sandbox")
        tracker.setEventQueue(
            EventQueue(
                apiClient = ApiClient("adn_test_served", Environment.PRODUCTION),
                eventDatabase = db,
                connectivityMonitor = null,
                batchSizeCap = 0,
                flushInterval = Long.MAX_VALUE,
            ),
        )
        AppDNA.installEventTrackerForTest(tracker)
        AppDNA.installApplicationContextForTest(ctx)
        ScreenManager.shared.reset()
        // A screen presented by an earlier test is never dismissed, so the coordinator would queue this one.
        ai.appdna.sdk.core.PresentationCoordinator.shared.reset()
        requests.clear()
        docs.clear()
        ScreenManager.shared.documentLoader = { kind, id, done ->
            requests.add("$kind/$id")
            done(docs["$kind/$id"])
        }
    }

    @After
    fun tearDown() {
        ScreenManager.shared.documentLoader = null
        ai.appdna.sdk.core.PresentationCoordinator.shared.reset()
        AppDNA.installEventTrackerForTest(null)
        AppDNA.installApplicationContextForTest(null)
        ScreenManager.shared.reset()
        ScreenHostActivity.clearActiveLaunches()
        db.clearAll()
    }

    private fun events(): List<Pair<String, JSONObject?>> =
        db.loadAll().map { JSONObject(it) }.map { it.getString("event_name") to it.optJSONObject("properties") }

    private fun startedIntent(): android.content.Intent? =
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>()).nextStartedActivity

    @Test
    fun `showScreen on a cache miss fetches the published document and presents it`() {
        docs["screens/scr_welcome"] = screenDoc("scr_welcome", "Welcome screen", sections = 2)
        var result: ScreenResult? = null
        AppDNA.showScreen("scr_welcome") { result = it }
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertEquals(listOf("screens/scr_welcome"), requests)
        assertNotNull("the host got no result", result)
        assertNull("the host was told the screen was not found", result!!.error)
        val intent = startedIntent()
        assertNotNull("no screen Activity was started", intent)
        assertEquals(ScreenHostActivity::class.java.name, intent!!.component?.className)
        val presented = events().firstOrNull { it.first == "screen_presented" }?.second
        assertNotNull("screen_presented was not tracked", presented)
        assertEquals("Welcome screen", presented!!.optString("screen_name"))
        assertEquals("modal", presented.optString("presentation"))
        assertEquals(2, ScreenManager.shared.getCachedScreen("scr_welcome")?.sections?.size)
    }

    @Test
    fun `a screen with no published document is still not found`() {
        var result: ScreenResult? = null
        AppDNA.showScreen("scr_gone") { result = it }
        assertEquals(listOf("screens/scr_gone"), requests)
        assertEquals(ScreenError.SCREEN_NOT_FOUND, result?.error)
        assertTrue(events().any { it.first == "screen_dismissed" && it.second?.optString("error") == "screen_not_found" })
    }

    @Test
    fun `a cached screen is presented without a fetch`() {
        docs["screens/scr_a"] = screenDoc("scr_a", "A")
        ScreenManager.shared.loadScreen("scr_a") { }
        requests.clear()
        AppDNA.showScreen("scr_a")
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(emptyList<String>(), requests)
        assertNotNull(startedIntent())
    }

    @Test
    fun `the index load prefetches every screen it names, so a slot resolves`() {
        docs["screens/scr_listed"] = screenDoc("scr_listed", "Listed")
        docs["screens/scr_slot"] = screenDoc("scr_slot", "Slot")
        docs["screens/scr_intercept"] = screenDoc("scr_intercept", "Intercept")
        docs["flows/flow_a"] = mapOf(
            "id" to "flow_a", "name" to "Flow", "version" to 1L,
            "config" to mapOf(
                "start_screen_id" to "scr_listed", "settings" to emptyMap<String, Any>(),
                "screens" to listOf(mapOf("screen_id" to "scr_listed", "navigation_rules" to emptyList<Any>())),
            ),
        )
        val index = ScreenIndex.fromMap(mapOf(
            "screens" to listOf(mapOf("id" to "scr_listed", "name" to "Listed")),
            "flows" to listOf(mapOf("id" to "flow_a", "name" to "Flow")),
            "slots" to listOf(mapOf("slot_name" to "home_banner", "screen_id" to "scr_slot")),
            "interceptions" to listOf(mapOf(
                "id" to "i1", "trigger_screen" to "Checkout", "timing" to "before", "screen_id" to "scr_intercept",
            )),
        ))
        assertNull("the slot resolved before the index load", ScreenManager.shared.screenForSlot("home_banner"))

        ScreenManager.shared.updateIndex(index)

        assertTrue(requests.containsAll(listOf(
            "screens/scr_listed", "screens/scr_slot", "screens/scr_intercept", "flows/flow_a",
        )))
        val slot = ScreenManager.shared.screenForSlot("home_banner")
        assertNotNull("the slot still resolves to nothing", slot)
        assertEquals("scr_slot", slot!!.first)
        assertEquals("Slot", slot.second.name)
        assertNotNull(ScreenManager.shared.getCachedScreen("scr_intercept"))
        assertNotNull(ScreenManager.shared.getCachedFlow("flow_a"))
    }

    @Test
    fun `showFlow on a cache miss fetches the flow and each of its screens, then presents it`() {
        docs["flows/flow_a"] = mapOf(
            "id" to "flow_a", "name" to "Two-screen flow", "version" to 1L,
            "config" to mapOf(
                "start_screen_id" to "scr_f1", "settings" to emptyMap<String, Any>(),
                "screens" to listOf(
                    mapOf("screen_id" to "scr_f1", "navigation_rules" to emptyList<Any>()),
                    mapOf("screen_id" to "scr_f2", "navigation_rules" to emptyList<Any>()),
                ),
            ),
        )
        docs["screens/scr_f1"] = screenDoc("scr_f1", "First")
        docs["screens/scr_f2"] = screenDoc("scr_f2", "Second")
        AppDNA.showFlow("flow_a")
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertEquals(listOf("flows/flow_a", "screens/scr_f1", "screens/scr_f2"), requests)
        val intent = startedIntent()
        assertNotNull("the flow was not presented", intent)
        assertEquals("flow_a", intent!!.getStringExtra(ScreenHostActivity.EXTRA_FLOW_ID))
        val started = events().firstOrNull { it.first == "flow_started" }?.second
        assertEquals("Two-screen flow", started?.optString("flow_name"))
    }
}
