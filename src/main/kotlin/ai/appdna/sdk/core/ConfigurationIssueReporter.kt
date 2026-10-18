package ai.appdna.sdk.core

import ai.appdna.sdk.AppDNA
import ai.appdna.sdk.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * #935 — A DROPPED BLOCK OR SECTION REACHES SOMEBODY.
 *
 * #919 made the iOS decode lenient and logged every drop with its coding path. That is a large
 * improvement over a silent vanish and it is still only a log: nothing leaves the device, so a
 * customer cannot report what their console is producing and neither can we. Nobody can answer
 * "how often is this happening in the field", which is the question that decides whether it
 * matters.
 *
 * Two reports, for two audiences:
 *
 * - `AppDNA.lifecycleDelegate.onConfigurationIssue(surface, id, detail)` — the HOST's copy, so an
 *   integration can forward it to its own crash reporter. It needs the host to implement something.
 * - the `sdk_decode_dropped` ANALYTICS event — OUR copy, which needs no host integration and lands
 *   where the rest of the fleet data already is.
 *
 * 🔴 BOTH CARRY THE DETAIL, NOT A COUNT. "dropped 4 of 5" tells an engineer that something is
 * wrong and nothing about what — exactly how this class of defect stayed invisible for months.
 *
 * 🔴 AND IT IS RATE-LIMITED. A malformed document is parsed on every bootstrap and every
 * presentation, so an unbounded report would bill a customer for thousands of identical events.
 * The same `surface` + `detail` is reported once per process. The iOS half
 * (`ConfigurationIssueReporter.swift`) does the same, with the same event name, so the two fleets
 * aggregate together.
 */
internal object ConfigurationIssueReporter {

    /** Shared with iOS, so the two platforms land in one BigQuery row set. */
    const val EVENT_NAME = "sdk_decode_dropped"

    private val seen = ConcurrentHashMap.newKeySet<String>()

    /**
     * Report one piece of published config that could not be used.
     *
     * @param surface the kind of document — `"onboarding_flow"`, `"paywall"`, `"survey"`, …
     * @param id the document it came from, when one is known at the call site.
     * @param detail what was wrong, as specifically as the call site can say it.
     * @param dropped how many elements were lost.
     * @param total how many were attempted, where that is known.
     */
    fun report(surface: String, id: String?, detail: String, dropped: Int = 1, total: Int = 1) {
        if (detail.isBlank()) return
        if (!seen.add("$surface|$detail")) return

        AppDNA.track(
            EVENT_NAME,
            mapOf(
                "surface" to surface,
                "surface_id" to (id ?: ""),
                // 🔴 Truncated rather than dropped: an event property has a practical size limit
                // and a long reason chain can run to kilobytes, but the FIRST path is the
                // actionable one and it is at the front.
                "coding_path" to detail.take(500),
                "dropped" to dropped,
                "total" to total,
            ),
        )

        // The host's copy, on the main thread — a delegate that touches UI must not be handed a
        // background thread, and config is parsed on whichever one the fetch landed on.
        /*
         * 🔴 THE DELEGATE IS CAPTURED NOW, NOT READ LATER — the same reason as the iOS half.
         * Reading it inside the posted block means reporting to whoever is registered when the
         * block happens to run, which is not the same thing.
         */
        val delegate = AppDNA.lifecycleDelegateOrNull
        runCatching {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                runCatching { delegate?.onConfigurationIssue(surface, id, detail) }
                    .onFailure { Log.warning("onConfigurationIssue threw: ${it.message}") }
            }
        }.onFailure { Log.warning("configuration-issue fan-out failed: ${it.message}") }
    }

    /** Test seam — the set is process-global, so a test asserting the first report must clear it. */
    fun resetForTesting() = seen.clear()
}
