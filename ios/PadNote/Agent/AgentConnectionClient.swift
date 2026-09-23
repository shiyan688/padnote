import Foundation
import SwiftUI

public enum AgentConnectionError: Error, LocalizedError, Equatable {
    case openClawRequiresBridge, incomplete, httpsRequired, redirected, http(Int), invalidResponse, missingCapability(String), responseTooLarge
    public var errorDescription: String? {
        switch self { case .openClawRequiresBridge: return "OpenClaw 连接需要 Gateway WebSocket Bridge，当前版本暂未开启"; case .incomplete: return "请填写 Agent 地址和连接令牌"; case .httpsRequired: return "Agent 地址必须使用 HTTPS"; case .redirected: return "Agent 健康检查不允许重定向"; case .http(let s): return "Hermes 健康检查失败（HTTP \(s)）"; case .invalidResponse: return "响应不是 Hermes Agent capabilities"; case .missingCapability(let c): return "Hermes 缺少能力：\(c)"; case .responseTooLarge: return "Agent 响应过大" }
    }
}

public struct AgentConnectionClient {
    public static let requiredCapabilities = ["run_submission", "run_status", "run_events_sse", "run_stop", "run_approval_response"]
    private let session: URLSession
    private let redirectDelegate: RedirectDelegate?
    public init(session: URLSession? = nil) {
        if let session { self.session = session; self.redirectDelegate = nil }
        else { let delegate = RedirectDelegate(); self.redirectDelegate = delegate; let configuration = URLSessionConfiguration.ephemeral; configuration.httpCookieStorage = nil; configuration.urlCache = nil; configuration.requestCachePolicy = .reloadIgnoringLocalCacheData; self.session = URLSession(configuration: configuration, delegate: delegate, delegateQueue: nil) }
    }

    public func probe(_ config: AgentConnectionConfig) async throws -> String {
        guard config.complete else { throw AgentConnectionError.incomplete }
        guard config.kind == .hermes else { throw AgentConnectionError.openClawRequiresBridge }
        var endpoint = config.endpoint.trimmingCharacters(in: .whitespacesAndNewlines)
        while endpoint.hasSuffix("/") { endpoint.removeLast() }
        if !endpoint.hasSuffix("/v1/capabilities") { endpoint += "/v1/capabilities" }
        guard let url = URL(string: endpoint), url.scheme?.lowercased() == "https", let host = url.host, !host.isEmpty, url.user == nil, url.query == nil, url.fragment == nil else { throw AgentConnectionError.httpsRequired }
        var request = URLRequest(url: url); request.httpMethod = "GET"; request.timeoutInterval = 12
        request.setValue("Bearer \(config.token)", forHTTPHeaderField: "Authorization"); request.setValue("application/json", forHTTPHeaderField: "Accept")
        let (bytes, response) = try await session.bytes(for: request)
        if response.url != url { throw AgentConnectionError.redirected }
        guard let http = response as? HTTPURLResponse else { throw AgentConnectionError.invalidResponse }
        if (300..<400).contains(http.statusCode) { throw AgentConnectionError.redirected }
        guard (200..<300).contains(http.statusCode) else { throw AgentConnectionError.http(http.statusCode) }
        var data = Data(); data.reserveCapacity(16 * 1024)
        for try await byte in bytes { data.append(byte); if data.count > 512 * 1024 { throw AgentConnectionError.responseTooLarge } }
        guard let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any], object["object"] as? String == "hermes.api_server.capabilities", object["platform"] as? String == "hermes-agent", let features = object["features"] as? [String: Any] else { throw AgentConnectionError.invalidResponse }
        for name in Self.requiredCapabilities where (features[name] as? Bool) != true { throw AgentConnectionError.missingCapability(name) }
        return "Hermes 已连接"
    }

    private final class RedirectDelegate: NSObject, URLSessionTaskDelegate {
        func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse, newRequest: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) { completionHandler(nil) }
    }
}

public struct AgentSettingsView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var kind: AgentKind = .hermes
    @State private var endpoint = ""
    @State private var token = ""
    @State private var status: String?
    @State private var checking = false
    @State private var showingConnectionGuide = false
    @State private var didLoadStoredConnection = false
    private let store = AgentConnectionStore()
    public init() {}
    public var body: some View {
        NavigationStack { Form {
            Picker("Agent 类型", selection: $kind) { Text("Hermes（HTTPS）").tag(AgentKind.hermes); Text("OpenClaw（当前未支持）").tag(AgentKind.openClaw) }
            Section {
                Button { showingConnectionGuide = true } label: {
                    Label("如何连接另一台电脑？", systemImage: "questionmark.circle.fill")
                        .font(.headline)
                }
            } footer: {
                Text("本版仅支持连接测试；任务发送与状态回传尚未实现。")
            }
            Section("连接") { TextField("HTTPS 地址", text: $endpoint).textInputAutocapitalization(.never).autocorrectionDisabled(); SecureField("连接令牌", text: $token) }
            if let status { Text(status).foregroundStyle(.secondary) }
            Button(checking ? "正在检查…" : "保存并测试") { check() }.disabled(checking)
            if store.load().complete { Button("断开连接", role: .destructive) { store.clear(); endpoint = ""; token = ""; status = "已断开" } }
        }.navigationTitle("电脑 Agent").toolbar { ToolbarItem(placement: .confirmationAction) { Button("完成") { dismiss() } } }
            .onAppear {
                guard !didLoadStoredConnection else { return }
                didLoadStoredConnection = true
                let c = store.load(); kind = c.kind; endpoint = c.endpoint; token = c.token
            }
            .sheet(isPresented: $showingConnectionGuide) { AgentConnectionGuideView() }
        }
    }
    private func check() { checking = true; status = nil; Task { @MainActor in do { try store.save(kind: kind, endpoint: endpoint, token: token); let saved = store.load(); _ = try await AgentConnectionClient().probe(saved); let current = store.load(); guard current.kind == saved.kind && current.endpoint == saved.endpoint && current.token == saved.token else { throw AgentConnectionError.incomplete }; store.setConnected(true); status = "连接测试通过" } catch { store.setConnected(false); status = error.localizedDescription }; checking = false } }
}
