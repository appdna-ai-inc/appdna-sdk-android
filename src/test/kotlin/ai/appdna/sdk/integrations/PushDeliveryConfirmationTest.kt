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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
 * #1786 — delivered is device-confirmed only (owner decision 147). On Android the device can confirm a push only when
 * the SDK's code runs as it arrives, so:
 *  - the token registration says whether the SDK's own service receives FCM messages (`push_display: "sdk"`), and the
 *    server then sends data-only messages;
 *  - a data-only message reaches the service with the app in the BACKGROUND too, and the SDK draws it and tracks
 *    `push_delivered` with its delivery id;
 *  - the authored small icon travels as `data.icon` and is drawn.
 * Red controls: a host's own service resolving → no `push_display`; a `notification` message in the background is not
 * handed to the service at all (FCM draws it), which is exactly why the probe must be right.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PushDeliveryConfirmationTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private lateinit var h: PushTestHarness

    @Before
    fun setUp() {
        h = PushTestHarness(ctx, "confirm")
        val cn = ComponentName(ctx.packageName, "${ctx.packageName}.LaunchActivity")
        shadowOf(ctx.packageManager).addActivityIfNotPresent(cn)
        shadowOf(ctx.packageManager).addIntentFilterForActivity(
            cn,
            IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) },
        )
    }

    @After
    fun tearDown() {
        PushDisplayProbe.resolvedServiceOverride = null
        h.close()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun notifications(): List<Notification> =
        shadowOf(ctx.getSystemService(NotificationManager::class.java)).allNotifications

    /** A subclass a host may register instead of the SDK's own service. */
    class HostSubclassService : AppDNAMessagingService()

    @Test
    fun `the SDK's service, or a subclass of it, resolving reports push_display sdk`() {
        PushDisplayProbe.resolvedServiceOverride = { AppDNAMessagingService::class.java.name }
        assertEquals("sdk", PushDisplayProbe.pushDisplay(ctx))
        PushDisplayProbe.resolvedServiceOverride = { HostSubclassService::class.java.name }
        assertEquals("sdk", PushDisplayProbe.pushDisplay(ctx))
    }

    @Test
    fun `red control - a host's own FirebaseMessagingService resolving reports nothing`() {
        PushDisplayProbe.resolvedServiceOverride = { com.google.firebase.messaging.FirebaseMessagingService::class.java.name }
        assertNull(PushDisplayProbe.pushDisplay(ctx))
        PushDisplayProbe.resolvedServiceOverride = { "com.example.host.Missing" }
        assertNull(PushDisplayProbe.pushDisplay(ctx))
    }

    @Test
    fun `the real package manager resolves the SDK's manifest service`() {
        // No override: the library's merged manifest declares AppDNAMessagingService for MESSAGING_EVENT.
        val intent = Intent(PushDisplayProbe.MESSAGING_EVENT).setPackage(ctx.packageName)
        shadowOf(ctx.packageManager).addServiceIfNotPresent(ComponentName(ctx.packageName, AppDNAMessagingService::class.java.name))
        shadowOf(ctx.packageManager).addIntentFilterForService(
            ComponentName(ctx.packageName, AppDNAMessagingService::class.java.name),
            IntentFilter(PushDisplayProbe.MESSAGING_EVENT),
        )
        assertEquals(AppDNAMessagingService::class.java.name, ctx.packageManager.resolveService(intent, 0)?.serviceInfo?.name)
        assertEquals("sdk", PushDisplayProbe.pushDisplay(ctx))
    }

    @Test
    fun `the registration body carries push_display only when given`() {
        val with = PushTokenRegistration.body("t", "android", "d", "1", "2", "14.0.0", "a", null, pushDisplay = "sdk")
        assertEquals("sdk", with.getString("push_display"))
        val without = PushTokenRegistration.body("t", "android", "d", "1", "2", "14.0.0", "a", null, pushDisplay = null)
        assertFalse(without.has("push_display"))
    }

    @Test
    fun `a data-only push in the background is drawn by the SDK and confirmed delivered`() {
        val am = ctx.getSystemService(android.app.ActivityManager::class.java)
        // The app's process is NOT in the foreground.
        shadowOf(am).setProcesses(listOf(android.app.ActivityManager.RunningAppProcessInfo(ctx.packageName, 1, arrayOf(ctx.packageName)).apply {
            importance = android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED
        }))
        assertFalse(AppDNAMessagingService.isAppInForeground(ctx))

        val service = Robolectric.setupService(AppDNAMessagingService::class.java)
        service.onMessageReceived(
            RemoteMessage.Builder("bg@fcm").setData(
                mapOf("appdna" to "1", "push_id" to "p1", "delivery_id" to "d-bg", "title" to "Bg title", "body" to "Bg body"),
            ).build(),
        )
        idle()
        val ev = h.events()
        assertEquals(listOf("push_delivered"), ev.map { it.first })
        assertEquals("d-bg", ev[0].second.getString("delivery_id"))
        assertEquals(1, notifications().size)
        assertEquals("Bg title", notifications()[0].extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
        assertEquals(listOf(false), h.received.map { it.second })
    }

    @Test
    fun `the authored small icon in data is drawn, and an unknown name falls back`() {
        val authored = ctx.resources.getIdentifier("appdna_google_g", "drawable", ctx.packageName)
        assertNotEquals("the test resource exists", 0, authored)
        assertEquals(authored, NotificationIconResolver.resolve(ctx, "appdna_google_g"))
        val fallback = NotificationIconResolver.resolve(ctx, null)
        assertEquals(fallback, NotificationIconResolver.resolve(ctx, "no_such_drawable_1786"))
        assertNotEquals(authored, fallback)

        val n = AppDNANotificationPoster.build(ctx, mapOf("appdna" to "1", "push_id" to "p", "delivery_id" to "d-icon", "title" to "T", "body" to "B", "icon" to "appdna_google_g"), null, null)
        assertEquals(authored, n.smallIcon.resId)
        assertTrue(n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() == "T")
    }
}
