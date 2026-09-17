package ai.appdna.sdk.core

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri

/**
 * Shared audio player for the `sound_button` content block (Device QA
 * scenario s20/s22). Streams a remote audio clip (mp3/wav/aac) from a URL and plays it on
 * tap / autoplay. Uses [MediaPlayer] with `prepareAsync()` so remote http(s)
 * URLs stream without a manual download step. A single retained player is
 * replaced on each new play, so tapping repeatedly restarts the clip rather than
 * overlapping.
 */
internal object AudioPlayer {
    private var player: MediaPlayer? = null

    /**
     * Play the audio clip at [url]. No-op on null/blank/unsafe URLs. Only http(s)
     * URLs are honoured (parity with iOS AVPlayer path and to keep the block from
     * reaching non-audio schemes).
     */
    @Synchronized
    fun play(context: Context, url: String?) {
        val raw = url?.trim()
        if (raw.isNullOrEmpty()) return
        val scheme = runCatching { Uri.parse(raw).scheme?.lowercase() }.getOrNull()
        if (scheme != "https" && scheme != "http") return

        try {
            player?.release()
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                setDataSource(context.applicationContext, Uri.parse(raw))
                setOnPreparedListener { it.start() }
                setOnCompletionListener { mp ->
                    mp.release()
                    if (player === mp) player = null
                }
                setOnErrorListener { mp, _, _ ->
                    mp.release()
                    if (player === mp) player = null
                    true
                }
                prepareAsync()
            }
        } catch (_: Exception) {
            player?.release()
            player = null
        }
    }

    /** Stop and release the current player. */
    @Synchronized
    fun stop() {
        player?.release()
        player = null
    }
}
