import XCTest
@testable import PadNote

final class NoteAIConversationTests: XCTestCase {
    final class MemorySecrets: SecretStore {
        func read(reference: String) throws -> String? { "fixture-token" }
        func write(_ value: String, reference: String) throws {}
    }

    final class ConversationURLProtocol: URLProtocol {
        enum Mode { case writeThenAnswer, maliciousWrite, readLoop }
        static var mode: Mode = .writeThenAnswer
        static var bodies: [[String: Any]] = []
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
            let body = (try? JSONSerialization.jsonObject(with: Self.bodyData(request))) as? [String: Any] ?? [:]
            Self.lock.lock(); Self.bodies.append(body); let index = Self.calls; Self.calls += 1; Self.lock.unlock()
            let response: String
            switch Self.mode {
            case .writeThenAnswer where index == 0:
                response = #"{"choices":[{"message":{"content":"","tool_calls":[{"id":"call-write","type":"function","function":{"name":"write_text","arguments":"{\"content\":\"结论\",\"placement\":{\"page\":1}}"}}]}}]}"#
            case .maliciousWrite where index == 0:
                response = #"{"choices":[{"message":{"content":"","tool_calls":[{"id":"call-malicious","type":"function","function":{"name":"write_text","arguments":"{\"content\":\"不得写入\",\"placement\":{\"page\":1}}"}}]}}]}"#
            case .readLoop:
                response = #"{"choices":[{"message":{"content":"","tool_calls":[{"id":"call-map","type":"function","function":{"name":"read_page_map","arguments":"{}"}}]}}]}"#
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
        ConversationURLProtocol.bodies = []; ConversationURLProtocol.calls = 0; ConversationURLProtocol.lock.unlock()
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

    func testToolRoundCarriesPageMapSchemasAndCommitsOnlyOutcomeSnapshot() async throws {
        let (client, profile) = makeClient()
        let original = NoteDocument(title: "原笔记")
        let outcome = try await NoteAIConversation.run(client: client, profile: profile, history: [],
            message: .init(role: "user", content: "请写入结论"), transcript: nil, note: original,
            page: 0, selection: nil, vault: [], permission: .createInFreeSpace)
        XCTAssertEqual(outcome.reply, "已完成")
        XCTAssertEqual(original.textFlows.count, 0)
        XCTAssertEqual(outcome.note.textFlows.count, 1)
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
}
