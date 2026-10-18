package dev.lumastream.torrent

import java.util.Locale

const val MAX_TORRENT_SUBTITLE_BYTES = 2 * 1024 * 1024

enum class SubtitleMatchConfidence { EXACT_BASENAME, VIDEO_DIRECTORY, EPISODE }

data class TorrentSubtitleCandidate(
    val index: Int,
    val name: String,
    val size: Long,
    /** A positively identified language, never an assumption based on the video or device. */
    val language: String?,
    val confidence: SubtitleMatchConfidence,
)

/** Multiple versions (including forced/SDH) require an explicit choice. */
fun subtitleToAutoSelect(
    candidates: List<TorrentSubtitleCandidate>,
    language: String = "en",
): TorrentSubtitleCandidate? = candidates.filter { it.language == language.lowercase(Locale.ROOT) }.singleOrNull()

/** Only metadata is inspected here; discovery never requests any torrent pieces. */
internal fun discoverTorrentSubtitles(
    selected: TorrentFile,
    videos: List<TorrentFile>,
    files: List<TorrentFile>,
): List<TorrentSubtitleCandidate> {
    val videoNames = videos.map { VideoName(it, words(it.name.substringAfterLast('/').substringBeforeLast('.'))) }
    return files.mapNotNull { file ->
        if (file.size !in 1..MAX_TORRENT_SUBTITLE_BYTES.toLong() ||
            file.name.substringAfterLast('.', "").lowercase(Locale.ROOT) !in SUBTITLE_EXTENSIONS
        ) return@mapNotNull null
        val components = file.name.split('/')
        val stem = words(components.last().substringBeforeLast('.'))
        val folders = components.dropLast(1).map(::words)
        val parentEpisodes = components.dropLast(1).map(::episodes)
        val subtitleEpisodes = episodes(components.last()).ifEmpty {
            parentEpisodes.lastOrNull { it.isNotEmpty() } ?: emptySet()
        }
        val matches = videoNames.mapNotNull { video ->
            match(video, stem, folders, subtitleEpisodes, parentEpisodes)?.let { video.file.index to it }
        }
        val best = matches.minOfOrNull { it.second.confidence.ordinal } ?: return@mapNotNull null
        val owners = matches.filter { it.second.confidence.ordinal == best }
        // An episode number or repeated basename is not sufficient to choose between releases.
        val owner = owners.singleOrNull()?.takeIf { it.first == selected.index } ?: return@mapNotNull null
        TorrentSubtitleCandidate(file.index, file.name, file.size, owner.second.language, owner.second.confidence)
    }.sortedWith(compareBy<TorrentSubtitleCandidate> { it.confidence.ordinal }.thenBy { it.name }.thenBy { it.index })
}

private data class VideoName(val file: TorrentFile, val stem: List<String>) {
    val basename = words(file.name.substringAfterLast('/'))
    val episode = file.name.split('/').asReversed().firstNotNullOfOrNull {
        episodes(it).takeIf { identity -> identity.isNotEmpty() }
    } ?: emptySet()
}

private data class SubtitleMatch(val confidence: SubtitleMatchConfidence, val language: String?)

private fun match(
    video: VideoName,
    stem: List<String>,
    folders: List<List<String>>,
    subtitleEpisodes: Set<Pair<Int, Int>>,
    parentEpisodes: List<Set<Pair<Int, Int>>>,
): SubtitleMatch? {
    if (subtitleEpisodes.isNotEmpty() && subtitleEpisodes != video.episode) return null
    if (parentEpisodes.any { it.isNotEmpty() && !it.containsAll(video.episode) }) return null
    // A subtitle without episode identity may still match an exact video-name directory.
    val suffix = suffixAfter(stem, video.basename) ?: suffixAfter(stem, video.stem)
    if (suffix != null && subtitleSuffix(suffix)) {
        return SubtitleMatch(SubtitleMatchConfidence.EXACT_BASENAME, language(suffix, folders, video.stem))
    }
    if (folders.any { it == video.stem || it == video.basename } && subtitleSuffix(stem)) {
        return SubtitleMatch(SubtitleMatchConfidence.VIDEO_DIRECTORY, language(stem, folders, video.stem))
    }
    if (subtitleEpisodes.size != 1 || video.episode.size != 1 || subtitleEpisodes != video.episode) return null
    val subtitlePrefix = episodePrefix(stem)
    val videoPrefix = episodePrefix(video.stem)
    // Do not pair different series that happen to contain the same episode number.
    if (subtitlePrefix.isNotEmpty() && videoPrefix.isNotEmpty() && subtitlePrefix != videoPrefix) return null
    val episodeEnd = EPISODE_TOKEN.findAll(stem.joinToString(" ")).lastOrNull()?.range?.last ?: return null
    val suffixWords = words(stem.joinToString(" ").substring(episodeEnd + 1))
    return SubtitleMatch(SubtitleMatchConfidence.EPISODE, language(suffixWords, folders, video.stem))
}

private fun suffixAfter(value: List<String>, prefix: List<String>): List<String>? =
    if (prefix.isNotEmpty() && value.size >= prefix.size && value.subList(0, prefix.size) == prefix) value.drop(prefix.size) else null

private fun subtitleSuffix(tokens: List<String>): Boolean = tokens.all {
    it in LANGUAGE_NAMES || it in SUBTITLE_LABELS || it.all(Char::isDigit)
}

private fun language(tokens: List<String>, folders: List<List<String>>, videoStem: List<String>): String? {
    val names = tokens.mapNotNull { LANGUAGE_NAMES[it] }.toMutableSet()
    for (index in 1 until folders.size) {
        val folder = folders[index]
        val parent = folders[index - 1]
        if (folder.size == 1 && folder != videoStem &&
            (parent == videoStem || (parent.size == 1 && parent.single() in SUBTITLE_FOLDERS))
        ) LANGUAGE_NAMES[folder.single()]?.let(names::add)
    }
    return names.singleOrNull()
}

private fun words(value: String): List<String> = WORD.findAll(value.lowercase(Locale.ROOT)).map { it.value }.toList()

private fun episodes(path: String): Set<Pair<Int, Int>> = buildSet {
    for (match in EPISODE.findAll(path)) {
        val season = match.groupValues[1].ifEmpty { match.groupValues[3] }.toInt()
        val episode = match.groupValues[2].ifEmpty { match.groupValues[4] }.toInt()
        add(season to episode)
        var end = match.range.last + 1
        var previous = episode
        while (end < path.length) {
            val continuation = EPISODE_CONTINUATION.find(path.substring(end)) ?: break
            val next = continuation.groupValues[1].toInt()
            if ('-' in continuation.value && next >= previous) {
                for (value in previous..next) add(season to value)
            } else {
                add(season to next)
            }
            previous = next
            end += continuation.value.length
        }
    }
}

private fun episodePrefix(tokens: List<String>): List<String> {
    val text = tokens.joinToString(" ")
    val match = EPISODE_TOKEN.find(text) ?: return emptyList()
    return words(text.substring(0, match.range.first))
}

private val SUBTITLE_EXTENSIONS = setOf("srt", "vtt")
private val WORD = Regex("[\\p{L}\\p{N}]+")
private val EPISODE = Regex("(?i)(?<![a-z0-9])s(\\d{1,3})[ ._-]*e(\\d{1,3})(?!\\d)|(?<![a-z0-9])(\\d{1,3})x(\\d{1,3})(?!\\d)")
private val EPISODE_TOKEN = Regex("(?i)\\bs\\d{1,3} ?e\\d{1,3}\\b|\\b\\d{1,3}x\\d{1,3}\\b")
private val EPISODE_CONTINUATION = Regex("(?i)^(?:[ ._]*e|[ ._]*-[ ._]*e?)(\\d{1,3})(?![a-df-z0-9])")
private val SUBTITLE_LABELS = setOf("forced", "sdh", "cc", "full", "sub", "subs", "subtitle", "subtitles", "us", "uk", "gb", "br", "und", "unknown")
private val SUBTITLE_FOLDERS = setOf("subs", "subtitles")
private val LANGUAGE_NAMES = mapOf(
    "en" to "en", "eng" to "en", "english" to "en",
    "ru" to "ru", "rus" to "ru", "russian" to "ru",
    "uk" to "uk", "ukr" to "uk", "ukrainian" to "uk",
    "de" to "de", "deu" to "de", "ger" to "de", "german" to "de",
    "fr" to "fr", "fra" to "fr", "fre" to "fr", "french" to "fr",
    "es" to "es", "spa" to "es", "spanish" to "es",
    "it" to "it", "ita" to "it", "italian" to "it",
    "pt" to "pt", "por" to "pt", "portuguese" to "pt",
    "ja" to "ja", "jpn" to "ja", "japanese" to "ja",
    "ko" to "ko", "kor" to "ko", "korean" to "ko",
    "zh" to "zh", "zho" to "zh", "chi" to "zh", "chinese" to "zh",
    "ar" to "ar", "ara" to "ar", "arabic" to "ar",
    "hi" to "hi", "hin" to "hi", "hindi" to "hi",
)
