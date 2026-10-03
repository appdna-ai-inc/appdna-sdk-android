package ai.appdna.sdk.billing

import ai.appdna.sdk.storage.LocalStorage
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * An undecodable ledger value (the delivery queue, the reported set, …) is read as empty — and the
 * next write replaces it. It is now logged and its raw value kept under `<key>.corrupt.<epoch-ms>`
 * (at most three copies). NEGATIVE CONTROL: without `keepCorrupt`, no copy exists and nothing is logged.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PurchaseLedgerCorruptTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private fun captureOut(block: () -> Unit): String {
        val old = System.out
        val buf = java.io.ByteArrayOutputStream()
        System.setOut(java.io.PrintStream(buf, true))
        try { block() } finally { System.setOut(old) }
        return buf.toString()
    }

    @Test fun `an undecodable delivery queue is logged and kept as a corrupt copy`() {
        val storage = LocalStorage(ctx)
        val ledger = PurchaseLedger(storage)
        val raw = "[{\"purchaseToken\":\"tok\", truncated"
        storage.setStringsSync(mapOf(PurchaseLedger.KEY_DELIVERIES to raw))

        var read: List<PendingDelivery>? = null
        val out = captureOut { read = ledger.deliveries() }
        assertEquals(emptyList<PendingDelivery>(), read)
        assertTrue(out, out.contains("[AppDNA][ERROR]") && out.contains(PurchaseLedger.KEY_DELIVERIES))
        assertEquals(raw, copy(storage, ledger, PurchaseLedger.KEY_DELIVERIES))

        // A later write replaces the live key; the corrupt copy survives it. Logged once per value.
        ledger.write(deliveries = emptyList())
        assertEquals(raw, copy(storage, ledger, PurchaseLedger.KEY_DELIVERIES))
        storage.setStringsSync(mapOf(PurchaseLedger.KEY_DELIVERIES to raw))
        val again = captureOut { ledger.deliveries() }
        assertTrue(again, !again.contains("[AppDNA][ERROR]"))
    }

    @Test fun `an undecodable reported set and class cache are kept too`() {
        val storage = LocalStorage(ctx)
        val ledger = PurchaseLedger(storage)
        storage.setStringsSync(mapOf(PurchaseLedger.KEY_REPORTED to "{not an array", PurchaseLedger.KEY_CLASS_CACHE to "[x"))
        assertEquals(emptyList<ReportedToken>(), ledger.reported())
        assertTrue(ledger.classCache().isEmpty())
        assertEquals("{not an array", copy(storage, ledger, PurchaseLedger.KEY_REPORTED))
        assertEquals("[x", copy(storage, ledger, PurchaseLedger.KEY_CLASS_CACHE))
    }

    private fun copy(storage: LocalStorage, ledger: PurchaseLedger, key: String): String? =
        ledger.corruptCopyKeys(key).lastOrNull()?.let { storage.getString(it) }

    /**
     * NEGATIVE CONTROL: one `<key>.corrupt` slot — a second unreadable value overwrote the first. Now each
     * distinct value gets a timestamped copy and the oldest beyond three are removed.
     */
    @Test fun `corrupt copies are timestamped and capped at three`() {
        val storage = LocalStorage(ctx)
        var now = 1_000L
        val ledger = PurchaseLedger(storage, clock = { now })
        for (i in 0 until 5) {
            storage.setStringsSync(mapOf(PurchaseLedger.KEY_DELIVERIES to "bad-$i"))
            ledger.deliveries()
            now += 1_000
        }
        val key = PurchaseLedger.KEY_DELIVERIES + PurchaseLedger.CORRUPT_SUFFIX
        assertEquals(listOf("$key.3000", "$key.4000", "$key.5000"), ledger.corruptCopyKeys(PurchaseLedger.KEY_DELIVERIES))
        assertEquals(listOf("bad-2", "bad-3", "bad-4"), ledger.corruptCopyKeys(PurchaseLedger.KEY_DELIVERIES).map { storage.getString(it) })
        assertEquals(null, storage.getString("$key.1000"))
    }

    /**
     * An older SDK kept ONE `<key>.corrupt` copy. NEGATIVE CONTROL: it was outside the index, so it
     * was never listed (`corruptCopyKeys` missed it) and never pruned — a fourth copy beside the cap of three.
     */
    @Test fun `a legacy single corrupt copy is folded into the index as the oldest and pruned by the cap`() {
        val storage = LocalStorage(ctx)
        var now = 1_000L
        val ledger = PurchaseLedger(storage, clock = { now })
        val legacy = PurchaseLedger.KEY_DELIVERIES + PurchaseLedger.CORRUPT_SUFFIX
        storage.setStringsSync(mapOf(legacy to "legacy"))
        assertEquals("listed before any new copy", listOf(legacy), ledger.corruptCopyKeys(PurchaseLedger.KEY_DELIVERIES))

        storage.setStringsSync(mapOf(PurchaseLedger.KEY_DELIVERIES to "bad-0"))
        ledger.deliveries()
        assertEquals(listOf(legacy, "$legacy.1000"), ledger.corruptCopyKeys(PurchaseLedger.KEY_DELIVERIES))
        assertTrue("the index itself now holds it", storage.getString("$legacy.index")!!.contains("\"$legacy\""))

        for (i in 1 until 3) {
            now += 1_000
            storage.setStringsSync(mapOf(PurchaseLedger.KEY_DELIVERIES to "bad-$i"))
            ledger.deliveries()
        }
        assertEquals(listOf("$legacy.1000", "$legacy.2000", "$legacy.3000"), ledger.corruptCopyKeys(PurchaseLedger.KEY_DELIVERIES))
        assertEquals("the legacy copy is pruned like any oldest copy", null, storage.getString(legacy))
    }
}
