import SwiftUI
import UniformTypeIdentifiers
import AVKit

struct LibraryBackupView: View {
    @EnvironmentObject private var library: NoteLibrary
    @StateObject private var vault = VaultLibrary()
    @StateObject private var model = LibraryBackupViewModel()
    @State private var isImporting = false
    @State private var importPurpose: LibraryBackupImportPurpose = .archive
    @State private var confirmRestore = false
    @State private var shareURL: URL?
    @State private var selectedNoteItemIDs = Set<String>()
    @State private var recoveryOptions = [LibraryBackupRecoveryOption]()
    @State private var cleanupTransactionID: String?
    @State private var materialsRevision = 0
#if DEBUG
    @State private var uiTestFixtureReady = false
    @State private var uiTestFixturePreparing = false
    @State private var uiTestFixtureError: String?
    @State private var uiTestLastAction = ""
#endif
    private let videoStore = NoteVideoAttachmentStore()
    private let presetStore = UserCoverPresetStore()
    private let restoredVideoStore = RestoredVideoAttachmentStore()

    var body: some View {
        NavigationStack {
            List {
                Section("整库备份") {
                    Text("备份此 iPad 已保存的笔记、PDF、已分配封面、知识库、用户封面预设和本机已验证视频。连接授权、电脑任务历史和在途工作不会包含。")
                        .font(.subheadline).foregroundStyle(.secondary)
                    Button { Task { await createBackup() } } label: {
                        Label("创建备份归档", systemImage: "archivebox")
                    }.disabled(model.busy).accessibilityIdentifier("libraryBackupCreate")
                    if let shareURL {
                        ShareLink(item: shareURL) { Label("保存或分享备份", systemImage: "square.and.arrow.up") }
                    }
                    Button { importPurpose = .archive; isImporting = true } label: { Label("检查并恢复备份", systemImage: "arrow.down.doc") }
                        .disabled(model.busy).accessibilityIdentifier("libraryBackupImport")
#if DEBUG
                    if LibraryBackupUITestFixture.isEnabled {
                        if let fixtureID = LibraryBackupUITestFixture.fixtureID {
                            Text("测试夹具ID：\(fixtureID.uuidString.lowercased())").accessibilityIdentifier("libraryBackupUITestFixtureID")
                        }
                        Text("当前笔记数：\(library.notes.count)").accessibilityIdentifier("libraryBackupUITestNoteCount")
                        ForEach(library.notes, id: \.id) { note in
                            Text(note.title).accessibilityIdentifier("libraryBackupUITestNote-\(note.id)")
                        }
                        if !uiTestLastAction.isEmpty {
                            Text("测试结果：\(uiTestLastAction)").accessibilityIdentifier("libraryBackupUITestActionResult")
                        }
                        if let uiTestFixtureError {
                            Text("测试夹具错误：\(uiTestFixtureError)").accessibilityIdentifier("libraryBackupUITestFixtureError")
                        }
                        Button("载入合成归档") {
                            Task {
                                guard let fixtureURL = LibraryBackupUITestFixture.archiveURL else { return }
                                await inspect(fixtureURL, waitForUITestCancellation: LibraryBackupUITestFixture.mode == "cancel")
                            }
                        }
                        .disabled(model.busy || !uiTestFixtureReady)
                        .accessibilityIdentifier("libraryBackupLoadUITestFixture")
                    }
#endif
                }
                if let preview = model.preview {
                    Section("恢复预览 · 默认作为副本") {
                        LabeledContent("笔记", value: "\(preview.noteCount)")
                        LabeledContent("PDF 原文", value: "\(preview.pdfCount)")
                        LabeledContent("已分配封面", value: "\(preview.coverCount)")
                        LabeledContent("知识库条目", value: "\(preview.vaultCount)")
                        LabeledContent("离线视频", value: "\(preview.videoCount)")
                        LabeledContent("用户封面预设", value: "\(preview.presetCount)")
                        LabeledContent("已验证资料", value: ByteCountFormatter.string(fromByteCount: preview.verifiedBytes, countStyle: .file))
                        ForEach(preview.staged.manifest.notes, id: \.itemID) { item in
                            if let note = preview.validatedNotesByItemID[item.itemID] {
                                Button {
                                    if !selectedNoteItemIDs.insert(item.itemID).inserted { selectedNoteItemIDs.remove(item.itemID) }
                                } label: {
                                    HStack {
                                        Image(systemName: selectedNoteItemIDs.contains(item.itemID) ? "checkmark.circle.fill" : "circle")
                                        VStack(alignment: .leading) {
                                            Text(note.title)
                                            Text("来源修订 \(Date(timeIntervalSince1970: note.updatedAt / 1000).formatted(date: .abbreviated, time: .shortened)) · 原笔记不会覆盖")
                                                .font(.caption).foregroundStyle(.secondary)
                                        }
                                    }
                                }.buttonStyle(.plain).accessibilityIdentifier("libraryBackupSelect-\(item.itemID)")
                            }
                        }
                        Button("恢复所选资料为新副本", role: .none) { confirmRestore = true }
                            .disabled(model.busy).accessibilityIdentifier("libraryBackupRestore")
                    }
                    if !recoveryOptions.isEmpty {
                        Section("继续或清理未完成恢复") {
                            Text("已提交的笔记会保留。继续会按原本地恢复编号补齐；清理只移除尚未提交且归本次日志所有的副本。")
                                .font(.footnote).foregroundStyle(.secondary)
                            ForEach(recoveryOptions) { option in
                                VStack(alignment: .leading, spacing: 8) {
                                    Text("已完成 \(option.completedGroupCount) 组 · 待处理 \(option.pendingGroupCount) 组").accessibilityIdentifier("libraryBackupRecoverySummary")
                                    HStack {
                                        Button("继续") { Task { await continueRestore(option.transactionID) } }.disabled(model.busy).accessibilityIdentifier("libraryBackupContinue-\(option.transactionID)")
                                        Button("清理未完成恢复", role: .destructive) { cleanupTransactionID = option.transactionID }.disabled(model.busy).accessibilityIdentifier("libraryBackupCleanup-\(option.transactionID)")
                                    }.buttonStyle(.borderless)
                                }
                            }
                        }
                    }
                }
                Section("独立恢复资料") {
                    IndependentArchiveMaterialsView(vault: vault, presets: presetStore, videos: restoredVideoStore)
                        .id(materialsRevision)
                    Button { importPurpose = .preset; isImporting = true } label: { Label("从文件添加用户封面预设", systemImage: "photo.badge.plus") }
                }
                if model.busy { Section { HStack {
#if DEBUG
                    ProgressView(model.testCancellationWait ? "等待测试取消…" : "正在流式校验或写入归档…")
                        .accessibilityIdentifier(model.testCancellationWait ? "libraryBackupUITestWaitpoint" : "libraryBackupBusyProgress")
#else
                    ProgressView("正在流式校验或写入归档…")
#endif
                    Spacer(); Button("取消") { model.cancel() }.accessibilityIdentifier("libraryBackupCancel")
                } } }
            }
            .navigationTitle("整库备份与恢复")
#if DEBUG
            .onAppear {
                guard LibraryBackupUITestFixture.isEnabled, !uiTestFixtureReady, !uiTestFixturePreparing else { return }
                uiTestFixturePreparing = true
                Task {
                    do {
                        _ = try await LibraryBackupUITestFixture.prepareIfNeeded(
                            library: library, vault: vault, videoStore: restoredVideoStore, presetStore: presetStore)
                        uiTestFixtureReady = true
                    } catch { uiTestFixtureError = error.localizedDescription }
                    uiTestFixturePreparing = false
                }
            }
#endif
            .onDisappear {
                if let preview = model.preview {
                    let coordinator = LibraryBackupRestoreCoordinator(library: library, vault: vault,
                        videoStore: restoredVideoStore, presetStore: presetStore)
                    Task { try? coordinator.cleanupStagedPreviewIfUnreferenced(preview) }
                }
            }
            .onChange(of: model.preview?.id) { _, _ in
                selectedNoteItemIDs = Set(model.preview?.staged.manifest.notes.map(\.itemID) ?? [])
            }
            .fileImporter(isPresented: $isImporting,
                allowedContentTypes: importPurpose == .preset ? [.png] : [.zip],
                allowsMultipleSelection: false) { result in
                guard let selection = LibraryBackupImportSelection.resolve(result: result, purpose: importPurpose) else { return }
                switch selection {
                case .archive(let url):
                    Task { await inspect(url) }
                case .preset(let url):
                    Task { await model.run { cancellation in
                        let scoped = url.startAccessingSecurityScopedResource(); defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                        _ = try cancellation.check()
                        _ = try presetStore.add(name: url.deletingPathExtension().lastPathComponent, png: url)
                        await MainActor.run { materialsRevision += 1 }
                    } }
                }
            }
            .confirmationDialog("将验证后的资料导入为新副本", isPresented: $confirmRestore, titleVisibility: .visible) {
                Button("恢复所选资料") { Task { await restore() } }
                Button("取消", role: .cancel) { }
            } message: { Text("不会覆盖现有笔记或恢复电脑连接、授权、任务记录。大文件会逐项校验。") }
            .confirmationDialog("清理未完成恢复？", isPresented: Binding(get: { cleanupTransactionID != nil }, set: { if !$0 { cleanupTransactionID = nil } }), titleVisibility: .visible) {
                Button("清理未完成恢复", role: .destructive) {
                    if let cleanupTransactionID { Task { await rollback(cleanupTransactionID) } }
                    cleanupTransactionID = nil
                }.accessibilityIdentifier("libraryBackupConfirmCleanup")
                Button("取消", role: .cancel) { cleanupTransactionID = nil }
            } message: { Text("只清理此归档中尚未提交、且由本机恢复日志绑定的副本。已经完成的笔记会保留。") }
            .alert("备份操作未完成", isPresented: Binding(get: { model.error != nil }, set: { presented in
                guard !presented else { return }
                // SwiftUI may dismiss the alert while updating this view; defer the observed-object publish.
                Task { @MainActor in
                    await Task.yield()
                    model.error = nil
                }
            })) {
                Button("好", role: .cancel) { }
            } message: { Text(model.error ?? "") }
        }
    }

    private func createBackup() async {
        await model.run { cancellation in
            let base = try Self.workDirectory()
            let captureDir = base.appendingPathComponent("capture-\(UUID().uuidString)", isDirectory: true)
            guard !FileManager.default.fileExists(atPath: captureDir.path) else { throw LibraryBackupError.unsafeFile }
            defer { try? FileManager.default.removeItem(at: captureDir) }
            let snapshot = try await LibraryBackupSnapshot.capture(library: library, vault: vault, videoStore: videoStore,
                presetStore: presetStore, restoredVideoStore: restoredVideoStore, coverStore: NoteCoverStore(), into: captureDir, cancellation: cancellation)
            let output = base.appendingPathComponent("PadNote-Library-\(UUID().uuidString.lowercased()).padnote-library.zip")
            guard !FileManager.default.fileExists(atPath: output.path) else { throw LibraryBackupError.unsafeFile }
            try await Task.detached(priority: .userInitiated) {
                try LibraryBackupArchive.write(manifest: snapshot.manifest, resourceFiles: snapshot.resourceFiles, to: output, cancellation: cancellation)
            }.value
            await MainActor.run { shareURL = output }
        }
    }

    private func inspect(_ url: URL, waitForUITestCancellation: Bool = false) async {
        await model.run { cancellation in
            let stage = LibraryBackupTransactionGate.defaultStagingRoot
            let coordinator = LibraryBackupRestoreCoordinator(library: library, vault: vault,
                videoStore: restoredVideoStore, presetStore: presetStore)
            let preview = try await coordinator.inspect(url, stagingRoot: stage, cancellation: cancellation)
#if DEBUG
            if waitForUITestCancellation {
                await MainActor.run { model.testCancellationWait = true }
                do {
                    try await LibraryBackupUITestFixture.waitForCancellation(cancellation, timeoutSeconds: 10)
                    try cancellation.check()
                } catch {
                    await MainActor.run { model.testCancellationWait = false }
                    try? coordinator.cleanupStagedPreviewIfUnreferenced(preview)
                    throw error
                }
                await MainActor.run { model.testCancellationWait = false }
                try? coordinator.cleanupStagedPreviewIfUnreferenced(preview)
                throw LibraryBackupError.transaction("测试取消等待超时")
            }
#endif
            if let old = model.preview { try coordinator.cleanupStagedPreviewIfUnreferenced(old) }
            let options = try coordinator.recoveryOptions(for: preview)
            await MainActor.run { model.preview = preview; recoveryOptions = options }
        }
    }

    private func restore() async {
        guard let preview = model.preview else { return }
        await model.run { cancellation in
            let coordinator = LibraryBackupRestoreCoordinator(library: library, vault: vault,
                videoStore: restoredVideoStore, presetStore: presetStore)
            try await coordinator.restore(preview, selectedNoteItemIDs: selectedNoteItemIDs, cancellation: cancellation)
            await MainActor.run { model.preview = nil; recoveryOptions = []; vault.reload(); materialsRevision += 1 }
        }
        await reloadRecoveryOptions()
    }

    private func continueRestore(_ transactionID: String) async {
        guard let preview = model.preview else { return }
        await model.run { cancellation in
            let coordinator = LibraryBackupRestoreCoordinator(library: library, vault: vault,
                videoStore: restoredVideoStore, presetStore: presetStore)
            try await coordinator.restore(preview, continuingTransactionID: transactionID, cancellation: cancellation)
            await MainActor.run {
                model.preview = nil; recoveryOptions = []; vault.reload(); materialsRevision += 1
#if DEBUG
                if LibraryBackupUITestFixture.isEnabled { uiTestLastAction = "continued" }
#endif
            }
        }
        await reloadRecoveryOptions()
    }

    private func rollback(_ transactionID: String) async {
        guard let preview = model.preview else { return }
        await model.run { cancellation in
            let coordinator = LibraryBackupRestoreCoordinator(library: library, vault: vault,
                videoStore: restoredVideoStore, presetStore: presetStore)
            try await coordinator.rollbackIncompleteRestore(transactionID, for: preview, cancellation: cancellation)
            await MainActor.run {
                vault.reload(); materialsRevision += 1
#if DEBUG
                if LibraryBackupUITestFixture.isEnabled { uiTestLastAction = "cleaned" }
#endif
            }
        }
        await reloadRecoveryOptions()
    }

    private func reloadRecoveryOptions() async {
        guard let preview = model.preview else { return }
        let coordinator = LibraryBackupRestoreCoordinator(library: library, vault: vault,
            videoStore: restoredVideoStore, presetStore: presetStore)
        do { recoveryOptions = try coordinator.recoveryOptions(for: preview) }
        catch { model.error = error.localizedDescription }
    }

    private static func workDirectory() throws -> URL {
        let fallback = FileManager.default.temporaryDirectory.appendingPathComponent("PadNoteLibraryBackup", isDirectory: true)
#if DEBUG
        let base = LibraryBackupUITestPaths.directory("work", fallback: fallback)
#else
        let base = fallback
#endif
        try FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        return base
    }
}

#if DEBUG
@MainActor private enum LibraryBackupUITestFixture {
    static var isEnabled: Bool {
        ProcessInfo.processInfo.arguments.contains("--uitesting-library-backup") && fixtureID != nil
    }
    static var fixtureID: UUID? {
        let args = ProcessInfo.processInfo.arguments
        guard let index = args.firstIndex(of: "--library-backup-fixture-id"), args.indices.contains(index + 1) else { return nil }
        return UUID(uuidString: args[index + 1])
    }
    static var mode: String? {
        let args = ProcessInfo.processInfo.arguments
        guard let index = args.firstIndex(of: "--library-backup-fixture-mode"), args.indices.contains(index + 1) else { return nil }
        return args[index + 1]
    }
    static var archiveURL: URL? {
        guard let root = LibraryBackupUITestPaths.root else { return nil }
        return root.appendingPathComponent("fixture-work", isDirectory: true).appendingPathComponent("fixture.zip")
    }
    private static var preparedID: UUID?
    private static var preparedURL: URL?

    static func prepareIfNeeded(library: NoteLibrary, vault: VaultLibrary, videoStore: RestoredVideoAttachmentStore,
                                presetStore: UserCoverPresetStore) async throws -> URL {
        guard isEnabled, let id = fixtureID, let root = LibraryBackupUITestPaths.root,
              ["cancel", "resume", "cleanup", "picker"].contains(mode ?? "picker") else {
            throw LibraryBackupError.transaction("UI测试夹具参数无效")
        }
        if preparedID == id, let preparedURL { return preparedURL }
        let work = root.appendingPathComponent("fixture-work", isDirectory: true)
        try FileManager.default.createDirectory(at: work, withIntermediateDirectories: true)
        let archive = work.appendingPathComponent("fixture.zip")
        guard !FileManager.default.fileExists(atPath: archive.path) else { throw LibraryBackupError.unsafeFile }
        let note = NoteDocument(id: "backup-ui-\(id.uuidString.lowercased())", title: "合成恢复笔记", updatedAt: NoteDocument.nowMillis())
        let payload = work.appendingPathComponent("note-\(UUID().uuidString.lowercased()).json")
        defer { try? FileManager.default.removeItem(at: payload) }
        try note.encoded().write(to: payload, options: .atomic)
        let noteItemID = "i-\(UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased())"
        let resourceID = "r-\(UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased())"
        let digest = try LibraryBackupArchive.hashFile(payload)
        let resource = LibraryBackupManifest.Resource(resourceID: resourceID, role: "note_document",
            mediaType: "application/json", byteLength: digest.size, sha256: digest.sha256,
            member: "payload/\(resourceID).bin")
        let manifestNote = LibraryBackupManifest.Note(itemID: noteItemID, sourceNoteID: note.id,
            sourceRevisionMS: Int64(note.updatedAt), schemaVersion: note.schemaVersion, noteResourceID: resourceID,
            pdfResourceID: nil, coverResourceID: nil, vaultEntryIDs: [], videoAttachmentIDs: [])
        let manifest = LibraryBackupManifest(createdAtMS: Int64(Date().timeIntervalSince1970 * 1000),
            producer: ["platform": "ios", "app_version": "uitest"], notes: [manifestNote],
            vaultEntries: [], videoAttachments: [], coverPresets: [], resources: [resource])
        try LibraryBackupArchive.write(manifest: manifest, resourceFiles: [resourceID: payload], to: archive)
        if mode == "resume" || mode == "cleanup" {
            let coordinator = LibraryBackupRestoreCoordinator(library: library, vault: vault,
                videoStore: videoStore, presetStore: presetStore)
            let preview = try await coordinator.inspect(archive, stagingRoot: LibraryBackupTransactionGate.defaultStagingRoot)
            let cancellation = LibraryBackupCancellationToken()
            cancellation.cancel()
            do {
                try await coordinator.restore(preview, cancellation: cancellation)
                throw LibraryBackupError.transaction("UI恢复夹具未停在可恢复状态")
            } catch LibraryBackupError.cancelled { }
        }
        preparedID = id; preparedURL = archive
        return archive
    }

    static func waitForCancellation(_ token: LibraryBackupCancellationToken, timeoutSeconds: UInt64) async throws {
        let deadline = ContinuousClock.now.advanced(by: .seconds(timeoutSeconds))
        while ContinuousClock.now < deadline {
            do { try token.check() } catch { return }
            try await Task.sleep(nanoseconds: 50_000_000)
        }
        throw LibraryBackupError.transaction("UI测试取消等待超时")
    }
}
#endif

@MainActor private final class LibraryBackupViewModel: ObservableObject {
    @Published var busy = false
    @Published var preview: LibraryBackupPreview?
    @Published var error: String?
#if DEBUG
    @Published var testCancellationWait = false
#endif
    private var cancellation: LibraryBackupCancellationToken?
    func cancel() { cancellation?.cancel() }
    func run(_ body: @escaping (LibraryBackupCancellationToken) async throws -> Void) async {
        guard !busy else { return }; busy = true
        let token = LibraryBackupCancellationToken(); cancellation = token
        defer { cancellation = nil; busy = false }
        do { try await body(token); error = nil } catch { self.error = error.localizedDescription }
    }
}

private struct IndependentArchiveMaterialsView: View {
    @ObservedObject var vault: VaultLibrary
    let presets: UserCoverPresetStore
    let videos: RestoredVideoAttachmentStore
    @State private var presetValues = [UserCoverPreset]()
    @State private var videoValues = [RestoredVideoAttachment]()
    @State private var error: String?
    @State private var share: ShareArtifact?
    @State private var playerURL: URL?
    @State private var player: AVPlayer?
    var body: some View {
        Group {
            if let vaultError = vault.errorMessage {
                Text("知识库：\(vaultError)").foregroundStyle(.orange)
            }
            if let error { Text("独立资料读取失败：\(error)").foregroundStyle(.orange) }
            ForEach(videoValues.filter { $0.noteID == nil }) { item in
                HStack {
                    VStack(alignment: .leading) {
                        Text("\(item.displayName) · 已恢复离线视频")
                        Text(sourceDescription(item.archiveSourceState))
                            .font(.caption).foregroundStyle(.secondary)
                    }
                    Spacer()
                    Button {
                        Task {
                            do {
                                let url = try await Task.detached(priority: .userInitiated) { try videos.fileURL(for: item) }.value
                                player = AVPlayer(url: url); playerURL = url
                            } catch { self.error = "无法打开视频：\(error.localizedDescription)" }
                        }
                    } label: { Image(systemName: "play.fill") }.accessibilityLabel("播放 \(item.displayName)")
                    Button {
                        Task {
                            do {
                                let url = try await Task.detached(priority: .userInitiated) { try videos.fileURL(for: item) }.value
                                share = ShareArtifact(url: url)
                            } catch { self.error = "无法分享视频：\(error.localizedDescription)" }
                        }
                    } label: { Image(systemName: "square.and.arrow.up") }.accessibilityLabel("分享 \(item.displayName)")
                    Button(role: .destructive) { remove(item) } label: { Image(systemName: "trash") }
                }
            }
            ForEach(vault.notes.filter { $0.archiveOrigin == "restored_archive" && $0.archiveLinkedNoteID == nil }) { item in
                NavigationLink {
                    ScrollView { Text(item.markdown).frame(maxWidth: .infinity, alignment: .leading).padding() }
                        .navigationTitle(item.title)
                } label: {
                    VStack(alignment: .leading) {
                        Text(item.title + " · 独立恢复知识库")
                        Text("来源：\(item.archiveSourceState == "source_deleted" ? "原笔记已删除" : item.archiveSourceState == "source_not_selected" ? "原笔记未包含在归档中" : item.archiveSourceNoteID ?? "独立资料")")
                            .font(.caption).foregroundStyle(.secondary)
                    }
                }
            }
            ForEach(presetValues) { item in
                HStack {
                    Text("封面预设：\(item.name)")
                    Spacer()
                    Button {
                        Task {
                            do {
                                let url = try await Task.detached(priority: .userInitiated) { try presets.pngURL(for: item) }.value
                                share = ShareArtifact(url: url)
                            } catch { self.error = "无法分享封面预设：\(error.localizedDescription)" }
                        }
                    } label: { Image(systemName: "square.and.arrow.up") }.accessibilityLabel("分享封面预设 \(item.name)")
                    Button(role: .destructive) { remove(item) } label: { Image(systemName: "trash") }
                }
            }
            if videoValues.allSatisfy({ $0.noteID != nil }) && presetValues.isEmpty && vault.notes.allSatisfy({ $0.archiveOrigin != "restored_archive" || $0.archiveLinkedNoteID != nil }) && error == nil {
                Text("没有独立恢复资料或用户封面预设。") .foregroundStyle(.secondary)
            }
        }
        .sheet(isPresented: Binding(get: { playerURL != nil }, set: { if !$0 { playerURL = nil } })) {
            if let player { VideoPlayer(player: player).ignoresSafeArea().onDisappear { player.pause(); self.player = nil } }
        }
        .sheet(item: $share) { ShareSheet(url: $0.url) }
        .task(id: vault.notes.count) { reload() }
    }
    private func reload() {
        vault.reload()
        Task {
            do {
                let result = try await Task.detached(priority: .userInitiated) {
                    let videos = try self.videos.listing(), videoIssues = self.videos.lastListingIssueCount
                    let presets = try self.presets.listing(), presetIssues = self.presets.lastListingIssueCount
                    return (videos, presets, videoIssues + presetIssues)
                }.value
                videoValues = result.0; presetValues = result.1
                error = result.2 == 0 ? nil : "有 \(result.2) 份恢复资料记录损坏或残留；其他有效资料仍可使用，未知文件不会自动删除。"
            } catch { self.error = "恢复资料读取失败：\(error.localizedDescription)" }
        }
    }
    private func sourceDescription(_ state: String?) -> String {
        switch state {
        case "source_deleted": return "原笔记已删除 · 本机独立副本"
        case "source_not_selected": return "来源笔记未包含在备份中 · 本机独立副本"
        default: return "独立恢复资料 · 本机副本"
        }
    }
    private func remove(_ value: RestoredVideoAttachment) {
        Task {
            do { try await Task.detached(priority: .userInitiated) { try videos.remove(value) }.value; reload() }
            catch { self.error = error.localizedDescription }
        }
    }
    private func remove(_ value: UserCoverPreset) {
        Task {
            do { try await Task.detached(priority: .userInitiated) { try presets.remove(value) }.value; reload() }
            catch { self.error = error.localizedDescription }
        }
    }
}


enum LibraryBackupImportPurpose: Equatable {
    case archive
    case preset
}

enum LibraryBackupImportSelection {
    case archive(URL)
    case preset(URL)

    static func resolve(result: Result<[URL], Error>, purpose: LibraryBackupImportPurpose) -> Self? {
        guard case .success(let urls) = result, let url = urls.first else { return nil }
        switch purpose {
        case .archive: return .archive(url)
        case .preset: return .preset(url)
        }
    }
}
