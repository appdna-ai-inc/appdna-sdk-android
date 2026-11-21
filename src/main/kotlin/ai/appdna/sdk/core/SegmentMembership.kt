package ai.appdna.sdk.core

import org.json.JSONArray
import org.json.JSONObject
import ai.appdna.sdk.Log

/**
 * An in-app message's segment targeting, as published: `{segments, exclude_segments}`. Blank and duplicate ids are
 * dropped; null when neither list has an id.
 */
data class SegmentAudience(
    val segments: List<String> = emptyList(),
    val exclude_segments: List<String> = emptyList(),
) {
    val ids: Set<String> get() = (segments + exclude_segments).toSet()

    companion object {
        private fun ids(v: Any?): List<String> = when (v) {
            is List<*> -> v
            is JSONArray -> (0 until v.length()).map { v.opt(it) }
            else -> emptyList()
        }.mapNotNull { (it as? String)?.trim()?.takeIf { s -> s.isNotEmpty() } }.distinct()

        fun parse(raw: Any?): SegmentAudience? {
            val map: Map<*, *> = when (raw) {
                is Map<*, *> -> raw
                is JSONObject -> raw.keys().asSequence().associateWith { raw.opt(it) }
                else -> return null
            }
            val a = SegmentAudience(ids(map["segments"]), ids(map["exclude_segments"]))
            return if (a.segments.isEmpty() && a.exclude_segments.isEmpty()) null else a
        }
    }
}

/**
 * Which segments the current user is in, as AppDNA resolved them (`POST /api/v1/sdk/segments`), for the segment
 * targeting of in-app messages. The same definition of a segment a journey uses, resolved on the server: trait
 * segments, behaviour audiences and personas alike.
 *
 * The answer is kept for ONE user (`userKey`) and for the ids it was asked about. It is persisted, so a cold start
 * without a network still has the previous launch's answer, and a different user never reads it.
 *
 * The gate fails closed: a message with segments is not shown until the answer for this user covers its ids.
 */
internal object SegmentMembership {
    data class Answer(
        val userKey: String,
        val asked: Set<String>,
        val memberOf: Set<String>,
        val unresolved: Set<String>,
        val fetchedAtMs: Long,
    )

    private const val PREFS = "ai.appdna.segments"
    private const val KEY_ANSWER = "answer"
    const val PATH = "/api/v1/sdk/segments"
    /** The server's cap on ids per request. */
    const val MAX_IDS = 50

    private var prefs: android.content.SharedPreferences? = null

    /** The current answer. Setting it is memory-only (the fixtures use it); [store] is the network path. */
    @Volatile
    var answer: Answer? = null

    private val inFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    /** The key an answer is kept under: the identified user, else the device's anonymous id. */
    fun userKey(anonId: String?, userId: String?): String? = when {
        !userId.isNullOrBlank() -> "u:$userId"
        !anonId.isNullOrBlank() -> "a:$anonId"
        else -> null
    }

    /**
     * Does [audience] admit the user [userKey], given [answer]? No segments: yes. Otherwise only with an answer for this
     * user that was asked about every id of the message, none of them unresolved; then the user must be in at least one
     * `segments` id (when there are any) and in no `exclude_segments` id.
     */
    fun allows(audience: SegmentAudience?, userKey: String?, answer: Answer?): Boolean {
        if (audience == null || (audience.segments.isEmpty() && audience.exclude_segments.isEmpty())) return true
        if (answer == null || userKey == null || answer.userKey != userKey) return false
        if (audience.ids.any { it !in answer.asked || it in answer.unresolved }) return false
        if (audience.segments.isNotEmpty() && audience.segments.none { it in answer.memberOf }) return false
        return audience.exclude_segments.none { it in answer.memberOf }
    }

    /** Whether the answer for [userKey] covers [ids] and is younger than [ttlMs]. */
    fun isFresh(ids: Set<String>, userKey: String?, ttlMs: Long, nowMs: Long = System.currentTimeMillis()): Boolean {
        val a = answer ?: return false
        return userKey != null && a.userKey == userKey && a.asked.containsAll(ids) && nowMs - a.fetchedAtMs < ttlMs
    }

    /** Load the persisted answer. Called from `configure`. */
    fun attach(context: android.content.Context) {
        val p = context.applicationContext.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        prefs = p
        if (answer == null) answer = p.getString(KEY_ANSWER, null)?.let { decode(it) }
    }

    /** The server's answer for [userKey], asked about [asked]. Null when the body is not an answer. */
    fun parseAnswer(body: String?, userKey: String, asked: Set<String>, nowMs: Long = System.currentTimeMillis()): Answer? {
        val o = try { JSONObject(body ?: "") } catch (_: Exception) { return null }
        fun set(k: String): Set<String>? = o.optJSONArray(k)?.let { a -> (0 until a.length()).mapNotNull { a.opt(it) as? String }.toSet() }
        val member = set("member_of") ?: return null
        return Answer(userKey, asked, member intersect asked, (set("unresolved") ?: emptySet()) intersect asked, nowMs)
    }

    /** Keep [a] as the current answer, in memory and on disk. */
    fun store(a: Answer) {
        answer = a
        prefs?.edit()?.putString(KEY_ANSWER, encode(a))?.apply()
    }

    /** Forget the answer (sign-out). The next user asks again. */
    fun clear() {
        answer = null
        prefs?.edit()?.remove(KEY_ANSWER)?.apply()
    }

    /**
     * Ask the server about [ids] for this user unless a fresh answer already covers them, one request at a time. On a
     * failed request the previous answer stays (for its own user only); the gate keeps failing closed otherwise.
     */
    suspend fun refresh(
        ids: Set<String>, anonId: String, userId: String?, ttlMs: Long,
        post: suspend (path: String, body: String) -> Pair<Int?, String?>,
    ) {
        val key = userKey(anonId, userId) ?: return
        val wanted = ids.filter { it.isNotBlank() }.toSortedSet().take(MAX_IDS).toSet()
        if (wanted.isEmpty() || isFresh(wanted, key, ttlMs)) return
        if (!inFlight.compareAndSet(false, true)) return
        try {
            val body = JSONObject()
                .put("anon_id", anonId)
                .put("user_id", userId ?: JSONObject.NULL)
                .put("segment_ids", JSONArray(wanted.toList()))
            val (status, raw) = post(PATH, body.toString())
            if (status != null && status in 200..299) {
                parseAnswer(raw, key, wanted)?.let { store(it) }
                    ?: Log.warning { "[Segments] unreadable membership answer" }
            } else {
                Log.debug("[Segments] membership request failed (${status ?: "network"}); segment-targeted messages stay hidden")
            }
        } finally {
            inFlight.set(false)
        }
    }

    internal fun resetForTest() { answer = null; inFlight.set(false) }

    private fun encode(a: Answer): String = JSONObject()
        .put("user_key", a.userKey)
        .put("asked", JSONArray(a.asked.toList()))
        .put("member_of", JSONArray(a.memberOf.toList()))
        .put("unresolved", JSONArray(a.unresolved.toList()))
        .put("fetched_at_ms", a.fetchedAtMs)
        .toString()

    private fun decode(s: String): Answer? = try {
        val o = JSONObject(s)
        fun set(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).mapNotNull { a.opt(it) as? String }.toSet() } ?: emptySet()
        Answer(o.getString("user_key"), set("asked"), set("member_of"), set("unresolved"), o.getLong("fetched_at_ms"))
    } catch (_: Exception) { null }
}
