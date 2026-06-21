package pl.lambada.songsync.util.networking

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import pl.lambada.songsync.util.EmptyQueryException
import pl.lambada.songsync.util.NoTrackFoundException
import kotlin.math.min
import kotlin.random.Random

/**
 * Runs [block] with exponential backoff + jitter, retrying transient failures (timeouts, dropped connections,
 * provider hiccups). Never retries a [CancellationException]. After [maxAttempts] it rethrows the last error so
 * the caller can mark the song failed and move on — the batch must never crash on one bad request.
 *
 * Backoff: base * 2^(n-1) plus up to `base` of jitter, capped at [maxDelayMs] (1s -> 2s -> 4s -> ... -> 30s).
 */
suspend fun <T> withRetry(
    maxAttempts: Int = 3,
    baseDelayMs: Long = 1000,
    maxDelayMs: Long = 30_000,
    onRetry: (attempt: Int, delayMs: Long, cause: Throwable) -> Unit = { _, _, _ -> },
    block: suspend () -> T
): T {
    var attempt = 0
    while (true) {
        try {
            return block()
        } catch (c: CancellationException) {
            throw c
        } catch (e: Throwable) {
            attempt++
            // "No track found" / "empty query" are definitive answers, not transient failures -- retrying
            // them just wastes seconds per provider and makes the search feel like it hangs.
            val nonRetryable = e is NoTrackFoundException || e is EmptyQueryException
            if (nonRetryable || attempt >= maxAttempts) throw e
            val exp = baseDelayMs shl (attempt - 1)            // base * 2^(attempt-1)
            val delayMs = min(exp + Random.nextLong(0, baseDelayMs.coerceAtLeast(1)), maxDelayMs)
            onRetry(attempt, delayMs, e)
            delay(delayMs)
        }
    }
}
