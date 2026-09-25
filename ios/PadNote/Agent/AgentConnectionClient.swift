import Foundation

public enum AgentConnectionError: Error, LocalizedError, Equatable {
    case openClawRequiresBridge
    case incomplete
    case httpsRequired
    case redirected
    case http(Int)
    case invalidResponse
    case identityMismatch
    case missingCapability(String)
    case responseTooLarge

    public var errorDescription: String? {
        switch self {
        case .openClawRequiresBridge: return "OpenClaw 任务适配尚未开放"
        case .incomplete: return "请填写 Agent 地址和连接令牌"
        case .httpsRequired: return "Agent 地址必须是安全的 HTTPS 根地址，且不能包含账号、查询参数或片段"
        case .redirected: return "Agent 请求不允许重定向"
        case .http(let status): return "Agent 连接检查失败（HTTP \(status)）"
        case .invalidResponse: return "响应不是受支持的 Agent capabilities"
        case .identityMismatch: return "电脑返回的 Agent 身份与已保存连接不一致"
        case .missingCapability(let capability): return "Agent 缺少能力：\(capability)"
        case .responseTooLarge: return "Agent 响应过大"
        }
    }
}

public struct AgentProbeResult: Equatable, Sendable {
    public let message: String
    public let capabilities: [String: Bool]
    public let bridgeID: String?
    public let instanceID: String?

    public init(message: String, capabilities: [String: Bool], bridgeID: String? = nil, instanceID: String? = nil) {
        self.message = message
        self.capabilities = capabilities
        self.bridgeID = bridgeID
        self.instanceID = instanceID
    }
}

public struct AgentConnectionClient {
    /// Polling is the first supported task transport. Approval and task bundles remain negotiated.
    public static let requiredCapabilities = ["run_submission", "run_status", "run_stop"]

    private let session: URLSession
    private let redirectDelegate: RedirectDelegate?

    public init(session: URLSession? = nil) {
        if let session {
            self.session = session
            self.redirectDelegate = nil
        } else {
            let delegate = RedirectDelegate()
            self.redirectDelegate = delegate
            let configuration = URLSessionConfiguration.ephemeral
            configuration.httpCookieStorage = nil
            configuration.urlCache = nil
            configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
            self.session = URLSession(configuration: configuration, delegate: delegate, delegateQueue: nil)
        }
    }

    public func probe(_ config: AgentConnectionConfig) async throws -> String {
        try await probeCapabilities(config).message
    }

    public func probeCapabilities(_ config: AgentConnectionConfig) async throws -> AgentProbeResult {
        guard config.complete else { throw AgentConnectionError.incomplete }
        guard config.kind == .hermes else { throw AgentConnectionError.openClawRequiresBridge }
        let url = try Self.capabilitiesURL(for: config)
        let object = try await requestJSON(url: url, method: "GET", token: config.token)
        let features = try Self.booleanFeatures(from: object)
        for name in Self.requiredCapabilities where features[name] != true {
            throw AgentConnectionError.missingCapability(name)
        }

        switch config.transport {
        case .direct:
            guard object["object"] as? String == "hermes.api_server.capabilities",
                  object["platform"] as? String == "hermes-agent" else {
                throw AgentConnectionError.invalidResponse
            }
            return AgentProbeResult(message: "Hermes 已连接", capabilities: features)
        case .bridge:
            guard object["object"] as? String == "padnote.agent.capabilities",
                  (object["protocol_version"] as? NSNumber)?.intValue == 1,
                  object["kind"] as? String == config.kind.rawValue,
                  let bridgeID = object["bridge_id"] as? String,
                  let instanceID = object["instance_id"] as? String else {
                throw AgentConnectionError.invalidResponse
            }
            guard bridgeID == config.bridgeID, instanceID == config.instanceID else {
                throw AgentConnectionError.identityMismatch
            }
            return AgentProbeResult(message: "电脑 Agent 已连接", capabilities: features, bridgeID: bridgeID, instanceID: instanceID)
        }
    }

    static func validatedBaseURL(_ endpoint: String) throws -> URL {
        let trimmed = endpoint.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let components = URLComponents(string: trimmed),
              components.scheme?.lowercased() == "https",
              let host = components.host, !host.isEmpty,
              components.user == nil,
              components.password == nil,
              components.query == nil,
              components.fragment == nil,
              let url = components.url else {
            throw AgentConnectionError.httpsRequired
        }
        return url
    }

    static func capabilitiesURL(for config: AgentConnectionConfig) throws -> URL {
        let base = try validatedBaseURL(config.endpoint)
        switch config.transport {
        case .direct:
            if base.path.hasSuffix("/v1/capabilities") { return base }
            if base.path.hasSuffix("/v1") { return base.appending(path: "capabilities") }
            return base.appending(path: "v1/capabilities")
        case .bridge:
            guard let instanceID = config.instanceID, validPathIdentifier(instanceID) else { throw AgentConnectionError.incomplete }
            return base
                .appending(path: "padnote/v1/agents")
                .appending(path: instanceID)
                .appending(path: "capabilities")
        }
    }

    static func validPathIdentifier(_ value: String) -> Bool {
        guard !value.isEmpty, value.count <= 200, value != ".", value != ".." else { return false }
        return value.unicodeScalars.allSatisfy {
            CharacterSet.alphanumerics.contains($0) || "-_.".unicodeScalars.contains($0)
        }
    }

    func requestJSON(
        url: URL,
        method: String,
        token: String?,
        body: Data? = nil,
        headers: [String: String] = [:],
        maximumBytes: Int = 512 * 1024
    ) async throws -> [String: Any] {
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.timeoutInterval = 20
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if let token { request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        if body != nil { request.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type") }
        for (key, value) in headers { request.setValue(value, forHTTPHeaderField: key) }
        request.httpBody = body
        let (bytes, response) = try await session.bytes(for: request)
        if response.url != url { throw AgentConnectionError.redirected }
        guard let http = response as? HTTPURLResponse else { throw AgentConnectionError.invalidResponse }
        if (300..<400).contains(http.statusCode) { throw AgentConnectionError.redirected }
        var data = Data()
        data.reserveCapacity(min(16 * 1024, maximumBytes))
        for try await byte in bytes {
            data.append(byte)
            if data.count > maximumBytes { throw AgentConnectionError.responseTooLarge }
        }
        guard (200..<300).contains(http.statusCode) else { throw AgentConnectionError.http(http.statusCode) }
        guard let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw AgentConnectionError.invalidResponse
        }
        return object
    }

    private static func booleanFeatures(from object: [String: Any]) throws -> [String: Bool] {
        guard let raw = object["features"] as? [String: Any] else { throw AgentConnectionError.invalidResponse }
        return raw.reduce(into: [:]) { result, pair in
            if let value = pair.value as? Bool { result[pair.key] = value }
            else if let value = pair.value as? NSNumber { result[pair.key] = value.boolValue }
        }
    }

    private final class RedirectDelegate: NSObject, URLSessionTaskDelegate {
        func urlSession(
            _ session: URLSession,
            task: URLSessionTask,
            willPerformHTTPRedirection response: HTTPURLResponse,
            newRequest request: URLRequest,
            completionHandler: @escaping (URLRequest?) -> Void
        ) {
            completionHandler(nil)
        }
    }
}
