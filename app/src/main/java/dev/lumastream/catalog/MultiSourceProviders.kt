package dev.lumastream.catalog

import java.net.URI
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

enum class SourceProviderState { LOADING, SUCCESS, ERROR }

data class SourceProviderStatus(
    val id: String,
    val label: String,
    val state: SourceProviderState,
    val sourceCount: Int,
    val error: String? = null,
    val cached: Boolean = false,
)

data class ProviderSourcesSnapshot(
    val page: SourcePage,
    val statuses: List<SourceProviderStatus>,
    val loading: Boolean,
)

internal fun interface ProviderSourceFetcher {
    suspend fun sources(details: CatalogDetails, episode: CatalogEpisode?): SourcePage
}

/** One configuration owns its clients, HTTP cooldowns and bounded, transient successful-page cache. */
class MultiSourceProviders internal constructor(
    configuration: CatalogConfiguration,
    private val clockMillis: () -> Long,
    createFetcher: (String) -> ProviderSourceFetcher,
) {
    constructor(configuration: CatalogConfiguration) : this(
        configuration,
        { System.nanoTime() / 1_000_000L },
        { url ->
            val client = StremioSources(url)
            ProviderSourceFetcher { details, episode -> client.sources(details, episode) }
        },
    )

    private val providers = buildList {
        val primaryUrl = configuration.providerManifestUrl.trim()
        val primaryKey = providerUrlKey(primaryUrl)
        val primaryLabel = when (primaryKey) {
            providerUrlKey(TORRENTIO_MANIFEST) -> "Torrentio"
            providerUrlKey(TORRENTS_DB_MANIFEST) -> "TorrentsDB"
            providerUrlKey(MEDIA_FUSION_MANIFEST) -> "MediaFusion Midnight"
            else -> "Configured provider"
        }
        add(Provider("primary", primaryLabel, createFetcher(primaryUrl)))
        if (configuration.torrentsDbEnabled && primaryKey != providerUrlKey(TORRENTS_DB_MANIFEST)) {
            add(Provider("torrentsdb", "TorrentsDB", createFetcher(TORRENTS_DB_MANIFEST)))
        }
        if (configuration.mediaFusionEnabled && primaryKey != providerUrlKey(MEDIA_FUSION_MANIFEST)) {
            add(Provider("mediafusion", "MediaFusion Midnight", createFetcher(MEDIA_FUSION_MANIFEST)))
        }
    }
    private val labels = providers.associate { it.id to it.label }
    private val requests = Semaphore(3)

    fun sources(details: CatalogDetails, episode: CatalogEpisode?): Flow<ProviderSourcesSnapshot> = channelFlow {
        val key = RequestKey(details.title.id, details.title.kind, details.imdbId, details.title.year,
            details.seasons.map { it.number }, episode?.season, episode?.number)
        val pages = arrayOfNulls<SourcePage>(providers.size)
        val statuses = providers.map { SourceProviderStatus(it.id, it.label, SourceProviderState.LOADING, 0) }.toMutableList()
        val publication = Mutex()
        fun snapshot(): ProviderSourcesSnapshot = ProviderSourcesSnapshot(
            SourcePage(mergeSourceRecords(pages.filterNotNull().flatMap { it.sources }, labels),
                pages.filterNotNull().sumOf { it.unsupportedCount }),
            statuses.toList(),
            statuses.any { it.state == SourceProviderState.LOADING },
        )
        send(snapshot())
        providers.mapIndexed { index, provider ->
            async {
                val cached = provider.cached(key, clockMillis())
                val result = if (cached != null) ProviderResult(cached) else fetch(provider, details, episode)
                currentCoroutineContext().ensureActive()
                if (cached == null && result.page != null) provider.cache(key, result.page, clockMillis())
                publication.withLock {
                    pages[index] = result.page
                    statuses[index] = SourceProviderStatus(provider.id, provider.label,
                        if (result.page == null) SourceProviderState.ERROR else SourceProviderState.SUCCESS,
                        result.page?.sources?.size ?: 0, result.error, cached != null)
                    send(snapshot())
                }
            }
        }.awaitAll()
    }

    private suspend fun fetch(provider: Provider, details: CatalogDetails, episode: CatalogEpisode?): ProviderResult = try {
        val page = withTimeoutOrNull(PROVIDER_DEADLINE_MILLIS) {
            requests.withPermit { provider.fetcher.sources(details, episode) }
        }
        when {
            page == null -> ProviderResult(error = "Source provider timed out after 30 seconds.")
            page.sources.size > MAX_PROVIDER_SOURCES -> ProviderResult(error = "Source provider returned more than 500 entries.")
            else -> ProviderResult(SourcePage(mergeSourceRecords(page.sources.map {
                it.copy(provider = provider.label, providerIds = setOf(provider.id))
            }, labels), page.unsupportedCount.coerceAtLeast(0)))
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: CatalogHttp.SafeFailure) {
        ProviderResult(error = failure.message)
    } catch (_: Exception) {
        // Provider messages, exception causes and URLs may contain credentials. Never expose them.
        ProviderResult(error = "Source provider failed. Check its configuration or try again later.")
    }

    private data class RequestKey(
        val titleId: Int,
        val kind: MediaKind,
        val imdbId: String?,
        val year: String,
        val seasons: List<Int>,
        val season: Int?,
        val episode: Int?,
    )

    private data class CachedPage(val page: SourcePage, val storedAt: Long)
    private data class ProviderResult(val page: SourcePage? = null, val error: String? = null)

    private class Provider(val id: String, val label: String, val fetcher: ProviderSourceFetcher) {
        private val cache = LinkedHashMap<RequestKey, CachedPage>(CACHE_ENTRIES, 0.75f, true)

        fun cached(key: RequestKey, now: Long): SourcePage? = synchronized(cache) {
            val entry = cache[key] ?: return@synchronized null
            if (now - entry.storedAt >= CACHE_TTL_MILLIS || now < entry.storedAt) {
                cache.remove(key)
                null
            } else entry.page
        }

        fun cache(key: RequestKey, page: SourcePage, now: Long) = synchronized(cache) {
            cache[key] = CachedPage(page, now)
            if (cache.size > CACHE_ENTRIES) {
                val oldest = cache.entries.iterator()
                oldest.next()
                oldest.remove()
            }
        }
    }

    private companion object {
        const val TORRENTIO_MANIFEST = "https://torrentio.strem.fun/manifest.json"
        const val TORRENTS_DB_MANIFEST = "https://torrentsdb.com/manifest.json"
        const val MEDIA_FUSION_MANIFEST = "https://mediafusionfortheweebs.midnightignite.me/manifest.json"
        const val PROVIDER_DEADLINE_MILLIS = 30_000L
        const val MAX_PROVIDER_SOURCES = 500
        const val CACHE_ENTRIES = 12
        const val CACHE_TTL_MILLIS = 120_000L
    }
}

private fun providerUrlKey(url: String): String {
    val uri = try { URI(url) } catch (_: Exception) { return url }
    if (uri.host == null || uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return url
    val port = if (uri.port == -1 || uri.port == 443) "" else ":${uri.port}"
    return "${uri.scheme?.lowercase(Locale.ROOT)}://${uri.host.lowercase(Locale.ROOT)}$port${uri.rawPath}"
}

private data class SourceIdentity(val hash: String, val fileIndex: Int?, val unindexedFilename: String?)

/** Null indices prove no file identity: distinct (or missing) filenames must remain separate. */
internal fun mergeSourceRecords(
    sources: List<CatalogSource>,
    providerLabels: Map<String, String> = emptyMap(),
): List<CatalogSource> {
    val merged = linkedMapOf<SourceIdentity, CatalogSource>()
    for (source in sources) {
        val key = SourceIdentity(source.infoHash.trim().lowercase(Locale.ROOT), source.fileIndex,
            source.filename.takeIf { source.fileIndex == null })
        val previous = merged[key]
        if (previous == null) {
            merged[key] = source
            continue
        }
        // Keep one real record's identity and metadata, not a fictional union of conflicting hints.
        val preferred = if (sourceMetadataScore(source) > sourceMetadataScore(previous)) source else previous
        val providerIds = previous.providerIds + source.providerIds
        val provider = providerLabels.filterKeys { it in providerIds }.values.distinct().joinToString(" + ")
        merged[key] = preferred.copy(
            provider = provider.ifEmpty { preferred.provider },
            providerIds = providerIds,
            trackers = if (previous.filename == source.filename) (previous.trackers + source.trackers).distinct()
                else preferred.trackers,
        )
    }
    return merged.values.toList()
}

private fun sourceMetadataScore(source: CatalogSource): Int =
    (if (source.filename != null) 8 else 0) + (if (source.quality != null) 1 else 0) +
        (if (source.sizeBytes != null) 1 else 0) + (if (source.seeders != null) 1 else 0) +
        (if (source.languages.isNotEmpty()) 1 else 0)
