package ai.appdna.sdk.feedback

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A survey shown as a bottom sheet or a modal, with the keyboard up: Submit sits ABOVE the keyboard, so a tap on it is a
 * tap on Submit and not on a key. The keyboard is the IME inset delivered to the window, as the system delivers it.
 * The control in each test: the same layout with no keyboard puts Submit lower than with it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class SurveySheetKeyboardTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val config = SurveyConfig.fromMap(
        mapOf(
            "name" to "Probe", "survey_type" to "custom",
            // The walk's shape: a question before the typed one (so Back shows), progress on, dismiss allowed.
            "questions" to listOf(
                mapOf("id" to "q0", "type" to "single_choice", "text" to "Pick one", "required" to false,
                    "options" to listOf(mapOf("id" to "a", "text" to "A"), mapOf("id" to "b", "text" to "B"))),
                mapOf("id" to "q1", "type" to "free_text", "text" to "Anything else you would like to tell us about your first week?", "required" to false),
            ),
            "trigger_rules" to mapOf("event" to "app_open", "frequency" to "once"),
            "appearance" to mapOf("presentation" to "bottom_sheet", "dismiss_allowed" to true, "show_progress" to true),
        ),
    )!!

    private val keyboardDp = 340f

    private fun submitBottom(keyboard: Boolean, modal: Boolean): Pair<Float, Float> {
        compose.runOnUiThread { WindowCompat.setDecorFitsSystemWindows(compose.activity.window, false) }
        compose.setContent {
            if (modal) ModalSurveyWrapper(config = config, onComplete = {}, onDismiss = {}, isDark = false)
            else BottomSheetSurveyWrapper(config = config, onComplete = {}, onDismiss = {}, isDark = false)
        }
        compose.mainClock.advanceTimeBy(3000)
        compose.onNodeWithText("Next").performClick()
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        if (keyboard) {
            compose.runOnUiThread {
                val px = (keyboardDp * compose.activity.resources.displayMetrics.density).toInt()
                val insets = WindowInsetsCompat.Builder()
                    .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, px))
                    .setVisible(WindowInsetsCompat.Type.ime(), true)
                    .build()
                ViewCompat.dispatchApplyWindowInsets(compose.activity.window.decorView, insets)
            }
        }
        compose.waitForIdle()
        val rootHeight = with(compose.density) { compose.activity.window.decorView.height.toFloat().toDp().value }
        // Drawn, with a real size: a button pushed out of the clipped sheet has empty bounds.
        compose.onNodeWithText("Submit").assertIsDisplayed()
        val submit = compose.onNodeWithText("Submit").getBoundsInRoot()
        assertTrue("Submit has a size (${submit})", submit.bottom.value - submit.top.value > 20f)
        return submit.bottom.value to rootHeight
    }

    @Test fun bottomSheetSubmitSitsAboveTheKeyboard() {
        val (bottom, height) = submitBottom(keyboard = true, modal = false)
        assertTrue("Submit bottom $bottom must be above the keyboard top ${height - keyboardDp}", bottom <= height - keyboardDp)
    }

    @Test fun modalSubmitSitsAboveTheKeyboard() {
        val (bottom, height) = submitBottom(keyboard = true, modal = true)
        assertTrue("Submit bottom $bottom must be above the keyboard top ${height - keyboardDp}", bottom <= height - keyboardDp)
    }

    @Test fun controlWithoutTheKeyboardSubmitSitsBelowWhereTheKeyboardWouldStart() {
        val (bottom, height) = submitBottom(keyboard = false, modal = false)
        assertTrue("control: the harness must see Submit in the keyboard's area when there is no keyboard ($bottom vs ${height - keyboardDp})", bottom > height - keyboardDp)
    }
}
