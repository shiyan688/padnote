import SwiftUI

struct AIConversationSession: Equatable {
    var messages: [AIConversationMessage] = []
    var visibleTurns: [AIVisibleTurn] = []
    var reply: String?
    var transcript: String?

    mutating func resetForBoundaryChange() {
        messages.removeAll(keepingCapacity: false)
        reply = nil
        transcript = nil
    }
}

public struct NoteAIToolCommit {
    public let mutation: NoteAIMutation?
    public let document: NoteDocument
    public init(mutation: NoteAIMutation?, document: NoteDocument) {
        self.mutation = mutation; self.document = document
    }
}
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
            try store.upsert(draft, visionToken: token, textToken: textToken)
            store.select(draft.id); store.mode = draft.mode; isCreating = false
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
    public var sourcePDFDigest: String?
    public var currentToolDocument: NoteDocument?
    public var onToolCommit: ((NoteDocument, NoteDocument) throws -> NoteAIToolCommit)?
    public var onToolMutationAction: ((NoteAIMutation, Bool) throws -> Void)?
    public var onToolLocate: ((NoteAIMutation) -> Void)?
    public var toolMutationApplied: ((UUID) -> Bool?)?
    @Environment(\.dismiss) private var dismiss
    @StateObject private var settingsStore: AISettingsStore
    @StateObject private var conversation: AIConversationController
    @State private var preset: AIPreset = .explain
    @State private var prompt = ""
    @State private var session = AIConversationSession()
    @State private var error: String?
    @State private var isSending = false
    @State private var confirm = false
    @State private var showSettings = false
    @State private var replyInserted = false
    @State private var lastMutation: NoteAIMutation?
    @State private var sendTask: Task<Void, Never>?
    @State private var insertTask: Task<Void, Never>?
    @State private var requestID = UUID()
    @State private var insertRequestID = UUID()
    @State private var allowWrites = true
    @State private var allowExistingEdits = false
    @State private var selectedVaultIDs: Set<String> = []
    @State private var toolStatus: String?
    @State private var sessionLoaded = false
    @State private var incomingReplacementAvailable = false
    @State private var restoredImage: UIImage?

    public init(image: UIImage? = nil, noteContext: String = "", allowsInsertion: Bool = true,
                embedded: Bool = false, onClose: (() -> Void)? = nil,
                toolNote: NoteDocument? = nil, toolPage: Int = 0, selectionBounds: CGRect? = nil,
                vaultEntries: [NoteToolVaultEntry] = [], currentToolDocument: NoteDocument? = nil,
                settingsStore: AISettingsStore = AISettingsStore(),
                conversationStore: AIConversationStore = AIConversationStore(),
                sourcePDFDigest: String? = nil,
                onToolCommit: ((NoteDocument, NoteDocument) throws -> NoteAIToolCommit)? = nil,
                onToolMutationAction: ((NoteAIMutation, Bool) throws -> Void)? = nil,
                onToolLocate: ((NoteAIMutation) -> Void)? = nil,
                toolMutationApplied: ((UUID) -> Bool?)? = nil,
                onInsert: @escaping (String) -> Void) {
        self.image = image
        self.noteContext = noteContext
        self.allowsInsertion = allowsInsertion
        self.embedded = embedded
        self.onClose = onClose
        self.onInsert = onInsert
        self.toolNote = toolNote; self.toolPage = toolPage; self.selectionBounds = selectionBounds
        self.vaultEntries = vaultEntries; self.currentToolDocument = currentToolDocument
        self.sourcePDFDigest = sourcePDFDigest
        _settingsStore = StateObject(wrappedValue: settingsStore)
        _conversation = StateObject(wrappedValue: AIConversationController(store: conversationStore))
        self.onToolCommit = onToolCommit; self.onToolMutationAction = onToolMutationAction
        self.onToolLocate = onToolLocate; self.toolMutationApplied = toolMutationApplied
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
                                Button(isSending ? "取消" : session.messages.isEmpty ? "发送" : "发送追问") { requestSend() }
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
        } message: { Text("选区、文字上下文\(toolNote == nil ? "" : "和不含正文的页面地图")将\(destinationSummary)。\(selectedVaultEntries.isEmpty ? "" : "模型可按需读取已选择的 \(selectedVaultEntries.count) 份知识库快照。")后续追问会携带这次对话的上下文。") }
        .onAppear { Task { await restoreConversation() } }
        .onChange(of: settingsStore.requestRecipientIdentity) { _, next in
            guard sessionLoaded, let next else { return }
            cancel()
            Task { await rotateWire(recipient: next, reason: "模型接收者已变化；旧聊天仍可查看，后续不会发送旧上下文。") }
        }
        .onChange(of: allowWrites) { _, _ in Task { await permissionDidChange() } }
        .onChange(of: allowExistingEdits) { _, _ in Task { await permissionDidChange() } }
        .onDisappear { cancel(); Task { await persistVisibleState() } }
    }

    private var assistantContent: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                if embedded {
                    HStack {
                        Button("设置") { showSettings = true }.disabled(isSending)
                        Spacer()
                        Button(isSending ? "取消" : session.messages.isEmpty ? "发送" : "发送追问") { requestSend() }
                            .buttonStyle(.borderedProminent)
                    }
                }
                if let image = activeImage {
                    Text("本次选区").font(.system(size: 17, weight: .semibold))
                    Image(uiImage: image).resizable().scaledToFit().frame(maxHeight: 220)
                        .frame(maxWidth: .infinity).background(.white)
                    Text("\(Int(image.size.width * image.scale)) × \(Int(image.size.height * image.scale)) 像素 · \((image.pngData()?.count ?? 0) / 1024) KB")
                        .font(.system(size: 12)).foregroundStyle(PadTheme.secondary)
                }
                Text(destinationSummary)
                    .font(.system(size: 12)).foregroundStyle(PadTheme.secondary)
                if let note = toolNote {
                    Text("来源：\(note.title) · 第 \(activePage + 1) 页\(selectionDescription)")
                        .font(.system(size: 12)).foregroundStyle(PadTheme.secondary)
                }
                if incomingReplacementAvailable {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("已恢复这本笔记的旧会话。当前圈选尚未替换旧材料。")
                            .font(.system(size: 13)).foregroundStyle(PadTheme.secondary)
                        HStack {
                            Button("继续旧材料") { incomingReplacementAvailable = false }
                            Button("用当前圈选更新材料") { Task { await replaceWithIncomingSource() } }
                                .buttonStyle(.borderedProminent)
                        }
                    }.padding(12).background(PadTheme.accentLight, in: RoundedRectangle(cornerRadius: 14))
                }
                if conversation.continuationBlocked {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("笔记或 PDF 已变化，旧聊天仍保留，但不会继续发送旧上下文。")
                            .font(.system(size: 13)).foregroundStyle(Color(hex: 0xA46A2A))
                        Button("以当前笔记开始新上下文") { Task { await replaceWithIncomingSource(forceCurrentDocument: true) } }
                    }
                }
                if toolNote?.pdfPageCount ?? 0 > 0, sourcePDFDigest == nil {
                    Text("附属 PDF 缺失或无法读取。旧聊天仍可查看；恢复 PDF 后才能继续发送。")
                        .font(.system(size: 13)).foregroundStyle(Color(hex: 0xA46A2A))
                }
                if !session.visibleTurns.isEmpty {
                    DisclosureGroup("会话记录（\(session.visibleTurns.count) 条）") {
                        VStack(alignment: .leading, spacing: 10) {
                            ForEach(session.visibleTurns) { turn in
                                VStack(alignment: .leading, spacing: 3) {
                                    Text(turn.role == "user" ? "你" : "AI").font(.caption.bold())
                                    if let executor = turn.executor { Text(executor).font(.caption2).foregroundStyle(PadTheme.secondary) }
                                    Text(turn.content).font(.system(size: 13)).textSelection(.enabled)
                                }.frame(maxWidth: .infinity, alignment: .leading)
                            }
                        }
                    }
                }
                if let transcript = session.transcript {
                    DisclosureGroup("本次手写转写") {
                        Text(transcript).font(.system(size: 13)).textSelection(.enabled)
                    }
                }
                if !noteContext.isEmpty {
                    DisclosureGroup("将发送的文字上下文（\(noteContext.count) 字）") {
                        Text(noteContext).font(.system(size: 13)).textSelection(.enabled)
                    }
                } else if activeImage == nil {
                    Text("当前没有选区，可直接提问。若要识别手写，请先用套索圈选内容。")
                        .font(.system(size: 13)).foregroundStyle(PadTheme.secondary)
                }
                Picker("动作", selection: $preset) {
                    ForEach(AIPreset.allCases) { Text($0.title).tag($0) }
                }.pickerStyle(.menu)
                if toolNote != nil {
                    Toggle("允许 AI 在空白处写入", isOn: $allowWrites).disabled(isSending)
                    if allowWrites { Toggle("本次允许移动或调整已有文字", isOn: $allowExistingEdits).disabled(isSending) }
                    if !vaultEntries.isEmpty {
                        DisclosureGroup("知识库材料（已选择 \(selectedVaultEntries.count) 份）") {
                            VStack(alignment: .leading, spacing: 10) {
                                ForEach(vaultEntries) { entry in
                                    Button {
                                        toggleVaultEntry(entry)
                                    } label: {
                                        HStack(alignment: .top) {
                                            Image(systemName: selectedVaultIDs.contains(entry.id) ? "checkmark.square.fill" : "square")
                                            VStack(alignment: .leading, spacing: 3) {
                                                Text(entry.title)
                                                Text(String(entry.markdown.prefix(120)))
                                                    .font(.system(size: 11)).foregroundStyle(PadTheme.secondary).lineLimit(2)
                                                Text("内容摘要 \(entry.contentSHA256.prefix(10))")
                                                    .font(.system(size: 10, design: .monospaced)).foregroundStyle(PadTheme.secondary)
                                            }
                                        }.frame(maxWidth: .infinity, alignment: .leading)
                                    }.buttonStyle(.plain).disabled(isSending)
                                }
                            }
                        }
                    }
                    DisclosureGroup("将发送的页面地图") {
                        Text(pageMap).font(.system(size: 12, design: .monospaced)).textSelection(.enabled)
                    }
                    if let toolStatus { Text(toolStatus).font(.system(size: 13)).foregroundStyle(PadTheme.secondary) }
                }
                TextEditor(text: $prompt).font(.system(size: 15)).frame(height: 96)
                    .padding(8).background(.white, in: RoundedRectangle(cornerRadius: 18))
                    .accessibilityLabel("给 AI 的问题")
                if let reply = session.reply {
                    Text(session.messages.count > 2 ? "追问回答" : "回答").font(.system(size: 17, weight: .semibold))
                    MathTextView(source: reply).frame(height: 340)
                    HStack {
                        if allowsInsertion, lastMutation == nil {
                            Button(replyInserted ? "已插入笔记" : "插入笔记", systemImage: "text.badge.plus") {
                                insertReply(reply)
                            }.buttonStyle(.bordered).disabled(replyInserted || isSending)
                        }
                        ShareLink(item: reply) { Label("分享文字", systemImage: "square.and.arrow.up") }
                    }
                }
                if let mutation = lastMutation { mutationResultCard(mutation) }
                if let recoveryFile = conversation.recoveryFile {
                    ShareLink(item: recoveryFile) {
                        Label(recoveryLabel, systemImage: "square.and.arrow.up")
                    }
                }
                if conversation.record != nil {
                    Button("清空本会话", role: .destructive) { Task { await clearConversation() } }
                        .disabled(isSending)
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

    private var activeImage: UIImage? { restoredImage ?? image }
    private var activePage: Int { conversation.record?.source.page ?? toolPage }
    private var activeBounds: CGRect? { conversation.record?.source.bounds ?? selectionBounds }

    private func incomingSource(vault: [NoteToolVaultEntry] = []) -> AIConversationSource {
        AIConversationSource(imagePNG: image?.pngData(), page: toolPage, bounds: selectionBounds,
            noteContext: noteContext, vaultEntries: NoteToolVaultEntry.bounded(vault),
            pdfDigest: sourcePDFDigest)
    }

    private func restoreConversation() async {
        guard !sessionLoaded, let note = currentToolDocument ?? toolNote else { sessionLoaded = true; return }
        let recipient = settingsStore.requestRecipientIdentity
        let initialPermission: NoteToolPermission = !allowWrites ? .readOnly
            : allowExistingEdits ? .modifyExisting : .createInFreeSpace
        let proposedSource = incomingSource()
        await conversation.open(note: note, source: proposedSource, recipient: recipient,
                                permission: initialPermission)
        if let record = conversation.record {
            session = AIConversationSession(messages: record.wireMessages,
                visibleTurns: record.visibleTurns, reply: record.reply, transcript: record.transcript)
            lastMutation = record.lastMutation
            restoredImage = record.source.imagePNG.flatMap(UIImage.init(data:))
            selectedVaultIDs = Set(record.source.vaultEntries.map(\.id))
            allowWrites = record.permission != .readOnly
            allowExistingEdits = record.permission == .modifyExisting
            incomingReplacementAvailable = image != nil && proposedSource != record.source
            if let recipient, record.recipient != recipient {
                await conversation.rotate(recipient: recipient, liveDocument: note,
                    reason: "模型接收者已变化；旧聊天仍可查看，后续不会发送旧上下文。")
                syncFromController()
            }
        }
        sessionLoaded = true
        if let persistenceError = conversation.persistenceError { error = persistenceError }
    }

    private func syncFromController() {
        guard let record = conversation.record else { return }
        session.messages = record.wireMessages
        session.visibleTurns = record.visibleTurns
        session.reply = record.reply
        session.transcript = record.transcript
        lastMutation = record.lastMutation
        restoredImage = record.source.imagePNG.flatMap(UIImage.init(data:))
        selectedVaultIDs = Set(record.source.vaultEntries.map(\.id))
    }

    private func rotateWire(recipient: AIRecipientIdentity? = nil, reason: String) async {
        guard sessionLoaded, let live = currentToolDocument ?? toolNote else { return }
        await conversation.rotate(recipient: recipient, liveDocument: live, reason: reason)
        syncFromController()
        confirm = true
        if let persistenceError = conversation.persistenceError { error = persistenceError }
    }

    private func permissionDidChange() async {
        guard sessionLoaded, let live = currentToolDocument ?? toolNote else { return }
        let permission: NoteToolPermission = !allowWrites ? .readOnly
            : allowExistingEdits ? .modifyExisting : .createInFreeSpace
        guard conversation.record?.permission != permission else { return }
        cancel()
        await conversation.rotate(permission: permission, liveDocument: live,
            reason: "写入权限已变化；旧聊天仍可查看，后续不会发送旧工具上下文。")
        syncFromController()
        confirm = true
    }

    private func replaceWithIncomingSource(forceCurrentDocument: Bool = false) async {
        guard let live = currentToolDocument ?? toolNote else { return }
        let retainedVault = conversation.record?.source.vaultEntries ?? []
        let replacement = AIConversationSource(
            imagePNG: forceCurrentDocument && image == nil ? nil : image?.pngData(),
            page: toolPage, bounds: forceCurrentDocument && image == nil ? nil : selectionBounds,
            noteContext: noteContext, vaultEntries: retainedVault, pdfDigest: sourcePDFDigest)
        cancel()
        await conversation.rotate(source: replacement, liveDocument: live,
            reason: forceCurrentDocument ? "已按当前笔记建立新上下文。" : "已用当前圈选替换会话材料。",
            explicitlyRebindSource: true)
        incomingReplacementAvailable = false
        syncFromController()
        confirm = true
    }

    private func persistVisibleState() async {
        await conversation.persistPresentation(reply: session.reply, transcript: session.transcript,
                                               mutation: lastMutation)
        if let persistenceError = conversation.persistenceError { error = persistenceError }
    }

    private func clearConversation() async {
        cancel()
        guard await conversation.clear() else {
            if let persistenceError = conversation.persistenceError { error = persistenceError }
            return
        }
        session = AIConversationSession()
        lastMutation = nil
        restoredImage = nil
        selectedVaultIDs = []
        toolStatus = nil
        sessionLoaded = false
        await restoreConversation()
        if let persistenceError = conversation.persistenceError { error = persistenceError }
    }

    private func closeView() { cancel(); onClose?(); if !embedded { dismiss() } }

    private func requestSend() {
        if isSending { cancel() }
        else if session.messages.isEmpty && (image != nil || !noteContext.isEmpty || toolNote != nil) { confirm = true }
        else { send() }
    }

    private var pageMap: String {
        guard let note = conversation.expectedToolNote ?? toolNote else { return "" }
        return NoteToolEngine(note: note, currentPage: activePage, selectionBounds: activeBounds)
            .invoke(name: "read_page_map", callID: "preview").jsonString
    }

    private var selectedVaultEntries: [NoteToolVaultEntry] {
        conversation.record?.source.vaultEntries
            ?? NoteToolVaultEntry.bounded(vaultEntries.filter { selectedVaultIDs.contains($0.id) })
    }

    private func toggleVaultEntry(_ entry: NoteToolVaultEntry) {
        var ids = selectedVaultIDs
        if ids.contains(entry.id) { ids.remove(entry.id) } else { ids.insert(entry.id) }
        let proposed = vaultEntries.filter { ids.contains($0.id) }
        let accepted = NoteToolVaultEntry.bounded(proposed)
        guard Set(accepted.map(\.id)) == Set(proposed.map(\.id)) else {
            error = entry.contentByteCount > NoteToolVaultEntry.maximumEntryBytes
                ? "单份知识库材料不能超过 512 KB。"
                : "一次最多选择 12 份、合计 2 MB 的知识库材料。"
            return
        }
        guard let live = currentToolDocument ?? toolNote else { return }
        let old = conversation.record?.source ?? incomingSource()
        let source = AIConversationSource(imagePNG: old.imagePNG, page: old.page, bounds: old.bounds,
            noteContext: old.noteContext, vaultEntries: accepted, pdfDigest: old.pdfDigest)
        cancel()
        Task { @MainActor in
            await conversation.rotate(source: source, liveDocument: live,
            reason: "可读材料已变化；旧聊天仍可查看，后续不会发送旧材料或工具结果。")
            syncFromController()
            confirm = true
            error = conversation.persistenceError
        }
    }

    private var selectionDescription: String {
        guard let selectionBounds = activeBounds else { return "" }
        return " · 选区 \(Int(selectionBounds.width))×\(Int(selectionBounds.height))"
    }

    private var recoveryLabel: String {
        switch conversation.recoveryKind {
        case .corruptOriginal: return "导出损坏的会话原文件"
        case .resumableSession: return "导出未保存会话副本"
        case .readOnlyTranscript: return "导出只读会话记录"
        case nil: return "导出会话恢复文件"
        }
    }

    private func insertReply(_ source: String) {
        guard let note = conversation.expectedToolNote ?? toolNote else { onInsert(source); replyInserted = true; return }
        let placement: [String: Any] = activeBounds == nil ? ["page": activePage + 1, "slot": "free.largest"] : ["relativeTo": "selection", "position": "below"]
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
                        anchorPageIndex: min(activePage, max(0, note.pageCount - 1)),
                        anchorXInPage: 16, anchorYInPage: 40)
                    var compilationNote = note
                    compilationNote.textFlows.append(candidate)
                    try await CompiledTextRenderer.shared.prepareAndWait(document: compilationNote)
                    guard insertRequestID == id, !Task.isCancelled else { return }
                    let engine = NoteToolEngine(note: note, currentPage: activePage, selectionBounds: activeBounds)
                    let result = engine.invoke(name: "write_text", callID: UUID().uuidString,
                        argumentsJSON: String(decoding: args, as: UTF8.self), authorization: .createInFreeSpace)
                    guard let proposed = result.proposedNote else {
                        throw NSError(domain: "PadNote.AI", code: 1,
                                      userInfo: [NSLocalizedDescriptionKey: "当前内容无法放入笔记，请缩短后重试。"])
                    }
                    guard insertRequestID == id, !Task.isCancelled else { return }
                    let committed = try onToolCommit?(note, proposed)
                    lastMutation = committed?.mutation
                    if let committed, committed.mutation != nil {
                        try await conversation.acceptManualCommit(committed,
                            liveDocument: currentToolDocument ?? committed.document)
                        syncFromController()
                    }
                    replyInserted = lastMutation != nil; isSending = false
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

    private func resetConversation() {
        cancel()
        session.resetForBoundaryChange()
        error = nil
        toolStatus = nil
        replyInserted = false
        lastMutation = nil
        confirm = false
    }

    private var destinationSummary: String {
        guard let profile = settingsStore.requestProfile else { return "未配置模型" }
        if profile.mode == .direct { return "发送到 \(URL(string: profile.visionEndpoint)?.host ?? "未配置地址") · \(profile.visionModel)" }
        return "转写 \(URL(string: profile.visionEndpoint)?.host ?? "未配置地址") · \(profile.visionModel) → 回答 \(URL(string: profile.textEndpoint)?.host ?? "未配置地址") · \(profile.textModel)"
    }

    private func send() {
        let clean = prompt.trimmingCharacters(in: .whitespacesAndNewlines)
        var content = clean.isEmpty ? instruction : clean
        let profile = settingsStore.requestProfile ?? AIProfile(visionEndpoint: settingsStore.settings.endpoint,
                                                               visionModel: settingsStore.settings.model,
                                                               visionKeyReference: settingsStore.settings.keyReference,
                                                               textKeyReference: settingsStore.settings.keyReference)
        let configuration = settingsStore.settings
        let permission: NoteToolPermission = !allowWrites ? .readOnly : allowExistingEdits ? .modifyExisting : .createInFreeSpace
        let recipient = settingsStore.requestRecipientIdentity
        let id = UUID(); requestID = id
        isSending = true; error = nil
        sendTask = Task { @MainActor in
            do {
                let client = AIClient(settings: configuration)
                if let live = currentToolDocument ?? toolNote {
                    guard let recipient else { throw AIClientError.missingToken }
                    let context = try await conversation.beginRequest(liveDocument: live,
                                                                       recipient: recipient,
                                                                       permission: permission)
                    if context.history.isEmpty && !context.source.noteContext.isEmpty {
                        content += "\n\n笔记上下文：\n" + context.source.noteContext
                    }
                    let imageURL = context.history.isEmpty
                        ? context.source.imagePNG.map { "data:image/png;base64," + $0.base64EncodedString() }
                        : nil
                    let message = AIConversationMessage(role: "user", content: content, imageDataURL: imageURL)
                    try await conversation.preflight(context, message: message)
                    var requestTranscript = context.transcript
                    if profile.mode == .twoStage, requestTranscript == nil,
                       let data = context.source.imagePNG, let sourceImage = UIImage(data: data) {
                        requestTranscript = try await client.transcribe(sourceImage, profile: profile)
                    }
                    guard requestID == id, !Task.isCancelled else { return }
                    let result = try await NoteAIConversation.run(client: client, profile: profile,
                        history: context.history, message: message, transcript: requestTranscript,
                        note: context.note, page: context.source.page, selection: context.source.bounds,
                        vault: context.source.vaultEntries, permission: permission)
                    guard requestID == id, !Task.isCancelled else { return }
                    let liveBeforeCommit = currentToolDocument ?? live
                    try await conversation.validate(context, liveDocument: liveBeforeCommit)
                    guard requestID == id, !Task.isCancelled else { return }
                    guard let commit = try onToolCommit?(context.note, result.note) else {
                        throw NoteAIConversationError.changedDocument
                    }
                    try await conversation.acceptRound(context: context, message: message, outcome: result,
                        commit: commit, liveDocument: commit.document, transcript: requestTranscript,
                        executor: recipient.display)
                    guard requestID == id, !Task.isCancelled else { return }
                    syncFromController()
                    prompt = ""
                    replyInserted = false; isSending = false
                    toolStatus = result.toolCount == 0 ? "模型仅回复了文字，笔记未更改。" : "完成 \(result.toolCount) 次工具调用\(commit.mutation == nil ? "，笔记未更改。" : "，已作为一次修改写入。")"
                    allowExistingEdits = false
                    return
                }
                if session.messages.isEmpty && !noteContext.isEmpty { content += "\n\n笔记上下文：\n" + noteContext }
                let imageURL = session.messages.isEmpty ? activeImage?.pngData().map { "data:image/png;base64," + $0.base64EncodedString() } : nil
                let message = AIConversationMessage(role: "user", content: content, imageDataURL: imageURL)
                let request = AIConversationRequest(action: preset,
                    model: profile.mode == .direct ? profile.visionModel : profile.textModel,
                    messages: [.init(role: "system", content: "请用中文回答。数学使用标准 LaTeX，行间 \\[...\\]，行内 \\(...\\)。输出 Markdown。无法确定的笔迹应明确说明。")] + session.messages + [message])
                var requestTranscript = session.transcript
                if profile.mode == .twoStage, requestTranscript == nil, let sourceImage = activeImage {
                    requestTranscript = try await client.transcribe(sourceImage, profile: profile)
                    guard requestID == id, !Task.isCancelled else { return }
                }
                let response = try await client.send(request, profile: profile, transcript: requestTranscript)
                guard requestID == id, !Task.isCancelled else { return }
                session.messages += [message, .init(role: "assistant", content: response.content)]
                session.visibleTurns += [.init(role: "user", content: message.content),
                    .init(role: "assistant", content: response.content,
                          executor: settingsStore.requestRecipientIdentity?.display)]
                session.reply = response.content; session.transcript = requestTranscript
                prompt = ""; replyInserted = false; lastMutation = nil; isSending = false
            } catch {
                guard requestID == id else { return }
                syncFromController()
                self.error = conversation.persistenceError ?? error.localizedDescription
                isSending = false
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

    @ViewBuilder
    private func mutationResultCard(_ mutation: NoteAIMutation) -> some View {
        let state = mutationState(mutation)
        let pages = currentToolDocument.map { mutation.targetPages(in: $0) } ?? []
        VStack(alignment: .leading, spacing: 10) {
            Label(mutationStatus(state, mutation: mutation), systemImage: mutationIcon(state))
                .font(.system(size: 14, weight: .semibold))
            HStack {
                if state != .differentNote, !pages.isEmpty {
                    Button(state == .applied ? "定位结果" : state == .undone ? "查看原位置" : "定位目标",
                           systemImage: "scope") { onToolLocate?(mutation) }
                        .buttonStyle(.bordered)
                }
                if state == .applied {
                    Button("撤销本次修改", systemImage: "arrow.uturn.backward") {
                        performMutationAction(mutation, apply: false)
                    }.buttonStyle(.bordered).disabled(isSending)
                } else if state == .undone {
                    Button("重新应用", systemImage: "arrow.uturn.forward") {
                        performMutationAction(mutation, apply: true)
                    }.buttonStyle(.bordered).disabled(isSending)
                }
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.white.opacity(0.72), in: RoundedRectangle(cornerRadius: 16))
    }

    private func mutationState(_ mutation: NoteAIMutation) -> NoteAIMutation.State {
        guard let currentToolDocument else { return .differentNote }
        return mutation.state(in: currentToolDocument, recordedApplied: toolMutationApplied?(mutation.id))
    }

    private func mutationStatus(_ state: NoteAIMutation.State, mutation: NoteAIMutation) -> String {
        let pages = mutation.targetPages(in: currentToolDocument ?? toolNote ?? NoteDocument()).map { String($0 + 1) }.joined(separator: "、")
        switch state {
        case .applied: return pages.isEmpty ? "AI 修改已写入" : "AI 修改已写入第 \(pages) 页"
        case .undone: return "本次 AI 修改已移除，可重新应用"
        case .partial: return "本次修改只有一部分仍在，无法整体撤销"
        case .conflict: return "目标已被继续编辑，无法安全撤销或重应用"
        case .differentNote: return "已切换笔记，不能操作这条结果"
        }
    }

    private func mutationIcon(_ state: NoteAIMutation.State) -> String {
        switch state {
        case .applied: return "checkmark.circle"
        case .undone: return "arrow.uturn.backward.circle"
        case .partial, .conflict: return "exclamationmark.triangle"
        case .differentNote: return "doc.badge.ellipsis"
        }
    }

    private func performMutationAction(_ mutation: NoteAIMutation, apply: Bool) {
        do {
            try onToolMutationAction?(mutation, apply)
            error = nil
        } catch { self.error = error.localizedDescription }
    }
}
