import XCTest
import CryptoKit
import zlib
import Darwin
import UIKit
@testable import PadNote

private actor RestorePromotionPause {
    private var releaseWaiter: CheckedContinuation<Void, Never>?
    private var reached = false
    private var didPause = false
    private var releaseRequested = false

    func pause() async {
        guard !didPause else { return }
        didPause = true
        reached = true
        guard !releaseRequested else { return }
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            releaseWaiter = continuation
        }
    }

    func waitUntilReached() async throws {
        let deadline = DispatchTime.now().uptimeNanoseconds + 30_000_000_000
        while !reached {
            guard DispatchTime.now().uptimeNanoseconds < deadline else { throw WaitError.timedOut }
            try await Task.sleep(nanoseconds: 10_000_000)
        }
    }

    func release() {
        releaseRequested = true
        releaseWaiter?.resume()
        releaseWaiter = nil
    }

    enum WaitError: Error { case timedOut }
}

final class LibraryBackupArchiveTests: XCTestCase {
    @MainActor
    func testGroupedVideoAttachmentSurvivesBackupRestoreAndRebackup() async throws {
        let root = try temporaryDirectory(); defer { try? FileManager.default.removeItem(at: root) }
        func groupMemberID(note: String, key: String) -> String {
            var bytes = Array(SHA256.hash(data: Data("padnote-group-member-v1|\(note)|\(key)".utf8)).prefix(16))
            bytes[6] = (bytes[6] & 0x0f) | 0x50
            bytes[8] = (bytes[8] & 0x3f) | 0x80
            let value = UUID(uuid: (bytes[0], bytes[1], bytes[2], bytes[3], bytes[4], bytes[5], bytes[6], bytes[7],
                                    bytes[8], bytes[9], bytes[10], bytes[11], bytes[12], bytes[13], bytes[14], bytes[15]))
            return value.uuidString.lowercased()
        }
        func makeStores(_ name: String) -> (NoteLibrary, VaultLibrary, NoteVideoAttachmentStore, RestoredVideoAttachmentStore, UserCoverPresetStore, NoteCoverStore, URL) {
            let base = root.appendingPathComponent(name, isDirectory: true)
            let journals = base.appendingPathComponent("journals", isDirectory: true)
            return (NoteLibrary(directory: base.appendingPathComponent("notes", isDirectory: true)),
                    VaultLibrary(directory: base.appendingPathComponent("vault", isDirectory: true), journalRoot: journals),
                    NoteVideoAttachmentStore(directory: base.appendingPathComponent("task-videos", isDirectory: true)),
                    RestoredVideoAttachmentStore(directory: base.appendingPathComponent("restored-videos", isDirectory: true), journalRoot: journals),
                    UserCoverPresetStore(directory: base.appendingPathComponent("presets", isDirectory: true), journalRoot: journals),
                    NoteCoverStore(directory: base.appendingPathComponent("covers", isDirectory: true)), journals)
        }
        let (library, vault, taskVideos, restored, presets, covers, journals) = makeStores("first")
        let note = NoteDocument(title: "grouped video backup")
        try library.save(note)
        let localID = try NoteGroupFacade.foundationUUID(note.id)
        let associationURL = root.appendingPathComponent("association.json")
        try JSONSerialization.data(withJSONObject: ["schemaVersion": 1, "localNoteID": localID,
            "legacyNoteID": note.id, "members": [[String: Any]]()], options: [.sortedKeys]).write(to: associationURL)
        let groupStore = try NoteGroupStore(rootURL: root.appendingPathComponent("first/note-groups", isDirectory: true))
        _ = try groupStore.commit(localNoteID: localID, lineageID: localID, expected: nil, members: [
            .init(id: localID, role: .body, mediaType: "application/json",
                  sourceURL: root.appendingPathComponent("first/notes/\(note.id).json")),
            .init(id: groupMemberID(note: localID, key: "association-index"), role: .association,
                  mediaType: "application/json", sourceURL: associationURL)
        ])
        let session = try library.openEditorSession(noteID: note.id)
        let stage = root.appendingPathComponent("verified.mp4")
        let media = Data([0, 0, 0, 24, 0x66, 0x74, 0x79, 0x70, 0x69, 0x73, 0x6f, 0x6d, 1, 2, 3, 4])
        try media.write(to: stage)
        let digest = sha256(media)
        let artifact = AgentTaskArtifact(id: "archive-video", name: "archive-video.mp4", mediaType: "video/mp4",
                                         sizeBytes: media.count, sha256: digest)
        let profile = AgentConnectionProfile(id: UUID(uuidString: "33333333-3333-4333-8333-333333333333")!,
            name: "archive video", kind: .builtinVideo, endpoint: "https://video.fixture", transport: .bridge,
            bridgeID: "bridge", instanceID: "instance", certSHA256: String(repeating: "d", count: 64), revision: 1)
        let stagedStore = NoteVideoAttachmentStore(directory: root.appendingPathComponent("staged-sidecars", isDirectory: true))
        let attachment = try stagedStore.associate(noteID: note.id, taskID: UUID(), remoteTaskID: "remote-archive",
            sourceRevision: 1, sourceSnapshotSHA256: String(repeating: "e", count: 64),
            connection: AgentTaskConnectionIdentity(profile: profile), artifact: artifact, verifiedFile: stage)
        let staged = try XCTUnwrap(stagedStore.archiveRecords().records.first { $0.attachment == attachment })
        _ = try library.publishGroupedVideo(attachment, stagedVideoURL: staged.fileURL,
                                            stagedMetadataURL: staged.metadataURL, basedOn: session)

        let firstSnapshot = try await LibraryBackupSnapshot.capture(library: library, vault: vault, videoStore: taskVideos,
            presetStore: presets, restoredVideoStore: restored, coverStore: covers,
            into: root.appendingPathComponent("snapshot-one", isDirectory: true), includeLegacySourceURLs: true)
        let firstVideo = try XCTUnwrap(firstSnapshot.manifest.videoAttachments.first)
        XCTAssertEqual(firstVideo.noteItemID, firstSnapshot.manifest.notes.first?.itemID)
        XCTAssertEqual(firstSnapshot.manifest.notes.first?.videoAttachmentIDs, [firstVideo.itemID])
        XCTAssertEqual(try Data(contentsOf: XCTUnwrap(firstSnapshot.resourceFiles[firstVideo.resourceID])), media)
        let sourceIdentity = try XCTUnwrap(firstSnapshot.videoSourceIdentities[firstVideo.itemID])
        XCTAssertEqual(try JSONDecoder().decode(NoteVideoAttachment.self,
            from: Data(contentsOf: XCTUnwrap(sourceIdentity.metadataURL))), attachment)
        let archiveOne = root.appendingPathComponent("grouped-video-one.zip")
        try LibraryBackupArchive.write(manifest: firstSnapshot.manifest, resourceFiles: firstSnapshot.resourceFiles, to: archiveOne)

        let (library2, vault2, taskVideos2, restored2, presets2, covers2, journals2) = makeStores("second")
        let restore2 = LibraryBackupRestoreCoordinator(library: library2, vault: vault2, videoStore: restored2,
            presetStore: presets2, coverStore: covers2, journalRoot: journals2)
        let preview2 = try await restore2.inspect(archiveOne, stagingRoot: root.appendingPathComponent("stage-two", isDirectory: true))
        try await restore2.restore(preview2)
        let restoredVideo = try XCTUnwrap(try restored2.listing().first)
        XCTAssertEqual(try Data(contentsOf: restored2.fileURL(for: restoredVideo)), media)
        XCTAssertEqual(restoredVideo.artifactID, attachment.artifactID)
        XCTAssertEqual(restoredVideo.taskID, attachment.taskID.uuidString.lowercased())
        XCTAssertEqual(restoredVideo.sourceNoteID, note.id)
        let restoredMetadataBefore = try Data(contentsOf: restored2.metadataURL(for: restoredVideo))
        let copiedNote = try XCTUnwrap(library2.notes.first)
        let secondMedia = Data([0, 0, 0, 24, 0x66, 0x74, 0x79, 0x70, 0x69, 0x73, 0x6f, 0x6d, 9, 8, 7, 6])
        let secondSource = root.appendingPathComponent("verified-original-after-restore.mp4")
        try secondMedia.write(to: secondSource)
        let secondDigest = sha256(secondMedia)
        let secondArtifact = AgentTaskArtifact(id: "additional-original-video", name: "additional.mp4", mediaType: "video/mp4",
            sizeBytes: secondMedia.count, sha256: secondDigest)
        let secondProfile = AgentConnectionProfile(id: UUID(uuidString: "44444444-4444-4444-8444-444444444444")!,
            name: "second video", kind: .builtinVideo, endpoint: "https://video.fixture", transport: .bridge,
            bridgeID: "bridge-2", instanceID: "instance-2", certSHA256: String(repeating: "f", count: 64), revision: 2)
        let original = try library2.associateLegacyVideo(taskVideos2, noteID: copiedNote.id, taskID: UUID(),
            remoteTaskID: "remote-second", sourceRevision: 2, sourceSnapshotSHA256: String(repeating: "9", count: 64),
            connection: AgentTaskConnectionIdentity(profile: secondProfile), artifact: secondArtifact, verifiedFile: secondSource)
        let originalRecord = try XCTUnwrap(taskVideos2.archiveRecords().records.first { $0.attachment == original })
        let originalMetadataBefore = try Data(contentsOf: originalRecord.metadataURL)
        let adapterStore = try NoteGroupStore(rootURL: root.appendingPathComponent("second/note-groups", isDirectory: true))
        let adapter = NoteGroupLegacyCaptureAdapter(library: library2, vault: vault2, videoStore: taskVideos2,
            restoredVideoStore: restored2, coverStore: covers2, presetStore: presets2, store: adapterStore,
            stagingRoot: root.appendingPathComponent("group-adoption-stage", isDirectory: true))
        let adoptionPreview = try await adapter.preview(noteID: copiedNote.id)
        _ = try await adapter.adopt(adoptionPreview)
        let adoptedSession = try XCTUnwrap(library2.currentGroupSession(noteID: copiedNote.id))
        let adoptedAssociation = try XCTUnwrap(adoptedSession.members.first { $0.record.role == .association })
        let adoptedIndex = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: adoptedAssociation.url)) as? [String: Any])
        let adoptedRows = try XCTUnwrap(adoptedIndex["members"] as? [[String: Any]])
        let adoptedVideoRows = adoptedRows.filter { $0["role"] as? String == NoteGroupMemberRole.video.rawValue }
        XCTAssertEqual(adoptedVideoRows.count, 2)
        for row in adoptedVideoRows {
            let sourceID = try XCTUnwrap(row["sourceID"] as? String)
            XCTAssertEqual(UUID(uuidString: sourceID)?.uuidString, sourceID,
                           "Capture must preserve the source UUID casing from legacy metadata")
            XCTAssertEqual(NoteLibrary.canonicalGroupMemberID(sourceID), sourceID.lowercased(),
                           "Readers compare canonical UUIDs while preserving the witness")
        }
        XCTAssertEqual(try library2.groupedVideoAttachments(basedOn: adoptedSession).map(\.id), [original.id])
        let adoptedRestored = try library2.groupedRestoredVideoMembers(basedOn: adoptedSession)
        XCTAssertEqual(adoptedRestored.map { $0.attachment.id }, [restoredVideo.id])
        let adoptedMetadata = try adoptedSession.members.filter { $0.record.role == .videoMetadata }.map { try Data(contentsOf: $0.url) }
        XCTAssertEqual(Set(adoptedMetadata), Set([originalMetadataBefore, restoredMetadataBefore]),
                       "Adoption must preserve the exact metadata bytes for original and restored attachments")
        let groupedNoteKey = copiedNote.id.lowercased()
        try FileManager.default.removeItem(at: originalRecord.fileURL)
        try FileManager.default.removeItem(at: restored2.fileURL(for: restoredVideo))
        let excludedOriginals = try taskVideos2.archiveRecords(excludingNoteIDs: [groupedNoteKey])
        XCTAssertTrue(excludedOriginals.records.isEmpty)
        XCTAssertEqual(excludedOriginals.unavailableCount, 0,
                       "Grouped task-video sidecars must be excluded before validating their mutable MP4 files")
        XCTAssertTrue(try restored2.listing(excludingNoteIDs: [groupedNoteKey]).isEmpty)
        XCTAssertEqual(restored2.lastListingIssueCount, 0,
                       "Grouped restored-video sidecars must be excluded before validating their mutable MP4 files")
        let snapshotTwo = try await LibraryBackupSnapshot.capture(library: library2, vault: vault2, videoStore: taskVideos2,
            presetStore: presets2, restoredVideoStore: restored2, coverStore: covers2,
            into: root.appendingPathComponent("snapshot-two", isDirectory: true), includeLegacySourceURLs: true)
        XCTAssertEqual(snapshotTwo.manifest.videoAttachments.count, 2)
        let snapshotNote = try XCTUnwrap(snapshotTwo.manifest.notes.first)
        XCTAssertEqual(Set(snapshotTwo.manifest.videoAttachments.compactMap(\.noteItemID)), Set([snapshotNote.itemID]))
        XCTAssertEqual(Set(snapshotNote.videoAttachmentIDs), Set(snapshotTwo.manifest.videoAttachments.map(\.itemID)))
        let snapshotVideoBytes = try snapshotTwo.manifest.videoAttachments.map { video -> Data in
            guard let url = snapshotTwo.resourceFiles[video.resourceID] else { throw LibraryBackupError.sourceChanged }
            return try Data(contentsOf: url)
        }
        XCTAssertEqual(Set(snapshotVideoBytes),
                       Set([media, secondMedia]), "A grouped backup must use each immutable MP4 exactly once and ignore duplicate sidecars")
        XCTAssertEqual(Set(snapshotTwo.manifest.videoAttachments.map(\.originKind)), Set(["computer_task", "restored_archive"]))
        let groupedMetadataAfter = Set(try snapshotTwo.videoSourceIdentities.values.compactMap(\.metadataURL).map { try Data(contentsOf: $0) })
        XCTAssertEqual(groupedMetadataAfter, Set([originalMetadataBefore, restoredMetadataBefore]))
        let archiveTwo = root.appendingPathComponent("grouped-video-two.zip")
        try LibraryBackupArchive.write(manifest: snapshotTwo.manifest, resourceFiles: snapshotTwo.resourceFiles, to: archiveTwo)
        let (library3, vault3, _, restored3, presets3, covers3, journals3) = makeStores("third")
        let restore3 = LibraryBackupRestoreCoordinator(library: library3, vault: vault3, videoStore: restored3,
            presetStore: presets3, coverStore: covers3, journalRoot: journals3)
        let preview3 = try await restore3.inspect(archiveTwo, stagingRoot: root.appendingPathComponent("stage-three", isDirectory: true))
        try await restore3.restore(preview3)
        let thirdVideos = try restored3.listing()
        XCTAssertEqual(thirdVideos.count, 2)
        XCTAssertEqual(Set(try thirdVideos.map { try LibraryBackupArchive.hashFile(restored3.fileURL(for: $0)).sha256 }),
                       Set([digest, secondDigest]))
    }

    @MainActor
    func testGroupedBodyOnlyRevisionWithoutAssociationHasEmptyVideoBackup() async throws {
        let root = try temporaryDirectory(); defer { try? FileManager.default.removeItem(at: root) }
        let notes = root.appendingPathComponent("notes", isDirectory: true)
        let library = NoteLibrary(directory: notes)
        let note = NoteDocument(title: "body only group")
        try library.save(note)
        let localID = try NoteGroupFacade.foundationUUID(note.id)
        let groups = try NoteGroupStore(rootURL: root.appendingPathComponent("note-groups", isDirectory: true))
        _ = try groups.commit(localNoteID: localID, lineageID: localID, expected: nil, members: [
            .init(id: localID, role: .body, mediaType: "application/json", sourceURL: notes.appendingPathComponent("\(note.id).json"))
        ])
        let session = try XCTUnwrap(library.currentGroupSession(noteID: note.id))
        XCTAssertTrue(try library.groupedVideoAttachments(basedOn: session).isEmpty)
        XCTAssertTrue(try library.groupedRestoredVideoMembers(basedOn: session).isEmpty)
        let vault = VaultLibrary(directory: root.appendingPathComponent("vault", isDirectory: true),
                                journalRoot: root.appendingPathComponent("journals", isDirectory: true))
        let snapshot = try await LibraryBackupSnapshot.capture(library: library, vault: vault,
            videoStore: NoteVideoAttachmentStore(directory: root.appendingPathComponent("task-videos", isDirectory: true)),
            presetStore: UserCoverPresetStore(directory: root.appendingPathComponent("presets", isDirectory: true),
                journalRoot: root.appendingPathComponent("journals", isDirectory: true)),
            restoredVideoStore: RestoredVideoAttachmentStore(directory: root.appendingPathComponent("restored-videos", isDirectory: true),
                journalRoot: root.appendingPathComponent("journals", isDirectory: true)),
            coverStore: NoteCoverStore(directory: root.appendingPathComponent("covers", isDirectory: true)),
            into: root.appendingPathComponent("empty-video-snapshot", isDirectory: true))
        XCTAssertTrue(snapshot.manifest.videoAttachments.isEmpty)
        XCTAssertEqual(snapshot.manifest.notes.first?.videoAttachmentIDs, [])
        try LibraryBackupArchive.validate(manifest: snapshot.manifest)
    }

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

    func testFormatV2TypedProfileShapeIsAcceptedAndUnsupportedSchemaRejected() throws {
        let note = NoteDocument(title: "typed v2 copy")
        let payload = try note.encoded()
        let resource = descriptor("r-0123456789abcdef0123456789abcdef", payload)
        let item = LibraryBackupManifest.Note(itemID: "i-0123456789abcdef0123456789abcdef", sourceNoteID: note.id,
            sourceRevisionMS: Int64(floor(note.updatedAt)), schemaVersion: note.schemaVersion, noteResourceID: resource.resourceID,
            pdfResourceID: nil, coverResourceID: nil, vaultEntryIDs: [], videoAttachmentIDs: [])
        var manifest = LibraryBackupManifest(createdAtMS: 1, producer: ["platform": "ios", "app_version": "test"],
            notes: [item], vaultEntries: [], videoAttachments: [], coverPresets: [], resources: [resource])
        manifest.formatVersion = 2
        manifest.updateProfiles = [.init(schemaVersion: 2, noteItemID: item.itemID, sourceNoteID: item.sourceNoteID,
            sourceLineageID: "01234567-89ab-3cde-8fab-0123456789ab", sourceRevisionID: "11234567-89ab-3cde-8fab-0123456789ab",
            groupSHA256: String(repeating: "a", count: 64), bodySHA256: resource.sha256, timestamps: [])]
        let encoded = try JSONEncoder().encode(manifest)
        XCTAssertNoThrow(try LibraryBackupArchive.validateManifestJSON(encoded))
        XCTAssertNoThrow(try LibraryBackupArchive.validate(manifest: manifest), "v2 remains a read/copy format without update authority")
        let object = try XCTUnwrap(JSONSerialization.jsonObject(with: encoded) as? [String: Any])
        var bad = object
        var profiles = try XCTUnwrap(bad["update_profiles"] as? [[String: Any]])
        profiles[0]["schema_version"] = 3
        bad["update_profiles"] = profiles
        let badData = try JSONSerialization.data(withJSONObject: bad)
        XCTAssertThrowsError(try LibraryBackupArchive.validateManifestJSON(badData))
        bad = object
        bad.removeValue(forKey: "update_profiles")
        XCTAssertThrowsError(try LibraryBackupArchive.validateManifestJSON(JSONSerialization.data(withJSONObject: bad)))
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
    func testNativeAndroidV2ArchiveCopiesAndRebacksAllLinkedMaterials() async throws {
        let repository = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        let archive = repository.appendingPathComponent("../docs/fixtures/android-r17-v2/application-valid-v2.padnote-library.zip").standardizedFileURL
        let archiveBytes = try Data(contentsOf: archive)
        XCTAssertEqual(archiveBytes.count, 242621)
        XCTAssertEqual(sha256(archiveBytes), "a9a869a4cdbebec40afc1f40505127d67a23965428a32b17b708d88db2c02bd6")
        let root = try temporaryDirectory(); defer { try? FileManager.default.removeItem(at: root) }
        let staged = try LibraryBackupArchive.stage(from: archive, into: root.appendingPathComponent("stage-android", isDirectory: true))
        let manifest = staged.manifest
        XCTAssertEqual(manifest.formatVersion, 2)
        XCTAssertEqual(manifest.producer["platform"], "android")
        XCTAssertEqual(manifest.notes.count, 1)
        XCTAssertEqual(manifest.vaultEntries.count, 1)
        XCTAssertEqual(manifest.videoAttachments.count, 1)
        XCTAssertEqual(manifest.resources.count, 6)
        XCTAssertEqual(Set(manifest.resources.map(\.role)), Set([
            "note_document", "pdf_original", "assigned_cover_png", "vault_entry_json",
            "vault_storage_markdown", "video_attachment_mp4"
        ]))
        let archivedNote = try XCTUnwrap(manifest.notes.first)
        XCTAssertEqual(archivedNote.schemaVersion, 8)
        XCTAssertEqual(archivedNote.vaultEntryIDs, manifest.vaultEntries.map(\.itemID))
        XCTAssertEqual(archivedNote.videoAttachmentIDs, manifest.videoAttachments.map(\.itemID))
        let profile = try XCTUnwrap(manifest.updateProfiles.first)
        XCTAssertEqual(profile.schemaVersion, 2)
        XCTAssertEqual(profile.noteItemID, archivedNote.itemID)
        XCTAssertEqual(profile.sourceNoteID, archivedNote.sourceNoteID)
        XCTAssertEqual(profile.timestamps.count, 4)
        for row in manifest.resources {
            let bytes = try Data(contentsOf: staged.directory.appendingPathComponent(row.resourceID + ".bin"))
            XCTAssertEqual(Int64(bytes.count), row.byteLength, row.role)
            XCTAssertEqual(sha256(bytes), row.sha256, row.role)
        }
        let noteResource = try XCTUnwrap(manifest.resources.first { $0.resourceID == archivedNote.noteResourceID })
        let noteBytes = try Data(contentsOf: staged.directory.appendingPathComponent(noteResource.resourceID + ".bin"))
        let sourceDocument = try NoteDocument.decode(noteBytes)
        XCTAssertEqual(sourceDocument.title, "R10 Android v2 fixture")
        XCTAssertEqual(sourceDocument.updatedAt, 1_791_424_936_706)
        XCTAssertEqual(sourceDocument.strokes.count, 1)
        XCTAssertEqual(sourceDocument.pageCount, 2)
        let vaultRow = try XCTUnwrap(manifest.vaultEntries.first)
        XCTAssertEqual(vaultRow.noteItemID, archivedNote.itemID)
        let rawStorageID = try XCTUnwrap(vaultRow.sourceStorageResourceID)
        let rawStorageResource = try XCTUnwrap(manifest.resources.first { $0.resourceID == rawStorageID })
        XCTAssertEqual(rawStorageResource.role, "vault_storage_markdown")
        let storageBytes = try Data(contentsOf: staged.directory.appendingPathComponent(rawStorageID + ".bin"))
        XCTAssertTrue(String(decoding: storageBytes, as: UTF8.self).contains("source-modified: 1791424936678"))
        let vaultJSONRow = try XCTUnwrap(manifest.resources.first { $0.resourceID == vaultRow.resourceID })
        let vaultJSONBytes = try Data(contentsOf: staged.directory.appendingPathComponent(vaultJSONRow.resourceID + ".bin"))
        let vaultJSON = try XCTUnwrap(try JSONSerialization.jsonObject(with: vaultJSONBytes) as? [String: Any])
        let archivedVaultMarkdown = try XCTUnwrap(vaultJSON["markdown"] as? String)
        let videoRow = try XCTUnwrap(manifest.videoAttachments.first)
        XCTAssertEqual(videoRow.noteItemID, archivedNote.itemID)
        XCTAssertEqual(videoRow.originKind, "computer_task")
        XCTAssertEqual(videoRow.byteLength, 264800)

        func makeStores(_ prefix: String) -> (NoteLibrary, VaultLibrary, RestoredVideoAttachmentStore, UserCoverPresetStore, NoteCoverStore, URL) {
            let base = root.appendingPathComponent(prefix, isDirectory: true)
            let journals = base.appendingPathComponent("journals", isDirectory: true)
            return (NoteLibrary(directory: base.appendingPathComponent("notes", isDirectory: true)),
                    VaultLibrary(directory: base.appendingPathComponent("vault", isDirectory: true), journalRoot: journals),
                    RestoredVideoAttachmentStore(directory: base.appendingPathComponent("videos", isDirectory: true), journalRoot: journals),
                    UserCoverPresetStore(directory: base.appendingPathComponent("presets", isDirectory: true), journalRoot: journals),
                    NoteCoverStore(directory: base.appendingPathComponent("covers", isDirectory: true)), journals)
        }
        let (library1, vault1, videos1, presets1, covers1, journals1) = makeStores("first-copy")
        let first = LibraryBackupRestoreCoordinator(library: library1, vault: vault1, videoStore: videos1,
            presetStore: presets1, coverStore: covers1, journalRoot: journals1)
        let preview1 = try await first.inspect(archive, stagingRoot: root.appendingPathComponent("inspect-first", isDirectory: true))
        try await first.restore(preview1)
        XCTAssertEqual(library1.notes.count, 1)
        XCTAssertEqual(try videos1.listing().count, 1)
        let restoredVideo1 = try XCTUnwrap(videos1.listing().first)
        XCTAssertEqual(restoredVideo1.archiveSourceState, "linked_note")
        XCTAssertNotNil(restoredVideo1.noteID)
        let restoredNote1 = try XCTUnwrap(library1.notes.first { $0.id == restoredVideo1.noteID })
        XCTAssertEqual(restoredNote1.title, "R10 Android v2 fixture")
        XCTAssertEqual(vault1.notes.count, 1)
        let restoredVault1 = try XCTUnwrap(vault1.notes.first)
        XCTAssertEqual(restoredVault1.archiveSourceState, "linked_note")
        XCTAssertEqual(restoredVault1.archiveLinkedNoteID, restoredNote1.id)
        XCTAssertEqual(restoredVault1.markdown, archivedVaultMarkdown)
        XCTAssertEqual(try presets1.listing().count, 0)

        let snapshot = try await LibraryBackupSnapshot.capture(library: library1, vault: vault1,
            videoStore: NoteVideoAttachmentStore(directory: root.appendingPathComponent("task-videos-1", isDirectory: true)),
            presetStore: presets1, restoredVideoStore: videos1, coverStore: covers1,
            into: root.appendingPathComponent("rebackup", isDirectory: true))
        try LibraryBackupArchive.validate(manifest: snapshot.manifest)
        XCTAssertEqual(snapshot.manifest.notes.count, 1)
        XCTAssertEqual(snapshot.manifest.vaultEntries.count, 1)
        XCTAssertEqual(snapshot.manifest.videoAttachments.count, 1)
        XCTAssertEqual(snapshot.manifest.vaultEntries.first?.sourceState, "linked_note")
        XCTAssertEqual(snapshot.manifest.videoAttachments.first?.sourceState, "linked_note")
        XCTAssertEqual(snapshot.manifest.vaultEntries.first?.noteItemID, snapshot.manifest.notes.first?.itemID)
        XCTAssertEqual(snapshot.manifest.videoAttachments.first?.noteItemID, snapshot.manifest.notes.first?.itemID)
        let rebackedNote = try XCTUnwrap(snapshot.manifest.notes.first)
        let rebackedPDF = try XCTUnwrap(snapshot.manifest.resources.first { $0.resourceID == rebackedNote.pdfResourceID })
        XCTAssertEqual(rebackedPDF.role, "pdf_original")
        let originalPDF = try XCTUnwrap(manifest.resources.first { $0.role == "pdf_original" })
        XCTAssertEqual(rebackedPDF.sha256, originalPDF.sha256)
        let rebackedCover = try XCTUnwrap(snapshot.manifest.resources.first { $0.resourceID == rebackedNote.coverResourceID })
        XCTAssertEqual(rebackedCover.role, "assigned_cover_png")
        let originalCover = try XCTUnwrap(manifest.resources.first { $0.role == "assigned_cover_png" })
        XCTAssertEqual(rebackedCover.sha256, originalCover.sha256)
        let rebackedVideo = try XCTUnwrap(snapshot.manifest.videoAttachments.first)
        XCTAssertEqual(rebackedVideo.sha256, videoRow.sha256)
        let rebackedVault = try XCTUnwrap(snapshot.manifest.resources.first { $0.role == "vault_entry_json" })
        XCTAssertEqual(rebackedVault.role, "vault_entry_json")
        let rebackup = root.appendingPathComponent("android-v2-ios-rebackup.zip")
        try LibraryBackupArchive.write(manifest: snapshot.manifest, resourceFiles: snapshot.resourceFiles, to: rebackup)

        let (library2, vault2, videos2, presets2, covers2, journals2) = makeStores("second-copy")
        let second = LibraryBackupRestoreCoordinator(library: library2, vault: vault2, videoStore: videos2,
            presetStore: presets2, coverStore: covers2, journalRoot: journals2)
        let preview2 = try await second.inspect(rebackup, stagingRoot: root.appendingPathComponent("inspect-second", isDirectory: true))
        try await second.restore(preview2)
        XCTAssertEqual(library2.notes.count, 1)
        XCTAssertEqual(vault2.notes.count, 1)
        XCTAssertEqual(try videos2.listing().count, 1)
        let restoredVideo2 = try XCTUnwrap(videos2.listing().first)
        XCTAssertEqual(restoredVideo2.archiveSourceState, "linked_note")
        let restoredNote2 = try XCTUnwrap(library2.notes.first { $0.id == restoredVideo2.noteID })
        XCTAssertEqual(restoredNote2.title, "R10 Android v2 fixture")
        let restoredVault2 = try XCTUnwrap(vault2.notes.first)
        XCTAssertEqual(restoredVault2.archiveSourceState, "linked_note")
        XCTAssertEqual(restoredVault2.archiveLinkedNoteID, restoredNote2.id)
        XCTAssertEqual(try presets2.listing().count, 0)
    }

    @MainActor
    func testAndroidR53bProductionV2ArchiveStrictlyCopiesAndRebacks() async throws {
        let repository = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        let archive = repository.appendingPathComponent("../docs/fixtures/android-r53b-v2/grouped-current-v2.zip").standardizedFileURL
        let archiveBytes = try Data(contentsOf: archive)
        XCTAssertEqual(archiveBytes.count, 240_715)
        XCTAssertEqual(sha256(archiveBytes), "c924b611a80a2382e091dc26db5304f2e144db5667f054fb7b4ab8e9f067786e")

        let root = try temporaryDirectory(); defer { try? FileManager.default.removeItem(at: root) }
        let staged = try LibraryBackupArchive.stage(from: archive,
            into: root.appendingPathComponent("stage-android-production", isDirectory: true))
        let manifest = staged.manifest
        XCTAssertEqual(manifest.formatVersion, 2)
        XCTAssertEqual(manifest.producer["platform"], "android")
        XCTAssertEqual(manifest.producer["app_version"], "0.18.0-beta.9-debug")
        XCTAssertEqual(manifest.notes.count, 1)
        XCTAssertEqual(manifest.vaultEntries.count, 1)
        XCTAssertEqual(manifest.videoAttachments.count, 1)
        XCTAssertEqual(manifest.resources.count, 6)
        XCTAssertEqual(Set(manifest.resources.map(\.role)), Set([
            "note_document", "pdf_original", "assigned_cover_png", "vault_entry_json",
            "vault_storage_markdown", "video_attachment_mp4"
        ]))

        let archivedNote = try XCTUnwrap(manifest.notes.first)
        let noteResource = try XCTUnwrap(manifest.resources.first { $0.resourceID == archivedNote.noteResourceID })
        let profile = try XCTUnwrap(manifest.updateProfiles.first)
        XCTAssertEqual(profile.schemaVersion, 2)
        XCTAssertEqual(profile.noteItemID, archivedNote.itemID)
        XCTAssertEqual(profile.sourceNoteID, archivedNote.sourceNoteID)
        XCTAssertEqual(profile.sourceLineageID, "d8636532-bd77-381f-8897-3a2b9c0d471a")
        XCTAssertEqual(profile.sourceRevisionID, "db075fe3-ca84-346a-a5df-dff2ac0e42b8")
        XCTAssertEqual(profile.groupSHA256, "ddc5df4fdac4f6891e33e5745854eed80857132243aeec4839bdc26f00073128")
        XCTAssertEqual(profile.bodySHA256, noteResource.sha256)
        XCTAssertEqual(profile.timestamps.count, 3)
        XCTAssertEqual(profile.timestamps.first { $0.pointer == "/updatedAt" }?.kind, "i64")
        XCTAssertEqual(profile.timestamps.first { $0.pointer == "/updatedAt" }?.valueBits, "1791458660714")
        XCTAssertEqual(profile.timestamps.first { $0.pointer == "/strokes/0/createdAt" }?.valueBits, "1700000000001")
        XCTAssertEqual(profile.timestamps.first { $0.pointer == "/strokes/0/points/0/timestamp" }?.valueBits, "1700000000002")

        func resource(_ role: String) throws -> LibraryBackupManifest.Resource {
            try XCTUnwrap(manifest.resources.first { $0.role == role })
        }
        func stagedBytes(_ row: LibraryBackupManifest.Resource) throws -> Data {
            let data = try Data(contentsOf: staged.directory.appendingPathComponent(row.resourceID + ".bin"))
            XCTAssertEqual(Int64(data.count), row.byteLength, row.role)
            XCTAssertEqual(sha256(data), row.sha256, row.role)
            return data
        }
        let sourceDocument = try NoteDocument.decode(stagedBytes(noteResource))
        XCTAssertEqual(sourceDocument.id, archivedNote.sourceNoteID)
        XCTAssertEqual(sourceDocument.title, "old complete target")
        XCTAssertEqual(sourceDocument.updatedAt, 1_791_458_660_714)
        XCTAssertEqual(sourceDocument.pageCount, 2)
        XCTAssertEqual(sourceDocument.pdfPageCount, 1)
        XCTAssertEqual(sourceDocument.strokes.first?.createdAt, 1_700_000_000_001)
        XCTAssertEqual(sourceDocument.strokes.first?.points.first?.timestamp, 1_700_000_000_002)

        let vaultRow = try XCTUnwrap(manifest.vaultEntries.first)
        XCTAssertEqual(vaultRow.noteItemID, archivedNote.itemID)
        XCTAssertEqual(vaultRow.sourceNoteID, archivedNote.sourceNoteID)
        XCTAssertEqual(vaultRow.sourceRevisionMS, 1_791_458_660_213)
        XCTAssertEqual(vaultRow.createdAtMS, 1_791_458_660_358)
        let vaultJSONResource = try XCTUnwrap(manifest.resources.first { $0.resourceID == vaultRow.resourceID })
        let vaultPayload = try LibraryBackupRestoreCoordinator.readVerifiedVaultPayload(
            at: staged.directory.appendingPathComponent(vaultJSONResource.resourceID + ".bin"),
            descriptor: vaultJSONResource, entry: vaultRow)
        XCTAssertEqual(vaultPayload.title, "Update fixture Vault")
        XCTAssertEqual(vaultPayload.markdown, "\n# Update fixture Vault\n\n## 第 1 页\n\nsource markdown\n\n")
        let storageRow = try resource("vault_storage_markdown")
        let storageMarkdown = String(decoding: try stagedBytes(storageRow), as: UTF8.self)
        XCTAssertTrue(storageMarkdown.contains("note-id: \(archivedNote.sourceNoteID)"))
        XCTAssertTrue(storageMarkdown.contains("digitized-epoch: 1791458660358"))
        XCTAssertTrue(storageMarkdown.contains("source-modified: 1791458660213"))

        let videoRow = try XCTUnwrap(manifest.videoAttachments.first)
        XCTAssertEqual(videoRow.noteItemID, archivedNote.itemID)
        XCTAssertEqual(videoRow.sourceNoteID, archivedNote.sourceNoteID)
        XCTAssertEqual(videoRow.byteLength, 264_800)
        XCTAssertEqual(videoRow.sha256, "801cd47ebc1a1eb31c4c398374ec60fb1dc5f8dfe23665d67445a340732e6218")
        let videoBytes = try stagedBytes(resource("video_attachment_mp4"))
        XCTAssertEqual(String(decoding: videoBytes.dropFirst(4).prefix(4), as: UTF8.self), "ftyp")
        XCTAssertEqual(try stagedBytes(resource("pdf_original")).prefix(8), Data("%PDF-1.4".utf8))
        XCTAssertEqual(try stagedBytes(resource("assigned_cover_png")).prefix(8), Data([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]))

        func makeStores(_ prefix: String) -> (NoteLibrary, VaultLibrary, RestoredVideoAttachmentStore, UserCoverPresetStore, NoteCoverStore, URL) {
            let base = root.appendingPathComponent(prefix, isDirectory: true)
            let journals = base.appendingPathComponent("journals", isDirectory: true)
            return (NoteLibrary(directory: base.appendingPathComponent("notes", isDirectory: true)),
                    VaultLibrary(directory: base.appendingPathComponent("vault", isDirectory: true), journalRoot: journals),
                    RestoredVideoAttachmentStore(directory: base.appendingPathComponent("videos", isDirectory: true), journalRoot: journals),
                    UserCoverPresetStore(directory: base.appendingPathComponent("presets", isDirectory: true), journalRoot: journals),
                    NoteCoverStore(directory: base.appendingPathComponent("covers", isDirectory: true)), journals)
        }
        let (library, vault, videos, presets, covers, journals) = makeStores("copy-import")
        let restore = LibraryBackupRestoreCoordinator(library: library, vault: vault, videoStore: videos,
            presetStore: presets, coverStore: covers, journalRoot: journals)
        let preview = try await restore.inspect(archive, stagingRoot: root.appendingPathComponent("inspect-android", isDirectory: true))
        XCTAssertEqual(preview.noteCount, 1)
        XCTAssertEqual(preview.pdfCount, 1)
        XCTAssertEqual(preview.coverCount, 1)
        XCTAssertEqual(preview.vaultCount, 1)
        XCTAssertEqual(preview.videoCount, 1)
        try await restore.restore(preview)

        let importedNote = try XCTUnwrap(library.notes.first)
        XCTAssertNotEqual(importedNote.id, archivedNote.sourceNoteID, "Import maps the source identity to a new local copy")
        XCTAssertEqual(importedNote.title, sourceDocument.title)
        XCTAssertEqual(importedNote.updatedAt, sourceDocument.updatedAt)
        XCTAssertEqual(importedNote.pageCount, sourceDocument.pageCount)
        XCTAssertEqual(importedNote.strokes, sourceDocument.strokes)
        let importedVault = try XCTUnwrap(vault.notes.first)
        XCTAssertEqual(importedVault.archiveSourceNoteID, archivedNote.sourceNoteID)
        XCTAssertEqual(importedVault.archiveLinkedNoteID, importedNote.id)
        XCTAssertEqual(importedVault.archiveSourceState, "linked_note")
        XCTAssertEqual(importedVault.markdown, vaultPayload.markdown)
        XCTAssertEqual(importedVault.sourceUpdatedAt, Double(vaultRow.sourceRevisionMS))
        let importedVideo = try XCTUnwrap(videos.listing().first)
        XCTAssertEqual(importedVideo.noteID, importedNote.id)
        XCTAssertEqual(importedVideo.sourceNoteID, archivedNote.sourceNoteID)
        XCTAssertEqual(importedVideo.sha256, videoRow.sha256)

        let taskVideos = NoteVideoAttachmentStore(directory: root.appendingPathComponent("empty-task-videos", isDirectory: true))
        let snapshot = try await LibraryBackupSnapshot.capture(library: library, vault: vault, videoStore: taskVideos,
            presetStore: presets, restoredVideoStore: videos, coverStore: covers,
            into: root.appendingPathComponent("ios-rebackup", isDirectory: true))
        try LibraryBackupArchive.validate(manifest: snapshot.manifest)
        let rebackedNote = try XCTUnwrap(snapshot.manifest.notes.first)
        let rebackedVault = try XCTUnwrap(snapshot.manifest.vaultEntries.first)
        let rebackedVideo = try XCTUnwrap(snapshot.manifest.videoAttachments.first)
        XCTAssertEqual(rebackedVault.noteItemID, rebackedNote.itemID)
        XCTAssertEqual(rebackedVideo.noteItemID, rebackedNote.itemID)
        XCTAssertEqual(rebackedVault.sourceNoteID, archivedNote.sourceNoteID)
        XCTAssertEqual(rebackedVideo.sourceNoteID, archivedNote.sourceNoteID)
        XCTAssertEqual(rebackedNote.sourceRevisionMS, archivedNote.sourceRevisionMS)
        XCTAssertEqual(rebackedNote.schemaVersion, archivedNote.schemaVersion)
        XCTAssertEqual(rebackedVideo.sourceRevisionMS, videoRow.sourceRevisionMS)
        XCTAssertEqual(rebackedVideo.sourceRevisionPrecisionMS, videoRow.sourceRevisionPrecisionMS)
        XCTAssertEqual(rebackedVideo.createdAtMS, videoRow.createdAtMS)
        XCTAssertEqual(rebackedVault.sourceRevisionMS, vaultRow.sourceRevisionMS)
        XCTAssertEqual(rebackedVault.createdAtMS, vaultRow.createdAtMS)
        XCTAssertEqual(rebackedVideo.sha256, videoRow.sha256)
        let rebackedNoteResource = try XCTUnwrap(snapshot.manifest.resources.first { $0.resourceID == rebackedNote.noteResourceID })
        let rebackedNoteFile = try XCTUnwrap(snapshot.resourceFiles[rebackedNoteResource.resourceID])
        let rebackedDocument = try NoteDocument.decode(Data(contentsOf: rebackedNoteFile))
        XCTAssertEqual(rebackedDocument.updatedAt, sourceDocument.updatedAt)
        XCTAssertEqual(rebackedDocument.strokes.first?.createdAt, sourceDocument.strokes.first?.createdAt)
        XCTAssertEqual(rebackedDocument.strokes.first?.points.first?.timestamp, sourceDocument.strokes.first?.points.first?.timestamp)
        let rebackedVaultResource = try XCTUnwrap(snapshot.manifest.resources.first { $0.resourceID == rebackedVault.resourceID })
        let rebackedVaultFile = try XCTUnwrap(snapshot.resourceFiles[rebackedVaultResource.resourceID])
        let rebackedVaultPayload = try LibraryBackupRestoreCoordinator.readVerifiedVaultPayload(
            at: rebackedVaultFile, descriptor: rebackedVaultResource, entry: rebackedVault)
        XCTAssertEqual(rebackedVaultPayload.title, vaultPayload.title)
        XCTAssertEqual(rebackedVaultPayload.markdown, vaultPayload.markdown)
        for role in ["pdf_original", "assigned_cover_png", "video_attachment_mp4"] {
            let original = try resource(role)
            let copy = try XCTUnwrap(snapshot.manifest.resources.first { $0.role == role })
            XCTAssertEqual(copy.sha256, original.sha256, "Copy-only import and rebackup preserve \(role) bytes")
            XCTAssertEqual(copy.byteLength, original.byteLength, role)
        }
        XCTAssertEqual(snapshot.manifest.formatVersion, 2,
                       "A copied archive with validated Vault sidecars must use the profile-bearing v2 format")
        XCTAssertEqual(snapshot.manifest.updateProfiles.count, snapshot.manifest.notes.count)
        let copiedProfile = try XCTUnwrap(snapshot.manifest.updateProfiles.first)
        XCTAssertEqual(copiedProfile.noteItemID, rebackedNote.itemID)
        XCTAssertEqual(copiedProfile.sourceNoteID, importedNote.id,
                       "The new iPad profile describes the copied local note, not the Android source ID")
        XCTAssertNotEqual(copiedProfile.sourceLineageID, profile.sourceLineageID,
                          "Imported Android provenance is not reused as the iPad copy's lineage")
        XCTAssertEqual(copiedProfile.bodySHA256, rebackedNoteResource.sha256)
        let rebackedStorageRow = try XCTUnwrap(snapshot.manifest.vaultEntries.first)
        let rebackedStorageResource = try XCTUnwrap(snapshot.manifest.resources.first {
            $0.resourceID == rebackedStorageRow.sourceStorageResourceID && $0.role == "vault_storage_markdown"
        })
        XCTAssertNotNil(rebackedStorageRow.sourceStorageResourceID)
        let importedSidecarMetadata = try XCTUnwrap(importedVault.archiveDigitizationMetadata)
        XCTAssertNil(importedSidecarMetadata.operationID)
        let rebackedStorageText = try String(contentsOf: snapshot.directory.appendingPathComponent(rebackedStorageResource.resourceID + ".bin"), encoding: .utf8)
        XCTAssertTrue(rebackedStorageText.contains("title: \(importedVault.title)\n"))
        XCTAssertTrue(rebackedStorageText.contains("note-id: \(archivedNote.sourceNoteID)\n"))
        XCTAssertTrue(rebackedStorageText.contains("pages: \(importedSidecarMetadata.pages)\n"))
        XCTAssertTrue(rebackedStorageText.contains("digitized: \(importedSidecarMetadata.digitized)\n"))
        XCTAssertTrue(rebackedStorageText.contains("digitized-epoch: \(importedSidecarMetadata.digitizedEpoch)\n"))
        XCTAssertTrue(rebackedStorageText.contains("source-modified: \(importedSidecarMetadata.sourceModified)\n"))
        XCTAssertFalse(rebackedStorageText.contains("digitization-operation-id:"),
                       "The valid legacy sidecar remains present without inventing an operation ID")
        let rebackedArchive = root.appendingPathComponent("ios-copy-rebackup.zip")
        try LibraryBackupArchive.write(manifest: snapshot.manifest, resourceFiles: snapshot.resourceFiles, to: rebackedArchive)
        let rebackedStage = try LibraryBackupArchive.stage(from: rebackedArchive,
            into: root.appendingPathComponent("stage-ios-rebackup", isDirectory: true))
        XCTAssertEqual(rebackedStage.manifest.formatVersion, 2)
        XCTAssertEqual(rebackedStage.manifest.notes.count, 1)
        XCTAssertEqual(rebackedStage.manifest.vaultEntries.first?.noteItemID, rebackedStage.manifest.notes.first?.itemID)
        XCTAssertEqual(rebackedStage.manifest.videoAttachments.first?.noteItemID, rebackedStage.manifest.notes.first?.itemID)
        XCTAssertEqual(rebackedStage.manifest.updateProfiles.count, rebackedStage.manifest.notes.count)
        let stagedProfile = try XCTUnwrap(rebackedStage.manifest.updateProfiles.first)
        XCTAssertEqual(stagedProfile.sourceNoteID, importedNote.id)
        XCTAssertNotEqual(stagedProfile.sourceLineageID, profile.sourceLineageID)
        let stagedStorageRow = try XCTUnwrap(rebackedStage.manifest.vaultEntries.first)
        let stagedStorageResource = try XCTUnwrap(rebackedStage.manifest.resources.first {
            $0.resourceID == stagedStorageRow.sourceStorageResourceID && $0.role == "vault_storage_markdown"
        })
        let stagedStorageText = try String(contentsOf: rebackedStage.directory.appendingPathComponent(stagedStorageResource.resourceID + ".bin"), encoding: .utf8)
        XCTAssertEqual(stagedStorageText, rebackedStorageText,
                       "The ZIP roundtrip retains the exact legacy sidecar and its absent operation ID")
        for role in ["pdf_original", "assigned_cover_png", "video_attachment_mp4"] {
            let original = try resource(role)
            let copied = try XCTUnwrap(rebackedStage.manifest.resources.first { $0.role == role })
            let bytes = try Data(contentsOf: rebackedStage.directory.appendingPathComponent(copied.resourceID + ".bin"))
            XCTAssertEqual(Int64(bytes.count), original.byteLength, role)
            XCTAssertEqual(sha256(bytes), original.sha256, "Serialized/re-read iPad ZIP preserves \(role)")
        }
    }

    @MainActor
    func testAndroidR22OperationHeaderArchiveCopiesAllRowsAndPreservesContentMedia() async throws {
        let repository = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        let archive = repository.appendingPathComponent("../docs/fixtures/android-r22-operation-header/operation-header.padnote").standardizedFileURL
        let expectedByteCount = 236_163
        let expectedSHA256 = "91e112271c5fdbf20fcba0a87e956264af5bd5fbda4b0007c8442c7dfec1aff4"
        let bytes = try Data(contentsOf: archive)
        XCTAssertEqual(bytes.count, expectedByteCount)
        XCTAssertEqual(sha256(bytes), expectedSHA256)

        let root = try temporaryDirectory(); defer { try? FileManager.default.removeItem(at: root) }
        let staged = try LibraryBackupArchive.stage(from: archive, into: root.appendingPathComponent("android-r8-stage", isDirectory: true))
        let manifest = staged.manifest
        XCTAssertEqual(manifest.formatVersion, 2)
        XCTAssertEqual(manifest.producer["platform"], "android")
        XCTAssertEqual(manifest.notes.count, 1)
        XCTAssertEqual(manifest.vaultEntries.count, 2)
        XCTAssertEqual(manifest.videoAttachments.count, 1)
        XCTAssertEqual(manifest.resources.count, 8)
        XCTAssertEqual(Dictionary(grouping: manifest.resources, by: \.role).mapValues { $0.count }, [
            "note_document": 1, "pdf_original": 1, "assigned_cover_png": 1,
            "vault_entry_json": 2, "vault_storage_markdown": 2, "video_attachment_mp4": 1
        ])
        XCTAssertEqual(manifest.updateProfiles.count, 1)

        func bytesFor(_ row: LibraryBackupManifest.Resource, in directory: URL) throws -> Data {
            let data = try Data(contentsOf: directory.appendingPathComponent(row.resourceID + ".bin"))
            XCTAssertEqual(Int64(data.count), row.byteLength, row.role)
            XCTAssertEqual(sha256(data), row.sha256, row.role)
            return data
        }
        let archivedNote = try XCTUnwrap(manifest.notes.first)
        let profile = try XCTUnwrap(manifest.updateProfiles.first)
        let noteResource = try XCTUnwrap(manifest.resources.first { $0.resourceID == archivedNote.noteResourceID })
        XCTAssertEqual(profile.schemaVersion, 2)
        XCTAssertEqual(profile.noteItemID, archivedNote.itemID)
        XCTAssertEqual(profile.sourceNoteID, archivedNote.sourceNoteID)
        XCTAssertEqual(profile.bodySHA256, noteResource.sha256)
        let sourceDocument = try NoteDocument.decode(bytesFor(noteResource, in: staged.directory))
        XCTAssertEqual(sourceDocument.id, archivedNote.sourceNoteID)
        XCTAssertEqual(sourceDocument.authorPageEditSerial, 2)
        XCTAssertEqual(sourceDocument.authorPageTopologySerial, 1)
        let sourceNoteObject = try XCTUnwrap(JSONSerialization.jsonObject(with: bytesFor(noteResource, in: staged.directory)) as? [String: Any])
        XCTAssertEqual(Set(sourceNoteObject.keys), Set([
            "schemaVersion", "id", "title", "updatedAt", "canvasWidth", "canvasHeight", "pageWidth", "pageHeight",
            "pageGap", "pageCount", "pdfPageCount", "authorPageEditSerial", "authorPageTopologySerial",
            "viewportScale", "viewportZoom", "viewportCenterX", "viewportCenterY", "strokes", "textFlows",
            "textBoxes", "images", "pageStyle"
        ]), "Pinned archive note has no unexamined top-level fields")
        XCTAssertEqual((sourceNoteObject["schemaVersion"] as? NSNumber)?.intValue, 8)
        XCTAssertEqual((sourceNoteObject["viewportScale"] as? NSNumber)?.doubleValue ?? .nan, 0.8700000047683716, accuracy: 0.0000001)
        XCTAssertEqual((sourceNoteObject["viewportZoom"] as? NSNumber)?.doubleValue ?? .nan, 1.0, accuracy: 0.0000001)
        XCTAssertTrue((sourceNoteObject["textBoxes"] as? [Any])?.isEmpty == true)
        XCTAssertEqual(sourceDocument.title, "Synthetic archive source")
        XCTAssertEqual(sourceDocument.pageCount, 2)
        XCTAssertEqual(sourceDocument.pdfPageCount, 1)
        XCTAssertEqual(sourceDocument.strokes.count, 1)
        let sourceStroke = try XCTUnwrap(sourceDocument.strokes.first)
        XCTAssertEqual(sourceStroke.id, "export-fixture-ink")
        XCTAssertEqual(sourceStroke.color, "#FF0000FF")
        XCTAssertEqual(sourceStroke.baseWidth, 2.5, accuracy: 0.000001)
        XCTAssertEqual(sourceStroke.points, [
            InkPoint(x: 48.25, y: 72.5, pressure: 0.65, timestamp: 1_730_000_000_001),
            InkPoint(x: 93.75, y: 118.125, pressure: 0.9, timestamp: 1_730_000_000_002)
        ])
        XCTAssertEqual(sourceDocument.textFlows.count, 1)
        let sourceFlow = try XCTUnwrap(sourceDocument.textFlows.first)
        XCTAssertEqual(sourceFlow.id, "flow-ai-9800a1c8c5604a9696c44d9cfe3eea5b")
        XCTAssertEqual(sourceFlow.format, "markdown")
        XCTAssertEqual(sourceFlow.source, "Synthetic AI text flow: preserve this complete note-body sentence.")
        XCTAssertEqual(sourceFlow.anchorPageIndex, 1)
        XCTAssertEqual(sourceFlow.fontSizeSp, 16.0, accuracy: 0.000001)
        XCTAssertTrue(sourceDocument.images.isEmpty)

        var sourceVaults: [LibraryBackupVaultPayload] = []
        var operationIDs: [String] = []
        for row in manifest.vaultEntries.sorted(by: { $0.itemID < $1.itemID }) {
            XCTAssertEqual(row.noteItemID, archivedNote.itemID)
            XCTAssertEqual(row.sourceNoteID, archivedNote.sourceNoteID)
            let jsonResource = try XCTUnwrap(manifest.resources.first { $0.resourceID == row.resourceID })
            let payload = try LibraryBackupRestoreCoordinator.readVerifiedVaultPayload(
                at: staged.directory.appendingPathComponent(jsonResource.resourceID + ".bin"), descriptor: jsonResource, entry: row)
            sourceVaults.append(payload)
            if let storageID = row.sourceStorageResourceID {
                let storage = try XCTUnwrap(manifest.resources.first { $0.resourceID == storageID && $0.role == "vault_storage_markdown" })
                XCTAssertEqual(storage.mediaType, "text/markdown", "Vault sidecars use the canonical archive media type")
                let text = try XCTUnwrap(String(data: bytesFor(storage, in: staged.directory), encoding: .utf8))
                XCTAssertTrue(text.contains("note-id: \(row.sourceNoteID)\n"))
                XCTAssertTrue(text.contains("source-modified: \(row.sourceRevisionMS)\n"))
                XCTAssertTrue(text.contains("digitized-epoch: \(row.createdAtMS)\n"))
                operationIDs.append(contentsOf: text.components(separatedBy: "\n").compactMap {
                    $0.hasPrefix("digitization-operation-id: ") ? String($0.dropFirst("digitization-operation-id: ".count)) : nil
                })
            }
        }
        XCTAssertEqual(operationIDs.count, 1)
        let operationID = try XCTUnwrap(operationIDs.first)
        XCTAssertTrue(operationID.range(of: "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", options: .regularExpression) != nil)

        let video = try XCTUnwrap(manifest.videoAttachments.first)
        XCTAssertEqual(video.noteItemID, archivedNote.itemID)
        XCTAssertEqual(video.sourceNoteID, archivedNote.sourceNoteID)
        let videoResource = try XCTUnwrap(manifest.resources.first { $0.role == "video_attachment_mp4" })
        XCTAssertEqual(video.sha256, videoResource.sha256)
        XCTAssertEqual(try bytesFor(videoResource, in: staged.directory).dropFirst(4).prefix(4), Data("ftyp".utf8))
        XCTAssertEqual(try bytesFor(try XCTUnwrap(manifest.resources.first { $0.role == "pdf_original" }), in: staged.directory).prefix(8), Data("%PDF-1.4".utf8))
        XCTAssertEqual(try bytesFor(try XCTUnwrap(manifest.resources.first { $0.role == "assigned_cover_png" }), in: staged.directory).prefix(8), Data([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]))

        let base = root.appendingPathComponent("copy-import", isDirectory: true)
        let journals = base.appendingPathComponent("journals", isDirectory: true)
        let library = NoteLibrary(directory: base.appendingPathComponent("notes", isDirectory: true))
        let vault = VaultLibrary(directory: base.appendingPathComponent("vault", isDirectory: true), journalRoot: journals)
        let videos = RestoredVideoAttachmentStore(directory: base.appendingPathComponent("videos", isDirectory: true), journalRoot: journals)
        let presets = UserCoverPresetStore(directory: base.appendingPathComponent("presets", isDirectory: true), journalRoot: journals)
        let covers = NoteCoverStore(directory: base.appendingPathComponent("covers", isDirectory: true))
        let restore = LibraryBackupRestoreCoordinator(library: library, vault: vault, videoStore: videos,
            presetStore: presets, coverStore: covers, journalRoot: journals)
        let preview = try await restore.inspect(archive, stagingRoot: root.appendingPathComponent("android-r8-inspect", isDirectory: true))
        XCTAssertEqual(preview.noteCount, 1); XCTAssertEqual(preview.pdfCount, 1); XCTAssertEqual(preview.coverCount, 1)
        XCTAssertEqual(preview.vaultCount, 2); XCTAssertEqual(preview.videoCount, 1)
        try await restore.restore(preview)

        let importedNote = try XCTUnwrap(library.notes.first)
        XCTAssertNotEqual(importedNote.id, archivedNote.sourceNoteID)
        var expectedImportedNote = sourceDocument
        expectedImportedNote.id = importedNote.id
        XCTAssertEqual(importedNote, expectedImportedNote,
                       "Every decoded NoteDocument field, including geometry, viewport model, page style and Android serials, survives copy restore")
        XCTAssertEqual(importedNote.title, sourceDocument.title)
        XCTAssertEqual(importedNote.updatedAt, sourceDocument.updatedAt)
        XCTAssertEqual(importedNote.pageCount, sourceDocument.pageCount)
        XCTAssertEqual(importedNote.pdfPageCount, sourceDocument.pdfPageCount)
        XCTAssertEqual(importedNote.strokes, sourceDocument.strokes)
        XCTAssertEqual(importedNote.authorPageEditSerial, sourceDocument.authorPageEditSerial)
        XCTAssertEqual(importedNote.authorPageTopologySerial, sourceDocument.authorPageTopologySerial)
        let importedVault = vault.notes.sorted { $0.markdown < $1.markdown }
        XCTAssertEqual(importedVault.count, 2)
        XCTAssertTrue(importedVault.allSatisfy {
            $0.archiveSourceNoteID == archivedNote.sourceNoteID &&
            $0.archiveLinkedNoteID == importedNote.id && $0.archiveSourceState == "linked_note"
        })
        XCTAssertEqual(importedVault.map { "\($0.title)|\($0.markdown)|\($0.sourceUpdatedAt)|\($0.createdAt.timeIntervalSince1970)" }.sorted(),
                       sourceVaults.map { "\($0.title)|\($0.markdown)|\(Double($0.sourceRevisionMS))|\(Double($0.createdAtMS) / 1000)" }.sorted())
        let importedOperationMetadata = try XCTUnwrap(
            importedVault.first { $0.archiveDigitizationMetadata?.operationID == operationID }?.archiveDigitizationMetadata)
        XCTAssertEqual(importedOperationMetadata.pages, "2")
        XCTAssertEqual(importedOperationMetadata.digitized, "2026-10-10 04:49")
        XCTAssertEqual(importedOperationMetadata.digitizedEpoch, "1791578947320")
        XCTAssertEqual(importedOperationMetadata.sourceModified, "1791578941872")
        XCTAssertEqual(importedVault.filter { $0.archiveDigitizationMetadata?.operationID != nil }.count, 1)
        XCTAssertEqual(importedVault.filter { $0.archiveDigitizationMetadata?.operationID == nil }.count, 1,
                       "The second validated six-field header stays present without inventing an operation ID")
        let coldVault = VaultLibrary(directory: base.appendingPathComponent("vault", isDirectory: true), journalRoot: journals)
        XCTAssertEqual(coldVault.notes.first { $0.archiveDigitizationMetadata?.operationID == operationID }?.archiveDigitizationMetadata,
                       importedOperationMetadata,
                       "Optional digitization provenance survives Vault JSON cold reload")
        let importedVideo = try XCTUnwrap(videos.listing().first)
        XCTAssertEqual(importedVideo.noteID, importedNote.id)
        XCTAssertEqual(importedVideo.sourceNoteID, archivedNote.sourceNoteID)
        XCTAssertEqual(importedVideo.sha256, video.sha256)

        let snapshot = try await LibraryBackupSnapshot.capture(library: library, vault: coldVault,
            videoStore: NoteVideoAttachmentStore(directory: root.appendingPathComponent("empty-task-videos", isDirectory: true)),
            presetStore: presets, restoredVideoStore: videos, coverStore: covers,
            into: root.appendingPathComponent("ios-r8-rebackup", isDirectory: true))
        try LibraryBackupArchive.validate(manifest: snapshot.manifest)
        XCTAssertEqual(snapshot.manifest.formatVersion, 2)
        let copiedProfile = try XCTUnwrap(snapshot.manifest.updateProfiles.first)
        XCTAssertEqual(snapshot.manifest.updateProfiles.count, 1)
        XCTAssertEqual(copiedProfile.sourceNoteID, importedNote.id,
                       "The copied note receives a profile bound to its new iPad-local identity")
        XCTAssertNotEqual(copiedProfile.sourceLineageID, profile.sourceLineageID,
                          "The imported Android source profile is not reused as iPad lineage")
        XCTAssertEqual(snapshot.manifest.notes.count, 1); XCTAssertEqual(snapshot.manifest.vaultEntries.count, 2)
        XCTAssertEqual(snapshot.manifest.videoAttachments.count, 1)
        let rebackedNote = try XCTUnwrap(snapshot.manifest.notes.first)
        XCTAssertEqual(rebackedNote.sourceRevisionMS, archivedNote.sourceRevisionMS)
        XCTAssertEqual(rebackedNote.schemaVersion, archivedNote.schemaVersion)
        XCTAssertEqual(snapshot.manifest.vaultEntries.map(\.sourceRevisionMS).sorted(), manifest.vaultEntries.map(\.sourceRevisionMS).sorted())
        XCTAssertEqual(snapshot.manifest.vaultEntries.map(\.createdAtMS).sorted(), manifest.vaultEntries.map(\.createdAtMS).sorted())
        XCTAssertEqual(snapshot.manifest.videoAttachments.first?.sourceRevisionMS, video.sourceRevisionMS)
        XCTAssertEqual(snapshot.manifest.videoAttachments.first?.sourceRevisionPrecisionMS, video.sourceRevisionPrecisionMS)
        XCTAssertEqual(snapshot.manifest.videoAttachments.first?.createdAtMS, video.createdAtMS)
        func roleDigests(_ resources: [LibraryBackupManifest.Resource]) -> [String: [String]] {
            Dictionary(grouping: resources, by: \.role).mapValues { $0.map { "\($0.byteLength):\($0.sha256)" }.sorted() }
        }
        let exactBinaryRoles: Set<String> = ["pdf_original", "assigned_cover_png", "video_attachment_mp4"]
        let copiedBinaryDigests = { (resources: [LibraryBackupManifest.Resource]) in
            roleDigests(resources.filter { exactBinaryRoles.contains($0.role) })
        }
        XCTAssertEqual(copiedBinaryDigests(manifest.resources), copiedBinaryDigests(snapshot.manifest.resources),
                       "PDF, PNG and MP4 remain byte-identical; JSON is checked by decoded semantic fields below")
        func vaultPayloads(_ manifest: LibraryBackupManifest, directory: URL) throws -> [LibraryBackupVaultPayload] {
            try manifest.vaultEntries.map { entry in
                let descriptor = try XCTUnwrap(manifest.resources.first { $0.resourceID == entry.resourceID })
                return try LibraryBackupRestoreCoordinator.readVerifiedVaultPayload(
                    at: directory.appendingPathComponent(descriptor.resourceID + ".bin"), descriptor: descriptor, entry: entry)
            }.sorted { $0.title < $1.title }
        }
        let expectedVaultPayloads = sourceVaults.sorted { $0.title < $1.title }
        XCTAssertEqual(try vaultPayloads(snapshot.manifest, directory: snapshot.directory), expectedVaultPayloads,
                       "Vault JSON may be re-encoded, but all six portable payload fields must survive")
        let snapshotNoteRow = try XCTUnwrap(snapshot.manifest.notes.first)
        let snapshotNoteResource = try XCTUnwrap(snapshot.manifest.resources.first { $0.resourceID == snapshotNoteRow.noteResourceID })
        let snapshotNote = try NoteDocument.decode(Data(contentsOf: snapshot.resourceFiles[snapshotNoteResource.resourceID]!))
        XCTAssertEqual(snapshotNote, importedNote, "The complete decoded note model, including Android author counters, survives snapshot encoding")
        XCTAssertEqual(snapshot.manifest.vaultEntries.filter { $0.sourceStorageResourceID != nil }.count, 2,
                       "Both validated source Vault headers remain represented in the iPad snapshot")
        XCTAssertEqual(snapshot.manifest.vaultEntries.count, 2)
        XCTAssertEqual(snapshot.manifest.updateProfiles.first?.bodySHA256,
                       snapshot.manifest.resources.first { $0.resourceID == snapshotNoteRow.noteResourceID }?.sha256)
        XCTAssertNotEqual(snapshot.manifest.updateProfiles.first?.sourceLineageID, profile.sourceLineageID,
                          "The copied archive's profile is newly bound to the iPad copy; it does not reuse Android source identity")
        let snapshotOperationIDs = try snapshot.manifest.vaultEntries.compactMap { row -> String? in
            guard let storageID = row.sourceStorageResourceID,
                  let storage = snapshot.manifest.resources.first(where: { $0.resourceID == storageID }) else { return nil }
            let text = try String(contentsOf: snapshot.directory.appendingPathComponent(storage.resourceID + ".bin"), encoding: .utf8)
            return text.components(separatedBy: "\n").first { $0.hasPrefix("digitization-operation-id: ") }
                .map { String($0.dropFirst("digitization-operation-id: ".count)) }
        }
        XCTAssertEqual(snapshotOperationIDs, [operationID],
                       "Rebackup preserves the one optional canonical operation ID and keeps the other valid legacy header absent")
        let rebackedArchive = root.appendingPathComponent("ios-r8-rebackup.zip")
        try LibraryBackupArchive.write(manifest: snapshot.manifest, resourceFiles: snapshot.resourceFiles, to: rebackedArchive)
        let rebacked = try LibraryBackupArchive.stage(from: rebackedArchive, into: root.appendingPathComponent("ios-r8-restage", isDirectory: true))
        XCTAssertEqual(rebacked.manifest.formatVersion, 2)
        XCTAssertEqual(rebacked.manifest.updateProfiles.count, 1)
        XCTAssertEqual(rebacked.manifest.updateProfiles.first?.sourceNoteID, importedNote.id)
        XCTAssertNotEqual(rebacked.manifest.updateProfiles.first?.sourceLineageID, profile.sourceLineageID)
        XCTAssertEqual(copiedBinaryDigests(manifest.resources), copiedBinaryDigests(rebacked.manifest.resources),
                       "Rebacked PDF, PNG and MP4 remain byte-identical")
        XCTAssertEqual(try vaultPayloads(rebacked.manifest, directory: rebacked.directory), expectedVaultPayloads,
                       "Vault JSON is semantically preserved through ZIP write and restage")
        let rebackedNoteRow = try XCTUnwrap(rebacked.manifest.notes.first)
        let rebackedNoteResource = try XCTUnwrap(rebacked.manifest.resources.first { $0.resourceID == rebackedNoteRow.noteResourceID })
        let decodedRebackedNote = try NoteDocument.decode(bytesFor(rebackedNoteResource, in: rebacked.directory))
        XCTAssertEqual(decodedRebackedNote, importedNote, "The staged rebackup retains every decoded note field and source serial")
        let copiedOperationIDs = try rebacked.manifest.vaultEntries.compactMap { row -> String? in
            guard let storageID = row.sourceStorageResourceID,
                  let storage = rebacked.manifest.resources.first(where: { $0.resourceID == storageID }) else { return nil }
            let text = try String(contentsOf: rebacked.directory.appendingPathComponent(storage.resourceID + ".bin"), encoding: .utf8)
            return text.components(separatedBy: "\n").first { $0.hasPrefix("digitization-operation-id: ") }
                .map { String($0.dropFirst("digitization-operation-id: ".count)) }
        }
        XCTAssertEqual(copiedOperationIDs, [operationID],
                       "Copy/rebackup preserves the optional operation ID exactly once without treating it as update authority")
        XCTAssertEqual(rebacked.manifest.vaultEntries.filter { $0.sourceStorageResourceID != nil }.count, 2)
        let rebackedStorageTexts = try rebacked.manifest.vaultEntries.compactMap { row -> String? in
            guard let storageID = row.sourceStorageResourceID else { return nil }
            let storage = try XCTUnwrap(rebacked.manifest.resources.first { $0.resourceID == storageID })
            return try String(contentsOf: rebacked.directory.appendingPathComponent(storage.resourceID + ".bin"), encoding: .utf8)
        }
        XCTAssertEqual(rebackedStorageTexts.filter { $0.contains("digitization-operation-id:") }.count, 1)
        XCTAssertEqual(rebackedStorageTexts.filter { !$0.contains("digitization-operation-id:") }.count, 1,
                       "A valid six-field legacy header remains present without adding an operation ID")
        let attachment = XCTAttachment(contentsOfFile: rebackedArchive)
        attachment.name = "iPad R22 copied archive with preserved operation provenance"
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    @MainActor
    func testVaultStorageTamperAfterPreviewIsRejectedBeforePromotion() async throws {
        let repository = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        let archive = repository.appendingPathComponent("../docs/fixtures/android-r22-operation-header/operation-header.padnote").standardizedFileURL
        let root = try temporaryDirectory(); defer { try? FileManager.default.removeItem(at: root) }
        let base = root.appendingPathComponent("tamper-import", isDirectory: true)
        let journals = base.appendingPathComponent("journals", isDirectory: true)
        let library = NoteLibrary(directory: base.appendingPathComponent("notes", isDirectory: true))
        let vault = VaultLibrary(directory: base.appendingPathComponent("vault", isDirectory: true), journalRoot: journals)
        let videos = RestoredVideoAttachmentStore(directory: base.appendingPathComponent("videos", isDirectory: true), journalRoot: journals)
        let presets = UserCoverPresetStore(directory: base.appendingPathComponent("presets", isDirectory: true), journalRoot: journals)
        let covers = NoteCoverStore(directory: base.appendingPathComponent("covers", isDirectory: true))
        let restore = LibraryBackupRestoreCoordinator(library: library, vault: vault, videoStore: videos,
            presetStore: presets, coverStore: covers, journalRoot: journals)
        let preview = try await restore.inspect(archive, stagingRoot: root.appendingPathComponent("tamper-stage", isDirectory: true))
        let row = try XCTUnwrap(preview.staged.manifest.vaultEntries.first { $0.sourceStorageResourceID != nil })
        let storageID = try XCTUnwrap(row.sourceStorageResourceID)
        let storageURL = preview.staged.directory.appendingPathComponent(storageID + ".bin")
        let original = try String(contentsOf: storageURL, encoding: .utf8)
        XCTAssertTrue(original.contains("digitization-operation-id: f92fd647-9384-4513-ab27-0b56bd867530\n"))
        let changed = original.replacingOccurrences(of: "f92fd647-9384-4513-ab27-0b56bd867530",
                                                     with: "f92fd647-9384-4513-ab27-0b56bd867531")
        try Data(changed.utf8).write(to: storageURL, options: .atomic)
        do {
            try await restore.restore(preview)
            XCTFail("A sidecar changed after preview must fail the manifest resource hash check")
        } catch let error as LibraryBackupError {
            XCTAssertEqual(error, .sourceChanged)
        }
        XCTAssertTrue(library.notes.isEmpty, "Changed metadata must be rejected before note promotion")
        XCTAssertTrue(vault.notes.isEmpty, "Changed metadata must be rejected before Vault promotion")
        XCTAssertTrue(try videos.listing().isEmpty, "Changed metadata must be rejected before video promotion")
    }

    @MainActor
    func testLegacyVaultRowsWithoutDigitizationMetadataKeepPreviousFiniteDateRules() throws {
        let root = try temporaryDirectory(); defer { try? FileManager.default.removeItem(at: root) }
        let directory = root.appendingPathComponent("legacy-vault", isDirectory: true)
        let journals = root.appendingPathComponent("journals", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let values = [
            VaultNote(id: "01234567-89ab-4cde-8fab-012345678901", title: "negative finite revision", markdown: "legacy",
                sourceUpdatedAt: -1, createdAt: Date(timeIntervalSince1970: 9_000_000_000_000)),
            VaultNote(id: "01234567-89ab-4cde-8fab-012345678902", title: "large finite revision", markdown: "legacy",
                sourceUpdatedAt: 9_000_000_000_000_001, createdAt: Date(timeIntervalSince1970: 1_790_000_000))
        ]
        func safeVaultID(_ id: String) -> String {
            Data(id.utf8).base64EncodedString().replacingOccurrences(of: "/", with: "_")
                .replacingOccurrences(of: "+", with: "-")
        }
        for value in values {
            let path = directory.appendingPathComponent("\(safeVaultID(value.id)).json")
            try JSONEncoder().encode(value).write(to: path)
        }
        let reloaded = VaultLibrary(directory: directory, journalRoot: journals)
        XCTAssertEqual(Set(reloaded.notes.map(\.id)), Set(values.map(\.id)),
                       "Metadata-free legacy rows retain the previous finite/date-nonnegative acceptance rules")
    }

    func testDigitizationMetadataTimestampStringsAreBoundedAndExact() {
        let valid = VaultDigitizationMetadata(pages: "2", digitized: "2026-10-10 04:49",
            digitizedEpoch: "1791578947320", sourceModified: "1791578941872",
            operationID: "f92fd647-9384-4513-ab27-0b56bd867530")
        XCTAssertTrue(valid.isValid(sourceRevisionMS: 1_791_578_941_872, createdAtMS: 1_791_578_947_320))
        XCTAssertFalse(valid.isValid(sourceRevisionMS: 1_791_578_941_873, createdAtMS: 1_791_578_947_320))
        XCTAssertFalse(valid.isValid(sourceRevisionMS: 1_791_578_941_872, createdAtMS: 1_791_578_947_321))
        let overflow = VaultDigitizationMetadata(pages: "2", digitized: "2026-10-10 04:49",
            digitizedEpoch: "9223372036854775808", sourceModified: "1791578941872", operationID: nil)
        XCTAssertFalse(overflow.isValid(sourceRevisionMS: 1_791_578_941_872, createdAtMS: 1_791_578_947_320))
    }

    func testVaultStorageWriterPreservesMarkdownBytesAfterFrontMatter() throws {
        let cases: [(String, String?)] = [
            ("\nleading-newline body", "f92fd647-9384-4513-ab27-0b56bd867530"),
            ("body without leading newline", nil)
        ]
        for (markdown, operationID) in cases {
            let metadata = VaultDigitizationMetadata(pages: "2", digitized: "2026-10-10 04:49",
                digitizedEpoch: "1791578947320", sourceModified: "1791578941872", operationID: operationID)
            let text = LibraryBackupSnapshot.vaultStorageText(title: "fixture", sourceNoteID: "source-note",
                metadata: metadata, markdown: markdown)
            let separator = try XCTUnwrap(text.range(of: "\n---\n"))
            XCTAssertEqual(String(text[separator.upperBound...]), markdown,
                           "Front matter serialization must not add or remove Vault markdown bytes")
        }
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

    @MainActor
    func testRestoreJournalVisibilityWritesRespectCatalogCaptureLease() throws {
        let root = try temporaryDirectory(); defer { try? FileManager.default.removeItem(at: root) }
        let journals = root.appendingPathComponent("journals", isDirectory: true)
        try FileManager.default.createDirectory(at: journals, withIntermediateDirectories: false)
        let transaction = UUID().uuidString.lowercased()
        let url = journals.appendingPathComponent(transaction + ".json")
        let noteGroupID = "i-0123456789abcdef0123456789abcdef"
        let noteID = UUID().uuidString.lowercased()
        let original = RestoreJournal(transactionID: transaction, archiveSHA256: String(repeating: "a", count: 64),
            status: "incomplete", stageID: UUID().uuidString.lowercased(), noteIDs: [noteGroupID: noteID],
            noteSHA256ByItemID: [noteGroupID: String(repeating: "b", count: 64)],
            vaultIDs: [:], videoIDs: [:], presetIDs: [:], resourceSHA256: [:], independentGroupIDs: [],
            completedGroups: [], groupPhases: [noteGroupID: "index_committed"], updatedAt: Date())
        try LibraryBackupRestoreCoordinator.writeJournal(original, to: url)
        let bytesBeforeBusyWrite = try Data(contentsOf: url)
        var pendingCommit = original
        pendingCommit.groupPhases[noteGroupID] = "committed"
        pendingCommit.completedGroups = [noteGroupID]

        try NoteGroupCatalogFence.withCapture {
            XCTAssertThrowsError(try LibraryBackupRestoreCoordinator.writeJournal(pendingCommit, to: url)) {
                XCTAssertEqual($0 as? NoteGroupCatalogFenceError, .snapshotInProgress)
            }
            XCTAssertEqual(try Data(contentsOf: url), bytesBeforeBusyWrite,
                           "A busy capture must leave durable transaction ownership/phase bytes unchanged")
        }

        // After the capture ends, the same transaction's stable journal IDs remain writable
        // for explicit recovery; the blocked attempted phase never became durable.
        try LibraryBackupRestoreCoordinator.writeJournal(pendingCommit, to: url)
        let reopened = try XCTUnwrap(LibraryBackupTransactionGate.readJournal(transaction, in: journals))
        XCTAssertTrue(LibraryBackupRestoreCoordinator.isGroupCompleted(noteGroupID, in: reopened),
                      "After the capture lease releases, the exact committed group phase can be durably retried")
        XCTAssertEqual(reopened.status, "incomplete")
        XCTAssertEqual(reopened.noteIDs, original.noteIDs,
                       "Stable transaction ownership survives the blocked visibility transition")
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
    func testRestoreHoldsCatalogWriterLeaseAcrossPromotedResourcesBeforeJournalWrite() async throws {
        let repository = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        let archive = repository.appendingPathComponent("../docs/fixtures/library-backup-r1/application-valid/archive/valid-library.zip").standardizedFileURL
        XCTAssertEqual(sha256(try Data(contentsOf: archive)), "82d7bb7794e5c56d4b3ec55a498000ba6bb3e365795c467616ea553ada518abe")
        let root = try temporaryDirectory(); defer { try? FileManager.default.removeItem(at: root) }
        let journalRoot = root.appendingPathComponent("journals", isDirectory: true)
        let library = NoteLibrary(directory: root.appendingPathComponent("notes", isDirectory: true))
        let vault = VaultLibrary(directory: root.appendingPathComponent("vault", isDirectory: true), journalRoot: journalRoot)
        let taskVideos = NoteVideoAttachmentStore(directory: root.appendingPathComponent("task-videos", isDirectory: true))
        let restoredVideos = RestoredVideoAttachmentStore(directory: root.appendingPathComponent("restored-videos", isDirectory: true), journalRoot: journalRoot)
        let presets = UserCoverPresetStore(directory: root.appendingPathComponent("presets", isDirectory: true), journalRoot: journalRoot)
        let covers = NoteCoverStore(directory: root.appendingPathComponent("covers", isDirectory: true))
        let coordinator = LibraryBackupRestoreCoordinator(library: library, vault: vault, videoStore: restoredVideos,
            presetStore: presets, coverStore: covers, journalRoot: journalRoot)
        let preview = try await coordinator.inspect(archive, stagingRoot: root.appendingPathComponent("staging", isDirectory: true))
        let pause = RestorePromotionPause()
        coordinator.restoreInterleavingTestHook = { point in
            if point == .resourcesPromotedBeforeJournalWrite { await pause.pause() }
        }

        let restore = Task { try await coordinator.restore(preview) }
        do {
            try await pause.waitUntilReached()
            let journalURL = try XCTUnwrap(FileManager.default.contentsOfDirectory(at: journalRoot,
                includingPropertiesForKeys: nil).first { $0.pathExtension == "json" })
            let journalBeforeBusyCapture = try Data(contentsOf: journalURL)
            do {
                _ = try await LibraryBackupSnapshot.capture(library: library, vault: vault, videoStore: taskVideos,
                    presetStore: presets, restoredVideoStore: restoredVideos, coverStore: covers,
                    into: root.appendingPathComponent("capture-during-restore", isDirectory: true))
                XCTFail("A backup snapshot must not observe resources promoted before their restore journal phase is durable")
            } catch {
                XCTAssertEqual(error as? NoteGroupCatalogFenceError, .writerInProgress)
            }
            XCTAssertEqual(try Data(contentsOf: journalURL), journalBeforeBusyCapture,
                           "The rejected capture cannot advance or rewrite the restore ownership journal")
        } catch {
            restore.cancel()
            await pause.release()
            _ = try? await restore.value
            throw error
        }
        await pause.release()
        try await restore.value
        do {
            _ = try await LibraryBackupSnapshot.capture(library: library, vault: vault, videoStore: taskVideos,
                presetStore: presets, restoredVideoStore: restoredVideos, coverStore: covers,
                into: root.appendingPathComponent("capture-after-restore", isDirectory: true))
        } catch {
            XCTFail("The writer lease must be released after restore completes: \(error)")
        }
        XCTAssertEqual(library.notes.count, 2)
        XCTAssertEqual(vault.notes.count, 2)
        XCTAssertEqual(try restoredVideos.listing().count, 2)
        XCTAssertEqual(try presets.listing().count, 1)
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

    func testAndroidDigitizationOperationHeaderIsOptionalButStrict() throws {
        let repository = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        let sourceArchive = repository.appendingPathComponent("../docs/fixtures/android-r53b-v2/grouped-current-v2.zip").standardizedFileURL
        let sourceArchiveBytes = try Data(contentsOf: sourceArchive)
        XCTAssertEqual(sourceArchiveBytes.count, 240_715)
        XCTAssertEqual(sha256(sourceArchiveBytes), "c924b611a80a2382e091dc26db5304f2e144db5667f054fb7b4ab8e9f067786e")
        let root = try temporaryDirectory(); defer { try? FileManager.default.removeItem(at: root) }

        let legacyStage = try LibraryBackupArchive.stage(from: sourceArchive, into: root.appendingPathComponent("legacy-stage", isDirectory: true))
        let legacyManifest = legacyStage.manifest
        try LibraryBackupArchive.writeStagedManifest(legacyManifest, to: legacyStage.directory)
        XCTAssertEqual(legacyManifest.producer["platform"], "android")
        let legacyStorage = try XCTUnwrap(legacyManifest.resources.first { $0.role == "vault_storage_markdown" })
        let legacyBytes = try Data(contentsOf: legacyStage.directory.appendingPathComponent(legacyStorage.resourceID + ".bin"))
        let legacyText = try XCTUnwrap(String(data: legacyBytes, encoding: .utf8))
        XCTAssertTrue(legacyText.contains("source-modified: 1791458660213\n"))
        XCTAssertFalse(legacyText.contains("digitization-operation-id:"))
        XCTAssertNoThrow(try LibraryBackupArchive.readStagedManifest(from: legacyStage.directory),
                         "the actual Android archive with the six required headers remains readable")

        let operationID = "01234567-89ab-4cde-8fab-0123456789ab"
        let validOptional = try writeAndroidArchiveVariant(sourceArchive, root: root, name: "optional-valid") { text in
            self.insertAndroidVaultHeader("digitization-operation-id: \(operationID)", into: text)
        }
        let optionalStage = try LibraryBackupArchive.stage(from: validOptional,
            into: root.appendingPathComponent("optional-valid-stage", isDirectory: true))
        let optionalStorage = try XCTUnwrap(optionalStage.manifest.resources.first { $0.role == "vault_storage_markdown" })
        let optionalText = try XCTUnwrap(String(data: Data(contentsOf: optionalStage.directory.appendingPathComponent(optionalStorage.resourceID + ".bin")), encoding: .utf8))
        XCTAssertTrue(optionalText.contains("digitization-operation-id: \(operationID)\n"))
        XCTAssertEqual(optionalText.components(separatedBy: "\n---\n").last,
                       legacyText.components(separatedBy: "\n---\n").last,
                       "the typed payload and Markdown body are preserved exactly")

        let invalidVariants: [(String, (String) -> String)] = [
            ("uppercase-operation-id", { self.insertAndroidVaultHeader("digitization-operation-id: \(operationID.uppercased())", into: $0) }),
            ("malformed-operation-id", { self.insertAndroidVaultHeader("digitization-operation-id: not-a-uuid", into: $0) }),
            ("duplicate-operation-id", { self.insertAndroidVaultHeader("digitization-operation-id: \(operationID)\ndigitization-operation-id: \(operationID)", into: $0) }),
            ("unknown-header", { self.insertAndroidVaultHeader("unrecognized-source: value", into: $0) }),
            ("duplicate-required-header", { self.insertAndroidVaultHeader("title: Update fixture Vault", into: $0) })
        ]
        for (name, mutation) in invalidVariants {
            let invalidArchive = try writeAndroidArchiveVariant(sourceArchive, root: root, name: name, mutate: mutation)
            XCTAssertThrowsError(try LibraryBackupArchive.stage(from: invalidArchive,
                into: root.appendingPathComponent("stage-\(name)", isDirectory: true)), name) { error in
                guard case LibraryBackupError.invalidManifest = error else {
                    return XCTFail("\(name) must be rejected by strict Vault Markdown validation, got \(error)")
                }
            }
        }
    }

    private func insertAndroidVaultHeader(_ line: String, into text: String) -> String {
        guard let separator = text.range(of: "\n---\n") else { return text }
        return String(text[..<separator.lowerBound]) + "\n" + line + String(text[separator.lowerBound...])
    }

    private func writeAndroidArchiveVariant(_ sourceArchive: URL, root: URL, name: String,
        mutate: (String) -> String) throws -> URL {
        let base = root.appendingPathComponent("variant-\(name)", isDirectory: true)
        try FileManager.default.createDirectory(at: base, withIntermediateDirectories: false)
        let staged = try LibraryBackupArchive.stage(from: sourceArchive, into: base.appendingPathComponent("source-stage", isDirectory: true))
        var manifest = staged.manifest
        let vault = try XCTUnwrap(manifest.vaultEntries.first { $0.sourceState == "linked_note" })
        let storageID = try XCTUnwrap(vault.sourceStorageResourceID)
        let resourceIndex = try XCTUnwrap(manifest.resources.firstIndex { $0.resourceID == storageID })
        let oldResource = manifest.resources[resourceIndex]
        let storageURL = staged.directory.appendingPathComponent(oldResource.resourceID + ".bin")
        let oldBytes = try Data(contentsOf: storageURL)
        let oldText = try XCTUnwrap(String(data: oldBytes, encoding: .utf8))
        let newText = mutate(oldText)
        XCTAssertNotEqual(newText, oldText, "each generated header variant must differ from the pinned source archive")
        let newBytes = Data(newText.utf8)
        try newBytes.write(to: storageURL, options: .atomic)
        let newDigest = sha256(newBytes)
        manifest.resources[resourceIndex] = .init(resourceID: oldResource.resourceID, role: oldResource.role,
            mediaType: oldResource.mediaType, byteLength: Int64(newBytes.count), sha256: newDigest, member: oldResource.member)

        let note = try XCTUnwrap(manifest.notes.first { $0.itemID == vault.noteItemID })
        let profileIndex = try XCTUnwrap(manifest.updateProfiles.firstIndex { $0.noteItemID == note.itemID })
        let oldProfile = manifest.updateProfiles[profileIndex]
        func resourceLine(_ role: String, _ id: String) throws -> String {
            let row = try XCTUnwrap(manifest.resources.first { $0.resourceID == id })
            return "\(role)\0\(id)\0\(row.byteLength)\0\(row.sha256)\0\n"
        }
        func materialLine(_ kind: String, _ item: String, _ source: String, _ revision: Int64, _ id: String) throws -> String {
            let row = try XCTUnwrap(manifest.resources.first { $0.resourceID == id })
            return "\(kind)\0\(item)\0\(source)\0\(revision)\0\(row.byteLength)\0\(row.sha256)\n"
        }
        var fixed = "note\0\(note.itemID)\0\(note.sourceNoteID)\0\(note.schemaVersion)\0\n"
        fixed += try resourceLine("body", note.noteResourceID)
        if let pdf = note.pdfResourceID { fixed += try resourceLine("pdf", pdf) } else { fixed += "pdf\0absent\n" }
        if let cover = note.coverResourceID { fixed += try resourceLine("cover", cover) } else { fixed += "cover\0absent\n" }
        var linked = [String]()
        for row in manifest.vaultEntries where row.noteItemID == note.itemID && row.sourceState == "linked_note" {
            linked.append(try materialLine("vault", row.itemID, row.sourceNoteID, row.sourceRevisionMS, row.resourceID))
            if let storageID = row.sourceStorageResourceID {
                linked.append(try materialLine("vault_storage", row.itemID, row.sourceNoteID, row.sourceRevisionMS, storageID))
                let resource = try XCTUnwrap(manifest.resources.first { $0.resourceID == storageID })
                linked.append("archive-v2-android-vault-material-uuid/v1\0\(row.itemID)\0\(row.sourceNoteID)\0\(storageID)\0\(resource.sha256)\n")
            }
        }
        for row in manifest.videoAttachments where row.noteItemID == note.itemID && row.sourceState == "linked_note" {
            linked.append(try materialLine("video", row.itemID, row.sourceNoteID, row.sourceRevisionMS, row.resourceID))
        }
        let group = sha256(Data(("PadNote/ArchiveNoteGroup/v2\n" + fixed + linked.sorted().joined()).utf8))
        let lineage = androidArchiveNameUUID("PadNote/source-lineage/v2\0\(manifest.producer["platform"] ?? "")\0\(note.sourceNoteID)")
        XCTAssertEqual(lineage, oldProfile.sourceLineageID)
        let revision = androidArchiveNameUUID("PadNote/source-revision/v2\0\(lineage)\0\(group)")
        manifest.updateProfiles[profileIndex] = .init(schemaVersion: oldProfile.schemaVersion,
            noteItemID: oldProfile.noteItemID, sourceNoteID: oldProfile.sourceNoteID, sourceLineageID: lineage,
            sourceRevisionID: revision, groupSHA256: group, bodySHA256: oldProfile.bodySHA256,
            timestamps: oldProfile.timestamps)

        let resources = Dictionary(uniqueKeysWithValues: manifest.resources.map {
            ($0.resourceID, staged.directory.appendingPathComponent($0.resourceID + ".bin"))
        })
        let archive = root.appendingPathComponent("variant-\(name).zip")
        try LibraryBackupArchive.write(manifest: manifest, resourceFiles: resources, to: archive)
        return archive
    }

    private func androidArchiveNameUUID(_ value: String) -> String {
        var bytes = Array(Insecure.MD5.hash(data: Data(value.utf8)))
        bytes[6] = (bytes[6] & 0x0f) | 0x30
        bytes[8] = (bytes[8] & 0x3f) | 0x80
        let hex = bytes.map { String(format: "%02x", $0) }.joined()
        return "\(hex.prefix(8))-\(hex.dropFirst(8).prefix(4))-\(hex.dropFirst(12).prefix(4))-\(hex.dropFirst(16).prefix(4))-\(hex.dropFirst(20))"
    }
}
