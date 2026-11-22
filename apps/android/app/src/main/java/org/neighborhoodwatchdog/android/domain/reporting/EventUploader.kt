package org.neighborhoodwatchdog.android.domain.reporting

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.neighborhoodwatchdog.android.domain.model.Clock
import org.neighborhoodwatchdog.android.domain.model.ConnectivityEvent
import org.neighborhoodwatchdog.android.domain.model.EventResult
import java.time.Instant
import kotlin.math.min
import kotlin.math.pow

/** Exponential backoff: start 5 s, factor 2, cap 15 minutes, with jitter. */
class BackoffPolicy(
    private val initialMs: Long = 5_000,
    private val factor: Double = 2.0,
    private val capMs: Long = 15 * 60_000,
    private val jitterRatio: Double = 0.2,
    private val random: () -> Double = { Math.random() },
) {
    /** @param attempt 1 for the first retry. */
    fun delayMs(attempt: Int): Long {
        val exponent = (attempt.coerceAtLeast(1) - 1).coerceAtMost(30)
        val base = min(capMs.toDouble(), initialMs * factor.pow(exponent))
        val jittered = base * (1 - jitterRatio) + random() * 2 * jitterRatio * base
        return min(capMs.toDouble(), jittered).toLong().coerceAtLeast(1)
    }
}

sealed interface FlushResult {
    /** The queue is empty. */
    data object Idle : FlushResult

    /** Events remain; try again after [delayMs]. */
    data class RetryIn(val delayMs: Long, val cause: String) : FlushResult

    /** Events remain and the server refuses this device or session (for example `device_revoked`). */
    data class Blocked(val errorCode: String, val delayMs: Long) : FlushResult
}

/**
 * Uploads the queue, oldest first, in batches of up to 50. An event leaves the queue only when the
 * server said `accepted`, `duplicate`, or `rejected` for a reason other than `rate_limited`.
 */
class EventUploader(
    private val store: EventQueueStore,
    private val sentLog: SentEventLog,
    private val transport: EventTransport,
    private val clock: Clock,
    private val backoff: BackoffPolicy = BackoffPolicy(),
    private val onDelivered: suspend (sentAtMs: Long) -> Unit = {},
) {
    private val mutex = Mutex()
    private var failures = 0

    /** Writes the event to the durable queue. Returns false when that id was already queued. */
    suspend fun enqueue(event: ConnectivityEvent): Boolean {
        val inserted = store.insert(event, clock.nowMs())
        QueuePolicy.enforce(store, clock.nowMs())
        return inserted
    }

    suspend fun flush(): FlushResult = mutex.withLock {
        QueuePolicy.enforce(store, clock.nowMs())
        while (true) {
            val batch = store.oldest(BATCH_SIZE)
            if (batch.isEmpty()) {
                failures = 0
                return@withLock FlushResult.Idle
            }
            val stop = sendBatch(batch.map { it.event }, isolateOnValidationError = true)
            if (stop != null) return@withLock stop
        }
        @Suppress("UNREACHABLE_CODE")
        FlushResult.Idle
    }

    /** Returns null to continue with the next batch, or the result that ends this flush. */
    private suspend fun sendBatch(events: List<ConnectivityEvent>, isolateOnValidationError: Boolean): FlushResult? {
        val sentAtMs = clock.nowMs()
        val ids = events.map { it.eventId }
        return when (val result = transport.send(events, Instant.ofEpochMilli(sentAtMs))) {
            is SendResult.Delivered -> {
                val byId = events.associateBy { it.eventId }
                val finished = mutableListOf<SentRecord>()
                var rateLimited = result.response.retryAfterSeconds != null
                for (outcome in result.response.outcomes) {
                    val event = byId[outcome.eventId] ?: continue
                    val limited = outcome.result == EventResult.REJECTED && outcome.reason?.contains(RATE_LIMITED) == true
                    if (limited) {
                        rateLimited = true
                    } else {
                        finished += SentRecord(event, sentAtMs, outcome.result.name.lowercase(), outcome.reason, result.reportedVia)
                    }
                }
                store.remove(finished.map { it.event.eventId })
                if (finished.isNotEmpty()) {
                    sentLog.record(finished)
                    onDelivered(sentAtMs)
                }
                val kept = ids - finished.map { it.event.eventId }.toSet()
                when {
                    rateLimited -> {
                        store.incrementAttempts(kept)
                        retry("rate_limited", result.response.retryAfterSeconds)
                    }
                    finished.isEmpty() -> {
                        // The server answered but settled nothing: do not spin on the same batch.
                        store.incrementAttempts(kept)
                        retry("no_outcome", null)
                    }
                    else -> {
                        failures = 0
                        null
                    }
                }
            }
            is SendResult.NetworkFailure -> {
                store.incrementAttempts(ids)
                retry("network", null)
            }
            is SendResult.ServerError -> {
                store.incrementAttempts(ids)
                retry("http_${result.httpCode}", null)
            }
            is SendResult.RateLimited -> {
                store.incrementAttempts(ids)
                retry("rate_limited", result.retryAfterSeconds)
            }
            SendResult.Unauthorized -> {
                failures++
                FlushResult.Blocked("unauthorized", backoff.delayMs(failures))
            }
            is SendResult.Rejected -> {
                val validation = result.httpCode == 422 || result.httpCode == 400
                when {
                    validation && events.size > 1 && isolateOnValidationError -> {
                        // One malformed event must not block the rest: send them one at a time.
                        for (event in events) {
                            val stop = sendBatch(listOf(event), isolateOnValidationError = false)
                            if (stop != null) return stop
                        }
                        null
                    }
                    validation -> {
                        // A single event the server will never accept: drop it and keep a record.
                        store.remove(ids)
                        sentLog.record(
                            events.map { SentRecord(it, sentAtMs, "rejected", result.errorCode ?: "validation_error", null) },
                        )
                        null
                    }
                    else -> {
                        failures++
                        FlushResult.Blocked(result.errorCode ?: "http_${result.httpCode}", backoff.delayMs(failures))
                    }
                }
            }
        }
    }

    private fun retry(cause: String, retryAfterSeconds: Int?): FlushResult {
        failures++
        val serverDelay = (retryAfterSeconds ?: 0).coerceAtLeast(0) * 1000L
        return FlushResult.RetryIn(maxOf(backoff.delayMs(failures), serverDelay), cause)
    }

    companion object {
        const val BATCH_SIZE = 50
        private const val RATE_LIMITED = "rate_limited"
    }
}
