import XCTest
import CryptoKit
import zlib
import Darwin
import UIKit
@testable import PadNote

final class LibraryBackupArchiveTests: XCTestCase {
    private func temporaryDirectory() throws -> URL {
        let value = FileManager.default.temporaryDirectory.appendingPathComponent("library-backup-test-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: value, withIntermediateDirectories: false)
        return value
    }

    private func descriptor(_ id: String, _ bytes: Data) -> LibraryBackupManifest.Resource {
        let digest = SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined()
        return .init(resourceID: id, role: "note_document", mediaType: "application/json", byteLength: Int64(bytes.count), sha256: digest, member: "payload/\(id).bin")
    }

    private func sha256(_ bytes: Data) -> String {
        SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined()
    }

    func testSelectedDocumentURLDispatchesByPersistentImportPurpose() throws {
        let archiveURL = URL(fileURLWithPath: "/tmp/selected-library.zip")
        let presetURL = URL(fileURLWithPath: "/tmp/selected-cover.png")

        guard case .archive(let selectedArchive)? = LibraryBackupImportSelection.resolve(
            result: .success([archiveURL]), purpose: .archive) else {
            return XCTFail("selected ZIP URL must dispatch to archive inspection")
        }
        XCTAssertEqual(selectedArchive, archiveURL)

        guard case .preset(let selectedPreset)? = LibraryBackupImportSelection.resolve(
            result: .success([presetURL]), purpose: .preset) else {
            return XCTFail("selected PNG URL must dispatch to preset import")
        }
        XCTAssertEqual(selectedPreset, presetURL)

        let canceled: Result<[URL], Error> = .failure(CancellationError())
        XCTAssertNil(LibraryBackupImportSelection.resolve(result: canceled, purpose: .archive))
        XCTAssertNil(LibraryBackupImportSelection.resolve(result: .success([]), purpose: .preset))
    }

    func testStoredWriterStagesExactManifestAndPayload() throws {
        let root = try temporaryDirectory(); defer { try? FileManager.default.removeItem(at: root) }
        var note = NoteDocument(title: "备份测试")
        note.updatedAt = 1_790_000_000_000.75
        let payload = try note.encoded()
        let resource = descriptor("r-0123456789abcdef0123456789abcdef", payload)
        let item = LibraryBackupManifest.Note(itemID: "i-0123456789abcdef0123456789abcdef", sourceNoteID: note.id,
            sourceRevisionMS: 1_790_000_000_000, schemaVersion: note.schemaVersion, noteResourceID: resource.resourceID,
            pdfResourceID: nil, coverResourceID: nil, vaultEntryIDs: [], videoAttachmentIDs: [])
        let manifest = LibraryBackupManifest(createdAtMS: 1_790_000_000_000, producer: ["platform": "ios", "app_version": "test"],
            notes: [item], vaultEntries: [], videoAttachments: [], coverPresets: [], resources: [resource])
        try LibraryBackupArchive.validate(manifest: manifest)
        let payloadURL = root.appendingPathComponent("payload.bin")
        try payload.write(to: payloadURL)
        let archiveURL = root.appendingPathComponent("fixture.padnote-library.zip")
        try LibraryBackupArchive.write(manifest: manifest, resourceFiles: [resource.resourceID: payloadURL], to: archiveURL)
        let staged = try LibraryBackupArchive.stage(from: archiveURL, into: root.appendingPathComponent("stage", isDirectory: true))
        XCTAssertEqual(staged.manifest, manifest)
        XCTAssertEqual(try Data(contentsOf: staged.directory.appendingPathComponent(resource.resourceID + ".bin")), payload)
        XCTAssertEqual(staged.archiveSHA256.count, 64)
    }

    func testRestoredVaultSnapshotRelinksAcrossNewItemIDsAndRejectsWrongNoteMappings() throws {
        let root = try temporaryDirectory(); defer { try? FileManager.default.removeItem(at: root) }
        let transaction = UUID().uuidString.lowercased()
        let restoredNoteID = UUID().uuidString.lowercased()
        let wrongNoteID = UUID().uuidString.lowercased()
        let vaultID = UUID().uuidString.lowercased()
        let archivedNoteItemID = "i-0123456789abcdef0123456789abcdef"
        let archivedVaultItemID = "v-0123456789abcdef0123456789abcdef"
        let currentNoteItemID = "i-fedcba9876543210fedcba9876543210"
        XCTAssertNotEqual(currentNoteItemID, archivedNoteItemID, "a rebackup allocates fresh manifest item IDs")
        let journalURL = root.appendingPathComponent(transaction + ".json")
        let journal = RestoreJournal(transactionID: transaction, archiveSHA256: String(repeating: "a", count: 64),
            status: "committed", stageID: UUID().uuidString.lowercased(),
            noteIDs: [archivedNoteItemID: restoredNoteID],
            noteSHA256ByItemID: [archivedNoteItemID: String(repeating: "b", count: 64)],
            vaultIDs: [archivedVaultItemID: vaultID], videoIDs: [:], presetIDs: [:],
            vaultGroupIDsByItemID: [archivedVaultItemID: archivedNoteItemID],
            videoGroupIDsByItemID: [:], presetGroupIDsByItemID: [:], resourceSHA256: [:],
            independentGroupIDs: [], completedGroups: [archivedNoteItemID],
            groupPhases: [archivedNoteItemID: "committed"], updatedAt: Date())
        let value = VaultNote(id: vaultID, title: "roundtrip", markdown: "body", sourceUpdatedAt: 1,
            createdAt: Date(timeIntervalSince1970: 2), archiveOrigin: "restored_archive",
            archiveSourceNoteID: "fixture-source-note", archiveLinkedNoteID: restoredNoteID,
            archiveSourceState: "linked_note", restoreTransactionID: transaction,
            restoreGroupID: archivedNoteItemID)
        func project(_ journalValue: RestoreJournal, currentItemID: String?, currentLocalNoteID: String?) throws -> Data {
            try JSONEncoder().encode(journalValue).write(to: journalURL)
            return try RestoredVaultMaterialProjection.descriptor(value, sourceNoteID: "fixture-source-note",
                sourceRevisionMS: 1, createdAtMS: 2, currentSourceState: "linked_note",
                currentNoteItemID: currentItemID, currentLinkedNoteID: currentLocalNoteID,
                journalRoot: root)
        }

        let encoded = try project(journal, currentItemID: currentNoteItemID, currentLocalNoteID: restoredNoteID)
        let summary = try MaterialCodec.inspectVaultDescriptor(encoded)
        XCTAssertEqual(summary.sourceState, "linked_note")
        XCTAssertEqual(summary.ownerLineageID, restoredNoteID)
        XCTAssertEqual(summary.sourceLineageID, restoredNoteID)
        XCTAssertNoThrow(try MaterialCodec.digest(encoded))

        XCTAssertThrowsError(try project(journal, currentItemID: currentNoteItemID, currentLocalNoteID: wrongNoteID)) { error in
            XCTAssertEqual(error as? LibraryBackupError, .transaction("恢复知识库当前笔记关联无效"))
        }
        XCTAssertThrowsError(try project(journal, currentItemID: nil, currentLocalNoteID: restoredNoteID)) { error in
            XCTAssertEqual(error as? LibraryBackupError, .transaction("恢复知识库当前笔记关联无效"))
        }
        var wrongJournalBinding = journal
        wrongJournalBinding.noteIDs[archivedNoteItemID] = wrongNoteID
        XCTAssertThrowsError(try project(wrongJournalBinding, currentItemID: currentNoteItemID, currentLocalNoteID: restoredNoteID)) { error in
            XCTAssertEqual(error as? LibraryBackupError, .transaction("恢复知识库原笔记映射无效"))
        }
    }

    func testRestoredVisibilityRequiresExactCommittedJournalResourceAndGroupBinding() throws {
        let root = try temporaryDirectory(); defer { try? FileManager.default.removeItem(at: root) }
        let transaction = UUID().uuidString.lowercased()
        let vaultItem = "v-0123456789abcdef0123456789abcdef"
        let videoItem = "a-0123456789abcdef0123456789abcdef"
        let presetItem = "c-0123456789abcdef0123456789abcdef"
        let vaultID = UUID().uuidString.lowercased(), videoID = UUID().uuidString.lowercased(), presetID = UUID().uuidString.lowercased()
        let groups = [vaultItem, videoItem, presetItem].sorted()
        let phases = Dictionary(uniqueKeysWithValues: groups.map { ($0, "committed") })
        let journal = RestoreJournal(transactionID: transaction, archiveSHA256: String(repeating: "a", count: 64),
            status: "committed", stageID: UUID().uuidString.lowercased(), noteIDs: [:], noteSHA256ByItemID: [:],
            vaultIDs: [vaultItem: vaultID], videoIDs: [videoItem: videoID], presetIDs: [presetItem: presetID],
            vaultGroupIDsByItemID: [vaultItem: vaultItem], videoGroupIDsByItemID: [videoItem: videoItem],
            presetGroupIDsByItemID: [presetItem: presetItem], resourceSHA256: [:], independentGroupIDs: groups,
            completedGroups: groups, groupPhases: phases, updatedAt: Date())
        try JSONEncoder().encode(journal).write(to: root.appendingPathComponent(transaction + ".json"))

        XCTAssertTrue(LibraryBackupTransactionGate.isVisible(originKind: "restored_archive", transactionID: transaction,
            groupID: vaultItem, kind: "vault", localID: vaultID, in: root))
        XCTAssertTrue(LibraryBackupTransactionGate.isVisible(originKind: "restored_archive", transactionID: transaction,
            groupID: videoItem, kind: "video", localID: videoID, in: root))
        XCTAssertTrue(LibraryBackupTransactionGate.isVisible(originKind: "restored_archive", transactionID: transaction,
            groupID: presetItem, kind: "preset", localID: presetID, in: root))
        XCTAssertFalse(LibraryBackupTransactionGate.isVisible(originKind: "restored_archive", transactionID: nil,
            groupID: videoItem, kind: "video", localID: videoID, in: root))
        XCTAssertFalse(LibraryBackupTransactionGate.isVisible(originKind: "restored_archive", transactionID: transaction,
            groupID: nil, kind: "video", localID: videoID, in: root))
        XCTAssertFalse(LibraryBackupTransactionGate.isVisible(originKind: "restored_archive", transactionID: transaction,
            groupID: videoItem, kind: "video", localID: UUID().uuidString.lowercased(), in: root))
        XCTAssertFalse(LibraryBackupTransactionGate.isVisible(originKind: "restored_archive", transactionID: transaction,
            groupID: "i-0123456789abcdef0123456789abcdef", kind: "video", localID: videoID, in: root))
        XCTAssertFalse(LibraryBackupTransactionGate.isVisible(originKind: "restored_archive", transactionID: UUID().uuidString.lowercased(),
            groupID: videoItem, kind: "video", localID: videoID, in: root))
        XCTAssertFalse(LibraryBackupTransactionGate.isVisible(originKind: "future_origin", transactionID: nil,
            groupID: nil, kind: "vault", localID: "legacy", in: root))
        XCTAssertTrue(LibraryBackupTransactionGate.isVisible(originKind: nil, transactionID: nil,
            groupID: nil, kind: "vault", localID: "ordinary-vault", in: root))
        XCTAssertTrue(LibraryBackupTransactionGate.isVisible(originKind: nil, transactionID: nil,
            groupID: nil, kind: "preset", localID: UUID().uuidString.lowercased(), in: root))
        XCTAssertFalse(LibraryBackupTransactionGate.isVisible(originKind: nil, transactionID: nil,
            groupID: nil, kind: "video", localID: videoID, in: root))
    }

    func testCancelledWriterRemovesOwnedPartialAndNeverPublishesArchive() throws {
        let root = try temporaryDirectory(); defer { try? FileManager.default.removeItem(at: root) }
        let payload = Data("{}".utf8)
        let resource = descriptor("r-0123456789abcdef0123456789abcdef", payload)
        let note = LibraryBackupManifest.Note(itemID: "i-0123456789abcdef0123456789abcdef", sourceNoteID: UUID().uuidString,
            sourceRevisionMS: 1, schemaVersion: 8, noteResourceID: resource.resourceID, pdfResourceID: nil, coverResourceID: nil,
            vaultEntryIDs: [], videoAttachmentIDs: [])
        let manifest = LibraryBackupManifest(createdAtMS: 1, producer: ["platform": "ios", "app_version": "test"],
            notes: [note], vaultEntries: [], videoAttachments: [], coverPresets: [], resources: [resource])
        let source = root.appendingPathComponent("payload.json"); try payload.write(to: source)
        let destination = root.appendingPathComponent("cancelled.zip")
        let token = LibraryBackupCancellationToken(); token.cancel()
        XCTAssertThrowsError(try LibraryBackupArchive.write(manifest: manifest,
            resourceFiles: [resource.resourceID: source], to: destination, cancellation: token)) { error in
            XCTAssertEqual(error as? LibraryBackupError, .cancelled)
        }
        XCTAssertFalse(FileManager.default.fileExists(atPath: destination.path))
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: root.path).sorted(), ["payload.json"])
    }

    func testCancellationDuringStreamingCopyRemovesPartialOutput() throws {
        let root = try temporaryDirectory(); defer { try? FileManager.default.removeItem(at: root) }
        let source = root.appendingPathComponent("large-note.json")
        let sourceFD = open(source.path, O_WRONLY | O_CREAT | O_EXCL, S_IRUSR | S_IWUSR)
        XCTAssertGreaterThanOrEqual(sourceFD, 0)
        let handle = FileHandle(fileDescriptor: sourceFD, closeOnDealloc: true)
        let block = Data(repeating: 0x61, count: 64 * 1024)
        for _ in 0..<512 { try handle.write(contentsOf: block) }
        try handle.synchronize(); try handle.close()
        let digest = try LibraryBackupArchive.hashFile(source)
        let resource = LibraryBackupManifest.Resource(resourceID: "r-0123456789abcdef0123456789abcdef", role: "note_document",
            mediaType: "application/json", byteLength: digest.size, sha256: digest.sha256,
            member: "payload/r-0123456789abcdef0123456789abcdef.bin")
        let note = LibraryBackupManifest.Note(itemID: "i-0123456789abcdef0123456789abcdef", sourceNoteID: UUID().uuidString,
            sourceRevisionMS: 1, schemaVersion: 8, noteResourceID: resource.resourceID, pdfResourceID: nil, coverResourceID: nil,
            vaultEntryIDs: [], videoAttachmentIDs: [])
        let manifest = LibraryBackupManifest(createdAtMS: 1, producer: ["platform": "ios", "app_version": "test"],
            notes: [note], vaultEntries: [], videoAttachments: [], coverPresets: [], resources: [resource])
        let destination = root.appendingPathComponent("cancel-mid-stream.zip")
        let token = LibraryBackupCancellationToken()
        let signal = DispatchSemaphore(value: 0)
        DispatchQueue.global(qos: .userInitiated).async {
            let deadline = Date().addingTimeInterval(3)
            while Date() < deadline {
                if let entries = try? FileManager.default.contentsOfDirectory(atPath: root.path),
                   let partial = entries.first(where: { $0.hasSuffix(".library-backup.partial") }),
                   let attrs = try? FileManager.default.attributesOfItem(atPath: root.appendingPathComponent(partial).path),
                   ((attrs[.size] as? NSNumber)?.intValue ?? 0) >= 1024 * 1024 {
                    token.cancel(); signal.signal(); return
                }
                Thread.sleep(forTimeInterval: 0.0005)
            }
            signal.signal()
        }
        XCTAssertThrowsError(try LibraryBackupArchive.write(manifest: manifest,
            resourceFiles: [resource.resourceID: source], to: destination, cancellation: token)) { error in
            XCTAssertEqual(error as? LibraryBackupError, .cancelled)
        }
        XCTAssertEqual(signal.wait(timeout: .now() + 1), .success)
        XCTAssertFalse(FileManager.default.fileExists(atPath: destination.path))
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: root.path).sorted(), ["large-note.json"])
    }

    func testManifestRejectsUnreferencedAndUnsafeResourceMembers() throws {
        let payload = Data("{}".utf8)
        var resource = descriptor("r-0123456789abcdef0123456789abcdef", payload)
        resource = .init(resourceID: resource.resourceID, role: resource.role, mediaType: resource.mediaType,
            byteLength: resource.byteLength, sha256: resource.sha256, member: "payload/../escape.bin")
        let note = LibraryBackupManifest.Note(itemID: "i-0123456789abcdef0123456789abcdef", sourceNoteID: UUID().uuidString,
            sourceRevisionMS: 1, schemaVersion: 8, noteResourceID: resource.resourceID, pdfResourceID: nil, coverResourceID: nil,
            vaultEntryIDs: [], videoAttachmentIDs: [])
        let manifest = LibraryBackupManifest(createdAtMS: 1, producer: ["platform": "ios", "app_version": "test"], notes: [note], vaultEntries: [], videoAttachments: [], coverPresets: [], resources: [resource])
        XCTAssertThrowsError(try LibraryBackupArchive.validate(manifest: manifest))
    }



    func testCanonicalPeerFixtureMatchesAndStages() throws {
        let archiveURL = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("../docs/fixtures/library-backup-r1/canonical/valid-library.zip").standardizedFileURL
        let manifestURL = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("../docs/fixtures/library-backup-r1/canonical/valid-manifest.json").standardizedFileURL
        let archive = try Data(contentsOf: archiveURL)
        let manifestBytes = try Data(contentsOf: manifestURL)
        XCTAssertEqual(sha256(archive), "8f319616681acfa50abe840300c3b9040262296e69a7f0b3e693f547a5984cc5")
        XCTAssertEqual(sha256(manifestBytes), "2d66d913ff7b5d75d5ff5101fe9a27b52b0b5018b5c595b1da8a5064dc674164")
        let manifest = try JSONDecoder().decode(LibraryBackupManifest.self, from: manifestBytes)
        try LibraryBackupArchive.validateManifestJSON(manifestBytes)
        try LibraryBackupArchive.validate(manifest: manifest)
        let root = try temporaryDirectory(); defer { try? FileManager.default.removeItem(at: root) }
        let localCopy = root.appendingPathComponent("fixture.zip"); try archive.write(to: localCopy)
        let staged = try LibraryBackupArchive.stage(from: localCopy, into: root.appendingPathComponent("stage", isDirectory: true))
        XCTAssertEqual(staged.manifest, manifest)
        // Preserve the iOS writer output in xcresult so the independent Python reference
        // validator can check the exact bytes before the importer consumes that archive.
        let writerArchive = root.appendingPathComponent("ios-writer-roundtrip.zip")
        var writerDocument = NoteDocument(title: "writer/import roundtrip")
        writerDocument.updatedAt = 1_790_000_000_000.75
        let noteBytes = try writerDocument.encoded()
        let writerResource = descriptor("r-abcdef0123456789abcdef0123456789", noteBytes)
        let writerNote = LibraryBackupManifest.Note(itemID: "i-abcdef0123456789abcdef0123456789",
            sourceNoteID: writerDocument.id, sourceRevisionMS: 1_790_000_000_000, schemaVersion: 8,
            noteResourceID: writerResource.resourceID, pdfResourceID: nil, coverResourceID: nil,
            vaultEntryIDs: [], videoAttachmentIDs: [])
        let writerManifest = LibraryBackupManifest(createdAtMS: 1, producer: ["platform": "ios", "app_version": "test"],
            notes: [writerNote], vaultEntries: [], videoAttachments: [], coverPresets: [], resources: [writerResource])
        let noteURL = root.appendingPathComponent("writer-note.json"); try noteBytes.write(to: noteURL)
        try LibraryBackupArchive.write(manifest: writerManifest, resourceFiles: [writerResource.resourceID: noteURL], to: writerArchive)
        let attachment = XCTAttachment(contentsOfFile: writerArchive)
        attachment.name = "ios-writer-roundtrip.zip"
        attachment.lifetime = .keepAlways
        add(attachment)
        let imported = try LibraryBackupArchive.stage(from: writerArchive, into: root.appendingPathComponent("writer-stage", isDirectory: true))
        XCTAssertEqual(imported.manifest, writerManifest)
    }

    func testManifestPreflightRejectsIgnoredDefaultsFloatsAndMissingNullableFields() throws {
        let root = try temporaryDirectory(); defer { try? FileManager.default.removeItem(at: root) }
        let note = NoteDocument(title: "strict json")
        let payload = try note.encoded()
        let resource = descriptor("r-0123456789abcdef0123456789abcdef", payload)
        let item = LibraryBackupManifest.Note(itemID: "i-0123456789abcdef0123456789abcdef", sourceNoteID: note.id,
            sourceRevisionMS: 1, schemaVersion: note.schemaVersion, noteResourceID: resource.resourceID,
            pdfResourceID: nil, coverResourceID: nil, vaultEntryIDs: [], videoAttachmentIDs: [])
        let manifest = LibraryBackupManifest(createdAtMS: 1, producer: ["platform": "ios", "app_version": "test"], notes: [item], vaultEntries: [],
            videoAttachments: [], coverPresets: [], resources: [resource])
        let json = try JSONEncoder().encode(manifest)
        try LibraryBackupArchive.validateManifestJSON(json)
        let text = String(decoding: json, as: UTF8.self)
        let fractional = Data(text.replacingOccurrences(of: "\"created_at_ms\":1", with: "\"created_at_ms\":1.0").utf8)
        XCTAssertThrowsError(try LibraryBackupArchive.validateManifestJSON(fractional))
        let wrongVersion = Data(text.replacingOccurrences(of: "\"format_version\":1", with: "\"format_version\":9").utf8)
        XCTAssertThrowsError(try LibraryBackupArchive.validateManifestJSON(wrongVersion))
        var omittedText = text.replacingOccurrences(of: ",\"pdf_resource_id\":null", with: "")
        if omittedText == text { omittedText = text.replacingOccurrences(of: "\"pdf_resource_id\":null,", with: "") }
        XCTAssertNotEqual(omittedText, text)
        let omittedNullable = Data(omittedText.utf8)
        XCTAssertThrowsError(try LibraryBackupArchive.validateManifestJSON(omittedNullable))
        let duplicateField = Data(text.replacingOccurrences(of: "\"format_version\":1", with: "\"format_version\":1,\"format_version\":1").utf8)
        XCTAssertThrowsError(try LibraryBackupArchive.validateManifestJSON(duplicateField))
        var missingProducerText = text.replacingOccurrences(of: "\"app_version\":\"test\",", with: "")
        if missingProducerText == text { missingProducerText = text.replacingOccurrences(of: ",\"app_version\":\"test\"", with: "") }
        XCTAssertNotEqual(missingProducerText, text)
        let missingProducer = Data(missingProducerText.utf8)
        XCTAssertThrowsError(try LibraryBackupArchive.validateManifestJSON(missingProducer))
        let badPlatform = Data(text.replacingOccurrences(of: "\"platform\":\"ios\"", with: "\"platform\":\"desktop\"").utf8)
        XCTAssertThrowsError(try LibraryBackupArchive.validateManifestJSON(badPlatform))
        let emptyVersion = Data(text.replacingOccurrences(of: "\"app_version\":\"test\"", with: "\"app_version\":\"\"").utf8)
        XCTAssertThrowsError(try LibraryBackupArchive.validateManifestJSON(emptyVersion))
        let stagedDirectory = try temporaryDirectory(); defer { try? FileManager.default.removeItem(at: stagedDirectory) }
        try wrongVersion.write(to: stagedDirectory.appendingPathComponent(".restore-manifest.json"))
        XCTAssertThrowsError(try LibraryBackupArchive.readStagedManifest(from: stagedDirectory))
    }

    func testPerNotePendingMarkerFailsClosedAndOnlyLocalCommittedGroupClearsIt() throws {
        let root = try temporaryDirectory(); defer { try? FileManager.default.removeItem(at: root) }
        let notes = root.appendingPathComponent("notes", isDirectory: true)
        let journals = root.appendingPathComponent("journals", isDirectory: true)
        try FileManager.default.createDirectory(at: notes, withIntermediateDirectories: false)
        try FileManager.default.createDirectory(at: journals, withIntermediateDirectories: false)
        let noteID = UUID().uuidString.lowercased(), transactionID = UUID().uuidString.lowercased()
        let groupID = "i-0123456789abcdef0123456789abcdef"
        let digest = String(repeating: "a", count: 64)
        func writeJournal(_ tx: String, note: String, phase: String) throws {
            let journal = RestoreJournal(transactionID: tx, archiveSHA256: digest, status: "incomplete", stageID: "stage",
                noteIDs: [groupID: note], noteSHA256ByItemID: [groupID: digest], vaultIDs: [:], videoIDs: [:], presetIDs: [:],
                resourceSHA256: [:], independentGroupIDs: [], completedGroups: phase == "committed" ? [groupID] : [],
                groupPhases: [groupID: phase], updatedAt: Date())
            try JSONEncoder().encode(journal).write(to: journals.appendingPathComponent(tx + ".json"))
        }
        try writeJournal(transactionID, note: noteID, phase: "staged")
        try LibraryBackupTransactionGate.createPendingMarker(noteID: noteID, transactionID: transactionID,
            groupID: groupID, expectedNoteSHA256: digest, expectedPDFSHA256: nil, in: notes, journalRoot: journals)
        XCTAssertEqual(try LibraryBackupTransactionGate.pendingNoteIDs(in: notes, journalRoot: journals), [noteID])
        let marker = notes.appendingPathComponent(".backup-restore-pending/\(noteID).json")
        try Data("corrupt".utf8).write(to: marker, options: .atomic)
        XCTAssertEqual(try LibraryBackupTransactionGate.pendingNoteIDs(in: notes, journalRoot: journals), [noteID])

        let committedID = UUID().uuidString.lowercased(), committedTx = UUID().uuidString.lowercased()
        try writeJournal(committedTx, note: committedID, phase: "staged")
        try LibraryBackupTransactionGate.createPendingMarker(noteID: committedID, transactionID: committedTx,
            groupID: groupID, expectedNoteSHA256: digest, expectedPDFSHA256: nil, in: notes, journalRoot: journals)
        try writeJournal(committedTx, note: committedID, phase: "committed")
        XCTAssertEqual(try LibraryBackupTransactionGate.pendingNoteIDs(in: notes, journalRoot: journals), [noteID])
        XCTAssertFalse(FileManager.default.fileExists(atPath: notes.appendingPathComponent(".backup-restore-pending/\(committedID).json").path))
    }

    @MainActor
    func testRollbackMarkerCannotRemovePreexistingNoteWithDifferentBytes() throws {
        let root = try temporaryDirectory(); defer { try? FileManager.default.removeItem(at: root) }
        let notesDirectory = root.appendingPathComponent("notes", isDirectory: true)
        let library = NoteLibrary(directory: notesDirectory)
        let note = NoteDocument(id: UUID().uuidString.lowercased(), title: "existing note")
        try library.save(note)
        let path = notesDirectory.appendingPathComponent(note.id + ".json")
        let original = try Data(contentsOf: path)
        let transactionID = UUID().uuidString.lowercased()
        let groupID = "i-0123456789abcdef0123456789abcdef"
        let wrongDigest = String(repeating: "f", count: 64)
        let journals = root.appendingPathComponent("journals", isDirectory: true)
        try FileManager.default.createDirectory(at: journals, withIntermediateDirectories: false)
        let journal = RestoreJournal(transactionID: transactionID, archiveSHA256: wrongDigest, status: "incomplete", stageID: "stage",
            noteIDs: [groupID: note.id], noteSHA256ByItemID: [groupID: wrongDigest], vaultIDs: [:], videoIDs: [:], presetIDs: [:],
            resourceSHA256: [:], independentGroupIDs: [], completedGroups: [], groupPhases: [groupID: "note_promoted"], updatedAt: Date())
        try JSONEncoder().encode(journal).write(to: journals.appendingPathComponent(transactionID + ".json"))
        try LibraryBackupTransactionGate.createPendingMarker(noteID: note.id, transactionID: transactionID,
            groupID: groupID, expectedNoteSHA256: wrongDigest, expectedPDFSHA256: nil, in: notesDirectory, journalRoot: journals)
        XCTAssertThrowsError(try library.rollbackBackupNote(id: note.id, transactionID: transactionID, groupID: groupID, journalRoot: journals))
        XCTAssertEqual(try Data(contentsOf: path), original)
    }

    @MainActor
    func testVaultReloadKeepsValidRowsBesideCorruptOversizedAndSymlinkRows() throws {
        let root = try temporaryDirectory(); defer { try? FileManager.default.removeItem(at: root) }
        let vault = VaultLibrary(directory: root, journalRoot: root.appendingPathComponent("journals", isDirectory: true))
        let note = NoteDocument(id: UUID().uuidString.lowercased(), title: "valid vault source")
        try vault.save(note: note, markdown: "valid markdown")
        let validJSON = try XCTUnwrap(FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: nil)
            .first { $0.pathExtension == "json" })
        try Data("{broken".utf8).write(to: root.appendingPathComponent("broken.json"))
        let oversized = root.appendingPathComponent("oversized.json")
        let fd = open(oversized.path, O_WRONLY | O_CREAT | O_EXCL, S_IRUSR | S_IWUSR)
        XCTAssertGreaterThanOrEqual(fd, 0)
        XCTAssertEqual(ftruncate(fd, off_t(LibraryBackupArchive.maxVaultBytes + 128 * 1024 + 1)), 0)
        _ = close(fd)
        let linked = root.appendingPathComponent("linked.json")
        XCTAssertEqual(symlink(validJSON.path, linked.path), 0)

        vault.reload()
        XCTAssertEqual(vault.notes.map(\.id), [note.id], "one corrupt row must not hide valid knowledge entries")
        XCTAssertEqual(vault.listingIssueCount, 3)
        XCTAssertTrue(vault.errorMessage?.contains("其他有效条目仍可用") == true)
        XCTAssertEqual(try Data(contentsOf: root.appendingPathComponent("broken.json")), Data("{broken".utf8),
                       "diagnostics must preserve corrupt user files rather than delete them")
    }

    func testCoverPresetListingKeepsValidRowsBesideCorruptOversizedAndSymlinkRows() throws {
        let root = try temporaryDirectory(); defer { try? FileManager.default.removeItem(at: root) }
        let store = UserCoverPresetStore(directory: root, journalRoot: root.appendingPathComponent("journals", isDirectory: true))
        let png = UIGraphicsImageRenderer(size: CGSize(width: 2, height: 2)).image { context in
            UIColor.systemBlue.setFill(); context.fill(CGRect(x: 0, y: 0, width: 2, height: 2))
        }.pngData()!
        let source = root.appendingPathComponent("source.png"); try png.write(to: source)
        let good = try store.add(name: "valid preset", png: source)
        try FileManager.default.removeItem(at: source)
        let validJSON = root.appendingPathComponent(good.id.uuidString.lowercased() + ".json")
        try Data("{broken".utf8).write(to: root.appendingPathComponent("broken.json"))
        let oversized = root.appendingPathComponent("oversized.json")
        let fd = open(oversized.path, O_WRONLY | O_CREAT | O_EXCL, S_IRUSR | S_IWUSR)
        XCTAssertGreaterThanOrEqual(fd, 0)
        XCTAssertEqual(ftruncate(fd, 64 * 1024 + 1), 0)
        _ = close(fd)
        let linked = root.appendingPathComponent("linked.json")
        XCTAssertEqual(symlink(validJSON.path, linked.path), 0)

        let values = try store.listing()
        XCTAssertEqual(values.map(\.id), [good.id], "one corrupt preset must not hide valid cover presets")
        XCTAssertEqual(store.lastListingIssueCount, 3)
        XCTAssertEqual(try Data(contentsOf: root.appendingPathComponent("broken.json")), Data("{broken".utf8),
                       "listing must not remove corrupt user files")
    }

    func testVaultCanonicalPayloadRequiresExactFieldsAndIntegerTokens() throws {
        let valid = #"{"schema_version":1,"title":"vault","markdown":"text","source_note_id":"source","source_revision_ms":1000,"created_at_ms":2000}"#.data(using: .utf8)!
        XCTAssertNoThrow(try LibraryBackupRestoreCoordinator.validateVaultPayloadJSON(valid))
        let root = try temporaryDirectory(); defer { try? FileManager.default.removeItem(at: root) }
        let file = root.appendingPathComponent("vault.json"); try valid.write(to: file)
        func vaultDescriptor(_ bytes: Data) -> LibraryBackupManifest.Resource {
            .init(resourceID: "r-0123456789abcdef0123456789abcdef", role: "vault_entry_json",
                  mediaType: "application/json", byteLength: Int64(bytes.count), sha256: sha256(bytes),
                  member: "payload/r-0123456789abcdef0123456789abcdef.bin")
        }
        let descriptor = vaultDescriptor(valid)
        let entry = LibraryBackupManifest.Vault(itemID: "v-0123456789abcdef0123456789abcdef", noteItemID: nil,
            sourceState: "source_deleted", sourceNoteID: "source", sourceRevisionMS: 1000, createdAtMS: 2000,
            resourceID: descriptor.resourceID)
        XCTAssertEqual(try LibraryBackupRestoreCoordinator.readVerifiedVaultPayload(at: file, descriptor: descriptor, entry: entry).title, "vault")
        for invalid in [
            #"{"schema_version":1.0,"title":"vault","markdown":"text","source_note_id":"source","source_revision_ms":1000,"created_at_ms":2000}"#,
            #"{"schema_version":1,"title":"vault","markdown":"text","source_note_id":"source","source_revision_ms":1e3,"created_at_ms":2000}"#,
            #"{"schema_version":true,"title":"vault","markdown":"text","source_note_id":"source","source_revision_ms":1000,"created_at_ms":2000}"#,
            #"{"schema_version":1,"title":"vault","markdown":"text","source_note_id":"source","source_revision_ms":1000}"#,
            #"{"schema_version":1,"title":"vault","markdown":"text","source_note_id":"source","source_revision_ms":1000,"created_at_ms":2000,"extra":0}"#,
            #"{"schema_version":1,"title":"vault","markdown":"text","source_note_id":"source","source_revision_ms":1000,"created_at_ms":2000,"created_at_ms":2000}"#
        ] {
            XCTAssertThrowsError(try LibraryBackupRestoreCoordinator.validateVaultPayloadJSON(Data(invalid.utf8)))
        }
        let badVault = #"{"schema_version":1,"title":"vault","markdown":"text","source_note_id":"source","source_revision_ms":1.0,"created_at_ms":2000}"#.data(using: .utf8)!
        try badVault.write(to: file, options: .atomic)
        XCTAssertThrowsError(try LibraryBackupRestoreCoordinator.readVerifiedVaultPayload(at: file, descriptor: vaultDescriptor(badVault), entry: entry))
        // Strict integer token rules apply to manifest/vault metadata, not the note's raw document.
        let note = NoteDocument(title: "fractional timestamp remains valid")
        var fractional = note
        fractional.updatedAt = 1_790_000_000_000.75
        XCTAssertNoThrow(try NoteDocument.decode(fractional.encoded()))
    }

    @MainActor
    func testRestoredApplicationArchiveCanBeRebackedAndRestoredAgain() async throws {
        let repository = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        let archive = repository.appendingPathComponent("../docs/fixtures/library-backup-r1/application-valid/archive/valid-library.zip").standardizedFileURL
        let root = try temporaryDirectory(); defer { try? FileManager.default.removeItem(at: root) }
        func makeStores(_ prefix: String) -> (NoteLibrary, VaultLibrary, RestoredVideoAttachmentStore, UserCoverPresetStore, NoteCoverStore, URL) {
            let base = root.appendingPathComponent(prefix, isDirectory: true)
            let journals = base.appendingPathComponent("journals", isDirectory: true)
            return (NoteLibrary(directory: base.appendingPathComponent("notes", isDirectory: true)),
                    VaultLibrary(directory: base.appendingPathComponent("vault", isDirectory: true), journalRoot: journals),
                    RestoredVideoAttachmentStore(directory: base.appendingPathComponent("videos", isDirectory: true), journalRoot: journals),
                    UserCoverPresetStore(directory: base.appendingPathComponent("presets", isDirectory: true), journalRoot: journals),
                    NoteCoverStore(directory: base.appendingPathComponent("covers", isDirectory: true)), journals)
        }
        let (library, vault, videos, presets, covers, journals) = makeStores("first")
        let first = LibraryBackupRestoreCoordinator(library: library, vault: vault, videoStore: videos,
            presetStore: presets, coverStore: covers, journalRoot: journals)
        let preview = try await first.inspect(archive, stagingRoot: root.appendingPathComponent("stage-first", isDirectory: true))
        try await first.restore(preview)

        let taskVideos = NoteVideoAttachmentStore(directory: root.appendingPathComponent("task-videos", isDirectory: true))
        let snapshotDirectory = root.appendingPathComponent("snapshot", isDirectory: true)
        let snapshot = try await LibraryBackupSnapshot.capture(library: library, vault: vault, videoStore: taskVideos,
            presetStore: presets, restoredVideoStore: videos, coverStore: covers, into: snapshotDirectory)
        try LibraryBackupArchive.validate(manifest: snapshot.manifest)
        let secondArchive = root.appendingPathComponent("ios-rebackup.zip")
        try LibraryBackupArchive.write(manifest: snapshot.manifest, resourceFiles: snapshot.resourceFiles, to: secondArchive)
        let attachment = XCTAttachment(contentsOfFile: secondArchive)
        attachment.name = "application-valid-ios-rebackup.zip"
        attachment.lifetime = .keepAlways
        add(attachment)
        let linkedNoteIDs = Set(snapshot.manifest.notes.map(\.sourceNoteID))
        XCTAssertEqual(snapshot.manifest.videoAttachments.filter { $0.noteItemID != nil }.count, 1)
        XCTAssertEqual(snapshot.manifest.videoAttachments.filter { $0.noteItemID == nil }.map(\.sourceState), ["source_deleted"])
        XCTAssertEqual(snapshot.manifest.videoAttachments.first(where: { $0.noteItemID != nil })?.sourceNoteID, "fixture-note-pdf")
        XCTAssertEqual(snapshot.manifest.vaultEntries.first(where: { $0.sourceNoteID == "fixture-note-ordinary" })?.sourceState, "linked_note")
        XCTAssertEqual(snapshot.manifest.vaultEntries.first(where: { $0.sourceNoteID == "fixture-note-ordinary" })?.noteItemID.flatMap { id in snapshot.manifest.notes.first(where: { $0.itemID == id })?.sourceNoteID }.map(linkedNoteIDs.contains), true)
        XCTAssertEqual(snapshot.manifest.videoAttachments.map(\.sourceRevisionPrecisionMS).sorted(), [1, 1000])
        let firstLinkedVault = try XCTUnwrap(vault.notes.first {
            $0.archiveOrigin == "restored_archive" && $0.archiveSourceNoteID == "fixture-note-ordinary"
        })
        let firstLinkedVaultDescriptor = try XCTUnwrap(snapshot.restoredVaultDescriptors[firstLinkedVault.id])
        let firstLinkedVaultProjection = try MaterialCodec.inspectVaultDescriptor(firstLinkedVaultDescriptor)
        XCTAssertEqual(firstLinkedVaultProjection.sourceNoteID, "fixture-note-ordinary")
        XCTAssertEqual(firstLinkedVaultProjection.ownerLineageID, firstLinkedVault.archiveLinkedNoteID)

        let (library2, vault2, videos2, presets2, covers2, journals2) = makeStores("second")
        let second = LibraryBackupRestoreCoordinator(library: library2, vault: vault2, videoStore: videos2,
            presetStore: presets2, coverStore: covers2, journalRoot: journals2)
        let secondPreview = try await second.inspect(secondArchive, stagingRoot: root.appendingPathComponent("stage-second", isDirectory: true))
        try await second.restore(secondPreview)
        XCTAssertEqual(library2.notes.count, 2)
        XCTAssertEqual(vault2.notes.count, 2)
        let secondVideoValues = try videos2.listing()
        XCTAssertEqual(secondVideoValues.count, 2)
        let linkedRestoredVideo = try XCTUnwrap(secondVideoValues.first { $0.noteID != nil })
        XCTAssertEqual(linkedRestoredVideo.archiveSourceState, "linked_note")
        XCTAssertEqual(linkedRestoredVideo.originKind, "restored_archive")
        XCTAssertNotEqual(linkedRestoredVideo.noteID, linkedRestoredVideo.sourceNoteID,
                          "current copied-note association must stay separate from historical source ID")
        XCTAssertTrue(secondVideoValues.contains { $0.noteID == nil && $0.archiveSourceState == "source_deleted" })
        XCTAssertEqual(Set(secondVideoValues.map(\.sourceRevisionPrecisionMS)), Set([1, 1000]))
        XCTAssertEqual(try presets2.listing().count, 1)

        let secondLinkedVault = try XCTUnwrap(vault2.notes.first {
            $0.archiveOrigin == "restored_archive" && $0.archiveSourceNoteID == "fixture-note-ordinary"
        })
        let secondVaultNoteID = try XCTUnwrap(secondLinkedVault.archiveLinkedNoteID)
        let secondVaultNote = try XCTUnwrap(library2.notes.first { $0.id == secondVaultNoteID })
        let linkedVideoNote = try XCTUnwrap(library2.notes.first { $0.id == linkedRestoredVideo.noteID })
        XCTAssertNotEqual(secondVaultNote.id, linkedVideoNote.id,
                          "the linked Vault and video in the fixture have different source notes")
        try library2.delete(secondVaultNote)
        try library2.delete(linkedVideoNote)
        let afterDelete = try await LibraryBackupSnapshot.capture(library: library2, vault: vault2,
            videoStore: NoteVideoAttachmentStore(directory: root.appendingPathComponent("task-videos-after-delete", isDirectory: true)),
            presetStore: presets2, restoredVideoStore: videos2, coverStore: covers2,
            into: root.appendingPathComponent("snapshot-after-delete", isDirectory: true))
        try LibraryBackupArchive.validate(manifest: afterDelete.manifest)
        let deletedVault = try XCTUnwrap(afterDelete.manifest.vaultEntries.first {
            $0.sourceNoteID == "fixture-note-ordinary"
        })
        XCTAssertEqual(deletedVault.sourceState, "source_deleted")
        XCTAssertNil(deletedVault.noteItemID)
        let deletedVaultDescriptor = try XCTUnwrap(afterDelete.restoredVaultDescriptors[secondLinkedVault.id])
        let deletedVaultProjection = try MaterialCodec.inspectVaultDescriptor(deletedVaultDescriptor)
        XCTAssertEqual(deletedVaultProjection.sourceState, "source_deleted")
        XCTAssertEqual(deletedVaultProjection.ownerLineageID, secondVaultNoteID)
        XCTAssertNil(deletedVaultProjection.sourceLineageID)
        let deletedVideo = try XCTUnwrap(afterDelete.manifest.videoAttachments.first {
            $0.sourceNoteID == linkedRestoredVideo.sourceNoteID
        })
        XCTAssertEqual(deletedVideo.sourceState, "source_deleted")
        XCTAssertNil(deletedVideo.noteItemID)
    }

    @MainActor
    func testResumeReusesJournalIDsCleansPartialOwnedVideoAndSkipsCommittedNote() async throws {
        let repository = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        let archive = repository.appendingPathComponent("../docs/fixtures/library-backup-r1/application-valid/archive/valid-library.zip").standardizedFileURL
        let root = try temporaryDirectory(); defer { try? FileManager.default.removeItem(at: root) }
        let journals = root.appendingPathComponent("journals", isDirectory: true)
        let noteDirectory = root.appendingPathComponent("notes", isDirectory: true)
        let staging = root.appendingPathComponent("staging", isDirectory: true)
        let library = NoteLibrary(directory: noteDirectory)
        let vault = VaultLibrary(directory: root.appendingPathComponent("vault", isDirectory: true), journalRoot: journals)
        let videos = RestoredVideoAttachmentStore(directory: root.appendingPathComponent("videos", isDirectory: true), journalRoot: journals)
        let presets = UserCoverPresetStore(directory: root.appendingPathComponent("presets", isDirectory: true), journalRoot: journals)
        let covers = NoteCoverStore(directory: root.appendingPathComponent("covers", isDirectory: true))
        let coordinator = LibraryBackupRestoreCoordinator(library: library, vault: vault, videoStore: videos,
            presetStore: presets, coverStore: covers, journalRoot: journals)
        let preview = try await coordinator.inspect(archive, stagingRoot: staging)
        let manifest = preview.staged.manifest
        let transaction = UUID().uuidString.lowercased()
        let notes = Dictionary(uniqueKeysWithValues: manifest.notes.map { ($0.itemID, UUID().uuidString.lowercased()) })
        let vaultIDs = Dictionary(uniqueKeysWithValues: manifest.vaultEntries.map { ($0.itemID, UUID().uuidString.lowercased()) })
        let videoIDs = Dictionary(uniqueKeysWithValues: manifest.videoAttachments.map { ($0.itemID, UUID().uuidString.lowercased()) })
        let presetIDs = Dictionary(uniqueKeysWithValues: manifest.coverPresets.map { ($0.itemID, UUID().uuidString.lowercased()) })
        var noteHashes = [String: String]()
        for descriptor in manifest.notes {
            var copy = try XCTUnwrap(preview.validatedNotesByItemID[descriptor.itemID]); copy.id = try XCTUnwrap(notes[descriptor.itemID])
            noteHashes[descriptor.itemID] = sha256(try copy.validated().encoded())
        }
        let phases = [manifest.notes[0].itemID: "committed", manifest.notes[1].itemID: "resources_promoted"]
        let journal = RestoreJournal(transactionID: transaction, archiveSHA256: preview.staged.archiveSHA256, status: "incomplete",
            stageID: preview.staged.directory.lastPathComponent, noteIDs: notes, noteSHA256ByItemID: noteHashes,
            vaultIDs: vaultIDs, videoIDs: videoIDs, presetIDs: presetIDs,
            vaultGroupIDsByItemID: Dictionary(uniqueKeysWithValues: manifest.vaultEntries.map { ($0.itemID, $0.noteItemID ?? $0.itemID) }),
            videoGroupIDsByItemID: Dictionary(uniqueKeysWithValues: manifest.videoAttachments.map { ($0.itemID, $0.noteItemID ?? $0.itemID) }),
            presetGroupIDsByItemID: Dictionary(uniqueKeysWithValues: manifest.coverPresets.map { ($0.itemID, $0.itemID) }),
            resourceSHA256: Dictionary(uniqueKeysWithValues: manifest.resources.map { ($0.resourceID, $0.sha256) }),
            independentGroupIDs: (manifest.vaultEntries.filter { $0.noteItemID == nil }.map(\.itemID) +
                manifest.videoAttachments.filter { $0.noteItemID == nil }.map(\.itemID) + manifest.coverPresets.map(\.itemID)).sorted(),
            completedGroups: [manifest.notes[0].itemID], groupPhases: phases, updatedAt: Date())
        try FileManager.default.createDirectory(at: journals, withIntermediateDirectories: false)
        try JSONEncoder().encode(journal).write(to: journals.appendingPathComponent(transaction + ".json"))

        var committed = try XCTUnwrap(preview.validatedNotesByItemID[manifest.notes[0].itemID]); committed.id = try XCTUnwrap(notes[manifest.notes[0].itemID])
        try library.save(committed)
        let firstVault = try XCTUnwrap(manifest.vaultEntries.first { $0.noteItemID == manifest.notes[0].itemID })
        let vaultDescriptor = try XCTUnwrap(manifest.resources.first { $0.resourceID == firstVault.resourceID })
        let payload = try LibraryBackupRestoreCoordinator.readVerifiedVaultPayload(at: preview.staged.directory.appendingPathComponent(firstVault.resourceID + ".bin"), descriptor: vaultDescriptor, entry: firstVault)
        _ = try vault.restoreArchiveEntry(id: try XCTUnwrap(vaultIDs[firstVault.itemID]), title: payload.title, markdown: payload.markdown,
            sourceRevisionMS: payload.sourceRevisionMS, createdAtMS: payload.createdAtMS, sourceNoteID: payload.sourceNoteID,
            transactionID: transaction, groupID: manifest.notes[0].itemID)

        let secondVideo = try XCTUnwrap(manifest.videoAttachments.first { $0.noteItemID == manifest.notes[1].itemID })
        let stagedVideo = preview.staged.directory.appendingPathComponent(secondVideo.resourceID + ".bin")
        _ = try videos.restore(from: stagedVideo, descriptor: secondVideo, newNoteID: try XCTUnwrap(notes[manifest.notes[1].itemID]),
            transactionID: transaction, attachmentID: UUID(uuidString: try XCTUnwrap(videoIDs[secondVideo.itemID])), groupID: manifest.notes[1].itemID)
        XCTAssertEqual(try coordinator.recoveryOptions(for: preview).first?.transactionID, transaction)

        try await coordinator.restore(preview, continuingTransactionID: transaction)
        library.reload(); vault.reload()
        XCTAssertEqual(Set(library.notes.map(\.id)), Set(notes.values))
        XCTAssertEqual(library.notes.count, 2)
        XCTAssertEqual(vault.notes.count, 2)
        let restoredVideos = try videos.listing()
        XCTAssertEqual(restoredVideos.count, 2)
        XCTAssertEqual(Set(restoredVideos.compactMap(\.noteID)), Set([try XCTUnwrap(notes[manifest.notes[1].itemID])]))
        XCTAssertEqual(try presets.listing().count, 1)
        let finished = try XCTUnwrap(LibraryBackupTransactionGate.readJournal(transaction, in: journals))
        XCTAssertEqual(finished.status, "committed")
        XCTAssertEqual(finished.noteIDs, notes)
        XCTAssertEqual(finished.videoIDs, videoIDs)
    }

    @MainActor
    func testRecoveryCountsAndRollsBackOnlySelectedNotesAndUnstartedIndependentGroups() async throws {
        let repository = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        let archive = repository.appendingPathComponent("../docs/fixtures/library-backup-r1/application-valid/archive/valid-library.zip").standardizedFileURL
        let root = try temporaryDirectory(); defer { try? FileManager.default.removeItem(at: root) }
        let journals = root.appendingPathComponent("journals", isDirectory: true)
        let library = NoteLibrary(directory: root.appendingPathComponent("notes", isDirectory: true))
        let vault = VaultLibrary(directory: root.appendingPathComponent("vault", isDirectory: true), journalRoot: journals)
        let videos = RestoredVideoAttachmentStore(directory: root.appendingPathComponent("videos", isDirectory: true), journalRoot: journals)
        let presets = UserCoverPresetStore(directory: root.appendingPathComponent("presets", isDirectory: true), journalRoot: journals)
        let coordinator = LibraryBackupRestoreCoordinator(library: library, vault: vault, videoStore: videos,
            presetStore: presets, journalRoot: journals)
        let preview = try await coordinator.inspect(archive, stagingRoot: root.appendingPathComponent("staging", isDirectory: true))
        let manifest = preview.staged.manifest
        let selectedNote = try XCTUnwrap(manifest.notes.first)
        let committedNoteID = UUID().uuidString.lowercased()
        var committedNote = try XCTUnwrap(preview.validatedNotesByItemID[selectedNote.itemID])
        committedNote.id = committedNoteID
        let committedNoteDigest = sha256(try committedNote.validated().encoded())
        try library.save(committedNote)
        let selected = Set([selectedNote.itemID])
        let relevantVault = manifest.vaultEntries.filter { $0.noteItemID == nil || selected.contains($0.noteItemID!) }
        let relevantVideos = manifest.videoAttachments.filter { $0.noteItemID == nil || selected.contains($0.noteItemID!) }
        let noteIDs = [selectedNote.itemID: committedNoteID]
        let vaultIDs = Dictionary(uniqueKeysWithValues: relevantVault.map { ($0.itemID, UUID().uuidString.lowercased()) })
        let videoIDs = Dictionary(uniqueKeysWithValues: relevantVideos.map { ($0.itemID, UUID().uuidString.lowercased()) })
        let presetIDs = Dictionary(uniqueKeysWithValues: manifest.coverPresets.map { ($0.itemID, UUID().uuidString.lowercased()) })
        let independent = (manifest.vaultEntries.filter { $0.noteItemID == nil }.map(\.itemID) +
            manifest.videoAttachments.filter { $0.noteItemID == nil }.map(\.itemID) + manifest.coverPresets.map(\.itemID)).sorted()
        let transaction = UUID().uuidString.lowercased()
        let journal = RestoreJournal(transactionID: transaction, archiveSHA256: preview.staged.archiveSHA256,
            status: "incomplete", stageID: preview.staged.directory.lastPathComponent,
            noteIDs: noteIDs, noteSHA256ByItemID: [selectedNote.itemID: committedNoteDigest],
            vaultIDs: vaultIDs, videoIDs: videoIDs, presetIDs: presetIDs,
            vaultGroupIDsByItemID: Dictionary(uniqueKeysWithValues: relevantVault.map { ($0.itemID, $0.noteItemID ?? $0.itemID) }),
            videoGroupIDsByItemID: Dictionary(uniqueKeysWithValues: relevantVideos.map { ($0.itemID, $0.noteItemID ?? $0.itemID) }),
            presetGroupIDsByItemID: Dictionary(uniqueKeysWithValues: manifest.coverPresets.map { ($0.itemID, $0.itemID) }),
            resourceSHA256: Dictionary(uniqueKeysWithValues: manifest.resources.map { ($0.resourceID, $0.sha256) }),
            independentGroupIDs: independent, completedGroups: [selectedNote.itemID],
            groupPhases: [selectedNote.itemID: "committed"], updatedAt: Date())
        try FileManager.default.createDirectory(at: journals, withIntermediateDirectories: false)
        try JSONEncoder().encode(journal).write(to: journals.appendingPathComponent(transaction + ".json"))

        let option = try XCTUnwrap(coordinator.recoveryOptions(for: preview).first)
        XCTAssertEqual(option.completedGroupCount, 1)
        XCTAssertEqual(option.pendingGroupCount, 3, "Standalone vault, video, and preset groups count before they start")
        try await coordinator.rollbackIncompleteRestore(transaction, for: preview)

        library.reload(); vault.reload()
        XCTAssertEqual(library.notes.map(\.id), [committedNoteID], "Committed selected note remains untouched")
        XCTAssertTrue(vault.notes.isEmpty)
        XCTAssertTrue(try videos.listing().isEmpty)
        XCTAssertTrue(try presets.listing().isEmpty)
        let rolledBack = try XCTUnwrap(LibraryBackupTransactionGate.readJournal(transaction, in: journals))
        XCTAssertEqual(rolledBack.completedGroups, [selectedNote.itemID])
        XCTAssertEqual(rolledBack.groupPhases[selectedNote.itemID], "committed")
        XCTAssertTrue(independent.allSatisfy { rolledBack.groupPhases[$0] == "rolled_back" })
        XCTAssertEqual(rolledBack.status, "rolled_back", "Cleanup is terminal even when committed groups remain preserved")
        XCTAssertTrue(try coordinator.recoveryOptions(for: preview).isEmpty, "A cleaned transaction must not remain resumable")
        do {
            try await coordinator.restore(preview, continuingTransactionID: transaction)
            XCTFail("A rolled-back transaction must not be resumed; a fresh restore creates a new transaction")
        } catch LibraryBackupError.transaction(_) { }
        catch { XCTFail("Unexpected error while rejecting a rolled-back transaction: \(error)") }
        XCTAssertEqual(Set(rolledBack.noteIDs.keys), selected, "Unselected note IDs are not authorized for cleanup")
    }

    func testJournalCompletionMarkerAndPhaseMustBeCommittedTogether() throws {
        let group = "i-0123456789abcdef0123456789abcdef"
        let journal = RestoreJournal(transactionID: UUID().uuidString.lowercased(), archiveSHA256: String(repeating: "a", count: 64),
            status: "incomplete", stageID: "stage", noteIDs: [group: UUID().uuidString.lowercased()],
            noteSHA256ByItemID: [group: String(repeating: "b", count: 64)], vaultIDs: [:], videoIDs: [:], presetIDs: [:],
            resourceSHA256: [:], independentGroupIDs: [], completedGroups: [group], groupPhases: [group: "index_committed"], updatedAt: Date())
        XCTAssertFalse(LibraryBackupRestoreCoordinator.isGroupCompleted(group, in: journal))
        XCTAssertThrowsError(try LibraryBackupRestoreCoordinator.validateJournalInternalConsistency(journal))

        var committed = journal
        committed.completedGroups = []
        committed.groupPhases[group] = "committed"
        XCTAssertFalse(LibraryBackupRestoreCoordinator.isGroupCompleted(group, in: committed))
        XCTAssertThrowsError(try LibraryBackupRestoreCoordinator.validateJournalInternalConsistency(committed))

        committed.completedGroups = [group]
        XCTAssertTrue(LibraryBackupRestoreCoordinator.isGroupCompleted(group, in: committed))
        XCTAssertNoThrow(try LibraryBackupRestoreCoordinator.validateJournalInternalConsistency(committed))
    }

    func testNotePayloadRejectsDuplicateKeysButPreservesFractionalDocumentTimes() throws {
        var note = NoteDocument(title: "fractional note time")
        note.updatedAt = 1_790_000_000_000.75
        let valid = try note.encoded()
        let decoded = try LibraryBackupRestoreCoordinator.decodeBackupNote(valid)
        XCTAssertEqual(decoded.updatedAt, note.updatedAt)
        let text = String(decoding: valid, as: UTF8.self)
        let duplicate = Data(text.replacingOccurrences(of: "\"title\":", with: "\"title\":\"shadow\",\"title\":", options: [], range: text.range(of: "\"title\":")).utf8)
        XCTAssertThrowsError(try LibraryBackupRestoreCoordinator.decodeBackupNote(duplicate))
    }

    @MainActor
    func testApplicationValidArchiveRestoresNotesAndOfflineMaterials() async throws {
        let repository = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        let archive = repository.appendingPathComponent("../docs/fixtures/library-backup-r1/application-valid/archive/valid-library.zip").standardizedFileURL
        XCTAssertEqual(sha256(try Data(contentsOf: archive)), "82d7bb7794e5c56d4b3ec55a498000ba6bb3e365795c467616ea553ada518abe")
        let root = try temporaryDirectory(); defer { try? FileManager.default.removeItem(at: root) }
        let journalRoot = root.appendingPathComponent("journals", isDirectory: true)
        let library = NoteLibrary(directory: root.appendingPathComponent("notes", isDirectory: true))
        let vault = VaultLibrary(directory: root.appendingPathComponent("vault", isDirectory: true), journalRoot: journalRoot)
        let videoStore = RestoredVideoAttachmentStore(directory: root.appendingPathComponent("videos", isDirectory: true), journalRoot: journalRoot)
        let presetStore = UserCoverPresetStore(directory: root.appendingPathComponent("presets", isDirectory: true), journalRoot: journalRoot)
        let coordinator = LibraryBackupRestoreCoordinator(library: library, vault: vault, videoStore: videoStore,
                                                           presetStore: presetStore, journalRoot: journalRoot)
        let preview = try await coordinator.inspect(archive, stagingRoot: root.appendingPathComponent("staging", isDirectory: true))
        XCTAssertEqual(preview.noteCount, 2)
        XCTAssertEqual(preview.videoCount, 2)
        try await coordinator.restore(preview)
        library.reload(); vault.reload()
        XCTAssertEqual(library.notes.count, 2)
        XCTAssertEqual(vault.notes.count, 2)
        let videos = try videoStore.listing()
        XCTAssertEqual(videos.count, 2)
        XCTAssertEqual(videos.filter { $0.noteID != nil }.count, 1)
        XCTAssertEqual(videos.filter { $0.noteID == nil }.count, 1)
        for video in videos {
            let file = try videoStore.fileURL(for: video)
            XCTAssertEqual(try LibraryBackupArchive.hashFile(file).sha256, "801cd47ebc1a1eb31c4c398374ec60fb1dc5f8dfe23665d67445a340732e6218")
        }
        XCTAssertEqual(try presetStore.listing().count, 1)
    }

    @MainActor
    func testDamagedVideoAndOrphanFilesDoNotHideOrBlockOtherRestoredMaterials() async throws {
        let repository = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        let archive = repository.appendingPathComponent("../docs/fixtures/library-backup-r1/application-valid/archive/valid-library.zip").standardizedFileURL
        let root = try temporaryDirectory(); defer { try? FileManager.default.removeItem(at: root) }
        let journals = root.appendingPathComponent("journals", isDirectory: true)
        let videosRoot = root.appendingPathComponent("videos", isDirectory: true)
        let videoStore = RestoredVideoAttachmentStore(directory: videosRoot, journalRoot: journals)
        let library = NoteLibrary(directory: root.appendingPathComponent("notes", isDirectory: true))
        let vault = VaultLibrary(directory: root.appendingPathComponent("vault", isDirectory: true), journalRoot: journals)
        let presets = UserCoverPresetStore(directory: root.appendingPathComponent("presets", isDirectory: true), journalRoot: journals)
        let coordinator = LibraryBackupRestoreCoordinator(library: library, vault: vault, videoStore: videoStore,
            presetStore: presets, journalRoot: journals)
        let preview = try await coordinator.inspect(archive, stagingRoot: root.appendingPathComponent("staging", isDirectory: true))
        try await coordinator.restore(preview)
        let restored = try videoStore.listing()
        XCTAssertEqual(restored.count, 2)
        let damaged = try XCTUnwrap(restored.first)
        let valid = try XCTUnwrap(restored.first { $0.id != damaged.id })
        try Data("damaged bytes".utf8).write(to: videosRoot.appendingPathComponent("\(damaged.id.uuidString.lowercased()).mp4"), options: .atomic)
        try Data("not metadata".utf8).write(to: videosRoot.appendingPathComponent("malformed.json"), options: .atomic)
        try Data("orphan bytes".utf8).write(to: videosRoot.appendingPathComponent("orphan.mp4"), options: .atomic)

        let visible = try videoStore.listing()
        XCTAssertEqual(Set(visible.map(\.id)), Set(restored.map(\.id)))
        XCTAssertEqual(videoStore.lastListingIssueCount, 2, "Malformed metadata and an unknown orphan are reported without hiding valid records")
        XCTAssertNoThrow(try videoStore.fileURL(for: valid))
        XCTAssertThrowsError(try videoStore.fileURL(for: damaged))
        try videoStore.remove(valid)
        XCTAssertEqual(try videoStore.listing().count, 1)
        try videoStore.remove(damaged)
        XCTAssertTrue(try videoStore.listing().isEmpty)
        XCTAssertTrue(FileManager.default.fileExists(atPath: videosRoot.appendingPathComponent("orphan.mp4").path),
                      "Unknown files are reported and never silently deleted")
    }

    func testRealZIPRejectsForeignVersionEvenWhenModelHasFixedDefaults() throws {
        let root = try temporaryDirectory(); defer { try? FileManager.default.removeItem(at: root) }
        let payload = Data("{}".utf8)
        let resource = descriptor("r-0123456789abcdef0123456789abcdef", payload)
        let note = LibraryBackupManifest.Note(itemID: "i-0123456789abcdef0123456789abcdef", sourceNoteID: "source",
            sourceRevisionMS: 1, schemaVersion: 8, noteResourceID: resource.resourceID, pdfResourceID: nil,
            coverResourceID: nil, vaultEntryIDs: [], videoAttachmentIDs: [])
        let manifest = LibraryBackupManifest(createdAtMS: 1, producer: ["platform": "ios", "app_version": "test"], notes: [note], vaultEntries: [],
            videoAttachments: [], coverPresets: [], resources: [resource])
        let source = root.appendingPathComponent("payload.bin"); try payload.write(to: source)
        let archiveURL = root.appendingPathComponent("good.zip")
        try LibraryBackupArchive.write(manifest: manifest, resourceFiles: [resource.resourceID: source], to: archiveURL)
        var bytes = try Data(contentsOf: archiveURL)
        let needle = Data("\"format_version\":1".utf8)
        let versionRange = try XCTUnwrap(bytes.range(of: needle))
        bytes[versionRange.upperBound - 1] = UInt8(ascii: "9")
        let crc = payloadCRC(bytes[versionRange.lowerBound..<versionRange.upperBound - 1] + Data([UInt8(ascii: "9")]))
        func put32(_ value: UInt32, at offset: Int) { for index in 0..<4 { bytes[offset + index] = UInt8((value >> (8 * index)) & 0xff) } }
        let nameLength = Int(bytes[26]) | Int(bytes[27]) << 8
        let extraLength = Int(bytes[28]) | Int(bytes[29]) << 8
        let manifestStart = 30 + nameLength + extraLength
        let manifestSize = Int(bytes[18]) | Int(bytes[19]) << 8 | Int(bytes[20]) << 16 | Int(bytes[21]) << 24
        let descriptorStart = manifestStart + manifestSize
        put32(crc, at: descriptorStart + 4)
        var central = 0
        while central + 46 <= bytes.count {
            if bytes[central] == 0x50 && bytes[central + 1] == 0x4b && bytes[central + 2] == 0x01 && bytes[central + 3] == 0x02 {
                let len = Int(bytes[central + 28]) | Int(bytes[central + 29]) << 8
                if String(decoding: bytes[(central + 46)..<(central + 46 + len)], as: UTF8.self) == "manifest.json" {
                    put32(crc, at: central + 16); break
                }
                let extra = Int(bytes[central + 30]) | Int(bytes[central + 31]) << 8
                let comment = Int(bytes[central + 32]) | Int(bytes[central + 33]) << 8
                central += 46 + len + extra + comment
            } else { central += 1 }
        }
        let corrupted = root.appendingPathComponent("foreign-version.zip"); try bytes.write(to: corrupted)
        XCTAssertThrowsError(try LibraryBackupArchive.stage(from: corrupted, into: root.appendingPathComponent("stage", isDirectory: true)))
    }

    private func payloadCRC(_ data: Data) -> UInt32 {
        data.withUnsafeBytes { raw in
            UInt32(truncatingIfNeeded: crc32(0, raw.bindMemory(to: Bytef.self).baseAddress, uInt(data.count)))
        }
    }

    func testDuplicateSourceNoteIDsRemainSeparateGroupsAndReferencesAreBidirectional() throws {
        let firstBytes = Data("{\"note\":1}".utf8)
        let secondBytes = Data("{\"note\":2}".utf8)
        let firstResource = descriptor("r-0123456789abcdef0123456789abcdef", firstBytes)
        let secondResource = descriptor("r-1123456789abcdef0123456789abcdef", secondBytes)
        let sourceID = "same-source-id"
        let first = LibraryBackupManifest.Note(itemID: "i-0123456789abcdef0123456789abcdef", sourceNoteID: sourceID,
            sourceRevisionMS: 1, schemaVersion: 8, noteResourceID: firstResource.resourceID, pdfResourceID: nil,
            coverResourceID: nil, vaultEntryIDs: [], videoAttachmentIDs: [])
        let second = LibraryBackupManifest.Note(itemID: "i-1123456789abcdef0123456789abcdef", sourceNoteID: sourceID,
            sourceRevisionMS: 2, schemaVersion: 8, noteResourceID: secondResource.resourceID, pdfResourceID: nil,
            coverResourceID: nil, vaultEntryIDs: [], videoAttachmentIDs: [])
        let manifest = LibraryBackupManifest(createdAtMS: 1, producer: ["platform": "ios", "app_version": "test"], notes: [first, second], vaultEntries: [],
            videoAttachments: [], coverPresets: [], resources: [firstResource, secondResource])
        XCTAssertNoThrow(try LibraryBackupArchive.validate(manifest: manifest))

        let bad = LibraryBackupManifest.Note(itemID: first.itemID, sourceNoteID: sourceID, sourceRevisionMS: 1,
            schemaVersion: 8, noteResourceID: firstResource.resourceID, pdfResourceID: nil, coverResourceID: nil,
            vaultEntryIDs: ["v-0123456789abcdef0123456789abcdef"], videoAttachmentIDs: [])
        let vaultResource = descriptor("r-2123456789abcdef0123456789abcdef", Data("{}".utf8))
        let vault = LibraryBackupManifest.Vault(itemID: "v-0123456789abcdef0123456789abcdef", noteItemID: first.itemID,
            sourceState: "linked_note", sourceNoteID: sourceID, sourceRevisionMS: 1, createdAtMS: 1,
            resourceID: vaultResource.resourceID)
        let mismatched = LibraryBackupManifest(createdAtMS: 1, producer: ["platform": "ios", "app_version": "test"], notes: [bad], vaultEntries: [vault],
            videoAttachments: [], coverPresets: [], resources: [firstResource, vaultResource])
        XCTAssertThrowsError(try LibraryBackupArchive.validate(manifest: mismatched))
    }

    func testStagedResourceMutationIsRejectedBeforeRestorePromotion() async throws {
        let root = try temporaryDirectory(); defer { try? FileManager.default.removeItem(at: root) }
        let bytes = Data("staged-resource".utf8)
        let resource = descriptor("r-0123456789abcdef0123456789abcdef", bytes)
        let note = LibraryBackupManifest.Note(itemID: "i-0123456789abcdef0123456789abcdef", sourceNoteID: "source",
            sourceRevisionMS: 1, schemaVersion: 8, noteResourceID: resource.resourceID, pdfResourceID: nil,
            coverResourceID: nil, vaultEntryIDs: [], videoAttachmentIDs: [])
        let manifest = LibraryBackupManifest(createdAtMS: 1, producer: ["platform": "ios", "app_version": "test"], notes: [note], vaultEntries: [],
            videoAttachments: [], coverPresets: [], resources: [resource])
        let file = root.appendingPathComponent("payload.bin")
        try bytes.write(to: file)
        let archive = root.appendingPathComponent("fixture.zip")
        try LibraryBackupArchive.write(manifest: manifest, resourceFiles: [resource.resourceID: file], to: archive)
        let stage = root.appendingPathComponent("stage", isDirectory: true)
        let staged = try LibraryBackupArchive.stage(from: archive, into: stage)
        let stagedFile = stage.appendingPathComponent(resource.resourceID + ".bin")
        try Data("tampered".utf8).write(to: stagedFile)
        do {
            try await LibraryBackupRestoreCoordinator.verifyGroupResources([resource.resourceID], manifest: staged.manifest, directory: stage)
            XCTFail("modified preview resource must not be promoted")
        } catch LibraryBackupError.sourceChanged {
            // The staged bytes no longer match the descriptor shown in the preview.
        }
    }

    func testArchivePayloadByteTamperingFailsDigestValidation() throws {
        let root = try temporaryDirectory(); defer { try? FileManager.default.removeItem(at: root) }
        let bytes = Data("payload-digest-check".utf8)
        let resource = descriptor("r-0123456789abcdef0123456789abcdef", bytes)
        let note = LibraryBackupManifest.Note(itemID: "i-0123456789abcdef0123456789abcdef", sourceNoteID: "source",
            sourceRevisionMS: 1, schemaVersion: 8, noteResourceID: resource.resourceID, pdfResourceID: nil,
            coverResourceID: nil, vaultEntryIDs: [], videoAttachmentIDs: [])
        let manifest = LibraryBackupManifest(createdAtMS: 1, producer: ["platform": "ios", "app_version": "test"], notes: [note], vaultEntries: [],
            videoAttachments: [], coverPresets: [], resources: [resource])
        let source = root.appendingPathComponent("payload.bin"); try bytes.write(to: source)
        let archive = root.appendingPathComponent("original.zip")
        try LibraryBackupArchive.write(manifest: manifest, resourceFiles: [resource.resourceID: source], to: archive)
        var encoded = try Data(contentsOf: archive)
        let range = try XCTUnwrap(encoded.range(of: bytes))
        encoded[range.lowerBound] ^= 0x01
        let corrupted = root.appendingPathComponent("corrupted.zip"); try encoded.write(to: corrupted)
        XCTAssertThrowsError(try LibraryBackupArchive.stage(from: corrupted, into: root.appendingPathComponent("bad-stage", isDirectory: true)))
    }

    func testVideoSourceRevisionPrecisionIsRequiredAndNormalized() throws {
        let payload = Data([0, 1, 2, 3])
        let id = "r-0123456789abcdef0123456789abcdef"
        let sha = SHA256.hash(data: payload).map { String(format: "%02x", $0) }.joined()
        let resource = LibraryBackupManifest.Resource(resourceID: id, role: "video_attachment_mp4", mediaType: "video/mp4",
            byteLength: 4, sha256: sha, member: "payload/\(id).bin")
        let connection = LibraryBackupManifest.ConnectionProvenance(connectionID: nil, revision: nil, kind: nil, transport: nil,
            bridgeID: nil, instanceID: nil, certificateSHA256: nil)
        let item = LibraryBackupManifest.Video(itemID: "a-0123456789abcdef0123456789abcdef", noteItemID: nil, sourceState: "independent",
            originKind: "computer_task", sourceNoteID: "deleted-source", sourceRevisionMS: 1_790_000_000_000,
            sourceRevisionPrecisionMS: 1000, sourceBundleSHA256: String(repeating: "a", count: 64), taskPayloadSHA256: nil,
            digestKind: "source_snapshot_only", offlineState: "verified_local_copy", taskID: nil, remoteTaskID: nil, connection: connection,
            artifactID: nil, displayName: "local.mp4", mediaType: "video/mp4", byteLength: 4, sha256: sha, createdAtMS: 1, resourceID: id)
        let manifest = LibraryBackupManifest(createdAtMS: 1, producer: ["platform": "ios", "app_version": "test"], notes: [], vaultEntries: [], videoAttachments: [item], coverPresets: [], resources: [resource])
        XCTAssertNoThrow(try LibraryBackupArchive.validate(manifest: manifest))
        let unaligned = LibraryBackupManifest.Video(itemID: item.itemID, noteItemID: nil, sourceState: item.sourceState, originKind: item.originKind,
            sourceNoteID: item.sourceNoteID, sourceRevisionMS: 1, sourceRevisionPrecisionMS: 1000, sourceBundleSHA256: item.sourceBundleSHA256,
            taskPayloadSHA256: nil, digestKind: item.digestKind, offlineState: item.offlineState, taskID: nil, remoteTaskID: nil,
            connection: connection, artifactID: nil, displayName: item.displayName, mediaType: item.mediaType, byteLength: 4,
            sha256: sha, createdAtMS: 1, resourceID: id)
        let bad = LibraryBackupManifest(createdAtMS: 1, producer: ["platform": "ios", "app_version": "test"], notes: [], vaultEntries: [], videoAttachments: [unaligned], coverPresets: [], resources: [resource])
        XCTAssertThrowsError(try LibraryBackupArchive.validate(manifest: bad))
    }
}
