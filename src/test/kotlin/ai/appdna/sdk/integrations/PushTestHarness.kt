package ai.appdna.sdk.integrations

import ai.appdna.sdk.AppDNA
import ai.appdna.sdk.AppDNAPushDelegate
import ai.appdna.sdk.Environment
import ai.appdna.sdk.IdentityManager
import ai.appdna.sdk.PushPayload
import ai.appdna.sdk.events.ClientSeqCounter
import ai.appdna.sdk.events.DroppedEventsCounter
import ai.appdna.sdk.events.EventQueue
import ai.appdna.sdk.events.EventTracker
import ai.appdna.sdk.network.ApiClient
import ai.appdna.sdk.storage.EventDatabase
import ai.appdna.sdk.storage.LocalStorage
import android.content.Context
import org.json.JSONObject

/**
 * SPEC-497 §9 — a REAL push pipeline for the forwarding tests: the SDK's own PushTokenManager on a
 * real EventTracker → EventQueue → EventDatabase (batchSize 0, so nothing leaves the device; no
 * ApiClient, so no network call), injected into `AppDNA.push`, with a recording push delegate.
 */
internal class PushTestHarness(private val ctx: Context, name: String) {
    val storage = LocalStorage(ctx)
    private val tracker = EventTracker(IdentityManager(storage), "1.0", "sandbox")
    private val db = EventDatabase(ctx, 10_000, "push_${name.take(40)}.db")
    val received = mutableListOf<Pair<PushPayload, Boolean>>()
    val tapped = mutableListOf<Pair<PushPayload, String?>>()
    val routes = mutableListOf<Pair<String, String>>()

    init {
        ClientSeqCounter.init(ctx)
        DroppedEventsCounter.init(ctx)
        db.clearAll()
        tracker.setEventQueue(
            EventQueue(
                apiClient = ApiClient("adn_test_push", Environment.PRODUCTION),
                eventDatabase = db,
                connectivityMonitor = null,
                batchSize = 0,
                flushInterval = Long.MAX_VALUE,
            ),
        )
        AppDNA.push.contextOverride = ctx
        PushIdempotency.resetForTesting()
        AppDNA.push.manager = PushTokenManager(ctx, storage, tracker, null)
        AppDNA.push.setDelegate(object : AppDNAPushDelegate {
            override fun onPushReceived(notification: PushPayload, inForeground: Boolean) {
                received += notification to inForeground
            }
            override fun onPushTapped(notification: PushPayload, actionId: String?) {
                tapped += notification to actionId
            }
        })
        PushTapRouter.routeSink = { t, v -> routes += t to v }
    }

    /** (event_name, properties) of every persisted envelope, in order. */
    fun events(): List<Pair<String, JSONObject>> = db.loadAll().map { JSONObject(it) }.map {
        it.getString("event_name") to (it.optJSONObject("properties") ?: JSONObject())
    }

    fun close() {
        db.clearAll()
        AppDNA.push.setDelegate(null)
        AppDNA.push.manager = null
        AppDNA.push.contextOverride = null
        PushTapRouter.routeSink = null
    }
}
