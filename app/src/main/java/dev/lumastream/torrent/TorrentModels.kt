package dev.lumastream.torrent

import android.net.Uri
import androidx.media3.datasource.DataSource

data class TorrentFile(val index: Int, val name: String, val size: Long)

data class TorrentState(
    val message: String = "Ready",
    val files: List<TorrentFile> = emptyList(),
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val cacheBytes: Long = 0,
    val cacheLimitBytes: Long = 0,
    val peers: Int = 0,
    val error: String? = null,
)

data class TorrentPlayback(
    val dataSourceFactory: DataSource.Factory,
    val uri: Uri,
    val title: String,
)
