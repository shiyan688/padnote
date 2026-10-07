import Foundation
import CryptoKit
import Darwin

public struct NoteVideoAttachment: Codable, Equatable, Identifiable, Sendable {
    public let id: UUID
    public let noteID: String
    public let taskID: UUID
    public let remoteTaskID: String
    public let sourceRevision: Int
    public let sourceSnapshotSHA256: String
    public let connectionID: UUID
    public let connectionRevision: Int
    public let connectionKind: AgentKind
    public let bridgeID: String?
    public let instanceID: String?
    public let certSHA256: String?
    public let artifactID: String
    public let displayName: String
    public let sizeBytes: Int
    public let sha256: String
    public let createdAt: Date

    fileprivate var fileName: String { "\(id.uuidString.lowercased()).mp4" }
}

public struct NoteVideoAttachmentListing: Sendable {
    public let attachments: [NoteVideoAttachment]
    public let unavailableCount: Int
}

public struct NoteVideoAttachmentArchiveRecord: Sendable { public let attachment: NoteVideoAttachment; public let fileURL: URL }
public struct NoteVideoAttachmentArchiveListing: Sendable { public let records: [NoteVideoAttachmentArchiveRecord]; public let unavailableCount: Int }

public enum NoteVideoAttachmentError: Error, LocalizedError {
    case invalidMetadata, invalidFile, changedFile, alreadyAssociated, notFound
    public var errorDescription: String? {
        switch self {
        case .invalidMetadata: return "视频来源信息无效"
        case .invalidFile: return "视频文件未通过本地完整性校验"
        case .changedFile: return "本地视频文件已变化"
        case .alreadyAssociated: return "该任务的关联记录与现有视频不一致"
        case .notFound: return "找不到这份笔记视频附件"
        }
    }
}

/// Stores verified videos beside, never inside, the note document. All local names are generated IDs.
public final class NoteVideoAttachmentStore: @unchecked Sendable {
    private let root: URL
    private static let processLock = NSRecursiveLock()
    private static var deletedSourceIDs = Set<String>()
    private let fileManager = FileManager.default
    private let maximumVideoBytes = 100 * 1024 * 1024

    public static func withSourceMutation<T>(_ body: () throws -> T) rethrows -> T {
        processLock.lock(); defer { processLock.unlock() }
        return try body()
    }

    public static func markSourceDeleted(_ noteID: String) {
        processLock.withVideoAttachmentLock { deletedSourceIDs.insert(noteID) }
    }

    public static func markSourcePresent(_ noteID: String) {
        processLock.withVideoAttachmentLock { deletedSourceIDs.remove(noteID) }
    }

    public init(directory: URL? = nil) {
        let fallback = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("PadNote", isDirectory: true)
            .appendingPathComponent("note-video-attachments", isDirectory: true)
        root = (directory ?? LibraryBackupUITestPaths.directory("note-video-attachments", fallback: fallback)).standardizedFileURL
    }

    @discardableResult
    public func associate(noteID: String, taskID: UUID, remoteTaskID: String, sourceRevision: Int,
                         sourceSnapshotSHA256: String, connection: AgentTaskConnectionIdentity,
                         artifact: AgentTaskArtifact, verifiedFile: URL) throws -> NoteVideoAttachment {
        try Self.processLock.withVideoAttachmentLock {
            guard !Self.deletedSourceIDs.contains(noteID), validNoteID(noteID), sourceRevision > 0, safeIdentifier(remoteTaskID),
                  Self.isSHA256(sourceSnapshotSHA256), connection.connectionRevision > 0,
                  artifact.mediaType == "video/mp4",
                  safeIdentifier(artifact.id), artifact.sizeBytes > 0, artifact.sizeBytes <= maximumVideoBytes,
                  Self.isSHA256(artifact.sha256), verifiedDisplayName(artifact.name) else {
                throw NoteVideoAttachmentError.invalidMetadata
            }
            let source = verifiedFile.standardizedFileURL
            let sourceInfo = try regularSingleLinkFile(source)
            guard sourceInfo.size == artifact.sizeBytes,
                  try Self.sha256File(source) == artifact.sha256.lowercased() else {
                throw NoteVideoAttachmentError.invalidFile
            }
            let directory = try noteDirectory(noteID, create: true)
            let prior = try readAll(noteID: noteID, directory: directory)
            if let existing = prior.first(where: { $0.taskID == taskID && $0.artifactID == artifact.id }) {
                guard existing.remoteTaskID == remoteTaskID,
                      existing.sourceRevision == sourceRevision,
                      existing.sourceSnapshotSHA256 == sourceSnapshotSHA256.lowercased(),
                      existing.connectionID == connection.connectionID,
                      existing.connectionRevision == connection.connectionRevision,
                      existing.connectionKind == connection.kind,
                      existing.bridgeID == connection.bridgeID, existing.instanceID == connection.instanceID,
                      existing.certSHA256 == connection.certSHA256,
                      existing.sha256 == artifact.sha256.lowercased(),
                      existing.sizeBytes == artifact.sizeBytes,
                      try verifiedStoredFile(existing, directory: directory) else {
                    throw NoteVideoAttachmentError.alreadyAssociated
                }
                return existing
            }
            let value = NoteVideoAttachment(id: UUID(), noteID: noteID, taskID: taskID,
                remoteTaskID: remoteTaskID, sourceRevision: sourceRevision,
                sourceSnapshotSHA256: sourceSnapshotSHA256.lowercased(),
                connectionID: connection.connectionID, connectionRevision: connection.connectionRevision,
                connectionKind: connection.kind, bridgeID: connection.bridgeID,
                instanceID: connection.instanceID, certSHA256: connection.certSHA256,
                artifactID: artifact.id,
                displayName: artifact.name, sizeBytes: artifact.sizeBytes,
                sha256: artifact.sha256.lowercased(), createdAt: Date())
            let destination = directory.appendingPathComponent(value.fileName, isDirectory: false)
            let metadata = metadataURL(value.id, directory: directory)
            guard !fileManager.fileExists(atPath: destination.path), !fileManager.fileExists(atPath: metadata.path) else {
                throw NoteVideoAttachmentError.invalidFile
            }
            let staging = directory.appendingPathComponent("\(UUID().uuidString.lowercased()).partial", isDirectory: false)
            var destinationCreated = false
            do {
                try fileManager.copyItem(at: source, to: staging)
                let copied = try regularSingleLinkFile(staging)
                guard copied.size == artifact.sizeBytes,
                      try Self.sha256File(staging) == artifact.sha256.lowercased() else {
                    throw NoteVideoAttachmentError.changedFile
                }
                try fileManager.moveItem(at: staging, to: destination)
                destinationCreated = true
                try Self.writeJSONExclusively(value, to: metadata, directory: directory)
                return value
            } catch {
                try? fileManager.removeItem(at: staging)
                if destinationCreated { try? fileManager.removeItem(at: destination) }
                throw error
            }
        }
    }

    public func attachments(noteID: String) throws -> [NoteVideoAttachment] {
        try Self.processLock.withVideoAttachmentLock {
            let directory = try noteDirectory(noteID, create: false)
            guard fileManager.fileExists(atPath: directory.path) else { return [] }
            return try readAll(noteID: noteID, directory: directory).sorted { $0.createdAt > $1.createdAt }
        }
    }

    public func listing(noteID: String) throws -> NoteVideoAttachmentListing {
        try Self.processLock.withVideoAttachmentLock {
            let directory = try noteDirectory(noteID, create: false)
            guard fileManager.fileExists(atPath: directory.path) else {
                return NoteVideoAttachmentListing(attachments: [], unavailableCount: 0)
            }
            let urls = try fileManager.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)
            guard urls.count <= 20_000 else { throw NoteVideoAttachmentError.invalidMetadata }
            var values: [NoteVideoAttachment] = []
            var unavailable = 0
            for url in urls where url.pathExtension == "json" {
                do {
                    try verifyRegularMetadataFile(url)
                    let info = try regularSingleLinkFile(url)
                    guard info.size <= 64 * 1024 else { throw NoteVideoAttachmentError.invalidMetadata }
                    let value = try JSONDecoder().decode(NoteVideoAttachment.self, from: Data(contentsOf: url))
                    guard value.noteID == noteID, valid(value), url.lastPathComponent == "\(value.id.uuidString.lowercased()).json" else {
                        throw NoteVideoAttachmentError.invalidMetadata
                    }
                    let file = directory.appendingPathComponent(value.fileName, isDirectory: false)
                    let fileInfo = try regularSingleLinkFile(file)
                    guard fileInfo.size == value.sizeBytes else { throw NoteVideoAttachmentError.changedFile }
                    values.append(value)
                } catch { unavailable += 1 }
            }
            let expected = Set(values.map(\.fileName) + values.map { "\($0.id.uuidString.lowercased()).json" })
            let orphanPattern = try! NSRegularExpression(pattern: "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(?:mp4|partial|metadata-partial)$")
            for url in urls where !expected.contains(url.lastPathComponent) && url.pathExtension != "json" {
                let name = url.lastPathComponent
                let range = NSRange(name.startIndex..<name.endIndex, in: name)
                if orphanPattern.firstMatch(in: name, range: range) == nil { unavailable += 1 }
            }
            return NoteVideoAttachmentListing(attachments: values.sorted { $0.createdAt > $1.createdAt }, unavailableCount: unavailable)
        }
    }

    /// Enumerates sidecar directories by reading only generated metadata. This also finds attachments whose source note was deleted.
    public func archiveRecords() throws -> NoteVideoAttachmentArchiveListing {
        try Self.processLock.withVideoAttachmentLock {
            guard fileManager.fileExists(atPath: root.path) else { return NoteVideoAttachmentArchiveListing(records: [], unavailableCount: 0) }
            try ensureDirectory(root, create: false)
            let directories = try fileManager.contentsOfDirectory(at: root, includingPropertiesForKeys: nil)
            guard directories.count <= 20_000 else { throw NoteVideoAttachmentError.invalidMetadata }
            var result = [NoteVideoAttachmentArchiveRecord]()
            var unavailable = 0
            for directory in directories {
                var info = stat()
                guard Darwin.lstat(directory.path, &info) == 0, info.st_mode & mode_t(S_IFMT) == mode_t(S_IFDIR),
                      directory.lastPathComponent.range(of: "^[0-9a-f]{64}$", options: .regularExpression) != nil else {
                    throw NoteVideoAttachmentError.invalidFile
                }
                let files = try fileManager.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)
                guard files.count <= 20_000 else { throw NoteVideoAttachmentError.invalidMetadata }
                for metadata in files where metadata.pathExtension == "json" {
                    do {
                        try verifyRegularMetadataFile(metadata)
                        let size = try regularSingleLinkFile(metadata).size
                        guard size <= 64 * 1024 else { throw NoteVideoAttachmentError.invalidMetadata }
                        let value = try JSONDecoder().decode(NoteVideoAttachment.self, from: Data(contentsOf: metadata))
                        guard valid(value), Self.sha256(Data(value.noteID.utf8)) == directory.lastPathComponent,
                              metadata.lastPathComponent == "\(value.id.uuidString.lowercased()).json" else { throw NoteVideoAttachmentError.invalidMetadata }
                        let media = directory.appendingPathComponent(value.fileName)
                        let mediaInfo = try regularSingleLinkFile(media)
                        guard mediaInfo.size == value.sizeBytes else { throw NoteVideoAttachmentError.changedFile }
                        result.append(NoteVideoAttachmentArchiveRecord(attachment: value, fileURL: media))
                    } catch { unavailable += 1 }
                }
                let metadataNames = Set(files.filter { $0.pathExtension == "json" }.map(\.lastPathComponent))
                for file in files where file.pathExtension != "json" && !metadataNames.contains(file.deletingPathExtension().lastPathComponent + ".json") {
                    if file.pathExtension == "mp4" { unavailable += 1 }
                }
            }
            return NoteVideoAttachmentArchiveListing(records: result, unavailableCount: unavailable)
        }
    }

    public func fileURL(noteID: String, attachmentID: UUID) throws -> URL {
        try Self.processLock.withVideoAttachmentLock {
            let directory = try noteDirectory(noteID, create: false)
            let value = try readAll(noteID: noteID, directory: directory).first { $0.id == attachmentID }
            guard let value else { throw NoteVideoAttachmentError.notFound }
            guard try verifiedStoredFile(value, directory: directory) else { throw NoteVideoAttachmentError.changedFile }
            return directory.appendingPathComponent(value.fileName, isDirectory: false)
        }
    }

    public func remove(noteID: String, attachmentID: UUID) throws {
        try Self.processLock.withVideoAttachmentLock {
            let directory = try noteDirectory(noteID, create: false)
            let value = try readAll(noteID: noteID, directory: directory).first { $0.id == attachmentID }
            guard let value else { throw NoteVideoAttachmentError.notFound }
            let file = directory.appendingPathComponent(value.fileName, isDirectory: false)
            let metadata = metadataURL(value.id, directory: directory)
            try verifyRegularMetadataFile(metadata)
            if fileManager.fileExists(atPath: file.path) {
                _ = try regularSingleLinkFile(file)
                try fileManager.removeItem(at: file)
            }
            try fileManager.removeItem(at: metadata)
        }
    }

    public func removeAll(noteID: String) throws {
        try Self.processLock.withVideoAttachmentLock {
            let directory = try noteDirectory(noteID, create: false)
            guard fileManager.fileExists(atPath: directory.path) else { return }
            let values = try readAll(noteID: noteID, directory: directory)
            for value in values {
                let file = directory.appendingPathComponent(value.fileName, isDirectory: false)
                let metadata = metadataURL(value.id, directory: directory)
                try verifyRegularMetadataFile(metadata)
                if fileManager.fileExists(atPath: file.path) {
                    _ = try regularSingleLinkFile(file)
                    try fileManager.removeItem(at: file)
                }
                try fileManager.removeItem(at: metadata)
            }
            // Leave the generated directory in place: interrupted copies and unindexed files
            // are not silently erased by a note-wide removal.
        }
    }

    private func readAll(noteID: String, directory: URL) throws -> [NoteVideoAttachment] {
        guard validNoteID(noteID) else { throw NoteVideoAttachmentError.invalidMetadata }
        let urls = try fileManager.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)
        let metadataURLs = urls.filter { $0.pathExtension == "json" }
        guard metadataURLs.count <= 10_000 else { throw NoteVideoAttachmentError.invalidMetadata }
        var values: [NoteVideoAttachment] = []
        for url in metadataURLs {
            try verifyRegularMetadataFile(url)
            let metadataInfo = try regularSingleLinkFile(url)
            guard metadataInfo.size <= 64 * 1024 else { throw NoteVideoAttachmentError.invalidMetadata }
            let value = try JSONDecoder().decode(NoteVideoAttachment.self, from: Data(contentsOf: url))
            guard value.noteID == noteID, valid(value), url.lastPathComponent == "\(value.id.uuidString.lowercased()).json" else {
                throw NoteVideoAttachmentError.invalidMetadata
            }
            // Listing is metadata-only. File bytes are streamed and checked when that
            // specific attachment is opened or associated, so one damaged file cannot
            // hide other valid attachments or prevent removal.
            values.append(value)
        }
        let expectedNames = Set(values.map(\.fileName) + values.map { "\($0.id.uuidString.lowercased()).json" })
        let orphanPattern = try! NSRegularExpression(pattern: "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(?:mp4|partial|metadata-partial)$")
        guard urls.allSatisfy({ url in
            let name = url.lastPathComponent
            if expectedNames.contains(name) { return true }
            let range = NSRange(name.startIndex..<name.endIndex, in: name)
            return orphanPattern.firstMatch(in: name, range: range) != nil
        }) else {
            throw NoteVideoAttachmentError.invalidMetadata
        }
        return values
    }

    private func noteDirectory(_ noteID: String, create: Bool) throws -> URL {
        guard validNoteID(noteID) else { throw NoteVideoAttachmentError.invalidMetadata }
        try ensureDirectory(root, create: create)
        let directory = root.appendingPathComponent(Self.sha256(Data(noteID.utf8)), isDirectory: true)
        try ensureDirectory(directory, create: create)
        return directory
    }

    private func ensureDirectory(_ url: URL, create: Bool) throws {
        if create && !fileManager.fileExists(atPath: url.path) {
            try fileManager.createDirectory(at: url, withIntermediateDirectories: true)
        }
        guard fileManager.fileExists(atPath: url.path) else { return }
        var info = stat()
        guard Darwin.lstat(url.path, &info) == 0,
              info.st_mode & mode_t(S_IFMT) == mode_t(S_IFDIR) else {
            throw NoteVideoAttachmentError.invalidFile
        }
    }

    private func verifyRegularMetadataFile(_ url: URL) throws {
        _ = try regularSingleLinkFile(url)
        guard url.pathExtension == "json" else { throw NoteVideoAttachmentError.invalidMetadata }
    }

    private func regularSingleLinkFile(_ url: URL) throws -> (size: Int, identity: UInt64) {
        var info = stat()
        guard Darwin.lstat(url.path, &info) == 0,
              info.st_mode & mode_t(S_IFMT) == mode_t(S_IFREG), info.st_nlink == 1,
              info.st_size >= 0 else { throw NoteVideoAttachmentError.invalidFile }
        return (Int(info.st_size), UInt64(info.st_ino))
    }

    private func verifiedStoredFile(_ value: NoteVideoAttachment, directory: URL) throws -> Bool {
        let file = directory.appendingPathComponent(value.fileName, isDirectory: false)
        let info = try regularSingleLinkFile(file)
        guard info.size == value.sizeBytes else { return false }
        return try Self.sha256File(file) == value.sha256
    }

    private func metadataURL(_ id: UUID, directory: URL) -> URL {
        directory.appendingPathComponent("\(id.uuidString.lowercased()).json", isDirectory: false)
    }

    private func valid(_ value: NoteVideoAttachment) -> Bool {
        validNoteID(value.noteID) && safeIdentifier(value.remoteTaskID) && value.sourceRevision > 0
            && Self.isSHA256(value.sourceSnapshotSHA256) && value.connectionRevision > 0
            && (value.certSHA256.map(Self.isSHA256) ?? true) && safeIdentifier(value.artifactID)
            && verifiedDisplayName(value.displayName) && value.sizeBytes > 0
            && value.sizeBytes <= maximumVideoBytes && Self.isSHA256(value.sha256)
    }

    private func validNoteID(_ value: String) -> Bool {
        !value.isEmpty && value.utf8.count <= 120 && !value.contains("/") && !value.contains("\\") && !value.contains("\0")
    }

    private func safeIdentifier(_ value: String) -> Bool {
        !value.isEmpty && value.utf8.count <= 160 && value.unicodeScalars.allSatisfy {
            CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789._-").contains($0)
        }
    }

    private func verifiedDisplayName(_ value: String) -> Bool {
        !value.isEmpty && value.utf8.count <= 256 && !value.contains("/") && !value.contains("\\") && !value.contains("\0")
    }

    private static func isSHA256(_ value: String) -> Bool {
        value.count == 64 && value.utf8.allSatisfy { (48...57).contains($0) || (97...102).contains($0) || (65...70).contains($0) }
    }

    private static func sha256(_ data: Data) -> String {
        SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }

    private static func sha256File(_ url: URL) throws -> String {
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        var hasher = SHA256()
        while let data = try handle.read(upToCount: 64 * 1024), !data.isEmpty { hasher.update(data: data) }
        return hasher.finalize().map { String(format: "%02x", $0) }.joined()
    }

    private static func writeJSONExclusively<T: Encodable>(_ value: T, to url: URL, directory: URL) throws {
        let data = try JSONEncoder.videoAttachment.encode(value)
        let staging = directory.appendingPathComponent("\(UUID().uuidString.lowercased()).metadata-partial", isDirectory: false)
        defer { try? FileManager.default.removeItem(at: staging) }
        try data.write(to: staging, options: [.atomic, .completeFileProtectionUnlessOpen])
        guard !FileManager.default.fileExists(atPath: url.path) else { throw NoteVideoAttachmentError.alreadyAssociated }
        try FileManager.default.moveItem(at: staging, to: url)
    }
}

private extension JSONEncoder {
    static var videoAttachment: JSONEncoder {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        return encoder
    }
}

private extension NSRecursiveLock {
    func withVideoAttachmentLock<T>(_ body: () throws -> T) rethrows -> T {
        lock(); defer { unlock() }
        return try body()
    }
}
