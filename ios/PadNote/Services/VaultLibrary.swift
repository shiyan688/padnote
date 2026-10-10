import Foundation
import Combine

struct VaultDigitizationMetadata: Codable, Equatable {
    let pages: String
    let digitized: String
    let digitizedEpoch: String
    let sourceModified: String
    let operationID: String?

    func isValid(sourceRevisionMS: Int64, createdAtMS: Int64) -> Bool {
        guard pages.range(of: "^[0-9]+$", options: .regularExpression) != nil,
              let pageCount = Int32(pages), pageCount >= 0,
              digitizedEpoch.range(of: "^[0-9]+$", options: .regularExpression) != nil,
              sourceModified.range(of: "^[0-9]+$", options: .regularExpression) != nil,
              !digitized.contains("\n"), !digitized.contains("\r"),
              let sourceModifiedMS = Int64(sourceModified), sourceModifiedMS == sourceRevisionMS,
              let digitizedEpochMS = Int64(digitizedEpoch), digitizedEpochMS == createdAtMS else { return false }
        if let operationID {
            guard operationID.range(of: "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", options: .regularExpression) != nil,
                  UUID(uuidString: operationID)?.uuidString.lowercased() == operationID else { return false }
        }
        return true
    }
}

struct VaultNote: Codable, Identifiable, Equatable {
    var id: String
    var title: String
    var markdown: String
    var sourceUpdatedAt: Double
    var createdAt: Date
    var archiveOrigin: String? = nil
    var archiveSourceNoteID: String? = nil
    var archiveLinkedNoteID: String? = nil
    var archiveSourceState: String? = nil
    var restoreTransactionID: String? = nil
    var restoreGroupID: String? = nil
    var archiveDigitizationMetadata: VaultDigitizationMetadata? = nil
}

@MainActor
final class VaultLibrary: ObservableObject, DigitizationVaultPublishing {
    @Published var notes: [VaultNote] = []
    @Published var errorMessage: String?
    @Published private(set) var listingIssueCount = 0
    private let directory: URL
    private let journalRoot: URL
    private weak var noteLibrary: NoteLibrary?

    init(directory: URL? = nil, journalRoot: URL? = nil) {
        let testing = ProcessInfo.processInfo.arguments.contains("--uitesting")
        let fallback = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent(testing ? "PadNoteUITestVault" : "PadNoteVault", isDirectory: true)
        self.directory = directory ?? LibraryBackupUITestPaths.directory("vault", fallback: fallback)
        self.journalRoot = journalRoot ?? LibraryBackupTransactionGate.defaultJournalRoot
        reload()
    }

    func reload() {
        do {
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            try UserCoverPresetStore.requireDirectoryForResources(directory)
            let files = try FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)
            guard files.count <= 20_000 else { throw LibraryBackupError.sizeLimit("知识库记录数量超限") }
            var valid = [VaultNote](), issues = 0
            for url in files where url.pathExtension == "json" {
                do {
                    _ = try LibraryBackupArchive.validateRegularSource(url, maximumBytes: LibraryBackupArchive.maxVaultBytes + 128 * 1024)
                    let data = try LibraryBackupArchive.readSmallFile(url, maximumBytes: LibraryBackupArchive.maxVaultBytes + 128 * 1024)
                    let entry = try JSONDecoder().decode(VaultNote.self, from: data)
                    guard !entry.id.isEmpty, entry.id.utf8.count <= 512,
                          entry.id.unicodeScalars.allSatisfy({ !CharacterSet.controlCharacters.contains($0) }),
                          url.lastPathComponent == "\(safeID(entry.id)).json",
                          !entry.title.isEmpty, entry.title.utf8.count <= 512,
                          entry.markdown.utf8.count <= LibraryBackupArchive.maxVaultBytes,
                          entry.sourceUpdatedAt.isFinite,
                          entry.createdAt.timeIntervalSince1970.isFinite,
                          entry.createdAt.timeIntervalSince1970 >= 0 else {
                        throw LibraryBackupError.invalidManifest("知识库记录无效")
                    }
                    if let metadata = entry.archiveDigitizationMetadata {
                        guard entry.sourceUpdatedAt >= 0, entry.sourceUpdatedAt < 9_000_000_000_000_000,
                              entry.createdAt.timeIntervalSince1970 < 9_000_000_000_000,
                              metadata.isValid(sourceRevisionMS: Int64(entry.sourceUpdatedAt.rounded(.down)),
                                  createdAtMS: Int64((entry.createdAt.timeIntervalSince1970 * 1000).rounded())) else {
                            throw LibraryBackupError.invalidManifest("知识库数字化来源无效")
                        }
                    }
                    if LibraryBackupTransactionGate.isVisible(originKind: entry.archiveOrigin,
                        transactionID: entry.restoreTransactionID, groupID: entry.restoreGroupID, kind: "vault",
                        localID: entry.id, in: journalRoot) {
                        valid.append(entry)
                    }
                } catch { issues += 1 }
            }
            notes = valid.sorted { $0.createdAt > $1.createdAt }
            listingIssueCount = issues
            errorMessage = issues == 0 ? nil : "有 \(issues) 条知识库记录无法读取；其他有效条目仍可用，原文件已保留。"
        } catch {
            listingIssueCount = max(1, listingIssueCount)
            errorMessage = "知识库目录无法安全读取：\(error.localizedDescription)"
        }
    }

    /// Re-reads the persisted Vault row before a backup snapshot relies on its association.
    func backupColdRead(for expected: VaultNote) throws -> VaultNote {
        let url = directory.appendingPathComponent("\(safeID(expected.id)).json")
        let maximumBytes = LibraryBackupArchive.maxVaultBytes + 128 * 1024
        _ = try LibraryBackupArchive.validateRegularSource(url, maximumBytes: maximumBytes)
        let data = try LibraryBackupArchive.readSmallFile(url, maximumBytes: maximumBytes)
        let value = try JSONDecoder().decode(VaultNote.self, from: data)
        guard value.id == expected.id, value.title == expected.title, value.markdown == expected.markdown,
              value.sourceUpdatedAt == expected.sourceUpdatedAt, value.createdAt == expected.createdAt,
              value.archiveOrigin == expected.archiveOrigin, value.archiveSourceNoteID == expected.archiveSourceNoteID,
              value.archiveLinkedNoteID == expected.archiveLinkedNoteID, value.archiveSourceState == expected.archiveSourceState,
              value.restoreTransactionID == expected.restoreTransactionID, value.restoreGroupID == expected.restoreGroupID,
              value.archiveDigitizationMetadata == expected.archiveDigitizationMetadata,
              url.lastPathComponent == "\(safeID(value.id)).json",
              LibraryBackupTransactionGate.isVisible(originKind: value.archiveOrigin,
                  transactionID: value.restoreTransactionID, groupID: value.restoreGroupID, kind: "vault",
                  localID: value.id, in: journalRoot) else {
            throw LibraryBackupError.sourceChanged
        }
        return value
    }

    /// Returns the exact persisted legacy Vault JSON only after the same cold-read and
    /// association checks used by backup capture. The caller must treat it as read-only.
    func backupSourceURL(for expected: VaultNote) throws -> URL {
        let value = try backupColdRead(for: expected)
        let url = directory.appendingPathComponent("\(safeID(value.id)).json")
        _ = try LibraryBackupArchive.validateRegularSource(url, maximumBytes: LibraryBackupArchive.maxVaultBytes + 128 * 1024)
        return url
    }

    func backupJournalRoot() -> URL { journalRoot }

    func bind(noteLibrary: NoteLibrary) { self.noteLibrary = noteLibrary }

    @discardableResult
    func save(note: NoteDocument, markdown: String,
              expectedGroupToken: NoteGroupVersionToken? = nil) throws -> VaultNote {
        if let noteLibrary {
            return try noteLibrary.publishVaultSnapshot(using: self, note: note, markdown: markdown,
                                                        expectedGroupToken: expectedGroupToken)
        }
        guard expectedGroupToken == nil else { throw NoteGroupStoreError.compareAndSwapConflict }
        return try NoteGroupCatalogFence.withWriter {
            try saveLegacyUnderCatalogWriter(note: note, markdown: markdown)
        }
    }

    func saveLegacyUnderCatalogWriter(note: NoteDocument, markdown: String) throws -> VaultNote {
        guard Data(markdown.utf8).count <= LibraryBackupArchive.maxVaultBytes else {
            throw LibraryBackupError.sizeLimit("知识库条目超过 16 MiB")
        }
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let existingCreatedAt = notes.first(where: { $0.id == note.id })?.createdAt
        let entry = VaultNote(id: note.id, title: note.title, markdown: markdown,
                              sourceUpdatedAt: note.updatedAt, createdAt: existingCreatedAt ?? Date())
        let data = try JSONEncoder().encode(entry)
        try data.write(to: directory.appendingPathComponent("\(safeID(note.id)).json"), options: .atomic)
        reload()
        return entry
    }

    func publishDigitization(note: NoteDocument, checkpoint: DigitizationCheckpoint) throws {
        guard checkpoint.source.noteID == note.id, checkpoint.isComplete,
              checkpoint.state == .readyToPublish else {
            throw DigitizationError.invalidSource("只有全部页面完成的数字化批次才能保存到知识库。")
        }
        try save(note: note, markdown: checkpoint.markdown(markIncomplete: false),
                 expectedGroupToken: checkpoint.source.groupVersion)
    }

    func archiveSourceNoteIDs() -> Set<String> { Set(notes.compactMap { $0.archiveOrigin == nil ? $0.id : ($0.archiveLinkedNoteID ?? $0.archiveSourceNoteID) }) }

    func restoreArchiveEntry(id: String, title: String, markdown: String, sourceRevisionMS: Int64,
                             createdAtMS: Int64, sourceNoteID: String, sourceState: String = "independent",
                             linkedNoteID: String? = nil, transactionID: String? = nil,
                             groupID: String? = nil,
                             digitizationMetadata: VaultDigitizationMetadata? = nil) throws -> VaultNote {
        try NoteGroupCatalogFence.withWriter {
        guard UUID(uuidString: id)?.uuidString.lowercased() == id, sourceRevisionMS >= 0, createdAtMS >= 0,
              ["linked_note", "source_deleted", "source_not_selected", "independent"].contains(sourceState),
              (sourceState == "linked_note" ? linkedNoteID != nil : linkedNoteID == nil),
              linkedNoteID == nil || UUID(uuidString: linkedNoteID!)?.uuidString.lowercased() == linkedNoteID,
              Data(markdown.utf8).count <= 16 * 1024 * 1024,
              digitizationMetadata == nil || digitizationMetadata!.isValid(sourceRevisionMS: sourceRevisionMS, createdAtMS: createdAtMS) else {
            throw LibraryBackupError.invalidManifest("知识库恢复载荷无效")
        }
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let entry = VaultNote(id: id, title: title, markdown: markdown, sourceUpdatedAt: Double(sourceRevisionMS),
                              createdAt: Date(timeIntervalSince1970: Double(createdAtMS) / 1000),
                              archiveOrigin: "restored_archive", archiveSourceNoteID: sourceNoteID,
                              archiveLinkedNoteID: linkedNoteID, archiveSourceState: sourceState,
                              restoreTransactionID: transactionID, restoreGroupID: groupID,
                              archiveDigitizationMetadata: digitizationMetadata)
        let target = directory.appendingPathComponent("\(safeID(id)).json")
        guard !FileManager.default.fileExists(atPath: target.path) else { throw LibraryBackupError.transaction("知识库目标已存在") }
        let temporary = directory.appendingPathComponent(".\(UUID().uuidString).restore.partial")
        let bytes = try JSONEncoder().encode(entry)
        try bytes.write(to: temporary, options: [.atomic, .completeFileProtectionUnlessOpen])
        do { try FileManager.default.moveItem(at: temporary, to: target) } catch { try? FileManager.default.removeItem(at: temporary); throw error }
        reload()
        return entry
        }
    }

    func rollbackPartialArchiveEntry(id: String, transactionID: String, groupID: String) throws {
        try NoteGroupCatalogFence.withWriter {
        guard let uuid = UUID(uuidString: id), uuid.uuidString.lowercased() == id else { throw LibraryBackupError.unsafeFile }
        let target = directory.appendingPathComponent("\(safeID(id)).json")
        guard FileManager.default.fileExists(atPath: target.path) else { return }
        _ = try LibraryBackupArchive.validateRegularSource(target, maximumBytes: 16 * 1024 * 1024)
        let entry = try JSONDecoder().decode(VaultNote.self, from: LibraryBackupArchive.readSmallFile(target, maximumBytes: 16 * 1024 * 1024))
        guard entry.id == id, entry.archiveOrigin == "restored_archive", entry.restoreTransactionID == transactionID,
              entry.restoreGroupID == groupID else { throw LibraryBackupError.transaction("知识库恢复所有权不匹配") }
        if let noteLibrary { try noteLibrary.assertLegacyVaultMutationAllowed(for: entry) }
        else {
            let ownerID = entry.archiveLinkedNoteID ?? entry.archiveSourceNoteID ?? entry.id
            try NoteGroupStore.requireLegacyMutationAllowed(noteID: ownerID)
        }
        try FileManager.default.removeItem(at: target)
        reload()
        }
    }

    func removeRestoredArchiveEntry(_ note: VaultNote,
                                    expectedGroupToken: NoteGroupVersionToken? = nil) throws {
        guard note.archiveOrigin == "restored_archive" else { throw LibraryBackupError.transaction("只能通过归档恢复记录执行此操作") }
        try delete(note, expectedGroupToken: expectedGroupToken)
    }

    func delete(_ note: VaultNote, expectedGroupToken: NoteGroupVersionToken? = nil) throws {
        if let noteLibrary {
            try noteLibrary.deleteVaultSnapshot(using: self, value: note, expectedGroupToken: expectedGroupToken)
            return
        }
        guard expectedGroupToken == nil else { throw NoteGroupStoreError.compareAndSwapConflict }
        let ownerID = note.archiveOrigin == "restored_archive"
            ? (note.archiveLinkedNoteID ?? note.archiveSourceNoteID ?? note.id) : note.id
        try NoteGroupCatalogFence.withWriter {
        try NoteGroupStore.requireLegacyMutationAllowed(noteID: ownerID)
        let persisted = try backupColdRead(for: note)
        guard persisted == note else { throw NoteGroupStoreError.compareAndSwapConflict }
        try deleteLegacyUnderCatalogWriter(note)
        }
    }

    func deleteLegacyUnderCatalogWriter(_ note: VaultNote) throws {
        try FileManager.default.removeItem(at: directory.appendingPathComponent("\(safeID(note.id)).json"))
        reload()
    }

    func export(_ note: VaultNote) throws -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("\(safeID(note.id)).md")
        try note.markdown.write(to: url, atomically: true, encoding: .utf8)
        return url
    }

    private func safeID(_ id: String) -> String {
        Data(id.utf8).base64EncodedString().replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "+", with: "-")
    }
}
