package dev.lumastream.catalog

internal data class NextEpisode(val episode: CatalogEpisode, val seasonEpisodes: List<CatalogEpisode>)

/** Follows TMDB numbering, not episode counts or assumed contiguous seasons. */
internal suspend fun findNextEpisode(
    details: CatalogDetails,
    current: CatalogEpisode,
    episodes: suspend (Int) -> List<CatalogEpisode>,
): NextEpisode? {
    require(details.title.kind == MediaKind.SERIES && details.seasons.any { it.number == current.season }) {
        "The current season is no longer listed for this series. Choose an episode from the catalog."
    }
    val sameSeason = episodes(current.season)
    var next: CatalogEpisode? = null
    for (episode in sameSeason) {
        if (episode.number > current.number && (next == null || episode.number < next.number)) next = episode
    }
    next?.let { return NextEpisode(it, sameSeason) }
    // Specials have no reliable chronological relationship to the regular seasons.
    if (current.season == 0) return null
    for (season in details.seasons.sortedBy { it.number }) {
        if (season.number <= current.season) continue
        val nextSeason = episodes(season.number)
        nextSeason.minByOrNull { it.number }?.let { return NextEpisode(it, nextSeason) }
    }
    return null
}
