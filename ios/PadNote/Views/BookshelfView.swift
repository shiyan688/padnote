import SwiftUI
import UniformTypeIdentifiers

struct BookshelfView: View {
    @EnvironmentObject private var library: NoteLibrary
    @State private var section: String? = "notes"
    @State private var search = ""
    @State private var creating = false
    @State private var importing = false
    @State private var settings = false
    @State private var agentSettings = false
    @State private var openedNote: NoteDocument?
    @State private var pendingCreatedNote: NoteDocument?
    @State private var renaming: NoteDocument?
    @State private var newTitle = ""
    @State private var deleting: NoteDocument?
    @State private var sharing: ShareArtifact?
    @State private var error: String?
    @State private var coverNote: NoteDocument?
    @State private var showingRecovery = false
    @State private var coverRefresh = UUID()
    private let coverStore = NoteCoverStore()

    private var filteredNotes: [NoteDocument] {
        library.notes.filter { search.isEmpty || $0.title.localizedCaseInsensitiveContains(search) }
    }

    var body: some View {
        NavigationSplitView {
            List(selection: $section) {
                Section {
                    Label("全部笔记", systemImage: "books.vertical").tag("notes")
                    Label("知识库", systemImage: "text.book.closed").tag("vault")
                }
                Section {
                    Button { settings = true } label: { Label("AI 设置", systemImage: "slider.horizontal.3") }
                    Button { agentSettings = true } label: { Label("电脑 Agent", systemImage: "desktopcomputer") }
                }
            }
            .scrollContentBackground(.hidden)
            .background(PadTheme.surface)
            .navigationTitle("PadNote")
            .safeAreaInset(edge: .bottom) {
                VStack(alignment: .leading, spacing: 8) {
                    Text("留住每一次思考").font(.system(size: 15, weight: .medium)).foregroundStyle(PadTheme.ink)
                    Text("手写 · 圈选 · 理解").font(.system(size: 12)).foregroundStyle(PadTheme.secondary)
                }.frame(maxWidth: .infinity, alignment: .leading).padding(24)
            }
            .navigationSplitViewColumnWidth(min: 220, ideal: 240, max: 280)
        } detail: {
            if section == "vault" {
                VaultShelfView()
            } else {
                NavigationStack {
                    ScrollView {
                        VStack(alignment: .leading, spacing: 24) {
                            HStack(alignment: .firstTextBaseline) {
                                VStack(alignment: .leading, spacing: 8) {
                                    Text("我的笔记").font(.system(size: 28, weight: .bold))
                                    Text("\(library.notes.count) 本笔记 · 保存在此 iPad")
                                        .font(.system(size: 13)).foregroundStyle(PadTheme.secondary)
                                }
                                Spacer()
                                Button { creating = true } label: { Label("新建笔记", systemImage: "plus") }
                                    .buttonStyle(.borderedProminent).controlSize(.large).foregroundStyle(.white)
                                    .accessibilityIdentifier("newNoteButton")
                            }
                            if !library.pendingDrafts.isEmpty {
                                VStack(alignment: .leading, spacing: 12) {
                                    Label("未保存的更改", systemImage: "exclamationmark.arrow.circlepath")
                                        .font(.headline).foregroundStyle(.orange)
                                    ForEach(library.pendingDrafts) { draft in
                                        Button {
                                            openedNote = draft.document
                                        } label: {
                                            HStack {
                                                VStack(alignment: .leading) {
                                                    Text(draft.document.title).font(.subheadline.weight(.semibold))
                                                    Text("恢复版本 · 打开后可重试保存或导出")
                                                        .font(.caption).foregroundStyle(PadTheme.secondary)
                                                }
                                                Spacer()
                                                Image(systemName: "chevron.right")
                                            }
                                        }.buttonStyle(.plain)
                                    }
                                }
                                .padding(16).background(.orange.opacity(0.08), in: RoundedRectangle(cornerRadius: 16))
                            }
                            if !library.recoveryItems.isEmpty {
                                Button { showingRecovery = true } label: {
                                    Label("\(library.recoveryItems.count) 个文件需要恢复", systemImage: "externaldrive.badge.exclamationmark")
                                }.buttonStyle(.bordered)
                            }
                            if library.notes.isEmpty {
                                emptyShelf
                            } else if filteredNotes.isEmpty {
                                ContentUnavailableView.search(text: search)
                            } else {
                                LazyVGrid(columns: [GridItem(.adaptive(minimum: 220, maximum: 360), spacing: 24)], spacing: 24) {
                                    ForEach(filteredNotes) { note in
                                        noteCard(note)
                                    }
                                }
                            }
                        }.padding(24)
                    }
                    .background(PadTheme.surface)
                    .foregroundStyle(PadTheme.ink)
                    .searchable(text: $search, prompt: "搜索笔记名称")
                    .toolbar {
                        ToolbarItem(placement: .topBarTrailing) {
                            Button { importing = true } label: { Label("导入", systemImage: "square.and.arrow.down") }
                                .accessibilityIdentifier("importNoteButton")
                        }
                    }
                }
            }
        }
        .sheet(isPresented: $creating, onDismiss: {
            if let note = pendingCreatedNote { pendingCreatedNote = nil; openedNote = note }
        }) {
            NewNoteView { note in
                do { try library.save(note); pendingCreatedNote = note }
                catch { self.error = error.localizedDescription }
            }
        }
        .sheet(isPresented: $settings) { AISettingsView() }
        .sheet(isPresented: $agentSettings) { AgentSettingsView() }
        .sheet(item: $sharing) { ShareSheet(url: $0.url) }
        .sheet(isPresented: $showingRecovery) { NoteRecoveryListView().environmentObject(library) }
        .sheet(item: $coverNote) { note in
            NoteCoverPicker { image in
                if let image { try coverStore.assign(noteID: note.id, image: image) }
                else { coverStore.remove(noteID: note.id) }
                coverRefresh = UUID()
            }
        }
        .fullScreenCover(item: $openedNote) { note in
            NoteEditorView(note: note).environmentObject(library)
        }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.json, .pdf, .zip], allowsMultipleSelection: false) { result in
            do {
                guard let url = try result.get().first else { return }
                let note = try url.pathExtension.lowercased() == "pdf"
                    ? library.importPDF(from: url) : library.importNote(from: url)
                openedNote = note
            } catch { self.error = error.localizedDescription }
        }
        .alert("重命名笔记", isPresented: Binding(get: { renaming != nil }, set: { if !$0 { renaming = nil } })) {
            TextField("名称", text: $newTitle)
            Button("取消", role: .cancel) { renaming = nil }
            Button("保存") {
                guard var note = renaming else { return }
                let title = newTitle.trimmingCharacters(in: .whitespacesAndNewlines)
                if !title.isEmpty {
                    note.title = title; note.updatedAt = Date().timeIntervalSince1970 * 1000
                    do { try library.save(note) } catch { self.error = error.localizedDescription }
                }
                renaming = nil
            }
        }
        .alert("删除这本笔记？", isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } })) {
            Button("取消", role: .cancel) { deleting = nil }
            Button("删除", role: .destructive) {
                guard let note = deleting else { return }
                do {
                    try AIConversationStore().clearNote(noteID: note.id)
                    try library.delete(note)
                    coverStore.remove(noteID: note.id)
                } catch { self.error = error.localizedDescription }
                deleting = nil
            }
        } message: { Text("将从此 iPad 移除笔记和原始 PDF。此操作无法撤销。") }
        .alert("无法完成操作", isPresented: Binding(get: { error != nil || library.errorMessage != nil }, set: { if !$0 { error = nil; library.errorMessage = nil } })) {
            Button("好", role: .cancel) { error = nil; library.errorMessage = nil }
        } message: { Text(error ?? library.errorMessage ?? "") }
    }

    private var emptyShelf: some View {
        VStack(alignment: .leading, spacing: 24) {
            Image(systemName: "pencil.and.outline").font(.system(size: 48, weight: .ultraLight)).foregroundStyle(PadTheme.accent)
            Text("从一张纸开始").font(.system(size: 28, weight: .semibold))
            Text("用 Apple Pencil 记下推导和灵感，也可以导入 PDF，在原文旁批注。")
                .font(.system(size: 15)).foregroundStyle(PadTheme.secondary)
            HStack(alignment: .top, spacing: 32) {
                guide("01", "自由书写", "选好纸张，笔迹自动保存。")
                guide("02", "圈选提问", "只把你确认的选区交给 AI。")
                guide("03", "积累知识", "将笔记整理成可检索的 Markdown。")
            }.padding(.top, 8)
            Button("导入 PDF 或 Android 笔记") { importing = true }.buttonStyle(.bordered)
        }.padding(.vertical, 48).frame(maxWidth: .infinity, alignment: .leading)
    }

    private func guide(_ number: String, _ title: String, _ detail: String) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(number).font(.system(size: 12, weight: .medium, design: .monospaced)).foregroundStyle(PadTheme.accent)
            Text(title).font(.system(size: 17, weight: .semibold))
            Text(detail).font(.system(size: 13)).foregroundStyle(PadTheme.secondary)
        }.frame(maxWidth: .infinity, alignment: .leading)
    }

    private func noteCard(_ note: NoteDocument) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Button { openedNote = library.pendingDraft(noteID: note.id)?.document ?? note } label: {
                VStack(alignment: .leading, spacing: 16) {
                    NoteCoverView(note: note, pdfURL: library.pdfURL(for: note), refresh: coverRefresh)
                        .frame(height: 200).clipped()
                    Text(note.title).font(.system(size: 17, weight: .semibold)).lineLimit(1)
                        .padding(.horizontal, 20)
                    if library.pendingDraft(noteID: note.id) != nil {
                        Label("有未保存版本", systemImage: "exclamationmark.arrow.circlepath")
                            .font(.caption).foregroundStyle(.orange).padding(.horizontal, 20)
                    }
                }
            }.buttonStyle(.plain).accessibilityIdentifier("note-\(note.title)")
            HStack {
                Text("\(note.pageCount) 页 · \(Date(timeIntervalSince1970: note.updatedAt / 1000).formatted(date: .abbreviated, time: .omitted))")
                    .font(.system(size: 12)).foregroundStyle(PadTheme.secondary)
                Spacer(minLength: 4)
                Menu {
                    Button("重命名", systemImage: "pencil") { newTitle = note.title; renaming = note }
                    Button("导出笔记", systemImage: "square.and.arrow.up") {
                        do { sharing = ShareArtifact(url: try library.exportURL(for: note)) }
                        catch { self.error = error.localizedDescription }
                    }
                    Button("封面", systemImage: "photo") { coverNote = note }
                    Button("删除", systemImage: "trash", role: .destructive) { deleting = note }
                } label: { Image(systemName: "ellipsis").frame(width: 44, height: 44) }
            }.padding(.horizontal, 20).padding(.bottom, 8)
        }
        .background(.white, in: RoundedRectangle(cornerRadius: 20))
        .overlay(RoundedRectangle(cornerRadius: 20).stroke(PadTheme.border, lineWidth: 1))
        .clipShape(RoundedRectangle(cornerRadius: 20))
    }
}

private struct NoteRecoveryListView: View {
    @EnvironmentObject private var library: NoteLibrary
    @Environment(\.dismiss) private var dismiss
    @State private var sharing: ShareArtifact?
    @State private var error: String?

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text("这些文件未被当作正常笔记打开，也不会被自动覆盖。可先导出原始字节，再在副本上排查或联系支持。")
                        .font(.callout).foregroundStyle(.secondary)
                }
                ForEach(library.recoveryItems) { item in
                    VStack(alignment: .leading, spacing: 8) {
                        Text(item.filename).font(.headline).textSelection(.enabled)
                        Text(item.reason).font(.caption).foregroundStyle(.secondary)
                        Button("导出原始文件", systemImage: "square.and.arrow.up") {
                            do { sharing = ShareArtifact(url: try library.exportRecoveryURL(for: item)) }
                            catch { self.error = error.localizedDescription }
                        }
                    }.padding(.vertical, 4)
                }
                if let error { Text(error).foregroundStyle(.red) }
            }
            .navigationTitle("文件恢复")
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { Button("重新检查") { library.reload() } }
                ToolbarItem(placement: .confirmationAction) { Button("完成") { dismiss() } }
            }
            .sheet(item: $sharing) { ShareSheet(url: $0.url) }
        }
    }
}

struct NoteCoverView: View {
    let note: NoteDocument
    let pdfURL: URL?
    let refresh: UUID
    private let coverStore = NoteCoverStore()
    @State private var image: UIImage?
    @State private var customCover: UIImage?
    var body: some View {
        ZStack {
            PadTheme.accentLight.opacity(0.5)
            if let customCover {
                Image(uiImage: customCover).resizable().scaledToFill().clipped()
            } else if let image {
                Image(uiImage: image).resizable().scaledToFit().padding(20)
            } else {
                PaperPreview(style: note.pageStyle).padding(24)
            }
        }.task(id: "\(note.updatedAt)-\(refresh.uuidString)") {
            customCover = coverStore.load(noteID: note.id, maxEdge: 640)
            guard customCover == nil else { return }
            image = NoteRenderer.renderPage(document: note, page: 0, pdfURL: pdfURL, maxEdge: 440)
            var cover = note
            cover.textFlows = note.textFlows.filter { $0.anchorPageIndex == 0 }
            try? await CompiledTextRenderer.shared.prepareAndWait(document: cover)
            if !Task.isCancelled { image = NoteRenderer.renderPage(document: note, page: 0, pdfURL: pdfURL, maxEdge: 440) }
        }
    }
}
