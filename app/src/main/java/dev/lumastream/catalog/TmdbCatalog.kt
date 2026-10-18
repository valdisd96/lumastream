package dev.lumastream.catalog

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

class TmdbCatalog(private val token: String, private val language: String) {
    private val http = CatalogHttp("TMDB")

    init {
        require(SUPPORTED_CATALOG_LANGUAGES.any { it.code == language }) {
            "Select a supported catalog language."
        }
    }

    suspend fun search(query: String, filter: CatalogFilter, page: Int = 1): CatalogPage {
        require(page in 1..MAX_PAGES) { "Catalog page must be between 1 and $MAX_PAGES." }
        val text = query.trim()
        require(text.length <= 200) { "Search must not exceed 200 characters." }
        if (text.isEmpty()) return CatalogPage(emptyList(), page, false)
        val type = when (filter) {
            CatalogFilter.ALL -> "multi"
            CatalogFilter.MOVIES -> "movie"
            CatalogFilter.SERIES -> "tv"
        }
        val data = request("search/$type", mapOf("query" to text, "page" to page.toString(), "include_adult" to "false"))
        val results = data.optJSONArray("results") ?: throw IOException("TMDB search metadata is missing.")
        val items = buildList {
            for (index in 0 until minOf(results.length(), 100)) {
                val result = results.optJSONObject(index) ?: continue
                val kind = when (if (type == "multi") result.catalogText("media_type") else type) {
                    "movie" -> MediaKind.MOVIE
                    "tv" -> MediaKind.SERIES
                    else -> continue
                }
                add(title(result, kind))
            }
        }.distinctBy { it.kind to it.id }
        return CatalogPage(items, page, page < minOf(data.optInt("total_pages", page), MAX_PAGES))
    }

    suspend fun details(item: CatalogTitle): CatalogDetails {
        require(item.id > 0) { "Invalid TMDB title identifier." }
        val data = request("${item.kind.apiType}/${item.id}", mapOf("append_to_response" to "external_ids"))
        val updated = title(data, item.kind)
        if (updated.id != item.id) throw IOException("TMDB returned metadata for another title.")
        val imdb = (data.optJSONObject("external_ids")?.catalogText("imdb_id")
            ?.takeIf { it.isNotEmpty() } ?: data.catalogText("imdb_id")).takeIf { IMDB.matches(it) }
        val seasons = if (item.kind == MediaKind.SERIES) {
            val values = data.optJSONArray("seasons") ?: throw IOException("TMDB season metadata is missing.")
            if (values.length() > 500) throw IOException("TMDB returned too many seasons.")
            buildList {
                for (index in 0 until values.length()) {
                    val value = values.optJSONObject(index) ?: continue
                    val number = value.catalogInteger("season_number")?.takeIf { it <= 10_000 }?.toInt() ?: continue
                    add(CatalogSeason(number, value.catalogText("name", 256), value.optInt("episode_count", 0).coerceAtLeast(0)))
                }
            }.distinctBy { it.number }.sortedBy { it.number }
        } else emptyList()
        val runtime = data.catalogInteger("runtime")?.takeIf { it in 1..100_000 }?.toInt()
            ?: data.optJSONArray("episode_run_time")?.firstRuntime()
        return CatalogDetails(updated, imdb, seasons, runtime)
    }

    suspend fun episodes(details: CatalogDetails, season: Int): List<CatalogEpisode> {
        require(details.title.kind == MediaKind.SERIES && details.seasons.any { it.number == season }) {
            "Select a season belonging to this series."
        }
        val data = request("tv/${details.title.id}/season/$season")
        if (data.optInt("season_number", -1) != season) throw IOException("TMDB returned another season.")
        val values = data.optJSONArray("episodes") ?: throw IOException("TMDB episode metadata is missing.")
        if (values.length() > 1000) throw IOException("TMDB returned too many episodes for one season.")
        return buildList {
            for (index in 0 until values.length()) {
                val value = values.optJSONObject(index) ?: throw IOException("TMDB episode metadata is invalid.")
                val id = value.optInt("id", -1)
                val number = value.optInt("episode_number", -1)
                if (id <= 0 || number <= 0 || value.optInt("season_number", -1) != season) {
                    throw IOException("TMDB episode identity is missing or inconsistent.")
                }
                add(CatalogEpisode(id, season, number, value.catalogText("name", 256),
                    value.catalogText("overview", 16_384), image(value, "still_path"), value.catalogText("air_date", 10)))
            }
        }.distinctBy { it.number }.sortedBy { it.number }
    }

    private suspend fun request(path: String, parameters: Map<String, String> = emptyMap()): JSONObject {
        if (token.isBlank()) throw IOException("Configure a TMDB API read access token to search the catalog.")
        if (token.length > 4096 || token.any { it.isWhitespace() || it.isISOControl() }) {
            throw IOException("The TMDB API read access token is invalid.")
        }
        val uri = Uri.parse("https://api.themoviedb.org/3/$path").buildUpon().appendQueryParameter("language", language)
        parameters.forEach { (key, value) -> uri.appendQueryParameter(key, value) }
        return http.get(uri.build().toString(), token)
    }

    private fun title(data: JSONObject, kind: MediaKind): CatalogTitle {
        val id = data.optInt("id", -1)
        val movie = kind == MediaKind.MOVIE
        val name = data.catalogText(if (movie) "title" else "name", 512)
        val original = data.catalogText(if (movie) "original_title" else "original_name", 512)
        if (id <= 0 || (name.isEmpty() && original.isEmpty())) throw IOException("TMDB title identity is missing.")
        val date = data.catalogText(if (movie) "release_date" else "first_air_date", 10)
        return CatalogTitle(id, kind, name.ifEmpty { original }, original, date.take(4).takeIf { YEAR.matches(it) }.orEmpty(),
            data.catalogText("overview", 16_384), image(data, "poster_path"), image(data, "backdrop_path"),
            data.catalogText("original_language", 16).takeIf { ORIGINAL_LANGUAGE.matches(it) })
    }

    private fun image(data: JSONObject, key: String): String? = data.catalogText(key, 256)
        .takeIf { IMAGE_PATH.matches(it) }

    private fun JSONArray.firstRuntime(): Int? {
        for (index in 0 until minOf(length(), 100)) {
            val value = optInt(index, 0)
            if (value in 1..100_000) return value
        }
        return null
    }

    private companion object {
        const val MAX_PAGES = 500
        val IMDB = Regex("tt[0-9]{7,12}")
        val YEAR = Regex("[0-9]{4}")
        val ORIGINAL_LANGUAGE = Regex("[a-z]{2,3}")
        val IMAGE_PATH = Regex("/[A-Za-z0-9_-]+\\.(jpg|png|webp)")
    }
}
