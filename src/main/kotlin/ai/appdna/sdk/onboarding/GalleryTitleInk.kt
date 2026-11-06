package ai.appdna.sdk.onboarding

/**
 * The colour of a `media_gallery` title: the authored `gallery_title_color`, else the unstyled ink for what is
 * behind the gallery — `light` (white) on a dark surface, `dark` (the theme's text ink) on a light one. Shared
 * with iOS and driven by the `media_gallery_title_ink` fixture. iOS defaulted to white everywhere (invisible on
 * a light step) while Android used the theme's dark ink everywhere (invisible on a dark one).
 */
internal object GalleryTitleInk {
    fun resolve(authored: String?, behind: GoogleMapsAttribution.Backdrop, darkAppearance: Boolean): String {
        authored?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        return if (GoogleMapsAttribution.needsLightInk(behind, darkAppearance)) "light" else "dark"
    }
}
