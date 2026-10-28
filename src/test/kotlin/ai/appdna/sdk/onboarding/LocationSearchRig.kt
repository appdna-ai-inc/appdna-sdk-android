package ai.appdna.sdk.onboarding

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.fail

/**
 * Drives the REAL [LocationSuggestionSearch], the one state holder both Android location surfaces bind
 * to, with its three dependencies injected:
 *
 *   - the debounce clock is MANUAL: a debounced search stays pending until [advance] passes its due
 *     time, so CI timing can never fire it early (a held stub would already have counted it);
 *   - the autocomplete fetch answers at once from a table keyed by query, through the REAL suggestion
 *     decoder ([decodeLocationSuggestions]), and records every listing request;
 *   - the resolve call is HELD: [tap] records the body the holder built, and [reply] answers it
 *     through the REAL reply parser ([LocationResolve.outcome]).
 *
 * The holder's writes are captured the way a composable's `attach` persists them.
 *
 * [echo] is what SwiftUI's `.onChange(of:)` does after a programmatic set: it calls `textChanged` with
 * the holder's text — only when that text differs from the last value delivered. A Compose text field
 * never sends one; a script that never calls it is the Compose shape, and the shared fixtures run both.
 */
internal class LocationSearchRig(
    val settings: LocationSearchSettings,
    storedAnswer: Any? = null,
    /** The autocomplete stub's replies, by query: each a JSON array of suggestion objects. */
    private val autocomplete: Map<String, JSONArray> = emptyMap(),
) {
    /** Every listing request the fetch was asked for, in order. */
    val listings = mutableListOf<LocationListingRequest>()
    val autocompleteQueries: List<String> get() = listings.map { it.query }

    /** Every resolve body sent, in order. */
    val resolveBodies = mutableListOf<Map<String, String>>()
    private val pendingResolves = ArrayDeque<(LocationResolveOutcome) -> Unit>()
    val resolvesInFlight: Int get() = pendingResolves.size

    /** What the composable would have persisted. `null` = no answer. */
    var stored: Any? = storedAnswer
        private set
    var storeWrites = 0
        private set

    // The manual clock.
    var now = 0L
        private set
    private class PendingWork(val due: Long, val seq: Int, val work: () -> Unit) {
        var cancelled = false
    }
    private val timers = mutableListOf<PendingWork>()
    private var timerSeq = 0

    val holder: LocationSuggestionSearch = LocationSuggestionSearch(
        storedAnswer = storedAnswer,
        fetch = { listing, done ->
            listings += listing
            done(decodeLocationSuggestions(autocomplete[listing.query] ?: JSONArray()))
        },
        resolve = { body, done ->
            resolveBodies += body
            pendingResolves.addLast(done)
        },
        schedule = { delayMs, work ->
            timerSeq += 1
            val timer = PendingWork(now + delayMs, timerSeq, work)
            timers += timer
            ({ timer.cancelled = true })
        },
    )

    /** The last text `textChanged` was given — what SwiftUI's `.onChange` compares against. */
    private var lastDelivered: String = holder.text

    /** Every typed text (a String answer) the holder stored, in order. */
    val storedTexts = mutableListOf<String>()

    init {
        holder.attach { value ->
            stored = value
            storeWrites += 1
            (value as? String)?.let { storedTexts += it }
        }
    }

    /** A user edit: the text field changed, and the composable forwards it. */
    fun type(text: String) {
        lastDelivered = text
        holder.textChanged(text, settings)
    }

    /** SwiftUI's `.onChange` after the holder set its own text. No-op when the text is unchanged. */
    fun echo() {
        if (holder.text == lastDelivered) return
        lastDelivered = holder.text
        holder.textChanged(holder.text, settings)
    }

    /** Advances the clock, firing every debounced search that falls due, in due order. */
    fun advance(ms: Long) {
        val target = now + ms
        while (true) {
            val next = timers
                .filter { !it.cancelled && it.due <= target }
                .minWithOrNull(compareBy<PendingWork>({ it.due }, { it.seq }))
                ?: break
            timers.remove(next)
            now = next.due
            next.work()
        }
        timers.removeAll { it.cancelled || it.due <= target }
        now = target
    }

    /** The user taps row [index] of the list on screen. */
    fun tap(index: Int) {
        if (index !in holder.results.indices) {
            fail("tap($index): the list on screen has ${holder.results.size} row(s)")
        }
        holder.select(index)
    }

    /** The oldest resolve in flight answers with [status] and the route's JSON [body]. */
    fun reply(status: Int, body: Any?) {
        if (pendingResolves.isEmpty()) fail("reply: no resolve call is in flight")
        val text = when (body) {
            is JSONObject -> body.toString()
            is Map<*, *> -> JSONObject(body).toString()
            null -> ""
            else -> body.toString()
        }
        pendingResolves.removeFirst()(LocationResolve.outcome(status, text))
    }

    /** The oldest resolve in flight fails at the network. */
    fun replyNetworkError() {
        if (pendingResolves.isEmpty()) fail("replyNetworkError: no resolve call is in flight")
        pendingResolves.removeFirst()(LocationResolveOutcome.Failed("network"))
    }

    /** The stored answer changes outside the holder (a parent seeding it), as the composable reports it. */
    fun storeFromOutside(value: Any?) {
        stored = value
        holder.storedAnswerChanged(value)
    }
}
