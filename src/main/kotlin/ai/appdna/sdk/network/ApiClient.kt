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
 * Supports deflate compression for request bodies and Accept-Encoding for responses.
 * Uses x-api-key header to match iOS APIClient.
 * Switched compression from gzip to deflate to match iOS Network/APIClient.swift:143.
 * 401 differentiation + UA/x-sdk-version headers + typed-body decode +
 * callTimeout / 30s connectTimeout to match iOS resource caps.
 */
internal class ApiClient(
    private val apiKey: String,
    /**
     * Every request URL is built from this. Production passes the resolved base
     * (the test-only override, else `environment.baseUrl`).
     */
    internal val baseUrl: String,
) {
    /** Existing callers (and tests) that pass an [Environment] keep compiling unchanged. */
    constructor(apiKey: String, environment: Environment) : this(apiKey, environment.baseUrl)

    // Bump connectTimeout to 30s (iOS default) and add a wall-clock
    // callTimeout so a stalled write/read can never hold the queue indefinitely.
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        /*
         * 🔴 NO `Accept-Encoding` INTERCEPTOR HERE, AND THAT IS THE POINT.
         *
         * There used to be one, setting `Accept-Encoding: gzip, deflate` on every request, with a
         * comment saying "OkHttp handles this automatically". That is exactly backwards. OkHttp's
         * `BridgeInterceptor` adds `Accept-Encoding: gzip` itself and transparently decompresses the
         * response — but ONLY when the application has not set the header. Set it by hand and OkHttp
         * assumes the caller will decode, and hands back the raw bytes.
         *
         * So every gzip-encoded answer reached the JSON parser as compressed bytes and failed:
         *
         *     [AppDNA][ERROR] GET unreadable answer: Value <0x1f> ...
         *     [AppDNA][WARN]  Bootstrap failed - using cached config
         *
         * `0x1f` is the first byte of the gzip magic number. It had never fired against our own
         * backend because these payloads are small (a bootstrap answer is ~450 bytes) and nothing in
         * front of them compressed. Measured through a compressing CDN it fails every time, and the
         * failure is SILENT — the SDK degrades to cached config and the host is told nothing useful.
         *
         * `deflate` was worse than useless: OkHttp cannot decode raw deflate at all, so advertising
         * it invited an encoding the SDK could never read.
         *
         * Leave this to OkHttp.
         */
        // Stamp every outgoing request with a stable
        // User-Agent + x-sdk-version header so the backend can attribute
        // traffic and roll out version gates without parsing User-Agent.
        .addInterceptor(SdkIdentityInterceptor())
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val retryDelays = longArrayOf(1000L, 2000L, 4000L)

    // Long-lived scope for fire-and-forget posts so we don't
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
                        // 401 means the API key is invalid or
                        // the SDK is suspended. Retrying won't fix
                        // it; surface a loud error and break out of the retry
                        // loop. Decode the typed error body
                        // ({error_code, message}) for richer logging.
                        if (response.code == 401) {
                            val (errCode, errMsg) = decodeErrorBody(response)
                            Log.error(
                                "API auth failed (HTTP 401): Invalid API key or SDK suspended. " +
                                    "path=$path code=${errCode ?: "n/a"} msg=${errMsg ?: "n/a"}"
                            )
                            return@withContext null
                        }
                        // Log structured 4xx errors so callers
                        // see the backend's reason string instead of a generic
                        // status code.
                        if (response.code in 400..499) {
                            val (errCode, errMsg) = decodeErrorBody(response)
                            Log.warning(
                                "API request failed (${response.code}): $path " +
                                    "code=${errCode ?: "n/a"} msg=${errMsg ?: "n/a"}"
                            )
                            // A 4xx is a PERMANENT client error (bad request / rejected receipt
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
     * "Verify result plumbing" (S5-M1) — [post] with the outcome kept: the HTTP status
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
                    // CANCELLABLE: a blocking `execute()` ignores coroutine
                    // cancellation, so `PurchaseCompletion`'s 30 s `withTimeoutOrNull` around a verify
                    // could not end the call and the purchase lock was held for the client's 60 s
                    // `callTimeout` (plus retries). `awaitCancellable` cancels the OkHttp call itself.
                    val (status, raw) = client.newCall(request).awaitCancellable()
                    if (status in 200..299) {
                        return@withContext ApiResult(status, raw, null)
                    }
                    val code = raw?.let { decodeErrorCode(it) }
                    if (status in 400..499) {
                        Log.warning("API request failed ($status): $path code=${code ?: "n/a"}")
                        return@withContext ApiResult(status, raw, code)
                    }
                    Log.warning("API request failed ($status): $path")
                    lastStatus = status
                    lastBody = raw
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
     * Enqueue this call and suspend until OkHttp answers, reading the status and body on OkHttp's
     * thread. Cancelling the waiting coroutine cancels the call (`invokeOnCancellation`), so a stalled
     * server can no longer outlive the caller's timeout. A failure resumes with its exception (an
     * [IOException] from the call; any [Throwable] from reading the response).
     */
    private suspend fun okhttp3.Call.awaitCancellable(): Pair<Int, String?> =
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { runCatching { cancel() } }
            enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: IOException) {
                    if (cont.isActive) cont.resumeWith(Result.failure(e))
                }

                override fun onResponse(call: okhttp3.Call, response: Response) {
                    // Throwable, not IOException: anything else thrown while
                    // reading the body (a charset / state error, an OOM on a huge body) escaped this
                    // callback into OkHttp's dispatcher and the waiting coroutine never resumed.
                    val read = try {
                        response.use { Result.success(it.code to it.body?.string()) }
                    } catch (e: Throwable) {
                        Result.failure<Pair<Int, String?>>(e)
                    }
                    if (cont.isActive) cont.resumeWith(read)
                }
            })
        }

    /**
     * POST a JSON body with gzip compression for event ingestion.
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
     * Fire-and-forget POST. Used for the identify alias call,
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
     * GET with bounded retry on TRANSIENT failures only (5xx + network), used for the
     * cold-start bootstrap. iOS retries the `.bootstrap` request 3× with 1/2/4s backoff
     * (Network/APIClient.swift); the plain [get] made a single attempt and immediately degraded to
     * cached config on any blip, stranding Android on stale config for the whole session. A 401/4xx is
     * permanent — returned immediately without retry (retrying an auth/permanent error is pointless).
     */
    suspend fun getWithRetry(path: String, maxAttempts: Int = 3): JSONObject? =
        (getWithRetryResult(path, maxAttempts) as? GetResult.Ok)?.json

    /** What [getWithRetryResult] came to: the 2xx answer (null when its body is empty), or the failure. */
    sealed class GetResult {
        data class Ok(val json: JSONObject?) : GetResult()
        /** [status] null: no HTTP answer (network error). [retryAfterSeconds]: a 429's parsed `Retry-After`. */
        data class Failed(val status: Int?, val retryAfterSeconds: Long? = null) : GetResult()
    }

    /**
     * [getWithRetry] with the failure kept: its HTTP status (a 401 / 403 ends the bootstrap retries) and a
     * 429's `Retry-After` (which holds the next retry back). Same retry rules: 5xx and network errors are
     * retried here, any 4xx is returned at once.
     */
    suspend fun getWithRetryResult(path: String, maxAttempts: Int = 3): GetResult {
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
                            return@withContext GetResult.Ok(response.body?.string()?.let { JSONObject(it) })
                        }
                        if (response.code == 401) {
                            val (errCode, errMsg) = decodeErrorBody(response)
                            Log.error(
                                "API auth failed (HTTP 401, GET): Invalid API key or SDK suspended. " +
                                    "path=$path code=${errCode ?: "n/a"} msg=${errMsg ?: "n/a"}"
                            )
                            return@withContext GetResult.Failed(401)
                        }
                        // Only 5xx is transient; other 4xx are permanent. Give up once attempts exhausted.
                        if (response.code < 500 || attempt >= maxAttempts) {
                            Log.warning("GET failed (${response.code}): $path")
                            val retryAfter = if (response.code == 429) parseRetryAfter(response.header("Retry-After")) else null
                            return@withContext GetResult.Failed(response.code, retryAfter)
                        }
                        Log.warning("GET transient (${response.code}), retry $attempt/$maxAttempts: $path")
                    }
                } catch (e: IOException) {
                    if (attempt >= maxAttempts) {
                        Log.error("GET error (final attempt $attempt): ${e.message}")
                        return@withContext GetResult.Failed(null)
                    }
                    Log.warning("GET network error, retry $attempt/$maxAttempts: ${e.message}")
                } catch (e: org.json.JSONException) {
                    Log.error("GET unreadable answer: ${e.message}")
                    return@withContext GetResult.Failed(null)
                }
                // Exponential backoff: 1s, 2s, 4s (mirrors iOS bootstrap policy).
                delay((getRetryBaseDelayMsForTest ?: 1000L) * (1L shl (attempt - 1)))
            }
            @Suppress("UNREACHABLE_CODE")
            GetResult.Failed(null)
        }
    }

    /**
     * Single-attempt event-batch upload with structured result.
     *
     * Unlike [postCompressed] (which performs its own internal retry loop), this method
     * makes ONE network attempt and returns an [EventUploadResult] so the caller
     * (EventQueue) can apply the retry policy:
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
                    // The CLASSIFICATION is [dispositionFor], a pure function the
                    // shared `permanent_4xx_dropped` fixture asserts on both platforms. It used to
                    // live only in the branches of this `when` — reachable exclusively through a real
                    // OkHttp round trip, so no test could ask whether a 429 drops a batch. On iOS the
                    // equivalent code said "yes" for months and nothing caught it.
                    when (dispositionFor(code)) {
                        EventUploadDisposition.SUCCESS -> EventUploadResult.Success

                        EventUploadDisposition.DROP_PERMANENT -> {
                            val (errCode, errMsg) = decodeErrorBody(response)
                            if (code == 401) {
                                // Surface invalid API key explicitly
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
     * Best-effort decode of `{error_code, message}` from a 4xx
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

    // MARK: Deflate Compression (matches iOS COMPRESSION_ZLIB raw deflate)

    companion object {
        /** Test seam: the base of [getWithRetryResult]'s 1 s / 2 s / 4 s backoff (null in production). */
        @Volatile internal var getRetryBaseDelayMsForTest: Long? = null

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
         * The pure classifier the shared `permanent_4xx_dropped` fixture asserts.
         * Mirrors iOS `APIClient.disposition(for:)` exactly, which is the point: the two SDKs
         * disagreed on 429 in production, and nothing could see it because the decision only existed
         * inside a `when` over a live OkHttp `Response`.
         */
        /**
         * Whether a permanent rejection with this status pauses every upload path until the next foreground or
         * `AppDNA.flush()`: 401 / 403 — the API key is wrong or revoked, so every later batch would be rejected
         * (and dropped as a loss) the same way. Any other permanent 4xx (400, 404, 413 …) is about the batch: it is
         * dropped and uploads go on. The in-process queue and the WorkManager uploader both apply it; iOS
         * `APIClient.rejectionPausesUploads`, same table (fixture `permanent_4xx_dropped`, `rejection_pauses`).
         */
        @JvmStatic
        fun rejectionPausesUploads(code: Int): Boolean = code == 401 || code == 403

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
         * (the same format iOS sends with Content-Encoding: deflate).
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
     * Stamp every outgoing request with a User-Agent + x-sdk-version
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
 * Outcome of a single event-batch upload attempt.
 * Lets [EventQueue] apply the differentiated retry policy (permanent 4xx dropped, transient retried).
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
 * What an event-upload HTTP status MEANS, independent of any network.
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
 * The outcome of a [ApiClient.postForResult] call. `status == null` is a network
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
