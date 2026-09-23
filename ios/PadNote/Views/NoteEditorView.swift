import SwiftUI
import UniformTypeIdentifiers

struct NoteEditorView: View {
    @EnvironmentObject private var library: NoteLibrary
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    @State private var document: NoteDocument
    @StateObject private var canvas = CanvasController()
    @StateObject private var vault = VaultLibrary()
    @State private var pendingSave: Task<Void, Never>?
    @State private var saveStatus = "已保存"
    @State private var error: String?
    @State private var showText = false
    @State private var editingFlow: NoteTextFlow?
    @State private var showAI = false
    @State private var aiImage: UIImage?
    @State private var showPages = false
    @State private var showPaperSettings = false
    @State private var sharing: ShareArtifact?
    @State private var showImageImport = false
    @State private var showDigitize = false
    @State private var showWidth = false
    @State private var showColors = false
    @State private var exportingPDF = false
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
                    NoteCanvas(document: $document, controller: canvas, pdfURL: pdfURL, onChange: scheduleSave)
                        .accessibilityIdentifier("noteCanvas")
                    if canvas.hasSelection {
                        HStack(spacing: 16) {
                            Button("问 AI", systemImage: "sparkles") { aiImage = canvas.selectionImage; showAI = true }
                                .disabled(canvas.selectionImage == nil)
                            Button("复制", systemImage: "doc.on.doc") { canvas.duplicateSelection() }
                            Button("删除", systemImage: "trash", role: .destructive) { canvas.deleteSelection() }
                            Button("取消") { canvas.clearSelection() }
                        }
                        .buttonStyle(.bordered).controlSize(.large)
                        .padding(12).background(.regularMaterial, in: RoundedRectangle(cornerRadius: 20))
                        .padding(16)
                    }
                    if showAI {
                        FloatingAIPanel(onClose: { showAI = false }) {
                            AIAssistantView(image: aiImage, noteContext: "", embedded: true, onClose: { showAI = false },
                                toolNote: document, toolPage: canvas.currentPage, selectionBounds: canvas.selectionBounds,
                                vaultEntries: vault.notes.map { NoteToolVaultEntry(id: $0.id, title: $0.title, markdown: $0.markdown) },
                                onToolCommit: commitAITools) { _ in }
                        }
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
                    Text(saveStatus).accessibilityIdentifier("saveStatus")
                }
                .font(.system(size: 12)).foregroundStyle(PadTheme.secondary)
                .padding(.horizontal, 24).padding(.vertical, 8)
                .background(PadTheme.surface)
            }
            .background(PadTheme.surface)
            .navigationTitle(document.title).navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button { saveNow(); if error == nil { dismiss() } } label: { Label("书架", systemImage: "chevron.left") }
                        .accessibilityIdentifier("backToShelf")
                }
                ToolbarItemGroup(placement: .topBarTrailing) {
                    Button { canvas.undo() } label: { Image(systemName: "arrow.uturn.backward") }
                        .disabled(!canvas.canUndo).accessibilityLabel("撤销").accessibilityIdentifier("undoButton")
                    Button { canvas.redo() } label: { Image(systemName: "arrow.uturn.forward") }
                        .disabled(!canvas.canRedo).accessibilityLabel("重做")
                    Button { aiImage = canvas.selectionImage; showAI = true } label: { Image(systemName: "sparkles") }
                        .accessibilityLabel("AI 助手")
                    Menu {
                        Button("保存", systemImage: "checkmark.circle") { saveNow() }
                        Button("导出可编辑笔记", systemImage: "square.and.arrow.up") {
                            saveNow()
                            do { sharing = ShareArtifact(url: try library.exportURL(for: document)) }
                            catch { self.error = error.localizedDescription }
                        }
                        Button("导出 PDF", systemImage: "doc.richtext") { exportPDF() }
                        Button("整理到知识库", systemImage: "text.book.closed") { showDigitize = true }
                        Button("插入图片", systemImage: "photo") { showImageImport = true }
                        Button("管理文字", systemImage: "text.alignleft") { showPaperSettings = true }
                        Button("清空当前笔记", systemImage: "trash", role: .destructive) { confirmClear = true }
                            .disabled(document.strokes.isEmpty && document.textFlows.isEmpty && document.images.isEmpty)
                    } label: { Image(systemName: "ellipsis.circle") }.accessibilityLabel("更多操作")
                }
            }
        }
        .onChange(of: document) { _, _ in scheduleSave() }
        .onChange(of: scenePhase) { _, phase in if phase != .active { saveNow() } }
        .onDisappear { canvas.onFinishTextEditing?(); saveNow() }
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
                    canvas.performEdit { if $0.pageCount < 500 { $0.pageCount += 1 } }
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
                    Button {
                        editingFlow = flow; showPaperSettings = false
                        DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) { showText = true }
                    } label: {
                        VStack(alignment: .leading, spacing: 8) {
                            Text(flow.source).lineLimit(3)
                            Text("第 \(flow.anchorPageIndex + 1) 页 · \(flow.format)").font(.caption).foregroundStyle(PadTheme.secondary)
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
                if needsPage { note.pageCount += 1 }
                let flow = NoteTextFlow(id: UUID().uuidString, format: format, source: clean, fontSizeSp: size,
                                        lineHeight: 1.35, width: note.pageWidth - 96, anchorPageIndex: page,
                                        anchorXInPage: 48, anchorYInPage: 56)
                note.textFlows.append(flow)
            }
        }
        DispatchQueue.main.async { canvas.goToPage(targetPage) }
    }

    private func scheduleSave() {
        pendingSave?.cancel(); saveStatus = "正在保存…"
        pendingSave = Task { @MainActor in
            do { try await Task.sleep(nanoseconds: 600_000_000) } catch { return }
            saveNow()
        }
    }

    private func commitAITools(_ original: NoteDocument, _ proposed: NoteDocument) throws {
        guard document.id == original.id, document.strokes == original.strokes,
              document.textFlows == original.textFlows, document.images == original.images,
              document.pageCount == original.pageCount else { throw NoteAIConversationError.changedDocument }
        let validated = try proposed.validated()
        canvas.performEdit { note in
            note.textFlows = validated.textFlows
            note.pageCount = validated.pageCount
        }
    }

    private func saveNow() {
        pendingSave?.cancel()
        do { try library.save(document); saveStatus = "已保存" }
        catch { saveStatus = "保存失败"; self.error = error.localizedDescription }
    }

    private func exportPDF() {
        guard !exportingPDF else { return }
        canvas.onFinishTextEditing?()
        exportingPDF = true
        let snapshot = document
        Task { @MainActor in
          defer { exportingPDF = false }
          do {
            try await CompiledTextRenderer.shared.prepareAndWait(document: snapshot)
            var rendered = snapshot
            rendered.pageCount = NoteTextLayout.requiredPageCount(rendered)
            let data = NoteRenderer.exportPDF(document: rendered, pdfURL: pdfURL)
            let url = FileManager.default.temporaryDirectory.appendingPathComponent("PadNote-\(UUID().uuidString.prefix(8)).pdf")
            try data.write(to: url, options: .atomic)
            sharing = ShareArtifact(url: url)
          } catch { self.error = error.localizedDescription }
        }
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
