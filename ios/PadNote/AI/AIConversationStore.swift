import CryptoKit
import Foundation
import ImageIO

enum AIConversationCapability: String, Codable, Equatable {
    case unknown
    case toolsVerified = "tools_verified"
    case toolRequestRejected = "tool_request_rejected"
    case unavailable
}

struct AIRecipientIdentity: Codable, Equatable {
    let fingerprint: String
    let display: String

    init(fingerprint: String, display: String) {
        self.fingerprint = fingerprint
        self.display = String(display.prefix(300))
    }
}

struct AIVisibleTurn: Codable, Equatable, Identifiable {
    let id: UUID
    let role: String
    let content: String
    let executor: String?
    let createdAt: Date

    init(id: UUID = UUID(), role: String, content: String, executor: String? = nil,
         createdAt: Date = Date()) {
        self.id = id
        self.role = role
        self.content = content
        self.executor = executor
        self.createdAt = createdAt
    }
}

struct AIConversationSource: Codable, Equatable {
    let imagePNG: Data?
    let page: Int
    let bounds: CGRect?
    let noteContext: String
    let vaultEntries: [NoteToolVaultEntry]
    let pdfDigest: String?

    var fingerprint: String {
        var data = Data()
        data.append(imagePNG ?? Data())
        let geometry = bounds.map { "\($0.origin.x),\($0.origin.y),\($0.width),\($0.height)" } ?? "nil"
        data.append(Data("\n\(page)\n\(geometry)\n\(noteContext)".utf8))
        for entry in vaultEntries.sorted(by: { $0.id < $1.id }) {
            data.append(Data("\n\(entry.id)\n\(entry.title)\n\(entry.sourceRevision.map { String($0) } ?? "nil")\n\(entry.contentSHA256)".utf8))
        }
        data.append(Data("\n\(pdfDigest ?? "none")".utf8))
        return AIConversationDigest.hex(data)
    }
}

struct AIConversationRecord: Codable, Equatable, Identifiable {
    static let schemaVersion = 1

    var schema: Int = schemaVersion
    let id: UUID
    let noteID: String
    var storageEpoch: Int
    var generation: Int
    var contextGeneration: Int
    var updatedAt: Date
    var visibleTurns: [AIVisibleTurn]
    var wireMessages: [AIConversationMessage]
    var reply: String?
    var transcript: String?
    var source: AIConversationSource
    var expectedDocumentDigest: String
    var recipient: AIRecipientIdentity
    var permission: NoteToolPermission
    var toolSchemaVersion: Int
    var capability: AIConversationCapability
    var lastMutation: NoteAIMutation?

    init(id: UUID = UUID(), noteID: String, storageEpoch: Int = 0, generation: Int = 1,
         contextGeneration: Int = 1,
         visibleTurns: [AIVisibleTurn] = [], wireMessages: [AIConversationMessage] = [],
         reply: String? = nil, transcript: String? = nil,
         source: AIConversationSource, expectedDocumentDigest: String,
         recipient: AIRecipientIdentity, permission: NoteToolPermission,
         toolSchemaVersion: Int = 1, capability: AIConversationCapability = .unknown,
         lastMutation: NoteAIMutation? = nil, updatedAt: Date = Date()) {
        self.id = id
        self.noteID = noteID
        self.storageEpoch = storageEpoch
        self.generation = generation
        self.contextGeneration = contextGeneration
        self.updatedAt = updatedAt
        self.visibleTurns = visibleTurns
        self.wireMessages = wireMessages
        self.reply = reply
        self.transcript = transcript
        self.source = source
        self.expectedDocumentDigest = expectedDocumentDigest
        self.recipient = recipient
        self.permission = permission
        self.toolSchemaVersion = toolSchemaVersion
        self.capability = capability
        self.lastMutation = lastMutation
    }

    mutating func invalidateWire(recipient: AIRecipientIdentity? = nil,
                                 source: AIConversationSource? = nil,
                                 permission: NoteToolPermission? = nil,
                                 expectedDocumentDigest: String? = nil) {
        generation += 1
        contextGeneration += 1
        updatedAt = Date()
        wireMessages.removeAll(keepingCapacity: false)
        transcript = nil
        capability = .unknown
        if let recipient { self.recipient = recipient }
        if let source { self.source = source }
        if let permission { self.permission = permission }
        if let expectedDocumentDigest { self.expectedDocumentDigest = expectedDocumentDigest }
    }
}

enum AIConversationStoreError: LocalizedError, Equatable {
    case malformed
    case tooLarge
    case stale
    case corrupt([URL])

    var errorDescription: String? {
        switch self {
        case .malformed: return "AI 会话文件格式无效。"
        case .tooLarge: return "AI 会话已达到本机保存上限，请清空后开始新会话。"
        case .stale: return "AI 会话已由另一个窗口更新，请重新打开。"
        case .corrupt: return "已保存的 AI 会话损坏；原文件已保留，可先导出。"
        }
    }
}

struct AIConversationRecoveryExport {
    let url: URL
    let resumable: Bool
}

enum AIConversationDigest {
    static func document(_ document: NoteDocument) throws -> String {
        var normalized = try document.validated()
        normalized.updatedAt = 0
        normalized.viewportZoom = 1
        normalized.viewportCenterX = 0
        normalized.viewportCenterY = 0
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        return hex(try encoder.encode(normalized))
    }

    static func hex(_ data: Data) -> String {
        SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }

    static func file(_ url: URL, limit: Int = 100 * 1024 * 1024) throws -> String {
        let input = try FileHandle(forReadingFrom: url)
        defer { try? input.close() }
        var hasher = SHA256()
        var total = 0
        while let chunk = try input.read(upToCount: 64 * 1024), !chunk.isEmpty {
            total += chunk.count
            guard total <= limit else { throw AIConversationStoreError.tooLarge }
            hasher.update(data: chunk)
        }
        return hasher.finalize().map { String(format: "%02x", $0) }.joined()
    }
}

public final class AIConversationStore: @unchecked Sendable {
    static let maximumFileBytes = 8 * 1024 * 1024
    static let maximumImageBytes = 4 * 1024 * 1024
    static let maximumHistoryBytes = 2 * 1024 * 1024
    static let maximumTurns = 200

    private struct GuardState: Codable {
        let epoch: Int
    }

    private static let locksGuard = NSLock()
    private static var locks: [String: NSRecursiveLock] = [:]
    private let directory: URL
    private let faultInjector: ((NoteLibraryFaultPoint, URL) -> Error?)?

    public convenience init() { self.init(directory: nil, faultInjector: nil) }

    init(directory: URL?, faultInjector: ((NoteLibraryFaultPoint, URL) -> Error?)? = nil) {
        self.directory = directory ?? FileManager.default.urls(for: .applicationSupportDirectory,
            in: .userDomainMask)[0].appendingPathComponent("AIConversations", isDirectory: true)
        self.faultInjector = faultInjector
    }

    func load(noteID: String) throws -> AIConversationRecord? {
        try withLock {
            try ensureDirectory()
            guard safeID(noteID), noteID.count <= 120 else { throw AIConversationStoreError.malformed }
            let target = sessionURL(noteID: noteID)
            let family = [target, NoteAtomicFile.sidecar(target, ".tmp"), NoteAtomicFile.sidecar(target, ".bak")]
            let epoch = try readGuard(noteID: noteID).epoch
            var valid: [(URL, AIConversationRecord, Data)] = []
            var staleValid = false
            var invalidExisting = false
            var existing: [URL] = []
            for url in family where FileManager.default.fileExists(atPath: url.path) {
                existing.append(url)
                guard let data = boundedData(at: url), let record = try? decode(data, expectedNoteID: noteID) else {
                    invalidExisting = true
                    continue
                }
                if record.storageEpoch == epoch { valid.append((url, record, data)) }
                else { staleValid = true }
            }
            guard let selected = valid.max(by: {
                if $0.1.generation != $1.1.generation { return $0.1.generation < $1.1.generation }
                return $0.1.updatedAt < $1.1.updatedAt
            }) else {
                if existing.isEmpty { return nil }
                if staleValid && !invalidExisting {
                    NoteAtomicFile.removeFamily(target)
                    return nil
                }
                throw AIConversationStoreError.corrupt(existing)
            }
            if selected.0 != target {
                let recoveryTemporary = directory.appendingPathComponent(".recover-\(UUID().uuidString).tmp")
                let recoveryBackup = directory.appendingPathComponent(".recover-\(UUID().uuidString).bak")
                try NoteAtomicFile.write(selected.2, to: target, faultInjector: faultInjector,
                    temporaryURL: recoveryTemporary, backupURL: recoveryBackup) { data in
                        _ = try self.decode(data, expectedNoteID: noteID)
                    }
                guard let promoted = boundedData(at: target), promoted == selected.2 else {
                    throw AIConversationStoreError.corrupt(existing)
                }
                for url in family where url != target && FileManager.default.fileExists(atPath: url.path) {
                    try? FileManager.default.removeItem(at: url)
                }
            }
            return selected.1
        }
    }

    func save(_ record: AIConversationRecord) throws {
        try withLock {
            try ensureDirectory()
            try validate(record)
            let target = sessionURL(noteID: record.noteID)
            guard record.storageEpoch == (try readGuard(noteID: record.noteID)).epoch else {
                throw AIConversationStoreError.stale
            }
            let family = [target, NoteAtomicFile.sidecar(target, ".tmp"), NoteAtomicFile.sidecar(target, ".bak")]
            if family.contains(where: { FileManager.default.fileExists(atPath: $0.path) }) {
                guard let current = try load(noteID: record.noteID) else {
                    throw AIConversationStoreError.stale
                }
                guard current.id == record.id, current.generation <= record.generation else {
                    throw AIConversationStoreError.stale
                }
                if current.generation == record.generation, current != record {
                    throw AIConversationStoreError.stale
                }
            }
            let encoder = JSONEncoder()
            encoder.outputFormatting = [.sortedKeys]
            let data = try encoder.encode(record)
            guard data.count <= Self.maximumFileBytes else { throw AIConversationStoreError.tooLarge }
            try NoteAtomicFile.write(data, to: target, faultInjector: faultInjector) { data in
                _ = try self.decode(data, expectedNoteID: record.noteID)
            }
            try? FileManager.default.setAttributes([.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication],
                                                   ofItemAtPath: target.path)
        }
    }

    func preflight(_ record: AIConversationRecord, message: AIConversationMessage) throws {
        try withLock {
            var projected = record
            guard projected.generation < 1_000_000_000 else { throw AIConversationStoreError.tooLarge }
            projected.generation += 1
            projected.wireMessages += [message, .init(role: "assistant",
                content: String(repeating: "x", count: 512 * 1024))]
            projected.visibleTurns += [.init(role: "user", content: message.content),
                .init(role: "assistant", content: String(repeating: "x", count: 512 * 1024))]
            try validate(projected)
            guard try JSONEncoder().encode(projected).count <= Self.maximumFileBytes else {
                throw AIConversationStoreError.tooLarge
            }
        }
    }

    func exportRecovery(_ record: AIConversationRecord) throws -> AIConversationRecoveryExport {
        try withLock {
            guard safeID(record.noteID), record.noteID.count <= 120 else {
                throw AIConversationStoreError.malformed
            }
            guard record.storageEpoch == (try readGuard(noteID: record.noteID)).epoch else {
                throw AIConversationStoreError.stale
            }
            let data: Data
            let resumable: Bool
            if (try? validate(record)) != nil,
               let encoded = try? JSONEncoder().encode(record), encoded.count <= Self.maximumFileBytes {
                data = encoded
                resumable = true
            } else {
                struct ReadOnlyExport: Codable {
                    let schema: String
                    let noteID: String
                    let sessionID: UUID
                    let updatedAt: Date
                    let visibleTurns: [AIVisibleTurn]
                    let latestReply: String?
                    let transcript: String?
                }
                let lastVisibleReply = record.visibleTurns.last(where: { $0.role == "assistant" })?.content
                data = try JSONEncoder().encode(ReadOnlyExport(schema: "padnote.ai-conversation.read-only.v1",
                    noteID: record.noteID, sessionID: record.id, updatedAt: record.updatedAt,
                    visibleTurns: record.visibleTurns,
                    latestReply: lastVisibleReply == record.reply ? nil : record.reply,
                    transcript: record.transcript))
                guard data.count <= 12 * 1024 * 1024 else { throw AIConversationStoreError.tooLarge }
                resumable = false
            }
            let url = FileManager.default.temporaryDirectory
                .appendingPathComponent("PadNote-AI-\(resumable ? "session" : "read-only")-\(UUID().uuidString.prefix(8)).json")
            try data.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            return AIConversationRecoveryExport(url: url, resumable: resumable)
        }
    }

    func clear(_ record: AIConversationRecord) throws {
        try withLock {
            guard safeID(record.noteID), record.noteID.count <= 120 else {
                throw AIConversationStoreError.malformed
            }
            try clearNoteLocked(noteID: record.noteID, expectedEpoch: record.storageEpoch)
        }
    }

    func clearNote(noteID: String) throws {
        try withLock {
            guard safeID(noteID), noteID.count <= 120 else { throw AIConversationStoreError.malformed }
            try clearNoteLocked(noteID: noteID, expectedEpoch: nil)
        }
    }

    private func clearNoteLocked(noteID: String, expectedEpoch: Int?) throws {
            try ensureDirectory()
            let current = try readGuard(noteID: noteID)
            if let expectedEpoch, expectedEpoch != current.epoch { throw AIConversationStoreError.stale }
            guard current.epoch < 1_000_000_000 else { throw AIConversationStoreError.malformed }
            let guardState = GuardState(epoch: current.epoch + 1)
            let data = try JSONEncoder().encode(guardState)
            let target = guardURL(noteID: noteID)
            try NoteAtomicFile.write(data, to: target, faultInjector: faultInjector) { bytes in
                _ = try JSONDecoder().decode(GuardState.self, from: bytes)
            }
            NoteAtomicFile.removeFamily(sessionURL(noteID: noteID))
    }

    func currentEpoch(noteID: String) throws -> Int {
        try withLock {
            try ensureDirectory()
            guard safeID(noteID), noteID.count <= 120 else { throw AIConversationStoreError.malformed }
            return try readGuard(noteID: noteID).epoch
        }
    }

    func recoveryFiles(noteID: String) -> [URL] {
        withLock {
            try? ensureDirectory()
            guard safeID(noteID), noteID.count <= 120 else { return [] }
            let target = sessionURL(noteID: noteID)
            return [target, NoteAtomicFile.sidecar(target, ".tmp"), NoteAtomicFile.sidecar(target, ".bak")]
                .filter { FileManager.default.fileExists(atPath: $0.path) }
        }
    }

    private func decode(_ data: Data, expectedNoteID: String) throws -> AIConversationRecord {
        guard data.count <= Self.maximumFileBytes,
              let record = try? JSONDecoder().decode(AIConversationRecord.self, from: data),
              record.noteID == expectedNoteID else { throw AIConversationStoreError.malformed }
        try validate(record)
        return record
    }

    private func validate(_ record: AIConversationRecord) throws {
        guard record.schema == AIConversationRecord.schemaVersion,
              (0...1_000_000_000).contains(record.storageEpoch),
              (1...1_000_000_000).contains(record.generation),
              (1...1_000_000_000).contains(record.contextGeneration),
              record.noteID.count <= 120,
              safeID(record.noteID), isSHA256(record.expectedDocumentDigest),
              isSHA256(record.recipient.fingerprint), record.recipient.display.utf8.count <= 300,
              record.toolSchemaVersion == 1,
              record.visibleTurns.count + record.wireMessages.count <= Self.maximumTurns,
              (0..<500).contains(record.source.page),
              (record.source.imagePNG?.count ?? 0) <= Self.maximumImageBytes,
              record.source.noteContext.utf8.count <= 512 * 1024,
              NoteToolVaultEntry.bounded(record.source.vaultEntries).count == record.source.vaultEntries.count else {
            throw AIConversationStoreError.malformed
        }
        if let digest = record.source.pdfDigest, !isSHA256(digest) {
            throw AIConversationStoreError.malformed
        }
        if let image = record.source.imagePNG { try validateImageHeader(image) }
        if let bounds = record.source.bounds {
            guard bounds.origin.x.isFinite, bounds.origin.y.isFinite,
                  bounds.width.isFinite, bounds.height.isFinite,
                  bounds.width >= 0, bounds.height >= 0 else { throw AIConversationStoreError.malformed }
        }
        let historyData = try JSONEncoder().encode(record.wireMessages)
        let visibleData = try JSONEncoder().encode(record.visibleTurns)
        guard historyData.count + visibleData.count <= Self.maximumHistoryBytes else {
            throw AIConversationStoreError.tooLarge
        }
        guard record.visibleTurns.allSatisfy({ ["user", "assistant", "notice"].contains($0.role)
                  && $0.content.utf8.count <= 512 * 1024 && ($0.executor?.utf8.count ?? 0) <= 300 }),
              record.wireMessages.allSatisfy({ ["system", "user", "assistant", "tool"].contains($0.role)
                  && $0.content.utf8.count <= 512 * 1024 && $0.imageDataURL == nil }),
              (record.reply?.utf8.count ?? 0) <= 512 * 1024,
              (record.transcript?.utf8.count ?? 0) <= 512 * 1024 else {
            throw AIConversationStoreError.malformed
        }
        if let mutation = record.lastMutation { try mutation.validatePersistedReceipt() }
    }

    private func validateImageHeader(_ data: Data) throws {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil),
              let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
              let width = properties[kCGImagePropertyPixelWidth] as? Int,
              let height = properties[kCGImagePropertyPixelHeight] as? Int,
              width > 0, height > 0, width <= 2048, height <= 2048,
              width <= 4_000_000 / max(1, height) else {
            throw AIConversationStoreError.malformed
        }
    }

    private func boundedData(at url: URL) -> Data? {
        guard let size = try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize,
              size >= 0, size <= Self.maximumFileBytes else { return nil }
        return try? Data(contentsOf: url, options: .mappedIfSafe)
    }

    private func sessionURL(noteID: String) -> URL {
        directory.appendingPathComponent("\(noteID).ai-session.json")
    }

    private func guardURL(noteID: String) -> URL {
        directory.appendingPathComponent("\(noteID).ai-session.guard")
    }

    private func readGuard(noteID: String) throws -> GuardState {
        let target = guardURL(noteID: noteID)
        let family = [target, NoteAtomicFile.sidecar(target, ".tmp"), NoteAtomicFile.sidecar(target, ".bak")]
        var existing: [URL] = []
        var valid: [(URL, GuardState, Data)] = []
        for url in family where FileManager.default.fileExists(atPath: url.path) {
            existing.append(url)
            guard let data = boundedData(at: url),
                  let value = try? JSONDecoder().decode(GuardState.self, from: data),
                  (0...1_000_000_000).contains(value.epoch) else { continue }
            valid.append((url, value, data))
        }
        guard let selected = valid.max(by: { $0.1.epoch < $1.1.epoch }) else {
            if existing.isEmpty { return GuardState(epoch: 0) }
            throw AIConversationStoreError.corrupt(existing)
        }
        if selected.0 != target {
            let temporary = directory.appendingPathComponent(".guard-recover-\(UUID().uuidString).tmp")
            let backup = directory.appendingPathComponent(".guard-recover-\(UUID().uuidString).bak")
            try NoteAtomicFile.write(selected.2, to: target, faultInjector: faultInjector,
                temporaryURL: temporary, backupURL: backup) { bytes in
                    guard (try JSONDecoder().decode(GuardState.self, from: bytes)).epoch == selected.1.epoch else {
                        throw AIConversationStoreError.malformed
                    }
                }
        }
        return selected.1
    }

    private func safeID(_ value: String) -> Bool {
        !value.isEmpty && value != "." && value != ".." &&
            value.unicodeScalars.allSatisfy { CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "-_" )).contains($0) }
    }

    private func isSHA256(_ value: String) -> Bool {
        value.count == 64 && value.unicodeScalars.allSatisfy {
            (48...57).contains($0.value) || (97...102).contains($0.value)
        }
    }

    private func ensureDirectory() throws {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        var value = directory
        var resource = URLResourceValues()
        resource.isExcludedFromBackup = true
        try value.setResourceValues(resource)
    }

    private func withLock<T>(_ body: () throws -> T) rethrows -> T {
        let key = directory.standardizedFileURL.resolvingSymlinksInPath().path
        Self.locksGuard.lock()
        let lock = Self.locks[key] ?? NSRecursiveLock()
        Self.locks[key] = lock
        Self.locksGuard.unlock()
        lock.lock()
        defer { lock.unlock() }
        return try body()
    }
}

private actor AIConversationPersistenceWorker {
    let store: AIConversationStore
    init(store: AIConversationStore) { self.store = store }
    func load(noteID: String) throws -> AIConversationRecord? { try store.load(noteID: noteID) }
    func save(_ record: AIConversationRecord) throws { try store.save(record) }
    func clear(_ record: AIConversationRecord) throws { try store.clear(record) }
    func currentEpoch(noteID: String) throws -> Int { try store.currentEpoch(noteID: noteID) }
    func digest(_ document: NoteDocument) throws -> String { try AIConversationDigest.document(document) }
    func preflight(_ record: AIConversationRecord, message: AIConversationMessage) throws {
        try store.preflight(record, message: message)
    }
    func exportRecovery(_ record: AIConversationRecord) throws -> AIConversationRecoveryExport {
        try store.exportRecovery(record)
    }
    func remove(_ url: URL) { try? FileManager.default.removeItem(at: url) }
}

@MainActor
final class AIConversationController: ObservableObject {
    enum RecoveryKind { case corruptOriginal, resumableSession, readOnlyTranscript }
    struct RequestContext {
        let sessionID: UUID
        let contextGeneration: Int
        let note: NoteDocument
        let source: AIConversationSource
        let history: [AIConversationMessage]
        let transcript: String?
        let recipient: AIRecipientIdentity
        let permission: NoteToolPermission
    }

    @Published private(set) var record: AIConversationRecord?
    @Published private(set) var expectedToolNote: NoteDocument?
    @Published private(set) var recoveryFile: URL?
    @Published private(set) var recoveryKind: RecoveryKind?
    @Published private(set) var continuationBlocked = false
    @Published private(set) var persistenceError: String?
    private let worker: AIConversationPersistenceWorker
    private var operationSerial = 0
    private var automaticRecoveryFile: URL?

    init(store: AIConversationStore) { worker = AIConversationPersistenceWorker(store: store) }

    private func startOperation() -> Int {
        operationSerial += 1
        return operationSerial
    }

    private func isCurrent(_ operation: Int) -> Bool { operationSerial == operation }

    func open(note: NoteDocument, source: AIConversationSource,
              recipient: AIRecipientIdentity?, permission: NoteToolPermission) async {
        let operation = startOperation()
        do {
            if let restored = try await worker.load(noteID: note.id) {
                let digest = try await worker.digest(note)
                guard isCurrent(operation) else { return }
                record = restored
                expectedToolNote = digest == restored.expectedDocumentDigest
                    && restored.source.pdfDigest == source.pdfDigest
                    && (note.pdfPageCount == 0 || source.pdfDigest != nil) ? note : nil
                continuationBlocked = expectedToolNote == nil
                recoveryFile = nil
                recoveryKind = nil
            } else {
                guard let recipient else {
                    guard isCurrent(operation) else { return }
                    record = nil
                    expectedToolNote = nil
                    continuationBlocked = true
                    persistenceError = "请先配置模型档案；这本笔记目前没有已保存的 AI 会话。"
                    return
                }
                let created = try await makeRecord(note: note, source: source, recipient: recipient,
                                                   permission: permission)
                guard isCurrent(operation) else { return }
                record = created
                expectedToolNote = note.pdfPageCount == 0 || source.pdfDigest != nil ? note : nil
                continuationBlocked = expectedToolNote == nil
                try await worker.save(created)
                guard isCurrent(operation), record?.id == created.id else { return }
            }
            persistenceError = nil
        } catch let AIConversationStoreError.corrupt(files) {
            var fallback: AIConversationRecord?
            if let recipient {
                fallback = try? await makeRecord(note: note, source: source, recipient: recipient,
                                                 permission: permission)
            } else { fallback = nil }
            guard isCurrent(operation) else { return }
            record = fallback
            let sourceReadable = note.pdfPageCount == 0 || source.pdfDigest != nil
            expectedToolNote = sourceReadable ? note : nil
            continuationBlocked = !sourceReadable
            recoveryFile = files.first
            recoveryKind = .corruptOriginal
            persistenceError = AIConversationStoreError.corrupt(files).localizedDescription
        } catch {
            var fallback: AIConversationRecord?
            if let recipient {
                fallback = try? await makeRecord(note: note, source: source, recipient: recipient,
                                                 permission: permission)
            } else { fallback = nil }
            guard isCurrent(operation) else { return }
            record = fallback
            let sourceReadable = note.pdfPageCount == 0 || source.pdfDigest != nil
            expectedToolNote = sourceReadable ? note : nil
            continuationBlocked = !sourceReadable
            persistenceError = error.localizedDescription
        }
    }

    func beginRequest(liveDocument: NoteDocument, recipient: AIRecipientIdentity,
                      permission: NoteToolPermission) async throws -> RequestContext {
        guard !continuationBlocked, let captured = record, let expected = expectedToolNote,
              captured.recipient == recipient, captured.permission == permission,
              NoteAIMutation.sourceCompatible(expected, liveDocument) else {
            continuationBlocked = true
            throw NoteAIConversationError.changedDocument
        }
        let operation = operationSerial
        let digest = try await worker.digest(liveDocument)
        guard isCurrent(operation), let current = record,
              current.id == captured.id, current.contextGeneration == captured.contextGeneration,
              digest == captured.expectedDocumentDigest else {
            continuationBlocked = true
            throw NoteAIConversationError.changedDocument
        }
        return RequestContext(sessionID: captured.id, contextGeneration: captured.contextGeneration,
            note: expected, source: captured.source, history: captured.wireMessages,
            transcript: captured.transcript, recipient: recipient, permission: permission)
    }

    func preflight(_ context: RequestContext, message: AIConversationMessage) async throws {
        guard let value = record, value.id == context.sessionID,
              value.contextGeneration == context.contextGeneration else {
            throw AIConversationStoreError.stale
        }
        let operation = operationSerial
        try await worker.preflight(value, message: AIConversationMessage(role: message.role,
            content: message.content, toolCallID: message.toolCallID, toolCalls: message.toolCalls))
        guard isCurrent(operation), record?.id == value.id,
              record?.contextGeneration == context.contextGeneration else {
            throw AIConversationStoreError.stale
        }
    }

    func validate(_ context: RequestContext, liveDocument: NoteDocument) async throws {
        guard let value = record, value.id == context.sessionID,
              value.contextGeneration == context.contextGeneration,
              value.recipient == context.recipient, value.permission == context.permission,
              let expectedToolNote,
              NoteAIMutation.sourceCompatible(expectedToolNote, liveDocument) else {
            throw AIConversationStoreError.stale
        }
        let operation = operationSerial
        let digest = try await worker.digest(liveDocument)
        guard isCurrent(operation), let current = record, current.id == context.sessionID,
              current.contextGeneration == context.contextGeneration,
              digest == current.expectedDocumentDigest else { throw AIConversationStoreError.stale }
    }

    func acceptRound(context: RequestContext, message: AIConversationMessage,
                     outcome: NoteAIConversation.Outcome, commit: NoteAIToolCommit,
                     liveDocument: NoteDocument, transcript: String?, executor: String) async throws {
        let operation = operationSerial
        let committedDigest = try await worker.digest(commit.document)
        let liveDigest = try await worker.digest(liveDocument)
        guard isCurrent(operation), var value = record, value.id == context.sessionID,
              value.contextGeneration == context.contextGeneration,
              value.recipient == context.recipient, value.permission == context.permission,
              NoteAIMutation.sourceCompatible(commit.document, liveDocument),
              committedDigest == liveDigest else {
            throw AIConversationStoreError.stale
        }
        value.generation += 1
        value.updatedAt = Date()
        value.wireMessages = outcome.messages.map {
            AIConversationMessage(role: $0.role, content: $0.content,
                                  toolCallID: $0.toolCallID, toolCalls: $0.toolCalls)
        }
        value.reply = outcome.reply
        value.transcript = transcript
        value.visibleTurns.append(.init(role: "user", content: message.content))
        value.visibleTurns.append(.init(role: "assistant", content: outcome.reply, executor: executor))
        value.lastMutation = commit.mutation
        if outcome.validToolCallCount > 0 { value.capability = .toolsVerified }
        if commit.mutation != nil {
            value.expectedDocumentDigest = committedDigest
            expectedToolNote = commit.document
        }
        record = value
        do {
            try await worker.save(value)
            guard isCurrent(operation), record?.id == value.id,
                  record?.generation == value.generation else { throw AIConversationStoreError.stale }
            persistenceError = nil
            await removeAutomaticRecovery()
        }
        catch {
            guard isCurrent(operation), record?.id == value.id,
                  record?.generation == value.generation else { throw error }
            let exported = try? await worker.exportRecovery(value)
            guard isCurrent(operation), record?.id == value.id,
                  record?.generation == value.generation else { throw error }
            if recoveryKind != .corruptOriginal, let exported {
                automaticRecoveryFile = exported.url
                recoveryFile = exported.url
                recoveryKind = exported.resumable ? .resumableSession : .readOnlyTranscript
            }
            persistenceError = error.localizedDescription + " 回答与修改仍保留在当前页面，可先导出会话副本。"
            throw error
        }
    }

    func acceptPlainRound(context: RequestContext, message: AIConversationMessage,
                          response: AIDomainResponse, transcript: String?, executor: String) async throws {
        let operation = operationSerial
        try await validate(context, liveDocument: expectedToolNote ?? context.note)
        guard var value = record, value.id == context.sessionID,
              value.contextGeneration == context.contextGeneration, value.recipient == context.recipient else {
            throw AIConversationStoreError.stale
        }
        value.generation += 1
        value.updatedAt = Date()
        value.wireMessages += [message, .init(role: "assistant", content: response.content)]
        value.reply = response.content
        value.transcript = transcript
        value.visibleTurns.append(.init(role: "user", content: message.content))
        value.visibleTurns.append(.init(role: "assistant", content: response.content, executor: executor))
        record = value
        do {
            try await worker.save(value)
            guard isCurrent(operation), record?.id == value.id,
                  record?.generation == value.generation else { throw AIConversationStoreError.stale }
            persistenceError = nil
            await removeAutomaticRecovery()
        }
        catch {
            guard isCurrent(operation), record?.id == value.id,
                  record?.generation == value.generation else { throw error }
            let exported = try? await worker.exportRecovery(value)
            guard isCurrent(operation), record?.id == value.id,
                  record?.generation == value.generation else { throw error }
            if recoveryKind != .corruptOriginal, let exported {
                automaticRecoveryFile = exported.url; recoveryFile = exported.url
                recoveryKind = exported.resumable ? .resumableSession : .readOnlyTranscript
            }
            persistenceError = error.localizedDescription + " 回答仍保留在当前页面，可先导出会话副本。"
            throw error
        }
    }

    func acceptManualCommit(_ commit: NoteAIToolCommit, liveDocument: NoteDocument) async throws {
        let operation = operationSerial
        let committedDigest = try await worker.digest(commit.document)
        let liveDigest = try await worker.digest(liveDocument)
        guard isCurrent(operation), var value = record, commit.mutation != nil,
              NoteAIMutation.sourceCompatible(commit.document, liveDocument),
              committedDigest == liveDigest else {
            throw AIConversationStoreError.stale
        }
        value.generation += 1
        value.updatedAt = Date()
        value.expectedDocumentDigest = committedDigest
        value.lastMutation = commit.mutation
        record = value
        expectedToolNote = commit.document
        do {
            try await worker.save(value)
            guard isCurrent(operation), record?.id == value.id,
                  record?.generation == value.generation else { throw AIConversationStoreError.stale }
            persistenceError = nil
            await removeAutomaticRecovery()
        }
        catch {
            guard isCurrent(operation), record?.id == value.id,
                  record?.generation == value.generation else { throw error }
            let exported = try? await worker.exportRecovery(value)
            guard isCurrent(operation), record?.id == value.id,
                  record?.generation == value.generation else { throw error }
            if recoveryKind != .corruptOriginal, let exported {
                automaticRecoveryFile = exported.url; recoveryFile = exported.url
                recoveryKind = exported.resumable ? .resumableSession : .readOnlyTranscript
            }
            persistenceError = error.localizedDescription + " 修改记录仍保留在当前页面，可先导出会话副本。"
            throw error
        }
    }

    func rotate(recipient: AIRecipientIdentity? = nil, source: AIConversationSource? = nil,
                permission: NoteToolPermission? = nil, liveDocument: NoteDocument,
                reason: String, explicitlyRebindSource: Bool = false) async {
        let operation = startOperation()
        guard var value = record else { return }
        do {
            let effectiveSource = source ?? value.source
            let sourceIsReadable = liveDocument.pdfPageCount == 0 || effectiveSource.pdfDigest != nil
            let wasCompatible = expectedToolNote.map { NoteAIMutation.sourceCompatible($0, liveDocument) } ?? false
            guard sourceIsReadable && (wasCompatible || explicitlyRebindSource) else {
                continuationBlocked = true
                value.invalidateWire(recipient: recipient, source: source, permission: permission)
                value.visibleTurns.append(.init(role: "notice", content: reason))
                guard isCurrent(operation) else { return }
                record = value
                try await worker.save(value)
                guard isCurrent(operation), record?.generation == value.generation else { return }
                persistenceError = nil
                return
            }
            let digest = try await worker.digest(liveDocument)
            guard isCurrent(operation) else { return }
            value.invalidateWire(recipient: recipient, source: source, permission: permission,
                expectedDocumentDigest: digest)
            value.visibleTurns.append(.init(role: "notice", content: reason))
            record = value
            expectedToolNote = liveDocument
            continuationBlocked = false
            try await worker.save(value)
            guard isCurrent(operation), record?.generation == value.generation else { return }
            persistenceError = nil
        } catch where isCurrent(operation) { persistenceError = error.localizedDescription }
        catch { }
    }

    func persistPresentation(reply: String?, transcript: String?, mutation: NoteAIMutation?) async {
        guard var value = record else { return }
        value.generation += 1
        value.updatedAt = Date()
        value.reply = reply
        value.transcript = transcript
        value.lastMutation = mutation
        record = value
        do {
            try await worker.save(value)
            if record?.id == value.id, record?.generation == value.generation { persistenceError = nil }
        } catch {
            if record?.id == value.id, record?.generation == value.generation {
                persistenceError = error.localizedDescription
            }
        }
    }

    @discardableResult
    func clear() async -> Bool {
        let operation = startOperation()
        guard let value = record else { return true }
        do {
            try await worker.clear(value)
            guard isCurrent(operation), record?.id == value.id else { return false }
            await removeAutomaticRecovery()
            record = nil
            expectedToolNote = nil
            continuationBlocked = false
            persistenceError = nil
            recoveryFile = nil
            recoveryKind = nil
            return true
        } catch {
            if isCurrent(operation) { persistenceError = error.localizedDescription }
            return false
        }
    }

    private func makeRecord(note: NoteDocument, source: AIConversationSource,
                            recipient: AIRecipientIdentity,
                            permission: NoteToolPermission) async throws -> AIConversationRecord {
        AIConversationRecord(noteID: note.id, storageEpoch: try await worker.currentEpoch(noteID: note.id),
            source: source, expectedDocumentDigest: try await worker.digest(note),
            recipient: recipient, permission: permission)
    }

    private func removeAutomaticRecovery() async {
        if let url = automaticRecoveryFile { await worker.remove(url) }
        automaticRecoveryFile = nil
        if recoveryKind != .corruptOriginal {
            recoveryFile = nil
            recoveryKind = nil
        }
    }
}
