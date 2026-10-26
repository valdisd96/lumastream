package dev.lumastream

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import dev.lumastream.history.removeHistoryEntry
import dev.lumastream.history.setHistoryCompleted
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import dev.lumastream.subtitle.ProviderSubtitle
import dev.lumastream.torrent.TorrentSubtitleCandidate
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

private const val PLAYER_TAG = "LumaPlayer"

@androidx.annotation.OptIn(UnstableApi::class)
class MainActivity : ComponentActivity() {
    private val model: PlaybackViewModel by viewModels()
    private val isTv: Boolean
        get() = (resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION
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
                view.findViewById<View>(androidx.media3.ui.R.id.exo_next).apply {
                    isEnabled = model.screen.value.episodeSelection != null
                    alpha = if (isEnabled) 1f else 0.3f
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
        activePlayerView?.let { if (isTv) leavePlayerActions(it) else it.showController() }
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
        val onlineEnabled = model.screen.value.configuration.isOnlineSubtitlesConfigured
        val labels = listOf("Off", "Choose external SRT / VTT…", "Load HTTPS subtitle URL…",
            "Subtitle timing (${subtitleDelayLabel(model.screen.value.subtitleDelayMs)})…",
            "Subtitles in this torrent (${model.torrentSubtitles().size})…") +
            (if (onlineEnabled) listOf("Find subtitles online (${model.screen.value.configuration.subtitleLanguage})…") else emptyList()) +
            tracks.map { (group, index) ->
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
                        AlertDialog.Builder(this).setMessage("This device has no document picker. Install a document picker to load external subtitles.")
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
                else -> if (onlineEnabled && choice == 5) {
                    showOnlineSubtitles()
                } else {
                    val (group, index) = tracks[choice - if (onlineEnabled) 6 else 5]
                    model.selectSubtitle(group, index)
                }
            }
        }.setNegativeButton("Cancel", null).setOnDismissListener { finishPlayerMenu() }.show()
    }

    private fun showTorrentSubtitles() {
        val candidates = model.torrentSubtitles()
        if (candidates.isEmpty()) {
            AlertDialog.Builder(this).setTitle("Torrent subtitles")
                .setMessage("No matching SRT / WebVTT files were found for this video. You can import an external subtitle file or HTTPS URL.")
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
        if (!model.screen.value.configuration.isOnlineSubtitlesConfigured) return
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
        val scroll = android.widget.ScrollView(this).apply { addView(content) }
        AlertDialog.Builder(this).setTitle("Subtitle timing").setView(scroll)
            .setPositiveButton("Apply") { _, _ -> model.setSubtitleDelay(delayMs) }
            .setNegativeButton("Cancel", null)
            .setOnDismissListener { finishPlayerMenu() }.show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (isTv) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
                navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            )
        }
        val originalCallback = window.callback
        window.callback = object : android.view.Window.Callback by originalCallback {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean =
                dispatchPlaybackKeyEvent(event) || originalCallback.dispatchKeyEvent(event)
        }
        if (savedInstanceState == null) model.acceptIntent(intent)
        setContent {
            AdaptiveContent(isTv) {
                PlaybackScreen(model, onExit = { finishAndRemoveTask() }, onPlayerViewChanged = { view ->
                    if (activePlayerView !== view) {
                        activePlayerView?.player?.removeListener(playerActionsListener)
                        navigatingPlayerActions = false
                        view?.player?.addListener(playerActionsListener)
                    }
                    activePlayerView = view
                    view?.findViewById<View>(androidx.media3.ui.R.id.exo_subtitle)
                        ?.setOnClickListener { showSubtitles() }
                    view?.findViewById<View>(androidx.media3.ui.R.id.exo_next)?.apply {
                        contentDescription = "Next episode · Choose source"
                        isEnabled = model.screen.value.episodeSelection != null
                        setOnClickListener { model.nextEpisode() }
                    }
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
        if (model.screen.value.automaticStatus != null) return false
        val player = view?.player
        if (player != null && view.hasWindowFocus()) {
            if (navigatingPlayerActions) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (event.action == KeyEvent.ACTION_DOWN) {
                            val next = androidx.media3.ui.R.id.exo_next
                            val settings = androidx.media3.ui.R.id.exo_settings
                            val subtitles = androidx.media3.ui.R.id.exo_subtitle
                            val target = if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                                if (focusedPlayerAction == next) settings else subtitles
                            } else {
                                if (focusedPlayerAction == subtitles || model.screen.value.episodeSelection == null)
                                    settings else next
                            }
                            focusPlayerAction(view, target)
                        }
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                            when (focusedPlayerAction) {
                                androidx.media3.ui.R.id.exo_subtitle -> showSubtitles()
                                androidx.media3.ui.R.id.exo_next -> model.nextEpisode()
                                else -> {
                                    leavePlayerActions(view)
                                    view.findViewById<View>(androidx.media3.ui.R.id.exo_settings).performClick()
                                }
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
        if (!choosingSubtitle && !isChangingConfigurations) {
            activePlayerView = null
            model.stop("Stopped while LumaStream is in the background. Open a source to resume.")
            if (model.screen.value.page == AppPage.PLAYER) model.navigate(AppPage.CATALOG)
        }
        super.onStop()
    }
}

enum class AppPage { CATALOG, OPEN_TORRENT, ARCHIVE, SETTINGS, PLAYER }

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
    val resumeAutomaticSources: List<CatalogSelection> = emptyList(),
    val automaticStatus: String? = null,
    val recoveryEntry: WatchEntry? = null,
    val recoveryRequested: Boolean = false,
    val episodeSelection: CatalogSelection? = null,
    val nextEpisodeRequest: CatalogSelection? = null,
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
    private class StartupAttempt(val budget: AutomaticStartupBudget) {
        val firstFrame = CompletableDeferred<Unit>()
        var committed = false
    }
    private var startupAttempt: StartupAttempt? = null

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

    fun removeHistory(entry: WatchEntry) {
        editHistory { removeHistoryEntry(it, entry.key) }
    }

    fun setHistoryCompleted(entry: WatchEntry, completed: Boolean) {
        editHistory { setHistoryCompleted(it, entry.key, completed) }
    }

    private fun editHistory(transform: (List<WatchEntry>) -> List<WatchEntry>) {
        // Catalog actions must not race initial loading or an active player's progress writes.
        if (!historyWritable || screen.value.page != AppPage.CATALOG || screen.value.player != null) return
        val entries = transform(screen.value.history)
        if (entries == screen.value.history) return
        mutableScreen.update { it.copy(history = entries) }
        historyWrites.trySend(entries)
    }

    fun requestCatalog(selection: CatalogSelection) = requestSources(listOf(selection), automatic = false)

    fun requestAutomaticSources(selections: List<CatalogSelection>) {
        if (selections.isNotEmpty()) requestSources(selections.take(4), automatic = true)
    }

    private fun requestSources(selections: List<CatalogSelection>, automatic: Boolean) {
        cancelAutomaticPreparation()
        dismissResume()
        releasePlayer()
        runCommand {
            historyReady.await()
            val selection = selections.first()
            val key = WatchEntry(selection, 0, 0, 0, false).key
            val previous = screen.value.recoveryEntry?.takeIf { it.key == key }
                ?: screen.value.history.firstOrNull { it.key == key }
            if (previous != null) {
                engine.stop()
                mutableScreen.update {
                    it.copy(page = AppPage.CATALOG, resumeSelection = selection, resumeEntry = previous,
                        resumeAutomaticSources = if (automatic) selections else emptyList())
                }
            } else if (automatic) {
                startAutomaticSources(selections, 0)
            } else {
                startCatalog(selection, 0)
            }
        }
    }

    fun dismissResume() {
        mutableScreen.update { it.copy(resumeSelection = null, resumeEntry = null, resumeAutomaticSources = emptyList()) }
    }

    fun confirmResume(startOver: Boolean) {
        val selection = screen.value.resumeSelection ?: return
        val entry = screen.value.resumeEntry ?: return
        val automatic = screen.value.resumeAutomaticSources
        dismissResume()
        val position = if (startOver || entry.completed) 0 else entry.positionMs
        if (automatic.isNotEmpty()) {
            releasePlayer()
            runCommand { startAutomaticSources(automatic, position) }
        } else openCatalog(selection, position)
    }

    fun resume(entry: WatchEntry, startOver: Boolean) {
        openCatalog(entry.selection, if (startOver || entry.completed) 0 else entry.positionMs)
    }

    fun recoverSource() {
        if (screen.value.busy || screen.value.recoveryEntry == null) return
        mutableScreen.update { it.copy(page = AppPage.CATALOG, recoveryRequested = true) }
    }

    fun consumeRecoveryRequest() {
        mutableScreen.update { it.copy(recoveryRequested = false) }
    }

    fun nextEpisode() {
        val selection = screen.value.episodeSelection ?: return
        if (screen.value.busy) return
        stop("Finding the next episode…")
        mutableScreen.update { it.copy(page = AppPage.CATALOG, nextEpisodeRequest = selection) }
    }

    fun consumeNextEpisodeRequest() {
        mutableScreen.update { it.copy(nextEpisodeRequest = null) }
    }

    private fun finishPlayback() {
        val selection = screen.value.episodeSelection
        stop(if (selection == null) "Playback finished. Open a source to play again."
            else "Episode finished. Choose Next episode to find sources; playback will not start automatically.")
        mutableScreen.update { it.copy(episodeSelection = selection) }
    }

    private fun captureRecoveryPosition() {
        val entry = screen.value.recoveryEntry ?: return
        val player = screen.value.player ?: return
        if (renderedVideo) mutableScreen.update {
            it.copy(recoveryEntry = entry.copy(positionMs = player.currentPosition.coerceAtLeast(0),
                durationMs = player.duration.coerceAtLeast(0), completed = false))
        }
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
                    mutableScreen.update { it.copy(historyError = "Unable to save watch history. Your latest changes may not survive closing the app.") }
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
        cancelAutomaticPreparation()
        releasePlayer()
        dismissResume()
        runCommand { startCatalog(selection, positionMs) }
    }

    private suspend fun startCatalog(selection: CatalogSelection, positionMs: Long) {
        navigate(AppPage.PLAYER)
        mutableScreen.update {
            it.copy(recoveryEntry = WatchEntry(selection, positionMs, 0, System.currentTimeMillis(), false),
                recoveryRequested = false, busy = true, error = null, notice = "Opening selected source…", title = "")
        }
        historyReady.await()
        openTorrent(catalogMagnet(selection.source))
        val selected = selectCatalogFile(selection, engine.state.value.files)
        val resolved = selection.copy(source = selection.source.copy(fileIndex = selected.index, filename = selected.name))
        mutableScreen.update { it.copy(recoveryEntry = it.recoveryEntry?.copy(selection = resolved)) }
        startVideo(selected.index, resolved, positionMs)
    }

    private suspend fun startAutomaticSources(selections: List<CatalogSelection>, positionMs: Long) {
        navigate(AppPage.PLAYER)
        mutableScreen.update {
            it.copy(busy = true, error = null, notice = null,
                recoveryEntry = WatchEntry(selections.first(), positionMs, 0, System.currentTimeMillis(), false),
                recoveryRequested = false, automaticStatus = "Preparing automatic selection…")
        }
        val failure = runAutomaticStartup(
            candidates = selections,
            now = SystemClock::elapsedRealtime,
            onAttempt = { index, count, budget ->
                startupAttempt = StartupAttempt(budget)
                mutableScreen.update {
                    it.copy(automaticStatus = "Trying source $index of $count · Fetching metadata…", error = null)
                }
                Log.i(PLAYER_TAG, "auto_start attempt=$index count=$count elapsedMs=${budget.elapsed()}")
            },
            prepare = { selection ->
                val attempt = checkNotNull(startupAttempt)
                coroutineScope {
                    val engineFailure = launch {
                        engine.state.first { it.error != null }
                        throw AutomaticStartupFailure("The torrent engine could not fetch metadata. Check connectivity and free storage, or choose another source.")
                    }
                    try {
                        openTorrent(catalogMagnet(selection.source))
                        val file = selectCatalogFile(selection, engine.state.value.files)
                        val resolved = selection.copy(source = selection.source.copy(fileIndex = file.index, filename = file.name))
                        Log.i(PLAYER_TAG, "auto_metadata elapsedMs=${attempt.budget.elapsed()} attemptMs=${attempt.budget.attemptElapsed()}")
                        resolved
                    } finally {
                        engineFailure.cancel()
                    }
                }
            },
            awaitFirstFrame = { selection ->
                val attempt = checkNotNull(startupAttempt)
                mutableScreen.update {
                    it.copy(automaticStatus = it.automaticStatus?.substringBefore(" · ") + " · Waiting for first video frame…",
                        recoveryEntry = WatchEntry(selection, positionMs, 0, System.currentTimeMillis(), false))
                }
                coroutineScope {
                    val engineFailure = launch {
                        engine.state.first { it.error != null }
                        if (startupAttempt === attempt && !attempt.committed) {
                            attempt.firstFrame.completeExceptionally(AutomaticStartupFailure(
                                "The torrent engine could not read this source. Check connectivity and free storage, or choose another source."))
                        }
                    }
                    try {
                        startVideo(checkNotNull(selection.source.fileIndex), selection, positionMs, attempt)
                        attempt.firstFrame.await()
                    } finally {
                        engineFailure.cancel()
                    }
                }
            },
            committed = { startupAttempt?.committed == true },
            cleanup = {
                startupAttempt = null
                releasePlayer()
                engine.stop()
            },
            onOutcome = { outcome, budget ->
                Log.i(PLAYER_TAG, "auto_outcome result=$outcome elapsedMs=${budget.elapsed()} attemptMs=${budget.attemptElapsed()}")
            },
        )
        startupAttempt = null
        mutableScreen.update {
            it.copy(automaticStatus = null, error = failure, notice = if (failure == null) null else "Automatic selection stopped. Choose manually to try other sources.")
        }
    }

    fun chooseAutomaticSourceManually() {
        val recovery = screen.value.recoveryEntry
        stop()
        mutableScreen.update {
            it.copy(page = AppPage.CATALOG, recoveryEntry = recovery, recoveryRequested = recovery != null)
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
        cancelAutomaticPreparation()
        navigate(AppPage.PLAYER)
        mutableScreen.update { it.copy(recoveryEntry = null, recoveryRequested = false) }
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
            openTorrent(uri)
            mutableScreen.update { it.copy(notice = "Choose a video file below to start playback.") }
        }
    }

    private suspend fun openTorrent(uri: Uri) {
        configurationReady.await()
        engine.open(uri, screen.value.configuration.torrentCacheGiB.toLong() * 1024 * 1024 * 1024)
    }

    fun select(index: Int) {
        cancelAutomaticPreparation()
        releasePlayer()
        runCommand { startVideo(index) }
    }

    private suspend fun startVideo(index: Int, selection: CatalogSelection? = null, positionMs: Long = 0,
        attempt: StartupAttempt? = null) {
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
            mutableScreen.update {
                it.copy(player = player, title = playback.title, notice = null,
                    episodeSelection = selection?.takeIf { value -> value.episode != null })
            }
            player.addListener(object : Player.Listener {
                override fun onEvents(playerEvents: Player, events: Player.Events) {
                    if (screen.value.player !== player) return
                    engine.setPausePrefetch(
                        screen.value.configuration.pausePrefetchEnabled &&
                            !player.playWhenReady && player.playerError == null &&
                            (player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_BUFFERING),
                    )
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (screen.value.player !== player) return
                    val name = when (playbackState) {
                        Player.STATE_IDLE -> "IDLE"
                        Player.STATE_BUFFERING -> "BUFFERING"
                        Player.STATE_READY -> "READY"
                        Player.STATE_ENDED -> "ENDED"
                        else -> playbackState.toString()
                    }
                    Log.i(PLAYER_TAG, "state=$name positionMs=${player.currentPosition}")
                    if (playbackState == Player.STATE_ENDED) {
                        if (attempt != null && !attempt.committed) {
                            attempt.firstFrame.completeExceptionally(AutomaticStartupFailure(
                                "This source ended before displaying video. Choose another source."))
                        } else finishPlayback()
                    }
                }

                override fun onRenderedFirstFrame() {
                    if (screen.value.player !== player || renderedVideo) return
                    if (attempt != null) {
                        if (startupAttempt !== attempt || attempt.firstFrame.isCompleted || attempt.budget.remaining() == 0L) return
                        attempt.committed = true
                        attempt.firstFrame.complete(Unit)
                        mutableScreen.update { it.copy(automaticStatus = null, busy = false) }
                        Log.i(PLAYER_TAG, "auto_first_frame elapsedMs=${attempt.budget.elapsed()} attemptMs=${attempt.budget.attemptElapsed()}")
                    }
                    renderedVideo = true
                    saveProgress()
                    val transfer = engine.state.value
                    Log.i(PLAYER_TAG, "first_frame title=${playback.title} positionMs=${player.currentPosition} downloadedBytes=${transfer.downloadedBytes} totalBytes=${transfer.totalBytes}")
                }

                override fun onTracksChanged(tracks: Tracks) {
                    if (screen.value.player !== player) return
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
                    if (screen.value.player !== player) return
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (screen.value.player !== player) return
                    Log.i(PLAYER_TAG, "isPlaying=$isPlaying positionMs=${player.currentPosition}")
                    if (!isPlaying) saveProgress()
                }

                override fun onPlayerError(error: PlaybackException) {
                    if (screen.value.player !== player) return
                    if (attempt != null && !attempt.committed) {
                        if (startupAttempt === attempt) {
                            Log.i(PLAYER_TAG, "auto_player_error code=${error.errorCodeName} elapsedMs=${attempt.budget.elapsed()}")
                            attempt.firstFrame.completeExceptionally(AutomaticStartupFailure(
                                "The device could not play this source (${error.errorCodeName}). Try H.264/AVC video with AAC audio."))
                        }
                        return
                    }
                    Log.e(PLAYER_TAG, "playback_error code=${error.errorCodeName}", error)
                    captureRecoveryPosition()
                    stop("Playback stopped after an error.", playbackErrorMessage(error))
                }

                override fun onPositionDiscontinuity(
                    oldPosition: Player.PositionInfo,
                    newPosition: Player.PositionInfo,
                    reason: Int,
                ) {
                    if (screen.value.player !== player) return
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

    private fun cancelAutomaticPreparation() {
        startupAttempt = null
        mutableScreen.update { it.copy(automaticStatus = null) }
    }

    fun stop(notice: String = "Stopped. Open a source to play again.", error: String? = null) {
        startupAttempt = null
        dismissResume()
        mutableScreen.update { it.copy(automaticStatus = null) }
        releasePlayer()
        if (error == null) mutableScreen.update { it.copy(recoveryEntry = null, recoveryRequested = false) }
        runCommand {
            mutableScreen.update { it.copy(busy = true, notice = notice, error = error, title = "") }
            engine.stop()
        }
    }

    private fun runCommand(action: suspend () -> Unit) {
        val previous = command
        previous?.cancel()
        val next = viewModelScope.launch(start = CoroutineStart.LAZY) {
            // Even a superseded waiter must drain its predecessor before its successor can open the engine.
            withContext(NonCancellable) { previous?.join() }
            currentCoroutineContext().ensureActive()
            try {
                action()
            } catch (cancelled: CancellationException) {
                Log.i(PLAYER_TAG, "startup_cancelled")
                throw cancelled
            } catch (failure: Exception) {
                cancelAutomaticPreparation()
                captureRecoveryPosition()
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
        command = next
        next.start()
    }

    private fun releasePlayer() {
        engine.setPausePrefetch(false)
        saveProgress()
        activeSelection = null
        renderedVideo = false
        subtitles.unbind()
        heartbeat?.cancel()
        heartbeat = null
        val player = screen.value.player
        mutableScreen.update {
            it.copy(player = null, subtitleDelayMs = 0, episodeSelection = null, nextEpisodeRequest = null)
        }
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
private fun AdaptiveContent(isTv: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme()) {
        androidx.tv.material3.MaterialTheme {
            BoxWithConstraints(
                Modifier.fillMaxSize().background(Color(0xFF10131B))
                    .then(if (isTv) Modifier else Modifier.safeDrawingPadding().imePadding()),
            ) {
                val layout = remember(isTv, maxWidth, maxHeight) { AppLayout(isTv, maxWidth, maxHeight) }
                CompositionLocalProvider(LocalAppLayout provides layout, content = content)
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
@androidx.annotation.OptIn(UnstableApi::class)
private fun PlaybackScreen(model: PlaybackViewModel, onExit: () -> Unit, onPlayerViewChanged: (PlayerView?) -> Unit) {
    val screen by model.screen.collectAsState()
    val torrent by model.torrent.collectAsState()
    val layout = LocalAppLayout.current
    when (screen.page) {
        AppPage.CATALOG -> {
            MovieCatalogScreen(
                configuration = screen.configuration,
                onConfigure = { model.navigate(AppPage.SETTINGS) },
                onArchive = { model.navigate(AppPage.ARCHIVE) },
                onOpenTorrent = { model.navigate(AppPage.OPEN_TORRENT) },
                onBack = onExit,
                onOpenSource = model::requestCatalog,
                onAutoSources = model::requestAutomaticSources,
                history = screen.history,
                historyError = screen.historyError,
                onResume = model::resume,
                onRemoveHistory = model::removeHistory,
                onSetHistoryCompleted = model::setHistoryCompleted,
                recoverySelection = screen.recoveryEntry?.selection?.takeIf { screen.recoveryRequested },
                onRecoveryConsumed = model::consumeRecoveryRequest,
                nextEpisodeSelection = screen.nextEpisodeRequest,
                onNextEpisodeConsumed = model::consumeNextEpisodeRequest,
            )
            val entry = screen.resumeEntry
            val selection = screen.resumeSelection
            if (entry != null && selection != null) {
                androidx.compose.ui.window.Dialog(onDismissRequest = model::dismissResume) {
                    Column(
                        Modifier.heightIn(max = layout.height).verticalScroll(rememberScrollState())
                            .background(Color(0xFF192535)).padding(if (layout.compact) 16.dp else 24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(selection.details.title.title, color = Color.White, fontSize = 24.sp)
                        val changedSource = !entry.selection.source.infoHash.equals(selection.source.infoHash, ignoreCase = true) ||
                            entry.selection.source.fileIndex != selection.source.fileIndex
                        val automatic = screen.resumeAutomaticSources.isNotEmpty()
                        Text(
                            if (automatic) "Automatic candidates may have different cuts or timing. Transfer the saved position to every attempted source explicitly, or start each from the beginning."
                            else if (changedSource) "Different source selected. Its cut or timing may differ. Transfer the saved position explicitly, or start from the beginning."
                            else if (entry.completed) "You finished this video."
                            else "Continue from ${entry.positionMs / 60_000}:${(entry.positionMs / 1000 % 60).toString().padStart(2, '0')}?",
                            color = Color.White,
                        )
                        val initialFocus = remember { FocusRequester() }
                        if (!entry.completed) RemoteButton(
                            if (changedSource || automatic) "Transfer saved position" else "Continue",
                            Modifier.focusRequester(initialFocus),
                        ) { model.confirmResume(false) }
                        RemoteButton("Start from beginning",
                            if (entry.completed) Modifier.focusRequester(initialFocus) else Modifier,
                        ) { model.confirmResume(true) }
                        RemoteButton("Cancel") { model.dismissResume() }
                        LaunchedEffect(entry.key) {
                            if (!layout.isTv) return@LaunchedEffect
                            withFrameNanos { }
                            initialFocus.requestFocus()
                        }
                    }
                }
            }
            return
        }
        AppPage.OPEN_TORRENT -> {
            OpenTorrentScreen(onOpen = model::open, onBack = { model.navigate(AppPage.CATALOG) })
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
        if (screen.automaticStatus != null) model.chooseAutomaticSourceManually()
        else {
            model.stop()
            model.navigate(AppPage.CATALOG)
        }
    }
    BackHandler(onBack = ::returnToCatalog)
    val player = screen.player
    if (player != null) {
        Box(Modifier.fillMaxSize()) {
            VideoSurface(player, screen.title, torrent, screen.error, screen.subtitleMessage,
                screen.episodeSelection != null, screen.automaticStatus != null, onPlayerViewChanged, ::returnToCatalog)
            screen.automaticStatus?.let { status ->
                AutomaticStartupOverlay(status, torrent, model::chooseAutomaticSourceManually)
            }
        }
        return
    }
    screen.automaticStatus?.let { status ->
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AutomaticStartupOverlay(status, torrent, model::chooseAutomaticSourceManually)
        }
        return
    }
    val backFocus = remember { FocusRequester() }
    val firstFileFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    LaunchedEffect(torrent.files, screen.busy) {
        if (!layout.isTv) return@LaunchedEffect
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
        modifier = Modifier.fillMaxSize().background(Color(0xFF10131B))
            .padding(horizontal = layout.horizontalPadding, vertical = layout.verticalPadding),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                maxItemsInEachRow = if (layout.compact) 1 else 2,
            ) {
                Text("LumaStream", color = Color.White, fontSize = 30.sp)
                val recovery = screen.error != null && screen.recoveryEntry != null
                val nextEpisode = screen.episodeSelection != null
                RemoteButton(when {
                    recovery -> "Choose another source"
                    nextEpisode -> "Next episode"
                    else -> "Back to Home"
                }, modifier = Modifier.focusRequester(backFocus),
                    enabled = !(recovery || nextEpisode) || !screen.busy,
                    onClick = when {
                        recovery -> model::recoverSource
                        nextEpisode -> model::nextEpisode
                        else -> ::returnToCatalog
                    })
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (screen.busy) CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                    Text(if (screen.busy) "Preparing your video…" else "Playback", color = Color.White, fontSize = 22.sp)
                }
                screen.notice?.let { Text(it, color = Color(0xFFB8D8FF), fontSize = 16.sp) }
                screen.episodeSelection?.let { selection ->
                    Text("${selection.details.title.title} · S${selection.episode?.season} E${selection.episode?.number}",
                        color = Color.White, fontSize = 18.sp)
                    Text("Back returns to the catalog.", color = Color(0xFFBCC5D7), fontSize = 14.sp)
                }
                if (screen.busy) {
                    Text(torrent.message, color = Color(0xFFB8D8FF), fontSize = 16.sp)
                    Text(transferLabel(torrent), color = Color(0xFFD3D9E5), fontSize = 15.sp)
                    Text(loadLabel(torrent), color = Color(0xFFD3D9E5), fontSize = 15.sp)
                }
                (screen.error ?: torrent.error)?.let {
                    Text(it, color = Color(0xFFFFA6A6), fontSize = 16.sp)
                    Text(if (screen.recoveryEntry != null)
                        "Choose another source for the same title or episode. You can transfer the saved position or start over."
                        else "Return to Home and open another source.",
                        color = Color(0xFFBCC5D7), fontSize = 14.sp)
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
private fun AutomaticStartupOverlay(status: String, torrent: TorrentState, onManual: () -> Unit) {
    val focus = remember { FocusRequester() }
    val layout = LocalAppLayout.current
    Column(
        Modifier.fillMaxWidth().heightIn(max = layout.height).verticalScroll(rememberScrollState())
            .background(Color(0xEE192535)).padding(if (layout.compact) 16.dp else 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(status, color = Color.White, fontSize = 22.sp)
        Text("Automatic selection stops at the first video frame · Up to 4 sources / 120 seconds",
            color = Color(0xFFB8D8FF), fontSize = 16.sp)
        Text("${torrent.message}\n${transferLabel(torrent)}", color = Color.White, fontSize = 15.sp)
        RemoteButton("Choose manually / Cancel", Modifier.focusRequester(focus), onClick = onManual)
    }
    LaunchedEffect(Unit) {
        if (!layout.isTv) return@LaunchedEffect
        withFrameNanos { }
        focus.requestFocus()
    }
}

@Composable
private fun RemoteButton(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    AppButton(onClick = onClick, modifier = modifier, enabled = enabled) {
        Text(label, fontSize = 15.sp)
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
@androidx.annotation.OptIn(UnstableApi::class)
private fun VideoSurface(
    player: ExoPlayer,
    title: String,
    torrent: TorrentState,
    subtitleError: String?,
    subtitleMessage: String?,
    nextEpisodeAvailable: Boolean,
    automaticPreparing: Boolean,
    onPlayerViewChanged: (PlayerView?) -> Unit,
    onExit: () -> Unit,
) {
    val layout = LocalAppLayout.current
    var showTransferDetails by remember(player) { mutableStateOf(false) }
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
                    useController = !automaticPreparing
                    controllerAutoShow = !automaticPreparing
                    controllerShowTimeoutMs = 5_000
                    setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
                        controlsVisible = visibility == View.VISIBLE
                    })
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    setShowPreviousButton(false)
                    setShowNextButton(nextEpisodeAvailable)
                    setShowRewindButton(true)
                    setShowFastForwardButton(true)
                    setShowSubtitleButton(true)
                    keepScreenOn = true
                    isFocusable = true
                    descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
                    onPlayerViewChanged(this)
                    if (!automaticPreparing) post {
                        if (layout.isTv) requestFocus()
                        showController()
                    }
                }
            },
            update = { view ->
                view.player = player
                val wasPreparing = !view.useController
                view.useController = !automaticPreparing
                view.controllerAutoShow = !automaticPreparing
                if (wasPreparing && !automaticPreparing) view.post {
                    if (layout.isTv) view.requestFocus()
                    view.showController()
                }
                view.setShowNextButton(nextEpisodeAvailable)
                view.findViewById<View>(androidx.media3.ui.R.id.exo_next).apply {
                    isEnabled = nextEpisodeAvailable
                    alpha = if (nextEpisodeAvailable) 1f else 0.3f
                }
            },
            onRelease = {
                onPlayerViewChanged(null)
                it.player = null
                it.keepScreenOn = false
            },
        )
        if (controlsVisible || buffering || torrent.error != null || subtitleError != null || subtitleMessage != null) {
            Column(
                modifier = Modifier.align(Alignment.TopStart).fillMaxWidth()
                    .heightIn(max = if (layout.isTv) layout.height else layout.height * 0.45f)
                    .verticalScroll(rememberScrollState())
                    .background(Color.Black.copy(alpha = 0.65f))
                    .padding(horizontal = if (layout.isTv) 30.dp else 12.dp, vertical = 8.dp),
            ) {
                if (!layout.isTv && controlsVisible && !automaticPreparing) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppButton(onClick = onExit) { Text("Back") }
                        AppButton(onClick = { showTransferDetails = !showTransferDetails }) {
                            Text(if (showTransferDetails) "Hide info" else "Playback info")
                        }
                    }
                }
                Text(title, color = Color.White, fontSize = 17.sp, maxLines = 1)
                if (layout.isTv || buffering || showTransferDetails) {
                    Text("${if (buffering) "Buffering · " else ""}${transferLabel(torrent)} · Buffered ${bufferedMs / 1000} s", color = Color(0xFFD3D9E5), fontSize = 15.sp)
                }
                if (buffering) Text(loadLabel(torrent), color = Color(0xFFB8D8FF), fontSize = 15.sp)
                if (controlsVisible && layout.isTv) Text("Left/right: seek · OK: pause/play · Up: subtitles/timing · Down: CC/settings${if (nextEpisodeAvailable) "/next episode (Right → OK)" else ""} · Back: stop",
                    color = Color(0xFFD3D9E5), fontSize = 12.sp)
                if (layout.isTv || showTransferDetails) {
                    Text("Cache ${formatBytes(torrent.cacheBytes)} / ${formatBytes(torrent.cacheLimitBytes)}", color = Color(0xFFBCC5D7), fontSize = 12.sp)
                    torrent.pausePrefetchStatus?.let {
                        Text("$it · Ahead of reader ${formatBytes(torrent.prefetchedBytes)}", color = Color(0xFFB8D8FF), fontSize = 13.sp)
                    }
                }
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

@androidx.annotation.OptIn(UnstableApi::class)
private fun playbackErrorMessage(error: PlaybackException): String {
    val reason = when (error.errorCode) {
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
            "This device cannot decode the selected media format. Choose another source, preferably H.264/AVC with AAC audio."
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ->
            "This source exceeds the device decoder's capabilities. Choose a lower-resolution, non-3D source."
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ->
            "The device could not start its decoder. Try another source; if this also happens with previously playable videos, restart the device."
        PlaybackException.ERROR_CODE_DECODING_FAILED ->
            "The decoder failed while reading this video. The stream may be damaged or incompatible. Choose another source."
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ->
            "This media container is not supported. Choose another source."
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ->
            "The media container could not be read. It may be damaged. Choose another source."
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
            "The required media data could not be read. Check the source and connection; see the underlying cause below."
        PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED ->
            "The device could not play this audio track. Try a source with different audio, preferably AAC."
        else -> "Playback failed. Choose another source or retry."
    }
    val format = (error as? androidx.media3.exoplayer.ExoPlaybackException)?.rendererFormat
    val media = format?.let {
        buildList {
            it.sampleMimeType?.let(::add)
            it.codecs?.let(::add)
            if (it.width > 0 && it.height > 0) add("${it.width}×${it.height}")
        }.joinToString(" · ")
    }?.takeIf(String::isNotBlank)
    val details = generateSequence<Throwable>(error) { it.cause }
        .take(8).mapNotNull { it.message?.trim()?.takeIf(String::isNotEmpty) }.distinct().toList()
    return buildList {
        add(reason)
        media?.let { add("Media: $it") }
        add("${error.errorCodeName}: ${details.joinToString(" · ")}")
    }.joinToString("\n")
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> String.format(Locale.US, "%.2f GiB", bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MiB", bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> String.format(Locale.US, "%.0f KiB", bytes / 1024.0)
    else -> "$bytes B"
}
