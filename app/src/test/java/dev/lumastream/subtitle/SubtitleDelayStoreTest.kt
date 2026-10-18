package dev.lumastream.subtitle

import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SubtitleDelayStoreTest {
    @get:Rule val directory = TemporaryFolder()
    private val file: File get() = File(directory.root, "delays.json")

    @Test fun delayOnlyRestoresForExactVideoAndSubtitleAndResetSurvivesRecreation() = runBlocking {
        val video = subtitleVideoIdentity("a".repeat(40), 3, "film.mkv", 2000)
        val track = externalSubtitleIdentity("first subtitles".toByteArray())
        SubtitleDelayStore(file).set(video, track, -1200)
        val restored = SubtitleDelayStore(file)
        assertEquals(-1200L, restored.get(video, track))
        assertEquals(0L, restored.get(subtitleVideoIdentity("b".repeat(40), 3, "film.mkv", 2000), track))
        assertEquals(0L, restored.get(subtitleVideoIdentity("a".repeat(40), 4, "film.mkv", 2000), track))
        assertEquals(0L, restored.get(video, externalSubtitleIdentity("replacement subtitles".toByteArray())))
        restored.set(video, track, 0)
        assertEquals(0L, SubtitleDelayStore(file).get(video, track))
    }

    @Test fun pairEncodingCannotConfuseFieldBoundaries() = runBlocking {
        SubtitleDelayStore(file).set("video:one", "two", 100)
        assertEquals(0L, SubtitleDelayStore(file).get("video", "one:two"))
        assertNotEquals(identityDigest("ab", "c"), identityDigest("a", "bc"))
    }

    @Test fun acceptsBothRangeEndpointsAndRejectsOverflowWithoutChangingSavedValue() = runBlocking {
        val store = SubtitleDelayStore(file)
        store.set("video", "track", 30_000)
        assertEquals(30_000L, store.get("video", "track"))
        store.set("video", "track", -30_000)
        assertEquals(-30_000L, store.get("video", "track"))
        assertThrows(IllegalArgumentException::class.java) { runBlocking { store.set("video", "track", 30_001) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { store.set("video", "track", Long.MIN_VALUE) } }
        assertEquals(-30_000L, store.get("video", "track"))
    }

    @Test fun evictsLeastRecentlyUpdatedAndSerializesIndependentInstances() = runBlocking {
        (0 until 128).map { index ->
            async(Dispatchers.Default) { SubtitleDelayStore(file).set("video-$index", "track", index + 1L) }
        }.awaitAll()
        for (index in 0 until 128) assertEquals(index + 1L, SubtitleDelayStore(file).get("video-$index", "track"))
        val store = SubtitleDelayStore(file)
        // Touch every entry in a deterministic order after exercising concurrent writes.
        for (index in 0 until 128) store.set("video-$index", "track", index + 1L)
        store.set("video-0", "track", 999)
        store.set("new-video", "track", 500)
        assertEquals(0L, store.get("video-1", "track"))
        assertEquals(999L, store.get("video-0", "track"))
        assertEquals(500L, store.get("new-video", "track"))
    }

    @Test fun corruptOrOversizedStorageIsReportedAndNeverOverwritten() {
        for (contents in listOf("{broken", " ".repeat(64 * 1024 + 1))) {
            file.writeText(contents)
            assertThrows(IOException::class.java) { runBlocking { SubtitleDelayStore(file).get("video", "track") } }
            assertThrows(IOException::class.java) { runBlocking { SubtitleDelayStore(file).set("video", "track", 100) } }
            assertEquals(contents, file.readText())
        }
    }

    @Test fun failedAtomicReplacementPreservesCommittedTiming() = runBlocking {
        val store = SubtitleDelayStore(file)
        store.set("video", "track", 100)
        val blocked = File(directory.root, "delays.json.new")
        assertTrue(blocked.mkdir())
        File(blocked, "occupied").writeText("block")
        assertThrows(IOException::class.java) { runBlocking { store.set("video", "track", 200) } }
        assertEquals(100L, SubtitleDelayStore(file).get("video", "track"))
    }
}
