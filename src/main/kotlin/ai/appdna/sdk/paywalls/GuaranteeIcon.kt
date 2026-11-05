package ai.appdna.sdk.paywalls

import ai.appdna.sdk.core.MaterialIconMapping
import ai.appdna.sdk.core.SFSymbolMapping

/**
 * Which symbol a paywall `guarantee` section draws above its badge.
 *
 * The slot takes an SF Symbol name: a name with a `.` or `_` is drawn as authored, anything else
 * (nothing, an emoji, a plain word) draws the default shield. This platform draws it through the
 * SF → Material table, which did not know the default shield, so the badge drew a "?". Swift twin:
 * `GuaranteeIcon`. Shared fixture: `dto_parsing/guarantee_icon_resolution`.
 */
internal object GuaranteeIcon {
    /** `shield.checkmark.fill`, the old default, is not an SF Symbol; it is still read as the one it meant. */
    const val DEFAULT_SYMBOL = "checkmark.shield.fill"

    fun symbol(icon: String?): String {
        val name = icon?.trim().orEmpty()
        if (!(name.contains("_") || name.contains("."))) return DEFAULT_SYMBOL
        return if (name == "shield.checkmark.fill") DEFAULT_SYMBOL else name
    }

    /** Whether this platform draws [symbol] as an icon (rather than the "?" fallback). */
    fun isDrawn(symbol: String): Boolean =
        MaterialIconMapping.resolve(SFSymbolMapping.toMaterial[symbol] ?: symbol) != null
}
