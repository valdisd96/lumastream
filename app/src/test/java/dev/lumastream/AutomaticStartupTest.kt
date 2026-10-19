package dev.lumastream

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomaticStartupTest {
    @Test fun firstFrameCommitsSourceWithoutTryingRemainingCandidates() = runBlocking {
        val events = mutableListOf<String>()
        val failure = runAutomaticStartup(
            candidates = listOf(1, 2, 3), now = { 0L },
            onAttempt = { _, _, _ -> },
            prepare = { events += "metadata:$it"; it },
            awaitFirstFrame = {
                events += "frame:$it"
                if (it == 1) throw AutomaticStartupFailure("Unreadable source")
            },
            committed = { false }, cleanup = { events += "stop" }, onOutcome = { _, _ -> },
        )
        assertNull(failure)
        assertEquals(listOf("stop", "metadata:1", "frame:1", "stop", "metadata:2", "frame:2"), events)
    }

    @Test fun attemptsAreBoundedEvenWhenCallerSuppliesMoreSources() = runBlocking {
        val attempts = mutableListOf<Int>()
        val failure = runAutomaticStartup(
            candidates = (1..10).toList(), now = { 0L }, onAttempt = { _, _, _ -> },
            prepare = { attempts += it; throw AutomaticStartupFailure("Metadata unavailable") },
            awaitFirstFrame = { _: Nothing -> error("No metadata") }, committed = { false },
            cleanup = {}, onOutcome = { _, _ -> },
        )
        assertEquals(listOf(1, 2, 3, 4), attempts)
        assertEquals("Metadata unavailable", failure)
    }

    @Test fun cancellationDoesNotAdvanceAndWaitsForCleanup() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val cleanupStarted = CompletableDeferred<Unit>()
        val cleanupAllowed = CompletableDeferred<Unit>()
        var cleanupCount = 0
        val attempts = mutableListOf<Int>()
        val job = launch {
            runAutomaticStartup(
                candidates = listOf(1, 2), now = { 0L }, onAttempt = { index, _, _ -> attempts += index },
                prepare = { it }, awaitFirstFrame = { entered.complete(Unit); awaitCancellation() },
                committed = { false }, cleanup = {
                    cleanupCount++
                    if (cleanupCount > 1) {
                        cleanupStarted.complete(Unit)
                        cleanupAllowed.await()
                    }
                }, onOutcome = { _, _ -> },
            )
        }
        entered.await()
        job.cancel()
        cleanupStarted.await()
        assertFalse(job.isCompleted)
        cleanupAllowed.complete(Unit)
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(listOf(1), attempts)
    }

    @Test fun explicitCancellationIsNotTreatedAsAnUnhealthySource() = runBlocking {
        val attempts = mutableListOf<Int>()
        var cancelled = false
        try {
            runAutomaticStartup(
                candidates = listOf(1, 2), now = { 0L }, onAttempt = { index, _, _ -> attempts += index },
                prepare = { throw CancellationException("User cancelled") },
                awaitFirstFrame = { _: Nothing -> error("No metadata") }, committed = { false },
                cleanup = {}, onOutcome = { _, _ -> },
            )
        } catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
        assertEquals(listOf(1), attempts)
    }

    @Test fun elapsedMetadataTimeIsDeductedFromTheSameAttemptBudget() {
        var now = 0L
        val budget = AutomaticStartupBudget { now }
        budget.beginAttempt()
        assertEquals(15_000L, budget.metadataRemaining())
        now = 15_000
        assertEquals(20_000L, budget.remaining())
        assertEquals(0L, budget.metadataRemaining())
        now = 35_000
        assertEquals(0L, budget.remaining())
        now = 100_000
        budget.beginAttempt()
        assertEquals(20_000L, budget.remaining())
        now = 120_000
        assertEquals(0L, budget.remaining())
        assertEquals(0L, budget.overallRemaining())
    }

    @Test fun teardownConsumesOverallBudgetAndPreventsAnotherAttempt() = runBlocking {
        var now = 0L
        var stops = 0
        val attempts = mutableListOf<Int>()
        val failure = runAutomaticStartup(
            candidates = listOf(1, 2), now = { now }, onAttempt = { index, _, _ -> attempts += index },
            prepare = { it }, awaitFirstFrame = { throw AutomaticStartupFailure("Read failed") },
            committed = { false }, cleanup = { if (++stops == 2) now = 120_000 },
            onOutcome = { _, _ -> },
        )
        assertEquals(listOf(1), attempts)
        assertTrue(failure!!.contains("120-second limit"))
    }

    @Test fun metadataPhaseCannotConsumeTheRenderingBudget() = runBlocking {
        var now = 0L
        var rendered = false
        val failure = runAutomaticStartup(
            candidates = listOf(1), now = { now }, onAttempt = { _, _, _ -> },
            prepare = { now = 15_000; it }, awaitFirstFrame = { rendered = true },
            committed = { false }, cleanup = {}, onOutcome = { _, _ -> },
        )
        assertFalse(rendered)
        assertTrue(failure!!.contains("Metadata did not arrive within 15 seconds"))
    }

    @Test fun lateRenderingCannotExtendTheAttemptDeadline() = runBlocking {
        var now = 0L
        val failure = runAutomaticStartup(
            candidates = listOf(1), now = { now }, onAttempt = { _, _, _ -> },
            prepare = { now = 10_000; it }, awaitFirstFrame = { now = 35_000 },
            committed = { false }, cleanup = {}, onOutcome = { _, _ -> },
        )
        assertTrue(failure!!.contains("No video frame"))
    }

    @Test fun cleanupFailureCannotReplaceCancellation() = runBlocking {
        val cancellation = CancellationException("Cancelled by user")
        var stops = 0
        var observed: Throwable? = null
        try {
            runAutomaticStartup<Int, Int>(
                candidates = listOf(1, 2), now = { 0L }, onAttempt = { _, _, _ -> },
                prepare = { throw cancellation }, awaitFirstFrame = {},
                committed = { false }, cleanup = { if (++stops > 1) error("Storage cleanup failed") },
                onOutcome = { _, _ -> },
            )
        } catch (failure: Throwable) { observed = failure }
        assertTrue(observed is CancellationException)
    }

    @Test fun committedFrameWinsOverAnAlreadyQueuedFailure() = runBlocking {
        var committed = false
        val attempts = mutableListOf<Int>()
        var stops = 0
        val failure = runAutomaticStartup(
            candidates = listOf(1, 2), now = { 0L }, onAttempt = { index, _, _ -> attempts += index },
            prepare = { it }, awaitFirstFrame = {
                committed = true
                throw AutomaticStartupFailure("Late engine failure")
            }, committed = { committed }, cleanup = { stops++ }, onOutcome = { _, _ -> },
        )
        assertNull(failure)
        assertEquals(listOf(1), attempts)
        assertEquals(1, stops)
    }
}
