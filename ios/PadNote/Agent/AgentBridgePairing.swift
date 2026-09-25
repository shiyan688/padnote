import Foundation

public struct AgentPairingCode: Codable, Equatable, Sendable {
    public let type: String
    public let version: Int
    public let url: String
    public let bridgeID: String
    public let code: String

    enum CodingKeys: String, CodingKey {
        case type, version, url, code
        case bridgeID = "bridge_id"
    }

    public init(json: String) throws {
        guard let data = json.data(using: .utf8),
              let value = try? JSONDecoder().decode(Self.self, from: data),
              value.type == "padnote-pair", value.version == 1,
              AgentConnectionClient.validPathIdentifier(value.bridgeID), !value.code.isEmpty else {
            throw AgentPairingError.invalidCode
        }
        _ = try AgentConnectionClient.validatedBaseURL(value.url)
        self = value
    }

    public init(type: String = "padnote-pair", version: Int = 1, url: String, bridgeID: String, code: String) {
        self.type = type
        self.version = version
        self.url = url
        self.bridgeID = bridgeID
        self.code = code
    }
}

public struct AgentPairingRequest: Equatable, Sendable {
    public let requestID: String
    public let pollToken: String
    public let expiresAt: Date
}

public struct AgentPairingConnection: Codable, Equatable, Sendable {
    public let instanceID: String
    public let kind: AgentKind
    public let name: String
    public let token: String

    enum CodingKeys: String, CodingKey {
        case kind, name, token
        case instanceID = "instance_id"
    }
}

public enum AgentPairingClaim: Equatable, Sendable {
    case pending
    case approved(bridgeID: String, deviceID: String, connections: [AgentPairingConnection])
}

public enum AgentPairingError: Error, LocalizedError, Equatable {
    case invalidCode
    case redirected
    case denied
    case expired
    case http(Int)
    case invalidResponse
    case responseTooLarge

    public var errorDescription: String? {
        switch self {
        case .invalidCode: return "配对内容无效或已损坏"
        case .redirected: return "配对请求不允许重定向"
        case .denied: return "电脑已拒绝这次配对"
        case .expired: return "配对已过期，请在电脑上生成新二维码"
        case .http(let status): return "配对失败（HTTP \(status)）"
        case .invalidResponse: return "电脑返回了无效的配对响应"
        case .responseTooLarge: return "配对响应过大"
        }
    }
}

public final class AgentDeviceIdentity: @unchecked Sendable {
    private let defaults: UserDefaults
    private let key = "padnote.agent.device.id"

    public init(defaults: UserDefaults = .standard) { self.defaults = defaults }

    public func id() -> String {
        if let existing = defaults.string(forKey: key), UUID(uuidString: existing) != nil { return existing }
        let generated = UUID().uuidString.lowercased()
        defaults.set(generated, forKey: key)
        return generated
    }
}

public struct AgentBridgePairingClient {
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

    public func request(code: AgentPairingCode, deviceID: String, deviceName: String) async throws -> AgentPairingRequest {
        let body = try JSONSerialization.data(withJSONObject: [
            "code": code.code,
            "device_id": deviceID,
            "device_name": String(deviceName.prefix(128))
        ], options: [.sortedKeys])
        let response = try await request(
            base: code.url,
            path: "padnote/v1/pair/request",
            body: body,
            allowedStatuses: [202]
        )
        guard let requestID = response.object["request_id"] as? String, !requestID.isEmpty,
              let pollToken = response.object["poll_token"] as? String, !pollToken.isEmpty,
              response.object["status"] as? String == "pending",
              let expiresAt = Self.parseDate(response.object["expires_at"]) else {
            throw AgentPairingError.invalidResponse
        }
        return AgentPairingRequest(requestID: requestID, pollToken: pollToken, expiresAt: expiresAt)
    }

    public func claim(code: AgentPairingCode, request: AgentPairingRequest) async throws -> AgentPairingClaim {
        let body = try JSONSerialization.data(withJSONObject: [
            "request_id": request.requestID,
            "poll_token": request.pollToken
        ], options: [.sortedKeys])
        let response = try await self.request(
            base: code.url,
            path: "padnote/v1/pair/claim",
            body: body,
            allowedStatuses: [200, 202, 403, 410]
        )
        switch response.status {
        case 202: return .pending
        case 403: throw AgentPairingError.denied
        case 410: throw AgentPairingError.expired
        case 200:
            guard let bridgeID = response.object["bridge_id"] as? String,
                  bridgeID == code.bridgeID,
                  let deviceID = response.object["device_id"] as? String,
                  let rawConnections = response.object["connections"],
                  JSONSerialization.isValidJSONObject(rawConnections),
                  let data = try? JSONSerialization.data(withJSONObject: rawConnections),
                  let connections = try? JSONDecoder().decode([AgentPairingConnection].self, from: data),
                  !connections.isEmpty,
                  connections.allSatisfy({ AgentConnectionClient.validPathIdentifier($0.instanceID) && !$0.name.isEmpty && !$0.token.isEmpty && $0.kind == .hermes }) else {
                throw AgentPairingError.invalidResponse
            }
            return .approved(bridgeID: bridgeID, deviceID: deviceID, connections: connections)
        default: throw AgentPairingError.http(response.status)
        }
    }

    private func request(base: String, path: String, body: Data, allowedStatuses: Set<Int>) async throws -> (status: Int, object: [String: Any]) {
        let baseURL = try AgentConnectionClient.validatedBaseURL(base)
        let url = baseURL.appending(path: path)
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = 20
        request.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.httpBody = body
        let (bytes, response) = try await session.bytes(for: request)
        if response.url != url { throw AgentPairingError.redirected }
        guard let http = response as? HTTPURLResponse else { throw AgentPairingError.invalidResponse }
        if (300..<400).contains(http.statusCode) { throw AgentPairingError.redirected }
        var data = Data()
        for try await byte in bytes {
            data.append(byte)
            if data.count > 256 * 1024 { throw AgentPairingError.responseTooLarge }
        }
        guard allowedStatuses.contains(http.statusCode) else { throw AgentPairingError.http(http.statusCode) }
        guard let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw AgentPairingError.invalidResponse
        }
        return (http.statusCode, object)
    }

    private static func parseDate(_ value: Any?) -> Date? {
        if let seconds = value as? NSNumber { return Date(timeIntervalSince1970: seconds.doubleValue) }
        guard let string = value as? String else { return nil }
        let formatter = ISO8601DateFormatter()
        return formatter.date(from: string)
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
