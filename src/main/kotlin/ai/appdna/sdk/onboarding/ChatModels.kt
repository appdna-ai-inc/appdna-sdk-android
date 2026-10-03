package ai.appdna.sdk.onboarding

/**
 * Interactive Chat Config Models
 */

data class ChatConfig(
    val max_user_turns: Int? = null,
    val min_user_turns: Int? = null,
    val turn_limit_behavior: String? = null, // "hard" | "soft"
    val persona: ChatPersona? = null,
    val auto_messages: List<ChatAutoMessage>? = null,
    val completion_message: ChatCompletionMessage? = null,
    val completion_cta_text: String? = null,
    val completion_button: ChatCompletionButton? = null,
    val quick_replies: List<ChatQuickReply>? = null,
    val turn_actions: List<ChatTurnAction>? = null,
    val input_placeholder: String? = null,
    val input_max_length: Int? = null,
    val webhook: ai.appdna.sdk.onboarding.StepHookConfig? = null,
    val style: ChatStyleConfig? = null
) {
    val resolvedMaxTurns: Int get() = max_user_turns ?: 5
    val resolvedMinTurns: Int get() = min_user_turns ?: 1
    val isHardLimit: Boolean get() = turn_limit_behavior != "soft"
}

/**
 * Completion CTA button styling. Mirrors the regular CTA button block
 * shape — product users edit both with the same controls. Any nil field
 * falls back to the chat theme user bubble colors.
 */
data class ChatCompletionButton(
    val variant: String? = null, // "primary" | "secondary" | "outline" | "text"
    val bg_color: String? = null,
    val text_color: String? = null,
    val button_corner_radius: Double? = null,
    val button_height: Double? = null,
    val style: ChatCompletionButtonStyle? = null
)

data class ChatCompletionButtonStyle(
    val font_size: Double? = null,
    val font_weight: Double? = null
)

data class ChatPersona(
    val name: String? = null,
    val role: String? = null,
    val avatar_url: String? = null
)

data class ChatAutoMessage(
    val id: String,
    val turn: Int,
    val delay_ms: Int? = null,
    val content: String,
    val media: ChatMedia? = null
)

data class ChatCompletionMessage(
    val content: String,
    val delay_ms: Int? = null
)

data class ChatQuickReply(
    val id: String,
    val text: String,
    val show_at_turn: Int? = null
)

data class ChatTurnAction(
    val turn: Int,
    val type: String, // "rating_prompt", "quick_reply_inject", "auto_message"
    val config: Map<String, Any>? = null
)

data class ChatMedia(
    val type: String, // "image", "lottie", "link"
    val url: String? = null,
    val alt_text: String? = null
)

data class ChatStyleConfig(
    val background_color: String? = null,
    val ai_bubble_bg: String? = null,
    val ai_bubble_text: String? = null,
    val user_bubble_bg: String? = null,
    val user_bubble_text: String? = null,
    val input_bg: String? = null,
    val input_text: String? = null,
    val input_border: String? = null,
    val input_font_size: Int? = null,
    val typing_indicator_color: String? = null,
    val timestamp_color: String? = null,
    val quick_reply_bg: String? = null,
    val quick_reply_text: String? = null,
    val quick_reply_border: String? = null,
    val rating_star_color: String? = null,
    val send_button_color: String? = null
)

/** Runtime chat message (not from config). */
data class ChatMessage(
    val id: String,
    val role: ChatRole,
    val content: String,
    val media: ChatMedia? = null,
    val timestamp: Long = System.currentTimeMillis(),
    var rating: Int? = null
)

enum class ChatRole { AI, USER, SYSTEM }

// ── Parsing ──

@Suppress("UNCHECKED_CAST")
fun parseChatConfig(raw: Any?): ChatConfig? {
    val map = raw as? Map<String, Any> ?: return null
    return ChatConfig(
        max_user_turns = (map["max_user_turns"] as? Number)?.toInt(),
        min_user_turns = (map["min_user_turns"] as? Number)?.toInt(),
        turn_limit_behavior = map["turn_limit_behavior"] as? String,
        persona = (map["persona"] as? Map<String, Any>)?.let {
            ChatPersona(name = it["name"] as? String, role = it["role"] as? String, avatar_url = it["avatar_url"] as? String)
        },
        auto_messages = (map["auto_messages"] as? List<*>)?.mapNotNull { m ->
            val mm = m as? Map<String, Any> ?: return@mapNotNull null
            ChatAutoMessage(
                id = mm["id"] as? String ?: return@mapNotNull null,
                turn = (mm["turn"] as? Number)?.toInt() ?: 0,
                delay_ms = (mm["delay_ms"] as? Number)?.toInt(),
                content = mm["content"] as? String ?: return@mapNotNull null,
                media = parseChatMedia(mm["media"])
            )
        },
        completion_message = (map["completion_message"] as? Map<String, Any>)?.let {
            ChatCompletionMessage(content = it["content"] as? String ?: "", delay_ms = (it["delay_ms"] as? Number)?.toInt())
        },
        completion_cta_text = map["completion_cta_text"] as? String,
        completion_button = (map["completion_button"] as? Map<String, Any>)?.let { cb ->
            ChatCompletionButton(
                variant = cb["variant"] as? String,
                bg_color = cb["bg_color"] as? String,
                text_color = cb["text_color"] as? String,
                button_corner_radius = (cb["button_corner_radius"] as? Number)?.toDouble(),
                button_height = (cb["button_height"] as? Number)?.toDouble(),
                style = (cb["style"] as? Map<String, Any>)?.let { s ->
                    ChatCompletionButtonStyle(
                        font_size = (s["font_size"] as? Number)?.toDouble(),
                        font_weight = (s["font_weight"] as? Number)?.toDouble()
                    )
                }
            )
        },
        quick_replies = (map["quick_replies"] as? List<*>)?.mapNotNull { q ->
            val qm = q as? Map<String, Any> ?: return@mapNotNull null
            ChatQuickReply(id = qm["id"] as? String ?: "", text = qm["text"] as? String ?: "", show_at_turn = (qm["show_at_turn"] as? Number)?.toInt())
        },
        turn_actions = (map["turn_actions"] as? List<*>)?.mapNotNull { a ->
            val am = a as? Map<String, Any> ?: return@mapNotNull null
            ChatTurnAction(turn = (am["turn"] as? Number)?.toInt() ?: 1, type = am["type"] as? String ?: "", config = am["config"] as? Map<String, Any>)
        },
        input_placeholder = map["input_placeholder"] as? String,
        input_max_length = (map["input_max_length"] as? Number)?.toInt(),
        webhook = parseStepHookFromMap(map["webhook"] as? Map<String, Any>),
        style = (map["style"] as? Map<String, Any>)?.let { s ->
            ChatStyleConfig(
                background_color = s["background_color"] as? String,
                ai_bubble_bg = s["ai_bubble_bg"] as? String,
                ai_bubble_text = s["ai_bubble_text"] as? String,
                user_bubble_bg = s["user_bubble_bg"] as? String,
                user_bubble_text = s["user_bubble_text"] as? String,
                input_bg = s["input_bg"] as? String,
                input_text = s["input_text"] as? String,
                input_border = s["input_border"] as? String,
                input_font_size = (s["input_font_size"] as? Number)?.toInt(),
                typing_indicator_color = s["typing_indicator_color"] as? String,
                timestamp_color = s["timestamp_color"] as? String,
                quick_reply_bg = s["quick_reply_bg"] as? String,
                quick_reply_text = s["quick_reply_text"] as? String,
                quick_reply_border = s["quick_reply_border"] as? String,
                rating_star_color = s["rating_star_color"] as? String,
                send_button_color = s["send_button_color"] as? String
            )
        }
    )
}

private fun parseChatMedia(raw: Any?): ChatMedia? {
    val map = raw as? Map<*, *> ?: return null
    return ChatMedia(type = map["type"] as? String ?: "image", url = map["url"] as? String, alt_text = map["alt_text"] as? String)
}

private fun parseStepHookFromMap(map: Map<String, Any>?): StepHookConfig? {
    map ?: return null
    val enabled = map["enabled"] as? Boolean ?: false
    val url = map["webhook_url"] as? String ?: ""
    if (!enabled || url.isEmpty()) return null
    @Suppress("UNCHECKED_CAST")
    return StepHookConfig(
        enabled = true,
        webhook_url = url,
        timeout_ms = (map["timeout_ms"] as? Number)?.toInt() ?: 15000,
        loading_text = map["loading_text"] as? String,
        error_text = map["error_text"] as? String,
        retry_count = (map["retry_count"] as? Number)?.toInt() ?: 1,
        headers = map["headers"] as? Map<String, String>
    )
}
