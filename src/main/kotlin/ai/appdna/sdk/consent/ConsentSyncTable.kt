package ai.appdna.sdk.consent

/**
 * The consent-signal state table: a PURE transition function. The sender ([ConsentSignalSender]) calls [next] on its
 * serial executor for every event, persists the persisted half of the result, and only then runs the effects — so a
 * SEND's "ack := unknown" is written ahead of the request (a crash at any point leaves either the slot or the unknown
 * mark, and the next `configure` sends again).
 *
 * The signal exists because the SDK drops every analytics event of a user who declined, on the device: without it,
 * the server cannot honour that decision on messages it sends itself. Mirrors iOS `ConsentSyncTable.swift`; both are
 * held to the shared `event_emission/consent_*` fixtures and to an exhaustive enumeration test.
 *
 * Rows (numbers are the table's):
 *  1  setConsent(v), or a user change while the effective decision is declined → new slot; cancel a timer; EVALUATE if idle
 *  2  user change while granted → nothing
 *  3  configure → at most ONE send, if (a) a slot, (b) E declined, or (c) ack unknown / known ≠ E and dropped ≠ E
 *  4  2xx → ack := known(T), dropped := none; clear the slot if it is that generation, else EVALUATE
 *  5  transient → retry the same body (≤ 3 retries), or EVALUATE a newer slot
 *  6  exhaustion → keep the slot for the next configure
 *  7  retry fire → re-send the same body (a stale generation does nothing)
 *  8  drop (any other 4xx) → dropped := T, clear the slot; or EVALUATE a newer slot
 * Rows 0, 9, 10 and 11 (before a sender exists, relaunch, reinstall, end of a sender's epoch) are the sender's.
 */
internal object ConsentSyncTable {

    /** The triple a signal carries. */
    data class Triple(val anonId: String, val userId: String?, val analytics: Boolean)

    sealed class Ack {
        /** This install has never decided to send. */
        object None : Ack()
        /** A send went out and its outcome is not known (written ahead of every send). */
        object Unknown : Ack()
        /** The last triple a 2xx acknowledged. */
        data class Known(val triple: Triple) : Ack()
    }

    /** The pending decision: generation, triple, retries spent, and the body's `ts_ms` (fixed per generation). */
    data class Slot(val generation: Long, val triple: Triple, val attempts: Int, val tsMs: Long)

    data class State(
        // persisted: the ack record (encrypted prefs)
        val ack: Ack = Ack.None,
        val dropped: Triple? = null,
        // persisted: the slot store (plain prefs)
        val slot: Slot? = null,
        val lastBodyTs: Long? = null,
        val lastGeneration: Long = 0,
        // in memory
        val inFlight: Slot? = null,
        val timer: Long? = null,
    )

    sealed class Event {
        /** Row 1. [effective] is E after the decision was applied. */
        data class SetConsent(val effective: Triple, val nowMs: Long) : Event()
        /** Rows 1 / 2: `identify` or `reset` changed the user; [effective] is E with the new user. */
        data class UserChanged(val effective: Triple, val nowMs: Long) : Event()
        /** Row 3. [rowZeroCall] is true when a `setConsent` was made while no sender existed (row 0). */
        data class Configure(val effective: Triple, val rowZeroCall: Boolean, val nowMs: Long) : Event()
        /** Row 4. */
        data class Success(val generation: Long) : Event()
        /** Rows 5 / 6. */
        data class Transient(val generation: Long, val retryAfterSeconds: Long?) : Event()
        /** Row 8. */
        data class Drop(val generation: Long) : Event()
        /** Row 7. */
        data class RetryFire(val generation: Long) : Event()
    }

    sealed class Effect {
        data class Send(val slot: Slot) : Effect()
        /** Arm the retry for [generation]; [retry] is 1, 2 or 3. */
        data class ArmTimer(val generation: Long, val retry: Int, val retryAfterSeconds: Long?) : Effect()
        object CancelTimer : Effect()
    }

    /** At most three in-session retries per generation (four requests in all), then the next `configure`. */
    const val MAX_RETRIES = 3

    data class Result(val state: State, val effects: List<Effect>)

    fun next(state: State, event: Event): Result {
        val fx = mutableListOf<Effect>()
        val s = when (event) {
            is Event.SetConsent -> replace(state, event.effective, event.nowMs, fx)
            is Event.UserChanged ->
                if (event.effective.analytics) state // row 2: dropped kept
                else replace(state, event.effective, event.nowMs, fx)
            is Event.Configure -> configure(state, event, fx)
            is Event.Success -> {
                val g = state.inFlight
                if (g == null || g.generation != event.generation) state
                else {
                    val acked = state.copy(inFlight = null, ack = Ack.Known(g.triple), dropped = null)
                    if (acked.slot?.generation == g.generation) acked.copy(slot = null) else evaluate(acked, fx)
                }
            }
            is Event.Transient -> {
                val g = state.inFlight
                if (g == null || g.generation != event.generation) state
                else {
                    val idle = state.copy(inFlight = null)
                    val slot = idle.slot
                    when {
                        slot?.generation == g.generation && slot.attempts < MAX_RETRIES -> {
                            val retry = slot.attempts + 1
                            fx += Effect.ArmTimer(slot.generation, retry, event.retryAfterSeconds)
                            idle.copy(slot = slot.copy(attempts = retry), timer = slot.generation)
                        }
                        slot?.generation == g.generation -> idle // row 6: exhausted, kept for configure
                        else -> evaluate(idle, fx)
                    }
                }
            }
            is Event.Drop -> {
                val g = state.inFlight
                if (g == null || g.generation != event.generation) state
                else {
                    val idle = state.copy(inFlight = null)
                    if (idle.slot?.generation == g.generation) idle.copy(dropped = g.triple, slot = null)
                    else evaluate(idle, fx)
                }
            }
            is Event.RetryFire -> {
                val slot = state.slot
                if (state.timer != event.generation) state
                else if (slot == null || slot.generation != event.generation) state.copy(timer = null)
                else send(state.copy(timer = null), slot, fx)
            }
        }
        return Result(s, fx)
    }

    /** Row 1: a new generation with a new body; cancel a timer; EVALUATE when idle, else keep the slot. */
    private fun replace(state: State, e: Triple, nowMs: Long, fx: MutableList<Effect>): State {
        val withSlot = newSlot(state, e, nowMs).copy(dropped = null)
        val cancelled = if (withSlot.timer != null) {
            fx += Effect.CancelTimer
            withSlot.copy(timer = null)
        } else withSlot
        return if (cancelled.inFlight == null) evaluate(cancelled, fx) else cancelled
    }

    private fun configure(state: State, ev: Event.Configure, fx: MutableList<Effect>): State {
        // A setConsent made before the sender existed clears `dropped` before the triggers are read.
        val s = if (ev.rowZeroCall) state.copy(dropped = null) else state
        val e = ev.effective
        val a = s.slot != null
        val b = !e.analytics
        val ackDiffers = when (val ack = s.ack) {
            Ack.Unknown -> true
            is Ack.Known -> ack.triple != e
            Ack.None -> false
        }
        val c = ackDiffers && s.dropped != e
        if (!(a || b || c)) return s
        val withSlot = newSlot(s, e, ev.nowMs)
        return send(withSlot, withSlot.slot!!, fx)
    }

    /** EVALUATE: only when nothing is in flight and no timer is armed. */
    private fun evaluate(state: State, fx: MutableList<Effect>): State {
        if (state.inFlight != null || state.timer != null) return state
        val slot = state.slot ?: return state
        val ack = state.ack
        return if (ack is Ack.Known && ack.triple == slot.triple) state.copy(slot = null)
        else send(state, slot, fx)
    }

    /** SEND: ack := unknown (persisted before the request), in flight := the slot's generation. */
    private fun send(state: State, slot: Slot, fx: MutableList<Effect>): State {
        fx += Effect.Send(slot)
        return state.copy(ack = Ack.Unknown, inFlight = slot)
    }

    /** A new generation whose body takes `ts_ms = max(now, last_body_ts + 1)`, strictly increasing per device. */
    private fun newSlot(state: State, e: Triple, nowMs: Long): State {
        val g = state.lastGeneration + 1
        val ts = state.lastBodyTs?.let { maxOf(nowMs, it + 1) } ?: nowMs
        return state.copy(slot = Slot(g, e, 0, ts), lastBodyTs = ts, lastGeneration = g)
    }
}
