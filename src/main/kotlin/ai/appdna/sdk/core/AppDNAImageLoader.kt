package ai.appdna.sdk.core

import android.content.Context
import android.os.Build
import coil.ImageLoader
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import java.io.File

/**
 * Singleton Coil [ImageLoader] for the SDK's [NetworkImage].
 *
 * Replaces the previous `BitmapFactory.decodeStream` path that re-decoded
 * the bitmap on every recomposition (no caching, blocked the launching
 * coroutine on the network call again whenever Compose re-laid the screen).
 *
 * Cache configuration:
 *   - **Memory cache**: 25% of the app's available memory budget
 *     (`MemoryCache.Builder.maxSizePercent(0.25)`).
 *   - **Disk cache**: 50 MB at `<cacheDir>/appdna_image_cache`.
 *
 * The loader is intentionally namespaced separately from the host app's
 * default Coil loader (`Coil.imageLoader(context)`) so the SDK's caching
 * behaviour can't be accidentally clobbered by a host app that sets a
 * different default singleton via `Coil.setImageLoader(...)`.
 *
 * Animated-GIF decoder registered alongside the static
 * decoders. On API 28+ Coil's [ImageDecoderDecoder] uses the platform
 * `ImageDecoder` (covers GIF + animated WebP + animated HEIF); on older
 * devices the legacy [GifDecoder] is registered as a fallback so GIF
 * URLs still animate frame-by-frame instead of rendering a static
 * first frame. SVG is handled in [NetworkImage] directly via
 * `androidsvg` rather than as a Coil decoder so we don't add a third
 * decoder dependency to the host classpath.
 */
internal object AppDNAImageLoader {

    private const val DISK_CACHE_DIRECTORY = "appdna_image_cache"
    private const val DISK_CACHE_BYTES: Long = 50L * 1024L * 1024L // 50 MB
    private const val MEMORY_CACHE_PERCENT: Double = 0.25 // 25% of app memory budget

    @Volatile
    private var instance: ImageLoader? = null

    /**
     * Returns the SDK's shared Coil [ImageLoader], creating it on first use.
     *
     * Thread-safe via double-checked locking — multiple Compose threads
     * touching [NetworkImage] during the first frame won't race to build
     * separate loaders.
     */
    fun singleton(context: Context): ImageLoader {
        instance?.let { return it }
        return synchronized(this) {
            instance ?: build(context.applicationContext).also { instance = it }
        }
    }

    /** Resolves the on-disk cache directory used by the SDK loader. */
    fun diskCacheDir(context: Context): File =
        File(context.applicationContext.cacheDir, DISK_CACHE_DIRECTORY)

    private fun build(appContext: Context): ImageLoader {
        return ImageLoader.Builder(appContext)
            .components {
                // Register GIF decoder so animated GIFs
                // render their full animation rather than the first frame.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    add(ImageDecoderDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
                // #986 — `appdna-assets/<hash><ext>` from a bundled config, resolved out of the
                // app's own assets when the file is there and handed back untouched when it is
                // not, so a partially bundled config degrades to the network rather than to a
                // broken image. The iOS counterpart is `BundledImageResolver`.
                add(BundledAssetMapper(appContext))
            }
            .memoryCache {
                MemoryCache.Builder(appContext)
                    .maxSizePercent(MEMORY_CACHE_PERCENT)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(diskCacheDir(appContext))
                    .maxSizeBytes(DISK_CACHE_BYTES)
                    .build()
            }
            .build()
    }
}
