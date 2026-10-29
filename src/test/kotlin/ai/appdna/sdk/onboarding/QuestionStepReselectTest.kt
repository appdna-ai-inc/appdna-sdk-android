package ai.appdna.sdk.onboarding

import ai.appdna.sdk.AppDNA
import ai.appdna.sdk.billing.BillingTestPipeline
import ai.appdna.sdk.config.RemoteConfigManager
import ai.appdna.sdk.storage.LocalStorage
import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.os.Parcel
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * A question step the user already answered shows the answer again when they come back to it: the
 * chosen option (or options) is highlighted and Continue is enabled, as when they left it.
 *
 * Driven through the real activity: options and Continue are tapped through their click actions,
 * Back is the flow's own back control, and "highlighted" is read from each option card's
 * `selected` semantics, together with Continue's enabled state (which needs a selection).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class QuestionStepReselectTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var events: BillingTestPipeline
    private val controllers = mutableListOf<ActivityController<*>>()

    private fun question(id: String, mode: String, vararg options: String) = mapOf(
        "id" to id, "type" to "question",
        "config" to mapOf(
            "title" to "Question $id", "selection_mode" to mode,
            "options" to options.map { mapOf("id" to it.lowercase(), "label" to it) },
        ),
    )

    @Before
    fun setUp() {
        events = BillingTestPipeline(ctx, "onboarding_reselect")
        LocalStorage(ctx).setString(
            "cache_onboarding",
            JSONObject(mapOf("active_flow_id" to FLOW, "flows" to mapOf(FLOW to mapOf(
                "id" to FLOW, "name" to "Reselect probe", "version" to 1,
                "steps" to listOf(
                    question("q_single", "single", "Red", "Green", "Blue"),
                    question("q_multi", "multi", "Apples", "Pears", "Plums"),
                    mapOf("id" to "s_end", "type" to "welcome", "config" to mapOf("title" to "End")),
                ),
            )))).toString(),
        )
    }

    @After
    fun tearDown() {
        for (c in controllers.asReversed()) {
            if (!c.get().isDestroyed) runCatching { c.pause().stop().destroy() }
        }
        controllers.clear()
        idle()
        AppDNA.installOnboardingForTest(null, ready = false)
        OnboardingActivity.clearActiveLaunches()
        PendingCompletionRoute.clear()
        LocalStorage(ctx).setString("cache_onboarding", "{}")
        events.close()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun settle() { idle(); compose.waitForIdle() }

    private fun manager() = OnboardingFlowManager(RemoteConfigManager(null, LocalStorage(ctx), 3600L), events.tracker)

    private fun vmOf(activity: OnboardingActivity): OnboardingViewModel =
        ViewModelProvider(activity)[OnboardingViewModel::class.java]

    private fun present(): ActivityController<OnboardingActivity> {
        AppDNA.installOnboardingForTest(manager(), ready = true)
        val host = Robolectric.buildActivity(Activity::class.java).setup().also { controllers += it }.get()
        assertTrue(AppDNA.presentOnboarding(host, FLOW))
        val c = Robolectric.buildActivity(OnboardingActivity::class.java).setup().also { controllers += it }
        settle()
        return c
    }

    private fun tap(label: String) {
        compose.onNode(hasText(label) and hasClickAction(), useUnmergedTree = false).performClick()
        settle()
    }

    private fun tapOption(label: String) = tap(label)

    private fun back() = tap("←")

    /** The labels of the option cards marked selected. */
    private fun selected(vararg labels: String): List<String> = labels.filter { label ->
        compose.onAllNodes(hasText(label) and hasClickAction())
            .fetchSemanticsNodes()
            .any { it.config.getOrNull(SemanticsProperties.Selected) == true }
    }

    private fun continueButton() = compose.onNode(hasText("Continue") and hasClickAction())

    @Test
    fun `a single-select answer is highlighted again after Back`() {
        present()
        tapOption("Green")
        assertEquals("the tap selects the option", listOf("Green"), selected("Red", "Green", "Blue"))
        tap("Continue")
        compose.onNodeWithTextExists("Question q_multi")

        back()
        compose.onNodeWithTextExists("Question q_single")
        continueButton().assertIsEnabled()
        assertEquals(listOf("Green"), selected("Red", "Green", "Blue"))
    }

    @Test
    fun `a multi-select answer is highlighted again after Back, every option of it`() {
        val c = present()
        tapOption("Green"); tap("Continue")
        tapOption("Apples"); tapOption("Plums")
        tap("Continue")
        compose.onNodeWithTextExists("End")
        assertEquals(mapOf("selected" to listOf("apples", "plums"), "selection_mode" to "multi"), vmOf(c.get()).responses["q_multi"])

        back()
        continueButton().assertIsEnabled()
        assertEquals(listOf("Apples", "Plums"), selected("Apples", "Pears", "Plums"))
    }

    @Test
    fun `a re-pick after Back replaces the answer, single-select keeps one`() {
        val c = present()
        tapOption("Green"); tap("Continue")
        back()
        tapOption("Blue")
        assertEquals(listOf("Blue"), selected("Red", "Green", "Blue"))
        tap("Continue")
        assertEquals(listOf("blue"), (vmOf(c.get()).responses["q_single"] as Map<*, *>)["selected"])
    }

    @Test
    fun `after process death, Back to an answered question highlights the answer`() {
        val first = present()
        tapOption("Green"); tap("Continue")
        compose.onNodeWithTextExists("Question q_multi")

        val saved = Bundle()
        first.saveInstanceState(saved).pause().stop().destroy()
        val parcel = Parcel.obtain()
        val restoredBundle = try {
            parcel.writeBundle(saved)
            parcel.setDataPosition(0)
            parcel.readBundle(OnboardingActivity::class.java.classLoader)!!
        } finally {
            parcel.recycle()
        }
        OnboardingActivity.clearActiveLaunches()
        AppDNA.installOnboardingForTest(null, ready = false)

        AppDNA.installOnboardingForTest(manager(), ready = true)
        val restored = Robolectric.buildActivity(OnboardingActivity::class.java).setup(restoredBundle).also { controllers += it }
        settle()
        assertFalse(restored.get().isFinishing)
        assertEquals(1, vmOf(restored.get()).currentIndex.intValue)

        back()
        continueButton().assertIsEnabled()
        assertEquals(listOf("Green"), selected("Red", "Green", "Blue"))
    }

    @Test
    fun `a rotation keeps a pick not yet submitted`() {
        val c = present()
        tapOption("Blue")
        c.recreate()
        settle()
        assertEquals(listOf("Blue"), selected("Red", "Green", "Blue"))
        continueButton().assertIsEnabled()
    }

    @Test
    fun `a question never answered starts with nothing selected`() {
        present()
        assertEquals(emptyList<String>(), selected("Red", "Green", "Blue"))
        continueButton().assertIsNotEnabled()
    }

    @Test
    fun `the restored selection keeps only offered ids, and one for a single-select question`() {
        fun cfg(mode: String) = OnboardingConfigParser.parseStepForTest(
            mapOf("id" to "q", "type" to "question", "config" to mapOf(
                "selection_mode" to mode,
                "options" to listOf(mapOf("id" to "a", "label" to "A"), mapOf("id" to "b", "label" to "B")),
            )),
        )!!.config
        assertEquals(listOf("b"), QuestionSelection.restored(mapOf("selected" to listOf("b", "a")), cfg("single")))
        assertEquals(listOf("b", "a"), QuestionSelection.restored(mapOf("selected" to listOf("b", "gone", "a", "b")), cfg("multi")))
        assertEquals(listOf("a"), QuestionSelection.restored(mapOf("selected" to "a"), cfg("single")))
        assertEquals(emptyList<String>(), QuestionSelection.restored(null, cfg("multi")))
    }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.onNodeWithTextExists(text: String) {
        assertTrue("expected \"$text\" on screen", onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty())
    }

    private companion object {
        const val FLOW = "reselect_probe_flow"
    }
}
