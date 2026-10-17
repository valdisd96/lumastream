package dev.lumastream.torrent

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile

@UnstableApi
internal class TorrentDataSource(
    private val torrent: NativeTorrent,
    private val selection: TorrentSelection,
    private val playbackUri: Uri,
) : BaseDataSource(true) {
    private val fileLock = Any()
    private var file: RandomAccessFile? = null
    private var position = 0L
    private var remaining = 0L
    private var opened = false
    @Volatile private var closed = true
    private val isCancelled = { closed }

    override fun open(dataSpec: DataSpec): Long {
        if (opened) close()
        transferInitializing(dataSpec)
        if (dataSpec.uri != playbackUri) throw IOException("This torrent data source cannot open a different video")
        torrent.ensureSelection(selection)
        if (dataSpec.position < 0 || dataSpec.position > selection.video.file.size) {
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }
        closed = false
        position = dataSpec.position
        val available = selection.video.file.size - position
        remaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) available else minOf(available, dataSpec.length)
        try {
            if (remaining > 0) torrent.demand(selection, position)
            opened = true
            transferStarted(dataSpec)
            return remaining
        } catch (error: IOException) {
            closed = true
            if (error !is InterruptedIOException) torrent.fail(error.message ?: "Cannot open torrent video")
            throw error
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (closed || Thread.currentThread().isInterrupted) throw InterruptedIOException("Torrent read cancelled")
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        try {
            torrent.ensureSelection(selection)
            torrent.demand(selection, position)
            val absolute = selection.video.torrentOffset + position
            val piece = (absolute / torrent.pieceLength).toInt()
            // One read cannot cross into the next, possibly unverified, piece.
            val untilPieceEnd = torrent.pieceLength - absolute % torrent.pieceLength
            val count = minOf(length.toLong(), remaining, untilPieceEnd).toInt()
            torrent.awaitReadable(selection, piece, isCancelled)
            val bytes = synchronized(fileLock) {
                if (closed || Thread.currentThread().isInterrupted) throw InterruptedIOException("Torrent read cancelled")
                val input = file ?: RandomAccessFile(selection.video.path, "r").also { file = it }
                input.seek(position)
                val read = input.read(buffer, offset, count)
                if (read < 0) throw EOFException("Verified torrent piece is missing from disk")
                position += read
                remaining -= read
                bytesTransferred(read)
                read
            }
            return bytes
        } catch (error: IOException) {
            if (!closed && !Thread.currentThread().isInterrupted && error !is InterruptedIOException) {
                torrent.fail(error.message ?: "Unable to read torrent video")
            }
            throw error
        }
    }

    override fun getUri(): Uri? = if (opened) playbackUri else null

    override fun close() {
        closed = true
        synchronized(fileLock) {
            try {
                file?.close()
            } finally {
                file = null
                if (opened) {
                    opened = false
                    transferEnded()
                }
            }
        }
    }
}
