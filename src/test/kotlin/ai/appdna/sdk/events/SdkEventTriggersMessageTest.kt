package ai.appdna.sdk.events

import ai.appdna.sdk.IdentityManager
import ai.appdna.sdk.AppDNAInAppMessageDelegate
import ai.appdna.sdk.messages.MessageConfigParser
import ai.appdna.sdk.messages.MessageManager
import ai.appdna.sdk.storage.LocalStorage
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * An event the SDK tracks itself reaches the REAL [MessageManager]'s trigger evaluation, and a message
 * authored on `app_open` — the console's default trigger — is presented.
 *
 * The shared fixture `event_emission/sdk_events_reach_message_and_survey_triggers` pins the routing
 * with a recording evaluator; this closes the gap between that evaluator and a real manager: the
 * event goes through the real [EventTracker.track] into the real [TriggerDispatcher], whose evaluator
 * is the same `messageManager.onEvent` call `AppDNA.configure()` attaches, and the manager matches the
 * trigger, passes frequency and the date window, and reaches presentation. Presentation is observed at
 * the host's `shouldShowMessage` — the first thing a presentation consults — which vetoes, so no
 * Dialog is needed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SdkEventTriggersMessageTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    /**
     * A tracker with NO event queue: the route under test never depends on the queue, and a real queue
     * would try to upload what this test tracks and hold the process-wide upload claim other tests need.
     */
    private fun tracker(): EventTracker {
        ClientSeqCounter.init(ctx)
        DroppedEventsCounter.init(ctx)
        return EventTracker(IdentityManager(LocalStorage(ctx)), "1.0", "sandbox")
    }

    private fun message(id: String, event: String, frequency: String): Map<String, ai.appdna.sdk.messages.MessageConfig> {
        val cfg = MessageConfigParser.parseSingleMessage(
            mapOf(
                "name" to id,
                "message_type" to "modal",
                "content" to mapOf("title" to "Welcome back", "body" to "b"),
                "trigger_rules" to mapOf("event" to event, "frequency" to frequency),
            ),
        ) ?: error("message did not parse")
        return mapOf(id to cfg)
    }

    private class Recorder : AppDNAInAppMessageDelegate {
        val asked = mutableListOf<String>()
        override fun shouldShowMessage(messageId: String): Boolean {
            asked.add(messageId)
            return false // observe the presentation, show nothing
        }
    }

    private fun idleMain() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun anSdkTrackedAppOpenHeldAtLaunchPresentsTheAppOpenMessageOnceTheConfigIsReady() {
        val t = tracker()
        val dispatcher = TriggerDispatcher()
        t.triggerSink = { e, p -> dispatcher.record(e, p) }
        val mm = MessageManager(ctx, configProvider = { message("m_app_open", "app_open", "every_time") })
        val host = Recorder()
        mm.delegate = host

        // The cold launch: app_open is tracked by the SDK (SessionManager → EventTracker) before the
        // manager is attached and before the config is ready.
        t.track("app_open", emptyMap())
        dispatcher.attach { surface, e, p ->
            if (surface == TriggerDispatcher.Surface.MESSAGE) mm.onEvent(e, p ?: emptyMap())
        }
        idleMain()
        assertEquals("held until the config is ready", emptyList<String>(), host.asked)

        dispatcher.markConfigReady()
        idleMain()
        assertEquals("the held app_open presents the app_open message", listOf("m_app_open"), host.asked)

        // A second app_open (the next foreground), tracked by the SDK: evaluated once, not twice.
        t.track("app_open", emptyMap())
        idleMain()
        assertEquals(listOf("m_app_open", "m_app_open"), host.asked)

        // The message's own event never re-triggers a message, even one authored on it.
        val selfTriggered = MessageManager(ctx, configProvider = { message("m_self", "in_app_message_shown", "every_time") })
        val selfHost = Recorder()
        selfTriggered.delegate = selfHost
        dispatcher.attach { surface, e, p ->
            if (surface == TriggerDispatcher.Surface.MESSAGE) selfTriggered.onEvent(e, p ?: emptyMap())
        }
        t.track("in_app_message_shown", mapOf("message_id" to "x"))
        idleMain()
        assertEquals(emptyList<String>(), selfHost.asked)
    }

    @Test
    fun withoutTheRouteAnSdkTrackedAppOpenNeverReachesTheManager() {
        // Negative control: the same manager and the same event with no trigger sink — the shape before
        // the fix — presents nothing. Proves the assertion above is about the route, not the harness.
        val t = tracker()
        val mm = MessageManager(ctx, configProvider = { message("m_app_open", "app_open", "every_time") })
        val host = Recorder()
        mm.delegate = host
        t.track("app_open", emptyMap())
        idleMain()
        assertEquals(emptyList<String>(), host.asked)
    }
}
