package ai.appdna.sdk.onboarding

import ai.appdna.sdk.AppDNA
import ai.appdna.sdk.billing.BillingTestPipeline
import ai.appdna.sdk.config.RemoteConfigManager
import ai.appdna.sdk.storage.LocalStorage
import android.app.Activity
import android.content.Context
import android.os.Looper
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * Android's system Back does what the flow's own back arrow does: on a step that draws the arrow it goes to the
 * step the user came from and the flow stays open. Where the arrow is not drawn (the first step, `allow_back`
 * off, or the step's `hide_back`) Back keeps closing the flow, and only when dismissing is allowed.
 *
 * Driven through the real activity: the steps are advanced with their own CTA and Back is `onBackPressed()`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class SystemBackGoesToPreviousStepTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var events: BillingTestPipeline
    private val controllers = mutableListOf<ActivityController<*>>()

    private fun step(id: String, title: String, extra: Map<String, Any> = emptyMap()) = mapOf(
        "id" to id, "type" to "welcome",
        "config" to mapOf("title" to title, "cta_text" to "Next $id"),
    ) + extra

    private fun seed(settings: Map<String, Any> = emptyMap(), secondStep: Map<String, Any> = emptyMap()) {
        events = BillingTestPipeline(ctx, "onboarding_system_back")
        LocalStorage(ctx).setString(
            "cache_onboarding",
            JSONObject(mapOf("active_flow_id" to FLOW, "flows" to mapOf(FLOW to mapOf(
                "id" to FLOW, "name" to "System back probe", "version" to 1,
                "settings" to settings,
                "steps" to listOf(
                    step("one", "Step one"),
                    step("two", "Step two", secondStep),
                    step("three", "Step three"),
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
        if (::events.isInitialized) events.close()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun settle() { idle(); compose.waitForIdle() }

    private fun present(): ActivityController<OnboardingActivity> {
        AppDNA.installOnboardingForTest(
            OnboardingFlowManager(RemoteConfigManager(null, LocalStorage(ctx), 3600L), events.tracker), ready = true,
        )
        val host = Robolectric.buildActivity(Activity::class.java).setup().also { controllers += it }.get()
        assertTrue(AppDNA.presentOnboarding(host, FLOW))
        val c = Robolectric.buildActivity(OnboardingActivity::class.java).setup().also { controllers += it }
        settle()
        return c
    }

    private fun tap(label: String) {
        compose.onNode(hasText(label) and hasClickAction()).performClick()
        settle()
    }

    private fun systemBack(c: ActivityController<OnboardingActivity>) {
        c.get().onBackPressed()
        settle()
    }

    private fun index(c: ActivityController<OnboardingActivity>) =
        ViewModelProvider(c.get())[OnboardingViewModel::class.java].currentIndex.intValue

    private fun onScreen(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    private fun dismissed() = events.names().contains("onboarding_flow_dismissed")

    @Test
    fun `Back on step two goes to step one, and the flow stays open`() {
        seed()
        val c = present()
        tap("Next one")
        assertEquals(1, index(c))

        systemBack(c)

        assertFalse("system Back closed the flow on a step that draws a back arrow", c.get().isFinishing)
        assertFalse(dismissed())
        assertEquals(0, index(c))
        assertTrue(onScreen("Step one"))
    }

    @Test
    fun `Back goes to the step the user came from, twice in a row`() {
        seed()
        val c = present()
        tap("Next one"); tap("Next two")
        assertEquals(2, index(c))

        systemBack(c)
        assertEquals(1, index(c))
        systemBack(c)
        assertEquals(0, index(c))
        assertFalse(c.get().isFinishing)
    }

    @Test
    fun `Back on the first step closes the flow`() {
        seed()
        val c = present()

        systemBack(c)

        assertTrue(c.get().isFinishing)
        assertTrue(dismissed())
    }

    @Test
    fun `Back where the step hides its back control closes the flow`() {
        seed(secondStep = mapOf("hide_back" to true))
        val c = present()
        tap("Next one")
        assertEquals(1, index(c))

        systemBack(c)

        assertTrue(c.get().isFinishing)
        assertTrue(dismissed())
    }

    @Test
    fun `Back with allow_back off closes the flow`() {
        seed(settings = mapOf("allow_back" to false))
        val c = present()
        tap("Next one")

        systemBack(c)

        assertTrue(c.get().isFinishing)
        assertTrue(dismissed())
    }

    @Test
    fun `Back where nothing draws a back arrow and dismissing is off does nothing`() {
        seed(settings = mapOf("allow_back" to false, "dismiss_allowed" to false))
        val c = present()
        tap("Next one")

        systemBack(c)

        assertFalse(c.get().isFinishing)
        assertFalse(dismissed())
        assertEquals(1, index(c))
    }

    private companion object {
        const val FLOW = "system_back_probe_flow"
    }
}
