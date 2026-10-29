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
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import java.time.Duration
import java.util.Collections

/**
 * An interactive chat step keeps its conversation when the onboarding activity is rebuilt: after a
 * configuration change (a rotation), and after the OS ended the process in the background and Android
 * restored the activity in a new one. The bubbles, the turn count, the quick replies and the text in
 * the composer come back; the welcome messages do not play again.
 *
 * A webhook reply in flight is never lost and never asked for twice: across a rotation the request
 * keeps running and its reply is appended to the conversation on screen; after a process death,
 * which ends the request, the last user message is sent again, once.
 *
 * The activity is real (Robolectric). Only the webhook's HTTP is stubbed ([chatWebhookTransport]);
 * all conversation text is synthetic.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ChatStepKeptTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var events: BillingTestPipeline
    private val controllers = mutableListOf<ActivityController<*>>()

    /** Every request body the webhook received, in order. */
    private val requests: MutableList<JSONObject> = Collections.synchronizedList(mutableListOf())

    /** The replies the stub hands out, one per request in order; a request past the list waits forever. */
    private val replies: MutableList<CompletableDeferred<String>> = Collections.synchronizedList(mutableListOf())

    private val chatStep = mapOf(
        "id" to "s_chat", "type" to "interactive_chat",
        "config" to mapOf(
            "title" to "Chat",
            "chat_config" to mapOf(
                "max_user_turns" to 5,
                "auto_messages" to listOf(mapOf("id" to "am_hello", "turn" to 0, "delay_ms" to 0, "content" to "Hello from the guide")),
                "quick_replies" to listOf(mapOf("id" to "qr_maps", "text" to "Tell me more", "show_at_turn" to 0)),
                "webhook" to mapOf("enabled" to true, "webhook_url" to "https://chat.example.test/hook"),
            ),
        ),
    )

    @Before
    fun setUp() {
        events = BillingTestPipeline(ctx, "chat_step_kept")
        LocalStorage(ctx).setString(
            "cache_onboarding",
            JSONObject(mapOf("active_flow_id" to FLOW, "flows" to mapOf(FLOW to mapOf(
                "id" to FLOW, "name" to "Chat probe", "version" to 1,
                "steps" to listOf(chatStep, mapOf("id" to "s_end", "type" to "welcome", "config" to mapOf("title" to "End"))),
            )))).toString(),
        )
        chatWebhookTransport = ChatWebhookTransport { _, _, body, _ ->
            val index = synchronized(requests) { requests += JSONObject(body); requests.size - 1 }
            val reply = synchronized(replies) {
                while (replies.size <= index) replies += CompletableDeferred()
                replies[index]
            }
            ChatWebhookHttpReply(200, reply.await())
        }
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
        chatWebhookTransport = LiveChatWebhookTransport
        events.close()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** Lets delayed main-thread work (the welcome message's typing pause) run. */
    private fun advance(ms: Long) {
        var left = ms
        while (left > 0) {
            compose.mainClock.advanceTimeBy(100)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            left -= 100
        }
        compose.waitForIdle()
    }

    private fun manager() = OnboardingFlowManager(RemoteConfigManager(null, LocalStorage(ctx), 3600L), events.tracker)

    private fun present(): ActivityController<OnboardingActivity> {
        AppDNA.installOnboardingForTest(manager(), ready = true)
        val host = Robolectric.buildActivity(Activity::class.java).setup().also { controllers += it }.get()
        assertTrue(AppDNA.presentOnboarding(host, FLOW))
        val c = Robolectric.buildActivity(OnboardingActivity::class.java).setup().also { controllers += it }
        idle()
        compose.waitForIdle()
        advance(2_000)
        assertEquals("the welcome message plays once", 1, count("Hello from the guide"))
        return c
    }

    private fun reply(index: Int, text: String) {
        val d = synchronized(replies) {
            while (replies.size <= index) replies += CompletableDeferred()
            replies[index]
        }
        d.complete("""{"messages":[{"content":"$text"}]}""")
    }

    /** Waits for work that finishes off the main thread (the webhook) and lands back on it. */
    private fun waitFor(what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < end) {
            idle(); compose.waitForIdle()
            if (cond()) return
            Thread.sleep(20)
        }
        assertTrue("timed out waiting for $what", cond())
    }

    private fun count(text: String): Int =
        compose.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().size

    private fun composerText(): String =
        compose.onNode(hasSetTextAction()).fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text ?: ""

    private fun send(text: String) {
        compose.onNode(hasSetTextAction()).performTextInput(text)
        compose.waitForIdle()
        compose.onNode(hasContentDescription("Send message")).performClick()
        idle(); compose.waitForIdle()
    }

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
        advance(2_000)
        assertFalse("the restored activity must resume the flow", c.get().isFinishing)
        return c
    }

    /** One finished turn, and a second message half typed in the composer. */
    private fun aConversation(): ActivityController<OnboardingActivity> {
        val c = present()
        reply(0, "Maps are great")
        send("I like maps")
        waitFor("the first reply") { count("Maps are great") == 1 }
        compose.onNode(hasSetTextAction()).performTextInput("And trai")
        compose.waitForIdle()
        return c
    }

    private fun assertTheConversationIsKept(state: String) {
        assertEquals("$state: the welcome message, once", 1, count("Hello from the guide"))
        assertEquals("$state: the user's message", 1, count("I like maps"))
        assertEquals("$state: the reply", 1, count("Maps are great"))
        assertEquals("$state: the half-typed message in the composer", "And trai", composerText())
        assertEquals("$state: one request was sent, no more", 1, requests.size)
    }

    @Test
    fun `a rotation keeps the conversation`() {
        val c = aConversation()
        c.recreate()
        idle(); compose.waitForIdle()
        advance(2_000)
        assertTheConversationIsKept("after a rotation")
    }

    @Test
    fun `a process death keeps the conversation`() {
        val saved = kill(aConversation())
        restoreInNewProcess(saved)
        assertTheConversationIsKept("after a process death")
    }

    @Test
    fun `a reply in flight during a rotation is shown once it arrives, and asked for once`() {
        val c = present()
        send("I like maps")
        assertEquals(1, requests.size)

        c.recreate()
        idle(); compose.waitForIdle()
        advance(2_000)
        assertEquals("the user's message is kept while the reply is in flight", 1, count("I like maps"))

        reply(0, "A late reply")
        waitFor("the late reply") { count("A late reply") == 1 }
        assertEquals("the reply was not asked for again", 1, requests.size)
        assertEquals(1, count("I like maps"))
    }

    @Test
    fun `after a process death with a reply in flight, the last message is sent again once`() {
        val c = present()
        send("I like maps")
        assertEquals(1, requests.size)

        val saved = kill(c)
        reply(1, "An answer after the restart")
        restoreInNewProcess(saved)

        waitFor("the re-sent request's reply") { count("An answer after the restart") == 1 }
        assertEquals("the last message was sent again, once", 2, requests.size)
        val resent = requests[1].getJSONObject("conversation")
        assertEquals("I like maps", resent.getString("user_message"))
        assertEquals("the same turn as the request the process lost", requests[0].getJSONObject("conversation").getInt("turn"), resent.getInt("turn"))
        assertEquals("the user's message is not duplicated", 1, count("I like maps"))
        assertEquals("the welcome message did not play again", 1, count("Hello from the guide"))
    }

    @Test
    fun `a long conversation is kept within a bounded size, newest messages first`() {
        val session = ChatSession("s_chat")
        session.messages = (0 until 200).map { i ->
            ChatMessage(id = "m$i", role = if (i % 2 == 0) ChatRole.USER else ChatRole.AI, content = "x".repeat(1_000) + i)
        }
        session.webhookData["thread"] = "t1"
        session.seeded = true
        val draft = StepDraft("s_chat").apply { chat = session; seeded = true }
        val json = StepDrafts.encode(draft, secrets = emptySet())!!
        assertTrue("the saved conversation stays bounded (${json.length} chars)", json.length < ChatSession.MAX_CHARS + 4_096)

        val back = StepDrafts.decode(json, "s_chat")!!.chat!!
        assertEquals("the newest message is kept", "m199", back.messages.last().id)
        assertTrue("older messages give way", back.messages.size in 2 until 200)
        assertEquals("t1", back.webhookData["thread"])
        assertFalse("nothing was awaited, so nothing is sent again", back.resendOnShow)
    }

    private companion object {
        const val FLOW = "chat_kept_probe"
    }
}
