package dev.lumastream

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import dev.lumastream.catalog.CatalogCollection
import dev.lumastream.catalog.CatalogGenre
import dev.lumastream.catalog.CatalogConfiguration
import dev.lumastream.catalog.CatalogDetails
import dev.lumastream.catalog.CatalogEpisode
import dev.lumastream.catalog.CatalogFilter
import dev.lumastream.catalog.CatalogSelection
import dev.lumastream.catalog.CatalogSource
import dev.lumastream.catalog.CatalogTitle
import dev.lumastream.catalog.MediaKind
import dev.lumastream.catalog.SourcePage
import dev.lumastream.catalog.MultiSourceProviders
import dev.lumastream.catalog.SourceProviderState
import dev.lumastream.catalog.SourceProviderStatus
import dev.lumastream.catalog.TmdbCatalog
import dev.lumastream.catalog.findNextEpisode
import dev.lumastream.catalog.rankAutomaticSources
import dev.lumastream.history.WatchEntry
import dev.lumastream.history.WatchLaterStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale

private enum class CatalogRoute { RESULTS, DETAILS, EPISODES, SOURCES, NEXT_EPISODE }
private enum class CatalogSection { HOME, SEARCH, DISCOVERY, WATCH_LATER, HISTORY }

private fun CatalogSection.label(): String = when (this) {
    CatalogSection.HOME -> "Home"
    CatalogSection.SEARCH -> "Search"
    CatalogSection.DISCOVERY -> "Discover"
    CatalogSection.WATCH_LATER -> "Watch later"
    CatalogSection.HISTORY -> "History"
}

private data class WatchLaterState(
    val titles: List<CatalogTitle> = emptyList(),
    val loaded: Boolean = false,
    val busy: Boolean = true,
    val error: String? = null,
)

private data class CatalogViewport(val index: Int = 0, val offset: Int = 0, val focused: String? = null)

private data class MovieCatalogState(
    val section: CatalogSection = CatalogSection.HOME,
    val browseKind: MediaKind = MediaKind.MOVIE,
    val collection: CatalogCollection = CatalogCollection.POPULAR,
    val genres: List<CatalogGenre> = emptyList(),
    val genreId: Int? = null,
    val query: String = "",
    val filter: CatalogFilter = CatalogFilter.ALL,
    val route: CatalogRoute = CatalogRoute.RESULTS,
    val titles: List<CatalogTitle> = emptyList(),
    val page: Int = 1,
    val hasMore: Boolean = false,
    val searched: Boolean = false,
    val selected: CatalogTitle? = null,
    val details: CatalogDetails? = null,
    val season: Int? = null,
    val episodes: List<CatalogEpisode> = emptyList(),
    val episodesLoaded: Boolean = false,
    val episode: CatalogEpisode? = null,
    val sources: SourcePage? = null,
    val providerStatuses: List<SourceProviderStatus> = emptyList(),
    val historySources: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val nextEpisodeAbsent: Boolean = false,
    val focusEpoch: Long = 0,
)

private class MovieCatalogViewModel(context: Context) : ViewModel() {
    private val mutableState = MutableStateFlow(MovieCatalogState())
    val state = mutableState.asStateFlow()
    private var configuration: CatalogConfiguration? = null
    private var catalog: TmdbCatalog? = null
    private var provider: MultiSourceProviders? = null
    private var active = false
    private var generation = 0L
    private var request: Job? = null
    private val watchLaterStore = WatchLaterStore(context.applicationContext)
    private val watchLaterLock = Mutex()
    private val mutableWatchLater = MutableStateFlow(WatchLaterState())
    val watchLater = mutableWatchLater.asStateFlow()
    val viewports = mutableMapOf<String, CatalogViewport>()

    fun saveViewport(key: String, viewport: CatalogViewport) {
        viewports.remove(key)
        viewports[key] = viewport
        if (viewports.size > 32) viewports.remove(viewports.keys.first())
    }

    init { loadWatchLater() }

    fun loadWatchLater() {
        if (watchLater.value.loaded) return
        viewModelScope.launch {
            watchLaterLock.withLock {
                if (watchLater.value.loaded) return@withLock
                mutableWatchLater.update { it.copy(busy = true, error = null) }
                try {
                    mutableWatchLater.value = WatchLaterState(watchLaterStore.load(), loaded = true, busy = false)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    mutableWatchLater.update {
                        it.copy(busy = false, error = "Watch later could not be read. Saved titles have not been changed. Retry loading before making changes.")
                    }
                }
            }
        }
    }

    fun toggleWatchLater(title: CatalogTitle) {
        if (!watchLater.value.loaded || watchLater.value.busy) return
        mutableWatchLater.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            watchLaterLock.withLock {
                val current = watchLater.value.titles
                val exists = current.any { it.identity() == title.identity() }
                if (!exists && current.size >= 100) {
                    mutableWatchLater.update {
                        it.copy(busy = false, error = "Watch later is full (100 titles). Remove a title before adding another.")
                    }
                    return@withLock
                }
                val next = if (exists) current.filterNot { it.identity() == title.identity() } else listOf(title) + current
                try {
                    watchLaterStore.save(next)
                    mutableWatchLater.value = WatchLaterState(next, loaded = true, busy = false)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    mutableWatchLater.update {
                        it.copy(busy = false, error = "Watch later could not be saved. Previous titles are unchanged. Try the action again.")
                    }
                }
            }
        }
    }

    fun openSection(section: CatalogSection) {
        cancel()
        mutableState.update {
            it.copy(section = section, route = CatalogRoute.RESULTS, titles = emptyList(), selected = null,
                details = null, searched = false, page = 1, hasMore = false, error = null,
                focusEpoch = it.focusEpoch + 1)
        }
        if (section == CatalogSection.DISCOVERY) browse()
    }

    fun browse(
        kind: MediaKind = state.value.browseKind,
        collection: CatalogCollection = state.value.collection,
        genreId: Int? = state.value.genreId,
        page: Int = 1,
    ) {
        val client = catalog ?: return
        if (!active || page !in 1..500) return
        cancel()
        val changedKind = kind != state.value.browseKind
        mutableState.update {
            it.copy(section = CatalogSection.DISCOVERY, route = CatalogRoute.RESULTS,
                browseKind = kind, collection = collection, genreId = if (changedKind) null else genreId,
                genres = if (changedKind) emptyList() else it.genres, page = page,
                titles = emptyList(), selected = null, searched = false, hasMore = false,
                focusEpoch = it.focusEpoch + 1)
        }
        val current = state.value
        load {
            val genres = if (current.genres.isEmpty()) client.genres(kind) else current.genres
            val result = client.browse(kind, collection, page, current.genreId)
            current.copy(genres = genres, titles = result.items, page = result.page,
                hasMore = result.hasMore && result.page < 500, searched = true,
                focusEpoch = current.focusEpoch + 1)
        }
    }

    fun page(number: Int) {
        if (state.value.section == CatalogSection.DISCOVERY) browse(page = number) else search(number)
    }

    fun configure(value: CatalogConfiguration) {
        if (configuration == value) return
        cancel()
        configuration = value
        catalog = value.token.takeIf { it.isNotBlank() }?.let { TmdbCatalog(it, value.catalogLanguage) }
        provider = MultiSourceProviders(value)
        viewports.clear()
        mutableState.value = MovieCatalogState(focusEpoch = state.value.focusEpoch + 1)
    }

    fun activate() { active = true }

    private fun cancel() {
        generation++
        request?.cancel()
        request = null
        mutableState.update {
            it.copy(loading = false, providerStatuses = it.providerStatuses.map { status ->
                if (status.state == SourceProviderState.LOADING) status.copy(
                    state = SourceProviderState.ERROR, error = "Search cancelled. Retry to finish.",
                ) else status
            })
        }
    }

    fun deactivate() {
        active = false
        val pending = state.value.loading
        cancel()
        if (pending && state.value.route != CatalogRoute.SOURCES) {
            mutableState.update { it.copy(error = "Request cancelled. Select Retry to continue.") }
        }
    }

    private fun load(afterLoad: (() -> Unit)? = null, block: suspend () -> MovieCatalogState) {
        if (!active) return
        cancel()
        val revision = generation
        mutableState.update { it.copy(loading = true, error = null) }
        request = viewModelScope.launch {
            try {
                val result = block()
                ensureActive()
                if (active && revision == generation) {
                    mutableState.value = result.copy(loading = false, error = null)
                    afterLoad?.invoke()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (active && revision == generation) mutableState.update {
                    it.copy(loading = false, error = error.message ?: "Request failed. Check your connection and settings, then retry.")
                }
            }
        }
    }

    fun setQuery(query: String) {
        if (query == state.value.query) return
        cancel()
        mutableState.update {
            it.copy(section = CatalogSection.SEARCH, query = query, route = CatalogRoute.RESULTS,
                titles = emptyList(), searched = false, selected = null, error = null)
        }
    }

    fun setFilter(filter: CatalogFilter) {
        if (state.value.filter == filter) return
        cancel()
        mutableState.update {
            it.copy(section = CatalogSection.SEARCH, filter = filter, route = CatalogRoute.RESULTS,
                titles = emptyList(), searched = false, selected = null, error = null)
        }
        if (state.value.query.isNotBlank()) search()
    }

    fun search(page: Int = 1) {
        val client = catalog ?: return
        if (!active || state.value.query.isBlank() || page < 1) return
        cancel()
        mutableState.update {
            it.copy(section = CatalogSection.SEARCH, route = CatalogRoute.RESULTS, page = page, titles = emptyList(), hasMore = false,
                searched = false, selected = null, details = null, season = null, episodes = emptyList(),
                episodesLoaded = false, episode = null, sources = null, historySources = false)
        }
        val current = state.value
        load {
            val result = client.search(current.query.trim(), current.filter, page)
            current.copy(titles = result.items, page = result.page, hasMore = result.hasMore,
                searched = true, focusEpoch = current.focusEpoch + 1)
        }
    }

    fun select(title: CatalogTitle) {
        cancel()
        mutableState.update {
            it.copy(route = CatalogRoute.DETAILS, selected = title, details = null, season = null,
                episodes = emptyList(), episodesLoaded = false, episode = null, sources = null,
                historySources = false, error = null, focusEpoch = it.focusEpoch + 1)
        }
        loadDetails()
    }

    private fun loadDetails() {
        val client = catalog ?: run {
            mutableState.update { it.copy(error = "Connect TMDB in Settings to load current details and find sources.") }
            return
        }
        val current = state.value
        val title = current.selected ?: return
        load { current.copy(details = client.details(title)) }
    }

    fun selectSeason(number: Int) {
        cancel()
        mutableState.update {
            it.copy(route = CatalogRoute.EPISODES, season = number, episodes = emptyList(), episodesLoaded = false,
                episode = null, sources = null, historySources = false, error = null, focusEpoch = it.focusEpoch + 1)
        }
        loadEpisodes()
    }

    private fun loadEpisodes() {
        val client = catalog ?: return
        val current = state.value
        val details = current.details ?: return
        val season = current.season ?: return
        load { current.copy(episodes = client.episodes(details, season), episodesLoaded = true) }
    }

    fun findSources(episode: CatalogEpisode? = null) {
        if (state.value.details == null) return
        cancel()
        mutableState.update {
            it.copy(route = CatalogRoute.SOURCES, episode = episode, sources = null,
                historySources = false, error = null, focusEpoch = it.focusEpoch + 1)
        }
        loadSources()
    }

    fun findHistorySources(selection: CatalogSelection) {
        cancel()
        mutableState.update {
            it.copy(route = CatalogRoute.SOURCES, selected = selection.details.title,
                details = selection.details, season = selection.episode?.season,
                episode = selection.episode, episodes = emptyList(), episodesLoaded = false,
                sources = null, historySources = true, error = null, focusEpoch = it.focusEpoch + 1)
        }
        loadSources()
    }

    fun recoverSources(selection: CatalogSelection) {
        val current = state.value
        if (current.route == CatalogRoute.SOURCES && current.details == selection.details &&
            current.episode == selection.episode) {
            loadSources()
        } else {
            findHistorySources(selection)
        }
    }

    fun nextEpisode(selection: CatalogSelection) {
        val episode = selection.episode ?: return
        cancel()
        mutableState.update {
            it.copy(route = CatalogRoute.NEXT_EPISODE, selected = selection.details.title,
                details = selection.details, season = episode.season, episode = episode,
                episodes = emptyList(), episodesLoaded = false, sources = null, historySources = false,
                nextEpisodeAbsent = false, error = null, focusEpoch = it.focusEpoch + 1)
        }
        loadNextEpisode()
    }

    private fun loadNextEpisode() {
        val current = state.value
        val title = current.selected ?: return
        val episode = current.episode ?: return
        mutableState.update { it.copy(nextEpisodeAbsent = false) }
        load(afterLoad = { if (state.value.route == CatalogRoute.SOURCES) loadSources() }) {
            val client = catalog ?: error("Configure a TMDB API read access token to find the next episode.")
            val details = client.details(title)
            val next = findNextEpisode(details, episode) { client.episodes(details, it) }
            if (next == null) {
                current.copy(details = details, nextEpisodeAbsent = true)
            } else {
                current.copy(route = CatalogRoute.SOURCES, details = details, selected = details.title,
                    episode = next.episode, season = next.episode.season, episodes = next.seasonEpisodes,
                    episodesLoaded = true, sources = null, providerStatuses = emptyList(), nextEpisodeAbsent = false,
                    focusEpoch = current.focusEpoch + 1)
            }
        }
    }

    private fun loadSources() {
        val client = provider ?: return
        val current = state.value
        val details = current.details ?: return
        if (!active) return
        cancel()
        val revision = generation
        mutableState.update { it.copy(sources = null, providerStatuses = emptyList(), loading = true, error = null) }
        request = viewModelScope.launch {
            try {
                client.sources(details, current.episode).collect { result ->
                    ensureActive()
                    if (active && revision == generation) mutableState.update {
                        it.copy(sources = result.page, providerStatuses = result.statuses,
                            loading = result.loading, error = null)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (active && revision == generation) mutableState.update {
                    it.copy(loading = false, error = "Unable to search source providers. Try again.")
                }
            }
        }
    }

    fun retry() {
        when (state.value.route) {
            CatalogRoute.RESULTS -> page(state.value.page)
            CatalogRoute.DETAILS -> loadDetails()
            CatalogRoute.EPISODES -> loadEpisodes()
            CatalogRoute.SOURCES -> loadSources()
            CatalogRoute.NEXT_EPISODE -> loadNextEpisode()
        }
    }

    fun back(): Boolean {
        val current = state.value
        cancel()
        val route = when (current.route) {
            CatalogRoute.RESULTS -> {
                if (current.section == CatalogSection.HOME) return false
                openSection(CatalogSection.HOME)
                return true
            }
            CatalogRoute.DETAILS -> CatalogRoute.RESULTS
            CatalogRoute.EPISODES -> CatalogRoute.DETAILS
            CatalogRoute.NEXT_EPISODE -> CatalogRoute.DETAILS
            CatalogRoute.SOURCES -> when {
                current.historySources -> CatalogRoute.RESULTS
                current.episode == null -> CatalogRoute.DETAILS
                else -> CatalogRoute.EPISODES
            }
        }
        mutableState.update {
            it.copy(route = route, historySources = false, error = null, focusEpoch = it.focusEpoch + 1)
        }
        return true
    }

    fun selection(source: CatalogSource, expected: CatalogConfiguration): CatalogSelection? {
        val current = state.value
        if (!active || configuration != expected || current.route != CatalogRoute.SOURCES ||
            current.sources?.sources?.none {
                it.infoHash.equals(source.infoHash, ignoreCase = true) && it.fileIndex == source.fileIndex &&
                    (it.fileIndex != null || it.filename == source.filename)
            } != false) return null
        return CatalogSelection(current.details ?: return null, current.episode, source)
    }
}

private val CatalogBackground = Color(0xFF10131B)
private val CatalogMuted = Color(0xFFBCC5D7)
private val CatalogAccent = Color(0xFFB8D8FF)

@Composable
fun MovieCatalogScreen(
    configuration: CatalogConfiguration,
    history: List<WatchEntry>,
    historyError: String?,
    onResume: (WatchEntry, Boolean) -> Unit,
    onRemoveHistory: (WatchEntry) -> Unit,
    onSetHistoryCompleted: (WatchEntry, Boolean) -> Unit,
    onConfigure: () -> Unit,
    onArchive: () -> Unit,
    onBack: () -> Unit,
    onOpenSource: (CatalogSelection) -> Unit,
    onAutoSources: (List<CatalogSelection>) -> Unit,
    recoverySelection: CatalogSelection?,
    onRecoveryConsumed: () -> Unit,
    nextEpisodeSelection: CatalogSelection?,
    onNextEpisodeConsumed: () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val model: MovieCatalogViewModel = viewModel(factory = remember {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = MovieCatalogViewModel(context) as T
        }
    })
    val state by model.state.collectAsStateWithLifecycle()
    val watchLater by model.watchLater.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val backFocus = remember { FocusRequester() }
    val searchFocus = remember { FocusRequester() }
    val queryFocus = remember { FocusRequester() }
    val searchScroll = rememberLazyGridState()
    val discoveryScroll = rememberLazyGridState()
    val watchLaterScroll = rememberLazyGridState()
    val historyScroll = rememberLazyListState()
    val resultsScroll = when (state.section) {
        CatalogSection.WATCH_LATER -> watchLaterScroll
        CatalogSection.DISCOVERY -> discoveryScroll
        else -> searchScroll
    }
    val visibleTitles = if (state.section == CatalogSection.WATCH_LATER) watchLater.titles else state.titles
    val listKey = when (state.section) {
        CatalogSection.SEARCH -> "search:${state.query}:${state.filter}:${state.page}"
        CatalogSection.DISCOVERY -> "browse:${state.browseKind}:${state.collection}:${state.genreId}:${state.page}"
        else -> state.section.name
    }
    var editingQuery by remember { mutableStateOf(false) }
    var keyboardWasVisible by remember { mutableStateOf(false) }
    var homeFocusPending by remember { mutableStateOf(true) }
    var pendingResultFocus by remember { mutableStateOf<String?>(null) }
    var pendingHistoryFocus by remember { mutableStateOf<String?>(null) }
    val latestHistory = remember(history) {
        history.sortedByDescending { it.updatedAtMs }.distinctBy { it.key }
    }
    var resumeEntry by remember { mutableStateOf<WatchEntry?>(null) }
    var choosingGenre by remember { mutableStateOf(false) }

    DisposableEffect(model, lifecycle, configuration) {
        model.configure(configuration)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) model.activate()
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> model.activate()
                Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY -> model.deactivate()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            model.deactivate()
        }
    }

    LaunchedEffect(recoverySelection) {
        recoverySelection?.let {
            model.recoverSources(it)
            onRecoveryConsumed()
        }
    }

    LaunchedEffect(nextEpisodeSelection) {
        nextEpisodeSelection?.let {
            model.nextEpisode(it)
            onNextEpisodeConsumed()
        }
    }

    fun dismissInput() {
        keyboard?.hide()
        focusManager.clearFocus(force = true)
        editingQuery = false
    }

    fun goBack() {
        if (editingQuery) {
            dismissInput()
            homeFocusPending = true
        } else if (!model.back()) {
            model.deactivate()
            onBack()
        }
    }

    fun leave(action: () -> Unit) {
        dismissInput()
        model.deactivate()
        action()
    }

    BackHandler(onBack = ::goBack)
    LaunchedEffect(editingQuery, imeVisible) {
        if (!editingQuery) {
            keyboardWasVisible = false
        } else if (imeVisible) {
            keyboardWasVisible = true
        } else if (keyboardWasVisible) {
            dismissInput()
            homeFocusPending = true
        }
    }

    LaunchedEffect(state.route, state.focusEpoch, visibleTitles, latestHistory) {
        if (editingQuery) return@LaunchedEffect
        dismissInput()
        pendingResultFocus = null
        pendingHistoryFocus = null
        homeFocusPending = state.route == CatalogRoute.RESULTS
        if (state.route == CatalogRoute.RESULTS) {
            val saved = model.viewports[listKey]
            if (visibleTitles.isNotEmpty()) {
                val index = (saved?.index ?: 0).coerceIn(0, visibleTitles.lastIndex)
                resultsScroll.scrollToItem(index, saved?.offset ?: 0)
                pendingResultFocus = saved?.focused?.takeIf { key -> visibleTitles.any { it.identity() == key } }
                    ?: visibleTitles[index].identity()
                homeFocusPending = false
            } else if (state.section == CatalogSection.HISTORY && latestHistory.isNotEmpty()) {
                val index = (saved?.index ?: 0).coerceIn(0, latestHistory.lastIndex)
                historyScroll.scrollToItem(index, saved?.offset ?: 0)
                pendingHistoryFocus = saved?.focused?.takeIf { key -> latestHistory.any { it.key == key } }
                    ?: latestHistory[index].key
                homeFocusPending = false
            }
        }
        if (pendingResultFocus == null && pendingHistoryFocus == null &&
            state.route == CatalogRoute.RESULTS && state.section !in listOf(CatalogSection.HOME, CatalogSection.SEARCH)) {
            withFrameNanos { }
            searchFocus.requestFocus()
            homeFocusPending = false
        }
    }

    fun rememberHistory(entry: WatchEntry) {
        model.saveViewport(listKey, CatalogViewport(
            historyScroll.firstVisibleItemIndex, historyScroll.firstVisibleItemScrollOffset, entry.key,
        ))
    }

    fun closeHistoryDialog() {
        resumeEntry = null
        val saved = model.viewports[listKey]
        pendingHistoryFocus = saved?.focused?.takeIf { key -> latestHistory.any { it.key == key } }
            ?: latestHistory.getOrNull((saved?.index ?: 0).coerceAtMost((latestHistory.size - 1).coerceAtLeast(0)))?.key
        homeFocusPending = latestHistory.isEmpty()
    }

    fun search() {
        dismissInput()
        homeFocusPending = true
        model.search()
    }

    resumeEntry?.let { entry ->
        CatalogResumeDialog(
            entry = entry,
            onDismiss = ::closeHistoryDialog,
            onResume = { startOver ->
                resumeEntry = null
                leave { onResume(entry, startOver) }
            },
            onChooseSource = {
                resumeEntry = null
                dismissInput()
                model.findHistorySources(entry.selection)
            },
            onChooseEpisode = {
                resumeEntry = null
                dismissInput()
                model.select(entry.selection.details.title)
            },
            onRemove = {
                onRemoveHistory(entry)
                closeHistoryDialog()
            },
            onSetCompleted = {
                onSetHistoryCompleted(entry, !entry.completed)
                closeHistoryDialog()
            },
        )
    }
    if (choosingGenre) {
        Dialog(onDismissRequest = { choosingGenre = false }) {
            val initial = remember { FocusRequester() }
            LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = 400.dp)
                    .background(CatalogBackground, RoundedCornerShape(16.dp)).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item { Text("Choose genre", color = Color.White, fontSize = 24.sp) }
                item {
                    CatalogButton("All genres", Modifier.fillMaxWidth().focusRequester(initial),
                        selected = state.genreId == null) {
                        choosingGenre = false
                        model.browse(genreId = null)
                    }
                }
                items(state.genres, key = { it.id }) { genre ->
                    CatalogButton(genre.name, Modifier.fillMaxWidth(), selected = state.genreId == genre.id) {
                        choosingGenre = false
                        model.browse(genreId = genre.id)
                    }
                }
                item { CatalogButton("Back", Modifier.fillMaxWidth()) { choosingGenre = false } }
            }
            LaunchedEffect(Unit) {
                withFrameNanos { }
                initial.requestFocus()
            }
        }
    }
    Column(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF1A263A), CatalogBackground, Color(0xFF0C1018))))
            .padding(horizontal = 32.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.route != CatalogRoute.RESULTS || state.section != CatalogSection.HOME) {
                CatalogButton(
                    when (state.route) {
                        CatalogRoute.RESULTS -> "Back to Home"
                        CatalogRoute.DETAILS -> "Back to ${state.section.label()}"
                        CatalogRoute.EPISODES -> "Back to seasons"
                        CatalogRoute.NEXT_EPISODE -> "Back to seasons"
                        CatalogRoute.SOURCES -> when {
                            state.historySources -> "Back to ${state.section.label()}"
                            state.episode == null -> "Back to details"
                            else -> "Back to episodes"
                        }
                    },
                    Modifier.focusRequester(backFocus), onClick = ::goBack,
                )
                if (state.route != CatalogRoute.RESULTS) LaunchedEffect(state.route, state.focusEpoch) {
                    withFrameNanos { }
                    backFocus.requestFocus()
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("LumaStream", color = Color.White, fontSize = 25.sp)
                Text("MOVIES & SERIES", color = CatalogAccent, fontSize = 10.sp, letterSpacing = 2.sp)
            }
            if (state.route == CatalogRoute.RESULTS &&
                state.section !in listOf(CatalogSection.HOME, CatalogSection.SEARCH)) {
                CatalogButton("Search", Modifier.focusRequester(searchFocus)) {
                    model.openSection(CatalogSection.SEARCH)
                }
            }
            CatalogButton("Archive") { leave(onArchive) }
            CatalogButton("Settings") { leave(onConfigure) }
        }
        when (state.route) {
            CatalogRoute.RESULTS -> {
                if (state.section == CatalogSection.HOME || state.section == CatalogSection.SEARCH) {
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                            .background(Brush.horizontalGradient(listOf(Color(0xFF263B57), Color(0xFF192535))))
                            .padding(horizontal = 24.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (state.section == CatalogSection.HOME) {
                            Text("What will you watch?", color = Color.White, fontSize = 30.sp)
                        }
                        if (editingQuery) {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = state.query,
                                    onValueChange = model::setQuery,
                                    modifier = Modifier.weight(1f).focusRequester(queryFocus),
                                    label = { Text("Search movies and series") },
                                    singleLine = true,
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                                        cursorColor = CatalogAccent, focusedBorderColor = CatalogAccent,
                                        unfocusedBorderColor = CatalogMuted, focusedLabelColor = CatalogAccent,
                                        unfocusedLabelColor = CatalogMuted,
                                    ),
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                    keyboardActions = KeyboardActions(onSearch = { search() }),
                                )
                                CatalogButton("Search", enabled = state.query.isNotBlank(), onClick = ::search)
                            }
                            LaunchedEffect(Unit) {
                                withFrameNanos { }
                                queryFocus.requestFocus()
                                keyboard?.show()
                            }
                        } else {
                            Button(
                                onClick = {
                                    if (configuration.token.isBlank()) leave(onConfigure) else editingQuery = true
                                },
                                modifier = Modifier.fillMaxWidth().height(54.dp).focusRequester(searchFocus),
                                scale = ButtonDefaults.scale(focusedScale = 1.02f),
                                colors = ButtonDefaults.colors(
                                    containerColor = Color(0xFF334C6B), contentColor = Color.White,
                                    focusedContainerColor = CatalogAccent, focusedContentColor = Color(0xFF10213B),
                                ),
                            ) {
                                androidx.tv.material3.Text(
                                    if (state.query.isBlank()) "Search movies and series" else "Search: ${state.query}",
                                    fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                            LaunchedEffect(homeFocusPending, state.focusEpoch, windowFocused) {
                                if (windowFocused && homeFocusPending && resumeEntry == null) {
                                    withFrameNanos { }
                                    searchFocus.requestFocus()
                                    homeFocusPending = false
                                }
                            }
                        }
                        if (configuration.token.isBlank()) {
                            Text("Connect TMDB in Settings to search and discover.", color = CatalogMuted, fontSize = 14.sp)
                        } else if (state.section == CatalogSection.SEARCH) {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("Search", color = CatalogMuted, fontSize = 13.sp)
                                CatalogFilter.entries.forEach { filter ->
                                    CatalogButton(
                                        when (filter) {
                                            CatalogFilter.ALL -> "All"
                                            CatalogFilter.MOVIES -> "Movies"
                                            CatalogFilter.SERIES -> "Series"
                                        }, selected = state.filter == filter,
                                    ) {
                                        dismissInput()
                                        model.setFilter(filter)
                                    }
                                }
                            }
                        }
                    }
                }
                if (state.section == CatalogSection.HOME) {
                    LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        item {
                            CatalogButton("Discover · Popular, top rated & genres", Modifier.fillMaxWidth()) {
                                if (configuration.token.isBlank()) leave(onConfigure)
                                else model.openSection(CatalogSection.DISCOVERY)
                            }
                        }
                        item {
                            CatalogButton("Watch later · ${watchLater.titles.size}", Modifier.fillMaxWidth()) {
                                model.openSection(CatalogSection.WATCH_LATER)
                            }
                        }
                        item {
                            CatalogButton("History · Continue watching & watched", Modifier.fillMaxWidth()) {
                                model.openSection(CatalogSection.HISTORY)
                            }
                        }
                        item {
                            Text("Movie and series metadata by TMDB. Availability and rights are separate.",
                                color = CatalogMuted, fontSize = 13.sp)
                        }
                    }
                }
                if (state.section == CatalogSection.DISCOVERY) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        CatalogButton("Movies", selected = state.browseKind == MediaKind.MOVIE) {
                            model.browse(kind = MediaKind.MOVIE)
                        }
                        CatalogButton("Series", selected = state.browseKind == MediaKind.SERIES) {
                            model.browse(kind = MediaKind.SERIES)
                        }
                        CatalogButton("Popular", selected = state.collection == CatalogCollection.POPULAR) {
                            model.browse(collection = CatalogCollection.POPULAR)
                        }
                        CatalogButton("Top rated", selected = state.collection == CatalogCollection.TOP_RATED) {
                            model.browse(collection = CatalogCollection.TOP_RATED)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        CatalogButton(
                            state.genres.firstOrNull { it.id == state.genreId }?.name ?: "All genres",
                            enabled = state.genres.isNotEmpty() && !state.loading,
                        ) { choosingGenre = true }
                        Text("TMDB metadata · Playback availability is separate", color = CatalogMuted, fontSize = 13.sp,
                            modifier = Modifier.weight(1f))
                    }
                }
                if (state.section == CatalogSection.HISTORY || state.section == CatalogSection.HOME) {
                    historyError?.let {
                        Text("Watch history: $it", color = Color(0xFFFFB4AB), fontSize = 14.sp,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (state.section == CatalogSection.WATCH_LATER) {
                    WatchLaterStatus(watchLater, model::loadWatchLater)
                    if (watchLater.loaded && watchLater.titles.isEmpty()) {
                        Text("Watch later is empty. Open a movie or series and choose Add to Watch later.",
                            color = CatalogMuted, fontSize = 18.sp)
                    }
                }
                CatalogStatus(state, model::retry)
                if (!state.loading && state.error == null && state.searched && state.titles.isEmpty()) {
                    Text("No matching titles. Try another search, collection or genre.", color = CatalogMuted)
                    if (state.page > 1) CatalogButton("Previous page") { model.page(state.page - 1) }
                }
                if (visibleTitles.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (state.section == CatalogSection.WATCH_LATER) "Watch later · ${visibleTitles.size} / 100"
                            else "${state.section.label()} · Page ${state.page}",
                            color = Color.White, fontSize = 18.sp, modifier = Modifier.weight(1f),
                        )
                        if (state.section != CatalogSection.WATCH_LATER) {
                            if (state.page > 1) CatalogButton("Previous page") { model.page(state.page - 1) }
                            if (state.hasMore) CatalogButton("Next page") { model.page(state.page + 1) }
                        }
                    }
                    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                        val posterHeight = (maxHeight - 94.dp).coerceIn(60.dp, 220.dp)
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(150.dp), state = resultsScroll,
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp),
                        ) {
                            itemsIndexed(visibleTitles, key = { _, title -> title.identity() }) { _, title ->
                                val requester = remember { FocusRequester() }
                                LaunchedEffect(pendingResultFocus) {
                                    if (pendingResultFocus == title.identity()) {
                                        val index = resultsScroll.firstVisibleItemIndex
                                        val offset = resultsScroll.firstVisibleItemScrollOffset
                                        withFrameNanos { }
                                        requester.requestFocus()
                                        withFrameNanos { }
                                        resultsScroll.scrollToItem(index, offset)
                                        pendingResultFocus = null
                                    }
                                }
                                fun saveViewport() {
                                    model.saveViewport(listKey, CatalogViewport(
                                        resultsScroll.firstVisibleItemIndex,
                                        resultsScroll.firstVisibleItemScrollOffset, title.identity(),
                                    ))
                                }
                                CatalogTile(
                                    Modifier.focusRequester(requester).onFocusChanged { if (it.isFocused) saveViewport() },
                                    onClick = {
                                        saveViewport()
                                        model.select(title)
                                    },
                                ) {
                                    CatalogImage(title.posterPath, CatalogImageType.POSTER, title.title,
                                        Modifier.fillMaxWidth().height(posterHeight))
                                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(title.title, color = Color.White, fontSize = 17.sp, lineHeight = 20.sp,
                                            minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Text(title.subtitle(), color = CatalogMuted, fontSize = 13.sp, lineHeight = 16.sp,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }
                } else if (state.section == CatalogSection.HISTORY) {
                    if (latestHistory.isEmpty()) {
                        Text("History is empty. Titles appear here after playback.", color = CatalogMuted, fontSize = 18.sp)
                    } else {
                        CatalogHistoryRow(
                            latestHistory, Modifier.weight(1f).fillMaxWidth(), historyScroll,
                            pendingFocus = pendingHistoryFocus,
                            onFocusRestored = { pendingHistoryFocus = null },
                            onFocused = ::rememberHistory,
                        ) { entry ->
                            rememberHistory(entry)
                            resumeEntry = entry
                        }
                    }
                }
            }
            CatalogRoute.DETAILS -> CatalogDetailsContent(
                state, model::retry, model::findSources, model::selectSeason,
                watchLater, model::loadWatchLater, model::toggleWatchLater,
            )
            CatalogRoute.EPISODES -> CatalogEpisodesContent(state, model::retry, model::findSources)
            CatalogRoute.NEXT_EPISODE -> {
                CatalogReadable(state.selected?.title.orEmpty(), heading = true)
                CatalogReadable("After S${state.episode?.season} E${state.episode?.number} · ${state.episode?.title.orEmpty()}")
                CatalogStatus(state, model::retry)
                if (state.nextEpisodeAbsent && !state.loading && state.error == null) {
                    CatalogReadable("No next episode is listed by TMDB. Specials stay within Season 0; regular seasons follow episode numbering.")
                    CatalogButton("Refresh episodes", onClick = model::retry)
                }
            }
            CatalogRoute.SOURCES -> CatalogSourcesContent(state, model::retry, automatic = { sources ->
                val selections = sources.mapNotNull { model.selection(it, configuration) }
                if (selections.size == sources.size && selections.isNotEmpty()) {
                    dismissInput()
                    onAutoSources(selections)
                }
            }) { source ->
                model.selection(source, configuration)?.let { selection ->
                    dismissInput()
                    onOpenSource(selection)
                }
            }
        }
    }
}

@Composable
private fun CatalogHistoryRow(
    entries: List<WatchEntry>,
    modifier: Modifier,
    scroll: LazyListState,
    pendingFocus: String?,
    onFocusRestored: () -> Unit,
    onFocused: (WatchEntry) -> Unit,
    select: (WatchEntry) -> Unit,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Continue watching · Watched", color = Color.White, fontSize = 22.sp)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val posterHeight = (maxHeight - 128.dp).coerceIn(32.dp, 160.dp)
            LazyRow(
                modifier = Modifier.fillMaxSize(),
                state = scroll,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp),
            ) {
                items(entries, key = { it.key }) { entry ->
                    val requester = remember { FocusRequester() }
                    LaunchedEffect(pendingFocus) {
                        if (pendingFocus == entry.key) {
                            val index = scroll.firstVisibleItemIndex
                            val offset = scroll.firstVisibleItemScrollOffset
                            withFrameNanos { }
                            requester.requestFocus()
                            withFrameNanos { }
                            scroll.scrollToItem(index, offset)
                            onFocusRestored()
                        }
                    }
                    val title = entry.selection.details.title
                    CatalogTile(
                        Modifier.width(188.dp).focusRequester(requester)
                            .onFocusChanged { if (it.isFocused) onFocused(entry) },
                        onClick = { select(entry) },
                    ) {
                        CatalogImage(title.posterPath, CatalogImageType.POSTER, title.title,
                            Modifier.fillMaxWidth().height(posterHeight))
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(title.title, color = Color.White, fontSize = 16.sp, lineHeight = 18.sp,
                                minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                entry.selection.episode?.let { "S${it.season} · E${it.number}" } ?: title.subtitle(),
                                color = CatalogMuted, fontSize = 13.sp, lineHeight = 16.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            Text(entry.watchTime(), color = CatalogMuted, fontSize = 12.sp,
                                lineHeight = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            LinearProgressIndicator(
                                progress = {
                                    if (entry.completed) 1f else if (entry.durationMs > 0) {
                                        (entry.positionMs.toDouble() / entry.durationMs).toFloat().coerceIn(0f, 1f)
                                    } else 0f
                                },
                                modifier = Modifier.fillMaxWidth().height(4.dp),
                                color = CatalogAccent,
                                trackColor = Color(0xFF354056),
                            )
                            Text(if (entry.completed) "Watched" else "Continue watching",
                                color = CatalogAccent, fontSize = 12.sp, lineHeight = 15.sp, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CatalogResumeDialog(
    entry: WatchEntry,
    onDismiss: () -> Unit,
    onResume: (Boolean) -> Unit,
    onChooseSource: () -> Unit,
    onChooseEpisode: () -> Unit,
    onRemove: () -> Unit,
    onSetCompleted: () -> Unit,
) {
    val initialFocus = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp)
                .background(CatalogBackground, RoundedCornerShape(16.dp)).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(entry.selection.details.title.title, color = Color.White, fontSize = 24.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            entry.selection.episode?.let { episode ->
                item {
                    Text("S${episode.season} · E${episode.number} — ${episode.title}",
                        color = CatalogAccent, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                item {
                    Text("History changes below affect only this episode, not the whole series.",
                        color = CatalogMuted, fontSize = 14.sp)
                }
            }
            item {
                Text((if (entry.completed) "Watched · " else "") + entry.watchTime(),
                    color = CatalogMuted, fontSize = 14.sp)
            }
            if (!entry.completed) {
                item {
                    CatalogButton("Continue", Modifier.fillMaxWidth().focusRequester(initialFocus)) {
                        onResume(false)
                    }
                }
            }
            if (entry.selection.details.title.kind == MediaKind.SERIES) {
                item { CatalogButton("Choose episode", Modifier.fillMaxWidth(), onClick = onChooseEpisode) }
            }
            item {
                CatalogButton("Start from beginning", Modifier.fillMaxWidth().then(
                    if (entry.completed) Modifier.focusRequester(initialFocus) else Modifier,
                )) { onResume(true) }
            }
            item { CatalogButton("Choose another source", Modifier.fillMaxWidth(), onClick = onChooseSource) }
            item {
                CatalogButton(
                    if (entry.completed) "Mark unwatched · reset saved position" else "Mark watched",
                    Modifier.fillMaxWidth(), onClick = onSetCompleted,
                )
            }
            item {
                CatalogButton(
                    if (entry.selection.episode != null) "Remove this episode from history" else "Remove from history",
                    Modifier.fillMaxWidth(), onClick = onRemove,
                )
            }
            item { CatalogButton("Back", Modifier.fillMaxWidth(), onClick = onDismiss) }
        }
        LaunchedEffect(Unit) {
            withFrameNanos { }
            initialFocus.requestFocus()
        }
    }
}

private fun WatchEntry.watchTime(): String =
    if (durationMs > 0) "${formatWatchTime(positionMs)} / ${formatWatchTime(durationMs)}"
    else "${formatWatchTime(positionMs)} played · total unknown"

private fun formatWatchTime(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    return if (seconds >= 3600) {
        String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
    } else {
        String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
    }
}

@Composable
private fun WatchLaterStatus(state: WatchLaterState, retry: () -> Unit) {
    if (state.busy) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(22.dp), color = CatalogAccent, strokeWidth = 2.dp)
            Text(if (state.loaded) "Saving Watch later…" else "Loading Watch later…", color = CatalogMuted)
        }
    }
    state.error?.let {
        Text(it, color = Color(0xFFFFB4AB), fontSize = 14.sp)
        if (!state.loaded) CatalogButton("Retry loading Watch later", enabled = !state.busy, onClick = retry)
    }
}

@Composable
private fun CatalogDetailsContent(
    state: MovieCatalogState,
    retry: () -> Unit,
    sources: (CatalogEpisode?) -> Unit,
    season: (Int) -> Unit,
    watchLater: WatchLaterState,
    retryWatchLater: () -> Unit,
    toggleWatchLater: (CatalogTitle) -> Unit,
) {
    val title = state.details?.title ?: state.selected ?: return
    LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Box(Modifier.fillMaxWidth().height(235.dp).clip(RoundedCornerShape(12.dp))) {
                CatalogImage(title.backdropPath, CatalogImageType.BACKDROP, "${title.title} backdrop", Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0xF010131B), Color(0xB010131B)))))
                Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(22.dp), verticalAlignment = Alignment.CenterVertically) {
                    CatalogImage(title.posterPath, CatalogImageType.POSTER, title.title, Modifier.width(125.dp).aspectRatio(2f / 3f))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(title.title, color = Color.White, fontSize = 27.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Text(title.subtitle() + (state.details?.runtimeMinutes?.let { " · $it min" } ?: ""), color = Color.White)
                    }
                }
            }
        }
        item { CatalogStatus(state, retry) }
        item { WatchLaterStatus(watchLater, retryWatchLater) }
        item {
            CatalogButton(
                if (watchLater.titles.any { it.identity() == title.identity() }) "Remove from Watch later"
                else "Add to Watch later",
                Modifier.fillMaxWidth(), enabled = watchLater.loaded && !watchLater.busy,
            ) { toggleWatchLater(title) }
        }
        item { CatalogReadable(title.title, heading = true) }
        if (title.originalTitle.isNotBlank() && title.originalTitle != title.title) {
            item { CatalogReadable("Original title: ${title.originalTitle}") }
        }
        if (state.details != null) {
            if (title.kind == MediaKind.MOVIE) {
                item { CatalogButton("Find sources", Modifier.fillMaxWidth()) { sources(null) } }
            } else {
                item { CatalogReadable("Seasons", heading = true) }
                if (state.details.seasons.isEmpty()) item { CatalogReadable("TMDB has no season information for this series.") }
                items(state.details.seasons, key = { it.number }) { entry ->
                    CatalogButton("${entry.name} · ${entry.episodeCount} episodes", Modifier.fillMaxWidth()) { season(entry.number) }
                }
            }
        }
        items(readableSections(title.overview.ifBlank { "No synopsis available from TMDB." })) { CatalogReadable(it) }
        item { CatalogReadable("Metadata: TMDB. Source availability and rights are separate from catalog metadata.") }
    }
}

@Composable
private fun CatalogEpisodesContent(state: MovieCatalogState, retry: () -> Unit, select: (CatalogEpisode) -> Unit) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { CatalogReadable("${state.selected?.title.orEmpty()} · Season ${state.season}", heading = true) }
        item { CatalogStatus(state, retry) }
        if (state.episodesLoaded && state.episodes.isEmpty()) item { CatalogReadable("No episodes are listed for this season.") }
        items(state.episodes, key = { it.id }) { episode ->
            CatalogTile(Modifier.fillMaxWidth(), onClick = { select(episode) }) {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    CatalogImage(episode.stillPath, CatalogImageType.STILL, episode.title, Modifier.width(190.dp).aspectRatio(16f / 9f))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("S${episode.season} · E${episode.number} — ${episode.title}", color = Color.White, fontSize = 19.sp)
                        if (episode.airDate.isNotBlank()) Text("Air date: ${episode.airDate}", color = CatalogMuted)
                        Text("Find sources for this episode", color = CatalogAccent, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun CatalogSourcesContent(
    state: MovieCatalogState,
    retry: () -> Unit,
    automatic: (List<CatalogSource>) -> Unit,
    select: (CatalogSource) -> Unit,
) {
    val title = state.details?.title ?: return
    val episode = state.episode
    val context = LocalContext.current
    val compatibility = remember(context) { SourceCompatibility(context) }
    var automaticOptions by remember(state.details, state.episode) {
        mutableStateOf<List<Pair<String, List<CatalogSource>>>?>(null)
    }
    val sources = state.sources?.sources.orEmpty()
    val automaticGroups = remember(sources, compatibility) {
        sources.groupBy { source ->
            source.quality?.trim()?.lowercase(Locale.ROOT).orEmpty() to
                source.languages.map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }.distinct().sorted()
        }.mapNotNull { (cohort, entries) ->
            val ranked = rankAutomaticSources(entries, entries.first().quality, entries.first().languages) {
                compatibility.warnings(it).isEmpty()
            }
            if (ranked.isEmpty()) null else {
                val label = "${cohort.first.ifBlank { "Unknown quality" }} · " +
                    cohort.second.joinToString(", ").ifBlank { "Unknown languages" }
                label to ranked
            }
        }
    }
    automaticOptions?.let { options ->
        val initialFocus = remember { FocusRequester() }
        Dialog(onDismissRequest = { automaticOptions = null }) {
            LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = 440.dp)
                    .background(CatalogBackground, RoundedCornerShape(16.dp)).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { Text("Watch automatically", color = Color.White, fontSize = 24.sp) }
                item {
                    Text("Choose quality and provider language labels. We try up to four sources in this group, one at a time, until video starts. Labels are not verified audio tracks; unknown stays unknown. No automatic switching after playback starts.",
                        color = CatalogMuted, fontSize = 15.sp)
                }
                item {
                    Text("Up to 2 minutes, plus session cleanup. You can cancel and choose manually at any time. A quick start does not guarantee sustained speed.",
                        color = CatalogMuted, fontSize = 15.sp)
                }
                items(options.size) { index ->
                    val (label, candidates) = options[index]
                    CatalogButton("$label · ${candidates.size} source(s)", Modifier.fillMaxWidth().then(
                        if (index == 0) Modifier.focusRequester(initialFocus) else Modifier,
                    )) {
                        automaticOptions = null
                        automatic(candidates)
                    }
                }
                item { CatalogButton("Cancel", onClick = { automaticOptions = null }) }
            }
            LaunchedEffect(Unit) {
                withFrameNanos { }
                initialFocus.requestFocus()
            }
        }
    }
    // Give sources the full remaining height; provider notices must not squeeze the list.
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        LazyColumn(
            Modifier.weight(0.34f),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CatalogImage(title.posterPath, CatalogImageType.POSTER, title.title,
                        Modifier.width(52.dp).aspectRatio(2f / 3f))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(title.title, color = Color.White, fontSize = 22.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Text(title.subtitle(), color = CatalogAccent, fontSize = 13.sp)
                    }
                }
            }
            episode?.let {
                item { CatalogReadable("S${it.season} · E${it.number}\n${it.title}") }
            }
            item { Text("PROVIDERS", color = CatalogAccent, fontSize = 12.sp, letterSpacing = 1.sp) }
            item { CatalogStatus(state, retry) }
            items(state.providerStatuses) { status ->
                var focused by remember { mutableStateOf(false) }
                val message = when (status.state) {
                    SourceProviderState.LOADING -> "Searching…"
                    SourceProviderState.SUCCESS -> "${status.sourceCount} sources${if (status.cached) " · cached" else ""}"
                    SourceProviderState.ERROR -> status.error ?: "Unavailable. Try again."
                }
                Column(
                    Modifier.fillMaxWidth()
                        .border(1.dp, if (focused) CatalogAccent else Color.Transparent, RoundedCornerShape(10.dp))
                        .background(if (focused) Color(0xFF293D58) else Color(0xFF1C2330), RoundedCornerShape(10.dp))
                        .onFocusChanged { focused = it.isFocused }.focusable().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(status.label, color = Color.White, fontSize = 14.sp)
                    Text(message, color = if (status.state == SourceProviderState.ERROR) Color(0xFFFFB4AB) else CatalogMuted,
                        fontSize = 13.sp)
                }
            }
            if (!state.loading && state.providerStatuses.any { it.state == SourceProviderState.ERROR } &&
                sources.isNotEmpty()) {
                item { CatalogButton("Retry unavailable providers", onClick = retry) }
            }
            item {
                CatalogReadable("Only open content you are authorized to share. BitTorrent may upload and exposes your IP to peers.")
            }
            item {
                CatalogReadable("Availability and codec support are unverified. Quality, languages and seed counts are provider reports.")
            }
        }
        Column(Modifier.weight(0.66f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text("Choose a source", color = Color.White, fontSize = 22.sp)
                Text("${sources.size} sources", color = CatalogMuted, fontSize = 13.sp)
            }
            LazyColumn(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(10.dp),
            ) {
                val page = state.sources
                if (page != null && sources.isEmpty() && !state.loading) {
                    item {
                        CatalogReadable(if (page.unsupportedCount > 0) {
                            "Providers returned ${page.unsupportedCount} unsupported streams, but no supported torrent sources."
                        } else {
                            "No torrent sources are available. Check provider statuses."
                        })
                    }
                    item { CatalogButton("Refresh sources", onClick = retry) }
                }
                if (sources.isNotEmpty()) {
                    if (automaticGroups.isNotEmpty()) {
                        item {
                            CatalogButton("Watch automatically…", Modifier.fillMaxWidth()) {
                                automaticOptions = automaticGroups
                            }
                        }
                    } else {
                        item { CatalogReadable("No automatic candidates without compatibility warnings. Choose a source manually.") }
                    }
                    if (page != null && page.unsupportedCount > 0) {
                        item { Text("${page.unsupportedCount} unsupported streams omitted.", color = CatalogMuted, fontSize = 13.sp) }
                    }
                }
                items(sources, key = { "${it.infoHash}:${it.fileIndex}:${it.filename}" }) { source ->
                    val warnings = remember(source, compatibility) { compatibility.warnings(source) }
                    val label = remember(source.displayName, source.provider) {
                        source.displayName.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }
                            .joinToString(" · ").ifBlank { source.provider }
                    }
                    CatalogTile(Modifier.fillMaxWidth(), onClick = { select(source) }) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(source.provider, Modifier.weight(1f), color = CatalogAccent,
                                    fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(source.quality?.takeIf { it.isNotBlank() } ?: "Unknown quality",
                                    color = Color.White, fontSize = 14.sp)
                            }
                            Text(label, color = Color.White, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            source.filename?.let {
                                Text(it, color = CatalogMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            val metadata = buildList {
                                if (source.languages.isNotEmpty()) add(source.languages.joinToString(", "))
                                source.sizeBytes?.let { add(formatSourceSize(it)) }
                                source.seeders?.let { add("$it seeders · reported") }
                            }
                            if (metadata.isNotEmpty()) Text(metadata.joinToString(" · "), color = CatalogMuted,
                                fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            warnings.forEach { warning ->
                                Text(warning, color = Color(0xFFFFB4AB), fontSize = 13.sp)
                            }
                            Text("OK to play", color = CatalogAccent, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CatalogStatus(state: MovieCatalogState, retry: () -> Unit) {
    if (state.loading) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(Modifier.size(22.dp), color = CatalogAccent, strokeWidth = 2.dp)
        Text(when (state.route) {
            CatalogRoute.RESULTS -> if (state.section == CatalogSection.DISCOVERY) "Loading TMDB discovery…" else "Searching TMDB…"
            CatalogRoute.DETAILS -> "Loading title details…"
            CatalogRoute.EPISODES -> "Loading episodes…"
            CatalogRoute.SOURCES -> "Requesting torrent sources…"
            CatalogRoute.NEXT_EPISODE -> "Finding the next episode and its sources…"
        }, color = Color.White)
    }
    state.error?.let { error ->
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text((if (state.route == CatalogRoute.SOURCES) "Source request failed: " else "") + error, color = Color(0xFFFFB4AB))
            CatalogButton("Retry", onClick = retry)
        }
    }
}

@Composable
private fun CatalogButton(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick, modifier = modifier, enabled = enabled,
        colors = ButtonDefaults.colors(
            containerColor = if (selected) Color(0xFF425F80) else Color(0xFF283246),
            contentColor = Color.White,
            focusedContainerColor = CatalogAccent, focusedContentColor = Color(0xFF10213B),
        ),
    ) { androidx.tv.material3.Text(label, fontSize = 14.sp) }
}

@Composable
private fun CatalogTile(modifier: Modifier = Modifier, onClick: () -> Unit, content: @Composable () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier.graphicsLayer { scaleX = if (focused) 1.025f else 1f; scaleY = if (focused) 1.025f else 1f }
            .border(if (focused) 3.dp else 1.dp, if (focused) CatalogAccent else Color(0xFF354056), RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp)).background(if (focused) Color(0xFF293D58) else Color(0xFF1C2330))
            .onFocusChanged { focused = it.isFocused }.clickable(onClick = onClick),
    ) { content() }
}

@Composable
private fun CatalogReadable(text: String, heading: Boolean = false) {
    var focused by remember { mutableStateOf(false) }
    Text(text, color = Color.White, fontSize = if (heading) 22.sp else 16.sp,
        modifier = Modifier.fillMaxWidth().background(if (focused) Color(0xFF283246) else Color.Transparent)
            .onFocusChanged { focused = it.isFocused }.focusable().padding(8.dp))
}

private fun CatalogTitle.identity() = "${kind.apiType}:$id"
private fun CatalogTitle.subtitle() = listOfNotNull(year.takeIf { it.isNotBlank() }, if (kind == MediaKind.MOVIE) "Movie" else "Series").joinToString(" · ")

private fun readableSections(text: String): List<String> {
    val sections = mutableListOf<String>()
    val current = StringBuilder()
    for (word in text.split(Regex("\\s+"))) {
        if (current.length + word.length > 360 && current.isNotEmpty()) {
            sections.add(current.toString())
            current.clear()
        }
        if (current.isNotEmpty()) current.append(' ')
        current.append(word)
    }
    if (current.isNotEmpty()) sections.add(current.toString())
    return sections
}

private fun formatSourceSize(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> String.format(Locale.ROOT, "%.1f GiB", bytes / 1_073_741_824.0)
    bytes >= 1_048_576L -> String.format(Locale.ROOT, "%.1f MiB", bytes / 1_048_576.0)
    bytes >= 1024L -> String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0)
    else -> "$bytes bytes"
}
