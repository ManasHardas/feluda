import Foundation

/// A small JSON file written atomically with file protection
/// `completeUntilFirstUserAuthentication`, so it can be read and written in the background once
/// the phone has been unlocked one time after a restart.
struct JSONFileStore<Value: Codable & Sendable>: Sendable {
    let url: URL

    func load() throws -> Value? {
        guard FileManager.default.fileExists(atPath: url.path) else { return nil }
        let data = try Data(contentsOf: url)
        return try WatchdogJSON.decoder().decode(Value.self, from: data)
    }

    func save(_ value: Value) throws {
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        let data = try WatchdogJSON.encoder().encode(value)
        try data.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }

    func delete() {
        try? FileManager.default.removeItem(at: url)
    }
}

/// Durable, ordered queue of events waiting for upload. Every event is written to disk before
/// any network attempt. Also owns the per-device sequence counter.
actor EventQueue {
    struct Entry: Codable, Sendable, Equatable, Identifiable {
        let event: ConnectivityEvent
        let enqueuedAt: Date
        var attempts: Int
        var id: UUID { event.eventID }
    }

    struct Limits: Sendable, Equatable {
        var maxCount = 2_000
        var maxAge: TimeInterval = 7 * 24 * 60 * 60
    }

    private struct FileContents: Codable, Sendable {
        var nextSequence: Int
        var entries: [Entry]
    }

    private let store: JSONFileStore<FileContents>
    private let limits: Limits
    private var contents: FileContents
    /// Set when the last write to disk failed; shown on the Transparency screen.
    private(set) var lastPersistenceError: String?

    init(directory: URL, limits: Limits = Limits(), now: Date = Date()) {
        self.store = JSONFileStore(url: directory.appendingPathComponent("event-queue.json"))
        self.limits = limits
        do {
            self.contents = try store.load() ?? FileContents(nextSequence: 0, entries: [])
        } catch {
            // Unreadable file: keep it aside for inspection and start an empty queue. The sequence
            // restarts from the clock so it stays larger than anything sent before.
            let aside = directory.appendingPathComponent("event-queue.unreadable.json")
            try? FileManager.default.removeItem(at: aside)
            try? FileManager.default.moveItem(at: store.url, to: aside)
            self.contents = FileContents(nextSequence: Int(now.timeIntervalSince1970), entries: [])
            self.lastPersistenceError = "The stored queue could not be read and was set aside: \(error.localizedDescription)"
        }
    }

    var count: Int { contents.entries.count }

    func entries() -> [Entry] { contents.entries }

    /// Appends an event. The queue assigns the sequence number; `build` receives it and returns
    /// the finished event. The event is on disk when this returns (unless the disk write failed,
    /// in which case it stays in memory and `lastPersistenceError` says so).
    @discardableResult
    func enqueue(now: Date = Date(), _ build: @Sendable (Int) -> ConnectivityEvent) -> ConnectivityEvent {
        let sequence = contents.nextSequence
        contents.nextSequence += 1
        let event = build(sequence)
        contents.entries.append(Entry(event: event, enqueuedAt: now, attempts: 0))
        prune(now: now)
        persist()
        return event
    }

    /// Oldest events first, at most `limit` (the API accepts up to 50 per request).
    func nextBatch(limit: Int = 50, now: Date = Date()) -> [ConnectivityEvent] {
        if prune(now: now) { persist() }
        return contents.entries.prefix(limit).map(\.event)
    }

    func recordAttempt(ids: Set<UUID>) {
        var changed = false
        for index in contents.entries.indices where ids.contains(contents.entries[index].event.eventID) {
            contents.entries[index].attempts += 1
            changed = true
        }
        if changed { persist() }
    }

    func remove(ids: Set<UUID>) {
        guard !ids.isEmpty else { return }
        contents.entries.removeAll { ids.contains($0.event.eventID) }
        persist()
    }

    /// Removes everything, including the sequence counter (used when leaving the neighborhood).
    func clear() {
        contents = FileContents(nextSequence: 0, entries: [])
        store.delete()
        lastPersistenceError = nil
    }

    /// Cap: 7 days or 2,000 events. Over the count cap, the oldest heartbeats go first, then the
    /// oldest lifecycle events; connectivity events are dropped only when nothing else is left.
    @discardableResult
    private func prune(now: Date) -> Bool {
        let before = contents.entries.count
        let cutoff = now.addingTimeInterval(-limits.maxAge)
        contents.entries.removeAll { $0.event.occurredAt < cutoff }
        var excess = contents.entries.count - limits.maxCount
        if excess > 0 {
            for retention in [EventType.RetentionClass.heartbeat, .lifecycle, .connectivity] where excess > 0 {
                var kept: [Entry] = []
                kept.reserveCapacity(contents.entries.count)
                for entry in contents.entries {
                    if excess > 0, entry.event.eventType.retentionClass == retention {
                        excess -= 1
                    } else {
                        kept.append(entry)
                    }
                }
                contents.entries = kept
            }
        }
        return contents.entries.count != before
    }

    private func persist() {
        do {
            try store.save(contents)
            lastPersistenceError = nil
        } catch {
            lastPersistenceError = "Could not write the event queue to disk: \(error.localizedDescription)"
        }
    }
}

/// What happened to one event at upload time. Kept (last 50) for the transparency viewer.
struct SentRecord: Codable, Sendable, Equatable, Identifiable {
    let event: ConnectivityEvent
    let sentAt: Date
    let reportedVia: Transport
    let result: EventResult
    let reason: String?
    var id: UUID { event.eventID }
}

actor SentLog {
    static let capacity = 50

    private struct FileContents: Codable, Sendable {
        var records: [SentRecord]
        var lastSuccessfulReportAt: Date?
    }

    private let store: JSONFileStore<FileContents>
    private var contents: FileContents

    init(directory: URL) {
        self.store = JSONFileStore(url: directory.appendingPathComponent("sent-log.json"))
        self.contents = (try? store.load()) ?? FileContents(records: [], lastSuccessfulReportAt: nil)
    }

    /// Newest first.
    func records() -> [SentRecord] { contents.records }

    var lastSuccessfulReportAt: Date? { contents.lastSuccessfulReportAt }

    func append(_ records: [SentRecord], reportSucceededAt: Date?) {
        contents.records.insert(contentsOf: records.reversed(), at: 0)
        if contents.records.count > Self.capacity {
            contents.records.removeLast(contents.records.count - Self.capacity)
        }
        if let reportSucceededAt { contents.lastSuccessfulReportAt = reportSucceededAt }
        try? store.save(contents)
    }

    func clear() {
        contents = FileContents(records: [], lastSuccessfulReportAt: nil)
        store.delete()
    }
}
