import Foundation
import PDFKit
import Combine
import CryptoKit
import CoreFoundation

public struct LibraryBackupPreview: Identifiable {
    public let id: UUID
    public let archiveURL: URL
    public let staged: LibraryBackupArchive.StagedArchive
    public let notes: [NoteDocument]
    public let validatedNotesByItemID: [String: NoteDocument]
    public let noteCount: Int
    public let pdfCount: Int
    public let coverCount: Int
    public let vaultCount: Int
    public let videoCount: Int
    public let presetCount: Int
    public let sourceConflicts: Int
    public let verifiedBytes: Int64
}

enum LibraryBackupRestoreInterleavingTestPoint: Equatable {
    case resourcesPromotedBeforeJournalWrite
}

public struct LibraryBackupRecoveryOption: Identifiable, Equatable {
    public let transactionID: String
    public let completedGroupCount: Int
    public let pendingGroupCount: Int
    public var id: String { transactionID }
}

struct RestoreJournal: Codable {
    var transactionID: String
    var archiveSHA256: String
    var status: String
    var stageID: String
    var noteIDs: [String: String]
    var noteSHA256ByItemID: [String: String]
    var vaultIDs: [String: String]
    var videoIDs: [String: String]
    var presetIDs: [String: String]
    var vaultGroupIDsByItemID: [String: String] = [:]
    var videoGroupIDsByItemID: [String: String] = [:]
    var presetGroupIDsByItemID: [String: String] = [:]
    var resourceSHA256: [String: String]
    var independentGroupIDs: [String]
    var completedGroups: [String]
    var groupPhases: [String: String]
    var updatedAt: Date
}

/// Validates an archive before touching the library, then restores each note group as a copy.
/// Every visible imported record carries a transaction ID and remains hidden until the journal commits.
@MainActor
public final class LibraryBackupRestoreCoordinator: ObservableObject {
    @Published public private(set) var isWorking = false
    #if DEBUG
    var restoreInterleavingTestHook: (@MainActor (LibraryBackupRestoreInterleavingTestPoint) async -> Void)?
    #endif
    private let library: NoteLibrary
    private let vault: VaultLibrary
    private let videoStore: RestoredVideoAttachmentStore
    private let presetStore: UserCoverPresetStore
    private let coverStore: NoteCoverStore
    private let journalRoot: URL

    init(library: NoteLibrary, vault: VaultLibrary, videoStore: RestoredVideoAttachmentStore = RestoredVideoAttachmentStore(),
                presetStore: UserCoverPresetStore = UserCoverPresetStore(), coverStore: NoteCoverStore = NoteCoverStore(),
                journalRoot: URL? = nil) {
        self.library = library; self.vault = vault; self.videoStore = videoStore
        self.presetStore = presetStore; self.coverStore = coverStore
        self.journalRoot = journalRoot ?? library.backupTransactionDirectory
        vault.bind(noteLibrary: library)
    }

    public func inspect(_ archive: URL, stagingRoot: URL, cancellation: LibraryBackupCancellationToken? = nil) async throws -> LibraryBackupPreview {
        guard !isWorking else { throw LibraryBackupError.transaction("已有归档操作正在进行") }
        isWorking = true; defer { isWorking = false }
        let scoped = archive.startAccessingSecurityScopedResource(); defer { if scoped { archive.stopAccessingSecurityScopedResource() } }
        try FileManager.default.createDirectory(at: stagingRoot, withIntermediateDirectories: true)
        try Self.requirePlainDirectory(stagingRoot)
        let stage = stagingRoot.appendingPathComponent(UUID().uuidString.lowercased(), isDirectory: true)
        let existingNotes = library.notes
        let result: LibraryBackupPreview
        do {
            result = try await Task.detached(priority: .userInitiated) { try Self.validateArchive(archive, into: stage, existingNotes: existingNotes, cancellation: cancellation) }.value
        } catch {
            try? Self.removeOwnedStage(stage, within: stagingRoot)
            throw error
        }
        do {
            try await Task.detached(priority: .userInitiated) { try LibraryBackupArchive.writeStagedManifest(result.staged.manifest, to: stage) }.value
            return result
        } catch { try? FileManager.default.removeItem(at: stage); throw error }
    }

    public func recoveryOptions(for preview: LibraryBackupPreview) throws -> [LibraryBackupRecoveryOption] {
        try Self.recoveryOptions(archiveSHA256: preview.staged.archiveSHA256, in: journalRoot)
    }

    public func cleanupStagedPreviewIfUnreferenced(_ preview: LibraryBackupPreview) throws {
        try Self.removeStageIfUnreferenced(preview.staged.directory, journalRoot: journalRoot)
    }

    public func rollbackIncompleteRestore(_ transactionID: String, for preview: LibraryBackupPreview, cancellation: LibraryBackupCancellationToken? = nil) async throws {
        try await NoteGroupCatalogFence.withWriter {
            try await self.rollbackIncompleteRestoreUnderCatalogWriter(transactionID, for: preview, cancellation: cancellation)
        }
    }

    private func rollbackIncompleteRestoreUnderCatalogWriter(_ transactionID: String, for preview: LibraryBackupPreview,
                                                               cancellation: LibraryBackupCancellationToken?) async throws {
        guard !isWorking else { throw LibraryBackupError.transaction("已有归档操作正在进行") }
        isWorking = true; defer { isWorking = false }
        guard let journal = try LibraryBackupTransactionGate.readJournal(transactionID, in: journalRoot),
              journal.archiveSHA256 == preview.staged.archiveSHA256,
              ["staged", "incomplete", "rolled_back"].contains(journal.status) else {
            throw LibraryBackupError.transaction("找不到匹配的未完成本地恢复")
        }
        try Self.validateJournalBinding(journal, manifest: preview.staged.manifest)
        var mutable = journal
        for item in preview.staged.manifest.notes where mutable.noteIDs[item.itemID] != nil && !Self.isGroupCompleted(item.itemID, in: mutable) {
            try cancellation?.check()
            try rollbackUncommittedNoteGroup(item, manifest: preview.staged.manifest, journal: mutable)
            mutable.groupPhases[item.itemID] = "rolled_back"
        }
        for item in preview.staged.manifest.vaultEntries where item.noteItemID == nil && mutable.vaultIDs[item.itemID] != nil && !Self.isGroupCompleted(item.itemID, in: mutable) {
            try cancellation?.check()
            guard let id = mutable.vaultIDs[item.itemID] else { throw LibraryBackupError.transaction("恢复ID缺失") }
            try vault.rollbackPartialArchiveEntry(id: id, transactionID: transactionID, groupID: item.itemID)
            mutable.groupPhases[item.itemID] = "rolled_back"
        }
        for item in preview.staged.manifest.videoAttachments where item.noteItemID == nil && mutable.videoIDs[item.itemID] != nil && !Self.isGroupCompleted(item.itemID, in: mutable) {
            try cancellation?.check()
            guard let id = mutable.videoIDs[item.itemID], let uuid = UUID(uuidString: id) else { throw LibraryBackupError.transaction("恢复ID无效") }
            try videoStore.rollbackPartial(id: uuid, transactionID: transactionID, groupID: item.itemID, expectedSHA256: item.sha256)
            mutable.groupPhases[item.itemID] = "rolled_back"
        }
        for item in preview.staged.manifest.coverPresets where mutable.presetIDs[item.itemID] != nil && !Self.isGroupCompleted(item.itemID, in: mutable) {
            try cancellation?.check()
            guard let id = mutable.presetIDs[item.itemID], let uuid = UUID(uuidString: id),
                  let resource = preview.staged.manifest.resources.first(where: { $0.resourceID == item.resourceID }) else {
                throw LibraryBackupError.transaction("封面预设恢复ID无效")
            }
            try presetStore.rollbackPartial(id: uuid, transactionID: transactionID, groupID: item.itemID, expectedSHA256: resource.sha256)
            mutable.groupPhases[item.itemID] = "rolled_back"
        }
        // Rollback is terminal for the remaining groups; committed records stay visible through their group markers.
        mutable.status = "rolled_back"
        mutable.updatedAt = Date()
        try Self.writeJournal(mutable, to: journalRoot.appendingPathComponent(transactionID + ".json"))
        library.reload(); vault.reload()
    }

    public func restore(_ preview: LibraryBackupPreview, selectedNoteItemIDs: Set<String>? = nil,
                        continuingTransactionID: String? = nil, cancellation: LibraryBackupCancellationToken? = nil) async throws {
        try await NoteGroupCatalogFence.withWriter {
            try await self.restoreUnderCatalogWriter(preview, selectedNoteItemIDs: selectedNoteItemIDs,
                continuingTransactionID: continuingTransactionID, cancellation: cancellation)
        }
    }

    private func restoreUnderCatalogWriter(_ preview: LibraryBackupPreview, selectedNoteItemIDs: Set<String>?,
                                           continuingTransactionID: String?,
                                           cancellation: LibraryBackupCancellationToken?) async throws {
        guard !isWorking else { throw LibraryBackupError.transaction("已有归档操作正在进行") }
        isWorking = true; defer { isWorking = false }
        try FileManager.default.createDirectory(at: journalRoot, withIntermediateDirectories: true)
        try Self.requirePlainDirectory(journalRoot)
        let manifest = preview.staged.manifest
        let prior: RestoreJournal?
        if let continuingTransactionID {
            prior = try LibraryBackupTransactionGate.readJournal(continuingTransactionID, in: journalRoot)
        } else {
            prior = nil
        }
        if let continuingTransactionID {
            guard let prior, prior.transactionID == continuingTransactionID,
                  prior.archiveSHA256 == preview.staged.archiveSHA256,
                  ["staged", "incomplete"].contains(prior.status) else {
                throw LibraryBackupError.transaction("未完成恢复与此归档不匹配")
            }
            try Self.validateJournalBinding(prior, manifest: preview.staged.manifest)
        }
        let transactionID = prior?.transactionID ?? UUID().uuidString.lowercased()
        let selected = selectedNoteItemIDs ?? prior.map { Set($0.noteIDs.keys) } ?? Set(manifest.notes.map(\.itemID))
        guard selected.isSubset(of: Set(manifest.notes.map(\.itemID))), prior == nil || selected == Set(prior!.noteIDs.keys) else {
            throw LibraryBackupError.invalidManifest("恢复选择已变化")
        }
        let selectedNotes = manifest.notes.filter { selected.contains($0.itemID) }
        let relevantVault = manifest.vaultEntries.filter { $0.noteItemID == nil || selected.contains($0.noteItemID!) }
        let relevantVideos = manifest.videoAttachments.filter { $0.noteItemID == nil || selected.contains($0.noteItemID!) }
        let mapping = prior?.noteIDs ?? Dictionary(uniqueKeysWithValues: selectedNotes.map { ($0.itemID, UUID().uuidString.lowercased()) })
        let noteDigests = Dictionary(uniqueKeysWithValues: preview.validatedNotesByItemID.compactMap { itemID, source -> (String, String)? in
            guard let newID = mapping[itemID] else { return nil }
            var copy = source; copy.id = newID
            guard let bytes = try? copy.validated().encoded() else { return nil }
            let sha = SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined()
            return (itemID, sha)
        })
        guard noteDigests.count == selectedNotes.count,
              prior == nil || prior!.noteSHA256ByItemID == noteDigests else { throw LibraryBackupError.sourceChanged }
        let vaultIDs = prior?.vaultIDs ?? Dictionary(uniqueKeysWithValues: relevantVault.map { ($0.itemID, UUID().uuidString.lowercased()) })
        let videoIDs = prior?.videoIDs ?? Dictionary(uniqueKeysWithValues: relevantVideos.map { ($0.itemID, UUID().uuidString.lowercased()) })
        let presetIDs = prior?.presetIDs ?? Dictionary(uniqueKeysWithValues: manifest.coverPresets.map { ($0.itemID, UUID().uuidString.lowercased()) })
        let vaultGroupIDs = Dictionary(uniqueKeysWithValues: relevantVault.map { ($0.itemID, $0.noteItemID ?? $0.itemID) })
        let videoGroupIDs = Dictionary(uniqueKeysWithValues: relevantVideos.map { ($0.itemID, $0.noteItemID ?? $0.itemID) })
        let presetGroupIDs = Dictionary(uniqueKeysWithValues: manifest.coverPresets.map { ($0.itemID, $0.itemID) })
        let resourceSHA = Dictionary(uniqueKeysWithValues: manifest.resources.map { ($0.resourceID, $0.sha256) })
        let independentGroupIDs = relevantVault.filter { $0.noteItemID == nil }.map(\.itemID) +
            relevantVideos.filter { $0.noteItemID == nil }.map(\.itemID) + manifest.coverPresets.map(\.itemID)
        if let prior {
            guard Set(prior.vaultIDs.keys) == Set(relevantVault.map(\.itemID)),
                  Set(prior.videoIDs.keys) == Set(relevantVideos.map(\.itemID)),
                  Set(prior.presetIDs.keys) == Set(manifest.coverPresets.map(\.itemID)),
                  prior.vaultGroupIDsByItemID == vaultGroupIDs, prior.videoGroupIDsByItemID == videoGroupIDs,
                  prior.presetGroupIDsByItemID == presetGroupIDs,
                  prior.resourceSHA256 == resourceSHA,
                  Set(prior.independentGroupIDs) == Set(independentGroupIDs),
                  Set(prior.completedGroups).isSubset(of: Set(selectedNotes.map(\.itemID) + relevantVault.filter { $0.noteItemID == nil }.map(\.itemID) + relevantVideos.filter { $0.noteItemID == nil }.map(\.itemID) + manifest.coverPresets.map(\.itemID))) else {
                throw LibraryBackupError.transaction("本地恢复日志与归档资源绑定不匹配")
            }
        }
        var journal = prior ?? RestoreJournal(transactionID: transactionID, archiveSHA256: preview.staged.archiveSHA256,
            status: "staged", stageID: preview.staged.directory.lastPathComponent, noteIDs: mapping,
            noteSHA256ByItemID: noteDigests, vaultIDs: vaultIDs, videoIDs: videoIDs, presetIDs: presetIDs,
            vaultGroupIDsByItemID: vaultGroupIDs, videoGroupIDsByItemID: videoGroupIDs, presetGroupIDsByItemID: presetGroupIDs,
            resourceSHA256: resourceSHA, independentGroupIDs: independentGroupIDs.sorted(), completedGroups: [], groupPhases: [:], updatedAt: Date())
        journal.stageID = preview.staged.directory.lastPathComponent
        journal.status = "staged"
        journal.updatedAt = Date()
        let journalURL = journalRoot.appendingPathComponent(transactionID + ".json")
        try Self.writeJournal(journal, to: journalURL)
        do {
            for item in selectedNotes where !Self.isGroupCompleted(item.itemID, in: journal) {
                try cancellation?.check()
                try rollbackUncommittedNoteGroup(item, manifest: manifest, journal: journal)
                guard let source = preview.validatedNotesByItemID[item.itemID], let mapped = mapping[item.itemID] else {
                    throw LibraryBackupError.sourceChanged
                }
                let newID = mapped
                var copy = source; copy.id = newID
                let vaultItems = manifest.vaultEntries.filter { $0.noteItemID == item.itemID }
                let videoItems = manifest.videoAttachments.filter { $0.noteItemID == item.itemID }
                var addedVault = [VaultNote](), addedVideos = [RestoredVideoAttachment]()
                var coverAdded = false
                do {
                    journal.groupPhases[item.itemID] = "staged"; journal.updatedAt = Date(); try Self.writeJournal(journal, to: journalURL)
                    try await Self.verifyGroupResources([item.noteResourceID, item.pdfResourceID, item.coverResourceID].compactMap { $0 } + vaultItems.map(\.resourceID) + vaultItems.compactMap(\.sourceStorageResourceID) + videoItems.map(\.resourceID), manifest: manifest, directory: preview.staged.directory, cancellation: cancellation)
                    if let coverID = item.coverResourceID, let resource = manifest.resources.first(where: { $0.resourceID == coverID }) {
                        let target = preview.staged.directory.appendingPathComponent(resource.resourceID + ".bin")
                        let store = coverStore
                        guard let descriptor = manifest.resources.first(where: { $0.resourceID == coverID }) else { throw LibraryBackupError.invalidManifest("封面资源缺失") }
                        try await Task.detached(priority: .userInitiated) {
                            try store.restorePNG(noteID: newID, from: target, expectedSize: descriptor.byteLength, expectedSHA256: descriptor.sha256, cancellation: cancellation)
                        }.value
                        coverAdded = true
                    }
                    for entry in vaultItems {
                        let url = preview.staged.directory.appendingPathComponent(entry.resourceID + ".bin")
                        guard let descriptor = manifest.resources.first(where: { $0.resourceID == entry.resourceID }) else {
                            throw LibraryBackupError.invalidManifest("知识库资源描述缺失")
                        }
                        let payload = try await Task.detached(priority: .userInitiated) {
                            try Self.readVerifiedVaultPayload(at: url, descriptor: descriptor, entry: entry)
                        }.value
                        guard let restoredID = journal.vaultIDs[entry.itemID] else { throw LibraryBackupError.transaction("知识库恢复ID缺失") }
                        let added = try vault.restoreArchiveEntry(id: restoredID, title: payload.title, markdown: payload.markdown,
                            sourceRevisionMS: payload.sourceRevisionMS, createdAtMS: payload.createdAtMS, sourceNoteID: payload.sourceNoteID,
                            sourceState: entry.sourceState, linkedNoteID: newID, transactionID: transactionID, groupID: item.itemID,
                            digitizationMetadata: try LibraryBackupArchive.readVaultDigitizationMetadata(for: entry, manifest: manifest, directory: preview.staged.directory))
                        addedVault.append(added)
                    }
                    for video in videoItems {
                        let url = preview.staged.directory.appendingPathComponent(video.resourceID + ".bin")
                        let store = videoStore
                        guard let restoredID = journal.videoIDs[video.itemID] else { throw LibraryBackupError.transaction("视频恢复ID缺失") }
                        let added = try await Task.detached(priority: .userInitiated) {
                            try store.restore(from: url, descriptor: video, newNoteID: newID, transactionID: transactionID,
                                              attachmentID: UUID(uuidString: restoredID), groupID: item.itemID, cancellation: cancellation)
                        }.value
                        addedVideos.append(added)
                    }
                    #if DEBUG
                    if let hook = restoreInterleavingTestHook {
                        await hook(.resourcesPromotedBeforeJournalWrite)
                    }
                    #endif
                    journal.groupPhases[item.itemID] = "resources_promoted"; journal.updatedAt = Date(); try Self.writeJournal(journal, to: journalURL)
                    let pdf = item.pdfResourceID.flatMap { id in manifest.resources.first(where: { $0.resourceID == id }) }
                        .map { preview.staged.directory.appendingPathComponent($0.resourceID + ".bin") }
                    let pdfDescriptor = item.pdfResourceID.flatMap { id in manifest.resources.first(where: { $0.resourceID == id }) }
                    try await library.restoreBackupNote(copy, stagedPDF: pdf, expectedPDF: pdfDescriptor,
                        transactionID: transactionID, groupID: item.itemID,
                        expectedNoteSHA256: journal.noteSHA256ByItemID[item.itemID], journalRoot: journalRoot, cancellation: cancellation)
                    journal.groupPhases[item.itemID] = "note_promoted"; journal.updatedAt = Date(); try Self.writeJournal(journal, to: journalURL)
                    journal.groupPhases[item.itemID] = "index_committed"; journal.updatedAt = Date(); try Self.writeJournal(journal, to: journalURL)
                    try await library.verifyBackupNote(copy)
                    journal.groupPhases[item.itemID] = "committed"
                    if !journal.completedGroups.contains(item.itemID) { journal.completedGroups.append(item.itemID) }
                    journal.updatedAt = Date(); try Self.writeJournal(journal, to: journalURL)
                    try library.completeBackupNoteRestore(id: newID, transactionID: transactionID, groupID: item.itemID,
                        expectedNoteSHA256: journal.noteSHA256ByItemID[item.itemID], journalRoot: journalRoot)
                } catch let operationError {
                    if !Self.isGroupCompleted(item.itemID, in: journal) {
                        try? library.rollbackBackupNote(id: newID, transactionID: transactionID, groupID: item.itemID, journalRoot: journalRoot)
                        do { try rollbackUncommittedNoteGroup(item, manifest: manifest, journal: journal) }
                        catch {
                            journal.groupPhases[item.itemID] = "cleanup_failed"; journal.updatedAt = Date()
                            try? Self.writeJournal(journal, to: journalURL)
                            // Keep the phase that failed visible to the caller. Stable journal IDs and
                            // ownership witnesses leave any residue eligible for explicit recovery.
                            throw operationError
                        }
                    }
                    throw operationError
                }
            }
            for item in relevantVault where item.noteItemID == nil && !Self.isGroupCompleted(item.itemID, in: journal) {
                try cancellation?.check()
                guard let id = journal.vaultIDs[item.itemID] else { throw LibraryBackupError.transaction("知识库恢复ID缺失") }
                try vault.rollbackPartialArchiveEntry(id: id, transactionID: transactionID, groupID: item.itemID)
                try await Self.verifyGroupResources([item.resourceID] + [item.sourceStorageResourceID].compactMap { $0 }, manifest: manifest, directory: preview.staged.directory, cancellation: cancellation)
                guard let descriptor = manifest.resources.first(where: { $0.resourceID == item.resourceID }) else {
                    throw LibraryBackupError.invalidManifest("知识库资源描述缺失")
                }
                let url = preview.staged.directory.appendingPathComponent(item.resourceID + ".bin")
                let payload = try await Task.detached(priority: .userInitiated) {
                    try Self.readVerifiedVaultPayload(at: url, descriptor: descriptor, entry: item)
                }.value
                guard let restoredID = journal.vaultIDs[item.itemID] else { throw LibraryBackupError.transaction("知识库恢复ID缺失") }
                journal.groupPhases[item.itemID] = "resources_promoted"; journal.updatedAt = Date(); try Self.writeJournal(journal, to: journalURL)
                _ = try vault.restoreArchiveEntry(id: restoredID, title: payload.title, markdown: payload.markdown,
                    sourceRevisionMS: payload.sourceRevisionMS, createdAtMS: payload.createdAtMS, sourceNoteID: payload.sourceNoteID,
                    sourceState: item.sourceState, linkedNoteID: nil, transactionID: transactionID, groupID: item.itemID,
                    digitizationMetadata: try LibraryBackupArchive.readVaultDigitizationMetadata(for: item, manifest: manifest, directory: preview.staged.directory))
                journal.groupPhases[item.itemID] = "committed"; journal.completedGroups.append(item.itemID)
                journal.updatedAt = Date(); try Self.writeJournal(journal, to: journalURL)
            }
            for item in relevantVideos where item.noteItemID == nil && !Self.isGroupCompleted(item.itemID, in: journal) {
                try cancellation?.check()
                guard let id = journal.videoIDs[item.itemID], let fixedID = UUID(uuidString: id) else { throw LibraryBackupError.transaction("视频恢复ID无效") }
                try videoStore.rollbackPartial(id: fixedID, transactionID: transactionID, groupID: item.itemID, expectedSHA256: item.sha256)
                try await Self.verifyGroupResources([item.resourceID], manifest: manifest, directory: preview.staged.directory, cancellation: cancellation)
                let source = preview.staged.directory.appendingPathComponent(item.resourceID + ".bin")
                let store = videoStore
                guard let restoredID = journal.videoIDs[item.itemID] else { throw LibraryBackupError.transaction("视频恢复ID无效") }
                journal.groupPhases[item.itemID] = "resources_promoted"; journal.updatedAt = Date(); try Self.writeJournal(journal, to: journalURL)
                _ = try await Task.detached(priority: .userInitiated) {
                    try store.restore(from: source, descriptor: item, newNoteID: nil, transactionID: transactionID,
                                      attachmentID: fixedID, groupID: item.itemID, cancellation: cancellation)
                }.value
                journal.groupPhases[item.itemID] = "committed"; journal.completedGroups.append(item.itemID)
                journal.updatedAt = Date(); try Self.writeJournal(journal, to: journalURL)
            }
            for item in manifest.coverPresets where !Self.isGroupCompleted(item.itemID, in: journal) {
                try cancellation?.check()
                guard let restoredID = journal.presetIDs[item.itemID], let fixedID = UUID(uuidString: restoredID) else { throw LibraryBackupError.transaction("封面预设恢复ID无效") }
                guard let resource = manifest.resources.first(where: { $0.resourceID == item.resourceID }) else { throw LibraryBackupError.invalidManifest("封面预设资源缺失") }
                try await Self.verifyGroupResources([item.resourceID], manifest: manifest, directory: preview.staged.directory, cancellation: cancellation)
                let source = preview.staged.directory.appendingPathComponent(resource.resourceID + ".bin")
                let store = presetStore
                try cancellation?.check()
                guard let restoredID = journal.presetIDs[item.itemID], let fixedID = UUID(uuidString: restoredID) else { throw LibraryBackupError.transaction("封面恢复ID无效") }
                journal.groupPhases[item.itemID] = "resources_promoted"; journal.updatedAt = Date(); try Self.writeJournal(journal, to: journalURL)
                _ = try await Task.detached(priority: .userInitiated) {
                    try store.restore(name: item.displayName, stagedPNG: source, transactionID: transactionID,
                                      presetID: fixedID, groupID: item.itemID,
                                      expectedSize: resource.byteLength, expectedSHA256: resource.sha256, cancellation: cancellation)
                }.value
                journal.groupPhases[item.itemID] = "committed"; journal.completedGroups.append(item.itemID)
                journal.updatedAt = Date(); try Self.writeJournal(journal, to: journalURL)
            }
            journal.status = "committed"; journal.updatedAt = Date(); try Self.writeJournal(journal, to: journalURL)
            library.reload(); vault.reload()
            try? Self.removeStageIfUnreferenced(preview.staged.directory, journalRoot: journalRoot)
        } catch {
            // Completed groups are durable and remain visible; only the currently failing group is rolled back above.
            // The fixed IDs and phase map make any remaining group recoverable without creating duplicate records.
            journal.status = "incomplete"; journal.updatedAt = Date(); try? Self.writeJournal(journal, to: journalURL)
            throw error
        }
    }

    private func rollbackUncommittedNoteGroup(_ item: LibraryBackupManifest.Note, manifest: LibraryBackupManifest, journal: RestoreJournal) throws {
        guard let noteID = journal.noteIDs[item.itemID] else { throw LibraryBackupError.transaction("笔记恢复ID缺失") }
        try library.rollbackBackupNoteIfPresent(id: noteID, transactionID: journal.transactionID, groupID: item.itemID, journalRoot: journalRoot)
        if let coverID = item.coverResourceID, let resource = manifest.resources.first(where: { $0.resourceID == coverID }) {
            try coverStore.rollbackRestoredPNG(noteID: noteID, expectedSHA256: resource.sha256)
        }
        for entry in manifest.vaultEntries where entry.noteItemID == item.itemID {
            guard let id = journal.vaultIDs[entry.itemID] else { throw LibraryBackupError.transaction("知识库恢复ID缺失") }
            try vault.rollbackPartialArchiveEntry(id: id, transactionID: journal.transactionID, groupID: item.itemID)
        }
        for video in manifest.videoAttachments where video.noteItemID == item.itemID {
            guard let id = journal.videoIDs[video.itemID], let uuid = UUID(uuidString: id) else { throw LibraryBackupError.transaction("视频恢复ID无效") }
            try videoStore.rollbackPartial(id: uuid, transactionID: journal.transactionID, groupID: item.itemID, expectedSHA256: video.sha256)
        }
    }

    private nonisolated static func validateArchive(_ archive: URL, into stage: URL, existingNotes: [NoteDocument], cancellation: LibraryBackupCancellationToken? = nil) throws -> LibraryBackupPreview {
        let staged = try LibraryBackupArchive.stage(from: archive, into: stage, cancellation: cancellation)
        do {
            let m = staged.manifest
            var notes = [NoteDocument](), notesByItemID = [String: NoteDocument](), bytes: Int64 = 0
            for descriptor in m.notes {
                guard let resource = m.resources.first(where: { $0.resourceID == descriptor.noteResourceID }) else { throw LibraryBackupError.invalidManifest("笔记载荷缺失") }
                let url = stage.appendingPathComponent(resource.resourceID + ".bin")
                let noteBytes = try LibraryBackupArchive.readSmallFile(url, maximumBytes: LibraryBackupArchive.maxNoteBytes)
                let note = try decodeBackupNote(noteBytes)
                guard note.id == descriptor.sourceNoteID, note.schemaVersion == descriptor.schemaVersion,
                      try floorMS(note.updatedAt) == descriptor.sourceRevisionMS,
                      (note.pdfPageCount > 0) == (descriptor.pdfResourceID != nil) else { throw LibraryBackupError.invalidManifest("笔记载荷与manifest不一致") }
                if let pdfID = descriptor.pdfResourceID {
                    guard let pdfResource = m.resources.first(where: { $0.resourceID == pdfID }),
                          let pdf = PDFDocument(url: stage.appendingPathComponent(pdfID + ".bin")), !pdf.isEncrypted, !pdf.isLocked,
                          pdf.pageCount == note.pdfPageCount, pdf.pageCount > 0, pdf.pageCount <= 500 else { throw LibraryBackupError.invalidManifest("PDF与笔记页数不一致") }
                    _ = pdfResource
                }
                if let coverID = descriptor.coverResourceID { _ = try NoteCoverStore.validatePNGFile(stage.appendingPathComponent(coverID + ".bin")) }
                notes.append(note)
                notesByItemID[descriptor.itemID] = note
            }
            for entry in m.vaultEntries {
                guard let resource = m.resources.first(where: { $0.resourceID == entry.resourceID }) else {
                    throw LibraryBackupError.invalidManifest("知识库资源描述缺失")
                }
                _ = try readVerifiedVaultPayload(at: stage.appendingPathComponent(entry.resourceID + ".bin"),
                                                descriptor: resource, entry: entry)
            }
            for r in m.resources { bytes += r.byteLength }
            return LibraryBackupPreview(id: UUID(), archiveURL: archive, staged: staged, notes: notes, validatedNotesByItemID: notesByItemID, noteCount: notes.count,
                pdfCount: m.notes.filter { $0.pdfResourceID != nil }.count, coverCount: m.notes.filter { $0.coverResourceID != nil }.count,
                vaultCount: m.vaultEntries.count, videoCount: m.videoAttachments.count, presetCount: m.coverPresets.count,
                sourceConflicts: notes.filter { imported in existingNotes.contains(where: { $0.id == imported.id || $0.title == imported.title }) }.count, verifiedBytes: bytes)
        } catch { try? FileManager.default.removeItem(at: stage); throw error }
    }

    nonisolated static func decodeBackupNote(_ data: Data) throws -> NoteDocument {
        try LibraryBackupArchive.rejectDuplicateJSONKeys(data)
        return try NoteDocument.decode(data).validated()
    }

    nonisolated static func validateVaultPayloadJSON(_ data: Data) throws {
        try LibraryBackupArchive.validateIntegerJSONTokens(data)
        guard let object = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              Set(object.keys) == Set(["schema_version", "title", "markdown", "source_note_id", "source_revision_ms", "created_at_ms"]),
              let schema = object["schema_version"] as? NSNumber, CFGetTypeID(schema) != CFBooleanGetTypeID(),
              ["c", "s", "i", "q", "l", "C", "S", "I", "Q", "L"].contains(String(cString: schema.objCType)),
              schema.intValue == 1,
              object["title"] is String, object["markdown"] is String, object["source_note_id"] is String else {
            throw LibraryBackupError.invalidManifest("知识库载荷字段无效")
        }
        for key in ["source_revision_ms", "created_at_ms"] {
            guard let value = object[key] as? NSNumber, CFGetTypeID(value) != CFBooleanGetTypeID(),
                  ["c", "s", "i", "q", "l", "C", "S", "I", "Q", "L"].contains(String(cString: value.objCType)),
                  value.int64Value >= 0 else {
                throw LibraryBackupError.invalidManifest("知识库时间必须为非负整数")
            }
        }
    }

    nonisolated static func readVerifiedVaultPayload(at url: URL, descriptor: LibraryBackupManifest.Resource,
                                                                  entry: LibraryBackupManifest.Vault) throws -> LibraryBackupVaultPayload {
        guard descriptor.role == "vault_entry_json", descriptor.resourceID == entry.resourceID,
              descriptor.byteLength <= LibraryBackupArchive.maxVaultBytes else {
            throw LibraryBackupError.invalidManifest("知识库资源描述无效")
        }
        let bytes = try LibraryBackupArchive.readSmallFile(url, maximumBytes: LibraryBackupArchive.maxVaultBytes)
        let digest = SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined()
        guard Int64(bytes.count) == descriptor.byteLength, digest == descriptor.sha256 else { throw LibraryBackupError.sourceChanged }
        try Self.validateVaultPayloadJSON(bytes)
        let payload = try JSONDecoder().decode(LibraryBackupVaultPayload.self, from: bytes)
        guard payload.schemaVersion == 1, payload.sourceNoteID == entry.sourceNoteID,
              payload.sourceRevisionMS == entry.sourceRevisionMS, payload.createdAtMS == entry.createdAtMS else {
            throw LibraryBackupError.invalidManifest("知识库来源信息不一致")
        }
        return payload
    }

    private nonisolated static func floorMS(_ value: Double) throws -> Int64 {
        guard value.isFinite, value >= 0, value < Double(Int64.max) else { throw LibraryBackupError.invalidManifest("笔记时间值无效") }
        return Int64(value.rounded(.down))
    }

    nonisolated static func verifyGroupResources(_ ids: [String], manifest: LibraryBackupManifest, directory: URL, cancellation: LibraryBackupCancellationToken? = nil) async throws {
        try await Task.detached(priority: .userInitiated) {
            for id in ids {
                try cancellation?.check()
                guard let resource = manifest.resources.first(where: { $0.resourceID == id }) else { throw LibraryBackupError.invalidManifest("资源描述缺失") }
                let actual = try LibraryBackupArchive.hashFile(directory.appendingPathComponent(id + ".bin"), cancellation: cancellation)
                guard actual.size == resource.byteLength, actual.sha256 == resource.sha256 else { throw LibraryBackupError.sourceChanged }
            }
        }.value
    }

    private static func recoveryOptions(archiveSHA256: String, in root: URL) throws -> [LibraryBackupRecoveryOption] {
        guard FileManager.default.fileExists(atPath: root.path) else { return [] }
        try requirePlainDirectory(root)
        let urls = try FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: nil)
        guard urls.count <= 10_000 else { throw LibraryBackupError.sizeLimit("本地恢复日志数量超限") }
        var options = [LibraryBackupRecoveryOption]()
        for url in urls where url.pathExtension == "json" {
            _ = try LibraryBackupArchive.validateRegularSource(url, maximumBytes: 1024 * 1024)
            let journal = try JSONDecoder().decode(RestoreJournal.self, from: LibraryBackupArchive.readSmallFile(url, maximumBytes: 1024 * 1024))
            guard url.lastPathComponent == journal.transactionID + ".json",
                  journal.transactionID.range(of: "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", options: .regularExpression) != nil else {
                throw LibraryBackupError.unsafeFile
            }
            guard journal.archiveSHA256 == archiveSHA256,
                  ["staged", "incomplete"].contains(journal.status) else { continue }
            try validateJournalInternalConsistency(journal)
            let complete = Set(journal.completedGroups)
            let total = Set(journal.noteIDs.keys).union(journal.independentGroupIDs)
            options.append(LibraryBackupRecoveryOption(transactionID: journal.transactionID,
                completedGroupCount: complete.count, pendingGroupCount: total.subtracting(complete).count))
        }
        return options.sorted { $0.transactionID < $1.transactionID }
    }

    private static func removeStageIfUnreferenced(_ stage: URL, journalRoot: URL) throws {
        guard FileManager.default.fileExists(atPath: stage.path) else { return }
        guard stage.deletingLastPathComponent().standardizedFileURL == LibraryBackupTransactionGate.defaultStagingRoot.standardizedFileURL else {
            return
        }
        try requirePlainDirectory(stage)
        guard stage.lastPathComponent.range(of: "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", options: .regularExpression) != nil else {
            throw LibraryBackupError.unsafeFile
        }
        guard FileManager.default.fileExists(atPath: journalRoot.path) else {
            try FileManager.default.removeItem(at: stage); return
        }
        try requirePlainDirectory(journalRoot)
        let urls = try FileManager.default.contentsOfDirectory(at: journalRoot, includingPropertiesForKeys: nil)
        guard urls.count <= 10_000 else { throw LibraryBackupError.sizeLimit("本地恢复日志数量超限") }
        for url in urls where url.pathExtension == "json" {
            _ = try LibraryBackupArchive.validateRegularSource(url, maximumBytes: 1024 * 1024)
            let journal = try JSONDecoder().decode(RestoreJournal.self, from: LibraryBackupArchive.readSmallFile(url, maximumBytes: 1024 * 1024))
            try validateJournalInternalConsistency(journal)
            guard url.lastPathComponent == journal.transactionID + ".json" else { throw LibraryBackupError.unsafeFile }
            if journal.stageID == stage.lastPathComponent && journal.status != "committed" && journal.status != "rolled_back" { return }
        }
        try FileManager.default.removeItem(at: stage)
    }

    private static func removeOwnedStage(_ stage: URL, within root: URL) throws {
        let parent = stage.deletingLastPathComponent().standardizedFileURL
        guard parent == root.standardizedFileURL,
              stage.lastPathComponent.range(of: "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", options: .regularExpression) != nil,
              FileManager.default.fileExists(atPath: stage.path) else { return }
        try requirePlainDirectory(root); try requirePlainDirectory(stage)
        try FileManager.default.removeItem(at: stage)
    }

    static func writeJournal(_ value: RestoreJournal, to url: URL) throws {
        let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
        let data = try encoder.encode(value)
        try NoteGroupCatalogFence.withWriter {
            try data.write(to: url, options: [.atomic, .completeFileProtectionUnlessOpen])
        }
    }
    private static func requirePlainDirectory(_ url: URL) throws {
        var info = stat(); guard lstat(url.path, &info) == 0, info.st_mode & mode_t(S_IFMT) == mode_t(S_IFDIR) else { throw LibraryBackupError.unsafeFile }
    }

    nonisolated static func isGroupCompleted(_ itemID: String, in journal: RestoreJournal) -> Bool {
        journal.completedGroups.contains(itemID) && journal.groupPhases[itemID] == "committed"
    }

    nonisolated static func validateJournalInternalConsistency(_ journal: RestoreJournal) throws {
        let completed = journal.completedGroups
        let allowedGroups = Set(journal.noteIDs.keys).union(journal.independentGroupIDs)
        guard Set(completed).count == completed.count,
              Set(completed).isSubset(of: allowedGroups), Set(journal.groupPhases.keys).isSubset(of: allowedGroups),
              Set(journal.noteSHA256ByItemID.keys) == Set(journal.noteIDs.keys),
              journal.noteIDs.values.allSatisfy(isCanonicalUUID), journal.vaultIDs.values.allSatisfy(isCanonicalUUID),
              journal.videoIDs.values.allSatisfy(isCanonicalUUID), journal.presetIDs.values.allSatisfy(isCanonicalUUID),
              Set(journal.vaultGroupIDsByItemID.keys) == Set(journal.vaultIDs.keys),
              Set(journal.videoGroupIDsByItemID.keys) == Set(journal.videoIDs.keys),
              Set(journal.presetGroupIDsByItemID.keys) == Set(journal.presetIDs.keys),
              journal.noteSHA256ByItemID.values.allSatisfy(isSHA256), journal.resourceSHA256.values.allSatisfy(isSHA256),
              journal.groupPhases.values.allSatisfy({ ["staged", "resources_promoted", "note_promoted", "index_committed", "committed", "rolled_back", "cleanup_failed"].contains($0) }),
              Set(completed) == Set(journal.groupPhases.compactMap { $0.value == "committed" ? $0.key : nil }),
              Set(journal.independentGroupIDs).count == journal.independentGroupIDs.count,
              Set(journal.independentGroupIDs).isDisjoint(with: Set(journal.noteIDs.keys)),
              Set(journal.independentGroupIDs).isSubset(of: Set(journal.vaultIDs.keys).union(journal.videoIDs.keys).union(journal.presetIDs.keys)),
              Set(journal.vaultGroupIDsByItemID.values).isSubset(of: allowedGroups),
              Set(journal.videoGroupIDsByItemID.values).isSubset(of: allowedGroups),
              Set(journal.presetGroupIDsByItemID.values).isSubset(of: allowedGroups),
              journal.status != "committed" || Set(completed) == Set(journal.noteIDs.keys).union(journal.independentGroupIDs) else {
            throw LibraryBackupError.transaction("本地恢复日志阶段不一致")
        }
    }

    private static func validateJournalBinding(_ journal: RestoreJournal, manifest: LibraryBackupManifest) throws {
        try validateJournalInternalConsistency(journal)
        let selected = Set(journal.noteIDs.keys)
        let validNotes = Set(manifest.notes.map(\.itemID))
        guard selected.isSubset(of: validNotes), Set(journal.noteSHA256ByItemID.keys) == selected,
              journal.noteIDs.values.allSatisfy(Self.isCanonicalUUID),
              journal.noteSHA256ByItemID.values.allSatisfy(Self.isSHA256),
              journal.resourceSHA256 == Dictionary(uniqueKeysWithValues: manifest.resources.map { ($0.resourceID, $0.sha256) }) else {
            throw LibraryBackupError.transaction("本地恢复日志与归档资源绑定不匹配")
        }
        let expectedVault = Set(manifest.vaultEntries.filter { $0.noteItemID == nil || selected.contains($0.noteItemID!) }.map(\.itemID))
        let expectedVideo = Set(manifest.videoAttachments.filter { $0.noteItemID == nil || selected.contains($0.noteItemID!) }.map(\.itemID))
        let expectedPresets = Set(manifest.coverPresets.map(\.itemID))
        let expectedIndependent = Set(manifest.vaultEntries.filter { $0.noteItemID == nil }.map(\.itemID) +
            manifest.videoAttachments.filter { $0.noteItemID == nil }.map(\.itemID) + manifest.coverPresets.map(\.itemID))
        let allowedGroups = selected.union(expectedIndependent)
        let expectedVaultGroups = Dictionary(uniqueKeysWithValues: manifest.vaultEntries.filter { expectedVault.contains($0.itemID) }.map { ($0.itemID, $0.noteItemID ?? $0.itemID) })
        let expectedVideoGroups = Dictionary(uniqueKeysWithValues: manifest.videoAttachments.filter { expectedVideo.contains($0.itemID) }.map { ($0.itemID, $0.noteItemID ?? $0.itemID) })
        let expectedPresetGroups = Dictionary(uniqueKeysWithValues: manifest.coverPresets.map { ($0.itemID, $0.itemID) })
        guard Set(journal.vaultIDs.keys) == expectedVault, Set(journal.videoIDs.keys) == expectedVideo,
              Set(journal.presetIDs.keys) == expectedPresets, Set(journal.independentGroupIDs) == expectedIndependent,
              journal.vaultGroupIDsByItemID == expectedVaultGroups, journal.videoGroupIDsByItemID == expectedVideoGroups,
              journal.presetGroupIDsByItemID == expectedPresetGroups,
              journal.vaultIDs.values.allSatisfy(Self.isCanonicalUUID), journal.videoIDs.values.allSatisfy(Self.isCanonicalUUID),
              journal.presetIDs.values.allSatisfy(Self.isCanonicalUUID),
              Set(journal.groupPhases.keys).isSubset(of: allowedGroups), Set(journal.completedGroups).isSubset(of: allowedGroups) else {
            throw LibraryBackupError.transaction("本地恢复日志固定ID与归档不匹配")
        }
    }

    nonisolated private static func isCanonicalUUID(_ value: String) -> Bool {
        value.range(of: "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", options: .regularExpression) != nil
    }

    nonisolated private static func isSHA256(_ value: String) -> Bool {
        value.range(of: "^[0-9a-f]{64}$", options: .regularExpression) != nil
    }
}

struct BackupPendingNoteMarker: Codable, Equatable {
    let noteID: String
    let transactionID: String
    let groupID: String
    let expectedNoteSHA256: String
    let expectedPDFSHA256: String?
}

enum LibraryBackupUITestPaths {
    static var root: URL? {
#if DEBUG
        let arguments = ProcessInfo.processInfo.arguments
        if arguments.contains("--uitesting-content-outline") {
            guard let index = arguments.firstIndex(of: "--content-outline-fixture-id"),
                  arguments.indices.contains(index + 1),
                  let id = UUID(uuidString: arguments[index + 1]),
                  id.uuidString.lowercased() == arguments[index + 1].lowercased() else {
                preconditionFailure("Content outline UI tests require an explicit valid fixture UUID")
            }
            return FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
                .appendingPathComponent("UITestLibrary/ContentOutline", isDirectory: true)
                .appendingPathComponent(id.uuidString.lowercased(), isDirectory: true)
        }
        guard arguments.contains("--uitesting-library-backup"),
              let index = arguments.firstIndex(of: "--library-backup-fixture-id"),
              arguments.indices.contains(index + 1),
              let id = UUID(uuidString: arguments[index + 1]) else { return nil }
        return FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("PadNote/LibraryBackupUITest", isDirectory: true)
            .appendingPathComponent(id.uuidString.lowercased(), isDirectory: true)
#else
        return nil
#endif
    }

    static func directory(_ name: String, fallback: URL) -> URL {
        root?.appendingPathComponent(name, isDirectory: true) ?? fallback
    }
}

enum LibraryBackupTransactionGate {
    static var defaultJournalRoot: URL {
        if let root = LibraryBackupUITestPaths.root {
            return root.appendingPathComponent("transactions", isDirectory: true)
        }
        return FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("PadNote/library-backup-transactions", isDirectory: true)
    }
    static var defaultStagingRoot: URL { defaultJournalRoot.appendingPathComponent("staging", isDirectory: true) }

    static func pendingNoteIDs(in notesDirectory: URL, journalRoot: URL = defaultJournalRoot) throws -> Set<String> {
        let root = notesDirectory.appendingPathComponent(".backup-restore-pending", isDirectory: true)
        guard FileManager.default.fileExists(atPath: root.path) else { return [] }
        try UserCoverPresetStore.requireDirectoryForResources(root)
        let urls = try FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: nil)
        guard urls.count <= 20_000 else { throw LibraryBackupError.sizeLimit("备份恢复标记数量超限") }
        var result = Set<String>()
        for url in urls {
            let noteID = url.deletingPathExtension().lastPathComponent
            guard url.pathExtension == "json", noteID.range(of: "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", options: .regularExpression) != nil else {
                throw LibraryBackupError.unsafeFile
            }
            // A malformed marker still hides the exact generated note named by the safe filename.
            // It can never grant visibility; only a matching local journal commit can do that.
            guard (try? LibraryBackupArchive.validateRegularSource(url, maximumBytes: 16 * 1024)) != nil else {
                result.insert(noteID); continue
            }
            guard let data = try? LibraryBackupArchive.readSmallFile(url, maximumBytes: 16 * 1024),
                  let marker = try? JSONDecoder().decode(BackupPendingNoteMarker.self, from: data),
                  marker.noteID == noteID,
                  marker.transactionID.range(of: "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", options: .regularExpression) != nil,
                  marker.groupID.range(of: "^i-[0-9a-f]{32}$", options: .regularExpression) != nil else {
                result.insert(noteID); continue
            }
            if isCommitted(marker, in: journalRoot) {
                try removePendingMarker(marker, at: url, journalRoot: journalRoot)
            } else {
                result.insert(noteID)
            }
        }
        return result
    }

    static func createPendingMarker(noteID: String, transactionID: String, groupID: String,
                                    expectedNoteSHA256: String, expectedPDFSHA256: String?, in notesDirectory: URL,
                                    journalRoot: URL = defaultJournalRoot) throws {
        guard noteID.range(of: "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", options: .regularExpression) != nil,
              transactionID.range(of: "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", options: .regularExpression) != nil,
              groupID.range(of: "^i-[0-9a-f]{32}$", options: .regularExpression) != nil else { throw LibraryBackupError.transaction("恢复标记绑定无效") }
        let root = notesDirectory.appendingPathComponent(".backup-restore-pending", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        try UserCoverPresetStore.requireDirectoryForResources(root)
        guard expectedNoteSHA256.range(of: "^[0-9a-f]{64}$", options: .regularExpression) != nil,
              expectedPDFSHA256 == nil || expectedPDFSHA256!.range(of: "^[0-9a-f]{64}$", options: .regularExpression) != nil else {
            throw LibraryBackupError.transaction("恢复资源摘要无效")
        }
        let marker = BackupPendingNoteMarker(noteID: noteID, transactionID: transactionID, groupID: groupID,
            expectedNoteSHA256: expectedNoteSHA256, expectedPDFSHA256: expectedPDFSHA256)
        let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
        let data = try encoder.encode(marker)
        guard owns(marker, in: journalRoot) else { throw LibraryBackupError.transaction("恢复标记与本地日志不匹配") }
        let target = root.appendingPathComponent(noteID + ".json")
        let fd = Darwin.open(target.path, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, S_IRUSR | S_IWUSR)
        guard fd >= 0 else { throw LibraryBackupError.unsafeFile }
        let output = FileHandle(fileDescriptor: fd, closeOnDealloc: true)
        do { try output.write(contentsOf: data); try output.synchronize(); try output.close() }
        catch { try? output.close(); try? FileManager.default.removeItem(at: target); throw error }
    }

    static func removePendingMarker(noteID: String, transactionID: String, groupID: String, expectedNoteSHA256: String,
                                    in notesDirectory: URL, journalRoot: URL = defaultJournalRoot,
                                    requireCommitted: Bool = true) throws {
        let root = notesDirectory.appendingPathComponent(".backup-restore-pending", isDirectory: true)
        let url = root.appendingPathComponent(noteID + ".json")
        guard (try? LibraryBackupArchive.validateRegularSource(url, maximumBytes: 16 * 1024)) != nil,
              let data = try? LibraryBackupArchive.readSmallFile(url, maximumBytes: 16 * 1024),
              let marker = try? JSONDecoder().decode(BackupPendingNoteMarker.self, from: data),
              marker.noteID == noteID, marker.transactionID == transactionID, marker.groupID == groupID,
              marker.expectedNoteSHA256 == expectedNoteSHA256, owns(marker, in: journalRoot),
              (!requireCommitted || isCommitted(marker, in: journalRoot)) else {
            throw LibraryBackupError.transaction("本地恢复标记所有权不匹配")
        }
        try FileManager.default.removeItem(at: url)
    }

    private static func removePendingMarker(_ marker: BackupPendingNoteMarker, at url: URL, journalRoot: URL) throws {
        guard (try? LibraryBackupArchive.validateRegularSource(url, maximumBytes: 16 * 1024)) != nil,
              let data = try? LibraryBackupArchive.readSmallFile(url, maximumBytes: 16 * 1024),
              let current = try? JSONDecoder().decode(BackupPendingNoteMarker.self, from: data), current == marker,
              owns(marker, in: journalRoot), isCommitted(marker, in: journalRoot) else {
            throw LibraryBackupError.sourceChanged
        }
        try FileManager.default.removeItem(at: url)
    }

    static func ownsPendingNoteMarker(_ marker: BackupPendingNoteMarker, in directory: URL) -> Bool {
        owns(marker, in: directory)
    }

    private static func owns(_ marker: BackupPendingNoteMarker, in directory: URL) -> Bool {
        guard let journal = loadJournal(marker.transactionID, in: directory) else { return false }
        return journal.noteIDs[marker.groupID] == marker.noteID &&
            journal.noteSHA256ByItemID[marker.groupID] == marker.expectedNoteSHA256
    }

    private static func isCommitted(_ marker: BackupPendingNoteMarker, in directory: URL) -> Bool {
        guard let journal = loadJournal(marker.transactionID, in: directory), owns(marker, in: directory) else { return false }
        guard (try? LibraryBackupRestoreCoordinator.validateJournalInternalConsistency(journal)) != nil else { return false }
        return journal.status == "committed" || LibraryBackupRestoreCoordinator.isGroupCompleted(marker.groupID, in: journal)
    }

    static func readJournal(_ transactionID: String, in directory: URL) throws -> RestoreJournal? {
        guard transactionID.range(of: "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", options: .regularExpression) != nil else { throw LibraryBackupError.unsafeFile }
        let url = directory.appendingPathComponent(transactionID + ".json")
        guard FileManager.default.fileExists(atPath: url.path) else { return nil }
        _ = try LibraryBackupArchive.validateRegularSource(url, maximumBytes: 1024 * 1024)
        let journal = try JSONDecoder().decode(RestoreJournal.self, from: LibraryBackupArchive.readSmallFile(url, maximumBytes: 1024 * 1024))
        guard journal.transactionID == transactionID else { throw LibraryBackupError.unsafeFile }
        return journal
    }

    private static func loadJournal(_ transactionID: String, in directory: URL) -> RestoreJournal? {
        guard transactionID.range(of: "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", options: .regularExpression) != nil else { return nil }
        let url = directory.appendingPathComponent(transactionID + ".json")
        guard (try? LibraryBackupArchive.validateRegularSource(url, maximumBytes: 1024 * 1024)) != nil,
              let data = try? LibraryBackupArchive.readSmallFile(url, maximumBytes: 1024 * 1024),
              let journal = try? JSONDecoder().decode(RestoreJournal.self, from: data), journal.transactionID == transactionID else { return nil }
        return journal
    }

    static func isVisible(originKind: String?, transactionID: String?, groupID: String?, kind: String,
                          localID: String, in directory: URL) -> Bool {
        if originKind == nil {
            return kind != "video" && transactionID == nil && groupID == nil
        }
        guard originKind == "restored_archive", let transactionID, let groupID else { return false }
        return isCommitted(transactionID, groupID: groupID, kind: kind, localID: localID, in: directory)
    }

    static func isCommitted(_ transactionID: String, groupID: String?, kind: String, localID: String, in directory: URL) -> Bool {
        guard transactionID.range(of: "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", options: .regularExpression) != nil,
              let groupID, groupID.range(of: "^[ivac]-[0-9a-f]{32}$", options: .regularExpression) != nil else { return false }
        let url = directory.appendingPathComponent(transactionID + ".json")
        guard (try? LibraryBackupArchive.validateRegularSource(url, maximumBytes: 1024 * 1024)) != nil,
              let data = try? LibraryBackupArchive.readSmallFile(url, maximumBytes: 1024 * 1024),
              let journal = try? JSONDecoder().decode(RestoreJournal.self, from: data),
              journal.transactionID == transactionID,
              (try? LibraryBackupRestoreCoordinator.validateJournalInternalConsistency(journal)) != nil else { return false }
        let ownsBinding: Bool
        switch kind {
        case "vault": ownsBinding = journal.vaultIDs.contains { $0.value == localID && journal.vaultGroupIDsByItemID[$0.key] == groupID }
        case "video": ownsBinding = journal.videoIDs.contains { $0.value == localID && journal.videoGroupIDsByItemID[$0.key] == groupID }
        case "preset": ownsBinding = journal.presetIDs.contains { $0.value == localID && journal.presetGroupIDsByItemID[$0.key] == groupID }
        default: ownsBinding = false
        }
        return ownsBinding && LibraryBackupRestoreCoordinator.isGroupCompleted(groupID, in: journal)
    }

    private static func isCommitted(_ journal: RestoreJournal, groupID: String) -> Bool {
        guard (try? LibraryBackupRestoreCoordinator.validateJournalInternalConsistency(journal)) != nil else { return false }
        return journal.status == "committed" || LibraryBackupRestoreCoordinator.isGroupCompleted(groupID, in: journal)
    }
}
