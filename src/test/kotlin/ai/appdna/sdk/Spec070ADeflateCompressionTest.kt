package ai.appdna.sdk

import ai.appdna.sdk.network.ApiClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/**
 * Verify request body compression switched from gzip to
 * raw deflate (matching iOS APIClient.swift:143 / `compression_encode_buffer
 * (..., COMPRESSION_ZLIB)`). The output must NOT be gzip-framed (no 0x1f 0x8b
 * magic bytes) so the backend's `Content-Encoding: deflate` decoder accepts it.
 */
class Spec070ADeflateCompressionTest {

    @Test
    fun `A_15 deflate output has no gzip magic bytes`() {
        val body = "abcdefghijklmnopqrstuvwxyz0123456789".repeat(20)
        val compressed = ApiClient.deflateCompress(body.toByteArray(Charsets.UTF_8))
        assertTrue(compressed.size >= 2)

        // gzip magic = 0x1f 0x8b. Raw deflate must NOT start with it.
        val first = compressed[0].toInt() and 0xFF
        val second = compressed[1].toInt() and 0xFF
        assertNotEquals(
            "Output starts with 0x1f 0x8b — looks gzipped, but Content-Encoding header says deflate",
            0x1f, first
        )
        // Defensive: also confirm the (gzip member-magic) double-byte didn't sneak in.
        if (first == 0x1f && second == 0x8b) fail("deflate output is gzip-framed")
    }

    @Test
    fun `A_15 deflate roundtrip via Inflater nowrap=true`() {
        val original = "AppDNA SPEC-070-A A.15 deflate parity test 📦"
        val compressed = ApiClient.deflateCompress(original.toByteArray(Charsets.UTF_8))

        val inflater = Inflater(/* nowrap = */ true)
        val decompressed = InflaterInputStream(ByteArrayInputStream(compressed), inflater)
            .use { it.readBytes() }
        assertEquals(original, String(decompressed, Charsets.UTF_8))
    }

    @Test
    fun `A_15 deflate gives reasonable compression on repetitive payloads`() {
        // Deflate (especially on repetitive JSON-like strings) should win meaningfully.
        // We don't claim a specific ratio here; just confirm compression actually happened.
        val body = """{"event_name":"test","key":"value","count":42}""".repeat(100)
        val raw = body.toByteArray(Charsets.UTF_8)
        val compressed = ApiClient.deflateCompress(raw)
        assertTrue(
            "Compressed (${compressed.size} bytes) should be smaller than raw (${raw.size} bytes)",
            compressed.size < raw.size
        )
    }
}
