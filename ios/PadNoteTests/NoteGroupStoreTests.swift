import XCTest
import Dispatch
import Darwin
@testable import PadNote

final class NoteGroupStoreTests: XCTestCase {
    private final class CommitCounters: @unchecked Sendable {
        private let lock = NSLock()
        private var successCount = 0
        private var conflictCount = 0
        func recordSuccess() { lock.lock(); successCount += 1; lock.unlock() }
        func recordConflict() { lock.lock(); conflictCount += 1; lock.unlock() }
        var values: (Int, Int) { lock.lock(); defer { lock.unlock() }; return (successCount, conflictCount) }
    }
    private func fixture() throws -> (URL, URL, String, String) {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("note-group-store-\(UUID().uuidString)", isDirectory: true)
        let sources = root.appendingPathComponent("sources", isDirectory: true)
        try FileManager.default.createDirectory(at: sources, withIntermediateDirectories: true)
        let body = sources.appendingPathComponent("body.json")
        let pdf = sources.appendingPathComponent("scan.pdf")
        try Data("{\"schema\":8,\"text\":\"synthetic\"}".utf8).write(to: body)
        try Data([0x25, 0x50, 0x44, 0x46, 0x2d, 0x31]).write(to: pdf)
        try Data([0x89, 0x50, 0x4e, 0x47]).write(to: sources.appendingPathComponent("cover.png"))
        try Data([0x00, 0x00, 0x00, 0x18, 0x66, 0x74, 0x79, 0x70]).write(to: sources.appendingPathComponent("clip.mp4"))
        try Data("---\ntitle: local\n---\nSynthetic vault content".utf8).write(to: sources.appendingPathComponent("vault.md"))
        return (root, sources, body.lastPathComponent, pdf.lastPathComponent)
    }

    private func privateDirectory(_ url: URL) throws {
        guard Darwin.mkdir(url.path, 0o700) == 0 else { throw NSError(domain: NSPOSIXErrorDomain, code: Int(errno)) }
    }

    private func sentinelFile(_ url: URL) throws {
        try Data("do-not-follow-or-change".utf8).write(to: url)
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: url.path)
    }

    private func inputs(_ sources: URL, bodyName: String = "body.json") -> [NoteGroupMemberInput] {
        [
            .init(id: UUID().uuidString.lowercased(), role: .body, mediaType: "application/json", sourceURL: sources.appendingPathComponent(bodyName)),
            .init(id: UUID().uuidString.lowercased(), role: .pdf, mediaType: "application/pdf", sourceURL: sources.appendingPathComponent("scan.pdf")),
            .init(id: UUID().uuidString.lowercased(), role: .cover, mediaType: "image/png", sourceURL: sources.appendingPathComponent("cover.png")),
            .init(id: UUID().uuidString.lowercased(), role: .video, mediaType: "video/mp4", sourceURL: sources.appendingPathComponent("clip.mp4")),
            .init(id: UUID().uuidString.lowercased(), role: .vault, mediaType: "text/markdown", sourceURL: sources.appendingPathComponent("vault.md"))
        ]
    }

    func testWholeGroupCommitAndHistoricalExactReadAfterTombstone() throws {
        let (root, sources, _, _) = try fixture(); defer { try? FileManager.default.removeItem(at: root) }
        let local = UUID().uuidString.lowercased(), lineage = UUID().uuidString.lowercased()
        let store = try NoteGroupStore(rootURL: root.appendingPathComponent("store"))
        let first = try store.commit(localNoteID: local, lineageID: lineage, expected: nil, members: inputs(sources), committedAtMS: 10)
        let firstRead = try store.readRevision(first)
        XCTAssertEqual(firstRead.manifest.members.count, 5)
        XCTAssertTrue(firstRead.manifest.members.allSatisfy { $0.ownerLocalNoteID == local })
        XCTAssertFalse(firstRead.manifest.tombstone)
        for member in firstRead.manifest.members {
            let url = try store.memberURL(for: member, in: first)
            let verified = try LibraryBackupArchive.hashFile(url)
            XCTAssertEqual(verified.size, member.byteLength)
            XCTAssertEqual(verified.sha256, member.sha256)
        }
        let history = try store.history(localNoteID: local, lineageID: lineage)
        XCTAssertEqual(history, [first])
        let removed = try store.tombstone(localNoteID: local, lineageID: lineage, expected: first, committedAtMS: 20)
        let current = try store.readCurrent(localNoteID: local, lineageID: lineage)
        XCTAssertEqual(current.token, removed)
        XCTAssertTrue(current.manifest.tombstone)
        XCTAssertTrue(current.manifest.members.isEmpty)
        XCTAssertEqual(try store.readRevision(first).manifest, firstRead.manifest)
        XCTAssertEqual(try store.history(localNoteID: local, lineageID: lineage), [removed, first])
    }

    func testConcurrentInitialCommitHasExactlyOneCASWinner() throws {
        let (root, sources, _, _) = try fixture(); defer { try? FileManager.default.removeItem(at: root) }
        let local = UUID().uuidString.lowercased(), lineage = UUID().uuidString.lowercased()
        let a = try NoteGroupStore(rootURL: root.appendingPathComponent("store"))
        let b = try NoteGroupStore(rootURL: root.appendingPathComponent("store"))
        let gate = DispatchSemaphore(value: 0), group = DispatchGroup(), counters = CommitCounters()
        for store in [a, b] {
            group.enter()
            DispatchQueue.global().async {
                gate.wait()
                do { _ = try store.commit(localNoteID: local, lineageID: lineage, expected: nil, members: self.inputs(sources))
                    counters.recordSuccess()
                } catch NoteGroupStoreError.compareAndSwapConflict {
                    counters.recordConflict()
                } catch { XCTFail("unexpected commit error: \(error)") }
                group.leave()
            }
        }
        gate.signal(); gate.signal(); XCTAssertEqual(group.wait(timeout: .now() + 20), .success)
        let counts = counters.values
        XCTAssertEqual(counts.0, 1); XCTAssertEqual(counts.1, 1)
        XCTAssertEqual(try a.history(localNoteID: local, lineageID: lineage).count, 1)
    }

    func testStaleRevisionCannotReplaceCurrent() throws {
        let (root, sources, _, _) = try fixture(); defer { try? FileManager.default.removeItem(at: root) }
        let local = UUID().uuidString.lowercased(), lineage = UUID().uuidString.lowercased()
        let store = try NoteGroupStore(rootURL: root.appendingPathComponent("store"))
        let base = try store.commit(localNoteID: local, lineageID: lineage, expected: nil, members: inputs(sources))
        let next = try store.commit(localNoteID: local, lineageID: lineage, expected: base, members: inputs(sources), committedAtMS: 30)
        XCTAssertThrowsError(try store.commit(localNoteID: local, lineageID: lineage, expected: base, members: inputs(sources), committedAtMS: 40)) { error in
            XCTAssertEqual(error as? NoteGroupStoreError, .compareAndSwapConflict)
        }
        XCTAssertEqual(try store.readCurrent(localNoteID: local, lineageID: lineage).token, next)
        XCTAssertEqual(try store.history(localNoteID: local, lineageID: lineage).count, 2)
    }

    func testMissingMemberDuringStagingLeavesCurrentAndHistoryUntouched() throws {
        let (root, sources, _, _) = try fixture(); defer { try? FileManager.default.removeItem(at: root) }
        let local = UUID().uuidString.lowercased(), lineage = UUID().uuidString.lowercased()
        let store = try NoteGroupStore(rootURL: root.appendingPathComponent("store"))
        let base = try store.commit(localNoteID: local, lineageID: lineage, expected: nil, members: inputs(sources))
        let brokenInputs = inputs(sources)
        try FileManager.default.removeItem(at: sources.appendingPathComponent("vault.md"))
        XCTAssertThrowsError(try store.commit(localNoteID: local, lineageID: lineage, expected: base, members: brokenInputs))
        XCTAssertEqual(try store.readCurrent(localNoteID: local, lineageID: lineage).token, base)
        XCTAssertEqual(try store.history(localNoteID: local, lineageID: lineage), [base])
        let revisions = root.appendingPathComponent("store/groups/\(lineage)/revisions")
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: revisions.path), [base.revisionID])
    }

    func testInjectedFailureLeavesNoPublishedPartialRevision() throws {
        let (root, sources, _, _) = try fixture(); defer { try? FileManager.default.removeItem(at: root) }
        let local = UUID().uuidString.lowercased(), lineage = UUID().uuidString.lowercased()
        let normal = try NoteGroupStore(rootURL: root.appendingPathComponent("store"))
        let base = try normal.commit(localNoteID: local, lineageID: lineage, expected: nil, members: inputs(sources))
        let failing = try NoteGroupStore(rootURL: root.appendingPathComponent("store"), failureInjector: { _ in throw NoteGroupStoreError.injectedFailure })
        XCTAssertThrowsError(try failing.commit(localNoteID: local, lineageID: lineage, expected: base, members: inputs(sources))) { error in
            XCTAssertEqual(error as? NoteGroupStoreError, .injectedFailure)
        }
        XCTAssertEqual(try normal.readCurrent(localNoteID: local, lineageID: lineage).token, base)
        XCTAssertEqual(try normal.history(localNoteID: local, lineageID: lineage), [base])
        let revisions = root.appendingPathComponent("store/groups/\(lineage)/revisions")
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: revisions.path), [base.revisionID])
    }

    func testRevisionRenameBeforeMarkerKeepsOrphanOutsideCommittedChain() throws {
        let (root, sources, _, _) = try fixture(); defer { try? FileManager.default.removeItem(at: root) }
        let local = UUID().uuidString.lowercased(), lineage = UUID().uuidString.lowercased()
        let normal = try NoteGroupStore(rootURL: root.appendingPathComponent("store"))
        let base = try normal.commit(localNoteID: local, lineageID: lineage, expected: nil, members: inputs(sources))
        let injected = try NoteGroupStore(rootURL: root.appendingPathComponent("store"), failureInjector: { point in
            if case .afterRevisionRenameBeforeMarker = point { throw NoteGroupStoreError.injectedFailure }
        })
        XCTAssertThrowsError(try injected.commit(localNoteID: local, lineageID: lineage, expected: base, members: inputs(sources))) { error in
            XCTAssertEqual(error as? NoteGroupStoreError, .injectedFailure)
        }
        XCTAssertEqual(try normal.readCurrent(localNoteID: local, lineageID: lineage).token, base)
        XCTAssertEqual(try normal.history(localNoteID: local, lineageID: lineage), [base])
        let revisions = root.appendingPathComponent("store/groups/\(lineage)/revisions")
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: revisions.path).filter { !$0.hasPrefix(".") }.count, 2, "the immutable orphan is retained but unreachable from current")
    }

    func testPostMarkerFailureReportsCommittedDurabilityUnconfirmed() throws {
        let (root, sources, _, _) = try fixture(); defer { try? FileManager.default.removeItem(at: root) }
        let local = UUID().uuidString.lowercased(), lineage = UUID().uuidString.lowercased()
        let normal = try NoteGroupStore(rootURL: root.appendingPathComponent("store"))
        let base = try normal.commit(localNoteID: local, lineageID: lineage, expected: nil, members: inputs(sources))
        let injected = try NoteGroupStore(rootURL: root.appendingPathComponent("store"), failureInjector: { point in
            if case .afterMarkerRenameBeforeDirectorySync = point { throw NoteGroupStoreError.injectedFailure }
        })
        var committed: NoteGroupVersionToken?
        XCTAssertThrowsError(try injected.commit(localNoteID: local, lineageID: lineage, expected: base, members: inputs(sources))) { error in
            guard let typed = error as? NoteGroupStoreError,
                  case .committedButDurabilityUnconfirmed(let token) = typed else { return XCTFail("post-marker failure must disclose committed state: \(error)") }
            committed = token
        }
        let current = try normal.readCurrent(localNoteID: local, lineageID: lineage).token
        guard let committed else { return XCTFail("durability outcome must include the published token") }
        XCTAssertEqual(current, committed)
        XCTAssertEqual(try normal.history(localNoteID: local, lineageID: lineage), [current, base])
    }

    func testRejectsSymlinkRootWithoutModifyingExternalSentinel() throws {
        let (root, _, _, _) = try fixture(); defer { try? FileManager.default.removeItem(at: root) }
        let external = root.appendingPathComponent("external-root", isDirectory: true); try privateDirectory(external)
        let sentinel = external.appendingPathComponent("sentinel"); try sentinelFile(sentinel)
        let before = try Data(contentsOf: sentinel)
        let link = root.appendingPathComponent("store-link", isDirectory: true)
        XCTAssertEqual(symlink(external.path, link.path), 0)
        XCTAssertThrowsError(try NoteGroupStore(rootURL: link))
        XCTAssertEqual(try Data(contentsOf: sentinel), before)
        XCTAssertEqual(try FileManager.default.attributesOfItem(atPath: external.path)[.posixPermissions] as? NSNumber, NSNumber(value: 0o700))
    }

    func testRejectsSymlinkGroupsDirectoryWithoutModifyingExternalSentinel() throws {
        let (root, _, _, _) = try fixture(); defer { try? FileManager.default.removeItem(at: root) }
        let storeRoot = root.appendingPathComponent("store", isDirectory: true); try privateDirectory(storeRoot)
        let external = root.appendingPathComponent("external-groups", isDirectory: true); try privateDirectory(external)
        let sentinel = external.appendingPathComponent("sentinel"); try sentinelFile(sentinel); let before = try Data(contentsOf: sentinel)
        XCTAssertEqual(symlink(external.path, storeRoot.appendingPathComponent("groups", isDirectory: true).path), 0)
        XCTAssertThrowsError(try NoteGroupStore(rootURL: storeRoot))
        XCTAssertEqual(try Data(contentsOf: sentinel), before)
        XCTAssertEqual(try FileManager.default.attributesOfItem(atPath: external.path)[.posixPermissions] as? NSNumber, NSNumber(value: 0o700))
    }

    func testRejectsSymlinkLockWithoutModifyingExternalSentinel() throws {
        let (root, _, _, _) = try fixture(); defer { try? FileManager.default.removeItem(at: root) }
        let storeRoot = root.appendingPathComponent("store", isDirectory: true); try privateDirectory(storeRoot)
        try privateDirectory(storeRoot.appendingPathComponent("groups", isDirectory: true))
        let external = root.appendingPathComponent("external-lock"); try sentinelFile(external); let before = try Data(contentsOf: external)
        XCTAssertEqual(symlink(external.path, storeRoot.appendingPathComponent(".group-store.lock").path), 0)
        XCTAssertThrowsError(try NoteGroupStore(rootURL: storeRoot))
        XCTAssertEqual(try Data(contentsOf: external), before)
        XCTAssertEqual(try FileManager.default.attributesOfItem(atPath: external.path)[.posixPermissions] as? NSNumber, NSNumber(value: 0o600))
    }

    func testRejectsSymlinkMemberDirectoryWithoutReadingExternalSentinel() throws {
        let (root, sources, _, _) = try fixture(); defer { try? FileManager.default.removeItem(at: root) }
        let local = UUID().uuidString.lowercased(), lineage = UUID().uuidString.lowercased()
        let store = try NoteGroupStore(rootURL: root.appendingPathComponent("store"))
        let token = try store.commit(localNoteID: local, lineageID: lineage, expected: nil, members: inputs(sources))
        let revision = root.appendingPathComponent("store/groups/\(lineage)/revisions/\(token.revisionID)")
        let members = revision.appendingPathComponent("members", isDirectory: true)
        let saved = revision.appendingPathComponent("members-saved", isDirectory: true)
        try FileManager.default.moveItem(at: members, to: saved)
        let external = root.appendingPathComponent("external-members", isDirectory: true); try privateDirectory(external)
        let sentinel = external.appendingPathComponent("sentinel"); try sentinelFile(sentinel); let before = try Data(contentsOf: sentinel)
        XCTAssertEqual(symlink(external.path, members.path), 0)
        XCTAssertThrowsError(try store.readRevision(token))
        XCTAssertEqual(try Data(contentsOf: sentinel), before)
    }


    func testRejectsExistingNonPrivateRootWithoutChangingModeOrSentinel() throws {
        let (root, _, _, _) = try fixture(); defer { try? FileManager.default.removeItem(at: root) }
        let storeRoot = root.appendingPathComponent("existing-root", isDirectory: true)
        XCTAssertEqual(Darwin.mkdir(storeRoot.path, 0o700), 0)
        let sentinel = storeRoot.appendingPathComponent("sentinel"); try sentinelFile(sentinel)
        XCTAssertEqual(chmod(storeRoot.path, 0o755), 0)
        let contents = try Data(contentsOf: sentinel)
        XCTAssertThrowsError(try NoteGroupStore(rootURL: storeRoot))
        XCTAssertEqual(try Data(contentsOf: sentinel), contents)
        XCTAssertEqual((try FileManager.default.attributesOfItem(atPath: storeRoot.path)[.posixPermissions] as? NSNumber)?.intValue, 0o755)
    }

    func testRejectsExistingNonPrivateGroupsWithoutChangingModeOrSentinel() throws {
        let (root, _, _, _) = try fixture(); defer { try? FileManager.default.removeItem(at: root) }
        let storeRoot = root.appendingPathComponent("store", isDirectory: true); try privateDirectory(storeRoot)
        let groups = storeRoot.appendingPathComponent("groups", isDirectory: true); try privateDirectory(groups)
        let sentinel = groups.appendingPathComponent("sentinel"); try sentinelFile(sentinel)
        XCTAssertEqual(chmod(groups.path, 0o755), 0)
        let contents = try Data(contentsOf: sentinel)
        XCTAssertThrowsError(try NoteGroupStore(rootURL: storeRoot))
        XCTAssertEqual(try Data(contentsOf: sentinel), contents)
        XCTAssertEqual((try FileManager.default.attributesOfItem(atPath: groups.path)[.posixPermissions] as? NSNumber)?.intValue, 0o755)
    }

    func testRejectsExistingNonPrivateLockWithoutChangingModeOrContents() throws {
        let (root, _, _, _) = try fixture(); defer { try? FileManager.default.removeItem(at: root) }
        let storeRoot = root.appendingPathComponent("store", isDirectory: true); try privateDirectory(storeRoot)
        try privateDirectory(storeRoot.appendingPathComponent("groups", isDirectory: true))
        let lock = storeRoot.appendingPathComponent(".group-store.lock"); try sentinelFile(lock)
        XCTAssertEqual(chmod(lock.path, 0o644), 0)
        let contents = try Data(contentsOf: lock)
        XCTAssertThrowsError(try NoteGroupStore(rootURL: storeRoot))
        XCTAssertEqual(try Data(contentsOf: lock), contents)
        XCTAssertEqual((try FileManager.default.attributesOfItem(atPath: lock.path)[.posixPermissions] as? NSNumber)?.intValue, 0o644)
    }

    func testRejectsWorldWritableParentWithoutCreatingStoreRoot() throws {
        let (root, _, _, _) = try fixture(); defer { try? FileManager.default.removeItem(at: root) }
        let parent = root.appendingPathComponent("unsafe-parent", isDirectory: true); try privateDirectory(parent)
        XCTAssertEqual(chmod(parent.path, 0o777), 0)
        let storeRoot = parent.appendingPathComponent("store", isDirectory: true)
        XCTAssertThrowsError(try NoteGroupStore(rootURL: storeRoot))
        XCTAssertFalse(FileManager.default.fileExists(atPath: storeRoot.path))
        XCTAssertEqual((try FileManager.default.attributesOfItem(atPath: parent.path)[.posixPermissions] as? NSNumber)?.intValue, 0o777)
    }


    func testTombstoneRenameBeforeMarkerKeepsOldCurrentAndHistory() throws {
        let (root, sources, _, _) = try fixture(); defer { try? FileManager.default.removeItem(at: root) }
        let local = UUID().uuidString.lowercased(), lineage = UUID().uuidString.lowercased()
        let normal = try NoteGroupStore(rootURL: root.appendingPathComponent("store"))
        let base = try normal.commit(localNoteID: local, lineageID: lineage, expected: nil, members: inputs(sources))
        let injected = try NoteGroupStore(rootURL: root.appendingPathComponent("store"), failureInjector: { point in
            if case .afterRevisionRenameBeforeMarker = point { throw NoteGroupStoreError.injectedFailure }
        })
        XCTAssertThrowsError(try injected.tombstone(localNoteID: local, lineageID: lineage, expected: base)) { error in
            XCTAssertEqual(error as? NoteGroupStoreError, .injectedFailure)
        }
        XCTAssertEqual(try normal.readCurrent(localNoteID: local, lineageID: lineage).token, base)
        XCTAssertEqual(try normal.history(localNoteID: local, lineageID: lineage), [base])
        let revisions = root.appendingPathComponent("store/groups/\(lineage)/revisions")
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: revisions.path).filter { !$0.hasPrefix(".") }.count, 2)
    }

}
