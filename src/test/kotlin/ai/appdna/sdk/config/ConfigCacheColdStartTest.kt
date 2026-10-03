package ai.appdna.sdk.config

import ai.appdna.sdk.storage.LocalStorage
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * An offline cold start: a previous online session wrote each config cache exactly as [RemoteConfigManager]'s
 * writers do (`cacheData(section, JSONObject(firestoreData).toString())`), then a NEW manager is built with no
 * Firestore path — it serves only what `loadCachedConfigs` restores. One test per cache.
 *
 * NEGATIVE CONTROL (base code): the loader handed the parsers JSONObject values, which they skip (they take
 * Firestore's Map / List) — the paywall, onboarding, survey and message tests each find an EMPTY section (their
 * first assertion fails), and the flags test gets a JSONObject / JSONArray for an object- / list-valued flag
 * (`is Map` / `is List` fails). Experiments: `ExperimentCacheAndDocParseTest`. iOS reads its caches with
 * JSONSerialization (dictionaries and arrays, as a fetch gives) and needs no change.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConfigCacheColdStartTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    /** A previous online session cached [data] under [section]; a fresh manager starts offline. */
    private fun coldStart(section: String, data: Map<String, Any?>): RemoteConfigManager {
        val storage = LocalStorage(ctx)
        storage.setString("cache_$section", JSONObject(data).toString())
        return RemoteConfigManager(null, storage, 3600L)
    }

    @Test fun cachedPaywallsSurviveAnOfflineColdStart() {
        val rcm = coldStart("paywalls", mapOf("paywalls" to mapOf("pw_1" to mapOf(
            "id" to "pw_1", "name" to "Paywall", "placement" to "main", "version" to 1,
            "layout" to mapOf("type" to "stack", "sections" to listOf(mapOf("type" to "header", "data" to mapOf("title" to "Go pro")))),
            "plans" to emptyList<Any>(),
            "cta" to mapOf("text" to "Continue"), "dismiss" to mapOf("type" to "x_button"),
        ))))
        assertEquals("cached paywalls were lost", setOf("pw_1"), rcm.getAllPaywalls().keys)
        assertEquals("Paywall", rcm.getPaywallConfig("pw_1")?.name)
        assertEquals("the nested section list must survive", 1, rcm.getPaywallConfig("pw_1")?.sections?.size)
    }

    @Test fun cachedOnboardingFlowsSurviveAnOfflineColdStart() {
        val rcm = coldStart("onboarding", mapOf("active_flow_id" to "f1", "flows" to mapOf("f1" to mapOf(
            "id" to "f1", "name" to "Flow", "version" to 1,
            "steps" to listOf(mapOf("id" to "s1", "type" to "welcome", "config" to mapOf("title" to "Hi"))),
        ))))
        assertEquals("cached onboarding flows were lost", setOf("f1"), rcm.getAllOnboardingFlows().keys)
        assertEquals("f1", rcm.getOnboardingFlow(null)?.id)
        assertEquals(1, rcm.getOnboardingFlow("f1")?.steps?.size)
    }

    @Test fun cachedSurveysSurviveAnOfflineColdStart() {
        val rcm = coldStart("surveys", mapOf("surveys" to mapOf("s1" to mapOf(
            "name" to "Survey", "survey_type" to "nps",
            "questions" to listOf(mapOf("id" to "q1", "type" to "nps", "text" to "How likely?")),
        ))))
        assertEquals("cached surveys were lost", setOf("s1"), rcm.getSurveyConfigs().keys)
        assertTrue("a survey's nested values must be Firestore-shaped", rcm.getSurveyConfigs()["s1"]!!["questions"] is List<*>)
        // SurveyManager's handler is attached after construction (in configure): it gets the cached surveys at once.
        // NEGATIVE CONTROL (base code): the handler fired only on a later fetch — `delivered` stayed null.
        var delivered: Set<String>? = null
        rcm.surveyUpdateHandler = { delivered = it.keys }
        assertEquals("the cached surveys never reached the survey handler", setOf("s1"), delivered)
    }

    @Test fun cachedInAppMessagesSurviveAnOfflineColdStart() {
        val rcm = coldStart("messages", mapOf("messages" to mapOf("m1" to mapOf(
            "name" to "Message", "message_type" to "modal",
            "content" to mapOf("title" to "Hi"), "trigger_rules" to mapOf("event" to "app_open"),
        ))))
        assertEquals("cached messages were lost", setOf("m1"), rcm.messagesForTesting().keys)
    }

    @Test fun cachedFlagsKeepTheirOnlineShapes() {
        val rcm = coldStart("flags", mapOf(
            "k_bool" to true, "k_num" to 3, "k_str" to "x",
            "k_obj" to mapOf("a" to 1, "b" to listOf("x", "y")), "k_list" to listOf(1, 2),
        ))
        assertEquals(true, rcm.getConfig("k_bool"))
        assertEquals(3, (rcm.getConfig("k_num") as Number).toInt())
        assertEquals("x", rcm.getConfig("k_str"))
        val obj = rcm.getConfig("k_obj")
        assertTrue("an object flag must come back as a Map (as online), was ${obj?.javaClass}", obj is Map<*, *>)
        assertEquals(listOf("x", "y"), (obj as Map<*, *>)["b"])
        assertTrue("a list flag must come back as a List (as online)", rcm.getConfig("k_list") is List<*>)
    }

    /**
     * Whole-number doubles through the cache. Firestore hands a double over as a Double (16.0) and an integer as a
     * Long; `JSONObject.toString()` wrote 16.0 as `16`, which read back as an Integer — so a cached config was not
     * the config an online session parses: a remote-config value read `as? Double` was null offline, and raw maps
     * (survey configs) compared unequal. The cache writer now keeps a decimal point on every Double and the reader
     * turns integers back into Longs, so the cached sections parse to exactly what the online path builds.
     *
     * NEGATIVE CONTROL (base writer, `JSONObject(data).toString()`): `k_double` reads back as the Integer 2
     * (`is Double` fails) and the cached survey map holds 16 / 1 / 8 Integers (the survey `assertEquals` fails).
     */
    @Test fun wholeNumberDoublesKeepTheirOnlineTypesThroughTheCache() {
        val style = mapOf("container" to mapOf("corner_radius" to 8.0, "opacity" to 1.0,
            "text_style" to mapOf("font_size" to 16.0, "font_weight" to 600L, "opacity" to 1.0)))
        val paywalls = mapOf("paywalls" to mapOf("pw_1" to mapOf(
            "id" to "pw_1", "name" to "Paywall", "placement" to "main", "version" to 1L,
            "layout" to mapOf("type" to "stack", "sections" to listOf(mapOf("type" to "header",
                "data" to mapOf("title" to "Go pro"), "style" to style))),
            "plans" to emptyList<Any>(), "cta" to mapOf("text" to "Continue"), "dismiss" to mapOf("type" to "x_button"),
        )))
        val onboarding = mapOf("active_flow_id" to "f1", "flows" to mapOf("f1" to mapOf(
            "id" to "f1", "name" to "Flow", "version" to 1L,
            "steps" to listOf(mapOf("id" to "s1", "type" to "custom", "config" to mapOf("content_blocks" to listOf(
                mapOf("id" to "b1", "type" to "text", "text" to "Hi", "corner_radius" to 8.0,
                    "style" to mapOf("font_size" to 16.0, "opacity" to 1.0)),
            )))),
        )))
        val surveys = mapOf("surveys" to mapOf("s1" to mapOf("name" to "Survey", "survey_type" to "nps",
            "appearance" to mapOf("corner_radius" to 8.0, "opacity" to 1.0, "font_size" to 16.0),
            "questions" to listOf(mapOf("id" to "q1", "type" to "nps", "text" to "How likely?")))))
        val experiments = mapOf("experiments" to mapOf("e1" to mapOf("id" to "e1", "status" to "running", "salt" to "s",
            "platforms" to listOf("android"), "traffic_allocation" to 1.0, "started_at_ms" to 1_767_225_600_000L,
            "variants" to listOf(mapOf("id" to "a", "weight" to 1.0, "payload" to mapOf("price_scale" to 2.0))))))
        val flags = mapOf("k_double" to 2.0, "k_long" to 3L, "k_obj" to mapOf("ratio" to 1.0))

        // Online: the parsers get Firestore's own values.
        val online = RemoteConfigManager(null, LocalStorage(ctx), 3600L)
        online.loadBundledConfig(mapOf("paywalls" to paywalls, "onboarding" to onboarding, "surveys" to surveys,
            "experiments" to experiments, "remote_config" to flags))
        // A fetch caches each section; then a fresh manager starts offline from the cache.
        online.cacheSectionForTesting("paywalls", paywalls)
        online.cacheSectionForTesting("onboarding", onboarding)
        online.cacheSectionForTesting("surveys", surveys)
        online.cacheSectionForTesting("experiments", experiments)
        online.cacheSectionForTesting("flags", flags)
        val offline = RemoteConfigManager(null, LocalStorage(ctx), 3600L)

        assertTrue("a double flag must stay a Double, was ${offline.getConfig("k_double")?.javaClass}", offline.getConfig("k_double") is Double)
        assertEquals(2.0, offline.getConfig("k_double"))
        assertEquals(3L, offline.getConfig("k_long"))
        assertEquals(online.getAllConfig(), offline.getAllConfig())
        assertEquals("survey configs differ after the cache", online.getSurveyConfigs(), offline.getSurveyConfigs())

        val pwOnline = online.getPaywallConfig("pw_1")!!.sections.single().style
        val pwOffline = offline.getPaywallConfig("pw_1")!!.sections.single().style
        assertEquals(16.0, pwOffline?.container?.text_style?.font_size)
        assertEquals(8.0, pwOffline?.container?.corner_radius)
        assertEquals(1.0, pwOffline?.container?.opacity)
        assertEquals(pwOnline, pwOffline)

        val blocksOnline = online.getOnboardingFlow("f1")!!.steps.single().config.content_blocks
        val blocksOffline = offline.getOnboardingFlow("f1")!!.steps.single().config.content_blocks
        assertEquals(16.0, blocksOffline?.single()?.style?.font_size)
        assertEquals(8.0, blocksOffline?.single()?.corner_radius)
        assertEquals(blocksOnline, blocksOffline)

        assertEquals(online.getExperimentConfig("e1"), offline.getExperimentConfig("e1"))
        assertEquals(2.0, offline.getExperimentConfig("e1")!!.variants.single().config["price_scale"])
    }
}
