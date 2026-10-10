import XCTest
import CryptoKit
@testable import PadNote

final class AgentTests: XCTestCase {
    func testCapabilitySummaryDistinguishesReportedMissingUnknownAndUnconfirmedValues() {
        let reported: [String: Bool] = [
            "run_submission": true,
            "run_status": false,
            "task_bundle": true,
            "video_task_submission": false,
            "runtime_verified": true,
            "future_feature": true
        ]
        let verified = AgentCapabilitySummary.items(kind: .hermes, capabilities: reported,
            verified: true, credentialDisabled: false)
        XCTAssertEqual(verified.first { $0.id == "run_submission" }?.state, .supported)
        XCTAssertEqual(verified.first { $0.id == "run_status" }?.state, .unsupported)
        XCTAssertEqual(verified.first { $0.id == "run_stop" }?.state, .notReported)
        XCTAssertEqual(verified.first { $0.id == "runtime_verified" }?.state, .supported)
        XCTAssertEqual(AgentCapabilitySummary.unknownCount(capabilities: reported), 1)

        let unverified = AgentCapabilitySummary.items(kind: .hermes, capabilities: reported,
            verified: false, credentialDisabled: false)
        XCTAssertEqual(unverified.first { $0.id == "run_submission" }?.state, .oldUnconfirmed)
        XCTAssertTrue(AgentCapabilitySummary.nextStep(kind: .hermes, transport: .direct, capabilities: reported,
            verified: false, credentialDisabled: false).contains("测试连接"))

        let disabled = AgentCapabilitySummary.items(kind: .hermes, capabilities: reported,
            verified: true, credentialDisabled: true)
        XCTAssertEqual(disabled.first { $0.id == "run_submission" }?.state, .oldUnconfirmed)
        XCTAssertTrue(AgentCapabilitySummary.nextStep(kind: .hermes, transport: .direct, capabilities: reported,
            verified: true, credentialDisabled: true).contains("重新配对"))
    }

    func testCapabilitySummaryNeverEnablesUnsupportedOpenClawAndRequiresBothVideoCapabilities() {
        let openClawNextStep = AgentCapabilitySummary.nextStep(kind: .openClaw, transport: .bridge,
            capabilities: ["run_submission": true], verified: true, credentialDisabled: false)
        XCTAssertTrue(openClawNextStep.contains("此应用当前不支持执行 OpenClaw 任务"))

        let completeVideoNextStep = AgentCapabilitySummary.nextStep(kind: .builtinVideo, transport: .bridge,
            capabilities: ["task_bundle": true, "video_task_submission": true],
            verified: true, credentialDisabled: false)
        XCTAssertTrue(completeVideoNextStep.contains("视频任务包流程"))

        let directVideoNextStep = AgentCapabilitySummary.nextStep(kind: .builtinVideo, transport: .direct,
            capabilities: ["task_bundle": true, "video_task_submission": true],
            verified: true, credentialDisabled: false)
        XCTAssertFalse(directVideoNextStep.contains("可在现有视频任务包流程"),
            "video submission requires the existing Bridge transport as well as both reported capabilities")

        for incomplete in [["task_bundle": true], ["video_task_submission": true], [:]] {
            XCTAssertFalse(AgentCapabilitySummary.nextStep(kind: .builtinVideo, transport: .bridge, capabilities: incomplete,
                verified: true, credentialDisabled: false).contains("可在现有视频任务包流程"))
        }

        let withoutUnknown = AgentCapabilitySummary.nextStep(kind: .hermes, transport: .direct,
            capabilities: ["run_submission": false], verified: true, credentialDisabled: false)
        let withUnknown = AgentCapabilitySummary.nextStep(kind: .hermes, transport: .direct,
            capabilities: ["run_submission": false, "future_feature": true], verified: true, credentialDisabled: false)
        XCTAssertEqual(withUnknown, withoutUnknown, "unknown capabilities are informational only")
    }

    func testDuplicateTaskDestinationsAreDistinctAndNeverExposeSecrets() {
        let first = AgentConnectionProfile(
            id: UUID(uuidString: "11111111-1111-1111-1111-111111111111")!, name: "同名电脑",
            endpoint: "https://user:password@private.example.test:7443/path?token=query-secret#fragment-secret",
            credentialReference: "credential-secret", transport: .bridge,
            bridgeID: "bridge-alpha", instanceID: "instance-01"
        )
        let second = AgentConnectionProfile(
            id: UUID(uuidString: "22222222-2222-2222-2222-222222222222")!, name: "同名电脑",
            endpoint: "https://unused.example.test:7443", credentialReference: "credential-secret-2",
            transport: .bridge, bridgeID: "bridge-beta", instanceID: "instance-02"
        )

        let firstLabel = AgentTaskDestinationLabel.make(first)
        let secondLabel = AgentTaskDestinationLabel.make(second)
        XCTAssertNotEqual(firstLabel, secondLabel)
        XCTAssertTrue(firstLabel.contains("Hermes"))
        XCTAssertTrue(firstLabel.contains("private.example.test:7443"))
        XCTAssertTrue(firstLabel.contains("bridge-alpha"))
        XCTAssertTrue(firstLabel.contains("instance-01"))
        XCTAssertFalse(firstLabel.contains("https://user:password@"))
        XCTAssertFalse(firstLabel.contains("password"))
        XCTAssertFalse(firstLabel.contains("query-secret"))
        XCTAssertFalse(firstLabel.contains("fragment-secret"))
        XCTAssertFalse(firstLabel.contains("credential-secret"))
    }

    func testDirectTaskDestinationShowsHostAndPortWithoutURLSecrets() {
        let profile = AgentConnectionProfile(
            name: "Laptop", kind: .openClaw,
            endpoint: "https://user:password@private.example.test:9443/path?token=query-secret#fragment-secret"
        )
        let label = AgentTaskDestinationLabel.make(profile)

        XCTAssertTrue(label.contains("OpenClaw"))
        XCTAssertTrue(label.contains("private.example.test:9443"))
        XCTAssertFalse(label.contains("https://"))
        XCTAssertFalse(label.contains("password"))
        XCTAssertFalse(label.contains("query-secret"))
        XCTAssertFalse(label.contains("fragment-secret"))
    }

    func testBuiltinVideoDestinationKindIsNotMislabeledAsOpenClaw() {
        let profile = AgentConnectionProfile(
            name: "Video helper", kind: .builtinVideo, endpoint: "https://video.example.test:8543",
            transport: .bridge, bridgeID: "bridge-video", instanceID: "instance-video-01"
        )
        let label = AgentTaskDestinationLabel.make(profile)

        XCTAssertTrue(label.contains("内置视频"))
        XCTAssertFalse(label.contains("OpenClaw"))
        XCTAssertTrue(label.contains("video.example.test:8543"))
        XCTAssertTrue(label.contains("bridge-video"))
        XCTAssertTrue(label.contains("video-01"))
    }

    func testSameHostDifferentPortsHaveDistinctTaskDestinations() {
        let first = AgentConnectionProfile(
            id: UUID(uuidString: "33333333-3333-3333-3333-333333333333")!, name: "同名电脑",
            endpoint: "https://same.example.test:8443/path?token=secret-one"
        )
        let second = AgentConnectionProfile(
            id: UUID(uuidString: "44444444-4444-4444-4444-444444444444")!, name: "同名电脑",
            endpoint: "https://same.example.test:9443/path?token=secret-two"
        )

        let firstLabel = AgentTaskDestinationLabel.make(first)
        let secondLabel = AgentTaskDestinationLabel.make(second)
        XCTAssertNotEqual(firstLabel, secondLabel)
        XCTAssertTrue(firstLabel.contains("same.example.test:8443"))
        XCTAssertTrue(secondLabel.contains("same.example.test:9443"))
        XCTAssertFalse(firstLabel.contains("secret-one"))
        XCTAssertFalse(secondLabel.contains("secret-two"))
    }

    func testTaskHistoryDestinationComesFromPersistedSnapshotAndSeparatesSameNamedTargets() throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("agent-task-history-destination-\(UUID().uuidString)", isDirectory: true)
            .appendingPathComponent("tasks", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: root.deletingLastPathComponent()) }
        let store = AgentTaskStore(fileURL: root)
        var original = AgentConnectionProfile(
            id: UUID(uuidString: "55555555-5555-4555-8555-555555555555")!, name: "同名助手",
            kind: .builtinVideo,
            endpoint: "https://url-user:url-password@render-a.fixture.invalid:18443/path?token=url-query-secret#url-fragment-secret",
            credentialReference: "credential-reference-secret", transport: .bridge,
            bridgeID: "bridge-alpha", instanceID: "inst-a1"
        )
        let originalPayload = try AgentTaskPayload(title: "相同标题", input: "fixture input",
                                                   source: AgentTaskSource(noteID: "fixture-note", noteRevision: 1))
        let originalRecord = try store.create(profile: original, payload: originalPayload)
        let persistedOriginal = try XCTUnwrap(store.task(id: originalRecord.id))
        let originalLabel = AgentTaskHistoryDestinationLabel.make(persistedOriginal.connection)

        XCTAssertTrue(originalLabel.contains("内置视频"))
        XCTAssertTrue(originalLabel.contains("render-a.fixture.invalid:18443"))
        XCTAssertTrue(originalLabel.contains("Bridge 助手 bridge-alpha"))
        XCTAssertTrue(originalLabel.contains("实例 inst-a1"))
        XCTAssertTrue(originalLabel.contains("#55555555"))
        for secret in ["url-user", "url-password", "url-query-secret", "url-fragment-secret", "credential-reference-secret"] {
            XCTAssertFalse(originalLabel.contains(secret))
        }

        original.name = "后来改名的助手"
        original.endpoint = "https://new-target.fixture.invalid:29443"
        original.bridgeID = "bridge-new"
        original.instanceID = "instance-new"
        let laterSnapshotLabel = AgentTaskHistoryDestinationLabel.make(AgentTaskConnectionIdentity(profile: original))
        XCTAssertFalse(originalLabel.contains("new-target.fixture.invalid"),
                       "history destination must remain the immutable task snapshot, not a lookup of the current profile")
        XCTAssertNotEqual(originalLabel, laterSnapshotLabel)
        let reopenedStore = AgentTaskStore(fileURL: root)
        let rereadOriginal = try XCTUnwrap(reopenedStore.task(id: originalRecord.id))
        XCTAssertEqual(AgentTaskHistoryDestinationLabel.make(rereadOriginal.connection), originalLabel,
                       "reopening the task store after profile edits must preserve the original persisted destination")

        let second = AgentConnectionProfile(
            id: UUID(uuidString: "66666666-6666-4666-8666-666666666666")!, name: "同名助手",
            endpoint: "https://render-b.fixture.invalid:18443", transport: .bridge,
            bridgeID: "bridge-beta", instanceID: "inst-b2"
        )
        let secondPayload = try AgentTaskPayload(title: "相同标题", input: "other fixture input",
                                                 source: AgentTaskSource(noteID: "fixture-note", noteRevision: 1))
        let secondRecord = try store.create(profile: second, payload: secondPayload)
        let persistedSecond = try XCTUnwrap(store.task(id: secondRecord.id))
        let secondLabel = AgentTaskHistoryDestinationLabel.make(persistedSecond.connection)
        XCTAssertNotEqual(originalLabel, secondLabel)
        XCTAssertTrue(secondLabel.contains("render-b.fixture.invalid:18443"))
        XCTAssertTrue(secondLabel.contains("Bridge 助手 bridge-beta"))
        XCTAssertTrue(secondLabel.contains("实例 inst-b2"))
    }

    func testTaskHistoryLongBridgeIdentityUsesSafeEightCharacterSuffixes() {
        let profile = AgentConnectionProfile(
            id: UUID(uuidString: "99999999-9999-4999-8999-999999999999")!, name: "同名助手",
            endpoint: "https://long-id.fixture.invalid:18443", transport: .bridge,
            bridgeID: "bridge-private-prefix-AB12CD34",
            instanceID: "instance-private-prefix-Z9Y8X7W6"
        )
        let label = AgentTaskHistoryDestinationLabel.make(AgentTaskConnectionIdentity(profile: profile))

        XCTAssertTrue(label.contains("AB12CD34"))
        XCTAssertTrue(label.contains("Z9Y8X7W6"))
        XCTAssertFalse(label.contains("private-prefix"), "long Bridge identifiers must expose only the documented safe suffix")
    }

    func testTaskHistoryLegacySnapshotShowsUnknownForMissingOptionalIdentity() throws {
        let legacyProfile = AgentConnectionProfile(
            id: UUID(uuidString: "77777777-7777-4777-8777-777777777777")!, name: "旧记录电脑",
            kind: .hermes, endpoint: "legacy endpoint without a URL", credentialReference: "legacy-credential-secret",
            transport: .bridge
        )
        let encodedSnapshot = try JSONEncoder().encode(AgentTaskConnectionIdentity(profile: legacyProfile))
        let decodedSnapshot = try JSONDecoder().decode(AgentTaskConnectionIdentity.self, from: encodedSnapshot)
        let label = AgentTaskHistoryDestinationLabel.make(decodedSnapshot)

        XCTAssertTrue(label.contains("Hermes"))
        XCTAssertTrue(label.contains("未知主机:端口未知"))
        XCTAssertTrue(label.contains("Bridge 助手 未知"))
        XCTAssertTrue(label.contains("实例 未知"))
        XCTAssertTrue(label.contains("#77777777"))
        XCTAssertFalse(label.contains("legacy-credential-secret"))
    }

    func testTaskHistoryDestinationUsesSafeHostPortDefaultsAndIPv6AndRejectsInvalidHosts() {
        func label(for endpoint: String) -> String {
            let profile = AgentConnectionProfile(
                id: UUID(uuidString: "88888888-8888-4888-8888-888888888888")!,
                name: "fixture", endpoint: endpoint
            )
            return AgentTaskHistoryDestinationLabel.make(AgentTaskConnectionIdentity(profile: profile))
        }

        XCTAssertTrue(label(for: "http://default-http.fixture.invalid").contains("default-http.fixture.invalid:80"))
        XCTAssertTrue(label(for: "https://default-https.fixture.invalid").contains("default-https.fixture.invalid:443"))
        XCTAssertTrue(label(for: "https://[2001:db8::7]:8443/path").contains("[2001:db8::7]:8443"),
                      "Foundation returns the IPv6 host with brackets; formatting must not add a second pair")

        for invalidPort in ["https://invalid-port.fixture.invalid:0", "https://invalid-port.fixture.invalid:65536"] {
            let invalidPortLabel = label(for: invalidPort)
            XCTAssertTrue(invalidPortLabel.contains("invalid-port.fixture.invalid:端口未知"), invalidPort)
        }
        let malformedPortLabel = label(for: "https://malformed-port.fixture.invalid:bad")
        XCTAssertTrue(malformedPortLabel.contains("未知主机:端口未知"),
                      "a URLComponents parse failure must fail closed without pretending the host is verified")

        let escapedControlHost = label(for: "https://unsafe%0Ahost.fixture.invalid:9443")
        XCTAssertTrue(escapedControlHost.contains("未知主机:端口未知"))
        XCTAssertFalse(escapedControlHost.contains("\n"), "encoded controls in URLComponents.host must never enter visible text")
        XCTAssertFalse(escapedControlHost.contains("unsafe"), "reject the entire unsafe host rather than display a partial identity")
    }

    private func videoAttachmentConnection() -> AgentTaskConnectionIdentity {
        AgentTaskConnectionIdentity(profile: AgentConnectionProfile(
            id: UUID(uuidString: "11111111-1111-4111-8111-111111111111")!, name: "Video fixture", kind: .builtinVideo, endpoint: "https://video.fixture",
            transport: .bridge, bridgeID: "bridge-fixture", instanceID: "instance-fixture",
            certSHA256: String(repeating: "a", count: 64), revision: 3))
    }

    func testNoteVideoAttachmentStoreAssociatesIdempotentlyAndRemoves() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("note-video-attachments-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let source = root.appendingPathComponent("verified.mp4")
        let bytes = Data([0, 0, 0, 24, 0x66, 0x74, 0x79, 0x70, 0x69, 0x73, 0x6f, 0x6d, 1, 2, 3])
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        try bytes.write(to: source)
        let digest = SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined()
        let store = NoteVideoAttachmentStore(directory: root.appendingPathComponent("attachments", isDirectory: true))
        let note = NoteDocument(id: "roundtrip-note", title: "原笔记", updatedAt: 1234)
        let noteBefore = try note.encoded()
        let artifact = AgentTaskArtifact(id: "video-result", name: "讲解.mp4", mediaType: "video/mp4",
                                         sizeBytes: bytes.count, sha256: digest)
        let first = try store.associate(noteID: "roundtrip-note", taskID: UUID(), remoteTaskID: "server-task",
            sourceRevision: 7, sourceSnapshotSHA256: String(repeating: "a", count: 64), connection: videoAttachmentConnection(), artifact: artifact, verifiedFile: source)
        let repeated = try store.associate(noteID: "roundtrip-note", taskID: first.taskID, remoteTaskID: "server-task",
            sourceRevision: 7, sourceSnapshotSHA256: String(repeating: "a", count: 64), connection: videoAttachmentConnection(), artifact: artifact, verifiedFile: source)
        XCTAssertEqual(first, repeated)
        XCTAssertEqual(try store.attachments(noteID: "roundtrip-note"), [first])
        let saved = try store.fileURL(noteID: "roundtrip-note", attachmentID: first.id)
        XCTAssertNotEqual(saved, source)
        XCTAssertEqual(try Data(contentsOf: saved), bytes)
        XCTAssertEqual(try note.encoded(), noteBefore, "video association must not mutate the source note")
        try store.remove(noteID: "roundtrip-note", attachmentID: first.id)
        XCTAssertTrue(try store.attachments(noteID: "roundtrip-note").isEmpty)
        XCTAssertFalse(FileManager.default.fileExists(atPath: saved.path))
    }

    func testNoteVideoAttachmentStoreRejectsBadDigestPathAndContentType() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("note-video-attachments-invalid-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: root) }
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let source = root.appendingPathComponent("verified.mp4")
        let bytes = Data("local-video-fixture".utf8)
        try bytes.write(to: source)
        let store = NoteVideoAttachmentStore(directory: root.appendingPathComponent("attachments", isDirectory: true))
        let digest = SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined()
        let badArtifacts = [
            AgentTaskArtifact(id: "../escape", name: "x.mp4", mediaType: "video/mp4", sizeBytes: bytes.count, sha256: digest),
            AgentTaskArtifact(id: "video", name: "x.mp4", mediaType: "application/json", sizeBytes: bytes.count, sha256: digest),
            AgentTaskArtifact(id: "video", name: "x.mp4", mediaType: "video/mp4", sizeBytes: bytes.count, sha256: String(repeating: "0", count: 64)),
        ]
        for artifact in badArtifacts {
            XCTAssertThrowsError(try store.associate(noteID: "n", taskID: UUID(), remoteTaskID: "task",
                sourceRevision: 1, sourceSnapshotSHA256: String(repeating: "a", count: 64), connection: videoAttachmentConnection(), artifact: artifact, verifiedFile: source))
        }
        XCTAssertThrowsError(try store.associate(noteID: "../outside", taskID: UUID(), remoteTaskID: "task",
            sourceRevision: 1, sourceSnapshotSHA256: String(repeating: "a", count: 64), connection: videoAttachmentConnection(),
            artifact: AgentTaskArtifact(id: "video", name: "x.mp4", mediaType: "video/mp4", sizeBytes: bytes.count, sha256: digest), verifiedFile: source))
    }

    func testNoteVideoAttachmentStoreDetectsTamperingAndConflictingRetry() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("note-video-attachments-tamper-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: root) }
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let source = root.appendingPathComponent("verified.mp4")
        let bytes = Data("verified-mp4-bytes".utf8)
        try bytes.write(to: source)
        let digest = SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined()
        let store = NoteVideoAttachmentStore(directory: root.appendingPathComponent("attachments", isDirectory: true))
        let task = UUID()
        let connection = videoAttachmentConnection()
        let artifact = AgentTaskArtifact(id: "video", name: "video.mp4", mediaType: "video/mp4", sizeBytes: bytes.count, sha256: digest)
        let value = try store.associate(noteID: "note", taskID: task, remoteTaskID: "task", sourceRevision: 1,
            sourceSnapshotSHA256: String(repeating: "b", count: 64), connection: connection, artifact: artifact, verifiedFile: source)
        let sibling = try store.associate(noteID: "note", taskID: UUID(), remoteTaskID: "task-2", sourceRevision: 1,
            sourceSnapshotSHA256: String(repeating: "b", count: 64), connection: connection,
            artifact: AgentTaskArtifact(id: "video-2", name: "video-2.mp4", mediaType: "video/mp4", sizeBytes: bytes.count, sha256: digest), verifiedFile: source)
        let path = try store.fileURL(noteID: "note", attachmentID: value.id)
        try Data("tampered".utf8).write(to: path, options: .atomic)
        XCTAssertThrowsError(try store.fileURL(noteID: "note", attachmentID: value.id))
        XCTAssertEqual(Set(try store.attachments(noteID: "note").map(\.id)), Set([value.id, sibling.id]))
        XCTAssertNoThrow(try store.fileURL(noteID: "note", attachmentID: sibling.id))
        let noteDirectory = root.appendingPathComponent("attachments", isDirectory: true)
            .appendingPathComponent(SHA256.hash(data: Data("note".utf8)).map { String(format: "%02x", $0) }.joined(), isDirectory: true)
        let interruptedCopy = noteDirectory.appendingPathComponent("\(UUID().uuidString.lowercased()).partial")
        try Data("interrupted copy".utf8).write(to: interruptedCopy)
        XCTAssertEqual(try store.attachments(noteID: "note").count, 2, "an interrupted generated copy must not hide valid sidecars")
        let conflicting = AgentTaskArtifact(id: "video", name: "video.mp4", mediaType: "video/mp4",
                                            sizeBytes: bytes.count, sha256: digest)
        XCTAssertThrowsError(try store.associate(noteID: "note", taskID: task, remoteTaskID: "other-task", sourceRevision: 1,
            sourceSnapshotSHA256: String(repeating: "b", count: 64), connection: connection, artifact: conflicting, verifiedFile: source))
        try store.remove(noteID: "note", attachmentID: value.id)
        XCTAssertEqual(try store.attachments(noteID: "note"), [sibling])
    }
    func testNoteVideoAttachmentStoreSerializesIndependentInstancesAndBlocksDeletedSource() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("note-video-attachments-concurrent-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: root) }
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let source = root.appendingPathComponent("verified.mp4")
        let bytes = Data("parallel-video-fixture".utf8)
        try bytes.write(to: source)
        let digest = SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined()
        let directory = root.appendingPathComponent("attachments", isDirectory: true)
        let firstStore = NoteVideoAttachmentStore(directory: directory)
        let secondStore = NoteVideoAttachmentStore(directory: directory)
        let connection = videoAttachmentConnection()
        let taskID = UUID()
        let artifact = AgentTaskArtifact(id: "parallel-video", name: "video.mp4", mediaType: "video/mp4", sizeBytes: bytes.count, sha256: digest)
        let resultsLock = NSLock()
        var results: [NoteVideoAttachment] = []
        var failures: [Error] = []
        DispatchQueue.concurrentPerform(iterations: 2) { index in
            do {
                let store = index == 0 ? firstStore : secondStore
                let value = try store.associate(noteID: "parallel-note", taskID: taskID, remoteTaskID: "remote-task",
                    sourceRevision: 9, sourceSnapshotSHA256: String(repeating: "c", count: 64),
                    connection: connection, artifact: artifact, verifiedFile: source)
                resultsLock.lock(); results.append(value); resultsLock.unlock()
            } catch {
                resultsLock.lock(); failures.append(error); resultsLock.unlock()
            }
        }
        XCTAssertTrue(failures.isEmpty)
        XCTAssertEqual(results.count, 2)
        XCTAssertEqual(Set(results.map(\.id)).count, 1, "separate store instances must converge on one idempotent association")

        NoteVideoAttachmentStore.markSourceDeleted("parallel-note")
        XCTAssertThrowsError(try firstStore.associate(noteID: "parallel-note", taskID: UUID(), remoteTaskID: "other-task",
            sourceRevision: 9, sourceSnapshotSHA256: String(repeating: "c", count: 64),
            connection: connection, artifact: artifact, verifiedFile: source))
        NoteVideoAttachmentStore.markSourcePresent("parallel-note")
        XCTAssertEqual(try secondStore.attachments(noteID: "parallel-note").count, 1)
    }

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
        let legacyBundlePayload = try AgentTaskPayload(title: "微积分", input: "Explain the lesson",
            source: AgentTaskSource(noteID: "note-1", noteRevision: 1), bundle: data)
        XCTAssertNil(legacyBundlePayload.requiredCapability, "legacy video payloads keep the optional paper capability absent")
    }

    func testPaperTaskBundleBindsCompletePDFAndOrderedReadableContext() throws {
        XCTAssertEqual(PaperTaskPreset.findGapsAndPractice.rawValue, "find_gaps_and_practice")
        XCTAssertEqual(PaperTaskPreset.explainerVideo.rawValue, "explain_video")
        let markdown = "# 函数与图像\n\n第 1 页：函数先增后减。\n\n## 本笔记中较早纸面版本的 AI 交流\n\n### 用户\n这里是同一笔记的历史提问。"
        let pdf = Data("%PDF-1.7\nfixture-page-content\n%%EOF".utf8)
        let archive = try PaperTaskBundleIO.make(noteID: "note-paper-1", noteRevision: 42,
            title: "函数与图像", markdown: markdown, pdf: pdf,
            instruction: "找理解漏洞并出练习", presetID: PaperTaskPreset.findGapsAndPractice.rawValue,
            stylePrompt: PaperTaskVideoStyle.stepByStep.prompt)
        XCTAssertLessThanOrEqual(archive.count, PaperTaskBundleIO.maximumArchiveBytes)
        let files = try unzipStored(archive)
        XCTAssertEqual(Set(files.keys), Set([
            "request.json", "input/manifest.json", "input/content.md", "input/paper.pdf", "work/.keep", "output/.keep"
        ]))
        XCTAssertEqual(files["input/content.md"], Data(markdown.utf8))
        XCTAssertEqual(files["input/paper.pdf"], pdf)

        let request = try XCTUnwrap(JSONSerialization.jsonObject(with: try XCTUnwrap(files["request.json"])) as? [String: Any])
        XCTAssertEqual(request["schema_version"] as? String, "1.0")
        XCTAssertEqual(request["task_type"] as? String, "note.work.v1")
        XCTAssertNil(request["required_capability"], "capability is carried by the outer task payload, not duplicated in the task schema")
        let source = try XCTUnwrap(request["source"] as? [String: Any])
        XCTAssertEqual(source["note_id"] as? String, "note-paper-1")
        XCTAssertEqual(source["note_revision"] as? Int, 42)
        XCTAssertEqual(source["entrypoint"] as? String, "input/content.md")
        let brief = try XCTUnwrap(request["brief"] as? [String: Any])
        XCTAssertEqual(brief["preset_id"] as? String, PaperTaskPreset.findGapsAndPractice.rawValue)
        XCTAssertEqual(brief["style_prompt"] as? String, PaperTaskVideoStyle.stepByStep.prompt)

        let manifest = try XCTUnwrap(JSONSerialization.jsonObject(with: try XCTUnwrap(files["input/manifest.json"])) as? [String: Any])
        XCTAssertEqual(manifest["schema_version"] as? String, "1.0")
        let rows = try XCTUnwrap(manifest["files"] as? [[String: Any]])
        XCTAssertEqual(rows.compactMap { $0["path"] as? String }, ["input/content.md", "input/paper.pdf"])
        for (index, pair) in zip(rows, ["input/content.md", "input/paper.pdf"]).enumerated() {
            let (row, path) = pair
            let data = try XCTUnwrap(files[path])
            let digest = SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
            XCTAssertEqual(row["media_type"] as? String, index == 0 ? "text/markdown" : "application/pdf")
            XCTAssertEqual(row["size_bytes"] as? Int, data.count)
            XCTAssertEqual(row["sha256"] as? String, digest)
        }
        let canonical = rows.map { row in
            "\(row["path"] as! String)\0\(row["size_bytes"] as! Int)\0\(row["sha256"] as! String)\n"
        }.joined()
        let bundleDigest = SHA256.hash(data: Data(canonical.utf8)).map { String(format: "%02x", $0) }.joined()
        XCTAssertEqual(source["bundle_sha256"] as? String, bundleDigest)

        let noStyleArchive = try PaperTaskBundleIO.make(noteID: "note-paper-1", noteRevision: 42,
            title: "函数与图像", markdown: markdown, pdf: pdf,
            instruction: "生成可分享讲解视频", presetID: PaperTaskPreset.explainerVideo.rawValue,
            stylePrompt: "  \n ")
        let noStyleFiles = try unzipStored(noStyleArchive)
        let noStyleRequest = try XCTUnwrap(JSONSerialization.jsonObject(with: try XCTUnwrap(noStyleFiles["request.json"])) as? [String: Any])
        let noStyleBrief = try XCTUnwrap(noStyleRequest["brief"] as? [String: Any])
        XCTAssertNil(noStyleBrief["style_prompt"], "an empty optional style is omitted from the wire request")
    }

    func testPaperTaskBundleRejectsInvalidAndOversizedPaperBeforePackaging() throws {
        XCTAssertThrowsError(try PaperTaskBundleIO.make(noteID: "note", noteRevision: 1, title: "Paper",
            markdown: "context", pdf: Data("not a PDF".utf8), instruction: "help", presetID: "continue")) { error in
            XCTAssertEqual(error as? PaperTaskBundleError, .invalidPDF)
        }
        let oversizedPDF = Data("%PDF-1.7\n".utf8) + Data(repeating: 0x41, count: PaperTaskBundleIO.maximumExpandedBytes)
        XCTAssertThrowsError(try PaperTaskBundleIO.make(noteID: "note", noteRevision: 1, title: "Paper",
            markdown: "context", pdf: oversizedPDF, instruction: "help", presetID: "continue")) { error in
            XCTAssertEqual(error as? PaperTaskBundleError, .tooLarge)
        }
        let archiveOversizedPDF = Data("%PDF-1.7\n".utf8) + Data(repeating: 0x41, count: PaperTaskBundleIO.maximumArchiveBytes)
        XCTAssertThrowsError(try PaperTaskBundleIO.make(noteID: "note", noteRevision: 1, title: "Paper",
            markdown: "context", pdf: archiveOversizedPDF, instruction: "help", presetID: "continue")) { error in
            XCTAssertEqual(error as? PaperTaskBundleError, .tooLarge)
        }
    }

    func testPaperTaskCapabilityIsCheckedWhenCreatingAndSubmitting() async throws {
        let (defaults, suite) = makeDefaults()
        defer { defaults.removePersistentDomain(forName: suite) }
        let tokens = MemoryTokenStore()
        let connections = AgentConnectionStore(defaults: defaults, keychain: tokens)
        let initial = try connections.create(name: "Study Mac", kind: .hermes, endpoint: "https://fixture.test",
            token: "fixture-token", transport: .bridge, bridgeID: "bridge-1", instanceID: "instance-1")
        let baseCapabilities = ["run_submission": true, "task_bundle": true]
        XCTAssertTrue(connections.applyProbeSuccess(id: initial.id, revision: initial.revision, capabilities: baseCapabilities))
        let taskRoot = FileManager.default.temporaryDirectory.appendingPathComponent("paper-task-capability-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: taskRoot) }
        let tasks = AgentTaskStore(fileURL: taskRoot)
        var requestCount = 0
        let session = fixtureSession { _ in
            requestCount += 1
            return FixtureResponse(status: 202, json: ["task_id": "unexpected", "status": "running", "instance_id": "instance-1"])
        }
        let service = AgentTaskService(connectionStore: connections, taskStore: tasks,
            client: AgentTaskClient(session: session))
        let payload = try AgentTaskPayload(title: "Paper", input: "Use attached paper",
            source: AgentTaskSource(noteID: "note", noteRevision: 1), bundle: Data("zip".utf8),
            requiredCapability: "note_context_bundle")
        XCTAssertThrowsError(try AgentTaskPayload(title: "Paper", input: "Use attached paper",
            source: AgentTaskSource(noteID: "note", noteRevision: 1), bundle: Data("zip".utf8),
            requiredCapability: "another_capability")) { error in
            XCTAssertEqual(error as? AgentTaskError, .invalidPayload)
        }
        XCTAssertThrowsError(try AgentTaskPayload(title: "Paper", input: "Use attached paper",
            source: AgentTaskSource(noteID: "note", noteRevision: 1), requiredCapability: "note_context_bundle")) { error in
            XCTAssertEqual(error as? AgentTaskError, .invalidPayload)
        }

        XCTAssertThrowsError(try service.create(connectionID: initial.id, payload: payload)) { error in
            XCTAssertEqual(error as? AgentTaskError, .capabilityUnavailable("note_context_bundle"))
        }

        let supported = baseCapabilities.merging(["note_context_bundle": true]) { _, new in new }
        XCTAssertTrue(connections.applyProbeSuccess(id: initial.id, revision: initial.revision, capabilities: supported))
        let local = try service.create(connectionID: initial.id, payload: payload)
        XCTAssertEqual(local.payload.requiredCapability, "note_context_bundle")
        let wire = try XCTUnwrap(JSONSerialization.jsonObject(with: local.payload.canonicalData) as? [String: Any])
        XCTAssertEqual(wire["required_capability"] as? String, "note_context_bundle")

        XCTAssertTrue(connections.applyProbeSuccess(id: initial.id, revision: initial.revision, capabilities: baseCapabilities))
        do {
            _ = try await service.submit(id: local.id)
            XCTFail("a removed context capability must prevent a persisted task from being submitted")
        } catch let error as AgentTaskError {
            XCTAssertEqual(error, .capabilityUnavailable("note_context_bundle"))
        }
        XCTAssertEqual(requestCount, 0, "capability loss must be caught before an HTTP request")

        let legacy = try AgentTaskPayload(title: "Legacy", input: "Text only", source: AgentTaskSource(noteID: "note", noteRevision: 1))
        let legacyWire = try XCTUnwrap(JSONSerialization.jsonObject(with: legacy.canonicalData) as? [String: Any])
        XCTAssertNil(legacyWire["required_capability"], "old task envelopes keep their optional wire field absent")
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

    func testBridgeCapabilityProbePreservesReadOnlyHermesAndBuiltinConnections() async throws {
        func response(kind: String, features: [String: Any]) -> FixtureResponse {
            FixtureResponse(json: [
                "object": "padnote.agent.capabilities", "protocol_version": 1,
                "bridge_id": "bridge-1", "instance_id": "instance-1", "kind": kind,
                "features": features
            ])
        }

        let builtinReadOnly = fixtureSession { _ in
            response(kind: "builtin_video", features: [
                "video_task_submission": false, "video_operations": false,
                "video_production": false, "run_status": true, "artifacts": true,
                "run_submission": false
            ])
        }
        let builtinConfig = AgentConnectionConfig(
            kind: .builtinVideo, endpoint: "https://fixture.test", token: "device",
            transport: .bridge, bridgeID: "bridge-1", instanceID: "instance-1"
        )
        let builtin = try await AgentConnectionClient(session: builtinReadOnly).probeCapabilities(builtinConfig)
        XCTAssertEqual(builtin.capabilities["video_operations"], false)
        XCTAssertEqual(builtin.capabilities["run_status"], true)
        XCTAssertEqual(builtin.capabilities["artifacts"], true)

        let hermesReadOnly = fixtureSession { _ in
            response(kind: "hermes", features: [
                "run_submission": false, "run_status": true, "run_stop": false,
                "task_bundle": true, "video_operations": false
            ])
        }
        let hermesConfig = AgentConnectionConfig(
            kind: .hermes, endpoint: "https://fixture.test", token: "device",
            transport: .bridge, bridgeID: "bridge-1", instanceID: "instance-1"
        )
        let hermes = try await AgentConnectionClient(session: hermesReadOnly).probeCapabilities(hermesConfig)
        XCTAssertEqual(hermes.capabilities["run_submission"], false)
        XCTAssertEqual(hermes.capabilities["run_status"], true)

        let unsafeSubmission = fixtureSession { _ in
            response(kind: "builtin_video", features: ["run_submission": true, "run_status": true])
        }
        do {
            _ = try await AgentConnectionClient(session: unsafeSubmission).probeCapabilities(builtinConfig)
            XCTFail("builtin video must never advertise generic run submission")
        } catch let error as AgentConnectionError {
            XCTAssertEqual(error, .invalidResponse)
        }

        let numericCapability = fixtureSession { _ in
            response(kind: "builtin_video", features: ["video_production": 1, "run_status": true, "run_submission": false])
        }
        do {
            _ = try await AgentConnectionClient(session: numericCapability).probeCapabilities(builtinConfig)
            XCTFail("JSON number 1 must not be accepted as a Boolean capability")
        } catch let error as AgentConnectionError {
            XCTAssertEqual(error, .invalidResponse)
        }
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

    func testPairingClaimsAndStoresBuiltinVideoConnection() async throws {
        let (defaults, suite) = makeDefaults()
        defer { defaults.removePersistentDomain(forName: suite) }
        let tokenStore = MemoryTokenStore()
        let store = AgentConnectionStore(defaults: defaults, keychain: tokenStore)
        let pin = String(repeating: "a", count: 64)
        let code = AgentPairingCode(url: "https://fixture.test/base", bridgeID: "bridge-1",
                                    code: "one-time-code", certSHA256: pin)
        let session = fixtureSession { request in
            if request.url?.path.hasSuffix("/request") == true {
                return FixtureResponse(status: 202, json: [
                    "request_id": "request-video", "poll_token": "poll-video",
                    "expires_at": Date().addingTimeInterval(60).timeIntervalSince1970,
                    "status": "pending"
                ])
            }
            return FixtureResponse(status: 200, json: [
                "bridge_id": "bridge-1", "device_id": "device-1",
                "connections": [[
                    "instance_id": "video-instance-1", "kind": "builtin_video",
                    "name": "内置视频工作流", "token": "fixture-device-token"
                ]]
            ])
        }
        let client = AgentBridgePairingClient(session: session)
        let request = try await client.request(code: code, deviceID: "device-1", deviceName: "Fixture iPad")
        let claim = try await client.claim(code: code, request: request)
        guard case .approved(let bridgeID, let deviceID, let connections) = claim else {
            return XCTFail("a desktop builtin_video claim should be approved")
        }
        XCTAssertEqual(bridgeID, "bridge-1")
        XCTAssertEqual(deviceID, "device-1")
        let connection = try XCTUnwrap(connections.first)
        XCTAssertEqual(connection.kind, .builtinVideo)
        let profile = try store.create(name: connection.name, kind: connection.kind,
            endpoint: code.url, token: connection.token, transport: .bridge,
            bridgeID: bridgeID, instanceID: connection.instanceID,
            certSHA256: code.certSHA256)
        XCTAssertEqual(profile.kind, .builtinVideo)
        XCTAssertEqual(profile.transport, .bridge)
        XCTAssertEqual(profile.bridgeID, "bridge-1")
        XCTAssertEqual(profile.instanceID, "video-instance-1")
        XCTAssertEqual(profile.certSHA256, pin)
        XCTAssertEqual(store.token(reference: profile.credentialReference), "fixture-device-token")
    }

    func testPairingStillRejectsOpenClawClaimForExecutionConnection() async throws {
        let code = AgentPairingCode(url: "https://fixture.test/base", bridgeID: "bridge-1", code: "one-time-code")
        let session = fixtureSession { request in
            if request.url?.path.hasSuffix("/request") == true {
                return FixtureResponse(status: 202, json: [
                    "request_id": "request-openclaw", "poll_token": "poll-openclaw",
                    "expires_at": Date().addingTimeInterval(60).timeIntervalSince1970,
                    "status": "pending"
                ])
            }
            return FixtureResponse(status: 200, json: [
                "bridge_id": "bridge-1", "device_id": "device-1",
                "connections": [[
                    "instance_id": "openclaw-instance-1", "kind": "openclaw",
                    "name": "OpenClaw", "token": "fixture-device-token"
                ]]
            ])
        }
        let client = AgentBridgePairingClient(session: session)
        let request = try await client.request(code: code, deviceID: "device-1", deviceName: "Fixture iPad")
        do {
            _ = try await client.claim(code: code, request: request)
            XCTFail("OpenClaw must remain rejected for this execution connection flow")
        } catch let error as AgentPairingError {
            XCTAssertEqual(error, .invalidResponse)
        }
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

    func testBridgeFollowupUsesOnePersistedChildAndKeepsSourceAndHistory() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("agent-task-followup-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let profile = AgentConnectionProfile(name: "Home", endpoint: "https://fixture.test", transport: .bridge,
            bridgeID: "bridge-1", instanceID: "instance-1", verifiedAt: Date(),
            capabilities: ["run_submission": true, "run_status": true])
        let store = AgentTaskStore(fileURL: root)
        let payload = try AgentTaskPayload(title: "Review", input: "First ask", source: AgentTaskSource(noteID: "note-7", noteRevision: 9))
        let parent = try store.create(profile: profile, payload: payload)
        let completed = try store.mutate(id: parent.id, expectedRevision: parent.recordRevision) {
            $0.remoteTaskID = "bridge-task-1"
            $0.conversationID = "bridge-task-1"
            $0.status = .completed
            $0.output = "First result"
            $0.followupAvailable = true
        }

        let child = try store.createFollowup(parentID: completed.id, profile: profile, input: "Explain why")
        XCTAssertThrowsError(try store.createFollowup(parentID: completed.id, profile: profile, input: "Different composer text")) {
            XCTAssertEqual($0 as? AgentTaskError, .followupAlreadyExists)
        }
        let repeated = try XCTUnwrap(store.followup(of: completed.id))
        XCTAssertEqual(repeated.id, child.id)
        XCTAssertEqual(repeated.payload, child.payload)
        XCTAssertEqual(child.payload.parentTaskID, "bridge-task-1")
        XCTAssertEqual(child.payload.source, payload.source)
        XCTAssertNil(child.payload.bundleBase64)
        XCTAssertEqual(child.conversationID, "bridge-task-1")
        XCTAssertEqual(try store.history(for: child.id).map(\.id), [parent.id, child.id])

        let coldStore = AgentTaskStore(fileURL: root)
        XCTAssertEqual(try coldStore.followup(of: parent.id)?.payload.canonicalData, child.payload.canonicalData)
        XCTAssertEqual(try coldStore.history(for: parent.id).map(\.output), ["First result", nil])
    }

    func testLegacyTaskFilesWithoutFollowupFieldsKeepTheirOriginalPayloadHash() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("agent-task-legacy-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let store = AgentTaskStore(fileURL: root)
        let payload = try AgentTaskPayload(clientTaskID: "22222222-2222-2222-2222-222222222222", title: "Legacy", input: "Keep hash", source: AgentTaskSource(noteID: "manual", noteRevision: 1))
        let task = try store.create(profile: AgentConnectionProfile(name: "Old", endpoint: "https://fixture.test"), payload: payload)
        let directory = root.appendingPathComponent(task.id.uuidString.lowercased(), isDirectory: true)
        for name in ["immutable.json", "state.json"] {
            let file = directory.appendingPathComponent(name)
            let data = try Data(contentsOf: file)
            var object = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
            object.removeValue(forKey: name == "immutable.json" ? "parentTaskLocalID" : "conversationID")
            if name == "state.json" {
                object.removeValue(forKey: "followupAvailable")
                object.removeValue(forKey: "followupReason")
            }
            try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys]).write(to: file, options: .atomic)
        }

        let coldStore = AgentTaskStore(fileURL: root)
        let restored = try XCTUnwrap(coldStore.task(id: task.id))
        XCTAssertEqual(restored.payload.canonicalData, payload.canonicalData)
        XCTAssertEqual(restored.payloadSHA256, task.payloadSHA256)
        XCTAssertFalse(restored.followupAvailable)
    }

    func testBridgeFollowupSubmissionSendsPersistedParentAndChecksResponseLineage() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("agent-task-followup-wire-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let profile = AgentConnectionProfile(name: "Home", endpoint: "https://fixture.test", transport: .bridge,
            bridgeID: "bridge-1", instanceID: "instance-1")
        let payload = try AgentTaskPayload(title: "Review", input: "Continue", source: AgentTaskSource(noteID: "note-7", noteRevision: 9), parentTaskID: "parent-1")
        var task = AgentTaskRecord(profile: profile, payload: payload)
        task.conversationID = "root-1"
        let session = fixtureSession { request in
            XCTAssertEqual(request.url?.path, "/padnote/v1/agents/instance-1/runs")
            let json = try! XCTUnwrap(request.httpBody.flatMap { try? JSONSerialization.jsonObject(with: $0) as? [String: Any] })
            XCTAssertEqual(json["parent_task_id"] as? String, "parent-1")
            XCTAssertEqual(json["client_task_id"] as? String, payload.clientTaskID)
            XCTAssertEqual(request.value(forHTTPHeaderField: "Idempotency-Key"), payload.clientTaskID)
            return FixtureResponse(status: 202, json: [
                "task_id": "child-1", "instance_id": "instance-1", "status": "running",
                "conversation_id": "root-1", "parent_task_id": "parent-1",
                "followup_available": false, "followup_reason": "本轮尚未完成"
            ])
        }
        let state = try await AgentTaskClient(session: session).submit(task: task, token: "device-token")
        XCTAssertEqual(state.remoteTaskID, "child-1")
        XCTAssertEqual(state.conversationID, "root-1")
        XCTAssertEqual(state.parentTaskID, "parent-1")

        let mismatch = fixtureSession { _ in FixtureResponse(status: 202, json: [
            "task_id": "child-1", "instance_id": "instance-1", "status": "running",
            "conversation_id": "other-root", "parent_task_id": "parent-1"
        ]) }
        do {
            _ = try await AgentTaskClient(session: mismatch).submit(task: task, token: "device-token")
            XCTFail("responses from another conversation must be rejected")
        } catch let error as AgentTaskError {
            XCTAssertEqual(error, .invalidResponse)
        }
    }

    func testBridgeSubmittingTaskWithKnownRemoteIDReplaysExactRequest() async throws {
        let (defaults, suite) = makeDefaults()
        defer { defaults.removePersistentDomain(forName: suite) }
        let connections = AgentConnectionStore(defaults: defaults, keychain: MemoryTokenStore())
        let initial = try connections.create(name: "Fixture", kind: .hermes, endpoint: "https://fixture.test",
            token: "fixture-token", transport: .bridge, bridgeID: "bridge-1", instanceID: "instance-1")
        XCTAssertTrue(connections.applyProbeSuccess(id: initial.id, revision: initial.revision, capabilities: ["run_submission": true]))
        let profile = try XCTUnwrap(connections.profile(id: initial.id))
        let taskRoot = FileManager.default.temporaryDirectory.appendingPathComponent("agent-task-known-id-retry-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: taskRoot) }
        let tasks = AgentTaskStore(fileURL: taskRoot)
        let payload = try AgentTaskPayload(clientTaskID: "33333333-3333-4333-8333-333333333333", title: "Unknown submit",
            input: "Original body", source: AgentTaskSource(noteID: "manual", noteRevision: 1))
        let local = try tasks.create(profile: profile, payload: payload)
        _ = try tasks.mutate(id: local.id, expectedRevision: local.recordRevision) {
            $0.remoteTaskID = "bridge-pending-1"
            $0.conversationID = "bridge-pending-1"
            $0.status = .submitting
        }
        let captureLock = NSLock()
        var bodies = [Data]()
        var responseIndex = 0
        let session = fixtureSession { request in
            captureLock.lock(); defer { captureLock.unlock() }
            XCTAssertEqual(request.value(forHTTPHeaderField: "Idempotency-Key"), payload.clientTaskID)
            bodies.append(request.httpBody ?? Data())
            responseIndex += 1
            return FixtureResponse(status: 202, json: [
                "task_id": "bridge-pending-1", "instance_id": "instance-1",
                "status": responseIndex == 1 ? "submitting" : "running",
                "conversation_id": "bridge-pending-1", "parent_task_id": "",
                "followup_available": false, "followup_reason": "等待上游对账"
            ])
        }
        let service = AgentTaskService(connectionStore: connections, taskStore: tasks, client: AgentTaskClient(session: session))

        let first = try await service.submit(id: local.id)
        XCTAssertEqual(first.remoteTaskID, "bridge-pending-1")
        XCTAssertEqual(first.status, .submitting)
        XCTAssertEqual(try XCTUnwrap(tasks.task(id: local.id)).remoteTaskID, "bridge-pending-1")
        let second = try await service.submit(id: local.id)
        XCTAssertEqual(second.status, .running)
        XCTAssertEqual(bodies.count, 2)
        XCTAssertEqual(bodies[0], bodies[1], "replaying a known Bridge task id must keep the immutable payload bytes")
    }

    func testCompletedRefreshCannotRegressOrEraseSavedResult() async throws {
        let (defaults, suite) = makeDefaults()
        defer { defaults.removePersistentDomain(forName: suite) }
        let connections = AgentConnectionStore(defaults: defaults, keychain: MemoryTokenStore())
        let initial = try connections.create(name: "Fixture", kind: .hermes, endpoint: "https://fixture.test",
            token: "fixture-token", transport: .bridge, bridgeID: "bridge-1", instanceID: "instance-1")
        XCTAssertTrue(connections.applyProbeSuccess(id: initial.id, revision: initial.revision, capabilities: ["run_submission": true, "run_status": true]))
        let profile = try XCTUnwrap(connections.profile(id: initial.id))
        let taskRoot = FileManager.default.temporaryDirectory.appendingPathComponent("agent-task-terminal-refresh-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: taskRoot) }
        let tasks = AgentTaskStore(fileURL: taskRoot)
        let payload = try AgentTaskPayload(title: "Completed", input: "Original request", source: AgentTaskSource(noteID: "manual", noteRevision: 1))
        let local = try tasks.create(profile: profile, payload: payload)
        _ = try tasks.mutate(id: local.id, expectedRevision: local.recordRevision) {
            $0.remoteTaskID = "done-1"
            $0.conversationID = "done-1"
            $0.status = .completed
            $0.output = "Saved result"
            $0.artifacts = [AgentTaskArtifact(id: "saved-artifact", name: "saved.txt", mediaType: "text/plain", sizeBytes: 3, sha256: String(repeating: "a", count: 64))]
        }
        var responseIndex = 0
        let session = fixtureSession { _ in
            responseIndex += 1
            return FixtureResponse(json: [
                "task_id": "done-1", "instance_id": "instance-1",
                "status": responseIndex == 1 ? "completed" : "running",
                "output": "replacement result",
                "conversation_id": "done-1", "parent_task_id": "",
                "followup_available": false, "followup_reason": "已结束"
            ])
        }
        let service = AgentTaskService(connectionStore: connections, taskStore: tasks, client: AgentTaskClient(session: session))
        let refreshed = try await service.refresh(id: local.id)
        XCTAssertEqual(refreshed.status, .completed)
        XCTAssertEqual(refreshed.output, "Saved result", "a sparse terminal GET must preserve the saved result")
        XCTAssertEqual(refreshed.artifacts.map(\.id), ["saved-artifact"], "a terminal GET must preserve the saved artifact list")
        do {
            _ = try await service.refresh(id: local.id)
            XCTFail("a terminal task must not regress to running")
        } catch let error as AgentTaskError {
            XCTAssertEqual(error, .invalidResponse)
        }
        XCTAssertEqual(try tasks.task(id: local.id)?.output, "Saved result")
    }

    func testNonterminalRefreshClearsResolvedApprovalAndError() async throws {
        let (defaults, suite) = makeDefaults()
        defer { defaults.removePersistentDomain(forName: suite) }
        let connections = AgentConnectionStore(defaults: defaults, keychain: MemoryTokenStore())
        let initial = try connections.create(name: "Fixture", kind: .hermes, endpoint: "https://fixture.test",
            token: "fixture-token", transport: .bridge, bridgeID: "bridge-1", instanceID: "instance-1")
        XCTAssertTrue(connections.applyProbeSuccess(id: initial.id, revision: initial.revision, capabilities: ["run_submission": true, "run_status": true]))
        let profile = try XCTUnwrap(connections.profile(id: initial.id))
        let taskRoot = FileManager.default.temporaryDirectory.appendingPathComponent("agent-task-approval-refresh-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: taskRoot) }
        let tasks = AgentTaskStore(fileURL: taskRoot)
        let local = try tasks.create(profile: profile, payload: AgentTaskPayload(title: "Approval", input: "Run", source: AgentTaskSource(noteID: "manual", noteRevision: 1)))
        _ = try tasks.mutate(id: local.id, expectedRevision: local.recordRevision) {
            $0.remoteTaskID = "run-1"
            $0.conversationID = "run-1"
            $0.status = .waitingForApproval
            $0.approval = AgentTaskApproval(id: "approval-1", title: "Approve", description: "Pending")
            $0.error = "stale error"
        }
        let session = fixtureSession { _ in FixtureResponse(json: [
            "task_id": "run-1", "instance_id": "instance-1", "status": "running",
            "conversation_id": "run-1", "parent_task_id": ""
        ]) }
        let service = AgentTaskService(connectionStore: connections, taskStore: tasks, client: AgentTaskClient(session: session))
        let refreshed = try await service.refresh(id: local.id)
        XCTAssertEqual(refreshed.status, .running)
        XCTAssertNil(refreshed.approval)
        XCTAssertNil(refreshed.error)
    }

    func testLegacyBridgeResponseDoesNotOfferFollowup() async throws {
        let session = fixtureSession { _ in FixtureResponse(json: [
            "task_id": "root-1", "instance_id": "instance-1", "status": "completed", "output": "done"
        ]) }
        let profile = AgentConnectionProfile(name: "Home", endpoint: "https://fixture.test", transport: .bridge,
            bridgeID: "bridge-1", instanceID: "instance-1")
        var task = AgentTaskRecord(profile: profile, payload: try AgentTaskPayload(title: "Legacy", input: "Ask", source: AgentTaskSource(noteID: "manual", noteRevision: 1)))
        task.remoteTaskID = "root-1"
        let state = try await AgentTaskClient(session: session).status(task: task, token: "device-token")
        XCTAssertFalse(state.followupAvailable)
        XCTAssertNil(state.conversationID)
    }

    func testBridgeFollowupEligibilityRequiresReasonMetadata() async throws {
        let session = fixtureSession { _ in FixtureResponse(json: [
            "task_id": "root-1", "instance_id": "instance-1", "status": "completed", "output": "done",
            "conversation_id": "root-1", "parent_task_id": "", "followup_available": true
        ]) }
        let profile = AgentConnectionProfile(name: "Home", endpoint: "https://fixture.test", transport: .bridge,
            bridgeID: "bridge-1", instanceID: "instance-1")
        var task = AgentTaskRecord(profile: profile, payload: try AgentTaskPayload(title: "Root", input: "Ask", source: AgentTaskSource(noteID: "manual", noteRevision: 1)))
        task.remoteTaskID = "root-1"
        let state = try await AgentTaskClient(session: session).status(task: task, token: "device-token")
        XCTAssertFalse(state.followupAvailable, "all frozen lineage and eligibility metadata must be present")
    }

    func testBridgeFollowupEligibilityAllowsEmptyReasonMetadata() async throws {
        let session = fixtureSession { _ in FixtureResponse(json: [
            "task_id": "root-1", "instance_id": "instance-1", "status": "completed", "output": "done",
            "conversation_id": "root-1", "parent_task_id": "", "followup_available": true, "followup_reason": ""
        ]) }
        let profile = AgentConnectionProfile(name: "Home", endpoint: "https://fixture.test", transport: .bridge,
            bridgeID: "bridge-1", instanceID: "instance-1")
        var task = AgentTaskRecord(profile: profile, payload: try AgentTaskPayload(title: "Root", input: "Ask", source: AgentTaskSource(noteID: "manual", noteRevision: 1)))
        task.remoteTaskID = "root-1"
        let state = try await AgentTaskClient(session: session).status(task: task, token: "device-token")
        XCTAssertTrue(state.followupAvailable)
    }

    func testTaskDetailUsesInjectedStoresAndFixtureClient() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("agent-task-detail-fixture-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let (defaults, suite) = makeDefaults()
        defer { defaults.removePersistentDomain(forName: suite) }
        let taskStore = AgentTaskStore(fileURL: root)
        let connectionStore = AgentConnectionStore(defaults: defaults, keychain: MemoryTokenStore())
        let fixture = fixtureSession { _ in
            XCTFail("constructing the detail view must not start a network request")
            return FixtureResponse(json: [:])
        }
        let service = AgentTaskService(connectionStore: connectionStore, taskStore: taskStore, client: AgentTaskClient(session: fixture))
        let view = AgentTaskDetailView(taskID: UUID(), store: taskStore, service: service)
        XCTAssertTrue(type(of: view) == AgentTaskDetailView.self)
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

    func testDirectApprovalDenialSendsOnlyDenyChoice() async throws {
        let session = fixtureSession { request in
            XCTAssertEqual(request.url?.path, "/v1/runs/run-1/approval")
            let body = request.httpBody.flatMap { try? JSONSerialization.jsonObject(with: $0) as? [String: String] }
            XCTAssertEqual(body, ["request_id": "approval-1", "choice": "deny"])
            return FixtureResponse(json: [
                "object": "hermes.run.approval_response", "run_id": "run-1",
                "request_id": "approval-1", "choice": "deny", "resolved": 1
            ])
        }
        let profile = AgentConnectionProfile(name: "Direct", endpoint: "https://fixture.test/v1")
        let payload = try AgentTaskPayload(title: "Task", input: "Hello", source: AgentTaskSource(noteID: "manual", noteRevision: 1))
        var task = AgentTaskRecord(profile: profile, payload: payload)
        task.remoteTaskID = "run-1"; task.status = .waitingForApproval
        task.approval = AgentTaskApproval(id: "approval-1", title: "Review", description: "No authorization")
        let state = try await AgentTaskClient(session: session).approve(task: task, approvalID: "approval-1", decision: "deny", token: "secret")
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

    func testVideoProduceUsesApprovedCursorHashesAndStrictCloudTTSConsent() async throws {
        let profile = AgentConnectionProfile(name: "Home", kind: .builtinVideo, endpoint: "https://fixture.test/base",
            transport: .bridge, bridgeID: "bridge-1", instanceID: "instance-1",
            certSHA256: String(repeating: "a", count: 64))
        let bundle = try VideoTaskBundleIO.make(noteId: "note-1", noteRevision: 3, title: "Lesson",
            markdown: "# Lesson", audience: "Students", learningGoal: "Understand", durationSeconds: 90)
        let payload = try AgentTaskPayload(title: "Lesson", input: "Explain",
            source: AgentTaskSource(noteID: "note-1", noteRevision: 3), bundle: bundle)
        var task = AgentTaskRecord(profile: profile, payload: payload)
        task.remoteTaskID = "remote-1"
        let key = "22222222-2222-4222-8222-222222222222"
        let reviewHash = String(repeating: "c", count: 64)
        let lessonHash = String(repeating: "d", count: 64)
        let session = fixtureSession { request in
            XCTAssertEqual(request.httpMethod, "POST")
            XCTAssertEqual(request.url?.path, "/base/padnote/v1/agents/instance-1/runs/remote-1/video/operations")
            XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer device-token")
            XCTAssertEqual(request.value(forHTTPHeaderField: "Idempotency-Key"), key)
            let body = (try? JSONSerialization.jsonObject(with: request.httpBody ?? Data())) as? [String: Any]
            XCTAssertEqual(body?["action"] as? String, "produce")
            let parameters = body?["parameters"] as? [String: Any]
            XCTAssertEqual(Set(parameters?.keys ?? Dictionary<String, Any>().keys),
                           Set(["revision", "event_cursor", "review_sha256", "lesson_ir_sha256", "allow_cloud_tts"]))
            XCTAssertEqual(parameters?["revision"] as? Int, 1)
            XCTAssertEqual(parameters?["event_cursor"] as? Int, 4,
                           "production consumes the exact post-approval event cursor")
            XCTAssertEqual(parameters?["review_sha256"] as? String, reviewHash)
            XCTAssertEqual(parameters?["lesson_ir_sha256"] as? String, lessonHash)
            XCTAssertTrue((parameters?["allow_cloud_tts"] as? Bool) == true)
            return FixtureResponse(status: 202, json: [
                "object": "padnote.video.operation", "protocol_version": 1,
                "operation_id": "11111111-1111-4111-8111-111111111111", "task_id": "remote-1", "client_operation_id": key,
                "action": "produce", "status": "queued", "created_at": 1, "updated_at": 1,
                "result": NSNull(), "error": NSNull()
            ])
        }
        let response = try await AgentVideoOperationClient(session: session).submit(
            task: task, remoteTaskID: "remote-1", token: "device-token", action: .produce,
            revision: 1, eventCursor: 4, reviewSHA256: reviewHash, lessonIRSHA256: lessonHash,
            clientOperationID: key)
        XCTAssertEqual(response.status, .queued)
    }

    func testVideoProduceRejectsReceiptWithoutActualBooleanCloudTTSConsent() async throws {
        let profile = AgentConnectionProfile(name: "Home", kind: .builtinVideo, endpoint: "https://fixture.test",
            transport: .bridge, bridgeID: "bridge-1", instanceID: "instance-1",
            certSHA256: String(repeating: "a", count: 64))
        let bundle = try VideoTaskBundleIO.make(noteId: "note-1", noteRevision: 1, title: "Lesson",
            markdown: "# Lesson", audience: "Students", learningGoal: "Understand", durationSeconds: 60)
        let payload = try AgentTaskPayload(title: "Lesson", input: "Explain",
            source: AgentTaskSource(noteID: "note-1", noteRevision: 1), bundle: bundle)
        var task = AgentTaskRecord(profile: profile, payload: payload); task.remoteTaskID = "remote-1"
        let key = "22222222-2222-4222-8222-222222222222"
        let operationID = "11111111-1111-4111-8111-111111111111"
        let hash = String(repeating: "c", count: 64)
        let receipt: [String: Any] = ["operation_id": operationID, "attempt_id": "33333333-3333-4333-8333-333333333333",
            "action": "produce", "payload_digest": String(repeating: "e", count: 64),
            "source_snapshot_digest": String(repeating: "f", count: 64), "request_sha256": String(repeating: "a", count: 64),
            "input_event_cursor": 4, "result_event_cursor": 10, "revision": 1,
            "review_sha256": hash, "lesson_ir_sha256": hash, "allow_cloud_tts": "true"]
        let session = fixtureSession { _ in FixtureResponse(status: 202, json: [
            "object": "padnote.video.operation", "protocol_version": 1, "operation_id": operationID,
            "task_id": "remote-1", "client_operation_id": key, "action": "produce", "status": "succeeded",
            "created_at": 1, "updated_at": 2,
            "result": ["protocol_version": 1, "task_id": "remote-1", "status": "completed", "phase": "completed",
                "event_cursor": 10, "revision": 1, "review_sha256": hash, "lesson_ir_sha256": hash, "receipt": receipt],
            "error": NSNull()
        ]) }
        do {
            _ = try await AgentVideoOperationClient(session: session).submit(task: task, remoteTaskID: "remote-1",
                token: "device-token", action: .produce, revision: 1, eventCursor: 4,
                reviewSHA256: hash, lessonIRSHA256: hash, clientOperationID: key)
            XCTFail("a string that resembles true must not satisfy paid cloud-TTS consent")
        } catch let error as AgentVideoError {
            XCTAssertEqual(error, .malformedResponse)
        }
    }

    func testVideoInitializeSupportsHermesWithAndWithoutLegacyCertificatePin() async throws {
        for pin in [nil, String(repeating: "a", count: 64)] as [String?] {
            let task = try makeVideoRequestTask(kind: .hermes, pin: pin)
            let key = "22222222-2222-4222-8222-222222222222"
            var requestCount = 0
            let session = fixtureSession { request in
                requestCount += 1
                XCTAssertEqual(request.url?.scheme, "https")
                XCTAssertEqual(request.url?.path, "/base/padnote/v1/agents/instance-1/runs/remote-1/video/operations")
                return Self.videoOperationResponse(status: "queued", key: key)
            }
            let result = try await AgentVideoOperationClient(session: session).submit(
                task: task, remoteTaskID: "remote-1", token: "device-token", action: .initialize,
                clientOperationID: key)
            XCTAssertEqual(result.status, .queued)
            XCTAssertEqual(requestCount, 1)
        }
    }

    func testVideoInitializeSupportsBuiltinVideoWithPinnedCertificate() async throws {
        let task = try makeVideoRequestTask(kind: .builtinVideo, pin: String(repeating: "b", count: 64))
        let key = "22222222-2222-4222-8222-222222222222"
        var requestCount = 0
        let session = fixtureSession { request in
            requestCount += 1
            XCTAssertEqual(request.url?.scheme, "https")
            XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer device-token")
            return Self.videoOperationResponse(status: "queued", key: key)
        }
        let response = try await AgentVideoOperationClient(session: session).submit(
            task: task, remoteTaskID: "remote-1", token: "device-token", action: .initialize,
            clientOperationID: key)
        XCTAssertEqual(response.status, .queued)
        XCTAssertEqual(requestCount, 1)
    }

    func testVideoInitializeRejectsBuiltinVideoWithoutCertificatePinBeforeRequest() async throws {
        let task = try makeVideoRequestTask(kind: .builtinVideo, pin: nil)
        var requestCount = 0
        let session = fixtureSession { _ in
            requestCount += 1
            return Self.videoOperationResponse(status: "queued", key: "22222222-2222-4222-8222-222222222222")
        }
        do {
            _ = try await AgentVideoOperationClient(session: session).submit(
                task: task, remoteTaskID: "remote-1", token: "device-token", action: .initialize,
                clientOperationID: "22222222-2222-4222-8222-222222222222")
            XCTFail("builtin video must not use a CA-only connection without its pairing pin")
        } catch let error as AgentVideoError {
            XCTAssertEqual(error, .connectionChanged)
        }
        XCTAssertEqual(requestCount, 0)
    }

    func testVideoInitializeRejectsUnsupportedAgentKindBeforeRequest() async throws {
        let task = try makeVideoRequestTask(kind: .openClaw, pin: String(repeating: "a", count: 64))
        var requestCount = 0
        let session = fixtureSession { _ in
            requestCount += 1
            return Self.videoOperationResponse(status: "queued", key: "22222222-2222-4222-8222-222222222222")
        }
        do {
            _ = try await AgentVideoOperationClient(session: session).submit(
                task: task, remoteTaskID: "remote-1", token: "device-token", action: .initialize,
                clientOperationID: "22222222-2222-4222-8222-222222222222")
            XCTFail("only Hermes Bridge or pinned builtin-video connections may use video routes")
        } catch let error as AgentVideoError {
            XCTAssertEqual(error, .connectionChanged)
        }
        XCTAssertEqual(requestCount, 0)
    }

    func testVideoServiceRequiresVideoOperationsCapabilityBeforePosting() async throws {
        var requestCount = 0
        let session = fixtureSession { _ in
            requestCount += 1
            return Self.videoOperationResponse(status: "queued", key: "22222222-2222-4222-8222-222222222222")
        }
        let setup = try makeVideoCapabilityService(capabilities: ["video_operations": false, "video_production": true], session: session)
        defer { try? FileManager.default.removeItem(at: setup.root) }
        defer { UserDefaults(suiteName: setup.suite)?.removePersistentDomain(forName: setup.suite) }
        do {
            _ = try await setup.service.submit(taskID: setup.taskID, action: .initialize)
            XCTFail("video operations must be capability-gated before creating or posting an operation")
        } catch let error as AgentVideoError {
            XCTAssertEqual(error, .unavailable)
        }
        XCTAssertEqual(requestCount, 0)
    }

    func testVideoServiceRequiresVideoProductionCapabilityBeforePosting() async throws {
        var requestCount = 0
        let session = fixtureSession { _ in
            requestCount += 1
            return Self.videoOperationResponse(status: "queued", key: "22222222-2222-4222-8222-222222222222")
        }
        let setup = try makeVideoCapabilityService(capabilities: ["video_operations": true, "video_production": false], session: session)
        defer { try? FileManager.default.removeItem(at: setup.root) }
        defer { UserDefaults(suiteName: setup.suite)?.removePersistentDomain(forName: setup.suite) }
        do {
            _ = try await setup.service.submit(taskID: setup.taskID, action: .produce)
            XCTFail("paid production must require an explicitly probed video_production capability")
        } catch let error as AgentVideoError {
            XCTAssertEqual(error, .unavailable)
        }
        XCTAssertEqual(requestCount, 0)
    }

    func testVideoInitializeUsesFixedBridgeRouteAndIdempotencyHeader() async throws {
        let profile = AgentConnectionProfile(name: "Home", kind: .builtinVideo, endpoint: "https://fixture.test/base",
            transport: .bridge, bridgeID: "bridge-1", instanceID: "instance-1",
            certSHA256: String(repeating: "a", count: 64))
        let bundle = try VideoTaskBundleIO.make(noteId: "note-1", noteRevision: 3, title: "Lesson",
            markdown: "# Lesson", audience: "Students", learningGoal: "Understand", durationSeconds: 90)
        let payload = try AgentTaskPayload(title: "Lesson", input: "Explain",
            source: AgentTaskSource(noteID: "note-1", noteRevision: 3), bundle: bundle)
        var task = AgentTaskRecord(profile: profile, payload: payload)
        task.remoteTaskID = "remote-1"
        let key = UUID().uuidString.lowercased()
        let session = fixtureSession { request in
            XCTAssertEqual(request.httpMethod, "POST")
            XCTAssertEqual(request.url?.path, "/base/padnote/v1/agents/instance-1/runs/remote-1/video/operations")
            XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer device-token")
            XCTAssertEqual(request.value(forHTTPHeaderField: "Idempotency-Key"), key)
            let body = try? JSONSerialization.jsonObject(with: request.httpBody ?? Data()) as? [String: Any]
            XCTAssertEqual(body?["action"] as? String, "initialize")
            XCTAssertEqual(body?["parameters"] as? NSDictionary, NSDictionary())
            return FixtureResponse(status: 202, json: [
                "object": "padnote.video.operation", "protocol_version": 1,
                "operation_id": UUID().uuidString.lowercased(), "task_id": "remote-1", "client_operation_id": key,
                "action": "initialize", "status": "queued", "created_at": 1, "updated_at": 1,
                "result": NSNull(), "error": NSNull()
            ])
        }
        let result = try await AgentVideoOperationClient(session: session).submit(
            task: task, remoteTaskID: "remote-1", token: "device-token", action: .initialize,
            clientOperationID: key)
        XCTAssertEqual(result.taskID, "remote-1")
        XCTAssertEqual(result.status, .queued)
    }

    func testVideoReconcileUsesExplicitEmptyPostAndBoundOperationRoute() async throws {
        let profile = AgentConnectionProfile(name: "Home", kind: .builtinVideo, endpoint: "https://fixture.test/base",
            transport: .bridge, bridgeID: "bridge-1", instanceID: "instance-1",
            certSHA256: String(repeating: "a", count: 64))
        let bundle = try VideoTaskBundleIO.make(noteId: "note-1", noteRevision: 3, title: "Lesson",
            markdown: "# Lesson", audience: "Students", learningGoal: "Understand", durationSeconds: 90)
        let payload = try AgentTaskPayload(title: "Lesson", input: "Explain",
            source: AgentTaskSource(noteID: "note-1", noteRevision: 3), bundle: bundle)
        var task = AgentTaskRecord(profile: profile, payload: payload)
        task.remoteTaskID = "remote-1"
        let operationID = "11111111-1111-4111-8111-111111111111"
        let key = "22222222-2222-4222-8222-222222222222"
        let session = fixtureSession { request in
            XCTAssertEqual(request.httpMethod, "POST")
            XCTAssertEqual(request.url?.path,
                "/base/padnote/v1/agents/instance-1/runs/remote-1/video/operations/\(operationID)/reconcile")
            XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer device-token")
            XCTAssertEqual(try? JSONSerialization.jsonObject(with: request.httpBody ?? Data()) as? NSDictionary,
                           NSDictionary(), "reconcile sends exactly an empty JSON object")
            return FixtureResponse(status: 200, json: [
                "object": "padnote.video.operation", "protocol_version": 1,
                "operation_id": operationID, "task_id": "remote-1", "client_operation_id": key,
                "action": "initialize", "status": "unknown", "created_at": 1, "updated_at": 2,
                "result": NSNull(), "error": "worker_unknown"
            ])
        }
        let response = try await AgentVideoOperationClient(session: session).reconcile(
            task: task, remoteTaskID: "remote-1", token: "device-token",
            operationID: operationID, action: .initialize, key: key)
        XCTAssertEqual(response.status, .unknown)
        XCTAssertEqual(response.operationID, operationID)
    }

    func testVideoServiceReconcileCasChangesOnlyOriginalUnknownOperationToSucceeded() async throws {
        let (defaults, suite) = makeDefaults()
        defer { defaults.removePersistentDomain(forName: suite) }
        let tokens = MemoryTokenStore()
        let connections = AgentConnectionStore(defaults: defaults, keychain: tokens)
        let created = try connections.create(name: "Home", kind: .builtinVideo, endpoint: "https://fixture.test",
            token: "device-token", transport: .bridge, bridgeID: "bridge-1", instanceID: "instance-1",
            certSHA256: String(repeating: "a", count: 64))
        XCTAssertTrue(connections.applyProbeSuccess(id: created.id, revision: created.revision,
            capabilities: ["task_bundle": true, "video_task_submission": true, "video_operations": true, "video_production": true, "artifacts": true, "run_status": true, "run_submission": false]))
        let profile = try XCTUnwrap(connections.profile(id: created.id))
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("video-reconcile-service-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let tasks = AgentTaskStore(fileURL: root.appendingPathComponent("tasks", isDirectory: true))
        let bundle = try VideoTaskBundleIO.make(noteId: "note", noteRevision: 1, title: "Lesson",
            markdown: "# Lesson", audience: "Students", learningGoal: "Understand", durationSeconds: 60)
        let payload = try AgentTaskPayload(title: "Lesson", input: "Explain",
            source: AgentTaskSource(noteID: "note", noteRevision: 1), bundle: bundle)
        let task = try tasks.create(profile: profile, payload: payload)
        _ = try tasks.mutate(id: task.id, expectedRevision: task.recordRevision) {
            $0.remoteTaskID = "remote-1"; $0.status = .completed
        }
        let videoStore = AgentVideoOperationStore(directory: root.appendingPathComponent("video", isDirectory: true))
        let operation = AgentVideoOperationRecord(parentTaskLocalID: task.id,
            connection: AgentTaskConnectionIdentity(profile: profile), remoteTaskID: "remote-1", action: .initialize)
        _ = try videoStore.prepare(operation)
        try videoStore.mutate(taskID: task.id, operationID: operation.id) {
            $0.submissionState = .accepted
            $0.operationID = "11111111-1111-4111-8111-111111111111"
            $0.remoteStatus = .unknown
            $0.errorCode = "worker_unknown"
        }
        let requestLock = NSLock()
        var reconcileRequests = 0
        let session = fixtureSession { request in
            requestLock.lock(); defer { requestLock.unlock() }
            reconcileRequests += 1
            XCTAssertEqual(request.httpMethod, "POST")
            XCTAssertTrue(request.url?.path.hasSuffix("/video/operations/11111111-1111-4111-8111-111111111111/reconcile") == true)
            XCTAssertEqual(try? JSONSerialization.jsonObject(with: request.httpBody ?? Data()) as? NSDictionary, NSDictionary())
            return FixtureResponse(status: 200, json: [
                "object": "padnote.video.operation", "protocol_version": 1,
                "operation_id": "11111111-1111-4111-8111-111111111111", "task_id": "remote-1",
                "client_operation_id": operation.clientOperationID, "action": "initialize",
                "status": "succeeded", "created_at": 1, "updated_at": 2,
                "result": ["task_id": "remote-1", "status": "initialized", "phase": "idle", "event_cursor": 1],
                "error": NSNull()
            ])
        }
        let service = AgentVideoOperationService(connections: connections, tasks: tasks, store: videoStore,
            client: AgentVideoOperationClient(session: session))
        let completed = try await service.reconcile(taskID: task.id, operationID: operation.id)
        XCTAssertEqual(completed.remoteStatus, .succeeded)
        XCTAssertEqual(completed.result?.eventCursor, 1)
        XCTAssertNil(completed.errorCode)
        XCTAssertEqual(reconcileRequests, 1)
        XCTAssertEqual(try videoStore.snapshot(for: task.id).operations.first?.clientOperationID,
                       operation.clientOperationID, "reconciliation preserves the original idempotency key")
    }

    func testInFlightReconcileCannotOverwriteNewerSucceededRecord() async throws {
        let entered = DispatchSemaphore(value: 0)
        let release = DispatchSemaphore(value: 0)
        defer { release.signal() }
        var operationKey = ""
        let session = fixtureSession { _ in
            entered.signal()
            _ = release.wait(timeout: .now() + 8)
            return Self.videoOperationResponse(status: "unknown", key: operationKey, error: "worker_unknown")
        }
        let setup = try makeUnknownVideoReconcileSetup(session: session)
        operationKey = try XCTUnwrap(setup.videoStore.snapshot(for: setup.taskID).operations.first?.clientOperationID)
        defer {
            try? FileManager.default.removeItem(at: setup.root)
            UserDefaults(suiteName: setup.suite)?.removePersistentDomain(forName: setup.suite)
        }
        let request = Task {
            try await setup.service.reconcile(taskID: setup.taskID, operationID: setup.operationID)
        }
        XCTAssertEqual(entered.wait(timeout: .now() + 5), .success, "the real Service request must be in flight")
        let newerResult = AgentVideoInspection(taskID: "remote-1", status: "initialized", phase: "idle",
            eventCursor: 1, revision: nil, reviewSHA256: nil, lessonIRSHA256: nil)
        _ = try setup.videoStore.mutate(taskID: setup.taskID, operationID: setup.operationID) {
            $0.remoteStatus = .succeeded
            $0.result = newerResult
            $0.errorCode = nil
        }
        release.signal()
        do {
            _ = try await request.value
            XCTFail("the older in-flight response must fail its full-record CAS")
        } catch let error as AgentTaskError {
            XCTAssertEqual(error, .staleTask)
        }
        let reopened = try AgentVideoOperationStore(directory: setup.root.appendingPathComponent("video", isDirectory: true))
            .snapshot(for: setup.taskID)
        XCTAssertEqual(reopened.operations.first?.remoteStatus, .succeeded)
        XCTAssertEqual(reopened.operations.first?.result, newerResult,
            "reopening must preserve the newer terminal result rather than the stale remote reply")
    }

    func testInFlightReconcileRejectsSuccessAfterConnectionRevocation() async throws {
        let entered = DispatchSemaphore(value: 0)
        let release = DispatchSemaphore(value: 0)
        defer { release.signal() }
        var operationKey = ""
        let session = fixtureSession { _ in
            entered.signal()
            _ = release.wait(timeout: .now() + 8)
            return Self.videoOperationResponse(status: "succeeded", key: operationKey, result: [
                "task_id": "remote-1", "status": "initialized", "phase": "idle", "event_cursor": 1
            ])
        }
        let setup = try makeUnknownVideoReconcileSetup(session: session)
        operationKey = try XCTUnwrap(setup.videoStore.snapshot(for: setup.taskID).operations.first?.clientOperationID)
        defer {
            try? FileManager.default.removeItem(at: setup.root)
            UserDefaults(suiteName: setup.suite)?.removePersistentDomain(forName: setup.suite)
        }
        let request = Task {
            try await setup.service.reconcile(taskID: setup.taskID, operationID: setup.operationID)
        }
        XCTAssertEqual(entered.wait(timeout: .now() + 5), .success, "the authorized reconcile must reach the endpoint")
        setup.connections.delete(id: setup.connectionID)
        release.signal()
        do {
            _ = try await request.value
            XCTFail("a success response arriving after revocation must not be applied")
        } catch let error as AgentVideoError {
            XCTAssertEqual(error, .connectionChanged)
        }
        let reopened = try AgentVideoOperationStore(directory: setup.root.appendingPathComponent("video", isDirectory: true))
            .snapshot(for: setup.taskID)
        XCTAssertEqual(reopened.operations.first?.remoteStatus, .unknown)
        XCTAssertNil(reopened.operations.first?.result,
            "connection revocation must preserve the unknown local record")
    }

    func testRunningCancelRetryRequiresFreshRunningGETAndReusesSameIntent() async throws {
        var key = ""
        let setup = try makeRunningVideoCancelSetup(session: fixtureSession { request in
            XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer device-token")
            if request.httpMethod == "GET" {
                XCTAssertTrue(request.url?.path.hasSuffix("/video/operations/22222222-2222-4222-8222-222222222222") == true)
                return FixtureResponse(status: 200, json: [
                    "object": "padnote.video.operation", "protocol_version": 1,
                    "operation_id": "22222222-2222-4222-8222-222222222222", "task_id": "remote-1",
                    "client_operation_id": key, "action": "storyboard",
                    "status": "running", "created_at": 1, "updated_at": 2,
                    "result": NSNull(), "error": NSNull()
                ])
            }
            XCTAssertEqual(request.httpMethod, "POST")
            XCTAssertTrue(request.url?.path.hasSuffix("/video/operations/22222222-2222-4222-8222-222222222222/cancel-running") == true)
            XCTAssertEqual(try? JSONSerialization.jsonObject(with: request.httpBody ?? Data()) as? NSDictionary, NSDictionary())
            return FixtureResponse(status: 200, json: [
                "object": "padnote.video.cancel_request", "protocol_version": 1,
                "operation_id": "22222222-2222-4222-8222-222222222222", "task_id": "remote-1",
                "status": "verified_cancelled", "requested_at": 10, "updated_at": 11
            ])
        })
        defer {
            try? FileManager.default.removeItem(at: setup.root)
            UserDefaults(suiteName: setup.suite)?.removePersistentDomain(forName: setup.suite)
        }
        let before = try XCTUnwrap(setup.videoStore.snapshot(for: setup.taskID).operations.first)
        key = before.clientOperationID
        XCTAssertEqual(before.runningCancelIntent?.delivery, .noRequest)
        let result = try await setup.service.retryRunningStoryboardCancel(taskID: setup.taskID, operationID: setup.operationID)
        XCTAssertEqual(result.remoteStatus, .cancelled)
        XCTAssertEqual(result.runningCancelIntent?.operationID, before.runningCancelIntent?.operationID)
        XCTAssertEqual(result.clientOperationID, before.clientOperationID)
        XCTAssertEqual(result.runningCancelIntent?.status, .verifiedCancelled)
        let reopened = try AgentVideoOperationStore(directory: setup.root.appendingPathComponent("video", isDirectory: true))
            .snapshot(for: setup.taskID).operations.first
        XCTAssertEqual(reopened?.remoteStatus, .cancelled)
        XCTAssertEqual(reopened?.runningCancelIntent?.delivery, .accepted)
    }

    func testQueuedOperationCancellationStillAppliesWithoutRunningIntent() async throws {
        var key = ""
        let session = fixtureSession { request in
            XCTAssertEqual(request.httpMethod, "POST")
            XCTAssertTrue(request.url?.path.hasSuffix("/video/operations/11111111-1111-4111-8111-111111111111/cancel") == true)
            XCTAssertEqual(try? JSONSerialization.jsonObject(with: request.httpBody ?? Data()) as? NSDictionary, NSDictionary())
            return FixtureResponse(status: 200, json: [
                "object": "padnote.video.operation", "protocol_version": 1,
                "operation_id": "11111111-1111-4111-8111-111111111111", "task_id": "remote-1",
                "client_operation_id": key, "action": "initialize", "status": "cancelled",
                "created_at": 1, "updated_at": 2, "result": NSNull(), "error": "cancelled"
            ])
        }
        let setup = try makeUnknownVideoReconcileSetup(session: session, initialStatus: .queued)
        defer {
            try? FileManager.default.removeItem(at: setup.root)
            UserDefaults(suiteName: setup.suite)?.removePersistentDomain(forName: setup.suite)
        }
        let prior = try XCTUnwrap(setup.videoStore.snapshot(for: setup.taskID).operations.first)
        key = prior.clientOperationID
        let cancelled = try await setup.service.cancel(taskID: setup.taskID, operationID: setup.operationID)
        XCTAssertEqual(cancelled.remoteStatus, .cancelled)
        XCTAssertNil(cancelled.runningCancelIntent)
        XCTAssertEqual(cancelled.errorCode, "cancelled")
    }

    func testUnknownWithSavedRunningCancelIntentCanReconcileToCancelled() async throws {
        var key = ""
        let setup = try makeRunningVideoCancelSetup(session: fixtureSession { request in
            if request.url?.path.hasSuffix("/reconcile") == true {
                XCTAssertEqual(request.httpMethod, "POST")
                XCTAssertTrue(request.url?.path.hasSuffix("/video/operations/22222222-2222-4222-8222-222222222222/reconcile") == true)
                XCTAssertEqual(try? JSONSerialization.jsonObject(with: request.httpBody ?? Data()) as? NSDictionary, NSDictionary())
                return FixtureResponse(status: 200, json: [
                    "object": "padnote.video.operation", "protocol_version": 1,
                    "operation_id": "22222222-2222-4222-8222-222222222222", "task_id": "remote-1",
                    "client_operation_id": key, "action": "storyboard", "status": "cancelled",
                    "created_at": 1, "updated_at": 2, "result": NSNull(), "error": "cancelled"
                ])
            }
            XCTAssertEqual(request.httpMethod, "GET")
            XCTAssertTrue(request.url?.path.hasSuffix("/video/operations/22222222-2222-4222-8222-222222222222/cancel-running") == true)
            return FixtureResponse(status: 200, json: [
                "object": "padnote.video.cancel_request", "protocol_version": 1,
                "operation_id": "22222222-2222-4222-8222-222222222222", "task_id": "remote-1",
                "status": "verified_cancelled", "requested_at": 10, "updated_at": 11
            ])
        })
        defer {
            try? FileManager.default.removeItem(at: setup.root)
            UserDefaults(suiteName: setup.suite)?.removePersistentDomain(forName: setup.suite)
        }
        let prior = try XCTUnwrap(setup.videoStore.snapshot(for: setup.taskID).operations.first)
        key = prior.clientOperationID
        _ = try setup.videoStore.mutate(taskID: setup.taskID, operationID: setup.operationID) {
            $0.remoteStatus = .unknown; $0.errorCode = "worker_unknown"
        }
        let cancelled = try await setup.service.reconcile(taskID: setup.taskID, operationID: setup.operationID)
        XCTAssertEqual(cancelled.remoteStatus, .cancelled)
        XCTAssertEqual(cancelled.runningCancelIntent?.status, .verifiedCancelled)
        XCTAssertNotNil(cancelled.runningCancelIntent?.operationStatusVerifiedAt)
        XCTAssertEqual(cancelled.errorCode, "cancelled")
        _ = try await setup.service.refreshRunningCancelStatus(taskID: setup.taskID, operationID: setup.operationID)
        let reopened = try AgentVideoOperationStore(directory: setup.root.appendingPathComponent("video", isDirectory: true))
            .snapshot(for: setup.taskID).operations.first
        XCTAssertEqual(reopened?.remoteStatus, .cancelled)
        XCTAssertEqual(reopened?.runningCancelIntent?.operationID, "22222222-2222-4222-8222-222222222222")
    }

    func testConfirmedRunningCancelColdReopenAndRepeatedStatusGetAreIdempotent() async throws {
        let counters = NSLock()
        var postCount = 0
        var getCount = 0
        let setup = try makeRunningVideoCancelSetup(session: fixtureSession { request in
            counters.lock(); defer { counters.unlock() }
            if request.httpMethod == "POST" {
                postCount += 1
                return FixtureResponse(status: 200, json: [
                    "object": "padnote.video.cancel_request", "protocol_version": 1,
                    "operation_id": "22222222-2222-4222-8222-222222222222", "task_id": "remote-1",
                    "status": "verified_cancelled", "requested_at": 10, "updated_at": 11
                ])
            }
            getCount += 1
            return FixtureResponse(status: 200, json: [
                "object": "padnote.video.cancel_request", "protocol_version": 1,
                "operation_id": "22222222-2222-4222-8222-222222222222", "task_id": "remote-1",
                "status": "verified_cancelled", "requested_at": 10, "updated_at": 11
            ])
        }, initialIntentDelivery: nil)
        defer {
            try? FileManager.default.removeItem(at: setup.root)
            UserDefaults(suiteName: setup.suite)?.removePersistentDomain(forName: setup.suite)
        }
        _ = try await setup.service.requestRunningStoryboardCancel(taskID: setup.taskID, operationID: setup.operationID)
        let coldStore = try AgentVideoOperationStore(directory: setup.root.appendingPathComponent("video", isDirectory: true))
        let confirmed = try XCTUnwrap(coldStore.snapshot(for: setup.taskID).operations.first)
        XCTAssertEqual(confirmed.remoteStatus, .cancelled)
        _ = try await setup.service.refreshRunningCancelStatus(taskID: setup.taskID, operationID: setup.operationID)
        counters.lock(); let posts = postCount, gets = getCount; counters.unlock()
        XCTAssertEqual(posts, 1, "status checks after reopen must never repeat the POST")
        XCTAssertEqual(gets, 1)
        let reopened = try AgentVideoOperationStore(directory: setup.root.appendingPathComponent("video", isDirectory: true))
            .snapshot(for: setup.taskID).operations.first
        XCTAssertEqual(reopened?.remoteStatus, .cancelled)
        XCTAssertEqual(reopened?.runningCancelIntent?.status, .verifiedCancelled)
    }

    func testPreparedCancelIntentSurvivesColdOperationRefresh() async throws {
        var key = ""
        let setup = try makeRunningVideoCancelSetup(session: fixtureSession { request in
            XCTAssertEqual(request.httpMethod, "GET")
            XCTAssertTrue(request.url?.path.hasSuffix("/video/operations/22222222-2222-4222-8222-222222222222") == true)
            return FixtureResponse(status: 200, json: [
                "object": "padnote.video.operation", "protocol_version": 1,
                "operation_id": "22222222-2222-4222-8222-222222222222", "task_id": "remote-1",
                "client_operation_id": key, "action": "storyboard",
                "status": "running", "created_at": 1, "updated_at": 2,
                "result": NSNull(), "error": NSNull()
            ])
        }, initialIntentDelivery: .prepared)
        defer {
            try? FileManager.default.removeItem(at: setup.root)
            UserDefaults(suiteName: setup.suite)?.removePersistentDomain(forName: setup.suite)
        }
        let prior = try XCTUnwrap(setup.videoStore.snapshot(for: setup.taskID).operations.first)
        key = prior.clientOperationID
        _ = try await setup.service.refresh(taskID: setup.taskID, operationID: setup.operationID)
        let reopened = try AgentVideoOperationStore(directory: setup.root.appendingPathComponent("video", isDirectory: true))
            .snapshot(for: setup.taskID).operations.first
        XCTAssertEqual(reopened?.remoteStatus, .running)
        XCTAssertEqual(reopened?.runningCancelIntent?.delivery, .prepared,
            "an operation GET must preserve a locally prepared intent across cold reopen")
        XCTAssertEqual(reopened?.runningCancelIntent?.operationID, prior.runningCancelIntent?.operationID)
    }

    func testVerifiedStoppedStoryboardCannotBeResubmittedUnderNewKey() async throws {
        let counterLock = NSLock()
        var requestCount = 0
        let setup = try makeRunningVideoCancelSetup(session: fixtureSession { request in
            counterLock.lock(); requestCount += 1; counterLock.unlock()
            XCTAssertEqual(request.httpMethod, "POST")
            XCTAssertTrue(request.url?.path.hasSuffix("/video/operations/22222222-2222-4222-8222-222222222222/cancel-running") == true)
            return FixtureResponse(status: 200, json: [
                "object": "padnote.video.cancel_request", "protocol_version": 1,
                "operation_id": "22222222-2222-4222-8222-222222222222", "task_id": "remote-1",
                "status": "verified_cancelled", "requested_at": 10, "updated_at": 11
            ])
        }, initialIntentDelivery: nil)
        defer {
            try? FileManager.default.removeItem(at: setup.root)
            UserDefaults(suiteName: setup.suite)?.removePersistentDomain(forName: setup.suite)
        }
        _ = try await setup.service.requestRunningStoryboardCancel(taskID: setup.taskID, operationID: setup.operationID)
        do {
            _ = try await setup.service.submit(taskID: setup.taskID, action: .storyboard)
            XCTFail("a verified stopped storyboard cannot be retried as a new operation")
        } catch let error as AgentVideoError {
            XCTAssertEqual(error, .cancelledStoryboardCannotResume)
        }
        counterLock.lock(); let observedCount = requestCount; counterLock.unlock()
        XCTAssertEqual(observedCount, 1, "the refused second action must not send another POST")
        XCTAssertEqual(try setup.videoStore.snapshot(for: setup.taskID).operations.count, 1)
    }

    func testUnknownRunningCancelWithoutExistingIntentCannotCreateOne() async throws {
        let setup = try makeUnknownVideoReconcileSetup(session: fixtureSession { _ in
            XCTFail("unknown operation must not POST a stop request")
            return FixtureResponse(status: 500, json: [:])
        })
        defer {
            try? FileManager.default.removeItem(at: setup.root)
            UserDefaults(suiteName: setup.suite)?.removePersistentDomain(forName: setup.suite)
        }
        do {
            _ = try await setup.service.requestRunningStoryboardCancel(taskID: setup.taskID, operationID: setup.operationID)
            XCTFail("unknown must not be treated as running")
        } catch let error as AgentVideoError {
            XCTAssertEqual(error, .unknownRemote)
        }
        XCTAssertNil(try setup.videoStore.snapshot(for: setup.taskID).operations.first?.runningCancelIntent)
    }

    func testCancellingExplicitRetryWhileOperationGetIsHeldNeverSendsStopPost() async throws {
        let entered = DispatchSemaphore(value: 0)
        let release = DispatchSemaphore(value: 0)
        let countLock = NSLock()
        var stopPosts = 0
        var key = ""
        let setup = try makeRunningVideoCancelSetup(session: fixtureSession { request in
            if request.httpMethod == "GET" {
                entered.signal()
                _ = release.wait(timeout: .now() + 8)
                return FixtureResponse(status: 200, json: [
                    "object": "padnote.video.operation", "protocol_version": 1,
                    "operation_id": "22222222-2222-4222-8222-222222222222", "task_id": "remote-1",
                    "client_operation_id": key, "action": "storyboard", "status": "running",
                    "created_at": 1, "updated_at": 2, "result": NSNull(), "error": NSNull()
                ])
            }
            countLock.lock(); stopPosts += 1; countLock.unlock()
            return FixtureResponse(status: 200, json: [
                "object": "padnote.video.cancel_request", "protocol_version": 1,
                "operation_id": "22222222-2222-4222-8222-222222222222", "task_id": "remote-1",
                "status": "verified_cancelled", "requested_at": 10, "updated_at": 11
            ])
        })
        defer {
            release.signal()
            try? FileManager.default.removeItem(at: setup.root)
            UserDefaults(suiteName: setup.suite)?.removePersistentDomain(forName: setup.suite)
        }
        key = try XCTUnwrap(setup.videoStore.snapshot(for: setup.taskID).operations.first?.clientOperationID)
        let request = Task {
            try await setup.service.retryRunningStoryboardCancel(taskID: setup.taskID, operationID: setup.operationID)
        }
        XCTAssertEqual(entered.wait(timeout: .now() + 5), .success, "the original-operation status GET is in flight")
        request.cancel()
        release.signal()
        do {
            _ = try await request.value
            XCTFail("a cancelled view task must not proceed to the stop POST")
        } catch { /* Cancellation or URLSession cancellation is expected. */ }
        countLock.lock(); let observedPosts = stopPosts; countLock.unlock()
        XCTAssertEqual(observedPosts, 0, "cancellation during the GET must block the subsequent POST")
        let stored = try XCTUnwrap(setup.videoStore.snapshot(for: setup.taskID).operations.first)
        XCTAssertEqual(stored.remoteStatus, .running)
        XCTAssertEqual(stored.runningCancelIntent?.delivery, .noRequest)
        XCTAssertEqual(stored.runningCancelIntent?.operationID, "22222222-2222-4222-8222-222222222222")
    }

    func testVideoOperationStoreBindsApprovalToDisplayedReviewAndPersistsSeparateState() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("video-store-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let profile = AgentConnectionProfile(name: "Home", endpoint: "https://fixture.test", transport: .bridge,
            bridgeID: "bridge-1", instanceID: "instance-1")
        let taskID = UUID()
        let connection = AgentTaskConnectionIdentity(profile: profile)
        let store = AgentVideoOperationStore(directory: root)
        let initialize = AgentVideoOperationRecord(parentTaskLocalID: taskID, connection: connection,
            remoteTaskID: "remote-1", action: .initialize)
        _ = try store.prepare(initialize)
        try store.mutate(taskID: taskID, operationID: initialize.id) {
            $0.submissionState = .accepted; $0.operationID = UUID().uuidString.lowercased(); $0.remoteStatus = .succeeded
            $0.result = AgentVideoInspection(taskID: "remote-1", status: "initialized", phase: "idle",
                eventCursor: 1, revision: nil, reviewSHA256: nil, lessonIRSHA256: nil)
        }
        let review = makeVideoReview(taskID: "remote-1", cursor: 3, revision: 1)
        let storyboard = AgentVideoOperationRecord(parentTaskLocalID: taskID, connection: connection,
            remoteTaskID: "remote-1", action: .storyboard, revision: 1, eventCursor: 1)
        _ = try store.prepare(storyboard)
        try store.mutate(taskID: taskID, operationID: storyboard.id) {
            $0.submissionState = .accepted; $0.operationID = UUID().uuidString.lowercased(); $0.remoteStatus = .succeeded
            $0.result = AgentVideoInspection(taskID: "remote-1", status: "awaiting_storyboard_review",
                phase: "awaiting_approval", eventCursor: 3, revision: 1,
                reviewSHA256: review.reviewSHA256, lessonIRSHA256: review.lessonIRSHA256)
        }
        try store.save(review: review, for: taskID)
        let approve = AgentVideoOperationRecord(parentTaskLocalID: taskID, connection: connection,
            remoteTaskID: "remote-1", action: .approve, revision: 1, eventCursor: 3,
            reviewSHA256: review.reviewSHA256, lessonIRSHA256: review.lessonIRSHA256)
        XCTAssertThrowsError(try store.prepare(approve, expectedReview: review, verifiedPreviewIDs: [])) {
            XCTAssertEqual($0 as? AgentVideoError, .staleReview)
        }
        _ = try store.prepare(approve, expectedReview: review, verifiedPreviewIDs: Set(review.scenes.map(\.id)))
        try store.mutate(taskID: taskID, operationID: approve.id, markReviewApproved: true) {
            $0.submissionState = .accepted; $0.operationID = UUID().uuidString.lowercased(); $0.remoteStatus = .succeeded
            $0.result = AgentVideoInspection(taskID: "remote-1", status: "approved", phase: "approval_pending",
                eventCursor: 4, revision: 1, reviewSHA256: review.reviewSHA256, lessonIRSHA256: review.lessonIRSHA256)
        }
        let reloaded = try AgentVideoOperationStore(directory: root).snapshot(for: taskID)
        XCTAssertEqual(reloaded.operations.map(\.action), [.initialize, .storyboard, .approve])
        XCTAssertEqual(reloaded.operations[1].result?.eventCursor, 3)
        XCTAssertEqual(reloaded.review?.status, "approved")
        XCTAssertEqual(reloaded.review?.eventCursor, 4, "approved review advances to the server approval cursor")
        let next = AgentVideoOperationRecord(parentTaskLocalID: taskID, connection: connection,
            remoteTaskID: "remote-1", action: .storyboard, revision: 2, eventCursor: 4)
        XCTAssertThrowsError(try store.prepare(next, expectedReview: reloaded.review,
            verifiedPreviewIDs: Set(review.scenes.map(\.id)))) {
            XCTAssertEqual($0 as? AgentVideoError, .staleReview)
        }
        let duplicateApproval = AgentVideoOperationRecord(parentTaskLocalID: taskID, connection: connection,
            remoteTaskID: "remote-1", action: .approve, revision: 1, eventCursor: 4,
            reviewSHA256: review.reviewSHA256, lessonIRSHA256: review.lessonIRSHA256)
        XCTAssertThrowsError(try store.prepare(duplicateApproval, expectedReview: reloaded.review,
            verifiedPreviewIDs: Set(review.scenes.map(\.id)))) {
            XCTAssertEqual($0 as? AgentVideoError, .staleReview)
        }
        try store.save(review: try XCTUnwrap(reloaded.review), for: taskID)
        XCTAssertEqual(try store.snapshot(for: taskID).review?.eventCursor, 4,
            "an approved review returned by the server can be reloaded and persisted")
    }

    func testVideoOperationStoreCannotUnlockOrRewriteUnknownTerminalResult() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("video-unknown-store-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let profile = AgentConnectionProfile(name: "Home", endpoint: "https://fixture.test",
            transport: .bridge, bridgeID: "bridge-1", instanceID: "instance-1")
        let taskID = UUID()
        let store = AgentVideoOperationStore(directory: root)
        let operation = AgentVideoOperationRecord(parentTaskLocalID: taskID,
            connection: AgentTaskConnectionIdentity(profile: profile), remoteTaskID: "remote-1", action: .initialize)
        _ = try store.prepare(operation)
        try store.mutate(taskID: taskID, operationID: operation.id) {
            $0.submissionState = .accepted
            $0.operationID = "11111111-1111-4111-8111-111111111111"
            $0.remoteStatus = .unknown
            $0.errorCode = "worker_unknown"
        }
        XCTAssertThrowsError(try store.mutate(taskID: taskID, operationID: operation.id) {
            $0.remoteStatus = .queued
        }) { XCTAssertEqual($0 as? AgentTaskError, .staleTask) }
        XCTAssertThrowsError(try store.mutate(taskID: taskID, operationID: operation.id) {
            $0.remoteStatus = .failed
        }) { XCTAssertEqual($0 as? AgentTaskError, .staleTask) }
        XCTAssertThrowsError(try store.mutate(taskID: taskID, operationID: operation.id) {
            $0.errorCode = "worker_failed"
        }) { XCTAssertEqual($0 as? AgentTaskError, .staleTask) }
        let preserved = try XCTUnwrap(store.snapshot(for: taskID).operations.first)
        XCTAssertEqual(preserved.remoteStatus, .unknown)
        XCTAssertEqual(preserved.errorCode, "worker_unknown")
        XCTAssertNil(preserved.result)
        let completed = try store.mutate(taskID: taskID, operationID: operation.id) {
            $0.remoteStatus = .succeeded
            $0.errorCode = nil
            $0.result = AgentVideoInspection(taskID: "remote-1", status: "initialized", phase: "idle",
                eventCursor: 1, revision: nil, reviewSHA256: nil, lessonIRSHA256: nil)
        }
        XCTAssertEqual(completed.remoteStatus, .succeeded)
        XCTAssertEqual(completed.result?.eventCursor, 1)
        XCTAssertThrowsError(try store.mutate(taskID: taskID, operationID: operation.id) {
            $0.errorCode = "worker_failed"
        }) { XCTAssertEqual($0 as? AgentTaskError, .staleTask) }
        XCTAssertThrowsError(try store.mutate(taskID: taskID, operationID: operation.id) {
            $0.result = AgentVideoInspection(taskID: "remote-1", status: "initialized", phase: "idle",
                eventCursor: 2, revision: nil, reviewSHA256: nil, lessonIRSHA256: nil)
        }) { XCTAssertEqual($0 as? AgentTaskError, .staleTask) }
    }

    func testVideoOperationStoreReadsLegacySnapshotWithoutRunningCancelIntent() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("video-legacy-cancel-json-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let profile = AgentConnectionProfile(name: "Home", endpoint: "https://fixture.test",
            transport: .bridge, bridgeID: "bridge-1", instanceID: "instance-1")
        let taskID = UUID()
        let store = AgentVideoOperationStore(directory: root)
        let operation = AgentVideoOperationRecord(parentTaskLocalID: taskID,
            connection: AgentTaskConnectionIdentity(profile: profile), remoteTaskID: "remote-1", action: .initialize)
        _ = try store.prepare(operation)
        let file = root.appendingPathComponent(taskID.uuidString.lowercased() + ".json")
        let bytes = try Data(contentsOf: file)
        var snapshot = try XCTUnwrap(JSONSerialization.jsonObject(with: bytes) as? [String: Any])
        var operations = try XCTUnwrap(snapshot["operations"] as? [[String: Any]])
        operations[0].removeValue(forKey: "runningCancelIntent")
        snapshot["operations"] = operations
        try JSONSerialization.data(withJSONObject: snapshot, options: [.sortedKeys]).write(to: file, options: .atomic)
        let reopened = try AgentVideoOperationStore(directory: root).snapshot(for: taskID)
        XCTAssertEqual(reopened.operations.first?.id, operation.id)
        XCTAssertNil(reopened.operations.first?.runningCancelIntent)
    }

    private func makeVideoReview(taskID: String, cursor: Int, revision: Int) -> AgentVideoReview {
        let preview = AgentVideoPreview(id: String(repeating: "a", count: 64), mediaType: "image/png",
            sizeBytes: 100, sha256: String(repeating: "b", count: 64), width: 640, height: 480)
        let scene = AgentVideoScene(id: "scene-1", learningObjective: "Objective", narration: "Narration",
            screenText: ["Text"], visualKind: "title", preview: preview)
        return AgentVideoReview(object: "padnote.video.review", protocolVersion: 1,
            status: "awaiting_storyboard_review", taskID: taskID, workerTaskID: "worker-1",
            eventCursor: cursor, revision: revision, reviewSHA256: String(repeating: "c", count: 64),
            lessonIRSHA256: String(repeating: "d", count: 64),
            episode: AgentVideoEpisode(title: "Title", audience: "Students", learningGoal: "Learn", language: "zh-CN"),
            scenes: [scene])
    }

    private func makeVideoRequestTask(kind: AgentKind, pin: String?) throws -> AgentTaskRecord {
        let profile = AgentConnectionProfile(name: "Fixture", kind: kind, endpoint: "https://fixture.test/base",
            transport: .bridge, bridgeID: "bridge-1", instanceID: "instance-1", certSHA256: pin)
        let bundle = try VideoTaskBundleIO.make(noteId: "note-1", noteRevision: 1, title: "Lesson",
            markdown: "# Lesson", audience: "Students", learningGoal: "Understand", durationSeconds: 60)
        let payload = try AgentTaskPayload(title: "Lesson", input: "Explain",
            source: AgentTaskSource(noteID: "note-1", noteRevision: 1), bundle: bundle)
        var task = AgentTaskRecord(profile: profile, payload: payload)
        task.remoteTaskID = "remote-1"
        return task
    }

    private func makeVideoCapabilityService(capabilities: [String: Bool], session: URLSession) throws ->
        (root: URL, suite: String, taskID: UUID, service: AgentVideoOperationService) {
        let (defaults, suite) = makeDefaults()
        let connections = AgentConnectionStore(defaults: defaults, keychain: MemoryTokenStore())
        let created = try connections.create(name: "Fixture", kind: .hermes, endpoint: "https://fixture.test/base",
            token: "device-token", transport: .bridge, bridgeID: "bridge-1", instanceID: "instance-1")
        XCTAssertTrue(connections.applyProbeSuccess(id: created.id, revision: created.revision, capabilities: capabilities))
        let profile = try XCTUnwrap(connections.profile(id: created.id))
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("video-capability-\(UUID().uuidString)", isDirectory: true)
        let tasks = AgentTaskStore(fileURL: root.appendingPathComponent("tasks", isDirectory: true))
        let bundle = try VideoTaskBundleIO.make(noteId: "note-1", noteRevision: 1, title: "Lesson",
            markdown: "# Lesson", audience: "Students", learningGoal: "Understand", durationSeconds: 60)
        let payload = try AgentTaskPayload(title: "Lesson", input: "Explain",
            source: AgentTaskSource(noteID: "note-1", noteRevision: 1), bundle: bundle)
        let local = try tasks.create(profile: profile, payload: payload)
        let task = try tasks.mutate(id: local.id, expectedRevision: local.recordRevision) {
            $0.remoteTaskID = "remote-1"; $0.status = .completed
        }
        let store = AgentVideoOperationStore(directory: root.appendingPathComponent("video", isDirectory: true))
        let service = AgentVideoOperationService(connections: connections, tasks: tasks, store: store,
            client: AgentVideoOperationClient(session: session))
        return (root, suite, task.id, service)
    }

    private func makeDefaults() -> (UserDefaults, String) {
        let suite = "agent-test-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defaults.removePersistentDomain(forName: suite)
        return (defaults, suite)
    }

    private func makeUnknownVideoReconcileSetup(session: URLSession,
                                                initialStatus: AgentVideoRemoteStatus = .unknown) throws ->
        (root: URL, suite: String, connections: AgentConnectionStore, connectionID: UUID,
         taskID: UUID, operationID: UUID, videoStore: AgentVideoOperationStore,
         service: AgentVideoOperationService) {
        let (defaults, suite) = makeDefaults()
        let connections = AgentConnectionStore(defaults: defaults, keychain: MemoryTokenStore())
        let created = try connections.create(name: "Home", kind: .builtinVideo, endpoint: "https://fixture.test",
            token: "device-token", transport: .bridge, bridgeID: "bridge-1", instanceID: "instance-1",
            certSHA256: String(repeating: "a", count: 64))
        XCTAssertTrue(connections.applyProbeSuccess(id: created.id, revision: created.revision,
            capabilities: ["task_bundle": true, "video_task_submission": true, "video_operations": true, "video_production": true, "artifacts": true, "run_status": true, "run_submission": false]))
        let profile = try XCTUnwrap(connections.profile(id: created.id))
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(
            "video-reconcile-inflight-\(UUID().uuidString)", isDirectory: true)
        let tasks = AgentTaskStore(fileURL: root.appendingPathComponent("tasks", isDirectory: true))
        let bundle = try VideoTaskBundleIO.make(noteId: "note", noteRevision: 1, title: "Lesson",
            markdown: "# Lesson", audience: "Students", learningGoal: "Understand", durationSeconds: 60)
        let payload = try AgentTaskPayload(title: "Lesson", input: "Explain",
            source: AgentTaskSource(noteID: "note", noteRevision: 1), bundle: bundle)
        let task = try tasks.create(profile: profile, payload: payload)
        _ = try tasks.mutate(id: task.id, expectedRevision: task.recordRevision) {
            $0.remoteTaskID = "remote-1"; $0.status = .completed
        }
        let videoStore = AgentVideoOperationStore(directory: root.appendingPathComponent("video", isDirectory: true))
        let operation = AgentVideoOperationRecord(parentTaskLocalID: task.id,
            connection: AgentTaskConnectionIdentity(profile: profile), remoteTaskID: "remote-1", action: .initialize)
        _ = try videoStore.prepare(operation)
        try videoStore.mutate(taskID: task.id, operationID: operation.id) {
            $0.submissionState = .accepted
            $0.operationID = "11111111-1111-4111-8111-111111111111"
            $0.remoteStatus = initialStatus
            $0.errorCode = initialStatus == .unknown ? "worker_unknown" : nil
        }
        let service = AgentVideoOperationService(connections: connections, tasks: tasks, store: videoStore,
            client: AgentVideoOperationClient(session: session))
        return (root, suite, connections, profile.id, task.id, operation.id, videoStore, service)
    }

    private func makeRunningVideoCancelSetup(session: URLSession,
                                             initialIntentDelivery: AgentVideoCancelDelivery? = .noRequest) throws ->
        (root: URL, suite: String, taskID: UUID, operationID: UUID,
         videoStore: AgentVideoOperationStore, service: AgentVideoOperationService) {
        let (defaults, suite) = makeDefaults()
        let connections = AgentConnectionStore(defaults: defaults, keychain: MemoryTokenStore())
        let created = try connections.create(name: "Home", kind: .builtinVideo, endpoint: "https://fixture.test",
            token: "device-token", transport: .bridge, bridgeID: "bridge-1", instanceID: "instance-1",
            certSHA256: String(repeating: "a", count: 64))
        XCTAssertTrue(connections.applyProbeSuccess(id: created.id, revision: created.revision,
            capabilities: ["task_bundle": true, "video_task_submission": true, "video_operations": true, "video_production": true, "artifacts": true, "run_status": true, "run_submission": false]))
        let profile = try XCTUnwrap(connections.profile(id: created.id))
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(
            "video-running-cancel-service-\(UUID().uuidString)", isDirectory: true)
        let tasks = AgentTaskStore(fileURL: root.appendingPathComponent("tasks", isDirectory: true))
        let bundle = try VideoTaskBundleIO.make(noteId: "note", noteRevision: 1, title: "Lesson",
            markdown: "# Lesson", audience: "Students", learningGoal: "Understand", durationSeconds: 60)
        let payload = try AgentTaskPayload(title: "Lesson", input: "Explain",
            source: AgentTaskSource(noteID: "note", noteRevision: 1), bundle: bundle)
        let task = try tasks.create(profile: profile, payload: payload)
        _ = try tasks.mutate(id: task.id, expectedRevision: task.recordRevision) {
            $0.remoteTaskID = "remote-1"; $0.status = .completed
        }
        let videoStore = AgentVideoOperationStore(directory: root.appendingPathComponent("video", isDirectory: true))
        let operation = AgentVideoOperationRecord(parentTaskLocalID: task.id,
            connection: AgentTaskConnectionIdentity(profile: profile), remoteTaskID: "remote-1",
            action: .storyboard, revision: 1, eventCursor: 1)
        _ = try videoStore.prepare(operation)
        try videoStore.mutate(taskID: task.id, operationID: operation.id) {
            $0.submissionState = .accepted
            $0.operationID = "22222222-2222-4222-8222-222222222222"
            $0.remoteStatus = .running
        }
        let running = try XCTUnwrap(videoStore.snapshot(for: task.id).operations.first)
        if let initialIntentDelivery {
            let intent = AgentVideoRunningCancelIntent(operationID: "22222222-2222-4222-8222-222222222222",
                clientOperationID: running.clientOperationID, connection: running.connection, remoteTaskID: running.remoteTaskID)
            _ = try videoStore.mutate(taskID: task.id, operationID: operation.id) { $0.runningCancelIntent = intent }
            if initialIntentDelivery != .prepared {
                _ = try videoStore.mutate(taskID: task.id, operationID: operation.id) {
                    guard var saved = $0.runningCancelIntent else { throw AgentTaskError.staleTask }
                    saved.delivery = initialIntentDelivery
                    $0.runningCancelIntent = saved
                }
            }
        }
        let service = AgentVideoOperationService(connections: connections, tasks: tasks, store: videoStore,
            client: AgentVideoOperationClient(session: session))
        return (root, suite, task.id, operation.id, videoStore, service)
    }

    private static func videoOperationResponse(status: String, key: String, error: String? = nil,
                                                result: [String: Any]? = nil) -> FixtureResponse {
        let resultValue: Any = result.map { $0 as Any } ?? NSNull()
        let errorValue: Any = error.map { $0 as Any } ?? NSNull()
        return FixtureResponse(status: 200, json: [
            "object": "padnote.video.operation", "protocol_version": 1,
            "operation_id": "11111111-1111-4111-8111-111111111111", "task_id": "remote-1",
            "client_operation_id": key, "action": "initialize", "status": status,
            "created_at": 1, "updated_at": 2, "result": resultValue, "error": errorValue
        ])
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
