import SwiftUI
import UIKit

public struct AISettingsView: View {
    @Environment(\.dismiss) private var dismiss
    @StateObject private var store: AISettingsStore
    @State private var token = ""
    @State private var textToken = ""
    @State private var draft = AIProfile()
    @State private var selectedID = ""
    @State private var isCreating = false
    @State private var status: String?
    private let secrets: SecretStore

    public init(store: AISettingsStore = AISettingsStore(), secrets: SecretStore = KeychainSecretStore()) {
        _store = StateObject(wrappedValue: store)
        self.secrets = secrets
    }

    public var body: some View {
        NavigationStack {
            Form {
                Section {
                    Picker("配置档案", selection: $selectedID) {
                        ForEach(store.profiles) { profile in Text(profile.name).tag(profile.id) }
                        if isCreating { Text(draft.name).tag(draft.id) }
                    }
                    TextField("档案名称", text: $draft.name)
                    Picker("供应商预设", selection: Binding(get: { draft.provider }, set: { provider in
                        if let preset = AIProviderPreset.all.first(where: { $0.provider == provider }) { draft = draft.applying(preset) }
                    })) {
                        ForEach(AIProvider.allCases) { Text($0.title).tag($0) }
                    }
                    Picker("调用方式", selection: Binding(get: { draft.mode }, set: { mode in
                        draft.mode = mode
                        if let preset = AIProviderPreset.all.first(where: { $0.provider == draft.provider }),
                           draft.provider != .custom, draft.visionEndpoint == preset.endpoint,
                           [preset.directModel, preset.visionModel].contains(draft.visionModel) {
                            draft.visionModel = mode == .direct ? preset.directModel : preset.visionModel
                        }
                    })) {
                        ForEach(AIProfileMode.allCases) { Text($0.title).tag($0) }
                    }
                    Picker("视觉档案", selection: Binding(get: { store.visionProfileID ?? store.activeProfileID ?? "" }, set: { store.visionProfileID = $0 })) {
                        ForEach(store.profiles) { Text($0.name).tag($0.id) }
                    }
                    Picker("回答档案", selection: Binding(get: { store.textProfileID ?? store.activeProfileID ?? "" }, set: { store.textProfileID = $0 })) {
                        ForEach(store.profiles) { Text($0.name).tag($0.id) }
                    }
                } header: { Text("配置档案") } footer: {
                    Text("每个档案独立保存视觉与回答模型。预设只填写地址和模型，不读取或保存服务商密钥。")
                }
                Section {
                    TextField("视觉 API 地址", text: $draft.visionEndpoint)
                        .textInputAutocapitalization(.never).autocorrectionDisabled().keyboardType(.URL)
                    TextField("视觉模型名称", text: $draft.visionModel)
                        .textInputAutocapitalization(.never).autocorrectionDisabled()
                    SecureField("视觉 API Key（留空保留原值）", text: $token)
                    if draft.mode == .twoStage {
                        TextField("回答 API 地址", text: $draft.textEndpoint)
                            .textInputAutocapitalization(.never).autocorrectionDisabled().keyboardType(.URL)
                        TextField("回答模型名称", text: $draft.textModel)
                            .textInputAutocapitalization(.never).autocorrectionDisabled()
                        SecureField("回答 API Key（留空保留原值）", text: $textToken)
                    }
                    Button("保存档案") { saveProfile() }
                } header: { Text("模型服务") } footer: {
                    Text("填写支持 OpenAI-compatible /chat/completions 的 HTTPS 地址；应用会补齐路径。两段式会先转写选区，再把可见转写交给回答模型。")
                }
                Section {
                    Button("新建档案") { isCreating = true; draft = AIProfile(name: "新配置 \(store.profiles.count + 1)"); selectedID = draft.id; token = ""; textToken = "" }
                    Button("删除当前档案", role: .destructive) { store.delete(draft.id); loadActive() }.disabled(store.profiles.count <= 1)
                    if let status { Text(status).font(.footnote).foregroundStyle(PadTheme.secondary) }
                } header: { Text("档案管理") } footer: {
                    Text("API Key 仅保存在本机 Keychain 的独立档案引用中，不写入普通设置。")
                }
            }.scrollContentBackground(.hidden).background(PadTheme.surface)
                .navigationTitle("AI 设置")
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button("完成") { dismiss() } } }
        }.onAppear { loadActive() }
            .onChange(of: selectedID) { _, _ in
                guard store.profiles.contains(where: { $0.id == selectedID }) else { return }
                token = ""; textToken = ""; loadActive()
            }
    }

    private func loadActive() {
        guard let profile = store.profiles.first(where: { $0.id == selectedID }) ?? store.activeProfile else { return }
        isCreating = false; token = ""; textToken = ""; draft = profile; selectedID = profile.id; store.select(profile.id)
    }
    private func saveProfile() {
        do {
            _ = try AIClient.endpointURL(draft.visionEndpoint)
            if draft.mode == .twoStage { _ = try AIClient.endpointURL(draft.textEndpoint) }
            if !token.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { try secrets.write(token.trimmingCharacters(in: .whitespacesAndNewlines), reference: draft.visionKeyReference) }
            if !textToken.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { try secrets.write(textToken.trimmingCharacters(in: .whitespacesAndNewlines), reference: draft.textKeyReference) }
            store.upsert(draft); store.select(draft.id); store.mode = draft.mode; isCreating = false
            if store.visionProfileID == nil { store.visionProfileID = draft.id }
            if store.textProfileID == nil { store.textProfileID = draft.id }
            status = "已保存档案与本机 Keychain 凭据"; token = ""; textToken = ""
        } catch { status = error.localizedDescription }
    }
}

public struct AIAssistantView: View {
    public var image: UIImage?
    public var noteContext: String
    public var allowsInsertion: Bool
    public var onInsert: (String) -> Void
    public var embedded: Bool
    public var onClose: (() -> Void)?
    public var toolNote: NoteDocument?
    public var toolPage: Int
    public var selectionBounds: CGRect?
    public var vaultEntries: [NoteToolVaultEntry]
    public var onToolCommit: ((NoteDocument, NoteDocument) throws -> Void)?
    @Environment(\.dismiss) private var dismiss
    @StateObject private var settingsStore = AISettingsStore()
    @State private var preset: AIPreset = .explain
    @State private var prompt = ""
    @State private var messages: [AIConversationMessage] = []
    @State private var reply: String?
    @State private var error: String?
    @State private var isSending = false
    @State private var confirm = false
    @State private var showSettings = false
    @State private var inserted = false
    @State private var sendTask: Task<Void, Never>?
    @State private var insertTask: Task<Void, Never>?
    @State private var requestID = UUID()
    @State private var insertRequestID = UUID()
    @State private var transcript: String?
    @State private var allowWrites = true
    @State private var allowExistingEdits = false
    @State private var allowVault = false
    @State private var toolStatus: String?

    public init(image: UIImage? = nil, noteContext: String = "", allowsInsertion: Bool = true,
                embedded: Bool = false, onClose: (() -> Void)? = nil,
                toolNote: NoteDocument? = nil, toolPage: Int = 0, selectionBounds: CGRect? = nil,
                vaultEntries: [NoteToolVaultEntry] = [], onToolCommit: ((NoteDocument, NoteDocument) throws -> Void)? = nil,
                onInsert: @escaping (String) -> Void) {
        self.image = image
        self.noteContext = noteContext
        self.allowsInsertion = allowsInsertion
        self.embedded = embedded
        self.onClose = onClose
        self.onInsert = onInsert
        self.toolNote = toolNote; self.toolPage = toolPage; self.selectionBounds = selectionBounds
        self.vaultEntries = vaultEntries; self.onToolCommit = onToolCommit
    }

    public var body: some View {
        Group {
            if embedded {
                assistantContent
            } else {
                NavigationStack {
                    assistantContent.navigationTitle("AI 助手")
                        .toolbar {
                            ToolbarItem(placement: .cancellationAction) { Button("关闭") { closeView() } }
                            ToolbarItemGroup(placement: .topBarTrailing) {
                                Button("设置") { showSettings = true }.disabled(isSending)
                                Button(isSending ? "取消" : messages.isEmpty ? "发送" : "发送追问") { requestSend() }
                                    .disabled(!isSending && preset == .custom && prompt.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                            }
                        }
                }
            }
        }
        .sheet(isPresented: $showSettings) { AISettingsView(store: settingsStore) }
        .alert("发送已预览的内容？", isPresented: $confirm) {
            Button("取消", role: .cancel) {}
            Button("确认发送") { send() }
        } message: { Text("选区、文字上下文\(toolNote == nil ? "" : "和页面地图")将\(destinationSummary)。\(allowVault ? "模型可按需读取知识库，并发送检索结果。" : "")后续追问会携带这次对话的上下文。") }
        .onChange(of: settingsStore.requestProfile) { _, _ in resetConversation() }
        .onChange(of: settingsStore.profiles) { _, _ in resetConversation() }
        .onChange(of: settingsStore.settings) { _, _ in resetConversation() }
        .onDisappear { cancel() }
    }

    private var assistantContent: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                if embedded {
                    HStack {
                        Button("设置") { showSettings = true }.disabled(isSending)
                        Spacer()
                        Button(isSending ? "取消" : messages.isEmpty ? "发送" : "发送追问") { requestSend() }
                            .buttonStyle(.borderedProminent)
                    }
                }
                if let image {
                    Text("本次选区").font(.system(size: 17, weight: .semibold))
                    Image(uiImage: image).resizable().scaledToFit().frame(maxHeight: 220)
                        .frame(maxWidth: .infinity).background(.white)
                    Text("\(Int(image.size.width * image.scale)) × \(Int(image.size.height * image.scale)) 像素 · \((image.pngData()?.count ?? 0) / 1024) KB")
                        .font(.system(size: 12)).foregroundStyle(PadTheme.secondary)
                }
                Text(destinationSummary)
                    .font(.system(size: 12)).foregroundStyle(PadTheme.secondary)
                if let transcript {
                    DisclosureGroup("本次手写转写") {
                        Text(transcript).font(.system(size: 13)).textSelection(.enabled)
                    }
                }
                if !noteContext.isEmpty {
                    DisclosureGroup("将发送的文字上下文（\(noteContext.count) 字）") {
                        Text(noteContext).font(.system(size: 13)).textSelection(.enabled)
                    }
                } else if image == nil {
                    Text("当前没有选区，可直接提问。若要识别手写，请先用套索圈选内容。")
                        .font(.system(size: 13)).foregroundStyle(PadTheme.secondary)
                }
                Picker("动作", selection: $preset) {
                    ForEach(AIPreset.allCases) { Text($0.title).tag($0) }
                }.pickerStyle(.menu)
                if toolNote != nil {
                    Toggle("允许 AI 在空白处写入", isOn: $allowWrites).disabled(isSending)
                    if allowWrites { Toggle("本次允许移动或调整已有文字", isOn: $allowExistingEdits).disabled(isSending) }
                    Toggle("允许按需读取知识库", isOn: $allowVault).disabled(isSending)
                    DisclosureGroup("将发送的页面地图") {
                        Text(pageMap).font(.system(size: 12, design: .monospaced)).textSelection(.enabled)
                    }
                    if let toolStatus { Text(toolStatus).font(.system(size: 13)).foregroundStyle(PadTheme.secondary) }
                }
                TextEditor(text: $prompt).font(.system(size: 15)).frame(height: 96)
                    .padding(8).background(.white, in: RoundedRectangle(cornerRadius: 18))
                    .accessibilityLabel("给 AI 的问题")
                if let reply {
                    Text(messages.count > 2 ? "追问回答" : "回答").font(.system(size: 17, weight: .semibold))
                    MathTextView(source: reply).frame(height: 340)
                    HStack {
                        if allowsInsertion {
                            Button(inserted ? "已插入笔记" : "插入笔记", systemImage: "text.badge.plus") {
                                insertReply(reply)
                            }.buttonStyle(.bordered).disabled(inserted || isSending)
                        }
                        ShareLink(item: reply) { Label("分享文字", systemImage: "square.and.arrow.up") }
                    }
                }
                if let error { Text(error).font(.system(size: 14)).foregroundStyle(Color(hex: 0xA46A2A)) }
                if isSending { ProgressView("正在等待模型回答…") }
            }.padding(24)
        }.background(PadTheme.surface)
    }

    private func cancel() {
        requestID = UUID()
        insertRequestID = UUID()
        sendTask?.cancel()
        insertTask?.cancel()
        isSending = false
    }

    private func closeView() { cancel(); onClose?(); if !embedded { dismiss() } }

    private func requestSend() {
        if isSending { cancel() }
        else if messages.isEmpty && (image != nil || !noteContext.isEmpty || toolNote != nil) { confirm = true }
        else { send() }
    }

    private var pageMap: String {
        guard let note = toolNote else { return "" }
        return NoteToolEngine(note: note, currentPage: toolPage, selectionBounds: selectionBounds)
            .invoke(name: "read_page_map", callID: "preview").jsonString
    }

    private func insertReply(_ source: String) {
        guard let note = toolNote else { onInsert(source); inserted = true; return }
        let placement: [String: Any] = selectionBounds == nil ? ["page": toolPage + 1, "slot": "free.largest"] : ["relativeTo": "selection", "position": "below"]
        do {
            let args = try JSONSerialization.data(withJSONObject: ["content": source, "format": "markdown", "placement": placement])
            insertTask?.cancel()
            let id = UUID(); insertRequestID = id; isSending = true; error = nil
            insertTask = Task { @MainActor in
                do {
                    // Compile the existing flows and a temporary candidate
                    // first. Placement then uses the measured WebKit fragments
                    // instead of the pre-compilation CoreText estimate.
                    let width = note.pageWidth - 32
                    let candidate = NoteTextFlow(format: "markdown", source: source,
                        fontSizeSp: 16, lineHeight: 1.35, width: width,
                        anchorPageIndex: min(toolPage, max(0, note.pageCount - 1)),
                        anchorXInPage: 16, anchorYInPage: 40)
                    var compilationNote = note
                    compilationNote.textFlows.append(candidate)
                    try await CompiledTextRenderer.shared.prepareAndWait(document: compilationNote)
                    guard insertRequestID == id, !Task.isCancelled else { return }
                    let engine = NoteToolEngine(note: note, currentPage: toolPage, selectionBounds: selectionBounds)
                    let result = engine.invoke(name: "write_text", callID: UUID().uuidString,
                        argumentsJSON: String(decoding: args, as: UTF8.self), authorization: .createInFreeSpace)
                    guard let proposed = result.proposedNote else {
                        throw NSError(domain: "PadNote.AI", code: 1,
                                      userInfo: [NSLocalizedDescriptionKey: "当前内容无法放入笔记，请缩短后重试。"])
                    }
                    guard insertRequestID == id, !Task.isCancelled else { return }
                    try onToolCommit?(note, proposed)
                    inserted = true; isSending = false
                } catch is CancellationError {
                    guard insertRequestID == id else { return }
                    isSending = false
                } catch {
                    guard insertRequestID == id else { return }
                    self.error = error.localizedDescription; isSending = false
                }
            }
        } catch { self.error = error.localizedDescription }
    }

    private func resetConversation() { cancel(); messages = []; transcript = nil; reply = nil; error = nil }

    private var destinationSummary: String {
        guard let profile = settingsStore.requestProfile else { return "未配置模型" }
        if profile.mode == .direct { return "发送到 \(URL(string: profile.visionEndpoint)?.host ?? profile.visionEndpoint) · \(profile.visionModel)" }
        return "转写 \(URL(string: profile.visionEndpoint)?.host ?? profile.visionEndpoint) · \(profile.visionModel) → 回答 \(URL(string: profile.textEndpoint)?.host ?? profile.textEndpoint) · \(profile.textModel)"
    }

    private func send() {
        let clean = prompt.trimmingCharacters(in: .whitespacesAndNewlines)
        var content = clean.isEmpty ? instruction : clean
        if messages.isEmpty && !noteContext.isEmpty { content += "\n\n笔记上下文：\n" + noteContext }
        let imageURL = messages.isEmpty ? image?.pngData().map { "data:image/png;base64," + $0.base64EncodedString() } : nil
        let message = AIConversationMessage(role: "user", content: content, imageDataURL: imageURL)
        let profile = settingsStore.requestProfile ?? AIProfile(visionEndpoint: settingsStore.settings.endpoint,
                                                               visionModel: settingsStore.settings.model,
                                                               visionKeyReference: settingsStore.settings.keyReference,
                                                               textKeyReference: settingsStore.settings.keyReference)
        let request = AIConversationRequest(action: preset, model: profile.mode == .direct ? profile.visionModel : profile.textModel,
            messages: [.init(role: "system", content: "请用中文回答。数学使用标准 LaTeX，行间 \\[...\\]，行内 \\(...\\)。输出 Markdown。无法确定的笔迹应明确说明。")] + messages + [message])
        let configuration = settingsStore.settings
        let id = UUID(); requestID = id
        isSending = true; error = nil
        sendTask = Task { @MainActor in
            do {
                let client = AIClient(settings: configuration)
                if profile.mode == .twoStage, transcript == nil, let image {
                    transcript = try await client.transcribe(image, profile: profile)
                    guard requestID == id, !Task.isCancelled else { return }
                }
                if let note = toolNote {
                    let result = try await NoteAIConversation.run(client: client, profile: profile, history: messages,
                        message: message, transcript: transcript, note: note, page: toolPage,
                        selection: selectionBounds, vault: allowVault ? vaultEntries : [],
                        permission: !allowWrites ? .readOnly : allowExistingEdits ? .modifyExisting : .createInFreeSpace)
                    guard requestID == id, !Task.isCancelled else { return }
                    if result.note != note { try onToolCommit?(note, result.note) }
                    messages = result.messages
                    reply = result.reply; prompt = ""; inserted = result.note != note; isSending = false
                    toolStatus = result.toolCount == 0 ? "模型仅回复了文字，笔记未更改。" : "完成 \(result.toolCount) 次工具调用\(inserted ? "，写入可一次撤销。" : "，笔记未更改。")"
                    allowExistingEdits = false
                    return
                }
                let response = try await client.send(request, profile: profile, transcript: transcript)
                guard requestID == id, !Task.isCancelled else { return }
                messages += [message, .init(role: "assistant", content: response.content)]
                reply = response.content; prompt = ""; inserted = false; isSending = false
            } catch {
                guard requestID == id else { return }
                self.error = error.localizedDescription; isSending = false
            }
        }
    }

    private var instruction: String {
        switch preset {
        case .explain: return "请分步骤讲解圈选内容，保留公式并指出不确定处。"
        case .organizeMarkdown: return "请整理为结构化 Markdown，保留公式。"
        case .formalize: return "请改写为正式、清晰的书面文字。"
        case .extractTasks: return "请提取任务、日期和负责人，输出 Markdown 列表。"
        case .custom: return prompt
        }
    }
}
