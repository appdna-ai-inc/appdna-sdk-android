package ai.appdna.sdk.onboarding

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SPEC-496 §5b C5.5 / C10 "Native unit tests" — the pure [InteractionCoordinator] seam that production
 * (`BlockBasedStepView`) and the fixture driver share.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InteractionCoordinatorTest {

    /** Snapshot state written here has no Compose host to flush it; a later Compose test would never idle. */
    @org.junit.After
    fun flushSnapshotWrites() = androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()


    private class Harness(scope: TestScope, current: () -> Boolean = { true }, handler: Boolean = true) {
        var seq = 0L
        val logs = mutableListOf<String>()
        val calls = mutableListOf<String>()
        var next = CompletableDeferred<ElementInteractionResult?>()
        val replies = mutableListOf<Pair<ElementInteractionResult, Long>>()
        val c = InteractionCoordinator(
            stepId = "step_a",
            scope = scope.backgroundScope,
            isCurrentPresentation = current,
            drawSeq = { ++seq },
            hasHandler = { handler },
            call = { stepId, blockId, action, _, _ -> calls += "$stepId/$blockId/$action"; next.await() },
            log = { logs += it },
        )
        fun start(action: String = "refresh", block: String = "show_more", pending: Boolean = false) =
            c.start(block, action, "more", emptyMap(), pending) { r, s -> replies += r to s }
    }

    @Test
    fun `C5_2 — no native delegate, a refresh makes no call, shows no loading state and logs`() = runTest {
        val h = Harness(this, handler = false)
        assertNull(h.start())
        runCurrent()
        assertTrue(h.calls.isEmpty())
        assertNull(h.c.loadingBlockId)
        assertFalse(h.c.inFlight)
        assertEquals(0L, h.seq)
        assertTrue(h.logs.any { it.contains("refresh_step tapped with no onElementInteraction handler") })
    }

    @Test
    fun `a refused start (presentation not current) draws no callSeq`() = runTest {
        val h = Harness(this, current = { false })
        assertNull(h.start())
        assertNull(h.start(action = "otp_entered", block = "otp"))
        runCurrent()
        assertEquals("no callSeq drawn", 0L, h.seq)
        assertTrue(h.calls.isEmpty())
        assertNull(h.c.loadingBlockId)
    }

    @Test
    fun `refresh shows loading on the tapped block, locks every refresh, and a nil reply releases`() = runTest {
        val h = Harness(this)
        assertNotNull(h.start())
        runCurrent()
        assertEquals(listOf("step_a/show_more/refresh"), h.calls)
        assertEquals("show_more", h.c.loadingBlockId)
        assertFalse(h.c.isTappable("show_more_2", pending = false))
        // A second refresh (or any interaction) while one is in flight is refused.
        assertNull(h.start(block = "show_more_2"))
        h.next.complete(null)
        runCurrent()
        assertNull(h.c.loadingBlockId)
        assertTrue(h.c.isTappable("show_more", pending = false))
        assertTrue("nil changes nothing", h.replies.isEmpty())
    }

    @Test
    fun `a refresh is refused while pending, a non-refresh is not`() = runTest {
        val h = Harness(this)
        assertNull(h.start(pending = true))
        assertFalse(h.c.isTappable("show_more", pending = true))
        assertNotNull(h.start(action = "otp_entered", block = "otp", pending = true))
    }

    @Test
    fun `refresh ends at the 8 s deadline and the late reply is dropped`() = runTest {
        val h = Harness(this)
        h.start(); runCurrent()
        advanceTimeBy(7_999); runCurrent()
        assertEquals("show_more", h.c.loadingBlockId)
        advanceTimeBy(1); runCurrent()
        assertNull(h.c.loadingBlockId)
        assertFalse(h.c.inFlight)
        h.next.complete(ElementInteractionResult(advance = true))
        runCurrent()
        assertTrue(h.replies.isEmpty())
        assertTrue("our deadline is not the host's cancellation", h.logs.none { it.contains("ended by cancellation") })
    }

    @Test
    fun `a non-refresh interaction has no SDK deadline`() = runTest {
        val h = Harness(this)
        h.start(action = "otp_entered", block = "otp"); runCurrent()
        advanceTimeBy(60_000); runCurrent()
        assertTrue(h.c.inFlight)
        assertNull("no spinner for a non-refresh", h.c.loadingBlockId)
        h.next.complete(ElementInteractionResult(inputValuePatches = mapOf("x" to 1)))
        runCurrent()
        assertEquals(1, h.replies.size)
        assertEquals(1L, h.replies.single().second) // the callSeq its call drew
    }

    @Test
    fun `the deadline ends a call even when the host ignores cancellation`() = runTest {
        val never = CompletableDeferred<Unit>()
        val logs = mutableListOf<String>()
        val c = InteractionCoordinator(
            stepId = "s", scope = backgroundScope, isCurrentPresentation = { true }, drawSeq = { 1 }, hasHandler = { true },
            call = { _, _, _, _, _ -> withContext(NonCancellable) { never.await() }; null },
            log = { logs += it },
        )
        c.start("b", "refresh", null, emptyMap(), false) { _, _ -> }
        runCurrent()
        advanceTimeBy(8_000); runCurrent()
        assertFalse(c.inFlight)
        never.complete(Unit)
        runCurrent()
        assertTrue("our deadline is not the host's cancellation", logs.none { it.contains("ended by cancellation") })
    }

    @Test
    fun `a throwing host is a nil reply, and the button works again`() = runTest {
        val h = Harness(this)
        h.start(); runCurrent()
        h.next.completeExceptionally(IllegalStateException("boom"))
        runCurrent()
        assertFalse(h.c.inFlight)
        assertTrue(h.replies.isEmpty())
        assertTrue(h.logs.any { it.contains("treated as null") })
        h.next = CompletableDeferred()
        assertNotNull(h.start())
    }

    @Test
    fun `a host that throws CancellationException ends the call and releases the lock (otp)`() = runTest {
        val h = Harness(this)
        h.start(action = "otp_entered", block = "otp"); runCurrent()
        assertTrue(h.c.inFlight)
        // e.g. the host's own `withTimeout` expiring inside onElementInteraction.
        h.next.completeExceptionally(kotlinx.coroutines.CancellationException("x"))
        runCurrent()
        assertFalse("the lock must be released on a host cancellation", h.c.inFlight)
        assertTrue("the host's OWN cancellation is logged", h.logs.any { it.contains("ended by cancellation") })
        assertTrue(h.replies.isEmpty())
        h.next = CompletableDeferred()
        assertNotNull("a second interaction is accepted", h.start(action = "otp_entered", block = "otp"))
    }

    @Test
    fun `a host that throws CancellationException ends a refresh at once, not at the 8 s deadline`() = runTest {
        val h = Harness(this)
        h.start(); runCurrent()
        assertEquals("show_more", h.c.loadingBlockId)
        val t0 = testScheduler.currentTime
        h.next.completeExceptionally(kotlinx.coroutines.CancellationException("x"))
        runCurrent()
        assertEquals("no virtual time passed", t0, testScheduler.currentTime)
        assertNull("the spinner ends immediately", h.c.loadingBlockId)
        assertFalse(h.c.inFlight)
        assertTrue(h.c.isTappable("show_more", pending = false))
        h.next = CompletableDeferred()
        assertNotNull(h.start())
    }

    @Test
    fun `our own scope cancellation still cancels the call`() = runTest {
        val scope = kotlinx.coroutines.CoroutineScope(backgroundScope.coroutineContext + kotlinx.coroutines.Job(backgroundScope.coroutineContext[kotlinx.coroutines.Job]))
        val replies = mutableListOf<ElementInteractionResult>()
        val never = CompletableDeferred<ElementInteractionResult?>()
        val logs = mutableListOf<String>()
        val c = InteractionCoordinator(
            stepId = "s", scope = scope, isCurrentPresentation = { true }, drawSeq = { 1 }, hasHandler = { true },
            call = { _, _, _, _, _ -> never.await() },
            log = { logs += it },
        )
        c.start("otp", "otp_entered", null, emptyMap(), false) { r, _ -> replies += r }
        runCurrent()
        scope.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
        runCurrent()
        assertFalse(c.inFlight)
        never.complete(ElementInteractionResult(advance = true))
        runCurrent()
        assertTrue("a reply after the composition left is never applied", replies.isEmpty())
        // The composition leaving is OUR cancellation, not the host's — it must not be logged as one.
        assertTrue(logs.none { it.contains("ended by cancellation") })
    }

    @Test
    fun `a reply arriving after the presentation ended is dropped whole`() = runTest {
        var current = true
        val h = Harness(this, current = { current })
        h.start(action = "confirmed", block = "hold"); runCurrent()
        current = false
        h.next.complete(ElementInteractionResult(advance = true, dataContext = mapOf("k" to "v")))
        runCurrent()
        assertTrue(h.replies.isEmpty())
        assertFalse(h.c.inFlight)
    }

    @Test
    fun `cached on arrival counts a layer value only under a referenced key`() {
        val step = OnboardingConfigParser.parseStepForTest(
            mapOf(
                "id" to "s", "type" to "content",
                "config" to mapOf("content_blocks" to listOf(mapOf("id" to "t", "type" to "text", "text" to "{{hook_data.recommendations.0.name}}"))),
            ),
        )!!
        val overrides = mutableMapOf<String, StepConfigOverride>()
        val access = HostDataInteractionAccess(overrides, HostDataInteractionStore(), HostDataPresentations())
        assertFalse(access.cachedAtArrival(step))
        access.applyInteractionData("s", mapOf("banner" to "x"), access.nextCallSeq())
        assertFalse("unreferenced key", access.cachedAtArrival(step))
        access.applyInteractionData("s", mapOf("recommendations" to null), access.nextCallSeq())
        assertFalse("a removal marker", access.cachedAtArrival(step))
        access.applyInteractionData("s", mapOf("recommendations" to listOf(mapOf("name" to "E"))), access.nextCallSeq())
        assertTrue(access.cachedAtArrival(step))
    }

    @Test
    fun `presentations latch cached at creation`() {
        val step = OnboardingConfigParser.parseStepForTest(
            mapOf(
                "id" to "s", "type" to "content",
                "config" to mapOf("content_blocks" to listOf(mapOf("id" to "t", "type" to "text", "text" to "{{hook_data.banner}}"))),
            ),
        )!!
        val ps = HostDataPresentations()
        val p = ps.presentationFor(step, 0, hasDelegate = true, hasCachedOverride = false)
        assertTrue(p.pending)
        // A later "cached" answer on the SAME presentation does not end pending (Android latches).
        assertTrue(ps.presentationFor(step, 0, hasDelegate = true, hasCachedOverride = true) === p)
        assertTrue(p.pending)
        assertTrue(ps.presentationAt(0, "s") === p)
        assertNull(ps.presentationAt(3, "s"))
    }
}
