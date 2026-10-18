package dev.lumastream.torrent

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import java.io.IOException
import java.io.InterruptedIOException

@UnstableApi
internal class TorrentDataSource(
    private val torrent: NativeTorrent,
    private val selection: TorrentSelection,
    private val playbackUri: Uri,
) : BaseDataSource(true) {
    private var position = 0L
    private var remaining = 0L
    private var opened = false
    @Volatile private var closed = true
    private val isCancelled = { closed }

    override fun open(dataSpec: DataSpec): Long {
        if (opened) close()
        transferInitializing(dataSpec)
        if (dataSpec.uri != playbackUri) throw IOException("This torrent data source cannot open a different video")
        try {
            torrent.ensureSelection(selection)
        } catch (error: IOException) {
            throw sourceException(error)
        }
        if (dataSpec.position < 0 || dataSpec.position > selection.video.file.size) {
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }
        closed = false
        position = dataSpec.position
        val available = selection.video.file.size - position
        remaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) available else minOf(available, dataSpec.length)
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (closed || Thread.currentThread().isInterrupted) throw InterruptedIOException("Torrent read cancelled")
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        try {
            val absolute = selection.video.torrentOffset + position
            // One read cannot cross into the next, possibly unverified, piece.
            val untilPieceEnd = torrent.pieceLength - absolute % torrent.pieceLength
            val count = minOf(length.toLong(), remaining, untilPieceEnd).toInt()
            val bytes = torrent.read(selection, position, buffer, offset, count, isCancelled)
            position += bytes
            remaining -= bytes
            bytesTransferred(bytes)
            return bytes
        } catch (error: IOException) {
            if (!closed && !Thread.currentThread().isInterrupted && error !is InterruptedIOException) {
                torrent.fail(error)
            }
            throw sourceException(error)
        }
    }

    private fun sourceException(error: IOException): IOException =
        if (error is TorrentReadTimeoutException && error.waitingForNetwork) {
            DataSourceException(error, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT)
        } else error

    override fun getUri(): Uri? = if (opened) playbackUri else null

    override fun close() {
        closed = true
        if (opened) {
            opened = false
            transferEnded()
        }
    }
}
