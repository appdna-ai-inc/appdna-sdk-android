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
