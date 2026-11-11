package ai.appdna.sdk.events

/**
 * Routes every tracked event — the host's AND the SDK's own — to in-app message and survey trigger
 * evaluation, exactly once per event, in tracking order.
 *
 * 🔴 Why this exists. Trigger evaluation used to be called from one place: the public `AppDNA.track`.
 * Every event the SDK tracks itself (`app_open`, `session_start`, `onboarding_flow_completed`, the
 * billing events, `paywall_dismissed`, …) goes to [EventTracker.track] directly, so none of them could
 * ever show a message or a survey — including the console's default in-app message trigger,
 * `app_open`. [EventTracker.track] is the one place every event passes through, so it hands each
 * event here, and nothing else calls the managers' `onEvent`.
 *
 * Three rules, each pinned by the shared fixture
 * `event_emission/sdk_events_reach_message_and_survey_triggers` (iOS `TriggerDispatcher`, same rules):
 *
 * 1. **Held until the SDK can answer.** An event tracked before the managers exist (the session start
 *    and the cold-launch `app_open` fire before the in-app message manager is built) or before the
 *    config is ready (no config cached on disk and the first fetch not yet back) is HELD, in order,
 *    and evaluated once both are true. A config already cached on disk, or bundled in the app, counts as ready at once — a
 *    stale cache is the config the SDK serves everywhere else too. The hold is bounded
 *    ([PENDING_CAP], oldest dropped) so an app that never reaches the server cannot grow it.
 * 2. **A surface never triggers on its own events.** A message does not evaluate `in_app_message_*`,
 *    a survey does not evaluate `survey_*` — the events a presentation emits — so a presentation
 *    cannot re-trigger its own surface. The other surface still sees them. Internal `_`-prefixed
 *    events reach neither.
 * 3. **No re-entry.** An event tracked WHILE an event is being evaluated is queued behind it and
 *    evaluated after, never recursively. One drain loop at a time, on whichever thread started it —
 *    the caller's thread, where a host event has always been evaluated on this platform.
 */
internal class TriggerDispatcher {
    enum class Surface(val wire: String) { MESSAGE("message"), SURVEY("survey") }

    fun interface Evaluator {
        fun evaluate(surface: Surface, event: String, properties: Map<String, Any>?)
    }

    private data class Item(val event: String, val properties: Map<String, Any>?)

    private val lock = Any()
    private var evaluator: Evaluator? = null
    private var configReady = false
    private val pending = ArrayDeque<Item>()
    private val inbox = ArrayDeque<Item>()
    private var draining = false
    private var droppedWhileHeldCount = 0

    /** Events dropped from the hold because it was full. */
    val droppedWhileHeld: Int get() = synchronized(lock) { droppedWhileHeldCount }

    /** Events currently held, oldest first. Test reader. */
    val heldEventNames: List<String> get() = synchronized(lock) { pending.map { it.event } }

    /** Called by [EventTracker.track] for every event it is handed. */
    fun record(event: String, properties: Map<String, Any>?) {
        if (surfaces(event).isEmpty()) return
        val start: Boolean
        synchronized(lock) {
            if (evaluator == null || !configReady) {
                if (pending.size >= PENDING_CAP) {
                    pending.removeFirst()
                    droppedWhileHeldCount++
                }
                pending.addLast(Item(event, properties))
                return
            }
            inbox.addLast(Item(event, properties))
            start = !draining
            if (start) draining = true
        }
        if (start) drain()
    }

    /** The managers exist. Releases the hold if the config is also ready. */
    fun attach(evaluator: Evaluator) {
        val start = synchronized(lock) {
            this.evaluator = evaluator
            releaseHeldLocked()
        }
        if (start) drain()
    }

    /**
     * The config is ready (cached on disk at launch, or a fetch reached the server). Releases the hold
     * if the managers also exist. Idempotent: a later fetch changes nothing.
     */
    fun markConfigReady() {
        val start = synchronized(lock) {
            configReady = true
            releaseHeldLocked()
        }
        if (start) drain()
    }

    /** `shutdown()`: forget the managers and everything held or queued. */
    fun detach() {
        synchronized(lock) {
            evaluator = null
            configReady = false
            pending.clear()
            inbox.clear()
        }
    }

    private fun releaseHeldLocked(): Boolean {
        if (evaluator == null || !configReady || pending.isEmpty()) return false
        inbox.addAll(pending)
        pending.clear()
        if (draining) return false
        draining = true
        return true
    }

    private fun drain() {
        while (true) {
            val next: Item
            val ev: Evaluator
            synchronized(lock) {
                val e = evaluator
                if (inbox.isEmpty() || e == null) {
                    draining = false
                    return
                }
                next = inbox.removeFirst()
                ev = e
            }
            for (surface in surfaces(next.event)) {
                // A manager that throws must not stop the next surface, the next event, or leave
                // `draining` stuck true (which would silence every trigger for the rest of the process).
                try {
                    ev.evaluate(surface, next.event, next.properties)
                } catch (t: Throwable) {
                    ai.appdna.sdk.Log.warning { "Trigger evaluation of '${next.event}' (${surface.wire}) threw: ${t.message}" }
                }
            }
        }
    }

    companion object {
        /** The events an in-app message presentation tracks. A message never triggers on these. */
        val MESSAGE_OWN_EVENTS: Set<String> = setOf(
            "in_app_message_shown",
            "in_app_message_clicked",
            "in_app_message_dismissed",
            "in_app_message_received",
        )

        /** The events a survey presentation tracks. A survey never triggers on these. */
        val SURVEY_OWN_EVENTS: Set<String> = setOf(
            "survey_shown",
            "survey_question_answered",
            "survey_completed",
            "survey_dismissed",
            "survey_feedback_submitted",
            "survey_followup_feedback_form",
            "survey_followup_prompt_review",
            "survey_followup_winback",
        )

        /** Events held while the SDK is not ready to evaluate. Oldest dropped beyond this. */
        const val PENDING_CAP = 32

        /** Which surfaces evaluate [event], in evaluation order: message, then survey (iOS order). */
        fun surfaces(event: String): List<Surface> {
            if (event.isEmpty() || event.startsWith("_")) return emptyList()
            val out = ArrayList<Surface>(2)
            if (event !in MESSAGE_OWN_EVENTS) out.add(Surface.MESSAGE)
            if (event !in SURVEY_OWN_EVENTS) out.add(Surface.SURVEY)
            return out
        }
    }
}
