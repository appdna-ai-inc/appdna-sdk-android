package ai.appdna.sdk.feedback

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import org.json.JSONObject
import org.json.JSONArray
import ai.appdna.sdk.core.TextStyleConfig
import ai.appdna.sdk.core.ElementStyleConfig
import ai.appdna.sdk.core.BackgroundStyleConfig
import ai.appdna.sdk.core.BorderStyleConfig
import ai.appdna.sdk.core.ShadowStyleConfig
import ai.appdna.sdk.core.SpacingConfig
import ai.appdna.sdk.core.GradientConfig
import ai.appdna.sdk.core.GradientStopConfig
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

/**
 * Firestore schema types for surveys.
 *
 * Compose stability: SurveyActivity threads these
 * DTOs directly into Composables. Hot iterables (`questions`, `options`,
 * `conditions`) are migrated to `ImmutableList<T>` and the data classes are
 * annotated `@Immutable` (or `@Stable` where untyped passthrough fields remain).
 */

@Immutable
data class SurveyConfig(
    val name: String,
    val surveyType: String,
    val questions: ImmutableList<SurveyQuestion>,
    val triggerRules: SurveyTriggerRules,
    val appearance: SurveyAppearance,
    val followUpActions: SurveyFollowUpActions?
) {
    companion object {
        fun fromMap(data: Map<String, Any>): SurveyConfig? {
            return try {
                // Wrap as ImmutableList for Compose stability.
                val questions = (data["questions"] as? List<*>)?.mapNotNull { q ->
                    @Suppress("UNCHECKED_CAST")
                    SurveyQuestion.fromMap(q as? Map<String, Any> ?: return@mapNotNull null)
                }?.toImmutableList() ?: kotlinx.collections.immutable.persistentListOf()

                @Suppress("UNCHECKED_CAST")
                val triggerData = data["trigger_rules"] as? Map<String, Any> ?: return null
                @Suppress("UNCHECKED_CAST")
                val appearanceData = data["appearance"] as? Map<String, Any> ?: return null
                @Suppress("UNCHECKED_CAST")
                val followUpData = data["follow_up_actions"] as? Map<String, Any>

                SurveyConfig(
                    name = data["name"] as? String ?: "",
                    surveyType = data["survey_type"] as? String ?: "custom",
                    questions = questions,
                    triggerRules = SurveyTriggerRules.fromMap(triggerData),
                    appearance = SurveyAppearance.fromMap(appearanceData),
                    followUpActions = followUpData?.let { SurveyFollowUpActions.fromMap(it) }
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}

@Immutable
data class SurveyQuestion(
    val id: String,
    val type: String,
    val text: String,
    val required: Boolean,
    val showIf: ShowIfCondition?,
    val npsConfig: NPSConfig?,
    val csatConfig: CSATConfig?,
    val ratingConfig: RatingConfig?,
    // Options iterated by survey question Composables.
    val options: ImmutableList<QuestionOption>?,
    val emojiConfig: EmojiConfig?,
    val freeTextConfig: FreeTextConfig?,
    /**
     * Likert scale config — horizontal numeric scale with
     * optional left/right anchor labels (e.g. "Strongly Disagree" → "Strongly
     * Agree"). Read from `likert_config` (canonical) or `scale_config` (legacy
     * alias) on the question payload. Null when the question type isn't
     * `likert` / `scale`.
     */
    val likertConfig: LikertConfig? = null,
    /**
     * Optional question-level hero image rendered
     * above the question text. Mirrors iOS `SurveyQuestion.image_url`.
     */
    val imageUrl: String? = null,
) {
    companion object {
        fun fromMap(data: Map<String, Any>): SurveyQuestion {
            @Suppress("UNCHECKED_CAST")
            val showIfData = data["show_if"] as? Map<String, Any>
            @Suppress("UNCHECKED_CAST")
            val npsData = data["nps_config"] as? Map<String, Any>
            @Suppress("UNCHECKED_CAST")
            val csatData = data["csat_config"] as? Map<String, Any>
            @Suppress("UNCHECKED_CAST")
            val ratingData = data["rating_config"] as? Map<String, Any>
            @Suppress("UNCHECKED_CAST")
            val emojiData = data["emoji_config"] as? Map<String, Any>
            @Suppress("UNCHECKED_CAST")
            val freeTextData = data["free_text_config"] as? Map<String, Any>
            // Likert config — accept canonical `likert_config`
            // and the legacy `scale_config` alias.
            @Suppress("UNCHECKED_CAST")
            val likertData = (data["likert_config"] as? Map<String, Any>)
                ?: (data["scale_config"] as? Map<String, Any>)
            // Restart attempt 2 F3: prefer the
            // Firestore-canonical `choice_config.options` container, fall back
            // to the legacy flat `options` field. Mirrors iOS
            // SurveyQuestion.options resolver (SurveyConfig.swift:23-46).
            @Suppress("UNCHECKED_CAST")
            val choiceConfig = data["choice_config"] as? Map<String, Any>
            val optionsData = (choiceConfig?.get("options") as? List<*>)
                ?: (data["options"] as? List<*>)

            return SurveyQuestion(
                id = data["id"] as? String ?: "",
                type = data["type"] as? String ?: "",
                text = data["text"] as? String ?: "",
                required = data["required"] as? Boolean ?: false,
                showIf = showIfData?.let { ShowIfCondition.fromMap(it) },
                npsConfig = npsData?.let { NPSConfig(it["low_label"] as? String, it["high_label"] as? String) },
                csatConfig = csatData?.let {
                    // Read Firestore-canonical `scale` + `labels`,
                    // fall back to legacy `max_rating` + `style`.
                    @Suppress("UNCHECKED_CAST")
                    val labelsList = (it["labels"] as? List<*>)?.filterIsInstance<String>()
                    CSATConfig(
                        maxRating = (it["max_rating"] as? Number)?.toInt() ?: 5,
                        style = it["style"] as? String ?: "star",
                        scale = (it["scale"] as? Number)?.toInt(),
                        labels = labelsList,
                    )
                },
                ratingConfig = ratingData?.let {
                    // Read Firestore `max` + `icon`,
                    // fall back to legacy `max_rating` + `style`.
                    RatingConfig(
                        maxRating = (it["max_rating"] as? Number)?.toInt() ?: 5,
                        style = it["style"] as? String ?: "star",
                        max = (it["max"] as? Number)?.toInt(),
                        icon = it["icon"] as? String,
                    )
                },
                // Wrap as ImmutableList for Compose stability.
                options = optionsData?.mapNotNull { o ->
                    @Suppress("UNCHECKED_CAST")
                    val om = o as? Map<String, Any> ?: return@mapNotNull null
                    // Accept both `label` (Firestore canonical)
                    // and `text` (legacy) — prefer label, fall back to text.
                    val labelOrText = (om["label"] as? String) ?: (om["text"] as? String) ?: ""
                    QuestionOption(om["id"] as? String ?: "", labelOrText, om["icon"] as? String)
                }?.toImmutableList(),
                emojiConfig = emojiData?.let {
                    @Suppress("UNCHECKED_CAST")
                    EmojiConfig((it["emojis"] as? List<String>) ?: listOf("\uD83D\uDE21", "\uD83D\uDE15", "\uD83D\uDE10", "\uD83D\uDE0A", "\uD83D\uDE0D"))
                },
                freeTextConfig = freeTextData?.let { FreeTextConfig(it["placeholder"] as? String, (it["max_length"] as? Number)?.toInt() ?: 500) },
                // Likert scale config (numeric scale +
                // optional left/right anchor labels).
                likertConfig = likertData?.let {
                    LikertConfig(
                        min = (it["min"] as? Number)?.toInt() ?: 1,
                        max = (it["max"] as? Number)?.toInt()
                            ?: (it["scale"] as? Number)?.toInt()
                            ?: 5,
                        lowLabel = (it["low_label"] as? String)
                            ?: (it["left_label"] as? String),
                        highLabel = (it["high_label"] as? String)
                            ?: (it["right_label"] as? String),
                    )
                },
                // Question-level hero image
                imageUrl = data["image_url"] as? String,
            )
        }
    }
}

// @Stable: `answerIn: List<Any>` carries untyped values
// (mixed types from `data["answer_in"]`). Stays a plain list — Compose stability
// is downstream of SurveyQuestion which is annotated @Immutable.
@Stable
data class ShowIfCondition(val questionId: String, val answerIn: List<Any>) {
    companion object {
        fun fromMap(data: Map<String, Any>): ShowIfCondition {
            @Suppress("UNCHECKED_CAST")
            return ShowIfCondition(
                questionId = data["question_id"] as? String ?: "",
                answerIn = (data["answer_in"] as? List<*>)?.filterNotNull() ?: emptyList()
            )
        }
    }
}

@Immutable
data class NPSConfig(val lowLabel: String?, val highLabel: String?)

/**
 * CSAT (customer satisfaction) question config.
 *
 * Matches iOS `CSATConfig` shape — Firestore writes `scale`
 * (3, 5, or 7), `labels` (per-step), and `style`; legacy SDKs wrote
 * `max_rating`. Use [resolvedMax] to read the effective scale.
 */
// @Stable: `labels` is a plain List<String>. Even though
// stock List is mutable-by-interface, this leaf type is rarely re-emitted
// independently and we don't want to widen ImmutableList contract beyond the
// hot-path types.
@Stable
data class CSATConfig(
    /** Legacy SDK field. Prefer [scale] when both are set. */
    val maxRating: Int = 5,
    val style: String = "star",
    /** Firestore-canonical scale value (3, 5, or 7). Null when only legacy `max_rating` provided. */
    val scale: Int? = null,
    /** Optional per-step labels rendered under each option. */
    val labels: List<String>? = null,
) {
    /** Effective max — prefer Firestore [scale], fall back to legacy [maxRating]. */
    val resolvedMax: Int get() = scale ?: maxRating
}

/**
 * 1–N rating question config (star/heart/thumb).
 *
 * Matches iOS `RatingConfig` shape — Firestore writes `max`
 * (e.g., 5 or 10) + `icon` ("star" | "heart" | "thumb"); legacy SDKs wrote
 * `max_rating` + `style`. Use [resolvedMax] / [resolvedIcon] for the effective
 * values.
 */
@Immutable
data class RatingConfig(
    /** Legacy SDK field. Prefer [max] when both are set. */
    val maxRating: Int = 5,
    /** Legacy SDK field. Prefer [icon] when both are set. */
    val style: String = "star",
    /** Firestore-canonical max value. Null when only legacy `max_rating` provided. */
    val max: Int? = null,
    /** Firestore-canonical icon. Null when only legacy `style` provided. */
    val icon: String? = null,
) {
    /** Effective max — prefer Firestore [max], fall back to legacy [maxRating]. */
    val resolvedMax: Int get() = max ?: maxRating
    /** Effective icon — prefer Firestore [icon], fall back to legacy [style]. */
    val resolvedIcon: String get() = icon ?: style
}
@Immutable
data class QuestionOption(val id: String, val text: String, val icon: String?)
@Stable
data class EmojiConfig(val emojis: List<String> = listOf("\uD83D\uDE21", "\uD83D\uDE15", "\uD83D\uDE10", "\uD83D\uDE0A", "\uD83D\uDE0D"))
@Immutable
data class FreeTextConfig(val placeholder: String?, val maxLength: Int = 500)

/**
 * Likert scale question config. Renders a horizontal numeric
 * scale from [min]..[max] with optional left/right anchor labels (e.g.
 * "Strongly Disagree" \u2192 "Strongly Agree"). Server may serialize with `min` /
 * `max` (canonical) or `scale` (single-int max alias). Anchor labels accept
 * either `low_label`/`high_label` or `left_label`/`right_label`.
 */
@Immutable
data class LikertConfig(
    val min: Int = 1,
    val max: Int = 5,
    val lowLabel: String? = null,
    val highLabel: String? = null,
)

data class ScoreRange(val min: Int, val max: Int)

data class SurveyTriggerRules(
    val event: String,
    val conditions: List<TriggerCondition>,
    val loveScoreRange: ScoreRange?,
    val frequency: String,
    val maxDisplays: Int?,
    val delaySeconds: Int?,
    val minSessions: Int?
) {
    companion object {
        fun fromMap(data: Map<String, Any>): SurveyTriggerRules {
            val conditionsList = (data["conditions"] as? List<*>)?.mapNotNull { c ->
                @Suppress("UNCHECKED_CAST")
                val cm = c as? Map<String, Any> ?: return@mapNotNull null
                TriggerCondition(
                    field = cm["field"] as? String ?: "",
                    operator = cm["operator"] as? String ?: "eq",
                    value = cm["value"]
                )
            } ?: emptyList()

            @Suppress("UNCHECKED_CAST")
            val rangeData = data["love_score_range"] as? Map<String, Any>
            val scoreRange = rangeData?.let {
                ScoreRange(
                    min = (it["min"] as? Number)?.toInt() ?: 0,
                    max = (it["max"] as? Number)?.toInt() ?: 100
                )
            }

            return SurveyTriggerRules(
                event = data["event"] as? String ?: "",
                conditions = conditionsList,
                loveScoreRange = scoreRange,
                frequency = data["frequency"] as? String ?: "once",
                maxDisplays = (data["max_displays"] as? Number)?.toInt(),
                delaySeconds = (data["delay_seconds"] as? Number)?.toInt(),
                minSessions = (data["min_sessions"] as? Number)?.toInt()
            )
        }
    }
}

data class TriggerCondition(val field: String, val operator: String, val value: Any?)

// @Stable: SurveyAppearance is threaded into SurveyActivity
// Composables. All fields are nullable primitives or fully-immutable holders;
// the legacy `theme` is mergeable so Compose can short-circuit re-emits when
// the resolved theme is structurally equal.
@Stable
data class SurveyAppearance(
    val presentation: String,
    val theme: SurveyTheme?,
    val dismissAllowed: Boolean,
    val showProgress: Boolean,
    // Style engine integration
    val cornerRadius: Int? = null,
    // Gap #20 — question text style token
    val questionTextStyle: TextStyleConfig? = null,
    // Gap #21 — option card container style token
    val optionStyle: ElementStyleConfig? = null,
    // Rich media
    val introLottieUrl: String? = null,
    val thankyouLottieUrl: String? = null,
    val thankyouParticleEffect: ai.appdna.sdk.core.ParticleEffect? = null,
    val blurBackdrop: ai.appdna.sdk.core.BlurConfig? = null,
    val haptic: ai.appdna.sdk.core.HapticConfig? = null,
    // Configurable thank-you text for interpolation
    val thankYouText: String? = null,
    /**
     * Optional sparse dark-mode overrides for [theme].
     * Mirrors iOS `theme: ThemeSet<SurveyTheme>?` — server may serialize either
     * a flat theme object (legacy → goes into [theme] only) OR
     * `{ light: {...}, dark: {...} }` (Wave 2 → flat fields go into [theme] (the
     * light baseline) and `dark` overrides land here).
     */
    val themeDark: SurveyTheme? = null,
) {
    /**
     * Render-time theme resolver. In dark mode,
     * any field set on [themeDark] overrides the matching field on [theme];
     * unset dark fields fall back to the light value (sparse merge). In
     * light mode, returns [theme] unchanged. Mirrors iOS
     * `appearance.theme?.resolved(for: colorScheme)`.
     */
    fun resolveTheme(isDark: Boolean): SurveyTheme? {
        val light = theme ?: return null
        if (!isDark) return light
        val dark = themeDark ?: return light
        return dark.mergedOnto(light)
    }

    companion object {
        fun fromMap(data: Map<String, Any>): SurveyAppearance {
            @Suppress("UNCHECKED_CAST")
            val themeData = data["theme"] as? Map<String, Any>
            @Suppress("UNCHECKED_CAST")
            val qtsData = data["question_text_style"] as? Map<String, Any>
            @Suppress("UNCHECKED_CAST")
            val osData = data["option_style"] as? Map<String, Any>
            // Parse particle effect
            @Suppress("UNCHECKED_CAST")
            val peData = data["thankyou_particle_effect"] as? Map<String, Any>
            val particleEffect = peData?.let { p ->
                ai.appdna.sdk.core.ParticleEffect(
                    type = p["type"] as? String ?: "confetti",
                    trigger = p["trigger"] as? String ?: "on_flow_complete",
                    duration_ms = (p["duration_ms"] as? Number)?.toInt() ?: 2500,
                    intensity = p["intensity"] as? String ?: "medium",
                    colors = (p["colors"] as? List<*>)?.filterIsInstance<String>(),
                )
            }
            // Parse blur config
            @Suppress("UNCHECKED_CAST")
            val blurData = data["blur_backdrop"] as? Map<String, Any>
            val blurConfig = blurData?.let { b ->
                ai.appdna.sdk.core.BlurConfig(
                    radius = (b["radius"] as? Number)?.toFloat() ?: 0f,
                    tint = b["tint"] as? String,
                    saturation = (b["saturation"] as? Number)?.toFloat(),
                )
            }
            // Parse haptic config
            @Suppress("UNCHECKED_CAST")
            val hapticData = data["haptic"] as? Map<String, Any>
            val hapticConfig = hapticData?.let { h ->
                val triggersMap = h["triggers"] as? Map<String, Any>
                ai.appdna.sdk.core.HapticConfig(
                    enabled = h["enabled"] as? Boolean ?: false,
                    triggers = triggersMap?.let { t ->
                        ai.appdna.sdk.core.HapticTriggers(
                            on_option_select = t["on_option_select"] as? String,
                            on_button_tap = t["on_button_tap"] as? String,
                            on_form_submit = t["on_form_submit"] as? String,
                            on_success = t["on_success"] as? String,
                        )
                    } ?: ai.appdna.sdk.core.HapticTriggers(),
                )
            }
            // Parse rich-media + typography fields from BOTH
            // `appearance.theme.*` (iOS canonical location) and the legacy
            // `appearance.*` location, preferring theme.* when set.
            // Gradient parser (theme.gradient / theme.button_gradient)
            fun parseGradient(raw: Any?): ai.appdna.sdk.core.GradientConfig? {
                @Suppress("UNCHECKED_CAST")
                val m = raw as? Map<String, Any> ?: return null
                val stops = (m["stops"] as? List<*>)?.mapNotNull { s ->
                    @Suppress("UNCHECKED_CAST")
                    val sm = s as? Map<String, Any> ?: return@mapNotNull null
                    ai.appdna.sdk.core.GradientStopConfig(
                        color = sm["color"] as? String ?: "#000000",
                        position = (sm["position"] as? Number)?.toDouble() ?: 0.0,
                    )
                }
                return ai.appdna.sdk.core.GradientConfig(
                    type = m["type"] as? String,
                    angle = (m["angle"] as? Number)?.toDouble(),
                    stops = stops,
                )
            }

            fun parseHaptic(raw: Any?): ai.appdna.sdk.core.HapticConfig? {
                @Suppress("UNCHECKED_CAST")
                val h = raw as? Map<String, Any> ?: return null
                @Suppress("UNCHECKED_CAST")
                val triggersMap = h["triggers"] as? Map<String, Any>
                // Read all 8 trigger fields (iOS HapticTriggers parity).
                return ai.appdna.sdk.core.HapticConfig(
                    enabled = h["enabled"] as? Boolean ?: false,
                    triggers = triggersMap?.let { t ->
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

            fun parseParticle(raw: Any?, defaultTrigger: String): ai.appdna.sdk.core.ParticleEffect? {
                @Suppress("UNCHECKED_CAST")
                val p = raw as? Map<String, Any> ?: return null
                return ai.appdna.sdk.core.ParticleEffect(
                    type = p["type"] as? String ?: "confetti",
                    trigger = p["trigger"] as? String ?: defaultTrigger,
                    duration_ms = (p["duration_ms"] as? Number)?.toInt() ?: 2500,
                    intensity = p["intensity"] as? String ?: "medium",
                    colors = (p["colors"] as? List<*>)?.filterIsInstance<String>(),
                )
            }

            fun parseBlur(raw: Any?): ai.appdna.sdk.core.BlurConfig? {
                @Suppress("UNCHECKED_CAST")
                val b = raw as? Map<String, Any> ?: return null
                return ai.appdna.sdk.core.BlurConfig(
                    radius = (b["radius"] as? Number)?.toFloat() ?: 0f,
                    tint = b["tint"] as? String,
                    saturation = (b["saturation"] as? Number)?.toFloat(),
                )
            }

            // Server may serialize theme as either:
            //   1. flat: `theme: { background_color: ..., ... }` (legacy)
            //   2. themed: `theme: { light: {...}, dark: {...} }` (Wave 2)
            // In case (2), unwrap `theme.light` as the baseline and keep
            // `theme.dark` for the sparse override. In case (1), use `theme.*`
            // directly as the light baseline. Mirrors iOS `ThemeSet`'s
            // back-compat decoder in `Core/ThemeSet.swift`.
            @Suppress("UNCHECKED_CAST")
            val themeLightMap: Map<String, Any>? = themeData?.let {
                (it["light"] as? Map<String, Any>) ?: it
            }
            @Suppress("UNCHECKED_CAST")
            val themeDarkMap: Map<String, Any>? = themeData?.let { it["dark"] as? Map<String, Any> }

            // Build a SurveyTheme from a raw theme-shape map. Reused for both
            // the light baseline and the dark sparse override. The dark variant
            // does NOT pull from `appearance.*` (only its own keys) so that
            // unset fields cleanly fall back to light at render time.
            fun buildTheme(raw: Map<String, Any>, allowAppearanceFallback: Boolean): SurveyTheme {
                val tIntroLottie = raw["intro_lottie_url"] as? String
                    ?: if (allowAppearanceFallback) data["intro_lottie_url"] as? String else null
                val tThankyouLottie = raw["thankyou_lottie_url"] as? String
                    ?: if (allowAppearanceFallback) data["thankyou_lottie_url"] as? String else null
                val tParticle = parseParticle(raw["thankyou_particle_effect"], "on_flow_complete")
                    ?: if (allowAppearanceFallback) parseParticle(data["thankyou_particle_effect"], "on_flow_complete") else null
                val tBlur = parseBlur(raw["blur_backdrop"])
                    ?: if (allowAppearanceFallback) parseBlur(data["blur_backdrop"]) else null
                val tHaptic = parseHaptic(raw["haptic"])
                    ?: if (allowAppearanceFallback) parseHaptic(data["haptic"]) else null
                val tThankYouText = raw["thank_you_text"] as? String
                    ?: if (allowAppearanceFallback) data["thank_you_text"] as? String else null

                return SurveyTheme(
                    backgroundColor = raw["background_color"] as? String,
                    textColor = raw["text_color"] as? String,
                    accentColor = raw["accent_color"] as? String,
                    buttonColor = raw["button_color"] as? String,
                    fontFamily = raw["font_family"] as? String,
                    buttonTextColor = raw["button_text_color"] as? String,
                    gradient = parseGradient(raw["gradient"]),
                    buttonGradient = parseGradient(raw["button_gradient"]),
                    textAlign = raw["text_align"] as? String,
                    questionFontSize = (raw["question_font_size"] as? Number)?.toDouble(),
                    fontWeight = raw["font_weight"] as? String,
                    introLottieUrl = tIntroLottie,
                    thankyouLottieUrl = tThankyouLottie,
                    thankyouParticleEffect = tParticle,
                    blurBackdrop = tBlur,
                    haptic = tHaptic,
                    thankYouText = tThankYouText,
                )
            }

            return SurveyAppearance(
                presentation = data["presentation"] as? String ?: "bottom_sheet",
                theme = themeLightMap?.let { buildTheme(it, allowAppearanceFallback = true) },
                themeDark = themeDarkMap?.let { buildTheme(it, allowAppearanceFallback = false) },
                dismissAllowed = data["dismiss_allowed"] as? Boolean ?: true,
                showProgress = data["show_progress"] as? Boolean ?: false,
                cornerRadius = (data["corner_radius"] as? Number)?.toInt(),
                questionTextStyle = qtsData?.let { parseTextStyleConfig(it) },
                optionStyle = osData?.let { parseElementStyleConfig(it) },
                introLottieUrl = data["intro_lottie_url"] as? String,
                thankyouLottieUrl = data["thankyou_lottie_url"] as? String,
                thankyouParticleEffect = particleEffect,
                blurBackdrop = blurConfig,
                haptic = hapticConfig,
                thankYouText = data["thank_you_text"] as? String,
            )
        }

        @Suppress("UNCHECKED_CAST")
        private fun parseTextStyleConfig(data: Map<String, Any>): TextStyleConfig {
            return TextStyleConfig(
                font_family = data["font_family"] as? String,
                font_size = (data["font_size"] as? Number)?.toDouble(),
                font_weight = (data["font_weight"] as? Number)?.toInt(),
                color = data["color"] as? String,
                alignment = data["alignment"] as? String,
                line_height = (data["line_height"] as? Number)?.toDouble(),
                letter_spacing = (data["letter_spacing"] as? Number)?.toDouble(),
                opacity = (data["opacity"] as? Number)?.toDouble(),
            )
        }

        @Suppress("UNCHECKED_CAST")
        private fun parseElementStyleConfig(data: Map<String, Any>): ElementStyleConfig {
            val bgData = data["background"] as? Map<String, Any>
            val borderData = data["border"] as? Map<String, Any>
            val shadowData = data["shadow"] as? Map<String, Any>
            val paddingData = data["padding"] as? Map<String, Any>
            return ElementStyleConfig(
                background = bgData?.let {
                    val stopsRaw = (it["gradient"] as? Map<String, Any>)
                        ?.let { g -> (g["stops"] as? List<*>)?.mapNotNull { s ->
                            val sm = s as? Map<String, Any> ?: return@mapNotNull null
                            GradientStopConfig(
                                color = sm["color"] as? String ?: "#000000",
                                position = (sm["position"] as? Number)?.toDouble() ?: 0.0,
                            )
                        }}
                    val gradData = it["gradient"] as? Map<String, Any>
                    BackgroundStyleConfig(
                        type = it["type"] as? String,
                        color = it["color"] as? String,
                        gradient = gradData?.let { g ->
                            GradientConfig(
                                type = g["type"] as? String,
                                angle = (g["angle"] as? Number)?.toDouble(),
                                stops = stopsRaw,
                            )
                        },
                        image_url = it["image_url"] as? String,
                        image_fit = it["image_fit"] as? String,
                        overlay = it["overlay"] as? String,
                    )
                },
                border = borderData?.let {
                    BorderStyleConfig(
                        width = (it["width"] as? Number)?.toDouble(),
                        color = it["color"] as? String,
                        style = it["style"] as? String,
                        radius = (it["radius"] as? Number)?.toDouble(),
                        radius_top_left = (it["radius_top_left"] as? Number)?.toDouble(),
                        radius_top_right = (it["radius_top_right"] as? Number)?.toDouble(),
                        radius_bottom_left = (it["radius_bottom_left"] as? Number)?.toDouble(),
                        radius_bottom_right = (it["radius_bottom_right"] as? Number)?.toDouble(),
                    )
                },
                shadow = shadowData?.let {
                    ShadowStyleConfig(
                        x = (it["x"] as? Number)?.toDouble(),
                        y = (it["y"] as? Number)?.toDouble(),
                        blur = (it["blur"] as? Number)?.toDouble(),
                        spread = (it["spread"] as? Number)?.toDouble(),
                        color = it["color"] as? String,
                    )
                },
                padding = paddingData?.let {
                    SpacingConfig(
                        top = (it["top"] as? Number)?.toDouble(),
                        right = (it["right"] as? Number)?.toDouble(),
                        bottom = (it["bottom"] as? Number)?.toDouble(),
                        left = (it["left"] as? Number)?.toDouble(),
                    )
                },
                corner_radius = (data["corner_radius"] as? Number)?.toDouble(),
                opacity = (data["opacity"] as? Number)?.toDouble(),
            )
        }
    }
}

/**
 * Survey theme tokens. Mirrors iOS `Feedback/SurveyConfig.swift` `SurveyTheme`.
 *
 * Adds button text color, gradients, typography overrides,
 * intro/thank-you Lottie URLs, particle effect, blur backdrop, haptic config,
 * and configurable thank-you text. Server writes these under
 * `appearance.theme.*` (mirroring iOS) — see
 * [SurveyAppearance.fromMap] for parser entries.
 *
 * Implements [ai.appdna.sdk.core.SparseMergeable]
 * so a `dark` variant on the same shape can sparse-override any field at
 * render time — `theme.dark` lives on [SurveyAppearance] and is resolved by
 * [SurveyAppearance.resolveTheme]. Mirrors iOS `SurveyTheme: SparseMergeable`.
 */
@Immutable
data class SurveyTheme(
    val backgroundColor: String? = null,
    val textColor: String? = null,
    val accentColor: String? = null,
    val buttonColor: String? = null,
    val fontFamily: String? = null,
    // Button text color (iOS parity)
    val buttonTextColor: String? = null,
    // Gradients
    val gradient: ai.appdna.sdk.core.GradientConfig? = null,
    val buttonGradient: ai.appdna.sdk.core.GradientConfig? = null,
    // Typography
    val textAlign: String? = null,            // "left" | "center" | "right"
    val questionFontSize: Double? = null,
    val fontWeight: String? = null,            // "normal" | "medium" | "semibold" | "bold"
    // Rich media (mirrors iOS SurveyTheme)
    val introLottieUrl: String? = null,
    val thankyouLottieUrl: String? = null,
    val thankyouParticleEffect: ai.appdna.sdk.core.ParticleEffect? = null,
    val blurBackdrop: ai.appdna.sdk.core.BlurConfig? = null,
    val haptic: ai.appdna.sdk.core.HapticConfig? = null,
    // Configurable thank-you text
    val thankYouText: String? = null,
) : ai.appdna.sdk.core.SparseMergeable<SurveyTheme> {
    /**
     * Sparse-merge self (overrides) onto baseline.
     * Any field set on self wins; otherwise fall back to the baseline value.
     * Mirrors iOS `SurveyTheme.merged(onto:)`.
     */
    override fun mergedOnto(baseline: SurveyTheme): SurveyTheme = SurveyTheme(
        backgroundColor = backgroundColor ?: baseline.backgroundColor,
        textColor = textColor ?: baseline.textColor,
        accentColor = accentColor ?: baseline.accentColor,
        buttonColor = buttonColor ?: baseline.buttonColor,
        fontFamily = fontFamily ?: baseline.fontFamily,
        buttonTextColor = buttonTextColor ?: baseline.buttonTextColor,
        gradient = gradient ?: baseline.gradient,
        buttonGradient = buttonGradient ?: baseline.buttonGradient,
        textAlign = textAlign ?: baseline.textAlign,
        questionFontSize = questionFontSize ?: baseline.questionFontSize,
        fontWeight = fontWeight ?: baseline.fontWeight,
        introLottieUrl = introLottieUrl ?: baseline.introLottieUrl,
        thankyouLottieUrl = thankyouLottieUrl ?: baseline.thankyouLottieUrl,
        thankyouParticleEffect = thankyouParticleEffect ?: baseline.thankyouParticleEffect,
        blurBackdrop = blurBackdrop ?: baseline.blurBackdrop,
        haptic = haptic ?: baseline.haptic,
        thankYouText = thankYouText ?: baseline.thankYouText,
    )
}

data class SurveyFollowUpActions(
    val onPositive: FollowUpAction?,
    val onNegative: FollowUpAction?,
    val onNeutral: FollowUpAction?
) {
    companion object {
        fun fromMap(data: Map<String, Any>): SurveyFollowUpActions {
            @Suppress("UNCHECKED_CAST")
            val pos = data["on_positive"] as? Map<String, Any>
            @Suppress("UNCHECKED_CAST")
            val neg = data["on_negative"] as? Map<String, Any>
            @Suppress("UNCHECKED_CAST")
            val neut = data["on_neutral"] as? Map<String, Any>
            return SurveyFollowUpActions(
                onPositive = pos?.let { FollowUpAction(it["action"] as? String ?: "", it["message"] as? String) },
                onNegative = neg?.let { FollowUpAction(it["action"] as? String ?: "", it["message"] as? String) },
                onNeutral = neut?.let { FollowUpAction(it["action"] as? String ?: "", it["message"] as? String) }
            )
        }
    }
}

data class FollowUpAction(val action: String, val message: String? = null)

data class SurveyAnswer(val questionId: String, val answer: Any) {
    fun toMap(): Map<String, Any> = mapOf("question_id" to questionId, "answer" to answer)
}

enum class SurveySentiment { POSITIVE, NEGATIVE, NEUTRAL }
