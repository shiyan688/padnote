import Combine
import CryptoKit
import Foundation
import Security
import UIKit

public enum AIProfileMode: String, Codable, CaseIterable, Identifiable {
    case direct
    case twoStage = "two_stage"
    public var id: String { rawValue }
    public var title: String { self == .direct ? "直连多模态" : "两段式转写 + 回答" }
}

public enum AIProvider: String, Codable, CaseIterable, Identifiable {
    case custom, bigModel, deepSeek, ali, aliTokenPlan, miniMax, miniMaxTokenPlan, kimi
    public var id: String { rawValue }
    public var title: String {
        switch self {
        case .custom: return "自定义"
        case .bigModel: return "智谱 BigModel（有免费视觉模型）"
        case .deepSeek: return "DeepSeek"
        case .ali: return "阿里云百炼"
        case .aliTokenPlan: return "阿里云百炼 Token Plan（订阅）"
        case .miniMax: return "MiniMax"
        case .miniMaxTokenPlan: return "MiniMax Token Plan（订阅）"
        case .kimi: return "Kimi 开放平台"
        }
    }
}

public struct AIProviderPreset: Codable, Equatable, Identifiable {
    public let provider: AIProvider
    public let endpoint: String
    public let directModel: String
    public let visionModel: String
    public let textModel: String
    public let hint: String
    public var id: String { provider.rawValue }
    public static let all: [AIProviderPreset] = [
        .init(provider: .custom, endpoint: "", directModel: "", visionModel: "", textModel: "", hint: ""),
        .init(provider: .bigModel, endpoint: "https://open.bigmodel.cn/api/paas/v4", directModel: "glm-4.6v-flash", visionModel: "glm-4.6v-flash", textModel: "glm-4.7-flash", hint: "API Key 在 open.bigmodel.cn 控制台获取。glm-4.6v-flash 支持图像与工具调用。"),
        .init(provider: .deepSeek, endpoint: "https://api.deepseek.com", directModel: "deepseek-v4-flash-vision-exp", visionModel: "deepseek-v4-flash-vision-exp", textModel: "deepseek-v4-flash", hint: "API Key 在 platform.deepseek.com 获取。vision-exp 为实验版。"),
        .init(provider: .ali, endpoint: "https://dashscope.aliyuncs.com/compatible-mode/v1", directModel: "qwen3.7-flash", visionModel: "qwen3.5-ocr", textModel: "qwen3.7-flash", hint: "粘贴 sk- 开头的按量 API Key。"),
        .init(provider: .aliTokenPlan, endpoint: "https://token-plan.cn-beijing.maas.aliyuncs.com/compatible-mode/v1", directModel: "qwen3.7-plus", visionModel: "qwen3.7-plus", textModel: "qwen3.7-plus", hint: "粘贴 sk-sp- 开头的订阅 Key，与按量 Key 不互通。"),
        .init(provider: .miniMax, endpoint: "https://api.minimaxi.com/v1", directModel: "MiniMax-M3", visionModel: "MiniMax-M3", textModel: "MiniMax-M3", hint: "按量 API Key；M2.7 是纯文本模型。"),
        .init(provider: .miniMaxTokenPlan, endpoint: "https://api.minimaxi.com/v1", directModel: "MiniMax-M3", visionModel: "MiniMax-M3", textModel: "MiniMax-M3", hint: "订阅管理中的 Token Plan Key，与按量 API Key 不互通。"),
        .init(provider: .kimi, endpoint: "https://api.moonshot.cn/v1", directModel: "kimi-k2.6", visionModel: "kimi-k2.6", textModel: "kimi-k2.6", hint: "API Key 在 platform.kimi.com 获取，纯按量计费。")
    ]
}

public struct AIProfile: Codable, Equatable, Identifiable {
    public var id: String
    public var name: String
    public var provider: AIProvider
    public var mode: AIProfileMode
    public var visionEndpoint: String
    public var visionModel: String
    public var textEndpoint: String
    public var textModel: String
    public var visionKeyReference: String
    public var textKeyReference: String
    public var revision: Int
    public init(id: String = UUID().uuidString, name: String = "默认配置", provider: AIProvider = .custom,
                mode: AIProfileMode = .direct, visionEndpoint: String = "", visionModel: String = "",
                textEndpoint: String = "", textModel: String = "",
                visionKeyReference: String? = nil, textKeyReference: String? = nil,
                revision: Int = 1) {
        self.id = id; self.name = name; self.provider = provider; self.mode = mode
        self.visionEndpoint = visionEndpoint; self.visionModel = visionModel
        self.textEndpoint = textEndpoint.isEmpty ? visionEndpoint : textEndpoint
        self.textModel = textModel.isEmpty ? visionModel : textModel
        self.visionKeyReference = visionKeyReference ?? "secure-storage://padnote/profile/\(id)/vision"
        self.textKeyReference = textKeyReference ?? "secure-storage://padnote/profile/\(id)/text"
        self.revision = min(1_000_000_000, max(1, revision))
    }
    public var summary: String { mode == .direct ? "直连 · \(visionModel)" : "两段式 · 转写 \(visionModel) → 回答 \(textModel)" }
    public func applying(_ preset: AIProviderPreset) -> AIProfile {
        var copy = self; copy.provider = preset.provider; copy.visionEndpoint = preset.endpoint
        copy.visionModel = mode == .direct ? preset.directModel : preset.visionModel
        copy.textEndpoint = preset.endpoint; copy.textModel = preset.textModel; return copy
    }

    private enum CodingKeys: String, CodingKey {
        case id, name, provider, mode, visionEndpoint, visionModel, textEndpoint, textModel,
             visionKeyReference, textKeyReference, revision
    }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(id: try c.decode(String.self, forKey: .id),
                  name: try c.decode(String.self, forKey: .name),
                  provider: try c.decodeIfPresent(AIProvider.self, forKey: .provider) ?? .custom,
                  mode: try c.decodeIfPresent(AIProfileMode.self, forKey: .mode) ?? .direct,
                  visionEndpoint: try c.decodeIfPresent(String.self, forKey: .visionEndpoint) ?? "",
                  visionModel: try c.decodeIfPresent(String.self, forKey: .visionModel) ?? "",
                  textEndpoint: try c.decodeIfPresent(String.self, forKey: .textEndpoint) ?? "",
                  textModel: try c.decodeIfPresent(String.self, forKey: .textModel) ?? "",
                  visionKeyReference: try c.decodeIfPresent(String.self, forKey: .visionKeyReference),
                  textKeyReference: try c.decodeIfPresent(String.self, forKey: .textKeyReference),
                  revision: try c.decodeIfPresent(Int.self, forKey: .revision) ?? 1)
    }
}

public struct AISettings: Codable, Equatable {
    public var endpoint: String
    public var model: String
    public var keyReference: String
    public init(endpoint: String = "https://api.openai.com/v1", model: String = "gpt-4o-mini", keyReference: String = "secure-storage://padnote/default") {
        self.endpoint = endpoint; self.model = model; self.keyReference = keyReference
    }
}
public typealias AIConfiguration = AISettings

public final class AISettingsStore: ObservableObject {
    private struct PendingSecretDelete: Codable, Equatable {
        let reference: String
        let processGeneration: String
    }
    private static let profilesKey = "padnote.ai.profiles"
    private static let pendingSecretDeletesKey = "padnote.ai.pendingSecretDeletes"
    private static let processGeneration = UUID().uuidString
    @Published public var settings: AISettings { didSet { save() } }
    @Published public private(set) var profiles: [AIProfile]
    @Published public var activeProfileID: String? { didSet { saveProfiles() } }
    @Published public var visionProfileID: String? { didSet { saveProfiles() } }
    @Published public var textProfileID: String? { didSet { saveProfiles() } }
    @Published public var mode: AIProfileMode { didSet { saveProfiles() } }
    @Published public private(set) var credentialError: String?
    private let defaults: UserDefaults
    private let secrets: SecretStore
    private let currentProcessGeneration: String
    private var profileMetadataIsValid: Bool
    public convenience init(defaults: UserDefaults = .standard,
                            secretStore: SecretStore = KeychainSecretStore()) {
        self.init(defaults: defaults, secretStore: secretStore,
                  processGeneration: Self.processGeneration)
    }
    init(defaults: UserDefaults, secretStore: SecretStore, processGeneration: String) {
        self.defaults = defaults
        secrets = secretStore
        currentProcessGeneration = processGeneration
        credentialError = nil
        let legacyData = defaults.data(forKey: "padnote.ai.settings")
        let legacySettings = legacyData
            .flatMap { try? JSONDecoder().decode(AISettings.self, from: $0) } ?? AISettings()
        let profilesData = defaults.data(forKey: Self.profilesKey)
        let saved = profilesData.flatMap { try? JSONDecoder().decode([AIProfile].self, from: $0) }
        profileMetadataIsValid = profilesData == nil || saved != nil
        let initialProfiles: [AIProfile]
        if profilesData != nil {
            // An empty list is an intentional result of deleting the final
            // profile. A corrupt new-format payload also fails closed instead
            // of recreating a profile from stale legacy settings.
            initialProfiles = saved ?? []
        } else {
            let legacy = AIProfile(name: "默认配置", visionEndpoint: legacySettings.endpoint, visionModel: legacySettings.model,
                                   visionKeyReference: legacySettings.keyReference, textKeyReference: legacySettings.keyReference)
            initialProfiles = [legacy]
        }
        let storedActiveID = defaults.string(forKey: "padnote.ai.activeProfile")
        let resolvedActiveID = initialProfiles.contains { $0.id == storedActiveID }
            ? storedActiveID : initialProfiles.first?.id
        if profilesData != nil {
            if let active = initialProfiles.first(where: { $0.id == resolvedActiveID }) {
                settings = AISettings(endpoint: active.visionEndpoint, model: active.visionModel,
                                      keyReference: active.visionKeyReference)
            } else {
                settings = AISettings(endpoint: "", model: "", keyReference: "")
            }
        } else {
            settings = legacySettings
        }
        profiles = initialProfiles
        activeProfileID = resolvedActiveID
        let storedVisionID = defaults.string(forKey: "padnote.ai.visionProfile")
        let storedTextID = defaults.string(forKey: "padnote.ai.textProfile")
        visionProfileID = initialProfiles.contains { $0.id == storedVisionID } ? storedVisionID : nil
        textProfileID = initialProfiles.contains { $0.id == storedTextID } ? storedTextID : nil
        mode = AIProfileMode(rawValue: defaults.string(forKey: "padnote.ai.mode") ?? "direct") ?? .direct
        if profilesData == nil { saveProfiles() }
        else { save() }
        if profileMetadataIsValid {
            drainPendingSecretDeletes()
        } else {
            credentialError = "模型档案数据无法读取；凭据已保留，请先恢复档案数据。"
        }
    }
    private func save() { if let data = try? JSONEncoder().encode(settings) { defaults.set(data, forKey: "padnote.ai.settings") } }
    private func saveProfiles() {
        guard profileMetadataIsValid else { return }
        if let data = try? JSONEncoder().encode(profiles) { defaults.set(data, forKey: Self.profilesKey) }
        defaults.set(activeProfileID, forKey: "padnote.ai.activeProfile"); defaults.set(visionProfileID, forKey: "padnote.ai.visionProfile")
        defaults.set(textProfileID, forKey: "padnote.ai.textProfile"); defaults.set(mode.rawValue, forKey: "padnote.ai.mode")
    }
    public var activeProfile: AIProfile? { profiles.first { $0.id == activeProfileID } ?? profiles.first }
    public var requestProfile: AIProfile? {
        guard let base = activeProfile else { return nil }
        let vision = profiles.first { $0.id == visionProfileID } ?? base
        let text = profiles.first { $0.id == textProfileID } ?? base
        var merged = base; merged.mode = mode; merged.visionEndpoint = vision.visionEndpoint; merged.visionModel = vision.visionModel; merged.visionKeyReference = vision.visionKeyReference
        merged.textEndpoint = text.textEndpoint; merged.textModel = text.textModel; merged.textKeyReference = text.textKeyReference
        return merged
    }
    var requestRecipientIdentity: AIRecipientIdentity? {
        guard let base = activeProfile else { return nil }
        let vision = profiles.first { $0.id == visionProfileID } ?? base
        let text = profiles.first { $0.id == textProfileID } ?? base
        let raw = [mode.rawValue,
                   vision.id, String(vision.revision), vision.visionModel, vision.visionKeyReference,
                   text.id, String(text.revision), text.textModel, text.textKeyReference]
            .joined(separator: "\u{0}")
        let fingerprint = SHA256.hash(data: Data(raw.utf8)).map { String(format: "%02x", $0) }.joined()
        func host(_ value: String) -> String { URLComponents(string: value)?.host ?? "未配置地址" }
        let display = mode == .direct
            ? "\(host(vision.visionEndpoint)) · \(vision.visionModel)"
            : "转写 \(host(vision.visionEndpoint)) · \(vision.visionModel) → 回答 \(host(text.textEndpoint)) · \(text.textModel)"
        return AIRecipientIdentity(fingerprint: fingerprint, display: display)
    }
    public func upsert(_ profile: AIProfile) {
        // Adding or replacing a profile is the explicit recovery path after a
        // corrupt payload. Passive property changes must not overwrite it.
        profileMetadataIsValid = true
        let replacedReferences: Set<String>
        var profile = profile
        if let index = profiles.firstIndex(where: { $0.id == profile.id }) {
            replacedReferences = secretReferences(in: profiles[index])
            let old = profiles[index]
            let identityChanged = old.mode != profile.mode || old.visionEndpoint != profile.visionEndpoint
                || old.visionModel != profile.visionModel || old.textEndpoint != profile.textEndpoint
                || old.textModel != profile.textModel || old.visionKeyReference != profile.visionKeyReference
                || old.textKeyReference != profile.textKeyReference
            profile.revision = identityChanged ? max(profile.revision, old.revision + 1)
                                               : max(profile.revision, old.revision)
            profiles[index] = profile
        } else {
            replacedReferences = []
            profiles.append(profile)
        }
        if activeProfileID == nil { activeProfileID = profile.id }
        if activeProfileID == profile.id {
            settings = AISettings(endpoint: profile.visionEndpoint, model: profile.visionModel,
                                  keyReference: profile.visionKeyReference)
        }
        saveProfiles()
        enqueueSecretDeletes(replacedReferences.subtracting(referencedSecretReferences))
    }

    /// Stages replacement credentials under new references, verifies them, then
    /// commits metadata. Shared references used by another profile are never
    /// overwritten by editing this profile.
    public func upsert(_ profile: AIProfile, visionToken: String?, textToken: String?) throws {
        let cleanVision = visionToken?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let cleanText = textToken?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        var staged = profile
        let previous = profiles.first { $0.id == profile.id }
        let nextRevision = max(profile.revision, (previous?.revision ?? 0) + 1)
        var stagedReferences: [String] = []
        do {
            if !cleanVision.isEmpty {
                let reference = "secure-storage://padnote/profile/\(profile.id)/vision.r\(nextRevision).\(UUID().uuidString.lowercased())"
                try secrets.write(cleanVision, reference: reference)
                guard try secrets.read(reference: reference) == cleanVision else { throw SecretStoreError.invalidData }
                staged.visionKeyReference = reference
                stagedReferences.append(reference)
            }
            if !cleanText.isEmpty {
                let reference = "secure-storage://padnote/profile/\(profile.id)/text.r\(nextRevision).\(UUID().uuidString.lowercased())"
                try secrets.write(cleanText, reference: reference)
                guard try secrets.read(reference: reference) == cleanText else { throw SecretStoreError.invalidData }
                staged.textKeyReference = reference
                stagedReferences.append(reference)
            }
            if !stagedReferences.isEmpty { staged.revision = nextRevision }
            upsert(staged)
        } catch {
            for reference in stagedReferences { try? secrets.delete(reference: reference) }
            throw error
        }
    }
    public func select(_ id: String) {
        guard let profile = profiles.first(where: { $0.id == id }) else { return }
        if activeProfileID != id { visionProfileID = id; textProfileID = id; mode = profile.mode }
        activeProfileID = id
        settings = AISettings(endpoint: profile.visionEndpoint, model: profile.visionModel, keyReference: profile.visionKeyReference)
        saveProfiles()
    }
    public func delete(_ id: String) {
        let removedReferences = Set(profiles.filter { $0.id == id }.flatMap { secretReferences(in: $0) })
        profiles.removeAll { $0.id == id }
        let fallback = profiles.first
        if visionProfileID == id { visionProfileID = fallback?.id }
        if textProfileID == id { textProfileID = fallback?.id }
        if activeProfileID == id { activeProfileID = fallback?.id }
        if let active = activeProfile {
            settings = AISettings(endpoint: active.visionEndpoint, model: active.visionModel,
                                  keyReference: active.visionKeyReference)
        } else {
            activeProfileID = nil; visionProfileID = nil; textProfileID = nil
            settings = AISettings(endpoint: "", model: "", keyReference: "")
        }
        saveProfiles()
        enqueueSecretDeletes(removedReferences.subtracting(referencedSecretReferences))
    }

    private var referencedSecretReferences: Set<String> {
        var references = Set(profiles.flatMap { secretReferences(in: $0) })
        if !settings.keyReference.isEmpty { references.insert(settings.keyReference) }
        return references
    }

    private func secretReferences(in profile: AIProfile) -> Set<String> {
        Set([profile.visionKeyReference, profile.textKeyReference].filter { !$0.isEmpty })
    }

    private func enqueueSecretDeletes(_ references: Set<String>) {
        guard !references.isEmpty else { return }
        var pending = pendingSecretDeletes()
        pending.removeAll { references.contains($0.reference) }
        pending.append(contentsOf: references.sorted().map {
            PendingSecretDelete(reference: $0, processGeneration: currentProcessGeneration)
        })
        savePendingSecretDeletes(pending)
        // A new store in this process shares the same generation and cannot
        // drain these entries. Only a later process generation may remove the
        // rollback credential after both metadata and journal were persisted.
    }

    private func drainPendingSecretDeletes() {
        guard profileMetadataIsValid else { return }
        var retained: [PendingSecretDelete] = []
        for item in pendingSecretDeletes() {
            if referencedSecretReferences.contains(item.reference) { continue }
            if item.processGeneration == currentProcessGeneration {
                retained.append(item)
                continue
            }
            do { try secrets.delete(reference: item.reference) }
            catch {
                retained.append(item)
                credentialError = "旧模型凭据尚未清理，将在下次启动重试：\(error.localizedDescription)"
            }
        }
        savePendingSecretDeletes(retained)
    }

    private func pendingSecretDeletes() -> [PendingSecretDelete] {
        if let data = defaults.data(forKey: Self.pendingSecretDeletesKey),
           let value = try? JSONDecoder().decode([PendingSecretDelete].self, from: data) {
            return value
        }
        // beta.7 stored a string array. It can only have been written by an
        // earlier app process, so it is safe to mark as a legacy generation.
        return (defaults.stringArray(forKey: Self.pendingSecretDeletesKey) ?? []).map {
            PendingSecretDelete(reference: $0, processGeneration: "legacy")
        }
    }

    private func savePendingSecretDeletes(_ items: [PendingSecretDelete]) {
        if items.isEmpty {
            defaults.removeObject(forKey: Self.pendingSecretDeletesKey)
        } else if let data = try? JSONEncoder().encode(items) {
            defaults.set(data, forKey: Self.pendingSecretDeletesKey)
        }
    }
}

public protocol SecretStore {
    func read(reference: String) throws -> String?
    func write(_ value: String, reference: String) throws
    func delete(reference: String) throws
}
public enum SecretStoreError: Error { case invalidData, operation(OSStatus) }
public final class KeychainSecretStore: SecretStore {
    private let service = "com.padnote.ai"
    public init() {}
    public func read(reference: String) throws -> String? {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: reference, kSecReturnData as String: true, kSecMatchLimit as String: kSecMatchLimitOne]
        var result: CFTypeRef?; let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }; guard status == errSecSuccess else { throw SecretStoreError.operation(status) }
        guard let data = result as? Data, let value = String(data: data, encoding: .utf8) else { throw SecretStoreError.invalidData }; return value
    }
    public func write(_ value: String, reference: String) throws {
        let identity: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: reference]
        let update: [String: Any] = [kSecValueData as String: Data(value.utf8), kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlockedThisDeviceOnly]
        let status = SecItemUpdate(identity as CFDictionary, update as CFDictionary)
        if status == errSecItemNotFound { var item = identity; update.forEach { item[$0.key] = $0.value }; let add = SecItemAdd(item as CFDictionary, nil); guard add == errSecSuccess else { throw SecretStoreError.operation(add) } } else if status != errSecSuccess { throw SecretStoreError.operation(status) }
    }
    public func delete(reference: String) throws {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
                                    kSecAttrService as String: service,
                                    kSecAttrAccount as String: reference]
        let status = SecItemDelete(query as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw SecretStoreError.operation(status)
        }
    }
}

public enum AIPreset: String, CaseIterable, Identifiable { case explain, organizeMarkdown = "organize_markdown", formalize, extractTasks = "extract_tasks", custom; public var id: String { rawValue }; public var title: String { switch self { case .explain: return "讲解"; case .organizeMarkdown: return "整理 Markdown"; case .formalize: return "正式改写"; case .extractTasks: return "提取任务"; case .custom: return "自定义" } } }
public struct AIToolCall: Codable, Equatable {
    public struct Function: Codable, Equatable { public let name: String; public let arguments: String }
    public let id: String
    public let type: String
    public let function: Function
}
public struct AIConversationMessage: Codable, Equatable {
    public let role: String
    public let content: String
    public let imageDataURL: String?
    public let toolCallID: String?
    public let toolCalls: [AIToolCall]?
    public init(role: String, content: String, imageDataURL: String? = nil, toolCallID: String? = nil, toolCalls: [AIToolCall]? = nil) {
        self.role = role; self.content = content; self.imageDataURL = imageDataURL
        self.toolCallID = toolCallID; self.toolCalls = toolCalls
    }
}
public struct AIConversationRequest: Codable { public let requestId: String; public let action: String; public let locale: String; public let messages: [AIConversationMessage]; public let model: String; public init(action: AIPreset, model: String, messages: [AIConversationMessage], locale: String = "zh-CN", requestId: String = "req-\(UUID().uuidString)") { self.requestId = requestId; self.action = action.rawValue; self.locale = locale; self.messages = messages; self.model = model } }
public struct AIDomainResponse: Codable {
    public let requestId: String?
    public let content: String
    public let format: String?
    public let warnings: [String]?
    public let toolCalls: [AIToolCall]?
    public init(requestId: String?, content: String, format: String?, warnings: [String]?, toolCalls: [AIToolCall]? = nil) {
        self.requestId = requestId; self.content = content; self.format = format; self.warnings = warnings; self.toolCalls = toolCalls
    }
}
public enum AIClientError: LocalizedError { case invalidEndpoint, insecureEndpoint, redirected, responseTooLarge, http(Int), emptyResponse, timedOut, cancelled, invalidResponse, missingToken; public var errorDescription: String { switch self { case .invalidEndpoint: return "模型地址无效"; case .insecureEndpoint: return "模型地址必须使用 HTTPS"; case .redirected: return "请求被重定向，已阻止发送密钥"; case .responseTooLarge: return "模型响应超过 4 MB"; case .http(let c): return "模型服务返回 HTTP \(c)"; case .emptyResponse: return "模型没有返回内容"; case .timedOut: return "请求超时"; case .cancelled: return "请求已取消"; case .invalidResponse: return "模型响应格式无法识别"; case .missingToken: return "请先在设置中保存 API Key" } } }

public final class AIClient {
    public let settings: AISettings
    private let secrets: SecretStore
    private let configuration: URLSessionConfiguration

    public init(settings: AISettings, secretStore: SecretStore = KeychainSecretStore(),
                sessionConfiguration: URLSessionConfiguration = .ephemeral) {
        self.settings = settings
        secrets = secretStore
        configuration = sessionConfiguration.copy() as! URLSessionConfiguration
        configuration.httpShouldSetCookies = false
        configuration.httpCookieStorage = nil
        configuration.urlCache = nil
        configuration.timeoutIntervalForRequest = 90
        configuration.timeoutIntervalForResource = 120
    }

    static func endpointURL(_ value: String) throws -> URL {
        let clean = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard var url = URL(string: clean), let host = url.host, !host.isEmpty else {
            throw AIClientError.invalidEndpoint
        }
        guard url.scheme?.lowercased() == "https" else { throw AIClientError.insecureEndpoint }
        guard url.user == nil, url.password == nil, url.query == nil, url.fragment == nil else {
            throw AIClientError.invalidEndpoint
        }
        var components = URLComponents(url: url, resolvingAgainstBaseURL: false)!
        while components.path.hasSuffix("/") { components.path.removeLast() }
        url = components.url!
        if !url.path.hasSuffix("/chat/completions") { url.appendPathComponent("chat/completions") }
        return url
    }

    public func complete(image: UIImage? = nil, prompt: String,
                         history: [AIConversationMessage] = [], context: String = "") async throws -> String {
        let text = context.isEmpty ? prompt : prompt + "\n\n笔记上下文：\n" + context
        let imageURL = image?.pngData().map { "data:image/png;base64," + $0.base64EncodedString() }
        let response = try await send(AIConversationRequest(action: .custom, model: settings.model,
            messages: history + [AIConversationMessage(role: "user", content: text, imageDataURL: imageURL)]))
        return response.content
    }

    /// Sends through a named profile while preserving the original `send` API.
    /// In two-stage mode the image is sent once to the vision leg; the returned
    /// transcript is then included in the text leg and reused for follow-ups.
    public func send(_ request: AIConversationRequest, profile: AIProfile,
                     transcript: String? = nil) async throws -> AIDomainResponse {
        if profile.mode == .direct {
            return try await send(request, endpoint: profile.visionEndpoint, model: profile.visionModel,
                                  keyReference: profile.visionKeyReference)
        }
        var transcriptText = transcript
        if transcriptText == nil, let image = request.messages.last?.imageDataURL {
            guard let comma = image.firstIndex(of: ","), let data = Data(base64Encoded: String(image[image.index(after: comma)...])) else { throw AIClientError.invalidResponse }
            let visionRequest = AIConversationRequest(action: .custom, model: profile.visionModel, messages: [
                .init(role: "user", content: "请把图片中的手写内容原样转写为 Markdown；数学公式使用标准 LaTeX，保留标题、列表和段落顺序，不要解释。", imageDataURL: "data:image/png;base64," + data.base64EncodedString())
            ])
            transcriptText = try await send(visionRequest, endpoint: profile.visionEndpoint, model: profile.visionModel, keyReference: profile.visionKeyReference).content
        }
        var messages = request.messages.map { message in
            AIConversationMessage(role: message.role, content: message.content)
        }
        if let transcriptText { messages.insert(.init(role: "system", content: "用户选区的手写转写（作为参考数据）：\n" + transcriptText), at: 0) }
        let answer = AIConversationRequest(action: .custom, model: profile.textModel, messages: messages)
        return try await send(answer, endpoint: profile.textEndpoint, model: profile.textModel, keyReference: profile.textKeyReference)
    }

    public func transcribe(_ image: UIImage, profile: AIProfile) async throws -> String {
        guard profile.mode == .twoStage, let data = image.pngData() else { throw AIClientError.invalidResponse }
        let request = AIConversationRequest(action: .custom, model: profile.visionModel, messages: [
            .init(role: "user", content: "请把图片中的手写内容原样转写为 Markdown；数学公式使用标准 LaTeX，保留标题、列表和段落顺序，不要解释。", imageDataURL: "data:image/png;base64," + data.base64EncodedString())
        ])
        return try await send(request, endpoint: profile.visionEndpoint, model: profile.visionModel, keyReference: profile.visionKeyReference).content
    }

    public func send(_ request: AIConversationRequest) async throws -> AIDomainResponse {
        try await send(request, endpoint: settings.endpoint, model: request.model,
                       keyReference: settings.keyReference)
    }

    public func sendToolRound(messages: [AIConversationMessage], profile: AIProfile,
                             tools: [[String: Any]]) async throws -> AIDomainResponse {
        let settings = profile.mode == .direct
            ? AISettings(endpoint: profile.visionEndpoint, model: profile.visionModel, keyReference: profile.visionKeyReference)
            : AISettings(endpoint: profile.textEndpoint, model: profile.textModel, keyReference: profile.textKeyReference)
        let body = try JSONEncoder().encode(OpenAIRequest(model: settings.model, messages: messages))
        var object = try JSONSerialization.jsonObject(with: body) as! [String: Any]
        object["tools"] = tools
        object["tool_choice"] = "auto"
        return try await transmit(body: JSONSerialization.data(withJSONObject: object), settings: settings)
    }

    private func send(_ request: AIConversationRequest, endpoint endpointValue: String, model: String,
                      keyReference: String) async throws -> AIDomainResponse {
        try await transmit(body: JSONEncoder().encode(OpenAIRequest(model: model, messages: request.messages)),
            settings: AISettings(endpoint: endpointValue, model: model, keyReference: keyReference))
    }

    private func transmit(body: Data, settings: AISettings) async throws -> AIDomainResponse {
        try Task.checkCancellation()
        let endpoint = try Self.endpointURL(settings.endpoint)
        guard !settings.model.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw AIClientError.invalidResponse
        }
        guard let token = try secrets.read(reference: settings.keyReference), !token.isEmpty else {
            throw AIClientError.missingToken
        }
        var value = URLRequest(url: endpoint)
        value.httpMethod = "POST"
        value.setValue("application/json", forHTTPHeaderField: "Content-Type")
        value.setValue("Bearer " + token, forHTTPHeaderField: "Authorization")
        value.httpBody = body
        let operation = RequestOperation(configuration: configuration, request: value)
        return try await withTaskCancellationHandler {
            try await operation.run()
        } onCancel: {
            operation.cancel()
        }
    }

    private struct OpenAIRequest: Encodable {
        let model: String
        let messages: [OpenAIMessage]
        let max_tokens = 4096
        let stream = false
        init(model: String, messages: [AIConversationMessage]) {
            self.model = model
            self.messages = messages.map(OpenAIMessage.init)
        }
    }

    private struct OpenAIMessage: Encodable {
        struct ImageURL: Encodable { let url: String }
        struct Part: Encodable { let type: String; let text: String?; let image_url: ImageURL? }
        let role: String
        let content: Content
        let tool_call_id: String?
        let tool_calls: [AIToolCall]?
        enum Content: Encodable {
            case text(String), image(String, String)
            func encode(to encoder: Encoder) throws {
                switch self {
                case .text(let text):
                    var container = encoder.singleValueContainer()
                    try container.encode(text)
                case .image(let text, let url):
                    var container = encoder.unkeyedContainer()
                    try container.encode(Part(type: "text", text: text, image_url: nil))
                    try container.encode(Part(type: "image_url", text: nil, image_url: ImageURL(url: url)))
                }
            }
        }
        init(_ message: AIConversationMessage) {
            role = message.role
            tool_call_id = message.toolCallID
            tool_calls = message.toolCalls
            content = message.imageDataURL.map { .image(message.content, $0) } ?? .text(message.content)
        }
    }

    private struct OpenAIResponse: Decodable {
        struct Choice: Decodable {
            struct Message: Decodable { let content: String?; let tool_calls: [AIToolCall]? }
            let message: Message?
        }
        let choices: [Choice]?
    }

    /// Start, cancellation and every delegate callback share one serial queue.
    /// In particular, cancellation before the continuation exists still finishes it.
    private final class RequestOperation: NSObject, URLSessionDataDelegate, @unchecked Sendable {
        private let configuration: URLSessionConfiguration
        private let request: URLRequest
        private let stateQueue = DispatchQueue(label: "com.padnote.ai.request")
        private var session: URLSession?
        private var task: URLSessionDataTask?
        private var continuation: CheckedContinuation<AIDomainResponse, Error>?
        private var outcome: Result<AIDomainResponse, Error>?
        private var data = Data()
        private let maxBytes = 4 * 1024 * 1024

        init(configuration: URLSessionConfiguration, request: URLRequest) {
            self.configuration = configuration
            self.request = request
        }

        func run() async throws -> AIDomainResponse {
            try await withCheckedThrowingContinuation { continuation in
                stateQueue.async {
                    if let outcome = self.outcome { continuation.resume(with: outcome); return }
                    self.continuation = continuation
                    let queue = OperationQueue()
                    queue.maxConcurrentOperationCount = 1
                    queue.underlyingQueue = self.stateQueue
                    let session = URLSession(configuration: self.configuration, delegate: self, delegateQueue: queue)
                    self.session = session
                    self.task = session.dataTask(with: self.request)
                    self.task?.resume()
                }
            }
        }

        func cancel() { stateQueue.async { self.finish(.failure(AIClientError.cancelled)) } }

        func urlSession(_ session: URLSession, task: URLSessionTask,
                        willPerformHTTPRedirection response: HTTPURLResponse, newRequest: URLRequest,
                        completionHandler: @escaping (URLRequest?) -> Void) {
            completionHandler(nil)
            finish(.failure(AIClientError.redirected))
        }

        func urlSession(_ session: URLSession, dataTask: URLSessionDataTask,
                        didReceive response: URLResponse,
                        completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
            if response.expectedContentLength > Int64(maxBytes) {
                completionHandler(.cancel)
                finish(.failure(AIClientError.responseTooLarge))
            } else { completionHandler(.allow) }
        }

        func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
            guard outcome == nil else { return }
            guard data.count <= maxBytes - self.data.count else {
                finish(.failure(AIClientError.responseTooLarge)); return
            }
            self.data.append(data)
        }

        func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
            guard outcome == nil else { return }
            if let error {
                let code = (error as NSError).code
                finish(.failure(code == NSURLErrorCancelled ? AIClientError.cancelled :
                    code == NSURLErrorTimedOut ? AIClientError.timedOut : error))
                return
            }
            guard let response = task.response as? HTTPURLResponse else {
                finish(.failure(AIClientError.invalidResponse)); return
            }
            guard (200..<300).contains(response.statusCode) else {
                finish(.failure(AIClientError.http(response.statusCode))); return
            }
            do {
                let result = try JSONDecoder().decode(OpenAIResponse.self, from: data)
                guard let message = result.choices?.first?.message else { throw AIClientError.emptyResponse }
                let content = message.content ?? ""
                guard !content.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || !(message.tool_calls ?? []).isEmpty else {
                    throw AIClientError.emptyResponse
                }
                finish(.success(AIDomainResponse(requestId: nil, content: content, format: "markdown", warnings: nil, toolCalls: message.tool_calls)))
            } catch { finish(.failure(error)) }
        }

        private func finish(_ result: Result<AIDomainResponse, Error>) {
            guard outcome == nil else { return }
            outcome = result
            continuation?.resume(with: result)
            continuation = nil
            session?.invalidateAndCancel()
            task = nil
            session = nil
        }
    }
}
