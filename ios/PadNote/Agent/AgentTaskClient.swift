import Foundation
import CryptoKit

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
}

public struct AgentTaskClient {
    private let connectionClient: AgentConnectionClient
    private let session: URLSession
    private let sessionDelegate: URLSessionDelegate?

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
        let object = try await connectionClient.requestJSON(url: url, method: "POST", token: token, body: body)
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
        case .direct: payload = ["request_id": approvalID, "choice": decision]
        case .bridge: payload = ["approval_id": approvalID, "decision": decision]
        }
        let body = try JSONSerialization.data(withJSONObject: payload, options: [.sortedKeys])
        let object = try await connectionClient.requestJSON(url: url, method: "POST", token: token, body: body)
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
        if let rawStatus = object["status"] as? String {
            if rawStatus == "started" { status = .running }
            else if let parsed = AgentTaskStatus(rawValue: rawStatus) { status = parsed }
            else { throw AgentTaskError.invalidResponse }
        } else if let fallbackStatus { status = fallbackStatus }
        else { throw AgentTaskError.invalidResponse }
        let output = object["output"] as? String
        let error: String?
        if let value = object["error"] as? String { error = value }
        else if let value = object["error"] as? [String: Any] { error = value["message"] as? String }
        else { error = nil }
        let approval = parseApproval(object["approval"], transport: transport)
        let artifacts: [AgentTaskArtifact]
        if let raw = object["artifacts"], JSONSerialization.isValidJSONObject(raw),
           let data = try? JSONSerialization.data(withJSONObject: raw),
           let decoded = try? JSONDecoder().decode([AgentTaskArtifact].self, from: data) {
            artifacts = decoded
        } else { artifacts = [] }
        let lineageIsComplete = transport == .bridge && conversationID != nil && parentTaskID != nil
            && parentTaskID == expectedParentTaskID
            && (expectedConversationID == nil ? conversationID == id : conversationID == expectedConversationID)
        return AgentTaskRemoteState(remoteTaskID: id, status: status, output: output, error: error, approval: approval,
            artifacts: artifacts, conversationID: conversationID, parentTaskID: parentTaskID,
            followupAvailable: rawFollowupAvailable && status == .completed && lineageIsComplete
                && followupReason != nil,
            followupReason: followupReason)
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
            value.status = remote.status
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
