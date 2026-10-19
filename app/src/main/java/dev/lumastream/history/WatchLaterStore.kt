package dev.lumastream.history

import android.content.Context
import dev.lumastream.catalog.CatalogTitle
import dev.lumastream.catalog.MediaKind
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Source-independent snapshots, in caller order (first occurrence wins). */
class WatchLaterStore internal constructor(private val file: File) {
    constructor(context: Context) : this(File(context.filesDir, "watch-later.json"))

    suspend fun load(): List<CatalogTitle> = withContext(Dispatchers.IO) {
        storageLock.withLock {
            if (!file.exists()) return@withLock emptyList()
            try {
                require(file.length() <= MAX_BYTES) { "Watch later storage is too large" }
                val root = JSONObject(file.readText(Charsets.UTF_8))
                require(root.get("version") == 1) { "Unsupported watch later version" }
                val titles = root.getJSONArray("titles")
                require(titles.length() <= MAX_TITLES) { "Too many saved titles" }
                List(titles.length()) { titles.getJSONObject(it).toTitle() }.distinctBy { it.kind to it.id }
            } catch (failure: Exception) {
                throw IOException("Watch later could not be read. Saved titles have not been changed.", failure)
            }
        }
    }

    suspend fun save(titles: List<CatalogTitle>) = withContext(Dispatchers.IO) {
        storageLock.withLock {
            val temporary = File(file.parentFile, "${file.name}.new")
            try {
                val retained = titles.asSequence().distinctBy { it.kind to it.id }.take(MAX_TITLES).toList()
                retained.forEach { it.validate() }
                val root = JSONObject().put("version", 1).put("titles", JSONArray(retained.map { it.toJson() }))
                val bytes = root.toString().toByteArray(Charsets.UTF_8)
                require(bytes.size <= MAX_BYTES) { "Watch later storage is too large" }
                FileOutputStream(temporary).use { output ->
                    output.write(bytes)
                    output.fd.sync()
                }
                // Keep the previous committed file until the synced replacement is ready.
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                Unit
            } catch (failure: Exception) {
                temporary.delete()
                throw IOException("Watch later could not be saved. Previous saved titles have been preserved.", failure)
            }
        }
    }

    private companion object {
        const val MAX_TITLES = 100
        const val MAX_BYTES = 16 * 1024 * 1024
        val storageLock = Mutex()
    }
}

private val SAVED_YEAR = Regex("[0-9]{4}")
private val SAVED_LANGUAGE = Regex("[a-z]{2,3}")
private val SAVED_IMAGE = Regex("/[A-Za-z0-9_-]+\\.(jpg|png|webp)")

private fun CatalogTitle.validate() {
    require(id > 0 && title.isNotBlank() && title.length <= 512 && originalTitle.length <= 512)
    require(year.isEmpty() || SAVED_YEAR.matches(year))
    require(overview.length <= 16_384)
    require(originalLanguage == null || SAVED_LANGUAGE.matches(originalLanguage))
    require(validSavedImage(posterPath) && validSavedImage(backdropPath))
}

private fun validSavedImage(path: String?): Boolean =
    path == null || (path.length <= 256 && SAVED_IMAGE.matches(path))

private fun CatalogTitle.toJson(): JSONObject = JSONObject()
    .put("id", id).put("kind", kind.name).put("title", title).put("originalTitle", originalTitle)
    .put("year", year).put("overview", overview)
    .put("posterPath", posterPath ?: JSONObject.NULL).put("backdropPath", backdropPath ?: JSONObject.NULL)
    .put("originalLanguage", originalLanguage ?: JSONObject.NULL)

private fun JSONObject.toTitle(): CatalogTitle {
    val id = get("id")
    require(id is Int && id > 0) { "Invalid saved title identifier" }
    return CatalogTitle(
        id, MediaKind.valueOf(requiredText("kind")), requiredText("title"), requiredText("originalTitle"),
        requiredText("year"), requiredText("overview"), nullableText("posterPath"), nullableText("backdropPath"),
        nullableText("originalLanguage"),
    ).also { it.validate() }
}

private fun JSONObject.requiredText(key: String): String =
    get(key) as? String ?: throw IOException("Invalid saved title metadata")

private fun JSONObject.nullableText(key: String): String? =
    if (get(key) == JSONObject.NULL) null else requiredText(key)
