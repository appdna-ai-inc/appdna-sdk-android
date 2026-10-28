package ai.appdna.sdk.onboarding

import ai.appdna.sdk.R
import ai.appdna.sdk.core.BackgroundStyleConfig
import ai.appdna.sdk.core.GoogleMarkChip
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.toArgb
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
         * A solid surface. [hex] is the colour drawn there (an authored colour, or the theme colour a
         * view fills with — [hexOf]); null means the theme's background, which the attribution reads
         * from `MaterialTheme` where it draws. Never the system appearance: a host's theme need not
         * follow it (a Screen is hosted in a light theme on a dark device).
         */
        data class Plain(val hex: String?) : Backdrop()

        /** An image, a gradient, an animation or a translucent surface: Google asks for the outlined logo. */
        object Busy : Backdrop()
    }

    /**
     * A step's background as a backdrop: what the step host actually draws for it
     * (`StepFullScreenBackground`). A colour is plain — `#FFFFFF` when none is authored, which is what
     * the host draws then, as on iOS; an image, gradient or animation is busy. A background with no
     * `type` (even one holding a colour) and a transparent one paint the theme's background, so: plain,
     * with no colour of its own.
     */
    fun backdrop(of: BackgroundStyleConfig?): Backdrop {
        val background = of ?: return Backdrop.Plain(null)
        return when (background.type) {
            "color" -> (background.color ?: DEFAULT_COLOR_BACKGROUND).let {
                if (it == "transparent" || it == "clear") Backdrop.Plain(null) else Backdrop.Plain(it)
            }
            null, "transparent", "clear", "none" -> Backdrop.Plain(null)
            else -> Backdrop.Busy
        }
    }

    /**
     * What a content block draws behind its own content: its own fill, else [behind]. In order from
     * the content outwards — a row's `bg_color`, the block's `block_style` fill, its container fill
     * (`container_bg_color`, `blur_background`). A gradient, a blur or a translucent fill is busy. A
     * field inside a filled container, or a filled field, picks its logo from THIS. The Swift twin is
     * `GoogleMapsAttribution.backdrop(ofBlock:behind:)`.
     */
    fun backdrop(ofBlock: ContentBlock, behind: Backdrop): Backdrop {
        val cfg = ofBlock.field_config
        fun double(key: String): Double? = (cfg?.get(key) as? Number)?.toDouble()
        fun visible(hex: String?): String? {
            if (hex.isNullOrBlank()) return null
            val l = hex.lowercase()
            return if (l == "transparent" || l == "clear") null else hex
        }
        if (ofBlock.type == "row") {
            visible(cfg?.get("bg_color") as? String)?.let { bg ->
                return if ((double("background_opacity") ?: 1.0) < 1.0) Backdrop.Busy else Backdrop.Plain(bg)
            }
        }
        ofBlock.block_style?.let { style ->
            if (style.background_gradient != null) return Backdrop.Busy
            visible(style.background_color)?.let { bg ->
                return if ((style.opacity ?: 1.0) < 1.0) Backdrop.Busy else Backdrop.Plain(bg)
            }
        }
        if (cfg?.get("blur_background") == true) return Backdrop.Busy
        visible(cfg?.get("container_bg_color") as? String)?.takeIf { !it.equals("#ffffff", true) }?.let { bg ->
            val opacity = double("container_opacity") ?: double("background_opacity") ?: 1.0
            return if (opacity < 1.0) Backdrop.Busy else Backdrop.Plain(bg)
        }
        return behind
    }

    /**
     * What the onboarding host draws behind a step (`StepFullScreenBackground`), in its order: the
     * step's background; a chat step's own colour; a chat step's dark default; else the theme's
     * background. A chat step is dark in a light theme, so the attribution decides from THIS.
     */
    fun backdrop(ofStep: ai.appdna.sdk.onboarding.StepConfig, type: OnboardingStep.StepType): Backdrop {
        ofStep.background?.let { return backdrop(it) }
        ofStep.chat_config?.style?.background_color?.let { return Backdrop.Plain(it) }
        if (type == OnboardingStep.StepType.INTERACTIVE_CHAT) return Backdrop.Plain(INTERACTIVE_CHAT_BACKGROUND)
        return Backdrop.Plain(null)
    }

    /** The onboarding host's default behind an `interactive_chat` step. */
    const val INTERACTIVE_CHAT_BACKGROUND = "#0F172A"

    /** What a Screen draws behind its sections: its background colour, else the theme's. */
    fun backdrop(ofScreen: ai.appdna.sdk.screens.BackgroundConfig?): Backdrop = Backdrop.Plain(ofScreen?.color)

    /** What a Screen section draws behind its content: its own fill, else what is behind it. */
    fun backdrop(ofSection: ai.appdna.sdk.screens.SectionStyle?, behind: Backdrop): Backdrop {
        val style = ofSection ?: return behind
        val gradient = style.backgroundGradient
        if (gradient?.start != null && gradient.end != null) return Backdrop.Busy
        style.backgroundColor?.let { return Backdrop.Plain(it) }
        return behind
    }

    /** `#RRGGBB` of a colour a view fills with, for [Backdrop.Plain]. */
    fun hexOf(color: Color): String = String.format("#%06X", 0xFFFFFF and color.toArgb())

    /** What a `color` step background with no colour draws, on both platforms. */
    const val DEFAULT_COLOR_BACKGROUND = "#FFFFFF"

    /**
     * What a location dropdown draws behind its rows, and so behind the logo under them: its authored
     * fill (`dropdown_bg_color`), else [unstyled] (what an unstyled dropdown draws). A fill drawn
     * translucent (`dropdown_opacity` below 1, or an alpha below `FF`) is busy. A fill that draws nothing
     * — `transparent`, `clear`, a fully transparent colour, or a value no colour parser reads (both
     * platforms draw those clear) — shows what is [behind] the dropdown. The Swift twin is
     * `GoogleMapsAttribution.backdrop(ofDropdownFill:opacity:behind:unstyled:)`.
     */
    fun backdrop(ofDropdownFill: String?, opacity: Float, behind: Backdrop, unstyled: Backdrop): Backdrop {
        val raw = ofDropdownFill?.trim()
        if (raw.isNullOrEmpty()) return if (opacity < 1f) Backdrop.Busy else unstyled
        val digits = raw.removePrefix("#")
        val isHex = (digits.length == 6 || digits.length == 8) && digits.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
        if (!isHex) return behind
        val alpha = if (digits.length == 8) digits.substring(6, 8).toInt(16) else 255
        if (alpha == 0) return behind
        return if (opacity < 1f || alpha < 255) Backdrop.Busy else Backdrop.Plain(raw)
    }

    /**
     * The variant for a backdrop. A known plain colour decides by its own luminance; the theme's
     * background (and a busy surface) by whether that background is dark.
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

/**
 * The variant a logo draws, readable from the semantics tree (a custom key: accessibility services are
 * not told). It lets a test read the variant where pixels cannot be captured, inside a sheet's window.
 */
internal val GoogleLogoVariantKey =
    androidx.compose.ui.semantics.SemanticsPropertyKey<GoogleMapsAttribution.Variant>("AppDNAGoogleLogoVariant")

/** The "Google Maps" logo with Google's clear space. */
/**
 * Whether the theme's background is dark: what [GoogleMapsAttribution.Backdrop.Plain] with no colour,
 * and a busy surface, are decided by. Read from the colour the theme fills with, not from the system
 * appearance — a host's theme need not follow it.
 */
@Composable
internal fun googleAttributionThemeIsDark(): Boolean {
    val l = ai.appdna.sdk.core.GoogleMarkChip.luminance(GoogleMapsAttribution.hexOf(MaterialTheme.colorScheme.background))
    return l != null && l < ai.appdna.sdk.core.GoogleMarkChip.LIGHT_ENOUGH
}

@Composable
internal fun GoogleMapsLogo(backdrop: GoogleMapsAttribution.Backdrop, modifier: Modifier = Modifier) {
    val variant = GoogleMapsAttribution.variant(backdrop, googleAttributionThemeIsDark())
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
            .clearAndSetSemantics {
                contentDescription = GoogleMapsAttribution.LABEL
                this[GoogleLogoVariantKey] = variant
            },
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
    val hex = GoogleMapsAttribution.textColorHex(backdrop, googleAttributionThemeIsDark())
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
