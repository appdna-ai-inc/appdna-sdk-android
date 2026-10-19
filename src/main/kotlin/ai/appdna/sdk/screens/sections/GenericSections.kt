package ai.appdna.sdk.screens.sections

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import ai.appdna.sdk.screens.ScreenSection
import ai.appdna.sdk.screens.SectionAction
import ai.appdna.sdk.screens.SectionContext
import ai.appdna.sdk.onboarding.RenderBlock
import ai.appdna.sdk.onboarding.ContentBlock
import ai.appdna.sdk.core.StyleEngine

/**
 * Expanded ContentBlock decoder used by
 * SDUI ContentBlocksSectionRenderer. Mirrors ~35 of the ContentBlock
 * fields from the canonical OnboardingConfig.kt parser (still missing
 * lottie_json, providers, field_options, timeline_items, loading_items,
 * and a handful of style sub-objects — those require helpers that live
 * inside `OnboardingConfigParser`'s private scope and need a dedicated
 * extraction pass to share). Common content-block use cases (text,
 * heading, button, image, video, lottie, rive, icon, divider, badge,
 * spacer, list, page_indicator, social_login outer fields, countdown,
 * progress_bar, rating, rich_text, form input fields) all populate.
 */
@Suppress("UNCHECKED_CAST")
private fun decodeContentBlock(map: Map<String, Any?>): ContentBlock = ContentBlock(
    id = map["id"] as? String ?: "",
    type = map["type"] as? String ?: "text",
    text = map["text"] as? String,
    level = (map["level"] as? Number)?.toInt(),
    image_url = map["image_url"] as? String,
    alt = map["alt"] as? String,
    corner_radius = (map["corner_radius"] as? Number)?.toDouble(),
    height = (map["height"] as? Number)?.toDouble(),
    variant = map["variant"] as? String,
    action = map["action"] as? String,
    action_value = map["action_value"] as? String,
    bg_color = map["bg_color"] as? String,
    text_color = map["text_color"] as? String,
    button_corner_radius = (map["button_corner_radius"] as? Number)?.toDouble(),
    spacer_height = (map["spacer_height"] as? Number)?.toDouble(),
    items = (map["items"] as? List<*>)?.filterIsInstance<String>()
        ?.let { kotlinx.collections.immutable.persistentListOf<String>().addAll(it) },
    list_style = map["list_style"] as? String,
    divider_color = map["divider_color"] as? String,
    divider_thickness = (map["divider_thickness"] as? Number)?.toDouble(),
    divider_margin_y = (map["divider_margin_y"] as? Number)?.toDouble(),
    badge_text = map["badge_text"] as? String,
    badge_bg_color = map["badge_bg_color"] as? String,
    badge_text_color = map["badge_text_color"] as? String,
    badge_corner_radius = (map["badge_corner_radius"] as? Number)?.toDouble(),
    icon_emoji = map["icon_emoji"] as? String,
    icon_size = (map["icon_size"] as? Number)?.toDouble(),
    icon_alignment = map["icon_alignment"] as? String,
    toggle_label = map["toggle_label"] as? String,
    toggle_description = map["toggle_description"] as? String,
    toggle_default = map["toggle_default"] as? Boolean,
    video_thumbnail_url = map["video_thumbnail_url"] as? String,
    video_url = map["video_url"] as? String,
    video_height = (map["video_height"] as? Number)?.toDouble(),
    video_corner_radius = (map["video_corner_radius"] as? Number)?.toDouble(),
    lottie_url = map["lottie_url"] as? String,
    lottie_autoplay = map["lottie_autoplay"] as? Boolean,
    lottie_loop = map["lottie_loop"] as? Boolean,
    lottie_speed = (map["lottie_speed"] as? Number)?.toFloat(),
    rive_url = map["rive_url"] as? String,
    rive_artboard = map["rive_artboard"] as? String,
    rive_state_machine = map["rive_state_machine"] as? String,
    icon_ref = map["icon_ref"] ?: map["icon"],
    video_autoplay = map["video_autoplay"] as? Boolean,
    video_loop = map["video_loop"] as? Boolean,
    video_muted = map["video_muted"] as? Boolean,
    vertical_align = map["vertical_align"] as? String,
    horizontal_align = map["horizontal_align"] as? String,
    vertical_offset = (map["vertical_offset"] as? Number)?.toDouble(),
    horizontal_offset = (map["horizontal_offset"] as? Number)?.toDouble(),
    zone = map["zone"] as? String,
    field_id = map["field_id"] as? String,
    field_label = map["field_label"] as? String,
    field_placeholder = map["field_placeholder"] as? String,
    field_required = map["field_required"] as? Boolean,
    label = map["label"] as? String,
    button_style = map["button_style"] as? String,
    button_height = (map["button_height"] as? Number)?.toDouble(),
    spacing = (map["spacing"] as? Number)?.toDouble(),
    show_divider = map["show_divider"] as? Boolean,
    divider_text = map["divider_text"] as? String,
    accent_color = map["accent_color"] as? String,
    font_size = (map["font_size"] as? Number)?.toDouble(),
    alignment = map["alignment"] as? String,
    max_stars = (map["max_stars"] as? Number)?.toInt(),
    default_value = (map["default_value"] as? Number)?.toDouble(),
    star_size = (map["star_size"] as? Number)?.toDouble(),
    allow_half = map["allow_half"] as? Boolean,
    content = map["content"] as? String,
    link_color = map["link_color"] as? String,
    max_lines = (map["max_lines"] as? Number)?.toInt(),
)

@Composable
internal fun ContentBlocksSectionRenderer(section: ScreenSection, context: SectionContext) {
    @Suppress("UNCHECKED_CAST")
    val blocksData = section.data["blocks"] as? List<Map<String, Any?>> ?: return
    val blocks = blocksData.map { decodeContentBlock(it) }
    val spacing = (section.data["spacing"] as? Number)?.toDouble() ?: 12.0
    val toggleValues = remember { mutableStateMapOf<String, Boolean>() }
    val inputValues = remember { mutableStateMapOf<String, Any>() }

    // Gate `next` action on required-field
    // validation. Mirrors iOS GenericSections.swift:58-88 validateFormFields()
    // Without this, hosts pressing "next" with an empty
    // required input field would advance silently. We block the advance
    // and (best-effort) leave field-level error UI to the renderer.
    fun validateRequiredFields(): Boolean {
        for (b in blocks) {
            if (b.field_required == true) {
                val key = b.field_id ?: b.id
                val value = inputValues[key]
                val isEmpty = when (value) {
                    null -> true
                    is String -> value.isBlank()
                    is List<*> -> value.isEmpty()
                    is Map<*, *> -> value.isEmpty()
                    else -> false
                }
                if (isEmpty) return false
            }
        }
        return true
    }

    Column(verticalArrangement = Arrangement.spacedBy(spacing.dp)) {
        for (block in blocks) {
            RenderBlock(
                block = block,
                onAction = { action ->
                    when (action) {
                        "next" -> if (validateRequiredFields()) context.onAction(SectionAction.Next)
                        "dismiss" -> context.onAction(SectionAction.Dismiss)
                        "back" -> context.onAction(SectionAction.Back)
                        else -> context.onAction(SectionAction.Custom(action, null))
                    }
                },
                toggleValues = toggleValues,
                inputValues = inputValues,
                currentStepIndex = context.currentScreenIndex,
                totalSteps = context.totalScreens,
            )
        }
    }
}

@Composable
internal fun HeroSectionRenderer(section: ScreenSection, context: SectionContext) {
    val height = (section.data["height"] as? Number)?.toDouble() ?: 300.0

    Box(
        modifier = Modifier.fillMaxWidth().height(height.dp),
        contentAlignment = Alignment.BottomStart,
    ) {
        // Background placeholder
        Box(modifier = Modifier.fillMaxSize().background(Color.Gray.copy(alpha = 0.1f)))

        // Overlay content (simplified)
        @Suppress("UNCHECKED_CAST")
        val overlayData = section.data["overlay_blocks"] as? List<Map<String, Any?>>
        if (overlayData != null) {
            Column(modifier = Modifier.padding(16.dp)) {
                val blocks = overlayData.map { map ->
                    ContentBlock(
                        id = map["id"] as? String ?: "",
                        type = map["type"] as? String ?: "text",
                        text = map["text"] as? String,
                    )
                }
                val toggleValues = remember { mutableStateMapOf<String, Boolean>() }
                val inputValues = remember { mutableStateMapOf<String, Any>() }
                for (block in blocks) {
                    RenderBlock(block, { context.onAction(SectionAction.Custom(it, null)) }, toggleValues, inputValues)
                }
            }
        }
    }
}

@Composable
internal fun SpacerSectionRenderer(section: ScreenSection, context: SectionContext) {
    val height = (section.data["height"] as? Number)?.toDouble() ?: 16.0
    Spacer(modifier = Modifier.height(height.dp))
}

@Composable
internal fun DividerSectionRenderer(section: ScreenSection, context: SectionContext) {
    val color = section.data["color"] as? String
    val thickness = (section.data["thickness"] as? Number)?.toDouble() ?: 1.0
    val insetLeft = (section.data["inset_left"] as? Number)?.toDouble() ?: 0.0
    val insetRight = (section.data["inset_right"] as? Number)?.toDouble() ?: 0.0

    @Suppress("DEPRECATION")
    Divider(
        modifier = Modifier.padding(start = insetLeft.dp, end = insetRight.dp),
        thickness = thickness.dp,
        color = if (color != null) StyleEngine.parseColor(color) else Color.Gray.copy(alpha = 0.3f),
    )
}

@Composable
internal fun CTAFooterSectionRenderer(section: ScreenSection, context: SectionContext) {
    @Suppress("UNCHECKED_CAST")
    val primaryButton = section.data["primary_button"] as? Map<String, Any?>
    @Suppress("UNCHECKED_CAST")
    val secondaryButton = section.data["secondary_button"] as? Map<String, Any?>
    val disclaimerText = section.data["disclaimer_text"] as? String

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (primaryButton != null) {
            Button(
                onClick = { handleButtonAction(primaryButton, context) },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(primaryButton["text"] as? String ?: "Continue")
            }
        }

        if (secondaryButton != null) {
            TextButton(onClick = { handleButtonAction(secondaryButton, context) }) {
                Text(secondaryButton["text"] as? String ?: "Skip")
            }
        }

        if (disclaimerText != null) {
            Text(
                text = disclaimerText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun handleButtonAction(button: Map<String, Any?>, context: SectionContext) {
    val action = button["action"] as? String ?: "next"
    val actionValue = button["action_value"] as? String

    when (action) {
        "next" -> context.onAction(SectionAction.Next)
        "dismiss" -> context.onAction(SectionAction.Dismiss)
        "back" -> context.onAction(SectionAction.Back)
        "open_url" -> actionValue?.let { context.onAction(SectionAction.OpenURL(it)) }
        "open_in_webview" -> actionValue?.let { context.onAction(SectionAction.OpenWebview(it)) }
        "deep_link" -> actionValue?.let { context.onAction(SectionAction.DeepLink(it)) }
        "show_paywall" -> context.onAction(SectionAction.ShowPaywall(actionValue))
        "show_survey" -> context.onAction(SectionAction.ShowSurvey(actionValue))
        "show_screen" -> actionValue?.let { context.onAction(SectionAction.ShowScreen(it)) }
        "share" -> context.onAction(SectionAction.Share(actionValue ?: ""))
        "open_app_settings" -> context.onAction(SectionAction.OpenAppSettings)
        else -> context.onAction(SectionAction.Custom(action, actionValue))
    }
}

/**
 * image_section now loads `image_url`
 * via [ai.appdna.sdk.core.NetworkImage] (Coil under the hood) instead
 * of rendering a gray Box. Mirrors iOS BundledAsyncImage.
 */
@Composable
internal fun ImageSectionRenderer(section: ScreenSection, context: SectionContext) {
    val height = (section.data["height"] as? Number)?.toDouble() ?: 200.0
    val cornerRadius = (section.data["corner_radius"] as? Number)?.toDouble() ?: 0.0
    val url = section.data["image_url"] as? String ?: section.data["url"] as? String

    val mod = Modifier.fillMaxWidth().height(height.dp)
        .let { if (cornerRadius > 0) it.clip(RoundedCornerShape(cornerRadius.dp)) else it }
    if (url != null) {
        ai.appdna.sdk.core.NetworkImage(
            url = url,
            modifier = mod,
            contentDescription = section.data["alt"] as? String,
        )
    } else {
        Box(modifier = mod.background(Color.Gray.copy(alpha = 0.1f)))
    }
}

/**
 * video_section uses [VideoBlockView]
 * with the section data, mirroring iOS.
 */
@Composable
internal fun VideoSectionRenderer(section: ScreenSection, context: SectionContext) {
    val url = section.data["video_url"] as? String ?: section.data["url"] as? String
    val height = (section.data["video_height"] as? Number)?.toFloat()
        ?: (section.data["height"] as? Number)?.toFloat() ?: 200f
    if (url == null) {
        Box(modifier = Modifier.fillMaxWidth().height(height.dp).background(Color.Black))
        return
    }
    ai.appdna.sdk.core.VideoBlockView(
        block = ai.appdna.sdk.core.VideoBlock(
            video_url = url,
            // #981 — the screens editor writes `thumbnail_url` (SectionPropertiesPanel.tsx:521)
            // and iOS's VideoSectionRenderer reads exactly that, so an authored poster frame
            // reached nothing here. The prefixed spelling stays first for a section pasted from a
            // content block.
            video_thumbnail_url = section.data["video_thumbnail_url"] as? String
                ?: section.data["thumbnail_url"] as? String,
            video_height = height,
            video_corner_radius = (section.data["video_corner_radius"] as? Number)?.toFloat()
                ?: (section.data["corner_radius"] as? Number)?.toFloat(),
            autoplay = section.data["video_autoplay"] as? Boolean ?: section.data["autoplay"] as? Boolean,
            loop = section.data["video_loop"] as? Boolean ?: section.data["loop"] as? Boolean,
            muted = section.data["video_muted"] as? Boolean ?: section.data["muted"] as? Boolean,
        ),
    )
}

/**
 * lottie_section uses [LottieBlockView],
 * mirroring iOS.
 */
@Composable
internal fun LottieSectionRenderer(section: ScreenSection, context: SectionContext) {
    val url = section.data["lottie_url"] as? String ?: section.data["url"] as? String
    @Suppress("UNCHECKED_CAST")
    val json = section.data["lottie_json"] as? Map<String, Any>
    val height = (section.data["lottie_height"] as? Number)?.toFloat()
        ?: (section.data["height"] as? Number)?.toFloat() ?: 200f
    if (url == null && json == null) {
        Box(modifier = Modifier.fillMaxWidth().height(height.dp))
        return
    }
    ai.appdna.sdk.core.LottieBlockView(
        block = ai.appdna.sdk.core.LottieBlock(
            lottie_url = url,
            lottie_json = json,
            autoplay = section.data["lottie_autoplay"] as? Boolean ?: section.data["autoplay"] as? Boolean ?: true,
            loop = section.data["lottie_loop"] as? Boolean ?: section.data["loop"] as? Boolean ?: true,
            // #981 — same: the editor's Speed slider writes `speed`, which iOS reads, and this
            // read only the prefixed name, so every authored playback speed was 1.0 on Android.
            speed = (section.data["lottie_speed"] as? Number)?.toFloat()
                ?: (section.data["speed"] as? Number)?.toFloat() ?: 1.0f,
            height = height,
        ),
    )
}

/**
 * rive_section uses [RiveBlockView],
 * mirroring iOS.
 */
@Composable
internal fun RiveSectionRenderer(section: ScreenSection, context: SectionContext) {
    val url = section.data["rive_url"] as? String ?: section.data["url"] as? String
    val height = (section.data["height"] as? Number)?.toFloat() ?: 200f
    if (url == null) {
        Box(modifier = Modifier.fillMaxWidth().height(height.dp))
        return
    }
    ai.appdna.sdk.core.RiveBlockView(
        block = ai.appdna.sdk.core.RiveBlock(
            rive_url = url,
            artboard = section.data["rive_artboard"] as? String ?: section.data["artboard"] as? String,
            state_machine = section.data["rive_state_machine"] as? String ?: section.data["state_machine"] as? String,
            autoplay = section.data["autoplay"] as? Boolean ?: true,
            height = height,
        ),
    )
}
