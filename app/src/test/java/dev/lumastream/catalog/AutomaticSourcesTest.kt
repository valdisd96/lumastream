package dev.lumastream.catalog

import org.junit.Assert.assertEquals
import org.junit.Test

class AutomaticSourcesTest {
    private fun source(
        hash: String,
        quality: String? = "1080p",
        languages: List<String> = listOf("en"),
        seeders: Int? = null,
        size: Long? = null,
        fileIndex: Int? = 0,
    ) = CatalogSource("Provider", hash, fileIndex, null, hash, "", quality,
        languages, size, seeders, emptyList())

    @Test fun staysWithinExplicitQualityAndExactLanguageSet() {
        val selected = source("selected", " 1080P ", listOf(" EN ", "fr", "en"))
        val sources = listOf(
            source("lower", "720p", listOf("en", "fr"), seeders = 100),
            source("different-language", languages = listOf("de"), seeders = 100),
            source("subset", languages = listOf("en"), seeders = 100),
            source("unknown-quality", null, listOf("en", "fr"), seeders = 100),
            source("unknown-language", languages = emptyList(), seeders = 100),
            selected,
        )
        assertEquals(listOf(selected), rankAutomaticSources(sources, "1080p", listOf("fr", "en")) { true })
        assertEquals(emptyList<CatalogSource>(), rankAutomaticSources(sources, "2160p", listOf("fr", "en")) { true })
        assertEquals(emptyList<CatalogSource>(), rankAutomaticSources(sources, "1080p", listOf("ja")) { true })
    }

    @Test fun unknownCohortDoesNotMatchKnownMetadata() {
        val unknown = source("unknown", null, emptyList())
        val blank = source("blank", "  ", listOf(" "))
        val sources = listOf(source("known"), source("known-quality", languages = emptyList()),
            source("known-language", null), unknown, blank)
        assertEquals(listOf(unknown, blank), rankAutomaticSources(sources, null, emptyList()) { true })
    }

    @Test fun excludesWarningSourcesWithoutFallingBackToAnotherCohort() {
        val warned = source("warned", seeders = 100)
        val safe = source("safe", seeders = 1)
        val lower = source("lower", quality = "720p", seeders = 500)
        assertEquals(listOf(safe), rankAutomaticSources(listOf(warned, safe, lower), "1080p", listOf("en")) {
            it.infoHash != "warned"
        })
        assertEquals(emptyList<CatalogSource>(), rankAutomaticSources(listOf(warned, lower), "1080p", listOf("en")) {
            it.infoHash != "warned"
        })
    }

    @Test fun positiveSeedReportsPrecedeUnknownAndZeroRegardlessOfSize() {
        val zero = source("zero", seeders = 0, size = 1)
        val unknown = source("unknown", size = 2)
        val positive = source("positive", seeders = 1, size = 100)
        val popular = source("popular", seeders = 20, size = 200)
        assertEquals(listOf(popular, positive, unknown, zero),
            rankAutomaticSources(listOf(zero, unknown, positive, popular), "1080p", listOf("en")) { true })
    }

    @Test fun usesOnlyPositiveReportedSizesToBreakSeederTies() {
        val unknown = source("unknown", seeders = 5)
        val zero = source("zero", seeders = 5, size = 0)
        val large = source("large", seeders = 5, size = 1000)
        val small = source("small", seeders = 5, size = 100)
        assertEquals(listOf(small, large, unknown, zero),
            rankAutomaticSources(listOf(unknown, large, zero, small), "1080p", listOf("en")) { true })
    }

    @Test fun deduplicatesCaseInsensitiveHashAndIndexBeforeApplyingCap() {
        val stale = source("ABC", seeders = 1)
        val best = source("abc", seeders = 100)
        val secondFile = source("ABC", seeders = 90, fileIndex = 1)
        val unspecifiedFile = source("abc", seeders = 80, fileIndex = null)
        val fourth = source("other", seeders = 70)
        val fifth = source("last", seeders = 60)
        assertEquals(listOf(best, secondFile, unspecifiedFile, fourth),
            rankAutomaticSources(listOf(stale, fifth, fourth, best, secondFile, unspecifiedFile),
                "1080p", listOf("en")) { true })
    }
}
