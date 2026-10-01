package ai.appdna.sdk.events

import ai.appdna.sdk.DeviceIdentity
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

/**
 * SDK event envelope — matches iOS SDKEvent schema exactly.
 *
 * SPEC-070-A A.6: `SDK_VERSION` is the live `AppDNA.sdkVersion` value (not a stale literal).
 * SPEC-070-A A.14: `experiment_exposures` is now part of the envelope `context` object.
 * SPEC-070-A G.10: `environment` ("production" / "sandbox") is included in the envelope so
 *   ingest can route test-key traffic to the sandbox dataset.
 * SPEC-070-A G.15: `device.os` is emitted as a 3-part dotted string (e.g. `"14.0.0"`),
 *   matching iOS `UIDevice.current.systemVersion` semantics.
 * SPEC-070-A G.16: `device.locale` is emitted as a BCP-47 language tag
 *   (`Locale.toLanguageTag()`, e.g. `"zh-Hans-CN"`) — was previously the legacy
 *   `Locale.toString()` which produces `zh_CN_#Hans` style strings.
 * SPEC-070-A G.17: optional `context.screen` field for SPEC-086 zero-code attribution.
 */
internal object EventSchema {
    const val SCHEMA_VERSION = 1

    /**
     * SDK version reported in the event envelope `device.sdk_version` field.
     * Sources directly from [ai.appdna.sdk.AppDNA.sdkVersion] so it cannot drift
     * (the previous hard-coded "1.0.3" literal was 27 versions out of date).
     */
    val SDK_VERSION: String
        get() = ai.appdna.sdk.AppDNA.sdkVersion

    /**
     * Build an event envelope JSON matching the iOS format.
     */
    fun buildEnvelope(
        eventName: String,
        properties: Map<String, Any>?,
        identity: DeviceIdentity,
        sessionId: String,
        appVersion: String,
        analyticsConsent: Boolean,
        experimentExposures: List<ExperimentExposure>? = null,
        environment: String? = null,
        screen: String? = null,
        pushId: String? = null,
        // SPEC-428 STEP-9/§4.E: a PRE-STAMPED client_seq for a drained pre-init event — used verbatim,
        // never re-minted, so the pre-init events keep their reserved (lower) block below post-configure ones.
        clientSeq: Long? = null
    ): JSONObject {
        return JSONObject().apply {
            put("schema_version", SCHEMA_VERSION)
            put("event_id", UUID.randomUUID().toString().lowercase())
            put("event_name", eventName)
            put("ts_ms", System.currentTimeMillis())
            // SPEC-070-A G.10: tag every event with the active SDK environment
            // so ingest can fan test-key traffic into the sandbox dataset.
            if (environment != null) {
                put("environment", environment)
            }

            put("user", JSONObject().apply {
                put("anon_id", identity.anonId)
                if (identity.userId != null) put("user_id", identity.userId)
            })

            put("device", JSONObject().apply {
                put("platform", "android")
                // SPEC-070-C D4: SDK-wrapper attribution (native|flutter|react_native).
                put("framework", ai.appdna.sdk.AppDNA.framework)
                // SPEC-070-B §7 rule 4: the WRAPPER's own version. `sdk_version` below is always the
                // NATIVE core's, so without this the warehouse cannot name the artifact an RN or
                // Flutter developer actually installed. OMITTED (not null) for a native host, so a
                // native envelope keeps the exact shape iOS emits.
                ai.appdna.sdk.AppDNA.frameworkVersion?.let { put("framework_version", it) }
                // SPEC-070-A G.15: emit `device.os` as a 3-part dotted string so it
                // matches the iOS shape `"<major>.<minor>.<patch>"`. Android
                // `Build.VERSION.RELEASE` is just the major (e.g. "14") on most
                // releases — pad to "<major>.0.0" when the OEM string lacks dots.
                put("os", normalizeOsVersion(Build.VERSION.RELEASE))
                put("app_version", appVersion)
                put("sdk_version", SDK_VERSION)
                if (ai.appdna.sdk.AppDNA.currentBundleVersion > 0) {
                    put("bundle_version", ai.appdna.sdk.AppDNA.currentBundleVersion)
                }
                // SPEC-070-A G.16: emit BCP-47 language tag (e.g. "zh-Hans-CN")
                // instead of the legacy `Locale.toString()` form ("zh_CN_#Hans"),
                // so the field round-trips with iOS `Locale.identifier` exactly.
                put("locale", Locale.getDefault().toLanguageTag())
                put("country", Locale.getDefault().country)
                // The device's IANA time-zone id (e.g. "Europe/Berlin"), read per event so a user who
                // travels is scheduled in the zone they are in now. The server records it on the user's
                // profile for time-zone-aware pushes, quiet hours and journey waits. Omitted when the
                // platform reports none, as iOS omits a nil `timezone`.
                deviceTimeZoneId()?.let { put("timezone", it) }
            })

            put("context", JSONObject().apply {
                put("session_id", sessionId)
                // SPEC-428 CL-3/D6: per-device monotonic sequence, drawn at the single buildEnvelope
                // choke point. ts_ms stays but is no longer the ordering key.
                put("client_seq", clientSeq ?: ClientSeqCounter.next())
                // SPEC-070-A G.17: optional screen name for zero-code attribution.
                if (screen != null) {
                    put("screen", screen)
                }
                // SPEC-070-A H.7: most-recently-received push id, propagated
                // for the rolling 30-minute attribution window so subsequent
                // events can be tied back to the push that triggered them.
                // Mirrors iOS `EventContext.push_id`.
                if (pushId != null) {
                    put("push_id", pushId)
                }
                // SPEC-070-A A.14: include experiment exposure context so the
                // BigQuery ETL can attribute events to the assigned variant.
                // Shape mirrors iOS Events/EventSchema.swift `EventContext.experiment_exposures`:
                //   [{ exp: <experimentId>, variant: <variantId> }, ...]
                if (!experimentExposures.isNullOrEmpty()) {
                    put("experiment_exposures", JSONArray().apply {
                        for (exposure in experimentExposures) {
                            put(JSONObject().apply {
                                put("exp", exposure.experimentId)
                                put("variant", exposure.variantId)
                            })
                        }
                    })
                }
            })

            if (properties != null && properties.isNotEmpty()) {
                put("properties", JSONObject(properties))
            }

            put("privacy", JSONObject().apply {
                put("consent", JSONObject().apply {
                    put("analytics", analyticsConsent)
                })
            })
        }
    }

    /** The device's IANA zone id (`TimeZone.getDefault().id`), or null when empty. */
    internal fun deviceTimeZoneId(): String? =
        java.util.TimeZone.getDefault()?.id?.takeIf { it.isNotEmpty() }

    /**
     * SPEC-070-A G.15: Pad `Build.VERSION.RELEASE` to a 3-part `<major>.<minor>.<patch>`
     * dotted string so the wire-format matches iOS `UIDevice.current.systemVersion`.
     *
     * Examples:
     *   "14"        → "14.0.0"
     *   "14.0"      → "14.0.0"
     *   "14.0.1"    → "14.0.1"
     *   "11-rc1"    → "11-rc1.0.0"  (best-effort; very rare in practice)
     */
    internal fun normalizeOsVersion(raw: String?): String {
        val v = (raw ?: "").trim()
        if (v.isEmpty()) return "0.0.0"
        val dots = v.count { it == '.' }
        return when (dots) {
            0 -> "$v.0.0"
            1 -> "$v.0"
            else -> v
        }
    }
}

/**
 * SPEC-070-A A.14: Single experiment-exposure tuple emitted in the event envelope's
 * `context.experiment_exposures` array.
 *
 * Sourced from [ai.appdna.sdk.config.ExperimentManager.getExposures] when an event is tracked.
 * Wire-format shape matches iOS `Events/EventSchema.swift` `ExperimentExposure { exp, variant }`.
 */
internal data class ExperimentExposure(
    val experimentId: String,
    val variantId: String
)

/**
 * SPEC-428 CL-3/D6 — device-wide MONOTONIC sequence counter, persisted in a FACADE-available store
 * (SharedPreferences), never the EventDatabase/EventQueue (which are built inside configure()).
 * Initialized at configure() when the app Context arrives; the single increment site is
 * EventSchema.buildEnvelope. Survives restart, independent of wall-clock.
 */
internal object ClientSeqCounter {
    // SPEC-428 CL-3/STEP-6: KEY persists the RESERVED CEILING (>= every seq handed out). We hand out from
    // an in-memory block and WRITE only when the block is exhausted — persisting the ceiling ABOVE the
    // handed-out values — so a hard kill between the async apply() and its disk flush yields a GAP, never
    // a REUSE. Also O(1) amortized (one write every BLOCK, not per event).
    private const val PREFS = "appdna_client_seq"
    private const val KEY = "client_seq"
    private const val BLOCK = 100L
    private var prefs: android.content.SharedPreferences? = null
    private var current = 0L
    private var ceiling = 0L
    private var loaded = false
    private val lock = Any()

    fun init(context: android.content.Context) {
        synchronized(lock) {
            prefs = context.applicationContext.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            loaded = false // re-read the persisted ceiling on next() (also simulates a cold restart in tests)
        }
    }

    fun next(): Long {
        synchronized(lock) {
            val p = prefs ?: return ++current // pre-init fallback (no Context yet) — in-memory monotonic
            if (!loaded) {
                val persisted = p.getLong(KEY, 0L) // >= every seq handed out before a crash
                current = persisted; ceiling = persisted; loaded = true
            }
            current += 1
            if (current > ceiling) {
                ceiling = current + BLOCK
                // SYNC commit() at the (rare) block boundary so the ceiling is DURABLE before we hand out a
                // seq from the new block — a hard kill then yields a gap, NEVER a reuse. Only ~1 in BLOCK
                // events pays the sync write, so the CL-8 hot-path budget still holds.
                p.edit().putLong(KEY, ceiling).commit()
            }
            return current
        }
    }
}

/**
 * SPEC-428 CL-1/D2 — durable counter of events dropped by a cap/quota eviction, persisted in
 * SharedPreferences (survives restart). Drained by the tracker into a `_sdk_events_dropped`
 * meta-event so the loss is SERVER-VISIBLE, not a silent Log.warning.
 */
internal object DroppedEventsCounter {
    private const val PREFS = "appdna_dropped_events"
    private const val KEY = "dropped"
    private var prefs: android.content.SharedPreferences? = null
    private val lock = Any()

    fun init(context: android.content.Context) {
        synchronized(lock) {
            if (prefs == null) {
                prefs = context.applicationContext.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            }
        }
    }

    fun increment(n: Int) {
        if (n <= 0) return
        synchronized(lock) {
            val p = prefs ?: return
            p.edit().putInt(KEY, p.getInt(KEY, 0) + n).apply()
        }
    }

    /** Atomically read + reset. Test helper; production uses peek()+subtract() (STEP-4). */
    fun getAndReset(): Int {
        synchronized(lock) {
            val p = prefs ?: return 0
            val c = p.getInt(KEY, 0)
            if (c > 0) p.edit().putInt(KEY, 0).apply()
            return c
        }
    }

    /**
     * SPEC-428 STEP-4: read WITHOUT resetting. The count is removed (subtract) ONLY after the
     * `_sdk_events_dropped` meta carrying it is DURABLY persisted, so a hard kill before the meta lands
     * re-emits it (never an UNDER-count, which STEP-4 forbids). A concurrent peek may double-emit
     * (over-count, direction-safe — the spec prioritizes never-under-count).
     */
    fun peek(): Int {
        synchronized(lock) {
            val p = prefs ?: return 0
            return p.getInt(KEY, 0)
        }
    }

    /** SPEC-428 STEP-4: atomic DECREMENT-by-N (floored at 0), called once the meta carrying N is durable. */
    fun subtract(n: Int) {
        if (n <= 0) return
        synchronized(lock) {
            val p = prefs ?: return
            p.edit().putInt(KEY, maxOf(0, p.getInt(KEY, 0) - n)).apply()
        }
    }
}
