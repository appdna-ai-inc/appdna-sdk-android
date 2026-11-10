package ai.appdna.sdk.onboarding

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #1653 — Radio Fill "Circle" and "Checkmark" draw the round indicator for a multi-select too, which is what iOS draws.
 * The composable under test is the one the select block calls. THE CONTROL: the emoji fill draws neither tag, so a pass
 * is not a harness that finds every tag.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class SelectRadioIndicatorTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(fill: String, multi: Boolean, selected: Boolean) {
        compose.setContent { SelectRadioIndicator(isSelected = selected, isMulti = multi, fillCol = Color.Red, radioFill = fill) }
    }

    private fun check(n: Int) = compose.onAllNodesWithTag("select_indicator_check", useUnmergedTree = true).assertCountEquals(n)
    private fun ring(n: Int) = compose.onAllNodesWithTag("select_indicator_ring", useUnmergedTree = true).assertCountEquals(n)

    @Test fun multiCircleSelectedIsARoundCheck() { show("circle", true, true); check(1); ring(0) }
    @Test fun multiCircleUnselectedIsARing() { show("circle", true, false); check(0); ring(1) }
    @Test fun multiCheckmarkUnselectedIsARing() { show("checkmark", true, false); check(0); ring(1) }
    @Test fun singleCircleIsARadio() { show("circle", false, true); check(0); ring(1) }
    @Test fun singleCheckmarkSelectedIsARoundCheck() { show("checkmark", false, true); check(1); ring(0) }
    @Test fun emojiFillDrawsNeitherControl() { show("\u2B50", true, true); check(0); ring(0) }
}
