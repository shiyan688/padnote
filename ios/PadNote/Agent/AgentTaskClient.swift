import Foundation
import CryptoKit
import CoreFoundation

enum AgentTaskStopDelivery: String, Sendable {
    case queued, sending, unknown, confirmed, rejected, notSent = "not_sent", superseded

    var permitsResume: Bool { self == .rejected || self == .notSent || self == .superseded }
}

public struct AgentTaskRemoteState: Equatable, Sendable {
    public let remoteTaskID: String
    public let status: AgentTaskStatus
    public let output: String?
    public let error: String?
    public let approval: AgentTaskApproval?
    public let artifacts: [AgentTaskArtifact]
    public let conversationID: String?
    public let parentTaskID: String?
    public let followupAvailable: Bool
    public let followupReason: String?
    let stopDelivery: AgentTaskStopDelivery?

    init(remoteTaskID: String, status: AgentTaskStatus, output: String?, error: String?,
         approval: AgentTaskApproval?, artifacts: [AgentTaskArtifact], conversationID: String?,
         parentTaskID: String?, followupAvailable: Bool, followupReason: String?,
         stopDelivery: AgentTaskStopDelivery? = nil) {
        self.remoteTaskID = remoteTaskID; self.status = status; self.output = output; self.error = error
        self.approval = approval; self.artifacts = artifacts; self.conversationID = conversationID
        self.parentTaskID = parentTaskID; self.followupAvailable = followupAvailable
        self.followupReason = followupReason; self.stopDelivery = stopDelivery
    }
}

public struct AgentTaskClient {
    private let connectionClient: AgentConnectionClient
    private let session: URLSession
    private let sessionDelegate: URLSessionDelegate?

    static func encodeApprovalToken(promptID: String, generation: Int64) throws -> String {
        guard generation > 0, generation <= 9_007_199_254_740_991,
              !promptID.isEmpty, promptID == promptID.trimmingCharacters(in: .whitespacesAndNewlines),
              !promptID.unicodeScalars.contains(where: { (0...31).contains($0.value) || (127...159).contains($0.value) }) else {
            throw AgentTaskError.invalidPayload
        }
        let token = "pc1:\(generation):\(promptID)"
        guard token.utf8.count <= 256 else { throw AgentTaskError.invalidPayload }
        return token
    }

    static func decodeApprovalToken(_ token: String) throws -> (String, Int64) {
        guard token.utf8.count <= 256 else { throw AgentTaskError.invalidResponse }
        let parts = token.split(separator: ":", maxSplits: 2, omittingEmptySubsequences: false)
        guard parts.count == 3, parts[0] == "pc1", !parts[1].isEmpty,
              parts[1].utf8.allSatisfy({ (48...57).contains($0) }),
              parts[1].first != "0", let generation = Int64(parts[1]), generation > 0,
              generation <= 9_007_199_254_740_991 else {
            throw AgentTaskError.invalidResponse
        }
        let promptID = String(parts[2])
        guard try encodeApprovalToken(promptID: promptID, generation: generation) == token else {
            throw AgentTaskError.invalidResponse
        }
        return (promptID, generation)
    }

    static func parseDirectApproval(object: [String: Any], status: AgentTaskStatus) -> (AgentTaskApproval?, String?) {
        guard status == .waitingForApproval else { return (nil, nil) }
        let fallback = "无法验证这条审批的身份，请在电脑端处理或更新 Hermes。"

        func approval(from source: [String: Any], id: String) -> (AgentTaskApproval?, Bool) {
            func meaningful(_ value: Any) -> Bool {
                if value is NSNull { return false }
                if let string = value as? String { return !string.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
                if let dictionary = value as? [String: Any] { return dictionary.values.contains(where: meaningful) }
                if let array = value as? [Any] { return array.contains(where: meaningful) }
                return true
            }
            let title = String((source["title"] as? String ?? source["tool_name"] as? String ?? "需要批准").prefix(256))
            var details = [String]()
            for key in ["description", "command", "reason"] {
                guard let raw = source[key], meaningful(raw) else { continue }
                let rendered = raw as? String ?? String(describing: raw)
                if !rendered.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    details.append("\(key): \(rendered)")
                }
            }
            if details.isEmpty {
                let identityKeys: Set<String> = ["kind", "prompt_id", "request_id", "execution_generation", "session_id", "admission_id", "approval_id", "run_id", "title", "tool_name", "tool"]
                let readable = source.filter { !identityKeys.contains($0.key) && meaningful($0.value) }
                if !readable.isEmpty,
                   let data = try? JSONSerialization.data(withJSONObject: readable, options: [.sortedKeys, .fragmentsAllowed, .withoutEscapingSlashes]),
                   let json = String(data: data, encoding: .utf8), !json.isEmpty {
                    details = [json]
                }
            }
            let description = details.joined(separator: "\n")
            guard !description.isEmpty, description.utf16.count <= 2000 else { return (nil, true) }
            return (AgentTaskApproval(id: id, title: title, description: description), false)
        }

        let generationNumber = object["execution_generation"] as? NSNumber
        let generationValid = generationNumber.map {
            CFGetTypeID($0) != CFBooleanGetTypeID() && $0.doubleValue.rounded(.towardZero) == $0.doubleValue
                && String(cString: $0.objCType) != "f" && String(cString: $0.objCType) != "d"
                && $0.int64Value > 0 && $0.int64Value <= 9_007_199_254_740_991
        } ?? false
        if let controls = object["pending_controls"] as? [[String: Any]], controls.count <= 128,
           generationValid, let generation = generationNumber?.int64Value {
            for control in controls where control["kind"] as? String == "approval" {
                guard let promptID = control["prompt_id"] as? String,
                      (try? encodeApprovalToken(promptID: promptID, generation: generation)) != nil else { continue }
                if let bound = control["execution_generation"] {
                    guard let number = bound as? NSNumber, CFGetTypeID(number) != CFBooleanGetTypeID(),
                          String(cString: number.objCType) != "f" && String(cString: number.objCType) != "d",
                          number.doubleValue.rounded(.towardZero) == number.doubleValue,
                          number.int64Value == generation else { continue }
                }
                let payload = control["payload"] as? [String: Any] ?? control
                let token = try! encodeApprovalToken(promptID: promptID, generation: generation)
                let (parsed, oversized) = approval(from: payload, id: token)
                if oversized { return (nil, fallback) }
                if let parsed { return (parsed, nil) }
            }
        }
        if object["approval"] is [String: Any] { return (nil, fallback) }
        return (nil, object["pending_controls"] == nil ? nil : fallback)
    }

    public init(session: URLSession? = nil, pin: String? = nil) {
        if let session {
            self.session = session
            self.sessionDelegate = nil
            self.connectionClient = AgentConnectionClient(session: session)
        } else {
            let (resolved, delegate) = AgentCertificatePin.session(pin: pin)
            self.session = resolved
            self.sessionDelegate = delegate
            self.connectionClient = AgentConnectionClient(session: resolved)
        }
    }

    /// A client whose session trusts the certificate this task's connection was paired with.
    public static func forTask(_ task: AgentTaskRecord) -> AgentTaskClient {
        AgentTaskClient(pin: task.connection.certSHA256)
    }

    public func submit(task: AgentTaskRecord, token: String) async throws -> AgentTaskRemoteState {
        let url = try endpoint(for: task, suffix: "runs")
        let body: Data
        switch task.connection.transport {
        case .direct:
            guard task.payload.bundleBase64 == nil else { throw AgentTaskError.capabilityUnavailable("task_bundle") }
            body = try JSONSerialization.data(withJSONObject: ["input": task.payload.input], options: [.sortedKeys])
        case .bridge:
            body = task.payload.canonicalData
            guard body.count <= 12 * 1024 * 1024 else { throw AgentTaskError.bundleTooLarge }
        }
        let object = try await connectionClient.requestJSON(
            url: url,
            method: "POST",
            token: token,
            body: body,
            headers: ["Idempotency-Key": task.payload.clientTaskID],
            maximumBytes: 1024 * 1024
        )
        let idKey = task.connection.transport == .bridge ? "task_id" : "run_id"
        guard let remoteID = object[idKey] as? String, !remoteID.isEmpty else { throw AgentTaskError.invalidResponse }
        return try Self.state(from: object, fallbackID: remoteID, fallbackStatus: .running, transport: task.connection.transport, expectedInstanceID: task.connection.instanceID, expectedParentTaskID: task.payload.parentTaskID ?? "", expectedConversationID: task.conversationID)
    }

    public func status(task: AgentTaskRecord, token: String) async throws -> AgentTaskRemoteState {
        guard let remoteID = task.remoteTaskID else { throw AgentTaskError.invalidResponse }
        let url = try endpoint(for: task, suffix: "runs/\(remoteID)")
        let object = try await connectionClient.requestJSON(url: url, method: "GET", token: token, maximumBytes: 2 * 1024 * 1024)
        return try Self.state(from: object, fallbackID: remoteID, fallbackStatus: nil, transport: task.connection.transport, expectedInstanceID: task.connection.instanceID, expectedParentTaskID: task.payload.parentTaskID ?? "", expectedConversationID: task.conversationID)
    }

    public func stop(task: AgentTaskRecord, token: String) async throws -> AgentTaskRemoteState {
        guard let remoteID = task.remoteTaskID else { throw AgentTaskError.invalidResponse }
        let url = try endpoint(for: task, suffix: "runs/\(remoteID)/stop")
        let body = try JSONSerialization.data(withJSONObject: [:])
        let headers = task.connection.transport == .bridge
            ? ["X-PadNote-Control-Receipt": "1"] : [:]
        let object = try await connectionClient.requestJSON(url: url, method: "POST", token: token, body: body,
                                                            headers: headers)
        return try Self.state(from: object, fallbackID: remoteID, fallbackStatus: .stopping, transport: task.connection.transport, expectedInstanceID: task.connection.instanceID, expectedParentTaskID: task.payload.parentTaskID ?? "", expectedConversationID: task.conversationID)
    }

    public func approve(task: AgentTaskRecord, approvalID: String, decision: String, token: String) async throws -> AgentTaskRemoteState {
        guard decision == "once" || decision == "deny",
              !approvalID.isEmpty,
              approvalID == task.approval?.id,
              let remoteID = task.remoteTaskID else { throw AgentTaskError.invalidPayload }
        let url = try endpoint(for: task, suffix: "runs/\(remoteID)/approval")
        let payload: [String: Any]
        switch task.connection.transport {
        case .direct:
            let (promptID, generation) = try Self.decodeApprovalToken(approvalID)
            payload = ["request_id": promptID, "execution_generation": generation, "choice": decision]
        case .bridge: payload = ["approval_id": approvalID, "decision": decision]
        }
        let body = try JSONSerialization.data(withJSONObject: payload, options: [.sortedKeys])
        let headers = task.connection.transport == .bridge
            ? ["X-PadNote-Control-Receipt": "1"] : [:]
        let object = try await connectionClient.requestJSON(url: url, method: "POST", token: token, body: body,
                                                            headers: headers)
        return try Self.state(from: object, fallbackID: remoteID, fallbackStatus: .running, transport: task.connection.transport, expectedInstanceID: task.connection.instanceID, expectedParentTaskID: task.payload.parentTaskID ?? "", expectedConversationID: task.conversationID)
    }

    public func downloadArtifact(task: AgentTaskRecord, artifact: AgentTaskArtifact, token: String) async throws -> URL {
        guard task.connection.transport == .bridge,
              let remoteID = task.remoteTaskID,
              task.artifacts.contains(artifact),
              artifact.sizeBytes >= 0, artifact.sizeBytes <= 100 * 1024 * 1024,
              Self.safeIdentifier(artifact.id) else {
            throw AgentTaskError.artifactInvalid
        }
        let url = try endpoint(for: task, suffix: "runs/\(remoteID)/artifacts/\(artifact.id)")
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.timeoutInterval = 60
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        let (bytes, response) = try await session.bytes(for: request)
        guard response.url == url, let http = response as? HTTPURLResponse,
              (200..<300).contains(http.statusCode) else { throw AgentTaskError.invalidResponse }
        let safeName = Self.safeArtifactName(artifact.name)
        let destination = FileManager.default.temporaryDirectory
            .appendingPathComponent("PadNote-Agent-\(UUID().uuidString)-\(safeName)")
        FileManager.default.createFile(atPath: destination.path, contents: nil)
        do {
            let handle = try FileHandle(forWritingTo: destination)
            defer { try? handle.close() }
            var hasher = SHA256()
            var buffer = Data()
            buffer.reserveCapacity(64 * 1024)
            var count = 0
            for try await byte in bytes {
                buffer.append(byte)
                count += 1
                if count > 100 * 1024 * 1024 || count > artifact.sizeBytes { throw AgentTaskError.artifactInvalid }
                if buffer.count == 64 * 1024 {
                    try handle.write(contentsOf: buffer)
                    hasher.update(data: buffer)
                    buffer.removeAll(keepingCapacity: true)
                }
            }
            if !buffer.isEmpty {
                try handle.write(contentsOf: buffer)
                hasher.update(data: buffer)
            }
            guard count == artifact.sizeBytes,
                  hasher.finalize().map({ String(format: "%02x", $0) }).joined() == artifact.sha256.lowercased() else {
                throw AgentTaskError.artifactInvalid
            }
            return destination
        } catch {
            try? FileManager.default.removeItem(at: destination)
            throw error
        }
    }

    private func endpoint(for task: AgentTaskRecord, suffix: String) throws -> URL {
        let base = try AgentConnectionClient.validatedBaseURL(task.connection.endpoint)
        switch task.connection.transport {
        case .direct:
            let v1Base: URL
            if base.path.hasSuffix("/v1/capabilities") {
                v1Base = base.deletingLastPathComponent()
            } else if base.path.hasSuffix("/v1") {
                v1Base = base
            } else {
                v1Base = base.appending(path: "v1")
            }
            return try Self.appendingSafeSuffix(suffix, to: v1Base)
        case .bridge:
            guard let instanceID = task.connection.instanceID, Self.safeIdentifier(instanceID) else { throw AgentTaskError.connectionChanged }
            let root = base.appending(path: "padnote/v1/agents").appending(path: instanceID)
            return try Self.appendingSafeSuffix(suffix, to: root)
        }
    }

    private static func state(
        from object: [String: Any],
        fallbackID: String,
        fallbackStatus: AgentTaskStatus?,
        transport: AgentTransport,
        expectedInstanceID: String?,
        expectedParentTaskID: String? = nil,
        expectedConversationID: String? = nil
    ) throws -> AgentTaskRemoteState {
        let id = (object[transport == .bridge ? "task_id" : "run_id"] as? String) ?? fallbackID
        guard id == fallbackID || fallbackID.isEmpty else { throw AgentTaskError.invalidResponse }
        if transport == .bridge {
            guard let instanceID = object["instance_id"] as? String,
                  instanceID == expectedInstanceID else { throw AgentTaskError.invalidResponse }
        }
        let conversationID = object["conversation_id"] as? String
        let parentTaskID = object["parent_task_id"] as? String
        if transport == .bridge {
            if let expectedParentTaskID, let parentTaskID, parentTaskID != expectedParentTaskID { throw AgentTaskError.invalidResponse }
            if expectedParentTaskID?.isEmpty == true, let parentTaskID, !parentTaskID.isEmpty { throw AgentTaskError.invalidResponse }
            if let expectedConversationID, let conversationID, conversationID != expectedConversationID { throw AgentTaskError.invalidResponse }
            if expectedParentTaskID?.isEmpty == false,
               parentTaskID != expectedParentTaskID || conversationID == nil || conversationID != expectedConversationID {
                throw AgentTaskError.invalidResponse
            }
            if expectedParentTaskID?.isEmpty == true, let conversationID, conversationID != id { throw AgentTaskError.invalidResponse }
        }
        let rawFollowupAvailable = object["followup_available"] as? Bool ?? false
        let followupReason = object["followup_reason"] as? String
        let status: AgentTaskStatus
        var normalizationMessage: String?
        if let rawStatus = object["status"] as? String {
            if rawStatus == "started" || rawStatus == "queued" || rawStatus == "unknown" { status = .running }
            else if let parsed = AgentTaskStatus(rawValue: rawStatus) { status = parsed }
            else { throw AgentTaskError.invalidResponse }
            if rawStatus == "queued" { normalizationMessage = "任务正在排队等待运行。" }
            if rawStatus == "unknown" { normalizationMessage = "任务结果尚未确认，请在电脑端核对状态，不要重新发送。" }
        } else if let fallbackStatus { status = fallbackStatus }
        else { throw AgentTaskError.invalidResponse }
        let stopDelivery: AgentTaskStopDelivery?
        if transport == .bridge, let rawDelivery = object["stop_delivery"] as? String {
            guard let parsed = AgentTaskStopDelivery(rawValue: rawDelivery) else { throw AgentTaskError.invalidResponse }
            stopDelivery = parsed
        } else {
            stopDelivery = nil
        }
        let output = object["output"] as? String
        let error: String?
        if let value = object["error"] as? String { error = value }
        else if let value = object["error"] as? [String: Any] { error = value["message"] as? String }
        else { error = nil }
        let normalizedApproval: AgentTaskApproval?
        if transport == .direct {
            let (parsedApproval, approvalMessage) = parseDirectApproval(object: object, status: status)
            normalizedApproval = parsedApproval
            if let approvalMessage { normalizationMessage = approvalMessage }
        } else {
            normalizedApproval = parseApproval(object["approval"], transport: transport)
        }
        let finalError = [error, normalizationMessage].compactMap { $0 }.filter { !$0.isEmpty }
            .joined(separator: "\n")
        let artifacts: [AgentTaskArtifact]
        if let raw = object["artifacts"], JSONSerialization.isValidJSONObject(raw),
           let data = try? JSONSerialization.data(withJSONObject: raw),
           let decoded = try? JSONDecoder().decode([AgentTaskArtifact].self, from: data) {
            artifacts = decoded
        } else { artifacts = [] }
        let lineageIsComplete = transport == .bridge && conversationID != nil && parentTaskID != nil
            && parentTaskID == expectedParentTaskID
            && (expectedConversationID == nil ? conversationID == id : conversationID == expectedConversationID)
        return AgentTaskRemoteState(remoteTaskID: id, status: status, output: output,
            error: finalError.isEmpty ? nil : finalError, approval: normalizedApproval,
            artifacts: artifacts, conversationID: conversationID, parentTaskID: parentTaskID,
            followupAvailable: rawFollowupAvailable && status == .completed && lineageIsComplete
                && followupReason != nil,
            followupReason: followupReason, stopDelivery: stopDelivery)
    }

    private static func parseApproval(_ raw: Any?, transport: AgentTransport) -> AgentTaskApproval? {
        guard let object = raw as? [String: Any] else { return nil }
        let key = transport == .direct ? "request_id" : "approval_id"
        guard let id = object[key] as? String, !id.isEmpty else { return nil }
        let title = object["title"] as? String
            ?? object["tool"] as? String
            ?? "需要批准"
        let details = [object["description"] as? String, object["reason"] as? String, object["command"] as? String]
            .compactMap { $0?.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
            .joined(separator: "\n")
        guard !details.isEmpty else { return nil }
        return AgentTaskApproval(id: id, title: title, description: details)
    }

    private static func safeArtifactName(_ value: String) -> String {
        let leaf = URL(fileURLWithPath: value).lastPathComponent
        let safe = leaf.unicodeScalars.map { scalar -> Character in
            CharacterSet.alphanumerics.contains(scalar) || ".-_ ".unicodeScalars.contains(scalar) ? Character(String(scalar)) : "_"
        }
        return String(safe).isEmpty ? "artifact" : String(safe)
    }

    private static func safeIdentifier(_ value: String) -> Bool {
        guard !value.isEmpty, value.count <= 200, value != ".", value != ".." else { return false }
        return value.unicodeScalars.allSatisfy {
            CharacterSet.alphanumerics.contains($0) || "-_.".unicodeScalars.contains($0)
        }
    }

    private static func appendingSafeSuffix(_ suffix: String, to base: URL) throws -> URL {
        let components = suffix.split(separator: "/", omittingEmptySubsequences: false).map(String.init)
        guard !components.isEmpty, components.allSatisfy(safeIdentifier) else { throw AgentTaskError.invalidResponse }
        return components.reduce(base) { $0.appendingPathComponent($1, isDirectory: false) }
    }
}

public final class AgentTaskService: @unchecked Sendable {
    private let connectionStore: AgentConnectionStore
    private let taskStore: AgentTaskStore
    private let injectedClient: AgentTaskClient?

    /// `client` is for tests; production resolves a client per task so a
    /// same-network connection's certificate pin travels with its requests.
    public init(
        connectionStore: AgentConnectionStore = AgentConnectionStore(),
        taskStore: AgentTaskStore = AgentTaskStore(),
        client: AgentTaskClient? = nil
    ) {
        self.connectionStore = connectionStore
        self.taskStore = taskStore
        self.injectedClient = client
    }

    private func client(for task: AgentTaskRecord) -> AgentTaskClient {
        injectedClient ?? AgentTaskClient.forTask(task)
    }

    @discardableResult
    public func create(connectionID: UUID, payload: AgentTaskPayload) throws -> AgentTaskRecord {
        guard let profile = connectionStore.profile(id: connectionID), profile.connected else {
            throw AgentTaskError.connectionUnavailable
        }
        let isBuiltinVideo = profile.kind == .builtinVideo
        if isBuiltinVideo {
            guard payload.bundleBase64 != nil, profile.transport == .bridge,
                  profile.capabilities["video_task_submission"] == true else {
                throw AgentTaskError.capabilityUnavailable("video_task_submission")
            }
        } else {
            guard profile.kind == .hermes else { throw AgentTaskError.capabilityUnavailable("OpenClaw") }
            guard profile.capabilities["run_submission"] == true else { throw AgentTaskError.capabilityUnavailable("run_submission") }
        }
        if payload.bundleBase64 != nil {
            guard profile.transport == .bridge, profile.capabilities["task_bundle"] == true else {
                throw AgentTaskError.capabilityUnavailable("task_bundle")
            }
        }
        try Self.requirePaperContextCapability(payload, profile: profile)
        return try taskStore.create(profile: profile, payload: payload)
    }

    /// Atomically saves the follow-up round before sending it. Store-level parent reservation
    /// makes another composer or a reopened screen reuse the same immutable child request.
    public func createFollowup(parentID: UUID, input: String) async throws -> AgentTaskRecord {
        let parent = try requireTask(parentID)
        guard parent.connection.transport == .bridge else { throw AgentTaskError.capabilityUnavailable("Bridge追问") }
        guard let profile = connectionStore.profile(id: parent.connection.connectionID) else { throw AgentTaskError.connectionUnavailable }
        guard parent.connection.stillMatches(profile) else { throw AgentTaskError.connectionChanged }
        let child = try taskStore.createFollowup(parentID: parentID, profile: profile, input: input)
        if child.status != .submitting { return child }
        return try await submit(id: child.id)
    }

    public func submit(id: UUID) async throws -> AgentTaskRecord {
        let task = try requireTask(id)
        if task.payload.requiredCapability != nil {
            guard let profile = connectionStore.profile(id: task.connection.connectionID),
                  task.connection.stillMatches(profile) else { throw AgentTaskError.connectionChanged }
            guard profile.connected else { throw AgentTaskError.connectionChanged }
            try Self.requirePaperContextCapability(task.payload, profile: profile)
        }
        let token = try credential(for: task)
        let remote = try await client(for: task).submit(task: task, token: token)
        return try apply(remote, to: task)
    }

    public func refresh(id: UUID) async throws -> AgentTaskRecord {
        let task = try requireTask(id)
        guard hasCapability("run_status", for: task) else {
            throw AgentTaskError.capabilityUnavailable("run_status")
        }
        let token = try credential(for: task)
        let remote = try await client(for: task).status(task: task, token: token)
        return try apply(remote, to: task)
    }

    public func stop(id: UUID) async throws -> AgentTaskRecord {
        let task = try requireTask(id)
        let token = try credential(for: task)
        let remote = try await client(for: task).stop(task: task, token: token)
        return try apply(remote, to: task)
    }

    public func approve(id: UUID, approvalID: String, decision: String) async throws -> AgentTaskRecord {
        let task = try requireTask(id)
        let token = try credential(for: task)
        let remote = try await client(for: task).approve(task: task, approvalID: approvalID, decision: decision, token: token)
        return try apply(remote, to: task)
    }

    public func download(id: UUID, artifact: AgentTaskArtifact) async throws -> URL {
        let task = try requireTask(id)
        guard hasCapability("artifacts", for: task) else {
            throw AgentTaskError.capabilityUnavailable("artifacts")
        }
        return try await client(for: task).downloadArtifact(task: task, artifact: artifact, token: credential(for: task))
    }

    private func hasCapability(_ name: String, for task: AgentTaskRecord) -> Bool {
        guard let profile = connectionStore.profile(id: task.connection.connectionID),
              profile.connected, task.connection.stillMatches(profile) else { return false }
        return profile.capabilities[name] == true
    }

    private static func requirePaperContextCapability(_ payload: AgentTaskPayload, profile: AgentConnectionProfile) throws {
        guard let required = payload.requiredCapability else { return }
        guard required == "note_context_bundle", payload.bundleBase64 != nil, profile.connected,
              profile.kind == .hermes, profile.transport == .bridge,
              profile.capabilities["task_bundle"] == true, profile.capabilities[required] == true,
              profile.capabilities["builtinVideo"] != true, profile.capabilities["builtin_video"] != true else {
            throw AgentTaskError.capabilityUnavailable(required)
        }
    }

    private func requireTask(_ id: UUID) throws -> AgentTaskRecord {
        guard let task = try taskStore.task(id: id) else { throw AgentTaskError.taskNotFound }
        return task
    }

    private func credential(for task: AgentTaskRecord) throws -> String {
        guard let current = connectionStore.profile(id: task.connection.connectionID) else {
            throw AgentTaskError.connectionUnavailable
        }
        guard task.connection.stillMatches(current) else { throw AgentTaskError.connectionChanged }
        guard let token = connectionStore.token(reference: task.connection.credentialReference), !token.isEmpty else {
            throw AgentTaskError.connectionUnavailable
        }
        return token
    }

    private func apply(_ remote: AgentTaskRemoteState, to task: AgentTaskRecord) throws -> AgentTaskRecord {
        _ = try credential(for: task)
        return try taskStore.mutate(id: task.id, expectedRevision: task.recordRevision) { value in
            if let existing = value.remoteTaskID, existing != remote.remoteTaskID { throw AgentTaskError.invalidResponse }
            let wasTerminal = value.status.terminal
            if wasTerminal && remote.status != value.status { throw AgentTaskError.invalidResponse }
            value.remoteTaskID = remote.remoteTaskID
            if let conversationID = remote.conversationID { value.conversationID = conversationID }
            if value.status == .stopping && remote.status == .running
                && !(remote.stopDelivery?.permitsResume ?? false) {
                // Keep an uncertain or accepted stop pending until a later status confirms otherwise.
            } else {
                value.status = remote.status
            }
            value.followupAvailable = value.connection.transport == .bridge && remote.followupAvailable
            if let reason = remote.followupReason { value.followupReason = reason }
            if wasTerminal {
                if value.output?.isEmpty != false, let output = remote.output, !output.isEmpty { value.output = output }
                if value.artifacts.isEmpty && !remote.artifacts.isEmpty { value.artifacts = remote.artifacts }
            } else {
                value.output = remote.output
                value.error = remote.error
                value.approval = remote.approval
                value.artifacts = remote.artifacts
            }
        }
    }
}
