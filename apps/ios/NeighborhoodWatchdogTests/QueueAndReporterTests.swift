import XCTest
@testable import NeighborhoodWatchdog

final class BackoffTests: XCTestCase {
    func testExponentialWithCap() {
        let policy = BackoffPolicy()
        // random 0.5 means "no jitter".
        XCTAssertEqual(policy.delay(attempt: 0, random: 0.5), 5, accuracy: 0.001)
        XCTAssertEqual(policy.delay(attempt: 1, random: 0.5), 10, accuracy: 0.001)
        XCTAssertEqual(policy.delay(attempt: 2, random: 0.5), 20, accuracy: 0.001)
        XCTAssertEqual(policy.delay(attempt: 7, random: 0.5), 640, accuracy: 0.001)
        XCTAssertEqual(policy.delay(attempt: 8, random: 0.5), 900, accuracy: 0.001)
        XCTAssertEqual(policy.delay(attempt: 500, random: 1), 900, accuracy: 0.001, "never above 15 minutes")
    }

    func testJitterStaysInsideItsBand() {
        let policy = BackoffPolicy()
        XCTAssertEqual(policy.delay(attempt: 2, random: 0), 16, accuracy: 0.001)
        XCTAssertEqual(policy.delay(attempt: 2, random: 1), 24, accuracy: 0.001)
        for _ in 0..<200 {
            let delay = policy.delay(attempt: 3)
            XCTAssertGreaterThanOrEqual(delay, 32)
            XCTAssertLessThanOrEqual(delay, 48)
        }
    }
}

private let t0 = Date(timeIntervalSince1970: 1_800_000_000)

final class EventQueueTests: XCTestCase {
    func testEventsSurviveANewQueueOnTheSameStorage() async {
        let directory = TestFiles.temporaryDirectory()
        let first = EventQueue(directory: directory, now: t0)
        let a = await first.enqueue(now: t0) { makeEvent(.wifiLost, sequence: $0, at: t0) }
        let b = await first.enqueue(now: t0) { makeEvent(.wifiRestored, sequence: $0, at: t0) }
        XCTAssertEqual([a.sequence, b.sequence], [0, 1])

        let second = EventQueue(directory: directory, now: t0)
        let restored = await second.entries().map(\.event)
        XCTAssertEqual(restored, [a, b], "same ids, same order, same occurred_at")
        let c = await second.enqueue(now: t0) { makeEvent(.heartbeat, sequence: $0, at: t0) }
        XCTAssertEqual(c.sequence, 2, "the sequence counter continues")
    }

    func testFileIsWrittenBeforeEnqueueReturns() async throws {
        let directory = TestFiles.temporaryDirectory()
        let queue = EventQueue(directory: directory, now: t0)
        let event = await queue.enqueue(now: t0) { makeEvent(sequence: $0, at: t0) }
        let data = try Data(contentsOf: directory.appendingPathComponent("event-queue.json"))
        XCTAssertTrue(String(decoding: data, as: UTF8.self).contains(event.eventID.uuidString))
        let error = await queue.lastPersistenceError
        XCTAssertNil(error)
    }

    func testCountCapDropsOldestHeartbeatsFirstAndKeepsFailureEvents() async {
        let queue = EventQueue(directory: TestFiles.temporaryDirectory(), limits: .init(maxCount: 4, maxAge: 7 * 86_400), now: t0)
        let kinds: [EventType] = [.heartbeat, .wifiLost, .heartbeat, .monitoringResumed, .wifiRestored, .heartbeat, .internetReachabilityChanged]
        for kind in kinds {
            await queue.enqueue(now: t0) { makeEvent(kind, sequence: $0, at: t0) }
        }
        let kept = await queue.entries().map(\.event)
        XCTAssertEqual(kept.count, 4)
        XCTAssertEqual(kept.map(\.eventType), [.wifiLost, .monitoringResumed, .wifiRestored, .internetReachabilityChanged])
        XCTAssertEqual(kept.map(\.sequence), [1, 3, 4, 6], "order is preserved")

        // Still over the cap with no heartbeats left: lifecycle events go before connectivity events.
        await queue.enqueue(now: t0) { makeEvent(.cellularUnavailable, sequence: $0, at: t0) }
        let afterwards = await queue.entries().map(\.event.eventType)
        XCTAssertEqual(afterwards, [.wifiLost, .wifiRestored, .internetReachabilityChanged, .cellularUnavailable])
    }

    func testAgeCapDropsEventsOlderThanSevenDays() async {
        let queue = EventQueue(directory: TestFiles.temporaryDirectory(), now: t0)
        await queue.enqueue(now: t0) { makeEvent(.wifiLost, sequence: $0, at: t0) }
        let later = t0.addingTimeInterval(6 * 86_400)
        await queue.enqueue(now: later) { makeEvent(.wifiRestored, sequence: $0, at: later) }
        let batch = await queue.nextBatch(now: t0.addingTimeInterval(7 * 86_400 + 60))
        XCTAssertEqual(batch.map(\.eventType), [.wifiRestored])
    }

    func testUnreadableFileIsSetAsideAndSequenceStaysIncreasing() async throws {
        let directory = TestFiles.temporaryDirectory()
        try Data("not json".utf8).write(to: directory.appendingPathComponent("event-queue.json"))
        let queue = EventQueue(directory: directory, now: t0)
        let count = await queue.count
        XCTAssertEqual(count, 0)
        let event = await queue.enqueue(now: t0) { makeEvent(sequence: $0, at: t0) }
        XCTAssertGreaterThan(event.sequence, 1_000_000)
        XCTAssertTrue(FileManager.default.fileExists(atPath: directory.appendingPathComponent("event-queue.unreadable.json").path))
    }
}

final class EventReporterTests: XCTestCase {
    private struct Rig {
        let clock = TestClock()
        let directory = TestFiles.temporaryDirectory()
        let queue: EventQueue
        let sentLog: SentLog
        let requests = Locked<[SubmitEventsRequest]>([])
        let transport = Locked<Transport>(.cellular)
        /// What the fake server does with a request.
        let behaviour = Locked<@Sendable (SubmitEventsRequest) throws -> SubmitEventsResponse>({ request in
            SubmitEventsResponse(serverTime: request.sentAt,
                                 outcomes: request.events.map { EventOutcome(eventID: $0.eventID, result: .accepted, reason: nil) },
                                 retryAfterSeconds: nil)
        })
        let deviceID = UUID()
        let reRegistrations = Locked(0)

        init() {
            queue = EventQueue(directory: directory, now: clock.now)
            sentLog = SentLog(directory: directory)
        }

        func makeReporter() -> EventReporter {
            EventReporter(queue: queue, sentLog: sentLog, dependencies: .init(
                submit: { [requests, behaviour] request in
                    requests.mutate { $0.append(request) }
                    return try behaviour.value(request)
                },
                deviceID: { [deviceID] in deviceID },
                currentTransport: { [transport] in transport.value },
                reRegisterDevice: { [reRegistrations] in reRegistrations.mutate { $0 += 1 }; return true },
                now: clock.provider,
                random: { 0.5 }), schedulesRetries: false)
        }

        @discardableResult
        func enqueue(_ type: EventType = .wifiLost) async -> ConnectivityEvent {
            let now = clock.now
            return await queue.enqueue(now: now) { makeEvent(type, sequence: $0, at: now) }
        }
    }

    func testDelayedUploadKeepsOccurredAtAndEventID() async {
        let rig = Rig()
        let reporter = rig.makeReporter()
        let occurred = rig.clock.now
        let event = await rig.enqueue(.wifiLost)

        // No network: the event stays and a retry is scheduled 5 seconds later.
        rig.behaviour.value = { _ in throw URLError(.notConnectedToInternet) }
        var result = await reporter.flush()
        XCTAssertEqual(result.remaining, 1)
        XCTAssertEqual(result.nextRetryAt, occurred.addingTimeInterval(5))

        // Before the backoff has passed nothing is sent.
        rig.clock.advance(3)
        result = await reporter.flush()
        XCTAssertEqual(rig.requests.value.count, 1)

        // Second failure doubles the delay.
        rig.clock.advance(3)
        result = await reporter.flush()
        XCTAssertEqual(rig.requests.value.count, 2)
        XCTAssertEqual(result.nextRetryAt, rig.clock.now.addingTimeInterval(10))

        // Forty minutes later the network is back.
        rig.clock.advance(2_400)
        rig.behaviour.value = { request in
            SubmitEventsResponse(serverTime: request.sentAt,
                                 outcomes: request.events.map { EventOutcome(eventID: $0.eventID, result: .accepted, reason: nil) },
                                 retryAfterSeconds: nil)
        }
        result = await reporter.flush()
        XCTAssertEqual(result.remaining, 0)
        XCTAssertNil(result.nextRetryAt)

        let requests = rig.requests.value
        XCTAssertEqual(requests.count, 3)
        XCTAssertTrue(requests.allSatisfy { $0.events == [event] }, "the very same event every time")
        XCTAssertTrue(requests.allSatisfy { $0.events[0].eventID == event.eventID })
        XCTAssertTrue(requests.allSatisfy { $0.events[0].occurredAt == occurred }, "occurred_at never changes")
        XCTAssertEqual(requests[2].sentAt, rig.clock.now, "sent_at is the time of this attempt")
        XCTAssertNotEqual(requests[0].sentAt, requests[2].sentAt)
        XCTAssertEqual(requests[2].deviceID, rig.deviceID)

        let sent = await rig.sentLog.records()
        XCTAssertEqual(sent.map(\.event), [event])
        XCTAssertEqual(sent[0].sentAt, rig.clock.now)
        let lastSuccess = await rig.sentLog.lastSuccessfulReportAt
        XCTAssertEqual(lastSuccess, rig.clock.now)
        let attempts = await rig.queue.entries().count
        XCTAssertEqual(attempts, 0)
    }

    func testAcceptedAndDuplicateAreRemovedAndNeverSentAgain() async {
        let rig = Rig()
        let reporter = rig.makeReporter()
        let first = await rig.enqueue(.wifiLost)
        let second = await rig.enqueue(.wifiRestored)
        rig.behaviour.value = { request in
            SubmitEventsResponse(serverTime: request.sentAt, outcomes: [
                EventOutcome(eventID: request.events[0].eventID, result: .accepted, reason: nil),
                // The server already had this one (an earlier attempt got through but its answer was lost).
                EventOutcome(eventID: request.events[1].eventID, result: .duplicate, reason: nil),
            ], retryAfterSeconds: nil)
        }
        let result = await reporter.flush()
        XCTAssertEqual(result.removed, 2)
        XCTAssertEqual(result.remaining, 0)
        _ = await reporter.flush()
        _ = await reporter.flush(ignoreBackoff: true)
        XCTAssertEqual(rig.requests.value.count, 1, "nothing left to send, so no second request")
        let sent = await rig.sentLog.records()
        XCTAssertEqual(Set(sent.map(\.event.eventID)), [first.eventID, second.eventID])
        XCTAssertEqual(Set(sent.map(\.result)), [.accepted, .duplicate])
    }

    func testAnswerLostThenRetryUsesTheSameEventID() async {
        let rig = Rig()
        let reporter = rig.makeReporter()
        let event = await rig.enqueue()
        // The request reached the server but the response timed out.
        rig.behaviour.value = { _ in throw URLError(.timedOut) }
        _ = await reporter.flush()
        rig.clock.advance(6)
        rig.behaviour.value = { request in
            SubmitEventsResponse(serverTime: request.sentAt,
                                 outcomes: [EventOutcome(eventID: request.events[0].eventID, result: .duplicate, reason: nil)],
                                 retryAfterSeconds: nil)
        }
        let result = await reporter.flush()
        XCTAssertEqual(result.remaining, 0)
        XCTAssertEqual(rig.requests.value.map { $0.events[0].eventID }, [event.eventID, event.eventID])
    }

    func testRejectedEventIsDroppedButRateLimitedIsKept() async {
        let rig = Rig()
        let reporter = rig.makeReporter()
        let bad = await rig.enqueue(.wifiLost)
        let limited = await rig.enqueue(.wifiRestored)
        rig.behaviour.value = { request in
            SubmitEventsResponse(serverTime: request.sentAt, outcomes: request.events.map { event in
                event.eventID == bad.eventID
                    ? EventOutcome(eventID: event.eventID, result: .rejected, reason: "too_old")
                    : EventOutcome(eventID: event.eventID, result: .rejected, reason: "rate_limited")
            }, retryAfterSeconds: 120)
        }
        let start = rig.clock.now
        let result = await reporter.flush()
        XCTAssertEqual(result.remaining, 1)
        let remaining = await rig.queue.entries().map(\.event.eventID)
        XCTAssertEqual(remaining, [limited.eventID])
        XCTAssertEqual(result.nextRetryAt, start.addingTimeInterval(120), "retry_after_seconds is honoured")
        let log = await rig.sentLog.records()
        XCTAssertEqual(log.map(\.reason), ["too_old"])
        XCTAssertEqual(log.map(\.result), [.rejected])
        let lastSuccess = await rig.sentLog.lastSuccessfulReportAt
        XCTAssertNil(lastSuccess, "a rejection is not a successful report")

        // A connectivity callback does not override a rate limit set by the server.
        rig.clock.advance(30)
        await reporter.connectivityChanged()
        XCTAssertEqual(rig.requests.value.count, 1)
        rig.clock.advance(100)
        _ = await reporter.flush()
        XCTAssertEqual(rig.requests.value.count, 2)
    }

    func testServerErrorKeepsEventsAndHonoursRetryAfterHeader() async {
        let rig = Rig()
        let reporter = rig.makeReporter()
        await rig.enqueue()
        rig.behaviour.value = { _ in
            throw APIError.server(status: 503, code: "http_503", message: "", requestID: nil, retryAfter: 60)
        }
        let start = rig.clock.now
        var result = await reporter.flush()
        XCTAssertEqual(result.remaining, 1)
        XCTAssertEqual(result.nextRetryAt, start.addingTimeInterval(60))

        rig.behaviour.value = { _ in
            throw APIError.server(status: 429, code: "rate_limited", message: "", requestID: nil, retryAfter: 300)
        }
        rig.clock.advance(61)
        result = await reporter.flush()
        XCTAssertEqual(result.remaining, 1)
        XCTAssertEqual(result.nextRetryAt, rig.clock.now.addingTimeInterval(300))
        let status = await reporter.currentStatus()
        XCTAssertEqual(status.consecutiveFailures, 2)
        XCTAssertEqual(status.lastErrorCode, "rate_limited")
    }

    func testConnectivityCallbackRetriesBeforeTheBackoffEnds() async {
        let rig = Rig()
        let reporter = rig.makeReporter()
        await rig.enqueue()
        rig.behaviour.value = { _ in throw URLError(.networkConnectionLost) }
        _ = await reporter.flush()
        rig.behaviour.value = { request in
            SubmitEventsResponse(serverTime: request.sentAt,
                                 outcomes: request.events.map { EventOutcome(eventID: $0.eventID, result: .accepted, reason: nil) },
                                 retryAfterSeconds: nil)
        }
        rig.clock.advance(1)
        let result = await reporter.connectivityChanged()
        XCTAssertEqual(result.remaining, 0)
        XCTAssertEqual(rig.requests.value.count, 2)
    }

    func testBatchesOfFiftyOldestFirst() async {
        let rig = Rig()
        let reporter = rig.makeReporter()
        for _ in 0..<120 { await rig.enqueue(.heartbeat) }
        let result = await reporter.flush()
        XCTAssertEqual(result.removed, 120)
        let requests = rig.requests.value
        XCTAssertEqual(requests.map(\.events.count), [50, 50, 20])
        XCTAssertEqual(requests.flatMap(\.events).map(\.sequence), Array(0..<120))
        let log = await rig.sentLog.records()
        XCTAssertEqual(log.count, 50, "the sent log keeps the last 50")
        XCTAssertEqual(log.first?.event.sequence, 119, "newest first")
    }

    func testReportedViaComesFromThePathAtSendTime() async {
        let rig = Rig()
        let reporter = rig.makeReporter()
        await rig.enqueue()
        rig.transport.value = .cellular
        _ = await reporter.flush()
        await rig.enqueue()
        rig.transport.value = .unknown
        _ = await reporter.flush()
        XCTAssertEqual(rig.requests.value.map(\.reportedVia), [.cellular, .unknown])
        let status = await reporter.currentStatus()
        XCTAssertEqual(status.lastReportedVia, .unknown)
    }

    func testValidationErrorIsolatesTheBadEvent() async {
        let rig = Rig()
        let reporter = rig.makeReporter()
        let good = await rig.enqueue(.wifiLost)
        let bad = await rig.enqueue(.wifiRestored)
        rig.behaviour.value = { request in
            if request.events.contains(where: { $0.eventID == bad.eventID }) {
                throw APIError.server(status: 422, code: "validation_error", message: "bad field", requestID: nil, retryAfter: nil)
            }
            return SubmitEventsResponse(serverTime: request.sentAt,
                                        outcomes: request.events.map { EventOutcome(eventID: $0.eventID, result: .accepted, reason: nil) },
                                        retryAfterSeconds: nil)
        }
        let result = await reporter.flush()
        XCTAssertEqual(result.remaining, 0, "one refused event must not block the queue")
        let log = await rig.sentLog.records()
        XCTAssertEqual(log.first { $0.event.eventID == good.eventID }?.result, .accepted)
        XCTAssertEqual(log.first { $0.event.eventID == bad.eventID }?.result, .rejected)
    }

    func testDeviceNotRegisteredTriggersOneReRegistration() async {
        let rig = Rig()
        let reporter = rig.makeReporter()
        await rig.enqueue()
        let calls = Locked(0)
        rig.behaviour.value = { request in
            if calls.mutate({ $0 += 1; return $0 }) == 1 {
                throw APIError.server(status: 404, code: "device_not_registered", message: "", requestID: nil, retryAfter: nil)
            }
            return SubmitEventsResponse(serverTime: request.sentAt,
                                        outcomes: request.events.map { EventOutcome(eventID: $0.eventID, result: .accepted, reason: nil) },
                                        retryAfterSeconds: nil)
        }
        let result = await reporter.flush()
        XCTAssertEqual(result.remaining, 0)
        XCTAssertEqual(rig.reRegistrations.value, 1)
    }

    func testConcurrentFlushesDoNotSendAnEventTwice() async {
        let rig = Rig()
        let reporter = rig.makeReporter()
        for _ in 0..<10 { await rig.enqueue() }
        await withTaskGroup(of: Void.self) { group in
            for _ in 0..<8 { group.addTask { _ = await reporter.flush(ignoreBackoff: true) } }
        }
        let sentIDs = rig.requests.value.flatMap(\.events).map(\.eventID)
        XCTAssertEqual(sentIDs.count, 10)
        XCTAssertEqual(Set(sentIDs).count, 10)
    }
}
