package ai.appdna.sdk.onboarding

import ai.appdna.sdk.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONArray
import org.json.JSONObject

/**
 * The conversation of an interactive chat step that is on screen: its bubbles, the turn count, the
 * composer's text, the quick replies, the webhook's accumulated `data`, and whether a reply is awaited.
 *
 * It lives in the step's draft ([StepDraft.chat]), which the ViewModel holds, so a rotation keeps the
 * conversation; it is written into the saved-state Bundle with the draft, so a process death keeps it
 * too. A webhook request runs in [scope], not in the composition's scope: a rotation does not cancel it,
 * and its reply lands in this session, which the new composition shows. A process death ends the
 * request; the restored session is marked [resendOnShow] and the step sends the last user message again,
 * once.
 *
 * Like the rest of a draft, it is not an answer: the step submits its transcript when the chat ends.
 */
internal class ChatSession(val stepId: String) {
    var messages by mutableStateOf<List<ChatMessage>>(emptyList())
    var inputText by mutableStateOf("")
    var isTyping by mutableStateOf(false)
    var userTurnCount by mutableIntStateOf(0)
    var isCompleted by mutableStateOf(false)
    var currentRating by mutableStateOf<Int?>(null)
    var quickReplies by mutableStateOf<List<ChatQuickReply>>(emptyList())
    var showSoftLimitWarning by mutableStateOf(false)

    /** The webhook's accumulated `data`, sent back as `context` on every turn. */
    val webhookData: MutableMap<String, Any> = mutableMapOf()

    var startTime: Long = System.currentTimeMillis()

    /** False until the step seeded this session (from a completed transcript, or from its config). */
    var seeded = false

    /** The welcome messages were started, and finished. */
    var autoMessagesStarted = false
    var autoMessagesDone = false

    /** A webhook request for the latest user message has no reply yet. */
    var awaitingReply = false

    /** Restored after the process ended with a reply awaited: the last user message is sent again. */
    var resendOnShow = false

    /** Where the webhook requests and the welcome messages run; ended with the draft. */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun close() = scope.cancel()

    companion object {
        /** The conversation is kept below this many characters in the saved state, newest messages first. */
        internal const val MAX_CHARS = 24 * 1024

        /** The webhook's `data` is left out of the saved state when larger than this. */
        internal const val MAX_DATA_CHARS = 8 * 1024

        internal fun encode(session: ChatSession): JSONObject {
            val obj = JSONObject()
            // Newest first, so a long conversation keeps its latest turns.
            val kept = ArrayList<JSONObject>()
            var total = 0
            var dropped = 0
            for (m in session.messages.asReversed()) {
                val j = JSONObject()
                    .put("id", m.id)
                    .put("role", m.role.name)
                    .put("content", m.content)
                    .put("timestamp", m.timestamp)
                m.media?.let { media ->
                    j.put("media", JSONObject().put("type", media.type).put("url", media.url).put("alt_text", media.alt_text))
                }
                val size = j.toString().length
                if (total + size > MAX_CHARS) { dropped++; continue }
                kept += j
                total += size
            }
            if (dropped > 0) Log.info("Chat step ${session.stepId}: $dropped older message(s) too large to keep if the app is closed")
            obj.put("messages", JSONArray(kept.asReversed()))
            obj.put("input", session.inputText)
            obj.put("turns", session.userTurnCount)
            obj.put("completed", session.isCompleted)
            session.currentRating?.let { obj.put("rating", it) }
            obj.put("quick_replies", JSONArray(session.quickReplies.map { qr ->
                JSONObject().put("id", qr.id).put("text", qr.text).put("show_at_turn", qr.show_at_turn)
            }))
            obj.put("soft_limit_warning", session.showSoftLimitWarning)
            val data = JSONObject(session.webhookData as Map<*, *>)
            if (data.toString().length <= MAX_DATA_CHARS) {
                obj.put("data", data)
            } else {
                Log.info("Chat step ${session.stepId}: the webhook data is too large to keep if the app is closed")
            }
            obj.put("start", session.startTime)
            obj.put("auto_done", session.autoMessagesDone)
            obj.put("awaiting", session.awaitingReply)
            return obj
        }

        @Suppress("UNCHECKED_CAST")
        internal fun decode(obj: JSONObject?, stepId: String): ChatSession? {
            obj ?: return null
            val session = ChatSession(stepId)
            val arr = obj.optJSONArray("messages") ?: JSONArray()
            session.messages = (0 until arr.length()).mapNotNull { i ->
                val m = arr.optJSONObject(i) ?: return@mapNotNull null
                val role = runCatching { ChatRole.valueOf(m.optString("role")) }.getOrNull() ?: return@mapNotNull null
                val media = m.optJSONObject("media")?.let {
                    ChatMedia(type = it.optString("type", "image"), url = it.optString("url").takeIf { u -> u.isNotEmpty() }, alt_text = it.optString("alt_text").takeIf { a -> a.isNotEmpty() })
                }
                ChatMessage(id = m.optString("id"), role = role, content = m.optString("content"), media = media, timestamp = m.optLong("timestamp", System.currentTimeMillis()))
            }
            session.inputText = obj.optString("input")
            session.userTurnCount = obj.optInt("turns")
            session.isCompleted = obj.optBoolean("completed")
            if (obj.has("rating")) session.currentRating = obj.optInt("rating")
            val qrs = obj.optJSONArray("quick_replies") ?: JSONArray()
            session.quickReplies = (0 until qrs.length()).mapNotNull { i ->
                val q = qrs.optJSONObject(i) ?: return@mapNotNull null
                ChatQuickReply(id = q.optString("id"), text = q.optString("text"), show_at_turn = if (q.has("show_at_turn")) q.optInt("show_at_turn") else null)
            }
            session.showSoftLimitWarning = obj.optBoolean("soft_limit_warning")
            (fromJsonAny(obj.optJSONObject("data")) as? Map<String, Any?>)?.forEach { (k, v) -> if (v != null) session.webhookData[k] = v }
            session.startTime = obj.optLong("start", System.currentTimeMillis())
            session.seeded = true
            session.autoMessagesDone = obj.optBoolean("auto_done")
            // Welcome messages cut short by the process ending play on, skipping the ones already shown.
            session.autoMessagesStarted = session.autoMessagesDone
            // The request the process lost is sent again, once.
            session.resendOnShow = obj.optBoolean("awaiting") && !session.isCompleted
            return session
        }
    }
}
