package dev.lumastream

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.ImageRequest
import coil.decode.SvgDecoder
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.Dispatcher
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer


private val IMAGE_PATH = Regex("/[A-Za-z0-9_.-]+\\.(jpg|png|webp)")
enum class CatalogImageType(val size: String) { POSTER("w342"), BACKDROP("w780"), STILL("w300") }

private object CatalogImages {
    @Volatile private var loader: ImageLoader? = null

    fun get(context: Context): ImageLoader = loader ?: synchronized(this) {
        loader ?: ImageLoader.Builder(context.applicationContext)
            .memoryCache { MemoryCache.Builder(context.applicationContext).maxSizeBytes(24 * 1024 * 1024).build() }
            .diskCache { DiskCache.Builder().directory(File(context.cacheDir, "catalog-images")).maxSizeBytes(64L * 1024 * 1024).build() }
            .components { add(SvgDecoder.Factory()) }
            .okHttpClient {
                OkHttpClient.Builder()
                    .dispatcher(Dispatcher().apply { maxRequests = 4; maxRequestsPerHost = 4 })
                    .connectTimeout(15, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
                    .callTimeout(30, TimeUnit.SECONDS)
                    .followSslRedirects(false)
                    .addNetworkInterceptor { chain ->
                        val response = chain.proceed(chain.request())
                        val body = response.body ?: return@addNetworkInterceptor response
                        val limit = 4L * 1024 * 1024
                        if (body.contentLength() > limit) {
                            response.close()
                            throw IOException("Image exceeds safety limit")
                        }
                        val bounded = object : ForwardingSource(body.source()) {
                            var total = 0L
                            override fun read(sink: Buffer, byteCount: Long): Long {
                                val count = super.read(sink, minOf(byteCount, limit - total + 1))
                                if (count > 0) total += count
                                if (total > limit) throw IOException("Image exceeds safety limit")
                                return count
                            }
                        }.buffer()
                        response.newBuilder().body(object : ResponseBody() {
                            override fun contentType(): MediaType? = body.contentType()
                            override fun contentLength(): Long = body.contentLength()
                            override fun source(): BufferedSource = bounded
                        }).build()
                    }.build()
            }.build().also { loader = it }
    }
}

@Composable
fun CatalogImage(
    path: String?,
    type: CatalogImageType,
    description: String,
    remoteRequestsEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val url = remember(path, type, remoteRequestsEnabled) {
        path?.takeIf { remoteRequestsEnabled && IMAGE_PATH.matches(it) }
            ?.let { "https://image.tmdb.org/t/p/${type.size}$it" }
    }
    Box(modifier.background(Color(0xFF253043)), contentAlignment = Alignment.Center) {
        Text(description, color = Color(0xFFB8C5D9), modifier = Modifier.padding(12.dp), maxLines = 5, overflow = TextOverflow.Ellipsis)
        if (url != null) {
            val request = remember(context, url) {
                ImageRequest.Builder(context).data(url).crossfade(false).build()
            }
            AsyncImage(
                model = request,
                imageLoader = CatalogImages.get(context),
                contentDescription = description,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
fun TmdbAttributionLogo(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    AsyncImage(
        model = "file:///android_asset/tmdb-logo.svg",
        imageLoader = CatalogImages.get(context),
        contentDescription = "The Movie Database (TMDB)",
        contentScale = ContentScale.Fit,
        modifier = modifier,
    )
}
