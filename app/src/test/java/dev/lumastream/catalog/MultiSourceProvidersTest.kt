package dev.lumastream.catalog

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MultiSourceProvidersTest {
    private val allEnabled = CatalogConfiguration(torrentsDbEnabled = true, mediaFusionEnabled = true)

    private fun details(id: Int = 1) = CatalogDetails(
        CatalogTitle(id, MediaKind.MOVIE, "Movie", "Movie", "2024", "", null, null),
        "tt${id.toString().padStart(7, '0')}", emptyList(), 100,
    )

    private fun source(hash: String, index: Int? = 0, filename: String? = "Movie.2024.mkv") = CatalogSource(
        "Untrusted remote name", hash, index, filename, hash, "", "1080p", listOf("en"),
        100L, 10, emptyList(),
    )

    private fun page(hash: String) = SourcePage(listOf(source(hash)), 0)

    @Test fun publishesFastResultsWhileOtherProvidersWaitAndMergesInConfiguredOrder() = runTest {
        val gates = List(3) { CompletableDeferred<SourcePage>() }
        val started = mutableListOf<Int>()
        var client = 0
        val providers = MultiSourceProviders(allEnabled, { testScheduler.currentTime }) {
            val index = client++
            ProviderSourceFetcher { _, _ -> started.add(index); gates[index].await() }
        }
        val snapshots = mutableListOf<ProviderSourcesSnapshot>()
        val collecting = launch { providers.sources(details(), null).collect { snapshots.add(it) } }
        runCurrent()
        assertEquals(setOf(0, 1, 2), started.toSet())
        assertEquals(listOf(SourceProviderState.LOADING, SourceProviderState.LOADING, SourceProviderState.LOADING),
            snapshots.single().statuses.map { it.state })

        gates[1].complete(page("fast"))
        runCurrent()
        assertEquals(listOf("fast"), snapshots.last().page.sources.map { it.infoHash })
        assertTrue(snapshots.last().loading)
        assertEquals(SourceProviderState.LOADING, snapshots.last().statuses[0].state)
        assertEquals(SourceProviderState.SUCCESS, snapshots.last().statuses[1].state)
        gates[2].complete(page("third"))
        runCurrent()
        assertEquals(listOf("fast", "third"), snapshots.last().page.sources.map { it.infoHash })
        gates[0].complete(page("primary"))
        collecting.join()
        assertEquals(4, snapshots.size)
        assertEquals(listOf("primary", "fast", "third"), snapshots.last().page.sources.map { it.infoHash })
        assertEquals(listOf("Torrentio", "TorrentsDB", "MediaFusion Midnight"),
            snapshots.last().page.sources.map { it.provider })
        assertFalse(snapshots.last().loading)
    }

    @Test fun isolatesAndRedactsFailureAndCachesOnlySuccessfulProviders() = runTest {
        val calls = IntArray(3)
        var client = 0
        val providers = MultiSourceProviders(allEnabled, { testScheduler.currentTime }) {
            val index = client++
            ProviderSourceFetcher { _, _ ->
                calls[index]++
                if (index == 0) throw IOException("https://secret.example/private-token/manifest.json")
                if (index == 1) page("healthy") else SourcePage(emptyList(), 2)
            }
        }
        val first = providers.sources(details(), null).last()
        assertEquals(listOf("healthy"), first.page.sources.map { it.infoHash })
        assertEquals(2, first.page.unsupportedCount)
        assertEquals(SourceProviderState.ERROR, first.statuses[0].state)
        assertFalse(first.statuses[0].error.orEmpty().contains("secret"))
        assertFalse(first.statuses[0].error.orEmpty().contains("https"))
        assertEquals(listOf(0, 1, 0), first.statuses.map { it.sourceCount })
        assertFalse(first.loading)
        val second = providers.sources(details(), null).last()
        assertEquals(listOf(2, 1, 1), calls.toList())
        assertEquals(listOf(false, true, true), second.statuses.map { it.cached })
    }

    @Test fun cancellingCollectionCancelsEveryPendingProviderWithoutCachingOrErrorSnapshots() = runTest {
        val calls = IntArray(3)
        var cancelled = 0
        var client = 0
        val providers = MultiSourceProviders(allEnabled, { testScheduler.currentTime }) {
            val index = client++
            ProviderSourceFetcher { _, _ ->
                calls[index]++
                if (calls[index] == 1) {
                    try { awaitCancellation() } finally { cancelled++ }
                } else page("provider-$index")
            }
        }
        val snapshots = mutableListOf<ProviderSourcesSnapshot>()
        val collecting = launch { providers.sources(details(), null).collect { snapshots.add(it) } }
        runCurrent()
        collecting.cancelAndJoin()
        assertEquals(3, cancelled)
        assertEquals(listOf(1, 1, 1), calls.toList())
        assertTrue(snapshots.all { it.statuses.all { status -> status.state == SourceProviderState.LOADING } })
        val next = providers.sources(details(), null).last()
        assertEquals(listOf(2, 2, 2), calls.toList())
        assertTrue(next.statuses.all { it.state == SourceProviderState.SUCCESS && !it.cached })
    }

    @Test fun providerCancellationPropagatesInsteadOfBecomingAnErrorOrIncompleteSuccess() = runTest {
        val cancel = CompletableDeferred<Unit>()
        var stopped = 0
        var client = 0
        val providers = MultiSourceProviders(allEnabled, { testScheduler.currentTime }) {
            val index = client++
            ProviderSourceFetcher { _, _ ->
                try {
                    if (index == 0) {
                        cancel.await()
                        throw CancellationException("Request was cancelled")
                    } else awaitCancellation()
                } finally { stopped++ }
            }
        }
        val collecting = async { providers.sources(details(), null).last() }
        runCurrent()
        cancel.complete(Unit)
        val failure = runCatching { collecting.await() }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertEquals(3, stopped)
    }

    @Test fun boundsEntireProviderOperationToThirtySecondsWithoutRetry() = runTest {
        var calls = 0
        var streamStarted = false
        var cancelled = false
        val providers = MultiSourceProviders(CatalogConfiguration(), { testScheduler.currentTime }) {
            ProviderSourceFetcher { _, _ ->
                calls++
                try {
                    delay(20_000) // Manifest phase.
                    streamStarted = true
                    delay(20_000) // Stream phase must share the same deadline.
                    page("late")
                } finally { cancelled = true }
            }
        }
        val snapshots = mutableListOf<ProviderSourcesSnapshot>()
        val collecting = launch { providers.sources(details(), null).collect { snapshots.add(it) } }
        runCurrent()
        advanceTimeBy(29_999)
        runCurrent()
        assertTrue(streamStarted)
        assertEquals(1, snapshots.size)
        advanceTimeBy(1)
        runCurrent()
        collecting.join()
        assertEquals(30_000L, testScheduler.currentTime)
        assertEquals(1, calls)
        assertTrue(cancelled)
        assertEquals(SourceProviderState.ERROR, snapshots.last().statuses.single().state)
        assertTrue(snapshots.last().statuses.single().error.orEmpty().contains("timed out"))
        assertTrue(snapshots.last().page.sources.isEmpty())
        assertFalse(snapshots.last().loading)
    }

    @Test fun boundsConcurrentCallsAcrossOverlappingCollections() = runTest {
        val gate = CompletableDeferred<Unit>()
        var active = 0
        var maximum = 0
        var calls = 0
        val providers = MultiSourceProviders(allEnabled, { testScheduler.currentTime }) {
            ProviderSourceFetcher { details, _ ->
                calls++
                active++
                maximum = maxOf(maximum, active)
                try { gate.await(); page("title-${details.title.id}") } finally { active-- }
            }
        }
        val requests = (1..4).map { id -> async { providers.sources(details(id), null).last() } }
        runCurrent()
        assertEquals(3, active)
        gate.complete(Unit)
        val results = requests.awaitAll()
        assertEquals(12, calls)
        assertEquals(3, maximum)
        assertEquals(0, active)
        assertTrue(results.all { result -> result.statuses.all { it.state == SourceProviderState.SUCCESS } })
    }

    @Test fun cacheExpiresAtTwoMinutesAndNeverCrossesConfigurationInstances() = runTest {
        var now = 0L
        var calls = 0
        val factory: (String) -> ProviderSourceFetcher = {
            ProviderSourceFetcher { _, _ -> calls++; page("cached") }
        }
        val configuration = CatalogConfiguration()
        val providers = MultiSourceProviders(configuration, { now }, factory)
        assertFalse(providers.sources(details(), null).last().statuses.single().cached)
        now = 119_999
        assertTrue(providers.sources(details(), null).last().statuses.single().cached)
        assertEquals(1, calls)
        now = 120_000
        assertFalse(providers.sources(details(), null).last().statuses.single().cached)
        assertEquals(2, calls)
        val changed = MultiSourceProviders(configuration.copy(catalogLanguage = "fr-FR"), { now }, factory)
        assertFalse(changed.sources(details(), null).last().statuses.single().cached)
        assertEquals(3, calls)
    }

    @Test fun cachesTwelveLeastRecentlyUsedTitlesPerProvider() = runTest {
        var calls = 0
        val providers = MultiSourceProviders(CatalogConfiguration(), { 0L }) {
            ProviderSourceFetcher { details, _ -> calls++; page("title-${details.title.id}") }
        }
        for (id in 1..12) providers.sources(details(id), null).last()
        assertTrue(providers.sources(details(1), null).last().statuses.single().cached)
        providers.sources(details(13), null).last()
        assertTrue(providers.sources(details(1), null).last().statuses.single().cached)
        assertEquals(13, calls)
        assertFalse(providers.sources(details(2), null).last().statuses.single().cached)
        assertEquals(14, calls)
    }

    @Test fun cachesEpisodesSeparatelyWithoutRecreatingClients() = runTest {
        var clients = 0
        var calls = 0
        val providers = MultiSourceProviders(CatalogConfiguration(), { 0L }) {
            clients++
            ProviderSourceFetcher { _, episode -> calls++; page("episode-${episode?.number}") }
        }
        val series = details().let { it.copy(title = it.title.copy(kind = MediaKind.SERIES),
            seasons = listOf(CatalogSeason(1, "Season 1", 2))) }
        val first = CatalogEpisode(1, 1, 1, "First", "", null, "")
        val second = first.copy(id = 2, number = 2, title = "Second")
        assertEquals("episode-1", providers.sources(series, first).last().page.sources.single().infoHash)
        assertEquals("episode-2", providers.sources(series, second).last().page.sources.single().infoHash)
        assertTrue(providers.sources(series, first).last().statuses.single().cached)
        assertEquals(2, calls)
        assertEquals(1, clients)
    }

    @Test fun disabledProvidersAreNeverContactedAndPrimaryBuiltInUrlIsNotRequestedTwice() = runTest {
        val requested = mutableListOf<String>()
        val factory: (String) -> ProviderSourceFetcher = { url ->
            ProviderSourceFetcher { _, _ -> requested.add(url); page(url.hashCode().toString()) }
        }
        val primaryOnly = MultiSourceProviders(CatalogConfiguration(), { 0L }, factory)
        primaryOnly.sources(details(), null).last()
        assertEquals(listOf("https://torrentio.strem.fun/manifest.json"), requested)
        requested.clear()
        val duplicate = MultiSourceProviders(allEnabled.copy(
            providerManifestUrl = "https://TORRENTSDB.COM:443/manifest.json"), { 0L }, factory)
        val result = duplicate.sources(details(), null).last()
        assertEquals(setOf("https://TORRENTSDB.COM:443/manifest.json",
            "https://mediafusionfortheweebs.midnightignite.me/manifest.json"), requested.toSet())
        assertEquals(2, requested.size)
        assertEquals(listOf("primary", "mediafusion"), result.statuses.map { it.id })
        assertEquals("TorrentsDB", result.statuses.first().label)
    }

    @Test fun acceptsFiveHundredPerProviderAndRejectsOversizedProviderWithoutDiscardingOthers() = runTest {
        suspend fun fetch(firstCount: Int): ProviderSourcesSnapshot {
            var client = 0
            val providers = MultiSourceProviders(allEnabled, { 0L }) {
                val index = client++
                ProviderSourceFetcher { _, _ ->
                    val count = if (index == 0) firstCount else 500
                    SourcePage(List(count) { source("$index-$it") }, index)
                }
            }
            return providers.sources(details(), null).last()
        }
        val full = fetch(500)
        assertEquals(1500, full.page.sources.size)
        assertEquals(listOf(500, 500, 500), full.statuses.map { it.sourceCount })
        val oversized = fetch(501)
        assertEquals(1000, oversized.page.sources.size)
        assertEquals(listOf(SourceProviderState.ERROR, SourceProviderState.SUCCESS, SourceProviderState.SUCCESS),
            oversized.statuses.map { it.state })
        assertEquals(3, oversized.page.unsupportedCount)
    }

    @Test fun exactTorrentFilesUnionProvenanceAndCompatibleTrackersButKeepPreferredMetadata() {
        val first = source("ABC").copy(providerIds = setOf("primary"), quality = null,
            languages = emptyList(), sizeBytes = null, seeders = 500, trackers = listOf("first"))
        val preferred = source("abc").copy(providerIds = setOf("torrentsdb"), seeders = 2,
            trackers = listOf("second", "first"))
        val merged = mergeSourceRecords(listOf(first, preferred),
            linkedMapOf("primary" to "Torrentio", "torrentsdb" to "TorrentsDB")).single()
        assertEquals(preferred.copy(provider = "Torrentio + TorrentsDB", providerIds = setOf("primary", "torrentsdb"),
            trackers = listOf("first", "second")), merged)
    }

    @Test fun conflictingFilenamesNeverCombineTrackersOrInventQualityLanguageAndSizeHints() {
        val named = source("ABC").copy(quality = null, languages = emptyList(), sizeBytes = null,
            seeders = null, trackers = listOf("safe"), providerIds = setOf("primary"))
        val unnamed = source("abc", filename = null).copy(quality = "2160p", languages = listOf("fr"),
            sizeBytes = 900, seeders = 900, trackers = listOf("unproven"), providerIds = setOf("mediafusion"))
        val merged = mergeSourceRecords(listOf(named, unnamed)).single()
        assertEquals(named.copy(providerIds = setOf("primary", "mediafusion")), merged)
        assertNull(merged.quality)
        assertTrue(merged.languages.isEmpty())
        assertNull(merged.sizeBytes)
        val contrary = source("abc", filename = "Different.2160p.mkv").copy(quality = "2160p",
            languages = listOf("fr"), seeders = 900, trackers = listOf("contrary"))
        val original = source("ABC").copy(trackers = listOf("original"))
        assertEquals(original, mergeSourceRecords(listOf(original, contrary)).single())
    }

    @Test fun nullIndexDifferentOrMissingFilenamesStaySeparateAndIndicesRemainExact() {
        val first = source("ABC", null, "first.mkv").copy(providerIds = setOf("primary"), trackers = listOf("first"))
        val duplicate = first.copy(infoHash = "abc", providerIds = setOf("torrentsdb"), trackers = listOf("second"))
        val second = source("abc", null, "second.mkv")
        val unnamed = source("abc", null, null)
        val zero = source("abc", 0, "first.mkv")
        val one = source("ABC", 1, "first.mkv")
        val merged = mergeSourceRecords(listOf(first, duplicate, second, unnamed, zero, one))
        assertEquals(listOf(first.copy(providerIds = setOf("primary", "torrentsdb"),
            trackers = listOf("first", "second")), second, unnamed, zero, one), merged)
    }
}
