import Combine
import Foundation
import PDFKit

@MainActor
public final class NoteLibrary: ObservableObject {
    @Published public private(set) var notes: [NoteDocument] = []
    @Published public var errorMessage: String?

    private let directory: URL
    private let fileManager = FileManager.default
    private let maxPDFBytes = NoteArchive.maxPDFBytes

    public init(directory: URL? = nil) {
        if let directory {
            self.directory = directory
        } else {
            let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
                ?? FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first!
            self.directory = base.appendingPathComponent("PadNote/notes", isDirectory: true)
        }
        do {
            try fileManager.createDirectory(at: self.directory, withIntermediateDirectories: true)
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
            if comparable == existing { return }
        }
        _ = try value.validated()
        try writeAtomically(value.encoded(), to: noteURL(for: value))
        notes.removeAll { $0.id == value.id }
        notes.append(value)
        notes.sort { $0.updatedAt > $1.updatedAt }
    }

    public func delete(_ note: NoteDocument) throws {
        let checked = try note.validated()
        let target = noteURL(for: checked)
        let targets = [target, sidecarURL(target, suffix: ".bak"), sidecarURL(target, suffix: ".tmp")] + (pdfURL(for: checked).map { [$0] } ?? [])
        for url in targets {
            if fileManager.fileExists(atPath: url.path) { try fileManager.removeItem(at: url) }
        }
        notes.removeAll { $0.id == note.id }
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
        guard let urls = try? fileManager.contentsOfDirectory(at: directory, includingPropertiesForKeys: [.isRegularFileKey], options: [.skipsHiddenFiles]) else { return }
        var candidates = Set<URL>()
        for url in urls {
            if url.pathExtension.lowercased() == "json" && !url.lastPathComponent.hasPrefix(".") {
                candidates.insert(url)
            } else if url.lastPathComponent.hasSuffix(".json.bak") {
                candidates.insert(URL(fileURLWithPath: String(url.path.dropLast(4))))
            } else if url.lastPathComponent.hasSuffix(".json.tmp") {
                candidates.insert(URL(fileURLWithPath: String(url.path.dropLast(4))))
            }
        }
        var loaded: [NoteDocument] = []
        for url in candidates {
            do {
                let value = try recoverAndLoad(url)
                if value.pdfPageCount > 0 && !fileManager.fileExists(atPath: pdfURL(for: value)?.path ?? "") {
                    throw NoteDocumentError.malformed("PDF original is missing")
                }
                loaded.append(value)
            } catch {
                errorMessage = [errorMessage, "Skipped \(url.lastPathComponent): \(error.localizedDescription)"]
                    .compactMap { $0 }.joined(separator: "\n")
            }
        }
        notes = loaded.sorted { $0.updatedAt > $1.updatedAt }
    }

    private func recoverAndLoad(_ target: URL) throws -> NoteDocument {
        do { return try NoteDocument.decode(try Data(contentsOf: target)) }
        catch let originalError {
            let backup = sidecarURL(target, suffix: ".bak")
            if fileManager.fileExists(atPath: backup.path), let value = try? NoteDocument.decode(Data(contentsOf: backup)) {
                preserveCorrupt(target)
                try? fileManager.moveItem(at: backup, to: target)
                return value
            }
            let temporary = sidecarURL(target, suffix: ".tmp")
            if fileManager.fileExists(atPath: temporary.path), let value = try? NoteDocument.decode(Data(contentsOf: temporary)) {
                preserveCorrupt(target)
                try? fileManager.moveItem(at: temporary, to: target)
                return value
            }
            throw originalError
        }
    }

    private func writeAtomically(_ data: Data, to target: URL) throws {
        try fileManager.createDirectory(at: target.deletingLastPathComponent(), withIntermediateDirectories: true)
        let temporary = sidecarURL(target, suffix: ".tmp")
        let backup = sidecarURL(target, suffix: ".bak")
        try data.write(to: temporary, options: .atomic)
        if fileManager.fileExists(atPath: backup.path) { try fileManager.removeItem(at: backup) }
        let hadTarget = fileManager.fileExists(atPath: target.path)
        if hadTarget { try fileManager.moveItem(at: target, to: backup) }
        do {
            try fileManager.moveItem(at: temporary, to: target)
            if fileManager.fileExists(atPath: backup.path) { try fileManager.removeItem(at: backup) }
        } catch {
            if hadTarget && fileManager.fileExists(atPath: backup.path) { try? fileManager.moveItem(at: backup, to: target) }
            throw error
        }
    }

    private func preserveCorrupt(_ target: URL) {
        guard fileManager.fileExists(atPath: target.path) else { return }
        let stamp = Int(Date().timeIntervalSince1970 * 1000)
        try? fileManager.moveItem(at: target, to: target.deletingLastPathComponent().appendingPathComponent("\(target.lastPathComponent).corrupt-\(stamp)"))
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
