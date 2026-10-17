package dev.lumastream

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.tv.material3.Button
import dev.lumastream.catalog.CatalogConfiguration

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
    var validationError by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf(false) }
    val tokenFocus = remember { FocusRequester() }
    val providerFocus = remember { FocusRequester() }
    val backFocus = remember { FocusRequester() }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    fun dismissInput() {
        keyboard?.hide()
        focus.clearFocus(force = true)
        editing = false
    }
    BackHandler {
        if (editing) { dismissInput(); backFocus.requestFocus() } else onBack()
    }
    LaunchedEffect(Unit) { backFocus.requestFocus() }
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(Color(0xFF10131B)).padding(36.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { Text("Catalog settings", color = Color.White, fontSize = 28.sp) }
        item {
            Button(onClick = { dismissInput(); onBack() }, modifier = Modifier.focusRequester(backFocus)) {
                Text("Back to catalog", color = Color.White)
            }
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
                Button(onClick = { tokenFocus.requestFocus(); keyboard?.show() }) { Text("Edit token", color = Color.White) }
                Button(onClick = { token = "" }) { Text("Clear token", color = Color.White) }
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
        item { Button(onClick = { providerFocus.requestFocus(); keyboard?.show() }) { Text("Edit provider", color = Color.White) } }
        item {
            Button(enabled = !saving, onClick = {
                dismissInput()
                try {
                    val value = CatalogConfiguration(token, provider).validated()
                    validationError = null
                    onSave(value)
                } catch (failure: IllegalArgumentException) {
                    validationError = failure.message
                }
            }) { Text(if (saving) "Saving…" else "Save settings", color = Color.White) }
            (validationError ?: error)?.let { Text(it, color = Color(0xFFFFA6A6)) }
        }
        item {
            TmdbAttributionLogo(Modifier.width(160.dp).height(28.dp))
            Text("This product uses the TMDB API but is not endorsed or certified by TMDB.", color = Color(0xFFBCC5D7))
            Text("TMDB: https://www.themoviedb.org · https://www.themoviedb.org/api-terms-of-use", color = Color(0xFFBCC5D7), fontSize = 13.sp)
            Text("Stremio protocol compatibility does not imply endorsement by Stremio or a provider. Catalog images use a 24 MiB memory / 64 MiB disk cache. Direct video/debrid, DRM and external-app sources are not supported by this torrent-source flow.", color = Color(0xFFBCC5D7), fontSize = 13.sp)
        }
    }
}
