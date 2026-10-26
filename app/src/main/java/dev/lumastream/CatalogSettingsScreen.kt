package dev.lumastream

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import dev.lumastream.catalog.CatalogConfiguration
import dev.lumastream.catalog.SUPPORTED_CATALOG_LANGUAGES
import dev.lumastream.catalog.SUPPORTED_SUBTITLE_LANGUAGES
import dev.lumastream.catalog.SUPPORTED_AUDIO_LANGUAGES
import dev.lumastream.catalog.SUPPORTED_TORRENT_CACHE_GIB

@Composable
fun CatalogSettingsScreen(
    configuration: CatalogConfiguration,
    saving: Boolean,
    error: String?,
    onSave: (CatalogConfiguration) -> Unit,
    onBack: () -> Unit,
) {
    var token by remember(configuration) { mutableStateOf(configuration.token) }
    var provider by remember(configuration) { mutableStateOf(configuration.providerManifestUrl) }
    var catalogLanguage by remember(configuration) { mutableStateOf(configuration.catalogLanguage) }
    var subtitleLanguage by remember(configuration) { mutableStateOf(configuration.subtitleLanguage) }
    var audioLanguage by remember(configuration) { mutableStateOf(configuration.audioLanguage) }
    var subtitleKey by remember(configuration) { mutableStateOf(configuration.openSubtitlesApiKey) }
    var torrentsDbEnabled by remember(configuration) { mutableStateOf(configuration.torrentsDbEnabled) }
    var mediaFusionEnabled by remember(configuration) { mutableStateOf(configuration.mediaFusionEnabled) }
    var pausePrefetchEnabled by remember(configuration) { mutableStateOf(configuration.pausePrefetchEnabled) }
    var torrentCacheGiB by remember(configuration) { mutableStateOf(configuration.torrentCacheGiB) }
    var choosingCacheSize by remember { mutableStateOf(false) }
    var choosingLanguage by remember { mutableStateOf<String?>(null) }
    var validationError by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf(false) }
    val tokenFocus = remember { FocusRequester() }
    val providerFocus = remember { FocusRequester() }
    val backFocus = remember { FocusRequester() }
    val languageFocus = remember { FocusRequester() }
    val subtitleLanguageFocus = remember { FocusRequester() }
    val audioLanguageFocus = remember { FocusRequester() }
    val subtitleKeyFocus = remember { FocusRequester() }
    val cacheSizeFocus = remember { FocusRequester() }
    val settingsScroll = rememberLazyListState()
    var dialogItemIndex by remember { mutableStateOf(0) }
    var restoreDialogFocus by remember { mutableStateOf<FocusRequester?>(null) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val buttonColors = ButtonDefaults.colors(
        containerColor = Color(0xFF283246), contentColor = Color.White,
        focusedContainerColor = Color(0xFF44638A), focusedContentColor = Color.White,
    )
    fun dismissInput() {
        keyboard?.hide()
        focus.clearFocus(force = true)
        editing = false
    }
    fun prepareDialog(itemIndex: Int) {
        dialogItemIndex = itemIndex
        dismissInput()
    }
    LaunchedEffect(restoreDialogFocus) {
        val requester = restoreDialogFocus ?: return@LaunchedEffect
        // Clearing focus can dispose the opener in the lazy list while the dialog is open.
        settingsScroll.scrollToItem(dialogItemIndex)
        withFrameNanos { }
        requester.requestFocus()
        restoreDialogFocus = null
    }
    BackHandler {
        if (editing) { dismissInput(); backFocus.requestFocus() } else onBack()
    }
    LaunchedEffect(Unit) { backFocus.requestFocus() }
    if (choosingLanguage != null) {
        val options = when (choosingLanguage) {
            "Subtitles" -> SUPPORTED_SUBTITLE_LANGUAGES
            "Audio" -> SUPPORTED_AUDIO_LANGUAGES
            else -> SUPPORTED_CATALOG_LANGUAGES
        }
        val selectedLanguage = when (choosingLanguage) {
            "Subtitles" -> subtitleLanguage
            "Audio" -> audioLanguage
            else -> catalogLanguage
        }
        val selectedFocus = remember { FocusRequester() }
        val languageList = rememberLazyListState(
            initialFirstVisibleItemIndex = options.indexOfFirst { it.code == selectedLanguage }.coerceAtLeast(0),
        )
        fun closeLanguages() {
            val requester = when (choosingLanguage) {
                "Subtitles" -> subtitleLanguageFocus
                "Audio" -> audioLanguageFocus
                else -> languageFocus
            }
            choosingLanguage = null
            restoreDialogFocus = requester
        }
        Dialog(onDismissRequest = { closeLanguages() }) {
            Column(
                modifier = Modifier.fillMaxWidth().background(Color(0xFF1B2333)).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("${choosingLanguage} language", color = Color.White, fontSize = 24.sp)
                Text("Use Up / Down to choose, OK to select, or Back to cancel.", color = Color(0xFFBCC5D7))
                LazyColumn(
                    state = languageList,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(options, key = { it.code }) { language ->
                        Button(
                            colors = buttonColors,
                            onClick = {
                                when (choosingLanguage) {
                                    "Subtitles" -> subtitleLanguage = language.code
                                    "Audio" -> audioLanguage = language.code
                                    else -> catalogLanguage = language.code
                                }
                                closeLanguages()
                            },
                            modifier = Modifier.fillMaxWidth().then(
                                if (language.code == selectedLanguage) Modifier.focusRequester(selectedFocus) else Modifier,
                            ),
                        ) {
                            Text(
                                "${language.label} (${language.code})${if (language.code == selectedLanguage) " — selected" else ""}",
                                color = Color.White,
                            )
                        }
                    }
                }
            }
            LaunchedEffect(Unit) { selectedFocus.requestFocus() }
        }
    }
    if (choosingCacheSize) {
        val selectedFocus = remember { FocusRequester() }
        fun closeCacheSizes() {
            choosingCacheSize = false
            restoreDialogFocus = cacheSizeFocus
        }
        Dialog(onDismissRequest = { closeCacheSizes() }) {
            Column(
                modifier = Modifier.fillMaxWidth().background(Color(0xFF1B2333)).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("Torrent disk cache", color = Color.White, fontSize = 24.sp)
                Text("Use Up / Down to choose, OK to select, or Back to cancel.", color = Color(0xFFBCC5D7))
                SUPPORTED_TORRENT_CACHE_GIB.forEach { size ->
                    Button(
                        colors = buttonColors,
                        onClick = {
                            torrentCacheGiB = size
                            closeCacheSizes()
                        },
                        modifier = Modifier.fillMaxWidth().then(
                            if (size == torrentCacheGiB) Modifier.focusRequester(selectedFocus) else Modifier,
                        ),
                    ) {
                        Text("$size GiB${if (size == torrentCacheGiB) " — selected" else ""}", color = Color.White)
                    }
                }
            }
            LaunchedEffect(Unit) { selectedFocus.requestFocus() }
        }
    }
    LazyColumn(
        state = settingsScroll,
        modifier = Modifier.fillMaxSize().background(Color(0xFF10131B)).padding(36.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { Text("Catalog settings", color = Color.White, fontSize = 28.sp) }
        item {
            Button(colors = buttonColors, onClick = { dismissInput(); onBack() }, modifier = Modifier.focusRequester(backFocus)) {
                Text("Back to catalog", color = Color.White)
            }
        }
        item {
            Text("Additional source providers (optional)", color = Color.White, fontSize = 20.sp)
            Text("Your configured primary provider remains active. Enable additional services below with OK; Save applies your choices. Back leaves settings unchanged.", color = Color(0xFFBCC5D7))
        }
        item {
            Button(
                colors = buttonColors,
                enabled = !saving,
                onClick = { torrentsDbEnabled = !torrentsDbEnabled },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("TorrentsDB: ${if (torrentsDbEnabled) "On" else "Off"} · torrentsdb.com", color = Color.White)
            }
        }
        item {
            Button(
                colors = buttonColors,
                enabled = !saving,
                onClick = { mediaFusionEnabled = !mediaFusionEnabled },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column {
                    Text("MediaFusion Midnight: ${if (mediaFusionEnabled) "On" else "Off"}", color = Color.White)
                    Text("mediafusionfortheweebs.midnightignite.me", color = Color(0xFFBCC5D7), fontSize = 13.sp)
                }
            }
        }
        item {
            Text("Enabled services receive the selected title/episode ID and see your network IP when sources are requested. Disabled additional services receive no requests. Only use content you are authorized to download and share. Results do not guarantee availability, playback, ownership or permission.", color = Color(0xFFBCC5D7))
        }
        item {
            Text("Torrent cache and pause prefetch", color = Color.White, fontSize = 20.sp)
            Text("When enabled, explicitly pausing playback downloads ahead of the video reader until the available disk cache is full. Save applies your choices; Back discards changes.", color = Color(0xFFBCC5D7))
        }
        item {
            Button(
                colors = buttonColors,
                enabled = !saving,
                onClick = { pausePrefetchEnabled = !pausePrefetchEnabled },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Prefetch while paused: ${if (pausePrefetchEnabled) "On" else "Off"}", color = Color.White)
            }
        }
        item {
            Button(
                colors = buttonColors,
                enabled = !saving,
                onClick = { prepareDialog(itemIndex = 8); choosingCacheSize = true },
                modifier = Modifier.fillMaxWidth().focusRequester(cacheSizeFocus),
            ) {
                Text("Torrent disk cache: $torrentCacheGiB GiB", color = Color.White)
            }
        }
        item {
            Text("Keeps 1 GiB of storage free. The actual cache limit can be lower when less disk space is available. Cache size changes apply to the next opened source.", color = Color(0xFFBCC5D7))
        }
        item {
            Text("Choose the language for catalog titles, descriptions and episode information. This does not change playback audio or subtitles.", color = Color(0xFFBCC5D7))
        }
        item {
            Button(
                colors = buttonColors,
                onClick = { prepareDialog(itemIndex = 11); choosingLanguage = "Catalog" },
                modifier = Modifier.focusRequester(languageFocus),
            ) {
                val language = SUPPORTED_CATALOG_LANGUAGES.first { it.code == catalogLanguage }
                Text("Catalog language: ${language.label}", color = Color.White)
            }
        }
        item {
            Text("Subtitles use only your selected language, with no automatic fallback. Original audio uses the title's known original language; when unknown, the source default is kept.", color = Color(0xFFBCC5D7))
        }
        item {
            Button(
                colors = buttonColors,
                onClick = { prepareDialog(itemIndex = 13); choosingLanguage = "Subtitles" },
                modifier = Modifier.focusRequester(subtitleLanguageFocus),
            ) {
                Text("Subtitle language: ${SUPPORTED_SUBTITLE_LANGUAGES.first { it.code == subtitleLanguage }.label} only", color = Color.White)
            }
        }
        item {
            Button(
                colors = buttonColors,
                onClick = { prepareDialog(itemIndex = 14); choosingLanguage = "Audio" },
                modifier = Modifier.focusRequester(audioLanguageFocus),
            ) {
                Text("Audio preference: ${SUPPORTED_AUDIO_LANGUAGES.first { it.code == audioLanguage }.label}", color = Color.White)
            }
        }
        item {
            Button(colors = buttonColors, enabled = !saving, onClick = {
                dismissInput()
                try {
                    val value = configuration.copy(
                        token = token, providerManifestUrl = provider, catalogLanguage = catalogLanguage,
                        subtitleLanguage = subtitleLanguage, audioLanguage = audioLanguage,
                        openSubtitlesApiKey = subtitleKey,
                        torrentsDbEnabled = torrentsDbEnabled, mediaFusionEnabled = mediaFusionEnabled,
                        pausePrefetchEnabled = pausePrefetchEnabled, torrentCacheGiB = torrentCacheGiB,
                    ).validated()
                    validationError = null
                    onSave(value)
                } catch (failure: IllegalArgumentException) {
                    validationError = failure.message
                }
            }) { Text(if (saving) "Saving…" else "Save settings", color = Color.White) }
            (validationError ?: error)?.let { Text(it, color = Color(0xFFFFA6A6)) }
        }
        item {
            Text("TMDB provides film and series information, not playable video. Enter your API Read Access Token from your TMDB account. Credentials are stored only in this app's private storage, never in the APK or logs.", color = Color(0xFFBCC5D7))
        }
        item {
            OutlinedTextField(
                value = token, onValueChange = { if (it.length <= 4096) token = it },
                label = { Text("TMDB API Read Access Token") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth().focusRequester(tokenFocus).onFocusChanged { editing = it.isFocused },
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Button(colors = buttonColors, onClick = { tokenFocus.requestFocus(); keyboard?.show() }) { Text("Edit token", color = Color.White) }
                Button(colors = buttonColors, onClick = { token = "" }) { Text("Clear token", color = Color.White) }
            }
        }
        item {
            Text("OpenSubtitles search runs only when you press the search button in the subtitle menu. Private developer setup: configure LumaStream's application consumer key with anonymous downloads enabled, not a separate key for each viewer. No OpenSubtitles account password is needed. The key stays in app-private storage. Search sends the title, episode and release information to OpenSubtitles, which also sees your network IP. Provider quotas, availability and terms apply; a matching result does not guarantee correct timing or permission to use it.", color = Color(0xFFBCC5D7))
        }
        item {
            OutlinedTextField(
                value = subtitleKey, onValueChange = { if (it.length <= 512) subtitleKey = it },
                label = { Text("LumaStream consumer key (developer setup)") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth().focusRequester(subtitleKeyFocus).onFocusChanged { editing = it.isFocused },
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Button(colors = buttonColors, onClick = { subtitleKeyFocus.requestFocus(); keyboard?.show() }) { Text("Edit subtitle key", color = Color.White) }
                Button(colors = buttonColors, onClick = { subtitleKey = "" }) { Text("Clear subtitle key", color = Color.White) }
            }
        }
        item {
            Text("Source provider: HTTPS Stremio manifest URL. Torrentio is the initial provider. Requests send the selected IMDb title/episode ID and expose your network IP to that service. Returned torrents are third-party suggestions, not verified ownership, availability or permission. Only open content you are authorized to download and share.", color = Color(0xFFBCC5D7))
        }
        item {
            OutlinedTextField(
                value = provider, onValueChange = { if (it.length <= 4096) provider = it },
                label = { Text("Provider manifest URL (hidden: may contain credentials)") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth().focusRequester(providerFocus).onFocusChanged { editing = it.isFocused },
            )
        }
        item { Button(colors = buttonColors, onClick = { providerFocus.requestFocus(); keyboard?.show() }) { Text("Edit provider", color = Color.White) } }
        item {
            TmdbAttributionLogo(Modifier.width(160.dp).height(28.dp))
            Text("This product uses the TMDB API but is not endorsed or certified by TMDB.", color = Color(0xFFBCC5D7))
            Text("TMDB: https://www.themoviedb.org · https://www.themoviedb.org/api-terms-of-use", color = Color(0xFFBCC5D7), fontSize = 13.sp)
            Text("Stremio protocol compatibility does not imply endorsement by Stremio or a provider. Catalog images use a 24 MiB memory / 64 MiB disk cache. Direct video/debrid, DRM and external-app sources are not supported by this torrent-source flow.", color = Color(0xFFBCC5D7), fontSize = 13.sp)
        }
    }
}
