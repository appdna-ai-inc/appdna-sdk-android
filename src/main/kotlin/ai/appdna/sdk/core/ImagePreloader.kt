package ai.appdna.sdk.core

import ai.appdna.sdk.Log
import android.content.Context
import coil.imageLoader
import coil.request.ImageRequest

/**
 * Image prefetch helper.
 *
 * Warms Coil's memory + disk caches for a list of URLs so the first
 * onboarding/paywall paint never has to do a synchronous network fetch.
 *
 * Supports two callers:
 *   - Onboarding flow: pass every `image_url` referenced by the queued steps
 *     immediately after the flow config is loaded.
 *   - Paywall presentation: pass every hero/background image referenced by
 *     the resolved [PaywallConfig] before launching the activity.
 *
 * Mirrors iOS `Onboarding/ImagePreloader.swift` (NSURLCache + UIImage decode
 * pre-warm path). Wiring into the actual sites (OnboardingActivity /
 * PaywallManager) is intentionally NOT done here — separate items handle
 * those edits so we keep this file's surface area minimal.
 */
internal class ImagePreloader(private val context: Context) {

    /**
     * Enqueue Coil image-load requests for [urls]. No-op for blank URLs.
     * Coil handles its own dedupe + concurrency throttling so it's safe to
     * call this on every config refresh.
     */
    fun prefetch(urls: List<String>) {
        if (urls.isEmpty()) return
        val loader = context.imageLoader
        var queued = 0
        for (url in urls) {
            val trimmed = url.trim()
            if (trimmed.isEmpty()) continue
            try {
                val request = ImageRequest.Builder(context)
                    .data(trimmed)
                    .build()
                loader.enqueue(request)
                queued++
            } catch (e: Throwable) {
                Log.warning { "ImagePreloader could not enqueue '$trimmed': ${e.message}" }
            }
        }
        if (queued > 0) {
            Log.debug { "ImagePreloader: prefetching $queued image(s)" }
        }
    }
}
