package dev.lumastream.torrent

import java.io.IOException
import java.util.BitSet

internal class TorrentReadTimeoutException(message: String, val waitingForNetwork: Boolean) : IOException(message)

/** Tracks unique verified bytes, not resident cache bytes or repeated network transfers. */
internal class SelectedDownloadProgress(
    private val fileOffset: Long,
    private val fileSize: Long,
    private val pieceLength: Int,
) {
    private val firstPiece = (fileOffset / pieceLength).toInt()
    private val lastPiece = ((fileOffset + fileSize - 1) / pieceLength).toInt()
    private val completed = BitSet(lastPiece - firstPiece + 1)
    private var bytes = 0L

    @Synchronized fun record(piece: Int) {
        if (piece !in firstPiece..lastPiece || completed[piece - firstPiece]) return
        completed.set(piece - firstPiece)
        val start = maxOf(fileOffset, piece.toLong() * pieceLength)
        val end = minOf(fileOffset + fileSize, (piece.toLong() + 1) * pieceLength)
        bytes += end - start
    }

    @Synchronized fun downloadedBytes(): Long = bytes
}

/** Progress in unrelated lookahead pieces must not keep a missing demanded piece alive. */
internal class PieceReadWait(private val startedAt: Long) {
    companion object {
        const val STALL_TIMEOUT_MILLIS = 90_000L
        const val MAX_WAIT_MILLIS = 300_000L
    }

    private var progressedAt = startedAt
    private var mostLoaded = 0
    private var mostWritten = 0
    private var verified = false

    fun observe(now: Long, loaded: Int, written: Int, isVerified: Boolean) {
        if (loaded > mostLoaded || written > mostWritten || (isVerified && !verified)) {
            progressedAt = now
            mostLoaded = maxOf(mostLoaded, loaded)
            mostWritten = maxOf(mostWritten, written)
            verified = verified || isVerified
        }
    }

    fun expired(now: Long): Boolean =
        now - startedAt >= MAX_WAIT_MILLIS || now - progressedAt >= STALL_TIMEOUT_MILLIS

    fun elapsedMillis(now: Long): Long = (now - startedAt).coerceAtLeast(0)
    fun stalledMillis(now: Long): Long = (now - progressedAt).coerceAtLeast(0)
}
