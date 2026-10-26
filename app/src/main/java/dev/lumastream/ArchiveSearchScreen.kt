package dev.lumastream

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.lumastream.catalog.ArchiveCatalog
import dev.lumastream.catalog.ArchiveItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private data class ArchiveSearchState(
    val query: String = "",
    val items: List<ArchiveItem> = emptyList(),
    val page: Int = 0,
    val hasMore: Boolean = false,
    val searched: Boolean = false,
    val selected: ArchiveItem? = null,
    val loading: Boolean = false,
    val opening: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
)

private class ArchiveSearchViewModel : ViewModel() {
    private val catalog = ArchiveCatalog()
    private val mutableState = MutableStateFlow(ArchiveSearchState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    private var generation = 0L
    private var active = false

    fun activate() { active = true }

    private fun cancel() {
        generation++
        job?.cancel()
        job = null
        mutableState.update { it.copy(loading = false, opening = false) }
    }

    fun deactivate() {
        active = false
        val pending = state.value.loading || state.value.opening
        cancel()
        if (pending) mutableState.update { it.copy(notice = "Request cancelled. Search again or retry opening the torrent.") }
    }

    fun setQuery(query: String) {
        if (query == state.value.query) return
        cancel()
        mutableState.value = ArchiveSearchState(query = query)
    }

    fun search(page: Int = 1) {
        val current = state.value
        if (!active || current.query.isBlank() || current.loading || current.opening) return
        if (page < 1) return
        cancel()
        val token = generation
        mutableState.value = if (page != 1) {
            current.copy(loading = true, error = null, notice = null)
        } else {
            ArchiveSearchState(query = current.query, loading = true)
        }
        job = viewModelScope.launch {
            try {
                val result = catalog.search(current.query.trim(), page)
                ensureActive()
                if (!active || token != generation) return@launch
                mutableState.update {
                    it.copy(
                        items = result.items,
                        page = page,
                        hasMore = result.hasMore,
                        searched = true,
                        loading = false,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (active && token == generation) mutableState.update {
                    it.copy(loading = false, error = error.message ?: "Internet Archive search failed. Please try again.")
                }
            }
        }
    }

    fun select(item: ArchiveItem) {
        cancel()
        mutableState.update { it.copy(selected = item, error = null, notice = null) }
    }

    fun showResults() {
        cancel()
        mutableState.update { it.copy(selected = null, error = null, notice = null) }
    }

    fun open(onOpenTorrent: (String) -> Unit) {
        val item = state.value.selected ?: return
        if (!active || state.value.opening) return
        cancel()
        val token = generation
        mutableState.update { it.copy(opening = true, error = null, notice = null) }
        job = viewModelScope.launch {
            try {
                val url = catalog.torrentUrl(item)
                ensureActive()
                if (!active || token != generation) return@launch
                mutableState.update { it.copy(opening = false) }
                onOpenTorrent(url)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (active && token == generation) mutableState.update {
                    it.copy(opening = false, error = error.message ?: "Could not resolve this item's torrent. Please try again.")
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ArchiveSearchScreen(onOpenTorrent: (String) -> Unit, onBack: () -> Unit) {
    val layout = LocalAppLayout.current
    val imeVisible = WindowInsets.isImeVisible
    val model: ArchiveSearchViewModel = viewModel(factory = remember {
        object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ArchiveSearchViewModel() as T
        }
    })
    val state by model.state.collectAsState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val openTorrent by rememberUpdatedState(onOpenTorrent)
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    var inputFocused by remember { mutableStateOf(false) }
    val backFocus = remember { FocusRequester() }
    val resultFocus = remember { FocusRequester() }
    val queryFocus = remember { FocusRequester() }
    var selectedIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    val resultsScroll = rememberLazyListState()
    val detailsScroll = rememberLazyListState()
    val scope = rememberCoroutineScope()

    fun focusNavigation() {
        if (!layout.isTv) return
        scope.launch {
            val scroll = if (state.selected == null) resultsScroll else detailsScroll
            scroll.scrollToItem(1)
            withFrameNanos { }
            backFocus.requestFocus()
        }
    }

    DisposableEffect(model, lifecycle) {
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

    fun dismissKeyboard() {
        keyboard?.hide()
        focusManager.clearFocus(force = true)
        inputFocused = false
    }

    fun goBack() {
        dismissKeyboard()
        if (state.selected != null) model.showResults() else {
            model.deactivate()
            onBack()
        }
    }

    BackHandler {
        if (state.selected == null && inputFocused && (layout.isTv || imeVisible)) {
            dismissKeyboard()
            focusNavigation()
        } else goBack()
    }

    LaunchedEffect(state.selected?.identifier) {
        if (state.selected != null) detailsScroll.scrollToItem(0)
        if (!layout.isTv) return@LaunchedEffect
        dismissKeyboard()
        if (state.selected == null && selectedIndex != null && state.items.isNotEmpty()) {
            // Header, navigation, query, submit and status precede the result rows.
            resultsScroll.scrollToItem(5 + selectedIndex!!.coerceAtMost(state.items.lastIndex))
            withFrameNanos { }
            resultFocus.requestFocus()
        } else {
            // Start at navigation so its focus target is composed even in a short window.
            if (state.selected != null) detailsScroll.scrollToItem(1) else resultsScroll.scrollToItem(1)
            withFrameNanos { }
            backFocus.requestFocus()
        }
    }

    LaunchedEffect(state.page) {
        if (state.selected == null && state.page > 0) {
            selectedIndex = null
            resultsScroll.scrollToItem(if (layout.isTv) 1 else 0)
            if (layout.isTv) {
                withFrameNanos { }
                backFocus.requestFocus()
            }
        }
    }

    val selected = state.selected
    LazyColumn(
        state = if (selected == null) resultsScroll else detailsScroll,
        modifier = Modifier.fillMaxSize().background(Color(0xFF10131B))
            .padding(horizontal = layout.horizontalPadding, vertical = layout.verticalPadding),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text("Internet Archive", color = Color.White, fontSize = 30.sp)
            Text("Source: archive.org · Search video items with torrents", color = Color(0xFFBCC5D7), fontSize = 14.sp)
        }
        item {
            ArchiveButton(
                if (selected == null) "Back to player" else if (state.opening) "Cancel / Back to results" else "Back to results",
                modifier = Modifier.focusRequester(backFocus),
                onClick = ::goBack,
            )
        }
        if (selected == null) {
            item {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = { selectedIndex = null; model.setQuery(it) },
                    modifier = Modifier.fillMaxWidth().focusRequester(queryFocus).onFocusChanged { inputFocused = it.isFocused },
                    label = { Text("Search titles or creators") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        dismissKeyboard()
                        focusNavigation()
                        selectedIndex = null
                        model.search()
                    }),
                )
            }
            item {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ArchiveButton("Edit query") {
                        scope.launch {
                            resultsScroll.scrollToItem(2)
                            withFrameNanos { }
                            queryFocus.requestFocus()
                            keyboard?.show()
                        }
                    }
                    ArchiveButton("Search", enabled = state.query.isNotBlank() && !state.loading) {
                        dismissKeyboard()
                        focusNavigation()
                        selectedIndex = null
                        model.search()
                    }
                    if (state.page > 1) {
                        ArchiveButton("Previous page", enabled = !state.loading) { model.search(state.page - 1) }
                    }
                    if (state.hasMore) {
                        ArchiveButton("Next page", enabled = !state.loading) { model.search(state.page + 1) }
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (state.loading) ArchiveLoading("Searching Internet Archive…")
                    state.error?.let { Text(it, color = Color(0xFFFFA6A6)) }
                    state.notice?.let { Text(it, color = Color(0xFFB8D8FF)) }
                    if (!state.loading && state.error == null) {
                        Text(
                            when {
                                state.searched && state.items.isEmpty() -> "No matching videos. Try another title or creator."
                                state.items.isNotEmpty() -> "Page ${state.page} · ${state.items.size} results · Select an item to review its source and license."
                                else -> "Enter a title or creator to search."
                            },
                            color = Color(0xFFBCC5D7),
                        )
                    }
                }
            }
            itemsIndexed(state.items, key = { _, item -> item.identifier }) { index, item ->
                ArchiveButton(
                    "${item.title}\n${item.creator.ifBlank { "Creator unknown" }}",
                    modifier = Modifier.fillMaxWidth().then(
                        if (index == selectedIndex) Modifier.focusRequester(resultFocus) else Modifier,
                    ),
                ) {
                    dismissKeyboard()
                    selectedIndex = index
                    model.select(item)
                }
            }
        } else {
            item {
                ArchiveButton("Open torrent", enabled = !state.opening) {
                    dismissKeyboard()
                    focusNavigation()
                    model.open { openTorrent(it) }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (state.opening) ArchiveLoading("Resolving torrent metadata…")
                    state.error?.let { Text(it, color = Color(0xFFFFA6A6)) }
                    state.notice?.let { Text(it, color = Color(0xFFB8D8FF)) }
                }
            }
            item { ArchiveDetail(selected.title, heading = true) }
            item { ArchiveDetail("Creator: ${selected.creator.ifBlank { "Unknown" }}") }
            item { ArchiveDetail("Source: https://archive.org/details/${Uri.encode(selected.identifier)}") }
            item {
                ArchiveDetail(
                    if (selected.licenseUrl.isBlank()) {
                        "License unknown: Internet Archive did not provide a license. Availability is not permission to download or share. Verify the rights before opening."
                    } else {
                        "Reported license: ${selected.licenseUrl}\nReview this license and verify that it permits your intended use; catalog metadata is not proof of permission."
                    },
                )
            }
            item {
                ArchiveDetail("Only open content you are authorized to download and share. BitTorrent may upload to peers and exposes your network IP to them. LumaStream does not verify ownership or licensing.")
            }
        }
    }
}

@Composable
private fun ArchiveDetail(text: String, heading: Boolean = false) {
    val isTv = LocalAppLayout.current.isTv
    // Focusable metadata lets a TV remote scroll through long attribution and license text.
    var focused by remember { mutableStateOf(false) }
    Text(
        text,
        color = Color.White,
        fontSize = if (heading) 22.sp else 15.sp,
        modifier = Modifier.fillMaxWidth().background(if (focused) Color(0xFF283246) else Color.Transparent)
            .onFocusChanged { focused = it.isFocused }.focusable(enabled = isTv).padding(8.dp),
    )
}

@Composable
private fun ArchiveLoading(label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
        Text(label, color = Color.White)
    }
}

@Composable
private fun ArchiveButton(label: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    AppButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
    ) {
        Text(label, color = Color.White, fontSize = 15.sp)
    }
}
