package org.neighborhoodwatchdog.android.domain

import org.neighborhoodwatchdog.android.domain.auth.CredentialStore
import org.neighborhoodwatchdog.android.domain.model.Clock
import org.neighborhoodwatchdog.android.domain.model.ConnectivityEvent
import org.neighborhoodwatchdog.android.domain.model.ConnectivityState
import org.neighborhoodwatchdog.android.domain.model.Diagnostics
import org.neighborhoodwatchdog.android.domain.model.EventOutcome
import org.neighborhoodwatchdog.android.domain.model.EventResult
import org.neighborhoodwatchdog.android.domain.model.EventType
import org.neighborhoodwatchdog.android.domain.model.SubmitEventsResponse
import org.neighborhoodwatchdog.android.domain.model.Transport
import org.neighborhoodwatchdog.android.domain.reporting.EventFactory
import org.neighborhoodwatchdog.android.domain.reporting.EventQueueStore
import org.neighborhoodwatchdog.android.domain.reporting.EventTransport
import org.neighborhoodwatchdog.android.domain.reporting.QueuedEvent
import org.neighborhoodwatchdog.android.domain.reporting.SendResult
import org.neighborhoodwatchdog.android.domain.reporting.SentEventLog
import org.neighborhoodwatchdog.android.domain.reporting.SentRecord
import java.time.Instant

class InMemoryCredentialStore(var deviceToken: String? = null) : CredentialStore {
    override fun load(): String? = deviceToken
    override fun save(deviceToken: String) {
        this.deviceToken = deviceToken
    }

    override fun clear() {
        deviceToken = null
    }
}

class FakeClock(var now: Long = 1_800_000_000_000L) : Clock {
    override fun nowMs(): Long = now
}

/** In-memory queue with the same semantics as the Room store. */
class InMemoryQueueStore : EventQueueStore {
    val rows = LinkedHashMap<String, QueuedEvent>()

    override suspend fun insert(event: ConnectivityEvent, nowMs: Long): Boolean {
        if (event.eventId in rows) return false
        rows[event.eventId] = QueuedEvent(event, 0, nowMs)
        return true
    }

    private fun ordered() = rows.values.sortedBy { it.event.sequence }

    override suspend fun oldest(limit: Int): List<QueuedEvent> = ordered().take(limit)

    override suspend fun remove(eventIds: Collection<String>) {
        eventIds.forEach(rows::remove)
    }

    override suspend fun incrementAttempts(eventIds: Collection<String>) {
        eventIds.forEach { id -> rows[id]?.let { rows[id] = it.copy(attempts = it.attempts + 1) } }
    }

    override suspend fun count(): Int = rows.size

    override suspend fun deleteOccurredBefore(cutoffMs: Long): Int {
        val old = rows.values.filter { it.event.occurredAt.toEpochMilli() < cutoffMs }.map { it.event.eventId }
        old.forEach(rows::remove)
        return old.size
    }

    override suspend fun deleteOldest(types: Set<EventType>?, limit: Int): Int {
        val victims = ordered().filter { types == null || it.event.eventType in types }.take(limit).map { it.event.eventId }
        victims.forEach(rows::remove)
        return victims.size
    }

    override suspend fun clear() = rows.clear()
}

class InMemorySentLog : SentEventLog {
    val records = mutableListOf<SentRecord>()

    override suspend fun record(records: List<SentRecord>) {
        this.records += records
    }

    override suspend fun clear() = records.clear()
}

/** A fake API. It remembers accepted ids, so a resend is answered with `duplicate`, like the server. */
class FakeApi(private val reportedVia: Transport = Transport.WIFI) : EventTransport {
    data class Call(val eventIds: List<String>, val sentAt: Instant, val events: List<ConnectivityEvent>)

    val calls = mutableListOf<Call>()
    val stored = LinkedHashMap<String, ConnectivityEvent>()
    val scripted = ArrayDeque<(List<ConnectivityEvent>) -> SendResult>()

    /** When true the server stores the events but the response is lost on the way back. */
    var loseNextResponse = false

    override suspend fun send(events: List<ConnectivityEvent>, sentAt: Instant): SendResult {
        calls += Call(events.map { it.eventId }, sentAt, events)
        scripted.removeFirstOrNull()?.let { return it(events) }
        val outcomes = events.map { event ->
            if (event.eventId in stored) {
                EventOutcome(event.eventId, EventResult.DUPLICATE)
            } else {
                stored[event.eventId] = event
                EventOutcome(event.eventId, EventResult.ACCEPTED)
            }
        }
        if (loseNextResponse) {
            loseNextResponse = false
            return SendResult.NetworkFailure("response lost")
        }
        return SendResult.Delivered(SubmitEventsResponse(sentAt, outcomes), reportedVia)
    }
}

class TestEvents(private val clock: FakeClock) {
    private var sequence = 0L
    private var idCounter = 0
    val factory = EventFactory(
        sequence = { ++sequence },
        osVersion = "Android 14 (API 34)",
        appVersion = "0.1.0 (1)",
        newId = { "00000000-0000-4000-8000-%012d".format(++idCounter) },
    )

    fun event(type: EventType = EventType.WIFI_LOST, occurredAtMs: Long = clock.now): ConnectivityEvent =
        factory.create(type, ConnectivityState.WIFI_DISCONNECTED, occurredAtMs, Diagnostics())
}
