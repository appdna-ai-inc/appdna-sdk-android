package ai.appdna.sdk.config

import ai.appdna.sdk.AppDNA
import ai.appdna.sdk.storage.LocalStorage
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.MemoryCacheSettings
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.net.HttpURLConnection
import java.net.URL

/**
 * The whole fetch path against a REAL Firestore (the local emulator): `fetchConfigs()` reads each index
 * document and its per-item documents through the Firestore SDK, and the disk cache is read back by a new
 * manager. The shared fixture `index_prunes_removed_items` drives the same rule without Firestore; this
 * test proves the listeners wire it up — a successful read prunes, an empty index clears without reading
 * the mega-doc, and a failed read (network disabled) keeps everything.
 *
 * Opt-in: it runs only when `APPDNA_FIRESTORE_EMULATOR` names a running emulator (`host:port`), and is
 * skipped otherwise. All data is synthetic, under a throwaway project id.
 */
@RunWith(RobolectricTestRunner::class)
class IndexPruneFirestoreEmulatorTest {

    private val emulator: String? = System.getenv("APPDNA_FIRESTORE_EMULATOR")
    private val project = "demo-sdk-index-prune"
    private val appPath = "orgs/o_index/apps/a_index"
    private lateinit var ctx: Context
    private lateinit var db: FirebaseFirestore

    private val kinds = listOf(
        // index document, item-map key, mega-doc, item prefix, item fields
        Kind("paywall_index", "paywalls", "paywalls", "pw_", mapOf("name" to "Synthetic paywall")),
        Kind("onboarding_index", "flows", "onboarding", "flow_", mapOf("name" to "Synthetic flow", "version" to 1, "steps" to emptyList<Any>())),
        Kind("survey_index", "surveys", "surveys", "survey_", mapOf("name" to "Synthetic survey", "survey_type" to "nps")),
    )

    private data class Kind(val index: String, val key: String, val mega: String, val prefix: String, val fields: Map<String, Any>)

    @Before
    fun setUp() {
        assumeTrue("APPDNA_FIRESTORE_EMULATOR is not set", !emulator.isNullOrBlank())
        ctx = ApplicationProvider.getApplicationContext()
        val app = FirebaseApp.getApps(ctx).firstOrNull { it.name == "index_prune" } ?: FirebaseApp.initializeApp(
            ctx,
            FirebaseOptions.Builder().setProjectId(project).setApplicationId("1:1:android:1").setApiKey("fake").build(),
            "index_prune",
        )
        db = FirebaseFirestore.getInstance(app)
        val (host, port) = emulator!!.split(":")
        runCatching { db.useEmulator(host, port.toInt()) }
        db.firestoreSettings = FirebaseFirestoreSettings.Builder()
            .setLocalCacheSettings(MemoryCacheSettings.newBuilder().build())
            .build()
        installFirestore(db)
        http("DELETE", "http://$emulator/emulator/v1/projects/$project/databases/(default)/documents", null)
        for (s in listOf("flags", "experiments", "surveys", "paywalls", "onboarding", "messages")) {
            LocalStorage(ctx).remove("cache_$s")
        }
    }

    @After
    fun tearDown() {
        if (emulator.isNullOrBlank()) return
        installFirestore(null)
    }

    @Test
    fun aRemovedItemIsGoneFromMemoryAndFromTheDiskCacheAfterARestart() {
        publish(listOf("a", "b"))
        val first = fetch(RemoteConfigManager(appPath, LocalStorage(ctx), 3600L))
        assertHeld("after the first fetch", listOf("a", "b"), first)

        publish(listOf("a"))
        fetch(first)
        assertHeld("after the fetch that no longer lists b", listOf("a"), first)
        assertHeld("a new manager over the same disk cache", listOf("a"), RemoteConfigManager(appPath, LocalStorage(ctx), 3600L))

        // A restart, then removal: the item came from the disk cache, not from this process's fetches.
        val restarted = RemoteConfigManager(appPath, LocalStorage(ctx), 3600L)
        publish(emptyList(), megaDocIds = listOf("mega"))
        fetch(restarted)
        assertHeld("an empty index, with a mega-doc that lists another item", emptyList(), restarted)
        assertHeld("after the empty index, restarted", emptyList(), RemoteConfigManager(appPath, LocalStorage(ctx), 3600L))
    }

    @Test
    fun aFailedReadKeepsTheCache() {
        publish(listOf("a", "b"))
        val rcm = fetch(RemoteConfigManager(appPath, LocalStorage(ctx), 3600L))
        assertHeld("after the first fetch", listOf("a", "b"), rcm)

        publish(listOf("a"))
        await(db.disableNetwork())
        try {
            fetch(rcm)
        } finally {
            await(db.enableNetwork())
        }
        assertHeld("after a fetch with the network off", listOf("a", "b"), rcm)
        assertHeld("restarted after the failed fetch", listOf("a", "b"), RemoteConfigManager(appPath, LocalStorage(ctx), 3600L))
    }

    @Test
    fun noIndexDocumentReadsTheMegaDoc() {
        publishMegaOnly(listOf("old_a", "old_b"))
        val rcm = fetch(RemoteConfigManager(appPath, LocalStorage(ctx), 3600L))
        assertHeld("an older server's mega-doc", listOf("old_a", "old_b"), rcm)
        assertHeld("restarted", listOf("old_a", "old_b"), RemoteConfigManager(appPath, LocalStorage(ctx), 3600L))
    }

    /**
     * #1554 — the SERVER's own output, not a hand-written index. `APPDNA_SERVER_INDEX_DUMP` names the JSON the server
     * int test `issue1554-index-map.int.test.ts` writes (`ISSUE1554_DUMP`): a legacy app (mega-doc only, no index)
     * after one of its two flows was archived, as the server's config-sync services left the index, the kept flow's item
     * document and the mega-doc. A device that cached both flows from the mega-doc fetches once more and must drop the
     * archived one. Against the dump of the previous server (an index with no `flows` map) it keeps both.
     */
    @Test
    fun aServerWrittenIndexPrunesTheRemovedFlow() {
        val dumpPath = System.getenv("APPDNA_SERVER_INDEX_DUMP")
        assumeTrue("APPDNA_SERVER_INDEX_DUMP is not set", !dumpPath.isNullOrBlank())
        val dump = JSONObject(java.io.File(dumpPath!!).readText())
        val kept = dump.getString("kept")
        val removed = dump.getString("removed")
        @Suppress("UNCHECKED_CAST")
        val mega = plain(dump.getJSONObject("mega")) as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val template = (mega["flows"] as Map<String, Any>)[kept] as Map<String, Any>

        // Before: what an older server served — the mega-doc lists both flows, there is no index document.
        write("$appPath/config/onboarding", mapOf("flows" to mapOf(kept to template, removed to template + ("id" to removed))))
        val rcm = fetch(RemoteConfigManager(appPath, LocalStorage(ctx), 3600L))
        assertEquals("the device holds both flows", listOf(kept, removed).sorted(), rcm.getAllOnboardingFlows().keys.sorted())

        // After: exactly the documents the server wrote when the second flow was archived.
        @Suppress("UNCHECKED_CAST")
        write("$appPath/config/onboarding_index", plain(dump.getJSONObject("index")) as Map<String, Any>)
        val items = dump.optJSONObject("items") ?: JSONObject()
        for (id in items.keys()) {
            @Suppress("UNCHECKED_CAST")
            write("$appPath/config/onboarding_index/flows/$id", plain(items.getJSONObject(id)) as Map<String, Any>)
        }
        write("$appPath/config/onboarding", mega)
        fetch(rcm)
        assertEquals("after the fetch of the server-written index", listOf(kept), rcm.getAllOnboardingFlows().keys.sorted())
        assertEquals("restarted over the same disk cache", listOf(kept),
            RemoteConfigManager(appPath, LocalStorage(ctx), 3600L).getAllOnboardingFlows().keys.sorted())
    }

    // ── helpers ──

    /** org.json → plain Kotlin maps and lists (what [write] takes). */
    private fun plain(v: Any?): Any? = when (v) {
        null, JSONObject.NULL -> null
        is JSONObject -> v.keys().asSequence().associateWith { plain(v.get(it)) }
        is JSONArray -> (0 until v.length()).map { plain(v.get(it)) }
        else -> v
    }

    /** `AppDNA.firestoreDB` has a private setter; the test sets the backing field the SDK reads. */
    private fun installFirestore(db: FirebaseFirestore?) {
        AppDNA::class.java.getDeclaredField("firestoreDB").apply { isAccessible = true }.set(AppDNA, db)
    }

    private fun held(rcm: RemoteConfigManager) = listOf(
        rcm.getAllPaywalls().keys.sorted(),
        rcm.getAllOnboardingFlows().keys.sorted(),
        rcm.getSurveyConfigs().keys.sorted(),
    )

    private fun assertHeld(label: String, ids: List<String>, rcm: RemoteConfigManager) {
        assertEquals(label, kinds.map { k -> ids.map { k.prefix + it }.sorted() }, held(rcm))
    }

    /** One real `fetchConfigs()`, waited on until its `config_fetched` barrier has run. */
    private fun fetch(rcm: RemoteConfigManager): RemoteConfigManager {
        val start = System.currentTimeMillis()
        rcm.fetchConfigs()
        val deadline = start + 30_000
        while (rcm.isStale(start + 3600_000L - 1)) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(25)
            check(System.currentTimeMillis() < deadline) { "the fetch did not complete in 30 s" }
        }
        shadowOf(Looper.getMainLooper()).idle()
        return rcm
    }

    private fun await(task: com.google.android.gms.tasks.Task<*>) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!task.isComplete) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
            check(System.currentTimeMillis() < deadline) { "task did not complete" }
        }
    }

    /** The server's index-path shape: an index listing [ids], their item documents, and a stale mega-doc. */
    private fun publish(ids: List<String>, megaDocIds: List<String> = listOf("stale")) {
        for (k in kinds) {
            write("$appPath/config/${k.index}", mapOf(k.key to ids.associate { (k.prefix + it) to mapOf("name" to it) }))
            for (id in ids) write("$appPath/config/${k.index}/${k.key}/${k.prefix + id}", k.fields + ("id" to k.prefix + id))
            writeMega(k, megaDocIds)
        }
    }

    /** An older server: no index document, only the mega-doc. */
    private fun publishMegaOnly(ids: List<String>) {
        for (k in kinds) writeMega(k, ids)
    }

    private fun writeMega(k: Kind, ids: List<String>) {
        write("$appPath/config/${k.mega}", mapOf(k.key to ids.associate { (k.prefix + it) to (k.fields + ("id" to k.prefix + it)) }))
    }

    private fun write(path: String, data: Map<String, Any>) {
        val doc = JSONObject().put("name", "projects/$project/databases/(default)/documents/$path").put("fields", fields(data))
        val body = JSONObject().put("writes", JSONArray().put(JSONObject().put("update", doc)))
        http("POST", "http://$emulator/v1/projects/$project/databases/(default)/documents:commit", body.toString())
    }

    private fun fields(m: Map<*, *>): JSONObject = JSONObject().also { o -> m.forEach { (k, v) -> o.put(k.toString(), value(v)) } }

    private fun value(v: Any?): JSONObject = when (v) {
        null -> JSONObject().put("nullValue", JSONObject.NULL)
        is String -> JSONObject().put("stringValue", v)
        is Boolean -> JSONObject().put("booleanValue", v)
        is Int, is Long -> JSONObject().put("integerValue", v.toString())
        is Number -> JSONObject().put("doubleValue", v.toDouble())
        is Map<*, *> -> JSONObject().put("mapValue", JSONObject().put("fields", fields(v)))
        is List<*> -> JSONObject().put("arrayValue", JSONObject().put("values", JSONArray(v.map { value(it) })))
        else -> error("unsupported value $v")
    }

    private fun http(method: String, url: String, body: String?) {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.setRequestProperty("Authorization", "Bearer owner")
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = c.responseCode
        check(code in 200..299) { "$method $url → $code ${c.errorStream?.bufferedReader()?.readText()}" }
        c.disconnect()
    }
}
