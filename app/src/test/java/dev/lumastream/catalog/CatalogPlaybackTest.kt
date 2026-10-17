package dev.lumastream.catalog

import dev.lumastream.torrent.TorrentFile
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CatalogPlaybackTest {
    private val title = CatalogTitle(1, MediaKind.SERIES, "The Studio", "The Studio", "2025", "", null, null)
    private val details = CatalogDetails(title, "tt23649128", listOf(CatalogSeason(1, "Season 1", 10)), 30)
    private val episode = CatalogEpisode(2, 1, 2, "Episode 2", "", null, "")
    private val source = CatalogSource("Provider", "a".repeat(40), null, null, "", "", null, emptyList(), null, null, emptyList())

    @Test fun seasonPackSelectsRequestedEpisodeRatherThanLargestFile() {
        val files = listOf(TorrentFile(3, "Show/The_Studio_S01E01.mkv", 9000), TorrentFile(7, "Show/The_Studio_S01E02.mkv", 3000))
        assertEquals(7, selectCatalogFile(CatalogSelection(details, episode, source), files).index)
    }

    @Test fun explicitIndexCannotOverrideContradictoryEpisode() {
        val wrong = "The.Studio.S01E03.mkv"
        val selection = CatalogSelection(details, episode, source.copy(fileIndex = 4, filename = wrong))
        assertThrows(IOException::class.java) { selectCatalogFile(selection, listOf(TorrentFile(4, wrong, 3000))) }
    }

    @Test fun providerFilenameMustMatchActualIndexedFile() {
        val selection = CatalogSelection(details, episode, source.copy(fileIndex = 4, filename = "The.Studio.S01E02.mkv"))
        assertThrows(IOException::class.java) {
            selectCatalogFile(selection, listOf(TorrentFile(4, "Unrelated.S01E02.mkv", 3000)))
        }
    }

    @Test fun ambiguousEpisodeVersionsRequireAnotherSource() {
        val files = listOf(TorrentFile(0, "The.Studio.1x02.720p.mkv", 3000), TorrentFile(1, "The.Studio.1x02.1080p.mkv", 6000))
        assertThrows(IOException::class.java) { selectCatalogFile(CatalogSelection(details, episode, source), files) }
    }

    @Test fun unknownEpisodeFilenameDoesNotSelectSingleFileBySize() {
        assertThrows(IOException::class.java) {
            selectCatalogFile(CatalogSelection(details, episode, source), listOf(TorrentFile(0, "random-name.mp4", 3000)))
        }
    }

    @Test fun remakeYearMismatchIsRejectedEvenWithMatchingProviderFilename() {
        val movie = details.copy(title = title.copy(kind = MediaKind.MOVIE, title = "Example", originalTitle = "Example", year = "2014"), seasons = emptyList())
        val filename = "Example.2025.1080p.mp4"
        assertThrows(IOException::class.java) {
            selectCatalogFile(CatalogSelection(movie, null, source.copy(fileIndex = 0, filename = filename)), listOf(TorrentFile(0, filename, 3000)))
        }
    }

    @Test fun fileIndexUsesTorrentIndexNotPositionInFilteredVideoList() {
        val selection = CatalogSelection(details, episode, source.copy(fileIndex = 7, filename = "The.Studio.S01E02.mkv"))
        val files = listOf(TorrentFile(3, "The.Studio.S01E01.mkv", 9000), TorrentFile(7, "The.Studio.S01E02.mkv", 3000))
        assertEquals(7, selectCatalogFile(selection, files).index)
    }
}
