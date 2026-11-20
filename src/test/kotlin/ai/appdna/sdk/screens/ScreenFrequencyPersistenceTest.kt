package ai.appdna.sdk.screens

import ai.appdna.sdk.AppDNA
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A screen trigger's `max_impressions` and `cooldown_hours` survive a cold launch. They were kept in memory
 * only, so a screen capped at one impression showed once per launch. A new [AutoTriggerEngine] over the same store
 * is a new process. `once_per_session` deliberately does not survive: a new process is a new session.
 *
 * Red control: an engine with no store (the old shape) shows the capped screen again after the "relaunch".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ScreenFrequencyPersistenceTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() { AppDNA.installApplicationContextForTest(null) }

    private fun entry(id: String, frequency: Map<String, Any>) = ScreenIndexEntry.fromMap(mapOf(
        "id" to id, "name" to id,
        "trigger_rules" to mapOf("events" to listOf(mapOf("event_name" to "go")), "frequency" to frequency),
    ))

    private fun AutoTriggerEngine.go(e: ScreenIndexEntry) = evaluate(listOf(e), "go", null, emptyMap(), 1, 0, null)

    @Test
    fun `max impressions and cooldown hold across a relaunch through SharedPreferences`() {
        AppDNA.installApplicationContextForTest(ctx)
        val capped = entry("capped", mapOf("max_impressions" to 1))
        val cooled = entry("cooled", mapOf("cooldown_hours" to 24))
        val perSession = entry("per_session", mapOf("once_per_session" to true))

        val first = AutoTriggerEngine(PrefsScreenFrequencyStore)
        assertEquals("capped", first.go(capped))
        assertEquals("cooled", first.go(cooled))
        assertEquals("per_session", first.go(perSession))
        assertNull(first.go(capped))
        assertNull(first.go(perSession))

        val relaunched = AutoTriggerEngine(PrefsScreenFrequencyStore)
        assertNull("max_impressions started again on a relaunch", relaunched.go(capped))
        assertNull("cooldown_hours started again on a relaunch", relaunched.go(cooled))
        assertEquals("once_per_session must reset with the session", "per_session", relaunched.go(perSession))
    }

    @Test
    fun `without a store the cap resets on relaunch (negative control)`() {
        val capped = entry("capped_mem", mapOf("max_impressions" to 1))
        assertEquals("capped_mem", AutoTriggerEngine().go(capped))
        assertEquals("capped_mem", AutoTriggerEngine().go(capped))
    }

    @Test
    fun `no application context yet persists nothing and is read later`() {
        val capped = entry("late", mapOf("max_impressions" to 1))
        val early = AutoTriggerEngine(PrefsScreenFrequencyStore)
        assertEquals("late", early.go(capped))
        AppDNA.installApplicationContextForTest(ctx)
        assertEquals("the in-memory count still caps it", null, early.go(capped))
    }
}
