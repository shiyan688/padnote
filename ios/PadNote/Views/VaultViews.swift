import SwiftUI

struct VaultShelfView: View {
    @EnvironmentObject private var library: NoteLibrary
    @StateObject private var vault = VaultLibrary()
    @State private var search = ""
    @State private var selected: VaultNote?
    @State private var deleting: VaultNote?
    @State private var share: ShareArtifact?
    @State private var showAI = false
    @State private var aiContextSnapshot = ""
    @State private var agentSettings = false
    @State private var videoShare: ShareArtifact?
    @State private var videoFormNote: VaultNote?
    @State private var pendingVideoURL: URL?

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
                        Button("询问当前检索结果", systemImage: "sparkles") {
                            aiContextSnapshot = String(results.map { "来源：\($0.title)\n\($0.markdown)" }
                                .joined(separator: "\n\n").prefix(24_000))
                            showAI = true
                        }
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
                                    Button("生成视频任务包", systemImage: "film") { videoFormNote = note }
                                    Button("删除", systemImage: "trash", role: .destructive) { deleting = note }
                                } label: { Image(systemName: "ellipsis").frame(width: 44, height: 44) }
                            }.padding(20).background(.white, in: RoundedRectangle(cornerRadius: 18))
                        }
                    }
                }.padding(24)
            }.background(PadTheme.surface).searchable(text: $search, prompt: "搜索名称与正文")
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("电脑 Agent", systemImage: "desktopcomputer") { agentSettings = true } } }
        }
        .onAppear { vault.reload() }
        .sheet(item: $selected) { note in
            NavigationStack {
                MathTextView(source: note.markdown).padding(24).navigationTitle(note.title)
                    .toolbar { ToolbarItem(placement: .confirmationAction) { Button("完成") { selected = nil } } }
            }
        }
        .sheet(item: $share) { ShareSheet(url: $0.url) }
        .sheet(item: $videoShare) { ShareSheet(url: $0.url) }
        .sheet(item: $videoFormNote, onDismiss: { if let url = pendingVideoURL { pendingVideoURL = nil; videoShare = ShareArtifact(url: url) } }) { note in VideoTaskExportView(note: note) { pendingVideoURL = $0 } }
        .sheet(isPresented: $agentSettings) { AgentSettingsView() }
        .sheet(isPresented: $showAI, onDismiss: { aiContextSnapshot = "" }) {
            AIAssistantView(image: nil, noteContext: aiContextSnapshot, allowsInsertion: false) { _ in }
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
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var vault = VaultLibrary()
    @StateObject private var session = DigitizationSession()
    @State private var prepareTask: Task<Void, Never>?
    @State private var profile: AIProfile?
    @State private var confirm = false
    @State private var showSettings = false
    @State private var preview: DigitizationCheckpoint?
    @State private var share: ShareArtifact?
    @State private var deleting: DigitizationCheckpoint?
    @State private var deletingRecovery: DigitizationRecoveryItem?
    @State private var retryingAttempt: DigitizationPageAttempt?

    private var settings: AISettings {
        guard let profile else { return AISettings(endpoint: "", model: "", keyReference: "") }
        return AISettings(endpoint: profile.visionEndpoint, model: profile.visionModel, keyReference: profile.visionKeyReference)
    }

    private var current: DigitizationCheckpoint? { session.checkpoint }

    private var recipientDestination: String {
        guard let value = profile?.visionEndpoint,
              let components = URLComponents(string: value),
              components.user == nil, components.password == nil,
              components.query == nil, components.fragment == nil,
              let host = components.host else { return "配置地址无效" }
        let port = components.port.map { ":\($0)" } ?? ""
        return host + port + components.path
    }

    private var remainingRequestCount: Int {
        current?.remainingPages.count ?? session.identity?.measuredPageCount ?? note.pageCount
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
                    Text("上传范围：整本笔记的每一页（包括 PDF 原文）。\n目标：\(recipientDestination)\n模型：\(settings.model)")
                        .font(.system(size: 13)).foregroundStyle(PadTheme.secondary)
                    Text("未完成的 PDF 批次会在本机保留一份受大小限制的原文副本；完成或删除该草稿后移除。应用进入后台时会暂停，不会在锁屏后继续请求。")
                        .font(.system(size: 12)).foregroundStyle(PadTheme.secondary)
                    if session.preparing { ProgressView("正在核对来源与草稿…") }
                    if let current { currentBatchCard(current) }

                    if !session.running && !session.preparing {
                        if let attempt = current?.activeAttempt {
                            Button("处理第 \(attempt.pageIndex + 1) 页未知结果", systemImage: "exclamationmark.arrow.triangle.2.circlepath") {
                                retryingAttempt = attempt
                            }.buttonStyle(.borderedProminent)
                        } else if current?.state == .partial {
                            Button("继续剩余 \(remainingRequestCount) 页", systemImage: "play.fill") { confirm = true }
                                .buttonStyle(.borderedProminent).disabled(profile == nil)
                        } else if current?.state == .readyToPublish {
                            Button("保存完整结果到知识库", systemImage: "tray.and.arrow.down") {
                                if let profile {
                                    session.publishReady(document: note, sourcePDFURL: pdfURL,
                                                         profile: profile, publisher: vault)
                                }
                            }.buttonStyle(.borderedProminent)
                        } else {
                            Button(current == nil ? "确认范围并开始数字化" : "新建数字化批次",
                                   systemImage: "text.viewfinder") { confirm = true }
                                .buttonStyle(.borderedProminent).disabled(profile == nil || session.identity == nil)
                        }
                        Button("AI 设置") { showSettings = true }
                        existingTextButton
                    }
                    if !session.incompatibleBatches.isEmpty { incompatibleDrafts }
                    if !session.recoveryItems.isEmpty { damagedDrafts }
                    if let status = session.status {
                        Text(status).font(.system(size: 14)).foregroundStyle(PadTheme.secondary)
                    }
                }.padding(24)
            }.background(PadTheme.surface).navigationTitle("整理到知识库")
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button("完成") { session.cancel(); dismiss() } } }
        }
        .sheet(isPresented: $showSettings, onDismiss: prepare) { AISettingsView() }
        .sheet(item: $preview) { batch in
            NavigationStack {
                MathTextView(source: batch.markdown()).padding(24)
                    .navigationTitle(batch.isComplete ? "完整结果" : "未完成草稿")
                    .toolbar { ToolbarItem(placement: .confirmationAction) { Button("完成") { preview = nil } } }
            }
        }
        .sheet(item: $share) { ShareSheet(url: $0.url) }
        .alert("发送整本笔记？", isPresented: $confirm) {
            Button("取消", role: .cancel) {}
            Button("发送剩余 \(remainingRequestCount) 页") { startOrContinue() }
        } message: {
            Text("会向 \(recipientDestination) 逐页发送。每页成功后立即保存草稿；取消或失败后可从未完成页继续。")
        }
        .alert("删除数字化草稿？", isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } })) {
            Button("取消", role: .cancel) { deleting = nil }
            Button("删除", role: .destructive) {
                if let deleting { try? session.delete(deleting) }
                deleting = nil
            }
        } message: { Text("只删除此数字化批次；原笔记和已经保存的知识库内容不变。") }
        .alert("重新发送结果未知的页面？", isPresented: Binding(
            get: { retryingAttempt != nil },
            set: { if !$0 { retryingAttempt = nil } }
        )) {
            Button("取消", role: .cancel) { retryingAttempt = nil }
            Button("仍要重新发送") {
                session.confirmRetryUnknownPage()
                retryingAttempt = nil
            }
        } message: {
            Text("第 \((retryingAttempt?.pageIndex ?? 0) + 1) 页的上次请求可能已经由模型处理或收费，但结果尚未保存。确认后，下次继续会重新发送这一页。")
        }
        .alert("删除损坏的检查点？", isPresented: Binding(get: { deletingRecovery != nil }, set: { if !$0 { deletingRecovery = nil } })) {
            Button("取消", role: .cancel) { deletingRecovery = nil }
            Button("删除", role: .destructive) {
                if let deletingRecovery { try? session.deleteRecovery(deletingRecovery) }
                deletingRecovery = nil
            }
        } message: { Text("该文件无法解码，不能继续；删除不会影响原笔记或知识库。") }
        .alert("知识库操作失败", isPresented: Binding(
            get: { vault.errorMessage != nil },
            set: { if !$0 { vault.errorMessage = nil } }
        )) {
            Button("好", role: .cancel) { vault.errorMessage = nil }
        } message: { Text(vault.errorMessage ?? "") }
        .onAppear(perform: prepare)
        .onChange(of: scenePhase) { _, phase in
            if phase != .active { prepareTask?.cancel(); session.pause() }
        }
        .onDisappear { prepareTask?.cancel(); session.cancel() }
    }

    @ViewBuilder
    private func currentBatchCard(_ batch: DigitizationCheckpoint) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text(batch.state == .published ? "已保存批次" : batch.isComplete ? "完整结果（尚未保存到知识库）" : "未完成草稿")
                    .font(.system(size: 17, weight: .semibold))
                Spacer()
                Text("\(batch.pages.count) / \(batch.source.measuredPageCount) 页")
                    .font(.system(size: 13)).foregroundStyle(PadTheme.secondary)
            }
            ProgressView(value: Double(batch.pages.count), total: Double(max(1, batch.source.measuredPageCount)))
            Text("接收：\(batch.source.recipient.displayName) · \(batch.source.recipient.visionDestination) · \(batch.source.recipient.visionModel)")
                .font(.system(size: 12)).foregroundStyle(PadTheme.secondary)
            if let message = batch.lastMessage { Text(message).font(.system(size: 12)).foregroundStyle(PadTheme.secondary) }
            HStack {
                Button("预览") { preview = batch }
                Button("导出 Markdown") { export(batch) }
                if session.running { Button("暂停", role: .cancel) { session.pause() } }
                Button("删除草稿", role: .destructive) { deleting = batch }.disabled(session.running)
            }.buttonStyle(.bordered)
        }.padding(18).background(.white, in: RoundedRectangle(cornerRadius: 16))
    }

    private var existingTextButton: some View {
        Button("仅将已有文字存入知识库（不上传）") {
            do {
                let text = note.textFlows.sorted { $0.anchorPageIndex < $1.anchorPageIndex }
                    .map { "## 第 \($0.anchorPageIndex + 1) 页\n\n\($0.source)" }.joined(separator: "\n\n")
                try vault.save(note: note, markdown: "# \(note.title)\n\n\(text)")
            } catch { vault.errorMessage = error.localizedDescription }
        }.buttonStyle(.bordered).disabled(note.textFlows.isEmpty)
    }

    private var incompatibleDrafts: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("其他来源或接收配置的草稿").font(.system(size: 17, weight: .semibold))
            Text("这些结果不会与当前笔记内容或模型配置混合。可以预览、导出或删除；新建批次会保留它们。")
                .font(.system(size: 13)).foregroundStyle(PadTheme.secondary)
            ForEach(session.incompatibleBatches) { batch in
                HStack {
                    VStack(alignment: .leading) {
                        Text("\(batch.pages.count) / \(batch.source.measuredPageCount) 页 · \(batch.source.recipient.displayName)")
                        Text(batch.updatedAt.formatted()).font(.system(size: 12)).foregroundStyle(PadTheme.secondary)
                    }
                    Spacer()
                    Button("预览") { preview = batch }
                    Menu {
                        Button("导出 Markdown") { export(batch) }
                        Button("删除", role: .destructive) { deleting = batch }.disabled(session.running)
                    } label: { Image(systemName: "ellipsis") }
                }
            }
        }.padding(18).background(Color.white.opacity(0.7), in: RoundedRectangle(cornerRadius: 16))
    }

    private var damagedDrafts: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("无法读取的数字化检查点").font(.system(size: 17, weight: .semibold))
            ForEach(session.recoveryItems) { item in
                HStack {
                    VStack(alignment: .leading) {
                        Text(item.filename).lineLimit(1)
                        Text(item.reason).font(.system(size: 12)).foregroundStyle(PadTheme.secondary).lineLimit(2)
                    }
                    Spacer(); Button("删除", role: .destructive) { deletingRecovery = item }
                }
            }
        }.padding(18).background(Color(hex: 0xFFF4E8), in: RoundedRectangle(cornerRadius: 16))
    }

    private func prepare() {
        prepareTask?.cancel()
        session.loadHistory(noteID: note.id)
        let selected = AISettingsStore().requestProfile
        profile = selected
        guard let selected else {
            session.blockSending("尚未选择可用的 AI 配置。旧草稿仍可预览或导出。")
            return
        }
        prepareTask = Task { @MainActor in
            do {
                try await CompiledTextRenderer.shared.prepareAndWait(document: note)
                try Task.checkCancellation()
                let measured = max(note.pageCount, NoteTextLayout.requiredPageCount(note))
                await session.prepare(document: note, measuredPageCount: measured,
                                      pdfURL: pdfURL, profile: selected)
            } catch is CancellationError {} catch {
                session.blockSending("无法核对当前笔记来源：\(error.localizedDescription)。旧草稿仍可预览或导出。")
            }
        }
    }

    private func startOrContinue() {
        guard let profile else { return }
        Task { @MainActor in
            if current == nil || current?.state == .published {
                await session.createBatch(document: note, pdfURL: pdfURL)
            }
            session.start(document: note, sourcePDFURL: pdfURL, profile: profile, publisher: vault)
        }
    }

    private func export(_ batch: DigitizationCheckpoint) {
        do { share = ShareArtifact(url: try session.export(batch)) }
        catch { vault.errorMessage = error.localizedDescription }
    }
}
