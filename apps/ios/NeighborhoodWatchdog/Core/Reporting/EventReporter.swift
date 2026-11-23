import Foundation

/// Uploads queued events, oldest first, in batches of up to 50.
///
/// - An event leaves the queue only when the server said `accepted`, `duplicate`, or `rejected`
///   with a reason other than `rate_limited`.
/// - A network failure, an HTTP 5xx or a rate limit keeps the events and schedules a retry with
///   exponential backoff and jitter. `retry_after_seconds` / `Retry-After` is honoured.
/// - `event_id` and `occurred_at` are never touched here; `sent_at` is the time of each attempt,
///   so a delayed upload still carries the original occurrence time.
actor EventReporter {
    struct Dependencies: Sendable {
        var submit: @Sendable (SubmitEventsRequest) async throws -> SubmitEventsResponse
        /// The install's device id, nil when the device is not registered yet.
        var deviceID: @Sendable () async -> UUID?
        /// Interface type of the current network path at send time ("unknown" when unclear).
        var currentTransport: @Sendable () async -> Transport
        /// Called once per flush when the server says the device is not registered.
        var reRegisterDevice: @Sendable () async -> Bool = { false }
        var now: @Sendable () -> Date = { Date() }
        var random: @Sendable () -> Double = { Double.random(in: 0...1) }
    }

    struct Status: Sendable, Equatable {
        var queuedCount = 0
        var lastAttemptAt: Date?
        var lastSuccessAt: Date?
        var lastError: String?
        /// Contract error code of the last failure when the server gave one.
        var lastErrorCode: String?
        var nextRetryAt: Date?
        var consecutiveFailures = 0
        /// How the last successful upload left the phone, as far as the app could tell.
        var lastReportedVia: Transport?
    }

    struct FlushResult: Sendable, Equatable {
        /// Events removed from the queue during this flush (delivered or finally rejected).
        var removed = 0
        var remaining = 0
        var nextRetryAt: Date?
    }

    static let batchSize = 50

    private let queue: EventQueue
    private let sentLog: SentLog
    private let dependencies: Dependencies
    private let backoff: BackoffPolicy
    private let schedulesRetries: Bool

    private var status = Status()
    private var rateLimitedUntil: Date?
    private var isFlushing = false
    private var flushRequestedWhileBusy = false
    private var retryTask: Task<Void, Never>?
    private var onChange: (@Sendable () async -> Void)?

    init(queue: EventQueue, sentLog: SentLog, dependencies: Dependencies,
         backoff: BackoffPolicy = BackoffPolicy(), schedulesRetries: Bool = true) {
        self.queue = queue
        self.sentLog = sentLog
        self.dependencies = dependencies
        self.backoff = backoff
        self.schedulesRetries = schedulesRetries
    }

    func setOnChange(_ handler: (@Sendable () async -> Void)?) {
        onChange = handler
    }

    func currentStatus() async -> Status {
        var copy = status
        copy.queuedCount = await queue.count
        copy.lastSuccessAt = await sentLog.lastSuccessfulReportAt
        return copy
    }

    /// A connectivity callback arrived: a waiting retry may go now instead of at the end of its
    /// backoff. A rate-limit wait requested by the server is still respected.
    @discardableResult
    func connectivityChanged() async -> FlushResult {
        await flush(ignoreBackoff: true)
    }

    func reset() {
        retryTask?.cancel()
        retryTask = nil
        status = Status()
        rateLimitedUntil = nil
    }

    @discardableResult
    func flush(ignoreBackoff: Bool = false) async -> FlushResult {
        if isFlushing {
            flushRequestedWhileBusy = true
            return FlushResult(removed: 0, remaining: await queue.count, nextRetryAt: status.nextRetryAt)
        }
        isFlushing = true
        var result = FlushResult()
        var skipBackoff = ignoreBackoff
        repeat {
            flushRequestedWhileBusy = false
            result.removed += await runFlush(ignoreBackoff: skipBackoff)
            skipBackoff = false
        } while flushRequestedWhileBusy && status.nextRetryAt == nil
        isFlushing = false

        result.remaining = await queue.count
        result.nextRetryAt = status.nextRetryAt
        scheduleRetryIfNeeded()
        await onChange?()
        return result
    }

    // MARK: Internals

    private enum BatchOutcome {
        case progressed(removed: Int)
        case rateLimited(removed: Int, retryAfter: TimeInterval?)
        case failed(APIError, removed: Int)
    }

    private func runFlush(ignoreBackoff: Bool) async -> Int {
        var removedTotal = 0
        var reRegistered = false
        while true {
            let now = dependencies.now()
            if let until = rateLimitedUntil {
                if now < until { break }
                rateLimitedUntil = nil
            }
            if !ignoreBackoff || removedTotal > 0, let next = status.nextRetryAt, now < next { break }
            guard let deviceID = await dependencies.deviceID() else { break }
            let batch = await queue.nextBatch(limit: Self.batchSize, now: now)
            guard !batch.isEmpty else {
                status.nextRetryAt = nil
                break
            }
            status.lastAttemptAt = now

            switch await send(batch, deviceID: deviceID) {
            case let .progressed(removed):
                removedTotal += removed
                status.consecutiveFailures = 0
                status.nextRetryAt = nil
                status.lastError = nil
                status.lastErrorCode = nil
                continue
            case let .rateLimited(removed, retryAfter):
                removedTotal += removed
                noteFailure(message: "The service asked this phone to slow down. Events are kept and sent later.",
                            code: "rate_limited", retryAfter: retryAfter, isRateLimit: true)
            case let .failed(error, removed):
                removedTotal += removed
                if error.code == "device_not_registered", !reRegistered {
                    reRegistered = true
                    if await dependencies.reRegisterDevice() { continue }
                }
                noteFailure(message: error.userMessage, code: error.code, retryAfter: retryAfterValue(of: error),
                            isRateLimit: error.status == 429)
            }
            break
        }
        return removedTotal
    }

    private func retryAfterValue(of error: APIError) -> TimeInterval? {
        if case let .server(_, _, _, _, retryAfter) = error { return retryAfter }
        return nil
    }

    private func noteFailure(message: String, code: String?, retryAfter: TimeInterval?, isRateLimit: Bool) {
        let now = dependencies.now()
        let delay = max(backoff.delay(attempt: status.consecutiveFailures, random: dependencies.random()), retryAfter ?? 0)
        status.consecutiveFailures += 1
        status.nextRetryAt = now.addingTimeInterval(delay)
        status.lastError = message
        status.lastErrorCode = code
        if isRateLimit { rateLimitedUntil = status.nextRetryAt }
    }

    private func send(_ batch: [ConnectivityEvent], deviceID: UUID) async -> BatchOutcome {
        let sentAt = dependencies.now()
        let via = await dependencies.currentTransport()
        let request = SubmitEventsRequest(deviceID: deviceID, reportedVia: via, sentAt: sentAt, events: batch)
        await queue.recordAttempt(ids: Set(batch.map(\.eventID)))

        let response: SubmitEventsResponse
        do {
            response = try await dependencies.submit(request)
        } catch {
            let apiError = APIError.from(error)
            guard apiError.status == 422 else { return .failed(apiError, removed: 0) }
            // The server refused the request body. One bad event must not block the queue forever.
            if batch.count > 1 {
                var removed = 0
                for event in batch {
                    switch await send([event], deviceID: deviceID) {
                    case let .progressed(count): removed += count
                    case let .rateLimited(count, retryAfter): return .rateLimited(removed: removed + count, retryAfter: retryAfter)
                    case let .failed(inner, count): return .failed(inner, removed: removed + count)
                    }
                }
                return .progressed(removed: removed)
            }
            let reason = "validation_error" + (apiError.userMessage.isEmpty ? "" : ": \(apiError.userMessage)")
            await queue.remove(ids: [batch[0].eventID])
            await sentLog.append([SentRecord(event: batch[0], sentAt: sentAt, reportedVia: via, result: .rejected, reason: reason)],
                                 reportSucceededAt: nil)
            return .progressed(removed: 1)
        }

        let byID = Dictionary(batch.map { ($0.eventID, $0) }, uniquingKeysWith: { first, _ in first })
        var remove = Set<UUID>()
        var records: [SentRecord] = []
        var rateLimited = false
        var delivered = false
        for outcome in response.outcomes {
            guard let event = byID[outcome.eventID] else { continue }
            switch outcome.result {
            case .accepted, .duplicate:
                delivered = true
            case .rejected:
                if outcome.reason == "rate_limited" {
                    rateLimited = true
                    continue
                }
            }
            remove.insert(event.eventID)
            records.append(SentRecord(event: event, sentAt: sentAt, reportedVia: via, result: outcome.result, reason: outcome.reason))
        }
        await queue.remove(ids: remove)
        if !records.isEmpty {
            await sentLog.append(records, reportSucceededAt: delivered ? sentAt : nil)
        }
        if delivered { status.lastReportedVia = via }

        if rateLimited || response.retryAfterSeconds != nil, remove.count < batch.count {
            return .rateLimited(removed: remove.count, retryAfter: response.retryAfterSeconds.map(TimeInterval.init))
        }
        if remove.isEmpty {
            // A 2xx answer that settles nothing: treat it as a failure so the loop cannot spin.
            return .failed(.decoding("The service returned no outcome for the submitted events."), removed: 0)
        }
        return .progressed(removed: remove.count)
    }

    private func scheduleRetryIfNeeded() {
        retryTask?.cancel()
        retryTask = nil
        guard schedulesRetries, let next = status.nextRetryAt else { return }
        let delay = max(0.5, next.timeIntervalSince(dependencies.now()))
        retryTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000))
            guard !Task.isCancelled else { return }
            await self?.flush()
        }
    }
}
