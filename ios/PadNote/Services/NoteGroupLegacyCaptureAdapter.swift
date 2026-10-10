import Foundation
import CryptoKit
import Darwin

public enum NoteGroupLegacyCaptureError: Error, Equatable {
    case invalidStagingRoot
    case noteMissingOrAmbiguous
    case incompleteSnapshot
    case inconsistentAssociations
    case missingResource
}

/// Concrete, read-only adapter over the current iPad stores. It captures a full
/// LibraryBackupSnapshot and then narrows it through the snapshot's explicit note links.
/// This class is opt-in and intentionally is not connected to any app screen or writer.
@MainActor
final class NoteGroupLegacyCaptureAdapter {
    private struct Association: Codable {
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
    private struct AssociationIndex: Codable {
        let schemaVersion: Int
        let localNoteID: String
        let legacyNoteID: String
        let members: [Association]
    }

    private let library: NoteLibrary
    private let vault: VaultLibrary
    private let videoStore: NoteVideoAttachmentStore
    private let restoredVideoStore: RestoredVideoAttachmentStore
    private let coverStore: NoteCoverStore
    private let presetStore: UserCoverPresetStore
    private let facade: NoteGroupFacade
    private let stagingRoot: URL

    init(library: NoteLibrary, vault: VaultLibrary, videoStore: NoteVideoAttachmentStore,
                restoredVideoStore: RestoredVideoAttachmentStore, coverStore: NoteCoverStore,
                presetStore: UserCoverPresetStore, store: NoteGroupStore, stagingRoot: URL) {
        self.library = library; self.vault = vault; self.videoStore = videoStore
        self.restoredVideoStore = restoredVideoStore; self.coverStore = coverStore
        self.presetStore = presetStore; self.stagingRoot = stagingRoot.standardizedFileURL
        self.facade = NoteGroupFacade(store: store) { _ in throw NoteGroupFacadeError.legacyCaptureUnavailable }
    }

    /// Preview captures a complete read-only catalog snapshot; no legacy source is changed.
    func preview(noteID: String) async throws -> NoteGroupPreview {
        let captured = try await capture(noteID: noteID)
        defer { try? FileManager.default.removeItem(at: captured.directory) }
        return try facade.preview(snapshot: captured.legacy)
    }

    /// Confirmation performs a fresh catalog capture, compares it to preview, then synchronously
    /// copies the approved immutable staged members into the group's private revision.
    func adopt(_ preview: NoteGroupPreview) async throws -> NoteGroupVersionToken {
        try await NoteGroupCatalogFence.withCapture {
            let captured = try await captureUnderLease(noteID: preview.localNoteID)
            defer { try? FileManager.default.removeItem(at: captured.directory) }
            return try facade.adopt(preview, currentSnapshot: captured.legacy)
        }
    }

    /// Reads one verified current generation. Legacy fallback is deliberately unavailable here;
    /// callers without a current marker must obtain a new explicit preview through this adapter.
    func readCurrent(noteID: String, lineageID: String) throws -> NoteGroupReadResult {
        try facade.read(noteID: noteID, lineageID: lineageID)
    }

    private func capture(noteID: String) async throws -> (legacy: LegacyNoteGroupSnapshot, directory: URL) {
        try await NoteGroupCatalogFence.withCapture { try await captureUnderLease(noteID: noteID) }
    }

    private func captureUnderLease(noteID: String) async throws -> (legacy: LegacyNoteGroupSnapshot, directory: URL) {
        let canonicalID = try NoteGroupFacade.foundationUUID(noteID)
        try ensurePrivateStagingRoot()
        let directory = stagingRoot.appendingPathComponent(UUID().uuidString.lowercased(), isDirectory: true)
        let snapshot: LibraryBackupCapturedSnapshot
        do {
            snapshot = try await LibraryBackupSnapshot.captureUnderExistingLease(library: library, vault: vault,
                videoStore: videoStore, presetStore: presetStore, restoredVideoStore: restoredVideoStore,
                coverStore: coverStore, into: directory, includeLegacySourceURLs: true)
        } catch {
            try? FileManager.default.removeItem(at: directory)
            throw error
        }
        do {
            guard snapshot.unavailableVideoCount == 0 else { throw NoteGroupLegacyCaptureError.incompleteSnapshot }
            guard library.pendingDrafts.isEmpty, library.recoveryItems.isEmpty, vault.listingIssueCount == 0,
                  restoredVideoStore.lastListingIssueCount == 0 else {
                throw NoteGroupLegacyCaptureError.incompleteSnapshot
            }
            let matches = snapshot.manifest.notes.filter { Self.normalizeUUID($0.sourceNoteID) == canonicalID }
            guard matches.count == 1, let note = matches.first else { throw NoteGroupLegacyCaptureError.noteMissingOrAmbiguous }
            guard let legacyNote = library.notes.first(where: { Self.normalizeUUID($0.id) == canonicalID }),
                  Self.normalizeUUID(legacyNote.id) == canonicalID else { throw NoteGroupLegacyCaptureError.noteMissingOrAmbiguous }

            var sources = [LegacyNoteGroupSnapshot.Source]()
            var associations = [Association]()
            func append(_ id: String, _ role: NoteGroupMemberRole, _ url: URL,
                        _ kind: String, _ sourceID: String, _ sourceNoteID: String,
                        _ itemID: String? = nil, _ state: String? = nil,
                        _ metadata: URL? = nil) throws {
                let canonicalMember = try NoteGroupFacade.foundationUUID(id)
                let metadataWitness = try metadata.map { try LibraryBackupArchive.hashFile($0) }
                sources.append(.init(id: canonicalMember, role: role, url: url))
                associations.append(Association(memberID: canonicalMember, role: role.rawValue,
                    sourceKind: kind, sourceID: sourceID, sourceNoteID: sourceNoteID,
                    manifestItemID: itemID, sourceState: state,
                    metadataByteLength: metadataWitness.map { Int64($0.size) },
                    metadataSHA256: metadataWitness?.sha256))
            }
            func resource(_ id: String?) throws -> URL? {
                guard let id else { return nil }
                guard let url = snapshot.resourceFiles[id] else { throw NoteGroupLegacyCaptureError.missingResource }
                return url
            }

            guard let bodyURL = try resource(note.noteResourceID) else { throw NoteGroupLegacyCaptureError.missingResource }
            try append(canonicalID, .body, bodyURL, "note_library_document", legacyNote.id, legacyNote.id)
            if let pdfURL = try resource(note.pdfResourceID) {
                try append(Self.stableMemberID(note: canonicalID, key: "pdf"), .pdf, pdfURL,
                           "note_library_pdf", legacyNote.id, legacyNote.id)
            }
            if let coverURL = try resource(note.coverResourceID) {
                try append(Self.stableMemberID(note: canonicalID, key: "cover"), .cover, coverURL,
                           "note_cover_store", legacyNote.id, legacyNote.id)
            }

            let linkedVideos = snapshot.manifest.videoAttachments.filter { $0.noteItemID == note.itemID }
            guard Set(linkedVideos.map { $0.itemID }) == Set(note.videoAttachmentIDs),
                  linkedVideos.count == note.videoAttachmentIDs.count else { throw NoteGroupLegacyCaptureError.inconsistentAssociations }
            for video in linkedVideos.sorted(by: { $0.itemID < $1.itemID }) {
                guard let identity = snapshot.videoSourceIdentities[video.itemID],
                      let url = try resource(video.resourceID),
                      let metadataURL = identity.metadataURL,
                      Self.normalizeUUID(identity.sourceAttachmentID) != nil else { throw NoteGroupLegacyCaptureError.missingResource }
                let memberID = try NoteGroupFacade.foundationUUID(identity.sourceAttachmentID)
                let metadataMemberID = Self.stableMemberID(note: canonicalID, key: "video-metadata:\(memberID)")
                let metadataCopy = try copyStableSource(metadataURL, to: directory,
                    fileName: "video-metadata-\(memberID).json", maximumBytes: 1_048_576)
                try validateVideoMetadata(metadataCopy.bytes, identity: identity, descriptor: video)
                try append(memberID, .video, url, identity.sourceKind, identity.sourceAttachmentID,
                           identity.sourceNoteID, nil, video.sourceState, metadataCopy.url)
                try append(metadataMemberID, .videoMetadata, metadataCopy.url, "video_attachment_exact_metadata",
                           identity.sourceAttachmentID, identity.sourceNoteID, nil, video.sourceState, metadataCopy.url)
            }

            let linkedVault = snapshot.manifest.vaultEntries.filter { $0.noteItemID == note.itemID }
            guard Set(linkedVault.map { $0.itemID }) == Set(note.vaultEntryIDs),
                  linkedVault.count == note.vaultEntryIDs.count else { throw NoteGroupLegacyCaptureError.inconsistentAssociations }
            var vaultSourceIDs = Set<String>()
            for entry in linkedVault.sorted(by: { $0.itemID < $1.itemID }) {
                guard let url = snapshot.legacyVaultSourceFiles[entry.itemID] else { throw NoteGroupLegacyCaptureError.missingResource }
                let metadataCopy = try copyStableSource(url, to: directory,
                    fileName: "vault-metadata-\(UUID().uuidString.lowercased()).json", maximumBytes: LibraryBackupArchive.maxVaultBytes + 128 * 1024)
                let raw = metadataCopy.bytes
                let source = try JSONDecoder().decode(VaultNote.self, from: raw)
                guard let portableURL = snapshot.resourceFiles[entry.resourceID] else { throw NoteGroupLegacyCaptureError.missingResource }
                let portable = try JSONDecoder().decode(LibraryBackupVaultPayload.self,
                    from: LibraryBackupArchive.readSmallFile(portableURL, maximumBytes: LibraryBackupArchive.maxVaultBytes))
                guard source.title.utf8.count <= 512, source.markdown.utf8.count <= LibraryBackupArchive.maxVaultBytes else {
                    throw NoteGroupLegacyCaptureError.incompleteSnapshot
                }
                guard portable.title == source.title, portable.markdown == source.markdown,
                      portable.sourceNoteID == entry.sourceNoteID,
                      portable.sourceRevisionMS == entry.sourceRevisionMS,
                      portable.createdAtMS == entry.createdAtMS else { throw NoteGroupLegacyCaptureError.incompleteSnapshot }
                let sourceIDKey = Self.normalizeUUID(source.id) ?? source.id
                guard vaultSourceIDs.insert(sourceIDKey).inserted else { throw NoteGroupLegacyCaptureError.inconsistentAssociations }
                let bodyID = Self.stableMemberID(note: canonicalID, key: "vault-body:\(sourceIDKey)")
                let recordID = Self.stableMemberID(note: canonicalID, key: "vault-record:\(sourceIDKey)")
                let markdownURL = directory.appendingPathComponent("vault-body-\(entry.itemID).md")
                try Data(source.markdown.utf8).write(to: markdownURL, options: [.atomic, .completeFileProtectionUnlessOpen])
                try append(bodyID, .vault, markdownURL, "vault_library_markdown", source.id,
                           entry.sourceNoteID, nil, entry.sourceState)
                try append(recordID, .vaultMetadata, metadataCopy.url, "vault_library_exact_json", source.id,
                           entry.sourceNoteID, nil, entry.sourceState, metadataCopy.url)
            }

            guard associations.map(\.memberID).count == Set(associations.map(\.memberID)).count else {
                throw NoteGroupLegacyCaptureError.inconsistentAssociations
            }
            let indexID = Self.stableMemberID(note: canonicalID, key: "association-index")
            let index = AssociationIndex(schemaVersion: 1, localNoteID: canonicalID,
                                         legacyNoteID: legacyNote.id,
                                         members: associations.sorted { $0.memberID < $1.memberID })
            let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
            let indexURL = directory.appendingPathComponent("legacy-group-associations.json")
            try encoder.encode(index).write(to: indexURL, options: [.atomic, .completeFileProtectionUnlessOpen])
            sources.append(.init(id: indexID, role: .association, url: indexURL))

            let legacy = LegacyNoteGroupSnapshot(localNoteID: legacyNote.id,
                lineageID: canonicalID, sources: sources)
            return (legacy, directory)
        } catch {
            try? FileManager.default.removeItem(at: snapshot.directory)
            throw error
        }
    }

    private func ensurePrivateStagingRoot() throws {
        var info = stat()
        if lstat(stagingRoot.path, &info) == 0 {
            guard info.st_mode & mode_t(S_IFMT) == mode_t(S_IFDIR), info.st_uid == getuid(),
                  info.st_mode & mode_t(0o077) == 0 else { throw NoteGroupLegacyCaptureError.invalidStagingRoot }
        } else {
            guard errno == ENOENT else { throw NoteGroupLegacyCaptureError.invalidStagingRoot }
            try FileManager.default.createDirectory(at: stagingRoot, withIntermediateDirectories: true,
                attributes: [.posixPermissions: 0o700, .protectionKey: FileProtectionType.completeUnlessOpen])
            guard lstat(stagingRoot.path, &info) == 0, info.st_mode & mode_t(S_IFMT) == mode_t(S_IFDIR),
                  info.st_uid == getuid(), info.st_mode & mode_t(0o077) == 0 else {
                throw NoteGroupLegacyCaptureError.invalidStagingRoot
            }
        }
    }

    private static func normalizeUUID(_ value: String) -> String? {
        UUID(uuidString: value)?.uuidString.lowercased()
    }

    private func copyStableSource(_ source: URL, to directory: URL, fileName: String,
                                  maximumBytes: Int64) throws -> (url: URL, bytes: Data, size: Int64, sha256: String) {
        try LibraryBackupArchive.validateRegularSource(source, maximumBytes: maximumBytes)
        let before = try LibraryBackupArchive.hashFile(source)
        guard before.size <= maximumBytes else { throw NoteGroupLegacyCaptureError.incompleteSnapshot }
        let data = try Data(contentsOf: source, options: [.mappedIfSafe])
        let after = try LibraryBackupArchive.hashFile(source)
        let readHash = Self.sha256(data)
        guard before.size == after.size, before.sha256 == after.sha256,
              Int64(data.count) == before.size, readHash == before.sha256 else {
            throw LibraryBackupError.sourceChanged
        }
        let staged = directory.appendingPathComponent(fileName)
        try data.write(to: staged, options: [.atomic, .completeFileProtectionUnlessOpen])
        let copied = try LibraryBackupArchive.hashFile(staged)
        guard copied.size == before.size, copied.sha256 == before.sha256 else { throw LibraryBackupError.sourceChanged }
        return (staged, data, copied.size, copied.sha256)
    }

    private func validateVideoMetadata(_ data: Data, identity: LibraryBackupVideoSourceIdentity,
                                       descriptor: LibraryBackupManifest.Video) throws {
        switch identity.sourceKind {
        case "note_video_attachment":
            let value = try JSONDecoder().decode(NoteVideoAttachment.self, from: data)
            guard Self.normalizeUUID(value.id.uuidString) == Self.normalizeUUID(identity.sourceAttachmentID),
                  Self.normalizeUUID(value.noteID) == Self.normalizeUUID(identity.sourceNoteID),
                  Int64(value.sizeBytes) == descriptor.byteLength, value.sha256 == descriptor.sha256,
                  value.taskID.uuidString.lowercased() == descriptor.taskID,
                  value.remoteTaskID == descriptor.remoteTaskID, value.artifactID == descriptor.artifactID,
                  value.displayName == descriptor.displayName, value.connectionID.uuidString.lowercased() == descriptor.connection.connectionID,
                  value.connectionRevision == descriptor.connection.revision,
                  value.connectionKind.rawValue == descriptor.connection.kind,
                  value.bridgeID == descriptor.connection.bridgeID,
                  value.instanceID == descriptor.connection.instanceID,
                  value.certSHA256 == descriptor.connection.certificateSHA256,
                  Int64(value.sourceRevision) == descriptor.sourceRevisionMS / 1000,
                  value.sourceSnapshotSHA256 == descriptor.sourceBundleSHA256 else { throw LibraryBackupError.sourceChanged }
        case "restored_video_attachment":
            let value = try JSONDecoder().decode(RestoredVideoAttachment.self, from: data)
            guard Self.normalizeUUID(value.id.uuidString) == Self.normalizeUUID(identity.sourceAttachmentID),
                  Self.normalizeUUID(value.sourceNoteID) == Self.normalizeUUID(identity.sourceNoteID),
                  value.originKind == descriptor.originKind, value.byteLength == descriptor.byteLength,
                  value.sha256 == descriptor.sha256, value.displayName == descriptor.displayName,
                  value.taskID == descriptor.taskID, value.remoteTaskID == descriptor.remoteTaskID,
                  value.sourceRevisionMS == descriptor.sourceRevisionMS,
                  value.sourceBundleSHA256 == descriptor.sourceBundleSHA256 else { throw LibraryBackupError.sourceChanged }
        default:
            throw NoteGroupLegacyCaptureError.incompleteSnapshot
        }
    }

    private static func sha256(_ data: Data) -> String {
        SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }

    private static func stableMemberID(note: String, key: String) -> String {
        var bytes = Array(SHA256.hash(data: Data("padnote-group-member-v1|\(note)|\(key)".utf8)).prefix(16))
        bytes[6] = (bytes[6] & 0x0f) | 0x50
        bytes[8] = (bytes[8] & 0x3f) | 0x80
        let value = UUID(uuid: (bytes[0], bytes[1], bytes[2], bytes[3], bytes[4], bytes[5], bytes[6], bytes[7],
                                bytes[8], bytes[9], bytes[10], bytes[11], bytes[12], bytes[13], bytes[14], bytes[15]))
        return value.uuidString.lowercased()
    }
}
