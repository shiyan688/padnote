import XCTest
@testable import PadNote

final class DigitizationTests: XCTestCase {
    final class LockedCounts: @unchecked Sendable {
        private let lock = NSLock()
        private var successes = 0
        private var stale = 0
        func recordSuccess() { lock.lock(); successes += 1; lock.unlock() }
        func recordStale() { lock.lock(); stale += 1; lock.unlock() }
        func snapshot() -> (Int, Int) {
            lock.lock(); defer { lock.unlock() }
            return (successes, stale)
        }
    }

    actor ScriptedClient: DigitizationPageClient {
        let failures: Set<Int>
        private(set) var requestedPages: [Int] = []
        init(failures: Set<Int> = []) { self.failures = failures }
        func transcribe(pngData: Data, prompt: String, context: String,
                        profile: AIProfile) async throws -> String {
            let page = Int(pngData.first ?? 0) - 1
            requestedPages.append(page)
            if failures.contains(page) { throw AIClientError.timedOut }
            return "PAGE_\(page + 1)"
        }
        func calls() -> [Int] { requestedPages }
    }

    actor BlockingClient: DigitizationPageClient {
        private var response: CheckedContinuation<String, Never>?
        private var startedWaiters: [CheckedContinuation<Void, Never>] = []
        private var started = false
        private(set) var requestCount = 0
        func transcribe(pngData: Data, prompt: String, context: String,
                        profile: AIProfile) async throws -> String {
            requestCount += 1; started = true
            let waiters = startedWaiters; startedWaiters = []
            waiters.forEach { $0.resume() }
            return await withCheckedContinuation { response = $0 }
        }
        func waitUntilStarted() async {
            if started { return }
            await withCheckedContinuation { startedWaiters.append($0) }
        }
        func release() { response?.resume(returning: "LATE_RESULT"); response = nil }
        func calls() -> Int { requestCount }
    }

    @MainActor
    final class Publisher: DigitizationVaultPublishing {
        var published: [DigitizationCheckpoint] = []
        var failure: Error?
        func publishDigitization(note: NoteDocument, checkpoint: DigitizationCheckpoint) throws {
            if let failure { throw failure }
            XCTAssertTrue(checkpoint.isComplete)
            XCTAssertEqual(.readyToPublish, checkpoint.state)
            published.append(checkpoint)
        }
    }

    private func profile(id: String = "profile-a", endpoint: String = "https://model.example/v1",
                         model: String = "vision-a") -> AIProfile {
        AIProfile(id: id, name: "测试接收方", provider: .custom, mode: .direct,
                  visionEndpoint: endpoint, visionModel: model,
                  textEndpoint: endpoint, textModel: model,
                  visionKeyReference: "secure-storage://SHOULD-NOT-PERSIST",
                  textKeyReference: "secure-storage://SHOULD-NOT-PERSIST")
    }

    private func note(pageCount: Int = 3) -> NoteDocument {
        NoteDocument(id: "digitization-note", title: "整本恢复测试", updatedAt: 100,
                     pageCount: pageCount,
                     textFlows: [.init(id: "flow", format: "markdown", source: "SOURCE_TEXT",
                                       anchorPageIndex: 0)])
    }

    private func renderer(_ document: NoteDocument, _ page: Int, _ pdfURL: URL?) throws -> Data {
        Data([UInt8(page + 1)])
    }

    func testContentIdentityIgnoresTimestampAndViewportButTracksContentAndRecipient() throws {
        try withTemporaryDirectory { directory in
            let store = DigitizationCheckpointStore(directory: directory)
            let selected = profile()
            var first = note()
            let original = try store.identity(document: first, measuredPageCount: 3,
                                              pdfURL: nil, profile: selected)
            first.updatedAt += 999
            first.viewportZoom = 2.5; first.viewportCenterX = 12; first.viewportCenterY = 34
            XCTAssertEqual(original, try store.identity(document: first, measuredPageCount: 3,
                                                         pdfURL: nil, profile: selected))
            first.textFlows[0].source = "CHANGED"
            XCTAssertNotEqual(original.noteFingerprint,
                              try store.identity(document: first, measuredPageCount: 3,
                                                 pdfURL: nil, profile: selected).noteFingerprint)
            XCTAssertNotEqual(original.recipient,
                              try store.identity(document: note(), measuredPageCount: 3,
                                                 pdfURL: nil, profile: profile(id: "profile-b")).recipient)
            XCTAssertThrowsError(try store.identity(
                document: note(), measuredPageCount: 3, pdfURL: nil,
                profile: profile(endpoint: "https://model.example/v1?api_key=leak")))
        }
    }

    func testCheckpointPersistsNoEndpointSecretAndRejectsEmptyPage() throws {
        try withTemporaryDirectory { directory in
            let store = DigitizationCheckpointStore(directory: directory)
            let selected = profile()
            let identity = try store.identity(document: note(), measuredPageCount: 3,
                                              pdfURL: nil, profile: selected)
            let batch = try store.create(document: note(), identity: identity, pdfURL: nil)
            let checkpointURL = directory.appendingPathComponent("batch-\(batch.id).json")
            let data = try Data(contentsOf: checkpointURL)
            let json = String(decoding: data, as: UTF8.self)
            let decoded = try JSONDecoder().decode(DigitizationCheckpoint.self, from: data)
            XCTAssertFalse(json.contains("SHOULD-NOT-PERSIST"))
            XCTAssertFalse(json.contains("https://"))
            XCTAssertEqual("model.example/v1", decoded.source.recipient.visionDestination)
            XCTAssertThrowsError(try store.appendPage(batchID: batch.id,
                                                       expectedRevision: batch.revision,
                                                       pageIndex: 0, markdown: " \n"))
        }
    }

    func testTwoStoreCASAllowsOnlyOnePageCommitAtSameRevision() throws {
        try withTemporaryDirectory { directory in
            let first = DigitizationCheckpointStore(directory: directory)
            let second = DigitizationCheckpointStore(directory: directory)
            let identity = try first.identity(document: note(), measuredPageCount: 3,
                                              pdfURL: nil, profile: profile())
            let batch = try first.create(document: note(), identity: identity, pdfURL: nil)
            let finished = expectation(description: "both writers")
            finished.expectedFulfillmentCount = 2
            let counts = LockedCounts()
            for (store, page) in [(first, 0), (second, 1)] {
                DispatchQueue.global().async {
                    do {
                        _ = try store.appendPage(batchID: batch.id, expectedRevision: 0,
                                                 pageIndex: page, markdown: "PAGE")
                        counts.recordSuccess()
                    } catch DigitizationError.staleCheckpoint {
                        counts.recordStale()
                    } catch {}
                    finished.fulfill()
                }
            }
            wait(for: [finished], timeout: 3)
            let (successes, stale) = counts.snapshot()
            XCTAssertEqual(1, successes); XCTAssertEqual(1, stale)
            XCTAssertEqual(1, first.loadAll(noteID: note().id).first?.pages.count)
        }
    }

    func testInterruptedAtomicWriteKeepsPreviousCheckpointReadable() throws {
        enum Injected: Error { case stop }
        try withTemporaryDirectory { directory in
            let writes = LockedCounts()
            let store = DigitizationCheckpointStore(directory: directory) { point in
                switch point {
                case .afterTemporaryWrite:
                    let before = writes.snapshot().0
                    writes.recordSuccess()
                    if before > 0 { throw Injected.stop }
                }
            }
            let document = note()
            let identity = try store.identity(document: document, measuredPageCount: 3,
                                              pdfURL: nil, profile: profile())
            let batch = try store.create(document: document, identity: identity, pdfURL: nil)
            XCTAssertThrowsError(try store.appendPage(batchID: batch.id,
                                                       expectedRevision: batch.revision,
                                                       pageIndex: 0, markdown: "SHOULD_NOT_COMMIT"))
            let reopened = DigitizationCheckpointStore(directory: directory).loadAll(noteID: document.id)
            XCTAssertEqual(1, reopened.count)
            XCTAssertEqual(0, reopened[0].revision)
            XCTAssertTrue(reopened[0].pages.isEmpty)
        }
    }

    @MainActor
    func testPageFailureReopensAndRequestsOnlyMissingPagesThenPublishes() async throws {
        try await withTemporaryDirectoryAsync { directory in
            let store = DigitizationCheckpointStore(directory: directory)
            let document = note()
            let selected = profile()
            let identity = try store.identity(document: document, measuredPageCount: 3,
                                              pdfURL: nil, profile: selected)
            _ = try store.create(document: document, identity: identity, pdfURL: nil)
            let firstClient = ScriptedClient(failures: [1])
            let firstSession = DigitizationSession(store: store, client: firstClient, renderer: renderer)
            await firstSession.prepare(document: document, measuredPageCount: 3,
                                       pdfURL: nil, profile: selected)
            let publisher = Publisher()
            firstSession.start(document: document, sourcePDFURL: nil,
                               profile: selected, publisher: publisher)
            await firstSession.waitUntilIdle()
            let firstCalls = await firstClient.calls()
            XCTAssertEqual([0, 1], firstCalls)
            XCTAssertEqual([0], firstSession.checkpoint?.pages.map(\.pageIndex))
            XCTAssertTrue(publisher.published.isEmpty)

            let resumedClient = ScriptedClient()
            let resumed = DigitizationSession(store: DigitizationCheckpointStore(directory: directory),
                                              client: resumedClient, renderer: renderer)
            await resumed.prepare(document: document, measuredPageCount: 3,
                                  pdfURL: nil, profile: selected)
            XCTAssertEqual(1, resumed.checkpoint?.activeAttempt?.pageIndex)
            resumed.start(document: document, sourcePDFURL: nil,
                          profile: selected, publisher: publisher)
            await resumed.waitUntilIdle()
            var resumedCalls = await resumedClient.calls()
            XCTAssertEqual([], resumedCalls)
            resumed.confirmRetryUnknownPage()
            resumed.start(document: document, sourcePDFURL: nil,
                          profile: selected, publisher: publisher)
            await resumed.waitUntilIdle()
            resumedCalls = await resumedClient.calls()
            XCTAssertEqual([1, 2], resumedCalls)
            XCTAssertEqual(.published, resumed.checkpoint?.state)
            XCTAssertEqual(1, publisher.published.count)

            let reopenedClient = ScriptedClient()
            let reopened = DigitizationSession(store: DigitizationCheckpointStore(directory: directory),
                                               client: reopenedClient, renderer: renderer)
            await reopened.prepare(document: document, measuredPageCount: 3,
                                   pdfURL: nil, profile: selected)
            reopened.start(document: document, sourcePDFURL: nil,
                           profile: selected, publisher: publisher)
            await reopened.waitUntilIdle()
            let reopenedCalls = await reopenedClient.calls()
            XCTAssertEqual([], reopenedCalls)
            XCTAssertEqual(1, publisher.published.count)
        }
    }

    @MainActor
    func testCancellationInvalidatesLateResponse() async throws {
        try await withTemporaryDirectoryAsync { directory in
            let store = DigitizationCheckpointStore(directory: directory)
            let document = note(pageCount: 1); let selected = profile()
            let identity = try store.identity(document: document, measuredPageCount: 1,
                                              pdfURL: nil, profile: selected)
            _ = try store.create(document: document, identity: identity, pdfURL: nil)
            let client = BlockingClient()
            let session = DigitizationSession(store: store, client: client, renderer: renderer)
            await session.prepare(document: document, measuredPageCount: 1,
                                  pdfURL: nil, profile: selected)
            session.start(document: document, sourcePDFURL: nil,
                          profile: selected, publisher: Publisher())
            await client.waitUntilStarted()
            session.cancel()
            await client.release()
            try await Task.sleep(nanoseconds: 100_000_000)
            let calls = await client.calls()
            XCTAssertEqual(1, calls)
            let persisted = store.loadAll(noteID: document.id).first
            XCTAssertTrue(persisted?.pages.isEmpty == true)
            XCTAssertEqual(0, persisted?.activeAttempt?.pageIndex)
        }
    }

    @MainActor
    func testTwoSessionsCallClientOnceAndCancelKeepsLeaseUntilTaskEnds() async throws {
        try await withTemporaryDirectoryAsync { directory in
            let store = DigitizationCheckpointStore(directory: directory)
            let document = note(pageCount: 1); let selected = profile()
            let identity = try store.identity(document: document, measuredPageCount: 1,
                                              pdfURL: nil, profile: selected)
            _ = try store.create(document: document, identity: identity, pdfURL: nil)
            let client = BlockingClient()
            let first = DigitizationSession(store: store, client: client, renderer: renderer)
            let second = DigitizationSession(
                store: DigitizationCheckpointStore(directory: directory),
                client: client, renderer: renderer)
            await first.prepare(document: document, measuredPageCount: 1,
                                pdfURL: nil, profile: selected)
            await second.prepare(document: document, measuredPageCount: 1,
                                 pdfURL: nil, profile: selected)
            first.start(document: document, sourcePDFURL: nil,
                        profile: selected, publisher: Publisher())
            await client.waitUntilStarted()
            second.start(document: document, sourcePDFURL: nil,
                         profile: selected, publisher: Publisher())
            await second.waitUntilIdle()
            var calls = await client.calls()
            XCTAssertEqual(1, calls)

            first.cancel()
            let active = try XCTUnwrap(store.loadAll(noteID: document.id).first)
            XCTAssertThrowsError(try store.confirmRetry(batchID: active.id,
                                                        expectedRevision: active.revision)) { error in
                XCTAssertEqual(.requestInProgress, error as? DigitizationError)
            }
            await client.release()
            try await Task.sleep(nanoseconds: 100_000_000)
            let released = try XCTUnwrap(store.loadAll(noteID: document.id).first)
            XCTAssertNoThrow(try store.confirmRetry(batchID: released.id,
                                                    expectedRevision: released.revision))
            calls = await client.calls()
            XCTAssertEqual(1, calls)
        }
    }

    @MainActor
    func testResponseAppendFailureReopensAsUnknownWithoutAutomaticRequest() async throws {
        enum Injected: Error { case stop }
        try await withTemporaryDirectoryAsync { directory in
            let writes = LockedCounts()
            let store = DigitizationCheckpointStore(directory: directory) { point in
                switch point {
                case .afterTemporaryWrite:
                    let prior = writes.snapshot().0
                    writes.recordSuccess()
                    if prior == 2 { throw Injected.stop }
                }
            }
            let document = note(pageCount: 1); let selected = profile()
            let identity = try store.identity(document: document, measuredPageCount: 1,
                                              pdfURL: nil, profile: selected)
            _ = try store.create(document: document, identity: identity, pdfURL: nil)
            let firstClient = ScriptedClient()
            let first = DigitizationSession(store: store, client: firstClient, renderer: renderer)
            await first.prepare(document: document, measuredPageCount: 1,
                                pdfURL: nil, profile: selected)
            first.start(document: document, sourcePDFURL: nil,
                        profile: selected, publisher: Publisher())
            await first.waitUntilIdle()
            let firstCalls = await firstClient.calls()
            XCTAssertEqual([0], firstCalls)

            let reopenedClient = ScriptedClient()
            let reopened = DigitizationSession(
                store: DigitizationCheckpointStore(directory: directory),
                client: reopenedClient, renderer: renderer)
            await reopened.prepare(document: document, measuredPageCount: 1,
                                   pdfURL: nil, profile: selected)
            XCTAssertEqual(0, reopened.checkpoint?.activeAttempt?.pageIndex)
            reopened.start(document: document, sourcePDFURL: nil,
                           profile: selected, publisher: Publisher())
            await reopened.waitUntilIdle()
            var calls = await reopenedClient.calls()
            XCTAssertEqual([], calls)
            XCTAssertTrue(reopened.status?.contains("可能已经处理或收费") == true)

            reopened.confirmRetryUnknownPage()
            reopened.start(document: document, sourcePDFURL: nil,
                           profile: selected, publisher: Publisher())
            await reopened.waitUntilIdle()
            calls = await reopenedClient.calls()
            XCTAssertEqual([0], calls)
            XCTAssertEqual(.published, reopened.checkpoint?.state)
        }
    }

    @MainActor
    func testChangedPDFOrProfileIsRejectedBeforeNetwork() async throws {
        try await withTemporaryDirectoryAsync { directory in
            let source = directory.appendingPathComponent("source.pdf")
            try Data("PDF-A".utf8).write(to: source)
            var document = note(pageCount: 1); document.pdfPageCount = 1
            let selected = profile()
            let store = DigitizationCheckpointStore(directory: directory.appendingPathComponent("checkpoints"))
            let identity = try store.identity(document: document, measuredPageCount: 1,
                                              pdfURL: source, profile: selected)
            _ = try store.create(document: document, identity: identity, pdfURL: source)
            let client = ScriptedClient()
            let session = DigitizationSession(store: store, client: client, renderer: renderer)
            await session.prepare(document: document, measuredPageCount: 1,
                                  pdfURL: source, profile: selected)
            try Data("PDF-B".utf8).write(to: source)
            session.start(document: document, sourcePDFURL: source,
                          profile: selected, publisher: Publisher())
            await session.waitUntilIdle()
            var calls = await client.calls()
            XCTAssertEqual([], calls)
            XCTAssertTrue(session.status?.contains("变化") == true)

            try Data("PDF-A".utf8).write(to: source)
            session.start(document: document, sourcePDFURL: source,
                          profile: profile(id: "different"), publisher: Publisher())
            await session.waitUntilIdle()
            calls = await client.calls()
            XCTAssertEqual([], calls)
            XCTAssertTrue(session.status?.contains("模型配置") == true)
        }
    }

    @MainActor
    func testLatePrepareCannotReplaceNewRecipientAndHistorySurvivesNoProfile() async throws {
        try await withTemporaryDirectoryAsync { directory in
            let store = DigitizationCheckpointStore(directory: directory)
            let document = note(pageCount: 1)
            let oldProfile = profile(id: "slow")
            let newProfile = profile(id: "current", model: "vision-current")
            let source = try store.identity(document: document, measuredPageCount: 1,
                                            pdfURL: nil, profile: oldProfile)
            let draft = try store.create(document: document, identity: source, pdfURL: nil)
            let started = expectation(description: "old identity build started")
            let release = DispatchSemaphore(value: 0)
            let session = DigitizationSession(
                store: store,
                client: ScriptedClient(),
                identityBuilder: { document, count, pdfURL, selected in
                    if selected.id == oldProfile.id {
                        started.fulfill()
                        _ = release.wait(timeout: .now() + 3)
                    }
                    return try store.identity(document: document, measuredPageCount: count,
                                              pdfURL: pdfURL, profile: selected)
                }, renderer: renderer)

            let oldPrepare = Task { @MainActor in
                await session.prepare(document: document, measuredPageCount: 1,
                                      pdfURL: nil, profile: oldProfile)
            }
            await fulfillment(of: [started], timeout: 2)
            await session.prepare(document: document, measuredPageCount: 1,
                                  pdfURL: nil, profile: newProfile)
            release.signal()
            await oldPrepare.value
            XCTAssertEqual(newProfile.id, session.identity?.recipient.profileID)
            XCTAssertNil(session.checkpoint)

            session.loadHistory(noteID: document.id)
            session.blockSending("尚未选择可用的 AI 配置。旧草稿仍可预览或导出。")
            XCTAssertEqual(draft.id, session.checkpoint?.id)
            XCTAssertTrue(try String(contentsOf: session.exportCurrent()).contains("未完成的数字化草稿"))
        }
    }

    @MainActor
    func testCorruptCheckpointVisibleAndPartialCannotOverwriteVault() throws {
        try withTemporaryDirectory { directory in
            let checkpointDirectory = directory.appendingPathComponent("checkpoints")
            try FileManager.default.createDirectory(at: checkpointDirectory, withIntermediateDirectories: true)
            let corruptID = UUID().uuidString
            try Data("broken".utf8).write(
                to: checkpointDirectory.appendingPathComponent("batch-\(corruptID).json"))
            let store = DigitizationCheckpointStore(directory: checkpointDirectory)
            XCTAssertTrue(store.loadAll().isEmpty)
            XCTAssertEqual(1, store.recoveryItems.count)

            let document = note(); let selected = profile()
            let identity = try store.identity(document: document, measuredPageCount: 3,
                                              pdfURL: nil, profile: selected)
            let partial = try store.create(document: document, identity: identity, pdfURL: nil)
            let vault = VaultLibrary(directory: directory.appendingPathComponent("vault"))
            try vault.save(note: document, markdown: "EXISTING_COMPLETE")
            XCTAssertThrowsError(try vault.publishDigitization(note: document, checkpoint: partial))
            XCTAssertEqual("EXISTING_COMPLETE", vault.notes.first?.markdown)
            XCTAssertTrue(partial.markdown().contains("未完成的数字化草稿"))
        }
    }

    private func withTemporaryDirectory(_ body: (URL) throws -> Void) throws {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("DigitizationTests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: url) }
        try body(url)
    }

    private func withTemporaryDirectoryAsync(_ body: (URL) async throws -> Void) async throws {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("DigitizationTests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: url) }
        try await body(url)
    }
}
