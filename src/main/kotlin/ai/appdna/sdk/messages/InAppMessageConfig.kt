package ai.appdna.sdk.messages

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable

// Parse `blur_backdrop` from EITHER a bare Boolean (the console/Zod toggle shape production
// uses: `blur_backdrop: true`) OR the full {radius,tint,saturation} object, mirroring iOS BlurConfig.init
// (true → radius 30 / saturation 1.8; false/absent → null = no blur). The Boolean form was previously
// dropped (`as? Map` failed), so the blur backdrop feature was entirely INERT on Android.
private fun parseBlurBackdrop(value: Any?): ai.appdna.sdk.core.BlurConfig? = when (value) {
    is Boolean -> if (value) ai.appdna.sdk.core.BlurConfig(radius = 30f, tint = null, saturation = 1.8f) else null
    is Map<*, *> -> {
        @Suppress("UNCHECKED_CAST")
        val b = value as Map<String, Any>
        ai.appdna.sdk.core.BlurConfig(
            radius = (b["radius"] as? Number)?.toFloat() ?: 0f,
            tint = b["tint"] as? String,
            saturation = (b["saturation"] as? Number)?.toFloat(),
        )
    }
    else -> null
}

/**
 * In-app message config models matching Firestore schema.
 *
 * Compose stability: MessageConfig is read by
 * InAppMessageRenderer Composables. Annotated `@Stable` (rather than
 * `@Immutable`) because [MessageContent.cta_icon] / [MessageContent.secondary_cta_icon]
 * are untyped `Any?` (caller-supplied icon refs / emoji / IconReference maps)
 * and [TriggerCondition.value] is `Any?` — we don't widen those to a typed shape
 * here. There are no list fields to migrate on this surface
 * (`MessageConfig.actions` does not exist on Android — the server schema only
 * carries a single `cta_action` + a `secondary_cta_text`; iOS-listed
 * `MessageConfig.content_blocks` likewise isn't in the Android schema).
 */

@Stable
data class MessageRoot(
    val version: Int = 1,
    val messages: Map<String, MessageConfig> = emptyMap(),
)

@Stable
data class MessageConfig(
    val name: String,
    val message_type: MessageType,
    val content: MessageContent,
    val trigger_rules: TriggerRules,
    val priority: Int = 0,
    val start_date: String? = null,
    val end_date: String? = null,
)

enum class MessageType(val value: String) {
    BANNER("banner"),
    MODAL("modal"),
    FULLSCREEN("fullscreen"),
    TOOLTIP("tooltip");

    companion object {
        fun fromString(value: String): MessageType =
            entries.firstOrNull { it.value == value } ?: MODAL
    }
}

@Stable
data class MessageContent(
    val title: String? = null,
    val body: String? = null,
    val image_url: String? = null,
    val cta_text: String? = null,
    val cta_action: CTAAction? = null,
    val dismiss_text: String? = null,
    val background_color: String? = null,
    val banner_position: String? = null, // "top" | "bottom"
    val auto_dismiss_seconds: Int? = null,
    // Styling fields
    val text_color: String? = null,
    val button_color: String? = null,
    // Button text color + button corner radius (iOS parity)
    val button_text_color: String? = null,
    val button_corner_radius: Int? = null,
    val corner_radius: Int? = null,
    val secondary_cta_text: String? = null,
    // Typography parity (font family + title/body sizes)
    val font_family: String? = null,
    val title_font_size: Double? = null,
    val body_font_size: Double? = null,
    // Rich media fields
    val lottie_url: String? = null,
    val rive_url: String? = null,
    // Rive state machine name (iOS parity)
    val rive_state_machine: String? = null,
    val video_url: String? = null,
    val video_thumbnail_url: String? = null,
    val cta_icon: Any? = null,
    val secondary_cta_icon: Any? = null,
    val haptic: ai.appdna.sdk.core.HapticConfig? = null,
    val particle_effect: ai.appdna.sdk.core.ParticleEffect? = null,
    val blur_backdrop: ai.appdna.sdk.core.BlurConfig? = null,
    // the console's "Allow Dismiss" switch and "Dismiss Delay (seconds)". Absent → dismissable at
    // once (the behaviour before these were read). Read through [MessageDismissPolicy], never directly.
    val dismiss_allowed: Boolean? = null,
    val dismiss_delay: Double? = null,
    // Sparse dark-mode overrides
    val dark: MessageContentDark? = null,
)

/**
 * WHEN the user may dismiss an in-app message: the close control (the ✕ and `dismiss_text`), a
 * tap on the backdrop, and the system back button.
 *
 * - `dismiss_allowed == false` → never. The message leaves only through an authored button (the CTA or
 *   the secondary CTA), or its `auto_dismiss_seconds` timer — what the console preview shows, where the
 *   ✕ is hidden and both CTAs stay. A message with NEITHER button stays dismissable: the switch cannot
 *   make a message the user has no way out of.
 * - `dismiss_delay = N` → only once N seconds have passed since the message appeared; the ✕ is not
 *   drawn before then.
 * - absent / wrong type → dismissable at once.
 *
 * ONE rule for all four message types. iOS `MessageDismissPolicy` is the same rule;
 * `packages/sdk-shared-fixtures/dto_parsing/message_dismiss_policy.fixture.json` pins both.
 */
data class MessageDismissPolicy(val allowed: Boolean, val delaySeconds: Double) {
    /** May the user dismiss the message [elapsedSeconds] after it appeared? */
    fun userMayDismiss(elapsedSeconds: Double): Boolean = allowed && elapsedSeconds >= delaySeconds

    /** The state a view starts in. */
    val dismissableAtOpen: Boolean get() = userMayDismiss(0.0)

    /** Seconds until the close control appears, or null when it never will (or already has). */
    val unlockAfterSeconds: Double? get() = if (allowed && delaySeconds > 0.0) delaySeconds else null

    companion object {
        fun of(content: MessageContent?): MessageDismissPolicy {
            val d = content?.dismiss_delay ?: 0.0
            // Never a trap: a message with no button at all (no CTA, no secondary CTA) stays dismissable
            // whatever the switch says — otherwise a fullscreen message would hold the app until it is killed.
            val hasAuthoredExit = !content?.cta_text.isNullOrBlank() || !content?.secondary_cta_text.isNullOrBlank()
            return MessageDismissPolicy(
                allowed = (content?.dismiss_allowed ?: true) || !hasAuthoredExit,
                delaySeconds = if (d.isFinite()) maxOf(0.0, d) else 0.0,
            )
        }
    }
}

/**
 * Render-time resolver. In dark mode, any field
 * set on [MessageContent.dark] overrides the matching field above; everything
 * else falls back to the light (default) value. In light mode or when no
 * `dark` overrides exist, returns this unchanged.
 *
 * Mirrors iOS `MessageContent.resolved(for: ColorScheme)` in
 * `InAppMessaging/MessageConfig.swift`.
 */
fun MessageContent.resolved(isDark: Boolean): MessageContent {
    if (!isDark) return this
    val d = this.dark ?: return this
    return this.copy(
        image_url = d.image_url ?: image_url,
        background_color = d.background_color ?: background_color,
        text_color = d.text_color ?: text_color,
        button_color = d.button_color ?: button_color,
        button_text_color = d.button_text_color ?: button_text_color,
        button_corner_radius = d.button_corner_radius ?: button_corner_radius,
        corner_radius = d.corner_radius ?: corner_radius,
        // Typography stays light-only by design — messages don't vary
        // fonts or sizes between modes (iOS parity).
        lottie_url = d.lottie_url ?: lottie_url,
        rive_url = d.rive_url ?: rive_url,
        video_url = d.video_url ?: video_url,
        video_thumbnail_url = d.video_thumbnail_url ?: video_thumbnail_url,
        cta_icon = d.cta_icon ?: cta_icon,
        secondary_cta_icon = d.secondary_cta_icon ?: secondary_cta_icon,
        particle_effect = d.particle_effect ?: particle_effect,
        blur_backdrop = d.blur_backdrop ?: blur_backdrop,
        // Drop the dark block on the resolved copy so downstream consumers
        // can't accidentally re-resolve.
        dark = null,
    )
}

/**
 * Dark-mode overrides for [MessageContent].
 * Sparse — only fields set here override the matching light-mode field on the
 * parent. Mirrors iOS `MessageContentDark` in `InAppMessaging/MessageConfig.swift`.
 */
@Stable
data class MessageContentDark(
    // Colors
    val background_color: String? = null,
    val text_color: String? = null,
    val button_color: String? = null,
    val button_text_color: String? = null,
    // Corner radii
    val button_corner_radius: Int? = null,
    val corner_radius: Int? = null,
    // Themed assets
    val image_url: String? = null,
    val lottie_url: String? = null,
    val rive_url: String? = null,
    val video_url: String? = null,
    val video_thumbnail_url: String? = null,
    val cta_icon: Any? = null,
    val secondary_cta_icon: Any? = null,
    // Rich effects
    val particle_effect: ai.appdna.sdk.core.ParticleEffect? = null,
    val blur_backdrop: ai.appdna.sdk.core.BlurConfig? = null,
)

@Immutable
data class CTAAction(
    val type: String, // "dismiss", "deep_link", "open_url", "share", "unknown" — see MessageConfigParser.parseCTAAction
    val url: String? = null,
    /** `share` only: the text the share sheet carries; [url] is its optional link. */
    val text: String? = null,
) {
    /** The link a tap opens: a `deep_link` / `open_url` with a link, else null (the tap only dismisses). */
    val linkToOpen: String?
        get() = if (type == "deep_link" || type == "open_url") url else null

    /**
     * What a `share` CTA shares: [text] and [url], trimmed, through the SDK's one share rule. Null for
     * any other type, and for a share with nothing in it — which then only dismisses, like `dismiss`.
     */
    internal val sharePayload: ai.appdna.sdk.core.ShareAction.SharePayload?
        get() = if (type == ai.appdna.sdk.core.ShareAction.ACTION_NAME) ai.appdna.sdk.core.ShareAction.payload(text, url) else null
}

/**
 * What a message CTA does after the tap is tracked, ONE implementation for both presenters
 * ([MessageManager] and [PendingMessageListener]). The dialog closes itself after the CTA callback.
 *
 *  - `share` → the share sheet, started from [activity] (the message is closing over it; the sheet
 *    comes up on the host). Nothing to share → nothing opened: the tap only dismisses.
 *  - `deep_link` / `open_url` with a link → that link, scheme-checked.
 *  - anything else (`dismiss`, `unknown`, a link type with no link) → nothing extra.
 */
internal object MessageCTADispatch {
    fun perform(activity: android.app.Activity, action: CTAAction?, logTag: String) {
        if (action?.type == ai.appdna.sdk.core.ShareAction.ACTION_NAME) {
            val payload = action.sharePayload
            if (payload == null) {
                ai.appdna.sdk.Log.warning(
                    "$logTag a message CTA is configured to share but has neither text nor a link; it only dismisses.",
                )
                return
            }
            ai.appdna.sdk.core.ShareAction.open(activity, payload)
            return
        }
        val url = action?.linkToOpen ?: return
        // Config-driven URL — scheme-checked before the OS sees it.
        val uri = ai.appdna.sdk.core.URLSafety.sanitized(url, activity) ?: return
        try {
            activity.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri))
        } catch (e: Throwable) {
            ai.appdna.sdk.Log.warning("$logTag open URL failed: ${e.message}")
        }
    }
}

// @Stable, not @Immutable: TriggerCondition.value is `Any?`
// (passthrough scalar/object). The `conditions` list is short-lived (evaluated
// once per message check) and not a Compose hot path, so we keep stock `List`
// rather than widen the API to ImmutableList.
@Stable
data class TriggerRules(
    val event: String,
    val conditions: List<TriggerCondition>? = null,
    // Default `once` (NOT `every_time`) matches
    // iOS MessageManager.swift:58 `config.trigger_rules?.frequency ?? .once`.
    // A message authored with no `frequency` field shipped from the console
    // would show every event-fire on Android vs once on iOS — inflating
    // Android impressions and breaking message-volume parity.
    val frequency: String = "once", // "once", "once_per_session", "every_time", "max_times"
    val max_displays: Int? = null,
    val delay_seconds: Int? = null,
)

@Stable
data class TriggerCondition(
    val field: String,
    val operator: String, // "eq", "gte", "lte", "gt", "lt", "contains"
    val value: Any? = null,
)

// MARK: - Parsing helpers

internal object MessageConfigParser {

    @Suppress("UNCHECKED_CAST")
    fun parseMessages(rawData: Map<String, Any>): Map<String, MessageConfig> {
        // Unwrap the `{messages:{id:cfg}}` / `{version,updated_at,messages:{...}}` wrapper if present
        // (mega-doc + disk-cache shape), else treat the map as a flat {id:cfg} (iOS parity — its
        // parseMessages does `data["messages"] ?? data`). Without this, cold-start / mega-doc restore
        // parsed the wrapper as a single bogus message and lost the real ones.
        val data = (rawData["messages"] as? Map<String, Any>) ?: rawData
        val parsed = mutableMapOf<String, MessageConfig>()
        for ((key, value) in data) {
            if (value is Map<*, *>) {
                try {
                    val map = value as Map<String, Any>
                    parsed[key] = parseMessage(map)
                } catch (_: Exception) {}
            }
        }
        return parsed
    }

    /**
     * Parse a single in-app-message config map (e.g. an
     * experiment treatment `payload`) into a typed [MessageConfig], using the
     * SAME parser the live config path uses. Returns null on failure → caller
     * falls back to the active entity.
     */
    fun parseSingleMessage(data: Map<String, Any>): MessageConfig? {
        return try {
            parseMessage(data)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * A message CTA's action. The published shape is `{type: deep_link|open_url|dismiss, url}`. Also read, for
     * configs written before the server published that shape: `value` in place of `url` (`url` wins); type `url`,
     * which is `open_url`; a bare string — a type, or a link to open as a deep link when it carries `://`.
     * iOS `CTAAction.init(from:)` follows the same rule. A type this build does not know is `unknown`, as on iOS,
     * and only dismisses.
     */
    internal fun parseCTAAction(raw: Any?): CTAAction? {
        fun normType(t: String): String = when (val s = t.trim()) {
            "url" -> "open_url"
            "dismiss", "deep_link", "open_url", ai.appdna.sdk.core.ShareAction.ACTION_NAME -> s
            else -> "unknown"
        }
        fun link(v: Any?): String? = (v as? String)?.trim()?.takeIf { it.isNotEmpty() }
        return when (raw) {
            null -> null
            is String -> {
                val s = raw.trim()
                if (s.contains("://")) CTAAction(type = "deep_link", url = s) else CTAAction(type = normType(s))
            }
            is Map<*, *> -> CTAAction(
                type = (raw["type"] as? String)?.let(::normType) ?: "dismiss",
                url = link(raw["url"]) ?: link(raw["value"]),
                // `share`'s text. Kept raw: the share rule trims it, and a blank one is absent.
                text = raw["text"] as? String,
            )
            else -> null
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseMessage(map: Map<String, Any>): MessageConfig {
        val contentMap = map["content"] as? Map<String, Any> ?: emptyMap()
        val content = MessageContent(
            title = contentMap["title"] as? String,
            body = contentMap["body"] as? String,
            image_url = contentMap["image_url"] as? String,
            cta_text = contentMap["cta_text"] as? String,
            cta_action = parseCTAAction(contentMap["cta_action"]),
            dismiss_text = contentMap["dismiss_text"] as? String,
            background_color = contentMap["background_color"] as? String,
            banner_position = contentMap["banner_position"] as? String,
            auto_dismiss_seconds = (contentMap["auto_dismiss_seconds"] as? Number)?.toInt(),
            // wrong type → null → the default, as iOS's `@LenientScalar` reads it.
            dismiss_allowed = contentMap["dismiss_allowed"] as? Boolean,
            dismiss_delay = (contentMap["dismiss_delay"] as? Number)?.toDouble(),
            text_color = contentMap["text_color"] as? String,
            button_color = contentMap["button_color"] as? String,
            //
            button_text_color = contentMap["button_text_color"] as? String,
            button_corner_radius = (contentMap["button_corner_radius"] as? Number)?.toInt(),
            corner_radius = (contentMap["corner_radius"] as? Number)?.toInt(),
            secondary_cta_text = contentMap["secondary_cta_text"] as? String,
            // Typography
            font_family = contentMap["font_family"] as? String,
            title_font_size = (contentMap["title_font_size"] as? Number)?.toDouble(),
            body_font_size = (contentMap["body_font_size"] as? Number)?.toDouble(),
            // Rich media fields
            lottie_url = contentMap["lottie_url"] as? String,
            rive_url = contentMap["rive_url"] as? String,
            //
            rive_state_machine = contentMap["rive_state_machine"] as? String,
            video_url = contentMap["video_url"] as? String,
            video_thumbnail_url = contentMap["video_thumbnail_url"] as? String,
            cta_icon = contentMap["cta_icon"],
            secondary_cta_icon = contentMap["secondary_cta_icon"],
            haptic = (contentMap["haptic"] as? Map<String, Any>)?.let { h ->
                @Suppress("UNCHECKED_CAST")
                val triggersMap = h["triggers"] as? Map<String, Any>
                ai.appdna.sdk.core.HapticConfig(
                    enabled = h["enabled"] as? Boolean ?: false,
                    triggers = triggersMap?.let { t ->
                        ai.appdna.sdk.core.HapticTriggers(
                            on_button_tap = t["on_button_tap"] as? String,
                            on_success = t["on_success"] as? String,
                        )
                    } ?: ai.appdna.sdk.core.HapticTriggers(),
                )
            },
            particle_effect = (contentMap["particle_effect"] as? Map<String, Any>)?.let { p ->
                ai.appdna.sdk.core.ParticleEffect(
                    type = p["type"] as? String ?: "confetti",
                    trigger = p["trigger"] as? String ?: "on_appear",
                    duration_ms = (p["duration_ms"] as? Number)?.toInt() ?: 2500,
                    intensity = p["intensity"] as? String ?: "medium",
                    colors = (p["colors"] as? List<*>)?.filterIsInstance<String>(),
                )
            },
            blur_backdrop = parseBlurBackdrop(contentMap["blur_backdrop"]),
            // Dark mode sparse overrides
            dark = (contentMap["dark"] as? Map<String, Any>)?.let { d ->
                MessageContentDark(
                    background_color = d["background_color"] as? String,
                    text_color = d["text_color"] as? String,
                    button_color = d["button_color"] as? String,
                    button_text_color = d["button_text_color"] as? String,
                    button_corner_radius = (d["button_corner_radius"] as? Number)?.toInt(),
                    corner_radius = (d["corner_radius"] as? Number)?.toInt(),
                    image_url = d["image_url"] as? String,
                    lottie_url = d["lottie_url"] as? String,
                    rive_url = d["rive_url"] as? String,
                    video_url = d["video_url"] as? String,
                    video_thumbnail_url = d["video_thumbnail_url"] as? String,
                    cta_icon = d["cta_icon"],
                    secondary_cta_icon = d["secondary_cta_icon"],
                    particle_effect = (d["particle_effect"] as? Map<String, Any>)?.let { p ->
                        ai.appdna.sdk.core.ParticleEffect(
                            type = p["type"] as? String ?: "confetti",
                            trigger = p["trigger"] as? String ?: "on_appear",
                            duration_ms = (p["duration_ms"] as? Number)?.toInt() ?: 2500,
                            intensity = p["intensity"] as? String ?: "medium",
                            colors = (p["colors"] as? List<*>)?.filterIsInstance<String>(),
                        )
                    },
                    blur_backdrop = parseBlurBackdrop(d["blur_backdrop"]),
                )
            },
        )

        val rulesMap = map["trigger_rules"] as? Map<String, Any> ?: emptyMap()
        val conditions = (rulesMap["conditions"] as? List<Map<String, Any>>)?.map {
            TriggerCondition(
                field = it["field"] as? String ?: "",
                operator = it["operator"] as? String ?: "eq",
                value = it["value"],
            )
        }
        val triggerRules = TriggerRules(
            event = rulesMap["event"] as? String ?: "",
            conditions = conditions,
            // Default `once` matches iOS
            // `triggerRules.frequency ?? .once` at MessageManager.swift:56,133.
            // Was `every_time` → console-published messages with no frequency
            // configured showed on EVERY event-match on Android while iOS
            // showed them exactly once per user. Same message diverged
            // visibly between platforms after the second trigger fire.
            frequency = rulesMap["frequency"] as? String ?: "once",
            max_displays = (rulesMap["max_displays"] as? Number)?.toInt(),
            delay_seconds = (rulesMap["delay_seconds"] as? Number)?.toInt(),
        )

        return MessageConfig(
            name = map["name"] as? String ?: "",
            message_type = MessageType.fromString(map["message_type"] as? String ?: "modal"),
            content = content,
            trigger_rules = triggerRules,
            priority = (map["priority"] as? Number)?.toInt() ?: 0,
            start_date = map["start_date"] as? String,
            end_date = map["end_date"] as? String,
        )
    }
}
