import Foundation
import Security

public enum AgentKind: String, Codable, Sendable { case hermes, openClaw }

public struct AgentConnectionConfig: Equatable, Sendable {
    public let kind: AgentKind
    public let endpoint: String
    public let token: String
    public let connected: Bool

    public init(kind: AgentKind = .hermes, endpoint: String = "", token: String = "", connected: Bool = false) {
        self.kind = kind; self.endpoint = endpoint; self.token = token; self.connected = connected
    }
    public var complete: Bool { !endpoint.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !token.isEmpty }
}

public protocol AgentTokenStore: AnyObject {
    func save(_ value: String) throws
    func read() -> String?
    func delete()
}

public final class AgentConnectionStore: @unchecked Sendable {
    private let defaults: UserDefaults
    private let keychain: AgentTokenStore
    private let kindKey = "padnote.agent.kind"
    private let endpointKey = "padnote.agent.endpoint"
    private let connectedKey = "padnote.agent.connected"

    public init(defaults: UserDefaults = .standard, keychain: AgentTokenStore = KeychainTokenStore()) {
        self.defaults = defaults; self.keychain = keychain
    }
    public func load() -> AgentConnectionConfig {
        AgentConnectionConfig(kind: AgentKind(rawValue: defaults.string(forKey: kindKey) ?? "hermes") ?? .hermes,
                               endpoint: defaults.string(forKey: endpointKey) ?? "", token: keychain.read() ?? "",
                               connected: defaults.bool(forKey: connectedKey))
    }
    public func save(kind: AgentKind, endpoint: String, token: String) throws {
        try keychain.save(token)
        defaults.set(kind.rawValue, forKey: kindKey); defaults.set(endpoint.trimmingCharacters(in: .whitespacesAndNewlines), forKey: endpointKey); defaults.set(false, forKey: connectedKey)
    }
    public func setConnected(_ value: Bool) { defaults.set(value, forKey: connectedKey) }
    public func clear() { defaults.removeObject(forKey: kindKey); defaults.removeObject(forKey: endpointKey); defaults.removeObject(forKey: connectedKey); keychain.delete() }
}

public final class KeychainTokenStore: AgentTokenStore, @unchecked Sendable {
    private let service: String
    private let account: String
    public init(service: String = "com.padnote.agent", account: String = "token") { self.service = service; self.account = account }
    public func save(_ value: String) throws {
        let data = Data(value.utf8)
        let query: [CFString: Any] = [kSecClass: kSecClassGenericPassword, kSecAttrService: service, kSecAttrAccount: account]
        let status = SecItemUpdate(query as CFDictionary, [kSecValueData: data, kSecAttrAccessible: kSecAttrAccessibleWhenUnlockedThisDeviceOnly] as CFDictionary)
        if status == errSecItemNotFound {
            var item = query; item[kSecValueData] = data; item[kSecAttrAccessible] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
            guard SecItemAdd(item as CFDictionary, nil) == errSecSuccess else { throw AgentStoreError.keychain }
        } else if status != errSecSuccess { throw AgentStoreError.keychain }
    }
    public func read() -> String? {
        let query: [CFString: Any] = [kSecClass: kSecClassGenericPassword, kSecAttrService: service, kSecAttrAccount: account, kSecReturnData: true, kSecMatchLimit: kSecMatchLimitOne]
        var result: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess, let data = result as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }
    public func delete() { let query: [CFString: Any] = [kSecClass: kSecClassGenericPassword, kSecAttrService: service, kSecAttrAccount: account]; SecItemDelete(query as CFDictionary) }
}

public enum AgentStoreError: Error, LocalizedError { case keychain; public var errorDescription: String? { "无法保存 Agent 连接令牌" } }
