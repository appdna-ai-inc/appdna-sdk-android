package ai.appdna.sdk.integrations

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.google.firebase.messaging.RemoteMessage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * SPEC-497 §9.2 / §9.8 (B2) — `AppDNAMessagingService` handles ONLY AppDNA-marked messages.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppDNAMessagingServiceTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private lateinit var h: PushTestHarness
    private lateinit var service: AppDNAMessagingService

    @Before
    fun setUp() {
        h = PushTestHarness(ctx, "svc")
        // The notification's tap / button intents are built from the launch intent.
        val cn = ComponentName(ctx.packageName, "${ctx.packageName}.LaunchActivity")
        shadowOf(ctx.packageManager).addActivityIfNotPresent(cn)
        shadowOf(ctx.packageManager).addIntentFilterForActivity(
            cn,
            IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) },
        )
        service = Robolectric.setupService(AppDNAMessagingService::class.java)
    }

    @After fun tearDown() = h.close()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun notifications(): List<Notification> =
        shadowOf(ctx.getSystemService(NotificationManager::class.java)).allNotifications
    private fun msg(data: Map<String, String>) = RemoteMessage.Builder("test@fcm").setData(data).build()

    @Test
    fun `a non-AppDNA message is ignored - no tracking, no delegate, no notification`() {
        service.onMessageReceived(msg(mapOf("push_id" to "host_1", "title" to "Host", "body" to "Their push")))
        idle()
        assertTrue(h.events().isEmpty())
        assertTrue(h.received.isEmpty())
        assertTrue(notifications().isEmpty())
    }

    @Test
    fun `a marked data-only message with blank title and body posts no notification`() {
        service.onMessageReceived(msg(mapOf("appdna" to "1", "push_id" to "p1", "delivery_id" to "d1")))
        idle()
        assertEquals(listOf("push_delivered"), h.events().map { it.first })
        assertTrue(notifications().isEmpty())
    }

    @Test
    fun `a marked message tracks one delivered with delivery_id and is displayed`() {
        service.onMessageReceived(
            msg(mapOf("appdna" to "1", "push_id" to "p1", "delivery_id" to "d1", "title" to "T", "body" to "B")),
        )
        idle()
        val ev = h.events()
        assertEquals(1, ev.size)
        assertEquals("push_delivered", ev[0].first)
        assertEquals("d1", ev[0].second.getString("delivery_id"))
        assertEquals(1, h.received.size)
        assertEquals(1, notifications().size)
        // The body tap intent carries the marker and the delivery id, so handlePushTap accepts it.
        val tap = shadowOf(notifications()[0].contentIntent).savedIntent
        assertEquals("1", tap.getStringExtra("appdna"))
        assertEquals("d1", tap.getStringExtra("delivery_id"))
    }

    /** An FCM message with a `notification` block, as FCM hands it to the service (foreground only). */
    private fun notificationMsg(data: Map<String, String>, title: String, body: String): RemoteMessage {
        val b = android.os.Bundle()
        data.forEach { (k, v) -> b.putString(k, v) }
        b.putString("gcm.n.e", "1")
        b.putString("gcm.n.title", title)
        b.putString("gcm.n.body", body)
        return RemoteMessage(b)
    }

    /**
     * A marked message with a `notification` block reaches the service only in the foreground, where
     * FCM does not display it — so the SDK does, with the notification block's title and body, once.
     * NEGATIVE CONTROL: with the old `if (message.notification != null) return` back, nothing is posted.
     */
    @Test
    fun `a marked notification message received in the foreground is displayed once`() {
        val m = notificationMsg(mapOf("appdna" to "1", "push_id" to "p1", "delivery_id" to "d1"), "Hello", "World")
        assertTrue("the test message has a notification block", m.notification != null)
        service.onMessageReceived(m)
        idle()
        assertEquals(listOf("push_delivered"), h.events().map { it.first })
        assertEquals(1, notifications().size)
        val n = notifications()[0]
        assertEquals("Hello", n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
        assertEquals("World", n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
        assertEquals(AppDNAMessagingService.DEFAULT_CHANNEL_ID, n.channelId)
        // Delivered again (FCM redelivery / the host also forwarding it): not displayed twice.
        service.onMessageReceived(m)
        idle()
        assertEquals(1, notifications().size)
    }

    /**
     * A host that displays the message itself forwards it through `handleMessageData` first; the SDK's
     * service then sees a duplicate and does not display it a second time.
     */
    @Test
    fun `a notification message the host already handled is not displayed by the service`() {
        val data = mapOf("appdna" to "1", "push_id" to "p2", "delivery_id" to "d2")
        assertTrue(ai.appdna.sdk.AppDNA.push.handleMessageData(data))
        service.onMessageReceived(notificationMsg(data, "Hello", "World"))
        idle()
        assertTrue(notifications().isEmpty())
        assertEquals(1, h.events().size)
    }

    @Test
    fun `two notifications with a same-id button keep their own pending intents and extras`() {
        val actions = """[{"id":"view","label":"View","type":"deep_link","value":"x://v"}]"""
        service.onMessageReceived(
            msg(mapOf("appdna" to "1", "push_id" to "pA", "delivery_id" to "dA", "title" to "A", "actions" to actions)),
        )
        service.onMessageReceived(
            msg(mapOf("appdna" to "1", "push_id" to "pB", "delivery_id" to "dB", "title" to "B", "actions" to actions)),
        )
        idle()
        val posted = notifications()
        assertEquals(2, posted.size)
        val buttons = posted.map { it.actions.single().actionIntent }
        assertNotEquals(buttons[0], buttons[1])
        val pushIds = buttons.map { shadowOf(it).savedIntent.getStringExtra("push_id") }.toSet()
        assertEquals(setOf("pA", "pB"), pushIds)
        buttons.forEach {
            val extras = shadowOf(it).savedIntent
            assertEquals("1", extras.getStringExtra("appdna"))
            assertEquals("view", extras.getStringExtra("action_id"))
        }
    }

    @Test
    fun `two deliveries of one campaign are two notifications`() {
        val base = mapOf("appdna" to "1", "push_id" to "camp", "title" to "T")
        service.onMessageReceived(msg(base + ("delivery_id" to "d1")))
        service.onMessageReceived(msg(base + ("delivery_id" to "d2")))
        idle()
        assertEquals(2, notifications().size)
    }

    @Test
    fun `the manifest declares the service with the resource switch, default on`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val service = Regex("""<service[^>]*AppDNAMessagingService[^>]*>""").find(manifest)?.value
            ?: error("AppDNAMessagingService not declared in the SDK manifest")
        assertTrue(service, service.contains("""android:enabled="@bool/appdna_messaging_service_enabled""""))
        val bools = File("src/main/res/values/appdna_bools.xml").readText()
        assertTrue(bools.contains("""<bool name="appdna_messaging_service_enabled">true</bool>"""))
    }
}
