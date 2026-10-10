import SwiftUI
import AVKit

enum AgentTaskDestinationLabel {
    static func make(_ profile: AgentConnectionProfile) -> String {
        let name = profile.name
            .components(separatedBy: .newlines).joined(separator: " ")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let kind: String
        switch profile.kind {
        case .hermes: kind = "Hermes"
        case .openClaw: kind = "OpenClaw"
        case .builtinVideo: kind = "内置视频"
        }
        let host = hostIdentity(profile.endpoint)
        let identity: String
        if profile.transport == .bridge {
            identity = "主机 \(host) / 助手 \(short(profile.bridgeID)) / 实例 \(short(profile.instanceID))"
        } else {
            identity = "主机 \(host)"
        }
        return "\(name.isEmpty ? "未命名 Agent" : name) · \(kind) · \(identity) · #\(profile.id.uuidString.prefix(8))"
    }

    private static func short(_ value: String?) -> String {
        let safe = (value ?? "").filter { $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "-" || $0 == "_") }
        guard !safe.isEmpty else { return "未标识" }
        return safe.count <= 16 ? String(safe) : String(safe.suffix(8))
    }

    private static func hostIdentity(_ endpoint: String) -> String {
        guard let components = URLComponents(string: endpoint),
              let host = components.host, !host.isEmpty else { return "未标识主机" }
        return components.port.map { "\(host):\($0)" } ?? host
    }
}

enum AgentTaskHistoryDestinationLabel {
    static func make(_ snapshot: AgentTaskConnectionIdentity) -> String {
        let kind: String
        switch snapshot.kind {
        case .hermes: kind = "Hermes"
        case .openClaw: kind = "OpenClaw"
        case .builtinVideo: kind = "内置视频"
        }
        let host = hostPort(snapshot.endpoint)
        let transport: String
        if snapshot.transport == .bridge {
            transport = "Bridge 助手 \(short(snapshot.bridgeID)) · 实例 \(short(snapshot.instanceID))"
        } else {
            transport = "直连"
        }
        return "\(kind) · 主机 \(host) · \(transport) · 连接 #\(snapshot.connectionID.uuidString.prefix(8))"
    }

    private static func short(_ value: String?) -> String {
        let safe = (value ?? "").filter { $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "-" || $0 == "_") }
        guard !safe.isEmpty else { return "未知" }
        return safe.count <= 16 ? safe : String(safe.suffix(8))
    }

    private static func hostPort(_ endpoint: String) -> String {
        guard let components = URLComponents(string: endpoint),
              let scheme = components.scheme?.lowercased(), ["http", "https"].contains(scheme),
              let parsedHost = components.host, !parsedHost.isEmpty,
              let host = safeHost(parsedHost) else { return "未知主机:端口未知" }

        let port: Int
        if let explicitPort = components.port {
            guard (1...65535).contains(explicitPort) else { return "\(host):端口未知" }
            port = explicitPort
        } else {
            port = scheme == "https" ? 443 : 80
        }
        return "\(host):\(port)"
    }

    private static func safeHost(_ parsedHost: String) -> String? {
        let isBracketed = parsedHost.hasPrefix("[") && parsedHost.hasSuffix("]")
        let unwrapped = isBracketed ? String(parsedHost.dropFirst().dropLast()) : parsedHost
        guard !unwrapped.isEmpty,
              unwrapped.unicodeScalars.allSatisfy({ $0.isASCII }) else { return nil }

        if unwrapped.contains(":") {
            let allowedIPv6Characters = CharacterSet(charactersIn: "0123456789abcdefABCDEF:.")
            guard unwrapped.unicodeScalars.allSatisfy({ allowedIPv6Characters.contains($0) }) else { return nil }
            return "[\(unwrapped)]"
        }

        let allowedHostnameCharacters = CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789.-")
        guard unwrapped.unicodeScalars.allSatisfy({ allowedHostnameCharacters.contains($0) }) else { return nil }
        return unwrapped
    }
}

public struct AgentTaskListView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var tasks: [AgentTaskRecord] = []
    @State private var selected: AgentTaskRecord?
    @State private var composing = false
    @State private var error: String?
    private let store: AgentTaskStore
    private let service: AgentTaskService

    public init() { self.init(store: AgentTaskStore(), service: AgentTaskService()) }

    init(store: AgentTaskStore, service: AgentTaskService) {
        self.store = store
        self.service = service
    }

    public var body: some View {
        NavigationStack {
            List {
                if tasks.isEmpty {
                    ContentUnavailableView("暂无电脑任务", systemImage: "clock.arrow.circlepath", description: Text("发送到电脑的任务会保留在这里。"))
                } else {
                    ForEach(tasks) { task in
                        Button { selected = task } label: {
                            VStack(alignment: .leading, spacing: 5) {
                                HStack {
                                    Text(task.payload.title).font(.headline)
                                    Spacer()
                                    Text(label(task.status)).font(.caption).foregroundStyle(color(task.status))
                                }
                                Text("\(task.connection.connectionName) · \(task.updatedAt.formatted(date: .abbreviated, time: .shortened))")
                                    .font(.caption).foregroundStyle(.secondary)
                                Text(AgentTaskHistoryDestinationLabel.make(task.connection))
                                    .font(.caption2).foregroundStyle(.secondary)
                                    .fixedSize(horizontal: false, vertical: true)
                                    .textSelection(.enabled)
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .contentShape(Rectangle())
                        }.buttonStyle(.plain).accessibilityIdentifier("agentTaskRow-\(task.payload.clientTaskID)")
                    }
                }
                if let error { Text(error).foregroundStyle(.red) }
            }
            .navigationTitle("电脑任务")
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { Button("新建", systemImage: "plus") { composing = true } }
                ToolbarItem(placement: .confirmationAction) { Button("完成") { dismiss() } }
            }
            .onAppear(perform: reload)
            .sheet(item: $selected, onDismiss: reload) { task in AgentTaskDetailView(taskID: task.id, store: store, service: service) }
            .sheet(isPresented: $composing, onDismiss: reload) { AgentTaskComposeView() }
        }
    }

    private func reload() {
        do { tasks = try store.tasks(); error = nil }
        catch { self.error = error.localizedDescription }
    }
}

private struct AgentTaskComposeView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var connections: [AgentConnectionProfile] = []
    @State private var connectionID: UUID?
    @State private var title = ""
    @State private var input = ""
    @State private var sending = false
    @State private var error: String?
    @State private var localTaskID: UUID?
    @State private var showingTask = false

    var body: some View {
        NavigationStack {
            Form {
                Section("目标") {
                    Picker("连接", selection: $connectionID) {
                        ForEach(connections) { profile in Text(AgentTaskDestinationLabel.make(profile)).tag(Optional(profile.id)) }
                    }
                }.disabled(localTaskID != nil)
                Section("任务") {
                    TextField("标题", text: $title)
                    TextEditor(text: $input).frame(minHeight: 180)
                    Text("只发送这里明确填写的文本，最多 128 KiB。")
                        .font(.caption).foregroundStyle(.secondary)
                }.disabled(localTaskID != nil)
                if localTaskID != nil {
                    Section {
                        Button("打开任务并重试") { showingTask = true }
                    } footer: {
                        Text("首次提交结果不明时会沿用同一任务编号，避免电脑重复执行。")
                    }
                }
                if let error { Text(error).foregroundStyle(.red) }
            }
            .navigationTitle("新建电脑任务")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(sending ? "正在发送…" : (localTaskID == nil ? "发送" : "重试")) { send() }
                        .disabled(sending || connectionID == nil || title.isEmpty || input.isEmpty)
                }
            }
            .onAppear {
                let connectionStore = AgentConnectionStore()
                connections = connectionStore.profiles().filter {
                    $0.connected && $0.kind == .hermes && $0.capabilities["run_submission"] == true
                }
                let preferred = connectionStore.defaultProfileID().flatMap { defaultID in
                    connections.first(where: { $0.id == defaultID })?.id
                }
                connectionID = preferred ?? connections.first?.id
            }
            .sheet(isPresented: $showingTask) {
                if let localTaskID { AgentTaskDetailView(taskID: localTaskID) }
            }
        }
    }

    private func send() {
        guard let connectionID else { return }
        sending = true
        Task { @MainActor in
            do {
                let service = AgentTaskService()
                let taskID: UUID
                if let localTaskID {
                    taskID = localTaskID
                } else {
                    let payload = try AgentTaskPayload(
                        title: title,
                        input: input,
                        source: AgentTaskSource(noteID: "manual", noteRevision: 1)
                    )
                    let local = try service.create(connectionID: connectionID, payload: payload)
                    taskID = local.id
                    localTaskID = taskID
                }
                _ = try await service.submit(id: taskID)
                dismiss()
            } catch {
                self.error = error.localizedDescription
                if localTaskID != nil { showingTask = true }
            }
            sending = false
        }
    }
}

public struct AgentTaskDetailView: View {
    let taskID: UUID
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    @EnvironmentObject private var noteLibrary: NoteLibrary
    @State private var task: AgentTaskRecord?
    @State private var noteVideoAttachments: [NoteVideoAttachment] = []
    @State private var unavailableVideoAttachmentCount = 0
    @State private var history: [AgentTaskRecord] = []
    @State private var error: String?
    @State private var working = false
    @State private var artifactPresentation: AgentTaskArtifactPresentation?
#if DEBUG
    @State private var fixtureRetrySnapshot = AgentTaskUITestURLProtocol.Snapshot()
#endif
    @State private var composingFollowup = false
    @State private var childToOpen: UUID?
    @State private var openChild: UUID?
    private let store: AgentTaskStore
    private let service: AgentTaskService
    private let videoOperationStore: AgentVideoOperationStore
    private let videoOperationService: AgentVideoOperationService

    public init(taskID: UUID) {
        self.init(taskID: taskID, store: AgentTaskStore(), service: AgentTaskService(),
                  videoOperationStore: AgentVideoOperationStore())
    }

    init(taskID: UUID, store: AgentTaskStore, service: AgentTaskService,
         videoOperationStore: AgentVideoOperationStore = AgentVideoOperationStore(),
         videoOperationService: AgentVideoOperationService? = nil) {
        self.taskID = taskID
        self.store = store
        self.service = service
        self.videoOperationStore = videoOperationStore
        self.videoOperationService = videoOperationService ?? AgentVideoOperationService(tasks: store, store: videoOperationStore)
    }

    public var body: some View {
        NavigationStack {
            Form {
                if let task {
                    Section("任务") {
                        LabeledContent("状态", value: label(task.status))
                        VStack(alignment: .leading, spacing: 6) {
                            Text("电脑")
                                .font(.headline)
                            Text(task.connection.connectionName)
                                .fixedSize(horizontal: false, vertical: true)
                                .accessibilityIdentifier("agentTaskDetailConnectionName")
                            Text("已保存连接目标")
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                            Text(AgentTaskHistoryDestinationLabel.make(task.connection))
                                .fixedSize(horizontal: false, vertical: true)
                                .textSelection(.enabled)
                                .accessibilityIdentifier("agentTaskDetailDestinationIdentity")
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        LabeledContent("创建", value: task.createdAt.formatted(date: .abbreviated, time: .shortened))
                        if task.payload.parentTaskID != nil {
                            Text("本轮沿用原任务的内容快照；每轮结果单独保存。")
                                .font(.caption).foregroundStyle(.secondary)
                        }
                    }
                    Section("本轮输入") { Text(task.payload.input).textSelection(.enabled) }
                    if let output = task.output, !output.isEmpty {
                        Section("结果") { Text(output).textSelection(.enabled) }
                    }
                    if task.connection.transport == .bridge,
                       task.payload.requiredCapability != "note_context_bundle",
                       task.payload.bundleBase64 != nil, task.remoteTaskID != nil {
                        Section("视频任务包") {
                            NavigationLink {
                                AgentVideoOperationDetailView(
                                    taskID: task.id,
                                    taskStore: store,
                                    videoStore: videoOperationStore,
                                    service: videoOperationService
                                )
                            } label: {
                                Label("视频分镜", systemImage: "film.stack")
                            }
                            .accessibilityIdentifier("agentVideoStoryboardEntry")
                            Text("连接电脑后可初始化任务、生成分镜并审核批准。")
                                .font(.caption).foregroundStyle(.secondary)
                        }
                    }
                    if let remoteError = task.error, !remoteError.isEmpty {
                        Section("电脑返回的错误") { Text(remoteError).foregroundStyle(.red).textSelection(.enabled) }
                    }
                    if let approval = task.approval, task.status == .waitingForApproval {
                        Section(approval.title) {
                            Text(approval.description)
                            HStack {
                                Button("仅本次允许") { approve("once") }
                                    .disabled(working)
                                    .buttonStyle(.borderless)
                                    .accessibilityIdentifier("agentTaskApproveOnce")
                                Button("拒绝", role: .destructive) { approve("deny") }
                                    .disabled(working)
                                    .buttonStyle(.borderless)
                                    .accessibilityIdentifier("agentTaskDenyApproval")
                            }
                        }
                    }
                    if task.status == .completed, task.artifacts.contains(where: { $0.mediaType == "video/mp4" }) {
                        Section("已完成的视频") {
                            Text("内容审核与视频制作是两步；这里只把已完成并通过大小和 SHA-256 校验的 MP4 保存为本地附件。")
                                .font(.footnote).foregroundStyle(.secondary)
                            if !noteLibrary.notes.contains(where: { $0.id == task.payload.source.noteID }) {
                                Text("原笔记已不存在，因此不能新建关联；此任务与电脑上的成果仍可导出。")
                                    .font(.caption).foregroundStyle(.orange)
                            }
                            ForEach(task.artifacts.filter { $0.mediaType == "video/mp4" }) { artifact in
                                let alreadyAssociated = noteVideoAttachments.contains {
                                    $0.taskID == task.id && $0.artifactID == artifact.id
                                }
                                Button(alreadyAssociated ? "已关联：\(artifact.name)" : "保存到原笔记：\(artifact.name)", systemImage: alreadyAssociated ? "checkmark.paperclip" : "paperclip") {
                                    associateVideo(artifact)
                                }
                                .disabled(working || !noteLibrary.notes.contains(where: { $0.id == task.payload.source.noteID }) || task.payload.bundleSHA256 == nil || alreadyAssociated)
                                .accessibilityIdentifier("associateVideoArtifact-\(artifact.id)")
                            }
                            if unavailableVideoAttachmentCount > 0 {
                                Text("有 \(unavailableVideoAttachmentCount) 项本地附件不可用或需要检查；不会自动删除。")
                                    .font(.caption).foregroundStyle(.orange)
                            }
                            ForEach(noteVideoAttachments.filter { $0.taskID == task.id }) { attachment in
                                Text("已关联：\(attachment.displayName) · 本地离线可用")
                                    .font(.caption).accessibilityIdentifier("associatedVideo-\(attachment.id.uuidString.lowercased())")
                            }
                            if let currentSource = noteLibrary.notes.first(where: { $0.id == task.payload.source.noteID }),
                               Int(currentSource.updatedAt / 1000) != task.payload.source.noteRevision {
                                Text("当前笔记已更新；此视频仍绑定任务创建时的材料快照。")
                                    .font(.caption).foregroundStyle(.orange)
                            }
                        }
                    }
                    if !task.artifacts.isEmpty {
                        Section("产物") {
                            ForEach(task.artifacts) { artifact in
                                HStack {
                                    Button(artifact.mediaType == "video/mp4" ? "播放本地视频" : "下载 \(artifact.name)") { download(artifact) }
                                        .disabled(working)
                                        .buttonStyle(.borderless)
                                        .accessibilityIdentifier("agentVideoPlayArtifact-\(artifact.id)")
                                    if artifact.mediaType == "video/mp4" {
                                        Button("保存或分享") { downloadForSharing(artifact) }
                                            .disabled(working)
                                            .buttonStyle(.borderless)
                                            .accessibilityIdentifier("agentVideoShareArtifact-\(artifact.id)")
                                    }
                                }
                            }
                        }
                    }
                    if history.count > 1 {
                        Section("对话历史") {
                            ForEach(Array(history.enumerated()), id: \.element.id) { index, round in
                                NavigationLink {
                                    AgentTaskDetailView(taskID: round.id, store: store, service: service)
                                } label: {
                                    VStack(alignment: .leading, spacing: 3) {
                                        Text("第\(index + 1)轮 · \(round.payload.input)").lineLimit(2)
                                        Text("\(label(round.status)) · \(round.updatedAt.formatted(date: .abbreviated, time: .shortened))")
                                            .font(.caption).foregroundStyle(.secondary)
                                    }
                                }.accessibilityIdentifier("agentTaskHistoryRound-\(index + 1)")
                            }
                        }
                    }
                    Section {
                        if task.status == .submitting
                            && (task.connection.transport == .bridge || task.remoteTaskID == nil) {
                            Button("重试同一任务") { retry() }.disabled(working)
                                .accessibilityIdentifier("agentTaskRetrySubmission")
                        }
                        Button("刷新状态") { refresh() }.disabled(working || task.remoteTaskID == nil)
                        if !task.status.terminal && task.remoteTaskID != nil {
                            Button("停止任务", role: .destructive) { stop() }.disabled(working)
                        }
                    }
                    if task.connection.transport == .bridge {
                        Section("继续沟通") {
                            if let child = (try? store.followup(of: task.id)) ?? nil {
                                NavigationLink("查看后续轮次（\(label(child.status))）") {
                                    AgentTaskDetailView(taskID: child.id, store: store, service: service)
                                }.accessibilityIdentifier("agentTaskChildRoundLink")
                            } else if task.status == .completed && task.followupAvailable {
                                Button("继续沟通") { composingFollowup = true }.disabled(working)
                                    .accessibilityIdentifier("agentTaskContinueFollowup")
                                Text("继续使用这次任务的内容快照；需要更换材料时请新建任务。")
                                    .font(.caption).foregroundStyle(.secondary)
                            } else {
                                Text(task.followupReason?.isEmpty == false
                                     ? task.followupReason!
                                     : (task.status == .completed
                                        ? "电脑尚未确认此任务可以继续沟通。刷新状态后，支持追问的连接会显示继续入口。"
                                        : "任务完成后，电脑会返回是否可以继续沟通。"))
                                    .font(.footnote).foregroundStyle(.secondary)
                            }
                        }
                    }
#if DEBUG
                    if ProcessInfo.processInfo.arguments.contains("--uitesting-agent-followup") {
                        Section("测试请求记录") {
                            Text("retry=\(fixtureRetrySnapshot.retryCount);post=\(fixtureRetrySnapshot.postCount);key=\(fixtureRetrySnapshot.lastIdempotencyKey);client=\(fixtureRetrySnapshot.lastClientTaskID);parent=\(fixtureRetrySnapshot.lastParentTaskID);bytes=\(fixtureRetrySnapshot.lastBodyByteCount);stable=\(fixtureRetrySnapshot.identicalBody ? 1 : 0)")
                                .accessibilityIdentifier("fixtureSnapshot")
                        }
                    }
#endif
                } else if let error {
                    ContentUnavailableView("无法读取任务", systemImage: "exclamationmark.triangle", description: Text(error))
                } else {
                    ProgressView()
                }
                if let error, task != nil { Text(error).foregroundStyle(.red) }
            }
            .accessibilityIdentifier("agentTaskDetailForm")
            .navigationTitle(task?.payload.title ?? "任务详情")
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("完成") { dismiss() } } }
            .task(id: scenePhase) { await loadAndPoll() }
            .onAppear { refreshBuiltinVideoArtifactsAfterReturn(); loadNoteVideoAttachments() }
            .sheet(item: $artifactPresentation) { presentation in
                switch presentation {
                case .share(let url): ShareSheet(url: url)
                case .video(let url): VerifiedLocalVideoPlayer(url: url)
                }
            }
            .sheet(isPresented: $composingFollowup, onDismiss: {
                if let childToOpen { openChild = childToOpen; self.childToOpen = nil }
            }) {
                if let task { AgentTaskFollowupComposeView(task: task) { input in
                    do {
                        let submitted = try await service.createFollowup(parentID: task.id, input: input)
                        childToOpen = submitted.id
                        reloadHistory()
                        return nil
                    } catch {
                        // The immutable child is saved before the request. Keep it reachable after an
                        // ambiguous network result so retrying uses the same payload and id.
                        if let child = (try? store.followup(of: task.id)) ?? nil { childToOpen = child.id }
                        reloadHistory()
                        return error.localizedDescription
                    }
                } }
            }
            .sheet(isPresented: Binding(get: { openChild != nil }, set: { if !$0 { openChild = nil } })) {
                if let openChild { AgentTaskDetailView(taskID: openChild, store: store, service: service) }
            }
        }
    }

    private func loadAndPoll() async {
#if DEBUG
        if ProcessInfo.processInfo.arguments.contains("--uitesting-agent-followup") {
            fixtureRetrySnapshot = AgentTaskUITestURLProtocol.retryCapture()
        }
#endif
        do { task = try store.task(id: taskID); reloadHistory(); loadNoteVideoAttachments(); error = nil }
        catch { self.error = error.localizedDescription; return }
        guard scenePhase == .active else { return }
        var failureDelay = 2.5
        while !Task.isCancelled, scenePhase == .active, let current = task, !current.status.terminal, current.remoteTaskID != nil {
            do {
                try await Task.sleep(for: .seconds(failureDelay))
                task = try await service.refresh(id: taskID)
                reloadHistory()
                error = nil
                failureDelay = 2.5
            } catch is CancellationError { return }
            catch {
                self.error = error.localizedDescription
                failureDelay = min(15, failureDelay * 2)
            }
        }
    }

    private func refreshBuiltinVideoArtifactsAfterReturn() {
        guard let task, task.connection.kind == .builtinVideo, task.status == .completed,
              task.remoteTaskID != nil, task.artifacts.isEmpty, !working else { return }
        refresh()
    }

    private func refresh() {
        perform { try await service.refresh(id: taskID) }
    }

    private func retry() {
        perform { try await service.submit(id: taskID) }
    }

    private func stop() {
        perform { try await service.stop(id: taskID) }
    }

    private func approve(_ decision: String) {
        guard let approvalID = task?.approval?.id else { return }
        perform { try await service.approve(id: taskID, approvalID: approvalID, decision: decision) }
    }

    private func loadNoteVideoAttachments() {
        guard let task else { return }
        do {
            if let session = try noteLibrary.currentGroupSession(noteID: task.payload.source.noteID) {
                noteVideoAttachments = try noteLibrary.groupedVideoAttachments(basedOn: session)
                unavailableVideoAttachmentCount = 0
            } else {
                let listing = try NoteVideoAttachmentStore().listing(noteID: task.payload.source.noteID)
                noteVideoAttachments = listing.attachments
                unavailableVideoAttachmentCount = listing.unavailableCount
            }
        } catch { self.error = "无法读取本地视频附件：\(error.localizedDescription)" }
    }

    private func associateVideo(_ artifact: AgentTaskArtifact) {
        guard let task, task.status == .completed,
              noteLibrary.notes.contains(where: { $0.id == task.payload.source.noteID }),
              let remoteTaskID = task.remoteTaskID, let bundleSHA256 = task.payload.bundleSHA256 else { return }
        let capturedSession: NoteGroupEditorSession?
        do { capturedSession = try noteLibrary.currentGroupSession(noteID: task.payload.source.noteID) }
        catch { self.error = error.localizedDescription; return }
        working = true
        Task { @MainActor in
            do {
                let verifiedURL = try await service.download(id: task.id, artifact: artifact)
                let operationRoot = FileManager.default.temporaryDirectory.appendingPathComponent(
                    "note-video-stage-\(UUID().uuidString.lowercased())", isDirectory: true)
                try FileManager.default.createDirectory(at: operationRoot, withIntermediateDirectories: false,
                    attributes: [.posixPermissions: 0o700])
                defer { try? FileManager.default.removeItem(at: operationRoot) }
                let store = NoteVideoAttachmentStore(directory: operationRoot)
                let connection = task.connection
                let noteID = task.payload.source.noteID
                let taskID = task.id
                let revision = task.payload.source.noteRevision
                let sourceDigest = bundleSHA256
                let remoteID = remoteTaskID
                let attachment: NoteVideoAttachment = try await Task.detached(priority: .utility) {
                    try NoteGroupCatalogFence.withWriter {
                        try store.associate(noteID: noteID, taskID: taskID, remoteTaskID: remoteID,
                            sourceRevision: revision, sourceSnapshotSHA256: sourceDigest,
                            connection: connection, artifact: artifact, verifiedFile: verifiedURL)
                    }
                }.value
                if let capturedSession {
                    let record = try store.archiveRecords().records.first { $0.attachment == attachment }
                    guard let record else { throw NoteVideoAttachmentError.invalidMetadata }
                    _ = try noteLibrary.publishGroupedVideo(attachment, stagedVideoURL: record.fileURL,
                        stagedMetadataURL: record.metadataURL, basedOn: capturedSession)
                    noteVideoAttachments = try noteLibrary.groupedVideoAttachments(
                        basedOn: noteLibrary.currentGroupSession(noteID: noteID) ?? capturedSession)
                } else {
                    let legacyStore = NoteVideoAttachmentStore()
                    _ = try noteLibrary.associateLegacyVideo(legacyStore, noteID: noteID, taskID: taskID,
                        remoteTaskID: remoteID, sourceRevision: revision, sourceSnapshotSHA256: sourceDigest,
                        connection: connection, artifact: artifact, verifiedFile: verifiedURL)
                    noteVideoAttachments = (try? legacyStore.listing(noteID: noteID).attachments) ?? [attachment]
                }
                self.error = nil
            } catch let operationError { self.error = operationError.localizedDescription }
            working = false
        }
    }

    private func download(_ artifact: AgentTaskArtifact) {
        working = true
        Task { @MainActor in
            do {
                let url = try await service.download(id: taskID, artifact: artifact)
                artifactPresentation = artifact.mediaType == "video/mp4" ? .video(url) : .share(url)
                error = nil
            } catch { self.error = error.localizedDescription }
            working = false
        }
    }

    private func downloadForSharing(_ artifact: AgentTaskArtifact) {
        working = true
        Task { @MainActor in
            do { artifactPresentation = .share(try await service.download(id: taskID, artifact: artifact)); error = nil }
            catch { self.error = error.localizedDescription }
            working = false
        }
    }

    private func perform(_ operation: @escaping () async throws -> AgentTaskRecord) {
        working = true
        Task { @MainActor in
            do { task = try await operation(); reloadHistory(); error = nil }
            catch { self.error = error.localizedDescription }
            #if DEBUG
            if ProcessInfo.processInfo.arguments.contains("--uitesting-agent-followup") {
                fixtureRetrySnapshot = AgentTaskUITestURLProtocol.retryCapture()
            }
            #endif
            working = false
        }
    }

    private func reloadHistory() {
        history = (try? store.history(for: taskID)) ?? []
    }
}

private struct AgentTaskFollowupComposeView: View {
    @Environment(\.dismiss) private var dismiss
    let task: AgentTaskRecord
    let submit: (String) async -> String?
    @State private var input = ""
    @State private var sending = false
    @State private var error: String?

    var body: some View {
        NavigationStack {
            Form {
                Section("本轮追问") {
                    TextEditor(text: $input).frame(minHeight: 180).accessibilityIdentifier("agentTaskFollowupInput")
                    Text("会沿用第1轮确认的材料快照，并单独保存本轮输入、结果、审批和产物。")
                        .font(.caption).foregroundStyle(.secondary)
                }
                if let error { Text(error).foregroundStyle(.red) }
            }
            .navigationTitle("继续沟通")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(sending ? "正在发送…" : "发送追问") {
                        sending = true
                        Task { @MainActor in
                            if let message = await submit(input) { error = message }
                            else { dismiss() }
                            sending = false
                        }
                    }.disabled(sending || input.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                        .accessibilityIdentifier("agentTaskSendFollowup")
                }
            }
        }
    }
}

#if DEBUG
/// Isolated entry point selected only by the dedicated UI test launch argument.
/// It uses an in-memory credential and a URLProtocol that rejects every non-fixture host.
struct AgentTaskUITestHost: View {
    private let store: AgentTaskStore
    private let service: AgentTaskService
    private let setupError: String?

    init() {
        let suite = "PadNote.AgentFollowup.UITest"
        let defaults = UserDefaults(suiteName: suite)!
        defaults.removePersistentDomain(forName: suite)
        let tokens = AgentTaskUITestTokenStore()
        let connections = AgentConnectionStore(defaults: defaults, keychain: tokens)
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("PadNoteAgentFollowupUITest-\(ProcessInfo.processInfo.processIdentifier)", isDirectory: true)
        try? FileManager.default.removeItem(at: directory)
        let taskStore = AgentTaskStore(fileURL: directory)
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [AgentTaskUITestURLProtocol.self]
        let client = AgentTaskClient(session: URLSession(configuration: configuration))
        store = taskStore

        do {
            let profile = try connections.create(name: "Fixture computer", kind: .hermes, endpoint: "https://followup.fixture.test",
                token: "fixture-only-token", transport: .bridge, bridgeID: "fixture-bridge", instanceID: "fixture-instance")
            guard connections.applyProbeSuccess(id: profile.id, revision: profile.revision, capabilities: [
                "run_submission": true, "run_status": true, "run_stop": true
            ]), let verified = connections.profile(id: profile.id) else { throw AgentTaskError.connectionUnavailable }
            let parentPayload = try AgentTaskPayload(
                clientTaskID: AgentTaskUITestURLProtocol.parentClientTaskID,
                title: "UITest 追问父任务", input: "请总结选中的材料",
                source: AgentTaskSource(noteID: "uitest-snapshot", noteRevision: 3)
            )
            let parent = try taskStore.create(profile: verified, payload: parentPayload)
            _ = try taskStore.mutate(id: parent.id, expectedRevision: parent.recordRevision) {
                $0.remoteTaskID = AgentTaskUITestURLProtocol.parentRemoteTaskID
                $0.conversationID = AgentTaskUITestURLProtocol.parentRemoteTaskID
                $0.status = .running
                $0.output = nil
            }

            let pendingPayload = try AgentTaskPayload(
                clientTaskID: AgentTaskUITestURLProtocol.pendingClientTaskID,
                title: "UITest 未确认提交", input: "首次提交结果不明",
                source: AgentTaskSource(noteID: "uitest-snapshot", noteRevision: 3)
            )
            let pending = try taskStore.create(profile: verified, payload: pendingPayload)
            _ = try taskStore.mutate(id: pending.id, expectedRevision: pending.recordRevision) {
                $0.remoteTaskID = AgentTaskUITestURLProtocol.pendingRemoteTaskID
                $0.conversationID = AgentTaskUITestURLProtocol.pendingRemoteTaskID
                $0.status = .submitting
            }
            service = AgentTaskService(connectionStore: connections, taskStore: taskStore, client: client)
            setupError = nil
            AgentTaskUITestURLProtocol.resetRetryCapture()
        } catch {
            service = AgentTaskService(connectionStore: connections, taskStore: taskStore, client: client)
            setupError = error.localizedDescription
        }
    }

    var body: some View {
        Group {
            if let setupError {
                ContentUnavailableView("测试夹具启动失败", systemImage: "exclamationmark.triangle", description: Text(setupError))
            } else {
                AgentTaskListView(store: store, service: service)
            }
        }
    }
}

private final class AgentTaskUITestTokenStore: AgentTokenStore {
    private var values = [String: String]()
    func save(_ value: String, reference: String) throws { values[reference] = value }
    func read(reference: String) throws -> String? { values[reference] }
    func delete(reference: String) { values.removeValue(forKey: reference) }
}

private final class AgentTaskUITestURLProtocol: URLProtocol {
    struct Snapshot {
        var retryCount = 0
        var postCount = 0
        var lastIdempotencyKey = ""
        var lastClientTaskID = ""
        var lastParentTaskID = ""
        var lastBodyByteCount = 0
        var identicalBody = true
    }

    static let parentClientTaskID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    static let pendingClientTaskID = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
    static let parentRemoteTaskID = "bridge-parent-fixture"
    static let pendingRemoteTaskID = "bridge-pending-fixture"
    private static let lock = NSLock()
    private static var retryCount = 0
    private static var postCount = 0
    private static var firstRetryBody: Data?
    private static var identicalBody = true
    private static var lastIdempotencyKey = ""
    private static var lastClientTaskID = ""
    private static var lastParentTaskID = ""
    private static var lastBodyByteCount = 0

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    static func resetRetryCapture() {
        lock.lock(); defer { lock.unlock() }
        retryCount = 0; postCount = 0; firstRetryBody = nil; identicalBody = true; lastIdempotencyKey = ""
        lastClientTaskID = ""; lastParentTaskID = ""; lastBodyByteCount = 0
    }

    static func retryCapture() -> Snapshot {
        lock.lock(); defer { lock.unlock() }
        return Snapshot(retryCount: retryCount, postCount: postCount, lastIdempotencyKey: lastIdempotencyKey,
            lastClientTaskID: lastClientTaskID, lastParentTaskID: lastParentTaskID,
            lastBodyByteCount: lastBodyByteCount, identicalBody: identicalBody)
    }

    override func startLoading() {
        guard request.url?.host == "followup.fixture.test" else {
            client?.urlProtocol(self, didFailWithError: URLError(.unsupportedURL))
            return
        }
        let path = request.url?.path ?? ""
        let runsPath = "/padnote/v1/agents/fixture-instance/runs"
        let response: [String: Any]
        if request.httpMethod == "POST", path == runsPath {
            let body: Data
            let object: [String: Any]
            do {
                body = try Self.requestBody(request, maximumBytes: 12 * 1024 * 1024)
                guard let decoded = try JSONSerialization.jsonObject(with: body) as? [String: Any],
                      let clientTaskID = decoded["client_task_id"] as? String, !clientTaskID.isEmpty,
                      let input = decoded["input"] as? String, !input.isEmpty,
                      let title = decoded["title"] as? String, !title.isEmpty,
                      decoded["source"] is [String: Any],
                      request.value(forHTTPHeaderField: "Idempotency-Key") == clientTaskID else {
                    throw URLError(.cannotParseResponse)
                }
                object = decoded
            } catch {
                client?.urlProtocol(self, didFailWithError: error)
                return
            }

            let key = request.value(forHTTPHeaderField: "Idempotency-Key") ?? ""
            let clientTaskID = object["client_task_id"] as? String ?? ""
            let parentID = object["parent_task_id"] as? String
            if clientTaskID == Self.pendingClientTaskID, parentID == nil {
                Self.recordPost(body: body, clientTaskID: clientTaskID, parentTaskID: "", idempotencyKey: key)
                Self.lock.lock()
                Self.retryCount += 1
                if let first = Self.firstRetryBody { Self.identicalBody = Self.identicalBody && first == body && key == Self.pendingClientTaskID }
                else { Self.firstRetryBody = body; Self.identicalBody = key == Self.pendingClientTaskID }
                let attempt = Self.retryCount
                Self.lock.unlock()
                response = [
                    "task_id": Self.pendingRemoteTaskID, "instance_id": "fixture-instance",
                    "status": attempt == 1 ? "submitting" : "running",
                    "conversation_id": Self.pendingRemoteTaskID, "parent_task_id": "",
                    "followup_available": false, "followup_reason": "提交结果待对账"
                ]
            } else if let parentID, parentID == Self.parentRemoteTaskID, clientTaskID != Self.pendingClientTaskID {
                Self.recordPost(body: body, clientTaskID: clientTaskID, parentTaskID: parentID, idempotencyKey: key)
                response = [
                    "task_id": "bridge-child-fixture", "instance_id": "fixture-instance", "status": "completed",
                    "output": "追问结果：按原材料说明了原因。", "conversation_id": Self.parentRemoteTaskID,
                    "parent_task_id": parentID, "followup_available": false, "followup_reason": "此轮已结束"
                ]
            } else {
                client?.urlProtocol(self, didFailWithError: URLError(.cannotParseResponse))
                return
            }
        } else if request.httpMethod == "GET", path == "\(runsPath)/\(Self.parentRemoteTaskID)" {
            response = [
                "task_id": Self.parentRemoteTaskID, "instance_id": "fixture-instance", "status": "completed",
                "output": "父轮结果：已总结材料。", "conversation_id": Self.parentRemoteTaskID,
                "parent_task_id": "", "followup_available": true, "followup_reason": "可以继续沟通"
            ]
        } else if request.httpMethod == "GET", path == "\(runsPath)/\(Self.pendingRemoteTaskID)" {
            let count = Self.retryCapture().retryCount
            response = [
                "task_id": Self.pendingRemoteTaskID, "instance_id": "fixture-instance",
                "status": count >= 2 ? "running" : "submitting", "conversation_id": Self.pendingRemoteTaskID,
                "parent_task_id": "", "followup_available": false, "followup_reason": "提交结果待对账"
            ]
        } else if request.httpMethod == "GET", path == "\(runsPath)/bridge-child-fixture" {
            response = [
                "task_id": "bridge-child-fixture", "instance_id": "fixture-instance", "status": "completed",
                "output": "追问结果：按原材料说明了原因。", "conversation_id": Self.parentRemoteTaskID,
                "parent_task_id": Self.parentRemoteTaskID, "followup_available": false, "followup_reason": "此轮已结束"
            ]
        } else {
            client?.urlProtocol(self, didFailWithError: URLError(.unsupportedURL))
            return
        }
        Self.finish(self, response: response)
    }

    private static func requestBody(_ request: URLRequest, maximumBytes: Int) throws -> Data {
        if let body = request.httpBody {
            guard body.count <= maximumBytes else { throw AgentTaskError.bundleTooLarge }
            return body
        }
        guard let stream = request.httpBodyStream else { throw URLError(.cannotParseResponse) }
        stream.open()
        defer { stream.close() }
        var body = Data()
        var buffer = [UInt8](repeating: 0, count: 16 * 1024)
        while true {
            let count = stream.read(&buffer, maxLength: buffer.count)
            if count < 0 { throw stream.streamError ?? URLError(.cannotParseResponse) }
            if count == 0 { break }
            guard body.count + count <= maximumBytes else { throw AgentTaskError.bundleTooLarge }
            body.append(buffer, count: count)
        }
        guard !body.isEmpty else { throw URLError(.cannotParseResponse) }
        return body
    }

    private static func recordPost(body: Data, clientTaskID: String, parentTaskID: String, idempotencyKey: String) {
        lock.lock(); defer { lock.unlock() }
        postCount += 1
        lastClientTaskID = clientTaskID
        lastParentTaskID = parentTaskID
        lastIdempotencyKey = idempotencyKey
        lastBodyByteCount = body.count
    }

    private static func finish(_ protocolInstance: AgentTaskUITestURLProtocol, response: [String: Any]) {
        guard let url = protocolInstance.request.url,
              let data = try? JSONSerialization.data(withJSONObject: response),
              let http = HTTPURLResponse(url: url, statusCode: 200, httpVersion: "HTTP/1.1", headerFields: ["Content-Type": "application/json"]) else {
            protocolInstance.client?.urlProtocol(protocolInstance, didFailWithError: URLError(.cannotParseResponse))
            return
        }
        protocolInstance.client?.urlProtocol(protocolInstance, didReceive: http, cacheStoragePolicy: .notAllowed)
        protocolInstance.client?.urlProtocol(protocolInstance, didLoad: data)
        protocolInstance.client?.urlProtocolDidFinishLoading(protocolInstance)
    }

    override func stopLoading() {}
}
#endif

private func label(_ status: AgentTaskStatus) -> String {
    switch status {
    case .submitting: return "正在提交"
    case .running: return "运行中"
    case .waitingForApproval: return "等待批准"
    case .stopping: return "正在停止"
    case .completed: return "已完成"
    case .failed: return "失败"
    case .cancelled: return "已取消"
    case .interrupted: return "已中断"
    }
}

private func color(_ status: AgentTaskStatus) -> Color {
    switch status {
    case .completed: return .green
    case .failed, .interrupted: return .red
    case .cancelled: return .secondary
    case .waitingForApproval: return .orange
    default: return .blue
    }
}

struct VerifiedLocalVideoPlayer: View {
    let url: URL
    @State private var player: AVPlayer?
    @State private var status = "正在验证本地视频…"

    var body: some View {
        VStack {
            if let player { VideoPlayer(player: player).ignoresSafeArea() }
            Text(status).accessibilityIdentifier("agentVideoLocalPlayer")
        }
        .task {
            do {
                let asset = AVURLAsset(url: url)
                let tracks = try await asset.loadTracks(withMediaType: .video)
                guard !tracks.isEmpty else { throw NSError(domain: "PadNoteVideoPlayback", code: 1) }
                let ready = AVPlayer(playerItem: AVPlayerItem(asset: asset))
                player = ready
                status = "本地视频已加载"
                ready.play()
            } catch {
                status = "视频无法播放：\(error.localizedDescription)"
            }
        }
    }
}

struct NoteVideoAttachmentShelf: View {
    let noteID: String
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var noteLibrary: NoteLibrary
    @State private var attachments: [NoteVideoAttachment] = []
    @State private var restoredAttachments: [NoteGroupRestoredVideoMember] = []
    @State private var unavailableCount = 0
    @State private var playing: LocalAttachmentVideo?
    @State private var sharing: ShareArtifact?
    @State private var removing: NoteVideoAttachment?
    @State private var removingRestored: RestoredVideoAttachment?
    @State private var pendingRemoval: NoteVideoAttachment?
    @State private var pendingRestoredRemoval: RestoredVideoAttachment?
    @State private var pendingGroupSession: NoteGroupEditorSession?
    @State private var pendingWasGrouped = false
    @State private var error: String?
    private let store = NoteVideoAttachmentStore()

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text("视频保存在本机笔记附件目录，不写入笔记正文或手写笔迹。删除原笔记不会自动删除这些附件；在此移除只删除所选本地视频和关联记录。")
                        .font(.footnote).foregroundStyle(.secondary)
                }
                if unavailableCount > 0 {
                    Label("有 \(unavailableCount) 项附件记录或文件不可用；未自动删除。", systemImage: "exclamationmark.triangle")
                        .foregroundStyle(.orange)
                }
                if attachments.isEmpty && unavailableCount == 0 {
                    ContentUnavailableView("还没有关联视频", systemImage: "video", description: Text("在已完成的视频任务中选择“保存到原笔记”。"))
                }
                ForEach(attachments) { item in
                    VStack(alignment: .leading, spacing: 8) {
                        Text(item.displayName).font(.headline)
                        Text("\(ByteCountFormatter.string(fromByteCount: Int64(item.sizeBytes), countStyle: .file)) · SHA-256 已记录 · 来源任务\(item.remoteTaskID)")
                            .font(.caption).foregroundStyle(.secondary)
                        Text("任务已完成；关联不代表已逐项核对视频内容。")
                            .font(.caption).foregroundStyle(.secondary)
                        if let source = noteLibrary.notes.first(where: { $0.id == noteID }) {
                            if Int(source.updatedAt / 1000) != item.sourceRevision {
                                Label("原笔记已更新；视频仍对应旧材料快照。", systemImage: "clock.arrow.circlepath")
                                    .font(.caption).foregroundStyle(.orange)
                            }
                        } else {
                            Label("原笔记已删除；此本地附件仍可播放、分享或移除。", systemImage: "info.circle")
                                .font(.caption).foregroundStyle(.orange)
                        }
                        HStack {
                            Button("播放") { open(item, sharing: false) }.buttonStyle(.borderless)
                                .accessibilityIdentifier("playNoteVideo-\(item.id.uuidString.lowercased())")
                            Button("分享") { open(item, sharing: true) }.buttonStyle(.borderless)
                                .accessibilityIdentifier("shareNoteVideo-\(item.id.uuidString.lowercased())")
                            Button("移除", role: .destructive) { beginRemove(item) }.buttonStyle(.borderless)
                                .accessibilityIdentifier("removeNoteVideo-\(item.id.uuidString.lowercased())")
                        }
                    }.padding(.vertical, 6)
                }
                ForEach(restoredAttachments, id: \.attachment.id) { item in
                    VStack(alignment: .leading, spacing: 8) {
                        Text(item.attachment.displayName).font(.headline)
                        Text("恢复的视频 · \(ByteCountFormatter.string(fromByteCount: item.attachment.byteLength, countStyle: .file)) · SHA-256 已记录")
                            .font(.caption).foregroundStyle(.secondary)
                        Text("来源笔记：\(item.attachment.sourceNoteID)")
                            .font(.caption).foregroundStyle(.secondary)
                        HStack {
                            Button("播放") { playing = LocalAttachmentVideo(url: item.videoURL) }.buttonStyle(.borderless)
                            Button("分享") { sharing = ShareArtifact(url: item.videoURL) }.buttonStyle(.borderless)
                            Button("移除", role: .destructive) { beginRemove(item.attachment) }.buttonStyle(.borderless)
                        }
                    }.padding(.vertical, 6)
                }
            }
            .navigationTitle("笔记视频")
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("完成") { dismiss() } } }
            .task { reload() }
            .alert("移除本地视频？", isPresented: Binding(get: { removing != nil || removingRestored != nil }, set: {
                if !$0 {
                    removing = nil; removingRestored = nil
                }
            })) {
                Button("取消", role: .cancel) { clearPendingRemoval() }
                Button("移除", role: .destructive) {
                    removing = nil; removingRestored = nil
                    removeSelected()
                }
            } message: { Text("只删除这份本地 MP4 与附件关联记录。电脑任务和笔记正文不受影响。") }
            .alert("附件操作失败", isPresented: Binding(get: { error != nil }, set: { if !$0 { error = nil } })) {
                Button("取消移除", role: .cancel) { error = nil; clearPendingRemoval() }
                Button("重试") { error = nil; removeSelected() }
            } message: { Text(error ?? "") }
            .sheet(item: $playing) { item in VerifiedLocalVideoPlayer(url: item.url) }
            .sheet(item: $sharing) { ShareSheet(url: $0.url) }
        }
    }

    private func reload() {
        do {
            if let session = try noteLibrary.currentGroupSession(noteID: noteID) {
                attachments = try noteLibrary.groupedVideoAttachments(basedOn: session)
                restoredAttachments = try noteLibrary.groupedRestoredVideoMembers(basedOn: session)
                unavailableCount = 0
            } else {
                let listing = try store.listing(noteID: noteID)
                attachments = listing.attachments
                restoredAttachments = []
                unavailableCount = listing.unavailableCount
            }
        } catch { self.error = error.localizedDescription }
    }

    private func open(_ attachment: NoteVideoAttachment, sharing: Bool) {
        Task {
            do {
                let session = try await MainActor.run { try noteLibrary.currentGroupSession(noteID: noteID) }
                let url: URL
                if let session {
                    url = try await MainActor.run { try noteLibrary.groupedVideoFileURL(attachmentID: attachment.id, basedOn: session) }
                } else {
                    url = try await Task.detached(priority: .utility) { try store.fileURL(noteID: noteID, attachmentID: attachment.id) }.value
                }
                if sharing { self.sharing = ShareArtifact(url: url) }
                else { playing = LocalAttachmentVideo(url: url) }
            } catch { self.error = error.localizedDescription }
        }
    }

    private func beginRemove(_ attachment: NoteVideoAttachment) {
        do {
            pendingGroupSession = try noteLibrary.currentGroupSession(noteID: noteID)
            pendingWasGrouped = pendingGroupSession != nil
            pendingRemoval = attachment; pendingRestoredRemoval = nil
            removing = attachment
        } catch { self.error = error.localizedDescription }
    }

    private func beginRemove(_ attachment: RestoredVideoAttachment) {
        do {
            pendingGroupSession = try noteLibrary.currentGroupSession(noteID: noteID)
            pendingWasGrouped = pendingGroupSession != nil
            pendingRemoval = nil; pendingRestoredRemoval = attachment
            removingRestored = attachment
        } catch { self.error = error.localizedDescription }
    }

    private func removeSelected() {
        guard pendingRemoval != nil || pendingRestoredRemoval != nil else { return }
        do {
            if let attachment = pendingRemoval {
                if pendingWasGrouped {
                    guard let pendingGroupSession else { throw NoteGroupStoreError.compareAndSwapConflict }
                    _ = try noteLibrary.removeGroupedVideo(attachmentID: attachment.id, basedOn: pendingGroupSession)
                } else {
                    try noteLibrary.removeLegacyVideo(store, noteID: noteID, attachmentID: attachment.id)
                }
            } else if let restored = pendingRestoredRemoval {
                if pendingWasGrouped {
                    guard let pendingGroupSession else { throw NoteGroupStoreError.compareAndSwapConflict }
                    _ = try noteLibrary.removeGroupedVideo(attachmentID: restored.id, basedOn: pendingGroupSession)
                } else {
                    let restoredStore = RestoredVideoAttachmentStore()
                    try NoteGroupCatalogFence.withWriter { try restoredStore.remove(restored) }
                }
            }
            clearPendingRemoval(); error = nil; reload()
        } catch { self.error = error.localizedDescription }
    }

    private func clearPendingRemoval() {
        removing = nil; removingRestored = nil
        pendingRemoval = nil; pendingRestoredRemoval = nil
        pendingGroupSession = nil; pendingWasGrouped = false
    }
}

private struct LocalAttachmentVideo: Identifiable { let id = UUID(); let url: URL }

private enum AgentTaskArtifactPresentation: Identifiable {
    case share(URL)
    case video(URL)
    var id: String {
        switch self {
        case .share(let url): return "share:\(url.path)"
        case .video(let url): return "video:\(url.path)"
        }
    }
}


struct PaperWorkflowView: View {
    private struct PreparedSend {
        let archive: Data
        let markdown: String
        let pdfBytes: Int
        let textBytes: Int
        let revision: Int
        let noteID: String
        let title: String
        let pageCount: Int
        let presetTitle: String
        let instruction: String
        let stylePrompt: String?
        let aiTurnCount: Int
        let targetID: UUID
    }

    @Environment(\.dismiss) private var dismiss
    let note: NoteDocument
    let prepareVerifiedExport: @MainActor (NoteDocument) async throws -> PaperWorkflowExport

    @State private var connections: [AgentConnectionProfile] = []
    @State private var connectionID: UUID?
    @State private var presetID = PaperTaskPreset.continueAsRequested.rawValue
    @State private var instruction = PaperTaskPreset.continueAsRequested.instruction
    @State private var stylePrompt = PaperTaskVideoStyle.clearLecture.prompt
    @State private var aiTurns: [AIVisibleTurn] = []
    @State private var aiLoadWarning: String?
    @State private var aiConversationExists = false
    @State private var aiConversationMatchesCurrentDocument = true
    @State private var loadedAIConversationDigest: String?
    @State private var omitUnavailableAIContext = false
    @State private var prepared: PreparedSend?
    @State private var previewing = false
    @State private var sending = false
    @State private var shareArtifact: ShareArtifact?
    @State private var localTaskID: UUID?
    @State private var sent = false
    @State private var showSentTaskDetail = false
    @State private var error: String?

    private var selectedProfile: AgentConnectionProfile? {
        connections.first { $0.id == connectionID }
    }
    private var selectedPreset: PaperTaskPreset {
        PaperTaskPreset(rawValue: presetID) ?? .continueAsRequested
    }
    private var canUseVideoStyle: Bool { selectedPreset == .explainerVideo }
    private var canPrepare: Bool {
        !previewing && !sending && selectedProfile != nil &&
        !instruction.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
        (aiLoadWarning == nil || omitUnavailableAIContext) && localTaskID == nil
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("当前纸面") {
                    LabeledContent("笔记", value: note.title)
                    LabeledContent("页数", value: "\(note.pageCount)")
                    LabeledContent("正文文字", value: "\(note.textFlows.count) 个文字对象")
                    Button("分享整份纸面 PDF", systemImage: "square.and.arrow.up") { sharePDF() }
                        .disabled(previewing || sending)
                    Text("分享和发送都使用当前整份纸面。PDF 包含手写、页面背景、导入页面与 AI 排版文字；提取文本只作辅助上下文。")
                        .font(.caption).foregroundStyle(.secondary)
                }
                Section("交给电脑 Agent") {
                    if connections.isEmpty {
                        Text("没有已验证且同时支持任务包与纸面上下文的电脑 Agent。请在设置中完成 Bridge 配对和能力检查。")
                            .foregroundStyle(.secondary)
                    } else {
                        Picker("目标电脑", selection: $connectionID) {
                            ForEach(connections) { profile in Text(profile.name).tag(Optional(profile.id)) }
                        }
                    }
                    Picker("任务", selection: $presetID) {
                        ForEach(PaperTaskPreset.allCases) { preset in Text(preset.title).tag(preset.id) }
                    }
                    TextField("给 Agent 的任务要求", text: $instruction, axis: .vertical)
                        .lineLimit(3...7)
                    if canUseVideoStyle {
                        Picker("讲解风格", selection: $stylePrompt) {
                            ForEach(PaperTaskVideoStyle.allCases) { style in Text(style.rawValue).tag(style.prompt) }
                        }
                        TextField("自定义讲解风格（可留空）", text: $stylePrompt, axis: .vertical)
                            .lineLimit(2...6)
                        Text("视频预设只是交给所选 Agent 的任务意图；模型与配音工具由你或 Agent 决定，并按已授权范围和既有审阅流程处理。")
                            .font(.caption).foregroundStyle(.secondary)
                    }
                    if aiLoadWarning == nil {
                        Text(aiConversationExists
                            ? (aiConversationMatchesCurrentDocument
                                ? "将附上这本笔记中与当前内容版本匹配的 AI 交流（\(aiTurns.count) 条）。"
                                : "将附上这本笔记中较早纸面版本的 AI 交流（\(aiTurns.count) 条），并标明其版本不同。")
                            : "这本笔记没有可附加的已保存 AI 交流；将发送纸面和正文。")
                            .font(.caption).foregroundStyle(.secondary)
                    } else {
                        Text(aiLoadWarning!).font(.caption).foregroundStyle(.orange)
                        Toggle("继续发送纸面与正文，不包含无法验证的 AI 交流", isOn: $omitUnavailableAIContext)
                            .accessibilityIdentifier("paperWorkflowOmitUnavailableAI")
                    }
                    Button(previewing ? "正在生成完整预览…" : "生成发送预览", systemImage: "doc.text.magnifyingglass") {
                        preparePreview()
                    }.disabled(!canPrepare)
                }
                if let prepared, let selectedProfile, prepared.targetID == selectedProfile.id {
                    Section("发送前确认") {
                        LabeledContent("接收电脑", value: selectedProfile.name)
                        LabeledContent("范围", value: "仅此笔记 · \(prepared.pageCount) 页")
                        LabeledContent("任务", value: prepared.presetTitle)
                        Text(prepared.instruction).font(.callout)
                        if let style = prepared.stylePrompt { LabeledContent("讲解风格", value: style) }
                        DisclosureGroup("查看完整正文与本笔记 AI 交流") {
                            Text(prepared.markdown)
                                .textSelection(.enabled)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        LabeledContent("PDF", value: ByteCountFormatter.string(fromByteCount: Int64(prepared.pdfBytes), countStyle: .file))
                        LabeledContent("正文", value: ByteCountFormatter.string(fromByteCount: Int64(prepared.textBytes), countStyle: .file))
                        if prepared.aiTurnCount > 0 {
                            LabeledContent("AI 交流", value: "\(prepared.aiTurnCount) 条\(aiConversationMatchesCurrentDocument ? "" : " · 较早纸面版本")")
                        } else if aiLoadWarning != nil && omitUnavailableAIContext {
                            LabeledContent("AI 交流", value: "未包含（已按你的选择继续）")
                        }
                        LabeledContent("任务包", value: ByteCountFormatter.string(fromByteCount: Int64(prepared.archive.count), countStyle: .file))
                        Button(sending ? "正在提交…" : "确认并发送", systemImage: "paperplane.fill") { sendPreparedTask() }
                            .disabled(sending || localTaskID != nil)
                            .accessibilityIdentifier("paperWorkflowConfirmSend")
                        Text("只发送上面显示的这本笔记和选定任务；不包含其他笔记、知识库条目或连接凭据。")
                            .font(.caption).foregroundStyle(.secondary)
                    }
                }
                if let localTaskID {
                    Section("电脑任务") {
                        NavigationLink(sent ? "查看电脑任务" : "查看已保存的任务或处理提交结果") {
                            AgentTaskDetailView(taskID: localTaskID)
                        }
                        if !sent {
                            Text("任务已在本地保存。提交若结果不明，不会自动重试；请打开任务详情核对后再按原任务编号继续。")
                                .font(.caption).foregroundStyle(.orange)
                        }
                    }
                }
                if let error { Section { Text(error).foregroundStyle(.red).textSelection(.enabled) } }
            }
            .navigationTitle("分享与交给电脑 Agent")
            .navigationDestination(isPresented: $showSentTaskDetail) {
                if let localTaskID { AgentTaskDetailView(taskID: localTaskID) }
            }
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("完成") { dismiss() } }
            }
            .task { loadConnections(); loadAIContext() }
            .onChange(of: presetID) { _, value in
                instruction = PaperTaskPreset(rawValue: value)?.instruction ?? PaperTaskPreset.continueAsRequested.instruction
                prepared = nil
            }
            .onChange(of: instruction) { _, _ in prepared = nil }
            .onChange(of: stylePrompt) { _, _ in prepared = nil }
            .onChange(of: connectionID) { _, _ in prepared = nil }
            .sheet(item: $shareArtifact) { ShareSheet(url: $0.url) }
        }
    }

    private func loadConnections() {
        let store = AgentConnectionStore()
        connections = store.profiles().filter(Self.supportsPaperContext)
        let preferred = store.defaultProfileID().flatMap { id in connections.first(where: { $0.id == id })?.id }
        connectionID = preferred ?? connections.first?.id
    }

    private static func supportsPaperContext(_ profile: AgentConnectionProfile) -> Bool {
        profile.connected && profile.kind == .hermes && profile.transport == .bridge &&
        profile.capabilities["run_submission"] == true &&
        profile.capabilities["task_bundle"] == true &&
        profile.capabilities["note_context_bundle"] == true &&
        profile.capabilities["builtinVideo"] != true && profile.capabilities["builtin_video"] != true
    }

    private func loadAIContext() {
        do {
            guard let record = try AIConversationStore().load(noteID: note.id) else { return }
            aiConversationExists = true
            loadedAIConversationDigest = record.expectedDocumentDigest
            if let digest = try? AIConversationDigest.document(note) {
                aiConversationMatchesCurrentDocument = record.expectedDocumentDigest == digest
            } else {
                aiConversationMatchesCurrentDocument = false
            }
            aiTurns = record.visibleTurns.filter { $0.role == "user" || $0.role == "assistant" }
        } catch let failure {
            aiConversationExists = true
            aiLoadWarning = "无法验证这本笔记的 AI 交流（\(failure.localizedDescription)）。它不会被静默省略；如仍要继续，请明确选择仅发送纸面与正文。"
        }
    }

    private func sharePDF() {
        previewing = true
        error = nil
        Task { @MainActor in
            defer { previewing = false }
            do {
                let export = try await prepareVerifiedExport(note)
                guard export.pdf.starts(with: Data("%PDF-".utf8)) else { throw PaperTaskBundleError.invalidPDF }
                let url = FileManager.default.temporaryDirectory
                    .appendingPathComponent("PadNote-paper-\(UUID().uuidString).pdf")
                try export.pdf.write(to: url, options: .atomic)
                shareArtifact = ShareArtifact(url: url)
            } catch let failure { self.error = failure.localizedDescription }
        }
    }

    private func preparePreview() {
        guard let target = selectedProfile, Self.supportsPaperContext(target) else {
            error = "所选电脑当前未验证所需能力，请重新检查连接。"; return
        }
        previewing = true
        error = nil
        prepared = nil
        Task { @MainActor in
            defer { previewing = false }
            do {
                let export = try await prepareVerifiedExport(note)
                let exportedDigest = try AIConversationDigest.document(export.document)
                let conversationMatches = loadedAIConversationDigest == nil || loadedAIConversationDigest == exportedDigest
                aiConversationMatchesCurrentDocument = conversationMatches
                let markdown = try Self.readableContext(note: export.document, turns: aiTurns,
                    conversationMatchesCurrentDocument: conversationMatches)
                let revision = try Self.noteRevision(export.document)
                let cleanStylePrompt = stylePrompt.trimmingCharacters(in: .whitespacesAndNewlines)
                let style = canUseVideoStyle && !cleanStylePrompt.isEmpty ? cleanStylePrompt : nil
                let archive = try PaperTaskBundleIO.make(
                    noteID: export.document.id, noteRevision: revision, title: export.document.title,
                    markdown: markdown, pdf: export.pdf, instruction: instruction,
                    presetID: presetID, stylePrompt: style
                )
                prepared = PreparedSend(archive: archive, markdown: markdown, pdfBytes: export.pdf.count, textBytes: Data(markdown.utf8).count,
                    revision: revision, noteID: export.document.id, title: export.document.title,
                    pageCount: export.document.pageCount, presetTitle: selectedPreset.title, instruction: instruction,
                    stylePrompt: style, aiTurnCount: aiTurns.count, targetID: target.id)
            } catch let failure { self.error = failure.localizedDescription }
        }
    }

    private static func noteRevision(_ note: NoteDocument) throws -> Int {
        let milliseconds = note.updatedAt / 1000
        guard milliseconds.isFinite, milliseconds >= 0, milliseconds < Double(Int.max) else {
            throw PaperTaskBundleError.invalidInput
        }
        return max(1, Int(milliseconds))
    }

    private static func readableContext(note: NoteDocument, turns: [AIVisibleTurn],
                                        conversationMatchesCurrentDocument: Bool) throws -> String {
        var sections = ["# \(note.title)", "来源：当前整份纸面 · \(note.pageCount) 页",
                        "本文的 PDF 保留完整页面视觉内容；以下文字用于检索和交流，不代表从手写或 PDF 中 OCR 提取。", "## 页面文字对象"]
        let orderedFlows = note.textFlows.enumerated().sorted {
            if $0.element.anchorPageIndex != $1.element.anchorPageIndex {
                return $0.element.anchorPageIndex < $1.element.anchorPageIndex
            }
            return $0.offset < $1.offset
        }
        if orderedFlows.isEmpty { sections.append("（当前笔记没有独立文字对象；请以随附完整 PDF 为准。）") }
        for (_, flow) in orderedFlows {
            sections.append("### 第 \(flow.anchorPageIndex + 1) 页 · \(flow.format)")
            sections.append(flow.source)
        }
        if !turns.isEmpty {
            sections.append(conversationMatchesCurrentDocument
                ? "## 本笔记中与当前内容版本匹配的 AI 交流"
                : "## 本笔记中较早纸面版本的 AI 交流（作为历史学习上下文，不代表当前纸面）")
            for turn in turns {
                let role = turn.role == "user" ? "用户" : "AI"
                sections.append("### \(role)\n\(turn.content)")
            }
        }
        let result = sections.joined(separator: "\n\n")
        guard Data(result.utf8).count <= PaperTaskBundleIO.maximumExpandedBytes else {
            throw PaperTaskBundleError.tooLarge
        }
        return result
    }

    private func sendPreparedTask() {
        guard !sending, localTaskID == nil,
              let prepared, prepared.targetID == connectionID,
              let profile = selectedProfile, Self.supportsPaperContext(profile) else {
            error = "发送预览已过期或电脑能力发生变化，请重新生成预览。"; return
        }
        sending = true
        error = nil
        Task { @MainActor in
            defer { sending = false }
            do {
                let payload = try AgentTaskPayload(
                    title: "纸面任务：\(prepared.title)",
                    input: "请按任务包中的 note.work.v1 请求，结合随附整份纸面 PDF、正文和本笔记交流完成任务。",
                    source: AgentTaskSource(noteID: prepared.noteID, noteRevision: prepared.revision),
                    bundle: prepared.archive, requiredCapability: "note_context_bundle"
                )
                let service = AgentTaskService()
                let local = try service.create(connectionID: profile.id, payload: payload)
                localTaskID = local.id
                let submitted = try await service.submit(id: local.id)
                sent = submitted.status != .failed && submitted.status != .interrupted
                if sent {
                    showSentTaskDetail = true
                } else {
                    error = submitted.error ?? "电脑任务未能启动，请打开任务详情查看。"
                }
            } catch let failure { self.error = failure.localizedDescription }
        }
    }
}
