package ai.appdna.sdk.events

import ai.appdna.sdk.AppDNAOptions
import android.content.Context
import org.json.JSONObject

/**
 * The SDK's three runtime settings — `flushInterval`, `batchSize` and `configTTL` — and where each value
 * comes from.
 *
 * Precedence, per setting: a value the host passed to `configure` ([AppDNAOptions], non-null) > the value the
 * bootstrap answer carries in `settings` > the built-in default. The built-in default applies until a
 * bootstrap answer arrives, and stays when none does or when it does not carry the field. A bootstrap value
 * of 0 or less is ignored, so a server answer can never stop uploads; so is a host value of 0 or less (logged as a
 * warning): the setting then resolves as if the host had not set it.
 *
 * `batchSize` is a cap on the adaptive, network-sized batch (100 on Wi-Fi or wired, 50 on cellular, 20 on a
 * metered connection, 0 offline): the effective size is the adaptive one when no cap is set, otherwise the
 * smaller of the two. It is both the queue length that triggers a flush and the most one upload sends. Only an
 * internal test seam can install a cap of 0 (which holds every event on the device); no option or answer can. iOS `RuntimeSettings`, same rules; both are pinned by the shared
 * fixture `resilience/runtime_settings_precedence`.
 */
internal object RuntimeSettings {
    const val DEFAULT_FLUSH_INTERVAL: Long = 30L
    const val DEFAULT_CONFIG_TTL: Long = 3600L

    /** The values in force after resolving host options against a bootstrap answer. */
    data class Resolved(
        val flushInterval: Long,
        /** null: no cap (the adaptive size). */
        val batchSizeCap: Int?,
        val configTTL: Long,
    )

    /** The runtime values a bootstrap answer's `settings` carries; null where absent or not a number. */
    data class Bootstrap(val flushInterval: Long?, val batchSize: Int?, val configTTL: Long?) {
        companion object {
            fun from(settings: JSONObject?): Bootstrap? {
                settings ?: return null
                fun num(key: String): Number? = if (settings.has(key) && !settings.isNull(key)) settings.opt(key) as? Number else null
                return Bootstrap(num("flushInterval")?.toLong(), num("batchSize")?.toInt(), num("configTTL")?.toLong())
            }
        }
    }

    /** explicit (positive only) > bootstrap (positive only) > fallback. */
    fun resolveLong(explicit: Long?, bootstrap: Long?, fallback: Long?): Long? =
        explicit?.takeIf { it > 0 } ?: bootstrap?.takeIf { it > 0 } ?: fallback

    fun resolveInt(explicit: Int?, bootstrap: Int?, fallback: Int?): Int? =
        explicit?.takeIf { it > 0 } ?: bootstrap?.takeIf { it > 0 } ?: fallback

    /** A host value below 1 is not used; say so. */
    private fun warnIgnored(name: String, value: Long?) {
        if (value != null && value <= 0) ai.appdna.sdk.Log.warning("AppDNAOptions.$name must be 1 or more; $value is ignored and the default applies.")
    }

    /** Resolve all three settings. [bootstrap] is null before (or without) a successful bootstrap. */
    fun resolveAll(options: AppDNAOptions, bootstrap: Bootstrap?): Resolved {
        warnIgnored("flushInterval", options.flushInterval)
        warnIgnored("batchSize", options.batchSize?.toLong())
        warnIgnored("configTTL", options.configTTL)
        return resolved(options, bootstrap)
    }

    private fun resolved(options: AppDNAOptions, bootstrap: Bootstrap?): Resolved = Resolved(
        flushInterval = resolveLong(options.flushInterval, bootstrap?.flushInterval, DEFAULT_FLUSH_INTERVAL)
            ?: DEFAULT_FLUSH_INTERVAL,
        batchSizeCap = resolveInt(options.batchSize, bootstrap?.batchSize, null),
        configTTL = resolveLong(options.configTTL, bootstrap?.configTTL, DEFAULT_CONFIG_TTL) ?: DEFAULT_CONFIG_TTL,
    )

    /** The batch size in effect: the adaptive size, capped by [cap] when one is set (never below 0). */
    fun effectiveBatchSize(adaptive: Int, cap: Int?): Int =
        if (cap == null) adaptive else minOf(adaptive, maxOf(cap, 0))
}

/**
 * The batch-size cap in force, persisted so the WorkManager uploader (which can run in a fresh process,
 * outside the queue) sends batches no larger than the queue would. Written by the queue when it starts and
 * whenever a bootstrap changes the cap; absent means no cap. iOS `BatchSizeCapGate`, same rule.
 */
internal object BatchSizeCapGate {
    private const val PREFS = "ai.appdna.sdk.events"
    private const val KEY = "batch_size_cap"

    fun set(context: Context?, cap: Int?) {
        val ctx = context ?: return
        try {
            val edit = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            if (cap == null) edit.remove(KEY) else edit.putInt(KEY, cap)
            edit.apply()
        } catch (_: Throwable) { /* best effort: the queue's in-memory cap still holds */ }
    }

    fun cap(context: Context?): Int? {
        val ctx = context ?: return null
        return try {
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (prefs.contains(KEY)) prefs.getInt(KEY, 0) else null
        } catch (_: Throwable) { null }
    }
}
