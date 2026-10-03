package ai.appdna.sdk.config

import ai.appdna.sdk.core.AudienceRule
import ai.appdna.sdk.core.AudienceRuleEvaluator
import ai.appdna.sdk.core.ConditionEvaluator
import android.content.Context
import android.os.Build
import java.util.Locale

/**
 * The `targeting` object of a served experiment doc — the rules the Console lets an author set, as the server
 * normalises them: `countries` (upper-case ISO-3166 alpha-2), `min_app_version`, `new_users_only`, and
 * `user_traits` (`[{trait, operator, value}]`, every condition must match). `platforms` is informational; the
 * top-level `platforms` field of the doc stays authoritative. Mirrors iOS `ExperimentTargeting`.
 *
 * A key that is present but has the wrong shape marks the targeting [malformed], and a malformed targeting is never
 * eligible (fail closed — a rule the author set is never silently skipped).
 */
data class ExperimentTargeting(
    val countries: List<String>? = null,
    val minAppVersion: String? = null,
    val newUsersOnly: Boolean? = null,
    val userTraits: List<AudienceRule>? = null,
    val platforms: List<String>? = null,
    val malformed: Boolean = false,
) {
    companion object {
        /** Parse the served `targeting` map. null / absent → null (no rules). */
        @Suppress("UNCHECKED_CAST")
        fun fromAny(raw: Any?): ExperimentTargeting? {
            if (raw == null) return null
            val map = raw as? Map<String, Any?> ?: return ExperimentTargeting(malformed = true)
            var bad = false
            fun strings(key: String): List<String>? {
                val v = map[key] ?: return null
                val list = v as? List<*>
                if (list == null || list.any { it !is String }) { bad = true; return null }
                return list.filterIsInstance<String>()
            }
            val countries = strings("countries")
            val minVersion = when (val v = map["min_app_version"]) { null -> null; is String -> v; else -> { bad = true; null } }
            val newUsers = when (val v = map["new_users_only"]) { null -> null; is Boolean -> v; else -> { bad = true; null } }
            val traits = when (val v = map["user_traits"]) {
                null -> null
                is List<*> -> if (v.all { it is Map<*, *> && isEvaluableRule(it) }) v.map { AudienceRule.fromMap(it as Map<String, Any?>) } else { bad = true; null }
                else -> { bad = true; null }
            }
            val platforms = strings("platforms")
            return ExperimentTargeting(countries, minVersion, newUsers, traits, platforms, malformed = bad)
        }

        /**
         * A trait condition the device can evaluate: a non-blank trait name (`trait`, or the console's `field`), each a
         * string when present, and an `operator` that is a string when present. Anything else fails closed (malformed)
         * — the shared rule parser would read a non-string trait as "" and a non-string operator as `equals`, so a
         * `neq` rule on trait 5 passed every user. iOS rejects the same rules (its `AudienceRule` decode, and the
         * blank-trait check in `ExperimentTargeting`).
         */
        private fun isEvaluableRule(rule: Map<*, *>): Boolean {
            val trait = rule["trait"]
            val field = rule["field"]
            val op = rule["operator"]
            if (trait != null && trait !is String) return false
            if (field != null && field !is String) return false
            if (op != null && op !is String) return false
            val name = (trait as? String)?.takeIf { it.isNotBlank() } ?: (field as? String)
            return !name.isNullOrBlank()
        }
    }
}

/**
 * What the device knows about itself when an experiment is evaluated. Production values come from [current]; tests
 * pass their own. Mirrors iOS `ExperimentEligibilityContext`.
 */
internal data class ExperimentEligibilityContext(
    /** "android" */
    val platform: String,
    /** The host app's `versionName`. */
    val appVersion: String?,
    /** The device region (ISO-3166 alpha-2, upper case) — [DeviceRegion.current], not an IP lookup. */
    val deviceRegion: String?,
    /**
     * When the app was first installed on this device (`PackageInfo.firstInstallTime`, epoch ms): an update keeps it;
     * a reinstall, or a restore from a backup / device transfer, starts a new one (iOS `AppInstallDate`, same rule).
     */
    val installEpochMs: Long?,
    /**
     * The identity's traits: the ones the host passed to `AppDNA.identify(userId, traits)`. There is no API that sets
     * traits for an anonymous user, so until `identify` there are none (Android adds no bootstrap geo traits).
     */
    val traits: Map<String, Any>,
) {
    companion object {
        /** Package facts do not change while the process runs — read them once (getPackageInfo is a binder call). */
        @Volatile private var packageFacts: Pair<String?, Long?>? = null

        fun current(context: Context?, traits: Map<String, Any>): ExperimentEligibilityContext {
            val (version, installed) = packageFacts ?: readPackageFacts(context).also { if (context != null) packageFacts = it }
            return ExperimentEligibilityContext(
                platform = "android",
                appVersion = version,
                deviceRegion = DeviceRegion.current(context),
                installEpochMs = installed,
                traits = traits,
            )
        }

        private fun readPackageFacts(context: Context?): Pair<String?, Long?> {
            var version: String? = null
            var installed: Long? = null
            if (context != null) {
                try {
                    val pm = context.packageManager
                    @Suppress("DEPRECATION")
                    val info = if (Build.VERSION.SDK_INT >= 33) {
                        pm.getPackageInfo(context.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
                    } else {
                        pm.getPackageInfo(context.packageName, 0)
                    }
                    version = info.versionName
                    installed = info.firstInstallTime.takeIf { it > 0 }
                } catch (_: Exception) { /* unknown → rules that need it fail closed */ }
            }
            return version to installed
        }
    }
}

/**
 * The device's country for the targeting "Countries" rule — one definition on both platforms (iOS `DeviceRegion`):
 * the first candidate that is an ISO-3166 alpha-2 code (exactly two ASCII letters, upper-cased; a UN M.49 area such as
 * `419` is not a country). Candidates, in order: the default locale's country, the other entries of the locale list,
 * then the SIM's country and the mobile network's country. None → null (a country rule then fails closed). A
 * region-less default locale (`en`) used to give "" here — no country at all — where iOS fell back.
 */
internal object DeviceRegion {
    fun current(context: Context?): String? {
        val candidates = sequence<String?> {
            yield(Locale.getDefault().country)
            val list = android.os.LocaleList.getDefault()
            for (i in 0 until list.size()) yield(list.get(i)?.country)
            val tm = try {
                context?.getSystemService(Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
            } catch (_: Throwable) { null }
            if (tm != null) {
                yield(try { tm.simCountryIso } catch (_: Throwable) { null })
                yield(try { tm.networkCountryIso } catch (_: Throwable) { null })
            }
        }
        return resolve(candidates)
    }

    /** The first ISO-3166 alpha-2 code among [candidates], upper-cased; null when there is none. */
    fun resolve(candidates: Sequence<String?>): String? = candidates
        .mapNotNull { it?.trim() }
        .firstOrNull { c -> c.length == 2 && c.all { it in 'a'..'z' || it in 'A'..'Z' } }
        ?.uppercase(Locale.ROOT)

    fun resolve(candidates: List<String?>): String? = resolve(candidates.asSequence())
}

/**
 * Decides whether this device / user is IN a running experiment, before any variant is assigned. Order on device:
 * status → platform → targeting → traffic allocation → variant bucket → exposure. Not in → no variant, no exposure.
 * Must produce the same answers as iOS `ExperimentEligibility.swift` (shared fixtures: `experiment_serving/`).
 */
internal object ExperimentEligibility {

    enum class Ineligible(val wire: String) {
        MALFORMED_TARGETING("malformed_targeting"),
        COUNTRY("country"),
        MIN_APP_VERSION("min_app_version"),
        NEW_USERS_ONLY("new_users_only"),
        USER_TRAITS("user_traits"),
    }

    /** The first targeting rule this context fails, or null when every rule passes (or there is none). */
    fun failedRule(targeting: ExperimentTargeting?, startedAtMs: Long?, context: ExperimentEligibilityContext): Ineligible? {
        val t = targeting ?: return null
        if (t.malformed) return Ineligible.MALFORMED_TARGETING

        val countries = t.countries?.map { it.trim().uppercase(Locale.ROOT) }?.filter { it.isNotEmpty() }
        if (!countries.isNullOrEmpty()) {
            val region = context.deviceRegion?.uppercase(Locale.ROOT)
            if (region == null || region !in countries) return Ineligible.COUNTRY
        }

        val minVersion = t.minAppVersion?.trim()
        if (!minVersion.isNullOrEmpty()) {
            val app = context.appVersion ?: return Ineligible.MIN_APP_VERSION
            val cmp = compareVersions(app, minVersion) ?: return Ineligible.MIN_APP_VERSION
            if (cmp < 0) return Ineligible.MIN_APP_VERSION
        }

        if (t.newUsersOnly == true) {
            val installed = context.installEpochMs
            if (installed == null || startedAtMs == null || installed < startedAtMs) return Ineligible.NEW_USERS_ONLY
        }

        val conditions = t.userTraits
        if (!conditions.isNullOrEmpty()) {
            // The shared evaluator treats a numeric comparison with a missing / non-numeric side as "equal", so
            // `ltv >= 10` would pass for a user with no `ltv`. Here a comparison needs two numbers.
            for (rule in conditions) {
                if (rule.operator in NUMERIC_OPERATORS &&
                    (ConditionEvaluator.toDoubleOrNull(context.traits[rule.trait]) == null ||
                        ConditionEvaluator.toDoubleOrNull(rule.value) == null)
                ) return Ineligible.USER_TRAITS
            }
            if (!AudienceRuleEvaluator.evaluate(conditions, context.traits)) return Ineligible.USER_TRAITS
        }
        return null
    }

    private val NUMERIC_OPERATORS = setOf("gt", "gte", "lt", "lte")

    /**
     * Dotted versions compared number by number (missing parts = 0; a part's non-numeric suffix is ignored, so
     * "2.1.0-beta" = 2.1.0). null when either side has no number at all. Negative / 0 / positive like compareTo.
     */
    fun compareVersions(a: String, b: String): Int? {
        fun parts(v: String): List<Int>? {
            var sawDigit = false
            val out = v.trim().split(".").map { comp ->
                val digits = comp.takeWhile { it in '0'..'9' }
                if (digits.isNotEmpty()) sawDigit = true
                digits.take(9).toIntOrNull() ?: 0
            }
            return if (sawDigit) out else null
        }
        val pa = parts(a) ?: return null
        val pb = parts(b) ?: return null
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    /**
     * The allocation bucket: `murmur3_32("<experimentId>.<salt>.<userId>.allocation") % 10000`. A different input from
     * the variant bucket (`"<experimentId>.<salt>.<userId>"`), so allocation and variant are independent.
     */
    fun allocationBucket(experimentId: String, salt: String, userId: String): UInt =
        MurmurHash3.hash32("$experimentId.$salt.$userId.allocation") % 10000u

    /**
     * In the allocated share of traffic? `bucket < round(allocation × 10000)`, so a user in at 30 % is still in at
     * 50 %. A doc without `traffic_allocation` (written before it was served) = everyone; a non-finite value = no one.
     */
    fun isAllocated(experimentId: String, salt: String, userId: String, trafficAllocation: Double?): Boolean {
        val allocation = trafficAllocation ?: return true
        if (allocation.isNaN() || allocation.isInfinite()) return false
        val threshold = Math.round(allocation * 10000).coerceIn(0L, 10000L)
        if (threshold >= 10000L) return true
        return allocationBucket(experimentId, salt, userId).toLong() < threshold
    }
}
