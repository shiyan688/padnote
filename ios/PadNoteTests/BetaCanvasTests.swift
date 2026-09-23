import XCTest
@testable import PadNote

final class BetaCanvasTests: XCTestCase {
    @MainActor
    func testToolWidthsAreRememberedIndependently() {
        let controller = CanvasController(defaults: UserDefaults(suiteName: "BetaCanvasTests.\(UUID().uuidString)")!)
        controller.strokeWidth = 1
        controller.tool = .highlighter
        controller.strokeWidth = 20
        controller.tool = .eraser
        controller.strokeWidth = 40
        controller.tool = .pen
        XCTAssertEqual(controller.strokeWidth, 1)
        controller.tool = .highlighter
        XCTAssertEqual(controller.strokeWidth, 20)
        controller.tool = .eraser
        XCTAssertEqual(controller.strokeWidth, 40)
    }

    func testAnnotationPageDuplicateMovesWorldCoordinatesAndAnchors() {
        var document = NoteDocument(pageCount: 3, pdfPageCount: 1,
                                    strokes: [InkStroke(points: [InkPoint(x: 4, y: 1122)])],
                                    textFlows: [NoteTextFlow(anchorPageIndex: 1)],
                                    images: [NoteImage(page: 1, x: 8, y: 9, width: 20, height: 10)])
        XCTAssertTrue(NotePageOperations.duplicate(page: 1, in: &document))
        XCTAssertEqual(document.pageCount, 4)
        XCTAssertEqual(document.strokes.count, 2)
        XCTAssertEqual(document.textFlows.map(\.anchorPageIndex).sorted(), [1, 2])
        XCTAssertEqual(document.images.map(\.page).sorted(), [1, 2])
        XCTAssertEqual(document.strokes.map { $0.points[0].y }.sorted(), [1122, 2232])
    }

    func testPDFPrefixCannotBeDeletedCopiedOrReordered() {
        var document = NoteDocument(pageCount: 3, pdfPageCount: 2)
        XCTAssertFalse(NotePageOperations.delete(page: 0, in: &document))
        XCTAssertFalse(NotePageOperations.duplicate(page: 1, in: &document))
        XCTAssertFalse(NotePageOperations.move(page: 2, to: 0, in: &document))
        XCTAssertEqual(document.pageCount, 3)
    }

    func testMovePreservesPageRelativeCoordinates() {
        let stride = 1110.0
        var document = NoteDocument(pageCount: 4, strokes: [
            InkStroke(points: [InkPoint(x: 1, y: stride * 1 + 12)]),
            InkStroke(points: [InkPoint(x: 2, y: stride * 3 + 15)])
        ])
        XCTAssertTrue(NotePageOperations.move(page: 1, to: 3, in: &document))
        XCTAssertEqual(document.strokes.map { $0.points[0].y }.sorted(), [stride * 2 + 15, stride * 3 + 12])
    }

    func testSingleFlowCandidateCanCrossPageAndEdgeTriggerIsPure() {
        let document = NoteDocument(pageCount: 2)
        let flow = NoteTextFlow(width: 240, anchorPageIndex: 0, anchorYInPage: 900)
        let candidate = NotePageOperations.candidateFlowMove(flow, delta: CGPoint(x: 12, y: 300), in: document)
        XCTAssertEqual(candidate?.flow.anchorPageIndex, 1)
        XCTAssertGreaterThanOrEqual(candidate?.flow.anchorYInPage ?? -1, 0)
        XCTAssertFalse(NotePageOperations.shouldAutoPage(after: 0.49, locationY: 790, viewportHeight: 800))
        XCTAssertTrue(NotePageOperations.shouldAutoPage(after: 0.5, locationY: 790, viewportHeight: 800))
    }
}
