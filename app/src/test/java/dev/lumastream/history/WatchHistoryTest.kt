package dev.lumastream.history

import dev.lumastream.catalog.CatalogDetails
import dev.lumastream.catalog.CatalogEpisode
import dev.lumastream.catalog.CatalogSeason
import dev.lumastream.catalog.CatalogSelection
import dev.lumastream.catalog.CatalogSource
import dev.lumastream.catalog.CatalogTitle
import dev.lumastream.catalog.MediaKind
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WatchHistoryTest {
    @get:Rule val directory = TemporaryFolder()
    private val file: File get() = File(directory.root, "watch-history.json")
    private val title = CatalogTitle(42, MediaKind.SERIES, "A title 日本語", "Original", "2025", "Overview\nDetails", "/poster.jpg", "/backdrop.jpg", originalLanguage = "ja")
    private val details = CatalogDetails(title, "tt1234567", listOf(CatalogSeason(0, "Specials", 2), CatalogSeason(1, "First", 10)), 45)
    private val episode = CatalogEpisode(99, 1, 2, "Second", "Episode overview", "/still.jpg", "2025-02-03")
    private val source = CatalogSource("Provider", "a".repeat(40), 7, "Show.S01E02.mkv", "1080p", "Description\nMore",
        "1080p", listOf("en", "ja"), 5_000_000_000L, 123, listOf("udp://tracker.example:1337/announce", "https://tracker.example/announce"))
    private val entry = WatchEntry(CatalogSelection(details, episode, source), 3_000_000_000L, 4_000_000_000L, 1_800_000_000_000L, false)

    @Test fun legacyHistoryWithoutOriginalLanguageDoesNotGuessEnglish() = runBlocking {
        WatchHistoryStore(file).save(listOf(entry))
        val root = org.json.JSONObject(file.readText())
        root.getJSONArray("entries").getJSONObject(0).getJSONObject("selection")
            .getJSONObject("details").getJSONObject("title").remove("originalLanguage")
        file.writeText(root.toString())
        val restored = WatchHistoryStore(file).load().single()
        assertEquals(entry.copy(selection = entry.selection.copy(
            details = details.copy(title = title.copy(originalLanguage = null)),
        )), restored)
    }

    @Test fun absentHistoryIsEmpty() = runBlocking {
        assertEquals(emptyList<WatchEntry>(), WatchHistoryStore(file).load())
        assertFalse(file.exists())
    }

    @Test fun allSelectionFieldsAndPositionsSurviveStoreRecreation() = runBlocking {
        val movie = entry.copy(
            selection = CatalogSelection(
                details.copy(title = title.copy(kind = MediaKind.MOVIE, posterPath = null, backdropPath = null),
                    imdbId = null, seasons = emptyList(), runtimeMinutes = null),
                null,
                source.copy(fileIndex = null, filename = null, quality = null, languages = emptyList(),
                    sizeBytes = null, seeders = null, trackers = emptyList()),
            ),
            updatedAtMs = entry.updatedAtMs - 1,
            completed = true,
        )
        WatchHistoryStore(file).save(listOf(movie, entry))
        assertEquals(listOf(entry, movie), WatchHistoryStore(file).load())
    }

    @Test fun identitiesSeparateMediaKindsAndEpisodesButNotSources() {
        val movie = entry.copy(selection = entry.selection.copy(details = details.copy(title = title.copy(kind = MediaKind.MOVIE)), episode = null))
        val nextEpisode = entry.copy(selection = entry.selection.copy(episode = episode.copy(number = 3)))
        val nextSeason = entry.copy(selection = entry.selection.copy(episode = episode.copy(season = 2)))
        assertEquals(4, setOf(entry.key, movie.key, nextEpisode.key, nextSeason.key).size)
        assertEquals(entry.titleKey, nextEpisode.titleKey)
        assertEquals(entry.titleKey, nextSeason.titleKey)
        assertFalse(entry.titleKey == movie.titleKey)
        assertEquals(entry.key, entry.copy(selection = entry.selection.copy(source = source.copy(infoHash = "b".repeat(40)))).key)
    }

    @Test fun keepsLatestHundredDistinctEpisodesRatherThanHundredTitles() = runBlocking {
        val entries = (1..105).map { number ->
            entry.copy(selection = entry.selection.copy(episode = episode.copy(number = number)), updatedAtMs = number.toLong())
        }
        val newest = entries.last().copy(positionMs = 1234, updatedAtMs = 106)
        WatchHistoryStore(file).save(listOf(newest) + entries.reversed())
        val loaded = WatchHistoryStore(file).load()
        assertEquals(listOf(newest) + entries.drop(5).dropLast(1).reversed(), loaded)
    }

    @Test fun historyCardsGroupTitlesByRecencyWithoutLosingEpisodeResumePoints() = runBlocking {
        val earlierEpisodePlayedLast = entry.copy(updatedAtMs = 400, positionMs = 1234)
        val laterEpisode = entry.copy(
            selection = entry.selection.copy(episode = episode.copy(number = 9)),
            updatedAtMs = 100, completed = true,
        )
        val nextSeason = entry.copy(
            selection = entry.selection.copy(episode = episode.copy(season = 2)),
            updatedAtMs = 200,
        )
        val sameIdMovie = entry.copy(
            selection = entry.selection.copy(details = details.copy(title = title.copy(kind = MediaKind.MOVIE)), episode = null),
            updatedAtMs = 300,
        )
        val anotherSeries = entry.copy(
            selection = entry.selection.copy(details = details.copy(title = title.copy(id = 43))),
            updatedAtMs = 500,
        )
        val entries = listOf(laterEpisode, sameIdMovie, nextSeason, anotherSeries, earlierEpisodePlayedLast)
        WatchHistoryStore(file).save(entries)
        val restored = WatchHistoryStore(file).load()
        assertEquals(listOf(anotherSeries, earlierEpisodePlayedLast, sameIdMovie), historyCards(restored))
        assertEquals(entries.sortedByDescending { it.updatedAtMs }, WatchHistoryStore(file).load())
        assertEquals(emptyList<WatchEntry>(), historyCards(emptyList()))
    }

    @Test fun groupedCardTracksLatestExactEpisodeEvenWhenCompletedAndKeepsTitleIdentity() {
        val next = entry.copy(
            selection = entry.selection.copy(episode = episode.copy(number = 3), source = source.copy(infoHash = "b".repeat(40))),
            updatedAtMs = entry.updatedAtMs + 1, completed = true,
        )
        val before = historyCards(listOf(entry)).single()
        val after = historyCards(listOf(entry, next)).single()
        assertEquals(next, after)
        assertEquals(before.titleKey, after.titleKey)
        assertEquals(listOf(entry), historyCards(removeHistoryEntry(listOf(entry, next), next.key)))
    }

    @Test fun manualCompletionAndResetAffectOnlyTheChosenEpisodeAndPersist() = runBlocking {
        val other = entry.copy(selection = entry.selection.copy(episode = episode.copy(number = 3)),
            updatedAtMs = entry.updatedAtMs - 1)
        val watched = setHistoryCompleted(listOf(entry, other), entry.key, true)
        WatchHistoryStore(file).save(watched)
        val restored = WatchHistoryStore(file).load()
        assertEquals(listOf(entry.copy(completed = true), other), restored)
        WatchHistoryStore(file).save(setHistoryCompleted(restored, entry.key, false))
        assertEquals(listOf(entry.copy(positionMs = 0, completed = false), other),
            WatchHistoryStore(file).load())
    }

    @Test fun removalKeepsOtherEpisodesAndSameIdMovieAndPersistsEmptyHistory() = runBlocking {
        val other = entry.copy(selection = entry.selection.copy(episode = episode.copy(number = 3)),
            updatedAtMs = entry.updatedAtMs - 1)
        val movie = entry.copy(selection = entry.selection.copy(
            details = details.copy(title = title.copy(kind = MediaKind.MOVIE)), episode = null),
            updatedAtMs = entry.updatedAtMs - 2)
        WatchHistoryStore(file).save(removeHistoryEntry(listOf(entry, other, movie), entry.key))
        assertEquals(listOf(other, movie), WatchHistoryStore(file).load())
        WatchHistoryStore(file).save(removeHistoryEntry(removeHistoryEntry(
            WatchHistoryStore(file).load(), other.key), movie.key))
        assertEquals(emptyList<WatchEntry>(), WatchHistoryStore(file).load())
    }

    @Test fun corruptHistoryReportsErrorWithoutClearingFile() {
        val corrupt = "{\"version\":1,\"entries\":[{"
        file.writeText(corrupt)
        assertThrows(IOException::class.java) { runBlocking { WatchHistoryStore(file).load() } }
        assertEquals(corrupt, file.readText())
    }

    @Test fun incompleteRecordsAndUnknownVersionsAreNotSilentlyDiscarded() {
        for (invalid in listOf("{\"version\":1,\"entries\":[{}]}", "{\"version\":2,\"entries\":[]}")) {
            file.writeText(invalid)
            assertThrows(IOException::class.java) { runBlocking { WatchHistoryStore(file).load() } }
            assertEquals(invalid, file.readText())
        }
    }

    @Test fun interruptedReplacementLeavesPreviousHistoryReadable() = runBlocking {
        WatchHistoryStore(file).save(listOf(entry))
        File(directory.root, "watch-history.json.new").writeText("{\"version\":1,\"entries\":[")
        assertEquals(listOf(entry), WatchHistoryStore(file).load())
        val updated = entry.copy(positionMs = 5678)
        WatchHistoryStore(file).save(listOf(updated))
        assertEquals(listOf(updated), WatchHistoryStore(file).load())
        assertFalse(File(directory.root, "watch-history.json.new").exists())
    }

    @Test fun failedReplacementPreservesPreviouslyCommittedHistory() = runBlocking {
        WatchHistoryStore(file).save(listOf(entry))
        val temporary = File(directory.root, "watch-history.json.new")
        assertTrue(temporary.mkdir())
        File(temporary, "obstacle").writeText("Prevent opening replacement as a file")
        assertThrows(IOException::class.java) {
            runBlocking { WatchHistoryStore(file).save(listOf(entry.copy(positionMs = 5678))) }
        }
        assertEquals(listOf(entry), WatchHistoryStore(file).load())
    }
}
