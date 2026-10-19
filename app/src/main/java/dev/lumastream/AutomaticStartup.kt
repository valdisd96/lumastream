package dev.lumastream

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal class AutomaticStartupFailure(message: String) : Exception(message)

/** One monotonic budget covers metadata, selection, rendering and teardown between sources. */
internal class AutomaticStartupBudget(private val now: () -> Long) {
    private val started = now()
    private var attemptStarted = started
    fun beginAttempt() { attemptStarted = now() }
    fun remaining(): Long = minOf(120_000 - (now() - started), 35_000 - (now() - attemptStarted)).coerceAtLeast(0)
    fun metadataRemaining(): Long = minOf(15_000 - attemptElapsed(), remaining()).coerceAtLeast(0)
    fun overallRemaining(): Long = (120_000 - (now() - started)).coerceAtLeast(0)
    fun elapsed(): Long = now() - started
    fun attemptElapsed(): Long = now() - attemptStarted
}

/** Returns a bounded, user-facing last failure, or null once a frame commits this source. */
internal suspend fun <T, P> runAutomaticStartup(
    candidates: List<T>,
    now: () -> Long,
    onAttempt: (Int, Int, AutomaticStartupBudget) -> Unit,
    prepare: suspend (T) -> P,
    awaitFirstFrame: suspend (P) -> Unit,
    committed: () -> Boolean,
    cleanup: suspend () -> Unit,
    onOutcome: (String, AutomaticStartupBudget) -> Unit,
): String? {
    val budget = AutomaticStartupBudget(now)
    val bounded = candidates.take(4)
    var succeeded = false
    var cancellation: CancellationException? = null
    var reason = "No compatible sources remain. Choose a source manually."
    try {
        cleanup()
        for ((index, candidate) in bounded.withIndex()) {
            currentCoroutineContext().ensureActive()
            if (budget.overallRemaining() == 0L) {
                reason = "Automatic selection reached its 120-second limit. Choose a source manually."
                break
            }
            budget.beginAttempt()
            onAttempt(index + 1, bounded.size, budget)
            try {
                var prepared: P? = null
                val metadataReady = withTimeoutOrNull(budget.metadataRemaining()) {
                    prepared = prepare(candidate)
                    true
                } == true
                if (!metadataReady || budget.metadataRemaining() == 0L) {
                    throw AutomaticStartupFailure("Metadata did not arrive within 15 seconds. Check connectivity or choose another source.")
                }
                @Suppress("UNCHECKED_CAST")
                val firstFrame = withTimeoutOrNull(budget.remaining()) {
                    awaitFirstFrame(prepared as P)
                    true
                } == true
                // A first-frame callback can win just before the timeout continuation is dispatched.
                if ((firstFrame && budget.remaining() > 0) || committed()) {
                    currentCoroutineContext().ensureActive()
                    succeeded = true
                    onOutcome("first_frame", budget)
                    return null
                }
                reason = "No video frame arrived within the startup limit. Choose a healthier or more compatible source."
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (committed()) {
                    currentCoroutineContext().ensureActive()
                    succeeded = true
                    onOutcome("first_frame", budget)
                    return null
                }
                reason = failure.message?.take(500)?.takeIf { it.isNotBlank() }
                    ?: "This source could not start. Check connectivity, storage, or choose another source."
            }
            onOutcome("failed: $reason", budget)
            // Teardown must finish before the single engine is opened for another candidate.
            withContext(NonCancellable) { cleanup() }
        }
        return reason
    } catch (cancelled: CancellationException) {
        cancellation = cancelled
        throw cancelled
    } finally {
        if (!succeeded) {
            try {
                withContext(NonCancellable) { cleanup() }
            } catch (cleanupFailure: Exception) {
                cancellation?.let { throw it }
                currentCoroutineContext().ensureActive()
                throw cleanupFailure
            }
        }
    }
}
