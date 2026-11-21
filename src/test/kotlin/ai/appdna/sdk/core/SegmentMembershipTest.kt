package ai.appdna.sdk.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The membership answer's network and storage half: what the SDK sends to `POST /api/v1/sdk/segments`, how it keeps
 * the answer (for one user, for the ids it asked about), that it survives a relaunch, and that it does not ask again
 * while a fresh answer covers the ids. The gate itself is the shared fixture `message_audience_segments`.
 */
@RunWith(RobolectricTestRunner::class)
class SegmentMembershipTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val sent = mutableListOf<Pair<String, JSONObject>>()
    private var reply: Pair<Int?, String?> = 200 to """{"member_of":["setup:seg-a"],"unresolved":["setup:gone"],"evaluated_at":"2026-11-21T10:00:00Z"}"""
    private val post: suspend (String, String) -> Pair<Int?, String?> = { path, body -> sent += path to JSONObject(body); reply }

    @Before fun setUp() {
        ctx.getSharedPreferences("ai.appdna.segments", Context.MODE_PRIVATE).edit().clear().commit()
        SegmentMembership.resetForTest()
        SegmentMembership.attach(ctx)
    }

    @After fun tearDown() = SegmentMembership.resetForTest()

    @Test fun `asks for the ids with both identities and keeps the answer for that user`() = runBlocking {
        SegmentMembership.refresh(setOf("setup:seg-a", "setup:gone", " "), "anon-1", "user-1", 60_000L, post)
        assertEquals(1, sent.size)
        val (path, body) = sent.single()
        assertEquals("/api/v1/sdk/segments", path)
        assertEquals("anon-1", body.getString("anon_id"))
        assertEquals("user-1", body.getString("user_id"))
        assertEquals(listOf("setup:gone", "setup:seg-a"), (0 until body.getJSONArray("segment_ids").length()).map { body.getJSONArray("segment_ids").getString(it) })
        val a = SegmentMembership.answer!!
        assertEquals("u:user-1", a.userKey)
        assertEquals(setOf("setup:seg-a"), a.memberOf)
        assertEquals(setOf("setup:gone"), a.unresolved)
    }

    @Test fun `an anonymous device sends a null user id and is keyed by its anon id`() = runBlocking {
        SegmentMembership.refresh(setOf("setup:seg-a"), "anon-1", null, 60_000L, post)
        assertTrue(sent.single().second.isNull("user_id"))
        assertEquals("a:anon-1", SegmentMembership.answer!!.userKey)
    }

    @Test fun `does not ask again while a fresh answer covers the ids, and asks for a new id or a new user`() = runBlocking {
        SegmentMembership.refresh(setOf("setup:seg-a"), "anon-1", "user-1", 60_000L, post)
        SegmentMembership.refresh(setOf("setup:seg-a"), "anon-1", "user-1", 60_000L, post)
        assertEquals(1, sent.size)
        SegmentMembership.refresh(setOf("setup:seg-a", "audience:aud-b"), "anon-1", "user-1", 60_000L, post)
        assertEquals(2, sent.size)
        SegmentMembership.refresh(setOf("setup:seg-a"), "anon-1", "user-2", 60_000L, post)
        assertEquals(3, sent.size)
        assertEquals("u:user-2", SegmentMembership.answer!!.userKey)
    }

    @Test fun `a failed request keeps the previous answer and an unreadable one is not stored`() = runBlocking {
        SegmentMembership.refresh(setOf("setup:seg-a"), "anon-1", "user-1", 0L, post)
        val before = SegmentMembership.answer
        reply = 503 to null
        SegmentMembership.refresh(setOf("setup:seg-a"), "anon-1", "user-1", 0L, post)
        assertEquals(before, SegmentMembership.answer)
        reply = 200 to "not json"
        SegmentMembership.refresh(setOf("setup:seg-a"), "anon-1", "user-1", 0L, post)
        assertEquals(before, SegmentMembership.answer)
    }

    @Test fun `the answer survives a relaunch, and clear forgets it on disk too`() = runBlocking {
        SegmentMembership.refresh(setOf("setup:seg-a"), "anon-1", "user-1", 60_000L, post)
        val kept = SegmentMembership.answer
        SegmentMembership.resetForTest()
        SegmentMembership.attach(ctx)
        assertEquals(kept, SegmentMembership.answer)
        SegmentMembership.clear()
        SegmentMembership.resetForTest()
        SegmentMembership.attach(ctx)
        assertNull(SegmentMembership.answer)
    }

    @Test fun `an answer only reports ids it was asked about`() {
        val a = SegmentMembership.parseAnswer("""{"member_of":["x","setup:seg-a"],"unresolved":["y"]}""", "u:1", setOf("setup:seg-a"))!!
        assertEquals(setOf("setup:seg-a"), a.memberOf)
        assertTrue(a.unresolved.isEmpty())
        assertNull(SegmentMembership.parseAnswer("""{"unresolved":[]}""", "u:1", setOf("x")))
        assertFalse(SegmentMembership.allows(SegmentAudience(listOf("setup:seg-a")), "u:2", a))
    }
}
