import XCTest
import PDFKit
import UIKit
@testable import PadNote

@MainActor
final class CanvasTests: XCTestCase {
    func testTwoPagesRenderAtDifferentWorldContent() throws {
        var note = NoteDocument(pageCount: 2)
        note.strokes = [
            InkStroke(id: "p0", color: "#FFFF0000", points: [InkPoint(x: 40, y: 40), InkPoint(x: 100, y: 40)]),
            InkStroke(id: "p1", color: "#FF0000FF", points: [InkPoint(x: 40, y: note.pageHeight + note.pageGap + 40), InkPoint(x: 100, y: note.pageHeight + note.pageGap + 40)])
        ]
        let first = NoteRenderer.renderPage(document: note, page: 0, pdfURL: nil, maxEdge: 1600)
        let second = NoteRenderer.renderPage(document: note, page: 1, pdfURL: nil, maxEdge: 1600)
        XCTAssertNotNil(first.cgImage); XCTAssertNotNil(second.cgImage)
        XCTAssertNotEqual(first.pngData(), second.pngData())
    }

    func testPDFExportHasDocumentPageCount() throws {
        var note = NoteDocument(pageCount: 2)
        note.strokes = [InkStroke(points: [InkPoint(x: 10, y: 10), InkPoint(x: 20, y: 20)])]
        let data = NoteRenderer.exportPDF(document: note, pdfURL: nil)
        XCTAssertEqual(PDFDocument(data: data)?.pageCount, 2)
    }

    func testPolygonWithNegativeSlopeUsesSignedDenominator() {
        let polygon = [CGPoint(x: 0, y: 0), CGPoint(x: 80, y: 20), CGPoint(x: 40, y: 80), CGPoint(x: -20, y: 40)]
        XCTAssertTrue(CanvasGeometry.pointInPolygon(CGPoint(x: 25, y: 35), polygon))
        XCTAssertFalse(CanvasGeometry.pointInPolygon(CGPoint(x: 120, y: 35), polygon))
    }

    func testEraserPathDistanceKeepsFarPoint() {
        let eraser = [CGPoint(x: 0, y: 0), CGPoint(x: 20, y: 0)]
        XCTAssertLessThanOrEqual(CanvasGeometry.distanceToPolyline(CGPoint(x: 10, y: 4), eraser), 4)
        XCTAssertGreaterThan(CanvasGeometry.distanceToPolyline(CGPoint(x: 10, y: 30), eraser), 10)
    }

    func testSeparatedCollinearSegmentsDoNotIntersect() {
        XCTAssertFalse(CanvasGeometry.segmentsIntersect(CGPoint(x: 0, y: 0), CGPoint(x: 10, y: 0),
                                                         CGPoint(x: 20, y: 0), CGPoint(x: 30, y: 0)))
        XCTAssertTrue(CanvasGeometry.segmentsIntersect(CGPoint(x: 0, y: 0), CGPoint(x: 10, y: 0),
                                                        CGPoint(x: 8, y: 0), CGPoint(x: 20, y: 0)))
    }

    func testRenderRespectsMaxEdgeWithoutUpscaling() {
        let note = NoteDocument(pageWidth: 2400, pageHeight: 1800)
        let image = NoteRenderer.renderPage(document: note, page: 0, pdfURL: nil, maxEdge: 1600)
        XCTAssertLessThanOrEqual(max(image.size.width, image.size.height), 1600)
    }
    func testSelectionImageExcludesContentOutsidePolygon() throws {
        var note = NoteDocument(pageWidth: 200, pageHeight: 200, pageStyle: PageStyle(paper: "blank"))
        note.strokes = [InkStroke(color: "#FF285EA8", baseWidth: 6,
            points: [InkPoint(x: 40, y: 50), InkPoint(x: 70, y: 50)])]
        let triangle = [CGPoint(x: 20, y: 20), CGPoint(x: 180, y: 20), CGPoint(x: 20, y: 180)]
        let before = try XCTUnwrap(NoteRenderer.renderSelection(document: note, polygon: triangle, pdfURL: nil))
        note.strokes.append(InkStroke(color: "#FFFF0000", baseWidth: 12,
            points: [InkPoint(x: 130, y: 150), InkPoint(x: 170, y: 150)]))
        let after = try XCTUnwrap(NoteRenderer.renderSelection(document: note, polygon: triangle, pdfURL: nil))
        XCTAssertEqual(before.pngData(), after.pngData(), "Ink outside the lasso must never be uploaded")
    }

    func testLongTextPaginatesWithoutDroppingCharacters() {
        let source = (1...120).map { "第 \($0) 行：一段需要完整保留的笔记内容。" }.joined(separator: "\n")
        let flow = NoteTextFlow(format: "markdown", source: source, width: 300, anchorYInPage: 40)
        let fragments = NoteTextLayout.fragments(flow, pageHeight: 500)
        XCTAssertGreaterThan(fragments.count, 2)
        XCTAssertEqual(fragments.map { $0.text.string }.joined(), source + "\n")
        XCTAssertTrue(fragments.allSatisfy { $0.rect.maxY <= 460 })
    }

    func testHeadingAtPageTailMovesWithFollowingBody() throws {
        let flow = NoteTextFlow(format: "markdown", source: "# 不能孤立的标题\n紧随标题的正文。",
            width: 300, anchorPageIndex: 0, anchorXInPage: 16, anchorYInPage: 430)
        let fragments = NoteTextLayout.fragments(flow, pageHeight: 500)
        let first = try XCTUnwrap(fragments.first)
        XCTAssertEqual(first.page, 1)
        XCTAssertTrue(first.text.string.contains("不能孤立的标题"))
        XCTAssertTrue(first.text.string.contains("紧随标题的正文"))
        XCTAssertTrue(NoteTextLayout.canFullyLayout(flow, pageHeight: 500))
    }

    func testCoreTextFallbackKeepsOrderedListNumbers() {
        let source = "3. 第三步\n\n4. 第四步\n7) 第七步"
        let flow = NoteTextFlow(format: "markdown", source: source, width: 300, anchorYInPage: 40)
        let rendered = NoteTextLayout.fragments(flow, pageHeight: 500).map { $0.text.string }.joined()
        XCTAssertEqual(rendered, source + "\n")
    }

    func testScrollPageUsesViewportCenterOnceAndRestoresRelativeZoom() {
        var note = NoteDocument(pageWidth: 1000, pageHeight: 1000, pageGap: 20, pageCount: 5)
        note.viewportZoom = 1
        note.viewportCenterX = 500
        note.viewportCenterY = 500
        let controller = CanvasController(defaults: UserDefaults(suiteName: "CanvasTests.\(UUID().uuidString)")!)
        let scroll = CanvasScrollView(frame: CGRect(x: 0, y: 0, width: 500, height: 500))
        scroll.configure(document: note, controller: controller, pdfURL: nil)
        scroll.layoutIfNeeded()
        XCTAssertEqual(scroll.zoomScale, 0.5, accuracy: 0.001)
        scroll.setContentOffset(CGPoint(x: 0, y: 600), animated: false)
        scroll.scrollViewDidScroll(scroll)
        XCTAssertEqual(controller.currentPage, 1)
        XCTAssertEqual(scroll.surface.center.y, scroll.contentSize.height / 2, accuracy: 0.001)
    }

}
