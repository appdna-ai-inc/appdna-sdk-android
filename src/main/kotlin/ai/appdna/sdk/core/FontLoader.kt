package ai.appdna.sdk.core

import android.content.Context
import android.graphics.Typeface as AndroidTypeface
import androidx.compose.ui.text.font.FontFamily
import java.io.File
import java.net.URL
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Downloads and registers custom fonts (.ttf/.otf) referenced by a hosted URL in config.
 *
 * Any element whose `font_family` value is a font URL (instead of a built-in family
 * identifier) renders that font — the loader downloads it once, caches it in the app's
 * cache dir, builds an [android.graphics.Typeface] via [AndroidTypeface.createFromFile],
 * and hands back a Compose [FontFamily] that `FontResolver` returns.
 *
 * The file-UPLOAD backend (storing an author-uploaded .ttf → serving a stable URL) is
 * separate infrastructure; this loader works with ANY hosted font URL today.
 */
object FontLoader {

    @Volatile private var appContext: Context? = null
    private val cache = ConcurrentHashMap<String, FontFamily>()
    private val inFlight = Collections.synchronizedSet(HashSet<String>())

    /** Wired from `AppDNA.configure` so downloads can reach the app cache dir. */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * True when a `font_family` value is a hosted custom-font URL rather than a
     * built-in family identifier.
     */
    fun isCustomFontURL(value: String?): Boolean {
        val v = value?.lowercase() ?: return false
        if (!v.startsWith("http://") && !v.startsWith("https://")) return false
        return v.endsWith(".ttf") || v.endsWith(".otf") ||
            v.contains(".ttf?") || v.contains(".otf?")
    }

    /**
     * Returns a Compose [FontFamily] for a font URL once it has been downloaded + built;
     * otherwise returns `null` and kicks off a one-time background download. Callers fall
     * back to the default family until it becomes available (the next config re-render
     * picks it up from the cache).
     */
    fun resolve(urlString: String): FontFamily? {
        cache[urlString]?.let { return it }
        val ctx = appContext ?: return null
        val dest = cacheFile(ctx, urlString)
        if (dest.exists()) {
            return build(dest, urlString)
        }
        if (inFlight.add(urlString)) {
            Thread {
                try {
                    URL(urlString).openStream().use { input ->
                        dest.outputStream().use { output -> input.copyTo(output) }
                    }
                    build(dest, urlString)
                } catch (_: Throwable) {
                    // best-effort — fall back to default family
                } finally {
                    inFlight.remove(urlString)
                }
            }.apply { isDaemon = true }.start()
        }
        return null
    }

    private fun build(file: File, urlString: String): FontFamily? {
        return try {
            val androidTypeface = AndroidTypeface.createFromFile(file)
            val family = FontFamily(androidx.compose.ui.text.font.Typeface(androidTypeface))
            cache[urlString] = family
            family
        } catch (_: Throwable) {
            null
        }
    }

    private fun cacheFile(ctx: Context, urlString: String): File {
        val dir = File(ctx.cacheDir, "appdna-fonts").apply { mkdirs() }
        val ext = if (urlString.lowercase().contains(".otf")) "otf" else "ttf"
        return File(dir, "${urlString.hashCode()}.$ext")
    }
}
