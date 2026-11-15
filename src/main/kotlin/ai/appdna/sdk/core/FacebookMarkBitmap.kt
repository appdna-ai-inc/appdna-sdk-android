package ai.appdna.sdk.core

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import ai.appdna.sdk.R

/**
 * Meta's two Facebook marks, decoded once each.
 *
 * The drawables are Meta's logo pack PNGs, byte-identical and 2084 × 2084. `painterResource` would
 * decode one at full size (~17 MB) for an image drawn at 20dp, so it is decoded at 1/16 (130px,
 * enough for 20dp at xxxhdpi). That is a scale, not an edit: the shipped file is untouched.
 */
object FacebookMarkBitmap {
    private val cache = java.util.concurrent.ConcurrentHashMap<FacebookMark.Variant, ImageBitmap>()

    fun resId(variant: FacebookMark.Variant): Int = when (variant) {
        FacebookMark.Variant.PRIMARY -> R.drawable.appdna_facebook_mark_primary
        FacebookMark.Variant.SECONDARY -> R.drawable.appdna_facebook_mark_secondary
    }

    fun load(context: Context, variant: FacebookMark.Variant): ImageBitmap? {
        cache[variant]?.let { return it }
        val bmp = try {
            BitmapFactory.decodeResource(
                context.resources,
                resId(variant),
                BitmapFactory.Options().apply { inSampleSize = 16; inScaled = false },
            )
        } catch (_: Throwable) {
            null
        } ?: return null
        return bmp.asImageBitmap().also { cache[variant] = it }
    }
}
