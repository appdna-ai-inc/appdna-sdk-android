package ai.appdna.sdk.core

import ai.appdna.sdk.network.ApiClient
import ai.appdna.sdk.messages.MessageConfigParser
import ai.appdna.sdk.messages.MessageManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The SDK's REAL network path for segment membership, against a REAL AppDNA server: the production `ApiClient` posts to
 * `POST /api/v1/sdk/segments`, `SegmentMembership.refresh` stores the answer, and the message gate reads it. Skipped
 * unless `APPDNA_LIVE_SEGMENTS_URL` (a local production build) and `APPDNA_LIVE_SEGMENTS_KEY` (its SDK key) are set,
 * with a synthetic walk app seeded: user `walk-pro` (plan pro), `walk-free` (plan free), segment `setup:pro-plan`.
 */
@RunWith(RobolectricTestRunner::class)
class SegmentMembershipLiveServerTest {
    private val url = System.getenv("APPDNA_LIVE_SEGMENTS_URL")
    private val key = System.getenv("APPDNA_LIVE_SEGMENTS_KEY")

    private val message = MessageConfigParser.parseMessages(mapOf("messages" to mapOf("m" to mapOf(
        "message_type" to "modal",
        "content" to mapOf("title" to "t", "body" to "b"),
        "trigger_rules" to mapOf("event" to "level_complete"),
        "audience" to mapOf("segments" to listOf("setup:pro-plan")),
    ))))["m"]!!

    private fun askAs(userId: String): SegmentMembership.Answer? = runBlocking {
        SegmentMembership.resetForTest()
        val client = ApiClient(key!!, url!!)
        SegmentMembership.refresh(setOf("setup:pro-plan", "setup:no-such-segment"), "anon-$userId", userId, 60_000L) { path, body ->
            client.postForResult(path, body).let { it.status to it.body }
        }
        SegmentMembership.answer
    }

    @Test fun `the server's answer reaches the gate, a member sees the message and a non-member does not`() {
        assumeTrue("set APPDNA_LIVE_SEGMENTS_URL and APPDNA_LIVE_SEGMENTS_KEY", !url.isNullOrBlank() && !key.isNullOrBlank())
        val pro = askAs("walk-pro")!!
        println("[segments live] walk-pro → $pro")
        assertEquals(setOf("setup:pro-plan"), pro.memberOf)
        assertEquals(setOf("setup:no-such-segment"), pro.unresolved)
        assertTrue(MessageManager.segmentsAllow(message, "u:walk-pro", pro))

        val free = askAs("walk-free")!!
        println("[segments live] walk-free → $free")
        assertTrue(free.memberOf.isEmpty())
        assertFalse(MessageManager.segmentsAllow(message, "u:walk-free", free))
        // the free user's answer never admits the pro user, and vice versa
        assertFalse(MessageManager.segmentsAllow(message, "u:walk-pro", free))
    }
}
