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
    init(note: VaultNote, onComplete: @escaping (URL?) -> Void) { self.note = note; self.onComplete = onComplete }
    public var body: some View {
        NavigationStack { Form {
            Section("讲解目标") { TextField("受众，例如：高中生", text: $audience); TextField("学习目标", text: $goal); TextField("目标时长（30–900 秒）", text: $duration).keyboardType(.numberPad) }
            Section("声音") { TextField("语音档案", text: $voice); TextField("速度（0.5–2.0）", text: $speed).keyboardType(.decimalPad) }
            if let error { Text(error).foregroundStyle(.red) }
            Button("导出任务包") { export() }.disabled(audience.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || goal.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        }.navigationTitle("生成视频任务包").toolbar { ToolbarItem(placement: .cancellationAction) { Button("取消") { onComplete(nil); dismiss() } } } }
    }
    private func export() {
        guard AgentConnectionStore().load().connected else { error = "电脑 Agent 尚未通过健康检查"; onComplete(nil); dismiss(); return }
        guard let seconds = Int(duration.trimmingCharacters(in: .whitespaces)), (30...900).contains(seconds) else { error = "时长必须是 30–900 秒"; return }
        guard let rate = Float(speed.trimmingCharacters(in: .whitespaces)), rate.isFinite, (0.5...2).contains(rate) else { error = "速度必须是 0.5–2.0"; return }
        do {
            let data = try VideoTaskBundleIO.make(noteId: note.id, noteRevision: max(1, Int(note.sourceUpdatedAt / 1000)), title: note.title, markdown: note.markdown, audience: audience.trimmingCharacters(in: .whitespacesAndNewlines), learningGoal: goal.trimmingCharacters(in: .whitespacesAndNewlines), durationSeconds: seconds, voiceProfile: voice.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? "zh-CN-neutral" : voice, speed: rate)
            let url = FileManager.default.temporaryDirectory.appendingPathComponent("PadNote-\(UUID().uuidString).padnote-video.zip")
            try data.write(to: url, options: .atomic); onComplete(url); dismiss()
        } catch { self.error = error.localizedDescription }
    }
}
