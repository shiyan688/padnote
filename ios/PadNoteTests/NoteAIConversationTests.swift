import XCTest
import UIKit
@testable import PadNote

final class NoteAIConversationTests: XCTestCase {
    final class MemorySecrets: SecretStore {
        func read(reference: String) throws -> String? { "fixture-token" }
        func write(_ value: String, reference: String) throws {}
        func delete(reference: String) throws {}
    }

    final class ConversationURLProtocol: URLProtocol {
        enum Mode { case writeThenAnswer, maliciousWrite, readLoop, answerOnly, readOtherPageThenAnswer, readUnselectedVaultThenAnswer }
        static var mode: Mode = .writeThenAnswer
        static var bodies: [[String: Any]] = []
        static var rawBodies: [Data] = []
        static var lock = NSLock()
        static var calls = 0

        override class func canInit(with request: URLRequest) -> Bool { request.url?.host == "conversation.test" }
        override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
        private static func bodyData(_ request: URLRequest) -> Data {
            if let body = request.httpBody { return body }
            guard let stream = request.httpBodyStream else { return Data() }
            stream.open(); defer { stream.close() }
            var result = Data(); var buffer = [UInt8](repeating: 0, count: 4096)
            while stream.hasBytesAvailable {
                let count = stream.read(&buffer, maxLength: buffer.count)
                if count <= 0 { break }
                result.append(buffer, count: count)
            }
            return result
        }
        override func startLoading() {
            let raw = Self.bodyData(request)
            let body = (try? JSONSerialization.jsonObject(with: raw)) as? [String: Any] ?? [:]
            Self.lock.lock(); Self.bodies.append(body); Self.rawBodies.append(raw); let index = Self.calls; Self.calls += 1; Self.lock.unlock()
            let response: String
            switch Self.mode {
            case .writeThenAnswer where index == 0:
                response = #"{"choices":[{"message":{"content":"","tool_calls":[{"id":"call-write","type":"function","function":{"name":"write_text","arguments":"{\"content\":\"结论\",\"placement\":{\"page\":1}}"}}]}}]}"#
            case .maliciousWrite where index == 0:
                response = #"{"choices":[{"message":{"content":"","tool_calls":[{"id":"call-malicious","type":"function","function":{"name":"write_text","arguments":"{\"content\":\"不得写入\",\"placement\":{\"page\":1}}"}}]}}]}"#
            case .readLoop:
                response = #"{"choices":[{"message":{"content":"","tool_calls":[{"id":"call-map","type":"function","function":{"name":"read_page_map","arguments":"{}"}}]}}]}"#
            case .readOtherPageThenAnswer where index == 0:
                response = #"{"choices":[{"message":{"content":"","tool_calls":[{"id":"call-map-other","type":"function","function":{"name":"read_page_map","arguments":"{\"page\":2}"}}]}}]}"#
            case .readUnselectedVaultThenAnswer where index == 0:
                response = #"{"choices":[{"message":{"content":"","tool_calls":[{"id":"call-vault-b","type":"function","function":{"name":"read_vault_note","arguments":"{\"id\":\"vault-b\"}"}}]}}]}"#
            default:
                response = #"{"choices":[{"message":{"content":"已完成"}}]}"#
            }
            let data = Data(response.utf8)
            let http = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: ["Content-Type": "application/json"])!
            client?.urlProtocol(self, didReceive: http, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: data)
            client?.urlProtocolDidFinishLoading(self)
        }
        override func stopLoading() {}
    }

    override func setUp() {
        ConversationURLProtocol.lock.lock(); ConversationURLProtocol.mode = .writeThenAnswer
        ConversationURLProtocol.bodies = []; ConversationURLProtocol.rawBodies = []
        ConversationURLProtocol.calls = 0; ConversationURLProtocol.lock.unlock()
    }

    private func makeClient() -> (AIClient, AIProfile) {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [ConversationURLProtocol.self]
        let profile = AIProfile(name: "fixture", visionEndpoint: "https://conversation.test/v1", visionModel: "fixture-model", visionKeyReference: "fixture-key")
        return (AIClient(settings: AISettings(endpoint: profile.visionEndpoint, model: profile.visionModel), secretStore: MemorySecrets(), sessionConfiguration: config), profile)
    }

    private func bodySnapshot() -> [[String: Any]] {
        ConversationURLProtocol.lock.lock(); defer { ConversationURLProtocol.lock.unlock() }
        return ConversationURLProtocol.bodies
    }

    private func rawBodySnapshot() -> [String] {
        ConversationURLProtocol.lock.lock(); defer { ConversationURLProtocol.lock.unlock() }
        return ConversationURLProtocol.rawBodies.map { String(decoding: $0, as: UTF8.self) }
    }

    func testToolRoundCarriesPageMapSchemasAndCommitsOnlyOutcomeSnapshot() async throws {
        let (client, profile) = makeClient()
        let original = NoteDocument(title: "原笔记")
        let outcome = try await NoteAIConversation.run(client: client, profile: profile, history: [],
            message: .init(role: "user", content: "请写入结论"), transcript: nil, note: original,
            page: 0, selection: nil, vault: [], permission: .createInFreeSpace)
        XCTAssertEqual(outcome.reply, "已完成")
        XCTAssertEqual(original.textFlows.count, 0)
        XCTAssertEqual(outcome.note.textFlows.count, 1)
        XCTAssertEqual(outcome.validToolCallCount, 1)
        let bodies = bodySnapshot(); XCTAssertEqual(bodies.count, 2)
        let tools = try XCTUnwrap(bodies[0]["tools"] as? [[String: Any]])
        XCTAssertTrue(tools.contains { (($0["function"] as? [String: Any])?["name"] as? String) == "write_text" })
        let firstMessages = try XCTUnwrap(bodies[0]["messages"] as? [[String: Any]])
        XCTAssertTrue(String(describing: firstMessages).contains("pageCount"))
        let secondMessages = try XCTUnwrap(bodies[1]["messages"] as? [[String: Any]])
        XCTAssertTrue(String(describing: secondMessages).contains("tool_calls"))
        XCTAssertTrue(String(describing: secondMessages).contains("tool_call_id"))
    }

    func testReadOnlyCannotMutateWhenModelRequestsWriteTool() async throws {
        ConversationURLProtocol.mode = .maliciousWrite
        let (client, profile) = makeClient()
        let note = NoteDocument(title: "只读")
        let outcome = try await NoteAIConversation.run(client: client, profile: profile, history: [],
            message: .init(role: "user", content: "不要修改"), transcript: nil, note: note,
            page: 0, selection: nil, vault: [], permission: .readOnly)
        XCTAssertEqual(outcome.note.textFlows.count, 0)
        XCTAssertEqual(outcome.validToolCallCount, 0,
                       "a request for a tool that was not advertised is not capability evidence")
        let tools = try XCTUnwrap(bodySnapshot().first?["tools"] as? [[String: Any]])
        XCTAssertFalse(tools.contains { (($0["function"] as? [String: Any])?["name"] as? String) == "write_text" })
    }

    func testSixReadPageMapRoundsAreBoundedWithoutPersistence() async throws {
        ConversationURLProtocol.mode = .readLoop
        let (client, profile) = makeClient()
        let note = NoteDocument(title: "循环")
        do {
            _ = try await NoteAIConversation.run(client: client, profile: profile, history: [],
                message: .init(role: "user", content: "读取页面"), transcript: nil, note: note,
                page: 0, selection: nil, vault: [], permission: .readOnly)
            XCTFail("expected bounded tool loop")
        } catch let error as NoteAIConversationError {
            guard case .tooManyCalls = error else { XCTFail("wrong error: \(error)"); return }
        }
        XCTAssertEqual(note.textFlows.count, 0)
        XCTAssertEqual(bodySnapshot().count, 6)
    }

    func testDirectAndFollowUpRequestBytesNeverContainUnselectedPageText() async throws {
        ConversationURLProtocol.mode = .answerOnly
        let (client, profile) = makeClient()
        var note = NoteDocument(title: "请求字节", pageCount: 2)
        note.textFlows = [
            NoteTextFlow(id: "visible-layout-a", source: "SAME_PAGE_PRIVATE_MARKER", anchorPageIndex: 0),
            NoteTextFlow(id: "visible-layout-b", source: "ADJACENT_PAGE_PRIVATE_MARKER", anchorPageIndex: 1)
        ]
        let first = try await NoteAIConversation.run(client: client, profile: profile, history: [],
            message: .init(role: "user", content: "解释选区", imageDataURL: "data:image/png;base64,AQID"),
            transcript: nil, note: note, page: 0, selection: CGRect(x: 20, y: 20, width: 80, height: 60),
            vault: [], permission: .readOnly)
        _ = try await NoteAIConversation.run(client: client, profile: profile, history: first.messages,
            message: .init(role: "user", content: "继续"), transcript: nil, note: note, page: 0,
            selection: CGRect(x: 20, y: 20, width: 80, height: 60), vault: [], permission: .readOnly)

        let bodies = rawBodySnapshot()
        XCTAssertEqual(bodies.count, 2)
        XCTAssertTrue(bodies[0].contains("image_url"), "direct first request carries only the frozen selection image")
        XCTAssertTrue(bodies[1].contains("继续"), "follow-up uses the same conversation serializer")
        for body in bodies {
            XCTAssertFalse(body.contains("SAME_PAGE_PRIVATE_MARKER"))
            XCTAssertFalse(body.contains("ADJACENT_PAGE_PRIVATE_MARKER"))
            XCTAssertFalse(body.contains("search_vault"), "no knowledge snapshot means no knowledge tool is advertised")
        }
    }

    func testOtherPageToolRoundRequestBytesContainLayoutButNoSource() async throws {
        ConversationURLProtocol.mode = .readOtherPageThenAnswer
        let (client, profile) = makeClient()
        var note = NoteDocument(title: "跨页", pageCount: 2)
        note.textFlows = [
            NoteTextFlow(id: "layout-first", source: "FIRST_PAGE_SECRET", anchorPageIndex: 0),
            NoteTextFlow(id: "layout-second", source: "SECOND_PAGE_SECRET", anchorPageIndex: 1)
        ]
        _ = try await NoteAIConversation.run(client: client, profile: profile, history: [],
            message: .init(role: "user", content: "读取布局"), transcript: nil, note: note,
            page: 0, selection: nil, vault: [], permission: .readOnly)
        let bodies = rawBodySnapshot()
        XCTAssertEqual(bodies.count, 2)
        XCTAssertTrue(bodies[1].contains("layout-second"))
        XCTAssertFalse(bodies[1].contains("FIRST_PAGE_SECRET"))
        XCTAssertFalse(bodies[1].contains("SECOND_PAGE_SECRET"))
    }

    func testTwoStageAnswerRequestUsesTranscriptWithoutImageOrUnselectedText() async throws {
        ConversationURLProtocol.mode = .answerOnly
        let (client, base) = makeClient()
        let profile = AIProfile(name: "two-stage", mode: .twoStage,
            visionEndpoint: base.visionEndpoint, visionModel: "vision",
            textEndpoint: base.visionEndpoint, textModel: "text",
            visionKeyReference: base.visionKeyReference, textKeyReference: base.visionKeyReference)
        var note = NoteDocument(title: "两阶段")
        note.textFlows = [NoteTextFlow(id: "layout-only", source: "UNSELECTED_NOTE_TEXT")]
        _ = try await NoteAIConversation.run(client: client, profile: profile, history: [],
            message: .init(role: "user", content: "回答", imageDataURL: "data:image/png;base64,AQID"),
            transcript: "AUTHORIZED_SELECTION_TRANSCRIPT", note: note, page: 0,
            selection: CGRect(x: 10, y: 10, width: 30, height: 20), vault: [], permission: .readOnly)
        let body = try XCTUnwrap(rawBodySnapshot().first)
        XCTAssertTrue(body.contains("AUTHORIZED_SELECTION_TRANSCRIPT"))
        XCTAssertFalse(body.contains("image_url"))
        XCTAssertFalse(body.contains("UNSELECTED_NOTE_TEXT"))
    }

    func testActualToolRequestCannotReadOrRevealUnselectedVaultEntry() async throws {
        ConversationURLProtocol.mode = .readUnselectedVaultThenAnswer
        let (client, profile) = makeClient()
        let selectedA = NoteToolVaultEntry(id: "vault-a", title: "授权材料 A", markdown: "AUTHORIZED_VAULT_A")
        _ = try await NoteAIConversation.run(client: client, profile: profile, history: [],
            message: .init(role: "user", content: "查材料"), transcript: nil,
            note: NoteDocument(title: "范围"), page: 0, selection: nil,
            vault: [selectedA], permission: .readOnly)
        let bodies = rawBodySnapshot()
        XCTAssertEqual(bodies.count, 2)
        XCTAssertTrue(bodies[0].contains("read_vault_note"))
        XCTAssertTrue(bodies[1].contains("找不到知识库笔记"))
        for body in bodies {
            XCTAssertFalse(body.contains("UNSELECTED_VAULT_B"))
            XCTAssertFalse(body.contains("未授权材料 B"))
        }
    }

    func testBoundaryResetClearsPopulatedHistoryToolResultsAndTranscript() {
        var session = AIConversationSession(
            messages: [
                .init(role: "user", content: "旧问题"),
                .init(role: "tool", content: "OLD_TOOL_PRIVATE", toolCallID: "old-call")
            ],
            reply: "旧回答",
            transcript: "OLD_TRANSCRIPT_PRIVATE"
        )
        session.resetForBoundaryChange()
        XCTAssertTrue(session.messages.isEmpty)
        XCTAssertNil(session.reply)
        XCTAssertNil(session.transcript)
    }

    func testEditorSourceSnapshotFreezesImageDocumentPageBoundsAndVaultContent() throws {
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: 2, height: 2))
        let image = renderer.image { context in UIColor.black.setFill(); context.fill(CGRect(x: 0, y: 0, width: 1, height: 1)) }
        var note = NoteDocument(title: "冻结来源")
        note.updatedAt = 123
        note.textFlows = [NoteTextFlow(source: "FROZEN_NOTE")]
        var bounds = CGRect(x: 11, y: 22, width: 33, height: 44)
        var entries = [NoteToolVaultEntry(id: "a", title: "A", markdown: "FROZEN_VAULT", sourceRevision: 88)]
        let snapshot = NoteAISourceSnapshot(image: image, note: note, page: 3,
            selectionBounds: bounds, vaultEntries: entries)
        let imageBytes = try XCTUnwrap(snapshot.image?.pngData())

        note.title = "后来编辑"
        note.textFlows[0].source = "CHANGED_NOTE"
        bounds.origin = CGPoint(x: 900, y: 900)
        entries[0] = NoteToolVaultEntry(id: "a", title: "A", markdown: "CHANGED_VAULT", sourceRevision: 99)

        XCTAssertEqual(snapshot.note.title, "冻结来源")
        XCTAssertEqual(snapshot.note.textFlows[0].source, "FROZEN_NOTE")
        XCTAssertEqual(snapshot.page, 3)
        XCTAssertEqual(snapshot.selectionBounds, CGRect(x: 11, y: 22, width: 33, height: 44))
        XCTAssertEqual(snapshot.vaultEntries[0].markdown, "FROZEN_VAULT")
        XCTAssertEqual(snapshot.vaultEntries[0].sourceRevision, 88)
        XCTAssertEqual(snapshot.image?.pngData(), imageBytes)
    }

    @MainActor
    func testProductionControllerRotationRemovesOldWireBytesBeforeNextToolRound() async throws {
        ConversationURLProtocol.mode = .answerOnly
        let (client, profile) = makeClient()
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("NoteAIConversationRotation-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = AIConversationStore(directory: directory)
        let note = NoteDocument(id: "conversation-wire-rotation", title: "材料")
        let recipient = AIRecipientIdentity(fingerprint: String(repeating: "a", count: 64), display: "fixture")
        let oldSource = AIConversationSource(imagePNG: nil, page: 0, bounds: nil,
            noteContext: "OLD_SOURCE_PRIVATE", vaultEntries: [], pdfDigest: nil)
        var seeded = AIConversationRecord(noteID: note.id,
            visibleTurns: [.init(role: "assistant", content: "旧回答")],
            wireMessages: [.init(role: "tool", content: "OLD_TOOL_PRIVATE", toolCallID: "old")],
            source: oldSource, expectedDocumentDigest: try AIConversationDigest.document(note),
            recipient: recipient, permission: .readOnly)
        seeded.transcript = "OLD_TRANSCRIPT_PRIVATE"
        try store.save(seeded)
        let controller = AIConversationController(store: store)
        await controller.open(note: note, source: oldSource, recipient: recipient, permission: .readOnly)
        let newSource = AIConversationSource(imagePNG: nil, page: 0, bounds: nil,
            noteContext: "NEW_SOURCE_VISIBLE", vaultEntries: [], pdfDigest: nil)
        await controller.rotate(source: newSource, liveDocument: note,
            reason: "材料已变化", explicitlyRebindSource: true)
        let context = try await controller.beginRequest(liveDocument: note, recipient: recipient,
                                                        permission: .readOnly)
        XCTAssertTrue(context.history.isEmpty)
        XCTAssertNil(context.transcript)
        _ = try await NoteAIConversation.run(client: client, profile: profile, history: context.history,
            message: .init(role: "user", content: "NEW_QUESTION"), transcript: context.transcript,
            note: context.note, page: context.source.page, selection: context.source.bounds,
            vault: context.source.vaultEntries, permission: context.permission)
        let body = try XCTUnwrap(rawBodySnapshot().last)
        XCTAssertTrue(body.contains("NEW_QUESTION"))
        XCTAssertFalse(body.contains("OLD_TOOL_PRIVATE"))
        XCTAssertFalse(body.contains("OLD_TRANSCRIPT_PRIVATE"))
        XCTAssertFalse(body.contains("OLD_SOURCE_PRIVATE"))
    }
}
