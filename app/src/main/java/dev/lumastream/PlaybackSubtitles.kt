package dev.lumastream

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleExtractor
import dev.lumastream.catalog.CatalogConfiguration
import dev.lumastream.catalog.CatalogSelection
import dev.lumastream.subtitle.OpenSubtitlesClient
import dev.lumastream.subtitle.ProviderSubtitle
import dev.lumastream.subtitle.SubtitleDelayStore
import dev.lumastream.subtitle.SubtitleSearchRequest
import dev.lumastream.subtitle.embeddedSubtitleIdentity
import dev.lumastream.subtitle.externalSubtitleIdentity
import dev.lumastream.subtitle.matchesSubtitleLanguage
import dev.lumastream.subtitle.preferredAudioLanguage
import dev.lumastream.subtitle.openSubtitlesMovieHash
import dev.lumastream.torrent.TorrentEngine
import dev.lumastream.torrent.TorrentPlayback
import dev.lumastream.torrent.TorrentSubtitleCandidate
import dev.lumastream.torrent.subtitleToAutoSelect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Owns subtitle operations for exactly one player; stale async results cannot replace a new video. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class PlaybackSubtitles(
    private val context: Context,
    private val engine: TorrentEngine,
    private val scope: CoroutineScope,
    private val onDelay: (Long) -> Unit,
    private val onMessage: (String?) -> Unit,
    private val onError: (String?) -> Unit,
) {
    private val provider = OpenSubtitlesClient()
    private val delays = SubtitleDelayStore(context)
    private data class DelayWrite(val video: String, val track: String, val delay: Long)
    private val writes = Channel<DelayWrite>(32)
    private val recentDelays = LinkedHashMap<Pair<String, String>, Long>()
    private var player: ExoPlayer? = null
    private var playback: TorrentPlayback? = null
    private var selection: CatalogSelection? = null
    private var configuration = CatalogConfiguration()
    private var videoSource: MediaSource? = null
    private var externalSource: MediaSource? = null
    private var activeTrack: String? = null
    private var delayMs = 0L
    private var importJob: Job? = null
    private var restoreJob: Job? = null
    private var automaticSelection = true
    private var sidecarAttempted = false
    private var pendingExternalTrack: String? = null
    private var subtitlesOff = false
    private var importRevision = 0L

    init {
        // A bounded writer drains accepted updates even when the Activity/ViewModel closes.
        CoroutineScope(Dispatchers.IO).launch {
            for (write in writes) {
                try {
                    delays.set(write.video, write.track, write.delay)
                } catch (_: Exception) {
                    withContext(Dispatchers.Main) { onError("Unable to save subtitle timing. This adjustment may not survive closing the app.") }
                }
            }
        }
    }

    fun bind(player: ExoPlayer, playback: TorrentPlayback, selection: CatalogSelection?, configuration: CatalogConfiguration, source: MediaSource) {
        unbind()
        this.player = player
        this.playback = playback
        this.selection = selection
        this.configuration = configuration
        videoSource = source
        val audioLanguage = preferredAudioLanguage(configuration.audioLanguage, selection?.details?.title?.originalLanguage)
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setPreferredAudioLanguage(audioLanguage)
            .setPreferredTextLanguage(configuration.subtitleLanguage)
            .setSelectUndeterminedTextLanguage(false)
            // Explicitly choose a matching track once metadata arrives: no other-language fallback.
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
    }

    fun candidates(): List<TorrentSubtitleCandidate> = playback?.subtitleCandidates.orEmpty()

    fun selectTrack(group: Tracks.Group?, index: Int = 0) {
        val current = player ?: return
        automaticSelection = false
        subtitlesOff = group == null
        pendingExternalTrack = null
        importRevision++
        importJob?.cancel()
        onMessage(null)
        if (group == null) {
            restoreJob?.cancel()
            activeTrack = null
            current.trackSelectionParameters = current.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
            applyDelay(0)
        } else {
            current.trackSelectionParameters = current.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index)).build()
        }
    }

    fun onTracksChanged(tracks: Tracks) {
        val current = player ?: return
        if (subtitlesOff) return
        val available = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
            .flatMap { group -> (0 until group.length).filter { group.isTrackSupported(it) }.map { group to it } }
        pendingExternalTrack?.let { wanted ->
            val match = available.firstOrNull { (group, index) -> group.getTrackFormat(index).id == wanted }
            if (match != null) {
                val (group, index) = match
                if (!group.isTrackSelected(index)) {
                    current.trackSelectionParameters = current.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index)).build()
                    return
                }
                pendingExternalTrack = null
            }
        }
        if (automaticSelection) {
            val matching = available.filter { (group, index) -> matchesSubtitleLanguage(group.getTrackFormat(index).language, configuration.subtitleLanguage) }
                .minByOrNull { (group, index) -> if (group.getTrackFormat(index).selectionFlags and C.SELECTION_FLAG_FORCED != 0) 1 else 0 }
            if (matching != null) {
                val (group, index) = matching
                if (!group.isTrackSelected(index)) {
                    current.trackSelectionParameters = current.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index)).build()
                    return
                }
            } else if (!sidecarAttempted && tracks.groups.any { it.type == C.TRACK_TYPE_VIDEO }) {
                sidecarAttempted = true
                subtitleToAutoSelect(candidates(), configuration.subtitleLanguage)?.let { loadTorrent(it, automatic = true) }
            }
        }
        val selected = available.firstOrNull { (group, index) -> group.isTrackSelected(index) } ?: return
        val (group, index) = selected
        val format = group.getTrackFormat(index)
        // MergingMediaSource prefixes the video's group IDs; that is not a new embedded track.
        val groupId = if (externalSource != null) group.mediaTrackGroup.id.removePrefix("0:") else group.mediaTrackGroup.id
        val identity = format.id?.takeIf { it.startsWith("external:") }
            ?: embeddedSubtitleIdentity(groupId, index, format)
        restoreDelay(identity)
    }

    private fun restoreDelay(identity: String) {
        if (activeTrack == identity) return
        activeTrack = identity
        restoreJob?.cancel()
        val current = player ?: return
        val video = playback?.videoIdentity ?: return
        // Never let a previous track's correction leak while the new preference is read.
        applyDelay(0)
        restoreJob = scope.launch {
            try {
                val saved = recentDelays[video to identity] ?: delays.get(video, identity)
                if (player === current && activeTrack == identity) applyDelay(saved)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (player === current) onError("Unable to restore saved subtitle timing; using zero offset.")
            }
        }
    }

    fun setDelay(value: Long) {
        val video = playback?.videoIdentity ?: return
        val track = activeTrack
        if (track == null) {
            onError("Select a subtitle track before adjusting its timing.")
            return
        }
        restoreJob?.cancel()
        val bounded = value.coerceIn(-30_000, 30_000)
        applyDelay(bounded)
        recentDelays.remove(video to track)
        recentDelays[video to track] = bounded
        while (recentDelays.size > 128) recentDelays.remove(recentDelays.keys.first())
        if (!writes.trySend(DelayWrite(video, track, bounded)).isSuccess) {
            onError("Unable to queue subtitle timing for saving. Try Apply again.")
        }
    }

    private fun applyDelay(value: Long) {
        if (delayMs == value) return
        delayMs = value
        onDelay(value)
        player?.let {
            Log.i("LumaPlayer", "subtitle_delay_ms=$value positionMs=${it.currentPosition}")
            prepare(it)
        }
    }

    fun loadUri(uri: Uri) = load("Loading subtitle…", manual = true) {
        importSubtitle(context, uri) to "External subtitles"
    }

    fun loadTorrent(candidate: TorrentSubtitleCandidate, automatic: Boolean = false) {
        val token = playback ?: return
        load("Loading torrent subtitle: ${candidate.name}", manual = !automatic, language = candidate.language) {
            val bytes = engine.readSubtitle(token, candidate)
            withContext(Dispatchers.Default) { parseSubtitle(bytes) } to candidate.name.substringAfterLast('/')
        }
    }

    fun loadOnline(candidate: ProviderSubtitle) {
        if (!configuration.isOnlineSubtitlesConfigured) {
            onError("Online subtitles are disabled. Enable and configure OpenSubtitles in Settings to use them.")
            return
        }
        val apiKey = configuration.openSubtitlesApiKey
        load("Downloading selected subtitle…", manual = true, language = candidate.language) {
            val result = provider.download(apiKey, candidate)
            withContext(Dispatchers.Default) { parseSubtitle(result.bytes) } to result.filename
        }
    }

    suspend fun searchOnline(): List<ProviderSubtitle> {
        val current = player ?: error("Open a video first.")
        val token = playback ?: error("Open a video first.")
        val config = configuration
        require(config.isOnlineSubtitlesConfigured) {
            "Online subtitles are disabled or not configured. Enable and configure OpenSubtitles in Settings to use them."
        }
        val catalog = selection
        // Only this explicit user action may fetch file ends for release-specific hash matching.
        val sample = try {
            engine.readMovieHashSample(token)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null // Catalog identity and strict release matching remain available without a hash.
        }
        check(player === current && playback === token) { "Playback changed. Search again for the current video." }
        val request = SubtitleSearchRequest(
            videoFilename = token.selectedFile.name,
            imdbId = catalog?.details?.imdbId,
            season = catalog?.episode?.season,
            episode = catalog?.episode?.number,
            language = config.subtitleLanguage,
            movieHash = sample?.let { openSubtitlesMovieHash(it.size, it.first, it.last) },
        )
        val results = provider.search(config.openSubtitlesApiKey, request)
        check(player === current && playback === token) { "Playback changed. Search again for the current video." }
        return results
    }

    private fun load(message: String, manual: Boolean, language: String? = null, action: suspend () -> Pair<ImportedSubtitle, String>) {
        val current = player ?: return
        if (manual) automaticSelection = false
        importJob?.cancel()
        val revision = ++importRevision
        onMessage(message)
        importJob = scope.launch {
            try {
                val (imported, label) = action()
                ensureActive()
                if (player !== current) return@launch
                val identity = externalSubtitleIdentity(imported.bytes)
                val format = Format.Builder().setId(identity).setSampleMimeType(imported.mimeType)
                    .setLanguage(language).setLabel(label).setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()
                val subtitleSource = ProgressiveMediaSource.Factory(
                    DataSource.Factory { ByteArrayDataSource(imported.bytes) },
                    ExtractorsFactory { arrayOf(SubtitleExtractor(DefaultSubtitleParserFactory().create(format), format)) },
                ).createMediaSource(MediaItem.fromUri("memory://external-subtitle"))
                val video = playback?.videoIdentity ?: return@launch
                var restoreError: String? = null
                val savedDelay = try { recentDelays[video to identity] ?: delays.get(video, identity) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { restoreError = "Unable to restore saved subtitle timing; using zero offset."; 0L }
                ensureActive()
                if (player !== current) return@launch
                restoreJob?.cancel()
                externalSource = CompleteSubtitleMediaSource(subtitleSource)
                activeTrack = identity
                pendingExternalTrack = identity
                subtitlesOff = false
                delayMs = savedDelay
                onDelay(delayMs)
                current.trackSelectionParameters = current.trackSelectionParameters.buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setPreferredTextLanguage(language).build()
                // Imported choice must win over an embedded default and stay selected after reprepare.
                automaticSelection = false
                prepare(current)
                onError(restoreError)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (player === current) onError("Unable to load subtitles: ${failure.message ?: "unreadable subtitles"}")
            } finally {
                if (player === current && importRevision == revision) onMessage(null)
            }
        }
    }

    private fun prepare(current: ExoPlayer) {
        val video = videoSource ?: return
        val source = externalSource?.let { MergingMediaSource(video, it) } ?: video
        val position = current.currentPosition
        val playing = current.playWhenReady
        current.setMediaSource(if (delayMs == 0L) source else SubtitleOffsetMediaSource(source, delayMs), position)
        current.prepare()
        current.playWhenReady = playing
    }

    fun unbind() {
        importRevision++
        importJob?.cancel()
        restoreJob?.cancel()
        importJob = null
        restoreJob = null
        player = null
        playback = null
        selection = null
        videoSource = null
        externalSource = null
        activeTrack = null
        pendingExternalTrack = null
        subtitlesOff = false
        delayMs = 0
        automaticSelection = true
        sidecarAttempted = false
        onDelay(0)
        onMessage(null)
    }

    fun close() { unbind(); writes.close() }
}
