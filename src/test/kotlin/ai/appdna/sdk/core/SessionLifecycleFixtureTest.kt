package ai.appdna.sdk.core

import ai.appdna.sdk.IdentityManager
import ai.appdna.sdk.events.EventTracker
import ai.appdna.sdk.storage.LocalStorage
import android.content.Context
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * Session-lifecycle behavioral fixtures: `packages/sdk-shared-fixtures/events/` files that carry a
 * `lifecycle` block. Mirrors the iOS `SessionLifecycleFixtureTests`.
 *
 * Each case is a fresh process: the process-wide foreground state is reset, the persisted last-active
 * time is seeded, and the REAL [SessionManager] is driven through the lifecycle callbacks Android
 * delivers (`start()` from configure in Application.onCreate, then `ON_START` / `ON_STOP`). The events
 * it tracks are read off the real [EventTracker] through its test sink and counted.
 *
 * Step mapping (Android):
 *  - launch_foreground   start(), then the launch's first ON_START
 *  - launch_background   start() only (a process started for a push or a broadcast has no ON_START)
 *  - foreground_callback ON_START
 *  - background          ON_STOP
 *  - reconfigure         stop() the current manager, start() a new one, and its replayed ON_START
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionLifecycleFixtureTest {

    private val ctx: Context = RuntimeEnvironment.getApplication()

    @Test
    fun sessionLifecycleFixtures() {
        val fixtures = loadLifecycleFixtures()
        assertTrue("No Android session-lifecycle fixtures found (packages/sdk-shared-fixtures/events)", fixtures.isNotEmpty())
        var cases = 0
        for ((id, lifecycle) in fixtures) {
            val appOpenProps = lifecycle.getJSONObject("app_open_properties")
            val arr = lifecycle.getJSONArray("cases")
            for (i in 0 until arr.length()) {
                runCase(id, arr.getJSONObject(i), appOpenProps)
                cases++
            }
        }
        println("[SessionLifecycle] asserted $cases cases in ${fixtures.size} fixtures")
    }

    private fun runCase(fixtureId: String, c: JSONObject, appOpenProps: JSONObject) {
        val label = "[$fixtureId / ${c.getString("name")}]"
        SessionManager.resetProcessStateForTest()
        SessionDataStore.initialize(ctx)
        val store = SessionDataStore.instance!!
        store.clearAll()
        val now = System.currentTimeMillis()
        when (val la = c.getString("last_active")) {
            "none" -> Unit
            "recent" -> store.setSessionData(SessionManager.KEY_LAST_ACTIVE_AT, now - 5L * 60L * 1000L)
            "stale" -> store.setSessionData(SessionManager.KEY_LAST_ACTIVE_AT, now - 31L * 60L * 1000L)
            else -> fail("$label unknown last_active: $la")
        }

        val tracked = mutableListOf<Pair<String, Map<String, Any>?>>()
        val tracker = EventTracker(IdentityManager(LocalStorage(ctx)), appVersion = "1.0.0-test")
        tracker.eventSink = { name, props -> tracked += name to props }

        var sm: SessionManager? = null
        fun launch(withUi: Boolean) {
            val m = SessionManager(ctx, tracker, store)
            m.start()
            if (withUi) m.handleForeground()
            sm = m
        }
        val steps = c.getJSONArray("steps")
        for (i in 0 until steps.length()) {
            when (val step = steps.getString(i)) {
                "launch_foreground" -> launch(withUi = true)
                "launch_background" -> launch(withUi = false)
                "foreground_callback" -> sm!!.handleForeground()
                "background" -> sm!!.handleBackground()
                "reconfigure" -> { sm!!.stop(); launch(withUi = true) }
                else -> fail("$label unknown lifecycle step: $step")
            }
        }
        sm?.stop()

        val expect = c.getJSONObject("expect")
        for (name in expect.keys()) {
            assertEquals("$label count of $name (tracked: ${tracked.map { it.first }})",
                expect.getInt(name), tracked.count { it.first == name })
        }
        for ((name, props) in tracked) {
            if (name != "app_open") continue
            val actual = JSONObject(props ?: emptyMap<String, Any>())
            assertEquals("$label app_open properties", appOpenProps.toString(), actual.toString())
        }
        store.clearAll()
        SessionManager.resetProcessStateForTest()
    }

    private fun loadLifecycleFixtures(): List<Pair<String, JSONObject>> {
        val dir = fixturesRoot()?.let { File(it, "events") } ?: error("Could not locate packages/sdk-shared-fixtures/events")
        val files = dir.listFiles { f -> f.name.endsWith(".fixture.json") } ?: emptyArray()
        return files.sortedBy { it.name }.mapNotNull { f ->
            val j = JSONObject(f.readText())
            if (j.getString("category") != "events" || !j.has("lifecycle")) return@mapNotNull null
            val plats = j.getJSONArray("platforms")
            if ((0 until plats.length()).none { plats.getString(it) == "android" }) return@mapNotNull null
            j.getString("id") to j.getJSONObject("lifecycle")
        }
    }

    private fun fixturesRoot(): File? {
        System.getenv("APPDNA_SDK_FIXTURES_DIR")?.let { val f = File(it); if (f.isDirectory) return f }
        var here: File? = File(".").canonicalFile
        repeat(10) {
            val candidate = File(here, "packages/sdk-shared-fixtures")
            if (candidate.isDirectory) return candidate
            here = here?.parentFile
        }
        val codespace = File("/workspaces/appdna-ai/packages/sdk-shared-fixtures")
        if (codespace.isDirectory) return codespace
        return null
    }
}
