package ai.appdna.sdk

import ai.appdna.sdk.background.EventUploadWorker
import ai.appdna.sdk.events.EventQueue
import ai.appdna.sdk.events.EventUploadCoordinator
import ai.appdna.sdk.network.ApiClient
import ai.appdna.sdk.network.BASE_URL_OVERRIDE_META_KEY
import ai.appdna.sdk.network.TestHttpServer
import ai.appdna.sdk.storage.EventDatabase
import android.content.Context
import android.content.pm.ApplicationInfo
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities
import java.io.ByteArrayInputStream
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/**
 * What `AppDNA.shutdown()` does with the queued events — against the REAL `configure()` / `shutdown()`,
 * the real WorkManager (work-testing) and a loopback server.
 *
 *  - The final upload attempt holds the process-wide upload claim. The WorkManager upload used to be
 *    scheduled at `shutdown()` and, when it ran during that attempt, answered success without uploading;
 *    it also sent ONE batch per run. With more than one batch queued, the rest waited for the next
 *    `configure()` (pruned after 7 days). Now the worker answers retry when the claim is held, the queue
 *    schedules it when its attempt has finished, and a run sends every batch.
 *  - `shutdown()` closed the event database while that attempt ran on IO; it reopened the file (a
 *    connection nobody closed, once per shutdown — every React Native reload) or failed its removal (the
 *    batch then went again). Now the attempt closes the database when it is done.
 *  - After `shutdown(); configure()` the new queue loads the rows the old queue's attempt is sending; it
 *    used to send them again once that attempt removed them from disk.
 *  - A flush whose request was in flight when `shutdown()` cancelled the queue's scope (here: the threshold
 *    flush `sdk_initialized` starts) delivered its batch but threw on return, so the batch was sent again
 *    by the final attempt. Found by the first run of the hand-off test (100 events sent twice).
 *
 * NEGATIVE CONTROLS (run on the build Mac against patched sources):
 *  - the worker answering success when the claim is held → `aWorkerThatFindsTheClaimHeldRetries` fails;
 *  - the worker scheduled at `shutdown()` again (and no schedule after the attempt) → the hand-off test
 *    fails (events left on disk);
 *  - `AppDNA.shutdown()` closing the database again → the connection test fails (the helper is open);
 *  - no `wasResolved` filter in `EventQueue` → the re-configure test fails (an event id sent twice);
 *  - the upload call cancellable again (no `NonCancellable`) → the hand-off test fails (100 sent twice).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EventShutdownHandoffTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private var server: TestHttpServer? = null
    /** Ingest requests are held until this opens (set per test). */
    @Volatile private var ingestGate: CountDownLatch? = null
    private val ingestArrived = CountDownLatch(1)
    private val sentIds = Collections.synchronizedList(mutableListOf<String>())

    @Before fun setUp() {
        TestHttpServer.settleIoDispatcher()
        EventUploadCoordinator.clearResolvedForTest()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            ctx, Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        // Wi-Fi, so the adaptive batch size is 100 (Robolectric's default network has no capabilities).
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = ShadowNetworkCapabilities.newInstance()
        shadowOf(caps).addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        shadowOf(cm).setNetworkCapabilities(cm.activeNetwork, caps)

        val srv = TestHttpServer { path, _ ->
            when {
                path.startsWith("/api/v1/sdk/bootstrap") -> 200 to JSONObject()
                    .put("orgId", "org_handoff").put("appId", "app_handoff")
                    .put("firestorePath", "orgs/org_handoff/apps/app_handoff").toString()
                path.startsWith("/api/v1/ingest/events") -> {
                    ingestArrived.countDown()
                    ingestGate?.await(60, TimeUnit.SECONDS)
                    200 to "{}"
                }
                else -> 404 to "{}"
            }
        }
        server = srv
        val pkg = shadowOf(ctx.packageManager).getInternalMutablePackageInfo(ctx.packageName)
        pkg.applicationInfo!!.flags = pkg.applicationInfo!!.flags or ApplicationInfo.FLAG_DEBUGGABLE
        pkg.applicationInfo!!.metaData = Bundle().apply { putString(BASE_URL_OVERRIDE_META_KEY, srv.baseUrl) }
        EventDatabase(ctx).apply { clearAll(); close() }
    }

    @After fun tearDown() {
        ingestGate?.countDown()
        AppDNA.shutdown()
        AppDNA.lastShutdownEventQueueForTest?.awaitShutdownFlush(30_000)
        server?.close()
        EventDatabase(ctx).apply { clearAll(); close() }
        EventUploadCoordinator.clearResolvedForTest()
    }

    private fun await(what: String, seconds: Long = 30, cond: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (!cond()) {
            if (System.nanoTime() > deadline) throw AssertionError("timed out waiting for: $what — ${TestHttpServer.workerDiagnostics()}")
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    private fun event(i: Int) = JSONObject()
        .put("event_id", "handoff-$i-${UUID.randomUUID()}")
        .put("event_name", "queued_$i")
        .put("ts_ms", System.currentTimeMillis())

    private fun pendingOnDisk(): Int = EventDatabase(ctx).let { db -> db.count().also { db.close() } }

    /** Every event id the server received, decoded from the deflated bodies. */
    private fun idsReceived(): List<String> = synchronized(server!!.rawBodies) {
        server!!.rawBodies.filter { it.first.startsWith("/api/v1/ingest/events") }.flatMap { (_, raw) ->
            val json = InflaterInputStream(ByteArrayInputStream(raw), Inflater(true)).readBytes().toString(Charsets.UTF_8)
            val batch = JSONObject(json).getJSONArray("batch")
            (0 until batch.length()).map { batch.getJSONObject(it).getString("event_id") }
        }
    }

    private fun configureAndAwaitReady(key: String) {
        val ready = CountDownLatch(1)
        AppDNA.configure(ctx, key, Environment.SANDBOX)
        AppDNA.onReady { ready.countDown() }
        await("the SDK became ready") { ready.count == 0L }
    }

    /** Run every enqueued upload the test WorkManager holds, until nothing is left to run or [until] holds. */
    private fun runUploadWork(until: () -> Boolean) {
        val wm = WorkManager.getInstance(ctx)
        val driver = WorkManagerTestInitHelper.getTestDriver(ctx)!!
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (!until() && System.nanoTime() < deadline) {
            for (info in wm.getWorkInfosForUniqueWork("ai.appdna.sdk.eventUpload").get()) {
                if (info.state == WorkInfo.State.ENQUEUED) {
                    driver.setAllConstraintsMet(info.id)
                    driver.setInitialDelayMet(info.id)
                }
            }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(50)
        }
    }

    @Test fun aWorkerThatFindsTheClaimHeldRetries() {
        val db = EventDatabase(ctx, 10_000, "claim_held_${UUID.randomUUID()}.db")
        try {
            db.insertEvent(event(1).toString())
            assertTrue(EventUploadCoordinator.tryAcquire())
            try {
                assertEquals(
                    "a run that cannot get the upload claim must come back, not succeed with nothing sent",
                    EventUploadWorker.Outcome.RETRY,
                    EventUploadWorker.uploadPending(db, "adn_test_claim", server!!.baseUrl),
                )
            } finally {
                EventUploadCoordinator.release()
            }
            assertEquals("nothing may be sent while another owner holds the claim", 0, server!!.rawBodies.size)
            assertEquals(1, db.count())
            // With the claim free, the same run sends it.
            assertEquals(EventUploadWorker.Outcome.DONE, EventUploadWorker.uploadPending(db, "adn_test_claim", server!!.baseUrl))
            assertEquals(0, db.count())
        } finally {
            db.close()
        }
    }

    @Test fun shutdownWithMoreThanOneBatchQueuedDeliversEveryEvent() {
        // 250 events on disk before configure: the queue loads them, and no flush runs before shutdown
        // (the interval is 30 s; the batch threshold is checked only on `track()`).
        val queued = (1..250).map { event(it) }
        EventDatabase(ctx).apply { insertEvents(queued.map { it.toString() }); close() }
        ingestGate = CountDownLatch(1) // hold the final attempt's request

        configureAndAwaitReady("adn_test_handoff")
        AppDNA.shutdown()
        assertTrue("the final upload attempt never reached the server", ingestArrived.await(30, TimeUnit.SECONDS))

        // While that attempt holds the claim, WorkManager runs whatever is scheduled (the old code scheduled
        // the upload at shutdown(); it ran here, found the claim held and succeeded without sending).
        val wm = WorkManager.getInstance(ctx)
        val driver = WorkManagerTestInitHelper.getTestDriver(ctx)!!
        for (info in wm.getWorkInfosForUniqueWork("ai.appdna.sdk.eventUpload").get()) {
            if (info.state == WorkInfo.State.ENQUEUED) driver.setAllConstraintsMet(info.id)
        }
        Thread.sleep(500)

        ingestGate!!.countDown()
        assertTrue("the final attempt did not finish",
            AppDNA.lastShutdownEventQueueForTest!!.awaitShutdownFlush(30_000))
        runUploadWork { pendingOnDisk() == 0 }

        assertEquals("events left on disk after shutdown — they wait for the next configure()", 0, pendingOnDisk())
        val ids = idsReceived()
        assertTrue("not every queued event reached the server", ids.containsAll(queued.map { it.getString("event_id") }))
        assertEquals("an event was sent twice", ids.size, ids.toSet().size)
    }

    @Test fun repeatedShutdownConfigureCyclesLeaveNoOpenConnection() {
        repeat(4) { cycle ->
            configureAndAwaitReady("adn_test_close_$cycle")
            AppDNA.track("cycle_$cycle")
            AppDNA.shutdown()
            val q = AppDNA.lastShutdownEventQueueForTest!!
            assertTrue("cycle $cycle: the final attempt did not finish", q.awaitShutdownFlush(30_000))
            Thread.sleep(100)
            assertFalse("cycle $cycle: the event database was reopened after shutdown() and never closed",
                isOpen(q.eventDatabaseForTest))
        }
        runUploadWork { pendingOnDisk() == 0 }
        assertEquals("events left on disk", 0, pendingOnDisk())
    }

    @Test fun aReconfiguredQueueDoesNotResendTheBatchTheEndedQueueIsSending() {
        val queued = (1..30).map { event(it) }
        EventDatabase(ctx).apply { insertEvents(queued.map { it.toString() }); close() }
        ingestGate = CountDownLatch(1)

        configureAndAwaitReady("adn_test_reconfigure_a")
        AppDNA.shutdown()
        val old = AppDNA.lastShutdownEventQueueForTest!!
        assertTrue(ingestArrived.await(30, TimeUnit.SECONDS))
        // The next configure's queue loads the same 30 rows while the old attempt is in flight.
        configureAndAwaitReady("adn_test_reconfigure_b")
        ingestGate!!.countDown()
        assertTrue(old.awaitShutdownFlush(30_000))

        AppDNA.flush()
        Thread.sleep(1_500)
        runUploadWork { pendingOnDisk() == 0 }
        val ids = idsReceived().filter { id -> queued.any { it.getString("event_id") == id } }
        assertEquals("every queued event sent once", queued.size, ids.toSet().size)
        assertEquals("the new queue re-sent the batch the old one delivered", ids.toSet().size, ids.size)
    }

    /**
     * The failure pause holds for the WorkManager uploader. Backgrounding a paused queue used to schedule it
     * anyway, and the worker never looked at the pause. Now backgrounding schedules nothing while paused, a
     * worker that runs while paused (scheduled before the pause, or in a fresh process — the pause is
     * persisted in `UploadPauseGate`) uploads nothing and succeeds (no retry storm), and a foreground resumes.
     * NEGATIVE CONTROLS: without the `paused` check in `onAppBackground` work is enqueued; without the gate
     * check in the worker the 3 events reach the server.
     */
    @Test fun aPausedQueueSchedulesNoBackgroundUploadAndAWorkerRunningWhilePausedUploadsNothing() {
        val key = "adn_test_pause_worker"
        val base = server!!.baseUrl
        val db = EventDatabase(ctx)
        val events = (1..3).map { event(it) }
        events.forEach { db.insertEvent(it.toString()) }
        val q = EventQueue(ApiClient(key, base), db, null, 100, Long.MAX_VALUE, ctx,
            object : ai.appdna.sdk.events.BackgroundUploadScheduler {
                override fun scheduleIfNeeded() = EventUploadWorker.scheduleIfNeeded(ctx, key, base, db)
            })
        try {
            q.pauseForTest()
            q.onAppBackground()
            Thread.sleep(1_000)
            val wm = WorkManager.getInstance(ctx)
            assertTrue("backgrounding a paused queue scheduled the background upload",
                wm.getWorkInfosForUniqueWork("ai.appdna.sdk.eventUpload").get().none { it.state == WorkInfo.State.ENQUEUED })

            // A run scheduled before the pause (or in a fresh process) runs now.
            EventUploadWorker.scheduleIfNeeded(ctx, key, base, db)
            val driver = WorkManagerTestInitHelper.getTestDriver(ctx)!!
            val info = wm.getWorkInfosForUniqueWork("ai.appdna.sdk.eventUpload").get().single()
            driver.setAllConstraintsMet(info.id)
            await("the paused run finished") {
                wm.getWorkInfoById(info.id).get()?.state?.isFinished == true
            }
            assertEquals("a run while paused must succeed (no retry storm)", WorkInfo.State.SUCCEEDED, wm.getWorkInfoById(info.id).get()!!.state)
            assertEquals("a background run uploaded while the queue was paused", 0,
                server!!.rawBodies.count { it.first.startsWith("/api/v1/ingest/events") })
            assertEquals(3, db.count())

            // The next foreground clears the pause and uploads.
            q.onAppForeground()
            await("the foreground flush uploaded") { db.count() == 0 }
        } finally {
            q.shutdown()
            q.awaitShutdownFlush(10_000)
        }
    }

    private fun isOpen(helper: SQLiteOpenHelper): Boolean {
        val f = SQLiteOpenHelper::class.java.getDeclaredField("mDatabase")
        f.isAccessible = true
        val db = f.get(helper) as SQLiteDatabase? ?: return false
        return db.isOpen
    }
}
