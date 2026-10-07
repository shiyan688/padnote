import Foundation

public enum AgentVideoSubmissionState: String, Codable, Sendable {
    case prepared
    case uncertain
    case accepted
    case rejected
}

public enum AgentVideoCancelDelivery: String, Codable, Sendable {
    case prepared, uncertain, accepted, unsupported, noRequest
}

public struct AgentVideoRunningCancelIntent: Codable, Equatable, Sendable {
    public let operationID: String
    public let clientOperationID: String
    public let connection: AgentTaskConnectionIdentity
    public let remoteTaskID: String
    public let preparedAt: Date
    public var delivery: AgentVideoCancelDelivery
    public var status: AgentVideoCancelRemoteStatus?
    public var requestedAt: Int?
    public var updatedAt: Int?
    public var operationStatusVerifiedAt: Date?

    public init(operationID: String, clientOperationID: String,
                connection: AgentTaskConnectionIdentity, remoteTaskID: String, now: Date = Date()) {
        self.operationID = operationID
        self.clientOperationID = clientOperationID
        self.connection = connection
        self.remoteTaskID = remoteTaskID
        self.preparedAt = now
        self.delivery = .prepared
        self.status = nil
        self.requestedAt = nil
        self.updatedAt = nil
        self.operationStatusVerifiedAt = nil
    }
}

public struct AgentVideoOperationRecord: Codable, Equatable, Identifiable, Sendable {
    public let id: UUID
    public let parentTaskLocalID: UUID
    public let connection: AgentTaskConnectionIdentity
    public let remoteTaskID: String
    public let action: AgentVideoAction
    public let revision: Int?
    public let eventCursor: Int?
    public let reviewSHA256: String?
    public let lessonIRSHA256: String?
    public let clientOperationID: String
    public var operationID: String?
    public var submissionState: AgentVideoSubmissionState
    public var remoteStatus: AgentVideoRemoteStatus?
    public var result: AgentVideoInspection?
    public var errorCode: String?
    public var runningCancelIntent: AgentVideoRunningCancelIntent?
    public let createdAt: Date
    public var updatedAt: Date

    public init(parentTaskLocalID: UUID, connection: AgentTaskConnectionIdentity,
                remoteTaskID: String, action: AgentVideoAction, revision: Int? = nil,
                eventCursor: Int? = nil, reviewSHA256: String? = nil,
                lessonIRSHA256: String? = nil, now: Date = Date()) {
        self.id = UUID()
        self.parentTaskLocalID = parentTaskLocalID
        self.connection = connection
        self.remoteTaskID = remoteTaskID
        self.action = action
        self.revision = revision
        self.eventCursor = eventCursor
        self.reviewSHA256 = reviewSHA256
        self.lessonIRSHA256 = lessonIRSHA256
        self.clientOperationID = UUID().uuidString.lowercased()
        self.operationID = nil
        self.submissionState = .prepared
        self.remoteStatus = nil
        self.result = nil
        self.errorCode = nil
        self.runningCancelIntent = nil
        self.createdAt = now
        self.updatedAt = now
    }
}

public struct AgentVideoTaskSnapshot: Codable, Equatable, Sendable {
    public let schemaVersion: Int
    public var operations: [AgentVideoOperationRecord]
    public var review: AgentVideoReview?

    public init(operations: [AgentVideoOperationRecord] = [], review: AgentVideoReview? = nil) {
        schemaVersion = 1
        self.operations = operations
        self.review = review
    }
}

public final class AgentVideoOperationStore: @unchecked Sendable {
    private static let registryLock = NSLock()
    private static var locks: [String: NSLock] = [:]
    private let root: URL
    private let lock: NSLock

    public init(directory: URL? = nil) {
        let base = directory ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("PadNote", isDirectory: true)
            .appendingPathComponent("agent-video-operations", isDirectory: true)
        let normalizedRoot = base.standardizedFileURL
        root = normalizedRoot
        lock = Self.registryLock.withVideoLock {
            if let existing = Self.locks[normalizedRoot.path] { return existing }
            let created = NSLock()
            Self.locks[normalizedRoot.path] = created
            return created
        }
    }

    public func snapshot(for taskID: UUID) throws -> AgentVideoTaskSnapshot {
        try lock.withVideoLock { try read(taskID) }
    }

    @discardableResult
    public func prepare(_ operation: AgentVideoOperationRecord,
                        expectedReview: AgentVideoReview? = nil,
                        verifiedPreviewIDs: Set<String> = []) throws -> AgentVideoOperationRecord {
        try lock.withVideoLock {
            var value = try read(operation.parentTaskLocalID)
            if value.operations.contains(where: Self.blocksNewAction) { throw AgentVideoError.operationInProgress }
            if operation.action == .storyboard, let existingReview = value.review {
                guard let expectedReview, existingReview == expectedReview,
                      existingReview.status == "awaiting_storyboard_review" else {
                    throw AgentVideoError.staleReview
                }
            }
            if operation.action == .approve {
                guard let expectedReview, expectedReview.status == "awaiting_storyboard_review",
                      value.review == expectedReview,
                      verifiedPreviewIDs == Set(expectedReview.scenes.map { $0.id }),
                      operation.revision == expectedReview.revision,
                      operation.eventCursor == expectedReview.eventCursor,
                      operation.reviewSHA256 == expectedReview.reviewSHA256,
                      operation.lessonIRSHA256 == expectedReview.lessonIRSHA256 else {
                    throw AgentVideoError.staleReview
                }
                guard let last = value.operations.last(where: { $0.action == .storyboard }),
                      last.remoteStatus == .succeeded,
                      last.result?.eventCursor == expectedReview.eventCursor,
                      last.result?.revision == expectedReview.revision,
                      last.result?.reviewSHA256 == expectedReview.reviewSHA256,
                      last.result?.lessonIRSHA256 == expectedReview.lessonIRSHA256 else {
                    throw AgentVideoError.staleReview
                }
            }
            if operation.action == .produce {
                guard expectedReview == nil, let current = value.review, current.status == "approved",
                      let prior = value.operations.last(where: { $0.action == .approve && $0.remoteStatus == .succeeded }),
                      let approved = prior.result,
                      operation.revision == approved.revision, operation.eventCursor == approved.eventCursor,
                      operation.reviewSHA256 == approved.reviewSHA256,
                      operation.lessonIRSHA256 == approved.lessonIRSHA256,
                      current.revision == approved.revision, current.eventCursor == approved.eventCursor,
                      current.reviewSHA256 == approved.reviewSHA256,
                      current.lessonIRSHA256 == approved.lessonIRSHA256 else { throw AgentVideoError.staleReview }
            }
            value.operations.append(operation)
            try write(value, taskID: operation.parentTaskLocalID)
            return operation
        }
    }

    @discardableResult
    public func mutate(taskID: UUID, operationID: UUID, clearReview: Bool = false,
                       markReviewApproved: Bool = false,
                       _ body: (inout AgentVideoOperationRecord) throws -> Void) throws -> AgentVideoOperationRecord {
        try lock.withVideoLock {
            var value = try read(taskID)
            guard let index = value.operations.firstIndex(where: { $0.id == operationID }) else {
                throw AgentTaskError.taskNotFound
            }
            let previous = value.operations[index]
            try body(&value.operations[index])
            guard Self.allowedTransition(from: previous, to: value.operations[index]) else {
                throw AgentTaskError.staleTask
            }
            if clearReview { value.review = nil }
            else if markReviewApproved, let review = value.review,
                    let cursor = value.operations[index].result?.eventCursor {
                value.review = review.withStatus("approved", eventCursor: cursor)
            }
            value.operations[index].updatedAt = Date()
            let updated = value.operations[index]
            try write(value, taskID: taskID)
            return updated
        }
    }

    public func save(review: AgentVideoReview, for taskID: UUID) throws {
        try lock.withVideoLock {
            var value = try read(taskID)
            value.review = review
            try write(value, taskID: taskID)
        }
    }

    private func read(_ taskID: UUID) throws -> AgentVideoTaskSnapshot {
        let url = fileURL(taskID)
        guard FileManager.default.fileExists(atPath: url.path) else { return AgentVideoTaskSnapshot() }
        do {
            let data = try Data(contentsOf: url)
            guard data.count <= 8 * 1024 * 1024 else { throw AgentVideoError.responseTooLarge }
            let value = try JSONDecoder.video.decode(AgentVideoTaskSnapshot.self, from: data)
            guard value.schemaVersion == 1, value.operations.count <= 1024,
                  value.operations.allSatisfy({ $0.parentTaskLocalID == taskID && Self.valid($0) }),
                  Set(value.operations.map(\.id)).count == value.operations.count,
                  Set(value.operations.map(\.clientOperationID)).count == value.operations.count,
                  value.operations.filter(Self.blocksNewAction).count <= 1,
                  value.review.map(Self.valid) ?? true,
                  Self.reviewMatchesLatestStoryboard(value.review, operations: value.operations) else {
                throw AgentVideoError.persistence
            }
            return value
        } catch let error as AgentVideoError { throw error }
        catch { throw AgentVideoError.persistence }
    }

    private func write(_ value: AgentVideoTaskSnapshot, taskID: UUID) throws {
        do {
            guard value.schemaVersion == 1, value.operations.count <= 1024,
                  value.operations.allSatisfy({ $0.parentTaskLocalID == taskID && Self.valid($0) }),
                  Set(value.operations.map(\.id)).count == value.operations.count,
                  Set(value.operations.map(\.clientOperationID)).count == value.operations.count,
                  value.operations.filter(Self.blocksNewAction).count <= 1,
                  value.review.map(Self.valid) ?? true,
                  Self.reviewMatchesLatestStoryboard(value.review, operations: value.operations) else {
                throw AgentVideoError.persistence
            }
            try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
            let bytes = try JSONEncoder.video.encode(value)
            guard bytes.count <= 8 * 1024 * 1024 else { throw AgentVideoError.responseTooLarge }
            try bytes.write(to: fileURL(taskID), options: .atomic)
        } catch { throw AgentVideoError.persistence }
    }

    private func fileURL(_ taskID: UUID) -> URL {
        root.appendingPathComponent(taskID.uuidString.lowercased() + ".json")
    }

    private static func blocksNewAction(_ item: AgentVideoOperationRecord) -> Bool {
        if item.submissionState == .prepared || item.submissionState == .uncertain { return true }
        guard item.submissionState == .accepted, let status = item.remoteStatus else { return false }
        return status == .queued || status == .running || status == .unknown
    }

    private static func allowedTransition(from old: AgentVideoOperationRecord,
                                          to new: AgentVideoOperationRecord) -> Bool {
        guard old.id == new.id, old.parentTaskLocalID == new.parentTaskLocalID,
              old.connection == new.connection, old.remoteTaskID == new.remoteTaskID,
              old.action == new.action, old.revision == new.revision,
              old.eventCursor == new.eventCursor, old.reviewSHA256 == new.reviewSHA256,
              old.lessonIRSHA256 == new.lessonIRSHA256,
              old.clientOperationID == new.clientOperationID, old.createdAt == new.createdAt,
              allowedCancelIntentTransition(from: old, to: new) else { return false }
        let submissionAllowed: Bool
        switch old.submissionState {
        case .prepared: submissionAllowed = [.prepared, .uncertain, .accepted, .rejected].contains(new.submissionState)
        case .uncertain: submissionAllowed = [.uncertain, .accepted, .rejected].contains(new.submissionState)
        case .accepted: submissionAllowed = new.submissionState == .accepted && old.operationID == new.operationID
        case .rejected: submissionAllowed = new.submissionState == .rejected
        }
        guard submissionAllowed else { return false }
        switch old.remoteStatus {
        case .none:
            return new.remoteStatus == nil || new.remoteStatus == .queued || new.remoteStatus == .running ||
                new.remoteStatus == .succeeded || new.remoteStatus == .failed || new.remoteStatus == .unknown ||
                new.remoteStatus == .cancelled
        case .some(.queued):
            return [.queued, .running, .succeeded, .failed, .unknown, .cancelled].contains(new.remoteStatus ?? .unknown)
        case .some(.running):
            if new.remoteStatus == .cancelled {
                return new.runningCancelIntent?.status == .verifiedCancelled && new.errorCode == "cancelled" && new.result == nil
            }
            return [.running, .succeeded, .failed, .unknown].contains(new.remoteStatus ?? .cancelled)
        case .some(.unknown):
            if new.remoteStatus == .unknown {
                return new.result == old.result && new.errorCode == old.errorCode
            }
            if new.remoteStatus == .cancelled {
                return old.runningCancelIntent != nil
                    && new.runningCancelIntent?.status == .verifiedCancelled
                    && new.errorCode == "cancelled" && new.result == nil
            }
            return new.remoteStatus == .succeeded
        case .some(.succeeded), .some(.failed), .some(.cancelled):
            return new.remoteStatus == old.remoteStatus && new.result == old.result &&
                new.errorCode == old.errorCode
        }
    }

    private static func allowedCancelIntentTransition(from old: AgentVideoOperationRecord,
                                                       to new: AgentVideoOperationRecord) -> Bool {
        switch (old.runningCancelIntent, new.runningCancelIntent) {
        case (nil, nil): return true
        case (nil, .some(let intent)):
            let cancellableStoryboard = old.action == .storyboard && old.remoteStatus == .running
            let cancellableProduction = old.action == .produce && (old.remoteStatus == .running || old.remoteStatus == .unknown)
            return (cancellableStoryboard || cancellableProduction) && old.submissionState == .accepted
                && old.operationID == intent.operationID
                && old.clientOperationID == intent.clientOperationID
                && old.connection == intent.connection && old.remoteTaskID == intent.remoteTaskID
                && intent.delivery == .prepared && intent.status == nil
                && intent.requestedAt == nil && intent.updatedAt == nil
        case (.some(let previous), .some(let next)):
            guard previous.operationID == next.operationID,
                  previous.clientOperationID == next.clientOperationID,
                  previous.connection == next.connection, previous.remoteTaskID == next.remoteTaskID,
                  previous.preparedAt == next.preparedAt else { return false }
            if next == previous { return true }
            if previous.delivery == .accepted
                && (previous.status == .verifiedCancelled || previous.status == .tooLate) {
                return next == previous
            }
            switch previous.delivery {
            case .prepared:
                return [.uncertain, .accepted, .unsupported, .noRequest].contains(next.delivery)
                    && validCancelProgress(next)
            case .uncertain:
                return [.uncertain, .accepted, .unsupported, .noRequest].contains(next.delivery)
                    && validCancelProgress(next)
            case .accepted:
                let monotonicTimestamp = previous.updatedAt.map { (next.updatedAt ?? -1) >= $0 } ?? true
                return next.delivery == .accepted && validCancelProgress(next)
                    && allowedCancelStatusTransition(from: previous.status, to: next.status)
                    && monotonicTimestamp
            case .unsupported:
                return next == previous
            case .noRequest:
                if next == previous { return true }
                if old.remoteStatus == .unknown && new.remoteStatus == .cancelled
                    && next.delivery == .accepted && next.status == .verifiedCancelled
                    && next.requestedAt == nil && next.updatedAt == nil
                    && next.operationStatusVerifiedAt != nil { return true }
                // A user may explicitly retry only after a fresh operation GET
                // proved the same storyboard is still running. The original
                // intent identity and preparedAt remain immutable.
                return old.remoteStatus == .running && new.remoteStatus == .running
                    && next.delivery == .prepared && next.status == nil
                    && next.requestedAt == nil && next.updatedAt == nil
                    && next.operationStatusVerifiedAt == nil
            }
        case (.some, nil): return false
        }
    }

    private static func validCancelProgress(_ value: AgentVideoRunningCancelIntent) -> Bool {
        switch value.delivery {
        case .prepared, .uncertain:
            return value.status == nil && value.requestedAt == nil && value.updatedAt == nil
        case .accepted:
            guard let status = value.status else { return false }
            if status == .verifiedCancelled, value.requestedAt == nil, value.updatedAt == nil {
                return value.operationStatusVerifiedAt != nil
            }
            guard let requestedAt = value.requestedAt, let updatedAt = value.updatedAt,
                  requestedAt >= 0, updatedAt >= requestedAt,
                  value.operationStatusVerifiedAt == nil else { return false }
            return status == .requested || status == .unconfirmed || status == .verifiedCancelled || status == .tooLate
        case .unsupported, .noRequest:
            return value.status == nil && value.requestedAt == nil && value.updatedAt == nil
        }
    }

    private static func allowedCancelStatusTransition(from old: AgentVideoCancelRemoteStatus?,
                                                       to new: AgentVideoCancelRemoteStatus?) -> Bool {
        guard let old else { return new != nil }
        guard let new else { return false }
        switch old {
        case .requested: return [.requested, .unconfirmed, .verifiedCancelled, .tooLate].contains(new)
        case .unconfirmed: return [.unconfirmed, .verifiedCancelled, .tooLate].contains(new)
        case .verifiedCancelled: return new == .verifiedCancelled
        case .tooLate: return new == .tooLate
        }
    }

    private static let safeInteger = 9_007_199_254_740_991
    private static let errorCodes: Set<String> = ["request_rejected", "submission_uncertain", "worker_failed",
        "worker_unknown", "worker_interrupted", "worker_result_invalid", "worker_timeout",
        "authorization_revoked", "instance_retired", "run_unavailable", "source_changed",
        "binding_changed", "cancelled"]

    private static func valid(_ value: AgentVideoOperationRecord) -> Bool {
        guard value.id.uuidString != "00000000-0000-0000-0000-000000000000",
              UUID(uuidString: value.clientOperationID) != nil,
              value.connection.transport == .bridge, value.connection.connectionRevision > 0,
              URL(string: value.connection.endpoint)?.scheme == "https",
              value.connection.instanceID?.range(of: "^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$", options: .regularExpression) != nil,
              value.connection.bridgeID?.isEmpty == false, !value.connection.credentialReference.isEmpty,
              value.remoteTaskID.range(of: "^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$", options: .regularExpression) != nil,
              value.updatedAt >= value.createdAt,
              value.errorCode.map(errorCodes.contains) ?? true else { return false }
        switch value.action {
        case .initialize:
            guard value.revision == nil, value.eventCursor == nil, value.reviewSHA256 == nil,
                  value.lessonIRSHA256 == nil else { return false }
        case .storyboard:
            guard let revision = value.revision, (1...safeInteger).contains(revision),
                  let cursor = value.eventCursor, (1...safeInteger - 2).contains(cursor),
                  value.reviewSHA256 == nil, value.lessonIRSHA256 == nil else { return false }
        case .approve:
            guard let revision = value.revision, (1...safeInteger).contains(revision),
                  let cursor = value.eventCursor, (1...safeInteger - 1).contains(cursor),
                  value.reviewSHA256.map(isSHA256) == true, value.lessonIRSHA256.map(isSHA256) == true else { return false }
        case .produce:
            guard let revision = value.revision, (1...safeInteger).contains(revision),
                  let cursor = value.eventCursor, (1...safeInteger - 1).contains(cursor),
                  value.reviewSHA256.map(isSHA256) == true, value.lessonIRSHA256.map(isSHA256) == true else { return false }
        }
        switch value.submissionState {
        case .prepared:
            return value.operationID == nil && value.remoteStatus == nil && value.result == nil && value.errorCode == nil
        case .uncertain:
            return value.operationID == nil && value.remoteStatus == nil && value.result == nil && value.errorCode == "submission_uncertain"
        case .rejected:
            return value.operationID == nil && value.remoteStatus == nil && value.result == nil && value.errorCode == "request_rejected"
        case .accepted:
            guard value.operationID.flatMap(UUID.init(uuidString:)) != nil,
                  let status = value.remoteStatus else { return false }
            if let intent = value.runningCancelIntent {
                guard value.action == .storyboard || value.action == .produce,
                      intent.operationID == value.operationID,
                      intent.clientOperationID == value.clientOperationID,
                      intent.connection == value.connection, intent.remoteTaskID == value.remoteTaskID,
                      validCancelProgress(intent) else { return false }
            }
            if status == .succeeded {
                guard let result = value.result, valid(result), value.errorCode == nil,
                      result.taskID == value.remoteTaskID else { return false }
                switch value.action {
                case .initialize:
                    return result.status == "initialized" && result.phase == "idle" && result.eventCursor == 1
                case .storyboard:
                    return result.status == "awaiting_storyboard_review" && result.phase == "awaiting_approval" &&
                        result.revision == value.revision && result.eventCursor == (value.eventCursor ?? 0) + 2 &&
                        result.reviewSHA256.map(isSHA256) == true && result.lessonIRSHA256.map(isSHA256) == true
                case .approve:
                    return result.status == "approved" && result.phase == "approval_pending" &&
                        result.revision == value.revision && result.eventCursor == (value.eventCursor ?? 0) + 1 &&
                        result.reviewSHA256 == value.reviewSHA256 && result.lessonIRSHA256 == value.lessonIRSHA256
                case .produce:
                    guard result.status == "completed", result.phase == "completed",
                          result.eventCursor > (value.eventCursor ?? 0), let receipt = result.receipt,
                          receipt.action == "produce", receipt.inputEventCursor == value.eventCursor,
                          receipt.resultEventCursor == result.eventCursor, receipt.revision == value.revision,
                          receipt.reviewSHA256 == value.reviewSHA256,
                          receipt.lessonIRSHA256 == value.lessonIRSHA256, receipt.allowCloudTTS == true,
                          receipt.operationID == value.operationID else { return false }
                    return true
                }
            }
            if status == .cancelled {
                return value.result == nil && value.errorCode == "cancelled"
                    && (value.runningCancelIntent == nil || value.runningCancelIntent?.status == .verifiedCancelled)
            }
            return value.result == nil && (value.errorCode.map(errorCodes.contains) ?? true)
        }
    }

    private static func valid(_ value: AgentVideoInspection) -> Bool {
        guard value.taskID.range(of: "^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$", options: .regularExpression) != nil,
              (1...safeInteger).contains(value.eventCursor) else { return false }
        switch (value.status, value.phase) {
        case ("initialized", "idle"), ("awaiting_storyboard_review", "awaiting_approval"),
             ("approved", "approval_pending"), ("completed", "completed"): break
        default: return false
        }
        if let revision = value.revision, !(1...safeInteger).contains(revision) { return false }
        if let hash = value.reviewSHA256, !isSHA256(hash) { return false }
        if let hash = value.lessonIRSHA256, !isSHA256(hash) { return false }
        if let receipt = value.receipt {
            guard safeUUID(receipt.operationID), safeUUID(receipt.attemptID), receipt.action == "produce",
                  isSHA256(receipt.payloadDigest), isSHA256(receipt.sourceSnapshotDigest),
                  isSHA256(receipt.requestSHA256), isSHA256(receipt.reviewSHA256),
                  isSHA256(receipt.lessonIRSHA256), receipt.inputEventCursor > 0,
                  receipt.resultEventCursor > receipt.inputEventCursor, receipt.revision > 0,
                  receipt.allowCloudTTS == true else { return false }
        }
        return true
    }

    private static func safeUUID(_ value: String) -> Bool {
        value.range(of: "^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$", options: .regularExpression) != nil
    }

    private static func valid(_ value: AgentVideoReview) -> Bool {
        guard value.object == "padnote.video.review", value.protocolVersion == 1,
              ["awaiting_storyboard_review", "approved"].contains(value.status),
              value.taskID.range(of: "^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$", options: .regularExpression) != nil,
              value.workerTaskID.range(of: "^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$", options: .regularExpression) != nil,
              (1...safeInteger).contains(value.revision), (1...safeInteger).contains(value.eventCursor),
              isSHA256(value.reviewSHA256), isSHA256(value.lessonIRSHA256),
              !value.scenes.isEmpty, value.scenes.count <= 60,
              Set(value.scenes.map(\.id)).count == value.scenes.count else { return false }
        return value.scenes.allSatisfy { scene in
            scene.id.range(of: "^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$", options: .regularExpression) != nil &&
                !scene.narration.isEmpty && Data(scene.narration.utf8).count <= 20_000 &&
                scene.screenText.count <= 20 && isSHA256(scene.preview.id) &&
                scene.preview.mediaType == "image/png" && (1...8 * 1024 * 1024).contains(scene.preview.sizeBytes) &&
                isSHA256(scene.preview.sha256) && (1...4096).contains(scene.preview.width) &&
                (1...4096).contains(scene.preview.height)
        }
    }

    private static func reviewMatchesLatestStoryboard(_ review: AgentVideoReview?, operations: [AgentVideoOperationRecord]) -> Bool {
        guard let review else { return true }
        guard let latestStoryboard = operations.last(where: { $0.action == .storyboard && $0.remoteStatus == .succeeded }),
              let result = latestStoryboard.result else { return false }
        let storyboardMatches = review.status == "awaiting_storyboard_review" &&
            result.taskID == review.taskID && result.eventCursor == review.eventCursor &&
            result.revision == review.revision && result.reviewSHA256 == review.reviewSHA256 &&
            result.lessonIRSHA256 == review.lessonIRSHA256
        if storyboardMatches { return true }
        guard review.status == "approved",
              let approval = operations.last(where: { $0.action == .approve && $0.remoteStatus == .succeeded })?.result else { return false }
        return approval.status == "approved" && approval.taskID == review.taskID &&
            approval.eventCursor == review.eventCursor && approval.revision == review.revision &&
            approval.reviewSHA256 == review.reviewSHA256 && approval.lessonIRSHA256 == review.lessonIRSHA256
    }

    private static func isSHA256(_ value: String) -> Bool {
        value.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil
    }
}

private extension AgentVideoReview {
    func withStatus(_ status: String, eventCursor: Int) -> AgentVideoReview {
        AgentVideoReview(object: object, protocolVersion: protocolVersion, status: status, taskID: taskID,
            workerTaskID: workerTaskID, eventCursor: eventCursor, revision: revision,
            reviewSHA256: reviewSHA256, lessonIRSHA256: lessonIRSHA256, episode: episode, scenes: scenes)
    }
}

private extension NSLock {
    func withVideoLock<T>(_ body: () throws -> T) rethrows -> T {
        lock(); defer { unlock() }; return try body()
    }
}

private extension JSONEncoder {
    static var video: JSONEncoder {
        let value = JSONEncoder()
        value.outputFormatting = [.sortedKeys]
        return value
    }
}

private extension JSONDecoder {
    static var video: JSONDecoder { JSONDecoder() }
}
