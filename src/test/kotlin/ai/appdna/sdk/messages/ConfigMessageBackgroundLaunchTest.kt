package ai.appdna.sdk.messages

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * A config-driven in-app message triggered while the process has no resumed activity (an FCM data message, a
 * WorkManager job, a background fetch) was DROPPED: the presenter found no activity and returned. It is now held and
 * drawn when an activity next resumes in the same process, as on iOS.
 *
 * These tests run the shared fixture's script through the REAL [MessageManager] twice — as the SDK now behaves, and
 * with the activation signal cut as the RED CONTROL: the same harness must see the held messages stay undrawn when the
 * app is opened, or a green run of the fixture proves nothing. A third keeps the PRODUCTION activity lookup in a
 * process with no activity.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConfigMessageBackgroundLaunchTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private fun fixtureAction(): JSONObject {
        val rel = "sdk-shared-fixtures/action_dispatch/config_messages_background_launch.fixture.json"
        var dir: File? = File(".").absoluteFile
        var found: File? = null
        while (dir != null && found == null) {
            listOf(File(dir, rel), File(dir, "packages/$rel")).firstOrNull { it.exists() }?.let { found = it }
            dir = dir.parentFile
        }
        if (found == null) System.getenv("APPDNA_SDK_FIXTURES_DIR")?.let { env ->
            File(env, "action_dispatch/config_messages_background_launch.fixture.json").takeIf { it.exists() }?.let { found = it }
        }
        assertNotNull("config_messages_background_launch.fixture.json not found", found)
        return JSONObject(found!!.readText()).getJSONObject("action")
    }

    @Test
    fun fixtureScriptHoldsAndDrawsEachMessageOnceWhenActive() {
        val state = ConfigMessageHoldRig.run(ctx, fixtureAction())!!
        assertEquals(listOf("fx_hold_a", "fx_hold_b", "fx_hold_b"), state["presented"])
        assertEquals(emptyList<String>(), state["held"])
    }

    /**
     * RED CONTROL — no activation signal: opening the app draws nothing. The `once` message is drawn only when it
     * triggers again later (and, being on screen, keeps every later trigger out), and the held one only because that
     * one closed — one message short of the fixture.
     */
    @Test
    fun redControlWithoutActivationTheHeldMessagesWaitPastTheOpening() {
        val state = ConfigMessageHoldRig.run(ctx, fixtureAction(), noActivation = true)!!
        assertEquals(listOf("fx_hold_a", "fx_hold_b"), state["presented"])
        assertEquals(emptyList<String>(), state["held"])
    }

    /**
     * A Share CTA on the message on screen opens the system chooser over the host. The close that follows must not draw
     * the next held message behind the chooser: it waits for the next activity resume (the user coming back), as iOS
     * waits for the next activation. A plain dismiss still draws it at once.
     */
    @Test
    fun aShareCtaCloseLeavesTheNextHeldMessageForTheNextResume() {
        val messages = JSONObject("""{
          "fx_share": {"message_type": "modal", "content": {"title": "Share it", "body": "b", "cta_text": "Share",
                       "cta_action": {"type": "share", "text": "Synthetic share text", "url": "https://example.com/s"}},
                       "trigger_rules": {"event": "evt_share", "frequency": "every_time"}},
          "fx_next":  {"message_type": "modal", "content": {"title": "Next", "body": "b", "cta_text": "OK"},
                       "trigger_rules": {"event": "evt_next", "frequency": "every_time"}}
        }""")
        fun script(vararg ops: String) = JSONObject().put("messages", messages).put("script", org.json.JSONArray().apply {
            ops.forEach { put(JSONObject(it)) }
        })
        val setup = arrayOf("""{"op":"window","available":false}""", """{"op":"track","event":"evt_share"}""",
            """{"op":"track","event":"evt_next"}""", """{"op":"window","available":true}""", """{"op":"app_active"}""")

        val afterShare = ConfigMessageHoldRig.run(ctx, script(*setup, """{"op":"tap_cta"}"""))!!
        assertEquals("the chooser is up: nothing drawn behind it", listOf("fx_share"), afterShare["presented"])
        assertEquals(listOf("fx_next"), afterShare["held"])

        val back = ConfigMessageHoldRig.run(ctx, script(*setup, """{"op":"tap_cta"}""", """{"op":"app_active"}"""))!!
        assertEquals("back from the chooser: drawn on the resume", listOf("fx_share", "fx_next"), back["presented"])
        assertEquals(emptyList<String>(), back["held"])

        // A dismiss (no share) still draws the next one when the first closes.
        val dismissed = ConfigMessageHoldRig.run(ctx, script(*setup, """{"op":"close"}"""))!!
        assertEquals(listOf("fx_share", "fx_next"), dismissed["presented"])
    }

    /** The PRODUCTION activity lookup with no activity in the process: held, not drawn, its `once` not used up. */
    @Test
    fun liveActivityLookupHoldsWithNoActivity() {
        val id = "fx_hold_live"
        ctx.getSharedPreferences("ai.appdna.sdk.msg_freq", Context.MODE_PRIVATE).edit().clear().commit()
        val messages = MessageConfigParser.parseMessages(
            mapOf(
                id to mapOf(
                    "message_type" to "modal",
                    "content" to mapOf("title" to "Synthetic", "body" to "Synthetic body.", "cta_text" to "OK"),
                    "trigger_rules" to mapOf("event" to "session_start", "frequency" to "once"),
                ),
            ),
        )
        val manager = MessageManager(context = ctx, configProvider = { messages })
        manager.onEvent("session_start", emptyMap())
        shadowOf(Looper.getMainLooper()).idle()
        manager.onEvent("session_start", emptyMap())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("held once, however often it triggers", listOf(id), manager.heldMessageIds)
        assertTrue(
            "a held `once` message is not used up",
            MessageFrequencyTracker(ctx).canShow(id, "once", null),
        )
        manager.shutdown()
    }
}
