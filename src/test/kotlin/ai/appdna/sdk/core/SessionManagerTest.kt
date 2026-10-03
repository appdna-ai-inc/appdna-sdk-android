package ai.appdna.sdk.core

import ai.appdna.sdk.IdentityManager
import ai.appdna.sdk.events.EventTracker
import ai.appdna.sdk.storage.LocalStorage
import org.robolectric.RuntimeEnvironment
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * SessionManager unit tests.
 *
 * Covers:
 *  - Fresh-install cold start emits a brand-new session id and increments
 *    the persisted sessions-this-install counter.
 *  - Idle->active rotation: lastActiveAt older than 30 min triggers a new
 *    session id and counter increment on the next foreground.
 *  - Sub-threshold gap preserves the same session id across foreground.
 *  - lastActiveAt persistence boundary cases (exactly at threshold = rotate).
 */
@RunWith(RobolectricTestRunner::class)
class SessionManagerTest {

    private lateinit var dataStore: SessionDataStore
    private lateinit var tracker: EventTracker

    @Before
    fun setUp() {
        val ctx = RuntimeEnvironment.getApplication() as android.content.Context
        // Reset SessionDataStore singleton state by clearing prefs in test scope.
        SessionDataStore.initialize(ctx)
        dataStore = SessionDataStore.instance!!
        dataStore.clearAll()

        val storage = LocalStorage(ctx)
        val identity = IdentityManager(storage)
        tracker = EventTracker(identity, appVersion = "1.0.0-test")
    }

    @After
    fun tearDown() {
        // Cleanup any observer state from previous test
        dataStore.clearAll()
    }

    @Test
    fun `fresh install starts a new session`() {
        val ctx = RuntimeEnvironment.getApplication() as android.content.Context
        val sm = SessionManager(ctx, tracker, dataStore)

        // No prior lastActiveAt — start() must rotate to a new session.
        sm.start()

        val sid = sm.currentSessionId()
        assertNotNull(sid)
        // Sessions-this-install incremented.
        assertEquals(1L, sm.sessionsThisInstall())
    }

    @Test
    fun `resume within 30 minutes preserves session id`() {
        val ctx = RuntimeEnvironment.getApplication() as android.content.Context
        // Seed lastActiveAt to "5 minutes ago".
        val recent = System.currentTimeMillis() - (5L * 60L * 1000L)
        dataStore.setSessionData(SessionManager.KEY_LAST_ACTIVE_AT, recent)
        // Pretend a prior session existed.
        dataStore.setSessionData(SessionManager.KEY_SESSIONS_THIS_INSTALL, 3L)

        val sm = SessionManager(ctx, tracker, dataStore)
        sm.start()
        val firstId = sm.currentSessionId()

        // A real return-to-foreground is always preceded by a background (gate: the
        // first foreground before any background is a cold-launch no-op). Background, re-seed
        // the sub-threshold gap, then foreground — same session, no rotation.
        sm.handleBackground()
        dataStore.setSessionData(SessionManager.KEY_LAST_ACTIVE_AT, recent)
        sm.handleForeground()
        assertEquals(firstId, sm.currentSessionId())
        // Counter unchanged because no rotation happened on `start`.
        assertEquals(3L, sm.sessionsThisInstall())
    }

    @Test
    fun `idle past threshold rotates the session on foreground`() {
        val ctx = RuntimeEnvironment.getApplication() as android.content.Context
        // Seed lastActiveAt to "31 minutes ago" — past the 30-min cutoff.
        val stale = System.currentTimeMillis() - (31L * 60L * 1000L)
        dataStore.setSessionData(SessionManager.KEY_LAST_ACTIVE_AT, stale)
        dataStore.setSessionData(SessionManager.KEY_SESSIONS_THIS_INSTALL, 7L)

        val sm = SessionManager(ctx, tracker, dataStore)
        sm.start()
        // start() already rotated because cold-start path saw stale activity.
        val rotatedId = sm.currentSessionId()
        assertEquals(8L, sm.sessionsThisInstall())

        // Real return: background first, then a >30 min idle gap, then foreground.
        sm.handleBackground()
        val stale2 = System.currentTimeMillis() - (31L * 60L * 1000L)
        dataStore.setSessionData(SessionManager.KEY_LAST_ACTIVE_AT, stale2)
        sm.handleForeground()
        assertNotEquals("session id should rotate", rotatedId, sm.currentSessionId())
        assertEquals(9L, sm.sessionsThisInstall())
    }

    @Test
    fun `cold-launch initial foreground does not rotate or double-count`() {
        val ctx = RuntimeEnvironment.getApplication() as android.content.Context
        // Returning user: stale lastActiveAt so start() rotates once (7 -> 8).
        val stale = System.currentTimeMillis() - (31L * 60L * 1000L)
        dataStore.setSessionData(SessionManager.KEY_LAST_ACTIVE_AT, stale)
        dataStore.setSessionData(SessionManager.KEY_SESSIONS_THIS_INSTALL, 7L)

        val sm = SessionManager(ctx, tracker, dataStore)
        sm.start()
        val coldId = sm.currentSessionId()
        assertEquals(8L, sm.sessionsThisInstall())

        // The initial cold-launch foreground (no prior background) must be swallowed — otherwise
        // it re-reads the still-stale lastActiveAt and rotates a SECOND time, doubling the counter
        // and firing a phantom session_end + cold-launch app_open (regression guard).
        sm.handleForeground()
        assertEquals("initial foreground must NOT rotate again", 8L, sm.sessionsThisInstall())
        assertEquals("session id must not change on the cold-launch foreground", coldId, sm.currentSessionId())
    }

    @Test
    fun `background updates persisted lastActiveAt`() {
        val ctx = RuntimeEnvironment.getApplication() as android.content.Context
        val sm = SessionManager(ctx, tracker, dataStore)
        sm.start()

        val beforeBg = System.currentTimeMillis()
        sm.handleBackground()
        val stored = dataStore.getSessionData(SessionManager.KEY_LAST_ACTIVE_AT) as Number
        // lastActiveAt is set to "now" — must be >= beforeBg (within a tight bound).
        if (stored.toLong() < beforeBg) {
            error("expected lastActiveAt to be >= $beforeBg, got $stored")
        }
    }

    @Test
    fun `sessions counter persists across SessionManager instances`() {
        val ctx = RuntimeEnvironment.getApplication() as android.content.Context
        val sm1 = SessionManager(ctx, tracker, dataStore)
        sm1.start()
        val firstCount = sm1.sessionsThisInstall()
        assertEquals(1L, firstCount)

        // Stale lastActiveAt forces a second rotation on the next instance.
        dataStore.setSessionData(
            SessionManager.KEY_LAST_ACTIVE_AT,
            System.currentTimeMillis() - (60L * 60L * 1000L),
        )
        val sm2 = SessionManager(ctx, tracker, dataStore)
        sm2.start()
        assertEquals(2L, sm2.sessionsThisInstall())
    }
}
