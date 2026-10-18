package dev.lumastream.torrent

import java.util.BitSet

/** Reserves whole pieces as soon as they may be written, including incomplete downloads. */
internal class RollingPieceWindow(
    val pieceCount: Int,
    val pieceLength: Int,
    val capacityBytes: Long,
    val lookaheadBytes: Long = 8L * 1024 * 1024,
    val lookbehindBytes: Long = 8L * 1024 * 1024,
) {
    init {
        require(pieceCount > 0) { "Torrent must contain pieces" }
        require(pieceLength > 0) { "Piece length must be positive" }
        require(capacityBytes > 0) { "Cache capacity must be positive" }
        require(lookaheadBytes >= 0 && lookbehindBytes >= 0) { "Window sizes must be nonnegative" }
    }

    private val capacityPieces = capacityBytes / pieceLength
    private val forwardPieces = (lookaheadBytes / pieceLength).coerceIn(1, 32).toInt()
    private val behindPieces = roundedPieces(lookbehindBytes).coerceAtMost(pieceCount.toLong()).toInt()

    init {
        // Leave refill headroom beyond a full retained neighborhood. If the entire torrent
        // fits, there is no eviction/refill cycle and no extra piece is necessary.
        val minimumPieces = minOf(pieceCount.toLong(), behindPieces.toLong() + forwardPieces + 1)
        require(capacityPieces >= minimumPieces) { "Cache must fit the retained window and one new piece" }
    }

    // Allocate once: admission, checks and rollover never copy or grow the bit set.
    private val admitted = BitSet(pieceCount)
    private var admittedCount = 0

    val reservedBytes: Long get() = admittedCount.toLong() * pieceLength

    fun windowEnd(piece: Int, lastPiece: Int): Int {
        requireInterval(piece, lastPiece)
        return piece + minOf(lastPiece - piece, forwardPieces - 1)
    }

    fun needsEviction(start: Int, end: Int): Boolean {
        requireInterval(start, end)
        return admittedCount.toLong() + newPieceCount(start, end) > capacityPieces
    }

    fun admit(start: Int, end: Int) {
        requireInterval(start, end)
        val added = newPieceCount(start, end)
        check(admittedCount.toLong() + added <= capacityPieces) { "Piece admission exceeds cache capacity" }
        admitted.set(start, end + 1)
        admittedCount += added
    }

    fun retainedStart(demand: Int, firstPiece: Int): Int {
        requireInterval(firstPiece, demand)
        return demand - minOf(demand - firstPiece, behindPieces)
    }

    fun retainedEnd(demand: Int, lastPiece: Int): Int = windowEnd(demand, lastPiece)

    /** Call only after native writes stop and bytes outside this interval have been removed. */
    fun reset(retainStart: Int, retainEnd: Int) {
        requireInterval(retainStart, retainEnd)
        admitted.clear(0, retainStart)
        admitted.clear(retainEnd + 1, pieceCount)
        admittedCount = admitted.cardinality()
    }

    private fun newPieceCount(start: Int, end: Int): Int {
        var count = 0
        var piece = admitted.nextClearBit(start)
        while (piece <= end) {
            count++
            piece = admitted.nextClearBit(piece + 1)
        }
        return count
    }

    private fun requireInterval(start: Int, end: Int) {
        require(start >= 0 && end >= start && end < pieceCount) { "Invalid piece interval" }
    }

    private fun roundedPieces(bytes: Long): Long =
        bytes / pieceLength + if (bytes % pieceLength == 0L) 0 else 1
}

/** Maps a global half-open keep interval into an inclusive, file-local byte range. */
internal fun retainedFileRange(
    fileOffset: Long,
    fileSize: Long,
    keepStart: Long,
    keepEndExclusive: Long,
): LongRange? {
    require(fileOffset >= 0 && fileSize >= 0 && fileSize <= Long.MAX_VALUE - fileOffset) {
        "Invalid file extent"
    }
    require(keepStart >= 0 && keepEndExclusive >= keepStart) { "Invalid retained byte interval" }
    val start = maxOf(fileOffset, keepStart)
    val end = minOf(fileOffset + fileSize, keepEndExclusive)
    if (start >= end) return null
    return (start - fileOffset)..(end - fileOffset - 1)
}
