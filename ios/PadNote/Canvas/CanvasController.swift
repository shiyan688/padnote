import Foundation
import SwiftUI
import UIKit

public enum CanvasTool: String, CaseIterable, Sendable {
    case pen
    case highlighter
    case eraser
    case lasso
    case rectangle
    case line
    case ellipse
    case text
}

public typealias DocumentTransform = (inout NoteDocument) -> Void

/// The small state bridge used by the SwiftUI toolbar and the UIKit canvas.
/// The document itself remains the source of truth; this object only owns
/// transient selection and history state.
@MainActor
public final class CanvasController: ObservableObject {
    @Published public var tool: CanvasTool = .pen {
        didSet {
            guard oldValue != tool else { return }
            onFinishTextEditing?()
            strokeWidth = width(for: tool)
            mountedSurface?.clearSelection()
        }
    }
    @Published public var inkColor: String = "#FF1F2933"
        { didSet {
            let value = Self.validColor(inkColor, fallback: "#FF1F2933")
            if inkColor != value { inkColor = value; return }
            persistSettings()
        } }
    @Published public var highlighterColor: String = "#69FFCC33"
        { didSet {
            let value = Self.validColor(highlighterColor, fallback: "#69FFCC33")
            if highlighterColor != value { highlighterColor = value; return }
            persistSettings()
        } }
    /// Compatibility surface for existing toolbars. Each tool remembers its
    /// own width; changing tools restores that tool's last value.
    @Published public var strokeWidth: Double = 2.5 {
        didSet {
            let range: ClosedRange<Double> = tool == .highlighter ? 4...32 : (tool == .eraser ? 8...64 : 0.3...8)
            let value = min(range.upperBound, max(range.lowerBound, strokeWidth))
            if strokeWidth != value { strokeWidth = value; return }
            switch tool {
            case .highlighter: highlighterWidth = value
            case .eraser: eraserWidth = value
            default: penWidth = value
            }
            persistSettings()
        }
    }
    @Published public var penWidth: Double = 2.5 { didSet { persistSettings() } }
    @Published public var highlighterWidth: Double = 12 { didSet { persistSettings() } }
    @Published public var eraserWidth: Double = 16 { didSet { persistSettings() } }
    @Published public var pencilOnly: Bool = true { didSet { persistSettings() } }
    @Published public private(set) var canUndo = false
    @Published public private(set) var canRedo = false
    @Published public private(set) var hasSelection = false
    @Published public private(set) var currentPage = 0
    @Published public private(set) var selectedTextFlowIDs: Set<String> = []
    @Published public private(set) var selectedTextFontSize: Double?
    @Published public private(set) var aiHistoryRevision = 0
    @Published public private(set) var hasActiveCanvasInput = false
    public private(set) var selectionBounds: CGRect?

    /// Root-owned inline editor hooks. Canvas only reports taps; it never
    /// installs a full-screen web view or otherwise owns text editing UI.
    public var onTextTap: ((CGPoint) -> Void)?
    public var onEditText: ((String) -> Void)?
    public var onFinishTextEditing: (() -> Void)?

    public private(set) var selectionImage: UIImage?

    private struct HistoryEntry {
        let document: NoteDocument
        let aiApplications: [UUID: Bool]
    }
    private var undoStack: [HistoryEntry] = []
    private var redoStack: [HistoryEntry] = []
    private var aiApplications: [UUID: Bool] = [:]
    private var pendingAIApplication: (id: UUID, applied: Bool)?
    private var editingGroupDepth = 0
    private var editingGroupRecorded = false
    private let settingsDefaults: UserDefaults
    private var loadingSettings = false
    private var editHandler: ((@escaping DocumentTransform) -> Void)?
    fileprivate var pendingHistoryCommand: HistoryCommand?
    weak var mountedSurface: CanvasSurface?
    weak var mountedScrollView: CanvasScrollView?
    var mountedDocument: NoteDocument?
    var mountedPDFURL: URL?

    enum HistoryCommand { case undo, redo }

    public init(defaults: UserDefaults = .standard) {
        settingsDefaults = defaults
        loadingSettings = true
        penWidth = Self.validWidth(defaults.double(forKey: "penWidth"), fallback: 2.5, range: 0.3...8)
        highlighterWidth = Self.validWidth(defaults.double(forKey: "highlighterWidth"), fallback: 12, range: 4...32)
        eraserWidth = Self.validWidth(defaults.double(forKey: "eraserWidth"), fallback: 16, range: 8...64)
        inkColor = Self.validColor(defaults.string(forKey: "inkColor"), fallback: "#FF1F2933")
        highlighterColor = Self.validColor(defaults.string(forKey: "highlighterColor"), fallback: "#69FFCC33")
        pencilOnly = defaults.object(forKey: "pencilOnly") as? Bool ?? true
        strokeWidth = penWidth
        loadingSettings = false
    }

    private static func validWidth(_ value: Double, fallback: Double, range: ClosedRange<Double>) -> Double {
        guard value.isFinite, value > 0 else { return fallback }
        return min(range.upperBound, max(range.lowerBound, value))
    }

    private static func validColor(_ value: String?, fallback: String) -> String {
        guard let value, value.range(of: "^#[0-9A-Fa-f]{8}$", options: .regularExpression) != nil else { return fallback }
        return value.uppercased()
    }

    private func persistSettings() {
        guard !loadingSettings else { return }
        settingsDefaults.set(penWidth, forKey: "penWidth")
        settingsDefaults.set(highlighterWidth, forKey: "highlighterWidth")
        settingsDefaults.set(eraserWidth, forKey: "eraserWidth")
        settingsDefaults.set(Self.validColor(inkColor, fallback: "#FF1F2933"), forKey: "inkColor")
        settingsDefaults.set(Self.validColor(highlighterColor, fallback: "#69FFCC33"), forKey: "highlighterColor")
        settingsDefaults.set(pencilOnly, forKey: "pencilOnly")
    }

    private func width(for tool: CanvasTool) -> Double {
        switch tool {
        case .highlighter: return highlighterWidth
        case .eraser: return eraserWidth
        default: return penWidth
        }
    }

    func attachEditor(_ handler: @escaping (@escaping DocumentTransform) -> Void) {
        editHandler = handler
    }

    /// Lets a root view (for example, its add-page or text-flow action) use
    /// the same undo history as the ink gestures.
    public func performEdit(_ transform: @escaping DocumentTransform) {
        editHandler?(transform)
    }

    public func performAIEdit(id: UUID, applied: Bool, _ transform: @escaping DocumentTransform) {
        guard pendingAIApplication == nil else { return }
        pendingAIApplication = (id, applied)
        guard let editHandler else { pendingAIApplication = nil; return }
        editHandler(transform)
    }

    public func aiApplicationState(for id: UUID) -> Bool? { aiApplications[id] }

    /// Coalesces a stream of small edits (for example inline text layout
    /// updates) into one undo entry. Nested callers share the outer group.
    public func beginEditingGroup() {
        if editingGroupDepth == 0 { editingGroupRecorded = false }
        editingGroupDepth += 1
    }

    public func endEditingGroup() {
        guard editingGroupDepth > 0 else { return }
        editingGroupDepth -= 1
        if editingGroupDepth == 0 { editingGroupRecorded = false }
    }

    public func undo() {
        onFinishTextEditing?()
        guard !undoStack.isEmpty else { return }
        pendingHistoryCommand = .undo
        editHandler? { [weak self] document in
            guard let self, let previous = self.undoStack.popLast() else { return }
            self.redoStack.append(.init(document: document, aiApplications: self.aiApplications))
            if self.redoStack.count > 30 { self.redoStack.removeFirst(self.redoStack.count - 30) }
            document = previous.document
            self.replaceAIApplications(previous.aiApplications)
        }
    }

    public func redo() {
        onFinishTextEditing?()
        guard !redoStack.isEmpty else { return }
        pendingHistoryCommand = .redo
        editHandler? { [weak self] document in
            guard let self, let next = self.redoStack.popLast() else { return }
            self.undoStack.append(.init(document: document, aiApplications: self.aiApplications))
            if self.undoStack.count > 30 { self.undoStack.removeFirst(self.undoStack.count - 30) }
            document = next.document
            self.replaceAIApplications(next.aiApplications)
        }
    }

    public func deleteSelection() {
        guard hasSelection else { return }
        editHandler? { [weak self] document in self?.mountedSurface?.deleteSelection(from: &document) }
    }

    public func duplicateSelection() {
        guard hasSelection else { return }
        editHandler? { [weak self] document in self?.mountedSurface?.duplicateSelection(in: &document) }
    }

    public func deletePage(_ index: Int) {
        performEdit { document in _ = NotePageOperations.delete(page: index, in: &document) }
    }

    public func duplicatePage(_ index: Int) {
        performEdit { document in _ = NotePageOperations.duplicate(page: index, in: &document) }
    }

    public func movePage(from: Int, to: Int) {
        performEdit { document in _ = NotePageOperations.move(page: from, to: to, in: &document) }
    }

    public func clearSelection() {
        mountedSurface?.clearSelection()
        hasSelection = false
        selectionImage = nil
        setSelectedTextFlows([], fontSize: nil)
        NotificationCenter.default.post(name: .canvasSelectionDidChange, object: nil)
    }

    public func goToPage(_ index: Int) {
        let next = max(0, index)
        if currentPage != next {
            currentPage = next
            NotificationCenter.default.post(name: .canvasPageDidChange, object: currentPage)
        }
        mountedScrollView?.scrollToPage(next, animated: true)
    }

    func updateVisiblePage(_ index: Int) {
        let next = max(0, index)
        guard currentPage != next else { return }
        currentPage = next
    }

    func setActiveCanvasInput(_ active: Bool) {
        if hasActiveCanvasInput != active { hasActiveCanvasInput = active }
    }

    public func pageImage(_ index: Int) -> UIImage? {
        guard let document = mountedDocument else { return nil }
        return NoteRenderer.renderPage(document: document, page: index, pdfURL: mountedPDFURL)
    }

    func setSelection(_ image: UIImage?) {
        selectionImage = image
        if hasSelection != (image != nil) { hasSelection = image != nil }
    }

    func setSelectionBounds(_ bounds: CGRect?) { selectionBounds = bounds }

    func setSelectedTextFlows(_ ids: Set<String>, fontSize: Double?) {
        selectedTextFlowIDs = ids
        selectedTextFontSize = fontSize
    }

    public func adjustSelectedTextFontSize(by delta: Double) {
        guard !selectedTextFlowIDs.isEmpty else { return }
        let ids = selectedTextFlowIDs
        performEdit { document in
            for index in document.textFlows.indices where ids.contains(document.textFlows[index].id) {
                document.textFlows[index].fontSizeSp = min(32, max(10, document.textFlows[index].fontSizeSp + delta))
            }
        }
    }

    func setDocument(_ document: NoteDocument) {
        mountedDocument = document
        let selected = document.textFlows.filter { selectedTextFlowIDs.contains($0.id) }
        setSelectedTextFlows(Set(selected.map(\.id)), fontSize: selected.first?.fontSizeSp)
        if currentPage >= document.pageCount { updateVisiblePage(max(0, document.pageCount - 1)) }
        if canUndo != !undoStack.isEmpty { canUndo = !undoStack.isEmpty }
        if canRedo != !redoStack.isEmpty { canRedo = !redoStack.isEmpty }
    }

    func recordEdit(from document: NoteDocument) {
        guard pendingHistoryCommand == nil else { return }
        if editingGroupDepth > 0 {
            guard !editingGroupRecorded else { return }
            editingGroupRecorded = true
        }
        undoStack.append(.init(document: document, aiApplications: aiApplications))
        if undoStack.count > 30 { undoStack.removeFirst(undoStack.count - 30) }
        redoStack.removeAll(keepingCapacity: true)
        canUndo = true
        canRedo = false
    }

    func consumeHistoryCommand() -> HistoryCommand? {
        defer { pendingHistoryCommand = nil }
        return pendingHistoryCommand
    }

    func finishPendingAIApplication(didChange: Bool) {
        defer { pendingAIApplication = nil }
        guard didChange, let pendingAIApplication else { return }
        aiApplications[pendingAIApplication.id] = pendingAIApplication.applied
        aiHistoryRevision &+= 1
    }

    private func replaceAIApplications(_ value: [UUID: Bool]) {
        guard aiApplications != value else { return }
        aiApplications = value
        aiHistoryRevision &+= 1
    }
}

extension Notification.Name {
    static let canvasSelectionDidChange = Notification.Name("PadNote.canvasSelectionDidChange")
    static let canvasPageDidChange = Notification.Name("PadNote.canvasPageDidChange")
}
