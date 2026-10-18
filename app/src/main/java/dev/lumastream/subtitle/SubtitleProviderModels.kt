package dev.lumastream.subtitle

import java.io.IOException
import java.util.Locale

/** IMDb identifies the movie, or the parent series when season and episode are supplied. */
data class SubtitleSearchRequest(
    val videoFilename: String,
    val imdbId: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val movieHash: String? = null,
    val language: String = "en",
)

data class ProviderSubtitle(
    val fileId: Long,
    val release: String,
    val filename: String,
    val language: String,
    val hashMatch: Boolean,
    val hearingImpaired: Boolean,
    val fps: Double?,
)

data class SubtitleDownload(
    val bytes: ByteArray,
    val filename: String,
    val remainingDownloads: Int?,
    val resetTimeUtc: String?,
)

internal data class SubtitleEpisode(val season: Int, val episode: Int)
internal data class SubtitleSearchPlan(
    val parameters: Map<String, String>,
    val request: SubtitleSearchRequest,
    val expectedEpisode: SubtitleEpisode?,
    val titleTokens: Set<String>,
    val year: Int?,
    val hashOnly: Boolean,
)

private val episodePattern = Regex("(?i)(?<![a-z0-9])(?:s(\\d{1,3})[ ._-]*e(\\d{1,4})|(\\d{1,3})x(\\d{1,4}))(?!\\d)")
private val episodeChain = Regex("(?i)(?:s\\d{1,3}[ ._-]*e\\d{1,4}|\\d{1,3}x\\d{1,4})[ ._-]*(?:e\\d|x\\d|[-+]\\d)")
private val yearPattern = Regex("(?<!\\d)(?:19|20)\\d{2}(?!\\d)")
private val releaseBoundary = Regex("(?i)(?<![a-z0-9])(?:2160p|1080p|720p|480p|bluray|b[dr]rip|web[ ._-]?(?:dl|rip)|hdtv|x26[45]|h[ ._-]?26[45]|hevc|avc|dvdrip)(?![a-z0-9])")

internal fun subtitleEpisodes(value: String): Set<SubtitleEpisode> = episodePattern.findAll(value).map {
    SubtitleEpisode((it.groupValues[1].ifEmpty { it.groupValues[3] }).toInt(), (it.groupValues[2].ifEmpty { it.groupValues[4] }).toInt())
}.toSet()

internal fun subtitleTokens(value: String): Set<String> = value.lowercase(Locale.ROOT)
    .split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }.toSet()

internal fun subtitleSearchPlan(request: SubtitleSearchRequest): SubtitleSearchPlan {
    fun invalid(message: String): Nothing = throw IOException(message)
    if (!Regex("[a-z]{2}(?:-[a-z]{2})?").matches(request.language)) invalid("Choose a supported subtitle language.")
    if (request.videoFilename.length > 4096) invalid("Video filename is too long for subtitle search.")
    if (request.imdbId != null && !Regex("tt\\d{7,10}").matches(request.imdbId)) invalid("The catalog IMDb identity is invalid.")
    if ((request.season == null) != (request.episode == null) || (request.season != null && request.season < 0) || (request.episode != null && request.episode < 1)) {
        invalid("Subtitle search needs both a season and an episode.")
    }
    if (request.movieHash != null && !Regex("[0-9a-fA-F]{16}").matches(request.movieHash)) invalid("The video subtitle hash is invalid.")
    val basename = request.videoFilename.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.', request.videoFilename.substringAfterLast('/').substringAfterLast('\\'))
    val episodes = subtitleEpisodes(basename)
    if (episodes.size > 1 || episodeChain.containsMatchIn(basename)) invalid("Subtitle search cannot identify a single episode in this video filename.")
    val expectedEpisode = request.season?.let { SubtitleEpisode(it, requireNotNull(request.episode)) } ?: episodes.singleOrNull()
    if (episodes.any { it != expectedEpisode }) invalid("The video filename contradicts the selected episode.")
    if (request.imdbId != null && request.season == null && episodes.isNotEmpty()) invalid("The movie filename contains an episode identity.")
    val year = yearPattern.find(basename)?.value?.toInt()
    val boundary = listOfNotNull(episodePattern.find(basename)?.range?.first, yearPattern.find(basename)?.range?.first, releaseBoundary.find(basename)?.range?.first).minOrNull() ?: basename.length
    val title = basename.take(boundary).replace(Regex("[._-]+"), " ").trim()
    val titleTokens = subtitleTokens(title)
    val safeQuery = title.length >= 3 && titleTokens.isNotEmpty() && (expectedEpisode != null || year != null)
    val hashOnly = request.imdbId == null && !safeQuery
    if (hashOnly && request.movieHash == null) invalid("This filename has no safe title/year or single-episode identity. Choose a catalog source or use a video hash.")
    val parameters = linkedMapOf("languages" to request.language, "page" to "1")
    if (request.imdbId != null) {
        parameters[if (expectedEpisode == null) "imdb_id" else "parent_imdb_id"] = request.imdbId.removePrefix("tt").trimStart('0')
        parameters["type"] = if (expectedEpisode == null) "movie" else "episode"
    } else if (!hashOnly) {
        parameters["query"] = title.take(200)
        if (expectedEpisode == null) parameters["year"] = requireNotNull(year).toString()
        parameters["type"] = if (expectedEpisode == null) "movie" else "episode"
    }
    expectedEpisode?.let {
        parameters["season_number"] = it.season.toString()
        parameters["episode_number"] = it.episode.toString()
    }
    request.movieHash?.let { parameters["moviehash"] = it.lowercase(Locale.ROOT) }
    if (hashOnly) parameters["moviehash_match"] = "only"
    return SubtitleSearchPlan(parameters, request, expectedEpisode, titleTokens, year, hashOnly)
}

internal fun contradictorySubtitleEpisode(value: String, expected: SubtitleEpisode?): Boolean =
    episodeChain.containsMatchIn(value) || subtitleEpisodes(value).any { it != expected }

internal fun subtitleReleaseScore(videoFilename: String, candidate: ProviderSubtitle): Int {
    val video = subtitleTokens(videoFilename.substringAfterLast('/').substringBeforeLast('.'))
    val release = subtitleTokens(candidate.release + " " + candidate.filename.substringBeforeLast('.'))
    return (if (candidate.hashMatch) 10_000 else 0) + video.count { it in release } * 10 +
        (if (video == subtitleTokens(candidate.release)) 1000 else 0)
}

/** OpenSubtitles hash: size plus little-endian 64-bit words from the first/last 64 KiB. */
fun openSubtitlesMovieHash(fileSize: Long, first64KiB: ByteArray, last64KiB: ByteArray): String? {
    if (fileSize < 131_072 || first64KiB.size != 65_536 || last64KiB.size != 65_536) return null
    var sum = fileSize
    fun add(block: ByteArray) {
        for (offset in block.indices step 8) {
            var word = 0L
            for (byte in 0..7) word = word or ((block[offset + byte].toLong() and 255L) shl (byte * 8))
            sum += word
        }
    }
    add(first64KiB)
    add(last64KiB)
    return java.lang.Long.toUnsignedString(sum, 16).padStart(16, '0')
}
