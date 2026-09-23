import SwiftUI
import UniformTypeIdentifiers
import PhotosUI

struct NoteCoverPicker: View {
    let onPick: (UIImage?) throws -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var importing = false
    @State private var photo: PhotosPickerItem?
    @State private var error: String?
    @State private var choices = NoteCoverStore.builtins()

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 20) {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 150), spacing: 16)], spacing: 16) {
                        ForEach(choices, id: \.0) { item in
                            Button { pick(item.2) } label: {
                                VStack {
                                    Image(uiImage: item.2).resizable().scaledToFit().frame(height: 96)
                                        .clipShape(RoundedRectangle(cornerRadius: 12))
                                    Text(item.1).font(.system(size: 13))
                                }
                            }.buttonStyle(.plain)
                        }
                    }
                    PhotosPicker(selection: $photo, matching: .images) {
                        Label("从照片选择", systemImage: "photo").frame(maxWidth: .infinity, minHeight: 44)
                    }.buttonStyle(.bordered)
                    Button { importing = true } label: {
                        Label("从文件导入", systemImage: "folder").frame(maxWidth: .infinity, minHeight: 44)
                    }.buttonStyle(.bordered)
                    Button("移除封面", role: .destructive) { pick(nil) }
                }.padding(24)
            }
            .background(PadTheme.surface).navigationTitle("选择封面")
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("完成") { dismiss() } } }
            .fileImporter(isPresented: $importing, allowedContentTypes: [.image], allowsMultipleSelection: false) { result in
                do {
                    guard let url = try result.get().first else { return }
                    let scoped = url.startAccessingSecurityScopedResource()
                    defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                    let bytes = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
                    guard bytes <= NoteCoverStore.maxBytes else { throw NoteCoverError.tooLarge }
                    pick(try NoteCoverStore.decode(Data(contentsOf: url)))
                } catch { self.error = error.localizedDescription }
            }
            .task(id: photo) {
                guard let photo else { return }
                do {
                    guard let data = try await photo.loadTransferable(type: Data.self) else { throw NoteCoverError.invalidImage }
                    try Task.checkCancellation()
                    pick(try NoteCoverStore.decode(data))
                } catch is CancellationError {} catch { self.error = error.localizedDescription }
            }
            .alert("封面导入失败", isPresented: Binding(get: { error != nil }, set: { if !$0 { error = nil } })) {
                Button("好", role: .cancel) { error = nil }
            } message: { Text(error ?? "") }
        }
    }

    private func pick(_ image: UIImage?) {
        do { try onPick(image); dismiss() } catch { self.error = error.localizedDescription }
    }
}
