package ai.appdna.sdk.onboarding

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import ai.appdna.sdk.core.TextStyleConfig
import ai.appdna.sdk.core.HapticConfig
import ai.appdna.sdk.core.HapticTriggers
import ai.appdna.sdk.core.ParticleEffect
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

/**
 * Firestore schema types for onboarding flows (Android).
 * Mirrors the iOS OnboardingConfig.swift.
 *
 * SPEC-070-A J.10 + J.22 — Compose stability:
 *   These config DTOs are hot-path Compose parameters threaded into
 *   OnboardingFlowHost / FormStepComposable / ContentBlockRenderer.
 *   They're annotated `@Immutable` (or `@Stable` for the few that still hold
 *   passthrough JSON Maps) so Compose can skip recompositions when an outer
 *   parent re-emits with structurally-equal contents. The annotation is only
 *   honored when iterable fields use `ImmutableList<T>` rather than stock
 *   `List<T>` — Kotlin's stock List is mutable-by-interface so Compose's
 *   stability inference can't prove safety on its own.
 */

/**
 * A single onboarding flow definition.
 *
 * SPEC-070-A A.5: `@Keep` so R8/minify cannot strip getters used by
 * reflective `fromMap`-style parsing in [OnboardingConfigParser].
 */
// SPEC-070-A J.10 — @Stable rather than @Immutable: graph_layout / graph_nodes
// are passthrough JSON bags from Firestore (Map<String, Any?>) that we don't
// migrate (per SPEC-070-A J.22 EXCLUDE rule for raw JSON parsing maps), and
// audience_rules is `Any?` (untyped passthrough). Steps IS migrated to
// ImmutableList<OnboardingStep> so the hot-path step list is Compose-stable.
@Stable
@androidx.annotation.Keep
data class OnboardingFlowConfig(
    val id: String,
    val name: String,
    val version: Int,
    val steps: ImmutableList<OnboardingStep>,
    val settings: OnboardingSettings,
    // SPEC-070-A F.1: top-level fields mirroring iOS OnboardingFlowConfig
    val status: String? = null,
    val graph_layout: Map<String, Any?>? = null,
    /**
     * Lightweight extract of SDK-relevant graph nodes (paywall_trigger, login, end).
     * Keyed by node ID for O(1) lookup. Preferred over graph_layout for SDK use.
     */
    val graph_nodes: Map<String, Any?>? = null,
    /**
     * Audience targeting rules. Either a `[Map]` list of conditions OR an object with
     * `{priority, conditions, match_mode}`. Stored raw — evaluated at present-time
     * by [OnboardingFlowManager] via `AudienceRuleEvaluator`.
     */
    val audience_rules: Any? = null,
)

/**
 * Flow-level settings.
 *
 * SPEC-070-A A.5: `@Keep` so R8/minify cannot strip getters used by reflection
 * via [OnboardingConfigParser].
 */
@Immutable
@androidx.annotation.Keep
data class BackButtonStyle(
    val icon_size: Double? = null,
    val icon_color: String? = null,
    val position: String? = null,  // "left" | "right"
    // EPIC-2 — custom back glyph (any char, default "←") + back-arrow⇄X switch
    // (close_on_first: show the dismiss "✕" in the leading slot on the first/no-history step).
    val icon: String? = null,
    val close_on_first: Boolean? = null,
)

@Immutable
@androidx.annotation.Keep
data class OnboardingSettings(
    val show_progress: Boolean = true,
    val allow_back: Boolean = true,
    val skip_to_step: String? = null,
    // SPEC-085: Rich media config
    val haptic: HapticConfig? = null,
    val particle_effect: ParticleEffect? = null,
    // Gap 9: Custom progress bar colors
    val progress_color: String? = null,
    val progress_track_color: String? = null,
    // EPIC-2 — thin sizing (custom height; the Material bar floored ~4dp) + multiple colors at once.
    val progress_height: Double? = null,
    val progress_gradient_colors: List<String>? = null,
    // EPIC-2 — optional "Skip" link rendered beside the progress bar (Flo).
    val progress_skip_label: String? = null,
    // EPIC-2 — dynamic color flash on step-advance (Duolingo "success" pulse): the progress fill briefly
    // animates to this color on each advance, then back to the normal fill.
    val progress_flash_color: String? = null,
    // SPEC-070-A F.1: progress style + back button + content padding (iOS parity)
    val progress_style: String? = null,  // "dots" | "segmented_bar" | "continuous_bar" | "fraction" | "none"
    val back_button_style: BackButtonStyle? = null,
    val dismiss_allowed: Boolean? = null,
    /** Global horizontal content padding in points. Default 24. */
    val content_padding: Double? = null,
    /** Global vertical spacing between content blocks in points. Default 12. */
    val block_spacing: Double? = null,
)

/**
 * A single step within a flow.
 *
 * SPEC-070-A A.21: matches iOS `NextStepRule` shape — supports both the
 * legacy single-`condition` field AND the multi-`conditions` array with
 * AND/OR `logic` composition. Mirrors `OnboardingConfig.swift:122-127`.
 *
 * Evaluation precedence (see [NextStepRuleEvaluator]):
 *   1. If `conditions` is non-null/non-empty → evaluate every entry under `logic`.
 *   2. Else if `condition` is non-null → wrap as a 1-element list.
 *   3. Else → treat as `always` (rule matches unconditionally).
 */
// SPEC-070-A J.10 — @Stable: `condition: Any?` and `conditions: List<Map<String, Any?>>`
// are passthrough condition expressions evaluated at runtime; the inner Map is the
// exception listed in J.22 EXCLUDE (raw JSON parsing map — we don't deep-migrate
// the per-condition map). The OUTER list IS migrated to ImmutableList so Compose
// can prove the rule list itself is stable.
@Stable
@androidx.annotation.Keep
data class NextStepRule(
    val condition: Any? = null,
    val conditions: ImmutableList<Map<String, Any?>>? = null,
    val logic: String? = null,  // "and" | "or" — null defaults to "and" at evaluation time
    val target_step_id: String = ""
)

@Immutable
data class OnboardingStep(
    val id: String,
    val type: StepType,
    val config: StepConfig,
    val hook: StepHookConfig? = null,
    /** When true, the progress indicator is hidden on this step but the step still counts toward total progress. */
    val hide_progress: Boolean? = null,
    /** When true, the back button is hidden on this step (the step still counts toward total progress). */
    val hide_back: Boolean? = null,
    val next_step_rules: ImmutableList<NextStepRule>? = null
) {
    enum class StepType(val value: String) {
        WELCOME("welcome"),
        QUESTION("question"),
        VALUE_PROP("value_prop"),
        CUSTOM("custom"),
        FORM("form"),
        INTERACTIVE_CHAT("interactive_chat"),
        // SPEC-401-A — `info` and `permission` are author-friendly step
        // types declared in `flow.schema.ts STEP_TYPES`. iOS routes both
        // through CustomStepView (OnboardingRenderer.swift:1497). Adding
        // explicit cases here lets Android dispatch route them through
        // CustomStep so configured title/subtitle/cta render rather than
        // silently falling back via `fromString` else clause.
        INFO("info"),
        PERMISSION("permission");

        companion object {
            fun fromString(value: String): StepType {
                return entries.find { it.value == value } ?: CUSTOM
            }
        }
    }
}

/**
 * Step configuration -- varies by step type.
 *
 * SPEC-070-A J.10 — @Stable rather than @Immutable: `layout`, `field_defaults`
 * and `localizations` are JSON passthrough Map fields (per J.22 EXCLUDE rule).
 * Compose-stable iterables (options/items/fields/content_blocks/next_step_rules)
 * are migrated to ImmutableList<T> below so a re-emit with structurally-equal
 * lists doesn't trigger downstream recomposition.
 */
@Stable
data class StepConfig(
    // welcome
    val title: String? = null,
    val subtitle: String? = null,
    val image_url: String? = null,
    val cta_text: String? = null,
    val skip_enabled: Boolean? = null,

    // question
    val options: ImmutableList<QuestionOption>? = null,
    val selection_mode: SelectionMode? = null,

    // value_prop
    val items: ImmutableList<ValuePropItem>? = null,

    // custom
    // J.22 EXCLUDE: passthrough JSON map from `step.config.layout` — left raw.
    val layout: Map<String, Any>? = null,

    // form (SPEC-082)
    val fields: ImmutableList<FormField>? = null,
    val validation_mode: String? = null,  // "on_submit" or "realtime"

    // SPEC-083: Populated by applyOverrides from StepConfigOverride.fieldDefaults
    // J.22 EXCLUDE: caller-provided defaults bag — left raw.
    val field_defaults: Map<String, Any>? = null,

    // SPEC-090: Interactive chat
    val chat_config: ChatConfig? = null,

    // SPEC-084: Rendering fidelity
    val content_blocks: ImmutableList<ContentBlock>? = null,
    val layout_variant: String? = null,  // image_top, image_bottom, image_fullscreen, image_split, no_image
    val background: ai.appdna.sdk.core.BackgroundStyleConfig? = null,
    val text_style: ai.appdna.sdk.core.TextStyleConfig? = null,
    val element_style: ai.appdna.sdk.core.ElementStyleConfig? = null,
    val animation: ai.appdna.sdk.core.AnimationConfig? = null,
    // J.22 EXCLUDE: `localizations` is a nested JSON passthrough.
    val localizations: Map<String, Map<String, String>>? = null,
    val default_locale: String? = null,

    // SPEC-070-A audit Round 2-restart attempt 2 F1+F2:
    // (F1) layout-level `next_step_rules` so the editor's Logic panel can
    //      author rules under `step.config.next_step_rules` (mirroring iOS
    //      OnboardingRenderer.swift:761-766 where layout rules are preferred
    //      when richer than step-level).
    // (F2) per-step `progress_color` override consulted by OnboardingActivity
    //      progress-bar theming (mirrors iOS OnboardingRenderer.swift:174-186).
    val next_step_rules: ImmutableList<NextStepRule>? = null,
    val progress_color: String? = null,

    // SPEC-421 — permission-step contract. The console serializer writes these at the
    // step-content TOP LEVEL (siblings of `content_blocks`), NOT under the inner `layout`
    // sub-map. Previously only read from `layout[...]` → resolved null → every authored
    // permission step emitted `permission_unavailable` and advanced without prompting.
    // Decoded as first-class fields; OnboardingActivity prefers these, falling back to the
    // inner `layout` map only for safety.
    val permission_type: String? = null,
    val show_settings_fallback_on_denied: Boolean? = null,
    val settings_fallback_label: String? = null
)

@Immutable
data class QuestionOption(
    val id: String,
    val label: String,
    val icon: String? = null,
    /**
     * SPEC-070-A finalization parity audit R2 — per-option caption text
     * shown below the label. Mirrors iOS `QuestionOption.subtitle`
     * (OnboardingConfig.swift:340-345) and the QuestionStepView
     * renderer at `QuestionStepView.swift:136-142` which renders this
     * as a smaller caption when non-empty.
     */
    val subtitle: String? = null,
)

enum class SelectionMode(val value: String) {
    SINGLE("single"),
    MULTI("multi");

    companion object {
        fun fromString(value: String): SelectionMode {
            return entries.find { it.value == value } ?: SINGLE
        }
    }
}

@Immutable
data class ValuePropItem(
    val icon: String,
    val title: String,
    val subtitle: String
)

// MARK: - Form Field Types (SPEC-082)

enum class FormFieldType(val value: String) {
    TEXT("text"), TEXTAREA("textarea"), NUMBER("number"),
    EMAIL("email"), PHONE("phone"),
    // SPEC-401-A: missing field types added — schema in
    // `flow.schema.ts FORM_FIELD_TYPES` lists all 22; these were
    // previously unimplemented in the Android SDK so configs that
    // referenced them silently fell back to TEXT (silent breakage).
    PASSWORD("password"),
    URL("url"),
    DATE("date"), TIME("time"), DATETIME("datetime"),
    SELECT("select"), SLIDER("slider"), TOGGLE("toggle"),
    STEPPER("stepper"), SEGMENTED("segmented"),
    LOCATION("location"),
    RATING("rating"),
    RANGE_SLIDER("range_slider"),
    IMAGE_PICKER("image_picker"),
    COLOR("color"),
    MULTILINE_CHIPS("multiline_chips"),
    SIGNATURE("signature");

    companion object {
        fun fromString(value: String): FormFieldType {
            return entries.find { it.value == value } ?: TEXT
        }
    }
}

/** Structured location data from geocoding autocomplete (SPEC-089). */
data class LocationData(
    val formatted_address: String = "",
    val city: String = "",
    val state: String = "",
    val state_code: String = "",
    val country: String = "",
    val country_code: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val timezone: String = "UTC",
    val timezone_offset: Int = 0,
    val postal_code: String? = null,
    val raw_query: String = ""
)

data class FormFieldOption(
    val id: String,
    val label: String,
    val icon: String? = null,
    val value: Any? = null
)

data class FormFieldValidation(
    val pattern: String? = null,
    val pattern_message: String? = null
)

data class FormFieldDependency(
    val field_id: String,
    val operator: String,  // equals, not_equals, contains, not_empty, empty, gt, lt, is_set
    val value: Any? = null
)

data class FormFieldConfig(
    val max_length: Int? = null,
    val min_length: Int? = null,
    val keyboard_type: String? = null,
    val autocapitalize: String? = null,
    val autocorrect: Boolean? = null,
    val multiline_min_lines: Int? = null,
    val min_value: Double? = null,
    val max_value: Double? = null,
    val step: Double? = null,
    val unit: String? = null,
    val decimal_places: Int? = null,
    val min_date: String? = null,
    val max_date: String? = null,
    val date_format: String? = null,
    val picker_style: String? = null,
    val search_enabled: Boolean? = null,
    val multi_select: Boolean? = null,
    val max_selections: Int? = null,
    val default_value: Any? = null,
    // Location (SPEC-089)
    val location_type: String? = null,
    val location_bias_country: String? = null,
    val location_language: String? = null,
    val location_placeholder: String? = null,
    val location_min_chars: Int? = null,
    // SPEC-401-A R68 (Lens A P2) — opt-out for the prefix location pin.
    // iOS LocationFieldView reads `field_config["show_prefix_icon"]`
    // (default true). Some flows want to hide the pin in dark/minimalist
    // designs.
    val show_prefix_icon: Boolean? = null,
    // SPEC-401-A R68 (Lens A P2) — caption + icon shown below the field
    // (e.g. select_grid hint "tap multiple to choose"). iOS
    // FormInputBlockViews.swift:769-770 reads from `field_config`.
    val tooltip_text: String? = null,
    val tooltip_icon: String? = null,
    // SPEC-401-A: rating
    val max_stars: Int? = null,
    val allow_half: Boolean? = null,
    val star_size: Int? = null,
    val filled_color: String? = null,
    val empty_color: String? = null,
    // SPEC-401-A: range_slider
    val min_label: String? = null,
    val max_label: String? = null,
    // SPEC-401-A: image_picker
    val max_size_mb: Double? = null,
    val allowed_types: String? = null, // "photo" | "camera" | "both"
    val aspect_ratio: String? = null, // e.g. "1:1", "4:3", "16:9", or "free"
    val placeholder_text: String? = null,
    // SPEC-401-A: color picker
    val default_color: String? = null,
    val show_opacity: Boolean? = null,
    val preset_colors: List<String>? = null,
    // SPEC-401-A: url validation
    val validate_format: Boolean? = null,
    // SPEC-401-A: multiline_chips
    val max_chips: Int? = null,
    val suggestions: List<String>? = null,
    val allow_custom: Boolean? = null,
    // SPEC-401-A: signature
    val stroke_color: String? = null,
    val stroke_width: Double? = null,
    val clear_button_text: String? = null
)

@Immutable
/**
 * SPEC-401-A — per-field style envelope (mirrors `FormFieldStyleSchema`
 * in `flow.schema.ts:101-109`). Authors set these in the console; the
 * renderer applies them to Material3 widgets where applicable:
 *   - `corner_radius` → `OutlinedTextField` shape, button corners
 *   - `border_color` / `focus_border_color` → `OutlinedTextField.colors`
 *   - `background_color` → toggle's `checkedTrackColor`, container fills
 *   - `error_style.color` (free-form input_style/error_style) → error tint
 *
 * `label_style` / `input_style` / `error_style` are typed as free-form
 * maps because the schema treats them as opaque records — authors may
 * pass any sub-keys (e.g. `font_size`, `font_weight`, `color`).
 */
data class FormFieldStyle(
    val label_style: Map<String, Any>? = null,
    val input_style: Map<String, Any>? = null,
    val error_style: Map<String, Any>? = null,
    val corner_radius: Double? = null,
    val border_color: String? = null,
    val focus_border_color: String? = null,
    val background_color: String? = null,
)

data class FormField(
    val id: String,
    val type: FormFieldType,
    val label: String,
    val placeholder: String? = null,
    val required: Boolean = false,
    val validation: FormFieldValidation? = null,
    val options: ImmutableList<FormFieldOption>? = null,
    val config: FormFieldConfig? = null,
    val depends_on: FormFieldDependency? = null,
    val style: FormFieldStyle? = null,
)

// MARK: - Async Step Hook Types (SPEC-083)

/**
 * Result of the async step hook called before advancing.
 */
sealed class StepAdvanceResult {
    /** Continue to next step normally. */
    object Proceed : StepAdvanceResult()

    /** Continue to next step, merging additional data into responses. */
    data class ProceedWithData(val data: Map<String, Any>) : StepAdvanceResult()

    /** Block advancement. Stay on current step and display error message. */
    data class Block(val message: String) : StepAdvanceResult()

    /** Skip to a specific step by ID, optionally merging data. */
    data class SkipTo(val stepId: String, val data: Map<String, Any>? = null) : StepAdvanceResult()

    /**
     * SPEC-070-A C.8 — port of iOS v1.0.60 SPEC-083 amendment
     * `OnboardingConfig.swift:485 case stay(message: String? = nil)`.
     *
     * Stay on the current step optionally surfacing a success/info message.
     * Differs from [Block]: [Stay] is for "host handled the side effect,
     * waiting for user choice / verification email / etc." — not an error.
     * If `message` is null/empty the SDK stays silent (host handles UI).
     */
    data class Stay(val message: String? = null) : StepAdvanceResult()
}

/**
 * Optional config override for dynamic step content.
 */
data class StepConfigOverride(
    val fieldDefaults: Map<String, Any>? = null,
    val title: String? = null,
    val subtitle: String? = null,
    val ctaText: String? = null,
    val layoutOverrides: Map<String, Any>? = null
)

// MARK: - Step Hook Config (SPEC-083 P1)

/**
 * Server-side webhook configuration for a step.
 */
data class StepHookConfig(
    val enabled: Boolean,
    val webhook_url: String,
    val timeout_ms: Int = 10000,
    val loading_text: String? = null,
    val error_text: String? = null,
    val retry_count: Int = 0,
    val headers: Map<String, String>? = null
)

/**
 * Delegate for receiving onboarding flow lifecycle events.
 */
/** Result of [AppDNAOnboardingDelegate.onElementInteraction] — lets the host push state back into the live
 * step from its backend (SPEC-419 EPIC-11 — backend-interactive elements; e.g. a calendar day tap → backend
 * confirms → update the rendered element without leaving the step). */
data class ElementInteractionResult(
    /** Per-block `field_config` patches to merge + re-render. Keyed by blockId -> (key -> value). */
    val fieldConfigPatches: Map<String, Map<String, Any>>? = null,
    /** Patches to merge into the step's `inputValues` (keyed by field_id). */
    val inputValuePatches: Map<String, Any>? = null,
    /** When true, advance to the next step after handling this interaction. */
    val advance: Boolean = false,
)

/** The merged result of applying an [ElementInteractionResult] to a step's live state. */
data class AppliedInteraction(
    val inputValues: Map<String, Any>,
    val fieldConfigOverrides: Map<String, Map<String, Any>>,
    val advance: Boolean,
)

/** SPEC-419 EPIC-11 — pure application of an [ElementInteractionResult] to a step's live state. Merges
 * `inputValuePatches` over [inputValues] and exposes per-block `fieldConfigPatches` as overrides the renderer
 * layers at READ TIME (ContentBlock is immutable on iOS — both platforms use a read-time override layer for
 * parity). The caller acts on [AppliedInteraction.advance]. Pure + unit-tested. */
fun applyInteractionResult(result: ElementInteractionResult, inputValues: Map<String, Any>): AppliedInteraction {
    val iv = inputValues.toMutableMap()
    result.inputValuePatches?.let { iv.putAll(it) }
    return AppliedInteraction(
        inputValues = iv,
        fieldConfigOverrides = result.fieldConfigPatches ?: emptyMap(),
        advance = result.advance,
    )
}

/**
 * SPEC-421 — optional host override for an onboarding permission step. Returned from
 * [AppDNAOnboardingDelegate.onPermissionRequest]. [HandledByHost] means the host already resolved the
 * permission (or wants to inject a result) — the SDK writes the result + emits analytics uniformly and
 * does NOT show the OS prompt. [Proceed] (or `null`) lets the SDK run the native OS flow.
 */
sealed class PermissionHandling {
    object Proceed : PermissionHandling()
    data class HandledByHost(val granted: Boolean) : PermissionHandling()
}

interface AppDNAOnboardingDelegate {
    // Observe-only callbacks (unchanged)
    fun onOnboardingStarted(flowId: String) {}
    fun onOnboardingStepChanged(flowId: String, stepId: String, stepIndex: Int, totalSteps: Int) {}
    fun onOnboardingCompleted(flowId: String, responses: Map<String, Any>) {}
    fun onOnboardingDismissed(flowId: String, atStep: Int) {}

    // SPEC-083: Async hook called BEFORE advancing from a step.
    suspend fun onBeforeStepAdvance(
        flowId: String,
        fromStepId: String,
        stepIndex: Int,
        stepType: String,
        responses: Map<String, Any>,
        stepData: Map<String, Any>?
    ): StepAdvanceResult = StepAdvanceResult.Proceed

    // SPEC-083: Optional hook to modify step config before rendering.
    suspend fun onBeforeStepRender(
        flowId: String,
        stepId: String,
        stepIndex: Int,
        stepType: String,
        responses: Map<String, Any>
    ): StepConfigOverride? = null

    // SPEC-419 EPIC-11: Async hook fired DURING render when an interactive element acts (calendar day tap,
    // otp digit, memory flip, dark-mode toggle, health connect…). Return a result to push backend state into
    // the live step. blockId/action/value identify the interaction; inputValues is the live snapshot.
    suspend fun onElementInteraction(
        flowId: String,
        stepId: String,
        blockId: String,
        action: String,
        value: String?,
        inputValues: Map<String, Any>,
    ): ElementInteractionResult? = null

    // SPEC-421: Runtime permission hooks for onboarding permission steps.
    // Optional pre-hook — return PermissionHandling.HandledByHost(granted) to short-circuit the OS
    // prompt, or PermissionHandling.Proceed/null to let the SDK run the native flow.
    suspend fun onPermissionRequest(permissionType: String): PermissionHandling? = null
    // Observe-only result callback after the permission is resolved (host, status, or prompt).
    fun onPermissionResult(flowId: String, stepId: String, permissionType: String, granted: Boolean) {}
}

// MARK: - Parsing helpers

internal object OnboardingConfigParser {

    @Suppress("UNCHECKED_CAST")
    fun parseOnboardingRoot(data: Map<String, Any>): Pair<String?, Map<String, OnboardingFlowConfig>> {
        val activeFlowId = data["active_flow_id"] as? String

        val flowsMap = data["flows"] as? Map<String, Any> ?: emptyMap()
        val parsed = mutableMapOf<String, OnboardingFlowConfig>()
        for ((key, value) in flowsMap) {
            if (value is Map<*, *>) {
                try {
                    val map = value as Map<String, Any>
                    parsed[key] = parseFlowConfig(key, map)
                } catch (_: Exception) {}
            }
        }
        return activeFlowId to parsed
    }

    /** Parse a single flow from a per-item Firestore document. */
    @Suppress("UNCHECKED_CAST")
    fun parseSingleFlow(id: String, data: Map<String, Any>): OnboardingFlowConfig? {
        return try {
            parseFlowConfig(id, data)
        } catch (_: Exception) {
            null
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseFlowConfig(id: String, map: Map<String, Any>): OnboardingFlowConfig {
        val stepsList = map["steps"] as? List<Map<String, Any>> ?: emptyList()
        // SPEC-070-A J.22 — wrap as ImmutableList so the @Stable
        // OnboardingFlowConfig contract holds (Compose stability inference).
        val steps = stepsList.map { parseStep(it) }.toImmutableList()

        val settingsMap = map["settings"] as? Map<String, Any> ?: emptyMap()
        // SPEC-085: Parse haptic config
        val hapticMap = settingsMap["haptic"] as? Map<String, Any>
        val haptic = hapticMap?.let { h ->
            val triggersMap = h["triggers"] as? Map<String, Any>
            HapticConfig(
                enabled = h["enabled"] as? Boolean ?: false,
                triggers = triggersMap?.let { t ->
                    HapticTriggers(
                        on_step_advance = t["on_step_advance"] as? String,
                        on_button_tap = t["on_button_tap"] as? String,
                        on_option_select = t["on_option_select"] as? String,
                        on_toggle = t["on_toggle"] as? String,
                        on_form_submit = t["on_form_submit"] as? String,
                        on_error = t["on_error"] as? String,
                        on_success = t["on_success"] as? String,
                    )
                } ?: HapticTriggers(),
            )
        }
        // SPEC-085: Parse particle effect config
        val particleMap = settingsMap["particle_effect"] as? Map<String, Any>
        val particleEffect = particleMap?.let { p ->
            ParticleEffect(
                type = p["type"] as? String ?: "confetti",
                trigger = p["trigger"] as? String ?: "on_appear",
                duration_ms = (p["duration_ms"] as? Number)?.toInt() ?: 2500,
                intensity = p["intensity"] as? String ?: "medium",
                colors = (p["colors"] as? List<*>)?.filterIsInstance<String>(),
            )
        }
        // SPEC-070-A F.1: parse back_button_style + content_padding + block_spacing
        val backBtnMap = settingsMap["back_button_style"] as? Map<String, Any>
        val backButtonStyle = backBtnMap?.let { b ->
            BackButtonStyle(
                icon_size = (b["icon_size"] as? Number)?.toDouble(),
                icon_color = b["icon_color"] as? String,
                position = b["position"] as? String,
                icon = b["icon"] as? String,
                close_on_first = b["close_on_first"] as? Boolean,
            )
        }

        val settings = OnboardingSettings(
            show_progress = settingsMap["show_progress"] as? Boolean ?: true,
            allow_back = settingsMap["allow_back"] as? Boolean ?: true,
            skip_to_step = settingsMap["skip_to_step"] as? String,
            haptic = haptic,
            particle_effect = particleEffect,
            progress_color = settingsMap["progress_color"] as? String,
            progress_track_color = settingsMap["progress_track_color"] as? String,
            progress_height = (settingsMap["progress_height"] as? Number)?.toDouble(),
            progress_gradient_colors = (settingsMap["progress_gradient_colors"] as? List<*>)?.mapNotNull { it as? String },
            progress_skip_label = settingsMap["progress_skip_label"] as? String,
            progress_flash_color = settingsMap["progress_flash_color"] as? String,
            progress_style = settingsMap["progress_style"] as? String,
            back_button_style = backButtonStyle,
            dismiss_allowed = settingsMap["dismiss_allowed"] as? Boolean,
            content_padding = (settingsMap["content_padding"] as? Number)?.toDouble(),
            block_spacing = (settingsMap["block_spacing"] as? Number)?.toDouble(),
        )

        // SPEC-070-A F.1: top-level flow fields (status / graph_layout / graph_nodes / audience_rules)
        @Suppress("UNCHECKED_CAST")
        val graphLayout = map["graph_layout"] as? Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val graphNodes = map["graph_nodes"] as? Map<String, Any?>

        return OnboardingFlowConfig(
            id = map["id"] as? String ?: id,
            name = map["name"] as? String ?: "",
            version = (map["version"] as? Number)?.toInt() ?: 1,
            steps = steps,
            settings = settings,
            status = map["status"] as? String,
            graph_layout = graphLayout,
            graph_nodes = graphNodes,
            audience_rules = map["audience_rules"],
        )
    }

    /** Exposed as internal for unit testing. */
    @Suppress("UNCHECKED_CAST")
    internal fun parseStepForTest(map: Map<String, Any>): OnboardingStep? = try { parseStep(map) } catch (_: Exception) { null }

    @Suppress("UNCHECKED_CAST")
    private fun parseStep(map: Map<String, Any>): OnboardingStep {
        val typeStr = map["type"] as? String ?: "custom"
        val configMap = (map["config"] as? Map<String, Any>)
            ?: (map["layout"] as? Map<String, Any>)
            ?: emptyMap()

        // SPEC-070-A J.22 — ImmutableList wraps for Compose-stable iteration.
        val options = (configMap["options"] as? List<*>)?.mapNotNull { opt ->
            if (opt is Map<*, *>) {
                val optMap = opt as Map<String, Any>
                QuestionOption(
                    id = optMap["id"] as? String ?: "",
                    label = optMap["label"] as? String ?: "",
                    icon = optMap["icon"] as? String,
                    // SPEC-070-A finalization parity audit R2 — per-option subtitle.
                    subtitle = optMap["subtitle"] as? String,
                )
            } else null
        }?.toImmutableList()

        val items = (configMap["items"] as? List<*>)?.mapNotNull { item ->
            if (item is Map<*, *>) {
                val itemMap = item as Map<String, Any>
                ValuePropItem(
                    icon = itemMap["icon"] as? String ?: "",
                    title = itemMap["title"] as? String ?: "",
                    subtitle = itemMap["subtitle"] as? String ?: ""
                )
            } else null
        }?.toImmutableList()

        val selectionModeStr = configMap["selection_mode"] as? String
        val selectionMode = selectionModeStr?.let { SelectionMode.fromString(it) }

        @Suppress("UNCHECKED_CAST")
        val fields = (configMap["fields"] as? List<*>)?.mapNotNull { f ->
            if (f is Map<*, *>) {
                val fm = f as Map<String, Any>
                // SPEC-070-A J.22 — wrap form-field options as ImmutableList.
                val fieldOptions = (fm["options"] as? List<*>)?.mapNotNull { o ->
                    if (o is Map<*, *>) {
                        val om = o as Map<String, Any>
                        FormFieldOption(
                            id = om["id"] as? String ?: "",
                            label = om["label"] as? String ?: "",
                            icon = om["icon"] as? String,
                            value = om["value"]
                        )
                    } else null
                }?.toImmutableList()
                val fieldConfigMap = fm["config"] as? Map<String, Any>
                val fieldConfig = fieldConfigMap?.let { fc ->
                    // SPEC-401-A R41 — same shape of parser drift R36/R37
                    // closed for ContentBlock + R40 closed for nested
                    // children, but for the FormField envelope's `config`
                    // sub-map. Was reading 21 of 47 declared fields → every
                    // form_input_rating / range_slider / image_picker /
                    // color_picker / multiline_chips / signature / multiline
                    // text fell through to hardcoded renderer defaults
                    // regardless of console authoring. Renderer reads
                    // (FormFieldRendererExtras.kt + FormStepComposable.kt)
                    // the missing keys directly via `config?.X`.
                    FormFieldConfig(
                        max_length = (fc["max_length"] as? Number)?.toInt(),
                        min_length = (fc["min_length"] as? Number)?.toInt(),
                        keyboard_type = fc["keyboard_type"] as? String,
                        autocapitalize = fc["autocapitalize"] as? String,
                        autocorrect = fc["autocorrect"] as? Boolean,
                        multiline_min_lines = (fc["multiline_min_lines"] as? Number)?.toInt(),
                        min_value = (fc["min_value"] as? Number)?.toDouble(),
                        max_value = (fc["max_value"] as? Number)?.toDouble(),
                        step = (fc["step"] as? Number)?.toDouble(),
                        unit = fc["unit"] as? String,
                        decimal_places = (fc["decimal_places"] as? Number)?.toInt(),
                        min_date = fc["min_date"] as? String,
                        max_date = fc["max_date"] as? String,
                        date_format = fc["date_format"] as? String,
                        picker_style = fc["picker_style"] as? String,
                        search_enabled = fc["search_enabled"] as? Boolean,
                        multi_select = fc["multi_select"] as? Boolean,
                        max_selections = (fc["max_selections"] as? Number)?.toInt(),
                        default_value = fc["default_value"],
                        // Location (SPEC-089)
                        location_type = fc["location_type"] as? String,
                        location_bias_country = fc["location_bias_country"] as? String,
                        location_language = fc["location_language"] as? String,
                        location_placeholder = fc["location_placeholder"] as? String,
                        location_min_chars = (fc["location_min_chars"] as? Number)?.toInt(),
                        // SPEC-401-A R68 (Lens A P2) — show_prefix_icon +
                        // tooltip_text/tooltip_icon parsed from field_config.
                        show_prefix_icon = fc["show_prefix_icon"] as? Boolean,
                        tooltip_text = fc["tooltip_text"] as? String,
                        tooltip_icon = fc["tooltip_icon"] as? String,
                        // SPEC-401-A R41 — rating
                        max_stars = (fc["max_stars"] as? Number)?.toInt(),
                        allow_half = fc["allow_half"] as? Boolean,
                        star_size = (fc["star_size"] as? Number)?.toInt(),
                        filled_color = fc["filled_color"] as? String,
                        empty_color = fc["empty_color"] as? String,
                        // range_slider
                        min_label = fc["min_label"] as? String,
                        max_label = fc["max_label"] as? String,
                        // image_picker
                        max_size_mb = (fc["max_size_mb"] as? Number)?.toDouble(),
                        allowed_types = fc["allowed_types"] as? String,
                        aspect_ratio = fc["aspect_ratio"] as? String,
                        placeholder_text = fc["placeholder_text"] as? String,
                        // color picker
                        default_color = fc["default_color"] as? String,
                        show_opacity = fc["show_opacity"] as? Boolean,
                        preset_colors = (fc["preset_colors"] as? List<*>)?.filterIsInstance<String>(),
                        // url validation
                        validate_format = fc["validate_format"] as? Boolean,
                        // multiline_chips
                        max_chips = (fc["max_chips"] as? Number)?.toInt(),
                        suggestions = (fc["suggestions"] as? List<*>)?.filterIsInstance<String>(),
                        allow_custom = fc["allow_custom"] as? Boolean,
                        // signature
                        stroke_color = fc["stroke_color"] as? String,
                        stroke_width = (fc["stroke_width"] as? Number)?.toDouble(),
                        clear_button_text = fc["clear_button_text"] as? String,
                    )
                }
                val validationMap = fm["validation"] as? Map<String, Any>
                val fieldValidation = validationMap?.let { v ->
                    FormFieldValidation(
                        pattern = v["pattern"] as? String,
                        pattern_message = v["pattern_message"] as? String
                    )
                }
                val depMap = fm["depends_on"] as? Map<String, Any>
                val dependency = depMap?.let { d ->
                    FormFieldDependency(
                        field_id = d["field_id"] as? String ?: "",
                        operator = d["operator"] as? String ?: "not_empty",
                        value = d["value"]
                    )
                }
                // SPEC-401-A — parse the per-field `style` envelope from the
                // schema's `FormFieldStyleSchema`. Renderer reads it for
                // toggle track tint, OutlinedTextField border colors, and
                // corner radius.
                @Suppress("UNCHECKED_CAST")
                val styleMap = fm["style"] as? Map<String, Any>
                val fieldStyle = styleMap?.let { sm ->
                    FormFieldStyle(
                        label_style = sm["label_style"] as? Map<String, Any>,
                        input_style = sm["input_style"] as? Map<String, Any>,
                        error_style = sm["error_style"] as? Map<String, Any>,
                        corner_radius = (sm["corner_radius"] as? Number)?.toDouble(),
                        border_color = sm["border_color"] as? String,
                        focus_border_color = sm["focus_border_color"] as? String,
                        background_color = sm["background_color"] as? String,
                    )
                }
                FormField(
                    // SPEC-401-A R43 — match iOS OnboardingConfig.swift:508
                    // `self.id = decodeIfPresent(.id) ?? UUID().uuidString`.
                    // Was empty-string fallback → id-less fields shared
                    // `inputValues[fieldId]` collisions in responses payload.
                    id = fm["id"] as? String ?: java.util.UUID.randomUUID().toString(),
                    type = FormFieldType.fromString(fm["type"] as? String ?: "text"),
                    label = fm["label"] as? String ?: "",
                    placeholder = fm["placeholder"] as? String,
                    required = fm["required"] as? Boolean ?: false,
                    validation = fieldValidation,
                    options = fieldOptions,
                    config = fieldConfig,
                    depends_on = dependency,
                    style = fieldStyle,
                )
            } else null
        }?.toImmutableList()

        // SPEC-084: Parse content blocks (fallback to step-level content_blocks if not in configMap)
        @Suppress("UNCHECKED_CAST")
        val rawContentBlocks = (configMap["content_blocks"] as? List<*>)
            ?: (map["content_blocks"] as? List<*>)
        val contentBlocks = rawContentBlocks?.mapNotNull { b ->
            if (b is Map<*, *>) {
                val bm = b as Map<String, Any>
                // SPEC-401-A R40 P0 — body extracted into `decodeContentBlock`
                // (defined alongside `parseChildBlocks` further below) so
                // nested children inside row/stack containers (parsed by
                // `parseChildBlocks`) take the SAME ~150-field decode path.
                // Until R40, parseChildBlocks only populated ~25 fields,
                // silently shadowing every R3-R39 renderer fix for content
                // nested inside a container. iOS gets full parity
                // automatically because ContentBlock is Codable; Android
                // emulates that via this shared helper.
                decodeContentBlock(bm)
            } else null
        }?.toImmutableList()

        // SPEC-084: Parse background config
        @Suppress("UNCHECKED_CAST")
        val bgMap = configMap["background"] as? Map<String, Any>
        val background = bgMap?.let { bg ->
            val gradMap = bg["gradient"] as? Map<String, Any>
            val gradient = gradMap?.let { g ->
                val stopsList = (g["stops"] as? List<*>)?.mapNotNull { s ->
                    if (s is Map<*, *>) {
                        ai.appdna.sdk.core.GradientStopConfig(
                            color = (s as Map<String, Any>)["color"] as? String ?: "#000000",
                            position = ((s as Map<String, Any>)["position"] as? Number)?.toDouble() ?: 0.0,
                        )
                    } else null
                }
                ai.appdna.sdk.core.GradientConfig(type = g["type"] as? String, angle = (g["angle"] as? Number)?.toDouble(), stops = stopsList)
            }
            ai.appdna.sdk.core.BackgroundStyleConfig(
                type = bg["type"] as? String, color = bg["color"] as? String,
                gradient = gradient, image_url = bg["image_url"] as? String,
                image_fit = bg["image_fit"] as? String, overlay = bg["overlay"] as? String,
                // SPEC-401-A R36 — R15 + R35 fixes shadowed because parser
                // never read these. overlay_opacity drives the dim layer over
                // image/lottie/rive backgrounds; lottie_url + animation_loop
                // wire R15's full-screen Lottie branch; rive_url wires R35's
                // full-screen Rive branch. Without these parsed, the
                // canonical iOS-style background payload silently rendered as
                // theme default.
                overlay_opacity = (bg["overlay_opacity"] as? Number)?.toDouble(),
                lottie_url = bg["lottie_url"] as? String,
                animation_loop = bg["animation_loop"] as? Boolean,
                rive_url = bg["rive_url"] as? String,
            )
        }

        // SPEC-084: Parse animation config
        @Suppress("UNCHECKED_CAST")
        val animMap = configMap["animation"] as? Map<String, Any>
        val animConfig = animMap?.let { a ->
            ai.appdna.sdk.core.AnimationConfig(
                entry_animation = a["entry_animation"] as? String,
                entry_duration_ms = (a["entry_duration_ms"] as? Number)?.toInt(),
                section_stagger = a["section_stagger"] as? String,
                section_stagger_delay_ms = (a["section_stagger_delay_ms"] as? Number)?.toInt(),
                cta_animation = a["cta_animation"] as? String,
                plan_selection_animation = a["plan_selection_animation"] as? String,
                dismiss_animation = a["dismiss_animation"] as? String,
            )
        }

        // SPEC-084: Parse localizations
        @Suppress("UNCHECKED_CAST")
        val localizations = configMap["localizations"] as? Map<String, Map<String, String>>

        // SPEC-084: Parse step-level text_style and element_style
        @Suppress("UNCHECKED_CAST")
        val textStyle = (configMap["text_style"] as? Map<String, Any>)?.let { parseTextStyleConfig(it) }
        @Suppress("UNCHECKED_CAST")
        val elementStyle = (configMap["element_style"] as? Map<String, Any>)?.let { parseElementStyleConfig(it) }

        val config = StepConfig(
            title = configMap["title"] as? String,
            subtitle = configMap["subtitle"] as? String,
            image_url = configMap["image_url"] as? String,
            cta_text = configMap["cta_text"] as? String,
            skip_enabled = configMap["skip_enabled"] as? Boolean,
            options = options,
            selection_mode = selectionMode,
            items = items,
            layout = configMap["layout"] as? Map<String, Any>,
            fields = fields,
            validation_mode = configMap["validation_mode"] as? String,
            // SPEC-401-A R41 — `field_defaults` parser drop. Data class
            // declares it (line 193); FormStepComposable.kt reads it via
            // `config.field_defaults?.forEach`; OnboardingActivity merges
            // it with override at line 954. But the JSON parser never
            // assigned it to the StepConfig constructor — only the
            // SPEC-083 override path populated it. Console-authored
            // `field_defaults` (vs. hook-supplied) was dropped on parse,
            // so editor pre-fills never reached the form composable.
            field_defaults = configMap["field_defaults"] as? Map<String, Any>,
            content_blocks = contentBlocks,
            layout_variant = configMap["layout_variant"] as? String,
            background = background,
            text_style = textStyle,
            element_style = elementStyle,
            animation = animConfig,
            localizations = localizations,
            default_locale = configMap["default_locale"] as? String,
            chat_config = parseChatConfig(configMap["chat_config"]),
            // SPEC-421 — decoded from the SAME top level as content_blocks (console siblings),
            // NOT from the inner `layout` sub-map.
            permission_type = configMap["permission_type"] as? String,
            show_settings_fallback_on_denied = configMap["show_settings_fallback_on_denied"] as? Boolean,
            settings_fallback_label = configMap["settings_fallback_label"] as? String,
        )

        // SPEC-083 P1: Parse hook config
        @Suppress("UNCHECKED_CAST")
        val hookMap = map["hook"] as? Map<String, Any>
        val hook = hookMap?.let { h ->
            val enabled = h["enabled"] as? Boolean ?: false
            val webhookUrl = h["webhook_url"] as? String ?: ""
            if (enabled && webhookUrl.isNotEmpty()) {
                StepHookConfig(
                    enabled = true,
                    webhook_url = webhookUrl,
                    timeout_ms = (h["timeout_ms"] as? Number)?.toInt() ?: 10000,
                    loading_text = h["loading_text"] as? String,
                    error_text = h["error_text"] as? String,
                    retry_count = (h["retry_count"] as? Number)?.toInt() ?: 0,
                    headers = h["headers"] as? Map<String, String>
                )
            } else null
        }

        // Parse next_step_rules — SPEC-070-A A.21: also pull `conditions` array + `logic`
        // so the iOS multi-condition shape (`OnboardingConfig.swift:122-127`) round-trips.
        val nextStepRules = parseNextStepRulesList(map["next_step_rules"])
        // SPEC-070-A audit Round 2-restart attempt 2 F1: also parse layout
        // rules under `step.config.next_step_rules` (the editor's Logic-panel
        // path). These get folded into the StepConfig so iOS-style preference
        // logic (layout rules > step rules when richer) can run client-side.
        val configLayoutRules = parseNextStepRulesList(configMap["next_step_rules"])
        // SPEC-070-A audit Round 2-restart attempt 2 F2: per-step
        // progress-color override read from configMap.
        val progressColor = configMap["progress_color"] as? String

        val configWithLayoutRules = if (configLayoutRules != null || progressColor != null) {
            config.copy(next_step_rules = configLayoutRules, progress_color = progressColor)
        } else config

        return OnboardingStep(
            id = map["id"] as? String ?: "",
            type = OnboardingStep.StepType.fromString(typeStr),
            config = configWithLayoutRules,
            hook = hook,
            // The console writes hide_progress/hide_back INSIDE the step's layout object
            // (`configMap`), not at the step root — read layout first with a root fallback
            // (mirrors iOS OnboardingConfig.swift LayoutFlagKeys decode).
            hide_progress = map["hide_progress"] as? Boolean ?: configMap["hide_progress"] as? Boolean,
            hide_back = map["hide_back"] as? Boolean ?: configMap["hide_back"] as? Boolean,
            next_step_rules = nextStepRules
        )
    }

    /**
     * Shared parser for `next_step_rules` arrays — used at both the step level
     * and the step.config (layout) level so the editor's Logic panel can write
     * to either path. Mirrors iOS `OnboardingRenderer.swift:761-766`.
     *
     * SPEC-070-A J.22 — returns ImmutableList so step / step.config
     * `next_step_rules` fields satisfy Compose's stability inference.
     */
    @Suppress("UNCHECKED_CAST")
    private fun parseNextStepRulesList(raw: Any?): ImmutableList<NextStepRule>? {
        return (raw as? List<*>)?.mapNotNull { r ->
            if (r is Map<*, *>) {
                val rm = r as Map<String, Any>
                val conditionsRaw = rm["conditions"] as? List<*>
                val conditionsList = conditionsRaw?.mapNotNull { entry ->
                    when (entry) {
                        is Map<*, *> -> (entry as Map<String, Any?>)
                        is String -> mapOf<String, Any?>("type" to entry)
                        else -> null
                    }
                }?.toImmutableList()
                NextStepRule(
                    condition = rm["condition"],
                    conditions = conditionsList,
                    logic = rm["logic"] as? String,
                    target_step_id = rm["target_step_id"] as? String ?: ""
                )
            } else null
        }?.toImmutableList()
    }

    /**
     * Parse a raw map from Firestore into a TextStyleConfig.
     * Used for per-block style overrides on ContentBlock.style (SPEC-084).
     */
    @Suppress("UNCHECKED_CAST")
    internal fun parseTextStyleConfig(map: Map<String, Any>): TextStyleConfig {
        return TextStyleConfig(
            font_family = map["font_family"] as? String,
            font_size = (map["font_size"] as? Number)?.toDouble(),
            font_weight = (map["font_weight"] as? Number)?.toInt(),
            color = map["color"] as? String,
            alignment = map["alignment"] as? String,
            line_height = (map["line_height"] as? Number)?.toDouble(),
            letter_spacing = (map["letter_spacing"] as? Number)?.toDouble(),
            opacity = (map["opacity"] as? Number)?.toDouble(),
        )
    }

    /**
     * Parse a raw map into an ElementStyleConfig.
     * Used for step-level element_style (SPEC-084).
     */
    @Suppress("UNCHECKED_CAST")
    internal fun parseElementStyleConfig(map: Map<String, Any>): ai.appdna.sdk.core.ElementStyleConfig {
        val bgMap = map["background"] as? Map<String, Any>
        val bg = bgMap?.let {
            val gradMap = it["gradient"] as? Map<String, Any>
            val grad = gradMap?.let { g ->
                val stops = (g["stops"] as? List<Map<String, Any>>)?.map { s ->
                    ai.appdna.sdk.core.GradientStopConfig(
                        color = s["color"] as? String ?: "#000000",
                        position = (s["position"] as? Number)?.toDouble() ?: 0.0,
                    )
                }
                ai.appdna.sdk.core.GradientConfig(type = g["type"] as? String, angle = (g["angle"] as? Number)?.toDouble(), stops = stops)
            }
            ai.appdna.sdk.core.BackgroundStyleConfig(
                type = it["type"] as? String, color = it["color"] as? String,
                gradient = grad, image_url = it["image_url"] as? String,
                image_fit = it["image_fit"] as? String, overlay = it["overlay"] as? String,
            )
        }
        val borderMap = map["border"] as? Map<String, Any>
        val border = borderMap?.let {
            ai.appdna.sdk.core.BorderStyleConfig(
                width = (it["width"] as? Number)?.toDouble(), color = it["color"] as? String,
                style = it["style"] as? String, radius = (it["radius"] as? Number)?.toDouble(),
                radius_top_left = (it["radius_top_left"] as? Number)?.toDouble(),
                radius_top_right = (it["radius_top_right"] as? Number)?.toDouble(),
                radius_bottom_left = (it["radius_bottom_left"] as? Number)?.toDouble(),
                radius_bottom_right = (it["radius_bottom_right"] as? Number)?.toDouble(),
            )
        }
        val shadowMap = map["shadow"] as? Map<String, Any>
        val shadow = shadowMap?.let {
            ai.appdna.sdk.core.ShadowStyleConfig(
                x = (it["x"] as? Number)?.toDouble(), y = (it["y"] as? Number)?.toDouble(),
                blur = (it["blur"] as? Number)?.toDouble(), spread = (it["spread"] as? Number)?.toDouble(),
                color = it["color"] as? String,
            )
        }
        val paddingMap = map["padding"] as? Map<String, Any>
        val padding = paddingMap?.let {
            ai.appdna.sdk.core.SpacingConfig(
                top = (it["top"] as? Number)?.toDouble(), right = (it["right"] as? Number)?.toDouble(),
                bottom = (it["bottom"] as? Number)?.toDouble(), left = (it["left"] as? Number)?.toDouble(),
            )
        }
        val tsMap = map["text_style"] as? Map<String, Any>
        val textStyle = tsMap?.let { parseTextStyleConfig(it) }
        return ai.appdna.sdk.core.ElementStyleConfig(
            background = bg, border = border, shadow = shadow, padding = padding,
            corner_radius = (map["corner_radius"] as? Number)?.toDouble(),
            opacity = (map["opacity"] as? Number)?.toDouble(),
            text_style = textStyle,
        )
    }

    /**
     * Parse a raw Firestore map into a BlockStyle (SPEC-089d §6.1).
     * Used for per-block design tokens: background, border, shadow, padding, margin, opacity.
     */
    @Suppress("UNCHECKED_CAST")
    internal fun parseBlockStyle(map: Map<String, Any>): BlockStyle {
        val shadowMap = map["shadow"] as? Map<String, Any>
        // SPEC-401-A R38 — drop legacy parser defaults that walked back R26's
        // iOS-parity correction. R26 set data-class defaults to color =
        // "transparent" + y/blur/spread = 0 to match iOS's `.shadow(color:
        // ?? "transparent", radius: blur ?? 0)/2` (ContentBlockTypes.swift:99-104).
        // The parser still hardcoded `?: 2.0`, `?: 8.0`, `?: "#1A000000"`,
        // so any authored partial-shadow JSON rendered as a 10%-black 8px
        // y=2 shadow on Android while iOS rendered it transparent — same
        // payload, two different shadows.
        val shadow = shadowMap?.let { s ->
            BlockShadowStyle(
                x = (s["x"] as? Number)?.toDouble() ?: 0.0,
                y = (s["y"] as? Number)?.toDouble() ?: 0.0,
                blur = (s["blur"] as? Number)?.toDouble() ?: 0.0,
                spread = (s["spread"] as? Number)?.toDouble() ?: 0.0,
                color = s["color"] as? String ?: "transparent",
            )
        }
        val gradientMap = map["background_gradient"] as? Map<String, Any>
        // SPEC-401-A R38 — same R26 walk-back fix for BlockGradientStyle.
        // R26 set defaults to angle=0/start=#000000/end=#FFFFFF to match iOS
        // (ContentBlockTypes.swift:67-71,121). Parser was overriding with
        // legacy AppDNA brand indigo→purple at 135° → identical JSON
        // rendered black-to-white horizontal on iOS but indigo→purple
        // diagonal on Android.
        val gradient = gradientMap?.let { g ->
            BlockGradientStyle(
                angle = (g["angle"] as? Number)?.toDouble() ?: 0.0,
                start = g["start"] as? String ?: "#000000",
                end = g["end"] as? String ?: "#FFFFFF",
            )
        }
        return BlockStyle(
            background_color = map["background_color"] as? String,
            background_gradient = gradient,
            border_color = map["border_color"] as? String,
            border_width = (map["border_width"] as? Number)?.toDouble(),
            border_style = map["border_style"] as? String,
            border_radius = (map["border_radius"] as? Number)?.toDouble(),
            shadow = shadow,
            padding_top = (map["padding_top"] as? Number)?.toDouble(),
            padding_right = (map["padding_right"] as? Number)?.toDouble(),
            padding_bottom = (map["padding_bottom"] as? Number)?.toDouble(),
            padding_left = (map["padding_left"] as? Number)?.toDouble(),
            margin_top = (map["margin_top"] as? Number)?.toDouble(),
            margin_bottom = (map["margin_bottom"] as? Number)?.toDouble(),
            margin_left = (map["margin_left"] as? Number)?.toDouble(),
            margin_right = (map["margin_right"] as? Number)?.toDouble(),
            opacity = (map["opacity"] as? Number)?.toDouble(),
        )
    }

    /**
     * Decode a single Firestore content-block Map into a [ContentBlock].
     *
     * SPEC-401-A R40 P0 — extracted from the inline `ContentBlock(...)`
     * constructor invocation that used to live mid-`parseStep`. Both
     * top-level `content_blocks` (in `parseStep`) AND nested children
     * inside row/stack containers (in `parseChildBlocks`) call into this
     * shared helper so every R3-R39 renderer fix takes effect at every
     * tree level. Until R40 the inline body decoded ~150 fields while
     * `parseChildBlocks` only populated ~25, silently shadowing all
     * nested-content fixes (lottie autoplay, gauge metrics, pricing
     * cards, etc.). iOS achieves the same outcome automatically because
     * `ContentBlock` is `Codable`; Android emulates that via this helper.
     */
    @Suppress("UNCHECKED_CAST")
    private fun decodeContentBlock(bm: Map<String, Any>): ContentBlock {
        return ContentBlock(
            // SPEC-401-A R43 — match iOS ContentBlockTypes.swift:1208
            // `self.id = decodeIfPresent(.id) ?? UUID().uuidString`. Was
            // empty-string fallback so two id-less toggle blocks shared the
            // same `toggleValues[block.id]` map key (last write wins) and
            // `loc?.invoke("block.${block.id}.text", …)` resolved identical
            // localization keys across every id-less block. Affects nested
            // row/stack children too via R40 shared decodeContentBlock path.
            id = bm["id"] as? String ?: java.util.UUID.randomUUID().toString(),
            type = bm["type"] as? String ?: "text",
            text = bm["text"] as? String,
            level = (bm["level"] as? Number)?.toInt(),
            image_url = bm["image_url"] as? String,
            alt = bm["alt"] as? String,
            corner_radius = (bm["corner_radius"] as? Number)?.toDouble(),
            height = (bm["height"] as? Number)?.toDouble(),
            variant = bm["variant"] as? String,
            action = bm["action"] as? String,
            action_value = bm["action_value"] as? String,
            bg_color = bm["bg_color"] as? String,
            text_color = bm["text_color"] as? String,
            button_corner_radius = (bm["button_corner_radius"] as? Number)?.toDouble(),
            // Mrozu (Duolingo s20/s22) — sound_button remote audio clip.
            spacer_height = (bm["spacer_height"] as? Number)?.toDouble(),
            items = (bm["items"] as? List<*>)?.filterIsInstance<String>()?.toImmutableList(),
            list_style = bm["list_style"] as? String,
            divider_color = bm["divider_color"] as? String,
            divider_thickness = (bm["divider_thickness"] as? Number)?.toDouble(),
            divider_margin_y = (bm["divider_margin_y"] as? Number)?.toDouble(),
            badge_text = bm["badge_text"] as? String,
            badge_bg_color = bm["badge_bg_color"] as? String,
            badge_text_color = bm["badge_text_color"] as? String,
            badge_corner_radius = (bm["badge_corner_radius"] as? Number)?.toDouble(),
            icon_emoji = bm["icon_emoji"] as? String,
            icon_size = (bm["icon_size"] as? Number)?.toDouble(),
            icon_alignment = bm["icon_alignment"] as? String,
            toggle_label = bm["toggle_label"] as? String,
            toggle_description = bm["toggle_description"] as? String,
            toggle_default = bm["toggle_default"] as? Boolean,
            video_thumbnail_url = bm["video_thumbnail_url"] as? String,
            // SPEC-084: per-block text style + video source fields
            style = (bm["style"] as? Map<String, Any>)?.let { parseTextStyleConfig(it) },
            video_url = bm["video_url"] as? String,
            video_height = (bm["video_height"] as? Number)?.toDouble(),
            video_corner_radius = (bm["video_corner_radius"] as? Number)?.toDouble(),
            // SPEC-085: Rich media fields
            lottie_url = bm["lottie_url"] as? String,
            lottie_json = bm["lottie_json"] as? Map<String, Any>,
            lottie_autoplay = bm["lottie_autoplay"] as? Boolean,
            lottie_loop = bm["lottie_loop"] as? Boolean,
            lottie_speed = (bm["lottie_speed"] as? Number)?.toFloat(),
            rive_url = bm["rive_url"] as? String,
            rive_artboard = bm["rive_artboard"] as? String,
            rive_state_machine = bm["rive_state_machine"] as? String,
            icon_ref = bm["icon_ref"] ?: bm["icon"],
            video_autoplay = bm["video_autoplay"] as? Boolean,
            video_loop = bm["video_loop"] as? Boolean,
            video_muted = bm["video_muted"] as? Boolean,
            // SPEC-089d §6.1: Per-block style design tokens
            block_style = (bm["block_style"] as? Map<String, Any>)?.let { parseBlockStyle(it) },
            // SPEC-089d §6.2: 2D positioning
            vertical_align = bm["vertical_align"] as? String,
            horizontal_align = bm["horizontal_align"] as? String,
            vertical_offset = (bm["vertical_offset"] as? Number)?.toDouble(),
            horizontal_offset = (bm["horizontal_offset"] as? Number)?.toDouble(),
            // SPEC-070-A I.17 — three-zone layout opt-in.
            zone = bm["zone"] as? String,
            // SPEC-089d: page_indicator fields
            dot_count = (bm["dot_count"] as? Number)?.toInt(),
            active_index = (bm["active_index"] as? Number)?.toInt(),
            active_color = bm["active_color"] as? String,
            inactive_color = bm["inactive_color"] as? String,
            dot_size = (bm["dot_size"] as? Number)?.toDouble(),
            dot_spacing = (bm["dot_spacing"] as? Number)?.toDouble(),
            active_dot_width = (bm["active_dot_width"] as? Number)?.toDouble(),
            // SPEC-089d: social_login fields
            providers = (bm["providers"] as? List<*>)?.mapNotNull { p ->
                if (p is Map<*, *>) {
                    @Suppress("UNCHECKED_CAST")
                    val pm = p as Map<String, Any>
                    SocialProvider(
                        type = pm["type"] as? String ?: "",
                        label = pm["label"] as? String,
                        enabled = pm["enabled"] as? Boolean ?: true,
                        // SPEC-070-A finalization OB-2 — per-provider color/style overrides.
                        bg_color = pm["bg_color"] as? String,
                        text_color = pm["text_color"] as? String,
                        border_color = pm["border_color"] as? String,
                        border_width = (pm["border_width"] as? Number)?.toFloat(),
                        corner_radius = (pm["corner_radius"] as? Number)?.toFloat(),
                        icon_style = pm["icon_style"] as? String,
                        icon_url = pm["icon_url"] as? String,
                    )
                } else null
            }?.toImmutableList(),
            button_style = bm["button_style"] as? String,
            button_height = (bm["button_height"] as? Number)?.toDouble(),
            spacing = (bm["spacing"] as? Number)?.toDouble(),
            show_divider = bm["show_divider"] as? Boolean,
            divider_text = bm["divider_text"] as? String,
            // Social-Login styling v2
            // SPEC-089d: countdown_timer fields
            target_type = bm["target_type"] as? String,
            duration_seconds = (bm["duration_seconds"] as? Number)?.toInt(),
            target_datetime = bm["target_datetime"] as? String,
            show_days = bm["show_days"] as? Boolean,
            show_hours = bm["show_hours"] as? Boolean,
            show_minutes = bm["show_minutes"] as? Boolean,
            show_seconds = bm["show_seconds"] as? Boolean,
            labels = (bm["labels"] as? Map<String, Any>)?.let { lm ->
                CountdownLabels(
                    days = lm["days"] as? String,
                    hours = lm["hours"] as? String,
                    minutes = lm["minutes"] as? String,
                    seconds = lm["seconds"] as? String,
                )
            },
            on_expire_action = bm["on_expire_action"] as? String,
            expired_text = bm["expired_text"] as? String,
            accent_color = bm["accent_color"] as? String,
            font_size = (bm["font_size"] as? Number)?.toDouble(),
            alignment = bm["alignment"] as? String,
            // SPEC-089d: rating fields
            field_id = bm["field_id"] as? String,
            max_stars = (bm["max_stars"] as? Number)?.toInt(),
            default_value = (bm["default_value"] as? Number)?.toDouble(),
            star_size = (bm["star_size"] as? Number)?.toDouble(),
            active_rating_color = bm["active_rating_color"] as? String ?: bm["filled_color"] as? String ?: bm["active_color"] as? String,
            inactive_rating_color = bm["inactive_rating_color"] as? String ?: bm["empty_color"] as? String ?: bm["inactive_color"] as? String,
            allow_half = bm["allow_half"] as? Boolean,
            label = bm["label"] as? String,
            // SPEC-089d: rich_text fields
            content = bm["content"] as? String,
            base_style = (bm["base_style"] as? Map<String, Any>)?.let { parseTextStyleConfig(it) },
            link_color = bm["link_color"] as? String,
            max_lines = (bm["max_lines"] as? Number)?.toInt(),
            // SPEC-089d: progress_bar fields
            segment_count = (bm["segment_count"] as? Number)?.toInt() ?: (bm["total_segments"] as? Number)?.toInt(),
            active_segments = (bm["active_segments"] as? Number)?.toInt() ?: (bm["filled_segments"] as? Number)?.toInt(),
            fill_color = bm["fill_color"] as? String,
            track_color = bm["track_color"] as? String,
            segment_gap = (bm["segment_gap"] as? Number)?.toDouble(),
            show_label = bm["show_label"] as? Boolean,
            label_style = (bm["label_style"] as? Map<String, Any>)?.let { parseTextStyleConfig(it) },
            // SPEC-089d: timeline fields
            timeline_items = (bm["timeline_items"] as? List<*>
                ?: bm["items"] as? List<*>)?.mapNotNull { ti ->
                if (ti is Map<*, *>) {
                    @Suppress("UNCHECKED_CAST")
                    val tm = ti as Map<String, Any>
                    // Only parse as TimelineItem if it has a title (to distinguish from string items)
                    val title = tm["title"] as? String ?: return@mapNotNull null
                    TimelineItem(
                        id = tm["id"] as? String ?: "",
                        title = title,
                        subtitle = tm["subtitle"] as? String,
                        icon = tm["icon"] as? String,
                        // SPEC-401-A R39 — drop `?: "upcoming"` parser
                        // walk-back of R18. R18 made `status` nullable
                        // on the data class so missing-status timeline
                        // items render in solid title color (not greyed).
                        // Parser hardcoded `?: "upcoming"`, so the
                        // renderer's `if (item.status == "upcoming")`
                        // greyed every missing-status title regardless.
                        status = tm["status"] as? String,
                    )
                } else null
            }?.toImmutableList(),
            line_color = bm["line_color"] as? String,
            completed_color = bm["completed_color"] as? String,
            current_color = bm["current_color"] as? String,
            upcoming_color = bm["upcoming_color"] as? String,
            show_line = bm["show_line"] as? Boolean,
            compact = bm["compact"] as? Boolean,
            title_style = (bm["title_style"] as? Map<String, Any>)?.let { parseTextStyleConfig(it) },
            subtitle_style = (bm["subtitle_style"] as? Map<String, Any>)?.let { parseTextStyleConfig(it) },
            // SPEC-089d: animated_loading fields
            loading_items = (bm["loading_items"] as? List<*>
                ?: if (bm["type"] == "animated_loading") bm["items"] as? List<*> else null)?.mapNotNull { li ->
                if (li is Map<*, *>) {
                    @Suppress("UNCHECKED_CAST")
                    val lm = li as Map<String, Any>
                    LoadingItem(
                        label = lm["label"] as? String ?: "",
                        duration_ms = (lm["duration_ms"] as? Number)?.toInt() ?: 1000,
                        icon = lm["icon"] as? String,
                        // SPEC-401-A R35 orbit-variant per-item fields.
                        // Until R36 these were declared on LoadingItem
                        // but never read from JSON.
                        icon_url = lm["icon_url"] as? String,
                        icon_bg_color = lm["icon_bg_color"] as? String,
                        icon_size = (lm["icon_size"] as? Number)?.toFloat(),
                        icon_orbit_angle = (lm["icon_orbit_angle"] as? Number)?.toFloat(),
                    )
                } else null
            }?.toImmutableList(),
            progress_color = bm["progress_color"] as? String,
            check_color = bm["check_color"] as? String,
            total_duration_ms = (bm["total_duration_ms"] as? Number)?.toInt(),
            auto_advance = bm["auto_advance"] as? Boolean,
            show_percentage = bm["show_percentage"] as? Boolean,
            // SPEC-089d: Form input fields
            field_label = bm["field_label"] as? String,
            field_placeholder = bm["field_placeholder"] as? String,
            field_required = bm["field_required"] as? Boolean,
            // SPEC-401-A R49 (Lens A #1) — multi_select on input_select
            // (iOS ContentBlockTypes.swift:1120). Without this Android
            // always renders single-select even when authored multi.
            multi_select = bm["multi_select"] as? Boolean,
            // SPEC-401-A R49 (Lens A #3-#5) — date picker constraints +
            // chrome (iOS ContentBlockStandaloneViews.swift:966-1239).
            allow_future = bm["allow_future"] as? Boolean,
            allow_past = bm["allow_past"] as? Boolean,
            date_validation_message = bm["date_validation_message"] as? String,
            picker_presentation = bm["picker_presentation"] as? String,
            picker_mode = bm["picker_mode"] as? String,
            picker_spacing = (bm["picker_spacing"] as? Number)?.toDouble(),
            wheel_bg_color = bm["wheel_bg_color"] as? String,
            wheel_height = (bm["wheel_height"] as? Number)?.toDouble(),
            calendar_bg_color = bm["calendar_bg_color"] as? String,
            // SPEC-401-A R49 (Lens A #2) — Sprint 7 scroll-collapse.
            collapse_on_scroll = bm["collapse_on_scroll"] as? Boolean,
            field_options = (bm["field_options"] as? List<*>)?.mapNotNull { fo ->
                if (fo is Map<*, *>) {
                    @Suppress("UNCHECKED_CAST")
                    val fm = fo as Map<String, Any>
                    // SPEC-070-A finalization P0 audit-11 Drift 2 —
                    // expanded to read all 24 iOS InputOption fields.
                    // Console-authored per-option styling now reaches
                    // the renderer instead of being dropped on parse.
                    InputOption(
                        // SPEC-401-A R37 — match iOS canonical
                        // value-first fallback (ContentBlockTypes.swift:358-360
                        // `let rawValue = decode(.value); self.value =
                        // rawValue ?? rawId`). Was id-first → response
                        // payload reported display id instead of
                        // authored canonical answer code, breaking
                        // server-side analytics joins + next_step_rule
                        // comparisons + webhook payloads when console
                        // author set both id and value.
                        value = fm["value"] as? String ?: fm["id"] as? String ?: "",
                        label = fm["label"] as? String ?: "",
                        image_url = fm["image_url"] as? String,
                        // SPEC-401-A R43 — match iOS rawValue fallback at
                        // ContentBlockTypes.swift:357-360. Was id-only; when
                        // console author writes only `value`, iOS resolves
                        // `id=value`, Android got `id=null` → renderer code
                        // keying selection state by option.id silently broke.
                        id = fm["id"] as? String ?: fm["value"] as? String,
                        icon = fm["icon"] as? String,
                        selected_image_url = fm["selected_image_url"] as? String,
                        unselected_image_url = fm["unselected_image_url"] as? String,
                        subtitle = fm["subtitle"] as? String,
                        title_color = fm["title_color"] as? String,
                        subtitle_color = fm["subtitle_color"] as? String,
                        title_font_size = (fm["title_font_size"] as? Number)?.toDouble(),
                        subtitle_font_size = (fm["subtitle_font_size"] as? Number)?.toDouble(),
                        title_font_weight = fm["title_font_weight"] as? String,
                        selected_icon = fm["selected_icon"] as? String,
                        unselected_icon = fm["unselected_icon"] as? String,
                        image_overlay_color = fm["image_overlay_color"] as? String,
                        image_overlay_opacity = (fm["image_overlay_opacity"] as? Number)?.toDouble(),
                        selected_image_overlay_color = fm["selected_image_overlay_color"] as? String,
                        selected_image_overlay_opacity = (fm["selected_image_overlay_opacity"] as? Number)?.toDouble(),
                        image_shape = fm["image_shape"] as? String,
                        border_color = fm["border_color"] as? String,
                        selected_border_color = fm["selected_border_color"] as? String,
                        bg_color = fm["bg_color"] as? String,
                        selected_bg_color = fm["selected_bg_color"] as? String,
                        selected_text_color = fm["selected_text_color"] as? String,
                        cell_alignment = fm["cell_alignment"] as? String,
                        // SPEC-419 D7/D5 — leading/trailing labels + per-option badge (were unparsed).
                        leading_text = fm["leading_text"] as? String,
                        trailing_text = fm["trailing_text"] as? String,
                        text_alignment = fm["text_alignment"] as? String,
                        badge = (fm["badge"] as? Map<*, *>)?.let { bdg ->
                            ai.appdna.sdk.onboarding.OptionBadge(
                                text = bdg["text"] as? String,
                                bg_color = bdg["bg_color"] as? String,
                                text_color = bdg["text_color"] as? String,
                                position = bdg["position"] as? String,
                            )
                        },
                    )
                } else null
            }?.toImmutableList(),
            // Gap 8: Parse field_config for display_style, use_variable, use_webhook
            // SPEC-419 gap#6 — progress_bar `label_format`/`custom_label` are authored
            // top-level by the console editor, but Android can't add top-level ContentBlock
            // params (JVM arg-budget guard), so fold them into field_config for the renderer.
            // iOS reads them as top-level Codable fields.
            field_config = run {
                val base = (bm["field_config"] as? Map<String, Any>)?.toMutableMap()
                val labelFormat = bm["label_format"] as? String
                val customLabel = bm["custom_label"] as? String
                // SPEC-419 pass-14 #3 — fold the top-level `video_controls` key
                // into field_config so the renderer can honour it without adding
                // a new top-level ContentBlock field (budget-locked). The console
                // editor + preview + iOS all read `video_controls`.
                val videoControls = bm["video_controls"] as? Boolean
                // SPEC-419 pass-15 #8/#9/#25 — star_background particle_color /
                // particle_opacity / particle_speed are authored top-level by the
                // console editor + preview + (now) iOS, but Android is budget-locked
                // (245/245 top-level params). Fold them into field_config so the
                // StarBackgroundBlock renderer can honor them.
                val particleColor = bm["particle_color"] as? String
                val particleOpacity = (bm["particle_opacity"] as? Number)?.toDouble()
                val particleSpeed = bm["particle_speed"] as? String
                // SPEC-419 pass-15 #10 — countdown_timer `timer_variant` authored top-level by editor; fold for the renderer.
                val timerVariant = bm["timer_variant"] as? String
                // Progress/Loading v2 — `label_placement` (progress_bar) + `loading_text_align`
                // (animated_loading) authored top-level by the console editor; fold into
                // field_config since Android's top-level ContentBlock is budget-locked. iOS
                // reads them as top-level Codable fields.
                val labelPlacement = bm["label_placement"] as? String
                val loadingTextAlign = bm["loading_text_align"] as? String
                // SPEC-401-A — date_wheel_picker selection-line color/stroke authored
                // top-level by the console editor + preview (block.wheel_line_color /
                // wheel_line_stroke_width) and read as top-level Codable fields by iOS
                // (ContentBlockStandaloneViews.swift:1335-1339). Android's top-level
                // ContentBlock is budget-locked at the JVM 255-arg limit, so fold both
                // into field_config for the DateWheelPickerBlock renderer to read.
                val wheelLineColor = bm["wheel_line_color"] as? String
                val wheelLineStrokeWidth = (bm["wheel_line_stroke_width"] as? Number)?.toDouble()
                // ContentBlock 255-arg budget — these 10 were removed from the Android ContentBlock
                // ctor (was 255→245) and are now read from field_config by the renderer. Console
                // authors them top-level + iOS reads them top-level; fold into field_config here.
                val audioUrl = bm["audio_url"] as? String
                val buttonTextAlign = bm["button_text_align"] as? String
                val dividerPosition = bm["divider_position"] as? String
                val dotShape = bm["dot_shape"] as? String
                val galleryAutoscroll = bm["gallery_autoscroll"] as? Boolean
                val galleryAutoscrollSpeed = (bm["gallery_autoscroll_speed"] as? Number)?.toDouble()
                val galleryFill = bm["gallery_fill"] as? Boolean
                val particleMulticolor = bm["particle_multicolor"] as? Boolean
                val timeFormat = bm["time_format"] as? String
                val timeTextSize = (bm["time_text_size"] as? Number)?.toDouble()
                if (labelFormat == null && customLabel == null && videoControls == null &&
                    particleColor == null && particleOpacity == null && particleSpeed == null &&
                    timerVariant == null && labelPlacement == null && loadingTextAlign == null &&
                    wheelLineColor == null && wheelLineStrokeWidth == null &&
                    audioUrl == null && buttonTextAlign == null && dividerPosition == null &&
                    dotShape == null && galleryAutoscroll == null && galleryAutoscrollSpeed == null &&
                    galleryFill == null && particleMulticolor == null && timeFormat == null &&
                    timeTextSize == null) {
                    base
                } else {
                    val merged = base ?: mutableMapOf()
                    if (labelFormat != null) merged.putIfAbsent("label_format", labelFormat)
                    if (customLabel != null) merged.putIfAbsent("custom_label", customLabel)
                    if (videoControls != null) merged.putIfAbsent("video_controls", videoControls)
                    if (particleColor != null) merged.putIfAbsent("particle_color", particleColor)
                    if (particleOpacity != null) merged.putIfAbsent("particle_opacity", particleOpacity)
                    if (particleSpeed != null) merged.putIfAbsent("particle_speed", particleSpeed)
                    if (timerVariant != null) merged.putIfAbsent("timer_variant", timerVariant)
                    if (labelPlacement != null) merged.putIfAbsent("label_placement", labelPlacement)
                    if (loadingTextAlign != null) merged.putIfAbsent("loading_text_align", loadingTextAlign)
                    if (wheelLineColor != null) merged.putIfAbsent("wheel_line_color", wheelLineColor)
                    if (wheelLineStrokeWidth != null) merged.putIfAbsent("wheel_line_stroke_width", wheelLineStrokeWidth)
                    if (audioUrl != null) merged.putIfAbsent("audio_url", audioUrl)
                    if (buttonTextAlign != null) merged.putIfAbsent("button_text_align", buttonTextAlign)
                    if (dividerPosition != null) merged.putIfAbsent("divider_position", dividerPosition)
                    if (dotShape != null) merged.putIfAbsent("dot_shape", dotShape)
                    if (galleryAutoscroll != null) merged.putIfAbsent("gallery_autoscroll", galleryAutoscroll)
                    if (galleryAutoscrollSpeed != null) merged.putIfAbsent("gallery_autoscroll_speed", galleryAutoscrollSpeed)
                    if (galleryFill != null) merged.putIfAbsent("gallery_fill", galleryFill)
                    if (particleMulticolor != null) merged.putIfAbsent("particle_multicolor", particleMulticolor)
                    if (timeFormat != null) merged.putIfAbsent("time_format", timeFormat)
                    if (timeTextSize != null) merged.putIfAbsent("time_text_size", timeTextSize)
                    merged
                }
            },
            // SPEC-089d: Visibility, animation, pressed style, bindings, sizing
            visibility_condition = (bm["visibility_condition"] as? Map<String, Any>)?.let { vc ->
                VisibilityCondition(
                    type = vc["type"] as? String ?: "always",
                    variable = vc["variable"] as? String,
                    value = vc["value"],
                    expression = vc["expression"] as? String,
                )
            },
            entrance_animation = (bm["entrance_animation"] as? Map<String, Any>)?.let { ea ->
                EntranceAnimationConfig(
                    type = ea["type"] as? String ?: "none",
                    duration_ms = (ea["duration_ms"] as? Number)?.toInt() ?: 300,
                    delay_ms = (ea["delay_ms"] as? Number)?.toInt() ?: 0,
                    easing = ea["easing"] as? String ?: "ease_out",
                    spring_damping = (ea["spring_damping"] as? Number)?.toDouble(),
                    animation_delay_ms = (ea["animation_delay_ms"] as? Number)?.toInt() ?: 0,
                    animation_order = (ea["animation_order"] as? Number)?.toInt(),
                )
            },
            pressed_style = (bm["pressed_style"] as? Map<String, Any>)?.let { ps ->
                PressedStyleConfig(
                    scale = (ps["scale"] as? Number)?.toDouble(),
                    opacity = (ps["opacity"] as? Number)?.toDouble(),
                    bg_color = ps["bg_color"] as? String,
                    text_color = ps["text_color"] as? String,
                )
            },
            bindings = (bm["bindings"] as? Map<*, *>)?.entries?.associate { (k, v) -> k.toString() to v.toString() },
            element_width = bm["element_width"] as? String,
            element_height = bm["element_height"] as? String,
            overflow = bm["overflow"] as? String,
            // Row / stack container fields
            // SPEC-070-A finalization §3.2 — coalesce `children` and
            // legacy `stack_children`. iOS ContentBlockTypes.swift:1068
            // accepts both keys; Android previously read only `children`,
            // so console-saved stack containers rendered blank.
            children = parseChildBlocks(bm["children"] ?: bm["stack_children"]),
            row_direction = bm["row_direction"] as? String,
            row_distribution = bm["row_distribution"] as? String,
            row_child_fill = bm["row_child_fill"] as? Boolean,
            gap = (bm["gap"] as? Number)?.toDouble(),
            wrap = bm["wrap"] as? Boolean,
            justify = bm["justify"] as? String,
            align_items = bm["align_items"] as? String,

            // ============================================================
            // SPEC-401-A R36 — Lens A P0 systemic parser drift fix.
            // Many ContentBlock fields were declared on the data class
            // and read by the renderer (often added by R3/R7/R10/R13/
            // R15/R16/R35) but never populated from the Firestore JSON
            // payload. Each "honour canonical first" fix was thus
            // shadowed by the parser; console-authored values silently
            // fell through to defaults. Adding all of them here so
            // every prior R-round renderer fix actually receives data.
            // ============================================================

            // Lottie/Rive canonical short names (iOS ContentBlockTypes.swift).
            // Console flow.schema.ts:225-244 emits these — NOT the
            // legacy `lottie_*`/`rive_*` aliases — so authoring
            // `autoplay: false` was always lost on Android.
            autoplay = bm["autoplay"] as? Boolean,
            loop = bm["loop"] as? Boolean,
            muted = bm["muted"] as? Boolean,
            controls = bm["controls"] as? Boolean,
            inline_playback = bm["inline_playback"] as? Boolean,
            lottie_height = (bm["lottie_height"] as? Number)?.toDouble(),
            lottie_width = (bm["lottie_width"] as? Number)?.toDouble(),
            play_on_scroll = bm["play_on_scroll"] as? Boolean,
            play_on_tap = bm["play_on_tap"] as? Boolean,
            color_overrides = (bm["color_overrides"] as? Map<*, *>)?.entries
                ?.mapNotNull { (k, v) ->
                    val ks = k as? String ?: return@mapNotNull null
                    val vs = v as? String ?: return@mapNotNull null
                    ks to vs
                }?.toMap(),
            artboard = bm["artboard"] as? String,
            state_machine = bm["state_machine"] as? String,
            rive_inputs = @Suppress("UNCHECKED_CAST") (bm["rive_inputs"] as? Map<String, Any>),
            trigger_on_step_complete = bm["trigger_on_step_complete"] as? String,

            // PricingCard fields (iOS ContentBlockTypes.swift PricingPlan).
            // PricingCardBlock at ContentBlockRenderer.kt:4006 reads
            // block.pricing_plans which was always null — card always
            // empty.
            pricing_plans = (bm["pricing_plans"] as? List<*>)?.mapNotNull { pp ->
                if (pp is Map<*, *>) {
                    @Suppress("UNCHECKED_CAST")
                    val pm = pp as Map<String, Any>
                    PricingPlan(
                        id = pm["id"] as? String ?: "",
                        label = pm["label"] as? String ?: pm["title"] as? String ?: "",
                        price = pm["price"] as? String ?: "",
                        period = pm["period"] as? String ?: "",
                        badge = pm["badge"] as? String,
                        is_highlighted = pm["is_highlighted"] as? Boolean
                            ?: pm["highlighted"] as? Boolean
                            ?: false,
                    )
                } else null
            }?.toImmutableList(),
            pricing_layout = bm["pricing_layout"] as? String,

            // Rich text canonical (R4)
            markdown_content = bm["markdown_content"] as? String,
            rich_text_variant = bm["rich_text_variant"] as? String,

            // Social-login R13 below-inputs CTA layout
            email_login_placement = bm["email_login_placement"] as? String,
            email_cta_spacing_below = (bm["email_cta_spacing_below"] as? Number)?.toDouble(),

            // Rating canonical R3
            default_rating = (bm["default_rating"] as? Number)?.toDouble(),
            rating_label = bm["rating_label"] as? String,

            // ProgressBar canonical (Phase F + R3)
            total_segments = (bm["total_segments"] as? Number)?.toInt(),
            progress_value = (bm["progress_value"] as? Number)?.toDouble(),
            progress_variant = bm["progress_variant"] as? String,
            bar_color = bm["bar_color"] as? String,
            bar_gradient_colors = (bm["bar_gradient_colors"] as? List<*>)?.mapNotNull { it as? String },
            bar_height = (bm["bar_height"] as? Number)?.toDouble(),

            // WheelPicker (Phase F + R35 horizontal/haptic)
            min_value = (bm["min_value"] as? Number)?.toDouble(),
            max_value_picker = (bm["max_value_picker"] as? Number)?.toDouble(),
            step_value = (bm["step_value"] as? Number)?.toDouble(),
            default_picker_value = (bm["default_picker_value"] as? Number)?.toDouble(),
            unit = bm["unit"] as? String,
            unit_position = bm["unit_position"] as? String,
            visible_items = (bm["visible_items"] as? Number)?.toInt(),
            wheel_orientation = bm["wheel_orientation"] as? String,
            orientation = bm["orientation"] as? String,
            highlight_color = bm["highlight_color"] as? String,
            haptic_on_scroll = bm["haptic_on_scroll"] as? Boolean,

            // DateWheelPicker (Phase F)
            min_date = bm["min_date"] as? String,
            max_date = bm["max_date"] as? String,
            default_date_value = bm["default_date_value"] as? String,
            // SPEC-402 A.2 — `columns` (per-wheel type/label/values list)
            // was declared on ContentBlock + read by the renderer but
            // never extracted from JSON. date_wheel_picker fell through
            // to default month/day/year columns regardless of console.
            columns = (bm["columns"] as? List<*>)?.mapNotNull { c ->
                if (c is Map<*, *>) {
                    @Suppress("UNCHECKED_CAST") val cm = c as Map<String, Any>
                    DateWheelColumn(
                        type = cm["type"] as? String ?: "",
                        label = cm["label"] as? String,
                        values = (cm["values"] as? List<*>)
                            ?.mapNotNull { it as? String }
                            ?.toImmutableList(),
                    )
                } else null
            }?.toImmutableList(),

            // PulsingAvatar (Phase F + R3)
            pulse_color = bm["pulse_color"] as? String,
            pulse_ring_count = (bm["pulse_ring_count"] as? Number)?.toInt(),
            pulse_speed = (bm["pulse_speed"] as? Number)?.toDouble(),
            border_width = (bm["border_width"] as? Number)?.toDouble(),
            border_color = bm["border_color"] as? String,
            image_shape = bm["image_shape"] as? String,
            image_corner_radius = (bm["image_corner_radius"] as? Number)?.toDouble(),
            badge_position = bm["badge_position"] as? String,
            badge_size = (bm["badge_size"] as? Number)?.toDouble(),

            // StarBackground (Phase F) — density/speed are String tokens
            // ("low"/"medium"/"high", "slow"/"normal"/"fast") on the
            // data class; iOS reads the same shape.
            particle_type = bm["particle_type"] as? String,
            density = bm["density"] as? String,
            speed = bm["speed"] as? String,
            secondary_color = bm["secondary_color"] as? String,
            size_range = (bm["size_range"] as? List<*>)?.mapNotNull { (it as? Number)?.toDouble() }?.toImmutableList(),
            fullscreen = bm["fullscreen"] as? Boolean,

            // CircularGauge (Phase F — block previously rendered fully default)
            gauge_value = (bm["gauge_value"] as? Number)?.toDouble(),
            max_value = (bm["max_value"] as? Number)?.toDouble(),
            // SPEC-402 A.2 — both max_value AND max_gauge_value are
            // accepted iOS-side (ContentBlockRenderer.swift falls back
            // `block.max_value ?? block.max_gauge_value ?? 100.0`).
            // Decoder previously dropped max_gauge_value silently.
            max_gauge_value = (bm["max_gauge_value"] as? Number)?.toDouble(),
            gauge_variant = bm["gauge_variant"] as? String,
            gradient_start_color = bm["gradient_start_color"] as? String,
            gradient_end_color = bm["gradient_end_color"] as? String,
            arrow_color = bm["arrow_color"] as? String,
            arrow_stroke_width = (bm["arrow_stroke_width"] as? Number)?.toDouble(),
            min_label = bm["min_label"] as? String,
            max_label = bm["max_label"] as? String,
            min_max_font_size = (bm["min_max_font_size"] as? Number)?.toDouble(),
            min_max_color = bm["min_max_color"] as? String,
            percentage_location = bm["percentage_location"] as? String,
            sublabel = bm["sublabel"] as? String,
            stroke_width = (bm["stroke_width"] as? Number)?.toDouble(),
            label_color = bm["label_color"] as? String,
            label_font_size = (bm["label_font_size"] as? Number)?.toDouble(),
            animate = bm["animate"] as? Boolean,
            animation_duration_ms = (bm["animation_duration_ms"] as? Number)?.toInt(),

            // AnimatedLoading variant (R3) — block.variant alias works,
            // but loading_variant is the canonical name iOS reads.
            loading_variant = bm["loading_variant"] as? String,
            loading_text = bm["loading_text"] as? String,
            loading_text_position = bm["loading_text_position"] as? String,
            loading_text_size = (bm["loading_text_size"] as? Number)?.toDouble(),
            // Android maps every key by hand — a field on the data class alone stays null forever.
            loading_bar_height = (bm["loading_bar_height"] as? Number)?.toDouble(),
            loading_item_size = (bm["loading_item_size"] as? Number)?.toDouble(),
            loading_text_color = bm["loading_text_color"] as? String,
            gallery_images = (bm["gallery_images"] as? List<*>)?.mapNotNull { it as? String }?.toImmutableList(),
            gallery_item_width = (bm["gallery_item_width"] as? Number)?.toDouble(),
            gallery_item_height = (bm["gallery_item_height"] as? Number)?.toDouble(),
            gallery_corner_radius = (bm["gallery_corner_radius"] as? Number)?.toDouble(),
            gallery_spacing = (bm["gallery_spacing"] as? Number)?.toDouble(),
            gallery_align = bm["gallery_align"] as? String,

            // Container/positioning (multiple R-rounds) — column_ratios
            // is a colon-encoded ratio string ("1:2") on both platforms
            // (iOS ContentBlockTypes.swift, Android data class line 634).
            column_ratios = bm["column_ratios"] as? String,

            // SPEC-401-A R37 Lens A — same-pattern parser drift
            // continuation of R36. These four fields are declared on the
            // data class + read first by their respective renderers but
            // never populated from JSON.

            // P0 — `field_style` envelope (FormFieldBlockStyle, 17
            // styling tokens) is read by every form_input_* renderer
            // (text/slider/select/chips/rating/toggle/etc) but parser
            // dropped it entirely; console-authored field styling
            // silently fell through to hardcoded defaults
            // (#D1D5DB borders, 8.0 radius, #374151 labels).
            field_style = (bm["field_style"] as? Map<*, *>)?.let { fs ->
                FormFieldBlockStyle(
                    background_color = fs["background_color"] as? String,
                    border_color = fs["border_color"] as? String,
                    border_width = (fs["border_width"] as? Number)?.toDouble(),
                    corner_radius = (fs["corner_radius"] as? Number)?.toDouble(),
                    text_color = fs["text_color"] as? String,
                    placeholder_color = fs["placeholder_color"] as? String,
                    font_size = (fs["font_size"] as? Number)?.toDouble(),
                    focused_border_color = fs["focused_border_color"] as? String,
                    label_color = fs["label_color"] as? String,
                    label_font_size = (fs["label_font_size"] as? Number)?.toDouble(),
                    // SPEC-439 (#546) — Android builds FormFieldBlockStyle key by key, so
                    // adding a field to the data class is not enough: unmapped here it stays
                    // null forever and the renderer reading it can never fire. This is the
                    // half of the label_position defect that the fixture actually caught.
                    label_position = fs["label_position"] as? String,
                    label_align = fs["label_align"] as? String,
                    label_font_family = fs["label_font_family"] as? String,
                    error_border_color = fs["error_border_color"] as? String,
                    error_text_color = fs["error_text_color"] as? String,
                    track_color = fs["track_color"] as? String,
                    fill_color = fs["fill_color"] as? String,
                    thumb_color = fs["thumb_color"] as? String,
                    toggle_on_color = fs["toggle_on_color"] as? String,
                    toggle_off_color = fs["toggle_off_color"] as? String,
                    // SPEC-401-A R41 — match iOS ContentBlockTypes.swift:291-312
                    // (3 fields R37 missed). Renderer wiring follow-up.
                    height = fs["height"] as? String,
                    font_weight = fs["font_weight"] as? String,
                    focused_background_color = fs["focused_background_color"] as? String,
                    // Select v2 (Mrozu QA) — per-option styling extras.
                    option_font_family = fs["option_font_family"] as? String,
                    option_corner_radius = (fs["option_corner_radius"] as? Number)?.toDouble(),
                    option_text_wrap = fs["option_text_wrap"] as? Boolean,
                    option_image_scale = fs["option_image_scale"] as? String,
                    checkmark_color = fs["checkmark_color"] as? String,
                )
            },

            // P1 — `filled_segments` parser previously folded it into
            // active_segments fallback, but renderer at
            // ContentBlockRenderer.kt:2561 reads filled_segments first
            // (`block.filled_segments ?: block.active_segments`).
            // Author intent was lost when only filled_segments was set.
            filled_segments = (bm["filled_segments"] as? Number)?.toInt(),

            // P1 — Rating canonical iOS-first names (R3 added the
            // renderer fallback at lines 2301-2306 but parser never
            // reads them; rating fell through to default colours).
            filled_color = bm["filled_color"] as? String,
            empty_color = bm["empty_color"] as? String,
            z_index = (bm["z_index"] as? Number)?.toDouble(),
            image_fit = bm["image_fit"] as? String,
            image_frame = bm["image_frame"] as? String,
            view_key = bm["view_key"] as? String,
            custom_config = @Suppress("UNCHECKED_CAST") (bm["custom_config"] as? Map<String, Any>),
            placeholder_image_url = bm["placeholder_image_url"] as? String,
            placeholder_text = bm["placeholder_text"] as? String,
        )
    }

    /**
     * Recursively parse child ContentBlocks for row/stack containers.
     * Accepts the raw `children` value from a Firestore map and returns a
     * list of [ContentBlock], or null if no children are present.
     *
     * SPEC-070-A J.22 — returns ImmutableList so ContentBlock.children
     * stays Compose-stable through the recursive tree.
     *
     * SPEC-401-A R40 P0 — delegates per-child decoding to
     * [decodeContentBlock] so nested children get the SAME ~150-field
     * decode path as top-level blocks. The previous skeleton populated
     * only ~25 fields, silently shadowing every R3-R39 renderer fix for
     * any block nested inside a row or stack container.
     */
    @Suppress("UNCHECKED_CAST")
    private fun parseChildBlocks(raw: Any?): kotlinx.collections.immutable.ImmutableList<ContentBlock>? {
        val list = raw as? List<*> ?: return null
        return list.mapNotNull { child ->
            if (child is Map<*, *>) {
                val cm = child as Map<String, Any>
                decodeContentBlock(cm)
            } else null
        }.takeIf { it.isNotEmpty() }?.toImmutableList()
    }
}
