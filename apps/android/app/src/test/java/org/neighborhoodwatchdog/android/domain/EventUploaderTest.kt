package org.neighborhoodwatchdog.android.domain

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.neighborhoodwatchdog.android.domain.model.EventOutcome
import org.neighborhoodwatchdog.android.domain.model.EventResult
import org.neighborhoodwatchdog.android.domain.model.EventType
import org.neighborhoodwatchdog.android.domain.model.SubmitEventsResponse
import org.neighborhoodwatchdog.android.domain.model.Transport
import org.neighborhoodwatchdog.android.domain.reporting.BackoffPolicy
import org.neighborhoodwatchdog.android.domain.reporting.EventUploader
import org.neighborhoodwatchdog.android.domain.reporting.FlushResult
import org.neighborhoodwatchdog.android.domain.reporting.QueuePolicy
import org.neighborhoodwatchdog.android.domain.reporting.SendResult
import java.time.Instant

class EventUploaderTest {
    private val clock = FakeClock()
    private val store = InMemoryQueueStore()
    private val sentLog = InMemorySentLog()
    private val api = FakeApi()
    private val events = TestEvents(clock)
    private val delivered = mutableListOf<Long>()

    /** No jitter (random = 0.5 gives exactly the base delay), so delays can be asserted exactly. */
    private val uploader = EventUploader(
        store = store,
        sentLog = sentLog,
        transport = api,
        clock = clock,
        backoff = BackoffPolicy(random = { 0.5 }),
        onDelivered = { delivered += it },
    )

    @Test
    fun acceptedEventsLeaveTheQueueOldestFirst() = runTest {
        val first = events.event()
        val second = events.event(EventType.WIFI_RESTORED)
        uploader.enqueue(second)
        uploader.enqueue(first)
        assertEquals(FlushResult.Idle, uploader.flush())
        assertEquals(listOf(first.eventId, second.eventId), api.calls.single().eventIds)
        assertEquals(0, store.count())
        assertEquals(2, sentLog.records.size)
        assertEquals(Transport.WIFI, sentLog.records.first().reportedVia)
        assertEquals(listOf(clock.now), delivered)
    }

    @Test
    fun sameEventIdIsQueuedOnlyOnce() = runTest {
        val event = events.event()
        assertTrue(uploader.enqueue(event))
        assertFalse(uploader.enqueue(event))
        assertFalse(uploader.enqueue(event.copy(sequence = 99)))
        assertEquals(1, store.count())
    }

    @Test
    fun networkFailureKeepsEventsAndBacksOffExponentially() = runTest {
        uploader.enqueue(events.event())
        val expected = listOf(5_000L, 10_000L, 20_000L, 40_000L, 80_000L, 160_000L, 320_000L, 640_000L, 900_000L, 900_000L)
        for (delay in expected) {
            api.scripted += { SendResult.NetworkFailure("offline") }
            assertEquals(FlushResult.RetryIn(delay, "network"), uploader.flush())
        }
        assertEquals(1, store.count())
        assertEquals(expected.size, store.rows.values.single().attempts)
        // Connectivity is back: the event goes out and the backoff starts from 5 s again next time.
        assertEquals(FlushResult.Idle, uploader.flush())
        uploader.enqueue(events.event())
        api.scripted += { SendResult.ServerError(503) }
        assertEquals(FlushResult.RetryIn(5_000, "http_503"), uploader.flush())
    }

    @Test
    fun backoffHasJitterAndNeverExceedsTheCap() {
        val low = BackoffPolicy(random = { 0.0 })
        val high = BackoffPolicy(random = { 1.0 })
        assertEquals(4_000L, low.delayMs(1))
        assertEquals(6_000L, high.delayMs(1))
        assertEquals(8_000L, low.delayMs(2))
        assertEquals(15 * 60_000L, high.delayMs(20))
        assertTrue(low.delayMs(20) <= 15 * 60_000L)
        assertTrue(BackoffPolicy().delayMs(3) in 16_000L..24_000L)
    }

    @Test
    fun delayedUploadKeepsOccurredAtAndEventIdButUsesANewSentAt() = runTest {
        val occurredAt = clock.now
        val event = events.event(occurredAtMs = occurredAt)
        uploader.enqueue(event)
        api.scripted += { SendResult.NetworkFailure("no route") }
        uploader.flush()

        clock.now += 3 * 60 * 60_000 // three hours without any network
        assertEquals(FlushResult.Idle, uploader.flush())

        val (failed, succeeded) = api.calls
        assertEquals(failed.eventIds, succeeded.eventIds)
        assertEquals(Instant.ofEpochMilli(occurredAt), failed.events.single().occurredAt)
        assertEquals(Instant.ofEpochMilli(occurredAt), succeeded.events.single().occurredAt)
        assertEquals(Instant.ofEpochMilli(occurredAt), failed.sentAt)
        assertEquals(Instant.ofEpochMilli(occurredAt + 3 * 60 * 60_000), succeeded.sentAt)
        assertEquals(event, api.stored.values.single())
        assertEquals(occurredAt + 3 * 60 * 60_000, sentLog.records.single().sentAtMs)
    }

    @Test
    fun lostResponseIsResentWithTheSameIdAndSettledAsDuplicate() = runTest {
        val event = events.event()
        uploader.enqueue(event)
        api.loseNextResponse = true
        assertTrue(uploader.flush() is FlushResult.RetryIn)
        assertEquals("kept because no outcome was received", 1, store.count())

        assertEquals(FlushResult.Idle, uploader.flush())
        assertEquals(0, store.count())
        assertEquals("the server stored it once", 1, api.stored.size)
        assertEquals("duplicate", sentLog.records.single().result)
    }

    @Test
    fun rejectedEventsAreRemovedExceptRateLimitedOnes() = runTest {
        val bad = events.event()
        val limited = events.event()
        val good = events.event()
        listOf(bad, limited, good).forEach { uploader.enqueue(it) }
        api.scripted += { batch ->
            SendResult.Delivered(
                SubmitEventsResponse(
                    serverTime = Instant.ofEpochMilli(clock.now),
                    outcomes = listOf(
                        EventOutcome(batch[0].eventId, EventResult.REJECTED, "occurred_at_too_old"),
                        EventOutcome(batch[1].eventId, EventResult.REJECTED, "rate_limited"),
                        EventOutcome(batch[2].eventId, EventResult.ACCEPTED),
                    ),
                    retryAfterSeconds = 120,
                ),
                Transport.CELLULAR,
            )
        }
        assertEquals("retry_after_seconds is honoured", FlushResult.RetryIn(120_000, "rate_limited"), uploader.flush())
        assertEquals(listOf(limited.eventId), store.rows.keys.toList())
        assertEquals(listOf("rejected", "accepted"), sentLog.records.map { it.result })
        assertEquals("occurred_at_too_old", sentLog.records.first().reason)
        assertEquals(Transport.CELLULAR, sentLog.records.first().reportedVia)
    }

    @Test
    fun http429HonoursRetryAfterWhenLongerThanTheBackoff() = runTest {
        uploader.enqueue(events.event())
        api.scripted += { SendResult.RateLimited(retryAfterSeconds = 60) }
        assertEquals(FlushResult.RetryIn(60_000, "rate_limited"), uploader.flush())
        api.scripted += { SendResult.RateLimited(retryAfterSeconds = 1) }
        assertEquals("never shorter than the backoff", FlushResult.RetryIn(10_000, "rate_limited"), uploader.flush())
        assertEquals(1, store.count())
    }

    @Test
    fun moreThanFiftyEventsGoInSeveralRequests() = runTest {
        repeat(120) { uploader.enqueue(events.event(EventType.HEARTBEAT)) }
        assertEquals(FlushResult.Idle, uploader.flush())
        assertEquals(listOf(50, 50, 20), api.calls.map { it.eventIds.size })
        val sequences = api.calls.flatMap { it.events }.map { it.sequence }
        assertEquals(sequences.sorted(), sequences)
    }

    @Test
    fun onePoisonEventDoesNotBlockTheOthers() = runTest {
        val good1 = events.event()
        val poison = events.event()
        val good2 = events.event()
        listOf(good1, poison, good2).forEach { uploader.enqueue(it) }
        // The whole batch is refused with 422, then each event is sent on its own.
        api.scripted += { SendResult.Rejected(422, "validation_error") }
        api.scripted += { batch -> accept(batch.map { it.eventId }) }
        api.scripted += { SendResult.Rejected(422, "validation_error") }
        api.scripted += { batch -> accept(batch.map { it.eventId }) }
        assertEquals(FlushResult.Idle, uploader.flush())
        assertEquals(0, store.count())
        assertEquals(listOf(3, 1, 1, 1), api.calls.map { it.eventIds.size })
        val dropped = sentLog.records.single { it.event.eventId == poison.eventId }
        assertEquals("rejected", dropped.result)
        assertEquals("validation_error", dropped.reason)
    }

    @Test
    fun revokedDeviceAndExpiredSessionBlockWithoutLosingEvents() = runTest {
        uploader.enqueue(events.event())
        api.scripted += { SendResult.Rejected(403, "device_revoked") }
        val revoked = uploader.flush()
        assertTrue(revoked is FlushResult.Blocked && revoked.errorCode == "device_revoked")
        api.scripted += { SendResult.Unauthorized }
        val unauthorized = uploader.flush()
        assertTrue(unauthorized is FlushResult.Blocked && unauthorized.errorCode == "unauthorized")
        assertEquals(1, store.count())
        assertTrue(sentLog.records.isEmpty())
    }

    @Test
    fun answerWithoutOutcomesDoesNotSpin() = runTest {
        uploader.enqueue(events.event())
        api.scripted += { SendResult.Delivered(SubmitEventsResponse(Instant.ofEpochMilli(clock.now)), Transport.WIFI) }
        assertEquals(FlushResult.RetryIn(5_000, "no_outcome"), uploader.flush())
        assertEquals(1, api.calls.size)
        assertEquals(1, store.count())
    }

    @Test
    fun queueCapDropsHeartbeatsFirstAndKeepsFailureEvents() = runTest {
        repeat(1_500) { store.insert(events.event(EventType.HEARTBEAT), clock.now) }
        repeat(400) { store.insert(events.event(EventType.MONITORING_RESUMED), clock.now) }
        repeat(600) { store.insert(events.event(EventType.WIFI_LOST), clock.now) }
        val oldestHeartbeat = store.rows.values.first().event
        assertEquals(500, QueuePolicy.enforce(store, clock.now))
        assertEquals(QueuePolicy.MAX_EVENTS, store.count())
        assertFalse("the oldest heartbeat went first", oldestHeartbeat.eventId in store.rows)
        val byType = store.rows.values.groupingBy { it.event.eventType }.eachCount()
        assertEquals(1_000, byType[EventType.HEARTBEAT])
        assertEquals(400, byType[EventType.MONITORING_RESUMED])
        assertEquals(600, byType[EventType.WIFI_LOST])
    }

    @Test
    fun queueCapDropsLowPriorityEventsOnlyAfterHeartbeatsAreGone() = runTest {
        repeat(100) { store.insert(events.event(EventType.HEARTBEAT), clock.now) }
        repeat(300) { store.insert(events.event(EventType.MONITORING_RESUMED), clock.now) }
        repeat(1_900) { store.insert(events.event(EventType.WIFI_LOST), clock.now) }
        QueuePolicy.enforce(store, clock.now)
        val byType = store.rows.values.groupingBy { it.event.eventType }.eachCount()
        assertEquals(null, byType[EventType.HEARTBEAT])
        assertEquals(100, byType[EventType.MONITORING_RESUMED])
        assertEquals("failure events inside the cap are never dropped", 1_900, byType[EventType.WIFI_LOST])
    }

    @Test
    fun eventsOlderThanSevenDaysAreDropped() = runTest {
        val old = events.event(occurredAtMs = clock.now - QueuePolicy.MAX_AGE_MS - 1)
        val recent = events.event(occurredAtMs = clock.now - QueuePolicy.MAX_AGE_MS + 60_000)
        store.insert(old, clock.now)
        store.insert(recent, clock.now)
        assertEquals(FlushResult.Idle, uploader.flush())
        assertEquals(listOf(recent.eventId), api.calls.single().eventIds)
    }

    private fun accept(ids: List<String>): SendResult = SendResult.Delivered(
        SubmitEventsResponse(Instant.ofEpochMilli(clock.now), ids.map { EventOutcome(it, EventResult.ACCEPTED) }),
        Transport.WIFI,
    )
}
