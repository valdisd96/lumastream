package dev.lumastream

import android.content.Context
import android.net.Uri
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.subrip.SubripParser
import androidx.media3.extractor.text.webvtt.WebvttParser
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive

internal data class ImportedSubtitle(val bytes: ByteArray, val mimeType: String)

@androidx.annotation.OptIn(UnstableApi::class)
internal suspend fun importSubtitle(context: Context, uri: Uri): ImportedSubtitle = withContext(Dispatchers.IO) {
    require(uri.scheme == "content" || uri.scheme == "https") { "Choose a document or enter an HTTPS subtitle URL." }
    val connection = if (uri.scheme == "https") {
        (java.net.URL(uri.toString()).openConnection() as javax.net.ssl.HttpsURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            instanceFollowRedirects = false
        }
    } else null
    val bytes = try {
        if (connection != null) require(connection.responseCode == 200) { "Subtitle server returned HTTP ${connection.responseCode}. Use a direct file URL." }
        (connection?.inputStream ?: context.contentResolver.openInputStream(uri))?.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            val deadline = System.nanoTime() + 30_000_000_000L
            while (true) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                require(System.nanoTime() < deadline) { "Subtitle import timed out." }
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= 2 * 1024 * 1024) { "Subtitles must be smaller than 2 MiB." }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } ?: error("Unable to read the subtitle document.")
    } finally {
        connection?.disconnect()
    }
    parseSubtitle(bytes)
}

@androidx.annotation.OptIn(UnstableApi::class)
internal fun parseSubtitle(bytes: ByteArray): ImportedSubtitle {
    require(bytes.isNotEmpty() && bytes.size <= 2 * 1024 * 1024) { "Subtitles must be nonempty and smaller than 2 MiB." }
    val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
    val vtt = text.startsWith("WEBVTT")
    require(vtt || Regex("(?m)^\\s*\\d+\\s*\\r?\\n\\d{2}:\\d{2}:\\d{2},\\d{3} --> ").containsMatchIn(text)) {
        "Choose UTF-8 SRT or WebVTT subtitles."
    }
    val parser = if (vtt) WebvttParser() else SubripParser()
    val subtitle = parser.parseToLegacySubtitle(bytes, 0, bytes.size)
    require(subtitle.eventTimeCount > 0) { "The subtitle document contains no cues." }
    return ImportedSubtitle(bytes, if (vtt) MimeTypes.TEXT_VTT else MimeTypes.APPLICATION_SUBRIP)
}
