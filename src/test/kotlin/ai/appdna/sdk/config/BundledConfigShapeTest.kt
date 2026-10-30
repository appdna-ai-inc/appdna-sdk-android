package ai.appdna.sdk.config

import ai.appdna.sdk.AppDNA
import ai.appdna.sdk.storage.LocalStorage
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The embedded `appdna-config.json` in the shape the server builds it: every section in its mega-doc shape
 * (`{paywalls:{id:…}}`, `{active_flow_id, flows:{…}}`, `{surveys:{…}}`, `{messages:{…}}`), flags as RAW
 * values, and a paywall set well over 1 MB — the size at which the server's old source (the legacy paywall
 * mega-doc) was skipped. Read through the SDK's own JSON → Map conversion. Every section loads; the flags
 * read back as their values. iOS `BundledConfigShapeTests`, same bundle.
 *
 * The negative control is kept as a test: `{value, type}` flag wrappers (what the old bundle carried) reach
 * the flag getter as maps — the bundle loader assigns the map as-is, which is why the server now writes raw
 * values.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BundledConfigShapeTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private fun bundle(flags: JSONObject): JSONObject {
        val pad = "x".repeat(30_000)
        val paywalls = JSONObject()
        for (i in 0 until PAYWALLS) {
            val id = "pw_$i"
            paywalls.put(id, JSONObject()
                .put("id", id).put("name", "Paywall $i").put("placement", "placement_$i").put("version", 1)
                .put("layout", JSONObject().put("type", "stack").put("sections", JSONArray()))
                .put("plans", JSONArray()).put("cta", JSONObject().put("text", "Go")).put("dismiss", JSONObject().put("type", "x_button"))
                .put("localizations", JSONObject().put("en", JSONObject().put("pad", pad))).put("default_locale", "en"))
        }
        return JSONObject()
            .put("paywalls", JSONObject().put("paywalls", paywalls))
            .put("onboarding", JSONObject().put("active_flow_id", "f1").put("flows", JSONObject()
                .put("f1", JSONObject().put("id", "f1").put("name", "Flow").put("version", 1).put("steps", JSONArray()))))
            .put("surveys", JSONObject().put("surveys", JSONObject()
                .put("s1", JSONObject().put("name", "Survey").put("survey_type", "nps").put("questions", JSONArray()))))
            .put("messages", JSONObject().put("messages", JSONObject()
                .put("m1", JSONObject().put("name", "Message").put("message_type", "modal")
                    .put("content", JSONObject().put("title", "Hi"))
                    .put("trigger_rules", JSONObject().put("event", "app_open")))))
            .put("remote_config", flags).put("feature_flags", flags)
            .put("experiments", JSONObject()).put("screen_index", JSONObject()).put("brand", JSONObject())
            .put("bundle_version", 7)
    }

    private fun load(json: JSONObject): RemoteConfigManager {
        val rcm = RemoteConfigManager(null, LocalStorage(ctx), 3600L)
        // Through bytes and the SDK's own JSONObject → Map conversion, as `loadConfigBundle` reads the asset.
        rcm.loadBundledConfig(AppDNA.jsonObjectToMap(JSONObject(json.toString())))
        return rcm
    }

    @Test fun everySectionOfAServerBuiltBundleLoadsIncludingAPaywallSetOverOneMegabyte() {
        val json = bundle(JSONObject().put("k_bool", true).put("k_num", 3).put("k_str", "x"))
        assertTrue("the bundle must be over 1 MB to prove anything", json.toString().toByteArray().size > 1_048_576)
        val rcm = load(json)

        assertEquals("paywalls lost from a > 1 MB bundle", PAYWALLS, rcm.getAllPaywalls().size)
        assertEquals(setOf("f1"), rcm.getAllOnboardingFlows().keys)
        assertEquals(setOf("s1"), rcm.getSurveyConfigs().keys)
        assertEquals(setOf("m1"), rcm.messagesForTesting().keys)

        val flags = FeatureFlagManager(rcm)
        assertEquals(true, rcm.getConfig("k_bool"))
        assertTrue(flags.isEnabled("k_bool"))
        assertEquals(3, (rcm.getConfig("k_num") as Number).toInt())
        assertEquals("x", rcm.getConfig("k_str"))
    }

    /** Negative control: wrapped flag entries reach the getter as maps, not values. */
    @Test fun wrappedFlagsInABundleReachTheGetterAsMaps() {
        val rcm = load(bundle(JSONObject().put("k_bool", JSONObject().put("value", true).put("type", "boolean"))))
        assertTrue("the loader unwraps flags now — the server could go back to wrapped entries",
            rcm.getConfig("k_bool") is Map<*, *>)
        assertNull(rcm.getConfig("k_bool") as? Boolean)
    }

    private companion object { const val PAYWALLS = 40 }

    /**
     * A BUNDLE THE SDK CANNOT READ MUST SAY SO.
     *
     * Every section above is optional, so a bundle carrying none of them parsed in silence and the
     * SDK carried on with no config at all: valid JSON, right size, right place, and inert.
     *
     * 🔴 That silence is the defect, not the wrong bundle. The console's CI/CD snippet saved the
     * RAW response from `/api/v1/sdk/config-bundle`, whose payload sits under `.data.content`, so
     * every key the loader looks for was one level too deep. Measured on a device, airplane mode,
     * fresh install each time: the raw-response asset gave `presentOnboarding → false` at T+95s
     * while `.data.content + {bundle_version}` gave `true` at T+40s — and not one line was logged
     * to tell the two apart.
     */
    private fun logsFrom(json: JSONObject): String {
        // `Log` writes with println and has no handler seam, so stdout is the capture point.
        val original = System.out
        val buffer = java.io.ByteArrayOutputStream()
        System.setOut(java.io.PrintStream(buffer, true))
        try { load(json) } finally { System.setOut(original) }
        return buffer.toString()
    }

    @Test fun aBundleWithNoRecognisedSectionWarnsAndNamesTheLikelyCause() {
        // Exactly what the wrong snippet produced: the envelope rather than its content.
        val raw = JSONObject()
            .put("data", JSONObject().put("content", JSONObject().put("onboarding", JSONObject())))
            .put("bundle_version", 7)
        val logs = logsFrom(raw)
        assertTrue("a bundle the SDK cannot read must warn — logs were:\n$logs",
            logs.contains("none of the sections"))
        assertTrue("the warning must name the keys that WERE present", logs.contains("data"))
        assertTrue("the warning must name the likely cause", logs.contains(".data.content"))
    }

    @Test fun aCorrectBundleStaysQuiet() {
        // The guard must not cry wolf, or it becomes a line people learn to scroll past.
        val logs = logsFrom(JSONObject().put("onboarding", JSONObject()).put("bundle_version", 7))
        assertTrue("a correct bundle must NOT warn — logs were:\n$logs",
            !logs.contains("none of the sections"))
    }

    @Test fun anEmptyBundleWarnsToo() {
        assertTrue(logsFrom(JSONObject()).contains("none of the sections"))
    }
}

