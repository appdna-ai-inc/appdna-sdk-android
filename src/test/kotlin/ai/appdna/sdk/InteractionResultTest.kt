package ai.appdna.sdk

import ai.appdna.sdk.onboarding.ElementInteractionResult
import ai.appdna.sdk.onboarding.HostDataInteractionAccess
import ai.appdna.sdk.onboarding.HostDataInteractionStore
import ai.appdna.sdk.onboarding.HostDataLayerEntry
import ai.appdna.sdk.onboarding.HostDataLayering
import ai.appdna.sdk.onboarding.HostDataPresentations
import ai.appdna.sdk.onboarding.HostDataResolver
import ai.appdna.sdk.onboarding.StepConfigOverride
import ai.appdna.sdk.onboarding.applyInteractionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** SPEC-419 EPIC-11 — pure logic of the interactive-state-contract result application. */
class InteractionResultTest {

    /** Snapshot state written here has no Compose host to flush it; a later Compose test would never idle. */
    @org.junit.After
    fun flushSnapshotWrites() = androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()


    @Test
    fun mergesInputValuePatchesOverExisting() {
        val result = ElementInteractionResult(inputValuePatches = mapOf("name" to "Alex", "age" to 25))
        val applied = applyInteractionResult(result, mapOf("name" to "old", "city" to "NYC"))
        assertEquals("Alex", applied.inputValues["name"])  // patched
        assertEquals(25, applied.inputValues["age"])        // added
        assertEquals("NYC", applied.inputValues["city"])    // untouched
        assertTrue(applied.fieldConfigOverrides.isEmpty())
        assertFalse(applied.advance)
    }

    @Test
    fun exposesFieldConfigOverridesAndAdvance() {
        val result = ElementInteractionResult(
            fieldConfigPatches = mapOf("cal" to mapOf("selected_days" to listOf(1, 2, 3))),
            advance = true,
        )
        val applied = applyInteractionResult(result, emptyMap())
        assertEquals(listOf(1, 2, 3), applied.fieldConfigOverrides["cal"]?.get("selected_days"))
        assertTrue(applied.advance)
    }

    @Test
    fun emptyResultIsNoOp() {
        val applied = applyInteractionResult(ElementInteractionResult(), mapOf("k" to "v"))
        assertEquals("v", applied.inputValues["k"])
        assertTrue(applied.fieldConfigOverrides.isEmpty())
        assertFalse(applied.advance)
    }

    // ── SPEC-496 §5b C2 / C4.1 — passthrough ────────────────────────────────────────────────────

    @Test
    fun appliedInteractionPassesRawPatchesAndDataContextThrough() {
        val dc = mapOf<String, Any?>("recommendations" to listOf("a"), "banner" to null)
        val applied = applyInteractionResult(
            ElementInteractionResult(inputValuePatches = mapOf("x" to 1), dataContext = dc),
            mapOf("keep" to "me"),
        )
        // The raw patches, NOT the snapshot-merged map: the step scope applies them onto live values.
        assertEquals(mapOf("x" to 1), applied.inputValuePatches)
        assertEquals(dc, applied.dataContext)
        assertTrue(applied.dataContext!!.containsKey("banner"))
    }

    @Test
    fun minimumBridgeTimeoutIsEightSecondsForRefreshOnly() {
        assertEquals(8_000L, ElementInteractionResult.minimumBridgeTimeoutMs("refresh"))
        assertNull(ElementInteractionResult.minimumBridgeTimeoutMs("otp_entered"))
        assertNull(ElementInteractionResult.minimumBridgeTimeoutMs(null))
    }

    @Test
    fun decodeDataContextKeepsNullsAndStringifiesKeys() {
        val raw = mapOf<Any?, Any?>(1 to "one", "banner" to null, "meta" to mapOf("cursor" to null, 2 to listOf(1, null)))
        val d = ElementInteractionResult.decodeDataContext(raw)!!
        assertEquals("one", d["1"])
        assertTrue(d.containsKey("banner"))
        assertNull(d["banner"])
        val meta = d["meta"] as Map<*, *>
        assertTrue(meta.containsKey("cursor"))
        assertEquals(listOf(1, null), meta["2"])
        assertNull(ElementInteractionResult.decodeDataContext("not a map"))
        assertNull(ElementInteractionResult.decodeDataContext(null))
    }

    // ── SPEC-496 §5b C3 — effective / apply ─────────────────────────────────────────────────────

    private fun e(v: Any?, stamp: Long) = HostDataLayerEntry(v, removed = v == null, stamp = stamp)

    @Test
    fun effectiveLayersNewerEntriesOverTheBase() {
        val base = mapOf<String, Any?>("recommendations" to "page1", "banner" to "hi")
        // Base stamped 1, an interaction started later (2) replaces recommendations.
        val layer = HostDataLayering.applyInteraction(null, base, 1, mapOf("recommendations" to "page2"), 2)
        val eff = HostDataLayering.effective(base, 1, layer)!!
        assertEquals("page2", eff["recommendations"])
        assertEquals("hi", eff["banner"])
    }

    @Test
    fun removalThenSet() {
        val base = mapOf<String, Any?>("banner" to "Top picks")
        var layer = HostDataLayering.applyInteraction(null, base, 1, mapOf("banner" to null), 2)
        assertFalse(HostDataLayering.effective(base, 1, layer)!!.containsKey("banner"))
        layer = HostDataLayering.applyInteraction(layer, base, 1, mapOf("banner" to "More"), 3)
        assertEquals("More", HostDataLayering.effective(base, 1, layer)!!["banner"])
    }

    @Test
    fun baseKeyRemovalAndNilWhenOnlyRemovalsWithoutBase() {
        val base = mapOf<String, Any?>("a" to 1, "b" to 2)
        val layer = HostDataLayering.applyInteraction(null, base, 1, mapOf("a" to null), 5)
        assertEquals(mapOf<String, Any?>("b" to 2), HostDataLayering.effective(base, 1, layer))
        // No base, only removal markers → nil ("no host data").
        val onlyRemovals = HostDataLayering.applyInteraction(null, null, 0, mapOf("x" to null), 1)
        assertNull(HostDataLayering.effective(null, 0, onlyRemovals))
        // No base, a value entry → that value.
        assertEquals(mapOf<String, Any?>("x" to 9), HostDataLayering.effective(null, 0, mapOf("x" to e(9, 1))))
    }

    @Test
    fun skipsAnInteractionOlderThanTheBaseForAKeyTheBaseSets() {
        // The interaction call started at 2; a newer onBeforeStepRender call (5) already answered `recs`.
        val base = mapOf<String, Any?>("recs" to "base")
        val layer = HostDataLayering.applyInteraction(null, base, 5, mapOf("recs" to "old", "other" to "kept"), 2)
        assertFalse("not stored", layer.containsKey("recs"))
        assertEquals("kept", layer["other"]?.value)
        assertEquals("base", HostDataLayering.effective(base, 5, layer)!!["recs"])
    }

    @Test
    fun applyingABaseDeletesOlderEntriesOnlyForTheKeysItSets() {
        val layer = mapOf("recs" to e("layer", 2), "cursor" to e("p2", 2), "newer" to e("n", 9))
        val pruned = HostDataLayering.applyBase(layer, mapOf("recs" to "base", "newer" to "b"), 5)!!
        assertFalse(pruned.containsKey("recs"))      // superseded: deleted, never revives
        assertTrue(pruned.containsKey("cursor"))     // not set by the base: kept
        assertTrue(pruned.containsKey("newer"))      // started after the base: kept, and wins
        assertEquals("n", HostDataLayering.effective(mapOf("recs" to "base", "newer" to "b"), 5, pruned)!!["newer"])
    }

    @Test
    fun accessOrdersByCallStartWhateverTheArrivalOrder() {
        val overrides = mutableMapOf<String, StepConfigOverride>()
        val access = HostDataInteractionAccess(overrides, HostDataInteractionStore(), HostDataPresentations())
        val baseSeq = access.nextCallSeq()      // onBeforeStepRender re-fire starts
        val tapSeq = access.nextCallSeq()       // a Show more starts AFTER it
        // The interaction reply lands first…
        access.applyInteractionData("s", mapOf("recs" to "a..l"), tapSeq)
        // …then the older base lands: it must not supersede the later-started call.
        access.applyBeforeRenderReply("s", StepConfigOverride(dataContext = mapOf("recs" to "a..h")), baseSeq)
        assertEquals("a..l", access.effectiveHookData("s")!!["recs"])
    }

    @Test
    fun referencedKeysAreTheFirstSegmentAfterHookData() {
        val blocks = listOf(
            mapOf("id" to "t", "type" to "text", "text" to "{{hook_data.banner}} and {{responses.x}}"),
            mapOf(
                "id" to "s", "type" to "input_select",
                "field_config" to mapOf("repeat" to mapOf("source" to "hook_data.recommendations", "template_option_id" to "o")),
            ),
            mapOf("id" to "b", "type" to "image", "bindings" to mapOf("image_url" to "hook_data.hero.url")),
        )
        assertEquals(setOf("banner", "recommendations", "hero"), HostDataResolver.stepHookDataKeys(blocks, null))
        val locs = mapOf("en" to mapOf("block.t.text" to "{{hook_data.greeting}}"))
        assertTrue("greeting" in HostDataResolver.stepHookDataKeys(emptyList<Any>(), locs))
    }
}
