import Combine
import Foundation
import PDFKit

public struct NotePendingDraft: Identifiable, Equatable {
    public var id: String { document.id }
    public let revision: Int
    public let document: NoteDocument
}

public struct NoteRecoveryItem: Identifiable, Equatable {
    public let id: String
    public let filename: String
    public let reason: String
    fileprivate let sourceURL: URL
}

@MainActor
public final class NoteLibrary: ObservableObject {
    @Published public private(set) var notes: [NoteDocument] = []
    @Published public private(set) var pendingDrafts: [NotePendingDraft] = []
    @Published public private(set) var recoveryItems: [NoteRecoveryItem] = []
    @Published public var errorMessage: String?

    private let directory: URL
    private let draftDirectory: URL
    private let fileManager = FileManager.default
    private let maxPDFBytes = NoteArchive.maxPDFBytes
    private let faultInjector: ((NoteLibraryFaultPoint, URL) -> Error?)?
    private let revisionGate: NoteRevisionGate
    private let draftWorker: NoteDraftWorker

    public init(
        directory: URL? = nil,
        faultInjector: ((NoteLibraryFaultPoint, URL) -> Error?)? = nil
    ) {
        let resolved: URL
        if let directory {
            resolved = directory
        } else {
            let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
                ?? FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first!
            resolved = base.appendingPathComponent("PadNote/notes", isDirectory: true)
        }
        self.directory = resolved
        draftDirectory = resolved.appendingPathComponent(".pending-edits", isDirectory: true)
        self.faultInjector = faultInjector
        let gate = NoteRevisionGate()
        revisionGate = gate
        draftWorker = NoteDraftWorker(directory: draftDirectory, gate: gate, faultInjector: faultInjector)
        do {
            try fileManager.createDirectory(at: self.directory, withIntermediateDirectories: true)
            try fileManager.createDirectory(at: draftDirectory, withIntermediateDirectories: true)
            loadNotes()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    public func save(_ note: NoteDocument) throws {
        var value = note
        value.schemaVersion = 8
        if let existing = notes.first(where: { $0.id == value.id }) {
            var comparable = value
            comparable.updatedAt = existing.updatedAt
            if comparable == existing,
               let persisted = try? readLimited(noteURL(for: value), limit: NoteDocument.maximumEncodedBytes),
               let reopened = try? NoteDocument.decode(persisted), reopened == existing {
                return
            }
        }
        let data = try value.encoded()
        try writeAtomically(data, to: noteURL(for: value)) { persisted in
            guard try NoteDocument.decode(persisted) == value else {
                throw NoteDocumentError.malformed("Saved note verification failed")
            }
        }
        notes.removeAll { $0.id == value.id }
        notes.append(value)
        notes.sort { $0.updatedAt > $1.updatedAt }
    }

    public func registerDraftRevision(noteID: String, revision: Int) {
        revisionGate.register(noteID: noteID, revision: revision)
    }

    public func nextDraftRevision(noteID: String) -> Int {
        revisionGate.nextRevision(noteID: noteID)
    }

    @discardableResult
    public func persistRegisteredDraft(_ note: NoteDocument, revision: Int) async throws -> Bool {
        let persisted = try await draftWorker.persist(document: note, revision: revision)
        if persisted && revisionGate.shouldCommit(noteID: note.id, revision: revision) {
            pendingDrafts.removeAll { $0.id == note.id }
            pendingDrafts.append(NotePendingDraft(revision: revision, document: note))
            pendingDrafts.sort { $0.document.updatedAt > $1.document.updatedAt }
        }
        return persisted
    }

    public func markCanonicalSaved(noteID: String, revision: Int) async {
        revisionGate.markSaved(noteID: noteID, revision: revision)
        await draftWorker.canonicalSaved(noteID: noteID, revision: revision)
        pendingDrafts.removeAll { $0.id == noteID && $0.revision <= revision }
    }

    public func pendingDraft(noteID: String) -> NotePendingDraft? {
        pendingDrafts.first { $0.id == noteID }
    }

    public func delete(_ note: NoteDocument) throws {
        let checked = try note.validated()
        revisionGate.invalidate(noteID: checked.id)
        let target = noteURL(for: checked)
        let targets = [target, sidecarURL(target, suffix: ".bak"), sidecarURL(target, suffix: ".tmp")] + (pdfURL(for: checked).map { [$0] } ?? [])
        for url in targets {
            if fileManager.fileExists(atPath: url.path) { try fileManager.removeItem(at: url) }
        }
        NoteAtomicFile.removeFamily(draftURL(noteID: checked.id))
        notes.removeAll { $0.id == note.id }
        pendingDrafts.removeAll { $0.id == note.id }
    }

    public func importNote(from url: URL) throws -> NoteDocument {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        let data = try readLimited(url, limit: NoteArchive.maxArchiveBytes)
        let imported: NoteDocument
        var originalPDF: Data?
        if data.count >= 4 && data[0] == 0x50 && data[1] == 0x4b {
            let payload = try NoteArchive.decode(data)
            imported = payload.note
            originalPDF = payload.pdf
        } else {
            imported = try NoteDocument.decode(data)
            if imported.pdfPageCount > 0 {
                throw NoteDocumentError.unsupportedExport("PDF 笔记必须导入包含原文的 .padnote.zip 包")
            }
        }
        var copy = imported
        copy.id = uniqueID()
        copy.updatedAt = NoteDocument.nowMillis()
        if let originalPDF {
            let pdfTarget = directory.appendingPathComponent("\(copy.id).pdf")
            try writeAtomically(originalPDF, to: pdfTarget)
            do { try save(copy) }
            catch { try? fileManager.removeItem(at: pdfTarget); throw error }
        } else {
            try save(copy)
        }
        return copy
    }

    public func exportURL(for note: NoteDocument) throws -> URL {
        let checked = try note.validated()
        let exports = directory.appendingPathComponent("Exports", isDirectory: true)
        try fileManager.createDirectory(at: exports, withIntermediateDirectories: true)
        let isPDF = checked.pdfPageCount > 0
        let filename = "\(safeFilename(checked.title, fallback: "note"))-\(checked.id.prefix(8)).padnote.\(isPDF ? "zip" : "json")"
        let output = exports.appendingPathComponent(filename)
        if isPDF {
            guard let source = pdfURL(for: checked) else { throw NoteArchiveError.invalidPDF("PDF 原文不存在") }
            let pdf = try readLimited(source, limit: NoteArchive.maxPDFBytes)
            try NoteArchive.encode(note: checked, pdf: pdf).write(to: output, options: .atomic)
        } else {
            try checked.encoded().write(to: output, options: .atomic)
        }
        return output
    }

    public func exportRecoveryURL(for item: NoteRecoveryItem) throws -> URL {
        guard recoveryItems.contains(where: { $0.id == item.id }),
              fileManager.fileExists(atPath: item.sourceURL.path) else {
            throw CocoaError(.fileNoSuchFile)
        }
        let exports = directory.appendingPathComponent("Exports", isDirectory: true)
        try fileManager.createDirectory(at: exports, withIntermediateDirectories: true)
        let name = safeFilename(item.filename, fallback: "damaged-note")
        let output = exports.appendingPathComponent("\(name)-\(UUID().uuidString.prefix(8)).recovery")
        try fileManager.copyItem(at: item.sourceURL, to: output)
        return output
    }

    public func reload() {
        loadNotes()
    }

    public func pdfURL(for note: NoteDocument) -> URL? {
        guard safeID(note.id) else { return nil }
        let url = directory.appendingPathComponent("\(note.id).pdf")
        return fileManager.fileExists(atPath: url.path) ? url : nil
    }

    public func importPDF(from url: URL) throws -> NoteDocument {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        let data = try readLimited(url, limit: maxPDFBytes)
        guard let document = PDFDocument(data: data), document.pageCount > 0 else {
            throw NoteDocumentError.malformed("Unable to read PDF")
        }
        try NoteArchive.validatePDF(data, expectedPageCount: document.pageCount)
        let firstBounds = document.page(at: 0)?.bounds(for: .mediaBox) ?? CGRect(x: 0, y: 0, width: 768, height: 1086)
        let title = safeTitle(url.deletingPathExtension().lastPathComponent)
        var note = NoteDocument(title: title)
        note.pageWidth = max(1, Double(abs(firstBounds.width)))
        note.pageHeight = max(1, Double(abs(firstBounds.height)))
        note.pageCount = document.pageCount
        note.pdfPageCount = document.pageCount
        note.viewportCenterX = note.pageWidth / 2
        note.viewportCenterY = note.pageHeight / 2
        let pdfTarget = directory.appendingPathComponent("\(note.id).pdf")
        do {
            try writeAtomically(data, to: pdfTarget)
            try save(note)
        } catch {
            try? fileManager.removeItem(at: pdfTarget)
            throw error
        }
        return note
    }

    private func loadNotes() {
        errorMessage = nil
        guard let urls = try? fileManager.contentsOfDirectory(at: directory, includingPropertiesForKeys: [.isRegularFileKey], options: [.skipsHiddenFiles]) else { return }
        var candidates = Set<URL>()
        var recoveries = [NoteRecoveryItem]()
        for url in urls {
            if url.pathExtension.lowercased() == "json" && !url.lastPathComponent.hasPrefix(".") {
                candidates.insert(url)
            } else if url.lastPathComponent.hasSuffix(".json.bak") {
                candidates.insert(URL(fileURLWithPath: String(url.path.dropLast(4))))
            } else if url.lastPathComponent.hasSuffix(".json.tmp") {
                candidates.insert(URL(fileURLWithPath: String(url.path.dropLast(4))))
            } else if url.lastPathComponent.contains(".corrupt-") || url.lastPathComponent.contains(".failed-") {
                recoveries.append(recoveryItem(url, reason: "此前加载或保存时保留的原始文件"))
            }
        }
        var loaded: [NoteDocument] = []
        for url in candidates {
            do {
                let result = try recoverAndLoad(url)
                let value = result.note
                recoveries.append(contentsOf: result.recoveries)
                if value.pdfPageCount > 0 && !fileManager.fileExists(atPath: pdfURL(for: value)?.path ?? "") {
                    throw NoteDocumentError.malformed("PDF original is missing")
                }
                loaded.append(value)
            } catch {
                for candidate in noteCandidateURLs(url) where fileManager.fileExists(atPath: candidate.path) {
                    recoveries.append(recoveryItem(candidate, reason: error.localizedDescription))
                }
                errorMessage = [errorMessage, "Skipped \(url.lastPathComponent): \(error.localizedDescription)"]
                    .compactMap { $0 }.joined(separator: "\n")
            }
        }
        notes = loaded.sorted { $0.updatedAt > $1.updatedAt }
        loadPendingDrafts(recoveries: &recoveries)
        recoveryItems = Dictionary(grouping: recoveries, by: \.id).compactMap { $0.value.first }
            .sorted { $0.filename < $1.filename }
    }

    private func recoverAndLoad(_ target: URL) throws -> (note: NoteDocument, recoveries: [NoteRecoveryItem]) {
        var valid = [(url: URL, data: Data, note: NoteDocument)]()
        var invalid = [(url: URL, error: Error)]()
        for url in noteCandidateURLs(target) where fileManager.fileExists(atPath: url.path) {
            do {
                let data = try readLimited(url, limit: NoteDocument.maximumEncodedBytes)
                valid.append((url, data, try NoteDocument.decode(data)))
            } catch { invalid.append((url, error)) }
        }
        guard let selected = valid.max(by: {
            if $0.note.updatedAt == $1.note.updatedAt { return $0.url != target && $1.url == target }
            return $0.note.updatedAt < $1.note.updatedAt
        }) else {
            throw invalid.first?.error ?? CocoaError(.fileNoSuchFile)
        }
        var recoveries = invalid.map { recoveryItem($0.url, reason: $0.error.localizedDescription) }
        var promotionSucceeded = true
        if selected.url != target {
            if invalid.contains(where: { $0.url == target }), let preserved = preserveCorrupt(target) {
                recoveries.removeAll { $0.sourceURL == target }
                recoveries.append(recoveryItem(preserved, reason: "原笔记损坏，已从可读取的保存候选恢复"))
            }
            do {
                try promoteRecovery(selected.data, to: target) { data in
                    guard try NoteDocument.decode(data) == selected.note else {
                        throw NoteDocumentError.malformed("Recovered note verification failed")
                    }
                }
            } catch {
                promotionSucceeded = false
                recoveries.append(recoveryItem(selected.url,
                    reason: "恢复提升失败，原候选已保留：\(error.localizedDescription)"))
                errorMessage = [errorMessage, "Could not promote \(selected.url.lastPathComponent): \(error.localizedDescription)"]
                    .compactMap { $0 }.joined(separator: "\n")
            }
        }
        if promotionSucceeded {
            for url in [sidecarURL(target, suffix: ".tmp"), sidecarURL(target, suffix: ".bak")]
            where fileManager.fileExists(atPath: url.path)
                  && !invalid.contains(where: { $0.url == url }) {
                try? fileManager.removeItem(at: url)
            }
        }
        return (selected.note, recoveries)
    }

    private func writeAtomically(_ data: Data, to target: URL, verify: (Data) throws -> Void = { _ in }) throws {
        try NoteAtomicFile.write(data, to: target, faultInjector: faultInjector, verify: verify)
    }

    private func preserveCorrupt(_ target: URL) -> URL? {
        guard fileManager.fileExists(atPath: target.path) else { return nil }
        let stamp = Int(Date().timeIntervalSince1970 * 1000)
        let destination = target.deletingLastPathComponent().appendingPathComponent("\(target.lastPathComponent).corrupt-\(stamp)")
        do { try fileManager.moveItem(at: target, to: destination); return destination }
        catch { return nil }
    }

    private func loadPendingDrafts(recoveries: inout [NoteRecoveryItem]) {
        guard let urls = try? fileManager.contentsOfDirectory(
            at: draftDirectory,
            includingPropertiesForKeys: [.isRegularFileKey],
            options: [.skipsHiddenFiles]
        ) else { pendingDrafts = []; return }
        var targets = Set<URL>()
        for url in urls {
            if url.lastPathComponent.hasSuffix(".draft.json") { targets.insert(url) }
            else if url.lastPathComponent.hasSuffix(".draft.json.bak") || url.lastPathComponent.hasSuffix(".draft.json.tmp") {
                targets.insert(URL(fileURLWithPath: String(url.path.dropLast(4))))
            } else if url.lastPathComponent.contains(".corrupt-") {
                recoveries.append(recoveryItem(url, reason: "损坏的未保存恢复副本"))
            }
        }
        var drafts = [NotePendingDraft]()
        for target in targets {
            let family = [target, sidecarURL(target, suffix: ".tmp"), sidecarURL(target, suffix: ".bak")]
                .filter { fileManager.fileExists(atPath: $0.path) }
            var valid = [(url: URL, data: Data, value: PersistedNoteDraft)]()
            var invalidURLs = Set<URL>()
            for url in family {
                do {
                    let data = try readLimited(url, limit: NoteDocument.maximumEncodedBytes + 64 * 1024)
                    valid.append((url, data, try PersistedNoteDraft.decode(data)))
                } catch {
                    invalidURLs.insert(url)
                    recoveries.append(recoveryItem(url, reason: error.localizedDescription))
                }
            }
            guard let selected = valid.max(by: { $0.value.revision < $1.value.revision }) else { continue }
            var promotionSucceeded = true
            if selected.url != target {
                if invalidURLs.contains(target), let preserved = preserveCorrupt(target) {
                    recoveries.removeAll { $0.sourceURL == target }
                    recoveries.append(recoveryItem(preserved, reason: "损坏的草稿已从可读取候选恢复"))
                }
                do {
                    try promoteRecovery(selected.data, to: target) { data in
                        guard try PersistedNoteDraft.decode(data) == selected.value else {
                            throw NoteDocumentError.malformed("Recovery draft verification failed")
                        }
                    }
                } catch {
                    promotionSucceeded = false
                    recoveries.append(recoveryItem(selected.url,
                        reason: "草稿恢复提升失败，原候选已保留：\(error.localizedDescription)"))
                    errorMessage = [errorMessage, "Could not promote \(selected.url.lastPathComponent): \(error.localizedDescription)"]
                        .compactMap { $0 }.joined(separator: "\n")
                }
            }
            if promotionSucceeded {
                for url in [sidecarURL(target, suffix: ".tmp"), sidecarURL(target, suffix: ".bak")]
                where fileManager.fileExists(atPath: url.path) && !invalidURLs.contains(url) {
                    try? fileManager.removeItem(at: url)
                }
            }
            if let canonical = notes.first(where: { $0.id == selected.value.document.id }), canonical == selected.value.document {
                NoteAtomicFile.removeFamily(target)
            } else {
                revisionGate.register(noteID: selected.value.document.id, revision: selected.value.revision)
                drafts.append(NotePendingDraft(revision: selected.value.revision, document: selected.value.document))
            }
        }
        pendingDrafts = drafts.sorted { $0.document.updatedAt > $1.document.updatedAt }
    }

    private func recoveryItem(_ url: URL, reason: String) -> NoteRecoveryItem {
        NoteRecoveryItem(id: url.standardizedFileURL.path, filename: url.lastPathComponent, reason: reason, sourceURL: url)
    }

    /// Recovery candidates may themselves be the normal `.tmp` or `.bak` path.
    /// Promote through unique sidecars so a failed write can never truncate or
    /// replace the only valid candidate selected above.
    private func promoteRecovery(_ data: Data, to target: URL, verify: (Data) throws -> Void) throws {
        let token = UUID().uuidString
        let temporary = target.deletingLastPathComponent()
            .appendingPathComponent(".\(target.lastPathComponent).recovery-\(token).tmp")
        let backup = target.deletingLastPathComponent()
            .appendingPathComponent(".\(target.lastPathComponent).recovery-\(token).bak")
        try NoteAtomicFile.write(data, to: target, faultInjector: faultInjector,
                                 temporaryURL: temporary, backupURL: backup, verify: verify)
    }

    private func noteCandidateURLs(_ target: URL) -> [URL] {
        [target, sidecarURL(target, suffix: ".tmp"), sidecarURL(target, suffix: ".bak")]
    }

    private func readLimited(_ url: URL, limit: Int) throws -> Data {
        if let values = try? url.resourceValues(forKeys: [.fileSizeKey]), let size = values.fileSize, size > limit {
            throw NoteDocumentError.tooLarge("File exceeds import limit")
        }
        let data = try Data(contentsOf: url, options: .mappedIfSafe)
        guard data.count <= limit else { throw NoteDocumentError.tooLarge("File exceeds import limit") }
        return data
    }

    private func noteURL(for note: NoteDocument) -> URL { directory.appendingPathComponent("\(note.id).json") }
    private func draftURL(noteID: String) -> URL { draftDirectory.appendingPathComponent("\(noteID).draft.json") }
    private func sidecarURL(_ target: URL, suffix: String) -> URL { URL(fileURLWithPath: target.path + suffix) }
    private func uniqueID() -> String { UUID().uuidString }
    private func safeID(_ value: String) -> Bool { value.range(of: #"^[A-Za-z0-9_.:-]+$"#, options: .regularExpression) != nil }
    private func safeTitle(_ value: String) -> String { value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? "未命名笔记" : String(value.prefix(200)) }
    private func safeFilename(_ value: String, fallback: String) -> String {
        let cleaned = value.replacingOccurrences(of: "[^A-Za-z0-9_ .-]", with: "_", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return String((cleaned.isEmpty ? fallback : cleaned).prefix(80))
    }
}
