package ai.appdna.sdk.messages

import org.json.JSONObject

/**
 * The fake edges of a `follow_pending_messages` run (the shared fixture
 * `action_dispatch/pending_messages_follow_identity`), around the REAL [PendingMessageListener.follow] and its REAL
 * queueing, de-duplication and retry. The Firestore subscription is a store that answers the listener's
 * `consumed == false` query the way Firestore does (everything unconsumed at once when a listener opens, then each
 * new document); the screen is a presenter that draws synchronously when a foreground activity exists.
 *
 * `legacyUserOnly` replays the previous rule (nothing before identify, the user id only after it) through the same
 * listener, for the red control in `PendingMessageFollowTest`.
 */
internal class PendingFollowRig {
    private class Doc(val owner: String, val id: String, val data: Map<String, Any?>, var consumed: Boolean)

    private val docs = ArrayList<Doc>()
    private val listeners = HashMap<String, LinkedHashMap<Any, (PendingMessageListener.Delivery) -> Unit>>()
    var windowAvailable = true
    val presented = ArrayList<String>()
    private var onScreen: (() -> Unit)? = null
    private val retries = ArrayList<() -> Unit>()
    /** What the listener runs when an activity resumes (#1783). */
    private var onActive: (() -> Unit)? = null

    private fun delivery(d: Doc) = PendingMessageListener.Delivery(d.id, d.data) { d.consumed = true }

    val listener = PendingMessageListener(
        eventTracker = null,
        appContext = null,
        subscribe = { _, _, id, onAdded ->
            val token = Any()
            listeners.getOrPut(id) { LinkedHashMap() }[token] = onAdded
            // Firestore's first snapshot for `consumed == false`: everything unconsumed already there.
            docs.filter { it.owner == id && !it.consumed }.forEach { onAdded(delivery(it)) }
            ({ listeners[id]?.remove(token); Unit })
        },
        present = { deliveryId, _, onShown, onClosed ->
            if (!windowAvailable) {
                onShown(false)
            } else {
                val owner = docs.last { it.id == deliveryId }.owner
                presented.add("$owner/$deliveryId")
                onScreen = onClosed
                onShown(true)
            }
        },
        schedule = { _, block -> retries.add(block) },
        observeActivation = { block -> onActive = block; ({ onActive = null }) },
    )

    fun deliver(to: String, id: String, title: String) {
        val d = Doc(to, id, mapOf(
            "content" to mapOf("title" to title, "body" to "Fixture body.", "cta_text" to "OK"),
            "message_type" to "modal", "trigger" to "journey", "consumed" to false,
        ), false)
        docs.add(d)
        listeners[to]?.values?.toList()?.forEach { it(delivery(d)) }
    }

    fun redeliver(to: String, id: String) {
        val d = docs.lastOrNull { it.owner == to && it.id == id } ?: return
        listeners[to]?.values?.toList()?.forEach { it(delivery(d)) }
    }

    fun close() {
        val c = onScreen
        onScreen = null
        c?.invoke()
    }

    fun runRetries() {
        val r = retries.toList()
        retries.clear()
        r.forEach { it() }
    }

    /** An activity of the app resumes. */
    fun appActive() { onActive?.invoke() }

    val unconsumed: List<String> get() = docs.filter { !it.consumed }.map { "${it.owner}/${it.id}" }.sorted()

    companion object {
        /** Runs the fixture's script; the state it produced (`presented`, `listening`, `unconsumed`), or null on an unknown op. */
        fun run(action: JSONObject, legacyUserOnly: Boolean = false): Map<String, Any?>? {
            val script = action.optJSONArray("script") ?: return null
            val org = action.optString("org_id").takeIf { it.isNotEmpty() } ?: return null
            val app = action.optString("app_id").takeIf { it.isNotEmpty() } ?: return null
            val rig = PendingFollowRig()
            val listening = ArrayList<List<String>>()
            val prefix = "$org/$app/"
            fun str(o: JSONObject, k: String): String? = if (o.isNull(k)) null else o.optString(k)
            for (i in 0 until script.length()) {
                val o = script.getJSONObject(i)
                when (o.optString("op")) {
                    "window" -> rig.windowAvailable = o.optBoolean("available", true)
                    "deliver" -> rig.deliver(o.getString("to"), o.getString("id"), o.optString("title"))
                    "redeliver" -> rig.redeliver(o.getString("to"), o.getString("id"))
                    "follow" -> {
                        val anon = str(o, "anon_id")
                        val user = str(o, "user_id")
                        rig.listener.follow(org, app, if (legacyUserOnly) null else anon, user)
                        listening.add(rig.listener.observedKeys.map { it.removePrefix(prefix) })
                    }
                    "close" -> rig.close()
                    "run_retries" -> rig.runRetries()
                    "app_active" -> rig.appActive()
                    else -> return null
                }
            }
            return mapOf("presented" to rig.presented.toList(), "listening" to listening, "unconsumed" to rig.unconsumed)
        }
    }
}
