package dev.lumastream.subtitle

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class OpenSubtitlesTest {
    private val request = SubtitleSearchRequest("The.Studio.S01E02.1080p.WEB-DL.mkv", "tt23649128", 1, 2)

    private fun result(
        id: Long = 123,
        release: String = "The.Studio.S01E02.1080p.WEB-DL",
        language: String = "en",
        episode: Int = 2,
        parent: Long = 23649128,
        hash: Boolean = false,
        title: String = "The Studio",
        filename: String = "$release.srt",
    ): JSONObject = JSONObject().put("attributes", JSONObject()
        .put("release", release).put("language", language).put("moviehash_match", hash)
        .put("hearing_impaired", true).put("fps", 23.976)
        .put("feature_details", JSONObject().put("feature_type", "Episode").put("parent_imdb_id", parent)
            .put("season_number", 1).put("episode_number", episode).put("parent_title", title))
        .put("files", JSONArray().put(JSONObject().put("file_id", id).put("file_name", filename))))

    private fun parse(request: SubtitleSearchRequest, vararg rows: JSONObject): List<ProviderSubtitle> =
        parseProviderSubtitles(JSONObject().put("data", JSONArray(rows.toList())), subtitleSearchPlan(request))

    @Test fun seriesSearchUsesParentIdentityAndRejectsOtherSeriesEpisodesAndLanguages() {
        val plan = subtitleSearchPlan(request)
        assertEquals("23649128", plan.parameters["parent_imdb_id"])
        assertFalse(plan.parameters.containsKey("imdb_id"))
        val results = parse(request, result(), result(id = 124, episode = 3), result(id = 125, parent = 1234567), result(id = 126, language = "fr"))
        assertEquals(listOf(123L), results.map { it.fileId })
        assertEquals(23.976, requireNotNull(results.single().fps), 0.0001)
        assertTrue(results.single().hearingImpaired)
    }

    @Test fun contradictoryReleaseOrFilenameCannotOverrideVerifiedMetadataEvenWithHash() {
        val hashed = request.copy(movieHash = "0123456789abcdef")
        assertEquals(emptyList<ProviderSubtitle>(), parse(hashed,
            result(release = "The.Studio.S01E03", hash = true),
            result(filename = "The.Studio.S01E03.srt", hash = true),
            result(release = "The.Studio.S01E02E03", hash = true)))
    }

    @Test fun exactHashRanksBeforeReleaseSimilarityAndCannotBeClaimedWithoutSentHash() {
        val results = parse(request.copy(movieHash = "0123456789abcdef"),
            result(id = 1), result(id = 2, release = "Other.encode", hash = true))
        assertEquals(listOf(2L, 1L), results.map { it.fileId })
        assertFalse(parse(request, result(hash = true)).single().hashMatch)
    }

    @Test fun sameReleaseRanksAheadOfDifferentEncodeWithoutHash() {
        val results = parse(request, result(id = 1, release = "The.Studio.S01E02.480p.DVDRip"), result(id = 2))
        assertEquals(listOf(2L, 1L), results.map { it.fileId })
    }

    @Test fun filenameFallbackRequiresSameTitleAndSingleEpisode() {
        val external = request.copy(imdbId = null, season = null, episode = null)
        assertEquals(listOf(123L), parse(external, result(), result(id = 2, title = "Another Studio"), result(id = 3, episode = 3)).map { it.fileId })
        assertThrows(IOException::class.java) { subtitleSearchPlan(external.copy(videoFilename = "The.Studio.S01E02-E03.mkv")) }
        assertThrows(IOException::class.java) { subtitleSearchPlan(external.copy(videoFilename = "The.Studio.S01E02.S01E03.mkv")) }
    }

    @Test fun ambiguousFilenameRequiresHashAndHashOnlyRejectsUnmatchedRows() {
        val external = SubtitleSearchRequest("opaque-video.mkv")
        assertThrows(IOException::class.java) { subtitleSearchPlan(external) }
        val hashed = external.copy(movieHash = "0123456789abcdef")
        assertEquals("only", subtitleSearchPlan(hashed).parameters["moviehash_match"])
        assertEquals(listOf(2L), parse(hashed, result(id = 1), result(id = 2, hash = true)).map { it.fileId })
    }

    @Test fun movieFilenameFallbackRejectsRemakeAndSimilarTitle() {
        fun movie(id: Int, title: String, year: Int): JSONObject = result(id = id.toLong(), release = "Example.$year")
            .also { it.getJSONObject("attributes").put("feature_details", JSONObject().put("feature_type", "Movie").put("title", title).put("year", year)) }
        assertEquals(listOf(1L), parse(SubtitleSearchRequest("Example.2014.1080p.mkv"),
            movie(1, "Example", 2014), movie(2, "Example", 2025), movie(3, "Another Example", 2014)).map { it.fileId })
    }

    @Test fun multiCdAndInvalidFileIdentitiesAreNotOfferedAsWholeVideoSubtitles() {
        val multi = result().also { it.getJSONObject("attributes").getJSONArray("files").put(JSONObject().put("file_id", 456).put("file_name", "cd2.srt")) }
        assertEquals(emptyList<ProviderSubtitle>(), parse(request, multi, result(id = 0), result(id = -1)))
    }

    @Test fun movieHashEnforcesNonoverlappingCompleteSamples() {
        val zero = ByteArray(65_536)
        assertNull(openSubtitlesMovieHash(131_071, zero, zero))
        assertNull(openSubtitlesMovieHash(131_072, ByteArray(65_535), zero))
        assertEquals("0000000000020000", openSubtitlesMovieHash(131_072, zero, zero))
    }

    @Test fun movieHashUsesLittleEndianAndWrappingUnsignedSum() {
        val first = ByteArray(65_536).also { it[0] = 1; it[7] = 0x80.toByte() }
        val last = ByteArray(65_536).also { it[0] = 2; it[7] = 0x80.toByte() }
        assertEquals("0000000000020003", openSubtitlesMovieHash(131_072, first, last))
        assertEquals("000000000001c000", openSubtitlesMovieHash(131_072, ByteArray(65_536) { -1 }, ByteArray(65_536) { -1 }))
    }
}
