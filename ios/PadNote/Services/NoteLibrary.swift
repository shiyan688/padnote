import Combine
import Foundation
import PDFKit
import CryptoKit

public struct NotePendingDraft: Identifiable, Equatable {
    public var id: String { document.id }
    public let revision: Int
    public let document: NoteDocument
    public let baseGroupToken: NoteGroupVersionToken?

    public init(revision: Int, document: NoteDocument, baseGroupToken: NoteGroupVersionToken? = nil) {
        self.revision = revision; self.document = document; self.baseGroupToken = baseGroupToken
    }
}

public struct NoteGroupEditorSession {
    public let document: NoteDocument
    public let groupToken: NoteGroupVersionToken?
    public let members: [NoteGroupReadMember]

    public init(document: NoteDocument, groupToken: NoteGroupVersionToken?, members: [NoteGroupReadMember]) {
        self.document = document; self.groupToken = groupToken; self.members = members
    }
}

public struct NoteGroupRestoredVideoMember {
    public let attachment: RestoredVideoAttachment
    public let videoURL: URL
    public let metadataURL: URL
}

struct NoteGroupVaultMember {
    let value: VaultNote
    let markdownURL: URL
    let metadataURL: URL
}

public enum NoteGroupVideoMetadata {
    case task(NoteVideoAttachment)
    case restored(RestoredVideoAttachment)
}

public struct NoteGroupVideoEntry: Identifiable {
    public let id: UUID
    public let displayName: String
    public let byteLength: Int64
    public let sha256: String
    public let sourceNoteID: String
    public let remoteTaskID: String?
    public let metadata: NoteGroupVideoMetadata
}

private struct NoteGroupVideoAssociation: Codable {
    let memberID: String
    let role: String
    let sourceKind: String
    let sourceID: String
    let sourceNoteID: String
    let manifestItemID: String?
    let sourceState: String?
    let metadataByteLength: Int64?
    let metadataSHA256: String?
}
private struct NoteGroupVideoAssociationIndex: Codable {
    let schemaVersion: Int
    let localNoteID: String
    let legacyNoteID: String
    var members: [NoteGroupVideoAssociation]
}

public struct NoteRecoveryItem: Identifiable, Equatable {
    public let id: String
    public let filename: String
    public let reason: String
    fileprivate let sourceURL: URL
}

@MainActor
public final class NoteLibrary: ObservableObject {
    @Published public private(set) var notes: [NoteDocument] = []
    @Published public private(set) var pendingDrafts: [NotePendingDraft] = []
    @Published public private(set) var recoveryItems: [NoteRecoveryItem] = []
    @Published public var errorMessage: String?

    private let directory: URL
    private let draftDirectory: URL
    private let fileManager = FileManager.default
    private let maxPDFBytes = NoteArchive.maxPDFBytes
    private let faultInjector: ((NoteLibraryFaultPoint, URL) -> Error?)?
    private let revisionGate: NoteRevisionGate
    private let draftWorker: NoteDraftWorker
    private let groupStore: NoteGroupStore?
    private let groupStoreInitializationFailure: String?
    private var groupSessions: [String: NoteGroupEditorSession] = [:]
    var backupTransactionDirectory: URL { LibraryBackupTransactionGate.defaultJournalRoot }

    public init(
        directory: URL? = nil,
        faultInjector: ((NoteLibraryFaultPoint, URL) -> Error?)? = nil
    ) {
        let resolved: URL
        if let directory {
            resolved = directory
        } else {
            let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
                ?? FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first!
            resolved = base.appendingPathComponent("PadNote/notes", isDirectory: true)
        }
        self.directory = resolved
        draftDirectory = resolved.appendingPathComponent(".pending-edits", isDirectory: true)
        self.faultInjector = faultInjector
        // The group store validates that its parent already exists and is private.
        // Create the library hierarchy first so custom/test library paths work too.
        var libraryDirectoryReady = false
        var directorySetupError: String?
        do {
            try fileManager.createDirectory(at: self.directory, withIntermediateDirectories: true)
            try fileManager.createDirectory(at: draftDirectory, withIntermediateDirectories: true)
            libraryDirectoryReady = true
        } catch {
            directorySetupError = error.localizedDescription
        }
        let groupRoot = directory.map { $0.deletingLastPathComponent().appendingPathComponent("note-groups", isDirectory: true) }
            ?? NoteGroupStore.defaultRootURL()
        var resolvedGroupStore: NoteGroupStore?
        var groupStoreFailure: String?
        if libraryDirectoryReady {
            do { resolvedGroupStore = try NoteGroupStore(rootURL: groupRoot) }
            catch { groupStoreFailure = error.localizedDescription }
        } else {
            groupStoreFailure = directorySetupError ?? "Library directory is unavailable"
        }
        groupStore = resolvedGroupStore
        groupStoreInitializationFailure = groupStoreFailure
        let gate = NoteRevisionGate()
        revisionGate = gate
        draftWorker = NoteDraftWorker(directory: draftDirectory, gate: gate, faultInjector: faultInjector)
        if let directorySetupError { errorMessage = directorySetupError }
        if libraryDirectoryReady { loadNotes() }
    }

    public func save(_ note: NoteDocument) throws {
        try NoteGroupCatalogFence.withWriter {
            try requireLegacyMutationAllowed(noteID: note.id)
            try saveLegacy(note)
        }
    }

    private func requireLegacyMutationAllowed(noteID: String) throws {
        guard groupStoreInitializationFailure == nil, let groupStore else {
            throw NoteGroupOrdinaryAccessError.groupStoreUnavailable
        }
        // Legacy documents can carry historical non-UUID IDs. Such IDs cannot address a
        // foundation group, whose IDs are validated as UUIDs at creation and commit.
        guard let localID = try? NoteGroupFacade.foundationUUID(noteID) else { return }
        if let _ = try currentGroup(localID: localID, store: groupStore) {
            throw NoteGroupStoreError.compareAndSwapConflict
        }
    }

    private func saveLegacy(_ note: NoteDocument) throws {
        var value = note
        value.schemaVersion = 8
        if let existing = notes.first(where: { $0.id == value.id }) {
            var comparable = value
            comparable.updatedAt = existing.updatedAt
            if comparable == existing,
               let persisted = try? readLimited(noteURL(for: value), limit: NoteDocument.maximumEncodedBytes),
               let reopened = try? NoteDocument.decode(persisted), reopened == existing {
                return
            }
        }
        let data = try value.encoded()
        try writeAtomically(data, to: noteURL(for: value)) { persisted in
            guard try NoteDocument.decode(persisted) == value else {
                throw NoteDocumentError.malformed("Saved note verification failed")
            }
        }
        NoteVideoAttachmentStore.markSourcePresent(value.id)
        notes.removeAll { $0.id == value.id }
        notes.append(value)
        notes.sort { $0.updatedAt > $1.updatedAt }
    }

    public func openEditorSession(noteID: String) throws -> NoteGroupEditorSession {
        guard groupStoreInitializationFailure == nil, let groupStore else { throw NoteGroupOrdinaryAccessError.groupStoreUnavailable }
        let normalizedID = try? NoteGroupFacade.foundationUUID(noteID)
        if let localID = normalizedID,
           let current = try currentGroup(localID: localID, store: groupStore) {
            let session = try makeEditorSession(token: current.token, manifest: current.manifest, store: groupStore)
            groupSessions[localID] = session
            return session
        }
        guard let legacy = notes.first(where: {
            $0.id == noteID || (normalizedID != nil && (try? NoteGroupFacade.foundationUUID($0.id)) == normalizedID)
        }) else { throw CocoaError(.fileNoSuchFile) }
        return NoteGroupEditorSession(document: legacy, groupToken: nil, members: [])
    }

    public func openEditorSession(for draft: NotePendingDraft) throws -> NoteGroupEditorSession {
        guard groupStoreInitializationFailure == nil, let groupStore else { throw NoteGroupOrdinaryAccessError.groupStoreUnavailable }
        guard let baseToken = draft.baseGroupToken else {
            // A legacy draft has no authority over a group marker that appeared after it was saved.
            return NoteGroupEditorSession(document: draft.document, groupToken: nil, members: [])
        }
        let revision = try groupStore.readRevision(baseToken)
        let base = try makeEditorSession(token: revision.token, manifest: revision.manifest, store: groupStore)
        return NoteGroupEditorSession(document: draft.document, groupToken: baseToken, members: base.members)
    }

    /// Saves one editor revision against the exact group generation opened by that editor. For
    /// legacy sessions this stays on the legacy writer and never creates a group automatically.
    @discardableResult
    public func save(_ note: NoteDocument, basedOn session: NoteGroupEditorSession) throws -> NoteGroupEditorSession {
        guard let expected = session.groupToken else {
            guard note.id == session.document.id else { throw NoteGroupStoreError.invalidIdentifier }
            try save(note)
            return try openEditorSession(noteID: note.id)
        }
        let localID = try NoteGroupFacade.foundationUUID(note.id)
        guard localID == (try NoteGroupFacade.foundationUUID(session.document.id)) else {
            throw NoteGroupStoreError.invalidIdentifier
        }
        guard groupStoreInitializationFailure == nil, let groupStore else { throw NoteGroupStoreError.unsafeStorePath }
        return try NoteGroupCatalogFence.withWriter {
            guard let current = try groupStore.readCurrentIfPresent(localNoteID: expected.localNoteID,
                                                                    lineageID: expected.lineageID),
                  current.token == expected, !current.manifest.tombstone else {
                throw NoteGroupStoreError.compareAndSwapConflict
            }
            let bodyRecords = current.manifest.members.filter { $0.role == .body }
            guard bodyRecords.count == 1,
                  let bodyRecord = bodyRecords.first,
                  try NoteGroupFacade.foundationUUID(note.id) == expected.localNoteID else {
                throw NoteGroupStoreError.corruptRevision
            }
            let bodyBytes = try note.validated().encoded()
            let stage = FileManager.default.temporaryDirectory.appendingPathComponent(
                "note-group-edit-\(UUID().uuidString.lowercased())", isDirectory: true)
            try FileManager.default.createDirectory(at: stage, withIntermediateDirectories: false,
                attributes: [.posixPermissions: 0o700, .protectionKey: FileProtectionType.completeUnlessOpen])
            defer { try? FileManager.default.removeItem(at: stage) }
            let bodyURL = stage.appendingPathComponent("edited-note.json")
            try bodyBytes.write(to: bodyURL, options: [.atomic, .completeFileProtectionUnlessOpen])
            let bodySHA = SHA256.hash(data: bodyBytes).map { String(format: "%02x", $0) }.joined()
            let inputs = try current.manifest.members.map { record -> NoteGroupMemberInput in
                let source: URL
                if record.role == .body { source = bodyURL }
                else { source = try groupStore.memberURL(for: record, in: expected) }
                return NoteGroupMemberInput(id: record.id, role: record.role, mediaType: record.mediaType, sourceURL: source)
            }
            let witnesses = current.manifest.members.map { record in
                NoteGroupExpectedMemberWitness(id: record.id, role: record.role,
                    byteLength: record.role == .body ? Int64(bodyBytes.count) : record.byteLength,
                    sha256: record.role == .body ? bodySHA : record.sha256)
            }
            let savedToken = try groupStore.commit(localNoteID: expected.localNoteID, lineageID: expected.lineageID,
                expected: expected, members: inputs, expectedSourceWitnesses: witnesses)
            guard let saved = try groupStore.readCurrentIfPresent(localNoteID: expected.localNoteID,
                                                                  lineageID: expected.lineageID), saved.token == savedToken else {
                throw NoteGroupStoreError.corruptRevision
            }
            let updated = try makeEditorSession(token: saved.token, manifest: saved.manifest, store: groupStore)
            groupSessions[localID] = updated
            notes.removeAll { (try? NoteGroupFacade.foundationUUID($0.id)) == localID }
            notes.append(updated.document)
            notes.sort { $0.updatedAt > $1.updatedAt }
            return updated
        }
    }

    /// Replaces or removes the cover in a complete immutable group revision. The editor
    /// session token is the expected CAS parent; every unrelated member is re-witnessed.
    @discardableResult
    public func saveCoverPNG(_ pngData: Data?, basedOn session: NoteGroupEditorSession) throws -> NoteGroupEditorSession {
        guard let expected = session.groupToken else { throw NoteGroupStoreError.invalidIdentifier }
        guard groupStoreInitializationFailure == nil, let groupStore else { throw NoteGroupOrdinaryAccessError.groupStoreUnavailable }
        let localID = try NoteGroupFacade.foundationUUID(session.document.id)
        guard localID == expected.localNoteID else { throw NoteGroupStoreError.invalidIdentifier }
        if let pngData, pngData.isEmpty || pngData.count > NoteCoverStore.maxBytes { throw NoteCoverError.tooLarge }

        let stagingDirectory = FileManager.default.temporaryDirectory.appendingPathComponent(
            "PadNoteGroupCover-\(UUID().uuidString.lowercased())", isDirectory: true)
        var stagedCoverURL: URL?
        if let pngData {
            try FileManager.default.createDirectory(at: stagingDirectory, withIntermediateDirectories: false,
                                                    attributes: [.posixPermissions: 0o700])
            let url = stagingDirectory.appendingPathComponent("cover.png")
            do {
                try pngData.write(to: url, options: [.atomic, .completeFileProtectionUnlessOpen])
                _ = try NoteCoverStore.validatePNGFile(url)
                stagedCoverURL = url
            } catch {
                try? FileManager.default.removeItem(at: stagingDirectory)
                throw error
            }
        }
        defer { try? FileManager.default.removeItem(at: stagingDirectory) }

        return try NoteGroupCatalogFence.withWriter {
            guard let current = try groupStore.readCurrentIfPresent(localNoteID: expected.localNoteID,
                                                                    lineageID: expected.lineageID),
                  current.token == expected, !current.manifest.tombstone else {
                throw NoteGroupStoreError.compareAndSwapConflict
            }
            let oldCovers = current.manifest.members.filter { $0.role == .cover }
            guard oldCovers.count <= 1 else { throw NoteGroupStoreError.corruptRevision }
            var inputs = [NoteGroupMemberInput]()
            var witnesses = [NoteGroupExpectedMemberWitness]()
            for record in current.manifest.members where record.role != .cover {
                let source = try groupStore.memberURL(for: record, in: current.token)
                inputs.append(NoteGroupMemberInput(id: record.id, role: record.role,
                                                   mediaType: record.mediaType, sourceURL: source))
                witnesses.append(NoteGroupExpectedMemberWitness(id: record.id, role: record.role,
                                                                 byteLength: record.byteLength, sha256: record.sha256))
            }
            if let stagedCoverURL, let pngData {
                let coverID = oldCovers.first?.id ?? UUID().uuidString.lowercased()
                inputs.append(NoteGroupMemberInput(id: coverID, role: .cover, mediaType: "image/png",
                                                   sourceURL: stagedCoverURL))
                let digest = SHA256.hash(data: pngData).map { String(format: "%02x", $0) }.joined()
                witnesses.append(NoteGroupExpectedMemberWitness(id: coverID, role: .cover,
                                                                 byteLength: Int64(pngData.count), sha256: digest))
            }
            let savedToken = try groupStore.commit(localNoteID: expected.localNoteID, lineageID: expected.lineageID,
                expected: expected, members: inputs, expectedSourceWitnesses: witnesses)
            guard let saved = try groupStore.readCurrentIfPresent(localNoteID: expected.localNoteID,
                                                                  lineageID: expected.lineageID), saved.token == savedToken else {
                throw NoteGroupStoreError.corruptRevision
            }
            let updated = try makeEditorSession(token: saved.token, manifest: saved.manifest, store: groupStore)
            groupSessions[localID] = updated
            notes.removeAll { canonicalID($0.id) == localID }
            notes.append(updated.document)
            notes.sort { $0.updatedAt > $1.updatedAt }
            return updated
        }
    }

    /// Reads the video rows from one immutable group revision. A malformed or incomplete
    /// video pair fails closed rather than falling back to the mutable sidecar directory.
    public func groupedVideoAttachments(basedOn session: NoteGroupEditorSession) throws -> [NoteVideoAttachment] {
        guard let token = session.groupToken, let groupStore else { throw NoteGroupOrdinaryAccessError.groupStoreUnavailable }
        let manifest = try groupStore.readRevision(token).manifest
        guard !manifest.tombstone else { throw NoteGroupOrdinaryAccessError.retired }
        let videos = manifest.members.filter { $0.role == .video }
        let metadata = manifest.members.filter { $0.role == .videoMetadata }
        guard videos.count == metadata.count else { throw NoteGroupStoreError.corruptRevision }
        guard !videos.isEmpty else {
            guard metadata.isEmpty else { throw NoteGroupStoreError.corruptRevision }
            return []
        }
        guard let associationRecord = manifest.members.first(where: { $0.role == .association }) else {
            throw NoteGroupStoreError.corruptRevision
        }
        let associationURL = try groupStore.memberURL(for: associationRecord, in: token)
        let associationIndex = try JSONDecoder().decode(NoteGroupVideoAssociationIndex.self,
            from: LibraryBackupArchive.readSmallFile(associationURL, maximumBytes: 4 * 1024 * 1024))
        guard associationIndex.schemaVersion == 1, associationIndex.localNoteID == token.localNoteID,
              associationIndex.members.count == Set(associationIndex.members.map(\.memberID)).count else {
            throw NoteGroupStoreError.corruptRevision
        }
        var values = [NoteVideoAttachment]()
        for video in videos {
            guard let id = UUID(uuidString: video.id),
                  let meta = metadata.first(where: { $0.id == Self.groupMemberID(note: token.localNoteID, key: "video-metadata:\(video.id)") }) else {
                throw NoteGroupStoreError.corruptRevision
            }
            let metadataURL = try groupStore.memberURL(for: meta, in: token)
            let raw = try LibraryBackupArchive.readSmallFile(metadataURL, maximumBytes: 64 * 1024)
            guard let videoAssociation = associationIndex.members.first(where: {
                      $0.memberID == video.id && $0.role == NoteGroupMemberRole.video.rawValue &&
                      Self.canonicalGroupMemberID($0.sourceID) == video.id
                  }),
                  let metadataAssociation = associationIndex.members.first(where: {
                      $0.memberID == meta.id && $0.role == NoteGroupMemberRole.videoMetadata.rawValue &&
                      Self.canonicalGroupMemberID($0.sourceID) == video.id &&
                      $0.sourceKind == "video_attachment_exact_metadata"
                  }),
                  metadataAssociation.sourceNoteID == videoAssociation.sourceNoteID,
                  metadataAssociation.metadataByteLength == meta.byteLength,
                  metadataAssociation.metadataSHA256 == meta.sha256,
                  try LibraryBackupArchive.hashFile(groupStore.memberURL(for: video, in: token)).sha256 == video.sha256 else {
                throw NoteGroupStoreError.corruptRevision
            }
            switch videoAssociation.sourceKind {
            case "note_video_attachment":
                let value = try JSONDecoder().decode(NoteVideoAttachment.self, from: raw)
                guard value.id == id, videoAssociation.sourceNoteID == value.noteID,
                      try NoteGroupFacade.foundationUUID(value.noteID) == token.localNoteID,
                      videoAssociation.sourceNoteID == value.noteID,
                      value.sizeBytes == video.byteLength, value.sha256 == video.sha256 else {
                    throw NoteGroupStoreError.corruptRevision
                }
                values.append(value)
            case "restored_video_attachment":
                let value = try JSONDecoder().decode(RestoredVideoAttachment.self, from: raw)
                guard value.id == id, let noteID = value.noteID,
                      try NoteGroupFacade.foundationUUID(noteID) == token.localNoteID,
                      videoAssociation.sourceNoteID == value.sourceNoteID,
                      value.byteLength == video.byteLength, value.sha256 == video.sha256 else {
                    throw NoteGroupStoreError.corruptRevision
                }
            default:
                throw NoteGroupStoreError.corruptRevision
            }
        }
        return values.sorted { $0.createdAt > $1.createdAt }
    }

    public func groupedRestoredVideoMembers(basedOn session: NoteGroupEditorSession) throws -> [NoteGroupRestoredVideoMember] {
        guard let token = session.groupToken, let groupStore else { throw NoteGroupOrdinaryAccessError.groupStoreUnavailable }
        let manifest = try groupStore.readRevision(token).manifest
        guard !manifest.tombstone else { throw NoteGroupOrdinaryAccessError.retired }
        let videos = manifest.members.filter { $0.role == .video }
        let metadata = manifest.members.filter { $0.role == .videoMetadata }
        guard videos.count == metadata.count else { throw NoteGroupStoreError.corruptRevision }
        guard !videos.isEmpty else { return [] }
        guard let associationRecord = manifest.members.first(where: { $0.role == .association }) else {
            throw NoteGroupStoreError.corruptRevision
        }
        let indexURL = try groupStore.memberURL(for: associationRecord, in: token)
        let index = try JSONDecoder().decode(NoteGroupVideoAssociationIndex.self,
            from: LibraryBackupArchive.readSmallFile(indexURL, maximumBytes: 4 * 1024 * 1024))
        guard index.schemaVersion == 1, index.localNoteID == token.localNoteID,
              index.members.count == Set(index.members.map(\.memberID)).count else { throw NoteGroupStoreError.corruptRevision }
        var result = [NoteGroupRestoredVideoMember]()
        for video in videos {
            guard let id = UUID(uuidString: video.id),
                  let meta = metadata.first(where: { $0.id == Self.groupMemberID(note: token.localNoteID, key: "video-metadata:\(video.id)") }),
            let association = index.members.first(where: {
                  $0.memberID == video.id && $0.role == NoteGroupMemberRole.video.rawValue &&
                  Self.canonicalGroupMemberID($0.sourceID) == video.id
                  }) else { throw NoteGroupStoreError.corruptRevision }
            switch association.sourceKind {
            case "note_video_attachment":
                continue
            case "restored_video_attachment":
                break
            default:
                throw NoteGroupStoreError.corruptRevision
            }
            guard let metadataAssociation = index.members.first(where: {
                $0.memberID == meta.id && $0.role == NoteGroupMemberRole.videoMetadata.rawValue &&
                Self.canonicalGroupMemberID($0.sourceID) == video.id &&
                $0.sourceKind == "video_attachment_exact_metadata"
            }), metadataAssociation.metadataByteLength == meta.byteLength,
                 metadataAssociation.metadataSHA256 == meta.sha256,
                 metadataAssociation.sourceNoteID == association.sourceNoteID else {
                throw NoteGroupStoreError.corruptRevision
            }
            let videoURL = try groupStore.memberURL(for: video, in: token)
            let metadataURL = try groupStore.memberURL(for: meta, in: token)
            let value = try JSONDecoder().decode(RestoredVideoAttachment.self,
                from: LibraryBackupArchive.readSmallFile(metadataURL, maximumBytes: 64 * 1024))
            guard value.id == id, let noteID = value.noteID,
                  try NoteGroupFacade.foundationUUID(noteID) == token.localNoteID,
                  association.sourceNoteID == value.sourceNoteID,
                  value.byteLength == video.byteLength, value.sha256 == video.sha256,
                  try LibraryBackupArchive.hashFile(videoURL).sha256 == value.sha256 else {
                throw NoteGroupStoreError.corruptRevision
            }
            result.append(NoteGroupRestoredVideoMember(attachment: value, videoURL: videoURL, metadataURL: metadataURL))
        }
        return result
    }

    public func groupedVideoEntries(basedOn session: NoteGroupEditorSession) throws -> [NoteGroupVideoEntry] {
        guard let token = session.groupToken, let groupStore else { throw NoteGroupOrdinaryAccessError.groupStoreUnavailable }
        let originals = try groupedVideoAttachments(basedOn: session)
        let restored = try groupedRestoredVideoMembers(basedOn: session)
        let manifest = try groupStore.readRevision(token).manifest
        var entries = [NoteGroupVideoEntry]()
        for value in originals {
            let id = value.id.uuidString.lowercased()
            guard let video = manifest.members.first(where: { $0.role == .video && $0.id == id }),
                  let metadata = manifest.members.first(where: {
                      $0.role == .videoMetadata && $0.id == Self.groupMemberID(note: token.localNoteID, key: "video-metadata:\(id)")
                  }) else { throw NoteGroupStoreError.corruptRevision }
            entries.append(NoteGroupVideoEntry(id: value.id, displayName: value.displayName,
                byteLength: Int64(value.sizeBytes), sha256: value.sha256, sourceNoteID: value.noteID,
                remoteTaskID: value.remoteTaskID, metadata: .task(value)))
            _ = try groupStore.memberURL(for: video, in: token)
            _ = try groupStore.memberURL(for: metadata, in: token)
        }
        for item in restored {
            let value = item.attachment
            entries.append(NoteGroupVideoEntry(id: value.id, displayName: value.displayName,
                byteLength: value.byteLength, sha256: value.sha256, sourceNoteID: value.sourceNoteID,
                remoteTaskID: value.remoteTaskID, metadata: .restored(value)))
        }
        return entries.sorted { $0.displayName.localizedCaseInsensitiveCompare($1.displayName) == .orderedAscending }
    }

    func groupedVaultMembers(basedOn session: NoteGroupEditorSession,
                             journalRoot: URL = LibraryBackupTransactionGate.defaultJournalRoot) throws -> [NoteGroupVaultMember] {
        guard let token = session.groupToken, let groupStore else { throw NoteGroupOrdinaryAccessError.groupStoreUnavailable }
        let manifest = try groupStore.readRevision(token).manifest
        guard !manifest.tombstone else { throw NoteGroupOrdinaryAccessError.retired }
        let bodies = manifest.members.filter { $0.role == .vault }
        let metadata = manifest.members.filter { $0.role == .vaultMetadata }
        guard bodies.count == metadata.count else { throw NoteGroupStoreError.corruptRevision }
        if bodies.isEmpty {
            let associationRecords = manifest.members.filter { $0.role == .association }
            guard associationRecords.count <= 1 else { throw NoteGroupStoreError.corruptRevision }
            if let associationRecord = associationRecords.first {
                let url = try groupStore.memberURL(for: associationRecord, in: token)
                let index = try JSONDecoder().decode(NoteGroupVideoAssociationIndex.self,
                    from: LibraryBackupArchive.readSmallFile(url, maximumBytes: 4 * 1024 * 1024))
                guard index.schemaVersion == 1, index.localNoteID == token.localNoteID,
                      index.members.count == Set(index.members.map(\.memberID)).count,
                      index.members.allSatisfy({ NoteGroupMemberRole(rawValue: $0.role) != nil }),
                      !index.members.contains(where: {
                          $0.role == NoteGroupMemberRole.vault.rawValue
                            || $0.role == NoteGroupMemberRole.vaultMetadata.rawValue
                      }) else { throw NoteGroupStoreError.corruptRevision }
            }
            return []
        }
        guard let indexRecord = manifest.members.first(where: { $0.role == .association }),
              manifest.members.filter({ $0.role == .association }).count == 1 else {
            throw NoteGroupStoreError.corruptRevision
        }
        let indexURL = try groupStore.memberURL(for: indexRecord, in: token)
        let index = try JSONDecoder().decode(NoteGroupVideoAssociationIndex.self,
            from: LibraryBackupArchive.readSmallFile(indexURL, maximumBytes: 4 * 1024 * 1024))
        let vaultRows = index.members.filter { $0.role == NoteGroupMemberRole.vault.rawValue }
        let metadataRows = index.members.filter { $0.role == NoteGroupMemberRole.vaultMetadata.rawValue }
        guard index.schemaVersion == 1, index.localNoteID == token.localNoteID,
              index.members.count == Set(index.members.map(\.memberID)).count,
              index.members.allSatisfy({ NoteGroupMemberRole(rawValue: $0.role) != nil }),
              vaultRows.count == bodies.count, metadataRows.count == metadata.count else {
            throw NoteGroupStoreError.corruptRevision
        }

        var result = [NoteGroupVaultMember]()
        var seenSourceIDs = Set<String>()
        for metadataRecord in metadata {
            guard let metadataRow = metadataRows.first(where: { $0.memberID == metadataRecord.id }),
                  metadataRow.sourceKind == "vault_library_exact_json" else {
                throw NoteGroupStoreError.corruptRevision
            }
            let canonicalSourceID = Self.canonicalGroupMemberID(metadataRow.sourceID) ?? metadataRow.sourceID
            guard seenSourceIDs.insert(canonicalSourceID).inserted else { throw NoteGroupStoreError.corruptRevision }
            let expectedMetadataID = Self.groupMemberID(note: token.localNoteID, key: "vault-record:\(canonicalSourceID)")
            let expectedBodyID = Self.groupMemberID(note: token.localNoteID, key: "vault-body:\(canonicalSourceID)")
            guard metadataRecord.id == expectedMetadataID,
                  let bodyRecord = bodies.first(where: { $0.id == expectedBodyID }),
                  let bodyRow = vaultRows.first(where: { $0.memberID == bodyRecord.id }),
                  bodyRow.sourceKind == "vault_library_markdown",
                  bodyRow.sourceID == metadataRow.sourceID,
                  bodyRow.sourceNoteID == metadataRow.sourceNoteID,
                  bodyRow.manifestItemID == nil, metadataRow.manifestItemID == nil,
                  bodyRow.sourceState == "linked_note", metadataRow.sourceState == "linked_note",
                  bodyRow.manifestItemID == metadataRow.manifestItemID else {
                throw NoteGroupStoreError.corruptRevision
            }
            let metadataURL = try groupStore.memberURL(for: metadataRecord, in: token)
            let metadataBytes = try LibraryBackupArchive.readSmallFile(metadataURL,
                maximumBytes: LibraryBackupArchive.maxVaultBytes + 128 * 1024)
            let metadataWitness = try LibraryBackupArchive.hashFile(metadataURL)
            guard metadataRow.metadataByteLength == metadataRecord.byteLength,
                  metadataRow.metadataSHA256 == metadataRecord.sha256,
                  metadataWitness.size == metadataRecord.byteLength,
                  metadataWitness.sha256 == metadataRecord.sha256 else { throw NoteGroupStoreError.corruptRevision }
            let value = try JSONDecoder().decode(VaultNote.self, from: metadataBytes)
            let sourceNoteID: String
            let ownerNoteID: String
            switch value.archiveOrigin {
            case nil:
                sourceNoteID = value.id
                ownerNoteID = value.id
            case "restored_archive":
                guard let sourceID = value.archiveSourceNoteID, let linkedID = value.archiveLinkedNoteID else {
                    throw NoteGroupStoreError.corruptRevision
                }
                sourceNoteID = sourceID
                ownerNoteID = linkedID
            default:
                throw NoteGroupStoreError.corruptRevision
            }
            guard value.id == metadataRow.sourceID, sourceNoteID == metadataRow.sourceNoteID,
                  bodyRow.sourceID == value.id, bodyRow.sourceNoteID == sourceNoteID,
                  (value.archiveOrigin == nil || value.archiveSourceState == metadataRow.sourceState),
                  (try? NoteGroupFacade.foundationUUID(ownerNoteID)) == token.localNoteID,
                  !value.id.isEmpty, value.title.utf8.count <= 512,
                  value.markdown.utf8.count <= LibraryBackupArchive.maxVaultBytes,
                  value.sourceUpdatedAt.isFinite, value.createdAt.timeIntervalSince1970.isFinite,
                  value.createdAt.timeIntervalSince1970 >= 0 else { throw NoteGroupStoreError.corruptRevision }
            guard LibraryBackupTransactionGate.isVisible(originKind: value.archiveOrigin,
                transactionID: value.restoreTransactionID, groupID: value.restoreGroupID,
                kind: "vault", localID: value.id, in: journalRoot) else {
                throw NoteGroupStoreError.corruptRevision
            }
            let markdownURL = try groupStore.memberURL(for: bodyRecord, in: token)
            let markdownBytes = try LibraryBackupArchive.readSmallFile(markdownURL,
                maximumBytes: LibraryBackupArchive.maxVaultBytes)
            let bodyWitness = try LibraryBackupArchive.hashFile(markdownURL)
            guard markdownBytes == Data(value.markdown.utf8),
                  bodyWitness.size == bodyRecord.byteLength, bodyWitness.sha256 == bodyRecord.sha256 else {
                throw NoteGroupStoreError.corruptRevision
            }
            result.append(NoteGroupVaultMember(value: value, markdownURL: markdownURL, metadataURL: metadataURL))
        }
        return result.sorted { $0.value.createdAt > $1.value.createdAt }
    }

    /// A typed editor row keeps the local note owner separate from a restored archive value's
    /// own Vault ID. Those IDs are intentionally different for imported linked material.
    struct EditorVaultEntry {
        let ownerLocalNoteID: String
        let value: VaultNote
    }

    /// Returns verified Vault rows ordinary editor entry points may expose. Group markers are
    /// authoritative: validate each known owner through a fresh current session, include its
    /// verified group members, and suppress any legacy sidecar for that owner. A corrupt marker
    /// always propagates. A retired unrelated owner only suppresses its legacy sidecar; a retired
    /// current note still propagates so callers cannot use stale knowledge while editing it.
    func editorVaultSnapshot(using vault: VaultLibrary, currentNoteID: String) throws -> [EditorVaultEntry] {
        try NoteGroupCatalogFence.withCapture {
            // Read legacy sidecars inside the same catalog exclusion window as group markers.
            vault.reload()
            let legacyEntries = vault.notes
            let owners = Set(notes.map { canonicalID($0.id) }
                + legacyEntries.map { canonicalID(Self.vaultOwnerID($0)) }
                + [canonicalID(currentNoteID)])
            var groupedOwners = Set<String>()
            var groupedEntries = [EditorVaultEntry]()
            for ownerID in owners.sorted() {
                let session: NoteGroupEditorSession?
                do {
                    session = try currentGroupSession(noteID: ownerID)
                } catch NoteGroupOrdinaryAccessError.retired {
                    if canonicalID(ownerID) == canonicalID(currentNoteID) { throw NoteGroupOrdinaryAccessError.retired }
                    groupedOwners.insert(canonicalID(ownerID))
                    continue
                }
                guard let session, session.groupToken != nil else { continue }
                let localOwner = canonicalID(session.document.id)
                groupedOwners.insert(localOwner)
                groupedEntries += try groupedVaultMembers(basedOn: session, journalRoot: vault.backupJournalRoot()).map {
                    EditorVaultEntry(ownerLocalNoteID: localOwner, value: $0.value)
                }
            }
            let legacy = legacyEntries.filter { !groupedOwners.contains(canonicalID(Self.vaultOwnerID($0))) }
                .map { EditorVaultEntry(ownerLocalNoteID: canonicalID(Self.vaultOwnerID($0)), value: $0) }
            return (groupedEntries + legacy).sorted { $0.value.createdAt > $1.value.createdAt }
        }
    }

    /// Selects the editor's current-note material by its local owner identity. Prefer the
    /// canonical self-ID row when both an ordinary value and restored linked values exist;
    /// otherwise accept only a single owner-linked row to avoid an ambiguous video source.
    func editorVaultEntry(forCurrentNoteID noteID: String, in entries: [EditorVaultEntry]) throws -> VaultNote? {
        let ownerID = canonicalID(noteID)
        let matches = entries.filter { canonicalID($0.ownerLocalNoteID) == ownerID }
        let selfMatches = matches.filter { canonicalID($0.value.id) == ownerID }
        if selfMatches.count == 1 { return selfMatches[0].value }
        guard selfMatches.isEmpty else { throw NoteGroupStoreError.corruptRevision }
        guard matches.count <= 1 else { throw NoteGroupStoreError.corruptRevision }
        return matches.first?.value
    }

    private static func vaultOwnerID(_ value: VaultNote) -> String {
        guard value.archiveOrigin == "restored_archive" else { return value.id }
        return value.archiveLinkedNoteID ?? value.archiveSourceNoteID ?? value.id
    }

    /// Prevent legacy sidecar deletion/rollback while a group owns the Vault snapshot.
    /// The operation fails closed if group storage cannot be inspected.
    func assertLegacyVaultMutationAllowed(for value: VaultNote) throws {
        let owner = value.archiveOrigin == "restored_archive"
            ? (value.archiveLinkedNoteID ?? value.archiveSourceNoteID ?? value.id) : value.id
        guard let localID = try? NoteGroupFacade.foundationUUID(owner) else { return }
        guard groupStoreInitializationFailure == nil, let groupStore else {
            throw NoteGroupOrdinaryAccessError.groupStoreUnavailable
        }
        if let _ = try currentGroup(localID: localID, store: groupStore) {
            throw NoteGroupStoreError.compareAndSwapConflict
        }
    }

    /// Removes one Vault record from the immutable group using the exact revision captured by
    /// the shelf action. The original note, other Vault records, media, and history are retained.
    func deleteVaultSnapshot(using vault: VaultLibrary, value: VaultNote,
                             expectedGroupToken: NoteGroupVersionToken?) throws {
        let ownerID = value.archiveOrigin == "restored_archive"
            ? (value.archiveLinkedNoteID ?? value.archiveSourceNoteID ?? value.id) : value.id
        guard let localID = try? NoteGroupFacade.foundationUUID(ownerID) else {
            guard expectedGroupToken == nil else { throw NoteGroupStoreError.compareAndSwapConflict }
            try NoteGroupCatalogFence.withWriter {
                let persisted = try vault.backupColdRead(for: value)
                guard persisted == value else { throw NoteGroupStoreError.compareAndSwapConflict }
                try vault.deleteLegacyUnderCatalogWriter(value)
            }
            return
        }
        try NoteGroupCatalogFence.withWriter {
            guard groupStoreInitializationFailure == nil, let groupStore else {
                throw NoteGroupOrdinaryAccessError.groupStoreUnavailable
            }
            guard let current = try currentGroup(localID: localID, store: groupStore) else {
                guard expectedGroupToken == nil else { throw NoteGroupStoreError.compareAndSwapConflict }
                let persisted = try vault.backupColdRead(for: value)
                guard persisted == value else { throw NoteGroupStoreError.compareAndSwapConflict }
                try vault.deleteLegacyUnderCatalogWriter(value)
                return
            }
            guard let expectedGroupToken, current.token == expectedGroupToken,
                  current.token.localNoteID == localID else {
                throw NoteGroupStoreError.compareAndSwapConflict
            }
            let session = try makeEditorSession(token: current.token, manifest: current.manifest, store: groupStore)
            if current.manifest.members.contains(where: { $0.role == .video || $0.role == .videoMetadata }) {
                _ = try groupedVideoEntries(basedOn: session)
            }
            let entries = try groupedVaultMembers(basedOn: session, journalRoot: vault.backupJournalRoot())
            guard let selected = entries.first(where: { $0.value.id == value.id }), Self.sameVaultRecord(selected.value, value) else {
                throw NoteGroupStoreError.compareAndSwapConflict
            }
            let sourceIDKey = Self.canonicalGroupMemberID(value.id) ?? value.id
            let bodyID = Self.groupMemberID(note: localID, key: "vault-body:\(sourceIDKey)")
            let metadataID = Self.groupMemberID(note: localID, key: "vault-record:\(sourceIDKey)")
            let bodyRecords = current.manifest.members.filter { $0.role == .vault && $0.id == bodyID }
            let metadataRecords = current.manifest.members.filter { $0.role == .vaultMetadata && $0.id == metadataID }
            let associationRecords = current.manifest.members.filter { $0.role == .association }
            guard bodyRecords.count == 1, metadataRecords.count == 1, associationRecords.count == 1 else {
                throw NoteGroupStoreError.corruptRevision
            }
            let associationRecord = associationRecords[0]
            let associationURL = try groupStore.memberURL(for: associationRecord, in: current.token)
            var index = try JSONDecoder().decode(NoteGroupVideoAssociationIndex.self,
                from: LibraryBackupArchive.readSmallFile(associationURL, maximumBytes: 4 * 1024 * 1024))
            guard index.schemaVersion == 1, index.localNoteID == localID,
                  index.members.count == Set(index.members.map(\.memberID)).count,
                  index.members.allSatisfy({ NoteGroupMemberRole(rawValue: $0.role) != nil }) else {
                throw NoteGroupStoreError.corruptRevision
            }
            let beforeCount = index.members.count
            index.members.removeAll { $0.memberID == bodyID || $0.memberID == metadataID }
            guard beforeCount - index.members.count == 2 else { throw NoteGroupStoreError.corruptRevision }

            let stage = FileManager.default.temporaryDirectory.appendingPathComponent(
                "note-group-vault-delete-\(UUID().uuidString)", isDirectory: true)
            try FileManager.default.createDirectory(at: stage, withIntermediateDirectories: false,
                attributes: [.posixPermissions: 0o700, .protectionKey: FileProtectionType.completeUnlessOpen])
            defer { try? FileManager.default.removeItem(at: stage) }
            let indexURL = stage.appendingPathComponent("association.json")
            let associationBytes: Data?
            if index.members.isEmpty { associationBytes = nil }
            else {
                let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
                let bytes = try encoder.encode(index)
                try bytes.write(to: indexURL, options: [.atomic, .completeFileProtectionUnlessOpen])
                associationBytes = bytes
            }
            var inputs = [NoteGroupMemberInput](), witnesses = [NoteGroupExpectedMemberWitness]()
            for record in current.manifest.members where record.id != bodyID && record.id != metadataID
                && (associationBytes != nil || record.id != associationRecord.id) {
                let source = record.id == associationRecord.id ? indexURL : try groupStore.memberURL(for: record, in: current.token)
                let length: Int64
                let digest: String
                if record.id == associationRecord.id, let associationBytes {
                    length = Int64(associationBytes.count)
                    digest = Self.sha256(associationBytes)
                } else {
                    length = record.byteLength
                    digest = record.sha256
                }
                inputs.append(NoteGroupMemberInput(id: record.id, role: record.role, mediaType: record.mediaType, sourceURL: source))
                witnesses.append(NoteGroupExpectedMemberWitness(id: record.id, role: record.role,
                    byteLength: length, sha256: digest))
            }
            let next = try groupStore.commit(localNoteID: localID, lineageID: current.token.lineageID,
                expected: expectedGroupToken, members: inputs, expectedSourceWitnesses: witnesses)
            guard let saved = try groupStore.readCurrentIfPresent(localNoteID: localID, lineageID: current.token.lineageID),
                  saved.token == next else { throw NoteGroupStoreError.corruptRevision }
            groupSessions[localID] = try makeEditorSession(token: next, manifest: saved.manifest, store: groupStore)
        }
    }

    @discardableResult
    func publishVaultSnapshot(using vault: VaultLibrary, note: NoteDocument, markdown: String,
                              expectedGroupToken: NoteGroupVersionToken?) throws -> VaultNote {
        guard Data(markdown.utf8).count <= LibraryBackupArchive.maxVaultBytes else {
            throw LibraryBackupError.sizeLimit("知识库条目超过 16 MiB")
        }
        guard let localID = try? NoteGroupFacade.foundationUUID(note.id) else {
            guard expectedGroupToken == nil else { throw NoteGroupStoreError.compareAndSwapConflict }
            return try NoteGroupCatalogFence.withWriter {
                try vault.saveLegacyUnderCatalogWriter(note: note, markdown: markdown)
            }
        }
        return try NoteGroupCatalogFence.withWriter {
            guard groupStoreInitializationFailure == nil, let groupStore else {
                throw NoteGroupOrdinaryAccessError.groupStoreUnavailable
            }
            guard let current = try currentGroup(localID: localID, store: groupStore) else {
                guard expectedGroupToken == nil else { throw NoteGroupStoreError.compareAndSwapConflict }
                return try vault.saveLegacyUnderCatalogWriter(note: note, markdown: markdown)
            }
            guard let expectedGroupToken, current.token == expectedGroupToken,
                  try NoteGroupFacade.foundationUUID(current.manifest.localNoteID) == localID else {
                throw NoteGroupStoreError.compareAndSwapConflict
            }
            let session = try makeEditorSession(token: current.token, manifest: current.manifest, store: groupStore)
            guard session.document == note else { throw NoteGroupStoreError.compareAndSwapConflict }
            if current.manifest.members.contains(where: { $0.role == .video || $0.role == .videoMetadata }) {
                _ = try groupedVideoEntries(basedOn: session)
            }
            let existingEntries = try groupedVaultMembers(basedOn: session, journalRoot: vault.backupJournalRoot())
            let existing = existingEntries.first { $0.value.id == note.id }
            if let existing, existing.value.archiveOrigin != nil {
                throw NoteGroupStoreError.compareAndSwapConflict
            }
            let value = VaultNote(id: note.id, title: note.title, markdown: markdown,
                sourceUpdatedAt: note.updatedAt, createdAt: existing?.value.createdAt ?? Date())
            let sourceIDKey = Self.canonicalGroupMemberID(value.id) ?? value.id
            let bodyID = Self.groupMemberID(note: localID, key: "vault-body:\(sourceIDKey)")
            let metadataID = Self.groupMemberID(note: localID, key: "vault-record:\(sourceIDKey)")
            let metadataEncoder = JSONEncoder(); metadataEncoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
            let metadataBytes = try metadataEncoder.encode(value)
            let markdownBytes = Data(markdown.utf8)

            let associationRecords = current.manifest.members.filter { $0.role == .association }
            guard associationRecords.count <= 1 else { throw NoteGroupStoreError.corruptRevision }
            let indexID: String
            var index: NoteGroupVideoAssociationIndex
            if let associationRecord = associationRecords.first {
                indexID = associationRecord.id
                let url = try groupStore.memberURL(for: associationRecord, in: current.token)
                index = try JSONDecoder().decode(NoteGroupVideoAssociationIndex.self,
                    from: LibraryBackupArchive.readSmallFile(url, maximumBytes: 4 * 1024 * 1024))
                guard index.schemaVersion == 1, index.localNoteID == localID,
                      index.members.count == Set(index.members.map(\.memberID)).count else {
                    throw NoteGroupStoreError.corruptRevision
                }
            } else {
                let allowed: Set<NoteGroupMemberRole> = [.body, .pdf, .cover]
                guard current.manifest.members.contains(where: { $0.role == .body }),
                      current.manifest.members.allSatisfy({ allowed.contains($0.role) }) else {
                    throw NoteGroupStoreError.corruptRevision
                }
                indexID = Self.groupMemberID(note: localID, key: "association-index")
                index = NoteGroupVideoAssociationIndex(schemaVersion: 1, localNoteID: localID,
                    legacyNoteID: session.document.id, members: [])
            }
            let oldBody = current.manifest.members.first { $0.role == .vault && $0.id == bodyID }
            let oldMetadata = current.manifest.members.first { $0.role == .vaultMetadata && $0.id == metadataID }
            guard (oldBody == nil) == (oldMetadata == nil) else { throw NoteGroupStoreError.corruptRevision }
            let metadataSHA = Self.sha256(metadataBytes)
            index.members.removeAll { $0.memberID == bodyID || $0.memberID == metadataID }
            index.members.append(NoteGroupVideoAssociation(memberID: bodyID, role: NoteGroupMemberRole.vault.rawValue,
                sourceKind: "vault_library_markdown", sourceID: value.id, sourceNoteID: value.id,
                manifestItemID: nil, sourceState: "linked_note", metadataByteLength: nil, metadataSHA256: nil))
            index.members.append(NoteGroupVideoAssociation(memberID: metadataID, role: NoteGroupMemberRole.vaultMetadata.rawValue,
                sourceKind: "vault_library_exact_json", sourceID: value.id, sourceNoteID: value.id,
                manifestItemID: nil, sourceState: "linked_note", metadataByteLength: Int64(metadataBytes.count), metadataSHA256: metadataSHA))
            index.members.sort { $0.memberID < $1.memberID }
            let indexEncoder = JSONEncoder(); indexEncoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
            let indexBytes = try indexEncoder.encode(index)
            let stage = FileManager.default.temporaryDirectory.appendingPathComponent("note-group-vault-\(UUID().uuidString)", isDirectory: true)
            try FileManager.default.createDirectory(at: stage, withIntermediateDirectories: false, attributes: [.posixPermissions: 0o700])
            defer { try? FileManager.default.removeItem(at: stage) }
            let markdownURL = stage.appendingPathComponent("vault.md")
            let metadataURL = stage.appendingPathComponent("vault.json")
            let indexURL = stage.appendingPathComponent("association.json")
            try markdownBytes.write(to: markdownURL, options: [.atomic, .completeFileProtectionUnlessOpen])
            try metadataBytes.write(to: metadataURL, options: [.atomic, .completeFileProtectionUnlessOpen])
            try indexBytes.write(to: indexURL, options: [.atomic, .completeFileProtectionUnlessOpen])
            var inputs = [NoteGroupMemberInput](), witnesses = [NoteGroupExpectedMemberWitness]()
            for record in current.manifest.members where record.id != bodyID && record.id != metadataID {
                let source = record.role == .association ? indexURL : try groupStore.memberURL(for: record, in: current.token)
                inputs.append(NoteGroupMemberInput(id: record.id, role: record.role, mediaType: record.mediaType, sourceURL: source))
                witnesses.append(NoteGroupExpectedMemberWitness(id: record.id, role: record.role,
                    byteLength: record.role == .association ? Int64(indexBytes.count) : record.byteLength,
                    sha256: record.role == .association ? Self.sha256(indexBytes) : record.sha256))
            }
            if associationRecords.isEmpty {
                inputs.append(NoteGroupMemberInput(id: indexID, role: .association, mediaType: "application/json", sourceURL: indexURL))
                witnesses.append(NoteGroupExpectedMemberWitness(id: indexID, role: .association,
                    byteLength: Int64(indexBytes.count), sha256: Self.sha256(indexBytes)))
            }
            inputs.append(NoteGroupMemberInput(id: bodyID, role: .vault, mediaType: "text/markdown", sourceURL: markdownURL))
            witnesses.append(NoteGroupExpectedMemberWitness(id: bodyID, role: .vault,
                byteLength: Int64(markdownBytes.count), sha256: Self.sha256(markdownBytes)))
            inputs.append(NoteGroupMemberInput(id: metadataID, role: .vaultMetadata, mediaType: "application/json", sourceURL: metadataURL))
            witnesses.append(NoteGroupExpectedMemberWitness(id: metadataID, role: .vaultMetadata,
                byteLength: Int64(metadataBytes.count), sha256: metadataSHA))
            let token = try groupStore.commit(localNoteID: localID, lineageID: current.token.lineageID,
                expected: current.token, members: inputs, expectedSourceWitnesses: witnesses)
            guard let saved = try groupStore.readCurrentIfPresent(localNoteID: localID, lineageID: current.token.lineageID),
                  saved.token == token else { throw NoteGroupStoreError.corruptRevision }
            let updatedSession = try makeEditorSession(token: token, manifest: saved.manifest, store: groupStore)
            groupSessions[localID] = updatedSession
            return value
        }
    }

    @discardableResult
    public func associateLegacyVideo(_ store: NoteVideoAttachmentStore, noteID: String, taskID: UUID,
                                     remoteTaskID: String, sourceRevision: Int, sourceSnapshotSHA256: String,
                                     connection: AgentTaskConnectionIdentity, artifact: AgentTaskArtifact,
                                     verifiedFile: URL) throws -> NoteVideoAttachment {
        try NoteGroupCatalogFence.withWriter {
            if let localID = try? NoteGroupFacade.foundationUUID(noteID) {
                guard groupStoreInitializationFailure == nil, let groupStore else {
                    throw NoteGroupOrdinaryAccessError.groupStoreUnavailable
                }
                guard try currentGroup(localID: localID, store: groupStore) == nil else {
                    throw NoteGroupStoreError.compareAndSwapConflict
                }
            }
            return try store.associate(noteID: noteID, taskID: taskID, remoteTaskID: remoteTaskID,
                sourceRevision: sourceRevision, sourceSnapshotSHA256: sourceSnapshotSHA256,
                connection: connection, artifact: artifact, verifiedFile: verifiedFile)
        }
    }

    public func removeLegacyVideo(_ store: NoteVideoAttachmentStore, noteID: String, attachmentID: UUID) throws {
        try NoteGroupCatalogFence.withWriter {
            if let localID = try? NoteGroupFacade.foundationUUID(noteID) {
                guard groupStoreInitializationFailure == nil, let groupStore else {
                    throw NoteGroupOrdinaryAccessError.groupStoreUnavailable
                }
                guard try currentGroup(localID: localID, store: groupStore) == nil else {
                    throw NoteGroupStoreError.compareAndSwapConflict
                }
            }
            try store.remove(noteID: noteID, attachmentID: attachmentID)
        }
    }

    public func groupedVideoFileURL(attachmentID: UUID, basedOn session: NoteGroupEditorSession) throws -> URL {
        guard let token = session.groupToken, let groupStore else { throw NoteGroupOrdinaryAccessError.groupStoreUnavailable }
        _ = try groupedVideoAttachments(basedOn: session)
        guard let record = try groupStore.readRevision(token).manifest.members.first(where: {
            $0.role == .video && $0.id == attachmentID.uuidString.lowercased()
        }) else { throw NoteVideoAttachmentError.notFound }
        return try groupStore.memberURL(for: record, in: token)
    }

    /// Publishes a verified attachment staged outside the catalog lease. The session token
    /// captured before download remains the CAS parent, so a concurrent edit rejects cleanly.
    @discardableResult
    public func publishGroupedVideo(_ attachment: NoteVideoAttachment, stagedVideoURL: URL,
                                    stagedMetadataURL: URL, basedOn session: NoteGroupEditorSession) throws -> NoteGroupEditorSession {
        guard let expected = session.groupToken, let groupStore else { throw NoteGroupOrdinaryAccessError.groupStoreUnavailable }
        let localID = try NoteGroupFacade.foundationUUID(session.document.id)
        guard localID == expected.localNoteID, try NoteGroupFacade.foundationUUID(attachment.noteID) == localID,
              attachment.sizeBytes > 0, attachment.sizeBytes <= 100 * 1024 * 1024 else { throw NoteVideoAttachmentError.invalidMetadata }
        _ = try LibraryBackupArchive.validateRegularSource(stagedVideoURL, maximumBytes: 100 * 1024 * 1024)
        _ = try LibraryBackupArchive.validateRegularSource(stagedMetadataURL, maximumBytes: 64 * 1024)
        let videoInfo = try LibraryBackupArchive.hashFile(stagedVideoURL)
        let metadataInfo = try LibraryBackupArchive.hashFile(stagedMetadataURL)
        guard Int(videoInfo.size) == attachment.sizeBytes, videoInfo.sha256 == attachment.sha256,
              metadataInfo.size <= 64 * 1024,
              try JSONDecoder().decode(NoteVideoAttachment.self, from: LibraryBackupArchive.readSmallFile(stagedMetadataURL, maximumBytes: 64 * 1024)) == attachment else {
            throw NoteVideoAttachmentError.changedFile
        }
        return try NoteGroupCatalogFence.withWriter {
            guard let current = try groupStore.readCurrentIfPresent(localNoteID: expected.localNoteID, lineageID: expected.lineageID),
                  current.token == expected, !current.manifest.tombstone else { throw NoteGroupStoreError.compareAndSwapConflict }
            let oldVideos = try groupedVideoAttachments(basedOn: session)
            if let existing = oldVideos.first(where: { $0.taskID == attachment.taskID && $0.artifactID == attachment.artifactID }) {
                guard existing.noteID == attachment.noteID, existing.remoteTaskID == attachment.remoteTaskID,
                      existing.sourceRevision == attachment.sourceRevision,
                      existing.sourceSnapshotSHA256 == attachment.sourceSnapshotSHA256,
                      existing.connectionID == attachment.connectionID,
                      existing.connectionRevision == attachment.connectionRevision,
                      existing.connectionKind == attachment.connectionKind,
                      existing.bridgeID == attachment.bridgeID, existing.instanceID == attachment.instanceID,
                      existing.certSHA256 == attachment.certSHA256, existing.sha256 == attachment.sha256,
                      existing.sizeBytes == attachment.sizeBytes, existing.displayName == attachment.displayName else {
                    throw NoteVideoAttachmentError.alreadyAssociated
                }
                return session
            }
            guard !current.manifest.members.contains(where: { $0.role == .video && $0.id == attachment.id.uuidString.lowercased() }) else {
                throw NoteVideoAttachmentError.alreadyAssociated
            }
            let videoMemberID = attachment.id.uuidString.lowercased()
            let metadataMemberID = Self.groupMemberID(note: localID, key: "video-metadata:\(videoMemberID)")
            let indexRecords = current.manifest.members.filter { $0.role == .association }
            guard indexRecords.count <= 1 else { throw NoteGroupStoreError.corruptRevision }
            let indexMemberID: String
            var index: NoteGroupVideoAssociationIndex
            if let indexRecord = indexRecords.first {
                indexMemberID = indexRecord.id
                let indexURL = try groupStore.memberURL(for: indexRecord, in: current.token)
                index = try JSONDecoder().decode(NoteGroupVideoAssociationIndex.self,
                    from: LibraryBackupArchive.readSmallFile(indexURL, maximumBytes: 4 * 1024 * 1024))
            } else {
                let allowedWithoutAssociation: Set<NoteGroupMemberRole> = [.body, .pdf, .cover]
                guard current.manifest.members.contains(where: { $0.role == .body }),
                      current.manifest.members.allSatisfy({ allowedWithoutAssociation.contains($0.role) }) else {
                    throw NoteGroupStoreError.corruptRevision
                }
                indexMemberID = Self.groupMemberID(note: localID, key: "association-index")
                index = NoteGroupVideoAssociationIndex(schemaVersion: 1, localNoteID: localID,
                    legacyNoteID: session.document.id, members: [])
            }
            guard index.localNoteID == localID, index.members.count < 20_000,
                  !index.members.contains(where: { $0.memberID == videoMemberID || $0.memberID == metadataMemberID }) else {
                throw NoteGroupStoreError.corruptRevision
            }
            let metadataSHA = metadataInfo.sha256
            index.members.append(NoteGroupVideoAssociation(memberID: videoMemberID, role: NoteGroupMemberRole.video.rawValue,
                sourceKind: "note_video_attachment", sourceID: videoMemberID, sourceNoteID: attachment.noteID,
                manifestItemID: nil, sourceState: nil, metadataByteLength: Int64(metadataInfo.size), metadataSHA256: metadataSHA))
            index.members.append(NoteGroupVideoAssociation(memberID: metadataMemberID, role: NoteGroupMemberRole.videoMetadata.rawValue,
                sourceKind: "video_attachment_exact_metadata", sourceID: videoMemberID, sourceNoteID: attachment.noteID,
                manifestItemID: nil, sourceState: nil, metadataByteLength: Int64(metadataInfo.size), metadataSHA256: metadataSHA))
            index.members.sort { $0.memberID < $1.memberID }
            let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
            let indexBytes = try encoder.encode(index)
            let stage = FileManager.default.temporaryDirectory.appendingPathComponent("note-group-video-\(UUID().uuidString)", isDirectory: true)
            try FileManager.default.createDirectory(at: stage, withIntermediateDirectories: false, attributes: [.posixPermissions: 0o700])
            defer { try? FileManager.default.removeItem(at: stage) }
            let newIndexURL = stage.appendingPathComponent("association-index.json")
            try indexBytes.write(to: newIndexURL, options: [.atomic, .completeFileProtectionUnlessOpen])
            var inputs = [NoteGroupMemberInput](); var witnesses = [NoteGroupExpectedMemberWitness]()
            for record in current.manifest.members {
                let source = record.role == .association ? newIndexURL : try groupStore.memberURL(for: record, in: current.token)
                inputs.append(NoteGroupMemberInput(id: record.id, role: record.role, mediaType: record.mediaType, sourceURL: source))
                let changedAssociation = record.role == .association
                witnesses.append(NoteGroupExpectedMemberWitness(id: record.id, role: record.role,
                    byteLength: changedAssociation ? Int64(indexBytes.count) : record.byteLength,
                    sha256: changedAssociation ? Self.sha256(indexBytes) : record.sha256))
            }
            if indexRecords.isEmpty {
                inputs.append(NoteGroupMemberInput(id: indexMemberID, role: .association,
                    mediaType: "application/json", sourceURL: newIndexURL))
                witnesses.append(NoteGroupExpectedMemberWitness(id: indexMemberID, role: .association,
                    byteLength: Int64(indexBytes.count), sha256: Self.sha256(indexBytes)))
            }
            inputs.append(NoteGroupMemberInput(id: videoMemberID, role: .video, mediaType: "video/mp4", sourceURL: stagedVideoURL))
            witnesses.append(NoteGroupExpectedMemberWitness(id: videoMemberID, role: .video, byteLength: Int64(videoInfo.size), sha256: videoInfo.sha256))
            inputs.append(NoteGroupMemberInput(id: metadataMemberID, role: .videoMetadata, mediaType: "application/json", sourceURL: stagedMetadataURL))
            witnesses.append(NoteGroupExpectedMemberWitness(id: metadataMemberID, role: .videoMetadata, byteLength: Int64(metadataInfo.size), sha256: metadataInfo.sha256))
            let savedToken = try groupStore.commit(localNoteID: localID, lineageID: expected.lineageID,
                expected: expected, members: inputs, expectedSourceWitnesses: witnesses)
            guard let saved = try groupStore.readCurrentIfPresent(localNoteID: localID, lineageID: expected.lineageID), saved.token == savedToken else {
                throw NoteGroupStoreError.corruptRevision
            }
            return try makeEditorSession(token: saved.token, manifest: saved.manifest, store: groupStore)
        }
    }

    @discardableResult
    public func removeGroupedVideo(attachmentID: UUID, basedOn session: NoteGroupEditorSession) throws -> NoteGroupEditorSession {
        guard let expected = session.groupToken, let groupStore else { throw NoteGroupOrdinaryAccessError.groupStoreUnavailable }
        return try NoteGroupCatalogFence.withWriter {
            guard let current = try groupStore.readCurrentIfPresent(localNoteID: expected.localNoteID, lineageID: expected.lineageID),
                  current.token == expected, !current.manifest.tombstone else { throw NoteGroupStoreError.compareAndSwapConflict }
            _ = try groupedVideoAttachments(basedOn: session)
            let id = attachmentID.uuidString.lowercased()
            let metadataID = Self.groupMemberID(note: expected.localNoteID, key: "video-metadata:\(id)")
            guard current.manifest.members.contains(where: { $0.role == .video && $0.id == id }),
                  current.manifest.members.contains(where: { $0.role == .videoMetadata && $0.id == metadataID }),
                  let indexRecord = current.manifest.members.first(where: { $0.role == .association }) else { throw NoteVideoAttachmentError.notFound }
            let indexURL = try groupStore.memberURL(for: indexRecord, in: current.token)
            var index = try JSONDecoder().decode(NoteGroupVideoAssociationIndex.self,
                from: LibraryBackupArchive.readSmallFile(indexURL, maximumBytes: 4 * 1024 * 1024))
            guard index.members.contains(where: { $0.memberID == id && $0.role == NoteGroupMemberRole.video.rawValue }),
                  index.members.contains(where: { $0.memberID == metadataID && $0.role == NoteGroupMemberRole.videoMetadata.rawValue }) else {
                throw NoteGroupStoreError.corruptRevision
            }
            index.members.removeAll { $0.memberID == id || $0.memberID == metadataID }
            let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
            let bytes = try encoder.encode(index)
            let stage = FileManager.default.temporaryDirectory.appendingPathComponent("note-group-video-remove-\(UUID().uuidString)", isDirectory: true)
            try FileManager.default.createDirectory(at: stage, withIntermediateDirectories: false, attributes: [.posixPermissions: 0o700])
            defer { try? FileManager.default.removeItem(at: stage) }
            let stagedIndex = stage.appendingPathComponent("association-index.json")
            try bytes.write(to: stagedIndex, options: [.atomic, .completeFileProtectionUnlessOpen])
            var inputs = [NoteGroupMemberInput](); var witnesses = [NoteGroupExpectedMemberWitness]()
            for record in current.manifest.members where record.id != id && record.id != metadataID {
                let source = record.role == .association ? stagedIndex : try groupStore.memberURL(for: record, in: current.token)
                inputs.append(NoteGroupMemberInput(id: record.id, role: record.role, mediaType: record.mediaType, sourceURL: source))
                witnesses.append(NoteGroupExpectedMemberWitness(id: record.id, role: record.role,
                    byteLength: record.role == .association ? Int64(bytes.count) : record.byteLength,
                    sha256: record.role == .association ? Self.sha256(bytes) : record.sha256))
            }
            let token = try groupStore.commit(localNoteID: expected.localNoteID, lineageID: expected.lineageID,
                expected: expected, members: inputs, expectedSourceWitnesses: witnesses)
            guard let saved = try groupStore.readCurrentIfPresent(localNoteID: expected.localNoteID, lineageID: expected.lineageID), saved.token == token else {
                throw NoteGroupStoreError.corruptRevision
            }
            return try makeEditorSession(token: token, manifest: saved.manifest, store: groupStore)
        }
    }

    private func makeEditorSession(token: NoteGroupVersionToken, manifest: NoteGroupRevisionManifest,
                                   store: NoteGroupStore) throws -> NoteGroupEditorSession {
        guard !manifest.tombstone else { throw NoteGroupOrdinaryAccessError.retired }
        let records = manifest.members
        let bodies = records.filter { $0.role == .body }
        let pdfs = records.filter { $0.role == .pdf }
        let videos = records.filter { $0.role == .video }
        let videoMetadata = records.filter { $0.role == .videoMetadata }
        let vault = records.filter { $0.role == .vault }
        let vaultMetadata = records.filter { $0.role == .vaultMetadata }
        guard bodies.count == 1, let body = bodies.first,
              videos.count == videoMetadata.count, vault.count == vaultMetadata.count else {
            throw NoteGroupStoreError.corruptRevision
        }
        let members = try records.map { record in
            NoteGroupReadMember(record: record, url: try store.memberURL(for: record, in: token))
        }
        guard let bodyMember = members.first(where: { $0.record.id == body.id }) else { throw NoteGroupStoreError.corruptRevision }
        let document = try NoteDocument.decode(LibraryBackupArchive.readSmallFile(bodyMember.url,
            maximumBytes: Int64(NoteDocument.maximumEncodedBytes)))
        guard try NoteGroupFacade.foundationUUID(document.id) == token.localNoteID,
              (document.pdfPageCount > 0 ? pdfs.count == 1 : pdfs.isEmpty) else {
            throw NoteGroupStoreError.corruptRevision
        }
        return NoteGroupEditorSession(document: document, groupToken: token, members: members)
    }

    public func registerDraftRevision(noteID: String, revision: Int) {
        revisionGate.register(noteID: noteID, revision: revision)
    }

    public func nextDraftRevision(noteID: String) -> Int {
        revisionGate.nextRevision(noteID: noteID)
    }

    @discardableResult
    public func persistRegisteredDraft(_ note: NoteDocument, revision: Int,
                                       baseGroupToken: NoteGroupVersionToken? = nil) async throws -> Bool {
        try await NoteGroupCatalogFence.withWriter {
            let persisted = try await draftWorker.persist(document: note, revision: revision, baseGroupToken: baseGroupToken)
            if persisted && revisionGate.shouldCommit(noteID: note.id, revision: revision) {
                pendingDrafts.removeAll { $0.id == note.id }
                pendingDrafts.append(NotePendingDraft(revision: revision, document: note, baseGroupToken: baseGroupToken))
                pendingDrafts.sort { $0.document.updatedAt > $1.document.updatedAt }
            }
            return persisted
        }
    }

    public func markCanonicalSaved(noteID: String, revision: Int) async throws {
        try await NoteGroupCatalogFence.withWriter {
            await draftWorker.canonicalSaved(noteID: noteID, revision: revision)
            revisionGate.markSaved(noteID: noteID, revision: revision)
            pendingDrafts.removeAll { $0.id == noteID && $0.revision <= revision }
        }
    }

    public func pendingDraft(noteID: String) -> NotePendingDraft? {
        pendingDrafts.first { $0.id == noteID }
    }

    public func editorSessionIfLoaded(noteID: String) -> NoteGroupEditorSession? {
        groupSessions[canonicalID(noteID)]
    }

    /// Resolves a fresh current group snapshot for resource readers. A corrupt marker throws;
    /// callers must not fall back to legacy PDFs/covers after that failure.
    public func currentGroupSession(noteID: String) throws -> NoteGroupEditorSession? {
        guard let localID = try? NoteGroupFacade.foundationUUID(noteID) else { return nil }
        guard groupStoreInitializationFailure == nil, let groupStore else {
            throw NoteGroupOrdinaryAccessError.groupStoreUnavailable
        }
        guard let current = try currentGroup(localID: localID, store: groupStore) else { return nil }
        let session = try makeEditorSession(token: current.token, manifest: current.manifest, store: groupStore)
        groupSessions[localID] = session
        return session
    }

    private func currentGroup(localID: String, store: NoteGroupStore) throws -> (token: NoteGroupVersionToken, manifest: NoteGroupRevisionManifest)? {
        let matches = try store.currentGroupTokens().filter { $0.localNoteID == localID }
        guard matches.count <= 1 else { throw NoteGroupStoreError.corruptRevision }
        guard let token = matches.first else { return nil }
        guard let current = try store.readCurrentIfPresent(localNoteID: localID, lineageID: token.lineageID),
              current.token == token else { throw NoteGroupStoreError.corruptRevision }
        return current
    }

    private func canonicalID(_ id: String) -> String {
        (try? NoteGroupFacade.foundationUUID(id)) ?? id.lowercased()
    }

    private static func groupMemberID(note: String, key: String) -> String {
        var bytes = Array(SHA256.hash(data: Data("padnote-group-member-v1|\(note)|\(key)".utf8)).prefix(16))
        bytes[6] = (bytes[6] & 0x0f) | 0x50
        bytes[8] = (bytes[8] & 0x3f) | 0x80
        let value = UUID(uuid: (bytes[0], bytes[1], bytes[2], bytes[3], bytes[4], bytes[5], bytes[6], bytes[7],
                                bytes[8], bytes[9], bytes[10], bytes[11], bytes[12], bytes[13], bytes[14], bytes[15]))
        return value.uuidString.lowercased()
    }

    /// Canonicalizes only parseable UUID source IDs for comparison with immutable member IDs.
    /// The raw association and metadata bytes remain untouched.
    static func canonicalGroupMemberID(_ raw: String) -> String? {
        guard let value = UUID(uuidString: raw) else { return nil }
        return value.uuidString.lowercased()
    }

    private static func sameVaultRecord(_ lhs: VaultNote, _ rhs: VaultNote) -> Bool {
        lhs.id == rhs.id && lhs.title == rhs.title && lhs.markdown == rhs.markdown
            && lhs.sourceUpdatedAt == rhs.sourceUpdatedAt && lhs.createdAt == rhs.createdAt
            && lhs.archiveOrigin == rhs.archiveOrigin && lhs.archiveSourceNoteID == rhs.archiveSourceNoteID
            && lhs.archiveLinkedNoteID == rhs.archiveLinkedNoteID && lhs.archiveSourceState == rhs.archiveSourceState
            && lhs.restoreTransactionID == rhs.restoreTransactionID && lhs.restoreGroupID == rhs.restoreGroupID
            && lhs.archiveDigitizationMetadata == rhs.archiveDigitizationMetadata
    }

    private static func sha256(_ data: Data) -> String {
        SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }

    public func delete(_ note: NoteDocument) throws {
        try NoteGroupCatalogFence.withWriter { try deleteLegacy(note) }
    }

    /// Retires one current group revision by CAS. Its immutable resource history is retained;
    /// only the visible in-memory note index is changed.
    @discardableResult
    public func delete(_ note: NoteDocument, basedOn session: NoteGroupEditorSession) throws -> NoteGroupVersionToken? {
        guard let expected = session.groupToken else {
            guard note.id == session.document.id else { throw NoteGroupStoreError.invalidIdentifier }
            try delete(note)
            return nil
        }
        let localID = try NoteGroupFacade.foundationUUID(note.id)
        guard localID == expected.localNoteID,
              try NoteGroupFacade.foundationUUID(session.document.id) == expected.localNoteID else {
            throw NoteGroupStoreError.invalidIdentifier
        }
        guard groupStoreInitializationFailure == nil, let groupStore else {
            throw NoteGroupOrdinaryAccessError.groupStoreUnavailable
        }
        return try NoteGroupCatalogFence.withWriter {
            guard let current = try groupStore.readCurrentIfPresent(localNoteID: expected.localNoteID,
                                                                    lineageID: expected.lineageID),
                  current.token == expected, !current.manifest.tombstone else {
                throw NoteGroupStoreError.compareAndSwapConflict
            }
            let tombstone = try groupStore.tombstone(localNoteID: expected.localNoteID,
                                                      lineageID: expected.lineageID, expected: expected)
            groupSessions.removeValue(forKey: expected.localNoteID)
            notes.removeAll { canonicalID($0.id) == expected.localNoteID }
            return tombstone
        }
    }

    private func deleteLegacy(_ note: NoteDocument) throws {
        let checked = try note.validated()
        try requireLegacyMutationAllowed(noteID: checked.id)
        try NoteVideoAttachmentStore.withSourceMutation {
            revisionGate.invalidate(noteID: checked.id)
            let target = noteURL(for: checked)
            let targets = [target, sidecarURL(target, suffix: ".bak"), sidecarURL(target, suffix: ".tmp")] + (pdfURL(for: checked).map { [$0] } ?? [])
            for url in targets {
                if fileManager.fileExists(atPath: url.path) { try fileManager.removeItem(at: url) }
            }
            NoteAtomicFile.removeFamily(draftURL(noteID: checked.id))
            NoteVideoAttachmentStore.markSourceDeleted(checked.id)
        }
        notes.removeAll { $0.id == note.id }
        pendingDrafts.removeAll { $0.id == note.id }
    }

    public func importNote(from url: URL) throws -> NoteDocument {
        try NoteGroupCatalogFence.withWriter { try importNoteLegacy(from: url) }
    }

    private func importNoteLegacy(from url: URL) throws -> NoteDocument {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        let data = try readLimited(url, limit: NoteArchive.maxArchiveBytes)
        let imported: NoteDocument
        var originalPDF: Data?
        if data.count >= 4 && data[0] == 0x50 && data[1] == 0x4b {
            let payload = try NoteArchive.decode(data)
            imported = payload.note
            originalPDF = payload.pdf
        } else {
            imported = try NoteDocument.decode(data)
            if imported.pdfPageCount > 0 {
                throw NoteDocumentError.unsupportedExport("PDF 笔记必须导入包含原文的 .padnote.zip 包")
            }
        }
        var copy = imported
        copy.id = uniqueID()
        copy.updatedAt = NoteDocument.nowMillis()
        if let originalPDF {
            let pdfTarget = directory.appendingPathComponent("\(copy.id).pdf")
            try writeAtomically(originalPDF, to: pdfTarget)
            do { try save(copy) }
            catch { try? fileManager.removeItem(at: pdfTarget); throw error }
        } else {
            try save(copy)
        }
        return copy
    }

    /// Returns a stable, already-saved note snapshot only if its on-disk bytes still decode to the current revision.
    public func backupSourceURL(for note: NoteDocument) throws -> URL {
        guard notes.contains(where: { $0.id == note.id && $0 == note }), safeID(note.id),
              !pendingDrafts.contains(where: { $0.id == note.id }) else { throw LibraryBackupError.sourceChanged }
        let url: URL
        if let session = try currentGroupSession(noteID: note.id), session.groupToken != nil {
            guard session.document == note,
                  let body = session.members.first(where: { $0.record.role == .body }) else {
                throw LibraryBackupError.sourceChanged
            }
            url = body.url
        } else {
            url = noteURL(for: note)
        }
        _ = try LibraryBackupArchive.validateRegularSource(url, maximumBytes: Int64(NoteDocument.maximumEncodedBytes))
        return url
    }

    /// Commits the book JSON last, after the restore coordinator has promoted and verified its resources.
    public func restoreBackupNote(_ note: NoteDocument, stagedPDF: URL?, expectedPDF: LibraryBackupManifest.Resource? = nil,
                                  transactionID: String, groupID: String, expectedNoteSHA256: String?, journalRoot: URL, cancellation: LibraryBackupCancellationToken? = nil) async throws {
        try await NoteGroupCatalogFence.withWriter {
            try await restoreBackupNoteLegacy(note, stagedPDF: stagedPDF, expectedPDF: expectedPDF,
                transactionID: transactionID, groupID: groupID, expectedNoteSHA256: expectedNoteSHA256,
                journalRoot: journalRoot, cancellation: cancellation)
        }
    }

    private func restoreBackupNoteLegacy(_ note: NoteDocument, stagedPDF: URL?, expectedPDF: LibraryBackupManifest.Resource?,
                                         transactionID: String, groupID: String, expectedNoteSHA256: String?,
                                         journalRoot: URL, cancellation: LibraryBackupCancellationToken?) async throws {
        try requireLegacyMutationAllowed(noteID: note.id)
        guard safeID(note.id), !notes.contains(where: { $0.id == note.id }), !fileManager.fileExists(atPath: noteURL(for: note).path),
              let expectedNoteSHA256 else { throw LibraryBackupError.transaction("恢复目标笔记 ID 或摘要无效") }
        let encodedNote = try note.validated().encoded()
        let encodedSHA = SHA256.hash(data: encodedNote).map { String(format: "%02x", $0) }.joined()
        guard encodedSHA == expectedNoteSHA256 else { throw LibraryBackupError.sourceChanged }
        try LibraryBackupTransactionGate.createPendingMarker(noteID: note.id, transactionID: transactionID, groupID: groupID,
            expectedNoteSHA256: expectedNoteSHA256, expectedPDFSHA256: expectedPDF?.sha256,
            in: directory, journalRoot: journalRoot)
        var promotedPDF: URL?
        do {
            if note.pdfPageCount > 0 {
                guard let stagedPDF, let expectedPDF else { throw LibraryBackupError.transaction("PDF 笔记缺少 PDF 原文") }
                let pdfTarget = directory.appendingPathComponent("\(note.id).pdf")
                guard !fileManager.fileExists(atPath: pdfTarget.path) else { throw LibraryBackupError.transaction("PDF 恢复目标已存在") }
                let pdfLimit = Int64(maxPDFBytes)
                try await Task.detached(priority: .userInitiated) {
                    let copied = try LibraryBackupArchive.copyVerified(stagedPDF, to: pdfTarget, maximumBytes: pdfLimit, cancellation: cancellation)
                    guard expectedPDF.role == "pdf_original", copied.size == expectedPDF.byteLength,
                          copied.sha256 == expectedPDF.sha256 else { throw LibraryBackupError.sourceChanged }
                    guard let document = PDFDocument(url: pdfTarget), !document.isEncrypted, !document.isLocked,
                          document.pageCount == note.pdfPageCount, document.pageCount > 0, document.pageCount <= 500 else {
                        throw LibraryBackupError.invalidManifest("恢复后的 PDF 校验失败")
                    }
                }.value
                promotedPDF = pdfTarget
            } else if stagedPDF != nil || expectedPDF != nil { throw LibraryBackupError.transaction("普通笔记不能附带 PDF") }
            try cancellation?.check()
            try writeAtomically(encodedNote, to: noteURL(for: note)) { persisted in
                guard try NoteDocument.decode(persisted) == note else { throw NoteDocumentError.malformed("备份恢复笔记复核失败") }
            }
        } catch {
            if let promotedPDF { try? fileManager.removeItem(at: promotedPDF) }
            throw error
        }
    }

    public func verifyBackupNote(_ note: NoteDocument) async throws {
        let url = noteURL(for: note)
        let pdf = note.pdfPageCount > 0 ? pdfURL(for: note) : nil
        try await Task.detached(priority: .userInitiated) {
            let bytes = try LibraryBackupArchive.readSmallFile(url, maximumBytes: Int64(NoteDocument.maximumEncodedBytes))
            guard try NoteDocument.decode(bytes) == note else { throw LibraryBackupError.sourceChanged }
            if note.pdfPageCount > 0 {
                guard let pdf, let document = PDFDocument(url: pdf), document.pageCount == note.pdfPageCount,
                      !document.isEncrypted, !document.isLocked else {
                    throw LibraryBackupError.sourceChanged
                }
            }
        }.value
    }

    public func completeBackupNoteRestore(id: String, transactionID: String, groupID: String,
                                          expectedNoteSHA256: String?, journalRoot: URL) throws {
        try NoteGroupCatalogFence.withWriter {
            try completeBackupNoteRestoreLegacy(id: id, transactionID: transactionID, groupID: groupID,
                expectedNoteSHA256: expectedNoteSHA256, journalRoot: journalRoot)
        }
    }

    private func completeBackupNoteRestoreLegacy(id: String, transactionID: String, groupID: String,
                                                 expectedNoteSHA256: String?, journalRoot: URL) throws {
        guard let expectedNoteSHA256 else { throw LibraryBackupError.transaction("恢复笔记摘要缺失") }
        try LibraryBackupTransactionGate.removePendingMarker(noteID: id, transactionID: transactionID, groupID: groupID,
            expectedNoteSHA256: expectedNoteSHA256, in: directory, journalRoot: journalRoot)
    }

    func rollbackBackupNoteIfPresent(id: String, transactionID: String, groupID: String, journalRoot: URL) throws {
        guard safeID(id) else { throw LibraryBackupError.unsafeFile }
        let marker = directory.appendingPathComponent(".backup-restore-pending", isDirectory: true).appendingPathComponent(id + ".json")
        let note = directory.appendingPathComponent("\(id).json")
        let pdf = directory.appendingPathComponent("\(id).pdf")
        guard fileManager.fileExists(atPath: marker.path) else {
            guard !fileManager.fileExists(atPath: note.path), !fileManager.fileExists(atPath: pdf.path) else {
                throw LibraryBackupError.transaction("存在没有本地恢复标记的恢复目标")
            }
            return
        }
        try rollbackBackupNote(id: id, transactionID: transactionID, groupID: groupID, journalRoot: journalRoot)
    }

    public func rollbackBackupNote(id: String, transactionID: String, groupID: String, journalRoot: URL) throws {
        try NoteGroupCatalogFence.withWriter {
            try rollbackBackupNoteLegacy(id: id, transactionID: transactionID, groupID: groupID, journalRoot: journalRoot)
        }
    }

    private func rollbackBackupNoteLegacy(id: String, transactionID: String, groupID: String, journalRoot: URL) throws {
        guard safeID(id) else { throw LibraryBackupError.transaction("无法验证备份恢复事务所有权") }
        let markerRoot = directory.appendingPathComponent(".backup-restore-pending", isDirectory: true)
        let markerURL = markerRoot.appendingPathComponent(id + ".json")
        _ = try LibraryBackupArchive.validateRegularSource(markerURL, maximumBytes: 16 * 1024)
        let markerBytes = try LibraryBackupArchive.readSmallFile(markerURL, maximumBytes: 16 * 1024)
        let marker = try JSONDecoder().decode(BackupPendingNoteMarker.self, from: markerBytes)
        guard marker.noteID == id, marker.transactionID == transactionID, marker.groupID == groupID,
              LibraryBackupTransactionGate.ownsPendingNoteMarker(marker, in: journalRoot) else {
            throw LibraryBackupError.transaction("无法验证备份恢复事务所有权")
        }
        let note = directory.appendingPathComponent("\(id).json")
        if fileManager.fileExists(atPath: note.path) {
            let bytes = try LibraryBackupArchive.readSmallFile(note, maximumBytes: Int64(NoteDocument.maximumEncodedBytes))
            let digest = SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined()
            guard digest == marker.expectedNoteSHA256 else { throw LibraryBackupError.sourceChanged }
            try fileManager.removeItem(at: note)
        }
        let pdf = directory.appendingPathComponent("\(id).pdf")
        if fileManager.fileExists(atPath: pdf.path) {
            guard let expected = marker.expectedPDFSHA256 else { throw LibraryBackupError.unsafeFile }
            let actual = try LibraryBackupArchive.hashFile(pdf)
            guard actual.sha256 == expected else { throw LibraryBackupError.sourceChanged }
            try fileManager.removeItem(at: pdf)
        }
        try LibraryBackupTransactionGate.removePendingMarker(noteID: id, transactionID: transactionID, groupID: groupID,
            expectedNoteSHA256: marker.expectedNoteSHA256, in: directory, journalRoot: journalRoot, requireCommitted: false)
        notes.removeAll { $0.id == id }
    }

    public func exportURL(for note: NoteDocument) throws -> URL {
        var checked = try note.validated()
        let currentGroup = try currentGroupSession(noteID: note.id)
        if let currentGroup, currentGroup.groupToken != nil { checked = currentGroup.document }
        let exports = directory.appendingPathComponent("Exports", isDirectory: true)
        try fileManager.createDirectory(at: exports, withIntermediateDirectories: true)
        let isPDF = checked.pdfPageCount > 0
        let filename = "\(safeFilename(checked.title, fallback: "note"))-\(checked.id.prefix(8)).padnote.\(isPDF ? "zip" : "json")"
        let output = exports.appendingPathComponent(filename)
        if isPDF {
            let source = currentGroup?.members.first(where: { $0.record.role == .pdf })?.url ??
                (currentGroup?.groupToken == nil ? pdfURL(for: checked) : nil)
            guard let source else { throw NoteArchiveError.invalidPDF("PDF 原文不存在") }
            let pdf = try readLimited(source, limit: NoteArchive.maxPDFBytes)
            try NoteArchive.encode(note: checked, pdf: pdf).write(to: output, options: .atomic)
        } else {
            try checked.encoded().write(to: output, options: .atomic)
        }
        return output
    }

    public func exportRecoveryURL(for item: NoteRecoveryItem) throws -> URL {
        guard recoveryItems.contains(where: { $0.id == item.id }),
              fileManager.fileExists(atPath: item.sourceURL.path) else {
            throw CocoaError(.fileNoSuchFile)
        }
        let exports = directory.appendingPathComponent("Exports", isDirectory: true)
        try fileManager.createDirectory(at: exports, withIntermediateDirectories: true)
        let name = safeFilename(item.filename, fallback: "damaged-note")
        let output = exports.appendingPathComponent("\(name)-\(UUID().uuidString.prefix(8)).recovery")
        try fileManager.copyItem(at: item.sourceURL, to: output)
        return output
    }

    public func reload() {
        loadNotes()
    }

    public func pdfURL(for note: NoteDocument) -> URL? {
        guard safeID(note.id) else { return nil }
        if let localID = try? NoteGroupFacade.foundationUUID(note.id) {
            do {
                if let session = try currentGroupSession(noteID: localID), session.groupToken != nil {
                    return session.members.first(where: { $0.record.role == .pdf })?.url
                }
            } catch {
                errorMessage = "当前笔记 PDF 无法安全读取：\(error.localizedDescription)"
                return nil
            }
        }
        let url = directory.appendingPathComponent("\(note.id).pdf")
        return fileManager.fileExists(atPath: url.path) ? url : nil
    }

    public func importPDF(from url: URL) throws -> NoteDocument {
        try NoteGroupCatalogFence.withWriter { try importPDFLegacy(from: url) }
    }

    private func importPDFLegacy(from url: URL) throws -> NoteDocument {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        let data = try readLimited(url, limit: maxPDFBytes)
        guard let document = PDFDocument(data: data), document.pageCount > 0 else {
            throw NoteDocumentError.malformed("Unable to read PDF")
        }
        try NoteArchive.validatePDF(data, expectedPageCount: document.pageCount)
        let firstBounds = document.page(at: 0)?.bounds(for: .mediaBox) ?? CGRect(x: 0, y: 0, width: 768, height: 1086)
        let title = safeTitle(url.deletingPathExtension().lastPathComponent)
        var note = NoteDocument(title: title)
        note.pageWidth = max(1, Double(abs(firstBounds.width)))
        note.pageHeight = max(1, Double(abs(firstBounds.height)))
        note.pageCount = document.pageCount
        note.pdfPageCount = document.pageCount
        note.viewportCenterX = note.pageWidth / 2
        note.viewportCenterY = note.pageHeight / 2
        let pdfTarget = directory.appendingPathComponent("\(note.id).pdf")
        do {
            try writeAtomically(data, to: pdfTarget)
            try save(note)
        } catch {
            try? fileManager.removeItem(at: pdfTarget)
            throw error
        }
        return note
    }

    private func loadNotes() {
        errorMessage = nil
        groupSessions.removeAll()
        guard groupStoreInitializationFailure == nil, let groupStore else {
            notes = []
            errorMessage = "笔记版本索引无法安全验证，笔记暂未显示：\(groupStoreInitializationFailure ?? "unavailable")"
            return
        }
        guard let urls = try? fileManager.contentsOfDirectory(at: directory, includingPropertiesForKeys: [.isRegularFileKey], options: [.skipsHiddenFiles]) else { return }
        var candidates = Set<URL>()
        var recoveries = [NoteRecoveryItem]()
        for url in urls {
            if url.pathExtension.lowercased() == "json" && !url.lastPathComponent.hasPrefix(".") {
                candidates.insert(url)
            } else if url.lastPathComponent.hasSuffix(".json.bak") {
                candidates.insert(URL(fileURLWithPath: String(url.path.dropLast(4))))
            } else if url.lastPathComponent.hasSuffix(".json.tmp") {
                candidates.insert(URL(fileURLWithPath: String(url.path.dropLast(4))))
            } else if url.lastPathComponent.contains(".corrupt-") || url.lastPathComponent.contains(".failed-") {
                recoveries.append(recoveryItem(url, reason: "此前加载或保存时保留的原始文件"))
            }
        }
        var loaded: [NoteDocument] = []
        let pendingBackupIDs: Set<String>
        do { pendingBackupIDs = try LibraryBackupTransactionGate.pendingNoteIDs(in: directory, journalRoot: backupTransactionDirectory) }
        catch {
            notes = []
            errorMessage = "备份恢复标记无法安全验证，笔记暂未显示：\(error.localizedDescription)"
            return
        }
        var groupedNoteIDs = Set<String>()
        do {
            for token in try groupStore.currentGroupTokens() {
                let localID = try NoteGroupFacade.foundationUUID(token.localNoteID)
                guard let current = try groupStore.readCurrentIfPresent(localNoteID: localID, lineageID: token.lineageID),
                      current.token == token else { throw NoteGroupStoreError.corruptRevision }
                groupedNoteIDs.insert(localID)
                guard !current.manifest.tombstone else { continue }
                let session = try makeEditorSession(token: token, manifest: current.manifest, store: groupStore)
                loaded.append(session.document)
                groupSessions[localID] = session
            }
        } catch {
            notes = []
            errorMessage = "笔记版本索引损坏，未回退到旧文件：\(error.localizedDescription)"
            return
        }
        for url in candidates {
            do {
                let candidateID = url.deletingPathExtension().lastPathComponent
                if let canonicalID = try? NoteGroupFacade.foundationUUID(candidateID), groupedNoteIDs.contains(canonicalID) { continue }
                let result = try recoverAndLoad(url)
                let value = result.note
                recoveries.append(contentsOf: result.recoveries)
                if pendingBackupIDs.contains(value.id) { continue }
                if value.pdfPageCount > 0 && !fileManager.fileExists(atPath: pdfURL(for: value)?.path ?? "") {
                    throw NoteDocumentError.malformed("PDF original is missing")
                }
                loaded.append(value)
            } catch {
                for candidate in noteCandidateURLs(url) where fileManager.fileExists(atPath: candidate.path) {
                    recoveries.append(recoveryItem(candidate, reason: error.localizedDescription))
                }
                errorMessage = [errorMessage, "Skipped \(url.lastPathComponent): \(error.localizedDescription)"]
                    .compactMap { $0 }.joined(separator: "\n")
            }
        }
        notes = loaded.sorted { $0.updatedAt > $1.updatedAt }
        loadPendingDrafts(recoveries: &recoveries)
        recoveryItems = Dictionary(grouping: recoveries, by: \.id).compactMap { $0.value.first }
            .sorted { $0.filename < $1.filename }
    }

    private func recoverAndLoad(_ target: URL) throws -> (note: NoteDocument, recoveries: [NoteRecoveryItem]) {
        var valid = [(url: URL, data: Data, note: NoteDocument)]()
        var invalid = [(url: URL, error: Error)]()
        for url in noteCandidateURLs(target) where fileManager.fileExists(atPath: url.path) {
            do {
                let data = try readLimited(url, limit: NoteDocument.maximumEncodedBytes)
                valid.append((url, data, try NoteDocument.decode(data)))
            } catch { invalid.append((url, error)) }
        }
        guard let selected = valid.max(by: {
            if $0.note.updatedAt == $1.note.updatedAt { return $0.url != target && $1.url == target }
            return $0.note.updatedAt < $1.note.updatedAt
        }) else {
            throw invalid.first?.error ?? CocoaError(.fileNoSuchFile)
        }
        var recoveries = invalid.map { recoveryItem($0.url, reason: $0.error.localizedDescription) }
        var promotionSucceeded = true
        if selected.url != target {
            if invalid.contains(where: { $0.url == target }), let preserved = preserveCorrupt(target) {
                recoveries.removeAll { $0.sourceURL == target }
                recoveries.append(recoveryItem(preserved, reason: "原笔记损坏，已从可读取的保存候选恢复"))
            }
            do {
                try promoteRecovery(selected.data, to: target) { data in
                    guard try NoteDocument.decode(data) == selected.note else {
                        throw NoteDocumentError.malformed("Recovered note verification failed")
                    }
                }
            } catch {
                promotionSucceeded = false
                recoveries.append(recoveryItem(selected.url,
                    reason: "恢复提升失败，原候选已保留：\(error.localizedDescription)"))
                errorMessage = [errorMessage, "Could not promote \(selected.url.lastPathComponent): \(error.localizedDescription)"]
                    .compactMap { $0 }.joined(separator: "\n")
            }
        }
        if promotionSucceeded {
            for url in [sidecarURL(target, suffix: ".tmp"), sidecarURL(target, suffix: ".bak")]
            where fileManager.fileExists(atPath: url.path)
                  && !invalid.contains(where: { $0.url == url }) {
                try? fileManager.removeItem(at: url)
            }
        }
        return (selected.note, recoveries)
    }

    private func writeAtomically(_ data: Data, to target: URL, verify: (Data) throws -> Void = { _ in }) throws {
        try NoteAtomicFile.write(data, to: target, faultInjector: faultInjector, verify: verify)
    }

    private func preserveCorrupt(_ target: URL) -> URL? {
        guard fileManager.fileExists(atPath: target.path) else { return nil }
        let stamp = Int(Date().timeIntervalSince1970 * 1000)
        let destination = target.deletingLastPathComponent().appendingPathComponent("\(target.lastPathComponent).corrupt-\(stamp)")
        do { try fileManager.moveItem(at: target, to: destination); return destination }
        catch { return nil }
    }

    private func loadPendingDrafts(recoveries: inout [NoteRecoveryItem]) {
        guard let urls = try? fileManager.contentsOfDirectory(
            at: draftDirectory,
            includingPropertiesForKeys: [.isRegularFileKey],
            options: [.skipsHiddenFiles]
        ) else { pendingDrafts = []; return }
        var targets = Set<URL>()
        for url in urls {
            if url.lastPathComponent.hasSuffix(".draft.json") { targets.insert(url) }
            else if url.lastPathComponent.hasSuffix(".draft.json.bak") || url.lastPathComponent.hasSuffix(".draft.json.tmp") {
                targets.insert(URL(fileURLWithPath: String(url.path.dropLast(4))))
            } else if url.lastPathComponent.contains(".corrupt-") {
                recoveries.append(recoveryItem(url, reason: "损坏的未保存恢复副本"))
            }
        }
        var drafts = [NotePendingDraft]()
        for target in targets {
            let family = [target, sidecarURL(target, suffix: ".tmp"), sidecarURL(target, suffix: ".bak")]
                .filter { fileManager.fileExists(atPath: $0.path) }
            var valid = [(url: URL, data: Data, value: PersistedNoteDraft)]()
            var invalidURLs = Set<URL>()
            for url in family {
                do {
                    let data = try readLimited(url, limit: NoteDocument.maximumEncodedBytes + 64 * 1024)
                    valid.append((url, data, try PersistedNoteDraft.decode(data)))
                } catch {
                    invalidURLs.insert(url)
                    recoveries.append(recoveryItem(url, reason: error.localizedDescription))
                }
            }
            guard let selected = valid.max(by: { $0.value.revision < $1.value.revision }) else { continue }
            var promotionSucceeded = true
            if selected.url != target {
                if invalidURLs.contains(target), let preserved = preserveCorrupt(target) {
                    recoveries.removeAll { $0.sourceURL == target }
                    recoveries.append(recoveryItem(preserved, reason: "损坏的草稿已从可读取候选恢复"))
                }
                do {
                    try promoteRecovery(selected.data, to: target) { data in
                        guard try PersistedNoteDraft.decode(data) == selected.value else {
                            throw NoteDocumentError.malformed("Recovery draft verification failed")
                        }
                    }
                } catch {
                    promotionSucceeded = false
                    recoveries.append(recoveryItem(selected.url,
                        reason: "草稿恢复提升失败，原候选已保留：\(error.localizedDescription)"))
                    errorMessage = [errorMessage, "Could not promote \(selected.url.lastPathComponent): \(error.localizedDescription)"]
                        .compactMap { $0 }.joined(separator: "\n")
                }
            }
            if promotionSucceeded {
                for url in [sidecarURL(target, suffix: ".tmp"), sidecarURL(target, suffix: ".bak")]
                where fileManager.fileExists(atPath: url.path) && !invalidURLs.contains(url) {
                    try? fileManager.removeItem(at: url)
                }
            }
            if let canonical = notes.first(where: { $0.id == selected.value.document.id }), canonical == selected.value.document {
                NoteAtomicFile.removeFamily(target)
            } else {
                revisionGate.register(noteID: selected.value.document.id, revision: selected.value.revision)
                drafts.append(NotePendingDraft(revision: selected.value.revision, document: selected.value.document,
                                               baseGroupToken: selected.value.baseGroupToken))
            }
        }
        pendingDrafts = drafts.sorted { $0.document.updatedAt > $1.document.updatedAt }
    }

    private func recoveryItem(_ url: URL, reason: String) -> NoteRecoveryItem {
        NoteRecoveryItem(id: url.standardizedFileURL.path, filename: url.lastPathComponent, reason: reason, sourceURL: url)
    }

    /// Recovery candidates may themselves be the normal `.tmp` or `.bak` path.
    /// Promote through unique sidecars so a failed write can never truncate or
    /// replace the only valid candidate selected above.
    private func promoteRecovery(_ data: Data, to target: URL, verify: (Data) throws -> Void) throws {
        let token = UUID().uuidString
        let temporary = target.deletingLastPathComponent()
            .appendingPathComponent(".\(target.lastPathComponent).recovery-\(token).tmp")
        let backup = target.deletingLastPathComponent()
            .appendingPathComponent(".\(target.lastPathComponent).recovery-\(token).bak")
        try NoteAtomicFile.write(data, to: target, faultInjector: faultInjector,
                                 temporaryURL: temporary, backupURL: backup, verify: verify)
    }

    private func noteCandidateURLs(_ target: URL) -> [URL] {
        [target, sidecarURL(target, suffix: ".tmp"), sidecarURL(target, suffix: ".bak")]
    }

    private func readLimited(_ url: URL, limit: Int) throws -> Data {
        if let values = try? url.resourceValues(forKeys: [.fileSizeKey]), let size = values.fileSize, size > limit {
            throw NoteDocumentError.tooLarge("File exceeds import limit")
        }
        let data = try Data(contentsOf: url, options: .mappedIfSafe)
        guard data.count <= limit else { throw NoteDocumentError.tooLarge("File exceeds import limit") }
        return data
    }

    private func noteURL(for note: NoteDocument) -> URL { directory.appendingPathComponent("\(note.id).json") }
    private func draftURL(noteID: String) -> URL { draftDirectory.appendingPathComponent("\(noteID).draft.json") }
    private func sidecarURL(_ target: URL, suffix: String) -> URL { URL(fileURLWithPath: target.path + suffix) }
    private func uniqueID() -> String { UUID().uuidString }
    private func safeID(_ value: String) -> Bool { value.range(of: #"^[A-Za-z0-9_.:-]+$"#, options: .regularExpression) != nil }
    private func safeTitle(_ value: String) -> String { value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? "未命名笔记" : String(value.prefix(200)) }
    private func safeFilename(_ value: String, fallback: String) -> String {
        let cleaned = value.replacingOccurrences(of: "[^A-Za-z0-9_ .-]", with: "_", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return String((cleaned.isEmpty ? fallback : cleaned).prefix(80))
    }
}
