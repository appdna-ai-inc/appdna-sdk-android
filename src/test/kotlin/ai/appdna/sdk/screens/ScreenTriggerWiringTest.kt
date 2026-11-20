package ai.appdna.sdk.screens

import ai.appdna.sdk.IdentityManager
import ai.appdna.sdk.events.ClientSeqCounter
import ai.appdna.sdk.events.DroppedEventsCounter
import ai.appdna.sdk.events.EventTracker
import ai.appdna.sdk.events.TriggerDispatcher
import ai.appdna.sdk.storage.LocalStorage
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Every tracked event runs the screen triggers, as it runs the message and survey triggers. Nothing called
 * `ScreenManager.evaluateTriggers` before, so no screen ever auto-presented from an event, on either platform.
 *
 * The route is the real one: `EventTracker.track` → `TriggerDispatcher` → the evaluator `AppDNA.configure()`
 * attaches, whose SCREEN branch is `ScreenManager.shared.evaluateTriggers`. What is observed: a trigger capped at
 * ONE impression. When the decision runs, the engine records the impression, and the same decision asked again
 * picks nothing. The screens module's own events (`screen_dismissed`) run no trigger, so the cap stays unspent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ScreenTriggerWiringTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        ScreenManager.shared.reset()
    }

    private fun tracker(): EventTracker {
        ClientSeqCounter.init(ctx)
        DroppedEventsCounter.init(ctx)
        return EventTracker(IdentityManager(LocalStorage(ctx)), "1.0", "sandbox")
    }

    private fun index(screenId: String, event: String) = ScreenIndex.fromMap(mapOf(
        "screens" to listOf(mapOf(
            "id" to screenId, "name" to "Wired",
            "trigger_rules" to mapOf(
                "events" to listOf(mapOf("event_name" to event)),
                "frequency" to mapOf("max_impressions" to 1),
            ),
        )),
    ))

    private fun pickedAgain(event: String): String? = ScreenManager.shared.triggeredScreen(
        event, null, currentScreenName = null,
        inputs = ScreenTriggerInputs(sessionCount = 1, daysSinceInstall = 0), traits = emptyMap(),
    )

    /** The dispatcher wired the way `AppDNA.configure()` wires it, SCREEN branch included. */
    private fun wiredTracker(): EventTracker {
        val t = tracker()
        val d = TriggerDispatcher()
        t.triggerSink = { e, p -> d.record(e, p) }
        d.attach { surface, e, p ->
            if (surface == TriggerDispatcher.Surface.SCREEN) ScreenManager.shared.evaluateTriggers(e, p)
        }
        d.markConfigReady()
        return t
    }

    @Test
    fun `a tracked event runs the screen triggers`() {
        assertTrue(TriggerDispatcher.surfaces("walk_event").contains(TriggerDispatcher.Surface.SCREEN))
        ScreenManager.shared.updateIndex(index("wired_1", "walk_event"))
        assertEquals("the harness sees an unspent trigger", "wired_1", pickedAgain("walk_event"))
        ScreenManager.shared.reset()
        ScreenManager.shared.updateIndex(index("wired_2", "walk_event"))

        wiredTracker().track("walk_event", emptyMap())
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        assertNull("track did not run the trigger: its one impression is still unspent", pickedAgain("walk_event"))
    }

    @Test
    fun `the screens module's own events run no trigger`() {
        assertTrue(TriggerDispatcher.Surface.SCREEN !in TriggerDispatcher.surfaces("screen_dismissed"))
        ScreenManager.shared.updateIndex(index("own_1", "screen_dismissed"))
        wiredTracker().track("screen_dismissed", emptyMap())
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals("screen_dismissed ran the trigger and spent its impression", "own_1", pickedAgain("screen_dismissed"))
    }
}
