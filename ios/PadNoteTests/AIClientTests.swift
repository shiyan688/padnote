import XCTest
@testable import PadNote

final class AIClientTests: XCTestCase {
    final class Store: SecretStore {
        func read(reference: String) throws -> String? { "test-key" }
        func write(_ value: String, reference: String) throws {}
    }

    final class ProtocolStub: URLProtocol {
        static var responseData = Data()
        static var status = 200
        static var neverFinishes = false
        static var requestedURL: URL?
        static var requestBody = Data()
        static var stopped: XCTestExpectation?

        override class func canInit(with request: URLRequest) -> Bool { true }
        override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
        override func startLoading() {
            Self.requestedURL = request.url
            Self.requestBody = request.httpBody ?? Data()
            if let stream = request.httpBodyStream {
                stream.open(); defer { stream.close() }
                var buffer = [UInt8](repeating: 0, count: 4096)
                while stream.hasBytesAvailable {
                    let count = stream.read(&buffer, maxLength: buffer.count)
                    if count <= 0 { break }
                    Self.requestBody.append(buffer, count: count)
                }
            }
            if Self.neverFinishes { return }
            let response = HTTPURLResponse(url: request.url!, statusCode: Self.status, httpVersion: nil,
                                           headerFields: ["Content-Type": "application/json", "Content-Length": "\(Self.responseData.count)"])!
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: Self.responseData)
            client?.urlProtocolDidFinishLoading(self)
        }
        override func stopLoading() { Self.stopped?.fulfill() }
    }

    override func setUp() {
        ProtocolStub.responseData = Data(#"{"choices":[{"message":{"content":"ok"}}]}"#.utf8)
        ProtocolStub.status = 200
        ProtocolStub.neverFinishes = false
        ProtocolStub.requestedURL = nil
        ProtocolStub.stopped = nil
    }

    func makeClient(_ endpoint: String = "https://example.test/v1") -> AIClient {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [ProtocolStub.self]
        return AIClient(settings: AISettings(endpoint: endpoint, model: "test-model"), secretStore: Store(), sessionConfiguration: config)
    }

    func request() -> AIConversationRequest {
        AIConversationRequest(action: .explain, model: "test-model", messages: [.init(role: "user", content: "hello")])
    }

    func testEndpointPathsAndRejections() throws {
        XCTAssertEqual(try AIClient.endpointURL("https://example.test/").path, "/chat/completions")
        XCTAssertEqual(try AIClient.endpointURL("https://example.test/v1/").path, "/v1/chat/completions")
        XCTAssertEqual(try AIClient.endpointURL("https://example.test/v1/chat/completions/").path, "/v1/chat/completions")
        for value in ["http://example.test/v1", "https://user:secret@example.test/v1", "https://example.test/v1?key=value", "file:///tmp/key"] {
            XCTAssertThrowsError(try AIClient.endpointURL(value))
        }
    }

    func testCompleteIncludesHistoryAndContext() async throws {
        let result = try await makeClient().complete(prompt: "follow up", history: [.init(role: "assistant", content: "previous")], context: "page context")
        XCTAssertEqual(result, "ok")
        XCTAssertEqual(ProtocolStub.requestedURL?.path, "/v1/chat/completions")
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: ProtocolStub.requestBody) as? [String: Any])
        let messages = try XCTUnwrap(json["messages"] as? [[String: Any]])
        XCTAssertEqual(messages.count, 2)
        XCTAssertEqual(messages.first?["content"] as? String, "previous")
        XCTAssertTrue((messages.last?["content"] as? String)?.contains("page context") == true)
    }

    func testHTTPErrorAndResponseLimit() async {
        ProtocolStub.status = 429
        do { _ = try await makeClient().send(request()); XCTFail("Expected HTTP error") }
        catch let error as AIClientError {
            guard case .http(429) = error else { return XCTFail("Wrong error") }
        } catch { XCTFail("\(error)") }
        ProtocolStub.status = 200
        ProtocolStub.responseData = Data(repeating: 65, count: 4 * 1024 * 1024 + 1)
        do { _ = try await makeClient().send(request()); XCTFail("Expected size rejection") }
        catch let error as AIClientError {
            guard case .responseTooLarge = error else { return XCTFail("Wrong error") }
        } catch { XCTFail("\(error)") }
    }

    func testCancellationStopsUnderlyingRequest() async throws {
        ProtocolStub.neverFinishes = true
        let stopped = expectation(description: "URLSession cancels transport")
        ProtocolStub.stopped = stopped
        let client = makeClient()
        let job = Task { try await client.send(request()) }
        try await Task.sleep(nanoseconds: 100_000_000)
        job.cancel()
        do { _ = try await job.value; XCTFail("Expected cancellation") }
        catch { XCTAssertTrue(error is AIClientError || error is CancellationError) }
        await fulfillment(of: [stopped], timeout: 3)
    }

    func testAlreadyCancelledRequestDoesNotStart() async {
        let client = makeClient()
        let job = Task { try await Task.sleep(nanoseconds: 1_000_000_000); return try await client.send(request()) }
        job.cancel()
        do { _ = try await job.value; XCTFail("Expected cancellation") } catch {}
        XCTAssertNil(ProtocolStub.requestedURL)
    }
}
