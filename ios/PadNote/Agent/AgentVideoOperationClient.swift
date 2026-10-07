import Foundation
import CryptoKit
import ImageIO
import CoreGraphics
import CoreFoundation

public enum AgentVideoAction: String, Codable, Sendable {
    case initialize, storyboard, approve, produce
}

public enum AgentVideoRemoteStatus: String, Codable, Sendable {
    case queued, running, succeeded, failed, unknown, cancelled

    var terminal: Bool { self == .succeeded || self == .failed || self == .unknown || self == .cancelled }
}

public struct AgentVideoInspection: Codable, Equatable, Sendable {
    public let taskID: String
    public let status: String
    public let phase: String
    public let eventCursor: Int
    public let revision: Int?
    public let reviewSHA256: String?
    public let lessonIRSHA256: String?
    public let receipt: AgentVideoReceipt?

    enum CodingKeys: String, CodingKey {
        case taskID = "task_id", status, phase
        case eventCursor = "event_cursor", revision, receipt
        case reviewSHA256 = "review_sha256", lessonIRSHA256 = "lesson_ir_sha256"
    }

    public init(taskID: String, status: String, phase: String, eventCursor: Int,
                revision: Int? = nil, reviewSHA256: String? = nil,
                lessonIRSHA256: String? = nil, receipt: AgentVideoReceipt? = nil) {
        self.taskID = taskID; self.status = status; self.phase = phase; self.eventCursor = eventCursor
        self.revision = revision; self.reviewSHA256 = reviewSHA256
        self.lessonIRSHA256 = lessonIRSHA256; self.receipt = receipt
    }
}

public struct AgentVideoReceipt: Codable, Equatable, Sendable {
    public let operationID: String
    public let attemptID: String
    public let action: String
    public let payloadDigest: String
    public let sourceSnapshotDigest: String
    public let requestSHA256: String
    public let inputEventCursor: Int
    public let resultEventCursor: Int
    public let revision: Int
    public let reviewSHA256: String
    public let lessonIRSHA256: String
    public let allowCloudTTS: Bool?
    enum CodingKeys: String, CodingKey {
        case action, revision
        case operationID = "operation_id", attemptID = "attempt_id"
        case payloadDigest = "payload_digest", sourceSnapshotDigest = "source_snapshot_digest"
        case requestSHA256 = "request_sha256", inputEventCursor = "input_event_cursor"
        case resultEventCursor = "result_event_cursor", reviewSHA256 = "review_sha256"
        case lessonIRSHA256 = "lesson_ir_sha256", allowCloudTTS = "allow_cloud_tts"
    }
}

public struct AgentVideoOperationResponse: Codable, Equatable, Sendable {
    public let object: String
    public let protocolVersion: Int
    public let operationID: String
    public let taskID: String
    public let clientOperationID: String
    public let action: AgentVideoAction
    public let status: AgentVideoRemoteStatus
    public let createdAt: Int
    public let updatedAt: Int
    public let result: AgentVideoInspection?
    public let error: String?

    enum CodingKeys: String, CodingKey {
        case object, operationID = "operation_id", taskID = "task_id"
        case clientOperationID = "client_operation_id", action, status, result, error
        case protocolVersion = "protocol_version", createdAt = "created_at", updatedAt = "updated_at"
    }
}

public enum AgentVideoCancelRemoteStatus: String, Codable, Sendable {
    case requested, unconfirmed, verifiedCancelled = "verified_cancelled", tooLate = "too_late"
}

public struct AgentVideoCancelRequestResponse: Codable, Equatable, Sendable {
    public let object: String
    public let protocolVersion: Int
    public let operationID: String
    public let taskID: String
    public let status: AgentVideoCancelRemoteStatus
    public let requestedAt: Int
    public let updatedAt: Int
    enum CodingKeys: String, CodingKey {
        case object, status
        case protocolVersion = "protocol_version", operationID = "operation_id"
        case taskID = "task_id", requestedAt = "requested_at", updatedAt = "updated_at"
    }
}

public struct AgentVideoEpisode: Codable, Equatable, Sendable {
    public let title: String
    public let audience: String
    public let learningGoal: String
    public let language: String
    enum CodingKeys: String, CodingKey {
        case title, audience, language, learningGoal = "learning_goal"
    }
}

public struct AgentVideoPreview: Codable, Equatable, Sendable {
    public let id: String
    public let mediaType: String
    public let sizeBytes: Int
    public let sha256: String
    public let width: Int
    public let height: Int
    enum CodingKeys: String, CodingKey {
        case id, sha256, width, height
        case mediaType = "media_type", sizeBytes = "size_bytes"
    }
}

public struct AgentVideoScene: Codable, Equatable, Identifiable, Sendable {
    public let id: String
    public let learningObjective: String
    public let narration: String
    public let screenText: [String]
    public let visualKind: String
    public let preview: AgentVideoPreview
    enum CodingKeys: String, CodingKey {
        case id, narration, preview
        case learningObjective = "learning_objective", screenText = "screen_text", visualKind = "visual_kind"
    }
}

public struct AgentVideoReview: Codable, Equatable, Sendable {
    public let object: String
    public let protocolVersion: Int
    public let status: String
    public let taskID: String
    public let workerTaskID: String
    public let eventCursor: Int
    public let revision: Int
    public let reviewSHA256: String
    public let lessonIRSHA256: String
    public let episode: AgentVideoEpisode
    public let scenes: [AgentVideoScene]
    enum CodingKeys: String, CodingKey {
        case object, episode, scenes, revision, status
        case protocolVersion = "protocol_version", taskID = "task_id", workerTaskID = "worker_task_id"
        case eventCursor = "event_cursor", reviewSHA256 = "review_sha256", lessonIRSHA256 = "lesson_ir_sha256"
    }
}

public struct AgentVideoDiagnostic: Codable, Equatable, Sendable {
    public struct Check: Codable, Equatable, Sendable {
        public let status: String
        public let reason: String
    }
    public let schemaVersion: String
    public let runtimeVerified: Bool
    public let videoReady: Bool
    public let checks: [String: Check]
    enum CodingKeys: String, CodingKey {
        case checks
        case schemaVersion = "schema_version", runtimeVerified = "runtime_verified", videoReady = "video_ready"
    }
}

public enum AgentVideoError: Error, LocalizedError, Equatable {
    case connectionChanged
    case unavailable
    case malformedResponse
    case responseTooLarge
    case requestRejected(Int)
    case needsRemoteTask
    case noReview
    case previewInvalid
    case persistence
    case unknownRemote
    case operationInProgress
    case staleReview
    case runningCancelUnsupported
    case runningCancelNotRecorded
    case cancelledStoryboardCannotResume

    public var errorDescription: String? {
        switch self {
        case .connectionChanged: return "连接已变化或撤销；为保护令牌与任务绑定，不能继续操作。"
        case .unavailable: return "连接助手当前未配置视频操作能力。"
        case .malformedResponse: return "电脑返回的视频任务数据不符合协议。"
        case .responseTooLarge: return "电脑返回的数据超过安全大小限制。"
        case .requestRejected(let code): return "电脑拒绝了视频操作（HTTP \(code)）。"
        case .needsRemoteTask: return "请先提交原视频任务包，并刷新任务编号。"
        case .noReview: return "请先生成并加载当前分镜审阅内容。"
        case .previewInvalid: return "分镜图片校验失败，无法审阅或批准。"
        case .persistence: return "无法安全保存视频操作记录；请求没有发送。"
        case .unknownRemote: return "电脑报告执行结果未知。可显式核对电脑端结果；原操作不会重跑，请勿重新提交或更换编号。"
        case .operationInProgress: return "当前视频操作仍未结束或结果未知，请先刷新状态。"
        case .staleReview: return "当前分镜已变化，请刷新审阅后再批准。"
        case .runningCancelUnsupported: return "这台电脑版本暂不支持停止运行中的分镜；操作结果仍需查看。"
        case .runningCancelNotRecorded: return "电脑端没有记录到停止请求，尚未确认停止；PadNote不会自动重发。"
        case .cancelledStoryboardCannotResume: return "已停止的分镜任务目前不能在原任务中恢复。原材料和历史记录会保留；请回到原笔记新建任务继续。"
        }
    }
}

public struct AgentVideoOperationClient: Sendable {
    private let injectedSession: URLSession?

    public init(session: URLSession? = nil) {
        self.injectedSession = session
    }

    public func diagnostics(task: AgentTaskRecord, token: String) async throws -> AgentVideoDiagnostic {
        let url = try diagnosticsEndpoint(task)
        let data = try await request(url, task: task, method: "GET", token: token, maximumBytes: 32 * 1024)
        try requireExactObject(data, keys: ["schema_version", "runtime_verified", "video_ready", "checks"])
        let raw = try JSONSerialization.jsonObject(with: data) as? [String: Any]
        guard let checks = raw?["checks"] as? [String: Any] else { throw AgentVideoError.malformedResponse }
        for item in checks.values {
            guard let object = item as? [String: Any], Set(object.keys) == ["status", "reason"] else {
                throw AgentVideoError.malformedResponse
            }
        }
        let value = try decode(AgentVideoDiagnostic.self, data: data)
        let names: Set<String> = ["worker_modules", "storyboard_browser", "render_browser", "ffmpeg", "ffprobe", "tts"]
        guard value.schemaVersion == "1.0", value.runtimeVerified == false, value.videoReady == false,
              Set(value.checks.keys) == names,
              value.checks.values.allSatisfy({ ["available", "missing", "unchecked", "not_configured"].contains($0.status) }) else {
            throw AgentVideoError.malformedResponse
        }
        return value
    }

    public func submit(task: AgentTaskRecord, remoteTaskID: String, token: String,
                       action: AgentVideoAction, revision: Int? = nil, eventCursor: Int? = nil,
                       reviewSHA256: String? = nil, lessonIRSHA256: String? = nil,
                       clientOperationID: String) async throws -> AgentVideoOperationResponse {
        guard Self.safeID(remoteTaskID), Self.safeID(clientOperationID) else { throw AgentVideoError.malformedResponse }
        var parameters: [String: Any] = [:]
        switch action {
        case .initialize:
            guard revision == nil, eventCursor == nil, reviewSHA256 == nil, lessonIRSHA256 == nil else {
                throw AgentVideoError.malformedResponse
            }
        case .storyboard:
            guard let revision, let eventCursor, (1...Self.maxSafeInteger).contains(revision),
                  eventCursor >= 1, eventCursor <= Self.maxSafeInteger - 2 else { throw AgentVideoError.staleReview }
            parameters = ["revision": revision, "event_cursor": eventCursor]
        case .approve, .produce:
            guard let revision, let eventCursor, let reviewSHA256, let lessonIRSHA256,
                  (1...Self.maxSafeInteger).contains(revision), eventCursor >= 1,
                  eventCursor < Self.maxSafeInteger,
                  Self.isSHA256(reviewSHA256), Self.isSHA256(lessonIRSHA256) else {
                throw AgentVideoError.staleReview
            }
            parameters = ["revision": revision, "event_cursor": eventCursor,
                          "review_sha256": reviewSHA256, "lesson_ir_sha256": lessonIRSHA256]
            if action == .produce { parameters["allow_cloud_tts"] = true }
        }
        let url = try endpoint(task, suffix: "video/operations")
        let body = try JSONSerialization.data(withJSONObject: ["action": action.rawValue, "parameters": parameters], options: [.sortedKeys])
        let data = try await request(url, task: task, method: "POST", token: token, body: body,
                                     headers: ["Idempotency-Key": clientOperationID], maximumBytes: 32 * 1024)
        try validateOperationJSON(data)
        let response = try decode(AgentVideoOperationResponse.self, data: data)
        try validate(response, remoteTaskID: remoteTaskID, action: action, key: clientOperationID)
        return response
    }

    public func status(task: AgentTaskRecord, remoteTaskID: String, token: String,
                       operationID: String, action: AgentVideoAction, key: String) async throws -> AgentVideoOperationResponse {
        guard Self.safeID(remoteTaskID), Self.safeID(operationID) else { throw AgentVideoError.malformedResponse }
        let url = try endpoint(task, suffix: "video/operations/\(operationID)")
        let data = try await request(url, task: task, method: "GET", token: token, maximumBytes: 32 * 1024)
        try validateOperationJSON(data)
        let response = try decode(AgentVideoOperationResponse.self, data: data)
        try validate(response, remoteTaskID: remoteTaskID, action: action, key: key)
        guard response.operationID == operationID else { throw AgentVideoError.malformedResponse }
        return response
    }

    public func reconcile(task: AgentTaskRecord, remoteTaskID: String, token: String,
                          operationID: String, action: AgentVideoAction,
                          key: String) async throws -> AgentVideoOperationResponse {
        guard Self.safeID(remoteTaskID), Self.safeID(operationID) else { throw AgentVideoError.malformedResponse }
        let url = try endpoint(task, suffix: "video/operations/\(operationID)/reconcile")
        let body = try JSONSerialization.data(withJSONObject: [:], options: [.sortedKeys])
        let data = try await request(url, task: task, method: "POST", token: token, body: body,
                                     maximumBytes: 32 * 1024)
        try validateOperationJSON(data)
        let response = try decode(AgentVideoOperationResponse.self, data: data)
        try validate(response, remoteTaskID: remoteTaskID, action: action, key: key)
        guard response.operationID == operationID,
              response.status == .unknown || response.status == .succeeded || response.status == .cancelled else {
            throw AgentVideoError.malformedResponse
        }
        return response
    }

    public func cancel(task: AgentTaskRecord, remoteTaskID: String, token: String,
                       operationID: String, action: AgentVideoAction, key: String) async throws -> AgentVideoOperationResponse {
        guard Self.safeID(remoteTaskID), Self.safeID(operationID) else { throw AgentVideoError.malformedResponse }
        let url = try endpoint(task, suffix: "video/operations/\(operationID)/cancel")
        let body = try JSONSerialization.data(withJSONObject: [:], options: [.sortedKeys])
        let data = try await request(url, task: task, method: "POST", token: token, body: body, maximumBytes: 32 * 1024)
        try validateOperationJSON(data)
        let response = try decode(AgentVideoOperationResponse.self, data: data)
        try validate(response, remoteTaskID: remoteTaskID, action: action, key: key)
        guard response.operationID == operationID else { throw AgentVideoError.malformedResponse }
        return response
    }

    public func cancelRunningStoryboard(task: AgentTaskRecord, remoteTaskID: String, token: String,
                                        operationID: String) async throws -> AgentVideoCancelRequestResponse {
        let url = try runningCancelEndpoint(task, remoteTaskID: remoteTaskID, operationID: operationID)
        let body = try JSONSerialization.data(withJSONObject: [:], options: [.sortedKeys])
        let data = try await request(url, task: task, method: "POST", token: token, body: body, maximumBytes: 16 * 1024)
        try validateCancelRequestJSON(data)
        let response = try decode(AgentVideoCancelRequestResponse.self, data: data)
        try validate(response, remoteTaskID: remoteTaskID, operationID: operationID)
        return response
    }

    public func cancelRunningProduce(task: AgentTaskRecord, remoteTaskID: String, token: String,
                                     operationID: String) async throws -> AgentVideoCancelRequestResponse {
        guard Self.safeID(remoteTaskID), Self.safeID(operationID) else { throw AgentVideoError.malformedResponse }
        let url = try endpoint(task, suffix: "video/operations/\(operationID)/cancel")
        let body = try JSONSerialization.data(withJSONObject: [:], options: [.sortedKeys])
        let data = try await request(url, task: task, method: "POST", token: token, body: body, maximumBytes: 16 * 1024)
        try validateCancelRequestJSON(data)
        let response = try decode(AgentVideoCancelRequestResponse.self, data: data)
        try validate(response, remoteTaskID: remoteTaskID, operationID: operationID)
        return response
    }

    public func runningProduceCancelStatus(task: AgentTaskRecord, remoteTaskID: String, token: String,
                                           operationID: String) async throws -> AgentVideoCancelRequestResponse {
        guard Self.safeID(remoteTaskID), Self.safeID(operationID) else { throw AgentVideoError.malformedResponse }
        let url = try endpoint(task, suffix: "video/operations/\(operationID)/cancel")
        let data = try await request(url, task: task, method: "GET", token: token, maximumBytes: 16 * 1024)
        try validateCancelRequestJSON(data)
        let response = try decode(AgentVideoCancelRequestResponse.self, data: data)
        try validate(response, remoteTaskID: remoteTaskID, operationID: operationID)
        return response
    }

    public func runningCancelStatus(task: AgentTaskRecord, remoteTaskID: String, token: String,
                                    operationID: String) async throws -> AgentVideoCancelRequestResponse {
        let url = try runningCancelEndpoint(task, remoteTaskID: remoteTaskID, operationID: operationID)
        let data = try await request(url, task: task, method: "GET", token: token, maximumBytes: 16 * 1024)
        try validateCancelRequestJSON(data)
        let response = try decode(AgentVideoCancelRequestResponse.self, data: data)
        try validate(response, remoteTaskID: remoteTaskID, operationID: operationID)
        return response
    }

    public func review(task: AgentTaskRecord, remoteTaskID: String, token: String) async throws -> AgentVideoReview {
        guard Self.safeID(remoteTaskID) else { throw AgentVideoError.malformedResponse }
        let url = try endpoint(task, suffix: "video/review")
        let data = try await request(url, task: task, method: "GET", token: token, maximumBytes: 2 * 1024 * 1024)
        try Self.validateReviewJSON(data)
        let review = try decode(AgentVideoReview.self, data: data)
        guard let workerID = try VideoBundleIdentity.workerTaskID(task.payload) else {
            throw AgentVideoError.unavailable
        }
        try validate(review, remoteTaskID: remoteTaskID, workerTaskID: workerID)
        return review
    }

    public func preview(task: AgentTaskRecord, remoteTaskID: String, token: String,
                        item: AgentVideoPreview) async throws -> Data {
        guard Self.safeID(remoteTaskID), Self.isSHA256(item.id), item.mediaType == "image/png",
              (1...8 * 1024 * 1024).contains(item.sizeBytes), (1...4096).contains(item.width),
              (1...4096).contains(item.height) else { throw AgentVideoError.previewInvalid }
        let url = try endpoint(task, suffix: "video/previews/\(item.id)")
        let bytes = try await request(url, task: task, method: "GET", token: token, maximumBytes: 8 * 1024 * 1024)
        guard bytes.count == item.sizeBytes, Self.sha256(bytes) == item.sha256,
              bytes.starts(with: Data([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a])),
              let source = CGImageSourceCreateWithData(bytes as CFData, nil),
              let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
              (properties[kCGImagePropertyPixelWidth] as? Int) == item.width,
              (properties[kCGImagePropertyPixelHeight] as? Int) == item.height else {
            throw AgentVideoError.previewInvalid
        }
        return bytes
    }

    private func endpoint(_ task: AgentTaskRecord, suffix: String) throws -> URL {
        guard task.connection.transport == .bridge,
              let instance = task.connection.instanceID, Self.safeID(instance),
              let remoteTaskID = task.remoteTaskID, Self.safeID(remoteTaskID) else { throw AgentVideoError.needsRemoteTask }
        let base = try AgentConnectionClient.validatedBaseURL(task.connection.endpoint)
        var root = base.appending(path: "padnote/v1/agents").appending(path: instance)
            .appending(path: "runs").appending(path: remoteTaskID)
        for component in suffix.split(separator: "/") { root.append(path: String(component)) }
        guard root.scheme == base.scheme, root.host == base.host, root.port == base.port else {
            throw AgentVideoError.connectionChanged
        }
        return root
    }

    private func runningCancelEndpoint(_ task: AgentTaskRecord, remoteTaskID: String,
                                       operationID: String) throws -> URL {
        guard Self.safeID(remoteTaskID), Self.safeID(operationID), task.remoteTaskID == remoteTaskID else {
            throw AgentVideoError.malformedResponse
        }
        return try endpoint(task, suffix: "video/operations/\(operationID)/cancel-running")
    }

    private func validate(_ value: AgentVideoCancelRequestResponse,
                          remoteTaskID: String, operationID: String) throws {
        guard value.object == "padnote.video.cancel_request", value.protocolVersion == 1,
              value.operationID == operationID, value.taskID == remoteTaskID,
              value.requestedAt >= 0, value.updatedAt >= value.requestedAt else {
            throw AgentVideoError.malformedResponse
        }
    }

    private func validateCancelRequestJSON(_ data: Data) throws {
        let keys: Set<String> = ["object", "protocol_version", "operation_id", "task_id",
                                 "status", "requested_at", "updated_at"]
        guard let object = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              Set(object.keys) == keys,
              let protocolVersion = Self.strictInteger(object["protocol_version"]), protocolVersion == 1,
              let requestedAt = Self.strictInteger(object["requested_at"]), requestedAt >= 0,
              let updatedAt = Self.strictInteger(object["updated_at"]), updatedAt >= requestedAt,
              let status = object["status"] as? String,
              AgentVideoCancelRemoteStatus(rawValue: status) != nil else {
            throw AgentVideoError.malformedResponse
        }
    }

    private func diagnosticsEndpoint(_ task: AgentTaskRecord) throws -> URL {
        guard task.connection.transport == .bridge,
              let instance = task.connection.instanceID, Self.safeID(instance) else { throw AgentVideoError.unavailable }
        let base = try AgentConnectionClient.validatedBaseURL(task.connection.endpoint)
        let url = base.appending(path: "padnote/v1/agents").appending(path: instance)
            .appending(path: "video").appending(path: "diagnostics")
        guard url.scheme == base.scheme, url.host == base.host, url.port == base.port else {
            throw AgentVideoError.connectionChanged
        }
        return url
    }

    private func request(_ url: URL, task: AgentTaskRecord, method: String, token: String, body: Data? = nil,
                         headers: [String: String] = [:], maximumBytes: Int) async throws -> Data {
        guard !token.isEmpty else { throw AgentVideoError.connectionChanged }
        guard task.connection.kind == .hermes || task.connection.kind == .builtinVideo else {
            throw AgentVideoError.connectionChanged
        }
        let pin: String?
        do { pin = try AgentCertificatePin.normalize(task.connection.certSHA256) }
        catch { throw AgentVideoError.connectionChanged }
        guard task.connection.kind != .builtinVideo || pin != nil else {
            throw AgentVideoError.connectionChanged
        }
        let activeSession: URLSession
        let pinDelegate: URLSessionDelegate?
        if let injectedSession {
            activeSession = injectedSession
            pinDelegate = nil
        } else {
            let pair = AgentCertificatePin.session(pin: pin)
            activeSession = pair.0
            pinDelegate = pair.1
        }
        _ = pinDelegate
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.timeoutInterval = 30
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if body != nil { request.setValue("application/json", forHTTPHeaderField: "Content-Type") }
        headers.forEach { request.setValue($0.value, forHTTPHeaderField: $0.key) }
        request.httpBody = body
        do {
            let (bytes, response) = try await activeSession.bytes(for: request)
            guard response.url == url else { throw AgentVideoError.connectionChanged }
            guard let http = response as? HTTPURLResponse else { throw AgentVideoError.malformedResponse }
            guard (200..<300).contains(http.statusCode) else { throw AgentVideoError.requestRejected(http.statusCode) }
            if method == "GET" && url.path.contains("/video/previews/") {
                guard http.value(forHTTPHeaderField: "Content-Type")?.lowercased().hasPrefix("image/png") == true else {
                    throw AgentVideoError.previewInvalid
                }
            }
            if let length = http.value(forHTTPHeaderField: "Content-Length"),
               let declared = Int(length), declared > maximumBytes { throw AgentVideoError.responseTooLarge }
            var data = Data()
            data.reserveCapacity(min(maximumBytes, 64 * 1024))
            for try await byte in bytes {
                guard data.count < maximumBytes else { throw AgentVideoError.responseTooLarge }
                data.append(byte)
            }
            return data
        } catch let error as AgentVideoError { throw error }
        catch { throw error }
    }

    private func decode<T: Decodable>(_ type: T.Type, data: Data) throws -> T {
        guard data.count <= 2 * 1024 * 1024 else { throw AgentVideoError.responseTooLarge }
        do { return try JSONDecoder().decode(type, from: data) }
        catch { throw AgentVideoError.malformedResponse }
    }

    private func validate(_ value: AgentVideoOperationResponse,
                          remoteTaskID: String, action: AgentVideoAction, key: String) throws {
        let allowedErrors: Set<String> = ["worker_failed", "worker_unknown", "worker_interrupted", "worker_result_invalid",
            "worker_timeout", "authorization_revoked", "instance_retired", "run_unavailable", "source_changed",
            "binding_changed", "cancelled"]
        guard value.object == "padnote.video.operation", value.protocolVersion == 1,
              Self.safeID(value.operationID), value.taskID == remoteTaskID,
              value.clientOperationID == key, value.action == action,
              value.createdAt >= 0, value.updatedAt >= value.createdAt,
              value.error == nil || allowedErrors.contains(value.error!) else {
            throw AgentVideoError.malformedResponse
        }
        if let result = value.result {
            guard result.taskID == remoteTaskID, Self.validTaskState(result.status, phase: result.phase),
                  (1...Self.maxSafeInteger).contains(result.eventCursor) else { throw AgentVideoError.malformedResponse }
        }
        if value.status == .succeeded && value.result == nil { throw AgentVideoError.malformedResponse }
        if value.status != .succeeded && value.result != nil { throw AgentVideoError.malformedResponse }
    }

    private func validateOperationJSON(_ data: Data) throws {
        guard let root = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              Set(root.keys) == Self.operationKeys else { throw AgentVideoError.malformedResponse }
        if let result = root["result"], !(result is NSNull) {
            let allowed: Set<String> = ["protocol_version", "task_id", "status", "phase", "event_cursor",
                                        "revision", "lesson_ir_sha256", "review_sha256", "approval", "receipt"]
            guard let item = result as? [String: Any],
                  Set(item.keys).isSubset(of: allowed),
                  ["task_id", "status", "phase", "event_cursor"].allSatisfy({ item[$0] != nil }) else {
                throw AgentVideoError.malformedResponse
            }
            if let approval = item["approval"] as? [String: Any],
               !Set(approval.keys).isSubset(of: ["approval_id", "revision", "review_sha256", "lesson_ir_sha256", "granted_at", "consumed_at"]) {
                throw AgentVideoError.malformedResponse
            }
            if let receipt = item["receipt"] as? [String: Any] {
                let keys: Set<String> = ["operation_id", "attempt_id", "action", "payload_digest", "source_snapshot_digest",
                    "request_sha256", "input_event_cursor", "result_event_cursor", "revision", "review_sha256",
                    "lesson_ir_sha256", "allow_cloud_tts"]
                guard Set(receipt.keys) == keys,
                      let operationID = receipt["operation_id"] as? String, Self.safeUUID(operationID),
                      let attemptID = receipt["attempt_id"] as? String, Self.safeUUID(attemptID),
                      ["payload_digest", "source_snapshot_digest", "request_sha256", "review_sha256", "lesson_ir_sha256"]
                        .allSatisfy({ (receipt[$0] as? String).map(Self.isSHA256) == true }),
                      let action = receipt["action"] as? String, action == "produce",
                      let input = Self.strictInteger(receipt["input_event_cursor"]), input > 0,
                      let output = Self.strictInteger(receipt["result_event_cursor"]), output > input,
                      let revision = Self.strictInteger(receipt["revision"]), revision > 0,
                      Self.isJSONTrue(receipt["allow_cloud_tts"]) else {
                    throw AgentVideoError.malformedResponse
                }
            } else if item["receipt"] != nil {
                throw AgentVideoError.malformedResponse
            }
        }
    }

    private func validate(_ review: AgentVideoReview, remoteTaskID: String,
                          workerTaskID: String) throws {
        guard review.object == "padnote.video.review", review.protocolVersion == 1,
              ["awaiting_storyboard_review", "approved"].contains(review.status), review.taskID == remoteTaskID,
              review.workerTaskID == workerTaskID,
              review.eventCursor >= 1, review.eventCursor <= Self.maxSafeInteger,
              review.revision >= 1, review.revision <= Self.maxSafeInteger,
              Self.isSHA256(review.reviewSHA256), Self.isSHA256(review.lessonIRSHA256),
              !review.scenes.isEmpty, review.scenes.count <= 60 else { throw AgentVideoError.malformedResponse }
        let validVisuals: Set<String> = ["title", "formula_steps", "concept_map", "process", "comparison", "annotated_source", "quantity_change"]
        let ids = review.scenes.map(\.id)
        guard Set(ids).count == ids.count else { throw AgentVideoError.malformedResponse }
        for scene in review.scenes {
            guard Self.safeID(scene.id), validVisuals.contains(scene.visualKind),
                  !scene.narration.isEmpty, Data(scene.narration.utf8).count <= 20_000,
                  scene.screenText.count <= 20,
                  scene.preview.mediaType == "image/png", Self.isSHA256(scene.preview.id),
                  Self.isSHA256(scene.preview.sha256), (1...8 * 1024 * 1024).contains(scene.preview.sizeBytes),
                  (1...4096).contains(scene.preview.width), (1...4096).contains(scene.preview.height) else {
                throw AgentVideoError.malformedResponse
            }
        }
    }

    private static func safeID(_ value: String) -> Bool {
        guard value.count <= 200, !value.isEmpty else { return false }
        return value.range(of: "^[A-Za-z0-9][A-Za-z0-9_.:-]*$", options: .regularExpression) != nil
    }

    private static func safeUUID(_ value: String) -> Bool {
        value.range(of: "^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$", options: .regularExpression) != nil
    }

    private static func isJSONTrue(_ value: Any?) -> Bool {
        guard let number = value as? NSNumber, CFGetTypeID(number) == CFBooleanGetTypeID() else { return false }
        return number.boolValue
    }
    private static func strictInteger(_ value: Any?) -> Int? {
        guard let number = value as? NSNumber, CFGetTypeID(number) != CFBooleanGetTypeID() else { return nil }
        let double = number.doubleValue
        guard double.isFinite, double.rounded() == double,
              double >= 0, double <= Double(maxSafeInteger) else { return nil }
        return number.intValue
    }
    private static func isSHA256(_ value: String) -> Bool {
        value.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil
    }
    private static func sha256(_ data: Data) -> String { SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined() }
    private static let maxSafeInteger = 9_007_199_254_740_991
    private static let operationKeys: Set<String> = ["object", "protocol_version", "operation_id", "task_id",
        "client_operation_id", "action", "status", "created_at", "updated_at", "result", "error"]

    private func requireExactObject(_ data: Data, keys: Set<String>) throws {
        guard let object = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              Set(object.keys) == keys else { throw AgentVideoError.malformedResponse }
    }

    private static func validateReviewJSON(_ data: Data) throws {
        func exact(_ value: Any?, _ keys: Set<String>) -> [String: Any]? {
            guard let object = value as? [String: Any], Set(object.keys) == keys else { return nil }
            return object
        }
        let reviewKeys: Set<String> = ["object", "protocol_version", "task_id", "worker_task_id", "status", "event_cursor",
                                       "revision", "review_sha256", "lesson_ir_sha256", "episode", "scenes"]
        let episodeKeys: Set<String> = ["title", "audience", "learning_goal", "language"]
        let sceneKeys: Set<String> = ["id", "learning_objective", "narration", "screen_text", "visual_kind", "preview"]
        let previewKeys: Set<String> = ["id", "media_type", "size_bytes", "sha256", "width", "height"]
        guard let root = exact(try JSONSerialization.jsonObject(with: data), reviewKeys),
              exact(root["episode"], episodeKeys) != nil,
              let scenes = root["scenes"] as? [Any], !scenes.isEmpty, scenes.count <= 60 else {
            throw AgentVideoError.malformedResponse
        }
        for item in scenes {
            guard let scene = exact(item, sceneKeys), exact(scene["preview"], previewKeys) != nil,
                  scene["screen_text"] is [String] else { throw AgentVideoError.malformedResponse }
        }
    }
    private static func validTaskState(_ status: String, phase: String) -> Bool {
        let pairs: Set<String> = ["initialized|idle", "awaiting_storyboard_review|awaiting_approval",
                                  "approved|approval_pending", "ready_to_render|audio_ready", "completed|completed",
                                  "running|storyboard", "running|approval_consumed", "running|tts_starting",
                                  "running|audio_ready", "running|render_starting", "failed|idle", "interrupted|idle",
                                  "cancelling|cancelling", "cancelled|cancelled"]
        return pairs.contains("\(status)|\(phase)")
    }

    private final class RedirectDelegate: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
        func urlSession(_ session: URLSession, task: URLSessionTask,
                        willPerformHTTPRedirection response: HTTPURLResponse,
                        newRequest request: URLRequest,
                        completionHandler: @escaping (URLRequest?) -> Void) {
            completionHandler(nil)
        }
    }
}

public final class AgentVideoOperationService: @unchecked Sendable {
    private let connections: AgentConnectionStore
    private let tasks: AgentTaskStore
    private let store: AgentVideoOperationStore
    private let client: AgentVideoOperationClient

    public init(connections: AgentConnectionStore = AgentConnectionStore(),
                tasks: AgentTaskStore = AgentTaskStore(),
                store: AgentVideoOperationStore = AgentVideoOperationStore(),
                client: AgentVideoOperationClient = AgentVideoOperationClient()) {
        self.connections = connections
        self.tasks = tasks
        self.store = store
        self.client = client
    }

    /// Checks the task's saved connection identity locally. It never adopts the currently selected connection.
    public func connectionIsCurrent(taskID: UUID) -> Bool {
        do { _ = try context(taskID); return true }
        catch { return false }
    }

    public func supportsCapability(_ name: String, taskID: UUID) -> Bool {
        let supported = Set(["video_operations", "video_production", "run_status", "artifacts"])
        guard supported.contains(name),
              let context = try? context(taskID),
              let profile = connections.profile(id: context.task.connection.connectionID),
              profile.connected, context.task.connection.stillMatches(profile) else { return false }
        if name == "video_production" {
            return profile.capabilities["video_operations"] == true &&
                profile.capabilities["video_production"] == true
        }
        return profile.capabilities[name] == true
    }

    public func diagnostics(taskID: UUID) async throws -> AgentVideoDiagnostic {
        let context = try context(taskID)
        let value = try await client.diagnostics(task: context.task, token: context.token)
        try ensureConnection(taskID: taskID, matches: context)
        return value
    }

    public func submit(taskID: UUID, action: AgentVideoAction,
                       displayedReview: AgentVideoReview? = nil,
                       verifiedPreviewIDs: Set<String> = []) async throws -> AgentVideoOperationRecord {
        let context = try context(taskID)
        try requireCapability("video_operations", task: context.task)
        if action == .produce { try requireCapability("video_production", task: context.task) }
        let prior = try store.snapshot(for: taskID)
        if action == .storyboard,
           prior.operations.contains(where: {
               $0.action == .storyboard && $0.remoteStatus == .cancelled
                   && $0.runningCancelIntent?.status == .verifiedCancelled
           }) {
            throw AgentVideoError.cancelledStoryboardCannotResume
        }
        let revision: Int?
        let eventCursor: Int?
        let reviewSHA: String?
        let lessonSHA: String?
        switch action {
        case .initialize:
            revision = nil; eventCursor = nil; reviewSHA = nil; lessonSHA = nil
        case .storyboard:
            if let review = prior.review {
                guard review.status == "awaiting_storyboard_review", displayedReview == review else {
                    throw AgentVideoError.staleReview
                }
                revision = review.revision + 1
                eventCursor = review.eventCursor
            } else if let initialized = prior.operations.last(where: {
                $0.action == .initialize && $0.remoteStatus == .succeeded
            })?.result, initialized.status == "initialized" {
                revision = 1; eventCursor = initialized.eventCursor
            } else {
                throw AgentVideoError.needsRemoteTask
            }
            reviewSHA = nil; lessonSHA = nil
        case .approve:
            guard let review = displayedReview, review.status == "awaiting_storyboard_review", prior.review == review,
                  verifiedPreviewIDs == Set(review.scenes.map { $0.id }) else { throw AgentVideoError.noReview }
            revision = review.revision; eventCursor = review.eventCursor
            reviewSHA = review.reviewSHA256; lessonSHA = review.lessonIRSHA256
        case .produce:
            guard let review = prior.review, review.status == "approved",
                  let approved = prior.operations.last(where: { $0.action == .approve && $0.remoteStatus == .succeeded }),
                  let approvalResult = approved.result, approvalResult.status == "approved",
                  let approvedRevision = approvalResult.revision,
                  let reviewHash = approvalResult.reviewSHA256, let lessonHash = approvalResult.lessonIRSHA256,
                  review.revision == approvedRevision, review.eventCursor == approvalResult.eventCursor,
                  review.reviewSHA256 == reviewHash, review.lessonIRSHA256 == lessonHash else {
                throw AgentVideoError.staleReview
            }
            revision = approvedRevision; eventCursor = approvalResult.eventCursor; reviewSHA = reviewHash; lessonSHA = lessonHash
        }
        let operation = AgentVideoOperationRecord(
            parentTaskLocalID: taskID, connection: context.task.connection,
            remoteTaskID: context.remoteTaskID, action: action, revision: revision,
            eventCursor: eventCursor, reviewSHA256: reviewSHA, lessonIRSHA256: lessonSHA)
        // This atomic local write must succeed before any request can leave the device.
        let saved = try store.prepare(operation, expectedReview: displayedReview,
                                      verifiedPreviewIDs: verifiedPreviewIDs)
        return try await post(saved, context: context)
    }

    public func retryUncertainSubmission(taskID: UUID, operationID: UUID) async throws -> AgentVideoOperationRecord {
        let context = try context(taskID)
        let current = try find(operationID, in: store.snapshot(for: taskID))
        try ensure(current, matches: context)
        guard current.submissionState == .prepared ||
                (current.submissionState == .uncertain && current.operationID == nil) else {
            throw AgentVideoError.unknownRemote
        }
        return try await post(current, context: context)
    }

    public func refresh(taskID: UUID, operationID: UUID) async throws -> AgentVideoOperationRecord {
        let context = try context(taskID)
        let current = try find(operationID, in: store.snapshot(for: taskID))
        try ensure(current, matches: context)
        guard current.submissionState == .accepted, let remoteID = current.operationID else {
            if current.submissionState == .uncertain { throw AgentVideoError.unknownRemote }
            throw AgentVideoError.operationInProgress
        }
        let response = try await client.status(task: context.task, remoteTaskID: context.remoteTaskID,
            token: context.token, operationID: remoteID, action: current.action, key: current.clientOperationID)
        return try await apply(response, to: current, taskID: taskID)
    }

    /// Explicitly asks the authorized computer to verify its durable receipt. This never submits the action again.
    public func reconcile(taskID: UUID, operationID: UUID) async throws -> AgentVideoOperationRecord {
        let context = try context(taskID)
        let current = try find(operationID, in: store.snapshot(for: taskID))
        try ensure(current, matches: context)
        guard current.submissionState == .accepted, current.remoteStatus == .unknown,
              let remoteID = current.operationID else { throw AgentVideoError.operationInProgress }
        let response = try await client.reconcile(task: context.task, remoteTaskID: context.remoteTaskID,
            token: context.token, operationID: remoteID, action: current.action,
            key: current.clientOperationID)
        return try await apply(response, to: current, taskID: taskID)
    }

    public func cancel(taskID: UUID, operationID: UUID) async throws -> AgentVideoOperationRecord {
        let context = try context(taskID)
        let current = try find(operationID, in: store.snapshot(for: taskID))
        try ensure(current, matches: context)
        guard current.submissionState == .accepted, let remoteID = current.operationID,
              current.remoteStatus == .queued else { throw AgentVideoError.operationInProgress }
        let response = try await client.cancel(task: context.task, remoteTaskID: context.remoteTaskID,
            token: context.token, operationID: remoteID, action: current.action, key: current.clientOperationID)
        return try await apply(response, to: current, taskID: taskID)
    }

    /// Persists a one-shot local stop intent before the POST. Existing intents are
    /// never POSTed again; a later open can only GET their status.
    public func requestRunningStoryboardCancel(taskID: UUID, operationID: UUID) async throws -> AgentVideoOperationRecord {
        let requestContext = try context(taskID)
        let current = try find(operationID, in: store.snapshot(for: taskID))
        try ensure(current, matches: requestContext)
        guard current.submissionState == .accepted, current.action == .storyboard,
              current.remoteStatus == .running, current.operationID != nil,
              current.runningCancelIntent == nil else {
            throw current.remoteStatus == .unknown ? AgentVideoError.unknownRemote : AgentVideoError.operationInProgress
        }
        let intent = AgentVideoRunningCancelIntent(
            operationID: try requireRemoteOperationID(current),
            clientOperationID: current.clientOperationID,
            connection: current.connection, remoteTaskID: current.remoteTaskID)
        let prepared = try store.mutate(taskID: taskID, operationID: operationID) { value in
            guard value == current, value.runningCancelIntent == nil,
                  value.remoteStatus == .running, value.action == .storyboard else {
                throw AgentTaskError.staleTask
            }
            value.runningCancelIntent = intent
        }
        // A failure here occurs before the control request leaves the device. Keep
        // the durable intent: reopening can only query, never implicitly POST.
        try ensureConnection(taskID: taskID, matches: requestContext)
        return try await postRunningCancel(expected: prepared, requestContext: requestContext)
    }

    public func requestRunningProduceCancel(taskID: UUID, operationID: UUID) async throws -> AgentVideoOperationRecord {
        let requestContext = try context(taskID)
        let current = try find(operationID, in: store.snapshot(for: taskID))
        try ensure(current, matches: requestContext)
        guard current.submissionState == .accepted, current.action == .produce,
              current.remoteStatus == .running || current.remoteStatus == .unknown,
              current.operationID != nil, current.runningCancelIntent == nil else {
            throw current.remoteStatus == .unknown ? AgentVideoError.unknownRemote : AgentVideoError.operationInProgress
        }
        let intent = AgentVideoRunningCancelIntent(
            operationID: try requireRemoteOperationID(current), clientOperationID: current.clientOperationID,
            connection: current.connection, remoteTaskID: current.remoteTaskID)
        let prepared = try store.mutate(taskID: taskID, operationID: operationID) { value in
            guard value == current, value.runningCancelIntent == nil,
                  value.action == .produce, value.remoteStatus == .running || value.remoteStatus == .unknown else {
                throw AgentTaskError.staleTask
            }
            value.runningCancelIntent = intent
        }
        try ensureConnection(taskID: taskID, matches: requestContext)
        return try await postRunningCancel(expected: prepared, requestContext: requestContext)
    }

    /// Explicit user retry after a previous POST was not recorded. It first GETs
    /// the existing operation and proceeds only if that exact storyboard is still
    /// running on the original connection. The existing operation/key is reused.
    public func retryRunningStoryboardCancel(taskID: UUID, operationID: UUID) async throws -> AgentVideoOperationRecord {
        let requestContext = try context(taskID)
        let current = try find(operationID, in: store.snapshot(for: taskID))
        try ensure(current, matches: requestContext)
        guard current.submissionState == .accepted, current.action == .storyboard,
              current.remoteStatus == .running, current.runningCancelIntent?.delivery == .noRequest,
              let remoteID = current.operationID,
              current.runningCancelIntent?.operationID == remoteID else {
            throw current.remoteStatus == .unknown ? AgentVideoError.unknownRemote : AgentVideoError.operationInProgress
        }
        let fresh = try await client.status(task: requestContext.task, remoteTaskID: requestContext.remoteTaskID,
            token: requestContext.token, operationID: remoteID, action: current.action, key: current.clientOperationID)
        try Task.checkCancellation()
        try ensureConnection(taskID: taskID, matches: requestContext)
        guard fresh.status == .running else {
            _ = try await apply(fresh, to: current, taskID: taskID)
            throw AgentVideoError.operationInProgress
        }
        _ = try await apply(fresh, to: current, taskID: taskID)
        try Task.checkCancellation()
        let refreshed = try find(operationID, in: store.snapshot(for: taskID))
        guard refreshed.remoteStatus == .running,
              refreshed.runningCancelIntent?.delivery == .noRequest else {
            throw AgentVideoError.operationInProgress
        }
        let prepared = try store.mutate(taskID: taskID, operationID: operationID) { value in
            guard value == refreshed, var intent = value.runningCancelIntent,
                  intent.delivery == .noRequest else { throw AgentTaskError.staleTask }
            intent.delivery = .prepared
            value.runningCancelIntent = intent
        }
        try Task.checkCancellation()
        return try await postRunningCancel(expected: prepared, requestContext: requestContext)
    }

    private func postRunningCancel(expected prepared: AgentVideoOperationRecord,
                                   requestContext: (task: AgentTaskRecord, remoteTaskID: String, token: String)) async throws -> AgentVideoOperationRecord {
        guard let intent = prepared.runningCancelIntent else { throw AgentTaskError.staleTask }
        let taskID = prepared.parentTaskLocalID
        do {
            try Task.checkCancellation()
            try ensureConnection(taskID: taskID, matches: requestContext)
            guard try find(prepared.id, in: store.snapshot(for: taskID)) == prepared else {
                throw AgentTaskError.staleTask
            }
            let response = prepared.action == .produce
                ? try await client.cancelRunningProduce(task: requestContext.task, remoteTaskID: requestContext.remoteTaskID,
                    token: requestContext.token, operationID: intent.operationID)
                : try await client.cancelRunningStoryboard(task: requestContext.task, remoteTaskID: requestContext.remoteTaskID,
                    token: requestContext.token, operationID: intent.operationID)
            try ensureConnection(taskID: taskID, matches: requestContext)
            return try applyRunningCancelResponse(response, to: prepared, taskID: taskID)
        } catch is CancellationError {
            // Cancellation before the URLSession call leaves the durable intent
            // untouched; cancellation after dispatch is surfaced by URLSession as
            // a transport error and remains uncertain.
            throw CancellationError()
        } catch AgentVideoError.requestRejected(let code) where code == 404 || code == 409 {
            try ensureConnection(taskID: taskID, matches: requestContext)
            let marked = try updateCancelDelivery(
                expected: prepared, taskID: taskID, delivery: code == 404 ? .unsupported : .noRequest)
            _ = marked
            throw code == 404 ? AgentVideoError.runningCancelUnsupported : AgentVideoError.runningCancelNotRecorded
        } catch {
            // Only mark uncertainty while the original connection remains current.
            // Revocation/identity change rejects the old response and preserves the intent.
            try ensureConnection(taskID: taskID, matches: requestContext)
            _ = try updateCancelDelivery(expected: prepared, taskID: taskID, delivery: .uncertain)
            throw error
        }
    }

    /// Reads an existing stop intent. This method never sends the cancellation POST.
    public func refreshRunningCancelStatus(taskID: UUID, operationID: UUID) async throws -> AgentVideoOperationRecord {
        let requestContext = try context(taskID)
        let current = try find(operationID, in: store.snapshot(for: taskID))
        try ensure(current, matches: requestContext)
        guard current.submissionState == .accepted,
              (current.action == .storyboard || current.action == .produce),
              let intent = current.runningCancelIntent,
              let remoteOperationID = current.operationID,
              intent.operationID == remoteOperationID,
              intent.clientOperationID == current.clientOperationID else {
            throw AgentVideoError.operationInProgress
        }
        do {
            let response = current.action == .produce
                ? try await client.runningProduceCancelStatus(task: requestContext.task, remoteTaskID: requestContext.remoteTaskID,
                    token: requestContext.token, operationID: remoteOperationID)
                : try await client.runningCancelStatus(task: requestContext.task, remoteTaskID: requestContext.remoteTaskID,
                    token: requestContext.token, operationID: remoteOperationID)
            try ensureConnection(taskID: taskID, matches: requestContext)
            return try applyRunningCancelResponse(response, to: current, taskID: taskID)
        } catch AgentVideoError.requestRejected(let code) where code == 404 || code == 409 {
            try ensureConnection(taskID: taskID, matches: requestContext)
            if current.runningCancelIntent?.delivery == .accepted {
                throw code == 404 ? AgentVideoError.runningCancelUnsupported : AgentVideoError.runningCancelNotRecorded
            }
            return try updateCancelDelivery(
                expected: current, taskID: taskID, delivery: code == 404 ? .unsupported : .noRequest)
        }
    }

    public func loadReview(taskID: UUID) async throws -> AgentVideoReview {
        let requestContext = try context(taskID)
        let review = try await client.review(task: requestContext.task, remoteTaskID: requestContext.remoteTaskID,
                                              token: requestContext.token)
        _ = try context(taskID)
        try store.save(review: review, for: taskID)
        return review
    }

    public func loadPreview(taskID: UUID, item: AgentVideoPreview) async throws -> Data {
        let requestContext = try context(taskID)
        let bytes = try await client.preview(task: requestContext.task, remoteTaskID: requestContext.remoteTaskID,
                                             token: requestContext.token, item: item)
        try ensureConnection(taskID: taskID, matches: requestContext)
        return bytes
    }

    private func post(_ operation: AgentVideoOperationRecord,
                      context requestContext: (task: AgentTaskRecord, remoteTaskID: String, token: String)) async throws -> AgentVideoOperationRecord {
        do {
            try ensureConnection(taskID: operation.parentTaskLocalID, matches: requestContext)
            let response = try await client.submit(task: requestContext.task, remoteTaskID: requestContext.remoteTaskID,
                token: requestContext.token, action: operation.action, revision: operation.revision,
                eventCursor: operation.eventCursor, reviewSHA256: operation.reviewSHA256,
                lessonIRSHA256: operation.lessonIRSHA256,
                clientOperationID: operation.clientOperationID)
            return try await apply(response, to: operation, taskID: operation.parentTaskLocalID)
        } catch {
            let latest = try self.context(operation.parentTaskLocalID)
            try ensure(operation, matches: latest)
            let rejected: Bool
            if case AgentVideoError.requestRejected(let status) = error { rejected = (400..<500).contains(status) }
            else { rejected = false }
            return try store.mutate(taskID: operation.parentTaskLocalID, operationID: operation.id) { value in
                value.submissionState = rejected ? .rejected : .uncertain
                value.errorCode = rejected ? "request_rejected" : "submission_uncertain"
            }
        }
    }

    private func apply(_ response: AgentVideoOperationResponse, to operation: AgentVideoOperationRecord,
                       taskID: UUID) async throws -> AgentVideoOperationRecord {
        let latest = try context(taskID)
        try ensure(operation, matches: latest)
        if response.status == .succeeded {
            guard let result = response.result else { throw AgentVideoError.malformedResponse }
            switch operation.action {
            case .initialize:
                guard result.status == "initialized", result.phase == "idle", result.eventCursor == 1 else {
                    throw AgentVideoError.malformedResponse
                }
            case .storyboard:
                guard result.status == "awaiting_storyboard_review", result.phase == "awaiting_approval",
                      result.revision == operation.revision,
                      result.eventCursor == (operation.eventCursor ?? 0) + 2 else { throw AgentVideoError.malformedResponse }
            case .approve:
                guard result.status == "approved", result.phase == "approval_pending",
                      result.revision == operation.revision,
                      result.eventCursor == (operation.eventCursor ?? 0) + 1,
                      result.reviewSHA256 == operation.reviewSHA256,
                      result.lessonIRSHA256 == operation.lessonIRSHA256 else { throw AgentVideoError.malformedResponse }
            case .produce:
                guard result.status == "completed", result.phase == "completed",
                      result.eventCursor > (operation.eventCursor ?? 0), let receipt = result.receipt,
                      receipt.action == "produce", receipt.inputEventCursor == operation.eventCursor,
                      receipt.resultEventCursor == result.eventCursor, receipt.revision == operation.revision,
                      receipt.reviewSHA256 == operation.reviewSHA256,
                      receipt.lessonIRSHA256 == operation.lessonIRSHA256,
                      receipt.allowCloudTTS == true,
                      receipt.operationID == response.operationID else { throw AgentVideoError.malformedResponse }
            }
        }
        if response.status == .cancelled {
            let queuedCancellation = operation.remoteStatus == .queued
                && operation.runningCancelIntent == nil
            let receiptCancellation = (operation.action == .storyboard || operation.action == .produce)
                && operation.runningCancelIntent != nil
                && (operation.remoteStatus == .running || operation.remoteStatus == .unknown)
            guard queuedCancellation || receiptCancellation,
                  response.error == "cancelled", response.result == nil else {
                throw AgentVideoError.malformedResponse
            }
        }
        return try store.mutate(taskID: taskID, operationID: operation.id,
                                clearReview: response.status == .succeeded && operation.action == .storyboard,
                                markReviewApproved: response.status == .succeeded && operation.action == .approve) { value in
            guard value == operation else { throw AgentTaskError.staleTask }
            if let old = value.remoteStatus, old.terminal,
               !(old == .unknown && (response.status == .succeeded || response.status == .cancelled)),
               old != response.status {
                throw AgentTaskError.staleTask
            }
            value.operationID = response.operationID
            value.submissionState = .accepted
            value.remoteStatus = response.status
            value.result = response.result
            value.errorCode = response.error
            if response.status == .cancelled, var intent = value.runningCancelIntent {
                let alreadyAccepted = intent.delivery == .accepted
                intent.delivery = .accepted
                intent.status = .verifiedCancelled
                if alreadyAccepted {
                    intent.operationStatusVerifiedAt = nil
                } else {
                    intent.requestedAt = nil
                    intent.updatedAt = nil
                    intent.operationStatusVerifiedAt = Date()
                }
                value.runningCancelIntent = intent
            }
        }
    }

    private func applyRunningCancelResponse(_ response: AgentVideoCancelRequestResponse,
                                            to operation: AgentVideoOperationRecord,
                                            taskID: UUID) throws -> AgentVideoOperationRecord {
        let context = try self.context(taskID)
        try ensure(operation, matches: context)
        guard operation.runningCancelIntent != nil,
              response.operationID == operation.operationID,
              response.taskID == operation.remoteTaskID else { throw AgentVideoError.malformedResponse }
        if operation.remoteStatus == .cancelled,
           operation.runningCancelIntent?.status == .verifiedCancelled,
           operation.runningCancelIntent?.operationStatusVerifiedAt != nil,
           response.status == .verifiedCancelled {
            // Reconcile created a local receipt-backed confirmation without the
            // control endpoint's timestamps. A later remote GET validates the
            // same binding but must not rewrite that already terminal proof.
            return operation
        }
        return try store.mutate(taskID: taskID, operationID: operation.id) { value in
            guard value == operation, var intent = value.runningCancelIntent,
                  intent.operationID == response.operationID,
                  intent.clientOperationID == value.clientOperationID,
                  intent.connection == value.connection,
                  intent.remoteTaskID == response.taskID else { throw AgentTaskError.staleTask }
            intent.delivery = .accepted
            intent.status = response.status
            intent.requestedAt = response.requestedAt
            intent.updatedAt = response.updatedAt
            intent.operationStatusVerifiedAt = nil
            value.runningCancelIntent = intent
            if response.status == .verifiedCancelled {
                guard (value.action == .storyboard || value.action == .produce),
                      value.remoteStatus == .running || value.remoteStatus == .unknown ||
                        (value.remoteStatus == .cancelled && intent.status == .verifiedCancelled) else {
                    throw AgentTaskError.staleTask
                }
                if value.remoteStatus != .cancelled {
                    value.remoteStatus = .cancelled
                    value.result = nil
                    value.errorCode = "cancelled"
                }
            }
        }
    }

    private func updateCancelDelivery(expected: AgentVideoOperationRecord, taskID: UUID,
                                      delivery: AgentVideoCancelDelivery) throws -> AgentVideoOperationRecord {
        try store.mutate(taskID: taskID, operationID: expected.id) { value in
            guard value == expected, var intent = value.runningCancelIntent else {
                throw AgentTaskError.staleTask
            }
            intent.delivery = delivery
            intent.status = nil
            intent.requestedAt = nil
            intent.updatedAt = nil
            intent.operationStatusVerifiedAt = nil
            value.runningCancelIntent = intent
        }
    }

    private func requireRemoteOperationID(_ operation: AgentVideoOperationRecord) throws -> String {
        guard let value = operation.operationID else { throw AgentVideoError.malformedResponse }
        return value
    }

    private func requireCapability(_ name: String, task: AgentTaskRecord) throws {
        guard let profile = connections.profile(id: task.connection.connectionID), profile.connected,
              task.connection.stillMatches(profile), profile.capabilities[name] == true else {
            throw AgentVideoError.unavailable
        }
    }

    private func context(_ taskID: UUID) throws -> (task: AgentTaskRecord, remoteTaskID: String, token: String) {
        guard let task = try tasks.task(id: taskID), task.connection.transport == .bridge,
              task.payload.bundleBase64 != nil, let remoteTaskID = task.remoteTaskID, !remoteTaskID.isEmpty else {
            throw AgentVideoError.needsRemoteTask
        }
        guard try VideoBundleIdentity.workerTaskID(task.payload) != nil else { throw AgentVideoError.unavailable }
        guard let profile = connections.profile(id: task.connection.connectionID), profile.connected,
              task.connection.stillMatches(profile),
              let token = connections.token(reference: task.connection.credentialReference), !token.isEmpty else {
            throw AgentVideoError.connectionChanged
        }
        return (task, remoteTaskID, token)
    }

    private func find(_ id: UUID, in snapshot: AgentVideoTaskSnapshot) throws -> AgentVideoOperationRecord {
        guard let value = snapshot.operations.first(where: { $0.id == id }) else { throw AgentTaskError.taskNotFound }
        return value
    }

    private func ensure(_ operation: AgentVideoOperationRecord,
                        matches context: (task: AgentTaskRecord, remoteTaskID: String, token: String)) throws {
        guard operation.connection == context.task.connection, operation.remoteTaskID == context.remoteTaskID else {
            throw AgentVideoError.connectionChanged
        }
    }

    private func ensureConnection(taskID: UUID,
                                  matches expected: (task: AgentTaskRecord, remoteTaskID: String, token: String)) throws {
        let latest = try context(taskID)
        guard latest.task.connection == expected.task.connection,
              latest.remoteTaskID == expected.remoteTaskID else { throw AgentVideoError.connectionChanged }
    }
}

private enum VideoBundleIdentity {
    static func workerTaskID(_ payload: AgentTaskPayload) throws -> String? {
        guard let encoded = payload.bundleBase64, let bytes = Data(base64Encoded: encoded),
              bytes.count <= 8 * 1024 * 1024,
              let declared = payload.bundleSHA256,
              SHA256.hash(data: bytes).map({ String(format: "%02x", $0) }).joined() == declared.lowercased() else {
            return nil
        }
        let requestData = try StoredZipRequest.read(from: bytes)
        guard let request = try JSONSerialization.jsonObject(with: requestData) as? [String: Any],
              request["task_type"] as? String == "video.explain.v1",
              let workerID = request["task_id"] as? String,
              workerID.range(of: "^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$", options: .regularExpression) != nil,
              let source = request["source"] as? [String: Any],
              source["note_id"] as? String == payload.source.noteID,
              (source["note_revision"] as? NSNumber)?.intValue == payload.source.noteRevision else { return nil }
        return workerID
    }
}

private enum StoredZipRequest {
    static func read(from archive: Data) throws -> Data {
        func u16(_ offset: Int) -> UInt16? {
            guard offset >= 0, offset + 2 <= archive.count else { return nil }
            return UInt16(archive[offset]) | (UInt16(archive[offset + 1]) << 8)
        }
        func u32(_ offset: Int) -> UInt32? {
            guard offset >= 0, offset + 4 <= archive.count else { return nil }
            return UInt32(archive[offset]) | (UInt32(archive[offset + 1]) << 8)
                | (UInt32(archive[offset + 2]) << 16) | (UInt32(archive[offset + 3]) << 24)
        }
        guard u32(0) == 0x04034b50, u16(6) == 0, u16(8) == 0,
              let compressed = u32(18), let uncompressed = u32(22), compressed == uncompressed,
              let nameLength = u16(26), let extraLength = u16(28),
              Int(nameLength) == "request.json".utf8.count else { throw AgentVideoError.unavailable }
        let nameStart = 30
        let dataStart = nameStart + Int(nameLength) + Int(extraLength)
        guard dataStart <= archive.count,
              String(data: archive.subdata(in: nameStart..<(nameStart + Int(nameLength))), encoding: .utf8) == "request.json",
              uncompressed > 0, uncompressed <= 1024 * 1024,
              dataStart + Int(uncompressed) <= archive.count,
              let expectedCRC = u32(14) else { throw AgentVideoError.unavailable }
        let bytes = archive.subdata(in: dataStart..<(dataStart + Int(uncompressed)))
        guard CRC32Video.checksum(bytes) == expectedCRC else { throw AgentVideoError.unavailable }
        return bytes
    }
}

private enum CRC32Video {
    static func checksum(_ data: Data) -> UInt32 {
        var crc: UInt32 = 0xffff_ffff
        for byte in data {
            crc ^= UInt32(byte)
            for _ in 0..<8 { crc = (crc >> 1) ^ ((crc & 1) == 1 ? 0xedb8_8320 : 0) }
        }
        return ~crc
    }
}

private extension JSONEncoder {
    static var sorted: JSONEncoder {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        return encoder
    }
}
