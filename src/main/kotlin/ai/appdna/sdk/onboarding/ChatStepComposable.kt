package ai.appdna.sdk.onboarding

import android.util.Log
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowCircleUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
// ContentScale removed — no image loading dependency
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
// Avatar uses initial-based circle (no coil dependency)
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

/**
 * Interactive chat step Composable.
 * Renders a multi-turn AI chat with webhooks, quick replies, typing indicator, and turn limits.
 */
@Composable
fun ChatStepComposable(
    step: OnboardingStep,
    flowId: String,
    onNext: (Map<String, Any>) -> Unit,
    onSkip: () -> Unit,
    // Saved transcript for back-nav restore. Mirrors
    // iOS `ChatStepView.savedTranscript`. When the renderer was
    // already completed once, the orchestrator passes the prior
    // `responses[step.id]` map back in so the bubble history is
    // rebuilt instead of replaying auto-messages from scratch.
    savedTranscript: Map<String, Any>? = null,
) {
    val chatConfig = step.config.chat_config ?: return
    val style = chatConfig.style
    val persona = chatConfig.persona
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    // Host View for haptic feedback on sendMessage.
    val hostView = androidx.compose.ui.platform.LocalView.current

    // Restore from savedTranscript on first compose.
    // We seed initial state with the saved values so LaunchedEffect
    // never plays auto-messages when restoring.
    val initialMessages: List<ChatMessage> = remember(savedTranscript) {
        @Suppress("UNCHECKED_CAST")
        (savedTranscript?.get("transcript") as? List<Map<String, Any>>)?.map { m ->
            val role = (m["role"] as? String) ?: "ai"
            val content = (m["content"] as? String).orEmpty()
            val msgId = (m["id"] as? String) ?: java.util.UUID.randomUUID().toString()
            val ts = (m["timestamp"] as? String)?.let { ts ->
                ai.appdna.sdk.core.IsoInstant.parseEpochMs(ts)
            } ?: System.currentTimeMillis()
            ChatMessage(
                id = msgId,
                role = if (role == "user") ChatRole.USER else ChatRole.AI,
                content = content,
                timestamp = ts,
            )
        } ?: emptyList()
    }
    val initialUserTurnCount: Int = remember(savedTranscript) {
        (savedTranscript?.get("user_turn_count") as? Number)?.toInt()
            ?: initialMessages.count { it.role == ChatRole.USER }
    }
    val didRestore = savedTranscript != null && initialMessages.isNotEmpty()

    // State
    var messages by remember { mutableStateOf(initialMessages) }
    var inputText by remember { mutableStateOf("") }
    var isTyping by remember { mutableStateOf(false) }
    var userTurnCount by remember { mutableIntStateOf(initialUserTurnCount) }
    var isCompleted by remember { mutableStateOf(didRestore) }
    var currentRating by remember { mutableStateOf<Int?>(null) }
    // Only seed turn-0 quick replies synchronously
    // when there are no auto-messages to play. iOS calls
    // `loadQuickReplies(forTurn: 0)` AFTER `playAutoMessages`
    // completes (or immediately when no auto-messages); Android
    // previously seeded them at composition so they appeared while
    // the AI was still "typing" the welcome sequence.
    val initialQuickReplies = if (didRestore || chatConfig.auto_messages.isNullOrEmpty()) {
        chatConfig.quick_replies?.filter { (it.show_at_turn ?: 0) == 0 } ?: emptyList()
    } else emptyList()
    var dynamicQuickReplies by remember { mutableStateOf(initialQuickReplies) }
    var webhookData by remember { mutableStateOf(mutableMapOf<String, Any>()) }
    val startTime = remember { System.currentTimeMillis() }
    var showSoftLimitWarning by remember { mutableStateOf(false) }

    val maxTurns = chatConfig.resolvedMaxTurns
    val minTurns = chatConfig.resolvedMinTurns
    val turnsRemaining = maxOf(0, maxTurns - userTurnCount)
    val canSendMessage = inputText.isNotBlank() && !isTyping && !isCompleted

    // Colors
    fun hex(value: String?, fallback: String): Color {
        val v = value ?: fallback
        return try { Color(android.graphics.Color.parseColor(v)) } catch (_: Exception) { Color.Gray }
    }
    val aiBubbleBg = hex(style?.ai_bubble_bg, "#1E293B")
    val aiBubbleTextColor = hex(style?.ai_bubble_text, "#E2E8F0")
    val userBubbleBg = hex(style?.user_bubble_bg, (ai.appdna.sdk.AppDNA.brandAccentHex ?: "#6366F1"))
    val userBubbleTextColor = hex(style?.user_bubble_text, "#FFFFFF")
    val inputBgColor = hex(style?.input_bg, "#1E293B")
    val inputTextColor = hex(style?.input_text, "#E2E8F0")
    val inputBorderColor = hex(style?.input_border, "#334155")
    val sendBtnColor = hex(style?.send_button_color, (ai.appdna.sdk.AppDNA.brandAccentHex ?: "#6366F1"))
    val qrBgColor = hex(style?.quick_reply_bg, "#334155")
    val qrTextColor = hex(style?.quick_reply_text, "#E2E8F0")
    val typingDotColor = hex(style?.typing_indicator_color, (ai.appdna.sdk.AppDNA.brandAccentHex ?: "#6366F1"))

    // Auto-messages on appear — skip when we restored
    // a prior transcript so the user does not see the welcome
    // sequence replay over the saved bubbles.
    LaunchedEffect(Unit) {
        if (didRestore) return@LaunchedEffect
        val autoMsgs = chatConfig.auto_messages?.filter { it.turn == 0 } ?: emptyList()
        // Refactor to a SINGLE sequential coroutine
        // (replacing the parallel-coroutines + AtomicInteger pattern).
        // iOS playAutoMessages at ChatStepView.swift:614-633 schedules
        // independent `DispatchQueue.main.asyncAfter` timers, but each
        // timer's deadline is fired in absolute order by the OS — so iOS
        // gets serial-deadline ordering for free. Android's parallel
        // coroutines all share `messages = messages + msg` which is a
        // read-snapshot/compute/write pattern that is NOT atomic across
        // suspend points; even on Dispatchers.Main, two coroutines can
        // resume at the same dispatch tick and race each other's writes,
        // potentially losing a message OR firing the quick_reply load
        // before the last message visually commits. Sorting by deadline
        // and awaiting each in a single coroutine preserves the absolute
        // schedule iOS provides AND eliminates the race entirely.
        val playStart = System.currentTimeMillis()
        // Sort by absolute deadline (smaller delay → fires first), then
        // walk in order awaiting each.
        val sorted = autoMsgs.withIndex()
            .map { (i, m) -> i to (m.delay_ms ?: (500 + i * 1200)) }
            .sortedBy { it.second }
        var elapsedAtLastFire = 0
        for ((origIndex, deadlineMs) in sorted) {
            val autoMsg = autoMsgs[origIndex]
            val sleepFromHere = deadlineMs - elapsedAtLastFire
            if (sleepFromHere > 0) delay(sleepFromHere.toLong())
            isTyping = true
            delay(800)
            isTyping = false
            messages = messages + ChatMessage(id = autoMsg.id, role = ChatRole.AI, content = autoMsg.content, media = autoMsg.media)
            elapsedAtLastFire = deadlineMs + 800
        }
        // Load turn-0 quick replies AFTER all auto-messages
        // have rendered. Now naturally tails the loop body since the for
        // loop runs sequentially.
        dynamicQuickReplies = chatConfig.quick_replies?.filter { (it.show_at_turn ?: 0) == 0 } ?: emptyList()
        @Suppress("UNUSED_VARIABLE") val _start = playStart
    }

    // Scroll to bottom on new message OR when the typing indicator appears.
    // Also depend on isTyping so the indicator
    // row (appended below the last message at line ~461) scrolls into view.
    // iOS pins to the "typing" anchor whenever the indicator is the last item
    // (ChatStepView.swift:79-83). Without this fix the dots could render
    // off-screen on long transcripts, leaving the user wondering if the AI
    // was responding.
    LaunchedEffect(messages.size, isTyping) {
        if (messages.isNotEmpty()) {
            val target = if (isTyping) messages.size else messages.size - 1
            listState.animateScrollToItem(target.coerceAtLeast(0))
        }
    }

    fun sendMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return

        // sendMessage haptic feedback. Mirrors iOS
        // HapticEngine.trigger(.light) at ChatStepView.swift:432.
        runCatching {
            ai.appdna.sdk.core.HapticEngine.trigger(
                hostView,
                ai.appdna.sdk.core.HapticType.LIGHT,
            )
        }
        val userMsg = ChatMessage(id = "msg_u$userTurnCount", role = ChatRole.USER, content = trimmed)
        messages = messages + userMsg
        inputText = ""
        userTurnCount++
        dynamicQuickReplies = emptyList()
        focusManager.clearFocus()

        // Track message sent
        ai.appdna.sdk.AppDNA.track("chat_message_sent", mapOf(
            "flow_id" to flowId, "step_id" to step.id, "turn" to userTurnCount, "message_length" to trimmed.length
        ))

        if (!chatConfig.isHardLimit && turnsRemaining == 1) showSoftLimitWarning = true

        // Check turn actions
        chatConfig.turn_actions?.filter { it.turn == userTurnCount }?.forEach { action ->
            when (action.type) {
                "rating_prompt" -> currentRating = 0
                "auto_message" -> {
                    val content = (action.config?.get("content") as? String) ?: return@forEach
                    scope.launch {
                        delay(500)
                        messages = messages + ChatMessage(id = "action_$userTurnCount", role = ChatRole.AI, content = content)
                    }
                }
            }
        }

        isTyping = true
        scope.launch {
            try {
                val result = fireWebhook(
                    webhook = chatConfig.webhook,
                    flowId = flowId,
                    stepId = step.id,
                    messages = messages,
                    turn = userTurnCount - 1,
                    maxTurns = maxTurns,
                    remaining = turnsRemaining,
                    rating = currentRating,
                    context = webhookData.toMap(),
                )
                isTyping = false
                // Match iOS ChatStepView.swift:496-507.
                // Distinguish HTTP non-2xx (preserves http_status +
                // response_body) from network/transport errors so console
                // analytics can filter by failure category.
                val response: WebhookResponse? = when (result) {
                    null -> null
                    is WebhookResult.Success -> result.response
                    is WebhookResult.HttpError -> {
                        val errMsg = chatConfig.webhook?.error_text ?: "Sorry, something went wrong. Please try again."
                        messages = messages + ChatMessage(id = "err_$userTurnCount", role = ChatRole.SYSTEM, content = errMsg)
                        ai.appdna.sdk.AppDNA.track("chat_webhook_error", mapOf(
                            "flow_id" to flowId, "step_id" to step.id, "turn" to userTurnCount,
                            "http_status" to result.statusCode,
                            "response_body" to result.bodyPreview,
                        ))
                        null
                    }
                    is WebhookResult.NetworkError -> {
                        val errMsg = chatConfig.webhook?.error_text ?: "Sorry, something went wrong. Please try again."
                        messages = messages + ChatMessage(id = "err_$userTurnCount", role = ChatRole.SYSTEM, content = errMsg)
                        ai.appdna.sdk.AppDNA.track("chat_webhook_error", mapOf(
                            "flow_id" to flowId, "step_id" to step.id, "turn" to userTurnCount,
                            "error" to result.message,
                        ))
                        null
                    }
                }
                if (response == null) {
                    // No-op: result was null (no webhook configured) or already
                    // handled above (HttpError/NetworkError both surface a
                    // system message + emit analytics).
                } else {
                    ai.appdna.sdk.AppDNA.track("chat_message_received", mapOf(
                        "flow_id" to flowId, "step_id" to step.id, "turn" to userTurnCount,
                        "message_count" to (response.messages?.size ?: 0)
                    ))
                    // Add AI messages
                    response.messages?.forEachIndexed { i, msg ->
                        messages = messages + ChatMessage(id = "msg_a${userTurnCount}_$i", role = ChatRole.AI, content = msg.content ?: "", media = msg.media)
                    }
                    // Update quick replies
                    response.quick_replies?.let { dynamicQuickReplies = it }
                    // Merge data
                    response.data?.forEach { (k, v) -> webhookData[k] = v }
                    // Force complete
                    if (response.force_complete == true || response.action == "reply_and_complete") {
                        val completionMsg = response.completion_message ?: chatConfig.completion_message?.content
                        if (completionMsg != null) {
                            messages = messages + ChatMessage(id = "completion", role = ChatRole.AI, content = completionMsg)
                        }
                        isCompleted = true
                        // Clear stale soft-limit warning when chat
                        // completes. iOS does this inside completeChat() at
                        // ChatStepView.swift:580. Without this the warning text
                        // ("You have 1 message remaining") stays visible after
                        // completion CTA appears.
                        showSoftLimitWarning = false
                        ai.appdna.sdk.AppDNA.track("chat_completed", mapOf(
                            "flow_id" to flowId, "step_id" to step.id, "user_turn_count" to userTurnCount,
                            "total_messages" to messages.size, "completion_reason" to "ai_completed",
                            "duration_ms" to (System.currentTimeMillis() - startTime).toInt()
                        ))
                    }
                    // Max turns
                    if (turnsRemaining <= 0 && !isCompleted) {
                        chatConfig.completion_message?.content?.let {
                            messages = messages + ChatMessage(id = "completion", role = ChatRole.AI, content = it)
                        }
                        isCompleted = true
                        // See chat completion sites: clear soft-limit warning.
                        showSoftLimitWarning = false
                        ai.appdna.sdk.AppDNA.track("chat_completed", mapOf(
                            "flow_id" to flowId, "step_id" to step.id, "user_turn_count" to userTurnCount,
                            "total_messages" to messages.size, "completion_reason" to "max_turns",
                            "duration_ms" to (System.currentTimeMillis() - startTime).toInt()
                        ))
                    }
                }
            } catch (e: Exception) {
                isTyping = false
                // Match iOS catch-default at
                // ChatStepView.swift:518 ("Sorry, something went wrong. Please
                // try again."). Previous Android catch dropped "Please try
                // again." so the same caught network error rendered shorter
                // on Android than iOS.
                val errMsg = chatConfig.webhook?.error_text ?: "Sorry, something went wrong. Please try again."
                messages = messages + ChatMessage(id = "err_$userTurnCount", role = ChatRole.SYSTEM, content = errMsg)
                ai.appdna.sdk.AppDNA.track("chat_webhook_error", mapOf(
                    "flow_id" to flowId, "step_id" to step.id, "turn" to userTurnCount, "error" to (e.message ?: "unknown")
                ))
            }
        }
    }

    fun buildTranscript(reason: String): Map<String, Any> {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val transcript = messages.filter { it.role != ChatRole.SYSTEM }.map { msg ->
            mapOf("role" to if (msg.role == ChatRole.USER) "user" else "ai", "content" to msg.content, "id" to msg.id, "timestamp" to iso.format(Date(msg.timestamp)))
        }
        val result = mutableMapOf<String, Any>(
            "transcript" to transcript,
            "user_turn_count" to userTurnCount,
            "total_message_count" to messages.size,
            "completion_reason" to reason,
            "duration_ms" to (System.currentTimeMillis() - startTime).toInt()
        )
        currentRating?.let { result["rating"] = it }
        if (webhookData.isNotEmpty()) result["webhook_data"] = webhookData
        return result
    }

    // UI
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(hex(style?.background_color, "#0F172A"))
    ) {
        // Header
        // 4dp VStack spacing matching
        // iOS ChatStepView.swift:138-159 VStack(spacing: 4).
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // Header title 16→17sp
            // matching iOS ChatStepView.swift:142 .headline.
            // SwiftUI .headline maps to
            // SemiBold (600), not Bold (700). Drop weight to match iOS.
            step.config.title?.let { Text(it, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 17.sp) }
            if (persona?.name != null && persona.role != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val avatarUrl = persona?.avatar_url?.takeIf { it.isNotBlank() }
                    // Only render the
                    // avatar Box when avatar_url exists. iOS ChatStepView.swift
                    // :138-159 has NO fallback initial circle in the HEADER;
                    // shows persona name+role as plain text only when no
                    // image. (Message bubbles still keep their initial
                    // fallback at lines 421-432, matching iOS 168-175.)
                    if (avatarUrl != null) {
                        Box(
                            // Header avatar
                            // placeholder bg uses iOS-canonical
                            // `Color.gray.opacity(0.3)`.
                            modifier = Modifier.size(28.dp).clip(CircleShape).background(Color.Gray.copy(alpha = 0.3f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            ai.appdna.sdk.core.NetworkImage(
                                url = avatarUrl,
                                modifier = Modifier.size(28.dp).clip(CircleShape),
                                contentDescription = persona.name,
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            )
                        }
                    }
                    Text("${persona.name} - ${persona.role}", color = Color.Gray, fontSize = 12.sp)
                }
            }
        }

        // Messages
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            items(messages, key = { it.id }) { msg ->
                when (msg.role) {
                    ChatRole.AI -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            // Load persona.avatar_url for AI message
                            // bubbles, fallback to initial circle.
                            val msgAvatarUrl = persona?.avatar_url?.takeIf { it.isNotBlank() }
                            Box(modifier = Modifier.size(32.dp).clip(CircleShape).background(aiBubbleBg), contentAlignment = Alignment.Center) {
                                if (msgAvatarUrl != null) {
                                    ai.appdna.sdk.core.NetworkImage(
                                        url = msgAvatarUrl,
                                        modifier = Modifier.size(32.dp).clip(CircleShape),
                                        contentDescription = persona?.name,
                                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                    )
                                } else {
                                    Text((persona?.name?.take(1) ?: "A"), color = aiBubbleTextColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            // Render text + optional inline image
                            // media bubble below it. iOS draws an image
                            // BundledAsyncImage when `media.type == "image"`
                            // and a non-empty url; Android previously dropped
                            // the media field on the floor.
                            Column(
                                modifier = Modifier.weight(1f, fill = false),
                                // 6→4dp
                                // matching iOS ChatStepView.swift:176
                                // VStack(spacing: 4).
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                if (msg.content.isNotBlank()) {
                                    Text(
                                        // Bubble text 14→15sp
                                        // matching iOS .subheadline (~15pt).
                                        msg.content, color = aiBubbleTextColor, fontSize = 15.sp,
                                        modifier = Modifier.background(aiBubbleBg, RoundedCornerShape(16.dp)).padding(12.dp)
                                    )
                                }
                                msg.media?.takeIf { it.type == "image" && !it.url.isNullOrBlank() }?.let { media ->
                                    ai.appdna.sdk.core.NetworkImage(
                                        url = media.url!!,
                                        contentDescription = media.alt_text,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            // 220→150dp
                                            // matching iOS ChatStepView.swift:185-187
                                            // .frame(maxHeight: 150).
                                            .heightIn(max = 150.dp)
                                            .clip(RoundedCornerShape(12.dp)),
                                        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                                    )
                                }
                            }
                            Spacer(Modifier.width(40.dp))
                        }
                    }
                    ChatRole.USER -> {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            Spacer(Modifier.width(40.dp))
                            Text(
                                // Bubble text 14→15sp.
                                msg.content, color = userBubbleTextColor, fontSize = 15.sp,
                                modifier = Modifier.background(userBubbleBg, RoundedCornerShape(16.dp)).padding(12.dp)
                            )
                        }
                    }
                    ChatRole.SYSTEM -> {
                        Text(msg.content, color = Color.Gray, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    }
                }
            }

            // Typing indicator
            if (isTyping) {
                item {
                    // Same persona avatar fallback as message bubbles.
                    val typingAvatarUrl = persona?.avatar_url?.takeIf { it.isNotBlank() }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(modifier = Modifier.size(32.dp).clip(CircleShape).background(aiBubbleBg), contentAlignment = Alignment.Center) {
                            if (typingAvatarUrl != null) {
                                ai.appdna.sdk.core.NetworkImage(
                                    url = typingAvatarUrl,
                                    modifier = Modifier.size(32.dp).clip(CircleShape),
                                    contentDescription = persona?.name,
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                )
                            } else {
                                Text((persona?.name?.take(1) ?: "A"), color = aiBubbleTextColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        // Animated typing indicator (3 dots
                        // pulsing alpha 0.3 → 1.0 with 0.2s stagger).
                        // Mirrors iOS ChatStepView.swift:223 0.6s easing curve.
                        // Static dots looked broken — pulsing tells the user
                        // the assistant is actively responding.
                        val infiniteTx = androidx.compose.animation.core.rememberInfiniteTransition(label = "typing")
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.background(aiBubbleBg, RoundedCornerShape(16.dp)).padding(12.dp)
                        ) {
                            for (dotIndex in 0..2) {
                                // Per-dot
                                // delay moved out of `tween(delayMillis)` into
                                // `initialStartOffset` so it applies ONCE before
                                // the first iteration, not before every cycle.
                                // SwiftUI `.delay(...)` semantics — iOS
                                // ChatStepView.swift:223 dots pulse continuously
                                // after initial stagger; Android tween(delay) was
                                // pausing 200/400ms between every cycle.
                                val alpha by infiniteTx.animateFloat(
                                    initialValue = 0.3f,
                                    targetValue = 1.0f,
                                    animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                                        animation = androidx.compose.animation.core.tween(
                                            durationMillis = 600,
                                            easing = androidx.compose.animation.core.FastOutSlowInEasing,
                                        ),
                                        repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
                                        initialStartOffset = androidx.compose.animation.core.StartOffset(dotIndex * 200),
                                    ),
                                    label = "typing_dot_$dotIndex",
                                )
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(typingDotColor.copy(alpha = alpha)),
                                )
                            }
                        }
                    }
                }
            }
        }

        // Chat rating widget. Shown when a `rating_prompt`
        // turn-action set `currentRating = 0`. Mirrors iOS ChatStepView.swift:309
        // 5-star row with `sendRatingEvent` emit. The composable uses a
        // simple unicode star (★/☆) instead of Material icons to avoid
        // pulling icon deps inside the chat composable.
        // Rating prompt only visible while currentRating == 0
        // (the "shown but unanswered" sentinel). Once user taps a star,
        // currentRating > 0 → row hides. Mirrors iOS ChatStepView.swift:71.
        currentRating?.takeIf { it == 0 }?.let { ratingState ->
            // Honour style.rating_star_color (was hardcoded
            // amber #FBBF24). Mirrors iOS ChatStepView.swift:315.
            val starColor = hex(style?.rating_star_color, "#FBBF24")
            // Wrap rating widget in
            // aiBubbleBg-filled rounded card with header label, mirroring
            // iOS ChatStepView.swift:301-323. Was bare unicode stars on
            // page background → no visual affordance distinguishing the
            // widget from chat content.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .background(aiBubbleBg, RoundedCornerShape(12.dp))
                    .padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "How helpful is this conversation?",
                    color = Color.Gray,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                Row(horizontalArrangement = Arrangement.Center) {
                    for (star in 1..5) {
                        val filled = ratingState >= star
                        // Empty-star uses
                        // same starColor as filled (iOS ChatStepView.swift
                        // :313-315 only differentiates by glyph). Was
                        // hardcoded slate-gray which produced inconsistent
                        // gold-filled + slate-empty mix.
                        Text(
                            text = if (filled) "★" else "☆",
                            color = starColor,
                            // 20sp matches
                            // iOS .font(.title3) (~20pt) at ChatStepView.swift:314.
                            // Was 24sp — visually 1 pt larger than iOS.
                            fontSize = 20.sp,
                            modifier = Modifier
                                .padding(horizontal = 4.dp)
                                .clickable {
                                    currentRating = star
                                    ai.appdna.sdk.AppDNA.track("chat_rating_submitted", mapOf(
                                        "flow_id" to flowId,
                                        "step_id" to step.id,
                                        "rating" to star,
                                        "turn" to userTurnCount,
                                    ))
                                }
                        )
                    }
                }
            }
        }

        // Quick replies
        if (dynamicQuickReplies.isNotEmpty() && !isCompleted && !isTyping) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // `style.quick_reply_border` config now applied
                // to OutlinedButton border. Mirrors iOS ChatStepView.swift:251
                // 1px stroke with `qrBorder` color. Was using Material3
                // default border, ignoring the config.
                // Fall back to iOS-canonical
                // slate-blue `#475569` when style.quick_reply_border is unset.
                // iOS ChatStepView.swift:40 hardcodes that fallback so unstyled
                // quick replies always show a visible stroke. Was falling back
                // to qrBgColor which made the stroke invisible (fill ≈ stroke).
                val qrBorderColor = style?.quick_reply_border?.takeIf { it.isNotBlank() }?.let { hex(it, "#475569") } ?: hex(null, "#475569")
                items(dynamicQuickReplies, key = { it.id }) { qr ->
                    OutlinedButton(
                        onClick = {
                            ai.appdna.sdk.AppDNA.track("chat_quick_reply_tapped", mapOf(
                                "flow_id" to flowId, "step_id" to step.id, "quick_reply_id" to qr.id, "turn" to userTurnCount
                            ))
                            sendMessage(qr.text)
                        },
                        shape = RoundedCornerShape(20.dp),
                        colors = ButtonDefaults.outlinedButtonColors(containerColor = qrBgColor, contentColor = qrTextColor),
                        border = androidx.compose.foundation.BorderStroke(1.dp, qrBorderColor),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text(qr.text, fontSize = 12.sp)
                    }
                }
            }
        }

        // Soft limit warning
        if (showSoftLimitWarning) {
            // bottom=4dp matches iOS
            // .padding(.bottom, 4) at ChatStepView.swift:97.
            Text("You have 1 message remaining", color = Color(0xFFF59E0B), fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 4.dp))
        }

        // Input or CTA
        // Show the completion CTA once the turn limit is reached for BOTH
        // hard AND soft limits (was `&& isHardLimit`, so soft-limit chats kept the
        // input showing forever → max never honored + CTA never shown). The inner
        // LaunchedEffect still AUTO-completes only on a hard limit; a soft limit
        // shows the CTA and lets the user tap it. Matches the iOS fix.
        if (isCompleted || turnsRemaining <= 0) {
            // Fire chat_completed (reason=max_turns) the
            // first time the hard-limit branch triggers. iOS does this in
            // ChatStepView.swift:107-109 via `.onAppear { completeChat(...) }`
            // as a safety net when turnsRemaining<=0 && hardLimit but
            // isCompleted hasn't been set (e.g. saved transcript with
            // stale state). Android previously rendered the CTA without
            // ever tracking the completion event for this code path.
            LaunchedEffect(turnsRemaining, chatConfig.isHardLimit, isCompleted) {
                if (!isCompleted && turnsRemaining <= 0 && chatConfig.isHardLimit) {
                    isCompleted = true
                    // Clear stale soft-limit warning when the
                    // safety-net hard-limit path takes over (matches the other
                    // completion sites + iOS completeChat).
                    showSoftLimitWarning = false
                    // Match iOS chat_completed schema at
                    // ChatStepView.swift:584-591: completion_reason / user_turn_count /
                    // total_messages keys. Previous keys (reason / turns) silently
                    // diverged from the main path completeChat() emit, so warehouse
                    // queries grouping by completion_reason missed the safety-net
                    // path entirely.
                    ai.appdna.sdk.AppDNA.track("chat_completed", mapOf(
                        "flow_id" to flowId,
                        "step_id" to step.id,
                        "completion_reason" to "max_turns",
                        "user_turn_count" to userTurnCount,
                        "total_messages" to messages.size,
                        // `.toInt()` to match iOS Int payload
                        // at ChatStepView.swift:589 + the regular completion
                        // paths (lines 303 + 317). Was emitting Long here only
                        // on this safety-net path → schema drift across the
                        // three chat_completed call sites.
                        "duration_ms" to (System.currentTimeMillis() - startTime).toInt(),
                    ))
                }
            }
            // Completion CTA — styling mirrors the normal CTA button block.
            // Any unset field in completion_button falls back to the chat
            // theme's user bubble colors so existing flows render unchanged.
            val btn = chatConfig.completion_button
            val variant = btn?.variant ?: "primary"
            val customBg = btn?.bg_color?.takeIf { it.isNotEmpty() }?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() }
            val resolvedBg = customBg ?: userBubbleBg
            // Match iOS contrast fallback at
            // ChatStepView.swift:346-352. When bg_color is authored
            // but text_color is empty, derive black/white from bg
            // luminance instead of the chat theme default — without
            // this, white-on-white renders for dark-text themes.
            val explicitText = btn?.text_color?.takeIf { it.isNotEmpty() }?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() }
            val resolvedText = when {
                explicitText != null -> explicitText
                customBg != null -> {
                    // Match iOS Color.isLightHex luminance threshold
                    // (PaywallHelperViews.swift:120 — 0.2126·R + 0.7152·G + 0.0722·B ≥ 0.6).
                    val l = 0.2126 * customBg.red + 0.7152 * customBg.green + 0.0722 * customBg.blue
                    if (l >= 0.6) Color.Black else Color.White
                }
                else -> userBubbleTextColor
            }
            val radius = (btn?.button_corner_radius ?: 14.0).dp
            // Match iOS height behaviour. iOS uses
            // height=nil → natural padding(14×2)≈46pt fallback when
            // button_height is not configured (ChatStepView.swift:354,
            // 374-375). Android previously forced 52.dp default making
            // the CTA consistently taller. Use Modifier.heightIn that
            // honours button_height when set, otherwise lets the
            // intrinsic content+padding determine height.
            val heightModifier = btn?.button_height
                ?.let { androidx.compose.ui.Modifier.height(it.dp) }
                ?: androidx.compose.ui.Modifier.defaultMinSize(minHeight = 46.dp)
            val fontSize = (btn?.style?.font_size ?: 17.0).sp
            val weight = when ((btn?.style?.font_weight ?: 600.0).toInt()) {
                400 -> FontWeight.Normal
                500 -> FontWeight.Medium
                700 -> FontWeight.Bold
                else -> FontWeight.SemiBold
            }
            val label = chatConfig.completion_cta_text ?: step.config.cta_text ?: "Continue"

            // Match iOS
            // ChatStepView.swift:394 `.padding(.horizontal, 20)` + line 401
            // `.padding(.vertical, 16)`. Was uniform `padding(16)` so the
            // button was 8dp narrower horizontally and the CTA-to-Skip gap
            // was 8dp wider vertically than iOS.
            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                when (variant) {
                    "text" -> {
                        TextButton(
                            onClick = { onNext(buildTranscript(if (isCompleted) "max_turns" else "user_completed")) },
                            modifier = Modifier.fillMaxWidth().then(heightModifier)
                        ) { Text(label, color = resolvedBg, fontWeight = weight, fontSize = fontSize) }
                    }
                    "outline" -> {
                        OutlinedButton(
                            onClick = { onNext(buildTranscript(if (isCompleted) "max_turns" else "user_completed")) },
                            modifier = Modifier.fillMaxWidth().then(heightModifier),
                            shape = RoundedCornerShape(radius),
                            border = androidx.compose.foundation.BorderStroke(2.dp, resolvedBg)
                        ) { Text(label, color = resolvedBg, fontWeight = weight, fontSize = fontSize) }
                    }
                    "secondary" -> {
                        Button(
                            onClick = { onNext(buildTranscript(if (isCompleted) "max_turns" else "user_completed")) },
                            modifier = Modifier.fillMaxWidth().then(heightModifier),
                            shape = RoundedCornerShape(radius),
                            colors = ButtonDefaults.buttonColors(containerColor = resolvedBg.copy(alpha = 0.15f))
                        ) { Text(label, color = resolvedText, fontWeight = weight, fontSize = fontSize) }
                    }
                    else -> { // primary
                        Button(
                            onClick = { onNext(buildTranscript(if (isCompleted) "max_turns" else "user_completed")) },
                            modifier = Modifier.fillMaxWidth().then(heightModifier),
                            shape = RoundedCornerShape(radius),
                            colors = ButtonDefaults.buttonColors(containerColor = resolvedBg)
                        ) { Text(label, color = resolvedText, fontWeight = weight, fontSize = fontSize) }
                    }
                }
                if (step.config.skip_enabled == true) {
                    // Theme-adaptive secondary
                    // text matching iOS ChatStepView.swift:398 `.foregroundColor(.gray)`
                    // (UIColor.systemGray adapts to dark mode). Was static
                    // Color.Gray (#888) which gave poor contrast on dark
                    // backgrounds and didn't shift between light/dark.
                    TextButton(onClick = onSkip) { Text("Skip", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), fontSize = 12.sp) }
                }
            }
        } else {
            // Input bar
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = inputText,
                    // Enforce input_max_length truncation.
                    // ChatModels declared the field but the UI ignored it.
                    onValueChange = { input ->
                        val maxLen = chatConfig.input_max_length
                        inputText = if (maxLen != null && input.length > maxLen) input.take(maxLen) else input
                    },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(chatConfig.input_placeholder ?: "Type your message...", fontSize = (style?.input_font_size ?: 14).sp, color = inputTextColor.copy(alpha = 0.5f)) },
                    textStyle = LocalTextStyle.current.copy(fontSize = (style?.input_font_size ?: 14).sp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = inputBgColor,
                        unfocusedContainerColor = inputBgColor,
                        focusedBorderColor = inputBorderColor,
                        unfocusedBorderColor = inputBorderColor,
                        focusedTextColor = inputTextColor,
                        unfocusedTextColor = inputTextColor,
                        cursorColor = sendBtnColor
                    ),
                    shape = RoundedCornerShape(20.dp),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { if (canSendMessage) sendMessage(inputText) })
                )
                // TalkBack label so the
                // glyph-only IconButton reads as "Send message" instead of
                // the literal arrow character. iOS uses Image(systemName:
                // "arrow.up.circle.fill") which SwiftUI auto-labels.
                IconButton(
                    onClick = { sendMessage(inputText) },
                    enabled = canSendMessage,
                    modifier = Modifier.semantics { contentDescription = "Send message" },
                ) {
                    // Bump glyph 24→32sp to
                    // match SwiftUI Image(systemName: "arrow.up.circle.fill") visual
                    // weight at iOS ChatStepView.swift send-button. Was reading as
                    // a small text caret next to a normal-sized text field.
                    // Disabled tint = faded BRAND color
                    // matching iOS ChatStepView.swift:290 `sendBtnColor.opacity(0.3)`.
                    // Was Color.Gray which dropped the brand identity in the disabled
                    // state — same JSON rendered gray on Android, faded indigo on iOS.
                    // Material ArrowCircleUp icon
                    // matching iOS `Image(systemName: "arrow.up.circle.fill")`
                    // at ChatStepView.swift:288-290. Was Unicode arrow "↑"
                    // which only tinted the arrow stroke; iOS fills the whole
                    // circle with brand color and renders white arrow inside.
                    Icon(
                        imageVector = Icons.Filled.ArrowCircleUp,
                        contentDescription = "Send message",
                        tint = if (canSendMessage) sendBtnColor else sendBtnColor.copy(alpha = 0.3f),
                        modifier = Modifier.size(32.dp),
                    )
                }
            }
        }
    }
}

// ── Webhook ──

private data class WebhookResponse(
    val action: String?,
    val messages: List<ChatWebhookMessage>?,
    val quick_replies: List<ChatQuickReply>?,
    val data: Map<String, Any>?,
    val force_complete: Boolean?,
    val completion_message: String?
)

/**
 * Sealed result for chat webhook outcomes so callers can
 * emit structured telemetry per failure mode (matches iOS ChatStepView.swift
 * :496-507 which preserves `http_status` + `response_body` on non-2xx).
 *
 * Previously `fireWebhook` returned `WebhookResponse?` and the caller
 * collapsed every non-2xx HTTP and every parse error into a single
 * `error: "http_non_2xx_or_null"` event — making it impossible to
 * distinguish 401 (auth revocation) from 5xx (backend deploy) in console
 * analytics. This split lets the caller emit the canonical iOS payload.
 */
private sealed class WebhookResult {
    data class Success(val response: WebhookResponse) : WebhookResult()
    data class HttpError(val statusCode: Int, val bodyPreview: String) : WebhookResult()
    data class NetworkError(val message: String) : WebhookResult()
}

private data class ChatWebhookMessage(
    val content: String?,
    val media: ChatMedia?,
    val delay_ms: Int?
)

/**
 * Fire chat webhook off the Main thread.
 *
 * The body is wrapped in `withContext(Dispatchers.IO)` because callers run on
 * the Compose `rememberCoroutineScope` (Main) and we do blocking
 * `URL.openConnection() + outputStream + bufferedReader().readText()`.
 * Without the wrap, every chat turn produces a `NetworkOnMainThreadException`
 * (or worse, a frozen UI). Mirrors the `executeWebhook` IO wrap in
 * `OnboardingActivity.executeWebhook`.
 */
@Suppress("UNCHECKED_CAST")
private suspend fun fireWebhook(
    webhook: StepHookConfig?,
    flowId: String,
    stepId: String,
    messages: List<ChatMessage>,
    turn: Int,
    maxTurns: Int,
    remaining: Int,
    rating: Int? = null,
    context: Map<String, Any> = emptyMap(),
): WebhookResult? = withContext(Dispatchers.IO) {
    webhook ?: return@withContext null
    val url = webhook.webhook_url ?: return@withContext null

    val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    val msgsJson = JSONArray().apply {
        messages.filter { it.role != ChatRole.SYSTEM }.forEach { msg ->
            put(JSONObject().apply {
                put("role", if (msg.role == ChatRole.USER) "user" else "ai")
                put("content", msg.content)
                put("id", msg.id)
                put("timestamp", iso.format(Date(msg.timestamp)))
            })
        }
    }

    val body = JSONObject().apply {
        put("event", "chat_message")
        put("flow_id", flowId)
        put("step_id", stepId)
        put("app_id", ai.appdna.sdk.AppDNA.getCurrentAppId() ?: "")
        put("user_id", ai.appdna.sdk.AppDNA.getCurrentUserId() ?: "")
        put("conversation", JSONObject().apply {
            put("turn", turn)
            put("messages", msgsJson)
            put("user_message", messages.lastOrNull { it.role == ChatRole.USER }?.content ?: "")
            put("max_turns", maxTurns)
            put("remaining_turns", remaining)
        })
        // Include `rating`, `context`,
        // and `responses` keys to match iOS `ChatStepView.swift:454-470`
        // ChatWebhookRequest. AI/webhook backends key off these for
        // chat satisfaction surveys + accumulated webhook_data carry-over.
        if (rating != null) put("rating", rating)
        if (context.isNotEmpty()) {
            put("context", JSONObject(context as Map<*, *>))
        }
        // REMOVED top-level `responses` field. iOS
        // ChatStepView.swift:467 sends `responses: nil` (Codable emits
        // `"responses": null`); the prior comment claiming "mirrors iOS"
        // was wrong. Android was injecting the entire onboarding-step
        // response set (single/multi-select answers, form inputs incl.
        // PII like name/email) into the chat-message payload — leaking
        // those answers to the chat-AI vendor even when the chat webhook
        // URL points to a separate vendor than the step-hook webhook.
        // iOS only round-trips the AI server's own `data` field via the
        // `context` key (already populated above).
    }

    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        setRequestProperty("Content-Type", "application/json")
        webhook.headers?.forEach { (k, v) -> setRequestProperty(k, v) }
        connectTimeout = (webhook.timeout_ms ?: 15000)
        readTimeout = (webhook.timeout_ms ?: 15000)
        doOutput = true
    }

    try {
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
        } catch (e: Exception) {
            // Capture transport-level failures (timeout, DNS, no-network) so
            // the caller can emit the iOS-canonical `error` field. Matches
            // ChatStepView.swift:518 catch path.
            return@withContext WebhookResult.NetworkError(e.message ?: "unknown")
        }
        val statusCode = try { conn.responseCode } catch (e: Exception) {
            return@withContext WebhookResult.NetworkError(e.message ?: "unknown")
        }
        if (statusCode !in 200..299) {
            // Read errorStream + take 500-char preview to
            // match iOS bodyPreview semantics at ChatStepView.swift:498-505.
            val bodyPreview = try {
                conn.errorStream?.bufferedReader()?.use { it.readText() }?.take(500) ?: ""
            } catch (_: Exception) { "" }
            Log.e("ChatWebhook", "HTTP $statusCode")
            return@withContext WebhookResult.HttpError(statusCode, bodyPreview)
        }
        val respText = conn.inputStream.bufferedReader().readText()
        val json = JSONObject(respText)

        val respMessages = json.optJSONArray("messages")?.let { arr ->
            (0 until arr.length()).map { i ->
                val m = arr.getJSONObject(i)
                val mediaJson = m.optJSONObject("media")
                val media = mediaJson?.let { ChatMedia(type = it.optString("type", "image"), url = it.optString("url", null), alt_text = it.optString("alt_text", null)) }
                ChatWebhookMessage(content = m.optString("content", null), media = media, delay_ms = if (m.has("delay_ms")) m.getInt("delay_ms") else null)
            }
        }

        val respQRs = json.optJSONArray("quick_replies")?.let { arr ->
            (0 until arr.length()).map { i ->
                val q = arr.getJSONObject(i)
                ChatQuickReply(id = q.optString("id", "qr_$i"), text = q.optString("text", ""), show_at_turn = if (q.has("show_at_turn")) q.getInt("show_at_turn") else null)
            }
        }

        val respData = json.optJSONObject("data")?.let { d ->
            val map = mutableMapOf<String, Any>()
            d.keys().forEach { k -> map[k] = d.get(k) }
            map
        }

        WebhookResult.Success(
            WebhookResponse(
                action = json.optString("action", null),
                messages = respMessages,
                quick_replies = respQRs,
                data = respData,
                force_complete = if (json.has("force_complete")) json.getBoolean("force_complete") else null,
                completion_message = json.optString("completion_message", null)
            )
        )
    } finally {
        conn.disconnect()
    }
}
