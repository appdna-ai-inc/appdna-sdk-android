package ai.appdna.sdk.onboarding

import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * An UNREGISTERED custom_view never draws its debug key to an end user. It drew the literal `[view_key]`
 * whenever the block carried no `placeholder_text`. The shipping renderer is rendered: in a release build
 * (`CustomViewFallback.debugOverride = false`) with nothing authored, no `[view_key]` node exists. CONTROLS on the same
 * harness: a debuggable build draws the key, and an authored `placeholder_text` draws in release — and a sibling text
 * after the block is drawn, so the step itself rendered.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class CustomViewFallbackRenderTest {
    @get:Rule val compose = createComposeRule()

    @After fun reset() { CustomViewFallback.debugOverride = null }

    private fun render(placeholderText: String?, debug: Boolean) {
        CustomViewFallback.debugOverride = debug
        val block = buildMap<String, Any> {
            put("id", "cv"); put("type", "custom_view"); put("view_key", "probe_unregistered_chart")
            placeholderText?.let { put("placeholder_text", it) }
        }
        val blocks = OnboardingConfigParser.parseStepForTest(
            mapOf("id" to "s", "type" to "custom", "config" to mapOf("content_blocks" to listOf(block))),
        )!!.config.content_blocks!!.toList()
        compose.setContent {
            Column {
                ContentBlockRendererView(blocks = blocks, onAction = {}, toggleValues = mutableMapOf(), inputValues = mutableMapOf())
                Text("after the block")
            }
        }
        compose.waitForIdle()
    }

    @Test fun aReleaseBuildDrawsNothingForAnUnregisteredViewWithNoFallback() {
        render(placeholderText = null, debug = false)
        compose.onNodeWithText("after the block").assertExists()
        compose.onNodeWithText("[probe_unregistered_chart]").assertDoesNotExist()
    }

    @Test fun controlADebuggableBuildDrawsTheKey() {
        render(placeholderText = null, debug = true)
        compose.onNodeWithText("[probe_unregistered_chart]").assertExists()
    }

    @Test fun controlAnAuthoredPlaceholderTextDrawsInRelease() {
        render(placeholderText = "Chart unavailable", debug = false)
        compose.onNodeWithText("Chart unavailable").assertExists()
        compose.onNodeWithText("[probe_unregistered_chart]").assertDoesNotExist()
    }
}
