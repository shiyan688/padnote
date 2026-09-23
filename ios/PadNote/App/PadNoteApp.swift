import SwiftUI

@main
struct PadNoteApp: App {
    @StateObject private var library: NoteLibrary

    init() {
        let testing = ProcessInfo.processInfo.arguments.contains("--uitesting")
        let directory = testing ? FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("UITestLibrary", isDirectory: true) : nil
        _library = StateObject(wrappedValue: NoteLibrary(directory: directory))
    }

    var body: some Scene {
        WindowGroup {
            BookshelfView()
                .environmentObject(library)
                .tint(PadTheme.accent)
                .preferredColorScheme(.light)
        }
    }
}
