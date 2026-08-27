package ai.appdna.sdk

import android.app.Activity
import android.content.Context
import ai.appdna.sdk.config.ExperimentManager
import ai.appdna.sdk.config.FeatureFlagManager
import ai.appdna.sdk.config.RemoteConfigManager
import ai.appdna.sdk.deeplinks.DeferredDeepLink
import ai.appdna.sdk.deeplinks.DeferredDeepLinkManager
import ai.appdna.sdk.feedback.SurveyConfig
import ai.appdna.sdk.events.EventQueue
import ai.appdna.sdk.events.EventSchema
import ai.appdna.sdk.events.EventTracker
import ai.appdna.sdk.feedback.SurveyManager
import ai.appdna.sdk.integrations.PushTokenManager
import ai.appdna.sdk.integrations.RevenueCatBridge
import ai.appdna.sdk.network.ApiClient
import ai.appdna.sdk.onboarding.AppDNAOnboardingDelegate
import ai.appdna.sdk.onboarding.OnboardingFlowManager
import ai.appdna.sdk.paywalls.AppDNAPaywallDelegate
import ai.appdna.sdk.paywalls.PaywallContext
import ai.appdna.sdk.paywalls.PaywallManager
import ai.appdna.sdk.storage.LocalStorage
import ai.appdna.sdk.webentitlements.WebEntitlement
import ai.appdna.sdk.webentitlements.WebEntitlementManager
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.*
import org.json.JSONObject
import androidx.compose.runtime.Composable

/**
 * Main entry point for the AppDNA Android SDK.
 * All public methods are thread-safe.
 */
object AppDNA {

    /** SDK version string. */
    const val sdkVersion = "1.0.45"

    /**
     * SPEC-419 brand-threading — the app's brand accent hex (from `/settings/brand`,
     * served via Firestore `config/brand`). When set, SDK render defaults use it
     * instead of the hardcoded #6366F1 brand indigo for accent/link/badge/selected
     * colors. Per-element authored colors still take precedence. nil until brand loads.
     */
    @Volatile
    @JvmStatic
    var brandAccentHex: String? = null
        internal set

    /**
     * SPEC-419 brand-threading — resolved Compose Color for the brand accent,
     * falling back to the #6366F1 brand indigo when no brand is configured/loaded.
     * Replaces hardcoded `Color(0xFF6366F1)` at SDK render-default sites.
     */
    @JvmStatic
    fun brandAccentColor(): androidx.compose.ui.graphics.Color {
        brandAccentHex?.let {
            try {
                return ai.appdna.sdk.core.StyleEngine.parseColor(it)
            } catch (_: Throwable) {}
        }
        return androidx.compose.ui.graphics.Color(0xFF6366F1)
    }

    /**
     * SPEC-070-A H.20: most recent throwable raised during configure/bootstrap
     * (e.g. missing `google-services-appdna.json`, malformed bundle config).
     *
     * Hosts can read this to detect a degraded init state — for example, to
     * decide whether remote config is reliable in a feature-flag check.
     * Pairs with `delegate?.onInitDegraded(reason)` which fires once whenever
     * the value transitions from null → non-null.
     */
    @Volatile
    var lastInitError: Throwable? = null
        @JvmStatic get
        internal set

    /**
     * SPEC-070-A H.21: small-icon resource id used for AppDNA push notifications.
     * Hosts SHOULD set this via [Configuration.AppDNAOptions.notificationIcon] —
     * surfaced here as a top-level read for [AppDNAMessagingService] /
     * [RichPushHandler]. `0` means "use manifest meta-data, then app icon".
     */
    @Volatile
    var notificationIcon: Int = 0
        @JvmStatic get
        private set

    /**
     * SPEC-070-A H.20: degraded-init callback for the active [AppDNAInitDelegate].
     * Fires from [reportInitDegraded] when bootstrap detects a recoverable but
     * worth-noting failure (Firebase config missing, etc.).
     */
    @Volatile
    private var initDelegate: AppDNAInitDelegate? = null

    /**
     * SPEC-404 — host-registered lifecycle delegate. Fires
     * [ai.appdna.sdk.generated.AppDNALifecycleDelegate.onSdkRuntimeLocked] /
     * `onSdkRuntimeUnlocked` on bootstrap-driven lock-state transitions.
     * Hosts use this to surface a custom "service unavailable" banner and
     * trigger an event-queue retry on unlock.
     */
    @Volatile
    private var lifecycleDelegate: ai.appdna.sdk.generated.AppDNALifecycleDelegate? = null

    /** Register/clear the SPEC-404 lifecycle delegate. */
    @JvmStatic
    fun setLifecycleDelegate(delegate: ai.appdna.sdk.generated.AppDNALifecycleDelegate?) {
        lifecycleDelegate = delegate
    }

    /**
     * SPEC-404 — current backend-driven SDK lock state. `null` when active;
     * a non-null `Pair(reason, lockedAtIso)` means the SDK is in locked
     * mode and UI render paths (paywall_trigger, messages, surveys) should
     * pause. Set by the bootstrap success path; cleared by the next
     * bootstrap that returns without runtime_lock.
     */
    @Volatile
    var runtimeLock: Pair<String, String>? = null
        @JvmStatic get
        private set

    /**
     * Register a delegate that receives [AppDNAInitDelegate.onInitDegraded]
     * callbacks when the SDK detects a recoverable init failure.
     */
    @JvmStatic
    fun setInitDelegate(delegate: AppDNAInitDelegate?) {
        initDelegate = delegate
        // If we already entered a degraded state before the delegate registered,
        // surface it once so late-binding hosts don't miss the signal.
        val pending = lastInitError
        if (pending != null && delegate != null) {
            try {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    runCatching { delegate.onInitDegraded(pending) }
                }
            } catch (_: Throwable) { /* best-effort */ }
        }
    }

    // MARK: - Subsystem init isolation (SPEC-070-B W13 / AC-31(b))

    /**
     * Names of subsystems to fail on purpose. Test-only, and the exact seam iOS uses
     * (`AppDNA.swift:233`): proving the isolation holds requires INJECTING a failure into the real
     * `configure()`, and a reporting path that is never exercised is not isolation.
     */
    @Volatile
    internal var subsystemInitFailures: Set<String> = emptySet()

    /**
     * Build one subsystem in isolation. A subsystem that fails to start is reported as degraded and
     * left null; the event pipeline — wired earlier in [configure] — keeps running either way, and
     * so do `isConfigured`, `sdk_initialized`, and every `onReady` callback.
     *
     * 🔴 Android used to construct all five bare. A `PaywallManager` that threw took the HOST's
     * `Application.onCreate()` down with it, left `isConfiguring` latched true so no later
     * `configure()` could ever recover, and cost the app its analytics for the whole process.
     * Analytics is the floor guarantee here, exactly as it is on iOS.
     */
    internal fun <T> initSubsystem(name: String, make: () -> T): T? {
        return try {
            if (name in subsystemInitFailures) {
                throw AppDNAInitError.SubsystemFailed(name, "injected failure")
            }
            make()
        } catch (e: Throwable) {
            reportInitDegraded(
                AppDNAInitError.SubsystemFailed(name, e.message ?: e::class.java.simpleName)
            )
            null
        }
    }

    /**
     * SPEC-070-A H.20: record a non-fatal init error and notify the delegate.
     * Stored in [lastInitError] so hosts can read it any time after configure().
     */
    internal fun reportInitDegraded(error: Throwable) {
        lastInitError = error
        val delegate = initDelegate ?: return
        try {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                runCatching { delegate.onInitDegraded(error) }
                    .onFailure { Log.warning("AppDNAInitDelegate.onInitDegraded threw: ${it.message}") }
            }
        } catch (e: Throwable) {
            Log.warning("Init delegate fan-out failed: ${e.message}")
        }
    }

    // Module namespaces (v1.0)
    /** Push notification module. */
    @JvmStatic val push = PushModule()
    /** Billing module. */
    @JvmStatic val billing = BillingModule()

    // SPEC-070-A finalization B6#P0-25 — top-level delegate properties.
    // Mirrors iOS `AppDNA.pushDelegate`, `AppDNA.billingDelegate`,
    // `AppDNA.screenDelegate` so hosts copying iOS samples
    // (`AppDNA.screenDelegate = self`) compile on Android. Each property
    // proxies to the corresponding `module.setDelegate(...)`. Reads return
    // the most recently set value.

    private var _pushDelegate: AppDNAPushDelegate? = null
    /** SPEC-070-A finalization B6#P0-25 — top-level proxy for `push.setDelegate(...)`. */
    @JvmStatic
    var pushDelegate: AppDNAPushDelegate?
        get() = _pushDelegate
        set(value) { _pushDelegate = value; push.setDelegate(value) }

    private var _billingDelegate: AppDNABillingDelegate? = null
    /** SPEC-070-A finalization B6#P0-25 — top-level proxy for `billing.setDelegate(...)`. */
    @JvmStatic
    var billingDelegate: AppDNABillingDelegate?
        get() = _billingDelegate
        set(value) { _billingDelegate = value; billing.setDelegate(value) }

    private var _screenDelegate: ai.appdna.sdk.screens.AppDNAScreenDelegate? = null
    /** SPEC-070-A finalization B6#P0-25 — top-level proxy for `ScreenManager.shared.setDelegate(...)`. */
    @JvmStatic
    var screenDelegate: ai.appdna.sdk.screens.AppDNAScreenDelegate?
        get() = _screenDelegate
        set(value) { _screenDelegate = value; ai.appdna.sdk.screens.ScreenManager.shared.setDelegate(value) }

    private var _asyncOnScreenAction: (suspend (String, Map<String, Any?>) -> Boolean)? = null
    /**
     * SPEC-070-C D10 — top-level proxy for `ScreenManager.shared.asyncOnScreenAction`.
     * Set by a cross-platform wrapper (the Flutter plugin) so the async
     * `onScreenAction` wrapper-veto is consulted alongside the sync delegate
     * veto. Null for native hosts → synchronous action dispatch unchanged.
     */
    @JvmStatic
    var asyncOnScreenAction: (suspend (String, Map<String, Any?>) -> Boolean)?
        get() = _asyncOnScreenAction
        set(value) { _asyncOnScreenAction = value; ai.appdna.sdk.screens.ScreenManager.shared.asyncOnScreenAction = value }

    // SPEC-070-A finalization B6 P1 — top-level config-update broadcast.
    // Mirrors iOS `AppDNA.configUpdated = Notification.Name(...)`. RN/Flutter
    // wrappers + RemoteConfigModule observers collect on this Flow to react
    // to remote config refreshes. Replay=0 + extraBufferCapacity=1 so a
    // burst of refreshes coalesces without backpressure on the emitter.
    private val _configUpdated = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    /** Hot Flow that emits Unit on every successful remote-config refresh. */
    @JvmStatic
    val configUpdated: kotlinx.coroutines.flow.SharedFlow<Unit> = _configUpdated

    /** Internal — invoked by [ai.appdna.sdk.config.RemoteConfigManager.notifyChangeListeners]. */
    internal fun notifyConfigUpdated() {
        _configUpdated.tryEmit(Unit)
    }

    /** Onboarding module. */
    @JvmStatic val onboarding = ai.appdna.sdk.OnboardingModule()
    /** Paywall module. */
    @JvmStatic val paywall = ai.appdna.sdk.PaywallModule()
    /** Remote config module. */
    @JvmStatic val remoteConfig = ai.appdna.sdk.RemoteConfigModule()
    /** Feature flags module. */
    @JvmStatic val features = ai.appdna.sdk.FeaturesModule()
    /** In-app messages module. */
    @JvmStatic val inAppMessages = InAppMessagesModule()
    /** Surveys module. */
    @JvmStatic val surveys = ai.appdna.sdk.SurveysModule()
    /** Deep links module. */
    @JvmStatic val deepLinks = DeepLinksModule()
    /** Experiments module. */
    @JvmStatic val experiments = ai.appdna.sdk.ExperimentsModule()

    // MARK: - Custom View Registry (SPEC-089d AC-026)

    /**
     * Registry of developer-provided Composable factories keyed by `view_key`.
     * Used by the `custom_view` content block to render developer escape-hatch views.
     */
    @JvmStatic
    val registeredCustomViews: MutableMap<String, @Composable (Map<String, Any>) -> Unit> = mutableMapOf()

    /**
     * Register a custom Composable factory for use in onboarding content blocks.
     * @param key The `view_key` value from the block config.
     * @param factory A composable factory that receives the block's custom config map.
     */
    @JvmStatic
    fun registerCustomView(key: String, factory: @Composable (Map<String, Any>) -> Unit) {
        registeredCustomViews[key] = factory
    }

    /** Current config bundle version reported in events. */
    @JvmStatic var currentBundleVersion: Int = 0
        internal set

    /** SPEC-070-C D4 — the configured SDK-wrapper framework tag (native|flutter|
     * react_native); tagged on every event's device context. Defaults to "native". */
    @JvmStatic
    val framework: String get() = options.framework

    /**
     * SPEC-070-B §7 rule 4 — the WRAPPER's own version (e.g. the npm package's `1.0.7`), injected by
     * the bridge and tagged on every event's device context as `framework_version`.
     *
     * `device.sdk_version` is always this NATIVE core's version, so before this field existed an RN
     * app was indistinguishable from a native one in the warehouse on every version column — every
     * `react_native` row in `raw.sdk_events` carried the core's version, never the wrapper's. The
     * value reached `options.frameworkVersion` and was then read by exactly one thing, `diagnose()`,
     * which prints to the developer's own console and never leaves the device.
     *
     * Null for a native host (there is no wrapper to name), and the envelope omits it in that case.
     */
    @JvmStatic
    val frameworkVersion: String?
        get() = if (options.framework == "native") null else options.frameworkVersion?.takeIf { it.isNotBlank() }

    /**
     * Firestore instance used by the SDK.
     * Uses a secondary Firebase app ("appdna") if google-services-appdna.json is found in assets,
     * otherwise falls back to the default Firebase app's Firestore instance.
     */
    internal var firestoreDB: FirebaseFirestore? = null
        private set

    // Internal managers
    private var apiKey: String? = null
    private var environment: Environment = Environment.PRODUCTION
    // `internal`, not `private`: the unit tests are a friend module, and the wrapper-attribution
    // fields (`framework`, `frameworkVersion`) are only observable through a configured options
    // object. They were untestable while this was private — which is why no test ever covered the
    // envelope's `framework` tag either. Still invisible to SDK consumers.
    internal var options: AppDNAOptions = AppDNAOptions()

    /**
     * SPEC-070-B PN row 6 — retained so the Adapty bridge (and its delegate) outlive
     * [initBillingModuleIfNeeded]. Null unless `billingProvider` is [BillingProvider.Adapty].
     */
    @Volatile
    internal var adaptyBridge: ai.appdna.sdk.integrations.AdaptyBridge? = null

    private var apiClient: ApiClient? = null
    private var identityManager: IdentityManager? = null
    // @Volatile so track()'s double-checked-locking fast path (below) sees the configure() publish; the
    // reserve-pre-init-seq-block + publish happen together under preInitLock (SPEC-428 STEP-9).
    @Volatile private var eventTracker: EventTracker? = null
    private val preInitLock = Any()
    private var eventQueue: EventQueue? = null
    private var remoteConfigManager: RemoteConfigManager? = null
    private var featureFlagManager: FeatureFlagManager? = null
    private var experimentManager: ExperimentManager? = null
    private var pushTokenManager: PushTokenManager? = null
    private var revenueCatBridge: RevenueCatBridge? = null
    /**
     * 🔴 `internal`, NOT `private` — SO A TEST CAN REACH THE PATH WHERE THE RETURN VALUE IS DECIDED.
     *
     * `presentPaywall` returns `hasPaywall(id)`: false for a typo'd id, so a wrapper's promise no longer
     * resolves "shown" when nothing was. But every test of that Boolean either called `PaywallManager`
     * directly, or called the facade on an UNCONFIGURED SDK — which returns early at the null-manager
     * guard and never reaches `val known` at all.
     *
     * So the line that computes the answer was untested, and `val known = true` passed the whole suite:
     * a typo'd paywall id would report success again, and nothing would catch it. The test could not
     * fail, because it could not get to the code.
     */
    internal var paywallManager: PaywallManager? = null
    private var onboardingFlowManager: OnboardingFlowManager? = null
    private var surveyManager: SurveyManager? = null
    private var webEntitlementManager: WebEntitlementManager? = null
    // SPEC-070-A A.8/A.9: SessionManager + MessageManager wired in configure().
    // Snapshot of the active message catalog kept alongside the manager so the
    // configProvider lambda has a stable read path when triggers evaluate.
    internal var messageManager: ai.appdna.sdk.messages.MessageManager? = null
    internal var sessionManager: ai.appdna.sdk.core.SessionManager? = null
    @Volatile
    private var activeMessages: Map<String, ai.appdna.sdk.messages.MessageConfig> = emptyMap()
    // SPEC-203: journey-triggered pending-messages listener.
    private var pendingMessageListener: ai.appdna.sdk.messages.PendingMessageListener? = null
    private var deferredDeepLinkManager: DeferredDeepLinkManager? = null
    // SPEC-067: Scale Layer 1 components
    private var eventDatabase: ai.appdna.sdk.storage.EventDatabase? = null
    private var connectivityMonitor: ai.appdna.sdk.network.ConnectivityMonitor? = null
    private var appContext: Context? = null
    private var bootstrapOrgId: String? = null
    private var bootstrapAppId: String? = null

    /**
     * Internal accessor used by manager classes (e.g. ScreenManager) that
     * need to launch host Activities from outside an Activity context.
     * Returns null when the SDK has not yet been configured.
     *
     * SPEC-070-A finalization Lens B P0 — required by ScreenHostActivity
     * launch path so `AppDNA.showScreen(id)` can present a real Activity
     * even when the caller didn't supply one (matches iOS where
     * `AppDNA.showScreen` reads the application's key window itself).
     */
    @JvmStatic
    fun getApplicationContext(): Context? = appContext

    // SPEC-070-A final audit pass H F1 — captured to enable
    // `unregisterActivityLifecycleCallbacks` from shutdown().
    private var navigationInterceptorCallbacks:
        ai.appdna.sdk.screens.NavigationInterceptorActivityCallbacks? = null

    private var isConfigured = false
    // SPEC-070-A final audit pass H F3 — `isConfiguring` flips true
    // synchronously inside the `configure()` guard (before the bootstrap
    // coroutine launches) so concurrent / early second `configure(...)`
    // calls don't double-construct EventTracker/EventQueue, double-register
    // NavigationInterceptorActivityCallbacks, double-fetch FCM token, or
    // launch `performBootstrap` twice. `isConfigured` keeps its post-
    // bootstrap semantic for the `onReady` callback gate.
    @Volatile
    private var isConfiguring = false
    // SPEC-070-A final audit pass H F2 — `scope` must be reassignable so
    // `configure()` after `shutdown()` lands on a live CoroutineScope.
    // Previously `val scope` + `scope.cancel()` in shutdown() left the
    // SDK silently unready on re-configure (performBootstrap would skip).
    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val readyCallbacks = mutableListOf<() -> Unit>()

    // SPEC-070-A G.18: pre-init event buffer. Calls to track() that arrive before
    // configure() finishes wiring the EventTracker land here. Drained on first
    // setEventQueue call (after configure completes constructing the EventQueue).
    // Cap = 200; on overflow we log + drop the OLDEST so the most recent action
    // (e.g. a deep-link tap that triggered the configure call) survives.
    private const val PRE_INIT_BUFFER_CAP = 200
    // Last push_id whose tap was handled by handlePushTap — persisted to dedup re-delivery across
    // Activity re-creation / process death (see handlePushTap).
    private const val KEY_LAST_HANDLED_PUSH_TAP = "appdna.last_handled_push_tap_id"
    // SPEC-428 CL-10/CL-1: pre-init overflow drops can't touch the persisted DroppedEventsCounter yet
    // (no app Context before configure()), so accrue them in memory and fold into the durable counter
    // once configure() initializes it.
    private val preInitDroppedCount = java.util.concurrent.atomic.AtomicInteger(0)
    private data class PreInitEvent(val name: String, val properties: Map<String, Any>?)
    private val preInitBuffer = java.util.concurrent.LinkedBlockingQueue<PreInitEvent>(PRE_INIT_BUFFER_CAP)

    // SPEC-070-A G.17: most recent screen name observed by NavigationInterceptor
    // / ScreenManager / Compose hosts. Surfaced into every event envelope as
    // `context.screen` for SPEC-086 zero-code attribution. Updated through
    // [notifyScreenAppeared] (already public) so no extra API required.
    @Volatile private var lastScreenName: String? = null

    // MARK: - Public API: Initialization

    /**
     * Configure the SDK. Call once at Application.onCreate().
     */
    @JvmStatic
    fun configure(
        context: Context,
        apiKey: String,
        environment: Environment = Environment.PRODUCTION,
        options: AppDNAOptions = AppDNAOptions()
    ) {
        synchronized(this) {
            // SPEC-070-A final audit pass H F3 — guard against BOTH
            // post-bootstrap (`isConfigured`) and in-flight (`isConfiguring`)
            // re-entrancy. Without the in-flight guard, a second configure()
            // call before bootstrap completes would double-construct managers
            // and double-register lifecycle callbacks.
            if (isConfigured || isConfiguring) {
                Log.warning("AppDNA.configure() called multiple times — ignoring")
                return
            }
            isConfiguring = true

            this.apiKey = apiKey
            this.environment = environment
            this.options = options
            Log.level = options.logLevel
            // SPEC-070-A H.21: pin host-supplied icon for AppDNAMessagingService
            // / RichPushHandler. `0` keeps fallback resolution active.
            this.notificationIcon = options.notificationIcon

            Log.info("Configuring AppDNA SDK v$sdkVersion (${environment.name})")

            // Validate API key format
            if (!apiKey.startsWith("adn_live_") && !apiKey.startsWith("adn_test_")) {
                Log.error("API key format invalid. Keys must start with 'adn_live_' (production) or 'adn_test_' (sandbox). Got: ${apiKey.take(10)}...")
            }

            // SPEC-070-A A.31a — Firebase asset I/O (loadSecondaryFirebaseOptions
            // reads google-services-appdna.json and parses JSON) is moved off
            // the configure-thread (Main on Application.onCreate()) into the
            // bootstrap coroutine on Dispatchers.IO. Until bootstrap finishes
            // `firestoreDB` stays null — every consumer already null-checks it
            // (`firestoreDB?.collection(...)`), so the lazy init is safe.

            this.appContext = context.applicationContext
            // SPEC-428 CL-3/D6: init the monotonic seq counter BEFORE draining the pre-init buffer,
            // so drained + post-configure events all draw from the persisted (restart-surviving) counter.
            ai.appdna.sdk.events.ClientSeqCounter.init(context)
            // SPEC-428 CL-1/CL-10: init the durable dropped-events counter. The pre-init overflow fold is
            // deferred to the atomic publish block below (under preInitLock) so overflow drops that occur
            // DURING configure() — before eventTracker is published — are captured, not lost.
            ai.appdna.sdk.events.DroppedEventsCounter.init(context)
            val appContext = this.appContext ?: run {
                Log.error("AppDNA not initialized — call configure() with valid context")
                return
            }
            val appVersion = try {
                appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName ?: "0.0.0"
            } catch (_: Exception) { "0.0.0" }

            // 1. Initialize storage
            val storage = LocalStorage(appContext)

            // SPEC-088: Initialize cross-module session data store
            ai.appdna.sdk.core.SessionDataStore.initialize(appContext)

            // Custom-font loader: lets any element render a hosted .ttf/.otf font URL.
            ai.appdna.sdk.core.FontLoader.init(appContext)

            // 2. Initialize identity
            val identityMgr = IdentityManager(storage)
            this.identityManager = identityMgr

            // 3. Initialize networking
            val client = ApiClient(apiKey, environment)
            this.apiClient = client

            // 4. Initialize event system
            // SPEC-070-A G.10: tag every emitted event with the active SDK
            // environment ("production" / "sandbox") so ingest can route
            // test-key traffic to the sandbox dataset.
            val envTag = environment.name.lowercase()
            val tracker = EventTracker(identityMgr, appVersion, envTag)
            // SPEC-070-B PN row 14 (AC-36): resolve consent from the PERSISTED decision before
            // anything can be tracked — including the pre-init buffer drain and `sdk_initialized`.
            // A denied user used to be silently re-opted-in on every cold start.
            tracker.setInitialConsent(
                ai.appdna.sdk.storage.ConsentStore.effectiveConsent(appContext, options.requireConsent)
            )
            // NB: eventTracker is published LATER, atomically with the pre-init seq reservation (STEP-9).

            // SPEC-067: Initialize EventDatabase (SQLite) and ConnectivityMonitor
            val eventDb = ai.appdna.sdk.storage.EventDatabase(appContext)
            eventDb.migrateFromSharedPreferences(storage) // One-time migration
            this.eventDatabase = eventDb

            val connMonitor = ai.appdna.sdk.network.ConnectivityMonitor(appContext)
            this.connectivityMonitor = connMonitor

            // SPEC-070-A A.18: pass the application context + a background scheduler
            // so EventQueue can register a ProcessLifecycleOwner observer and hand
            // off remaining events to WorkManager when the app is backgrounded.
            val bgScheduler = object : ai.appdna.sdk.events.BackgroundUploadScheduler {
                override fun scheduleIfNeeded() {
                    try {
                        ai.appdna.sdk.background.EventUploadWorker.scheduleIfNeeded(
                            appContext, apiKey, environment.baseUrl, eventDb
                        )
                    } catch (e: Throwable) {
                        Log.warning("Background upload schedule failed: ${e.message}")
                    }
                }
            }

            val eq = EventQueue(
                apiClient = client,
                eventDatabase = eventDb,
                connectivityMonitor = connMonitor,
                batchSize = options.batchSize,
                flushInterval = options.flushInterval,
                context = appContext,
                backgroundUploadScheduler = bgScheduler
            )
            this.eventQueue = eq
            tracker.setEventQueue(eq)

            // SPEC-428 STEP-9/§4.E + SPEC-070-A G.18: drain the pre-init buffer, RESERVE a contiguous
            // client_seq block for those events, AND publish eventTracker — all ATOMICALLY under
            // preInitLock. This closes the window: a concurrent track() (which takes the same lock on its
            // slow path) either buffered BEFORE (→ included in `drained`) or, once eventTracker is visible,
            // mints a seq AFTER the reserved block. It can NEVER mint DURING the reserve and land below an
            // earlier pre-init event. (Android can't stamp pre-configure — no Context/prefs before
            // configure(); iOS stamps at facade track() time instead.)
            val drained = ArrayList<PreInitEvent>()
            val reserved: LongArray
            synchronized(preInitLock) {
                preInitBuffer.drainTo(drained)
                reserved = LongArray(drained.size) { ai.appdna.sdk.events.ClientSeqCounter.next() }
                // SPEC-428 CL-10 (#2): fold ALL pre-init overflow drops now, still under the lock — every
                // overflow increment happened in track()'s buffer path under this same lock, so this
                // captures the ones accrued DURING configure() (the early fold missed them).
                ai.appdna.sdk.events.DroppedEventsCounter.increment(preInitDroppedCount.getAndSet(0))
                this.eventTracker = tracker // publish LAST, holding the lock — post-configure mints now follow the block
            }
            if (drained.isNotEmpty()) {
                Log.info { "Draining ${drained.size} pre-init buffered event(s) into tracker" }
                for ((i, entry) in drained.withIndex()) {
                    tracker.track(entry.name, entry.properties, reserved[i])
                }
            }

            // SPEC-070-A G.17: Wire the screen-name source so every event
            // envelope can pick up `context.screen` for zero-code attribution.
            // The latest screen is tracked via [updateCurrentScreen] which
            // NavigationInterceptor + ScreenManager call as the user navigates
            // (wired in a follow-up item; field stays null safely until then).
            tracker.setScreenProvider { lastScreenName }

            // SPEC-070-A H.7: wire push-id provider so subsequent events fold
            // the most-recent push_id into `context.push_id` for attribution
            // (rolling 30-minute window managed by PushSessionContext).
            tracker.setPushIdProvider {
                ai.appdna.sdk.integrations.PushSessionContext.currentPushId(appContext)
            }

            // 5. Initialize push token manager (v0.4 SPEC-030: backend registration)
            this.pushTokenManager = PushTokenManager(context, storage, tracker, client)
            push.manager = this.pushTokenManager

            // SPEC-070-A H.18: proactively fetch the FCM token on configure()
            // instead of waiting for a token-rotation event (which can take
            // hours/days). Mirrors iOS PushTokenManager.configure() behavior.
            try {
                this.pushTokenManager?.registerToken()
            } catch (e: Throwable) {
                Log.warning("PushTokenManager.registerToken() failed during configure: ${e.message}")
            }

            // SPEC-070-A finalization Lens D P0 — drain any FCM token buffered
            // by `onNewPushToken()` calls that fired before `configure()`.
            val pending = pendingPushTokenBeforeConfigure
            if (pending != null) {
                pendingPushTokenBeforeConfigure = null
                try {
                    this.pushTokenManager?.onNewToken(pending)
                    Log.info("Drained pre-configure FCM push token buffer")
                } catch (e: Throwable) {
                    Log.warning("Replaying pre-configure FCM token failed: ${e.message}")
                }
            }

            // 6. Initialize config managers (Firestore)
            val remoteCfg = RemoteConfigManager(
                firestorePath = null, // Set after bootstrap
                storage = storage,
                configTTL = options.configTTL
            )
            // SPEC-070-A G.4: wire EventTracker so RemoteConfigManager.fetchConfigs()
            // can emit a `config_fetched` event after each successful refresh.
            remoteCfg.eventTracker = tracker
            this.remoteConfigManager = remoteCfg

            this.featureFlagManager = FeatureFlagManager(remoteCfg)

            val expManager = ExperimentManager(
                remoteConfigManager = remoteCfg,
                identityManager = identityMgr,
                eventTracker = tracker
            )
            this.experimentManager = expManager

            // SPEC-070-A A.14: every event envelope now carries
            // `context.experiment_exposures` so BigQuery ETL can attribute
            // conversions to the variant the user was bucketed into.
            tracker.setExperimentExposureProvider {
                // SPEC-070-A I.15 — getExposures() now returns named ExposureEntry
                // values; map to the EventTracker's ExperimentExposure wire shape.
                expManager.getExposures().map { entry ->
                    ai.appdna.sdk.events.ExperimentExposure(entry.experimentId, entry.variant)
                }
            }

            // Paywall & onboarding managers.
            // SPEC-036-F §1.2 — surface managers receive the ExperimentManager so
            // they can consult it for a running experiment targeting the surface+
            // entity being presented (treatment → render variant payload; control/
            // none → render the active entity through the normal path).
            //
            // SPEC-070-B W13 — each of the five is built in ISOLATION, under the same names iOS
            // uses. A constructor that throws leaves its own manager null and is reported through
            // `onInitDegraded`; it does NOT abort configure(), which would cost the host every
            // remaining subsystem, `isConfigured`, `sdk_initialized`, and every `onReady` callback.
            this.paywallManager = initSubsystem("paywall") {
                PaywallManager(
                    remoteConfigManager = remoteCfg,
                    eventTracker = tracker,
                    experimentManager = expManager
                )
            }

            this.onboardingFlowManager = initSubsystem("onboarding") {
                OnboardingFlowManager(
                    remoteConfigManager = remoteCfg,
                    eventTracker = tracker,
                    experimentManager = expManager
                )
            }

            // v0.3 managers
            this.surveyManager = initSubsystem("surveys") {
                SurveyManager(appContext, tracker, client, expManager)
            }
            this.webEntitlementManager = initSubsystem("web_entitlements") {
                WebEntitlementManager(tracker, appContext)
            }
            // SPEC-203: per-user journey message listener.
            this.pendingMessageListener = initSubsystem("in_app_messages") {
                ai.appdna.sdk.messages.PendingMessageListener(tracker, appContext)
            }

            // Wire survey config updates from RemoteConfigManager to SurveyManager
            remoteCfg.surveyUpdateHandler = { rawSurveys ->
                val parsed = rawSurveys.mapNotNull { (key, data) ->
                    SurveyConfig.fromMap(data)?.let { key to it }
                }.toMap()
                surveyManager?.updateConfigs(parsed)
            }

            // SPEC-070-A A.8: SessionManager — observes ProcessLifecycle to emit
            // session_start / session_end / app_open / app_close with 30-min idle rotation.
            this.sessionManager = initSubsystem("sessions") {
                ai.appdna.sdk.core.SessionDataStore.instance?.let { sds ->
                    ai.appdna.sdk.core.SessionManager(appContext, tracker, sds).also { it.start() }
                }
            }

            // SPEC-070-A A.9: MessageManager — trigger evaluator + frequency tracker
            // + presentation queue + delegate veto. Renderer separate (InAppMessageRenderer)
            // per architectural split.
            this.messageManager = initSubsystem("in_app_messages") {
                ai.appdna.sdk.core.SessionDataStore.instance?.let { _ ->
                    ai.appdna.sdk.messages.MessageManager(
                        context = appContext,
                    // SPEC-070-A finalization parity audit B1#6 — read live
                    // from RemoteConfigManager so Firestore-published
                    // in-app messages reach the manager. Previous
                    // configProvider returned the dead `activeMessages`
                    // field which was never written. Now reads
                    // remoteConfigManager.getActiveMessages() each call
                    // (also picks up updates after Firestore re-fetch).
                        configProvider = { remoteConfigManager?.getActiveMessages() ?: activeMessages },
                        // SPEC-036-F §1.2 — experiment-aware presentation.
                        experimentManager = expManager,
                    )
                }
            }

            // SPEC-070-A A.10: NavigationInterceptor — observes Activity resume to
            // fire registered hooks. Compose-only screens use AppDNA.notifyScreenAppeared(...).
            // SPEC-070-A final audit pass H F1 — capture the callback
            // instance so shutdown() can `unregisterActivityLifecycleCallbacks`
            // and a subsequent configure() doesn't double-register (which
            // would fire screen events twice).
            val navCallbacks = ai.appdna.sdk.screens.NavigationInterceptorActivityCallbacks(
                ai.appdna.sdk.screens.NavigationInterceptor.shared
            )
            navigationInterceptorCallbacks = navCallbacks
            (appContext as? android.app.Application)?.registerActivityLifecycleCallbacks(navCallbacks)

            // SPEC-070-A finalization parity audit B6#P0-1 — initialise the
            // SectionRegistry. Mirrors iOS `SectionRegistry.shared.registerBuiltInSections()`
            // call at AppDNA.configure(). Without this every SDUI screen renders
            // NOTHING because AppDNAScreenSlot dispatches through an empty
            // registry. Module wrappers (paywall/onboarding/survey/messages)
            // are registered via the registerModuleSections() extension.
            try {
                ai.appdna.sdk.screens.SectionRegistry.registerBuiltInSections()
                ai.appdna.sdk.screens.sections.registerAllModuleSections()
            } catch (e: Throwable) {
                Log.warning("SectionRegistry init failed: ${e.message}")
            }

            // SPEC-070-A H.3: schedule periodic 15-minute remote-config refresh
            // via WorkManager. Idempotent — safe across configure() races.
            try {
                ai.appdna.sdk.background.ConfigRefreshWorker.schedule(appContext)
            } catch (e: Throwable) {
                Log.warning("ConfigRefreshWorker.schedule failed: ${e.message}")
            }

            // 7. Bootstrap (fetch orgId/appId, then Firestore configs)
            scope.launch {
                performBootstrap(client, remoteCfg, tracker)
            }
        }
    }

    // MARK: - Public API: Identity

    /**
     * Link the anonymous device to a known user.
     */
    @JvmStatic
    @JvmOverloads
    fun identify(userId: String, traits: Map<String, Any>? = null) {
        // Capture pre-identify ids so the backend alias call + identify event
        // can include the previous values (iOS parity, AppDNA.swift:185-216).
        val previousAnonId = identityManager?.currentIdentity?.anonId
        val previousUserId = identityManager?.currentIdentity?.userId

        // Cross-account-leak defence — anchor the device's "first
        // identifier" the first time anyone identifies. Untagged
        // historical purchases (e.g. SDK-driven onboarding-paywall
        // purchases that fired BEFORE the host identified anyone) are
        // scoped to this anchor so a later user-switch can't inherit
        // them. Idempotent — a later identify(B) does NOT change the
        // anchor; that user gets `DenyUntaggedOtherUser` for untagged
        // purchases. Mirrors iOS AppDNA.swift behaviour. See
        // EntitlementOwnerFilter for the full decision matrix.
        //
        // Recorded BEFORE the inner identityManager.identify(...) call
        // so that any downstream observer that immediately reads
        // `firstIdentifiedToken()` sees the anchor populated. The
        // resolver's recordFirstIdentifiedUserIdIfNeeded is
        // `@Synchronized`, so concurrent identify() calls from
        // different threads will agree on a single "first" winner.
        ai.appdna.sdk.billing.AppAccountTokenResolver.recordFirstIdentifiedUserIdIfNeeded(userId)

        // Identity changed → clear the device-global subscription snapshot, or the new user's first
        // reconcile diffs their (user-filtered) entitlements against the PREVIOUS user's snapshot and
        // fabricates a phantom subscription_canceled/_renewal_failed. A fresh baseline emits nothing.
        if (previousUserId != userId) {
            appContext?.let {
                ai.appdna.sdk.billing.NativeBillingManager.clearPersistedSnapshot(ai.appdna.sdk.storage.LocalStorage(it))
            }
        }

        identityManager?.identify(userId, traits)

        // SPEC-070-A G.3: emit local `identify` event so the existing client
        // pipeline (BigQuery alias resolution + experiment exposure ledger)
        // sees the user-id transition immediately.
        track(
            IDENTIFY_EVENT,
            buildIdentifyProps(
                userId = userId,
                previousAnonId = previousAnonId,
                previousUserId = previousUserId,
                traits = traits,
            ),
        )

        // SPEC-070-A G.2: fire-and-forget POST to /api/v1/sdk/identify so the
        // backend can stitch anon → user identities even if the user never
        // emits another event. Retries on 5xx/network are handled inside
        // ApiClient.post() (called by postFireAndForget).
        // SPEC-070-A audit Round 2 finding 2: align body to iOS
        // AppDNA.swift:206-210 (`anon_id`, `user_id`, optional `traits`
        // only). The previous code added `device_id` which iOS never
        // emits, breaking backend canonical-shape parity.
        try {
            val body = JSONObject()
            body.put("anon_id", previousAnonId ?: "")
            body.put("user_id", userId)
            if (traits != null) body.put("traits", JSONObject(traits))
            apiClient?.postFireAndForget("/api/v1/sdk/identify", body.toString())
        } catch (e: Exception) {
            Log.warning { "Failed to post identify alias: ${e.message}" }
        }

        // Start web entitlement observer for this user (v0.3)
        val orgId = bootstrapOrgId
        val appId = bootstrapAppId
        if (orgId != null && appId != null) {
            webEntitlementManager?.startObserving(orgId, appId, userId)
            // SPEC-203: start journey-triggered pending-messages listener.
            pendingMessageListener?.startObserving(orgId, appId, userId)
        }

        // SPEC-401 Fix 1D — silently refresh the entitlement cache so the
        // next paywall_trigger entitlement gate (Fix 1A) reflects the
        // identified user's current Play Billing subscriptions, not the
        // prior anonymous user's empty entitlements. Fire-and-forget on
        // the SDK's lifecycle-bound `scope` (cancelled on shutdown);
        // identify is not blocked on completion. Errors are swallowed
        // inside refreshEntitlementCache. Mirrors iOS AppDNA.identify.
        scope.launch {
            billing.refreshEntitlementCache()
        }
    }

    /**
     * Get current user traits (for audience evaluation).
     */
    fun getUserTraits(): Map<String, Any> {
        return identityManager?.currentIdentity?.traits ?: emptyMap()
    }

    /**
     * Clear user identity (keeps anonymous ID).
     *
     * Resets the host-supplied user identity, experiment exposures, the
     * in-app message session, the survey session, the web-entitlement
     * observer, and the journey-triggered pending-message listener.
     * **Does NOT clear the device's first-identifier anchor used by
     * the cross-account-entitlement-leak defence** (see
     * `EntitlementOwnerFilter`) — that anchor is intentionally durable
     * for the lifetime of the app installation. App uninstall, or
     * Settings → Apps → Clear data, is the only path that wipes it.
     * This makes `reset()` safe to call as the host's "sign-out" hook
     * without re-opening the leak surface for a subsequent user signing
     * in on the same device.
     */
    @JvmStatic
    fun reset() {
        identityManager?.reset()
        experimentManager?.resetExposures()
        surveyManager?.resetSession()
        webEntitlementManager?.stopObserving()
        pendingMessageListener?.stopObserving()
        // SPEC-070-A A.9: clear in-session message frequency counters + queue.
        messageManager?.resetSession()
        // The subscription snapshot is device-global; identity is not. Clear it on sign-out so the next
        // user's first reconcile does not diff their entitlements against this user's snapshot and
        // fabricate a phantom subscription_canceled/_renewal_failed. Mirrors iOS.
        appContext?.let {
            ai.appdna.sdk.billing.NativeBillingManager.clearPersistedSnapshot(ai.appdna.sdk.storage.LocalStorage(it))
        }

        // 🔴 USER A'S ONBOARDING ANSWERS SURVIVED THE SIGN-OUT AND RENDERED INTO USER B'S PAYWALL.
        //
        // `SessionDataStore` is a PERSISTED process-global (SharedPreferences here, UserDefaults on iOS)
        // holding three buckets: onboarding responses, computed data, session data. `reset()` cleared
        // identity, exposures, message and survey session — and never touched it. Neither did
        // `shutdown()`. `clearAll()` existed on both platforms with ZERO callers.
        //
        // So after A signed out and B signed in on the same device, B could read A's onboarding answers
        // and structured location back via `getOnboardingResponses()` / `session.get(key)` /
        // `getLocationData(fieldId)`. And worse than the read: `TemplateEngine.buildContext()` feeds all
        // three buckets into the `{{…}}` namespace, so A's answers RENDERED INTO B's paywall, onboarding
        // and in-app-message copy. It survived app restarts. All four SDKs.
        //
        // `reset()` IS the sign-out boundary. `shutdown()` deliberately does NOT do this — it is a
        // lifecycle stop, not a user change.
        ai.appdna.sdk.core.SessionDataStore.instance?.clearAll()

        // Cross-account-leak defence — DELIBERATELY do NOT call
        // `AppAccountTokenResolver.clearFirstIdentifiedUserId()` here.
        // The anchor is a security boundary: clearing it on sign-out
        // would let the next `identify(B)` become the new first-
        // identifier and inherit any untagged purchase on the device
        // (the exact reproducer R2 surfaced). The anchor's natural
        // lifecycle is the app installation; uninstall / clear-data
        // wipes SharedPreferences, which is the correct invalidation
        // event. Mirrors iOS AppDNA.swift reset() behaviour.
        Log.info("Identity reset (session data cleared)")
    }

    // MARK: - Public API: Events

    /**
     * Track a custom event.
     */
    @JvmStatic
    @JvmOverloads
    /**
     * SPEC-401-A R80 (Lens B P2) — internal emit that skips survey/message
     * re-evaluation. Mirrors iOS `eventTracker.track(...)` direct path used
     * by MessageManager.swift:136/152/174 to avoid re-entrant trigger
     * recursion. Public `AppDNA.track()` still drives survey + message
     * triggers as documented.
     */
    internal fun trackInternal(event: String, properties: Map<String, Any>? = null) {
        eventTracker?.track(event, properties)
    }

    /**
     * Test-only observation seam, mirroring iOS `EventTracker.eventSink`.
     *
     * Everything the billing layer emits goes through the STATIC `AppDNA.track(...)`, whose tracker is
     * only ever published by `configure()` (which needs a network bootstrap). A unit test therefore
     * could not see a single billing event — which is exactly how Android shipped a purchase chain
     * that emitted `purchase_started` and `purchase_completed` TWICE per purchase without one test
     * noticing. Installing a real EventTracker (backed by a real EventQueue + EventDatabase) lets a
     * test read back the envelopes the SDK actually produced. Nil/unused in production.
     */
    internal fun installEventTrackerForTest(tracker: EventTracker?) {
        eventTracker = tracker
    }

    /**
     * Test-only seam, alongside [installEventTrackerForTest]. The Activity-presenting surfaces
     * (screens, flows) launch from [appContext], which only `configure()` (network bootstrap) sets —
     * so a unit test could not reach the presentation path at all, which is how `showFlow()` shipped
     * rendering literally nothing. Nil/unused in production.
     */
    internal fun installApplicationContextForTest(context: Context?) {
        appContext = context
    }

    fun track(event: String, properties: Map<String, Any>? = null) {
        // SPEC-428 STEP-9: double-checked locking. Fast path (post-configure) reads the @Volatile
        // eventTracker with no lock. If null, take preInitLock (which configure() holds while it reserves
        // the pre-init seq block + publishes eventTracker) and RE-CHECK: still null → buffer (pre-configure);
        // set → fall through to mint a seq AFTER configure's reserved block (no inversion, no lost event).
        var tracker = eventTracker
        if (tracker == null) {
            synchronized(preInitLock) {
                tracker = eventTracker
                if (tracker == null) {
                    // SPEC-070-A G.18: pre-init buffer. Cap = 200; on overflow drop the OLDEST.
                    val entry = PreInitEvent(event, properties)
                    if (!preInitBuffer.offer(entry)) {
                        preInitBuffer.poll()
                        preInitDroppedCount.incrementAndGet() // SPEC-428 CL-10/CL-1: count the overflow drop
                        if (!preInitBuffer.offer(entry)) {
                            Log.warning { "Pre-init event buffer full; dropping '$event'" }
                        } else {
                            Log.warning { "Pre-init event buffer full; dropping oldest" }
                        }
                    }
                    return
                }
            }
        }
        tracker!!.track(event, properties)
        // SPEC-401-A R85 (Lens B F1) — message-then-survey order matches iOS
        // AppDNA.swift:265-267. The first surface to call show() wins via the
        // global isPresenting guard, so reverse-order Android was producing
        // a different surface than iOS for events that triggered both.
        messageManager?.onEvent(event, properties ?: emptyMap())
        surveyManager?.onEvent(event, properties)
    }

    /**
     * SPEC-070-A A.10: notify the SDK that a Compose-only screen has appeared
     * (Activity-based screens fire automatically via the lifecycle callback
     * registered in `configure()`). Hosts should call this from
     * `LaunchedEffect(Unit)` of any composable that represents a navigable
     * destination if they want it to participate in NavigationInterceptor hooks.
     */
    fun notifyScreenAppeared(screenName: String) {
        // SPEC-070-A G.17: snapshot the latest screen so subsequent events
        // include `context.screen` even when the host hasn't wired any
        // NavigationInterceptor hooks (zero-code attribution path).
        lastScreenName = screenName
        ai.appdna.sdk.screens.NavigationInterceptor.shared.notifyScreenAppeared(screenName)
    }

    /**
     * Force flush all queued events immediately.
     */
    @JvmStatic
    fun flush() {
        eventQueue?.flush()
    }

    // MARK: - Public API: Remote Config

    /**
     * Get a remote config value by key.
     */
    fun getRemoteConfig(key: String): Any? {
        return remoteConfigManager?.getConfig(key)
    }

    /**
     * SPEC-067: Force an immediate config refresh, bypassing the cache TTL.
     */
    @JvmStatic
    fun forceRefreshConfig() {
        remoteConfigManager?.forceRefresh()
    }

    /**
     * Check if a feature flag is enabled.
     */
    fun isFeatureEnabled(flag: String): Boolean {
        return featureFlagManager?.isEnabled(flag) ?: false
    }

    // MARK: - Public API: Experiments

    /**
     * Get the variant assignment for an experiment.
     * Exposure is auto-tracked on first call per session.
     */
    @JvmStatic
    fun getExperimentVariant(experimentId: String): String? {
        // SPEC-070-A I.14 — ExperimentManager.getVariant now returns String?
        // matching iOS. No `.id` deref needed.
        return experimentManager?.getVariant(experimentId)
    }

    /**
     * Check if the user is in a specific variant.
     */
    @JvmStatic
    fun isInVariant(experimentId: String, variantId: String): Boolean {
        return experimentManager?.isInVariant(experimentId, variantId) ?: false
    }

    /**
     * Get a specific config value from the assigned variant's payload.
     */
    @JvmStatic
    fun getExperimentConfig(experimentId: String, key: String): Any? {
        return experimentManager?.getExperimentConfig(experimentId, key)
    }

    // MARK: - Public API: Paywalls

    /**
     * Present a paywall by ID from the given Activity.
     * Fetches the paywall config from remote config and presents the paywall UI.
     *
     * @param activity The Activity to present from.
     * @param id The paywall ID (matching Firestore config).
     * @param context Optional paywall context (placement, experiment, variant).
     * @param listener Optional listener for paywall lifecycle events.
     * @return `false` when NOTHING will be presented — the SDK is runtime-locked, was never
     *   configured, or no paywall with this id exists in the published config. `presentOnboarding`
     *   and `showScreen` have always reported this; this returned `Unit`, so every wrapper resolved
     *   its promise SUCCESSFULLY on a typo'd id and told the host a paywall it never saw was shown.
     */
    @JvmStatic
    @JvmOverloads
    fun presentPaywall(
        activity: Activity,
        id: String,
        context: PaywallContext? = null,
        listener: AppDNAPaywallDelegate? = null
    ): Boolean {
        // SPEC-404 — refuse to present any paywall while the SDK is in
        // backend-locked mode. A purchase here would be wasted UX (the
        // receipt-validate route would 401 and no entitlement would land).
        if (runtimeLock != null) {
            Log.warning("AppDNA.presentPaywall(id=$id) skipped — SDK in runtime-locked mode")
            return false
        }
        val manager = paywallManager ?: run {
            Log.warning("Cannot present paywall — SDK not configured")
            return false
        }
        // Read BEFORE presenting: `present` still runs on the unknown-id path, because its not-found
        // branch is what fires `onPaywallPurchaseFailed` and a native host relies on that callback.
        val known = manager.hasPaywall(id)
        manager.present(
            activity = activity,
            id = id,
            context = context,
            // A wrapper (React Native / Flutter) has no Kotlin delegate object to pass — its delegate
            // lives in JS/Dart behind `AppDNA.paywall.setDelegate(...)`. Without this fallback the
            // paywall RENDERS PERFECTLY and every callback is dead, and PaywallManager's
            // `onPromoCodeSubmit = if (listener != null) {...} else null` drops promo codes into the
            // no-delegate fallback that accepts ANY non-blank code. iOS has always fallen back
            // (AppDNA.swift:607/632).
            listener = resolvePaywallListener(listener)
        )
        return known
    }

    /**
     * SPEC-070-A finalization B6#P0-24 — facade overload for placement-based
     * paywall presentation. Mirrors iOS `AppDNA.presentPaywall(placement:from:context:delegate:)`
     * which selects the best paywall by audience rules + priority. The
     * underlying selection algorithm lives in
     * [ai.appdna.sdk.paywalls.PaywallManager.presentByPlacement] —
     * this is a thin wrapper so hosts don't have to reach through
     * `AppDNA.paywalls.manager?.presentByPlacement(...)`.
     *
     * @return `false` when no paywall matched the placement, the SDK is locked, or it was never
     *   configured. See [presentPaywall].
     */
    @JvmStatic
    @JvmOverloads
    fun presentPaywallByPlacement(
        activity: Activity,
        placement: String,
        context: PaywallContext? = null,
        listener: AppDNAPaywallDelegate? = null,
    ): Boolean {
        // SPEC-404 — same lock check as the id-based variant above.
        if (runtimeLock != null) {
            Log.warning("AppDNA.presentPaywallByPlacement(placement=$placement) skipped — SDK in runtime-locked mode")
            return false
        }
        val manager = paywallManager ?: run {
            Log.warning("Cannot present paywall by placement — SDK not configured")
            return false
        }
        val known = manager.hasPaywallForPlacement(placement)
        manager.presentByPlacement(
            activity = activity,
            placement = placement,
            context = context,
            listener = resolvePaywallListener(listener)
        )
        return known
    }

    // MARK: - Public API: Onboarding (v0.2)

    /**
     * Present an onboarding flow by ID. Returns false if config is unavailable.
     * If flowId is null, the active flow from remote config is used.
     *
     * @param activity The Activity to present from.
     * @param flowId Optional flow ID. If null, the active flow is used.
     * @param listener Optional listener for onboarding lifecycle events.
     * @return true if the flow was presented, false if config was not found.
     */
    /**
     * The fallback, in one named place so it is testable and so a reader can see the guarantee.
     *
     * A caller that omits the delegate gets the one the host registered. Every WRAPPER omits it —
     * React Native and Flutter have no Kotlin delegate object to hand over, because theirs lives in
     * JS/Dart behind `setDelegate`.
     */
    @JvmStatic
    internal fun resolveOnboardingListener(explicit: AppDNAOnboardingDelegate?): AppDNAOnboardingDelegate? =
        explicit ?: onboarding.listener

    /** @see resolveOnboardingListener */
    @JvmStatic
    internal fun resolvePaywallListener(explicit: AppDNAPaywallDelegate?): AppDNAPaywallDelegate? =
        explicit ?: paywall.listener

    @JvmStatic
    @JvmOverloads
    fun presentOnboarding(
        activity: Activity,
        flowId: String? = null,
        listener: AppDNAOnboardingDelegate? = null
    ): Boolean {
        // 🔴 A caller that omits the listener MUST still get the one the host registered via
        // `AppDNA.onboarding.setDelegate(...)`. iOS has always done this (AppDNA.swift:660); Android
        // did not, and it was invisible because the flow RENDERS PERFECTLY with a null delegate:
        //
        //   - every lifecycle callback (started/stepChanged/completed/dismissed) went nowhere;
        //   - the veto seam (onBeforeStepAdvance / onBeforeStepRender / onElementInteraction) never
        //     fired, because the renderer holds THIS reference;
        //   - and the auth gate checks a DIFFERENT reference (`onboarding.listener`, which IS set), so
        //     it saw a delegate, passed, and asked no one — an `email_login` / `verify_otp` step
        //     ADVANCED PAST THE CREDENTIAL STEP with nobody authenticating the user.
        //
        // Every wrapper hits this: React Native and Flutter have no Kotlin delegate object to hand
        // over, because their delegate lives in JS/Dart behind setDelegate.
        @Suppress("NAME_SHADOWING")
        val listener = resolveOnboardingListener(listener)
        // SPEC-401-A R26 — match iOS AppDNA.swift:386-394 thread-safety:
        // iOS uses `Thread.isMainThread` + `DispatchQueue.main.sync` to
        // marshal off-thread callers. Android was running the entire
        // present path (analytics track, delegate.onOnboardingStarted,
        // mutation of OnboardingActivity.pendingLaunchPayload static
        // var) on whatever thread the caller used — typical hosts call
        // from FCM `onMessageReceived` workers / retention webhook
        // callbacks / coroutine launches → race on the static payload +
        // delegates expect main-thread invocation. Marshal to the main
        // looper synchronously (mirrors iOS `.sync` semantics).
        val mainLooper = android.os.Looper.getMainLooper()
        if (android.os.Looper.myLooper() != mainLooper) {
            var result = false
            val latch = java.util.concurrent.CountDownLatch(1)
            android.os.Handler(mainLooper).post {
                result = presentOnMain(activity, flowId, listener)
                latch.countDown()
            }
            // Bounded wait so a stuck main thread can't permanently block
            // the caller. 5s is generous — if the main thread isn't
            // available, present would have failed anyway.
            latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
            return result
        }
        return presentOnMain(activity, flowId, listener)
    }

    private fun presentOnMain(
        activity: Activity,
        flowId: String?,
        listener: AppDNAOnboardingDelegate?,
    ): Boolean {
        // 🔴 A caller that passes no listener MUST still get the one the host registered via
        // `AppDNA.onboarding.setDelegate(...)`. iOS has always done this (`AppDNA.swift:660`,
        // `delegate ?? AppDNA.onboarding.delegate`); Android did not, and the consequence was
        // invisible because the surface still RENDERS perfectly with a null delegate:
        //
        //   - every lifecycle callback (started / stepChanged / completed / dismissed) went nowhere;
        //   - the veto seam (onBeforeStepAdvance / onBeforeStepRender / onElementInteraction) never
        //     fired at all, because the renderer holds THIS reference;
        //   - and worst, the auth gate at OnboardingActivity checks a DIFFERENT reference
        //     (`AppDNA.onboarding.listener`, which IS set), so it saw a delegate, passed, and never
        //     asked anyone — so an `email_login` / `verify_otp` step ADVANCED PAST THE CREDENTIAL
        //     STEP with nobody authenticating the user.
        //
        // Every wrapper (React Native, Flutter) hits this path, because a wrapper has no Kotlin
        // delegate object to hand over — its delegate lives in JS/Dart behind `setDelegate`.
        return onboardingFlowManager?.present(
            activity = activity,
            flowId = flowId,
            listener = listener
        ) ?: run {
            Log.warning("Cannot present onboarding — SDK not configured")
            false
        }
    }

    // MARK: - Public API: Server-Driven Screens (SPEC-089c)

    /** Show a server-driven screen by ID. */
    fun showScreen(screenId: String, callback: ((ai.appdna.sdk.screens.ScreenResult) -> Unit)? = null) {
        ai.appdna.sdk.screens.ScreenManager.shared.showScreen(screenId, callback)
    }

    /**
     * SPEC-070-A I.11 — preview a server-driven screen from a raw JSON
     * payload (no remote-config fetch). Mirrors iOS `AppDNA.previewScreen(json:)`.
     * Used by the console live-preview pipe + integration tests. Returns
     * `true` if the JSON parsed and a screen was presented, `false` otherwise.
     */
    fun previewScreen(json: String): Boolean {
        return ai.appdna.sdk.screens.ScreenManager.shared.previewScreen(json) != null
    }

    /**
     * SPEC-419 D6 — the applied (fetched + parsed) onboarding config version, for the
     * structural parity harness's readiness poll. The host app surfaces this into a hidden
     * `testTag("adn.appliedConfigVersion")` label the harness polls until it equals the
     * just-published version. Debug only — R8 elides in release builds.
     */
    fun debugAppliedConfigVersion(flowId: String? = null): Int? {
        if (!ai.appdna.sdk.BuildConfig.DEBUG) return null
        return remoteConfigManager?.debugAppliedOnboardingVersion(flowId)
    }

    /**
     * SPEC-070-A I.11 — fetch the structured [ai.appdna.sdk.onboarding.LocationData]
     * captured by the named onboarding form field. Returns `null` if the
     * field hasn't been answered yet, was answered with a free-text value,
     * or no flow has run this session. Mirrors iOS
     * `AppDNA.getLocationData(fieldId:)`.
     */
    fun getLocationData(fieldId: String): ai.appdna.sdk.onboarding.LocationData? {
        val responses = ai.appdna.sdk.core.SessionDataStore.instance?.onboardingResponses
            ?: return null
        for ((_, stepData) in responses) {
            val raw = stepData[fieldId] as? Map<*, *> ?: continue
            val address = raw["formatted_address"] as? String ?: raw["address"] as? String ?: continue
            return ai.appdna.sdk.onboarding.LocationData(
                formatted_address = address,
                city = raw["city"] as? String ?: "",
                state = raw["state"] as? String ?: "",
                state_code = raw["state_code"] as? String ?: "",
                country = raw["country"] as? String ?: "",
                country_code = raw["country_code"] as? String ?: "",
                latitude = (raw["latitude"] as? Number)?.toDouble() ?: 0.0,
                longitude = (raw["longitude"] as? Number)?.toDouble() ?: 0.0,
                timezone = raw["timezone"] as? String ?: "UTC",
                timezone_offset = (raw["timezone_offset"] as? Number)?.toInt() ?: 0,
                postal_code = raw["postal_code"] as? String,
                raw_query = raw["raw_query"] as? String ?: "",
            )
        }
        return null
    }

    /**
     * SPEC-070-A I.11 — schedule (or re-schedule) all SDK-owned background
     * tasks. Hosts can call this to opt into the periodic config refresh
     * job before [configure] returns, or after the host's WorkManager has
     * finished initialising.  Idempotent — WorkManager dedupes by
     * unique-name.  Mirrors iOS `AppDNA.registerBackgroundTasks()`.
     */
    fun registerBackgroundTasks() {
        try {
            appContext?.let { ai.appdna.sdk.background.ConfigRefreshWorker.schedule(it) }
        } catch (e: Throwable) {
            Log.warning("registerBackgroundTasks: schedule failed: ${e.message}")
        }
    }

    /**
     * SPEC-070-B PN row 16 (W12) — record that a host veto timed out and fell back to its default.
     *
     * The counter is `internal` to this module, and the only code that can observe a veto timing out
     * lives in a wrapper — the SDK itself awaits a veto forever. So the counter shipped with a reader
     * (`diagnose()`) and no writer, and `veto.timeouts_observed` read 0 no matter what. This is the
     * writer.
     */
    @JvmStatic
    fun recordVetoTimeout() {
        ai.appdna.sdk.core.VetoTimeoutCounter.increment()
    }

    /**
     * SPEC-070-A I.11 — debug snapshot of the SDK's bootstrap state. Returns
     * a multi-line human-readable string covering: configured flag, env
     * (production / sandbox), api base url, identity (anon_id + user_id),
     * org/app ids, sdk version, current bundle version, consent status.
     * Mirrors iOS `AppDNA.diagnose()`. Safe to call before configure() —
     * unset fields surface as `<unset>`.
     */
    fun diagnose(): String {
        val identity = identityManager?.currentIdentity
        val sb = StringBuilder()
        sb.appendLine("=== AppDNA SDK diagnose ===")
        sb.appendLine("configured: $isConfigured")
        sb.appendLine("environment: ${environment.name.lowercase()}")
        sb.appendLine("base_url: ${environment.baseUrl}")
        sb.appendLine("api_key: ${if (apiKey.isNullOrBlank()) "<unset>" else "${apiKey?.take(8)}…"}")
        val fw = options.framework
        val reportVersion = if (fw != "native" && !options.frameworkVersion.isNullOrBlank()) options.frameworkVersion else sdkVersion
        sb.appendLine("sdk_version: $reportVersion")
        if (fw != "native") sb.appendLine("platform: $fw wrapper (native core v$sdkVersion)")
        sb.appendLine("bundle_version: $currentBundleVersion")
        sb.appendLine("anon_id: ${identity?.anonId ?: "<unset>"}")
        sb.appendLine("user_id: ${identity?.userId ?: "<unset>"}")
        sb.appendLine("org_id: ${bootstrapOrgId ?: "<unset>"}")
        sb.appendLine("app_id: ${bootstrapAppId ?: "<unset>"}")
        sb.appendLine("consent.analytics: ${eventTracker?.isConsentGranted ?: true}")
        // SPEC-070-B PN rows 14 + 16: the settings whose effect is invisible until something goes
        // wrong — a silently opted-out user, and a veto that timed out into its default.
        val consentDecision = appContext?.let { ai.appdna.sdk.storage.ConsentStore.decision(it) }
        sb.appendLine(
            "consent.persisted: ${consentDecision?.toString() ?: "no decision yet"} " +
                "(requireConsent=${options.requireConsent})"
        )
        sb.appendLine("veto.timeout_seconds: ${options.vetoTimeout}")
        sb.appendLine("veto.timeouts_observed: ${ai.appdna.sdk.core.VetoTimeoutCounter.count}")
        sb.appendLine("init.last_error: ${lastInitError?.message ?: "<none>"}")
        sb.appendLine("=== end ===")
        return sb.toString()
    }

    /**
     * Show a server-driven multi-screen flow by ID. Mirrors iOS
     * `AppDNA.showFlow(_:completion:)` at AppDNA.swift:406. Forwards to
     * `ScreenManager.shared.showFlow(...)`. R87 P2 — surfaced as
     * `@JvmStatic` + `@JvmOverloads` so Java callers can call
     * `AppDNA.showFlow("welcome")` without supplying a null callback.
     */
    @JvmStatic
    @JvmOverloads
    fun showFlow(flowId: String, callback: ((ai.appdna.sdk.screens.FlowResult) -> Unit)? = null) {
        ai.appdna.sdk.screens.ScreenManager.shared.showFlow(flowId, callback)
    }

    /** Dismiss the currently presented server-driven screen or flow. */
    @JvmStatic
    fun dismissScreen() {
        ai.appdna.sdk.screens.ScreenManager.shared.dismissScreen()
    }

    /** Enable navigation interception for server-driven screens. */
    @JvmStatic
    @JvmOverloads
    fun enableNavigationInterception(forScreens: List<String>? = null) {
        ai.appdna.sdk.screens.ScreenManager.shared.enableNavigationInterception(forScreens)
    }

    /** Disable navigation interception. */
    fun disableNavigationInterception() {
        ai.appdna.sdk.screens.ScreenManager.shared.disableNavigationInterception()
    }

    /** Check if analytics consent is granted. */
    fun isConsentGranted(): Boolean {
        return eventTracker?.isConsentGranted ?: true
    }

    /**
     * Shorthand to show a paywall by ID (used by SDUI screen action routing).
     *
     * Resolves the most-recently-resumed Activity (tracked by
     * [NavigationInterceptorActivityCallbacks]) and routes through
     * [presentPaywall]. Mirrors iOS `AppDNA.showPaywall(_:)` which
     * presents from the top-most view controller.
     */
    @JvmStatic
    fun showPaywall(id: String) {
        val activity = topActivityRef?.get()
        if (activity == null) {
            Log.warning("showPaywall($id): no resumed Activity available; paywall not presented")
            return
        }
        presentPaywall(activity = activity, id = id)
    }

    /**
     * Top-most resumed Activity, tracked via the lifecycle callback registered
     * in [configure]. Used by [showPaywall] (and any future shorthands) to
     * route SDUI actions that lack an explicit Activity context.
     */
    @Volatile
    private var topActivityRef: java.lang.ref.WeakReference<Activity>? = null

    internal fun setTopActivity(activity: Activity?) {
        topActivityRef = activity?.let { java.lang.ref.WeakReference(it) }
    }

    /** Shorthand to show a survey by ID (used by screen action routing). */
    @JvmStatic
    fun showSurvey(id: String) {
        surveyManager?.present(surveyId = id)
    }

    // MARK: - Public API: Push Token + Push Tracking (v0.4 / SPEC-030)

    /**
     * Set the FCM push token. Call from FirebaseMessagingService.onNewToken().
     * This registers the token with the backend for direct push delivery.
     */
    @JvmStatic
    fun setPushToken(token: String) {
        pushTokenManager?.setPushToken(token)
    }

    /**
     * Report push permission status.
     */
    @JvmStatic
    fun setPushPermission(granted: Boolean) {
        pushTokenManager?.setPushPermission(granted)
    }

    /**
     * Track that a push notification was delivered.
     * Call from FirebaseMessagingService.onMessageReceived().
     */
    fun trackPushDelivered(pushId: String) {
        pushTokenManager?.trackDelivered(pushId)
    }

    /**
     * Track that a push notification was tapped.
     * Call from the tap intent handler.
     */
    fun trackPushTapped(pushId: String, action: String? = null) {
        pushTokenManager?.trackTapped(pushId, action)
    }

    /**
     * SPEC-070-A H.5 + H.9 — single entry point hosts call from
     * `Activity.onCreate(savedInstanceState)` / `onNewIntent(intent)` to:
     *   1. Track `push_tapped` (carries push_id + action_id),
     *   2. Fire [AppDNAPushDelegate.onPushTapped] with a typed [PushPayload],
     *   3. Auto-route built-in actions (`screen_id`, `deep_link`,
     *      `action_type=show_screen`, etc.) without requiring host code.
     *
     * Safe to call with any intent — does nothing if `push_id` extra is absent
     * (so hosts can call unconditionally).
     *
     * @return `true` if the intent was an AppDNA push tap and was handled.
     */
    @JvmStatic
    fun handlePushTap(intent: android.content.Intent?): Boolean {
        if (intent == null) return false
        val pushId = intent.getStringExtra("push_id") ?: return false
        if (pushId.isBlank()) return false

        // 🔴 DEDUP RE-DELIVERY. On Activity re-creation (rotation, "don't keep activities", process-death
        // restore) `onCreate` re-runs `handlePushTap(getIntent())` with the SAME push_id — re-emitting
        // push_tapped and re-routing every time, over-counting push-tap conversions. iOS emits from the
        // one-shot OS `didReceive` callback and cannot double-fire. Skip if we already handled this
        // push_id (persisted so it survives process death); still return true (it IS a push tap).
        val handledStore = appContext?.let { ai.appdna.sdk.storage.LocalStorage(it) }
        if (handledStore?.getString(KEY_LAST_HANDLED_PUSH_TAP) == pushId) {
            intent.removeExtra("push_id") // also clear the in-memory extra so it stops re-triggering
            return true
        }

        val actionId = intent.getStringExtra("action_id")
        val actionType = intent.getStringExtra("action_type")
        val actionValue = intent.getStringExtra("action_value")
        val screenId = intent.getStringExtra("screen_id")
        val deepLink = intent.getStringExtra("deep_link")

        // 1. Analytics
        try {
            // SPEC-401-A R81 (Lens B P2) — body-tap (no actionId / actionType)
            // emits the same sentinel iOS UNNotificationDefaultActionIdentifier
            // ships ("com.apple.UNNotificationDefaultActionIdentifier") so
            // BigQuery `action` column is non-null and equality-filterable on
            // both platforms. Was emitting null — broke "default tap vs
            // custom action" dashboards mixing platforms.
            val resolvedAction = actionId
                ?: actionType
                ?: "com.apple.UNNotificationDefaultActionIdentifier"
            trackPushTapped(pushId, resolvedAction)
        } catch (e: Throwable) {
            Log.warning("handlePushTap: trackPushTapped threw: ${e.message}")
        }

        // SPEC-070-A H.7: fold push_id forward for the next 30 minutes.
        try {
            appContext?.let {
                ai.appdna.sdk.integrations.PushSessionContext.recordPushReceived(it, pushId)
            }
        } catch (_: Throwable) { /* best-effort */ }

        // 2. Delegate fan-out
        try {
            val delegate = pushTokenManager?.pushListener
            if (delegate != null) {
                val payload = ai.appdna.sdk.PushPayload(
                    pushId = pushId,
                    title = "",
                    body = "",
                    action = actionType?.let { ai.appdna.sdk.PushAction(it, actionValue ?: "") },
                )
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    runCatching { delegate.onPushTapped(payload, actionId) }
                        .onFailure { Log.warning("AppDNAPushDelegate.onPushTapped threw: ${it.message}") }
                }
            }
        } catch (e: Throwable) {
            Log.warning("handlePushTap: delegate fan-out failed: ${e.message}")
        }

        // 3. Auto-routing — screen_id wins, then deep_link, then action_type/value.
        try {
            when {
                !screenId.isNullOrBlank() -> {
                    Log.debug("handlePushTap: auto-routing to screen $screenId")
                    showScreen(screenId)
                }
                !deepLink.isNullOrBlank() -> {
                    Log.debug("handlePushTap: auto-routing deep link $deepLink")
                    deepLinks.handleURL(deepLink)
                }
                actionType == "show_screen" && !actionValue.isNullOrBlank() -> {
                    showScreen(actionValue)
                }
                actionType == "deep_link" && !actionValue.isNullOrBlank() -> {
                    deepLinks.handleURL(actionValue)
                }
                actionType == "show_paywall" && !actionValue.isNullOrBlank() -> {
                    showPaywall(actionValue)
                }
                actionType == "show_survey" && !actionValue.isNullOrBlank() -> {
                    showSurvey(actionValue)
                }
            }
        } catch (e: Throwable) {
            Log.warning("handlePushTap: auto-route failed: ${e.message}")
        }

        // Mark this push_id handled (persisted) + clear the extra so a re-created Activity re-running
        // handlePushTap(getIntent()) with the same intent cannot re-fire push_tapped. See top guard.
        handledStore?.setString(KEY_LAST_HANDLED_PUSH_TAP, pushId)
        intent.removeExtra("push_id")
        return true
    }

    // SPEC-070-A finalization (Lens D P0) — pre-configure FCM token buffer.
    // FirebaseMessagingService can fire onNewToken before the host calls
    // `AppDNA.configure(...)`. Mirrors iOS PushTokenManager.pendingTokenBeforeConfigure.
    @Volatile private var pendingPushTokenBeforeConfigure: String? = null

    /**
     * Called when FCM token refreshes. Re-registers with backend.
     *
     * SPEC-070-A finalization Lens D P0: when called BEFORE [configure],
     * buffer the token in [pendingPushTokenBeforeConfigure] and replay it
     * inside `configure()` once `pushTokenManager` is wired. Without this,
     * apps that registered FCM at boot dropped tokens silently.
     */
    @JvmStatic
    fun onNewPushToken(token: String) {
        val mgr = pushTokenManager
        if (mgr != null) {
            mgr.onNewToken(token)
        } else {
            pendingPushTokenBeforeConfigure = token
        }
    }

    // MARK: - Public API: Session Data (SPEC-088)

    /**
     * Store a key-value pair in the cross-module session data store.
     * Available to all modules via `{{session.key}}` template variables.
     */
    @JvmStatic
    fun setSessionData(key: String, value: Any) {
        ai.appdna.sdk.core.SessionDataStore.instance?.setSessionData(key, value)
    }

    /**
     * Retrieve a session data value by key.
     */
    @JvmStatic
    fun getSessionData(key: String): Any? {
        return ai.appdna.sdk.core.SessionDataStore.instance?.getSessionData(key)
    }

    /**
     * Clear all app-defined session data.
     */
    @JvmStatic
    fun clearSessionData() {
        ai.appdna.sdk.core.SessionDataStore.instance?.clearSessionData()
    }

    // MARK: - Public API: Privacy

    /**
     * Set analytics consent. When false, events are silently dropped.
     */
    fun setConsent(analytics: Boolean) {
        // SPEC-070-B PN row 14 (AC-36): persist FIRST. A crash before the write would lose a
        // revocation, and the next launch would re-enable analytics for a user who opted out.
        appContext?.let { ai.appdna.sdk.storage.ConsentStore.setDecision(it, analytics) }
        eventTracker?.setConsent(analytics)
        Log.info("Consent updated: analytics=$analytics")
    }

    // MARK: - Public API: Log Level

    /**
     * Set the SDK log level at runtime.
     * Valid values: "none", "error", "warning", "info", "debug".
     */
    @JvmStatic
    fun setLogLevel(level: String) {
        val logLevel = when (level.lowercase()) {
            "none" -> LogLevel.NONE
            "error" -> LogLevel.ERROR
            "warning" -> LogLevel.WARNING
            "info" -> LogLevel.INFO
            "debug" -> LogLevel.DEBUG
            else -> {
                Log.warning("Unknown log level '$level' — defaulting to INFO")
                LogLevel.INFO
            }
        }
        Log.level = logLevel
        Log.info("Log level set to ${logLevel.name}")
    }

    /**
     * SPEC-070-A finalization Phase C — strict-typed LogLevel overload.
     * Mirrors iOS `setLogLevel(_ level: LogLevel)` (typed enum, no
     * stringly-typed fallback). Java callers can use either overload
     * (`AppDNA.setLogLevel("debug")` or `AppDNA.setLogLevel(LogLevel.DEBUG)`).
     */
    @JvmStatic
    fun setLogLevel(level: LogLevel) {
        Log.level = level
        Log.info("Log level set to ${level.name}")
    }

    /**
     * SPEC-070-A finalization Phase C — app-level forced theme override.
     * Mirrors iOS `AppDNA.setForcedTheme(_ theme: ForcedTheme?)` (TBD).
     * When set, the SDK's renderers prefer this theme over the system
     * pref. Pass null to clear the override and resume system-pref behavior.
     */
    @Volatile
    private var _forcedTheme: ForcedTheme? = null

    /** Read the currently-set forced theme. Null means "follow system". */
    @JvmStatic
    fun getForcedTheme(): ForcedTheme? = _forcedTheme

    /** Set or clear the SDK-wide forced theme override. */
    @JvmStatic
    fun setForcedTheme(theme: ForcedTheme?) {
        _forcedTheme = theme
        Log.info("Forced theme set to ${theme?.name ?: "null (follow system)"}")
    }

    // MARK: - Public API: Web Entitlements (v0.3)

    /**
     * Get the current web subscription entitlement.
     */
    val webEntitlement: WebEntitlement?
        get() = webEntitlementManager?.currentEntitlement

    /**
     * Register a listener for web entitlement changes.
     */
    fun onWebEntitlementChanged(listener: (WebEntitlement?) -> Unit) {
        webEntitlementManager?.addChangeListener(listener)
    }

    /**
     * Detach a listener registered with [onWebEntitlementChanged]. Removal is by reference identity —
     * keep the lambda in a `val`. Without this a wrapper could not clean up on reload, and every
     * reload left another live module attached to the singleton.
     */
    fun removeWebEntitlementListener(listener: (WebEntitlement?) -> Unit) {
        webEntitlementManager?.removeChangeListener(listener)
    }

    // MARK: - Public API: Deferred Deep Links (v0.3)

    /**
     * Check for a deferred deep link on first launch.
     * Call after configure() and onReady.
     */
    fun checkDeferredDeepLink(callback: (DeferredDeepLink?) -> Unit) {
        deferredDeepLinkManager?.checkDeferredDeepLink(callback) ?: callback(null)
    }

    // MARK: - Public API: Ready callback

    /**
     * Register a callback that fires when the SDK is fully initialized.
     */
    fun onReady(callback: () -> Unit) {
        // SPEC-401-A R76 (Lens B P2) — always invoke `callback` on the main
        // thread, matching iOS AppDNA.swift:733-741 + :1098-1102 which
        // wraps with `DispatchQueue.main.async`. Was running inline on the
        // synchronized(this) thread (which could be Main if called from
        // Activity.onCreate, but a worker/coroutine context kept the
        // callback on a background thread — host code touching findViewById
        // / Compose recomposition / ViewModel mutation in `onReady` would
        // throw or violate main-thread contract).
        synchronized(this) {
            if (isConfigured) {
                android.os.Handler(android.os.Looper.getMainLooper()).post(callback)
            } else {
                readyCallbacks.add(callback)
            }
        }
    }

    // MARK: - Internal accessors (used by onboarding hooks, SPEC-088)

    /**
     * SPEC-070-A A.12: Returns the configured environment's base URL so internal
     * Composables (e.g. the AC-046 location autocomplete renderer) can issue
     * sandbox-vs-production-aware backend requests without hard-coding hosts.
     *
     * Returns Production base URL when called before [configure] (defensive fallback).
     */
    internal fun getApiBaseUrl(): String = environment.baseUrl

    /**
     * SPEC-070-A: Internal accessor for the SDK API key, used by ad-hoc HTTP calls
     * inside renderers that don't go through [ApiClient] (e.g. the geocode autocomplete).
     * Null before [configure] runs.
     */
    internal fun getApiKey(): String? = apiKey

    /**
     * SPEC-448 — the Option Set store's client. Mirrors iOS `AppDNA.optionSetClient`.
     * Null before [configure] runs, which the store treats as "no refresh possible" rather than an
     * error, so the fallback ladder still renders.
     */
    internal val optionSetClient: ApiClient? get() = apiClient

    internal fun getCurrentUserId(): String? {
        return identityManager?.currentIdentity?.userId
            ?: identityManager?.currentIdentity?.anonId
    }

    internal fun getCurrentAppId(): String? {
        return bootstrapAppId
    }

    internal fun getRemoteConfigFlag(key: String): String? {
        return remoteConfigManager?.getConfig(key) as? String
    }

    /** Internal reference to identity for TemplateEngine (SPEC-088). */
    internal fun getIdentityRef(): DeviceIdentity? {
        return identityManager?.currentIdentity
    }

    // MARK: - Internal bootstrap

    private suspend fun performBootstrap(
        client: ApiClient,
        remoteCfg: RemoteConfigManager,
        tracker: EventTracker
    ) {
        // SPEC-070-A A.31a — Firebase asset I/O moved off the configure-thread.
        // Reads google-services-appdna.json from assets + parses JSON; we run
        // it here on Dispatchers.IO before any Firestore consumer needs it.
        // SPEC-070-A A.24 — also adds GCM sender ID so the secondary
        // FirebaseApp can do FCM (otherwise PushTokenManager.registerToken
        // returns nothing because the SDK FirebaseApp has no project number).
        initSecondaryFirebaseAppIfNeeded()

        // SPEC-070-A A.26 — instantiate NativeBillingManager and bind to
        // BillingModule.manager so AppDNA.billing.purchase() / restore() /
        // getProducts() / getEntitlements() all work. Was never set,
        // resulting in silent no-ops every time the host called billing.
        initBillingModuleIfNeeded(tracker, client)

        // Round-10 #14 — retry the cold-start bootstrap on transient 5xx/network blips (iOS parity),
        // instead of degrading to cached config on the first failure.
        val result = client.getWithRetry("/api/v1/sdk/bootstrap")
        if (result != null) {
            try {
                val orgId = result.optString("orgId", "").ifEmpty { throw IllegalArgumentException("Missing orgId") }
                val appId = result.optString("appId", "").ifEmpty { throw IllegalArgumentException("Missing appId") }
                val firestorePath = result.optString("firestorePath", "").ifEmpty { throw IllegalArgumentException("Missing firestorePath") }

                Log.info("Bootstrap successful: orgId=$orgId, appId=$appId")

                // SPEC-404 — reconcile runtime lock state. Fire delegate
                // callbacks ONLY on state transitions (idle <-> locked), not
                // on every bootstrap. Repeated bootstraps in the same state
                // are a no-op for the delegate.
                val newLock: Pair<String, String>? = result.optJSONObject("runtime_lock")?.let { lockObj ->
                    val reason = lockObj.optString("reason", "")
                    val lockedAt = lockObj.optString("locked_at", "")
                    if (reason.isNotEmpty() && lockedAt.isNotEmpty()) Pair(reason, lockedAt) else null
                }
                val previousLock = runtimeLock
                runtimeLock = newLock
                if (previousLock == null && newLock != null) {
                    Log.warning("AppDNA runtime locked by backend (reason=${newLock.first}, locked_at=${newLock.second}) — pausing paywall/message/survey presentation")
                    lifecycleDelegate?.onSdkRuntimeLocked(newLock.first, newLock.second)
                } else if (previousLock != null && newLock == null) {
                    Log.info("AppDNA runtime lock cleared — restoring normal SDK behaviour")
                    lifecycleDelegate?.onSdkRuntimeUnlocked()
                }

                synchronized(this@AppDNA) {
                    bootstrapOrgId = orgId
                    bootstrapAppId = appId

                    // Propagate the Firestore tenant scope into RemoteConfigManager.
                    // Without this assignment, fetchConfigs() always falls through
                    // to "No Firestore path available — serving cached config only"
                    // because the manager was constructed with a null path.
                    remoteCfg.setFirestorePath(firestorePath)

                    // v0.3: Create deferred deep link manager
                    appContext?.let { ctx ->
                        deferredDeepLinkManager = DeferredDeepLinkManager(ctx, orgId, appId, tracker)
                    }

                    // Start web entitlement observer if user is already identified
                    identityManager?.currentIdentity?.userId?.let { userId ->
                        webEntitlementManager?.startObserving(orgId, appId, userId)
                        // SPEC-203: pending-messages listener when already identified.
                        pendingMessageListener?.startObserving(orgId, appId, userId)
                    }
                }

                // Fetch Firestore configs
                remoteCfg.fetchConfigs()
            } catch (e: Exception) {
                Log.error("Bootstrap parse error: ${e.message}")
            }
        } else {
            Log.warning("Bootstrap failed — using cached config")
        }

        // Wire module namespaces (v1.0)
        synchronized(this@AppDNA) {
            onboarding.manager = onboardingFlowManager
            paywall.manager = paywallManager
            remoteConfig.manager = remoteConfigManager
            features.manager = featureFlagManager
            surveys.manager = surveyManager
            experiments.manager = experimentManager
        }

        // Load bundled config (v1.0 offline-first)
        loadConfigBundle()

        // Mark ready
        synchronized(this@AppDNA) {
            isConfigured = true
            // SPEC-070-A final audit pass H F3 — release the in-flight
            // configure-guard so future shutdown-then-configure cycles can
            // re-arm it. Together with `scope` reassign in shutdown() this
            // makes the SDK fully re-initializable.
            isConfiguring = false
            tracker.track("sdk_initialized")
            Log.info("SDK ready")

            val callbacks = ArrayList(readyCallbacks)
            readyCallbacks.clear()
            // SPEC-401-A R76 (Lens B P2) — bootstrap drain runs on
            // Dispatchers.IO. Force `cb()` onto Main matching iOS
            // AppDNA.swift:1098-1102 `DispatchQueue.main.async { cb() }`.
            // Hosts touching findViewById / Compose / ViewModel in onReady
            // would otherwise crash on the cold path.
            val main = android.os.Handler(android.os.Looper.getMainLooper())
            for (cb in callbacks) {
                main.post(cb)
            }
        }
    }

    // MARK: - Config Bundle (v1.0 offline-first)

    /**
     * Load config from bundle embedded in app assets.
     * Priority: remote (already fetched) > cached > bundled.
     */
    @Suppress("UNCHECKED_CAST")
    private fun loadConfigBundle() {
        try {
            val ctx = appContext ?: return
            val inputStream = ctx.assets.open("appdna-config.json")
            val json = inputStream.bufferedReader().use { it.readText() }
            val config = JSONObject(json)
            val bundleVersion = config.optInt("bundle_version", 0)
            currentBundleVersion = bundleVersion

            // Feed bundled config to RemoteConfigManager — only fills empty caches
            // Convert JSONObject to Map recursively so parse methods work
            val map = jsonObjectToMap(config)
            remoteConfigManager?.loadBundledConfig(map)
            Log.info("Loaded bundled config (version $bundleVersion)")
        } catch (_: Exception) {
            Log.debug("No bundled config found at appdna-config.json — using remote/cached only")
        }
    }

    /**
     * Load FirebaseOptions from google-services-appdna.json in app assets.
     * Returns null if the file is not found or cannot be parsed.
     */
    /** Recursively convert JSONObject to Map<String, Any> for parse methods. */
    private fun jsonObjectToMap(obj: JSONObject): Map<String, Any> {
        val map = mutableMapOf<String, Any>()
        for (key in obj.keys()) {
            val value = obj.get(key)
            when (value) {
                JSONObject.NULL -> {} // Skip null values
                is JSONObject -> map[key] = jsonObjectToMap(value)
                is org.json.JSONArray -> map[key] = jsonArrayToList(value)
                else -> map[key] = value
            }
        }
        return map
    }

    private fun jsonArrayToList(arr: org.json.JSONArray): List<Any> {
        val list = mutableListOf<Any>()
        for (i in 0 until arr.length()) {
            val value = arr.get(i)
            when (value) {
                JSONObject.NULL -> {} // Skip null values
                is JSONObject -> list.add(jsonObjectToMap(value))
                is org.json.JSONArray -> list.add(jsonArrayToList(value))
                else -> list.add(value)
            }
        }
        return list
    }

    private fun loadSecondaryFirebaseOptions(context: Context): FirebaseOptions? {
        return try {
            val inputStream = context.assets.open("google-services-appdna.json")
            val json = inputStream.bufferedReader().use { it.readText() }
            val config = JSONObject(json)

            // google-services.json format has project_info and client arrays
            val projectInfo = config.optJSONObject("project_info") ?: return null
            val projectId = projectInfo.optString("project_id", "").ifEmpty { return null }
            val storageBucket = projectInfo.optString("storage_bucket", "$projectId.appspot.com")
            // SPEC-070-A A.24 (part 2) — pull `project_number` so we can populate
            // `setGcmSenderId` on the secondary FirebaseApp. Without this, FCM
            // (`FirebaseMessaging.getInstance(secondaryApp).token`) returns an
            // error because the secondary app has no sender ID.
            val gcmSenderId = projectInfo.optString("project_number", "").ifEmpty { null }

            // Find the first client entry
            val clients = config.optJSONArray("client") ?: return null
            if (clients.length() == 0) return null
            val client = clients.optJSONObject(0) ?: return null

            val clientInfo = client.optJSONObject("client_info") ?: return null
            val mobileSdkAppId = clientInfo.optString("mobilesdk_app_id", "").ifEmpty { return null }

            // API key from api_key array
            val apiKeys = client.optJSONArray("api_key")
            val apiKey = if (apiKeys != null && apiKeys.length() > 0) {
                apiKeys.optJSONObject(0)?.optString("current_key", null)
            } else null

            val builder = FirebaseOptions.Builder()
                .setProjectId(projectId)
                .setApplicationId(mobileSdkAppId)
                .setStorageBucket(storageBucket)

            if (apiKey != null) {
                builder.setApiKey(apiKey)
            }
            if (gcmSenderId != null) {
                builder.setGcmSenderId(gcmSenderId)
            }

            builder.build()
        } catch (_: Exception) {
            null
        }
    }

    // SPEC-070-A A.31a — Firebase asset I/O moved off the configure-thread.
    private fun initSecondaryFirebaseAppIfNeeded() {
        val context = appContext ?: return
        if (firestoreDB != null) return  // already initialized
        val secondaryOptions = loadSecondaryFirebaseOptions(context)
        synchronized(this@AppDNA) {
            if (secondaryOptions != null) {
                val existingApp = try { FirebaseApp.getInstance("appdna") } catch (_: Exception) { null }
                if (existingApp == null) {
                    FirebaseApp.initializeApp(context, secondaryOptions, "appdna")
                }
                val secondaryApp = try { FirebaseApp.getInstance("appdna") } catch (_: Exception) { null }
                if (secondaryApp != null) {
                    firestoreDB = FirebaseFirestore.getInstance(secondaryApp)
                    Log.info("Firebase: Using secondary app 'appdna' (google-services-appdna.json)")
                } else {
                    val msg = "Firebase: google-services-appdna.json found but failed to create secondary app. Check the JSON content."
                    Log.error(msg)
                    // SPEC-070-A H.20: surface degraded init state to host.
                    reportInitDegraded(IllegalStateException(msg))
                }
            } else if (FirebaseApp.getApps(context).isEmpty()) {
                val msg = "Firebase: No configuration found. Download google-services-appdna.json from Console -> Settings -> SDK and add it to your app/src/main/assets/ directory. See: https://docs.appdna.ai/sdks/android/installation#firebase-configuration"
                Log.error(msg)
                reportInitDegraded(IllegalStateException(msg))
            } else {
                val msg = "Firebase: Your app already has Firebase configured (its own project), but google-services-appdna.json was NOT found. AppDNA needs its own Firebase config. Download it from Console -> Settings -> SDK and add to app/src/main/assets/. Remote config will NOT work without this file."
                Log.error(msg)
                reportInitDegraded(IllegalStateException(msg))
            }
        }
    }

    // SPEC-070-A A.26 — instantiate NativeBillingManager and bind to BillingModule.
    private fun initBillingModuleIfNeeded(
        tracker: ai.appdna.sdk.events.EventTracker,
        client: ai.appdna.sdk.network.ApiClient,
    ) {
        if (billing.manager != null) return
        val ctx = appContext ?: return

        // SPEC-070-B PN row 6 (N1): honor the configured provider. Before this, Android ignored the
        // host's choice entirely and Adapty's apiKey had nowhere to live — AdaptyBridge had zero
        // call sites.
        val provider = options.billingProvider
        if (provider is BillingProvider.None) {
            Log.info("BillingModule: billingProvider = none — billing disabled")
            return
        }

        val storage = LocalStorage(ctx)
        try {
            val verifier = ai.appdna.sdk.billing.ReceiptVerifier(client)
            val cache = ai.appdna.sdk.billing.EntitlementCache(ctx)
            val mgr = ai.appdna.sdk.billing.NativeBillingManager(
                context = ctx,
                receiptVerifier = verifier,
                entitlementCache = cache,
                storage = storage,
            )
            mgr.initialize()
            synchronized(this@AppDNA) {
                billing.manager = mgr
            }
            // Start observing entitlement changes from Firestore once we have org/app/user.
            val orgId = bootstrapOrgId
            val appId = bootstrapAppId
            val userId = identityManager?.currentIdentity?.userId
            if (orgId != null && appId != null && userId != null) {
                cache.startObserving(orgId, appId, userId)
            }

            when (provider) {
                is BillingProvider.Adapty -> {
                    val bridge = ai.appdna.sdk.integrations.AdaptyBridge()
                    bridge.configure(provider.apiKey)
                    adaptyBridge = bridge
                }
                is BillingProvider.RevenueCat ->
                    // Matches iOS: the `.revenueCat` case carries no key, because the host configures
                    // Purchases itself and forwards results. We only note the choice.
                    Log.info("BillingModule: billingProvider = revenueCat — host is expected to call Purchases.configure()")
                else -> Unit
            }

            Log.info("BillingModule: NativeBillingManager initialized (provider=${provider.type})")
        } catch (e: Exception) {
            Log.error("BillingModule init failed: ${e.message}")
            // SPEC-070-B PN row 17 (W13/AC-31(b)): billing is not analytics. A failed billing init is a
            // degraded SDK, not a dead one — events keep flowing.
            reportInitDegraded(e)
        }
    }

    /**
     * Internal accessor for bridges that need the application context but
     * aren't passed it directly (e.g. `RevenueCatBridge.configure`).
     */
    internal fun appContextForBridges(): Context? = appContext

    /**
     * SPEC-070-B W13 — which subsystems `configure()` actually brought up. The isolation test reads
     * it to assert that a failing subsystem takes ONLY itself down: the ones after it in
     * `configure()` still exist, and so does the event pipeline.
     */
    internal fun subsystemsUp(): Map<String, Boolean> = mapOf(
        "paywall" to (paywallManager != null),
        "onboarding" to (onboardingFlowManager != null),
        "surveys" to (surveyManager != null),
        "web_entitlements" to (webEntitlementManager != null),
        "in_app_messages" to (messageManager != null),
        "sessions" to (sessionManager != null),
        "events" to (eventTracker != null),
    )

    /**
     * Shut down the SDK and release resources. Call from Application.onTerminate().
     *
     * SPEC-070-A H.24 — release ALL native handles + cancel ALL coroutine
     * scopes. Previous implementation cancelled `scope` but leaked
     * `nativeBillingManager`'s BillingClient connection, the event SQLite
     * handle, the survey/messages/push manager scopes, and the periodic
     * config-refresh worker.
     */
    fun shutdown() {
        synchronized(this) {
            eventQueue?.shutdown()
            // SPEC-067: Schedule background upload for remaining events
            val ctx = appContext
            val key = apiKey
            val db = eventDatabase
            if (ctx != null && key != null && db != null) {
                // 🔴 SPEC-070-B W13 — this was UNGUARDED, while the identical call in `configure()`
                // (the `bgScheduler` lambda) is wrapped. `WorkManager.getInstance()` THROWS
                // `IllegalStateException` in any app that disables `WorkManagerInitializer` — a
                // supported, documented configuration — so `AppDNA.shutdown()` threw into the host,
                // and returned before resetting `isConfigured`/`isConfiguring`. The SDK was then
                // wedged: the re-entrancy guard rejected every subsequent `configure()`.
                try {
                    ai.appdna.sdk.background.EventUploadWorker.scheduleIfNeeded(
                        ctx, key, environment.baseUrl, db
                    )
                } catch (e: Throwable) {
                    Log.warning("Background upload schedule on shutdown failed: ${e.message}")
                }
            }
            connectivityMonitor?.shutdown()
            webEntitlementManager?.stopObserving()
            pendingMessageListener?.stopObserving()

            // SPEC-070-A H.24: cancel periodic config-refresh worker.
            try {
                if (ctx != null) ai.appdna.sdk.background.ConfigRefreshWorker.cancel(ctx)
            } catch (_: Throwable) { /* WorkManager may not be initialized */ }

            // SPEC-070-A H.24: release the native billing handles. The wrapper
            // [BillingModule] does NOT itself own a BillingClient — its scope
            // is independent of the underlying [NativeBillingManager], so we
            // cancel both.
            try {
                billing.manager?.destroy()
            } catch (e: Throwable) {
                Log.warning("NativeBillingManager.destroy threw: ${e.message}")
            } finally {
                // Must null the handle, not just destroy it. `initBillingModuleIfNeeded`
                // early-returns on a non-null manager, so leaving a destroyed instance
                // here makes a later configure() skip re-init — killing billing and
                // entitlements for the rest of the process.
                billing.manager = null
            }
            try {
                billing.shutdown()
            } catch (e: Throwable) {
                Log.warning("BillingModule.shutdown threw: ${e.message}")
            }

            // SPEC-070-A H.24: cancel survey/push token scopes so background
            // coroutines stop emitting events after shutdown.
            try { surveyManager?.shutdown() } catch (_: Throwable) {}
            try { pushTokenManager?.shutdown() } catch (_: Throwable) {}

            // SPEC-070-A audit Round 2 finding 6: cancel paywall + deferred
            // deep-link scopes so they don't outlive shutdown.
            try { paywallManager?.shutdown() } catch (_: Throwable) {}
            try { deferredDeepLinkManager?.shutdown() } catch (_: Throwable) {}

            // SPEC-070-A final audit pass B F1 — release the
            // ProcessLifecycleOwner observer SessionManager registers in
            // its constructor (SessionManager.kt:117). Without this the
            // observer survives shutdown because ProcessLifecycleOwner is
            // a process-scoped singleton holding a strong ref. iOS ARC
            // handles this via `sessionManager = nil`; Android needs the
            // explicit `lifecycle.removeObserver(...)` call inside stop().
            try { sessionManager?.stop() } catch (_: Throwable) {}

            // SPEC-070-A final audit pass C F2 — drain MessageManager's
            // mainHandler so a `delay_seconds`-postponed Dialog
            // presentation can't fire on a stale Activity ref after the
            // SDK has been told to shut down.
            try { messageManager?.shutdown() } catch (_: Throwable) {}

            // SPEC-070-B PN row 7: cancel the configTTL refresh timer, or it fires against a
            // torn-down Firestore handle after shutdown.
            try { remoteConfigManager?.shutdown() } catch (_: Throwable) {}

            // SPEC-070-A H.24: close the SQLite handle. EventDatabase extends
            // SQLiteOpenHelper, so close() releases the underlying db file
            // without losing pending rows (they live on disk until uploaded).
            try { eventDatabase?.close() } catch (_: Throwable) {}

            scope.cancel()

            // SPEC-070-A final audit pass H F1 — null out manager + cache
            // refs so a subsequent `configure()` doesn't leak the prior
            // EventTracker/SessionManager/etc. iOS does the equivalent via
            // ARC (`shared.eventTracker = nil` etc. at AppDNA.swift:1126).
            eventQueue = null
            eventTracker = null
            eventDatabase = null
            connectivityMonitor = null
            apiClient = null
            identityManager = null
            sessionManager = null
            pushTokenManager = null
            paywallManager = null
            onboardingFlowManager = null
            surveyManager = null
            messageManager = null
            remoteConfigManager = null
            featureFlagManager = null
            experimentManager = null
            webEntitlementManager = null
            pendingMessageListener = null
            deferredDeepLinkManager = null
            firestoreDB = null
            bootstrapOrgId = null
            bootstrapAppId = null
            apiKey = null
            adaptyBridge = null

            // Round-10 #9 — the PUBLIC module facades (`AppDNA.onboarding`/`paywall`/`remoteConfig`/
            // `features`/`surveys`/`experiments`/`push`) hold their OWN strong `.manager` ref, assigned
            // in configure(). Nulling the private `*Manager` fields above does NOT clear these, so after
            // shutdown `AppDNA.surveys.present(id)` / `remoteConfig.get()` / `features.isEnabled()` /
            // `experiments.getVariant()` still ran against a torn-down instance and kept EventTracker/
            // ApiClient/Firestore pinned. iOS holds these `weak`, so nilling `shared.*` makes the facades
            // inert automatically; Android must clear them explicitly, exactly like `billing.manager` above.
            onboarding.manager = null
            paywall.manager = null
            remoteConfig.manager = null
            features.manager = null
            surveys.manager = null
            experiments.manager = null
            push.manager = null

            // SPEC-070-B PN row 10: these three are OBJECT-scoped statics, so nulling the managers
            // above leaves them holding the previous run's values. A re-configure()d SDK then renders
            // with a stale brand color, attributes its first events to the old screen, and can present
            // a message from a config that is no longer live.
            brandAccentHex = null
            lastScreenName = null
            activeMessages = emptyMap()
            lastInitError = null

            // SPEC-070-A final audit pass H F1 — release the registered
            // NavigationInterceptor lifecycle callbacks so a re-`configure()`
            // doesn't double-fire screen events. Mirrors iOS dealloc.
            try {
                val ctx = appContext
                val cbs = navigationInterceptorCallbacks
                if (ctx != null && cbs != null) {
                    (ctx as? android.app.Application)?.unregisterActivityLifecycleCallbacks(cbs)
                }
            } catch (_: Throwable) {}
            navigationInterceptorCallbacks = null

            // SPEC-070-A finalization R3 P0 (Lens D) — drop pending Activity
            // launch slots so SDK shutdown doesn't leak ScreenConfig /
            // PaywallConfig / SurveyConfig + closure captures forever.
            try { ai.appdna.sdk.screens.ScreenHostActivity.clearActiveLaunches() } catch (_: Throwable) {}
            try { ai.appdna.sdk.paywalls.PaywallActivity.clearActiveLaunches() } catch (_: Throwable) {}
            try { ai.appdna.sdk.feedback.SurveyActivity.clearActiveLaunches() } catch (_: Throwable) {}
            // SPEC-401-A R73 (Lens B P1) — drop pending OnboardingActivity
            // launch payload too. Was leaking OnboardingFlowConfig + delegate
            // + EventTracker + 5 lambda closures across shutdown→configure;
            // also a race where presentOnboarding()+shutdown() before
            // ActivityManager dispatched onCreate left activity launching
            // with stale (nulled-queue) EventTracker.
            try { ai.appdna.sdk.onboarding.OnboardingActivity.clearActiveLaunches() } catch (_: Throwable) {}

            // SPEC-070-A finalization R3 P0 (Lens D) — drop the pre-configure
            // FCM token buffer too so a re-configure() doesn't replay a stale
            // token that the host already discarded.
            pendingPushTokenBeforeConfigure = null

            appContext = null

            // SPEC-070-A final audit pass H F2 — reassign `scope` so a
            // future `configure()` lands on a live CoroutineScope. Without
            // this, the second performBootstrap{} dispatches onto the
            // cancelled scope and never fires.
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

            isConfigured = false
            isConfiguring = false
            Log.info("SDK shut down")
        }
    }
}

/**
 * The canonical name of the identity-transition event. A constant rather than a literal at the
 * single call site because the cross-platform behavioral fixtures assert this name — a rename must
 * break the fixture, not slip through.
 */
internal const val IDENTIFY_EVENT = "identify"

/**
 * The `identify` event's property shape, lifted out of [AppDNA.identify].
 *
 * WHY: the property NAMES are the contract (BigQuery alias resolution reads them, and the shared
 * fixtures pin them cross-platform), but they were built inline inside a function that first needs a
 * configured SDK — network, Firestore, billing — so nothing could assert the shape without booting
 * all of it. Verbatim move; `identify()` still emits it. Mirrors iOS AppDNA.swift:386-395.
 */
internal fun buildIdentifyProps(
    userId: String,
    previousAnonId: String?,
    previousUserId: String?,
    traits: Map<String, Any>?,
): Map<String, Any> {
    val props = mutableMapOf<String, Any>(
        "user_id" to userId,
        "anon_id" to (previousAnonId ?: ""),
    )
    if (previousUserId != null && previousUserId != userId) {
        props["previous_user_id"] = previousUserId
    }
    if (traits != null) {
        props["traits"] = traits
    }
    return props
}
