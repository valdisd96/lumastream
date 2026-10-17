package dev.lumastream.catalog

import android.net.Uri
import dev.lumastream.torrent.TorrentFile
import java.io.IOException
import java.util.Locale

private val EPISODE_MARKER = Regex("(?i)(?<![a-z0-9])(?:s(\\d{1,3})[ ._-]*e(\\d{1,3})|(\\d{1,3})x(\\d{1,3}))(?!\\d)")
private val YEAR_MARKER = Regex("(?<!\\d)(19\\d{2}|20\\d{2})(?!\\d)")
private val TITLE_SEPARATORS = Regex("[^\\p{L}\\p{N}]+")
private val INFO_HASH = Regex("[0-9a-fA-F]{40}")
private val TRACKER_SCHEMES = setOf("https", "http", "udp")
private val DEFAULT_TRACKERS = listOf(
    "udp://tracker.opentrackr.org:1337/announce",
    "udp://open.demonii.com:1337/announce",
)

fun catalogMagnet(source: CatalogSource): Uri {
    require(INFO_HASH.matches(source.infoHash)) { "The provider returned an invalid torrent hash." }
    val builder = Uri.Builder().scheme("magnet")
        .encodedQuery("xt=urn:btih:${source.infoHash.lowercase(Locale.ROOT)}")
    // Some providers return only a hash; DHT alone may not discover their swarm.
    for (tracker in source.trackers.ifEmpty { DEFAULT_TRACKERS }.distinct().take(16)) {
        val uri = Uri.parse(tracker)
        if (uri.scheme in TRACKER_SCHEMES && !uri.host.isNullOrBlank() &&
            tracker.length <= 2048 && tracker.none { it.isISOControl() }) {
            builder.appendQueryParameter("tr", tracker)
        }
    }
    return builder.build()
}

fun selectCatalogFile(selection: CatalogSelection, files: List<TorrentFile>): TorrentFile {
    val source = selection.source
    val episode = selection.episode
    require((selection.details.title.kind == MediaKind.SERIES) == (episode != null)) {
        "Select a specific episode before opening this source."
    }
    val expectedName = source.filename?.let(::basename)?.takeIf { it.isNotBlank() }
    val indexed = source.fileIndex?.let { index ->
        files.singleOrNull { it.index == index }
            ?: throw IOException("The source's selected file is not a supported video in this torrent. Choose another source.")
    }
    val named = expectedName?.let { name -> files.filter { basename(it.name).equals(name, ignoreCase = true) } }
    val chosen = when {
        indexed != null -> indexed
        named?.size == 1 -> named.single()
        episode != null -> files.filter { episodeMatches(it.name, episode) }.singleOrNull()
        files.size == 1 -> files.single()
        else -> null
    } ?: throw IOException("This source does not identify one unambiguous video file. Choose another source; no arbitrary file was selected.")
    val actualName = basename(chosen.name)
    if (expectedName != null && !actualName.equals(expectedName, ignoreCase = true)) {
        throw IOException("The torrent file does not match the provider's filename. Choose another source.")
    }
    if (episode != null) {
        val markers = EPISODE_MARKER.findAll(actualName).toList()
        if (markers.isNotEmpty() && !episodeMatches(actualName, episode)) {
            throw IOException("This torrent file belongs to a different episode. Choose another source.")
        }
        if (markers.isEmpty() && (indexed == null || expectedName == null)) {
            throw IOException("The episode cannot be identified safely in this torrent. Choose another source.")
        }
    } else {
        val expectedYear = selection.details.title.year.toIntOrNull()
        val years = YEAR_MARKER.findAll(actualName).map { it.value.toInt() }.toList()
        if (expectedYear != null && years.isNotEmpty() && expectedYear !in years) {
            throw IOException("The torrent filename has a different release year. Choose another source.")
        }
        if (indexed == null && expectedName == null) {
            val actual = normalizedTitle(actualName)
            val item = selection.details.title
            val titles = listOf(item.title, item.originalTitle).filter { it.isNotBlank() }.map(::normalizedTitle)
            if (titles.none { it.isNotEmpty() && " $actual ".contains(" $it ") }) {
                throw IOException("The movie cannot be identified safely in this torrent. Choose another source.")
            }
        }
    }
    return chosen
}

private fun basename(name: String): String = name.replace('\\', '/').substringAfterLast('/')
private fun normalizedTitle(title: String): String = TITLE_SEPARATORS.replace(title.lowercase(Locale.ROOT), " ").trim()
private fun episodeMatches(name: String, episode: CatalogEpisode): Boolean = EPISODE_MARKER.findAll(basename(name)).any { match ->
    val season = match.groupValues[1].ifEmpty { match.groupValues[3] }.toIntOrNull()
    val number = match.groupValues[2].ifEmpty { match.groupValues[4] }.toIntOrNull()
    season == episode.season && number == episode.number
}
