package ai.appdna.sdk.network

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.launch

/**
 * A tiny loopback HTTP/1.1 server for JVM tests (the Android test deps have no MockWebServer).
 *
 * - [respond] answers each request (path, body) with `(status, body)`; returning null STALLS the
 *   request: the connection is accepted and read, and then nothing is ever written back.
 * - [requests] records every request as `(method path, body)`.
 * - [connectionClosedByClient] counts down when a stalled request's connection is closed by the client
 *   (a cancelled OkHttp call closes its socket) — the observable proof that the call was cancelled.
 */
@OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
internal class TestHttpServer(
    /** Extra headers written on every answer, e.g. `Cache-Control` to see whether a client caches. */
    private val responseHeaders: Map<String, String> = emptyMap(),
    /**
     * Gzip every answer and label it `Content-Encoding: gzip`, the way a CDN in front of the
     * backend does. A client that advertises `Accept-Encoding` by hand gets the raw bytes back from
     * OkHttp and chokes; one that leaves the header to OkHttp gets a decompressed body. Nothing but
     * a real compressed round trip can tell those two apart.
     */
    private val gzipResponses: Boolean = false,
    private val respond: (path: String, body: String) -> Pair<Int, String>?,
) : AutoCloseable {
    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    val baseUrl: String = "http://127.0.0.1:${server.localPort}"
    val requests: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())
    /** Every request's headers, lower-cased, so a test can assert what the client advertised. */
    val requestHeaders: MutableList<Map<String, String>> = Collections.synchronizedList(mutableListOf())
    /** Every request's body as sent (`requests` decodes it as UTF-8, which a deflated body does not survive). */
    val rawBodies: MutableList<Pair<String, ByteArray>> = Collections.synchronizedList(mutableListOf())
    val requestSeen = CountDownLatch(1)
    val connectionClosedByClient = CountDownLatch(1)
    private val sockets = Collections.synchronizedList(mutableListOf<Socket>())
    @Volatile private var closed = false

    init {
        thread(isDaemon = true, name = "test-http-accept") {
            while (!closed) {
                val s = try { server.accept() } catch (_: Exception) { break }
                sockets += s
                thread(isDaemon = true, name = "test-http-conn") { serve(s) }
            }
        }
    }

    private fun serve(s: Socket) {
        try {
            val input = BufferedInputStream(s.getInputStream())
            while (!closed) {
                val requestLine = readLine(input) ?: return
                if (requestLine.isEmpty()) continue
                var length = 0
                val headers = mutableMapOf<String, String>()
                while (true) {
                    val h = readLine(input) ?: return
                    if (h.isEmpty()) break
                    val i = h.indexOf(':')
                    if (i > 0) {
                        val name = h.substring(0, i).trim().lowercase()
                        headers[name] = h.substring(i + 1).trim()
                        if (name == "content-length") length = headers[name]!!.toInt()
                    }
                }
                requestHeaders += headers
                val bodyBytes = ByteArray(length)
                var read = 0
                while (read < length) {
                    val n = input.read(bodyBytes, read, length - read)
                    if (n < 0) return
                    read += n
                }
                val parts = requestLine.split(" ")
                val path = parts.getOrElse(1) { "" }
                val body = String(bodyBytes, Charsets.UTF_8)
                rawBodies += path to bodyBytes
                requests += "${parts[0]} $path" to body
                requestSeen.countDown()
                val answer = respond(path, body)
                if (answer == null) {
                    // STALL: never answer; report when the client gives up and closes the socket.
                    while (!closed) {
                        if (input.read() < 0) {
                            connectionClosedByClient.countDown()
                            return
                        }
                    }
                    return
                }
                val raw = answer.second.toByteArray(Charsets.UTF_8)
                val payload = if (gzipResponses) gzip(raw) else raw
                val out = s.getOutputStream()
                out.write(
                    ("HTTP/1.1 ${answer.first} X\r\nContent-Type: application/json\r\n" +
                        (if (gzipResponses) "Content-Encoding: gzip\r\n" else "") +
                        responseHeaders.entries.joinToString("") { "${it.key}: ${it.value}\r\n" } +
                        "Content-Length: ${payload.size}\r\n\r\n").toByteArray(Charsets.UTF_8),
                )
                out.write(payload)
                out.flush()
            }
        } catch (_: Exception) {
            connectionClosedByClient.countDown()
        } finally {
            runCatching { s.close() }
        }
    }

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val c = input.read()
            if (c < 0) return if (buf.size() == 0) null else buf.toString("UTF-8")
            if (c == '\n'.code) return buf.toString("UTF-8").trimEnd('\r')
            buf.write(c)
        }
    }

    fun awaitRequest(seconds: Long = 10): Boolean = requestSeen.await(seconds, TimeUnit.SECONDS)

    companion object {
        /**
         * Wait until `Dispatchers.IO` is responsive again. The Robolectric test JVM is shared by up to
         * 250 tests (`forkEvery`), and earlier classes can leave work on the IO pool. (The worst case —
         * every worker inside Robolectric's native SQLite forever — was `EventQueue`'s auto-flush
         * spinning on an overflowed `flushInterval * 1000`, fixed with this test.) The code under test
         * does its HTTP on that pool, so a real-network test first lets it drain. Returns the ms spent.
         */
        fun settleIoDispatcher(maxSeconds: Long = 60): Long {
            val started = System.nanoTime()
            val deadline = started + TimeUnit.SECONDS.toNanos(maxSeconds)
            var fastRounds = 0
            while (fastRounds < 3 && System.nanoTime() < deadline) {
                val t0 = System.nanoTime()
                val done = CountDownLatch(1)
                kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) { done.countDown() }
                done.await(maxSeconds, TimeUnit.SECONDS)
                fastRounds = if (System.nanoTime() - t0 < TimeUnit.MILLISECONDS.toNanos(50)) fastRounds + 1 else 0
                Thread.sleep(20)
            }
            val ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            if (ms > 1_000) println("TestHttpServer: waited ${ms} ms for Dispatchers.IO to drain earlier tests' work")
            return ms
        }

        /** What the coroutine worker threads are doing — for a failure message when a request never came. */
        fun workerDiagnostics(): String {
            val workers = Thread.getAllStackTraces().filterKeys { it.name.startsWith("DefaultDispatcher-worker") }
            val tops = workers.values.map { st -> st.take(6).joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}" } }
            return "${workers.size} DefaultDispatcher workers; top frames: " +
                tops.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(6).joinToString(" | ") { "${it.value}× ${it.key}" }
        }
    }

    override fun close() {
        closed = true
        runCatching { server.close() }
        synchronized(sockets) { sockets.forEach { runCatching { it.close() } } }
    }
}
