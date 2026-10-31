import SwiftUI
import UIKit

/// A read-only page bitmap viewport. All gestures and the selection marker use
/// the same page-sized UIView coordinate space; viewport state is never persisted.
struct PaperPreviewViewport: View {
    enum Command { case fitPage, fitWidth, zoomIn, zoomOut, chooseCenter }

    let image: UIImage
    let paperSize: CGSize
    let viewportHeight: CGFloat
    let pageIdentity: String
    let selectionEnabled: Bool
    let selectedPoint: CGPoint?
    let onSelectPaperPoint: (CGPoint) -> Void

    @State private var command: Command = .fitPage
    @State private var commandSequence = 0
    @State private var debugViewportDiagnostic = "尚无触点记录"

    private var exposesUITestGeometry: Bool {
#if DEBUG
        ProcessInfo.processInfo.arguments.contains("--uitesting-agent-result-paper")
#else
        false
#endif
    }

    var body: some View {
        VStack(spacing: 8) {
            HStack(spacing: 8) {
                commandButton("整页", id: "agentResultPaperFitPage", command: .fitPage)
                commandButton("页宽", id: "agentResultPaperFitWidth", command: .fitWidth)
                commandButton("−", id: "agentResultPaperZoomOut", command: .zoomOut)
                    .accessibilityLabel("缩小纸面")
                commandButton("+", id: "agentResultPaperZoomIn", command: .zoomIn)
                    .accessibilityLabel("放大纸面")
                if selectionEnabled {
                    commandButton("选中心", id: "agentResultPaperChooseCenter", command: .chooseCenter)
                }
            }
            .buttonStyle(.bordered)
            .controlSize(.small)
            .frame(maxWidth: .infinity, alignment: .leading)

            PaperPreviewScrollRepresentable(
                image: image,
                paperSize: paperSize,
                pageIdentity: pageIdentity,
                selectionEnabled: selectionEnabled,
                selectedPoint: selectedPoint,
                command: command,
                commandSequence: commandSequence,
                exposesUITestGeometry: exposesUITestGeometry,
                onGeometryDiagnostic: { debugViewportDiagnostic = $0 },
                onSelectPaperPoint: onSelectPaperPoint
            )
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("纸面预览")
            .accessibilityValue(accessibilityValue)
            .accessibilityIdentifier("agentResultPaperViewport")
            .frame(height: viewportHeight)
            .clipShape(RoundedRectangle(cornerRadius: 8))
        }
    }

    private var accessibilityValue: String {
        let guidance = selectionEnabled ? "双指缩放并平移，或用‘选中心’选择位置" : "可缩放和平移"
        return exposesUITestGeometry ? "\(guidance); \(debugViewportDiagnostic)" : guidance
    }

    private func commandButton(_ title: String, id: String, command next: Command) -> some View {
        Button(title) {
            command = next
            commandSequence &+= 1
        }
        .accessibilityIdentifier(id)
    }
}

private struct PaperPreviewScrollRepresentable: UIViewRepresentable {
    let image: UIImage
    let paperSize: CGSize
    let pageIdentity: String
    let selectionEnabled: Bool
    let selectedPoint: CGPoint?
    let command: PaperPreviewViewport.Command
    let commandSequence: Int
    let exposesUITestGeometry: Bool
    let onGeometryDiagnostic: (String) -> Void
    let onSelectPaperPoint: (CGPoint) -> Void

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> UIScrollView {
        let scroll = PaperPreviewScrollView(frame: .zero)
        scroll.backgroundColor = UIColor(white: 0.88, alpha: 1)
        scroll.delegate = context.coordinator
        scroll.contentInsetAdjustmentBehavior = .never
        scroll.bouncesZoom = false
        scroll.alwaysBounceVertical = false
        scroll.alwaysBounceHorizontal = false
        scroll.minimumZoomScale = 0.001
        scroll.maximumZoomScale = 4
        scroll.showsHorizontalScrollIndicator = false
        scroll.showsVerticalScrollIndicator = false

        let pageView = PaperPreviewPageView(frame: .zero)
        pageView.imageView.image = image
        if paperSize.width.isFinite && paperSize.height.isFinite && paperSize.width > 0 && paperSize.height > 0 {
            pageView.setPaperSize(paperSize)
        }
        pageView.accessibilityIdentifier = "agentResultPaperPreviewImage"
        scroll.addSubview(pageView)
        context.coordinator.pageView = pageView
        scroll.onLayout = { [weak coordinator = context.coordinator, weak scroll] in
            guard let scroll else { return }
            coordinator?.layoutDidChange(in: scroll)
        }
        context.coordinator.configureTap(on: scroll)
        return scroll
    }

    func updateUIView(_ scroll: UIScrollView, context: Context) {
        let coordinator = context.coordinator
        coordinator.parent = self
        coordinator.onGeometryDiagnostic = onGeometryDiagnostic
        let pageChanged = coordinator.pageIdentity != pageIdentity || coordinator.paperSize != paperSize
        if pageChanged {
            coordinator.pageIdentity = pageIdentity
            coordinator.paperSize = paperSize
            coordinator.fitMode = .page
            coordinator.needsInitialLayout = true
            coordinator.lastCommandSequence = commandSequence
            coordinator.paperCenter = nil
            scroll.minimumZoomScale = 0.001
            scroll.maximumZoomScale = 4
            scroll.setZoomScale(1, animated: false)
            scroll.contentOffset = .zero
        }
        coordinator.pageView?.imageView.image = image
        if paperSize.width.isFinite && paperSize.height.isFinite && paperSize.width > 0 && paperSize.height > 0 {
            coordinator.pageView?.setPaperSize(paperSize)
        }
        coordinator.pageView?.setSelectionPoint(selectedPoint, visible: selectionEnabled)
        coordinator.selectionEnabled = selectionEnabled
        coordinator.updateTapAvailability()
        coordinator.pendingCommand = command
        coordinator.pendingCommandSequence = commandSequence
        scroll.setNeedsLayout()
        scroll.layoutIfNeeded()
        coordinator.applyPendingCommand(in: scroll)
        coordinator.publishGeometryDiagnostic()
    }

    final class Coordinator: NSObject, UIScrollViewDelegate, UIGestureRecognizerDelegate {
        enum FitMode: Equatable { case page, width, manual }

        var parent: PaperPreviewScrollRepresentable?
        weak var pageView: PaperPreviewPageView?
        var pageIdentity: String?
        var paperSize: CGSize = .zero
        var fitMode: FitMode = .page
        var selectionEnabled = false
        var needsInitialLayout = true
        var lastBoundsSize: CGSize = .zero
        var lastFitScale: CGFloat = 0
        var lastFullPageFitScale: CGFloat = 0
        var paperCenter: CGPoint?
        var lastCommandSequence = -1
        var pendingCommandSequence = -1
        var pendingCommand: PaperPreviewViewport.Command = .fitPage
        var onGeometryDiagnostic: ((String) -> Void)?
        private var lastTapDiagnostic = "尚无触点记录"
        private var lastPublishedGeometryDiagnostic: String?
        private weak var scrollView: UIScrollView?
        private var tapRecognizer: UITapGestureRecognizer?

        func configureTap(on scroll: UIScrollView) {
            scrollView = scroll
            let tap = UITapGestureRecognizer(target: self, action: #selector(handleTap(_:)))
            tap.delegate = self
            tap.cancelsTouchesInView = false
            tap.numberOfTouchesRequired = 1
            tap.require(toFail: scroll.panGestureRecognizer)
            scroll.addGestureRecognizer(tap)
            tapRecognizer = tap
        }

        func updateTapAvailability() {
            tapRecognizer?.isEnabled = selectionEnabled && scrollView.map { isValidGeometry(in: $0) } == true
            publishGeometryDiagnostic()
        }

        func applyPendingCommand(in scroll: UIScrollView) {
            guard pendingCommandSequence != lastCommandSequence, scroll.bounds.width > 0, scroll.bounds.height > 0 else { return }
            lastCommandSequence = pendingCommandSequence
            switch pendingCommand {
            case .fitPage:
                fitMode = .page
                setFitScale(in: scroll, widthOnly: false)
            case .fitWidth:
                fitMode = .width
                setFitScale(in: scroll, widthOnly: true)
            case .zoomIn:
                fitMode = .manual
                scroll.setZoomScale(min(scroll.maximumZoomScale, scroll.zoomScale * 1.35), animated: true)
            case .zoomOut:
                fitMode = .manual
                scroll.setZoomScale(max(scroll.minimumZoomScale, scroll.zoomScale / 1.35), animated: true)
            case .chooseCenter:
                guard let pageView else { return }
                parent?.onSelectPaperPoint(CGPoint(x: pageView.bounds.midX, y: pageView.bounds.midY))
            }
        }

        func viewForZooming(in scrollView: UIScrollView) -> UIView? { pageView }

        func scrollViewDidZoom(_ scrollView: UIScrollView) {
            if fitMode != .page && fitMode != .width { fitMode = .manual }
            updateContentGeometry(in: scrollView)
            centerPageIfNeeded(in: scrollView)
            pageView?.updateMarkerScale(scrollView.zoomScale)
            updatePaperCenter(in: scrollView)
            publishGeometryDiagnostic()
        }

        func scrollViewWillBeginDragging(_ scrollView: UIScrollView) { fitMode = .manual }
        func scrollViewWillBeginZooming(_ scrollView: UIScrollView, with view: UIView?) {
            guard let state = scrollView.pinchGestureRecognizer?.state,
                  state == .began || state == .changed else { return }
            fitMode = .manual
        }
        func scrollViewDidScroll(_ scrollView: UIScrollView) {
            if scrollView.bounds.size == lastBoundsSize { updatePaperCenter(in: scrollView) }
            publishGeometryDiagnostic()
        }

        func layoutDidChange(in scrollView: UIScrollView) {
            guard scrollView.bounds.size != lastBoundsSize || needsInitialLayout else { return }
            let oldCenter = paperCenter ?? pagePoint(at: CGPoint(x: scrollView.bounds.midX, y: scrollView.bounds.midY), in: scrollView)
            let oldZoom = scrollView.zoomScale
            let hadLayout = !needsInitialLayout
            lastBoundsSize = scrollView.bounds.size
            updatePageGeometry(in: scrollView)
            guard !needsInitialLayout else {
                needsInitialLayout = false
                fitMode = .page
                setFitScale(in: scrollView, widthOnly: false)
                return
            }
            if fitMode == .page { setFitScale(in: scrollView, widthOnly: false) }
            else if fitMode == .width { setFitScale(in: scrollView, widthOnly: true) }
            else if hadLayout {
                guard let nextFit = fitScale(in: scrollView, widthOnly: false),
                      let widthFit = fitScale(in: scrollView, widthOnly: true) else { return }
                let expandedFit = nextFit * 4
                guard expandedFit.isFinite else { return }
                let relativeZoom = lastFullPageFitScale > 0 ? oldZoom / lastFullPageFitScale : 1
                lastFitScale = nextFit
                lastFullPageFitScale = nextFit
                scrollView.minimumZoomScale = nextFit
                scrollView.maximumZoomScale = max(expandedFit, widthFit)
                scrollView.setZoomScale(min(scrollView.maximumZoomScale, max(nextFit, nextFit * relativeZoom)), animated: false)
                updatePageGeometry(in: scrollView)
                if let oldCenter { centerPaperPoint(oldCenter, in: scrollView) }
            }
        }

        func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer,
                               shouldRecognizeSimultaneouslyWith otherGestureRecognizer: UIGestureRecognizer) -> Bool { false }

        @objc private func handleTap(_ recognizer: UITapGestureRecognizer) {
            guard recognizer.state == .ended else { return }
            guard selectionEnabled, let scroll = scrollView, let pageView else {
                lastTapDiagnostic = "tapIgnored=selection-disabled-or-missing-view"
                publishGeometryDiagnostic()
                return
            }
            let location = recognizer.location(in: scroll)
            guard let paper = PaperPreviewCoordinateMapper.paperPoint(at: location, from: scroll,
                                                                      pageView: pageView, paperSize: paperSize) else {
                let local = pageView.convert(location, from: scroll)
                lastTapDiagnostic = "tapRejected=outside-paper; viewportPoint=\(location); paperPoint=\(local)"
                publishGeometryDiagnostic()
                return
            }
            lastTapDiagnostic = "tapAccepted=\(paper)"
            publishGeometryDiagnostic()
            parent?.onSelectPaperPoint(paper)
        }

        func publishGeometryDiagnostic() {
#if DEBUG
            guard parent?.exposesUITestGeometry == true, let scroll = scrollView, let pageView else { return }
            let status = "bounds=\(scroll.bounds); frame=\(scroll.frame); page.bounds=\(pageView.bounds); page.frame=\(pageView.frame); page.center=\(pageView.center); zoom=\(scroll.zoomScale); contentSize=\(scroll.contentSize); contentOffset=\(scroll.contentOffset); insets=\(scroll.contentInset); tapEnabled=\(tapRecognizer?.isEnabled == true); \(lastTapDiagnostic)"
            guard status != lastPublishedGeometryDiagnostic else { return }
            lastPublishedGeometryDiagnostic = status
            DispatchQueue.main.async { [weak self] in self?.onGeometryDiagnostic?(status) }
#endif
        }

        private func pagePoint(at viewportPoint: CGPoint, in scroll: UIScrollView) -> CGPoint? {
            guard let pageView else { return nil }
            return PaperPreviewCoordinateMapper.paperPoint(at: viewportPoint, from: scroll,
                                                           pageView: pageView, paperSize: paperSize)
        }

        private func centerPaperPoint(_ point: CGPoint, in scroll: UIScrollView) {
            guard pageView != nil else { return }
            let viewportPoint = CGPoint(x: scroll.bounds.midX, y: scroll.bounds.midY)
            let current = pagePoint(at: viewportPoint, in: scroll) ?? point
            let deltaX = (point.x - current.x) * scroll.zoomScale
            let deltaY = (point.y - current.y) * scroll.zoomScale
            scroll.setContentOffset(CGPoint(x: scroll.contentOffset.x + deltaX,
                                            y: scroll.contentOffset.y + deltaY), animated: false)
        }

        private func updatePageGeometry(in scroll: UIScrollView) {
            guard let pageView, isValidGeometry(in: scroll) else { return }
            pageView.setPaperSize(paperSize)
            updateContentGeometry(in: scroll)
            centerPageIfNeeded(in: scroll)
        }

        private func updateContentGeometry(in scroll: UIScrollView) {
            guard isValidGeometry(in: scroll) else { return }
            let scaledWidth = paperSize.width * scroll.zoomScale
            let scaledHeight = paperSize.height * scroll.zoomScale
            guard scaledWidth.isFinite, scaledHeight.isFinite, scaledWidth > 0, scaledHeight > 0 else { return }
            scroll.contentSize = CGSize(width: scaledWidth, height: scaledHeight)
            pageView?.center = CGPoint(x: scaledWidth / 2, y: scaledHeight / 2)
            let horizontal = max(0, (scroll.bounds.width - scaledWidth) / 2)
            let vertical = max(0, (scroll.bounds.height - scaledHeight) / 2)
            scroll.contentInset = UIEdgeInsets(top: vertical, left: horizontal, bottom: vertical, right: horizontal)
            if scaledWidth <= scroll.bounds.width { scroll.contentOffset.x = -horizontal }
            if scaledHeight <= scroll.bounds.height { scroll.contentOffset.y = -vertical }
            updateTapAvailability()
        }

        private func setFitScale(in scroll: UIScrollView, widthOnly: Bool) {
            guard isValidGeometry(in: scroll), let fit = fitScale(in: scroll, widthOnly: widthOnly) else { return }
            guard let fullFit = fitScale(in: scroll, widthOnly: false),
                  let widthFit = fitScale(in: scroll, widthOnly: true) else { return }
            let expandedFit = fullFit * 4
            guard expandedFit.isFinite else { return }
            lastFitScale = fullFit
            lastFullPageFitScale = fullFit
            scroll.minimumZoomScale = fullFit
            scroll.maximumZoomScale = max(expandedFit, widthFit)
            scroll.setZoomScale(fit, animated: false)
            scroll.contentOffset = .zero
            updatePageGeometry(in: scroll)
            centerPageIfNeeded(in: scroll)
        }

        private func fitScale(in scroll: UIScrollView, widthOnly: Bool) -> CGFloat? {
            guard isValidGeometry(in: scroll) else { return nil }
            let horizontalInset: CGFloat = 16
            let verticalInset: CGFloat = 12
            let widthScale = (scroll.bounds.width - horizontalInset * 2) / paperSize.width
            let heightScale = (scroll.bounds.height - verticalInset * 2) / paperSize.height
            let result = widthOnly ? widthScale : min(widthScale, heightScale)
            return result.isFinite && result > 0 ? result : nil
        }

        private func isValidGeometry(in scroll: UIScrollView) -> Bool {
            paperSize.width.isFinite && paperSize.height.isFinite && paperSize.width > 0 && paperSize.height > 0 &&
            scroll.bounds.width.isFinite && scroll.bounds.height.isFinite && scroll.bounds.width > 32 && scroll.bounds.height > 24
        }

        private func updatePaperCenter(in scroll: UIScrollView) {
            if let point = pagePoint(at: CGPoint(x: scroll.bounds.midX, y: scroll.bounds.midY), in: scroll) { paperCenter = point }
        }

        private func centerPageIfNeeded(in scroll: UIScrollView) {
            guard pageView != nil else { return }
            let width = paperSize.width * scroll.zoomScale
            let height = paperSize.height * scroll.zoomScale
            let horizontal = max(0, (scroll.bounds.width - width) / 2)
            let vertical = max(0, (scroll.bounds.height - height) / 2)
            scroll.contentInset = UIEdgeInsets(top: vertical, left: horizontal, bottom: vertical, right: horizontal)
        }
    }
}

private final class PaperPreviewScrollView: UIScrollView {
    var onLayout: (() -> Void)?

    override func layoutSubviews() {
        super.layoutSubviews()
        onLayout?()
    }
}

enum PaperPreviewCoordinateMapper {
    static func paperPoint(at viewportPoint: CGPoint, from viewport: UIView,
                           pageView: UIView, paperSize: CGSize) -> CGPoint? {
        guard paperSize.width.isFinite, paperSize.height.isFinite,
              paperSize.width > 0, paperSize.height > 0,
              viewportPoint.x.isFinite, viewportPoint.y.isFinite else { return nil }
        let point = pageView.convert(viewportPoint, from: viewport)
        guard point.x.isFinite, point.y.isFinite,
              CGRect(origin: .zero, size: paperSize).contains(point) else { return nil }
        return point
    }
}

private final class PaperPreviewPageView: UIView {
    let imageView = UIImageView()
    private let markerLayer = CAShapeLayer()
    private var markerPoint: CGPoint?
    private var markerIsVisible = false
    private var markerZoom: CGFloat = 1

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .white
        clipsToBounds = false
        imageView.contentMode = .scaleToFill
        imageView.isUserInteractionEnabled = false
        addSubview(imageView)
        markerLayer.fillColor = UIColor.systemRed.cgColor
        markerLayer.strokeColor = UIColor.white.cgColor
        markerLayer.lineWidth = 2
        markerLayer.shadowColor = UIColor.black.cgColor
        markerLayer.shadowOpacity = 0.35
        markerLayer.shadowRadius = 2
        layer.addSublayer(markerLayer)
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    func setPaperSize(_ size: CGSize) {
        if bounds.size != size { bounds = CGRect(origin: .zero, size: size) }
        imageView.frame = bounds
        updateMarkerPath()
    }

    func setSelectionPoint(_ point: CGPoint?, visible: Bool) {
        markerPoint = point
        markerIsVisible = visible && point != nil
        updateMarkerPath()
    }

    func updateMarkerScale(_ zoom: CGFloat) {
        markerZoom = max(0.001, zoom)
        updateMarkerPath()
    }

    private func updateMarkerPath() {
        guard markerIsVisible, let markerPoint else { markerLayer.path = nil; return }
        let radius = 7 / markerZoom
        markerLayer.frame = bounds
        markerLayer.path = UIBezierPath(ovalIn: CGRect(x: markerPoint.x - radius, y: markerPoint.y - radius,
                                                       width: radius * 2, height: radius * 2)).cgPath
        markerLayer.contentsScale = window?.screen.scale ?? UIScreen.main.scale
    }
}
