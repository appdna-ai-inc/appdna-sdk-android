package ai.appdna.sdk.messages

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.content.Context
import android.net.Uri
import android.content.Intent
import android.os.Handler
import android.os.Looper
import ai.appdna.sdk.AppDNA
import ai.appdna.sdk.Log
import ai.appdna.sdk.events.EventTracker
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.QueryDocumentSnapshot

/**
 * Listens for journey-triggered in-app messages at
 * `orgs/{orgId}/apps/{appId}/users/{id}/pending_messages` and
 * renders them via `InAppMessageView` (the same composable used for
 * remote-config-driven messages — modal/fullscreen/banner/tooltip with
 * full styling + rich media). Writes `consumed: true` back to Firestore
 * after display so repeat polls / the REST fallback don't double-deliver.
 *
 * WHICH ids it listens on. A journey enrols the unit the server keys every event on, `user_id ?: anon_id`,
 * and delivers each of its in-app steps to that enrolment's id for the life of the enrolment. So:
 *  - from configure, the device's ANON id — an anonymous user's journey writes there (the listener used to start only
 *    at `identify`, so those messages were marked sent and never drawn);
 *  - after `identify`, the anon id AND the user id — a journey that enrolled the device anonymously keeps writing to
 *    the anon id after the user signs in, and a journey that enrols the signed-in user writes to the user id;
 *  - after `reset()`, the anon id again (the anon id survives a reset; the signed-out user's id is dropped).
 * [listenIds] is that rule; [follow] applies it, keeping a listener that is still wanted (no restart, so no
 * re-delivery) and remembering what it presented (a delivery is drawn at most once per process).
 */
internal class PendingMessageListener(
    private val eventTracker: EventTracker?,
    private val appContext: Context?,
    subscribe: Subscribe? = null,
    present: Present? = null,
    schedule: Schedule? = null,
    observeActivation: ObserveActivation? = null,
) {
    /** One delivery document as a subscription hands it over. */
    internal class Delivery(val id: String, val data: Map<String, Any?>, val markConsumed: () -> Unit)

    /** Opens a listener on one user's unconsumed deliveries; returns its remover, or null when there is no Firestore. */
    internal fun interface Subscribe {
        fun open(orgId: String, appId: String, id: String, onAdded: (Delivery) -> Unit): (() -> Unit)?
    }

    /** Draws a delivery: `onShown(true)` once on screen, `onShown(false)` when it cannot be drawn yet; `onClosed` on dismiss. */
    internal fun interface Present {
        fun show(deliveryId: String, config: MessageConfig, onShown: (Boolean) -> Unit, onClosed: () -> Unit)
    }

    /** Runs `block` after `seconds` — the retry of a delivery that could not be drawn yet. */
    internal fun interface Schedule {
        fun after(seconds: Double, block: () -> Unit)
    }

    /**
     * Calls `block` each time an activity of the app resumes; returns what stops that. The default registers
     * `Application.ActivityLifecycleCallbacks` on [appContext] (none without one).
     */
    internal fun interface ObserveActivation {
        fun observe(block: () -> Unit): () -> Unit
    }

    private class Pending(val key: String, val ownerKey: String, val delivery: Delivery, val config: MessageConfig)

    private val subscribeFn: Subscribe = subscribe ?: Subscribe(::firestoreSubscribe)
    private val presentFn: Present = present ?: Present(::presentOnForegroundActivity)
    private val scheduleFn: Schedule = schedule ?: Schedule { seconds, block ->
        Handler(Looper.getMainLooper()).postDelayed({ block() }, (seconds * 1000).toLong())
    }
    private val lock = Any()
    /** A live subscription; `remove` is null while the listener is being opened. Compared by identity. */
    private class Subscription(val remove: (() -> Unit)?)
    /** Live subscriptions, keyed `org/app/id`. */
    private val subscriptions = LinkedHashMap<String, Subscription>()
    /** The delivery on screen (or being put there), keyed `org/app/id/deliveryId`. */
    private var presentingKey: String? = null
    /** Deliveries that arrived while another was on screen, in arrival order. */
    private val waiting = ArrayList<Pending>()
    /** Deliveries drawn by this process — a re-delivered document is never drawn twice. */
    private val presented = HashSet<String>()
    private val attempts = HashMap<String, Int>()
    /**
     * #1783 — deliveries still undrawn when their retries ran out (a process started with no activity: an FCM data
     * message, a background job), in arrival order. Drawn when an activity next resumes; without this they waited for
     * the process to die, because the listener hands a document over once.
     */
    private val parked = ArrayList<Pending>()
    private val stopActivation: () -> Unit =
        (observeActivation ?: ObserveActivation(::observeActivityResumes)).observe { presentParked() }

    private fun observeActivityResumes(block: () -> Unit): () -> Unit {
        val app = appContext?.applicationContext as? Application ?: return {}
        val main = Handler(Looper.getMainLooper())
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            // Posted, so the activity has finished resuming when the presenter looks for it.
            override fun onActivityResumed(activity: Activity) { main.post { block() } }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        }
        app.registerActivityLifecycleCallbacks(callbacks)
        return { app.unregisterActivityLifecycleCallbacks(callbacks) }
    }

    /** An activity resumed: every parked delivery still listened on and not expired gets a fresh round of attempts. */
    private fun presentParked() {
        val now = System.currentTimeMillis()
        val due = synchronized(lock) {
            val d = parked.filter { it.ownerKey in subscriptions && it.key !in presented }
            parked.clear()
            d.forEach { attempts[it.key] = 0 }
            d
        }
        for (p in due) {
            if (decodeDelivery(p.delivery.data, now) == null) {
                Log.debug("PendingMessageListener: ${p.delivery.id} expired while waiting for an activity — dropped")
                continue
            }
            Log.debug("PendingMessageListener: activity resumed — presenting ${p.delivery.id}, parked with no activity")
            retry(p)
        }
    }

    /**
     * Listen on exactly [listenIds] under this org and app: a listener still wanted is kept as it is, one no longer
     * wanted is removed (and its waiting deliveries dropped), a new one is opened. Called at configure (once the
     * bootstrap names the org and app), on `identify` and on `reset()`.
     */
    fun follow(orgId: String, appId: String, anonId: String?, userId: String?) {
        val wanted = listenIds(anonId, userId).map { it to "$orgId/$appId/$it" }
        val wantedKeys = wanted.map { it.second }.toSet()
        val removed: List<() -> Unit>
        val toOpen: List<Pair<String, String>>
        val placeholders = HashMap<String, Subscription>()
        synchronized(lock) {
            val gone = subscriptions.keys.filter { it !in wantedKeys }
            removed = gone.mapNotNull { subscriptions.remove(it)?.remove }
            waiting.removeAll { it.ownerKey !in wantedKeys }
            parked.removeAll { it.ownerKey !in wantedKeys }
            toOpen = wanted.filter { it.second !in subscriptions }
            // Registered before the listener opens: its first snapshot (the deliveries already waiting) may arrive at once.
            for ((_, key) in toOpen) {
                val placeholder = Subscription(null)
                placeholders[key] = placeholder
                subscriptions[key] = placeholder
            }
        }
        removed.forEach { it() }
        for ((id, key) in toOpen) {
            Log.debug("PendingMessageListener: observing orgs/$orgId/apps/$appId/users/$id/pending_messages")
            val remove = subscribeFn.open(orgId, appId, id) { delivery ->
                handleIncoming(key, delivery, System.currentTimeMillis())
            }
            val stillOurs = synchronized(lock) {
                val ours = subscriptions[key] === placeholders[key]
                if (ours) {
                    if (remove != null) subscriptions[key] = Subscription(remove) else subscriptions.remove(key)
                }
                ours
            }
            if (!stillOurs) remove?.invoke()
        }
    }

    /** The ids listened on now (`org/app/id`), sorted — what a test reads. */
    val observedKeys: List<String>
        get() = synchronized(lock) { subscriptions.keys.sorted() }

    /** Tear down every listener. Called from `AppDNA.shutdown()`. */
    fun stopObserving() {
        val all = synchronized(lock) {
            val l = subscriptions.values.mapNotNull { it.remove }
            subscriptions.clear()
            waiting.clear()
            parked.clear()
            l
        }
        all.forEach { it() }
        stopActivation()
    }

    private fun handleIncoming(ownerKey: String, delivery: Delivery, now: Long) {
        val config = decodeDelivery(delivery.data, now) ?: run {
            Log.debug("PendingMessageListener: skipping ${delivery.id} (expired or missing content)")
            return
        }
        val key = "$ownerKey/${delivery.id}"
        val firstSight = synchronized(lock) {
            // Still listened on (a late callback from a removed listener is ignored), and not already drawn or queued.
            if (ownerKey !in subscriptions || key in presented || presentingKey == key || waiting.any { it.key == key }) {
                return
            }
            val first = key !in attempts
            if (first) attempts[key] = 0
            first
        }
        if (firstSight) {
            eventTracker?.track(
                "in_app_message_received",
                mapOf(
                    "delivery_id" to delivery.id,
                    "trigger" to ((delivery.data["trigger"] as? String) ?: "journey"),
                    "source" to "pending_messages",
                ),
            )
        }
        enqueue(Pending(key, ownerKey, delivery, config))
    }

    private fun enqueue(p: Pending) {
        synchronized(lock) {
            if (presentingKey != null) {
                Log.debug("PendingMessageListener: another message presenting — queueing ${p.delivery.id}")
                waiting.add(p)
                return
            }
            presentingKey = p.key
        }
        presentNow(p)
    }

    private fun presentNow(p: Pending) {
        presentFn.show(p.delivery.id, p.config, { rendered ->
            if (rendered) {
                synchronized(lock) { presented.add(p.key) }
                p.delivery.markConsumed()
                eventTracker?.track(
                    "in_app_message_shown",
                    mapOf(
                        "delivery_id" to p.delivery.id,
                        "source" to "pending_messages",
                        "message_type" to p.config.message_type.value,
                    ),
                )
            } else {
                // Not drawn (no activity yet at a cold launch): the document stays unconsumed; retried a few times.
                val n = synchronized(lock) {
                    presentingKey = null
                    val n = (attempts[p.key] ?: 0) + 1
                    attempts[p.key] = n
                    n
                }
                if (n < MAX_PRESENT_ATTEMPTS) {
                    scheduleFn.after(n.toDouble()) { retry(p) }
                } else {
                    // #1783 — no activity for the whole retry window: drawn when an activity next resumes. The
                    // document stays unconsumed meanwhile, so a cold launch still draws it too.
                    synchronized(lock) { if (parked.none { it.key == p.key }) parked.add(p) }
                    Log.debug("PendingMessageListener: no activity after $n attempts — ${p.delivery.id} waits for one")
                }
                presentNext()
            }
        }, {
            synchronized(lock) { presentingKey = null }
            presentNext()
        })
    }

    private fun retry(p: Pending) {
        val live = synchronized(lock) {
            p.ownerKey in subscriptions && p.key !in presented && presentingKey != p.key &&
                waiting.none { it.key == p.key } && parked.none { it.key == p.key }
        }
        if (live) enqueue(p)
    }

    private fun presentNext() {
        val next = synchronized(lock) {
            if (presentingKey != null || waiting.isEmpty()) return
            val n = waiting.removeAt(0)
            presentingKey = n.key
            n
        }
        presentNow(next)
    }

    private fun firestoreSubscribe(orgId: String, appId: String, id: String, onAdded: (Delivery) -> Unit): (() -> Unit)? {
        val db = AppDNA.firestoreDB ?: run {
            Log.debug("PendingMessageListener: Firestore not available — skipping")
            return null
        }
        val registration: ListenerRegistration = db.collection("orgs").document(orgId)
            .collection("apps").document(appId)
            .collection("users").document(id)
            .collection("pending_messages")
            .whereEqualTo("consumed", false)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.error("PendingMessageListener error: ${error.message}")
                    return@addSnapshotListener
                }
                val docChanges = snapshot?.documentChanges ?: return@addSnapshotListener
                for (change in docChanges) {
                    if (change.type.name != "ADDED") continue
                    val doc: QueryDocumentSnapshot = change.document
                    onAdded(Delivery(doc.id, doc.data) {
                        doc.reference.update("consumed", true)
                            .addOnFailureListener { err ->
                                Log.warning("PendingMessageListener: failed to mark consumed: ${err.message}")
                            }
                    })
                }
            }
        return { registration.remove() }
    }

    /**
     * Present the InAppMessageView composable in a `ComponentDialog`
     * hosted on the foreground Activity. Same renderer the
     * remote-config pipeline uses, so journey-delivered messages get
     * identical UX (modal/fullscreen/banner/tooltip, styling, rich
     * media) instead of a plain AlertDialog.
     */
    private fun presentOnForegroundActivity(
        messageId: String,
        config: MessageConfig,
        onShown: (Boolean) -> Unit,
        onClosed: () -> Unit,
    ) {
        val activity = currentActivity() ?: run {
            Log.warning("PendingMessageListener: no foreground activity — cannot present $messageId yet")
            onShown(false)
            return
        }

        Handler(Looper.getMainLooper()).post {
            try {
                // Kept on screen across a recreation of the host's activity (a fold, a rotation).
                InAppMessageDialog.show(
                    activity,
                    config,
                    onCTATap = { onActivity ->
                        eventTracker?.track(
                            "in_app_message_clicked",
                            mapOf(
                                "delivery_id" to messageId,
                                "cta_action" to (config.content.cta_action?.type ?: "dismiss"),
                                "source" to "pending_messages",
                            ),
                        )
                        handleCTAAction(onActivity, config.content.cta_action)
                    },
                    onDismiss = {
                        eventTracker?.track(
                            "in_app_message_dismissed",
                            mapOf(
                                "delivery_id" to messageId,
                                "source" to "pending_messages",
                            ),
                        )
                    },
                    onClosed = onClosed,
                )
                onShown(true)
            } catch (err: Throwable) {
                Log.warning("PendingMessageListener: present failed: ${err.message}")
                onShown(false)
            }
        }
    }

    private fun handleCTAAction(activity: Activity, action: CTAAction?) {
        // dismiss / unknown / a link type with no link: nothing extra (the dialog dismisses).
        val url = action?.linkToOpen ?: return
        // Config-driven URL — scheme-checked before the OS sees it.
        val uri = ai.appdna.sdk.core.URLSafety.sanitized(url, activity) ?: return
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (err: Throwable) {
            Log.warning("PendingMessageListener: open URL failed: ${err.message}")
        }
    }

    /**
     * Reflection-based foreground-activity lookup. Mirrors the iOS path
     * of grabbing the topmost view controller via UIApplication.shared.
     * Same trick used by SurveyManager.
     */
    private fun currentActivity(): Activity? {
        return try {
            val activityThread = Class.forName("android.app.ActivityThread")
            val currentMethod = activityThread.getMethod("currentActivityThread")
            val thread = currentMethod.invoke(null)
            val activitiesField = activityThread.getDeclaredField("mActivities")
            activitiesField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val activities = activitiesField.get(thread) as? android.util.ArrayMap<Any, Any>
            activities?.values?.firstNotNullOfOrNull { record ->
                val pausedField = record.javaClass.getDeclaredField("paused")
                pausedField.isAccessible = true
                if (!pausedField.getBoolean(record)) {
                    val activityField = record.javaClass.getDeclaredField("activity")
                    activityField.isAccessible = true
                    activityField.get(record) as? Activity
                } else null
            }
        } catch (e: Exception) {
            Log.warning("PendingMessageListener: failed to get current activity: ${e.message}")
            null
        }
    }

    internal companion object {
        /** How many times a delivery that could not be drawn (no activity yet, at a cold launch) is retried. */
        internal const val MAX_PRESENT_ATTEMPTS = 5

        /** The ids whose deliveries the device shows: the anon id, then the user id when it is set and different. */
        internal fun listenIds(anonId: String?, userId: String?): List<String> {
            val ids = ArrayList<String>()
            if (!anonId.isNullOrEmpty()) ids.add(anonId)
            if (!userId.isNullOrEmpty() && userId !in ids) ids.add(userId)
            return ids
        }

        /**
         * The delivery document as the listener receives it → the [MessageConfig] it presents, or null when
         * the delivery is expired or carries no `content`. The one decode `handleIncoming` runs; on the
         * companion so a test can drive it with a document read back from Firestore.
         */
        @Suppress("UNCHECKED_CAST")
        internal fun decodeDelivery(data: Map<String, Any?>, now: Long): MessageConfig? {
            // Server-side expiry filter (TTL).
            val expiresMs = (data["expires_at_ms"] as? Number)?.toLong()
            if (expiresMs != null && expiresMs < now) return null
            val content = (data["content"] as? Map<String, Any>) ?: return null
            return decodeMessageConfig(content, data.filterValues { it != null } as Map<String, Any>)
        }

        /** Decode the delivered content into the full `MessageConfig`. */
        @Suppress("UNCHECKED_CAST")
        internal fun decodeMessageConfig(
            content: Map<String, Any>,
            root: Map<String, Any>,
        ): MessageConfig {
            // Build a config-shaped Map and feed it through MessageConfigParser.
            val configMap: MutableMap<String, Any> = mutableMapOf()
            configMap["content"] = content
            val mt = (root["message_type"] as? String) ?: (content["message_type"] as? String) ?: "modal"
            configMap["message_type"] = mt
            configMap["name"] = (root["trigger_id"] as? String) ?: ""
            // Trigger rules are required by MessageConfig but irrelevant for
            // pending-message delivery (the server already decided this user
            // gets it). Provide a no-op rules block so the parser succeeds.
            configMap["trigger_rules"] = mapOf(
                "event" to "journey_delivery",
                "frequency" to "every_time",
            )

            return try {
                val wrapper = MessageConfigParser.parseMessages(mapOf("delivery" to configMap))
                wrapper["delivery"] ?: fallbackConfig(content)
            } catch (e: Throwable) {
                Log.warning("PendingMessageListener: MessageConfig decode failed (${e.message}) — using fallback")
                fallbackConfig(content)
            }
        }

        private fun fallbackConfig(content: Map<String, Any>): MessageConfig {
            return MessageConfig(
                name = "",
                message_type = MessageType.MODAL,
                content = MessageContent(
                    title = content["title"] as? String,
                    body = (content["body"] as? String) ?: (content["message"] as? String),
                    cta_text = (content["cta_text"] as? String) ?: "OK",
                ),
                trigger_rules = TriggerRules(event = "journey_delivery", frequency = "every_time"),
            )
        }
    }
}
