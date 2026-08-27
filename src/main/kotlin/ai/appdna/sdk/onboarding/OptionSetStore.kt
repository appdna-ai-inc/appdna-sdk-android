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

    private val cache = mutableMapOf<String, CacheEntry>()
    /** Next-page cursor per set. Absence means 'no next page'. */
    private val cursors = mutableMapOf<String, String>()
    private val mutex = Mutex()

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
                    cache[setId] = existing.copy(items = merged, version = page.version, totalCount = page.totalCount)
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
    }
}
