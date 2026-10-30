package ai.appdna.sdk.feedback

import ai.appdna.sdk.IdentityManager
import ai.appdna.sdk.events.ClientSeqCounter
import ai.appdna.sdk.events.DroppedEventsCounter
import ai.appdna.sdk.events.EventQueue
import ai.appdna.sdk.events.EventTracker
import ai.appdna.sdk.network.ApiClient
import ai.appdna.sdk.Environment
import ai.appdna.sdk.storage.EventDatabase
import ai.appdna.sdk.storage.LocalStorage
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

/**
 * WHAT HAPPENED TO THE FEEDBACK FORM, ON THE PATHS NOBODY CHOSE.
 *
 * iOS fixed this first, and the comment that fix left said Android "gets this from its activity
 * lifecycle". That is true of the CLEANUP and false of the EVENT: `onActivityDestroyed` took the
 * dialog down and recorded nothing, and a back press or a tap outside the dialog — two paths iOS
 * does not even have, because a `UIAlertController` alert cannot be dismissed that way — recorded
 * nothing either. So `feedback_form_dismissed` under-counted on Android, and three outcomes were
 * indistinguishable in the data: a user who read the form and closed it, one who backed out of it,
 * and one whose form was taken off the screen by the host.
 *
 * 🔴 THE WIRING IS THE PART THAT WAS MISSING, so the wiring is what is driven here — the REAL
 * dialog, through the real `presentFeedbackForm`, with Robolectric's own activity in
 * `ActivityThread` (which is where `getCurrentActivity()` looks). Asserting `resolveFeedbackForm`
 * alone would have passed before this change AND after it: the latch was never the defect.
 *
 * Its iOS half is `OrphanedFeedbackFormTests.swift`, which drives the resolver directly for the
 * reason recorded there — presenting a real alert in a hostless test target hangs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FeedbackFormDismissalTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private var db: EventDatabase? = null

    @After fun tearDown() { db?.clearAll() }

    /** The real pipeline, so what is asserted is what the SDK actually persisted. */
    private fun managerWithCapture(): Pair<SurveyManager, () -> List<Pair<String, Map<String, Any?>>>> {
        val storage = LocalStorage(ctx)
        val tracker = EventTracker(IdentityManager(storage), "1.0", "sandbox")
        val database = EventDatabase(ctx, 10_000, "feedback_form_dismissal.db").also { db = it }
        ClientSeqCounter.init(ctx)
        DroppedEventsCounter.init(ctx)
        database.clearAll()
        tracker.setEventQueue(
            EventQueue(
                apiClient = ApiClient("adn_test_fixture", Environment.PRODUCTION),
                eventDatabase = database,
                connectivityMonitor = null,
                batchSizeCap = 0,
                flushInterval = Long.MAX_VALUE,
            ),
        )
        val read = {
            database.loadAll().map { raw ->
                val o = JSONObject(raw)
                val props = mutableMapOf<String, Any?>()
                o.optJSONObject("properties")?.let { p -> p.keys().forEach { k -> props[k] = p.get(k) } }
                o.getString("event_name") to props.toMap()
            }
        }
        return SurveyManager(ctx, tracker) to read
    }

    /** `presentFeedbackForm` is private: it is the production entry point, so it is what is called. */
    private fun present(manager: SurveyManager, surveyId: String?) {
        val m = SurveyManager::class.java.getDeclaredMethod(
            "presentFeedbackForm", String::class.java, String::class.java,
        )
        m.isAccessible = true
        m.invoke(manager, "How did we do?", surveyId)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun latestDialog(): AlertDialog {
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertNotNull("no feedback form was presented at all", dialog)
        return dialog!!
    }

    @Test
    fun `cancelling the form records it as the user's own dismissal`() {
        val (manager, events) = managerWithCapture()
        Robolectric.buildActivity(Activity::class.java).setup()

        present(manager, "survey_abc")
        latestDialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        val dismissed = events().filter { it.first == "feedback_form_dismissed" }
        assertEquals("exactly one dismissal must be recorded", 1, dismissed.size)
        assertEquals("user", dismissed.single().second["reason"])
    }

    /**
     * 🔴 The path this test exists for. Before the fix the dialog went and NOTHING was recorded —
     * a form the user never dismissed, counted as if it had never been shown.
     */
    @Test
    fun `a form taken away with its activity is recorded as presenter_released`() {
        val (manager, events) = managerWithCapture()
        val controller = Robolectric.buildActivity(Activity::class.java).setup()

        present(manager, "survey_abc")
        assertTrue("the form must be on screen before the activity goes", latestDialog().isShowing)

        controller.destroy()
        shadowOf(Looper.getMainLooper()).idle()

        val dismissed = events().filter { it.first == "feedback_form_dismissed" }
        assertEquals("exactly one dismissal must be recorded", 1, dismissed.size)
        assertEquals(
            "the host took the form away; calling that a user dismissal is the under-count",
            "presenter_released", dismissed.single().second["reason"],
        )
    }

    @Test
    fun `a back press or an outside tap is recorded too`() {
        val (manager, events) = managerWithCapture()
        Robolectric.buildActivity(Activity::class.java).setup()

        present(manager, "survey_abc")
        // What `onBackPressed` and a tap outside a cancelable dialog both reach.
        latestDialog().cancel()
        shadowOf(Looper.getMainLooper()).idle()

        val dismissed = events().filter { it.first == "feedback_form_dismissed" }
        assertEquals("a cancelled dialog recorded nothing before this", 1, dismissed.size)
        assertEquals("user", dismissed.single().second["reason"])
    }

    /**
     * Submitting ENDS the form, and the dismiss listener must not also call it a dismissal —
     * otherwise every submission would be followed by a phantom dismissal.
     *
     * 🔴 And `survey_id` is asserted here because the catalog declares it REQUIRED on
     * `survey_feedback_submitted` (`src/modules/feedback-loop/events.ts`) and the SDK sent only
     * `feedback`: a funnel grouped by survey came back empty for every Android respondent who
     * typed something, with a query that looked right.
     */
    @Test
    fun `submitting carries the survey id and is not also a dismissal`() {
        val (manager, events) = managerWithCapture()
        Robolectric.buildActivity(Activity::class.java).setup()

        present(manager, "survey_abc")
        latestDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        val submitted = events().filter { it.first == "survey_feedback_submitted" }
        assertEquals(1, submitted.size)
        assertEquals(
            "a required property that never arrives is worse than an absent one",
            "survey_abc", submitted.single().second["survey_id"],
        )
        assertEquals("the canonical event must go too", 1, events().count { it.first == "feedback_form_submitted" })
        assertEquals(
            "submitting is not dismissing",
            0, events().count { it.first == "feedback_form_dismissed" },
        )
    }
}
