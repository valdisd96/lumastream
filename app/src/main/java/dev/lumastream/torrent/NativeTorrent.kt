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
import org.libtorrent4j.swig.bitfield
import org.libtorrent4j.swig.int_bitfield_map
import org.libtorrent4j.swig.int64_vector
import org.libtorrent4j.swig.partial_piece_info_vector
import org.libtorrent4j.swig.settings_pack
import org.libtorrent4j.swig.status_flags_t
import org.libtorrent4j.swig.torrent_info
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import java.io.EOFException
import java.util.BitSet
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal data class TorrentSelection(val video: VideoFile, val serial: Long)
internal data class TorrentSnapshot(val peers: Int, val downloadedBytes: Long, val cacheBytes: Long, val cacheLimit: Long)

private data class StorageFile(val path: File, val offset: Long, val size: Long)

/** Native handles never escape this owner; alert callbacks copy only scalar data. */
internal class NativeTorrent(
    private val directory: File,
    private val onFailure: (NativeTorrent, String) -> Unit,
    private val maximumCacheBytes: Long = 4L * 1024 * 1024 * 1024,
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
    private var cache: RollingPieceWindow? = null
    private var cacheLimit = 0L
    private var storageOverhead = 0L
    private var storageFiles: List<StorageFile> = emptyList()
    private var reader: RandomAccessFile? = null
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
        if (info == null && metadata != null) videoFiles(metadata, directory)
        if (info == null) SparseFiles.verifySupport(directory)
        session.addListener(listener)
        if (!session.isRunning) startSession()
        params.savePath = directory.path
        params.storageMode = StorageMode.STORAGE_MODE_SPARSE
        params.maxConnections = 32
        params.maxUploads = 4
        params.flags = params.flags.and_(TorrentFlags.AUTO_MANAGED.inv()).and_(TorrentFlags.PAUSED.inv())
            .or_(TorrentFlags.UPLOAD_MODE).or_(TorrentFlags.DEFAULT_DONT_DOWNLOAD)
        if (metadata != null) {
            params.filePriorities(Priority.array(Priority.DEFAULT, metadata.numFiles()))
            params.piecePriorities(Priority.array(Priority.IGNORE, metadata.numPieces()))
        }
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

    private fun startSession() {
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
        val sessionParams = SessionParams(settings)
        try {
            sessionParams.setPosixDiskIO()
            session.start(sessionParams)
        } finally {
            sessionParams.swig().delete()
            settings.swig().delete()
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
            val storage = owned.files()
            storageFiles = (0 until storage.numFiles()).filterNot { storage.padFileAt(it) }.map {
                StorageFile(File(directory, storage.filePath(it)), storage.fileOffset(it), storage.fileSize(it))
            }
            val parents = HashSet<File>()
            for (file in storageFiles) {
                var parent = file.path.parentFile
                while (parent != null && parent != directory) {
                    parents.add(parent)
                    parent = parent.parentFile
                }
            }
            // Reserve filesystem-block tails, directories, and native bookkeeping separately.
            val filesystem = StatFs(directory.path)
            storageOverhead = (storageFiles.size + parents.size + 1L) * maxOf(8192, 2 * filesystem.blockSizeLong) + 8L * 1024 * 1024
            val available = filesystem.availableBytes
            cacheLimit = minOf(maximumCacheBytes, available - DISK_RESERVE - LOOKAHEAD_BYTES - 2L * pieceLength)
            val minimumPayload = minOf(owned.numPieces().toLong(), 3 + 2 * ((LOOKAHEAD_BYTES + pieceLength - 1) / pieceLength)) * pieceLength
            if (cacheLimit - storageOverhead < minimumPayload) {
                throw IOException("Not enough storage for the rolling buffer and a 1 GiB free-space reserve")
            }
            cache = RollingPieceWindow(owned.numPieces(), pieceLength, cacheLimit - storageOverhead)
            Log.i(TORRENT_TAG, "Rolling cache: limitBytes=$cacheLimit payloadBytes=${cache!!.capacityBytes} overheadBytes=$storageOverhead")
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
            reader?.close()
            reader = null
            selection = null
            windowStart = -1
            windowEnd = -1
            lastDemand = -1
            checkDiskReserve()
            chosen = TorrentSelection(video, selectionSerial)
            // Piece priorities, not file priorities, bound downloads. Keeping all files
            // enabled stores shared boundary blocks in their real files, never a partfile.
            val priorities = Priority.array(Priority.DEFAULT, info!!.numFiles())
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
        val policy = cache ?: throw IOException("Rolling cache is not ready")
        checkDiskReserve()
        val lastPiece = ((chosen.video.torrentOffset + chosen.video.file.size - 1) / pieceLength).toInt()
        val end = policy.windowEnd(piece, lastPiece)
        if (policy.needsEviction(piece, end)) {
            rotate(chosen, piece, lastPiece)
        }
        policy.admit(piece, end)
        val current = requireHandle()
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

    private fun rotate(chosen: TorrentSelection, demand: Int, lastPiece: Int) {
        val policy = cache!!
        val firstPiece = (chosen.video.torrentOffset / pieceLength).toInt()
        val keepStart = policy.retainedStart(demand, firstPiece)
        val keepEnd = policy.retainedEnd(demand, lastPiece)
        val current = requireHandle()
        current.setFlags(TorrentFlags.UPLOAD_MODE)
        current.pause()
        val resume = AddTorrentParams(current.swig().get_resume_data(TorrentHandle.SAVE_INFO_DICT))
        val sessionState = SessionParams(session.swig().session_state())
        try {
            // No disk mutation until all native disk/network workers have joined.
            // The listener never takes lock, so stop cannot deadlock on callbacks.
            session.stop()
            session.removeListener(listener)
            current.swig().delete()
            handle = null
            reader?.close()
            reader = null
            checkUsable()
            val before = allocatedBytes()
            val keepByteStart = keepStart.toLong() * pieceLength
            val keepByteEnd = (keepEnd.toLong() + 1) * pieceLength
            for (file in storageFiles) {
                if (!file.path.exists()) continue
                val range = retainedFileRange(file.offset, file.size, keepByteStart, keepByteEnd)
                if (range == null) {
                    SparseFiles.punch(file.path.path, 0, file.size)
                } else {
                    if (range.first > 0) SparseFiles.punch(file.path.path, 0, range.first)
                    val end = range.last + 1
                    if (end < file.size) SparseFiles.punch(file.path.path, end, file.size - end)
                }
            }
            val oldHave = resume.swig().get_have_pieces()
            val retained = bitfield(info!!.numPieces(), false)
            val unfinished = int_bitfield_map()
            try {
                for (piece in keepStart..keepEnd) {
                    if (piece < oldHave.size() && oldHave.get_bit(piece)) retained.set_bit(piece)
                }
                resume.swig().set_have_pieces(retained)
                resume.swig().set_verified_pieces(retained)
                // Partial blocks are not reusable after a restart without their exact map.
                resume.swig().set_unfinished_pieces(unfinished)
            } finally {
                oldHave.delete()
                retained.delete()
                unfinished.delete()
            }
            resume.flags = resume.flags.and_(TorrentFlags.SEED_MODE.inv())
            policy.reset(keepStart, keepEnd)
            flushed.clear()
            windowStart = -1
            windowEnd = -1
            lastDemand = -1
            val after = allocatedBytes()
            if (after > cacheLimit) throw IOException("Rolling cache could not reclaim enough storage")
            checkUsable()
            // Restore DHT routing state; resume data retains trackers, web seeds and peers.
            sessionState.setPosixDiskIO()
            session.start(sessionState)
            start(resume)
            requireHandle().unsetFlags(TorrentFlags.UPLOAD_MODE)
            requireHandle().resume()
            Log.i(TORRENT_TAG, "Cache eviction: keep=$keepStart..$keepEnd beforeBytes=$before afterBytes=$after limitBytes=$cacheLimit demand=$demand")
        } finally {
            sessionState.swig().delete()
            resume.swig().delete()
        }
    }

    fun read(chosen: TorrentSelection, position: Long, buffer: ByteArray, offset: Int, count: Int, cancelled: () -> Boolean): Int {
        val piece = ((chosen.video.torrentOffset + position) / pieceLength).toInt()
        while (true) {
            demand(chosen, position)
            awaitReadable(chosen, piece, cancelled)
            synchronized(lock) {
                ensureSelection(chosen)
                if (cancelled() || Thread.currentThread().isInterrupted) throw InterruptedIOException("Torrent read cancelled")
                // Another reader may have moved the window while awaitReadable released lock.
                if (flushed[piece]) {
                    val input = reader ?: RandomAccessFile(chosen.video.path, "r").also { reader = it }
                    input.seek(position)
                    val bytes = input.read(buffer, offset, count)
                    if (bytes < 0) throw EOFException("Verified torrent piece is missing from disk")
                    return bytes
                }
            }
        }
    }

    private fun allocatedBytes(): Long {
        var bytes = 0L
        for (file in storageFiles) {
            if (file.path.exists()) bytes += SparseFiles.allocatedBytes(file.path.path)
        }
        return bytes + storageOverhead
    }

    private fun awaitReadable(chosen: TorrentSelection, piece: Int, cancelled: () -> Boolean) {
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

    fun snapshot(): TorrentSnapshot = synchronized(lock) {
        checkUsable()
        val current = requireHandle()
        val status = current.status(statusFlags)
        try {
            val error = status.errorCode()
            if (error.value != 0) throw IOException("Torrent error: ${error.message}")
            if (selection != null) checkDiskReserve()
            val done = selection?.let { downloadedBytes(current, it.video.file.index) } ?: 0L
            val allocated = allocatedBytes()
            if (selection != null && allocated > cacheLimit) throw IOException("Torrent cache exceeded its disk limit")
            TorrentSnapshot(status.numPeers(), done, allocated, cacheLimit)
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
            val closeFailure = try {
                reader?.close()
                null
            } catch (error: IOException) {
                error
            }
            reader = null
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
            closeFailure?.let { throw it }
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
}
