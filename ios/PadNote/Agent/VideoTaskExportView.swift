import SwiftUI

struct VideoTaskExportView: View {
    let note: VaultNote
    let onComplete: (URL?) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var audience = "希望理解这份笔记的学习者"
    @State private var goal = "理解并记住这份笔记的核心概念"
    @State private var duration = "120"
    @State private var voice = "zh-CN-neutral"
    @State private var speed = "1.0"
    @State private var error: String?
    @State private var connections: [AgentConnectionProfile] = []
    @State private var selectedConnectionID: UUID?
    @State private var sending = false
    @State private var sentTaskID: UUID?
    @State private var sentSuccessfully = false
    @State private var confirmedStaleSnapshot = false
    @State private var showStaleSnapshotConfirmation = false
    let sourceIsStale: Bool
    private let connectionStore: AgentConnectionStore
    private let taskStore: AgentTaskStore
    private let injectedService: AgentTaskService?
    init(note: VaultNote, sourceIsStale: Bool = false,
         connectionStore: AgentConnectionStore = AgentConnectionStore(),
         taskStore: AgentTaskStore = AgentTaskStore(), service: AgentTaskService? = nil,
         onComplete: @escaping (URL?) -> Void) {
        self.note = note; self.sourceIsStale = sourceIsStale; self.onComplete = onComplete
        self.connectionStore = connectionStore; self.taskStore = taskStore; self.injectedService = service
    }
    private var taskService: AgentTaskService {
        injectedService ?? AgentTaskService(connectionStore: connectionStore, taskStore: taskStore)
    }
    public var body: some View {
        NavigationStack { Form {
            Section("本次材料") {
                Text(note.title).font(.headline)
                Text(String(note.markdown.prefix(4_000))).lineLimit(12).textSelection(.enabled)
                    .accessibilityIdentifier("noteVideoMaterialPreview")
                if note.markdown.count > 4_000 { Text("预览已截断；发送的是完整已整理文本。") }
                if sourceIsStale { Label("原笔记在整理后有更新；本任务仍会使用这份固定快照。", systemImage: "exclamationmark.triangle").foregroundStyle(.orange) }
            }
            Section("发送与费用") {
                Text("点击发送后，会把上述已整理文本发送到所选电脑和你配置的供应商账户，用于生成分镜；供应商可能计费。手写墨迹、图片和 PDF 页面不会随此文本任务包发送。")
                    .font(.footnote).accessibilityIdentifier("noteVideoMaterialDisclosure")
                if let connection = connections.first(where: { $0.id == selectedConnectionID }) {
                    Text("目标电脑：\(AgentTaskDestinationLabel.make(connection)) · 账户由你配置")
                        .font(.caption).foregroundStyle(.secondary)
                }
            }
            Section("讲解目标") { TextField("受众，例如：高中生", text: $audience); TextField("学习目标", text: $goal); TextField("目标时长（30–900 秒）", text: $duration).keyboardType(.numberPad) }
                .disabled(sentTaskID != nil)
            Section("声音") { TextField("语音档案", text: $voice); TextField("速度（0.5–2.0）", text: $speed).keyboardType(.decimalPad) }
                .disabled(sentTaskID != nil)
            if !connections.isEmpty {
                Section("发送到电脑") {
                    Picker("连接", selection: $selectedConnectionID) {
                        ForEach(connections) { connection in
                            Text(AgentTaskDestinationLabel.make(connection)).tag(Optional(connection.id))
                        }
                    }.disabled(sentTaskID != nil)
                    Button(sending ? "正在发送…" : (sentSuccessfully ? "已发送" : (sentTaskID == nil ? "发送到电脑" : "重试发送")), systemImage: "paperplane") { requestSend() }
                        .disabled(sending || sentSuccessfully || selectedConnectionID == nil || !validFields)
                    if let sentTaskID {
                        NavigationLink("查看任务状态") { AgentTaskDetailView(taskID: sentTaskID, store: taskStore, service: taskService) }
                    }
                }
            } else {
                Section { Text("没有已验证且支持任务包的连接。仍可先导出 ZIP，再自行交给电脑处理。") }
                    .font(.caption).foregroundStyle(.secondary)
            }
            if let error { Text(error).foregroundStyle(.red) }
            Button("导出离线任务包") { export() }.disabled(!validFields)
        }.navigationTitle("生成视频任务包").toolbar { ToolbarItem(placement: .cancellationAction) { Button("取消") { onComplete(nil); dismiss() } } }
            .alert("使用已整理快照？", isPresented: $showStaleSnapshotConfirmation) {
                Button("取消", role: .cancel) { }.accessibilityIdentifier("cancelStaleVideoSnapshot")
                Button("继续使用旧快照") { confirmedStaleSnapshot = true; send() }
                    .accessibilityIdentifier("confirmStaleVideoSnapshot")
            } message: { Text("原笔记已更新。继续会发送整理时保存的旧文本，不会自动替换新内容。请返回并重新整理，或明确继续。") }
            .onAppear {
                connections = connectionStore.profiles().filter {
                    $0.connected && $0.transport == .bridge && $0.capabilities["task_bundle"] == true &&
                    (($0.kind == .builtinVideo && $0.capabilities["video_task_submission"] == true) ||
                     ($0.kind == .hermes && $0.capabilities["run_submission"] == true))
                }
                let preferred = connectionStore.defaultProfileID().flatMap { defaultID in
                    connections.first(where: { $0.id == defaultID })?.id
                }
                selectedConnectionID = selectedConnectionID ?? preferred ?? connections.first?.id
            }
        }
    }
    private var validFields: Bool { !audience.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !goal.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
    private func requestSend() {
        if sourceIsStale && !confirmedStaleSnapshot { showStaleSnapshotConfirmation = true; return }
        send()
    }
    private func export() {
        do {
            let data = try bundle()
            let url = FileManager.default.temporaryDirectory.appendingPathComponent("PadNote-\(UUID().uuidString).padnote-video.zip")
            try data.write(to: url, options: .atomic); onComplete(url); dismiss()
        } catch { self.error = error.localizedDescription }
    }
    private func send() {
        guard let connectionID = selectedConnectionID else { return }
        sending = true; error = nil
        Task { @MainActor in
            do {
                let service = taskService
                let taskID: UUID
                if let sentTaskID {
                    taskID = sentTaskID
                } else {
                    let data = try bundle()
                    let payload = try AgentTaskPayload(
                        title: "生成讲解视频：\(note.title)",
                        input: "请按 request.json 先生成分镜和审阅产物，等待用户确认；不要开始完整视频渲染。",
                        source: AgentTaskSource(noteID: note.id, noteRevision: max(1, Int(note.sourceUpdatedAt / 1000))),
                        bundle: data
                    )
                    let local = try service.create(connectionID: connectionID, payload: payload)
                    taskID = local.id
                    sentTaskID = taskID
                }
                let submitted = try await service.submit(id: taskID)
                sentTaskID = submitted.id
                sentSuccessfully = true
            } catch { self.error = error.localizedDescription }
            sending = false
        }
    }
    private func bundle() throws -> Data {
        guard let seconds = Int(duration.trimmingCharacters(in: .whitespaces)), (30...900).contains(seconds) else { throw VideoTaskBundleError.invalidInput }
        guard let rate = Float(speed.trimmingCharacters(in: .whitespaces)), rate.isFinite, (0.5...2).contains(rate) else { throw VideoTaskBundleError.invalidInput }
        return try VideoTaskBundleIO.make(noteId: note.id, noteRevision: max(1, Int(note.sourceUpdatedAt / 1000)), title: note.title, markdown: note.markdown, audience: audience.trimmingCharacters(in: .whitespacesAndNewlines), learningGoal: goal.trimmingCharacters(in: .whitespacesAndNewlines), durationSeconds: seconds, voiceProfile: voice.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? "zh-CN-neutral" : voice, speed: rate)
    }
}
