package dev.lumastream.subtitle

import android.os.SystemClock
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URL
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.HttpsURLConnection

/** No retries of quota-consuming POSTs; API credentials never accompany file-host requests. */
internal class OpenSubtitlesHttp {
    suspend fun json(path: String, apiKey: String, body: JSONObject? = null): JSONObject {
        if (apiKey.isBlank()) throw IOException("Configure the LumaStream OpenSubtitles consumer API key before searching.")
        if (apiKey.length > 512 || apiKey.any { it <= ' ' || it >= '\u007f' }) throw IOException("The OpenSubtitles API key is invalid.")
        val bytes = request(URL("$API$path"), apiKey, body?.toString()?.toByteArray(Charsets.UTF_8))
        val text = bytes.toString(Charsets.UTF_8)
        var depth = 0
        var quoted = false
        var escaped = false
        text.forEachIndexed { index, char ->
            if (index % 16_384 == 0) currentCoroutineContext().ensureActive()
            if (quoted) {
                if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') quoted = false
            } else when (char) {
                '"' -> quoted = true
                '{', '[' -> if (++depth > 64) throw IOException("OpenSubtitles JSON exceeds the nesting safety limit.")
                '}', ']' -> depth--
            }
        }
        return try { JSONObject(text) } catch (_: org.json.JSONException) { throw IOException("OpenSubtitles returned invalid JSON.") }
    }

    suspend fun file(link: String): ByteArray {
        val url = try { URL(link) } catch (_: Exception) { throw IOException("OpenSubtitles returned an invalid file link.") }
        if (!safeUrl(url)) throw IOException("OpenSubtitles did not return a secure subtitle link.")
        return request(url, null, null)
    }

    private suspend fun request(initialUrl: URL, apiKey: String?, body: ByteArray?): ByteArray = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        try {
            withTimeoutOrNull(30_000L) {
                var url = initialUrl
                var redirects = 0
                var result: ByteArray? = null
                while (result == null) {
                    if (apiKey != null) rateGate.withLock {
                        val cooldown = cooldownUntil.get() - SystemClock.elapsedRealtime()
                        if (cooldown > 0) throw SafeFailure("OpenSubtitles is limiting requests. Try again in ${(cooldown + 999) / 1000} seconds.")
                        val wait = nextRequest - SystemClock.elapsedRealtime()
                        if (wait > 0) delay(wait)
                        nextRequest = SystemClock.elapsedRealtime() + 250
                    }
                    val connection = url.openConnection() as HttpsURLConnection
                    try {
                        result = cancellableIo(connection::disconnect) {
                            connection.connectTimeout = 10_000
                            connection.readTimeout = 10_000
                            connection.instanceFollowRedirects = false
                            connection.setRequestProperty("User-Agent", "LumaStream v0.1.0 (Android TV)")
                            connection.setRequestProperty("Accept", if (apiKey == null) "text/plain, application/octet-stream" else "application/json")
                            if (apiKey != null) connection.setRequestProperty("Api-Key", apiKey)
                            if (body != null) {
                                connection.requestMethod = "POST"
                                connection.doOutput = true
                                connection.setFixedLengthStreamingMode(body.size)
                                connection.setRequestProperty("Content-Type", "application/json")
                                connection.outputStream.use { it.write(body) }
                            }
                            val code = connection.responseCode
                            if (code in listOf(301, 302, 303, 307, 308)) {
                                if (body != null || redirects >= 2) throw SafeFailure("OpenSubtitles redirected the request unexpectedly. No download was retried.")
                                val location = connection.getHeaderField("Location") ?: throw SafeFailure("OpenSubtitles returned an invalid redirect.")
                                val target = try { URL(url, location) } catch (_: Exception) { throw SafeFailure("OpenSubtitles returned an invalid redirect.") }
                                if (!safeUrl(target) || (apiKey != null && (target.host != initialUrl.host || target.port != initialUrl.port))) {
                                    throw SafeFailure("OpenSubtitles redirected to an untrusted address.")
                                }
                                url = target
                                redirects++
                                null
                            } else {
                                if (apiKey != null && (code == 429 || code == 503)) {
                                    val header = connection.getHeaderField("Retry-After")
                                    val date = runCatching { ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - System.currentTimeMillis() }.getOrNull()
                                    val wait = (header?.toLongOrNull()?.coerceIn(0, 86_400)?.times(1000) ?: date ?: 60_000L).coerceIn(1000L, 86_400_000L)
                                    cooldownUntil.updateAndGet { maxOf(it, SystemClock.elapsedRealtime() + wait) }
                                }
                                if (code !in 200..299) throw SafeFailure(when (code) {
                                    401, 403 -> "OpenSubtitles denied access. Check the consumer key and enable anonymous downloads in its settings."
                                    406 -> "OpenSubtitles daily download quota is exhausted. Wait until the UTC daily reset."
                                    429 -> "OpenSubtitles is limiting requests. Wait before trying again."
                                    else -> "OpenSubtitles request failed (HTTP $code). No download was retried."
                                }, code)
                                if (connection.contentLengthLong > MAX_BYTES) throw SafeFailure("Subtitle response exceeds the 2 MiB safety limit.")
                                connection.inputStream.use { input ->
                                    cancellableIo(input::close) {
                                        val output = ByteArrayOutputStream()
                                        val buffer = ByteArray(16 * 1024)
                                        while (true) {
                                            currentCoroutineContext().ensureActive()
                                            val count = input.read(buffer)
                                            if (count < 0) break
                                            if (output.size() + count > MAX_BYTES) throw SafeFailure("Subtitle response exceeds the 2 MiB safety limit.")
                                            output.write(buffer, 0, count)
                                        }
                                        output.toByteArray()
                                    }
                                }
                            }
                        }
                    } finally { connection.disconnect() }
                }
                result
            } ?: throw SafeFailure("OpenSubtitles request timed out. No download was retried.")
        } catch (failure: IOException) {
            currentCoroutineContext().ensureActive()
            if (failure is SafeFailure) throw failure
            throw IOException("Cannot connect to OpenSubtitles securely. Check the network; no download was retried.")
        }
    }

    private suspend fun <T> cancellableIo(close: () -> Unit, block: suspend () -> T): T = coroutineScope {
        val finished = AtomicBoolean()
        val closer = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { if (!finished.get()) runCatching(close) }
        }
        try { block() } finally { finished.set(true); closer.cancel() }
    }

    internal class SafeFailure(message: String, val status: Int? = null) : IOException(message)
    private companion object {
        const val API = "https://api.opensubtitles.com/api/v1"
        const val MAX_BYTES = 2 * 1024 * 1024
        val rateGate = Mutex()
        val cooldownUntil = AtomicLong()
        var nextRequest = 0L
        fun safeUrl(url: URL): Boolean = url.protocol == "https" && url.host.isNotBlank() && url.userInfo == null && (url.port == -1 || url.port == 443)
    }
}
