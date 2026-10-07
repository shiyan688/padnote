import SwiftUI
import UIKit
#if DEBUG
import CryptoKit
#endif

public struct AgentVideoOperationDetailView: View {
    @Environment(\.scenePhase) private var scenePhase
    private let taskID: UUID
    private let taskStore: AgentTaskStore
    private let videoStore: AgentVideoOperationStore
    private let service: AgentVideoOperationService
    @State private var parent: AgentTaskRecord?
    @State private var snapshot = AgentVideoTaskSnapshot()
    @State private var diagnostic: AgentVideoDiagnostic?
    @State private var review: AgentVideoReview?
    @State private var reviewFresh = false
    @State private var previewData: [String: Data] = [:]
    @State private var loadingPreviews = false
    @State private var working = false
    @State private var error: String?
    @State private var confirmApproval = false
    @State private var confirmStoryboardCost = false
    @State private var confirmVideoProduction = false
    @State private var confirmRunningCancel = false
    @State private var cancelActionIsProduce = false
    @State private var retryingRunningCancel = false
    @State private var connectionAvailable = false
    @State private var videoOperationsAvailable = false
    @State private var videoProductionAvailable = false
    @State private var diagnosticsExpanded = false
    @State private var runningCancelTask: Task<Void, Never>?

    public init(taskID: UUID) {
        self.init(taskID: taskID, taskStore: AgentTaskStore(), videoStore: AgentVideoOperationStore(),
                  service: AgentVideoOperationService())
    }

    init(taskID: UUID, taskStore: AgentTaskStore, videoStore: AgentVideoOperationStore,
         service: AgentVideoOperationService) {
        self.taskID = taskID
        self.taskStore = taskStore
        self.videoStore = videoStore
        self.service = service
    }

    public var body: some View {
        Form {
            if let parent {
                Section("原任务") {
                    LabeledContent("任务", value: parent.payload.title)
                    LabeledContent("电脑编号", value: parent.remoteTaskID ?? "未提交")
                    Text("视频操作有单独的本地记录，不改变原任务状态或文本结果。")
                        .font(.caption).foregroundStyle(.secondary)
                }
                diagnosticsSection
                operationSection
                if let review { reviewSection(review) }
                Section {
                    Text("批准只记录当前版本的分镜授权，不会开始配音、付费请求或视频渲染。")
                        .font(.footnote).foregroundStyle(.secondary)
                }
            } else {
                ProgressView("读取视频任务…")
            }
        }
        .navigationTitle("视频分镜")
        .task { await load() }
        .onDisappear {
            // Reads and a not-yet-dispatched stop request belong to this view.
            // The durable intent stays on disk; the async task cannot advance from
            // its status GET to POST after the user leaves this screen.
            runningCancelTask?.cancel()
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { refreshConnectionBinding() }
        }
        .confirmationDialog("批准第\(review?.revision ?? 0)版分镜？", isPresented: $confirmApproval,
                            titleVisibility: .visible) {
            Button("批准第\(review?.revision ?? 0)版分镜", role: .destructive) { approve() }
                .accessibilityIdentifier("videoApproveCurrentRevision")
            Button("再检查一下", role: .cancel) {}
        } message: {
            Text("本次批准绑定当前显示的版本、事件序号和内容摘要；不会启动配音或渲染。")
        }
        .confirmationDialog("发送笔记材料并生成分镜？", isPresented: $confirmStoryboardCost, titleVisibility: .visible) {
            Button("发送材料并生成分镜", role: .destructive) { start(.storyboard) }
                .accessibilityIdentifier("videoConfirmStoryboardCost")
            Button("返回", role: .cancel) {}
                .accessibilityIdentifier("videoCancelStoryboardCostConfirmation")
        } message: {
            Text("本次会将任务包中笔记“\(parent?.payload.title ?? "本视频任务")”的完整 Markdown 正文，以及本任务填写的受众和学习目标发送到“\(parent?.connection.connectionName ?? "已配对")”电脑，再调用你配置的模型供应商账户生成分镜；供应商可能收费。只确认本次分镜请求。")
        }
        .confirmationDialog("生成视频并调用云配音？", isPresented: $confirmVideoProduction, titleVisibility: .visible) {
            Button("同意配音并生成视频", role: .destructive) { start(.produce) }
                .accessibilityIdentifier("videoConfirmProduction")
            Button("暂不生成", role: .cancel) {}
                .accessibilityIdentifier("videoCancelProductionConfirmation")
        } message: {
            Text("本次会把批准稿发给你配置的配音服务，再在电脑上渲染。配音可能产生供应商费用；结果未知时不会自动重试。")
        }
        .confirmationDialog(cancelActionIsProduce ? "停止云配音和视频渲染？" : (retryingRunningCancel ? "再次请求停止当前分镜？" : "请求停止当前分镜？"),
                            isPresented: $confirmRunningCancel, titleVisibility: .visible) {
            Button(retryingRunningCancel ? "再次请求停止" : "请求停止", role: .destructive) {
                guard let latest = snapshot.operations.last else { return }
                if retryingRunningCancel { retryRunningCancelRequest(latest) }
                else { requestRunningCancel(latest) }
            }
            .accessibilityIdentifier(cancelActionIsProduce ? "videoConfirmProductionStop" : "videoConfirmRunningCancel")
            Button("返回", role: .cancel) {}
                .accessibilityIdentifier("videoCancelProductionStopConfirmation")
        } message: {
            Text(cancelActionIsProduce
                ? "电脑端会记录并核验本次配音和渲染操作，再停止精确进程树。已发出的云配音请求及供应商费用不能撤回；结果未知时不会重跑。"
                : "只请求电脑停止当前分镜，不能撤回已经发生的费用或写入。重新请求前会先确认原操作仍在运行。")
        }
    }

    @ViewBuilder private var diagnosticsSection: some View {
        Section("电脑环境") {
            if let diagnostic {
                LabeledContent("完整运行环境已核验", value: diagnostic.runtimeVerified ? "是" : "否")
                LabeledContent("完整视频流程已就绪", value: diagnostic.videoReady ? "是" : "否")
                DisclosureGroup("技术依赖详情", isExpanded: $diagnosticsExpanded) {
                    ForEach(["worker_modules", "storyboard_browser", "render_browser", "ffmpeg", "ffprobe", "tts"], id: \.self) { key in
                        if let check = diagnostic.checks[key] {
                            LabeledContent(diagnosticName(key), value: "\(diagnosticStatus(check.status)) · \(diagnosticReason(check.reason))")
                        }
                    }
                }
                Text("完整运行环境尚未核验，不代表不能制作分镜；配音和渲染依赖见下方详情。批准分镜不会启动它们。")
                    .font(.caption).foregroundStyle(.secondary)
            } else {
                Text("尚未读取电脑端视频依赖。")
                    .foregroundStyle(.secondary)
            }
            if !connectionAvailable {
                Text("此任务仍绑定原来的电脑 Agent。请返回任务列表，点“电脑 Agent”检查原配置并尝试重新连接。若网络恢复且连接身份未变，可继续；若原配置已删除或身份已更换，请用新连接另建任务。旧任务只保留历史，PadNote不会自动改绑。已保存的分镜文本可查看；未缓存的图片需连回原 Agent 后加载。")
                    .font(.callout).foregroundStyle(.orange)
                    .accessibilityIdentifier("videoConnectionUnavailable")
                Button("重新检查原连接") { refreshConnectionBinding() }
                    .accessibilityIdentifier("videoRecheckConnection")
            }
            Button("检查电脑环境") { checkDiagnostics() }.disabled(working || !connectionAvailable)
                .accessibilityIdentifier("videoCheckDiagnostics")
#if DEBUG
            if let revoke = AgentVideoUITestHooks.revokeConnection {
                Button("撤销测试连接", role: .destructive) { revoke(); refreshConnectionBinding() }
                    .accessibilityIdentifier("videoUITestRevokeConnection")
            }
            if let fixtureStatus = AgentVideoUITestHooks.statusText?() {
                Text(fixtureStatus).font(.caption.monospaced())
                    .accessibilityIdentifier("videoUITestFixtureStatus")
            }
#endif
        }
    }

    @ViewBuilder private var operationSection: some View {
        Section("视频操作") {
            if !connectionAvailable {
                Text("连接身份不可用，远程操作已暂停。请按上方指引检查原配置；旧任务不会自动转给新连接。")
                    .font(.caption).foregroundStyle(.orange)
            } else if !videoOperationsAvailable {
                Text("此电脑尚未验证视频操作能力。请返回电脑 Agent 刷新连接能力；验证前不会发送视频请求。")
                    .font(.caption).foregroundStyle(.orange)
                    .accessibilityIdentifier("videoOperationsCapabilityUnavailable")
            } else if !videoProductionAvailable {
                Text("此电脑尚未验证配音与视频生成功能。分镜审阅仍可使用，但不会提交配音或渲染请求。")
                    .font(.caption).foregroundStyle(.orange)
                    .accessibilityIdentifier("videoProductionCapabilityUnavailable")
            }
            if let error {
                Text(error).foregroundStyle(.red).textSelection(.enabled)
                    .accessibilityIdentifier("videoOperationError")
            }
            if snapshot.operations.isEmpty {
                Button("初始化视频任务") { start(.initialize) }.disabled(working || !videoOperationsAvailable)
                    .accessibilityIdentifier("videoInitialize")
            }
            if let latest = snapshot.operations.last {
                LabeledContent("最近操作", value: actionName(latest.action))
                LabeledContent("状态", value: statusName(latest))
                    .accessibilityIdentifier("videoOperationStatus")
                if latest.action == .approve, latest.remoteStatus == .succeeded,
                   let approvedRevision = latest.result?.revision {
                    Label("第\(approvedRevision)版分镜已批准；配音和渲染尚未启动。", systemImage: "checkmark.seal.fill")
                        .foregroundStyle(.green)
                        .accessibilityIdentifier("videoApprovalSucceeded")
                }
                if let code = latest.errorCode { Text(errorName(code)).foregroundStyle(.secondary) }
                if latest.submissionState == .prepared ||
                    (latest.submissionState == .uncertain && latest.operationID == nil) {
                    Button("使用同一请求编号重试") { retrySubmission(latest) }.disabled(working || !videoOperationsAvailable)
                        .accessibilityIdentifier("videoRetrySameKey")
                    Text("提交结果不确定时，重试会沿用已保存的幂等编号，不会新建第二个操作。")
                        .font(.caption).foregroundStyle(.secondary)
                } else if latest.operationID != nil,
                          latest.remoteStatus == .queued || latest.remoteStatus == .running || latest.remoteStatus == .unknown {
                    Button("刷新操作状态") { refresh(latest) }.disabled(working)
                        .accessibilityIdentifier("videoRefreshOperation")
                    if latest.remoteStatus == .queued {
                        Button("取消排队中的操作", role: .destructive) { cancel(latest) }.disabled(working || !videoOperationsAvailable)
                            .accessibilityIdentifier("videoCancelQueued")
                    }
                    if latest.remoteStatus == .unknown {
                        Text("结果仍未确认；核对只检查电脑端记录，不会重跑原操作。")
                            .font(.caption).foregroundStyle(.orange)
                        Button("核对电脑端结果") { reconcile(latest) }
                            .disabled(working)
                            .accessibilityIdentifier("videoReconcileOperation")
                    }
                }
                if latest.action == .produce,
                   latest.remoteStatus == .running || latest.remoteStatus == .unknown {
                    if latest.runningCancelIntent == nil {
                        Button("请求停止配音与渲染…") { cancelActionIsProduce = true; retryingRunningCancel = false; confirmRunningCancel = true }
                            .disabled(working || !videoOperationsAvailable)
                            .accessibilityIdentifier("videoRequestProductionCancel")
                    } else if let intent = latest.runningCancelIntent {
                        Text(runningCancelStatusName(intent))
                            .font(.caption).foregroundStyle(intent.status == .verifiedCancelled ? .green : .secondary)
                            .accessibilityIdentifier("videoProductionCancelStatus")
                        if intent.status != .verifiedCancelled && intent.status != .tooLate {
                            Button("查看停止状态") { refreshRunningCancel(latest) }
                                .disabled(working)
                                .accessibilityIdentifier("videoRefreshProductionCancel")
                        }
                    }
                }
                if latest.action == .storyboard, latest.remoteStatus == .running,
                   latest.runningCancelIntent == nil {
                    Button("请求停止分镜…") { retryingRunningCancel = false; confirmRunningCancel = true }
                        .disabled(working || !videoOperationsAvailable)
                        .accessibilityIdentifier("videoRequestRunningCancel")
                }
                if latest.action == .storyboard, let intent = latest.runningCancelIntent {
                    Text(runningCancelStatusName(intent))
                        .font(.caption).foregroundStyle(intent.status == .verifiedCancelled ? .green : .secondary)
                        .accessibilityIdentifier("videoRunningCancelStatus")
                    if intent.delivery == .noRequest, latest.remoteStatus == .running {
                        Button("再次请求停止…") { retryingRunningCancel = true; confirmRunningCancel = true }
                            .disabled(working || !videoOperationsAvailable)
                            .accessibilityIdentifier("videoRetryRunningCancel")
                    } else if intent.delivery != .unsupported,
                              intent.status != .verifiedCancelled, intent.status != .tooLate {
                        Button("查看停止状态") { refreshRunningCancel(latest) }
                            .disabled(working)
                            .accessibilityIdentifier("videoRefreshRunningCancel")
                    }
                }
                if latest.action == .initialize, latest.remoteStatus == .succeeded,
                   latest.result?.status == "initialized" {
                    Button("生成第 1 版分镜…") { confirmStoryboardCost = true }.disabled(working || !videoOperationsAvailable)
                        .accessibilityIdentifier("videoGenerateStoryboard")
                }
                if latest.action == .initialize,
                   latest.remoteStatus == .failed || latest.remoteStatus == .cancelled
                    || latest.submissionState == .rejected {
                    Button("重新初始化视频任务") { start(.initialize) }.disabled(working || !videoOperationsAvailable)
                        .accessibilityIdentifier("videoRetryInitialize")
                }
                if let review, let last = snapshot.operations.last,
                   last.action == .storyboard, last.remoteStatus == .succeeded,
                   review.status == "awaiting_storyboard_review",
                   reviewFresh,
                   review.eventCursor == last.result?.eventCursor,
                   reviewSHAIsCurrent(review), !review.scenes.isEmpty,
                   previewData.count == review.scenes.count {
                    Button("批准第\(review.revision)版分镜…") { confirmApproval = true }
                        .disabled(working || !videoOperationsAvailable)
                        .accessibilityIdentifier("videoApproveRevision")
                }
                if latest.remoteStatus == .failed {
                    Text("操作失败。检查电脑端状态与依赖后，可再次尝试适用的操作。")
                        .font(.caption).foregroundStyle(.secondary)
                }
            }
            if snapshot.operations.last(where: { $0.action == .storyboard })?.remoteStatus == .succeeded,
               review == nil {
                Button("加载当前分镜审阅") { loadReview() }.disabled(working || !videoOperationsAvailable)
                    .accessibilityIdentifier("videoLoadReview")
            }
            if let review, !reviewFresh,
               snapshot.operations.last(where: { $0.action == .storyboard })?.remoteStatus == .succeeded {
                Button("重新加载当前分镜") { loadReview() }
                    .disabled(working || loadingPreviews || !videoOperationsAvailable)
                    .accessibilityIdentifier("videoReloadReview")
                Text("已保存的文字仍可查看；重新连接原电脑后，可再次读取审阅并校验图片。")
                    .font(.caption).foregroundStyle(.secondary)
            }
            if let review, review.scenes.count > 0, reviewFresh,
               review.status == "awaiting_storyboard_review",
               snapshot.operations.last(where: { $0.action == .storyboard })?.remoteStatus == .succeeded {
                Button("刷新分镜审阅") { loadReview() }.disabled(working || loadingPreviews || !videoOperationsAvailable)
                    .accessibilityIdentifier("videoRefreshReview")
                Text("首版审阅后不可修订；如需更换分镜，请从原笔记新建视频任务。")
                    .font(.caption).foregroundStyle(.secondary)
            }
            if let latest = snapshot.operations.last, latest.action == .produce,
               latest.remoteStatus == .succeeded, latest.result?.status == "completed",
               latest.result?.phase == "completed" {
                Label("视频已完成。返回任务详情后可播放或保存已校验的 MP4。", systemImage: "checkmark.seal.fill")
                    .foregroundStyle(.green)
                    .accessibilityIdentifier("videoProductionSucceeded")
            }
            if let latest = snapshot.operations.last,
               latest.action == .storyboard,
               latest.remoteStatus == .cancelled,
               latest.runningCancelIntent?.status == .verifiedCancelled {
                Text("已停止，原材料和历史分镜会保留；当前阶段不能在原任务中恢复或重新生成。请回到原笔记新建视频任务继续。")
                    .font(.caption).foregroundStyle(.secondary)
                    .accessibilityIdentifier("videoStoppedTaskRetained")
            } else if let latest = snapshot.operations.last,
               latest.action == .storyboard,
               (latest.remoteStatus == .failed || latest.submissionState == .rejected || latest.remoteStatus == .cancelled), review == nil {
                Button("重新生成分镜") { start(.storyboard, displayedReview: review) }.disabled(working || !videoOperationsAvailable)
                    .accessibilityIdentifier("videoRetryStoryboard")
            }
            if let approved = snapshot.operations.last(where: { $0.action == .approve && $0.remoteStatus == .succeeded }),
               approved.result?.status == "approved",
               !snapshot.operations.contains(where: { $0.action == .produce && $0.remoteStatus == .succeeded }) {
                Button("生成视频（配音与渲染）…") { confirmVideoProduction = true }
                    .disabled(working || !videoOperationsAvailable || !videoProductionAvailable
                              || snapshot.operations.contains(where: isUnresolved))
                    .accessibilityIdentifier("videoProduce")
            }
        }
    }

    private func reviewSection(_ value: AgentVideoReview) -> some View {
        Section("第\(value.revision)版分镜 · 序号 \(value.eventCursor)") {
            Text(value.episode.title).font(.headline)
            Text("\(value.episode.audience) · \(value.episode.learningGoal)")
                .font(.subheadline).foregroundStyle(.secondary)
            Text("内容摘要：\(value.reviewSHA256.prefix(12))… · \(value.lessonIRSHA256.prefix(12))…")
                .font(.caption.monospaced()).foregroundStyle(.secondary)
            if value.status == "approved" {
                Label("此分镜版本已批准", systemImage: "checkmark.seal.fill")
                    .foregroundStyle(.green).accessibilityIdentifier("videoApprovedReviewStatus")
            }
            ForEach(Array(value.scenes.enumerated()), id: \.element.id) { index, scene in
                VStack(alignment: .leading, spacing: 8) {
                    Text("场景 \(index + 1) · \(scene.visualKind)").font(.headline)
                    Text(scene.learningObjective).font(.subheadline)
                    Text(scene.narration).textSelection(.enabled)
                    ForEach(Array(scene.screenText.enumerated()), id: \.offset) { _, line in
                        Text("• \(line)").font(.callout)
                    }
                    if let bytes = previewData[scene.id], let image = UIImage(data: bytes) {
                        Image(uiImage: image).resizable().scaledToFit().clipShape(RoundedRectangle(cornerRadius: 8))
                            .accessibilityIdentifier("videoPreview-\(scene.id)")
                    } else if loadingPreviews {
                        ProgressView("校验分镜图片…")
                    } else {
                        Label("图片尚未通过校验，不能批准", systemImage: "exclamationmark.triangle")
                            .foregroundStyle(.orange).font(.caption)
                    }
                }
                .padding(.vertical, 6)
            }
        }
    }

    private func load() async {
        do {
            parent = try taskStore.task(id: taskID)
            refreshConnectionBinding()
            snapshot = try videoStore.snapshot(for: taskID)
            if let saved = snapshot.review {
                review = saved
                if connectionAvailable { await downloadPreviews(saved, authoritative: false) }
            }
            if parent?.remoteTaskID != nil, connectionAvailable {
                await refreshMostRecentIfNeeded()
                if let latest = snapshot.operations.last(where: { $0.runningCancelIntent != nil }) {
                    // Reopening is read-only: a saved intent can only be queried here.
                    await perform { _ = try await service.refreshRunningCancelStatus(taskID: taskID, operationID: latest.id); reloadSnapshot() }
                }
                if snapshot.operations.last(where: { $0.action == .storyboard })?.remoteStatus == .succeeded {
                    let last = snapshot.operations.last
                    if !(last?.action == .approve && last?.remoteStatus == .succeeded) {
                        try await fetchCurrentReview()
                    }
                }
            }
        } catch { self.error = error.localizedDescription }
    }

    private func refreshMostRecentIfNeeded() async {
        guard let latest = snapshot.operations.last, latest.operationID != nil,
              latest.remoteStatus == .queued || latest.remoteStatus == .running else { return }
        await perform {
            _ = try await service.refresh(taskID: taskID, operationID: latest.id)
            reloadSnapshot()
        }
    }

    private func checkDiagnostics() { Task { await perform { diagnostic = try await service.diagnostics(taskID: taskID) } } }
    private func loadReview() { Task { await perform { try await fetchCurrentReview() } } }
    private func fetchCurrentReview() async throws {
        let previousReview = review
        let previousPreviews = previewData
        reviewFresh = false
        do {
            let current = try await service.loadReview(taskID: taskID)
            review = current
            await downloadPreviews(current, authoritative: true)
        } catch {
            connectionAvailable = service.connectionIsCurrent(taskID: taskID)
            review = previousReview
            previewData = previousPreviews
            throw error
        }
    }
    private func start(_ action: AgentVideoAction, displayedReview: AgentVideoReview? = nil) {
        Task { await perform {
            if action == .storyboard { reviewFresh = false; previewData = [:] }
            _ = try await service.submit(taskID: taskID, action: action, displayedReview: displayedReview)
            reloadSnapshot()
        } }
    }
    private func approve() {
        guard let review else { return }
        Task { await perform {
            _ = try await service.submit(taskID: taskID, action: .approve,
                displayedReview: review, verifiedPreviewIDs: Set(previewData.keys))
            reloadSnapshot()
        } }
    }
    private func retrySubmission(_ item: AgentVideoOperationRecord) {
        Task { await perform { _ = try await service.retryUncertainSubmission(taskID: taskID, operationID: item.id); reloadSnapshot() } }
    }
    private func refresh(_ item: AgentVideoOperationRecord) {
        Task { await perform {
            _ = try await service.refresh(taskID: taskID, operationID: item.id)
            reloadSnapshot()
            if item.action == .storyboard,
               snapshot.operations.first(where: { $0.id == item.id })?.remoteStatus == .succeeded {
                try await fetchCurrentReview()
            }
        } }
    }
    private func reconcile(_ item: AgentVideoOperationRecord) {
        Task { await perform {
            _ = try await service.reconcile(taskID: taskID, operationID: item.id)
            reloadSnapshot()
            if item.action == .storyboard,
               snapshot.operations.first(where: { $0.id == item.id })?.remoteStatus == .succeeded {
                try await fetchCurrentReview()
            }
        } }
    }
    private func cancel(_ item: AgentVideoOperationRecord) {
        Task { await perform { _ = try await service.cancel(taskID: taskID, operationID: item.id); reloadSnapshot() } }
    }
    private func requestRunningCancel(_ item: AgentVideoOperationRecord) {
        runningCancelTask = Task { await perform {
            if item.action == .produce {
                _ = try await service.requestRunningProduceCancel(taskID: taskID, operationID: item.id)
            } else {
                _ = try await service.requestRunningStoryboardCancel(taskID: taskID, operationID: item.id)
            }
            reloadSnapshot()
        } }
    }
    private func retryRunningCancelRequest(_ item: AgentVideoOperationRecord) {
        runningCancelTask = Task { await perform { _ = try await service.retryRunningStoryboardCancel(taskID: taskID, operationID: item.id); reloadSnapshot() } }
    }
    private func refreshRunningCancel(_ item: AgentVideoOperationRecord) {
        runningCancelTask = Task { await perform { _ = try await service.refreshRunningCancelStatus(taskID: taskID, operationID: item.id); reloadSnapshot() } }
    }

    @MainActor private func perform(_ action: () async throws -> Void) async {
        guard !working else { return }
        guard refreshConnectionBinding() else {
            error = AgentVideoError.connectionChanged.localizedDescription
            return
        }
        working = true
        defer { working = false }
        do { try await action(); error = nil }
        catch {
            guard !Task.isCancelled else { return }
            connectionAvailable = service.connectionIsCurrent(taskID: taskID)
            self.error = error.localizedDescription
            reloadSnapshot()
        }
    }

    @discardableResult @MainActor private func refreshConnectionBinding() -> Bool {
        let current = service.connectionIsCurrent(taskID: taskID)
        connectionAvailable = current
        videoOperationsAvailable = current && service.supportsCapability("video_operations", taskID: taskID)
        videoProductionAvailable = videoOperationsAvailable && service.supportsCapability("video_production", taskID: taskID)
        if !current { confirmApproval = false }
        return current
    }

    private func reloadSnapshot() {
        let displayedReview = review
        do {
            snapshot = try videoStore.snapshot(for: taskID)
            guard let persistedReview = snapshot.review else {
                review = nil; previewData = [:]; reviewFresh = false
                return
            }
            if persistedReview != displayedReview {
                review = persistedReview
                if !sameReviewContent(persistedReview, displayedReview) {
                    previewData = [:]
                    reviewFresh = false
                }
            }
        }
        catch { self.error = error.localizedDescription }
    }

    private func sameReviewContent(_ lhs: AgentVideoReview, _ rhs: AgentVideoReview?) -> Bool {
        guard let rhs else { return false }
        return lhs.taskID == rhs.taskID && lhs.workerTaskID == rhs.workerTaskID &&
            lhs.revision == rhs.revision && lhs.reviewSHA256 == rhs.reviewSHA256 &&
            lhs.lessonIRSHA256 == rhs.lessonIRSHA256 && lhs.scenes == rhs.scenes
    }

    @MainActor private func downloadPreviews(_ value: AgentVideoReview, authoritative: Bool = false) async {
        previewData = [:]
        loadingPreviews = true
        defer { loadingPreviews = false }
        do {
            var total = 0
            var validated: [String: Data] = [:]
            for scene in value.scenes {
                let bytes = try await service.loadPreview(taskID: taskID, item: scene.preview)
                total += bytes.count
                guard total <= 64 * 1024 * 1024 else { throw AgentVideoError.responseTooLarge }
                validated[scene.id] = bytes
            }
            previewData = validated
            reviewFresh = authoritative
            error = nil
        } catch {
            connectionAvailable = service.connectionIsCurrent(taskID: taskID)
            previewData = [:]
            reviewFresh = false
            self.error = error.localizedDescription
        }
    }

    private func reviewSHAIsCurrent(_ value: AgentVideoReview) -> Bool {
        guard let result = snapshot.operations.last?.result else { return false }
        return value.revision == result.revision && value.reviewSHA256 == result.reviewSHA256
            && value.lessonIRSHA256 == result.lessonIRSHA256 && value.eventCursor == result.eventCursor
    }

    private func isUnresolved(_ value: AgentVideoOperationRecord) -> Bool {
        value.submissionState == .prepared || value.submissionState == .uncertain
            || value.remoteStatus == .queued || value.remoteStatus == .running || value.remoteStatus == .unknown
    }

    private func statusName(_ value: AgentVideoOperationRecord) -> String {
        if value.submissionState == .prepared { return "本地已保存，尚待提交" }
        if value.submissionState == .uncertain && value.operationID == nil { return "提交结果不确定" }
        if let status = value.remoteStatus {
            switch status {
            case .queued: return "排队中"
            case .running: return "执行中"
            case .succeeded: return "操作已完成"
            case .failed: return "操作失败"
            case .unknown: return "结果未知，需核对"
            case .cancelled: return "已取消"
            }
        }
        switch value.submissionState {
        case .prepared: return "本地已保存，尚待提交"
        case .uncertain: return "提交结果不确定"
        case .accepted: return "电脑已接收"
        case .rejected: return "电脑拒绝了操作"
        }
    }
    private func runningCancelStatusName(_ intent: AgentVideoRunningCancelIntent) -> String {
        switch intent.delivery {
        case .prepared: return "停止请求已保存在本机，正在提交。"
        case .uncertain: return "停止请求是否送达尚未确认；不会自动重发。"
        case .unsupported: return "这台电脑版本暂不支持停止运行中的分镜。"
        case .noRequest: return "电脑端没有记录到停止请求；操作仍在运行时可明确再次请求。"
        case .accepted:
            switch intent.status {
            case .requested: return "电脑已记录停止请求，是否已停止尚未确认。"
            case .unconfirmed: return "电脑无法确认是否已停止；操作没有重发。"
            case .verifiedCancelled: return "电脑已核实该分镜停止。"
            case .tooLate: return "操作已先结束，请查看结果。"
            case .none: return "电脑端停止状态尚未确认。"
            }
        }
    }
    private func actionName(_ value: AgentVideoAction) -> String {
        switch value { case .initialize: return "初始化"; case .storyboard: return "生成分镜"; case .approve: return "批准分镜"; case .produce: return "生成视频（配音与渲染）" }
    }
    private func diagnosticName(_ value: String) -> String {
        ["worker_modules": "任务模块", "storyboard_browser": "分镜浏览器", "render_browser": "渲染浏览器",
         "ffmpeg": "FFmpeg", "ffprobe": "FFprobe", "tts": "配音依赖"][value] ?? value
    }
    private func diagnosticStatus(_ value: String) -> String {
        switch value {
        case "available": return "可用"
        case "missing": return "缺少"
        case "unchecked": return "未检查"
        case "not_configured": return "未配置"
        default: return "未知"
        }
    }
    private func diagnosticReason(_ value: String) -> String {
        switch value {
        case "modules_resolved": return "依赖可定位"
        case "module_missing": return "依赖未安装"
        case "configuration_unavailable": return "配置未提供"
        case "executable_missing": return "程序文件不存在"
        case "installed_executable_found": return "已找到程序文件"
        case "adapter_not_configured": return "尚未配置配音适配器"
        default: return "详情不可用"
        }
    }
    private func errorName(_ value: String) -> String {
        switch value {
        case "worker_failed": return "电脑端操作失败。请先刷新状态，再检查电脑端提示。"
        case "worker_unknown": return "电脑暂时无法确认操作结果。可显式核对电脑端结果；原操作不会重跑。"
        case "worker_interrupted": return "电脑处理中断，结果尚待核对。"
        case "worker_result_invalid": return "电脑返回的结果无法验证，记录已保留。"
        case "worker_timeout": return "等待电脑处理超时。请先刷新状态核对结果。"
        case "authorization_revoked": return "这台电脑的授权已撤销，请重新连接原配置。"
        case "instance_retired": return "原电脑连接已停用。请返回任务列表，点“电脑 Agent”检查连接；若配置已删除，请用新连接另建任务。"
        case "run_unavailable": return "电脑端找不到此任务，请核对原任务状态。"
        case "source_changed": return "原任务内容已变化，请重新确认任务后再继续。"
        case "binding_changed": return "任务与电脑的绑定已变化，远程操作已暂停。"
        case "cancelled": return "操作已取消。"
        case "submission_uncertain": return "提交结果不确定。重试时会沿用原请求编号。"
        case "request_rejected": return "电脑拒绝了这项操作，请检查任务状态后再决定下一步。"
        default: return "电脑返回了无法识别的操作状态。"
        }
    }
}

#if DEBUG
@MainActor
enum AgentVideoUITestHooks {
    static var revokeConnection: (() -> Void)?
    static var statusText: (() -> String)?
}

private final class NoteVideoRoundTripURLProtocol: URLProtocol {
    static let artifactID = "roundtrip-video"
    fileprivate static let countLock = NSLock()
    fileprivate static var storedPostCount = 0
    @MainActor static var onPost: (() -> Void)?
    static var postCount: Int {
        countLock.lock(); defer { countLock.unlock() }
        return storedPostCount
    }
    override class func canInit(with request: URLRequest) -> Bool {
        request.url?.host == "roundtrip-video.fixture.test"
    }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        guard let url = request.url else { client?.urlProtocol(self, didFailWithError: URLError(.badURL)); return }
        let bytes = AgentVideoUITestHost.videoFixtureBytes()
        let digest = SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined()
        let body: Data
        let contentType: String
        if request.httpMethod == "POST" && url.path.hasSuffix("/runs") {
            Self.countLock.lock()
            Self.storedPostCount += 1
            Self.countLock.unlock()
            Task { @MainActor in Self.onPost?() }
            body = AgentVideoUITestHost.json([
                "task_id": "roundtrip-server-task", "instance_id": "roundtrip-instance",
                "status": "completed", "output": "视频已完成；内容尚未核对。",
                "artifacts": [["id": Self.artifactID, "name": "离线讲解.mp4", "media_type": "video/mp4",
                    "size_bytes": bytes.count, "sha256": digest]]
            ])
            contentType = "application/json"
        } else if request.httpMethod == "GET" && url.path.hasSuffix("/runs/roundtrip-server-task/artifacts/\(Self.artifactID)") {
            body = bytes; contentType = "video/mp4"
        } else {
            client?.urlProtocol(self, didFailWithError: URLError(.unsupportedURL)); return
        }
        guard let response = HTTPURLResponse(url: url, statusCode: 200, httpVersion: "HTTP/1.1", headerFields: ["Content-Type": contentType]) else {
            client?.urlProtocol(self, didFailWithError: URLError(.badServerResponse)); return
        }
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: body)
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}

struct AgentVideoRoundTripUITestHost: View {
    @EnvironmentObject private var library: NoteLibrary
    @State private var postVersion = 0
    private let note: NoteDocument
    private let connectionStore: AgentConnectionStore
    private let taskStore: AgentTaskStore
    private let taskService: AgentTaskService
    private let fixtureID: String

    init() {
        fixtureID = ProcessInfo.processInfo.arguments.firstIndex(of: "--video-roundtrip-fixture-id")
            .flatMap { ProcessInfo.processInfo.arguments.indices.contains($0 + 1) ? ProcessInfo.processInfo.arguments[$0 + 1] : nil }
            ?? UUID().uuidString.lowercased()
        note = NoteDocument(id: "roundtrip-note-\(fixtureID)", title: "视频回链测试笔记",
            updatedAt: Date().timeIntervalSince1970 * 1000)
        let connectionDefaults = UserDefaults(suiteName: "PadNote.NoteVideoRoundTrip.\(fixtureID)")!
        let tokens = NoteVideoRoundTripTokens()
        let connections = AgentConnectionStore(defaults: connectionDefaults, keychain: tokens)
        let profile = try! connections.create(name: "本机合成测试电脑", kind: .builtinVideo,
            endpoint: "https://roundtrip-video.fixture.test", token: "synthetic-only",
            transport: .bridge, bridgeID: "roundtrip-bridge", instanceID: "roundtrip-instance",
            certSHA256: String(repeating: "b", count: 64))
        _ = connections.applyProbeSuccess(id: profile.id, revision: profile.revision,
            capabilities: ["task_bundle": true, "artifacts": true, "video_task_submission": true,
                "run_status": true, "video_operations": true, "video_production": true, "run_submission": false])
        connectionStore = connections
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("note-video-roundtrip-\(fixtureID)", isDirectory: true)
        taskStore = AgentTaskStore(fileURL: root.appendingPathComponent("tasks", isDirectory: true))
        NoteVideoRoundTripURLProtocol.countLock.lock()
        NoteVideoRoundTripURLProtocol.storedPostCount = 0
        NoteVideoRoundTripURLProtocol.countLock.unlock()
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [NoteVideoRoundTripURLProtocol.self]
        let client = AgentTaskClient(session: URLSession(configuration: configuration))
        taskService = AgentTaskService(connectionStore: connections, taskStore: taskStore, client: client)

        let staleSnapshot = VaultNote(id: note.id, title: note.title,
            markdown: "# 已整理材料预览\n\n仅用于离线用户界面验证的合成内容。",
            sourceUpdatedAt: note.updatedAt - 5_000, createdAt: Date())
        // Seed the Codable vault snapshot directly so constructing a DEBUG fixture doesn't
        // publish ObservableObject changes while SwiftUI is building its initial view.
        let vaultDirectory = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("PadNoteUITestVault", isDirectory: true)
        let encodedID = Data(note.id.utf8).base64EncodedString().replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "+", with: "-")
        if let bytes = try? JSONEncoder().encode(staleSnapshot) {
            try? FileManager.default.createDirectory(at: vaultDirectory, withIntermediateDirectories: true)
            try? bytes.write(to: vaultDirectory.appendingPathComponent("\(encodedID).json"), options: .atomic)
        }
    }

    var body: some View {
        NoteEditorView(note: note, videoConnectionStore: connectionStore,
            videoTaskStore: taskStore, videoTaskService: taskService)
            .onAppear {
                NoteVideoRoundTripURLProtocol.onPost = { postVersion += 1 }
            }
            .onDisappear { NoteVideoRoundTripURLProtocol.onPost = nil }
            .overlay(alignment: .bottom) {
                Text("fixture posts=\(NoteVideoRoundTripURLProtocol.postCount) · v\(postVersion)")
                    .font(.caption2).accessibilityIdentifier("noteVideoRoundTripPostCount")
                    .allowsHitTesting(false)
            }
    }
}

private final class NoteVideoRoundTripTokens: AgentTokenStore {
    private var values: [String: String] = [:]
    func save(_ value: String, reference: String) throws { values[reference] = value }
    func read(reference: String) throws -> String? { values[reference] }
    func delete(reference: String) { values.removeValue(forKey: reference) }
}

struct AgentVideoUITestHost: View {
    private let taskID: UUID
    private let connectionsForService: AgentConnectionStore
    private let tasks: AgentTaskStore
    private let videoStore: AgentVideoOperationStore
    private let service: AgentVideoOperationService
    private let uiSession: URLSession

    init() {
        let arguments = ProcessInfo.processInfo.arguments
        func argument(_ name: String, fallback: String) -> String {
            guard let index = arguments.firstIndex(of: name), arguments.indices.contains(index + 1) else { return fallback }
            return arguments[index + 1]
        }
        let fixtureID = argument("--video-fixture-id", fallback: UUID().uuidString.lowercased())
        let mode = argument("--video-fixture-mode", fallback: "happy")
        let defaults = UserDefaults(suiteName: "PadNote.VideoOperation.UITest.\(fixtureID)")!
        let connections = AgentConnectionStore(defaults: defaults, keychain: VideoUITestTokens(defaults: defaults))
        connectionsForService = connections
        let profile: AgentConnectionProfile
        if let existing = connections.profiles().first {
            profile = existing
        } else {
            profile = try! connections.create(name: "UI 内置视频", kind: .builtinVideo, endpoint: "https://video-ui.test",
                token: "ui-token", transport: .bridge, bridgeID: "bridge-ui", instanceID: "instance-ui",
                certSHA256: String(repeating: "a", count: 64))
        }
        _ = connections.applyProbeSuccess(id: profile.id, revision: profile.revision,
            capabilities: ["task_bundle": true, "artifacts": true, "video_task_submission": true, "video_operations": true,
                           "video_production": mode != "no-video-production", "run_status": true, "run_submission": false])
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("padnote-video-ui-\(fixtureID)", isDirectory: true)
        tasks = AgentTaskStore(fileURL: root.appendingPathComponent("tasks", isDirectory: true))
        videoStore = AgentVideoOperationStore(directory: root.appendingPathComponent("video", isDirectory: true))
        let task: AgentTaskRecord
        if let existing = try? tasks.tasks().first(where: { $0.remoteTaskID == "server-video-task" }) {
            task = existing
        } else {
            let bundle = try! VideoTaskBundleIO.make(noteId: "ui-note", noteRevision: 1, title: "UI 微积分",
                markdown: "# 导数\n\n变化率", audience: "学生", learningGoal: "理解变化率", durationSeconds: 60)
            let payload = try! AgentTaskPayload(title: "UI 视频任务", input: "生成讲解视频",
                source: AgentTaskSource(noteID: "ui-note", noteRevision: 1), bundle: bundle)
            task = try! tasks.create(profile: profile, payload: payload)
        }
        let workerTaskID = Self.workerID(from: task.payload)
        taskID = task.id
        if task.remoteTaskID == nil {
            _ = try! tasks.mutate(id: task.id, expectedRevision: task.recordRevision) {
                $0.remoteTaskID = "server-video-task"
                $0.status = .completed
            }
        }
        let revokeFixtureConnection = ["revoked", "revoked-queued", "revoked-uncertain", "reconcile-revoked",
            "running-cancel-revoked", "running-cancel-pending"].contains(mode)
        AgentVideoUITestHooks.revokeConnection = revokeFixtureConnection ? { connections.delete(id: profile.id) } : nil
        AgentVideoUITestHooks.statusText = {
            "fixture posts=\(defaults.integer(forKey: "post-count")) executions=\(defaults.integer(forKey: "execution-count")) gets=\(defaults.integer(forKey: "get-count")) reconciles=\(defaults.integer(forKey: "reconcile-count")) reviews=\(defaults.integer(forKey: "review-count")) previews=\(defaults.integer(forKey: "preview-count")) cancels=\(defaults.integer(forKey: "cancel-count")) attempts=\(defaults.integer(forKey: "cancel-attempts")) cancelGets=\(defaults.integer(forKey: "cancel-status-gets"))"
        }
        VideoUITestProtocol.handler = { request in
            let path = request.url?.path ?? ""
            let method = request.httpMethod ?? "GET"
            let png = Self.previewPNG()
            let pngHash = SHA256.hash(data: png).map { String(format: "%02x", $0) }.joined()
            let previewID = String(repeating: "a", count: 64)
            if path.hasSuffix("/video/diagnostics") {
            let checks: [String: [String: String]] = [
                    "worker_modules": ["status": "available", "reason": "modules_resolved"],
                    "storyboard_browser": ["status": "available", "reason": "installed_executable_found"],
                    "render_browser": ["status": "available", "reason": "installed_executable_found"],
                    "ffmpeg": ["status": "available", "reason": "installed_executable_found"],
                    "ffprobe": ["status": "available", "reason": "installed_executable_found"],
                    "tts": ["status": "not_configured", "reason": "adapter_not_configured"]
                ]
                return (200, ["Content-Type": "application/json"], Self.json(["schema_version": "1.0", "runtime_verified": false, "video_ready": false, "checks": checks]))
            }
            if path.hasSuffix("/video/review") {
                let reviewCount = defaults.integer(forKey: "review-count") + 1
                defaults.set(reviewCount, forKey: "review-count")
                if mode == "review-refresh-failure" && reviewCount > 1 {
                    return (503, ["Content-Type": "application/json"], Self.json(["error": "temporary review read failure"]))
                }
                let review: [String: Any] = ["object": "padnote.video.review", "protocol_version": 1,
                    "status": "awaiting_storyboard_review", "task_id": "server-video-task", "worker_task_id": workerTaskID,
                    "event_cursor": 3, "revision": 1, "review_sha256": String(repeating: "c", count: 64),
                    "lesson_ir_sha256": String(repeating: "d", count: 64),
                    "episode": ["title": "导数是什么", "audience": "学生", "learning_goal": "理解变化率", "language": "zh-CN"],
                    "scenes": [["id": "scene-1", "learning_objective": "理解瞬时变化率", "narration": "导数描述函数在一点附近的变化快慢。", "screen_text": ["瞬时变化率"], "visual_kind": "title", "preview": ["id": previewID, "media_type": "image/png", "size_bytes": png.count, "sha256": pngHash, "width": 2, "height": 2]]]]
                return (200, ["Content-Type": "application/json"], Self.json(review))
            }
            if path.contains("/video/previews/") {
                defaults.set(defaults.integer(forKey: "preview-count") + 1, forKey: "preview-count")
                return (200, ["Content-Type": "image/png"], png)
            }
            if method == "GET", path.hasSuffix("/runs/server-video-task") {
                let bytes = Self.videoFixtureBytes()
                let digest = SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined()
                let artifacts: [[String: Any]] = defaults.string(forKey: "accepted-action") == "produce" ? [[
                    "id": "video-output", "name": "lesson.mp4", "media_type": "video/mp4",
                    "size_bytes": bytes.count, "sha256": digest
                ]] : []
                return (200, ["Content-Type": "application/json"], Self.json([
                    "task_id": "server-video-task", "instance_id": "instance-ui", "status": "completed",
                    "output": "", "artifacts": artifacts, "followup_available": false,
                    "followup_reason": "视频工作流不支持通用追问", "conversation_id": "server-video-task", "parent_task_id": ""
                ]))
            }
            if method == "GET", path.hasSuffix("/runs/server-video-task/artifacts/video-output") {
                return (200, ["Content-Type": "video/mp4"], Self.videoFixtureBytes())
            }
            if path.hasSuffix("/video/operations") {
                guard let bodyData = Self.bodyData(request),
                      let body = (try? JSONSerialization.jsonObject(with: bodyData)) as? [String: Any],
                      let action = body["action"] as? String,
                      let params = body["parameters"] as? [String: Any],
                      ["initialize", "storyboard", "approve", "produce"].contains(action),
                      let idempotencyKey = request.value(forHTTPHeaderField: "Idempotency-Key"),
                      UUID(uuidString: idempotencyKey) != nil else {
                    return (400, ["Content-Type": "application/json"], Self.json(["error": "invalid fixture request"]))
                }
                let parametersAreValid: Bool
                switch action {
                case "initialize": parametersAreValid = params.isEmpty
                case "storyboard": parametersAreValid = Set(params.keys) == ["revision", "event_cursor"] &&
                    (params["revision"] as? Int) == 1 && (params["event_cursor"] as? Int) == 1
                case "approve": parametersAreValid = Set(params.keys) == ["revision", "event_cursor", "review_sha256", "lesson_ir_sha256"] &&
                    (params["revision"] as? Int) == 1 && (params["event_cursor"] as? Int) == 3 &&
                    (params["review_sha256"] as? String) == String(repeating: "c", count: 64) &&
                    (params["lesson_ir_sha256"] as? String) == String(repeating: "d", count: 64)
                case "produce": parametersAreValid = Set(params.keys) == ["revision", "event_cursor", "review_sha256", "lesson_ir_sha256", "allow_cloud_tts"] &&
                    (params["revision"] as? Int) == 1 && (params["event_cursor"] as? Int) == 4 &&
                    (params["review_sha256"] as? String) == String(repeating: "c", count: 64) &&
                    (params["lesson_ir_sha256"] as? String) == String(repeating: "d", count: 64) &&
                    (params["allow_cloud_tts"] as? Bool) == true
                default: parametersAreValid = false
                }
                guard parametersAreValid else {
                    return (400, ["Content-Type": "application/json"], Self.json(["error": "fixture rejected wrong action binding"]))
                }
                let requestFingerprint = bodyData.base64EncodedString()
                if ["uncertain", "unknown", "revoked-uncertain", "reconcile-success",
                    "reconcile-unconfirmed", "reconcile-error", "reconcile-revoked"].contains(mode) {
                    if let acceptedKey = defaults.string(forKey: "accepted-key") {
                        guard acceptedKey == idempotencyKey,
                              defaults.string(forKey: "accepted-body") == requestFingerprint else {
                            return (409, ["Content-Type": "application/json"], Self.json(["error": "idempotency mismatch"]))
                        }
                    } else {
                        defaults.set(idempotencyKey, forKey: "accepted-key")
                        defaults.set(requestFingerprint, forKey: "accepted-body")
                        defaults.set(1, forKey: "execution-count")
                        defaults.synchronize()
                    }
                    defaults.set(defaults.integer(forKey: "post-count") + 1, forKey: "post-count")
                    defaults.set(action, forKey: "accepted-action")
                } else {
                    defaults.set(defaults.integer(forKey: "post-count") + 1, forKey: "post-count")
                    defaults.set(defaults.integer(forKey: "execution-count") + 1, forKey: "execution-count")
                    defaults.set(action, forKey: "accepted-action")
                }
                let operationID: String
                let status: String, phase: String, cursor: Int, revision: Int?
                switch action {
                case "storyboard": operationID = "00000000-0000-4000-8000-000000000002"; status = "awaiting_storyboard_review"; phase = "awaiting_approval"; cursor = 3; revision = 1
                case "approve": operationID = "00000000-0000-4000-8000-000000000003"; status = "approved"; phase = "approval_pending"; cursor = 4; revision = params["revision"] as? Int ?? 1
                case "produce": operationID = "00000000-0000-4000-8000-000000000004"; status = "completed"; phase = "completed"; cursor = 10; revision = params["revision"] as? Int ?? 1
                default: operationID = "00000000-0000-4000-8000-000000000001"; status = "initialized"; phase = "idle"; cursor = 1; revision = nil
                }
                if action == "initialize" && ["queued-cancel", "revoked-queued", "running-cancel"].contains(mode) {
                    let queued = mode != "running-cancel"
                    defaults.set(idempotencyKey, forKey: "accepted-key")
                    defaults.set(action, forKey: "accepted-action")
                    let pending: [String: Any] = ["object": "padnote.video.operation", "protocol_version": 1,
                        "operation_id": operationID, "task_id": "server-video-task",
                        "client_operation_id": idempotencyKey, "action": action,
                        "status": queued ? "queued" : "running", "created_at": 1, "updated_at": 1,
                        "result": NSNull(), "error": NSNull()]
                    return (202, ["Content-Type": "application/json"], Self.json(pending))
                }
                if action == "produce", mode == "produce-running-cancel" {
                    defaults.set(idempotencyKey, forKey: "running-produce-key")
                    let running: [String: Any] = ["object": "padnote.video.operation", "protocol_version": 1,
                        "operation_id": operationID, "task_id": "server-video-task",
                        "client_operation_id": idempotencyKey, "action": "produce", "status": "running",
                        "created_at": 1, "updated_at": 2, "result": NSNull(), "error": NSNull()]
                    return (202, ["Content-Type": "application/json"], Self.json(running))
                }
                if action == "storyboard", ["running-cancel-confirmed", "running-cancel-too-late",
                    "running-cancel-retry", "running-cancel-revoked", "running-cancel-pending"].contains(mode) {
                    defaults.set(idempotencyKey, forKey: "running-storyboard-key")
                    let running: [String: Any] = ["object": "padnote.video.operation", "protocol_version": 1,
                        "operation_id": "00000000-0000-4000-8000-000000000002", "task_id": "server-video-task",
                        "client_operation_id": idempotencyKey, "action": "storyboard", "status": "running",
                        "created_at": 1, "updated_at": 1, "result": NSNull(), "error": NSNull()]
                    return (202, ["Content-Type": "application/json"], Self.json(running))
                }
                var result: [String: Any] = ["protocol_version": 1, "task_id": "server-video-task", "status": status, "phase": phase, "event_cursor": cursor]
                if let revision { result["revision"] = revision }
                if action != "initialize" { result["review_sha256"] = String(repeating: "c", count: 64); result["lesson_ir_sha256"] = String(repeating: "d", count: 64) }
                if action == "produce" {
                    result["approval"] = ["approval_id": "33333333-3333-4333-8333-333333333333", "revision": revision ?? 1,
                        "review_sha256": params["review_sha256"] as? String ?? String(repeating: "c", count: 64),
                        "lesson_ir_sha256": params["lesson_ir_sha256"] as? String ?? String(repeating: "d", count: 64),
                        "granted_at": "2026-09-30T12:00:00.000Z", "consumed_at": "2026-09-30T12:00:01.000Z"]
                    result["receipt"] = ["operation_id": operationID, "attempt_id": "22222222-2222-4222-8222-222222222222",
                        "action": "produce", "payload_digest": String(repeating: "1", count: 64),
                        "source_snapshot_digest": String(repeating: "2", count: 64),
                        "request_sha256": String(repeating: "3", count: 64),
                        "input_event_cursor": params["event_cursor"] as? Int ?? 4, "result_event_cursor": cursor, "revision": revision ?? 1,
                        "review_sha256": params["review_sha256"] as? String ?? String(repeating: "c", count: 64),
                        "lesson_ir_sha256": params["lesson_ir_sha256"] as? String ?? String(repeating: "d", count: 64), "allow_cloud_tts": true]
                }
                if ["unknown", "reconcile-success", "reconcile-unconfirmed", "reconcile-error", "reconcile-revoked"].contains(mode) || (mode == "produce-unknown" && action == "produce") {
                    let unknown: [String: Any] = ["object": "padnote.video.operation", "protocol_version": 1,
                        "operation_id": operationID, "task_id": "server-video-task",
                        "client_operation_id": idempotencyKey, "action": action,
                        "status": "unknown", "created_at": 1, "updated_at": 1,
                        "result": NSNull(), "error": "worker_unknown"]
                    return (202, ["Content-Type": "application/json"], Self.json(unknown))
                }
                let root: [String: Any] = ["object": "padnote.video.operation", "protocol_version": 1,
                    "operation_id": operationID, "task_id": "server-video-task",
                    "client_operation_id": idempotencyKey,
                    "action": action, "status": "succeeded", "created_at": 1, "updated_at": 1,
                    "result": result, "error": NSNull()]
                return (202, ["Content-Type": "application/json"], Self.json(root))
            }
            if method == "POST", path.contains("/video/operations/"), path.hasSuffix("/reconcile") {
                defaults.set(defaults.integer(forKey: "reconcile-count") + 1, forKey: "reconcile-count")
                let requestObject: [String: Any]?
                if let bytes = Self.bodyData(request),
                   let decoded = try? JSONSerialization.jsonObject(with: bytes) as? [String: Any] {
                    requestObject = decoded
                } else {
                    requestObject = nil
                }
                guard ["reconcile-success", "reconcile-unconfirmed", "reconcile-error", "reconcile-revoked"].contains(mode),
                      path.hasSuffix("/video/operations/00000000-0000-4000-8000-000000000001/reconcile"),
                      requestObject?.isEmpty == true,
                      request.value(forHTTPHeaderField: "Authorization") == "Bearer ui-token",
                      let key = defaults.string(forKey: "accepted-key"),
                      defaults.string(forKey: "accepted-action") == "initialize" else {
                    return (409, ["Content-Type": "application/json"], Self.json(["error": "invalid reconciliation request"]))
                }
                if mode == "reconcile-error" {
                    return (503, ["Content-Type": "application/json"], Self.json(["error": "temporary reconciliation failure"]))
                }
                let confirmed = mode == "reconcile-success"
                let value: [String: Any] = ["object": "padnote.video.operation", "protocol_version": 1,
                    "operation_id": "00000000-0000-4000-8000-000000000001", "task_id": "server-video-task",
                    "client_operation_id": key, "action": "initialize",
                    "status": confirmed ? "succeeded" : "unknown", "created_at": 1, "updated_at": 2,
                    "result": confirmed ? ["task_id": "server-video-task", "status": "initialized",
                        "phase": "idle", "event_cursor": 1] as Any : NSNull(),
                    "error": confirmed ? NSNull() : "worker_unknown" as Any]
                return (200, ["Content-Type": "application/json"], Self.json(value))
            }
            if method == "POST", path.contains("/video/operations/"), path.hasSuffix("/cancel"), mode == "queued-cancel" {
                defaults.set(defaults.integer(forKey: "cancel-attempts") + 1, forKey: "cancel-attempts")
                let requestObject: [String: Any]?
                if let bytes = Self.bodyData(request),
                   let decoded = try? JSONSerialization.jsonObject(with: bytes) as? [String: Any] {
                    requestObject = decoded
                } else {
                    requestObject = nil
                }
                guard mode == "queued-cancel",
                      path.hasSuffix("/video/operations/00000000-0000-4000-8000-000000000001/cancel"),
                      requestObject?.isEmpty == true,
                      request.value(forHTTPHeaderField: "Authorization") == "Bearer ui-token",
                      let key = defaults.string(forKey: "accepted-key"),
                      defaults.string(forKey: "accepted-action") == "initialize" else {
                    let conflict: [String: Any] = ["error": "operation is not queued"]
                    return (409, ["Content-Type": "application/json"], Self.json(conflict))
                }
                defaults.set(1, forKey: "cancel-count")
                let cancelled: [String: Any] = ["object": "padnote.video.operation", "protocol_version": 1,
                    "operation_id": "00000000-0000-4000-8000-000000000001", "task_id": "server-video-task",
                    "client_operation_id": key, "action": "initialize", "status": "cancelled",
                    "created_at": 1, "updated_at": 2, "result": NSNull(), "error": "cancelled"]
                return (200, ["Content-Type": "application/json"], Self.json(cancelled))
            }
            if path.contains("/video/operations/00000000-0000-4000-8000-000000000004/cancel") {
                guard mode == "produce-running-cancel", request.value(forHTTPHeaderField: "Authorization") == "Bearer ui-token",
                      defaults.string(forKey: "running-produce-key") != nil else {
                    return (404, ["Content-Type": "application/json"], Self.json(["error": "not found"]))
                }
                if method == "GET" {
                    defaults.set(defaults.integer(forKey: "cancel-status-gets") + 1, forKey: "cancel-status-gets")
                } else {
                    guard method == "POST", let bytes = Self.bodyData(request),
                          let body = try? JSONSerialization.jsonObject(with: bytes) as? [String: Any], body.isEmpty else {
                        return (400, ["Content-Type": "application/json"], Self.json(["error": "expected empty object"]))
                    }
                    defaults.set(defaults.integer(forKey: "cancel-attempts") + 1, forKey: "cancel-attempts")
                    defaults.set(defaults.integer(forKey: "cancel-count") + 1, forKey: "cancel-count")
                }
                return (200, ["Content-Type": "application/json"], Self.json([
                    "object": "padnote.video.cancel_request", "protocol_version": 1,
                    "operation_id": "00000000-0000-4000-8000-000000000004", "task_id": "server-video-task",
                    "status": "requested", "requested_at": 20, "updated_at": 20]))
            }
            if method == "GET", path.hasSuffix("/video/operations/00000000-0000-4000-8000-000000000004"), mode == "produce-running-cancel" {
                defaults.set(defaults.integer(forKey: "get-count") + 1, forKey: "get-count")
                let key = defaults.string(forKey: "running-produce-key") ?? ""
                return (200, ["Content-Type": "application/json"], Self.json([
                    "object": "padnote.video.operation", "protocol_version": 1,
                    "operation_id": "00000000-0000-4000-8000-000000000004", "task_id": "server-video-task",
                    "client_operation_id": key, "action": "produce", "status": "running",
                    "created_at": 1, "updated_at": 2, "result": NSNull(), "error": NSNull()]))
            }
            if path.contains("/video/operations/00000000-0000-4000-8000-000000000002/cancel-running") {
                guard ["running-cancel-confirmed", "running-cancel-too-late", "running-cancel-retry",
                       "running-cancel-revoked", "running-cancel-pending"].contains(mode),
                      request.value(forHTTPHeaderField: "Authorization") == "Bearer ui-token",
                      defaults.string(forKey: "running-storyboard-key") != nil else {
                    return (404, ["Content-Type": "application/json"], Self.json(["error": "not found"]))
                }
                if method == "GET" {
                    defaults.set(defaults.integer(forKey: "cancel-status-gets") + 1, forKey: "cancel-status-gets")
                    if mode == "running-cancel-retry" || defaults.integer(forKey: "cancel-count") == 0 {
                        return (409, ["Content-Type": "application/json"], Self.json(["error": "no cancellation request recorded"]))
                    }
                    let state: String = mode == "running-cancel-too-late" ? "too_late" : "requested"
                    return (200, ["Content-Type": "application/json"], Self.json([
                        "object": "padnote.video.cancel_request", "protocol_version": 1,
                        "operation_id": "00000000-0000-4000-8000-000000000002", "task_id": "server-video-task",
                        "status": state, "requested_at": 10, "updated_at": 11]))
                }
                guard method == "POST", let bytes = Self.bodyData(request),
                      let body = try? JSONSerialization.jsonObject(with: bytes) as? [String: Any], body.isEmpty else {
                    return (400, ["Content-Type": "application/json"], Self.json(["error": "expected empty object"]))
                }
                defaults.set(defaults.integer(forKey: "cancel-attempts") + 1, forKey: "cancel-attempts")
                let count = defaults.integer(forKey: "cancel-attempts")
                defaults.set(count, forKey: "cancel-count")
                if mode == "running-cancel-retry", count == 1 {
                    return (409, ["Content-Type": "application/json"], Self.json(["error": "no cancellation request recorded"]))
                }
                let state = mode == "running-cancel-too-late" ? "too_late" : "verified_cancelled"
                let finalState = mode == "running-cancel-pending" ? "requested" : state
                return (200, ["Content-Type": "application/json"], Self.json([
                    "object": "padnote.video.cancel_request", "protocol_version": 1,
                    "operation_id": "00000000-0000-4000-8000-000000000002", "task_id": "server-video-task",
                    "status": finalState, "requested_at": 10, "updated_at": 11]))
            }
            if method == "GET", path.hasSuffix("/video/operations/00000000-0000-4000-8000-000000000002"),
               ["running-cancel-confirmed", "running-cancel-too-late", "running-cancel-retry",
                "running-cancel-revoked", "running-cancel-pending"].contains(mode) {
                defaults.set(defaults.integer(forKey: "get-count") + 1, forKey: "get-count")
                let key = defaults.string(forKey: "running-storyboard-key") ?? ""
                let status = defaults.integer(forKey: "cancel-count") > 1 && mode == "running-cancel-retry" ? "running" : "running"
                return (200, ["Content-Type": "application/json"], Self.json([
                    "object": "padnote.video.operation", "protocol_version": 1,
                    "operation_id": "00000000-0000-4000-8000-000000000002", "task_id": "server-video-task",
                    "client_operation_id": key, "action": "storyboard", "status": status,
                    "created_at": 1, "updated_at": 2, "result": NSNull(), "error": NSNull()]))
            }
            if method == "GET", path.contains("/video/operations/") {
                guard mode == "unknown", let key = defaults.string(forKey: "accepted-key"),
                      path.hasSuffix("/00000000-0000-4000-8000-000000000001") else {
                    return (404, ["Content-Type": "application/json"], Self.json(["error": "not found"]))
                }
                defaults.set(defaults.integer(forKey: "get-count") + 1, forKey: "get-count")
                let unknown: [String: Any] = ["object": "padnote.video.operation", "protocol_version": 1,
                    "operation_id": "00000000-0000-4000-8000-000000000001", "task_id": "server-video-task",
                    "client_operation_id": key, "action": "initialize", "status": "unknown",
                    "created_at": 1, "updated_at": 1, "result": NSNull(), "error": "worker_unknown"]
                return (200, ["Content-Type": "application/json"], Self.json(unknown))
            }
            return (404, ["Content-Type": "application/json"], Self.json(["error": "not found"]))
        }
        VideoUITestProtocol.dropResponse = { request in
            guard ["uncertain", "revoked-uncertain"].contains(mode), request.httpMethod == "POST",
                  request.url?.path.hasSuffix("/video/operations") == true,
                  !defaults.bool(forKey: "response-dropped") else { return false }
            defaults.set(true, forKey: "response-dropped")
            defaults.synchronize()
            return true
        }
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [VideoUITestProtocol.self]
        let session = URLSession(configuration: configuration)
        uiSession = session
        service = AgentVideoOperationService(connections: connections, tasks: tasks, store: videoStore,
            client: AgentVideoOperationClient(session: session))
    }

    var body: some View {
        AgentTaskDetailView(taskID: taskID, store: tasks,
            service: AgentTaskService(connectionStore: connectionsForService, taskStore: tasks, client: AgentTaskClient(session: uiSession)),
            videoOperationStore: videoStore, videoOperationService: service)
    }

    static func videoFixtureBytes() -> Data { Data(base64Encoded: "AAAAHGZ0eXBtcDQyAAAAAWlzb21tcDQxbXA0MgAAAAFtZGF0AAAAAAAAA/4AAAA6BgUyR1ZK3FxMQz+U78URPNFDqAEAAAMAAQMAAAMAAQIAAHUwCwAAAwAAAwAABnIMA4koAQ3/////gAAAAIYluCADP1Qyv915Ck1E2gt0zTP79hYGGyIapplsAgR2oImLefQyzAYAXFybjhz+YQOcBNBWLSpOvbPUwYJ3rjAMeFY5DYHmSX5uKbMPsy9toZiKIZbd5Ca785HPwTlXzF595E++A+LGbxUplPn2vHrS/sbMEq1690C8k674OcFioIgLFZnRGwAAAEwh4RBFf9Rk3xKFt6ywZ6mvZv9DuGlzVHFTPS15HlZBlYGZqzCH2EwfZJgF7wxUVADgeQ5osMxZH6g7VIC+KHE9IuNgK0rAYILZVSGAAAAANyGogoRHwB3lah/KAFng65+rvAF4qcbRX41Qxh/JOM/L+hpvOZULVauI8cc1d9NZbbNwMAQbtNQAAAA/AajBiM/H8S8sLSWPwvK3Y9VYzJ3mZ3PcmUz5JQMm5b/YrlQxPapr9XEfVvmwaHJFSnc2+gSWKQeVDMxTMuScAAAANgGow4ivuOZ3uvVtFlj+xZPhUDiOh3gICyiYARRiz0mgP1WZFlk9rhR08LcSfx/Sks2zPliHNAAAAHsh4xmiJP/ESXeEoVIfDsVL/DABIBKAdQohwsyvX/f10cUtaOa6AKkRntZOKAEQZFynu0x+lLLj8eE4KEBVVtY1vePNG0cX/aQ2i55j4I4AQFy1yr143LHuX0eAtrg/3gTlJGi2w64DIAFRxg4ce6PAIJKGTVlb03+y7IAAAAA6AakFiI/G+7PaLPhJXdBYBzmVm2OBKwC2hbmHqEHS4Hhu2JH2QNv/iihK4FE1TT+Y4V3YMyNXTKwNwAAAADgh5CBXvMDwShBwbsWHpeinPyU4L/4bjD7LKc6L8oBEwpVnZsXSLbOWUcBF4hJ43Mj712ZVoQpNgAAAACYBqUeIr8/3A+9QOClkRV6eH/m/Mu/w7WaNZVSwrcJlKo/xKABfmAAAABoGBRVHVkrcXExDP5TvxRE80UOoAwAAAwABgAAAANkluBAD/2f2jShY3JDeoVqSlxYqdG10AyGKVSl0F665LLbwk2mRU7gl2uT+kbza9jO536cosvn4mTK181SR2PX2ry8cQwbqyYbnKtJq/X2IumSy5p3yu4awkyoVNIybssxRdlPI5isl5q3JZ5M3ls07GIL8CzGnrlN1L8pDsssCfw4XEemiYR5K/PCbD/hCT+1olsITPy6BlXPh4gACvVVa9tJq3G4wqHL4lveUN7EmjRdbBRMcZKhp39J8Jmg38GFACFKgcFNRPAAAUbLTMvZuULDAqJlvMU2mAAADUG1vb3YAAABsbXZoZAAAAADm4r/15uK/9QAAAlgAAAJYAAEAAAEAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAAAAAQAAAAAAAAAAAAAAAAAAQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAIAAALcdHJhawAAAFx0a2hkAAAAAebiv/Xm4r/1AAAAAQAAAAAAAAJYAAAAAAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAAAAAQAAAAAAAAAAAAAAAAAAQAAAAABAAAAAQAAAAAAAJGVkdHMAAAAcZWxzdAAAAAAAAAABAAACWAAAAHgAAQAAAAACVG1kaWEAAAAgbWRoZAAAAADm4r/15uK/9QAAAlgAAAJYVcQAAAAAADFoZGxyAAAAAAAAAAB2aWRlAAAAAAAAAAAAAAAAQ29yZSBNZWRpYSBWaWRlbwAAAAH7bWluZgAAABR2bWhkAAAAAQAAAAAAAAAAAAAAJGRpbmYAAAAcZHJlZgAAAAAAAAABAAAADHVybCAAAAABAAABu3N0YmwAAAChc3RzZAAAAAAAAAABAAAAkWF2YzEAAAAAAAAAAQAAAAAAAAAAAAAAAAAAAAAAQABAAEgAAABIAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAY//8AAAAnYXZjQwFkAAv/4QAMJ2QAC6xWUMN4EGEUAQAEKO48sP34+AAAAAAKZmllbAEAAAAACmNocm0AAAAAABhzdHRzAAAAAAAAAAEAAAAKAAAAPAAAAGBjdHRzAAAAAAAAAAoAAAABAAAAeAAAAAEAAAEsAAAAAQAAAHgAAAABAAAAAAAAAAEAAAA8AAAAAQAAALQAAAABAAAAPAAAAAEAAAC0AAAAAQAAADwAAAABAAAAeAAAABhzdHNzAAAAAAAAAAIAAAABAAAACgAAABZzZHRwAAAAACAQEBgYEBgQGCAAAAAcc3RzYwAAAAAAAAABAAAAAQAAAAoAAAABAAAAPHN0c3oAAAAAAAAAAAAAAAoAAADIAAAAUAAAADsAAABDAAAAOgAAAH8AAAA+AAAAPAAAACoAAAD7AAAAFHN0Y28AAAAAAAAAAQAAACw=")! }
    static func json(_ value: Any) -> Data { (try? JSONSerialization.data(withJSONObject: value)) ?? Data() }
    private static func workerID(from payload: AgentTaskPayload) -> String {
        guard let encoded = payload.bundleBase64, let archive = Data(base64Encoded: encoded), archive.count <= 8 * 1024 * 1024 else {
            fatalError("video UI fixture bundle missing")
        }
        let nameLength = Int(archive[26]) | (Int(archive[27]) << 8)
        let extraLength = Int(archive[28]) | (Int(archive[29]) << 8)
        let size = Int(archive[18]) | (Int(archive[19]) << 8) | (Int(archive[20]) << 16) | (Int(archive[21]) << 24)
        let start = 30 + nameLength + extraLength
        let jsonData = start >= 0 && size > 0 && start + size <= archive.count
            ? archive.subdata(in: start..<(start + size)) : Data()
        let json = (try? JSONSerialization.jsonObject(with: jsonData)) as? [String: Any]
        guard size > 0, start >= 30, start + size <= archive.count,
              let json,
              let taskID = json["task_id"] as? String, !taskID.isEmpty else {
            fatalError("video UI fixture bundle invalid")
        }
        return taskID
    }
    private static func previewPNG() -> Data {
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        return UIGraphicsImageRenderer(size: CGSize(width: 2, height: 2), format: format).pngData {
            UIColor.systemBlue.setFill(); $0.fill(CGRect(x: 0, y: 0, width: 2, height: 2))
        }
    }
    private static func bodyData(_ request: URLRequest) -> Data? {
        if let data = request.httpBody { return data }
        guard let stream = request.httpBodyStream else { return nil }
        stream.open(); defer { stream.close() }
        var result = Data(); var buffer = [UInt8](repeating: 0, count: 8192)
        while stream.hasBytesAvailable {
            let count = stream.read(&buffer, maxLength: buffer.count)
            if count < 0 { return nil }
            if count == 0 { break }
            result.append(buffer, count: count)
            guard result.count <= 32 * 1024 else { return nil }
        }
        return result.isEmpty ? nil : result
    }
}

private final class VideoUITestTokens: AgentTokenStore {
    private let defaults: UserDefaults
    init(defaults: UserDefaults) { self.defaults = defaults }
    func save(_ value: String, reference: String) throws { defaults.set(value, forKey: "video-test-token-\(reference)") }
    func read(reference: String) throws -> String? { defaults.string(forKey: "video-test-token-\(reference)") }
    func delete(reference: String) { defaults.removeObject(forKey: "video-test-token-\(reference)") }
}

private final class VideoUITestProtocol: URLProtocol {
    static var handler: ((URLRequest) -> (Int, [String: String], Data))?
    static var dropResponse: ((URLRequest) -> Bool)?
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let responseValue = Self.handler?(request)
        if Self.dropResponse?(request) == true {
            client?.urlProtocol(self, didFailWithError: URLError(.timedOut)); return
        }
        guard let (status, headers, data) = responseValue, let url = request.url,
              let response = HTTPURLResponse(url: url, statusCode: status, httpVersion: "HTTP/1.1", headerFields: headers) else {
            client?.urlProtocol(self, didFailWithError: URLError(.badServerResponse)); return
        }
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: data)
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}
#endif
