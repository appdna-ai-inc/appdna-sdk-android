package ai.appdna.sdk.messages

import android.app.Activity
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
 * `orgs/{orgId}/apps/{appId}/users/{userId}/pending_messages` and
 * renders them via `InAppMessageView` (the same composable used for
 * remote-config-driven messages — modal/fullscreen/banner/tooltip with
 * full styling + rich media). Writes `consumed: true` back to Firestore
 * after display so repeat polls / the REST fallback don't double-deliver.
 */
internal class PendingMessageListener(
    private val eventTracker: EventTracker?,
    private val appContext: Context?,
) {
    private var listener: ListenerRegistration? = null
    private var isPresenting = false

    fun startObserving(orgId: String, appId: String, userId: String) {
        stopObserving()

        val db = AppDNA.firestoreDB ?: run {
            Log.debug("PendingMessageListener: Firestore not available — skipping")
            return
        }

        val path = "orgs/$orgId/apps/$appId/users/$userId/pending_messages"
        Log.debug("PendingMessageListener: observing $path")

        listener = db.collection("orgs").document(orgId)
            .collection("apps").document(appId)
            .collection("users").document(userId)
            .collection("pending_messages")
            .whereEqualTo("consumed", false)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.error("PendingMessageListener error: ${error.message}")
                    return@addSnapshotListener
                }
                val docChanges = snapshot?.documentChanges ?: return@addSnapshotListener
                val now = System.currentTimeMillis()
                for (change in docChanges) {
                    if (change.type.name != "ADDED") continue
                    handleIncoming(change.document, now)
                }
            }
    }

    fun stopObserving() {
        listener?.remove()
        listener = null
    }

    private fun handleIncoming(doc: QueryDocumentSnapshot, now: Long) {
        val data = doc.data

        val config = decodeDelivery(data, now) ?: run {
            Log.debug("PendingMessageListener: skipping ${doc.id} (expired or missing content)")
            return
        }

        eventTracker?.track(
            "in_app_message_received",
            mapOf(
                "delivery_id" to doc.id,
                "trigger" to ((data["trigger"] as? String) ?: "journey"),
                "source" to "pending_messages",
            ),
        )

        present(doc.id, config) { rendered ->
            if (rendered) {
                doc.reference.update("consumed", true)
                    .addOnFailureListener { err ->
                        Log.warning("PendingMessageListener: failed to mark consumed: ${err.message}")
                    }
                eventTracker?.track(
                    "in_app_message_shown",
                    mapOf(
                        "delivery_id" to doc.id,
                        "source" to "pending_messages",
                        "message_type" to config.message_type.value,
                    ),
                )
            }
        }
    }


    /**
     * Present the InAppMessageView composable in a `ComponentDialog`
     * hosted on the foreground Activity. Same renderer the
     * remote-config pipeline uses, so journey-delivered messages get
     * identical UX (modal/fullscreen/banner/tooltip, styling, rich
     * media) instead of a plain AlertDialog.
     */
    private fun present(
        messageId: String,
        config: MessageConfig,
        completion: (Boolean) -> Unit,
    ) {
        val activity = currentActivity() ?: run {
            Log.warning("PendingMessageListener: no foreground activity — cannot present $messageId")
            completion(false)
            return
        }

        Handler(Looper.getMainLooper()).post {
            if (isPresenting) {
                Log.debug("PendingMessageListener: another message presenting — deferring $messageId")
                completion(false)
                return@post
            }
            try {
                isPresenting = true
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
                    onClosed = { isPresenting = false },
                )
                completion(true)
            } catch (err: Throwable) {
                isPresenting = false
                Log.warning("PendingMessageListener: present failed: ${err.message}")
                completion(false)
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
