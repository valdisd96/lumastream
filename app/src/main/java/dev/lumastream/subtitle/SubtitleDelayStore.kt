package dev.lumastream.subtitle

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private const val MAX_DELAY_ENTRIES = 128
private const val MAX_DELAY_BYTES = 64 * 1024
private const val MAX_DELAY_MS = 30_000L

/** Serialized read-modify-write also protects callers using different store instances. */
class SubtitleDelayStore internal constructor(private val file: File) {
    constructor(context: Context) : this(File(context.filesDir, "subtitle-delays.json"))

    suspend fun get(videoIdentity: String, trackIdentity: String): Long = withContext(Dispatchers.IO) {
        val key = key(videoIdentity, trackIdentity)
        storageLock.withLock { readEntries()[key] ?: 0L }
    }

    suspend fun set(videoIdentity: String, trackIdentity: String, offsetMs: Long): Unit = withContext(Dispatchers.IO) {
        require(offsetMs in -MAX_DELAY_MS..MAX_DELAY_MS) { "Subtitle delay must be within ±30 seconds." }
        val key = key(videoIdentity, trackIdentity)
        storageLock.withLock {
            val entries = readEntries()
            entries.remove(key)
            if (offsetMs != 0L) entries[key] = offsetMs
            while (entries.size > MAX_DELAY_ENTRIES) entries.remove(entries.keys.first())
            val temporary = File(file.parentFile, "${file.name}.new")
            try {
                currentCoroutineContext().ensureActive()
                val records = JSONArray()
                entries.forEach { (identity, delay) ->
                    records.put(JSONObject().put("key", identity).put("offsetMs", delay))
                }
                val bytes = JSONObject().put("version", 1).put("entries", records)
                    .toString().toByteArray(Charsets.UTF_8)
                FileOutputStream(temporary).use { output ->
                    output.write(bytes)
                    output.fd.sync()
                }
                currentCoroutineContext().ensureActive()
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (failure: Exception) {
                temporary.delete()
                if (failure is CancellationException) throw failure
                throw IOException("Subtitle timing could not be saved. Previous saved timings have been preserved.", failure)
            }
            Unit
        }
    }

    private suspend fun readEntries(): LinkedHashMap<String, Long> {
        currentCoroutineContext().ensureActive()
        if (!file.exists()) return linkedMapOf()
        try {
            val bytes = file.inputStream().use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > MAX_DELAY_BYTES) throw IOException("Subtitle timing storage exceeds its size limit.")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            val root = JSONObject(bytes.toString(Charsets.UTF_8))
            require(root.getInt("version") == 1)
            val records = root.getJSONArray("entries")
            require(records.length() <= MAX_DELAY_ENTRIES)
            val entries = LinkedHashMap<String, Long>(records.length())
            for (index in 0 until records.length()) {
                val record = records.getJSONObject(index)
                val key = record.getString("key")
                val delay = record.getLong("offsetMs")
                require(key.length == 64 && key.all { it in '0'..'9' || it in 'a'..'f' })
                require(delay != 0L && delay in -MAX_DELAY_MS..MAX_DELAY_MS && !entries.containsKey(key))
                entries[key] = delay
            }
            currentCoroutineContext().ensureActive()
            return entries
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            throw IOException("Saved subtitle timings could not be read. Stored timings have not been changed.", failure)
        }
    }

    private fun key(video: String, track: String): String {
        require(video.isNotBlank() && track.isNotBlank() && video.length <= 4096 && track.length <= 4096) {
            "Exact video and subtitle identities are required for saved timing."
        }
        return identityDigest(video, track)
    }

    private companion object {
        val storageLock = Mutex()
    }
}
