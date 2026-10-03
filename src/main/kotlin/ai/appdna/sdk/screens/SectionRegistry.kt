package ai.appdna.sdk.screens

import androidx.compose.runtime.Composable
import ai.appdna.sdk.screens.sections.*

internal object SectionRegistry {
    private val renderers = mutableMapOf<String, @Composable (ScreenSection, SectionContext) -> Unit>()

    fun register(type: String, renderer: @Composable (ScreenSection, SectionContext) -> Unit) {
        renderers[type] = renderer
    }

    @Composable
    fun Render(section: ScreenSection, context: SectionContext) {
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
