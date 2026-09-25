import XCTest
import UIKit
import PDFKit
import SwiftUI
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

    func testSyntaxFailuresAreReportedAndDoNotBlockFollowingRealWebRender() async throws {
        let renderer = CompiledTextRenderer(configuration: .init()) {
            Bundle.main.url(forResource: "reader", withExtension: "html", subdirectory: "Web")
        }
        defer { renderer.shutdown() }
        let brokenMermaid = NoteTextFlow(id: UUID().uuidString, format: "markdown",
            source: "```mermaid\nflowchart LR\n  A[unterminated --> B\n```", width: 420)
        var brokenNote = NoteDocument(id: UUID().uuidString); brokenNote.textFlows = [brokenMermaid]
        do {
            try await renderer.prepareAndWait(document: brokenNote)
            XCTFail("invalid Mermaid must not be reported as a complete render")
        } catch let failure as CompiledTextRenderFailure {
            XCTAssertEqual(failure.kind, .syntax)
        }
        XCTAssertEqual(renderer.failure(documentID: brokenNote.id, flow: brokenMermaid)?.kind, .syntax)

        let mathID = UUID().uuidString
        var mathNote = NoteDocument(id: UUID().uuidString)
        for invalidSource in ["\\frac{", "\\badcommand{x}"] {
            let brokenMath = NoteTextFlow(id: mathID, format: "latex", source: invalidSource, width: 420)
            mathNote.textFlows = [brokenMath]
            do {
                try await renderer.prepareAndWait(document: mathNote)
                XCTFail("invalid KaTeX \(invalidSource) must not be reported as a complete render")
            } catch let failure as CompiledTextRenderFailure {
                XCTAssertEqual(failure.kind, .syntax)
            }
        }

        let repairedMath = NoteTextFlow(id: mathID, format: "latex", source: "x^2+y^2=z^2", width: 420)
        mathNote.textFlows = [repairedMath]
        try await renderer.prepareAndWait(document: mathNote)
        XCTAssertNil(renderer.failure(documentID: mathNote.id, flow: repairedMath),
                     "editing the same failed object to valid source must clear its failure state")
        XCTAssertNotNil(CompiledTextCache.entry(repairedMath))

        let exportMermaid = NoteTextFlow(id: UUID().uuidString, format: "markdown",
            source: "```mermaid\nflowchart LR\n  A[unterminated --> B\n```", width: 420)
        let exportMath = NoteTextFlow(id: UUID().uuidString, format: "latex", source: "\\frac{", width: 420)
        var incompleteExport = NoteDocument(id: UUID().uuidString)
        incompleteExport.textFlows = [exportMermaid, exportMath]
        do {
            _ = try await renderer.prepareExportSnapshot(document: incompleteExport)
            XCTFail("a PDF snapshot with any failed flow must be rejected")
        } catch {}
        XCTAssertEqual(renderer.failure(documentID: incompleteExport.id, flow: exportMermaid)?.kind, .syntax)
        XCTAssertEqual(renderer.failure(documentID: incompleteExport.id, flow: exportMath)?.kind, .syntax,
                       "export preparation must inspect later flows after an earlier failure")

        let healthy = NoteTextFlow(id: UUID().uuidString, format: "markdown",
            source: "# 正常任务\n中文正文与公式 \\(x^2+y^2\\)", width: 420)
        var healthyNote = NoteDocument(id: UUID().uuidString); healthyNote.textFlows = [healthy]
        try await renderer.prepareAndWait(document: healthyNote)
        XCTAssertNotNil(CompiledTextCache.entry(healthy), "a prior syntax failure must release the shared worker")
    }

    func testMissingResourceAndOversizeInputStayVisibleWithoutAutomaticRetry() async throws {
        var limits = CompiledTextRenderer.Configuration()
        limits.maximumSourceBytes = 128
        let missing = CompiledTextRenderer(configuration: limits, resourceURLProvider: { nil })
        defer { missing.shutdown() }
        let flow = NoteTextFlow(id: UUID().uuidString, source: "本地资源缺失", width: 360)
        var note = NoteDocument(id: UUID().uuidString); note.textFlows = [flow]
        do {
            try await missing.prepareAndWait(document: note)
            XCTFail("missing reader.html must fail")
        } catch let failure as CompiledTextRenderFailure {
            XCTAssertEqual(failure.kind, .resourceMissing)
        }

        let oversized = NoteTextFlow(id: UUID().uuidString, source: String(repeating: "字", count: 1_000), width: 360)
        var oversizedNote = NoteDocument(id: UUID().uuidString); oversizedNote.textFlows = [oversized]
        let first = expectation(description: "oversize result")
        missing.prepare(document: oversizedNote) { first.fulfill() }
        await fulfillment(of: [first], timeout: 2)
        let state = try XCTUnwrap(missing.state(documentID: oversizedNote.id, flow: oversized))
        XCTAssertEqual(state.failure?.kind, .tooLarge)
        let redundantPrepare = expectation(description: "unchanged failure does not redraw canvas")
        redundantPrepare.isInverted = true
        missing.prepare(document: oversizedNote) { redundantPrepare.fulfill() }
        await fulfillment(of: [redundantPrepare], timeout: 0.2)
        XCTAssertEqual(missing.state(documentID: oversizedNote.id, flow: oversized)?.generation, state.generation,
                       "ordinary canvas redraws must not retry the same failed digest")
        for attempt in 1...2 {
            let oversizeRetry = expectation(description: "oversize retry \(attempt)")
            var oversizeRetryFailure: CompiledTextRenderFailure?
            missing.retry(documentID: oversizedNote.id, flow: oversized) { result in
                if case .failure(let error) = result { oversizeRetryFailure = error as? CompiledTextRenderFailure }
                oversizeRetry.fulfill()
            }
            await fulfillment(of: [oversizeRetry], timeout: 2)
            XCTAssertEqual(oversizeRetryFailure?.kind, .tooLarge,
                           "every explicit retry must revalidate the source budget instead of entering WebKit")
        }

        let invalidWidth = NoteTextFlow(id: UUID().uuidString, source: "窄", width: 20)
        var invalidWidthNote = NoteDocument(id: UUID().uuidString); invalidWidthNote.textFlows = [invalidWidth]
        let invalidFirst = expectation(description: "invalid width result")
        missing.prepare(document: invalidWidthNote) { invalidFirst.fulfill() }
        await fulfillment(of: [invalidFirst], timeout: 2)
        XCTAssertEqual(missing.failure(documentID: invalidWidthNote.id, flow: invalidWidth)?.kind, .tooLarge)
        let invalidRetry = expectation(description: "invalid width retry")
        var invalidRetryFailure: CompiledTextRenderFailure?
        missing.retry(documentID: invalidWidthNote.id, flow: invalidWidth) { result in
            if case .failure(let error) = result { invalidRetryFailure = error as? CompiledTextRenderFailure }
            invalidRetry.fulfill()
        }
        await fulfillment(of: [invalidRetry], timeout: 2)
        XCTAssertEqual(invalidRetryFailure?.kind, .tooLarge,
                       "explicit retry must revalidate dimensions instead of entering WebKit")
        XCTAssertFalse(NoteTextLayout.fragments(oversized, pageHeight: oversizedNote.pageHeight).isEmpty,
                       "failure must retain a non-zero editable CoreText fallback")
    }

    func testJavaScriptDeadlineCancellationAndSharedWaitersLeaveQueueUsable() async throws {
        let url = try makeControlledReader()
        var configuration = CompiledTextRenderer.Configuration()
        configuration.loadTimeout = 2
        configuration.javaScriptTimeout = 0.25
        configuration.snapshotTimeout = 2
        let renderer = CompiledTextRenderer(configuration: configuration, resourceURLProvider: { url })
        defer { renderer.shutdown() }

        let hanging = NoteTextFlow(id: UUID().uuidString, source: "HANG", width: 320)
        do {
            _ = try await renderer.renderPreview(flow: hanging, owner: "timeout")
            XCTFail("never-resolving JavaScript must hit its independent deadline")
        } catch let failure as CompiledTextRenderFailure {
            XCTAssertEqual(failure.kind, .timeout)
            XCTAssertEqual(failure.stage, "javascript")
        }

        let shared = NoteTextFlow(id: UUID().uuidString, source: "NORMAL-SHARED", width: 320)
        let cancelled = Task { try await renderer.renderPreview(flow: shared, owner: "cancelled") }
        let surviving = Task { try await renderer.renderPreview(flow: shared, owner: "surviving") }
        try await Task.sleep(nanoseconds: 20_000_000)
        cancelled.cancel()
        do { _ = try await cancelled.value; XCTFail("cancelled waiter must return promptly") }
        catch { XCTAssertTrue(error is CancellationError) }
        let survivingUnits = try await surviving.value
        XCTAssertFalse(survivingUnits.isEmpty, "cancelling one caller must not cancel another shared caller")

        let next = NoteTextFlow(id: UUID().uuidString, source: "NORMAL-AFTER-FAILURE", width: 320)
        let nextUnits = try await renderer.renderPreview(flow: next, owner: "next")
        XCTAssertFalse(nextUnits.isEmpty, "timeout and cancellation must not poison the worker queue")

        let cancelledExportFlow = NoteTextFlow(id: UUID().uuidString, source: "HANG", width: 320)
        let mustNotStart = NoteTextFlow(id: UUID().uuidString, source: "NORMAL-NOT-STARTED", width: 320)
        var cancelledExport = NoteDocument(id: UUID().uuidString)
        cancelledExport.textFlows = [cancelledExportFlow, mustNotStart]
        let exportTask = Task { try await renderer.prepareExportSnapshot(document: cancelledExport) }
        try await Task.sleep(nanoseconds: 20_000_000)
        exportTask.cancel()
        do { _ = try await exportTask.value; XCTFail("cancelled export must stop immediately") }
        catch { XCTAssertTrue(error is CancellationError) }
        XCTAssertNil(renderer.state(documentID: cancelledExport.id, flow: mustNotStart),
                     "cancellation must not continue compiling later flows")

        var budgetConfiguration = configuration
        budgetConfiguration.maximumExportPixels = 100
        budgetConfiguration.maximumExportBytes = 400
        let budgetRenderer = CompiledTextRenderer(configuration: budgetConfiguration, resourceURLProvider: { url })
        defer { budgetRenderer.shutdown() }
        let budgetFlow = NoteTextFlow(id: UUID().uuidString, source: "NORMAL-BUDGET", width: 320)
        let laterFlow = NoteTextFlow(id: UUID().uuidString, source: "HANG", width: 320)
        var budgetNote = NoteDocument(id: UUID().uuidString); budgetNote.textFlows = [budgetFlow, laterFlow]
        do {
            _ = try await budgetRenderer.prepareExportSnapshot(document: budgetNote)
            XCTFail("strong export snapshots must have a whole-document bitmap budget")
        } catch let failure as CompiledTextRenderFailure {
            XCTAssertEqual(failure.kind, .tooLarge)
            XCTAssertEqual(failure.stage, "export-budget")
        }
        XCTAssertNil(budgetRenderer.state(documentID: budgetNote.id, flow: laterFlow),
                     "budget failure must release the partial snapshot and stop later rendering")
    }

    func testSnapshotBudgetIsRejectedBeforeCreatingHugeBitmapAndBlankFlowRemainsValid() async throws {
        let url = try makeControlledReader()
        var configuration = CompiledTextRenderer.Configuration()
        configuration.loadTimeout = 2; configuration.javaScriptTimeout = 2; configuration.snapshotTimeout = 2
        configuration.maximumSinglePixels = 100_000
        let renderer = CompiledTextRenderer(configuration: configuration, resourceURLProvider: { url })
        defer { renderer.shutdown() }
        let large = NoteTextFlow(id: UUID().uuidString, source: "LARGE", width: 600)
        do {
            _ = try await renderer.renderPreview(flow: large, owner: "large")
            XCTFail("the point-size chunk must be converted to pixel budget before snapshot allocation")
        } catch let failure as CompiledTextRenderFailure {
            XCTAssertEqual(failure.kind, .tooLarge)
            XCTAssertEqual(failure.stage, "snapshot")
        }

        var blank = NoteDocument(id: UUID().uuidString)
        blank.textFlows = [NoteTextFlow(id: UUID().uuidString, source: " \n\n ", width: 300)]
        try await renderer.prepareAndWait(document: blank)
        XCTAssertFalse(NoteTextLayout.fragments(blank.textFlows[0], pageHeight: blank.pageHeight).isEmpty)
    }

    func testVerifiedPDFKeepsStrongCompiledSnapshotAfterCacheEvictionAndIncludesTailPage() async throws {
        let renderer = CompiledTextRenderer(configuration: .init()) {
            Bundle.main.url(forResource: "reader", withExtension: "html", subdirectory: "Web")
        }
        defer { renderer.shutdown() }
        var note = NoteDocument(id: UUID().uuidString, pageWidth: 400, pageHeight: 300)
        let source = (1...12).map { "## 第 \($0) 节\n这是第 \($0) 节正文与公式 \\(x_{\($0)}^2+y^2\\)。" }.joined(separator: "\n\n")
        let flow = NoteTextFlow(id: UUID().uuidString, format: "markdown", source: source, width: 320,
                                anchorXInPage: 40, anchorYInPage: 40)
        note.textFlows = [flow]
        let compiled = try await renderer.prepareExportSnapshot(document: note)
        let units = try XCTUnwrap(compiled[flow.id])
        XCTAssertGreaterThan(units.count, 1)
        CompiledTextCache.remove(flow)
        XCTAssertNil(CompiledTextCache.entry(flow), "the test must evict the ordinary cache before export")
        let data = try NoteRenderer.exportVerifiedPDF(document: note, pdfURL: nil, compiledText: compiled)
        let pdf = try XCTUnwrap(PDFDocument(data: data))
        let expectedPages = try XCTUnwrap(NoteTextLayout.compiledFragments(units, flow: flow, pageHeight: note.pageHeight).last).page + 1
        XCTAssertGreaterThan(expectedPages, 1)
        XCTAssertEqual(pdf.pageCount, expectedPages)
        let page = try XCTUnwrap(pdf.page(at: pdf.pageCount - 1))
        let image = page.thumbnail(of: CGSize(width: 400, height: 300), for: .mediaBox)
        let attachment = XCTAttachment(image: image)
        attachment.name = "Verified compiled PDF tail after cache eviction"
        attachment.lifetime = .keepAlways
        add(attachment)
        XCTAssertTrue(imageContainsDarkContent(image), "the final compiled unit must remain in the exported PDF")
    }

    func testMathTextViewRendersVisibleRecoveryPanelInActualHost() async throws {
        let host = UIHostingController(rootView: MathTextView(source: "```mermaid\nflowchart LR\n A[unterminated --> B\n```"))
        let window = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
            .flatMap(\.windows).first(where: \.isKeyWindow))
        host.view.frame = CGRect(x: 0, y: 0, width: 360, height: 420)
        window.addSubview(host.view)
        defer { host.view.removeFromSuperview() }
        var snapshot: UIImage?
        let deadline = Date().addingTimeInterval(8)
        while Date() < deadline {
            try await Task.sleep(nanoseconds: 50_000_000)
            host.view.setNeedsLayout(); host.view.layoutIfNeeded()
            let rendered = UIGraphicsImageRenderer(bounds: host.view.bounds).image { _ in
                host.view.drawHierarchy(in: host.view.bounds, afterScreenUpdates: true)
            }
            snapshot = rendered
            if imageContainsOrangeWarning(rendered) { break }
        }
        let image = try XCTUnwrap(snapshot)
        XCTAssertTrue(host.view.window === window)
        XCTAssertEqual(host.view.bounds.width, 360, accuracy: 0.5)
        XCTAssertTrue(imageContainsOrangeWarning(image),
                      "the hosted error panel must visibly render its warning instead of remaining a spinner")
        let attachment = XCTAttachment(image: image)
        attachment.name = "Math card local render recovery actions"
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    private func makeControlledReader() throws -> URL {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("PadNote-render-test-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        addTeardownBlock { try? FileManager.default.removeItem(at: directory) }
        let html = """
        <!doctype html><meta charset="utf-8"><body><main id="content">render</main><script>
        window.preparePaper=async function(source){
          if(source==='HANG') return await new Promise(()=>{});
          const height=source==='LARGE'?1000:80;
          document.getElementById('content').textContent=source;
          document.getElementById('content').style.height=height+'px';
          return {chunks:[{top:0,height:height}],diagnostics:[]};
        };
        window.positionPaper=async function(){return true;};
        </script></body>
        """
        let url = directory.appendingPathComponent("reader.html")
        try Data(html.utf8).write(to: url)
        return url
    }

    private func imageContainsOrangeWarning(_ image: UIImage) -> Bool {
        guard let cg = image.cgImage else { return false }
        var pixels = [UInt8](repeating: 0, count: cg.width * cg.height * 4)
        guard let context = CGContext(data: &pixels, width: cg.width, height: cg.height, bitsPerComponent: 8,
                                      bytesPerRow: cg.width * 4, space: CGColorSpaceCreateDeviceRGB(),
                                      bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return false }
        context.draw(cg, in: CGRect(x: 0, y: 0, width: cg.width, height: cg.height))
        let stride = max(1, cg.width / 120)
        for y in Swift.stride(from: 0, to: cg.height, by: stride) {
            for x in Swift.stride(from: 0, to: cg.width, by: stride) {
                let offset = (y * cg.width + x) * 4
                let r = pixels[offset], g = pixels[offset + 1], b = pixels[offset + 2]
                if r > 180, g > 110, g < 245, b < 170 { return true }
            }
        }
        return false
    }

    private func imageContainsDarkContent(_ image: UIImage) -> Bool {
        guard let cg = image.cgImage else { return false }
        var pixels = [UInt8](repeating: 0, count: cg.width * cg.height * 4)
        guard let context = CGContext(data: &pixels, width: cg.width, height: cg.height, bitsPerComponent: 8,
                                      bytesPerRow: cg.width * 4, space: CGColorSpaceCreateDeviceRGB(),
                                      bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return false }
        context.draw(cg, in: CGRect(x: 0, y: 0, width: cg.width, height: cg.height))
        var count = 0
        for offset in stride(from: 0, to: pixels.count, by: 4) {
            if pixels[offset] < 125, pixels[offset + 1] < 125, pixels[offset + 2] < 125 { count += 1 }
        }
        return count > 30
    }
}
