package org.neighborhoodwatchdog.android.domain.reporting

import org.neighborhoodwatchdog.android.domain.model.ConnectivityEvent
import org.neighborhoodwatchdog.android.domain.model.ConnectivityState
import org.neighborhoodwatchdog.android.domain.model.Diagnostics
import org.neighborhoodwatchdog.android.domain.model.EventType
import org.neighborhoodwatchdog.android.domain.model.ObservationConfidence
import org.neighborhoodwatchdog.android.domain.model.SubmitEventsResponse
import org.neighborhoodwatchdog.android.domain.model.Transport
import java.time.Instant
import java.util.UUID

data class QueuedEvent(val event: ConnectivityEvent, val attempts: Int, val enqueuedAtMs: Long)

/** The durable local queue. Implemented with Room on the device and in memory in tests. */
interface EventQueueStore {
    /** Returns false when an event with the same id is already queued (nothing is written). */
    suspend fun insert(event: ConnectivityEvent, nowMs: Long): Boolean

    /** Oldest first (lowest sequence first). */
    suspend fun oldest(limit: Int): List<QueuedEvent>
    suspend fun remove(eventIds: Collection<String>)
    suspend fun incrementAttempts(eventIds: Collection<String>)
    suspend fun count(): Int

    /** Deletes events that occurred before [cutoffMs]. Returns how many were deleted. */
    suspend fun deleteOccurredBefore(cutoffMs: Long): Int

    /** Deletes up to [limit] of the oldest events of the given types (any type when null). */
    suspend fun deleteOldest(types: Set<EventType>?, limit: Int): Int
    suspend fun clear()
}

data class SentRecord(
    val event: ConnectivityEvent,
    val sentAtMs: Long,
    /** accepted, duplicate or rejected. */
    val result: String,
    val reason: String?,
    val reportedVia: Transport?,
)

/** The "last 50 sent events" log that residents can inspect. */
interface SentEventLog {
    suspend fun record(records: List<SentRecord>)
    suspend fun clear()
}

sealed interface SendResult {
    /** The server answered 2xx. [reportedVia] is the network the request really went through. */
    data class Delivered(val response: SubmitEventsResponse, val reportedVia: Transport) : SendResult
    data class NetworkFailure(val message: String?) : SendResult
    data class ServerError(val httpCode: Int) : SendResult
    data class RateLimited(val retryAfterSeconds: Int?) : SendResult
    data object Unauthorized : SendResult

    /** Any other 4xx for the whole request. [errorCode] is the API error code when present. */
    data class Rejected(val httpCode: Int, val errorCode: String?) : SendResult
}

fun interface EventTransport {
    /** One upload attempt. [sentAt] is the time of this attempt; the events are sent unchanged. */
    suspend fun send(events: List<ConnectivityEvent>, sentAt: Instant): SendResult
}

/** Persisted per-device counter for [ConnectivityEvent.sequence]. */
fun interface SequenceSource {
    fun next(): Long
}

class EventFactory(
    private val sequence: SequenceSource,
    private val osVersion: String,
    private val appVersion: String,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    /** The id and the occurrence time are fixed here, once, and never change across retries. */
    fun create(
        type: EventType,
        state: ConnectivityState,
        occurredAtMs: Long,
        diagnostics: Diagnostics,
        confidence: ObservationConfidence = ObservationConfidence.OBSERVED,
    ): ConnectivityEvent = ConnectivityEvent(
        eventId = newId(),
        sequence = sequence.next(),
        eventType = type,
        connectivityState = state,
        occurredAt = Instant.ofEpochMilli(occurredAtMs),
        osVersion = osVersion,
        appVersion = appVersion,
        confidence = confidence,
        diagnostics = diagnostics,
    )
}

/** Queue cap: 2,000 events or 7 days. Heartbeats go first; failure events go last. */
object QueuePolicy {
    const val MAX_EVENTS = 2_000
    const val MAX_AGE_MS: Long = 7L * 24 * 60 * 60 * 1000

    /** Dropped after heartbeats and before anything that describes a failure or a recovery. */
    val lowPriorityTypes: Set<EventType> = setOf(
        EventType.SIGNAL_STRENGTH_CHANGED,
        EventType.DEFAULT_NETWORK_CHANGED,
        EventType.PERMISSION_CHANGED,
        EventType.MONITORING_STARTED,
        EventType.MONITORING_RESUMED,
        EventType.MONITORING_STOPPED,
    )

    /** Returns the number of events dropped. */
    suspend fun enforce(store: EventQueueStore, nowMs: Long): Int {
        var dropped = store.deleteOccurredBefore(nowMs - MAX_AGE_MS)
        var over = store.count() - MAX_EVENTS
        if (over > 0) {
            val n = store.deleteOldest(setOf(EventType.HEARTBEAT), over)
            over -= n
            dropped += n
        }
        if (over > 0) {
            val n = store.deleteOldest(lowPriorityTypes, over)
            over -= n
            dropped += n
        }
        if (over > 0) {
            // Only reached when the queue holds more than the cap in failure events alone.
            dropped += store.deleteOldest(null, over)
        }
        return dropped
    }
}
