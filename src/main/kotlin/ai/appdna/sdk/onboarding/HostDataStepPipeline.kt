package ai.appdna.sdk.onboarding

import ai.appdna.sdk.core.LocalizationEngine
import ai.appdna.sdk.core.TemplateContext
import ai.appdna.sdk.core.TemplateEngine
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select

/*
 * The ONE step pipeline every reader of a presented onboarding step's blocks goes
 * through ("Readers", "One layered block list for every reader"):
 *
 *   raw pass (HostDataResolver) → typed decode with per-key revert → StepConfigOverride merge →
 *   ElementInteractionResult layering (options + resolved field_config patches, markers/sources
 *   dropped) → the list the Select views, the required gate / canAdvance, the validation toast and
 *   the consent-CTA colour gate ALL read.
 *
 * Non-Compose on purpose: `BlockBasedStepView` and the shared-fixture runner drive the SAME object,
 * so a fixture cannot pass while the screen does something else.
 */

/** Everything one resolve reads. Compared by value for memoisation. */
internal data class HostDataInputs(
    val hookData: Map<String, Any?>? = null,
    val responses: Map<String, Any?> = emptyMap(),
    val stepInputs: Map<String, Any?> = emptyMap(),
    val selected: Map<String, Any?> = emptyMap(),
    val user: Map<String, Any?>? = null,
    val session: Map<String, Any?>? = null,
    val pending: Boolean = false,
    val locale: String? = null,
    /** `configOverrides[step.id]` — merged AFTER resolve, never before. */
    val stepOverride: StepConfigOverride? = null,
    /** ElementInteractionResult `fieldConfigPatches`, per block id. */
    val fieldConfigOverrides: Map<String, Map<String, Any>> = emptyMap(),
    /** ElementInteractionResult `fieldOptions`, per block id. */
    val fieldOptionsOverrides: Map<String, List<InputOption>> = emptyMap(),
)

/**
 * The [HostDataInputs] of the presented step, read from its CURRENT state (identity, session, the
 * `selected` store, the locale). Kept out of `BlockBasedStepView` so the composable stays small (D8),
 * and so an interaction-requested advance re-runs the pipeline with exactly the inputs a frame would.
 */
internal fun liveHostDataInputs(
    stepOverride: StepConfigOverride?,
    /**
     * The EFFECTIVE `hook_data` (base + interaction layer), computed by the caller from
     * the flow-level owner at call time. Defaults to the base alone (no interaction layer).
     */
    hookData: Map<String, Any?>? = stepOverride?.dataContext,
    responses: Map<String, Any?>,
    inputValues: Map<String, Any>,
    pending: Boolean,
    fieldConfigOverrides: Map<String, Map<String, Any>>,
    fieldOptionsOverrides: Map<String, List<InputOption>>,
): HostDataInputs = HostDataInputs(
    hookData = hookData,
    responses = responses,
    stepInputs = inputValues.toMap(),
    selected = SelectedOptionStore.snapshot(),
    user = ai.appdna.sdk.AppDNA.getIdentityRef()?.traits,
    session = ai.appdna.sdk.core.SessionDataStore.instance?.sessionData,
    pending = pending,
    locale = java.util.Locale.getDefault().language,
    stepOverride = stepOverride,
    fieldConfigOverrides = fieldConfigOverrides.toMap(),
    fieldOptionsOverrides = fieldOptionsOverrides.toMap(),
)

/**
 * The per-presentation parameters of [liveHostDataInputs] (plus whether the step is the one on
 * screen). `BlockBasedStepView` holds them in ONE `rememberUpdatedState`, so a coroutine resumed
 * after the host's interaction round-trip reads the LATEST composition's values — not the ones its
 * closure captured at tap time (a host reply can land after `pending` ended or the override changed).
 * A data class so an unchanged recomposition writes nothing.
 */
internal data class HostDataLiveParams(
    val stepOverride: StepConfigOverride?,
    val responses: Map<String, Any?>,
    val pending: Boolean,
    val isCurrent: Boolean,
)

/**
 * The step an interaction-requested advance is gated on: re-resolved with the
 * state that interaction just wrote, THEN (only for the step on screen, like the LaunchedEffect)
 * with the selections it made vanish pruned from `inputValues` + `SelectedOptionStore`, re-resolved
 * once more when anything was pruned. Pending / Option-Set / out-of-scope blocks are never pruned
 * ([pruneHostDataSelections] only touches `scoped` / `empty_in_scope`). Without this the gate judged
 * the stale value — the LaunchedEffect prune runs only after the next frame.
 * A small top-level helper on purpose: `BlockBasedStepView` must not grow (D8).
 */
internal fun resolveForInteractionAdvance(
    pipeline: HostDataStepPipeline,
    isCurrent: Boolean,
    inputValues: MutableMap<String, Any>,
    inputs: () -> HostDataInputs,
): ResolvedHostDataStep {
    val r = pipeline.resolve(inputs())
    if (!isCurrent || !pruneHostDataSelections(r.blocks, r.rawResolvedIds, inputValues)) return r
    return pipeline.resolve(inputs())
}

/** A typed decode rejecting a resolved block, naming the offending key path when it can. */
internal class HostDataDecodeFailure(val keyPath: String?) : Exception("decode failed at ${keyPath ?: "<unknown>"}")

/** The layered result of one resolve. */
internal class ResolvedHostDataStep(
    /** The step's effective blocks — every reader reads these. */
    val blocks: List<ContentBlock>,
    /** The step's localizations with `block.<id>.*` values resolved / displaced ("Translations"). */
    val localizations: Map<String, Map<String, String>>?,
    /** Resolved raw sheet blocks keyed `"<block id>|<option id>"` — the sheet renderer re-reads them. */
    val sheetRaw: Map<String, List<Map<String, Any?>>>,
    /** Debug lines from the raw pass. */
    val log: List<String>,
    /**
     * Ids of the blocks the raw pass produced (a resolved top-level block that decoded, and its
     * `children` / `stack_children`). The markers are honoured ONLY on these: a skipped block, or
     * one that fell back to its authored JSON, keeps whatever `resolve_state` / `empty_state` its
     * author typed and must not change the gate or the view. Mirrors iOS `rawResolvedIds`.
     */
    val rawResolvedIds: Set<String> = emptySet(),
)

internal class HostDataStepPipeline(
    val step: OnboardingStep,
    /** TemplateEngine's context for non-block roots; read once per resolve. */
    private val legacyContext: () -> TemplateContext? = { runCatching { TemplateEngine.buildContext(ai.appdna.sdk.core.TemplateSurface.ONBOARDING) }.getOrNull() },
    private val decode: (Map<String, Any?>) -> ContentBlock = { OnboardingConfigParser.decodeRawBlock(it) },
) {
    /**
     * Decode seam (tests): called before each typed decode of a RESOLVED block with the current map and
     * its post-expansion snapshot; throwing [HostDataDecodeFailure] makes the decode reject it.
     */
    var decodeGuard: ((blockId: String, current: Map<String, Any?>, postExpansion: Map<String, Any?>) -> Unit)? = null

    /** Keys reverted by the decode-failure path while producing the CURRENT blocks, `(block id, key path)`. */
    val lastRevertedKeys: List<Pair<String, String>>
        get() = memo.entries.sortedBy { it.key }.flatMap { it.value.reverted }

    private class Memo(
        val key: List<Any?>,
        val result: HostDataResolveResult,
        val decoded: ContentBlock,
        val reverted: List<Pair<String, String>>,
        /** False when the block skipped the raw pass or every revert failed (authored JSON decoded). */
        val rawResolved: Boolean,
    )
    private val memo = HashMap<Int, Memo>()
    private var reverting: MutableList<Pair<String, String>> = mutableListOf()
    /** Set by [decodeWithRevert] when it gave up and decoded the authored (stamped) block. */
    private var decodeFellBack = false

    /** Every path one raw block reads ("Cost"): block-root paths and legacy TemplateEngine paths. */
    private class BlockDeps(val paths: List<String>, val legacyPaths: List<String>)
    private val deps = HashMap<Int, BlockDeps>()

    /** Test seam: how many times [legacyContext] was built (it must not be built on a memo hit). */
    internal var legacyContextBuilds = 0
        private set
    /** Test seam: how many blocks the raw pass actually resolved (memo misses). */
    internal var rawPassRuns = 0
        private set

    val hasRaw: Boolean get() = step.rawContentBlocks != null

    /**
     * The paths block [i] references under EVERY root, computed once per step: inline tokens in any
     * string (options, `data_templates`, sheets included), `bindings` sources, `repeat.source`, and the
     * step's localization values. `item` / `index` are covered by the repeat source they iterate.
     */
    private fun depsOf(i: Int, block: Map<*, *>): BlockDeps = deps.getOrPut(i) {
        val paths = LinkedHashSet<String>()
        val legacy = LinkedHashSet<String>()
        fun tokens(text: String) {
            if (!text.contains("{{")) return
            for (t in HostDataResolver.extractTemplateTokens(text)) when (t.root) {
                "item", "index" -> {}
                in HostDataResolver.BLOCK_ROOTS -> paths += t.path
                else -> legacy += t.path
            }
        }
        fun scan(v: Any?, key: String?) {
            when (v) {
                is String -> tokens(v)
                is List<*> -> v.forEach { scan(it, key) }
                is Map<*, *> -> for ((k, x) in v) {
                    val ks = k?.toString()
                    if (ks == "bindings" && x is Map<*, *>) x.values.forEach { (it as? String)?.let { p -> paths += p } }
                    if (ks == "source" && key == "repeat" && x is String) paths += x
                    scan(x, ks)
                }
                else -> {}
            }
        }
        scan(block, null)
        step.config.localizations?.values?.forEach { m -> m.values.forEach { tokens(it) } }
        BlockDeps(paths.toList(), legacy.toList())
    }

    fun resolve(inputs: HostDataInputs): ResolvedHostDataStep {
        val raw = step.rawContentBlocks ?: error("HostDataStepPipeline.resolve on a step without raw blocks")
        // Built lazily: only a block that references a TemplateEngine root (or a memo miss that
        // resolves one) pays for `TemplateEngine.buildContext()`.
        val tctx: TemplateContext? by lazy { legacyContextBuilds++; legacyContext() }
        val legacy: (String) -> String? = { path: String -> tctx?.let { TemplateEngine.resolveToken(path, it) } }
        // A stored value (an option's value, a field default, a URL action) leaves the screen, so a
        // Google answer resolves there as it does on every other surface: to its typed text.
        val storedContext: TemplateContext? by lazy { tctx?.copy(surface = ai.appdna.sdk.core.TemplateSurface.OTHER) }
        val legacyStored: (String) -> String? = { path: String -> storedContext?.let { TemplateEngine.resolveToken(path, it) } }
        val stepLocs = step.config.localizations
        val scope = HostDataResolver.Scope(
            mapOf(
                "responses" to inputs.responses, "hook_data" to inputs.hookData, "step" to inputs.stepInputs,
                "selected" to inputs.selected, "user" to inputs.user, "session" to inputs.session,
            ),
        )
        // "Cost" — memoised per block on the values of every path it references under every root
        // (legacy TemplateEngine roots included), the pending flag and the locale.
        fun memoKeyOf(i: Int, b: Map<*, *>): List<Any?> {
            val d = depsOf(i, b)
            return listOf(
                inputs.pending, inputs.locale,
                d.paths.map { HostDataResolver.deepCopy(HostDataResolver.resolveScopePath(it, scope)) },
                d.legacyPaths.map { legacy(it) },
                // What a stored value resolves the same paths to: a Google answer's typed text can
                // change while the drawn value does not.
                d.paths.map { HostDataResolver.resolveScopePath(it, scope, typedText = true) },
                d.legacyPaths.map { legacyStored(it) },
                // Whether a drawn path takes a Google answer's content (an option's Google keys).
                d.paths.map { HostDataResolver.readsGoogleAnswer(it, scope) },
            )
        }

        val decoded = mutableListOf<ContentBlock>()
        val log = mutableListOf<String>()
        val resolvedRaw = mutableListOf<Map<String, Any?>>()
        val rawIds = LinkedHashSet<String>()
        val locs: MutableMap<String, MutableMap<String, String>>? = stepLocs?.let { src ->
            LinkedHashMap<String, MutableMap<String, String>>().also { out -> src.forEach { (k, m) -> out[k] = LinkedHashMap(m) } }
        }

        raw.blocks.forEachIndexed { i, b ->
            if (b !is Map<*, *>) return@forEachIndexed
            val cached = memo[i]
            val memoKey = memoKeyOf(i, b)
            val entry = if (cached != null && cached.key == memoKey) cached else {
                rawPassRuns++
                val ctx = HostDataContext(
                    stepId = step.id,
                    blockIndex = i,
                    hookData = inputs.hookData,
                    responses = inputs.responses,
                    step = inputs.stepInputs,
                    user = inputs.user,
                    session = inputs.session,
                    selected = inputs.selected,
                    pending = inputs.pending,
                    localizations = stepLocs,
                    locale = inputs.locale,
                    legacyToken = legacy.takeIf { tctx != null },
                    legacyTokenStored = legacyStored.takeIf { tctx != null },
                )
                val r = HostDataResolver.resolveRawBlock(b, ctx)
                reverting = mutableListOf()
                decodeFellBack = false
                val d = decodeWithRevert(r).let { block ->
                    // Which option keys show a Google answer's content, for a later `selected` path.
                    if (r.googleOptionKeys.isEmpty()) block
                    else block.copy(
                        field_options = block.field_options?.map { o ->
                            o.copy(googleKeys = r.googleOptionKeys[o.id ?: o.value])
                        }?.toImmutableList(),
                    )
                }
                Memo(memoKey, r, d, reverting.toList(), rawResolved = !r.skipped && !decodeFellBack).also { memo[i] = it }
            }
            val result = entry.result
            val block = entry.decoded
            if (entry.rawResolved) collectBlockIds(block, rawIds)
            decoded += block
            resolvedRaw += result.block
            log += result.log
            // Merge this block's resolved localizations (entries of skipped blocks stay as authored).
            if (locs != null && stepLocs != null && !result.skipped) {
                val ids = mutableSetOf<String>()
                collectIds(result.block, ids)
                for ((locale, map) in stepLocs) {
                    for (key in map.keys) {
                        if (ids.none { key.startsWith("block.$it.") }) continue
                        val v = result.localizations?.get(locale)?.get(key)
                        if (v == null) locs[locale]?.remove(key) else locs.getOrPut(locale) { LinkedHashMap() }[key] = v
                    }
                }
            }
        }

        // StepConfigOverride typed merge AFTER resolve (never twice, never before).
        var blocks: List<ContentBlock> = decoded
        inputs.stepOverride?.let { o ->
            blocks = step.config.copy(content_blocks = decoded.toImmutableList()).applyingOverride(o).content_blocks.orEmpty()
            o.fieldOptions?.let { named ->
                blocks = blocks.map { blk -> if (named.containsKey(blk.id)) resolveHostOptionText(blk, inputs) else blk }
            }
        }
        // ElementInteractionResult layering: patches resolved, markers / sources dropped.
        if (inputs.fieldConfigOverrides.isNotEmpty() || inputs.fieldOptionsOverrides.isNotEmpty()) {
            val patchCtx = HostDataContext(
                stepId = step.id, hookData = inputs.hookData, responses = inputs.responses, step = inputs.stepInputs,
                user = inputs.user, session = inputs.session, selected = inputs.selected,
                legacyToken = legacy.takeIf { tctx != null },
                legacyTokenStored = legacyStored.takeIf { tctx != null },
            )
            blocks = blocks.map { blk -> layerInteraction(blk, inputs, patchCtx) }
        }

        val sheets = LinkedHashMap<String, List<Map<String, Any?>>>()
        resolvedRaw.forEach { collectSheets(it, sheets) }
        // Host options win: a block whose options the host replaced (either channel) has no option
        // from the raw pass, so its raw sheets must not be found by a host option reusing an authored id.
        val hostOptionBlocks = inputs.fieldOptionsOverrides.keys + inputs.stepOverride?.fieldOptions?.keys.orEmpty()
        if (hostOptionBlocks.isNotEmpty()) sheets.keys.removeAll { k -> k.substringBefore('|') in hostOptionBlocks }
        return ResolvedHostDataStep(blocks, locs, sheets, log, rawIds)
    }

    // ── Decode with per-key revert ("No whole-block failure") ───────────────────────────────

    private fun decodeWithRevert(r: HostDataResolveResult): ContentBlock {
        if (r.skipped) return decode(r.block)
        val blockId = r.block["id"]?.toString() ?: ""
        val post = r.postExpansion ?: r.stamped
        val current = HostDataResolver.deepCopyObj(r.block)
        val tried = mutableSetOf<String>()
        repeat(MAX_REVERTS) {
            val failure = try {
                decodeGuard?.invoke(blockId, current, post)
                return decode(current)
            } catch (e: HostDataDecodeFailure) {
                e.keyPath
            } catch (e: Exception) {
                null
            }
            // Owner decision: revert ONLY the key the failure names; cumulative only when none is named.
            val path = failure?.takeIf { it !in tried && valueAt(current, it) !== MISSING }
                ?: changedPaths(current, post).firstOrNull { it !in tried }
                ?: return@repeat
            tried += path
            revertPath(current, path, r)
            reverting += blockId to path
            ai.appdna.sdk.Log.debug { "Host data: typed decode rejected $blockId.$path — reverted to its authored value" }
        }
        // Still failing: the raw authored block, which is today's behaviour — NOT raw-resolved.
        decodeFellBack = true
        return decode(r.stamped)
    }

    private fun revertPath(current: MutableMap<String, Any?>, path: String, r: HostDataResolveResult) {
        val segs = path.split('.')
        // A key a BINDING wrote reverts to its authored raw value, not the post-binding one.
        val source: Map<String, Any?> = if (segs.size == 1 && segs[0] in r.boundKeys) r.stamped else (r.postExpansion ?: r.stamped)
        val original = if (segs.size >= 3 && segs[0] == "field_options") {
            // Per option, matched by id — resolved options may have been dropped / reordered.
            val idx = segs[1].toIntOrNull()
            val cur = (current["field_options"] as? List<*>)?.getOrNull(idx ?: -1) as? Map<*, *>
            val match = (source["field_options"] as? List<*>)?.firstOrNull { (it as? Map<*, *>)?.get("id") == cur?.get("id") }
            valueAt(match, segs.drop(2).joinToString("."))
        } else valueAt(source, path)
        setAt(current, segs, if (original === MISSING) MISSING else HostDataResolver.deepCopy(original))
    }

    private fun changedPaths(current: Map<String, Any?>, post: Map<String, Any?>): List<String> {
        val out = mutableListOf<String>()
        for ((k, v) in current) {
            if (k == "field_options" && v is List<*>) {
                v.forEachIndexed { i, o ->
                    val om = o as? Map<*, *> ?: return@forEachIndexed
                    val match = (post["field_options"] as? List<*>)?.firstOrNull { (it as? Map<*, *>)?.get("id") == om["id"] } as? Map<*, *>
                    for ((ok, ov) in om) if (match == null || match[ok] != ov) out += "field_options.$i.$ok"
                }
            } else if (post[k] != v) out += k
        }
        return out
    }

    private fun collectBlockIds(block: ContentBlock, out: MutableSet<String>) {
        out += block.id
        block.children?.forEach { collectBlockIds(it, out) }
        block.stack_children?.forEach { collectBlockIds(it, out) }
    }

    private fun collectIds(block: Map<*, *>, out: MutableSet<String>) {
        (block["id"] as? String)?.let { out += it }
        for (key in listOf("children", "stack_children")) (block[key] as? List<*>)?.forEach { c -> (c as? Map<*, *>)?.let { collectIds(it, out) } }
        (block["field_options"] as? List<*>)?.forEach { o ->
            ((o as? Map<*, *>)?.get("sheet_blocks") as? List<*>)?.forEach { sb -> (sb as? Map<*, *>)?.let { collectIds(it, out) } }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun collectSheets(block: Map<String, Any?>, out: MutableMap<String, List<Map<String, Any?>>>) {
        val id = block["id"]?.toString() ?: ""
        (block["field_options"] as? List<*>)?.forEach { o ->
            val om = o as? Map<*, *> ?: return@forEach
            val sheet = (om["sheet_blocks"] as? List<*>)?.mapNotNull { it as? Map<String, Any?> } ?: return@forEach
            val oid = (om["id"] ?: om["value"])?.toString() ?: return@forEach
            out["$id|$oid"] = sheet
            sheet.forEach { collectSheets(it, out) }
        }
        for (key in listOf("children", "stack_children")) {
            (block[key] as? List<*>)?.forEach { c -> (c as? Map<String, Any?>)?.let { collectSheets(it, out) } }
        }
    }

    // ── Layering ──────────────────────────────────────────────────────────────────────────

    private fun layerInteraction(block: ContentBlock, inputs: HostDataInputs, ctx: HostDataContext): ContentBlock {
        var next = block
        inputs.fieldConfigOverrides[block.id]?.takeIf { it.isNotEmpty() }?.let { patch ->
            // Host data cannot set markers or sources; patch strings are resolved like block strings.
            // Resolved with the SAME structural walker as the block's own field_config: key classes,
            // the URL rule (an unresolved / non-https `*_url` is dropped from the patch, so the
            // authored value stays), `data_templates`, single pass.
            @Suppress("UNCHECKED_CAST")
            val clean = HostDataResolver.resolveFieldConfigPatch(
                patch.filterKeys { it !in LAYER_DROPPED_KEYS },
                block.type,
                ctx,
            ).filterValues { it != null } as Map<String, Any>
            next = next.copy(field_config = (next.field_config ?: emptyMap()) + clean)
        }
        inputs.fieldOptionsOverrides[block.id]?.let { opts ->
            next = resolveHostOptionText(next.withHostOptions(opts.toImmutableList()), inputs)
        }
        return next
    }

    /**
     * Host `fieldOptions` are host data already: no expansion, no structural pass — only
     * `label`, `subtitle`, `leading_text` are resolved, with today's semantics (a miss keeps the literal).
     */
    @Suppress("UNCHECKED_CAST")
    private fun resolveHostOptionText(block: ContentBlock, inputs: HostDataInputs): ContentBlock {
        val opts = block.field_options ?: return block
        val hook = inputs.hookData?.filterValues { it != null } as Map<String, Any>?
        val responses = inputs.responses.filterValues { it != null } as Map<String, Any>
        val session = inputs.session?.filterValues { it != null } as Map<String, Any>?
        val user = inputs.user?.filterValues { it != null } as Map<String, Any>?
        val stepIn = inputs.stepInputs.filterValues { it != null } as Map<String, Any>
        fun r(s: String): String = if (s.contains("{{")) resolveTemplateString(s, hook, responses, session, user, stepIn) else s
        return block.withFieldOptions(opts.map { o -> o.withResolvedText(::r) }.toImmutableList())
    }

    companion object {
        private const val MAX_REVERTS = 64
        private val MISSING = Any()
        /** Patch keys dropped in the interaction layering. */
        val LAYER_DROPPED_KEYS = setOf("resolve_state", "empty_state", "sheet_step_paths", "repeat", "option_set_id")
        /** Applying host options (either channel) removes these from the block's field_config. */
        val HOST_OPTIONS_DROPPED_KEYS = setOf("option_set_id", "repeat", "empty_state", "resolve_state")

        private fun valueAt(root: Any?, path: String): Any? {
            var cur: Any? = root
            for (seg in path.split('.')) {
                val c = cur
                cur = when (c) {
                    is Map<*, *> -> if (c.containsKey(seg)) c[seg] else return MISSING
                    is List<*> -> seg.toIntOrNull()?.let { c.getOrNull(it) } ?: return MISSING
                    else -> return MISSING
                }
            }
            return cur
        }

        @Suppress("UNCHECKED_CAST")
        private fun setAt(root: MutableMap<String, Any?>, segs: List<String>, value: Any?) {
            var cur: Any? = root
            for (seg in segs.dropLast(1)) {
                val c = cur
                cur = when (c) {
                    is Map<*, *> -> c[seg]
                    is List<*> -> seg.toIntOrNull()?.let { c.getOrNull(it) }
                    else -> null
                } ?: return
            }
            val last = segs.last()
            when (cur) {
                is MutableMap<*, *> -> {
                    val m = cur as MutableMap<String, Any?>
                    if (value === MISSING) m.remove(last) else m[last] = value
                }
                is MutableList<*> -> last.toIntOrNull()?.let { idx ->
                    val l = cur as MutableList<Any?>
                    if (idx < l.size && value !== MISSING) l[idx] = value
                }
                else -> {}
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────────────────────────
// D8 guard — one giant data-class copy per method
// ─────────────────────────────────────────────────────────────────────────────────────────────────
//
// 🔴 `ContentBlock.copy` passes ~245 arguments (one register each, as an invoke/range) and
// `InputOption.copy` dozens more. A method that makes two or more of these calls — or one inside an
// inlined `map {}` next to another — was mis-dexed by D8 in the Flutter example's AGP 8.2.1 build:
// `resolveHostOptionText` and `applyBlockLevelOverrides` both failed ART verification
// (`copy1 v237<-v261 type=Undefined`), which rejects the WHOLE class and crashes every onboarding
// presentation. JVM tests cannot see it (see ComposeMethodSizeGuardTest). Keeping each copy in its
// own tiny, non-inline function keeps every caller's register frame small. Do not inline these.

/** Host options replace the authored list and drop the keys they outrank. */
internal fun ContentBlock.withHostOptions(options: kotlinx.collections.immutable.ImmutableList<InputOption>): ContentBlock =
    copy(
        field_options = options,
        field_config = field_config?.filterKeys { it !in HostDataStepPipeline.HOST_OPTIONS_DROPPED_KEYS },
    )

internal fun ContentBlock.withFieldOptions(options: kotlinx.collections.immutable.ImmutableList<InputOption>): ContentBlock =
    copy(field_options = options)

internal fun ContentBlock.withFieldConfig(config: Map<String, Any>?): ContentBlock = copy(field_config = config)

internal fun InputOption.withResolvedText(r: (String) -> String): InputOption =
    copy(label = r(label), subtitle = subtitle?.let(r), leading_text = leading_text?.let(r))

// ─────────────────────────────────────────────────────────────────────────────────────────────────
// Readers of the markers
// ─────────────────────────────────────────────────────────────────────────────────────────────────

/** The option's stored answer (`resolvedValue = value ?? id`). */
internal fun InputOption.storedAnswer(): String = value.ifEmpty { id ?: value }

/**
 * `field_config.empty_state` as the raw pass wrote it: `("text", text)`, `("hidden", null)` or null.
 * Honoured ONLY on a block the raw pass produced ([rawResolvedIds]) — an authored copy on a
 * skipped / fallen-back block, a Screen or SDUI is ignored. iOS `emptyState(_, rawResolved:)`.
 */
internal fun ContentBlock.hostDataEmptyState(rawResolvedIds: Set<String>): Pair<String, String?>? {
    if (id !in rawResolvedIds) return null
    val es = field_config?.get("empty_state") as? Map<*, *> ?: return null
    return when (es["mode"]) {
        "hidden" -> "hidden" to null
        "text" -> "text" to (es["text"] as? String)
        else -> null
    }
}

/** True when the raw pass decided this block renders nothing at all (`hide_when_empty`). */
internal fun ContentBlock.isHiddenByHostData(rawResolvedIds: Set<String>): Boolean =
    hostDataEmptyState(rawResolvedIds)?.first == "hidden"

/**
 * A container's children, minus those the raw pass hid (`hide_when_empty`). Every nested
 * renderer (row, stack, multi-buttons, carousel, zoned background) filters through this, exactly as
 * the top-level block list does; outside a raw-resolved step it is the identity.
 */
@Composable
@ReadOnlyComposable
internal fun List<ContentBlock>.visibleUnderHostData(): List<ContentBlock> {
    val ids = hostDataRawResolvedIds()
    return if (ids.isEmpty()) this else filterNot { it.isHiddenByHostData(ids) }
}

/**
 * The ids the raw pass produced in THIS composition — empty outside a raw-resolved onboarding
 * step (Screens, SDUI, a host-supplied option sheet), so no marker is honoured there.
 */
@Composable
@ReadOnlyComposable
internal fun hostDataRawResolvedIds(): Set<String> =
    if (LocalHostDataRawResolved.current) LocalHostDataRawResolvedIds.current else emptySet()

/**
 * After pending, a host-data-scoped input whose rendered options no longer contain a selected
 * value drops it from `inputValues[field_id]` and from `SelectedOptionStore`; otherwise the selection
 * is kept. Never while pending (the marker is `pending` then), never for Option Sets (no marker).
 * Returns true when anything changed.
 */
internal fun pruneHostDataSelections(
    blocks: List<ContentBlock>,
    rawResolvedIds: Set<String>,
    inputValues: MutableMap<String, Any>,
): Boolean {
    var changed = false
    fun visit(block: ContentBlock) {
        val state = RequiredFieldGate.resolveState(block, rawResolvedIds)
        if (state == "scoped" || state == "empty_in_scope") {
            val fid = block.field_id ?: block.id
            val rendered = block.field_options.orEmpty().mapNotNull { it.storedAnswer() }.toSet()
            when (val v = inputValues[fid]) {
                is String -> if (v.isNotEmpty() && v !in rendered) {
                    inputValues.remove(fid); SelectedOptionStore.clear(fid); changed = true
                }
                is List<*> -> {
                    val kept = v.filter { it?.toString() in rendered }
                    if (kept.size != v.size) {
                        if (kept.isEmpty()) inputValues.remove(fid) else inputValues[fid] = kept
                        SelectedOptionStore.retainValues(fid, kept.map { it.toString() }.toSet())
                        changed = true
                    }
                }
                else -> {}
            }
        }
        block.children?.forEach { visit(it) }
        block.stack_children?.forEach { visit(it) }
    }
    blocks.forEach { visit(it) }
    return changed
}

/**
 * The option pick every Select writes — shared by the Select views and the fixture runner so the
 * bookkeeping (`inputValues` + `SelectedOptionStore`) cannot drift between them.
 */
internal fun recordOptionPick(block: ContentBlock, options: List<InputOption>, value: String, inputValues: MutableMap<String, Any>) {
    val fieldId = block.field_id ?: block.id
    val isMulti = block.multi_select == true || (block.field_config?.get("multi_select") as? Boolean) == true
    if (isMulti) {
        val current = (inputValues[fieldId] as? List<*>)?.mapNotNull { it?.toString() }.orEmpty()
        val next = if (value in current) current - value else {
            val maxSel = (block.field_config?.get("max_selections") as? Number)?.toInt()
            if (maxSel != null && current.size >= maxSel) return
            current + value
        }
        inputValues[fieldId] = next
        SelectedOptionStore.record(fieldId, next.mapNotNull { v -> options.firstOrNull { it.storedAnswer() == v } })
    } else {
        inputValues[fieldId] = value
        options.firstOrNull { it.storedAnswer() == value }?.let { SelectedOptionStore.record(fieldId, it) }
    }
}

/**
 * The option whose bottom sheet a pick of [value] opens: the picked option when it owns
 * `sheet_blocks`, else null. Every Select presentation asks this one question, so the dropdown and
 * the list, stacked and grid styles open the same sheet for the same pick, a repeat-generated or
 * Option Set option included (they are in the rendered [options] like any other).
 */
internal fun optionSheetFor(options: List<InputOption>, value: String): InputOption? =
    options.firstOrNull { it.value == value }?.takeIf { !it.sheet_blocks.isNullOrEmpty() }

/**
 * A pick from the dropdown menu — shared by the dropdown view and the fixture runner. The choice is
 * recorded first, as the menu always recorded it (the one value, and the option for
 * `{{selected.<field_id>.…}}`). The return is the option whose sheet the pick opens, or null; the
 * sheet only presents, so nothing about it is reported. A value no rendered option carries records
 * nothing and opens nothing.
 */
internal fun dropdownOptionPick(
    block: ContentBlock,
    options: List<InputOption>,
    value: String,
    inputValues: MutableMap<String, Any>,
): InputOption? {
    val option = options.firstOrNull { it.value == value } ?: return null
    val fieldId = block.field_id ?: block.id
    inputValues[fieldId] = option.value
    SelectedOptionStore.record(fieldId, option)
    return optionSheetFor(options, value)
}

/**
 * The single-select dropdown's button text: the rendered option's label, else the label RECORDED
 * when the user picked [selectedValue] (an Option Set search result that is not in the cached pages,
 * or one the active query excludes), else [placeholder]. An empty [selectedValue] — nothing picked,
 * or a value pruned — is always the placeholder.
 */
internal fun dropdownSelectedLabel(
    options: List<InputOption>,
    selectedValue: String,
    recorded: Any?,
    placeholder: String,
): String {
    options.firstOrNull { it.value == selectedValue }?.label?.let { return it }
    if (selectedValue.isEmpty()) return placeholder
    val rec = recorded as? Map<*, *>
    if (rec != null && rec["value"]?.toString() == selectedValue) (rec["label"] as? String)?.let { return it }
    return placeholder
}

/**
 * The in-memory search over an authored or repeat list. Re-run whenever the resolved
 * `field_options` change, not only on a keystroke. A blank query is no filter (null).
 */
internal fun filterOptionsLocally(options: List<InputOption>, query: String): List<InputOption>? {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return null
    val folded = trimmed.lowercase()
    return options.filter {
        (it.label ?: "").lowercase().contains(folded) || (it.subtitle ?: "").lowercase().contains(folded)
    }
}

/**
 * Translation lookup for a block the raw pass resolved: NO interpolation (the pass already
 * resolved the values, and re-scanning would re-interpret host data containing `{{`).
 */
internal fun lookupOnlyLoc(key: String, fallback: String, localizations: Map<String, Map<String, String>>?, defaultLocale: String?): String =
    LocalizationEngine.resolve(key, localizations, defaultLocale, fallback)

/**
 * The "raw-resolved" flag — true inside an onboarding step whose blocks the raw pass resolved.
 * Propagates to every nested renderer (option sheets, carousel pages) so nothing re-scans a resolved
 * string. Screens / SDUI never set it.
 */
internal val LocalHostDataRawResolved = compositionLocalOf { false }

/** [ResolvedHostDataStep.rawResolvedIds] of the presented step — read via [hostDataRawResolvedIds]. */
internal val LocalHostDataRawResolvedIds = compositionLocalOf<Set<String>> { emptySet() }

/**
 * The presented step's FULL layered block list (lifted maps included), for the
 * consent-CTA gate inside [ThreeZoneBlockLayout], which itself only receives the column's blocks.
 * Null outside an onboarding step.
 */
internal val LocalStepGateBlocks = compositionLocalOf<List<ContentBlock>?> { null }

/** Resolved raw option-sheet blocks of the presented step, keyed `"<block id>|<option id>"`. */
internal val LocalHostDataSheets = compositionLocalOf<Map<String, List<Map<String, Any?>>>> { emptyMap() }

/**
 * "Sheet ids" — the sheet renderer resolves ONLY the paths the raw pass recorded in
 * `field_config.sheet_step_paths` (`step`-root tokens and `step`-rooted bindings), against the
 * sheet's own inputs, then decodes. Never descends: every nested block carries its own paths.
 */
internal fun resolveSheetBlocks(raw: List<Map<String, Any?>>, sheetInputs: Map<String, Any?>): List<ContentBlock> =
    raw.map { OnboardingConfigParser.decodeRawBlock(applySheetStepPaths(HostDataResolver.deepCopyObj(it), sheetInputs)) }

@Suppress("UNCHECKED_CAST")
private fun applySheetStepPaths(block: MutableMap<String, Any?>, sheetInputs: Map<String, Any?>): MutableMap<String, Any?> {
    val ctx = HostDataContext(step = sheetInputs)
    val paths = ((block["field_config"] as? Map<*, *>)?.get("sheet_step_paths") as? List<*>).orEmpty()
    for (p in paths) {
        val m = p as? Map<*, *> ?: continue
        val path = m["path"] as? String ?: continue
        when (m["kind"]) {
            "token" -> {
                val segs = path.split('.')
                var cur: Any? = block
                for (seg in segs.dropLast(1)) {
                    val c = cur
                    cur = when (c) {
                        is Map<*, *> -> c[seg]
                        is List<*> -> seg.toIntOrNull()?.let { c.getOrNull(it) }
                        else -> null
                    }
                }
                val last = segs.last()
                val target = cur
                when (target) {
                    is MutableMap<*, *> -> (target[last] as? String)?.let {
                        (target as MutableMap<String, Any?>)[last] = HostDataResolver.resolvePlainString(it, ctx)
                    }
                    is MutableList<*> -> last.toIntOrNull()?.let { i ->
                        (target.getOrNull(i) as? String)?.let { (target as MutableList<Any?>)[i] = HostDataResolver.resolvePlainString(it, ctx) }
                    }
                    else -> {}
                }
            }
            "binding" -> {
                val source = m["source"] as? String ?: continue
                val value = HostDataResolver.resolveScopePath(source, HostDataResolver.Scope(mapOf("step" to sheetInputs)))
                HostDataResolver.convertBindingValue(path, value)?.let { block[path] = it }
            }
        }
    }
    for (key in listOf("children", "stack_children")) {
        (block[key] as? MutableList<Any?>)?.let { l ->
            for (i in l.indices) (l[i] as? MutableMap<String, Any?>)?.let { l[i] = applySheetStepPaths(it, sheetInputs) }
        }
    }
    return block
}

// ─────────────────────────────────────────────────────────────────────────────────────────────────
// Host data pending — the per-presentation state machine
// ─────────────────────────────────────────────────────────────────────────────────────────────────

/**
 * One arrival on a step (back-then-forward is a new presentation). Held by the ViewModel so it
 * survives Activity recreation; `pending` is snapshot state so the step recomposes when it flips.
 */
internal class HostDataPresentation(val stepId: String, val stepIndex: Int, val serial: Long) {
    var pending by mutableStateOf(false)
        private set
    /** The pending flag the FIRST resolve of this presentation saw ("Starts synchronously"). */
    var firstResolvePending: Boolean? = null
    /** True once pending has ended — a later skipped call reads as not pending. */
    var ended = false
        private set
    /** The latest call's generation; only it may end pending. */
    var generation = 0
        private set
    /** A call was cancelled before replying → re-fire once, bypassing `lastViewedStepIndex`. */
    var needsRefire = false
    /** `onStepViewed` fires exactly once per step view. */
    var stepViewedFired = false
    /**
     * The in-flight call's `onStepViewed`, owed until it fires. Lets the Activity pay it BEFORE the
     * flow completes or is dismissed mid-call ([fireStepViewedIfOwed]) — after that the VM's host
     * lambdas are nulled, and firing from the dying composition's `finally` would reach nobody.
     */
    internal var owedStepViewed: (() -> Unit)? = null

    /** Fires the owed `onStepViewed` once; a no-op when it already fired or no call is in flight. */
    internal fun fireStepViewedIfOwed() {
        if (stepViewedFired) return
        val fire = owedStepViewed ?: return
        stepViewedFired = true
        owedStepViewed = null
        fire()
    }
    /** Pending ended at (or after) the deadline — a later reply is dropped. */
    var deadlinePassed = false
        private set

    internal fun start(applies: Boolean) {
        pending = applies
        if (!applies) ended = true
    }

    internal fun nextGeneration(): Int = ++generation

    /** Ends pending for [gen] when it is still the latest call. */
    internal fun end(gen: Int, byDeadline: Boolean = false) {
        if (gen != generation) return
        if (byDeadline) deadlinePassed = true
        ended = true
        pending = false
    }
}

/** Creates / reuses the presentation for the step on screen. Lives in the ViewModel. */
internal class HostDataPresentations {
    var current: HostDataPresentation? = null
        private set
    private var serial = 0L

    /**
     * "Starts synchronously" — called during composition, BEFORE the first resolve: pending =
     * true when a delegate is set, the step's raw blocks reference `hook_data`, and no
     * `configOverrides[step.id]` is cached yet. Reused while the same step stays on screen (Activity
     * recreation keeps the ViewModel), replaced on every new arrival (back-then-forward included:
     * the index changes in between).
     */
    fun presentationFor(step: OnboardingStep, stepIndex: Int, hasDelegate: Boolean, hasCachedOverride: Boolean): HostDataPresentation {
        current?.let { if (it.stepIndex == stepIndex && it.stepId == step.id) return it }
        val p = HostDataPresentation(step.id, stepIndex, ++serial)
        p.start(hasDelegate && !hasCachedOverride && stepHostDataApplies(step))
        previous = current
        current = p
        return p
    }

    /** The presentation that was on screen before [current] — the outgoing step of a transition. */
    var previous: HostDataPresentation? = null
        private set

    /**
     * The pending flag of the step rendered at [stepIndex]: the current presentation's, or
     * for the outgoing step still animating out — the flag ITS presentation had (a cancelled call
     * leaves it pending). Never forced to false: that would re-resolve the outgoing step with no hook
     * data and prune a restored selection.
     */
    fun pendingFor(stepIndex: Int, stepId: String): Boolean = presentationAt(stepIndex, stepId)?.pending == true

    /**
     * The presentation a composition of the step at [stepIndex] belongs to (the same
     * lookup as [pendingFor]), looked up on every recomposition. Null when neither [current] nor
     * [previous] matches (three navigations inside one exit tween): that composition's coordinator then
     * refuses every call.
     */
    fun presentationAt(stepIndex: Int, stepId: String): HostDataPresentation? =
        listOfNotNull(current, previous).firstOrNull { it.stepIndex == stepIndex && it.stepId == stepId }

    fun reset() { current = null; previous = null }
}

/** "Applies" — the step's raw blocks (or its `block.<id>.*` localizations) reference `hook_data`. */
internal fun stepHostDataApplies(step: OnboardingStep): Boolean {
    val raw = step.rawContentBlocks ?: return false
    return HostDataResolver.stepReferencesHookData(raw.blocks, step.config.localizations)
}

/**
 * One `onBeforeStepRender` call for [presentation], with the deadline driven by a timer
 * INDEPENDENT of the host call: `withTimeoutOrNull` cannot end pending when the host ignores
 * cancellation, so a separate `delay(deadlineMs)` path flips it. The host call runs in [hostScope]
 * (not awaited on cancellation), carries a generation token, and only the latest generation may end
 * pending. A reply after the deadline is dropped. A cancellation BEFORE the reply does not end
 * pending — it marks the presentation for one re-fire. Extracted from the Activity's LaunchedEffect
 * so tests drive it with virtual time.
 */
internal suspend fun runBeforeStepRenderHook(
    presentation: HostDataPresentation,
    hostScope: CoroutineScope,
    call: suspend () -> StepConfigOverride?,
    onOverride: (StepConfigOverride) -> Unit,
    onStepViewed: () -> Unit,
    deadlineMs: Long = HOST_DATA_DEADLINE_MS,
    /**
     * Whether [presentation] is still the one on screen. Checked only when the call is cancelled before
     * replying: still presented (Activity recreation) → the re-fire fires `onStepViewed`; not presented
     * (the user left the step) → the step WAS viewed, so it fires here, exactly once. Only the override
     * write and the end of pending are generation-gated, never the step-view side effect.
     */
    stillPresented: () -> Boolean = { true },
    /**
     * Draws the flow-level `callSeq` at call START. The reply's base is stamped with
     * the value this call drew, so ordering against interaction replies is by start, not arrival.
     */
    drawSeq: () -> Long = { 0L },
    /** When set, receives the reply together with the `callSeq` its call drew, instead of [onOverride]. */
    onStampedOverride: ((StepConfigOverride, Long) -> Unit)? = null,
) {
    val gen = presentation.nextGeneration()
    val seq = drawSeq()
    presentation.needsRefire = false
    if (!presentation.stepViewedFired) presentation.owedStepViewed = onStepViewed
    val reply = CompletableDeferred<Result<StepConfigOverride?>>()
    val hostJob = hostScope.launch {
        val r = try {
            Result.success(call())
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            Result.failure(t)
        }
        reply.complete(r)
    }
    val scope = CoroutineScope(currentCoroutineContext())
    val deadline = scope.launch { delay(deadlineMs) }
    var replied = false
    var cancelled = true
    try {
        val outcome: Result<StepConfigOverride?>? = select<Result<StepConfigOverride?>?> {
            reply.onAwait { it }
            deadline.onJoin { null }
        }
        cancelled = false
        if (outcome == null) {
            // Deadline: end pending; a later reply is dropped, even while the user is still on the
            // step (iOS `HostDataPendingCoordinator.finish` does the same; fixture
            // `before_render_reply_after_deadline_dropped`).
            presentation.end(gen, byDeadline = true)
            hostJob.cancel()
        } else {
            deadline.cancel()
            replied = true
            val override = outcome.getOrNull()
            if (override != null && gen == presentation.generation && !presentation.deadlinePassed) {
                if (onStampedOverride != null) onStampedOverride(override, seq) else onOverride(override)
            }
            presentation.end(gen)
        }
    } finally {
        if (cancelled) {
            deadline.cancel()
            hostJob.cancel()
            // Left the step before the reply: it WAS viewed — fire it now, exactly once.
            if (!stillPresented()) presentation.fireStepViewedIfOwed()
            // Cancelled before replying while still presented (Activity recreation): re-fire once.
            // Needed when pending has not ended (the override is still owed) AND — even for a
            // presentation that never started pending (`start(false)` ends it at once, e.g. a
            // revisit with a cached override) — while `onStepViewed` is still owed: the re-fire is
            // the only path left that fires it. Only `end()` depends on `ended`.
            if (!replied && gen == presentation.generation &&
                (!presentation.ended || !presentation.stepViewedFired)
            ) presentation.needsRefire = true
        }
    }
    presentation.fireStepViewedIfOwed()
}

internal const val HOST_DATA_DEADLINE_MS = 3_000L

/**
 * The Activity's `LaunchedEffect(currentIndex)` gate, extracted so the shared fixture drives the SAME
 * decision: fire on a genuine index change (the `lastViewedStepIndex` guard, — never on
 * a plain Activity recreation), OR — once more when the presentation's previous call was
 * cancelled before replying (`needsRefire`). A null [lastViewedStepIndex] (no ViewModel) never guards.
 * Yields first on a same-index relaunch so the dying composition's cancelled call has recorded that
 * it was cut off. Returns whether it fired.
 */
internal suspend fun beforeStepRenderEffect(
    stepIndex: Int,
    stepCount: Int,
    presentation: HostDataPresentation?,
    lastViewedStepIndex: () -> Int?,
    setLastViewedStepIndex: (Int) -> Unit,
    fire: suspend () -> Unit,
): Boolean {
    if (stepIndex == lastViewedStepIndex()) kotlinx.coroutines.yield()
    val refire = presentation?.needsRefire == true
    if (stepIndex >= stepCount || (stepIndex == lastViewedStepIndex() && !refire)) return false
    setLastViewedStepIndex(stepIndex)
    fire()
    return true
}
