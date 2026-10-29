package ai.appdna.sdk.screens

/**
 * What a screen's `trigger_rules` compare against besides the event: the sessions this install has
 * started and the whole days since the app was installed.
 *
 * [daysSinceInstall] is null when the install time cannot be read, and a `days_since_install` trigger
 * then does not fire (rather than reading the install as "today"). The iOS twin is
 * `ScreenTriggerInputs` in `ScreenTriggerInputs.swift`.
 */
internal data class ScreenTriggerInputs(val sessionCount: Int, val daysSinceInstall: Int?) {
    companion object {
        private const val DAY_MS = 86_400_000L

        /** Whole days from [installEpochMs] to [nowMs]; null when the install time is unknown. */
        fun daysSinceInstall(installEpochMs: Long?, nowMs: Long): Int? =
            installEpochMs?.let { maxOf(0L, (nowMs - it) / DAY_MS).toInt() }

        /**
         * The live inputs. The install time is the package's first-install time (an update keeps it; a
         * reinstall starts it again), the same one experiment targeting reads.
         */
        fun current(nowMs: Long = System.currentTimeMillis()): ScreenTriggerInputs = ScreenTriggerInputs(
            sessionCount = ai.appdna.sdk.AppDNA.sessionManager?.sessionsThisInstall()?.toInt() ?: 1,
            daysSinceInstall = daysSinceInstall(ai.appdna.sdk.AppDNA.installEpochMs(), nowMs),
        )
    }
}
