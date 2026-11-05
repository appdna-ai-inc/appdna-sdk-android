package ai.appdna.sdk.messages

import ai.appdna.sdk.core.AppDNAImageLoader
import android.content.Context
import android.graphics.Bitmap
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.ImageResult
import coil.request.SuccessResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * A generated in-app hero is published as a STABLE console URL that answers with a 302 to a freshly signed
 * storage URL, because the signed URL itself expires after 7 days while a config cached on a device lives longer.
 * The modal and fullscreen views draw `content.image_url` through [ai.appdna.sdk.core.NetworkImage], i.e. the
 * SDK's shared Coil loader — so the hero draws only if THAT loader follows the redirect to the image.
 *
 * A loopback server plays both hops: `/api/v1/public/message-hero/…` → 302 → `/signed/…` → a real PNG. The control
 * is the same loader against a stable URL that answers 404 (what an object no asset wrote gets), which must fail —
 * so a pass is not a harness that reports success for anything.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MessageHeroRedirectTest {

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    private fun png(): ByteArray {
        val bmp = Bitmap.createBitmap(4, 3, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    /** Answers `GET path` with (status, headers, body). Records every request line. */
    private class TwoHopServer(private val answer: (String) -> Triple<Int, Map<String, String>, ByteArray>) : AutoCloseable {
        private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val base = "http://127.0.0.1:${server.localPort}"
        val paths: MutableList<String> = Collections.synchronizedList(mutableListOf())
        init {
            thread(isDaemon = true) {
                while (!server.isClosed) {
                    val s = try { server.accept() } catch (_: Exception) { break }
                    thread(isDaemon = true) {
                        s.use { sock ->
                            val reader = sock.getInputStream().bufferedReader()
                            val line = reader.readLine() ?: return@use
                            while (true) { val h = reader.readLine() ?: break; if (h.isEmpty()) break }
                            val path = line.split(" ")[1]
                            paths += path
                            val (status, headers, body) = answer(path)
                            val head = buildString {
                                append("HTTP/1.1 $status X\r\n")
                                headers.forEach { (k, v) -> append("$k: $v\r\n") }
                                append("Content-Length: ${body.size}\r\nConnection: close\r\n\r\n")
                            }
                            sock.getOutputStream().apply { write(head.toByteArray()); write(body); flush() }
                        }
                    }
                }
            }
        }
        override fun close() = server.close()
    }

    /** Runs the request off the main thread and pumps the paused main looper Coil hops through until it finishes. */
    private fun load(url: String): ImageResult {
        val ctx = context()
        val loader = AppDNAImageLoader.singleton(ctx)
        val request = ImageRequest.Builder(ctx).data(url).allowHardware(false).build()
        val done = CompletableFuture<ImageResult>()
        thread { done.complete(runBlocking { loader.execute(request) }) }
        val deadline = System.currentTimeMillis() + 20_000
        while (!done.isDone && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        return done.get(1, TimeUnit.SECONDS)
    }

    @Test
    fun `the SDK image loader follows the stable hero URL's redirect to the signed image`() {
        val image = png()
        TwoHopServer { path ->
            when {
                path.startsWith("/api/v1/public/message-hero/") ->
                    Triple(302, mapOf("Location" to "/signed/bucket/hero.png?X-Goog-Signature=fresh", "Cache-Control" to "public, max-age=3600"), ByteArray(0))
                path.startsWith("/signed/") -> Triple(200, mapOf("Content-Type" to "image/png"), image)
                else -> Triple(404, emptyMap(), ByteArray(0))
            }
        }.use { server ->
            val result = load("${server.base}/api/v1/public/message-hero/bucket/pending/t/message_hero/2026-10/a-${System.nanoTime()}.png")
            assertTrue("expected a drawn image, got $result", result is SuccessResult)
            assertEquals(2, server.paths.size)
            assertTrue(server.paths[1].startsWith("/signed/bucket/hero.png"))
        }
    }

    @Test
    fun `control - a stable URL that answers 404 draws nothing`() {
        TwoHopServer { Triple(404, emptyMap(), ByteArray(0)) }.use { server ->
            val result = load("${server.base}/api/v1/public/message-hero/bucket/missing-${System.nanoTime()}.png")
            assertTrue("expected an error, got $result", result is ErrorResult)
        }
    }
}
