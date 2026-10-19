package ai.appdna.sdk.onboarding

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import ai.appdna.sdk.core.TextStyleConfig
import ai.appdna.sdk.core.HapticConfig
import ai.appdna.sdk.core.HapticTriggers
import ai.appdna.sdk.core.ParticleEffect
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import ai.appdna.sdk.core.TypedRead
import ai.appdna.sdk.core.bool
import ai.appdna.sdk.core.num
import ai.appdna.sdk.core.str
import ai.appdna.sdk.core.list
import ai.appdna.sdk.core.listOfMaps
import ai.appdna.sdk.core.obj
import ai.appdna.sdk.core.objMap

/** #958 — what a mis-typed field in this document is reported against. */
private const val SURFACE = TypedRead.ONBOARDING

/**
 * Firestore schema types for onboarding flows (Android).
 * Mirrors the iOS OnboardingConfig.swift.
 *
 * Compose stability:
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
 * `@Keep` so R8/minify cannot strip getters used by
 * reflective `fromMap`-style parsing in [OnboardingConfigParser].
 */
// @Stable rather than @Immutable: graph_layout / graph_nodes
// are passthrough JSON bags from Firestore (Map<String, Any?>) that we don't
// migrate (EXCLUDE rule for raw JSON parsing maps), and
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
    // Top-level fields mirroring iOS OnboardingFlowConfig
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
 * `@Keep` so R8/minify cannot strip getters used by reflection
 * via [OnboardingConfigParser].
 */
@Immutable
@androidx.annotation.Keep
data class BackButtonStyle(
    val icon_size: Double? = null,
    val icon_color: String? = null,
    val position: String? = null,  // "left" | "right"
    // Custom back glyph (any char, default "←") + back-arrow⇄X switch
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
    // Rich media config
    val haptic: HapticConfig? = null,
    val particle_effect: ParticleEffect? = null,
    // Gap 9: Custom progress bar colors
    val progress_color: String? = null,
    val progress_track_color: String? = null,
    // Thin sizing (custom height; the Material bar floored ~4dp) + multiple colors at once.
    val progress_height: Double? = null,
    val progress_gradient_colors: List<String>? = null,
    // Optional "Skip" link rendered beside the progress bar.
    val progress_skip_label: String? = null,
    // Dynamic color flash on step-advance ("success" pulse): the progress fill briefly
    // animates to this color on each advance, then back to the normal fill.
    val progress_flash_color: String? = null,
    // Progress style + back button + content padding (iOS parity)
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
 * Matches iOS `NextStepRule` shape — supports both the
 * legacy single-`condition` field AND the multi-`conditions` array with
 * AND/OR `logic` composition. Mirrors `OnboardingConfig.swift:122-127`.
 *
 * Evaluation precedence (see [NextStepRuleEvaluator]):
 *   1. If `conditions` is non-null/non-empty → evaluate every entry under `logic`.
 *   2. Else if `condition` is non-null → wrap as a 1-element list.
 *   3. Else → treat as `always` (rule matches unconditionally).
 */
// @Stable: `condition: Any?` and `conditions: List<Map<String, Any?>>`
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
    /**
     * #896 — when true, the SDK asks Play for an in-app review as this step APPEARS.
     *
     * 🔴 On the step, not on a button. Apple's rule is to not call the review prompt in response
     * to a user action and Google's design guidelines forbid putting a prompt in front of theirs;
     * arriving at a step is a moment in a flow, a tap is a request. A button that takes the user
     * to the store listing is the other half and is allowed — `action: "rate_app"`.
     *
     * The OS decides whether anything appears and reports nothing about the outcome, deliberately,
     * so an app cannot condition on it. Nothing of ours is drawn.
     */
    val request_store_review: Boolean? = null,
    /**
     * #921 — the step advances by itself after this many seconds.
     *
     * A splash screen and a step with a forgotten button are the same shape on the wire; this is
     * one of the two fields that tell them apart. [StepAutoAdvance] holds the precedence
     * ([wait_for_host] wins over this) and the bounds. `Double`, not `Int`: the console accepts
     * 0.5s and a JSON number does not say which it is.
     */
    val auto_advance_seconds: Double? = null,
    /**
     * #921 — the step is DECLARED host-driven: it holds until the app calls
     * `AppDNA.advanceOnboardingStep()`, and never times out.
     *
     * Carries no rendering of its own. What it buys is that the console's publish warning and the
     * platform's dead-end probe can skip the step honestly instead of being told to ignore two step
     * ids, and that a leftover [auto_advance_seconds] cannot cut the app's loading short.
     */
    val wait_for_host: Boolean? = null,
    val next_step_rules: ImmutableList<NextStepRule>? = null,
    /**
     * The step's authored name ("Screen path 1").
     *
     * Decoded solely so a cross-step `answer_key` can name its source step the way the console
     * writes it. The console publishes the whole step, so this has always been on the wire — it
     * was simply never parsed, and every name-keyed navigation rule silently resolved to null.
     */
    val name: String? = null,
    /**
     * The step's content blocks exactly as published (the same ladder the typed
     * decode reads), deep-copied once at parse, id-stamped (`<stepId>/<index path>`) and with
     * SDK-internal markers stripped. The raw host-data pass resolves THESE before typed decode, so
     * `data_templates`, `repeat` and tokens in typed slots survive. Null for a step built in code →
     * today's view-level pass.
     */
    val rawContentBlocks: RawContentBlocks? = null,
) {
    enum class StepType(val value: String) {
        WELCOME("welcome"),
        QUESTION("question"),
        VALUE_PROP("value_prop"),
        CUSTOM("custom"),
        FORM("form"),
        INTERACTIVE_CHAT("interactive_chat"),
        // `info` and `permission` are author-friendly step
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
 * The raw step JSON side table carried on [OnboardingStep].
 *
 * NOT a data class on purpose: equality is IDENTITY. A deep compare of every block map in
 * `equals`/`hashCode` would run on every recomposition, and Compose only needs to know whether the
 * published step changed — which a re-parse (new instance) answers. The maps are never mutated; the
 * resolver deep-copies before it touches anything.
 */
@Immutable
class RawContentBlocks internal constructor(
    /** Stamped raw top-level blocks, index-aligned with the published array (non-objects kept). */
    val blocks: List<Any?>,
)

/**
 * Step configuration -- varies by step type.
 *
 * @Stable rather than @Immutable: `layout`, `field_defaults`
 * and `localizations` are JSON passthrough Map fields (EXCLUDE rule).
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

    // form
    val fields: ImmutableList<FormField>? = null,
    val validation_mode: String? = null,  // "on_submit" or "realtime"

    // Populated by applyOverrides from StepConfigOverride.fieldDefaults
    // J.22 EXCLUDE: caller-provided defaults bag — left raw.
    val field_defaults: Map<String, Any>? = null,

    // Interactive chat
    val chat_config: ChatConfig? = null,

    // Rendering fidelity
    val content_blocks: ImmutableList<ContentBlock>? = null,
    val layout_variant: String? = null,  // image_top, image_bottom, image_fullscreen, image_split, no_image
    val background: ai.appdna.sdk.core.BackgroundStyleConfig? = null,
    val text_style: ai.appdna.sdk.core.TextStyleConfig? = null,
    val element_style: ai.appdna.sdk.core.ElementStyleConfig? = null,
    val animation: ai.appdna.sdk.core.AnimationConfig? = null,
    // J.22 EXCLUDE: `localizations` is a nested JSON passthrough.
    val localizations: Map<String, Map<String, String>>? = null,
    val default_locale: String? = null,

    // Restart attempt 2 F1+F2:
    // (F1) layout-level `next_step_rules` so the editor's Logic panel can
    //      author rules under `step.config.next_step_rules` (mirroring iOS
    //      OnboardingRenderer.swift:761-766 where layout rules are preferred
    //      when richer than step-level).
    // (F2) per-step `progress_color` override consulted by OnboardingActivity
    //      progress-bar theming (mirrors iOS OnboardingRenderer.swift:174-186).
    val next_step_rules: ImmutableList<NextStepRule>? = null,
    val progress_color: String? = null,

    // Permission-step contract. The console serializer writes these at the
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
     * Per-option caption text
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

// MARK: - Form Field Types

enum class FormFieldType(val value: String) {
    TEXT("text"), TEXTAREA("textarea"), NUMBER("number"),
    EMAIL("email"), PHONE("phone"),
    // Missing field types added — schema in
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

/**
 * Structured location data from geocoding autocomplete.
 *
 * Every field except [formatted_address] is optional: a stored answer may be a
 * typed string (no coordinates, no city) or a partial selection, and a missing value is `null`, never
 * a made-up `0.0` / `""` / `"UTC"`. (Source change for hosts that read these unwrapped.)
 */
data class LocationData(
    val formatted_address: String = "",
    val city: String? = null,
    val state: String? = null,
    val state_code: String? = null,
    val country: String? = null,
    val country_code: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val timezone: String? = null,
    val timezone_offset: Int? = null,
    val postal_code: String? = null,
    val raw_query: String? = null,
)

/**
 * [LocationData] from whatever a location field stored, tolerantly.
 *
 * - a map (a selected suggestion, a partial one, or the legacy `{address, latitude, longitude}`):
 *   each field read on its own; `address` is the legacy name of `formatted_address`; a coordinate is
 *   kept only when it is a finite number. A map with no address at all is not a location → `null`.
 * - a non-empty string (typed, never selected): `{formatted_address: text, raw_query: text}`, the
 *   rest null — the host still gets what the user typed.
 * - anything else (empty string, number, null, no answer): `null`.
 */
internal fun locationDataFromAnswer(value: Any?): LocationData? {
    fun str(v: Any?): String? = (v as? String)?.takeIf { it.isNotEmpty() }
    fun coord(v: Any?): Double? = (v as? Number)?.toDouble()?.takeIf { it.isFinite() }
    return when (value) {
        is String -> value.takeIf { it.isNotBlank() }?.let { LocationData(formatted_address = it, raw_query = it) }
        is Map<*, *> -> {
            val address = str(value["formatted_address"]) ?: str(value["address"]) ?: return null
            LocationData(
                formatted_address = address,
                city = str(value["city"]),
                state = str(value["state"]),
                state_code = str(value["state_code"]),
                country = str(value["country"]),
                country_code = str(value["country_code"]),
                latitude = coord(value["latitude"]),
                longitude = coord(value["longitude"]),
                timezone = str(value["timezone"]),
                timezone_offset = (value["timezone_offset"] as? Number)?.toInt(),
                postal_code = str(value["postal_code"]),
                raw_query = str(value["raw_query"]),
            )
        }
        else -> null
    }
}

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
    // Location
    val location_type: String? = null,
    val location_bias_country: String? = null,
    val location_language: String? = null,
    val location_placeholder: String? = null,
    val location_min_chars: Int? = null,
    // Opt-out for the prefix location pin.
    // iOS LocationFieldView reads `field_config["show_prefix_icon"]`
    // (default true). Some flows want to hide the pin in dark/minimalist
    // designs.
    val show_prefix_icon: Boolean? = null,
    // Caption + icon shown below the field
    // (e.g. select_grid hint "tap multiple to choose"). iOS
    // FormInputBlockViews.swift:769-770 reads from `field_config`.
    val tooltip_text: String? = null,
    val tooltip_icon: String? = null,
    // Rating
    val max_stars: Int? = null,
    val allow_half: Boolean? = null,
    val star_size: Int? = null,
    val filled_color: String? = null,
    val empty_color: String? = null,
    // range_slider
    val min_label: String? = null,
    val max_label: String? = null,
    // image_picker
    val max_size_mb: Double? = null,
    val allowed_types: String? = null, // "photo" | "camera" | "both"
    val aspect_ratio: String? = null, // e.g. "1:1", "4:3", "16:9", or "free"
    val placeholder_text: String? = null,
    // Color picker
    val default_color: String? = null,
    val show_opacity: Boolean? = null,
    val preset_colors: List<String>? = null,
    // Url validation
    val validate_format: Boolean? = null,
    // multiline_chips
    val max_chips: Int? = null,
    val suggestions: List<String>? = null,
    val allow_custom: Boolean? = null,
    // Signature
    val stroke_color: String? = null,
    val stroke_width: Double? = null,
    val clear_button_text: String? = null
)

@Immutable
/**
 * Per-field style envelope (mirrors `FormFieldStyleSchema`
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
    /**
     * #596 — the SAME `config` object, kept raw.
     *
     * `FormFieldConfig` is a typed class, so every key it does not declare is dropped at parse. The
     * console's Select field offers six display styles and roughly twenty option-styling keys; none
     * are declared there, so `display_style: "image_tiles"` never survived parsing and the field
     * always fell through to an ExposedDropdownMenu.
     *
     * Keeping the raw map lets a form Select render through the same engine the `input_select`
     * CONTENT block uses, rather than growing a second implementation of six layouts. iOS parity:
     * `FormField.config_raw`.
     */
    val config_raw: Map<String, Any>? = null,
    val depends_on: FormFieldDependency? = null,
    val style: FormFieldStyle? = null,
)

// MARK: - Async Step Hook Types

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
     * Port of iOS v1.0.60
     * `OnboardingConfig.swift:485 case stay(message: String? = nil)`.
     *
     * Stay on the current step optionally surfacing a success/info message.
     * Differs from [Block]: [Stay] is for "host handled the side effect,
     * waiting for user choice / verification email / etc." — not an error.
     * If `message` is null/empty the SDK stays silent (host handles UI).
     */
    data class Stay(val message: String? = null) : StepAdvanceResult()

    companion object {
        /**
         * The minimum time a Flutter / React Native bridge waits for the host's
         * `onBeforeStepAdvance` reply on a sign-in action. The native core imposes no timeout; this
         * floor only stops a bridge from cutting a legitimate sign-in (OS sheets, 2FA, a backend
         * round trip), while still bounding a handler that never answers.
         */
        const val AUTH_BRIDGE_TIMEOUT_MS: Long = 120_000L

        /**
         * The bridge floor for this hook call: [AUTH_BRIDGE_TIMEOUT_MS] when `stepData["action"]` is a
         * sign-in action ([AuthActionPolicy.bridgeFloorActions]), else `null` (the configured timeout
         * applies). Bridges wait `max(configured, floor ?: 0)`.
         */
        @JvmStatic
        fun minimumBridgeTimeoutMs(stepData: Map<String, Any?>?): Long? {
            val action = stepData?.get("action") as? String ?: return null
            return if (action in AuthActionPolicy.bridgeFloorActions) AUTH_BRIDGE_TIMEOUT_MS else null
        }

        /** The decision types a wrapper bridge understands in a host's `onBeforeStepAdvance` reply. */
        private val BRIDGE_DECISION_TYPES = setOf(
            "proceed", "proceedWithData", "block", "skipTo", "skipToWithData", "stay",
        )

        /**
         * Did the host make an EXPLICIT, RECOGNISED decision? A wrapper bridge's auth gate
         * blocks a sign-in step unless this is true. Not a decision: a non-map, the
         * `__appdna_unhandled` sentinel, a map with no or an unknown `type` — and a `skipTo` /
         * `skipToWithData` whose `stepId` is missing, not a string or blank. That last one used to
         * count: `{type: "skipTo"}` decoded to `SkipTo("")`, which names no step and so ADVANCED — past
         * a sign-in step nobody signed in on. Same rule as iOS `isExplicitBridgeDecision(_:)`.
         */
        @JvmStatic
        fun isExplicitBridgeDecision(reply: Any?): Boolean {
            val map = reply as? Map<*, *> ?: return false
            if (map["__appdna_unhandled"] == true) return false
            val type = map["type"] as? String ?: return false
            if (type !in BRIDGE_DECISION_TYPES) return false
            if (type == "skipTo" || type == "skipToWithData") return bridgeSkipTarget(map) != null
            return true
        }

        /**
         * The step a `skipTo` / `skipToWithData` reply names, or null when the reply is not a skip or
         * its `stepId` is missing, not a string or blank. A wrapper decodes a null target as NO skip:
         * `ProceedWithData` when the reply carries data, else `Proceed` (and on a sign-in step it is
         * not a decision at all — [isExplicitBridgeDecision]).
         */
        @JvmStatic
        fun bridgeSkipTarget(reply: Any?): String? {
            val map = reply as? Map<*, *> ?: return null
            val type = map["type"] as? String
            if (type != "skipTo" && type != "skipToWithData") return null
            return (map["stepId"] as? String)?.takeIf { it.isNotBlank() }
        }
    }
}

/**
 * Optional config override for dynamic step content.
 */
data class StepConfigOverride(
    val fieldDefaults: Map<String, Any>? = null,
    val title: String? = null,
    val subtitle: String? = null,
    val ctaText: String? = null,
    /**
     * Options supplied by the HOST APP, keyed by block id.
     *
     * The app reads its own backend with its own client, auth and cache and hands the list over.
     * No network, no credentials and no retry semantics inside this SDK — which is the point: a
     * customer whose list sits behind their own auth cannot be served by our fetcher.
     *
     * A block not named here keeps its authored options.
     *
     * `layoutOverrides` was REMOVED from this type. It was declared on all four SDKs and bridged
     * by the wrappers, and no renderer ever read it — a host could set it and nothing happened.
     * Making it work was the wrong fix: a bag that can overwrite any part of a step is unbounded,
     * untestable and a promise we would have to keep forever. Removing a public field is normally
     * breaking; here nothing ever read it, so no host can depend on its behaviour.
     */
    val fieldOptions: Map<String, List<InputOption>>? = null,
    /**
     * The host's data for THIS step, addressable from the console as `{{hook_data.…}}`.
     *
     * Not another override: nothing here replaces a field. It is a read-only namespace the
     * renderer's existing resolver reads, so the AUTHOR decides what is dynamic (by typing
     * `{{hook_data.tour.price}}` in the console, or pointing a block `binding` at `hook_data.…`)
     * and the host only supplies values. That is what separates it from the `layoutOverrides`
     * mistake above: the reachable surface is exactly the resolver's whitelist, and a path no
     * author referenced does nothing at all.
     *
     * It exists because a screen that must show live data ON ARRIVAL — a recommendation card, a
     * booking summary, an audio preview — has no interaction to trigger
     * `ElementInteractionResult.fieldConfigPatches`, which only fires after a tap. `hook_data` is
     * one of the six roots `resolveDotPath` accepts and had no producer: every `{{hook_data.x}}`
     * in every flow resolved to nothing, on both platforms.
     *
     * Paths walk maps and index lists, so `hook_data.recommendations.0.imageUrl` resolves.
     */
    val dataContext: Map<String, Any>? = null,
    /**
     * The route a `map` block draws, supplied by the HOST APP, keyed by block id.
     *
     * The third and last route source, and the only one that can answer "where is this delivery
     * right now": authored stops are fixed at publish time and a template variable can only carry
     * what the flow already knows. The app reads its own routing service with its own client and
     * hands the result over. No network, no credentials and no map-provider SDK inside ours.
     *
     * Typed rather than a free-form `field_config` patch on purpose. A bag that can overwrite any
     * key of any block is exactly what `layoutOverrides` was, and removing that was the right call
     * — it was unbounded, untestable, and a promise we would have had to keep forever. A route has
     * a shape, so it gets a type.
     *
     * A block not named here keeps its authored route.
     */
    val mapRoutes: Map<String, MapRouteOverride>? = null,
) {
    companion object {
        /**
         * The one public way to turn a wrapper bridge's raw
         * `[blockId: [option maps]]` into typed options.
         *
         * It lives HERE, in the core, rather than in each wrapper. `OnboardingConfigParser` is
         * `internal` on purpose, so the RN wrapper -- a separate Gradle module -- could not reach
         * it and the wrapper's own copy failed to compile. Re-parsing the maps inside the wrapper
         * would have compiled and been worse: ADR-001 says a wrapper carries no logic, and two
         * option parsers are how the promise that a host-supplied option and an authored one
         * render identically quietly stops being true.
         *
         * Returns null for anything unusable, so a malformed bridge payload leaves the block's
         * authored options standing rather than blanking the field.
         */
        @JvmStatic
        fun decodeFieldOptions(raw: Any?): Map<String, List<InputOption>>? {
            val byBlock = raw as? Map<*, *> ?: return null
            val out = mutableMapOf<String, List<InputOption>>()
            for ((k, v) in byBlock) {
                val blockId = k as? String ?: continue
                val list = v as? List<*> ?: continue
                out[blockId] = OnboardingConfigParser.parseInputOptionList(list)
            }
            return out.ifEmpty { null }
        }

        /**
         * The one public way to turn a wrapper bridge's raw
         * `[blockId: {polyline, stops}]` into typed route overrides.
         *
         * It lives HERE, in the core, for the same reason `decodeFieldOptions` does:
         * `OnboardingConfigParser` is `internal`, so the RN wrapper — a separate Gradle module —
         * cannot reach it, and a second decoder written inside the wrapper would compile happily
         * while quietly diverging from this one.
         *
         * Returns null for anything unusable, so a malformed bridge payload leaves the block's
         * authored route standing rather than blanking the map.
         */
        @JvmStatic
        fun decodeMapRoutes(raw: Any?): Map<String, MapRouteOverride>? {
            val byBlock = raw as? Map<*, *> ?: return null
            val out = mutableMapOf<String, MapRouteOverride>()
            for ((k, v) in byBlock) {
                val blockId = k as? String ?: continue
                val route = v as? Map<*, *> ?: continue
                val stops = (route["stops"] as? List<*>)?.mapNotNull { s ->
                    val stop = s as? Map<*, *> ?: return@mapNotNull null
                    val lat = (stop["lat"] as? Number)?.toDouble() ?: return@mapNotNull null
                    val lng = (stop["lng"] as? Number)?.toDouble() ?: return@mapNotNull null
                    if (!lat.isFinite() || !lng.isFinite()) return@mapNotNull null
                    MapRouteStop(lat, lng, stop["title"] as? String)
                } ?: emptyList()
                val polyline = (route["polyline"] as? String)?.ifEmpty { null }
                // Neither a line nor a place is not a route — drop it rather than blanking the map.
                if (polyline == null && stops.isEmpty()) continue
                out[blockId] = MapRouteOverride(polyline, stops)
            }
            return out.ifEmpty { null }
        }
    }
}

/**
 * A route handed to a `map` block at runtime.
 *
 * `polyline` is Google's encoded-polyline format, which is what every routing service returns and
 * what Mapbox's `path` overlay takes. Supplying it draws the real road geometry; supplying only
 * `stops` draws straight lines between them. Both together is the normal case: the line follows
 * the roads and the pins mark the stops.
 */
data class MapRouteOverride(
    val polyline: String? = null,
    val stops: List<MapRouteStop> = emptyList(),
)

/** One point on a host-supplied route. */
data class MapRouteStop(
    val lat: Double,
    val lng: Double,
    val title: String? = null,
)

// MARK: - Step Hook Config

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
 * step from its backend (backend-interactive elements; e.g. a calendar day tap → backend
 * confirms → update the rendered element without leaving the step). */
data class ElementInteractionResult @JvmOverloads constructor(
    /** Per-block `field_config` patches to merge + re-render. Keyed by blockId -> (key -> value). */
    val fieldConfigPatches: Map<String, Map<String, Any>>? = null,
    /** Patches to merge into the step's `inputValues` (keyed by field_id). */
    val inputValuePatches: Map<String, Any>? = null,
    /**
     * #657 — replacement options, keyed by blockId. The same shape `StepConfigOverride.fieldOptions`
     * takes at render time.
     *
     * Without this the refresh action could not change the thing it exists to change: a select's
     * options live in `field_options`, and `fieldConfigPatches` merges into `field_config` — a
     * different field the renderer never reads for options.
     */
    val fieldOptions: Map<String, List<InputOption>>? = null,
    /** When true, advance to the next step after handling this interaction. */
    val advance: Boolean = false,
    /**
     * Data merged into this step's `{{hook_data.…}}`, shallow, per TOP-LEVEL key:
     * a key named here replaces that key's value whole; a `null` value (Kotlin `null` or
     * `JSONObject.NULL`) REMOVES the key; keys not named are untouched. `null` / `{}` changes nothing.
     *
     * Honoured on the reply of EVERY `onElementInteraction` (refresh, OTP, calendar…). Kept in memory
     * for the flow presentation only — never persisted, sent to the backend or written to responses.
     * Keys are names, not paths: `"a.b"` is a key called `a.b`.
     *
     * The LAST constructor parameter, with a default, so existing Kotlin callers are unchanged; the
     * constructor is `@JvmOverloads` so existing Java callers are too.
     */
    val dataContext: Map<String, Any?>? = null,
) {
    /**
     * The pre-#657 Java constructor `(Map, Map, boolean)`. #657 inserted
     * `fieldOptions` BEFORE `advance`, so no `@JvmOverloads` overload has this parameter order; without
     * this, a Java host written against 1.0.50 stops compiling.
     */
    constructor(
        fieldConfigPatches: Map<String, Map<String, Any>>?,
        inputValuePatches: Map<String, Any>?,
        advance: Boolean,
    ) : this(fieldConfigPatches, inputValuePatches, null, advance, null)

    companion object {
        /** The interaction action a `refresh_step` button fires. */
        const val REFRESH_ACTION: String = "refresh"

        /** The SDK deadline of a `refresh` call (the ceiling for host data). */
        const val REFRESH_TIMEOUT_MS: Long = 8_000L

        /**
         * The least a wrapper bridge must wait for the host's answer to an
         * `onElementInteraction` call with [action], or null when the bridge keeps its own timeout.
         * Each bridge applies it in one line — `max(configured, this ?: 0)` — so no bridge carries its
         * own rule (ADR-001): 8000 for `"refresh"`, null otherwise.
         */
        @JvmStatic
        fun minimumBridgeTimeoutMs(action: String?): Long? =
            if (action == REFRESH_ACTION) REFRESH_TIMEOUT_MS else null

        /**
         * The ONE decoder for a host's `dataContext`, used by every wrapper bridge
         * (one-line forward) and by the core before the value is written, whoever built it.
         *
         * - a non-map input → null;
         * - keys are stringified;
         * - a top-level `null` / `JSONObject.NULL` member is KEPT as Kotlin `null` — the removal marker.
         *   (The bridges' `anyMap` / `asStringMap` helpers drop null members, which would make
         *   "null removes a key" impossible from React Native and Flutter hosts);
         * - nested bridged maps / lists (`Map<*, *>`, `JSONObject`, `JSONArray`, arrays) are normalised
         *   recursively to `Map<String, Any?>` / `List<Any?>`, nested nulls kept;
         * - every other value (string, number, bool) is passed through unchanged — the SDK does not
         *   convert values.
         */
        @JvmStatic
        fun decodeDataContext(raw: Any?): Map<String, Any?>? {
            val top: Map<*, *> = when (raw) {
                is Map<*, *> -> raw
                is org.json.JSONObject -> jsonObjectEntries(raw)
                else -> return null
            }
            val out = LinkedHashMap<String, Any?>(top.size)
            for ((k, v) in top) out[k.toString()] = normaliseDataValue(v)
            return out
        }

        private fun jsonObjectEntries(o: org.json.JSONObject): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            val keys = o.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                m[k] = o.opt(k)
            }
            return m
        }

        // Plain Kotlin shapes first: a JVM unit test (no Robolectric) runs against android.jar's
        // org.json STUBS, whose static state throws — so `JSONObject.NULL` is only touched for a value
        // that is none of the plain shapes (`is` checks never initialise a class).
        private fun normaliseDataValue(v: Any?): Any? = when (v) {
            null -> null
            is String, is Number, is Boolean -> v
            is Map<*, *> -> LinkedHashMap<String, Any?>(v.size).also { m -> for ((k, x) in v) m[k.toString()] = normaliseDataValue(x) }
            is List<*> -> v.map { normaliseDataValue(it) }
            is Array<*> -> v.map { normaliseDataValue(it) }
            is org.json.JSONObject -> normaliseDataValue(jsonObjectEntries(v))
            is org.json.JSONArray -> (0 until v.length()).map { normaliseDataValue(v.opt(it)) }
            else -> if (v == org.json.JSONObject.NULL) null else v
        }
    }
}

/** The merged result of applying an [ElementInteractionResult] to a step's live state. */
data class AppliedInteraction @JvmOverloads constructor(
    /**
     * The tap-time `inputValues` snapshot with `inputValuePatches` merged over it. Kept for
     * compatibility; since an earlier fix the renderers apply [inputValuePatches] onto the LIVE
     * answers instead, so a pick made while the call was in flight is not reverted.
     */
    val inputValues: Map<String, Any>,
    val fieldConfigOverrides: Map<String, Map<String, Any>>,
    /** #657 — per-block replacement options the renderer layers at read time. */
    val fieldOptionsOverrides: Map<String, List<InputOption>> = emptyMap(),
    val advance: Boolean,
    /** The raw `result.inputValuePatches`, passed through. */
    val inputValuePatches: Map<String, Any>? = null,
    /** The raw `result.dataContext`, passed through unchanged. */
    val dataContext: Map<String, Any?>? = null,
)

/** Pure application of an [ElementInteractionResult] to a step's live state. Merges
 * `inputValuePatches` over [inputValues] and exposes per-block `fieldConfigPatches` as overrides the renderer
 * layers at READ TIME (ContentBlock is immutable on iOS — both platforms use a read-time override layer for
 * parity). The caller acts on [AppliedInteraction.advance]. Pure + unit-tested. */
fun applyInteractionResult(result: ElementInteractionResult, inputValues: Map<String, Any>): AppliedInteraction {
    val iv = inputValues.toMutableMap()
    result.inputValuePatches?.let { iv.putAll(it) }
    return AppliedInteraction(
        inputValues = iv,
        fieldConfigOverrides = result.fieldConfigPatches ?: emptyMap(),
        fieldOptionsOverrides = result.fieldOptions ?: emptyMap(),
        advance = result.advance,
        inputValuePatches = result.inputValuePatches,
        dataContext = result.dataContext,
    )
}

/**
 * Optional host override for an onboarding permission step. Returned from
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

    // Async hook called BEFORE advancing from a step.
    suspend fun onBeforeStepAdvance(
        flowId: String,
        fromStepId: String,
        stepIndex: Int,
        stepType: String,
        responses: Map<String, Any>,
        stepData: Map<String, Any>?
    ): StepAdvanceResult = StepAdvanceResult.Proceed

    // Optional hook to modify step config before rendering.
    suspend fun onBeforeStepRender(
        flowId: String,
        stepId: String,
        stepIndex: Int,
        stepType: String,
        responses: Map<String, Any>
    ): StepConfigOverride? = null

    // Async hook fired DURING render when an interactive element acts (calendar day tap,
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

    // Runtime permission hooks for onboarding permission steps.
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
        val activeFlowId = data.str(SURFACE, "onboarding", "active_flow_id")

        val flowsMap = data.objMap(SURFACE, "onboarding", "flows") ?: emptyMap()
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
        // #958 — what a mis-typed field on the FLOW document is reported against; the nested
        // objects below name their own sub-paths.
        val docPath = "flows[$id]"
        val stepsList = map.listOfMaps(SURFACE, docPath, "steps") ?: emptyList()
        // Wrap as ImmutableList so the @Stable
        // OnboardingFlowConfig contract holds (Compose stability inference).
        val steps = stepsList.map { parseStep(it) }.toImmutableList()

        val settingsMap = map.objMap(SURFACE, docPath, "settings") ?: emptyMap()
        // Parse haptic config
        val hapticMap = settingsMap.objMap(SURFACE, docPath, "haptic")
        val haptic = hapticMap?.let { h ->
            val triggersMap = h.objMap(SURFACE, docPath, "triggers")
            HapticConfig(
                enabled = h.bool(SURFACE, "settings.haptic", "enabled") ?: false,
                triggers = triggersMap?.let { t ->
                    HapticTriggers(
                        on_step_advance = t.str(SURFACE, "settings.haptic.triggers", "on_step_advance"),
                        on_button_tap = t.str(SURFACE, "settings.haptic.triggers", "on_button_tap"),
                        on_option_select = t.str(SURFACE, "settings.haptic.triggers", "on_option_select"),
                        on_toggle = t.str(SURFACE, "settings.haptic.triggers", "on_toggle"),
                        on_form_submit = t.str(SURFACE, "settings.haptic.triggers", "on_form_submit"),
                        on_error = t.str(SURFACE, "settings.haptic.triggers", "on_error"),
                        on_success = t.str(SURFACE, "settings.haptic.triggers", "on_success"),
                    )
                } ?: HapticTriggers(),
            )
        }
        // Parse particle effect config
        val particleMap = settingsMap.objMap(SURFACE, docPath, "particle_effect")
        val particleEffect = particleMap?.let { p ->
            ParticleEffect(
                type = p.str(SURFACE, "settings.particle_effect", "type") ?: "confetti",
                trigger = p.str(SURFACE, "settings.particle_effect", "trigger") ?: "on_appear",
                duration_ms = (p.num(SURFACE, "settings.particle_effect", "duration_ms"))?.toInt() ?: 2500,
                intensity = p.str(SURFACE, "settings.particle_effect", "intensity") ?: "medium",
                colors = (p.list(SURFACE, docPath, "colors"))?.filterIsInstance<String>(),
            )
        }
        // Parse back_button_style + content_padding + block_spacing
        val backBtnMap = settingsMap.objMap(SURFACE, docPath, "back_button_style")
        val backButtonStyle = backBtnMap?.let { b ->
            BackButtonStyle(
                icon_size = (b.num(SURFACE, "settings.back_button_style", "icon_size"))?.toDouble(),
                icon_color = b.str(SURFACE, "settings.back_button_style", "icon_color"),
                position = b.str(SURFACE, "settings.back_button_style", "position"),
                icon = b.str(SURFACE, "settings.back_button_style", "icon"),
                close_on_first = b.bool(SURFACE, "settings.back_button_style", "close_on_first"),
            )
        }

        val settings = OnboardingSettings(
            show_progress = settingsMap.bool(SURFACE, "settings", "show_progress") ?: true,
            allow_back = settingsMap.bool(SURFACE, "settings", "allow_back") ?: true,
            skip_to_step = settingsMap.str(SURFACE, "settings", "skip_to_step"),
            haptic = haptic,
            particle_effect = particleEffect,
            progress_color = settingsMap.str(SURFACE, "settings", "progress_color"),
            progress_track_color = settingsMap.str(SURFACE, "settings", "progress_track_color"),
            progress_height = (settingsMap.num(SURFACE, "settings", "progress_height"))?.toDouble(),
            progress_gradient_colors = (settingsMap.list(SURFACE, docPath, "progress_gradient_colors"))?.mapNotNull { it as? String },
            progress_skip_label = settingsMap.str(SURFACE, "settings", "progress_skip_label"),
            progress_flash_color = settingsMap.str(SURFACE, "settings", "progress_flash_color"),
            progress_style = settingsMap.str(SURFACE, "settings", "progress_style"),
            back_button_style = backButtonStyle,
            dismiss_allowed = settingsMap.bool(SURFACE, "settings", "dismiss_allowed"),
            content_padding = (settingsMap.num(SURFACE, "settings", "content_padding"))?.toDouble(),
            block_spacing = (settingsMap.num(SURFACE, "settings", "block_spacing"))?.toDouble(),
        )

        // Top-level flow fields (status / graph_layout / graph_nodes / audience_rules)
        @Suppress("UNCHECKED_CAST")
        val graphLayout = map["graph_layout"] as? Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val graphNodes = map["graph_nodes"] as? Map<String, Any?>

        return OnboardingFlowConfig(
            id = map.str(SURFACE, docPath, "id") ?: id,
            name = map.str(SURFACE, docPath, "name") ?: "",
            version = (map.num(SURFACE, docPath, "version"))?.toInt() ?: 1,
            steps = steps,
            settings = settings,
            status = map.str(SURFACE, docPath, "status"),
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
        // #958 — what a mis-typed field in this step is reported against. The step ID, because that
        // is what an author searches the console for; `?` when the step has no id, which is itself
        // worth seeing in a report.
        val path = "steps[${map["id"] as? String ?: "?"}]"
        val typeStr = map.str(SURFACE, path, "type") ?: "custom"
        val configMap = (map.objMap(SURFACE, path, "config"))
            ?: (map.objMap(SURFACE, path, "layout"))
            ?: emptyMap()

        // ImmutableList wraps for Compose-stable iteration.
        val options = (configMap.list(SURFACE, path, "options"))?.mapNotNull { opt ->
            if (opt is Map<*, *>) {
                val optMap = opt as Map<String, Any>
                QuestionOption(
                    id = optMap.str(SURFACE, path, "id") ?: "",
                    label = optMap.str(SURFACE, path, "label") ?: "",
                    icon = optMap.str(SURFACE, path, "icon"),
                    // Per-option subtitle.
                    subtitle = optMap.str(SURFACE, path, "subtitle"),
                )
            } else null
        }?.toImmutableList()

        val items = (configMap.list(SURFACE, path, "items"))?.mapNotNull { item ->
            if (item is Map<*, *>) {
                val itemMap = item as Map<String, Any>
                ValuePropItem(
                    icon = itemMap.str(SURFACE, path, "icon") ?: "",
                    title = itemMap.str(SURFACE, path, "title") ?: "",
                    subtitle = itemMap.str(SURFACE, path, "subtitle") ?: ""
                )
            } else null
        }?.toImmutableList()

        val selectionModeStr = configMap.str(SURFACE, path, "selection_mode")
        val selectionMode = selectionModeStr?.let { SelectionMode.fromString(it) }

        @Suppress("UNCHECKED_CAST")
        val fields = (configMap.list(SURFACE, path, "fields"))?.mapNotNull { f ->
            if (f is Map<*, *>) {
                val fm = f as Map<String, Any>
                // Wrap form-field options as ImmutableList.
                val fieldOptions = (fm.list(SURFACE, path, "options"))?.mapNotNull { o ->
                    if (o is Map<*, *>) {
                        val om = o as Map<String, Any>
                        FormFieldOption(
                            id = om.str(SURFACE, path, "id") ?: "",
                            label = om.str(SURFACE, path, "label") ?: "",
                            icon = om.str(SURFACE, path, "icon"),
                            value = om["value"]
                        )
                    } else null
                }?.toImmutableList()
                val fieldConfigMap = fm.objMap(SURFACE, path, "config")
                val fieldConfig = fieldConfigMap?.let { fc ->
                    // Same shape of parser drift R36/R37
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
                        max_length = (fc.num(SURFACE, path, "max_length"))?.toInt(),
                        min_length = (fc.num(SURFACE, path, "min_length"))?.toInt(),
                        keyboard_type = fc.str(SURFACE, path, "keyboard_type"),
                        autocapitalize = fc.str(SURFACE, path, "autocapitalize"),
                        autocorrect = fc.bool(SURFACE, path, "autocorrect"),
                        multiline_min_lines = (fc.num(SURFACE, path, "multiline_min_lines"))?.toInt(),
                        min_value = (fc.num(SURFACE, path, "min_value"))?.toDouble(),
                        max_value = (fc.num(SURFACE, path, "max_value"))?.toDouble(),
                        step = (fc.num(SURFACE, path, "step"))?.toDouble(),
                        unit = fc.str(SURFACE, path, "unit"),
                        decimal_places = (fc.num(SURFACE, path, "decimal_places"))?.toInt(),
                        min_date = fc.str(SURFACE, path, "min_date"),
                        max_date = fc.str(SURFACE, path, "max_date"),
                        date_format = fc.str(SURFACE, path, "date_format"),
                        picker_style = fc.str(SURFACE, path, "picker_style"),
                        search_enabled = fc.bool(SURFACE, path, "search_enabled"),
                        multi_select = fc.bool(SURFACE, path, "multi_select"),
                        max_selections = (fc.num(SURFACE, path, "max_selections"))?.toInt(),
                        default_value = fc["default_value"],
                        // Location
                        location_type = fc.str(SURFACE, path, "location_type"),
                        location_bias_country = fc.str(SURFACE, path, "location_bias_country"),
                        location_language = fc.str(SURFACE, path, "location_language"),
                        location_placeholder = fc.str(SURFACE, path, "location_placeholder"),
                        location_min_chars = (fc.num(SURFACE, path, "location_min_chars"))?.toInt(),
                        // show_prefix_icon +
                        // tooltip_text/tooltip_icon parsed from field_config.
                        show_prefix_icon = fc.bool(SURFACE, path, "show_prefix_icon"),
                        tooltip_text = fc.str(SURFACE, path, "tooltip_text"),
                        tooltip_icon = fc.str(SURFACE, path, "tooltip_icon"),
                        // Rating
                        max_stars = (fc.num(SURFACE, path, "max_stars"))?.toInt(),
                        allow_half = fc.bool(SURFACE, path, "allow_half"),
                        star_size = (fc.num(SURFACE, path, "star_size"))?.toInt(),
                        filled_color = fc.str(SURFACE, path, "filled_color"),
                        empty_color = fc.str(SURFACE, path, "empty_color"),
                        // range_slider
                        min_label = fc.str(SURFACE, path, "min_label"),
                        max_label = fc.str(SURFACE, path, "max_label"),
                        // image_picker
                        max_size_mb = (fc.num(SURFACE, path, "max_size_mb"))?.toDouble(),
                        allowed_types = fc.str(SURFACE, path, "allowed_types"),
                        aspect_ratio = fc.str(SURFACE, path, "aspect_ratio"),
                        placeholder_text = fc.str(SURFACE, path, "placeholder_text"),
                        // color picker
                        default_color = fc.str(SURFACE, path, "default_color"),
                        show_opacity = fc.bool(SURFACE, path, "show_opacity"),
                        preset_colors = (fc.list(SURFACE, path, "preset_colors"))?.filterIsInstance<String>(),
                        // url validation
                        validate_format = fc.bool(SURFACE, path, "validate_format"),
                        // multiline_chips
                        max_chips = (fc.num(SURFACE, path, "max_chips"))?.toInt(),
                        suggestions = (fc.list(SURFACE, path, "suggestions"))?.filterIsInstance<String>(),
                        allow_custom = fc.bool(SURFACE, path, "allow_custom"),
                        // signature
                        stroke_color = fc.str(SURFACE, path, "stroke_color"),
                        stroke_width = (fc.num(SURFACE, path, "stroke_width"))?.toDouble(),
                        clear_button_text = fc.str(SURFACE, path, "clear_button_text"),
                    )
                }
                val validationMap = fm.objMap(SURFACE, path, "validation")
                val fieldValidation = validationMap?.let { v ->
                    FormFieldValidation(
                        pattern = v.str(SURFACE, path, "pattern"),
                        pattern_message = v.str(SURFACE, path, "pattern_message")
                    )
                }
                val depMap = fm.objMap(SURFACE, path, "depends_on")
                val dependency = depMap?.let { d ->
                    FormFieldDependency(
                        field_id = d.str(SURFACE, path, "field_id") ?: "",
                        operator = d.str(SURFACE, path, "operator") ?: "not_empty",
                        value = d["value"]
                    )
                }
                // Parse the per-field `style` envelope from the
                // schema's `FormFieldStyleSchema`. Renderer reads it for
                // toggle track tint, OutlinedTextField border colors, and
                // corner radius.
                @Suppress("UNCHECKED_CAST")
                val styleMap = fm.objMap(SURFACE, path, "style")
                val fieldStyle = styleMap?.let { sm ->
                    FormFieldStyle(
                        label_style = sm.objMap(SURFACE, path, "label_style"),
                        input_style = sm.objMap(SURFACE, path, "input_style"),
                        error_style = sm.objMap(SURFACE, path, "error_style"),
                        corner_radius = (sm.num(SURFACE, path, "corner_radius"))?.toDouble(),
                        border_color = sm.str(SURFACE, path, "border_color"),
                        focus_border_color = sm.str(SURFACE, path, "focus_border_color"),
                        background_color = sm.str(SURFACE, path, "background_color"),
                    )
                }
                FormField(
                    // Match iOS OnboardingConfig.swift:508
                    // `self.id = decodeIfPresent(.id) ?? UUID().uuidString`.
                    // Was empty-string fallback → id-less fields shared
                    // `inputValues[fieldId]` collisions in responses payload.
                    id = fm.str(SURFACE, path, "id") ?: java.util.UUID.randomUUID().toString(),
                    type = FormFieldType.fromString(fm.str(SURFACE, path, "type") ?: "text"),
                    label = fm.str(SURFACE, path, "label") ?: "",
                    placeholder = fm.str(SURFACE, path, "placeholder"),
                    required = fm.bool(SURFACE, path, "required") ?: false,
                    validation = fieldValidation,
                    options = fieldOptions,
                    config = fieldConfig,
                    // #596 — the same map, unfiltered, so the select styling keys survive.
                    config_raw = fieldConfigMap,
                    depends_on = dependency,
                    style = fieldStyle,
                )
            } else null
        }?.toImmutableList()

        /*
         * Parse content blocks: the `config`/`layout` copy first, then the step ROOT.
         *
         * 🔴 The fallback is on EMPTY, not just absent — which is what iOS has always done
         * (`OnboardingConfig.swift`: `if (decoded.content_blocks ?? []).isEmpty { … step root }`)
         * and what this did not. `?:` only fires on null, so an EMPTY `layout.content_blocks` was
         * taken as the answer and the root was never read: the step renders blank on Android and
         * correctly on iOS, from one published document.
         *
         * It matters now because the console is consolidating onto the root copy (#772). A writer
         * that leaves `content_blocks: []` behind instead of deleting the key would have blanked
         * every graph-authored step on Android only — the exact divergence a shared fixture exists
         * to catch, and the reason this is fixed BEFORE the console stops writing the duplicate.
         */
        @Suppress("UNCHECKED_CAST")
        val publishedContentBlocks = (configMap.list(SURFACE, path, "content_blocks"))?.takeIf { it.isNotEmpty() }
            ?: (map.list(SURFACE, path, "content_blocks"))
        // Capture the raw ladder ONCE: a deep copy (the maps are shared with the
        // config cache), every id-less block at any depth stamped `<stepId>/<index path>`, authored
        // SDK-internal markers stripped. The typed blocks below are decoded from THIS copy, so
        // authored readers (secret ids, prefetch) and the resolved blocks agree on every id.
        val stampedRaw = publishedContentBlocks?.let { captureRawContentBlocks(it, map.str(SURFACE, path, "id") ?: "") }
        val rawContentBlocks = stampedRaw?.blocks
        val contentBlocks = rawContentBlocks?.mapNotNull { b ->
            if (b is Map<*, *>) {
                val bm = b as Map<String, Any>
                // Body extracted into `decodeContentBlock`
                // (defined alongside `parseChildBlocks` further below) so
                // nested children inside row/stack containers (parsed by
                // `parseChildBlocks`) take the SAME ~150-field decode path.
                // Until an earlier fix, parseChildBlocks only populated ~25 fields,
                // silently shadowing every R3-R39 renderer fix for content
                // nested inside a container. iOS gets full parity
                // automatically because ContentBlock is Codable; Android
                // emulates that via this shared helper.
                decodeContentBlock(bm, "$path.content_blocks")
            } else null
        }?.toImmutableList()

        // Parse background config
        @Suppress("UNCHECKED_CAST")
        val bgMap = configMap.objMap(SURFACE, path, "background")
        val background = bgMap?.let { bg ->
            val gradMap = bg.objMap(SURFACE, path, "gradient")
            val gradient = gradMap?.let { g ->
                val stopsList = (g.list(SURFACE, path, "stops"))?.mapNotNull { s ->
                    if (s is Map<*, *>) {
                        ai.appdna.sdk.core.GradientStopConfig(
                            color = (s as Map<String, Any>)["color"] as? String ?: "#000000",
                            position = ((s as Map<String, Any>)["position"] as? Number)?.toDouble() ?: 0.0,
                        )
                    } else null
                }
                ai.appdna.sdk.core.GradientConfig(type = g.str(SURFACE, path, "type"), angle = (g.num(SURFACE, path, "angle"))?.toDouble(), stops = stopsList)
            }
            ai.appdna.sdk.core.BackgroundStyleConfig(
                type = bg.str(SURFACE, path, "type"), color = bg.str(SURFACE, path, "color"),
                gradient = gradient, image_url = bg.str(SURFACE, path, "image_url"),
                image_fit = bg.str(SURFACE, path, "image_fit"), overlay = bg.str(SURFACE, path, "overlay"),
                // R15 + R35 fixes shadowed because parser
                // never read these. overlay_opacity drives the dim layer over
                // image/lottie/rive backgrounds; lottie_url + animation_loop
                // wire the full-screen Lottie branch; rive_url wires the
                // full-screen Rive branch. Without these parsed, the
                // canonical iOS-style background payload silently rendered as
                // theme default.
                overlay_opacity = (bg.num(SURFACE, path, "overlay_opacity"))?.toDouble(),
                lottie_url = bg.str(SURFACE, path, "lottie_url"),
                animation_loop = bg.bool(SURFACE, path, "animation_loop"),
                rive_url = bg.str(SURFACE, path, "rive_url"),
            )
        }

        // Parse animation config
        @Suppress("UNCHECKED_CAST")
        val animMap = configMap.objMap(SURFACE, path, "animation")
        val animConfig = animMap?.let { a ->
            ai.appdna.sdk.core.AnimationConfig(
                entry_animation = a.str(SURFACE, path, "entry_animation"),
                entry_duration_ms = (a.num(SURFACE, path, "entry_duration_ms"))?.toInt(),
                section_stagger = a.str(SURFACE, path, "section_stagger"),
                section_stagger_delay_ms = (a.num(SURFACE, path, "section_stagger_delay_ms"))?.toInt(),
                cta_animation = a.str(SURFACE, path, "cta_animation"),
                plan_selection_animation = a.str(SURFACE, path, "plan_selection_animation"),
                dismiss_animation = a.str(SURFACE, path, "dismiss_animation"),
            )
        }

        // Parse localizations
        @Suppress("UNCHECKED_CAST")
        val localizations = configMap["localizations"] as? Map<String, Map<String, String>>

        // Parse step-level text_style and element_style
        @Suppress("UNCHECKED_CAST")
        val textStyle = (configMap.objMap(SURFACE, path, "text_style"))?.let { parseTextStyleConfig(it) }
        @Suppress("UNCHECKED_CAST")
        val elementStyle = (configMap.objMap(SURFACE, path, "element_style"))?.let { parseElementStyleConfig(it) }

        val config = StepConfig(
            title = configMap.str(SURFACE, path, "title"),
            subtitle = configMap.str(SURFACE, path, "subtitle"),
            image_url = configMap.str(SURFACE, path, "image_url"),
            cta_text = configMap.str(SURFACE, path, "cta_text"),
            skip_enabled = configMap.bool(SURFACE, path, "skip_enabled"),
            options = options,
            selection_mode = selectionMode,
            items = items,
            layout = configMap.objMap(SURFACE, path, "layout"),
            fields = fields,
            validation_mode = configMap.str(SURFACE, path, "validation_mode"),
            // `field_defaults` parser drop. Data class
            // declares it (line 193); FormStepComposable.kt reads it via
            // `config.field_defaults?.forEach`; OnboardingActivity merges
            // it with override at line 954. But the JSON parser never
            // assigned it to the StepConfig constructor — only the
            // Override path populated it. Console-authored
            // `field_defaults` (vs. hook-supplied) was dropped on parse,
            // so editor pre-fills never reached the form composable.
            field_defaults = configMap.objMap(SURFACE, path, "field_defaults"),
            content_blocks = contentBlocks,
            layout_variant = configMap.str(SURFACE, path, "layout_variant"),
            background = background,
            text_style = textStyle,
            element_style = elementStyle,
            animation = animConfig,
            localizations = localizations,
            default_locale = configMap.str(SURFACE, path, "default_locale"),
            chat_config = parseChatConfig(configMap["chat_config"]),
            // Decoded from the SAME top level as content_blocks (console siblings),
            // NOT from the inner `layout` sub-map.
            permission_type = configMap.str(SURFACE, path, "permission_type"),
            show_settings_fallback_on_denied = configMap.bool(SURFACE, path, "show_settings_fallback_on_denied"),
            settings_fallback_label = configMap.str(SURFACE, path, "settings_fallback_label"),
        )

        // Parse hook config
        @Suppress("UNCHECKED_CAST")
        val hookMap = map.objMap(SURFACE, path, "hook")
        val hook = hookMap?.let { h ->
            val enabled = h.bool(SURFACE, path, "enabled") ?: false
            val webhookUrl = h.str(SURFACE, path, "webhook_url") ?: ""
            if (enabled && webhookUrl.isNotEmpty()) {
                StepHookConfig(
                    enabled = true,
                    webhook_url = webhookUrl,
                    timeout_ms = (h.num(SURFACE, path, "timeout_ms"))?.toInt() ?: 10000,
                    loading_text = h.str(SURFACE, path, "loading_text"),
                    error_text = h.str(SURFACE, path, "error_text"),
                    retry_count = (h.num(SURFACE, path, "retry_count"))?.toInt() ?: 0,
                    headers = h["headers"] as? Map<String, String>
                )
            } else null
        }

        // Parse next_step_rules: also pull `conditions` array + `logic`
        // so the iOS multi-condition shape (`OnboardingConfig.swift:122-127`) round-trips.
        val nextStepRules = parseNextStepRulesList(map["next_step_rules"])
        // Restart attempt 2 F1: also parse layout
        // rules under `step.config.next_step_rules` (the editor's Logic-panel
        // path). These get folded into the StepConfig so iOS-style preference
        // logic (layout rules > step rules when richer) can run client-side.
        val configLayoutRules = parseNextStepRulesList(configMap["next_step_rules"])
        // Restart attempt 2 F2: per-step
        // progress-color override read from configMap.
        val progressColor = configMap.str(SURFACE, path, "progress_color")

        val configWithLayoutRules = if (configLayoutRules != null || progressColor != null) {
            config.copy(next_step_rules = configLayoutRules, progress_color = progressColor)
        } else config

        return OnboardingStep(
            id = map.str(SURFACE, path, "id") ?: "",
            type = OnboardingStep.StepType.fromString(typeStr),
            config = configWithLayoutRules,
            hook = hook,
            // The console writes hide_progress/hide_back INSIDE the step's layout object
            // (`configMap`), not at the step root — read layout first with a root fallback
            // (mirrors iOS OnboardingConfig.swift LayoutFlagKeys decode).
            hide_progress = map.bool(SURFACE, path, "hide_progress") ?: configMap.bool(SURFACE, path, "hide_progress"),
            hide_back = map.bool(SURFACE, path, "hide_back") ?: configMap.bool(SURFACE, path, "hide_back"),
            // Same root-then-layout ladder as the two flags above (#896).
            request_store_review = map.bool(SURFACE, path, "request_store_review")
                ?: configMap.bool(SURFACE, path, "request_store_review"),
            /*
             * #921 — the SAME root-then-layout ladder, and here it is the ONLY path that works for
             * a graph-authored step: `updateNodeData` writes every Logic-panel flag into
             * `data.stepConfig`, which the console ships as the step's `layout` (= `configMap`).
             * The builder also hoists these two to the step root on save, so both are live.
             *
             * `as? Number`, not `as? Double`: Firestore hands an integral JSON number back as a
             * Long, so `3` (not `3.0`) would have cast to null and the timer would never start —
             * the same class of miss as the `priceCents` Int/Double cast in the paywall DTO.
             */
            auto_advance_seconds = (map.num(SURFACE, path, "auto_advance_seconds"))?.toDouble()
                ?: (configMap.num(SURFACE, path, "auto_advance_seconds"))?.toDouble(),
            wait_for_host = map.bool(SURFACE, path, "wait_for_host")
                ?: configMap.bool(SURFACE, path, "wait_for_host"),
            next_step_rules = nextStepRules,
            name = map.str(SURFACE, path, "name"),
            rawContentBlocks = stampedRaw,
        )
    }

    /** See [OnboardingStep.rawContentBlocks]. */
    internal fun captureRawContentBlocks(published: List<*>, stepId: String): RawContentBlocks =
        RawContentBlocks(
            published.mapIndexed { i, b ->
                if (b is Map<*, *>) {
                    HostDataResolver.stampBlockIds(b, stepId, i).also { HostDataResolver.stripMarkers(it) }
                } else b
            },
        )

    /**
     * Decode one RESOLVED raw block with the SAME decoder the published step uses. JSON
     * nulls are dropped first (the decoder is all `as?` casts over non-null maps).
     */
    @Suppress("UNCHECKED_CAST")
    internal fun decodeRawBlock(map: Map<String, Any?>): ContentBlock =
        decodeContentBlock(stripNulls(map) as Map<String, Any>)

    /** Host / interaction option maps through the one option parser. */
    internal fun decodeRawOptions(list: List<*>): List<InputOption> = parseInputOptionList(list.map { stripNulls(it) })

    private fun stripNulls(v: Any?): Any? = when (v) {
        is Map<*, *> -> {
            val out = LinkedHashMap<String, Any>()
            for ((k, value) in v) if (value != null) out[k.toString()] = stripNulls(value)!!
            out
        }
        is List<*> -> v.map { stripNulls(it) }
        else -> v
    }

    /**
     * Shared parser for `next_step_rules` arrays — used at both the step level
     * and the step.config (layout) level so the editor's Logic panel can write
     * to either path. Mirrors iOS `OnboardingRenderer.swift:761-766`.
     *
     * Returns ImmutableList so step / step.config
     * `next_step_rules` fields satisfy Compose's stability inference.
     */
    @Suppress("UNCHECKED_CAST")
    private fun parseNextStepRulesList(raw: Any?): ImmutableList<NextStepRule>? {
        return (raw as? List<*>)?.mapNotNull { r ->
            if (r is Map<*, *>) {
                val rm = r as Map<String, Any>
                val conditionsRaw = rm.list(SURFACE, "next_step_rules", "conditions")
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
                    logic = rm.str(SURFACE, "next_step_rules", "logic"),
                    target_step_id = rm.str(SURFACE, "next_step_rules", "target_step_id") ?: ""
                )
            } else null
        }?.toImmutableList()
    }

    /**
     * Parse a raw map from Firestore into a TextStyleConfig.
     * Used for per-block style overrides on ContentBlock.style.
     */
    @Suppress("UNCHECKED_CAST")
    internal fun parseTextStyleConfig(map: Map<String, Any>): TextStyleConfig {
        return TextStyleConfig(
            font_family = map.str(SURFACE, "style.text_style", "font_family"),
            font_size = (map.num(SURFACE, "style.text_style", "font_size"))?.toDouble(),
            font_weight = (map.num(SURFACE, "style.text_style", "font_weight"))?.toInt(),
            color = map.str(SURFACE, "style.text_style", "color"),
            alignment = map.str(SURFACE, "style.text_style", "alignment"),
            line_height = (map.num(SURFACE, "style.text_style", "line_height"))?.toDouble(),
            letter_spacing = (map.num(SURFACE, "style.text_style", "letter_spacing"))?.toDouble(),
            opacity = (map.num(SURFACE, "style.text_style", "opacity"))?.toDouble(),
        )
    }

    /**
     * Parse a raw map into an ElementStyleConfig.
     * Used for step-level element_style.
     */
    @Suppress("UNCHECKED_CAST")
    internal fun parseElementStyleConfig(map: Map<String, Any>): ai.appdna.sdk.core.ElementStyleConfig {
        val bgMap = map.objMap(SURFACE, "style.elements", "background")
        val bg = bgMap?.let {
            val gradMap = it.objMap(SURFACE, "style.elements", "gradient")
            val grad = gradMap?.let { g ->
                val stops = g.listOfMaps(SURFACE, "style.elements", "stops")?.map { s ->
                    ai.appdna.sdk.core.GradientStopConfig(
                        color = s.str(SURFACE, "style.elements", "color") ?: "#000000",
                        position = (s.num(SURFACE, "style.elements", "position"))?.toDouble() ?: 0.0,
                    )
                }
                ai.appdna.sdk.core.GradientConfig(type = g.str(SURFACE, "style.elements", "type"), angle = (g.num(SURFACE, "style.elements", "angle"))?.toDouble(), stops = stops)
            }
            ai.appdna.sdk.core.BackgroundStyleConfig(
                type = it.str(SURFACE, "style.elements", "type"), color = it.str(SURFACE, "style.elements", "color"),
                gradient = grad, image_url = it.str(SURFACE, "style.elements", "image_url"),
                image_fit = it.str(SURFACE, "style.elements", "image_fit"), overlay = it.str(SURFACE, "style.elements", "overlay"),
            )
        }
        val borderMap = map.objMap(SURFACE, "style.elements", "border")
        val border = borderMap?.let {
            ai.appdna.sdk.core.BorderStyleConfig(
                width = (it.num(SURFACE, "style.elements", "width"))?.toDouble(), color = it.str(SURFACE, "style.elements", "color"),
                style = it.str(SURFACE, "style.elements", "style"), radius = (it.num(SURFACE, "style.elements", "radius"))?.toDouble(),
                radius_top_left = (it.num(SURFACE, "style.elements", "radius_top_left"))?.toDouble(),
                radius_top_right = (it.num(SURFACE, "style.elements", "radius_top_right"))?.toDouble(),
                radius_bottom_left = (it.num(SURFACE, "style.elements", "radius_bottom_left"))?.toDouble(),
                radius_bottom_right = (it.num(SURFACE, "style.elements", "radius_bottom_right"))?.toDouble(),
            )
        }
        val shadowMap = map["shadow"] as? Map<String, Any>
        val shadow = shadowMap?.let {
            ai.appdna.sdk.core.ShadowStyleConfig(
                x = (it.num(SURFACE, "style.elements", "x"))?.toDouble(), y = (it.num(SURFACE, "style.elements", "y"))?.toDouble(),
                blur = (it.num(SURFACE, "style.elements", "blur"))?.toDouble(), spread = (it.num(SURFACE, "style.elements", "spread"))?.toDouble(),
                color = it.str(SURFACE, "style.elements", "color"),
            )
        }
        val paddingMap = map["padding"] as? Map<String, Any>
        val padding = paddingMap?.let {
            ai.appdna.sdk.core.SpacingConfig(
                top = (it.num(SURFACE, "style.elements", "top"))?.toDouble(), right = (it.num(SURFACE, "style.elements", "right"))?.toDouble(),
                bottom = (it.num(SURFACE, "style.elements", "bottom"))?.toDouble(), left = (it.num(SURFACE, "style.elements", "left"))?.toDouble(),
            )
        }
        val tsMap = map["text_style"] as? Map<String, Any>
        val textStyle = tsMap?.let { parseTextStyleConfig(it) }
        return ai.appdna.sdk.core.ElementStyleConfig(
            background = bg, border = border, shadow = shadow, padding = padding,
            corner_radius = (map.num(SURFACE, "style.elements", "corner_radius"))?.toDouble(),
            opacity = (map.num(SURFACE, "style.elements", "opacity"))?.toDouble(),
            text_style = textStyle,
        )
    }

    /**
     * Parse a raw Firestore map into a BlockStyle.
     * Used for per-block design tokens: background, border, shadow, padding, margin, opacity.
     */
    @Suppress("UNCHECKED_CAST")
    internal fun parseBlockStyle(map: Map<String, Any>): BlockStyle {
        val shadowMap = map["shadow"] as? Map<String, Any>
        // Drop legacy parser defaults that walked back the earlier
        // iOS-parity correction. R26 set data-class defaults to color =
        // "transparent" + y/blur/spread = 0 to match iOS's `.shadow(color:
        // ?? "transparent", radius: blur ?? 0)/2` (ContentBlockTypes.swift:99-104).
        // The parser still hardcoded `?: 2.0`, `?: 8.0`, `?: "#1A000000"`,
        // so any authored partial-shadow JSON rendered as a 10%-black 8px
        // y=2 shadow on Android while iOS rendered it transparent — same
        // payload, two different shadows.
        val shadow = shadowMap?.let { s ->
            BlockShadowStyle(
                x = (s.num(SURFACE, "block_style", "x"))?.toDouble() ?: 0.0,
                y = (s.num(SURFACE, "block_style", "y"))?.toDouble() ?: 0.0,
                blur = (s.num(SURFACE, "block_style", "blur"))?.toDouble() ?: 0.0,
                spread = (s.num(SURFACE, "block_style", "spread"))?.toDouble() ?: 0.0,
                color = s.str(SURFACE, "block_style", "color") ?: "transparent",
            )
        }
        val gradientMap = map["background_gradient"] as? Map<String, Any>
        // Same R26 walk-back fix for BlockGradientStyle.
        // R26 set defaults to angle=0/start=#000000/end=#FFFFFF to match iOS
        // (ContentBlockTypes.swift:67-71,121). Parser was overriding with
        // legacy AppDNA brand indigo→purple at 135° → identical JSON
        // rendered black-to-white horizontal on iOS but indigo→purple
        // diagonal on Android.
        val gradient = gradientMap?.let { g ->
            BlockGradientStyle(
                angle = (g.num(SURFACE, "block_style", "angle"))?.toDouble() ?: 0.0,
                start = g.str(SURFACE, "block_style", "start") ?: "#000000",
                end = g.str(SURFACE, "block_style", "end") ?: "#FFFFFF",
            )
        }
        return BlockStyle(
            background_color = map.str(SURFACE, "block_style", "background_color"),
            background_gradient = gradient,
            border_color = map.str(SURFACE, "block_style", "border_color"),
            border_width = (map.num(SURFACE, "block_style", "border_width"))?.toDouble(),
            border_style = map.str(SURFACE, "block_style", "border_style"),
            border_radius = (map.num(SURFACE, "block_style", "border_radius"))?.toDouble(),
            shadow = shadow,
            padding_top = (map.num(SURFACE, "block_style", "padding_top"))?.toDouble(),
            padding_right = (map.num(SURFACE, "block_style", "padding_right"))?.toDouble(),
            padding_bottom = (map.num(SURFACE, "block_style", "padding_bottom"))?.toDouble(),
            padding_left = (map.num(SURFACE, "block_style", "padding_left"))?.toDouble(),
            margin_top = (map.num(SURFACE, "block_style", "margin_top"))?.toDouble(),
            margin_bottom = (map.num(SURFACE, "block_style", "margin_bottom"))?.toDouble(),
            margin_left = (map.num(SURFACE, "block_style", "margin_left"))?.toDouble(),
            margin_right = (map.num(SURFACE, "block_style", "margin_right"))?.toDouble(),
            opacity = (map.num(SURFACE, "block_style", "opacity"))?.toDouble(),
        )
    }

    /**
     * Decode a single Firestore content-block Map into a [ContentBlock].
     *
     * Extracted from the inline `ContentBlock(...)`
     * constructor invocation that used to live mid-`parseStep`. Both
     * top-level `content_blocks` (in `parseStep`) AND nested children
     * inside row/stack containers (in `parseChildBlocks`) call into this
     * shared helper so every R3-R39 renderer fix takes effect at every
     * tree level. Until an earlier fix the inline body decoded ~150 fields while
     * `parseChildBlocks` only populated ~25, silently shadowing all
     * nested-content fixes (lottie autoplay, gauge metrics, pricing
     * cards, etc.). iOS achieves the same outcome automatically because
     * `ContentBlock` is `Codable`; Android emulates that via this helper.
     */
    @Suppress("UNCHECKED_CAST")
    private fun decodeContentBlock(bm: Map<String, Any>, parentPath: String = "content_blocks"): ContentBlock {
        // #958 — `steps[step_1].content_blocks[hero]`, so a report names the screen AND the block.
        // Defaulted rather than required: a nested child is decoded through the same function and
        // its caller does not always know the step.
        val path = "$parentPath[${bm["id"] as? String ?: "?"}]"
        return ContentBlock(
            // Match iOS ContentBlockTypes.swift:1208
            // `self.id = decodeIfPresent(.id) ?? UUID().uuidString`. Was
            // empty-string fallback so two id-less toggle blocks shared the
            // same `toggleValues[block.id]` map key (last write wins) and
            // `loc?.invoke("block.${block.id}.text", …)` resolved identical
            // localization keys across every id-less block. Affects nested
            // row/stack children too via R40 shared decodeContentBlock path.
            id = bm.str(SURFACE, path, "id") ?: java.util.UUID.randomUUID().toString(),
            type = bm.str(SURFACE, path, "type") ?: "text",
            text = bm.str(SURFACE, path, "text"),
            level = (bm.num(SURFACE, path, "level"))?.toInt(),
            image_url = bm.str(SURFACE, path, "image_url"),
            alt = bm.str(SURFACE, path, "alt"),
            corner_radius = (bm.num(SURFACE, path, "corner_radius"))?.toDouble(),
            height = (bm.num(SURFACE, path, "height"))?.toDouble(),
            variant = bm.str(SURFACE, path, "variant"),
            action = bm.str(SURFACE, path, "action"),
            action_value = bm.str(SURFACE, path, "action_value"),
            bg_color = bm.str(SURFACE, path, "bg_color"),
            text_color = bm.str(SURFACE, path, "text_color"),
            button_corner_radius = (bm.num(SURFACE, path, "button_corner_radius"))?.toDouble(),
            // Device QA (s20/s22) — sound_button remote audio clip.
            spacer_height = (bm.num(SURFACE, path, "spacer_height"))?.toDouble(),
            items = (bm.list(SURFACE, path, "items"))?.filterIsInstance<String>()?.toImmutableList(),
            list_style = bm.str(SURFACE, path, "list_style"),
            divider_color = bm.str(SURFACE, path, "divider_color"),
            divider_thickness = (bm.num(SURFACE, path, "divider_thickness"))?.toDouble(),
            divider_margin_y = (bm.num(SURFACE, path, "divider_margin_y"))?.toDouble(),
            badge_text = bm.str(SURFACE, path, "badge_text"),
            badge_bg_color = bm.str(SURFACE, path, "badge_bg_color"),
            badge_text_color = bm.str(SURFACE, path, "badge_text_color"),
            badge_corner_radius = (bm.num(SURFACE, path, "badge_corner_radius"))?.toDouble(),
            icon_emoji = bm.str(SURFACE, path, "icon_emoji"),
            icon_size = (bm.num(SURFACE, path, "icon_size"))?.toDouble(),
            icon_alignment = bm.str(SURFACE, path, "icon_alignment"),
            toggle_label = bm.str(SURFACE, path, "toggle_label"),
            toggle_description = bm.str(SURFACE, path, "toggle_description"),
            toggle_default = bm.bool(SURFACE, path, "toggle_default"),
            video_thumbnail_url = bm.str(SURFACE, path, "video_thumbnail_url"),
            // Per-block text style + video source fields
            style = (bm.objMap(SURFACE, path, "style"))?.let { parseTextStyleConfig(it) },
            video_url = bm.str(SURFACE, path, "video_url"),
            video_height = (bm.num(SURFACE, path, "video_height"))?.toDouble(),
            video_corner_radius = (bm.num(SURFACE, path, "video_corner_radius"))?.toDouble(),
            // Rich media fields
            lottie_url = bm.str(SURFACE, path, "lottie_url"),
            lottie_json = bm.objMap(SURFACE, path, "lottie_json"),
            lottie_autoplay = bm.bool(SURFACE, path, "lottie_autoplay"),
            lottie_loop = bm.bool(SURFACE, path, "lottie_loop"),
            lottie_speed = (bm.num(SURFACE, path, "lottie_speed"))?.toFloat(),
            rive_url = bm.str(SURFACE, path, "rive_url"),
            rive_artboard = bm.str(SURFACE, path, "rive_artboard"),
            rive_state_machine = bm.str(SURFACE, path, "rive_state_machine"),
            icon_ref = bm["icon_ref"] ?: bm["icon"],
            video_autoplay = bm.bool(SURFACE, path, "video_autoplay"),
            video_loop = bm.bool(SURFACE, path, "video_loop"),
            video_muted = bm.bool(SURFACE, path, "video_muted"),
            // Per-block style design tokens
            block_style = (bm.objMap(SURFACE, path, "block_style"))?.let { parseBlockStyle(it) },
            // 2D positioning
            vertical_align = bm.str(SURFACE, path, "vertical_align"),
            horizontal_align = bm.str(SURFACE, path, "horizontal_align"),
            vertical_offset = (bm.num(SURFACE, path, "vertical_offset"))?.toDouble(),
            horizontal_offset = (bm.num(SURFACE, path, "horizontal_offset"))?.toDouble(),
            // Three-zone layout opt-in.
            zone = bm.str(SURFACE, path, "zone"),
            // page_indicator fields
            dot_count = (bm.num(SURFACE, path, "dot_count"))?.toInt(),
            active_index = (bm.num(SURFACE, path, "active_index"))?.toInt(),
            active_color = bm.str(SURFACE, path, "active_color"),
            inactive_color = bm.str(SURFACE, path, "inactive_color"),
            dot_size = (bm.num(SURFACE, path, "dot_size"))?.toDouble(),
            dot_spacing = (bm.num(SURFACE, path, "dot_spacing"))?.toDouble(),
            active_dot_width = (bm.num(SURFACE, path, "active_dot_width"))?.toDouble(),
            // social_login fields
            providers = (bm.list(SURFACE, path, "providers"))?.mapNotNull { p ->
                if (p is Map<*, *>) {
                    @Suppress("UNCHECKED_CAST")
                    val pm = p as Map<String, Any>
                    SocialProvider(
                        type = pm.str(SURFACE, path, "type") ?: "",
                        label = pm.str(SURFACE, path, "label"),
                        enabled = pm.bool(SURFACE, path, "enabled") ?: true,
                        // OB-2 — per-provider color/style overrides.
                        bg_color = pm.str(SURFACE, path, "bg_color"),
                        text_color = pm.str(SURFACE, path, "text_color"),
                        border_color = pm.str(SURFACE, path, "border_color"),
                        border_width = (pm.num(SURFACE, path, "border_width"))?.toFloat(),
                        corner_radius = (pm.num(SURFACE, path, "corner_radius"))?.toFloat(),
                        icon_style = pm.str(SURFACE, path, "icon_style"),
                        icon_url = pm.str(SURFACE, path, "icon_url"),
                        // #560 — Android maps every provider key by hand, so a field the console
                        // writes and this line omits is null forever and the renderer silently uses
                        // its default. That is the exact class this file's own comments record.
                        font_size = (pm.num(SURFACE, path, "font_size"))?.toFloat(),
                    )
                } else null
            }?.toImmutableList(),
            button_style = bm.str(SURFACE, path, "button_style"),
            button_height = (bm.num(SURFACE, path, "button_height"))?.toDouble(),
            spacing = (bm.num(SURFACE, path, "spacing"))?.toDouble(),
            show_divider = bm.bool(SURFACE, path, "show_divider"),
            divider_text = bm.str(SURFACE, path, "divider_text"),
            // Social-Login styling v2
            // countdown_timer fields
            target_type = bm.str(SURFACE, path, "target_type"),
            duration_seconds = (bm.num(SURFACE, path, "duration_seconds"))?.toInt(),
            target_datetime = bm.str(SURFACE, path, "target_datetime"),
            show_days = bm.bool(SURFACE, path, "show_days"),
            show_hours = bm.bool(SURFACE, path, "show_hours"),
            show_minutes = bm.bool(SURFACE, path, "show_minutes"),
            show_seconds = bm.bool(SURFACE, path, "show_seconds"),
            labels = (bm.objMap(SURFACE, path, "labels"))?.let { lm ->
                CountdownLabels(
                    days = lm.str(SURFACE, path, "days"),
                    hours = lm.str(SURFACE, path, "hours"),
                    minutes = lm.str(SURFACE, path, "minutes"),
                    seconds = lm.str(SURFACE, path, "seconds"),
                )
            },
            on_expire_action = bm.str(SURFACE, path, "on_expire_action"),
            expired_text = bm.str(SURFACE, path, "expired_text"),
            accent_color = bm.str(SURFACE, path, "accent_color"),
            font_size = (bm.num(SURFACE, path, "font_size"))?.toDouble(),
            alignment = bm.str(SURFACE, path, "alignment"),
            // Rating fields
            field_id = bm.str(SURFACE, path, "field_id"),
            max_stars = (bm.num(SURFACE, path, "max_stars"))?.toInt(),
            default_value = (bm.num(SURFACE, path, "default_value"))?.toDouble(),
            star_size = (bm.num(SURFACE, path, "star_size"))?.toDouble(),
            active_rating_color = bm.str(SURFACE, path, "active_rating_color") ?: bm.str(SURFACE, path, "filled_color") ?: bm.str(SURFACE, path, "active_color"),
            inactive_rating_color = bm.str(SURFACE, path, "inactive_rating_color") ?: bm.str(SURFACE, path, "empty_color") ?: bm.str(SURFACE, path, "inactive_color"),
            allow_half = bm.bool(SURFACE, path, "allow_half"),
            label = bm.str(SURFACE, path, "label"),
            // rich_text fields
            content = bm.str(SURFACE, path, "content"),
            base_style = (bm.objMap(SURFACE, path, "base_style"))?.let { parseTextStyleConfig(it) },
            link_color = bm.str(SURFACE, path, "link_color"),
            max_lines = (bm.num(SURFACE, path, "max_lines"))?.toInt(),
            // progress_bar fields
            segment_count = (bm.num(SURFACE, path, "segment_count"))?.toInt() ?: (bm.num(SURFACE, path, "total_segments"))?.toInt(),
            active_segments = (bm.num(SURFACE, path, "active_segments"))?.toInt() ?: (bm.num(SURFACE, path, "filled_segments"))?.toInt(),
            fill_color = bm.str(SURFACE, path, "fill_color"),
            track_color = bm.str(SURFACE, path, "track_color"),
            segment_gap = (bm.num(SURFACE, path, "segment_gap"))?.toDouble(),
            show_label = bm.bool(SURFACE, path, "show_label"),
            label_style = (bm.objMap(SURFACE, path, "label_style"))?.let { parseTextStyleConfig(it) },
            // Timeline fields
            timeline_items = (bm.list(SURFACE, path, "timeline_items")
                ?: bm.list(SURFACE, path, "items"))?.mapNotNull { ti ->
                if (ti is Map<*, *>) {
                    @Suppress("UNCHECKED_CAST")
                    val tm = ti as Map<String, Any>
                    // Only parse as TimelineItem if it has a title (to distinguish from string items)
                    val title = tm.str(SURFACE, path, "title") ?: return@mapNotNull null
                    TimelineItem(
                        id = tm.str(SURFACE, path, "id") ?: "",
                        title = title,
                        subtitle = tm.str(SURFACE, path, "subtitle"),
                        icon = tm.str(SURFACE, path, "icon"),
                        // Drop `?: "upcoming"` parser
                        // walk-back of R18. R18 made `status` nullable
                        // on the data class so missing-status timeline
                        // items render in solid title color (not greyed).
                        // Parser hardcoded `?: "upcoming"`, so the
                        // renderer's `if (item.status == "upcoming")`
                        // greyed every missing-status title regardless.
                        status = tm.str(SURFACE, path, "status"),
                    )
                } else null
            }?.toImmutableList(),
            line_color = bm.str(SURFACE, path, "line_color"),
            completed_color = bm.str(SURFACE, path, "completed_color"),
            current_color = bm.str(SURFACE, path, "current_color"),
            upcoming_color = bm.str(SURFACE, path, "upcoming_color"),
            show_line = bm.bool(SURFACE, path, "show_line"),
            compact = bm.bool(SURFACE, path, "compact"),
            title_style = (bm.objMap(SURFACE, path, "title_style"))?.let { parseTextStyleConfig(it) },
            subtitle_style = (bm.objMap(SURFACE, path, "subtitle_style"))?.let { parseTextStyleConfig(it) },
            // animated_loading fields
            loading_items = (bm.list(SURFACE, path, "loading_items")
                ?: if (bm["type"] == "animated_loading") bm.list(SURFACE, path, "items") else null)?.mapNotNull { li ->
                if (li is Map<*, *>) {
                    @Suppress("UNCHECKED_CAST")
                    val lm = li as Map<String, Any>
                    LoadingItem(
                        label = lm.str(SURFACE, path, "label") ?: "",
                        duration_ms = (lm.num(SURFACE, path, "duration_ms"))?.toInt() ?: 1000,
                        icon = lm.str(SURFACE, path, "icon"),
                        // Orbit-variant per-item fields.
                        // Until an earlier fix these were declared on LoadingItem
                        // but never read from JSON.
                        icon_url = lm.str(SURFACE, path, "icon_url"),
                        icon_bg_color = lm.str(SURFACE, path, "icon_bg_color"),
                        icon_size = (lm.num(SURFACE, path, "icon_size"))?.toFloat(),
                        icon_orbit_angle = (lm.num(SURFACE, path, "icon_orbit_angle"))?.toFloat(),
                    )
                } else null
            }?.toImmutableList(),
            progress_color = bm.str(SURFACE, path, "progress_color"),
            check_color = bm.str(SURFACE, path, "check_color"),
            total_duration_ms = (bm.num(SURFACE, path, "total_duration_ms"))?.toInt(),
            auto_advance = bm.bool(SURFACE, path, "auto_advance"),
            show_percentage = bm.bool(SURFACE, path, "show_percentage"),
            // Form input fields
            field_label = bm.str(SURFACE, path, "field_label"),
            field_placeholder = bm.str(SURFACE, path, "field_placeholder"),
            field_required = bm.bool(SURFACE, path, "field_required"),
            // multi_select on input_select
            // (iOS ContentBlockTypes.swift:1120). Without this Android
            // always renders single-select even when authored multi.
            multi_select = bm.bool(SURFACE, path, "multi_select"),
            // Date picker constraints +
            // chrome (iOS ContentBlockStandaloneViews.swift:966-1239).
            allow_future = bm.bool(SURFACE, path, "allow_future"),
            allow_past = bm.bool(SURFACE, path, "allow_past"),
            date_validation_message = bm.str(SURFACE, path, "date_validation_message"),
            picker_presentation = bm.str(SURFACE, path, "picker_presentation"),
            picker_mode = bm.str(SURFACE, path, "picker_mode"),
            picker_spacing = (bm.num(SURFACE, path, "picker_spacing"))?.toDouble(),
            wheel_bg_color = bm.str(SURFACE, path, "wheel_bg_color"),
            wheel_height = (bm.num(SURFACE, path, "wheel_height"))?.toDouble(),
            calendar_bg_color = bm.str(SURFACE, path, "calendar_bg_color"),
            // Sprint 7 scroll-collapse.
            collapse_on_scroll = bm.bool(SURFACE, path, "collapse_on_scroll"),
            // The `?.let` is load-bearing. Extracting this into the shared parser gave it an
            // `.orEmpty()` tail -- correct for the Option Set path, where an empty list is a real
            // answer -- and that turned an ABSENT `field_options` into an empty list rather than
            // null. iOS decodes absent as nil, and a Select distinguishes "no options authored"
            // from "options authored, none of them valid", so the two platforms disagreed.
            field_options = (bm.list(SURFACE, path, "field_options"))?.let { parseInputOptionList(it, "$path.field_options").toImmutableList() },
            // Gap 8: Parse field_config for display_style, use_variable, use_webhook
            // progress_bar `label_format`/`custom_label` are authored
            // top-level by the console editor, but Android can't add top-level ContentBlock
            // params (JVM arg-budget guard), so fold them into field_config for the renderer.
            // iOS reads them as top-level Codable fields.
            field_config = run {
                val base = (bm.objMap(SURFACE, path, "field_config"))?.toMutableMap()
                val labelFormat = bm.str(SURFACE, path, "label_format")
                val customLabel = bm.str(SURFACE, path, "custom_label")
                // Fold the top-level `video_controls` key
                // into field_config so the renderer can honour it without adding
                // a new top-level ContentBlock field (budget-locked). The console
                // editor + preview + iOS all read `video_controls`.
                val videoControls = bm.bool(SURFACE, path, "video_controls")
                // star_background particle_color /
                // particle_opacity / particle_speed are authored top-level by the
                // console editor + preview + (now) iOS, but Android is budget-locked
                // (245/245 top-level params). Fold them into field_config so the
                // StarBackgroundBlock renderer can honor them.
                val particleColor = bm.str(SURFACE, path, "particle_color")
                val particleOpacity = (bm.num(SURFACE, path, "particle_opacity"))?.toDouble()
                val particleSpeed = bm.str(SURFACE, path, "particle_speed")
                // countdown_timer `timer_variant` authored top-level by editor; fold for the renderer.
                val timerVariant = bm.str(SURFACE, path, "timer_variant")
                // Progress/Loading v2 — `label_placement` (progress_bar) + `loading_text_align`
                // (animated_loading) authored top-level by the console editor; fold into
                // field_config since Android's top-level ContentBlock is budget-locked. iOS
                // reads them as top-level Codable fields.
                val labelPlacement = bm.str(SURFACE, path, "label_placement")
                val loadingTextAlign = bm.str(SURFACE, path, "loading_text_align")
                // date_wheel_picker selection-line color/stroke authored
                // top-level by the console editor + preview (block.wheel_line_color /
                // wheel_line_stroke_width) and read as top-level Codable fields by iOS
                // (ContentBlockStandaloneViews.swift:1335-1339). Android's top-level
                // ContentBlock is budget-locked at the JVM 255-arg limit, so fold both
                // into field_config for the DateWheelPickerBlock renderer to read.
                val wheelLineColor = bm.str(SURFACE, path, "wheel_line_color")
                val wheelLineStrokeWidth = (bm.num(SURFACE, path, "wheel_line_stroke_width"))?.toDouble()
                // ContentBlock 255-arg budget — these 10 were removed from the Android ContentBlock
                // ctor (was 255→245) and are now read from field_config by the renderer. Console
                // authors them top-level + iOS reads them top-level; fold into field_config here.
                val audioUrl = bm.str(SURFACE, path, "audio_url")
                val buttonTextAlign = bm.str(SURFACE, path, "button_text_align")
                val dividerPosition = bm.str(SURFACE, path, "divider_position")
                val dotShape = bm.str(SURFACE, path, "dot_shape")
                val galleryAutoscroll = bm.bool(SURFACE, path, "gallery_autoscroll")
                val galleryAutoscrollSpeed = (bm.num(SURFACE, path, "gallery_autoscroll_speed"))?.toDouble()
                val galleryFill = bm.bool(SURFACE, path, "gallery_fill")
                val particleMulticolor = bm.bool(SURFACE, path, "particle_multicolor")
                val timeFormat = bm.str(SURFACE, path, "time_format")
                val timeTextSize = (bm.num(SURFACE, path, "time_text_size"))?.toDouble()
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
            // Visibility, animation, pressed style, bindings, sizing
            visibility_condition = (bm.objMap(SURFACE, path, "visibility_condition"))?.let { vc ->
                VisibilityCondition(
                    type = vc.str(SURFACE, path, "type") ?: "always",
                    variable = vc.str(SURFACE, path, "variable"),
                    value = vc["value"],
                    expression = vc.str(SURFACE, path, "expression"),
                )
            },
            entrance_animation = (bm.objMap(SURFACE, path, "entrance_animation"))?.let { ea ->
                EntranceAnimationConfig(
                    type = ea.str(SURFACE, path, "type") ?: "none",
                    duration_ms = (ea.num(SURFACE, path, "duration_ms"))?.toInt() ?: 300,
                    delay_ms = (ea.num(SURFACE, path, "delay_ms"))?.toInt() ?: 0,
                    easing = ea.str(SURFACE, path, "easing") ?: "ease_out",
                    spring_damping = (ea.num(SURFACE, path, "spring_damping"))?.toDouble(),
                    animation_delay_ms = (ea.num(SURFACE, path, "animation_delay_ms"))?.toInt() ?: 0,
                    animation_order = (ea.num(SURFACE, path, "animation_order"))?.toInt(),
                )
            },
            pressed_style = (bm.objMap(SURFACE, path, "pressed_style"))?.let { ps ->
                PressedStyleConfig(
                    scale = (ps.num(SURFACE, path, "scale"))?.toDouble(),
                    opacity = (ps.num(SURFACE, path, "opacity"))?.toDouble(),
                    bg_color = ps.str(SURFACE, path, "bg_color"),
                    text_color = ps.str(SURFACE, path, "text_color"),
                )
            },
            bindings = (bm.obj(SURFACE, path, "bindings"))?.entries?.associate { (k, v) -> k.toString() to v.toString() },
            element_width = bm.str(SURFACE, path, "element_width"),
            element_height = bm.str(SURFACE, path, "element_height"),
            overflow = bm.str(SURFACE, path, "overflow"),
            // Row / stack container fields
            // Coalesce `children` and
            // legacy `stack_children`. iOS ContentBlockTypes.swift:1068
            // accepts both keys; Android previously read only `children`,
            // so console-saved stack containers rendered blank.
            children = parseChildBlocks(bm["children"] ?: bm["stack_children"], path),
            row_direction = bm.str(SURFACE, path, "row_direction"),
            row_distribution = bm.str(SURFACE, path, "row_distribution"),
            row_child_fill = bm.bool(SURFACE, path, "row_child_fill"),
            gap = (bm.num(SURFACE, path, "gap"))?.toDouble(),
            wrap = bm.bool(SURFACE, path, "wrap"),
            align_items = bm.str(SURFACE, path, "align_items"),

            // ============================================================
            // Lens A P0 systemic parser drift fix.
            // Many ContentBlock fields were declared on the data class
            // and read by the renderer (often added by
            // earlier fixes) but never populated from the Firestore JSON
            // payload. Each "honour canonical first" fix was thus
            // shadowed by the parser; console-authored values silently
            // fell through to defaults. Adding all of them here so
            // every prior R-round renderer fix actually receives data.
            // ============================================================

            // Lottie/Rive canonical short names (iOS ContentBlockTypes.swift).
            // Console flow.schema.ts:225-244 emits these — NOT the
            // legacy `lottie_*`/`rive_*` aliases — so authoring
            // `autoplay: false` was always lost on Android.
            autoplay = bm.bool(SURFACE, path, "autoplay"),
            loop = bm.bool(SURFACE, path, "loop"),
            muted = bm.bool(SURFACE, path, "muted"),
            controls = bm.bool(SURFACE, path, "controls"),
            inline_playback = bm.bool(SURFACE, path, "inline_playback"),
            lottie_height = (bm.num(SURFACE, path, "lottie_height"))?.toDouble(),
            lottie_width = (bm.num(SURFACE, path, "lottie_width"))?.toDouble(),
            play_on_scroll = bm.bool(SURFACE, path, "play_on_scroll"),
            play_on_tap = bm.bool(SURFACE, path, "play_on_tap"),
            color_overrides = (bm.obj(SURFACE, path, "color_overrides"))?.entries
                ?.mapNotNull { (k, v) ->
                    val ks = k as? String ?: return@mapNotNull null
                    val vs = v as? String ?: return@mapNotNull null
                    ks to vs
                }?.toMap(),
            artboard = bm.str(SURFACE, path, "artboard"),
            state_machine = bm.str(SURFACE, path, "state_machine"),
            rive_inputs = @Suppress("UNCHECKED_CAST") (bm.objMap(SURFACE, path, "rive_inputs")),
            trigger_on_step_complete = bm.str(SURFACE, path, "trigger_on_step_complete"),

            // PricingCard fields (iOS ContentBlockTypes.swift PricingPlan).
            // PricingCardBlock at ContentBlockRenderer.kt:4006 reads
            // block.pricing_plans which was always null — card always
            // empty.
            pricing_plans = (bm.list(SURFACE, path, "pricing_plans"))?.mapNotNull { pp ->
                if (pp is Map<*, *>) {
                    @Suppress("UNCHECKED_CAST")
                    val pm = pp as Map<String, Any>
                    PricingPlan(
                        id = pm.str(SURFACE, path, "id") ?: "",
                        label = pm.str(SURFACE, path, "label") ?: pm.str(SURFACE, path, "title") ?: "",
                        price = pm.str(SURFACE, path, "price") ?: "",
                        period = pm.str(SURFACE, path, "period") ?: "",
                        badge = pm.str(SURFACE, path, "badge"),
                        is_highlighted = pm.bool(SURFACE, path, "is_highlighted")
                            ?: pm.bool(SURFACE, path, "highlighted")
                            ?: false,
                    )
                } else null
            }?.toImmutableList(),
            pricing_layout = bm.str(SURFACE, path, "pricing_layout"),

            // Rich text canonical
            markdown_content = bm.str(SURFACE, path, "markdown_content"),
            rich_text_variant = bm.str(SURFACE, path, "rich_text_variant"),

            // Social-login R13 below-inputs CTA layout
            email_login_placement = bm.str(SURFACE, path, "email_login_placement"),
            email_cta_spacing_below = (bm.num(SURFACE, path, "email_cta_spacing_below"))?.toDouble(),

            // Rating canonical R3
            default_rating = (bm.num(SURFACE, path, "default_rating"))?.toDouble(),
            rating_label = bm.str(SURFACE, path, "rating_label"),

            // ProgressBar canonical (Phase F)
            total_segments = (bm.num(SURFACE, path, "total_segments"))?.toInt(),
            progress_value = (bm.num(SURFACE, path, "progress_value"))?.toDouble(),
            progress_variant = bm.str(SURFACE, path, "progress_variant"),
            bar_color = bm.str(SURFACE, path, "bar_color"),
            bar_gradient_colors = (bm.list(SURFACE, path, "bar_gradient_colors"))?.mapNotNull { it as? String },
            bar_height = (bm.num(SURFACE, path, "bar_height"))?.toDouble(),

            // WheelPicker (Phase F + horizontal/haptic)
            min_value = (bm.num(SURFACE, path, "min_value"))?.toDouble(),
            max_value_picker = (bm.num(SURFACE, path, "max_value_picker"))?.toDouble(),
            step_value = (bm.num(SURFACE, path, "step_value"))?.toDouble(),
            default_picker_value = (bm.num(SURFACE, path, "default_picker_value"))?.toDouble(),
            unit = bm.str(SURFACE, path, "unit"),
            unit_position = bm.str(SURFACE, path, "unit_position"),
            visible_items = (bm.num(SURFACE, path, "visible_items"))?.toInt(),
            wheel_orientation = bm.str(SURFACE, path, "wheel_orientation"),
            orientation = bm.str(SURFACE, path, "orientation"),
            highlight_color = bm.str(SURFACE, path, "highlight_color"),
            haptic_on_scroll = bm.bool(SURFACE, path, "haptic_on_scroll"),

            // DateWheelPicker (Phase F)
            min_date = bm.str(SURFACE, path, "min_date"),
            max_date = bm.str(SURFACE, path, "max_date"),
            default_date_value = bm.str(SURFACE, path, "default_date_value"),
            // `columns` (per-wheel type/label/values list)
            // was declared on ContentBlock + read by the renderer but
            // never extracted from JSON. date_wheel_picker fell through
            // to default month/day/year columns regardless of console.
            columns = (bm.list(SURFACE, path, "columns"))?.mapNotNull { c ->
                if (c is Map<*, *>) {
                    @Suppress("UNCHECKED_CAST") val cm = c as Map<String, Any>
                    DateWheelColumn(
                        type = cm.str(SURFACE, path, "type") ?: "",
                        label = cm.str(SURFACE, path, "label"),
                        values = (cm.list(SURFACE, path, "values"))
                            ?.mapNotNull { it as? String }
                            ?.toImmutableList(),
                    )
                } else null
            }?.toImmutableList(),

            // PulsingAvatar (Phase F)
            pulse_color = bm.str(SURFACE, path, "pulse_color"),
            pulse_ring_count = (bm.num(SURFACE, path, "pulse_ring_count"))?.toInt(),
            pulse_speed = (bm.num(SURFACE, path, "pulse_speed"))?.toDouble(),
            border_width = (bm.num(SURFACE, path, "border_width"))?.toDouble(),
            border_color = bm.str(SURFACE, path, "border_color"),
            image_shape = bm.str(SURFACE, path, "image_shape"),
            image_corner_radius = (bm.num(SURFACE, path, "image_corner_radius"))?.toDouble(),
            badge_position = bm.str(SURFACE, path, "badge_position"),
            badge_size = (bm.num(SURFACE, path, "badge_size"))?.toDouble(),

            // StarBackground (Phase F) — density/speed are String tokens
            // ("low"/"medium"/"high", "slow"/"normal"/"fast") on the
            // data class; iOS reads the same shape.
            particle_type = bm.str(SURFACE, path, "particle_type"),
            density = bm.str(SURFACE, path, "density"),
            speed = bm.str(SURFACE, path, "speed"),
            secondary_color = bm.str(SURFACE, path, "secondary_color"),
            size_range = (bm.list(SURFACE, path, "size_range"))?.mapNotNull { (it as? Number)?.toDouble() }?.toImmutableList(),
            fullscreen = bm.bool(SURFACE, path, "fullscreen"),

            // CircularGauge (Phase F — block previously rendered fully default)
            gauge_value = (bm.num(SURFACE, path, "gauge_value"))?.toDouble(),
            max_value = (bm.num(SURFACE, path, "max_value"))?.toDouble(),
            // Both max_value AND max_gauge_value are
            // accepted iOS-side (ContentBlockRenderer.swift falls back
            // `block.max_value ?? block.max_gauge_value ?? 100.0`).
            // Decoder previously dropped max_gauge_value silently.
            max_gauge_value = (bm.num(SURFACE, path, "max_gauge_value"))?.toDouble(),
            gauge_variant = bm.str(SURFACE, path, "gauge_variant"),
            gradient_start_color = bm.str(SURFACE, path, "gradient_start_color"),
            gradient_end_color = bm.str(SURFACE, path, "gradient_end_color"),
            arrow_color = bm.str(SURFACE, path, "arrow_color"),
            arrow_stroke_width = (bm.num(SURFACE, path, "arrow_stroke_width"))?.toDouble(),
            min_label = bm.str(SURFACE, path, "min_label"),
            max_label = bm.str(SURFACE, path, "max_label"),
            min_max_font_size = (bm.num(SURFACE, path, "min_max_font_size"))?.toDouble(),
            min_max_color = bm.str(SURFACE, path, "min_max_color"),
            percentage_location = bm.str(SURFACE, path, "percentage_location"),
            sublabel = bm.str(SURFACE, path, "sublabel"),
            stroke_width = (bm.num(SURFACE, path, "stroke_width"))?.toDouble(),
            label_color = bm.str(SURFACE, path, "label_color"),
            label_font_size = (bm.num(SURFACE, path, "label_font_size"))?.toDouble(),
            animate = bm.bool(SURFACE, path, "animate"),
            animation_duration_ms = (bm.num(SURFACE, path, "animation_duration_ms"))?.toInt(),

            // AnimatedLoading variant — block.variant alias works,
            // but loading_variant is the canonical name iOS reads.
            loading_variant = bm.str(SURFACE, path, "loading_variant"),
            loading_text = bm.str(SURFACE, path, "loading_text"),
            loading_text_position = bm.str(SURFACE, path, "loading_text_position"),
            loading_text_size = (bm.num(SURFACE, path, "loading_text_size"))?.toDouble(),
            loading_text_color = bm.str(SURFACE, path, "loading_text_color"),
            gallery_images = (bm.list(SURFACE, path, "gallery_images"))?.mapNotNull { it as? String }?.toImmutableList(),
            gallery_item_width = (bm.num(SURFACE, path, "gallery_item_width"))?.toDouble(),
            gallery_item_height = (bm.num(SURFACE, path, "gallery_item_height"))?.toDouble(),
            gallery_corner_radius = (bm.num(SURFACE, path, "gallery_corner_radius"))?.toDouble(),
            gallery_spacing = (bm.num(SURFACE, path, "gallery_spacing"))?.toDouble(),
            gallery_align = bm.str(SURFACE, path, "gallery_align"),

            // Container/positioning (multiple R-rounds) — column_ratios
            // is a colon-encoded ratio string ("1:2") on both platforms
            // (iOS ContentBlockTypes.swift, Android data class line 634).
            column_ratios = bm.str(SURFACE, path, "column_ratios"),

            // Same-pattern parser drift
            // continuation of R36. These four fields are declared on the
            // data class + read first by their respective renderers but
            // never populated from JSON.

            // `field_style` envelope (FormFieldBlockStyle, 17
            // styling tokens) is read by every form_input_* renderer
            // (text/slider/select/chips/rating/toggle/etc) but parser
            // dropped it entirely; console-authored field styling
            // silently fell through to hardcoded defaults
            // (#D1D5DB borders, 8.0 radius, #374151 labels).
            field_style = (bm.objMap(SURFACE, path, "field_style"))?.let { fs ->
                FormFieldBlockStyle(
                    background_color = fs.str(SURFACE, path, "background_color"),
                    border_color = fs.str(SURFACE, path, "border_color"),
                    border_width = (fs.num(SURFACE, path, "border_width"))?.toDouble(),
                    corner_radius = (fs.num(SURFACE, path, "corner_radius"))?.toDouble(),
                    text_color = fs.str(SURFACE, path, "text_color"),
                    placeholder_color = fs.str(SURFACE, path, "placeholder_color"),
                    font_size = (fs.num(SURFACE, path, "font_size"))?.toDouble(),
                    focused_border_color = fs.str(SURFACE, path, "focused_border_color"),
                    label_color = fs.str(SURFACE, path, "label_color"),
                    label_font_size = (fs.num(SURFACE, path, "label_font_size"))?.toDouble(),
                    // Android builds FormFieldBlockStyle key by key, so
                    // adding a field to the data class is not enough: unmapped here it stays
                    // null forever and the renderer reading it can never fire. This is the
                    // half of the label_position defect that the fixture actually caught.
                    label_position = fs.str(SURFACE, path, "label_position"),
                    label_align = fs.str(SURFACE, path, "label_align"),
                    label_font_family = fs.str(SURFACE, path, "label_font_family"),
                    error_border_color = fs.str(SURFACE, path, "error_border_color"),
                    error_text_color = fs.str(SURFACE, path, "error_text_color"),
                    track_color = fs.str(SURFACE, path, "track_color"),
                    fill_color = fs.str(SURFACE, path, "fill_color"),
                    thumb_color = fs.str(SURFACE, path, "thumb_color"),
                    toggle_on_color = fs.str(SURFACE, path, "toggle_on_color"),
                    toggle_off_color = fs.str(SURFACE, path, "toggle_off_color"),
                    // Match iOS ContentBlockTypes.swift:291-312
                    // (3 fields missed). Renderer wiring follow-up.
                    height = fs.str(SURFACE, path, "height"),
                    font_weight = fs.str(SURFACE, path, "font_weight"),
                    focused_background_color = fs.str(SURFACE, path, "focused_background_color"),
                    // Select v2 (Device QA) — per-option styling extras.
                    option_font_family = fs.str(SURFACE, path, "option_font_family"),
                    option_corner_radius = (fs.num(SURFACE, path, "option_corner_radius"))?.toDouble(),
                    option_text_wrap = fs.bool(SURFACE, path, "option_text_wrap"),
                    option_image_scale = fs.str(SURFACE, path, "option_image_scale"),
                    checkmark_color = fs.str(SURFACE, path, "checkmark_color"),
                )
            },

            // `filled_segments` parser previously folded it into
            // active_segments fallback, but renderer at
            // ContentBlockRenderer.kt:2561 reads filled_segments first
            // (`block.filled_segments ?: block.active_segments`).
            // Author intent was lost when only filled_segments was set.
            filled_segments = (bm.num(SURFACE, path, "filled_segments"))?.toInt(),

            // Rating canonical iOS-first names (R3 added the
            // renderer fallback at lines 2301-2306 but parser never
            // reads them; rating fell through to default colours).
            filled_color = bm.str(SURFACE, path, "filled_color"),
            empty_color = bm.str(SURFACE, path, "empty_color"),
            z_index = (bm.num(SURFACE, path, "z_index"))?.toDouble(),
            image_fit = bm.str(SURFACE, path, "image_fit"),
            image_frame = bm.str(SURFACE, path, "image_frame"),
            view_key = bm.str(SURFACE, path, "view_key"),
            custom_config = @Suppress("UNCHECKED_CAST") (bm.objMap(SURFACE, path, "custom_config")),
            placeholder_image_url = bm.str(SURFACE, path, "placeholder_image_url"),
            placeholder_text = bm.str(SURFACE, path, "placeholder_text"),
        )
    }

    /**
     * Recursively parse child ContentBlocks for row/stack containers.
     * Accepts the raw `children` value from a Firestore map and returns a
     * list of [ContentBlock], or null if no children are present.
     *
     * Returns ImmutableList so ContentBlock.children
     * stays Compose-stable through the recursive tree.
     *
     * Delegates per-child decoding to
     * [decodeContentBlock] so nested children get the SAME ~150-field
     * decode path as top-level blocks. The previous skeleton populated
     * only ~25 fields, silently shadowing every R3-R39 renderer fix for
     * any block nested inside a row or stack container.
     */
    @Suppress("UNCHECKED_CAST")
    private fun parseChildBlocks(
        raw: Any?,
        parentPath: String = "content_blocks",
    ): kotlinx.collections.immutable.ImmutableList<ContentBlock>? {
        val list = raw as? List<*> ?: return null
        return list.mapNotNull { child ->
            if (child is Map<*, *>) {
                val cm = child as Map<String, Any>
                decodeContentBlock(cm, "$parentPath.children")
            } else null
        }.takeIf { it.isNotEmpty() }?.toImmutableList()
    }

    /**
     * ONE option parser, shared by the inline `field_options` path and the dynamic
     * Option Set path.
     *
     * Extracted from the inline lambda it used to be. The spec requires that an option authored in
     * a set and the identical option authored inline render IDENTICALLY, and the surest way to break
     * that is to give the two sources their own parsers and let them drift a field at a time — which
     * is exactly how this DTO reached 24 fields with a comment noting the last audit found them
     * dropped on parse.
     */
    internal fun parseInputOptionList(
        raw: List<*>?,
        path: String = "field_options",
    ): List<InputOption> =
        raw?.mapNotNull { fo ->
                    if (fo is Map<*, *>) {
                        @Suppress("UNCHECKED_CAST")
                        val fm = fo as Map<String, Any>
                        // 11 Drift 2
                        // expanded to read all 24 iOS InputOption fields.
                        // Console-authored per-option styling now reaches
                        // the renderer instead of being dropped on parse.
                        InputOption(
                            // Match iOS canonical
                            // value-first fallback (ContentBlockTypes.swift:358-360
                            // `let rawValue = decode(.value); self.value =
                            // rawValue ?? rawId`). Was id-first → response
                            // payload reported display id instead of
                            // authored canonical answer code, breaking
                            // server-side analytics joins + next_step_rule
                            // comparisons + webhook payloads when console
                            // author set both id and value.
                            value = fm.str(SURFACE, path, "value") ?: fm.str(SURFACE, path, "id") ?: "",
                            label = fm.str(SURFACE, path, "label") ?: "",
                            // Android maps every key by hand — omit this and category is null
                            // forever and the chip filter can never match anything.
                            category = fm.str(SURFACE, path, "category"),
                            // The option's bottom-sheet contents, parsed
                            // with the same child-block helper the stack/row containers use.
                            sheet_blocks = parseChildBlocks(fm["sheet_blocks"]),
                            image_url = fm.str(SURFACE, path, "image_url"),
                            // Match iOS rawValue fallback at
                            // ContentBlockTypes.swift:357-360. Was id-only; when
                            // console author writes only `value`, iOS resolves
                            // `id=value`, Android got `id=null` → renderer code
                            // keying selection state by option.id silently broke.
                            id = fm.str(SURFACE, path, "id") ?: fm.str(SURFACE, path, "value"),
                            icon = fm.str(SURFACE, path, "icon"),
                            selected_image_url = fm.str(SURFACE, path, "selected_image_url"),
                            unselected_image_url = fm.str(SURFACE, path, "unselected_image_url"),
                            subtitle = fm.str(SURFACE, path, "subtitle"),
                            title_color = fm.str(SURFACE, path, "title_color"),
                            subtitle_color = fm.str(SURFACE, path, "subtitle_color"),
                            title_font_size = (fm.num(SURFACE, path, "title_font_size"))?.toDouble(),
                            subtitle_font_size = (fm.num(SURFACE, path, "subtitle_font_size"))?.toDouble(),
                            title_font_weight = fm.str(SURFACE, path, "title_font_weight"),
                            selected_icon = fm.str(SURFACE, path, "selected_icon"),
                            unselected_icon = fm.str(SURFACE, path, "unselected_icon"),
                            image_overlay_color = fm.str(SURFACE, path, "image_overlay_color"),
                            image_overlay_opacity = (fm.num(SURFACE, path, "image_overlay_opacity"))?.toDouble(),
                            selected_image_overlay_color = fm.str(SURFACE, path, "selected_image_overlay_color"),
                            selected_image_overlay_opacity = (fm.num(SURFACE, path, "selected_image_overlay_opacity"))?.toDouble(),
                            image_shape = fm.str(SURFACE, path, "image_shape"),
                            border_color = fm.str(SURFACE, path, "border_color"),
                            selected_border_color = fm.str(SURFACE, path, "selected_border_color"),
                            bg_color = fm.str(SURFACE, path, "bg_color"),
                            selected_bg_color = fm.str(SURFACE, path, "selected_bg_color"),
                            selected_text_color = fm.str(SURFACE, path, "selected_text_color"),
                            cell_alignment = fm.str(SURFACE, path, "cell_alignment"),
                            // leading/trailing labels + per-option badge (were unparsed).
                            leading_text = fm.str(SURFACE, path, "leading_text"),
                            trailing_text = fm.str(SURFACE, path, "trailing_text"),
                            text_alignment = fm.str(SURFACE, path, "text_alignment"),
                            badge = (fm.objMap(SURFACE, path, "badge"))?.let { bdg ->
                                ai.appdna.sdk.onboarding.OptionBadge(
                                    text = bdg.str(SURFACE, "$path.badge", "text"),
                                    bg_color = bdg.str(SURFACE, "$path.badge", "bg_color"),
                                    text_color = bdg.str(SURFACE, "$path.badge", "text_color"),
                                    position = bdg.str(SURFACE, "$path.badge", "position"),
                                )
                            },
                        )
                    } else null
        }.orEmpty()

}
