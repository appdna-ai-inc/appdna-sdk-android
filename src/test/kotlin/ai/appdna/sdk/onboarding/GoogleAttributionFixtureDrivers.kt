package ai.appdna.sdk.onboarding

import ai.appdna.sdk.core.interpolated

/**
 * Android drivers for Google's attribution, dispatched from `SharedFixtureTest`. Every value they
 * return is produced by REAL SDK code:
 *
 *   location_field_attribution  REAL: `LocationAnswer.carriesPlaceId`, the one rule both location
 *                               composables read to keep Google's logo beside a field.
 *   onboarding_google_credit    REAL: the step parsed by the SDK's own `OnboardingConfigParser` (its
 *                               published source and id-stamped raw blocks captured as for a published
 *                               flow), the override's options through `StepConfigOverride.decodeFieldOptions`
 *                               (the decoder every bridge uses), then `GoogleCredit.shows(step, …)` — the
 *                               call `OnboardingStepView` makes — and, for an option sheet,
 *                               `GoogleCreditSheetContext.shows`, the call the sheet makes, handed the
 *                               option's sheet blocks as the REAL pipeline resolved them.
 *
 * The dropdown's logo and the field's attribution through a tap are asserted by the
 * `location_resolve_request` kind (`list_google_logo` / `field_google_attribution` observables).
 *
 * The runner converts the fixture's JSON to plain maps and lists (JSON null → null) before calling.
 */
internal object GoogleAttributionFixtureDrivers {

    /** `action.cases[] = {name, stored_answer}` → `[{name, google_attribution}]`. */
    fun locationFieldAttribution(cases: List<Map<String, Any?>>): List<Map<String, Any?>> =
        cases.map { c ->
            val name = c["name"] as? String ?: throw AssertionError("a case needs a name")
            check(c.containsKey("stored_answer")) { "case $name needs stored_answer" }
            linkedMapOf("name" to name, "google_attribution" to LocationAnswer.carriesPlaceId(c["stored_answer"]))
        }

    /**
     * `action.cases[] = {name, step, block_id?}` → `[{name, backdrop, variant_light}]`: the step parsed
     * by the SDK's own parser, then `GoogleMapsAttribution.backdrop(ofStep, type)` — what the step
     * container provides — and, for a block, `backdrop(ofBlock, behind)` applied at every block from the
     * step down to it, as each block's wrapper applies it.
     */
    @Suppress("UNCHECKED_CAST")
    fun googleAttributionBackdrop(cases: List<Map<String, Any?>>): List<Map<String, Any?>> {
        fun chain(id: String, blocks: List<ContentBlock>): List<ContentBlock>? {
            for (b in blocks) {
                if (b.id == id) return listOf(b)
                chain(id, b.children.orEmpty() + b.stack_children.orEmpty())?.let { return listOf(b) + it }
            }
            return null
        }
        return cases.map { c ->
            val name = c["name"] as? String ?: throw AssertionError("a case needs a name")
            val step = OnboardingConfigParser.parseStepForTest(stripNulls(c["step"]) as Map<String, Any>)
                ?: throw AssertionError("$name: the SDK's parser refused the step")
            // What is behind the logo, given the surface an unstyled dropdown is filled with.
            fun backdropWith(surface: androidx.compose.ui.graphics.Color): GoogleMapsAttribution.Backdrop {
                var backdrop = GoogleMapsAttribution.backdrop(ofStep = step.config, type = step.type)
                (c["block_id"] as? String)?.let { blockId ->
                    val path = chain(blockId, step.config.content_blocks.orEmpty())
                        ?: throw AssertionError("$name: no block $blockId")
                    for (b in path) backdrop = GoogleMapsAttribution.backdrop(ofBlock = b, behind = backdrop)
                    // `dropdown`: what is behind the logo under the block's location dropdown, the call the
                    // block's composable makes with what is behind the dropdown.
                    if (c["dropdown"] == true) {
                        backdrop = GoogleMapsAttribution.backdrop(
                            ofLocationDropdown = path.last().field_config,
                            behind = backdrop,
                            surface = surface,
                        )
                    }
                }
                return backdrop
            }
            fun variantName(b: GoogleMapsAttribution.Backdrop, dark: Boolean) =
                GoogleMapsAttribution.variant(b, darkAppearance = dark).name.lowercase()
            // The onboarding host's colour scheme, which follows the system appearance: an unstyled dropdown
            // is filled with its surface.
            val light = backdropWith(androidx.compose.material3.lightColorScheme().surface)
            if (c["appearances"] == true) {
                // The variant in each appearance, as the step draws it there. No backdrop: an unstyled fill
                // is a theme colour on this platform and the system background on iOS, and the variant is
                // what the two must agree on.
                val dark = backdropWith(androidx.compose.material3.darkColorScheme().surface)
                return@map linkedMapOf(
                    "name" to name,
                    "variant_light" to variantName(light, dark = false),
                    "variant_dark" to variantName(dark, dark = true),
                )
            }
            val described = when (val b = light) {
                is GoogleMapsAttribution.Backdrop.Plain -> b.hex?.let { "plain:$it" } ?: "plain"
                GoogleMapsAttribution.Backdrop.Busy -> "busy"
            }
            linkedMapOf(
                "name" to name,
                "backdrop" to described,
                "variant_light" to variantName(light, dark = false),
            )
        }
    }

    /**
     * `action.cases[] = {name, markdown, surface?}` → `[{name, links: [{text, url}]}]`: each line through
     * `parseMarkdownToAnnotatedString` — the parser rich text and an agreement's copy draw with — and the
     * spans carrying a URL annotation. `surface: "rich_text"` first runs the rich-text block's own
     * `normaliseMarkdown` (which drops a code span's backticks); `"agreement"`, or no surface, hands the
     * parser the line as written, as an agreement's copy does; `++underline++` is drawn on `rich_text`
     * only. `draw: true` adds `runs` ([drawnRuns]).
     */
    fun markdownLinks(cases: List<Map<String, Any?>>): List<Map<String, Any?>> = cases.map { c ->
        val name = c["name"] as? String ?: throw AssertionError("a case needs a name")
        val raw = c["markdown"] as? String ?: throw AssertionError("case $name needs markdown")
        val surface = c["surface"] as? String
        val md = when (surface) {
            null, "agreement" -> raw
            "rich_text" -> normaliseMarkdown(raw)
            else -> throw AssertionError("case $name: unknown surface '$surface'")
        }
        val drawn = parseMarkdownToAnnotatedString(
            md, androidx.compose.ui.text.TextStyle(), androidx.compose.ui.graphics.Color.Blue,
            underlineMarkers = surface == "rich_text",
        )
        val links = drawn.getStringAnnotations("URL", 0, drawn.length).map { a ->
            linkedMapOf<String, Any?>("text" to drawn.text.substring(a.start, a.end), "url" to a.item)
        }
        val result = linkedMapOf<String, Any?>("name" to name, "links" to links)
        if (c["draw"] == true) result["runs"] = drawnRuns(drawn)
        result
    }

    /**
     * What [drawn] shows, one entry per stretch drawn the same way: `{text, style?: [bold, italic,
     * underline, strike, code], link?}`, read off its span styles and URL annotations. `underline` is
     * the `++` marker's (a link is always underlined); `code` is the monospaced face.
     */
    fun drawnRuns(drawn: androidx.compose.ui.text.AnnotatedString): List<Map<String, Any?>> {
        val out = mutableListOf<MutableMap<String, Any?>>()
        var lastKey: String? = null
        for (k in drawn.text.indices) {
            val spans = drawn.spanStyles.filter { k >= it.start && k < it.end }.map { it.item }
            val link = drawn.getStringAnnotations("URL", k, k + 1).firstOrNull()?.item
            fun has(d: androidx.compose.ui.text.style.TextDecoration) = spans.any { it.textDecoration?.contains(d) == true }
            val style = listOfNotNull(
                "bold".takeIf { spans.any { it.fontWeight == androidx.compose.ui.text.font.FontWeight.Bold } },
                "italic".takeIf { spans.any { it.fontStyle == androidx.compose.ui.text.font.FontStyle.Italic } },
                "underline".takeIf { link == null && has(androidx.compose.ui.text.style.TextDecoration.Underline) },
                "strike".takeIf { has(androidx.compose.ui.text.style.TextDecoration.LineThrough) },
                "code".takeIf { spans.any { it.fontFamily == androidx.compose.ui.text.font.FontFamily.Monospace } },
            )
            val key = style.joinToString(",") + "|" + (link ?: "")
            if (key == lastKey) {
                out.last()["text"] = (out.last()["text"] as String) + drawn.text[k]
                continue
            }
            val entry = linkedMapOf<String, Any?>("text" to drawn.text[k].toString())
            if (style.isNotEmpty()) entry["style"] = style
            if (link != null) entry["link"] = link
            out += entry
            lastKey = key
        }
        return out
    }

    /**
     * `setup.config.step` = one onboarding step as published; `setup.session_data` = {responses (the
     * flow's accumulated answers), session_store (the session store's onboarding responses), step (the
     * step's inputs)}. `action`: override {title, subtitle, cta_text, field_defaults, field_options, data},
     * field_config_overrides, field_options_overrides (the interaction patches), sheet {block_id,
     * option_id, inputs}. Returns `shows_credit`, and `sheet_credit` when the action names a sheet.
     */
    @Suppress("UNCHECKED_CAST")
    fun onboardingGoogleCredit(
        stepJson: Map<String, Any?>,
        session: Map<String, Any?>,
        action: Map<String, Any?>,
    ): Map<String, Any?> {
        val step = OnboardingConfigParser.parseStepForTest(stripNulls(stepJson) as Map<String, Any>)
            ?: throw AssertionError("the SDK's parser refused setup.config.step")
        val accumulated = (session["responses"] as? Map<String, Any?>).orEmpty()
        val stepInputs = (session["step"] as? Map<String, Any?>).orEmpty()
        val store = (session["session_store"] as? Map<String, Any?>).orEmpty()
        // The TemplateEngine roots (`input`, `onboarding`) read the session store, on the onboarding
        // surface — as the step's context does.
        val templateContext = ai.appdna.sdk.core.TemplateContext(
            userTraits = null,
            remoteConfig = { null },
            onboardingResponses = (stripNulls(store) as Map<String, Any>)
                .mapNotNull { (k, v) -> (v as? Map<String, Any>)?.let { k to it } }.toMap(),
            computedData = emptyMap(),
            sessionData = emptyMap(),
            deviceInfo = emptyMap(),
            surface = ai.appdna.sdk.core.TemplateSurface.ONBOARDING,
        )
        // `action.select = {step, block_id, option_id | option_ids, in_sheet?, override?,
        // field_options_overrides?}` (or a list of them, picked in order): an EARLIER step, resolved
        // through the REAL pipeline with the same answers and the picks before it, and the option(s) the
        // user picked there, recorded by the REAL `SelectedOptionStore` — the `selected` root this step
        // reads.
        var selected: Map<String, Any?> = emptyMap()
        var selectedLabel: String? = null
        val picks: List<Map<String, Any?>> = when (val sel = action["select"]) {
            is List<*> -> sel.filterIsInstance<Map<String, Any?>>()
            is Map<*, *> -> listOf(sel as Map<String, Any?>)
            else -> emptyList()
        }
        SelectedOptionStore.resetForTesting()
        try {
        for (pick in picks) {
            val pickStep = OnboardingConfigParser.parseStepForTest(stripNulls(pick["step"]) as Map<String, Any>)
                ?: throw AssertionError("the SDK's parser refused action.select.step")
            val pickOverride = (pick["override"] as? Map<String, Any?>)?.let { o ->
                StepConfigOverride(fieldOptions = StepConfigOverride.decodeFieldOptions(stripNulls(o["field_options"])))
            }
            val resolvedPick = HostDataStepPipeline(pickStep, legacyContext = { templateContext }).resolve(
                HostDataInputs(
                    responses = accumulated, stepInputs = emptyMap(), selected = selected, stepOverride = pickOverride,
                    fieldOptionsOverrides = StepConfigOverride.decodeFieldOptions(stripNulls(pick["field_options_overrides"])).orEmpty(),
                ),
            )
            // The picked Select at any depth: a container's child records its pick like a top-level one.
            // `in_sheet = {block_id, option_id, inputs?}`: the Select is drawn in that option's sheet, as
            // the sheet draws it (`resolveSheetBlocks` over the raw sheet the pipeline kept), against the
            // sheet's own inputs.
            val pickBlocks = (pick["in_sheet"] as? Map<String, Any?>)?.let { inSheet ->
                val ownerId = inSheet["block_id"] as? String
                val optionId = inSheet["option_id"] as? String
                val opened = findBlock(ownerId, resolvedPick.blocks)?.field_options.orEmpty()
                    .firstOrNull { (it.id ?: it.value) == optionId }
                val raw = resolvedPick.sheetRaw["$ownerId|$optionId"]
                // `in_sheet.inputs`: what the user entered in the sheet before picking (its own inputs).
                val sheetInputs = (inSheet["inputs"] as? Map<String, Any?>).orEmpty()
                if (raw != null) resolveSheetBlocks(raw, sheetInputs, opened?.sheet_blocks.orEmpty()) else opened?.sheet_blocks.orEmpty()
            } ?: resolvedPick.blocks
            val block = findBlock(pick["block_id"] as? String, pickBlocks)
                ?: throw AssertionError("the picked block is not on the resolved step")
            // Each picked option is TAPPED through the production write (`recordOptionPick`), in order, so a
            // fixture can only assert a pick its own Select can make: several ids on a single Select leave the
            // last one picked, as a user's taps would.
            val ids = (pick["option_ids"] as? List<*>)?.filterIsInstance<String>()
                ?: listOfNotNull(pick["option_id"] as? String)
            val all = block.field_options.orEmpty()
            val options = ids.map { id ->
                all.firstOrNull { (it.id ?: it.value) == id }
                    ?: throw AssertionError("the picked option $id is not on the resolved step")
            }
            if (options.isEmpty()) throw AssertionError("the pick names no option")
            val pickInputs = mutableMapOf<String, Any>()
            for (option in options) recordOptionPick(block, all, option.storedAnswer(), pickInputs)
            selectedLabel = options.last().label
            selected = SelectedOptionStore.snapshot()
        }
        } finally {
            SelectedOptionStore.resetForTesting()
        }
        val answers = GoogleCredit.Answers(
            accumulated = accumulated, stepInputs = stepInputs, sessionStore = store, selected = selected,
        )

        val override = (action["override"] as? Map<String, Any?>)?.let { o ->
            StepConfigOverride(
                fieldDefaults = (o["field_defaults"] as? Map<String, Any?>)?.let { stripNulls(it) as Map<String, Any> },
                title = o["title"] as? String,
                subtitle = o["subtitle"] as? String,
                ctaText = o["cta_text"] as? String,
                fieldOptions = StepConfigOverride.decodeFieldOptions(stripNulls(o["field_options"])),
                // The host's data for the step: the `hook_data` root.
                dataContext = (o["data"] as? Map<String, Any?>)?.let { stripNulls(it) as Map<String, Any> },
            )
        }
        val fieldConfigOverrides = ((action["field_config_overrides"] as? Map<String, Any?>).orEmpty())
            .mapValues { (_, v) -> stripNulls(v) as Map<String, Any> }
        val fieldOptionsOverrides = StepConfigOverride.decodeFieldOptions(stripNulls(action["field_options_overrides"]))
            .orEmpty()

        val out = linkedMapOf<String, Any?>(
            "shows_credit" to GoogleCredit.shows(step, override, fieldConfigOverrides, fieldOptionsOverrides, answers),
        )
        if (action.containsKey("select")) out["selected_label"] = selectedLabel

        // The step as the REAL pipeline resolves it (raw pass → decode → override → interaction
        // layering) — what the step draws, and what it stores.
        val resolveStep = {
            HostDataStepPipeline(step, legacyContext = { templateContext }).resolve(
                HostDataInputs(
                    // The base `hook_data`, as `liveHostDataInputs` passes it for a step with no
                    // interaction layer.
                    hookData = override?.dataContext,
                    responses = accumulated,
                    stepInputs = stepInputs,
                    selected = selected,
                    stepOverride = override,
                    fieldConfigOverrides = fieldConfigOverrides,
                    fieldOptionsOverrides = fieldOptionsOverrides,
                ),
            )
        }

        // The option sheet `action.sheet` opens, as its renderer draws it: the presented option's raw
        // sheet blocks from the REAL pipeline, through `resolveSheetBlocks` — the call the sheet makes —
        // so its deferred `{{step.…}}` paths resolve against the sheet's own inputs.
        val sheetSpec = action["sheet"] as? Map<String, Any?>
        val drawnSheet = {
            val resolved = resolveStep()
            val blockId = sheetSpec?.get("block_id") as? String
            val optionId = sheetSpec?.get("option_id") as? String
            val option = resolved.blocks.firstOrNull { it.id == blockId }?.field_options.orEmpty()
                .firstOrNull { (it.id ?: it.value) == optionId }
            val raw = resolved.sheetRaw["$blockId|$optionId"]
            val inputs = (sheetSpec?.get("inputs") as? Map<String, Any?>).orEmpty()
            if (raw != null) resolveSheetBlocks(raw, inputs, option?.sheet_blocks.orEmpty()) else option?.sheet_blocks.orEmpty()
        }

        // `action.read[] = {name, via?, block_id?, option_id?, key}`: one value as a renderer draws or
        // stores it — an option's `label` / `value`, or a block's `text` / `action_value` /
        // `default_value` (its `field_config.default_value`) / `image_url` / `video_url` / `lottie_url` /
        // `markdown_content`, the block found at any depth. `via`: `pipeline` (default) the step the
        // REAL pipeline resolves; `view_pass` the same step without raw capture, each block drawn through
        // `resolveBlockBindings` (the view-level pass the renderer runs on it); `sheet` the sheet above;
        // `legacy` the step's `title` / `subtitle` / `cta_text` through `interpolated()`, as every legacy
        // view draws them. Absent → null.
        (action["read"] as? List<Map<String, Any?>>)?.let { reads ->
            val cache = HashMap<String, List<ContentBlock>>()
            fun blocks(via: String): List<ContentBlock> = cache.getOrPut(via) {
                when (via) {
                    // The view-level pass reads the `selected` root from the shared store.
                    "view_pass", "screen" -> withShared(selected) {
                        val bare = step.copy(rawContentBlocks = null)
                        bare.config.content_blocks.orEmpty().map {
                            // The call the block renderer makes for a block the raw pass did not
                            // produce: a step with no raw capture layers the host's interaction options
                            // and patches there; a Screen has none.
                            viewPassBlock(
                                it, hookData = null,
                                responses = stripNulls(accumulated) as Map<String, Any>,
                                stepInputs = stripNulls(stepInputs) as Map<String, Any>,
                                // A Screen draws the same blocks with the surface a renderer outside an
                                // onboarding step has (`LocalTemplateSurface`'s default): no credit there.
                                surface = if (via == "screen") ai.appdna.sdk.core.TemplateSurface.OTHER
                                else ai.appdna.sdk.core.TemplateSurface.ONBOARDING,
                                fieldConfigOverrides = if (via == "screen") emptyMap() else fieldConfigOverrides,
                                fieldOptionsOverrides = if (via == "screen") emptyMap() else fieldOptionsOverrides,
                            )
                        }
                    }
                    "sheet" -> drawnSheet()
                    else -> resolveStep().blocks
                }
            }
            val legacyContext = templateContext
            out["read"] = reads.associate { r ->
                val name = r["name"] as? String ?: throw AssertionError("a read needs a name")
                val key = r["key"] as? String ?: throw AssertionError("read $name needs a key")
                val via = r["via"] as? String ?: "pipeline"
                if (via == "legacy") {
                    val source = when (key) {
                        "title" -> step.config.title
                        "subtitle" -> step.config.subtitle
                        "cta_text" -> step.config.cta_text
                        else -> throw AssertionError("read $name: unknown legacy key $key")
                    }
                    return@associate name to source?.interpolated(null, legacyContext)
                }
                val block = findBlock(r["block_id"] as? String, blocks(via))
                val optionId = r["option_id"] as? String
                val planId = r["plan_id"] as? String
                val value: Any? = if (planId != null) {
                    // What a pick of the plan stores (`selected_plan_label`).
                    if (key != "stored_plan_label") throw AssertionError("read $name: unknown plan key $key")
                    block?.pricing_plans.orEmpty().firstOrNull { it.id == planId }?.storedLabel
                } else if (optionId != null) {
                    val option = block?.field_options.orEmpty().firstOrNull { (it.id ?: it.value) == optionId }
                    when (key) {
                        "label" -> option?.label
                        "value" -> option?.value
                        // The text a pick of the option records for its label when the label took a
                        // Google answer's content: what the user typed. Null when it took none.
                        "google_label" -> option?.googleKeys?.get("label")
                        else -> throw AssertionError("read $name: unknown option key $key")
                    }
                } else {
                    when (key) {
                        "text" -> block?.text
                        "action_value" -> block?.action_value
                        "default_value" -> block?.field_config?.get("default_value")
                        "image_url" -> block?.image_url
                        "video_url" -> block?.video_url
                        "lottie_url" -> block?.lottie_url
                        "markdown_content" -> block?.markdown_content
                        else -> throw AssertionError("read $name: unknown block key $key")
                    }
                }
                name to matchRead(r["match"] as? Map<String, Any?>, value)
            }
        }

        (action["sheet"] as? Map<String, Any?>)?.let { sheet ->
            val blockId = sheet["block_id"] as? String
            val optionId = sheet["option_id"] as? String
            val inputs = (sheet["inputs"] as? Map<String, Any?>).orEmpty()
            val context = GoogleCreditSheetContext(
                sources = { GoogleCredit.stepSources(step, override, fieldConfigOverrides, fieldOptionsOverrides) },
                answers = answers,
            )
            // The presented option, as the sheet receives it in production: from the step the REAL
            // pipeline resolved (raw pass → decode → override → interaction layering), so its sheet
            // blocks have every non-`step` token substituted and a `repeat` has generated its options.
            val resolved = resolveStep()
            val option = resolved.blocks.firstOrNull { it.id == blockId }?.field_options.orEmpty()
                .firstOrNull { (it.id ?: it.value) == optionId }
                ?: throw AssertionError("the resolved step has no option $optionId on $blockId")
            val presented = option.sheet_blocks.orEmpty()
            out["presented_sheet_text"] = presented.mapNotNull { it.text }
            out["sheet_credit"] = context.shows(blockId, optionId, presented, inputs)
        }
        return out
    }

    /**
     * [body] run with the shared `SelectedOptionStore` holding [selected] — the store the view-level pass
     * reads the `selected` root from — and emptied after.
     */
    private fun <T> withShared(selected: Map<String, Any?>, body: () -> T): T {
        SelectedOptionStore.resetForTesting()
        for ((field, value) in selected) if (value != null) SelectedOptionStore.seedForTesting(field, value)
        try {
            return body()
        } finally {
            SelectedOptionStore.resetForTesting()
        }
    }

    /**
     * `read.match = {contains?, lacks?}`: a value whose exact text is not portable (a picked record drawn
     * whole, which each platform stringifies its own way) is checked for what it must and must not
     * hold; the read reports `"matched"`, or the value itself when a check fails, so the failure shows it.
     */
    private fun matchRead(match: Map<String, Any?>?, value: Any?): Any? {
        if (match == null) return value
        val text = value?.toString() ?: return null
        val contains = (match["contains"] as? List<*>).orEmpty().filterIsInstance<String>()
        val lacks = (match["lacks"] as? List<*>).orEmpty().filterIsInstance<String>()
        return if (contains.all { text.contains(it) } && lacks.none { text.contains(it) }) "matched" else text
    }

    /** The block with [id] among [blocks] at any depth: `children`, `stack_children` and option sheets. */
    private fun findBlock(id: String?, blocks: List<ContentBlock>): ContentBlock? {
        if (id == null) return null
        for (b in blocks) {
            if (b.id == id) return b
            val nested = b.children.orEmpty() + b.stack_children.orEmpty() +
                b.field_options.orEmpty().flatMap { it.sheet_blocks.orEmpty() }
            findBlock(id, nested)?.let { return it }
        }
        return null
    }

    /** JSON nulls dropped, as the SDK's own parsers expect (`Map<String, Any>`). */
    private fun stripNulls(v: Any?): Any? = when (v) {
        is Map<*, *> -> LinkedHashMap<String, Any>().also { out ->
            for ((k, value) in v) if (value != null) out[k.toString()] = stripNulls(value)!!
        }
        is List<*> -> v.mapNotNull { stripNulls(it) }
        else -> v
    }
}
