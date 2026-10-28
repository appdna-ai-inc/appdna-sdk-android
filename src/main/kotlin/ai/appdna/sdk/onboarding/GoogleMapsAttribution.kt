package ai.appdna.sdk.onboarding

import ai.appdna.sdk.R
import ai.appdna.sdk.core.BackgroundStyleConfig
import ai.appdna.sdk.core.GoogleMarkChip
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Google's attribution for Google Maps Platform content shown without a Google map.
 *
 * Google's Places and Geocoding policies require it wherever their data is shown without a Google
 * map: "It must always be clear to end users which content is provided by Google Maps." The SDK shows
 * Google data in three places, and each one attributes it:
 *
 * - a location dropdown listing Google suggestions draws the logo under the rows
 *   ([LocationSuggestionSearch.showsGoogleLogo]);
 * - a location field whose answer came from Google keeps the logo beside the field after the dropdown
 *   closes, and again when the answer is restored ([LocationAnswer.carriesPlaceId]);
 * - an onboarding step whose text templates a Google answer draws a small "Google Maps" text credit in
 *   the step's container ([GoogleCredit]).
 *
 * The logo is Google's own asset, unmodified (`res/drawable-nodpi/appdna_google_maps_logo_*.png`;
 * provenance in `NOTICE.md`), drawn per Google's attribution guidelines
 * (https://developers.google.com/maps/documentation/geocoding/policies): 16 dp high (16–19 dp allowed),
 * 10 dp clear space left, right and top and 5 dp below, the plain variant on a plain surface and the
 * outlined variant on a busy one, and the accessibility label "Google Maps". `GoogleMapsAttribution` in
 * `GoogleMapsAttribution.swift` is the Swift twin.
 */
internal object GoogleMapsAttribution {

    /** The accessibility label and the text attribution Google's guidelines name. */
    const val LABEL = "Google Maps"

    /** The test tag of the logo, and of the text attribution. */
    const val LOGO_TAG = "appdna.google_maps_attribution"
    const val TEXT_TAG = "appdna.google_maps_text_attribution"

    /** The logo's height. Google allows 16–19 dp; the smallest keeps a dropdown compact. */
    const val LOGO_HEIGHT_DP = 16f

    /** Google's minimum clear space around the logo. */
    const val CLEAR_SPACE_SIDES_DP = 10f
    const val CLEAR_SPACE_BOTTOM_DP = 5f

    /** One of the four variants Google publishes. */
    enum class Variant(val resourceName: String) {
        /** Plain gray — a plain light surface. */
        GRAY("appdna_google_maps_logo_gray"),

        /** Plain white — a plain dark surface. */
        WHITE("appdna_google_maps_logo_white"),

        /** Dark letters with a light outline — a busy surface in light appearance. */
        LIGHT_OUTLINE("appdna_google_maps_logo_light_outline"),

        /** White letters with a dark outline — a busy surface in dark appearance. */
        DARK_OUTLINE("appdna_google_maps_logo_dark_outline"),
        ;

        /** The drawable. Referenced through `R`, so a variant missing from the AAR fails the build. */
        val drawable: Int
            get() = when (this) {
                GRAY -> R.drawable.appdna_google_maps_logo_gray
                WHITE -> R.drawable.appdna_google_maps_logo_white
                LIGHT_OUTLINE -> R.drawable.appdna_google_maps_logo_light_outline
                DARK_OUTLINE -> R.drawable.appdna_google_maps_logo_dark_outline
            }
    }

    /** What is behind the attribution. */
    sealed class Backdrop {
        /**
         * A solid surface. [hex] is its colour when known (an authored dropdown colour, a colour
         * background); null means the system background, which follows the appearance.
         */
        data class Plain(val hex: String?) : Backdrop()

        /** An image, a gradient, an animation or a translucent surface: Google asks for the outlined logo. */
        object Busy : Backdrop()
    }

    /**
     * A step's background as a backdrop: a colour is plain, anything else authored is busy, and no
     * background is the system one.
     */
    fun backdrop(of: BackgroundStyleConfig?): Backdrop {
        val background = of ?: return Backdrop.Plain(null)
        return when (background.type ?: (if (background.color != null) "color" else null)) {
            "color" -> Backdrop.Plain(background.color)
            null -> Backdrop.Plain(null)
            else -> Backdrop.Busy
        }
    }

    /**
     * The variant for a backdrop. A known plain colour decides by its own luminance; the system
     * background and a busy one follow the appearance.
     */
    fun variant(on: Backdrop, darkAppearance: Boolean): Variant = when (on) {
        is Backdrop.Plain -> {
            val l = GoogleMarkChip.luminance(on.hex)
            when {
                l != null -> if (l >= GoogleMarkChip.LIGHT_ENOUGH) Variant.GRAY else Variant.WHITE
                darkAppearance -> Variant.WHITE
                else -> Variant.GRAY
            }
        }
        Backdrop.Busy -> if (darkAppearance) Variant.DARK_OUTLINE else Variant.LIGHT_OUTLINE
    }

    /**
     * The colour of the text attribution: one of the three Google names (white, `#1F1F1F`, `#5E5E5E`).
     * Gray on a light surface, white on a dark or busy one; on a busy surface the view adds a shadow
     * so the white stays legible over a light part of an image.
     */
    fun textColorHex(on: Backdrop, darkAppearance: Boolean): String =
        if (variant(on, darkAppearance) == Variant.GRAY) "#5E5E5E" else "#FFFFFF"
}

/**
 * What is behind a location field on this surface — the onboarding step's background. A field that
 * keeps the Google logo beside it after a tap draws the variant for it.
 */
internal val LocalGoogleAttributionBackdrop =
    compositionLocalOf<GoogleMapsAttribution.Backdrop> { GoogleMapsAttribution.Backdrop.Plain(null) }

/** The "Google Maps" logo with Google's clear space. */
@Composable
internal fun GoogleMapsLogo(backdrop: GoogleMapsAttribution.Backdrop, modifier: Modifier = Modifier) {
    val variant = GoogleMapsAttribution.variant(backdrop, isSystemInDarkTheme())
    val painter = painterResource(variant.drawable)
    val size = painter.intrinsicSize
    val ratio = if (size.width > 0f && size.height > 0f) size.width / size.height else 1f
    Box(
        modifier = modifier
            .padding(
                start = GoogleMapsAttribution.CLEAR_SPACE_SIDES_DP.dp,
                end = GoogleMapsAttribution.CLEAR_SPACE_SIDES_DP.dp,
                top = GoogleMapsAttribution.CLEAR_SPACE_SIDES_DP.dp,
                bottom = GoogleMapsAttribution.CLEAR_SPACE_BOTTOM_DP.dp,
            )
            .testTag(GoogleMapsAttribution.LOGO_TAG)
            .clearAndSetSemantics { contentDescription = GoogleMapsAttribution.LABEL },
    ) {
        Image(
            painter = painter,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .height(GoogleMapsAttribution.LOGO_HEIGHT_DP.dp)
                .aspectRatio(ratio),
        )
    }
}

/** The text attribution: "Google Maps", small, in one of the colours Google names. */
@Composable
internal fun GoogleMapsTextAttribution(backdrop: GoogleMapsAttribution.Backdrop, modifier: Modifier = Modifier) {
    val hex = GoogleMapsAttribution.textColorHex(backdrop, isSystemInDarkTheme())
    Text(
        text = GoogleMapsAttribution.LABEL,
        style = TextStyle(
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = Color(android.graphics.Color.parseColor(hex)),
            // The shadow only where it is needed: white over an image or gradient. On a plain surface
            // the text carries no effect at all.
            shadow = if (backdrop == GoogleMapsAttribution.Backdrop.Busy) {
                Shadow(color = Color.Black.copy(alpha = 0.6f), offset = Offset.Zero, blurRadius = 4f)
            } else null,
        ),
        modifier = modifier.testTag(GoogleMapsAttribution.TEXT_TAG),
    )
}
