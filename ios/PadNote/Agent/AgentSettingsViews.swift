import SwiftUI
import UIKit
import AVFoundation

enum AgentCapabilitySummary {
    enum State: Equatable {
        case supported
        case unsupported
        case notReported
        case oldUnconfirmed
        case notChecked

        var title: String {
            switch self {
            case .supported: return "支持"
            case .unsupported: return "不支持"
            case .notReported: return "未报告"
            case .oldUnconfirmed: return "旧结果未确认"
            case .notChecked: return "未检查"
            }
        }
    }

    struct Item: Identifiable, Equatable {
        let id: String
        let title: String
        let state: State
    }

    private static let definitions: [(id: String, title: String, aliases: [String])] = [
        ("run_submission", "文字任务", ["run_submission"]),
        ("run_status", "查看任务状态", ["run_status"]),
        ("run_stop", "停止任务", ["run_stop"]),
        ("run_approval", "审批响应", ["run_approval_response", "run_approval"]),
        ("task_bundle", "任务包", ["task_bundle"]),
        ("video_task_submission", "视频任务", ["video_task_submission"]),
        ("video_operations", "分镜工作流", ["video_operations"]),
        ("video_production", "视频生成能力", ["video_production"]),
        ("runtime_verified", "电脑视频环境检查", ["runtime_verified"]),
        ("artifacts", "接收文件", ["artifacts"])
    ]

    static func items(kind: AgentKind, capabilities: [String: Bool], verified: Bool, credentialDisabled: Bool) -> [Item] {
        definitions.map { definition in
            let value = definition.aliases.first(where: { capabilities[$0] != nil }).flatMap { capabilities[$0] }
            let state: State
            if credentialDisabled || !verified {
                state = value == nil ? .notChecked : .oldUnconfirmed
            } else if let value {
                state = value ? .supported : .unsupported
            } else {
                state = .notReported
            }
            return Item(id: definition.id, title: definition.title, state: state)
        }
    }

    static func unknownCount(capabilities: [String: Bool]) -> Int {
        let understood = definitions.reduce(into: Set<String>()) { result, definition in
            result.formUnion(definition.aliases)
        }
        return capabilities.keys.filter { !understood.contains($0) }.count
    }

    static func nextStep(kind: AgentKind, transport: AgentTransport, capabilities: [String: Bool], verified: Bool, credentialDisabled: Bool) -> String {
        if credentialDisabled {
            return "凭据已停用；请重新配对连接助手，再测试连接。"
        }
        if !verified {
            return "先测试连接；如检查未通过，请修正连接设置或重新配对后重试。"
        }
        switch kind {
        case .openClaw:
            return "连接身份最近检查通过；此应用当前不支持执行 OpenClaw 任务。"
        case .builtinVideo:
            if transport == .bridge && capabilities["task_bundle"] == true && capabilities["video_task_submission"] == true {
                return "可在现有视频任务包流程中选择此连接；生成操作仍需单独确认。"
            }
            return "此连接方式或电脑报告的能力不满足视频任务包要求；请使用连接助手重新配对并测试。"
        case .hermes:
            if capabilities["run_submission"] == true {
                return "此应用支持现有 Hermes 文字任务流程；其他电脑报告能力不改变应用支持范围。"
            }
            if capabilities["run_submission"] == false {
                return "电脑明确报告暂不支持 Hermes 文字任务；请重新测试连接或修复配对。"
            }
            return "电脑未报告可用的 Hermes 文字任务能力；请重新测试连接或修复配对。"
        }
    }
}

public struct AgentSettingsView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var profiles: [AgentConnectionProfile] = []
    @State private var defaultID: UUID?
    @State private var editing: AgentConnectionProfile?
    @State private var adding = false
    @State private var pairing = false
    @State private var showingGuide = false
    @State private var showingTasks = false
    @State private var checking = Set<UUID>()
    @State private var expandedCapabilityIDs = Set<UUID>()
    @State private var message: String?
    private let store: AgentConnectionStore

    public init(store: AgentConnectionStore = AgentConnectionStore()) { self.store = store }

    public var body: some View {
        NavigationStack {
            List {
                if profiles.isEmpty {
                    ContentUnavailableView("还没有电脑连接", systemImage: "desktopcomputer", description: Text("可手动添加 Hermes，或使用电脑连接助手配对。"))
                } else {
                    Section("连接") {
                        ForEach(profiles) { profile in
                            connectionRow(profile)
                                .swipeActions(edge: .trailing) {
                                    Button("删除", role: .destructive) { store.delete(id: profile.id); reload() }
                                        .disabled(!store.credentialMutationsEnabled)
                                }
                                .contextMenu {
                                    Button("设为默认") { setDefault(profile.id) }
                                        .disabled(!store.credentialMutationsEnabled)
                                    Button("编辑") { editing = profile }
                                        .disabled(!store.credentialMutationsEnabled)
                                    Button("删除", role: .destructive) { store.delete(id: profile.id); reload() }
                                        .disabled(!store.credentialMutationsEnabled)
                                }
                        }
                    }
                }

                Section {
                    Button("手动添加 Hermes", systemImage: "plus") { adding = true }
                        .disabled(!store.credentialMutationsEnabled)
                    Button("用连接助手配对", systemImage: "qrcode.viewfinder") { pairing = true }
                        .disabled(!store.credentialMutationsEnabled)
                    Button("任务记录", systemImage: "clock.arrow.circlepath") { showingTasks = true }
                    Button("如何连接另一台电脑？", systemImage: "questionmark.circle") { showingGuide = true }
                } footer: {
                    Text("连接检查只验证身份和能力。任务会单独保留状态与来源。删除连接后，电脑上的任务仍可能继续运行。")
                }

                if let message {
                    Section { Text(message).foregroundStyle(.secondary) }
                }
            }
            .navigationTitle("电脑 Agent")
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("完成") { dismiss() } } }
            .onAppear(perform: reload)
            .sheet(isPresented: $adding) {
                AgentConnectionEditor(profile: nil, store: store) { reload(); adding = false }
            }
            .sheet(item: $editing) { profile in
                AgentConnectionEditor(profile: profile, store: store) { reload(); editing = nil }
            }
            .sheet(isPresented: $pairing) {
                AgentBridgePairingView(store: store) { reload(); pairing = false }
            }
            .sheet(isPresented: $showingGuide) { AgentConnectionGuideView() }
            .sheet(isPresented: $showingTasks) { AgentTaskListView() }
        }
    }

    @ViewBuilder
    private func connectionRow(_ profile: AgentConnectionProfile) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Button { editing = profile } label: {
                    VStack(alignment: .leading, spacing: 4) {
                        HStack {
                            Text(profile.name).font(.headline)
                            if defaultID == profile.id { Image(systemName: "checkmark.circle.fill").foregroundStyle(.tint) }
                        }
                        Text(AgentTaskDestinationLabel.make(profile))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }.buttonStyle(.plain).disabled(!store.credentialMutationsEnabled)
                Spacer()
                statusLabel(profile)
            }
            if let error = profile.lastProbeError, !error.isEmpty {
                Text("上次检查失败：\(error)")
                    .font(.caption).foregroundStyle(.orange)
            }
            DisclosureGroup(isExpanded: Binding(
                get: { expandedCapabilityIDs.contains(profile.id) },
                set: { expanded in
                    if expanded { expandedCapabilityIDs.insert(profile.id) }
                    else { expandedCapabilityIDs.remove(profile.id) }
                }
            )) {
                let disabled = profile.credentialReference.hasPrefix("padnote-disabled://")
                let items = AgentCapabilitySummary.items(kind: profile.kind, capabilities: profile.capabilities,
                    verified: profile.connected, credentialDisabled: disabled)
                VStack(alignment: .leading, spacing: 6) {
                    Text(AgentCapabilitySummary.nextStep(kind: profile.kind, transport: profile.transport, capabilities: profile.capabilities,
                        verified: profile.connected, credentialDisabled: disabled))
                        .font(.caption).foregroundStyle(.primary)
                    if profile.connected && !disabled {
                        Text("这是最近一次检查结果，不代表当前在线。")
                            .font(.caption2).foregroundStyle(.secondary)
                    }
                    ForEach(items) { item in
                        HStack {
                            Text(item.title)
                                .accessibilityIdentifier("agentCapability.\(profile.id.uuidString.lowercased()).\(item.id)")
                                .font(.caption)
                            Spacer()
                            Text(item.state.title).font(.caption)
                                .foregroundStyle(item.state == .supported ? Color.green : Color.secondary)
                        }
                    }
                    let unknownCount = AgentCapabilitySummary.unknownCount(capabilities: profile.capabilities)
                    if unknownCount > 0 {
                        Text("另有 \(unknownCount) 项未识别能力，仅供参考，不改变可用操作。")
                            .font(.caption2).foregroundStyle(.secondary)
                    }
                    Text("这些能力由电脑报告，不代表此应用支持对应操作或视频环境已预检，也不代表云服务密钥、费用授权或语音生成已获批准。")
                        .accessibilityIdentifier("agentCapabilityDisclaimer.\(profile.id.uuidString.lowercased())")
                        .font(.caption2).foregroundStyle(.secondary)
                }.padding(.vertical, 4)
            } label: {
                Text("电脑报告的能力")
            }
            .disclosureGroupStyle(AgentCapabilityDisclosureStyle(
                accessibilityIdentifier: "agentCapabilityDisclosure.\(profile.kind.rawValue).\(profile.id.uuidString.lowercased())"))
            HStack {
                Button(defaultID == profile.id ? "默认连接" : "设为默认") { setDefault(profile.id) }
                    .disabled(defaultID == profile.id || !store.credentialMutationsEnabled)
                Spacer()
                Button(checking.contains(profile.id) ? "正在检查…" : "测试连接") { probe(profile.id) }
                    .disabled(checking.contains(profile.id) || !store.credentialMutationsEnabled)
            }.buttonStyle(.borderless)
        }.padding(.vertical, 4)
    }

    @ViewBuilder
    private func statusLabel(_ profile: AgentConnectionProfile) -> some View {
        if profile.credentialReference.hasPrefix("padnote-disabled://") {
            Label("需重新连接", systemImage: "key.slash").font(.caption).foregroundStyle(.orange)
        } else if profile.connected {
            VStack(alignment: .trailing, spacing: 2) {
                Label("最近检查通过", systemImage: "checkmark.circle.fill")
                if let date = profile.verifiedAt {
                    Text(date.formatted(date: .abbreviated, time: .shortened))
                }
            }.font(.caption).foregroundStyle(.green)
        } else if profile.lastProbeError != nil {
            Label("检查失败", systemImage: "exclamationmark.circle.fill").font(.caption).foregroundStyle(.orange)
        } else {
            Text("未验证").font(.caption).foregroundStyle(.secondary)
        }
    }

    private func reload() {
        profiles = store.profiles()
        defaultID = store.defaultProfileID()
    }

    private func setDefault(_ id: UUID) {
        do { try store.setDefault(id: id); reload() }
        catch { message = error.localizedDescription }
    }

    private func probe(_ id: UUID) {
        checking.insert(id)
        message = nil
        Task { @MainActor in
            let snapshot: AgentProbeSnapshot
            do {
                snapshot = try store.snapshotForProbe(id: id)
            } catch {
                message = error.localizedDescription
                checking.remove(id)
                reload()
                return
            }
            do {
                let result = try await AgentConnectionClient(pin: snapshot.config.certSHA256).probeCapabilities(snapshot.config)
                guard store.applyProbeSuccess(id: snapshot.id, revision: snapshot.revision,
                    lifecycleEpoch: snapshot.lifecycleEpoch, capabilities: result.capabilities) else {
                    message = "连接已在检查期间修改，旧结果已忽略。"
                    checking.remove(id); reload(); return
                }
                message = result.message
            } catch {
                _ = store.applyProbeFailure(id: snapshot.id, revision: snapshot.revision,
                    lifecycleEpoch: snapshot.lifecycleEpoch, message: error.localizedDescription)
                message = error.localizedDescription
            }
            checking.remove(id)
            reload()
        }
    }
}

private struct AgentCapabilityDisclosureStyle: DisclosureGroupStyle {
    let accessibilityIdentifier: String

    func makeBody(configuration: Configuration) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Button {
                withAnimation {
                    configuration.isExpanded.toggle()
                }
            } label: {
                HStack {
                    configuration.label
                    Spacer(minLength: 12)
                    Image(systemName: configuration.isExpanded ? "chevron.down" : "chevron.right")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.secondary)
                        .accessibilityHidden(true)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.borderless)
            .accessibilityIdentifier(accessibilityIdentifier)

            if configuration.isExpanded {
                configuration.content
            }
        }
    }
}

private struct AgentConnectionEditor: View {
    let profile: AgentConnectionProfile?
    let store: AgentConnectionStore
    let onSaved: () -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var endpoint = ""
    @State private var token = ""
    @State private var error: String?

    var body: some View {
        NavigationStack {
            Form {
                Section("连接") {
                    TextField("名称", text: $name)
                    if profile?.transport == .bridge {
                        LabeledContent("方式", value: "连接助手")
                        LabeledContent("HTTPS 地址", value: endpoint)
                    } else {
                        LabeledContent("Agent", value: "Hermes")
                        TextField("HTTPS 根地址", text: $endpoint)
                            .textInputAutocapitalization(.never).autocorrectionDisabled()
                        SecureField(profile == nil ? "连接令牌" : "新令牌（留空则保留）", text: $token)
                    }
                }
                if profile != nil && profile?.transport == .direct {
                    Text("修改地址、Agent 类型或令牌会建立新的连接修订；旧任务不会转发到新身份。")
                        .font(.caption).foregroundStyle(.secondary)
                }
                if let error { Text(error).foregroundStyle(.red) }
            }
            .navigationTitle(profile == nil ? "添加 Hermes" : "编辑连接")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button("保存") { save() } }
            }
            .onAppear {
                guard let profile else { return }
                name = profile.name
                endpoint = profile.endpoint
            }
        }
    }

    private func save() {
        do {
            if let profile {
                _ = try store.update(
                    id: profile.id,
                    expectedRevision: profile.revision,
                    name: name,
                    kind: profile.kind,
                    endpoint: endpoint,
                    token: token.isEmpty ? nil : token,
                    transport: profile.transport,
                    bridgeID: profile.bridgeID,
                    instanceID: profile.instanceID
                )
            } else {
                _ = try store.create(name: name, kind: .hermes, endpoint: endpoint, token: token)
            }
            onSaved()
            dismiss()
        } catch { self.error = error.localizedDescription }
    }
}

private struct AgentBridgePairingView: View {
    let store: AgentConnectionStore
    let onComplete: () -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var pasted = ""
    @State private var code: AgentPairingCode?
    @State private var status: String?
    @State private var error: String?
    @State private var showingScanner = false
    @State private var pairingTask: Task<Void, Never>?
    private let identity = AgentDeviceIdentity()

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextEditor(text: $pasted).frame(minHeight: 110).font(.system(.caption, design: .monospaced))
                    HStack {
                        Button("解析粘贴内容") { parse() }
                        Spacer()
                        Button("扫描二维码", systemImage: "qrcode.viewfinder") { showingScanner = true }
                    }
                } header: {
                    Text("配对内容")
                } footer: {
                    Text("模拟器或未授权相机时，可粘贴电脑连接助手显示的同一段 JSON。")
                }
                if let code {
                    Section("确认电脑") {
                        LabeledContent("地址", value: code.url)
                        LabeledContent("Bridge ID", value: code.bridgeID)
                        Button("向电脑申请连接") { start(code) }.disabled(pairingTask != nil)
                    }
                }
                if let status { Text(status).foregroundStyle(.secondary) }
                if let error { Text(error).foregroundStyle(.red) }
            }
            .navigationTitle("连接助手配对")
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("取消") { pairingTask?.cancel(); dismiss() } } }
            .sheet(isPresented: $showingScanner) {
                AgentQRCodeScannerView { value in pasted = value; showingScanner = false; parse() }
            }
            .onDisappear { pairingTask?.cancel() }
        }
    }

    private func parse() {
        do { code = try AgentPairingCode(json: pasted); error = nil }
        catch { code = nil; self.error = error.localizedDescription }
    }

    private func start(_ code: AgentPairingCode) {
        pairingTask?.cancel()
        error = nil
        status = "正在向电脑申请…"
        pairingTask = Task { @MainActor in
            do {
                let deviceID = identity.id()
                let pairing = AgentBridgePairingClient(pin: code.certSHA256)
                let request = try await pairing.request(code: code, deviceID: deviceID, deviceName: UIDevice.current.name)
                status = "请在电脑上确认这台 iPad。"
                while !Task.isCancelled {
                    if Date() >= request.expiresAt { throw AgentPairingError.expired }
                    switch try await pairing.claim(code: code, request: request) {
                    case .pending:
                        try await Task.sleep(for: .seconds(2))
                    case .approved(let bridgeID, let returnedDeviceID, let connections):
                        guard returnedDeviceID == deviceID else { throw AgentPairingError.invalidResponse }
                        var failures = [String]()
                        for connection in connections {
                            do {
                                _ = try store.create(
                                    name: connection.name,
                                    kind: connection.kind,
                                    endpoint: code.url,
                                    token: connection.token,
                                    transport: .bridge,
                                    bridgeID: bridgeID,
                                    instanceID: connection.instanceID,
                                    certSHA256: code.certSHA256,
                                    makeDefault: store.defaultProfileID() == nil
                                )
                            } catch { failures.append("\(connection.name)：\(error.localizedDescription)") }
                        }
                        if failures.isEmpty {
                            status = "配对完成"
                            onComplete(); dismiss()
                        } else {
                            self.error = "部分连接未保存：\n" + failures.joined(separator: "\n")
                        }
                        pairingTask = nil
                        return
                    }
                }
            } catch is CancellationError {
            } catch {
                self.error = error.localizedDescription
            }
            pairingTask = nil
        }
    }
}

private struct AgentQRCodeScannerView: UIViewControllerRepresentable {
    let onCode: (String) -> Void

    func makeUIViewController(context: Context) -> QRScannerViewController {
        let controller = QRScannerViewController()
        controller.onCode = onCode
        return controller
    }

    func updateUIViewController(_ uiViewController: QRScannerViewController, context: Context) {}
}

private final class QRScannerViewController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
    var onCode: ((String) -> Void)?
    private let session = AVCaptureSession()
    private var preview: AVCaptureVideoPreviewLayer?

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized: configure()
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { [weak self] allowed in
                DispatchQueue.main.async { if allowed { self?.configure() } else { self?.showUnavailable() } }
            }
        default: showUnavailable()
        }
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        preview?.frame = view.bounds
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        if session.isRunning { session.stopRunning() }
    }

    private func configure() {
        guard let device = AVCaptureDevice.default(for: .video),
              let input = try? AVCaptureDeviceInput(device: device), session.canAddInput(input) else {
            showUnavailable(); return
        }
        session.addInput(input)
        let output = AVCaptureMetadataOutput()
        guard session.canAddOutput(output) else { showUnavailable(); return }
        session.addOutput(output)
        output.setMetadataObjectsDelegate(self, queue: .main)
        output.metadataObjectTypes = [.qr]
        let layer = AVCaptureVideoPreviewLayer(session: session)
        layer.videoGravity = .resizeAspectFill
        layer.frame = view.bounds
        view.layer.addSublayer(layer)
        preview = layer
        DispatchQueue.global(qos: .userInitiated).async { [session] in session.startRunning() }
    }

    private func showUnavailable() {
        let label = UILabel()
        label.text = "相机不可用。请返回并粘贴配对 JSON。"
        label.textColor = .white
        label.textAlignment = .center
        label.numberOfLines = 0
        label.frame = view.bounds.insetBy(dx: 32, dy: 32)
        label.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        view.addSubview(label)
    }

    func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput metadataObjects: [AVMetadataObject], from connection: AVCaptureConnection) {
        guard let object = metadataObjects.first as? AVMetadataMachineReadableCodeObject,
              let value = object.stringValue else { return }
        session.stopRunning()
        onCode?(value)
    }
}
