package ai.appdna.sdk.onboarding

import ai.appdna.sdk.generated.BlockKeyTypes
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.util.IdentityHashMap

/*
 * The Android implementation of the RAW host-data pass.
 *
 * A line-for-line port of the console's reference resolver `src/lib/onboarding/host-data-resolve.ts`:
 * same rules, same order, same unresolved-token behaviour. One raw content block (plus the step's
 * `localizations`) in, the resolved block JSON out — BEFORE typed decode. The shared `resolve_block`
 * fixtures run this exact object on all three implementations, so the console, iOS and Android
 * cannot drift.
 *
 * ## The pass, in order
 *
 *   deep copy + id stamping → skip rule → bindings (every block depth; allowlist by generated key
 *   class) → strip authored markers → repeat expansion (top-level `input_select` only) → ONE
 *   structural pass (inline tokens + `data_templates`, every string at every depth, excluded keys
 *   skipped, binding-written keys skipped, substituted values never re-scanned) → option drop / hide /
 *   dedupe → summary-stat placeholder rules → markers (`resolve_state`, `empty_state`,
 *   `sheet_step_paths`) → the block's `block.<id>.*` localizations.
 *
 * ## The one native difference
 *
 * Tokens whose root is NOT a block root (`device`, `computed`, `remote_config`, `input`, bare names)
 * go through `TemplateEngine`'s existing per-token variable resolver ([HostDataContext.legacyToken]),
 * with its semantics: the `| fallback` on a miss, else "". The console has no TemplateEngine and keeps
 * them as authored; that is why no `console` fixture contains one.
 *
 * Pure functions over plain JSON (`Map` / `List` / String / Number / Boolean / null). Never mutates
 * its input — the raw maps are shared with the config cache.
 *
 * The key classes come ONLY from the generated [BlockKeyTypes] (`scripts/check-template-reach.ts`).
 */

/** The JSON object shape the pass works on. Always a fresh, mutable deep copy. */
internal typealias JsonObj = MutableMap<String, Any?>

/** `{ "<locale>": { "block.<id>.<key>": "<value>" } }` — `StepConfig.localizations`. */
internal typealias StepLocalizations = Map<String, Map<String, String>>

/** Inputs of one resolve. Mirrors the TS `HostDataContext` (strict mode only — natives never preview). */
internal data class HostDataContext(
    /** The step id; with [blockIndex] it drives id stamping ("Stable ids"). */
    val stepId: String = "fixture_step",
    /** The block's index in the step's top-level block array. */
    val blockIndex: Int = 0,
    val hookData: Map<String, Any?>? = null,
    val responses: Map<String, Any?>? = null,
    /** The current step's live input values (`{{step.<field_id>}}`). */
    val step: Map<String, Any?>? = null,
    val user: Map<String, Any?>? = null,
    val session: Map<String, Any?>? = null,
    /** `SelectedOptionStore` snapshot (`{{selected.<field_id>.label}}`). */
    val selected: Map<String, Any?>? = null,
    /** "Host data pending" — the step's delegate call is still in flight. */
    val pending: Boolean = false,
    val localizations: StepLocalizations? = null,
    /** Device locale. The pass resolves every locale's values; this is carried for callers. */
    val locale: String? = null,
    /**
     * `TemplateEngine`'s per-token variable resolver for non-block roots. Returns null on a
     * miss (the caller then applies the `| fallback`, else ""). Null = no TemplateEngine (tokens stay
     * as authored, the console behaviour).
     */
    val legacyToken: ((path: String) -> String?)? = null,
)

internal data class HostDataResolveResult(
    /** The resolved block JSON — what `decodeContentBlock` receives. */
    val block: JsonObj,
    /** This block's `block.<id>.*` localization entries after the pass (null when the step has none). */
    val localizations: Map<String, Map<String, String>>?,
    /** True when the skip rule applied — the block is the stamped raw block, untouched. */
    val skipped: Boolean,
    /** Debug log lines. */
    val log: List<String>,
    /**
     * The block right after bindings / marker strip / repeat expansion and BEFORE the structural pass
     * what a changed key reverts to when the typed decode rejects a resolved value ("No
     * whole-block failure"). Null when [skipped].
     */
    val postExpansion: JsonObj? = null,
    /** Keys a binding wrote on the top-level block — those revert to the authored raw value instead. */
    val boundKeys: Set<String> = emptySet(),
    /** The stamped authored block (before any pass step) — the last-resort decode input. */
    val stamped: JsonObj,
)

internal object HostDataResolver {

    /** The roots the block resolver owns. `item` / `index` exist only inside a repeat template. */
    val BLOCK_ROOTS: Set<String> = setOf("responses", "hook_data", "step", "selected", "user", "session", "item", "index")
    private val EXCLUDED: Set<String> = BlockKeyTypes.excludedKeys
    private val MARKERS: Set<String> = BlockKeyTypes.internalMarkers
    /** The only key classes a `bindings` entry may target. */
    private val BINDABLE_CLASSES = setOf("string", "number", "bool", "string_list")
    /** Option-bearing inputs ("Select"). */
    val OPTION_BEARING_TYPES: Set<String> = setOf("input_select", "input_chips", "input_segmented")
    /** Keys whose array elements are content blocks (order matters: it is the id-stamping order). */
    private val BLOCK_LIST_KEYS = listOf("children", "stack_children")
    /** The repeat cap. */
    const val REPEAT_DEFAULT_MAX = 20
    const val REPEAT_HARD_MAX = 50

    /** The token pattern — identical to iOS / the console (hyphenated ids, `| fallback`). */
    private val TOKEN_RE = Regex("""\{\{\s*([a-zA-Z0-9_.\-]+)\s*(?:\|\s*([^}]*?)\s*)?\}\}""")

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // Small helpers
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    /** A deep, mutable copy of a JSON value. Maps become `LinkedHashMap<String, Any?>` (order kept). */
    fun deepCopy(v: Any?): Any? = when (v) {
        is Map<*, *> -> {
            val out = LinkedHashMap<String, Any?>(v.size)
            for ((k, value) in v) out[k.toString()] = deepCopy(value)
            out
        }
        is List<*> -> v.mapTo(ArrayList(v.size)) { deepCopy(it) }
        is Array<*> -> v.mapTo(ArrayList(v.size)) { deepCopy(it) }
        else -> v
    }

    @Suppress("UNCHECKED_CAST")
    fun deepCopyObj(v: Map<*, *>): JsonObj = deepCopy(v) as JsonObj

    @Suppress("UNCHECKED_CAST")
    private fun obj(v: Any?): JsonObj? = v as? MutableMap<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun list(v: Any?): MutableList<Any?>? = v as? MutableList<Any?>

    /** Every JSON key ending `_url` is an image/media URL key; `gallery_images` elements too. */
    fun isUrlKey(key: String): Boolean = key.endsWith("_url")

    data class TemplateToken(
        val raw: String,
        val path: String,
        val root: String,
        /** The `| fallback`, verbatim, or null when none was written. */
        val fallback: String?,
        val index: Int,
    )

    /** Every `{{…}}` token in [text], in order. */
    fun extractTemplateTokens(text: String): List<TemplateToken> =
        TOKEN_RE.findAll(text).map { m ->
            val path = m.groupValues[1]
            TemplateToken(
                raw = m.value,
                path = path,
                root = path.split('.')[0],
                fallback = m.groups[2]?.value,
                index = m.range.first,
            )
        }.toList()

    /**
     * "Stringification" — identical on all three implementations. Strings as-is; `true`/`false`;
     * integral numbers below 2^53 without a decimal part; every other number in plain decimal notation
     * (never exponent form) with at most 15 significant digits and trailing zeros trimmed. `null`,
     * objects and arrays have no string form → null (= unresolved).
     */
    fun stringifyTemplateValue(v: Any?): String? = when (v) {
        is String -> v
        is Boolean -> if (v) "true" else "false"
        is Number -> formatNumber(v)
        else -> null
    }

    private const val TWO_POW_53 = 9007199254740992.0

    private fun formatNumber(n: Number): String? {
        if (n is Int || n is Long || n is Short || n is Byte) {
            val l = n.toLong()
            if (kotlin.math.abs(l.toDouble()) < TWO_POW_53) return l.toString()
        }
        if (n is BigDecimal) return formatDouble(n.toDouble())
        return formatDouble(n.toDouble())
    }

    private fun formatDouble(d: Double): String? {
        if (!d.isFinite()) return null
        if (d == 0.0) return "0" // also -0
        if (d == Math.floor(d) && kotlin.math.abs(d) < TWO_POW_53) return d.toLong().toString()
        val neg = d < 0
        val bd = BigDecimal(kotlin.math.abs(d)).round(MathContext(15, RoundingMode.HALF_UP)).stripTrailingZeros()
        var s = bd.toPlainString()
        if (s.isEmpty()) s = "0"
        return if (neg && s != "0") "-$s" else s
    }

    /**
     * A template-resolved URL must be an absolute `https` URL: scheme `https` (case-insensitive)
     * followed by `://`, a non-empty authority, and only RFC 3986 characters anywhere.
     *
     * NO `IGNORE_CASE`: Kotlin/JVM turns it into CASE_INSENSITIVE | UNICODE_CASE, which folds `ſ`
     * (U+017F) to `s` and `K` (U+212A) to `k` — TS (`/i` without `u`) does not. The classes already
     * list both ASCII cases; the scheme spells them out.
     */
    private val URL_RE = Regex(
        """[Hh][Tt][Tt][Pp][Ss]://[A-Za-z0-9\-._~%!$&'()*+,;=:@\[\]]+(?:[/?#][A-Za-z0-9\-._~:/?#\[\]@!$&'()*+,;=%]*)?""",
    )

    fun isValidTemplatedUrl(s: String): Boolean = URL_RE.matches(s)

    private const val PATH_SAFE = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~!$&'()*+,;=:@"
    private const val QUERY_SAFE = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~!$'()*,;:@/?"

    /**
     * Percent-encode a value substituted INSIDE a longer URL. `path`: RFC 3986 `pchar` with `/`
     * encoded; `query`: the query component minus `&`, `=`, `+` (and `#`). UTF-8, upper-case hex.
     */
    fun percentEncodeUrlComponent(value: String, query: Boolean): String {
        val safe = if (query) QUERY_SAFE else PATH_SAFE
        val out = StringBuilder()
        var i = 0
        while (i < value.length) {
            val cp = value.codePointAt(i)
            val len = Character.charCount(cp)
            if (len == 1 && safe.indexOf(cp.toChar()) >= 0) {
                out.append(cp.toChar())
            } else {
                for (b in String(Character.toChars(cp)).toByteArray(Charsets.UTF_8)) {
                    out.append('%').append(String.format(java.util.Locale.ROOT, "%02X", b.toInt() and 0xFF))
                }
            }
            i += len
        }
        return out.toString()
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // Scope and path resolution
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    internal class ItemScope(val value: Any?, val index: Int)

    internal class Scope(val roots: Map<String, Any?>, val item: ItemScope? = null) {
        fun withItem(i: ItemScope) = Scope(roots, i)
        fun withStep(step: Map<String, Any?>) = Scope(roots + ("step" to step), item)
    }

    private fun rootScope(ctx: HostDataContext) = Scope(
        mapOf(
            "responses" to ctx.responses,
            "hook_data" to ctx.hookData,
            "step" to ctx.step,
            "selected" to ctx.selected,
            "user" to ctx.user,
            "session" to ctx.session,
        ),
    )

    /**
     * Resolve a dot path against a scope: any depth, numeric array index, miss → null. Two or
     * more segments are required except for exactly `item` and `index`.
     */
    internal fun resolveScopePath(path: String, scope: Scope): Any? {
        val parts = path.split('.')
        val root = parts[0]
        var current: Any?
        when (root) {
            "item" -> {
                val item = scope.item ?: return null
                current = item.value
            }
            "index" -> {
                val item = scope.item ?: return null
                if (parts.size != 1) return null
                return item.index
            }
            else -> {
                if (parts.size < 2 || root !in BLOCK_ROOTS) return null
                current = scope.roots[root]
            }
        }
        for (part in parts.drop(1)) {
            current = when {
                current is Map<*, *> && current.containsKey(part) -> current[part]
                current is List<*> && part.isNotEmpty() && part.all { it in '0'..'9' } -> {
                    val idx = part.toIntOrNull() ?: return null
                    if (idx < current.size) current[idx] else return null
                }
                else -> return null
            }
        }
        return current
    }

    /**
     * Convert a bound value into the class of the ContentBlock key it targets (the sheet
     * renderer's deferred `step` bindings use this too). Null = does not fit / not bindable.
     */
    fun convertBindingValue(prop: String, v: Any?): Any? {
        if (v == null || prop in EXCLUDED || prop in MARKERS) return null
        return when (keyClass("ContentBlock", prop)) {
            "string" -> stringifyTemplateValue(v)
            "number" -> v as? Number
            "bool" -> v as? Boolean
            "string_list" -> (v as? List<*>)?.let { l ->
                val els = l.map { stringifyTemplateValue(it) }
                if (els.all { it != null }) els.toMutableList<Any?>() else null
            }
            else -> null
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // String resolution
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    private class StringResult(
        var value: String,
        val hadToken: Boolean,
        /** At least one token resolved to nothing and had no `| fallback`. */
        var unresolved: Boolean = false,
        /** …and at least one of those was a `hook_data` token. */
        var unresolvedHookData: Boolean = false,
        /** A `step` token was left for the sheet renderer (inside `sheet_blocks`). */
        var deferredStep: Boolean = false,
    )

    private fun resolveString(
        text: String,
        scope: Scope,
        url: Boolean,
        deferStep: Boolean,
        legacy: ((String) -> String?)?,
    ): StringResult {
        val tokens = extractTemplateTokens(text)
        val res = StringResult(value = text, hadToken = tokens.isNotEmpty())
        if (tokens.isEmpty()) return res
        val whole = tokens.size == 1 && tokens[0].raw == text
        val firstQuery = text.indexOf('?')
        val out = StringBuilder()
        var last = 0
        for (t in tokens) {
            out.append(text, last, t.index)
            last = t.index + t.raw.length
            if (deferStep && t.root == "step") {
                out.append(t.raw); res.deferredStep = true; continue
            }
            val encodeQuery = firstQuery >= 0 && firstQuery < t.index
            if (t.root !in BLOCK_ROOTS) {
                // TemplateEngine's per-token resolver, same semantics as today: fallback, else "".
                if (legacy == null) { out.append(t.raw); continue }
                val v = legacy(t.path) ?: t.fallback ?: ""
                out.append(if (url && !whole) percentEncodeUrlComponent(v, encodeQuery) else v)
                continue
            }
            val s = stringifyTemplateValue(resolveScopePath(t.path, scope))
            if (s != null) {
                out.append(if (url && !whole) percentEncodeUrlComponent(s, encodeQuery) else s)
            } else if (t.fallback != null) {
                out.append(t.fallback)
            } else {
                res.unresolved = true
                if (t.root == "hook_data") res.unresolvedHookData = true
            }
        }
        out.append(text, last, text.length)
        res.value = out.toString()
        return res
    }

    /**
     * `fieldConfigPatches` values are resolved with the same structural walker as a block's
     * `field_config` before merging. Returns a resolved copy; a `*_url` key the rule rejects is
     * absent from it (so the authored value stays).
     */
    internal fun resolveFieldConfigPatch(patch: Map<String, Any?>, blockType: String, ctx: HostDataContext): Map<String, Any?> {
        val copy = deepCopyObj(patch)
        RawPass(ctx).walkFieldConfigPatch(copy, blockType, rootScope(ctx))
        return copy
    }

    /** One inline string resolved in block scope, no URL rules — the step localizations' walker. */
    internal fun resolvePlainString(text: String, ctx: HostDataContext): String =
        resolveString(text, rootScope(ctx), url = false, deferStep = false, legacy = ctx.legacyToken).value

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // Id stamping and the skip rule
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    /** Visit every block object in a block tree (the block, children, stack_children, option sheet_blocks). */
    private fun forEachBlock(
        block: JsonObj,
        path: String,
        inSheet: Boolean = false,
        visit: (b: JsonObj, path: String, inSheet: Boolean) -> Unit,
    ) {
        visit(block, path, inSheet)
        for (key in BLOCK_LIST_KEYS) {
            val l = block[key] as? List<*> ?: continue
            l.forEachIndexed { j, child -> obj(child)?.let { forEachBlock(it, "$path/$key/$j", inSheet, visit) } }
        }
        val opts = block["field_options"] as? List<*>
        opts?.forEachIndexed { k, opt ->
            val o = opt as? Map<*, *> ?: return@forEachIndexed
            val sheet = o["sheet_blocks"] as? List<*> ?: return@forEachIndexed
            sheet.forEachIndexed { m, sb ->
                obj(sb)?.let { forEachBlock(it, "$path/field_options/$k/sheet_blocks/$m", true, visit) }
            }
        }
    }

    /**
     * "Stable ids" — stamp every id-less block at any depth with `<stepId>/<index path>` (the
     * top-level array key omitted: `step8/2/children/0`, `step8/3/field_options/1/sheet_blocks/0`).
     * Returns a deep copy; the input is untouched.
     */
    fun stampBlockIds(raw: Map<*, *>, stepId: String, blockIndex: Int): JsonObj {
        val copy = deepCopyObj(raw)
        forEachBlock(copy, "$stepId/$blockIndex") { b, path, _ -> if (b["id"] == null) b["id"] = path }
        return copy
    }

    /**
     * Strip SDK-internal markers (`resolve_state`, `empty_state`, `sheet_step_paths`) from every block's
     * `field_config` at any depth, in place. Authored values are never trusted ("Required gate
     * input") — also applied at raw capture so a block that skips the pass never carries one.
     */
    fun stripMarkers(block: JsonObj) {
        forEachBlock(block, "") { b, _, _ -> obj(b["field_config"])?.let { fc -> MARKERS.forEach { fc.remove(it) } } }
    }

    /** Every block id in the tree (after stamping), with its block, in visit order. */
    private fun blockIndex(block: JsonObj): LinkedHashMap<String, JsonObj> {
        val out = LinkedHashMap<String, JsonObj>()
        forEachBlock(block, "") { b, _, _ -> (b["id"] as? String)?.let { out[it] = b } }
        return out
    }

    private data class LocEntry(val locale: String, val key: String, val id: String)

    private fun locKeysOf(localizations: StepLocalizations?, ids: Collection<String>): List<LocEntry> {
        val out = mutableListOf<LocEntry>()
        if (localizations == null) return out
        for ((locale, map) in localizations) {
            @Suppress("USELESS_CAST")
            val m = (map as Any?) as? Map<*, *> ?: continue
            for ((k, value) in m) {
                val key = k as? String ?: continue
                if (value !is String) continue
                val id = ids.firstOrNull { key.startsWith("block.$it.") } ?: continue
                out += LocEntry(locale, key, id)
            }
        }
        return out
    }

    private fun containsNeedle(v: Any?): Boolean = when (v) {
        is String -> v.contains("{{") || v.contains("data_templates") || v.contains("bindings") || v.contains("repeat")
        is Map<*, *> -> v.entries.any { (k, value) -> containsNeedle(k?.toString()) || containsNeedle(value) }
        is List<*> -> v.any { containsNeedle(it) }
        else -> false
    }

    /**
     * "Skip rule (the only one)" — a block skips the pass only if its raw JSON contains none of
     * `{{`, `data_templates`, `bindings`, `repeat`, and none of its (or a descendant's) `block.<id>.*`
     * localization values contains `{{`.
     */
    fun blockSkipsRawPass(stampedBlock: JsonObj, localizations: StepLocalizations?): Boolean {
        if (containsNeedle(stampedBlock)) return false
        val ids = blockIndex(stampedBlock).keys
        for (e in locKeysOf(localizations, ids)) {
            if (localizations!![e.locale]!![e.key]!!.contains("{{")) return false
        }
        return true
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // Owner descriptors — which generated key list classes a key
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    private sealed class Owner {
        data class Type(val name: String) : Owner()
        data class FieldConfig(val blockType: String, val prefix: String) : Owner()
        object Unknown : Owner()
    }

    private fun keyClass(owner: String, key: String): String? = BlockKeyTypes.ownerKeyClasses[owner]?.get(key)

    private fun fieldConfigKeyClass(blockType: String, path: String): String? =
        BlockKeyTypes.fieldConfigKeyClasses[blockType]?.get(path) ?: BlockKeyTypes.fieldConfigKeyClasses["*"]?.get(path)

    private fun classOf(owner: Owner, key: String): String? = when (owner) {
        is Owner.Type -> keyClass(owner.name, key)
        is Owner.FieldConfig -> fieldConfigKeyClass(owner.blockType, owner.prefix + key)
        Owner.Unknown -> null
    }

    private fun childOwnerOf(owner: Owner, key: String): Owner = when (owner) {
        is Owner.Type -> BlockKeyTypes.ownerKeyChildTypes[owner.name]?.get(key)?.let { Owner.Type(it) } ?: Owner.Unknown
        is Owner.FieldConfig -> Owner.FieldConfig(owner.blockType, "${owner.prefix}$key[].")
        Owner.Unknown -> Owner.Unknown
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // The pass
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    private val OPTION_PATH_RE = Regex("""^field_options\.(\d+)(\..*)?$""")

    private data class SheetStepPath(val path: String, val kind: String, val source: String? = null)

    private class OptionState(
        val generated: Boolean,
        var labelUnresolved: Boolean = false,
        var labelUnresolvedHookData: Boolean = false,
        var valueUnresolved: Boolean = false,
    )

    private class WalkEnv(
        val block: JsonObj,
        val blockType: String,
        val inSheet: Boolean,
        val path: List<Any>,
        val inField: Boolean = false,
    ) {
        fun with(path: List<Any> = this.path, inField: Boolean = this.inField) = WalkEnv(block, blockType, inSheet, path, inField)
    }

    private class RawPass(private val ctx: HostDataContext) {
        val log = mutableListOf<String>()
        /** Keys a binding wrote, per object — the structural pass skips them (single pass). */
        val bound = IdentityHashMap<Any, MutableSet<String>>()
        private val sheetPaths = IdentityHashMap<Any, MutableList<SheetStepPath>>()
        private val optionState = IdentityHashMap<Any, OptionState>()
        private val itemScope = IdentityHashMap<Any, ItemScope>()
        private val unresolvedKeys = IdentityHashMap<Any, MutableSet<String>>()
        /** Localization keys a `data_templates` result displaced ("Translations"). */
        val displacedLocKeys = mutableSetOf<String>()
        /** Count of options the hide rule removed, per block. */
        val hiddenCount = IdentityHashMap<Any, Int>()

        // ── Bindings ───────────────────────────────────────────────────────────────────────
        fun applyBindings(block: JsonObj, scope: Scope, inSheet: Boolean) {
            val bindings = block["bindings"] as? Map<*, *> ?: return
            for ((p, pathV) in bindings) {
                val prop = p?.toString() ?: continue
                if (pathV !is String) continue
                if (prop in EXCLUDED || prop in MARKERS) {
                    log += "binding ${block["id"]}.$prop: excluded/marker target — skipped"; continue
                }
                val cls = keyClass("ContentBlock", prop)
                if (cls == null || cls !in BINDABLE_CLASSES) {
                    log += "binding ${block["id"]}.$prop: target class ${cls ?: "unknown"} not bindable — skipped"; continue
                }
                val root = pathV.split('.')[0]
                if (inSheet && root == "step") {
                    addSheetPath(block, SheetStepPath(prop, "binding", pathV)); continue
                }
                val v = resolveScopePath(pathV, scope) ?: continue
                val converted: Any? = when (cls) {
                    "string" -> stringifyTemplateValue(v)
                    "number" -> if (v is Number) v else null
                    "bool" -> if (v is Boolean) v else null
                    "string_list" -> (v as? List<*>)?.let { l ->
                        val els = l.map { stringifyTemplateValue(it) }
                        if (els.all { it != null }) els.toMutableList<Any?>() else null
                    }
                    else -> null
                }
                if (converted == null) {
                    log += "binding ${block["id"]}.$prop: value does not fit class $cls — skipped"; continue
                }
                block[prop] = converted
                bound.getOrPut(block) { mutableSetOf() }.add(prop)
            }
        }

        /** Deep-copy a repeat template WITH the per-object state bindings recorded on it. */
        fun cloneWithState(orig: Any?): Any? {
            val copy = deepCopy(orig)
            fun carry(a: Any?, b: Any?) {
                if (a is List<*> && b is List<*>) { a.forEachIndexed { i, x -> carry(x, b.getOrNull(i)) }; return }
                if (a !is Map<*, *> || b !is Map<*, *>) return
                bound[a]?.let { bound[b] = it.toMutableSet() }
                sheetPaths[a]?.let { sheetPaths[b] = it.map { p -> p.copy() }.toMutableList() }
                for (k in a.keys) if (b.containsKey(k)) carry(a[k], b[k])
            }
            carry(orig, copy)
            return copy
        }

        /**
         * "Sheet ids" — deferred `step` paths inside a sheet block's options are recorded as
         * `field_options.<i>.…` while walking, i.e. BEFORE the hide rule / dedupe settle the list.
         * Re-point each to its option's settled index (options matched by identity); an option that was
         * removed takes its paths with it. Mirrors the TS `remapOptionSheetPaths`.
         */
        private fun remapOptionSheetPaths(block: JsonObj, before: List<Any?>) {
            val paths = sheetPaths[block]
            if (paths.isNullOrEmpty()) return
            val after = (block["field_options"] as? List<*>).orEmpty()
            val next = mutableListOf<SheetStepPath>()
            for (p in paths) {
                val m = OPTION_PATH_RE.matchEntire(p.path)
                if (m == null) { next += p; continue }
                val oldIdx = m.groupValues[1].toIntOrNull()
                val opt = if (oldIdx != null) before.getOrNull(oldIdx) else null
                if (opt == null) continue
                val newIdx = after.indexOfFirst { it === opt }
                if (newIdx < 0) continue
                next += p.copy(path = "field_options.$newIdx${m.groupValues[2]}")
            }
            sheetPaths[block] = next
        }

        private fun addSheetPath(block: Any, entry: SheetStepPath) {
            sheetPaths.getOrPut(block) { mutableListOf() }.add(entry)
        }

        // ── Repeat expansion ───────────────────────────────────────────────────────────────
        /** Returns true when the block's options came from a repeat. */
        fun expandRepeat(block: JsonObj, scope: Scope): Boolean {
            if (block["type"] != "input_select") return false
            val fc = block["field_config"] as? Map<*, *> ?: return false
            val rep = fc["repeat"] as? Map<*, *> ?: return false
            val osid = fc["option_set_id"]
            if (osid is String && osid.trim().isNotEmpty()) {
                log += "repeat ${block["id"]}: Option Set present — Option Set wins, repeat ignored"
                return false
            }
            val options: List<Any?> = (block["field_options"] as? List<*>) ?: emptyList<Any?>()
            val tid = rep["template_option_id"]
            val template = if (tid is String) options.firstOrNull { it is Map<*, *> && it["id"] == tid } else null
            val extras = options.filter { it !== template }
            val rawMax = rep["max"]
            val max = if (rawMax is Number && isInteger(rawMax) && rawMax.toDouble() > 0) {
                minOf(rawMax.toDouble(), REPEAT_HARD_MAX.toDouble()).toInt()
            } else REPEAT_DEFAULT_MAX
            val source = rep["source"] as? String ?: ""
            val root = source.split('.')[0]
            val arr = if (root == "item" || root == "index") null else resolveScopePath(source, scope)
            val generated = mutableListOf<Any?>()
            if (template is Map<*, *>) {
                if (arr is List<*>) {
                    if (arr.size > max) {
                        // Items past `max` are silently not rendered; say so (once per
                        // raw pass, which runs only when the block's inputs — the source included — change).
                        val line = "repeat ${block["id"]}: source $source has ${arr.size} items, max is $max — items past $max are not rendered"
                        log += line
                        ai.appdna.sdk.Log.debug { "Host data: $line" }
                    }
                    arr.take(max).forEachIndexed { i, el ->
                        val copy = obj(cloneWithState(template))!!
                        copy["id"] = "${tid as String}__$i"
                        itemScope[copy] = ItemScope(el, i)
                        optionState[copy] = OptionState(generated = true)
                        generated += copy
                    }
                } else {
                    log += "repeat ${block["id"]}: source $source is not an array — 0 generated options"
                }
            } else {
                log += "repeat ${block["id"]}: template_option_id $tid not found — 0 generated options"
            }
            block["field_options"] = (generated + extras).toMutableList()
            return true
        }

        private fun isInteger(n: Number): Boolean {
            val d = n.toDouble()
            return d.isFinite() && d == Math.floor(d)
        }

        /**
         * An ElementInteractionResult `field_config` patch, walked exactly as the block's own
         * `field_config` is (FieldConfig owner of [blockType], inside a field): same key classes, same
         * URL rule, `data_templates`, single pass. Mutates [patch].
         */
        fun walkFieldConfigPatch(patch: JsonObj, blockType: String, scope: Scope) {
            val holder: JsonObj = linkedMapOf<String, Any?>("type" to blockType, "field_config" to patch)
            walkObject(patch, Owner.FieldConfig(blockType, ""), scope, WalkEnv(holder, blockType, false, listOf("field_config"), inField = true))
        }

        // ── Structural pass ──────────────────────────────────────────────────────────────────────
        fun walkBlock(block: JsonObj, scope: Scope, inSheet: Boolean) {
            val blockType = block["type"] as? String ?: ""
            // Carry-over — summary_stats `default` seeds the `step` root under the live values.
            val stats = ((block["field_config"] as? Map<*, *>)?.get("summary_stats") as? List<*>).orEmpty()
            var blockScope = scope
            val seeds = LinkedHashMap<String, Any?>()
            val liveStep = scope.roots["step"] as? Map<*, *>
            for (s in stats) {
                val m = s as? Map<*, *> ?: continue
                val fid = m["field_id"] as? String ?: continue
                if (fid.isEmpty() || !m.containsKey("default")) continue
                if (liveStep == null || !liveStep.containsKey(fid)) seeds[fid] = m["default"]
            }
            if (seeds.isNotEmpty()) {
                val merged = LinkedHashMap<String, Any?>(seeds)
                liveStep?.forEach { (k, v) -> merged[k.toString()] = v }
                blockScope = scope.withStep(merged)
            }
            walkObject(block, Owner.Type("ContentBlock"), blockScope, WalkEnv(block, blockType, inSheet, emptyList()))

            // Options: drop / hide / dedupe.
            val beforeOptions = block["field_options"] as? List<*>
            if (beforeOptions != null) {
                val before = beforeOptions.toList()
                settleOptions(block)
                remapOptionSheetPaths(block, before)
            }
            // Summary stats: the placeholder rules.
            val fc = obj(block["field_config"])
            val ss = fc?.get("summary_stats") as? List<*>
            if (fc != null && ss != null) {
                fc["summary_stats"] = ss.map { s -> if (s is Map<*, *>) sanitizeStat(obj(s)!!) else s }
                    .filter { it != null }.toMutableList()
            }
        }

        private fun walkObject(o: JsonObj, owner: Owner, scopeIn: Scope, env: WalkEnv) {
            var scope = scopeIn
            itemScope[o]?.let { scope = scope.withItem(it) }
            val boundKeys = bound[o]
            val templates = o["data_templates"]
            o.remove("data_templates")

            for (key in ArrayList(o.keys)) {
                if (key in EXCLUDED || boundKeys?.contains(key) == true) continue
                if (env.inField && key in MARKERS) continue
                if (!o.containsKey(key)) continue
                val v = o[key]
                val path = env.path + key
                when (v) {
                    is String -> resolveKey(o, key, v, scope, env, path)
                    is List<*> -> {
                        if (o === env.block && key in BLOCK_LIST_KEYS) {
                            for (child in v) obj(child)?.let { walkBlock(it, scope, env.inSheet) }
                            continue
                        }
                        if (o === env.block && key == "field_options") {
                            v.forEachIndexed { i, opt ->
                                val om = obj(opt) ?: return@forEachIndexed
                                if (!optionState.containsKey(om)) optionState[om] = OptionState(generated = false)
                                walkObject(om, Owner.Type("InputOption"), scope, env.with(path = path + i, inField = false))
                            }
                            continue
                        }
                        if (key == "sheet_blocks" && owner is Owner.Type && owner.name == "InputOption") {
                            for (sb in v) obj(sb)?.let { walkBlock(it, scope, true) }
                            continue
                        }
                        val elOwner = childOwnerOf(owner, key)
                        val kept = mutableListOf<Any?>()
                        var changed = false
                        v.forEachIndexed { i, el ->
                            if (el is String) {
                                val r = resolveListString(el, key, scope, env, path + i)
                                if (r == null) { changed = true; return@forEachIndexed }
                                if (r != el) changed = true
                                kept += r
                            } else {
                                obj(el)?.let { walkObject(it, elOwner, scope, env.with(path = path + i)) }
                                kept += el
                            }
                        }
                        if (changed) {
                            if (kept.isEmpty() && v.isNotEmpty() && key == "gallery_images") o.remove(key)
                            else o[key] = kept
                        }
                    }
                    is Map<*, *> -> {
                        val isFc = o === env.block && key == "field_config"
                        val childOwnerDesc = if (isFc) Owner.FieldConfig(env.blockType, "") else childOwnerOf(owner, key)
                        obj(v)?.let { walkObject(it, childOwnerDesc, scope, env.with(path = path, inField = if (isFc) true else env.inField)) }
                    }
                    else -> {}
                }
            }

            // data_templates — after the plain keys, so a winning template replaces the fallback.
            if (templates is Map<*, *>) applyDataTemplates(o, templates, owner, scope, env, boundKeys)
        }

        private fun resolveKey(o: JsonObj, key: String, v: String, scope: Scope, env: WalkEnv, path: List<Any>) {
            if (!v.contains("{{")) return
            val url = isUrlKey(key)
            val r = resolveString(v, scope, url, env.inSheet, ctx.legacyToken)
            if (r.deferredStep) addSheetPath(env.block, SheetStepPath(path.joinToString("."), "token"))
            val flags = optionState[o]
            if (flags != null && key == "label") {
                flags.labelUnresolved = r.unresolved
                flags.labelUnresolvedHookData = r.unresolvedHookData
            }
            if (flags?.generated == true && key == "value" && r.hadToken && (r.unresolved || r.value.trim().isEmpty())) {
                flags.valueUnresolved = true
            }
            if (r.unresolved) unresolvedKeys.getOrPut(o) { mutableSetOf() }.add(key)
            if (url && r.hadToken && !r.deferredStep && (r.unresolved || !isValidTemplatedUrl(r.value))) {
                o.remove(key)
                log += "url ${env.block["id"]}.${path.joinToString(".")}: unresolved or not an absolute https URL — key removed"
                return
            }
            o[key] = r.value
        }

        /** A string element of a list (string_list elements are string slots). Null = drop the element. */
        private fun resolveListString(el: String, key: String, scope: Scope, env: WalkEnv, path: List<Any>): String? {
            if (!el.contains("{{")) return el
            val url = key == "gallery_images"
            val r = resolveString(el, scope, url, env.inSheet, ctx.legacyToken)
            if (r.deferredStep) addSheetPath(env.block, SheetStepPath(path.joinToString("."), "token"))
            if (url && r.hadToken && !r.deferredStep && (r.unresolved || !isValidTemplatedUrl(r.value))) {
                log += "url ${env.block["id"]}.${path.joinToString(".")}: dropped"
                return null
            }
            return r.value
        }

        private fun applyDataTemplates(
            o: JsonObj,
            templates: Map<*, *>,
            owner: Owner,
            scope: Scope,
            env: WalkEnv,
            boundKeys: Set<String>?,
        ) {
            for ((k, tmpl) in templates) {
                val key = k?.toString() ?: continue
                if (tmpl !is String) continue
                if (key in EXCLUDED || key in MARKERS || key == "data_templates") {
                    log += "data_templates.$key: excluded key — ignored"; continue
                }
                val cls = classOf(owner, key)
                if (cls != null && cls != "string") { log += "data_templates.$key: $cls key — ignored"; continue }
                if (boundKeys?.contains(key) == true) continue // the binding wins
                // A `step` token in a data_templates entry inside sheet_blocks: the raw pass
                // cannot see sheet inputs, so the entry counts as unresolved (the sibling stays).
                if (env.inSheet && extractTemplateTokens(tmpl).any { it.root == "step" }) continue
                val url = isUrlKey(key)
                val r = resolveString(tmpl, scope, url, deferStep = false, legacy = ctx.legacyToken)
                val flags = optionState[o]
                var resolved = !r.unresolved && r.value.trim().isNotEmpty()
                if (resolved && url && !isValidTemplatedUrl(r.value)) resolved = false
                if (!resolved) {
                    if (flags?.generated == true && key == "value") flags.valueUnresolved = true
                    continue
                }
                o[key] = r.value
                if (flags != null && key == "label") { flags.labelUnresolved = false; flags.labelUnresolvedHookData = false }
                if (flags?.generated == true && key == "value") flags.valueUnresolved = false
                unresolvedKeys[o]?.remove(key)
                recordDisplacedLoc(env, env.path + key)
            }
        }

        private fun recordDisplacedLoc(env: WalkEnv, path: List<Any>) {
            val id = env.block["id"] as? String ?: return
            val pattern = StringBuilder()
            var index: Int? = null
            for (p in path) {
                if (p is Int) { pattern.append("[]"); if (index == null) index = p }
                else { if (pattern.isNotEmpty()) pattern.append('.'); pattern.append(p) }
            }
            val pat = pattern.toString()
            for (row in BlockKeyTypes.localizationSuffixes) {
                if (row.blockType != env.blockType || row.key != pat) continue
                val suffix = row.suffix.replace("<i>", (index ?: 0).toString())
                displacedLocKeys += "block.$id.$suffix"
            }
        }

        // ── Options after the pass ────────────────────────────────────────────────────
        private fun settleOptions(block: JsonObj) {
            val opts = (block["field_options"] as List<*>).mapNotNull { obj(it) }
            val pending = ctx.pending
            val generated = mutableListOf<JsonObj>()
            val extras = mutableListOf<JsonObj>()
            var hidden = 0
            for (o in opts) {
                val st = optionState[o]
                if (st?.generated == true && st.valueUnresolved) {
                    log += "option ${o["id"]}: generated value unresolved/empty — dropped"; continue
                }
                if (st != null) {
                    val label = o["label"] as? String ?: ""
                    val hide = (st.labelUnresolved && label.trim().isEmpty()) || (pending && st.labelUnresolvedHookData)
                    if (hide) { hidden++; continue }
                }
                (if (st?.generated == true) generated else extras) += o
            }
            fun valueOf(o: JsonObj): String {
                val v = o["value"] ?: o["id"] ?: ""
                return stringifyTemplateValue(v) ?: v.toString()
            }
            val extraValues = extras.map { valueOf(it) }.toSet()
            val seen = mutableSetOf<String>()
            val keptGenerated = generated.filter { o ->
                val v = valueOf(o)
                if (v in extraValues || v in seen) {
                    log += "option ${o["id"]}: duplicate value $v — dropped"; false
                } else { seen += v; true }
            }
            block["field_options"] = (keptGenerated + extras).toMutableList<Any?>()
            hiddenCount[block] = hidden
        }

        private fun sanitizeStat(stat: JsonObj): JsonObj? {
            val flags = unresolvedKeys[stat]
            val valueUnresolved = flags?.contains("value") ?: false
            val labelUnresolved = flags?.contains("label") ?: false
            val hasLabel = (stat["label"] as? String)?.trim()?.isNotEmpty() == true
            if (valueUnresolved && (labelUnresolved || !hasLabel)) return null
            if (valueUnresolved) stat["value"] = "—"
            if (labelUnresolved) stat.remove("label")
            return stat
        }

        fun sheetPathsOf(block: Any): List<SheetStepPath>? {
            val l = sheetPaths[block]
            if (l.isNullOrEmpty()) return null
            return l.sortedWith(compareBy<SheetStepPath> { it.path }.thenBy { it.kind })
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // Pending / hook_data reference analysis
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    private fun hasRootToken(s: Any?, root: String, requireNoFallback: Boolean = false): Boolean =
        s is String && extractTemplateTokens(s).any { it.root == root && (!requireNoFallback || it.fallback == null) }

    /**
     * "resolve_state: pending" — an option-bearing input where a `hook_data` reference can change
     * WHICH options exist or their STORED ANSWER. Media-only references never count.
     */
    fun optionsDependOnHookData(block: Map<*, *>): Boolean {
        if (block["type"]?.toString() !in OPTION_BEARING_TYPES) return false
        val fc = block["field_config"] as? Map<*, *>
        val rep = fc?.get("repeat") as? Map<*, *>
        val src = rep?.get("source")
        if (src is String && src.split('.')[0] == "hook_data") return true
        for (o in (block["field_options"] as? List<*>).orEmpty()) {
            val om = o as? Map<*, *> ?: continue
            if (hasRootToken(om["label"], "hook_data", true)) return true
            if (hasRootToken(om["value"], "hook_data")) return true
            val dt = om["data_templates"] as? Map<*, *>
            if (dt != null && hasRootToken(dt["value"], "hook_data")) return true
        }
        return false
    }

    /**
     * "Applies" — does this step's raw content reference `hook_data` at all (a token inline or in
     * `data_templates`, a `repeat.source`, a `bindings` value, or a `block.<id>.*` localization value)?
     */
    fun stepReferencesHookData(blocks: List<*>, localizations: StepLocalizations?): Boolean {
        fun scan(v: Any?, key: String? = null): Boolean = when (v) {
            is String -> hasRootToken(v, "hook_data") ||
                ((key == "source" || key == "bindingPath") && v.split('.')[0] == "hook_data")
            is List<*> -> v.any { scan(it) }
            is Map<*, *> -> v.entries.any { (k, e) ->
                val ks = k?.toString()
                when {
                    ks == "bindings" && e is Map<*, *> -> e.values.any { p -> p is String && p.split('.')[0] == "hook_data" }
                    ks == "repeat" && e is Map<*, *> -> (e["source"] as? String)?.split('.')?.get(0) == "hook_data"
                    else -> scan(e, ks)
                }
            }
            else -> false
        }
        if (blocks.any { scan(it) }) return true
        for (map in localizations?.values.orEmpty()) {
            for ((k, v) in map) if (k.startsWith("block.") && hasRootToken(v, "hook_data")) return true
        }
        return false
    }

    /** A bare `hook_data` reference (no key after the root): every key counts as referenced. */
    const val ANY_HOOK_DATA_KEY = "*"

    /**
     * The TOP-LEVEL `hook_data` keys a step references: the first path segment after
     * `hook_data`, over exactly the reference set [stepReferencesHookData] scans (tokens,
     * `data_templates`, `repeat.source`, `hook_data` bindings, `block.<id>.*` localizations). A bare
     * `hook_data` reference yields [ANY_HOOK_DATA_KEY]. "Cached on arrival" counts a layer VALUE only
     * under one of these keys.
     */
    fun stepHookDataKeys(blocks: List<*>, localizations: StepLocalizations?): Set<String> {
        val out = LinkedHashSet<String>()
        fun addPath(p: String) {
            val segs = p.split('.')
            if (segs[0] != "hook_data") return
            out += segs.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: ANY_HOOK_DATA_KEY
        }
        fun tokens(s: String) {
            if (!s.contains("{{")) return
            for (t in extractTemplateTokens(s)) if (t.root == "hook_data") addPath(t.path)
        }
        fun scan(v: Any?, key: String? = null) {
            when (v) {
                is String -> {
                    tokens(v)
                    if (key == "source" || key == "bindingPath") addPath(v)
                }
                is List<*> -> v.forEach { scan(it) }
                is Map<*, *> -> for ((k, e) in v) {
                    val ks = k?.toString()
                    when {
                        ks == "bindings" && e is Map<*, *> -> e.values.forEach { p -> if (p is String) addPath(p) }
                        ks == "repeat" && e is Map<*, *> -> (e["source"] as? String)?.let { addPath(it) }
                        else -> scan(e, ks)
                    }
                }
                else -> {}
            }
        }
        blocks.forEach { scan(it) }
        for (map in localizations?.values.orEmpty()) {
            for ((k, v) in map) if (k.startsWith("block.")) tokens(v)
        }
        return out
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // Public entry points
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Resolve ONE raw top-level content block. [HostDataContext.blockIndex] is its
     * index in the step's `content_blocks`; [HostDataContext.stepId] its step. The input is never
     * mutated.
     */
    fun resolveRawBlock(raw: Map<*, *>, ctx: HostDataContext): HostDataResolveResult {
        val block = stampBlockIds(raw, ctx.stepId, ctx.blockIndex)
        val stamped = deepCopyObj(block)
        val ids = blockIndex(block)
        val locEntries = locKeysOf(ctx.localizations, ids.keys)

        if (blockSkipsRawPass(block, ctx.localizations)) {
            val subset = ctx.localizations?.let {
                val out = LinkedHashMap<String, MutableMap<String, String>>()
                for (e in locEntries) out.getOrPut(e.locale) { LinkedHashMap() }[e.key] = it[e.locale]!![e.key]!!
                out
            }
            return HostDataResolveResult(block, subset, skipped = true, log = emptyList(), stamped = stamped)
        }

        val pass = RawPass(ctx)
        val scope = rootScope(ctx)

        // 1. Bindings at every block depth (children, stack_children, carousel pages = children, sheet_blocks).
        forEachBlock(block, "") { b, _, inSheet -> pass.applyBindings(b, scope, inSheet) }
        // 2. Strip authored markers — a hand-edited flow cannot bypass a required Select.
        stripMarkers(block)
        // 3. Pending analysis runs on the authored (post-binding, pre-expansion) shape.
        val dependsOnHookData = IdentityHashMap<Any, Boolean>()
        forEachBlock(block, "") { b, _, inSheet -> if (!inSheet) dependsOnHookData[b] = optionsDependOnHookData(b) }
        // 4. Repeat expansion — top-level input_select only (a nested repeat is a publish error; ignored here).
        val repeatActive = pass.expandRepeat(block, scope)
        val postExpansion = deepCopyObj(block)
        val boundKeys = pass.bound[block]?.toSet().orEmpty()
        // 5. The single structural pass.
        pass.walkBlock(block, scope, false)
        // 6. Markers.
        val pending = ctx.pending
        forEachBlock(block, "") { b, _, inSheet ->
            val paths = pass.sheetPathsOf(b)
            if (inSheet && paths != null) {
                if (b["field_config"] !is Map<*, *>) b["field_config"] = LinkedHashMap<String, Any?>()
                obj(b["field_config"])!!["sheet_step_paths"] = paths.map { p ->
                    val m = LinkedHashMap<String, Any?>()
                    m["path"] = p.path
                    m["kind"] = p.kind
                    if (p.source != null) m["source"] = p.source
                    m
                }.toMutableList()
            }
            if (inSheet || b["type"]?.toString() !in OPTION_BEARING_TYPES) return@forEachBlock
            val fc = b["field_config"] as? Map<*, *>
            val osid = fc?.get("option_set_id")
            if (osid is String && osid.trim().isNotEmpty()) return@forEachBlock
            val scoped = (b === block && repeatActive) || (pass.hiddenCount[b] ?: 0) > 0
            if (pending) {
                if (dependsOnHookData[b] == true) {
                    val next = LinkedHashMap<String, Any?>()
                    fc?.forEach { (k, v) -> next[k.toString()] = v }
                    next["resolve_state"] = "pending"
                    b["field_config"] = next
                }
                return@forEachBlock
            }
            if (!scoped) return@forEachBlock
            val count = (b["field_options"] as? List<*>)?.size ?: 0
            val next = LinkedHashMap<String, Any?>()
            fc?.forEach { (k, v) -> next[k.toString()] = v }
            next["resolve_state"] = if (count == 0) "empty_in_scope" else "scoped"
            if (count == 0) {
                val emptyText = next["empty_text"]
                if (next["hide_when_empty"] == true) next["empty_state"] = linkedMapOf<String, Any?>("mode" to "hidden")
                else if (emptyText is String && emptyText.trim().isNotEmpty()) {
                    next["empty_state"] = linkedMapOf<String, Any?>("mode" to "text", "text" to emptyText)
                }
            }
            b["field_config"] = next
        }

        // 7. Localizations — the block's `block.<id>.*` values, resolved in block scope; displaced keys deleted.
        var localizations: LinkedHashMap<String, MutableMap<String, String>>? = null
        if (ctx.localizations != null) {
            localizations = LinkedHashMap()
            for (e in locEntries) {
                val map = localizations.getOrPut(e.locale) { LinkedHashMap() }
                if (e.key in pass.displacedLocKeys) continue
                val value = ctx.localizations[e.locale]!![e.key]!!
                if (!value.contains("{{")) { map[e.key] = value; continue }
                val owner = ids[e.id]
                val locScope = if (owner != null) seededScope(owner, scope) else scope
                map[e.key] = resolveString(value, locScope, url = false, deferStep = false, legacy = ctx.legacyToken).value
            }
        }

        return HostDataResolveResult(
            block = block,
            localizations = localizations,
            skipped = false,
            log = pass.log,
            postExpansion = postExpansion,
            boundKeys = boundKeys,
            stamped = stamped,
        )
    }

    private fun seededScope(block: JsonObj, scope: Scope): Scope {
        val stats = ((block["field_config"] as? Map<*, *>)?.get("summary_stats") as? List<*>).orEmpty()
        val seeds = LinkedHashMap<String, Any?>()
        for (s in stats) {
            val m = s as? Map<*, *> ?: continue
            val fid = m["field_id"] as? String ?: continue
            if (fid.isNotEmpty() && m.containsKey("default")) seeds[fid] = m["default"]
        }
        if (seeds.isEmpty()) return scope
        (scope.roots["step"] as? Map<*, *>)?.forEach { (k, v) -> seeds[k.toString()] = v }
        return scope.withStep(seeds)
    }
}
