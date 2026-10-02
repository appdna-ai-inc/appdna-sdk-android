package ai.appdna.sdk.config

import ai.appdna.sdk.Log
import ai.appdna.sdk.events.EventTracker
import ai.appdna.sdk.onboarding.OnboardingConfigParser
import ai.appdna.sdk.onboarding.OnboardingFlowConfig
import ai.appdna.sdk.paywalls.PaywallConfig
import ai.appdna.sdk.paywalls.PaywallConfigParser
import ai.appdna.sdk.storage.LocalStorage
import ai.appdna.sdk.AppDNA
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Manages remote config from Firestore with local caching.
 */
internal class RemoteConfigManager(
    firestorePath: String?,
    private val storage: LocalStorage,
    private val configTTL: Long,
    /**
     * SPEC-070-B PN row 7 — injectable so a Robolectric test can advance the TTL timer. Robolectric
     * cannot advance `kotlinx.coroutines.delay` on a production dispatcher, which would make the
     * staleness gate untestable on Android.
     */
    private val ttlDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    // SPEC-070-B PN row 7 (§6 row 7): the staleness gate lives HERE, not in ConfigRefreshWorker —
    // WorkManager has a 15-minute floor, so a host asking for a 5-minute TTL silently got 15.
    // `configTTL` was previously accepted and never read: dead code on Android while iOS honored it.
    private val ttlScope = CoroutineScope(ttlDispatcher + SupervisorJob())

    @Volatile
    private var lastFetchAtMs: Long = 0L

    @Volatile
    private var ttlJob: Job? = null

    /** True when no fetch has completed, or the last one is older than [configTTL] seconds. */
    internal fun isStale(nowMs: Long = System.currentTimeMillis()): Boolean =
        lastFetchAtMs == 0L || nowMs - lastFetchAtMs >= configTTL * 1000L

    /** Cancel the pending TTL refresh. Called from `AppDNA.shutdown()`. */
    fun shutdown() {
        ttlJob = null
        runCatching { ttlScope.cancel() }
    }
    // Mutable so AppDNA.performBootstrap can supply the path returned by
    // /api/v1/sdk/bootstrap. Without this setter the manager forever falls
    // through to "No Firestore path available — serving cached config only"
    // because the path is null at construction.
    @Volatile
    private var firestorePath: String? = firestorePath

    fun setFirestorePath(path: String) {
        this.firestorePath = path
    }

    /**
     * SPEC-070-A G.4: Optional event tracker so [fetchConfigs] can emit
     * `config_fetched` after each successful Firestore refresh. Wired by
     * AppDNA.configure() once both managers are constructed; until then we
     * simply skip the event (no NPE risk).
     */
    @Volatile
    var eventTracker: EventTracker? = null

    /**
     * SPEC-070-A G.4: Tracks how many of the 6 Firestore documents in a
     * fetchConfigs() batch have completed (success OR failure). When the count
     * reaches the expected total, we emit a single `config_fetched` event with
     * the count of docs that succeeded — mirrors the iOS one-shot semantic
     * (Sources/AppDNASDK/Config/RemoteConfigManager.swift `configFetchCompleted`).
     */
    private val fetchCompletionCounter = AtomicInteger(0)
    private val fetchSuccessCounter = AtomicInteger(0)
    private val fetchExpectedTotal = AtomicInteger(0)
    // @Volatile: written on the Firestore listener thread (per-item + mega-doc parse), read cross-thread
    // by getConfig/isEnabled from the host present-time thread — publish writes (parity w/ experiments,
    // variantDocs). Same for `messages` below (read by getActiveMessages).
    @Volatile private var flags: Map<String, Any> = emptyMap()
    // @Volatile: read by resolveSurfacePresentation from the host present-time thread, written on the
    // Firestore listener thread — publish writes so the resolver never sees a stale/empty map (audit R1).
    @Volatile private var experiments: Map<String, ExperimentConfig> = emptyMap()
    // SPEC-036-H — prefetched per-item experiment variant configs, keyed by the variant's `variantDoc`
    // path; populated after the experiments doc parses so resolveSurfacePresentation reads synchronously.
    @Volatile private var variantDocs: Map<String, Map<String, Any>> = emptyMap()
    private var surveys: Map<String, Map<String, Any>> = emptyMap()
    private var paywalls: Map<String, PaywallConfig> = emptyMap()
    private var onboardingFlows: Map<String, OnboardingFlowConfig> = emptyMap()
    private var activeOnboardingFlowId: String? = null
    /**
     * SPEC-070-A finalization parity audit B1#6 — Firestore-published
     * in-app messages map. Mirrors iOS RemoteConfigManager.swift:56
     * `private var messages: [String: MessageConfig]`. Populated by
     * the new messages-doc fetch in [fetchConfigs]; consumed by
     * [getActiveMessages] which is wired into MessageManager via
     * AppDNA.configure() so push-delivered AND Firestore-broadcast
     * messages both reach the renderer.
     */
    @Volatile private var messages: Map<String, ai.appdna.sdk.messages.MessageConfig> = emptyMap()

    /** Callback when survey configs are updated from Firestore. */
    var surveyUpdateHandler: ((Map<String, Map<String, Any>>) -> Unit)? = null

    init {
        loadCachedConfigs()
    }

    private val changeListeners = CopyOnWriteArrayList<(Map<String, Any>) -> Unit>()

    fun getConfig(key: String): Any? = flags[key]

    fun getAllConfig(): Map<String, Any> = flags

    fun addChangeListener(callback: (Map<String, Any>) -> Unit) {
        changeListeners.add(callback)
    }

    fun getExperimentConfig(id: String): ExperimentConfig? = experiments[id]

    /**
     * SPEC-036-H — the prefetched `config` of a per-item experiment variant doc, by its `variantDoc`
     * pointer path. `null` if not yet fetched / fetch failed → caller renders the active item (never
     * cross-cohort, never broken). Test-injectable via [injectVariantDocForTesting].
     */
    fun getVariantDoc(path: String): Map<String, Any>? = variantDocs[path]

    fun getAllExperiments(): Map<String, ExperimentConfig> = experiments

    fun getSurveyConfigs(): Map<String, Map<String, Any>> = surveys

    fun getPaywallConfig(id: String): PaywallConfig? = paywalls[id]

    /**
     * SPEC-070-A I.13 — return every paywall config currently held in remote
     * config, keyed by id. Used by [PaywallManager.presentByPlacement] to
     * filter by `placement` + audience rules. Mirrors iOS
     * `RemoteConfigManager.getAllPaywalls()`.
     */
    fun getAllPaywalls(): Map<String, PaywallConfig> = paywalls

    /**
     * Get an onboarding flow by ID, or the active flow if id is null.
     */
    fun getOnboardingFlow(id: String?): OnboardingFlowConfig? {
        if (id != null) {
            return onboardingFlows[id]
        }
        val activeId = activeOnboardingFlowId ?: return null
        return onboardingFlows[activeId]
    }

    /**
     * SPEC-419 D6 — the applied (fetched + parsed) onboarding flow version, for the
     * structural parity harness's readiness poll. Debug only — R8 elides the body in
     * release builds (BuildConfig.DEBUG = false), so it returns null in production.
     */
    fun debugAppliedOnboardingVersion(flowId: String?): Int? {
        if (!ai.appdna.sdk.BuildConfig.DEBUG) return null
        val id = flowId ?: activeOnboardingFlowId ?: return null
        return onboardingFlows[id]?.version
    }

    /**
     * SPEC-070-A F.13: read-only view of every parsed onboarding flow keyed by id.
     * Used by [ai.appdna.sdk.onboarding.OnboardingFlowManager.present] to evaluate
     * `audience_rules` across all flows when no explicit flowId is supplied —
     * mirrors iOS `OnboardingFlowManager.swift:121-137`.
     */
    fun getAllOnboardingFlows(): Map<String, OnboardingFlowConfig> = onboardingFlows

    /**
     * SPEC-067: Force an immediate config refresh, bypassing the cache TTL.
     * Use this when you need configs to update immediately (e.g., after a user action).
     */
    fun forceRefresh() {
        Log.info("Force refreshing remote config (bypassing TTL)")
        fetchConfigs()
    }

    fun fetchConfigs() {
        val path = firestorePath ?: run {
            Log.warning("No Firestore path available — serving cached config only")
            return
        }

        val db = AppDNA.firestoreDB ?: run {
            Log.warning("Firestore not available — serving cached config only")
            return
        }
        val basePath = "$path/config"

        // SPEC-070-A G.4: 7 documents — flags, experiments, paywall_index,
        // onboarding_index, survey_index, screen_index, messages.
        // SPEC-070-A finalization parity audit B1#6 — added messages doc
        // fetch (iOS RemoteConfigManager.swift:305 fetches `messages` mega-doc;
        // Android previously dropped this doc, leaving activeMessages
        // perpetually empty so Firestore-broadcast in-app messages never
        // displayed). Re-init counters whenever a new fetch begins.
        val expected = 7
        fetchCompletionCounter.set(0)
        fetchSuccessCounter.set(0)
        fetchExpectedTotal.set(expected)

        // Flags — SPEC-036-H: index → per-item docs (config/flag_index/flags/{key}), fallback → mega-doc.
        fetchViaIndex(
            db = db, basePath = basePath,
            indexPath = "flag_index", indexKey = "flags",
            itemCollection = "flag_index/flags", megaDocPath = "flags",
            parseItem = { key, data -> parseSingleFlag(key, data) },
            parseMegaDoc = { data ->
                @Suppress("UNCHECKED_CAST")
                val unwrapped = (data["flags"] as? Map<String, Any>) ?: data
                // Normalize each entry to its RAW value (FeatureFlagManager/getConfig expect Bool/Number/
                // String, not the served {value,type,...} wrapper); omit null-valued (unset) flags.
                flags = unwrapped.entries.mapNotNull { e -> flagRawValue(e.value)?.let { e.key to it } }.toMap()
                cacheData("flags", JSONObject(flags as Map<*, *>).toString())
                notifyChangeListeners()
            },
            pruneToKeys = { keys ->
                flags = flags.filterKeys { it in keys }
                // Rewrite the disk cache to match (clears it when keys is empty — else a cold start
                // resurrects removed flags). Per-item adds re-cache the full set afterward.
                try { cacheData("flags", JSONObject(flags as Map<*, *>).toString()) } catch (_: Exception) {}
                notifyChangeListeners()  // fire on full-clear too (no per-item parse runs when empty)
            },
            onComplete = { ok -> markFetchComplete(ok) }
        )

        // Fetch experiments. SPEC-036-H: after parsing, prefetch any per-item variant docs referenced
        // via `variant_doc` so synchronous presentation resolution can read the treatment config. The
        // prefetch is fire-and-forget (NOT tied to the fixed-count fetch barrier); a not-yet-fetched
        // variant doc degrades to RenderActive, matching the failure-degradation contract.
        db.document("$basePath/experiments").get().addOnSuccessListener { snapshot ->
            snapshot.data?.let { data ->
                parseExperiments(data)
                cacheData("experiments", JSONObject(data).toString())
                prefetchVariantDocs(db)
            }
            markFetchComplete(success = true)
        }.addOnFailureListener { e ->
            Log.error("Failed to fetch experiments: ${e.message}")
            markFetchComplete(success = false)
        }

        // Paywalls: prefer per-item docs via index → fallback to mega-doc
        fetchViaIndex(
            db = db, basePath = basePath,
            indexPath = "paywall_index", indexKey = "paywalls",
            itemCollection = "paywall_index/paywalls", megaDocPath = "paywalls",
            parseItem = { id, data -> parseSinglePaywall(id, data) },
            parseMegaDoc = { data -> parsePaywalls(data); cacheData("paywalls", JSONObject(data).toString()) },
            onComplete = { ok -> markFetchComplete(ok) }
        )

        // Onboarding: prefer per-item docs via index → fallback to mega-doc
        fetchViaIndex(
            db = db, basePath = basePath,
            indexPath = "onboarding_index", indexKey = "flows",
            itemCollection = "onboarding_index/flows", megaDocPath = "onboarding",
            parseItem = { id, data -> parseSingleOnboardingFlow(id, data) },
            parseMegaDoc = { data -> parseOnboarding(data); cacheData("onboarding", JSONObject(data).toString()) },
            extraIndexParse = { indexData ->
                (indexData["active_flow_id"] as? String)?.let { activeOnboardingFlowId = it }
            },
            onComplete = { ok -> markFetchComplete(ok) }
        )

        // Surveys: prefer per-item docs via index → fallback to mega-doc
        fetchViaIndex(
            db = db, basePath = basePath,
            indexPath = "survey_index", indexKey = "surveys",
            itemCollection = "survey_index/surveys", megaDocPath = "surveys",
            parseItem = { id, data -> parseSingleSurvey(id, data) },
            parseMegaDoc = { data -> parseSurveys(data); cacheData("surveys", JSONObject(data).toString()) },
            onComplete = { ok -> markFetchComplete(ok) }
        )

        // SPEC-089c: Fetch screen_index for server-driven UI
        db.document("$basePath/screen_index").get().addOnSuccessListener { snapshot ->
            snapshot.data?.let { data ->
                parseScreenIndex(data)
            }
            markFetchComplete(success = true)
        }.addOnFailureListener { e ->
            Log.debug("No screen_index config: ${e.message}")
            markFetchComplete(success = false)
        }

        // SPEC-419 brand-threading: app brand palette → AppDNA.brandAccentColor() default.
        // Fire-and-forget (non-critical default that just overrides #6366F1); not counted in
        // the fetch barrier — a config-updated re-render picks up the accent if it lands late.
        db.document("$basePath/brand").get().addOnSuccessListener { snapshot ->
            parseBrand(snapshot.data)
        }.addOnFailureListener { e ->
            Log.debug("No brand config: ${e.message}")
        }

        // SPEC-070-A finalization parity audit B1#6 — fetch in-app messages
        // mega-doc. Mirrors iOS RemoteConfigManager.swift:305 which fetches
        // `$basePath/messages` and parses into a `[String: MessageConfig]`
        // map exposed via `getActiveMessages()`. Without this fetch the
        // Android `activeMessages` map stayed empty and Firestore-published
        // in-app messages never displayed (only push-delivered ones did).
        // In-app messages — SPEC-036-H: index → per-item docs (config/message_index/messages/{id}),
        // fallback → mega-doc.
        fetchViaIndex(
            db = db, basePath = basePath,
            indexPath = "message_index", indexKey = "messages",
            itemCollection = "message_index/messages", megaDocPath = "messages",
            parseItem = { id, data -> parseSingleMessage(id, data) },
            parseMegaDoc = { data ->
                @Suppress("UNCHECKED_CAST")
                messages = ai.appdna.sdk.messages.MessageConfigParser.parseMessages(data as Map<String, Any>)
                cacheData("messages", JSONObject(data).toString())
                notifyChangeListeners()
            },
            pruneToKeys = { keys ->
                messages = messages.filterKeys { it in keys }
                rawMessageData.keys.retainAll(keys)
                // Rewrite the disk cache (clears to {messages:{}} when empty — else cold start resurrects).
                try {
                    val combined = JSONObject(); val msgObj = JSONObject()
                    for ((mid, mdata) in rawMessageData) { msgObj.put(mid, JSONObject(mdata)) }
                    combined.put("messages", msgObj)
                    cacheData("messages", combined.toString())
                } catch (_: Exception) {}
                notifyChangeListeners()  // fire on full-clear too (no per-item parse runs when empty)
            },
            onComplete = { ok -> markFetchComplete(ok) }
        )

        Log.info("Fetching remote configs from Firestore")
    }

    /**
     * SPEC-070-A finalization parity audit B1#6 — public accessor for
     * Firestore-published in-app messages. Mirrors iOS
     * RemoteConfigManager.swift:177 `getActiveMessages()`. Wired into
     * MessageManager.configProvider via AppDNA.configure() so Firestore
     * broadcasts and push deliveries both render.
     */
    fun getActiveMessages(): Map<String, ai.appdna.sdk.messages.MessageConfig> = messages

    /**
     * SPEC-070-A G.4: Increment the per-fetch completion counter and emit
     * `config_fetched` exactly once when every doc in the batch has either
     * succeeded or failed. The event payload mirrors iOS shape:
     *   - success_count: int (docs with non-null Firestore snapshot)
     *   - total_count:   int (docs the SDK attempted to fetch)
     */
    private fun markFetchComplete(success: Boolean) {
        if (success) fetchSuccessCounter.incrementAndGet()
        val done = fetchCompletionCounter.incrementAndGet()
        val expected = fetchExpectedTotal.get()
        if (expected > 0 && done >= expected) {
            try {
                // Round-21 F1(non-render) — emit `config_fetched` WITHOUT properties, matching iOS
                // (RemoteConfigManager.swift emits `properties: nil`). Android previously attached
                // {success_count,total_count}; iOS attached none, so the same event had a divergent
                // property shape across platforms (iOS rows had empty properties). The counters are still
                // used above to fire this exactly once when the batch completes — they just aren't
                // emitted. (Partial-fetch telemetry can be re-added to BOTH platforms later, with matching
                // count semantics + a shared fixture, as a proper feature.)
                eventTracker?.track("config_fetched", emptyMap())
            } catch (e: Exception) {
                Log.warning { "config_fetched event emit failed: ${e.message}" }
            }
            // Reset so a subsequent forceRefresh() call doesn't double-fire.
            fetchExpectedTotal.set(0)

            // SPEC-070-B PN row 7: mark the batch fetched and arm the TTL refresh, mirroring iOS
            // (`RemoteConfigManager.swift:463`). Replacing the job means a forceRefresh() re-arms
            // rather than stacking timers.
            lastFetchAtMs = System.currentTimeMillis()
            ttlJob?.let { runCatching { it.cancel() } }
            ttlJob = ttlScope.launch {
                delay(configTTL * 1000L)
                if (isStale()) fetchConfigs()
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseExperiments(data: Map<String, Any>) {
        // Unwrap "experiments" wrapper if present
        val experimentsMap = (data["experiments"] as? Map<String, Any>) ?: data
        val parsed = mutableMapOf<String, ExperimentConfig>()
        for ((key, value) in experimentsMap) {
            if (value is Map<*, *>) {
                @Suppress("UNCHECKED_CAST")
                val map = value as Map<String, Any>
                try {
                    val variants = (map["variants"] as? List<*>)?.mapNotNull { v ->
                        if (v is Map<*, *>) {
                            @Suppress("UNCHECKED_CAST")
                            val vm = v as Map<String, Any>
                            // SPEC-070-A F.10: iOS canonical key is `payload`; legacy SDKs wrote
                            // `config`. Accept both — prefer `payload` when present.
                            @Suppress("UNCHECKED_CAST")
                            val variantData =
                                (vm["payload"] as? Map<String, Any>)
                                    ?: (vm["config"] as? Map<String, Any>)
                                    ?: emptyMap()
                            ExperimentVariant(
                                // Round-35 — retain variants with a missing id/weight (default
                                // "unknown"/0.0) instead of dropping them, matching iOS's optional
                                // decode. Dropping diverged the variant LIST, so the same user could
                                // bucket into a DIFFERENT variant per platform (corrupting experiment
                                // analytics). Well-formed console configs always carry id + weight.
                                id = vm["id"] as? String ?: "unknown",
                                weight = (vm["weight"] as? Number)?.toDouble() ?: 0.0,
                                config = variantData,
                                // SPEC-036-F §1.2 — served per-variant fields.
                                configRef = vm["config_ref"] as? String,
                                isControl = vm["is_control"] as? Boolean,
                                // SPEC-036-H — per_item variant-doc pointer.
                                variantDoc = vm["variant_doc"] as? String,
                            )
                        } else null
                    } ?: emptyList()

                    // Legacy top-level key nothing writes; kept so an old cached doc still parses. Not evaluated.
                    val segments = (map["segments"] as? List<*>)?.filterIsInstance<String>()

                    parsed[key] = ExperimentConfig(
                        id = map["id"] as? String ?: key,
                        name = map["name"] as? String ?: "",
                        status = map["status"] as? String ?: "paused",
                        // SPEC-036-F §1.2 — served surface type for experiment matching.
                        type = map["type"] as? String,
                        salt = map["salt"] as? String ?: "",
                        platforms = (map["platforms"] as? List<*>)?.filterIsInstance<String>() ?: listOf("android"),
                        variants = variants,
                        segments = segments,
                        // The audience gate the device evaluates (ExperimentEligibility), as served.
                        trafficAllocation = (map["traffic_allocation"] as? Number)?.toDouble(),
                        targeting = ExperimentTargeting.fromAny(map["targeting"]),
                        startedAtMs = (map["started_at_ms"] as? Number)?.toLong(),
                    )
                } catch (e: Exception) {
                    Log.warning("Failed to parse experiment '$key': ${e.message}")
                }
            }
        }
        experiments = parsed
    }

    /**
     * SPEC-036-H — fetch each `variantDoc`-referenced per-item variant doc by its EXACT path (never via
     * an index — that is the cohort-isolation guarantee) and cache its `config`. Fire-and-forget: a doc
     * still in flight resolves to RenderActive (failure degradation). Only runs for `per_item`-mode docs;
     * `inline`-mode experiments carry no `variantDoc`.
     */
    private fun prefetchVariantDocs(db: com.google.firebase.firestore.FirebaseFirestore) {
        val paths = experiments.values
            .flatMap { it.variants }
            .mapNotNull { it.variantDoc }
            .toSet()
        // Prune cached variant docs no longer referenced by any current experiment so the cache can't
        // grow unbounded across TTL refetches; new/changed paths are fetched below.
        variantDocs = variantDocs.filterKeys { it in paths }
        for (path in paths) {
            db.document(path).get().addOnSuccessListener { snap ->
                @Suppress("UNCHECKED_CAST")
                val config = snap.data?.get("config") as? Map<String, Any>
                if (config != null) {
                    variantDocs = variantDocs + (path to config)
                }
            }.addOnFailureListener { e ->
                Log.error("Failed to fetch variant doc '$path': ${e.message}")
            }
        }
    }

    /** SPEC-036-H test seam — inject a prefetched variant doc config by its pointer path. */
    internal fun injectVariantDocForTesting(path: String, config: Map<String, Any>) {
        variantDocs = variantDocs + (path to config)
    }

    /** SPEC-036-H test seams — drive the real per-item flag/message parsers + prune. */
    internal fun parseFlagDocForTesting(key: String, data: Map<String, Any>) = parseSingleFlag(key, data)
    internal fun parseMessageDocForTesting(id: String, data: Map<String, Any>) = parseSingleMessage(id, data)
    internal fun pruneFlagsForTesting(keys: Set<String>) { flags = flags.filterKeys { it in keys } }
    internal fun pruneMessagesForTesting(keys: Set<String>) { messages = messages.filterKeys { it in keys } }
    internal fun messagesForTesting(): Map<String, ai.appdna.sdk.messages.MessageConfig> = messages

    @Suppress("UNCHECKED_CAST")
    private fun parseSurveys(data: Map<String, Any>) {
        val surveysMap = data["surveys"] as? Map<String, Any> ?: data
        val parsed = mutableMapOf<String, Map<String, Any>>()
        for ((key, value) in surveysMap) {
            if (value is Map<*, *>) {
                parsed[key] = value as Map<String, Any>
            }
        }
        surveys = parsed
        Log.debug("Parsed ${parsed.size} survey configs")
        surveyUpdateHandler?.invoke(parsed)
    }

    @Suppress("UNCHECKED_CAST")
    private fun parsePaywalls(data: Map<String, Any>) {
        paywalls = PaywallConfigParser.parsePaywalls(data)
        Log.debug("Parsed ${paywalls.size} paywall configs")
    }

    private fun parseOnboarding(data: Map<String, Any>) {
        val (activeId, flows) = OnboardingConfigParser.parseOnboardingRoot(data)
        onboardingFlows = flows
        activeOnboardingFlowId = activeId
        Log.debug("Parsed ${flows.size} onboarding flows, active=$activeId")
    }

    /**
     * SPEC-419 brand-threading — capture the app's brand accent so SDK render
     * defaults use it instead of the hardcoded #6366F1. Doc shape:
     * `{ palette: { accent, primary, ... } }`.
     */
    private fun parseBrand(data: Map<String, Any>?) {
        val palette = data?.get("palette") as? Map<*, *> ?: return
        (palette["accent"] as? String)?.takeIf { it.isNotBlank() }?.let {
            AppDNA.brandAccentHex = it
            Log.debug("Loaded brand accent $it")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseScreenIndex(data: Map<String, Any>) {
        try {
            val index = ai.appdna.sdk.screens.ScreenIndex.fromMap(data)
            ai.appdna.sdk.screens.ScreenManager.shared.updateIndex(index)
            Log.info("Screen index loaded: ${index.screens?.size ?: 0} screens, ${index.flows?.size ?: 0} flows, ${index.slots?.size ?: 0} slots")
        } catch (e: Exception) {
            Log.error("Failed to parse screen_index: ${e.message}")
        }
    }

    // MARK: - Per-item fetch via index (enterprise-grade)

    /**
     * Generic helper: try to load items via a lightweight index document.
     * If the index exists, fan out to individual per-item documents.
     * If no index, fall back to the legacy mega-document.
     */
    @Suppress("UNCHECKED_CAST")
    private fun fetchViaIndex(
        db: com.google.firebase.firestore.FirebaseFirestore,
        basePath: String,
        indexPath: String,
        indexKey: String,
        itemCollection: String,
        megaDocPath: String,
        parseItem: (String, Map<String, Any>) -> Unit,
        parseMegaDoc: (Map<String, Any>) -> Unit,
        extraIndexParse: ((Map<String, Any>) -> Unit)? = null,
        // SPEC-036-H — when provided, the index is AUTHORITATIVE: in-memory entries whose key is not in
        // the current index are pruned (removed item stops serving), and an EMPTY index takes the index
        // branch (prune-to-empty) instead of the mega-doc. Flags + messages pass this to keep the
        // full-replace removal semantics they had via the mega-doc.
        pruneToKeys: ((Set<String>) -> Unit)? = null,
        // SPEC-070-A G.4: invoked once when this index/mega-doc fetch path
        // resolves — used by the parent fetchConfigs() to count down toward
        // the single `config_fetched` event.
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        db.document("$basePath/$indexPath").get()
            .addOnSuccessListener { snapshot ->
                val indexData = snapshot.data
                val itemsMap = indexData?.get(indexKey) as? Map<String, Any>
                if (indexData != null && (!itemsMap.isNullOrEmpty() || (itemsMap != null && pruneToKeys != null))) {
                    // Index exists — prune stale in-memory entries, then fetch individual docs. onComplete
                    // fires only AFTER every item resolves (Tasks.whenAllComplete) so `config_fetched`
                    // doesn't fire prematurely; an empty index → 0 tasks → completes immediately.
                    Log.debug("[$indexPath] Found index with ${itemsMap.size} items, fetching individually")
                    extraIndexParse?.invoke(indexData)
                    pruneToKeys?.invoke(itemsMap.keys.toSet())
                    val itemTasks = itemsMap.keys.map { itemId ->
                        db.document("$basePath/$itemCollection/$itemId").get()
                            .addOnSuccessListener { itemSnapshot ->
                                itemSnapshot.data?.let { itemData -> parseItem(itemId, itemData) }
                            }
                            .addOnFailureListener { e ->
                                Log.warning("[$indexPath] Failed to fetch item $itemId: ${e.message}")
                            }
                    }
                    com.google.android.gms.tasks.Tasks.whenAllComplete(itemTasks)
                        .addOnCompleteListener { onComplete?.invoke(true) }
                } else {
                    // No index — fall back to legacy mega-doc
                    Log.debug("[$indexPath] No index found, falling back to mega-doc $megaDocPath")
                    db.document("$basePath/$megaDocPath").get()
                        .addOnSuccessListener { megaSnapshot ->
                            megaSnapshot.data?.let { data -> parseMegaDoc(data) }
                            onComplete?.invoke(true)
                        }
                        .addOnFailureListener { e ->
                            Log.error("Failed to fetch $megaDocPath config: ${e.message}")
                            onComplete?.invoke(false)
                        }
                }
            }
            .addOnFailureListener { e ->
                // Index fetch failed — try mega-doc as fallback
                Log.debug("[$indexPath] Index fetch failed (${e.message}), trying mega-doc")
                db.document("$basePath/$megaDocPath").get()
                    .addOnSuccessListener { megaSnapshot ->
                        megaSnapshot.data?.let { data -> parseMegaDoc(data) }
                        onComplete?.invoke(true)
                    }
                    .addOnFailureListener { e2 ->
                        Log.error("Failed to fetch $megaDocPath config: ${e2.message}")
                        onComplete?.invoke(false)
                    }
            }
    }

    // Raw data accumulators for disk cache rebuild during per-item fetches.
    // When using index-based fetch, individual items bypass the mega-doc path
    // that normally handles caching. These track raw Firestore data so we can
    // rebuild the cache after each item is fetched.
    private val rawPaywallData = mutableMapOf<String, Map<String, Any>>()
    private val rawOnboardingData = mutableMapOf<String, Map<String, Any>>()
    private val rawMessageData = mutableMapOf<String, Map<String, Any>>()

    // SPEC-036-H — per-item flag doc config/flag_index/flags/{key} = {key,value,type,description,updated_at}.
    // Stored under flags[key] with the SAME shape the mega-doc produced; disk cache rebuilt as the bare
    // flags map (loadCachedConfigs reads it as {key:{...}}). Mirrors parseSingleSurvey.
    // Unwrap a served flag entry to its RAW value. The server serves `{value,type,description,updated_at}`;
    // FeatureFlagManager/getConfig expect the raw value. Defensive: a non-wrapper entry is kept as-is.
    @Suppress("UNCHECKED_CAST")
    private fun flagRawValue(entry: Any): Any? {
        val dict = entry as? Map<String, Any>
        // key-present (even if value is null) ⇒ raw value; not a wrapper ⇒ keep as-is. A null value must
        // resolve to null (absent flag), NOT fall back to the wrapper dict.
        return if (dict != null && dict.containsKey("value")) dict["value"] else entry
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseSingleFlag(key: String, data: Map<String, Any>) {
        val v = flagRawValue(data)
        flags = if (v != null) flags + (key to v) else flags - key  // null value ⇒ unset (omit)
        try { cacheData("flags", JSONObject(flags as Map<*, *>).toString()) } catch (_: Exception) {}
        notifyChangeListeners()
    }

    // SPEC-036-H — per-item message doc config/message_index/messages/{id}; disk cache rebuilt in the
    // mega-doc {messages:{id:data}} shape (loadCachedConfigs → MessageConfigParser.parseMessages).
    private fun parseSingleMessage(id: String, data: Map<String, Any>) {
        try {
            val config = ai.appdna.sdk.messages.MessageConfigParser.parseSingleMessage(data)
            if (config != null) messages = messages + (id to config)
        } catch (e: Exception) {
            Log.error("Failed to parse individual message '$id': ${e.message}")
        }
        rawMessageData[id] = data
        try {
            val combined = JSONObject()
            val msgObj = JSONObject()
            for ((mid, mdata) in rawMessageData) { msgObj.put(mid, JSONObject(mdata)) }
            combined.put("messages", msgObj)
            cacheData("messages", combined.toString())
        } catch (_: Exception) {}
        notifyChangeListeners()
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseSinglePaywall(id: String, data: Map<String, Any>) {
        try {
            val config = PaywallConfigParser.parseSinglePaywall(id, data)
            if (config != null) {
                paywalls = paywalls + (id to config)
            }
        } catch (e: Exception) {
            Log.error("Failed to parse individual paywall '$id': ${e.message}")
        }
        // Rebuild disk cache in mega-doc format for offline restart
        rawPaywallData[id] = data
        try {
            val combined = JSONObject()
            val paywallsObj = JSONObject()
            for ((pid, pdata) in rawPaywallData) { paywallsObj.put(pid, JSONObject(pdata)) }
            combined.put("paywalls", paywallsObj)
            cacheData("paywalls", combined.toString())
        } catch (_: Exception) {}
    }

    private fun parseSingleOnboardingFlow(id: String, data: Map<String, Any>) {
        try {
            val config = OnboardingConfigParser.parseSingleFlow(id, data)
            if (config != null) {
                onboardingFlows = onboardingFlows + (id to config)
            }
        } catch (e: Exception) {
            Log.error("Failed to parse individual onboarding flow '$id': ${e.message}")
        }
        // Rebuild disk cache in mega-doc format for offline restart
        rawOnboardingData[id] = data
        try {
            val combined = JSONObject()
            val flowsObj = JSONObject()
            for ((fid, fdata) in rawOnboardingData) { flowsObj.put(fid, JSONObject(fdata)) }
            combined.put("flows", flowsObj)
            activeOnboardingFlowId?.let { combined.put("active_flow_id", it) }
            cacheData("onboarding", combined.toString())
        } catch (_: Exception) {}
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseSingleSurvey(id: String, data: Map<String, Any>) {
        surveys = surveys + (id to data)
        surveyUpdateHandler?.invoke(surveys)
        // Rebuild disk cache in mega-doc format
        try {
            val combined = JSONObject()
            val surveysObj = JSONObject()
            for ((sid, sdata) in surveys) { surveysObj.put(sid, JSONObject(sdata)) }
            combined.put("surveys", surveysObj)
            cacheData("surveys", combined.toString())
        } catch (_: Exception) {}
    }

    private fun loadCachedConfigs() {
        storage.getString("cache_flags")?.let { json ->
            try {
                val obj = JSONObject(json)
                flags = obj.keys().asSequence().associateWith { obj.get(it) }
            } catch (_: Exception) {}
        }
        storage.getString("cache_experiments")?.let { json ->
            try {
                val obj = JSONObject(json)
                val data = obj.keys().asSequence().associateWith { obj.get(it) as Any }
                @Suppress("UNCHECKED_CAST")
                parseExperiments(data)
            } catch (_: Exception) {}
        }
        storage.getString("cache_surveys")?.let { json ->
            try {
                val obj = JSONObject(json)
                val data = obj.keys().asSequence().associateWith { obj.get(it) as Any }
                @Suppress("UNCHECKED_CAST")
                parseSurveys(data)
            } catch (_: Exception) {}
        }
        storage.getString("cache_paywalls")?.let { json ->
            try {
                val obj = JSONObject(json)
                val data = obj.keys().asSequence().associateWith { obj.get(it) as Any }
                @Suppress("UNCHECKED_CAST")
                parsePaywalls(data)
            } catch (_: Exception) {}
        }
        storage.getString("cache_onboarding")?.let { json ->
            try {
                val obj = JSONObject(json)
                val data = obj.keys().asSequence().associateWith { obj.get(it) as Any }
                @Suppress("UNCHECKED_CAST")
                parseOnboarding(data)
            } catch (_: Exception) {}
        }
        // SPEC-070-A finalization — restore in-app messages from disk on cold
        // start. Without this branch, hosts that opened cached without network
        // saw messages disappear after process death.
        storage.getString("cache_messages")?.let { json ->
            try {
                val obj = JSONObject(json)
                @Suppress("UNCHECKED_CAST")
                val data = obj.keys().asSequence().associateWith { obj.get(it) as Any }
                messages = ai.appdna.sdk.messages.MessageConfigParser.parseMessages(data)
            } catch (_: Exception) {}
        }
    }

    /**
     * Load config from the bundled appdna-config.json embedded in the app.
     * Only populates empty caches — remote and cached data take priority.
     */
    @Suppress("UNCHECKED_CAST")
    fun loadBundledConfig(json: Map<String, Any>) {
        if (paywalls.isEmpty()) {
            (json["paywalls"] as? Map<String, Any>)?.let { data ->
                if (data.isNotEmpty()) { parsePaywalls(data); Log.debug("Loaded paywalls from bundled config") }
            }
        }
        if (onboardingFlows.isEmpty()) {
            (json["onboarding"] as? Map<String, Any>)?.let { data ->
                if (data.isNotEmpty()) { parseOnboarding(data); Log.debug("Loaded onboarding from bundled config") }
            }
        }
        if (experiments.isEmpty()) {
            (json["experiments"] as? Map<String, Any>)?.let { data ->
                if (data.isNotEmpty()) { parseExperiments(data); Log.debug("Loaded experiments from bundled config") }
            }
        }
        if (surveys.isEmpty()) {
            (json["surveys"] as? Map<String, Any>)?.let { data ->
                if (data.isNotEmpty()) { parseSurveys(data); Log.debug("Loaded surveys from bundled config") }
            }
        }
        if (messages.isEmpty()) {
            (json["messages"] as? Map<String, Any>)?.let { data ->
                if (data.isNotEmpty()) {
                    messages = ai.appdna.sdk.messages.MessageConfigParser.parseMessages(data)
                    Log.debug("Loaded messages from bundled config")
                }
            }
        }
        if (flags.isEmpty()) {
            val flagData = (json["remote_config"] as? Map<String, Any>)
                ?: (json["feature_flags"] as? Map<String, Any>)
            if (flagData != null && flagData.isNotEmpty()) {
                flags = flagData
                Log.debug("Loaded remote_config from bundled config")
            }
        }
        (json["screen_index"] as? Map<String, Any>)?.let { data ->
            if (data.isNotEmpty()) { parseScreenIndex(data); Log.debug("Loaded screen_index from bundled config") }
        }
    }

    private fun cacheData(key: String, json: String) {
        storage.setString("cache_$key", json)
    }

    private fun notifyChangeListeners() {
        for (listener in changeListeners) {
            listener(flags)
        }
        // SPEC-070-A finalization B6 P1 — fan out top-level configUpdated
        // broadcast so RN/Flutter wrappers + ad-hoc collectors react to
        // refreshes without each registering through every module.
        try {
            ai.appdna.sdk.AppDNA.notifyConfigUpdated()
        } catch (_: Throwable) { /* best-effort — pre-configure() emit drops */ }
    }
}

/**
 * Experiment config model. Mirrors iOS `Config/RemoteConfigManager.swift` `ExperimentConfig`.
 */
data class ExperimentConfig(
    val id: String,
    val name: String,
    val status: String,
    /**
     * SPEC-036-F §1.2 — the served `type` (surface kind this experiment targets,
     * e.g. "paywall" / "onboarding_flow" / "in_app_message" / "survey"). Used by
     * the experiment-aware presentation resolver to match a running experiment
     * against the surface+entity being presented. Nullable for docs predating
     * the field-map fix.
     */
    val type: String? = null,
    val salt: String,
    val platforms: List<String>,
    val variants: List<ExperimentVariant>,
    /** Legacy top-level key that nothing writes; not evaluated (there is no segment targeting — see [targeting]). */
    val segments: List<String>? = null,
    /** Share of users in the experiment (0…1). Absent on docs written before it was served = everyone. */
    val trafficAllocation: Double? = null,
    /** The audience rules the device evaluates ([ExperimentEligibility]); nested, as the server has always written it. */
    val targeting: ExperimentTargeting? = null,
    /** When the experiment started (epoch ms) — "new users only" compares the install date with it. */
    val startedAtMs: Long? = null,
)

/**
 * A single experiment variant.
 *
 * SPEC-070-A F.10: Firestore-canonical key for variant data is `payload`
 * (matching iOS `ExperimentVariant.payload`); legacy SDKs wrote `config`.
 * The parser in [RemoteConfigManager.parseExperiments] accepts BOTH keys,
 * preferring `payload` when set. The Kotlin field stays named `config` so
 * all existing callers (e.g. [ExperimentManager.getValue]) keep working.
 *
 * SPEC-036-F §1.2 — `configRef` is the entity id this variant maps to: for the
 * control it's the live active entity id (rendered via the surface index, no
 * payload); for the treatment it's the materialized draft entity whose
 * renderable config the server inlined into `config`/`payload`. `isControl`
 * distinguishes the two. Both nullable for backward-compat. Decoded from the
 * served `config_ref` / `is_control` keys.
 */
data class ExperimentVariant(
    val id: String,
    val weight: Double,
    val config: Map<String, Any> = emptyMap(),
    val configRef: String? = null,
    val isControl: Boolean? = null,
    // SPEC-036-H — `per_item` serving: a POINTER (Firestore doc path) to this treatment's isolated,
    // index-less variant doc instead of an inline `config`/`payload`. The SDK prefetches the doc by this
    // exact path (never via an index) and renders its `config`. Absent in `inline` mode (036-F).
    val variantDoc: String? = null,
)
