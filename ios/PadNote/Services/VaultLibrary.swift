import Foundation
import Combine

struct VaultNote: Codable, Identifiable {
    var id: String
    var title: String
    var markdown: String
    var sourceUpdatedAt: Double
    var createdAt: Date
}

@MainActor
final class VaultLibrary: ObservableObject {
    @Published var notes: [VaultNote] = []
    @Published var errorMessage: String?
    private let directory: URL

    init() {
        let testing = ProcessInfo.processInfo.arguments.contains("--uitesting")
        directory = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent(testing ? "PadNoteUITestVault" : "PadNoteVault", isDirectory: true)
        reload()
    }

    func reload() {
        do {
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            notes = try FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)
                .filter { $0.pathExtension == "json" }
                .map { try JSONDecoder().decode(VaultNote.self, from: Data(contentsOf: $0)) }
                .sorted { $0.createdAt > $1.createdAt }
        } catch { errorMessage = error.localizedDescription }
    }

    func save(note: NoteDocument, markdown: String) throws {
        let entry = VaultNote(id: note.id, title: note.title, markdown: markdown,
                              sourceUpdatedAt: note.updatedAt, createdAt: Date())
        let data = try JSONEncoder().encode(entry)
        try data.write(to: directory.appendingPathComponent("\(safeID(note.id)).json"), options: .atomic)
        reload()
    }

    func delete(_ note: VaultNote) throws {
        try FileManager.default.removeItem(at: directory.appendingPathComponent("\(safeID(note.id)).json"))
        reload()
    }

    func export(_ note: VaultNote) throws -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("\(safeID(note.id)).md")
        try note.markdown.write(to: url, atomically: true, encoding: .utf8)
        return url
    }

    private func safeID(_ id: String) -> String {
        Data(id.utf8).base64EncodedString().replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "+", with: "-")
    }
}
