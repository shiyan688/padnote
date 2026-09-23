import SwiftUI

struct VaultShelfView: View {
    @EnvironmentObject private var library: NoteLibrary
    @StateObject private var vault = VaultLibrary()
    @State private var search = ""
    @State private var selected: VaultNote?
    @State private var deleting: VaultNote?
    @State private var share: ShareArtifact?
    @State private var showAI = false
    @State private var agentSettings = false
    @State private var videoShare: ShareArtifact?
    @State private var videoFormNote: VaultNote?
    @State private var pendingVideoURL: URL?
    @State private var agentConnected = false

    private var results: [VaultNote] {
        vault.notes.filter { search.isEmpty || $0.title.localizedCaseInsensitiveContains(search) || $0.markdown.localizedCaseInsensitiveContains(search) }
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 24) {
                    Text("知识库").font(.system(size: 28, weight: .bold))
                    Text("把笔记整理成 Markdown，保留公式，并按原文检索。")
                        .font(.system(size: 13)).foregroundStyle(PadTheme.secondary)
                    if vault.notes.isEmpty {
                        VStack(alignment: .leading, spacing: 16) {
                            Text("让手写内容也能被找到").font(.system(size: 17, weight: .semibold))
                            Text("打开笔记，在右上角菜单选择「整理到知识库」。整本数字化会在你确认后，逐页发送到所配置的模型。已有文字也可以直接保存为 Markdown。")
                                .font(.system(size: 15)).foregroundStyle(PadTheme.secondary)
                        }.padding(.vertical, 32)
                    } else {
                        Button("询问当前检索结果", systemImage: "sparkles") { showAI = true }
                            .buttonStyle(.bordered).disabled(results.isEmpty)
                        Text("AI 提问时会发送当前结果的文本，最多 24,000 字。原笔记与知识库文件不会被模型改动。")
                            .font(.system(size: 12)).foregroundStyle(PadTheme.secondary)
                        ForEach(results) { note in
                            HStack(alignment: .top) {
                                Button { selected = note } label: {
                                    VStack(alignment: .leading, spacing: 8) {
                                        Text(note.title).font(.system(size: 17, weight: .semibold))
                                        Text(note.markdown).font(.system(size: 13)).lineLimit(3).foregroundStyle(PadTheme.secondary)
                                        if let original = library.notes.first(where: { $0.id == note.id }), original.updatedAt > note.sourceUpdatedAt + 1000 {
                                            Text("原笔记已更新，可重新整理").font(.system(size: 12)).foregroundStyle(Color(hex: 0xA46A2A))
                                        }
                                    }.frame(maxWidth: .infinity, alignment: .leading)
                                }.buttonStyle(.plain)
                                Menu {
                                    Button("导出 Markdown", systemImage: "square.and.arrow.up") {
                                        do { share = ShareArtifact(url: try vault.export(note)) }
                                        catch { vault.errorMessage = error.localizedDescription }
                                    }
                                    if agentConnected {
                                        Button("生成视频任务包", systemImage: "film") { videoFormNote = note }
                                    }
                                    Button("删除", systemImage: "trash", role: .destructive) { deleting = note }
                                } label: { Image(systemName: "ellipsis").frame(width: 44, height: 44) }
                            }.padding(20).background(.white, in: RoundedRectangle(cornerRadius: 18))
                        }
                    }
                }.padding(24)
            }.background(PadTheme.surface).searchable(text: $search, prompt: "搜索名称与正文")
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("电脑 Agent", systemImage: "desktopcomputer") { agentSettings = true } } }
        }
        .onAppear { vault.reload(); agentConnected = AgentConnectionStore().load().connected }
        .sheet(item: $selected) { note in
            NavigationStack {
                MathTextView(source: note.markdown).padding(24).navigationTitle(note.title)
                    .toolbar { ToolbarItem(placement: .confirmationAction) { Button("完成") { selected = nil } } }
            }
        }
        .sheet(item: $share) { ShareSheet(url: $0.url) }
        .sheet(item: $videoShare) { ShareSheet(url: $0.url) }
        .sheet(item: $videoFormNote, onDismiss: { if let url = pendingVideoURL { pendingVideoURL = nil; videoShare = ShareArtifact(url: url) } }) { note in VideoTaskExportView(note: note) { pendingVideoURL = $0 } }
        .sheet(isPresented: $agentSettings, onDismiss: { agentConnected = AgentConnectionStore().load().connected }) { AgentSettingsView() }
        .sheet(isPresented: $showAI) {
            AIAssistantView(image: nil, noteContext: String(results.map { "来源：\($0.title)\n\($0.markdown)" }.joined(separator: "\n\n").prefix(24_000)), allowsInsertion: false) { _ in }
        }
        .alert("删除格式笔记？", isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } })) {
            Button("取消", role: .cancel) { deleting = nil }
            Button("删除", role: .destructive) {
                if let deleting { do { try vault.delete(deleting) } catch { vault.errorMessage = error.localizedDescription } }
                deleting = nil
            }
        } message: { Text("原始手写笔记会保留。") }
        .alert("知识库操作失败", isPresented: Binding(get: { vault.errorMessage != nil }, set: { if !$0 { vault.errorMessage = nil } })) {
            Button("好", role: .cancel) { vault.errorMessage = nil }
        } message: { Text(vault.errorMessage ?? "") }
    }

}

struct DigitizeNoteView: View {
    let note: NoteDocument
    let pdfURL: URL?
    @Environment(\.dismiss) private var dismiss
    @StateObject private var vault = VaultLibrary()
    @State private var task: Task<Void, Never>?
    @State private var running = false
    @State private var progress = 0
    @State private var totalPages = 0
    @State private var status: String?
    @State private var confirm = false
    @State private var showSettings = false

    private var requestProfile: AIProfile? { AISettingsStore().requestProfile }
    private var settings: AISettings {
        guard let profile = requestProfile else { return AISettings() }
        return AISettings(endpoint: profile.visionEndpoint, model: profile.visionModel, keyReference: profile.visionKeyReference)
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 24) {
                    Text("整理「\(note.title)」").font(.system(size: 28, weight: .bold))
                    Text("将 \(note.pageCount) 页手写、图片与 PDF 内容逐页转写，保留公式和页码，保存为可检索的 Markdown。")
                        .font(.system(size: 15))
                    Image(uiImage: NoteRenderer.renderPage(document: note, page: 0, pdfURL: pdfURL, maxEdge: 640))
                        .resizable().scaledToFit().frame(maxHeight: 280).frame(maxWidth: .infinity)
                    Text("上传范围：整本笔记的每一页（包括 PDF 原文）。\n目标：\(settings.endpoint)\n模型：\(settings.model)")
                        .font(.system(size: 13)).foregroundStyle(PadTheme.secondary)
                    if running {
                        ProgressView(value: Double(progress), total: Double(max(1, totalPages)))
                        Text("已完成 \(progress) / \(max(1, totalPages)) 页")
                        Button("取消整理", role: .cancel) { task?.cancel() }
                    } else {
                        Button("确认范围并开始数字化", systemImage: "text.viewfinder") { confirm = true }
                            .buttonStyle(.borderedProminent)
                        Button("AI 设置") { showSettings = true }
                        if !note.textFlows.isEmpty {
                            Button("仅将已有文字存入知识库（不上传）") {
                                do {
                                    let text = note.textFlows.sorted { $0.anchorPageIndex < $1.anchorPageIndex }
                                        .map { "## 第 \($0.anchorPageIndex + 1) 页\n\n\($0.source)" }.joined(separator: "\n\n")
                                    try vault.save(note: note, markdown: "# \(note.title)\n\n\(text)")
                                    status = "已保存到知识库"
                                } catch { status = error.localizedDescription }
                            }.buttonStyle(.bordered)
                        }
                    }
                    if let status { Text(status).font(.system(size: 14)).foregroundStyle(PadTheme.secondary) }
                }.padding(24)
            }.background(PadTheme.surface).navigationTitle("整理到知识库")
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button("完成") { task?.cancel(); dismiss() } } }
        }
        .sheet(isPresented: $showSettings) { AISettingsView() }
        .alert("发送整本笔记？", isPresented: $confirm) {
            Button("取消", role: .cancel) {}
            Button("发送 \(note.pageCount) 页") { start() }
        } message: { Text("会向 \(URL(string: settings.endpoint)?.host ?? settings.endpoint) 发送所有页面，产生 \(note.pageCount) 次模型请求。请确认内容与服务。") }
        .onDisappear { task?.cancel() }
    }

    private func start() {
        running = true; status = nil; progress = 0; totalPages = note.pageCount
        let configuration = settings
        task = Task { @MainActor in
            defer { running = false }
            do {
                try await CompiledTextRenderer.shared.prepareAndWait(document: note)
                // WebKit compilation can reveal fragments that continue past the
                // persisted pageCount. Render against that measured extent so
                // those newly required pages are included in the vault export.
                let measuredPages = max(note.pageCount, NoteTextLayout.requiredPageCount(note))
                totalPages = measuredPages
                var renderDocument = note
                renderDocument.pageCount = measuredPages
                var pages = ["# \(note.title)"]
                for page in 0..<measuredPages {
                    try Task.checkCancellation()
                    let image = NoteRenderer.renderPage(document: renderDocument, page: page, pdfURL: pdfURL, maxEdge: 1600)
                    let result = try await AIClient(settings: configuration).complete(image: image,
                        prompt: "请准确转写此页笔记为 Markdown。数学公式用 LaTeX，流程图可用 Mermaid。不要猜测模糊内容，用[无法辨认]标出，不加开场白，不省略内容。",
                        context: note.textFlows.filter { $0.anchorPageIndex == page }.map(\.source).joined(separator: "\n"))
                    try Task.checkCancellation()
                    pages.append("## 第 \(page + 1) 页\n\n\(result)")
                    progress = page + 1
                }
                try Task.checkCancellation()
                try vault.save(note: note, markdown: pages.joined(separator: "\n\n"))
                status = "已保存到知识库，可以检索或导出 Markdown。"
            } catch is CancellationError { status = "已取消，原有知识库内容未更改。" }
            catch { status = error.localizedDescription }
        }
    }
}
