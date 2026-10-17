package dev.lumastream

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import dev.lumastream.catalog.CatalogConfiguration
import dev.lumastream.catalog.CatalogDetails
import dev.lumastream.catalog.CatalogEpisode
import dev.lumastream.catalog.CatalogFilter
import dev.lumastream.catalog.CatalogSelection
import dev.lumastream.catalog.CatalogSource
import dev.lumastream.catalog.CatalogTitle
import dev.lumastream.catalog.MediaKind
import dev.lumastream.catalog.SourcePage
import dev.lumastream.catalog.StremioSources
import dev.lumastream.catalog.TmdbCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

private enum class CatalogRoute { RESULTS, DETAILS, EPISODES, SOURCES }

private data class MovieCatalogState(
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
    val loading: Boolean = false,
    val error: String? = null,
    val focusEpoch: Long = 0,
)

private class MovieCatalogViewModel : ViewModel() {
    private val mutableState = MutableStateFlow(MovieCatalogState())
    val state = mutableState.asStateFlow()
    private var configuration: CatalogConfiguration? = null
    private var catalog: TmdbCatalog? = null
    private var provider: StremioSources? = null
    private var active = false
    private var generation = 0L
    private var request: Job? = null

    fun configure(value: CatalogConfiguration) {
        if (configuration == value) return
        cancel()
        configuration = value
        catalog = value.token.takeIf { it.isNotBlank() }?.let { TmdbCatalog(it, value.catalogLanguage) }
        provider = StremioSources(value.providerManifestUrl)
        mutableState.value = MovieCatalogState(focusEpoch = state.value.focusEpoch + 1)
    }

    fun activate() { active = true }

    private fun cancel() {
        generation++
        request?.cancel()
        request = null
        mutableState.update { it.copy(loading = false) }
    }

    fun deactivate() {
        active = false
        val pending = state.value.loading
        cancel()
        if (pending) mutableState.update { it.copy(error = "Request cancelled. Select Retry to continue.") }
    }

    private fun load(block: suspend () -> MovieCatalogState) {
        if (!active || catalog == null) return
        cancel()
        val revision = generation
        mutableState.update { it.copy(loading = true, error = null) }
        request = viewModelScope.launch {
            try {
                val result = block()
                ensureActive()
                if (active && revision == generation) mutableState.value = result.copy(loading = false, error = null)
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
        mutableState.value = MovieCatalogState(query = query, filter = state.value.filter, focusEpoch = state.value.focusEpoch)
    }

    fun setFilter(filter: CatalogFilter) {
        if (state.value.filter == filter) return
        cancel()
        mutableState.value = MovieCatalogState(query = state.value.query, filter = filter, focusEpoch = state.value.focusEpoch)
        if (state.value.query.isNotBlank()) search()
    }

    fun search(page: Int = 1) {
        val client = catalog ?: return
        if (!active || state.value.query.isBlank() || page < 1) return
        cancel()
        mutableState.update {
            it.copy(route = CatalogRoute.RESULTS, page = page, titles = emptyList(), hasMore = false,
                searched = false, selected = null, details = null, season = null, episodes = emptyList(),
                episodesLoaded = false, episode = null, sources = null)
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
                error = null, focusEpoch = it.focusEpoch + 1)
        }
        loadDetails()
    }

    private fun loadDetails() {
        val client = catalog ?: return
        val current = state.value
        val title = current.selected ?: return
        load { current.copy(details = client.details(title)) }
    }

    fun selectSeason(number: Int) {
        cancel()
        mutableState.update {
            it.copy(route = CatalogRoute.EPISODES, season = number, episodes = emptyList(), episodesLoaded = false,
                episode = null, sources = null, error = null, focusEpoch = it.focusEpoch + 1)
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
                error = null, focusEpoch = it.focusEpoch + 1)
        }
        loadSources()
    }

    private fun loadSources() {
        val client = provider ?: return
        val current = state.value
        val details = current.details ?: return
        load { current.copy(sources = client.sources(details, current.episode)) }
    }

    fun retry() {
        when (state.value.route) {
            CatalogRoute.RESULTS -> search(state.value.page)
            CatalogRoute.DETAILS -> loadDetails()
            CatalogRoute.EPISODES -> loadEpisodes()
            CatalogRoute.SOURCES -> loadSources()
        }
    }

    fun back(): Boolean {
        val current = state.value
        cancel()
        val route = when (current.route) {
            CatalogRoute.RESULTS -> return false
            CatalogRoute.DETAILS -> CatalogRoute.RESULTS
            CatalogRoute.EPISODES -> CatalogRoute.DETAILS
            CatalogRoute.SOURCES -> if (current.episode == null) CatalogRoute.DETAILS else CatalogRoute.EPISODES
        }
        mutableState.update { it.copy(route = route, error = null, focusEpoch = it.focusEpoch + 1) }
        return true
    }

    fun selection(source: CatalogSource, expected: CatalogConfiguration): CatalogSelection? {
        val current = state.value
        if (!active || configuration != expected || current.route != CatalogRoute.SOURCES ||
            current.sources?.sources?.contains(source) != true) return null
        return CatalogSelection(current.details ?: return null, current.episode, source)
    }
}

private val CatalogBackground = Color(0xFF10131B)
private val CatalogMuted = Color(0xFFBCC5D7)
private val CatalogAccent = Color(0xFFB8D8FF)

@Composable
fun MovieCatalogScreen(
    configuration: CatalogConfiguration,
    onConfigure: () -> Unit,
    onArchive: () -> Unit,
    onBack: () -> Unit,
    onOpenSource: (CatalogSelection) -> Unit,
) {
    val model: MovieCatalogViewModel = viewModel(factory = remember {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = MovieCatalogViewModel() as T
        }
    })
    val state by model.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val backFocus = remember { FocusRequester() }
    val searchFocus = remember { FocusRequester() }
    val queryFocus = remember { FocusRequester() }
    val resultsScroll = rememberLazyGridState()
    var editingQuery by remember { mutableStateOf(false) }
    var keyboardWasVisible by remember { mutableStateOf(false) }
    var homeFocusPending by remember { mutableStateOf(true) }
    var pendingResultFocus by remember { mutableStateOf<String?>(null) }

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

    LaunchedEffect(state.route, state.focusEpoch) {
        dismissInput()
        pendingResultFocus = null
        homeFocusPending = state.route == CatalogRoute.RESULTS && state.titles.isEmpty()
        if (state.route == CatalogRoute.RESULTS && state.titles.isNotEmpty()) {
            val index = state.titles.indexOfFirst { it.id == state.selected?.id && it.kind == state.selected?.kind }
                .coerceAtLeast(0)
            resultsScroll.scrollToItem(index)
            pendingResultFocus = state.titles[index].identity()
        }
    }

    fun search() {
        dismissInput()
        homeFocusPending = true
        model.search()
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
            if (state.route != CatalogRoute.RESULTS) {
                CatalogButton(
                    when (state.route) {
                        CatalogRoute.RESULTS, CatalogRoute.DETAILS -> "Back to results"
                        CatalogRoute.EPISODES -> "Back to seasons"
                        CatalogRoute.SOURCES -> if (state.episode == null) "Back to details" else "Back to episodes"
                    },
                    Modifier.focusRequester(backFocus), onClick = ::goBack,
                )
                LaunchedEffect(state.route, state.focusEpoch) {
                    withFrameNanos { }
                    backFocus.requestFocus()
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("LumaStream", color = Color.White, fontSize = 25.sp)
                Text("MOVIES & SERIES", color = CatalogAccent, fontSize = 10.sp, letterSpacing = 2.sp)
            }
            CatalogButton("Archive") { leave(onArchive) }
            CatalogButton("Settings") { leave(onConfigure) }
        }
        when (state.route) {
            CatalogRoute.RESULTS -> {
                val welcoming = !state.searched && !state.loading && state.error == null
                Column(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(Brush.horizontalGradient(listOf(Color(0xFF263B57), Color(0xFF192535))))
                        .padding(horizontal = 24.dp, vertical = if (welcoming) 26.dp else 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    if (welcoming) {
                        Text("What will you watch?", color = Color.White, fontSize = 32.sp)
                        Text("Find your next movie or series.", color = CatalogMuted, fontSize = 16.sp)
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
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    cursorColor = CatalogAccent,
                                    focusedBorderColor = CatalogAccent,
                                    unfocusedBorderColor = CatalogMuted,
                                    focusedLabelColor = CatalogAccent,
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
                            modifier = Modifier.fillMaxWidth().height(58.dp).focusRequester(searchFocus),
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
                            if (windowFocused && (homeFocusPending || state.titles.isEmpty())) {
                                withFrameNanos { }
                                searchFocus.requestFocus()
                                homeFocusPending = false
                            }
                        }
                    }
                    if (configuration.token.isBlank()) {
                        Text("Connect TMDB in Settings to start searching.", color = CatalogMuted, fontSize = 14.sp)
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Browse", color = CatalogMuted, fontSize = 13.sp)
                            CatalogFilter.entries.forEach { filter ->
                                CatalogButton(
                                    when (filter) {
                                        CatalogFilter.ALL -> "All"
                                        CatalogFilter.MOVIES -> "Movies"
                                        CatalogFilter.SERIES -> "Series"
                                    },
                                    selected = state.filter == filter,
                                ) {
                                    dismissInput()
                                    homeFocusPending = true
                                    model.setFilter(filter)
                                }
                            }
                        }
                    }
                }
                CatalogStatus(state) {
                    dismissInput()
                    homeFocusPending = true
                    model.retry()
                }
                if (!state.loading && state.error == null && state.searched && state.titles.isEmpty()) {
                    Text("No matching titles. Try another search or filter.", color = CatalogMuted)
                }
                if (state.titles.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Results · Page ${state.page}", color = Color.White, fontSize = 18.sp, modifier = Modifier.weight(1f))
                        Text("TMDB", color = CatalogMuted, fontSize = 12.sp)
                        if (state.page > 1) CatalogButton("Previous page") {
                            homeFocusPending = true
                            model.search(state.page - 1)
                        }
                        if (state.hasMore) CatalogButton("Next page") {
                            homeFocusPending = true
                            model.search(state.page + 1)
                        }
                    }
                    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                        val posterHeight = (maxHeight - 94.dp).coerceIn(60.dp, 220.dp)
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(150.dp),
                            state = resultsScroll,
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            itemsIndexed(state.titles, key = { _, title -> title.identity() }) { _, title ->
                                val requester = remember { FocusRequester() }
                                LaunchedEffect(pendingResultFocus) {
                                    if (pendingResultFocus == title.identity()) {
                                        withFrameNanos { }
                                        requester.requestFocus()
                                        pendingResultFocus = null
                                    }
                                }
                                CatalogTile(Modifier.focusRequester(requester), onClick = { model.select(title) }) {
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
                } else if (welcoming) {
                    Column(
                        Modifier.weight(1f).fillMaxWidth().padding(start = 4.dp, top = 10.dp),
                        verticalArrangement = Arrangement.Bottom,
                    ) {
                        Text("Your next story starts here.", color = CatalogMuted, fontSize = 18.sp)
                        Text("Movie and series metadata by TMDB", color = CatalogMuted, fontSize = 12.sp)
                    }
                }
            }
            CatalogRoute.DETAILS -> CatalogDetailsContent(state, model::retry, model::findSources, model::selectSeason)
            CatalogRoute.EPISODES -> CatalogEpisodesContent(state, model::retry, model::findSources)
            CatalogRoute.SOURCES -> CatalogSourcesContent(state, model::retry) { source ->
                model.selection(source, configuration)?.let { selection -> leave { onOpenSource(selection) } }
            }
        }
    }
}

@Composable
private fun CatalogDetailsContent(
    state: MovieCatalogState,
    retry: () -> Unit,
    sources: (CatalogEpisode?) -> Unit,
    season: (Int) -> Unit,
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
private fun CatalogSourcesContent(state: MovieCatalogState, retry: () -> Unit, select: (CatalogSource) -> Unit) {
    val title = state.details?.title ?: return
    val episode = state.episode
    // Identity remains outside the scrolling source list, including while requests fail.
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            CatalogImage(title.posterPath, CatalogImageType.POSTER, title.title, Modifier.width(54.dp).aspectRatio(2f / 3f))
            Column(Modifier.weight(1f)) {
                Text(title.title, color = Color.White, fontSize = 24.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(title.subtitle() + (episode?.let { " · S${it.season} E${it.number} · ${it.title}" } ?: ""), color = CatalogAccent)
            }
        }
        Text("Torrent sources · Availability is not verified. Only open content you are authorized to share. BitTorrent may upload and exposes your IP to peers.", color = CatalogMuted, fontSize = 13.sp)
        CatalogStatus(state, retry)
        val page = state.sources
        if (page != null && page.sources.isEmpty()) {
            CatalogReadable(if (page.unsupportedCount > 0) {
                "The provider returned ${page.unsupportedCount} unsupported stream(s), but no supported torrent sources for this selection."
            } else {
                "No torrent sources were returned for this selection. This is not a provider error."
            })
            CatalogButton("Refresh sources", onClick = retry)
        }
        if (page != null && page.sources.isNotEmpty()) {
            if (page.unsupportedCount > 0) Text("${page.unsupportedCount} unsupported stream(s) omitted.", color = CatalogMuted, fontSize = 13.sp)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(page.sources) { source ->
                    CatalogTile(Modifier.fillMaxWidth(), onClick = { select(source) }) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text(source.displayName.ifBlank { source.provider }, color = Color.White, fontSize = 18.sp)
                            Text(source.provider, color = CatalogAccent)
                            val metadata = buildList {
                                source.quality?.takeIf { it.isNotBlank() }?.let(::add)
                                if (source.languages.isNotEmpty()) add(source.languages.joinToString(", "))
                                source.sizeBytes?.let { add(formatSourceSize(it)) }
                                source.seeders?.let { add("$it seeders (provider report)") }
                            }
                            if (metadata.isNotEmpty()) Text(metadata.joinToString(" · "), color = CatalogMuted)
                            source.filename?.let { Text(it, color = CatalogMuted, fontSize = 13.sp) }
                            Text("Play", color = CatalogAccent, fontSize = 14.sp)
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
            CatalogRoute.RESULTS -> "Searching TMDB…"
            CatalogRoute.DETAILS -> "Loading title details…"
            CatalogRoute.EPISODES -> "Loading episodes…"
            CatalogRoute.SOURCES -> "Requesting torrent sources…"
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
