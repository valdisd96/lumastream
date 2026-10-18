package dev.lumastream.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TorrentReadProgressTest {
    @Test fun progressingPieceSurvivesOldAbsoluteDeadlineButStalledPieceExpires() {
        val wait = PieceReadWait(1_000)
        wait.observe(1_000, 0, 0, false)
        assertFalse(wait.expired(90_999))
        wait.observe(90_999, 1, 0, false)
        assertFalse(wait.expired(91_000))
        assertFalse(wait.expired(180_998))
        assertTrue(wait.expired(180_999))
        assertEquals(179_999L, wait.elapsedMillis(180_999))
        assertEquals(90_000L, wait.stalledMillis(180_999))
    }

    @Test fun duplicateAndRegressedBlockCountsCannotRenewTheWait() {
        val wait = PieceReadWait(0)
        wait.observe(10_000, 2, 1, false)
        wait.observe(80_000, 0, 0, false)
        wait.observe(99_999, 2, 1, false)
        assertTrue(wait.expired(100_000))
    }

    @Test fun diskCompletionAndVerificationAreProgressButRepeatedVerificationIsNot() {
        val wait = PieceReadWait(0)
        wait.observe(1_000, 4, 0, false)
        wait.observe(90_000, 4, 1, false)
        assertFalse(wait.expired(91_000))
        wait.observe(179_000, 4, 1, true)
        assertFalse(wait.expired(180_000))
        wait.observe(268_999, 4, 1, true)
        assertTrue(wait.expired(269_000))
    }

    @Test fun slowProgressStillHasAFiniteOverallDeadline() {
        val wait = PieceReadWait(500)
        for (block in 1..5) {
            wait.observe(500 + block * 59_000L, block, block, false)
        }
        assertFalse(wait.expired(300_499))
        assertTrue(wait.expired(300_500))
    }

    @Test fun uniqueDownloadProgressClipsSharedPiecesAndIgnoresRedownloads() {
        val progress = SelectedDownloadProgress(900, 1500, 1024)
        progress.record(0)
        assertEquals(124L, progress.downloadedBytes())
        progress.record(2)
        assertEquals(476L, progress.downloadedBytes())
        // Re-downloading an evicted piece is traffic, not additional unique video data.
        progress.record(0)
        progress.record(2)
        progress.record(3)
        progress.record(-1)
        assertEquals(476L, progress.downloadedBytes())
        progress.record(1)
        assertEquals(1500L, progress.downloadedBytes())
    }

    @Test fun oneSharedPieceCountsOnlySelectedVideoBytes() {
        val progress = SelectedDownloadProgress(1100, 100, 1024)
        progress.record(0)
        progress.record(2)
        assertEquals(0L, progress.downloadedBytes())
        progress.record(1)
        progress.record(1)
        assertEquals(100L, progress.downloadedBytes())
    }

    @Test fun largeVideoOffsetsDoNotOverflowPieceMapping() {
        val offset = 5L * 1024 * 1024 * 1024 + 17
        val pieceLength = 4 * 1024 * 1024
        val progress = SelectedDownloadProgress(offset, pieceLength.toLong(), pieceLength)
        val first = (offset / pieceLength).toInt()
        progress.record(first)
        assertEquals(pieceLength - 17L, progress.downloadedBytes())
        progress.record(first + 1)
        assertEquals(pieceLength.toLong(), progress.downloadedBytes())
    }
}
