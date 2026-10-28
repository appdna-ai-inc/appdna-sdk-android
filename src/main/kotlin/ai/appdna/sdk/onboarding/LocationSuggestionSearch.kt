package ai.appdna.sdk.onboarding

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The autocomplete request that produced a suggestion list, AS SENT. It is kept WITH the list
 * ([LocationListedSuggestion]), and a tap reads it from there, never from the field's current text: the
 * text can have changed while the list was on screen (the 300 ms debounce shows a stale list after an
 * edit), and the resolve must repeat the request that listed what the user tapped.
 */
internal data class LocationListingRequest(
    val query: String,
    /** Each null when the author chose none — see [LocationSearchSettings.type]. */
    val type: String?,
    val biasCountry: String?,
    val language: String?,
) {
    /**
     * The autocomplete body. Each setting only when the author chose one: the proxy narrows results
     * only when the key is PRESENT, so sending a substituted default would filter a search the author
     * asked to leave open.
     */
    fun autocompleteBody(): org.json.JSONObject = org.json.JSONObject().apply {
        put("query", query)
        put("limit", 5)
        if (!type.isNullOrBlank()) put("type", type)
        if (!biasCountry.isNullOrBlank()) put("bias_country", biasCountry)
        if (!language.isNullOrBlank()) put("language", language)
    }

    companion object {
        fun of(query: String, settings: LocationSearchSettings): LocationListingRequest {
            return LocationListingRequest(
                query = query,
                type = settings.type,
                biasCountry = settings.biasCountry,
                language = settings.language,
            )
        }
    }
}

/**
 * One suggestion of the list on screen, paired with the request that listed it — so a tap pairs a
 * suggestion with ITS listing request even when the list was replaced between render and tap.
 */
internal data class LocationListedSuggestion(
    val suggestion: LocationSuggestion,
    val listing: LocationListingRequest,
)

/** What a resolve came back with. */
internal sealed class LocationResolveOutcome {
    /** `{resolved: true, suggestion}` — the answer that may be stored, carrying its provenance id. */
    data class Resolved(val location: LocationSuggestion) : LocationResolveOutcome()

    /** `{resolved: false, reason}` — e.g. `permanent_not_enabled`: only the typed text is stored. */
    data class Refused(val reason: String) : LocationResolveOutcome()

    /**
     * Anything else: an HTTP error (400 / 401 / 429 / 5xx, a key-required refusal), a network failure,
     * or a reply that does not parse. Also only the typed text.
     */
    data class Failed(val reason: String) : LocationResolveOutcome()
}

/**
 * THE ONE state holder both Android location surfaces bind to — the form-step
 * [LocationFieldComposable] and the `input_location` content block — so the shared fixtures and the
 * unit tests drive the code the composables run. A plain class: the composables `remember` it and
 * recompose through [onChange]. It deliberately holds no Compose state: snapshot writes made outside a
 * composition by a JVM test left later Compose tests in the same test JVM unable to reach idle.
 * `LocationSuggestionSearch` in `LocationAutocomplete.swift` is the Swift twin.
 *
 * It owns:
 * - the suggestion list, each suggestion with the listing request it came from;
 * - the field text: (a) after a resolve, (b) restored from a stored answer, (c) every user edit through
 *   [textChanged], the one entry point;
 * - the tap: cancel the pending search, close the dropdown, then resolve (d);
 * - whether the dropdown is open (e).
 *
 * The composables own focus, layout and styling, and persist what the holder writes through [attach].
 *
 * Its fetch, its resolve call and its debounce clock are injectable, so a test can hold a debounced
 * search PENDING while it taps — CI timing cannot fire it early — and stub a resolve reply while reading
 * the body that was sent. Every method is called on the main thread; the live dependencies
 * ([live]) deliver their completions there.
 */
internal class LocationSuggestionSearch(
    storedAnswer: Any?,
    private val fetch: (listing: LocationListingRequest, done: (List<LocationSuggestion>) -> Unit) -> Unit,
    private val resolve: (body: Map<String, String>, done: (LocationResolveOutcome) -> Unit) -> Unit,
    /** Runs `work` after `delayMs`; returns a cancel. */
    private val schedule: (delayMs: Long, work: () -> Unit) -> (() -> Unit),
) {

    companion object {
        /** The debounce both platforms use, so a keystroke pause costs one request. */
        const val DEBOUNCE_MS = 300L

        /**
         * Production: the live autocomplete and resolve calls, and a coroutine debounce, all on
         * [scope] (a composable's `rememberCoroutineScope`, so completions land on the main thread and
         * everything stops when the field leaves the composition). The API key and base URL are read
         * when each request is made, not when the field is built.
         */
        fun live(storedAnswer: Any?, scope: CoroutineScope): LocationSuggestionSearch = LocationSuggestionSearch(
            storedAnswer = storedAnswer,
            fetch = { listing, done -> scope.launch { done(fetchLocationSuggestionItems(listing)) } },
            resolve = { body, done -> scope.launch { done(LocationResolve.send(body)) } },
            schedule = { delayMs, work ->
                val job = scope.launch {
                    delay(delayMs)
                    work()
                }
                ({ job.cancel() })
            },
        )
    }

    /**
     * Called after any of the observable properties below changes. The composables set it to bump a
     * Compose state counter, so they recompose; the holder itself holds no Compose state, so a test
     * (or a fixture runner) can drive it without touching the global snapshot.
     */
    var onChange: (() -> Unit)? = null

    var results: List<LocationListedSuggestion> = emptyList()
        private set(value) {
            field = value
            onChange?.invoke()
        }

    /**
     * Whether the list on screen holds Google suggestions: the dropdown then draws Google's logo under
     * the rows. Read from each suggestion's own `provider`, so a list replaced by a newer search decides
     * for itself.
     */
    val showsGoogleLogo: Boolean
        get() = results.any { it.suggestion.provider == "google" }

    /** The field text. The composables show it and forward every user edit to [textChanged]. */
    var text: String = ""
        private set(value) {
            field = value
            onChange?.invoke()
        }

    /** An autocomplete request is running: the composables show progress. */
    var isSearching: Boolean = false
        private set(value) {
            field = value
            onChange?.invoke()
        }

    /** A tap's resolve is in flight: the composables show progress, and nothing is stored for the field. */
    var isResolving: Boolean = false
        private set(value) {
            field = value
            onChange?.invoke()
        }

    var isDropdownOpen: Boolean = false
        private set(value) {
            field = value
            onChange?.invoke()
        }

    /**
     * The answer as the holder last wrote it or was told it is ([storedAnswerChanged]). Its presence
     * drives the reactive restore; a typed String also lets [textChanged] skip an unchanged write.
     */
    var storedAnswer: Any? = storedAnswer
        private set

    private var store: ((Any?) -> Unit)? = null

    /**
     * The ONE-SHOT expectation armed by a programmatic text set (a resolve, a restore, a clear). The next
     * [textChanged] consumes it whatever its value: a match is the echo of our own set (SwiftUI sends one;
     * Compose never does), and is skipped; a mismatch is the user, and is processed. A value check, not a
     * flag reset on a later frame — that would be timing-dependent.
     */
    private var pendingEcho: String? = null
    private var cancelPendingSearch: (() -> Unit)? = null

    /** Bumped by every new search and every cancel, so a fetch for a superseded search is dropped. */
    private var searchGeneration = 0

    /**
     * Bumped by every tap, every user edit and [detach], so a resolve reply for a superseded tap is
     * dropped: the field's current text decides.
     */
    private var resolveGeneration = 0

    /**
     * The text the user had typed when the resolve in flight was tapped — that resolve's `raw_query`,
     * and the answer [settleResolveInFlight] stores if the step advances before the reply. Null when
     * no resolve is in flight.
     */
    private var resolvingTyped: String? = null

    /**
     * While the field shows a Google answer's line: that line and the answer's `raw_query`. Google's
     * line must never be stored or sent, so the first edit restarts from the raw query
     * ([LocationAnswer.editRestart]) and clears this; null once the field holds typed text.
     */
    private var shownGoogle: Pair<String, String>? = null

    /**
     * The first edit of a Google answer's line arrived while the input method was composing: it is
     * applied when the composition commits, not before.
     */
    private var awaitingCommit = false

    /**
     * Whether the text field's input method has a composition in progress (its composing span), as the
     * field reported it with its latest value ([compositionChanged]). The Swift twin is
     * `LocationSuggestionSearch.composing`.
     */
    var composing = false
        private set

    /**
     * The text field reports whether the input method is composing, with each value it delivers and
     * before that value reaches [textChanged]. A commit arrives as a value with no composing span, even
     * when it leaves the text as it was.
     */
    fun compositionChanged(composing: Boolean) {
        this.composing = composing
    }

    init {
        LocationAnswer.fieldText(storedAnswer)?.let { showAnswer(it, storedAnswer) }
    }

    // ---- Binding to a composable ---------------------------------------------------------------

    /**
     * Where the holder persists the field's answer (the form step's `values[field.id]`, the block's
     * `inputValues[fieldId]`). `null` = no answer.
     */
    fun attach(store: (Any?) -> Unit) {
        this.store = store
    }

    /**
     * The field left the composition (the step advanced, or the user navigated away): a resolve in
     * flight is settled first ([settleResolveInFlight] — the typed text is stored), so a reply that
     * arrives now is dropped, and a pending search never fires.
     *
     * The composables call this from `onDispose`, which runs AFTER the step has already submitted its
     * answers. So the step settles its fields itself, before it builds the payload
     * ([LocationAdvanceSettlement]); this call covers every other way the field leaves.
     */
    fun detach() {
        settleResolveInFlight()
        store = null
        cancelSearch()
    }

    /**
     * The step is advancing while a tap's resolve is in flight (an optional field's Continue): the field
     * stores the text the user had typed when they tapped, as for any failed resolve, and the late reply
     * is dropped — so nothing the user typed is lost. The write goes through the store BEFORE the reply
     * is dropped.
     *
     * Returns the answer written, or null when no resolve was in flight (nothing is written then).
     */
    fun settleResolveInFlight(): Any? {
        if (!isResolving) return null
        val answer: Any? = resolvingTyped?.ifEmpty { null }?.let { LocationAnswer.typed(it) }
        if (answer != null) write(answer)
        dropResolveInFlight()
        return answer
    }

    /**
     * The composable saw its stored answer. The restore is REACTIVE: on an absent → present transition
     * while the text is empty, the answer is restored into the text. That covers a parent that seeds the
     * answers after the field's first composition (`FormStep` fills `values` in a `LaunchedEffect`).
     */
    fun storedAnswerChanged(value: Any?) {
        val wasPresent = LocationAnswer.fieldText(storedAnswer) != null
        storedAnswer = value
        if (wasPresent || text.isNotEmpty()) return
        LocationAnswer.fieldText(value)?.let { showAnswer(it, value) }
    }

    // ---- Text ----------------------------------------------------------------------------------

    /**
     * EVERY change to the field text the user makes comes through here. The one echo of the holder's
     * own set is skipped (a Compose text field sends none, so on Android the expectation is consumed by
     * the user's next edit, which never equals the text it replaces); anything else is the user typing:
     * it drops a resolve in flight, stores the typed text, and searches.
     *
     * The first edit of a field showing a Google answer's line restarts from the user's own text
     * ([LocationAnswer.editRestart]), so the line itself is never stored as typed text or searched.
     */
    fun textChanged(newText: String, settings: LocationSearchSettings) {
        pendingEcho?.let { echo ->
            pendingEcho = null
            if (newText == echo) return
        }
        if (text != newText) text = newText
        // [composing]: the input method has a composition in progress (the field's composing span). That
        // is not the first edit yet: Google's line with composing text after it is neither stored nor
        // searched. The edit applies when the composition commits — the text field delivers the commit
        // even when it leaves the text as it was.
        if (composing && shownGoogle != null) {
            awaitingCommit = true
            return
        }
        awaitingCommit = false
        dropResolveInFlight()
        var typed = newText
        shownGoogle?.let { (line, rawQuery) ->
            shownGoogle = null
            typed = LocationAnswer.editRestart(rawQuery = rawQuery, shown = line, edited = newText)
            setTextProgrammatically(typed)
        }
        storeTyped(typed)
        search(typed, settings)
    }

    /** Typing without selecting stores the typed text (an empty field stores nothing). */
    private fun storeTyped(newText: String) {
        if (newText.isEmpty()) {
            if (storedAnswer != null) write(null)
            return
        }
        if ((storedAnswer as? String) == newText) return
        write(LocationAnswer.typed(newText))
    }

    /**
     * A programmatic set: arms the one-shot echo expectation, only when the text actually changes (no
     * text field reports an unchanged value, so an armed expectation would swallow the user's next real
     * edit that happened to match).
     */
    private fun setTextProgrammatically(newText: String) {
        if (newText == text) return
        pendingEcho = newText
        text = newText
    }

    /**
     * Shows a stored answer's line; for a Google answer, remembers the line and the user's own text
     * for the first edit.
     */
    private fun showAnswer(line: String, answer: Any?) {
        setTextProgrammatically(line)
        shownGoogle = LocationAnswer.googleRawQuery(answer)?.let { line to it }
    }

    // ---- Search --------------------------------------------------------------------------------

    /**
     * Debounces, then searches. A query below the authored Min characters (or blank) clears the list and
     * closes the dropdown without a request. The listing request is built HERE, from the settings the
     * surface resolved, and travels with the list it produces.
     */
    private fun search(query: String, settings: LocationSearchSettings) {
        cancelSearch()
        if (query.length < settings.minChars || query.isBlank()) {
            results = emptyList()
            isDropdownOpen = false
            return
        }
        val listing = LocationListingRequest.of(query, settings)
        val generation = searchGeneration
        cancelPendingSearch = schedule(DEBOUNCE_MS) {
            if (generation != searchGeneration) return@schedule
            cancelPendingSearch = null
            isSearching = true
            fetch(listing) { items ->
                if (generation != searchGeneration) return@fetch
                isSearching = false
                results = items.map { LocationListedSuggestion(it, listing) }
                isDropdownOpen = results.isNotEmpty()
            }
        }
    }

    /** Cancels the pending debounced search and drops any fetch in flight. */
    private fun cancelSearch() {
        searchGeneration += 1
        cancelPendingSearch?.invoke()
        cancelPendingSearch = null
        isSearching = false
    }

    /**
     * The user left the field (it lost focus): close the dropdown, and let no pending search reopen it
     * under a field the user is no longer in. The next edit searches again.
     */
    fun editingEnded() {
        cancelSearch()
        isDropdownOpen = false
    }

    /** The clear button: no answer, no text, no list. */
    fun clear() {
        cancelSearch()
        dropResolveInFlight()
        results = emptyList()
        isDropdownOpen = false
        write(null)
        shownGoogle = null
        setTextProgrammatically("")
    }

    // ---- Tap → resolve -------------------------------------------------------------------------

    /**
     * A tap ENDS the search: the pending debounced search is cancelled and the dropdown closes, and
     * nothing reopens it until the user edits again. Then the tapped suggestion is resolved; nothing is
     * stored while that is in flight (a required field keeps Continue gated).
     *
     * - Success → [LocationAnswer.selection] of the resolved answer, carrying `mapbox_id`; the field
     *   shows the resolved `formatted_address`.
     * - Any non-success, or a suggestion with no `id` (a server that predates resolving, or a rollback:
     *   it cannot be resolved, and a full temporary result may not be stored) → the typed text.
     */
    fun select(listed: LocationListedSuggestion) {
        cancelSearch()
        isDropdownOpen = false
        results = emptyList()
        dropResolveInFlight()
        // The text the user typed to find this place, read before anything changes it — never a
        // Google line the field still shows.
        val typed = shownGoogle?.second ?: text
        val typedAnswer: Any? = typed.ifEmpty { null }?.let { LocationAnswer.typed(it) }

        val body = resolveRequest(listed)
        if (body == null) {
            write(typedAnswer)
            ai.appdna.sdk.Log.debug("Location suggestion has no id; stored the typed text")
            return
        }
        write(null)
        isResolving = true
        resolvingTyped = typed
        val generation = resolveGeneration
        resolve(body) { outcome ->
            if (generation != resolveGeneration) return@resolve
            isResolving = false
            resolvingTyped = null
            when (outcome) {
                is LocationResolveOutcome.Resolved -> {
                    val answer = LocationAnswer.selection(outcome.location, typed)
                    write(answer)
                    showAnswer(LocationAnswer.fieldText(answer) ?: listed.suggestion.address, answer)
                    // Never log the place itself.
                    ai.appdna.sdk.Log.debug("Location suggestion resolved")
                }
                is LocationResolveOutcome.Refused -> {
                    write(typedAnswer)
                    ai.appdna.sdk.Log.debug("Location resolve not used (${outcome.reason}); stored the typed text")
                }
                is LocationResolveOutcome.Failed -> {
                    write(typedAnswer)
                    ai.appdna.sdk.Log.debug("Location resolve not used (${outcome.reason}); stored the typed text")
                }
            }
        }
    }

    /** The tap at [index] of the list on screen. */
    fun select(index: Int) {
        results.getOrNull(index)?.let { select(it) }
    }

    /**
     * The resolve body for a tapped suggestion: its `id`, its display line as `text`, and the
     * autocomplete request that LISTED it (`query`, `type`, `bias_country`, `language` as sent) — never
     * the field's current text, which may have changed while the list was on screen. A Google suggestion
     * sends `{id, provider, language}` only, so Places prediction text never returns to the server. Null
     * when the suggestion has no `id`.
     */
    fun resolveRequest(listed: LocationListedSuggestion): Map<String, String>? {
        val id = listed.suggestion.id ?: return null
        val provider = listed.suggestion.provider ?: "mapbox"
        val listing = listed.listing
        return buildMap {
            put("id", id)
            put("provider", provider)
            if (provider == "google") {
                listing.language?.takeIf { it.isNotBlank() }?.let { put("language", it) }
                return@buildMap
            }
            put("text", listed.suggestion.address)
            put("query", listing.query)
            listing.type?.takeIf { it.isNotBlank() }?.let { put("type", it) }
            listing.biasCountry?.takeIf { it.isNotBlank() }?.let { put("bias_country", it) }
            listing.language?.takeIf { it.isNotBlank() }?.let { put("language", it) }
        }
    }

    private fun dropResolveInFlight() {
        resolveGeneration += 1
        resolvingTyped = null
        isResolving = false
    }

    private fun write(value: Any?) {
        storedAnswer = value
        store?.invoke(value)
    }
}

/**
 * The step's half of the advance-while-resolving rule. Every location field on a step registers its
 * holder here; the step settles them when it ADVANCES — after its own required gate has passed, before
 * it hands its answers on — so a field whose resolve is still in flight stores the text the user typed
 * ([LocationSuggestionSearch.settleResolveInFlight]).
 *
 * The holder's own [LocationSuggestionSearch.detach] cannot do this alone: it runs from `onDispose`, a
 * frame after the step's Continue has already built and submitted its payload.
 *
 * Provided through [LocalLocationAdvanceSettlement] by the form step ([FormStep]) and by the
 * block-based step (`OnboardingStepView`). A field rendered with no step around it registers nowhere and
 * relies on [LocationSuggestionSearch.detach].
 */
internal class LocationAdvanceSettlement {
    /** Holder → its field id. A holder is a plain class, so the key is its identity. */
    private val fields = LinkedHashMap<LocationSuggestionSearch, String>()

    fun register(fieldId: String, holder: LocationSuggestionSearch) {
        fields[holder] = fieldId
    }

    fun unregister(holder: LocationSuggestionSearch) {
        fields.remove(holder)
    }

    /**
     * Settles every registered field. Each one with a resolve in flight writes its typed text through
     * its own store (the step's answer map) and is returned here as `field id → answer`.
     */
    fun settle(): Map<String, Any> {
        val settled = LinkedHashMap<String, Any>()
        for ((holder, fieldId) in fields.entries.toList()) {
            holder.settleResolveInFlight()?.let { settled[fieldId] = it }
        }
        return settled
    }

    /**
     * For an advance whose payload was built BEFORE the fields were settled (every block-step advance
     * path builds its own map and then calls `onNext`): settles, and adds each settled answer to the
     * payload where the payload holds none for that field. A key the payload already carries is kept —
     * the SDK's own keys (`action`, …) win over a field id, as they do when the payload is built.
     */
    fun settleInto(data: Map<String, Any>?): Map<String, Any>? {
        val settled = settle()
        if (settled.isEmpty()) return data
        val merged = LinkedHashMap<String, Any>()
        data?.let { merged.putAll(it) }
        for ((fieldId, answer) in settled) {
            val current = merged[fieldId]
            if (current == null || current == "") merged[fieldId] = answer
        }
        return merged
    }
}

/** The step's [LocationAdvanceSettlement]; null where no step provides one. */
internal val LocalLocationAdvanceSettlement =
    androidx.compose.runtime.staticCompositionLocalOf<LocationAdvanceSettlement?> { null }
