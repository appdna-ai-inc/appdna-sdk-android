package ai.appdna.sdk.integrations

import ai.appdna.sdk.IdentityManager
import ai.appdna.sdk.events.EventTracker
import ai.appdna.sdk.network.ApiClient
import ai.appdna.sdk.network.TestHttpServer
import ai.appdna.sdk.storage.LocalStorage
import androidx.test.core.app.ApplicationProvider
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The registration body says which Firebase project issued the token (`fcm_project_id`), through the real
 * [PushTokenManager] and [ApiClient] against a loopback server. The server sends each push with that project's
 * credential, so the attribution decides which key is used.
 *
 * RED CONTROL (by hand, the code before this change): no `fcm_project_id` in either body.
 */
@RunWith(RobolectricTestRunner::class)
class PushTokenFcmProjectTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    @After fun tearDown() { FirebaseApp.getApps(ctx).forEach { it.delete() } }

    private fun options(project: String) = FirebaseOptions.Builder()
        .setProjectId(project).setApplicationId("1:000000000000:android:0000000000000000").setApiKey("AIzaSynthetic-key-for-tests-000000000000").setGcmSenderId("000000000000").build()

    private fun tokenBodies(srv: TestHttpServer, n: Int): List<JSONObject> {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            val bodies = srv.requests.filter { it.first.contains("/api/v1/push/token") }.map { JSONObject(it.second) }
            if (bodies.size >= n) return bodies
            Thread.sleep(50)
        }
        throw AssertionError("expected $n token registrations, saw ${srv.requests}")
    }

    private fun manager(srv: TestHttpServer) = PushTokenManager(
        ctx, LocalStorage(ctx), EventTracker(IdentityManager(LocalStorage(ctx)), "1.0", "sandbox"),
        ApiClient("adn_test_push", srv.baseUrl),
    ) { "anon-synthetic-1" to null }

    @Test
    fun `a token handed in by the host is attributed to the default app's project`() {
        FirebaseApp.initializeApp(ctx, options("synthetic-host-project"))
        TestHttpServer { _, _ -> 201 to """{"data":{"registered":true}}""" }.use { srv ->
            manager(srv).setPushToken("host-synthetic-token")
            val body = tokenBodies(srv, 1)[0]
            assertEquals("host-synthetic-token", body.getString("token"))
            assertEquals("synthetic-host-project", body.getString("fcm_project_id"))
        }
    }

    @Test
    fun `no Firebase app at all - the field is absent, never guessed`() {
        TestHttpServer { _, _ -> 201 to """{"data":{"registered":true}}""" }.use { srv ->
            manager(srv).setPushToken("orphan-synthetic-token")
            assertFalse(tokenBodies(srv, 1)[0].has("fcm_project_id"))
        }
    }
}
