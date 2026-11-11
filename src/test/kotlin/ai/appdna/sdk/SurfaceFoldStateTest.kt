package ai.appdna.sdk

import ai.appdna.sdk.core.SurfaceMeasure
import ai.appdna.sdk.core.SurfacePanes
import ai.appdna.sdk.core.splitBoundary
import ai.appdna.sdk.core.verticalHingeCenterX
import ai.appdna.sdk.messages.InAppMessageDialog
import ai.appdna.sdk.messages.MessageConfigParser
import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.width
import androidx.test.core.app.ApplicationProvider
import androidx.window.layout.FoldingFeature
import androidx.window.testing.layout.FoldingFeature as TestFoldingFeature
import androidx.window.testing.layout.TestWindowLayoutInfo
import androidx.window.testing.layout.WindowLayoutInfoPublisherRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * What a fold or unfold does to the SDK's surfaces on Android, beyond the layout:
 *
 *  - the four SDK activities declare the size `configChanges`, so the system re-lays them out instead of
 *    destroying them (and with them the step, the answers, the plan and the score);
 *  - an in-app message, which is a dialog in the HOST's activity, moves to the recreated activity when the
 *    host does NOT handle the change, and the "presenting" gate opens only when it is really closed;
 *  - a vertical hinge reported by Jetpack WindowManager moves the boundary between the two panes onto it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-mdpi")
class SurfaceFoldStateTest {

    private val publisher = WindowLayoutInfoPublisherRule()
    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule val rules: RuleChain = RuleChain.outerRule(publisher).around(compose)

    // ── configChanges ───────────────────────────────────────────────────────────────────────────

    private val sizeChanges = ActivityInfo.CONFIG_SCREEN_SIZE or ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE or
        ActivityInfo.CONFIG_SCREEN_LAYOUT or ActivityInfo.CONFIG_ORIENTATION or ActivityInfo.CONFIG_DENSITY

    @Test
    fun `every SDK activity handles a fold itself rather than being recreated`() {
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        for (cls in listOf(
            ai.appdna.sdk.onboarding.OnboardingActivity::class.java,
            ai.appdna.sdk.paywalls.PaywallActivity::class.java,
            ai.appdna.sdk.feedback.SurveyActivity::class.java,
            ai.appdna.sdk.screens.ScreenHostActivity::class.java,
        )) {
            val info = ctx.packageManager.getActivityInfo(ComponentName(ctx, cls), 0)
            assertEquals("${cls.simpleName} declares every size change", sizeChanges, info.configChanges and sizeChanges)
        }
        // Control: the compose test host declares none, so the check can fail.
        val host = ctx.packageManager.getActivityInfo(ComponentName(ctx, ComponentActivity::class.java), 0)
        assertEquals("control: a plain activity handles none of them", 0, host.configChanges and sizeChanges)
    }

    /** The other posture from [from]: unfolded landscape (840 × 673dp), or folded portrait (412 × 915dp). */
    private fun unfolded(from: Configuration) = Configuration(from).apply {
        if (from.screenWidthDp == 840) {
            screenWidthDp = 412; screenHeightDp = 915; smallestScreenWidthDp = 412
            orientation = Configuration.ORIENTATION_PORTRAIT
        } else {
            screenWidthDp = 840; screenHeightDp = 673; smallestScreenWidthDp = 673
            orientation = Configuration.ORIENTATION_LANDSCAPE
        }
    }

    @Test
    fun `a fold delivered to the SDK's survey activity keeps the same instance`() {
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        val config = ai.appdna.sdk.feedback.SurveyConfig.fromMap(
            mapOf(
                "name" to "Probe", "survey_type" to "nps",
                "questions" to listOf(mapOf("id" to "q1", "type" to "nps", "text" to "How likely?", "required" to true)),
                "trigger_rules" to mapOf("event" to "app_open", "frequency" to "once"),
                "appearance" to mapOf("presentation" to "fullscreen", "dismiss_allowed" to true, "show_progress" to false),
            ),
        )!!
        // Control: an activity that does NOT declare them is recreated by the same call.
        val plain = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val p0 = plain.get()
        plain.configurationChange(unfolded(p0.resources.configuration))
        assertNotSame("control: a plain activity is recreated", p0, plain.get())
        plain.pause().stop().destroy()

        ai.appdna.sdk.feedback.SurveyActivity.launch(ctx, "s1", config) {}
        val intent = shadowOf(ctx).nextStartedActivity
        val controller = Robolectric.buildActivity(ai.appdna.sdk.feedback.SurveyActivity::class.java, intent).setup()
        val before = controller.get()
        val target = unfolded(before.resources.configuration)
        controller.configurationChange(target)
        assertEquals("the change reached the activity", target.screenWidthDp, controller.get().resources.configuration.screenWidthDp)
        assertSame("the survey activity was re-laid out, not recreated", before, controller.get())
        assertFalse(before.isFinishing)
        controller.pause().stop().destroy()

    }

    // ── A message over a host that is recreated ─────────────────────────────────────────────────

    private fun message() = MessageConfigParser.parseSingleMessage(
        mapOf(
            "name" to "synthetic",
            "message_type" to "modal",
            "content" to mapOf("title" to "A synthetic title", "cta_text" to "Primary action"),
            "trigger_rules" to mapOf("event" to "e"),
        ),
    ) ?: error("message did not parse")

    @Test
    fun `a message stays on screen when the host activity is recreated by a fold, and closes once`() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val first = controller.get()
        var closed = 0
        InAppMessageDialog.show(first, message(), onCTATap = {}, onDismiss = {}, onClosed = { closed++ })
        shadowOf(Looper.getMainLooper()).idle()
        val d1 = ShadowDialog.getLatestDialog()
        assertTrue("shown", d1.isShowing)

        controller.configurationChange(unfolded(first.resources.configuration))
        shadowOf(Looper.getMainLooper()).idle()
        val second = controller.get()
        assertNotSame("the host was recreated", first, second)
        val d2 = ShadowDialog.getLatestDialog()
        assertNotSame("a new window on the new activity", d1, d2)
        assertTrue("the message is on screen again", d2.isShowing)
        assertSame("on the recreated activity", second, (d2.context as? android.content.ContextWrapper)?.let { unwrap(it) })
        assertEquals("not closed by the move", 0, closed)

        d2.dismiss()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("closed exactly once when the user closes it", 1, closed)
        controller.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("and not again when the host goes", 1, closed)
    }

    @Test
    fun `negative control - the bare dialog is lost on recreation and its dismiss listener never runs`() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val first = controller.get()
        var released = false
        val dialog = InAppMessageDialog.build(first, message(), onCTATap = { it.dismiss() }, onDismiss = { it.dismiss() })
        dialog.setOnDismissListener { released = true }
        dialog.show()
        shadowOf(Looper.getMainLooper()).idle()
        controller.configurationChange(unfolded(first.resources.configuration))
        shadowOf(Looper.getMainLooper()).idle()
        assertSame("no new dialog was shown", dialog, ShadowDialog.getLatestDialog())
        assertFalse("the gate the old path released in its dismiss listener stays shut", released)
        controller.pause().stop().destroy()
    }

    @Test
    fun `a message on an activity that finishes closes and releases`() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        var closed = 0
        InAppMessageDialog.show(controller.get(), message(), onCTATap = {}, onDismiss = {}, onClosed = { closed++ })
        shadowOf(Looper.getMainLooper()).idle()
        controller.get().finish()
        controller.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, closed)
    }

    private tailrec fun unwrap(c: android.content.Context): android.content.Context =
        if (c is android.app.Activity || c !is android.content.ContextWrapper) c else unwrap(c.baseContext)

    // ── The hinge ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the split boundary sits on a hinge well inside the container, and at half otherwise`() {
        assertEquals(520f, splitBoundary(1040f, 120f, null), 0.01f)
        assertEquals("hinge at 600 in a 1040 container starting at 120", 480f, splitBoundary(1040f, 120f, 600f), 0.01f)
        assertEquals("a hinge near the edge is ignored", 520f, splitBoundary(1040f, 120f, 200f), 0.01f)
    }

    @Test
    fun `only a vertical separating or half-opened fold counts as a hinge`() {
        val activity = compose.activity
        val vertical = TestFoldingFeature(activity, center = 600, size = 0, state = FoldingFeature.State.HALF_OPENED, orientation = FoldingFeature.Orientation.VERTICAL)
        assertEquals(600f, verticalHingeCenterX(TestWindowLayoutInfo(listOf(vertical)))!!, 0.5f)
        val horizontal = TestFoldingFeature(activity, center = 400, size = 0, state = FoldingFeature.State.HALF_OPENED, orientation = FoldingFeature.Orientation.HORIZONTAL)
        assertNull("a horizontal (laptop-pose) fold is not a hinge for a side-by-side split", verticalHingeCenterX(TestWindowLayoutInfo(listOf(horizontal))))
        val flat = TestFoldingFeature(activity, center = 600, size = 0, state = FoldingFeature.State.FLAT, orientation = FoldingFeature.Orientation.VERTICAL)
        assertNull("a flat, non-separating fold is no hinge", verticalHingeCenterX(TestWindowLayoutInfo(listOf(flat))))
    }

    @Test
    fun `a reported hinge moves the pane boundary onto it`() {
        compose.setContent {
            SurfaceMeasure {
                SurfacePanes(
                    splits = true,
                    lead = { Box(Modifier.fillMaxSize().testTag("lead")) },
                ) { Box(Modifier.fillMaxSize().testTag("main")) }
            }
        }
        compose.waitForIdle()
        assertEquals("control: no hinge, the panes are equal", 520f, compose.onNodeWithTag("lead").getBoundsInRoot().width.value, 1f)

        publisher.overrideWindowLayoutInfo(
            TestWindowLayoutInfo(
                listOf(TestFoldingFeature(compose.activity, center = 600, size = 0, state = FoldingFeature.State.HALF_OPENED, orientation = FoldingFeature.Orientation.VERTICAL)),
            ),
        )
        compose.waitForIdle()
        val lead = compose.onNodeWithTag("lead").getBoundsInRoot()
        val main = compose.onNodeWithTag("main").getBoundsInRoot()
        assertEquals("the leading pane ends on the hinge", 600f, lead.right.value, 1f)
        assertEquals("the trailing pane starts on the hinge", 600f, main.left.value, 1f)
    }
}
