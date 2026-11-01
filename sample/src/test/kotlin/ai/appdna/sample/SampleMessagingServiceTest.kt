package ai.appdna.sample

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.os.Bundle
import android.os.Looper
import com.google.firebase.messaging.RemoteMessage
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The sample is the app's `FirebaseMessagingService`, so FCM hands it every message, AppDNA's included.
 * It must forward an AppDNA message to the SDK, which tracks it AND displays it, and leave every other
 * message alone. Driven through the sample's REAL service and the SDK configured by the sample's REAL
 * `SampleApp`; what is read back is the notifications the system was actually asked to post.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SampleMessagingServiceTest {
    private lateinit var app: Application
    private lateinit var nm: NotificationManager

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        nm = app.getSystemService(NotificationManager::class.java)
    }

    private fun deliver(vararg data: Pair<String, String>) {
        val bundle = Bundle().apply { data.forEach { (k, v) -> putString(k, v) } }
        SampleMessagingService().onMessageReceived(RemoteMessage(bundle))
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun posted(): List<Notification> = shadowOf(nm).allNotifications

    /*
     * One test, not two: `AppDNA` is a process-wide singleton that keeps the context of the first
     * `SampleApp` configured, while Robolectric builds a fresh Application per test, so a second test
     * would read a NotificationManager the SDK never posts to.
     */
    @Test
    fun `an AppDNA message is displayed with its title and body, and any other message is left alone`() {
        deliver("push_id" to "other-push", "title" to "Other sender", "body" to "Not for the SDK")
        assertEquals("a message without the AppDNA marker is not the SDK's", 0, posted().size)

        deliver("appdna" to "1", "push_id" to "sample-push", "delivery_id" to "sample-delivery", "title" to "Sample title", "body" to "Sample body")
        val shown = posted()
        assertEquals(1, shown.size)
        assertEquals("Sample title", shown[0].extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
        assertEquals("Sample body", shown[0].extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
    }
}
