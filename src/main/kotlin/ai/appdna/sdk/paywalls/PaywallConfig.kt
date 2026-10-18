package ai.appdna.sdk.paywalls

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import ai.appdna.sdk.core.TypedRead
import ai.appdna.sdk.core.bool
import ai.appdna.sdk.core.looseStr
import ai.appdna.sdk.core.num
import ai.appdna.sdk.core.str
import ai.appdna.sdk.core.list
import ai.appdna.sdk.core.listOfMaps
import ai.appdna.sdk.core.obj
import ai.appdna.sdk.core.objMap

/** #957 — what a mis-typed field in this document is reported against. */
private const val SURFACE = TypedRead.PAYWALL

/**
 * Codable structs matching Firestore PaywallConfig schema (Android).
 *
 * Compose-consumed paywall DTOs are annotated
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
    // Sections is the hot iteration path in PaywallActivity.
    val sections: ImmutableList<PaywallSection>,
    val dismiss: PaywallDismiss? = null,
    val background: PaywallBackground? = null,
    // Design tokens
    val animation: ai.appdna.sdk.core.AnimationConfig? = null,
    // J.22 EXCLUDE: localizations is a passthrough JSON Map (per spec).
    val localizations: Map<String, Map<String, String>>? = null,
    val default_locale: String? = null,
    // Rich media
    val haptic: ai.appdna.sdk.core.HapticConfig? = null,
    val particle_effect: ai.appdna.sdk.core.ParticleEffect? = null,
    val video_background_url: String? = null,
    // Top-level placement / version / audience / post-purchase parity with iOS
    val placement: String? = null,
    val placement_label: String? = null,
    val version: Int? = null,
    val post_purchase: PostPurchaseConfig? = null,
    /** Raw audience targeting rules (list or object). Mirrors iOS `audience_rules: AnyCodable?`. */
    val audience_rules: Any? = null,
    // Top-level `plans` array. iOS resolves plans
    // via `config.sections.first(where: { $0.type == "plans" })?.data?.plans
    // ?? config.plans ?? []` (`PaywallRenderer.swift:325-329`). Without this
    // field the Android renderer can't find plans authored at the document
    // root (some console layouts emit plans alongside `sections` rather than
    // inside a `plans` section).
    val plans: ImmutableList<PaywallPlan>? = null,
    // Top-level `cta` object. iOS reads
    // `config.cta?.text` before falling back to section-data CTA
    // (`PaywallRenderer.swift:146`).
    val cta: PaywallCTA? = null,
)

@Immutable
data class PaywallLayout(
    val type: String, // "stack", "grid", "carousel"
    val spacing: Float? = null,
    val padding: Float? = null,
    // footer/CTA zone padding + plan display style hint at layout root
    val footer_padding: Float? = null,
    val plan_display_style: String? = null,
    // Firestore writes `sections` and
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
    // Match iOS' `global_style` passthrough at
    // layout root (`PaywallConfig.swift:255`). Stored raw; consumers may
    // read individual keys for cross-section style tokens.
    val global_style: Map<String, Any>? = null,
)

/**
 * #923 — a copy of this paywall with the STORE's price on every plan, wherever that plan lives.
 *
 * 🔴 The root `plans` array and `sections[type=="plans"].data.plans` are two places a paywall's
 * plans can be written, and the renderer reads whichever is present
 * (`PaywallActivity.kt:3757`). Applying to one of them is the same as applying to none on a
 * document that used the other — which is what the first version of the substitution did on BOTH
 * platforms, and what iOS's `StorePriceRenderTests` caught by rendering the view and comparing
 * pixels. The fixture and the live-StoreKit test both passed throughout, because neither draws.
 *
 * An empty map returns the receiver untouched: a failed or timed-out lookup must change nothing.
 */
internal fun PaywallConfig.applyingStorePrices(prices: Map<String, String>): PaywallConfig {
    if (prices.isEmpty()) return this
    fun priced(list: List<PaywallPlan>?): ImmutableList<PaywallPlan>? =
        list?.map { it.copy(resolvedStorePrice = prices[it.product_id]) }?.toImmutableList()
    return copy(
        plans = priced(plans),
        sections = sections.map { section ->
            val sectionPlans = section.data?.plans
            if (section.type != "plans" || sectionPlans == null) section
            else section.copy(data = section.data.copy(plans = priced(sectionPlans)))
        }.toImmutableList(),
    )
}

@Immutable
data class PaywallSection(
    val type: String, // "header", "features", "plans", "cta", "social_proof", "guarantee", "image", "spacer", "testimonial", "lottie", "video", "rive", "countdown", "legal", "divider", "sticky_footer", "card", "carousel", "timeline", "icon_grid", "comparison_table", "promo_input", "toggle", "reviews_carousel"
    val data: PaywallSectionData? = null,
    // Per-section styling
    val style: ai.appdna.sdk.core.SectionStyleConfig? = null,
    // Per-section id + scroll-collapse parity with iOS
    val id: String? = null,
    val collapse_on_scroll: Boolean? = null,
    /**
     * #884 — which zone this section lives in: "body" (scrolls) or "bottom" (pinned with the CTA).
     *
     * Until now only the `cta` section, and a `legal` placed after it, could be pinned — so a
     * paywall's plan tiles scrolled away while the button that buys them stayed. On a short screen
     * that leaves the user choosing between options they cannot see.
     *
     * Absent means "body", so every paywall written before this renders exactly as it did.
     */
    val zone: String? = null,
)

// @Stable rather than @Immutable: the legacy `style: Any?`
// on PaywallCTA, the `Map<String, String>?` on labels, and links/items raw JSON
// all live here. Hot iterables (features/plans/items) ARE migrated to
// ImmutableList for Compose stability.
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
    // Data-level text style overrides for
    // header title + subtitle matching iOS PaywallSectionData.title_style /
    // subtitle_style at PaywallConfig.swift:88-89. Renderer prefers data-
    // level over section-level `style.elements["title"].text_style`.
    val title_style: ai.appdna.sdk.core.TextStyleConfig? = null,
    val subtitle_style: ai.appdna.sdk.core.TextStyleConfig? = null,

    // Features
    // Features list iterated by PaywallActivity FeaturesSection.
    val features: ImmutableList<String>? = null,
    /**
     * #591 — the feature subtitle's own type, and the shape an item image is clipped to.
     * Section-level for the same reason `item_text_color` is: one setting keeps every subtitle in
     * a paywall looking alike, which is the concern that got subtitles removed from these items in
     * April. iOS parity: `itemSubtitleColor` / `itemSubtitleFontSize` / `itemImageShape`.
     */
    val item_subtitle_color: String? = null,
    val item_subtitle_font_size: Float? = null,
    val item_image_shape: String? = null,
    /** The cross drawn on an EXCLUDED feature item. Authorable — a fixed red is unreadable on a red paywall. */
    val item_excluded_color: String? = null,

    // Plans
    // Plans list iterated by PaywallActivity / LazyColumn.
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

    // Discrete media heights mirroring iOS
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

    // Rich media section fields
    val lottie_url: String? = null,
    val lottie_loop: Boolean? = null,
    val lottie_speed: Float? = null,
    val rive_url: String? = null,
    val rive_state_machine: String? = null,
    val video_url: String? = null,
    val video_thumbnail_url: String? = null,
    val video_autoplay: Boolean? = null,
    val video_loop: Boolean? = null,

    // Countdown section
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

    // Legal section
    val color: String? = null,
    val links: List<PaywallLink>? = null,

    // Divider section
    val thickness: Float? = null,
    val line_style: String? = null,          // solid | dashed | dotted
    val margin_top: Float? = null,
    val margin_bottom: Float? = null,
    val margin_horizontal: Float? = null,
    val label_text: String? = null,
    val label_color: String? = null,
    val label_bg_color: String? = null,
    val label_font_size: Float? = null,
    // Countdown section layout selector
    // ("boxed", "banner", or null/inline) matching iOS PaywallRenderer.swift
    // :875-901. Determines whether the countdown timer renders inside a
    // RoundedRectangle (boxed = 12dp corner) or a Rectangle (banner = 0dp)
    // with `background_color` fill, or naked inline.
    val layout: String? = null,

    // Sticky footer section
    val cta_text: String? = null,
    val cta_bg_color: String? = null,
    val cta_text_color: String? = null,
    val cta_corner_radius: Float? = null,
    val secondary_text: String? = null,
    val secondary_action: String? = null,    // restore | link
    val secondary_url: String? = null,
    val legal_text: String? = null,
    /**
     * The sticky footer's Subtitle was drawn at a HARDCODED 10sp here and 10pt on
     * iOS, honouring no authored size or colour, while the Legal SECTION honoured its own. That is
     * what made #648 read as a Legal bug: a 13pt legal paragraph really did render larger than this
     * line, which the author had sized to 16. Unset keeps 10sp / the secondary colour.
     */
    val legal_font_size: Float? = null,
    val legal_text_color: String? = null,
    val blur_background: Boolean? = null,
    val padding: Float? = null,

    // Carousel section
    val pages: List<PaywallCarouselPage>? = null,
    val auto_scroll: Boolean? = null,
    val auto_scroll_interval_ms: Int? = null,
    val show_indicators: Boolean? = null,
    val indicator_color: String? = null,
    val indicator_active_color: String? = null,

    // Timeline / Icon grid items
    val items: List<PaywallGenericItem>? = null,
    val line_color: String? = null,
    val completed_color: String? = null,
    val current_color: String? = null,
    val upcoming_color: String? = null,
    val show_line: Boolean? = null,
    val compact: Boolean? = null,

    // Icon grid / comparison table
    val columns: Int? = null,
    val icon_size: Float? = null,
    val icon_color: String? = null,
    val spacing: Float? = null,

    // Comparison table section
    val table_columns: List<PaywallTableColumn>? = null,
    val rows: List<PaywallTableRow>? = null,
    val check_color: String? = null,
    val cross_color: String? = null,
    val highlight_color: String? = null,
    val border_color: String? = null,

    // Promo input section
    val placeholder: String? = null,
    val button_text: String? = null,
    val success_text: String? = null,
    val error_text: String? = null,

    // Toggle section
    val label: String? = null,
    val description: String? = null,
    val default_value: Boolean? = null,
    val on_color: String? = null,
    val off_color: String? = null,
    val label_color_val: String? = null,
    val description_color: String? = null,
    val icon: String? = null,
    val affects_price: Boolean? = null,

    // Reviews carousel section
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

    // Plan-card show-flags + selection styling.
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
    /** Size of the struck price + its gap to the current price. Both were
     *  hardcoded, so an author could set the colour and nothing else. */
    val strikethrough_font_size: Float? = null,
    val strikethrough_gap: Float? = null,
    /** "inline" (default, unchanged) or "headline_stacked": a large current
     *  price with the struck was-price and the real charged total beneath it. */
    val price_layout: String? = null,
    /** Badge border styling (iOS PaywallConfig.swift:266-268). */
    val badge_border_color: String? = null,
    val badge_border_width: Float? = null,
    val badge_icon: String? = null,

    // Restore button placement on CTA section.
    // iOS lets the console author place a "Restore Purchases" link above OR
    // below the main CTA, with custom text/color/font. Without this, Android
    // hardcoded the restore button inside the `plans` section
    // (PaywallActivity.kt:1296-1310) and the cta section had no restore at
    // all. iOS source: PaywallConfig.swift:107-108.
    val restore_text: String? = null,
    val show_restore: Boolean? = null,
    val restore_position: String? = null, // "above" | "below"
    val restore_text_color: String? = null,
    // #651 — Restore's own fill + radius, mirroring the pair the CTA already has.
    val restore_bg_color: String? = null,
    val restore_corner_radius: Float? = null,
    val restore_font_size: Float? = null,
    /**
     * The gap between the CTA button and the Restore link. Was a
     * hardcoded Spacer(8.dp) here, VStack(spacing: 8) on iOS and mt-2 in the preview, with no way
     * to author it. Unset keeps 8 on every surface.
     */
    val restore_gap: Float? = null,
    /**
     * What the CTA and the restore link DO. Unset keeps today's behaviour
     * exactly: the CTA purchases, the restore link restores.
     */
    val cta_action: String? = null,
    val restore_action: String? = null,
    /** Additional buttons, rendered under the CTA in authored order. */
    val extra_buttons: List<PaywallExtraButton>? = null,

    // CTA gradient (iOS `ctaGradient: PaywallGradient?`).
    // Console-authored CTA gradients silently rendered as solid before this.
    val cta_gradient: PaywallGradient? = null,
    val cta_height: Float? = null,
    val cta_font_size: Float? = null,
    // Parity: CTA text font weight (console "Font Weight" = cta_font_weight):
    // normal | medium | semibold | bold. iOS honors it via resolveCTAFontWeight;
    // Android dropped it (hardcoded SemiBold) before this.
    val cta_font_weight: String? = null,
)

// Sub-types for new paywall sections

data class PaywallLink(
    val label: String,
    val url: String,
    // Action ("restore", "url", etc.) parity with iOS
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
    // Features-list extras (text/image/included/emoji/color) parity with iOS
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
    // Emoji avatar fallback (iOS parity)
    val avatar_emoji: String? = null,
)

/**
 * Structured trial config: `{ duration_days, label }`. Mirrors iOS
 * `PaywallPlanTrial`.
 */
@Immutable
data class PaywallPlanTrial(
    val duration_days: Int? = null,
    val label: String? = null,
)

/**
 * The plan subtitle rendered as a coloured pill rather than plain text.
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
    /**
     * #923 — whether this plan's price comes from the STORE or from [price_display].
     *
     * 🔴 The DEFAULT IS THE STORE, so this is an opt-OUT and `null` means "ask". It exists for a
     * plan that deliberately shows a marketing price — a struck-through "was $19.99", a price the
     * app subsidises, a plan with no store product behind it — and `false` wins even over a store
     * that answered.
     */
    val use_store_price: Boolean? = null,
    /**
     * #923 — Play's localized price for this plan's product, filled in by the PRESENTER just
     before the screen is composed, or null when the lookup failed, timed out, or did not know the
     * product. Never parsed from a document.
     */
    val resolvedStorePrice: String? = null,
    val sort_order: Int = 0,
    // Rich plan metadata (iOS parity)
    val trial: PaywallPlanTrial? = null,
    val description: String? = null,
    // Features per plan iterated by Compose plan card.
    val features: ImmutableList<String>? = null,
    val savings_text: String? = null,
    val cta_text: String? = null,
    val icon: String? = null,
    val image_url: String? = null,
    /** Struck-through "old" price shown beside displayPrice (iOS original_price_display). */
    val original_price_display: String? = null,
    /** The real charged total, shown under a per-period headline price. */
    val price_total_display: String? = null,
    /** Render `description` as a coloured pill instead of plain text. */
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
    // Computed accessors mirroring iOS:
    // `displayName: label ?? name`, `displayPrice: price_display ?? price`,
    // `trialLabel: trial?.label ?? trial_duration`. Used by PlanCard so the
    // console's structured `label` / `price_display` / `trial.label` win
    // over the raw fields when set. iOS source: PaywallConfig.swift via
    // computed Codable accessors.
    val displayName: String get() = label.ifBlank { name }
    /**
     * Display price — the STORE's, falling back to what the author typed (#923).
     *
     * Every layout reads this one property, which is why the substitution lives here rather than
     * at each render site that would have had to remember it.
     */
    val displayPrice: String get() = ai.appdna.sdk.billing.StorePriceSubstitution.resolve(
        authoredPrice = price_display.ifBlank { price },
        productId = product_id,
        useStorePrice = use_store_price,
        storePrice = resolvedStorePrice,
    ).priceDisplay

    /**
     * Where [displayPrice] came from — `store`, or one of the three authored reasons (#923).
     * Reported with `paywall_view` so a customer asking why their paywall shows the old price has
     * an answer in the data rather than a guess.
     */
    val priceSource: String get() = ai.appdna.sdk.billing.StorePriceSubstitution.resolve(
        authoredPrice = price_display.ifBlank { price },
        productId = product_id,
        useStorePrice = use_store_price,
        storePrice = resolvedStorePrice,
    ).source
    // An authored trial.label wins verbatim; a bare trial_duration
    // ("7-day") is enriched to "7-day free trial" to match iOS PaywallConfig.swift
    // (the suffix used to be appended only at the iOS grid render site, so Android
    // showed "7-day" where iOS grid cards showed "7-day free trial").
    /**
     * #933 — an ISO duration in `trial_duration` is PHRASED rather than printed raw.
     *
     * `trial_duration` is author-supplied text, and the two stores express a trial period as an ISO
     * duration (`P7D`, `P1W`). A config carrying the store's own spelling — an import, a hand-edited
     * document or a generator, the same route #959's numeric price arrived by — rendered literally
     * as **"P7D free trial"**. [BillingPeriodPhrase] turns that into "7 days free trial"; anything
     * it cannot parse is printed exactly as authored, so "7-day" is untouched.
     */
    val trialLabel: String? get() = trial?.label ?: trial_duration?.let { d ->
        val phrased = ai.appdna.sdk.billing.BillingPeriodPhrase.duration(
            d,
            java.util.Locale.getDefault().language,
        ) ?: d
        "$phrased free trial"
    }
}

data class PaywallCTA(
    val text: String = "",
    val style: Any? = null,  // String or Map
    val bg_color: String? = null,
    val text_color: String? = null,
    val corner_radius: Double? = null,
    // Full PaywallCTAStyle parity with iOS (height/font_size/padding_vertical)
    val height: Double? = null,
    val font_size: Double? = null,
    val padding_vertical: Double? = null,
)

/**
 * An extra button in the CTA section, beyond the CTA and the restore link.
 * `action` is purchase | restore | dismiss | link; `dismiss` LEAVES the paywall, which returns the
 * user to the previous screen, so there is deliberately no separate `back`.
 */
data class PaywallExtraButton(
    val text: String? = null,
    val action: String? = null,
    val url: String? = null,
    val bg_color: String? = null,
    val text_color: String? = null,
    val font_size: Float? = null,
    /** `filled` (default) draws a button; `text` draws a tappable label like the restore link. */
    val style: String? = null,
    /** #651 — its own radius; unset inherits the CTA's so existing paywalls are untouched. */
    val corner_radius: Float? = null,
)

data class PaywallDismiss(
    val type: String = "x_button", // "x_button", "swipe", "text_link"
    val style: String? = null,
    val allowed: Boolean = true,
    val delay_seconds: Int? = null,
    val text: String? = null,
    /**
     * Placement, colour and size for the X / back chevron. Unset keeps the
     * previous hardcoded top-right, white and 16sp.
     */
    val position: String? = null,
    val color: String? = null,
    val size: Float? = null,
    /** #652 — the Back button is its OWN control, configured independently of Close. */
    val back: PaywallBackButton? = null,
)

/** #652 — the second dismissal affordance. iOS parity: `PaywallBackButton`. */
data class PaywallBackButton(
    val enabled: Boolean? = null,
    /** `chevron` (default) | `arrow` | `text_link`. */
    val style: String? = null,
    /** Back defaults to the LEFT, the side Close is not, so both can show without overlapping. */
    val position: String? = null,
    val color: String? = null,
    val size: Float? = null,
    val delay_seconds: Int? = null,
    val text: String? = null,
)

data class PaywallBackground(
    val type: String, // "color", "gradient", "image", "video"
    val value: String? = null, // hex color, gradient def, image URL, or video URL (legacy)
    val colors: List<String>? = null,
    // Full background parity with iOS PaywallBackground
    val color: String? = null,           // hex color (Firestore canonical)
    val gradient: PaywallGradient? = null,
    val image_url: String? = null,
    val image_fit: String? = null,        // "cover" | "contain" | "fill"
    val overlay: String? = null,           // hex color overlay
    // Video background
    val video_url: String? = null,
    val video_poster_url: String? = null,
    val video_muted: Boolean? = null,
    val video_loop: Boolean? = null,
)

/**
 * Linear/radial gradient with stops. Mirrors iOS `PaywallGradient`.
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

// MARK: - Post-purchase config

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
     * Arbitrary per-presentation attributes. Merged into the
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
    // Distinguish auto-dismiss after a successful restore
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
 * 12-method parity with iOS
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
    // Widen `error` to `Throwable` (mirrors
    // onPaywallRestoreFailed widening + iOS `error: Error` parity).
    fun onPaywallPurchaseFailed(paywallId: String, error: Throwable) {}

    /**
     * Same failure, plus a stable machine-readable discriminator
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
     * Same failure, plus the product the user was actually trying to buy.
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
    // Validate a promo code entered by the user. Call the completion handler with `true` if valid, `false` otherwise.
    fun onPromoCodeSubmit(paywallId: String, code: String, completion: (Boolean) -> Unit) { completion(false) }

    // Restore lifecycle (mirrors iOS
    // AppDNAPaywallDelegate.onPaywallRestore{Started,Completed,Failed}).
    fun onPaywallRestoreStarted(paywallId: String) {}
    fun onPaywallRestoreCompleted(paywallId: String, productIds: List<String>) {}
    fun onPaywallRestoreFailed(paywallId: String, error: Throwable) {}

    // Post-purchase dispatch hooks (mirrors iOS
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
        val paywallMap = data.objMap(SURFACE, "root", "paywalls") ?: data
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
        // #958 — what a mis-typed field on the DOCUMENT itself is reported against. Each nested
        // object below names its own sub-path instead ("dismiss.back", "haptic.triggers"), because
        // "a boolean where a string belongs" is unactionable without knowing which object.
        val docPath = "paywalls[$id]"
        val layoutMap = map.objMap(SURFACE, docPath, "layout") ?: emptyMap()

        // Sections live at `layout.sections`
        // (Firestore canonical) per the server validator + sync service. Top-
        // level `sections` is iOS' legacy fallback. Resolve in iOS' priority:
        // top-level wins, layout.sections fills in.
        val sectionsRaw = (map.listOfMaps(SURFACE, docPath, "sections"))
            ?: (layoutMap.listOfMaps(SURFACE, docPath, "sections"))
            ?: emptyList()
        // Wrap as ImmutableList for Compose stability.
        val sections = sectionsRaw.map { parseSectionFromMap(it) }.toImmutableList()

        // Same fallback chain for `background`.
        // The PaywallBackground branch below now resolves either map.
        val bgMap = (map.objMap(SURFACE, docPath, "background"))
            ?: (layoutMap.objMap(SURFACE, docPath, "background"))

        // Parse layout-level sections + background +
        // global_style passthrough. Section parsing already happened above so
        // the layout's `sections` field carries the SAME ImmutableList for any
        // consumer that walks `config.layout.sections` directly.
        val layout = PaywallLayout(
            type = layoutMap.str(SURFACE, "layout", "type") ?: "stack",
            spacing = (layoutMap.num(SURFACE, "layout", "spacing"))?.toFloat(),
            padding = (layoutMap.num(SURFACE, "layout", "padding"))?.toFloat(),
            //
            footer_padding = (layoutMap.num(SURFACE, "layout", "footer_padding"))?.toFloat(),
            plan_display_style = layoutMap.str(SURFACE, "layout", "plan_display_style"),
            sections = sections,
            // background built below — re-assigned via copy() after we have it
            background = null,
            global_style = layoutMap.objMap(SURFACE, docPath, "global_style"),
        )

        val dismissMap = map.objMap(SURFACE, docPath, "dismiss")
        val dismiss = dismissMap?.let {
            // iOS Codable keys read `style` first
            // and fall back to `type` (`PaywallConfig.swift:567-573`). Server
            // writes `style` per validator (`paywall.schema.ts:206`). Android
            // previously read only `type` so EVERY paywall rendered `x_button`
            // regardless of the console choice (text_link / swipe_down).
            val rawStyle = it.str(SURFACE, "dismiss", "style")
            val rawType = it.str(SURFACE, "dismiss", "type")
            val resolved = rawStyle ?: rawType ?: "x_button"
            PaywallDismiss(
                type = resolved,
                style = rawStyle,
                allowed = it.bool(SURFACE, "dismiss", "allowed") ?: true,
                delay_seconds = (it.num(SURFACE, "dismiss", "delay_seconds"))?.toInt(),
                text = it.str(SURFACE, "dismiss", "text"),
                // Placement, colour and size for the X / back chevron.
                position = it.str(SURFACE, "dismiss", "position"),
                color = it.str(SURFACE, "dismiss", "color"),
                size = (it.num(SURFACE, "dismiss", "size"))?.toFloat(),
                // #652 — the Back button's own config.
                back = (it.objMap(SURFACE, docPath, "back"))?.let { b ->
                    PaywallBackButton(
                        enabled = b.bool(SURFACE, "dismiss.back", "enabled"),
                        style = b.str(SURFACE, "dismiss.back", "style"),
                        position = b.str(SURFACE, "dismiss.back", "position"),
                        color = b.str(SURFACE, "dismiss.back", "color"),
                        size = (b.num(SURFACE, "dismiss.back", "size"))?.toFloat(),
                        delay_seconds = (b.num(SURFACE, "dismiss.back", "delay_seconds"))?.toInt(),
                        text = b.str(SURFACE, "dismiss.back", "text"),
                    )
                }
            )
        }

        val background = bgMap?.let {
            // Full PaywallBackground parity (color/gradient/image/overlay/video)
            @Suppress("UNCHECKED_CAST")
            val gradMap = it.objMap(SURFACE, docPath, "gradient")
            val gradient = gradMap?.let { g ->
                val stops = (g.list(SURFACE, docPath, "stops"))?.mapNotNull { s ->
                    @Suppress("UNCHECKED_CAST")
                    val sm = s as? Map<String, Any> ?: return@mapNotNull null
                    PaywallGradientStop(
                        color = sm.str(SURFACE, "background.gradient.stops", "color"),
                        position = (sm.num(SURFACE, "background.gradient.stops", "position"))?.toDouble(),
                    )
                }
                PaywallGradient(
                    type = g.str(SURFACE, "background.gradient", "type"),
                    angle = (g.num(SURFACE, "background.gradient", "angle"))?.toDouble(),
                    stops = stops,
                )
            }
            PaywallBackground(
                type = it.str(SURFACE, "background", "type") ?: "color",
                // iOS reads `bg.color` first,
                // falls back to `bg.value` (`PaywallRenderer.swift` color
                // resolution). Android previously read only `value` so
                // type=color backgrounds with a `color` field rendered as
                // theme-default (often white).
                value = (it.str(SURFACE, "background", "color")) ?: (it.str(SURFACE, "background", "value")),
                colors = (it.list(SURFACE, docPath, "colors"))?.filterIsInstance<String>(),
                color = it.str(SURFACE, "background", "color"),
                gradient = gradient,
                image_url = it.str(SURFACE, "background", "image_url"),
                image_fit = it.str(SURFACE, "background", "image_fit"),
                overlay = it.str(SURFACE, "background", "overlay"),
                video_url = it.str(SURFACE, "background", "video_url"),
                video_poster_url = it.str(SURFACE, "background", "video_poster_url"),
                video_muted = it.bool(SURFACE, "background", "video_muted"),
                video_loop = it.bool(SURFACE, "background", "video_loop"),
            )
        }

        // iOS Codable key is `animation_config`
        // (`PaywallConfig.swift:31` `case animation = "animation_config"`).
        // Android previously read `map["animation"]` only, so EVERY paywall's
        // entry/dismiss/section_stagger/CTA/plan_selection animation was
        // silently no-op. Accept both keys: prefer canonical, fall back to
        // legacy short key for back-compat with any older client payloads.
        val animMap = (map.objMap(SURFACE, docPath, "animation_config"))
            ?: (map.objMap(SURFACE, docPath, "animation"))
        val animation = animMap?.let {
            ai.appdna.sdk.core.AnimationConfig(
                entry_animation = it.str(SURFACE, "animation_config", "entry_animation"),
                entry_duration_ms = (it.num(SURFACE, "animation_config", "entry_duration_ms"))?.toInt(),
                section_stagger = it.str(SURFACE, "animation_config", "section_stagger"),
                section_stagger_delay_ms = (it.num(SURFACE, "animation_config", "section_stagger_delay_ms"))?.toInt(),
                cta_animation = it.str(SURFACE, "animation_config", "cta_animation"),
                plan_selection_animation = it.str(SURFACE, "animation_config", "plan_selection_animation"),
                dismiss_animation = it.str(SURFACE, "animation_config", "dismiss_animation"),
            )
        }

        // Parse localizations
        val locMap = map.objMap(SURFACE, docPath, "localizations")
        val localizations = locMap?.mapValues { (_, v) ->
            (v as? Map<*, *>)?.entries?.associate { (k, val2) ->
                (k as? String ?: "") to (val2 as? String ?: "")
            } ?: emptyMap()
        }

        // Parse haptic config
        val hapticMap = map.objMap(SURFACE, docPath, "haptic")
        val haptic = hapticMap?.let { h ->
            val triggersMap = h.objMap(SURFACE, docPath, "triggers")
            ai.appdna.sdk.core.HapticConfig(
                enabled = h.bool(SURFACE, "haptic", "enabled") ?: false,
                triggers = triggersMap?.let { t ->
                    // Read all 8 fields, not just 3.
                    // Without the missing 5, console-saved haptic configs
                    // for step_advance/option_select/toggle/form_submit/
                    // error events silently no-op on Android paywalls.
                    ai.appdna.sdk.core.HapticTriggers(
                        on_step_advance = t.str(SURFACE, "haptic.triggers", "on_step_advance"),
                        on_button_tap = t.str(SURFACE, "haptic.triggers", "on_button_tap"),
                        on_plan_select = t.str(SURFACE, "haptic.triggers", "on_plan_select"),
                        on_option_select = t.str(SURFACE, "haptic.triggers", "on_option_select"),
                        on_toggle = t.str(SURFACE, "haptic.triggers", "on_toggle"),
                        on_form_submit = t.str(SURFACE, "haptic.triggers", "on_form_submit"),
                        on_error = t.str(SURFACE, "haptic.triggers", "on_error"),
                        on_success = t.str(SURFACE, "haptic.triggers", "on_success"),
                    )
                } ?: ai.appdna.sdk.core.HapticTriggers(),
            )
        }

        // Parse particle effect
        val particleMap = map.objMap(SURFACE, docPath, "particle_effect")
        val particleEffect = particleMap?.let { p ->
            ai.appdna.sdk.core.ParticleEffect(
                type = p.str(SURFACE, "particle_effect", "type") ?: "confetti",
                trigger = p.str(SURFACE, "particle_effect", "trigger") ?: "on_purchase",
                duration_ms = (p.num(SURFACE, "particle_effect", "duration_ms"))?.toInt() ?: 2500,
                intensity = p.str(SURFACE, "particle_effect", "intensity") ?: "medium",
                colors = (p.list(SURFACE, docPath, "colors"))?.filterIsInstance<String>(),
            )
        }

        // Parse post-purchase config
        @Suppress("UNCHECKED_CAST")
        val postPurchaseMap = map.objMap(SURFACE, docPath, "post_purchase")
        val postPurchase = postPurchaseMap?.let { pp ->
            @Suppress("UNCHECKED_CAST")
            val onSuccess = (pp.objMap(SURFACE, docPath, "on_success"))?.let { s ->
                PostPurchaseSuccessConfig(
                    action = s.str(SURFACE, "post_purchase.on_success", "action") ?: "dismiss",
                    message = s.str(SURFACE, "post_purchase.on_success", "message"),
                    delay_ms = (s.num(SURFACE, "post_purchase.on_success", "delay_ms"))?.toInt(),
                    deep_link_url = s.str(SURFACE, "post_purchase.on_success", "deep_link_url"),
                    confetti = s.bool(SURFACE, "post_purchase.on_success", "confetti"),
                    lottie_url = s.str(SURFACE, "post_purchase.on_success", "lottie_url"),
                )
            }
            @Suppress("UNCHECKED_CAST")
            val onFailure = (pp.objMap(SURFACE, docPath, "on_failure"))?.let { f ->
                PostPurchaseFailureConfig(
                    action = f.str(SURFACE, "post_purchase.on_failure", "action") ?: "show_error",
                    message = f.str(SURFACE, "post_purchase.on_failure", "message"),
                    retry_text = f.str(SURFACE, "post_purchase.on_failure", "retry_text"),
                    allow_dismiss = f.bool(SURFACE, "post_purchase.on_failure", "allow_dismiss"),
                )
            }
            PostPurchaseConfig(on_success = onSuccess, on_failure = onFailure)
        }

        // Parse top-level `plans` (iOS fallback
        // path when no `plans`-type section exists). Reuse `parsePlanFromMap`.
        val topLevelPlans = (map.list(SURFACE, docPath, "plans"))
            ?.mapIndexedNotNull { idx, p ->
                @Suppress("UNCHECKED_CAST")
                (p as? Map<String, Any>)?.let { parsePlanFromMap(it).withFallbackId(idx) }
            }
            ?.toImmutableList()

        // Parse top-level `cta`. Reuses the
        // same shape iOS reads via `config.cta?.text`.
        val topLevelCtaMap = map.objMap(SURFACE, docPath, "cta")
        val topLevelCta = topLevelCtaMap?.let { ctaMap ->
            val rawStyle = ctaMap["style"]
            val styleMap = rawStyle as? Map<*, *>
            PaywallCTA(
                text = ctaMap.str(SURFACE, "cta", "text") ?: "",
                style = rawStyle,
                bg_color = ctaMap.str(SURFACE, "cta", "bg_color")
                    ?: (styleMap?.get("bg_color") as? String),
                text_color = ctaMap.str(SURFACE, "cta", "text_color")
                    ?: (styleMap?.get("text_color") as? String),
                corner_radius = (ctaMap.num(SURFACE, "cta", "corner_radius"))?.toDouble()
                    ?: (styleMap?.get("corner_radius") as? Number)?.toDouble(),
                height = (ctaMap.num(SURFACE, "cta", "height"))?.toDouble()
                    ?: (styleMap?.get("height") as? Number)?.toDouble(),
                font_size = (ctaMap.num(SURFACE, "cta", "font_size"))?.toDouble()
                    ?: (styleMap?.get("font_size") as? Number)?.toDouble(),
                padding_vertical = (ctaMap.num(SURFACE, "cta", "padding_vertical"))?.toDouble()
                    ?: (styleMap?.get("padding_vertical") as? Number)?.toDouble(),
            )
        }

        // Fold the resolved background into the
        // PaywallLayout so any consumer reading `config.layout.background`
        // sees the same value as `config.background` (both are populated).
        val layoutWithBackground = layout.copy(background = background)

        return PaywallConfig(
            id = map.str(SURFACE, docPath, "id") ?: id,
            name = map.str(SURFACE, docPath, "name") ?: "",
            layout = layoutWithBackground,
            sections = sections,
            dismiss = dismiss,
            background = background,
            animation = animation,
            localizations = localizations,
            default_locale = map.str(SURFACE, docPath, "default_locale"),
            haptic = haptic,
            particle_effect = particleEffect,
            video_background_url = map.str(SURFACE, docPath, "video_background_url"),
            // Top-level placement / version / audience / post-purchase
            placement = map.str(SURFACE, docPath, "placement"),
            placement_label = map.str(SURFACE, docPath, "placement_label"),
            version = (map.num(SURFACE, docPath, "version"))?.toInt(),
            post_purchase = postPurchase,
            audience_rules = map["audience_rules"],
            // Top-level plans + cta.
            plans = topLevelPlans,
            cta = topLevelCta,
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseSectionFromMap(map: Map<String, Any>): PaywallSection {
        val type = map["type"] as? String ?: ""
        // These two are read BEFORE `path` exists, because `path` is built from the section id
        // below — so they name the section by the only handle available here.
        val sectionHandle = "sections[${map["id"] as? String ?: type}]"
        val dataMap = (map.objMap(SURFACE, sectionHandle, "data"))
            ?: (map.objMap(SURFACE, sectionHandle, "config"))

        /*
         * #957 — the reads below NOTICE a present-but-wrong-typed field instead of absorbing it.
         *
         * `as?` yields null for "absent", "null" and "wrong type" alike, so a `corner_radius` the
         * console sent as `"12"` was indistinguishable from one nobody set — the author's value was
         * dropped and nothing anywhere knew. iOS has always known, because `Codable` throws; this
         * is the Android half of that, reported through the same path #935 built.
         *
         * `path` names the field for the report. The section's own id is the most specific handle
         * the parser has here, and it is what an author sees in the console.
         */
        val sectionPath = sectionHandle
        val path = "$sectionPath.config"

        val data = dataMap?.let { d ->
            PaywallSectionData(
                title = d.str(SURFACE, path, "title"),
                subtitle = d.str(SURFACE, path, "subtitle"),
                image_url = d.str(SURFACE, path, "image_url"),
                image_alignment = d.str(SURFACE, path, "image_alignment"),
                image_max_height = d.num(SURFACE, path, "image_max_height")?.toFloat(),
                // Data-level title_style /
                // subtitle_style decoded from text-style maps.
                title_style = parseDataTextStyle(d["title_style"], "$path.title_style"),
                subtitle_style = parseDataTextStyle(d["subtitle_style"], "$path.subtitle_style"),
                // Wrap features/plans as ImmutableList.
                features = (d.list(SURFACE, path, "features"))?.filterIsInstance<String>()?.toImmutableList(),
                item_subtitle_color = d.str(SURFACE, path, "item_subtitle_color"),
                item_subtitle_font_size = d.num(SURFACE, path, "item_subtitle_font_size")?.toFloat(),
                item_image_shape = d.str(SURFACE, path, "item_image_shape"),
                item_excluded_color = d.str(SURFACE, path, "item_excluded_color"),
                plans = (d.list(SURFACE, path, "plans"))?.mapIndexedNotNull { idx, planData ->
                    if (planData is Map<*, *>) {
                        @Suppress("UNCHECKED_CAST")
                        parsePlanFromMap(planData as Map<String, Any>).withFallbackId(idx)
                    } else null
                }?.toImmutableList(),
                cta = (d.objMap(SURFACE, path, "cta"))?.let { ctaMap ->
                    val rawStyle = ctaMap["style"]
                    val styleMap = rawStyle as? Map<*, *>
                    PaywallCTA(
                        text = ctaMap.str(SURFACE, "$path.cta", "text") ?: "",
                        style = rawStyle,
                        bg_color = ctaMap.str(SURFACE, "$path.cta", "bg_color")
                            ?: (styleMap?.get("bg_color") as? String),
                        text_color = ctaMap.str(SURFACE, "$path.cta", "text_color")
                            ?: (styleMap?.get("text_color") as? String),
                        corner_radius = (ctaMap.num(SURFACE, "$path.cta", "corner_radius"))?.toDouble()
                            ?: (styleMap?.get("corner_radius") as? Number)?.toDouble(),
                        // PaywallCTAStyle extras (height/font_size/padding_vertical)
                        height = (ctaMap.num(SURFACE, "$path.cta", "height"))?.toDouble()
                            ?: (styleMap?.get("height") as? Number)?.toDouble(),
                        font_size = (ctaMap.num(SURFACE, "$path.cta", "font_size"))?.toDouble()
                            ?: (styleMap?.get("font_size") as? Number)?.toDouble(),
                        padding_vertical = (ctaMap.num(SURFACE, "$path.cta", "padding_vertical"))?.toDouble()
                            ?: (styleMap?.get("padding_vertical") as? Number)?.toDouble(),
                    )
                },
                rating = d.num(SURFACE, path, "rating")?.toDouble(),
                review_count = d.num(SURFACE, path, "review_count")?.toInt(),
                testimonial = d.str(SURFACE, path, "testimonial"),
                sub_type = d.str(SURFACE, path, "sub_type"),
                countdown_seconds = d.num(SURFACE, path, "countdown_seconds")?.toInt(),
                text = d.str(SURFACE, path, "text"),
                guarantee_text = d.str(SURFACE, path, "guarantee_text"),
                height = d.num(SURFACE, path, "height")?.toFloat(),
                corner_radius = d.num(SURFACE, path, "corner_radius")?.toFloat(),
                lottie_height = d.num(SURFACE, path, "lottie_height")?.toFloat(),
                video_height = d.num(SURFACE, path, "video_height")?.toFloat(),
                spacer_height = d.num(SURFACE, path, "spacer_height")?.toFloat(),
                quote = d.str(SURFACE, path, "quote"),
                author_name = d.str(SURFACE, path, "author_name"),
                author_role = d.str(SURFACE, path, "author_role"),
                avatar_url = d.str(SURFACE, path, "avatar_url"),
                // Rich media fields
                lottie_url = d.str(SURFACE, path, "lottie_url"),
                lottie_loop = d.bool(SURFACE, path, "lottie_loop"),
                lottie_speed = d.num(SURFACE, path, "lottie_speed")?.toFloat(),
                rive_url = d.str(SURFACE, path, "rive_url"),
                rive_state_machine = d.str(SURFACE, path, "rive_state_machine"),
                video_url = d.str(SURFACE, path, "video_url"),
                video_thumbnail_url = d.str(SURFACE, path, "video_thumbnail_url"),
                video_autoplay = d.bool(SURFACE, path, "video_autoplay"),
                video_loop = d.bool(SURFACE, path, "video_loop"),
                // Countdown section
                variant = d.str(SURFACE, path, "variant"),
                duration_seconds = d.num(SURFACE, path, "duration_seconds")?.toInt(),
                target_datetime = d.str(SURFACE, path, "target_datetime"),
                show_days = d.bool(SURFACE, path, "show_days"),
                show_hours = d.bool(SURFACE, path, "show_hours"),
                show_minutes = d.bool(SURFACE, path, "show_minutes"),
                show_seconds = d.bool(SURFACE, path, "show_seconds"),
                labels = (d.obj(SURFACE, path, "labels"))?.entries?.associate { (k, v) -> (k as? String ?: "") to (v as? String ?: "") },
                on_expire_action = d.str(SURFACE, path, "on_expire_action"),
                expired_text = d.str(SURFACE, path, "expired_text"),
                accent_color = d.str(SURFACE, path, "accent_color"),
                background_color = d.str(SURFACE, path, "background_color"),
                font_size = d.num(SURFACE, path, "font_size")?.toFloat(),
                alignment = d.str(SURFACE, path, "alignment"),
                // Legal section
                color = d.str(SURFACE, path, "color"),
                links = (d.list(SURFACE, path, "links"))?.mapNotNull { linkData ->
                    (linkData as? Map<String, Any>)?.let { lm ->
                        PaywallLink(
                            label = lm.str(SURFACE, "$path.links", "label") ?: "",
                            url = lm.str(SURFACE, "$path.links", "url") ?: "",
                            // Link action ("restore", "url", etc.) parity
                            action = lm.str(SURFACE, "$path.links", "action"),
                        )
                    }
                },
                // Divider section
                thickness = d.num(SURFACE, path, "thickness")?.toFloat(),
                line_style = d.str(SURFACE, path, "style"),
                margin_top = d.num(SURFACE, path, "margin_top")?.toFloat(),
                margin_bottom = d.num(SURFACE, path, "margin_bottom")?.toFloat(),
                margin_horizontal = d.num(SURFACE, path, "margin_horizontal")?.toFloat(),
                label_text = d.str(SURFACE, path, "label_text"),
                label_color = d.str(SURFACE, path, "label_color"),
                label_bg_color = d.str(SURFACE, path, "label_bg_color"),
                label_font_size = d.num(SURFACE, path, "label_font_size")?.toFloat(),
                // Countdown layout selector.
                layout = d.str(SURFACE, path, "layout"),
                // Sticky footer section
                cta_text = d.str(SURFACE, path, "cta_text"),
                cta_bg_color = d.str(SURFACE, path, "cta_bg_color"),
                cta_text_color = d.str(SURFACE, path, "cta_text_color"),
                cta_corner_radius = d.num(SURFACE, path, "cta_corner_radius")?.toFloat(),
                secondary_text = d.str(SURFACE, path, "secondary_text"),
                secondary_action = d.str(SURFACE, path, "secondary_action"),
                secondary_url = d.str(SURFACE, path, "secondary_url"),
                legal_text = d.str(SURFACE, path, "legal_text"),
                legal_font_size = d.num(SURFACE, path, "legal_font_size")?.toFloat(),
                legal_text_color = d.str(SURFACE, path, "legal_text_color"),
                blur_background = d.bool(SURFACE, path, "blur_background"),
                padding = d.num(SURFACE, path, "padding")?.toFloat(),
                // Carousel section
                pages = (d.list(SURFACE, path, "pages"))?.mapNotNull { pageData ->
                    (pageData as? Map<String, Any>)?.let { pm ->
                        @Suppress("UNCHECKED_CAST")
                        val childSections = (pm.listOfMaps(SURFACE, path, "children"))?.map { parseSectionFromMap(it) }
                        PaywallCarouselPage(
                            id = pm.str(SURFACE, "$path.pages", "id") ?: "",
                            children = childSections,
                        )
                    }
                },
                auto_scroll = d.bool(SURFACE, path, "auto_scroll"),
                auto_scroll_interval_ms = d.num(SURFACE, path, "auto_scroll_interval_ms")?.toInt(),
                show_indicators = d.bool(SURFACE, path, "show_indicators"),
                indicator_color = d.str(SURFACE, path, "indicator_color"),
                indicator_active_color = d.str(SURFACE, path, "indicator_active_color"),
                // Timeline / Icon grid items
                items = (d.list(SURFACE, path, "items"))?.mapNotNull { itemData ->
                    (itemData as? Map<String, Any>)?.let { im ->
                        PaywallGenericItem(
                            id = im.str(SURFACE, "$path.items", "id"),
                            title = im.str(SURFACE, "$path.items", "title"),
                            subtitle = im.str(SURFACE, "$path.items", "subtitle"),
                            icon = im.str(SURFACE, "$path.items", "icon"),
                            status = im.str(SURFACE, "$path.items", "status"),
                            label = im.str(SURFACE, "$path.items", "label"),
                            description = im.str(SURFACE, "$path.items", "description"),
                            // Features-list extras parity
                            text = im.str(SURFACE, "$path.items", "text"),
                            image_url = im.str(SURFACE, "$path.items", "image_url"),
                            included = im.bool(SURFACE, "$path.items", "included"),
                            emoji = im.str(SURFACE, "$path.items", "emoji"),
                            color = im.str(SURFACE, "$path.items", "color"),
                        )
                    }
                },
                line_color = d.str(SURFACE, path, "line_color"),
                completed_color = d.str(SURFACE, path, "completed_color"),
                current_color = d.str(SURFACE, path, "current_color"),
                upcoming_color = d.str(SURFACE, path, "upcoming_color"),
                show_line = d.bool(SURFACE, path, "show_line"),
                compact = d.bool(SURFACE, path, "compact"),
                // Icon grid / comparison table
                columns = d.num(SURFACE, path, "columns")?.toInt(),
                icon_size = d.num(SURFACE, path, "icon_size")?.toFloat(),
                icon_color = d.str(SURFACE, path, "icon_color"),
                spacing = d.num(SURFACE, path, "spacing")?.toFloat(),
                // Comparison table section
                table_columns = ((d["table_columns"] ?: d["columns"]) as? List<*>)?.mapNotNull { colData ->
                    (colData as? Map<String, Any>)?.let { cm ->
                        PaywallTableColumn(
                            label = cm.str(SURFACE, "$path.table_columns", "label") ?: "",
                            highlighted = cm.bool(SURFACE, "$path.table_columns", "highlighted"),
                        )
                    }
                },
                rows = (d.list(SURFACE, path, "rows"))?.mapNotNull { rowData ->
                    (rowData as? Map<String, Any>)?.let { rm ->
                        PaywallTableRow(
                            feature = rm.str(SURFACE, "$path.rows", "feature") ?: "",
                            values = (rm.list(SURFACE, path, "values"))?.filterIsInstance<String>() ?: emptyList(),
                        )
                    }
                },
                check_color = d.str(SURFACE, path, "check_color"),
                cross_color = d.str(SURFACE, path, "cross_color"),
                highlight_color = d.str(SURFACE, path, "highlight_color"),
                border_color = d.str(SURFACE, path, "border_color"),
                // Promo input section
                placeholder = d.str(SURFACE, path, "placeholder"),
                button_text = d.str(SURFACE, path, "button_text"),
                success_text = d.str(SURFACE, path, "success_text"),
                error_text = d.str(SURFACE, path, "error_text"),
                // Toggle section
                label = d.str(SURFACE, path, "label"),
                description = d.str(SURFACE, path, "description"),
                default_value = d.bool(SURFACE, path, "default_value"),
                on_color = d.str(SURFACE, path, "on_color"),
                off_color = d.str(SURFACE, path, "off_color"),
                label_color_val = d.str(SURFACE, path, "label_color"),
                description_color = d.str(SURFACE, path, "description_color"),
                icon = d.str(SURFACE, path, "icon"),
                affects_price = d.bool(SURFACE, path, "affects_price"),
                // Reviews carousel section
                reviews = (d.list(SURFACE, path, "reviews"))?.mapNotNull { reviewData ->
                    (reviewData as? Map<String, Any>)?.let { rm ->
                        PaywallReview(
                            text = rm.str(SURFACE, "$path.reviews", "text") ?: "",
                            author = rm.str(SURFACE, "$path.reviews", "author") ?: "",
                            rating = (rm.num(SURFACE, "$path.reviews", "rating"))?.toDouble(),
                            avatar_url = rm.str(SURFACE, "$path.reviews", "avatar_url"),
                            date = rm.str(SURFACE, "$path.reviews", "date"),
                            // Emoji avatar fallback (iOS parity)
                            avatar_emoji = rm.str(SURFACE, "$path.reviews", "avatar_emoji"),
                        )
                    }
                },
                show_rating_stars = d.bool(SURFACE, path, "show_rating_stars"),
                star_color = d.str(SURFACE, path, "star_color"),
                // Gap 10-11: Plan display style + card/badge styling
                plan_display_style = d.str(SURFACE, path, "plan_display_style"),
                card_corner_radius = d.num(SURFACE, path, "card_corner_radius")?.toFloat(),
                card_padding = d.num(SURFACE, path, "card_padding")?.toFloat(),
                card_gap = d.num(SURFACE, path, "card_gap")?.toFloat(),
                card_height = d.num(SURFACE, path, "card_height")?.toFloat(),
                // Pass raw — renderer decodes Bool|String|null into elevation.
                card_shadow = d["card_shadow"],
                badge_position = d.str(SURFACE, path, "badge_position"),
                // The console writes the badge shape under `badge_style` (matching iOS
                // `badgeStyle="badge_style"`); Android read only `badge_shape`, which the console NEVER
                // writes, so the shape was always null → always a pill. Read `badge_style` first,
                // `badge_shape` as a back-compat fallback.
                badge_shape = (d["badge_style"] ?: d["badge_shape"]) as? String,
                badge_bg_color = d.str(SURFACE, path, "badge_bg_color"),
                badge_text_color = d.str(SURFACE, path, "badge_text_color"),
                badge_font_size = d.num(SURFACE, path, "badge_font_size")?.toFloat(),
                // Plan-card show-flags +
                // selection styling. Without these, console-authored
                // plan cards always render flat (name + price + period
                // + badge), and selection state can't tint colors.
                show_plan_icons = d.bool(SURFACE, path, "show_plan_icons"),
                show_plan_images = d.bool(SURFACE, path, "show_plan_images"),
                show_plan_subtitles = d.bool(SURFACE, path, "show_plan_subtitles"),
                show_plan_features = d.bool(SURFACE, path, "show_plan_features"),
                show_savings = d.bool(SURFACE, path, "show_savings"),
                subtitle_position = d.str(SURFACE, path, "subtitle_position"),
                selected_text_color = d.str(SURFACE, path, "selected_text_color"),
                unselected_border_color = d.str(SURFACE, path, "unselected_border_color"),
                unselected_bg_color = d.str(SURFACE, path, "unselected_bg_color"),
                selected_border_color = d.str(SURFACE, path, "selected_border_color"),
                selected_bg_color = d.str(SURFACE, path, "selected_bg_color"),
                show_divider = d.bool(SURFACE, path, "show_divider"),
                divider_color = d.str(SURFACE, path, "divider_color"),
                strikethrough_color = d.str(SURFACE, path, "strikethrough_color"),
                // Same manual-mapping trap as field_style: unmapped here
                // these stay null and the renderer branch can never fire.
                strikethrough_font_size = d.num(SURFACE, path, "strikethrough_font_size")?.toFloat(),
                strikethrough_gap = d.num(SURFACE, path, "strikethrough_gap")?.toFloat(),
                price_layout = d.str(SURFACE, path, "price_layout"),
                badge_border_color = d.str(SURFACE, path, "badge_border_color"),
                badge_border_width = d.num(SURFACE, path, "badge_border_width")?.toFloat(),
                badge_icon = d.str(SURFACE, path, "badge_icon"),
                // PW-10 — restore button placement on CTA section.
                restore_text = d.str(SURFACE, path, "restore_text"),
                show_restore = d.bool(SURFACE, path, "show_restore"),
                restore_position = d.str(SURFACE, path, "restore_position"),
                restore_text_color = d.str(SURFACE, path, "restore_text_color"),
                restore_bg_color = d.str(SURFACE, path, "restore_bg_color"),
                restore_corner_radius = d.num(SURFACE, path, "restore_corner_radius")?.toFloat(),
                restore_font_size = d.num(SURFACE, path, "restore_font_size")?.toFloat(),
                restore_gap = d.num(SURFACE, path, "restore_gap")?.toFloat(),
                // Authored button actions and extra buttons.
                cta_action = d.str(SURFACE, path, "cta_action"),
                restore_action = d.str(SURFACE, path, "restore_action"),
                extra_buttons = (d.list(SURFACE, path, "extra_buttons"))?.mapNotNull { raw ->
                    @Suppress("UNCHECKED_CAST")
                    val m = raw as? Map<String, Any?> ?: return@mapNotNull null
                    PaywallExtraButton(
                        text = m.str(SURFACE, "$path.extra_buttons", "text"),
                        action = m.str(SURFACE, "$path.extra_buttons", "action"),
                        url = m.str(SURFACE, "$path.extra_buttons", "url"),
                        bg_color = m.str(SURFACE, "$path.extra_buttons", "bg_color"),
                        text_color = m.str(SURFACE, "$path.extra_buttons", "text_color"),
                        font_size = (m.num(SURFACE, "$path.extra_buttons", "font_size"))?.toFloat(),
                        style = m.str(SURFACE, "$path.extra_buttons", "style"),
                        corner_radius = (m.num(SURFACE, "$path.extra_buttons", "corner_radius"))?.toFloat(),
                    )
                },
                // CTA gradient + height/font_size (iOS PaywallConfig.swift extras).
                cta_gradient = (d.objMap(SURFACE, path, "cta_gradient"))?.let { g ->
                    @Suppress("UNCHECKED_CAST")
                    val stopsList = (g.list(SURFACE, path, "stops"))?.mapNotNull { s ->
                        val sm = (s as? Map<String, Any>) ?: return@mapNotNull null
                        PaywallGradientStop(
                            color = sm.str(SURFACE, "$path.cta_gradient.stops", "color"),
                            position = (sm.num(SURFACE, "$path.cta_gradient.stops", "position"))?.toDouble(),
                        )
                    }
                    PaywallGradient(
                        type = g.str(SURFACE, "$path.cta_gradient", "type"),
                        angle = (g.num(SURFACE, "$path.cta_gradient", "angle"))?.toDouble(),
                        stops = stopsList,
                    )
                },
                cta_height = d.num(SURFACE, path, "cta_height")?.toFloat(),
                cta_font_size = d.num(SURFACE, path, "cta_font_size")?.toFloat(),
                cta_font_weight = d.str(SURFACE, path, "cta_font_weight"),
            )
        }

        // Parse per-section style
        val styleMap = map.objMap(SURFACE, path, "style")
        val style = styleMap?.let { parseStyle(it) }

        return PaywallSection(
            type = type,
            data = data,
            style = style,
            // Section id + scroll-collapse parity
            id = map.str(SURFACE, sectionPath, "id"),
            collapse_on_scroll = map.bool(SURFACE, sectionPath, "collapse_on_scroll"),
            zone = map.str(SURFACE, sectionPath, "zone"),
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseStyle(map: Map<String, Any>): ai.appdna.sdk.core.SectionStyleConfig {
        val containerMap = map.objMap(SURFACE, "style", "container")
        val container = containerMap?.let { parseElementStyle(it) }
        val elementsMap = map.objMap(SURFACE, "style", "elements")
        val elements = elementsMap?.mapValues { (_, v) ->
            parseElementStyle(v as? Map<String, Any> ?: emptyMap())
        }
        return ai.appdna.sdk.core.SectionStyleConfig(container = container, elements = elements)
    }

    @Suppress("UNCHECKED_CAST")
    /**
     * #905 — the same parser the renderer uses, reachable from the shared fixture.
     *
     * A fixture that re-implemented this would assert its own arithmetic; the point of the seam is
     * that the value the test compares is the value the screen gets.
     */
    internal fun parseElementStyleForTest(map: Map<String, Any>): ai.appdna.sdk.core.ElementStyleConfig =
        parseElementStyle(map)

    private fun parseElementStyle(map: Map<String, Any>): ai.appdna.sdk.core.ElementStyleConfig {
        // #958 — a mis-typed style value is reported rather than absorbed. Element styles are
        // where #905, #910, #913 and #947 all lived, and a colour sent as a number or a radius
        // sent as a string is the shape an author or a generator actually produces.
        val path = "style.elements"
        val bgMap = map.objMap(SURFACE, path, "background")
        val bg = bgMap?.let {
            val gradMap = it.objMap(SURFACE, path, "gradient")
            val grad = gradMap?.let { g ->
                val stops = (g.listOfMaps(SURFACE, path, "stops"))?.map { s ->
                    ai.appdna.sdk.core.GradientStopConfig(
                        color = s.str(SURFACE, path, "color") ?: "#000000",
                        position = (s.num(SURFACE, path, "position"))?.toDouble() ?: 0.0,
                    )
                }
                ai.appdna.sdk.core.GradientConfig(type = g.str(SURFACE, path, "type"), angle = (g.num(SURFACE, path, "angle"))?.toDouble(), stops = stops)
            }
            ai.appdna.sdk.core.BackgroundStyleConfig(
                type = it.str(SURFACE, path, "type"), color = it.str(SURFACE, path, "color"),
                gradient = grad, image_url = it.str(SURFACE, path, "image_url"),
                image_fit = it.str(SURFACE, path, "image_fit"), overlay = it.str(SURFACE, path, "overlay"),
            )
        }
        val borderMap = map.objMap(SURFACE, path, "border")
        val border = borderMap?.let {
            ai.appdna.sdk.core.BorderStyleConfig(
                width = (it.num(SURFACE, path, "width"))?.toDouble(), color = it.str(SURFACE, path, "color"),
                style = it.str(SURFACE, path, "style"), radius = (it.num(SURFACE, path, "radius"))?.toDouble(),
                radius_top_left = (it.num(SURFACE, path, "radius_top_left"))?.toDouble(),
                radius_top_right = (it.num(SURFACE, path, "radius_top_right"))?.toDouble(),
                radius_bottom_left = (it.num(SURFACE, path, "radius_bottom_left"))?.toDouble(),
                radius_bottom_right = (it.num(SURFACE, path, "radius_bottom_right"))?.toDouble(),
            )
        }
        val shadowMap = map.objMap(SURFACE, path, "shadow")
        val shadow = shadowMap?.let {
            ai.appdna.sdk.core.ShadowStyleConfig(
                x = (it.num(SURFACE, path, "x"))?.toDouble(), y = (it.num(SURFACE, path, "y"))?.toDouble(),
                blur = (it.num(SURFACE, path, "blur"))?.toDouble(), spread = (it.num(SURFACE, path, "spread"))?.toDouble(),
                color = it.str(SURFACE, path, "color"),
            )
        }
        val paddingMap = map.objMap(SURFACE, path, "padding")
        val padding = paddingMap?.let {
            ai.appdna.sdk.core.SpacingConfig(
                top = (it.num(SURFACE, path, "top"))?.toDouble(), right = (it.num(SURFACE, path, "right"))?.toDouble(),
                bottom = (it.num(SURFACE, path, "bottom"))?.toDouble(), left = (it.num(SURFACE, path, "left"))?.toDouble(),
            )
        }
        val tsMap = map.objMap(SURFACE, path, "text_style")
        val textStyle = parseDataTextStyle(tsMap, "$path.text_style")
        @Suppress("UNCHECKED_CAST")
        val nested = map.objMap(SURFACE, path, "element_style")?.let { parseElementStyle(it) }
        return ai.appdna.sdk.core.ElementStyleConfig(
            background = bg, border = border, shadow = shadow, padding = padding,
            corner_radius = (map.num(SURFACE, path, "corner_radius"))?.toDouble(),
            opacity = (map.num(SURFACE, path, "opacity"))?.toDouble(),
            text_style = textStyle,
            // #905 — the console's own spelling for a per-element container style.
            element_style = nested,
        )
    }

    // Shared text-style decoder used by both
    // ElementStyleConfig.text_style and PaywallSectionData.title_style /
    // subtitle_style. Mirrors iOS TextStyleConfig.init(from:) at
    // PaywallConfig.swift:170-179.
    @Suppress("UNCHECKED_CAST")
    private fun parseDataTextStyle(raw: Any?, path: String): ai.appdna.sdk.core.TextStyleConfig? {
        val it = raw as? Map<String, Any> ?: return null
        return ai.appdna.sdk.core.TextStyleConfig(
            font_family = it.str(SURFACE, path, "font_family"),
            font_size = (it.num(SURFACE, path, "font_size"))?.toDouble(),
            font_weight = (it.num(SURFACE, path, "font_weight"))?.toInt(),
            color = it.str(SURFACE, path, "color"),
            alignment = it.str(SURFACE, path, "alignment"),
            line_height = (it.num(SURFACE, path, "line_height"))?.toDouble(),
            letter_spacing = (it.num(SURFACE, path, "letter_spacing"))?.toDouble(),
            opacity = (it.num(SURFACE, path, "opacity"))?.toDouble(),
        )
    }

    /**
     * Plans authored WITHOUT an `id` get `id = ""` (parsePlanFromMap fallback),
     * which COLLIDES across plans: `selectedPlanId == ""` then matches EVERY plan, so every
     * card renders as selected (filled check + selected bg) and the CTA can't tell them apart.
     * Assign a unique synthetic id by index when none is authored. The real purchase target is
     * carried by `product_id`, so a synthetic selection id is safe.
     */
    private fun PaywallPlan.withFallbackId(index: Int): PaywallPlan =
        if (id.isBlank()) copy(id = "plan_$index") else this

    @Suppress("UNCHECKED_CAST")
    /**
     * #913 — the same plan parser the renderer uses, reachable from the shared fixture.
     *
     * Same reasoning as `parseElementStyleForTest`: a fixture that re-implemented the decode would
     * assert its own arithmetic, and the point of the seam is that the value the test compares is
     * the value the screen gets.
     */
    internal fun parsePlanForTest(map: Map<String, Any>): PaywallPlan = parsePlanFromMap(map)

    private fun parsePlanFromMap(map: Map<String, Any>): PaywallPlan {
        // #958 — a mis-typed plan field is reported rather than absorbed. The product id is the
        // handle an author recognises; `id` is the fallback when a plan carries no product.
        val path = "plans[${map["product_id"] as? String ?: map["id"] as? String ?: "?"}]"
        // Parse structured trial object (legacy `trial_duration` string still supported)
        val trialMap = map.objMap(SURFACE, path, "trial")
        val trial = trialMap?.let {
            PaywallPlanTrial(
                duration_days = (it.num(SURFACE, path, "duration_days"))?.toInt(),
                label = it.str(SURFACE, path, "label"),
            )
        }
        return PaywallPlan(
            id = map.str(SURFACE, path, "id") ?: "",
            product_id = map.str(SURFACE, path, "product_id") ?: "",
            name = map.str(SURFACE, path, "name") ?: "",
            // The five fields iOS coerces (`PaywallPlan.looseString`). A paywall carrying
            // `"price": 9.99` drew `9.99` on iOS and NOTHING here, because `as? String` dropped it.
            price = map.looseStr(SURFACE, path, "price") ?: "",
            period = map.str(SURFACE, path, "period"),
            badge = map.str(SURFACE, path, "badge"),
            trial_duration = map.looseStr(SURFACE, path, "trial_duration"),
            is_default = map.bool(SURFACE, path, "is_default"),
            label = map.str(SURFACE, path, "label") ?: "",
            price_display = map.looseStr(SURFACE, path, "price_display") ?: "",
            // #923 — absent means "ask the store", so only an explicit `false` opts out.
            use_store_price = map.bool(SURFACE, path, "use_store_price"),
            sort_order = (map.num(SURFACE, path, "sort_order"))?.toInt() ?: 0,
            //
            trial = trial,
            description = map.str(SURFACE, path, "description"),
            // Plan features list immutable for Compose stability.
            features = (map.list(SURFACE, path, "features"))?.filterIsInstance<String>()?.toImmutableList(),
            savings_text = map.str(SURFACE, path, "savings_text"),
            cta_text = map.str(SURFACE, path, "cta_text"),
            icon = map.str(SURFACE, path, "icon"),
            image_url = map.str(SURFACE, path, "image_url"),
            // Already coerced before `looseStr` existed, but with a plain `toString()` — which gave
            // `"9.0"` where iOS gives `"9"`. Same reader now, so the two agree to the digit.
            original_price_display = map.looseStr(SURFACE, path, "original_price_display"),
            price_total_display = map.looseStr(SURFACE, path, "price_total_display"),
            description_badge = (map.objMap(SURFACE, path, "description_badge"))?.let { b ->
                PaywallDescriptionBadge(
                    enabled = b.bool(SURFACE, "$path.description_badge", "enabled"),
                    bg_color = b.str(SURFACE, "$path.description_badge", "bg_color"),
                    text_color = b.str(SURFACE, "$path.description_badge", "text_color"),
                    corner_radius = (b.num(SURFACE, "$path.description_badge", "corner_radius"))?.toFloat(),
                )
            },
            // #587 / #588 / #589 — per-plan presentation. Sizes read loosely (`Number`, or a
            // numeric string) for the same reason the prices above do: the console writes them
            // through a slider, but an imported or AI-generated paywall can carry "13".
            price_color = map.str(SURFACE, path, "price_color"),
            subtitle_color = map.str(SURFACE, path, "subtitle_color"),
            subtitle_font_size = looseFloat(map["subtitle_font_size"]),
            subtitle_align = map.str(SURFACE, path, "subtitle_align"),
            display_mode = map.str(SURFACE, path, "display_mode"),
            text_only_font_size = looseFloat(map["text_only_font_size"]),
            text_only_color = map.str(SURFACE, path, "text_only_color"),
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
