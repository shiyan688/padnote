import XCTest
@testable import PadNote
import UIKit
import PDFKit
import CryptoKit

final class NoteGroupFacadeTests: XCTestCase {
    private func groupVideoConnection() -> AgentTaskConnectionIdentity {
        AgentTaskConnectionIdentity(profile: AgentConnectionProfile(
            id: UUID(uuidString: "22222222-2222-4222-8222-222222222222")!, name: "group video", kind: .builtinVideo,
            endpoint: "https://video.fixture", transport: .bridge, bridgeID: "bridge", instanceID: "instance",
            certSHA256: String(repeating: "c", count: 64), revision: 1))
    }

    private func groupTestMemberID(note: String, key: String) -> String {
        var bytes = Array(SHA256.hash(data: Data("padnote-group-member-v1|\(note)|\(key)".utf8)).prefix(16))
        bytes[6] = (bytes[6] & 0x0f) | 0x50
        bytes[8] = (bytes[8] & 0x3f) | 0x80
        let value = UUID(uuid: (bytes[0], bytes[1], bytes[2], bytes[3], bytes[4], bytes[5], bytes[6], bytes[7],
                                bytes[8], bytes[9], bytes[10], bytes[11], bytes[12], bytes[13], bytes[14], bytes[15]))
        return value.uuidString.lowercased()
    }
    @MainActor
    func testGroupedVideoAttachReadbackRemoveAndFullHistory() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("group-video-roundtrip-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        defer { try? FileManager.default.removeItem(at: root) }
        let notes = root.appendingPathComponent("notes", isDirectory: true)
        let library = NoteLibrary(directory: notes)
        let note = NoteDocument(title: "video group")
        try library.save(note)
        let localID = try NoteGroupFacade.foundationUUID(note.id)
        let associationID = groupTestMemberID(note: localID, key: "association-index")
        let index: [String: Any] = ["schemaVersion": 1, "localNoteID": localID, "legacyNoteID": note.id, "members": [[String: Any]]()]
        let indexURL = root.appendingPathComponent("association.json")
        try JSONSerialization.data(withJSONObject: index, options: [.sortedKeys]).write(to: indexURL)
        let store = try NoteGroupStore(rootURL: root.appendingPathComponent("note-groups", isDirectory: true))
        let base = try store.commit(localNoteID: localID, lineageID: localID, expected: nil, members: [
            .init(id: localID, role: .body, mediaType: "application/json", sourceURL: notes.appendingPathComponent("\(note.id).json")),
            .init(id: associationID, role: .association, mediaType: "application/json", sourceURL: indexURL)
        ])
        let session = try library.openEditorSession(noteID: note.id)
        XCTAssertEqual(session.groupToken, base)
        let stageRoot = root.appendingPathComponent("stage", isDirectory: true)
        try FileManager.default.createDirectory(at: stageRoot, withIntermediateDirectories: false, attributes: [.posixPermissions: 0o700])
        let sourceURL = stageRoot.appendingPathComponent("verified.mp4")
        let bytes = Data([0, 0, 0, 24, 0x66, 0x74, 0x79, 0x70, 0x69, 0x73, 0x6f, 0x6d, 1, 2, 3])
        try bytes.write(to: sourceURL)
        let digest = SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined()
        let artifact = AgentTaskArtifact(id: "group-video", name: "group.mp4", mediaType: "video/mp4", sizeBytes: bytes.count, sha256: digest)
        let stageStore = NoteVideoAttachmentStore(directory: stageRoot.appendingPathComponent("attachments", isDirectory: true))
        let attachment = try stageStore.associate(noteID: note.id, taskID: UUID(), remoteTaskID: "task-1", sourceRevision: 1,
            sourceSnapshotSHA256: String(repeating: "a", count: 64), connection: groupVideoConnection(), artifact: artifact, verifiedFile: sourceURL)
        let staged = try XCTUnwrap(stageStore.archiveRecords().records.first { $0.attachment == attachment })
        let attached = try library.publishGroupedVideo(attachment, stagedVideoURL: staged.fileURL, stagedMetadataURL: staged.metadataURL, basedOn: session)
        let associationMember = try XCTUnwrap(attached.members.first { $0.record.role == .association })
        let associationJSON = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: associationMember.url)) as? [String: Any])
        let associationRows = try XCTUnwrap(associationJSON["members"] as? [[String: Any]])
        let videoAssociation = try XCTUnwrap(associationRows.first { $0["role"] as? String == NoteGroupMemberRole.video.rawValue })
        let sourceID = try XCTUnwrap(videoAssociation["sourceID"] as? String)
        XCTAssertEqual(sourceID, attachment.id.uuidString.lowercased(),
                       "The ordinary publisher writes canonical group source IDs")
        XCTAssertEqual(NoteLibrary.canonicalGroupMemberID(sourceID), attachment.id.uuidString.lowercased())
        let video = try XCTUnwrap(attached.members.first { $0.record.role == .video })
        XCTAssertEqual(try Data(contentsOf: video.url), bytes)
        XCTAssertEqual(try library.groupedVideoAttachments(basedOn: attached), [attachment])
        XCTAssertEqual(try Data(contentsOf: library.groupedVideoFileURL(attachmentID: attachment.id, basedOn: attached)), bytes)
        let originalMembers = try store.readRevision(base).manifest.members
        for original in originalMembers {
            let updated = try XCTUnwrap(attached.members.first(where: { $0.record.id == original.id })).record
            if original.role == .association {
                XCTAssertNotEqual(updated.sha256, original.sha256,
                                  "The association index must publish the new video link")
                let historical = try XCTUnwrap(store.readRevision(base).manifest.members.first { $0.id == original.id })
                XCTAssertEqual(historical.sha256, original.sha256,
                               "The prior immutable revision must retain its exact association index")
            } else {
                XCTAssertEqual(updated.sha256, original.sha256,
                               "Adding an attachment must retain prior body and media members")
            }
        }
        XCTAssertFalse(try store.readRevision(base).manifest.members.contains { $0.role == .video })
        let same = try library.publishGroupedVideo(attachment, stagedVideoURL: staged.fileURL, stagedMetadataURL: staged.metadataURL, basedOn: attached)
        XCTAssertEqual(same.groupToken, attached.groupToken, "Retry of the exact task artifact must be idempotent")
        let removed = try library.removeGroupedVideo(attachmentID: attachment.id, basedOn: attached)
        XCTAssertTrue(try library.groupedVideoAttachments(basedOn: removed).isEmpty)
        XCTAssertTrue(try store.readRevision(attached.groupToken!).manifest.members.contains { $0.role == .video })
        XCTAssertFalse(try store.readRevision(removed.groupToken!).manifest.members.contains { $0.role == .video })
    }

    @MainActor
    func testGroupedVideoRejectsStaleTokenAndCaptureBusyWithoutPublishing() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("group-video-conflicts-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        defer { try? FileManager.default.removeItem(at: root) }
        let notes = root.appendingPathComponent("notes", isDirectory: true)
        let library = NoteLibrary(directory: notes)
        let note = NoteDocument(title: "video conflicts")
        try library.save(note)
        let localID = try NoteGroupFacade.foundationUUID(note.id)
        let associationURL = root.appendingPathComponent("association.json")
        try JSONSerialization.data(withJSONObject: ["schemaVersion": 1, "localNoteID": localID, "legacyNoteID": note.id,
            "members": [[String: Any]]()], options: [.sortedKeys]).write(to: associationURL)
        let store = try NoteGroupStore(rootURL: root.appendingPathComponent("note-groups", isDirectory: true))
        let associationID = groupTestMemberID(note: localID, key: "association-index")
        _ = try store.commit(localNoteID: localID, lineageID: localID, expected: nil, members: [
            .init(id: localID, role: .body, mediaType: "application/json", sourceURL: notes.appendingPathComponent("\(note.id).json")),
            .init(id: associationID, role: .association, mediaType: "application/json", sourceURL: associationURL)
        ])
        let stale = try library.openEditorSession(noteID: note.id)
        var edited = stale.document; edited.title = "newer revision"
        let current = try library.save(edited, basedOn: stale)
        let bytes = Data([0, 0, 0, 24, 0x66, 0x74, 0x79, 0x70, 0x69, 0x73, 0x6f, 0x6d, 1, 2, 3])
        let source = root.appendingPathComponent("source.mp4"); try bytes.write(to: source)
        let sha = SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined()
        let artifact = AgentTaskArtifact(id: "conflict-video", name: "conflict.mp4", mediaType: "video/mp4", sizeBytes: bytes.count, sha256: sha)
        let stageRoot = root.appendingPathComponent("stage", isDirectory: true)
        try FileManager.default.createDirectory(at: stageRoot, withIntermediateDirectories: false, attributes: [.posixPermissions: 0o700])
        let stageStore = NoteVideoAttachmentStore(directory: stageRoot)
        let value = try stageStore.associate(noteID: note.id, taskID: UUID(), remoteTaskID: "task-2", sourceRevision: 1,
            sourceSnapshotSHA256: String(repeating: "b", count: 64), connection: groupVideoConnection(), artifact: artifact, verifiedFile: source)
        let staged = try XCTUnwrap(stageStore.archiveRecords().records.first { $0.attachment == value })
        XCTAssertThrowsError(try library.publishGroupedVideo(value, stagedVideoURL: staged.fileURL, stagedMetadataURL: staged.metadataURL, basedOn: stale)) {
            XCTAssertEqual($0 as? NoteGroupStoreError, .compareAndSwapConflict)
        }
        let recordCount = try store.readRevision(current.groupToken!).manifest.members.count
        XCTAssertThrowsError(try NoteGroupCatalogFence.withCapture {
            _ = try library.publishGroupedVideo(value, stagedVideoURL: staged.fileURL, stagedMetadataURL: staged.metadataURL, basedOn: current)
        }) { XCTAssertEqual($0 as? NoteGroupCatalogFenceError, .snapshotInProgress) }
        let sidecarStore = NoteVideoAttachmentStore(directory: root.appendingPathComponent("legacy-sidecars", isDirectory: true))
        XCTAssertThrowsError(try NoteGroupCatalogFence.withCapture {
            try sidecarStore.remove(noteID: note.id, attachmentID: UUID())
        }) { XCTAssertEqual($0 as? NoteGroupCatalogFenceError, .snapshotInProgress) }
        let restoredStore = RestoredVideoAttachmentStore(directory: root.appendingPathComponent("restored-videos", isDirectory: true))
        XCTAssertThrowsError(try NoteGroupCatalogFence.withCapture {
            try restoredStore.rollbackPartial(id: UUID(), transactionID: "tx", groupID: "group", expectedSHA256: String(repeating: "0", count: 64))
        }) { XCTAssertEqual($0 as? NoteGroupCatalogFenceError, .snapshotInProgress) }
        XCTAssertEqual(try store.readCurrentIfPresent(localNoteID: localID, lineageID: localID)?.token, current.groupToken)
        XCTAssertEqual(try store.readRevision(current.groupToken!).manifest.members.count, recordCount)
    }

    func testCatalogCaptureExcludesWritersWithoutTaskOrThreadReentrancy() throws {
        XCTAssertThrowsError(try NoteGroupCatalogFence.withCapture {
            try NoteGroupCatalogFence.withWriter { () }
        }) { XCTAssertEqual($0 as? NoteGroupCatalogFenceError, .snapshotInProgress) }
        XCTAssertNoThrow(try NoteGroupCatalogFence.withWriter { () })
    }

    @MainActor
    func testGroupAssociationSourceIDComparisonRequiresUUIDButIgnoresCase() {
        let canonical = "12345678-1234-4234-8234-123456789abc"
        XCTAssertEqual(NoteLibrary.canonicalGroupMemberID(canonical.uppercased()), canonical)
        XCTAssertNil(NoteLibrary.canonicalGroupMemberID("not-a-uuid"),
                     "Arbitrary legacy IDs must not be normalized into foundation video member IDs")
    }

    @MainActor
    func testCustomLibraryCreatesParentBeforeInitializingGroupStore() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("group-custom-library-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let notes = root.appendingPathComponent("first-copy/notes", isDirectory: true)
        XCTAssertFalse(FileManager.default.fileExists(atPath: root.path))

        let note = NoteDocument(title: "custom path")
        let library = NoteLibrary(directory: notes)
        XCTAssertNoThrow(try library.save(note), "A custom path with a missing parent must initialize its group store")
        XCTAssertEqual(NoteLibrary(directory: notes).notes.first?.id, note.id)
    }

    @MainActor
    func testShelfRenameCASAndGroupTombstoneRetainFullMediaHistory() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("group-shelf-lifecycle-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        defer { try? FileManager.default.removeItem(at: root) }
        let notes = root.appendingPathComponent("notes", isDirectory: true)
        let legacyLibrary = NoteLibrary(directory: notes)
        var note = NoteDocument(title: "before shelf rename")
        note.pdfPageCount = 1
        try legacyLibrary.save(note)
        let bodyURL = notes.appendingPathComponent("\(note.id).json")
        let pdfURL = root.appendingPathComponent("original.pdf")
        let pdfBytes = UIGraphicsPDFRenderer(bounds: CGRect(x: 0, y: 0, width: 80, height: 120)).pdfData { $0.beginPage() }
        try pdfBytes.write(to: pdfURL)
        let coverURL = root.appendingPathComponent("cover.png")
        let image = UIGraphicsImageRenderer(size: CGSize(width: 16, height: 16)).image { UIColor.systemPurple.setFill(); $0.fill(CGRect(x: 0, y: 0, width: 16, height: 16)) }
        let coverBytes = try NoteCoverStore.encodedPNG(image)
        try coverBytes.write(to: coverURL)
        let sources: [(NoteGroupMemberRole, String, String, URL)] = [
            (.body, note.id.lowercased(), "application/json", bodyURL),
            (.pdf, UUID().uuidString.lowercased(), "application/pdf", pdfURL),
            (.cover, UUID().uuidString.lowercased(), "image/png", coverURL),
            (.video, UUID().uuidString.lowercased(), "video/mp4", root.appendingPathComponent("video.mp4")),
            (.videoMetadata, UUID().uuidString.lowercased(), "application/json", root.appendingPathComponent("video.json")),
            (.vault, UUID().uuidString.lowercased(), "text/markdown", root.appendingPathComponent("vault.md")),
            (.vaultMetadata, UUID().uuidString.lowercased(), "application/json", root.appendingPathComponent("vault.json")),
            (.association, UUID().uuidString.lowercased(), "application/json", root.appendingPathComponent("association.json"))
        ]
        let payloads: [NoteGroupMemberRole: Data] = [
            .video: Data("synthetic mp4 member".utf8), .videoMetadata: Data(#"{"taskID":"task-1"}"#.utf8),
            .vault: Data("# linked vault".utf8), .vaultMetadata: Data(#"{"entryID":"entry-1"}"#.utf8),
            .association: Data(#"{"videoID":"linked","vaultID":"linked"}"#.utf8)
        ]
        for (role, _, _, url) in sources where role != .body && role != .pdf && role != .cover {
            try payloads[role]!.write(to: url)
        }
        let store = try NoteGroupStore(rootURL: root.appendingPathComponent("note-groups", isDirectory: true))
        let localID = try NoteGroupFacade.foundationUUID(note.id)
        let baseToken = try store.commit(localNoteID: localID, lineageID: localID, expected: nil,
            members: sources.map { NoteGroupMemberInput(id: $0.1, role: $0.0, mediaType: $0.2, sourceURL: $0.3) })
        let library = NoteLibrary(directory: notes)
        let menuSession = try library.openEditorSession(noteID: note.id)
        let baseRows = try store.readRevision(baseToken).manifest.members
        XCTAssertEqual(Set(baseRows.map(\.role)), Set([.body, .pdf, .cover, .video, .videoMetadata, .vault, .vaultMetadata, .association]))

        var renamedNote = menuSession.document
        renamedNote.title = "renamed with captured token"
        let renamedSession = try library.save(renamedNote, basedOn: menuSession)
        XCTAssertNotEqual(renamedSession.groupToken, baseToken)
        for old in baseRows where old.role != .body {
            XCTAssertEqual(renamedSession.members.first(where: { $0.record.id == old.id })?.record.sha256, old.sha256,
                           "Shelf rename must carry all unchanged full-media members forward")
        }

        var staleRename = menuSession.document
        staleRename.title = "stale rename must fail"
        XCTAssertThrowsError(try library.save(staleRename, basedOn: menuSession)) {
            XCTAssertEqual($0 as? NoteGroupStoreError, .compareAndSwapConflict)
        }
        XCTAssertThrowsError(try library.delete(menuSession.document, basedOn: menuSession)) {
            XCTAssertEqual($0 as? NoteGroupStoreError, .compareAndSwapConflict)
        }

        _ = try library.delete(renamedSession.document, basedOn: renamedSession)
        XCTAssertTrue(library.notes.isEmpty, "Tombstoning removes only the visible note entry")
        let current = try XCTUnwrap(store.readCurrentIfPresent(localNoteID: localID, lineageID: localID))
        XCTAssertTrue(current.manifest.tombstone)
        XCTAssertEqual(current.manifest.parentRevisionID, renamedSession.groupToken?.revisionID)
        XCTAssertTrue(current.manifest.members.isEmpty)
        XCTAssertTrue(try store.readRevision(baseToken).manifest.members.count == 8)
        XCTAssertTrue(try store.readRevision(renamedSession.groupToken!).manifest.members.count == 8)
        XCTAssertTrue(NoteLibrary(directory: notes).notes.isEmpty, "Cold reopen must honor the tombstone without purging history")
    }

    @MainActor
    func testDraftWriterRejectsCaptureAndDirtyRevisionCanRetry() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("group-draft-fence-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        defer { try? FileManager.default.removeItem(at: root) }
        let library = NoteLibrary(directory: root.appendingPathComponent("notes", isDirectory: true))
        let note = NoteDocument(title: "saved")
        try library.save(note)
        var dirty = note
        dirty.title = "dirty but still in editor memory"
        let revision = library.nextDraftRevision(noteID: note.id)
        library.registerDraftRevision(noteID: note.id, revision: revision)

        do {
            try await NoteGroupCatalogFence.withCapture {
                _ = try await library.persistRegisteredDraft(dirty, revision: revision)
            }
            XCTFail("Draft persistence must reject while the catalog capture lease is held")
        } catch {
            XCTAssertEqual(error as? NoteGroupCatalogFenceError, .snapshotInProgress)
        }
        XCTAssertNil(library.pendingDraft(noteID: note.id), "A rejected draft write must not be reported as persisted")
        XCTAssertEqual(dirty.title, "dirty but still in editor memory")

        let retryPersisted = try await library.persistRegisteredDraft(dirty, revision: revision)
        XCTAssertTrue(retryPersisted)
        XCTAssertEqual(library.pendingDraft(noteID: note.id)?.document, dirty,
                       "The same dirty revision must remain retryable after capture releases the lease")
        try library.save(dirty)
        do {
            try await NoteGroupCatalogFence.withCapture {
                try await library.markCanonicalSaved(noteID: note.id, revision: revision)
            }
            XCTFail("Draft cleanup must reject while the catalog capture lease is held")
        } catch {
            XCTAssertEqual(error as? NoteGroupCatalogFenceError, .snapshotInProgress)
        }
        XCTAssertNotNil(library.pendingDraft(noteID: note.id), "Busy cleanup must not hide the persisted recovery draft")
        try await library.markCanonicalSaved(noteID: note.id, revision: revision)
        XCTAssertNil(library.pendingDraft(noteID: note.id), "Draft cleanup must be retryable after capture releases the lease")
    }

    @MainActor
    func testLateLegacyCoverPickerCannotMutateAfterGroupMarkerAppears() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("late-cover-group-marker-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        defer { try? FileManager.default.removeItem(at: root) }
        let groupRoot = root.appendingPathComponent("note-groups", isDirectory: true)
        let coverRoot = root.appendingPathComponent("covers", isDirectory: true)
        let covers = NoteCoverStore(directory: coverRoot, groupRootURL: groupRoot)
        let existingID = UUID().uuidString.lowercased()
        let emptyID = UUID().uuidString.lowercased()
        let oldImage = UIGraphicsImageRenderer(size: CGSize(width: 12, height: 12)).image { context in
            UIColor.systemBlue.setFill(); context.fill(CGRect(x: 0, y: 0, width: 12, height: 12))
        }
        let lateImage = UIGraphicsImageRenderer(size: CGSize(width: 12, height: 12)).image { context in
            UIColor.systemOrange.setFill(); context.fill(CGRect(x: 0, y: 0, width: 12, height: 12))
        }
        try covers.assign(noteID: existingID, image: oldImage)
        let existingCoverURL = coverRoot.appendingPathComponent("\(existingID).cover.png")
        let priorCoverBytes = try Data(contentsOf: existingCoverURL)
        let store = try NoteGroupStore(rootURL: groupRoot)

        func publishBody(_ id: String, title: String) throws -> NoteGroupVersionToken {
            let bodyURL = root.appendingPathComponent("\(id).json")
            let note = NoteDocument(id: id, title: title)
            try note.encoded().write(to: bodyURL)
            return try store.commit(localNoteID: id, lineageID: id, expected: nil, members: [
                .init(id: id, role: .body, mediaType: "application/json", sourceURL: bodyURL)
            ])
        }

        let activeToken = try publishBody(existingID, title: "grouped after picker opened")
        let active = try store.readCurrent(localNoteID: existingID, lineageID: existingID)
        XCTAssertEqual(active.token, activeToken)
        XCTAssertThrowsError(try covers.assign(noteID: existingID, image: lateImage)) {
            XCTAssertEqual($0 as? NoteGroupStoreError, .compareAndSwapConflict)
        }
        XCTAssertThrowsError(try covers.remove(noteID: existingID)) {
            XCTAssertEqual($0 as? NoteGroupStoreError, .compareAndSwapConflict)
        }
        XCTAssertEqual(try Data(contentsOf: existingCoverURL), priorCoverBytes, "late assign/remove must preserve the old legacy sidecar")
        XCTAssertEqual(try store.readCurrent(localNoteID: existingID, lineageID: existingID).token, activeToken,
                       "late legacy sidecar writes must leave the current grouped revision unchanged")
        let historical = try store.readRevision(activeToken)
        XCTAssertEqual(historical.manifest.members.map(\.sha256), active.manifest.members.map(\.sha256))

        let retiredToken = try store.tombstone(localNoteID: existingID, lineageID: existingID, expected: activeToken)
        XCTAssertThrowsError(try covers.assign(noteID: existingID, image: lateImage)) {
            XCTAssertEqual($0 as? NoteGroupStoreError, .compareAndSwapConflict)
        }
        XCTAssertThrowsError(try covers.remove(noteID: existingID)) {
            XCTAssertEqual($0 as? NoteGroupStoreError, .compareAndSwapConflict)
        }
        XCTAssertEqual(try Data(contentsOf: existingCoverURL), priorCoverBytes, "a retired marker also blocks legacy sidecar writes")
        XCTAssertEqual(try store.readCurrent(localNoteID: existingID, lineageID: existingID).token, retiredToken)

        _ = try publishBody(emptyID, title: "grouped before picker completion")
        let emptyCoverURL = coverRoot.appendingPathComponent("\(emptyID).cover.png")
        XCTAssertFalse(FileManager.default.fileExists(atPath: emptyCoverURL.path))
        XCTAssertThrowsError(try covers.assign(noteID: emptyID, image: lateImage)) {
            XCTAssertEqual($0 as? NoteGroupStoreError, .compareAndSwapConflict)
        }
        XCTAssertThrowsError(try covers.remove(noteID: emptyID)) {
            XCTAssertEqual($0 as? NoteGroupStoreError, .compareAndSwapConflict)
        }
        XCTAssertFalse(FileManager.default.fileExists(atPath: emptyCoverURL.path), "rejected late picker must not create a sidecar")

        let corruptID = UUID().uuidString.lowercased()
        try covers.assign(noteID: corruptID, image: oldImage)
        let corruptCoverURL = coverRoot.appendingPathComponent("\(corruptID).cover.png")
        let corruptCoverBytes = try Data(contentsOf: corruptCoverURL)
        _ = try publishBody(corruptID, title: "marker will be corrupted")
        let marker = groupRoot.appendingPathComponent("groups/\(corruptID)/current.json")
        try Data("corrupt-current-marker".utf8).write(to: marker, options: .atomic)
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: marker.path)
        XCTAssertThrowsError(try covers.assign(noteID: corruptID, image: lateImage)) {
            XCTAssertEqual($0 as? NoteGroupStoreError, .corruptRevision)
        }
        XCTAssertThrowsError(try covers.remove(noteID: corruptID)) {
            XCTAssertEqual($0 as? NoteGroupStoreError, .corruptRevision)
        }
        XCTAssertEqual(try Data(contentsOf: corruptCoverURL), corruptCoverBytes,
                       "a corrupt present marker must not downgrade to legacy sidecar mutation")
    }

    @MainActor
    func testLegacyCoverStoreRetainsOpaqueIDCompatibility() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("opaque-cover-id-\(UUID().uuidString)", isDirectory: true)
        let coverRoot = root.appendingPathComponent("covers", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        defer { try? FileManager.default.removeItem(at: root) }
        let covers = NoteCoverStore(directory: coverRoot, groupRootURL: root.appendingPathComponent("note-groups", isDirectory: true))
        let opaqueID = "legacy-opaque-cover-\(UUID().uuidString.lowercased())"
        let image = UIGraphicsImageRenderer(size: CGSize(width: 12, height: 12)).image { context in
            UIColor.systemGreen.setFill(); context.fill(CGRect(x: 0, y: 0, width: 12, height: 12))
        }
        try covers.assign(noteID: opaqueID, image: image)
        let url = coverRoot.appendingPathComponent("\(opaqueID).cover.png")
        XCTAssertTrue(FileManager.default.fileExists(atPath: url.path))
        try covers.remove(noteID: opaqueID)
        XCTAssertFalse(FileManager.default.fileExists(atPath: url.path))
    }

    @MainActor
    func testGroupedCoverReplaceAndRemoveUseCASAndRetainPDFHistory() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("group-cover-cas-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        defer { try? FileManager.default.removeItem(at: root) }
        let notes = root.appendingPathComponent("notes", isDirectory: true)
        let library = NoteLibrary(directory: notes)
        var note = NoteDocument(title: "group cover CAS")
        note.pdfPageCount = 1
        try library.save(note)
        let bodyURL = notes.appendingPathComponent("\(note.id).json")
        let pdfURL = notes.appendingPathComponent("\(note.id).pdf")
        let pdfBytes = UIGraphicsPDFRenderer(bounds: CGRect(x: 0, y: 0, width: 80, height: 120)).pdfData { $0.beginPage() }
        try pdfBytes.write(to: pdfURL)

        let store = try NoteGroupStore(rootURL: root.appendingPathComponent("note-groups", isDirectory: true))
        let localID = try NoteGroupFacade.foundationUUID(note.id)
        let baseToken = try store.commit(localNoteID: localID, lineageID: localID, expected: nil, members: [
            .init(id: localID, role: .body, mediaType: "application/json", sourceURL: bodyURL),
            .init(id: UUID().uuidString.lowercased(), role: .pdf, mediaType: "application/pdf", sourceURL: pdfURL)
        ])
        let groupedLibrary = NoteLibrary(directory: notes)
        let baseSession = try groupedLibrary.openEditorSession(noteID: note.id)
        XCTAssertEqual(baseSession.groupToken, baseToken)
        let oldPDF = try XCTUnwrap(baseSession.members.first(where: { $0.record.role == .pdf }))
        let oldBody = try XCTUnwrap(baseSession.members.first(where: { $0.record.role == .body }))
        XCTAssertEqual(groupedLibrary.pdfURL(for: note), oldPDF.url)
        XCTAssertEqual(try groupedLibrary.backupSourceURL(for: note), oldBody.url)
        let exported = try groupedLibrary.exportURL(for: note)
        XCTAssertEqual(try NoteArchive.decode(Data(contentsOf: exported)).pdf, pdfBytes,
                       "Single-note export must package the PDF from the current group snapshot")

        let image = UIGraphicsImageRenderer(size: CGSize(width: 24, height: 24)).image { context in
            UIColor.systemOrange.setFill(); context.fill(CGRect(x: 0, y: 0, width: 24, height: 24))
        }
        let png = try NoteCoverStore.encodedPNG(image)
        let withCover = try groupedLibrary.saveCoverPNG(png, basedOn: baseSession)
        let cover = try XCTUnwrap(withCover.members.first(where: { $0.record.role == .cover }))
        let preservedPDF = try XCTUnwrap(withCover.members.first(where: { $0.record.role == .pdf }))
        XCTAssertEqual(preservedPDF.record.sha256, oldPDF.record.sha256)
        XCTAssertNotEqual(withCover.groupToken, baseToken)
        XCTAssertEqual(try Data(contentsOf: cover.url), png)
        let oldRevision = try store.readRevision(baseToken)
        XCTAssertFalse(oldRevision.manifest.members.contains(where: { $0.role == .cover }))

        XCTAssertThrowsError(try groupedLibrary.saveCoverPNG(nil, basedOn: baseSession)) {
            XCTAssertEqual($0 as? NoteGroupStoreError, .compareAndSwapConflict)
        }
        let withoutCover = try groupedLibrary.saveCoverPNG(nil, basedOn: withCover)
        XCTAssertFalse(withoutCover.members.contains(where: { $0.record.role == .cover }))
        XCTAssertEqual(try XCTUnwrap(withoutCover.members.first(where: { $0.record.role == .pdf })).record.sha256,
                       oldPDF.record.sha256)
        XCTAssertTrue(try store.readRevision(withCover.groupToken!).manifest.members.contains(where: { $0.role == .cover }),
                      "Replacing/removing a cover must preserve its immutable parent revision")
    }

    @MainActor
    func testNoteLibraryOpensCurrentGroupAndEditorSaveUsesSessionCAS() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("group-library-session-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let localID = UUID().uuidString.lowercased()
        let notes = root.appendingPathComponent("notes", isDirectory: true)
        try FileManager.default.createDirectory(at: notes, withIntermediateDirectories: true)
        var legacy = NoteDocument(id: localID, title: "stale legacy")
        legacy.pageCount = 1
        try legacy.encoded().write(to: notes.appendingPathComponent("\(localID).json"))
        var current = NoteDocument(id: localID, title: "group current")
        current.pageCount = 1
        let body = root.appendingPathComponent("group-body.json")
        try current.encoded().write(to: body)
        let groups = try NoteGroupStore(rootURL: root.appendingPathComponent("note-groups", isDirectory: true))
        _ = try groups.commit(localNoteID: localID, lineageID: localID, expected: nil,
                             members: [.init(id: localID, role: .body, mediaType: "application/json", sourceURL: body)])

        let library = NoteLibrary(directory: notes)
        XCTAssertEqual(library.notes.first?.title, "group current", "A valid group marker must select the current snapshot")
        let first = try library.openEditorSession(noteID: localID)
        let stale = try library.openEditorSession(noteID: localID)
        var edited = first.document
        edited.title = "saved group edit"
        let next = try library.save(edited, basedOn: first)
        XCTAssertNotEqual(next.groupToken, first.groupToken)
        XCTAssertThrowsError(try library.save(legacy, basedOn: stale)) {
            XCTAssertEqual($0 as? NoteGroupStoreError, .compareAndSwapConflict)
        }
        XCTAssertEqual(try NoteDocument.decode(Data(contentsOf: notes.appendingPathComponent("\(localID).json"))).title,
                       "stale legacy", "Group editing must not rewrite the old legacy file")
        XCTAssertEqual(try library.openEditorSession(noteID: localID).document.title, "saved group edit")
    }

    func testPreviewRechecksAllFullMediaWitnessesAndAdoptsOneImmutableGeneration() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("group-facade-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }

        let noteID = "A4E95BD1-5A27-4B0F-8B9C-A3E54A70765A"
        let lineageID = "D85E5F91-7807-47A1-B483-91B3E053B6B3"
        let payloads: [(NoteGroupMemberRole, String, Data)] = [
            (.body, "a4e95bd1-5a27-4b0f-8b9c-a3e54a70765a", Data("legacy body bytes, including ink JSON".utf8)),
            (.pdf, "58e1f946-0d46-4b40-9e98-4dfb3987588f", Data("%PDF-legacy".utf8)),
            (.cover, "25a32782-93e4-495c-8be8-5301470c326d", Data([0x89, 0x50, 0x4e, 0x47])),
            (.video, "d68eeb61-7c93-49e1-9e26-fcbf4380f620", Data("video bytes".utf8)),
            (.videoMetadata, "6fa7b6b5-2c62-4d0b-a9ca-dcfb3420b7cf", Data("{\"taskID\":\"video-task\"}".utf8)),
            (.vault, "825d0802-c7b2-43e2-a16a-12ea8663f678", Data("linked knowledge markdown".utf8)),
            (.vaultMetadata, "67f4c9b3-7bd9-4433-88a0-2542f0e9f370", Data("{\"id\":\"vault-source\",\"markdown\":\"linked knowledge markdown\"}".utf8)),
            (.association, "be37582d-03bc-4b8c-bc50-1dfe786bcb73", Data("{\"videoID\":\"d68eeb61-7c93-49e1-9e26-fcbf4380f620\",\"vaultID\":\"825d0802-c7b2-43e2-a16a-12ea8663f678\"}".utf8))
        ]
        let sources = try payloads.map { item -> LegacyNoteGroupSnapshot.Source in
            let (role, id, bytes) = item
            let url = root.appendingPathComponent("legacy-\(id).bin")
            try bytes.write(to: url)
            return .init(id: id, role: role, url: url)
        }
        let snapshot = LegacyNoteGroupSnapshot(localNoteID: noteID, lineageID: lineageID, sources: sources)
        let store = try NoteGroupStore(rootURL: root.appendingPathComponent("groups"))
        let facade = NoteGroupFacade(store: store) { requested in
            XCTAssertEqual(requested, noteID.lowercased())
            return snapshot
        }

        let preview = try facade.preview(noteID: noteID)
        XCTAssertEqual(preview.localNoteID, noteID.lowercased())
        XCTAssertEqual(preview.witnesses.count, payloads.count)
        XCTAssertEqual(try Data(contentsOf: sources[0].url), payloads[0].2, "Preview must not rewrite legacy source bytes")
        let token = try facade.adopt(preview)
        let result = try facade.read(noteID: noteID, lineageID: lineageID)
        guard case .current(let readToken, let members) = result else { return XCTFail("Expected the committed group") }
        XCTAssertEqual(token, readToken)
        XCTAssertEqual(Set(members.map { $0.record.role }), Set(NoteGroupMemberRole.allCases))
        for (source, payload) in zip(sources, payloads) {
            XCTAssertEqual(try Data(contentsOf: source.url), payload.2, "Adoption must retain original legacy bytes")
            let member = try XCTUnwrap(members.first { $0.record.id == source.id.lowercased() })
            XCTAssertEqual(try Data(contentsOf: member.url), payload.2)
        }
        let staleInputs = try sources.map { source in
            NoteGroupMemberInput(id: try NoteGroupFacade.foundationUUID(source.id), role: source.role,
                                 mediaType: mediaType(for: source.role), sourceURL: source.url)
        }
        XCTAssertThrowsError(try store.commit(localNoteID: noteID.lowercased(), lineageID: lineageID.lowercased(),
                                               expected: nil, members: staleInputs)) {
            XCTAssertEqual($0 as? NoteGroupStoreError, .compareAndSwapConflict)
        }
        let retired = try store.tombstone(localNoteID: noteID.lowercased(), lineageID: lineageID.lowercased(), expected: token)
        guard case .retired(let retiredToken) = try facade.read(noteID: noteID, lineageID: lineageID) else {
            return XCTFail("Tombstone must be authoritative for ordinary reads")
        }
        XCTAssertEqual(retired, retiredToken)
    }

    func testPreviewRejectsSourceChangeBeforeConfirmation() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("group-witness-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let noteID = "a4e95bd1-5a27-4b0f-8b9c-a3e54a70765a"
        let body = root.appendingPathComponent("body.json")
        try Data("before".utf8).write(to: body)
        let snapshot = LegacyNoteGroupSnapshot(localNoteID: noteID, lineageID: "d85e5f91-7807-47a1-b483-91b3e053b6b3",
                                               sources: [.init(id: noteID, role: .body, url: body)])
        let store = try NoteGroupStore(rootURL: root.appendingPathComponent("groups"))
        let facade = NoteGroupFacade(store: store) { _ in snapshot }
        let preview = try facade.preview(noteID: noteID)
        try Data("after".utf8).write(to: body)
        XCTAssertThrowsError(try facade.adopt(preview)) { XCTAssertEqual($0 as? NoteGroupFacadeError, .sourceChanged) }
        XCTAssertThrowsError(try store.readCurrent(localNoteID: noteID, lineageID: snapshot.lineageID))
    }

    func testSourceChangedAfterConfirmationButBeforeCopyPublishesNothing() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("group-copy-race-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let noteID = "a4e95bd1-5a27-4b0f-8b9c-a3e54a70765a"
        let lineageID = "d85e5f91-7807-47a1-b483-91b3e053b6b3"
        let body = root.appendingPathComponent("body.json")
        try Data("approved".utf8).write(to: body)
        let snapshot = LegacyNoteGroupSnapshot(localNoteID: noteID, lineageID: lineageID,
                                               sources: [.init(id: noteID, role: .body, url: body)])
        let store = try NoteGroupStore(rootURL: root.appendingPathComponent("groups"), failureInjector: { point in
            if case .beforeMemberCopy = point { try Data("raced".utf8).write(to: body, options: .atomic) }
        })
        let facade = NoteGroupFacade(store: store) { _ in snapshot }
        let preview = try facade.preview(noteID: noteID)
        XCTAssertThrowsError(try facade.adopt(preview)) { XCTAssertEqual($0 as? NoteGroupFacadeError, .sourceChanged) }
        XCTAssertNil(try store.readCurrentIfPresent(localNoteID: noteID, lineageID: lineageID))
        XCTAssertTrue(try store.history(localNoteID: noteID, lineageID: lineageID).isEmpty)
    }

    func testBrokenCurrentRevisionNeverFallsBackToLegacy() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("group-read-marker-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let noteID = "a4e95bd1-5a27-4b0f-8b9c-a3e54a70765a"
        let lineageID = "d85e5f91-7807-47a1-b483-91b3e053b6b3"
        let body = root.appendingPathComponent("body.json")
        try Data("body".utf8).write(to: body)
        let storeRoot = root.appendingPathComponent("groups")
        let store = try NoteGroupStore(rootURL: storeRoot)
        let token = try store.commit(localNoteID: noteID, lineageID: lineageID, expected: nil,
                                     members: [.init(id: noteID, role: .body, mediaType: "application/json", sourceURL: body)])
        let revision = storeRoot.appendingPathComponent("groups", isDirectory: true)
            .appendingPathComponent(lineageID, isDirectory: true).appendingPathComponent("revisions", isDirectory: true)
            .appendingPathComponent(token.revisionID, isDirectory: true)
        try FileManager.default.removeItem(at: revision)
        var legacyFallbackCalls = 0
        let facade = NoteGroupFacade(store: store) { _ in
            legacyFallbackCalls += 1
            return LegacyNoteGroupSnapshot(localNoteID: noteID, lineageID: lineageID,
                                           sources: [.init(id: noteID, role: .body, url: body)])
        }
        XCTAssertThrowsError(try facade.read(noteID: noteID, lineageID: lineageID))
        XCTAssertEqual(legacyFallbackCalls, 0, "A present current marker with a missing revision must fail closed")
    }

    func testCurrentRevisionWithMissingMemberNeverFallsBackToLegacy() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("group-read-member-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let noteID = "a4e95bd1-5a27-4b0f-8b9c-a3e54a70765a"
        let lineageID = "d85e5f91-7807-47a1-b483-91b3e053b6b3"
        let body = root.appendingPathComponent("body.json")
        try Data("body".utf8).write(to: body)
        let storeRoot = root.appendingPathComponent("groups")
        let store = try NoteGroupStore(rootURL: storeRoot)
        let token = try store.commit(localNoteID: noteID, lineageID: lineageID, expected: nil,
                                     members: [.init(id: noteID, role: .body, mediaType: "application/json", sourceURL: body)])
        let member = storeRoot.appendingPathComponent("groups", isDirectory: true)
            .appendingPathComponent(lineageID, isDirectory: true).appendingPathComponent("revisions", isDirectory: true)
            .appendingPathComponent(token.revisionID, isDirectory: true).appendingPathComponent("members", isDirectory: true)
            .appendingPathComponent("\(noteID).bin")
        try FileManager.default.removeItem(at: member)
        var legacyFallbackCalls = 0
        let facade = NoteGroupFacade(store: store) { _ in
            legacyFallbackCalls += 1
            return LegacyNoteGroupSnapshot(localNoteID: noteID, lineageID: lineageID,
                                           sources: [.init(id: noteID, role: .body, url: body)])
        }
        XCTAssertThrowsError(try facade.read(noteID: noteID, lineageID: lineageID))
        XCTAssertEqual(legacyFallbackCalls, 0, "A present marker with a missing member must fail closed")
    }

    func testAbsentCurrentMarkerUsesLegacyFallback() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("group-read-legacy-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let noteID = "a4e95bd1-5a27-4b0f-8b9c-a3e54a70765a"
        let lineageID = "d85e5f91-7807-47a1-b483-91b3e053b6b3"
        let body = root.appendingPathComponent("body.json"); try Data("legacy".utf8).write(to: body)
        let store = try NoteGroupStore(rootURL: root.appendingPathComponent("groups"))
        let snapshot = LegacyNoteGroupSnapshot(localNoteID: noteID, lineageID: lineageID,
                                               sources: [.init(id: noteID, role: .body, url: body)])
        var legacyFallbackCalls = 0
        let facade = NoteGroupFacade(store: store) { _ in legacyFallbackCalls += 1; return snapshot }
        guard case .legacy(let legacy) = try facade.read(noteID: noteID, lineageID: lineageID) else {
            return XCTFail("Only a genuinely absent marker should use legacy fallback")
        }
        XCTAssertEqual(legacy.localNoteID, noteID)
        XCTAssertEqual(legacyFallbackCalls, 1)
    }

    func testSnapshotRejectsMultipleAssociationIndexes() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("group-association-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let noteID = "a4e95bd1-5a27-4b0f-8b9c-a3e54a70765a"
        let lineageID = "d85e5f91-7807-47a1-b483-91b3e053b6b3"
        let body = root.appendingPathComponent("body.json"); try Data("body".utf8).write(to: body)
        let first = root.appendingPathComponent("assoc1.json"); try Data("{}".utf8).write(to: first)
        let second = root.appendingPathComponent("assoc2.json"); try Data("{}".utf8).write(to: second)
        let snapshot = LegacyNoteGroupSnapshot(localNoteID: noteID, lineageID: lineageID, sources: [
            .init(id: noteID, role: .body, url: body),
            .init(id: "be37582d-03bc-4b8c-bc50-1dfe786bcb73", role: .association, url: first),
            .init(id: "4e28c1d7-2cc3-4e5f-b44a-cbb16d3ef11b", role: .association, url: second)
        ])
        let store = try NoteGroupStore(rootURL: root.appendingPathComponent("groups"))
        let facade = NoteGroupFacade(store: store) { _ in snapshot }
        XCTAssertThrowsError(try facade.preview(noteID: noteID)) { XCTAssertEqual($0 as? NoteGroupFacadeError, .invalidSnapshot) }
    }

    @MainActor
    func testRealLegacyStoresCaptureAdoptAndReadCompleteMediaGroup() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("group-real-capture-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        defer { try? FileManager.default.removeItem(at: root) }

        let noteID = "A4E95BD1-5A27-4B0F-8B9C-A3E54A70765A"
        let canonicalID = noteID.lowercased()
        let notesDirectory = root.appendingPathComponent("notes", isDirectory: true)
        let library = NoteLibrary(directory: notesDirectory)
        var note = NoteDocument(id: noteID, title: "Legacy full media")
        note.pageCount = 1; note.pdfPageCount = 1
        note.strokes = [InkStroke(id: "stroke-1", points: [InkPoint(x: 12, y: 18, pressure: 0.7, timestamp: 1)])]
        try library.save(note)
        let renderer = UIGraphicsPDFRenderer(bounds: CGRect(x: 0, y: 0, width: 120, height: 160))
        let pdf = renderer.pdfData { context in context.beginPage() }
        try pdf.write(to: notesDirectory.appendingPathComponent("\(noteID).pdf"))

        let coverStore = NoteCoverStore(directory: root.appendingPathComponent("covers", isDirectory: true))
        let image = UIGraphicsImageRenderer(size: CGSize(width: 32, height: 32)).image { context in
            UIColor.systemBlue.setFill(); context.fill(CGRect(x: 0, y: 0, width: 32, height: 32))
        }
        try coverStore.assign(noteID: noteID, image: image)

        let vault = VaultLibrary(directory: root.appendingPathComponent("vault", isDirectory: true),
                                 journalRoot: root.appendingPathComponent("journals", isDirectory: true))
        try vault.save(note: note, markdown: "linked legacy knowledge")

        let videoStore = NoteVideoAttachmentStore(directory: root.appendingPathComponent("original-videos", isDirectory: true))
        let originalVideoBytes = Data("synthetic-original-video-payload".utf8)
        let originalVideoURL = root.appendingPathComponent("original.mp4")
        try originalVideoBytes.write(to: originalVideoURL)
        let profile = AgentConnectionProfile(name: "local synthetic", kind: .builtinVideo, endpoint: "http://127.0.0.1")
        let connection = AgentTaskConnectionIdentity(profile: profile)
        let originalArtifact = AgentTaskArtifact(id: "original-artifact", name: "original.mp4", mediaType: "video/mp4",
                                                 sizeBytes: originalVideoBytes.count, sha256: Self.sha(originalVideoBytes))
        let originalAttachment = try videoStore.associate(noteID: noteID, taskID: UUID(), remoteTaskID: "original-task",
            sourceRevision: 1, sourceSnapshotSHA256: String(repeating: "a", count: 64), connection: connection,
            artifact: originalArtifact, verifiedFile: originalVideoURL)

        let restoredVideoStore = RestoredVideoAttachmentStore(directory: root.appendingPathComponent("restored-videos", isDirectory: true),
                                                               journalRoot: root.appendingPathComponent("journals", isDirectory: true))
        let restoredVideoBytes = Data("synthetic-restored-video-payload".utf8)
        let restoredVideoURL = root.appendingPathComponent("restored.mp4")
        try restoredVideoBytes.write(to: restoredVideoURL)
        let restoredDescriptor = try Self.restoredVideoDescriptor(bytes: restoredVideoBytes, noteID: noteID)
        let transactionID = UUID().uuidString.lowercased()
        let restoredGroupID = "i-" + String(repeating: "c", count: 32)
        let restoredVideoID = UUID().uuidString.lowercased()
        let noteHash = Self.sha(try note.validated().encoded())
        let journal = RestoreJournal(transactionID: transactionID, archiveSHA256: String(repeating: "d", count: 64),
            status: "incomplete", stageID: UUID().uuidString.lowercased(),
            noteIDs: [restoredGroupID: canonicalID], noteSHA256ByItemID: [restoredGroupID: noteHash],
            vaultIDs: [:], videoIDs: [restoredDescriptor.itemID: restoredVideoID], presetIDs: [:],
            vaultGroupIDsByItemID: [:], videoGroupIDsByItemID: [restoredDescriptor.itemID: restoredGroupID],
            presetGroupIDsByItemID: [:], resourceSHA256: [restoredDescriptor.resourceID: restoredDescriptor.sha256],
            independentGroupIDs: [], completedGroups: [restoredGroupID], groupPhases: [restoredGroupID: "committed"], updatedAt: Date())
        let journalRoot = root.appendingPathComponent("journals", isDirectory: true)
        try FileManager.default.createDirectory(at: journalRoot, withIntermediateDirectories: true)
        try JSONEncoder().encode(journal).write(to: journalRoot.appendingPathComponent(transactionID + ".json"))
        let restoredAttachment = try restoredVideoStore.restore(from: restoredVideoURL, descriptor: restoredDescriptor,
            newNoteID: noteID, transactionID: transactionID, attachmentID: UUID(uuidString: restoredVideoID), groupID: restoredGroupID)

        let restoredVaultTransaction = UUID().uuidString.lowercased()
        let restoredVaultItemID = "v-" + String(repeating: "b", count: 32)
        let restoredVaultID = UUID().uuidString.lowercased()
        let restoredVaultJournal = RestoreJournal(transactionID: restoredVaultTransaction,
            archiveSHA256: String(repeating: "e", count: 64), status: "committed",
            stageID: UUID().uuidString.lowercased(), noteIDs: [restoredGroupID: canonicalID],
            noteSHA256ByItemID: [restoredGroupID: noteHash],
            vaultIDs: [restoredVaultItemID: restoredVaultID], videoIDs: [:], presetIDs: [:],
            vaultGroupIDsByItemID: [restoredVaultItemID: restoredGroupID], videoGroupIDsByItemID: [:],
            presetGroupIDsByItemID: [:], resourceSHA256: [:], independentGroupIDs: [],
            completedGroups: [restoredGroupID], groupPhases: [restoredGroupID: "committed"], updatedAt: Date())
        try JSONEncoder().encode(restoredVaultJournal).write(
            to: journalRoot.appendingPathComponent(restoredVaultTransaction + ".json"))
        let restoredVault = try vault.restoreArchiveEntry(id: restoredVaultID, title: "Restored Vault",
            markdown: "restored knowledge", sourceRevisionMS: 9000, createdAtMS: 7000,
            sourceNoteID: "opaque-original-vault-id", sourceState: "linked_note", linkedNoteID: canonicalID,
            transactionID: restoredVaultTransaction, groupID: restoredGroupID)

        let presetStore = UserCoverPresetStore(directory: root.appendingPathComponent("presets", isDirectory: true),
                                               journalRoot: root.appendingPathComponent("journals", isDirectory: true))
        // NoteLibrary derives its group-store root from the custom notes directory's parent.
        let store = try NoteGroupStore(rootURL: root.appendingPathComponent("note-groups", isDirectory: true))
        let stagingRoot = root.appendingPathComponent("capture-staging", isDirectory: true)
        let adapter = NoteGroupLegacyCaptureAdapter(library: library, vault: vault, videoStore: videoStore,
            restoredVideoStore: restoredVideoStore, coverStore: coverStore, presetStore: presetStore,
            store: store, stagingRoot: stagingRoot)

        let initialBody = try Data(contentsOf: library.backupSourceURL(for: note))
        let initialVaultBytes = try Dictionary(uniqueKeysWithValues: vault.notes.map { value in
            (value.id, try Data(contentsOf: vault.backupSourceURL(for: value)))
        })
        XCTAssertEqual(initialVaultBytes.count, 2)
        let initialCoverURL = try XCTUnwrap(coverStore.backupPNGURL(noteID: noteID))
        let initialCover = try Data(contentsOf: initialCoverURL)
        let initialPDF = try Data(contentsOf: XCTUnwrap(library.pdfURL(for: note)))
        let originalMetadataURL = try XCTUnwrap(videoStore.archiveRecords().records.first { $0.attachment.id == originalAttachment.id }?.metadataURL)
        let initialOriginalMetadata = try Data(contentsOf: originalMetadataURL)
        let initialRestoredMetadata = try Data(contentsOf: restoredVideoStore.metadataURL(for: restoredAttachment))
        let preview = try await adapter.preview(noteID: noteID)
        let stablePreview = try await adapter.preview(noteID: noteID)
        XCTAssertEqual(stablePreview, preview, "A second identical capture must produce the same approval witnesses")

        note.title = "Changed between preview and confirmation"; note.updatedAt += 1
        try library.save(note)
        do {
            _ = try await adapter.adopt(preview)
            XCTFail("A changed saved body must invalidate the explicit preview")
        } catch {
            XCTAssertEqual(error as? NoteGroupFacadeError, .sourceChanged)
        }
        XCTAssertNil(try store.readCurrentIfPresent(localNoteID: canonicalID, lineageID: canonicalID))

        note.title = "Legacy full media"; note.updatedAt -= 1
        try library.save(note)
        let confirmed = try await adapter.preview(noteID: noteID)
        let token = try await adapter.adopt(confirmed)
        guard case .current(let currentToken, let members) = try adapter.readCurrent(noteID: noteID, lineageID: canonicalID) else {
            return XCTFail("Expected a single current full-media generation")
        }
        XCTAssertEqual(token, currentToken)
        XCTAssertEqual(members.filter { $0.record.role == .body }.count, 1)
        XCTAssertEqual(members.filter { $0.record.role == .pdf }.count, 1)
        XCTAssertEqual(members.filter { $0.record.role == .cover }.count, 1)
        XCTAssertEqual(members.filter { $0.record.role == .video }.count, 2)
        XCTAssertEqual(members.filter { $0.record.role == .videoMetadata }.count, 2)
        XCTAssertEqual(members.filter { $0.record.role == .vault }.count, 2)
        XCTAssertEqual(members.filter { $0.record.role == .vaultMetadata }.count, 2)
        XCTAssertEqual(members.filter { $0.record.role == .association }.count, 1)

        let bodyMember = try XCTUnwrap(members.first { $0.record.role == .body })
        XCTAssertEqual(try Data(contentsOf: bodyMember.url), initialBody)
        XCTAssertEqual(try NoteDocument.decode(Data(contentsOf: bodyMember.url)).strokes.count, 1)
        XCTAssertEqual(try Data(contentsOf: XCTUnwrap(members.first { $0.record.role == .pdf }).url), initialPDF)
        XCTAssertEqual(try Data(contentsOf: XCTUnwrap(members.first { $0.record.role == .cover }).url), initialCover)
        let initialVaultMetadata = try members.filter { $0.record.role == .vaultMetadata }.map { try Data(contentsOf: $0.url) }
        XCTAssertEqual(Set(initialVaultMetadata), Set(initialVaultBytes.values))
        let initialVaultBodies = try members.filter { $0.record.role == .vault }.map { try Data(contentsOf: $0.url) }
        XCTAssertEqual(Set(initialVaultBodies), Set([Data("linked legacy knowledge".utf8), Data("restored knowledge".utf8)]))
        let videoBytes = try members.filter { $0.record.role == .video }.map { try Data(contentsOf: $0.url) }
        XCTAssertEqual(Set(videoBytes), Set([originalVideoBytes, restoredVideoBytes]))
        let videoMetadataBytes = try members.filter { $0.record.role == .videoMetadata }.map { try Data(contentsOf: $0.url) }
        XCTAssertEqual(Set(videoMetadataBytes), Set([initialOriginalMetadata, initialRestoredMetadata]))

        let associationMember = try XCTUnwrap(members.first { $0.record.role == .association })
        let association = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: associationMember.url)) as? [String: Any])
        XCTAssertEqual(association["legacyNoteID"] as? String, noteID, "Legacy UUID casing remains in association data")
        let rows = try XCTUnwrap(association["members"] as? [[String: Any]])
        XCTAssertTrue(rows.contains { $0["sourceID"] as? String == originalAttachment.id.uuidString })
        XCTAssertTrue(rows.contains { $0["sourceID"] as? String == restoredAttachment.id.uuidString })
        XCTAssertEqual(rows.filter { $0["role"] as? String == "video" }.count, 2)

        XCTAssertEqual(try Data(contentsOf: library.backupSourceURL(for: note)), initialBody)
        XCTAssertEqual(Set(try vault.notes.map { try Data(contentsOf: vault.backupSourceURL(for: $0)) }),
                       Set(initialVaultBytes.values))
        XCTAssertEqual(try Data(contentsOf: coverStore.backupPNGURL(noteID: noteID)!), initialCover)
        XCTAssertEqual(try Data(contentsOf: library.pdfURL(for: note)!), initialPDF)
        XCTAssertEqual(try Data(contentsOf: videoStore.fileURL(noteID: noteID, attachmentID: originalAttachment.id)), originalVideoBytes)
        XCTAssertEqual(try Data(contentsOf: restoredVideoStore.fileURL(for: restoredAttachment)), restoredVideoBytes)
        XCTAssertEqual(try Data(contentsOf: videoStore.archiveRecords().records.first { $0.attachment.id == originalAttachment.id }!.metadataURL), initialOriginalMetadata)
        XCTAssertEqual(try Data(contentsOf: restoredVideoStore.metadataURL(for: restoredAttachment)), initialRestoredMetadata)

        // Ordinary Vault access now follows the adopted immutable group. Publishing text uses
        // the token opened by this editor, changes only Vault members plus the association, and
        // retains the complete previous revision and all original/restored video witnesses.
        vault.bind(noteLibrary: library)
        let opened = try library.currentGroupSession(noteID: noteID)
        XCTAssertEqual(opened?.groupToken, token)
        let beforeVaultPublish = try library.groupedVaultMembers(basedOn: XCTUnwrap(opened), journalRoot: vault.backupJournalRoot())
        XCTAssertEqual(Set(beforeVaultPublish.map(\.value.markdown)),
                       Set(["linked legacy knowledge", "restored knowledge"]))
        let staleToken = try XCTUnwrap(opened?.groupToken)
        _ = try library.publishVaultSnapshot(using: vault, note: note, markdown: "updated grouped knowledge",
                                             expectedGroupToken: staleToken)
        let updatedSession = try XCTUnwrap(library.currentGroupSession(noteID: noteID))
        let updatedToken = try XCTUnwrap(updatedSession.groupToken)
        XCTAssertNotEqual(updatedToken, staleToken)
        XCTAssertEqual(Set(try library.groupedVaultMembers(basedOn: updatedSession, journalRoot: vault.backupJournalRoot()).map(\.value.markdown)),
                       Set(["updated grouped knowledge", "restored knowledge"]))
        XCTAssertThrowsError(try library.publishVaultSnapshot(using: vault, note: note,
            markdown: "stale overwrite", expectedGroupToken: staleToken)) {
            XCTAssertEqual($0 as? NoteGroupStoreError, .compareAndSwapConflict)
        }
        let oldRevision = try store.readRevision(staleToken)
        XCTAssertEqual(oldRevision.manifest.members.first { $0.role == .vault }?.sha256,
                       members.first { $0.record.role == .vault }?.record.sha256)
        XCTAssertEqual(oldRevision.manifest.members.first { $0.role == .vaultMetadata }?.sha256,
                       members.first { $0.record.role == .vaultMetadata }?.record.sha256)
        let afterVaultPublish = try library.currentGroupSession(noteID: noteID)
        let afterMembers = try XCTUnwrap(afterVaultPublish).members
        XCTAssertEqual(afterMembers.filter { $0.record.role == .video }.map(\.record.sha256).sorted(),
                       members.filter { $0.record.role == .video }.map(\.record.sha256).sorted())
        XCTAssertEqual(afterMembers.filter { $0.record.role == .videoMetadata }.map(\.record.sha256).sorted(),
                       members.filter { $0.record.role == .videoMetadata }.map(\.record.sha256).sorted())

        // A grouped snapshot is the backup authority even if the former sidecar is damaged.
        let legacyVaultURL = try vault.backupSourceURL(for: XCTUnwrap(vault.notes.first(where: { $0.archiveOrigin == nil })))
        try Data("stale sidecar".utf8).write(to: legacyVaultURL, options: .atomic)
        vault.reload()
        let backupDirectory = root.appendingPathComponent("grouped-vault-backup", isDirectory: true)
        let backup = try await LibraryBackupSnapshot.capture(library: library, vault: vault,
            videoStore: videoStore, presetStore: presetStore, restoredVideoStore: restoredVideoStore,
            coverStore: coverStore, into: backupDirectory)
        XCTAssertEqual(backup.manifest.vaultEntries.count, 2)
        let payloads: [LibraryBackupVaultPayload] = try backup.manifest.vaultEntries.map { entry in
            let url = try XCTUnwrap(backup.resourceFiles[entry.resourceID])
            let descriptor = try XCTUnwrap(backup.manifest.resources.first { $0.resourceID == entry.resourceID })
            return try LibraryBackupRestoreCoordinator.readVerifiedVaultPayload(at: url,
                descriptor: descriptor, entry: entry)
        }
        XCTAssertEqual(Set(payloads.map(\.markdown)), Set(["updated grouped knowledge", "restored knowledge"]))
        XCTAssertTrue(payloads.contains { $0.markdown == "updated grouped knowledge" && $0.sourceNoteID == noteID })
        XCTAssertTrue(payloads.contains { $0.markdown == "restored knowledge" && $0.sourceNoteID == "opaque-original-vault-id" })
        XCTAssertEqual(backup.manifest.videoAttachments.count, 2)
        let stagedVideoCount = backup.manifest.videoAttachments.reduce(into: 0) { count, video in
            if backup.resourceFiles[video.resourceID] != nil { count += 1 }
        }
        XCTAssertEqual(stagedVideoCount, 2)
        let updatedRevision = try store.readRevision(updatedToken)
        let currentVaultValues = try library.groupedVaultMembers(basedOn: updatedSession,
            journalRoot: vault.backupJournalRoot())
        let selectedVault = try XCTUnwrap(currentVaultValues.first { $0.value.markdown == "updated grouped knowledge" })
        XCTAssertThrowsError(try vault.delete(selectedVault.value, expectedGroupToken: staleToken)) {
            XCTAssertEqual($0 as? NoteGroupStoreError, .compareAndSwapConflict)
        }
        XCTAssertThrowsError(try vault.delete(selectedVault.value, expectedGroupToken: nil)) {
            XCTAssertEqual($0 as? NoteGroupStoreError, .compareAndSwapConflict)
        }
        XCTAssertThrowsError(try vault.rollbackPartialArchiveEntry(id: restoredVault.id,
            transactionID: restoredVaultTransaction, groupID: restoredGroupID)) {
            XCTAssertEqual($0 as? NoteGroupStoreError, .compareAndSwapConflict,
                "Restore rollback cannot remove a legacy sidecar while the committed group owns it")
        }
        try vault.delete(selectedVault.value, expectedGroupToken: updatedToken)
        let afterDelete = try XCTUnwrap(library.currentGroupSession(noteID: noteID))
        XCTAssertNotEqual(afterDelete.groupToken, updatedToken)
        let remainingVault = try library.groupedVaultMembers(basedOn: afterDelete, journalRoot: vault.backupJournalRoot())
        XCTAssertEqual(remainingVault.map(\.value.markdown), ["restored knowledge"])
        XCTAssertEqual(remainingVault.first?.value.archiveSourceNoteID, "opaque-original-vault-id")
        let deletedRevision = try store.readRevision(updatedToken)
        XCTAssertEqual(deletedRevision.manifest.members.map(\.sha256).sorted(),
                       updatedRevision.manifest.members.map(\.sha256).sorted(),
                       "Deleting a current Vault entry must retain its complete immutable prior revision")
        let afterDeleteMembers = afterDelete.members
        for role in [NoteGroupMemberRole.body, .pdf, .cover, .video, .videoMetadata] {
            XCTAssertEqual(afterDeleteMembers.filter { $0.record.role == role }.map(\.record.sha256).sorted(),
                           updatedSession.members.filter { $0.record.role == role }.map(\.record.sha256).sorted(),
                           "Vault deletion must preserve all non-Vault (role.rawValue) members")
        }
        let deleteBackup = try await LibraryBackupSnapshot.capture(library: library, vault: vault,
            videoStore: videoStore, presetStore: presetStore, restoredVideoStore: restoredVideoStore,
            coverStore: coverStore, into: root.appendingPathComponent("after-vault-delete-backup", isDirectory: true))
        XCTAssertEqual(deleteBackup.manifest.vaultEntries.count, 1)
        let deletedBackupEntry = try XCTUnwrap(deleteBackup.manifest.vaultEntries.first)
        XCTAssertEqual(deletedBackupEntry.sourceNoteID, "opaque-original-vault-id")
        XCTAssertEqual(deleteBackup.manifest.videoAttachments.count, 2)
        let groupedMetadataURL = try XCTUnwrap(afterMembers.first { $0.record.role == .vaultMetadata }?.url)
        try Data("corrupt grouped metadata".utf8).write(to: groupedMetadataURL, options: .atomic)
        XCTAssertThrowsError(try library.groupedVaultMembers(basedOn: updatedSession, journalRoot: vault.backupJournalRoot())) {
            XCTAssertEqual($0 as? NoteGroupStoreError, .corruptRevision,
                "A corrupt current Vault member must fail closed instead of falling back to the damaged sidecar")
        }
    }

    private func mediaType(for role: NoteGroupMemberRole) -> String {
        switch role {
        case .body, .association: return "application/json"
        case .pdf: return "application/pdf"
        case .cover: return "image/png"
        case .video: return "video/mp4"
        case .videoMetadata: return "application/json"
        case .vault: return "text/markdown"
        case .vaultMetadata: return "application/json"
        }
    }

    @MainActor
    func testEditorVaultSnapshotUsesCurrentGroupAndLegacyControlForAIAndVideoEntry() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(
            "editor-vault-authority-\(UUID().uuidString.lowercased())", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true,
                                                attributes: [.posixPermissions: 0o700])
        defer { try? FileManager.default.removeItem(at: root) }

        let notes = root.appendingPathComponent("notes", isDirectory: true)
        let library = NoteLibrary(directory: notes)
        let groupedNote = NoteDocument(title: "Grouped Vault editor source")
        try library.save(groupedNote)
        let store = try installGroupedVault(groupedNote, markdown: "CURRENT GROUP KNOWLEDGE", root: root)

        let legacyNote = NoteDocument(title: "Legacy Vault editor source")
        try library.save(legacyNote)
        let vaultDirectory = root.appendingPathComponent("vault", isDirectory: true)
        let vault = VaultLibrary(directory: vaultDirectory, journalRoot: root.appendingPathComponent("journals", isDirectory: true))
        let staleSidecar = VaultNote(id: groupedNote.id, title: groupedNote.title, markdown: "STALE LEGACY SIDECAR",
                                     sourceUpdatedAt: groupedNote.updatedAt - 10_000, createdAt: Date(timeIntervalSince1970: 1))
        try FileManager.default.createDirectory(at: vaultDirectory, withIntermediateDirectories: true,
                                                attributes: [.posixPermissions: 0o700])
        try JSONEncoder().encode(staleSidecar).write(to: vaultDirectory.appendingPathComponent(vaultFixtureFilename(for: groupedNote.id)),
                                                     options: .atomic)
        try vault.save(note: legacyNote, markdown: "LEGACY CONTROL KNOWLEDGE")
        vault.reload()
        XCTAssertTrue(vault.notes.contains { $0.id == groupedNote.id && $0.markdown == "STALE LEGACY SIDECAR" },
                      "The fixture reproduces the editor's unbound legacy-only VaultLibrary input")

        let snapshot = try library.editorVaultSnapshot(using: vault, currentNoteID: groupedNote.id)
        let grouped = try XCTUnwrap(snapshot.first { $0.ownerLocalNoteID == groupedNote.id.lowercased() })
        XCTAssertEqual(grouped.value.markdown, "CURRENT GROUP KNOWLEDGE")
        XCTAssertFalse(snapshot.contains { $0.value.markdown == "STALE LEGACY SIDECAR" })
        XCTAssertEqual(snapshot.first { $0.value.id == legacyNote.id }?.value.markdown, "LEGACY CONTROL KNOWLEDGE")

        // These are the same two projections used by NoteEditorView.openAI and beginVideoEntry.
        let aiEntries = snapshot.map {
            NoteToolVaultEntry(id: $0.value.id, title: $0.value.title, markdown: $0.value.markdown,
                               sourceRevision: $0.value.sourceUpdatedAt)
        }
        XCTAssertEqual(aiEntries.first { $0.id == groupedNote.id }?.markdown, "CURRENT GROUP KNOWLEDGE")
        let videoEntry = try XCTUnwrap(library.editorVaultEntry(forCurrentNoteID: groupedNote.id, in: snapshot))
        XCTAssertEqual(videoEntry.markdown, "CURRENT GROUP KNOWLEDGE")
        XCTAssertNotNil(try store.currentGroupTokens().first { $0.localNoteID == groupedNote.id.lowercased() })
    }

    @MainActor
    func testEditorVaultSnapshotFailsClosedForRetiredAndCorruptGroupMarkers() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(
            "editor-vault-authority-fail-closed-\(UUID().uuidString.lowercased())", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true,
                                                attributes: [.posixPermissions: 0o700])
        defer { try? FileManager.default.removeItem(at: root) }

        let notes = root.appendingPathComponent("notes", isDirectory: true)
        let library = NoteLibrary(directory: notes)
        let note = NoteDocument(title: "Retired Vault editor source")
        try library.save(note)
        let store = try installGroupedVault(note, markdown: "CURRENT GROUP KNOWLEDGE", root: root)
        let vaultDirectory = root.appendingPathComponent("vault", isDirectory: true)
        try FileManager.default.createDirectory(at: vaultDirectory, withIntermediateDirectories: true,
                                                attributes: [.posixPermissions: 0o700])
        let staleSidecar = VaultNote(id: note.id, title: note.title, markdown: "STALE LEGACY SIDECAR",
                                     sourceUpdatedAt: note.updatedAt - 10_000, createdAt: Date(timeIntervalSince1970: 1))
        try JSONEncoder().encode(staleSidecar).write(to: vaultDirectory.appendingPathComponent(vaultFixtureFilename(for: note.id)),
                                                     options: .atomic)
        let vault = VaultLibrary(directory: vaultDirectory, journalRoot: root.appendingPathComponent("journals", isDirectory: true))
        let active = try XCTUnwrap(library.currentGroupSession(noteID: note.id)?.groupToken)

        _ = try store.tombstone(localNoteID: active.localNoteID, lineageID: active.lineageID, expected: active)
        vault.reload()
        XCTAssertThrowsError(try library.editorVaultSnapshot(using: vault, currentNoteID: note.id)) {
            XCTAssertEqual($0 as? NoteGroupOrdinaryAccessError, .retired)
        }

        let marker = root.appendingPathComponent("note-groups/groups/\(active.lineageID)/current.json")
        try Data("corrupt-current-marker".utf8).write(to: marker, options: .atomic)
        XCTAssertThrowsError(try library.editorVaultSnapshot(using: vault, currentNoteID: note.id)) {
            XCTAssertEqual($0 as? NoteGroupStoreError, .corruptRevision)
        }
    }

    @MainActor
    func testEditorVideoLookupUsesLocalOwnerForRestoredArchiveValue() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(
            "editor-vault-restored-owner-\(UUID().uuidString.lowercased())", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true,
                                                attributes: [.posixPermissions: 0o700])
        defer { try? FileManager.default.removeItem(at: root) }

        let library = NoteLibrary(directory: root.appendingPathComponent("notes", isDirectory: true))
        let note = NoteDocument(title: "Restored linked Vault owner")
        try library.save(note)
        let localID = try NoteGroupFacade.foundationUUID(note.id)
        let groupID = "i-" + String(repeating: "c", count: 32)
        let itemID = "v-" + String(repeating: "b", count: 32)
        let transactionID = UUID().uuidString.lowercased()
        let restoredValueID = UUID().uuidString.lowercased()
        let value = VaultNote(id: restoredValueID, title: "Imported knowledge", markdown: "CURRENT RESTORED GROUP VALUE",
            sourceUpdatedAt: note.updatedAt, createdAt: Date(timeIntervalSince1970: 7_000),
            archiveOrigin: "restored_archive", archiveSourceNoteID: "opaque-source-note",
            archiveLinkedNoteID: localID.uppercased(), archiveSourceState: "linked_note",
            restoreTransactionID: transactionID, restoreGroupID: groupID)
        let store = try installGroupedVault(note, value: value, root: root)

        let journalRoot = root.appendingPathComponent("journals", isDirectory: true)
        try FileManager.default.createDirectory(at: journalRoot, withIntermediateDirectories: true,
                                                attributes: [.posixPermissions: 0o700])
        let journal = RestoreJournal(transactionID: transactionID, archiveSHA256: String(repeating: "e", count: 64),
            status: "committed", stageID: UUID().uuidString.lowercased(),
            noteIDs: [groupID: localID], noteSHA256ByItemID: [groupID: Self.sha(try note.validated().encoded())],
            vaultIDs: [itemID: restoredValueID], videoIDs: [:], presetIDs: [:],
            vaultGroupIDsByItemID: [itemID: groupID], videoGroupIDsByItemID: [:], presetGroupIDsByItemID: [:],
            resourceSHA256: [:], independentGroupIDs: [], completedGroups: [groupID],
            groupPhases: [groupID: "committed"], updatedAt: Date())
        try JSONEncoder().encode(journal).write(to: journalRoot.appendingPathComponent(transactionID + ".json"))

        let vaultDirectory = root.appendingPathComponent("vault", isDirectory: true)
        try FileManager.default.createDirectory(at: vaultDirectory, withIntermediateDirectories: true,
                                                attributes: [.posixPermissions: 0o700])
        let staleSidecar = VaultNote(id: restoredValueID, title: value.title, markdown: "STALE RESTORED SIDECAR",
            sourceUpdatedAt: value.sourceUpdatedAt, createdAt: value.createdAt,
            archiveOrigin: value.archiveOrigin, archiveSourceNoteID: value.archiveSourceNoteID,
            archiveLinkedNoteID: value.archiveLinkedNoteID, archiveSourceState: value.archiveSourceState,
            restoreTransactionID: transactionID, restoreGroupID: groupID)
        try JSONEncoder().encode(staleSidecar).write(to: vaultDirectory.appendingPathComponent(vaultFixtureFilename(for: restoredValueID)))
        let vault = VaultLibrary(directory: vaultDirectory, journalRoot: journalRoot)

        let caseVariedLocalID = note.id.uppercased()
        let rows = try library.editorVaultSnapshot(using: vault, currentNoteID: caseVariedLocalID)
        let selected = try XCTUnwrap(library.editorVaultEntry(forCurrentNoteID: caseVariedLocalID, in: rows))
        XCTAssertNotEqual(selected.id, note.id, "Imported Vault values have their own archive ID")
        XCTAssertEqual(selected.id, restoredValueID)
        XCTAssertEqual(selected.markdown, "CURRENT RESTORED GROUP VALUE")
        XCTAssertFalse(rows.contains { $0.value.markdown == "STALE RESTORED SIDECAR" })
        XCTAssertNotNil(try store.currentGroupTokens().first { $0.localNoteID == localID })
    }

    @MainActor
    func testHealthyEditorSuppressesUnrelatedRetiredVaultSidecar() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(
            "editor-vault-unrelated-retired-\(UUID().uuidString.lowercased())", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true,
                                                attributes: [.posixPermissions: 0o700])
        defer { try? FileManager.default.removeItem(at: root) }

        let library = NoteLibrary(directory: root.appendingPathComponent("notes", isDirectory: true))
        let healthy = NoteDocument(title: "Healthy editor note")
        let retired = NoteDocument(title: "Retired unrelated note")
        try library.save(healthy)
        try library.save(retired)
        _ = try installGroupedVault(healthy, markdown: "HEALTHY GROUP KNOWLEDGE", root: root)
        let store = try installGroupedVault(retired, markdown: "RETIRED GROUP KNOWLEDGE", root: root)
        let token = try XCTUnwrap(library.currentGroupSession(noteID: retired.id)?.groupToken)
        _ = try store.tombstone(localNoteID: token.localNoteID, lineageID: token.lineageID, expected: token)

        let vaultDirectory = root.appendingPathComponent("vault", isDirectory: true)
        let journalRoot = root.appendingPathComponent("journals", isDirectory: true)
        try FileManager.default.createDirectory(at: vaultDirectory, withIntermediateDirectories: true,
                                                attributes: [.posixPermissions: 0o700])
        let staleSidecar = VaultNote(id: retired.id, title: retired.title, markdown: "STALE RETIRED SIDECAR",
            sourceUpdatedAt: retired.updatedAt - 10_000, createdAt: Date(timeIntervalSince1970: 1))
        try JSONEncoder().encode(staleSidecar).write(to: vaultDirectory.appendingPathComponent(vaultFixtureFilename(for: retired.id)))
        let vault = VaultLibrary(directory: vaultDirectory, journalRoot: journalRoot)

        let rows = try library.editorVaultSnapshot(using: vault, currentNoteID: healthy.id)
        XCTAssertEqual(try library.editorVaultEntry(forCurrentNoteID: healthy.id, in: rows)?.markdown,
                       "HEALTHY GROUP KNOWLEDGE")
        XCTAssertFalse(rows.contains { $0.value.id == retired.id || $0.value.markdown == "STALE RETIRED SIDECAR" })
        XCTAssertThrowsError(try library.editorVaultSnapshot(using: vault, currentNoteID: retired.id)) {
            XCTAssertEqual($0 as? NoteGroupOrdinaryAccessError, .retired)
        }
    }

    private func installGroupedVault(_ note: NoteDocument, markdown: String, root: URL) throws -> NoteGroupStore {
        let value = VaultNote(id: note.id, title: note.title, markdown: markdown,
                              sourceUpdatedAt: note.updatedAt, createdAt: Date(timeIntervalSince1970: 10))
        return try installGroupedVault(note, value: value, root: root)
    }

    private func installGroupedVault(_ note: NoteDocument, value: VaultNote, root: URL) throws -> NoteGroupStore {
        let localID = try NoteGroupFacade.foundationUUID(note.id)
        let ownerID = value.archiveOrigin == "restored_archive"
            ? (value.archiveLinkedNoteID ?? value.archiveSourceNoteID ?? value.id) : value.id
        let sourceNoteID = value.archiveOrigin == "restored_archive"
            ? (value.archiveSourceNoteID ?? value.id) : value.id
        let sourceState = value.archiveOrigin == "restored_archive" ? (value.archiveSourceState ?? "") : "linked_note"
        guard try NoteGroupFacade.foundationUUID(ownerID) == localID else { throw NoteGroupStoreError.corruptRevision }
        let canonicalSourceID = (try? NoteGroupFacade.foundationUUID(value.id)) ?? value.id
        let bodyID = groupTestMemberID(note: localID, key: "vault-body:\(canonicalSourceID)")
        let metadataID = groupTestMemberID(note: localID, key: "vault-record:\(canonicalSourceID)")
        let associationID = groupTestMemberID(note: localID, key: "association-index")
        let metadataBytes = try JSONEncoder().encode(value)
        let metadataSHA = Self.sha(metadataBytes)
        let stage = root.appendingPathComponent("grouped-vault-input-\(UUID().uuidString.lowercased())", isDirectory: true)
        try FileManager.default.createDirectory(at: stage, withIntermediateDirectories: false,
                                                attributes: [.posixPermissions: 0o700])
        defer { try? FileManager.default.removeItem(at: stage) }
        let markdownURL = stage.appendingPathComponent("vault.md")
        let metadataURL = stage.appendingPathComponent("vault.json")
        let associationURL = stage.appendingPathComponent("association.json")
        try Data(value.markdown.utf8).write(to: markdownURL)
        try metadataBytes.write(to: metadataURL)
        let associationRows: [[String: Any]] = [
            ["memberID": bodyID, "role": NoteGroupMemberRole.vault.rawValue,
             "sourceKind": "vault_library_markdown", "sourceID": value.id, "sourceNoteID": sourceNoteID,
             "manifestItemID": NSNull(), "sourceState": sourceState,
             "metadataByteLength": NSNull(), "metadataSHA256": NSNull()],
            ["memberID": metadataID, "role": NoteGroupMemberRole.vaultMetadata.rawValue,
             "sourceKind": "vault_library_exact_json", "sourceID": value.id, "sourceNoteID": sourceNoteID,
             "manifestItemID": NSNull(), "sourceState": sourceState,
             "metadataByteLength": metadataBytes.count, "metadataSHA256": metadataSHA]
        ]
        try JSONSerialization.data(withJSONObject: ["schemaVersion": 1, "localNoteID": localID,
            "legacyNoteID": note.id, "members": associationRows], options: [.sortedKeys]).write(to: associationURL)
        let store = try NoteGroupStore(rootURL: root.appendingPathComponent("note-groups", isDirectory: true))
        _ = try store.commit(localNoteID: localID, lineageID: localID, expected: nil, members: [
            .init(id: localID, role: .body, mediaType: "application/json",
                  sourceURL: root.appendingPathComponent("notes", isDirectory: true).appendingPathComponent("\(note.id).json")),
            .init(id: bodyID, role: .vault, mediaType: "text/markdown", sourceURL: markdownURL),
            .init(id: metadataID, role: .vaultMetadata, mediaType: "application/json", sourceURL: metadataURL),
            .init(id: associationID, role: .association, mediaType: "application/json", sourceURL: associationURL)
        ])
        return store
    }

    private static func sha(_ data: Data) -> String {
        SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }

    private func vaultFixtureFilename(for id: String) -> String {
        Data(id.utf8).base64EncodedString().replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "+", with: "-") + ".json"
    }

    private static func restoredVideoDescriptor(bytes: Data, noteID: String) throws -> LibraryBackupManifest.Video {
        let uuid = UUID().uuidString.lowercased()
        let object: [String: Any] = [
            "item_id": "restored-fixture", "note_item_id": "linked-fixture", "source_state": "linked_note",
            "origin_kind": "computer_task", "source_note_id": noteID, "source_revision_ms": 1,
            "source_revision_precision_ms": 1, "source_bundle_sha256": String(repeating: "b", count: 64),
            "task_payload_sha256": NSNull(), "digest_kind": "source_snapshot_only", "offline_state": "verified_local_copy",
            "task_id": uuid, "remote_task_id": "restored-task",
            "connection_provenance": ["connection_id": uuid, "connection_revision": 1, "kind": "builtin_video",
                                       "transport": NSNull(), "bridge_id": NSNull(), "instance_id": NSNull(), "certificate_sha256": NSNull()],
            "artifact_id": "restored-artifact", "display_name": "restored.mp4", "media_type": "video/mp4",
            "byte_length": bytes.count, "sha256": sha(bytes), "created_at_ms": 1, "resource_id": "restored-resource"
        ]
        let data = try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys, .fragmentsAllowed])
        return try JSONDecoder().decode(LibraryBackupManifest.Video.self, from: data)
    }
}
