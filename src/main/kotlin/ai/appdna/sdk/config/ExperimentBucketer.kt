package ai.appdna.sdk.config

/**
 * Pure MurmurHash3 32-bit bucketing for deterministic experiment variant
 * assignment. Public Kotlin counterpart to iOS
 * `Sources/AppDNASDK/Config/ExperimentBucketer.swift` (`public enum
 * ExperimentBucketer`). Output MUST be byte-identical to the Swift version
 * so the same user lands in the same variant on either platform.
 *
 * Hosts that want to bucket their own custom experiments (outside the
 * SDK-managed `experiments` config doc) can call [hash32] / [assignVariant]
 * directly without depending on internal SDK state.
 *
 * Extract public API surface to
 * mirror iOS. Internal SDK code paths still go through [MurmurHash3] in
 * [ExperimentManager] for back-compat; new callers should prefer
 * [ExperimentBucketer.hash32].
 */
object ExperimentBucketer {

    /**
     * Standard MurmurHash3 32-bit hash. Identical algorithm to iOS
     * `ExperimentBucketer.hash32(_:seed:)`.
     */
    fun hash32(key: String, seed: UInt = 0u): UInt = MurmurHash3.hash32(key, seed)

    /**
     * Assign a variant based on deterministic bucketing. Mirrors iOS
     * `ExperimentBucketer.assignVariant(experimentId:userId:salt:variants:)`.
     *
     * Returns the variant id, or null if the variants list is empty. Weights
     * should be 0.0..1.0 and sum to 1.0; the last variant catches any
     * rounding remainder.
     */
    @JvmStatic
    fun assignVariant(
        experimentId: String,
        userId: String,
        salt: String,
        variants: List<ExperimentVariant>,
    ): String? {
        if (variants.isEmpty()) return null

        val hashInput = "$experimentId.$salt.$userId"
        val bucket = hash32(hashInput) % 10000u

        var cumulative = 0u
        for (variant in variants) {
            cumulative += (variant.weight * 10000).toUInt()
            if (bucket < cumulative) {
                return variant.id
            }
        }
        return variants.last().id
    }
}
