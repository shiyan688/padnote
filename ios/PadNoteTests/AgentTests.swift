import XCTest
@testable import PadNote

final class AgentTests: XCTestCase {
    func testVideoBundleContainsAndroidCompatibleFieldsAndHash() throws {
        let data = try VideoTaskBundleIO.make(noteId: "note-1", noteRevision: 0, title: "微积分", markdown: "# 内容\n\n$x^2$", audience: "学生", learningGoal: "理解导数", durationSeconds: 120)
        XCTAssertTrue(data.starts(with: [0x50, 0x4b, 0x03, 0x04]))
        let files = try unzipStored(data)
        let request = try XCTUnwrap(JSONSerialization.jsonObject(with: try XCTUnwrap(files["request.json"])) as? [String: Any])
        XCTAssertEqual(request["task_type"] as? String, "video.explain.v1")
        XCTAssertEqual((request["source"] as? [String: Any])?["entrypoint"] as? String, "input/content.md")
        let manifest = try XCTUnwrap(JSONSerialization.jsonObject(with: try XCTUnwrap(files["input/manifest.json"])) as? [String: Any])
        let entry = try XCTUnwrap((manifest["files"] as? [[String: Any]])?.first)
        XCTAssertEqual(entry["path"] as? String, "input/content.md")
        XCTAssertEqual(files["input/content.md"], Data("# 内容\n\n$x^2$".utf8))
    }

    func testLegacySingleConnectionMigratesOnceWithoutDeletingRollbackCredential() throws {
        let (defaults, suite) = makeDefaults()
        defer { defaults.removePersistentDomain(forName: suite) }
        defaults.set("hermes", forKey: "padnote.agent.kind")
        defaults.set("https://computer.example.test", forKey: "padnote.agent.endpoint")
        defaults.set(true, forKey: "padnote.agent.connected")
        let tokens = MemoryTokenStore(values: ["token": "legacy-secret"])

        let first = AgentConnectionStore(defaults: defaults, keychain: tokens)
        let migrated = try XCTUnwrap(first.profiles().first)
        XCTAssertEqual(first.load().token, "legacy-secret")
        XCTAssertTrue(migrated.connected)
        XCTAssertTrue(migrated.capabilities.isEmpty, "migration must not invent a capability snapshot")
        XCTAssertEqual(try tokens.read(reference: "token"), "legacy-secret", "legacy key remains as rollback source")

        let second = AgentConnectionStore(defaults: defaults, keychain: tokens)
        XCTAssertEqual(second.profiles().map(\.id), [migrated.id])
        XCTAssertEqual(second.load().token, "legacy-secret")
    }

    func testUnreadableLegacyKeychainDoesNotCommitPartialMigration() throws {
        let (defaults, suite) = makeDefaults()
        defer { defaults.removePersistentDomain(forName: suite) }
        defaults.set("hermes", forKey: "padnote.agent.kind")
        defaults.set("https://computer.example.test", forKey: "padnote.agent.endpoint")
        let tokens = MemoryTokenStore(values: ["token": "secret"])
        tokens.readFailures.insert("token")

        let store = AgentConnectionStore(defaults: defaults, keychain: tokens)
        XCTAssertTrue(store.profiles().isEmpty)
        XCTAssertNil(defaults.data(forKey: "padnote.agent.connections.v1"))
        XCTAssertEqual(tokens.values, ["token": "secret"])
    }

    func testCorruptConnectionPayloadIsPreservedAndBlocksMutation() throws {
        let (defaults, suite) = makeDefaults()
        defer { defaults.removePersistentDomain(forName: suite) }
        let corrupt = Data("not-json".utf8)
        defaults.set(corrupt, forKey: "padnote.agent.connections.v1")
        let store = AgentConnectionStore(defaults: defaults, keychain: MemoryTokenStore())

        XCTAssertThrowsError(try store.create(name: "Hermes", kind: .hermes, endpoint: "https://host.test", token: "secret")) {
            XCTAssertEqual($0 as? AgentStoreError, .corruptStore)
        }
        XCTAssertEqual(defaults.data(forKey: "padnote.agent.connections.v1"), corrupt)
    }

    func testIdentityUpdateStagesCredentialAndRollsBackWhenMetadataWriteFails() throws {
        let (defaults, suite) = makeDefaults()
        defer { defaults.removePersistentDomain(forName: suite) }
        let tokens = MemoryTokenStore()
        let normal = AgentConnectionStore(defaults: defaults, keychain: tokens)
        let original = try normal.create(name: "Home", kind: .hermes, endpoint: "https://old.test", token: "old")
        let failing = AgentConnectionStore(defaults: defaults, keychain: tokens) { _, _, _ in false }

        XCTAssertThrowsError(try failing.update(id: original.id, expectedRevision: original.revision, name: "Home", kind: .hermes, endpoint: "https://new.test", token: "new", transport: .direct)) {
            XCTAssertEqual($0 as? AgentStoreError, .persistence)
        }
        XCTAssertEqual(normal.profile(id: original.id)?.endpoint, "https://old.test")
        XCTAssertEqual(normal.token(for: original.id), "old")
        XCTAssertEqual(tokens.values.count, 1, "staged credential must be removed after metadata failure")
    }

    func testOldCredentialIsCollectedOnlyByLaterProcessAfterMetadataCommit() throws {
        let (defaults, suite) = makeDefaults()
        defer { defaults.removePersistentDomain(forName: suite) }
        let tokens = MemoryTokenStore()
        let firstProcess = AgentConnectionStore(defaults: defaults, keychain: tokens, processIdentifier: "process-a")
        let original = try firstProcess.create(name: "Home", kind: .hermes, endpoint: "https://old.test", token: "old")
        let originalReference = original.credentialReference
        let updated = try firstProcess.update(
            id: original.id, expectedRevision: original.revision, name: original.name,
            kind: original.kind, endpoint: "https://new.test", token: "new", transport: original.transport
        )

        XCTAssertEqual(tokens.values[originalReference], "old", "the process that wrote metadata must retain the rollback credential")
        XCTAssertEqual(tokens.values[updated.credentialReference], "new")
        _ = AgentConnectionStore(defaults: defaults, keychain: tokens, processIdentifier: "process-a")
        XCTAssertEqual(tokens.values[originalReference], "old", "another Store in the same process must not collect early")

        let nextProcess = AgentConnectionStore(defaults: defaults, keychain: tokens, processIdentifier: "process-b")
        XCTAssertNil(tokens.values[originalReference])
        XCTAssertEqual(nextProcess.token(for: original.id), "new")

        nextProcess.delete(id: original.id)
        XCTAssertEqual(tokens.values[updated.credentialReference], "new", "deletion also keeps rollback credentials for the current process")
        _ = AgentConnectionStore(defaults: defaults, keychain: tokens, processIdentifier: "process-b")
        XCTAssertEqual(tokens.values[updated.credentialReference], "new")
        _ = AgentConnectionStore(defaults: defaults, keychain: tokens, processIdentifier: "process-c")
        XCTAssertNil(tokens.values[updated.credentialReference])
    }

    func testNameAndDefaultDoNotChangeIdentityRevisionButEndpointDoes() throws {
        let (defaults, suite) = makeDefaults()
        defer { defaults.removePersistentDomain(forName: suite) }
        let store = AgentConnectionStore(defaults: defaults, keychain: MemoryTokenStore())
        let first = try store.create(name: "A", kind: .hermes, endpoint: "https://a.test", token: "a")
        let second = try store.create(name: "B", kind: .hermes, endpoint: "https://b.test", token: "b", makeDefault: false)
        try store.setDefault(id: second.id)
        XCTAssertEqual(store.profile(id: first.id)?.revision, first.revision)

        let renamed = try store.update(id: first.id, expectedRevision: first.revision, name: "Renamed", kind: .hermes, endpoint: first.endpoint, token: nil, transport: .direct)
        XCTAssertEqual(renamed.revision, first.revision)
        XCTAssertThrowsError(try store.update(id: first.id, expectedRevision: renamed.revision, name: "Renamed", kind: .hermes, endpoint: "https://changed.test", token: nil, transport: .direct)) {
            XCTAssertEqual($0 as? AgentStoreError, .credentialRequired)
        }
        let changed = try store.update(id: first.id, expectedRevision: renamed.revision, name: "Renamed", kind: .hermes, endpoint: "https://changed.test", token: "new", transport: .direct)
        XCTAssertEqual(changed.revision, first.revision + 1)
        XCTAssertFalse(changed.connected)
    }

    func testStoreRejectsUnsafeEndpointBeforeSavingCredential() throws {
        let (defaults, suite) = makeDefaults()
        defer { defaults.removePersistentDomain(forName: suite) }
        let tokens = MemoryTokenStore()
        let store = AgentConnectionStore(defaults: defaults, keychain: tokens)
        for endpoint in ["http://host.test", "https://user@host.test", "https://host.test?a=1", "https://host.test/#x"] {
            XCTAssertThrowsError(try store.create(name: "Bad", kind: .hermes, endpoint: endpoint, token: "secret"))
        }
        XCTAssertTrue(tokens.values.isEmpty)
    }

    func testProbeCASIgnoresResponseAfterIdentityRevisionChanges() throws {
        let (defaults, suite) = makeDefaults()
        defer { defaults.removePersistentDomain(forName: suite) }
        let store = AgentConnectionStore(defaults: defaults, keychain: MemoryTokenStore())
        let profile = try store.create(name: "Home", kind: .hermes, endpoint: "https://old.test", token: "old")
        let snapshot = try store.snapshotForProbe(id: profile.id)
        let changed = try store.update(id: profile.id, expectedRevision: profile.revision, name: "Home", kind: .hermes, endpoint: "https://new.test", token: "new", transport: .direct)

        XCTAssertFalse(store.applyProbeSuccess(id: snapshot.id, revision: snapshot.revision, capabilities: ["run_submission": true]))
        XCTAssertNil(store.profile(id: profile.id)?.verifiedAt)
        XCTAssertTrue(store.applyProbeFailure(id: changed.id, revision: changed.revision, message: "offline"))
        XCTAssertEqual(store.profile(id: profile.id)?.revision, changed.revision, "probe result does not change identity revision")
    }

    func testDirectAndBridgeCapabilitiesUseExpectedIdentityAndPollingFeatures() async throws {
        let session = fixtureSession { request in
            if request.url?.path.contains("padnote/v1") == true {
                return FixtureResponse(json: [
                    "object": "padnote.agent.capabilities", "protocol_version": 1,
                    "bridge_id": "bridge-1", "instance_id": "instance-1", "kind": "hermes", "name": "Home",
                    "features": ["run_submission": true, "run_status": true, "run_stop": true, "task_bundle": true]
                ])
            }
            return FixtureResponse(json: [
                "object": "hermes.api_server.capabilities", "platform": "hermes-agent",
                "features": ["run_submission": true, "run_status": true, "run_stop": true]
            ])
        }
        let client = AgentConnectionClient(session: session)
        let direct = try await client.probeCapabilities(AgentConnectionConfig(endpoint: "https://fixture.test", token: "secret"))
        XCTAssertEqual(direct.message, "Hermes 已连接")
        let bridge = try await client.probeCapabilities(AgentConnectionConfig(kind: .hermes, endpoint: "https://fixture.test/prefix", token: "device", transport: .bridge, bridgeID: "bridge-1", instanceID: "instance-1"))
        XCTAssertTrue(bridge.capabilities["task_bundle"] == true)

        do {
            _ = try await client.probeCapabilities(AgentConnectionConfig(kind: .hermes, endpoint: "https://fixture.test", token: "device", transport: .bridge, bridgeID: "bridge-1", instanceID: "other"))
            XCTFail("identity mismatch must fail")
        } catch let error as AgentConnectionError { XCTAssertEqual(error, .identityMismatch) }
    }

    func testRedirectAndUnsafeURLsAreRejected() async throws {
        let redirect = fixtureSession { _ in FixtureResponse(status: 302, json: [:], headers: ["Location": "https://other.test/v1/capabilities"]) }
        do {
            _ = try await AgentConnectionClient(session: redirect).probe(AgentConnectionConfig(endpoint: "https://fixture.test", token: "x"))
            XCTFail("redirect must fail")
        } catch let error as AgentConnectionError { XCTAssertEqual(error, .redirected) }
        for endpoint in ["http://fixture.test", "https://user@fixture.test", "https://fixture.test?q=1", "https://fixture.test/#x"] {
            XCTAssertThrowsError(try AgentConnectionClient.validatedBaseURL(endpoint))
        }
    }

    func testPairingParsesExactCodeAndClaimsHermesConnection() async throws {
        let code = try AgentPairingCode(json: #"{"type":"padnote-pair","version":1,"url":"https://fixture.test/base","bridge_id":"bridge-1","code":"opaque"}"#)
        let session = fixtureSession { request in
            if request.url?.path.hasSuffix("/request") == true {
                return FixtureResponse(status: 202, json: ["request_id": "request-1", "poll_token": "poll", "expires_at": Date().addingTimeInterval(60).timeIntervalSince1970, "status": "pending"])
            }
            return FixtureResponse(status: 200, json: ["bridge_id": "bridge-1", "device_id": "device-1", "connections": [["instance_id": "instance-1", "kind": "hermes", "name": "Home", "token": "device-token"]]])
        }
        let client = AgentBridgePairingClient(session: session)
        let request = try await client.request(code: code, deviceID: "device-1", deviceName: "iPad")
        let claim = try await client.claim(code: code, request: request)
        guard case .approved(let bridgeID, let deviceID, let connections) = claim else { return XCTFail("must approve") }
        XCTAssertEqual(bridgeID, "bridge-1")
        XCTAssertEqual(deviceID, "device-1")
        XCTAssertEqual(connections.first?.instanceID, "instance-1")
    }

    func testTaskStoreUsesSharedPathLockAndKeepsIndependentTasks() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("agent-task-test-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let firstStore = AgentTaskStore(fileURL: root)
        let secondStore = AgentTaskStore(fileURL: root)
        let profile = AgentConnectionProfile(name: "Home", endpoint: "https://fixture.test", verifiedAt: Date(), capabilities: ["run_submission": true])
        let errorLock = NSLock()
        var errors = [Error]()
        DispatchQueue.concurrentPerform(iterations: 12) { index in
            do {
                let payload = try AgentTaskPayload(title: "Task \(index)", input: "Input \(index)", source: AgentTaskSource(noteID: "note", noteRevision: index + 1))
                _ = try (index.isMultiple(of: 2) ? firstStore : secondStore).create(profile: profile, payload: payload)
            } catch { errorLock.lock(); errors.append(error); errorLock.unlock() }
        }
        XCTAssertTrue(errors.isEmpty, "\(errors)")
        XCTAssertEqual(try firstStore.tasks().count, 12)
        XCTAssertEqual(try secondStore.tasks().count, 12)
    }

    func testTaskIdempotencyRejectsSameClientIDOnAnotherConnection() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("agent-task-test-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let store = AgentTaskStore(fileURL: root)
        let id = UUID().uuidString.lowercased()
        let payload = try AgentTaskPayload(clientTaskID: id, title: "Task", input: "Input", source: AgentTaskSource(noteID: "note", noteRevision: 1))
        let first = AgentConnectionProfile(name: "A", endpoint: "https://a.test")
        let second = AgentConnectionProfile(name: "B", endpoint: "https://b.test")
        _ = try store.create(profile: first, payload: payload)
        XCTAssertThrowsError(try store.create(profile: second, payload: payload)) {
            XCTAssertEqual($0 as? AgentTaskError, .invalidPayload)
        }
    }

    func testDirectTaskSubmissionUsesImmutableIdempotencyKeyAndTextOnlyBody() async throws {
        let session = fixtureSession { request in
            XCTAssertEqual(request.value(forHTTPHeaderField: "Idempotency-Key"), "11111111-1111-1111-1111-111111111111")
            let body = try! XCTUnwrap(request.httpBody)
            let json = try! XCTUnwrap(JSONSerialization.jsonObject(with: body) as? [String: Any])
            XCTAssertEqual(json.keys.sorted(), ["input"])
            return FixtureResponse(status: 202, json: ["run_id": "run-1", "status": "started"])
        }
        let profile = AgentConnectionProfile(name: "Direct", endpoint: "https://fixture.test")
        let payload = try AgentTaskPayload(clientTaskID: "11111111-1111-1111-1111-111111111111", title: "Task", input: "Hello", source: AgentTaskSource(noteID: "manual", noteRevision: 1))
        let state = try await AgentTaskClient(session: session).submit(task: AgentTaskRecord(profile: profile, payload: payload), token: "secret")
        XCTAssertEqual(state.remoteTaskID, "run-1")
        XCTAssertEqual(state.status, .running)
    }

    func testDirectApprovalUsesExactRequestAndShowsActualReason() async throws {
        let session = fixtureSession { request in
            XCTAssertEqual(request.url?.path, "/v1/runs/run-1/approval")
            let body = request.httpBody.flatMap { try? JSONSerialization.jsonObject(with: $0) as? [String: String] }
            XCTAssertEqual(body, ["request_id": "approval-1", "choice": "once"])
            return FixtureResponse(json: [
                "object": "hermes.run.approval_response", "run_id": "run-1",
                "request_id": "approval-1", "choice": "once", "resolved": 1
            ])
        }
        let profile = AgentConnectionProfile(name: "Direct", endpoint: "https://fixture.test/v1")
        let payload = try AgentTaskPayload(title: "Task", input: "Hello", source: AgentTaskSource(noteID: "manual", noteRevision: 1))
        var task = AgentTaskRecord(profile: profile, payload: payload)
        task.remoteTaskID = "run-1"
        task.status = .waitingForApproval
        task.approval = AgentTaskApproval(id: "approval-1", title: "运行命令", description: "需要读取所选目录")

        let state = try await AgentTaskClient(session: session).approve(task: task, approvalID: "approval-1", decision: "once", token: "secret")
        XCTAssertEqual(state.status, .running)
        XCTAssertNil(state.approval)
    }

    func testDirectStatusRequiresKnownStatusAndKeepsApprovalDetails() async throws {
        var response: [String: Any] = [
            "run_id": "run-1", "status": "waiting_for_approval",
            "approval": ["request_id": "approval-1", "command": "python review.py", "reason": "生成分镜审阅稿"]
        ]
        let session = fixtureSession { _ in FixtureResponse(json: response) }
        let profile = AgentConnectionProfile(name: "Direct", endpoint: "https://fixture.test")
        let payload = try AgentTaskPayload(title: "Task", input: "Hello", source: AgentTaskSource(noteID: "manual", noteRevision: 1))
        var task = AgentTaskRecord(profile: profile, payload: payload)
        task.remoteTaskID = "run-1"
        task.status = .running

        let waiting = try await AgentTaskClient(session: session).status(task: task, token: "secret")
        XCTAssertEqual(waiting.approval?.id, "approval-1")
        XCTAssertTrue(waiting.approval?.description.contains("python review.py") == true)
        XCTAssertTrue(waiting.approval?.description.contains("生成分镜审阅稿") == true)

        response = ["run_id": "run-1", "status": "future_state"]
        do {
            _ = try await AgentTaskClient(session: session).status(task: task, token: "secret")
            XCTFail("unknown task states must not be treated as running or successful")
        } catch let error as AgentTaskError {
            XCTAssertEqual(error, .invalidResponse)
        }
    }

    func testTaskResponseCannotApplyAfterConnectionIdentityChanges() async throws {
        let (defaults, suite) = makeDefaults()
        defer { defaults.removePersistentDomain(forName: suite) }
        let tokens = MemoryTokenStore()
        let connections = AgentConnectionStore(defaults: defaults, keychain: tokens)
        let initial = try connections.create(name: "Direct", kind: .hermes, endpoint: "https://fixture.test", token: "old")
        XCTAssertTrue(connections.applyProbeSuccess(id: initial.id, revision: initial.revision, capabilities: [
            "run_submission": true, "run_status": true, "run_stop": true
        ]))
        let taskRoot = FileManager.default.temporaryDirectory.appendingPathComponent("agent-task-test-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: taskRoot) }
        let tasks = AgentTaskStore(fileURL: taskRoot)
        let session = fixtureSession { _ in
            do {
                let current = try XCTUnwrap(connections.profile(id: initial.id))
                _ = try connections.update(
                    id: current.id, expectedRevision: current.revision, name: current.name,
                    kind: current.kind, endpoint: "https://changed.test", token: "new", transport: current.transport
                )
            } catch { XCTFail("fixture could not change connection: \(error)") }
            return FixtureResponse(status: 202, json: ["run_id": "run-1", "status": "started"])
        }
        let service = AgentTaskService(
            connectionStore: connections,
            taskStore: tasks,
            client: AgentTaskClient(session: session)
        )
        let payload = try AgentTaskPayload(title: "Task", input: "Hello", source: AgentTaskSource(noteID: "manual", noteRevision: 1))
        let local = try service.create(connectionID: initial.id, payload: payload)

        do {
            _ = try await service.submit(id: local.id)
            XCTFail("a response from the old identity must not update the task")
        } catch let error as AgentTaskError {
            XCTAssertEqual(error, .connectionChanged)
        }
        let unchanged = try XCTUnwrap(tasks.task(id: local.id))
        XCTAssertNil(unchanged.remoteTaskID)
        XCTAssertEqual(unchanged.status, .submitting)
    }

    private func makeDefaults() -> (UserDefaults, String) {
        let suite = "agent-test-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defaults.removePersistentDomain(forName: suite)
        return (defaults, suite)
    }

    private func fixtureSession(_ handler: @escaping (URLRequest) -> FixtureResponse) -> URLSession {
        FixtureProtocol.handler = handler
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [FixtureProtocol.self]
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
    var values: [String: String]
    var readFailures = Set<String>()
    var saveFailures = Set<String>()

    init(values: [String: String] = [:]) { self.values = values }
    func save(_ value: String, reference: String) throws {
        if saveFailures.contains(reference) { throw AgentStoreError.keychain }
        values[reference] = value
    }
    func read(reference: String) throws -> String? {
        if readFailures.contains(reference) { throw AgentStoreError.keychain }
        return values[reference]
    }
    func delete(reference: String) { values.removeValue(forKey: reference) }
}

private extension Data {
    func read16(_ offset: Int) -> UInt16 { UInt16(self[offset]) | UInt16(self[offset + 1]) << 8 }
    func read32(_ offset: Int) -> UInt32 { UInt32(read16(offset)) | UInt32(read16(offset + 2)) << 16 }
}

private struct FixtureResponse {
    var status: Int = 200
    var json: [String: Any]
    var headers: [String: String] = [:]
}

private final class FixtureProtocol: URLProtocol {
    static var handler: ((URLRequest) -> FixtureResponse)!
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        var prepared = request
        if prepared.httpBody == nil, let stream = request.httpBodyStream {
            stream.open()
            defer { stream.close() }
            var body = Data()
            var buffer = [UInt8](repeating: 0, count: 4096)
            while stream.hasBytesAvailable {
                let count = stream.read(&buffer, maxLength: buffer.count)
                if count <= 0 { break }
                body.append(buffer, count: count)
            }
            prepared.httpBody = body
        }
        let fixture = Self.handler(prepared)
        let data = try! JSONSerialization.data(withJSONObject: fixture.json)
        let response = HTTPURLResponse(url: request.url!, statusCode: fixture.status, httpVersion: "HTTP/1.1", headerFields: fixture.headers)!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: data)
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}
