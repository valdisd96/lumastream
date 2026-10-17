package dev.lumastream.catalog

import android.net.Uri
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
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException

data class ArchiveItem(
    val identifier: String,
    val title: String,
    val creator: String,
    val licenseUrl: String,
)

data class ArchivePage(val items: List<ArchiveItem>, val hasMore: Boolean)

class ArchiveCatalog {
    suspend fun search(query: String, page: Int): ArchivePage {
        require(query.length <= MAX_QUERY_LENGTH) { "Search must be at most $MAX_QUERY_LENGTH characters" }
        val term = query.trim()
        require(term.isNotEmpty()) { "Enter a movie title or creator to search" }
        require(page in 1..(Int.MAX_VALUE / PAGE_SIZE)) { "Search page is out of range" }
        // A quoted, escaped phrase prevents user input becoming Solr operators or fields.
        val literal = buildString {
            append('"')
            for (character in term) {
                if (character in "+-!(){}[]^\"~*?:\\/&|") append('\\')
                append(if (character.isISOControl()) ' ' else character)
            }
            append('"')
        }
        val uri = archiveUri().appendPath("advancedsearch.php")
            .appendQueryParameter("q", "mediatype:movies AND $literal")
            .appendQueryParameter("rows", PAGE_SIZE.toString())
            .appendQueryParameter("page", page.toString())
            .appendQueryParameter("sort[]", "identifier asc")
            .appendQueryParameter("output", "json")
        for (field in listOf("identifier", "title", "creator", "licenseurl")) {
            uri.appendQueryParameter("fl[]", field)
        }
        val root = request(uri.build())
        val response = root.optJSONObject("response")
            ?: throw IOException("Internet Archive could not complete this search. Try another search later.")
        val documents = response.optJSONArray("docs")
            ?: throw IOException("Internet Archive returned an invalid search result")
        val total = response.optLong("numFound", -1)
        val start = (page.toLong() - 1) * PAGE_SIZE
        if (total < 0 || response.optLong("start", -1) != start || documents.length() > PAGE_SIZE) {
            throw IOException("Internet Archive returned an inconsistent search page. Start a new search.")
        }
        val items = ArrayList<ArchiveItem>(documents.length())
        for (index in 0 until documents.length()) {
            currentCoroutineContext().ensureActive()
            val document = documents.optJSONObject(index)
                ?: throw IOException("Internet Archive returned an invalid movie entry")
            val identifier = document.text("identifier")
            if (!validIdentifier(identifier)) throw IOException("Internet Archive returned an invalid movie identifier")
            items.add(
                ArchiveItem(
                    identifier = identifier,
                    title = document.text("title").ifBlank { identifier },
                    creator = document.text("creator"),
                    licenseUrl = document.text("licenseurl"),
                ),
            )
        }
        return ArchivePage(items, documents.length() > 0 && start + documents.length() < total)
    }

    suspend fun torrentUrl(item: ArchiveItem): String {
        require(validIdentifier(item.identifier)) { "Invalid Internet Archive item identifier" }
        val root = request(archiveUri().appendPath("metadata").appendPath(item.identifier).build())
        val metadata = root.optJSONObject("metadata")
        if (root.flag("is_dark") || root.flag("is_restricted") ||
            metadata?.flag("access-restricted-item") == true || metadata?.flag("is_dark") == true
        ) {
            throw IOException("This Internet Archive item is restricted and cannot be played")
        }
        if (metadata == null || metadata.text("identifier") != item.identifier || root.has("error")) {
            throw IOException("This Internet Archive item is missing or no longer available")
        }
        val files = root.optJSONArray("files")
            ?: throw IOException("This Internet Archive item has no downloadable files")
        var restrictedTorrent = false
        for (index in 0 until files.length()) {
            currentCoroutineContext().ensureActive()
            val file = files.optJSONObject(index) ?: continue
            if (!file.text("format").equals("Archive BitTorrent", ignoreCase = true)) continue
            if (file.flag("private") || file.flag("restricted")) {
                restrictedTorrent = true
                continue
            }
            val name = file.text("name")
            if (name.length !in 1..2048 || !name.endsWith(".torrent", ignoreCase = true) ||
                name.any { it.isISOControl() || it == '\\' }
            ) continue
            val segments = name.split('/')
            if (segments.any { it.isEmpty() || it == "." || it == ".." }) continue
            // Only construct archive.org URLs; never trust a metadata-provided remote URL.
            val download = archiveUri().appendPath("download").appendPath(item.identifier)
            for (segment in segments) download.appendPath(segment)
            return download.build().toString()
        }
        if (restrictedTorrent) throw IOException("This item's Internet Archive torrent is restricted")
        throw IOException("This Internet Archive item does not provide an Archive BitTorrent download")
    }

    private suspend fun request(uri: Uri): JSONObject = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        checkCooldown()
        try {
            withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                val connection = URL(uri.toString()).openConnection() as HttpsURLConnection
                try {
                    cancellableIo(connection::disconnect) {
                        connection.connectTimeout = 15_000
                        connection.readTimeout = 15_000
                        connection.instanceFollowRedirects = false
                        connection.setRequestProperty("Accept", "application/json")
                        connection.setRequestProperty("User-Agent", "LumaStream/0.1 (Android TV; Internet Archive movie catalog)")
                        val code = connection.responseCode
                        if (code == 429 || code == 503) {
                            val delay = retryDelay(connection.getHeaderField("Retry-After"))
                            if (code == 429 || delay != null) {
                                startCooldown(delay ?: 60_000L)
                                checkCooldown()
                            }
                        }
                        if (code !in 200..299) throw httpFailure(code)
                        if (connection.contentLengthLong > MAX_RESPONSE_BYTES) {
                            throw IOException("Internet Archive response exceeds the 2 MiB safety limit. Choose another item or a narrower search.")
                        }
                        val text = connection.inputStream.use { input ->
                            cancellableIo(input::close) { readBounded(input) }
                        }
                        currentCoroutineContext().ensureActive()
                        try {
                            JSONObject(text)
                        } catch (failure: JSONException) {
                            throw IOException("Internet Archive returned invalid data. Try again later.", failure)
                        }
                    }
                } finally {
                    connection.disconnect()
                }
            } ?: throw IOException("Internet Archive request timed out after 30 seconds. Check your connection and try again.")
        } catch (failure: IOException) {
            // Closing a blocked connection on cancellation can throw IOException.
            currentCoroutineContext().ensureActive()
            if (failure is SocketTimeoutException) {
                throw IOException("Internet Archive connection timed out. Check your connection and try again.", failure)
            }
            if (failure is UnknownHostException || failure is ConnectException) {
                throw IOException("Cannot connect to Internet Archive. Check your internet connection and try again.", failure)
            }
            if (failure is SSLException) {
                throw IOException("Cannot establish a secure connection to Internet Archive. Check the device clock and network.", failure)
            }
            throw failure
        }
    }

    private suspend fun readBounded(input: InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size() + count > MAX_RESPONSE_BYTES) {
                throw IOException("Internet Archive response exceeds the 2 MiB safety limit. Choose another item or a narrower search.")
            }
            output.write(buffer, 0, count)
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private suspend fun <T> cancellableIo(cancelIo: () -> Unit, block: suspend () -> T): T = coroutineScope {
        val finished = AtomicBoolean()
        // Match torrent I/O: disconnect/close on another IO worker, not just a thread interrupt.
        val closer = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                if (!finished.get()) runCatching(cancelIo)
            }
        }
        try {
            block()
        } finally {
            finished.set(true)
            closer.cancel()
        }
    }

    private fun httpFailure(code: Int): IOException = IOException(
        when (code) {
            401, 403 -> "Internet Archive denied access (HTTP $code). This item may be restricted."
            404, 410 -> "This Internet Archive item is missing or no longer available (HTTP $code)."
            429 -> "Internet Archive is limiting requests (HTTP 429). Wait before trying again."
            in 500..599 -> "Internet Archive is temporarily unavailable (HTTP $code). Try again later."
            else -> "Internet Archive request failed (HTTP $code). Try again later."
        },
    )

    private fun checkCooldown() {
        val remaining = cooldownUntil.get() - SystemClock.elapsedRealtime()
        if (remaining > 0) {
            val seconds = remaining / 1000 + if (remaining % 1000 == 0L) 0 else 1
            throw IOException("Internet Archive asked LumaStream to wait. Try again in $seconds seconds.")
        }
    }

    private fun startCooldown(delayMs: Long) {
        val now = SystemClock.elapsedRealtime()
        val until = if (delayMs > Long.MAX_VALUE - now) Long.MAX_VALUE else now + delayMs
        cooldownUntil.updateAndGet { old -> maxOf(old, until) }
    }

    private fun retryDelay(value: String?): Long? {
        val header = value?.trim() ?: return null
        header.toLongOrNull()?.let { seconds ->
            if (seconds >= 0) return if (seconds > Long.MAX_VALUE / 1000) Long.MAX_VALUE else seconds * 1000
        }
        return try {
            val deadline = ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
            (deadline - System.currentTimeMillis()).coerceAtLeast(0)
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun JSONObject.text(key: String): String = when (val value = opt(key)) {
        is String -> value.trim()
        is JSONArray -> buildList {
            for (index in 0 until value.length()) {
                val entry = value.opt(index)
                if (entry is String && entry.isNotBlank()) add(entry.trim())
            }
        }.joinToString(", ")
        else -> ""
    }

    private fun JSONObject.flag(key: String): Boolean = when (val value = opt(key)) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        is String -> value.equals("true", ignoreCase = true) || value == "1"
        is JSONArray -> (0 until value.length()).any {
            value.optString(it).equals("true", ignoreCase = true) || value.optString(it) == "1"
        }
        else -> false
    }

    private fun validIdentifier(value: String): Boolean = value.length in 1..255 &&
        value != "." && value != ".." && value.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "-_." }

    private fun archiveUri(): Uri.Builder = Uri.Builder().scheme("https").authority("archive.org")

    private companion object {
        const val PAGE_SIZE = 20
        const val MAX_QUERY_LENGTH = 200
        const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
        const val REQUEST_TIMEOUT_MS = 30_000L
        val cooldownUntil = AtomicLong()
    }
}
