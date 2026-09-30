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
 * next write replaces it. It is now logged and its raw value kept under `<key>.corrupt`.
 * NEGATIVE CONTROL: without `keepCorrupt`, the `.corrupt` key is null and nothing is logged.
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
        assertEquals(raw, storage.getString(PurchaseLedger.KEY_DELIVERIES + PurchaseLedger.CORRUPT_SUFFIX))

        // A later write replaces the live key; the corrupt copy survives it. Logged once per value.
        ledger.write(deliveries = emptyList())
        assertEquals(raw, storage.getString(PurchaseLedger.KEY_DELIVERIES + PurchaseLedger.CORRUPT_SUFFIX))
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
        assertEquals("{not an array", storage.getString(PurchaseLedger.KEY_REPORTED + PurchaseLedger.CORRUPT_SUFFIX))
        assertEquals("[x", storage.getString(PurchaseLedger.KEY_CLASS_CACHE + PurchaseLedger.CORRUPT_SUFFIX))
    }
}
