import SwiftUI
import UIKit

@MainActor
private final class MathTextRenderModel: ObservableObject {
    @Published var units: [CompiledTextCache.Unit] = []
    @Published var failure: CompiledTextRenderFailure?
    @Published var rendering = false
    @Published var displaySource = ""
    @Published var isLocalCopy = false

    let owner = UUID().uuidString
    private var originalSource = ""
    private var fontSize = 16.0
    private var format = "markdown"
    private var width = 320.0
    private var requestGeneration = 0

    func update(source: String, fontSize: Double, format: String, width: Double) async {
        if source != originalSource {
            originalSource = source
            displaySource = source
            isLocalCopy = false
        }
        self.fontSize = fontSize
        self.format = format
        self.width = max(240, min(1200, width))
        await render(retry: false)
    }

    func saveLocalCopy(_ source: String) async {
        displaySource = source
        isLocalCopy = source != originalSource
        await render(retry: true)
    }

    func retry() async { await render(retry: true) }

    func cancel() {
        requestGeneration += 1
        CompiledTextRenderer.shared.cancelPreview(owner: owner)
        rendering = false
    }

    private func render(retry: Bool) async {
        requestGeneration += 1
        let generation = requestGeneration
        guard !displaySource.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            units = []; failure = nil; rendering = false; return
        }
        rendering = true
        failure = nil
        let flow = NoteTextFlow(id: "preview-\(owner)", format: format, source: displaySource,
                                fontSizeSp: max(10, min(32, fontSize)), lineHeight: 1.55,
                                width: width, anchorPageIndex: 0, anchorXInPage: 0, anchorYInPage: 0)
        do {
            let output = try await CompiledTextRenderer.shared.renderPreview(flow: flow, owner: owner, retry: retry)
            guard generation == requestGeneration, !Task.isCancelled else { return }
            units = output
            rendering = false
        } catch is CancellationError {
            guard generation == requestGeneration else { return }
            rendering = false
        } catch {
            guard generation == requestGeneration, !Task.isCancelled else { return }
            units = []
            failure = error as? CompiledTextRenderFailure
                ?? CompiledTextRenderFailure(kind: .invalidResponse, stage: "preview")
            rendering = false
        }
    }
}

/// Offline rich-text preview backed by the same bounded renderer as paper and
/// PDF. Editing here changes a local display copy only; it never alters the
/// model answer, wire history, or a note's persisted TextFlow.
public struct MathTextView: View {
    public var source: String
    public var fontSize: Double
    public var format: String
    @StateObject private var model = MathTextRenderModel()
    @State private var showingSource = false
    @State private var showingEditor = false
    @State private var editBuffer = ""
    @State private var availableWidth = 320.0

    public init(source: String, fontSize: Double = 16, format: String = "markdown") {
        self.source = source
        self.fontSize = fontSize
        self.format = format
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            if let failure = model.failure {
                VStack(alignment: .leading, spacing: 8) {
                    Label(failure.localizedDescription, systemImage: "exclamationmark.triangle")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(Color.orange)
                        .accessibilityIdentifier("renderFailureMessage")
                    ScrollView([.vertical, .horizontal]) {
                        Text(model.displaySource).font(.system(size: 13, design: .monospaced))
                            .textSelection(.enabled).frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
            } else if !model.units.isEmpty {
                ScrollView([.vertical, .horizontal]) {
                    VStack(alignment: .leading, spacing: 0) {
                        ForEach(Array(model.units.enumerated()), id: \.offset) { _, unit in
                            Image(uiImage: unit.image).resizable().scaledToFit()
                                .frame(width: unit.size.width, alignment: .leading)
                                .accessibilityHidden(true)
                        }
                    }.frame(maxWidth: .infinity, alignment: .leading)
                }
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(model.displaySource)
            } else if model.rendering {
                ProgressView("正在本地排版…").frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                ScrollView { Text(model.displaySource).textSelection(.enabled).frame(maxWidth: .infinity, alignment: .leading) }
            }

            ViewThatFits(in: .horizontal) {
                actionRow
                ScrollView(.horizontal, showsIndicators: false) { actionRow }
            }.font(.system(size: 12))
        }
        .background(GeometryReader { proxy in
            Color.clear.onAppear { availableWidth = Double(proxy.size.width) }
                .onChange(of: proxy.size.width) { _, value in availableWidth = Double(value) }
        })
        .task(id: "\(source)|\(fontSize)|\(format)|\(Int(availableWidth.rounded()))") {
            // Geometry can publish several intermediate widths while a sheet
            // or split view settles. Only the stable width should enter the
            // shared WebKit queue.
            do { try await Task.sleep(nanoseconds: 120_000_000) }
            catch { return }
            guard !Task.isCancelled else { return }
            await model.update(source: source, fontSize: fontSize, format: format, width: availableWidth)
        }
        .onDisappear { model.cancel() }
        .sheet(isPresented: $showingSource) {
            NavigationStack {
                ScrollView([.vertical, .horizontal]) {
                    Text(model.displaySource).font(.system(size: 15, design: .monospaced))
                        .textSelection(.enabled).frame(maxWidth: .infinity, alignment: .leading).padding(24)
                }.navigationTitle("渲染源码")
                    .toolbar { ToolbarItem(placement: .confirmationAction) { Button("完成") { showingSource = false } } }
            }
        }
        .sheet(isPresented: $showingEditor) {
            NavigationStack {
                TextEditor(text: $editBuffer).font(.system(size: 15, design: .monospaced)).padding(16)
                    .navigationTitle("编辑本地显示副本")
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) { Button("取消") { showingEditor = false } }
                        ToolbarItem(placement: .confirmationAction) {
                            Button("保存并重试") {
                                showingEditor = false
                                Task { await model.saveLocalCopy(editBuffer) }
                            }.disabled(editBuffer.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                        }
                    }
            }
        }
    }

    private var actionRow: some View {
        HStack(spacing: 12) {
            Button("查看源码") { showingSource = true }.accessibilityIdentifier("renderViewSource")
            Button("复制源码") { UIPasteboard.general.string = model.displaySource }
                .accessibilityIdentifier("renderCopySource")
            Button("编辑本地显示副本") { editBuffer = model.displaySource; showingEditor = true }
                .accessibilityIdentifier("renderEditLocalCopy")
            if model.failure != nil {
                Button("重新渲染") { Task { await model.retry() } }
                    .accessibilityIdentifier("renderRetry")
            }
            if model.isLocalCopy {
                Text("本地显示副本，不改变原回答").font(.caption).foregroundStyle(.secondary)
            }
        }
    }
}
