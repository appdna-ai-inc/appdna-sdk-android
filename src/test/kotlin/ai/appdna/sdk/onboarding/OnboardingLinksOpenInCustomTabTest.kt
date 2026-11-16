package ai.appdna.sdk.onboarding

import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import androidx.activity.ComponentActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.browser.customtabs.CustomTabsService
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * An onboarding `link` button opens its URL in a Custom Tab when the device has a Custom Tabs provider, and in a
 * plain VIEW intent when it has none. Renders the shipping renderer, taps the button, and reads the intent the
 * activity started.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class OnboardingLinksOpenInCustomTabTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun render() {
        val blocks = OnboardingConfigParser.parseStepForTest(
            mapOf(
                "id" to "s", "type" to "custom",
                "config" to mapOf("content_blocks" to listOf(
                    mapOf("id" to "l", "type" to "button", "text" to "Open the guide", "action" to "link", "action_value" to "https://example.com/guide"),
                )),
            ),
        )!!.config.content_blocks!!.toList()
        compose.setContent { ContentBlockRendererView(blocks = blocks, onAction = {}, toggleValues = mutableMapOf(), inputValues = mutableMapOf()) }
        compose.waitForIdle()
    }

    /** Registers a browser that offers the Custom Tabs service, as Chrome does. */
    private fun installCustomTabsProvider() {
        val pm = shadowOf(compose.activity.packageManager)
        val pkg = "com.example.browser"
        pm.addActivityIfNotPresent(ComponentName(pkg, "$pkg.Main"))
        pm.addIntentFilterForActivity(ComponentName(pkg, "$pkg.Main"), IntentFilter(Intent.ACTION_VIEW).apply { addCategory(Intent.CATEGORY_BROWSABLE); addDataScheme("http") })
        val service = ResolveInfo().apply { serviceInfo = ServiceInfo().apply { packageName = pkg; name = "$pkg.CustomTabs" } }
        pm.addResolveInfoForIntent(Intent(CustomTabsService.ACTION_CUSTOM_TABS_CONNECTION).setPackage(pkg), service)
    }

    @Test fun `a link button opens a Custom Tab when the device has a provider`() {
        installCustomTabsProvider()
        render()
        compose.onNodeWithText("Open the guide").performClick()
        compose.waitForIdle()
        val started = shadowOf(compose.activity).nextStartedActivity
        assertNotNull("the tap opened something", started)
        assertEquals("https://example.com/guide", started.dataString)
        assertTrue("a Custom Tab carries the session extra", started.hasExtra(CustomTabsIntent.EXTRA_SESSION))
        assertEquals("com.example.browser", started.`package`)
    }

    @Test fun `with no Custom Tabs provider it falls back to a plain VIEW`() {
        render()
        compose.onNodeWithText("Open the guide").performClick()
        compose.waitForIdle()
        val started = shadowOf(compose.activity).nextStartedActivity
        assertNotNull(started)
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertFalse(started.hasExtra(CustomTabsIntent.EXTRA_SESSION))
    }
}
