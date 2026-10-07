import SwiftUI

@main
struct PadNoteApp: App {
    @StateObject private var library: NoteLibrary
#if DEBUG
    private let credentialClearFixture: CredentialClearUITestFixture?
    private let credentialClearMaintenanceResult: String?
    private let agentSettingsLayoutFixture: AgentSettingsLayoutUITestFixture?
    private let agentSettingsLayoutMaintenanceResult: String?
    private let agentTaskHistoryFixture: AgentTaskHistoryUITestFixture?
    private let agentTaskHistoryMaintenanceResult: String?
#endif

    init() {
        let arguments = ProcessInfo.processInfo.arguments
#if DEBUG
        var clearFixture: CredentialClearUITestFixture?
        var clearMaintenance: String?
        var settingsLayoutFixture: AgentSettingsLayoutUITestFixture?
        var settingsLayoutMaintenance: String?
        var taskHistoryFixture: AgentTaskHistoryUITestFixture?
        var taskHistoryMaintenance: String?
        if arguments.contains("--uitesting-agent-task-history") {
            if arguments.contains("--agent-task-history-cleanup") {
                taskHistoryMaintenance = "pending"
            } else {
                taskHistoryFixture = AgentTaskHistoryUITestFixture.make()
            }
        } else if arguments.contains("--uitesting-agent-settings-layout") {
            if arguments.contains("--agent-settings-layout-cleanup") {
                settingsLayoutMaintenance = "pending"
            } else if arguments.contains("--agent-settings-layout-inspect-default") {
                settingsLayoutMaintenance = AgentSettingsLayoutUITestPaths.inspectDefault()
            } else {
                settingsLayoutFixture = AgentSettingsLayoutUITestFixture.make()
            }
        } else if arguments.contains("--uitesting-credential-clear") {
            if arguments.contains("--credential-clear-fixture-cleanup") {
                clearMaintenance = CredentialClearUITestPaths.cleanup()
            } else {
                clearFixture = CredentialClearUITestFixture.make()
                _ = clearFixture?.runtime.bootstrapBeforeStores()
            }
        } else {
            _ = CredentialLifecycleRuntime.shared.bootstrapBeforeStores()
        }
        credentialClearFixture = clearFixture
        credentialClearMaintenanceResult = clearMaintenance
        agentSettingsLayoutFixture = settingsLayoutFixture
        agentSettingsLayoutMaintenanceResult = settingsLayoutMaintenance
        agentTaskHistoryFixture = taskHistoryFixture
        agentTaskHistoryMaintenanceResult = taskHistoryMaintenance
#else
        _ = CredentialLifecycleRuntime.shared.bootstrapBeforeStores()
#endif
        let testing: Bool
#if DEBUG
        testing = arguments.contains("--uitesting") || arguments.contains("--uitesting-credential-clear") || arguments.contains("--uitesting-agent-followup") || arguments.contains("--uitesting-agent-video") || arguments.contains("--uitesting-library-backup") || arguments.contains("--uitesting-content-outline") || arguments.contains("--uitesting-agent-settings-layout") || arguments.contains("--uitesting-agent-task-history")
#else
        testing = false
#endif
        var agentSettingsLayoutDirectory: URL?
#if DEBUG
        if arguments.contains("--uitesting-agent-settings-layout") {
            agentSettingsLayoutDirectory = AgentSettingsLayoutUITestPaths.root.appendingPathComponent("notes", isDirectory: true)
        }
#endif
        var agentTaskHistoryDirectory: URL?
#if DEBUG
        if arguments.contains("--uitesting-agent-task-history") {
            agentTaskHistoryDirectory = AgentTaskHistoryUITestPaths.root.appendingPathComponent("notes", isDirectory: true)
        }
#endif
        let directory: URL?
        if let agentTaskHistoryDirectory {
            directory = agentTaskHistoryDirectory
        } else if let agentSettingsLayoutDirectory {
            directory = agentSettingsLayoutDirectory
        } else if let fixtureRoot = LibraryBackupUITestPaths.root,
           arguments.contains("--uitesting-content-outline") {
            directory = fixtureRoot.appendingPathComponent("notes", isDirectory: true)
        } else if testing, arguments.contains("--uitesting-library-backup"),
           let index = arguments.firstIndex(of: "--library-backup-fixture-id"),
           arguments.indices.contains(index + 1), let fixtureID = UUID(uuidString: arguments[index + 1]) {
            directory = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
                .appendingPathComponent("UITestLibrary/LibraryBackup", isDirectory: true)
                .appendingPathComponent(fixtureID.uuidString.lowercased(), isDirectory: true)
        } else {
            directory = testing ? FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
                .appendingPathComponent("UITestLibrary", isDirectory: true) : nil
        }
#if DEBUG
        let outlineMaintenance = arguments.contains("--uitesting-content-outline-inspect") ||
            arguments.contains("--uitesting-content-outline-cleanup")
        if let directory, arguments.contains("--uitesting-content-outline"), !outlineMaintenance,
           let fixtureRoot = LibraryBackupUITestPaths.root {
            let fixtureID = fixtureRoot.lastPathComponent
            let flowID = "outline-flow-\(fixtureID)"
            var note = NoteDocument(id: "outline-note-\(fixtureID)", title: "内容大纲测试笔记",
                pageCount: 2, textFlows: [NoteTextFlow(id: flowID, format: "markdown",
                    source: "第二页的合成测试正文", anchorPageIndex: 1, anchorXInPage: 32, anchorYInPage: 48)])
            note.updatedAt = Date().timeIntervalSince1970 * 1000
            do {
                try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
                let target = directory.appendingPathComponent("\(note.id).json")
                if !FileManager.default.fileExists(atPath: target.path) {
                    try note.encoded().write(to: target, options: .atomic)
                }
            } catch {
                preconditionFailure("Could not seed isolated content outline fixture: \(error)")
            }
        }
        if arguments.contains("--uitesting-note-video-roundtrip"),
           let index = arguments.firstIndex(of: "--video-roundtrip-fixture-id"),
           arguments.indices.contains(index + 1), let directory {
            let fixtureID = arguments[index + 1]
            let note = NoteDocument(id: "roundtrip-note-\(fixtureID)", title: "视频回链测试笔记",
                updatedAt: Date().timeIntervalSince1970 * 1000)
            do {
                try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
                try note.encoded().write(to: directory.appendingPathComponent("\(note.id).json"), options: .atomic)
            } catch {
                // The UI fixture will report its missing source through the ordinary flow.
            }
        }
#endif
        _library = StateObject(wrappedValue: NoteLibrary(directory: directory))
    }

    var body: some Scene {
        WindowGroup {
            Group {
#if DEBUG
                if ProcessInfo.processInfo.arguments.contains("--uitesting-agent-settings-layout") {
                    if let agentSettingsLayoutFixture {
                        AgentSettingsLayoutUITestHost(store: agentSettingsLayoutFixture.store)
                    } else if ProcessInfo.processInfo.arguments.contains("--agent-settings-layout-cleanup") {
                        AgentSettingsLayoutCleanupHost()
                    } else if ProcessInfo.processInfo.arguments.contains("--agent-settings-layout-inspect-default") {
                        Text(agentSettingsLayoutMaintenanceResult ?? "default-profile=unavailable")
                            .accessibilityIdentifier("agentSettingsLayoutDefaultInspection")
                    } else {
                        Text(agentSettingsLayoutMaintenanceResult ?? "agent-settings-layout-fixture-unavailable")
                            .accessibilityIdentifier("agentSettingsLayoutCleanupResult")
                    }
                } else if ProcessInfo.processInfo.arguments.contains("--uitesting-agent-task-history") {
                    if let agentTaskHistoryFixture {
                        AgentTaskHistoryUITestHost(fixture: agentTaskHistoryFixture).environmentObject(library)
                    } else {
                        AgentTaskHistoryCleanupHost(initialResult: agentTaskHistoryMaintenanceResult ?? "pending")
                    }
                } else if ProcessInfo.processInfo.arguments.contains("--uitesting-credential-clear") {
                    if let credentialClearFixture {
                        CredentialClearUITestHost(fixture: credentialClearFixture)
                    } else {
                        Text(credentialClearMaintenanceResult ?? "credential-fixture-unavailable")
                            .accessibilityIdentifier("credentialClearCleanupResult")
                    }
                } else if ProcessInfo.processInfo.arguments.contains("--uitesting-content-outline") {
                    if ProcessInfo.processInfo.arguments.contains("--uitesting-content-outline-inspect") {
                        ContentOutlineFixtureMaintenanceHost(mode: .inspect)
                    } else if ProcessInfo.processInfo.arguments.contains("--uitesting-content-outline-cleanup") {
                        ContentOutlineFixtureMaintenanceHost(mode: .cleanup)
                    } else {
                        ContentOutlineUITestHost().environmentObject(library)
                    }
                } else if ProcessInfo.processInfo.arguments.contains("--uitesting-agent-followup") {
                    AgentTaskUITestHost().environmentObject(library)
                } else if ProcessInfo.processInfo.arguments.contains("--uitesting-note-video-roundtrip") {
                    AgentVideoRoundTripUITestHost().environmentObject(library)
                } else if ProcessInfo.processInfo.arguments.contains("--uitesting-agent-video") {
                    AgentVideoUITestHost().environmentObject(library)
                } else {
                    BookshelfView()
                        .environmentObject(library)
                }
#else
                BookshelfView()
                    .environmentObject(library)
#endif
            }
                .tint(PadTheme.accent)
                .preferredColorScheme(.light)
        }
    }
}

#if DEBUG
private struct ContentOutlineUITestHost: View {
    @EnvironmentObject private var library: NoteLibrary

    var body: some View {
        if let note = library.notes.first {
                NoteEditorView(note: note,
                videoConnectionStore: AgentConnectionStore(
                    defaults: UserDefaults(suiteName: ContentOutlineUITestPaths.suiteName)!,
                    keychain: ContentOutlineUITestTokenStore()),
                videoTaskStore: AgentTaskStore(fileURL: LibraryBackupUITestPaths.root!
                    .appendingPathComponent("task-journal", isDirectory: true)))
                .environmentObject(library)
        } else {
            Text("大纲测试笔记未找到").accessibilityIdentifier("contentOutlineFixtureMissing")
        }
    }
}
#endif

#if DEBUG
private enum ContentOutlineUITestPaths {
    static var fixtureID: String {
        let arguments = ProcessInfo.processInfo.arguments
        guard let index = arguments.firstIndex(of: "--content-outline-fixture-id"), arguments.indices.contains(index + 1) else {
            preconditionFailure("Content outline UI tests require an explicit valid fixture UUID")
        }
        return arguments[index + 1].lowercased()
    }

    static var suiteName: String { "PadNote.ContentOutline.UITest.\(fixtureID)" }

    static func exportedMarkdown() -> Data? {
        guard let root = LibraryBackupUITestPaths.root else { return nil }
        let directory = root.appendingPathComponent("outline-exports", isDirectory: true)
        guard let files = try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: [.isRegularFileKey, .fileSizeKey]),
              let url = files.filter({ $0.lastPathComponent.range(of: "^PadNote-Outline-[0-9A-Fa-f-]{36}\\.md$", options: .regularExpression) != nil })
                .sorted(by: { $0.lastPathComponent < $1.lastPathComponent }).last,
              let values = try? url.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey]),
              values.isRegularFile == true, let size = values.fileSize, size >= 0, size <= 1_048_576 else { return nil }
        return try? Data(contentsOf: url, options: .mappedIfSafe)
    }

    static func cleanup() -> (rootExists: Bool, suiteKeyCount: Int) {
        guard let root = LibraryBackupUITestPaths.root else { return (true, -1) }
        try? FileManager.default.removeItem(at: root)
        UserDefaults(suiteName: suiteName)?.removePersistentDomain(forName: suiteName)
        let exists = FileManager.default.fileExists(atPath: root.path)
        let keys = UserDefaults(suiteName: suiteName)?.persistentDomain(forName: suiteName)?.count ?? 0
        return (exists, keys)
    }
}

private struct ContentOutlineFixtureMaintenanceHost: View {
    enum Mode: Equatable { case inspect, cleanup }
    let mode: Mode
    @State private var exportBase64 = ""
    @State private var cleanupResult = "not-cleaned"

    var body: some View {
        VStack {
            if mode == .inspect {
                Text(exportBase64).accessibilityIdentifier("contentOutlineFixtureMarkdownBase64")
                Text("内容大纲分享文件")
            } else {
                Button("清理 fixture") {
                    let result = ContentOutlineUITestPaths.cleanup()
                    cleanupResult = "fixture-cleaned; root-exists=\(result.rootExists); suite-keys=\(result.suiteKeyCount)"
                }.accessibilityIdentifier("contentOutlineFixtureCleanup")
                Text(cleanupResult).accessibilityIdentifier("contentOutlineFixtureCleanupResult")
            }
        }
        .onAppear {
            if mode == .inspect {
                exportBase64 = ContentOutlineUITestPaths.exportedMarkdown()?.base64EncodedString() ?? ""
            }
        }
    }
}

private final class ContentOutlineUITestTokenStore: AgentTokenStore {
    private let lock = NSLock()
    private var values = [String: String]()
    func save(_ value: String, reference: String) throws { lock.lock(); defer { lock.unlock() }; values[reference] = value }
    func read(reference: String) throws -> String? { lock.lock(); defer { lock.unlock() }; return values[reference] }
    func delete(reference: String) { lock.lock(); defer { lock.unlock() }; values.removeValue(forKey: reference) }
}
#endif


#if DEBUG
private final class CredentialClearMemorySecrets: SecretStore {
    private var values: [String: String] = [:]
    func read(reference: String) throws -> String? { values[reference] }
    func readForCleanup(reference: String) throws -> String? { values[reference] }
    func write(_ value: String, reference: String) throws { values[reference] = value }
    func delete(reference: String) throws { values.removeValue(forKey: reference) }
}

private final class CredentialClearMemoryAgentTokens: AgentTokenStore {
    private var values: [String: String] = [:]
    func save(_ value: String, reference: String) throws { values[reference] = value }
    func read(reference: String) throws -> String? { values[reference] }
    func readForCleanup(reference: String) throws -> String? { values[reference] }
    func delete(reference: String) { values.removeValue(forKey: reference) }
}

private enum CredentialClearUITestPaths {
    static let suitePrefix = "PadNote.CredentialClear.UITest."
    static var fixtureID: String? {
        let arguments = ProcessInfo.processInfo.arguments
        guard let index = arguments.firstIndex(of: "--credential-clear-fixture-id"),
              arguments.indices.contains(index + 1),
              let id = UUID(uuidString: arguments[index + 1]) else { return nil }
        return id.uuidString.lowercased()
    }
    static var suiteName: String? { fixtureID.map { suitePrefix + $0 } }
    static var root: URL? {
        guard let fixtureID else { return nil }
        return FileManager.default.temporaryDirectory
            .appendingPathComponent("PadNoteCredentialClearUITests", isDirectory: true)
            .appendingPathComponent(fixtureID, isDirectory: true)
    }
    static func cleanup() -> String {
        guard let fixtureID, let root, let suiteName else { return "credential-fixture-invalid-id" }
        let parent = FileManager.default.temporaryDirectory
            .appendingPathComponent("PadNoteCredentialClearUITests", isDirectory: true)
        guard root.deletingLastPathComponent().standardizedFileURL == parent.standardizedFileURL,
              root.lastPathComponent == fixtureID else { return "credential-fixture-path-rejected" }
        try? FileManager.default.removeItem(at: root)
        UserDefaults(suiteName: suiteName)?.removePersistentDomain(forName: suiteName)
        let rootExists = FileManager.default.fileExists(atPath: root.path)
        let keyCount = UserDefaults(suiteName: suiteName)?.persistentDomain(forName: suiteName)?.count ?? 0
        return "fixture-cleaned; root-exists=\(rootExists); suite-keys=\(keyCount)"
    }
}

private final class CredentialClearUITestFixture {
    let runtime: CredentialLifecycleRuntime
    let aiStore: AISettingsStore
    let aiSecrets: CredentialClearMemorySecrets
    let agentStore: AgentConnectionStore
    private let agentTokens: CredentialClearMemoryAgentTokens
    private let defaults: UserDefaults
    private let journalURL: URL
    private var simulatedNextProcessRuntime: CredentialLifecycleRuntime?
    private let aiSecretReferences: [String]
    private let agentTokenReference: String

    private init(runtime: CredentialLifecycleRuntime, journalURL: URL,
                 defaults: UserDefaults,
                 aiStore: AISettingsStore, aiSecrets: CredentialClearMemorySecrets,
                 agentStore: AgentConnectionStore, agentTokens: CredentialClearMemoryAgentTokens,
                 aiSecretReferences: [String], agentTokenReference: String) {
        self.runtime = runtime; self.defaults = defaults; self.aiStore = aiStore
        self.aiSecrets = aiSecrets; self.agentStore = agentStore; self.agentTokens = agentTokens
        self.aiSecretReferences = aiSecretReferences; self.agentTokenReference = agentTokenReference
        self.journalURL = journalURL
    }

    static func make() -> CredentialClearUITestFixture {
        guard let id = CredentialClearUITestPaths.fixtureID,
              let suite = CredentialClearUITestPaths.suiteName,
              let root = CredentialClearUITestPaths.root else {
            preconditionFailure("Credential clear UI tests require a valid fixture UUID")
        }
        let manager = FileManager.default
        do {
            try manager.createDirectory(at: root.deletingLastPathComponent(), withIntermediateDirectories: true,
                                        attributes: [.posixPermissions: 0o700])
            try manager.createDirectory(at: root, withIntermediateDirectories: false,
                                        attributes: [.posixPermissions: 0o700])
        } catch {
            preconditionFailure("Credential clear fixture root already exists or cannot be created")
        }
        guard let defaults = UserDefaults(suiteName: suite) else {
            preconditionFailure("Could not create credential clear fixture defaults suite")
        }
        let aiSecrets = CredentialClearMemorySecrets()
        let agentTokens = CredentialClearMemoryAgentTokens()
        let runtime = CredentialLifecycleRuntime(
            journalURL: root.appendingPathComponent("credential-clear-journal.json"),
            processGeneration: id,
            participants: [
                AISettingsStore.credentialLifecycleParticipant(defaults: defaults, secrets: aiSecrets),
                AgentConnectionStore.credentialLifecycleParticipant(defaults: defaults, keychain: agentTokens)
            ])
        _ = runtime.bootstrapBeforeStores()
        let aiStore = AISettingsStore(defaults: defaults, secretStore: aiSecrets,
                                      processGeneration: id, lifecycleRuntime: runtime)
        guard var aiProfile = aiStore.activeProfile else {
            preconditionFailure("Credential clear fixture has no AI profile")
        }
        aiProfile.name = "Fixture AI Profile"
        aiProfile.provider = .custom
        aiProfile.visionEndpoint = "https://fixture.invalid/v1"
        aiProfile.visionModel = "fixture-vision-model"
        aiProfile.textEndpoint = "https://fixture.invalid/v1"
        aiProfile.textModel = "fixture-text-model"
        do { try aiStore.upsert(aiProfile, visionToken: "synthetic-ai-token", textToken: nil) }
        catch { preconditionFailure("Could not seed synthetic AI credential") }
        let persistedAI = aiStore.activeProfile ?? aiProfile
        let aiRefs = [persistedAI.visionKeyReference, persistedAI.textKeyReference]
        let agentStore = AgentConnectionStore(defaults: defaults, keychain: agentTokens,
                                              processIdentifier: id, lifecycleRuntime: runtime)
        let agentProfile: AgentConnectionProfile
        do {
            agentProfile = try agentStore.create(name: "Fixture Agent Profile", kind: .hermes,
                endpoint: "https://credential-clear-fixture.invalid/v1", token: "synthetic-agent-token")
        } catch { preconditionFailure("Could not seed synthetic Agent credential") }
        return CredentialClearUITestFixture(runtime: runtime,
            journalURL: root.appendingPathComponent("credential-clear-journal.json"), defaults: defaults,
            aiStore: aiStore, aiSecrets: aiSecrets, agentStore: agentStore,
            agentTokens: agentTokens, aiSecretReferences: aiRefs,
            agentTokenReference: agentProfile.credentialReference)
    }

    func simulateNextProcessCleanup() {
        let next = CredentialLifecycleRuntime(
            journalURL: journalURL,
            processGeneration: UUID().uuidString.lowercased(),
            participants: [
                AISettingsStore.credentialLifecycleParticipant(defaults: defaults, secrets: aiSecrets),
                AgentConnectionStore.credentialLifecycleParticipant(defaults: defaults, keychain: agentTokens)
            ])
        _ = next.bootstrapBeforeStores()
        simulatedNextProcessRuntime = next
    }

    var snapshot: String {
        let ai = aiStore.activeProfile
        let agent = agentStore.profiles().first
        let aiPresent = aiSecretReferences.contains { (try? aiSecrets.readForCleanup(reference: $0)) != nil }
        let agentPresent = (try? agentTokens.readForCleanup(reference: agentTokenReference)) != nil
        let aiReferenceDisabled = ai?.visionKeyReference.hasPrefix("padnote-disabled://") == true
        let agentReferenceDisabled = agent?.credentialReference.hasPrefix("padnote-disabled://") == true
        let agentReadable = agent.flatMap { agentStore.token(for: $0.id) } != nil
        let aiReadable: Bool = {
            guard let reference = ai?.visionKeyReference else { return false }
            return (try? runtime.readCredential(side: .ai, reference: reference,
                rawRead: { try aiSecrets.readForCleanup(reference: reference) })) != nil
        }()
        let currentState = simulatedNextProcessRuntime?.currentState ?? runtime.currentState
        let nextProcess = simulatedNextProcessRuntime == nil ? "not-run" : stateName(currentState)
        return "state=\(stateName(runtime.currentState)); next-process=\(nextProcess); ai=\(ai?.name ?? "missing")|\(ai?.visionEndpoint ?? "")|\(ai?.visionModel ?? "")|\(ai?.textModel ?? "")|disabled=\(aiReferenceDisabled)|readable=\(aiReadable)|raw=\(aiPresent ? "present" : "absent"); agent=\(agent?.name ?? "missing")|\(agent?.endpoint ?? "")|disabled=\(agentReferenceDisabled)|readable=\(agentReadable)|raw=\(agentPresent ? "present" : "absent")"
    }

    private func stateName(_ state: CredentialLifecycleState) -> String {
        switch state {
        case .notReady: return "notReady"
        case .ready: return "ready"
        case .blocked: return "blocked"
        case .disabledAwaitingNextProcess: return "disabledAwaitingNextProcess"
        case .cleanupRetryRequired: return "cleanupRetryRequired"
        case .completeKnownReferences: return "completeKnownReferences"
        }
    }
}

private struct CredentialClearUITestHost: View {
    let fixture: CredentialClearUITestFixture
    @ObservedObject private var aiStore: AISettingsStore
    @State private var refresh = false

    init(fixture: CredentialClearUITestFixture) {
        self.fixture = fixture
        _aiStore = ObservedObject(wrappedValue: fixture.aiStore)
    }

    var body: some View {
        let _ = aiStore.profiles
        let _ = refresh
        return NavigationStack {
            List {
                Section("本机凭据验收") {
                    NavigationLink("AI 设置") {
                        AISettingsView(store: fixture.aiStore, secrets: fixture.aiSecrets)
                    }.accessibilityIdentifier("credentialClearAISettings")
                    NavigationLink("电脑 Agent") {
                        AgentSettingsView(store: fixture.agentStore)
                    }.accessibilityIdentifier("credentialClearAgentSettings")
                    Button("刷新验收状态") { refresh.toggle() }
                        .accessibilityIdentifier("credentialClearRefreshFixtureSnapshot")
                    Text(fixture.snapshot).accessibilityIdentifier("credentialClearFixtureSnapshot")
                    if fixture.runtime.currentState == .disabledAwaitingNextProcess {
                        Button("模拟下次进程清理") {
                            fixture.simulateNextProcessCleanup()
                            refresh.toggle()
                        }.accessibilityIdentifier("credentialClearSimulateNextProcess")
                    }
                }
            }
            .navigationTitle("凭据验收夹具")
        }
        .onAppear { refresh.toggle() }
        .onReceive(NotificationCenter.default.publisher(for: CredentialLifecycleRuntime.stateDidChange,
                                                         object: fixture.runtime)) { _ in refresh.toggle() }
    }
}
#endif

#if DEBUG
private enum AgentSettingsLayoutUITestPaths {
    static var fixtureID: String {
        let arguments = ProcessInfo.processInfo.arguments
        guard let index = arguments.firstIndex(of: "--agent-settings-layout-fixture-id"),
              arguments.indices.contains(index + 1),
              let id = UUID(uuidString: arguments[index + 1]) else {
            preconditionFailure("Agent settings layout UI tests require an explicit fixture UUID")
        }
        return id.uuidString.lowercased()
    }

    static var suiteName: String { "PadNote.AgentSettingsLayout.UITest.\(fixtureID)" }
    static var root: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("UITestLibrary/AgentSettingsLayout", isDirectory: true)
            .appendingPathComponent(fixtureID, isDirectory: true)
    }

    static func cleanup() -> String {
        let suite = suiteName
        try? FileManager.default.removeItem(at: root)
        UserDefaults(suiteName: suite)?.removePersistentDomain(forName: suite)
        let rootExists = FileManager.default.fileExists(atPath: root.path)
        let keys = UserDefaults(suiteName: suite)?.persistentDomain(forName: suite)?.count ?? 0
        return "fixture-cleaned; root-exists=\(rootExists); suite-keys=\(keys)"
    }

    static func inspectDefault() -> String {
        let store = AgentConnectionStore(
            defaults: UserDefaults(suiteName: AgentSettingsLayoutUITestPaths.suiteName)!,
            keychain: AgentSettingsLayoutUITestTokenStore())
        guard let id = store.defaultProfileID(), let profile = store.profile(id: id) else {
            return "default-profile=missing"
        }
        return "default-profile=\(profile.endpoint)"
    }
}

private final class AgentSettingsLayoutUITestTokenStore: AgentTokenStore {
    private let lock = NSLock()
    private var values = [String: String]()

    func save(_ value: String, reference: String) throws {
        lock.lock(); defer { lock.unlock() }
        values[reference] = value
    }

    func read(reference: String) throws -> String? {
        lock.lock(); defer { lock.unlock() }
        return values[reference]
    }

    func readForCleanup(reference: String) throws -> String? { try read(reference: reference) }

    func delete(reference: String) {
        lock.lock(); defer { lock.unlock() }
        values.removeValue(forKey: reference)
    }
}

private struct AgentSettingsLayoutUITestFixture {
    let store: AgentConnectionStore

    static func make() -> AgentSettingsLayoutUITestFixture {
        let defaults = UserDefaults(suiteName: AgentSettingsLayoutUITestPaths.suiteName)!
        let tokens = AgentSettingsLayoutUITestTokenStore()
        let store = AgentConnectionStore(defaults: defaults, keychain: tokens)
        let sharedName = "实验室视频连接专用工作站和团队协作助手"
        let video = try! store.create(name: sharedName, kind: .builtinVideo,
            endpoint: "https://alpha-workstation-with-a-very-long-name.fixture.invalid:18443",
            token: "synthetic-layout-token-video", transport: .bridge,
            bridgeID: "layout-bridge-VIDEOA1", instanceID: "layout-instance-VIDEO042")
        let hermes = try! store.create(name: sharedName, kind: .hermes,
            endpoint: "https://bravo-render-node-with-a-very-long-name.fixture.invalid:29443",
            token: "synthetic-layout-token-hermes", transport: .bridge,
            bridgeID: "layout-bridge-HERMES2", instanceID: "layout-instance-HERMES09", makeDefault: false)
        _ = store.applyProbeSuccess(id: video.id, revision: video.revision,
            capabilities: ["task_bundle": true, "video_task_submission": true, "video_production": false])
        _ = store.applyProbeSuccess(id: hermes.id, revision: hermes.revision,
            capabilities: ["run_submission": true, "run_status": true, "run_stop": false])
        return AgentSettingsLayoutUITestFixture(store: store)
    }
}

private struct AgentSettingsLayoutUITestHost: View {
    let store: AgentConnectionStore
    var body: some View { AgentSettingsView(store: store) }
}

private struct AgentSettingsLayoutCleanupHost: View {
    @State private var result = "cleanup-pending"
    var body: some View {
        Text(result).accessibilityIdentifier("agentSettingsLayoutCleanupResult")
            .onAppear { result = AgentSettingsLayoutUITestPaths.cleanup() }
    }
}

private enum AgentTaskHistoryUITestPaths {
    static var fixtureID: String {
        let arguments = ProcessInfo.processInfo.arguments
        guard let index = arguments.firstIndex(of: "--agent-task-history-fixture-id"),
              arguments.indices.contains(index + 1),
              let id = UUID(uuidString: arguments[index + 1]) else {
            preconditionFailure("Agent task history UI tests require an explicit fixture UUID")
        }
        return id.uuidString.lowercased()
    }

    static var suiteName: String { "PadNote.AgentTaskHistory.UITest.\(fixtureID)" }
    static var root: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("UITestLibrary/AgentTaskHistory", isDirectory: true)
            .appendingPathComponent(fixtureID, isDirectory: true)
    }

    static func cleanup() -> String {
        let ownRoot = root
        let suite = suiteName
        let expectedParent = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("UITestLibrary/AgentTaskHistory", isDirectory: true).standardizedFileURL
        guard ownRoot.deletingLastPathComponent().standardizedFileURL == expectedParent,
              UUID(uuidString: ownRoot.lastPathComponent)?.uuidString.lowercased() == fixtureID else {
            return "fixture-cleanup-refused"
        }
        try? FileManager.default.removeItem(at: ownRoot)
        UserDefaults(suiteName: suite)?.removePersistentDomain(forName: suite)
        let rootExists = FileManager.default.fileExists(atPath: ownRoot.path)
        let keys = UserDefaults(suiteName: suite)?.persistentDomain(forName: suite)?.count ?? 0
        return "fixture-cleaned; root-exists=\(rootExists); suite-keys=\(keys)"
    }
}

private final class AgentTaskHistoryUITestTokenStore: AgentTokenStore {
    private let lock = NSLock()
    private var values = [String: String]()

    func save(_ value: String, reference: String) throws {
        lock.lock(); defer { lock.unlock() }
        values[reference] = value
    }
    func read(reference: String) throws -> String? {
        lock.lock(); defer { lock.unlock() }
        return values[reference]
    }
    func readForCleanup(reference: String) throws -> String? { try read(reference: reference) }
    func delete(reference: String) {
        lock.lock(); defer { lock.unlock() }
        values.removeValue(forKey: reference)
    }
}

private struct AgentTaskHistoryUITestFixture {
    let taskStore: AgentTaskStore
    let service: AgentTaskService

    static func make() -> AgentTaskHistoryUITestFixture {
        let defaults = UserDefaults(suiteName: AgentTaskHistoryUITestPaths.suiteName)!
        let tokenStore = AgentTaskHistoryUITestTokenStore()
        let connections = AgentConnectionStore(defaults: defaults, keychain: tokenStore)
        let taskStore = AgentTaskStore(fileURL: AgentTaskHistoryUITestPaths.root.appendingPathComponent("tasks", isDirectory: true))
        let service = AgentTaskService(connectionStore: connections, taskStore: taskStore)
        let sharedName = "同名的电脑助手连接"
        let profiles: [(AgentConnectionProfile, String, String)] = [
            (try! connections.create(name: sharedName, kind: .hermes,
                endpoint: "https://history-hermes-long-host.fixture.invalid:18443", token: "synthetic-history-hermes",
                transport: .bridge, bridgeID: "bridgeHERMES01", instanceID: "instanceHERMES01"),
             "a1111111-1111-4111-8111-111111111111", "history-body-hermes-a111"),
            (try! connections.create(name: sharedName, kind: .builtinVideo,
                endpoint: "https://history-video-long-host.fixture.invalid:29443", token: "synthetic-history-video",
                transport: .bridge, bridgeID: "bridgeVIDEO01", instanceID: "instanceVIDEO02", makeDefault: false),
             "b2222222-2222-4222-8222-222222222222", "history-body-video-b222"),
            (try! connections.create(name: sharedName, kind: .hermes,
                endpoint: "https://history-hermes-secondary-long-host.fixture.invalid:38080", token: "synthetic-history-hermes-2",
                transport: .bridge, bridgeID: "bridgeHERMES02", instanceID: "instanceHERMES99", makeDefault: false),
             "c3333333-3333-4333-8333-333333333333", "history-body-hermes-c333"),
            (try! connections.create(name: sharedName, kind: .hermes,
                endpoint: "https://history-legacy.fixture.invalid:443", token: "synthetic-history-legacy",
                transport: .bridge, makeDefault: false),
             "d4444444-4444-4444-8444-444444444444", "history-body-legacy-d444")
        ]
        for (profile, clientTaskID, marker) in profiles {
            let payload = try! AgentTaskPayload(
                clientTaskID: clientTaskID,
                title: "同名的已保存任务",
                input: marker,
                source: AgentTaskSource(noteID: "agent-history-ui-fixture", noteRevision: 1))
            let created = try! taskStore.create(profile: profile, payload: payload)
            _ = try! taskStore.mutate(id: created.id, expectedRevision: created.recordRevision) { task in
                task.status = .completed
                task.output = "合成历史结果：\(marker)"
            }
        }
        return AgentTaskHistoryUITestFixture(taskStore: taskStore, service: service)
    }
}

private struct AgentTaskHistoryUITestHost: View {
    let fixture: AgentTaskHistoryUITestFixture
    var body: some View { AgentTaskListView(store: fixture.taskStore, service: fixture.service) }
}

private struct AgentTaskHistoryCleanupHost: View {
    let initialResult: String
    @State private var result: String

    init(initialResult: String) {
        self.initialResult = initialResult
        _result = State(initialValue: initialResult)
    }

    var body: some View {
        Text(result).accessibilityIdentifier("agentTaskHistoryCleanupResult")
            .onAppear { result = AgentTaskHistoryUITestPaths.cleanup() }
    }
}
#endif
