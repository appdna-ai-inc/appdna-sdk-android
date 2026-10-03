@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package ai.appdna.sdk.onboarding

// Wheel-picker measurement mode (Android leg).
//
// Opt-in measurement branch for the `wheel_picker` content block. Enabled only
// when `block.field_config["measurement_type"]` is present AND the `units[]`
// resolve to a usable set (runtime guards below); otherwise the legacy drum in
// `WheelPickerBlock` renders UNCHANGED.
//
// EVERYTHING here lives under the generic `field_config` passthrough map — no
// new top-level `ContentBlock` field (Android is at the 245/245 ctor-arg budget).
//
// The measurement wrapper OWNS persistence for ALL four styles (ruler / gauge /
// dial / wheel): it holds an unrounded BASE value (`units[0]`), and on every
// interaction snaps+clamps the base and writes the self-consistent sibling keys.
// The `wheel`/`dial` styles therefore use a RENDER-ONLY drum that never
// self-persists (unlike the legacy drum in `WheelPickerBlock`).
//
// Parity contract is byte-identical to iOS
// (Onboarding/MeasurementWheelBlockView.swift + MeasurementPersistenceTests.swift).

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.appdna.sdk.core.StyleEngine
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

// MARK: - Pure model (unit-tested — see MeasurementWheelPersistenceTest.kt)

/**
 * A single measurement unit resolved from `field_config.units[]`.
 * `units[0]` is the canonical BASE unit (`factor==1`, `offset==0` by convention).
 */
data class MeasurementUnit(
    val id: String,
    val label: String,
    val min: Double,
    val max: Double,
    val step: Double,
    val decimals: Int,
    val factor: Double,
    val offset: Double,
)

/**
 * Pinned half-away-from-zero rounding: `sign(x) * floor(abs(x) + 0.5)`.
 * The ONE algorithm shared across iOS/Android/JS — it deliberately does NOT use
 * Kotlin's `Math.round`/`roundToLong` (which is half-up, differs on negatives) so
 * negatives match Swift/JS. `sign(0) == 0`.
 */
fun measurementRoundHalfAway(x: Double): Double = when {
    x > 0 -> floor(x + 0.5)
    x < 0 -> -floor(-x + 0.5)
    else -> 0.0
}

/**
 * Snap a value to `unit.step`/`unit.decimals`, THEN clamp to `[unit.min, unit.max]`.
 * Clamp is the FINAL op and uses `min(max(d,lo),hi)` (never Kotlin `coerceIn`, which
 * throws on `lo > hi`) so non-step-aligned custom ranges still land on the boundary.
 */
fun measurementSnap(value: Double, unit: MeasurementUnit): Double {
    val step = if (unit.step > 0) unit.step else 1.0
    // step-snap in integer domain (half away from zero)
    val q = value / step
    val n = measurementRoundHalfAway(q)
    val s = n * step
    // decimal-normalize in integer domain
    val decimals = if (unit.decimals > 0) unit.decimals else 0
    var p = 1.0
    repeat(decimals) { p *= 10.0 }
    val d = measurementRoundHalfAway(s * p) / p
    // clamp LAST — non-throwing form (safe even if lo > hi upstream)
    return minOf(maxOf(d, unit.min), unit.max)
}

/** Convert a value expressed in `unit` to the canonical base: `(value - offset) / factor`. */
fun measurementToBase(value: Double, unit: MeasurementUnit): Double {
    val f = if (unit.factor != 0.0) unit.factor else 1.0
    return (value - unit.offset) / f
}

/** Convert a base value into `unit`: `base * factor + offset`. */
fun measurementFromBase(base: Double, unit: MeasurementUnit): Double = base * unit.factor + unit.offset

/**
 * Persist as a whole integer (`Long`, mirroring the legacy drum's
 * `if (v == v.toLong().toDouble()) v.toLong() else v` at ContentBlockRenderer.kt:5674)
 * when whole, `Double` otherwise. Keeps `answer_equals` numeric routing consistent.
 */
fun measurementScalar(v: Double): Any = if (v == v.toLong().toDouble()) v.toLong() else v

/**
 * The canonical persist + delegate-payload derivation. Given an (unrounded) base and
 * the current display unit, produces:
 *   - the `inputValues` patch: `{ fieldId: snapped+clamped BASE, _unit: base id,
 *     _display_unit: display id, _display_value: snapped display }`
 *   - the `onElementInteraction` payload: `{ value: base, display_value, unit: display id }`
 * The persisted scalar is UNIT-STABLE — flipping the toggle holds the base constant so
 * the scalar does not change; only the display keys / payload change.
 */
data class MeasurementSnapshot(
    val inputValues: Map<String, Any>,
    val payload: Map<String, Any>,
    val snappedBase: Double,
    val display: Double,
)

fun measurementSnapshot(
    fieldId: String,
    base: Double,
    baseUnit: MeasurementUnit,
    displayUnit: MeasurementUnit,
): MeasurementSnapshot {
    val snappedBase = measurementSnap(base, baseUnit)
    val display = measurementSnap(measurementFromBase(base, displayUnit), displayUnit)
    val baseScalar = measurementScalar(snappedBase)
    val displayScalar = measurementScalar(display)

    val iv = linkedMapOf<String, Any>(
        fieldId to baseScalar,                          // BASE scalar (units[0])
        "${fieldId}_unit" to baseUnit.id,               // annotates the BASE scalar
        "${fieldId}_display_unit" to displayUnit.id,    // user's chosen unit
        "${fieldId}_display_value" to displayScalar,    // value in the display unit
    )
    val payload = linkedMapOf<String, Any>(
        "value" to baseScalar,
        "display_value" to displayScalar,
        "unit" to displayUnit.id,
    )
    return MeasurementSnapshot(iv, payload, snappedBase, display)
}

/** Resolved measurement configuration parsed from `field_config`. */
data class MeasurementConfig(
    val type: String,
    val units: List<MeasurementUnit>,
    val initialUnitIndex: Int,
    val style: String, // ruler | gauge | dial | wheel
    val defaultBase: Double,
    val tickColorHex: String?,
    val trackColorHex: String?,
    val needleColorHex: String?,
    val toggleActiveColorHex: String?,
    val majorTickInterval: Int,
    val valueFontSize: Double,
    val unitFontSize: Double,
)

private val measurementValidStyles = setOf("ruler", "gauge", "dial", "wheel")

/**
 * Coerce a JSON-decoded value (Number/String) to Double. `field_config` numerics MAY
 * arrive as strings when not passed through `normalize-step-numerics` (e.g. nested in a
 * stack), so tolerate String too — defensively, per the spec.
 */
private fun measurementNum(v: Any?): Double? = when (v) {
    is Double -> v
    is Float -> v.toDouble()
    is Int -> v.toDouble()
    is Long -> v.toDouble()
    is Number -> v.toDouble()
    is String -> v.toDoubleOrNull()
    else -> null
}

/**
 * Parse + validate the measurement config. Returns `null` (→ legacy drum fallback) when
 * `measurement_type` is unset, `units` is empty/unresolvable, or ANY unit is degenerate
 * (`factor==0`, `step<=0`, `min>=max`). A `unit_default` that matches no unit is NOT a
 * fallback trigger — it resolves to `units[0]` and stays in measurement mode.
 */
fun parseMeasurementConfig(block: ContentBlock): MeasurementConfig? {
    val cfg = block.field_config ?: return null
    val type = (cfg["measurement_type"] as? String)?.takeIf { it.isNotEmpty() } ?: return null
    val rawUnits = (cfg["units"] as? List<*>)?.takeIf { it.isNotEmpty() } ?: return null

    val units = ArrayList<MeasurementUnit>(rawUnits.size)
    for (el in rawUnits) {
        val d = el as? Map<*, *> ?: return null                            // unresolvable → fallback
        val id = (d["id"] as? String)?.takeIf { it.isNotEmpty() } ?: return null
        val mn = measurementNum(d["min"]) ?: return null
        val mx = measurementNum(d["max"]) ?: return null
        val st = measurementNum(d["step"]) ?: return null
        val fc = measurementNum(d["factor"]) ?: return null                // missing numeric → fallback
        // Runtime guards → unit unusable → fall back to the legacy drum.
        if (fc == 0.0 || st <= 0.0 || mn >= mx) return null
        val label = (d["label"] as? String) ?: id
        val decimals = (measurementNum(d["decimals"]) ?: 0.0).toInt()
        val offset = measurementNum(d["offset"]) ?: 0.0
        units.add(
            MeasurementUnit(
                id = id, label = label, min = mn, max = mx, step = st,
                decimals = maxOf(0, decimals), factor = fc, offset = offset,
            ),
        )
    }
    if (units.isEmpty()) return null

    // find-or-default (never Kotlin `first { }`, which throws)
    val unitDefault = cfg["unit_default"] as? String
    val initialIndex = if (unitDefault == null) 0
    else units.indexOfFirst { it.id == unitDefault }.let { if (it < 0) 0 else it }

    val rawStyle = (cfg["measurement_style"] as? String) ?: "ruler"
    val style = if (rawStyle in measurementValidStyles) rawStyle else "ruler"

    val base = units[0]
    val defaultBase = measurementNum(cfg["measurement_default"]) ?: ((base.min + base.max) / 2.0)

    val majorRaw = (measurementNum(cfg["major_tick_interval"]) ?: 5.0).toInt()
    val major = if (majorRaw > 0) majorRaw else 5
    val vfsRaw = measurementNum(cfg["value_font_size"]) ?: 34.0
    val vfs = if (vfsRaw > 0) vfsRaw else 34.0
    val ufsRaw = measurementNum(cfg["unit_font_size"]) ?: 15.0
    val ufs = if (ufsRaw > 0) ufsRaw else 15.0

    return MeasurementConfig(
        type = type,
        units = units,
        initialUnitIndex = initialIndex,
        style = style,
        defaultBase = defaultBase,
        tickColorHex = cfg["tick_color"] as? String,
        trackColorHex = cfg["track_color"] as? String,
        needleColorHex = cfg["needle_color"] as? String,
        toggleActiveColorHex = cfg["toggle_active_color"] as? String,
        majorTickInterval = major,
        valueFontSize = vfs,
        unitFontSize = ufs,
    )
}

// MARK: - Render helpers (pure)

/** Discrete values in the given unit, from min to max by step. */
private fun generateMeasurementValues(u: MeasurementUnit): List<Double> {
    val vals = ArrayList<Double>()
    val step = if (u.step > 0) u.step else 1.0
    var c = u.min
    var guard = 0
    while (c <= u.max + step * 1e-6 && guard < 100_000) {
        vals.add(minOf(c, u.max))
        c += step
        guard++
    }
    return if (vals.isEmpty()) listOf(u.min) else vals
}

private fun nearestMeasurementIndex(value: Double, vals: List<Double>): Int {
    if (vals.isEmpty()) return 0
    var best = 0
    var bestDist = Double.MAX_VALUE
    vals.forEachIndexed { i, v ->
        val d = abs(v - value)
        if (d < bestDist) { bestDist = d; best = i }
    }
    return best
}

private fun formatMeasurement(v: Double, unit: MeasurementUnit): String =
    if (unit.decimals <= 0) measurementRoundHalfAway(v).toLong().toString()
    else "%.${unit.decimals}f".format(java.util.Locale.US, v)

// MARK: - Measurement view (wrapper OWNS persistence for all 4 styles)

@Composable
internal fun MeasurementWheelBlock(
    block: ContentBlock,
    config: MeasurementConfig,
    inputValues: MutableMap<String, Any>,
    // Fired ("value_changed", base value) on a real user commit (pick / unit switch),
    // NEVER on the pristine onAppear seed and never per-tick. Default no-op for the test path.
    onInteract: (String, String, String?) -> Unit = { _, _, _ -> },
) {
    val isDark = isSystemInDarkTheme()
    val fieldId = block.field_id ?: block.id
    val baseUnit = config.units[0]

    // Unrounded BASE value (`units[0]`) — the single source of truth. Display in any
    // unit is derived; the persisted scalar is snapped+clamped from this.
    var holdBase by remember { mutableStateOf(0.0) }
    var unitIndex by remember { mutableStateOf(0) }
    // Required-field gate parity: for a required field we do NOT persist the seeded
    // default until the user actually interacts (matches the legacy drum).
    var hasUserInteracted by remember { mutableStateOf(false) }

    fun unitAt(i: Int): MeasurementUnit =
        if (i in config.units.indices) config.units[i] else config.units[0]

    val currentUnit = unitAt(unitIndex)
    // Snapped display value in the current unit (base held constant + re-clamped).
    val currentDisplay = measurementSnap(measurementFromBase(holdBase, currentUnit), currentUnit)

    // Colors (pinned defaults; dark-mode-aware for tick/track).
    val tickColor = StyleEngine.parseColor(config.tickColorHex ?: if (isDark) "#475569" else "#CBD5E1")
    val trackColor = StyleEngine.parseColor(config.trackColorHex ?: if (isDark) "#334155" else "#E2E8F0")
    val accentColor = StyleEngine.parseColor(config.needleColorHex ?: block.highlight_color ?: "#6366F1")
    val toggleColor = StyleEngine.parseColor(config.toggleActiveColorHex ?: block.highlight_color ?: "#6366F1")

    // Persist + return the snapshot so callers can fire the delegate. The
    // `onElementInteraction` payload = { value, display_value, unit }; `value` (base scalar) is the
    // `value_changed` payload. The onAppear seed calls this WITHOUT firing onInteract (render-time seed).
    fun writeSnapshot(): MeasurementSnapshot {
        val snap = measurementSnapshot(fieldId, holdBase, baseUnit, unitAt(unitIndex))
        for ((k, v) in snap.inputValues) inputValues[k] = v
        return snap
    }

    // A pick from ANY style: convert the chosen display value → base, hold it, mark
    // interaction, and perform the single wrapper-owned persist + delegate fire (commit-level).
    fun pick(displayValue: Double) {
        hasUserInteracted = true
        holdBase = measurementToBase(displayValue, unitAt(unitIndex))
        val snap = writeSnapshot()
        onInteract(block.id, "value_changed", snap.payload["value"]?.toString())
    }

    fun selectUnit(idx: Int) {
        if (idx == unitIndex || idx !in config.units.indices) return
        hasUserInteracted = true
        unitIndex = idx
        // Base held constant; display recomputes + re-clamps inside the snapshot.
        val snap = writeSnapshot()
        onInteract(block.id, "value_changed", snap.payload["value"]?.toString())
    }

    LaunchedEffect(Unit) {
        unitIndex = config.initialUnitIndex
        // Restore a previously-persisted base scalar on re-entry.
        val saved = measurementNum(inputValues[fieldId])
        if (saved != null) {
            holdBase = saved
            hasUserInteracted = true
            val du = inputValues["${fieldId}_display_unit"] as? String
            if (du != null) {
                val idx = config.units.indexOfFirst { it.id == du }
                if (idx >= 0) unitIndex = idx
            }
        } else {
            holdBase = config.defaultBase
            // Required-field gate: seed at render ONLY for non-required fields (parity
            // with the legacy wheel). Required fields persist after first interaction.
            if (block.field_required != true) writeSnapshot()
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        (block.field_label ?: block.rating_label ?: block.text)?.takeIf { it.isNotEmpty() }?.let { label ->
            Text(
                text = label,
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
        }

        // Big centered value + unit — the numeric scale is LTR-locked; only the toggle
        // row follows layout direction.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = formatMeasurement(currentDisplay, currentUnit),
                    fontSize = config.valueFontSize.sp,
                    fontWeight = FontWeight.Bold,
                    color = accentColor, // value text = needle color
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = currentUnit.label,
                    fontSize = config.unitFontSize.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
            }
        }

        // Segmented unit toggle — only when there's more than one unit.
        if (config.units.size > 1) {
            Row(
                modifier = Modifier
                    .widthIn(max = 220.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(trackColor),
            ) {
                config.units.forEachIndexed { i, u ->
                    val selected = i == unitIndex
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { selectUnit(i) }
                            .background(if (selected) toggleColor else Color.Transparent)
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = u.label,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (selected) Color.White
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        )
                    }
                }
            }
        }

        // Style visual. `key(unitIndex)` resets the scroll state so the drum/ruler
        // re-centres on the equivalent value after a unit toggle.
        key(unitIndex) {
            val vals = generateMeasurementValues(currentUnit)
            when (config.style) {
                "gauge" -> MeasurementGauge(currentDisplay, currentUnit, trackColor, accentColor) { pick(it) }
                "dial" -> MeasurementDrum(vals, currentDisplay, currentUnit, accentColor, trackColor, perspective = true) { pick(it) }
                "wheel" -> MeasurementDrum(vals, currentDisplay, currentUnit, accentColor, trackColor, perspective = false) { pick(it) }
                else -> MeasurementRuler(vals, currentDisplay, currentUnit, tickColor, accentColor, config.majorTickInterval) { pick(it) }
            }
        }
    }
}

// MARK: - Ruler (fixed window of ticks CENTERED on the current value, LTR-locked)

// A scrollable list can't be relied on to scroll-to-current in a static snapshot
// (Robolectric) or reliably on device, so — mirroring iOS `rulerVisual` — we render
// a FIXED WINDOW of ticks centered on the current value with a fixed center caret over
// the middle tick. The current-value tick (off==0) sits directly under the caret.
private const val MEASUREMENT_RULER_RADIUS = 10 // ~21 ticks

@Composable
private fun MeasurementRuler(
    values: List<Double>,
    currentDisplay: Double,
    unit: MeasurementUnit,
    tickColor: Color,
    accentColor: Color,
    majorInterval: Int,
    onPick: (Double) -> Unit,
) {
    val sel = nearestMeasurementIndex(currentDisplay, values)
    val selState = rememberUpdatedState(sel)
    val tickWidth = 16.dp
    val major = maxOf(1, majorInterval)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(84.dp)
            .clipToBounds()
            .pointerInput(values) {
                var acc = 0f
                detectHorizontalDragGestures(onDragEnd = { acc = 0f }) { _, dragAmount ->
                    acc += dragAmount
                    val stepPx = 16f
                    while (acc >= stepPx) {   // drag right → lower value
                        val n = (selState.value - 1).coerceIn(0, values.lastIndex)
                        onPick(values[n]); acc -= stepPx
                    }
                    while (acc <= -stepPx) {  // drag left → higher value
                        val n = (selState.value + 1).coerceIn(0, values.lastIndex)
                        onPick(values[n]); acc += stepPx
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(verticalAlignment = Alignment.Bottom) {
                for (off in -MEASUREMENT_RULER_RADIUS..MEASUREMENT_RULER_RADIUS) {
                    val idx = sel + off
                    Column(
                        modifier = Modifier.width(tickWidth),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Bottom,
                    ) {
                        if (idx in values.indices) {
                            val isMajor = idx % major == 0
                            val isCenter = off == 0
                            if (isMajor) {
                                // Full formatted value as ONE string (not split).
                                Text(
                                    text = formatMeasurement(values[idx], unit),
                                    fontSize = 10.sp,
                                    color = tickColor,
                                    maxLines = 1,
                                    softWrap = false,
                                    modifier = Modifier.wrapContentWidth(unbounded = true),
                                )
                            } else {
                                Spacer(Modifier.height(14.dp))
                            }
                            Box(
                                modifier = Modifier
                                    .width(2.dp)
                                    .height(if (isMajor) 32.dp else 18.dp)
                                    .background(if (isCenter) accentColor else tickColor)
                                    .clickable { onPick(values[idx]) },
                            )
                        } else {
                            // Out-of-range placeholder keeps the current tick centered.
                            Spacer(Modifier.height(14.dp))
                            Spacer(Modifier.width(2.dp).height(18.dp))
                        }
                    }
                }
            }
        }
        // Fixed center caret over the middle (current-value) tick.
        Box(
            modifier = Modifier
                .width(2.dp)
                .height(46.dp)
                .background(accentColor),
        )
    }
}

// MARK: - Drum (wheel = flat; dial = perspective) — render-only, wrapper persists

// Fixed window of rows CENTERED on the current value (mirrors iOS `drumVisual`): the
// center row (off==0) sits on the track band, neighbors fade by |off|, and the `dial`
// variant adds a per-row SCALE (center 1.0 → smaller outward) for the rotary feel — NOT
// a per-row 3D rotation (which collapses layout). A scrollable list would not center in
// a static snapshot or reliably on device.
private const val MEASUREMENT_DRUM_RADIUS = 2 // 5 visible rows

@Composable
private fun MeasurementDrum(
    values: List<Double>,
    currentDisplay: Double,
    unit: MeasurementUnit,
    accentColor: Color,
    trackColor: Color,
    perspective: Boolean,
    onPick: (Double) -> Unit,
) {
    val sel = nearestMeasurementIndex(currentDisplay, values)
    val selState = rememberUpdatedState(sel)
    val rowHeight = 30.dp

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(150.dp)
            .clipToBounds()
            .pointerInput(values) {
                var acc = 0f
                detectVerticalDragGestures(onDragEnd = { acc = 0f }) { _, dragAmount ->
                    acc += dragAmount
                    val stepPx = 30f
                    while (acc >= stepPx) {   // drag down → lower value
                        val n = (selState.value - 1).coerceIn(0, values.lastIndex)
                        onPick(values[n]); acc -= stepPx
                    }
                    while (acc <= -stepPx) {  // drag up → higher value
                        val n = (selState.value + 1).coerceIn(0, values.lastIndex)
                        onPick(values[n]); acc += stepPx
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        // Center highlight band (track).
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .background(trackColor.copy(alpha = 0.6f), RoundedCornerShape(8.dp)),
        )
        // Fixed window of rows centered on the current value.
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            for (off in -MEASUREMENT_DRUM_RADIUS..MEASUREMENT_DRUM_RADIUS) {
                val idx = sel + off
                val a = abs(off)
                if (idx in values.indices) {
                    val isCenter = off == 0
                    val scale = if (perspective) (1f - 0.16f * a).coerceAtLeast(0.62f) else 1f
                    Box(
                        modifier = Modifier
                            .height(rowHeight)
                            .then(
                                if (perspective) Modifier.graphicsLayer { scaleX = scale; scaleY = scale }
                                else Modifier,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = formatMeasurement(values[idx], unit),
                            fontSize = if (isCenter) 22.sp else 16.sp,
                            fontWeight = if (isCenter) FontWeight.Bold else FontWeight.Normal,
                            color = if (isCenter) accentColor
                            else accentColor.copy(alpha = (0.6f - 0.16f * a).coerceAtLeast(0.25f)),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.clickable { onPick(values[idx]) },
                        )
                    }
                } else {
                    Spacer(Modifier.height(rowHeight))
                }
            }
        }
    }
}

// MARK: - Gauge (minimal radial arc + needle; reads units[] range ONLY)

@Composable
private fun MeasurementGauge(
    currentDisplay: Double,
    unit: MeasurementUnit,
    trackColor: Color,
    accentColor: Color,
    onPick: (Double) -> Unit,
) {
    val range = unit.max - unit.min
    val frac = if (range > 0) minOf(1.0, maxOf(0.0, (currentDisplay - unit.min) / range)) else 0.0
    val startDeg = 135.0
    val sweepDeg = 270.0

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(170.dp)
            .padding(horizontal = 24.dp)
            .pointerInput(unit) {
                detectDragGestures { change, _ ->
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    var deg = Math.toDegrees(
                        atan2((change.position.y - cy).toDouble(), (change.position.x - cx).toDouble()),
                    ) // -180..180
                    if (deg < startDeg) deg += 360.0 // map into 135..405
                    val f = minOf(1.0, maxOf(0.0, (deg - startDeg) / sweepDeg))
                    onPick(unit.min + f * range)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = 12f
            val cx = size.width / 2f
            val cy = size.height / 2f
            val radius = minOf(size.width, size.height) / 2f - 8f
            val arcSize = Size(radius * 2f, radius * 2f)
            val topLeft = Offset(cx - radius, cy - radius)
            // Track arc.
            drawArc(
                color = trackColor,
                startAngle = startDeg.toFloat(),
                sweepAngle = sweepDeg.toFloat(),
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            // Value arc.
            drawArc(
                color = accentColor,
                startAngle = startDeg.toFloat(),
                sweepAngle = (sweepDeg * frac).toFloat(),
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            // Needle.
            val ang = Math.toRadians(startDeg + sweepDeg * frac)
            val needleLen = radius - 16f
            drawLine(
                color = accentColor,
                start = Offset(cx, cy),
                end = Offset(
                    (cx + cos(ang) * needleLen).toFloat(),
                    (cy + sin(ang) * needleLen).toFloat(),
                ),
                strokeWidth = 3f,
                cap = StrokeCap.Round,
            )
            drawCircle(color = accentColor, radius = 6f, center = Offset(cx, cy))
        }
    }
}
