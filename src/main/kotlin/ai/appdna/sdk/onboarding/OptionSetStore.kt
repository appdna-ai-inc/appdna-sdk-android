package ai.appdna.sdk.onboarding

import ai.appdna.sdk.Log
import ai.appdna.sdk.network.ApiClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.net.URLEncoder

/**
 * SPEC-448 §A/§C — the device side of a dynamic option list. Mirrors iOS `OptionSetStore`.
 *
 * 🔴 The rule this exists to satisfy is "**never looks like it is fetching**". A Select bound to a
 * set must render instantly with whatever it already has, and improve quietly. The fallback ladder
 * is strict, and it is a ladder rather than a race:
 *
 *   1. **cache** — a previous fetch for this set at this version
 *   2. **embedded page** — the first N items that shipped inside the flow config, which is also
 *      the only thing an SDK predating this spec ever sees
 *   3. **static options** — whatever the author left authored on the block
 *   4. **skip** — the step has nothing to show
 *
 * Only step 1 misses on a genuinely cold start, and only then is a skeleton correct.
 */
internal object OptionSetStore {

    private data class CacheEntry(
        val version: Int,
        val items: List<InputOption>,
        val totalCount: Int,
    )

    /**
     * 🔴 SCALE. Three limits, because this feature is explicitly for lists of thousands and an
     * unbounded store is how that becomes an OOM report rather than a feature.
     *
     * - [MAX_ITEMS_PER_SET] caps what paging accumulates. A user scrolling a 20,000-item list would
     *   otherwise hold all 20,000 parsed options resident; the oldest pages are dropped and
     *   scrolling back re-fetches, which is far cheaper than never releasing them.
     * - [MAX_CACHED_SETS] caps how many sets stay resident, evicting least-recently-used.
     * - [MAX_DISK_BYTES] caps what is persisted, so the SDK cannot grow a user's storage without
     *   bound on a device that never clears it.
     */
    private const val MAX_ITEMS_PER_SET = 2_000
    private const val MAX_CACHED_SETS = 8
    private const val MAX_DISK_BYTES = 2 * 1024 * 1024

    private val cache = mutableMapOf<String, CacheEntry>()
    /** Access order for LRU eviction — most recent last. */
    private val lru = mutableListOf<String>()
    /** Next-page cursor per set. Absence means 'no next page'. */
    private val cursors = mutableMapOf<String, String>()
    private val mutex = Mutex()

    // ── Persistence ────────────────────────────────────────────────────────────────────────
    //
    // 🔴 Without this the ladder's FIRST rung is empty on every cold launch, and the promise that a
    // warm run never looks like it is fetching only holds within one process. A user opening the
    // app fresh would see the embedded 50 items and a re-download every time — on a list of
    // thousands that is a worse experience AND real repeated bandwidth for the customer.
    //
    // Written to cacheDir, which Android may reclaim under pressure — correct, because this is
    // re-derivable from the server. Every failure is swallowed: a cache that cannot be written must
    // never break a render.

    private fun cacheDir(): java.io.File? = try {
        ai.appdna.sdk.AppDNA.appContextForBridges()?.let { ctx ->
            java.io.File(ctx.cacheDir, "appdna-option-sets").apply { mkdirs() }
        }
    } catch (e: Exception) { null }

    private fun persist(setId: String) {
        try {
            val dir = cacheDir() ?: return
            val entry = cache[setId] ?: return
            val arr = org.json.JSONArray()
            for (item in entry.items) {
                arr.put(
                    org.json.JSONObject()
                        .put("id", item.id ?: item.value)
                        .put("value", item.value ?: item.id)
                        .put("label", item.label ?: "")
                        .putOpt("subtitle", item.subtitle)
                        .putOpt("category", item.category)
                        .putOpt("image_url", item.image_url)
                        .putOpt("icon", item.icon),
                )
            }
            val payload = org.json.JSONObject()
                .put("version", entry.version)
                .put("total_count", entry.totalCount)
                .putOpt("cursor", cursors[setId])
                .put("items", arr)
                .toString()
            if (payload.toByteArray().size > MAX_DISK_BYTES) return
            java.io.File(dir, "$setId.json").writeText(payload)
        } catch (e: Exception) { /* a cache write must never break a render */ }
    }

    /**
     * Load a persisted set into memory. Called before the ladder is consulted, so a cold launch
     * still has a real first rung.
     */
    @Synchronized
    fun hydrate(setId: String) {
        if (cache.containsKey(setId)) return
        try {
            val dir = cacheDir() ?: return
            val f = java.io.File(dir, "$setId.json")
            if (!f.exists()) return
            val json = org.json.JSONObject(f.readText())
            val arr = json.optJSONArray("items") ?: return
            val maps = (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { o -> o.keys().asSequence().associateWith { o.get(it) } }
            }
            val items = OnboardingConfigParser.parseInputOptionList(maps)
            if (items.isEmpty()) return
            cache[setId] = CacheEntry(
                version = json.optInt("version", 0),
                items = items,
                totalCount = json.optInt("total_count", items.size),
            )
            json.optString("cursor").takeIf { it.isNotEmpty() && it != "null" }?.let { cursors[setId] = it }
            touch(setId)
        } catch (e: Exception) { /* a corrupt cache file is a cold start, not a crash */ }
    }

    /** Mark a set as most-recently-used and evict past the cap. */
    private fun touch(setId: String) {
        lru.remove(setId)
        lru.add(setId)
        while (lru.size > MAX_CACHED_SETS) {
            val oldest = lru.removeAt(0)
            cache.remove(oldest)
            cursors.remove(oldest)
            // The DISK copy stays: eviction is about memory, and the file is what makes the next
            // cold start fast. Disk is bounded by its own byte cap instead.
        }
    }

    /**
     * What a Select should render RIGHT NOW, without waiting for anything.
     *
     * Deliberately NOT a suspend function: making the first frame depend on a coroutine would put
     * the network on the render path even when the answer is already in memory, which is the
     * "looks like it is fetching" this design avoids.
     */
    fun immediateOptions(
        setId: String?,
        embedded: List<InputOption>,
        authored: List<InputOption>,
    ): List<InputOption> {
        if (setId.isNullOrEmpty()) return authored
        val cached = cache[setId]?.items
        if (!cached.isNullOrEmpty()) return cached // 1
        if (embedded.isNotEmpty()) return embedded // 2
        return authored // 3 (4 = empty)
    }

    /** True only when there is genuinely nothing to draw — the one case where a skeleton is honest. */
    fun isColdStart(setId: String?, embedded: List<InputOption>, authored: List<InputOption>): Boolean =
        immediateOptions(setId, embedded, authored).isEmpty()

    /**
     * Refresh in the background. Never throws: a failed refresh must leave the ladder standing,
     * not replace a working list with an error.
     */
    suspend fun refresh(setId: String, client: ApiClient?, expectedVersion: Int?): List<InputOption> {
        // A cached entry at the version the flow config names is current by definition — this is
        // what makes an author's edit land on next launch via the version, not on TTL expiry.
        val cached = cache[setId]
        if (cached != null && expectedVersion != null && cached.version >= expectedVersion) {
            return cached.items
        }
        if (client == null) return cached?.items ?: emptyList()

        return try {
            val json = client.get("/api/v1/sdk/option-sets/$setId") ?: return emptyList()
            val page = parsePage(json) ?: return emptyList()
            mutex.withLock {
                cache[setId] = page.first
                setCursor(setId, page.second)
                touch(setId)
                persist(setId)
            }
            page.first.items
        } catch (e: Exception) {
            Log.debug("Option set $setId refresh failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * Search the set remotely. Returns null when the search could not be performed, which the
     * caller shows as "no progress" rather than "no results" — an empty list would tell the user
     * their query matched nothing, a different and wrong statement.
     */
    suspend fun search(setId: String, query: String, client: ApiClient?): List<InputOption>? {
        if (client == null) return null
        return try {
            // Encoded: a set id is a UUID, but a QUERY is whatever the user typed — an accented
            // name or an ampersand would otherwise corrupt the URL.
            val q = URLEncoder.encode(query, "UTF-8")
            val json = client.get("/api/v1/sdk/option-sets/$setId?q=$q") ?: return null
            parsePage(json)?.first?.items
        } catch (e: Exception) {
            null
        }
    }

    /** Next page, for scrolling past the first N. */
    suspend fun nextPage(setId: String, cursor: String, client: ApiClient?): List<InputOption>? {
        if (client == null) return null
        return try {
            val c = URLEncoder.encode(cursor, "UTF-8")
            val json = client.get("/api/v1/sdk/option-sets/$setId?cursor=$c") ?: return null
            val parsed = parsePage(json) ?: return null
            val page = parsed.first
            mutex.withLock {
                setCursor(setId, parsed.second)
                val existing = cache[setId]
                if (existing == null) {
                    cache[setId] = page
                } else {
                    // De-duplicate by value: a reorder between two page fetches can legitimately
                    // return an item the caller already holds, and showing it twice is worse than
                    // showing it late.
                    val seen = existing.items.mapNotNull { it.value }.toMutableSet()
                    val merged = existing.items.toMutableList()
                    for (item in page.items) {
                        val v = item.value ?: continue
                        if (seen.add(v)) merged.add(item)
                    }
                    // Cap what paging accumulates: keep the MOST RECENT window. Scrolling back
                    // re-fetches, which is cheaper than holding every page a long session touched.
                    val capped = if (merged.size > MAX_ITEMS_PER_SET) merged.takeLast(MAX_ITEMS_PER_SET) else merged
                    cache[setId] = existing.copy(items = capped, version = page.version, totalCount = page.totalCount)
                    touch(setId)
                    persist(setId)
                }
            }
            page.items
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Parses into the SAME `InputOption` the inline options use, via the same parser, rather than
     * a parallel type — an option authored in a set and the identical option authored inline must
     * render identically, and a second parser here is how that drifts.
     */
    /** The cursor for the next page, or null at the end. */
    @Synchronized
    fun cursor(setId: String): String? = cursors[setId]

    /** Everything cached for this set, after de-duplication. */
    @Synchronized
    fun cachedItems(setId: String): List<InputOption> = cache[setId]?.items.orEmpty()

    private fun setCursor(setId: String, next: String?) {
        if (!next.isNullOrEmpty()) cursors[setId] = next else cursors.remove(setId)
    }

    private fun parsePage(json: JSONObject): Pair<CacheEntry, String?>? {
        val data = json.optJSONObject("data") ?: return null
        val arr = data.optJSONArray("items") ?: return null
        // JSONObject → Map so the SHARED parser can read it. Going through the same
        // `parseInputOptionList` the inline path uses is the point: a separate parser here would
        // drift a field at a time, and set-authored options would slowly stop matching inline ones.
        val maps = (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { jsonToMap(it) }
        }
        val items = OnboardingConfigParser.parseInputOptionList(maps)
        return CacheEntry(
            version = data.optInt("version", 0),
            items = items,
            totalCount = data.optInt("total_count", items.size),
        ) to data.optString("next_cursor").takeIf { it.isNotEmpty() && it != "null" }
    }

    /** Recursive so nested option payloads (badge, sheet_blocks) survive the conversion. */
    private fun jsonToMap(obj: JSONObject): Map<String, Any> {
        val out = mutableMapOf<String, Any>()
        for (key in obj.keys()) {
            when (val v = obj.get(key)) {
                is JSONObject -> out[key] = jsonToMap(v)
                is org.json.JSONArray -> out[key] = (0 until v.length()).mapNotNull { i ->
                    when (val e = v.get(i)) {
                        is JSONObject -> jsonToMap(e)
                        JSONObject.NULL -> null
                        else -> e
                    }
                }
                JSONObject.NULL -> Unit // a null in JSON is an ABSENT key here, not a null value
                else -> out[key] = v
            }
        }
        return out
    }

    /** Test seam — the cache is process-lifetime, so tests must be able to start clean. */
    fun resetForTesting() {
        cache.clear()
        cursors.clear()
        lru.clear()
    }
}
