package dev.lumastream.catalog

import java.util.Locale

/** Metadata ordering is a heuristic, not a transfer-speed guarantee. */
internal fun rankAutomaticSources(
    sources: List<CatalogSource>,
    quality: String?,
    languages: List<String>,
    compatible: (CatalogSource) -> Boolean,
): List<CatalogSource> {
    val requestedQuality = normalizedQuality(quality)
    val requestedLanguages = normalizedLanguages(languages)
    val ranked = sources.asSequence()
        .filter { normalizedQuality(it.quality) == requestedQuality &&
            normalizedLanguages(it.languages) == requestedLanguages }
        .filter(compatible)
        .sortedWith(compareBy<CatalogSource> { seedCategory(it.seeders) }
            .thenByDescending { it.seeders?.takeIf { count -> count > 0 } ?: 0 }
            // Reported size may describe a whole pack; never estimate an episode size from it.
            .thenBy { it.sizeBytes?.takeIf { size -> size > 0 } ?: Long.MAX_VALUE })
        .distinctBy { it.infoHash.trim().lowercase(Locale.ROOT) to it.fileIndex }
        .toMutableList()
    val selected = ArrayList<CatalogSource>(minOf(4, ranked.size))
    val representedProviders = mutableSetOf<String>()
    while (selected.size < 4 && ranked.isNotEmpty()) {
        val bestCategory = seedCategory(ranked.first().seeders)
        // Diversify healthy options without promoting dead torrents over positive/unknown reports.
        val diverseIndex = if (selected.isEmpty()) -1 else ranked.indexOfFirst { candidate ->
            seedCategory(candidate.seeders) == bestCategory &&
                candidate.providerIds.any { it !in representedProviders }
        }
        val next = ranked.removeAt(if (diverseIndex < 0) 0 else diverseIndex)
        selected.add(next)
        representedProviders.addAll(next.providerIds)
    }
    return selected
}

private fun normalizedQuality(quality: String?): String? =
    quality?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }

private fun normalizedLanguages(languages: List<String>): List<String> = languages.asSequence()
    .map { it.trim().lowercase(Locale.ROOT) }
    .filter { it.isNotEmpty() }
    .distinct()
    .sorted()
    .toList()

private fun seedCategory(seeders: Int?): Int = when {
    seeders == null || seeders < 0 -> 1
    seeders == 0 -> 2
    else -> 0
}
