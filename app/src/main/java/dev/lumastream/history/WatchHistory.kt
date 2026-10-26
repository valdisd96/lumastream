package dev.lumastream.history

import android.content.Context
import dev.lumastream.catalog.CatalogDetails
import dev.lumastream.catalog.CatalogEpisode
import dev.lumastream.catalog.CatalogSeason
import dev.lumastream.catalog.CatalogSelection
import dev.lumastream.catalog.CatalogSource
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

private const val MAX_ENTRIES = 100

data class WatchEntry(
    val selection: CatalogSelection,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAtMs: Long,
    val completed: Boolean,
) {
    val titleKey: String get() = "${selection.details.title.kind.apiType}:${selection.details.title.id}"
    val key: String get() = selection.episode?.let { "$titleKey:${it.season}:${it.number}" } ?: titleKey
}

/** One card per title; keep the exact latest episode/source and retain all stored resume points. */
internal fun historyCards(entries: List<WatchEntry>): List<WatchEntry> =
    entries.sortedByDescending { it.updatedAtMs }.distinctBy { it.titleKey }

internal fun removeHistoryEntry(entries: List<WatchEntry>, key: String): List<WatchEntry> =
    entries.filterNot { it.key == key }

internal fun setHistoryCompleted(entries: List<WatchEntry>, key: String, completed: Boolean): List<WatchEntry> =
    entries.map { entry ->
        if (entry.key == key) entry.copy(
            completed = completed,
            positionMs = if (completed) entry.positionMs else 0,
        ) else entry
    }

class WatchHistoryStore internal constructor(private val file: File) {
    constructor(context: Context) : this(File(context.filesDir, "watch-history.json"))

    suspend fun load(): List<WatchEntry> = withContext(Dispatchers.IO) {
        storageLock.withLock {
            if (!file.exists()) return@withLock emptyList()
            try {
                val root = JSONObject(file.readText(Charsets.UTF_8))
                require(root.getInt("version") == 1) { "Unsupported watch history version" }
                val entries = root.getJSONArray("entries")
                recentEntries(List(entries.length()) { entries.getJSONObject(it).toWatchEntry() })
            } catch (failure: Exception) {
                throw IOException("Continue watching history could not be read. Stored history has not been changed.", failure)
            }
        }
    }

    suspend fun save(entries: List<WatchEntry>) = withContext(Dispatchers.IO) {
        storageLock.withLock {
            val temporary = File(file.parentFile, "${file.name}.new")
            try {
                val root = JSONObject().put("version", 1)
                    .put("entries", JSONArray(recentEntries(entries).map { it.toJson() }))
                FileOutputStream(temporary).use { output ->
                    output.write(root.toString().toByteArray(Charsets.UTF_8))
                    output.fd.sync()
                }
                // The synced replacement lives on the same filesystem. Never delete the old
                // history first: a failed/interrupted write must leave it available to load.
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                Unit
            } catch (failure: Exception) {
                temporary.delete()
                throw IOException("Continue watching history could not be saved. Previous history has been preserved.", failure)
            }
        }
    }

    private companion object {
        val storageLock = Mutex()
    }
}

private fun recentEntries(entries: List<WatchEntry>): List<WatchEntry> =
    entries.sortedByDescending { it.updatedAtMs }.distinctBy { it.key }.take(MAX_ENTRIES)

private fun WatchEntry.toJson(): JSONObject = JSONObject()
    .put("selection", selection.toJson())
    .put("positionMs", positionMs)
    .put("durationMs", durationMs)
    .put("updatedAtMs", updatedAtMs)
    .put("completed", completed)

private fun CatalogSelection.toJson(): JSONObject = JSONObject()
    .put("details", JSONObject()
        .put("title", JSONObject()
            .put("id", details.title.id)
            .put("kind", details.title.kind.name)
            .put("title", details.title.title)
            .put("originalTitle", details.title.originalTitle)
            .put("originalLanguage", details.title.originalLanguage ?: JSONObject.NULL)
            .put("year", details.title.year)
            .put("overview", details.title.overview)
            .put("posterPath", details.title.posterPath ?: JSONObject.NULL)
            .put("backdropPath", details.title.backdropPath ?: JSONObject.NULL))
        .put("imdbId", details.imdbId ?: JSONObject.NULL)
        .put("runtimeMinutes", details.runtimeMinutes ?: JSONObject.NULL)
        .put("seasons", JSONArray(details.seasons.map {
            JSONObject().put("number", it.number).put("name", it.name).put("episodeCount", it.episodeCount)
        })))
    .put("episode", episode?.let {
        JSONObject().put("id", it.id).put("season", it.season).put("number", it.number)
            .put("title", it.title).put("overview", it.overview)
            .put("stillPath", it.stillPath ?: JSONObject.NULL).put("airDate", it.airDate)
    } ?: JSONObject.NULL)
    .put("source", JSONObject()
        .put("provider", source.provider).put("infoHash", source.infoHash)
        .put("fileIndex", source.fileIndex ?: JSONObject.NULL)
        .put("filename", source.filename ?: JSONObject.NULL)
        .put("displayName", source.displayName).put("description", source.description)
        .put("quality", source.quality ?: JSONObject.NULL)
        .put("languages", JSONArray(source.languages))
        .put("sizeBytes", source.sizeBytes ?: JSONObject.NULL)
        .put("seeders", source.seeders ?: JSONObject.NULL)
        .put("trackers", JSONArray(source.trackers)))

private fun JSONObject.toWatchEntry(): WatchEntry = WatchEntry(
    selection = getJSONObject("selection").toSelection(),
    positionMs = getLong("positionMs"),
    durationMs = getLong("durationMs"),
    updatedAtMs = getLong("updatedAtMs"),
    completed = getBoolean("completed"),
)

private fun JSONObject.toSelection(): CatalogSelection {
    val details = getJSONObject("details")
    val title = details.getJSONObject("title")
    val seasons = details.getJSONArray("seasons")
    val source = getJSONObject("source")
    return CatalogSelection(
        details = CatalogDetails(
            title = CatalogTitle(
                id = title.getInt("id"), kind = MediaKind.valueOf(title.getString("kind")),
                title = title.getString("title"), originalTitle = title.getString("originalTitle"),
                year = title.getString("year"), overview = title.getString("overview"),
                posterPath = title.nullableString("posterPath"), backdropPath = title.nullableString("backdropPath"),
                originalLanguage = if (title.isNull("originalLanguage")) null else title.getString("originalLanguage"),
            ),
            imdbId = details.nullableString("imdbId"),
            seasons = List(seasons.length()) {
                val season = seasons.getJSONObject(it)
                CatalogSeason(season.getInt("number"), season.getString("name"), season.getInt("episodeCount"))
            },
            runtimeMinutes = details.nullableInt("runtimeMinutes"),
        ),
        episode = if (get("episode") == JSONObject.NULL) null else getJSONObject("episode").let {
            CatalogEpisode(it.getInt("id"), it.getInt("season"), it.getInt("number"),
                it.getString("title"), it.getString("overview"), it.nullableString("stillPath"), it.getString("airDate"))
        },
        source = CatalogSource(
            provider = source.getString("provider"), infoHash = source.getString("infoHash"),
            fileIndex = source.nullableInt("fileIndex"), filename = source.nullableString("filename"),
            displayName = source.getString("displayName"), description = source.getString("description"),
            quality = source.nullableString("quality"), languages = source.stringList("languages"),
            sizeBytes = if (source.get("sizeBytes") == JSONObject.NULL) null else source.getLong("sizeBytes"),
            seeders = source.nullableInt("seeders"), trackers = source.stringList("trackers"),
        ),
    )
}

private fun JSONObject.nullableString(key: String): String? = if (get(key) == JSONObject.NULL) null else getString(key)
private fun JSONObject.nullableInt(key: String): Int? = if (get(key) == JSONObject.NULL) null else getInt(key)
private fun JSONObject.stringList(key: String): List<String> = getJSONArray(key).let { array ->
    List(array.length()) { array.getString(it) }
}
