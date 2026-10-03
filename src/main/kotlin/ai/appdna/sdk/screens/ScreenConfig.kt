package ai.appdna.sdk.screens

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import ai.appdna.sdk.core.AudienceRuleSet
import ai.appdna.sdk.core.FrequencyConfig
import ai.appdna.sdk.core.TraitCondition
import ai.appdna.sdk.core.UnifiedTriggerRules
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

/**
 * Screens DTOs annotated for Compose stability.
 *
 * `ScreenSection.data: Map<String, Any?>` is a passthrough JSON bag (per
 * EXCLUDE rule for raw JSON parsing maps + caller-supplied
 * payloads consumed by per-section renderers via `SectionRegistry`). Because
 * of that one field, `ScreenConfig` and `ScreenSection` are `@Stable` rather
 * than `@Immutable`. The `sections` hot iteration list IS migrated to
 * `ImmutableList<ScreenSection>` so a parent re-emit of `ScreenConfig` with
 * the same sections short-circuits per-section recomposition.
 */
@Stable
data class ScreenConfig(
    val id: String,
    val name: String,
    val version: Int = 1,
    val presentation: String = "fullscreen",
    val transition: String? = null,
    val layout: ScreenLayout,
    // Sections list is the hot Compose iteration in
    // AppDNAScreenSlot / ScreenManager; migrated to ImmutableList.
    val sections: ImmutableList<ScreenSection>,
    val background: BackgroundConfig? = null,
    val dismiss: DismissConfig? = null,
    val navBar: NavBarConfig? = null,
    val haptic: HapticConfig? = null,
    val particleEffect: ParticleEffectConfig? = null,
    val localizations: Map<String, Map<String, String>>? = null,
    val defaultLocale: String? = "en",
    val audienceRules: List<Map<String, Any?>>? = null,
    val triggerRules: UnifiedTriggerRules? = null,
    val slotConfig: SlotConfig? = null,
    val startDate: String? = null,
    val endDate: String? = null,
    val minSdkVersion: String? = null,
    val experimentId: String? = null,
    /**
     * Typed variant overrides. Each entry
     * is keyed by variant id and parses into a [ScreenVariantOverride]
     * matching iOS `ScreenVariantOverride`. Mirrors fields the merge step
     * in [ScreenManager] copies onto the base config.
     */
    val variants: Map<String, ScreenVariantOverride>? = null,
    val analyticsName: String? = null,
) {
    companion object {
        @Suppress("UNCHECKED_CAST")
        fun fromMap(data: Map<String, Any?>): ScreenConfig {
            // Parse audience_rules (raw list of rule maps)
            // and variants (per-variant override maps keyed by variant id),
            // matching iOS `Screens/ScreenConfig.swift` ScreenConfig.
            val rawAudienceRules = (data["audience_rules"] as? List<*>)?.mapNotNull {
                @Suppress("UNCHECKED_CAST") (it as? Map<String, Any?>)
            }
            val rawVariants = (data["variants"] as? Map<*, *>)?.entries?.mapNotNull { (k, v) ->
                @Suppress("UNCHECKED_CAST")
                val km = k as? String ?: return@mapNotNull null
                @Suppress("UNCHECKED_CAST")
                val vm = v as? Map<String, Any?> ?: return@mapNotNull null
                km to ScreenVariantOverride.fromMap(vm)
            }?.toMap()

            return ScreenConfig(
                id = data["id"] as? String ?: "",
                name = data["name"] as? String ?: "",
                version = (data["version"] as? Number)?.toInt() ?: 1,
                presentation = data["presentation"] as? String ?: "fullscreen",
                transition = data["transition"] as? String,
                layout = ScreenLayout.fromMap(data["layout"] as? Map<String, Any?> ?: emptyMap()),
                // Wrap as ImmutableList for Compose stability.
                sections = (data["sections"] as? List<Map<String, Any?>>)?.map { ScreenSection.fromMap(it) }?.toImmutableList()
                    ?: kotlinx.collections.immutable.persistentListOf(),
                background = (data["background"] as? Map<String, Any?>)?.let { BackgroundConfig.fromMap(it) },
                dismiss = (data["dismiss"] as? Map<String, Any?>)?.let { DismissConfig.fromMap(it) },
                navBar = (data["nav_bar"] as? Map<String, Any?>)?.let { NavBarConfig.fromMap(it) },
                haptic = (data["haptic"] as? Map<String, Any?>)?.let { HapticConfig.fromMap(it) },
                particleEffect = (data["particle_effect"] as? Map<String, Any?>)?.let { ParticleEffectConfig.fromMap(it) },
                localizations = data["localizations"] as? Map<String, Map<String, String>>,
                defaultLocale = data["default_locale"] as? String ?: "en",
                audienceRules = rawAudienceRules,
                triggerRules = (data["trigger_rules"] as? Map<String, Any?>)?.let { UnifiedTriggerRules.fromMap(it) },
                slotConfig = (data["slot_config"] as? Map<String, Any?>)?.let { SlotConfig.fromMap(it) },
                startDate = data["start_date"] as? String,
                endDate = data["end_date"] as? String,
                minSdkVersion = data["min_sdk_version"] as? String,
                experimentId = data["experiment_id"] as? String,
                variants = rawVariants,
                analyticsName = data["analytics_name"] as? String,
            )
        }
    }
}

/**
 * Typed override applied to a [ScreenConfig]
 * when an experiment variant is selected. Mirrors iOS
 * `Screens/ScreenConfig.swift` `ScreenVariantOverride`. ScreenManager copies
 * the present fields onto the base config; absent fields fall through.
 */
@androidx.compose.runtime.Immutable
data class ScreenVariantOverride(
    val sections: kotlinx.collections.immutable.ImmutableList<ScreenSection>? = null,
    val presentation: String? = null,
    // `transition` parsed at variant level so
    // experiment-driven A/B variants can change just the present-animation
    // (e.g. control = slide_up, treatment = fade) without redefining the rest.
    val transition: String? = null,
    val background: BackgroundConfig? = null,
    val triggerRules: UnifiedTriggerRules? = null,
) {
    companion object {
        @Suppress("UNCHECKED_CAST")
        fun fromMap(data: Map<String, Any?>): ScreenVariantOverride {
            return ScreenVariantOverride(
                sections = (data["sections"] as? List<Map<String, Any?>>)
                    ?.map { ScreenSection.fromMap(it) }
                    ?.toImmutableList(),
                presentation = data["presentation"] as? String,
                transition = data["transition"] as? String,
                background = (data["background"] as? Map<String, Any?>)?.let { BackgroundConfig.fromMap(it) },
                triggerRules = (data["trigger_rules"] as? Map<String, Any?>)?.let { UnifiedTriggerRules.fromMap(it) },
            )
        }
    }
}

data class FlowConfig(
    val id: String,
    val name: String,
    val version: Int = 1,
    val screens: List<FlowScreenRef>,
    val startScreenId: String,
    val settings: FlowSettings,
    val triggerRules: UnifiedTriggerRules? = null,
) {
    companion object {
        @Suppress("UNCHECKED_CAST")
        fun fromMap(data: Map<String, Any?>): FlowConfig {
            return FlowConfig(
                id = data["id"] as? String ?: "",
                name = data["name"] as? String ?: "",
                version = (data["version"] as? Number)?.toInt() ?: 1,
                screens = (data["screens"] as? List<Map<String, Any?>>)?.map { FlowScreenRef.fromMap(it) } ?: emptyList(),
                startScreenId = data["start_screen_id"] as? String ?: "",
                settings = FlowSettings.fromMap(data["settings"] as? Map<String, Any?> ?: emptyMap()),
                triggerRules = (data["trigger_rules"] as? Map<String, Any?>)?.let { UnifiedTriggerRules.fromMap(it) },
            )
        }
    }
}

data class FlowScreenRef(
    val screenId: String,
    val navigationRules: List<NavigationRule>,
) {
    companion object {
        @Suppress("UNCHECKED_CAST")
        fun fromMap(data: Map<String, Any?>): FlowScreenRef {
            return FlowScreenRef(
                screenId = data["screen_id"] as? String ?: "",
                navigationRules = (data["navigation_rules"] as? List<Map<String, Any?>>)?.map { NavigationRule.fromMap(it) } ?: emptyList(),
            )
        }
    }
}

data class NavigationRule(
    val condition: String,
    val variable: String? = null,
    val value: Any? = null,
    val target: String,
    val transition: String? = null,
) {
    companion object {
        fun fromMap(data: Map<String, Any?>): NavigationRule {
            return NavigationRule(
                condition = data["condition"] as? String ?: "always",
                variable = data["variable"] as? String,
                value = data["value"],
                target = data["target"] as? String ?: "next",
                transition = data["transition"] as? String,
            )
        }
    }
}

data class FlowSettings(
    val showProgress: Boolean = true,
    val allowBack: Boolean = true,
    val dismissEnabled: Boolean = true,
    val persistState: Boolean = true,
) {
    companion object {
        fun fromMap(data: Map<String, Any?>): FlowSettings {
            return FlowSettings(
                showProgress = data["show_progress"] as? Boolean ?: true,
                allowBack = data["allow_back"] as? Boolean ?: true,
                dismissEnabled = data["dismiss_enabled"] as? Boolean ?: true,
                persistState = data["persist_state"] as? Boolean ?: true,
            )
        }
    }
}

// @Stable: `data: Map<String, Any?>` is the SDK's passthrough
// JSON bag for per-section renderers (EXCLUDE rule
// passthrough Map<String, Any?>). All other fields are nullable primitives or
// fully-immutable holders. The data field is intentionally NOT migrated to
// PersistentMap because consumers receive it raw and pass it into custom
// renderers — widening the contract would force every host renderer to
// understand kotlinx.collections.immutable.
@Stable
data class ScreenSection(
    val id: String,
    val type: String,
    val data: Map<String, Any?>,
    val style: SectionStyle? = null,
    val visibilityCondition: VisibilityConditionConfig? = null,
    val entranceAnimation: EntranceAnimationConfig? = null,
    val a11y: AccessibilityConfig? = null,
) {
    companion object {
        @Suppress("UNCHECKED_CAST")
        fun fromMap(data: Map<String, Any?>): ScreenSection {
            return ScreenSection(
                id = data["id"] as? String ?: "",
                type = data["type"] as? String ?: "",
                data = data["data"] as? Map<String, Any?> ?: emptyMap(),
                style = (data["style"] as? Map<String, Any?>)?.let { SectionStyle.fromMap(it) },
                visibilityCondition = (data["visibility_condition"] as? Map<String, Any?>)?.let { VisibilityConditionConfig.fromMap(it) },
                entranceAnimation = (data["entrance_animation"] as? Map<String, Any?>)?.let { EntranceAnimationConfig.fromMap(it) },
                a11y = (data["a11y"] as? Map<String, Any?>)?.let { AccessibilityConfig.fromMap(it) },
            )
        }
    }
}

@Immutable
data class ScreenLayout(
    val type: String = "scroll",
    val padding: Double? = null,
    val spacing: Double? = null,
    val safeArea: Boolean? = true,
    val scrollIndicator: Boolean? = false,
) {
    companion object {
        fun fromMap(data: Map<String, Any?>): ScreenLayout {
            return ScreenLayout(
                type = data["type"] as? String ?: "scroll",
                padding = (data["padding"] as? Number)?.toDouble(),
                spacing = (data["spacing"] as? Number)?.toDouble(),
                safeArea = data["safe_area"] as? Boolean ?: true,
                scrollIndicator = data["scroll_indicator"] as? Boolean ?: false,
            )
        }
    }
}

@Immutable
data class SectionStyle(
    val backgroundColor: String? = null,
    val backgroundGradient: GradientConfig? = null,
    val paddingTop: Double? = null,
    val paddingRight: Double? = null,
    val paddingBottom: Double? = null,
    val paddingLeft: Double? = null,
    val marginTop: Double? = null,
    val marginBottom: Double? = null,
    val borderRadius: Double? = null,
    val borderColor: String? = null,
    val borderWidth: Double? = null,
    val shadow: ShadowConfig? = null,
    val opacity: Double? = null,
) {
    companion object {
        @Suppress("UNCHECKED_CAST")
        fun fromMap(data: Map<String, Any?>): SectionStyle {
            return SectionStyle(
                backgroundColor = data["background_color"] as? String,
                backgroundGradient = (data["background_gradient"] as? Map<String, Any?>)?.let { GradientConfig.fromMap(it) },
                paddingTop = (data["padding_top"] as? Number)?.toDouble(),
                paddingRight = (data["padding_right"] as? Number)?.toDouble(),
                paddingBottom = (data["padding_bottom"] as? Number)?.toDouble(),
                paddingLeft = (data["padding_left"] as? Number)?.toDouble(),
                marginTop = (data["margin_top"] as? Number)?.toDouble(),
                marginBottom = (data["margin_bottom"] as? Number)?.toDouble(),
                borderRadius = (data["border_radius"] as? Number)?.toDouble(),
                borderColor = data["border_color"] as? String,
                borderWidth = (data["border_width"] as? Number)?.toDouble(),
                shadow = (data["shadow"] as? Map<String, Any?>)?.let { ShadowConfig.fromMap(it) },
                opacity = (data["opacity"] as? Number)?.toDouble(),
            )
        }
    }
}

data class DismissConfig(val enabled: Boolean = true, val style: String? = "x_button", val position: String? = "top_right") {
    companion object { fun fromMap(data: Map<String, Any?>) = DismissConfig(data["enabled"] as? Boolean ?: true, data["style"] as? String, data["position"] as? String) }
}

data class NavBarConfig(val title: String? = null, val showBack: Boolean? = false, val showClose: Boolean? = false, val backgroundColor: String? = null) {
    companion object { fun fromMap(data: Map<String, Any?>) = NavBarConfig(data["title"] as? String, data["show_back"] as? Boolean, data["show_close"] as? Boolean, data["background_color"] as? String) }
}

data class SlotConfig(val presentation: String = "inline", val tapToExpand: Boolean? = false, val maxHeight: Double? = null, val placeholder: PlaceholderConfig? = null) {
    companion object { @Suppress("UNCHECKED_CAST") fun fromMap(data: Map<String, Any?>) = SlotConfig(data["presentation"] as? String ?: "inline", data["tap_to_expand"] as? Boolean, (data["max_height"] as? Number)?.toDouble(), (data["placeholder"] as? Map<String, Any?>)?.let { PlaceholderConfig.fromMap(it) }) }
}

data class PlaceholderConfig(val type: String = "none", val height: Double? = null) {
    companion object { fun fromMap(data: Map<String, Any?>) = PlaceholderConfig(data["type"] as? String ?: "none", (data["height"] as? Number)?.toDouble()) }
}

data class BackgroundConfig(val type: String? = null, val color: String? = null, val gradient: GradientConfig? = null, val imageUrl: String? = null, val opacity: Double? = 1.0) {
    companion object { @Suppress("UNCHECKED_CAST") fun fromMap(data: Map<String, Any?>) = BackgroundConfig(data["type"] as? String, data["color"] as? String, (data["gradient"] as? Map<String, Any?>)?.let { GradientConfig.fromMap(it) }, data["image_url"] as? String, (data["opacity"] as? Number)?.toDouble() ?: 1.0) }
}

data class GradientConfig(val angle: Double? = null, val start: String? = null, val end: String? = null) {
    companion object { fun fromMap(data: Map<String, Any?>) = GradientConfig((data["angle"] as? Number)?.toDouble(), data["start"] as? String, data["end"] as? String) }
}

data class ShadowConfig(val x: Double? = null, val y: Double? = null, val blur: Double? = null, val spread: Double? = null, val color: String? = null) {
    companion object { fun fromMap(data: Map<String, Any?>) = ShadowConfig((data["x"] as? Number)?.toDouble(), (data["y"] as? Number)?.toDouble(), (data["blur"] as? Number)?.toDouble(), (data["spread"] as? Number)?.toDouble(), data["color"] as? String) }
}

data class HapticConfig(val type: String? = null, val onPresent: Boolean? = false) {
    companion object { fun fromMap(data: Map<String, Any?>) = HapticConfig(data["type"] as? String, data["on_present"] as? Boolean) }
}

data class ParticleEffectConfig(val type: String? = null, val durationMs: Int? = null, val intensity: String? = null, val onPresent: Boolean? = false) {
    companion object { fun fromMap(data: Map<String, Any?>) = ParticleEffectConfig(data["type"] as? String, (data["duration_ms"] as? Number)?.toInt(), data["intensity"] as? String, data["on_present"] as? Boolean) }
}

data class VisibilityConditionConfig(val type: String, val variable: String? = null, val value: Any? = null, val expression: String? = null) {
    companion object { fun fromMap(data: Map<String, Any?>) = VisibilityConditionConfig(data["type"] as? String ?: "always", data["variable"] as? String, data["value"], data["expression"] as? String) }
}

data class EntranceAnimationConfig(val type: String, val durationMs: Int? = null, val delayMs: Int? = null, val easing: String? = null, val springDamping: Double? = null) {
    companion object { fun fromMap(data: Map<String, Any?>) = EntranceAnimationConfig(data["type"] as? String ?: "none", (data["duration_ms"] as? Number)?.toInt(), (data["delay_ms"] as? Number)?.toInt(), data["easing"] as? String, (data["spring_damping"] as? Number)?.toDouble()) }
}

data class AccessibilityConfig(val label: String? = null, val hint: String? = null, val role: String? = null, val hidden: Boolean? = false) {
    companion object { fun fromMap(data: Map<String, Any?>) = AccessibilityConfig(data["label"] as? String, data["hint"] as? String, data["role"] as? String, data["hidden"] as? Boolean) }
}

// Screen Index
data class ScreenIndex(
    val screens: List<ScreenIndexEntry>? = null,
    val flows: List<ScreenIndexEntry>? = null,
    val slots: List<SlotAssignment>? = null,
    val interceptions: List<NavigationInterceptionConfig>? = null,
) {
    companion object {
        @Suppress("UNCHECKED_CAST")
        fun fromMap(data: Map<String, Any?>): ScreenIndex {
            return ScreenIndex(
                screens = (data["screens"] as? List<Map<String, Any?>>)?.map { ScreenIndexEntry.fromMap(it) },
                flows = (data["flows"] as? List<Map<String, Any?>>)?.map { ScreenIndexEntry.fromMap(it) },
                slots = (data["slots"] as? List<Map<String, Any?>>)?.map { SlotAssignment.fromMap(it) },
                interceptions = (data["interceptions"] as? List<Map<String, Any?>>)?.map { NavigationInterceptionConfig.fromMap(it) },
            )
        }
    }
}

data class ScreenIndexEntry(
    val id: String,
    val name: String,
    val triggerRules: UnifiedTriggerRules? = null,
    val audienceRules: AudienceRuleSet? = null,
    val priority: Int? = null,
    val startDate: String? = null,
    val endDate: String? = null,
    val minSdkVersion: String? = null,
) {
    companion object {
        @Suppress("UNCHECKED_CAST")
        fun fromMap(data: Map<String, Any?>): ScreenIndexEntry {
            return ScreenIndexEntry(
                id = data["id"] as? String ?: "",
                name = data["name"] as? String ?: "",
                triggerRules = (data["trigger_rules"] as? Map<String, Any?>)?.let { UnifiedTriggerRules.fromMap(it) },
                audienceRules = (data["audience_rules"] as? Map<String, Any?>)?.let { AudienceRuleSet.fromMap(it) },
                priority = (data["priority"] as? Number)?.toInt(),
                startDate = data["start_date"] as? String,
                endDate = data["end_date"] as? String,
                minSdkVersion = data["min_sdk_version"] as? String,
            )
        }
    }
}

data class SlotAssignment(val slotName: String, val screenId: String, val audienceRules: AudienceRuleSet? = null) {
    companion object {
        @Suppress("UNCHECKED_CAST")
        fun fromMap(data: Map<String, Any?>) = SlotAssignment(
            data["slot_name"] as? String ?: "",
            data["screen_id"] as? String ?: "",
            (data["audience_rules"] as? Map<String, Any?>)?.let { AudienceRuleSet.fromMap(it) },
        )
    }
}

data class NavigationInterceptionConfig(
    val id: String, val triggerScreen: String, val timing: String, val screenId: String,
    val audienceRules: AudienceRuleSet? = null, val userTraits: List<TraitCondition>? = null, val frequency: FrequencyConfig? = null,
) {
    companion object {
        @Suppress("UNCHECKED_CAST")
        fun fromMap(data: Map<String, Any?>) = NavigationInterceptionConfig(
            data["id"] as? String ?: "", data["trigger_screen"] as? String ?: "",
            data["timing"] as? String ?: "after", data["screen_id"] as? String ?: "",
            (data["audience_rules"] as? Map<String, Any?>)?.let { AudienceRuleSet.fromMap(it) },
            (data["user_traits"] as? List<Map<String, Any?>>)?.map { TraitCondition.fromMap(it) },
            (data["frequency"] as? Map<String, Any?>)?.let { FrequencyConfig.fromMap(it) },
        )
    }
}

// Results
enum class ScreenError { CONFIG_FETCH_FAILED, CONFIG_FETCH_TIMEOUT, SCREEN_NOT_FOUND, CONFIG_PARSE_ERROR, CONFIG_INVALID, NESTING_DEPTH_EXCEEDED }

data class ScreenResult(
    val screenId: String, val dismissed: Boolean = false, val responses: Map<String, Any> = emptyMap(),
    val lastAction: String? = null, val durationMs: Int = 0, val error: ScreenError? = null,
)

data class FlowResult(
    val flowId: String, val completed: Boolean = false, val lastScreenId: String = "",
    val responses: Map<String, Any> = emptyMap(), val screensViewed: List<String> = emptyList(),
    val durationMs: Int = 0, val error: ScreenError? = null,
)
