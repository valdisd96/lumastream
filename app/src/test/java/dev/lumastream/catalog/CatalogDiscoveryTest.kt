package dev.lumastream.catalog

import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogDiscoveryTest {
    private val catalog = TmdbCatalog("", "en-US")

    private fun movie(id: Int) = JSONObject().put("id", id).put("title", "Localized $id")
        .put("original_title", "Original $id").put("release_date", "2025-01-02")
        .put("original_language", "ja").put("poster_path", "/poster.jpg")
        .put("media_type", "movie")

    private fun page(vararg items: JSONObject) = JSONObject().put("results", JSONArray(items.toList()))
        .put("total_pages", 999)

    @Test fun movieAndSeriesUseTheirOwnMetadataAndRemainDistinctInSearch() {
        val series = JSONObject().put("id", 42).put("name", "Translated series")
            .put("original_name", "Original series").put("first_air_date", "2024-03-04")
            .put("media_type", "tv")
        val parsed = catalog.parsePage(page(movie(42), series, movie(42)), 1, null)
        assertEquals(listOf(MediaKind.MOVIE, MediaKind.SERIES), parsed.items.map { it.kind })
        assertEquals(listOf("Localized 42", "Translated series"), parsed.items.map { it.title })
        assertEquals(listOf("2025", "2024"), parsed.items.map { it.year })
        assertEquals("ja", parsed.items.first().originalLanguage)
        assertTrue(parsed.hasMore)
    }

    @Test fun discoveryWithoutMediaTypeUsesRequestedKindAndOriginalNameFallback() {
        val series = JSONObject().put("id", 7).put("original_name", "Original series")
            .put("poster_path", "https://example.com/unsafe.jpg")
        assertEquals(CatalogTitle(7, MediaKind.SERIES, "Original series", "Original series", "", "", null, null),
            catalog.parsePage(page(series), 1, MediaKind.SERIES).items.single())
    }

    @Test fun adultTitlesAndPeopleNeverEnterCatalogPages() {
        val data = page(movie(1).put("adult", true), JSONObject().put("id", 3).put("media_type", "person"), movie(2))
        assertEquals(listOf(2), catalog.parsePage(data, 1, null).items.map { it.id })
        assertEquals(emptyList<CatalogTitle>(), catalog.parsePage(page(movie(1).put("adult", true)), 1, MediaKind.MOVIE).items)
    }

    @Test fun itemAndPageLimitsBoundRemoteResponses() {
        val data = JSONObject().put("results", JSONArray((1..101).map(::movie))).put("total_pages", 999)
        val parsed = catalog.parsePage(data, 500, MediaKind.MOVIE)
        assertEquals((1..100).toList(), parsed.items.map { it.id })
        assertFalse(parsed.hasMore)
        assertThrows(IllegalArgumentException::class.java) { catalog.parsePage(data, 501, MediaKind.MOVIE) }
        assertThrows(IllegalArgumentException::class.java) { catalog.parsePage(data, 0, MediaKind.MOVIE) }
    }

    @Test fun malformedIdentitiesCannotAliasValidTitles() {
        for (id in listOf(0, -1, 1.5, 4_294_967_338L, "42")) {
            assertThrows(IOException::class.java) {
                catalog.parsePage(page(movie(42).put("id", id)), 1, MediaKind.MOVIE)
            }
        }
        assertThrows(IOException::class.java) { catalog.parsePage(JSONObject(), 1, MediaKind.MOVIE) }
    }

    @Test fun genresPreserveLocalizedLabelsAndDistinctServerIds() {
        val genres = JSONArray().put(JSONObject().put("id", 10759).put("name", "Action et aventure"))
            .put(JSONObject().put("id", 16).put("name", "Animation"))
            .put(JSONObject().put("id", 10759).put("name", "Duplicate"))
        assertEquals(listOf(CatalogGenre(10759, "Action et aventure"), CatalogGenre(16, "Animation")),
            catalog.parseGenres(JSONObject().put("genres", genres)))
    }

    @Test fun missingInvalidAndExcessiveGenresAreErrorsRatherThanEmptySuccess() {
        assertThrows(IOException::class.java) { catalog.parseGenres(JSONObject()) }
        for (id in listOf(0, 1.5, 4_294_967_338L)) {
            assertThrows(IOException::class.java) {
                catalog.parseGenres(JSONObject().put("genres", JSONArray().put(JSONObject().put("id", id).put("name", "Genre"))))
            }
        }
        assertThrows(IOException::class.java) {
            catalog.parseGenres(JSONObject().put("genres", JSONArray((1..101).map { JSONObject().put("id", it).put("name", "Genre") })))
        }
    }
}
