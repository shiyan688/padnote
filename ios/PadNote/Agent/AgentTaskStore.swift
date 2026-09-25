import Foundation
import CryptoKit

public enum AgentTaskStatus: String, Codable, CaseIterable, Sendable {
    case submitting
    case running
    case waitingForApproval = "waiting_for_approval"
    case stopping
    case completed
    case failed
    case cancelled
    case interrupted

    public var terminal: Bool {
        switch self {
        case .completed, .failed, .cancelled, .interrupted: return true
        default: return false
        }
    }
}

public struct AgentTaskSource: Codable, Equatable, Sendable {
    public let noteID: String
    public let noteRevision: Int

    enum CodingKeys: String, CodingKey {
        case noteID = "note_id"
        case noteRevision = "note_revision"
    }

    public init(noteID: String, noteRevision: Int) {
        self.noteID = noteID
        self.noteRevision = noteRevision
    }
}

public struct AgentTaskPayload: Codable, Equatable, Sendable {
    public let clientTaskID: String
    public let title: String
    public let input: String
    public let source: AgentTaskSource
    public let bundleBase64: String?
    public let bundleSHA256: String?

    enum CodingKeys: String, CodingKey {
        case clientTaskID = "client_task_id"
        case title, input, source
        case bundleBase64 = "bundle_base64"
        case bundleSHA256 = "bundle_sha256"
    }

    public init(
        clientTaskID: String = UUID().uuidString.lowercased(),
        title: String,
        input: String,
        source: AgentTaskSource,
        bundle: Data? = nil
    ) throws {
        let normalizedTitle = title.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !clientTaskID.isEmpty, !normalizedTitle.isEmpty, normalizedTitle.count <= 256,
              !input.isEmpty, Data(input.utf8).count <= 128 * 1024,
              !source.noteID.isEmpty else { throw AgentTaskError.invalidPayload }
        if let bundle, bundle.count > 8 * 1024 * 1024 { throw AgentTaskError.bundleTooLarge }
        self.clientTaskID = clientTaskID
        self.title = normalizedTitle
        self.input = input
        self.source = source
        self.bundleBase64 = bundle?.base64EncodedString()
        self.bundleSHA256 = bundle.map { SHA256.hash(data: $0).hex }
    }

    public var canonicalData: Data {
        (try? JSONEncoder.sorted.encode(self)) ?? Data()
    }
}

public struct AgentTaskConnectionIdentity: Codable, Equatable, Sendable {
    public let connectionID: UUID
    public let connectionRevision: Int
    public let connectionName: String
    public let kind: AgentKind
    public let endpoint: String
    public let credentialReference: String
    public let transport: AgentTransport
    public let bridgeID: String?
    public let instanceID: String?

    public init(profile: AgentConnectionProfile) {
        connectionID = profile.id
        connectionRevision = profile.revision
        connectionName = profile.name
        kind = profile.kind
        endpoint = profile.endpoint
        credentialReference = profile.credentialReference
        transport = profile.transport
        bridgeID = profile.bridgeID
        instanceID = profile.instanceID
    }

    public func stillMatches(_ profile: AgentConnectionProfile) -> Bool {
        connectionID == profile.id
            && connectionRevision == profile.revision
            && kind == profile.kind
            && endpoint == profile.endpoint
            && credentialReference == profile.credentialReference
            && transport == profile.transport
            && bridgeID == profile.bridgeID
            && instanceID == profile.instanceID
    }
}

public struct AgentTaskApproval: Codable, Equatable, Sendable {
    public let id: String
    public let title: String
    public let description: String
}

public struct AgentTaskArtifact: Codable, Equatable, Identifiable, Sendable {
    public let id: String
    public let name: String
    public let mediaType: String
    public let sizeBytes: Int
    public let sha256: String

    enum CodingKeys: String, CodingKey {
        case id, name, sha256
        case mediaType = "media_type"
        case sizeBytes = "size_bytes"
    }
}

public struct AgentTaskRecord: Codable, Equatable, Identifiable, Sendable {
    public let id: UUID
    public let createdAt: Date
    public var updatedAt: Date
    public let connection: AgentTaskConnectionIdentity
    public let payload: AgentTaskPayload
    public let payloadSHA256: String
    public var remoteTaskID: String?
    public var status: AgentTaskStatus
    public var output: String?
    public var error: String?
    public var approval: AgentTaskApproval?
    public var artifacts: [AgentTaskArtifact]
    public var recordRevision: Int

    public init(profile: AgentConnectionProfile, payload: AgentTaskPayload, now: Date = Date()) {
        id = UUID(uuidString: payload.clientTaskID) ?? UUID()
        createdAt = now
        updatedAt = now
        connection = AgentTaskConnectionIdentity(profile: profile)
        self.payload = payload
        payloadSHA256 = SHA256.hash(data: payload.canonicalData).hex
        remoteTaskID = nil
        status = .submitting
        output = nil
        error = nil
        approval = nil
        artifacts = []
        recordRevision = 1
    }

    init(
        id: UUID,
        createdAt: Date,
        updatedAt: Date,
        connection: AgentTaskConnectionIdentity,
        payload: AgentTaskPayload,
        payloadSHA256: String,
        remoteTaskID: String?,
        status: AgentTaskStatus,
        output: String?,
        error: String?,
        approval: AgentTaskApproval?,
        artifacts: [AgentTaskArtifact],
        recordRevision: Int
    ) {
        self.id = id
        self.createdAt = createdAt
        self.updatedAt = updatedAt
        self.connection = connection
        self.payload = payload
        self.payloadSHA256 = payloadSHA256
        self.remoteTaskID = remoteTaskID
        self.status = status
        self.output = output
        self.error = error
        self.approval = approval
        self.artifacts = artifacts
        self.recordRevision = recordRevision
    }
}

public enum AgentTaskError: Error, LocalizedError, Equatable {
    case invalidPayload
    case bundleTooLarge
    case connectionUnavailable
    case connectionChanged
    case capabilityUnavailable(String)
    case taskNotFound
    case staleTask
    case corruptStore
    case persistence
    case invalidResponse
    case artifactInvalid

    public var errorDescription: String? {
        switch self {
        case .invalidPayload: return "任务内容无效或超过限制"
        case .bundleTooLarge: return "任务包超过 8 MiB"
        case .connectionUnavailable: return "连接已删除或凭据不可用"
        case .connectionChanged: return "连接身份已变化，请新建任务并重新确认"
        case .capabilityUnavailable(let value): return "此连接不支持：\(value)"
        case .taskNotFound: return "任务记录不存在"
        case .staleTask: return "任务状态已更新"
        case .corruptStore: return "任务记录损坏，原文件已保留"
        case .persistence: return "无法保存任务记录"
        case .invalidResponse: return "电脑返回了无效任务响应"
        case .artifactInvalid: return "产物大小或摘要校验失败"
        }
    }
}

public final class AgentTaskStore: @unchecked Sendable {
    private struct ImmutableRecord: Codable {
        let schemaVersion: Int
        let id: UUID
        let createdAt: Date
        let connection: AgentTaskConnectionIdentity
        let payload: AgentTaskPayload
        let payloadSHA256: String
    }

    private struct MutableState: Codable {
        let schemaVersion: Int
        var updatedAt: Date
        var remoteTaskID: String?
        var status: AgentTaskStatus
        var output: String?
        var error: String?
        var approval: AgentTaskApproval?
        var artifacts: [AgentTaskArtifact]
        var recordRevision: Int
    }

    private static let lockRegistry = NSLock()
    private static var pathLocks = [String: NSLock]()
    private let rootURL: URL
    private let lock: NSLock

    public init(fileURL: URL? = nil) {
        let resolved: URL
        if let fileURL { resolved = fileURL }
        else {
            let root = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
                .appendingPathComponent("PadNote", isDirectory: true)
            resolved = root.appendingPathComponent("agent-tasks", isDirectory: true)
        }
        let standardized = resolved.standardizedFileURL
        rootURL = standardized
        lock = Self.lockRegistry.withTaskLock {
            if let existing = Self.pathLocks[standardized.path] { return existing }
            let created = NSLock()
            Self.pathLocks[standardized.path] = created
            return created
        }
    }

    public func tasks() throws -> [AgentTaskRecord] {
        try lock.withTaskLock { try readAll().sorted { $0.updatedAt > $1.updatedAt } }
    }

    public func task(id: UUID) throws -> AgentTaskRecord? {
        try lock.withTaskLock { try read(id: id) }
    }

    @discardableResult
    public func create(profile: AgentConnectionProfile, payload: AgentTaskPayload) throws -> AgentTaskRecord {
        try lock.withTaskLock {
            if let existing = try readAll().first(where: { $0.payload.clientTaskID == payload.clientTaskID }) {
                guard existing.payload == payload,
                      existing.connection == AgentTaskConnectionIdentity(profile: profile) else {
                    throw AgentTaskError.invalidPayload
                }
                return existing
            }
            let task = AgentTaskRecord(profile: profile, payload: payload)
            try writeNew(task)
            return task
        }
    }

    @discardableResult
    public func mutate(id: UUID, expectedRevision: Int, _ body: (inout AgentTaskRecord) throws -> Void) throws -> AgentTaskRecord {
        try lock.withTaskLock {
            guard var task = try read(id: id) else { throw AgentTaskError.taskNotFound }
            guard task.recordRevision == expectedRevision else { throw AgentTaskError.staleTask }
            try body(&task)
            task.updatedAt = Date()
            task.recordRevision += 1
            try writeState(task)
            return task
        }
    }

    private func readAll() throws -> [AgentTaskRecord] {
        guard FileManager.default.fileExists(atPath: rootURL.path) else { return [] }
        let directories: [URL]
        do {
            directories = try FileManager.default.contentsOfDirectory(
                at: rootURL,
                includingPropertiesForKeys: [.isDirectoryKey],
                options: [.skipsHiddenFiles]
            )
        } catch { throw AgentTaskError.corruptStore }
        return try directories.compactMap { directory in
            guard UUID(uuidString: directory.lastPathComponent) != nil else { return nil }
            guard let id = UUID(uuidString: directory.lastPathComponent) else { return nil }
            return try read(id: id)
        }
    }

    private func read(id: UUID) throws -> AgentTaskRecord? {
        let directory = taskDirectory(id)
        let immutableURL = directory.appendingPathComponent("immutable.json")
        let stateURL = directory.appendingPathComponent("state.json")
        let immutableExists = FileManager.default.fileExists(atPath: immutableURL.path)
        let stateExists = FileManager.default.fileExists(atPath: stateURL.path)
        guard immutableExists || stateExists else { return nil }
        guard immutableExists, stateExists,
              let immutableData = try? Data(contentsOf: immutableURL),
              let stateData = try? Data(contentsOf: stateURL),
              let immutable = try? JSONDecoder().decode(ImmutableRecord.self, from: immutableData),
              let state = try? JSONDecoder().decode(MutableState.self, from: stateData),
              immutable.schemaVersion == 1, state.schemaVersion == 1,
              immutable.id == id,
              SHA256.hash(data: immutable.payload.canonicalData).hex == immutable.payloadSHA256 else {
            throw AgentTaskError.corruptStore
        }
        return AgentTaskRecord(
            id: immutable.id,
            createdAt: immutable.createdAt,
            updatedAt: state.updatedAt,
            connection: immutable.connection,
            payload: immutable.payload,
            payloadSHA256: immutable.payloadSHA256,
            remoteTaskID: state.remoteTaskID,
            status: state.status,
            output: state.output,
            error: state.error,
            approval: state.approval,
            artifacts: state.artifacts,
            recordRevision: state.recordRevision
        )
    }

    private func writeNew(_ task: AgentTaskRecord) throws {
        let directory = taskDirectory(task.id)
        let existed = FileManager.default.fileExists(atPath: directory.path)
        do {
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            let immutable = ImmutableRecord(
                schemaVersion: 1,
                id: task.id,
                createdAt: task.createdAt,
                connection: task.connection,
                payload: task.payload,
                payloadSHA256: task.payloadSHA256
            )
            let immutableURL = directory.appendingPathComponent("immutable.json")
            if !FileManager.default.fileExists(atPath: immutableURL.path) {
                // The per-path process lock makes the existence check and atomic rename one critical section.
                try JSONEncoder.sorted.encode(immutable).write(to: immutableURL, options: .atomic)
            }
            try writeState(task)
        } catch {
            if !existed { try? FileManager.default.removeItem(at: directory) }
            throw AgentTaskError.persistence
        }
    }

    private func writeState(_ task: AgentTaskRecord) throws {
        let state = MutableState(
            schemaVersion: 1,
            updatedAt: task.updatedAt,
            remoteTaskID: task.remoteTaskID,
            status: task.status,
            output: task.output,
            error: task.error,
            approval: task.approval,
            artifacts: task.artifacts,
            recordRevision: task.recordRevision
        )
        do {
            try FileManager.default.createDirectory(at: taskDirectory(task.id), withIntermediateDirectories: true)
            try JSONEncoder.sorted.encode(state).write(
                to: taskDirectory(task.id).appendingPathComponent("state.json"),
                options: .atomic
            )
        } catch { throw AgentTaskError.persistence }
    }

    private func taskDirectory(_ id: UUID) -> URL {
        rootURL.appendingPathComponent(id.uuidString.lowercased(), isDirectory: true)
    }
}

private extension JSONEncoder {
    static var sorted: JSONEncoder {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        return encoder
    }
}

private extension SHA256.Digest {
    var hex: String { map { String(format: "%02x", $0) }.joined() }
}

private extension NSLock {
    func withTaskLock<T>(_ body: () throws -> T) rethrows -> T {
        lock()
        defer { unlock() }
        return try body()
    }
}
