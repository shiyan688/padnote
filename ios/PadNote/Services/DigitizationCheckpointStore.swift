import Combine
import CryptoKit
import Foundation
import UIKit

enum DigitizationError: Error, LocalizedError, Equatable {
    case invalidSource(String)
    case sourceChanged
    case recipientChanged
    case staleCheckpoint
    case damagedCheckpoint
    case sizeLimit(String)
    case requestInProgress
    case uncertainRequest(Int)

    var errorDescription: String? {
        switch self {
        case .invalidSource(let message), .sizeLimit(let message): return message
        case .sourceChanged: return "原笔记或 PDF 已变化。请保留此草稿并新建数字化批次。"
        case .recipientChanged: return "接收模型配置已变化。请保留此草稿并新建数字化批次。"
        case .staleCheckpoint: return "数字化草稿已由另一项操作更新，请重新打开。"
        case .damagedCheckpoint: return "数字化草稿已损坏，无法继续。"
        case .requestInProgress: return "同一批次已有页面正在处理中，请等待它结束。"
        case .uncertainRequest(let page):
            return "第 \(page + 1) 页上次请求的结果未知，可能已经处理或收费。确认后才能重新发送。"
        }
    }
}

struct DigitizationRecipientIdentity: Codable, Equatable {
    let profileID: String
    let displayName: String
    let provider: String
    let mode: String
    let visionDestination: String
    let visionModel: String
    let textDestination: String
    let textModel: String
    let fingerprint: String

    init(profile: AIProfile) throws {
        _ = try AIClient.endpointURL(profile.visionEndpoint)
        _ = try AIClient.endpointURL(profile.textEndpoint)
        profileID = profile.id
        displayName = profile.name
        provider = profile.provider.rawValue
        mode = profile.mode.rawValue
        visionDestination = Self.destination(profile.visionEndpoint)
        visionModel = profile.visionModel
        textDestination = Self.destination(profile.textEndpoint)
        textModel = profile.textModel
        let fields = [profile.id, provider, mode, profile.visionEndpoint, visionModel,
                      profile.textEndpoint, textModel]
        fingerprint = DigitizationHash.data(Data(fields.joined(separator: "\u{0}").utf8))
    }

    private static func destination(_ value: String) -> String {
        guard let components = URLComponents(string: value), let host = components.host else { return "" }
        let port = components.port.map { ":\($0)" } ?? ""
        return host + port + components.path
    }
}

struct DigitizationSourceIdentity: Codable, Equatable {
    let noteID: String
    let noteFingerprint: String
    let pdfFingerprint: String?
    let measuredPageCount: Int
    let recipient: DigitizationRecipientIdentity
}

struct DigitizationPageResult: Codable, Equatable, Identifiable {
    var id: Int { pageIndex }
    let pageIndex: Int
    let markdown: String
    let completedAt: Date
}

struct DigitizationPageAttempt: Codable, Equatable {
    let pageIndex: Int
    let ownerID: String
    let startedAt: Date
}

enum DigitizationBatchState: String, Codable {
    case partial
    case readyToPublish
    case published
}

struct DigitizationCheckpoint: Codable, Equatable, Identifiable {
    static let schemaVersion = 1
    let version: Int
    let id: String
    let source: DigitizationSourceIdentity
    let noteTitle: String
    var revision: Int
    var pages: [DigitizationPageResult]
    var state: DigitizationBatchState
    var lastMessage: String?
    var activeAttempt: DigitizationPageAttempt?
    let createdAt: Date
    var updatedAt: Date
    var publishedAt: Date?

    var completedPages: Set<Int> { Set(pages.map(\.pageIndex)) }
    var remainingPages: [Int] {
        (0..<source.measuredPageCount).filter { !completedPages.contains($0) }
    }
    var isComplete: Bool { pages.count == source.measuredPageCount && remainingPages.isEmpty }

    func markdown(markIncomplete: Bool = true) -> String {
        var sections = ["# \(noteTitle)"]
        if markIncomplete && !isComplete {
            sections.append("> **未完成的数字化草稿**：已完成 \(pages.count) / \(source.measuredPageCount) 页。缺少第 \(remainingPages.map { String($0 + 1) }.joined(separator: "、")) 页。")
            if let activeAttempt {
                sections.append("> 第 \(activeAttempt.pageIndex + 1) 页上次请求的结果未知，可能已经处理或收费；重新发送前需要用户确认。")
            }
        }
        sections += pages.sorted { $0.pageIndex < $1.pageIndex }.map {
            "## 第 \($0.pageIndex + 1) 页\n\n\($0.markdown)"
        }
        return sections.joined(separator: "\n\n")
    }
}

struct DigitizationRecoveryItem: Identifiable, Equatable {
    let id: String
    let filename: String
    let reason: String
    fileprivate let url: URL
}

private struct DigitizationContentFingerprint: Encodable {
    let schemaVersion: Int
    let id: String
    let title: String
    let pageWidth: Double
    let pageHeight: Double
    let pageGap: Double
    let pageCount: Int
    let pdfPageCount: Int
    let strokes: [InkStroke]
    let textFlows: [NoteTextFlow]
    let images: [NoteImage]
    let pageStyle: PageStyle

    init(_ note: NoteDocument) {
        schemaVersion = note.schemaVersion; id = note.id; title = note.title
        pageWidth = note.pageWidth; pageHeight = note.pageHeight; pageGap = note.pageGap
        pageCount = note.pageCount; pdfPageCount = note.pdfPageCount
        strokes = note.strokes; textFlows = note.textFlows; images = note.images; pageStyle = note.pageStyle
    }
}

private enum DigitizationHash {
    static func data(_ data: Data) -> String {
        SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }

    static func file(_ url: URL, limit: Int) throws -> String {
        let input = try FileHandle(forReadingFrom: url)
        defer { try? input.close() }
        var hasher = SHA256()
        var total = 0
        while let chunk = try input.read(upToCount: 64 * 1024), !chunk.isEmpty {
            total += chunk.count
            guard total <= limit else { throw DigitizationError.sizeLimit("PDF 超过 100 MB，无法建立可恢复批次。") }
            hasher.update(data: chunk)
        }
        return hasher.finalize().map { String(format: "%02x", $0) }.joined()
    }
}

private enum DigitizationDirectoryLocks {
    private static let guardLock = NSLock()
    private static var locks: [String: NSRecursiveLock] = [:]

    static func lock(for directory: URL) -> NSRecursiveLock {
        let key = directory.resolvingSymlinksInPath().standardizedFileURL.path
        guardLock.lock(); defer { guardLock.unlock() }
        if let existing = locks[key] { return existing }
        let created = NSRecursiveLock(); locks[key] = created; return created
    }
}

private enum DigitizationExecutionLeases {
    private static let lock = NSLock()
    private static var active: Set<String> = []

    static func acquire(_ key: String) -> Bool {
        lock.lock(); defer { lock.unlock() }
        return active.insert(key).inserted
    }

    static func release(_ key: String) {
        lock.lock(); active.remove(key); lock.unlock()
    }

    static func contains(_ key: String) -> Bool {
        lock.lock(); defer { lock.unlock() }
        return active.contains(key)
    }
}

final class DigitizationCheckpointStore {
    static let maximumCheckpointBytes = 16 * 1024 * 1024
    static let maximumPageResponseBytes = 1024 * 1024
    static let maximumImageBytes = 6 * 1024 * 1024
    static let maximumContextBytes = 64 * 1024

    private let directory: URL
    private let fileManager: FileManager
    private let lock: NSRecursiveLock
    private let faultInjector: ((DigitizationStoreFaultPoint) throws -> Void)?
    private(set) var recoveryItems: [DigitizationRecoveryItem] = []

    init(directory: URL? = nil, fileManager: FileManager = .default,
         faultInjector: ((DigitizationStoreFaultPoint) throws -> Void)? = nil) {
        self.fileManager = fileManager
        self.faultInjector = faultInjector
        let resolved: URL
        if let directory {
            resolved = directory
        } else {
            let base = fileManager.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
                ?? fileManager.urls(for: .documentDirectory, in: .userDomainMask).first!
            resolved = base.appendingPathComponent("PadNote/digitization-checkpoints", isDirectory: true)
        }
        try? fileManager.createDirectory(at: resolved, withIntermediateDirectories: true)
        self.directory = resolved.resolvingSymlinksInPath().standardizedFileURL
        lock = DigitizationDirectoryLocks.lock(for: self.directory)
    }

    func identity(document: NoteDocument, measuredPageCount: Int, pdfURL: URL?,
                  profile: AIProfile) throws -> DigitizationSourceIdentity {
        let checked = try document.validated()
        guard (1...500).contains(measuredPageCount) else {
            throw DigitizationError.invalidSource("数字化页数必须在 1–500 页之间。")
        }
        let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]
        let content = try encoder.encode(DigitizationContentFingerprint(checked))
        let pdfFingerprint: String?
        if checked.pdfPageCount > 0 {
            guard let pdfURL else { throw DigitizationError.invalidSource("PDF 原文不存在，无法继续数字化。") }
            pdfFingerprint = try DigitizationHash.file(pdfURL, limit: NoteArchive.maxPDFBytes)
        } else {
            pdfFingerprint = nil
        }
        return DigitizationSourceIdentity(
            noteID: checked.id,
            noteFingerprint: DigitizationHash.data(content),
            pdfFingerprint: pdfFingerprint,
            measuredPageCount: measuredPageCount,
            recipient: try DigitizationRecipientIdentity(profile: profile)
        )
    }

    func loadAll(noteID: String? = nil) -> [DigitizationCheckpoint] {
        lock.lock(); defer { lock.unlock() }
        recoveryItems = []
        do { try fileManager.createDirectory(at: directory, withIntermediateDirectories: true) }
        catch {
            recoveryItems = [.init(id: UUID().uuidString, filename: directory.lastPathComponent,
                                   reason: error.localizedDescription, url: directory)]
            return []
        }
        let urls: [URL]
        do {
            let contents = try fileManager.contentsOfDirectory(
                at: directory, includingPropertiesForKeys: [.fileSizeKey])
            for orphan in contents where orphan.lastPathComponent.hasPrefix(".write-")
                || orphan.lastPathComponent.hasPrefix(".pdf-") {
                try? fileManager.removeItem(at: orphan)
            }
            urls = contents.filter {
                $0.pathExtension == "json" && $0.lastPathComponent.hasPrefix("batch-")
            }
        } catch {
            recoveryItems = [.init(id: UUID().uuidString, filename: directory.lastPathComponent,
                                   reason: error.localizedDescription, url: directory)]
            return []
        }
        return urls.compactMap { url in
            do {
                let values = try url.resourceValues(forKeys: [.fileSizeKey])
                guard let size = values.fileSize, size <= Self.maximumCheckpointBytes else {
                    throw DigitizationError.sizeLimit("检查点超过 16 MB 限制。")
                }
                let data = try readBounded(url, limit: Self.maximumCheckpointBytes)
                let value = try JSONDecoder().decode(DigitizationCheckpoint.self, from: data)
                try validate(value, expectedBatchID: nil, fileURL: url)
                if let noteID, value.source.noteID != noteID { return nil }
                return value
            } catch {
                recoveryItems.append(.init(id: url.lastPathComponent, filename: url.lastPathComponent,
                                           reason: error.localizedDescription, url: url))
                return nil
            }
        }.sorted { $0.updatedAt > $1.updatedAt }
    }

    func create(document: NoteDocument, identity: DigitizationSourceIdentity,
                pdfURL: URL?) throws -> DigitizationCheckpoint {
        lock.lock(); defer { lock.unlock() }
        try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
        let checked = try document.validated()
        let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]
        let fingerprint = DigitizationHash.data(try encoder.encode(DigitizationContentFingerprint(checked)))
        guard checked.id == identity.noteID,
              fingerprint == identity.noteFingerprint else {
            throw DigitizationError.sourceChanged
        }
        let batchID = UUID().uuidString
        if identity.pdfFingerprint != nil {
            guard let pdfURL else { throw DigitizationError.invalidSource("PDF 原文不存在，无法建立批次。") }
            let snapshot = pdfSnapshotURL(batchID: batchID)
            let digest = try copyPDFAtomically(from: pdfURL, to: snapshot)
            guard digest == identity.pdfFingerprint else {
                try? fileManager.removeItem(at: snapshot)
                throw DigitizationError.sourceChanged
            }
        }
        let now = Date()
        let checkpoint = DigitizationCheckpoint(
            version: DigitizationCheckpoint.schemaVersion, id: batchID, source: identity,
            noteTitle: document.title, revision: 0, pages: [], state: .partial,
            lastMessage: nil, activeAttempt: nil, createdAt: now, updatedAt: now, publishedAt: nil)
        do { try write(checkpoint) }
        catch { try? fileManager.removeItem(at: pdfSnapshotURL(batchID: batchID)); throw error }
        return checkpoint
    }

    func appendPage(batchID: String, expectedRevision: Int, pageIndex: Int,
                    markdown: String) throws -> DigitizationCheckpoint {
        lock.lock(); defer { lock.unlock() }
        guard markdown.utf8.count <= Self.maximumPageResponseBytes else {
            throw DigitizationError.sizeLimit("单页模型结果超过 1 MB，未写入草稿。")
        }
        guard !markdown.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw DigitizationError.invalidSource("模型未返回可保存的本页内容。")
        }
        var value = try read(batchID)
        guard value.revision == expectedRevision else { throw DigitizationError.staleCheckpoint }
        guard value.state != .published,
              value.activeAttempt == nil,
              (0..<value.source.measuredPageCount).contains(pageIndex),
              !value.completedPages.contains(pageIndex) else { throw DigitizationError.staleCheckpoint }
        value.pages.append(.init(pageIndex: pageIndex, markdown: markdown, completedAt: Date()))
        value.revision += 1; value.updatedAt = Date(); value.lastMessage = nil
        if value.isComplete { value.state = .readyToPublish }
        try write(value)
        if value.isComplete { try? fileManager.removeItem(at: pdfSnapshotURL(batchID: batchID)) }
        return value
    }

    func acquireExecutionLease(batchID: String) -> Bool {
        DigitizationExecutionLeases.acquire(leaseKey(batchID: batchID))
    }

    func releaseExecutionLease(batchID: String) {
        DigitizationExecutionLeases.release(leaseKey(batchID: batchID))
    }

    func claimPage(batchID: String, expectedRevision: Int, pageIndex: Int,
                   ownerID: String) throws -> DigitizationCheckpoint {
        lock.lock(); defer { lock.unlock() }
        guard DigitizationExecutionLeases.contains(leaseKey(batchID: batchID)),
              UUID(uuidString: ownerID) != nil else { throw DigitizationError.requestInProgress }
        var value = try read(batchID)
        guard value.revision == expectedRevision, value.state == .partial,
              value.activeAttempt == nil,
              (0..<value.source.measuredPageCount).contains(pageIndex),
              !value.completedPages.contains(pageIndex) else { throw DigitizationError.staleCheckpoint }
        value.activeAttempt = .init(pageIndex: pageIndex, ownerID: ownerID, startedAt: Date())
        value.revision += 1; value.updatedAt = Date(); value.lastMessage = nil
        try write(value)
        return value
    }

    func appendClaimedPage(batchID: String, expectedRevision: Int, pageIndex: Int,
                           ownerID: String, markdown: String) throws -> DigitizationCheckpoint {
        lock.lock(); defer { lock.unlock() }
        guard markdown.utf8.count <= Self.maximumPageResponseBytes else {
            throw DigitizationError.sizeLimit("单页模型结果超过 1 MB，未写入草稿。")
        }
        guard !markdown.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw DigitizationError.invalidSource("模型未返回可保存的本页内容。")
        }
        var value = try read(batchID)
        guard value.revision == expectedRevision, value.state == .partial,
              value.activeAttempt?.pageIndex == pageIndex,
              value.activeAttempt?.ownerID == ownerID,
              !value.completedPages.contains(pageIndex) else { throw DigitizationError.staleCheckpoint }
        value.pages.append(.init(pageIndex: pageIndex, markdown: markdown, completedAt: Date()))
        value.activeAttempt = nil
        value.revision += 1; value.updatedAt = Date(); value.lastMessage = nil
        if value.isComplete { value.state = .readyToPublish }
        try write(value)
        if value.isComplete { try? fileManager.removeItem(at: pdfSnapshotURL(batchID: batchID)) }
        return value
    }

    func confirmRetry(batchID: String, expectedRevision: Int) throws -> DigitizationCheckpoint {
        lock.lock(); defer { lock.unlock() }
        guard !DigitizationExecutionLeases.contains(leaseKey(batchID: batchID)) else {
            throw DigitizationError.requestInProgress
        }
        var value = try read(batchID)
        guard value.revision == expectedRevision, value.state == .partial,
              value.activeAttempt != nil else { throw DigitizationError.staleCheckpoint }
        value.activeAttempt = nil
        value.revision += 1; value.updatedAt = Date()
        value.lastMessage = "用户已确认重新发送结果未知的页面。"
        try write(value)
        return value
    }

    func markInterrupted(batchID: String, expectedRevision: Int, message: String) throws -> DigitizationCheckpoint {
        lock.lock(); defer { lock.unlock() }
        var value = try read(batchID)
        guard value.revision == expectedRevision, value.state == .partial else { throw DigitizationError.staleCheckpoint }
        value.revision += 1; value.updatedAt = Date(); value.lastMessage = String(message.prefix(500))
        try write(value)
        return value
    }

    func markPublished(batchID: String, expectedRevision: Int) throws -> DigitizationCheckpoint {
        lock.lock(); defer { lock.unlock() }
        var value = try read(batchID)
        guard value.revision == expectedRevision, value.isComplete else { throw DigitizationError.staleCheckpoint }
        value.revision += 1; value.updatedAt = Date(); value.state = .published
        value.publishedAt = Date(); value.lastMessage = nil
        try write(value)
        try? fileManager.removeItem(at: pdfSnapshotURL(batchID: batchID))
        return value
    }

    func verifiedPDFSnapshot(for checkpoint: DigitizationCheckpoint) throws -> URL? {
        guard let expected = checkpoint.source.pdfFingerprint else { return nil }
        let url = pdfSnapshotURL(batchID: checkpoint.id)
        guard fileManager.fileExists(atPath: url.path),
              try DigitizationHash.file(url, limit: NoteArchive.maxPDFBytes) == expected else {
            throw DigitizationError.sourceChanged
        }
        return url
    }

    func delete(_ checkpoint: DigitizationCheckpoint) throws {
        lock.lock(); defer { lock.unlock() }
        try? fileManager.removeItem(at: pdfSnapshotURL(batchID: checkpoint.id))
        try fileManager.removeItem(at: checkpointURL(batchID: checkpoint.id))
    }

    func deleteRecovery(_ item: DigitizationRecoveryItem) throws {
        lock.lock(); defer { lock.unlock() }
        guard item.url.deletingLastPathComponent().standardizedFileURL == directory.standardizedFileURL,
              item.url.pathExtension == "json" else { throw DigitizationError.damagedCheckpoint }
        try fileManager.removeItem(at: item.url)
        let filename = item.url.deletingPathExtension().lastPathComponent
        if filename.hasPrefix("batch-") {
            let identifier = String(filename.dropFirst("batch-".count))
            if UUID(uuidString: identifier) != nil {
                try? fileManager.removeItem(at: pdfSnapshotURL(batchID: identifier))
            }
        }
        recoveryItems.removeAll { $0.id == item.id }
    }

    func export(_ checkpoint: DigitizationCheckpoint) throws -> URL {
        let suffix = checkpoint.isComplete ? "complete" : "incomplete"
        let output = fileManager.temporaryDirectory
            .appendingPathComponent("digitization-\(checkpoint.id.prefix(8))-\(suffix).md")
        try checkpoint.markdown().write(to: output, atomically: true, encoding: .utf8)
        return output
    }

    private func read(_ batchID: String) throws -> DigitizationCheckpoint {
        let url = checkpointURL(batchID: batchID)
        let data = try readBounded(url, limit: Self.maximumCheckpointBytes)
        guard let value = try? JSONDecoder().decode(DigitizationCheckpoint.self, from: data) else {
            throw DigitizationError.damagedCheckpoint
        }
        try validate(value, expectedBatchID: batchID, fileURL: url)
        return value
    }

    private func write(_ checkpoint: DigitizationCheckpoint) throws {
        try validate(checkpoint, expectedBatchID: checkpoint.id,
                     fileURL: checkpointURL(batchID: checkpoint.id))
        let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]
        let data = try encoder.encode(checkpoint)
        guard data.count <= Self.maximumCheckpointBytes else {
            throw DigitizationError.sizeLimit("数字化检查点超过 16 MB，未保存本页。")
        }
        let target = checkpointURL(batchID: checkpoint.id)
        let temporary = directory.appendingPathComponent(".write-\(UUID().uuidString).tmp")
        try data.write(to: temporary, options: .atomic)
        do {
            let reopened = try Data(contentsOf: temporary)
            guard try JSONDecoder().decode(DigitizationCheckpoint.self, from: reopened) == checkpoint else {
                throw DigitizationError.damagedCheckpoint
            }
            try faultInjector?(.afterTemporaryWrite)
            if fileManager.fileExists(atPath: target.path) {
                _ = try fileManager.replaceItemAt(target, withItemAt: temporary)
            } else {
                try fileManager.moveItem(at: temporary, to: target)
            }
        } catch {
            try? fileManager.removeItem(at: temporary)
            throw error
        }
    }

    private func copyPDFAtomically(from source: URL, to target: URL) throws -> String {
        let temporary = directory.appendingPathComponent(".pdf-\(UUID().uuidString).tmp")
        fileManager.createFile(atPath: temporary.path, contents: nil)
        let input = try FileHandle(forReadingFrom: source)
        let output = try FileHandle(forWritingTo: temporary)
        var hasher = SHA256(); var total = 0
        do {
            while let chunk = try input.read(upToCount: 64 * 1024), !chunk.isEmpty {
                total += chunk.count
                guard total <= NoteArchive.maxPDFBytes else {
                    throw DigitizationError.sizeLimit("PDF 超过 100 MB，无法建立可恢复批次。")
                }
                hasher.update(data: chunk); try output.write(contentsOf: chunk)
            }
            try output.synchronize(); try input.close(); try output.close()
            try fileManager.moveItem(at: temporary, to: target)
        } catch {
            try? input.close(); try? output.close(); try? fileManager.removeItem(at: temporary)
            throw error
        }
        return hasher.finalize().map { String(format: "%02x", $0) }.joined()
    }

    private func checkpointURL(batchID: String) -> URL {
        directory.appendingPathComponent("batch-\(batchID).json")
    }

    private func pdfSnapshotURL(batchID: String) -> URL {
        directory.appendingPathComponent("batch-\(batchID).pdf")
    }

    private func readBounded(_ url: URL, limit: Int) throws -> Data {
        let input = try FileHandle(forReadingFrom: url)
        defer { try? input.close() }
        let data = try input.read(upToCount: limit + 1) ?? Data()
        guard data.count <= limit else { throw DigitizationError.sizeLimit("检查点超过 16 MB 限制。") }
        return data
    }

    private func validate(_ value: DigitizationCheckpoint, expectedBatchID: String?,
                          fileURL: URL) throws {
        let hex = try! NSRegularExpression(pattern: "^[0-9a-f]{64}$")
        func isDigest(_ text: String) -> Bool {
            hex.firstMatch(in: text, range: NSRange(text.startIndex..., in: text)) != nil
        }
        func isIdentifier(_ text: String) -> Bool {
            !text.isEmpty && text.count <= 120 && text.unicodeScalars.allSatisfy {
                CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "-_.:")).contains($0)
            }
        }
        func isDestination(_ text: String) -> Bool {
            guard !text.contains("@"), !text.contains("?"), !text.contains("#"),
                  let components = URLComponents(string: "https://" + text),
                  components.user == nil, components.password == nil,
                  components.query == nil, components.fragment == nil,
                  components.host != nil else { return false }
            return true
        }
        guard value.version == DigitizationCheckpoint.schemaVersion,
              UUID(uuidString: value.id) != nil,
              expectedBatchID == nil || value.id == expectedBatchID,
              fileURL.lastPathComponent == "batch-\(value.id).json",
              value.revision >= 0,
              isIdentifier(value.source.noteID),
              isDigest(value.source.noteFingerprint),
              value.source.pdfFingerprint.map(isDigest) ?? true,
              (1...500).contains(value.source.measuredPageCount),
              isIdentifier(value.source.recipient.profileID),
              isDigest(value.source.recipient.fingerprint),
              !value.source.recipient.displayName.isEmpty,
              value.source.recipient.displayName.count <= 200,
              isDestination(value.source.recipient.visionDestination),
              value.source.recipient.visionDestination.count <= 2048,
              !value.source.recipient.visionModel.isEmpty,
              value.source.recipient.visionModel.count <= 500,
              isDestination(value.source.recipient.textDestination),
              value.source.recipient.textDestination.count <= 2048,
              !value.source.recipient.textModel.isEmpty,
              value.source.recipient.textModel.count <= 500,
              AIProvider(rawValue: value.source.recipient.provider) != nil,
              AIProfileMode(rawValue: value.source.recipient.mode) != nil,
              value.pages.count <= value.source.measuredPageCount,
              Set(value.pages.map(\.pageIndex)).count == value.pages.count,
              value.pages.allSatisfy({ page in
                  (0..<value.source.measuredPageCount).contains(page.pageIndex)
                      && !page.markdown.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                      && page.markdown.utf8.count <= Self.maximumPageResponseBytes
                      && page.completedAt.timeIntervalSinceReferenceDate.isFinite
                      && page.completedAt >= value.createdAt
                      && page.completedAt <= value.updatedAt
              }),
              value.noteTitle.count <= 200,
              (value.lastMessage?.count ?? 0) <= 500,
              value.createdAt.timeIntervalSinceReferenceDate.isFinite,
              value.updatedAt.timeIntervalSinceReferenceDate.isFinite,
              value.publishedAt?.timeIntervalSinceReferenceDate.isFinite ?? true,
              value.updatedAt >= value.createdAt else {
            throw DigitizationError.damagedCheckpoint
        }
        let complete = value.pages.count == value.source.measuredPageCount
        let attemptValid = value.activeAttempt.map { attempt in
            value.state == .partial
                && (0..<value.source.measuredPageCount).contains(attempt.pageIndex)
                && !value.completedPages.contains(attempt.pageIndex)
                && UUID(uuidString: attempt.ownerID) != nil
                && attempt.startedAt.timeIntervalSinceReferenceDate.isFinite
                && attempt.startedAt >= value.createdAt
                && attempt.startedAt <= value.updatedAt
        } ?? true
        guard attemptValid,
              (value.state == .partial && !complete && value.publishedAt == nil)
                || (value.state == .readyToPublish && complete && value.publishedAt == nil
                    && value.activeAttempt == nil)
                || (value.state == .published && complete
                    && value.activeAttempt == nil
                    && value.publishedAt.map { $0 >= value.createdAt && $0 <= value.updatedAt } == true) else {
            throw DigitizationError.damagedCheckpoint
        }
    }

    private func leaseKey(batchID: String) -> String {
        directory.path + "\u{0}" + batchID
    }
}

enum DigitizationStoreFaultPoint {
    case afterTemporaryWrite
}

protocol DigitizationPageClient {
    func transcribe(pngData: Data, prompt: String, context: String,
                    profile: AIProfile) async throws -> String
}

struct LiveDigitizationPageClient: DigitizationPageClient {
    func transcribe(pngData: Data, prompt: String, context: String,
                    profile: AIProfile) async throws -> String {
        guard let image = UIImage(data: pngData) else { throw AIClientError.invalidResponse }
        let settings = AISettings(endpoint: profile.visionEndpoint, model: profile.visionModel,
                                  keyReference: profile.visionKeyReference)
        return try await AIClient(settings: settings).complete(image: image, prompt: prompt, context: context)
    }
}

@MainActor
protocol DigitizationVaultPublishing: AnyObject {
    func publishDigitization(note: NoteDocument, checkpoint: DigitizationCheckpoint) throws
}

@MainActor
final class DigitizationSession: ObservableObject {
    typealias PageRenderer = (NoteDocument, Int, URL?) throws -> Data
    typealias IdentityBuilder = (NoteDocument, Int, URL?, AIProfile) throws -> DigitizationSourceIdentity
    @Published private(set) var checkpoint: DigitizationCheckpoint?
    @Published private(set) var batches: [DigitizationCheckpoint] = []
    @Published private(set) var recoveryItems: [DigitizationRecoveryItem] = []
    @Published private(set) var running = false
    @Published private(set) var preparing = false
    @Published private(set) var status: String?
    @Published private(set) var identity: DigitizationSourceIdentity?

    private let store: DigitizationCheckpointStore
    private let client: DigitizationPageClient
    private let renderer: PageRenderer
    private let identityBuilder: IdentityBuilder
    private var operation: Task<Void, Never>?
    private var operationID: UUID?
    private var preparationID: UUID?

    init(store: DigitizationCheckpointStore = DigitizationCheckpointStore(),
         client: DigitizationPageClient = LiveDigitizationPageClient(),
         identityBuilder: IdentityBuilder? = nil,
         renderer: @escaping PageRenderer = { document, page, pdfURL in
             let image = NoteRenderer.renderPage(document: document, page: page, pdfURL: pdfURL, maxEdge: 1600)
             guard let data = image.pngData() else { throw AIClientError.invalidResponse }
             return data
         }) {
        self.store = store; self.client = client; self.renderer = renderer
        self.identityBuilder = identityBuilder ?? { document, measuredPageCount, pdfURL, profile in
            try store.identity(document: document, measuredPageCount: measuredPageCount,
                               pdfURL: pdfURL, profile: profile)
        }
    }

    var compatibleBatches: [DigitizationCheckpoint] {
        guard let identity else { return [] }
        return batches.filter { $0.source == identity }
    }
    var incompatibleBatches: [DigitizationCheckpoint] {
        guard let identity else { return batches.filter { $0.id != checkpoint?.id } }
        return batches.filter { $0.source != identity && $0.id != checkpoint?.id }
    }

    func loadHistory(noteID: String) {
        cancel(); identity = nil
        batches = store.loadAll(noteID: noteID)
        recoveryItems = store.recoveryItems
        checkpoint = batches.first { $0.state != .published } ?? batches.first
    }

    func blockSending(_ message: String) {
        invalidatePreparation(); identity = nil; status = message; preparing = false
    }

    func prepare(document: NoteDocument, measuredPageCount: Int, pdfURL: URL?,
                 profile: AIProfile) async {
        loadHistory(noteID: document.id)
        let preparationToken = beginPreparation()
        preparing = true; status = "正在检查可恢复草稿…"
        do {
            let identityBuilder = self.identityBuilder
            let source = try await Task.detached(priority: .userInitiated) {
                try identityBuilder(document, measuredPageCount, pdfURL, profile)
            }.value
            guard preparationID == preparationToken else { return }
            identity = source
            checkpoint = batches.first { $0.source == source && $0.state != .published }
                ?? batches.first { $0.source == source }
            if let checkpoint {
                if let attempt = checkpoint.activeAttempt {
                    status = DigitizationError.uncertainRequest(attempt.pageIndex).localizedDescription
                } else {
                    status = checkpoint.state == .published
                        ? "此批次已保存到知识库。"
                        : "已恢复草稿：完成 \(checkpoint.pages.count) / \(checkpoint.source.measuredPageCount) 页。"
                }
            } else if !incompatibleBatches.isEmpty {
                status = "发现来源或接收模型不同的旧草稿。新建批次会保留它们。"
            } else {
                status = nil
            }
        } catch {
            guard preparationID == preparationToken else { return }
            identity = nil; status = error.localizedDescription
        }
        guard preparationID == preparationToken else { return }
        preparationID = nil; preparing = false
    }

    func createBatch(document: NoteDocument, pdfURL: URL?) async {
        guard let identity else { status = "请先完成来源检查。"; return }
        let preparationToken = beginPreparation()
        preparing = true
        do {
            let store = self.store
            let created = try await Task.detached(priority: .userInitiated) {
                try store.create(document: document, identity: identity, pdfURL: pdfURL)
            }.value
            guard preparationID == preparationToken else { return }
            checkpoint = created; batches.insert(created, at: 0)
            status = "已新建批次，尚未发送页面。"
        } catch {
            guard preparationID == preparationToken else { return }
            status = error.localizedDescription
        }
        guard preparationID == preparationToken else { return }
        preparationID = nil; preparing = false
    }

    func select(_ batch: DigitizationCheckpoint) {
        guard batch.source == identity else {
            status = batch.source.recipient.fingerprint == identity?.recipient.fingerprint
                ? DigitizationError.sourceChanged.localizedDescription
                : DigitizationError.recipientChanged.localizedDescription
            return
        }
        checkpoint = batch
    }

    func start(document: NoteDocument, sourcePDFURL: URL?, profile: AIProfile,
               publisher: DigitizationVaultPublishing) {
        guard !running, var current = checkpoint, let identity,
              current.source == identity else { status = "当前来源与草稿不匹配，请新建批次。"; return }
        if current.state == .published { status = "此批次已保存到知识库。"; return }
        if let attempt = current.activeAttempt {
            status = DigitizationError.uncertainRequest(attempt.pageIndex).localizedDescription
            return
        }
        if current.isComplete {
            publish(current, document: document, publisher: publisher)
            return
        }
        let token = UUID(); operationID = token; running = true; status = nil
        operation = Task { [weak self] in
            guard let self else { return }
            var leasedBatchID: String?
            defer {
                if let leasedBatchID { self.store.releaseExecutionLease(batchID: leasedBatchID) }
            }
            do {
                let store = self.store
                let actualIdentity = try await Task.detached(priority: .userInitiated) {
                    try store.identity(document: document,
                                       measuredPageCount: current.source.measuredPageCount,
                                       pdfURL: sourcePDFURL, profile: profile)
                }.value
                guard actualIdentity.recipient == current.source.recipient else {
                    throw DigitizationError.recipientChanged
                }
                guard actualIdentity.noteFingerprint == current.source.noteFingerprint,
                      actualIdentity.pdfFingerprint == current.source.pdfFingerprint,
                      actualIdentity.measuredPageCount == current.source.measuredPageCount else {
                    throw DigitizationError.sourceChanged
                }
                try Task.checkCancellation(); try ensureCurrent(token)
                let pdfSnapshot = try store.verifiedPDFSnapshot(for: current)
                var renderDocument = document; renderDocument.pageCount = current.source.measuredPageCount
                let prompt = "请准确转写此页笔记为 Markdown。数学公式用 LaTeX，流程图可用 Mermaid。不要猜测模糊内容，用[无法辨认]标出，不加开场白，不省略内容。"
                guard store.acquireExecutionLease(batchID: current.id) else {
                    throw DigitizationError.requestInProgress
                }
                leasedBatchID = current.id
                for page in current.remainingPages {
                    try Task.checkCancellation(); try ensureCurrent(token)
                    let png = try renderer(renderDocument, page, pdfSnapshot)
                    guard png.count <= DigitizationCheckpointStore.maximumImageBytes else {
                        throw DigitizationError.sizeLimit("第 \(page + 1) 页图片超过 6 MB，未发送。")
                    }
                    let rawContext = renderDocument.textFlows.filter { $0.anchorPageIndex == page }
                        .map(\.source).joined(separator: "\n")
                    let context = String(decoding: Data(rawContext.utf8).prefix(
                        DigitizationCheckpointStore.maximumContextBytes), as: UTF8.self)
                    try Task.checkCancellation(); try ensureCurrent(token)
                    current = try store.claimPage(batchID: current.id,
                                                  expectedRevision: current.revision,
                                                  pageIndex: page, ownerID: token.uuidString)
                    checkpoint = current; replaceBatch(current)
                    try Task.checkCancellation(); try ensureCurrent(token)
                    let result = try await client.transcribe(pngData: png, prompt: prompt,
                                                             context: context, profile: profile)
                    try Task.checkCancellation(); try ensureCurrent(token)
                    current = try store.appendClaimedPage(batchID: current.id,
                                                          expectedRevision: current.revision,
                                                          pageIndex: page,
                                                          ownerID: token.uuidString,
                                                          markdown: result)
                    checkpoint = current; replaceBatch(current)
                }
                try ensureCurrent(token)
                publish(current, document: document, publisher: publisher)
            } catch is CancellationError {
                if operationID == token {
                    status = "已暂停，已完成页面保留在草稿中。"
                    if let checkpoint, checkpoint.state == .partial {
                        try? updateInterrupted(checkpoint, message: "用户暂停")
                    }
                }
            } catch {
                if operationID == token {
                    status = error.localizedDescription
                    if let checkpoint, checkpoint.state == .partial {
                        try? updateInterrupted(checkpoint, message: safeInterruptionMessage(error))
                    }
                }
            }
            if operationID == token { running = false; operationID = nil; operation = nil }
        }
    }

    func publishReady(document: NoteDocument, sourcePDFURL: URL?, profile: AIProfile,
                      publisher: DigitizationVaultPublishing) {
        guard !running, let current = checkpoint, current.isComplete,
              current.state != .published else { return }
        let token = UUID(); operationID = token; running = true
        operation = Task { [weak self] in
            guard let self else { return }
            do {
                let store = self.store
                let actual = try await Task.detached(priority: .userInitiated) {
                    try store.identity(document: document,
                                       measuredPageCount: current.source.measuredPageCount,
                                       pdfURL: sourcePDFURL, profile: profile)
                }.value
                guard actual.recipient == current.source.recipient else { throw DigitizationError.recipientChanged }
                guard actual.noteFingerprint == current.source.noteFingerprint,
                      actual.pdfFingerprint == current.source.pdfFingerprint else {
                    throw DigitizationError.sourceChanged
                }
                try ensureCurrent(token)
                publish(current, document: document, publisher: publisher)
            } catch { if operationID == token { status = error.localizedDescription } }
            if operationID == token { running = false; operationID = nil; operation = nil }
        }
    }

    func cancel() {
        invalidatePreparation()
        operationID = nil; operation?.cancel(); operation = nil; running = false
    }

    func pause() { invalidatePreparation(); operation?.cancel() }

    func exportCurrent() throws -> URL {
        guard let checkpoint else { throw DigitizationError.invalidSource("没有可导出的草稿。") }
        return try store.export(checkpoint)
    }

    func export(_ batch: DigitizationCheckpoint) throws -> URL { try store.export(batch) }

    func confirmRetryUnknownPage() {
        guard !running, let current = checkpoint, current.activeAttempt != nil else { return }
        do {
            let updated = try store.confirmRetry(batchID: current.id,
                                                 expectedRevision: current.revision)
            checkpoint = updated; replaceBatch(updated)
            status = "已确认重新发送。点击继续后只处理尚未完成的页面。"
        } catch { status = error.localizedDescription }
    }

    func delete(_ batch: DigitizationCheckpoint) throws {
        if checkpoint?.id == batch.id { cancel(); checkpoint = nil }
        try store.delete(batch); batches.removeAll { $0.id == batch.id }
    }

    func deleteRecovery(_ item: DigitizationRecoveryItem) throws {
        try store.deleteRecovery(item); recoveryItems.removeAll { $0.id == item.id }
    }

    func waitUntilIdle() async { await operation?.value }

    private func publish(_ value: DigitizationCheckpoint, document: NoteDocument,
                         publisher: DigitizationVaultPublishing) {
        do {
            try publisher.publishDigitization(note: document, checkpoint: value)
        } catch {
            status = "页面已全部完成，保存知识库失败：\(error.localizedDescription)"
            return
        }
        do {
            let published = try store.markPublished(batchID: value.id, expectedRevision: value.revision)
            checkpoint = published; replaceBatch(published)
            status = "已保存到知识库，可以检索或导出 Markdown。"
        } catch {
            status = "已保存到知识库，但草稿状态确认失败；可重新打开后重试本地保存，不会再次请求模型。"
        }
    }

    private func updateInterrupted(_ value: DigitizationCheckpoint, message: String) throws {
        let updated = try store.markInterrupted(batchID: value.id, expectedRevision: value.revision,
                                                message: message)
        checkpoint = updated; replaceBatch(updated)
    }

    private func ensureCurrent(_ token: UUID) throws {
        guard operationID == token else { throw CancellationError() }
    }

    private func beginPreparation() -> UUID {
        invalidatePreparation()
        let token = UUID(); preparationID = token
        return token
    }

    private func invalidatePreparation() {
        preparationID = nil
        preparing = false
    }

    private func safeInterruptionMessage(_ error: Error) -> String {
        if error is CancellationError { return "用户暂停" }
        if let error = error as? DigitizationError { return error.localizedDescription }
        if let error = error as? AIClientError { return error.localizedDescription }
        return "本页处理失败，可稍后从未完成页继续。"
    }

    private func replaceBatch(_ value: DigitizationCheckpoint) {
        batches.removeAll { $0.id == value.id }; batches.append(value)
        batches.sort { $0.updatedAt > $1.updatedAt }
    }
}
