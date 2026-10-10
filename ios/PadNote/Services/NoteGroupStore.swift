import Foundation
import CryptoKit
import Darwin

public enum NoteGroupCatalogFenceError: Error, Equatable {
    case writerInProgress
    case snapshotInProgress
}

public enum NoteGroupOrdinaryAccessError: Error, Equatable {
    case retired
    case groupStoreUnavailable
}

/// A process-wide, nonblocking lease for the cross-store legacy catalog. Writers reject while a
/// snapshot is active; captures reject while any writer is active. Each nested writer takes its
/// own counted lease, so no Task or thread identity can inherit permission to write during capture.
public enum NoteGroupCatalogFence {
    private final class Lease {
        let isCapture: Bool
        init(isCapture: Bool) { self.isCapture = isCapture }
        deinit { NoteGroupCatalogFence.release(isCapture: isCapture) }
    }
    private static let lock = NSLock()
    private static var activeWriters = 0
    private static var captureActive = false

    public static func withWriter<T>(_ operation: () throws -> T) throws -> T {
        let lease = try acquireWriter()
        defer { withExtendedLifetime(lease) {} }
        return try operation()
    }

    public static func withWriter<T>(_ operation: () async throws -> T) async throws -> T {
        let lease = try acquireWriter()
        defer { withExtendedLifetime(lease) {} }
        return try await operation()
    }

    public static func withCapture<T>(_ operation: () async throws -> T) async throws -> T {
        let lease = try acquireCapture()
        defer { withExtendedLifetime(lease) {} }
        return try await operation()
    }

    public static func withCapture<T>(_ operation: () throws -> T) throws -> T {
        let lease = try acquireCapture()
        defer { withExtendedLifetime(lease) {} }
        return try operation()
    }

    private static func acquireWriter() throws -> Lease {
        lock.lock(); defer { lock.unlock() }
        guard !captureActive else { throw NoteGroupCatalogFenceError.snapshotInProgress }
        activeWriters += 1
        return Lease(isCapture: false)
    }

    private static func acquireCapture() throws -> Lease {
        lock.lock(); defer { lock.unlock() }
        guard !captureActive else { throw NoteGroupCatalogFenceError.snapshotInProgress }
        guard activeWriters == 0 else { throw NoteGroupCatalogFenceError.writerInProgress }
        captureActive = true
        return Lease(isCapture: true)
    }

    private static func release(isCapture: Bool) {
        lock.lock(); defer { lock.unlock() }
        if isCapture { captureActive = false } else { activeWriters = max(0, activeWriters - 1) }
    }
}

public enum NoteGroupStoreError: Error, Equatable {
    case invalidIdentifier
    case invalidMembers
    case sourceChanged
    case unsafeStorePath
    case compareAndSwapConflict
    case missingRevision
    case corruptRevision
    case injectedFailure
    case committedButDurabilityUnconfirmed(NoteGroupVersionToken)
}

public enum NoteGroupMemberRole: String, Codable, CaseIterable { case body, pdf, cover, video, videoMetadata, vault, vaultMetadata, association }

public struct NoteGroupMemberInput {
    public let id: String
    public let role: NoteGroupMemberRole
    public let mediaType: String
    public let sourceURL: URL
    public init(id: String, role: NoteGroupMemberRole, mediaType: String, sourceURL: URL) {
        self.id = id; self.role = role; self.mediaType = mediaType; self.sourceURL = sourceURL
    }
}

public struct NoteGroupVersionToken: Codable, Equatable, Hashable {
    public let localNoteID: String
    public let lineageID: String
    public let revisionID: String
    public let groupSHA256: String
}

public struct NoteGroupMemberRecord: Codable, Equatable {
    public let id: String
    public let ownerLocalNoteID: String
    public let role: NoteGroupMemberRole
    public let mediaType: String
    public let relativePath: String
    public let byteLength: Int64
    public let sha256: String
}

public struct NoteGroupRevisionManifest: Codable, Equatable {
    public let schemaVersion: Int
    public let localNoteID: String
    public let lineageID: String
    public let revisionID: String
    public let parentRevisionID: String?
    public let parentGroupSHA256: String?
    public let committedAtMS: Int64
    public let tombstone: Bool
    public let members: [NoteGroupMemberRecord]
}

/// Isolated durable group store foundation. It is not wired to normal note or material writes.
public struct NoteGroupExpectedMemberWitness: Equatable {
    public let id: String
    public let role: NoteGroupMemberRole
    public let byteLength: Int64
    public let sha256: String
    public init(id: String, role: NoteGroupMemberRole, byteLength: Int64, sha256: String) {
        self.id = id; self.role = role; self.byteLength = byteLength; self.sha256 = sha256
    }
}

public final class NoteGroupStore {
    public enum CommitPoint { case beforeMemberCopy, afterStageBeforeRename, afterRevisionRenameBeforeMarker, afterMarkerRenameBeforeDirectorySync }
    public typealias FailureInjector = (CommitPoint) throws -> Void

    private let root: URL
    private let fileManager: FileManager
    private let failureInjector: FailureInjector?
    private static let lockRegistry = NSLock()
    private static var locks: [String: NSRecursiveLock] = [:]

    public static func defaultRootURL() -> URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return base.appendingPathComponent("PadNote/note-groups", isDirectory: true)
    }

    public static func requireLegacyMutationAllowed(noteID: String, rootURL: URL? = nil) throws {
        let localID = try NoteGroupFacade.foundationUUID(noteID)
        let store = try NoteGroupStore(rootURL: rootURL ?? defaultRootURL())
        let matches = try store.currentGroupTokens().filter { $0.localNoteID == localID }
        guard matches.count <= 1 else { throw NoteGroupStoreError.corruptRevision }
        guard let token = matches.first else { return }
        guard let current = try store.readCurrentIfPresent(localNoteID: localID, lineageID: token.lineageID),
              current.token == token else { throw NoteGroupStoreError.corruptRevision }
        throw NoteGroupStoreError.compareAndSwapConflict
    }

    public init(rootURL: URL, fileManager: FileManager = .default, failureInjector: FailureInjector? = nil) throws {
        let requested = rootURL.standardizedFileURL
        guard requested.path != "/", let leaf = requested.pathComponents.last, leaf != "/", !leaf.isEmpty else { throw NoteGroupStoreError.unsafeStorePath }
        // Reject a symlink at the store root itself before canonicalizing an ancestor alias such as /var.
        if Self.lstatExists(requested.path) {
            _ = try Self.inspect(requested.path, expectedType: S_IFDIR, requirePrivateOwner: true)
        }
        let parent = requested.deletingLastPathComponent().resolvingSymlinksInPath().standardizedFileURL
        try Self.validateDirectoryChain(parent.path)
        var parentStat = stat()
        guard Darwin.lstat(parent.path, &parentStat) == 0, parentStat.st_uid == getuid(), parentStat.st_mode & mode_t(0o022) == 0 else { throw NoteGroupStoreError.unsafeStorePath }
        let canonicalRoot = parent.appendingPathComponent(leaf, isDirectory: true).standardizedFileURL
        self.root = canonicalRoot
        self.fileManager = fileManager
        self.failureInjector = failureInjector

        try Self.ensurePrivateDirectory(canonicalRoot.path)
        let groupsURL = canonicalRoot.appendingPathComponent("groups", isDirectory: true)
        try Self.ensurePrivateDirectory(groupsURL.path)
        let lockURL = canonicalRoot.appendingPathComponent(".group-store.lock")
        try Self.ensurePrivateLock(lockURL.path)
        try Self.syncDirectory(canonicalRoot)
    }

    @discardableResult
    public func commit(localNoteID: String, lineageID: String, expected: NoteGroupVersionToken?, members: [NoteGroupMemberInput], expectedSourceWitnesses: [NoteGroupExpectedMemberWitness]? = nil, committedAtMS: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) throws -> NoteGroupVersionToken {
        try validateIdentifier(localNoteID); try validateIdentifier(lineageID)
        guard committedAtMS >= 0 else { throw NoteGroupStoreError.invalidMembers }
        try validateMembers(members)
        if let expectedSourceWitnesses {
            guard expectedSourceWitnesses.count == members.count,
                  Set(expectedSourceWitnesses.map(\.id)).count == expectedSourceWitnesses.count,
                  Set(expectedSourceWitnesses.map(\.id)) == Set(members.map(\.id)),
                  expectedSourceWitnesses.allSatisfy({ $0.byteLength >= 0 && Self.validDigest($0.sha256) }) else {
                throw NoteGroupStoreError.invalidMembers
            }
            for member in members {
                guard let witness = expectedSourceWitnesses.first(where: { $0.id == member.id }), witness.role == member.role else {
                    throw NoteGroupStoreError.invalidMembers
                }
            }
        }
        return try coordinated(lineageID: lineageID) {
            let current = try readCurrentToken(localNoteID: localNoteID, lineageID: lineageID)
            guard current == expected else { throw NoteGroupStoreError.compareAndSwapConflict }
            let revisionID = UUID().uuidString.lowercased()
            let group = groupURL(lineageID)
            let groups = root.appendingPathComponent("groups", isDirectory: true)
            let revisions = group.appendingPathComponent("revisions", isDirectory: true)
            try Self.ensurePrivateDirectory(group.path)
            try Self.ensurePrivateDirectory(revisions.path)
            try Self.syncDirectory(groups); try Self.syncDirectory(group)
            let stage = revisions.appendingPathComponent(".stage-\(UUID().uuidString.lowercased())", isDirectory: true)
            try Self.ensurePrivateDirectory(stage.path)
            let memberDirectory = stage.appendingPathComponent("members", isDirectory: true)
            try Self.ensurePrivateDirectory(memberDirectory.path)
            do {
                var records: [NoteGroupMemberRecord] = []
                var totalBytes: Int64 = 0
                for input in members.sorted(by: { $0.id < $1.id }) {
                    try failureInjector?(.beforeMemberCopy)
                    let relative = "members/\(input.id).bin"
                    let destination = stage.appendingPathComponent(relative)
                    let verified = try LibraryBackupArchive.copyVerified(input.sourceURL, to: destination, maximumBytes: Self.limit(for: input.role))
                    if let expectedSourceWitnesses {
                        guard let witness = expectedSourceWitnesses.first(where: { $0.id == input.id }),
                              witness.role == input.role, witness.byteLength == Int64(verified.size),
                              witness.sha256 == verified.sha256 else { throw NoteGroupStoreError.sourceChanged }
                    }
                    if input.role == .association, !Self.isAssociationIndex(destination) { throw NoteGroupStoreError.invalidMembers }
                    totalBytes += verified.size
                    guard totalBytes <= LibraryBackupArchive.maxExpandedBytes else { throw NoteGroupStoreError.invalidMembers }
                    records.append(.init(id: input.id, ownerLocalNoteID: localNoteID, role: input.role, mediaType: input.mediaType, relativePath: relative, byteLength: verified.size, sha256: verified.sha256))
                }
                let manifest = NoteGroupRevisionManifest(schemaVersion: 1, localNoteID: localNoteID, lineageID: lineageID, revisionID: revisionID, parentRevisionID: current?.revisionID, parentGroupSHA256: current?.groupSHA256, committedAtMS: committedAtMS, tombstone: false, members: records)
                let digest = try writeManifest(manifest, in: stage)
                try Self.syncDirectory(memberDirectory); try Self.syncDirectory(stage)
                try failureInjector?(.afterStageBeforeRename)
                let revision = revisions.appendingPathComponent(revisionID, isDirectory: true)
                guard Darwin.rename(stage.path, revision.path) == 0 else { throw NoteGroupStoreError.unsafeStorePath }
                try Self.syncDirectory(revisions)
                let token = NoteGroupVersionToken(localNoteID: localNoteID, lineageID: lineageID, revisionID: revisionID, groupSHA256: digest)
                try failureInjector?(.afterRevisionRenameBeforeMarker)
                try publish(token)
                return token
            } catch {
                // A stage is disposable; a renamed revision is an immutable orphan and is preserved.
                if Self.lstatExists(stage.path) { try? fileManager.removeItem(at: stage); try? Self.syncDirectory(revisions) }
                throw error
            }
        }
    }

    @discardableResult
    public func tombstone(localNoteID: String, lineageID: String, expected: NoteGroupVersionToken?, committedAtMS: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) throws -> NoteGroupVersionToken {
        try validateIdentifier(localNoteID); try validateIdentifier(lineageID)
        guard committedAtMS >= 0, expected != nil else { throw NoteGroupStoreError.compareAndSwapConflict }
        return try coordinated(lineageID: lineageID) {
            let current = try readCurrentToken(localNoteID: localNoteID, lineageID: lineageID)
            guard current == expected else { throw NoteGroupStoreError.compareAndSwapConflict }
            let revisionID = UUID().uuidString.lowercased()
            let manifest = NoteGroupRevisionManifest(schemaVersion: 1, localNoteID: localNoteID, lineageID: lineageID, revisionID: revisionID, parentRevisionID: current?.revisionID, parentGroupSHA256: current?.groupSHA256, committedAtMS: committedAtMS, tombstone: true, members: [])
            let group = groupURL(lineageID), revisions = group.appendingPathComponent("revisions", isDirectory: true)
            try Self.ensurePrivateDirectory(group.path); try Self.ensurePrivateDirectory(revisions.path)
            let stage = revisions.appendingPathComponent(".stage-\(UUID().uuidString.lowercased())", isDirectory: true)
            try Self.ensurePrivateDirectory(stage.path)
            do {
                let digest = try writeManifest(manifest, in: stage)
                try Self.syncDirectory(stage)
                try failureInjector?(.afterStageBeforeRename)
                guard Darwin.rename(stage.path, revisions.appendingPathComponent(revisionID, isDirectory: true).path) == 0 else { throw NoteGroupStoreError.unsafeStorePath }
                try Self.syncDirectory(revisions)
                let token = NoteGroupVersionToken(localNoteID: localNoteID, lineageID: lineageID, revisionID: revisionID, groupSHA256: digest)
                try failureInjector?(.afterRevisionRenameBeforeMarker)
                try publish(token)
                return token
            } catch {
                if Self.lstatExists(stage.path) { try? fileManager.removeItem(at: stage); try? Self.syncDirectory(revisions) }
                throw error
            }
        }
    }

    public func readCurrent(localNoteID: String, lineageID: String) throws -> (token: NoteGroupVersionToken, manifest: NoteGroupRevisionManifest) {
        try validateIdentifier(localNoteID); try validateIdentifier(lineageID)
        return try coordinated(lineageID: lineageID) {
            guard let token = try readCurrentToken(localNoteID: localNoteID, lineageID: lineageID) else { throw NoteGroupStoreError.missingRevision }
            return try readRevision(token)
        }
    }

    /// Reads the current revision when a marker exists. `nil` means the marker itself is absent;
    /// a marker that points to a missing or corrupt revision throws and must never trigger legacy fallback.
    public func readCurrentIfPresent(localNoteID: String, lineageID: String) throws -> (token: NoteGroupVersionToken, manifest: NoteGroupRevisionManifest)? {
        try validateIdentifier(localNoteID); try validateIdentifier(lineageID)
        return try coordinated(lineageID: lineageID) {
            guard let token = try readCurrentToken(localNoteID: localNoteID, lineageID: lineageID) else { return nil }
            return try readRevision(token)
        }
    }

    public func readRevision(_ token: NoteGroupVersionToken) throws -> (token: NoteGroupVersionToken, manifest: NoteGroupRevisionManifest) {
        try validateIdentifier(token.localNoteID); try validateIdentifier(token.lineageID); try validateIdentifier(token.revisionID)
        let groups = root.appendingPathComponent("groups", isDirectory: true)
        let group = groupURL(token.lineageID), revisions = group.appendingPathComponent("revisions", isDirectory: true)
        let directory = revisions.appendingPathComponent(token.revisionID, isDirectory: true)
        try Self.requirePrivateDirectory(root.path); try Self.requirePrivateDirectory(groups.path); try Self.requirePrivateDirectory(group.path); try Self.requirePrivateDirectory(revisions.path); try Self.requirePrivateDirectory(directory.path)
        let manifestURL = directory.appendingPathComponent("manifest.json")
        guard let bytes = try? LibraryBackupArchive.readSmallFile(manifestURL, maximumBytes: 1_048_576), Self.sha256(bytes) == token.groupSHA256,
              let manifest = try? JSONDecoder().decode(NoteGroupRevisionManifest.self, from: bytes),
              manifest.schemaVersion == 1, manifest.localNoteID == token.localNoteID, manifest.lineageID == token.lineageID,
              manifest.revisionID == token.revisionID, manifest.tombstone == manifest.members.isEmpty,
              (manifest.parentRevisionID == nil) == (manifest.parentGroupSHA256 == nil),
              manifest.parentRevisionID.map(Self.validUUID) ?? true,
              manifest.parentGroupSHA256.map(Self.validDigest) ?? true else { throw NoteGroupStoreError.corruptRevision }
        guard manifest.members.count <= 256, manifest.members.allSatisfy({ $0.ownerLocalNoteID == token.localNoteID }),
              Set(manifest.members.map(\.id)).count == manifest.members.count,
              manifest.tombstone || manifest.members.filter({ $0.role == .body }).count == 1,
              manifest.members.filter({ $0.role == .pdf }).count <= 1,
              manifest.members.filter({ $0.role == .cover }).count <= 1,
              manifest.members.filter({ $0.role == .association }).count <= 1 else { throw NoteGroupStoreError.corruptRevision }
        if !manifest.tombstone { try Self.requirePrivateDirectory(directory.appendingPathComponent("members", isDirectory: true).path) }
        var totalBytes: Int64 = 0
        for member in manifest.members {
            guard Self.validUUID(member.id), member.relativePath == "members/\(member.id).bin",
                  Self.expectedMediaType(for: member.role) == member.mediaType, member.byteLength >= 0, member.byteLength <= Self.limit(for: member.role) else { throw NoteGroupStoreError.corruptRevision }
            totalBytes += member.byteLength
            guard totalBytes <= LibraryBackupArchive.maxExpandedBytes,
                  let measured = try? LibraryBackupArchive.hashFile(directory.appendingPathComponent(member.relativePath)), measured.size == member.byteLength, measured.sha256 == member.sha256 else { throw NoteGroupStoreError.corruptRevision }
            if member.role == .association, !Self.isAssociationIndex(directory.appendingPathComponent(member.relativePath)) { throw NoteGroupStoreError.corruptRevision }
        }
        return (token, manifest)
    }

    /// Returns only the committed chain reachable from current.json, newest first.
    public func history(localNoteID: String, lineageID: String) throws -> [NoteGroupVersionToken] {
        try validateIdentifier(localNoteID); try validateIdentifier(lineageID)
        return try coordinated(lineageID: lineageID) {
            guard var token = try readCurrentToken(localNoteID: localNoteID, lineageID: lineageID) else { return [] }
            var result: [NoteGroupVersionToken] = [], seen = Set<String>()
            while true {
                guard seen.insert(token.revisionID).inserted, result.count < 100_000 else { throw NoteGroupStoreError.corruptRevision }
                let revision = try readRevision(token)
                result.append(token)
                guard let parentID = revision.manifest.parentRevisionID, let parentDigest = revision.manifest.parentGroupSHA256 else { break }
                token = NoteGroupVersionToken(localNoteID: localNoteID, lineageID: lineageID, revisionID: parentID, groupSHA256: parentDigest)
            }
            return result
        }
    }

    /// Enumerates current markers without falling back around malformed group state.
    public func currentGroupTokens() throws -> [NoteGroupVersionToken] {
        let groups = root.appendingPathComponent("groups", isDirectory: true)
        guard try Self.inspect(groups.path, expectedType: S_IFDIR, requirePrivateOwner: true) else { return [] }
        let directories = try fileManager.contentsOfDirectory(at: groups, includingPropertiesForKeys: nil)
        guard directories.count <= 100_000 else { throw NoteGroupStoreError.corruptRevision }
        var result = [NoteGroupVersionToken]()
        for directory in directories {
            try Self.requirePrivateDirectory(directory.path)
            let lineageID = directory.lastPathComponent
            try validateIdentifier(lineageID)
            let marker = directory.appendingPathComponent("current.json")
            if !Self.lstatExists(marker.path) { continue }
            guard let data = try? LibraryBackupArchive.readSmallFile(marker, maximumBytes: 16_384),
                  let decoded = try? JSONDecoder().decode(NoteGroupVersionToken.self, from: data),
                  decoded.lineageID == lineageID else { throw NoteGroupStoreError.corruptRevision }
            guard let token = try readCurrentToken(localNoteID: decoded.localNoteID, lineageID: lineageID) else {
                throw NoteGroupStoreError.corruptRevision
            }
            result.append(token)
        }
        return result.sorted { $0.localNoteID < $1.localNoteID }
    }

    public func memberURL(for record: NoteGroupMemberRecord, in token: NoteGroupVersionToken) throws -> URL {
        let revision = try readRevision(token)
        guard revision.manifest.members.contains(record) else { throw NoteGroupStoreError.corruptRevision }
        return groupURL(token.lineageID).appendingPathComponent("revisions", isDirectory: true).appendingPathComponent(token.revisionID, isDirectory: true).appendingPathComponent(record.relativePath)
    }

    private func writeManifest(_ manifest: NoteGroupRevisionManifest, in directory: URL) throws -> String {
        let bytes = try Self.encode(manifest), url = directory.appendingPathComponent("manifest.json")
        let fd = Darwin.open(url.path, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0o600)
        guard fd >= 0 else { throw NoteGroupStoreError.unsafeStorePath }
        let handle = FileHandle(fileDescriptor: fd, closeOnDealloc: true)
        do { try handle.write(contentsOf: bytes); try handle.synchronize(); try handle.close() }
        catch { try? handle.close(); throw error }
        return Self.sha256(bytes)
    }

    private func publish(_ token: NoteGroupVersionToken) throws {
        let group = groupURL(token.lineageID)
        try Self.requirePrivateDirectory(group.path)
        let marker = group.appendingPathComponent("current.json"), temp = group.appendingPathComponent(".current-\(UUID().uuidString.lowercased()).tmp")
        let bytes = try Self.encode(token)
        let fd = Darwin.open(temp.path, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0o600)
        guard fd >= 0 else { throw NoteGroupStoreError.unsafeStorePath }
        let handle = FileHandle(fileDescriptor: fd, closeOnDealloc: true)
        do { try handle.write(contentsOf: bytes); try handle.synchronize(); try handle.close() }
        catch { try? handle.close(); try? FileManager.default.removeItem(at: temp); throw error }
        guard Darwin.rename(temp.path, marker.path) == 0 else { try? FileManager.default.removeItem(at: temp); throw NoteGroupStoreError.unsafeStorePath }
        do {
            try failureInjector?(.afterMarkerRenameBeforeDirectorySync)
            try Self.syncDirectory(group)
        } catch {
            throw NoteGroupStoreError.committedButDurabilityUnconfirmed(token)
        }
    }

    private func readCurrentToken(localNoteID: String, lineageID: String) throws -> NoteGroupVersionToken? {
        let group = groupURL(lineageID)
        if !Self.lstatExists(group.path) { return nil }
        try Self.requirePrivateDirectory(group.path)
        let url = group.appendingPathComponent("current.json")
        var info = stat()
        if Darwin.lstat(url.path, &info) != 0 {
            if errno == ENOENT { return nil }
            throw NoteGroupStoreError.unsafeStorePath
        }
        guard info.st_mode & mode_t(S_IFMT) == mode_t(S_IFREG), info.st_uid == getuid(), info.st_mode & mode_t(0o077) == 0, info.st_nlink == 1,
              let data = try? LibraryBackupArchive.readSmallFile(url, maximumBytes: 16_384),
              let token = try? JSONDecoder().decode(NoteGroupVersionToken.self, from: data), token.localNoteID == localNoteID, token.lineageID == lineageID else { throw NoteGroupStoreError.corruptRevision }
        _ = try readRevision(token)
        return token
    }

    private func coordinated<T>(lineageID: String, _ body: () throws -> T) throws -> T {
        try Self.requirePrivateDirectory(root.path)
        try Self.requirePrivateDirectory(root.appendingPathComponent("groups", isDirectory: true).path)
        try Self.requirePrivateLock(root.appendingPathComponent(".group-store.lock").path)
        let key = root.path + "/" + lineageID
        Self.lockRegistry.lock(); let lock = Self.locks[key] ?? NSRecursiveLock(); Self.locks[key] = lock; Self.lockRegistry.unlock()
        lock.lock(); defer { lock.unlock() }
        let coordinator = NSFileCoordinator(filePresenter: nil)
        var coordinationError: NSError?
        var result: Result<T, Error>?
        coordinator.coordinate(writingItemAt: root.appendingPathComponent(".group-store.lock"), options: [], error: &coordinationError) { _ in result = Result { try body() } }
        if let coordinationError { throw coordinationError }
        guard let result else { throw NoteGroupStoreError.corruptRevision }
        return try result.get()
    }

    private func groupURL(_ lineageID: String) -> URL { root.appendingPathComponent("groups", isDirectory: true).appendingPathComponent(lineageID, isDirectory: true) }
    private func validateMembers(_ members: [NoteGroupMemberInput]) throws {
        guard !members.isEmpty, members.count <= 256, members.contains(where: { $0.role == .body }), Set(members.map { $0.id.lowercased() }).count == members.count,
              members.filter({ $0.role == .body }).count == 1, members.filter({ $0.role == .pdf }).count <= 1,
              members.filter({ $0.role == .cover }).count <= 1, members.filter({ $0.role == .association }).count <= 1 else { throw NoteGroupStoreError.invalidMembers }
        for member in members { try validateIdentifier(member.id); guard member.mediaType == Self.expectedMediaType(for: member.role) else { throw NoteGroupStoreError.invalidMembers } }
    }
    private func validateIdentifier(_ value: String) throws { guard Self.validUUID(value) else { throw NoteGroupStoreError.invalidIdentifier } }
    private static func validUUID(_ value: String) -> Bool { guard let uuid = UUID(uuidString: value) else { return false }; return uuid.uuidString.lowercased() == value && value.utf8.count == 36 }
    private static func validDigest(_ value: String) -> Bool { value.range(of: "^[0-9a-f]{64}$", options: .regularExpression) != nil }
    private static func limit(for role: NoteGroupMemberRole) -> Int64 { switch role { case .body: return LibraryBackupArchive.maxNoteBytes; case .pdf: return LibraryBackupArchive.maxPDFBytes; case .cover: return LibraryBackupArchive.maxPNGBytes; case .video: return LibraryBackupArchive.maxVideoBytes; case .videoMetadata: return 1_048_576; case .vault: return LibraryBackupArchive.maxVaultBytes; case .vaultMetadata: return LibraryBackupArchive.maxVaultBytes + 128 * 1024; case .association: return 1_048_576 } }
    private static func expectedMediaType(for role: NoteGroupMemberRole) -> String { switch role { case .body: return "application/json"; case .pdf: return "application/pdf"; case .cover: return "image/png"; case .video: return "video/mp4"; case .vault: return "text/markdown"; case .videoMetadata, .vaultMetadata, .association: return "application/json" } }
    private static func isAssociationIndex(_ url: URL) -> Bool {
        guard let data = try? LibraryBackupArchive.readSmallFile(url, maximumBytes: 1_048_576) else { return false }
        return (try? JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed])) != nil
    }
    private static func encode<T: Encodable>(_ value: T) throws -> Data { let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]; return try encoder.encode(value) }
    private static func sha256(_ data: Data) -> String { SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined() }

    private static func lstatExists(_ path: String) -> Bool { var value = stat(); return Darwin.lstat(path, &value) == 0 }
    private static func validateDirectoryChain(_ path: String) throws {
        var current = URL(fileURLWithPath: "/", isDirectory: true)
        for component in URL(fileURLWithPath: path).pathComponents.dropFirst() {
            current.appendPathComponent(component, isDirectory: true)
            var value = stat()
            guard Darwin.lstat(current.path, &value) == 0, value.st_mode & mode_t(S_IFMT) == mode_t(S_IFDIR) else { throw NoteGroupStoreError.unsafeStorePath }
        }
    }
    @discardableResult private static func inspect(_ path: String, expectedType: mode_t, requirePrivateOwner: Bool) throws -> Bool {
        var value = stat()
        if Darwin.lstat(path, &value) != 0 { if errno == ENOENT { return false }; throw NoteGroupStoreError.unsafeStorePath }
        guard value.st_mode & mode_t(S_IFMT) == expectedType else { throw NoteGroupStoreError.unsafeStorePath }
        if requirePrivateOwner && (value.st_uid != getuid() || value.st_mode & mode_t(0o077) != 0) { throw NoteGroupStoreError.unsafeStorePath }
        return true
    }
    private static func ensurePrivateDirectory(_ path: String) throws {
        if try !inspect(path, expectedType: S_IFDIR, requirePrivateOwner: true) {
            guard Darwin.mkdir(path, 0o700) == 0 || errno == EEXIST else { throw NoteGroupStoreError.unsafeStorePath }
        }
        try requirePrivateDirectory(path)
    }
    private static func requirePrivateDirectory(_ path: String) throws { guard try inspect(path, expectedType: S_IFDIR, requirePrivateOwner: true) else { throw NoteGroupStoreError.unsafeStorePath } }
    private static func ensurePrivateLock(_ path: String) throws {
        let fd = Darwin.open(path, O_RDWR | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0o600)
        if fd >= 0 { _ = Darwin.close(fd); try requirePrivateLock(path); return }
        guard errno == EEXIST else { throw NoteGroupStoreError.unsafeStorePath }
        try requirePrivateLock(path)
    }
    private static func requirePrivateLock(_ path: String) throws {
        let fd = Darwin.open(path, O_RDWR | O_NOFOLLOW | O_CLOEXEC); guard fd >= 0 else { throw NoteGroupStoreError.unsafeStorePath }
        defer { _ = Darwin.close(fd) }
        var value = stat()
        guard Darwin.fstat(fd, &value) == 0, value.st_mode & mode_t(S_IFMT) == mode_t(S_IFREG), value.st_uid == getuid(), value.st_mode & mode_t(0o077) == 0, value.st_nlink == 1 else { throw NoteGroupStoreError.unsafeStorePath }
    }
    private static func syncDirectory(_ url: URL) throws {
        let fd = Darwin.open(url.path, O_RDONLY | O_CLOEXEC | O_DIRECTORY | O_NOFOLLOW)
        guard fd >= 0 else { throw NoteGroupStoreError.unsafeStorePath }
        defer { _ = Darwin.close(fd) }
        guard Darwin.fsync(fd) == 0 else { throw NoteGroupStoreError.unsafeStorePath }
    }
}
