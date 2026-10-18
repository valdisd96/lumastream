package dev.lumastream.catalog

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class NextEpisodeTest {
    private fun episode(season: Int, number: Int) = CatalogEpisode(season * 100 + number + 1,
        season, number, "Episode $number", "", null, "")
    private fun details(vararg seasons: Int) = CatalogDetails(
        CatalogTitle(1, MediaKind.SERIES, "Series", "Series", "2025", "", null, null),
        "tt1234567", seasons.map { CatalogSeason(it, "Season $it", 10) }, 30)

    @Test fun selectsNextListedNumberNotCurrentEpisodeOrAssumedIncrement() = runBlocking {
        val episodes = listOf(episode(1, 8), episode(1, 2), episode(1, 5))
        val result = findNextEpisode(details(1, 2), episode(1, 2)) { season ->
            check(season == 1)
            episodes
        }
        assertEquals(episode(1, 5), result?.episode)
    }

    @Test fun crossesNoncontiguousSeasonsAndSkipsEmptySeasons() = runBlocking {
        val result = findNextEpisode(details(0, 7, 1, 3), episode(1, 10)) { season ->
            when (season) {
                1 -> listOf(episode(1, 10))
                3 -> emptyList()
                7 -> listOf(episode(7, 4), episode(7, 2))
                else -> error("Specials must not enter regular season ordering")
            }
        }
        assertEquals(episode(7, 2), result?.episode)
    }

    @Test fun finalEpisodeHasNoNextTarget() = runBlocking {
        assertNull(findNextEpisode(details(0, 1), episode(1, 10)) { listOf(episode(1, 10)) })
    }

    @Test fun specialsStayWithinTheirOwnSeason() = runBlocking {
        val details = details(0, 1)
        val specials = listOf(episode(0, 1), episode(0, 3))
        assertEquals(episode(0, 3), findNextEpisode(details, episode(0, 1)) { specials }?.episode)
        assertNull(findNextEpisode(details, episode(0, 3)) { season ->
            check(season == 0)
            specials
        })
    }

    @Test fun missingCurrentSeasonDoesNotJumpToUnrelatedSeason() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { findNextEpisode(details(2), episode(1, 3)) { error("Must not fetch") } }
        }
    }

    @Test fun metadataFailuresAndCancellationAreNotReportedAsSeriesCompletion() {
        assertThrows(IOException::class.java) {
            runBlocking { findNextEpisode(details(1), episode(1, 3)) { throw IOException("Offline") } }
        }
        assertThrows(CancellationException::class.java) {
            runBlocking { findNextEpisode(details(1), episode(1, 3)) { throw CancellationException() } }
        }
    }
}
