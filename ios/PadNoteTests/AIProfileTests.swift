import XCTest
import UIKit
@testable import PadNote

final class AIProfileTests: XCTestCase {
    final class MemorySecrets: SecretStore {
        var values: [String: String] = [:]
        var deleted: [String] = []
        var failingDeletes = Set<String>()
        var writeCount = 0
        var failWriteAt: Int?
        func read(reference: String) throws -> String? { values[reference] ?? "fixture-token" }
        func write(_ value: String, reference: String) throws {
            writeCount += 1
            if writeCount == failWriteAt { throw NSError(domain: "MemorySecrets", code: 2) }
            values[reference] = value
        }
        func delete(reference: String) throws {
            if failingDeletes.contains(reference) { throw NSError(domain: "MemorySecrets", code: 1) }
            values.removeValue(forKey: reference)
            deleted.append(reference)
        }
    }

    final class ProfileURLProtocol: URLProtocol {
        struct Capture { let url: URL; let body: [String: Any] }
        static var captures: [Capture] = []
        static var lock = NSLock()
        override class func canInit(with request: URLRequest) -> Bool { request.url?.host?.hasSuffix(".test") == true }
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
            let data = Self.bodyData(request)
            let object = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
            if let url = request.url { Self.lock.lock(); Self.captures.append(Capture(url: url, body: object)); Self.lock.unlock() }
            let content = request.url?.host == "vision.test" ? "TRANSCRIPT" : "ANSWER"
            let responseData = Data("{\"choices\":[{\"message\":{\"content\":\"\(content)\"}}]}".utf8)
            let response = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil,
                                           headerFields: ["Content-Type": "application/json"])!
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: responseData)
            client?.urlProtocolDidFinishLoading(self)
        }
        override func stopLoading() {}
    }

    override func setUp() {
        ProfileURLProtocol.lock.lock(); ProfileURLProtocol.captures = []; ProfileURLProtocol.lock.unlock()
    }
    func testAndroidProviderPresetsRemainStable() {
        let presets = Dictionary(uniqueKeysWithValues: AIProviderPreset.all.map { ($0.provider, $0) })
        XCTAssertEqual(presets[.bigModel]?.endpoint, "https://open.bigmodel.cn/api/paas/v4")
        XCTAssertEqual(presets[.aliTokenPlan]?.visionModel, "qwen3.7-plus")
        XCTAssertEqual(presets[.miniMaxTokenPlan]?.endpoint, "https://api.minimaxi.com/v1")
        XCTAssertEqual(presets[.kimi]?.textModel, "kimi-k2.6")
    }

    func testProfilesPersistAndUseSeparateKeyReferences() {
        let suite = "padnote.profile.tests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let store = AISettingsStore(defaults: defaults)
        var profile = AIProfile(name: "订阅", mode: .twoStage, visionEndpoint: "https://vision.example/v1", visionModel: "vision", textEndpoint: "https://text.example/v1", textModel: "text")
        store.upsert(profile); store.select(profile.id); store.mode = .twoStage
        let restored = AISettingsStore(defaults: defaults)
        XCTAssertEqual(restored.activeProfile?.name, "订阅")
        XCTAssertEqual(restored.activeProfile?.mode, .twoStage)
        XCTAssertNotEqual(profile.visionKeyReference, profile.textKeyReference)
        profile.name = "改名"; restored.upsert(profile)
        XCTAssertEqual(restored.activeProfile?.name, "改名")
    }

    func testLegacySettingsBecomeDefaultProfileWithoutReadingSecret() {
        let suite = "padnote.legacy.tests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let legacy = AISettings(endpoint: "https://legacy.example/v1", model: "legacy-model", keyReference: "secure-storage://padnote/legacy")
        defaults.set(try? JSONEncoder().encode(legacy), forKey: "padnote.ai.settings")
        let store = AISettingsStore(defaults: defaults)
        XCTAssertEqual(store.activeProfile?.visionEndpoint, legacy.endpoint)
        XCTAssertEqual(store.activeProfile?.visionKeyReference, legacy.keyReference)
    }

    func testSelectingProfileSynchronizesLegacySettingsAndDeletingActiveFallsBack() {
        let suite = "padnote.profile.select.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let store = AISettingsStore(defaults: defaults)
        let first = store.activeProfile!
        let second = AIProfile(name: "第二档案", mode: .twoStage, visionEndpoint: "https://vision.test/v1", visionModel: "vision-model", textEndpoint: "https://text.test/v1", textModel: "text-model")
        store.upsert(second); store.select(second.id)
        XCTAssertEqual(store.settings.endpoint, second.visionEndpoint)
        XCTAssertEqual(store.settings.model, second.visionModel)
        XCTAssertEqual(store.settings.keyReference, second.visionKeyReference)
        XCTAssertEqual(store.visionProfileID, second.id)
        XCTAssertEqual(store.textProfileID, second.id)
        store.delete(second.id)
        XCTAssertEqual(store.activeProfileID, first.id)
        XCTAssertEqual(store.visionProfileID, first.id)
        XCTAssertEqual(store.textProfileID, first.id)
        XCTAssertEqual(store.settings.endpoint, first.visionEndpoint)
    }

    func testDeletingFinalProfileUsesDurableCleanupJournalAndDoesNotReviveLegacySecret() throws {
        let suite = "padnote.profile.delete-final.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let secrets = MemorySecrets()
        let legacyReference = "secure-storage://legacy/final"
        secrets.values[legacyReference] = "legacy-secret"
        defaults.set(try JSONEncoder().encode(AISettings(endpoint: "https://legacy.test/v1",
            model: "legacy", keyReference: legacyReference)), forKey: "padnote.ai.settings")

        let migrated = AISettingsStore(defaults: defaults, secretStore: secrets,
                                       processGeneration: "launch-a")
        let onlyID = try XCTUnwrap(migrated.activeProfileID)
        XCTAssertEqual(migrated.activeProfile?.visionKeyReference, legacyReference)
        migrated.delete(onlyID)
        XCTAssertTrue(migrated.profiles.isEmpty)
        XCTAssertNil(migrated.activeProfileID)
        XCTAssertNil(migrated.requestProfile)
        XCTAssertEqual(migrated.settings.keyReference, "")
        XCTAssertTrue(secrets.deleted.isEmpty, "the mutation process keeps rollback credentials until metadata survives a restart")

        _ = AISettingsStore(defaults: defaults, secretStore: secrets,
                            processGeneration: "launch-a")
        XCTAssertTrue(secrets.deleted.isEmpty,
                      "another store in the same launch must not delete the rollback credential")

        let restarted = AISettingsStore(defaults: defaults, secretStore: secrets,
                                        processGeneration: "launch-b")
        XCTAssertTrue(restarted.profiles.isEmpty, "an intentional empty profile list must not remigrate legacy settings")
        XCTAssertNil(restarted.requestProfile)
        XCTAssertEqual(secrets.deleted, [legacyReference])
        _ = AISettingsStore(defaults: defaults, secretStore: secrets,
                            processGeneration: "launch-b")
        XCTAssertEqual(secrets.deleted, [legacyReference], "cleanup is idempotent")
    }

    func testDeletingProfileKeepsSharedReferenceAndCleansOnlyUnreferencedSecret() throws {
        let suite = "padnote.profile.shared-secret.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let secrets = MemorySecrets()
        let shared = "secure-storage://shared"
        let unique = "secure-storage://unique-text"
        let other = "secure-storage://other-text"
        let first = AIProfile(id: "first", name: "第一", visionEndpoint: "https://one.test/v1",
            visionModel: "one", textEndpoint: "https://one.test/v1", textModel: "one-text",
            visionKeyReference: shared, textKeyReference: unique)
        let second = AIProfile(id: "second", name: "第二", visionEndpoint: "https://two.test/v1",
            visionModel: "two", textEndpoint: "https://two.test/v1", textModel: "two-text",
            visionKeyReference: shared, textKeyReference: other)
        defaults.set(try JSONEncoder().encode([first, second]), forKey: "padnote.ai.profiles")
        defaults.set(first.id, forKey: "padnote.ai.activeProfile")

        let store = AISettingsStore(defaults: defaults, secretStore: secrets,
                                    processGeneration: "launch-a")
        store.delete(first.id)
        XCTAssertEqual(store.activeProfileID, second.id)
        XCTAssertEqual(store.settings.keyReference, shared)
        _ = AISettingsStore(defaults: defaults, secretStore: secrets,
                            processGeneration: "launch-a")
        XCTAssertTrue(secrets.deleted.isEmpty)
        _ = AISettingsStore(defaults: defaults, secretStore: secrets,
                            processGeneration: "launch-b")
        XCTAssertTrue(secrets.deleted.contains(unique))
        XCTAssertFalse(secrets.deleted.contains(shared))
        XCTAssertFalse(secrets.deleted.contains(other))
    }

    func testChangingCredentialReferencesCleansOldValuesAfterCommittedRestart() throws {
        let suite = "padnote.profile.replace-secret.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let secrets = MemorySecrets()
        let oldVision = "secure-storage://old-vision"
        let oldText = "secure-storage://old-text"
        var profile = AIProfile(id: "replace", name: "替换", mode: .twoStage,
            visionEndpoint: "https://vision.test/v1", visionModel: "vision",
            textEndpoint: "https://text.test/v1", textModel: "text",
            visionKeyReference: oldVision, textKeyReference: oldText)
        defaults.set(try JSONEncoder().encode([profile]), forKey: "padnote.ai.profiles")
        defaults.set(profile.id, forKey: "padnote.ai.activeProfile")
        let store = AISettingsStore(defaults: defaults, secretStore: secrets,
                                    processGeneration: "launch-a")

        let newVision = "secure-storage://new-vision"
        let newText = "secure-storage://new-text"
        profile.visionKeyReference = newVision
        profile.textKeyReference = newText
        store.upsert(profile)
        XCTAssertEqual(store.settings.keyReference, newVision)
        XCTAssertTrue(secrets.deleted.isEmpty)

        _ = AISettingsStore(defaults: defaults, secretStore: secrets,
                            processGeneration: "launch-a")
        XCTAssertTrue(secrets.deleted.isEmpty)
        _ = AISettingsStore(defaults: defaults, secretStore: secrets,
                            processGeneration: "launch-b")
        XCTAssertEqual(Set(secrets.deleted), Set([oldVision, oldText]))
        XCTAssertFalse(secrets.deleted.contains(newVision))
        XCTAssertFalse(secrets.deleted.contains(newText))
    }

    func testEditingSharedCredentialUsesNewReferenceWithoutChangingOtherProfile() throws {
        let suite = "padnote.profile.shared-edit.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let secrets = MemorySecrets()
        let shared = "secure-storage://shared-edit"
        secrets.values[shared] = "old-token"
        let first = AIProfile(id: "shared-a", name: "A", visionEndpoint: "https://a.test/v1",
            visionModel: "model", visionKeyReference: shared, textKeyReference: shared)
        let second = AIProfile(id: "shared-b", name: "B", visionEndpoint: "https://b.test/v1",
            visionModel: "model", visionKeyReference: shared, textKeyReference: shared)
        defaults.set(try JSONEncoder().encode([first, second]), forKey: "padnote.ai.profiles")
        defaults.set(first.id, forKey: "padnote.ai.activeProfile")
        let store = AISettingsStore(defaults: defaults, secretStore: secrets, processGeneration: "launch-a")
        let oldIdentity = try XCTUnwrap(store.requestRecipientIdentity)
        try store.upsert(first, visionToken: "new-token", textToken: nil)
        let edited = try XCTUnwrap(store.profiles.first { $0.id == first.id })
        let untouched = try XCTUnwrap(store.profiles.first { $0.id == second.id })
        XCTAssertNotEqual(edited.visionKeyReference, shared)
        XCTAssertEqual(untouched.visionKeyReference, shared)
        XCTAssertEqual(try secrets.read(reference: shared), "old-token")
        XCTAssertEqual(try secrets.read(reference: edited.visionKeyReference), "new-token")
        XCTAssertNotEqual(store.requestRecipientIdentity, oldIdentity)
    }

    func testSecondCredentialWriteFailureLeavesMetadataAndSharedSecretsUnchanged() throws {
        let suite = "padnote.profile.partial-write.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let secrets = MemorySecrets()
        let profile = AIProfile(id: "partial", name: "P", mode: .twoStage,
            visionEndpoint: "https://vision.test/v1", visionModel: "vision",
            textEndpoint: "https://text.test/v1", textModel: "text",
            visionKeyReference: "secure-storage://old-v", textKeyReference: "secure-storage://old-t")
        defaults.set(try JSONEncoder().encode([profile]), forKey: "padnote.ai.profiles")
        defaults.set(profile.id, forKey: "padnote.ai.activeProfile")
        let store = AISettingsStore(defaults: defaults, secretStore: secrets, processGeneration: "launch-a")
        secrets.failWriteAt = 2
        XCTAssertThrowsError(try store.upsert(profile, visionToken: "new-v", textToken: "new-t"))
        let retained = try XCTUnwrap(store.profiles.first)
        XCTAssertEqual(retained.visionKeyReference, profile.visionKeyReference)
        XCTAssertEqual(retained.textKeyReference, profile.textKeyReference)
        XCTAssertEqual(retained.revision, profile.revision)
        XCTAssertFalse(secrets.values.values.contains("new-v"), "staged credential must be rolled back")
    }

    func testFailedSecretCleanupStaysJournaledAndRetriesAfterLaterLaunch() throws {
        let suite = "padnote.profile.secret-retry.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let secrets = MemorySecrets()
        let stale = "secure-storage://stale"
        let profile = AIProfile(id: "stale-profile", name: "旧档案",
            visionEndpoint: "https://legacy.test/v1", visionModel: "legacy",
            visionKeyReference: stale, textKeyReference: stale)
        defaults.set(try JSONEncoder().encode([profile]), forKey: "padnote.ai.profiles")
        let deleting = AISettingsStore(defaults: defaults, secretStore: secrets,
                                       processGeneration: "launch-a")
        deleting.delete(profile.id)
        secrets.failingDeletes.insert(stale)

        let failed = AISettingsStore(defaults: defaults, secretStore: secrets,
                                     processGeneration: "launch-b")
        XCTAssertTrue(failed.profiles.isEmpty)
        XCTAssertNil(failed.requestProfile)
        XCTAssertNotNil(failed.credentialError)
        XCTAssertFalse(secrets.deleted.contains(stale))

        secrets.failingDeletes.remove(stale)
        let retried = AISettingsStore(defaults: defaults, secretStore: secrets,
                                      processGeneration: "launch-c")
        XCTAssertTrue(retried.profiles.isEmpty)
        XCTAssertNil(retried.requestProfile)
        XCTAssertTrue(secrets.deleted.contains(stale))
    }

    func testCorruptProfilePayloadPreservesPendingSecretsUntilMetadataIsRecovered() throws {
        let suite = "padnote.profile.corrupt-preserve.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let secrets = MemorySecrets()
        let stale = "secure-storage://stale-after-corruption"
        let profile = AIProfile(id: "stale-profile", name: "旧档案",
            visionEndpoint: "https://legacy.test/v1", visionModel: "legacy",
            visionKeyReference: stale, textKeyReference: stale)
        defaults.set(try JSONEncoder().encode([profile]), forKey: "padnote.ai.profiles")
        let deleting = AISettingsStore(defaults: defaults, secretStore: secrets,
                                       processGeneration: "launch-a")
        deleting.delete(profile.id)
        defaults.set(Data("corrupt-profile-payload".utf8), forKey: "padnote.ai.profiles")

        let corrupt = AISettingsStore(defaults: defaults, secretStore: secrets,
                                      processGeneration: "launch-b")
        XCTAssertTrue(corrupt.profiles.isEmpty)
        XCTAssertNil(corrupt.requestProfile)
        XCTAssertNotNil(corrupt.credentialError)
        XCTAssertTrue(secrets.deleted.isEmpty)

        _ = AISettingsStore(defaults: defaults, secretStore: secrets,
                            processGeneration: "launch-c")
        XCTAssertTrue(secrets.deleted.isEmpty,
                      "additional launches must not guess references from corrupt metadata")

        defaults.set(try JSONEncoder().encode([AIProfile]()), forKey: "padnote.ai.profiles")
        _ = AISettingsStore(defaults: defaults, secretStore: secrets,
                            processGeneration: "launch-d")
        XCTAssertEqual(secrets.deleted, [stale])
    }

    func testTwoStageRoutesVisionOnceAndReusesTranscriptForFollowUp() async throws {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [ProfileURLProtocol.self]
        let profile = AIProfile(name: "fixture", mode: .twoStage,
                                visionEndpoint: "https://vision.test/v1", visionModel: "vision-model",
                                textEndpoint: "https://text.test/v1", textModel: "text-model")
        let client = AIClient(settings: AISettings(endpoint: profile.visionEndpoint, model: profile.visionModel),
                              secretStore: MemorySecrets(), sessionConfiguration: configuration)
        let image = Data([0, 1, 2, 3]).base64EncodedString()
        let firstRequest = AIConversationRequest(action: .explain, model: profile.textModel,
            messages: [.init(role: "user", content: "解释", imageDataURL: "data:image/png;base64,\(image)")])
        let transcript = try await client.transcribe(UIImage(data: tinyPNG())!, profile: profile)
        XCTAssertEqual(transcript, "TRANSCRIPT")
        _ = try await client.send(firstRequest, profile: profile, transcript: transcript)
        let followUp = AIConversationRequest(action: .custom, model: profile.textModel,
            messages: [.init(role: "user", content: "继续说明")])
        _ = try await client.send(followUp, profile: profile, transcript: transcript)
        ProfileURLProtocol.lock.lock(); let captures = ProfileURLProtocol.captures; ProfileURLProtocol.lock.unlock()
        XCTAssertEqual(captures.count, 3)
        XCTAssertEqual(captures.filter { $0.url.host == "vision.test" }.count, 1)
        let textCaptures = captures.filter { $0.url.host == "text.test" }
        XCTAssertEqual(textCaptures.count, 2)
        for capture in textCaptures {
            let messages = capture.body["messages"] as? [[String: Any]] ?? []
            let encoded = String(describing: messages)
            XCTAssertTrue(encoded.contains("TRANSCRIPT"))
            XCTAssertFalse(encoded.contains("image_url"))
        }
        let vision = captures.first { $0.url.host == "vision.test" }!
        XCTAssertTrue(String(describing: vision.body["messages"] ?? "").contains("image_url"))
    }

    private func tinyPNG() -> Data {
        // A 1×1 transparent PNG fixture; no file or network access is needed.
        Data(base64Encoded: "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=")!
    }
}
