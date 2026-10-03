package ai.appdna.sdk.onboarding

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select

/*
 * "Show more": `ElementInteractionResult.dataContext` and `refresh_step`.
 *
 *   HostDataLayering        the pure `effective` / `apply` functions of the per-step interaction layer (C3)
 *   HostDataInteractionStore the flow-level owner: the layer, the base stamps and `callSeq` (C3)
 *   HostDataInteractionAccess the holder the flow host builds over the ViewModel (or its `remember`
 *                             fallback) and threads to the step (C3 "What is threaded down")
 *   InteractionCoordinator   the ONE seam for an element interaction (C5.5): in-flight lock, generation
 *                             token, `callSeq` draw, injectable-clock 8 s refresh race, throw → nil,
 *                             `loadingBlockId`, `isTappable` and the live `isCurrentPresentation` check.
 *                             Production and the fixture driver use the same class.
 *
 * Kept OUT of OnboardingActivity.kt / ContentBlockRenderer.kt on purpose: large Compose methods are
 * what D8 mis-dexes (ComposeMethodSizeGuardTest), so `BlockBasedStepView` and `ButtonBlock` only call
 * into these.
 */

// ─────────────────────────────────────────────────────────────────────────────────────────────────
// The interaction data layer
// ─────────────────────────────────────────────────────────────────────────────────────────────────

/** One layer entry: a value, or a removal marker, stamped with the `callSeq` of the call that wrote it. */
internal class HostDataLayerEntry(val value: Any?, val removed: Boolean, val stamp: Long) {
    override fun equals(other: Any?): Boolean =
        other is HostDataLayerEntry && other.removed == removed && other.stamp == stamp && other.value == value
    override fun hashCode(): Int = (value?.hashCode() ?: 0) * 31 + stamp.hashCode() + if (removed) 1 else 0
    override fun toString(): String = if (removed) "removed@$stamp" else "$value@$stamp"
}

/** The pure merge rules. Ordering is by call START (`callSeq`), never by arrival. */
internal object HostDataLayering {

    /**
     * The effective `hook_data` of a step: the base (`configOverrides[S].dataContext`), then every layer
     * entry that applies — its key is absent from the base, or its stamp is newer than [baseStamp]. A
     * removal deletes the key; a value sets it. A nil base with no applying VALUE is nil ("no host data").
     */
    fun effective(
        base: Map<String, Any?>?,
        baseStamp: Long,
        layer: Map<String, HostDataLayerEntry>?,
    ): Map<String, Any?>? {
        if (layer.isNullOrEmpty()) return base
        val out = LinkedHashMap<String, Any?>()
        base?.let { out.putAll(it) }
        var hasValue = false
        for ((k, e) in layer) {
            val applies = base == null || !base.containsKey(k) || e.stamp > baseStamp
            if (!applies) continue
            if (e.removed) out.remove(k) else { out[k] = e.value; hasValue = true }
        }
        return if (base == null && !hasValue) null else out
    }

    /**
     * Applying an interaction's `dataContext` [data] (its call drew [stamp]): a key the base sets whose
     * base call started later is SKIPPED (a newer `onBeforeStepRender` already answered it); a null
     * member stores a removal; any other member replaces that key whole. Members not in [data] are
     * untouched — the merge is shallow per top-level key.
     */
    fun applyInteraction(
        layer: Map<String, HostDataLayerEntry>?,
        base: Map<String, Any?>?,
        baseStamp: Long,
        data: Map<String, Any?>,
        stamp: Long,
    ): Map<String, HostDataLayerEntry> {
        val out = LinkedHashMap<String, HostDataLayerEntry>()
        layer?.let { out.putAll(it) }
        for ((k, v) in data) {
            if (base?.containsKey(k) == true && stamp < baseStamp) continue
            out[k] = HostDataLayerEntry(v, removed = v == null, stamp = stamp)
        }
        return out
    }

    /**
     * Applying an `onBeforeStepRender` reply whose call drew [baseStamp]: for every key the new [base]
     * sets, layer entries OLDER than it are deleted — so a superseded value cannot revive when a later
     * base omits the key. Keys the base does not set keep their entries.
     */
    fun applyBase(
        layer: Map<String, HostDataLayerEntry>?,
        base: Map<String, Any?>?,
        baseStamp: Long,
    ): Map<String, HostDataLayerEntry>? {
        if (layer == null) return null
        if (base.isNullOrEmpty()) return layer
        return layer.filter { (k, e) -> !(base.containsKey(k) && e.stamp < baseStamp) }
    }
}

/**
 * The flow-level owner of the interaction layer, the base stamps and `callSeq`.
 * Lives in [OnboardingViewModel] beside `configOverrides` (survives Activity recreation; `reset()`
 * clears it), or in a `remember` when the flow host has no ViewModel. The layer is SNAPSHOT state, so a
 * reply that carries only `dataContext` recomposes the step. `callSeq` and the stamps are plain fields.
 */
internal class HostDataInteractionStore {
    val layers: SnapshotStateMap<String, Map<String, HostDataLayerEntry>> = mutableStateMapOf()
    private val baseStamps = HashMap<String, Long>()

    /**
     * One flow-level monotonic counter, drawn when an `onBeforeStepRender` or an interaction call STARTS.
     * MAIN-THREAD CONFINED, like the stamps: every draw happens on the main thread (composition-driven
     * `LaunchedEffect`s and tap handlers), so a plain field is enough. Do not draw from another thread.
     */
    var callSeq: Long = 0L
        private set

    fun nextCallSeq(): Long = ++callSeq

    fun baseStamp(stepId: String): Long = baseStamps[stepId] ?: 0L

    internal fun setBaseStamp(stepId: String, stamp: Long) { baseStamps[stepId] = stamp }

    fun reset() {
        layers.clear()
        baseStamps.clear()
        callSeq = 0L
    }
}

/**
 * "What is threaded down" — built by the flow host over the ViewModel's (or the
 * `remember` fallback's) `configOverrides`, [HostDataInteractionStore] and [HostDataPresentations].
 * Every reader of `hook_data` computes the effective map from HERE, at call time — never from a
 * composition snapshot.
 */
internal class HostDataInteractionAccess(
    private val configOverrides: MutableMap<String, StepConfigOverride>,
    val store: HostDataInteractionStore,
    val hostDataPresentations: HostDataPresentations,
) {
    /** `stepHookDataKeys`, computed once per step content (keyed by the raw block list's identity). */
    private val referencedKeys = HashMap<String, Pair<Any?, Set<String>>>()

    /** The effective `hook_data` of [stepId]: the live base plus the live layer (C3). */
    fun effectiveHookData(stepId: String): Map<String, Any?>? =
        HostDataLayering.effective(configOverrides[stepId]?.dataContext, store.baseStamp(stepId), store.layers[stepId])

    /** The layer writer (C4 step 2). Every `dataContext` goes through the core decoder first (C2). */
    fun applyInteractionData(stepId: String, dataContext: Map<String, Any?>?, stamp: Long) {
        val data = ElementInteractionResult.decodeDataContext(dataContext)
        if (data.isNullOrEmpty()) return
        store.layers[stepId] = HostDataLayering.applyInteraction(
            store.layers[stepId], configOverrides[stepId]?.dataContext, store.baseStamp(stepId), data, stamp,
        )
    }

    /** An `onBeforeStepRender` reply [override] whose call drew [seq] (the write at the flow host). */
    fun applyBeforeRenderReply(stepId: String, override: StepConfigOverride, seq: Long) {
        configOverrides[stepId] = override
        store.setBaseStamp(stepId, seq)
        val layer = store.layers[stepId] ?: return
        val pruned = HostDataLayering.applyBase(layer, override.dataContext, seq)
        if (pruned.isNullOrEmpty()) store.layers.remove(stepId) else if (pruned.size != layer.size) store.layers[stepId] = pruned
    }

    fun nextCallSeq(): Long = store.nextCallSeq()

    /**
     * "cached" on arrival: a non-nil base, or a layer VALUE under a key the step's raw blocks
     * reference. Removal markers and unreferenced keys do not count. [HostDataPresentations] samples
     * it once, when it creates the presentation (the latch).
     */
    fun cachedAtArrival(step: OnboardingStep): Boolean {
        if (configOverrides.containsKey(step.id)) return true
        val layer = store.layers[step.id] ?: return false
        val valueKeys = layer.filterValues { !it.removed }.keys
        if (valueKeys.isEmpty()) return false
        val refs = referencedKeysOf(step)
        return HostDataResolver.ANY_HOOK_DATA_KEY in refs || valueKeys.any { it in refs }
    }

    private fun referencedKeysOf(step: OnboardingStep): Set<String> {
        val raw = step.rawContentBlocks?.blocks ?: return emptySet()
        referencedKeys[step.id]?.let { (src, keys) -> if (src === raw) return keys }
        val keys = HostDataResolver.stepHookDataKeys(raw, step.config.localizations)
        referencedKeys[step.id] = raw to keys
        return keys
    }
}

/** The effective map in the view-level `hookData` shape (top-level values are never null). */
@Suppress("UNCHECKED_CAST")
internal fun effectiveHookDataForView(access: HostDataInteractionAccess, stepId: String): Map<String, Any>? =
    access.effectiveHookData(stepId) as Map<String, Any>?

// ─────────────────────────────────────────────────────────────────────────────────────────────────
// The interaction coordinator
// ─────────────────────────────────────────────────────────────────────────────────────────────────

/** The host call an [InteractionCoordinator] makes: `(stepId, blockId, action, value, inputValues)`. */
internal typealias ElementInteractionCall =
    suspend (String, String, String, String?, Map<String, Any>) -> ElementInteractionResult?

/**
 * One per PRESENTATION (`remember(myPresentation)`), bound for life to its own
 * step id and its own "is this presentation still current?" check.
 *
 * - [start] refuses — no host call, no `callSeq` draw, no loading state, a debug log — when the
 *   presentation is no longer current, when a call is already in flight (the lock is per
 *   presentation), when a refresh is tapped while the presentation is pending, or when there is no
 *   `onElementInteraction` handler.
 * - A `refresh` call ends at min(reply, 8 s) on an INDEPENDENT timer ([delayMs] is the injectable
 *   clock). Other actions have no SDK timeout, as before.
 * - A throwing host is treated as nil; `CancellationException` is rethrown.
 * - A reply is applied only if, at arrival, the presentation is still current AND it is the latest
 *   generation. Otherwise it is dropped whole (`dataContext` and `advance` included).
 * - [inFlight] / [loadingBlockId] are SNAPSHOT state, so the spinner shows
 *   on tap and goes on reply without any other state change.
 */
internal class InteractionCoordinator(
    val stepId: String,
    private val scope: CoroutineScope,
    private val isCurrentPresentation: () -> Boolean,
    private val drawSeq: () -> Long,
    private val hasHandler: () -> Boolean,
    private val call: ElementInteractionCall,
    private val delayMs: suspend (Long) -> Unit = { delay(it) },
    private val refreshTimeoutMs: Long = ElementInteractionResult.REFRESH_TIMEOUT_MS,
    private val log: (String) -> Unit = { msg -> ai.appdna.sdk.Log.debug { msg } },
) {
    var inFlight: Boolean by mutableStateOf(false)
        private set
    var loadingBlockId: String? by mutableStateOf(null)
        private set

    /**
     * The latest call's generation. Only it may apply or release. MAIN-THREAD CONFINED: [start] is called
     * from tap handlers and [runCall] resumes on the composition's scope (the main dispatcher), so this
     * plain field — like the flow-level `callSeq` — needs no synchronisation.
     */
    var generation: Int = 0
        private set

    /** A `refresh_step` button is tappable when nothing is in flight and not pending. */
    @Suppress("UNUSED_PARAMETER")
    fun isTappable(blockId: String, pending: Boolean): Boolean = !pending && !inFlight

    /**
     * Starts one interaction. Returns the call's job, or null when refused. [onReply] runs once, on the
     * scope's thread, with the reply and the `callSeq` its call drew — only for a non-nil reply that
     * passed the arrival check.
     */
    fun start(
        blockId: String,
        action: String,
        value: String?,
        inputValues: Map<String, Any>,
        pending: Boolean,
        onReply: (ElementInteractionResult, Long) -> Unit,
    ): Job? {
        val refresh = action == ElementInteractionResult.REFRESH_ACTION
        if (!isCurrentPresentation()) {
            log("Host data: $action on $blockId refused — its presentation is no longer on screen")
            return null
        }
        if (inFlight) {
            log("Host data: $action on $blockId refused — another interaction of this step is in flight")
            return null
        }
        if (refresh && pending) {
            log("Host data: refresh_step on $blockId refused — the step's host data is still pending")
            return null
        }
        if (!hasHandler()) {
            if (refresh) log("refresh_step tapped with no onElementInteraction handler")
            return null
        }
        val seq = drawSeq()
        val gen = ++generation
        inFlight = true
        if (refresh) loadingBlockId = blockId
        return scope.launch { runCall(gen, seq, refresh, blockId, action, value, inputValues, onReply) }
    }

    private suspend fun runCall(
        gen: Int,
        seq: Long,
        refresh: Boolean,
        blockId: String,
        action: String,
        value: String?,
        inputValues: Map<String, Any>,
        onReply: (ElementInteractionResult, Long) -> Unit,
    ) {
        val reply = CompletableDeferred<ElementInteractionResult?>()
        val host = scope.launch {
            val r = try {
                call(stepId, blockId, action, value, inputValues)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                // A throwing host is a nil reply, for every action.
                log("Host data: onElementInteraction threw for $action on $blockId — treated as null: $t")
                null
            }
            reply.complete(r)
        }
        // However the host coroutine ends, the call ends. A host that throws `CancellationException`
        // itself (e.g. its own `withTimeout` expiring) ends its job as CANCELLED, not failed — without
        // this the reply would never complete, `finally` below would never run and the step's lock
        // would stay held for the rest of the presentation (released on a reply, nil or throw).
        // A no-op when the host already replied. Our own scope's cancellation still cancels runCall.
        // Only the HOST's own cancellation is logged as the host's: not our deadline (`timedOut`, set
        // before we cancel it) and not our scope ending (the step composition left — `!scope.isActive`).
        var timedOut = false
        host.invokeOnCompletion { cause ->
            if (reply.complete(null) && cause != null && !timedOut && scope.isActive) {
                log("Host data: onElementInteraction for $action on $blockId ended by cancellation — treated as null")
            }
        }
        var deadline: Job? = null
        try {
            val result: ElementInteractionResult? = if (refresh) {
                val timer = scope.launch { delayMs(refreshTimeoutMs) }
                deadline = timer
                val out: Any? = select<Any?> {
                    reply.onAwait { it ?: NIL }
                    timer.onJoin { TIMEOUT }
                }
                if (out === TIMEOUT) {
                    log("Host data: refresh on $blockId timed out after $refreshTimeoutMs ms — a later reply is dropped")
                    timedOut = true
                    host.cancel()
                    null
                } else out as? ElementInteractionResult
            } else reply.await()
            if (result != null) {
                if (gen != generation || !isCurrentPresentation()) {
                    log("Host data: late reply to $action on $blockId dropped — its presentation ended or a newer call started")
                } else {
                    onReply(result, seq)
                }
            }
        } finally {
            deadline?.cancel()
            if (gen == generation) {
                inFlight = false
                loadingBlockId = null
            }
        }
    }

    private companion object {
        val NIL = Any()
        val TIMEOUT = Any()
    }
}

// ─────────────────────────────────────────────────────────────────────────────────────────────────
// Threading into the step and its nested renderers
// ─────────────────────────────────────────────────────────────────────────────────────────────────

/**
 * What the flow host hands ONE step composition: the flow-level [access], this composition's own
 * presentation (null when the lookup fails — three navigations inside one exit tween), and the host
 * call. A CompositionLocal rather than a parameter: `OnboardingStepView` is public API and these types
 * are internal; the value is the same holder.
 */
internal class StepHostDataWiring(
    val access: HostDataInteractionAccess,
    val presentation: HostDataPresentation?,
    val call: ElementInteractionCall,
    val hasHandler: () -> Boolean,
)

internal val LocalStepHostDataWiring = compositionLocalOf<StepHostDataWiring?> { null }

/**
 * The wiring of the step composition at [stepIndex], remembered per presentation (so an unrelated
 * flow-host recomposition does not hand the step a new holder). [call] and [hasHandler] must be stable
 * instances that read the latest delegate themselves.
 *
 * The presentation is looked up on every recomposition. When the lookup comes back null — the outgoing
 * step of three navigations inside one exit tween matches neither `current` nor `previous` — the
 * composition keeps the LAST presentation it had, so its overlays and coordinator do not reset while it
 * animates out. That presentation is no longer current, so its coordinator still refuses every call,
 * exactly as a null presentation would.
 */
@Composable
internal fun rememberStepHostDataWiring(
    access: HostDataInteractionAccess,
    stepIndex: Int,
    stepId: String,
    call: ElementInteractionCall,
    hasHandler: () -> Boolean,
): StepHostDataWiring {
    val lastKnown = remember(stepIndex, stepId) { arrayOfNulls<HostDataPresentation>(1) }
    val found = access.hostDataPresentations.presentationAt(stepIndex, stepId)
    if (found != null) lastKnown[0] = found
    val mine = found ?: lastKnown[0]
    return remember(access, mine, call, hasHandler) { StepHostDataWiring(access, mine, call, hasHandler) }
}

/**
 * The step's interaction wiring as nested renderers see it (row, stack,
 * section_background, carousel pages, multi_buttons children): the call, the loading block and
 * tappability. Provided by `BlockBasedStepView`; `null` in Screens / SDUI and — explicitly — inside an
 * option sheet. When non-null it is AUTHORITATIVE for `refresh_step` buttons; when null the button's
 * explicit `onInteract` parameter is used.
 */
internal class StepInteraction(
    val onInteract: (String, String, String?) -> Unit,
    private val coordinator: InteractionCoordinator,
    private val pending: () -> Boolean,
) {
    /** Snapshot reads — a button reading these recomposes when the coordinator's state changes. */
    val loadingBlockId: String? get() = coordinator.loadingBlockId
    fun isTappable(blockId: String): Boolean = coordinator.isTappable(blockId, pending())
}

internal val LocalStepInteraction = compositionLocalOf<StepInteraction?> { null }

/**
 * One [StepInteraction] per coordinator, so a step recomposition does not hand every nested refresh
 * button a new local value. The handler and the pending check are read through their latest versions.
 */
@Composable
internal fun rememberStepInteraction(
    coordinator: InteractionCoordinator,
    onInteract: (String, String, String?) -> Unit,
    pending: () -> Boolean,
): StepInteraction {
    val latestOnInteract by rememberUpdatedState(onInteract)
    val latestPending by rememberUpdatedState(pending)
    return remember(coordinator) {
        StepInteraction({ id, action, value -> latestOnInteract(id, action, value) }, coordinator) { latestPending() }
    }
}

/**
 * The step's coordinator — ONE per presentation (`remember(presentation)`): `AnimatedContent` reuses a
 * composition on a quick 0→1→0 while `presentationFor` creates a NEW presentation, and a coordinator
 * kept across that would refuse every interaction until the next navigation.
 *
 * Without the flow host (a step rendered on its own), the legacy [fallback] seam is used and every
 * call is accepted as current.
 */
@Composable
internal fun rememberInteractionCoordinator(
    stepId: String,
    wiring: StepHostDataWiring?,
    scope: CoroutineScope,
    fallback: suspend (String, String, String?, Map<String, Any>) -> AppliedInteraction?,
): InteractionCoordinator {
    val latestFallback by rememberUpdatedState(fallback)
    // The coordinator lives for the whole presentation; the host call and "is there a handler?" are read
    // through the LATEST wiring, so a delegate / flow change within one presentation is seen.
    val latestCall by rememberUpdatedState(wiring?.call)
    val latestHasHandler by rememberUpdatedState(wiring?.hasHandler)
    val presentation = wiring?.presentation
    return remember(stepId, presentation, wiring != null) {
        if (wiring != null) {
            val presentations = wiring.access.hostDataPresentations
            InteractionCoordinator(
                stepId = stepId,
                scope = scope,
                isCurrentPresentation = { presentation != null && presentations.current === presentation },
                drawSeq = wiring.access::nextCallSeq,
                hasHandler = { latestHasHandler?.invoke() == true },
                call = { stepId, blockId, action, value, inputs -> latestCall?.invoke(stepId, blockId, action, value, inputs) },
            )
        } else {
            var seq = 0L
            InteractionCoordinator(
                stepId = stepId,
                scope = scope,
                isCurrentPresentation = { true },
                drawSeq = { ++seq },
                // A step shown on its own has a handler only when the caller passed a legacy
                // interaction seam. The default seam is the [NoElementInteraction] sentinel: then a refresh
                // makes no call, shows no loading state and logs "no onElementInteraction handler".
                hasHandler = { latestFallback !== NoElementInteraction },
                call = { _, blockId, action, value, inputs -> latestFallback(blockId, action, value, inputs)?.asResult() },
            )
        }
    }
}

/**
 * The default legacy interaction seam of a step rendered WITHOUT the flow host (`OnboardingStepView`
 * on its own): "there is no `onElementInteraction` handler". Compared by identity, so it must be THE
 * default value of every `performInteraction` parameter on that path.
 */
internal val NoElementInteraction: suspend (String, String, String?, Map<String, Any>) -> AppliedInteraction? =
    { _, _, _, _ -> null }

/** The legacy seam's [AppliedInteraction] back as the result it was folded from. */
private fun AppliedInteraction.asResult(): ElementInteractionResult = ElementInteractionResult(
    fieldConfigPatches = fieldConfigOverrides,
    inputValuePatches = inputValuePatches ?: inputValues,
    fieldOptions = fieldOptionsOverrides,
    advance = advance,
    dataContext = dataContext,
)

/**
 * Steps 1–4 — one reply's four outputs, written TOGETHER in one snapshot so no frame
 * renders a partial application:
 *  1. `inputValuePatches` key by key onto the CURRENT answers (never the tap-time snapshot);
 *  2. `dataContext` into the step's layer, stamped with the call's `callSeq`;
 *  3. `fieldConfigPatches` key-merged; 4. `fieldOptions` replaced per block.
 * The re-resolve (step 5) is the next resolve, which reads the layer as just written.
 *
 * MAIN THREAD ONLY. The coordinator calls it from the composition's scope (the main dispatcher), the
 * only writer of these snapshot maps; `withMutableSnapshot` would throw on an apply conflict with a
 * concurrent writer on another thread. Asserted in debug builds.
 */
internal fun applyInteractionReply(
    result: ElementInteractionResult,
    stamp: Long,
    stepId: String,
    inputValues: MutableMap<String, Any>,
    access: HostDataInteractionAccess?,
    fieldConfigOverrides: MutableMap<String, Map<String, Any>>,
    fieldOptionsOverrides: MutableMap<String, List<InputOption>>,
) {
    assertMainThreadInDebug("applyInteractionReply")
    Snapshot.withMutableSnapshot {
        result.inputValuePatches?.forEach { (k, v) -> inputValues[k] = v }
        access?.applyInteractionData(stepId, result.dataContext, stamp)
        result.fieldConfigPatches?.forEach { (id, patch) ->
            fieldConfigOverrides[id] = (fieldConfigOverrides[id] ?: emptyMap()) + patch
        }
        // #657 — options are REPLACED wholesale, not merged: "the next four venues" is a new list.
        result.fieldOptions?.forEach { (id, options) -> fieldOptionsOverrides[id] = options }
    }
}

// ─────────────────────────────────────────────────────────────────────────────────────────────────
// The loading state of the tapped button (SDK chrome, not authorable)
// ─────────────────────────────────────────────────────────────────────────────────────────────────

internal const val REFRESH_SPINNER_TAG = "appdna_refresh_spinner"

/**
 * The button's content with the refresh loading state: while [loading] the label and icon draw at
 * opacity 0 (the button keeps its frame, colours and border) under a centred 18 dp / 2 dp
 * `CircularProgressIndicator` tinted with the button's foreground colour. A separate, small composable
 * so `ButtonBlock` does not grow (D8).
 */
@Composable
internal fun RefreshLoadingContent(loading: Boolean, tint: Color, content: @Composable () -> Unit) {
    if (!loading) {
        content()
        return
    }
    Box(contentAlignment = Alignment.Center) {
        Box(Modifier.alpha(0f)) { content() }
        CircularProgressIndicator(
            modifier = Modifier.size(18.dp).testTag(REFRESH_SPINNER_TAG),
            color = tint,
            strokeWidth = 2.dp,
        )
    }
}

/**
 * Accessibility for a `refresh_step` button: "Loading" while its call runs, and disabled while it is
 * not tappable. The button still DRAWS normally — Material's disabled colours are not used.
 */
internal fun refreshStateSemantics(loading: Boolean, tappable: Boolean): Modifier =
    if (!loading && tappable) Modifier else Modifier.semantics {
        if (loading) stateDescription = "Loading"
        if (!tappable) disabled()
    }

/**
 * Debug-only main-thread check. Silent where there is no Android main looper at all (a plain JVM unit
 * test runs against android.jar stubs) — Robolectric and devices have one.
 */
internal fun assertMainThreadInDebug(what: String) {
    if (!ai.appdna.sdk.BuildConfig.DEBUG) return
    val main = try { android.os.Looper.getMainLooper() } catch (_: Throwable) { null } ?: return
    check(android.os.Looper.myLooper() === main) { "Host data: $what must run on the main thread" }
}
