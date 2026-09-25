import Foundation

public enum NoteLibraryFaultPoint: String, Equatable, Sendable {
    case afterTemporaryWrite
    case afterOriginalMoved
    case beforeVerification
}

struct PersistedNoteDraft: Codable, Equatable {
    let schemaVersion: Int
    let revision: Int
    let document: NoteDocument

    init(revision: Int, document: NoteDocument) {
        schemaVersion = 1
        self.revision = revision
        self.document = document
    }

    func encoded() throws -> Data {
        guard schemaVersion == 1, revision > 0 else { throw NoteDocumentError.malformed("Invalid recovery draft") }
        _ = try document.validated()
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        let data = try encoder.encode(self)
        guard data.count <= NoteDocument.maximumEncodedBytes + 64 * 1024 else {
            throw NoteDocumentError.tooLarge("Recovery draft exceeds note file limit")
        }
        return data
    }

    static func decode(_ data: Data) throws -> PersistedNoteDraft {
        guard data.count <= NoteDocument.maximumEncodedBytes + 64 * 1024 else {
            throw NoteDocumentError.tooLarge("Recovery draft exceeds note file limit")
        }
        let value = try JSONDecoder().decode(Self.self, from: data)
        guard value.schemaVersion == 1, value.revision > 0 else {
            throw NoteDocumentError.malformed("Invalid recovery draft")
        }
        _ = try value.document.validated()
        _ = try value.document.encoded()
        return value
    }
}

final class NoteRevisionGate: @unchecked Sendable {
    private let lock = NSLock()
    private var latest = [String: Int]()
    private var saved = [String: Int]()
    private var invalidated = Set<String>()

    func register(noteID: String, revision: Int) {
        lock.withNoteLock {
            guard !invalidated.contains(noteID) else { return }
            latest[noteID] = max(latest[noteID] ?? 0, revision)
        }
    }

    func nextRevision(noteID: String) -> Int {
        lock.withNoteLock {
            guard !invalidated.contains(noteID) else { return 0 }
            let value = max(latest[noteID] ?? 0, saved[noteID] ?? 0) + 1
            latest[noteID] = value
            return value
        }
    }

    func markSaved(noteID: String, revision: Int) {
        lock.withNoteLock {
            latest[noteID] = max(latest[noteID] ?? 0, revision)
            saved[noteID] = max(saved[noteID] ?? 0, revision)
        }
    }

    func shouldCommit(noteID: String, revision: Int) -> Bool {
        lock.withNoteLock {
            !invalidated.contains(noteID) && latest[noteID] == revision && revision > (saved[noteID] ?? 0)
        }
    }

    func invalidate(noteID: String) {
        lock.withNoteLock {
            invalidated.insert(noteID)
            latest.removeValue(forKey: noteID)
            saved.removeValue(forKey: noteID)
        }
    }
}

actor NoteDraftWorker {
    private let directory: URL
    private let gate: NoteRevisionGate
    private let faultInjector: ((NoteLibraryFaultPoint, URL) -> Error?)?

    init(directory: URL, gate: NoteRevisionGate,
         faultInjector: ((NoteLibraryFaultPoint, URL) -> Error?)?) {
        self.directory = directory
        self.gate = gate
        self.faultInjector = faultInjector
    }

    func persist(document: NoteDocument, revision: Int) async throws -> Bool {
        guard gate.shouldCommit(noteID: document.id, revision: revision) else { return false }
        let envelope = PersistedNoteDraft(revision: revision, document: document)
        let data = try await Task.detached(priority: .utility) { try envelope.encoded() }.value
        try Task.checkCancellation()
        guard gate.shouldCommit(noteID: document.id, revision: revision) else { return false }
        let target = draftURL(noteID: document.id)
        try NoteAtomicFile.write(data, to: target, faultInjector: faultInjector) { persisted in
            let decoded = try PersistedNoteDraft.decode(persisted)
            guard decoded == envelope else { throw NoteDocumentError.malformed("Recovery draft verification failed") }
        }
        guard gate.shouldCommit(noteID: document.id, revision: revision) else {
            if let stored = try? PersistedNoteDraft.decode(Data(contentsOf: target)), stored.revision == revision {
                try? FileManager.default.removeItem(at: target)
            }
            return false
        }
        return true
    }

    func canonicalSaved(noteID: String, revision: Int) {
        let target = draftURL(noteID: noteID)
        guard let data = try? Data(contentsOf: target),
              let draft = try? PersistedNoteDraft.decode(data),
              draft.revision <= revision else { return }
        NoteAtomicFile.removeFamily(target)
    }

    private func draftURL(noteID: String) -> URL {
        directory.appendingPathComponent("\(noteID).draft.json")
    }
}

enum NoteAtomicFile {
    static func write(
        _ data: Data,
        to target: URL,
        faultInjector: ((NoteLibraryFaultPoint, URL) -> Error?)?,
        temporaryURL: URL? = nil,
        backupURL: URL? = nil,
        verify: (Data) throws -> Void
    ) throws {
        let fileManager = FileManager.default
        try fileManager.createDirectory(at: target.deletingLastPathComponent(), withIntermediateDirectories: true)
        let temporary = temporaryURL ?? sidecar(target, ".tmp")
        let backup = backupURL ?? sidecar(target, ".bak")
        try? fileManager.removeItem(at: temporary)
        do {
            try data.write(to: temporary, options: .atomic)
            if let error = faultInjector?(.afterTemporaryWrite, target) { throw error }
            try verify(try Data(contentsOf: temporary, options: .mappedIfSafe))
        } catch {
            // A handled failure must not leave a valid-looking candidate that
            // the next launch could mistake for a committed edit. A process
            // crash never reaches this catch, so its candidate remains useful.
            if fileManager.fileExists(atPath: temporary.path) { try? fileManager.removeItem(at: temporary) }
            throw error
        }

        let hadTarget = fileManager.fileExists(atPath: target.path)
        var movedOriginal = false
        if hadTarget {
            if fileManager.fileExists(atPath: backup.path) { try fileManager.removeItem(at: backup) }
            try fileManager.moveItem(at: target, to: backup)
            movedOriginal = true
        }
        do {
            if let error = faultInjector?(.afterOriginalMoved, target) { throw error }
            try fileManager.moveItem(at: temporary, to: target)
            if let error = faultInjector?(.beforeVerification, target) { throw error }
            let persisted = try Data(contentsOf: target, options: .mappedIfSafe)
            guard persisted == data else { throw NoteDocumentError.malformed("Saved note verification failed") }
            try verify(persisted)
            if movedOriginal, fileManager.fileExists(atPath: backup.path) { try fileManager.removeItem(at: backup) }
        } catch {
            if movedOriginal, fileManager.fileExists(atPath: backup.path) {
                if fileManager.fileExists(atPath: target.path) { try? fileManager.removeItem(at: target) }
                try? fileManager.moveItem(at: backup, to: target)
            } else if !hadTarget, fileManager.fileExists(atPath: target.path) {
                try? fileManager.removeItem(at: target)
            }
            // This process handled the failure and restored the last committed file.
            // Keeping its rejected candidate would make cold-start recovery promote an
            // edit that save explicitly reported as failed. A process crash leaves the
            // sidecars in place before this catch runs, so they remain recoverable.
            if fileManager.fileExists(atPath: temporary.path) {
                try? fileManager.removeItem(at: temporary)
            }
            throw error
        }
    }

    static func removeFamily(_ target: URL) {
        let manager = FileManager.default
        for url in [target, sidecar(target, ".tmp"), sidecar(target, ".bak")] where manager.fileExists(atPath: url.path) {
            try? manager.removeItem(at: url)
        }
    }

    static func sidecar(_ target: URL, _ suffix: String) -> URL {
        URL(fileURLWithPath: target.path + suffix)
    }
}

private extension NSLock {
    func withNoteLock<T>(_ body: () -> T) -> T {
        lock()
        defer { unlock() }
        return body()
    }
}
