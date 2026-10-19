package dev.lumastream.history

import dev.lumastream.catalog.CatalogTitle
import dev.lumastream.catalog.MediaKind
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WatchLaterStoreTest {
    @get:Rule val directory = TemporaryFolder()
    private val file: File get() = File(directory.root, "watch-later.json")
    private val title = CatalogTitle(42, MediaKind.MOVIE, "Title 日本語", "Original", "2025",
        "Overview\nDetails", "/poster.jpg", "/backdrop.webp", "ja")

    @Test fun absentStoreDoesNotCreateAFile() = runBlocking {
        assertEquals(emptyList<CatalogTitle>(), WatchLaterStore(file).load())
        assertFalse(file.exists())
    }

    @Test fun metadataAndMediaIdentitySurviveRecreationWithoutSources() = runBlocking {
        val series = title.copy(kind = MediaKind.SERIES, year = "", posterPath = null,
            backdropPath = null, originalLanguage = null)
        WatchLaterStore(file).save(listOf(series, title))
        assertEquals(listOf(series, title), WatchLaterStore(file).load())
        WatchLaterStore(file).save(listOf(title))
        assertEquals(listOf(title), WatchLaterStore(file).load())
        WatchLaterStore(file).save(emptyList())
        assertEquals(emptyList<CatalogTitle>(), WatchLaterStore(file).load())
    }

    @Test fun firstOccurrenceWinsAndHundredUniqueTitlesRetainCallerOrder() = runBlocking {
        val titles = (105 downTo 1).map { title.copy(id = it) }
        WatchLaterStore(file).save(listOf(titles.first()) + titles.map { it.copy(title = "Updated") })
        assertEquals(listOf(titles.first()) + titles.drop(1).take(99).map { it.copy(title = "Updated") },
            WatchLaterStore(file).load())
    }

    @Test fun malformedIncompleteAndUnsupportedStorageIsNeverCleared() {
        for (invalid in listOf("{", "{\"version\":2,\"titles\":[]}", "{\"version\":1,\"titles\":[{}]}")) {
            file.writeText(invalid)
            assertThrows(IOException::class.java) { runBlocking { WatchLaterStore(file).load() } }
            assertEquals(invalid, file.readText())
        }
    }

    @Test fun invalidIdentitiesAndUnsafeArtworkAreRejectedWithoutDiscardingFile() = runBlocking {
        for ((key, value) in listOf("id" to 1.5, "id" to 0, "id" to 4_294_967_338L,
            "kind" to "UNKNOWN", "title" to JSONObject.NULL, "posterPath" to "https://example.com/image.jpg")) {
            WatchLaterStore(file).save(listOf(title))
            val root = JSONObject(file.readText())
            root.getJSONArray("titles").getJSONObject(0).put(key, value)
            val invalid = root.toString()
            file.writeText(invalid)
            assertThrows(IOException::class.java) { runBlocking { WatchLaterStore(file).load() } }
            assertEquals(invalid, file.readText())
        }
    }

    @Test fun oversizedRecordCannotReplaceCommittedTitles() = runBlocking {
        WatchLaterStore(file).save(listOf(title))
        assertThrows(IOException::class.java) {
            runBlocking { WatchLaterStore(file).save(listOf(title.copy(overview = "x".repeat(16_385)))) }
        }
        assertEquals(listOf(title), WatchLaterStore(file).load())
    }

    @Test fun interruptedWriteIsIgnoredAndNextSaveReplacesIt() = runBlocking {
        WatchLaterStore(file).save(listOf(title))
        val temporary = File(directory.root, "watch-later.json.new")
        temporary.writeText("{")
        assertEquals(listOf(title), WatchLaterStore(file).load())
        WatchLaterStore(file).save(emptyList())
        assertEquals(emptyList<CatalogTitle>(), WatchLaterStore(file).load())
        assertFalse(temporary.exists())
    }

    @Test fun overLimitStoredListIsReportedInsteadOfSilentlyTruncated() = runBlocking {
        WatchLaterStore(file).save(listOf(title))
        val root = JSONObject(file.readText())
        val titles = root.getJSONArray("titles")
        val saved = titles.getJSONObject(0)
        repeat(100) { titles.put(JSONObject(saved.toString()).put("id", it + 100)) }
        val invalid = root.toString()
        file.writeText(invalid)
        assertThrows(IOException::class.java) { runBlocking { WatchLaterStore(file).load() } }
        assertEquals(invalid, file.readText())
    }

    @Test fun failedReplacementLeavesCommittedTitlesReadable() = runBlocking {
        WatchLaterStore(file).save(listOf(title))
        val temporary = File(directory.root, "watch-later.json.new")
        assertTrue(temporary.mkdir())
        File(temporary, "obstacle").writeText("Prevent replacement")
        assertThrows(IOException::class.java) { runBlocking { WatchLaterStore(file).save(emptyList()) } }
        assertEquals(listOf(title), WatchLaterStore(file).load())
    }
}
