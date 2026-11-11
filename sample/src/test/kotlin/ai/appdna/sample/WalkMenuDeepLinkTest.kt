package ai.appdna.sample

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A deep link arriving while the walk menu is on screen swaps it for the deep link screen, and "Back to the menu"
 * swaps back, without crashing the composition (an early return out of the menu's Column did, on recomposition).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WalkMenuDeepLinkTest {
    @get:Rule val compose = createComposeRule()

    @After fun clear() { WalkLog.deepLink.value = null }

    @Test fun aLinkArrivingRecomposesToTheDeepLinkScreenAndBack() {
        WalkLog.deepLink.value = null
        compose.setContent { WalkMenuScreen(activity = null, title = "Walk menu", items = emptyList()) }
        compose.onNodeWithText("Walk menu").assertIsDisplayed()
        compose.runOnIdle { WalkLog.deepLink.value = "appdnasample://walk/j5" }
        compose.onNodeWithText("Deep link screen").assertIsDisplayed()
        compose.onNodeWithText("walk/j5").assertIsDisplayed()
        compose.onNodeWithText("Back to the menu").performClick()
        compose.onNodeWithText("Walk menu").assertIsDisplayed()
        compose.runOnIdle { WalkLog.deepLink.value = "appdnasample://walk/j5" }
        compose.onNodeWithText("Deep link screen").assertIsDisplayed()
    }
}
