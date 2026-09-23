import XCTest
@testable import PadNote

final class AgentTests: XCTestCase {
    func testVideoBundleContainsAndroidCompatibleFieldsAndHash() throws {
        let data = try VideoTaskBundleIO.make(noteId: "note-1", noteRevision: 0, title: "微积分", markdown: "# 内容\n\n$x^2$", audience: "学生", learningGoal: "理解导数", durationSeconds: 0)
        XCTAssertTrue(data.starts(with: [0x50, 0x4b, 0x03, 0x04]))
        let files = try unzipStored(data)
        let request = try XCTUnwrap(JSONSerialization.jsonObject(with: try XCTUnwrap(files["request.json"])) as? [String: Any])
        XCTAssertEqual(request["task_type"] as? String, "video.explain.v1")
        XCTAssertEqual((request["source"] as? [String: Any])?["entrypoint"] as? String, "input/content.md")
        let manifest = try XCTUnwrap(JSONSerialization.jsonObject(with: try XCTUnwrap(files["input/manifest.json"])) as? [String: Any])
        let entry = try XCTUnwrap((manifest["files"] as? [[String: Any]])?.first)
        XCTAssertEqual(entry["path"] as? String, "input/content.md"); XCTAssertEqual(files["input/content.md"], Data("# 内容\n\n$x^2$".utf8))
    }

    func testStoreInvalidatesHealthOnConfigurationChange() throws {
        let suite = "agent-test-\(UUID().uuidString)"; let defaults = UserDefaults(suiteName: suite)!; defer { defaults.removePersistentDomain(forName: suite) }
        let store = AgentConnectionStore(defaults: defaults, keychain: MemoryTokenStore())
        try store.save(kind: .hermes, endpoint: "https://example.test", token: "secret"); store.setConnected(true)
        XCTAssertTrue(store.load().connected); try store.save(kind: .hermes, endpoint: "https://changed.test", token: "secret"); XCTAssertFalse(store.load().connected)
    }

    func testOpenClawNeverReportsHealthy() async throws {
        let client = AgentConnectionClient(); do { _ = try await client.probe(AgentConnectionConfig(kind: .openClaw, endpoint: "https://example.test", token: "x")); XCTFail("must fail") } catch let error as AgentConnectionError { XCTAssertEqual(error, .openClawRequiresBridge) }
    }

    func testCapabilitiesFixtureRequiresAllFiveFeatures() async throws {
        let complete = #"{"object":"hermes.api_server.capabilities","platform":"hermes-agent","features":{"run_submission":true,"run_status":true,"run_events_sse":true,"run_stop":true,"run_approval_response":true}}"#.data(using: .utf8)!
        let session = fixtureSession(data: complete, status: 200)
        let config = AgentConnectionConfig(endpoint: "https://fixture.test", token: "secret")
        let result = try await AgentConnectionClient(session: session).probe(config)
        XCTAssertEqual(result, "Hermes 已连接")
        let missing = #"{"object":"hermes.api_server.capabilities","platform":"hermes-agent","features":{"run_submission":true,"run_status":true,"run_events_sse":true,"run_stop":true}}"#.data(using: .utf8)!
        do { _ = try await AgentConnectionClient(session: fixtureSession(data: missing, status: 200)).probe(config); XCTFail("missing capability must fail") } catch let error as AgentConnectionError { XCTAssertEqual(error, .missingCapability("run_approval_response")) }
    }

    func testRedirectFixtureIsRejected() async throws {
        let session = fixtureSession(data: Data(), status: 302, headers: ["Location": "https://other.test/v1/capabilities"])
        do { _ = try await AgentConnectionClient(session: session).probe(AgentConnectionConfig(endpoint: "https://fixture.test", token: "x")); XCTFail("redirect must fail") } catch let error as AgentConnectionError { XCTAssertEqual(error, .redirected) }
    }

    private func fixtureSession(data: Data, status: Int, headers: [String: String] = [:]) -> URLSession {
        FixtureProtocol.payload = data; FixtureProtocol.status = status; FixtureProtocol.headers = headers
        let configuration = URLSessionConfiguration.ephemeral; configuration.protocolClasses = [FixtureProtocol.self]
        return URLSession(configuration: configuration)
    }

    private func unzipStored(_ data: Data) throws -> [String: Data] {
        var result = [String: Data](); var cursor = 0
        while cursor + 30 <= data.count {
            let signature = data.read32(cursor); if signature == 0x02014b50 || signature == 0x06054b50 { break }; XCTAssertEqual(signature, 0x04034b50)
            let method = data.read16(cursor + 8); XCTAssertEqual(method, 0); let size = Int(data.read32(cursor + 18)); let nameLength = Int(data.read16(cursor + 26)); let extraLength = Int(data.read16(cursor + 28)); let nameStart = cursor + 30; let name = String(decoding: data[nameStart..<(nameStart + nameLength)], as: UTF8.self); let bodyStart = nameStart + nameLength + extraLength; result[name] = Data(data[bodyStart..<(bodyStart + size)]); cursor = bodyStart + size
        }
        return result
    }
}

private final class MemoryTokenStore: AgentTokenStore {
    private var value: String?
    func save(_ value: String) throws { self.value = value }
    func read() -> String? { value }
    func delete() { value = nil }
}

private extension Data { func read16(_ offset: Int) -> UInt16 { UInt16(self[offset]) | UInt16(self[offset + 1]) << 8 }; func read32(_ offset: Int) -> UInt32 { UInt32(read16(offset)) | UInt32(read16(offset + 2)) << 16 } }

private final class FixtureProtocol: URLProtocol {
    static var payload = Data(); static var status = 200; static var headers = [String: String]()
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() { let response = HTTPURLResponse(url: request.url!, statusCode: Self.status, httpVersion: "HTTP/1.1", headerFields: Self.headers)!; client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed); client?.urlProtocol(self, didLoad: Self.payload); client?.urlProtocolDidFinishLoading(self) }
    override func stopLoading() {}
}
