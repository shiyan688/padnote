import Foundation
import CoreGraphics

/// Pure document mutations shared by the canvas page commands.  PDF pages are
/// an immutable prefix: annotation pages may be edited, but the imported
/// source pages cannot be removed, copied, or moved across that prefix.
public enum NotePageOperations {
    public static let maximumPageCount = 500

    public struct FlowMoveCandidate: Equatable {
        public var flow: NoteTextFlow
        public var pageCount: Int
        public init(flow: NoteTextFlow, pageCount: Int) { self.flow = flow; self.pageCount = pageCount }
    }

    /// Computes a single-flow drag without mutating the document. The flow may
    /// cross pages, but never lands in the inter-page gap or outside a page.
    public static func candidateFlowMove(_ flow: NoteTextFlow, delta: CGPoint, in document: NoteDocument) -> FlowMoveCandidate? {
        let stride = document.pageHeight + document.pageGap
        guard stride > 0, document.pageCount > 0 else { return nil }
        let height = max(flow.fontSizeSp * flow.lineHeight * 2, flow.fontSizeSp * 2)
        var worldY = Double(flow.anchorPageIndex) * stride + flow.anchorYInPage + Double(delta.y)
        var page = Int(floor(worldY / stride))
        if worldY < 0 { page = 0; worldY = 0 }
        if page >= document.pageCount {
            guard document.pageCount < maximumPageCount else { return nil }
            page = document.pageCount
        }
        var localY = worldY - Double(page) * stride
        if localY > document.pageHeight - height {
            localY = max(0, document.pageHeight - height)
        }
        if localY > document.pageHeight { localY = document.pageHeight }
        var copy = flow
        copy.anchorPageIndex = min(page, maximumPageCount - 1)
        copy.anchorXInPage = min(max(0, flow.anchorXInPage + Double(delta.x)), max(0, document.pageWidth - flow.width))
        copy.anchorYInPage = min(max(0, localY), max(0, document.pageHeight - height))
        return FlowMoveCandidate(flow: copy, pageCount: max(document.pageCount, page + 1))
    }

    /// Returns true after a half-second dwell in the edge band. Kept pure so
    /// gesture tests can exercise paging without constructing UITouch objects.
    public static func shouldAutoPage(after elapsed: TimeInterval, locationY: CGFloat, viewportHeight: CGFloat) -> Bool {
        elapsed >= 0.5 && viewportHeight > 0 && (locationY <= 32 || locationY >= viewportHeight - 32)
    }

    public static func delete(page index: Int, in document: inout NoteDocument) -> Bool {
        guard valid(index, document), index >= document.pdfPageCount,
              document.pageCount > 1 else { return false }
        let stride = document.pageHeight + document.pageGap
        document.strokes.removeAll { stroke in
            page(of: stroke, stride: stride, pageCount: document.pageCount) == index
        }
        for i in document.strokes.indices {
            let old = page(of: document.strokes[i], stride: stride, pageCount: document.pageCount)
            if old > index { translate(&document.strokes[i], dy: -stride) }
        }
        document.textFlows.removeAll { $0.anchorPageIndex == index }
        for i in document.textFlows.indices where document.textFlows[i].anchorPageIndex > index {
            document.textFlows[i].anchorPageIndex -= 1
        }
        document.images.removeAll { $0.page == index }
        for i in document.images.indices where document.images[i].page > index {
            document.images[i].page -= 1
        }
        document.pageCount -= 1
        bumpTopology(&document)
        return true
    }

    public static func duplicate(page index: Int, in document: inout NoteDocument) -> Bool {
        guard valid(index, document), index >= document.pdfPageCount,
              document.pageCount < maximumPageCount else { return false }
        let oldCount = document.pageCount
        let stride = document.pageHeight + document.pageGap
        for i in document.strokes.indices where page(of: document.strokes[i], stride: stride, pageCount: oldCount) > index {
            translate(&document.strokes[i], dy: stride)
        }
        for i in document.textFlows.indices where document.textFlows[i].anchorPageIndex > index {
            document.textFlows[i].anchorPageIndex += 1
        }
        for i in document.images.indices where document.images[i].page > index { document.images[i].page += 1 }
        let pageStrokes = document.strokes.filter { page(of: $0, stride: stride, pageCount: oldCount) == index }
            .map { copyStroke($0, dy: stride) }
        document.strokes.append(contentsOf: pageStrokes)
        let pageFlows = document.textFlows.filter { $0.anchorPageIndex == index }.map { flow -> NoteTextFlow in
            var copy = flow; copy.id = UUID().uuidString; copy.anchorPageIndex = index + 1; return copy
        }
        document.textFlows.append(contentsOf: pageFlows)
        let pageImages = document.images.filter { $0.page == index }.map { image -> NoteImage in
            var copy = image; copy.id = UUID().uuidString; copy.page = index + 1; return copy
        }
        document.images.append(contentsOf: pageImages)
        document.pageCount += 1
        bumpTopology(&document)
        return true
    }

    public static func move(page from: Int, to: Int, in document: inout NoteDocument) -> Bool {
        guard valid(from, document), valid(to, document), from != to,
              from >= document.pdfPageCount, to >= document.pdfPageCount else { return false }
        let count = document.pageCount
        let mapping = (0..<count).map { old -> Int in
            if old == from { return to }
            if from < to && old > from && old <= to { return old - 1 }
            if to < from && old >= to && old < from { return old + 1 }
            return old
        }
        let stride = document.pageHeight + document.pageGap
        for i in document.strokes.indices {
            let old = page(of: document.strokes[i], stride: stride, pageCount: count)
            let next = mapping[old]; translate(&document.strokes[i], dy: Double(next - old) * stride)
        }
        for i in document.textFlows.indices {
            let old = min(count - 1, max(0, document.textFlows[i].anchorPageIndex))
            document.textFlows[i].anchorPageIndex = mapping[old]
        }
        for i in document.images.indices {
            let old = min(count - 1, max(0, document.images[i].page))
            document.images[i].page = mapping[old]
        }
        bumpTopology(&document)
        return true
    }

    public static func appendBlankPage(in document: inout NoteDocument) -> Bool {
        guard document.pageCount < maximumPageCount else { return false }
        document.pageCount += 1
        bumpTopology(&document)
        return true
    }

    private static func bumpTopology(_ document: inout NoteDocument) {
        if document.pageTopologyRevision < 1_000_000_000 {
            document.pageTopologyRevision = max(0, document.pageTopologyRevision) + 1
        } else {
            document.pageTopologyRevision = 1_000_000_000
        }
    }

    private static func valid(_ index: Int, _ document: NoteDocument) -> Bool { index >= 0 && index < document.pageCount }
    private static func page(of stroke: InkStroke, stride: Double, pageCount: Int) -> Int {
        guard stride > 0, let y = stroke.points.first?.y else { return 0 }
        return min(pageCount - 1, max(0, Int(floor(max(0, y) / stride))))
    }
    private static func translate(_ stroke: inout InkStroke, dy: Double) {
        stroke.points = stroke.points.map { var p = $0; p.y += dy; return p }
    }
    private static func copyStroke(_ stroke: InkStroke, dy: Double) -> InkStroke {
        var copy = stroke; copy.id = UUID().uuidString; copy.createdAt = NoteDocument.nowMillis(); translate(&copy, dy: dy); return copy
    }
}
