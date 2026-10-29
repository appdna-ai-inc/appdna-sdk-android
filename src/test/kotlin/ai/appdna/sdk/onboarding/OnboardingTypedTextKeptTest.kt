package ai.appdna.sdk.onboarding

import ai.appdna.sdk.AppDNA
import ai.appdna.sdk.billing.BillingTestPipeline
import ai.appdna.sdk.config.RemoteConfigManager
import ai.appdna.sdk.storage.LocalStorage
import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.os.Parcel
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * Text the user typed on the step on screen, and has not submitted yet, is still in its fields when
 * the onboarding activity is rebuilt: after a configuration change (a rotation), and after the OS
 * killed the process in the background and Android restored the activity in a new one.
 *
 * Covers the three places a step keeps typed values: the `input_*` content blocks, the fields of a
 * `form` step, and a password field — kept across a rotation (it never leaves memory), and NOT
 * written into the saved-state Bundle, which leaves the process.
 *
 * The activity is real (Robolectric), the typing goes through the text fields' own semantics
 * actions, and the process-death Bundle is parcelled as it is between processes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class OnboardingTypedTextKeptTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var events: BillingTestPipeline
    private val controllers = mutableListOf<ActivityController<*>>()

    private val blocksStep = mapOf(
        "id" to "s_blocks", "type" to "custom",
        "config" to mapOf(
            "content_blocks" to listOf(
                mapOf("id" to "b_name", "type" to "input_text", "field_id" to "name", "field_label" to "Name"),
                mapOf("id" to "b_story", "type" to "input_textarea", "field_id" to "story", "field_label" to "Story"),
                mapOf("id" to "b_pw", "type" to "input_password", "field_id" to "pw", "field_label" to "Password"),
            ),
        ),
    )
    private val formStep = mapOf(
        "id" to "s_form", "type" to "form",
        "config" to mapOf(
            "fields" to listOf(
                mapOf("id" to "city", "type" to "text", "label" to "City"),
                mapOf("id" to "secret", "type" to "password", "label" to "Secret"),
            ),
        ),
    )
    private val lastStep = mapOf("id" to "s_end", "type" to "welcome", "config" to mapOf("title" to "End"))
    private val locationStep = mapOf(
        "id" to "s_loc", "type" to "custom",
        "config" to mapOf(
            "content_blocks" to listOf(
                mapOf(
                    "id" to "b_loc", "type" to "input_location", "field_id" to "where",
                    "field_config" to mapOf("location_type" to "address", "location_min_chars" to 3),
                ),
            ),
        ),
    )

    /** Autocomplete answers with no rows; nothing leaves the test. */
    private val noRows = object : GeocodeTransport {
        override suspend fun post(url: String, headers: Map<String, String>, body: String): GeocodeHttpReply =
            GeocodeHttpReply(200, """{"data":{"suggestions":[]}}""")
    }

    @Before
    fun setUp() {
        events = BillingTestPipeline(ctx, "onboarding_typed_text")
        LocalStorage(ctx).setString(
            "cache_onboarding",
            JSONObject(mapOf("active_flow_id" to FLOW, "flows" to mapOf(FLOW to mapOf(
                "id" to FLOW, "name" to "Typed text probe", "version" to 1,
                "steps" to listOf(blocksStep, formStep, lastStep, locationStep),
            )))).toString(),
        )
    }

    @After
    fun tearDown() {
        for (c in controllers.asReversed()) {
            if (!c.get().isDestroyed) runCatching { c.pause().stop().destroy() }
        }
        controllers.clear()
        idle()
        AppDNA.installOnboardingForTest(null, ready = false)
        OnboardingActivity.clearActiveLaunches()
        PendingCompletionRoute.clear()
        LocalStorage(ctx).setString("cache_onboarding", "{}")
        locationGeocodeTransport = LiveGeocodeTransport
        events.close()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun manager() = OnboardingFlowManager(RemoteConfigManager(null, LocalStorage(ctx), 3600L), events.tracker)

    private fun vmOf(activity: OnboardingActivity): OnboardingViewModel =
        ViewModelProvider(activity)[OnboardingViewModel::class.java]

    /** Present the flow and open it on [stepIndex]. */
    private fun present(stepIndex: Int): ActivityController<OnboardingActivity> {
        AppDNA.installOnboardingForTest(manager(), ready = true)
        val host = Robolectric.buildActivity(Activity::class.java).setup().also { controllers += it }.get()
        assertTrue(AppDNA.presentOnboarding(host, FLOW))
        val c = Robolectric.buildActivity(OnboardingActivity::class.java).setup().also { controllers += it }
        idle()
        vmOf(c.get()).currentIndex.intValue = stepIndex
        idle()
        compose.waitForIdle()
        return c
    }

    private fun fields(): List<SemanticsNodeInteraction> {
        val all = compose.onAllNodes(hasSetTextAction())
        val n = all.fetchSemanticsNodes().size
        return (0 until n).map { all[it] }
    }

    private fun fieldTexts(): List<String> = compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes()
        .map { it.config.getOrNull(SemanticsProperties.EditableText)?.text ?: "" }

    /** A password field reports its text masked, one bullet per character. */
    private fun masked(secret: String) = "\u2022".repeat(secret.length)

    /** Types [values] into the step's text fields in order; [shown] is what the fields then read. */
    private fun type(values: List<String>, shown: List<String> = values) {
        val f = fields()
        assertEquals("the step must show one text field per value typed", values.size, f.size)
        values.forEachIndexed { i, v -> if (v.isNotEmpty()) f[i].performTextInput(v) }
        compose.waitForIdle()
        assertEquals("the typing itself must land in the fields", shown, fieldTexts())
    }

    /** Save, destroy as a process death does (not a config change), and parcel the Bundle. */
    private fun kill(c: ActivityController<OnboardingActivity>): Bundle {
        val saved = Bundle()
        c.saveInstanceState(saved).pause().stop().destroy()
        val parcel = Parcel.obtain()
        val restored = try {
            parcel.writeBundle(saved)
            parcel.setDataPosition(0)
            parcel.readBundle(OnboardingActivity::class.java.classLoader)!!
        } finally {
            parcel.recycle()
        }
        OnboardingActivity.clearActiveLaunches()
        PendingCompletionRoute.clear()
        AppDNA.installOnboardingForTest(null, ready = false)
        return restored
    }

    private fun restoreInNewProcess(saved: Bundle): ActivityController<OnboardingActivity> {
        AppDNA.installOnboardingForTest(manager(), ready = true)
        val c = Robolectric.buildActivity(OnboardingActivity::class.java).setup(saved).also { controllers += it }
        idle()
        compose.waitForIdle()
        assertFalse("the restored activity must resume the flow", c.get().isFinishing)
        return c
    }

    // ---- Rotation ---------------------------------------------------------------------------------

    @Test
    fun `a rotation keeps the text typed into the input blocks, the password included`() {
        val c = present(0)
        type(listOf("Ada", "A long answer", "hunter2"), listOf("Ada", "A long answer", masked("hunter2")))

        c.recreate()
        idle()
        compose.waitForIdle()

        assertEquals(0, vmOf(c.get()).currentIndex.intValue)
        assertEquals(listOf("Ada", "A long answer", masked("hunter2")), fieldTexts())
    }

    @Test
    fun `a rotation keeps the text typed into a form step's fields, the password included`() {
        val c = present(1)
        type(listOf("Lisbon", "s3cret"), listOf("Lisbon", masked("s3cret")))

        c.recreate()
        idle()
        compose.waitForIdle()

        assertEquals(1, vmOf(c.get()).currentIndex.intValue)
        assertEquals(listOf("Lisbon", masked("s3cret")), fieldTexts())
    }

    @Test
    fun `a rotation and a process death keep the text typed into a location field`() {
        locationGeocodeTransport = noRows
        val c = present(3)
        type(listOf("12 Example"))

        c.recreate()
        idle()
        compose.waitForIdle()
        assertEquals("rotation", listOf("12 Example"), fieldTexts())

        val saved = kill(c)
        restoreInNewProcess(saved)
        assertEquals("process death", listOf("12 Example"), fieldTexts())
    }

    // ---- Process death ----------------------------------------------------------------------------

    @Test
    fun `after process death the input blocks show what was typed, except the password`() {
        val saved = kill(present(0).also { type(listOf("Ada", "A long answer", "hunter2"), listOf("Ada", "A long answer", masked("hunter2"))) })

        assertSecretNotInBundle(saved, "hunter2")
        restoreInNewProcess(saved)

        assertEquals(listOf("Ada", "A long answer", ""), fieldTexts())
    }

    @Test
    fun `after process death a form step's fields show what was typed, except the password`() {
        val saved = kill(present(1).also { type(listOf("Lisbon", "s3cret"), listOf("Lisbon", masked("s3cret"))) })

        assertSecretNotInBundle(saved, "s3cret")
        val restored = restoreInNewProcess(saved)

        assertEquals(1, vmOf(restored.get()).currentIndex.intValue)
        assertEquals(listOf("Lisbon", ""), fieldTexts())
    }

    @Test
    fun `typed text is a draft, not an answer — it is not submitted by being kept`() {
        val saved = kill(present(0).also { type(listOf("Ada", "", "")) })
        val restored = restoreInNewProcess(saved)

        assertNull("an unsubmitted draft must not appear as the step's answer", vmOf(restored.get()).responses["s_blocks"])
    }

    @Test
    fun `a step left and returned to starts from its submitted answers, not an old draft`() {
        val c = present(1)
        type(listOf("Lisbon", ""))
        val vm = vmOf(c.get())

        // Leave the step without submitting (as Skip does), then come back to it.
        vm.currentIndex.intValue = 2
        idle(); compose.waitForIdle()
        vm.currentIndex.intValue = 1
        idle(); compose.waitForIdle()

        assertEquals(listOf("", ""), fieldTexts())
    }

    @Test
    fun `a value too large for the saved state is left out, and the rest of the draft is kept`() {
        val draft = StepDraft("s_blocks")
        draft.inputs["name"] = "Ada"
        draft.inputs["signature"] = "x".repeat(StepDrafts.MAX_VALUE_CHARS + 1)
        draft.toggles["agree"] = true
        val json = StepDrafts.encode(draft, secrets = setOf("pw"))!!
        assertTrue("the encoded draft must stay small", json.length < 1024)

        val back = StepDrafts.decode(json, "s_blocks")!!
        assertEquals(mapOf("name" to "Ada"), back.inputs.toMap())
        assertEquals(mapOf("agree" to true), back.toggles.toMap())
        assertTrue("a restored draft is already seeded", back.seeded)
        assertNull("a draft saved for another step is not applied", StepDrafts.decode(json, "s_other"))
    }

    /** Walks every value, nested Bundles included (the saved-state registry nests them). */
    private fun assertSecretNotInBundle(saved: Bundle, secret: String) {
        for (key in saved.keySet()) {
            @Suppress("DEPRECATION")
            when (val v = saved.get(key)) {
                is Bundle -> assertSecretNotInBundle(v, secret)
                null -> Unit
                else -> assertFalse("the saved state must not carry the password (key $key)", v.toString().contains(secret))
            }
        }
    }

    private companion object {
        const val FLOW = "typed_text_probe_flow"
    }
}
