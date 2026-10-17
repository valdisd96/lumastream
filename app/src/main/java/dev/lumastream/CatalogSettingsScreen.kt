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
    var choosingLanguage by remember { mutableStateOf(false) }
    var validationError by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf(false) }
    val tokenFocus = remember { FocusRequester() }
    val providerFocus = remember { FocusRequester() }
    val backFocus = remember { FocusRequester() }
    val languageFocus = remember { FocusRequester() }
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
    BackHandler {
        if (editing) { dismissInput(); backFocus.requestFocus() } else onBack()
    }
    LaunchedEffect(Unit) { backFocus.requestFocus() }
    if (choosingLanguage) {
        val selectedFocus = remember { FocusRequester() }
        val languageList = rememberLazyListState(
            initialFirstVisibleItemIndex = SUPPORTED_CATALOG_LANGUAGES.indexOfFirst { it.code == catalogLanguage }.coerceAtLeast(0),
        )
        fun closeLanguages() {
            choosingLanguage = false
            languageFocus.requestFocus()
        }
        Dialog(onDismissRequest = { closeLanguages() }) {
            Column(
                modifier = Modifier.fillMaxWidth().background(Color(0xFF1B2333)).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("Catalog language", color = Color.White, fontSize = 24.sp)
                Text("Use Up / Down to choose, OK to select, or Back to cancel.", color = Color(0xFFBCC5D7))
                LazyColumn(
                    state = languageList,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(SUPPORTED_CATALOG_LANGUAGES, key = { it.code }) { language ->
                        Button(
                            colors = buttonColors,
                            onClick = {
                                catalogLanguage = language.code
                                closeLanguages()
                            },
                            modifier = Modifier.fillMaxWidth().then(
                                if (language.code == catalogLanguage) Modifier.focusRequester(selectedFocus) else Modifier,
                            ),
                        ) {
                            Text(
                                "${language.label} (${language.code})${if (language.code == catalogLanguage) " — selected" else ""}",
                                color = Color.White,
                            )
                        }
                    }
                }
            }
            LaunchedEffect(Unit) { selectedFocus.requestFocus() }
        }
    }
    LazyColumn(
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
            Text("Choose the language for catalog titles, descriptions and episode information. This does not change playback audio or subtitles.", color = Color(0xFFBCC5D7))
        }
        item {
            Button(
                colors = buttonColors,
                onClick = { dismissInput(); choosingLanguage = true },
                modifier = Modifier.focusRequester(languageFocus),
            ) {
                val language = SUPPORTED_CATALOG_LANGUAGES.first { it.code == catalogLanguage }
                Text("Catalog language: ${language.label}", color = Color.White)
            }
        }
        item {
            Button(colors = buttonColors, enabled = !saving, onClick = {
                dismissInput()
                try {
                    val value = CatalogConfiguration(token, provider, catalogLanguage).validated()
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
