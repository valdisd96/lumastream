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

    private var reserveStart = -1
    private var reserveEnd = -1
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

    /** Keep one uncommitted piece for a subtitle/hash read; never evict to extend prefetch. */
    fun prefetchEnd(start: Int, lastPiece: Int, enabled: Boolean): Int {
        requireInterval(start, lastPiece)
        if (!enabled) return start - 1
        var remaining = (capacityPieces - admittedCount - 1).coerceAtLeast(0)
        val limit = windowEnd(start, lastPiece)
        var end = start - 1
        for (piece in start..limit) {
            if (!admitted[piece]) {
                if (remaining == 0L) break
                remaining--
            }
            end = piece
        }
        return end
    }

    fun admitPrefetch(demand: Int, start: Int, end: Int) {
        requireInterval(demand, start)
        admit(start, end)
        if (reserveStart < 0 || demand !in reserveStart..reserveEnd) {
            reserveStart = demand
            reserveEnd = end
        } else {
            reserveEnd = maxOf(reserveEnd, end)
        }
    }

    /** A seek within the reserve can still consume it; an unrelated seek redirects it. */
    fun redirectDemand(demand: Int) {
        requireInterval(demand, demand)
        if (demand !in reserveStart..reserveEnd) {
            reserveStart = -1
            reserveEnd = -1
        }
    }

    fun retainedStart(demand: Int, firstPiece: Int): Int {
        requireInterval(firstPiece, demand)
        return demand - minOf(demand - firstPiece, behindPieces)
    }

    fun retainedEnd(demand: Int, lastPiece: Int): Int {
        val ordinaryEnd = windowEnd(demand, lastPiece)
        return if (demand in reserveStart..reserveEnd) {
            maxOf(ordinaryEnd, minOf(reserveEnd, lastPiece))
        } else {
            ordinaryEnd
        }
    }

    /** Reclaim consumed lookbehind before sacrificing any still-useful forward reserve. */
    fun retainedStartForAdmission(
        demand: Int,
        firstPiece: Int,
        lastPiece: Int,
        admissionStart: Int,
        admissionEnd: Int,
    ): Int {
        requireInterval(admissionStart, admissionEnd)
        var start = retainedStart(demand, firstPiece)
        val end = retainedEnd(demand, lastPiece)
        var count = admissionEnd.toLong() - admissionStart + 1
        var piece = admitted.nextSetBit(start)
        while (piece >= 0 && piece <= end) {
            if (piece !in admissionStart..admissionEnd) count++
            piece = admitted.nextSetBit(piece + 1)
        }
        while (count > capacityPieces && start < demand) {
            if (admitted[start] && start !in admissionStart..admissionEnd) count--
            start++
        }
        check(count <= capacityPieces) { "Forward reserve leaves no room for required data" }
        return start
    }

    /** Call only after native writes stop and bytes outside this interval have been removed. */
    fun reset(retainStart: Int, retainEnd: Int) {
        requireInterval(retainStart, retainEnd)
        admitted.clear(0, retainStart)
        admitted.clear(retainEnd + 1, pieceCount)
        admittedCount = admitted.cardinality()
        if (reserveStart >= 0) {
            reserveStart = maxOf(reserveStart, retainStart)
            reserveEnd = minOf(reserveEnd, retainEnd)
            if (reserveStart > reserveEnd) {
                reserveStart = -1
                reserveEnd = -1
            }
        }
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

/** Resident verified data, distinct from cumulative downloaded bytes and playback time. */
internal class VerifiedReaderAhead(
    private val fileOffset: Long,
    private val fileSize: Long,
    private val pieceLength: Int,
) {
    private val firstPiece = (fileOffset / pieceLength).toInt()
    private val lastPiece = ((fileOffset + fileSize - 1) / pieceLength).toInt()
    private val verified = BitSet(lastPiece - firstPiece + 1)
    private var readerPosition = -1L
    private var contiguousEnd = firstPiece

    // This small independent lock never calls native code or the NativeTorrent lock:
    // the alert thread may record a finished piece while native shutdown is joining it.
    @Synchronized fun record(piece: Int) {
        if (piece !in firstPiece..lastPiece) return
        verified.set(piece - firstPiece)
        if (readerPosition >= 0 && piece == contiguousEnd) {
            contiguousEnd = firstMissing(piece)
        }
    }

    @Synchronized fun moveReader(position: Long) {
        require(position in 0..fileSize) { "Reader cursor is outside the selected video" }
        val piece = ((fileOffset + position) / pieceLength).toInt()
        val previousPiece = if (readerPosition < 0) -1 else ((fileOffset + readerPosition) / pieceLength).toInt()
        if (readerPosition < 0 || piece < previousPiece || piece >= contiguousEnd) {
            contiguousEnd = firstMissing(piece)
        }
        readerPosition = position
    }

    @Synchronized fun firstMissing(piece: Int): Int {
        if (piece > lastPiece) return piece
        require(piece >= firstPiece)
        return firstPiece + verified.nextClearBit(piece - firstPiece)
    }

    @Synchronized fun bytesAhead(): Long {
        if (readerPosition < 0) return 0
        val end = minOf(fileOffset + fileSize, contiguousEnd.toLong() * pieceLength)
        return (end - fileOffset - readerPosition).coerceAtLeast(0)
    }

    @Synchronized fun reachesEnd(): Boolean = readerPosition >= 0 && contiguousEnd > lastPiece

    @Synchronized fun retain(start: Int, end: Int) {
        val from = (start - firstPiece).coerceIn(0, lastPiece - firstPiece + 1)
        val until = (end.toLong() - firstPiece + 1).coerceIn(0, (lastPiece - firstPiece + 1).toLong()).toInt()
        verified.clear(0, from)
        verified.clear(until, lastPiece - firstPiece + 1)
        if (readerPosition >= 0) {
            contiguousEnd = firstMissing(((fileOffset + readerPosition) / pieceLength).toInt())
        }
    }
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
