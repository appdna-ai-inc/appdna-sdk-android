package ai.appdna.sdk.events

import ai.appdna.sdk.IdentityManager
import ai.appdna.sdk.Log
import org.json.JSONObject

/**
 * Builds event envelopes from current identity and queues them.
 *
 * Each tracked event is decorated with the current
 * `experiment_exposures` list (sourced from a lazy provider to break the
 * EventTracker ↔ ExperimentManager construction cycle).
 *
 * Each event carries an `environment` tag (`"production"` /
 * `"sandbox"`) sourced from `Configuration.environment`.
 *
 * Each event optionally carries `context.screen` for
 * zero-code attribution. The currently-visible screen name is supplied by
 * NavigationInterceptor via [setScreenProvider].
 */
internal class EventTracker(
    private val identityManager: IdentityManager,
    private val appVersion: String,
    private val environmentTag: String? = null
) {
    private var eventQueue: EventQueue? = null
    private var analyticsConsent = true

    /**
     * Lazy supplier of current experiment exposures. Wired by [AppDNA.configure]
     * after both EventTracker and ExperimentManager are constructed. Returning
     * null/empty list omits the field from the envelope (iOS parity).
     */
    private var exposureProvider: (() -> List<ExperimentExposure>)? = null

    /**
     * Lazy supplier of the currently-visible screen name.
     * Returns null when no screen is being tracked yet (early app start).
     */
    private var screenProvider: (() -> String?)? = null

    /**
     * Lazy supplier of the most-recently-received push id.
     * Folded into `context.push_id` so subsequent events can be attributed
     * to the push that triggered the session. Provider returns null when no
     * recent push exists (or its 30-minute window has expired).
     */
    private var pushIdProvider: (() -> String?)? = null

    fun setEventQueue(queue: EventQueue) {
        this.eventQueue = queue
    }

    /**
     * Wire the exposure source (typically `ExperimentManager.getExposures()`).
     * Setting this to null disables the field (used in tests).
     */
    fun setExperimentExposureProvider(provider: (() -> List<ExperimentExposure>)?) {
        this.exposureProvider = provider
    }

    /**
     * Wire the screen-name source (typically NavigationInterceptor).
     */
    fun setScreenProvider(provider: (() -> String?)?) {
        this.screenProvider = provider
    }

    /**
     * Wire the push-id source (typically reads
     * `PushSessionContext.currentPushId(...)`). Setting null disables the field.
     */
    fun setPushIdProvider(provider: (() -> String?)?) {
        this.pushIdProvider = provider
    }

    /**
     * Apply the persisted consent decision at `configure()` time.
     * Unlike [setConsent], this does NOT purge the queue — events persisted during an earlier,
     * consented session are not a revocation, and startup is not the moment to delete them.
     */
    fun setInitialConsent(analytics: Boolean) {
        analyticsConsent = analytics
    }

    fun setConsent(analytics: Boolean) {
        analyticsConsent = analytics
        // Revoking consent MUST purge any queued-but-unsent events (memory
        // + on-disk) WITHOUT uploading — else the server-side consent gate is defeated by a later
        // flush of events captured while consent was true.
        if (!analytics) {
            eventQueue?.clear()
        }
    }

    /** Whether analytics consent is currently granted. */
    val isConsentGranted: Boolean get() = analyticsConsent

    /**
     * Track an event. If consent is false, the event is silently dropped.
     */
    fun track(event: String, properties: Map<String, Any>? = null, clientSeq: Long? = null) {
        if (!analyticsConsent) {
            Log.debug { "Event '$event' dropped — analytics consent is false" }
            return
        }

        // Surface any silently-dropped events (cap/quota evictions) as a
        // _sdk_events_dropped meta-event so the loss is SERVER-VISIBLE. Guard against re-entry.
        if (event != "_sdk_events_dropped") {
            // PEEK (don't reset) — the count is decremented only after the meta is durable
            // (in emitDroppedMeta's onPersisted), so a crash before the meta lands re-emits it (no under-count).
            val dropped = DroppedEventsCounter.peek()
            if (dropped > 0) emitDroppedMeta(dropped)
        }

        val exposures = try {
            exposureProvider?.invoke()
        } catch (e: Exception) {
            // Never let an exposure read break event tracking
            Log.warning { "Exposure provider threw: ${e.message}" }
            null
        }

        val screen = try {
            screenProvider?.invoke()
        } catch (e: Exception) {
            Log.warning { "Screen provider threw: ${e.message}" }
            null
        }

        val pushId = try {
            pushIdProvider?.invoke()
        } catch (e: Exception) {
            // Never let push-id lookup break event tracking.
            Log.warning { "PushId provider threw: ${e.message}" }
            null
        }

        val envelope = EventSchema.buildEnvelope(
            eventName = event,
            properties = properties,
            identity = identityManager.currentIdentity,
            sessionId = identityManager.sessionId,
            appVersion = appVersion,
            analyticsConsent = analyticsConsent,
            experimentExposures = exposures,
            environment = environmentTag,
            screen = screen,
            pushId = pushId,
            clientSeq = clientSeq // A drained pre-init event carries its reserved seq
        )

        eventQueue?.enqueue(envelope)
        Log.debug { "Tracked event: $event" }
    }

    /** Build + enqueue the _sdk_events_dropped meta-event directly (NOT via track(),
     * to avoid re-entrancy). Count = events evicted since the last drain. */
    private fun emitDroppedMeta(count: Int) {
        // Enrich the meta-event with the same screen / experiment-exposure / push context a
        // normal track() attaches, matching iOS emitDroppedMeta. Was omitting all three, so
        // _sdk_events_dropped landed with null screen/experiment_exposures/push_id on Android while iOS
        // populated them — a field-level envelope divergence on the same event name.
        val exposures = try { exposureProvider?.invoke() } catch (e: Exception) { null }
        val screen = try { screenProvider?.invoke() } catch (e: Exception) { null }
        val pushId = try { pushIdProvider?.invoke() } catch (e: Exception) { null }
        val envelope = EventSchema.buildEnvelope(
            eventName = "_sdk_events_dropped",
            properties = mapOf("count" to count),
            identity = identityManager.currentIdentity,
            sessionId = identityManager.sessionId,
            appVersion = appVersion,
            analyticsConsent = analyticsConsent,
            experimentExposures = exposures,
            environment = environmentTag,
            screen = screen,
            pushId = pushId
        )
        // Decrement by exactly `count` ONLY after the meta is durably persisted (never a
        // zero-reset). Crash before persist → counter keeps `count` → re-emit (no under-count).
        eventQueue?.enqueue(envelope) { DroppedEventsCounter.subtract(count) }
        Log.debug { "Emitted _sdk_events_dropped meta-event (count=$count)" }
    }
}
