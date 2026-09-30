package ai.appdna.sdk.billing

import ai.appdna.sdk.AppDNA
import ai.appdna.sdk.Environment
import ai.appdna.sdk.IdentityManager
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
 * A REAL event pipeline (EventTracker → EventQueue → EventDatabase, batchSize 0 so nothing uploads)
 * installed as the SDK's tracker, so a test reads back the envelopes the billing layer produced.
 */
internal class BillingTestPipeline(ctx: Context, name: String) {
    val tracker: EventTracker
    private val db = EventDatabase(ctx, 10_000, "billing_${name.take(40)}.db")

    /** Shut down on close(): a leaked queue keeps its auto-flush coroutine alive for the whole test JVM. */
    private val queue: EventQueue

    init {
        ClientSeqCounter.init(ctx)
        DroppedEventsCounter.init(ctx)
        db.clearAll()
        tracker = EventTracker(IdentityManager(LocalStorage(ctx)), "1.0", "sandbox")
        queue = EventQueue(
            apiClient = ApiClient("adn_test_billing", Environment.PRODUCTION),
            eventDatabase = db,
            connectivityMonitor = null,
            batchSize = 0,
            flushInterval = Long.MAX_VALUE,
        )
        tracker.setEventQueue(queue)
        AppDNA.installEventTrackerForTest(tracker)
    }

    fun events(): List<Pair<String, JSONObject>> =
        db.loadAll().map { JSONObject(it) }.map { it.getString("event_name") to (it.optJSONObject("properties") ?: JSONObject()) }

    fun names(): List<String> = events().map { it.first }
    fun count(name: String): Int = events().count { it.first == name }
    fun props(name: String): JSONObject = events().first { it.first == name }.second

    fun close() {
        queue.shutdown()
        AppDNA.installEventTrackerForTest(null)
        db.clearAll()
    }
}
