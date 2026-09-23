import XCTest
import UIKit
@testable import PadNote

@MainActor
final class CompiledTextTests: XCTestCase {
    func testOfflineCompiledPaperContainsMathAndDiagramPixels() async throws {
        var note = NoteDocument()
        let source = "# 勾股定理\n\\[a^2+b^2=c^2\\]\n```mermaid\ngraph LR\n A[条件] --> B[结论]\n```"
        let flow = NoteTextFlow(format: "markdown", source: source, width: 420, anchorYInPage: 56)
        note.textFlows = [flow]
        try await CompiledTextRenderer.shared.prepareAndWait(document: note)
        let entry = try XCTUnwrap(CompiledTextCache.entry(flow))
        XCTAssertGreaterThanOrEqual(entry.units.count, 3)
        for unit in entry.units {
            let cg = try XCTUnwrap(unit.image.cgImage)
            let pixels = try XCTUnwrap(cg.dataProvider?.data) as Data
            XCTAssertGreaterThan(pixels.filter { $0 > 0 }.count, 100, "Every compiled block should have painted pixels")
        }
        let fragments = NoteTextLayout.fragments(flow, pageHeight: note.pageHeight)
        XCTAssertTrue(fragments.allSatisfy { $0.image != nil })
        XCTAssertEqual(fragments.map { $0.text.string }.joined(), source)
        let attachment = XCTAttachment(image: NoteRenderer.renderPage(document: note, page: 0, pdfURL: nil))
        attachment.name = "Compiled formula and diagram on paper"
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    func testSemanticBlocksRelayoutFromAnchorWithoutRecompiling() {
        let image = UIGraphicsImageRenderer(size: CGSize(width: 100, height: 100)).image { _ in UIColor.black.setFill(); UIRectFill(CGRect(x: 0, y: 0, width: 100, height: 100)) }
        let units = [160.0, 220.0, 120.0].map { CompiledTextCache.Unit(image: image, size: CGSize(width: 300, height: $0)) }
        var flow = NoteTextFlow(source: "Full source", width: 300, anchorYInPage: 200)
        let first = NoteTextLayout.compiledFragments(units, flow: flow, pageHeight: 500)
        XCTAssertEqual(first.map(\.page), [0, 1, 1])
        XCTAssertTrue(first.allSatisfy { $0.rect.maxY <= 460 })
        flow.anchorPageIndex = 2; flow.anchorYInPage = 40
        XCTAssertEqual(NoteTextLayout.compiledFragments(units, flow: flow, pageHeight: 500).map(\.page), [2, 2, 3])
    }
}
