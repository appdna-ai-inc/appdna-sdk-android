package ai.appdna.sdk.onboarding

/**
 * Whether a dropdown Select's field draws light ink: judged by its own `field_style.background_color` when one
 * is authored (and visible), else by what is behind the block. Shared with iOS (`DropdownFieldInk`) and driven
 * by the `dropdown_field_ink` fixture.
 */
internal object DropdownFieldInk {
    fun light(fieldBackground: String?, behind: GoogleMapsAttribution.Backdrop, darkAppearance: Boolean): Boolean {
        val bg = fieldBackground?.trim().orEmpty()
        val surface = if (bg.isEmpty() || bg.lowercase() in setOf("transparent", "clear")) behind else GoogleMapsAttribution.Backdrop.Plain(bg)
        return GoogleMapsAttribution.needsLightInk(surface, darkAppearance)
    }
}
