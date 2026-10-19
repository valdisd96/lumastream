package dev.lumastream.catalog

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.util.Locale

class StremioSources(private val manifestUrl: String) {
    private val http = CatalogHttp("Source provider")

    suspend fun sources(details: CatalogDetails, episode: CatalogEpisode?): SourcePage {
        val imdb = details.imdbId?.takeIf { IMDB.matches(it) }
            ?: throw IOException("This title has no IMDb identifier. The source provider cannot resolve it.")
        if (details.title.kind == MediaKind.SERIES) {
            require(episode != null && episode.number > 0 && details.seasons.any { it.number == episode.season }) {
                "Select an episode of this series before searching sources."
            }
        } else require(episode == null) { "A movie cannot have an episode selection." }
        val manifest = manifestUri()
        val data = http.get(manifest.toASCIIString())
        val id = if (episode == null) imdb else "$imdb:${episode.season}:${episode.number}"
        val type = details.title.kind.streamType
        if (!supports(data, type, id)) throw IOException("This provider does not support IMDb streams for this media type.")
        val prefix = manifest.toASCIIString().removeSuffix("manifest.json")
        val response = http.get("${prefix}stream/$type/$id.json")
        val streams = response.optJSONArray("streams") ?: throw IOException("Source provider returned no stream list.")
        if (streams.length() > MAX_STREAMS) throw IOException("Source provider returned more than $MAX_STREAMS entries. Narrow its configuration.")
        val provider = data.catalogText("name", 128).ifEmpty { "Stremio provider" }
        val sources = ArrayList<CatalogSource>(streams.length())
        var unsupported = 0
        var mismatched = 0
        for (index in 0 until streams.length()) {
            val stream = streams.optJSONObject(index)
            if (stream == null) { unsupported++; continue }
            val hash = stream.catalogText("infoHash", 128).lowercase(Locale.ROOT)
            val fileIndex = stream.catalogInteger("fileIdx")?.takeIf { it <= Int.MAX_VALUE }?.toInt()
            if (!HASH.matches(hash) || (stream.has("fileIdx") && !stream.isNull("fileIdx") && fileIndex == null)) {
                unsupported++; continue
            }
            val hints = stream.optJSONObject("behaviorHints")
            val rawFilename = hints?.opt("filename") as? String
            val filename = rawFilename?.takeIf { it.length in 1..1024 && it.none { char -> char.isISOControl() } }
            if (rawFilename != null && filename == null) { unsupported++; continue }
            // Provider labels are not identity proof. Reject contrary filenames before any ranking.
            if (filename != null && contradicts(filename, details, episode)) { mismatched++; continue }
            val name = stream.catalogText("name", 256)
            val description = stream.catalogText("description").ifEmpty { stream.catalogText("title") }
            val metadata = listOfNotNull(filename, name, description).joinToString("\n")
            val quality = QUALITY.find(metadata)?.groupValues?.get(1)?.lowercase(Locale.ROOT)?.let {
                if (it == "4k") "2160p" else it
            }
            val size = hints?.catalogInteger("videoSize")?.takeIf { it > 0 }
                ?: stream.catalogInteger("size")?.takeIf { it > 0 }
                ?: parseSize(description)
            val seeders = (stream.catalogInteger("seeders") ?: stream.catalogInteger("seeds"))
                ?.takeIf { it <= Int.MAX_VALUE }?.toInt()
                ?: SEEDERS.find(description)?.groupValues?.get(1)?.toIntOrNull()
            val source = CatalogSource(provider, hash, fileIndex, filename,
                name.ifEmpty { "Torrent source" }, description, quality, languages(stream, metadata), size, seeders,
                trackers(stream.optJSONArray("sources")))
            sources.add(source)
        }
        if (sources.isEmpty() && mismatched > 0) {
            throw IOException("Provider sources name a different year or episode. No matching torrent source was accepted.")
        }
        return SourcePage(mergeSourceRecords(sources).sortedWith(
            compareBy<CatalogSource> { qualityRank(it.quality) }
                .thenByDescending { it.seeders ?: -1 }
                .thenBy { it.sizeBytes ?: Long.MAX_VALUE }
                .thenBy { it.infoHash }.thenBy { it.fileIndex ?: -1 },
        ), unsupported)
    }

    private fun manifestUri(): URI {
        val uri = try { URI(manifestUrl) } catch (_: Exception) { null }
        if (uri == null || manifestUrl.length > 4096 || manifestUrl.any { it.isISOControl() || it.isWhitespace() } ||
            !uri.scheme.equals("https", true) || uri.host.isNullOrBlank() || uri.rawUserInfo != null ||
            uri.rawQuery != null || uri.rawFragment != null || uri.port !in -1..65535 || uri.port == 0 ||
            !uri.rawPath.orEmpty().endsWith("/manifest.json") ||
            uri.path.split('/').any { it == "." || it == ".." || it.any(Char::isISOControl) } ||
            uri.rawPath.contains(Regex("(?i)%2f|%5c")) || uri.path.contains('\\')) {
            throw IOException("Configure a valid HTTPS Stremio manifest URL ending in /manifest.json, without query, fragment or user information.")
        }
        return uri
    }

    private fun supports(manifest: JSONObject, type: String, id: String): Boolean {
        val resources = manifest.optJSONArray("resources") ?: throw IOException("Provider manifest is missing resources.")
        if (resources.length() > 100) throw IOException("Provider manifest has too many resources.")
        for (index in 0 until resources.length()) {
            val resource = resources.opt(index)
            val definition = when {
                resource == "stream" -> manifest
                resource is JSONObject && resource.catalogText("name") == "stream" -> resource
                else -> continue
            }
            val types = definition.optJSONArray("types") ?: continue
            if (!types.containsString(type)) continue
            if (!definition.has("idPrefixes")) return true
            val prefixes = definition.optJSONArray("idPrefixes") ?: continue
            for (prefixIndex in 0 until minOf(prefixes.length(), 100)) {
                val prefix = prefixes.opt(prefixIndex) as? String ?: continue
                if (id.startsWith(prefix)) return true
            }
        }
        return false
    }

    private fun contradicts(filename: String, details: CatalogDetails, episode: CatalogEpisode?): Boolean {
        val base = filename.substringAfterLast('/').substringAfterLast('\\')
        val markers = EPISODE.findAll(base).toList()
        if (episode == null) {
            if (markers.isNotEmpty() || EPISODE_X.containsMatchIn(base)) return true
            val years = YEAR.findAll(base).map { it.groupValues[1] }.toList()
            return details.title.year.isNotEmpty() && years.isNotEmpty() && details.title.year !in years
        }
        if (markers.any { it.groupValues[1].toIntOrNull() != episode.season || it.groupValues[2].toIntOrNull() != episode.number }) return true
        val alternate = EPISODE_X.findAll(base).toList()
        if (alternate.any { it.groupValues[1].toIntOrNull() != episode.season || it.groupValues[2].toIntOrNull() != episode.number }) return true
        // A season pack without an episode marker is not rejected: playback must inspect its files.
        return SEASON.findAll(base).any { it.groupValues[1].toIntOrNull() != episode.season }
    }

    private fun trackers(values: JSONArray?): List<String> = buildList {
        if (values == null) return@buildList
        for (index in 0 until minOf(values.length(), 100)) {
            val raw = values.opt(index) as? String ?: continue
            if (!raw.startsWith("tracker:")) continue
            val tracker = raw.removePrefix("tracker:")
            if (tracker.length > 2048 || tracker.any { it.isWhitespace() || it.isISOControl() }) continue
            val uri = runCatching { URI(tracker) }.getOrNull() ?: continue
            if (uri.scheme?.lowercase(Locale.ROOT) !in setOf("https", "http", "udp") || uri.host.isNullOrBlank() ||
                uri.rawUserInfo != null || uri.rawFragment != null || uri.port !in -1..65535 || uri.port == 0) continue
            add(tracker)
        }
    }.distinct()

    private fun languages(stream: JSONObject, metadata: String): List<String> {
        val values = stream.optJSONArray("languages")
        val result = linkedSetOf<String>()
        if (values != null) for (index in 0 until minOf(values.length(), 30)) {
            val language = values.opt(index) as? String ?: continue
            if (language.matches(Regex("[A-Za-z]{2,3}(-[A-Za-z]{2})?"))) result.add(language.lowercase(Locale.ROOT))
        }
        LANGUAGE_MARKERS.forEach { (pattern, language) -> if (pattern.containsMatchIn(metadata)) result.add(language) }
        return result.toList()
    }

    private fun parseSize(text: String): Long? {
        val match = SIZE.find(text) ?: return null
        val number = match.groupValues[1].toDoubleOrNull() ?: return null
        val unit = match.groupValues[2].uppercase(Locale.ROOT)
        val multiplier = when (unit) {
            "KB" -> 1e3; "MB" -> 1e6; "GB" -> 1e9; "TB" -> 1e12
            "KIB" -> 1024.0; "MIB" -> 1048576.0; "GIB" -> 1073741824.0; "TIB" -> 1099511627776.0
            else -> return null
        }
        val size = number * multiplier
        return size.takeIf { it.isFinite() && it > 0 && it < Long.MAX_VALUE.toDouble() }?.toLong()
    }

    private fun qualityRank(quality: String?): Int = when (quality) {
        "1080p" -> 0; "720p" -> 1; "480p" -> 2; "2160p" -> 3; else -> 4
    }

    private fun JSONArray.containsString(value: String): Boolean =
        (0 until minOf(length(), 100)).any { opt(it) == value }

    private companion object {
        const val MAX_STREAMS = 500
        val IMDB = Regex("tt[0-9]{7,12}")
        val HASH = Regex("[a-f0-9]{40}")
        val QUALITY = Regex("(?i)(?<![a-z0-9])(2160p|1080p|720p|480p|4k)(?![a-z0-9])")
        val YEAR = Regex("(?<![0-9])((?:19|20)[0-9]{2})(?![0-9])")
        val EPISODE = Regex("(?i)(?<![a-z0-9])s([0-9]{1,4})[ ._-]*e([0-9]{1,4})(?![0-9])")
        val EPISODE_X = Regex("(?i)(?<![a-z0-9])([0-9]{1,3})x([0-9]{1,4})(?![0-9])")
        val SEASON = Regex("(?i)(?<![a-z0-9])s([0-9]{1,4})(?![0-9])")
        val SEEDERS = Regex("(?:\\x{1F464}|(?i:seeders|seeds)\\s*[:=]?)\\s*([0-9]+)(?![0-9.,])")
        val SIZE = Regex("(?i)(?<![0-9.])([0-9]+(?:\\.[0-9]+)?)\\s*(KiB|MiB|GiB|TiB|KB|MB|GB|TB)\\b")
        val LANGUAGE_MARKERS = listOf(
            Regex("(?i)\\b(?:russian|rus)\\b|\\x{1F1F7}\\x{1F1FA}") to "ru",
            Regex("(?i)\\b(?:english|eng)\\b|\\x{1F1EC}\\x{1F1E7}|\\x{1F1FA}\\x{1F1F8}") to "en",
            Regex("(?i)\\b(?:french|fra|fre)\\b|\\x{1F1EB}\\x{1F1F7}") to "fr",
            Regex("(?i)\\b(?:german|deu|ger)\\b|\\x{1F1E9}\\x{1F1EA}") to "de",
            Regex("(?i)\\b(?:spanish|spa)\\b|\\x{1F1EA}\\x{1F1F8}") to "es",
            Regex("(?i)\\b(?:italian|ita)\\b|\\x{1F1EE}\\x{1F1F9}") to "it",
        )
    }
}
