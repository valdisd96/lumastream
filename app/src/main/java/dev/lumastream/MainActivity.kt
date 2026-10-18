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
import androidx.compose.runtime.DisposableEffect
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
import androidx.lifecycle.lifecycleScope
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
import dev.lumastream.history.WatchEntry
import dev.lumastream.history.WatchHistoryStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import dev.lumastream.subtitle.ProviderSubtitle
import dev.lumastream.torrent.TorrentSubtitleCandidate

private const val PLAYER_TAG = "LumaPlayer"

@androidx.annotation.OptIn(UnstableApi::class)
class MainActivity : ComponentActivity() {
    private val model: PlaybackViewModel by viewModels()
    private var activePlayerView: PlayerView? = null
    private var navigatingPlayerActions = false
    private var focusedPlayerAction = androidx.media3.ui.R.id.exo_settings
    private var subtitleSearch: Job? = null
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
    private fun finishPlayerMenu() {
        activePlayerView?.let(::leavePlayerActions)
    }

    private val subtitlePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        choosingSubtitle = false
        finishPlayerMenu()
        if (uri != null) model.loadSubtitle(uri)
    }

    private fun showSubtitles() {
        val player = model.screen.value.player ?: return
        finishPlayerMenu()
        val tracks = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
            .flatMap { group -> (0 until group.length).filter { group.isTrackSupported(it) }.map { group to it } }
        val labels = listOf("Off", "Choose external SRT / VTT…", "Load HTTPS subtitle URL…",
            "Subtitle timing (${subtitleDelayLabel(model.screen.value.subtitleDelayMs)})…",
            "Subtitles in this torrent (${model.torrentSubtitles().size})…",
            "Find subtitles online (${model.screen.value.configuration.subtitleLanguage})…") + tracks.map { (group, index) ->
            val format = group.getTrackFormat(index)
            val name = format.label ?: format.language ?: "Subtitle ${index + 1}"
            if (group.isTrackSelected(index)) "$name (selected)" else name
        }
        AlertDialog.Builder(this).setTitle("Subtitles").setItems(labels.toTypedArray()) { _, choice ->
            when (choice) {
                0 -> model.selectSubtitle(null)
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
                        .setNegativeButton("Cancel", null).setOnDismissListener { finishPlayerMenu() }.show()
                }
                3 -> showSubtitleTiming()
                4 -> showTorrentSubtitles()
                5 -> showOnlineSubtitles()
                else -> {
                    val (group, index) = tracks[choice - 6]
                    model.selectSubtitle(group, index)
                }
            }
        }.setNegativeButton("Cancel", null).setOnDismissListener { finishPlayerMenu() }.show()
    }

    private fun showTorrentSubtitles() {
        val candidates = model.torrentSubtitles()
        if (candidates.isEmpty()) {
            AlertDialog.Builder(this).setTitle("Torrent subtitles")
                .setMessage("No matching SRT / WebVTT files were found for this video. Try Find subtitles online.")
                .setPositiveButton("OK", null).setOnDismissListener { finishPlayerMenu() }.show()
            return
        }
        AlertDialog.Builder(this).setTitle("Torrent subtitles")
            .setItems(candidates.map { "${it.language ?: "Unknown language"} · ${it.name}" }.toTypedArray()) { _, index ->
                model.loadTorrentSubtitle(candidates[index])
            }.setNegativeButton("Cancel", null).setOnDismissListener { finishPlayerMenu() }.show()
    }

    private fun onlineSubtitleDialog(title: String): AlertDialog.Builder {
        val heading = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(32, 24, 32, 8)
            addView(android.widget.TextView(this@MainActivity).apply {
                text = title
                textSize = 20f
                setTextColor(android.graphics.Color.WHITE)
            })
            addView(android.widget.Button(this@MainActivity).apply {
                text = "Subtitles provided by OpenSubtitles.com"
                textSize = 14f
                setOnClickListener {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.opensubtitles.com")))
                    } catch (_: ActivityNotFoundException) {
                        AlertDialog.Builder(this@MainActivity).setMessage("Visit https://www.opensubtitles.com")
                            .setPositiveButton("OK", null).show()
                    }
                }
            })
        }
        return AlertDialog.Builder(this).setCustomTitle(heading)
    }

    private fun showOnlineSubtitles() {
        val player = model.screen.value.player ?: return
        subtitleSearch?.cancel()
        val progress = onlineSubtitleDialog("Find subtitles")
            .setMessage("Searching the selected language for this video/release…")
            .setNegativeButton("Cancel") { _, _ -> subtitleSearch?.cancel() }
            .setOnCancelListener { subtitleSearch?.cancel() }.create()
        progress.setOnDismissListener { finishPlayerMenu() }
        progress.show()
        subtitleSearch = lifecycleScope.launch {
            try {
                val results = model.findOnlineSubtitles()
                if (model.screen.value.player !== player || isFinishing || isDestroyed) return@launch
                progress.dismiss()
                if (results.isEmpty()) {
                    onlineSubtitleDialog("No matches")
                        .setMessage("No matching subtitles in the selected language. No other language was substituted.")
                        .setPositiveButton("OK", null).setOnDismissListener { finishPlayerMenu() }.show()
                } else {
                    onlineSubtitleDialog("Choose subtitles · ${results.size} matches")
                        .setItems(results.map {
                            "${if (it.hashMatch) "Exact video hash · " else ""}${it.release}\n${it.filename}${if (it.hearingImpaired) " · SDH" else ""}${it.fps?.let { fps -> " · $fps fps" } ?: ""}"
                        }.toTypedArray()) { _, index ->
                            if (model.screen.value.player === player) model.loadOnlineSubtitle(results[index])
                        }.setNegativeButton("Cancel", null).setOnDismissListener { finishPlayerMenu() }.show()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (model.screen.value.player === player && !isFinishing && !isDestroyed) {
                    onlineSubtitleDialog("Subtitle search")
                        .setMessage(failure.message ?: "Search failed. Try again later.")
                        .setPositiveButton("OK", null).setOnDismissListener { finishPlayerMenu() }.show()
                }
            } finally {
                progress.dismiss()
            }
        }
    }

    private fun showSubtitleTiming() {
        var delayMs = model.screen.value.subtitleDelayMs
        val content = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(32, 16, 32, 8)
        }
        val current = android.widget.TextView(this).apply {
            textSize = 18f
            setTextColor(android.graphics.Color.WHITE)
        }
        fun refresh() {
            current.text = "Current: ${subtitleDelayLabel(delayMs)}\nPositive = later · Negative = earlier\nApply may briefly buffer. This fixes a constant offset, not different cuts or drift."
        }
        content.addView(current)
        fun adjustment(label: String, change: Long?) {
            content.addView(android.widget.Button(this).apply {
                text = label
                setOnClickListener {
                    delayMs = if (change == null) 0 else (delayMs + change).coerceIn(-30_000, 30_000)
                    refresh()
                }
            })
        }
        adjustment("Earlier −1.0 s", -1_000)
        adjustment("Earlier −0.1 s", -100)
        adjustment("Later +0.1 s", 100)
        adjustment("Later +1.0 s", 1_000)
        adjustment("Reset to 0.0 s", null)
        refresh()
        AlertDialog.Builder(this).setTitle("Subtitle timing").setView(content)
            .setPositiveButton("Apply") { _, _ -> model.setSubtitleDelay(delayMs) }
            .setNegativeButton("Cancel", null)
            .setOnDismissListener { finishPlayerMenu() }.show()
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
                                leavePlayerActions(view)
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
        subtitleSearch?.cancel()
        subtitleSearch = null
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
    val subtitleDelayMs: Long = 0,
    val subtitleMessage: String? = null,
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
    private val configurationReady = CompletableDeferred<Unit>()
    private val subtitles = PlaybackSubtitles(application, engine, viewModelScope,
        onDelay = { value -> mutableScreen.update { it.copy(subtitleDelayMs = value) } },
        onMessage = { value -> mutableScreen.update { it.copy(subtitleMessage = value) } },
        onError = { value -> mutableScreen.update { it.copy(error = value) } },
    )
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

    fun loadSubtitle(uri: Uri) = subtitles.loadUri(uri)
    fun setSubtitleDelay(delayMs: Long) = subtitles.setDelay(delayMs)
    fun selectSubtitle(group: Tracks.Group?, index: Int = 0) = subtitles.selectTrack(group, index)
    fun torrentSubtitles(): List<TorrentSubtitleCandidate> = subtitles.candidates()
    fun loadTorrentSubtitle(candidate: TorrentSubtitleCandidate) = subtitles.loadTorrent(candidate)
    suspend fun findOnlineSubtitles(): List<ProviderSubtitle> = subtitles.searchOnline()
    fun loadOnlineSubtitle(candidate: ProviderSubtitle) = subtitles.loadOnline(candidate)

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
            } finally {
                configurationReady.complete(Unit)
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
            configurationReady.await()
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
                // A loader may be joining native disk workers during cache eviction.
                .setReleaseTimeoutMs(5_000)
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
                    subtitles.onTracksChanged(tracks)
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
                    stop("Playback stopped after an error.", playbackErrorMessage(error))
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
            subtitles.bind(player, playback, selection, screen.value.configuration, source)
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
        subtitles.unbind()
        heartbeat?.cancel()
        heartbeat = null
        val player = screen.value.player
        mutableScreen.update { it.copy(player = null, subtitleDelayMs = 0) }
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
        subtitles.close()
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
        VideoSurface(player, screen.title, torrent, screen.error, screen.subtitleMessage, onPlayerViewChanged)
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
                if (screen.busy) {
                    Text(torrent.message, color = Color(0xFFB8D8FF), fontSize = 16.sp)
                    Text(transferLabel(torrent), color = Color(0xFFD3D9E5), fontSize = 15.sp)
                    Text(loadLabel(torrent), color = Color(0xFFD3D9E5), fontSize = 15.sp)
                }
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
    subtitleMessage: String?,
    onPlayerViewChanged: (PlayerView?) -> Unit,
) {
    var controlsVisible by remember(player) { mutableStateOf(true) }
    var buffering by remember(player) { mutableStateOf(player.playbackState == Player.STATE_BUFFERING) }
    var bufferedMs by remember(player) { mutableStateOf(player.totalBufferedDuration) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                buffering = playbackState == Player.STATE_BUFFERING
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    LaunchedEffect(player) {
        while (isActive) {
            bufferedMs = player.totalBufferedDuration
            delay(1_000)
        }
    }
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
        if (controlsVisible || buffering || torrent.error != null || subtitleError != null || subtitleMessage != null) {
            Column(
                modifier = Modifier.align(Alignment.TopStart).fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.65f)).padding(horizontal = 30.dp, vertical = 10.dp),
            ) {
                Text(title, color = Color.White, fontSize = 17.sp, maxLines = 1)
                Text("${if (buffering) "Buffering · " else ""}${transferLabel(torrent)} · Buffered ${bufferedMs / 1000} s", color = Color(0xFFD3D9E5), fontSize = 15.sp)
                if (buffering) Text(loadLabel(torrent), color = Color(0xFFB8D8FF), fontSize = 15.sp)
                if (controlsVisible) Text("Left/right: seek · OK: pause/play · Up: subtitles/timing · Down: CC/settings · Back: stop", color = Color(0xFFD3D9E5), fontSize = 12.sp)
                Text("Cache ${formatBytes(torrent.cacheBytes)} / ${formatBytes(torrent.cacheLimitBytes)}", color = Color(0xFFBCC5D7), fontSize = 12.sp)
                subtitleMessage?.let { Text(it, color = Color(0xFFB8D8FF), fontSize = 13.sp) }
                (subtitleError ?: torrent.error)?.let { Text(it, color = Color(0xFFFFA6A6), fontSize = 13.sp) }
            }
        }
    }
}

private fun transferLabel(state: TorrentState): String {
    val progress = if (state.totalBytes > 0) {
        " · Downloaded ${formatBytes(state.downloadedBytes)} / ${formatBytes(state.totalBytes)} (${(state.downloadedBytes * 100 / state.totalBytes).coerceIn(0, 100)}%)"
    } else ""
    return "Download ${formatBytes(state.downloadBytesPerSecond)}/s · ${state.peers} peers$progress"
}

private fun loadLabel(state: TorrentState): String = when (state.requiredDataStatus) {
    dev.lumastream.torrent.RequiredDataStatus.DOWNLOADING ->
        "Loading requested piece · ${state.requiredBlocksLoaded}/${state.requiredBlockCount} blocks · Waiting ${state.requiredWaitMillis / 1000} s"
    dev.lumastream.torrent.RequiredDataStatus.VERIFYING -> "Verifying requested piece · Waiting ${state.requiredWaitMillis / 1000} s"
    dev.lumastream.torrent.RequiredDataStatus.WRITING -> "Reading requested piece from storage · Waiting ${state.requiredWaitMillis / 1000} s"
    dev.lumastream.torrent.RequiredDataStatus.IDLE -> "Preparing playback buffer"
}

private fun subtitleDelayLabel(delayMs: Long): String = String.format(Locale.US, "%+.1f s", delayMs / 1000.0)

private fun playbackErrorMessage(error: PlaybackException): String {
    val details = generateSequence<Throwable>(error) { it.cause }
        .take(8).mapNotNull { it.message?.trim()?.takeIf(String::isNotEmpty) }.distinct().toList()
    return "Playback failed: ${error.errorCodeName}. ${details.joinToString(" · ")}"
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> String.format(Locale.US, "%.2f GiB", bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MiB", bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> String.format(Locale.US, "%.0f KiB", bytes / 1024.0)
    else -> "$bytes B"
}
