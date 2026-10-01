package ai.appdna.sdk.integrations

import ai.appdna.sdk.AppDNA
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Looper
import androidx.core.app.RemoteInput
import androidx.test.core.app.ApplicationProvider
import com.google.firebase.messaging.RemoteMessage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * SPEC-497 §17 item 28 — the console's action buttons and custom sound render on Android: buttons from
 * `data.actions` (SDK shape) routed through the push-tap path, `dismiss` / background buttons through
 * [PushActionReceiver], `text_reply` with a RemoteInput whose text reaches `onPushTapped` as
 * `data["reply_text"]`, and a custom sound on its own `appdna_sound_<key>` channel.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PushNotificationActionsTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private lateinit var h: PushTestHarness
    private lateinit var service: AppDNAMessagingService
    private val nm get() = ctx.getSystemService(NotificationManager::class.java)

    /** The `actions` JSON exactly as the server sends it (fixture server_fcm_action_button_tap_routes_open_url). */
    private val actionsJson = JSONArray()
        .put(JSONObject(mapOf("id" to "view", "label" to "View", "action_type" to "open_url", "action_value" to "https://example.test/p", "foreground" to true)))
        .put(JSONObject(mapOf("id" to "later", "label" to "Later", "action_type" to "dismiss", "foreground" to false)))
        .put(JSONObject(mapOf("id" to "reply", "label" to "Reply", "action_type" to "text_reply", "action_value" to "Type a reply", "foreground" to false)))
        .toString()

    private fun data(extra: Map<String, String> = emptyMap()) = mapOf(
        "appdna" to "1", "push_id" to "p1", "delivery_id" to "d1", "title" to "T", "body" to "B", "actions" to actionsJson,
    ) + extra

    @Before
    fun setUp() {
        h = PushTestHarness(ctx, "actions")
        val cn = ComponentName(ctx.packageName, "${ctx.packageName}.LaunchActivity")
        shadowOf(ctx.packageManager).addActivityIfNotPresent(cn)
        shadowOf(ctx.packageManager).addIntentFilterForActivity(
            cn,
            IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) },
        )
        service = Robolectric.setupService(AppDNAMessagingService::class.java)
    }

    @After
    fun tearDown() {
        h.close()
        PushSoundChannels.rawResourceId = { c, key -> c.resources.getIdentifier(key, "raw", c.packageName) }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun notifications(): List<Notification> = shadowOf(nm).allNotifications
    private fun post(d: Map<String, String>): Notification {
        service.onMessageReceived(RemoteMessage.Builder("test@fcm").setData(d).build())
        idle()
        return notifications().single()
    }

    @Test
    fun `the three buttons render - open-app button launches the app, dismiss goes to the receiver, reply has a RemoteInput`() {
        val n = post(data())
        assertEquals(listOf("View", "Later", "Reply"), n.actions.map { it.title.toString() })

        val view = shadowOf(n.actions[0].actionIntent)
        assertTrue("the View button opens the app", view.isActivityIntent)
        assertEquals("view", view.savedIntent.getStringExtra("action_id"))
        assertEquals("open_url", view.savedIntent.getStringExtra("action_type"))
        assertEquals("https://example.test/p", view.savedIntent.getStringExtra("action_value"))
        assertEquals("1", view.savedIntent.getStringExtra("appdna"))

        val later = shadowOf(n.actions[1].actionIntent)
        assertTrue("dismiss does not open the app", later.isBroadcastIntent)
        assertEquals(PushActionReceiver.ACTION_PUSH_ACTION, later.savedIntent.action)

        val reply = n.actions[2]
        assertTrue("foreground:false reply is handled without opening the app", shadowOf(reply.actionIntent).isBroadcastIntent)
        val inputs = reply.remoteInputs
        assertNotNull(inputs)
        assertEquals(PushNotificationActions.REPLY_KEY, inputs!!.single().resultKey)
        assertEquals("Type a reply", inputs.single().label.toString())
    }

    @Test
    fun `a dismiss button tap cancels the notification and reports the tap with its action id, without routing`() {
        val n = post(data())
        val intent = shadowOf(n.actions[1].actionIntent).savedIntent
        PushActionReceiver().onReceive(ctx, intent)
        idle()
        assertTrue("the notification is removed", notifications().isEmpty())
        assertEquals("later", h.tapped.single().second)
        val tapped = h.events().single { it.first == "push_tapped" }.second
        assertEquals("later", tapped.optString("action"))
        assertTrue("dismiss routes nowhere", h.routes.isEmpty())
    }

    @Test
    fun `a reply reaches onPushTapped as data reply_text`() {
        val n = post(data())
        val intent = Intent(shadowOf(n.actions[2].actionIntent).savedIntent)
        RemoteInput.addResultsToIntent(
            arrayOf(RemoteInput.Builder(PushNotificationActions.REPLY_KEY).build()),
            intent,
            Bundle().apply { putCharSequence(PushNotificationActions.REPLY_KEY, "See you there") },
        )
        PushActionReceiver().onReceive(ctx, intent)
        idle()
        val (payload, actionId) = h.tapped.single()
        assertEquals("reply", actionId)
        assertEquals("See you there", payload.data?.get("reply_text"))
        assertTrue(notifications().isEmpty())
    }

    @Test
    fun `an open-app button tap through handlePushTap routes the button's open_url as a deep link and cancels the notification`() {
        val n = post(data())
        val intent = Intent(shadowOf(n.actions[0].actionIntent).savedIntent)
        assertTrue(AppDNA.handlePushTap(intent))
        idle()
        assertEquals("view", h.tapped.single().second)
        assertEquals(listOf("deep_link" to "https://example.test/p"), h.routes)
        assertTrue(notifications().isEmpty())
        assertNull("the notification id extra is not handed to the host", h.tapped.single().first.data?.get(PushNotificationActions.EXTRA_NOTIFICATION_ID))
    }

    @Test
    fun `a bundled custom sound posts on its own appdna_sound channel created with that sound`() {
        PushSoundChannels.rawResourceId = { _, key -> if (key == "chime") 0x7f0f0001 else 0 }
        val n = post(data(mapOf("sound" to "Chime.wav")))
        assertEquals("appdna_sound_chime", n.channelId)
        val channel = nm.getNotificationChannel("appdna_sound_chime")
        assertNotNull(channel)
        assertEquals("android.resource://${ctx.packageName}/${0x7f0f0001}", channel.sound.toString())
    }

    @Test
    fun `a sound that is not bundled keeps the usual channel`() {
        val n = post(data(mapOf("sound" to "missing.wav")))
        assertEquals(AppDNAMessagingService.DEFAULT_CHANNEL_ID, n.channelId)
        assertNull(nm.getNotificationChannel("appdna_sound_missing"))
    }

    @Test
    fun `a forwarded message with a sound creates the channel even when the SDK does not display it`() {
        PushSoundChannels.rawResourceId = { _, key -> if (key == "chime") 0x7f0f0001 else 0 }
        // handleMessageData never displays (the host / FCM does): the channel must exist for FCM's own
        // display of the next push, which names it in android.notification.channel_id.
        assertTrue(AppDNA.push.handleMessageData(data(mapOf("sound" to "chime.wav"))))
        idle()
        assertTrue(notifications().isEmpty())
        assertNotNull(nm.getNotificationChannel("appdna_sound_chime"))
    }

    /**
     * The server keeps the `notification` block; in the foreground such a message reaches the service,
     * which displays it through the same builder — with the push's buttons and sound.
     */
    @Test
    fun `a foreground notification-block message gets the buttons and the sound channel`() {
        PushSoundChannels.rawResourceId = { _, key -> if (key == "chime") 0x7f0f0001 else 0 }
        val b = android.os.Bundle()
        (data(mapOf("sound" to "chime.wav")) - "title" - "body").forEach { (k, v) -> b.putString(k, v) }
        b.putString("gcm.n.e", "1")
        b.putString("gcm.n.title", "T")
        b.putString("gcm.n.body", "B")
        service.onMessageReceived(RemoteMessage(b))
        idle()
        val n = notifications().single()
        assertEquals(listOf("View", "Later", "Reply"), n.actions.map { it.title.toString() })
        assertEquals("appdna_sound_chime", n.channelId)
    }

    /** handleMessage (a host that owns Firebase Messaging but lets AppDNA display) uses the same builder. */
    @Test
    fun `a host-forwarded handleMessage gets the buttons too`() {
        val b = android.os.Bundle()
        data().forEach { (k, v) -> b.putString(k, v) }
        assertTrue(AppDNA.push.handleMessage(RemoteMessage(b)))
        idle()
        assertEquals(3, notifications().single().actions.size)
    }

    private val lowCampaign = mapOf("channel_importance" to "low", "channel_name" to "Offers", "channel_description" to "Promotional pushes")

    /**
     * Round 19 — a campaign's channel settings describe its custom-sound channel only. NEGATIVE CONTROL: the
     * default `appdna_push` channel was created from the FIRST push's `channel_*` keys, so one Low campaign
     * fixed it at Low (named "Offers") and every later push without a custom sound posted silently.
     */
    @Test
    fun `the default channel ignores a push's channel settings and keeps the SDK's own`() {
        post(data(lowCampaign))
        service.onMessageReceived(RemoteMessage.Builder("test@fcm").setData(data(mapOf("delivery_id" to "d2"))).build())
        idle()
        assertTrue(notifications().all { it.channelId == AppDNAMessagingService.DEFAULT_CHANNEL_ID })
        val channel = nm.getNotificationChannel(AppDNAMessagingService.DEFAULT_CHANNEL_ID)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
        assertEquals("Push Notifications", channel.name.toString())
        assertNull(channel.description)
    }

    /**
     * Round 20 — a channel a push's custom data names (`data.channel_id`). NEGATIVE CONTROL: it was created as
     * "Push Notifications", the default channel's name, so the system settings showed two channels of one name.
     */
    @Test
    fun `a custom-data channel is named after its id, and the default keeps its name`() {
        val n = post(data(lowCampaign + ("channel_id" to "orders")))
        assertEquals("orders", n.channelId)
        val channel = nm.getNotificationChannel("orders")
        assertEquals("Push Notifications (orders)", channel.name.toString())
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
        assertNull(channel.description)
        service.onMessageReceived(RemoteMessage.Builder("test@fcm").setData(data(mapOf("delivery_id" to "d2", "push_id" to "p2"))).build())
        idle()
        assertEquals("Push Notifications", nm.getNotificationChannel(AppDNAMessagingService.DEFAULT_CHANNEL_ID).name.toString())
    }

    @Test
    fun `the custom-sound channel takes the push's channel settings`() {
        PushSoundChannels.rawResourceId = { _, key -> if (key == "chime") 0x7f0f0001 else 0 }
        val n = post(data(lowCampaign + ("sound" to "chime.wav")))
        assertEquals("appdna_sound_chime", n.channelId)
        val channel = nm.getNotificationChannel("appdna_sound_chime")
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        assertEquals("Offers (chime)", channel.name.toString())
        assertEquals("Promotional pushes", channel.description)
        assertNull("a sound push does not create the default channel from its settings either",
            nm.getNotificationChannel(AppDNAMessagingService.DEFAULT_CHANNEL_ID)?.takeIf { it.importance != NotificationManager.IMPORTANCE_HIGH })
    }

    @Test
    fun `sound keys match the server rule`() {
        assertEquals("notification_chime", PushSoundChannels.soundKey("Notification-Chime.WAV"))
        assertEquals("chime", PushSoundChannels.soundKey("chime"))
        assertEquals("appdna_sound_chime", PushSoundChannels.channelId("chime"))
    }
}

/**
 * A background button tapped in a COLD process (the SDK is still bootstrapping): the receiver waits for
 * `configure` and then tracks the tap. NEGATIVE CONTROL: it handled the tap at once, with no push
 * pipeline — the tap was claimed untracked and never reported, and `onPushTapped` never fired.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PushActionReceiverColdProcessTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        PushActionReceiver.isReady = { AppDNA.push.manager != null }
        PushActionReceiver.awaitReady = { callback -> AppDNA.onReady(callback) }
    }

    @Test
    fun `a tap before configure is tracked once the SDK is ready`() {
        val h = PushTestHarness(ctx, "cold-tap")
        try {
            var ready = false
            var onReady: (() -> Unit)? = null
            PushActionReceiver.isReady = { ready }
            PushActionReceiver.awaitReady = { onReady = it }
            val intent = Intent(ctx, PushActionReceiver::class.java).apply {
                action = PushActionReceiver.ACTION_PUSH_ACTION
                putExtra("appdna", "1"); putExtra("push_id", "p1"); putExtra("delivery_id", "d-cold")
                putExtra("action_id", "later"); putExtra("action_type", "dismiss")
            }
            PushActionReceiver().onReceive(ctx, intent)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue("nothing handled before the SDK is ready", h.tapped.isEmpty())

            ready = true
            onReady!!.invoke()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("later", h.tapped.single().second)
            assertEquals("later", h.events().single { it.first == "push_tapped" }.second.optString("action"))

            // The timeout fallback afterwards does not handle it a second time.
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(PushActionReceiver.READY_WAIT_MS + 1))
            assertEquals(1, h.tapped.size)
        } finally {
            h.close()
        }
    }
}
