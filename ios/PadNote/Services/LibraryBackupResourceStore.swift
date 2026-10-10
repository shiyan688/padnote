import Foundation
import CryptoKit
import UIKit

public struct UserCoverPreset: Codable, Identifiable, Equatable {
    public let id: UUID
    public let name: String
    public let sizeBytes: Int
    public let sha256: String
    public let createdAt: Date
    public let restoreTransactionID: String?
    public let restoreGroupID: String?
    public let originKind: String?
    fileprivate var fileName: String { "\(id.uuidString.lowercased()).png" }
}

/// Persistent user-owned cover presets. Built-in artwork is intentionally not exported as user data.
public final class UserCoverPresetStore: @unchecked Sendable {
    private let root: URL
    private let journalRoot: URL
    private let fm = FileManager.default
    public private(set) var lastListingIssueCount = 0
    public init(directory: URL? = nil, journalRoot: URL? = nil) {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let fallback = base.appendingPathComponent("PadNote/cover-presets", isDirectory: true)
        root = directory ?? LibraryBackupUITestPaths.directory("cover-presets", fallback: fallback)
        self.journalRoot = journalRoot ?? LibraryBackupTransactionGate.defaultJournalRoot
    }
    public func listing() throws -> [UserCoverPreset] {
        lastListingIssueCount = 0
        guard fm.fileExists(atPath: root.path) else { return [] }
        try Self.requireDirectory(root)
        let files = try fm.contentsOfDirectory(at: root, includingPropertiesForKeys: nil)
        guard files.count <= 20_000 else { throw LibraryBackupError.sizeLimit("封面预设数量超限") }
        var values = [UserCoverPreset](), allValues = [UserCoverPreset](), issueNames = Set<String>()
        for url in files where url.pathExtension == "json" {
            do {
                _ = try LibraryBackupArchive.validateRegularSource(url, maximumBytes: 64 * 1024)
                let data = try LibraryBackupArchive.readSmallFile(url, maximumBytes: 64 * 1024)
                let value = try JSONDecoder().decode(UserCoverPreset.self, from: data)
                guard url.lastPathComponent == "\(value.id.uuidString.lowercased()).json",
                      value.originKind == nil || value.originKind == "restored_archive", value.sizeBytes > 0,
                      value.sizeBytes <= NoteCoverStore.maxBytes, value.createdAt.timeIntervalSince1970.isFinite,
                      value.createdAt.timeIntervalSince1970 >= 0,
                      value.sha256.range(of: "^[0-9a-f]{64}$", options: .regularExpression) != nil else {
                    throw LibraryBackupError.invalidManifest("封面预设元数据无效")
                }
                allValues.append(value)
                let png = root.appendingPathComponent(value.fileName)
                _ = try LibraryBackupArchive.validateRegularSource(png, maximumBytes: Int64(NoteCoverStore.maxBytes))
                let isVisible = LibraryBackupTransactionGate.isVisible(originKind: value.originKind,
                    transactionID: value.restoreTransactionID, groupID: value.restoreGroupID, kind: "preset",
                    localID: value.id.uuidString.lowercased(), in: journalRoot)
                if isVisible {
                    let actual = try LibraryBackupArchive.hashFile(png)
                    guard actual.size == value.sizeBytes, actual.sha256 == value.sha256 else { throw LibraryBackupError.sourceChanged }
                    _ = try NoteCoverStore.validatePNGFile(png)
                    values.append(value)
                }
            } catch { issueNames.insert(url.lastPathComponent) }
        }
        let known = Set(allValues.flatMap { [$0.fileName, "\($0.id.uuidString.lowercased()).json"] })
        issueNames.formUnion(files.filter { !known.contains($0.lastPathComponent) }.map(\.lastPathComponent))
        lastListingIssueCount = issueNames.count
        return values.sorted { $0.createdAt > $1.createdAt }
    }
    @discardableResult public func add(name: String, png: URL, transactionID: String? = nil,
                                       presetID: UUID? = nil, groupID: String? = nil,
                                       expectedSize: Int64? = nil, expectedSHA256: String? = nil, cancellation: LibraryBackupCancellationToken? = nil) throws -> UserCoverPreset {
        let title = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !title.isEmpty, title.utf8.count <= 256 else { throw LibraryBackupError.invalidManifest("封面预设名称无效") }
        _ = try NoteCoverStore.validatePNGFile(png)
        try fm.createDirectory(at: root, withIntermediateDirectories: true)
        try Self.requireDirectory(root)
        let id = presetID ?? UUID(), target = root.appendingPathComponent("\(id.uuidString.lowercased()).png")
        let copied: (size: Int64, sha256: String)
        do {
            copied = try LibraryBackupArchive.copyVerified(png, to: target, maximumBytes: Int64(NoteCoverStore.maxBytes), cancellation: cancellation)
            if let expectedSize, copied.size != expectedSize { throw LibraryBackupError.sourceChanged }
            if let expectedSHA256, copied.sha256 != expectedSHA256 { throw LibraryBackupError.sourceChanged }
            _ = try NoteCoverStore.validatePNGFile(target)
        } catch { try? fm.removeItem(at: target); throw error }
        let value = UserCoverPreset(id: id, name: title, sizeBytes: Int(copied.size), sha256: copied.sha256,
                                    createdAt: Date(), restoreTransactionID: transactionID, restoreGroupID: groupID,
                                    originKind: transactionID == nil ? nil : "restored_archive")
        do { try Self.writeMetadata(value, to: root.appendingPathComponent("\(id.uuidString.lowercased()).json")) }
        catch { try? fm.removeItem(at: target); throw error }
        return value
    }
    @discardableResult public func restore(name: String, stagedPNG: URL, transactionID: String? = nil,
                                           presetID: UUID? = nil, groupID: String? = nil,
                                           expectedSize: Int64? = nil, expectedSHA256: String? = nil, cancellation: LibraryBackupCancellationToken? = nil) throws -> UserCoverPreset {
        return try add(name: name, png: stagedPNG, transactionID: transactionID, presetID: presetID, groupID: groupID,
                       expectedSize: expectedSize, expectedSHA256: expectedSHA256, cancellation: cancellation)
    }
    public func pngURL(for preset: UserCoverPreset) throws -> URL {
        let url = root.appendingPathComponent(preset.fileName)
        let actual = try LibraryBackupArchive.hashFile(url)
        guard actual.size == preset.sizeBytes, actual.sha256 == preset.sha256 else { throw LibraryBackupError.sourceChanged }
        _ = try NoteCoverStore.validatePNGFile(url)
        return url
    }
    public func remove(_ preset: UserCoverPreset) throws {
        let current = try listing().first { $0.id == preset.id }
        guard let current else { throw LibraryBackupError.transaction("找不到封面预设") }
        let png = root.appendingPathComponent(current.fileName), json = root.appendingPathComponent("\(current.id.uuidString.lowercased()).json")
        try fm.removeItem(at: png)
        try fm.removeItem(at: json)
    }
    func rollbackPartial(id: UUID, transactionID: String, groupID: String, expectedSHA256: String) throws {
        let stem = id.uuidString.lowercased()
        guard expectedSHA256.range(of: "^[0-9a-f]{64}$", options: .regularExpression) != nil else { throw LibraryBackupError.unsafeFile }
        let json = root.appendingPathComponent(stem + ".json"), png = root.appendingPathComponent(stem + ".png")
        var current: UserCoverPreset?
        if fm.fileExists(atPath: json.path) {
            _ = try LibraryBackupArchive.validateRegularSource(json, maximumBytes: 64 * 1024)
            current = try JSONDecoder().decode(UserCoverPreset.self, from: LibraryBackupArchive.readSmallFile(json, maximumBytes: 64 * 1024))
            guard current?.id == id, current?.restoreTransactionID == transactionID, current?.restoreGroupID == groupID,
                  current?.sha256 == expectedSHA256 else { throw LibraryBackupError.transaction("封面预设恢复所有权不匹配") }
        }
        if fm.fileExists(atPath: png.path) {
            _ = try LibraryBackupArchive.validateRegularSource(png, maximumBytes: Int64(NoteCoverStore.maxBytes))
            guard try LibraryBackupArchive.hashFile(png).sha256 == expectedSHA256 else { throw LibraryBackupError.sourceChanged }
            try fm.removeItem(at: png)
        }
        if current != nil { try fm.removeItem(at: json) }
    }

    func rollback(_ preset: UserCoverPreset, transactionID: String) throws {
        let json = root.appendingPathComponent("\(preset.id.uuidString.lowercased()).json")
        _ = try LibraryBackupArchive.validateRegularSource(json, maximumBytes: 64 * 1024)
        let current = try JSONDecoder().decode(UserCoverPreset.self, from: LibraryBackupArchive.readSmallFile(json, maximumBytes: 64 * 1024))
        guard current.id == preset.id, current.restoreTransactionID == transactionID else { throw LibraryBackupError.transaction("封面预设回滚所有权不匹配") }
        let png = root.appendingPathComponent(current.fileName)
        _ = try LibraryBackupArchive.validateRegularSource(png, maximumBytes: Int64(NoteCoverStore.maxBytes))
        try fm.removeItem(at: png); try fm.removeItem(at: json)
    }
    private static func writeMetadata(_ value: UserCoverPreset, to url: URL) throws {
        let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]
        let data = try encoder.encode(value)
        let temp = url.deletingLastPathComponent().appendingPathComponent(".\(UUID().uuidString).partial")
        try data.write(to: temp, options: [.atomic, .completeFileProtectionUnlessOpen])
        guard !FileManager.default.fileExists(atPath: url.path) else { try? FileManager.default.removeItem(at: temp); throw LibraryBackupError.transaction("封面预设记录已存在") }
        try FileManager.default.moveItem(at: temp, to: url)
    }
    private static func requireDirectory(_ url: URL) throws {
        var s = stat(); guard lstat(url.path, &s) == 0, s.st_mode & mode_t(S_IFMT) == mode_t(S_IFDIR) else { throw LibraryBackupError.unsafeFile }
    }
    static func requireDirectoryForResources(_ url: URL) throws { try requireDirectory(url) }
    static func writeResourceMetadata<T: Encodable>(_ value: T, to url: URL) throws {
        let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]
        let data = try encoder.encode(value)
        let temp = url.deletingLastPathComponent().appendingPathComponent(".\(UUID().uuidString).partial")
        try data.write(to: temp, options: [.atomic, .completeFileProtectionUnlessOpen])
        guard !FileManager.default.fileExists(atPath: url.path) else { try? FileManager.default.removeItem(at: temp); throw LibraryBackupError.transaction("恢复资料记录已存在") }
        try FileManager.default.moveItem(at: temp, to: url)
    }
}

public struct RestoredVideoAttachment: Codable, Identifiable, Equatable {
    public let id: UUID
    public let noteID: String?
    public let originKind: String
    public let sourceNoteID: String
    public let archiveSourceState: String?
    public let sourceRevisionMS: Int64
    public let sourceRevisionPrecisionMS: Int64
    public let sourceBundleSHA256: String
    public let taskPayloadSHA256: String?
    public let displayName: String
    public let artifactID: String?
    public let taskID: String?
    public let remoteTaskID: String?
    public let connection: LibraryBackupManifest.ConnectionProvenance
    public let byteLength: Int64
    public let sha256: String
    public let createdAtMS: Int64
    public let restoreTransactionID: String?
    public let restoreGroupID: String?
    fileprivate var fileName: String { "\(id.uuidString.lowercased()).mp4" }
}

/// Archive-restored videos are independent local files. They never create a task, connection, or certificate pin.
public final class RestoredVideoAttachmentStore: @unchecked Sendable {
    private let root: URL
    private let journalRoot: URL
    private let fm = FileManager.default
    public private(set) var lastListingIssueCount = 0
    private static let lock = NSRecursiveLock()
    public init(directory: URL? = nil, journalRoot: URL? = nil) {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let fallback = base.appendingPathComponent("PadNote/restored-videos", isDirectory: true)
        root = directory ?? LibraryBackupUITestPaths.directory("restored-videos", fallback: fallback)
        self.journalRoot = journalRoot ?? LibraryBackupTransactionGate.defaultJournalRoot
    }
    public func listing(excludingNoteIDs: Set<String> = []) throws -> [RestoredVideoAttachment] {
        guard fm.fileExists(atPath: root.path) else { return [] }
        try UserCoverPresetStore.requireDirectoryForResources(root)
        let urls = try fm.contentsOfDirectory(at: root, includingPropertiesForKeys: nil)
        guard urls.count <= 20_000 else { throw LibraryBackupError.sizeLimit("恢复视频数量超限") }
        var values = [RestoredVideoAttachment](), allValues = [RestoredVideoAttachment](), issueNames = Set<String>()
        for url in urls where url.pathExtension == "json" {
            do {
                _ = try LibraryBackupArchive.validateRegularSource(url, maximumBytes: 64 * 1024)
                let value = try JSONDecoder().decode(RestoredVideoAttachment.self, from: LibraryBackupArchive.readSmallFile(url, maximumBytes: 64 * 1024))
                guard value.originKind == "restored_archive", url.lastPathComponent == "\(value.id.uuidString.lowercased()).json",
                      value.sourceRevisionMS >= 0, [1, 1000].contains(value.sourceRevisionPrecisionMS),
                      value.sourceRevisionPrecisionMS != 1000 || value.sourceRevisionMS % 1000 == 0,
                      value.createdAtMS >= 0, value.displayName.utf8.count <= 256,
                      value.sourceBundleSHA256.range(of: "^[0-9a-f]{64}$", options: .regularExpression) != nil,
                      value.taskPayloadSHA256 == nil || value.taskPayloadSHA256!.range(of: "^[0-9a-f]{64}$", options: .regularExpression) != nil,
                      value.sha256.range(of: "^[0-9a-f]{64}$", options: .regularExpression) != nil,
                      value.connection.certificateSHA256 == nil || value.connection.certificateSHA256!.range(of: "^[0-9a-f]{64}$", options: .regularExpression) != nil else {
                    throw LibraryBackupError.invalidManifest("恢复视频记录无效")
                }
                guard value.byteLength > 0, value.byteLength <= LibraryBackupArchive.maxVideoBytes else { throw LibraryBackupError.invalidManifest("恢复视频大小无效") }
                allValues.append(value)
                let canonicalNoteID = value.noteID.map { UUID(uuidString: $0)?.uuidString.lowercased() ?? $0 }
                if let canonicalNoteID, excludingNoteIDs.contains(canonicalNoteID) { continue }
                _ = try LibraryBackupArchive.validateRegularSource(root.appendingPathComponent(value.fileName), maximumBytes: LibraryBackupArchive.maxVideoBytes)
                if LibraryBackupTransactionGate.isVisible(originKind: value.originKind,
                    transactionID: value.restoreTransactionID, groupID: value.restoreGroupID, kind: "video",
                    localID: value.id.uuidString.lowercased(), in: journalRoot) { values.append(value) }
            } catch { issueNames.insert(url.lastPathComponent) }
        }
        let known = Set(allValues.flatMap { [$0.fileName, "\($0.id.uuidString.lowercased()).json"] })
        issueNames.formUnion(urls.filter { !known.contains($0.lastPathComponent) }.map(\.lastPathComponent))
        lastListingIssueCount = issueNames.count
        return values.sorted { $0.createdAtMS > $1.createdAtMS }
    }
    @discardableResult public func restore(from stagedMP4: URL, descriptor: LibraryBackupManifest.Video, newNoteID: String?,
                                           transactionID: String? = nil, attachmentID: UUID? = nil, groupID: String? = nil, cancellation: LibraryBackupCancellationToken? = nil) throws -> RestoredVideoAttachment {
        try NoteGroupCatalogFence.withWriter {
          try Self.lock.withBackupLock {
            guard descriptor.mediaType == "video/mp4", descriptor.originKind == "computer_task" || descriptor.originKind == "restored_archive",
                  descriptor.byteLength > 0, descriptor.byteLength <= LibraryBackupArchive.maxVideoBytes,
                  let digest = try? LibraryBackupArchive.hashFile(stagedMP4), digest.size == descriptor.byteLength,
                  digest.sha256 == descriptor.sha256 else { throw LibraryBackupError.invalidManifest("视频归档资源校验失败") }
            try fm.createDirectory(at: root, withIntermediateDirectories: true)
            try UserCoverPresetStore.requireDirectoryForResources(root)
            let id = attachmentID ?? UUID(), target = root.appendingPathComponent("\(id.uuidString.lowercased()).mp4")
            let copied = try LibraryBackupArchive.copyVerified(stagedMP4, to: target, maximumBytes: LibraryBackupArchive.maxVideoBytes, cancellation: cancellation)
            guard copied.size == descriptor.byteLength, copied.sha256 == descriptor.sha256 else { try? fm.removeItem(at: target); throw LibraryBackupError.sourceChanged }
            let value = RestoredVideoAttachment(id: id, noteID: newNoteID, originKind: "restored_archive", sourceNoteID: descriptor.sourceNoteID,
                archiveSourceState: descriptor.sourceState, sourceRevisionMS: descriptor.sourceRevisionMS,
                sourceRevisionPrecisionMS: descriptor.sourceRevisionPrecisionMS, sourceBundleSHA256: descriptor.sourceBundleSHA256,
                taskPayloadSHA256: descriptor.taskPayloadSHA256,
                displayName: descriptor.displayName, artifactID: descriptor.artifactID,
                taskID: descriptor.taskID, remoteTaskID: descriptor.remoteTaskID, connection: descriptor.connection,
                byteLength: descriptor.byteLength, sha256: descriptor.sha256, createdAtMS: descriptor.createdAtMS,
                restoreTransactionID: transactionID, restoreGroupID: groupID)
            let meta = root.appendingPathComponent("\(id.uuidString.lowercased()).json")
            do { try UserCoverPresetStore.writeResourceMetadata(value, to: meta) }
            catch { try? fm.removeItem(at: target); throw error }
            return value
          }
        }
    }
    public func fileURL(for value: RestoredVideoAttachment) throws -> URL {
        let current = try listing().first { $0.id == value.id }
        guard let current else { throw LibraryBackupError.transaction("找不到恢复视频") }
        let file = root.appendingPathComponent(current.fileName), actual = try LibraryBackupArchive.hashFile(file)
        guard actual.size == current.byteLength, actual.sha256 == current.sha256 else { throw LibraryBackupError.sourceChanged }
        return file
    }
    public func metadataURL(for value: RestoredVideoAttachment) throws -> URL {
        guard let current = try listing().first(where: { $0.id == value.id }) else { throw LibraryBackupError.transaction("找不到恢复视频") }
        let metadata = root.appendingPathComponent("\(current.id.uuidString.lowercased()).json")
        _ = try LibraryBackupArchive.validateRegularSource(metadata, maximumBytes: 64 * 1024)
        return metadata
    }
    public func remove(_ value: RestoredVideoAttachment) throws {
        try NoteGroupCatalogFence.withWriter {
        let current = try listing().first { $0.id == value.id }
        guard let current else { throw LibraryBackupError.transaction("找不到恢复视频") }
        let file = root.appendingPathComponent(current.fileName), meta = root.appendingPathComponent("\(current.id.uuidString.lowercased()).json")
        try fm.removeItem(at: file); try fm.removeItem(at: meta)
        }
    }
    func rollbackPartial(id: UUID, transactionID: String, groupID: String, expectedSHA256: String) throws {
        try NoteGroupCatalogFence.withWriter {
          try Self.lock.withBackupLock {
            let stem = id.uuidString.lowercased()
            guard expectedSHA256.range(of: "^[0-9a-f]{64}$", options: .regularExpression) != nil else { throw LibraryBackupError.unsafeFile }
            let meta = root.appendingPathComponent(stem + ".json"), file = root.appendingPathComponent(stem + ".mp4")
            var current: RestoredVideoAttachment?
            if fm.fileExists(atPath: meta.path) {
                _ = try LibraryBackupArchive.validateRegularSource(meta, maximumBytes: 64 * 1024)
                current = try JSONDecoder().decode(RestoredVideoAttachment.self, from: LibraryBackupArchive.readSmallFile(meta, maximumBytes: 64 * 1024))
                guard current?.id == id, current?.restoreTransactionID == transactionID, current?.restoreGroupID == groupID,
                      current?.sha256 == expectedSHA256 else { throw LibraryBackupError.transaction("恢复视频所有权不匹配") }
            }
            if fm.fileExists(atPath: file.path) {
                _ = try LibraryBackupArchive.validateRegularSource(file, maximumBytes: LibraryBackupArchive.maxVideoBytes)
                guard try LibraryBackupArchive.hashFile(file).sha256 == expectedSHA256 else { throw LibraryBackupError.sourceChanged }
                try fm.removeItem(at: file)
            }
            if current != nil { try fm.removeItem(at: meta) }
          }
        }
    }

    func rollback(_ value: RestoredVideoAttachment, transactionID: String) throws {
        try NoteGroupCatalogFence.withWriter {
          try Self.lock.withBackupLock {
            let meta = root.appendingPathComponent("\(value.id.uuidString.lowercased()).json")
            _ = try LibraryBackupArchive.validateRegularSource(meta, maximumBytes: 64 * 1024)
            let current = try JSONDecoder().decode(RestoredVideoAttachment.self, from: LibraryBackupArchive.readSmallFile(meta, maximumBytes: 64 * 1024))
            guard current.id == value.id, current.restoreTransactionID == transactionID else { throw LibraryBackupError.transaction("恢复视频回滚所有权不匹配") }
            let file = root.appendingPathComponent(current.fileName)
            _ = try LibraryBackupArchive.validateRegularSource(file, maximumBytes: LibraryBackupArchive.maxVideoBytes)
            try fm.removeItem(at: file); try fm.removeItem(at: meta)
          }
        }
    }
}

private extension NSRecursiveLock { func withBackupLock<T>(_ body: () throws -> T) rethrows -> T { lock(); defer { unlock() }; return try body() } }
