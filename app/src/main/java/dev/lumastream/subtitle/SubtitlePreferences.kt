package dev.lumastream.subtitle

import dev.lumastream.catalog.ORIGINAL_AUDIO_LANGUAGE
import java.util.Locale

/** Language tags from containers commonly use either ISO 639-1 or 639-2. */
fun normalizeTrackLanguage(language: String?): String? {
    val base = language?.trim()?.lowercase(Locale.ROOT)?.substringBefore('-')?.substringBefore('_')
        ?.takeIf { it.isNotEmpty() && it != "und" && it != "zxx" } ?: return null
    return when (base) {
        "eng" -> "en"
        "rus" -> "ru"
        "ukr" -> "uk"
        "deu", "ger" -> "de"
        "fra", "fre" -> "fr"
        "spa" -> "es"
        "ita" -> "it"
        "por" -> "pt"
        "jpn" -> "ja"
        "kor" -> "ko"
        "zho", "chi" -> "zh"
        else -> base.takeIf { it.length in 2..3 && it.all { character -> character in 'a'..'z' } }
    }
}

fun matchesSubtitleLanguage(trackLanguage: String?, preference: String): Boolean {
    val desired = normalizeTrackLanguage(preference) ?: return false
    return normalizeTrackLanguage(trackLanguage) == desired
}

/** Null means preserve the source's default, never substitute English for unknown metadata. */
fun preferredAudioLanguage(preference: String, originalLanguage: String?): String? =
    normalizeTrackLanguage(if (preference == ORIGINAL_AUDIO_LANGUAGE) originalLanguage else preference)
