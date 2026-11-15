package ai.appdna.sdk.paywalls

import ai.appdna.sdk.onboarding.GoogleMapsAttribution
import ai.appdna.sdk.onboarding.systemBarsUseDarkIcons
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A paywall's status and navigation bar icons follow the paywall's background, not the phone's theme (the onboarding
 * rule). It used SystemBarStyle.auto, which follows the phone: a white paywall in a dark phone got light icons and lost
 * its clock. RED CONTROL: before `systemBarBackdrop` the paywall read nothing, so every case below followed the theme.
 */
@RunWith(RobolectricTestRunner::class)
class PaywallSystemBarIconsTest {
    private fun paywall(background: Map<String, Any>?) = PaywallConfigParser.parseSinglePaywall(
        "pw_bars",
        mapOf("id" to "pw_bars", "name" to "Bars", "sections" to emptyList<Any>()) +
            (background?.let { mapOf("background" to it) } ?: emptyMap()),
    ) ?: error("did not parse")

    private fun darkIcons(background: Map<String, Any>?, phoneDark: Boolean) =
        systemBarsUseDarkIcons(paywall(background).systemBarBackdrop(), themeIsDark = phoneDark)

    @Test fun aWhitePaywallInADarkPhoneTakesDarkIcons() = assertEquals(true, darkIcons(mapOf("type" to "color", "value" to "#FFFFFF"), phoneDark = true))
    @Test fun aDarkPaywallInALightPhoneTakesLightIcons() = assertEquals(false, darkIcons(mapOf("type" to "color", "value" to "#0B1220"), phoneDark = false))
    @Test fun aGradientPaywallTakesLightIcons() = assertEquals(false, darkIcons(mapOf("type" to "gradient", "gradient" to mapOf("type" to "linear", "colors" to listOf("#FFFFFF", "#EEEEEE"))), phoneDark = false))
    @Test fun noBackgroundFollowsThePhone() {
        assertEquals(GoogleMapsAttribution.Backdrop.Plain(null), paywall(null).systemBarBackdrop())
        assertEquals(false, darkIcons(null, phoneDark = true))
        assertEquals(true, darkIcons(null, phoneDark = false))
    }
}
