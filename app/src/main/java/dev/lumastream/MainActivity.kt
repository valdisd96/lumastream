package dev.lumastream

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import dev.lumastream.torrent.TorrentEngine
import dev.lumastream.torrent.TorrentState
import dev.lumastream.catalog.CatalogConfiguration
import dev.lumastream.catalog.CatalogConfigurationStore
import dev.lumastream.catalog.CatalogSelection
import dev.lumastream.catalog.catalogMagnet
import dev.lumastream.catalog.selectCatalogFile
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import android.app.AlertDialog
import androidx.media3.common.C
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.common.Format
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleExtractor
import dev.lumastream.history.WatchEntry
import dev.lumastream.history.WatchHistoryStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel

private const val PLAYER_TAG = "LumaPlayer"

@androidx.annotation.OptIn(UnstableApi::class)
class MainActivity : ComponentActivity() {
    private val model: PlaybackViewModel by viewModels()
    private var activePlayerView: PlayerView? = null
    private var navigatingPlayerActions = false
    private var focusedPlayerAction = androidx.media3.ui.R.id.exo_settings
    private val playerActionsListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            val view = activePlayerView ?: return
            // Media3 disables CC when there are no embedded tracks. Our CC also imports files.
            view.post {
                if (activePlayerView !== view) return@post
                view.findViewById<View>(androidx.media3.ui.R.id.exo_subtitle).apply {
                    isEnabled = true
                    alpha = 1f
                }
                if (navigatingPlayerActions) view.findViewById<View>(focusedPlayerAction).requestFocus()
            }
        }
    }

    private fun focusPlayerAction(view: PlayerView, id: Int) {
        navigatingPlayerActions = true
        focusedPlayerAction = id
        view.controllerShowTimeoutMs = 0
        view.showController()
        view.findViewById<View>(id).apply {
            // CC also imports external subtitles when the video has no text tracks.
            isEnabled = true
            isFocusable = true
            alpha = 1f
            requestFocus()
        }
    }

    private fun leavePlayerActions(view: PlayerView) {
        navigatingPlayerActions = false
        view.controllerShowTimeoutMs = 5_000
        view.findViewById<View>(androidx.media3.ui.R.id.exo_play_pause).requestFocus()
        view.showController()
    }
    private var choosingSubtitle = false
    private val subtitlePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        choosingSubtitle = false
        if (uri != null) model.loadSubtitle(uri)
    }

    private fun showSubtitles() {
        val player = model.screen.value.player ?: return
        val tracks = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
            .flatMap { group -> (0 until group.length).filter { group.isTrackSupported(it) }.map { group to it } }
        val labels = listOf("Off", "Choose external SRT / VTT…", "Load HTTPS subtitle URL…") + tracks.map { (group, index) ->
            val format = group.getTrackFormat(index)
            val name = format.label ?: format.language ?: "Subtitle ${index + 1}"
            if (group.isTrackSelected(index)) "$name (selected)" else name
        }
        AlertDialog.Builder(this).setTitle("Subtitles").setItems(labels.toTypedArray()) { _, choice ->
            when (choice) {
                0 -> player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
                1 -> {
                    player.pause()
                    try {
                        choosingSubtitle = true
                        subtitlePicker.launch(arrayOf("*/*"))
                    } catch (_: ActivityNotFoundException) {
                        choosingSubtitle = false
                        AlertDialog.Builder(this).setMessage("This TV has no document picker. Install a document picker to load external subtitles.")
                            .setPositiveButton("OK", null).show()
                    }
                }
                2 -> {
                    val input = android.widget.EditText(this).apply {
                        hint = "https://…/subtitles.srt"
                        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
                        setSingleLine()
                    }
                    AlertDialog.Builder(this).setTitle("SRT / WebVTT URL").setView(input)
                        .setPositiveButton("Load") { _, _ -> model.loadSubtitle(Uri.parse(input.text.toString().trim())) }
                        .setNegativeButton("Cancel", null).show()
                }
                else -> {
                    val (group, index) = tracks[choice - 3]
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index)).build()
                }
            }
        }.setNegativeButton("Cancel", null).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val originalCallback = window.callback
        window.callback = object : android.view.Window.Callback by originalCallback {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean =
                dispatchPlaybackKeyEvent(event) || originalCallback.dispatchKeyEvent(event)
        }
        if (savedInstanceState == null) model.acceptIntent(intent)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                PlaybackScreen(model, onExit = { finishAndRemoveTask() }, onPlayerViewChanged = { view ->
                    if (activePlayerView !== view) {
                        activePlayerView?.player?.removeListener(playerActionsListener)
                        navigatingPlayerActions = false
                        view?.player?.addListener(playerActionsListener)
                    }
                    activePlayerView = view
                    view?.findViewById<View>(androidx.media3.ui.R.id.exo_subtitle)
                        ?.setOnClickListener { showSubtitles() }
                })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        model.acceptIntent(intent)
    }

    private fun dispatchPlaybackKeyEvent(event: KeyEvent): Boolean {
        val view = activePlayerView
        val player = view?.player
        if (player != null && view.hasWindowFocus()) {
            if (navigatingPlayerActions) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (event.action == KeyEvent.ACTION_DOWN) {
                            focusPlayerAction(view, if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT)
                                androidx.media3.ui.R.id.exo_subtitle else androidx.media3.ui.R.id.exo_settings)
                        }
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                            if (view.findViewById<View>(androidx.media3.ui.R.id.exo_subtitle).hasFocus()) {
                                showSubtitles()
                            } else {
                                view.findViewById<View>(androidx.media3.ui.R.id.exo_settings).performClick()
                            }
                        }
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_BACK -> {
                        if (event.action == KeyEvent.ACTION_UP) leavePlayerActions(view)
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> return true
                }
            }
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                        player.playWhenReady = !player.playWhenReady
                        view.showController()
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) player.seekBack() else player.seekForward()
                        view.showController()
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) showSubtitles()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                        focusPlayerAction(view, androidx.media3.ui.R.id.exo_settings)
                    }
                    return true
                }
            }
        }
        val playbackKey = when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_REWIND,
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_HEADSETHOOK -> true
            else -> false
        }
        // Route through Media3, not Compose's focus owner. Popup windows keep their own input.
        if (playbackKey && view != null && view.player != null && view.hasWindowFocus()) {
            if (!view.hasFocus()) view.requestFocus()
            if (view.dispatchKeyEvent(event)) return true
        }
        return false
    }

    override fun onStop() {
        // A document picker temporarily covers playback; keep the paused session until its result.
        if (!choosingSubtitle) {
            activePlayerView = null
            model.stop("Stopped while LumaStream is in the background. Open a source to resume.")
            if (model.screen.value.page == AppPage.PLAYER) model.navigate(AppPage.CATALOG)
        }
        super.onStop()
    }
}

enum class AppPage { CATALOG, ARCHIVE, SETTINGS, PLAYER }

data class PlaybackScreenState(
    val page: AppPage = AppPage.CATALOG,
    val configuration: CatalogConfiguration = CatalogConfiguration(),
    val configurationSaving: Boolean = false,
    val configurationError: String? = null,
    val busy: Boolean = false,
    val notice: String? = null,
    val error: String? = null,
    val title: String = "",
    val player: ExoPlayer? = null,
    val history: List<WatchEntry> = emptyList(),
    val historyError: String? = null,
    val resumeSelection: CatalogSelection? = null,
    val resumeEntry: WatchEntry? = null,
)

@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackViewModel(application: Application) : AndroidViewModel(application) {
    private val engine = TorrentEngine(application)
    val torrent = engine.state
    private val mutableScreen = MutableStateFlow(PlaybackScreenState())
    val screen = mutableScreen.asStateFlow()
    private var command: Job? = null
    private var heartbeat: Job? = null
    private val configurationStore = CatalogConfigurationStore(application)
    private var videoSource: MediaSource? = null
    private var subtitleImport: Job? = null
    private val historyStore = WatchHistoryStore(application)
    private val historyReady = CompletableDeferred<Unit>()
    private val historyWrites = Channel<List<WatchEntry>>(Channel.CONFLATED)
    private var historyWritable = false
    private var activeSelection: CatalogSelection? = null
    private var renderedVideo = false

    private fun saveProgress() {
        val player = screen.value.player ?: return
        val selection = activeSelection ?: return
        // Failed preparation must not replace a previously usable resume point.
        if (!renderedVideo || !historyWritable) return
        val entry = WatchEntry(
            selection, player.currentPosition.coerceAtLeast(0),
            player.duration.coerceAtLeast(0), System.currentTimeMillis(),
            player.playbackState == Player.STATE_ENDED,
        )
        val entries = (listOf(entry) + screen.value.history.filterNot { it.key == entry.key }).take(100)
        mutableScreen.update { it.copy(history = entries) }
        historyWrites.trySend(entries)
    }

    fun requestCatalog(selection: CatalogSelection) {
        viewModelScope.launch {
            historyReady.await()
            val key = WatchEntry(selection, 0, 0, 0, false).key
            val previous = screen.value.history.firstOrNull { it.key == key }
            if (previous != null) {
                mutableScreen.update { it.copy(resumeSelection = selection, resumeEntry = previous) }
            } else {
                openCatalog(selection)
            }
        }
    }

    fun dismissResume() {
        mutableScreen.update { it.copy(resumeSelection = null, resumeEntry = null) }
    }

    fun confirmResume(startOver: Boolean) {
        val selection = screen.value.resumeSelection ?: return
        val entry = screen.value.resumeEntry ?: return
        dismissResume()
        openCatalog(selection, if (startOver || entry.completed) 0 else entry.positionMs)
    }

    fun resume(entry: WatchEntry, startOver: Boolean) {
        openCatalog(entry.selection, if (startOver || entry.completed) 0 else entry.positionMs)
    }

    fun loadSubtitle(uri: Uri) {
        val player = screen.value.player ?: return
        val video = videoSource ?: return
        subtitleImport?.cancel()
        subtitleImport = viewModelScope.launch {
            try {
                val imported = importSubtitle(getApplication(), uri)
                if (screen.value.player !== player) return@launch
                val format = Format.Builder().setSampleMimeType(imported.mimeType)
                    .setLabel("External subtitles").setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()
                val subtitle = ProgressiveMediaSource.Factory(
                    DataSource.Factory { ByteArrayDataSource(imported.bytes) },
                    ExtractorsFactory {
                        arrayOf(SubtitleExtractor(DefaultSubtitleParserFactory().create(format), format))
                    },
                ).createMediaSource(MediaItem.fromUri("memory://external-subtitle"))
                val position = player.currentPosition
                val playing = player.playWhenReady
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setPreferredTextLanguage(null).build()
                player.setMediaSource(MergingMediaSource(video, subtitle), position)
                player.prepare()
                player.playWhenReady = playing
                mutableScreen.update { it.copy(error = null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutableScreen.update { it.copy(error = "Unable to load subtitles: ${failure.message ?: "unreadable document"}") }
            }
        }
    }

    init {
        // This writer drains the final snapshot even after ViewModel cancellation.
        CoroutineScope(Dispatchers.IO).launch {
            for (entries in historyWrites) {
                try {
                    historyStore.save(entries)
                    mutableScreen.update { it.copy(historyError = null) }
                } catch (_: Exception) {
                    mutableScreen.update { it.copy(historyError = "Unable to save watch history. Your latest position may not survive closing the app.") }
                }
            }
        }
        viewModelScope.launch {
            try {
                val entries = historyStore.load()
                mutableScreen.update { it.copy(history = entries) }
                historyWritable = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableScreen.update { it.copy(historyError = "Unable to read watch history. Existing history has not been overwritten.") }
            } finally {
                historyReady.complete(Unit)
            }
        }
        viewModelScope.launch {
            try {
                val configuration = configurationStore.load()
                mutableScreen.update { it.copy(configuration = configuration) }
            } catch (_: Exception) {
                mutableScreen.update { it.copy(configurationError = "Unable to read catalog settings. Save them again.") }
            }
        }
    }

    fun navigate(page: AppPage) {
        mutableScreen.update { it.copy(page = page) }
    }

    fun saveConfiguration(configuration: CatalogConfiguration) {
        if (screen.value.configurationSaving) return
        mutableScreen.update { it.copy(configurationSaving = true, configurationError = null) }
        viewModelScope.launch {
            try {
                val saved = configurationStore.save(configuration)
                mutableScreen.update { it.copy(configuration = saved, page = AppPage.CATALOG) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutableScreen.update { it.copy(configurationError = failure.message ?: "Unable to save catalog settings.") }
            } finally {
                mutableScreen.update { it.copy(configurationSaving = false) }
            }
        }
    }

    private fun openCatalog(selection: CatalogSelection, positionMs: Long = 0) {
        navigate(AppPage.PLAYER)
        releasePlayer()
        runCommand {
            historyReady.await()
            val magnet = catalogMagnet(selection.source)
            mutableScreen.update {
                it.copy(busy = true, error = null, notice = "Opening selected source…", title = "")
            }
            engine.open(magnet)
            val selected = selectCatalogFile(selection, engine.state.value.files)
            val resolved = selection.copy(source = selection.source.copy(fileIndex = selected.index, filename = selected.name))
            startVideo(selected.index, resolved, positionMs)
        }
    }

    @Suppress("DEPRECATION")
    fun acceptIntent(intent: Intent?) {
        val uri = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                ?: intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim()
                    ?.takeIf { it.isNotEmpty() }?.let(Uri::parse)
            else -> return
        }
        if (uri == null) {
            mutableScreen.update { it.copy(error = "No torrent link or document was supplied.") }
        } else {
            open(uri)
        }
    }

    fun open(uri: Uri) {
        navigate(AppPage.PLAYER)
        when (uri.scheme?.lowercase(Locale.ROOT)) {
            "magnet", "https", "content", "file" -> Unit
            else -> {
                mutableScreen.update {
                    it.copy(error = "This source is not supported. Choose another title or source.")
                }
                return
            }
        }
        releasePlayer()
        runCommand {
            mutableScreen.update {
                it.copy(busy = true, error = null, notice = "Opening selected source…", title = "")
            }
            engine.open(uri)
            mutableScreen.update { it.copy(notice = "Choose a video file below to start playback.") }
        }
    }

    fun select(index: Int) {
        releasePlayer()
        runCommand { startVideo(index) }
    }

    private suspend fun startVideo(index: Int, selection: CatalogSelection? = null, positionMs: Long = 0) {
            mutableScreen.update { it.copy(busy = true, error = null, notice = "Preparing video…") }
            val playback = engine.select(index)
            activeSelection = selection
            renderedVideo = false
            val player = ExoPlayer.Builder(getApplication<Application>())
                .setLoadControl(
                    DefaultLoadControl.Builder()
                        .setBufferDurationsMs(5_000, 15_000, 1_000, 2_000)
                        .setTargetBufferBytes(16 * 1024 * 1024)
                        .setPrioritizeTimeOverSizeThresholds(false)
                        .build(),
                )
                .setSeekBackIncrementMs(10_000)
                .setSeekForwardIncrementMs(30_000)
                .setAudioAttributes(AudioAttributes.DEFAULT, true)
                .build()
            // Publish ownership before configuring Media3 so failures also release the player.
            mutableScreen.update { it.copy(player = player, title = playback.title, notice = null) }
            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    val name = when (playbackState) {
                        Player.STATE_IDLE -> "IDLE"
                        Player.STATE_BUFFERING -> "BUFFERING"
                        Player.STATE_READY -> "READY"
                        Player.STATE_ENDED -> "ENDED"
                        else -> playbackState.toString()
                    }
                    Log.i(PLAYER_TAG, "state=$name positionMs=${player.currentPosition}")
                    if (playbackState == Player.STATE_ENDED) stop("Playback finished. Open a source to play again.")
                }

                override fun onRenderedFirstFrame() {
                    renderedVideo = true
                    saveProgress()
                    val transfer = engine.state.value
                    Log.i(PLAYER_TAG, "first_frame title=${playback.title} positionMs=${player.currentPosition} downloadedBytes=${transfer.downloadedBytes} totalBytes=${transfer.totalBytes}")
                }

                override fun onTracksChanged(tracks: Tracks) {
                    for (group in tracks.groups) {
                        for (index in 0 until group.length) {
                            if (group.isTrackSelected(index)) {
                                val format = group.getTrackFormat(index)
                                Log.i(PLAYER_TAG, "selected_track type=${group.type} mime=${format.sampleMimeType} codecs=${format.codecs} width=${format.width} height=${format.height} channels=${format.channelCount} sampleRate=${format.sampleRate}")
                            }
                        }
                    }
                }

                override fun onAudioSessionIdChanged(audioSessionId: Int) {
                    Log.i(PLAYER_TAG, "audio_session id=$audioSessionId")
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    Log.i(PLAYER_TAG, "isPlaying=$isPlaying positionMs=${player.currentPosition}")
                    if (!isPlaying) saveProgress()
                }

                override fun onPlayerError(error: PlaybackException) {
                    Log.e(PLAYER_TAG, "playback_error code=${error.errorCodeName}", error)
                    stop("Playback stopped after an error.", "Playback failed: ${error.errorCodeName}. ${error.message.orEmpty()}")
                }

                override fun onPositionDiscontinuity(
                    oldPosition: Player.PositionInfo,
                    newPosition: Player.PositionInfo,
                    reason: Int,
                ) {
                    if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) {
                        val transfer = engine.state.value
                        Log.i(PLAYER_TAG, "seek fromMs=${oldPosition.positionMs} toMs=${newPosition.positionMs} reason=$reason downloadedBytes=${transfer.downloadedBytes} totalBytes=${transfer.totalBytes}")
                        saveProgress()
                    }
                }
            })
            val source = ProgressiveMediaSource.Factory(playback.dataSourceFactory)
                .createMediaSource(MediaItem.fromUri(playback.uri))
            videoSource = source
            player.setMediaSource(source, positionMs)
            player.prepare()
            player.playWhenReady = true
            heartbeat = viewModelScope.launch {
                while (isActive) {
                    if (player.isPlaying) {
                        Log.i(PLAYER_TAG, "playing positionMs=${player.currentPosition} bufferedMs=${player.bufferedPosition} durationMs=${player.duration}")
                        saveProgress()
                    }
                    delay(10_000)
                }
            }
    }

    fun stop(notice: String = "Stopped. Open a source to play again.", error: String? = null) {
        releasePlayer()
        runCommand {
            mutableScreen.update { it.copy(busy = true, notice = notice, error = error, title = "") }
            engine.stop()
        }
    }

    private fun runCommand(action: suspend () -> Unit) {
        val previous = command
        previous?.cancel()
        command = viewModelScope.launch {
            previous?.join()
            try {
                action()
            } catch (cancelled: CancellationException) {
                mutableScreen.update { it.copy(notice = "Operation cancelled.") }
                throw cancelled
            } catch (failure: Exception) {
                releasePlayer()
                Log.e(PLAYER_TAG, "operation_failed", failure)
                mutableScreen.update { it.copy(error = failure.message ?: "Unable to open or play this torrent.", notice = null) }
                try {
                    engine.stop()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (cleanupFailure: Exception) {
                    Log.e(PLAYER_TAG, "stop_failed", cleanupFailure)
                }
            } finally {
                mutableScreen.update { it.copy(busy = false) }
            }
        }
    }

    private fun releasePlayer() {
        saveProgress()
        activeSelection = null
        renderedVideo = false
        subtitleImport?.cancel()
        subtitleImport = null
        videoSource = null
        heartbeat?.cancel()
        heartbeat = null
        val player = screen.value.player
        mutableScreen.update { it.copy(player = null) }
        if (player != null) {
            Log.i(PLAYER_TAG, "release positionMs=${player.currentPosition}")
            player.release()
        }
    }

    override fun onCleared() {
        command?.cancel()
        releasePlayer()
        engine.close()
        historyWrites.close()
        super.onCleared()
    }
}

@Composable
@androidx.annotation.OptIn(UnstableApi::class)
private fun PlaybackScreen(model: PlaybackViewModel, onExit: () -> Unit, onPlayerViewChanged: (PlayerView?) -> Unit) {
    val screen by model.screen.collectAsState()
    val torrent by model.torrent.collectAsState()
    when (screen.page) {
        AppPage.CATALOG -> {
            MovieCatalogScreen(
                configuration = screen.configuration,
                onConfigure = { model.navigate(AppPage.SETTINGS) },
                onArchive = { model.navigate(AppPage.ARCHIVE) },
                onBack = onExit,
                onOpenSource = model::requestCatalog,
                history = screen.history,
                historyError = screen.historyError,
                onResume = model::resume,
            )
            val entry = screen.resumeEntry
            val selection = screen.resumeSelection
            if (entry != null && selection != null) {
                androidx.compose.ui.window.Dialog(onDismissRequest = model::dismissResume) {
                    Column(
                        Modifier.background(Color(0xFF192535)).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(selection.details.title.title, color = Color.White, fontSize = 24.sp)
                        val changedSource = !entry.selection.source.infoHash.equals(selection.source.infoHash, ignoreCase = true) ||
                            entry.selection.source.fileIndex != selection.source.fileIndex
                        Text(
                            if (changedSource) "Different source selected. Its cut or timing may differ. Transfer the saved position explicitly, or start from the beginning."
                            else if (entry.completed) "You finished this video."
                            else "Continue from ${entry.positionMs / 60_000}:${(entry.positionMs / 1000 % 60).toString().padStart(2, '0')}?",
                            color = Color.White,
                        )
                        val initialFocus = remember { FocusRequester() }
                        if (!entry.completed) RemoteButton(
                            if (changedSource) "Transfer saved position" else "Continue",
                            Modifier.focusRequester(initialFocus),
                        ) { model.confirmResume(false) }
                        RemoteButton("Start from beginning",
                            if (entry.completed) Modifier.focusRequester(initialFocus) else Modifier,
                        ) { model.confirmResume(true) }
                        RemoteButton("Cancel") { model.dismissResume() }
                        LaunchedEffect(entry.key) {
                            withFrameNanos { }
                            initialFocus.requestFocus()
                        }
                    }
                }
            }
            return
        }
        AppPage.ARCHIVE -> {
            ArchiveSearchScreen(
                onOpenTorrent = { model.open(Uri.parse(it)) },
                onBack = { model.navigate(AppPage.CATALOG) },
            )
            return
        }
        AppPage.SETTINGS -> {
            CatalogSettingsScreen(
                configuration = screen.configuration,
                saving = screen.configurationSaving,
                error = screen.configurationError,
                onSave = model::saveConfiguration,
                onBack = { model.navigate(AppPage.CATALOG) },
            )
            return
        }
        AppPage.PLAYER -> Unit
    }
    fun returnToCatalog() {
        model.stop()
        model.navigate(AppPage.CATALOG)
    }
    BackHandler(onBack = ::returnToCatalog)
    val player = screen.player
    if (player != null) {
        VideoSurface(player, screen.title, torrent, screen.error, onPlayerViewChanged)
        return
    }
    val backFocus = remember { FocusRequester() }
    val firstFileFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    LaunchedEffect(torrent.files, screen.busy) {
        if (torrent.files.isNotEmpty() && !screen.busy) {
            // Header, status and section heading precede the video choices.
            listState.scrollToItem(3)
            withFrameNanos { }
            firstFileFocus.requestFocus()
        } else {
            listState.scrollToItem(0)
            withFrameNanos { }
            backFocus.requestFocus()
        }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().background(Color(0xFF10131B)).padding(horizontal = 44.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("LumaStream", color = Color.White, fontSize = 30.sp)
                RemoteButton("Back to search", modifier = Modifier.focusRequester(backFocus), onClick = ::returnToCatalog)
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (screen.busy) CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                    Text(if (screen.busy) "Preparing your video…" else "Playback", color = Color.White, fontSize = 22.sp)
                }
                screen.notice?.let { Text(it, color = Color(0xFFB8D8FF), fontSize = 16.sp) }
                (screen.error ?: torrent.error)?.let {
                    Text(it, color = Color(0xFFFFA6A6), fontSize = 16.sp)
                    Text("Return to search and choose another source.", color = Color(0xFFBCC5D7), fontSize = 14.sp)
                }
            }
        }
        if (torrent.files.isNotEmpty()) {
            item { Text("Choose a video", color = Color.White, fontSize = 20.sp) }
            items(torrent.files, key = { it.index }) { file ->
                RemoteButton(
                    label = "${file.name}  ·  ${formatBytes(file.size)}",
                    modifier = Modifier.fillMaxWidth().then(
                        if (file.index == torrent.files.first().index) Modifier.focusRequester(firstFileFocus) else Modifier,
                    ),
                    enabled = !screen.busy,
                    onClick = { model.select(file.index) },
                )
            }
        }
    }
}

@Composable
private fun RemoteButton(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = ButtonDefaults.colors(
            containerColor = Color(0xFF283246),
            contentColor = Color.White,
            focusedContainerColor = Color(0xFFB8D8FF),
            focusedContentColor = Color(0xFF10213B),
        ),
    ) {
        androidx.tv.material3.Text(label, fontSize = 15.sp)
    }
}

@Composable
@androidx.annotation.OptIn(UnstableApi::class)
private fun VideoSurface(
    player: ExoPlayer,
    title: String,
    torrent: TorrentState,
    subtitleError: String?,
    onPlayerViewChanged: (PlayerView?) -> Unit,
) {
    var controlsVisible by remember(player) { mutableStateOf(true) }
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                PlayerView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    this.player = player
                    useController = true
                    controllerAutoShow = true
                    controllerShowTimeoutMs = 5_000
                    setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
                        controlsVisible = visibility == View.VISIBLE
                    })
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    setShowPreviousButton(false)
                    setShowNextButton(false)
                    setShowRewindButton(true)
                    setShowFastForwardButton(true)
                    setShowSubtitleButton(true)
                    keepScreenOn = true
                    isFocusable = true
                    descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
                    onPlayerViewChanged(this)
                    post { requestFocus(); showController() }
                }
            },
            update = { it.player = player },
            onRelease = {
                onPlayerViewChanged(null)
                it.player = null
                it.keepScreenOn = false
            },
        )
        if (controlsVisible || torrent.error != null || subtitleError != null) {
            Column(
                modifier = Modifier.align(Alignment.TopStart).fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.65f)).padding(horizontal = 30.dp, vertical = 10.dp),
            ) {
                Text(title, color = Color.White, fontSize = 17.sp, maxLines = 1)
                Text("${transferLabel(torrent)}  ·  Left/right: seek  ·  OK: pause/play  ·  Up: subtitles  ·  Down: CC/settings buttons  ·  Back: stop", color = Color(0xFFD3D9E5), fontSize = 12.sp)
                (subtitleError ?: torrent.error)?.let { Text(it, color = Color(0xFFFFA6A6), fontSize = 13.sp) }
            }
        }
    }
}

private fun transferLabel(state: TorrentState): String =
    "${formatBytes(state.downloadedBytes)} / ${formatBytes(state.totalBytes)} downloaded · ${state.peers} peers"

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> String.format(Locale.US, "%.2f GiB", bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MiB", bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> String.format(Locale.US, "%.0f KiB", bytes / 1024.0)
    else -> "$bytes B"
}
