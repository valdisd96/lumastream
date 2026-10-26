package dev.lumastream.torrent

import android.net.Uri
import androidx.media3.datasource.DataSource

data class TorrentFile(val index: Int, val name: String, val size: Long)

enum class RequiredDataStatus { IDLE, DOWNLOADING, VERIFYING, WRITING }

data class TorrentState(
    val message: String = "Ready",
    val files: List<TorrentFile> = emptyList(),
    /** Unique verified selected-video bytes this selection, including subsequently evicted data. */
    val downloadedBytes: Long = 0,
    /** Native payload rate, including lookahead/re-downloads but excluding protocol overhead. */
    val downloadBytesPerSecond: Long = 0,
    val totalBytes: Long = 0,
    val cacheBytes: Long = 0,
    val cacheLimitBytes: Long = 0,
    /** Contiguous verified bytes beyond the extractor's video read cursor, not playback time. */
    val prefetchedBytes: Long = 0,
    val pausePrefetchStatus: String? = null,
    val peers: Int = 0,
    /** Only the piece blocking a reader, never rolling-cache fullness. */
    val requiredPieceIndex: Int? = null,
    val requiredBlocksLoaded: Int = 0,
    val requiredBlockCount: Int = 0,
    val requiredWaitMillis: Long = 0,
    val requiredDataStatus: RequiredDataStatus = RequiredDataStatus.IDLE,
    val error: String? = null,
)

data class TorrentPlayback(
    val dataSourceFactory: DataSource.Factory,
    val uri: Uri,
    val title: String,
    val videoIdentity: String,
    val selectedFile: TorrentFile,
    val subtitleCandidates: List<TorrentSubtitleCandidate>,
    internal val selectionSerial: Long,
)

/** Obtained only on an explicit external-search request, never during playback setup. */
data class TorrentMovieHashSample(val size: Long, val first: ByteArray, val last: ByteArray)
