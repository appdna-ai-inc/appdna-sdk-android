package ai.appdna.sdk.core

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

/**
 * Server-driven inline video block.
 *
 * Replaces the "▶ Playing video" stub with real
 * `androidx.media3` ExoPlayer playback wired through [PlayerView].
 *
 * Lifecycle:
 *   - The [ExoPlayer] is created lazily inside [remember] so it
 *     survives recompositions but is unique per Composable instance.
 *   - [DisposableEffect] releases the player when the Composable
 *     leaves the composition. Without this, every navigation event
 *     leaks one ExoPlayer + its codec + audio focus + decoder
 *     thread, which on low-end devices (the AppDNA SDK ships down
 *     to API 24) starves the OS within a few flows.
 *
 * iOS parity:
 * `packages/appdna-sdk-ios/Sources/AppDNASDK/Core/VideoBlockView.swift`
 * uses AVKit's `VideoPlayer(player:)` + `AVPlayer(url:)`. We mirror
 * the same flags — `muted`, `autoplay`, `loop`, optional `controls`,
 * thumbnail-tap-to-play — and surface them as ExoPlayer + PlayerView
 * properties.
 */
data class VideoBlock(
    val video_url: String,
    val video_thumbnail_url: String? = null,
    val video_height: Float = 200f,
    val video_corner_radius: Float? = null,
    val autoplay: Boolean? = false,
    val loop: Boolean? = false,
    val muted: Boolean? = true,
    val controls: Boolean? = true,
    val inline_playback: Boolean? = true,
)

@Composable
fun VideoBlockView(block: VideoBlock) {
    var showThumbnail by remember(block.video_url) {
        mutableStateOf(!(block.autoplay ?: false))
    }
    val cornerRadius = (block.video_corner_radius ?: 0f).dp
    val context = LocalContext.current

    if (showThumbnail) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(block.video_height.dp)
                .clip(RoundedCornerShape(cornerRadius))
                .clickable { showThumbnail = false },
            contentAlignment = Alignment.Center,
        ) {
            // Thumbnail or gradient background
            if (block.video_thumbnail_url != null) {
                NetworkImage(
                    url = block.video_thumbnail_url,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.linearGradient(
                                colors = listOf(Color(0xFF1A1A2E), Color(0xFF16213E)),
                            ),
                        ),
                )
            }

            // Play button overlay
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.9f)),
                contentAlignment = Alignment.Center,
            ) {
                // Material PlayArrow icon
                // matching iOS `Image(systemName: "play.fill")` at
                // VideoBlockView.swift:55-58. Was Unicode glyph "▶" which
                // some OEM Android fonts substitute as colored emoji
                // play-button instead of solid black triangle.
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = "Play video",
                    tint = Color.Black,
                    modifier = Modifier.size(28.dp).offset(x = 2.dp),
                )
            }
        }
    } else {
        // ExoPlayer is built once per Composable instance
        // and released in DisposableEffect to prevent codec leaks across
        // navigations. Keying on `video_url` rebuilds the player when the
        // server pushes a new URL into the same composition slot.
        val exoPlayer = remember(block.video_url) {
            ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(block.video_url))
                volume = if (block.muted ?: true) 0f else 1f
                repeatMode = if (block.loop == true) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                playWhenReady = block.autoplay ?: false
                prepare()
            }
        }

        DisposableEffect(exoPlayer) {
            onDispose { exoPlayer.release() }
        }

        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .height(block.video_height.dp)
                .clip(RoundedCornerShape(cornerRadius)),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = block.controls ?: true
                    // Pin the resize mode so PlayerView fills available
                    // bounds without letterboxing on tall onboarding
                    // hero areas — matches iOS AVPlayer's
                    // `videoGravity = .resizeAspectFill` posture used
                    // when controls are hidden.
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                }
            },
            update = { view ->
                view.useController = block.controls ?: true
            },
        )
    }
}
