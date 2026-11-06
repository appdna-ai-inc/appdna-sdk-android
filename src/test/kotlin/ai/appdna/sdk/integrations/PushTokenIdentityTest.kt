package ai.appdna.sdk.integrations

import ai.appdna.sdk.IdentityManager
import ai.appdna.sdk.events.EventTracker
import ai.appdna.sdk.network.ApiClient
import ai.appdna.sdk.network.TestHttpServer
import ai.appdna.sdk.storage.LocalStorage
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The push-token registration REQUEST carries who the token belongs to, through the real [PushTokenManager]
 * and the real [ApiClient] against a loopback server: `anon_id` always, `user_id` once identify ran. A
 * re-registration (what identify and reset trigger) sends the identity as it is now. Before, the body had
 * neither, and the server stored every token as user `anonymous`.
 */
@RunWith(RobolectricTestRunner::class)
class PushTokenIdentityTest {
    private fun tokenBodies(srv: TestHttpServer, n: Int): List<JSONObject> {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            val bodies = srv.requests.filter { it.first.contains("/api/v1/push/token") }.map { JSONObject(it.second) }
            if (bodies.size >= n) return bodies
            Thread.sleep(50)
        }
        throw AssertionError("expected $n token registrations, saw ${srv.requests}")
    }

    @Test
    fun `the token registration body carries anon_id and, once identified, user_id`() {
        TestHttpServer { _, _ -> 201 to """{"data":{"registered":true}}""" }.use { srv ->
            val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
            val storage = LocalStorage(ctx)
            var userId: String? = null
            val manager = PushTokenManager(
                ctx, storage, EventTracker(IdentityManager(storage), "1.0", "sandbox"),
                ApiClient("adn_test_push", srv.baseUrl),
            ) { "anon-synthetic-1" to userId }

            manager.setPushToken("fcm-synthetic-token")
            val first = tokenBodies(srv, 1)[0]
            assertEquals("anon-synthetic-1", first.optString("anon_id"))
            assertFalse("no user yet, so no user_id", first.has("user_id"))

            userId = "user-synthetic-9" // identify()
            manager.reregister()
            val second = tokenBodies(srv, 2)[1]
            assertEquals("fcm-synthetic-token", second.getString("token"))
            assertEquals("user-synthetic-9", second.optString("user_id"))
            assertEquals("anon-synthetic-1", second.optString("anon_id"))

            userId = null // reset()
            manager.reregister()
            val third = tokenBodies(srv, 3)[2]
            assertFalse("reset sends no user_id", third.has("user_id"))
        }
    }
}
