package dev.lumastream

import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun OpenTorrentScreen(onOpen: (Uri) -> Unit, onBack: () -> Unit) {
    val layout = LocalAppLayout.current
    var link by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val backFocus = remember { FocusRequester() }
    val openFocus = remember { FocusRequester() }
    var editing by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onOpen(uri)
    }
    fun openLink() {
        val uri = Uri.parse(link.trim())
        if (uri.scheme != "magnet" && (uri.scheme != "https" || uri.host.isNullOrBlank())) {
            error = "Enter a magnet link or an HTTPS URL to a .torrent file."
            return
        }
        keyboard?.hide()
        onOpen(uri)
    }
    BackHandler {
        if (layout.isTv && editing) {
            keyboard?.hide()
            focus.clearFocus(force = true)
            editing = false
            if (link.isNotBlank()) openFocus.requestFocus() else backFocus.requestFocus()
        } else onBack()
    }
    LaunchedEffect(Unit) { if (layout.isTv) backFocus.requestFocus() }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = layout.horizontalPadding, vertical = layout.verticalPadding),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        AppButton(onClick = onBack, modifier = Modifier.focusRequester(backFocus)) { Text("Back to Home") }
        Text("Open magnet / torrent", color = Color.White, fontSize = 26.sp)
        Text("No account or catalog key is needed. Only open content you are authorized to obtain and share. BitTorrent can upload to peers, who can see your IP address.", color = Color(0xFFBCC5D7))
        OutlinedTextField(
            value = link,
            onValueChange = { link = it; error = null },
            label = { Text("Magnet link or HTTPS torrent URL") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) editing = true },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { openLink() }),
        )
        AppButton(onClick = { openLink() }, enabled = link.isNotBlank(),
            modifier = Modifier.focusRequester(openFocus)) { Text("Open link") }
        AppButton(onClick = {
            try {
                picker.launch(arrayOf("*/*"))
            } catch (_: ActivityNotFoundException) {
                error = "This device has no document picker. Use a magnet / HTTPS link, open a torrent from a file manager, or install a document picker."
            }
        }) { Text("Choose .torrent file") }
        Text("Choose a .torrent document, not the downloaded video. A document picker is required for file selection.", color = Color(0xFFBCC5D7))
        error?.let { Text(it, color = Color(0xFFFFB4AB)) }
    }
}
