package ai.appdna.sdk.core

/**
 * Theme variants for light / dark mode.
 *
 * Generic wrapper that allows ANY theme-shaped data class to be specified
 * either as a flat object (legacy — treated as light) or as a
 * light/dark pair. The [light] variant is the complete baseline and is
 * required; [dark] is optional sparse overrides that merge on top of
 * light at render time — any field not set in dark falls back to the
 * light value (mirrors iOS asset catalogs' "Dark Appearance" pattern).
 *
 * Resolution happens at render time via Compose's
 * `androidx.compose.foundation.isSystemInDarkTheme()` so the SDK
 * auto-adapts when the user toggles system dark mode.
 *
 * Back-compat: legacy messages / surveys that stored a flat theme
 * object are decoded into [light] with [dark] = null. No migration
 * needed on existing customer data.
 *
 * Mirrors iOS `Core/ThemeSet.swift`.
 */
data class ThemeSet<T>(
    val light: T,
    val dark: T? = null,
) {
    /**
     * Pick the variant to render with. Does NOT merge — callers that
     * want sparse overrides (the default behavior for themes with
     * optional fields) should call [resolve] instead which delegates
     * to [SparseMergeable.mergedOnto].
     *
     * Mirrors iOS `ThemeSet.variant(for:)`.
     */
    fun variant(isDark: Boolean): T {
        return if (isDark && dark != null) dark else light
    }

    /**
     * Render-time resolver. In dark mode, when [T] implements
     * [SparseMergeable], any field set on [dark] overrides the matching
     * field on [light]; everything else falls back to the light value.
     * In light mode or when no [dark] overrides exist, returns [light]
     * unchanged.
     *
     * For non-[SparseMergeable] types this returns [variant] which
     * picks dark wholesale or light. Most callers should make their
     * theme types implement [SparseMergeable].
     *
     * Mirrors iOS `ThemeSet.resolved(for:)`.
     */
    @Suppress("UNCHECKED_CAST")
    fun resolve(isDark: Boolean): T {
        if (!isDark || dark == null) return light
        val d = dark
        if (d is SparseMergeable<*> && light is SparseMergeable<*>) {
            return (d as SparseMergeable<T>).mergedOnto(light)
        }
        return d
    }
}

/**
 * Render-time resolver for themes where every field is optional.
 * Pairs with the conventional pattern: every style property on the
 * inner type is nullable, [dark] specifies only what differs, and
 * [ThemeSet.resolve] returns a fully-populated theme by preferring the
 * dark value and falling back to light.
 *
 * Mirrors iOS `SparseMergeable` protocol.
 */
interface SparseMergeable<T> {
    /**
     * Merge overrides (this) onto a baseline. Implementations should
     * prefer overrides' non-null values over baseline's.
     */
    fun mergedOnto(baseline: T): T
}
