package ai.appdna.sdk.network

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * NO PRODUCTION REQUEST MAY SET `Accept-Encoding` BY HAND.
 *
 * OkHttp's `BridgeInterceptor` adds `Accept-Encoding: gzip` itself and transparently decompresses
 * the response — but only when the application has not set that header. Set it by hand and OkHttp
 * assumes the caller will decode, and returns the body untouched, so every gzip answer reaches the
 * parser as raw bytes. `deflate` is worse: OkHttp cannot decode raw deflate at all.
 *
 * 🔴 THIS EXISTS BECAUSE ONE FILE WAS FIXED AND ANOTHER WAS MISSED. The original fix removed the
 * interceptor from `ApiClient`, and `EventUploadWorker` — which builds its own `okhttp3.Request` —
 * kept `.header("Accept-Encoding", "gzip, deflate")` for another day. A unit test of `ApiClient`
 * could not see it; what found it was grepping the shipped APK's dex for the header STRING, where
 * the interceptor class was absent and the string was still present three times.
 *
 * A grep over source is the right shape for this: the defect is "anyone, anywhere, sets this
 * header", and no per-class test can cover a file nobody has written yet.
 */
class NoHandSetAcceptEncodingTest {

    private fun productionKotlinFiles(): List<File> {
        // Walk up to the module root from the test's working directory.
        var dir = File(".").absoluteFile
        while (dir.parentFile != null && !File(dir, "src/main/kotlin").isDirectory) dir = dir.parentFile
        val main = File(dir, "src/main/kotlin")
        assertTrue("could not locate src/main/kotlin from ${File(".").absolutePath}", main.isDirectory)
        return main.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    @Test
    fun `no production file sets Accept-Encoding on a request`() {
        val offenders = productionKotlinFiles().mapNotNull { f ->
            val hits = f.readLines().withIndex().filter { (_, line) ->
                // The SETTER, not a comment explaining why it is absent.
                val t = line.trim()
                !t.startsWith("*") && !t.startsWith("//") &&
                    Regex("""\.header\s*\(\s*"Accept-Encoding"""").containsMatchIn(line)
            }
            if (hits.isEmpty()) null else "${f.name}: " + hits.joinToString { "line ${it.index + 1}" }
        }
        assertTrue(
            "a request sets Accept-Encoding by hand, which stops OkHttp decompressing (#857):\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun `no production file advertises deflate as an accepted response encoding`() {
        // OkHttp cannot decode raw deflate. Advertising it can only ever produce a body nothing reads.
        val offenders = productionKotlinFiles().mapNotNull { f ->
            val hits = f.readLines().withIndex().filter { (_, line) ->
                val t = line.trim()
                !t.startsWith("*") && !t.startsWith("//") && line.contains("\"gzip, deflate\"")
            }
            if (hits.isEmpty()) null else "${f.name}: " + hits.joinToString { "line ${it.index + 1}" }
        }
        assertTrue("`gzip, deflate` is advertised but undecodable (#857):\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }
}
