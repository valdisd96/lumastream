package dev.lumastream.subtitle

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneOffset

/** Construction is offline. Call search/download only in response to the user's buttons. */
class OpenSubtitlesClient {
    private val http = OpenSubtitlesHttp()
    private val downloads = Mutex()
    private var quotaKey: String? = null
    private var quotaReset = 0L

    suspend fun search(apiKey: String, request: SubtitleSearchRequest): List<ProviderSubtitle> = withContext(Dispatchers.IO) {
        val plan = subtitleSearchPlan(request)
        val query = plan.parameters.entries.joinToString("&") { (key, value) -> "$key=${URLEncoder.encode(value, Charsets.UTF_8.name())}" }
        val json = http.json("/subtitles?$query", apiKey)
        currentCoroutineContext().ensureActive()
        parseProviderSubtitles(json, plan)
    }

    suspend fun download(apiKey: String, subtitle: ProviderSubtitle): SubtitleDownload = downloads.withLock {
        if (subtitle.fileId <= 0) throw IOException("The OpenSubtitles file identity is invalid.")
        if (quotaKey == apiKey && System.currentTimeMillis() < quotaReset) throw IOException("OpenSubtitles daily download quota is exhausted. Wait until the UTC daily reset.")
        val response = try {
            http.json("/download", apiKey, JSONObject().put("file_id", subtitle.fileId).put("sub_format", "srt"))
        } catch (failure: OpenSubtitlesHttp.SafeFailure) {
            if (failure.status == 406) {
                quotaKey = apiKey
                quotaReset = nextUtcMidnight()
            }
            throw failure
        }
        val remaining = response.providerInteger("remaining")?.takeIf { it <= Int.MAX_VALUE }?.toInt()
        val reset = response.providerText("reset_time_utc", 64).ifEmpty { null }
        if (remaining == 0) {
            quotaKey = apiKey
            quotaReset = runCatching { Instant.parse(reset).toEpochMilli() }.getOrNull()
                ?.takeIf { it > System.currentTimeMillis() && it <= System.currentTimeMillis() + 86_400_000L }
                ?: nextUtcMidnight()
        }
        val link = response.providerText("link", 16_384)
        if (link.isBlank()) throw IOException("OpenSubtitles did not return a subtitle file link. No download was retried.")
        val bytes = http.file(link)
        currentCoroutineContext().ensureActive()
        // The API explicitly converts to UTF-8 SRT; the caller's existing parser validates content.
        val filename = response.providerText("file_name", 512).substringAfterLast('/').substringAfterLast('\\')
            .substringBeforeLast('.', "subtitle") + ".srt"
        SubtitleDownload(bytes, filename, remaining, reset)
    }

    private fun nextUtcMidnight(): Long = Instant.now().atZone(ZoneOffset.UTC).toLocalDate()
        .plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
}

internal fun parseProviderSubtitles(json: JSONObject, plan: SubtitleSearchPlan): List<ProviderSubtitle> {
    val data = json.optJSONArray("data") ?: throw IOException("OpenSubtitles returned an invalid search result.")
    val candidates = mutableListOf<ProviderSubtitle>()
    for (index in 0 until minOf(data.length(), 100)) {
        val attributes = data.optJSONObject(index)?.optJSONObject("attributes") ?: continue
        val language = attributes.providerText("language", 16)
        if (language != plan.request.language) continue
        val hashMatch = plan.request.movieHash != null && attributes.optBoolean("moviehash_match", false)
        if (plan.hashOnly && !hashMatch) continue
        val feature = attributes.optJSONObject("feature_details") ?: continue
        val release = attributes.providerText("release", 1024)
        val season = feature.providerInteger("season_number")
        val episode = feature.providerInteger("episode_number")
        val expected = plan.expectedEpisode ?: if (plan.hashOnly && hashMatch && season != null &&
            season <= Int.MAX_VALUE && episode != null && episode in 1..Int.MAX_VALUE.toLong()
        ) SubtitleEpisode(season.toInt(), episode.toInt()) else null
        if (contradictorySubtitleEpisode(release, expected)) continue
        if (expected != null) {
            if (season != expected.season.toLong() || episode != expected.episode.toLong()) continue
        } else if (feature.providerText("feature_type", 32) == "Episode" || episode?.let { it > 0 } == true) continue
        if (plan.request.imdbId != null) {
            val key = if (expected == null) "imdb_id" else "parent_imdb_id"
            if (feature.providerInteger(key) != plan.request.imdbId.removePrefix("tt").toLong()) continue
        } else if (!hashMatch) {
            val title = feature.providerText(if (expected == null) "title" else "parent_title", 512)
            if (subtitleTokens(title) != plan.titleTokens) continue
            if (expected == null && feature.providerInteger("year") != plan.year?.toLong()) continue
        }
        val files = attributes.optJSONArray("files") ?: continue
        // Multi-CD uploads cannot safely be applied as one complete subtitle to this video.
        if (files.length() != 1) continue
        val file = files.optJSONObject(0) ?: continue
        val fileId = file.providerInteger("file_id")?.takeIf { it > 0 } ?: continue
        val filename = file.providerText("file_name", 1024)
        if (contradictorySubtitleEpisode(filename, expected)) continue
        val fps = (attributes.opt("fps") as? Number)?.toDouble()?.takeIf { it.isFinite() && it > 0 && it <= 240 }
        candidates += ProviderSubtitle(fileId, release.ifBlank { filename }, filename, language, hashMatch, attributes.optBoolean("hearing_impaired", false), fps)
    }
    return candidates.distinctBy { it.fileId }
        .sortedWith(compareByDescending<ProviderSubtitle> { subtitleReleaseScore(plan.request.videoFilename, it) }.thenBy { it.fileId })
        .take(40)
}

internal fun JSONObject.providerText(key: String, limit: Int): String =
    (opt(key) as? String).orEmpty().take(limit).filter { !it.isISOControl() }.trim()

internal fun JSONObject.providerInteger(key: String): Long? {
    val value = opt(key)
    if (value is String) return value.takeIf { it.length <= 18 && it.all(Char::isDigit) }?.toLongOrNull()
    if (value !is Number) return null
    val integer = value.toLong()
    return integer.takeIf { it >= 0 && value.toDouble().isFinite() && value.toDouble() == it.toDouble() }
}
