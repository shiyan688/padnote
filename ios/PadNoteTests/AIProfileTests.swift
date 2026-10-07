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
        func readForCleanup(reference: String) throws -> String? { values[reference] }
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
        let store = AISettingsStore(defaults: defaults, secretStore: MemorySecrets())
        var profile = AIProfile(name: "订阅", mode: .twoStage, visionEndpoint: "https://vision.example/v1", visionModel: "vision", textEndpoint: "https://text.example/v1", textModel: "text")
        store.upsert(profile); store.select(profile.id); store.mode = .twoStage
        let restored = AISettingsStore(defaults: defaults, secretStore: MemorySecrets())
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
        let store = AISettingsStore(defaults: defaults, secretStore: MemorySecrets())
        XCTAssertEqual(store.activeProfile?.visionEndpoint, legacy.endpoint)
        XCTAssertEqual(store.activeProfile?.visionKeyReference, legacy.keyReference)
    }

    func testSelectingProfileSynchronizesLegacySettingsAndDeletingActiveFallsBack() {
        let suite = "padnote.profile.select.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let store = AISettingsStore(defaults: defaults, secretStore: MemorySecrets())
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
        let secrets = MemorySecrets()
        secrets.values[profile.visionKeyReference] = "vision-fixture-token"
        secrets.values[profile.textKeyReference] = "text-fixture-token"
        let suite = "padnote.ai-client.profile.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let runtime = CredentialLifecycleRuntime.isolatedForInjectedAI(defaults: defaults, secrets: secrets)
        let client = AIClient(settings: AISettings(endpoint: profile.visionEndpoint, model: profile.visionModel),
                              secretStore: secrets, lifecycleRuntime: runtime,
                              sessionConfiguration: configuration)
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
    final class LifecycleTokens: AgentTokenStore {
        var values: [String: String] = [:]
        var unclearedDeletes = Set<String>()
        func save(_ value: String, reference: String) throws { values[reference] = value }
        func read(reference: String) throws -> String? { values[reference] }
        func delete(reference: String) { if !unclearedDeletes.contains(reference) { values.removeValue(forKey: reference) } }
    }

    final class GatedExistentialTokens: AgentTokenStore {
        func save(_ value: String, reference: String) throws {}
        func read(reference: String) throws -> String? { "ordinary-read-gate" }
        func readForCleanup(reference: String) throws -> String? { nil }
        func delete(reference: String) {}
    }

    func testCleanupReadRequirementsDispatchThroughStoreExistentials() throws {
        let secrets: SecretStore = MemorySecrets()
        XCTAssertEqual(try secrets.read(reference: "missing"), "fixture-token")
        XCTAssertNil(try secrets.readForCleanup(reference: "missing"),
                     "cleanup verification must reach the raw adapter implementation")

        let tokens: AgentTokenStore = GatedExistentialTokens()
        XCTAssertEqual(try tokens.read(reference: "missing"), "ordinary-read-gate")
        XCTAssertNil(try tokens.readForCleanup(reference: "missing"),
                     "cleanup verification must not fall back to the ordinary gated read")
    }

    private func makeIsolatedLifecycle(defaults: UserDefaults, agentDefaults: UserDefaults,
                                       secrets: MemorySecrets, tokens: LifecycleTokens,
                                       journalDirectory: URL,
                                       agentMetadataWriter: ((UserDefaults, String, Data) -> Bool)? = nil) -> CredentialLifecycleRuntime {
        try! FileManager.default.createDirectory(at: journalDirectory, withIntermediateDirectories: true,
            attributes: [.posixPermissions: 0o700])
        return CredentialLifecycleRuntime(
            journalURL: journalDirectory.appendingPathComponent("journal.json"),
            processGeneration: "test-launch-a",
            participants: [
                AISettingsStore.credentialLifecycleParticipant(defaults: defaults, secrets: secrets),
                AgentConnectionStore.credentialLifecycleParticipant(defaults: agentDefaults, keychain: tokens,
                    metadataWriter: agentMetadataWriter)
            ])
    }

    func testNativeAdaptersPreserveProfilesGateOldReferencesAndRetryRawCleanupAcrossServices() throws {
        let aiSuite = "padnote.clear.native-ai.\(UUID().uuidString)"
        let agentSuite = "padnote.clear.native-agent.\(UUID().uuidString)"
        let aiDefaults = UserDefaults(suiteName: aiSuite)!
        let agentDefaults = UserDefaults(suiteName: agentSuite)!
        defer { aiDefaults.removePersistentDomain(forName: aiSuite); agentDefaults.removePersistentDomain(forName: agentSuite) }
        let secrets = MemorySecrets(), tokens = LifecycleTokens()
        let sharedAccount = "token" // Deliberately the same account string in two independent services.
        let aiProfile = AIProfile(id: "meta-ai", name: "保留的AI名称", provider: .deepSeek,
            mode: .twoStage, visionEndpoint: "https://vision.example/v1", visionModel: "vision-v2",
            textEndpoint: "https://text.example/v1", textModel: "text-v3",
            visionKeyReference: sharedAccount, textKeyReference: sharedAccount, revision: 7)
        aiDefaults.set(try JSONEncoder().encode([aiProfile]), forKey: "padnote.ai.profiles")
        aiDefaults.set(try JSONEncoder().encode(AISettings(endpoint: aiProfile.visionEndpoint,
            model: aiProfile.visionModel, keyReference: sharedAccount)), forKey: "padnote.ai.settings")
        aiDefaults.set(aiProfile.id, forKey: "padnote.ai.activeProfile")
        secrets.values[sharedAccount] = "ai-old"
        tokens.values[sharedAccount] = "agent-old"
        agentDefaults.set("hermes", forKey: "padnote.agent.kind")
        agentDefaults.set("https://agent.example/api", forKey: "padnote.agent.endpoint")

        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("clear-native-test-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: directory) }
        let runtime = makeIsolatedLifecycle(defaults: aiDefaults, agentDefaults: agentDefaults,
            secrets: secrets, tokens: tokens, journalDirectory: directory)
        XCTAssertEqual(runtime.bootstrapBeforeStores(), .ready)
        let aiStore = AISettingsStore(defaults: aiDefaults, secretStore: secrets,
            processGeneration: "native-launch-a", lifecycleRuntime: runtime)
        let agentStore = AgentConnectionStore(defaults: agentDefaults, keychain: tokens, lifecycleRuntime: runtime)
        let agentProfile = try agentStore.create(name: "保留的Agent名称", kind: .hermes,
            endpoint: "https://agent-profile.example/api", token: "agent-profile-old",
            certSHA256: String(repeating: "a", count: 64))
        let pinnedBeforeClear = try XCTUnwrap(agentStore.profile(id: agentProfile.id))
        let inFlightProbe = try agentStore.snapshotForProbe(id: agentProfile.id)
        let result = runtime.clearKnownCredentials()
        XCTAssertEqual(result.phase, .disabledAwaitingNextProcess)
        XCTAssertEqual(aiStore.profiles.first?.name, "保留的AI名称")
        XCTAssertEqual(aiStore.profiles.first?.visionEndpoint, "https://vision.example/v1")
        XCTAssertEqual(aiStore.profiles.first?.visionModel, "vision-v2")
        XCTAssertEqual(aiStore.profiles.first?.textModel, "text-v3")
        XCTAssertEqual(agentStore.profile(id: agentProfile.id)?.name, agentProfile.name)
        XCTAssertEqual(agentStore.profile(id: agentProfile.id)?.endpoint, agentProfile.endpoint)
        XCTAssertEqual(agentStore.profile(id: agentProfile.id)?.certSHA256, pinnedBeforeClear.certSHA256)
        XCTAssertFalse(agentStore.applyProbeSuccess(id: inFlightProbe.id, revision: inFlightProbe.revision,
            lifecycleEpoch: inFlightProbe.lifecycleEpoch, capabilities: ["late": true]),
            "a probe started before clear must not restore metadata after the lifecycle epoch changes")
        XCTAssertNil(agentStore.token(reference: agentProfile.credentialReference), "ordinary reads must not expose stale refs")
        XCTAssertThrowsError(try runtime.readCredential(side: .ai, reference: sharedAccount) { try secrets.readForCleanup(reference: sharedAccount) })
        XCTAssertThrowsError(try runtime.readCredential(side: .agent, reference: sharedAccount) { try tokens.readForCleanup(reference: sharedAccount) })
        let forgedMarker = "padnote-disabled://untrusted-marker"
        XCTAssertTrue(try runtime.isDisabled(side: .ai, reference: forgedMarker))
        XCTAssertThrowsError(try runtime.readCredential(side: .ai, reference: forgedMarker) { try secrets.readForCleanup(reference: forgedMarker) })

        var updatedAI = try XCTUnwrap(aiStore.profiles.first)
        updatedAI.name = "保留的AI名称"
        try aiStore.upsert(updatedAI, visionToken: "ai-fresh", textToken: "ai-fresh-text")
        updatedAI = try XCTUnwrap(aiStore.profiles.first { $0.id == updatedAI.id })
        let currentAgent = try XCTUnwrap(agentStore.profile(id: agentProfile.id))
        let updatedAgent = try agentStore.update(id: currentAgent.id, expectedRevision: currentAgent.revision,
            name: currentAgent.name, kind: currentAgent.kind, endpoint: currentAgent.endpoint,
            token: "agent-fresh", transport: currentAgent.transport, bridgeID: currentAgent.bridgeID,
            instanceID: currentAgent.instanceID)
        XCTAssertNotEqual(updatedAI.visionKeyReference, sharedAccount)
        XCTAssertNotEqual(updatedAgent.credentialReference, agentProfile.credentialReference)
        XCTAssertEqual(updatedAgent.certSHA256, pinnedBeforeClear.certSHA256)
        XCTAssertEqual(try secrets.readForCleanup(reference: updatedAI.visionKeyReference), "ai-fresh")
        XCTAssertEqual(try tokens.readForCleanup(reference: updatedAgent.credentialReference), "agent-fresh")

        // A new process retries actual adapter cleanup and confirms absence per service.
        secrets.failingDeletes.insert(sharedAccount)
        tokens.unclearedDeletes.insert(sharedAccount)
        let nextRuntime = CredentialLifecycleRuntime(journalURL: directory.appendingPathComponent("journal.json"),
            processGeneration: "native-launch-b", participants: [
                AISettingsStore.credentialLifecycleParticipant(defaults: aiDefaults, secrets: secrets),
                AgentConnectionStore.credentialLifecycleParticipant(defaults: agentDefaults, keychain: tokens)
            ])
        XCTAssertEqual(nextRuntime.bootstrapBeforeStores(), .cleanupRetryRequired)
        XCTAssertEqual(try secrets.readForCleanup(reference: sharedAccount), "ai-old")
        XCTAssertEqual(try tokens.readForCleanup(reference: sharedAccount), "agent-old")
        secrets.failingDeletes.remove(sharedAccount)
        tokens.unclearedDeletes.remove(sharedAccount)
        let finalRuntime = CredentialLifecycleRuntime(journalURL: directory.appendingPathComponent("journal.json"),
            processGeneration: "native-launch-c", participants: [
                AISettingsStore.credentialLifecycleParticipant(defaults: aiDefaults, secrets: secrets),
                AgentConnectionStore.credentialLifecycleParticipant(defaults: agentDefaults, keychain: tokens)
            ])
        XCTAssertEqual(finalRuntime.bootstrapBeforeStores(), .completeKnownReferences)
        XCTAssertNil(try secrets.readForCleanup(reference: sharedAccount))
        XCTAssertNil(try tokens.readForCleanup(reference: sharedAccount))
        XCTAssertEqual(try secrets.readForCleanup(reference: updatedAI.visionKeyReference), "ai-fresh")
        XCTAssertEqual(try tokens.readForCleanup(reference: updatedAgent.credentialReference), "agent-fresh")
    }

    func testNativeAgentAdapterPartialMetadataFailureRecoversOnNextRuntime() throws {
        let aiSuite = "padnote.clear.partial-ai.\(UUID().uuidString)"
        let agentSuite = "padnote.clear.partial-agent.\(UUID().uuidString)"
        let aiDefaults = UserDefaults(suiteName: aiSuite)!
        let agentDefaults = UserDefaults(suiteName: agentSuite)!
        defer { aiDefaults.removePersistentDomain(forName: aiSuite); agentDefaults.removePersistentDomain(forName: agentSuite) }
        let secrets = MemorySecrets(), tokens = LifecycleTokens()
        let aiRef = "secure-storage://partial-ai"
        let profile = AIProfile(id: "partial", name: "Partial", visionEndpoint: "https://ai.example/v1",
            visionModel: "vision", visionKeyReference: aiRef, textKeyReference: aiRef)
        aiDefaults.set(try JSONEncoder().encode([profile]), forKey: "padnote.ai.profiles")
        aiDefaults.set(try JSONEncoder().encode(AISettings(endpoint: profile.visionEndpoint,
            model: profile.visionModel, keyReference: aiRef)), forKey: "padnote.ai.settings")
        secrets.values[aiRef] = "ai-secret"
        // Seed through the real store, then its native payload adapter is used for disable/recovery.
        let seedDirectory = FileManager.default.temporaryDirectory.appendingPathComponent("clear-partial-seed-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: seedDirectory) }
        try FileManager.default.createDirectory(at: seedDirectory, withIntermediateDirectories: true,
            attributes: [.posixPermissions: 0o700])
        let seedRuntime = CredentialLifecycleRuntime(journalURL: seedDirectory.appendingPathComponent("journal.json"),
            processGeneration: "seed", participants: [
                AISettingsStore.credentialLifecycleParticipant(defaults: aiDefaults, secrets: secrets),
                AgentConnectionStore.credentialLifecycleParticipant(defaults: agentDefaults, keychain: tokens)
            ])
        XCTAssertEqual(seedRuntime.bootstrapBeforeStores(), .ready)
        let seedStore = AgentConnectionStore(defaults: agentDefaults, keychain: tokens, lifecycleRuntime: seedRuntime)
        let created = try seedStore.create(name: "Agent", kind: .hermes,
            endpoint: "https://agent.example", token: "agent-secret")
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("clear-partial-test-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: directory) }
        let runtime = makeIsolatedLifecycle(defaults: aiDefaults, agentDefaults: agentDefaults,
            secrets: secrets, tokens: tokens, journalDirectory: directory,
            agentMetadataWriter: { _, _, _ in false })
        XCTAssertEqual(runtime.bootstrapBeforeStores(), .ready)
        let result = runtime.clearKnownCredentials()
        XCTAssertEqual(result.phase, .partiallyDisabled)
        XCTAssertTrue(result.aiMetadataVerified)
        XCTAssertFalse(result.agentMetadataVerified)
        XCTAssertFalse(runtime.allowsMutation)
        XCTAssertThrowsError(try runtime.readCredential(side: .ai, reference: aiRef) { try secrets.readForCleanup(reference: aiRef) })
        XCTAssertEqual(try tokens.readForCleanup(reference: created.credentialReference), "agent-secret")

        // The real Agent adapter succeeds on retry; the same durable reference set remains gated.
        let recovered = CredentialLifecycleRuntime(journalURL: directory.appendingPathComponent("journal.json"),
            processGeneration: "next-launch", participants: [
                AISettingsStore.credentialLifecycleParticipant(defaults: aiDefaults, secrets: secrets),
                AgentConnectionStore.credentialLifecycleParticipant(defaults: agentDefaults, keychain: tokens)
            ])
        XCTAssertEqual(recovered.bootstrapBeforeStores(), .completeKnownReferences)
        XCTAssertNil(try tokens.readForCleanup(reference: created.credentialReference))
        XCTAssertThrowsError(try recovered.readCredential(side: .agent, reference: created.credentialReference) { try tokens.readForCleanup(reference: created.credentialReference) })
    }

    func testCorruptJournalAfterBootstrapBlocksWritesWithoutNotificationLoop() async throws {
        let aiSuite = "padnote.clear.corrupt-ai.\(UUID().uuidString)"
        let agentSuite = "padnote.clear.corrupt-agent.\(UUID().uuidString)"
        let aiDefaults = UserDefaults(suiteName: aiSuite)!
        let agentDefaults = UserDefaults(suiteName: agentSuite)!
        defer { aiDefaults.removePersistentDomain(forName: aiSuite); agentDefaults.removePersistentDomain(forName: agentSuite) }
        let secrets = MemorySecrets()
        let tokens = LifecycleTokens()
        aiDefaults.set(try JSONEncoder().encode(AISettings(endpoint: "https://existing-ai.example/v1",
            model: "existing-model", keyReference: "secure-storage://existing-ai")), forKey: "padnote.ai.settings")
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("clear-journal-test-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: directory) }
        let runtime = makeIsolatedLifecycle(defaults: aiDefaults, agentDefaults: agentDefaults,
            secrets: secrets, tokens: tokens, journalDirectory: directory)
        XCTAssertEqual(runtime.bootstrapBeforeStores(), .ready)
        let store = AISettingsStore(defaults: aiDefaults, secretStore: secrets,
            processGeneration: "test-launch-a", lifecycleRuntime: runtime)
        let agentStore = AgentConnectionStore(defaults: agentDefaults, keychain: tokens, lifecycleRuntime: runtime)
        _ = try agentStore.create(name: "Existing Agent", kind: .hermes,
            endpoint: "https://existing-agent.example", token: "existing-token")
        let original = try XCTUnwrap(aiDefaults.data(forKey: "padnote.ai.settings"))
        let originalAgent = try XCTUnwrap(agentDefaults.data(forKey: "padnote.agent.connections.v1"))
        let notification = expectation(forNotification: CredentialLifecycleRuntime.stateDidChange, object: runtime)
        let notificationLock = NSLock()
        var notificationCount = 0
        let observer = NotificationCenter.default.addObserver(forName: CredentialLifecycleRuntime.stateDidChange,
            object: runtime, queue: nil) { _ in notificationLock.lock(); notificationCount += 1; notificationLock.unlock() }
        defer { NotificationCenter.default.removeObserver(observer) }
        let journalURL = directory.appendingPathComponent("journal.json")
        try Data("{truncated".utf8).write(to: journalURL, options: .atomic)
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: journalURL.path)

        var changed = store.settings
        changed.endpoint = "https://must-not-save.example/v1"
        store.settings = changed
        store.mode = .twoStage
        XCTAssertThrowsError(try agentStore.create(name: "New", kind: .hermes,
            endpoint: "https://must-not-save.example", token: "secret"))

        XCTAssertEqual(agentDefaults.data(forKey: "padnote.agent.connections.v1"), originalAgent,
                       "a damaged durable gate must reject Agent metadata before mutation")
        XCTAssertEqual(aiDefaults.data(forKey: "padnote.ai.settings"), original,
                       "a damaged durable gate must reject writes before changing metadata")
        if case .blocked = runtime.currentState {} else { XCTFail("corrupt journal must visibly block lifecycle operations") }
        XCTAssertNotNil(store.credentialError)
        await fulfillment(of: [notification], timeout: 1)
        try await Task.sleep(nanoseconds: 150_000_000)
        notificationLock.lock(); let observedNotifications = notificationCount; notificationLock.unlock()
        XCTAssertEqual(observedNotifications, 1, "unchanged blocked state must not repost and trigger an observer loop")
    }

    func testIntentCannotLoseInitialCleanupReferencesByEditingPendingSet() throws {
        let aiSuite = "padnote.clear.intent-ai.\(UUID().uuidString)"
        let agentSuite = "padnote.clear.intent-agent.\(UUID().uuidString)"
        let aiDefaults = UserDefaults(suiteName: aiSuite)!
        let agentDefaults = UserDefaults(suiteName: agentSuite)!
        defer { aiDefaults.removePersistentDomain(forName: aiSuite); agentDefaults.removePersistentDomain(forName: agentSuite) }
        let secrets = MemorySecrets()
        let tokens = LifecycleTokens()
        let reference = "secure-storage://known-secret"
        let profile = AIProfile(id: "known", name: "Known", visionEndpoint: "https://vision.example/v1",
            visionModel: "vision", visionKeyReference: reference, textKeyReference: reference)
        aiDefaults.set(try JSONEncoder().encode([profile]), forKey: "padnote.ai.profiles")
        aiDefaults.set(try JSONEncoder().encode(AISettings(endpoint: profile.visionEndpoint,
            model: profile.visionModel, keyReference: reference)), forKey: "padnote.ai.settings")
        secrets.values[reference] = "secret"
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("clear-intent-test-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: directory) }
        let runtime = makeIsolatedLifecycle(defaults: aiDefaults, agentDefaults: agentDefaults,
            secrets: secrets, tokens: tokens, journalDirectory: directory)
        XCTAssertEqual(runtime.bootstrapBeforeStores(), .ready)
        let result = runtime.clearKnownCredentials()
        XCTAssertEqual(result.phase, .disabledAwaitingNextProcess)
        let journalURL = directory.appendingPathComponent("journal.json")
        var object = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: journalURL)) as? [String: Any])
        object["cleanupStarted"] = true
        object["initialPendingDeletion"] = []
        object["pendingDeletion"] = []
        try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys]).write(to: journalURL, options: .atomic)
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: journalURL.path)

        XCTAssertFalse(runtime.allowsMutation)
        XCTAssertThrowsError(try runtime.readCredential(side: .ai, reference: reference) { secrets.values[reference] })
        if case .blocked = runtime.currentState {} else { XCTFail("edited cleanup journal must remain blocked") }
    }

}
