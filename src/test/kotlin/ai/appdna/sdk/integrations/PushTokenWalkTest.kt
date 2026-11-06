package ai.appdna.sdk.integrations

import ai.appdna.sdk.IdentityManager
import ai.appdna.sdk.events.EventTracker
import ai.appdna.sdk.network.ApiClient
import ai.appdna.sdk.storage.LocalStorage
import androidx.test.core.app.ApplicationProvider
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A device-side walk of token registration against a REAL server: the SDK's own [PushTokenManager] and [ApiClient]
 * register one token with a live AppDNA server — anonymously, then after identify, then after reset — and the server's
 * stored row is read back outside this test.
 *
 * Opt-in: it runs only when `APPDNA_WALK_BASE_URL`, `APPDNA_WALK_API_KEY`, `APPDNA_WALK_TOKEN` and
 * `APPDNA_WALK_ANON_ID` are set (a local server on a throwaway database), and is skipped otherwise. `APPDNA_WALK_STEP`
 * picks the step: `anon`, `identify` or `reset` (one registration each, so the row can be read between them).
 * `APPDNA_WALK_NO_IDENTITY=1` sends NO identity — the body an SDK without the fix sent — as the red control.
 */
@RunWith(RobolectricTestRunner::class)
class PushTokenWalkTest {
    @Test
    fun `register a token with a live server`() {
        val base = System.getenv("APPDNA_WALK_BASE_URL")
        val key = System.getenv("APPDNA_WALK_API_KEY")
        val token = System.getenv("APPDNA_WALK_TOKEN")
        val anon = System.getenv("APPDNA_WALK_ANON_ID")
        val step = System.getenv("APPDNA_WALK_STEP") ?: "anon"
        assumeTrue("walk env not set", base != null && key != null && token != null && anon != null)
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val storage = LocalStorage(ctx)
        val userId = if (step == "identify") System.getenv("APPDNA_WALK_USER_ID") ?: "walk-user-1" else null
        val manager = PushTokenManager(
            ctx, storage, EventTracker(IdentityManager(storage), "1.0", "production"),
            ApiClient(key!!, base!!),
        ) { if (System.getenv("APPDNA_WALK_NO_IDENTITY") == "1") null to null else anon to userId }
        val before = System.currentTimeMillis()
        if (step == "anon") manager.setPushToken(token!!) else { storage.setString("push_token", token!!); manager.reregister() }
        // The registration runs on the manager's IO scope; give it time to land, then report.
        Thread.sleep(4000)
        println("[push-walk] step=$step token=$token anon=$anon user=$userId sent in ${System.currentTimeMillis() - before} ms")
    }
}
