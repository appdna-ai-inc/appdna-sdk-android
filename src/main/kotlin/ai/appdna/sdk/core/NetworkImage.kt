package ai.appdna.sdk.core

import ai.appdna.sdk.Log
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.request.ImageRequest
import com.caverock.androidsvg.SVG
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL

/**
 * Network image composable for the SDK.
 *
 * Backed by Coil ([io.coil-kt:coil-compose]). The previous
 * implementation re-ran `BitmapFactory.decodeStream` on every recomposition
 * with no caching; Coil now handles HTTP, decoding, and memory + disk caching
 * via the [AppDNAImageLoader] singleton (25% memory budget, 50 MB disk cache
 * at `<cacheDir>/appdna_image_cache`).
 *
 * Adds GIF + SVG support:
 *   - GIF: registered as a Coil decoder in [AppDNAImageLoader] so the
 *     entire animation plays (not just the first frame).
 *   - SVG: detected by file extension on this side and rendered via
 *     `com.caverock.androidsvg` to a [Bitmap] that we hand back to
 *     Compose as an `ImageBitmap`. We deliberately don't ship a Coil
 *     SVG decoder factory because (a) only a tiny subset of console
 *     URLs are SVG today, (b) it keeps the Coil dep surface minimal,
 *     and (c) `androidsvg` gives us deterministic sizing aligned to
 *     the Composable's layout bounds.
 *
 * Public signature is preserved so all existing callers (paywall renderer,
 * onboarding renderer, message renderer, video thumbnail, media block) work
 * without changes — caller-supplied [modifier] and [contentScale] still
 * pass through untouched.
 */
@Composable
fun NetworkImage(
    url: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    placeholderColor: Color = Color.Gray.copy(alpha = 0.2f),
    contentDescription: String? = null,
    // image_position: how the (cropped) image aligns within its frame.
    alignment: Alignment = Alignment.Center,
) {
    if (url.isNullOrBlank()) {
        Box(modifier = modifier.background(placeholderColor))
        return
    }

    if (isSvgUrl(url)) {
        SvgNetworkImage(
            url = url,
            modifier = modifier,
            contentScale = contentScale,
            placeholderColor = placeholderColor,
            contentDescription = contentDescription,
            alignment = alignment,
        )
        return
    }

    val context = LocalContext.current
    val request = ImageRequest.Builder(context)
        .data(url)
        .crossfade(true)
        .build()

    SubcomposeAsyncImage(
        model = request,
        contentDescription = contentDescription,
        imageLoader = AppDNAImageLoader.singleton(context),
        modifier = modifier,
        contentScale = contentScale,
        alignment = alignment,
    ) {
        // Mirror the prior loading / error visuals exactly so visual
        // snapshots and host-app expectations don't shift.
        when (painter.state) {
            is AsyncImagePainter.State.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(placeholderColor),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.fillMaxSize(0.3f),
                        color = Color.White.copy(alpha = 0.5f),
                        strokeWidth = 2.dp,
                    )
                }
            }
            is AsyncImagePainter.State.Error -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(placeholderColor),
                )
            }
            else -> SubcomposeAsyncImageContent()
        }
    }
}

private fun isSvgUrl(url: String): Boolean {
    // Strip query string + fragment before checking the extension —
    // signed CDN URLs commonly look like `…/asset.svg?token=…`.
    val path = url.substringBefore('?').substringBefore('#').lowercase()
    return path.endsWith(".svg") || path.endsWith(".svgz")
}

@Composable
private fun SvgNetworkImage(
    url: String,
    modifier: Modifier,
    contentScale: ContentScale,
    placeholderColor: Color,
    contentDescription: String?,
    alignment: Alignment = Alignment.Center,
) {
    var bitmap by remember(url) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(url) { mutableStateOf(false) }

    LaunchedEffect(url) {
        bitmap = null
        failed = false
        val rendered = try {
            withContext(Dispatchers.IO) {
                URL(url).openStream().use { stream ->
                    val svg = SVG.getFromInputStream(stream)
                    val widthPx = svg.documentWidth.takeIf { it > 0 } ?: 512f
                    val heightPx = svg.documentHeight.takeIf { it > 0 } ?: 512f
                    val bmp = Bitmap.createBitmap(
                        widthPx.toInt().coerceAtLeast(1),
                        heightPx.toInt().coerceAtLeast(1),
                        Bitmap.Config.ARGB_8888,
                    )
                    svg.renderToCanvas(Canvas(bmp))
                    bmp
                }
            }
        } catch (t: Throwable) {
            Log.warning("NetworkImage(SVG): decode failed for $url: ${t.message}")
            null
        }
        if (rendered != null) {
            bitmap = rendered
        } else {
            failed = true
        }
    }

    val current = bitmap
    Box(
        modifier = modifier.background(placeholderColor),
        contentAlignment = Alignment.Center,
    ) {
        when {
            current != null -> {
                Image(
                    bitmap = current.asImageBitmap(),
                    contentDescription = contentDescription,
                    contentScale = contentScale,
                    alignment = alignment,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            failed -> {
                // Render a broken-image glyph on failure
                // (matches iOS `Image(systemName: "photo")` placeholder
                // at ~50sp grey). Was an empty placeholder background
                // which gave no visual hint that loading errored.
                Text(
                    text = "🖼",
                    fontSize = 48.sp,
                    color = Color.Gray.copy(alpha = 0.6f),
                )
            }
            else -> {
                CircularProgressIndicator(
                    modifier = Modifier.fillMaxSize(0.3f),
                    color = Color.White.copy(alpha = 0.5f),
                    strokeWidth = 2.dp,
                )
            }
        }
    }
}
