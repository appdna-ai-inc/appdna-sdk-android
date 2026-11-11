package ai.appdna.sdk.onboarding

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The status- and navigation-bar icon tint follows the background the step draws, not the phone's theme. Synthetic
 * colours. The theme only decides for a step with no colour of its own.
 */
@RunWith(RobolectricTestRunner::class)
class SystemBarIconsTest {
    private val dark = GoogleMapsAttribution.Backdrop.Plain("#111827")
    private val white = GoogleMapsAttribution.Backdrop.Plain("#FFFFFF")

    @Test fun aDarkStepTakesLightIconsOnALightPhone() = assertEquals(false, systemBarsUseDarkIcons(dark, themeIsDark = false))
    @Test fun aWhiteStepTakesDarkIconsOnADarkPhone() = assertEquals(true, systemBarsUseDarkIcons(white, themeIsDark = true))
    @Test fun aBusyStepTakesLightIcons() = assertEquals(false, systemBarsUseDarkIcons(GoogleMapsAttribution.Backdrop.Busy, themeIsDark = false))
    @Test fun noColourFollowsTheTheme() {
        assertEquals(true, systemBarsUseDarkIcons(GoogleMapsAttribution.Backdrop.Plain(null), themeIsDark = false))
        assertEquals(false, systemBarsUseDarkIcons(GoogleMapsAttribution.Backdrop.Plain(null), themeIsDark = true))
    }

    @Test fun aStepWithNoBackgroundOfItsOwnDrawsTheFlowsAndTheBarsFollowIt() {
        val flow = OnboardingConfigParser.parseFlowForTest(
            "f",
            mapOf(
                "id" to "f", "name" to "f", "version" to 1,
                "settings" to mapOf("background" to mapOf("type" to "color", "color" to "#111827")),
                "steps" to listOf(mapOf("id" to "s1", "type" to "custom", "config" to mapOf("content_blocks" to emptyList<Any>()))),
            ),
        )
        val step = flow!!.steps.first()
        assertEquals(false, systemBarsUseDarkIcons(GoogleMapsAttribution.backdrop(ofStep = step.config, type = step.type), themeIsDark = false))
    }
}
