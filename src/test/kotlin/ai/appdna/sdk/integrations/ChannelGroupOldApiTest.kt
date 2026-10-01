package ai.appdna.sdk.integrations

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A push with `data.channel_group` on Android 8.0 / 8.1 (API 26 / 27) must not crash.
 *
 * `NotificationManager.getNotificationChannelGroup(String)` is API 28, and both channel helpers called it
 * under an API 26 guard only: on a real 8.x device the first such push threw `NoSuchMethodError` in the
 * messaging service. Robolectric may or may not reproduce the missing method, so the real guard is the
 * Android lint `NewApi` gate in sdk-ci.yml; this test pins the behaviour on each side of API 28 —
 * the group is created once and the channel is filed under it.
 */
@RunWith(RobolectricTestRunner::class)
class ChannelGroupOldApiTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val nm: NotificationManager get() = ctx.getSystemService(NotificationManager::class.java)

    private fun data(pushId: String) = mapOf(
        "appdna" to "1", "push_id" to pushId, "delivery_id" to "d-$pushId",
        "channel_id" to "orders", "channel_group" to "shop",
    )

    private fun assertFiledUnderShop() {
        assertEquals("the group is created exactly once", listOf("shop"), nm.notificationChannelGroups.map { it.id })
        val channel = nm.getNotificationChannel("orders")
        assertNotNull("the channel is created", channel)
        assertEquals("shop", channel!!.group)
    }

    private fun postTwice() {
        AppDNANotificationPoster.post(ctx, data("p1"), "Your order", "has shipped")
        AppDNANotificationPoster.post(ctx, data("p2"), "Your order", "has shipped")
    }

    @Test
    @Config(sdk = [26])
    fun `api 26 - a push with channel_group posts and creates the group`() {
        postTwice()
        assertFiledUnderShop()
    }

    @Test
    @Config(sdk = [27])
    fun `api 27 - a push with channel_group posts and creates the group`() {
        postTwice()
        assertFiledUnderShop()
    }

    @Test
    @Config(sdk = [33])
    fun `api 33 - a push with channel_group posts and creates the group`() {
        postTwice()
        assertFiledUnderShop()
    }

    @Test
    @Config(sdk = [26])
    fun `api 26 - the rich-push channel helper creates the group too`() {
        RichPushHandler.ensureChannel(ctx, "orders", "Orders", group = "shop")
        RichPushHandler.ensureChannel(ctx, "returns", "Returns", group = "shop")
        assertEquals(listOf("shop"), nm.notificationChannelGroups.map { it.id })
        assertEquals("shop", nm.getNotificationChannel("orders").group)
        assertEquals("shop", nm.getNotificationChannel("returns").group)
    }

    @Test
    @Config(sdk = [26])
    fun `api 26 - an existing group is reused, not recreated`() {
        nm.createNotificationChannelGroup(android.app.NotificationChannelGroup("shop", "Shop (host)"))
        postTwice()
        assertEquals(listOf("shop"), nm.notificationChannelGroups.map { it.id })
        assertEquals("the host's group keeps its name", "Shop (host)", nm.notificationChannelGroups.single().name.toString())
    }
}
