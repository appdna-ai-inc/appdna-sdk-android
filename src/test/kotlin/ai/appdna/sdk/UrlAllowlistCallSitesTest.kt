package ai.appdna.sdk

import ai.appdna.sdk.core.URLSafety
import ai.appdna.sdk.screens.FlowConfig
import ai.appdna.sdk.screens.FlowSettings
import ai.appdna.sdk.screens.FlowManager
import ai.appdna.sdk.screens.SectionAction
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * The scheme allowlist, asserted at the CALL SITES.
 *
 * 🔴 `URLSafety` existed and was unit-tested, and the surfaces that matter most called straight past
 * it. `PaywallActivity` — the MONEY surface — built `Intent(ACTION_VIEW, Uri.parse(configString))`
 * by hand in three places (legal-block markdown links, the legal link row, and the sticky footer's
 * secondary action), and `FlowManager` did the same in both of its openers. A malicious or merely
 * compromised paywall config could therefore drive `javascript:`, `intent:`, `file:` or `content:`
 * navigation out of the app. The old tests asserted only that the HELPER refused those schemes —
 * which it did, faithfully, while nothing on the money path called it.
 *
 * So these tests assert two things the helper tests could not:
 *   1. the real `FlowManager.handleAction` — production code, entered the way the SDUI layer enters
 *      it — refuses a dangerous scheme and starts no Activity, and
 *   2. NO source file in the SDK builds a config-driven `ACTION_VIEW` intent by hand any more. That
 *      is the assertion that goes red the moment someone reintroduces the bypass, in any file,
 *      including the three Compose call sites in `PaywallActivity` that a unit test cannot click.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class UrlAllowlistCallSitesTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun newFlowManager(): FlowManager {
        val flow = FlowConfig(
            id = "flow_1",
            name = "test",
            screens = emptyList(),
            startScreenId = "",
            settings = FlowSettings(),
        )
        return FlowManager(flow, emptyMap()).also { it.contextProvider = { context } }
    }

    /** The Activity the SDK asked the OS to start, if any. */
    private fun startedActivity() =
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
            .nextStartedActivity

    // ---- (1) the REAL call site: FlowManager.handleAction ----

    @Test
    fun `a flow config naming a javascript URL starts nothing`() {
        val flow = newFlowManager()

        flow.handleAction(SectionAction.OpenURL("javascript:alert(document.cookie)"))

        assertNull(
            "a config-driven `javascript:` URL reached the OS — this is the in-app phishing hole",
            startedActivity(),
        )
    }

    @Test
    fun `a flow config naming an intent or file URL starts nothing`() {
        val flow = newFlowManager()

        flow.handleAction(SectionAction.DeepLink("intent://evil/#Intent;scheme=http;end"))
        assertNull("an `intent:` URL reached the OS", startedActivity())

        flow.handleAction(SectionAction.OpenWebview("file:///data/data/com.host/databases/x.db"))
        assertNull("a `file:` URL reached the OS", startedActivity())

        flow.handleAction(SectionAction.OpenURL("http://insecure.example.com"))
        assertNull("cleartext `http:` reached the OS", startedActivity())
    }

    @Test
    fun `an allowed https URL still opens — the guard must not break the feature`() {
        val flow = newFlowManager()

        flow.handleAction(SectionAction.OpenURL("https://appdna.ai/terms"))

        val intent = startedActivity()
        assertTrue("a legitimate https link no longer opens", intent != null)
        assertEquals(android.content.Intent.ACTION_VIEW, intent!!.action)
        assertEquals("https://appdna.ai/terms", intent.data.toString())
    }

    // ---- (2) no call site anywhere may build the intent by hand ----

    @Test
    fun `no SDK source builds a config-driven ACTION_VIEW intent outside the allowlist`() {
        // Matches exactly the bypass this defect was: an ACTION_VIEW intent whose Uri is parsed
        // INLINE from a string. A site that passes an already-`sanitized()` Uri does not match, and
        // is fine — that value came through the allowlist.
        val bypass = Regex("""ACTION_VIEW,\s*(android\.net\.)?Uri\.parse\(""")
        // Comments describe the bypass (this one included) without being it.
        val comments = Regex("""//[^\n]*|/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)

        val offenders = File("src/main/kotlin").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.name != "URLSafety.kt" } // the allowlist itself, by definition
            .filter { bypass.containsMatchIn(comments.replace(it.readText(), "")) }
            .map { it.path }
            .toList()

        assertEquals(
            "these files open a config-driven URL without the scheme allowlist — route them " +
                "through URLSafety.open()/sanitized(): $offenders",
            emptyList<String>(),
            offenders,
        )
    }

    // ---- the opener the call sites now share ----

    @Test
    fun `URLSafety open refuses a dangerous scheme and permits an allowed one`() {
        // `newTask` because this test drives it with an APPLICATION context; the paywall call sites
        // hold an Activity context (`LocalContext.current`) and do not need the flag.
        assertEquals(false, URLSafety.open(context, "javascript:alert(1)", newTask = true))
        assertNull(startedActivity())

        assertEquals(true, URLSafety.open(context, "https://appdna.ai/", newTask = true))
        assertEquals("https://appdna.ai/", startedActivity()!!.data.toString())
    }
}
