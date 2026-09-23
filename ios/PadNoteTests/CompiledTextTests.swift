import XCTest
import UIKit
@testable import PadNote

@MainActor
final class CompiledTextTests: XCTestCase {
    private func prepare(_ note: NoteDocument, timeout: TimeInterval = 20) async throws {
        let finished = expectation(description: "Offline paper resources finish rendering")
        var outcome: Result<Void, Error>?
        Task { @MainActor in
            do {
                try await CompiledTextRenderer.shared.prepareAndWait(document: note)
                outcome = .success(())
            } catch {
                outcome = .failure(error)
            }
            finished.fulfill()
        }
        await fulfillment(of: [finished], timeout: timeout)
        guard let outcome else { throw NSError(domain: "PadNoteTests", code: 1, userInfo: [NSLocalizedDescriptionKey: "Timed out rendering offline paper resources"]) }
        try outcome.get()
    }

    private func attachPages(_ note: NoteDocument, name: String) {
        let contentPages = note.textFlows.flatMap { NoteTextLayout.fragments($0, pageHeight: note.pageHeight).map(\.page) }
        let pageCount = max(note.pageCount, (contentPages.max() ?? -1) + 1)
        for page in 0..<pageCount {
            let attachment = XCTAttachment(image: NoteRenderer.renderPage(document: note, page: page, pdfURL: nil))
            attachment.name = "\(name) – page \(page + 1)"
            attachment.lifetime = .keepAlways
            add(attachment)
        }
    }

    func testOfflineCompiledPaperContainsMathAndDiagramPixels() async throws {
        var note = NoteDocument()
        let terms = (1...36).map { "x_{\($0)}" }.joined(separator: "+")
        let source = """
        # 长公式
        \\[\(terms)=S\\]
        ## 横向图
        ```mermaid
        flowchart LR
          A --> B --> C --> D --> E --> F
        ```
        ## 纵向图
        ```mermaid
        flowchart TD
          A --> B --> C --> D --> E --> F
        ```
        """
        let flow = NoteTextFlow(format: "markdown", source: source, width: 420, anchorYInPage: 56)
        note.textFlows = [flow]
        try await prepare(note)
        let entry = try XCTUnwrap(CompiledTextCache.entry(flow))
        XCTAssertEqual(entry.units.count, 3, "Each heading must stay with its formula or diagram")
        for unit in entry.units {
            XCTAssertGreaterThan(unit.image.size.width, 100)
            XCTAssertGreaterThan(unit.image.size.height, 10)
            XCTAssertLessThanOrEqual(unit.size.width, flow.width)
        }
        let fragments = NoteTextLayout.fragments(flow, pageHeight: note.pageHeight)
        XCTAssertTrue(fragments.allSatisfy { $0.image != nil })
        XCTAssertEqual(fragments.map { $0.text.string }.joined(), source)
        attachPages(note, name: "Compiled formula and diagram on paper")
    }

    func testRealWebViewKeepsHeadingChainWithFormulaOffPageTail() async throws {
        var note = NoteDocument()
        let terms = (1...36).map { "x_{\($0)}" }.joined(separator: "+")
        let source = "# 主标题\n## 推导\n\\[\(terms)=S\\]"
        let flow = NoteTextFlow(format: "markdown", source: source, width: 520,
            anchorPageIndex: 0, anchorXInPage: 16, anchorYInPage: note.pageHeight - 55)
        note.textFlows = [flow]
        try await prepare(note)
        let entry = try XCTUnwrap(CompiledTextCache.entry(flow))
        XCTAssertEqual(entry.units.count, 1, "Heading chain and its formula must compile as one semantic unit")
        let fragments = NoteTextLayout.fragments(flow, pageHeight: note.pageHeight)
        XCTAssertEqual(fragments.count, 1)
        XCTAssertEqual(fragments.first?.page, 1, "The title must move with the formula instead of remaining at the page tail")
        XCTAssertEqual(fragments.map { $0.text.string }.joined(), source)
        note.pageCount = 2
        attachPages(note, name: "Heading chain and long formula")
    }

    func testOrderedStepsRenderOnPaperWithMathAndDiagram() async throws {
        var note = NoteDocument()
        let source = """
        # 从差商到导数
        按顺序完成下面三个步骤，再核对公式和流程图。

        3. 展开分子

        4. 约去非零的 h

        5. 令 h 趋近于零

        \\[f'(x)=\\lim_{h\\to0}\\frac{f(x+h)-f(x)}{h}\\]

        ```mermaid
        flowchart LR
          A[展开] --> B[约分] --> C[取极限]
        ```
        """
        let flow = NoteTextFlow(format: "markdown", source: source, width: note.pageWidth - 32,
            anchorPageIndex: 0, anchorXInPage: 16, anchorYInPage: 40)
        note.textFlows = [flow]
        try await prepare(note)
        let entry = try XCTUnwrap(CompiledTextCache.entry(flow))
        XCTAssertGreaterThanOrEqual(entry.units.count, 5)
        XCTAssertTrue(NoteTextLayout.canFullyLayout(flow, pageHeight: note.pageHeight))
        attachPages(note, name: "Ordered steps with formula and diagram")
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
