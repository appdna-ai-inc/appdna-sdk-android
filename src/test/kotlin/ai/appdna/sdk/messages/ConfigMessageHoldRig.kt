package ai.appdna.sdk.messages

import android.app.Activity
import android.content.Context
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf

/**
 * The fake edges of a `hold_config_messages` run (the shared fixture
 * `action_dispatch/config_messages_background_launch`), around the REAL [MessageManager]: its trigger evaluation,
 * frequency tracker, presentation gate, hold and release, over messages parsed by the REAL [MessageConfigParser]. Only
 * the screen ([MessageSurface]) and the activation signal are replaced: an activity is resumed or it is not, and the
 * screen records what is drawn.
 *
 * `noActivation` cuts the activation signal (an activity resuming), for the red control in
 * `ConfigMessageBackgroundLaunchTest`: without it, a message triggered with no activity is never drawn when the app
 * is opened — the previous behaviour, where it was dropped.
 */
internal class ConfigMessageHoldRig(context: Context, messagesJson: JSONObject, private val noActivation: Boolean = false) {
    var foreground = true
    val presented = ArrayList<String>()
    private var onActive: (() -> Unit)? = null
    private var onScreen: Pair<() -> Unit, () -> Unit>? = null
    private var onScreenCta: ((Activity) -> Unit)? = null
    private val activity: Activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    private var messages: Map<String, MessageConfig> = MessageConfigParser.parseMessages(toMap(messagesJson))

    val manager = MessageManager(
        context = context,
        configProvider = { messages },
        surface = object : MessageSurface {
            override fun host(): Activity? = if (foreground) activity else null
            override fun show(
                messageId: String,
                activity: Activity,
                config: MessageConfig,
                onCTATap: (Activity) -> Unit,
                onDismiss: () -> Unit,
                onClosed: () -> Unit,
            ) {
                presented.add(messageId)
                onScreen = onDismiss to onClosed
                onScreenCta = onCTATap
            }
            override fun observeActivation(block: () -> Unit): () -> Unit {
                onActive = if (noActivation) null else block
                return { onActive = null }
            }
        },
    )

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    fun track(event: String) {
        manager.onEvent(event, emptyMap())
        idle()
    }

    fun appActive() { onActive?.invoke(); idle() }

    fun close() {
        val s = onScreen ?: return
        onScreen = null
        s.first()
        s.second()
        idle()
    }

    /** The user taps the message's CTA: its action runs, then the message closes (as the dialog does). */
    fun tapCta() {
        val cta = onScreenCta ?: return
        val s = onScreen ?: return
        onScreen = null
        onScreenCta = null
        cta(activity)
        s.second()
        idle()
    }

    fun unpublish(id: String) { messages = messages - id }

    companion object {
        @Suppress("UNCHECKED_CAST")
        private fun toMap(o: JSONObject): Map<String, Any> =
            o.keys().asSequence().associateWith { k -> unwrap(o.get(k)) } as Map<String, Any>

        private fun unwrap(v: Any?): Any? = when (v) {
            is JSONObject -> toMap(v)
            is JSONArray -> (0 until v.length()).map { unwrap(v.get(it)) }
            JSONObject.NULL -> null
            else -> v
        }

        /** Runs the fixture's script; the state it produced (`presented`, `held`), or null on an unknown op. */
        fun run(context: Context, action: JSONObject, noActivation: Boolean = false): Map<String, Any?>? {
            val script = action.optJSONArray("script") ?: return null
            val messages = action.optJSONObject("messages") ?: return null
            // A previous case's `once` record would hide the message.
            context.getSharedPreferences("ai.appdna.sdk.msg_freq", Context.MODE_PRIVATE).edit().clear().commit()
            val rig = ConfigMessageHoldRig(context, messages, noActivation)
            for (i in 0 until script.length()) {
                val o = script.getJSONObject(i)
                when (o.optString("op")) {
                    "window" -> rig.foreground = o.optBoolean("available", true)
                    "track" -> rig.track(o.getString("event"))
                    "app_active" -> rig.appActive()
                    "close" -> rig.close()
                    "tap_cta" -> rig.tapCta()
                    "unpublish" -> rig.unpublish(o.getString("id"))
                    else -> return null
                }
            }
            return mapOf("presented" to rig.presented.toList(), "held" to rig.manager.heldMessageIds)
        }
    }
}
