package ai.appdna.sdk.config

/**
 * Feature flag evaluation — thin wrapper over RemoteConfigManager.
 */
internal class FeatureFlagManager(private val remoteConfigManager: RemoteConfigManager) {

    /**
     * Check if a feature flag is enabled.
     * Returns true for a truthy value: boolean true, any NON-ZERO number, or a string in
     * {"true","1","yes","on"} (case-insensitive). The Number case was strictly `== 1`, so a
     * tri-state/count flag with value 2 read `false` on Android but `true` on iOS (NSNumber.boolValue).
     * Now non-zero → true, matching iOS; the string set is aligned across both platforms.
     */
    fun isEnabled(flag: String): Boolean {
        return when (val value = remoteConfigManager.getConfig(flag)) {
            is Boolean -> value
            is Number -> value.toDouble() != 0.0
            is String -> value.trim().lowercase() in setOf("true", "1", "yes", "on")
            else -> false
        }
    }

    /**
     * Get the raw value of a feature flag.
     */
    fun getValue(flag: String): Any? = remoteConfigManager.getConfig(flag)

    /**
     * Register a listener for feature flag changes. It fires whenever the remote config's flags
     * change, with every flag's [isEnabled] value. (It was a stub that registered nothing, so
     * `AppDNA.features.onChanged` never fired on Android.)
     */
    fun addChangeListener(callback: (Map<String, Boolean>) -> Unit) {
        remoteConfigManager.addChangeListener { flags -> callback(flags.keys.associateWith { isEnabled(it) }) }
    }
}
