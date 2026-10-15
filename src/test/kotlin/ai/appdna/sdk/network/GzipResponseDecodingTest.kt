package ai.appdna.sdk.network

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #857 — a gzip-encoded answer must reach the JSON parser DECODED.
 *
 * It did not. `ApiClient` installed an interceptor that set `Accept-Encoding: gzip, deflate` on
 * every request, with a comment claiming OkHttp would decompress "automatically". The opposite is
 * true: OkHttp's `BridgeInterceptor` adds `Accept-Encoding: gzip` itself and unwraps the response
 * ONLY when the application has not set that header. Set it by hand and OkHttp hands back the raw
 * bytes, so the body arrives at `JSONObject(...)` starting `0x1f 0x8b` and throws:
 *
 *     [AppDNA][ERROR] GET unreadable answer: Value <0x1f> ...
 *     [AppDNA][WARN]  Bootstrap failed - using cached config
 *
 * Measured against a real compressing CDN, every request, and silent: the SDK degrades to cached
 * config and the host is told nothing it can act on. It never fired against our own backend only
 * because a bootstrap answer is ~450 bytes and nothing in front of it compressed.
 *
 * NEGATIVE CONTROL: restore the interceptor and `decodesAGzipAnswer` fails — `get()` returns null.
 * `doesNotAdvertiseEncodingItself` is the structural half: it fails the moment any code sets the
 * header by hand again, which is what makes the first test's green meaningful rather than lucky.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GzipResponseDecodingTest {
    private var server: TestHttpServer? = null

    @After fun tearDown() { server?.close() }

    @Test fun decodesAGzipAnswer() {
        val s = TestHttpServer(gzipResponses = true) { _, _ -> 200 to """{"ok":true,"n":7}""" }
        server = s
        val client = ApiClient("adn_test_placeholder", s.baseUrl)

        val answer = runBlocking { client.get("/api/v1/sdk/bootstrap") }

        assertNotNull("a gzip answer must parse, not come back as raw bytes", answer)
        assertEquals(true, answer!!.getBoolean("ok"))
        assertEquals(7, answer.getInt("n"))
    }

    @Test fun doesNotAdvertiseEncodingItself() {
        val s = TestHttpServer { _, _ -> 200 to """{"ok":true}""" }
        server = s
        val client = ApiClient("adn_test_placeholder", s.baseUrl)

        runBlocking { client.get("/api/v1/sdk/bootstrap") }

        val sent = s.requestHeaders.first()["accept-encoding"]
        // OkHttp's own header is exactly `gzip`; the removed interceptor sent `gzip, deflate`, and
        // `deflate` was worse than useless — OkHttp cannot decode raw deflate at all, so advertising
        // it invited an encoding the SDK could never read.
        assertNull("the SDK must not set Accept-Encoding by hand — OkHttp stops decompressing when it does",
            sent?.takeIf { it.contains("deflate") })
        assertEquals("gzip", sent)
    }

    @Test fun stillReadsAnUncompressedAnswer() {
        val s = TestHttpServer { _, _ -> 200 to """{"ok":true,"plain":1}""" }
        server = s
        val client = ApiClient("adn_test_placeholder", s.baseUrl)

        val answer = runBlocking { client.get("/api/v1/sdk/bootstrap") }

        assertNotNull(answer)
        assertEquals(1, answer!!.getInt("plain"))
    }
}
