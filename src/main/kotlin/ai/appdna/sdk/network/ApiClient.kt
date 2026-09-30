package ai.appdna.sdk.network

import ai.appdna.sdk.AppDNA
import ai.appdna.sdk.Environment
import ai.appdna.sdk.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

/**
 * HTTP client for SDK API communication with retry and backoff.
 * SPEC-067: Supports deflate compression for request bodies and Accept-Encoding for responses.
 * SPEC-070-A A.1: Uses x-api-key header to match iOS APIClient.
 * SPEC-070-A A.15: Switched compression from gzip to deflate to match iOS Network/APIClient.swift:143.
 * SPEC-070-A H.1/H.2/H.13/H.15: 401 differentiation + UA/x-sdk-version headers + typed-body decode +
 * callTimeout / 30s connectTimeout to match iOS resource caps.
 */
internal class ApiClient(
    private val apiKey: String,
    /**
     * SPEC-497 §3.11 — every request URL is built from this. Production passes the resolved base
     * (the test-only override, else `environment.baseUrl`).
     */
    internal val baseUrl: String,
) {
    /** Existing callers (and tests) that pass an [Environment] keep compiling unchanged. */
    constructor(apiKey: String, environment: Environment) : this(apiKey, environment.baseUrl)

    // SPEC-070-A H.15: bump connectTimeout to 30s (iOS default) and add a wall-clock
    // callTimeout so a stalled write/read can never hold the queue indefinitely.
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        // SPEC-067: Add Accept-Encoding: gzip for response decompression (OkHttp handles this automatically)
        .addInterceptor(AcceptEncodingInterceptor())
        // SPEC-070-A H.2: stamp every outgoing request with a stable
        // User-Agent + x-sdk-version header so the backend can attribute
        // traffic and roll out version gates without parsing User-Agent.
        .addInterceptor(SdkIdentityInterceptor())
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val retryDelays = longArrayOf(1000L, 2000L, 4000L)

    // SPEC-070-A G.2: long-lived scope for fire-and-forget posts so we don't
    // create a fresh CoroutineScope per call (which would leak on cancellation).
    private val fireAndForgetScope = kotlinx.coroutines.CoroutineScope(
        Dispatchers.IO + kotlinx.coroutines.SupervisorJob()
    )

    /**
     * POST a JSON body to the given path with retry.
     */
    suspend fun post(path: String, body: String): String? {
        return withContext(Dispatchers.IO) {
            var lastError: Exception? = null
            for (attempt in 0..retryDelays.size) {
                try {
                    val request = Request.Builder()
                        .url("$baseUrl$path")
                        .header("x-api-key", apiKey)
                        .header("Content-Type", "application/json")
                        .post(body.toRequestBody(jsonMediaType))
                        .build()

                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            return@withContext response.body?.string()
                        }
                        // SPEC-070-A H.1: 401 means the API key is invalid or
                        // the SDK is suspended (SPEC-322). Retrying won't fix
                        // it; surface a loud error and break out of the retry
                        // loop. SPEC-070-A H.13: decode the typed error body
                        // ({error_code, message}) for richer logging.
                        if (response.code == 401) {
                            val (errCode, errMsg) = decodeErrorBody(response)
                            Log.error(
                                "API auth failed (HTTP 401): Invalid API key or SDK suspended. " +
                                    "path=$path code=${errCode ?: "n/a"} msg=${errMsg ?: "n/a"}"
                            )
                            return@withContext null
                        }
                        // SPEC-070-A H.13: log structured 4xx errors so callers
                        // see the backend's reason string instead of a generic
                        // status code.
                        if (response.code in 400..499) {
                            val (errCode, errMsg) = decodeErrorBody(response)
                            Log.warning(
                                "API request failed (${response.code}): $path " +
                                    "code=${errCode ?: "n/a"} msg=${errMsg ?: "n/a"}"
                            )
                            // Round-18 — a 4xx is a PERMANENT client error (bad request / rejected receipt
                            // / not-found / unprocessable / rate-limited). Retrying won't help and only
                            // triples load + delays the user-facing failure ~7s. iOS's generic request()
                            // treats ANY 4xx as terminal, and the event-upload disposition table already
                            // drops 4xx permanently — this path was the lone exception, falling through to
                            // the retry loop. Return terminal now, matching iOS.
                            return@withContext null
                        } else {
                            Log.warning("API request failed (${response.code}): $path")
                        }
                    }
                } catch (e: IOException) {
                    lastError = e
                    Log.warning("API request error: ${e.message}")
                }

                if (attempt < retryDelays.size) {
                    delay(retryDelays[attempt])
                }
            }
            Log.error("API request exhausted retries: $path — ${lastError?.message}")
            null
        }
    }

    /**
     * SPEC-497 §13a.2 "Verify result plumbing" (S5-M1) — [post] with the outcome kept: the HTTP status
     * (null = a network error after the retries), the body, and the server's error code. Same retry
     * policy as [post]: network errors and 5xx are retried with [retryDelays]; every 4xx is terminal on
     * the first answer. [post] itself is unchanged for its other callers.
     */
    suspend fun postForResult(path: String, body: String): ApiResult {
        return withContext(Dispatchers.IO) {
            var lastStatus: Int? = null
            var lastBody: String? = null
            for (attempt in 0..retryDelays.size) {
                try {
                    val request = Request.Builder()
                        .url("$baseUrl$path")
                        .header("x-api-key", apiKey)
                        .header("Content-Type", "application/json")
                        .post(body.toRequestBody(jsonMediaType))
                        .build()
                    client.newCall(request).execute().use { response ->
                        val raw = response.body?.string()
                        if (response.isSuccessful) {
                            return@withContext ApiResult(response.code, raw, null)
                        }
                        val code = raw?.let { decodeErrorCode(it) }
                        if (response.code in 400..499) {
                            Log.warning("API request failed (${response.code}): $path code=${code ?: "n/a"}")
                            return@withContext ApiResult(response.code, raw, code)
                        }
                        Log.warning("API request failed (${response.code}): $path")
                        lastStatus = response.code
                        lastBody = raw
                    }
                } catch (e: IOException) {
                    Log.warning("API request error: ${e.message}")
                    lastStatus = null
                    lastBody = null
                }
                if (attempt < retryDelays.size) delay(retryDelays[attempt])
            }
            ApiResult(lastStatus, lastBody, lastBody?.let { decodeErrorCode(it) })
        }
    }

    /**
     * SPEC-067: POST a JSON body with gzip compression for event ingestion.
     * Returns the response body string, or null on failure.
     */
    suspend fun postCompressed(path: String, body: String): String? {
        return withContext(Dispatchers.IO) {
            var lastError: Exception? = null
            for (attempt in 0..retryDelays.size) {
                try {
                    val compressed = deflateCompress(body.toByteArray(Charsets.UTF_8))
                    val originalSize = body.toByteArray(Charsets.UTF_8).size
                    Log.debug("Compressed events: $originalSize → ${compressed.size} bytes (${String.format("%.1f", originalSize.toDouble() / compressed.size.coerceAtLeast(1))}x)")

                    val request = Request.Builder()
                        .url("$baseUrl$path")
                        .header("x-api-key", apiKey)
                        .header("Content-Type", "application/json")
                        .header("Content-Encoding", "deflate")
                        .post(compressed.toRequestBody("application/json".toMediaType()))
                        .build()

                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            return@withContext response.body?.string()
                        }
                        if (response.code == 401) {
                            val (errCode, errMsg) = decodeErrorBody(response)
                            Log.error(
                                "API auth failed (HTTP 401, compressed): Invalid API key or SDK suspended. " +
                                    "path=$path code=${errCode ?: "n/a"} msg=${errMsg ?: "n/a"}"
                            )
                            return@withContext null
                        }
                        Log.warning("API compressed request failed (${response.code}): $path")
                    }
                } catch (e: IOException) {
                    lastError = e
                    Log.warning("API compressed request error: ${e.message}")
                }

                if (attempt < retryDelays.size) {
                    delay(retryDelays[attempt])
                }
            }
            Log.error("API compressed request exhausted retries: $path — ${lastError?.message}")
            null
        }
    }

    /**
     * SPEC-070-A G.2: Fire-and-forget POST. Used for the identify alias call,
     * where we don't want a transient backend hiccup to delay the synchronous
     * SDK API. Behaves identically to [post] except the network work is launched
     * on a background scope and the caller never blocks. Transient/network
     * failures are retried internally exactly like [post].
     *
     * Returns immediately; logs the outcome at DEBUG.
     */
    fun postFireAndForget(path: String, body: String) {
        fireAndForgetScope.launch {
            val result = post(path, body)
            if (result == null) {
                Log.debug { "Fire-and-forget POST $path eventually failed (no body)" }
            } else {
                Log.debug { "Fire-and-forget POST $path completed" }
            }
        }
    }

    /**
     * GET a JSON response from the given path.
     */
    suspend fun get(path: String): JSONObject? {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("$baseUrl$path")
                    .header("x-api-key", apiKey)
                    .get()
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        return@withContext response.body?.string()?.let { JSONObject(it) }
                    }
                    if (response.code == 401) {
                        val (errCode, errMsg) = decodeErrorBody(response)
                        Log.error(
                            "API auth failed (HTTP 401, GET): Invalid API key or SDK suspended. " +
                                "path=$path code=${errCode ?: "n/a"} msg=${errMsg ?: "n/a"}"
                        )
                        return@withContext null
                    }
                    Log.warning("GET failed (${response.code}): $path")
                    null
                }
            } catch (e: IOException) {
                Log.error("GET error: ${e.message}")
                null
            }
        }
    }

    /**
     * Round-10 #14 — GET with bounded retry on TRANSIENT failures only (5xx + network), used for the
     * cold-start bootstrap. iOS retries the `.bootstrap` request 3× with 1/2/4s backoff
     * (Network/APIClient.swift); the plain [get] made a single attempt and immediately degraded to
     * cached config on any blip, stranding Android on stale config for the whole session. A 401/4xx is
     * permanent — returned immediately without retry (retrying an auth/permanent error is pointless).
     */
    suspend fun getWithRetry(path: String, maxAttempts: Int = 3): JSONObject? {
        return withContext(Dispatchers.IO) {
            var attempt = 0
            while (true) {
                attempt++
                try {
                    val request = Request.Builder()
                        .url("$baseUrl$path")
                        .header("x-api-key", apiKey)
                        .get()
                        .build()

                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            return@withContext response.body?.string()?.let { JSONObject(it) }
                        }
                        if (response.code == 401) {
                            val (errCode, errMsg) = decodeErrorBody(response)
                            Log.error(
                                "API auth failed (HTTP 401, GET): Invalid API key or SDK suspended. " +
                                    "path=$path code=${errCode ?: "n/a"} msg=${errMsg ?: "n/a"}"
                            )
                            return@withContext null
                        }
                        // Only 5xx is transient; other 4xx are permanent. Give up once attempts exhausted.
                        if (response.code < 500 || attempt >= maxAttempts) {
                            Log.warning("GET failed (${response.code}): $path")
                            return@withContext null
                        }
                        Log.warning("GET transient (${response.code}), retry $attempt/$maxAttempts: $path")
                    }
                } catch (e: IOException) {
                    if (attempt >= maxAttempts) {
                        Log.error("GET error (final attempt $attempt): ${e.message}")
                        return@withContext null
                    }
                    Log.warning("GET network error, retry $attempt/$maxAttempts: ${e.message}")
                }
                // Exponential backoff: 1s, 2s, 4s (mirrors iOS bootstrap policy).
                delay(1000L * (1L shl (attempt - 1)))
            }
            @Suppress("UNREACHABLE_CODE")
            null
        }
    }

    /**
     * SPEC-070-A A.16: Single-attempt event-batch upload with structured result.
     *
     * Unlike [postCompressed] (which performs its own internal retry loop), this method
     * makes ONE network attempt and returns an [EventUploadResult] so the caller
     * (EventQueue) can apply the SPEC-070-A retry policy:
     *   - 2xx → Success
     *   - 4xx (except 429) → ClientError (drop the batch — retrying won't help)
     *   - 5xx + 429 + network error → TransientFailure (retry with backoff)
     *
     * Mirrors the differentiation in iOS `APIClient.sendEvents` (Network/APIClient.swift:159).
     */
    suspend fun postEventsBatch(path: String, body: String): EventUploadResult {
        return withContext(Dispatchers.IO) {
            try {
                val compressed = deflateCompress(body.toByteArray(Charsets.UTF_8))
                val originalSize = body.toByteArray(Charsets.UTF_8).size
                Log.debug("Compressed events: $originalSize → ${compressed.size} bytes")

                val request = Request.Builder()
                    .url("$baseUrl$path")
                    .header("x-api-key", apiKey)
                    .header("Content-Type", "application/json")
                    .header("Content-Encoding", "deflate")
                    .post(compressed.toRequestBody("application/json".toMediaType()))
                    .build()

                client.newCall(request).execute().use { response ->
                    val code = response.code
                    // SPEC-070-B AC-35: the CLASSIFICATION is [dispositionFor], a pure function the
                    // shared `permanent_4xx_dropped` fixture asserts on both platforms. It used to
                    // live only in the branches of this `when` — reachable exclusively through a real
                    // OkHttp round trip, so no test could ask whether a 429 drops a batch. On iOS the
                    // equivalent code said "yes" for months and nothing caught it.
                    when (dispositionFor(code)) {
                        EventUploadDisposition.SUCCESS -> EventUploadResult.Success

                        EventUploadDisposition.DROP_PERMANENT -> {
                            val (errCode, errMsg) = decodeErrorBody(response)
                            if (code == 401) {
                                // SPEC-070-A H.1: surface invalid API key explicitly
                                // so EventQueue can drop the batch (retrying won't help).
                                Log.error(
                                    "Event upload auth failed (HTTP 401): Invalid API key or SDK suspended. " +
                                        "code=${errCode ?: "n/a"} msg=${errMsg ?: "n/a"}"
                                )
                            } else {
                                Log.error(
                                    "Event upload rejected (HTTP $code): dropping batch — retrying won't help. " +
                                        "code=${errCode ?: "n/a"} msg=${errMsg ?: "n/a"}"
                                )
                            }
                            EventUploadResult.ClientError(code)
                        }

                        EventUploadDisposition.RETRY_TRANSIENT -> {
                            // 429 rate-limited / 408 request-timeout are transient by definition —
                            // never drop the batch. 503 commonly sends a Retry-After. Honor it either
                            // way; a status nobody expected is retried rather than silently dropped.
                            val retryAfter = parseRetryAfter(response.header("Retry-After"))
                            val hint = retryAfter?.let { " Retry-After: ${it}s." } ?: ""
                            if (code in TRANSIENT_STATUS_CODES) {
                                Log.warning("Event upload throttled (HTTP $code):$hint retrying.")
                            } else {
                                Log.warning("Event upload failed (HTTP $code):$hint retrying.")
                            }
                            EventUploadResult.TransientFailure(code, retryAfter)
                        }
                    }
                }
            } catch (e: IOException) {
                Log.warning("Event upload network error: ${e.message}")
                EventUploadResult.TransientFailure(null)
            }
        }
    }

    /**
     * SPEC-070-A H.13: best-effort decode of `{error_code, message}` from a 4xx
     * response. Returns (errorCode, message) when the body parses as JSON;
     * (null, null) otherwise. Always safe to call — never throws.
     */
    /**
     * Parses a `Retry-After` header. RFC 9110 permits either delta-seconds or an
     * HTTP-date. Returns null for absent/unparseable/non-positive values, and caps
     * the result so a hostile or mistaken header cannot park the queue.
     *
     * Mirrors iOS `APIClient.parseRetryAfter`.
     */
    internal fun parseRetryAfter(raw: String?): Long? {
        val v = raw?.trim().orEmpty()
        if (v.isEmpty()) return null
        v.toLongOrNull()?.let { return if (it > 0) it.coerceAtMost(MAX_RETRY_AFTER_SECONDS) else null }
        return try {
            val fmt = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US)
            fmt.timeZone = java.util.TimeZone.getTimeZone("GMT")
            val date = fmt.parse(v) ?: return null
            val delta = (date.time - System.currentTimeMillis()) / 1000
            if (delta > 0) delta.coerceAtMost(MAX_RETRY_AFTER_SECONDS) else null
        } catch (_: java.text.ParseException) {
            null
        }
    }

    private fun decodeErrorBody(response: Response): Pair<String?, String?> {
        return try {
            val raw = response.peekBody(8 * 1024).string()
            if (raw.isBlank()) return null to null
            val obj = JSONObject(raw)
            val code = decodeErrorCode(raw)
            val msg = obj.optString("message", "").ifEmpty { null }
                ?: obj.optJSONObject("error")?.optString("message", "")?.ifEmpty { null }
            code to msg
        } catch (_: Throwable) {
            null to null
        }
    }

    // MARK: SPEC-067 — Deflate Compression (matches iOS COMPRESSION_ZLIB raw deflate, SPEC-070-A A.15)

    companion object {
        /** Upper bound on an honored `Retry-After`, in seconds. Matches iOS. */
        const val MAX_RETRY_AFTER_SECONDS = 120L

        /**
         * HTTP statuses that look like a permanent 4xx but MUST be retried, never latched.
         * Mirrors iOS `APIClient.transientStatusCodes` — the shared `resilience` fixture
         * (rate_limited_429_retried) asserts both against this same set, so the platforms
         * cannot drift on the most common failure under load.
         */
        @JvmStatic
        val TRANSIENT_STATUS_CODES: Set<Int> = setOf(408, 429)

        /**
         * SPEC-070-B AC-35 — the pure classifier the shared `permanent_4xx_dropped` fixture asserts.
         * Mirrors iOS `APIClient.disposition(for:)` exactly, which is the point: the two SDKs
         * disagreed on 429 in production, and nothing could see it because the decision only existed
         * inside a `when` over a live OkHttp `Response`.
         */
        @JvmStatic
        fun dispositionFor(code: Int): EventUploadDisposition = when {
            code in 200..299 -> EventUploadDisposition.SUCCESS
            code in TRANSIENT_STATUS_CODES -> EventUploadDisposition.RETRY_TRANSIENT
            code in 400..499 -> EventUploadDisposition.DROP_PERMANENT
            else -> EventUploadDisposition.RETRY_TRANSIENT
        }

        /**
         * Compress data using raw deflate (no zlib header/checksum) to match the iOS
         * `compression_encode_buffer(... COMPRESSION_ZLIB)` byte stream exactly.
         *
         * Java's `Deflater(level, nowrap=true)` produces RFC 1951 raw deflate output
         * (the same format iOS sends with Content-Encoding: deflate per SPEC-067).
         */
        fun deflateCompress(data: ByteArray): ByteArray {
            val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, /* nowrap = */ true)
            val baos = ByteArrayOutputStream()
            DeflaterOutputStream(baos, deflater).use { it.write(data) }
            deflater.end()
            return baos.toByteArray()
        }
    }

    /**
     * SPEC-067: OkHttp interceptor that adds Accept-Encoding: gzip, deflate to all requests
     * (matching iOS APIClient.swift:195). OkHttp automatically decompresses gzip responses
     * when this header is present; deflate response bodies (rare on AppDNA backend) fall
     * through to caller decoding.
     */
    private class AcceptEncodingInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request().newBuilder()
                .header("Accept-Encoding", "gzip, deflate")
                .build()
            return chain.proceed(request)
        }
    }

    /**
     * SPEC-070-A H.2: stamp every outgoing request with a User-Agent + x-sdk-version
     * header so the backend can attribute traffic by SDK version + platform without
     * parsing User-Agent. Mirrors iOS APIClient header injection.
     */
    private class SdkIdentityInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val sdkVersion = AppDNA.sdkVersion
            val androidRelease = android.os.Build.VERSION.RELEASE ?: "unknown"
            val ua = "AppDNA-Android-SDK/$sdkVersion (Android $androidRelease)"
            val request = chain.request().newBuilder()
                .header("User-Agent", ua)
                .header("x-sdk-version", sdkVersion)
                .header("x-sdk-platform", "android")
                .build()
            return chain.proceed(request)
        }
    }
}

/**
 * SPEC-070-A A.16: Outcome of a single event-batch upload attempt.
 * Lets [EventQueue] apply the differentiated retry policy described in §3.1 P0.
 */
internal sealed class EventUploadResult {
    /** 2xx response — batch is delivered, drop from disk + memory. */
    object Success : EventUploadResult()
    /**
     * 4xx response (excluding 408 and 429) — payload or auth is malformed.
     * Retrying won't help; drop the batch immediately.
     */
    data class ClientError(val statusCode: Int) : EventUploadResult()
    /**
     * 5xx, 429, 408, or network error — server may recover.
     * Retry with exponential backoff up to maxRetries.
     *
     * [retryAfterSeconds] is a server-supplied `Retry-After` hint (already parsed
     * and capped). When present it overrides the local backoff schedule.
     */
    data class TransientFailure(
        val statusCode: Int?,
        val retryAfterSeconds: Long? = null,
    ) : EventUploadResult()
}

/**
 * SPEC-070-B AC-35 — what an event-upload HTTP status MEANS, independent of any network.
 *
 * Mirrors iOS `APIClient.EventUploadDisposition`. It is a separate type from [EventUploadResult]
 * because a result carries the response (status, Retry-After); a disposition is the pure decision
 * ABOUT a status, and only a pure decision can be put in front of a shared fixture. The two SDKs
 * disagreed on 429 for months precisely because the decision existed nowhere except inside a `when`
 * over a live OkHttp `Response`.
 */
internal enum class EventUploadDisposition {
    /** 2xx — the batch landed. Clears iOS's permanent-failure latch. */
    SUCCESS,

    /** Retry with backoff: 408, 429, every 5xx, and anything else unexpected. Never latches. */
    RETRY_TRANSIENT,

    /**
     * A genuine permanent 4xx (400/401/403/404 …). Drop the batch: retrying a bad API key forever
     * would only drain the battery. iOS additionally LATCHES `eventUploadPermanentlyFailed` here;
     * Android pauses via `bumpFailureCounter()`.
     */
    DROP_PERMANENT,
}

/**
 * SPEC-497 §13a.2 — the outcome of a [ApiClient.postForResult] call. `status == null` is a network
 * error after the retries; `errorCode` is the server's error code (see [decodeErrorCode]).
 */
internal data class ApiResult(val status: Int?, val body: String?, val errorCode: String?) {
    val isSuccess: Boolean get() = status != null && status in 200..299
}

/**
 * The server's error code from an error body, in this order: a top-level `error_code` string (kept
 * first so nothing that relied on it changes); `error` when it is a string; `error.code` when `error`
 * is an object — the `{ error: { code, message } }` shape `withErrorHandler` answers an `AppError` with.
 * Pure, so it is unit-testable without a server.
 */
internal fun decodeErrorCode(raw: String): String? = try {
    val obj = JSONObject(raw)
    obj.optString("error_code", "").ifEmpty { null }
        ?: (obj.opt("error") as? String)?.ifEmpty { null }
        ?: obj.optJSONObject("error")?.optString("code", "")?.ifEmpty { null }
} catch (_: Throwable) {
    null
}
