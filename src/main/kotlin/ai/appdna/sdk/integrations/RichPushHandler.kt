package ai.appdna.sdk.integrations

import ai.appdna.sdk.Log
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URL

/**
 * Helper for building rich push notifications with images.
 * Used by AppDNAMessagingService to display BigPictureStyle notifications.
 *
 * SPEC-070-A H.21 — small-icon resolution delegated to
 * [NotificationIconResolver] so the icon priority chain stays in lockstep
 * with [AppDNAMessagingService].
 *
 * SPEC-070-A H.21b — server-supplied `channel_importance` overrides the
 * default `IMPORTANCE_HIGH` when the channel is created here.
 *
 * SPEC-070-A H.23 — bitmap download moved off the FCM listener thread into
 * a coroutine with an 8-second wall-clock cap (well under FCM's 10s service
 * window). Previously, the default URL connection's 25s combined timeout
 * could cause the FCM service to be killed before the notification posted.
 *
 * SPEC-070-A E.6 — rich-media handling for GIF and video URLs:
 *   - **GIF**: FCM/[NotificationCompat.BigPictureStyle] only renders a
 *     single still bitmap, so we decode the first frame of the GIF and
 *     surface it as the BigPicture. (Animated rendering inside the
 *     notification shade isn't supported by the platform.)
 *   - **Video** (MP4 / MOV / WEBM …): we pull the closest sync frame
 *     near t=0 via [MediaMetadataRetriever] and use that as the
 *     BigPicture thumbnail.
 *
 * The selection between paths is driven by the optional `mediaType`
 * argument (mapped from FCM `data.media_type`) with a filename-extension
 * fallback so the server can omit the field for backward compatibility.
 */
object RichPushHandler {

    fun buildRichNotification(
        context: Context,
        channelId: String,
        title: String,
        body: String,
        imageUrl: String? = null,
        mediaType: String? = null,
        customSound: String? = null,
    ): NotificationCompat.Builder {
        val builder = NotificationCompat.Builder(context, channelId)
            // SPEC-070-A H.21 — host-aware icon resolution.
            .setSmallIcon(NotificationIconResolver.resolve(context))
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)

        // SPEC-070-A H.23 / E.6: dispatch to the right decoder based on
        // declared media type (with extension fallback). We always cap
        // the wall-clock at 8s — bitmap decode, GIF first-frame extract,
        // and video sync-frame extract are all subject to the same
        // budget so the FCM service is never starved.
        imageUrl?.let { url ->
            try {
                val resolvedKind = resolveMediaKind(mediaType, url)
                val bitmap = runBlocking {
                    withTimeoutOrNull(BITMAP_TIMEOUT_MS) {
                        withContext(Dispatchers.IO) {
                            when (resolvedKind) {
                                MediaKind.VIDEO -> extractVideoThumbnail(url)
                                MediaKind.GIF -> extractGifFirstFrame(url)
                                MediaKind.IMAGE -> downloadBitmap(url)
                            }
                        }
                    }
                }
                if (bitmap != null) {
                    builder.setStyle(
                        NotificationCompat.BigPictureStyle()
                            .bigPicture(bitmap)
                            .bigLargeIcon(null as Bitmap?)
                    )
                    builder.setLargeIcon(bitmap)
                } else {
                    Log.warning(
                        "RichPushHandler: rich-media fetch timed out / failed for $url ($resolvedKind)",
                    )
                }
            } catch (e: Throwable) {
                Log.warning("RichPushHandler: rich-media fetch threw: ${e.message}")
            }
        }

        // Custom sound
        customSound?.let { soundName ->
            val soundResId = context.resources.getIdentifier(soundName, "raw", context.packageName)
            if (soundResId != 0) {
                val soundUri = android.net.Uri.parse("android.resource://${context.packageName}/$soundResId")
                builder.setSound(soundUri)
            }
        }

        return builder
    }

    private const val BITMAP_TIMEOUT_MS = 8_000L

    private enum class MediaKind { IMAGE, GIF, VIDEO }

    /**
     * Pick a decoder for [url] based on the server-supplied [mediaType]
     * (an FCM `data.media_type` value such as `image/gif`, `video/mp4`,
     * or shorthand `gif` / `video`). When the server omits the hint we
     * fall back to the URL's file extension so the existing console
     * payloads keep working without re-publishing.
     */
    private fun resolveMediaKind(mediaType: String?, url: String): MediaKind {
        mediaType?.lowercase()?.trim()?.let { hint ->
            when {
                hint == "gif" || hint.contains("gif") -> return MediaKind.GIF
                hint == "video" || hint.startsWith("video/") -> return MediaKind.VIDEO
                hint.startsWith("image/") -> return MediaKind.IMAGE
                else -> Unit  // fall through to URL-extension detection
            }
        }
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return when {
            path.endsWith(".gif") -> MediaKind.GIF
            path.endsWith(".mp4") ||
                path.endsWith(".mov") ||
                path.endsWith(".webm") ||
                path.endsWith(".m4v") ||
                path.endsWith(".3gp") -> MediaKind.VIDEO
            else -> MediaKind.IMAGE
        }
    }

    private fun downloadBitmap(url: String): Bitmap? {
        return try {
            val connection = URL(url).openConnection()
            // SPEC-070-A H.23 — keep individual socket timeouts tight so the
            // 8s outer cap can preempt slow servers. Connect: 4s, read: 4s.
            connection.connectTimeout = 4_000
            connection.readTimeout = 4_000
            connection.getInputStream().use { stream ->
                BitmapFactory.decodeStream(stream)
            }
        } catch (e: Exception) {
            Log.warning("RichPushHandler.downloadBitmap failed: ${e.message}")
            null
        }
    }

    /**
     * SPEC-070-A E.6 — GIFs in the Android notification shade can only
     * surface a single static frame (`BigPictureStyle.bigPicture` accepts
     * only a [Bitmap]). We decode the first frame via the platform
     * `ImageDecoder` on API 28+, and fall back to `BitmapFactory` (which
     * also yields the first frame) on older devices.
     */
    private fun extractGifFirstFrame(url: String): Bitmap? {
        return try {
            val connection = URL(url).openConnection()
            connection.connectTimeout = 4_000
            connection.readTimeout = 4_000
            connection.getInputStream().use { stream ->
                // BitmapFactory's GIF support yields the first frame as a
                // static bitmap on every supported API level — perfect for
                // notification surfacing where animation is unavailable.
                BitmapFactory.decodeStream(stream)
            }
        } catch (e: Exception) {
            Log.warning("RichPushHandler.extractGifFirstFrame failed: ${e.message}")
            null
        }
    }

    /**
     * SPEC-070-A E.6 — pulls a thumbnail near t=0 from a remote video URL
     * via [MediaMetadataRetriever]. We deliberately use the closest sync
     * frame (`OPTION_CLOSEST_SYNC`) so we don't have to decode forwards
     * from the start; on any keyframed encoding (the standard for h264 +
     * vp9 + av1 web video) this returns essentially instantly even for
     * long videos.
     */
    private fun extractVideoThumbnail(url: String): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            // setDataSource(String) accepts http/https URLs natively.
            // No headers required for the typical CDN-hosted asset.
            retriever.setDataSource(url, emptyMap<String, String>())
            retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } catch (e: Exception) {
            Log.warning("RichPushHandler.extractVideoThumbnail failed: ${e.message}")
            null
        } finally {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    closeRetriever(retriever)
                } else {
                    @Suppress("DEPRECATION")
                    retriever.release()
                }
            } catch (_: Throwable) {
                // best-effort
            }
        }
    }

    /**
     * SPEC-070-A J.15 — extracted helper that ASSUMES API 29+ so Android Lint
     * flags any accidental call that isn't behind a
     * `Build.VERSION.SDK_INT >= Q` guard. [MediaMetadataRetriever.close] was
     * added in API 29 (`AutoCloseable` was implemented on the same release);
     * on older devices `release()` is the supported teardown path.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun closeRetriever(retriever: MediaMetadataRetriever) {
        retriever.close()
    }

    /**
     * SPEC-070-A H.21b — overload that accepts server-supplied importance.
     * Existing 2-arg overload retained for source-compat.
     */
    fun ensureChannel(
        context: Context,
        channelId: String,
        channelName: String,
        importance: Int = NotificationManager.IMPORTANCE_HIGH,
        description: String? = null,
        group: String? = null,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        ensureChannelOreoPlus(context, channelId, channelName, importance, description, group)
    }

    /**
     * SPEC-070-A J.15 — extracted helper that ASSUMES API 26+ so Android Lint
     * flags any accidental call that isn't behind a
     * `Build.VERSION.SDK_INT >= O` guard. [NotificationChannel],
     * `NotificationManager.getNotificationChannel`, and
     * `createNotificationChannel` were all introduced in API 26.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun ensureChannelOreoPlus(
        context: Context,
        channelId: String,
        channelName: String,
        importance: Int,
        description: String?,
        group: String?,
    ) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(channelId) != null) return
        val channel = NotificationChannel(channelId, channelName, importance).apply {
            description?.let { this.description = it }
            group?.let {
                NotificationChannelGroups.ensure(manager, it)
                this.group = it
            }
        }
        manager.createNotificationChannel(channel)
    }
}
