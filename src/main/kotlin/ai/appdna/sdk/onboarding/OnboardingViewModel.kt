package ai.appdna.sdk.onboarding

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.lifecycle.ViewModel
import ai.appdna.sdk.events.EventTracker

/**
 * Survives Activity recreation (config changes / process
 * death-then-restore) without leaking pre-launch state across distinct flow
 * launches.
 *
 * Holds everything that previously lived in `OnboardingActivity.companion
 * object` static fields:
 *   - the [AppDNAOnboardingDelegate] reference,
 *   - the [OnboardingFlowConfig] under presentation,
 *   - the [EventTracker] handle,
 *   - the 5 lambda hooks (`onStepViewed`, `onStepCompleted`, `onStepSkipped`,
 *     `onFlowCompleted`, `onFlowDismissed`),
 *   - the live `currentStepIndex` + `responses` map driving the Compose host,
 *   - the `restoredStepIndex` / `restoredResponses` snapshot read by
 *     [onSaveInstanceState] on rotation / process death.
 *
 * Cross-launch isolation: each [OnboardingActivity] launch acquires its own
 * [androidx.lifecycle.ViewModelStore] (the Activity instance is its own
 * `ViewModelStoreOwner`), so a brand-new launch starts from a freshly
 * constructed [OnboardingViewModel] — there is no carryover from a previous
 * flow. The same-Activity re-launch path (which AppDNA never triggers — every
 * `presentOnboarding(...)` builds a new Intent + new Activity) would call
 * [reset] explicitly.
 *
 * Mirrors iOS `OnboardingFlowHost.swift` `@SceneStorage` round-trip + iOS
 * `OnboardingRenderer` instance-scoped delegate retention.
 */
internal class OnboardingViewModel : ViewModel() {

    /** Active flow under presentation. `null` until [bind] runs. */
    var flow: OnboardingFlowConfig? = null
        private set

    /** Host delegate — receives lifecycle + before-step-advance hooks. */
    var delegate: AppDNAOnboardingDelegate? = null
        private set

    /** Event tracker forwarded into renderer for hook telemetry. */
    var eventTracker: EventTracker? = null
        private set

    /** Step-viewed lambda; emitted by host when a step appears. */
    var onStepViewed: ((String, Int) -> Unit)? = null
        private set

    /** Step-completed lambda; emitted with response payload. */
    var onStepCompleted: ((String, Int, Map<String, Any>?) -> Unit)? = null
        private set

    /** Step-skipped lambda; emitted when host taps "Skip". */
    var onStepSkipped: ((String, Int) -> Unit)? = null
        private set

    /** Flow-completed lambda; emitted on natural end / rule-driven end. */
    var onFlowCompleted: ((Map<String, Any>) -> Unit)? = null
        private set

    /** Flow-dismissed lambda; emitted on system-back / dismiss tap. */
    var onFlowDismissed: ((String, Int) -> Unit)? = null
        private set

    /**
     * Live current step index. Drives the Compose host directly via
     * Compose's snapshot system (assignment from inside a `LaunchedEffect`
     * triggers recomposition). Survives Activity recreation.
     */
    val currentIndex = mutableIntStateOf(0)

    /**
     * Live response map keyed by step id. Drives the Compose host directly
     * (Compose observes additions/replacements via [SnapshotStateMap]).
     * Survives Activity recreation.
     */
    val responses: SnapshotStateMap<String, Any> = mutableStateMapOf()

    /**
     * 8 D2 — navigation history stack
     * for `previous_step_equals` / `previous_step_in` rule operators
     * (iOS OnboardingRenderer.swift:982-988, 1065-1088). Hoisted into
     * the ViewModel alongside [currentIndex] / [responses] so it
     * survives Activity recreation. Each entry is the step ID the user
     * navigated AWAY from. Rule evaluator reads `lastOrNull()` as the
     * user's previous step.
     */
    val navigationHistory: androidx.compose.runtime.snapshots.SnapshotStateList<String> =
        androidx.compose.runtime.mutableStateListOf()

    /**
     * `onBeforeStepRender` overrides survive
     * Activity recreation (rotation, locale change, dark/light toggle,
     * process-death restore). Was a composable-scoped `remember`; resets
     * on every recreation while sister state ([currentIndex] / [responses]
     * / [navigationHistory]) survives. Mirrors iOS
     * `OnboardingRenderer.swift:27` `@State private var configOverrides`
     * (preserved by SwiftUI view-identity diffing on the modal hosting
     * controller). Without VM hoisting, any flow whose host returns a
     * non-nil [StepConfigOverride] from `onBeforeStepRender` flashed
     * the ORIGINAL config for the gap between recomposition and the
     * `LaunchedEffect(currentIndex)`-driven re-await of
     * `onBeforeStepRender` resolving.
     */
    val configOverrides: SnapshotStateMap<String, StepConfigOverride> = mutableStateMapOf()

    /**
     * Gate `LaunchedEffect(currentIndex)` so
     * onBeforeStepRender + onStepViewed only fire ONCE per currentIndex
     * change, not on every Activity recreation (rotation, locale change,
     * dark/light toggle, process-death restore). iOS
     * `OnboardingRenderer.swift:421-438` binds handleStepAppear to
     * `.onAppear` on a view with `.id(currentIndex)`; SwiftUI fires
     * onAppear once per currentIndex change even across back-nav
     * re-visits, but NOT on rotation. Without this guard, hosts whose
     * `onBeforeStepRender` does network work pay duplicate billing per
     * rotation and ETL sees inflated `onboarding_step_viewed` counts.
     * `-1` sentinel means "no step has been emitted yet".
     */
    var lastViewedStepIndex: Int = -1

    /**
     * The host-data "pending" state of the step on screen, per presentation. Held
     * here (not in composition) so an Activity recreation mid-call keeps the presentation, sees the
     * call was cancelled before replying, and re-fires it once.
     */
    val hostDataPresentations = HostDataPresentations()

    /**
     * The per-step interaction data layer (`ElementInteractionResult.dataContext`),
     * the `onBeforeStepRender` base stamps and the flow-level `callSeq`. Beside [configOverrides], with
     * exactly its lifetime: survives Activity recreation, cleared by [reset] and nothing else.
     */
    val hostDataInteractionStore = HostDataInteractionStore()

    /**
     * Snapshot index restored by [OnboardingActivity.onCreate] from
     * `savedInstanceState`. Read once by the Compose host's initial-state
     * seed, then cleared.
     */
    var restoredStepIndex: Int? = null

    /**
     * Snapshot responses restored by [OnboardingActivity.onCreate] from
     * `savedInstanceState`. Read once by the Compose host's initial-state
     * seed, then cleared.
     */
    var restoredResponses: Map<String, Any>? = null

    /**
     * True once [bind] has populated this VM with a launch payload. Lets
     * [OnboardingActivity.onCreate] decide whether the VM was just
     * reconstructed (config-change rehydrate, no rebind) or freshly
     * created (consume the next-launch payload).
     */
    var isBound: Boolean = false
        private set

    /**
     * Wire all flow + callback state. Called once per Activity launch from
     * [OnboardingActivity.onCreate] after consuming the next-launch payload.
     */
    fun bind(payload: OnboardingActivity.LaunchPayload) {
        flow = payload.flow
        delegate = payload.delegate
        eventTracker = payload.eventTracker
        onStepViewed = payload.onStepViewed
        onStepCompleted = payload.onStepCompleted
        onStepSkipped = payload.onStepSkipped
        onFlowCompleted = payload.onFlowCompleted
        onFlowDismissed = payload.onFlowDismissed
        isBound = true
    }

    /**
     * Drop every reference held by this VM. Called from
     * [OnboardingActivity.cleanup] before `finish()` so that the VM does
     * not leak the host's delegate / lambdas across SDK calls in the same
     * process. (The VM is also auto-cleared by Android when the Activity
     * is finished — `onCleared()` runs then — but we proactively null
     * out lambda captures the moment the flow ends so any stray reference
     * to the VM doesn't keep the host's strategy / view-model graph alive.)
     */
    fun reset() {
        flow = null
        delegate = null
        eventTracker = null
        onStepViewed = null
        onStepCompleted = null
        onStepSkipped = null
        onFlowCompleted = null
        onFlowDismissed = null
        restoredStepIndex = null
        restoredResponses = null
        responses.clear()
        currentIndex.intValue = 0
        // 9 — navigationHistory is
        // flow-scoped state alongside responses + currentIndex. Reset
        // must clear it so a re-presented onboarding doesn't see stale
        // history from a previous flow. Currently masked by Activity
        // finish always destroying the VM (next launch gets a fresh
        // instance), but the contract of reset() is "drop every
        // reference held by this VM" — keeping these aligned.
        navigationHistory.clear()
        // Clear override map alongside other
        // flow-scoped state so a re-presented onboarding doesn't see stale
        // overrides from a previous flow.
        configOverrides.clear()
        // Clear viewed-index sentinel so the
        // re-presented onboarding fires onBeforeStepRender + onStepViewed
        // for its own first step.
        lastViewedStepIndex = -1
        hostDataPresentations.reset()
        hostDataInteractionStore.reset()
        isBound = false
    }

    override fun onCleared() {
        super.onCleared()
        // Defensive: Android runtime calls this when the ViewModelStore is
        // cleared (Activity finished, not a config change). Same null-out
        // as [reset] in case `cleanup()` was bypassed (system-killed task).
        reset()
    }
}
