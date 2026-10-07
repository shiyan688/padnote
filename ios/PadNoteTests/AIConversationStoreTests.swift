import XCTest
@testable import PadNote

@MainActor
final class AIConversationStoreTests: XCTestCase {
    private final class WriteGate: @unchecked Sendable {
        private let condition = NSCondition()
        private var enabled = false
        private var released = false
        var onBlocked: (() -> Void)?
        func arm() { condition.lock(); enabled = true; condition.unlock() }
        func unblock() { condition.lock(); released = true; condition.broadcast(); condition.unlock() }
        func inject(_ point: NoteLibraryFaultPoint, _: URL) -> Error? {
            condition.lock(); defer { condition.unlock() }
            guard enabled, point == .afterTemporaryWrite, !released else { return nil }
            enabled = false
            onBlocked?()
            while !released { condition.wait() }
            return nil
        }
    }

    private final class FailOnce: @unchecked Sendable {
        private let lock = NSLock()
        private var armed = false
        func arm() { lock.lock(); armed = true; lock.unlock() }
        func inject(_ point: NoteLibraryFaultPoint, _: URL) -> Error? {
            lock.lock(); defer { lock.unlock() }
            guard armed, point == .afterTemporaryWrite else { return nil }
            armed = false
            return NSError(domain: "AIConversationStoreTests", code: 77)
        }
    }
    private func temporaryDirectory() throws -> URL {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("AIConversationStoreTests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        addTeardownBlock { try? FileManager.default.removeItem(at: url) }
        return url
    }

    private func source(pdf: String? = nil) -> AIConversationSource {
        AIConversationSource(imagePNG: nil, page: 0, bounds: nil, noteContext: "",
                             vaultEntries: [], pdfDigest: pdf)
    }

    private func recipient(_ value: String = "a") -> AIRecipientIdentity {
        AIRecipientIdentity(fingerprint: String(repeating: value, count: 64), display: "fixture-\(value)")
    }

    private func record(note: NoteDocument, epoch: Int = 0) throws -> AIConversationRecord {
        AIConversationRecord(noteID: note.id, storageEpoch: epoch,
            visibleTurns: [.init(role: "user", content: "可见历史")],
            wireMessages: [.init(role: "user", content: "WIRE")], source: source(),
            expectedDocumentDigest: try AIConversationDigest.document(note),
            recipient: recipient(), permission: .readOnly)
    }

    func testRoundTripKeepsVisibleAndWireHistorySeparate() throws {
        let directory = try temporaryDirectory()
        let store = AIConversationStore(directory: directory)
        let note = NoteDocument(id: "conversation-roundtrip", title: "会话")
        let value = try record(note: note)
        try store.save(value)
        XCTAssertEqual(try directory.resourceValues(forKeys: [.isExcludedFromBackupKey]).isExcludedFromBackup, true)
        let loaded = try XCTUnwrap(store.load(noteID: note.id))
        XCTAssertEqual(loaded.visibleTurns.map(\.content), ["可见历史"])
        XCTAssertEqual(loaded.wireMessages.map(\.content), ["WIRE"])
        XCTAssertNil(loaded.wireMessages.first?.imageDataURL)
        var conflicting = loaded
        conflicting.reply = "same-generation-conflict"
        XCTAssertThrowsError(try store.save(conflicting)) {
            XCTAssertEqual($0 as? AIConversationStoreError, .stale)
        }
    }

    func testDeletedProfileStillRestoresVisibleConversationWithoutAuthorizingSend() async throws {
        let store = AIConversationStore(directory: try temporaryDirectory())
        let note = NoteDocument(id: "conversation-no-profile", title: "档案已删除")
        try store.save(record(note: note))
        let controller = AIConversationController(store: store)
        await controller.open(note: note, source: source(), recipient: nil, permission: .readOnly)
        XCTAssertEqual(controller.record?.visibleTurns.map(\.content), ["可见历史"])
        await XCTAssertThrowsErrorAsync {
            _ = try await controller.beginRequest(liveDocument: note,
                recipient: self.recipient("b"), permission: .readOnly)
        }
    }

    func testClearEpochRejectsResidualFilesAndOldHighGenerationWriters() throws {
        let directory = try temporaryDirectory()
        let store = AIConversationStore(directory: directory)
        let note = NoteDocument(id: "conversation-clear", title: "清空")
        var old = try record(note: note)
        try store.save(old)
        let canonical = directory.appendingPathComponent("\(note.id).ai-session.json")
        let oldBytes = try Data(contentsOf: canonical)
        try store.clear(old)
        try oldBytes.write(to: canonical)
        try oldBytes.write(to: NoteAtomicFile.sidecar(canonical, ".tmp"))
        XCTAssertNil(try store.load(noteID: note.id), "old epoch remnants must not be promoted")
        old.generation = 999_999_999
        XCTAssertThrowsError(try store.save(old))

        var replacement = try record(note: note, epoch: store.currentEpoch(noteID: note.id))
        replacement = AIConversationRecord(id: UUID(), noteID: note.id,
            storageEpoch: replacement.storageEpoch, source: source(),
            expectedDocumentDigest: replacement.expectedDocumentDigest,
            recipient: recipient("b"), permission: .readOnly)
        try store.save(replacement)
        try store.clear(replacement)
        XCTAssertThrowsError(try store.save(old), "a second clear must still reject the original identity")
    }

    func testCorruptCanonicalCannotBeOverwrittenByFallbackRecord() async throws {
        let directory = try temporaryDirectory()
        let note = NoteDocument(id: "conversation-corrupt", title: "损坏")
        let target = directory.appendingPathComponent("\(note.id).ai-session.json")
        let damaged = Data("CORRUPT-CONVERSATION-PRIVATE".utf8)
        try damaged.write(to: target)
        let store = AIConversationStore(directory: directory)
        let controller = AIConversationController(store: store)
        await controller.open(note: note, source: source(), recipient: recipient(), permission: .readOnly)
        XCTAssertEqual(controller.recoveryFile, target)
        await controller.persistPresentation(reply: "should-not-replace", transcript: nil, mutation: nil)
        XCTAssertEqual(try Data(contentsOf: target), damaged)
        XCTAssertNotNil(controller.persistenceError)
    }

    func testControllerColdRestoreAdvancesOnlyCommittedSource() async throws {
        let store = AIConversationStore(directory: try temporaryDirectory())
        var note = NoteDocument(id: "conversation-controller", title: "连续工具")
        let controller = AIConversationController(store: store)
        await controller.open(note: note, source: source(), recipient: recipient(), permission: .createInFreeSpace)
        let context = try await controller.beginRequest(liveDocument: note, recipient: recipient(),
                                                        permission: .createInFreeSpace)
        var proposed = note
        proposed.textFlows = [NoteTextFlow(id: "ai-flow", source: "AI result")]
        let mutation = try XCTUnwrap(try NoteAIMutation(original: note, proposed: proposed))
        let message = AIConversationMessage(role: "user", content: "write",
                                            imageDataURL: "data:image/png;base64,AAAA")
        let outcome = NoteAIConversation.Outcome(reply: "done",
            messages: [message, .init(role: "assistant", content: "done")],
            note: proposed, toolCount: 1, validToolCallCount: 1)
        try await controller.acceptRound(context: context, message: message, outcome: outcome,
            commit: NoteAIToolCommit(mutation: mutation, document: proposed), liveDocument: proposed,
            transcript: nil, executor: "fixture")

        let reopened = AIConversationController(store: store)
        await reopened.open(note: proposed, source: source(), recipient: recipient(), permission: .createInFreeSpace)
        XCTAssertFalse(reopened.continuationBlocked)
        XCTAssertEqual(reopened.record?.lastMutation, mutation)
        XCTAssertEqual(reopened.record?.capability, .toolsVerified)
        XCTAssertTrue(reopened.record?.wireMessages.allSatisfy { $0.imageDataURL == nil } == true,
                      "the frozen source owns image bytes; wire history must not duplicate them")
        _ = try await reopened.beginRequest(liveDocument: proposed, recipient: recipient(),
                                            permission: .createInFreeSpace)

        note = proposed
        note.strokes = [InkStroke(id: "later-handwriting", points: [InkPoint(x: 10, y: 10)])]
        await XCTAssertThrowsErrorAsync {
            _ = try await reopened.beginRequest(liveDocument: note, recipient: self.recipient(),
                                                permission: .createInFreeSpace)
        }

        await reopened.rotate(permission: .readOnly, liveDocument: proposed,
                              reason: "写入权限已自动回落")
        XCTAssertEqual(reopened.record?.lastMutation, mutation,
                       "wire rotation must not remove the cold-restorable result card")
        XCTAssertEqual(reopened.record?.reply, "done")
        XCTAssertTrue(reopened.record?.wireMessages.isEmpty == true)
    }

    func testRecipientRotationPreservesVisibleButDropsWireAndDoesNotAuthorizeExternalEdit() async throws {
        let store = AIConversationStore(directory: try temporaryDirectory())
        let note = NoteDocument(id: "conversation-rotate", title: "边界")
        var seeded = try record(note: note)
        seeded.permission = .readOnly
        try store.save(seeded)
        let controller = AIConversationController(store: store)
        await controller.open(note: note, source: source(), recipient: recipient(), permission: .readOnly)
        var edited = note
        edited.strokes = [InkStroke(id: "external", points: [InkPoint(x: 1, y: 1)])]
        await controller.rotate(recipient: recipient("b"), liveDocument: edited, reason: "接收者变化")
        XCTAssertTrue(controller.continuationBlocked)
        XCTAssertEqual(controller.record?.visibleTurns.first?.content, "可见历史")
        XCTAssertTrue(controller.record?.wireMessages.isEmpty == true)
        XCTAssertNil(controller.record?.lastMutation)
        XCTAssertNotNil(controller.expectedToolNote, "the last verified baseline remains available for review")
        await XCTAssertThrowsErrorAsync {
            _ = try await controller.beginRequest(liveDocument: edited, recipient: self.recipient("b"),
                                                  permission: .readOnly)
        }
    }

    func testPDFNoteWithoutReadableDigestCannotBeginOrExplicitlyRebind() async throws {
        let store = AIConversationStore(directory: try temporaryDirectory())
        let note = NoteDocument(id: "conversation-pdf", title: "PDF", pageCount: 1, pdfPageCount: 1)
        let controller = AIConversationController(store: store)
        await controller.open(note: note, source: source(pdf: nil), recipient: recipient(), permission: .readOnly)
        XCTAssertTrue(controller.continuationBlocked)
        await controller.rotate(source: source(pdf: nil), liveDocument: note,
                                reason: "bad pdf", explicitlyRebindSource: true)
        XCTAssertTrue(controller.continuationBlocked)
        await XCTAssertThrowsErrorAsync {
            _ = try await controller.beginRequest(liveDocument: note, recipient: self.recipient(), permission: .readOnly)
        }
    }

    func testPreflightRejectsPaidRequestWhenReservedReplyCannotBePersisted() throws {
        let store = AIConversationStore(directory: try temporaryDirectory())
        let note = NoteDocument(id: "conversation-budget", title: "预算")
        var value = try record(note: note)
        let block = String(repeating: "x", count: 12_000)
        value.visibleTurns = (0..<50).map { _ in .init(role: "user", content: block) }
        value.wireMessages = (0..<50).map { _ in .init(role: "user", content: block) }
        try store.save(value)
        XCTAssertThrowsError(try store.preflight(value, message: .init(role: "user", content: "new"))) {
            XCTAssertEqual($0 as? AIConversationStoreError, .tooLarge)
        }
        XCTAssertEqual(try store.load(noteID: note.id), value)
    }

    func testLateRotateCompletionCannotResurrectClearedSession() async throws {
        let directory = try temporaryDirectory()
        let gate = WriteGate()
        let store = AIConversationStore(directory: directory, faultInjector: gate.inject)
        let note = NoteDocument(id: "conversation-race", title: "竞态")
        let controller = AIConversationController(store: store)
        await controller.open(note: note, source: source(), recipient: recipient(), permission: .readOnly)
        let blocked = expectation(description: "rotation save reached disk")
        gate.onBlocked = { blocked.fulfill() }
        gate.arm()
        let rotation = Task { @MainActor in
            await controller.rotate(recipient: self.recipient("b"), liveDocument: note, reason: "rotate")
        }
        await fulfillment(of: [blocked], timeout: 2)
        let clearing = Task { @MainActor in await controller.clear() }
        await Task.yield()
        gate.unblock()
        await rotation.value
        let didClear = await clearing.value
        XCTAssertTrue(didClear)
        XCTAssertNil(controller.record)
        XCTAssertNil(try store.load(noteID: note.id))
    }

    func testSaveFailureKeepsCompletedAnswerAndCreatesExportableRecoveryCopy() async throws {
        let fault = FailOnce()
        let store = AIConversationStore(directory: try temporaryDirectory(), faultInjector: fault.inject)
        let note = NoteDocument(id: "conversation-save-failure", title: "保存失败")
        let controller = AIConversationController(store: store)
        await controller.open(note: note, source: source(), recipient: recipient(), permission: .readOnly)
        let context = try await controller.beginRequest(liveDocument: note, recipient: recipient(), permission: .readOnly)
        let message = AIConversationMessage(role: "user", content: "question")
        let outcome = NoteAIConversation.Outcome(reply: "answer",
            messages: [message, .init(role: "assistant", content: "answer")], note: note,
            toolCount: 0, validToolCallCount: 0)
        fault.arm()
        do {
            try await controller.acceptRound(context: context, message: message, outcome: outcome,
                commit: NoteAIToolCommit(mutation: nil, document: note), liveDocument: note,
                transcript: nil, executor: "fixture")
            XCTFail("expected injected persistence failure")
        } catch { }
        XCTAssertEqual(controller.record?.reply, "answer")
        XCTAssertEqual(controller.record?.visibleTurns.last?.content, "answer")
        let recovery = try XCTUnwrap(controller.recoveryFile)
        XCTAssertTrue(FileManager.default.fileExists(atPath: recovery.path))
        XCTAssertNotNil(controller.persistenceError)
        let cleared = await controller.clear()
        XCTAssertTrue(cleared)
        XCTAssertFalse(FileManager.default.fileExists(atPath: recovery.path),
                       "explicit clear removes controller-created recovery copies")
    }

    func testOversizedUnsavedResultExportsReadOnlyAndStaleEpochCannotExport() throws {
        let store = AIConversationStore(directory: try temporaryDirectory())
        let note = NoteDocument(id: "conversation-read-only-export", title: "只读导出")
        var value = try record(note: note)
        value.visibleTurns = (0..<6).map { index in
            .init(role: index.isMultiple(of: 2) ? "user" : "assistant",
                  content: String(repeating: "a", count: 400_000))
        }
        let exported = try store.exportRecovery(value)
        XCTAssertFalse(exported.resumable)
        let text = String(decoding: try Data(contentsOf: exported.url), as: UTF8.self)
        XCTAssertTrue(text.contains("padnote.ai-conversation.read-only.v1"))
        XCTAssertTrue(text.contains(String(repeating: "a", count: 1_000)))
        let current = try record(note: note)
        try store.save(current)
        try store.clear(current)
        XCTAssertThrowsError(try store.exportRecovery(value))
    }

    func testMalformedIdentifiersAndOverflowCountersFailClosed() throws {
        let directory = try temporaryDirectory()
        let store = AIConversationStore(directory: directory)
        XCTAssertThrowsError(try store.load(noteID: "../escape"))
        XCTAssertThrowsError(try store.currentEpoch(noteID: "../escape"))
        XCTAssertTrue(store.recoveryFiles(noteID: "../escape").isEmpty)
        let note = NoteDocument(id: "conversation-overflow", title: "计数")
        var value = try record(note: note)
        value.generation = Int.max
        XCTAssertThrowsError(try store.save(value))
        let guardURL = directory.appendingPathComponent("\(note.id).ai-session.guard")
        try Data("{\"epoch\":9223372036854775807}".utf8).write(to: guardURL)
        XCTAssertThrowsError(try store.currentEpoch(noteID: note.id))
    }
}

private func XCTAssertThrowsErrorAsync<T>(_ expression: () async throws -> T,
                                           file: StaticString = #filePath, line: UInt = #line) async {
    do {
        _ = try await expression()
        XCTFail("expected error", file: file, line: line)
    } catch { }
}
