package ai.appdna.sdk.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import ai.appdna.sdk.screens.sections.*

internal object SectionRegistry {
    private val renderers = mutableMapOf<String, @Composable (ScreenSection, SectionContext) -> Unit>()

    fun register(type: String, renderer: @Composable (ScreenSection, SectionContext) -> Unit) {
        renderers[type] = renderer
    }

    /**
     * #904 — the authored section style is applied HERE, once, rather than at each of the eight
     * `Render` call sites across [ScreenHostActivity] and [AppDNAScreenSlot]. iOS applies it at the
     * two places it builds a section view; a per-call-site wrapper on this side would be eight
     * chances to forget one, which is how the style came to be dropped on every one of them.
     */
    @Composable
    fun Render(section: ScreenSection, context: SectionContext) {
        // What the section draws behind its content, for a Google logo kept beside a location field.
        val backdrop = ai.appdna.sdk.onboarding.GoogleMapsAttribution.backdrop(
            ofSection = section.style,
            behind = ai.appdna.sdk.onboarding.LocalGoogleAttributionBackdrop.current,
        )
        androidx.compose.foundation.layout.Box(
            modifier = androidx.compose.ui.Modifier
                .fillMaxWidth()
                .applySectionStyle(section.style),
        ) {
            androidx.compose.runtime.CompositionLocalProvider(
                ai.appdna.sdk.onboarding.LocalGoogleAttributionBackdrop provides backdrop,
            ) {
                RenderUnstyled(section, context)
            }
        }
    }

    /** The section itself, with no section-level styling. Split out so [Render] has one job. */
    @Composable
    private fun RenderUnstyled(section: ScreenSection, context: SectionContext) {
        val renderer = renderers[section.type]
        if (renderer != null) {
            renderer(section, context)
            return
        }
        // content_blocks fallback for unknown section types that
        // ship `data.blocks`. Mirrors iOS SectionRegistry.swift:37-43 so
        // console-experimental section types not yet known to the SDK still
        // render their inline blocks instead of disappearing.
        if (section.data["blocks"] != null) {
            renderers["content_blocks"]?.invoke(section, context)
        }
        // Otherwise unknown sections render nothing
    }

    fun hasRenderer(type: String): Boolean = renderers.containsKey(type)

    fun registerBuiltInSections() {
        register("content_blocks") { section, context -> ContentBlocksSectionRenderer(section, context) }
        register("hero") { section, context -> HeroSectionRenderer(section, context) }
        register("spacer") { section, context -> SpacerSectionRenderer(section, context) }
        register("divider") { section, context -> DividerSectionRenderer(section, context) }
        register("cta_footer") { section, context -> CTAFooterSectionRenderer(section, context) }
        register("sticky_footer") { section, context -> CTAFooterSectionRenderer(section, context) }
        register("image_section") { section, context -> ImageSectionRenderer(section, context) }
        register("video_section") { section, context -> VideoSectionRenderer(section, context) }
        register("lottie_section") { section, context -> LottieSectionRenderer(section, context) }
        register("rive_section") { section, context -> RiveSectionRenderer(section, context) }
    }
}
