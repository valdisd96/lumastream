package dev.lumastream.catalog

enum class MediaKind(val apiType: String, val streamType: String) {
    MOVIE("movie", "movie"), SERIES("tv", "series"),
}

enum class CatalogFilter { ALL, MOVIES, SERIES }

data class CatalogTitle(
    val id: Int,
    val kind: MediaKind,
    val title: String,
    val originalTitle: String,
    val year: String,
    val overview: String,
    val posterPath: String?,
    val backdropPath: String?,
)

data class CatalogPage(val items: List<CatalogTitle>, val page: Int, val hasMore: Boolean)
data class CatalogSeason(val number: Int, val name: String, val episodeCount: Int)
data class CatalogDetails(
    val title: CatalogTitle,
    val imdbId: String?,
    val seasons: List<CatalogSeason>,
    val runtimeMinutes: Int?,
)
data class CatalogEpisode(
    val id: Int,
    val season: Int,
    val number: Int,
    val title: String,
    val overview: String,
    val stillPath: String?,
    val airDate: String,
)
data class CatalogSource(
    val provider: String,
    val infoHash: String,
    val fileIndex: Int?,
    val filename: String?,
    val displayName: String,
    val description: String,
    val quality: String?,
    val languages: List<String>,
    val sizeBytes: Long?,
    val seeders: Int?,
    val trackers: List<String>,
)
data class SourcePage(val sources: List<CatalogSource>, val unsupportedCount: Int)
data class CatalogSelection(val details: CatalogDetails, val episode: CatalogEpisode?, val source: CatalogSource)
