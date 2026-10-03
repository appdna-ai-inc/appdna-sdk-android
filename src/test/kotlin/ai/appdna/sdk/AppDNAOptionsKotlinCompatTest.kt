package ai.appdna.sdk

import ai.appdna.sdk.events.RuntimeSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A Kotlin host written against the released `AppDNAOptions` still compiles: the three runtime settings are
 * non-null (`val x: Long = options.flushInterval`, destructuring into `Long` / `Int`), and `copy()` keeps
 * working. "Not set by the host" is the default value (`requested*`), and resolves exactly like iOS's unset
 * option (shared fixture `runtime_settings_precedence`).
 *
 * NEGATIVE CONTROL: against the round-33 nullable properties this file does not compile (Long? / Int? where
 * Long / Int are required).
 */
class AppDNAOptionsKotlinCompatTest {
    @Test fun theReleasedKotlinCallsStillCompile() {
        val options = AppDNAOptions()
        val flush: Long = options.flushInterval
        val batch: Int = options.batchSize
        val ttl: Long = options.configTTL
        val (f, b, t) = options
        val fl: Long = f; val bi: Int = b; val tl: Long = t
        assertEquals(flush, fl); assertEquals(batch, bi); assertEquals(ttl, tl)
        val copied = options.copy(batchSize = 10)
        assertEquals(10, copied.batchSize)
        assertEquals(10, copied.requestedBatchSize)
        assertNull(copied.requestedFlushInterval)
    }

    /** `copy()` of a default options object leaves the untouched settings unset (the bootstrap still applies). */
    @Test fun aCopyKeepsTheUnsetSettingsUnset() {
        val copied = AppDNAOptions().copy(logLevel = LogLevel.DEBUG)
        assertEquals(RuntimeSettings.Resolved(60L, 7, 900L),
            RuntimeSettings.resolveAll(copied, RuntimeSettings.Bootstrap(60L, 7, 900L)))
    }

    /** A host value equal to the default is the same as none: the bootstrap's value applies (iOS too). */
    @Test fun aValueEqualToTheDefaultReadsAsUnset() {
        val o = AppDNAOptions(flushInterval = 30L, batchSize = 100, configTTL = 3600L)
        assertNull(o.requestedFlushInterval); assertNull(o.requestedBatchSize); assertNull(o.requestedConfigTTL)
        assertEquals(RuntimeSettings.Resolved(60L, 7, 900L),
            RuntimeSettings.resolveAll(o, RuntimeSettings.Bootstrap(60L, 7, 900L)))
        assertEquals(RuntimeSettings.Resolved(12L, 4, 120L),
            RuntimeSettings.resolveAll(AppDNAOptions(flushInterval = 12L, batchSize = 4, configTTL = 120L),
                RuntimeSettings.Bootstrap(60L, 7, 900L)))
    }
}
