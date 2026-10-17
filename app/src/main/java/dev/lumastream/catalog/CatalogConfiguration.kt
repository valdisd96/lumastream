package dev.lumastream.catalog

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.IOException
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val DEFAULT_PROVIDER = "https://torrentio.strem.fun/manifest.json"

data class CatalogConfiguration(val token: String = "", val providerManifestUrl: String = DEFAULT_PROVIDER) {
    override fun toString(): String = "CatalogConfiguration(token=<redacted>, provider=<redacted>)"

    fun validated(): CatalogConfiguration {
        val cleanToken = token.trim()
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
        return copy(token = cleanToken, providerManifestUrl = url)
    }
}

class CatalogConfigurationStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "catalog-config.json"))

    suspend fun load(): CatalogConfiguration = withContext(Dispatchers.IO) {
        if (!file.baseFile.exists()) return@withContext CatalogConfiguration()
        val text = file.openRead().use { input ->
            val bytes = input.readBytesBounded(16 * 1024)
            bytes.toString(Charsets.UTF_8)
        }
        try {
            val json = JSONObject(text)
            CatalogConfiguration(json.optString("token"), json.optString("providerManifestUrl", DEFAULT_PROVIDER)).validated()
        } catch (_: Exception) {
            throw IOException("Catalog configuration could not be read. Open Catalog settings to replace it.")
        }
    }

    suspend fun save(configuration: CatalogConfiguration): CatalogConfiguration = withContext(Dispatchers.IO) {
        val validated = configuration.validated()
        val bytes = JSONObject().put("token", validated.token)
            .put("providerManifestUrl", validated.providerManifestUrl).toString().toByteArray(Charsets.UTF_8)
        val output = file.startWrite()
        try {
            output.write(bytes)
            file.finishWrite(output)
        } catch (failure: Exception) {
            file.failWrite(output)
            throw IOException("Catalog settings could not be saved.", failure)
        }
        validated
    }
}

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
