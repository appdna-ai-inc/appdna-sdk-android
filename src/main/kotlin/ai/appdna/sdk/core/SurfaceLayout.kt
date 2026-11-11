package ai.appdna.sdk.core

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/*
 * THE CONTENT COLUMN OF A PRESENTED SURFACE, AND WHEN IT SPLITS INTO TWO PANES.
 *
 * An onboarding step, a paywall, a full-screen message and a survey lay their content out in one
 * centred column, `min(width, 560)`. Their background keeps filling the window. Below 560dp (every
 * phone in portrait, the outer display of a foldable) the column IS the width, so nothing moves.
 *
 * On a wide window — regular width (≥ 600dp), at least 720dp and wider than tall: an unfolded
 * foldable held in landscape, a tablet, a landscape phone with a large display — a surface that has
 * something for each side splits into two equal panes of a container `min(width, 1040)`: the
 * onboarding step's hero or the paywall's pitch on the leading side, the rest on the trailing side.
 *
 * The rules are shared with iOS and the console through the `surface_column` and `surface_layout`
 * fixtures; a rule change is a fixture change first.
 *
 * WHY NOT `Configuration.screenWidthDp`: that is the configuration's size, which does not track
 * the space the surface was given (multi-window, a resized freeform window, the insets). The
 * layout measures its own constraints instead.
 *
 * FOLD AND UNFOLD keep their state because the panes are built as `Row { if (split) Lead(); Main() }`:
 * `Main` — the inputs, the scroll and the pinned call to action — is the same composition in both
 * modes, and only the hero moves. The SDK's activities also declare the size-related
 * `configChanges`, so a fold is a re-layout, not a recreation.
 */

/** The widest the content column grows, in dp. */
object SurfaceColumn {
    const val MAX_WIDTH: Float = 560f

    /** The column for [available] dp of width. 0 (nothing measured yet) stays 0. */
    fun width(available: Float): Float = if (available > 0f) minOf(available, MAX_WIDTH) else 0f

    /** The space either side of a centred column. */
    fun gutter(available: Float): Float = maxOf(0f, (available - width(available)) / 2f)
}

enum class SurfaceLayoutMode { COLUMN, SPLIT }

/** The result of the onboarding partition: how many leading blocks are the hero, and whether the step splits. */
data class OnboardingSplit(val hero: Int, val splits: Boolean)

/** The result of the paywall partition: indexes (drawing order) of the pitch sections, and whether it splits. */
data class PaywallSplit(val pitch: List<Int>, val splits: Boolean)

object SurfaceLayout {
    /** A split needs at least this much usable width (dp). */
    const val SPLIT_MIN_WIDTH: Float = 720f

    /** A split container grows to at most this (dp); each pane is half of it. */
    const val SPLIT_MAX_WIDTH: Float = 1040f

    /** The window width class boundary: at or above this the window is "regular" (not compact). */
    const val REGULAR_MIN_WIDTH: Float = 600f

    fun isRegular(windowWidthDp: Float): Boolean = windowWidthDp >= REGULAR_MIN_WIDTH

    /** `split` only when the window is regular width, at least 720 wide and wider than tall. */
    fun mode(width: Float, height: Float, regular: Boolean): SurfaceLayoutMode =
        if (regular && width >= SPLIT_MIN_WIDTH && width > height) SurfaceLayoutMode.SPLIT else SurfaceLayoutMode.COLUMN

    /** The width the content occupies: the column, or (when the surface actually splits) the split container. */
    fun containerWidth(width: Float, splits: Boolean): Float = when {
        width <= 0f -> 0f
        splits -> minOf(width, SPLIT_MAX_WIDTH)
        else -> SurfaceColumn.width(width)
    }

    /** Each pane of a split container. */
    fun paneWidth(width: Float): Float = containerWidth(width, splits = true) / 2f

    /** Onboarding block types that make up a step's hero. A map is not one: it fills the surface. */
    val ONBOARDING_HERO_TYPES: Set<String> = setOf("image", "video", "lottie", "rive", "media_gallery", "carousel")

    /**
     * How many blocks lead the step as its hero: the leading run of hero types in the first
     * non-empty zone of top, then centre (`bottom` is the pinned call to action). The step splits
     * only if, besides the hero, top and centre hold something — a splash that is only an image
     * stays a column. [blocks] are the visible blocks in authored order as (type, zone) pairs, the
     * zone being `zone ?: vertical_align`.
     */
    fun onboardingSplit(blocks: List<Pair<String, String?>>): OnboardingSplit {
        fun zone(z: String?) = (z ?: "top").lowercase()
        val top = blocks.filter { zone(it.second) != "center" && zone(it.second) != "bottom" }
        val center = blocks.filter { zone(it.second) == "center" }
        val source = if (top.isNotEmpty()) top else center
        var hero = 0
        while (hero < source.size && source[hero].first in ONBOARDING_HERO_TYPES) hero++
        val rest = top.size + center.size - hero
        return OnboardingSplit(hero, hero > 0 && rest > 0)
    }

    private val PAYWALL_PINNED = setOf("cta", "sticky_footer")

    /**
     * Paywall: it splits only if it has a `plans` section anywhere and a non-empty pitch. The pitch
     * is every scrolling section before the first scrolling `plans` section, in drawing order; when
     * every `plans` section is pinned, it is every scrolling section. `cta`, `sticky_footer` and any
     * `zone: bottom` section are pinned and never in the pitch. [sections] are (type, zone) pairs.
     */
    fun paywallSplit(sections: List<Pair<String, String?>>): PaywallSplit {
        fun pinned(s: Pair<String, String?>) = s.first in PAYWALL_PINNED || s.second == "bottom"
        if (sections.none { it.first == "plans" }) return PaywallSplit(emptyList(), false)
        val firstScrollingPlans = sections.indexOfFirst { it.first == "plans" && !pinned(it) }
        val end = if (firstScrollingPlans < 0) sections.size else firstScrollingPlans
        val pitch = sections.indices.filter { it < end && !pinned(sections[it]) }
        return PaywallSplit(pitch, pitch.isNotEmpty())
    }

    /** Only a full-screen message with hero media and copy splits; a modal, banner or tooltip never does. */
    fun messageSplits(messageType: String, hasHero: Boolean, hasCopy: Boolean): Boolean =
        messageType == "fullscreen" && hasHero && hasCopy
}

/**
 * What a surface measured about the space it lays out in. `width`/`height` are the usable size in
 * dp inside the safe area; [column] is the width the content resolves its width-relative rules
 * against (the column, or the pane it is in). [hingeCenterDp] is the x of a vertical separating
 * fold measured from the surface's left edge, or null.
 */
@Immutable
data class SurfaceGeometry(
    val width: Float,
    val height: Float,
    val regular: Boolean,
    val column: Float,
    val hingeCenterDp: Float? = null,
) {
    val mode: SurfaceLayoutMode get() = SurfaceLayout.mode(width, height, regular)
    val wide: Boolean get() = mode == SurfaceLayoutMode.SPLIT

    companion object {
        val Unmeasured = SurfaceGeometry(0f, 0f, false, 0f)
    }
}

/** The surface's measured geometry. [SurfaceGeometry.Unmeasured] outside a surface. */
val LocalSurfaceGeometry = staticCompositionLocalOf { SurfaceGeometry.Unmeasured }

/**
 * The width (dp) width-relative rules resolve against: the column or pane the content is in, else
 * the configuration's width (code outside a measured surface — a preview, a test, a host-embedded
 * view).
 */
@Composable
fun surfaceColumnWidthDp(): Float {
    val g = LocalSurfaceGeometry.current
    return if (g.column > 0f) g.column else SurfaceColumn.width(LocalConfiguration.current.screenWidthDp.toFloat())
}

/**
 * Measures the space [content] has and publishes it as [LocalSurfaceGeometry] (the column is
 * `min(width, 560)`). Fills its parent; lays nothing out itself. Put it INSIDE the safe-area
 * padding so the measured size is the usable size.
 */
@Composable
fun SurfaceMeasure(modifier: Modifier = Modifier, content: @Composable BoxScope.(SurfaceGeometry) -> Unit) {
    val hinge = rememberVerticalHingeXInWindow()
    var leftInWindowPx by remember { mutableStateOf(0f) }
    val density = LocalDensity.current
    val windowWidthDp = LocalConfiguration.current.screenWidthDp.toFloat()
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { leftInWindowPx = it.positionInWindow().x },
    ) {
        val w = maxWidth.value.takeIf { constraints.hasBoundedWidth } ?: windowWidthDp
        val h = maxHeight.value.takeIf { constraints.hasBoundedHeight } ?: LocalConfiguration.current.screenHeightDp.toFloat()
        // Regular width is a WINDOW property: the window's width, of which the usable width is a part.
        val regular = SurfaceLayout.isRegular(maxOf(windowWidthDp, w))
        val hingeDp = hinge?.let { with(density) { (it - leftInWindowPx).toDp().value } }
        val geometry = SurfaceGeometry(
            width = w,
            height = h,
            regular = regular,
            column = SurfaceColumn.width(w),
            hingeCenterDp = hingeDp?.takeIf { it > 0f && it < w },
        )
        CompositionLocalProvider(LocalSurfaceGeometry provides geometry) {
            content(geometry)
        }
    }
}

/**
 * The surface's content, centred: a column `min(width, 560)` when the surface does not split, else
 * a split container `min(width, 1040)` of two equal panes. It splits when [splits] (the surface's
 * partition has something for each pane) AND the space it has is in split mode — measured here, so
 * a layout nested in a narrower box (a legacy image-split variant) never splits.
 *
 * [lead] is composed only while split; [main] is the SAME composition in both modes (the Row's
 * last child), so its state — typed text, a picked option, the scroll offset — survives a fold or
 * unfold. Each pane publishes its own width as the column width-relative rules resolve against.
 *
 * Where the device reports a vertical separating fold (a foldable in book pose) inside the
 * container, the boundary between the panes is placed on it, so the gap the panes' insets leave
 * falls on the hinge rather than on content.
 */
@Composable
fun SurfacePanes(
    splits: Boolean,
    modifier: Modifier = Modifier,
    lead: @Composable (split: Boolean) -> Unit = {},
    main: @Composable (split: Boolean) -> Unit,
) {
    val outer = LocalSurfaceGeometry.current
    val configuration = LocalConfiguration.current
    BoxWithConstraints(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val boxW = if (constraints.hasBoundedWidth) maxWidth.value else Float.POSITIVE_INFINITY
        val available = when {
            outer.width > 0f -> minOf(outer.width, boxW)
            boxW.isFinite() -> boxW
            else -> configuration.screenWidthDp.toFloat()
        }
        val height = if (outer.height > 0f) outer.height else
            (if (constraints.hasBoundedHeight) maxHeight.value else configuration.screenHeightDp.toFloat())
        val regular = if (outer.width > 0f) outer.regular else SurfaceLayout.isRegular(configuration.screenWidthDp.toFloat())
        val split = splits && SurfaceLayout.mode(available, height, regular) == SurfaceLayoutMode.SPLIT
        val container = SurfaceLayout.containerWidth(available, split)
        // The hinge, from this box's left edge (the box is the surface when nothing narrows it).
        val containerLeft = (available - container) / 2f
        val hinge = if (available == outer.width) outer.hingeCenterDp else null
        val boundary = splitBoundary(container, containerLeft, hinge)
        val geometry = SurfaceGeometry(available, height, regular, SurfaceColumn.width(available), hinge)
        Row(
            modifier = Modifier
                .fillMaxHeight()
                .then(if (container > 0f) Modifier.width(container.dp) else Modifier.fillMaxWidth()),
        ) {
            if (split) {
                Pane(width = boundary, geometry = geometry) { lead(true) }
            }
            Pane(width = if (split) container - boundary else container, geometry = geometry) { main(split) }
        }
    }
}

/**
 * A centred column `min(width, 560)` around [content] when [limit] is true; the full width when it
 * is false. Always the same two boxes, so switching [limit] never rebuilds [content].
 */
@Composable
fun SurfaceColumnLimit(limit: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val outer = LocalSurfaceGeometry.current
    BoxWithConstraints(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val w = if (constraints.hasBoundedWidth) maxWidth.value else outer.width
        val column = if (limit) SurfaceColumn.width(w) else w
        Box(modifier = Modifier.fillMaxHeight().then(if (column > 0f) Modifier.width(column.dp) else Modifier.fillMaxWidth())) {
            CompositionLocalProvider(
                LocalSurfaceGeometry provides outer.copy(column = if (column > 0f) column else outer.column),
            ) { content() }
        }
    }
}

/** Fills the width, then centres a child at most the surface column wide (`min(width, 560)`). */
fun Modifier.surfaceColumnCentred(column: Float): Modifier =
    this.fillMaxWidth()
        .wrapContentWidth(Alignment.CenterHorizontally)
        .then(if (column > 0f) Modifier.widthIn(max = column.dp) else Modifier)
        .fillMaxWidth()

/**
 * The x where a split's panes meet, from the container's left edge: half way, or on a vertical
 * hinge that lies well inside the container (each pane keeps at least a third of it).
 */
fun splitBoundary(container: Float, containerLeft: Float, hingeCenter: Float?): Float {
    val half = container / 2f
    val h = hingeCenter ?: return half
    val local = h - containerLeft
    return if (local >= container / 3f && local <= container * 2f / 3f) local else half
}

@Composable
private fun RowScope.Pane(width: Float, geometry: SurfaceGeometry, content: @Composable () -> Unit) {
    val pane = geometry.copy(column = if (width > 0f) width else geometry.column)
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .then(if (width > 0f) Modifier.width(width.dp) else Modifier.weight(1f)),
    ) {
        CompositionLocalProvider(LocalSurfaceGeometry provides pane) { content() }
    }
}

/**
 * The x (window pixels) of the centre of a vertical fold that separates the display — a foldable
 * half opened in book pose, or a dual-screen device — through Jetpack WindowManager. Null when
 * there is none, when the context is not an activity, or on a device without WindowManager
 * extensions.
 */
@Composable
internal fun rememberVerticalHingeXInWindow(): Float? {
    val activity = LocalContext.current.findActivity() ?: return null
    var hinge by remember(activity) { mutableStateOf<Float?>(null) }
    LaunchedEffect(activity) {
        try {
            androidx.window.layout.WindowInfoTracker.getOrCreate(activity)
                .windowLayoutInfo(activity)
                .collect { info -> hinge = verticalHingeCenterX(info) }
        } catch (_: Throwable) {
            // No extensions on this device (or a test host): no hinge, the split is centred.
            hinge = null
        }
    }
    return hinge
}

/** The centre x of the first vertical, separating folding feature in [info], or null. */
internal fun verticalHingeCenterX(info: androidx.window.layout.WindowLayoutInfo): Float? =
    info.displayFeatures
        .filterIsInstance<androidx.window.layout.FoldingFeature>()
        .firstOrNull {
            it.orientation == androidx.window.layout.FoldingFeature.Orientation.VERTICAL &&
                (it.isSeparating || it.state == androidx.window.layout.FoldingFeature.State.HALF_OPENED)
        }
        ?.bounds?.let { (it.left + it.right) / 2f }

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

