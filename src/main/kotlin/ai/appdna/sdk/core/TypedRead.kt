package ai.appdna.sdk.core

/**
 * READS THAT NOTICE WHEN A FIELD IS THE WRONG TYPE.
 *
 * Android parses published config out of `Map<String, Any?>` with `as?` casts. A wrongly-typed
 * field yields `null`, the parser carries on with a default, and nothing anywhere knows a value
 * was lost. iOS knows, because `Codable` THROWS and `FailableDecodable` catches it with a coding
 * path — which is why configuration-issue reporting ended up asymmetric: the platform whose parser is more
 * forgiving is the one that reports less, and a reader would reasonably conclude it has fewer
 * problems.
 *
 * 🔴 ABSENT AND NULL STAY SILENT. They are ordinary states and say nothing: a config that omits
 * `corner_radius` is not broken, it is unstyled. Only a key that is PRESENT, NON-NULL and of the
 * wrong kind is a defect, because that is a value an author set and a device threw away.
 *
 * ```
 * // before — a String here is indistinguishable from the key being absent
 * corner_radius = (d["corner_radius"] as? Number)?.toFloat()
 *
 * // after — same result, and the author hears about it
 * corner_radius = d.num(path, "corner_radius")?.toFloat()
 * ```
 *
 * `path` names the document for the report, e.g. `layout.sections[2].config`. The report goes
 * through [ConfigurationIssueReporter], so it reaches both the host delegate and the
 * `sdk_decode_dropped` event, rate-limited to one per distinct problem per process.
 */
internal object TypedRead {
    /** The surface reported for a paywall document's own fields. */
    const val PAYWALL = "paywall"

    /** The surface reported for an onboarding flow's own fields. */
    const val ONBOARDING = "onboarding_flow"
}

/**
 * Report a present-but-wrong-typed key. Shared by every reader below so the message has one shape.
 *
 * The value's own class is named because "expected a number" alone leaves the author guessing
 * which of their fields is a string — and `"12"` vs `12` is exactly the mistake a hand-edited or
 * generated document makes.
 */
private fun mismatch(surface: String, path: String, key: String, expected: String, actual: Any) {
    ConfigurationIssueReporter.report(
        surface = surface,
        id = null,
        detail = "$path.$key: expected $expected, found ${actual.javaClass.simpleName} ($actual)",
    )
}

/** A string, or null — reporting when the key is present and is something else. */
internal fun Map<String, Any?>.str(surface: String, path: String, key: String): String? {
    val raw = this[key] ?: return null
    if (raw is String) return raw
    mismatch(surface, path, key, "a string", raw)
    return null
}

/**
 * A number, or null — reporting when the key is present and is something else.
 *
 * 🔴 A BOOLEAN IS NOT A NUMBER HERE, even though `true` would widen in some languages. JSON tells
 * them apart and so does the console, so a `true` under `corner_radius` is an authoring mistake
 * worth hearing about rather than a 1.
 */
internal fun Map<String, Any?>.num(surface: String, path: String, key: String): Number? {
    val raw = this[key] ?: return null
    if (raw is Number && raw !is Boolean) return raw
    mismatch(surface, path, key, "a number", raw)
    return null
}

/** A boolean, or null — reporting when the key is present and is something else. */
internal fun Map<String, Any?>.bool(surface: String, path: String, key: String): Boolean? {
    val raw = this[key] ?: return null
    if (raw is Boolean) return raw
    mismatch(surface, path, key, "a boolean", raw)
    return null
}

/**
 * A list, or null — reporting when the key is present and is something else.
 *
 * This is the one that once cost a whole document on iOS: a survey held a DICTIONARY
 * under `questions[0].options` where the DTO declared an array. Android has always read `as? List`
 * and got null; now it says so.
 */
internal fun Map<String, Any?>.list(surface: String, path: String, key: String): List<*>? {
    val raw = this[key] ?: return null
    if (raw is List<*>) return raw
    mismatch(surface, path, key, "a list", raw)
    return null
}

/**
 * A string, COERCING a number or a boolean — for the handful of fields iOS coerces too.
 *
 * 🔴 WHY THIS EXISTS, AND WHY IT IS NOT THE DEFAULT. iOS's `PaywallPlan.looseString` turns `9.99`
 * into `"9.99"` on five plan fields, because its `Codable` is all-or-nothing and one numeric price
 * used to nil the WHOLE paywall. Android's `as? String` returned null instead and the paywall
 * rendered — with an empty price. So the two platforms disagreed about what a plan carrying
 * `"price": 9.99` costs: iOS drew `9.99`, Android drew NOTHING, and the shared fixtures have
 * carried exactly that document since `purchase_completed_event` was written.
 *
 * `str` stays strict everywhere else. A coerced scalar is NOT reported, on purpose: coercion is the
 * defined behaviour of these five fields on both platforms, so the value is not lost and there is
 * nothing for an author to fix. Only a map or a list — which neither platform can render as text —
 * is reported.
 *
 * The integral-double rule is iOS's, to the digit: `9.0` must give `"9"` and not `"9.0"`, which is
 * what Android's previous ad-hoc `it.toString()` produced on the two fields it already coerced.
 */
internal fun Map<String, Any?>.looseStr(surface: String, path: String, key: String): String? {
    val raw = this[key] ?: return null
    return when (raw) {
        is String -> raw
        is Boolean -> if (raw) "true" else "false"
        is Double, is Float -> {
            val d = (raw as Number).toDouble()
            // Swift: `if d == d.rounded(), abs(d) < 1e15 { String(Int64(d)) } else { String(d) }`
            if (!d.isNaN() && !d.isInfinite() && d == kotlin.math.floor(d) && kotlin.math.abs(d) < 1e15) {
                d.toLong().toString()
            } else {
                d.toString()
            }
        }
        is Number -> raw.toString()
        else -> {
            mismatch(surface, path, key, "a string", raw)
            null
        }
    }
}

/** A nested object, or null — reporting when the key is present and is something else. */
internal fun Map<String, Any?>.obj(surface: String, path: String, key: String): Map<*, *>? {
    val raw = this[key] ?: return null
    if (raw is Map<*, *>) return raw
    mismatch(surface, path, key, "an object", raw)
    return null
}

/**
 * A nested object typed as the parsers want it — the drop-in for `as? Map<String, Any>`.
 *
 * 🔴 WHY A SECOND OBJECT READER. `obj` returns `Map<*, *>`, which is honest about erasure but is
 * NOT what the call sites hold: they read a sub-object and index it by name, so `Map<*, *>` makes
 * every nested read fail to compile and pushes a cast back to each one. That is how the first pass
 * of this migration stalled at the container reads while 775 scalar reads went through.
 *
 * The cast here is exactly as unchecked as the `as? Map<String, Any>` it replaces — JSON values are
 * `Any?` at runtime whatever the declaration says — so this changes nothing but the reporting. The
 * suppression is deliberate and local rather than a file-wide one, so a genuinely new unchecked
 * cast elsewhere still stands out.
 */
@Suppress("UNCHECKED_CAST")
internal fun Map<String, Any?>.objMap(surface: String, path: String, key: String): Map<String, Any>? =
    obj(surface, path, key) as? Map<String, Any>

/**
 * A list of objects, typed — the drop-in for `as? List<Map<String, Any>>`.
 *
 * Same erasure caveat as [objMap], and the same reason for existing: `sections`, `steps` and
 * `plans` are all read this way, and `List<*>` would not compile at those call sites. A list whose
 * ELEMENTS are not objects is not reported here, because it was not reported before either — the
 * element cast was always erased, and the parsers already skip a non-map element one by one.
 */
@Suppress("UNCHECKED_CAST")
internal fun Map<String, Any?>.listOfMaps(
    surface: String,
    path: String,
    key: String,
): List<Map<String, Any>>? = list(surface, path, key) as? List<Map<String, Any>>
