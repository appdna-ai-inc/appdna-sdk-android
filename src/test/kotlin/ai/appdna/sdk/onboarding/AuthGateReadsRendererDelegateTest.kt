package ai.appdna.sdk.onboarding

import ai.appdna.sdk.AppDNA
import ai.appdna.sdk.Environment
import ai.appdna.sdk.events.ClientSeqCounter
import ai.appdna.sdk.events.DroppedEventsCounter
import ai.appdna.sdk.events.EventQueue
import ai.appdna.sdk.events.EventTracker
import ai.appdna.sdk.network.ApiClient
import ai.appdna.sdk.storage.EventDatabase
import ai.appdna.sdk.storage.LocalStorage
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * TWO bugs in the onboarding auth gate, both invisible to every existing test.
 *
 * 🔴 (1) THE GATE READ A DIFFERENT REFERENCE THAN THE RENDERER.
 * The gate asked `AppDNA.onboarding.listener` (the GLOBAL, set only by `setDelegate`). The renderer
 * holds `viewModel.delegate` — the one actually handed to `present`, and the one
 * `onBeforeStepAdvance` is invoked on. A native Kotlin host doing exactly what the docs say:
 *
 *     AppDNA.presentOnboarding(activity, "welcome", myDelegate)   // and nothing else
 *
 * got a renderer WITH a delegate (veto seam live) and a gate that saw NO delegate. Every
 * `email_login` / `request_otp` / `social_login` step then refused to advance FOREVER, with the
 * delegate sitting right there. The permission callbacks (`onPermissionRequest` /
 * `onPermissionResult`) read the same global and simply never fired for that host.
 *
 * 🔴 (2) IT EMITTED A COMPLETION FOR A STEP THAT DID NOT COMPLETE.
 * On that blocked path Android emitted `onboarding_step_completed` with `blocked_reason:
 * "no_delegate"` — a property no dbt model in the warehouse has ever heard of (`grep -rn
 * blocked_reason` outside the SDK: 0 hits), so nothing filtered it out. Android's step-completion
 * counts and onboarding funnel conversion were inflated by every misconfigured auth tap.
 *
 * Falsification, per test, is in each test's own comment.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AuthGateReadsRendererDelegateTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: EventDatabase
    private lateinit var tracker: EventTracker

    /** A delegate the host passed to `present` — never registered globally. */
    private class HostDelegate : AppDNAOnboardingDelegate

    @Before
    fun setUp() {
        val storage = LocalStorage(ctx)
        val identity = ai.appdna.sdk.IdentityManager(storage)
        db = EventDatabase(ctx, 10_000, "auth_gate_test.db")
        ClientSeqCounter.init(ctx)
        DroppedEventsCounter.init(ctx)
        db.clearAll()
        tracker = EventTracker(identity, "1.0", "sandbox")
        tracker.setEventQueue(
            EventQueue(
                apiClient = ApiClient("adn_test_auth_gate", Environment.PRODUCTION),
                eventDatabase = db,
                connectivityMonitor = null,
                batchSizeCap = 0,
                flushInterval = Long.MAX_VALUE,
            ),
        )
        AppDNA.installEventTrackerForTest(tracker)
        // THE POINT: the global listener is EMPTY. Only the renderer has the delegate.
        AppDNA.onboarding.setDelegate(null)
    }

    @After
    fun tearDown() {
        AppDNA.installEventTrackerForTest(null)
        AppDNA.onboarding.setDelegate(null)
        db.clearAll()
    }

    private fun events(): List<Pair<String, JSONObject?>> =
        db.loadAll().map { JSONObject(it) }.map { it.getString("event_name") to it.optJSONObject("properties") }

    private fun countOf(name: String) = events().count { it.first == name }

    // ---- (1) the gate must read the RENDERER's delegate ----

    /**
     * Falsification: restore `val hasDelegate = AppDNA.onboarding.listener != null`.
     * The global is null here → the auth action is blocked → `advanced` stays null → RED.
     */
    @Test
    fun `an auth action advances when the RENDERER holds the delegate and the global does not`() {
        var advanced: Map<String, Any>? = null
        val errors = mutableListOf<String>()

        emitAuthAction(
            action = "email_login",
            actionValue = null,
            toggleValues = emptyMap(),
            inputValues = mapOf("email" to "a@b.com"),
            onNext = { advanced = it },
            onError = { errors.add(it) },
            delegate = HostDelegate(),
        )

        assertNotNull("the renderer HAS a delegate — the step must advance", advanced)
        assertEquals("email_login", advanced!!["action"])
        assertEquals("a@b.com", advanced!!["email"])
        assertTrue("no error pill should be shown", errors.isEmpty())
    }

    /** Same for `social_login`, whose gate was a second, separately-written copy of the same bug. */
    @Test
    fun `social_login advances when the RENDERER holds the delegate and the global does not`() {
        var advanced: Map<String, Any>? = null
        val errors = mutableListOf<String>()

        emitSocialLoginAction(
            provider = "google",
            inputValues = emptyMap(),
            onNext = { advanced = it },
            onError = { errors.add(it) },
            delegate = HostDelegate(),
        )

        assertNotNull("the renderer HAS a delegate — social login must advance", advanced)
        assertEquals("social_login", advanced!!["action"])
        assertEquals("google", advanced!!["provider"])
        assertTrue(errors.isEmpty())
    }

    /**
     * The SDK's own keys win over the step's inputs: a form field whose id is `action` or `provider`
     * cannot replace `action: "social_login"` / the provider the host switches on. Other inputs pass
     * through. NEGATIVE CONTROL: with `putAll(inputValues)` back after the SDK keys, `action` is
     * "not_social" — this test fails.
     */
    @Test
    fun `social_login - an input named action or provider cannot override the SDK keys`() {
        var advanced: Map<String, Any>? = null
        emitSocialLoginAction(
            provider = "google",
            inputValues = mapOf("action" to "not_social", "provider" to "evil", "email" to "a@b.co"),
            onNext = { advanced = it },
            delegate = HostDelegate(),
        )
        assertEquals("social_login", advanced!!["action"])
        assertEquals("google", advanced!!["provider"])
        assertEquals("a@b.co", advanced!!["email"])
    }

    /** The gate still does its job: no delegate anywhere → no advance, and the user is TOLD. */
    @Test
    fun `an auth action with no delegate at all still refuses to advance`() {
        var advanced: Map<String, Any>? = null
        val errors = mutableListOf<String>()

        emitAuthAction(
            action = "email_login",
            actionValue = null,
            toggleValues = emptyMap(),
            inputValues = mapOf("email" to "a@b.com", "password" to "hunter2"),
            onNext = { advanced = it },
            onError = { errors.add(it) },
            delegate = null,
        )

        assertNull("credentials must not flow into responses with nobody authenticating", advanced)
        assertEquals(1, errors.size)
    }

    // ---- (2) a step that did not complete must not report a completion ----

    /**
     * Falsification: restore the `AppDNA.track("onboarding_step_completed", … "blocked_reason" to
     * "no_delegate")` emit inside the gate → `onboarding_step_completed` count becomes 1 → RED.
     */
    @Test
    fun `the blocked auth path emits NO onboarding_step_completed`() {
        emitAuthAction(
            action = "request_otp",
            actionValue = "sms",
            toggleValues = emptyMap(),
            inputValues = mapOf("phone" to "+15551234567"),
            onNext = { throw AssertionError("must not advance") },
            onError = { },
            delegate = null,
        )

        assertEquals(
            "the step did NOT complete — emitting a completion inflates the Android funnel",
            0,
            countOf("onboarding_step_completed"),
        )
        assertTrue(
            "`blocked_reason` is a property no dbt model has ever heard of",
            events().none { it.second?.has("blocked_reason") == true },
        )
    }

    /** The social_login copy of the same fake emit. */
    @Test
    fun `the blocked social_login path emits NO onboarding_step_completed`() {
        val errors = mutableListOf<String>()
        emitSocialLoginAction(
            provider = "apple",
            inputValues = emptyMap(),
            onNext = { throw AssertionError("must not advance") },
            onError = { errors.add(it) },
            delegate = null,
        )

        assertEquals(0, countOf("onboarding_step_completed"))
        // iOS shows an error toast on this path (OnboardingRenderer.swift showErrorToast); Android's
        // social_login branch used to log a warning the USER never saw — a dead button.
        assertEquals(1, errors.size)
    }

    // ---- (3) the permission callbacks: no reachable seam, so assert the SOURCE ----

    /**
     * `onPermissionRequest` / `onPermissionResult` are fired from closures inside the @Composable
     * `BlockBasedStepView`, which no unit test can enter. They read the SAME global the auth gate
     * did. This asserts the file no longer reads that global ANYWHERE — which is the property that
     * was violated, and it goes RED the moment anyone reintroduces the read, in any of the three
     * call sites or a fourth.
     */
    @Test
    fun `the onboarding renderer never reads the global onboarding listener`() {
        val source = File("src/main/kotlin/ai/appdna/sdk/onboarding/OnboardingActivity.kt").readText()
        val offenders = source.lines()
            .withIndex()
            .filter { (_, line) ->
                line.contains("onboarding.listener") &&
                    !line.trimStart().startsWith("//") &&
                    !line.trimStart().startsWith("*")
            }
            .map { (i, line) -> "${i + 1}: ${line.trim()}" }

        assertEquals(
            "the renderer must read the delegate it was GIVEN (`delegate`), never the global " +
                "`AppDNA.onboarding.listener` — they are different references:\n" +
                offenders.joinToString("\n"),
            emptyList<String>(),
            offenders,
        )
    }
}
