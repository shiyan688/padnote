import Foundation
import SwiftUI
import UIKit
import PDFKit

/// SwiftUI entry point. The drawing surface is UIKit because UIKit delivers
/// Pencil coalesced touches and pressure with less latency than a SwiftUI
/// gesture.
public struct NoteCanvas: UIViewRepresentable {
    @Binding public var document: NoteDocument
    @ObservedObject public var controller: CanvasController
    public var pdfURL: URL?
    public var onChange: () -> Void

    public init(document: Binding<NoteDocument>, controller: CanvasController,
                pdfURL: URL? = nil, onChange: @escaping () -> Void = {}) {
        _document = document
        self.controller = controller
        self.pdfURL = pdfURL
        self.onChange = onChange
    }

    public func makeCoordinator() -> Coordinator { Coordinator(self) }

    public func makeUIView(context: Context) -> CanvasScrollView {
        let view = CanvasScrollView()
        view.isAccessibilityElement = false
        view.accessibilityIdentifier = "noteCanvas"
        view.accessibilityLabel = "笔记画布"
        context.coordinator.install(on: view, document: document, pdfURL: pdfURL)
        return view
    }

    public func updateUIView(_ view: CanvasScrollView, context: Context) {
        context.coordinator.parent = self
        context.coordinator.update(view, document: document, pdfURL: pdfURL)
    }

    @MainActor
    public final class Coordinator {
        var parent: NoteCanvas
        weak var mountedView: CanvasScrollView?
        private var lastDocument: NoteDocument?
        private var textEditor: CanvasTextEditor?

        init(_ parent: NoteCanvas) { self.parent = parent }

        func install(on view: CanvasScrollView, document: NoteDocument, pdfURL: URL?) {
            mountedView = view
            lastDocument = document
            view.configure(document: document, controller: parent.controller, pdfURL: pdfURL)
            view.surface.derivedPageCountDidChange = { [weak self, weak view] pages in
                guard let self, let view, pages > self.parent.document.pageCount else { return }
                var value = self.parent.document
                value.pageCount = min(NotePageOperations.maximumPageCount, pages)
                self.parent.document = value
                self.lastDocument = value
                self.parent.controller.setDocument(value)
                view.surface.setDocument(value)
                self.parent.onChange()
            }
            view.viewportChange = { [weak self] zoom, centerX, centerY in
                self?.publishViewport(zoom: zoom, centerX: centerX, centerY: centerY)
            }
            parent.controller.attachEditor { [weak self] transform in
                self?.apply(transform)
            }
            parent.controller.mountedSurface = view.surface
            parent.controller.mountedScrollView = view
            parent.controller.mountedPDFURL = pdfURL
            parent.controller.setDocument(document)
            textEditor = CanvasTextEditor(controller: parent.controller)
            let requiredPages = NoteTextLayout.requiredPageCount(document)
            if requiredPages > document.pageCount {
                DispatchQueue.main.async { [weak self] in
                    guard let self, self.parent.document.id == document.id else { return }
                    var value = self.parent.document
                    value.pageCount = requiredPages
                    self.parent.document = value
                    self.lastDocument = value
                    self.parent.controller.setDocument(value)
                    view.surface.setDocument(value)
                    self.parent.onChange()
                }
            }
        }

        func update(_ view: CanvasScrollView, document: NoteDocument, pdfURL: URL?) {
            parent.controller.mountedSurface = view.surface
            parent.controller.mountedScrollView = view
            parent.controller.mountedPDFURL = pdfURL
            view.surface.controller = parent.controller
            view.surface.pdfURL = pdfURL
            view.surface.refreshSnapshot()
            view.updatePanPolicy(parent.controller)
            if lastDocument != document {
                lastDocument = document
                view.surface.setDocument(document)
            }
        }

        private func publishViewport(zoom: Double, centerX: Double, centerY: Double) {
            var value = parent.document
            value.viewportZoom = zoom
            value.viewportCenterX = centerX
            value.viewportCenterY = centerY
            guard value != parent.document else { return }
            parent.document = value
            lastDocument = value
            parent.controller.setDocument(value)
            parent.onChange()
        }

        func apply(_ transform: @escaping DocumentTransform) {
            guard let view = mountedView else { return }
            var value = parent.document
            let command = parent.controller.consumeHistoryCommand()
            let before = value
            transform(&value)
            guard value != before else { return }
            expandPagesForText(&value)
            value.updatedAt = NoteDocument.nowMillis()
            if command == nil { parent.controller.recordEdit(from: before) }
            parent.document = value
            lastDocument = value
            parent.controller.setDocument(value)
            view.surface.setDocument(value)
            parent.onChange()
        }

        private func expandPagesForText(_ document: inout NoteDocument) {
            document.pageCount = NoteTextLayout.requiredPageCount(document)
        }
    }
}

@MainActor
public final class CanvasScrollView: UIScrollView, UIScrollViewDelegate {
    public let surface = CanvasSurface()
    var viewportChange: ((Double, Double, Double) -> Void)?
    private var didSetInitialZoom = false
    private var lastLayoutSize: CGSize = .zero
    private var bottomPullDistance: CGFloat = 0
    private let pullPreview = UILabel()
    private let nextPaper = PullPaperPreview()

    override public init(frame: CGRect) {
        super.init(frame: frame)
        delegate = self
        backgroundColor = UIColor(red: 0.94, green: 0.93, blue: 0.89, alpha: 1)
        alwaysBounceVertical = true
        alwaysBounceHorizontal = true
        minimumZoomScale = 0.45
        maximumZoomScale = 4
        delaysContentTouches = false
        panGestureRecognizer.allowedTouchTypes = [NSNumber(value: UITouch.TouchType.direct.rawValue)]
        surface.isMultipleTouchEnabled = true
        addSubview(surface)
        nextPaper.isUserInteractionEnabled = false
        nextPaper.isHidden = true
        addSubview(nextPaper)
        pullPreview.textAlignment = .center
        pullPreview.font = .systemFont(ofSize: 13, weight: .medium)
        pullPreview.textColor = UIColor(red: 0.12, green: 0.16, blue: 0.20, alpha: 0.8)
        pullPreview.backgroundColor = UIColor(red: 0.96, green: 0.95, blue: 0.91, alpha: 0.96)
        pullPreview.layer.cornerRadius = 10
        pullPreview.clipsToBounds = true
        pullPreview.alpha = 0
        addSubview(pullPreview)
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    func configure(document: NoteDocument, controller: CanvasController, pdfURL: URL?) {
        surface.controller = controller
        surface.pdfURL = pdfURL
        surface.setDocument(document)
        surface.editor = { [weak controller] transform in controller?.performEdit(transform) }
        updatePanPolicy(controller)
        setNeedsLayout()
    }

    override public func layoutSubviews() {
        super.layoutSubviews()
        let worldSize = surface.worldSize
        if surface.bounds.size != worldSize {
            surface.bounds = CGRect(origin: .zero, size: worldSize)
            surface.setNeedsDisplay()
        }
        updateScaledContentGeometry()
        if !didSetInitialZoom, bounds.width > 0, worldSize.width > 0 {
            didSetInitialZoom = true
            let fit = min(1, bounds.width / worldSize.width)
            minimumZoomScale = max(0.001, fit * 0.5)
            maximumZoomScale = max(minimumZoomScale, fit * 20)
            let savedZoom = surface.document?.viewportZoom ?? 1
            zoomScale = max(minimumZoomScale, min(maximumZoomScale, fit * CGFloat(savedZoom)))
            let inset = max(0, (bounds.width - worldSize.width * zoomScale) / 2)
            contentInset = UIEdgeInsets(top: 20, left: inset, bottom: 20, right: inset)
            updateScaledContentGeometry()
            if let document = surface.document {
                let x = CGFloat(document.viewportCenterX) * zoomScale - (bounds.width / 2)
                let y = CGFloat(document.viewportCenterY) * zoomScale - (bounds.height / 2)
                setContentOffset(CGPoint(x: max(-adjustedContentInset.left, x), y: max(-adjustedContentInset.top, y)), animated: false)
            }
        }
        if lastLayoutSize != bounds.size {
            lastLayoutSize = bounds.size
            let inset = max(0, (bounds.width - worldSize.width * zoomScale) / 2)
            contentInset.left = inset; contentInset.right = inset
            surface.setNeedsLayout()
        }
    }

    public func viewForZooming(in scrollView: UIScrollView) -> UIView? { surface }

    func updatePanPolicy(_ controller: CanvasController) {
        // Lasso/selection remains finger-accessible in pencil-only mode;
        // handwriting tools still reject direct touches in CanvasSurface.
        let manipulatesObjects = controller.tool == .lasso || controller.tool == .text
        panGestureRecognizer.minimumNumberOfTouches = controller.pencilOnly && !manipulatesObjects ? 1 : 2
        panGestureRecognizer.maximumNumberOfTouches = 2
    }

    private func updateScaledContentGeometry() {
        let size = CGSize(width: surface.worldSize.width * zoomScale, height: surface.worldSize.height * zoomScale)
        contentSize = size
        surface.center = CGPoint(x: size.width / 2, y: size.height / 2)
    }

    func scrollToPage(_ page: Int, animated: Bool) {
        guard let document = surface.document else { return }
        layoutIfNeeded()
        updateScaledContentGeometry()
        let stride = CGFloat(document.pageHeight + document.pageGap)
        let y = CGFloat(page) * stride * zoomScale - adjustedContentInset.top
        setContentOffset(CGPoint(x: contentOffset.x, y: max(-adjustedContentInset.top, y)), animated: animated)
        if !animated { publishViewport() }
    }

    public func scrollViewDidScroll(_ scrollView: UIScrollView) {
        guard let document = surface.document, scrollView.zoomScale > 0 else { return }
        let maxY = max(-adjustedContentInset.top, contentSize.height - bounds.height + adjustedContentInset.bottom)
        let overscroll = max(0, contentOffset.y - maxY)
        if isDragging, document.pageCount < NotePageOperations.maximumPageCount,
           controllerAtLastPage(document: document), overscroll > 0 {
            bottomPullDistance = overscroll
            let progress = min(1, bottomPullDistance / max(1, bounds.height / 3))
            pullPreview.text = progress >= 1 ? "松开添加下一页" : "继续下拉添加下一页 · \(Int(progress * 100))%"
            pullPreview.alpha = min(1, 0.35 + progress * 0.65)
            pullPreview.frame = CGRect(x: bounds.minX + max(12, (bounds.width - 240) / 2), y: bounds.maxY - 52, width: 240, height: 32)
            nextPaper.configure(document)
            nextPaper.frame = CGRect(x: 0, y: CGFloat(document.pageCount) * CGFloat(document.pageHeight + document.pageGap) * zoomScale,
                                     width: CGFloat(document.pageWidth) * zoomScale, height: CGFloat(document.pageHeight) * zoomScale)
            nextPaper.isHidden = false
        } else if overscroll == 0 || !isDragging {
            bottomPullDistance = 0
            pullPreview.alpha = 0
            nextPaper.isHidden = true
        }
        let worldY = (scrollView.contentOffset.y + (scrollView.bounds.height / 2)) / scrollView.zoomScale
        let stride = max(1, CGFloat(document.pageHeight + document.pageGap))
        surface.controller?.updateVisiblePage(max(0, min(document.pageCount - 1, Int(floor(worldY / stride)))))
    }

    public func scrollViewDidZoom(_ scrollView: UIScrollView) {
        updateScaledContentGeometry()
    }

    public func scrollViewDidEndDecelerating(_ scrollView: UIScrollView) { publishViewport() }
    public func scrollViewDidEndScrollingAnimation(_ scrollView: UIScrollView) { publishViewport() }
    public func scrollViewDidEndDragging(_ scrollView: UIScrollView, willDecelerate decelerate: Bool) {
        finishBottomPull()
        if !decelerate { publishViewport() }
    }
    public func scrollViewDidEndZooming(_ scrollView: UIScrollView, with view: UIView?, atScale scale: CGFloat) { publishViewport() }

    private func publishViewport() {
        guard surface.document != nil, zoomScale > 0 else { return }
        let centerX = (contentOffset.x + (bounds.width / 2)) / zoomScale
        let centerY = (contentOffset.y + (bounds.height / 2)) / zoomScale
        let fittedScale = min(1, bounds.width / max(1, surface.worldSize.width))
        viewportChange?(Double(zoomScale / fittedScale), Double(centerX), Double(centerY))
    }

    private func controllerAtLastPage(document: NoteDocument) -> Bool {
        surface.controller?.currentPage == document.pageCount - 1
    }

    private func finishBottomPull() {
        guard let document = surface.document else { return }
        nextPaper.isHidden = true
        let threshold = max(1, bounds.height / 3)
        guard bottomPullDistance >= threshold,
              document.pageCount < NotePageOperations.maximumPageCount,
              controllerAtLastPage(document: document) else {
            bottomPullDistance = 0
            pullPreview.alpha = 0
            return
        }
        bottomPullDistance = 0
        pullPreview.alpha = 0
        surface.controller?.performEdit { value in value.pageCount += 1 }
        DispatchQueue.main.async { [weak self] in
            guard let self, let page = self.surface.document?.pageCount else { return }
            self.scrollToPage(page - 1, animated: true)
        }
    }
}

@MainActor
private final class PullPaperPreview: UIView {
    private var paper: NoteDocument?
    func configure(_ document: NoteDocument) {
        if paper?.pageStyle == document.pageStyle && paper?.pageWidth == document.pageWidth && paper?.pageHeight == document.pageHeight { return }
        var blank = document
        blank.pageCount = 1; blank.pdfPageCount = 0
        blank.strokes = []; blank.textFlows = []; blank.images = []
        paper = blank
        contentMode = .redraw
        setNeedsDisplay()
    }
    override func draw(_ rect: CGRect) {
        guard let paper, let context = UIGraphicsGetCurrentContext() else { return }
        context.scaleBy(x: bounds.width / paper.pageWidth, y: bounds.height / paper.pageHeight)
        NoteRenderer.drawPage(document: paper, page: 0, pdfURL: nil, in: context)
    }
}

private struct CanvasRenderSnapshot {
    let document: NoteDocument
    let pdfURL: URL?
    let liveStrokes: [InkStroke]
    let lasso: [CGPoint]
    let selectedIDs: Set<String>
    let selectedFlowIDs: Set<String>
    let selectedImageIDs: Set<String>
    let eraserCursor: CGPoint?
    let erasing: Bool
    let eraserRadius: CGFloat
    let moveDelta: CGPoint
    let movingSelection: Bool
    let candidateFlow: NotePageOperations.FlowMoveCandidate?
}

@MainActor
public final class CanvasSurface: UIView {
    private final class PadNoteTiledLayer: CATiledLayer {
        override class func fadeDuration() -> CFTimeInterval { 0 }
    }
    override public class var layerClass: AnyClass { PadNoteTiledLayer.self }
    /// Root-owned host for inline text/image editors. It stays inside the
    /// tiled canvas; callers may add lightweight UIKit views here without
    /// replacing the surface with a full-screen web view.
    public let inlineContentView = UIView(frame: .zero)
    weak var controller: CanvasController?
    var pdfURL: URL?
    var editor: ((@escaping DocumentTransform) -> Void)?
    var derivedPageCountDidChange: ((Int) -> Void)?
    private(set) var document: NoteDocument?
    private(set) var worldSize: CGSize = .zero
    private var activeStroke: InkStroke?
    private var finishedActiveStrokes: [InkStroke] = []
    private var eraserSamples: [CGPoint] = []
    private var lasso: [CGPoint] = []
    private var selectedIDs = Set<String>()
    private var selectedFlowIDs = Set<String>()
    private var selectedImageIDs = Set<String>()
    private var movingSelection = false
    private var movingSingleFlowID: String?
    private var candidateFlow: NotePageOperations.FlowMoveCandidate?
    private var edgeDwellStartedAt: TimeInterval?
    private var edgePageTriggered = false
    private var edgeTask: Task<Void, Never>?
    private var resizingImageID: String?
    private var resizeStartPoint: CGPoint = .zero
    private var resizeStartImage: NoteImage?
    private var resizingFlowID: String?
    private var resizeStartFlowWidth: Double = 0
    private var resizePreview: NoteDocument?
    private var moveStart: CGPoint = .zero
    private var moveDelta: CGPoint = .zero
    private var lastTouchTime: TimeInterval = 0
    private weak var activeTouch: UITouch?
    private var selectionPolygon: [CGPoint] = []
    private var eraserCursor: CGPoint?
    private let snapshotLock = NSLock()
    private var renderSnapshot: CanvasRenderSnapshot?
    private let liveOverlay = CAShapeLayer()

    override public init(frame: CGRect) {
        super.init(frame: frame)
        if let tiled = layer as? CATiledLayer {
            tiled.tileSize = CGSize(width: 512, height: 512)
            tiled.levelsOfDetail = 4
            tiled.levelsOfDetailBias = 3
            tiled.contentsScale = UIScreen.main.scale
        }
        isOpaque = false
        backgroundColor = .clear
        isMultipleTouchEnabled = true
        contentMode = .redraw
        liveOverlay.fillColor = UIColor.clear.cgColor
        liveOverlay.contentsScale = UIScreen.main.scale
        layer.addSublayer(liveOverlay)
        inlineContentView.backgroundColor = .clear
        // Inert until the root installs an editor, so the transparent host
        // never steals Pencil events from the canvas.
        inlineContentView.isUserInteractionEnabled = false
        addSubview(inlineContentView)
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    override public func layoutSubviews() {
        super.layoutSubviews()
        liveOverlay.frame = bounds
        inlineContentView.frame = bounds
        bringSubviewToFront(inlineContentView)
    }

    func setDocument(_ document: NoteDocument) {
        if self.document?.id != document.id { clearSelection() }
        self.document = document
        worldSize = CGSize(width: CGFloat(max(1, document.pageWidth)), height: max(1, CGFloat(document.pageCount) * CGFloat(document.pageHeight + document.pageGap) - CGFloat(document.pageGap)))
        refreshSnapshot()
        superview?.setNeedsLayout()
        setNeedsDisplay()
        CompiledTextRenderer.shared.prepare(document: document) { [weak self] in
            guard let self, let current = self.document else { return }
            let pages = NoteTextLayout.requiredPageCount(current)
            if pages > current.pageCount { self.derivedPageCountDidChange?(pages) }
            self.refreshSnapshot()
            self.setNeedsDisplay()
        }
    }

    func editDocument(_ transform: @escaping DocumentTransform) {
        editor?(transform)
    }

    override public func draw(_ rect: CGRect) {
        snapshotLock.lock()
        let snapshot = renderSnapshot
        snapshotLock.unlock()
        guard let snapshot, let context = UIGraphicsGetCurrentContext() else { return }
        let document = snapshot.document
        let pageHeight = CGFloat(document.pageHeight)
        let stride = pageHeight + CGFloat(document.pageGap)
        let first = max(0, Int(floor(rect.minY / max(1, stride))) - 1)
        let last = min(document.pageCount - 1, Int(floor(rect.maxY / max(1, stride))) + 1)
        for page in first...max(first, last) {
            context.saveGState()
            context.translateBy(x: 0, y: CGFloat(page) * stride)
            NoteRenderer.drawPage(document: document, page: page, pdfURL: snapshot.pdfURL, in: context)
            context.restoreGState()
        }
        for stroke in snapshot.liveStrokes { NoteRenderer.drawStroke(stroke, in: context) }
        if snapshot.movingSelection, snapshot.moveDelta != .zero {
            for stroke in document.strokes where snapshot.selectedIDs.contains(stroke.id) {
                var moved = stroke
                moved.points = moved.points.map { var p = $0; p.x += Double(snapshot.moveDelta.x); p.y += Double(snapshot.moveDelta.y); return p }
                NoteRenderer.drawStroke(moved, in: context)
            }
            context.saveGState()
            if let candidate = snapshot.candidateFlow, snapshot.selectedFlowIDs.contains(candidate.flow.id) {
                for fragment in NoteTextLayout.fragments(candidate.flow, pageHeight: document.pageHeight) {
                    let stride = CGFloat(document.pageHeight + document.pageGap)
                    let moved = NoteTextLayout.Fragment(page: fragment.page,
                        rect: fragment.rect.offsetBy(dx: 0, dy: CGFloat(fragment.page) * stride),
                        text: fragment.text, image: fragment.image)
                    NoteTextLayout.draw(moved, in: context)
                }
            } else {
                context.translateBy(x: snapshot.moveDelta.x, y: snapshot.moveDelta.y)
            }
            for flow in (snapshot.candidateFlow == nil ? document.textFlows : []) where snapshot.selectedFlowIDs.contains(flow.id) {
                for fragment in NoteTextLayout.fragments(flow, pageHeight: document.pageHeight) {
                    var moved = fragment
                    let stride = CGFloat(document.pageHeight + document.pageGap)
                    moved = NoteTextLayout.Fragment(page: fragment.page,
                        rect: fragment.rect.offsetBy(dx: 0, dy: CGFloat(fragment.page) * stride),
                        text: fragment.text, image: fragment.image)
                    NoteTextLayout.draw(moved, in: context)
                }
            }
            context.restoreGState()
        }
        if snapshot.lasso.count > 1 {
            context.saveGState()
            context.setStrokeColor(UIColor(red: 0.16, green: 0.37, blue: 0.66, alpha: 0.9).cgColor)
            context.setLineWidth(1.5)
            context.setLineDash(phase: 0, lengths: [5, 4])
            context.move(to: snapshot.lasso[0])
            snapshot.lasso.dropFirst().forEach { context.addLine(to: $0) }
            context.strokePath()
            context.restoreGState()
        }
        if !snapshot.selectedIDs.isEmpty || !snapshot.selectedFlowIDs.isEmpty || !snapshot.selectedImageIDs.isEmpty {
            context.saveGState()
            context.setStrokeColor(UIColor(red: 0.16, green: 0.37, blue: 0.66, alpha: 0.42).cgColor)
            context.setLineWidth(1)
            for stroke in document.strokes where snapshot.selectedIDs.contains(stroke.id) {
                let points = stroke.points.map { CGPoint(x: CGFloat($0.x), y: CGFloat($0.y)) }
                guard let first = points.first else { continue }
                let bounds = points.dropFirst().reduce(CGRect(origin: first, size: .zero)) { $0.union(CGRect(origin: $1, size: .zero)) }.insetBy(dx: -6, dy: -6)
                context.stroke(bounds)
            }
            for flow in document.textFlows where snapshot.selectedFlowIDs.contains(flow.id) {
                for bounds in flowRects(flow, document: document) {
                    context.stroke(bounds.insetBy(dx: -6, dy: -6))
                    if snapshot.selectedFlowIDs.count == 1 { context.fillEllipse(in: CGRect(x: bounds.maxX - 6, y: bounds.maxY - 6, width: 12, height: 12)) }
                }
            }
            for image in document.images where snapshot.selectedImageIDs.contains(image.id) {
                let bounds = imageRect(image, document: document)
                context.stroke(bounds.insetBy(dx: -6, dy: -6))
                if snapshot.selectedImageIDs.count == 1 { context.fillEllipse(in: CGRect(x: bounds.maxX - 6, y: bounds.maxY - 6, width: 12, height: 12)) }
            }
            context.restoreGState()
        }
        if let eraserCursor = snapshot.eraserCursor, snapshot.erasing {
            context.setStrokeColor(UIColor(red: 0.16, green: 0.37, blue: 0.66, alpha: 0.5).cgColor)
            context.setLineWidth(1)
            let radius = snapshot.eraserRadius
            context.strokeEllipse(in: CGRect(x: eraserCursor.x - radius, y: eraserCursor.y - radius, width: radius * 2, height: radius * 2))
        }
    }

    func refreshSnapshot() {
        guard var document = resizePreview ?? document else { return }
        if let candidateFlow { document.pageCount = max(document.pageCount, candidateFlow.pageCount) }
        let size = CGSize(width: document.pageWidth,
                          height: Double(document.pageCount) * (document.pageHeight + document.pageGap) - document.pageGap)
        if worldSize != size { worldSize = size; superview?.setNeedsLayout() }
        var visibleLive = finishedActiveStrokes + (activeStroke.map { [$0] } ?? [])
        if var shape = activeStroke, let tool = controller?.tool,
           tool == .rectangle || tool == .line || tool == .ellipse {
            normalizeShapeIfNeeded(&shape, tool: tool)
            visibleLive = finishedActiveStrokes + [shape]
        }
        let snapshot = CanvasRenderSnapshot(document: document, pdfURL: pdfURL, liveStrokes: visibleLive,
                                            lasso: lasso, selectedIDs: selectedIDs,
                                            selectedFlowIDs: selectedFlowIDs, selectedImageIDs: selectedImageIDs,
                                            eraserCursor: eraserCursor,
                                            erasing: controller?.tool == .eraser,
                                            eraserRadius: min(64, max(8, CGFloat(controller?.strokeWidth ?? 8))) / 2,
                                            moveDelta: moveDelta, movingSelection: movingSelection,
                                            candidateFlow: candidateFlow)
        snapshotLock.lock(); renderSnapshot = snapshot; snapshotLock.unlock()
        updateLiveOverlay(snapshot)
    }

    private func updateLiveOverlay(_ snapshot: CanvasRenderSnapshot) {
        liveOverlay.frame = bounds
        let path = CGMutablePath()
        if let stroke = snapshot.liveStrokes.last, let first = stroke.points.first {
            path.move(to: CGPoint(x: CGFloat(first.x), y: CGFloat(first.y)))
            for point in stroke.points.dropFirst() { path.addLine(to: CGPoint(x: CGFloat(point.x), y: CGFloat(point.y))) }
            liveOverlay.strokeColor = UIColor(padNoteCanvasHex: stroke.color).withAlphaComponent(stroke.highlighter ? 0.42 : 1).cgColor
            liveOverlay.lineWidth = CGFloat(stroke.baseWidth)
            liveOverlay.lineDashPattern = nil
        } else if snapshot.lasso.count > 1 {
            path.move(to: snapshot.lasso[0]); snapshot.lasso.dropFirst().forEach { path.addLine(to: $0) }
            liveOverlay.strokeColor = UIColor(red: 0.16, green: 0.37, blue: 0.66, alpha: 0.85).cgColor
            liveOverlay.lineWidth = 1.5
            liveOverlay.lineDashPattern = [5, 4]
        } else { liveOverlay.strokeColor = UIColor.clear.cgColor; liveOverlay.lineDashPattern = nil }
        liveOverlay.path = path
    }

    override public func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard let touch = touches.first, let document, let controller else { return }
        controller.onFinishTextEditing?()
        if (event?.allTouches?.count ?? touches.count) > 1 { cancelGesture(); return }
        let isPencil = touch.type == .pencil
        if controller.pencilOnly && !isPencil && controller.tool != .lasso && controller.tool != .text { return }
        activeTouch = touch
        let point = touch.location(in: self)
        lastTouchTime = touch.timestamp
        if controller.tool == .text {
            controller.onTextTap?(point)
            activeTouch = nil
            return
        }
        if controller.tool == .lasso, touch.tapCount >= 2,
           let flow = document.textFlows.first(where: { selectedFlowIDs.contains($0.id) && flowRects($0, document: document).contains(where: { $0.contains(point) }) }) {
            controller.onEditText?(flow.id)
            activeTouch = nil
            return
        }
        switch controller.tool {
        case .pen, .highlighter, .rectangle, .line, .ellipse:
            activeStroke = isPaperPoint(point, document: document) ? newStroke(at: point, touch: touch, highlighter: controller.tool == .highlighter) : nil
        case .eraser:
            eraserSamples = [point]
            eraserCursor = point
        case .lasso:
            if selectedImageIDs.count == 1, let image = document.images.first(where: { selectedImageIDs.contains($0.id) }),
               imageRect(image, document: document).insetBy(dx: -14, dy: -14).contains(point),
               distance(point, CGPoint(x: imageRect(image, document: document).maxX, y: imageRect(image, document: document).maxY)) < 22 {
                resizingImageID = image.id; resizeStartPoint = point; resizeStartImage = image
            } else if selectedFlowIDs.count == 1,
                      let flow = document.textFlows.first(where: { selectedFlowIDs.contains($0.id) }),
                      flowRects(flow, document: document).contains(where: { distance(point, CGPoint(x: $0.maxX, y: $0.maxY)) < 22 }) {
                resizingFlowID = flow.id; resizeStartPoint = point; resizeStartFlowWidth = flow.width
            } else if (!selectedIDs.isEmpty || !selectedFlowIDs.isEmpty || !selectedImageIDs.isEmpty) && pointIsInsideSelection(point, document: document) {
                movingSelection = true
                movingSingleFlowID = selectedFlowIDs.count == 1 && selectedIDs.isEmpty && selectedImageIDs.isEmpty ? selectedFlowIDs.first : nil
                moveStart = point
                moveDelta = .zero
                candidateFlow = nil
                edgeDwellStartedAt = nil
                edgePageTriggered = false
            } else {
                selectedIDs.removeAll()
                lasso = [point]
            }
        case .text:
            break
        }
        refreshSnapshot()
        setNeedsDisplay()
    }

    override public func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard let touch = touches.first, touch === activeTouch, controller != nil else { return }
        if (event?.allTouches?.count ?? touches.count) > 1 { cancelGesture(); return }
        let samples = (event?.coalescedTouches(for: touch) ?? [touch]).filter { $0.timestamp > lastTouchTime }
        for sample in samples { append(sample) }
        refreshSnapshot()
        setNeedsDisplay()
    }

    override public func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard let touch = touches.first, touch === activeTouch, let controller else { return }
        append(touch)
        edgeTask?.cancel(); edgeTask = nil
        switch controller.tool {
        case .pen, .highlighter, .rectangle, .line, .ellipse:
            if var stroke = activeStroke {
                normalizeShapeIfNeeded(&stroke, tool: controller.tool)
                finishedActiveStrokes.append(stroke)
            }
            let strokes = finishedActiveStrokes
            finishedActiveStrokes.removeAll()
            if !strokes.isEmpty { editDocument { document in document.strokes.append(contentsOf: strokes) } }
            activeStroke = nil
        case .eraser:
            let samples = eraserSamples
            if !samples.isEmpty { editDocument { [self] document in self.erase(samples, from: &document) } }
            eraserSamples.removeAll()
            eraserCursor = nil
        case .lasso:
            if movingSelection {
                let delta = moveDelta
                if abs(delta.x) + abs(delta.y) > 0.5 {
                    if let candidate = candidateFlow, movingSingleFlowID != nil {
                        if let original = document?.textFlows.first(where: { $0.id == candidate.flow.id }), let document {
                            let dx = candidate.flow.anchorXInPage - original.anchorXInPage
                            let dy = Double(candidate.flow.anchorPageIndex - original.anchorPageIndex) * (document.pageHeight + document.pageGap)
                                + candidate.flow.anchorYInPage - original.anchorYInPage
                            selectionPolygon = selectionPolygon.map { CGPoint(x: $0.x + dx, y: $0.y + dy) }
                        }
                        editDocument { document in
                            if candidate.pageCount > document.pageCount { document.pageCount = candidate.pageCount }
                            if let index = document.textFlows.firstIndex(where: { $0.id == candidate.flow.id }) {
                                document.textFlows[index] = candidate.flow
                            }
                        }
                    } else {
                        editDocument { [self] document in self.moveSelection(delta, in: &document) }
                    }
                    if let document { updateSelectionImage(document: document) }
                }
                movingSelection = false
                movingSingleFlowID = nil; candidateFlow = nil; edgeDwellStartedAt = nil; edgePageTriggered = false
            } else if let id = resizingImageID, let image = resizeStartImage {
                let delta = CGPoint(x: touch.location(in: self).x - resizeStartPoint.x,
                                    y: touch.location(in: self).y - resizeStartPoint.y)
                editDocument { document in
                    guard let index = document.images.firstIndex(where: { $0.id == id }) else { return }
                    let pageTop = CGFloat(document.images[index].page) * CGFloat(document.pageHeight + document.pageGap)
                    let maxWidth = CGFloat(document.pageWidth) - CGFloat(image.x)
                    let maxHeight = CGFloat(document.pageHeight) - CGFloat(image.y)
                    let aspect = max(0.01, image.width / max(0.01, image.height))
                    let proposedWidth = max(8, min(maxWidth, image.width + delta.x))
                    let proposedHeight = min(maxHeight, max(8, proposedWidth / aspect))
                    let width = min(proposedWidth, proposedHeight * aspect)
                    document.images[index].width = Double(width)
                    document.images[index].height = Double(width / aspect)
                    _ = pageTop // keep the page-local coordinate explicit
                }
                resizingImageID = nil; resizeStartImage = nil
                resizePreview = nil
            } else if let id = resizingFlowID {
                let delta = touch.location(in: self).x - resizeStartPoint.x
                editDocument { document in
                    guard let index = document.textFlows.firstIndex(where: { $0.id == id }) else { return }
                    document.textFlows[index].width = min(max(document.pageWidth - document.textFlows[index].anchorXInPage, 120),
                                                         max(120, self.resizeStartFlowWidth + Double(delta)))
                }
                resizingFlowID = nil
                resizePreview = nil
            } else if lasso.count > 2 {
                finishLasso()
            }
        case .text:
            break
        }
        activeTouch = nil
        refreshSnapshot()
        setNeedsDisplay()
    }

    override public func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) {
        activeTouch = nil
        cancelGesture()
    }

    func clearSelection() {
        controller?.setSelectedTextFlows([], fontSize: nil)
        selectedIDs.removeAll(); selectedFlowIDs.removeAll(); selectedImageIDs.removeAll(); lasso.removeAll(); selectionPolygon.removeAll(); controller?.setSelection(nil); controller?.setSelectionBounds(nil); refreshSnapshot(); setNeedsDisplay()
    }

    func deleteSelection(from document: inout NoteDocument) {
        guard !selectedIDs.isEmpty || !selectedFlowIDs.isEmpty || !selectedImageIDs.isEmpty else { return }
        document.strokes.removeAll { selectedIDs.contains($0.id) }
        document.textFlows.removeAll { selectedFlowIDs.contains($0.id) }
        document.images.removeAll { selectedImageIDs.contains($0.id) }
        clearSelection()
    }

    func duplicateSelection(in document: inout NoteDocument) {
        let selected = document.strokes.filter { selectedIDs.contains($0.id) }
        let selectedFlows = document.textFlows.filter { selectedFlowIDs.contains($0.id) }
        let selectedImages = document.images.filter { selectedImageIDs.contains($0.id) }
        var copies: [InkStroke] = []
        for stroke in selected {
            var copy = stroke
            copy.id = UUID().uuidString
            copy.createdAt = Date().timeIntervalSince1970 * 1000
            copy.points = copy.points.map { point in var p = point; p.x += 16; p.y += 16; return p }
            copies.append(copy)
        }
        document.strokes.append(contentsOf: copies)
        selectedIDs = Set(copies.map(\.id))
        var flowCopies: [NoteTextFlow] = []
        for flow in selectedFlows {
            var copy = flow; copy.id = UUID().uuidString
            copy.anchorXInPage = min(max(0, copy.anchorXInPage + 16), max(0, document.pageWidth - copy.width))
            copy.anchorYInPage = min(max(0, copy.anchorYInPage + 16), max(0, document.pageHeight - copy.fontSizeSp * 2))
            flowCopies.append(copy)
        }
        document.textFlows.append(contentsOf: flowCopies); selectedFlowIDs = Set(flowCopies.map(\.id))
        var imageCopies: [NoteImage] = []
        for image in selectedImages {
            var copy = image; copy.id = UUID().uuidString
            copy.x = min(max(0, copy.x + 16), max(0, document.pageWidth - copy.width))
            copy.y = min(max(0, copy.y + 16), max(0, document.pageHeight - copy.height))
            imageCopies.append(copy)
        }
        document.images.append(contentsOf: imageCopies); selectedImageIDs = Set(imageCopies.map(\.id))
        selectionPolygon = selectionPolygon.map { CGPoint(x: $0.x + 16, y: $0.y + 16) }
        updateSelectionImage(document: document)
    }

    private func newStroke(at point: CGPoint, touch: UITouch, highlighter: Bool) -> InkStroke {
        let requested = controller?.strokeWidth ?? 2.5
        let width = highlighter ? min(32, max(4, requested)) : min(8, max(0.3, requested))
        let color = highlighter ? (controller?.highlighterColor ?? "#69FFCC33") : (controller?.inkColor ?? "#FF1F2933")
        var stroke = InkStroke(id: UUID().uuidString, color: color, baseWidth: width, createdAt: Date().timeIntervalSince1970 * 1000, highlighter: highlighter, points: [])
        stroke.points = [pointValue(point, touch: touch)]
        return stroke
    }

    private func normalizeShapeIfNeeded(_ stroke: inout InkStroke, tool: CanvasTool) {
        guard tool == .rectangle || tool == .line || tool == .ellipse,
              let first = stroke.points.first, let last = stroke.points.last else { return }
        let left = min(first.x, last.x), right = max(first.x, last.x)
        let top = min(first.y, last.y), bottom = max(first.y, last.y)
        if tool == .line { stroke.points = [first, last]; return }
        if tool == .rectangle {
            stroke.points = [InkPoint(x: left, y: top, pressure: last.pressure, timestamp: last.timestamp),
                             InkPoint(x: right, y: top, pressure: last.pressure, timestamp: last.timestamp),
                             InkPoint(x: right, y: bottom, pressure: last.pressure, timestamp: last.timestamp),
                             InkPoint(x: left, y: bottom, pressure: last.pressure, timestamp: last.timestamp),
                             InkPoint(x: left, y: top, pressure: last.pressure, timestamp: last.timestamp)]
            return
        }
        let cx = (left + right) / 2, cy = (top + bottom) / 2
        let rx = max(0.5, (right - left) / 2), ry = max(0.5, (bottom - top) / 2)
        stroke.points = (0...24).map { i in
            let angle = Double(i) * Double.pi * 2 / 24
            return InkPoint(x: cx + rx * cos(angle), y: cy + ry * sin(angle), pressure: last.pressure, timestamp: last.timestamp)
        }
    }

    private func append(_ touch: UITouch) {
        let point = touch.location(in: self)
        lastTouchTime = touch.timestamp
        switch controller?.tool {
        case .pen, .highlighter, .rectangle, .line, .ellipse:
            guard let document, isPaperPoint(point, document: document) else { return }
            let value = pointValue(point, touch: touch)
            if let last = activeStroke?.points.last,
               pageForWorldY(last.y, document: document) != pageForWorldY(value.y, document: document) {
                if let activeStroke { finishedActiveStrokes.append(activeStroke) }
                activeStroke = newStroke(at: point, touch: touch, highlighter: controller?.tool == .highlighter)
            } else { activeStroke?.points.append(value) }
        case .eraser:
            eraserSamples.append(point)
            eraserCursor = point
        case .lasso:
            if let id = resizingImageID, let original = resizeStartImage, var preview = document,
               let index = preview.images.firstIndex(where: { $0.id == id }) {
                let aspect = original.width / max(0.01, original.height)
                let limit = min(preview.pageWidth - original.x, (preview.pageHeight - original.y) * aspect)
                let width = min(limit, max(8, original.width + point.x - resizeStartPoint.x))
                preview.images[index].width = width; preview.images[index].height = width / aspect
                resizePreview = preview
            } else if let id = resizingFlowID, var preview = document,
                      let index = preview.textFlows.firstIndex(where: { $0.id == id }) {
                preview.textFlows[index].width = min(preview.pageWidth - preview.textFlows[index].anchorXInPage,
                    max(120, resizeStartFlowWidth + point.x - resizeStartPoint.x))
                resizePreview = preview
            } else if movingSelection {
                moveDelta = CGPoint(x: point.x - moveStart.x, y: point.y - moveStart.y)
                if let id = movingSingleFlowID, let document,
                   let flow = document.textFlows.first(where: { $0.id == id }) {
                    candidateFlow = NotePageOperations.candidateFlowMove(flow, delta: moveDelta, in: document)
                    if let scroll = superview as? UIScrollView {
                        let converted = convert(point, to: scroll)
                        let screenPoint = CGPoint(x: converted.x - scroll.bounds.minX, y: converted.y - scroll.bounds.minY)
                        let atEdge = screenPoint.y <= 32 || screenPoint.y >= scroll.bounds.height - 32
                        if atEdge {
                            if edgeDwellStartedAt == nil && !edgePageTriggered {
                                edgeDwellStartedAt = touch.timestamp
                                let direction = screenPoint.y <= 32 ? -1 : 1
                                edgeTask?.cancel()
                                edgeTask = Task { [weak self] in
                                    do { try await Task.sleep(for: .milliseconds(500)) } catch { return }
                                    self?.autoPageSelection(direction: direction)
                                }
                            }
                        } else {
                            edgeTask?.cancel(); edgeTask = nil
                            edgeDwellStartedAt = nil; edgePageTriggered = false
                        }
                    }
                }
            }
            else { lasso.append(point) }
        default: break
        }
        refreshSnapshot()
    }

    private func autoPageSelection(direction: Int) {
        guard movingSelection, !edgePageTriggered, let id = movingSingleFlowID,
              let current = candidateFlow, let document, let touch = activeTouch,
              let original = document.textFlows.first(where: { $0.id == id }),
              let scroll = superview as? CanvasScrollView else { return }
        let target = scroll.surface.controller.map { $0.currentPage + direction } ?? (current.flow.anchorPageIndex + direction)
        guard target >= 0, target <= document.pageCount, target < NotePageOperations.maximumPageCount else { return }
        var moved = current.flow
        let pageDelta = target - (scroll.surface.controller?.currentPage ?? current.flow.anchorPageIndex)
        moved.anchorPageIndex = max(0, min(document.pageCount, NotePageOperations.maximumPageCount - 1, moved.anchorPageIndex + pageDelta))
        candidateFlow = NotePageOperations.FlowMoveCandidate(flow: moved, pageCount: max(current.pageCount, target + 1))
        edgePageTriggered = true
        refreshSnapshot()
        scroll.layoutIfNeeded()
        scroll.scrollToPage(target, animated: false)
        let delta = CGPoint(x: moved.anchorXInPage - original.anchorXInPage,
            y: Double(moved.anchorPageIndex - original.anchorPageIndex) * (document.pageHeight + document.pageGap)
                + moved.anchorYInPage - original.anchorYInPage)
        let point = touch.location(in: self)
        moveStart = CGPoint(x: point.x - delta.x, y: point.y - delta.y)
        moveDelta = delta
        setNeedsDisplay()
    }

    private func pointValue(_ point: CGPoint, touch: UITouch) -> InkPoint {
        InkPoint(x: point.x, y: point.y, pressure: max(0.01, Double(touch.force > 0 ? touch.force / max(0.001, touch.maximumPossibleForce) : 0.5)), timestamp: (Date().timeIntervalSince1970 + touch.timestamp - ProcessInfo.processInfo.systemUptime) * 1000)
    }

    private func distance(_ a: CGPoint, _ b: CGPoint) -> CGFloat { hypot(a.x - b.x, a.y - b.y) }

    private func isPaperPoint(_ point: CGPoint, document: NoteDocument) -> Bool {
        guard point.x >= 0, point.x <= CGFloat(document.pageWidth) else { return false }
        let stride = CGFloat(document.pageHeight + document.pageGap)
        guard stride > 0 else { return false }
        let page = floor(point.y / stride)
        let localY = point.y - CGFloat(page) * stride
        return page >= 0 && page < CGFloat(document.pageCount) && localY >= 0 && localY <= CGFloat(document.pageHeight)
    }

    private func pageForWorldY(_ y: Double, document: NoteDocument) -> Int {
        let stride = document.pageHeight + document.pageGap
        return stride > 0 ? max(0, Int(floor(y / stride))) : 0
    }

    private func finishLasso() {
        guard let document else { return }
        selectionPolygon = lasso
        selectedIDs = Set(document.strokes.filter { strokeIntersects($0, polygon: selectionPolygon) }.map(\.id))
        selectedFlowIDs = Set(document.textFlows.filter { flowIntersects($0, document: document, polygon: selectionPolygon) }.map(\.id))
        selectedImageIDs = Set(document.images.filter { imageIntersects($0, document: document, polygon: selectionPolygon) }.map(\.id))
        updateSelectionImage(document: document)
        lasso.removeAll()
    }

    private func updateSelectionImage(document: NoteDocument) {
        let flows = document.textFlows.filter { selectedFlowIDs.contains($0.id) }
        controller?.setSelectedTextFlows(Set(flows.map(\.id)), fontSize: flows.first?.fontSizeSp)
        controller?.setSelection(NoteRenderer.renderSelection(document: document, polygon: selectionPolygon, pdfURL: pdfURL))
        let world = CGRect(x: 0, y: 0, width: CGFloat(document.pageWidth), height: CGFloat(document.pageCount) * CGFloat(document.pageHeight + document.pageGap) - CGFloat(document.pageGap))
        let bounds = selectionPolygon.reduce(CGRect.null) { $0.union(CGRect(origin: $1, size: .zero)) }.intersection(world)
        controller?.setSelectionBounds(bounds.isNull ? nil : bounds)
    }

    private func strokeIntersects(_ stroke: InkStroke, polygon: [CGPoint]) -> Bool {
        guard polygon.count > 2 else { return false }
        if stroke.points.contains(where: { CanvasGeometry.pointInPolygon(CGPoint(x: CGFloat($0.x), y: CGFloat($0.y)), polygon) }) { return true }
        guard stroke.points.count > 1 else { return false }
        for index in 1..<stroke.points.count {
            let a = CGPoint(x: CGFloat(stroke.points[index - 1].x), y: CGFloat(stroke.points[index - 1].y)), b = CGPoint(x: CGFloat(stroke.points[index].x), y: CGFloat(stroke.points[index].y))
            for edge in 0..<polygon.count where CanvasGeometry.segmentsIntersect(a, b, polygon[edge], polygon[(edge + 1) % polygon.count]) { return true }
        }
        return false
    }

    private func erase(_ samples: [CGPoint], from document: inout NoteDocument) {
        guard !samples.isEmpty else { return }
        let radius = min(64, max(8, controller?.strokeWidth ?? 16)) / 2
        document.strokes = document.strokes.flatMap { stroke in
            InkEraser.erase(stroke: stroke, path: samples, radius: radius + stroke.baseWidth / 2)
        }
    }

    private func moveSelection(_ delta: CGPoint, in document: inout NoteDocument) {
        let constrained = constrainedDelta(delta, document: document)
        let selected = document.strokes.indices.filter { selectedIDs.contains(document.strokes[$0].id) }
        for index in selected {
            document.strokes[index].points = document.strokes[index].points.map { point in var p = point; p.x += constrained.x; p.y += constrained.y; return p }
        }
        for index in document.textFlows.indices where selectedFlowIDs.contains(document.textFlows[index].id) {
            document.textFlows[index].anchorXInPage += Double(constrained.x)
            document.textFlows[index].anchorYInPage += Double(constrained.y)
        }
        for index in document.images.indices where selectedImageIDs.contains(document.images[index].id) {
            document.images[index].x += Double(constrained.x); document.images[index].y += Double(constrained.y)
        }
        selectionPolygon = selectionPolygon.map { CGPoint(x: $0.x + constrained.x, y: $0.y + constrained.y) }
    }

    private func pointIsInsideSelection(_ point: CGPoint, document: NoteDocument) -> Bool {
        if document.strokes.filter({ selectedIDs.contains($0.id) }).contains(where: { strokeBounds($0).insetBy(dx: -12, dy: -12).contains(point) }) { return true }
        if document.images.filter({ selectedImageIDs.contains($0.id) }).contains(where: { imageRect($0, document: document).insetBy(dx: -12, dy: -12).contains(point) }) { return true }
        return document.textFlows.filter({ selectedFlowIDs.contains($0.id) }).contains { flow in
            flowRects(flow, document: document).contains { $0.insetBy(dx: -12, dy: -12).contains(point) }
        }
    }

    private func flowRects(_ flow: NoteTextFlow, document: NoteDocument) -> [CGRect] {
        let stride = CGFloat(document.pageHeight + document.pageGap)
        return NoteTextLayout.fragments(flow, pageHeight: document.pageHeight).map {
            $0.rect.offsetBy(dx: 0, dy: CGFloat($0.page) * stride)
        }
    }

    private func flowRect(_ flow: NoteTextFlow, document: NoteDocument) -> CGRect {
        let fragments = NoteTextLayout.fragments(flow, pageHeight: document.pageHeight)
        guard let first = fragments.first else {
            let top = CGFloat(flow.anchorPageIndex) * CGFloat(document.pageHeight + document.pageGap)
            return CGRect(x: flow.anchorXInPage, y: top + flow.anchorYInPage, width: flow.width, height: max(24, flow.fontSizeSp * flow.lineHeight * 2))
        }
        let stride = CGFloat(document.pageHeight + document.pageGap)
        return fragments.dropFirst().reduce(first.rect.offsetBy(dx: 0, dy: CGFloat(first.page) * stride)) { result, fragment in
            result.union(fragment.rect.offsetBy(dx: 0, dy: CGFloat(fragment.page) * stride))
        }
    }
    private func imageRect(_ image: NoteImage, document: NoteDocument) -> CGRect {
        let top = CGFloat(image.page) * CGFloat(document.pageHeight + document.pageGap)
        return CGRect(x: image.x, y: top + image.y, width: image.width, height: image.height)
    }
    private func flowIntersects(_ flow: NoteTextFlow, document: NoteDocument, polygon: [CGPoint]) -> Bool {
        flowRects(flow, document: document).contains { rectIntersects($0, polygon: polygon) }
    }
    private func imageIntersects(_ image: NoteImage, document: NoteDocument, polygon: [CGPoint]) -> Bool { rectIntersects(imageRect(image, document: document), polygon: polygon) }
    private func rectIntersects(_ rect: CGRect, polygon: [CGPoint]) -> Bool {
        if polygon.contains(where: { rect.contains($0) }) { return true }
        let corners = [CGPoint(x: rect.minX, y: rect.minY), CGPoint(x: rect.maxX, y: rect.minY), CGPoint(x: rect.maxX, y: rect.maxY), CGPoint(x: rect.minX, y: rect.maxY)]
        return corners.contains(where: { CanvasGeometry.pointInPolygon($0, polygon) })
    }
    private func constrainedDelta(_ delta: CGPoint, document: NoteDocument) -> CGPoint {
        var bounds = CGRect.null
        for stroke in document.strokes where selectedIDs.contains(stroke.id) { bounds = bounds.union(strokeBounds(stroke)) }
        for flow in document.textFlows where selectedFlowIDs.contains(flow.id) { bounds = bounds.union(flowRect(flow, document: document)) }
        for image in document.images where selectedImageIDs.contains(image.id) { bounds = bounds.union(imageRect(image, document: document)) }
        guard !bounds.isNull else { return delta }
        let page = max(0, min(document.pageCount - 1, Int(floor(Double(bounds.midY) / (document.pageHeight + document.pageGap)))))
        let top = CGFloat(page) * CGFloat(document.pageHeight + document.pageGap)
        let minX = -bounds.minX, maxX = CGFloat(document.pageWidth) - bounds.maxX
        let minY = top - bounds.minY, maxY = top + CGFloat(document.pageHeight) - bounds.maxY
        return CGPoint(x: min(max(delta.x, minX), maxX), y: min(max(delta.y, minY), maxY))
    }

    private func strokeBounds(_ stroke: InkStroke) -> CGRect {
        stroke.points.reduce(CGRect.null) { $0.union(CGRect(x: CGFloat($1.x), y: CGFloat($1.y), width: 0, height: 0)) }
    }

    private func cancelGesture() { edgeTask?.cancel(); edgeTask = nil; activeTouch = nil; activeStroke = nil; finishedActiveStrokes.removeAll(); eraserSamples.removeAll(); lasso.removeAll(); movingSelection = false; movingSingleFlowID = nil; candidateFlow = nil; edgeDwellStartedAt = nil; edgePageTriggered = false; resizingImageID = nil; resizingFlowID = nil; resizeStartImage = nil; resizePreview = nil; refreshSnapshot(); setNeedsDisplay() }
}

public enum CanvasGeometry {
    public static func pointInPolygon(_ point: CGPoint, _ polygon: [CGPoint]) -> Bool {
    var inside = false
    var j = polygon.count - 1
    for i in 0..<polygon.count {
        let a = polygon[i], b = polygon[j]
        if (a.y > point.y) != (b.y > point.y) && point.x < (b.x - a.x) * (point.y - a.y) / (b.y - a.y) + a.x { inside.toggle() }
        j = i
    }
    return inside
    }

    public static func segmentsIntersect(_ a: CGPoint, _ b: CGPoint, _ c: CGPoint, _ d: CGPoint) -> Bool {
    guard max(min(a.x, b.x), min(c.x, d.x)) <= min(max(a.x, b.x), max(c.x, d.x)),
          max(min(a.y, b.y), min(c.y, d.y)) <= min(max(a.y, b.y), max(c.y, d.y)) else { return false }
    func cross(_ a: CGPoint, _ b: CGPoint, _ c: CGPoint) -> CGFloat { (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x) }
    let ab1 = cross(a, b, c), ab2 = cross(a, b, d), cd1 = cross(c, d, a), cd2 = cross(c, d, b)
    return ((ab1 >= 0 && ab2 <= 0) || (ab1 <= 0 && ab2 >= 0)) && ((cd1 >= 0 && cd2 <= 0) || (cd1 <= 0 && cd2 >= 0))
    }

    public static func distanceToPolyline(_ point: CGPoint, _ path: [CGPoint]) -> CGFloat {
        guard !path.isEmpty else { return .greatestFiniteMagnitude }
        if path.count == 1 { return hypot(point.x - path[0].x, point.y - path[0].y) }
        return path.dropFirst().enumerated().map { index, end in pointSegmentDistance(point, path[index], end) }.min() ?? .greatestFiniteMagnitude
    }

    public static func segmentDistance(_ a: CGPoint, _ b: CGPoint, _ path: [CGPoint]) -> CGFloat {
        guard path.count > 1 else { return min(distanceToPolyline(a, path), distanceToPolyline(b, path)) }
        return path.dropFirst().enumerated().map { index, end in
            min(pointSegmentDistance(a, path[index], end), pointSegmentDistance(b, path[index], end), segmentToSegmentDistance(a, b, path[index], end))
        }.min() ?? .greatestFiniteMagnitude
    }

    private static func pointSegmentDistance(_ p: CGPoint, _ a: CGPoint, _ b: CGPoint) -> CGFloat {
        let dx = b.x - a.x, dy = b.y - a.y
        let t = max(0, min(1, ((p.x - a.x) * dx + (p.y - a.y) * dy) / max(0.000001, dx * dx + dy * dy)))
        return hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy))
    }

    private static func segmentToSegmentDistance(_ a: CGPoint, _ b: CGPoint, _ c: CGPoint, _ d: CGPoint) -> CGFloat {
        if segmentsIntersect(a, b, c, d) { return 0 }
        return min(pointSegmentDistance(a, c, d), pointSegmentDistance(b, c, d), pointSegmentDistance(c, a, b), pointSegmentDistance(d, a, b))
    }
}

private extension UIColor {
    convenience init(padNoteCanvasHex value: String) {
        var hex = value.replacingOccurrences(of: "#", with: "")
        if hex.count == 6 { hex = "FF" + hex }
        var number: UInt64 = 0
        Scanner(string: hex).scanHexInt64(&number)
        self.init(red: CGFloat((number >> 16) & 0xff) / 255,
                  green: CGFloat((number >> 8) & 0xff) / 255,
                  blue: CGFloat(number & 0xff) / 255,
                  alpha: CGFloat((number >> 24) & 0xff) / 255)
    }
}
