package dev.lumastream.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TorrentSubtitlesTest {
    @Test fun seasonPackDirectoryDoesNotReplaceExactLeafEpisodeIdentity() {
        val selected = video(0, "Show.S01E01-E03/Show.S01E02.1080p.mkv")
        val found = discover(selected, listOf(selected),
            subtitle(1, "Show.S01E01-E03/Subs/Show.S01E02.en.srt"),
            subtitle(2, "Show.S01E01-E03/Subs/Show.S01E03.en.srt"),
        )
        assertEquals(listOf(1), found.map { it.index })
        assertEquals("en", found.single().language)
    }

    @Test fun fullVideoFilenameLayoutsAndUnknownLanguageLabelsRemainSelectable() {
        val selected = video(0, "Release/Film.mkv")
        val found = discover(selected, listOf(selected),
            subtitle(1, "Release/Film.mkv.en.srt"),
            subtitle(2, "Release/Subs/Film.mkv/English.vtt"),
            subtitle(3, "Release/Film.und.srt"),
        )
        assertEquals(setOf(1, 2, 3), found.map { it.index }.toSet())
        assertEquals("en", found.single { it.index == 1 }.language)
        assertEquals("en", found.single { it.index == 2 }.language)
        assertNull(found.single { it.index == 3 }.language)
        assertNull(subtitleToAutoSelect(found))
    }

    @Test fun exactBasenameIdentifiesLanguageWithoutAssumingUnknownIsEnglish() {
        val video = video(4, "Release/Film.2024.1080p.mkv")
        val found = discover(video, listOf(video),
            subtitle(9, "Release/Film.2024.1080p.en.srt"),
            subtitle(13, "Release/Film.2024.1080p.ru.vtt"),
            subtitle(20, "Release/Film.2024.1080p.srt"),
        )
        assertEquals(setOf(9, 13, 20), found.map { it.index }.toSet())
        assertEquals("en", found.single { it.index == 9 }.language)
        assertEquals("ru", found.single { it.index == 13 }.language)
        assertNull(found.single { it.index == 20 }.language)
        assertEquals(9, subtitleToAutoSelect(found)?.index)
        assertEquals(13, subtitleToAutoSelect(found, "ru")?.index)
        assertNull(subtitleToAutoSelect(found, "de"))
    }

    @Test fun subsVideoNameLayoutMatchesOnlyItsVideoAndSupportsNumberedLanguageFiles() {
        val selected = video(0, "Season/Show.S01E02.1080p.mkv")
        val other = video(1, "Season/Show.S01E03.1080p.mkv")
        val found = discover(selected, listOf(selected, other),
            subtitle(2, "Season/Subs/Show.S01E02.1080p/2_English.srt"),
            subtitle(3, "Season/Subs/Show.S01E03.1080p/2_English.srt"),
            subtitle(4, "Season/Subs/English.srt"),
        )
        assertEquals(listOf(2), found.map { it.index })
        assertEquals(SubtitleMatchConfidence.VIDEO_DIRECTORY, found.single().confidence)
        assertEquals("en", found.single().language)
    }

    @Test fun episodeMatchingRejectsOtherSeasonsEpisodesAndSeries() {
        val selected = video(0, "Season/Show.S01E02.1080p-GROUP.mkv")
        val other = video(1, "Season/Show.S01E03.1080p-GROUP.mkv")
        val found = discover(selected, listOf(selected, other),
            subtitle(2, "Season/Subs/Show.S01E02.English.srt"),
            subtitle(3, "Season/Subs/Show.S01E03.English.srt"),
            subtitle(4, "Season/Subs/Show.S02E02.English.srt"),
            subtitle(5, "Season/Subs/Another.Show.S01E02.English.srt"),
            subtitle(6, "Season/Subs/1x02.en.vtt"),
        )
        assertEquals(setOf(2, 6), found.map { it.index }.toSet())
        assertTrue(found.all { it.confidence == SubtitleMatchConfidence.EPISODE })
        assertNull(subtitleToAutoSelect(found))
    }

    @Test fun contradictoryParentEpisodeOverridesAnExactFilename() {
        val selected = video(0, "Show.S01E02.mkv")
        val found = discover(selected, listOf(selected),
            subtitle(1, "Subs/Show.S01E03/Show.S01E02.en.srt"),
        )
        assertTrue(found.isEmpty())
    }

    @Test fun genericFilesAreNotAssignedEvenWhenThereIsOnlyOneVideo() {
        val selected = video(0, "Release/Film.mkv")
        assertTrue(discover(selected, listOf(selected),
            subtitle(1, "Release/Subs/English.srt"),
            subtitle(2, "Release/Subs/1.srt"),
            subtitle(3, "Release/en.vtt"),
        ).isEmpty())
    }

    @Test fun ambiguousEpisodeOrRepeatedBasenameRequiresNoAutomaticAssociation() {
        val selected = video(0, "ReleaseA/Show.S01E02.1080p.mkv")
        val other = video(1, "ReleaseB/Show.S01E02.720p.mkv")
        assertTrue(discover(selected, listOf(selected, other),
            subtitle(2, "Subs/Show.S01E02.en.srt"),
        ).isEmpty())
        val repeated = video(3, "ReleaseC/Show.S01E02.1080p.mkv")
        assertTrue(discover(selected, listOf(selected, repeated),
            subtitle(4, "Subs/Show.S01E02.1080p.en.srt"),
        ).isEmpty())
    }

    @Test fun exactReleaseMatchWinsOverAnotherReleaseOfTheSameEpisode() {
        val selected = video(0, "Show.S01E02.1080p.mkv")
        val other = video(1, "Show.S01E02.720p.mkv")
        val found = discover(selected, listOf(selected, other),
            subtitle(2, "Show.S01E02.1080p.en.srt"),
            subtitle(3, "Show.S01E02.720p.en.srt"),
        )
        assertEquals(listOf(2), found.map { it.index })
        assertEquals(SubtitleMatchConfidence.EXACT_BASENAME, found.single().confidence)
    }

    @Test fun multiEpisodeNamesAndRangesCannotMatchASingleEpisode() {
        val selected = video(0, "Show.S01E02.mkv")
        assertTrue(discover(selected, listOf(selected),
            subtitle(1, "Show.S01E02E03.en.srt"),
            subtitle(2, "Show.S01E02-04.en.srt"),
            subtitle(3, "Show.S01E01E02E03.en.srt"),
        ).isEmpty())
        val pair = video(4, "Show.S01E01E02.mkv")
        assertTrue(discover(pair, listOf(pair), subtitle(5, "Show.S01E01E02E03.en.srt")).isEmpty())
    }

    @Test fun titleAndReleaseRootAreNotPositiveLanguageEvidence() {
        val selected = video(0, "English/English.Patient.mkv")
        val found = discover(selected, listOf(selected), subtitle(1, "English/English.Patient.srt"))
        assertEquals(1, found.single().index)
        assertNull(found.single().language)
        assertNull(subtitleToAutoSelect(found))
    }

    @Test fun explicitLanguageDirectoryIsRecognizedAndConflictsStayUnknown() {
        val selected = video(0, "Release/Film.mkv")
        val found = discover(selected, listOf(selected),
            subtitle(1, "Release/Subs/English/Film.srt"),
            subtitle(2, "Release/Subs/Russian/Film.en.srt"),
        )
        assertEquals("en", found.single { it.index == 1 }.language)
        assertNull(found.single { it.index == 2 }.language)
        assertEquals(1, subtitleToAutoSelect(found)?.index)
    }

    @Test fun multipleEnglishVariantsRemainAnExplicitChoice() {
        val selected = video(0, "Film.mkv")
        val found = discover(selected, listOf(selected),
            subtitle(1, "Film.en.srt"),
            subtitle(2, "Film.en.sdh.srt"),
            subtitle(3, "Film.en.forced.vtt"),
        )
        assertEquals(setOf(1, 2, 3), found.map { it.index }.toSet())
        assertNull(subtitleToAutoSelect(found))
    }

    @Test fun onlyNonemptyBoundedSrtAndVttAreDiscoverable() {
        val selected = video(0, "Film.mkv")
        val found = discover(selected, listOf(selected),
            subtitle(1, "Film.en.srt").copy(size = MAX_TORRENT_SUBTITLE_BYTES.toLong()),
            subtitle(2, "Film.en.vtt").copy(size = MAX_TORRENT_SUBTITLE_BYTES.toLong() + 1),
            subtitle(3, "Film.ru.srt").copy(size = 0),
            subtitle(4, "Film.en.ass"),
            subtitle(5, "Film.en.srt.zip"),
            subtitle(6, "Film2.en.srt"),
        )
        assertEquals(listOf(1), found.map { it.index })
        assertEquals(MAX_TORRENT_SUBTITLE_BYTES.toLong(), found.single().size)
    }

    private fun video(index: Int, name: String) = TorrentFile(index, name, 100_000_000)
    private fun subtitle(index: Int, name: String) = TorrentFile(index, name, 2000)
    private fun discover(selected: TorrentFile, videos: List<TorrentFile>, vararg subtitles: TorrentFile) =
        discoverTorrentSubtitles(selected, videos, subtitles.toList())
}
