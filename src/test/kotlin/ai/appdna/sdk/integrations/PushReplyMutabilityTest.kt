package ai.appdna.sdk.integrations

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.google.firebase.messaging.RemoteMessage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A `text_reply` button's PendingIntent must be mutable — the system fills the typed text into it. On
 * API 31+ that is `FLAG_MUTABLE`; below 31 it is NO mutability flag (mutable by default). NEGATIVE
 * CONTROL: the reply was `FLAG_IMMUTABLE` below 31, so on Android 7–11 the typed text never reached
 * `onPushTapped`. Every other button stays `FLAG_IMMUTABLE`. Runs on API 29, 30 and 34.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 30, 34])
class PushReplyMutabilityTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private lateinit var h: PushTestHarness

    @Before
    fun setUp() {
        h = PushTestHarness(ctx, "reply-mutability")
        val cn = ComponentName(ctx.packageName, "${ctx.packageName}.LaunchActivity")
        shadowOf(ctx.packageManager).addActivityIfNotPresent(cn)
        shadowOf(ctx.packageManager).addIntentFilterForActivity(
            cn,
            IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) },
        )
    }

    @After
    fun tearDown() = h.close()

    @Test
    fun `the reply PendingIntent is mutable on this API level and every other button is immutable`() {
        val actions = JSONArray()
            .put(JSONObject(mapOf("id" to "view", "label" to "View", "action_type" to "open_url", "action_value" to "https://example.test/p", "foreground" to true)))
            .put(JSONObject(mapOf("id" to "later", "label" to "Later", "action_type" to "dismiss", "foreground" to false)))
            .put(JSONObject(mapOf("id" to "reply", "label" to "Reply", "action_type" to "text_reply", "action_value" to "Type a reply", "foreground" to false)))
            .toString()
        val service = Robolectric.setupService(AppDNAMessagingService::class.java)
        service.onMessageReceived(RemoteMessage.Builder("test@fcm").setData(mapOf(
            "appdna" to "1", "push_id" to "p1", "delivery_id" to "d1", "title" to "T", "body" to "B", "actions" to actions,
        )).build())
        shadowOf(Looper.getMainLooper()).idle()
        val n = shadowOf(ctx.getSystemService(NotificationManager::class.java)).allNotifications.single()
        val flags = n.actions.map { shadowOf(it.actionIntent).flags }

        assertTrue("view is immutable", flags[0] and PendingIntent.FLAG_IMMUTABLE != 0)
        assertTrue("dismiss is immutable", flags[1] and PendingIntent.FLAG_IMMUTABLE != 0)
        val reply = flags[2]
        assertEquals("the reply is never immutable", 0, reply and PendingIntent.FLAG_IMMUTABLE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            assertTrue("API 31+: FLAG_MUTABLE", reply and PendingIntent.FLAG_MUTABLE != 0)
        } else {
            assertEquals("below 31: no mutability flag", 0, reply and PendingIntent.FLAG_MUTABLE)
        }
    }

    @Test
    fun `the mutability rule`() {
        assertEquals(PendingIntent.FLAG_IMMUTABLE, PushNotificationActions.pendingIntentMutability(false, 29))
        assertEquals(PendingIntent.FLAG_IMMUTABLE, PushNotificationActions.pendingIntentMutability(false, 34))
        assertEquals(0, PushNotificationActions.pendingIntentMutability(true, 24))
        assertEquals(0, PushNotificationActions.pendingIntentMutability(true, 30))
        assertEquals(PendingIntent.FLAG_MUTABLE, PushNotificationActions.pendingIntentMutability(true, 31))
    }
}
