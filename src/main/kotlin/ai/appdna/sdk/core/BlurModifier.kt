package ai.appdna.sdk.core

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.RenderEffect as ComposeRenderEffect
import androidx.compose.ui.unit.dp

data class BlurConfig(
    val radius: Float,
    val tint: String? = null,
    val saturation: Float? = null,
)

fun Modifier.applyBlurBackdrop(config: BlurConfig?, cornerRadius: Float = 0f): Modifier {
    if (config == null || config.radius <= 0) return this

    return this
        .graphicsLayer {
            // API 31+ supports RenderEffect blur
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val blurPx = config.radius * density
                renderEffect = createBlurRenderEffect(blurPx)
            }
        }
        .background(
            // Tint overlay for glassmorphism
            config.tint?.let { StyleEngine.parseColor(it) }
                ?: Color.White.copy(alpha = 0.15f),
            shape = RoundedCornerShape(cornerRadius.dp)
        )
        .clip(RoundedCornerShape(cornerRadius.dp))
}

/**
 * Extracted helper that ASSUMES API 31+ so Android Lint
 * flags any accidental call from a code path that isn't behind a
 * `Build.VERSION.SDK_INT >= S` guard. The platform `RenderEffect` class
 * itself was introduced in S; calling [android.graphics.RenderEffect.createBlurEffect]
 * on older OS levels would `NoClassDefFoundError` at class verification.
 */
@RequiresApi(Build.VERSION_CODES.S)
private fun createBlurRenderEffect(blurPx: Float): ComposeRenderEffect =
    android.graphics.RenderEffect.createBlurEffect(
        blurPx, blurPx,
        android.graphics.Shader.TileMode.CLAMP,
    ).asComposeRenderEffect()
