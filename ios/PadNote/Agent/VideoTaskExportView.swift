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
    init(note: VaultNote, onComplete: @escaping (URL?) -> Void) { self.note = note; self.onComplete = onComplete }
    public var body: some View {
        NavigationStack { Form {
            Section("讲解目标") { TextField("受众，例如：高中生", text: $audience); TextField("学习目标", text: $goal); TextField("目标时长（30–900 秒）", text: $duration).keyboardType(.numberPad) }
                .disabled(sentTaskID != nil)
            Section("声音") { TextField("语音档案", text: $voice); TextField("速度（0.5–2.0）", text: $speed).keyboardType(.decimalPad) }
                .disabled(sentTaskID != nil)
            if !connections.isEmpty {
                Section("发送到电脑") {
                    Picker("连接", selection: $selectedConnectionID) {
                        ForEach(connections) { connection in Text(connection.name).tag(Optional(connection.id)) }
                    }.disabled(sentTaskID != nil)
                    Button(sending ? "正在发送…" : (sentSuccessfully ? "已发送" : (sentTaskID == nil ? "发送到电脑" : "重试发送")), systemImage: "paperplane") { send() }
                        .disabled(sending || sentSuccessfully || selectedConnectionID == nil || !validFields)
                    if let sentTaskID {
                        NavigationLink("查看任务状态") { AgentTaskDetailView(taskID: sentTaskID) }
                    }
                }
            } else {
                Section { Text("没有已验证且支持任务包的连接。仍可先导出 ZIP，再自行交给电脑处理。") }
                    .font(.caption).foregroundStyle(.secondary)
            }
            if let error { Text(error).foregroundStyle(.red) }
            Button("导出离线任务包") { export() }.disabled(!validFields)
        }.navigationTitle("生成视频任务包").toolbar { ToolbarItem(placement: .cancellationAction) { Button("取消") { onComplete(nil); dismiss() } } }
            .onAppear {
                let connectionStore = AgentConnectionStore()
                connections = connectionStore.profiles().filter {
                    $0.connected && $0.transport == .bridge && $0.kind == .hermes && $0.capabilities["task_bundle"] == true
                }
                let preferred = connectionStore.defaultProfileID().flatMap { defaultID in
                    connections.first(where: { $0.id == defaultID })?.id
                }
                selectedConnectionID = selectedConnectionID ?? preferred ?? connections.first?.id
            }
        }
    }
    private var validFields: Bool { !audience.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !goal.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
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
                let service = AgentTaskService()
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
