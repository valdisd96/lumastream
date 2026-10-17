package dev.lumastream.torrent

import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.os.StatFs
import android.os.SystemClock
import android.util.Log
import androidx.media3.datasource.DataSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.libtorrent4j.AddTorrentParams
import org.libtorrent4j.LibTorrent
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection

internal const val TORRENT_TAG = "LumaTorrent"
internal const val DISK_RESERVE = 1024L * 1024 * 1024
internal const val LOOKAHEAD_BYTES = 8L * 1024 * 1024

class TorrentEngine(context: Context) : Closeable {
    private val app = context.applicationContext
    private val directory = File(app.cacheDir, "torrent-session")
    private val mutableState = MutableStateFlow(TorrentState())
    val state: StateFlow<TorrentState> = mutableState.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycle = Mutex()
    private val requests = Any()
    private var generation = 0L
    private var opening: Job? = null
    @Volatile private var closed = false
    @Volatile private var active: NativeTorrent? = null

    suspend fun open(uri: Uri): Unit = coroutineScope {
        val job = currentCoroutineContext()[Job]!!
        var owned: NativeTorrent? = null
        val ticket = synchronized(requests) {
            check(!closed) { "Torrent engine is closed" }
            opening?.cancel()
            opening = job
            active?.invalidate()
            ++generation
        }
        try {
            withContext(Dispatchers.IO) {
                lifecycle.withLock {
                    checkTicket(ticket)
                    disposeActive()
                    try {
                        prepareDirectory()
                        mutableState.value = TorrentState(message = "Opening torrent…")
                        Log.i(TORRENT_TAG, "JNI loaded: libtorrent ${LibTorrent.version()}, wrapper ${LibTorrent.libtorrent4jVersion()}")
                        val params = if (uri.scheme.equals("magnet", ignoreCase = true)) {
                            AddTorrentParams.parseMagnetUri(uri.toString())
                        } else {
                            loadTorrent(readMetadata(uri))
                        }
                        val torrent = NativeTorrent(directory) { owner, message ->
                            if (active === owner && !owner.invalidated) {
                                mutableState.update { it.copy(message = "Torrent error", error = message) }
                            }
                        }
                        active = torrent
                        owned = torrent
                        try {
                            torrent.start(params)
                        } finally {
                            params.swig().delete()
                        }
                        try {
                            withTimeout(90_000) {
                                while (!torrent.loadMetadata()) {
                                    checkTicket(ticket)
                                    delay(100)
                                }
                            }
                        } catch (_: TimeoutCancellationException) {
                            throw IOException("Torrent metadata timed out after 90 seconds. Check peers, trackers, and connectivity.")
                        }
                        checkTicket(ticket)
                        mutableState.value = TorrentState(
                            message = "Choose a video",
                            files = torrent.videos.map { it.file },
                        )
                        Log.i(TORRENT_TAG, "Metadata ready: ${torrent.videos.size} video(s), piece length=${torrent.pieceLength}")
                        scope.launch { monitor(torrent) }
                    } catch (failure: Throwable) {
                        withContext(NonCancellable) { disposeActive() }
                        currentCoroutineContext().ensureActive()
                        if (failure !is CancellationException) {
                            Log.e(TORRENT_TAG, "Open failed", failure)
                            mutableState.value = TorrentState(error = failure.message ?: "Unable to open torrent", message = "Torrent error")
                        }
                        throw failure
                    }
                }
            }
        } finally {
            if (!job.isActive) {
                owned?.invalidate()
                withContext(NonCancellable + Dispatchers.IO) {
                    lifecycle.withLock { if (owned != null && active === owned) disposeActive() }
                }
            }
            synchronized(requests) { if (opening === job) opening = null }
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    suspend fun select(index: Int): TorrentPlayback = withContext(Dispatchers.IO) {
        lifecycle.withLock {
            check(!closed) { "Torrent engine is closed" }
            val torrent = active ?: throw IOException("Open a torrent first")
            try {
                val selection = torrent.select(index)
                val uri = Uri.Builder().scheme("lumastream").authority("torrent")
                    .appendPath(selection.serial.toString()).appendPath(selection.video.file.name).build()
                mutableState.value = mutableState.value.copy(
                    message = "Buffering ${selection.video.file.name}",
                    totalBytes = selection.video.file.size,
                    downloadedBytes = 0,
                    error = null,
                )
                Log.i(TORRENT_TAG, "Selection: index=$index, bytes=${selection.video.file.size}, offset=${selection.video.torrentOffset}")
                TorrentPlayback(DataSource.Factory { TorrentDataSource(torrent, selection, uri) }, uri, selection.video.file.name)
            } catch (failure: CancellationException) {
                // A cancelled selection may have changed asynchronous native priorities.
                withContext(NonCancellable) { disposeActive() }
                throw failure
            } catch (failure: Throwable) {
                torrent.fail(failure.message ?: "Unable to select video")
                throw failure
            }
        }
    }

    suspend fun stop() {
        val ticket = synchronized(requests) {
            opening?.cancel()
            active?.invalidate()
            ++generation
        }
        withContext(NonCancellable + Dispatchers.IO) {
            lifecycle.withLock {
                if (synchronized(requests) { ticket == generation }) {
                    disposeActive()
                    mutableState.value = TorrentState()
                }
            }
        }
    }

    override fun close() {
        synchronized(requests) {
            if (closed) return
            closed = true
            ++generation
            opening?.cancel()
            active?.invalidate()
        }
        scope.launch {
            try {
                lifecycle.withLock { disposeActive() }
            } catch (failure: Exception) {
                Log.e(TORRENT_TAG, "Torrent cleanup failed", failure)
                mutableState.update { it.copy(message = "Cleanup error", error = failure.message) }
            } finally {
                scope.cancel()
            }
        }
    }

    private suspend fun checkTicket(ticket: Long) {
        currentCoroutineContext().ensureActive()
        if (synchronized(requests) { closed || ticket != generation }) throw CancellationException("Torrent replaced")
    }

    private fun prepareDirectory() {
        if (directory.exists() && !directory.deleteRecursively()) throw IOException("Cannot remove old torrent cache")
        if (!directory.mkdirs() && !directory.isDirectory) throw IOException("Cannot create torrent cache")
        if (StatFs(directory.path).availableBytes < DISK_RESERVE + LOOKAHEAD_BYTES) {
            throw IOException("Not enough storage: LumaStream must keep at least 1 GiB free")
        }
    }

    private fun disposeActive() {
        val old = active
        active = null
        old?.shutdown()
        if (directory.exists() && !directory.deleteRecursively()) {
            val message = "Unable to remove torrent cache: ${directory.path}"
            Log.e(TORRENT_TAG, message)
            mutableState.update { it.copy(message = "Cleanup error", error = message) }
            throw IOException(message)
        }
    }

    private suspend fun monitor(torrent: NativeTorrent) {
        while (active === torrent && !torrent.invalidated) {
            try {
                val snapshot = torrent.snapshot()
                if (active === torrent && !torrent.invalidated) {
                    mutableState.update { it.copy(peers = snapshot.first, downloadedBytes = snapshot.second) }
                }
            } catch (failure: Exception) {
                if (torrent.invalidated) return
                torrent.fail(failure.message ?: "Torrent session failed")
                return
            }
            delay(1000)
        }
    }

    private suspend fun readMetadata(uri: Uri): ByteArray = try {
        withTimeout(60_000) { fetchMetadata(uri) }
    } catch (_: TimeoutCancellationException) {
        throw IOException("Torrent document read timed out after 60 seconds")
    }

    private suspend fun fetchMetadata(uri: Uri): ByteArray {
        return when (uri.scheme?.lowercase()) {
            "content", "file" -> {
                val signal = CancellationSignal()
                cancellableIo(signal::cancel) {
                    val descriptor = app.contentResolver.openAssetFileDescriptor(uri, "r", signal)
                        ?: throw IOException("Cannot open torrent document")
                    descriptor.use { it.createInputStream().use { input -> readBounded(input) } }
                }
            }
            "https" -> {
                var url = URL(uri.toString())
                repeat(6) { redirect ->
                    currentCoroutineContext().ensureActive()
                    val connection = url.openConnection() as HttpsURLConnection
                    try {
                        val response = cancellableIo(connection::disconnect) {
                            connection.connectTimeout = 15_000
                            connection.readTimeout = 15_000
                            connection.instanceFollowRedirects = false
                            connection.setRequestProperty("Accept", "application/x-bittorrent, application/octet-stream")
                            val code = connection.responseCode
                            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                                val location = connection.getHeaderField("Location") ?: throw IOException("Torrent redirect has no location")
                                url = URL(url, location)
                                if (url.protocol != "https") throw IOException("Torrent redirects must remain HTTPS")
                                if (redirect == 5) throw IOException("Too many torrent URL redirects")
                                null
                            } else {
                                if (code !in 200..299) throw IOException("Torrent URL returned HTTP $code")
                                if (connection.contentLengthLong > MAX_METADATA_BYTES) throw IOException("Torrent metadata exceeds 2 MiB")
                                connection.inputStream.use { readBounded(it) }
                            }
                        }
                        if (response != null) return response
                    } finally {
                        connection.disconnect()
                    }
                }
                throw IOException("Unable to fetch torrent URL")
            }
            else -> throw IOException("Use a magnet link, HTTPS torrent URL, or .torrent document")
        }
    }

    private suspend fun readBounded(input: InputStream): ByteArray = cancellableIo(input::close) {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        val started = SystemClock.elapsedRealtime()
        while (true) {
            currentCoroutineContext().ensureActive()
            if (SystemClock.elapsedRealtime() - started > 60_000) throw IOException("Torrent document read timed out")
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size() + count > MAX_METADATA_BYTES) throw IOException("Torrent metadata exceeds 2 MiB")
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    }

    private suspend fun <T> cancellableIo(cancelIo: () -> Unit, block: suspend () -> T): T = coroutineScope {
        val finished = AtomicBoolean()
        // Android document pipes and HTTPS reads do not consistently honor a
        // thread interrupt. Close/cancel their underlying resource on another
        // IO worker when this operation's coroutine is cancelled.
        val closer = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                if (!finished.get()) runCatching(cancelIo)
            }
        }
        try {
            block()
        } finally {
            finished.set(true)
            closer.cancel()
        }
    }
}
