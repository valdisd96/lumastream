package dev.lumastream.catalog

import android.os.SystemClock
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
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

/** Bounded, redirect-free JSON transport. Never exposes URLs, credentials or remote error bodies. */
internal class CatalogHttp(private val service: String) {
    private val cooldownUntil = AtomicLong()

    suspend fun get(url: String, bearer: String? = null): JSONObject = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        val remaining = cooldownUntil.get() - SystemClock.elapsedRealtime()
        if (remaining > 0) throw IOException("$service is limiting requests. Try again in ${(remaining + 999) / 1000} seconds.")
        try {
            withTimeoutOrNull(30_000L) {
                val connection = URL(url).openConnection() as HttpsURLConnection
                try {
                    cancellableIo(connection::disconnect) {
                        connection.connectTimeout = 15_000
                        connection.readTimeout = 15_000
                        connection.instanceFollowRedirects = false
                        connection.setRequestProperty("Accept", "application/json")
                        connection.setRequestProperty("User-Agent", "LumaStream/0.1 (Android TV)")
                        if (bearer != null) connection.setRequestProperty("Authorization", "Bearer $bearer")
                        val code = connection.responseCode
                        if (code == 429 || code == 503) {
                            val header = connection.getHeaderField("Retry-After")
                            val seconds = header?.toLongOrNull()?.coerceIn(0, 86_400)
                            val dateDelay = runCatching {
                                ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME)
                                    .toInstant().toEpochMilli() - System.currentTimeMillis()
                            }.getOrNull()
                            if (code == 429 || header != null) {
                                val delay = (seconds?.times(1000) ?: dateDelay ?: 60_000L).coerceIn(1000L, 86_400_000L)
                                val until = SystemClock.elapsedRealtime() + delay
                                cooldownUntil.updateAndGet { maxOf(it, until) }
                            }
                        }
                        if (code !in 200..299) throw SafeFailure(when (code) {
                            401, 403 -> "$service denied access. Check its configuration (HTTP $code)."
                            404 -> "$service has no metadata for this request (HTTP 404)."
                            429 -> "$service is limiting requests. Wait before trying again."
                            else -> "$service request failed (HTTP $code). Try again later."
                        })
                        if (connection.contentLengthLong > MAX_BYTES) throw SafeFailure("$service response exceeds the 2 MiB safety limit.")
                        val text = connection.inputStream.use { input ->
                            cancellableIo(input::close) {
                                val output = ByteArrayOutputStream()
                                val buffer = ByteArray(16 * 1024)
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    if (output.size() + count > MAX_BYTES) throw SafeFailure("$service response exceeds the 2 MiB safety limit.")
                                    output.write(buffer, 0, count)
                                }
                                output.toString(Charsets.UTF_8.name())
                            }
                        }
                        currentCoroutineContext().ensureActive()
                        checkNesting(text)
                        try { JSONObject(text) } catch (_: org.json.JSONException) {
                            throw SafeFailure("$service returned invalid JSON.")
                        }
                    }
                } finally { connection.disconnect() }
            } ?: throw SafeFailure("$service request timed out. Try again later.")
        } catch (failure: IOException) {
            currentCoroutineContext().ensureActive()
            if (failure is SafeFailure) throw failure
            throw IOException("Cannot connect to $service securely. Check the network and try again.")
        }
    }

    private suspend fun checkNesting(text: String) {
        var depth = 0
        var quoted = false
        var escaped = false
        text.forEachIndexed { index, char ->
            if (index % 16_384 == 0) currentCoroutineContext().ensureActive()
            if (quoted) {
                if (escaped) escaped = false
                else if (char == '\\') escaped = true
                else if (char == '"') quoted = false
            } else when (char) {
                '"' -> quoted = true
                '{', '[' -> {
                    depth++
                    if (depth > 64) throw SafeFailure("$service JSON exceeds the nesting safety limit.")
                }
                '}', ']' -> depth--
            }
        }
    }

    private suspend fun <T> cancellableIo(close: () -> Unit, block: suspend () -> T): T = coroutineScope {
        val finished = AtomicBoolean()
        val closer = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { if (!finished.get()) runCatching(close) }
        }
        try { block() } finally { finished.set(true); closer.cancel() }
    }

    private class SafeFailure(message: String) : IOException(message)
    private companion object { const val MAX_BYTES = 2 * 1024 * 1024 }
}

internal fun JSONObject.catalogText(key: String, limit: Int = 4096): String =
    (opt(key) as? String).orEmpty().take(limit).filter { !it.isISOControl() || it == '\n' }.trim()

internal fun JSONObject.catalogInteger(key: String): Long? {
    val value = opt(key) as? Number ?: return null
    val result = value.toLong()
    return result.takeIf { value.toDouble().isFinite() && value.toDouble() == result.toDouble() && result >= 0 }
}
