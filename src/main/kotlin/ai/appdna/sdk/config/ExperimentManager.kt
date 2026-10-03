package ai.appdna.sdk.config

import ai.appdna.sdk.IdentityManager
import ai.appdna.sdk.Log
import ai.appdna.sdk.events.EventTracker

/**
 * Manages experiment variant assignment via deterministic MurmurHash3 bucketing.
 * A user is in an experiment only when it is running, targets this platform, every targeting rule passes and the user
 * falls inside the traffic allocation ([ExperimentEligibility]); only then is a variant assigned and an exposure
 * tracked. Exposures are tracked once per experiment until `reset()` or the next app launch (kept in memory).
 *
 * MurmurHash3 implementation MUST produce identical output to the iOS Swift version.
 */
internal class ExperimentManager(
    private val remoteConfigManager: RemoteConfigManager,
    private val identityManager: IdentityManager,
    private val eventTracker: EventTracker,
    /** The device facts targeting is evaluated against (injectable for tests). */
    private val eligibilityContext: (traits: Map<String, Any>) -> ExperimentEligibilityContext =
        { traits -> ExperimentEligibilityContext.current(null, traits) },
) {
    private val exposedExperiments = mutableMapOf<String, String>()

    /**
     * Get the variant id for an experiment, or `null` if the user is not in it (not running,
     * platform / targeting / traffic allocation excludes them). Auto-tracks the exposure on the first assignment.
     * Return type matches iOS `getVariant(experimentId:) -> String?`.
     *
     * Use [getVariantPayload] when callers need the full [ExperimentVariant]
     * object (config payload).
     */
    fun getVariant(experimentId: String): String? {
        return getVariantPayload(experimentId)?.id
    }

    /**
     * Internal: returns the assigned [ExperimentVariant] (id + weight + payload).
     * Auto-tracks the exposure on the first assignment.
     */
    fun getVariantPayload(experimentId: String): ExperimentVariant? {
        val identity = identityManager.currentIdentity
        val userId = identity.userId ?: identity.anonId
        val config = resolveConfig(experimentId, userId) ?: return null

        // Fall back to experimentId when salt is blank, so a
        // missing/empty salt produces identical bucketing to iOS
        // (Config/ExperimentManager.swift:33-38 uses `config.salt ?? experimentId`).
        val effectiveSalt = config.salt.ifBlank { experimentId }
        val variant = assignVariant(
            experimentId = experimentId,
            userId = userId,
            salt = effectiveSalt,
            variants = config.variants
        ) ?: return null

        trackExposure(experimentId, variant.id)
        return variant
    }

    /**
     * Check if the user is assigned to a specific variant.
     */
    fun isInVariant(experimentId: String, variantId: String): Boolean {
        return getVariant(experimentId) == variantId
    }

    /**
     * Get a specific config value from the assigned variant.
     */
    fun getExperimentConfig(experimentId: String, key: String): Any? {
        val variant = getVariantPayload(experimentId) ?: return null
        return variant.config[key]
    }

    // MARK: - experiment-aware surface presentation

    /**
     * The outcome of resolving whether a running experiment governs how a given
     * surface entity should be presented. Mirrors iOS
     * `ExperimentManager.SurfaceResolution`.
     */
    sealed class SurfaceResolution {
        /** No running experiment targets this surface+entity, the user wasn't
         * bucketed, or the SDK fell to the control bucket → render the live
         * active entity through the normal path. */
        object RenderActive : SurfaceResolution()

        /** The user is bucketed into the treatment variant → render the inlined
         * [payload] config instead of the active entity. The map is the raw
         * Firestore-shaped config (same shape the live-config parsers consume). */
        data class RenderTreatment(
            val experimentId: String,
            val variantId: String,
            val payload: Map<String, Any>,
        ) : SurfaceResolution()
    }

    /**
     * Decide whether a `running` experiment governs the
     * presentation of [entityId] for the given surface [surfaceType]. Matches
     * an experiment whose served `type` == [surfaceType] AND whose control
     * variant's `configRef` == [entityId]. On a match the user is bucketed via
     * the SAME [assignVariant] path (+ exposure tracked):
     *   - control bucket / no payload → [SurfaceResolution.RenderActive]
     *   - treatment bucket with payload → [SurfaceResolution.RenderTreatment]
     *
     * Cohort isolation: the treatment config lives ONLY in the
     * experiment doc payload, so non-bucketed / control / old-SDK users can
     * never resolve to it. Mirrors iOS `resolveSurfacePresentation`.
     */
    fun resolveSurfacePresentation(surfaceType: String, entityId: String): SurfaceResolution {
        val allExperiments = remoteConfigManager.getAllExperiments()

        for ((experimentId, config) in allExperiments) {
            if (config.status != "running") continue
            if (config.type != surfaceType) continue
            if (!config.platforms.contains("android")) continue

            // The control variant's configRef names the live active entity.
            val controlMatches = config.variants.any { (it.isControl == true) && it.configRef == entityId }
            if (!controlMatches) continue

            val identity = identityManager.currentIdentity
            val userId = identity.userId ?: identity.anonId
            // Targeting + traffic allocation (same gate as getVariant): a user who is not in the experiment sees the
            // live entity and is not exposed.
            if (!isInExperiment(experimentId, config, userId)) continue
            val effectiveSalt = config.salt.ifBlank { experimentId }
            val variant = assignVariant(
                experimentId = experimentId,
                userId = userId,
                salt = effectiveSalt,
                variants = config.variants,
            ) ?: continue

            // Track the exposure once regardless of bucket — the user WAS exposed by seeing the surface.
            trackExposure(experimentId, variant.id)

            // Control bucket → render the live active entity. Treatment WITHOUT
            // a payload → safe fallback to active. Only a treatment WITH a
            // payload renders the variant config.
            if (variant.isControl == true) return SurfaceResolution.RenderActive
            // per_item serving: the treatment config lives in an isolated variant doc
            // pointed to by `variantDoc` (prefetched into the cache). Prefer it; fall back to the inline
            // 036-F `config`. A not-yet-fetched / failed variant doc → RenderActive (failure
            // degradation — never broken, never cross-cohort).
            val docPath = variant.variantDoc
            if (docPath != null) {
                val docConfig = remoteConfigManager.getVariantDoc(docPath)
                    ?: return SurfaceResolution.RenderActive
                return SurfaceResolution.RenderTreatment(
                    experimentId = experimentId,
                    variantId = variant.id,
                    payload = docConfig,
                )
            }
            if (variant.config.isEmpty()) return SurfaceResolution.RenderActive
            return SurfaceResolution.RenderTreatment(
                experimentId = experimentId,
                variantId = variant.id,
                payload = variant.config,
            )
        }

        return SurfaceResolution.RenderActive
    }

    /**
     * Reset exposure tracking (called by `reset()`; exposures are otherwise kept until the app is relaunched — a new
     * session does not reset them).
     */
    fun resetExposures() {
        synchronized(exposedExperiments) {
            exposedExperiments.clear()
        }
    }

    // MARK: - Private

    /** Running, on this platform, and the user is in the experiment (targeting + traffic allocation). */
    private fun resolveConfig(experimentId: String, userId: String): ExperimentConfig? {
        val config = remoteConfigManager.getExperimentConfig(experimentId) ?: run {
            Log.debug("Experiment '$experimentId' not found in config")
            return null
        }

        if (config.status != "running") {
            Log.debug("Experiment '$experimentId' is not running (status: ${config.status})")
            return null
        }

        if (!config.platforms.contains("android")) {
            Log.debug("Experiment '$experimentId' does not target Android")
            return null
        }

        if (!isInExperiment(experimentId, config, userId)) return null
        return config
    }

    /** Targeting rules, then the traffic allocation. Logs why a user is out. */
    private fun isInExperiment(experimentId: String, config: ExperimentConfig, userId: String): Boolean {
        val traits = identityManager.currentIdentity.traits ?: emptyMap()
        val failed = ExperimentEligibility.failedRule(config.targeting, config.startedAtMs, eligibilityContext(traits))
        if (failed != null) {
            Log.debug("Experiment '$experimentId': user not in the audience (${failed.wire})")
            return false
        }
        if (!ExperimentEligibility.isAllocated(experimentId, config.salt.ifBlank { experimentId }, userId, config.trafficAllocation)) {
            Log.debug("Experiment '$experimentId': user outside the traffic allocation")
            return false
        }
        return true
    }

    private fun assignVariant(
        experimentId: String,
        userId: String,
        salt: String,
        variants: List<ExperimentVariant>
    ): ExperimentVariant? {
        if (variants.isEmpty()) return null

        val hashInput = "$experimentId.$salt.$userId"
        val hash = MurmurHash3.hash32(hashInput)
        val bucket = hash.toUInt() % 10000u

        var cumulative = 0u
        for (variant in variants) {
            cumulative += (variant.weight * 10000).toUInt()
            if (bucket < cumulative) {
                return variant
            }
        }

        return variants.lastOrNull()
    }

    /**
     * Get all recorded exposures.
     *
     * Each entry is a named [ExposureEntry] (experimentId, variant) — the
     * Kotlin equivalent of iOS's named-tuple shape
     * `[(experimentId: String, variant: String)]`. Pair-form callers must
     * migrate to property access; the legacy [getExposurePairs] helper
     * remains for one release as a transition aid.
     */
    fun getExposures(): List<ExposureEntry> {
        synchronized(exposedExperiments) {
            return exposedExperiments.map { (experimentId, variantId) ->
                ExposureEntry(experimentId = experimentId, variant = variantId)
            }
        }
    }

    /**
     * Legacy `Pair<String,String>` shape for callers that haven't migrated to
     * [ExposureEntry] yet. Kept tightly internal — the public API is
     * [getExposures]. Will be removed in a follow-up release.
     */
    internal fun getExposurePairs(): List<Pair<String, String>> {
        synchronized(exposedExperiments) {
            return exposedExperiments.map { (k, v) -> k to v }
        }
    }

    private fun trackExposure(experimentId: String, variantId: String) {
        synchronized(exposedExperiments) {
            if (!exposedExperiments.containsKey(experimentId)) {
                exposedExperiments[experimentId] = variantId
                eventTracker.track("experiment_exposure", mapOf(
                    "experiment_id" to experimentId,
                    "variant" to variantId,
                    "source" to "sdk"
                ))
            }
        }
    }
}

/**
 * Single (experiment, variant) tuple for [ExperimentManager.getExposures].
 * Named-property access mirrors iOS's `[(experimentId: String, variant: String)]` shape;
 * Kotlin can't express named tuples in the type system so a dedicated data class is the
 * canonical equivalent.
 */
data class ExposureEntry(
    val experimentId: String,
    val variant: String,
)

/**
 * MurmurHash3 32-bit implementation — MUST produce identical output to iOS Swift version.
 */
object MurmurHash3 {
    /**
     * Standard MurmurHash3 32-bit hash.
     */
    fun hash32(key: String, seed: UInt = 0u): UInt {
        val data = key.toByteArray(Charsets.UTF_8)
        val len = data.size
        val nblocks = len / 4

        var h1 = seed

        val c1: UInt = 0xcc9e2d51u
        val c2: UInt = 0x1b873593u

        // Body — process 4-byte blocks
        for (i in 0 until nblocks) {
            val offset = i * 4
            var k1: UInt = (data[offset].toUInt() and 0xFFu) or
                    ((data[offset + 1].toUInt() and 0xFFu) shl 8) or
                    ((data[offset + 2].toUInt() and 0xFFu) shl 16) or
                    ((data[offset + 3].toUInt() and 0xFFu) shl 24)

            k1 *= c1
            k1 = k1.rotateLeft(15)
            k1 *= c2

            h1 = h1 xor k1
            h1 = h1.rotateLeft(13)
            h1 = h1 * 5u + 0xe6546b64u
        }

        // Tail — process remaining bytes
        val tail = nblocks * 4
        var k1: UInt = 0u

        when (len and 3) {
            3 -> {
                k1 = k1 xor ((data[tail + 2].toUInt() and 0xFFu) shl 16)
                k1 = k1 xor ((data[tail + 1].toUInt() and 0xFFu) shl 8)
                k1 = k1 xor (data[tail].toUInt() and 0xFFu)
                k1 *= c1
                k1 = k1.rotateLeft(15)
                k1 *= c2
                h1 = h1 xor k1
            }
            2 -> {
                k1 = k1 xor ((data[tail + 1].toUInt() and 0xFFu) shl 8)
                k1 = k1 xor (data[tail].toUInt() and 0xFFu)
                k1 *= c1
                k1 = k1.rotateLeft(15)
                k1 *= c2
                h1 = h1 xor k1
            }
            1 -> {
                k1 = k1 xor (data[tail].toUInt() and 0xFFu)
                k1 *= c1
                k1 = k1.rotateLeft(15)
                k1 *= c2
                h1 = h1 xor k1
            }
        }

        // Finalization
        h1 = h1 xor len.toUInt()
        h1 = h1 xor (h1 shr 16)
        h1 *= 0x85ebca6bu
        h1 = h1 xor (h1 shr 13)
        h1 *= 0xc2b2ae35u
        h1 = h1 xor (h1 shr 16)

        return h1
    }

    private fun UInt.rotateLeft(distance: Int): UInt {
        return (this shl distance) or (this shr (32 - distance))
    }
}
