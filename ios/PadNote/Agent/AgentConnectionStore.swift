import Foundation
import Security

public enum AgentKind: String, Codable, CaseIterable, Sendable {
    case hermes
    case openClaw
}

public enum AgentTransport: String, Codable, CaseIterable, Sendable {
    case direct
    case bridge
}

public struct AgentConnectionProfile: Codable, Equatable, Identifiable, Sendable {
    public var id: UUID
    public var name: String
    public var kind: AgentKind
    public var endpoint: String
    public var credentialReference: String
    public var transport: AgentTransport
    public var bridgeID: String?
    public var instanceID: String?
    public var revision: Int
    public var verifiedAt: Date?
    public var capabilities: [String: Bool]
    public var lastProbeError: String?

    public init(
        id: UUID = UUID(),
        name: String,
        kind: AgentKind = .hermes,
        endpoint: String,
        credentialReference: String? = nil,
        transport: AgentTransport = .direct,
        bridgeID: String? = nil,
        instanceID: String? = nil,
        revision: Int = 1,
        verifiedAt: Date? = nil,
        capabilities: [String: Bool] = [:],
        lastProbeError: String? = nil
    ) {
        self.id = id
        self.name = name
        self.kind = kind
        self.endpoint = endpoint
        self.credentialReference = credentialReference ?? Self.credentialReference(for: id)
        self.transport = transport
        self.bridgeID = bridgeID
        self.instanceID = instanceID
        self.revision = max(1, revision)
        self.verifiedAt = verifiedAt
        self.capabilities = capabilities
        self.lastProbeError = lastProbeError
    }

    public var connected: Bool { verifiedAt != nil }
    public var enabledCapabilities: [String] {
        capabilities.compactMap { $0.value ? $0.key : nil }.sorted()
    }

    public static func credentialReference(for id: UUID) -> String {
        "connection.\(id.uuidString.lowercased())"
    }
}

/// Compatibility shape for call sites that still consume the default connection.
public struct AgentConnectionConfig: Equatable, Sendable {
    public let id: UUID?
    public let name: String
    public let kind: AgentKind
    public let endpoint: String
    public let token: String
    public let connected: Bool
    public let transport: AgentTransport
    public let bridgeID: String?
    public let instanceID: String?
    public let revision: Int
    public let capabilities: [String: Bool]

    public init(
        id: UUID? = nil,
        name: String = "",
        kind: AgentKind = .hermes,
        endpoint: String = "",
        token: String = "",
        connected: Bool = false,
        transport: AgentTransport = .direct,
        bridgeID: String? = nil,
        instanceID: String? = nil,
        revision: Int = 0,
        capabilities: [String: Bool] = [:]
    ) {
        self.id = id
        self.name = name
        self.kind = kind
        self.endpoint = endpoint
        self.token = token
        self.connected = connected
        self.transport = transport
        self.bridgeID = bridgeID
        self.instanceID = instanceID
        self.revision = revision
        self.capabilities = capabilities
    }

    public init(profile: AgentConnectionProfile, token: String) {
        self.init(
            id: profile.id,
            name: profile.name,
            kind: profile.kind,
            endpoint: profile.endpoint,
            token: token,
            connected: profile.connected,
            transport: profile.transport,
            bridgeID: profile.bridgeID,
            instanceID: profile.instanceID,
            revision: profile.revision,
            capabilities: profile.capabilities
        )
    }

    public var complete: Bool {
        !endpoint.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !token.isEmpty
    }
}

public struct AgentProbeSnapshot: Equatable, Sendable {
    public let id: UUID
    public let revision: Int
    public let config: AgentConnectionConfig
}

public protocol AgentTokenStore: AnyObject {
    func save(_ value: String, reference: String) throws
    func read(reference: String) throws -> String?
    func delete(reference: String)
}

public enum AgentStoreError: Error, LocalizedError, Equatable {
    case keychain
    case profileNotFound
    case staleRevision
    case credentialRequired
    case invalidProfile
    case corruptStore
    case persistence

    public var errorDescription: String? {
        switch self {
        case .keychain: return "无法保存 Agent 连接令牌"
        case .profileNotFound: return "连接已被删除"
        case .staleRevision: return "连接已更新，请重新操作"
        case .credentialRequired: return "修改地址后需要重新填写连接令牌"
        case .invalidProfile: return "Agent 连接信息不完整"
        case .corruptStore: return "Agent 连接记录损坏，原记录已保留，请先恢复或导出诊断"
        case .persistence: return "无法持久保存 Agent 连接"
        }
    }
}

public final class AgentConnectionStore: @unchecked Sendable {
    private struct Payload: Codable {
        var schemaVersion: Int
        var defaultProfileID: UUID?
        var profiles: [AgentConnectionProfile]
    }

    private struct CredentialGarbage: Codable, Equatable {
        let reference: String
        let enqueuedByProcess: String
    }

    private static let mutationLock = NSLock()
    private static let schemaVersion = 1
    private static let payloadKey = "padnote.agent.connections.v1"
    private static let credentialGarbageKey = "padnote.agent.credentials.gc.v1"
    private static let processEpoch = UUID().uuidString.lowercased()
    private static let legacyKindKey = "padnote.agent.kind"
    private static let legacyEndpointKey = "padnote.agent.endpoint"
    private static let legacyConnectedKey = "padnote.agent.connected"
    private static let legacyCredentialReference = "token"
    private let defaults: UserDefaults
    private let keychain: AgentTokenStore
    private let payloadWriter: ((UserDefaults, String, Data) -> Bool)?
    private let processIdentifier: String

    public init(
        defaults: UserDefaults = .standard,
        keychain: AgentTokenStore = KeychainTokenStore(),
        payloadWriter: ((UserDefaults, String, Data) -> Bool)? = nil,
        processIdentifier: String? = nil
    ) {
        self.defaults = defaults
        self.keychain = keychain
        self.payloadWriter = payloadWriter
        self.processIdentifier = processIdentifier ?? Self.processEpoch
        migrateLegacyIfNeeded()
        collectCredentialGarbageFromPreviousProcesses()
    }

    public func profiles() -> [AgentConnectionProfile] {
        Self.mutationLock.withLock { (try? readPayload())?.profiles ?? [] }
    }

    public func defaultProfileID() -> UUID? {
        Self.mutationLock.withLock { (try? readPayload())?.defaultProfileID }
    }

    public func profile(id: UUID) -> AgentConnectionProfile? {
        Self.mutationLock.withLock { (try? readPayload())?.profiles.first { $0.id == id } }
    }

    public func token(for id: UUID) -> String? {
        Self.mutationLock.withLock {
            guard let profile = (try? readPayload())?.profiles.first(where: { $0.id == id }) else { return nil }
            return try? keychain.read(reference: profile.credentialReference)
        }
    }

    public func token(reference: String) -> String? {
        Self.mutationLock.withLock { try? keychain.read(reference: reference) }
    }

    public func load() -> AgentConnectionConfig {
        Self.mutationLock.withLock {
            guard let payload = try? readPayload() else { return AgentConnectionConfig() }
            guard let profile = selectedProfile(in: payload) else { return AgentConnectionConfig() }
            return AgentConnectionConfig(profile: profile, token: (try? keychain.read(reference: profile.credentialReference)) ?? "")
        }
    }

    public func snapshotForProbe(id: UUID) throws -> AgentProbeSnapshot {
        try Self.mutationLock.withLock {
            guard let profile = try readPayload().profiles.first(where: { $0.id == id }) else { throw AgentStoreError.profileNotFound }
            let token = try keychain.read(reference: profile.credentialReference) ?? ""
            return AgentProbeSnapshot(id: id, revision: profile.revision, config: AgentConnectionConfig(profile: profile, token: token))
        }
    }

    @discardableResult
    public func create(
        name: String,
        kind: AgentKind,
        endpoint: String,
        token: String,
        transport: AgentTransport = .direct,
        bridgeID: String? = nil,
        instanceID: String? = nil,
        makeDefault: Bool = true
    ) throws -> AgentConnectionProfile {
        let normalizedName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let normalizedEndpoint = Self.normalize(endpoint)
        guard !normalizedName.isEmpty, !normalizedEndpoint.isEmpty, !token.isEmpty,
              Self.isValidEndpoint(normalizedEndpoint) else { throw AgentStoreError.invalidProfile }
        return try Self.mutationLock.withLock {
            var payload = try readPayload()
            let profile = AgentConnectionProfile(
                name: normalizedName,
                kind: kind,
                endpoint: normalizedEndpoint,
                transport: transport,
                bridgeID: bridgeID,
                instanceID: instanceID
            )
            try keychain.save(token, reference: profile.credentialReference)
            guard try keychain.read(reference: profile.credentialReference) == token else {
                keychain.delete(reference: profile.credentialReference)
                throw AgentStoreError.keychain
            }
            payload.profiles.append(profile)
            if makeDefault || payload.defaultProfileID == nil { payload.defaultProfileID = profile.id }
            do { try writePayload(payload) }
            catch { keychain.delete(reference: profile.credentialReference); throw error }
            return profile
        }
    }

    @discardableResult
    public func update(
        id: UUID,
        expectedRevision: Int,
        name: String,
        kind: AgentKind,
        endpoint: String,
        token: String?,
        transport: AgentTransport,
        bridgeID: String? = nil,
        instanceID: String? = nil
    ) throws -> AgentConnectionProfile {
        let normalizedName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let normalizedEndpoint = Self.normalize(endpoint)
        guard !normalizedName.isEmpty, !normalizedEndpoint.isEmpty,
              Self.isValidEndpoint(normalizedEndpoint) else { throw AgentStoreError.invalidProfile }
        return try Self.mutationLock.withLock {
            var payload = try readPayload()
            guard let index = payload.profiles.firstIndex(where: { $0.id == id }) else { throw AgentStoreError.profileNotFound }
            let previous = payload.profiles[index]
            guard previous.revision == expectedRevision else { throw AgentStoreError.staleRevision }
            let endpointChanged = previous.endpoint != normalizedEndpoint
            let suppliedToken = token?.isEmpty == false ? token : nil
            let identityChanged = previous.kind != kind
                || endpointChanged
                || previous.transport != transport
                || previous.bridgeID != bridgeID
                || previous.instanceID != instanceID
                || suppliedToken != nil
            if identityChanged && suppliedToken == nil { throw AgentStoreError.credentialRequired }
            let stagedReference = identityChanged
                ? "\(AgentConnectionProfile.credentialReference(for: id)).r\(previous.revision + 1).\(UUID().uuidString.lowercased())"
                : previous.credentialReference
            if let suppliedToken {
                try keychain.save(suppliedToken, reference: stagedReference)
                guard try keychain.read(reference: stagedReference) == suppliedToken else {
                    keychain.delete(reference: stagedReference)
                    throw AgentStoreError.keychain
                }
            }
            var updated = previous
            updated.name = normalizedName
            updated.kind = kind
            updated.endpoint = normalizedEndpoint
            updated.transport = transport
            updated.bridgeID = bridgeID
            updated.instanceID = instanceID
            if identityChanged {
                updated.credentialReference = stagedReference
                updated.revision += 1
                updated.verifiedAt = nil
                updated.capabilities = [:]
                updated.lastProbeError = nil
            }
            payload.profiles[index] = updated
            do { try writePayload(payload) }
            catch {
                if stagedReference != previous.credentialReference { keychain.delete(reference: stagedReference) }
                throw error
            }
            if stagedReference != previous.credentialReference {
                enqueueCredentialCleanup(previous.credentialReference)
            }
            return updated
        }
    }

    public func setDefault(id: UUID) throws {
        try Self.mutationLock.withLock {
            var payload = try readPayload()
            guard payload.profiles.contains(where: { $0.id == id }) else { throw AgentStoreError.profileNotFound }
            payload.defaultProfileID = id
            try writePayload(payload)
        }
    }

    public func delete(id: UUID) {
        Self.mutationLock.withLock {
            guard var payload = try? readPayload() else { return }
            guard let index = payload.profiles.firstIndex(where: { $0.id == id }) else { return }
            let removed = payload.profiles.remove(at: index)
            if payload.defaultProfileID == id { payload.defaultProfileID = payload.profiles.first?.id }
            if (try? writePayload(payload)) != nil { enqueueCredentialCleanup(removed.credentialReference) }
        }
    }

    @discardableResult
    public func applyProbeSuccess(id: UUID, revision: Int, capabilities: [String: Bool], verifiedAt: Date = Date()) -> Bool {
        Self.mutationLock.withLock {
            guard var payload = try? readPayload() else { return false }
            guard let index = payload.profiles.firstIndex(where: { $0.id == id && $0.revision == revision }) else { return false }
            payload.profiles[index].verifiedAt = verifiedAt
            payload.profiles[index].capabilities = capabilities
            payload.profiles[index].lastProbeError = nil
            return (try? writePayload(payload)) != nil
        }
    }

    @discardableResult
    public func applyProbeFailure(id: UUID, revision: Int, message: String) -> Bool {
        Self.mutationLock.withLock {
            guard var payload = try? readPayload() else { return false }
            guard let index = payload.profiles.firstIndex(where: { $0.id == id && $0.revision == revision }) else { return false }
            payload.profiles[index].verifiedAt = nil
            payload.profiles[index].capabilities = [:]
            payload.profiles[index].lastProbeError = String(message.prefix(500))
            return (try? writePayload(payload)) != nil
        }
    }

    // MARK: Compatibility API

    public func save(kind: AgentKind, endpoint: String, token: String) throws {
        if let current = profiles().first(where: { $0.id == defaultProfileID() }) {
            _ = try update(
                id: current.id,
                expectedRevision: current.revision,
                name: current.name.isEmpty ? Self.defaultName(kind: kind, endpoint: endpoint) : current.name,
                kind: kind,
                endpoint: endpoint,
                token: token,
                transport: .direct
            )
        } else {
            _ = try create(name: Self.defaultName(kind: kind, endpoint: endpoint), kind: kind, endpoint: endpoint, token: token)
        }
    }

    public func setConnected(_ value: Bool) {
        guard let profile = profiles().first(where: { $0.id == defaultProfileID() }) else { return }
        if value {
            _ = applyProbeSuccess(id: profile.id, revision: profile.revision, capabilities: profile.capabilities)
        } else {
            _ = applyProbeFailure(id: profile.id, revision: profile.revision, message: "")
        }
    }

    public func clear() {
        guard let id = defaultProfileID() else { return }
        delete(id: id)
    }

    private func migrateLegacyIfNeeded() {
        Self.mutationLock.withLock {
            guard defaults.data(forKey: Self.payloadKey) == nil else { return }
            let endpoint = Self.normalize(defaults.string(forKey: Self.legacyEndpointKey) ?? "")
            let legacyToken: String
            do { legacyToken = try keychain.read(reference: Self.legacyCredentialReference) ?? "" }
            catch { return }
            let hasLegacyDefaults = defaults.object(forKey: Self.legacyKindKey) != nil
                || defaults.object(forKey: Self.legacyEndpointKey) != nil
                || defaults.object(forKey: Self.legacyConnectedKey) != nil
            guard hasLegacyDefaults || !legacyToken.isEmpty else {
                try? writePayload(Payload(schemaVersion: Self.schemaVersion, defaultProfileID: nil, profiles: []))
                return
            }
            let kind = AgentKind(rawValue: defaults.string(forKey: Self.legacyKindKey) ?? "hermes") ?? .hermes
            let id = UUID()
            let reference = AgentConnectionProfile.credentialReference(for: id)
            do {
                if !legacyToken.isEmpty { try keychain.save(legacyToken, reference: reference) }
                if !legacyToken.isEmpty,
                   try keychain.read(reference: reference) != legacyToken { throw AgentStoreError.keychain }
                let wasConnected = defaults.bool(forKey: Self.legacyConnectedKey)
                let profile = AgentConnectionProfile(
                    id: id,
                    name: Self.defaultName(kind: kind, endpoint: endpoint),
                    kind: kind,
                    endpoint: endpoint,
                    credentialReference: reference,
                    revision: 1,
                    verifiedAt: wasConnected ? Date() : nil,
                    capabilities: [:]
                )
                try writePayload(Payload(schemaVersion: Self.schemaVersion, defaultProfileID: id, profiles: [profile]))
                // Keep the legacy record as a rollback source. Presence of the v1 payload makes migration idempotent.
            } catch {
                keychain.delete(reference: reference)
            }
        }
    }

    private func readPayload() throws -> Payload {
        guard let data = defaults.data(forKey: Self.payloadKey) else {
            return Payload(schemaVersion: Self.schemaVersion, defaultProfileID: nil, profiles: [])
        }
        guard let payload = try? JSONDecoder().decode(Payload.self, from: data),
              payload.schemaVersion == Self.schemaVersion else { throw AgentStoreError.corruptStore }
        var repaired = payload
        if let defaultID = repaired.defaultProfileID,
           !repaired.profiles.contains(where: { $0.id == defaultID }) {
            repaired.defaultProfileID = repaired.profiles.first?.id
        }
        return repaired
    }

    private func writePayload(_ payload: Payload) throws {
        let data = try JSONEncoder().encode(payload)
        if let payloadWriter {
            guard payloadWriter(defaults, Self.payloadKey, data) else { throw AgentStoreError.persistence }
        } else {
            defaults.set(data, forKey: Self.payloadKey)
        }
        guard defaults.data(forKey: Self.payloadKey) == data else { throw AgentStoreError.persistence }
    }

    /// Keychain deletion is delayed until a later process observes that the committed metadata
    /// no longer references the credential. This avoids deleting the only usable token if the
    /// process exits before UserDefaults has durably flushed an identity update or deletion.
    private func enqueueCredentialCleanup(_ reference: String) {
        guard !reference.isEmpty else { return }
        var entries = readCredentialGarbage()
        let entry = CredentialGarbage(reference: reference, enqueuedByProcess: processIdentifier)
        if !entries.contains(entry) { entries.append(entry) }
        writeCredentialGarbage(entries)
    }

    private func collectCredentialGarbageFromPreviousProcesses() {
        Self.mutationLock.withLock {
            guard let payload = try? readPayload() else { return }
            let active = Set(payload.profiles.map(\.credentialReference))
            let entries = readCredentialGarbage()
            var remaining = [CredentialGarbage]()
            for entry in entries {
                guard entry.enqueuedByProcess != processIdentifier, !active.contains(entry.reference) else {
                    remaining.append(entry)
                    continue
                }
                keychain.delete(reference: entry.reference)
                do {
                    if try keychain.read(reference: entry.reference) != nil { remaining.append(entry) }
                } catch {
                    remaining.append(entry)
                }
            }
            if remaining != entries { writeCredentialGarbage(remaining) }
        }
    }

    private func readCredentialGarbage() -> [CredentialGarbage] {
        guard let data = defaults.data(forKey: Self.credentialGarbageKey),
              let entries = try? JSONDecoder().decode([CredentialGarbage].self, from: data) else { return [] }
        return entries
    }

    private func writeCredentialGarbage(_ entries: [CredentialGarbage]) {
        guard let data = try? JSONEncoder().encode(entries) else { return }
        defaults.set(data, forKey: Self.credentialGarbageKey)
    }

    private func selectedProfile(in payload: Payload) -> AgentConnectionProfile? {
        if let id = payload.defaultProfileID,
           let selected = payload.profiles.first(where: { $0.id == id }) { return selected }
        return payload.profiles.first
    }

    private static func normalize(_ endpoint: String) -> String {
        var value = endpoint.trimmingCharacters(in: .whitespacesAndNewlines)
        while value.hasSuffix("/") { value.removeLast() }
        return value
    }

    private static func isValidEndpoint(_ endpoint: String) -> Bool {
        guard let components = URLComponents(string: endpoint),
              components.scheme?.lowercased() == "https",
              let host = components.host, !host.isEmpty,
              components.user == nil,
              components.password == nil,
              components.query == nil,
              components.fragment == nil else { return false }
        return true
    }

    private static func defaultName(kind: AgentKind, endpoint: String) -> String {
        let label = kind == .hermes ? "Hermes" : "OpenClaw"
        guard let host = URL(string: normalize(endpoint))?.host, !host.isEmpty else { return label }
        return "\(label) · \(host)"
    }
}

public final class KeychainTokenStore: AgentTokenStore, @unchecked Sendable {
    private let service: String
    public init(service: String = "com.padnote.agent") { self.service = service }

    public func save(_ value: String, reference: String) throws {
        let data = Data(value.utf8)
        let query: [CFString: Any] = [
            kSecClass: kSecClassGenericPassword,
            kSecAttrService: service,
            kSecAttrAccount: reference
        ]
        let status = SecItemUpdate(query as CFDictionary, [
            kSecValueData: data,
            kSecAttrAccessible: kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        ] as CFDictionary)
        if status == errSecItemNotFound {
            var item = query
            item[kSecValueData] = data
            item[kSecAttrAccessible] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
            guard SecItemAdd(item as CFDictionary, nil) == errSecSuccess else { throw AgentStoreError.keychain }
        } else if status != errSecSuccess {
            throw AgentStoreError.keychain
        }
    }

    public func read(reference: String) throws -> String? {
        let query: [CFString: Any] = [
            kSecClass: kSecClassGenericPassword,
            kSecAttrService: service,
            kSecAttrAccount: reference,
            kSecReturnData: true,
            kSecMatchLimit: kSecMatchLimitOne
        ]
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = result as? Data else { throw AgentStoreError.keychain }
        return String(data: data, encoding: .utf8)
    }

    public func delete(reference: String) {
        let query: [CFString: Any] = [
            kSecClass: kSecClassGenericPassword,
            kSecAttrService: service,
            kSecAttrAccount: reference
        ]
        SecItemDelete(query as CFDictionary)
    }
}

private extension NSLock {
    func withLock<T>(_ body: () throws -> T) rethrows -> T {
        lock()
        defer { unlock() }
        return try body()
    }
}
