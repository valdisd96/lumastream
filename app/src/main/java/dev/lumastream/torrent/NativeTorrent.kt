package dev.lumastream.torrent

import android.os.StatFs
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.libtorrent4j.AddTorrentParams
import org.libtorrent4j.AlertListener
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.StorageMode
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.alerts.FileErrorAlert
import org.libtorrent4j.alerts.MetadataFailedAlert
import org.libtorrent4j.alerts.TorrentErrorAlert
import org.libtorrent4j.swig.error_code
import org.libtorrent4j.swig.int64_vector
import org.libtorrent4j.swig.partial_piece_info_vector
import org.libtorrent4j.swig.settings_pack
import org.libtorrent4j.swig.status_flags_t
import org.libtorrent4j.swig.torrent_info
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.util.BitSet
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal data class TorrentSelection(val video: VideoFile, val serial: Long)

/** Native handles never escape this owner; alert callbacks copy only scalar data. */
internal class NativeTorrent(
    private val directory: File,
    private val onFailure: (NativeTorrent, String) -> Unit,
) {
    private val lock = Any()
    private val session = SessionManager(false)
    private val statusFlags = status_flags_t()
    private val downloadQueue = partial_piece_info_vector()
    private val fileProgress = int64_vector()
    private var handle: TorrentHandle? = null
    private var info: TorrentInfo? = null
    private val failure = AtomicReference<IOException?>()
    private val priorityEvents = AtomicLong()
    private val flushed = BitSet()
    private var selectionSerial = 0L
    private var selection: TorrentSelection? = null
    private var windowStart = -1
    private var windowEnd = -1
    private var lastDemand = -1
    private var stopped = false
    @Volatile var invalidated = false
        private set
    var videos: List<VideoFile> = emptyList()
        private set
    var pieceLength = 0
        private set

    private val listener = object : AlertListener {
        override fun types(): IntArray = intArrayOf(
            AlertType.FILE_ERROR.swig(), AlertType.TORRENT_ERROR.swig(),
            AlertType.METADATA_FAILED.swig(), AlertType.FILE_PRIO.swig(),
        )

        override fun alert(alert: Alert<*>) {
            if (invalidated) return
            // The session owns these alert pointers only until the next pop_alerts.
            // Never acquire lock here: native synchronous calls and stop may wait
            // for this thread. No alert or borrowed native pointer is retained.
            when (alert) {
                is FileErrorAlert -> recordFailure("Torrent disk error: ${alert.error().message} (${alert.filename()})")
                is TorrentErrorAlert -> recordFailure("Torrent error: ${alert.error().message}")
                is MetadataFailedAlert -> recordFailure("Invalid torrent metadata: ${alert.error.message}")
                else -> when (alert.type()) {
                    AlertType.FILE_PRIO -> priorityEvents.incrementAndGet()
                    else -> Unit
                }
            }
        }
    }

    fun start(params: AddTorrentParams) = synchronized(lock) {
        checkUsable()
        val metadata = params.torrentInfo
        if (metadata != null) videoFiles(metadata, directory)
        val settings = SettingsPack().apply {
            connectionsLimit(32)
            maxPeerlistSize(128)
            activeDownloads(1)
            activeSeeds(1)
            activeLimit(1)
            uploadRateLimit(512 * 1024)
            setInteger(settings_pack.int_types.max_metadata_size.swigValue(), MAX_METADATA_BYTES)
            setInteger(settings_pack.int_types.max_queued_disk_bytes.swigValue(), 1024 * 1024)
            setInteger(settings_pack.int_types.max_out_request_queue.swigValue(), 64)
            setInteger(settings_pack.int_types.max_allowed_in_request_queue.swigValue(), 64)
            setInteger(settings_pack.int_types.connection_speed.swigValue(), 4)
            setInteger(settings_pack.int_types.file_pool_size.swigValue(), 16)
            setInteger(settings_pack.int_types.aio_threads.swigValue(), 2)
            setInteger(settings_pack.int_types.hashing_threads.swigValue(), 1)
            setInteger(settings_pack.int_types.send_buffer_watermark.swigValue(), 256 * 1024)
            setInteger(settings_pack.int_types.max_peer_recv_buffer_size.swigValue(), 256 * 1024)
            setInteger(settings_pack.int_types.max_http_recv_buffer_size.swigValue(), 1024 * 1024)
            setInteger(settings_pack.int_types.max_web_seed_connections.swigValue(), 2)
            setInteger(settings_pack.int_types.urlseed_max_request_bytes.swigValue(), 1024 * 1024)
            setBoolean(settings_pack.bool_types.close_redundant_connections.swigValue(), false)
            setBoolean(settings_pack.bool_types.enable_dht.swigValue(), true)
            setBoolean(settings_pack.bool_types.enable_lsd.swigValue(), true)
            setBoolean(settings_pack.bool_types.enable_upnp.swigValue(), false)
            setBoolean(settings_pack.bool_types.enable_natpmp.swigValue(), false)
        }
        session.addListener(listener)
        val sessionParams = SessionParams(settings)
        try {
            session.start(sessionParams)
        } finally {
            sessionParams.swig().delete()
            settings.swig().delete()
        }
        params.savePath = directory.path
        params.storageMode = StorageMode.STORAGE_MODE_SPARSE
        params.maxConnections = 32
        params.maxUploads = 4
        params.flags = params.flags.and_(TorrentFlags.AUTO_MANAGED.inv()).and_(TorrentFlags.PAUSED.inv())
            .or_(TorrentFlags.UPLOAD_MODE).or_(TorrentFlags.DEFAULT_DONT_DOWNLOAD)
        if (metadata != null) params.filePriorities(Priority.array(Priority.IGNORE, metadata.numFiles()))
        val error = error_code()
        try {
            val nativeHandle = session.swig().add_torrent(params.swig(), error)
            if (error.value() != 0) {
                nativeHandle.delete()
                throw IOException("Cannot add torrent: ${error.message()}")
            }
            handle = TorrentHandle(nativeHandle)
        } finally {
            error.delete()
        }
    }

    fun loadMetadata(): Boolean = synchronized(lock) {
        checkUsable()
        if (info != null) return@synchronized true
        val current = requireHandle()
        checkStatus(current)
        val borrowed = current.torrentFile() ?: return@synchronized false
        if (!borrowed.isValid) return@synchronized false
        // torrentFile() is borrowed from the session. Own a copy for validation
        // and indexing, and delete it explicitly before destroying the session.
        val owned = TorrentInfo(torrent_info(borrowed.swig()))
        try {
            videos = videoFiles(owned, directory)
            pieceLength = owned.pieceLength()
            info = owned
        } catch (error: Throwable) {
            owned.swig().delete()
            throw error
        }
        true
    }

    suspend fun select(index: Int): TorrentSelection {
        val target: Long
        val chosen: TorrentSelection
        synchronized(lock) {
            checkUsable()
            val video = videos.firstOrNull { it.file.index == index } ?: throw IOException("Select a supported video file")
            val current = requireHandle()
            current.setFlags(TorrentFlags.UPLOAD_MODE)
            current.clearPieceDeadlines()
            selectionSerial++
            selection = null
            windowStart = -1
            windowEnd = -1
            lastDemand = -1
            val downloaded = downloadedBytes(current, index)
            val required = video.file.size - downloaded + 2L * pieceLength + LOOKAHEAD_BYTES
            val available = StatFs(directory.path).availableBytes
            if (available < DISK_RESERVE || required > available - DISK_RESERVE) {
                throw IOException("Not enough storage: this video needs ${mib(required)} MiB plus a 1 GiB free-space reserve; ${mib(available)} MiB available. Rolling cache is not implemented.")
            }
            chosen = TorrentSelection(video, selectionSerial)
            val priorities = Priority.array(Priority.IGNORE, info!!.numFiles())
            priorities[index] = Priority.DEFAULT
            target = priorityEvents.get() + 1
            current.prioritizeFiles(priorities)
        }
        try {
            withTimeout(20_000) {
                while (priorityEvents.get() < target) {
                    synchronized(lock) { checkUsable() }
                    delay(40)
                }
            }
        } catch (_: TimeoutCancellationException) {
            throw IOException("Timed out preparing torrent file storage")
        }
        synchronized(lock) {
            checkUsable()
            val current = requireHandle()
            // File priorities are asynchronous and reset piece priorities. Wait
            // for FILE_PRIO before applying the demand-only piece map.
            current.prioritizePieces(Priority.array(Priority.IGNORE, info!!.numPieces()))
            flushed.clear()
            selection = chosen
            current.unsetFlags(TorrentFlags.UPLOAD_MODE)
            current.resume()
        }
        return chosen
    }

    fun ensureSelection(chosen: TorrentSelection) = synchronized(lock) {
        checkUsable()
        if (selection?.serial != chosen.serial) throw InterruptedIOException("Torrent file selection changed")
    }

    fun demand(chosen: TorrentSelection, filePosition: Long) = synchronized(lock) {
        ensureSelection(chosen)
        if (filePosition == chosen.video.file.size) return@synchronized
        val piece = ((chosen.video.torrentOffset + filePosition) / pieceLength).toInt()
        if (piece == lastDemand) return@synchronized
        val current = requireHandle()
        checkDiskReserve()
        val lastPiece = ((chosen.video.torrentOffset + chosen.video.file.size - 1) / pieceLength).toInt()
        val count = minOf(32, maxOf(1, (LOOKAHEAD_BYTES / pieceLength).toInt()))
        val end = minOf(lastPiece, piece + count - 1)
        for (old in windowStart..windowEnd) {
            if (old >= 0 && (old < piece || old > end)) {
                current.resetPieceDeadline(old)
                current.piecePriority(old, Priority.IGNORE)
            }
        }
        for (next in piece..end) {
            if (next < windowStart || next > windowEnd) current.piecePriority(next, Priority.TOP_PRIORITY)
            if (!current.havePiece(next)) current.setPieceDeadline(next, (next - piece) * 250)
        }
        if (lastDemand < 0) {
            Log.i(TORRENT_TAG, "First demand: fileOffset=$filePosition, piece=$piece, window=$piece..$end")
        } else if (piece != lastDemand + 1) {
            Log.i(TORRENT_TAG, "Seek demand: fileOffset=$filePosition, piece=$piece, previous=$lastDemand, ready=${current.havePiece(piece)}, window=$piece..$end")
        }
        windowStart = piece
        windowEnd = end
        lastDemand = piece
    }

    fun awaitReadable(chosen: TorrentSelection, piece: Int, cancelled: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 90_000
        while (true) {
            if (cancelled() || Thread.currentThread().isInterrupted) throw InterruptedIOException("Torrent read cancelled")
            val ready = synchronized(lock) {
                ensureSelection(chosen)
                val current = requireHandle()
                if (flushed[piece]) {
                    true
                } else if (!current.havePiece(piece)) {
                    false
                } else {
                    // In libtorrent 2.1, havePiece may become true while disk
                    // writes are pending. The download queue retains those
                    // pieces until every block is written; the picker is only
                    // freed for a seed after maybe_done_flushing completes.
                    current.swig().get_download_queue(downloadQueue)
                    var written = true
                    for (index in 0 until downloadQueue.size) {
                        val partial = downloadQueue[index]
                        try {
                            if (partial.piece_index == piece &&
                                (partial.writing != 0 || partial.finished != partial.blocks_in_piece)
                            ) written = false
                        } finally {
                            partial.delete()
                        }
                    }
                    if (written && current.havePiece(piece)) {
                        flushed.set(piece)
                        true
                    } else false
                }
            }
            if (ready) return
            if (SystemClock.elapsedRealtime() >= deadline) {
                val error = IOException("Torrent buffering timed out after 90 seconds at piece $piece; no usable peer data or disk completion")
                fail(error.message!!)
                throw error
            }
            try {
                Thread.sleep(40)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("Torrent read cancelled").apply { initCause(interrupted) }
            }
        }
    }

    fun snapshot(): Pair<Int, Long> = synchronized(lock) {
        checkUsable()
        val current = requireHandle()
        val status = current.status(statusFlags)
        try {
            val error = status.errorCode()
            if (error.value != 0) throw IOException("Torrent error: ${error.message}")
            if (selection != null) checkDiskReserve()
            val done = selection?.let { downloadedBytes(current, it.video.file.index) } ?: 0L
            status.numPeers() to done
        } finally {
            status.swig().delete()
        }
    }

    fun fail(message: String) {
        recordFailure(message)
        synchronized(lock) {
            if (!stopped) handle?.pause()
        }
    }

    private fun recordFailure(message: String) {
        if (failure.compareAndSet(null, IOException(message))) {
            Log.e(TORRENT_TAG, message)
            onFailure(this, message)
        }
    }

    fun invalidate() {
        invalidated = true
    }

    fun shutdown() {
        invalidate()
        synchronized(lock) {
            if (stopped) return
            stopped = true
            try {
                handle?.let {
                    try {
                        if (it.isValid) session.remove(it)
                    } finally {
                        it.swig().delete()
                    }
                }
            } finally {
                handle = null
                info?.swig()?.delete()
                info = null
                // stop joins native alert/disk/network workers before the
                // engine can remove this session's cache directory.
                try {
                    session.stop()
                } finally {
                    session.removeListener(listener)
                    statusFlags.delete()
                    downloadQueue.delete()
                    fileProgress.delete()
                }
            }
        }
        Log.i(TORRENT_TAG, "Native torrent session stopped")
    }

    private fun checkStatus(current: TorrentHandle) {
        val status = current.status(statusFlags)
        try {
            val error = status.errorCode()
            if (error.value != 0) throw IOException("Torrent error: ${error.message}")
        } finally {
            status.swig().delete()
        }
    }

    private fun downloadedBytes(current: TorrentHandle, index: Int): Long {
        current.swig().file_progress(fileProgress, TorrentHandle.PIECE_GRANULARITY)
        return fileProgress[index]
    }

    private fun checkDiskReserve() {
        if (StatFs(directory.path).availableBytes < DISK_RESERVE + LOOKAHEAD_BYTES + 2L * pieceLength) {
            throw IOException("Storage reserve reached: torrent stopped to keep at least 1 GiB free")
        }
    }

    private fun checkUsable() {
        if (invalidated || stopped) throw InterruptedIOException("Torrent session stopped")
        failure.get()?.let { throw it }
    }

    private fun requireHandle(): TorrentHandle = handle ?: throw IOException("Torrent session is not ready")
    private fun mib(bytes: Long): Long = (bytes + 1024 * 1024 - 1) / (1024 * 1024)
}
