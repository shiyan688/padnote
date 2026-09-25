import SwiftUI
import UniformTypeIdentifiers

struct NoteAISourceSnapshot: Identifiable {
    let id = UUID()
    let image: UIImage?
    let note: NoteDocument
    let page: Int
    let selectionBounds: CGRect?
    let vaultEntries: [NoteToolVaultEntry]
    let pdfDigest: String?

    init(image: UIImage?, note: NoteDocument, page: Int, selectionBounds: CGRect?,
         vaultEntries: [NoteToolVaultEntry], pdfDigest: String? = nil) {
        self.image = image?.pngData().flatMap(UIImage.init(data:)) ?? image
        self.note = note
        self.page = page
        self.selectionBounds = selectionBounds
        self.vaultEntries = vaultEntries
        self.pdfDigest = pdfDigest
    }
}

struct NoteEditorView: View {
    private enum SavePhase: Equatable { case saved, dirty, saving, failed }
    @EnvironmentObject private var library: NoteLibrary
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    @State private var document: NoteDocument
    @StateObject private var canvas = CanvasController()
    @StateObject private var vault = VaultLibrary()
    @ObservedObject private var compiledRenderer = CompiledTextRenderer.shared
    @State private var pendingSave: Task<Void, Never>?
    @State private var pendingDraft: Task<Void, Never>?
    @State private var savePhase: SavePhase = .saved
    @State private var editRevision = 0
    @State private var persistedDraftRevision = 0
    @State private var lastSavedAt: Date?
    @State private var saveFailure: String?
    @State private var showSaveActions = false
    @State private var error: String?
    @State private var showText = false
    @State private var editingFlow: NoteTextFlow?
    @State private var showAI = false
    @State private var aiSource: NoteAISourceSnapshot?
    @State private var showPages = false
    @State private var showPaperSettings = false
    @State private var sharing: ShareArtifact?
    @State private var showImageImport = false
    @State private var showDigitize = false
    @State private var showWidth = false
    @State private var showColors = false
    @State private var exportingPDF = false
    @State private var showPDFRenderRecovery = false
    @State private var pdfExportOperationID = UUID()
    @State private var confirmClear = false

    init(note: NoteDocument) { _document = State(initialValue: note) }

    private var pdfURL: URL? { library.pdfURL(for: document) }
    private let colors = ["#FF1F2933", "#FF285EA8", "#FFB23A30", "#FF2F805B", "#FF74509A"]

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                inkToolbar
                Divider()
                ZStack(alignment: .bottom) {
                    NoteCanvas(document: $document, controller: canvas, pdfURL: pdfURL)
                        .accessibilityIdentifier("noteCanvas")
                    if canvas.hasSelection {
                        HStack(spacing: 16) {
                            Button("问 AI", systemImage: "sparkles") { openAI(image: canvas.selectionImage) }
                                .disabled(canvas.selectionImage == nil)
                            Button("复制", systemImage: "doc.on.doc") { canvas.duplicateSelection() }
                            Button("删除", systemImage: "trash", role: .destructive) { canvas.deleteSelection() }
                            Button("取消") { canvas.clearSelection() }
                        }
                        .buttonStyle(.bordered).controlSize(.large)
                        .padding(12).background(.regularMaterial, in: RoundedRectangle(cornerRadius: 20))
                        .padding(16)
                    }
                    if showAI, let source = aiSource {
                        FloatingAIPanel(onClose: closeAI) {
                            AIAssistantView(image: source.image, noteContext: "", embedded: true, onClose: closeAI,
                                toolNote: source.note, toolPage: source.page, selectionBounds: source.selectionBounds,
                                vaultEntries: source.vaultEntries, currentToolDocument: document,
                                sourcePDFDigest: source.pdfDigest,
                                onToolCommit: commitAITools, onToolMutationAction: performAIToolMutation,
                                onToolLocate: locateAIToolMutation,
                                toolMutationApplied: { canvas.aiApplicationState(for: $0) }) { _ in }
                                .id(source.id)
                        }
                    }
                    if !compiledRenderer.failures(document: document).isEmpty && !showAI {
                        Button {
                            showPaperSettings = true
                        } label: {
                            Label("有 \(compiledRenderer.failures(document: document).count) 个文字对象未完成排版", systemImage: "exclamationmark.triangle")
                        }
                        .buttonStyle(.borderedProminent).tint(.orange)
                        .padding(16).accessibilityIdentifier("renderFailureEntry")
                    }
                }
                HStack {
                    Button { showPages = true } label: {
                        Label("第 \(canvas.currentPage + 1) / \(document.pageCount) 页", systemImage: "rectangle.stack")
                    }.accessibilityIdentifier("pageManagerButton")
                    Spacer()
                    Text(canvas.pencilOnly ? "Apple Pencil 书写 · 手指浏览 · 双指缩放" : "手指或 Apple Pencil 书写 · 双指浏览与缩放")
                        .lineLimit(1).minimumScaleFactor(0.8)
                    Spacer()
                    if savePhase == .failed {
                        Button("重试保存") { beginSave() }.buttonStyle(.borderless)
                        Button("导出未保存副本") { exportEditableSnapshot() }.buttonStyle(.borderless)
                    }
                    Text(saveStatusText).foregroundStyle(savePhase == .failed ? .red : PadTheme.secondary)
                        .accessibilityIdentifier("saveStatus")
                }
                .font(.system(size: 12)).foregroundStyle(PadTheme.secondary)
                .padding(.horizontal, 24).padding(.vertical, 8)
                .background(PadTheme.surface)
            }
            .background(PadTheme.surface)
            .navigationTitle(document.title).navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button { requestClose() } label: { Label("书架", systemImage: "chevron.left") }
                        .accessibilityIdentifier("backToShelf")
                }
                ToolbarItemGroup(placement: .topBarTrailing) {
                    Button { canvas.undo() } label: { Image(systemName: "arrow.uturn.backward") }
                        .disabled(!canvas.canUndo).accessibilityLabel("撤销").accessibilityIdentifier("undoButton")
                    Button { canvas.redo() } label: { Image(systemName: "arrow.uturn.forward") }
                        .disabled(!canvas.canRedo).accessibilityLabel("重做")
                    Button { openAI(image: canvas.selectionImage) } label: { Image(systemName: "sparkles") }
                        .accessibilityLabel("AI 助手")
                    Menu {
                        Button("保存", systemImage: "checkmark.circle") { beginSave() }
                        Button("导出可编辑笔记", systemImage: "square.and.arrow.up") {
                            exportEditableSnapshot()
                        }
                        Button("导出 PDF", systemImage: "doc.richtext") { exportPDF() }
                        Button("整理到知识库", systemImage: "text.book.closed") { showDigitize = true }
                        Button("插入图片", systemImage: "photo") { showImageImport = true }
                        Button("管理文字", systemImage: "text.alignleft") { showPaperSettings = true }
                        Button("清空当前笔记", systemImage: "trash", role: .destructive) { confirmClear = true }
                            .disabled(document.strokes.isEmpty && document.textFlows.isEmpty && document.images.isEmpty)
                    } label: { Image(systemName: "ellipsis.circle") }
                        .accessibilityLabel("更多操作").accessibilityIdentifier("moreActionsButton")
                }
            }
        }
        .onAppear(perform: configureInitialSaveState)
        .onChange(of: document) { _, _ in documentChanged() }
        .onChange(of: scenePhase) { _, phase in
            if phase != .active {
                canvas.onFinishTextEditing?()
                beginSave()
            }
        }
        .onDisappear {
            canvas.onFinishTextEditing?()
            if savePhase != .saved { enqueueDraft(document, revision: editRevision) }
        }
        .interactiveDismissDisabled(savePhase != .saved)
        .sheet(isPresented: $showText) {
            TextFlowEditor(flow: editingFlow) { source, format, size in
                insertText(source, format: format, size: size, replacing: editingFlow?.id)
            }
        }
        .sheet(isPresented: $showPages) { pageManager }
        .sheet(isPresented: $showPaperSettings) { textManager }
        .sheet(isPresented: $showDigitize) { DigitizeNoteView(note: document, pdfURL: pdfURL) }
        .sheet(item: $sharing) { ShareSheet(url: $0.url) }
        .fileImporter(isPresented: $showImageImport, allowedContentTypes: [.image]) { result in
            do {
                let url = try result.get()
                let access = url.startAccessingSecurityScopedResource()
                defer { if access { url.stopAccessingSecurityScopedResource() } }
                let values = try url.resourceValues(forKeys: [.fileSizeKey])
                guard (values.fileSize ?? 0) <= 20 * 1024 * 1024,
                      let original = UIImage(contentsOfFile: url.path), original.size.width > 0 else {
                    throw CocoaError(.fileReadCorruptFile)
                }
                let factor = min(1, 1600 / max(original.size.width, original.size.height))
                let size = CGSize(width: original.size.width * factor, height: original.size.height * factor)
                let rendererFormat = UIGraphicsImageRendererFormat(); rendererFormat.scale = 1
                let image = UIGraphicsImageRenderer(size: size, format: rendererFormat).image { _ in original.draw(in: CGRect(origin: .zero, size: size)) }
                guard let png = image.pngData() else { throw CocoaError(.fileReadCorruptFile) }
                let width = min(document.pageWidth - 96, Double(size.width), (document.pageHeight - 144) * size.width / size.height)
                let item = NoteImage(id: UUID().uuidString, png: png.base64EncodedString(), page: canvas.currentPage,
                                     x: 48, y: 72, width: width, height: width * size.height / size.width)
                var candidate = document; candidate.images.append(item)
                _ = try candidate.validated()
                canvas.performEdit { $0.images.append(item) }
            } catch { self.error = error.localizedDescription }
        }
        .alert("无法完成操作", isPresented: Binding(get: { error != nil }, set: { if !$0 { error = nil } })) {
            Button("好", role: .cancel) { error = nil }
        } message: { Text(error ?? "") }
        .confirmationDialog("清空当前笔记？", isPresented: $confirmClear, titleVisibility: .visible) {
            Button("清空", role: .destructive) {
                canvas.onFinishTextEditing?()
                canvas.clearSelection()
                canvas.performEdit { $0.strokes = []; $0.textFlows = []; $0.images = [] }
            }
            Button("取消", role: .cancel) {}
        } message: { Text("清除笔迹、文字和图片。页面和 PDF 底图保留，可使用撤销恢复。") }
        .confirmationDialog("PDF 中有文字未完成排版", isPresented: $showPDFRenderRecovery, titleVisibility: .visible) {
            Button("查看并修复源码") { showPaperSettings = true }
            Button("取消", role: .cancel) {}
        } message: {
            Text("完整 PDF 尚未生成。请查看失败对象，修复或重试后再导出。笔记源码仍然保留。")
        }
        .confirmationDialog("更改尚未保存", isPresented: $showSaveActions, titleVisibility: .visible) {
            Button("重试保存") { beginSave() }
            Button("导出未保存副本") { exportEditableSnapshot() }
            if persistedDraftRevision == editRevision && editRevision > 0 {
                Button("保留恢复副本并离开") { dismiss() }
            }
            Button("继续编辑", role: .cancel) {}
        } message: {
            Text(saveFailure ?? "保存未完成。请重试或先导出当前副本。")
        }
    }

    private var currentPageText: String {
        document.textFlows.filter { $0.anchorPageIndex == canvas.currentPage }.map(\.source).joined(separator: "\n\n")
    }

    private var inkToolbar: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                tool(.pen, "pencil.tip", "画笔")
                tool(.highlighter, "highlighter", "高亮")
                tool(.eraser, "eraser", "橡皮")
                tool(.lasso, "lasso", "套索")
                Button { canvas.tool = .text } label: { Image(systemName: "textformat").frame(width: 44, height: 44)
                    .background(canvas.tool == .text ? PadTheme.accentLight : .clear, in: RoundedRectangle(cornerRadius: 10)) }
                    .accessibilityLabel("插入文字").accessibilityIdentifier("insertTextButton")
                if !canvas.selectedTextFlowIDs.isEmpty {
                    Button("A−") { canvas.adjustSelectedTextFontSize(by: -1) }
                        .accessibilityIdentifier("selectedTextFontDecrease")
                    Text(canvas.selectedTextFontSize.map { String(format: "%.0f", $0) } ?? "—")
                        .frame(minWidth: 28).accessibilityIdentifier("selectedTextFontSize")
                    Button("A+") { canvas.adjustSelectedTextFontSize(by: 1) }
                        .accessibilityIdentifier("selectedTextFontIncrease")
                }
                Menu {
                    Button("矩形", systemImage: "rectangle") { canvas.tool = .rectangle }
                    Button("直线", systemImage: "line.diagonal") { canvas.tool = .line }
                    Button("椭圆", systemImage: "oval") { canvas.tool = .ellipse }
                } label: { Image(systemName: canvas.tool == .ellipse ? "oval" : canvas.tool == .line ? "line.diagonal" : "rectangle")
                    .frame(width: 44, height: 44)
                    .background([CanvasTool.rectangle, .line, .ellipse].contains(canvas.tool) ? PadTheme.accentLight : .clear, in: RoundedRectangle(cornerRadius: 10))
                }.accessibilityLabel("图形工具")
                Divider().frame(height: 24).padding(.horizontal, 4)
                ForEach(colors, id: \.self) { color in
                    let rgb = UInt32(color.suffix(6), radix: 16) ?? 0
                    Button { activeInkColor.wrappedValue = color } label: {
                        Circle().fill(Color(hex: rgb)).frame(width: 24, height: 24)
                            .padding(5).overlay(Circle().stroke(activeInkColor.wrappedValue == color ? PadTheme.accent : .clear, lineWidth: 2))
                            .frame(width: 44, height: 44)
                    }.accessibilityLabel("墨水颜色 \(color)")
                }
                Button { showColors = true } label: {
                    Image(systemName: "paintpalette").frame(width: 44, height: 44)
                }.accessibilityLabel("自定义颜色").popover(isPresented: $showColors) { InkColorPicker(hex: activeInkColor) }
                Button { showWidth = true } label: {
                    HStack(spacing: 6) { Circle().frame(width: min(12, canvas.strokeWidth + 2)); Text("\(canvas.strokeWidth, specifier: "%.1f")") }
                        .font(.system(size: 13)).frame(width: 64, height: 44)
                }.accessibilityLabel("笔触粗细").popover(isPresented: $showWidth) { InkToolOptions(canvas: canvas) }
                Divider().frame(height: 24).padding(.horizontal, 4)
                Toggle(isOn: $canvas.pencilOnly) { Label("仅笔", systemImage: "applepencil") }
                    .toggleStyle(.button).accessibilityIdentifier("pencilOnlyToggle")
                    .accessibilityValue(canvas.pencilOnly ? "1" : "0")
                Button {
                    let page = document.pageCount
                    canvas.performEdit { _ = NotePageOperations.appendBlankPage(in: &$0) }
                    canvas.goToPage(min(page, document.pageCount - 1))
                } label: { Image(systemName: "doc.badge.plus").frame(width: 44, height: 44) }
                    .accessibilityLabel("添加页面").accessibilityIdentifier("addPageButton")
            }.buttonStyle(.plain).padding(.horizontal, 16).padding(.vertical, 4)
        }.background(.white)
    }

    private var activeInkColor: Binding<String> {
        Binding(get: { canvas.tool == .highlighter ? canvas.highlighterColor : canvas.inkColor },
                set: { if canvas.tool == .highlighter { canvas.highlighterColor = $0 } else { canvas.inkColor = $0 } })
    }

    private func tool(_ tool: CanvasTool, _ symbol: String, _ title: String) -> some View {
        Button { canvas.tool = tool } label: {
            Image(systemName: symbol).font(.system(size: 20))
                .frame(width: 44, height: 44)
                .background(canvas.tool == tool ? PadTheme.accentLight : .clear, in: RoundedRectangle(cornerRadius: 10))
        }.accessibilityLabel(title).accessibilityIdentifier("tool-\(tool.rawValue)")
    }

    private var pageManager: some View {
        NavigationStack {
            ScrollView {
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 140))], spacing: 24) {
                    ForEach(0..<document.pageCount, id: \.self) { page in
                        Button {
                            canvas.goToPage(page); showPages = false
                        } label: {
                            VStack {
                                Image(uiImage: NoteRenderer.renderPage(document: document, page: page, pdfURL: pdfURL, maxEdge: 320))
                                    .resizable().scaledToFit().frame(height: 180)
                                Text("第 \(page + 1) 页").font(.system(size: 13))
                            }.padding(12).background(.white, in: RoundedRectangle(cornerRadius: 18))
                        }.buttonStyle(.plain)
                            .contextMenu {
                                Button("复制页面", systemImage: "doc.on.doc") { canvas.duplicatePage(page) }
                                    .disabled(page < document.pdfPageCount || document.pageCount >= 500)
                                Button("上移", systemImage: "arrow.up") { canvas.movePage(from: page, to: page - 1) }
                                    .disabled(page <= document.pdfPageCount)
                                Button("下移", systemImage: "arrow.down") { canvas.movePage(from: page, to: page + 1) }
                                    .disabled(page < document.pdfPageCount || page == document.pageCount - 1)
                                Button("删除页面", systemImage: "trash", role: .destructive) { canvas.deletePage(page) }
                                    .disabled(page < document.pdfPageCount || document.pageCount <= 1)
                            }
                    }
                }.padding(24)
            }.background(PadTheme.surface).navigationTitle("页面")
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button("完成") { showPages = false } } }
        }
    }

    private var textManager: some View {
        NavigationStack {
            List {
                if document.textFlows.isEmpty { Text("还没有文字。使用工具栏的文字按钮插入内容。") }
                ForEach(document.textFlows) { flow in
                    VStack(alignment: .leading, spacing: 8) {
                        Button {
                            editFlow(flow)
                        } label: {
                            VStack(alignment: .leading, spacing: 8) {
                            Text(flow.source).lineLimit(3).accessibilityIdentifier("textFlowSource")
                            Text("第 \(flow.anchorPageIndex + 1) 页 · \(flow.format)").font(.caption).foregroundStyle(PadTheme.secondary)
                            }
                        }.accessibilityIdentifier("textFlowEditButton")
                        if let failure = compiledRenderer.failure(documentID: document.id, flow: flow) {
                            Label(failure.localizedDescription, systemImage: "exclamationmark.triangle")
                                .font(.caption).foregroundStyle(.orange)
                            ViewThatFits(in: .horizontal) {
                                renderFailureActions(flow)
                                ScrollView(.horizontal, showsIndicators: false) { renderFailureActions(flow) }
                            }
                        }
                    }.swipeActions { Button("删除", role: .destructive) { canvas.performEdit { $0.textFlows.removeAll { $0.id == flow.id } } } }
                }
            }.navigationTitle("页面文字")
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button("完成") { showPaperSettings = false } } }
        }
    }

    private func insertText(_ source: String, format: String, size: Double, replacing id: String? = nil) {
        let clean = source.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !clean.isEmpty else { return }
        let targetPage = id.flatMap { id in document.textFlows.first(where: { $0.id == id })?.anchorPageIndex }
            ?? ((!document.strokes.isEmpty || !document.textFlows.isEmpty || document.pdfPageCount > 0 || !document.images.isEmpty) ? document.pageCount : 0)
        canvas.performEdit { note in
            if let id, let index = note.textFlows.firstIndex(where: { $0.id == id }) {
                note.textFlows[index].source = clean; note.textFlows[index].format = format; note.textFlows[index].fontSizeSp = size
            } else {
                // A dedicated notes page keeps inserted AI text clear of the original handwriting and PDF.
                let needsPage = !note.strokes.isEmpty || !note.textFlows.isEmpty || note.pdfPageCount > 0 || !note.images.isEmpty
                let page = needsPage ? note.pageCount : 0
                guard page < 500 else { return }
                if needsPage { _ = NotePageOperations.appendBlankPage(in: &note) }
                let flow = NoteTextFlow(id: UUID().uuidString, format: format, source: clean, fontSizeSp: size,
                                        lineHeight: 1.35, width: note.pageWidth - 32, anchorPageIndex: page,
                                        anchorXInPage: 16, anchorYInPage: 40)
                note.textFlows.append(flow)
            }
        }
        DispatchQueue.main.async { canvas.goToPage(targetPage) }
    }

    private var saveStatusText: String {
        switch savePhase {
        case .dirty: return "未保存"
        case .saving: return "正在保存…"
        case .failed:
            if let lastSavedAt { return "保存失败 · 上次成功 \(lastSavedAt.formatted(date: .omitted, time: .shortened))" }
            return "保存失败 · 尚无成功版本"
        case .saved:
            if let lastSavedAt { return "已保存 \(lastSavedAt.formatted(date: .omitted, time: .shortened))" }
            return "已保存"
        }
    }

    private func configureInitialSaveState() {
        if let canonical = library.notes.first(where: { $0.id == document.id }) {
            lastSavedAt = Date(timeIntervalSince1970: canonical.updatedAt / 1000)
        }
        if let draft = library.pendingDraft(noteID: document.id), draft.document == document {
            editRevision = draft.revision
            persistedDraftRevision = draft.revision
            savePhase = .dirty
        }
    }

    private func documentChanged() {
        let revision = library.nextDraftRevision(noteID: document.id)
        guard revision > 0 else { return }
        editRevision = revision
        savePhase = .dirty
        saveFailure = nil
        enqueueDraft(document, revision: revision)
        pendingSave?.cancel()
        pendingSave = Task { @MainActor in
            do { try await Task.sleep(for: .milliseconds(600)) }
            catch { return }
            _ = await saveCurrentRevision()
        }
    }

    private func enqueueDraft(_ snapshot: NoteDocument, revision: Int) {
        guard revision > 0 else { return }
        library.registerDraftRevision(noteID: snapshot.id, revision: revision)
        pendingDraft?.cancel()
        pendingDraft = Task { @MainActor in
            do {
                let persisted = try await library.persistRegisteredDraft(snapshot, revision: revision)
                guard !Task.isCancelled, persisted, editRevision == revision else { return }
                persistedDraftRevision = revision
            } catch is CancellationError {
            } catch {
                guard editRevision == revision else { return }
                saveFailure = "未保存恢复副本也未能写入：\(error.localizedDescription)"
                savePhase = .failed
            }
        }
    }

    private func beginSave() {
        pendingSave?.cancel()
        Task { @MainActor in _ = await saveCurrentRevision() }
    }

    @MainActor
    private func saveCurrentRevision() async -> Bool {
        if savePhase == .saved { return true }
        let snapshot = document
        let revision = editRevision
        guard revision > 0 else { return true }
        savePhase = .saving
        var draftError: Error?
        do {
            library.registerDraftRevision(noteID: snapshot.id, revision: revision)
            if try await library.persistRegisteredDraft(snapshot, revision: revision), editRevision == revision {
                persistedDraftRevision = revision
            }
        } catch { draftError = error }
        guard editRevision == revision else {
            savePhase = .dirty
            return false
        }
        do {
            try library.save(snapshot)
            await library.markCanonicalSaved(noteID: snapshot.id, revision: revision)
            guard editRevision == revision else { savePhase = .dirty; return false }
            persistedDraftRevision = 0
            lastSavedAt = Date()
            saveFailure = nil
            savePhase = .saved
            return true
        } catch {
            let detail = draftError.map { "恢复副本写入失败：\($0.localizedDescription)\n" } ?? ""
            saveFailure = detail + error.localizedDescription
            savePhase = .failed
            return false
        }
    }

    private func requestClose() {
        canvas.onFinishTextEditing?()
        Task { @MainActor in
            await Task.yield()
            if await saveCurrentRevision() { dismiss() }
            else { showSaveActions = true }
        }
    }

    private func exportEditableSnapshot() {
        do { sharing = ShareArtifact(url: try library.exportURL(for: document)) }
        catch { self.error = error.localizedDescription }
    }

    private func openAI(image: UIImage?) {
        let note = document
        let page = canvas.currentPage
        let bounds = canvas.selectionBounds
        let entries = vault.notes.map {
                NoteToolVaultEntry(id: $0.id, title: $0.title, markdown: $0.markdown,
                                   sourceRevision: $0.sourceUpdatedAt)
            }
        let sourcePDF = pdfURL
        Task { @MainActor in
            let digest = await Task.detached { sourcePDF.flatMap { try? AIConversationDigest.file($0) } }.value
            guard document.id == note.id else { return }
            aiSource = NoteAISourceSnapshot(image: image, note: note, page: page,
                selectionBounds: bounds, vaultEntries: entries, pdfDigest: digest)
            showAI = true
        }
    }

    private func closeAI() {
        showAI = false
        aiSource = nil
    }

    private func commitAITools(_ original: NoteDocument, _ proposed: NoteDocument) throws -> NoteAIToolCommit {
        guard !canvas.hasActiveCanvasInput else { throw NoteAIMutationError.activeCanvasInput }
        guard NoteAIMutation.sourceCompatible(original, document) else {
            throw NoteAIConversationError.changedDocument
        }
        guard let mutation = try NoteAIMutation(original: original, proposed: proposed) else {
            return NoteAIToolCommit(mutation: nil, document: document)
        }
        let candidate = try mutation.applying(to: document, recordedApplied: false)
        canvas.performAIEdit(id: mutation.id, applied: true) { note in
            note.textFlows = candidate.textFlows
            note.pageCount = candidate.pageCount
        }
        guard NoteAIMutation.sourceCompatible(candidate, document),
              (try? AIConversationDigest.document(candidate)) == (try? AIConversationDigest.document(document)) else {
            throw NoteAIConversationError.changedDocument
        }
        return NoteAIToolCommit(mutation: mutation, document: candidate)
    }

    private func performAIToolMutation(_ mutation: NoteAIMutation, apply: Bool) throws {
        guard !canvas.hasActiveCanvasInput else { throw NoteAIMutationError.activeCanvasInput }
        let recorded = canvas.aiApplicationState(for: mutation.id)
        let candidate = try apply
            ? mutation.applying(to: document, recordedApplied: recorded)
            : mutation.undoing(in: document, recordedApplied: recorded)
        canvas.performAIEdit(id: mutation.id, applied: apply) { note in
            note.textFlows = candidate.textFlows
            note.pageCount = candidate.pageCount
        }
    }

    private func locateAIToolMutation(_ mutation: NoteAIMutation) {
        guard let page = mutation.targetPages(in: document).first else { return }
        canvas.goToPage(page)
    }

    private func exportPDF() {
        guard !exportingPDF else { return }
        canvas.onFinishTextEditing?()
        exportingPDF = true
        let snapshot = document
        let snapshotPDFURL = pdfURL
        let operationID = UUID()
        pdfExportOperationID = operationID
        Task { @MainActor in
          defer { exportingPDF = false }
          do {
            let compiledText = try await CompiledTextRenderer.shared.prepareExportSnapshot(document: snapshot)
            guard pdfExportOperationID == operationID, pdfContentMatches(snapshot, document) else {
                self.error = "笔记内容已变化，请重新导出当前版本。"
                return
            }
            let data = try NoteRenderer.exportVerifiedPDF(document: snapshot, pdfURL: snapshotPDFURL,
                                                          compiledText: compiledText)
            let url = FileManager.default.temporaryDirectory.appendingPathComponent("PadNote-\(UUID().uuidString.prefix(8)).pdf")
            try data.write(to: url, options: .atomic)
            sharing = ShareArtifact(url: url)
          } catch {
            guard pdfExportOperationID == operationID, pdfContentMatches(snapshot, document) else {
                self.error = "笔记内容已变化，请重新导出当前版本。"
                return
            }
            let failures = Dictionary(uniqueKeysWithValues: compiledRenderer.failures(document: snapshot).map { ($0.0.id, $0.1) })
            if !failures.isEmpty {
                showPDFRenderRecovery = true
            } else {
                self.error = (error as? LocalizedError)?.errorDescription ?? "PDF 本地排版失败，请重试。"
            }
          }
        }
    }

    private func pdfContentMatches(_ lhs: NoteDocument, _ rhs: NoteDocument) -> Bool {
        lhs.id == rhs.id && lhs.pageWidth == rhs.pageWidth && lhs.pageHeight == rhs.pageHeight &&
        lhs.pageGap == rhs.pageGap && lhs.pageCount == rhs.pageCount && lhs.pdfPageCount == rhs.pdfPageCount &&
        lhs.pageTopologyRevision == rhs.pageTopologyRevision && lhs.strokes == rhs.strokes &&
        lhs.textFlows == rhs.textFlows && lhs.images == rhs.images && lhs.pageStyle == rhs.pageStyle
    }

    private func editFlow(_ flow: NoteTextFlow) {
        editingFlow = flow; showPaperSettings = false
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) { showText = true }
    }

    @ViewBuilder private func renderFailureActions(_ flow: NoteTextFlow) -> some View {
        HStack(spacing: 12) {
            Button("查看/编辑源码") { editFlow(flow) }.accessibilityIdentifier("renderFailureEditSource")
            Button("复制源码") { UIPasteboard.general.string = flow.source }
            Button("重新渲染") {
                compiledRenderer.retry(documentID: document.id, flow: flow) { result in
                    if case .failure(let failure) = result {
                        self.error = (failure as? CompiledTextRenderFailure)?.localizedDescription ?? "重新排版失败。"
                    }
                }
            }.accessibilityIdentifier("renderFailureRetry")
        }.font(.caption)
    }
}

private struct TextFlowEditor: View {
    @Environment(\.dismiss) private var dismiss
    @State private var source: String
    @State private var format: String
    @State private var size: Double
    @State private var preview = false
    let onSave: (String, String, Double) -> Void
    init(flow: NoteTextFlow?, onSave: @escaping (String, String, Double) -> Void) {
        _source = State(initialValue: flow?.source ?? "")
        _format = State(initialValue: flow?.format ?? "latex")
        _size = State(initialValue: flow?.fontSizeSp ?? 18)
        self.onSave = onSave
    }
    var body: some View {
        NavigationStack {
            VStack(spacing: 16) {
                HStack {
                    Picker("格式", selection: $format) { Text("Markdown").tag("markdown"); Text("LaTeX").tag("latex") }.pickerStyle(.segmented)
                    Stepper("\(Int(size)) pt", value: $size, in: 10...32, step: 1).frame(width: 180)
                    Toggle("预览", isOn: $preview).toggleStyle(.button)
                }
                if preview { MathTextView(source: source, fontSize: size, format: format) }
                else {
                    TextEditor(text: $source).font(.system(size: 17, design: .monospaced))
                        .accessibilityIdentifier("textSourceEditor")
                }
            }.padding(24).navigationTitle("页面文字")
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                    ToolbarItem(placement: .confirmationAction) {
                        Button("插入") { onSave(source, format, size); dismiss() }
                            .disabled(source.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                            .accessibilityIdentifier("saveTextButton")
                    }
                }
        }
    }
}
