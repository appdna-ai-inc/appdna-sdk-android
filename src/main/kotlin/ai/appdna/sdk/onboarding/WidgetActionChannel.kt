package ai.appdna.sdk.onboarding

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * How an `add_widget` button at ANY depth reaches its onboarding step.
 *
 * The action string alone cannot carry the button's how-to blocks or its id (`onAction` is a
 * `(String) -> Unit`), so the button hands the step the DECODED BLOCK itself. Same shape as
 * [LocalStepInteraction] for `refresh_step`: provided at the step's root, so a Row, stack, carousel
 * page or Multi-buttons child reaches it; null in Screens / SDUI and inside every sheet, where an
 * `add_widget` button falls back to the ordinary unrecognised-action path (advance, keep answers).
 */
internal fun interface WidgetActionChannel {
    fun tap(block: ContentBlock)
}

internal val LocalWidgetAction = compositionLocalOf<WidgetActionChannel?> { null }

/**
 * True inside an "Add widget" how-to sheet: a button there forwards EVERY action to the sheet's
 * handler, so the button renderer's own handling (opening a `link` in place) never runs. The sheet
 * acts on Done (`next` / `continue`) and Close (`dismiss`) and ignores anything else, which is what
 * iOS does too.
 */
internal val LocalForwardEveryButtonAction = staticCompositionLocalOf { false }

/** The JSON keys of an "Add widget" button's how-to sheets. */
internal const val HOWTO_BLOCKS_IOS = "howto_blocks_ios"
internal const val HOWTO_BLOCKS_ANDROID = "howto_blocks_android"

/*
 * ContentBlock is at the JVM's constructor-argument ceiling (scripts/check-contentblock-arg-budget.ts),
 * so the two how-to lists ride in `field_config` under their own key names, decoded as blocks. These
 * accessors are the only readers; [withHowtoBlocks] the only writer.
 */
@Suppress("UNCHECKED_CAST")
internal val ContentBlock.howto_blocks_ios: List<ContentBlock>?
    get() = field_config?.get(HOWTO_BLOCKS_IOS) as? List<ContentBlock>

@Suppress("UNCHECKED_CAST")
internal val ContentBlock.howto_blocks_android: List<ContentBlock>?
    get() = field_config?.get(HOWTO_BLOCKS_ANDROID) as? List<ContentBlock>

/** [this] with its how-to lists replaced (a null list is removed). */
internal fun ContentBlock.withHowtoBlocks(ios: List<ContentBlock>?, android: List<ContentBlock>?): ContentBlock {
    val fc = LinkedHashMap(field_config.orEmpty())
    fc.remove(HOWTO_BLOCKS_IOS); fc.remove(HOWTO_BLOCKS_ANDROID)
    ios?.let { fc[HOWTO_BLOCKS_IOS] = kotlinx.collections.immutable.persistentListOf<ContentBlock>().addAll(it) }
    android?.let { fc[HOWTO_BLOCKS_ANDROID] = kotlinx.collections.immutable.persistentListOf<ContentBlock>().addAll(it) }
    return copy(field_config = fc.takeIf { it.isNotEmpty() })
}
