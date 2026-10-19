package ai.appdna.sdk.core

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieClipSpec
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition

/**
 * Server-driven Lottie animation block.
 *
 * Replaces the stub placeholder with the real
 * `lottie-compose` `LottieAnimation` Composable so onboarding /
 * messages / surveys / paywalls can render the same Lottie URLs the
 * console publishes today on iOS.
 *
 * iOS parity:
 * `packages/appdna-sdk-ios/Sources/AppDNASDK/Core/LottieBlockView.swift`
 * intends to use `LottieView(animation: .init(url:))` from `lottie-ios`
 * with `playbackMode(.playing(.toProgress(1, loopMode: .loop)))`. This
 * port matches that surface — `loop` flips between
 * [LottieConstants.IterateForever] and a single play, `speed` is
 * passed through, and clip-spec defaults to the full animation range.
 *
 * Recomposition safety: the underlying composition + animation state
 * are scoped to this Composable instance via `remember*` helpers so
 * Compose recycles them deterministically when the view leaves the
 * composition (no manual `DisposableEffect` is required for Lottie —
 * note re: Rive/ExoPlayer needing explicit
 * disposal).
 */
data class LottieBlock(
    val lottie_url: String? = null,
    val autoplay: Boolean = true,
    val loop: Boolean = true,
    val speed: Float = 1.0f,
    val width: Float? = null,
    val height: Float = 160f,
    val alignment: String = "center",
    val play_on_scroll: Boolean? = null,
    val play_on_tap: Boolean? = null,
)

@Composable
fun LottieBlockView(block: LottieBlock) {
    val horizontalAlignment = when (block.alignment) {
        "left" -> Alignment.Start
        "right" -> Alignment.End
        else -> Alignment.CenterHorizontally
    }

    Column(
        modifier = Modifier
            .let { mod ->
                if (block.width != null) mod.width(block.width.dp) else mod.fillMaxWidth()
            }
            .height(block.height.dp)
            .clip(RoundedCornerShape(12.dp)),
        horizontalAlignment = horizontalAlignment,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            val url = block.lottie_url
            if (url.isNullOrBlank()) {
                // Defensive: keep a tiny visible placeholder so authoring
                // tools that drop a Lottie block without a URL don't
                // render an empty void on device.
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("▶", fontSize = 32.sp, color = Color(0xFF9333EA))
                    Text("Lottie", fontSize = 10.sp, color = Color.Gray)
                }
            } else {
                // Both http(s) and `file:///android_asset/...` URLs are
                // accepted by `LottieCompositionSpec.Url` — Lottie's
                // network fetcher delegates to OkHttp under the hood,
                // and asset URLs are resolved via AssetManager.
                val composition by rememberLottieComposition(
                    LottieCompositionSpec.Url(url),
                )
                val iterations =
                    if (block.loop) LottieConstants.IterateForever else 1
                val progress by animateLottieCompositionAsState(
                    composition = composition,
                    iterations = iterations,
                    speed = block.speed,
                    isPlaying = block.autoplay,
                    // Default clip-spec — full animation length; if the
                    // server later supplies start/end markers, this is
                    // where they would be wired through.
                    clipSpec = LottieClipSpec.Progress(0f, 1f),
                )
                LottieAnimation(
                    composition = composition,
                    progress = { progress },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
