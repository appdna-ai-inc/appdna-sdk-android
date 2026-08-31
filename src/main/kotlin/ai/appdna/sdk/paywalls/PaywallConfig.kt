package ai.appdna.sdk.paywalls

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

/**
 * Codable structs matching SPEC-002 Firestore PaywallConfig schema (Android).
 *
 * SPEC-070-A J.10 + J.22 — Compose-consumed paywall DTOs are annotated
 * `@Immutable` (or `@Stable` where iterables stay typed and a few raw JSON
 * passthrough fields remain). Hot-path iterables (`sections`, `features`,
 * `plans`) use `ImmutableList<T>` so a parent re-emit with structurally-equal
 * data short-circuits PaywallActivity recompositions.
 */
@Stable
data class PaywallConfig(
    val id: String,
    val name: String,
    val layout: PaywallLayout,
    // SPEC-070-A J.22 — sections is the hot iteration path in PaywallActivity.
    val sections: ImmutableList<PaywallSection>,
    val dismiss: PaywallDismiss? = null,
    val background: PaywallBackground? = null,
    // SPEC-084: Design tokens
    val animation: ai.appdna.sdk.core.AnimationConfig? = null,
    // J.22 EXCLUDE: localizations is a passthrough JSON Map (per spec).
    val localizations: Map<String, Map<String, String>>? = null,
    val default_locale: String? = null,
    // SPEC-085: Rich media
    val haptic: ai.appdna.sdk.core.HapticConfig? = null,
    val particle_effect: ai.appdna.sdk.core.ParticleEffect? = null,
    val video_background_url: String? = null,
    // SPEC-070-A F.8: top-level placement / version / audience / post-purchase parity with iOS
    val placement: String? = null,
    val placement_label: String? = null,
    val version: Int? = null,
    val post_purchase: PostPurchaseConfig? = null,
    /** Raw audience targeting rules (list or object). Mirrors iOS `audience_rules: AnyCodable?`. */
    val audience_rules: Any? = null,
    // SPEC-070-A finalization PW-2 — top-level `plans` array. iOS resolves plans
    // via `config.sections.first(where: { $0.type == "plans" })?.data?.plans
    // ?? config.plans ?? []` (`PaywallRenderer.swift:325-329`). Without this
    // field the Android renderer can't find plans authored at the document
    // root (some console layouts emit plans alongside `sections` rather than
    // inside a `plans` section).
    val plans: ImmutableList<PaywallPlan>? = null,
    // SPEC-070-A finalization PW-3 — top-level `cta` object. iOS reads
    // `config.cta?.text` before falling back to section-data CTA
    // (`PaywallRenderer.swift:146`).
    val cta: PaywallCTA? = null,
)

@Immutable
data class PaywallLayout(
    val type: String, // "stack", "grid", "carousel"
    val spacing: Float? = null,
    val padding: Float? = null,
    // SPEC-070-A F.8: footer/CTA zone padding + plan display style hint at layout root
    val footer_padding: Float? = null,
    val plan_display_style: String? = null,
    // SPEC-070-A finalization PW-1 — Firestore writes `sections` and
    // `background` INSIDE the `layout` object (per validator
    // `paywall.schema.ts:165-173` + entity `Paywall.ts:37-42` + sync service
    // `PaywallConfigSyncService.ts:180`). iOS resolves them via
    // `_sections ?? layout?.sections ?? []` (`PaywallConfig.swift:8-41`).
    // Android only read `map["sections"]` at the top-level, getting an empty
    // list for every real Firestore-authored paywall — which manifested as a
    // FULLY WHITE SCREEN at runtime (LazyColumn over empty list +
    // MaterialTheme.colorScheme.background = white).
    val sections: ImmutableList<PaywallSection>? = null,
    val background: PaywallBackground? = null,
    // SPEC-070-A finalization — match iOS' `global_style` passthrough at
    // layout root (`PaywallConfig.swift:255`). Stored raw; consumers may
    // read individual keys for cross-section style tokens.
    val global_style: Map<String, Any>? = null,
)

@Immutable
data class PaywallSection(
    val type: String, // "header", "features", "plans", "cta", "social_proof", "guarantee", "image", "spacer", "testimonial", "lottie", "video", "rive", "countdown", "legal", "divider", "sticky_footer", "card", "carousel", "timeline", "icon_grid", "comparison_table", "promo_input", "toggle", "reviews_carousel"
    val data: PaywallSectionData? = null,
    // SPEC-084: Per-section styling
    val style: ai.appdna.sdk.core.SectionStyleConfig? = null,
    // SPEC-070-A F.8: per-section id + scroll-collapse parity with iOS
    val id: String? = null,
    val collapse_on_scroll: Boolean? = null,
)

// SPEC-070-A J.10 — @Stable rather than @Immutable: the legacy `style: Any?`
// on PaywallCTA, the `Map<String, String>?` on labels, and links/items raw JSON
// all live here. Hot iterables (features/plans/items) ARE migrated to
// ImmutableList for Compose stability per SPEC-070-A J.22.
@Stable
data class PaywallSectionData(
    // Header
    val title: String? = null,
    val subtitle: String? = null,
    val image_url: String? = null,
    /** Header graphic alignment: "leading" | "center" (default) | "trailing". */
    val image_alignment: String? = null,
    /** Max height for the header graphic (default 200). */
    val image_max_height: Float? = null,
    // SPEC-401-A R77 (Lens A P1) — data-level text style overrides for
    // header title + subtitle matching iOS PaywallSectionData.title_style /
    // subtitle_style at PaywallConfig.swift:88-89. Renderer prefers data-
    // level over section-level `style.elements["title"].text_style`.
    val title_style: ai.appdna.sdk.core.TextStyleConfig? = null,
    val subtitle_style: ai.appdna.sdk.core.TextStyleConfig? = null,

    // Features
    // SPEC-070-A J.22 — features list iterated by PaywallActivity FeaturesSection.
    val features: ImmutableList<String>? = null,

    // Plans
    // SPEC-070-A J.22 — plans list iterated by PaywallActivity / LazyColumn.
    val plans: ImmutableList<PaywallPlan>? = null,

    // CTA
    val cta: PaywallCTA? = null,

    // Social proof
    val rating: Double? = null,
    val review_count: Int? = null,
    val testimonial: String? = null,
    val sub_type: String? = null,       // "app_rating", "countdown", "trial_badge"
    val countdown_seconds: Int? = null,
    val text: String? = null,

    // Guarantee
    val guarantee_text: String? = null,

    // Image section
    val height: Float? = null,
    val corner_radius: Float? = null,

    // SPEC-070-A finalization B5 P3 — discrete media heights mirroring iOS
    // PaywallConfig.swift:133,136. Console-authored `lottie_height: 250` /
    // `video_height: 240` previously fell back to the generic `height`
    // (default 200), making the same JSON render at different heights
    // across platforms. Now Android picks the type-specific field first.
    val lottie_height: Float? = null,
    val video_height: Float? = null,

    // Spacer section
    val spacer_height: Float? = null,

    // Testimonial section
    val quote: String? = null,
    val author_name: String? = null,
    val author_role: String? = null,
    val avatar_url: String? = null,

    // SPEC-085: Rich media section fields
    val lottie_url: String? = null,
    val lottie_loop: Boolean? = null,
    val lottie_speed: Float? = null,
    val rive_url: String? = null,
    val rive_state_machine: String? = null,
    val video_url: String? = null,
    val video_thumbnail_url: String? = null,
    val video_autoplay: Boolean? = null,
    val video_loop: Boolean? = null,

    // SPEC-089d: Countdown section
    val variant: String? = null,             // digital | circular | flip | bar
    val duration_seconds: Int? = null,
    val target_datetime: String? = null,
    val show_days: Boolean? = null,
    val show_hours: Boolean? = null,
    val show_minutes: Boolean? = null,
    val show_seconds: Boolean? = null,
    val labels: Map<String, String>? = null,
    val on_expire_action: String? = null,    // hide | show_expired_text | auto_advance
    val expired_text: String? = null,
    val accent_color: String? = null,
    val background_color: String? = null,
    val font_size: Float? = null,
    val alignment: String? = null,

    // SPEC-089d: Legal section
    val color: String? = null,
    val links: List<PaywallLink>? = null,

    // SPEC-089d: Divider section
    val thickness: Float? = null,
    val line_style: String? = null,          // solid | dashed | dotted
    val margin_top: Float? = null,
    val margin_bottom: Float? = null,
    val margin_horizontal: Float? = null,
    val label_text: String? = null,
    val label_color: String? = null,
    val label_bg_color: String? = null,
    val label_font_size: Float? = null,
    // SPEC-401-A R76 (Lens C P1) — countdown section layout selector
    // ("boxed", "banner", or null/inline) matching iOS PaywallRenderer.swift
    // :875-901. Determines whether the countdown timer renders inside a
    // RoundedRectangle (boxed = 12dp corner) or a Rectangle (banner = 0dp)
    // with `background_color` fill, or naked inline.
    val layout: String? = null,

    // SPEC-089d: Sticky footer section
    val cta_text: String? = null,
    val cta_bg_color: String? = null,
    val cta_text_color: String? = null,
    val cta_corner_radius: Float? = null,
    val secondary_text: String? = null,
    val secondary_action: String? = null,    // restore | link
    val secondary_url: String? = null,
    val legal_text: String? = null,
    val blur_background: Boolean? = null,
    val padding: Float? = null,

    // SPEC-089d: Carousel section
    val pages: List<PaywallCarouselPage>? = null,
    val auto_scroll: Boolean? = null,
    val auto_scroll_interval_ms: Int? = null,
    val show_indicators: Boolean? = null,
    val indicator_color: String? = null,
    val indicator_active_color: String? = null,

    // SPEC-089d: Timeline / Icon grid items
    val items: List<PaywallGenericItem>? = null,
    val line_color: String? = null,
    val completed_color: String? = null,
    val current_color: String? = null,
    val upcoming_color: String? = null,
    val show_line: Boolean? = null,
    val compact: Boolean? = null,

    // SPEC-089d: Icon grid / comparison table
    val columns: Int? = null,
    val icon_size: Float? = null,
    val icon_color: String? = null,
    val spacing: Float? = null,

    // SPEC-089d: Comparison table section
    val table_columns: List<PaywallTableColumn>? = null,
    val rows: List<PaywallTableRow>? = null,
    val check_color: String? = null,
    val cross_color: String? = null,
    val highlight_color: String? = null,
    val border_color: String? = null,

    // SPEC-089d: Promo input section
    val placeholder: String? = null,
    val button_text: String? = null,
    val success_text: String? = null,
    val error_text: String? = null,

    // SPEC-089d: Toggle section
    val label: String? = null,
    val description: String? = null,
    val default_value: Boolean? = null,
    val on_color: String? = null,
    val off_color: String? = null,
    val label_color_val: String? = null,
    val description_color: String? = null,
    val icon: String? = null,
    val affects_price: Boolean? = null,

    // SPEC-089d: Reviews carousel section
    val reviews: List<PaywallReview>? = null,
    val show_rating_stars: Boolean? = null,
    val star_color: String? = null,

    // Gap 10-11: Plan display style + card/badge styling
    val plan_display_style: String? = null,  // vertical_stack, radio_list, accordion, horizontal_scroll, carousel_cards, pill_selector, segmented_toggle, feature_comparison, pricing_table, minimal_chips, tiered_slider, toggle_cards
    val card_corner_radius: Float? = null,
    val card_padding: Float? = null,
    val card_gap: Float? = null,
    val card_height: Float? = null,  // Minimum plan-card height in dp (console card_height); applied as heightIn(min:)
    // Console emits either Boolean ("show shadow yes/no") OR a String enum
    // ("sm"/"md"/"lg"/"none"). iOS PlanCard.swift:206-210 derives radius and
    // y-offset from the string; Android previously discarded String values.
    // Kept Any? so the renderer can switch on actual runtime type.
    val card_shadow: Any? = null,
    val badge_position: String? = null,       // top_right, top_left, top_center, inline
    val badge_shape: String? = null,          // pill, rounded, square
    val badge_bg_color: String? = null,
    val badge_text_color: String? = null,
    val badge_font_size: Float? = null,

    // SPEC-070-A finalization PW-9 — plan-card show-flags + selection styling.
    // iOS reads these on the section.data to gate per-plan-card rendering of
    // the plan's optional fields (icon, image, subtitle, features, savings).
    // Without these, console-authored plan cards always render flat: name +
    // price + period + badge. iOS source: PaywallConfig.swift:254-258.
    val show_plan_icons: Boolean? = null,
    val show_plan_images: Boolean? = null,
    val show_plan_subtitles: Boolean? = null,
    val show_plan_features: Boolean? = null,
    val show_savings: Boolean? = null,
    /** Where to place plan.description ("above_price" / "below_price"). */
    val subtitle_position: String? = null,
    /** Color flip when card is selected (iOS `selected_text_color`). */
    val selected_text_color: String? = null,
    /** Border color when card is NOT selected (iOS unselected_border_color). */
    val unselected_border_color: String? = null,
    /** Background tint when card is NOT selected (iOS unselected_bg_color). */
    val unselected_bg_color: String? = null,
    /** Border color when card IS selected. */
    val selected_border_color: String? = null,
    /** Background color when card IS selected. */
    val selected_bg_color: String? = null,
    /** Optional divider between plan rows (e.g. radio_list). */
    val show_divider: Boolean? = null,
    val divider_color: String? = null,
    /** Color of the struck-through original price (plans[].original_price_display). */
    val strikethrough_color: String? = null,
    /** SPEC-438 (#548) — size of the struck price + its gap to the current price. Both were
     *  hardcoded, so an author could set the colour and nothing else. */
    val strikethrough_font_size: Float? = null,
    val strikethrough_gap: Float? = null,
    /** SPEC-438 (#548) — "inline" (default, unchanged) or "headline_stacked": a large current
     *  price with the struck was-price and the real charged total beneath it. */
    val price_layout: String? = null,
    /** Badge border styling (iOS PaywallConfig.swift:266-268). */
    val badge_border_color: String? = null,
    val badge_border_width: Float? = null,
    val badge_icon: String? = null,

    // SPEC-070-A finalization PW-10 — restore button placement on CTA section.
    // iOS lets the console author place a "Restore Purchases" link above OR
    // below the main CTA, with custom text/color/font. Without this, Android
    // hardcoded the restore button inside the `plans` section
    // (PaywallActivity.kt:1296-1310) and the cta section had no restore at
    // all. iOS source: PaywallConfig.swift:107-108.
    val restore_text: String? = null,
    val show_restore: Boolean? = null,
    val restore_position: String? = null, // "above" | "below"
    val restore_text_color: String? = null,
    val restore_font_size: Float? = null,

    // SPEC-070-A finalization — CTA gradient (iOS `ctaGradient: PaywallGradient?`).
    // Console-authored CTA gradients silently rendered as solid before this.
    val cta_gradient: PaywallGradient? = null,
    val cta_height: Float? = null,
    val cta_font_size: Float? = null,
    // Parity: CTA text font weight (console "Font Weight" = cta_font_weight):
    // normal | medium | semibold | bold. iOS honors it via resolveCTAFontWeight;
    // Android dropped it (hardcoded SemiBold) before this.
    val cta_font_weight: String? = null,
)

// SPEC-089d: Sub-types for new paywall sections

data class PaywallLink(
    val label: String,
    val url: String,
    // SPEC-070-A F.8: action ("restore", "url", etc.) parity with iOS
    val action: String? = null,
)

data class PaywallCarouselPage(
    val id: String,
    val children: List<PaywallSection>? = null,
)

data class PaywallGenericItem(
    val id: String? = null,
    val title: String? = null,
    val subtitle: String? = null,
    val icon: String? = null,
    val status: String? = null,       // completed | current | upcoming
    val label: String? = null,
    val description: String? = null,
    // SPEC-070-A F.8: features-list extras (text/image/included/emoji/color) parity with iOS
    val text: String? = null,
    val image_url: String? = null,
    val included: Boolean? = null,
    val emoji: String? = null,
    val color: String? = null,
)

data class PaywallTableColumn(
    val label: String,
    val highlighted: Boolean? = null,
)

data class PaywallTableRow(
    val feature: String,
    val values: List<String>,
)

data class PaywallReview(
    val text: String,
    val author: String,
    val rating: Double? = null,
    val avatar_url: String? = null,
    val date: String? = null,
    // SPEC-070-A F.8: emoji avatar fallback (iOS parity)
    val avatar_emoji: String? = null,
)

/**
 * Structured trial config: `{ duration_days, label }`. Mirrors iOS
 * `PaywallPlanTrial`. SPEC-070-A F.8.
 */
@Immutable
data class PaywallPlanTrial(
    val duration_days: Int? = null,
    val label: String? = null,
)

/**
 * SPEC-438 (#544) — the plan subtitle rendered as a coloured pill rather than plain text.
 * Authored on the product (Monetization -> Products) and copied onto the plan when the
 * product is selected, so one promotion is set once and every paywall inherits it.
 * Mirrors iOS `PaywallDescriptionBadge`.
 */
@Immutable
data class PaywallDescriptionBadge(
    val enabled: Boolean? = null,
    val bg_color: String? = null,
    val text_color: String? = null,
    val corner_radius: Float? = null,
)

@Immutable
data class PaywallPlan(
    val id: String,
    val product_id: String,
    val name: String,
    val price: String,
    val period: String? = null,
    val badge: String? = null,
    val trial_duration: String? = null,
    val is_default: Boolean? = null,
    val label: String = "",
    val price_display: String = "",
    val sort_order: Int = 0,
    // SPEC-070-A F.8: rich plan metadata (iOS parity)
    val trial: PaywallPlanTrial? = null,
    val description: String? = null,
    // SPEC-070-A J.22 — features per plan iterated by Compose plan card.
    val features: ImmutableList<String>? = null,
    val savings_text: String? = null,
    val cta_text: String? = null,
    val icon: String? = null,
    val image_url: String? = null,
    /** Struck-through "old" price shown beside displayPrice (iOS original_price_display). */
    val original_price_display: String? = null,
    /** SPEC-438 (#548) — the real charged total, shown under a per-period headline price. */
    val price_total_display: String? = null,
    /** SPEC-438 (#544) — render `description` as a coloured pill instead of plain text. */
    val description_badge: PaywallDescriptionBadge? = null,
    /**
     * #588 — this plan's price colour. Beats the section's `elements.price` style and the
     * selected/unselected text colour, because the whole point is to make ONE tier's price stand
     * out from the others; anything that could override it would defeat it. iOS parity.
     */
    val price_color: String? = null,
    /**
     * #587 — this plan's subtitle type. Section-level `elements.description` styles every plan
     * identically, which is the opposite of what an author wants when one tier should read
     * differently.
     */
    val subtitle_color: String? = null,
    val subtitle_font_size: Float? = null,
    val subtitle_align: String? = null,
    /**
     * #589 — `card` (default) or `text_only`: name and price on one centred line, with no border,
     * background, badge, subtitle or selection control. Still selectable — it is a plan.
     */
    val display_mode: String? = null,
    val text_only_font_size: Float? = null,
    val text_only_color: String? = null,
) {
    // SPEC-070-A finalization PW-12 — computed accessors mirroring iOS:
    // `displayName: label ?? name`, `displayPrice: price_display ?? price`,
    // `trialLabel: trial?.label ?? trial_duration`. Used by PlanCard so the
    // console's structured `label` / `price_display` / `trial.label` win
    // over the raw fields when set. iOS source: PaywallConfig.swift via
    // computed Codable accessors.
    val displayName: String get() = label.ifBlank { name }
    val displayPrice: String get() = price_display.ifBlank { price }
    // Round-30 — an authored trial.label wins verbatim; a bare trial_duration
    // ("7-day") is enriched to "7-day free trial" to match iOS PaywallConfig.swift
    // (the suffix used to be appended only at the iOS grid render site, so Android
    // showed "7-day" where iOS grid cards showed "7-day free trial").
    val trialLabel: String? get() = trial?.label ?: trial_duration?.let { "$it free trial" }
}

data class PaywallCTA(
    val text: String = "",
    val style: Any? = null,  // String or Map
    val bg_color: String? = null,
    val text_color: String? = null,
    val corner_radius: Double? = null,
    // SPEC-070-A F.8: full PaywallCTAStyle parity with iOS (height/font_size/padding_vertical)
    val height: Double? = null,
    val font_size: Double? = null,
    val padding_vertical: Double? = null,
)

data class PaywallDismiss(
    val type: String = "x_button", // "x_button", "swipe", "text_link"
    val style: String? = null,
    val allowed: Boolean = true,
    val delay_seconds: Int? = null,
    val text: String? = null,
)

data class PaywallBackground(
    val type: String, // "color", "gradient", "image", "video"
    val value: String? = null, // hex color, gradient def, image URL, or video URL (legacy)
    val colors: List<String>? = null,
    // SPEC-070-A F.8: full background parity with iOS PaywallBackground
    val color: String? = null,           // hex color (Firestore canonical)
    val gradient: PaywallGradient? = null,
    val image_url: String? = null,
    val image_fit: String? = null,        // "cover" | "contain" | "fill"
    val overlay: String? = null,           // hex color overlay
    // SPEC-085: Video background
    val video_url: String? = null,
    val video_poster_url: String? = null,
    val video_muted: Boolean? = null,
    val video_loop: Boolean? = null,
)

/**
 * Linear/radial gradient with stops. Mirrors iOS `PaywallGradient`.
 * SPEC-070-A F.8.
 */
data class PaywallGradient(
    val type: String? = null,    // "linear" | "radial"
    val angle: Double? = null,
    val stops: List<PaywallGradientStop>? = null,
)

data class PaywallGradientStop(
    val color: String? = null,
    val position: Double? = null,
)

// MARK: - Post-purchase config (SPEC-070-A F.8)

/**
 * Post-purchase action config (mirrors iOS `PostPurchaseConfig`).
 *
 * Reads `paywall.post_purchase.{on_success,on_failure}` from Firestore.
 * The renderer/host app applies these after a successful or failed purchase
 * (e.g., dismiss, show message, deep link, advance to next onboarding step).
 */
data class PostPurchaseConfig(
    val on_success: PostPurchaseSuccessConfig? = null,
    val on_failure: PostPurchaseFailureConfig? = null,
)

data class PostPurchaseSuccessConfig(
    val action: String,            // "dismiss" | "show_message" | "deep_link" | "next_step"
    val message: String? = null,
    val delay_ms: Int? = null,
    val deep_link_url: String? = null,
    val confetti: Boolean? = null,
    val lottie_url: String? = null,
)

data class PostPurchaseFailureConfig(
    val action: String,            // "show_error" | "retry" | "dismiss"
    val message: String? = null,
    val retry_text: String? = null,
    val allow_dismiss: Boolean? = null,
)

// MARK: - Public types

/**
 * Context passed when presenting a paywall.
 */
data class PaywallContext(
    val placement: String,
    val experiment: String? = null,
    val variant: String? = null,
    /**
     * SPEC-070-B PN row 4 (D-s) — arbitrary per-presentation attributes. Merged into the
     * `paywall_view` event's properties, so they are queryable in the warehouse alongside
     * `paywall_id` and `placement`. Keys colliding with a reserved property are dropped and warned
     * about, never silently overwritten.
     *
     * This does NOT feed the `{{custom.*}}` template namespace — that path is deferred.
     */
    val customData: Map<String, Any>? = null
) {
    companion object {
        /** Reserved `paywall_view` property names. [customData] may not shadow them. */
        internal val RESERVED_EVENT_KEYS = setOf("paywall_id", "placement")
    }
}

/**
 * Reason a paywall was dismissed.
 */
enum class DismissReason(val value: String) {
    PURCHASED("purchased"),
    DISMISSED("dismissed"),
    TAPPED_OUTSIDE("tappedOutside"),
    PROGRAMMATIC("programmatic"),
    // SPEC-401-A R20 — distinguish auto-dismiss after a successful restore
    // from a real first-time purchase. iOS PaywallManager.swift:411 emits
    // `paywall_close { dismiss_reason: "restore_success" }`; Android was
    // collapsing into PURCHASED → "purchased", which contaminated dashboards
    // filtering MTPU vs restore segmentation.
    RESTORE_SUCCESS("restore_success")
}

/**
 * Action taken by the user on a paywall.
 */
enum class PaywallAction(val value: String) {
    CTA_TAPPED("cta_tapped"),
    FEATURE_SELECTED("feature_selected"),
    PLAN_CHANGED("plan_changed"),
    LINK_TAPPED("link_tapped"),
    CUSTOM("custom")
}

/**
 * Delegate for paywall lifecycle events.
 *
 * SPEC-070-A C.2 / C.4 — 12-method parity with iOS
 * `AppDNAPaywallDelegate` (AppDNA+Delegates.swift): adds
 * `onPaywallRestoreStarted` / `onPaywallRestoreCompleted` /
 * `onPaywallRestoreFailed` (restore lifecycle) plus
 * `onPostPurchaseDeepLink` / `onPostPurchaseNextStep` (post-purchase
 * dispatch hooks consumed by [PaywallManager.handlePostPurchaseSuccess]).
 */
interface AppDNAPaywallDelegate {
    fun onPaywallPresented(paywallId: String) {}
    fun onPaywallAction(paywallId: String, action: PaywallAction) {}
    fun onPaywallPurchaseStarted(paywallId: String, productId: String) {}
    fun onPaywallPurchaseCompleted(paywallId: String, productId: String, transaction: ai.appdna.sdk.TransactionInfo) {}
    // SPEC-070-A finalization §3.2 — widen `error` to `Throwable` (mirrors
    // onPaywallRestoreFailed widening + iOS `error: Error` parity).
    fun onPaywallPurchaseFailed(paywallId: String, error: Throwable) {}

    /**
     * SPEC-070-B — same failure, plus a stable machine-readable discriminator
     * (`userCancelled` / `productNotFound` / `verificationFailed` / `networkError` /
     * `serverError` / `pending` / `providerNotAvailable` / `unknown`).
     *
     * WHY: a `Throwable` does not survive a Flutter/RN bridge, so wrappers (and hosts that
     * don't want to `when` over a sealed class) had no way to distinguish a user cancel from a
     * declined card. Defaulted to the 2-arg overload so every existing conformer keeps working
     * unchanged — the SDK calls THIS one; hosts that only override the 2-arg still receive it.
     */
    fun onPaywallPurchaseFailed(paywallId: String, error: Throwable, errorType: String) {
        onPaywallPurchaseFailed(paywallId, error)
    }

    /**
     * SPEC-070-B — same failure, plus the product the user was actually trying to buy.
     *
     * WHY: `onPaywallPurchaseCompleted` / `onPaywallPurchaseStarted` both carry `productId`, but the
     * FAILURE callback did not — so a paywall selling two plans (monthly + annual) told the host
     * only "a purchase failed on paywall X". The host could not retry the right product, could not
     * attribute the failure, and could not tell which plan the card was declined on. The
     * `purchase_failed` ANALYTIC always carried `product_id`; the delegate silently dropped it.
     *
     * `productId` is nullable: some failures happen before a product is in play (e.g. the paywall
     * config itself is missing — [PaywallManager.present]). Null means "no product was selected",
     * never "unknown product".
     *
     * Defaulted through the 3-arg overload so every existing conformer (which may override the
     * 2-arg or the 3-arg form) keeps compiling and keeps receiving the callback unchanged. The SDK
     * always calls THIS one.
     */
    fun onPaywallPurchaseFailed(
        paywallId: String,
        error: Throwable,
        errorType: String,
        productId: String?,
    ) {
        onPaywallPurchaseFailed(paywallId, error, errorType)
    }
    fun onPaywallDismissed(paywallId: String) {}
    // AC-037: Validate a promo code entered by the user. Call the completion handler with `true` if valid, `false` otherwise.
    fun onPromoCodeSubmit(paywallId: String, code: String, completion: (Boolean) -> Unit) { completion(false) }

    // SPEC-070-A C.2 — restore lifecycle (mirrors iOS
    // AppDNAPaywallDelegate.onPaywallRestore{Started,Completed,Failed}).
    fun onPaywallRestoreStarted(paywallId: String) {}
    fun onPaywallRestoreCompleted(paywallId: String, productIds: List<String>) {}
    fun onPaywallRestoreFailed(paywallId: String, error: Throwable) {}

    // SPEC-070-A C.4 / C.5 — post-purchase dispatch hooks (mirrors iOS
    // AppDNAPaywallDelegate.onPostPurchase{DeepLink,NextStep}).
    /** Fired after a successful purchase when post_purchase.on_success.action == "deep_link". */
    fun onPostPurchaseDeepLink(paywallId: String, url: String) {}
    /** Fired after a successful purchase when post_purchase.on_success.action == "next_step". */
    fun onPostPurchaseNextStep(paywallId: String) {}
}

// MARK: - Parsing helpers

internal object PaywallConfigParser {

    @Suppress("UNCHECKED_CAST")
    fun parsePaywalls(data: Map<String, Any>): Map<String, PaywallConfig> {
        // Firestore doc structure: { "paywalls": { "uuid1": {...}, "uuid2": {...} } }
        // Unwrap the "paywalls" wrapper if present, otherwise treat data as flat map
        val paywallMap = (data["paywalls"] as? Map<String, Any>) ?: data
        val parsed = mutableMapOf<String, PaywallConfig>()
        for ((key, value) in paywallMap) {
            if (value is Map<*, *>) {
                try {
                    val map = value as Map<String, Any>
                    parsed[key] = parsePaywallConfig(key, map)
                } catch (_: Exception) {}
            }
        }
        return parsed
    }

    /** Parse a single paywall from a per-item Firestore document. */
    @Suppress("UNCHECKED_CAST")
    fun parseSinglePaywall(id: String, data: Map<String, Any>): PaywallConfig? {
        return try {
            parsePaywallConfig(id, data)
        } catch (_: Exception) {
            null
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parsePaywallConfig(id: String, map: Map<String, Any>): PaywallConfig {
        val layoutMap = map["layout"] as? Map<String, Any> ?: emptyMap()

        // SPEC-070-A finalization PW-1 — sections live at `layout.sections`
        // (Firestore canonical) per the server validator + sync service. Top-
        // level `sections` is iOS' legacy fallback. Resolve in iOS' priority:
        // top-level wins, layout.sections fills in.
        val sectionsRaw = (map["sections"] as? List<Map<String, Any>>)
            ?: (layoutMap["sections"] as? List<Map<String, Any>>)
            ?: emptyList()
        // SPEC-070-A J.22 — wrap as ImmutableList for Compose stability.
        val sections = sectionsRaw.map { parseSectionFromMap(it) }.toImmutableList()

        // SPEC-070-A finalization PW-1 — same fallback chain for `background`.
        // The PaywallBackground branch below now resolves either map.
        val bgMap = (map["background"] as? Map<String, Any>)
            ?: (layoutMap["background"] as? Map<String, Any>)

        // SPEC-070-A finalization — parse layout-level sections + background +
        // global_style passthrough. Section parsing already happened above so
        // the layout's `sections` field carries the SAME ImmutableList for any
        // consumer that walks `config.layout.sections` directly.
        val layout = PaywallLayout(
            type = layoutMap["type"] as? String ?: "stack",
            spacing = (layoutMap["spacing"] as? Number)?.toFloat(),
            padding = (layoutMap["padding"] as? Number)?.toFloat(),
            // SPEC-070-A F.8
            footer_padding = (layoutMap["footer_padding"] as? Number)?.toFloat(),
            plan_display_style = layoutMap["plan_display_style"] as? String,
            sections = sections,
            // background built below — re-assigned via copy() after we have it
            background = null,
            global_style = layoutMap["global_style"] as? Map<String, Any>,
        )

        val dismissMap = map["dismiss"] as? Map<String, Any>
        val dismiss = dismissMap?.let {
            // SPEC-070-A finalization PW-5 — iOS Codable keys read `style` first
            // and fall back to `type` (`PaywallConfig.swift:567-573`). Server
            // writes `style` per validator (`paywall.schema.ts:206`). Android
            // previously read only `type` so EVERY paywall rendered `x_button`
            // regardless of the console choice (text_link / swipe_down).
            val rawStyle = it["style"] as? String
            val rawType = it["type"] as? String
            val resolved = rawStyle ?: rawType ?: "x_button"
            PaywallDismiss(
                type = resolved,
                style = rawStyle,
                allowed = it["allowed"] as? Boolean ?: true,
                delay_seconds = (it["delay_seconds"] as? Number)?.toInt(),
                text = it["text"] as? String
            )
        }

        val background = bgMap?.let {
            // SPEC-070-A F.8: full PaywallBackground parity (color/gradient/image/overlay/video)
            @Suppress("UNCHECKED_CAST")
            val gradMap = it["gradient"] as? Map<String, Any>
            val gradient = gradMap?.let { g ->
                val stops = (g["stops"] as? List<*>)?.mapNotNull { s ->
                    @Suppress("UNCHECKED_CAST")
                    val sm = s as? Map<String, Any> ?: return@mapNotNull null
                    PaywallGradientStop(
                        color = sm["color"] as? String,
                        position = (sm["position"] as? Number)?.toDouble(),
                    )
                }
                PaywallGradient(
                    type = g["type"] as? String,
                    angle = (g["angle"] as? Number)?.toDouble(),
                    stops = stops,
                )
            }
            PaywallBackground(
                type = it["type"] as? String ?: "color",
                // SPEC-070-A finalization PW-6 — iOS reads `bg.color` first,
                // falls back to `bg.value` (`PaywallRenderer.swift` color
                // resolution). Android previously read only `value` so
                // type=color backgrounds with a `color` field rendered as
                // theme-default (often white).
                value = (it["color"] as? String) ?: (it["value"] as? String),
                colors = (it["colors"] as? List<*>)?.filterIsInstance<String>(),
                color = it["color"] as? String,
                gradient = gradient,
                image_url = it["image_url"] as? String,
                image_fit = it["image_fit"] as? String,
                overlay = it["overlay"] as? String,
                video_url = it["video_url"] as? String,
                video_poster_url = it["video_poster_url"] as? String,
                video_muted = it["video_muted"] as? Boolean,
                video_loop = it["video_loop"] as? Boolean,
            )
        }

        // SPEC-070-A finalization PW-4 — iOS Codable key is `animation_config`
        // (`PaywallConfig.swift:31` `case animation = "animation_config"`).
        // Android previously read `map["animation"]` only, so EVERY paywall's
        // entry/dismiss/section_stagger/CTA/plan_selection animation was
        // silently no-op. Accept both keys: prefer canonical, fall back to
        // legacy short key for back-compat with any older client payloads.
        val animMap = (map["animation_config"] as? Map<String, Any>)
            ?: (map["animation"] as? Map<String, Any>)
        val animation = animMap?.let {
            ai.appdna.sdk.core.AnimationConfig(
                entry_animation = it["entry_animation"] as? String,
                entry_duration_ms = (it["entry_duration_ms"] as? Number)?.toInt(),
                section_stagger = it["section_stagger"] as? String,
                section_stagger_delay_ms = (it["section_stagger_delay_ms"] as? Number)?.toInt(),
                cta_animation = it["cta_animation"] as? String,
                plan_selection_animation = it["plan_selection_animation"] as? String,
                dismiss_animation = it["dismiss_animation"] as? String,
            )
        }

        // SPEC-084: Parse localizations
        val locMap = map["localizations"] as? Map<String, Any>
        val localizations = locMap?.mapValues { (_, v) ->
            (v as? Map<*, *>)?.entries?.associate { (k, val2) ->
                (k as? String ?: "") to (val2 as? String ?: "")
            } ?: emptyMap()
        }

        // SPEC-085: Parse haptic config
        val hapticMap = map["haptic"] as? Map<String, Any>
        val haptic = hapticMap?.let { h ->
            val triggersMap = h["triggers"] as? Map<String, Any>
            ai.appdna.sdk.core.HapticConfig(
                enabled = h["enabled"] as? Boolean ?: false,
                triggers = triggersMap?.let { t ->
                    // SPEC-070-A F.11: read all 8 fields, not just 3.
                    // Without the missing 5, console-saved haptic configs
                    // for step_advance/option_select/toggle/form_submit/
                    // error events silently no-op on Android paywalls.
                    ai.appdna.sdk.core.HapticTriggers(
                        on_step_advance = t["on_step_advance"] as? String,
                        on_button_tap = t["on_button_tap"] as? String,
                        on_plan_select = t["on_plan_select"] as? String,
                        on_option_select = t["on_option_select"] as? String,
                        on_toggle = t["on_toggle"] as? String,
                        on_form_submit = t["on_form_submit"] as? String,
                        on_error = t["on_error"] as? String,
                        on_success = t["on_success"] as? String,
                    )
                } ?: ai.appdna.sdk.core.HapticTriggers(),
            )
        }

        // SPEC-085: Parse particle effect
        val particleMap = map["particle_effect"] as? Map<String, Any>
        val particleEffect = particleMap?.let { p ->
            ai.appdna.sdk.core.ParticleEffect(
                type = p["type"] as? String ?: "confetti",
                trigger = p["trigger"] as? String ?: "on_purchase",
                duration_ms = (p["duration_ms"] as? Number)?.toInt() ?: 2500,
                intensity = p["intensity"] as? String ?: "medium",
                colors = (p["colors"] as? List<*>)?.filterIsInstance<String>(),
            )
        }

        // SPEC-070-A F.8: parse post-purchase config
        @Suppress("UNCHECKED_CAST")
        val postPurchaseMap = map["post_purchase"] as? Map<String, Any>
        val postPurchase = postPurchaseMap?.let { pp ->
            @Suppress("UNCHECKED_CAST")
            val onSuccess = (pp["on_success"] as? Map<String, Any>)?.let { s ->
                PostPurchaseSuccessConfig(
                    action = s["action"] as? String ?: "dismiss",
                    message = s["message"] as? String,
                    delay_ms = (s["delay_ms"] as? Number)?.toInt(),
                    deep_link_url = s["deep_link_url"] as? String,
                    confetti = s["confetti"] as? Boolean,
                    lottie_url = s["lottie_url"] as? String,
                )
            }
            @Suppress("UNCHECKED_CAST")
            val onFailure = (pp["on_failure"] as? Map<String, Any>)?.let { f ->
                PostPurchaseFailureConfig(
                    action = f["action"] as? String ?: "show_error",
                    message = f["message"] as? String,
                    retry_text = f["retry_text"] as? String,
                    allow_dismiss = f["allow_dismiss"] as? Boolean,
                )
            }
            PostPurchaseConfig(on_success = onSuccess, on_failure = onFailure)
        }

        // SPEC-070-A finalization PW-2 — parse top-level `plans` (iOS fallback
        // path when no `plans`-type section exists). Reuse `parsePlanFromMap`.
        val topLevelPlans = (map["plans"] as? List<*>)
            ?.mapIndexedNotNull { idx, p ->
                @Suppress("UNCHECKED_CAST")
                (p as? Map<String, Any>)?.let { parsePlanFromMap(it).withFallbackId(idx) }
            }
            ?.toImmutableList()

        // SPEC-070-A finalization PW-3 — parse top-level `cta`. Reuses the
        // same shape iOS reads via `config.cta?.text`.
        val topLevelCtaMap = map["cta"] as? Map<String, Any>
        val topLevelCta = topLevelCtaMap?.let { ctaMap ->
            val rawStyle = ctaMap["style"]
            val styleMap = rawStyle as? Map<*, *>
            PaywallCTA(
                text = ctaMap["text"] as? String ?: "",
                style = rawStyle,
                bg_color = ctaMap["bg_color"] as? String
                    ?: (styleMap?.get("bg_color") as? String),
                text_color = ctaMap["text_color"] as? String
                    ?: (styleMap?.get("text_color") as? String),
                corner_radius = (ctaMap["corner_radius"] as? Number)?.toDouble()
                    ?: (styleMap?.get("corner_radius") as? Number)?.toDouble(),
                height = (ctaMap["height"] as? Number)?.toDouble()
                    ?: (styleMap?.get("height") as? Number)?.toDouble(),
                font_size = (ctaMap["font_size"] as? Number)?.toDouble()
                    ?: (styleMap?.get("font_size") as? Number)?.toDouble(),
                padding_vertical = (ctaMap["padding_vertical"] as? Number)?.toDouble()
                    ?: (styleMap?.get("padding_vertical") as? Number)?.toDouble(),
            )
        }

        // SPEC-070-A finalization PW-1 — fold the resolved background into the
        // PaywallLayout so any consumer reading `config.layout.background`
        // sees the same value as `config.background` (both are populated).
        val layoutWithBackground = layout.copy(background = background)

        return PaywallConfig(
            id = map["id"] as? String ?: id,
            name = map["name"] as? String ?: "",
            layout = layoutWithBackground,
            sections = sections,
            dismiss = dismiss,
            background = background,
            animation = animation,
            localizations = localizations,
            default_locale = map["default_locale"] as? String,
            haptic = haptic,
            particle_effect = particleEffect,
            video_background_url = map["video_background_url"] as? String,
            // SPEC-070-A F.8: top-level placement / version / audience / post-purchase
            placement = map["placement"] as? String,
            placement_label = map["placement_label"] as? String,
            version = (map["version"] as? Number)?.toInt(),
            post_purchase = postPurchase,
            audience_rules = map["audience_rules"],
            // SPEC-070-A finalization PW-2 / PW-3 — top-level plans + cta.
            plans = topLevelPlans,
            cta = topLevelCta,
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseSectionFromMap(map: Map<String, Any>): PaywallSection {
        val type = map["type"] as? String ?: ""
        val dataMap = (map["data"] as? Map<String, Any>) ?: (map["config"] as? Map<String, Any>)

        val data = dataMap?.let { d ->
            PaywallSectionData(
                title = d["title"] as? String,
                subtitle = d["subtitle"] as? String,
                image_url = d["image_url"] as? String,
                image_alignment = d["image_alignment"] as? String,
                image_max_height = (d["image_max_height"] as? Number)?.toFloat(),
                // SPEC-401-A R77 (Lens A P1) — data-level title_style /
                // subtitle_style decoded from text-style maps.
                title_style = parseDataTextStyle(d["title_style"]),
                subtitle_style = parseDataTextStyle(d["subtitle_style"]),
                // SPEC-070-A J.22 — wrap features/plans as ImmutableList.
                features = (d["features"] as? List<*>)?.filterIsInstance<String>()?.toImmutableList(),
                plans = (d["plans"] as? List<*>)?.mapIndexedNotNull { idx, planData ->
                    if (planData is Map<*, *>) {
                        @Suppress("UNCHECKED_CAST")
                        parsePlanFromMap(planData as Map<String, Any>).withFallbackId(idx)
                    } else null
                }?.toImmutableList(),
                cta = (d["cta"] as? Map<String, Any>)?.let { ctaMap ->
                    val rawStyle = ctaMap["style"]
                    val styleMap = rawStyle as? Map<*, *>
                    PaywallCTA(
                        text = ctaMap["text"] as? String ?: "",
                        style = rawStyle,
                        bg_color = ctaMap["bg_color"] as? String
                            ?: (styleMap?.get("bg_color") as? String),
                        text_color = ctaMap["text_color"] as? String
                            ?: (styleMap?.get("text_color") as? String),
                        corner_radius = (ctaMap["corner_radius"] as? Number)?.toDouble()
                            ?: (styleMap?.get("corner_radius") as? Number)?.toDouble(),
                        // SPEC-070-A F.8: PaywallCTAStyle extras (height/font_size/padding_vertical)
                        height = (ctaMap["height"] as? Number)?.toDouble()
                            ?: (styleMap?.get("height") as? Number)?.toDouble(),
                        font_size = (ctaMap["font_size"] as? Number)?.toDouble()
                            ?: (styleMap?.get("font_size") as? Number)?.toDouble(),
                        padding_vertical = (ctaMap["padding_vertical"] as? Number)?.toDouble()
                            ?: (styleMap?.get("padding_vertical") as? Number)?.toDouble(),
                    )
                },
                rating = (d["rating"] as? Number)?.toDouble(),
                review_count = (d["review_count"] as? Number)?.toInt(),
                testimonial = d["testimonial"] as? String,
                sub_type = d["sub_type"] as? String,
                countdown_seconds = (d["countdown_seconds"] as? Number)?.toInt(),
                text = d["text"] as? String,
                guarantee_text = d["guarantee_text"] as? String,
                height = (d["height"] as? Number)?.toFloat(),
                corner_radius = (d["corner_radius"] as? Number)?.toFloat(),
                lottie_height = (d["lottie_height"] as? Number)?.toFloat(),
                video_height = (d["video_height"] as? Number)?.toFloat(),
                spacer_height = (d["spacer_height"] as? Number)?.toFloat(),
                quote = d["quote"] as? String,
                author_name = d["author_name"] as? String,
                author_role = d["author_role"] as? String,
                avatar_url = d["avatar_url"] as? String,
                // SPEC-085: Rich media fields
                lottie_url = d["lottie_url"] as? String,
                lottie_loop = d["lottie_loop"] as? Boolean,
                lottie_speed = (d["lottie_speed"] as? Number)?.toFloat(),
                rive_url = d["rive_url"] as? String,
                rive_state_machine = d["rive_state_machine"] as? String,
                video_url = d["video_url"] as? String,
                video_thumbnail_url = d["video_thumbnail_url"] as? String,
                video_autoplay = d["video_autoplay"] as? Boolean,
                video_loop = d["video_loop"] as? Boolean,
                // SPEC-089d: Countdown section
                variant = d["variant"] as? String,
                duration_seconds = (d["duration_seconds"] as? Number)?.toInt(),
                target_datetime = d["target_datetime"] as? String,
                show_days = d["show_days"] as? Boolean,
                show_hours = d["show_hours"] as? Boolean,
                show_minutes = d["show_minutes"] as? Boolean,
                show_seconds = d["show_seconds"] as? Boolean,
                labels = (d["labels"] as? Map<*, *>)?.entries?.associate { (k, v) -> (k as? String ?: "") to (v as? String ?: "") },
                on_expire_action = d["on_expire_action"] as? String,
                expired_text = d["expired_text"] as? String,
                accent_color = d["accent_color"] as? String,
                background_color = d["background_color"] as? String,
                font_size = (d["font_size"] as? Number)?.toFloat(),
                alignment = d["alignment"] as? String,
                // SPEC-089d: Legal section
                color = d["color"] as? String,
                links = (d["links"] as? List<*>)?.mapNotNull { linkData ->
                    (linkData as? Map<*, *>)?.let { lm ->
                        PaywallLink(
                            label = lm["label"] as? String ?: "",
                            url = lm["url"] as? String ?: "",
                            // SPEC-070-A F.8: link action ("restore", "url", etc.) parity
                            action = lm["action"] as? String,
                        )
                    }
                },
                // SPEC-089d: Divider section
                thickness = (d["thickness"] as? Number)?.toFloat(),
                line_style = d["style"] as? String,
                margin_top = (d["margin_top"] as? Number)?.toFloat(),
                margin_bottom = (d["margin_bottom"] as? Number)?.toFloat(),
                margin_horizontal = (d["margin_horizontal"] as? Number)?.toFloat(),
                label_text = d["label_text"] as? String,
                label_color = d["label_color"] as? String,
                label_bg_color = d["label_bg_color"] as? String,
                label_font_size = (d["label_font_size"] as? Number)?.toFloat(),
                // SPEC-401-A R76 (Lens C P1) — countdown layout selector.
                layout = d["layout"] as? String,
                // SPEC-089d: Sticky footer section
                cta_text = d["cta_text"] as? String,
                cta_bg_color = d["cta_bg_color"] as? String,
                cta_text_color = d["cta_text_color"] as? String,
                cta_corner_radius = (d["cta_corner_radius"] as? Number)?.toFloat(),
                secondary_text = d["secondary_text"] as? String,
                secondary_action = d["secondary_action"] as? String,
                secondary_url = d["secondary_url"] as? String,
                legal_text = d["legal_text"] as? String,
                blur_background = d["blur_background"] as? Boolean,
                padding = (d["padding"] as? Number)?.toFloat(),
                // SPEC-089d: Carousel section
                pages = (d["pages"] as? List<*>)?.mapNotNull { pageData ->
                    (pageData as? Map<*, *>)?.let { pm ->
                        @Suppress("UNCHECKED_CAST")
                        val childSections = (pm["children"] as? List<Map<String, Any>>)?.map { parseSectionFromMap(it) }
                        PaywallCarouselPage(
                            id = pm["id"] as? String ?: "",
                            children = childSections,
                        )
                    }
                },
                auto_scroll = d["auto_scroll"] as? Boolean,
                auto_scroll_interval_ms = (d["auto_scroll_interval_ms"] as? Number)?.toInt(),
                show_indicators = d["show_indicators"] as? Boolean,
                indicator_color = d["indicator_color"] as? String,
                indicator_active_color = d["indicator_active_color"] as? String,
                // SPEC-089d: Timeline / Icon grid items
                items = (d["items"] as? List<*>)?.mapNotNull { itemData ->
                    (itemData as? Map<*, *>)?.let { im ->
                        PaywallGenericItem(
                            id = im["id"] as? String,
                            title = im["title"] as? String,
                            subtitle = im["subtitle"] as? String,
                            icon = im["icon"] as? String,
                            status = im["status"] as? String,
                            label = im["label"] as? String,
                            description = im["description"] as? String,
                            // SPEC-070-A F.8: features-list extras parity
                            text = im["text"] as? String,
                            image_url = im["image_url"] as? String,
                            included = im["included"] as? Boolean,
                            emoji = im["emoji"] as? String,
                            color = im["color"] as? String,
                        )
                    }
                },
                line_color = d["line_color"] as? String,
                completed_color = d["completed_color"] as? String,
                current_color = d["current_color"] as? String,
                upcoming_color = d["upcoming_color"] as? String,
                show_line = d["show_line"] as? Boolean,
                compact = d["compact"] as? Boolean,
                // SPEC-089d: Icon grid / comparison table
                columns = (d["columns"] as? Number)?.toInt(),
                icon_size = (d["icon_size"] as? Number)?.toFloat(),
                icon_color = d["icon_color"] as? String,
                spacing = (d["spacing"] as? Number)?.toFloat(),
                // SPEC-089d: Comparison table section
                table_columns = ((d["table_columns"] ?: d["columns"]) as? List<*>)?.mapNotNull { colData ->
                    (colData as? Map<*, *>)?.let { cm ->
                        PaywallTableColumn(
                            label = cm["label"] as? String ?: "",
                            highlighted = cm["highlighted"] as? Boolean,
                        )
                    }
                },
                rows = (d["rows"] as? List<*>)?.mapNotNull { rowData ->
                    (rowData as? Map<*, *>)?.let { rm ->
                        PaywallTableRow(
                            feature = rm["feature"] as? String ?: "",
                            values = (rm["values"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
                        )
                    }
                },
                check_color = d["check_color"] as? String,
                cross_color = d["cross_color"] as? String,
                highlight_color = d["highlight_color"] as? String,
                border_color = d["border_color"] as? String,
                // SPEC-089d: Promo input section
                placeholder = d["placeholder"] as? String,
                button_text = d["button_text"] as? String,
                success_text = d["success_text"] as? String,
                error_text = d["error_text"] as? String,
                // SPEC-089d: Toggle section
                label = d["label"] as? String,
                description = d["description"] as? String,
                default_value = d["default_value"] as? Boolean,
                on_color = d["on_color"] as? String,
                off_color = d["off_color"] as? String,
                label_color_val = d["label_color"] as? String,
                description_color = d["description_color"] as? String,
                icon = d["icon"] as? String,
                affects_price = d["affects_price"] as? Boolean,
                // SPEC-089d: Reviews carousel section
                reviews = (d["reviews"] as? List<*>)?.mapNotNull { reviewData ->
                    (reviewData as? Map<*, *>)?.let { rm ->
                        PaywallReview(
                            text = rm["text"] as? String ?: "",
                            author = rm["author"] as? String ?: "",
                            rating = (rm["rating"] as? Number)?.toDouble(),
                            avatar_url = rm["avatar_url"] as? String,
                            date = rm["date"] as? String,
                            // SPEC-070-A F.8: emoji avatar fallback (iOS parity)
                            avatar_emoji = rm["avatar_emoji"] as? String,
                        )
                    }
                },
                show_rating_stars = d["show_rating_stars"] as? Boolean,
                star_color = d["star_color"] as? String,
                // Gap 10-11: Plan display style + card/badge styling
                plan_display_style = d["plan_display_style"] as? String,
                card_corner_radius = (d["card_corner_radius"] as? Number)?.toFloat(),
                card_padding = (d["card_padding"] as? Number)?.toFloat(),
                card_gap = (d["card_gap"] as? Number)?.toFloat(),
                card_height = (d["card_height"] as? Number)?.toFloat(),
                // Pass raw — renderer decodes Bool|String|null into elevation.
                card_shadow = d["card_shadow"],
                badge_position = d["badge_position"] as? String,
                // Round-21 F1 — the console writes the badge shape under `badge_style` (matching iOS
                // `badgeStyle="badge_style"`); Android read only `badge_shape`, which the console NEVER
                // writes, so the shape was always null → always a pill. Read `badge_style` first,
                // `badge_shape` as a back-compat fallback.
                badge_shape = (d["badge_style"] ?: d["badge_shape"]) as? String,
                badge_bg_color = d["badge_bg_color"] as? String,
                badge_text_color = d["badge_text_color"] as? String,
                badge_font_size = (d["badge_font_size"] as? Number)?.toFloat(),
                // SPEC-070-A finalization PW-9 — plan-card show-flags +
                // selection styling. Without these, console-authored
                // plan cards always render flat (name + price + period
                // + badge), and selection state can't tint colors.
                show_plan_icons = d["show_plan_icons"] as? Boolean,
                show_plan_images = d["show_plan_images"] as? Boolean,
                show_plan_subtitles = d["show_plan_subtitles"] as? Boolean,
                show_plan_features = d["show_plan_features"] as? Boolean,
                show_savings = d["show_savings"] as? Boolean,
                subtitle_position = d["subtitle_position"] as? String,
                selected_text_color = d["selected_text_color"] as? String,
                unselected_border_color = d["unselected_border_color"] as? String,
                unselected_bg_color = d["unselected_bg_color"] as? String,
                selected_border_color = d["selected_border_color"] as? String,
                selected_bg_color = d["selected_bg_color"] as? String,
                show_divider = d["show_divider"] as? Boolean,
                divider_color = d["divider_color"] as? String,
                strikethrough_color = d["strikethrough_color"] as? String,
                // SPEC-438 (#548) — same manual-mapping trap as field_style: unmapped here
                // these stay null and the renderer branch can never fire.
                strikethrough_font_size = (d["strikethrough_font_size"] as? Number)?.toFloat(),
                strikethrough_gap = (d["strikethrough_gap"] as? Number)?.toFloat(),
                price_layout = d["price_layout"] as? String,
                badge_border_color = d["badge_border_color"] as? String,
                badge_border_width = (d["badge_border_width"] as? Number)?.toFloat(),
                badge_icon = d["badge_icon"] as? String,
                // PW-10 — restore button placement on CTA section.
                restore_text = d["restore_text"] as? String,
                show_restore = d["show_restore"] as? Boolean,
                restore_position = d["restore_position"] as? String,
                restore_text_color = d["restore_text_color"] as? String,
                restore_font_size = (d["restore_font_size"] as? Number)?.toFloat(),
                // CTA gradient + height/font_size (iOS PaywallConfig.swift extras).
                cta_gradient = (d["cta_gradient"] as? Map<String, Any>)?.let { g ->
                    @Suppress("UNCHECKED_CAST")
                    val stopsList = (g["stops"] as? List<*>)?.mapNotNull { s ->
                        val sm = (s as? Map<String, Any>) ?: return@mapNotNull null
                        PaywallGradientStop(
                            color = sm["color"] as? String,
                            position = (sm["position"] as? Number)?.toDouble(),
                        )
                    }
                    PaywallGradient(
                        type = g["type"] as? String,
                        angle = (g["angle"] as? Number)?.toDouble(),
                        stops = stopsList,
                    )
                },
                cta_height = (d["cta_height"] as? Number)?.toFloat(),
                cta_font_size = (d["cta_font_size"] as? Number)?.toFloat(),
                cta_font_weight = d["cta_font_weight"] as? String,
            )
        }

        // SPEC-084: Parse per-section style
        val styleMap = map["style"] as? Map<String, Any>
        val style = styleMap?.let { parseStyle(it) }

        return PaywallSection(
            type = type,
            data = data,
            style = style,
            // SPEC-070-A F.8: section id + scroll-collapse parity
            id = map["id"] as? String,
            collapse_on_scroll = map["collapse_on_scroll"] as? Boolean,
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseStyle(map: Map<String, Any>): ai.appdna.sdk.core.SectionStyleConfig {
        val containerMap = map["container"] as? Map<String, Any>
        val container = containerMap?.let { parseElementStyle(it) }
        val elementsMap = map["elements"] as? Map<String, Any>
        val elements = elementsMap?.mapValues { (_, v) ->
            parseElementStyle(v as? Map<String, Any> ?: emptyMap())
        }
        return ai.appdna.sdk.core.SectionStyleConfig(container = container, elements = elements)
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseElementStyle(map: Map<String, Any>): ai.appdna.sdk.core.ElementStyleConfig {
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
        val textStyle = parseDataTextStyle(tsMap)
        return ai.appdna.sdk.core.ElementStyleConfig(
            background = bg, border = border, shadow = shadow, padding = padding,
            corner_radius = (map["corner_radius"] as? Number)?.toDouble(),
            opacity = (map["opacity"] as? Number)?.toDouble(),
            text_style = textStyle,
        )
    }

    // SPEC-401-A R77 (Lens A P1) — shared text-style decoder used by both
    // ElementStyleConfig.text_style and PaywallSectionData.title_style /
    // subtitle_style. Mirrors iOS TextStyleConfig.init(from:) at
    // PaywallConfig.swift:170-179.
    @Suppress("UNCHECKED_CAST")
    private fun parseDataTextStyle(raw: Any?): ai.appdna.sdk.core.TextStyleConfig? {
        val it = raw as? Map<String, Any> ?: return null
        return ai.appdna.sdk.core.TextStyleConfig(
            font_family = it["font_family"] as? String,
            font_size = (it["font_size"] as? Number)?.toDouble(),
            font_weight = (it["font_weight"] as? Number)?.toInt(),
            color = it["color"] as? String,
            alignment = it["alignment"] as? String,
            line_height = (it["line_height"] as? Number)?.toDouble(),
            letter_spacing = (it["letter_spacing"] as? Number)?.toDouble(),
            opacity = (it["opacity"] as? Number)?.toDouble(),
        )
    }

    /**
     * SPEC-419 — plans authored WITHOUT an `id` get `id = ""` (parsePlanFromMap fallback),
     * which COLLIDES across plans: `selectedPlanId == ""` then matches EVERY plan, so every
     * card renders as selected (filled check + selected bg) and the CTA can't tell them apart.
     * Assign a unique synthetic id by index when none is authored. The real purchase target is
     * carried by `product_id`, so a synthetic selection id is safe.
     */
    private fun PaywallPlan.withFallbackId(index: Int): PaywallPlan =
        if (id.isBlank()) copy(id = "plan_$index") else this

    @Suppress("UNCHECKED_CAST")
    private fun parsePlanFromMap(map: Map<String, Any>): PaywallPlan {
        // SPEC-070-A F.8: parse structured trial object (legacy `trial_duration` string still supported)
        val trialMap = map["trial"] as? Map<String, Any>
        val trial = trialMap?.let {
            PaywallPlanTrial(
                duration_days = (it["duration_days"] as? Number)?.toInt(),
                label = it["label"] as? String,
            )
        }
        return PaywallPlan(
            id = map["id"] as? String ?: "",
            product_id = map["product_id"] as? String ?: "",
            name = map["name"] as? String ?: "",
            price = map["price"] as? String ?: "",
            period = map["period"] as? String,
            badge = map["badge"] as? String,
            trial_duration = map["trial_duration"] as? String,
            is_default = map["is_default"] as? Boolean,
            label = map["label"] as? String ?: "",
            price_display = map["price_display"] as? String ?: "",
            sort_order = (map["sort_order"] as? Number)?.toInt() ?: 0,
            // SPEC-070-A F.8
            trial = trial,
            description = map["description"] as? String,
            // SPEC-070-A J.22 — plan features list immutable for Compose stability.
            features = (map["features"] as? List<*>)?.filterIsInstance<String>()?.toImmutableList(),
            savings_text = map["savings_text"] as? String,
            cta_text = map["cta_text"] as? String,
            icon = map["icon"] as? String,
            image_url = map["image_url"] as? String,
            original_price_display = map["original_price_display"]?.let { it as? String ?: it.toString() },
            // Same loose read as the anchor price above: a numeric total must not nil the plan.
            price_total_display = map["price_total_display"]?.let { it as? String ?: it.toString() },
            description_badge = (map["description_badge"] as? Map<*, *>)?.let { b ->
                PaywallDescriptionBadge(
                    enabled = b["enabled"] as? Boolean,
                    bg_color = b["bg_color"] as? String,
                    text_color = b["text_color"] as? String,
                    corner_radius = (b["corner_radius"] as? Number)?.toFloat(),
                )
            },
            // #587 / #588 / #589 — per-plan presentation. Sizes read loosely (`Number`, or a
            // numeric string) for the same reason the prices above do: the console writes them
            // through a slider, but an imported or AI-generated paywall can carry "13".
            price_color = map["price_color"] as? String,
            subtitle_color = map["subtitle_color"] as? String,
            subtitle_font_size = looseFloat(map["subtitle_font_size"]),
            subtitle_align = map["subtitle_align"] as? String,
            display_mode = map["display_mode"] as? String,
            text_only_font_size = looseFloat(map["text_only_font_size"]),
            text_only_color = map["text_only_color"] as? String,
        )
    }

    /**
     * A JSON number, or a numeric string rendered as one.
     *
     * Mirrors iOS `PaywallPlan.looseDouble`. A paywall document is not always written by the
     * console — experiment variant payloads, older documents and AI-generated ones can carry
     * `"13"` where a slider wrote `13`. Returning null on anything unparseable costs the author
     * that one override, never the plan.
     */
    private fun looseFloat(raw: Any?): Float? = when (raw) {
        is Number -> raw.toFloat()
        is String -> raw.toFloatOrNull()
        else -> null
    }
}
