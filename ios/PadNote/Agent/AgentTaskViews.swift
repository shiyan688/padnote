import SwiftUI

public struct AgentTaskListView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var tasks: [AgentTaskRecord] = []
    @State private var selected: AgentTaskRecord?
    @State private var composing = false
    @State private var error: String?
    private let store = AgentTaskStore()

    public init() {}

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
                                if task.connection.transport == .bridge {
                                    Text("连接助手").font(.caption2).foregroundStyle(.secondary)
                                }
                            }
                        }.buttonStyle(.plain)
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
            .sheet(item: $selected, onDismiss: reload) { task in AgentTaskDetailView(taskID: task.id) }
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
                        ForEach(connections) { profile in Text(profile.name).tag(Optional(profile.id)) }
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
    @State private var task: AgentTaskRecord?
    @State private var error: String?
    @State private var working = false
    @State private var share: ShareArtifact?
    private let store = AgentTaskStore()
    private let service = AgentTaskService()

    public init(taskID: UUID) { self.taskID = taskID }

    public var body: some View {
        NavigationStack {
            Form {
                if let task {
                    Section("任务") {
                        LabeledContent("状态", value: label(task.status))
                        LabeledContent("电脑", value: task.connection.connectionName)
                        LabeledContent("创建", value: task.createdAt.formatted(date: .abbreviated, time: .shortened))
                    }
                    if let output = task.output, !output.isEmpty {
                        Section("结果") { Text(output).textSelection(.enabled) }
                    }
                    if let remoteError = task.error, !remoteError.isEmpty {
                        Section("电脑返回的错误") { Text(remoteError).foregroundStyle(.red).textSelection(.enabled) }
                    }
                    if let approval = task.approval, task.status == .waitingForApproval {
                        Section(approval.title) {
                            Text(approval.description)
                            HStack {
                                Button("仅本次允许") { approve("once") }.disabled(working)
                                Button("拒绝", role: .destructive) { approve("deny") }.disabled(working)
                            }
                        }
                    }
                    if !task.artifacts.isEmpty {
                        Section("产物") {
                            ForEach(task.artifacts) { artifact in
                                Button("下载 \(artifact.name)") { download(artifact) }.disabled(working)
                            }
                        }
                    }
                    Section {
                        if task.status == .submitting && task.remoteTaskID == nil {
                            Button("重试同一任务") { retry() }.disabled(working)
                        }
                        Button("刷新状态") { refresh() }.disabled(working || task.remoteTaskID == nil)
                        if !task.status.terminal && task.remoteTaskID != nil {
                            Button("停止任务", role: .destructive) { stop() }.disabled(working)
                        }
                    }
                } else if let error {
                    ContentUnavailableView("无法读取任务", systemImage: "exclamationmark.triangle", description: Text(error))
                } else {
                    ProgressView()
                }
                if let error, task != nil { Text(error).foregroundStyle(.red) }
            }
            .navigationTitle(task?.payload.title ?? "任务详情")
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("完成") { dismiss() } } }
            .task(id: scenePhase) { await loadAndPoll() }
            .sheet(item: $share) { ShareSheet(url: $0.url) }
        }
    }

    private func loadAndPoll() async {
        do { task = try store.task(id: taskID); error = nil }
        catch { self.error = error.localizedDescription; return }
        guard scenePhase == .active else { return }
        var failureDelay = 2.5
        while !Task.isCancelled, scenePhase == .active, let current = task, !current.status.terminal, current.remoteTaskID != nil {
            do {
                try await Task.sleep(for: .seconds(failureDelay))
                task = try await service.refresh(id: taskID)
                error = nil
                failureDelay = 2.5
            } catch is CancellationError { return }
            catch {
                self.error = error.localizedDescription
                failureDelay = min(15, failureDelay * 2)
            }
        }
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

    private func download(_ artifact: AgentTaskArtifact) {
        working = true
        Task { @MainActor in
            do { share = ShareArtifact(url: try await service.download(id: taskID, artifact: artifact)); error = nil }
            catch { self.error = error.localizedDescription }
            working = false
        }
    }

    private func perform(_ operation: @escaping () async throws -> AgentTaskRecord) {
        working = true
        Task { @MainActor in
            do { task = try await operation(); error = nil }
            catch { self.error = error.localizedDescription }
            working = false
        }
    }
}

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
