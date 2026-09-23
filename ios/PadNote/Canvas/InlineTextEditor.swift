import UIKit
import SwiftUI

@MainActor
final class CanvasTextEditor {
    private weak var controller: CanvasController?
    private var input: InlineTextInput?
    private var pending: Task<Void, Never>?
    private var draft: NoteTextFlow?
    private var didStartGroup = false

    init(controller: CanvasController) {
        self.controller = controller
        controller.onTextTap = { [weak self] in self?.open(at: $0) }
        controller.onEditText = { [weak self] in self?.open(id: $0) }
        controller.onFinishTextEditing = { [weak self] in self?.finish() }
    }

    private func open(at point: CGPoint) {
        guard let controller, let note = controller.mountedDocument else { return }
        let stride = note.pageHeight + note.pageGap
        if let flow = note.textFlows.first(where: { flow in
            NoteTextLayout.fragments(flow, pageHeight: note.pageHeight).contains {
                $0.rect.offsetBy(dx: 0, dy: Double($0.page) * stride).contains(point)
            }
        }) { open(id: flow.id); return }
        finish()
        let page = Int(point.y / stride)
        let y = point.y - Double(page) * stride
        guard page >= 0, page < note.pageCount, y >= 0, y < note.pageHeight else { return }
        let x = min(max(24, point.x), max(24, note.pageWidth - 184))
        let flow = NoteTextFlow(format: "latex", width: min(440, note.pageWidth - x - 24),
            anchorPageIndex: page, anchorXInPage: x, anchorYInPage: min(y, note.pageHeight - 64))
        show(flow)
    }

    private func open(id: String) {
        finish()
        guard let flow = controller?.mountedDocument?.textFlows.first(where: { $0.id == id }) else { return }
        show(flow)
    }

    private func show(_ flow: NoteTextFlow) {
        guard let controller, let surface = controller.mountedSurface, let note = controller.mountedDocument else { return }
        draft = flow
        let width = max(240, min(note.pageWidth - 48, flow.width))
        let pageTop = Double(flow.anchorPageIndex) * (note.pageHeight + note.pageGap)
        let height = min(360, note.pageHeight - 48)
        let panel = InlineTextInput(flow: flow)
        panel.frame = CGRect(x: min(flow.anchorXInPage, note.pageWidth - width - 24),
            y: pageTop + min(flow.anchorYInPage, note.pageHeight - height - 24), width: width, height: height)
        panel.onChange = { [weak self] source, format, size, spacing in
            guard let self else { return }
            self.draft?.source = String(source.prefix(100_000))
            self.draft?.format = format
            self.draft?.fontSizeSp = size
            self.draft?.lineHeight = spacing
            self.pending?.cancel()
            self.pending = Task { @MainActor [weak self] in
                do { try await Task.sleep(nanoseconds: 90_000_000) } catch { return }
                self?.publish()
            }
        }
        panel.onDone = { [weak self] in self?.finish() }
        input = panel
        surface.addSubview(panel)
        panel.sourceView.becomeFirstResponder()
        surface.superview?.bringSubviewToFront(surface)
    }

    private func publish() {
        guard let controller, let flow = draft else { return }
        guard !flow.source.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ||
            controller.mountedDocument?.textFlows.contains(where: { $0.id == flow.id }) == true else { return }
        if !didStartGroup { controller.beginEditingGroup(); didStartGroup = true }
        controller.performEdit { note in
            if let index = note.textFlows.firstIndex(where: { $0.id == flow.id }) { note.textFlows[index] = flow }
            else { note.textFlows.append(flow) }
        }
        input?.updatePreview(flow)
    }

    func finish() {
        pending?.cancel(); pending = nil
        if draft != nil { publish() }
        input?.sourceView.resignFirstResponder()
        input?.removeFromSuperview(); input = nil; draft = nil
        if didStartGroup { controller?.endEditingGroup(); didStartGroup = false }
    }
}

/// Exists only during editing. Completed text has no hit-testing view over it,
/// so users can continue to write directly on and around compiled text.
@MainActor
private final class InlineTextInput: UIView, UITextViewDelegate {
    let sourceView = UITextView()
    var onChange: ((String, String, Double, Double) -> Void)?
    var onDone: (() -> Void)?
    private let format = UISegmentedControl(items: ["LaTeX", "Markdown"])
    private var size: Double
    private var spacing: Double
    private var preview: UIHostingController<MathTextView>
    private let top = UIStackView()
    private let bottom = UIStackView()

    init(flow: NoteTextFlow) {
        size = flow.fontSizeSp; spacing = flow.lineHeight
        preview = UIHostingController(rootView: MathTextView(source: flow.source, fontSize: flow.fontSizeSp, format: flow.format))
        super.init(frame: .zero)
        backgroundColor = UIColor.white
        layer.borderColor = UIColor(red: 0.16, green: 0.37, blue: 0.66, alpha: 1).cgColor
        layer.borderWidth = 1
        accessibilityIdentifier = "inlineTextEditor"
        format.selectedSegmentIndex = flow.format == "markdown" ? 1 : 0
        format.addTarget(self, action: #selector(changed), for: .valueChanged)
        top.axis = .horizontal; top.spacing = 8
        let done = UIButton(type: .system)
        done.setTitle("完成", for: .normal)
        done.accessibilityIdentifier = "finishInlineText"
        done.addTarget(self, action: #selector(finish), for: .touchUpInside)
        top.addArrangedSubview(format); top.addArrangedSubview(done)
        sourceView.text = flow.source
        sourceView.font = .monospacedSystemFont(ofSize: 15, weight: .regular)
        sourceView.textColor = UIColor(red: 0.12, green: 0.16, blue: 0.20, alpha: 1)
        sourceView.backgroundColor = .white
        sourceView.autocorrectionType = .no
        sourceView.smartQuotesType = .no; sourceView.smartDashesType = .no
        sourceView.delegate = self
        sourceView.accessibilityIdentifier = "inlineTextSource"
        bottom.axis = .horizontal; bottom.spacing = 8; bottom.distribution = .fillEqually
        for (title, action) in [("A−", #selector(smaller)), ("A+", #selector(larger)), ("行距−", #selector(tighter)), ("行距+", #selector(looser))] {
            let button = UIButton(type: .system); button.setTitle(title, for: .normal)
            button.addTarget(self, action: action, for: .touchUpInside)
            bottom.addArrangedSubview(button)
        }
        preview.view.backgroundColor = .clear
        [top, sourceView, preview.view!, bottom].forEach(addSubview)
    }
    required init?(coder: NSCoder) { fatalError() }
    override func layoutSubviews() {
        super.layoutSubviews()
        top.frame = CGRect(x: 8, y: 4, width: bounds.width - 16, height: 40)
        sourceView.frame = CGRect(x: 4, y: 48, width: bounds.width - 8, height: (bounds.height - 96) * 0.5)
        preview.view.frame = CGRect(x: 4, y: sourceView.frame.maxY, width: bounds.width - 8, height: bounds.height - sourceView.frame.maxY - 44)
        bottom.frame = CGRect(x: 8, y: bounds.height - 44, width: bounds.width - 16, height: 40)
    }
    func updatePreview(_ flow: NoteTextFlow) { preview.rootView = MathTextView(source: flow.source, fontSize: flow.fontSizeSp, format: flow.format) }
    func textViewDidChange(_ textView: UITextView) { changed() }
    @objc private func changed() { onChange?(sourceView.text, format.selectedSegmentIndex == 0 ? "latex" : "markdown", size, spacing) }
    @objc private func finish() { onDone?() }
    @objc private func smaller() { size = max(10, size - 1); changed() }
    @objc private func larger() { size = min(32, size + 1); changed() }
    @objc private func tighter() { spacing = max(1.1, spacing - 0.1); changed() }
    @objc private func looser() { spacing = min(2, spacing + 0.1); changed() }
}
