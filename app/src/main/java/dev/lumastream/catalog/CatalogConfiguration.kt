package dev.lumastream.catalog

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.IOException
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

private const val DEFAULT_PROVIDER = "https://torrentio.strem.fun/manifest.json"

const val DEFAULT_CATALOG_LANGUAGE = "en-US"

const val DEFAULT_TORRENT_CACHE_GIB = 4
val SUPPORTED_TORRENT_CACHE_GIB: List<Int> = listOf(1, 2, 4, 8)

data class CatalogLanguage(val code: String, val label: String)

val SUPPORTED_CATALOG_LANGUAGES: List<CatalogLanguage> = listOf(
    CatalogLanguage(DEFAULT_CATALOG_LANGUAGE, "English"),
    CatalogLanguage("ru-RU", "Russian"),
    CatalogLanguage("uk-UA", "Ukrainian"),
    CatalogLanguage("de-DE", "German"),
    CatalogLanguage("fr-FR", "French"),
    CatalogLanguage("es-ES", "Spanish"),
    CatalogLanguage("it-IT", "Italian"),
    CatalogLanguage("pt-BR", "Portuguese (Brazil)"),
    CatalogLanguage("ja-JP", "Japanese"),
    CatalogLanguage("ko-KR", "Korean"),
    CatalogLanguage("zh-CN", "Chinese (Simplified)"),
)

const val DEFAULT_SUBTITLE_LANGUAGE = "en"
const val ORIGINAL_AUDIO_LANGUAGE = "original"

val SUPPORTED_SUBTITLE_LANGUAGES: List<CatalogLanguage> = SUPPORTED_CATALOG_LANGUAGES.map {
    CatalogLanguage(it.code.substringBefore('-'), it.label.substringBefore(" ("))
}
val SUPPORTED_AUDIO_LANGUAGES: List<CatalogLanguage> =
    listOf(CatalogLanguage(ORIGINAL_AUDIO_LANGUAGE, "Original")) + SUPPORTED_SUBTITLE_LANGUAGES

data class CatalogConfiguration(
    val token: String = "",
    val providerManifestUrl: String = DEFAULT_PROVIDER,
    val catalogLanguage: String = DEFAULT_CATALOG_LANGUAGE,
    val subtitleLanguage: String = DEFAULT_SUBTITLE_LANGUAGE,
    val audioLanguage: String = ORIGINAL_AUDIO_LANGUAGE,
    val openSubtitlesApiKey: String = "",
    val torrentsDbEnabled: Boolean = false,
    val mediaFusionEnabled: Boolean = false,
    val pausePrefetchEnabled: Boolean = false,
    val torrentCacheGiB: Int = DEFAULT_TORRENT_CACHE_GIB,
    val catalogEnabled: Boolean = false,
    val onlineSubtitlesEnabled: Boolean = false,
) {
    val isCatalogConfigured: Boolean get() = catalogEnabled && token.isNotBlank()
    val isOnlineSubtitlesConfigured: Boolean get() = onlineSubtitlesEnabled && openSubtitlesApiKey.isNotBlank()

    override fun toString(): String = "CatalogConfiguration(token=<redacted>, provider=<redacted>, openSubtitlesApiKey=<redacted>)"

    fun validated(): CatalogConfiguration {
        require(torrentCacheGiB in SUPPORTED_TORRENT_CACHE_GIB) {
            "Select a torrent disk cache size of 1, 2, 4 or 8 GiB."
        }
        require(SUPPORTED_CATALOG_LANGUAGES.any { it.code == catalogLanguage }) {
            "Select a supported catalog language."
        }
        require(SUPPORTED_SUBTITLE_LANGUAGES.any { it.code == subtitleLanguage }) { "Select a supported subtitle language." }
        require(SUPPORTED_AUDIO_LANGUAGES.any { it.code == audioLanguage }) { "Select a supported audio preference." }
        val cleanSubtitleKey = openSubtitlesApiKey.trim()
        require(!onlineSubtitlesEnabled || cleanSubtitleKey.isNotBlank()) {
            "Enter the OpenSubtitles application consumer key supplied by your app provider, or set Online subtitles to Off."
        }
        require(cleanSubtitleKey.length <= 512 && cleanSubtitleKey.all { it in '!'..'~' }) {
            "Enter an OpenSubtitles consumer API key without spaces, or leave it empty to remove it."
        }
        val cleanToken = token.trim()
        require(!catalogEnabled || cleanToken.isNotBlank()) {
            "Enter your TMDB API Read Access Token, or set Catalog to Off."
        }
        require(cleanToken.length <= 4096 && cleanToken.none { it.isWhitespace() || it.isISOControl() }) {
            "Enter a TMDB API Read Access Token without spaces, or leave it empty to remove it."
        }
        val url = providerManifestUrl.trim()
        val uri = runCatching { URI(url) }.getOrNull()
        require(url.length <= 4096 && uri != null && uri.scheme == "https" &&
            !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null &&
            uri.query == null && uri.path.endsWith("/manifest.json")) {
            "Enter an HTTPS Stremio manifest URL ending in /manifest.json, without a query or fragment."
        }
        return copy(token = cleanToken, providerManifestUrl = url, openSubtitlesApiKey = cleanSubtitleKey)
    }
}

class CatalogConfigurationStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "catalog-config.json"))

    suspend fun load(): CatalogConfiguration = withContext(Dispatchers.IO) {
        storageLock.withLock {
            if (!file.baseFile.exists()) return@withLock CatalogConfiguration()
            try {
                val text = file.openRead().use { input ->
                    input.readBytesBounded(64 * 1024).toString(Charsets.UTF_8)
                }
                decodeCatalogConfiguration(text)
            } catch (_: Exception) {
                throw IOException("Catalog configuration could not be read. Open Catalog settings to replace it.")
            }
        }
    }

    suspend fun save(configuration: CatalogConfiguration): CatalogConfiguration = withContext(Dispatchers.IO) {
        storageLock.withLock {
            val validated = configuration.validated()
            val bytes = encodeCatalogConfiguration(validated).toByteArray(Charsets.UTF_8)
            val output = try {
                file.startWrite()
            } catch (_: IOException) {
                throw IOException("Catalog settings could not be saved.")
            }
            try {
                output.write(bytes)
                file.finishWrite(output)
            } catch (_: Exception) {
                file.failWrite(output)
                throw IOException("Catalog settings could not be saved.")
            }
            validated
        }
    }

    private companion object {
        val storageLock = Mutex()
    }
}

internal fun decodeCatalogConfiguration(text: String): CatalogConfiguration {
    val json = JSONObject(text)
    val cacheGiB = if (json.has("torrentCacheGiB")) {
        val value = json.get("torrentCacheGiB")
        require(value is Int) { "Select a torrent disk cache size of 1, 2, 4 or 8 GiB." }
        value
    } else {
        DEFAULT_TORRENT_CACHE_GIB
    }
    val token = json.optString("token")
    val subtitleKey = json.optString("openSubtitlesApiKey")
    return CatalogConfiguration(
        token = token,
        providerManifestUrl = json.optString("providerManifestUrl", DEFAULT_PROVIDER),
        catalogLanguage = json.optString("catalogLanguage", DEFAULT_CATALOG_LANGUAGE),
        subtitleLanguage = json.optString("subtitleLanguage", DEFAULT_SUBTITLE_LANGUAGE),
        audioLanguage = json.optString("audioLanguage", ORIGINAL_AUDIO_LANGUAGE),
        openSubtitlesApiKey = subtitleKey,
        catalogEnabled = if (json.has("catalogEnabled")) json.getBoolean("catalogEnabled") else token.isNotBlank(),
        onlineSubtitlesEnabled = if (json.has("onlineSubtitlesEnabled")) {
            json.getBoolean("onlineSubtitlesEnabled")
        } else subtitleKey.isNotBlank(),
        torrentsDbEnabled = json.optBoolean("torrentsDbEnabled", false),
        mediaFusionEnabled = json.optBoolean("mediaFusionEnabled", false),
        pausePrefetchEnabled = json.optBoolean("pausePrefetchEnabled", false),
        torrentCacheGiB = cacheGiB,
    ).validated()
}

internal fun encodeCatalogConfiguration(configuration: CatalogConfiguration): String = JSONObject()
    .put("token", configuration.token)
    .put("catalogEnabled", configuration.catalogEnabled)
    .put("onlineSubtitlesEnabled", configuration.onlineSubtitlesEnabled)
    .put("providerManifestUrl", configuration.providerManifestUrl)
    .put("catalogLanguage", configuration.catalogLanguage)
    .put("subtitleLanguage", configuration.subtitleLanguage)
    .put("audioLanguage", configuration.audioLanguage)
    .put("openSubtitlesApiKey", configuration.openSubtitlesApiKey)
    .put("torrentsDbEnabled", configuration.torrentsDbEnabled)
    .put("mediaFusionEnabled", configuration.mediaFusionEnabled)
    .put("pausePrefetchEnabled", configuration.pausePrefetchEnabled)
    .put("torrentCacheGiB", configuration.torrentCacheGiB)
    .toString()

private fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(1024)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        if (output.size() + count > limit) throw IOException("Catalog configuration is too large.")
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
