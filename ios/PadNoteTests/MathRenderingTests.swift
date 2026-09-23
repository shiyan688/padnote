import XCTest
import WebKit
import UIKit
@testable import PadNote

@MainActor
final class MathRenderingTests: XCTestCase, WKNavigationDelegate {
    private var loaded: XCTestExpectation?
    private var hostedWebViews: [WKWebView] = []
    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) { loaded?.fulfill() }

    override func tearDown() {
        hostedWebViews.forEach { $0.removeFromSuperview() }
        hostedWebViews.removeAll()
        loaded = nil
        super.tearDown()
    }

    private func makeWeb(width: CGFloat = 520) -> WKWebView {
        let web = WKWebView(frame: CGRect(x: -2048, y: 0, width: width, height: 1024))
        let window = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene })
            .flatMap(\.windows).first(where: \.isKeyWindow)
        XCTAssertNotNil(window, "WKWebView rendering tests require the hosted test application's key window")
        window?.addSubview(web)
        hostedWebViews.append(web)
        return web
    }

    private func callRenderer(_ web: WKWebView, script: String, arguments: [String: Any],
                              timeout: TimeInterval = 20) async throws -> Any? {
        let completed = expectation(description: "Offline renderer call completes")
        var outcome: Result<Any?, Error>?
        Task { @MainActor in
            do {
                outcome = .success(try await web.callAsyncJavaScript(script, arguments: arguments,
                    in: nil, contentWorld: .page))
            } catch {
                outcome = .failure(error)
            }
            completed.fulfill()
        }
        await fulfillment(of: [completed], timeout: timeout)
        guard let outcome else { throw NSError(domain: "PadNoteTests", code: 3, userInfo: [NSLocalizedDescriptionKey: "Timed out waiting for the offline renderer"]) }
        return try outcome.get()
    }

    private func preparePaper(_ web: WKWebView, source: String, timeout: TimeInterval = 20) async throws {
        _ = try await callRenderer(web,
            script: "return await window.preparePaper(source, 16, 1.45, 'markdown');",
            arguments: ["source": source], timeout: timeout)
    }

    func testOfflineMathAndUntrustedText() async throws {
        let web = makeWeb()
        web.navigationDelegate = self
        let expectation = expectation(description: "Local renderer loads")
        loaded = expectation
        let url = try XCTUnwrap(Bundle.main.url(forResource: "reader", withExtension: "html", subdirectory: "Web"))
        web.loadFileURL(url, allowingReadAccessTo: url.deletingLastPathComponent())
        await fulfillment(of: [expectation], timeout: 15)
        let source = "# 测试\n\\[x^2\\]\n\\(y^2\\)\n$$a+b$$\n$c=d$\n<script>window.injected=true</script>"
        let result = try await callRenderer(web, script: "return await window.renderNote(source, 16);",
            arguments: ["source": source])
        let dictionary = try XCTUnwrap(result as? [String: Any])
        XCTAssertEqual(dictionary["formulas"] as? Int, 4)
        let injected = try await web.evaluateJavaScript("typeof window.injected")
        XCTAssertEqual(injected as? String, "undefined")
        let style = try await web.evaluateJavaScript("getComputedStyle(document.querySelector('.katex')).fontFamily")
        XCTAssertTrue((style as? String)?.contains("KaTeX") == true)
    }

    func testMarkdownSemanticsStayEscapedAndLinksDoNotNavigate() async throws {
        let web = makeWeb()
        web.navigationDelegate = self
        let expectation = expectation(description: "Local renderer loads for markdown")
        loaded = expectation
        let url = try XCTUnwrap(Bundle.main.url(forResource: "reader", withExtension: "html", subdirectory: "Web"))
        web.loadFileURL(url, allowingReadAccessTo: url.deletingLastPathComponent())
        await fulfillment(of: [expectation], timeout: 15)
        let source = """
        - **bold** and __strong__
        1. *italic* and _emphasis_ and ~~removed~~
        [safe label](https://evil.example/?script=1)
        <script>window.markdownInjected = true</script>
        ```
        <img src=x onerror=window.codeInjected=true>
        """
        _ = try await callRenderer(web, script: "return await window.renderNote(source, 16);",
            arguments: ["source": source])
        let counts = try await web.evaluateJavaScript("JSON.stringify({ul:document.querySelectorAll('ul').length,ol:document.querySelectorAll('ol').length,bold:document.querySelectorAll('strong').length,italic:document.querySelectorAll('em').length,del:document.querySelectorAll('del').length,links:document.querySelectorAll('a').length,labels:document.querySelectorAll('.md-link').length,code:document.querySelectorAll('pre').length})")
        let values = try XCTUnwrap((counts as? String)?.data(using: .utf8).flatMap { try? JSONSerialization.jsonObject(with: $0) } as? [String: Any])
        XCTAssertEqual(values["ul"] as? Int, 1); XCTAssertEqual(values["ol"] as? Int, 1)
        XCTAssertEqual(values["bold"] as? Int, 2); XCTAssertEqual(values["italic"] as? Int, 2)
        XCTAssertEqual(values["del"] as? Int, 1); XCTAssertEqual(values["links"] as? Int, 0); XCTAssertEqual(values["labels"] as? Int, 1)
        XCTAssertEqual(values["code"] as? Int, 1)
        let markdownInjected = try await web.evaluateJavaScript("typeof window.markdownInjected") as? String
        let codeInjected = try await web.evaluateJavaScript("typeof window.codeInjected") as? String
        XCTAssertEqual(markdownInjected, "undefined")
        XCTAssertEqual(codeInjected, "undefined")
        let html = try await web.evaluateJavaScript("document.getElementById('content').innerHTML") as? String
        XCTAssertFalse(html?.contains("<script>") == true)
        let injectedNodes = try await web.evaluateJavaScript("document.querySelectorAll('[onerror]').length") as? Int
        XCTAssertEqual(injectedNodes, 0)
    }

    func testOrderedListKeepsSourceNumbersAcrossBlankSeparators() async throws {
        let web = makeWeb()
        web.navigationDelegate = self
        let expectation = expectation(description: "Local renderer loads for ordered lists")
        loaded = expectation
        let url = try XCTUnwrap(Bundle.main.url(forResource: "reader", withExtension: "html", subdirectory: "Web"))
        web.loadFileURL(url, allowingReadAccessTo: url.deletingLastPathComponent())
        await fulfillment(of: [expectation], timeout: 15)
        let source = """
        3. 第三步
        4. 第四步

        5) 第五步

        1. 第一项
        1. 第二项
        1. 第三项
        """
        _ = try await callRenderer(web, script: "return await window.renderNote(source, 16);",
            arguments: ["source": source])
        let listShape = try await web.evaluateJavaScript("Array.from(document.querySelectorAll('ol')).map(list => `${list.start}:${list.children.length}:${list.querySelectorAll('li[value]').length}`).join(',')")
        XCTAssertEqual(listShape as? String, "3:2:0,5:1:0,1:3:0",
            "Each run keeps its source start while repeated 1. markers use normal sequential numbering")
    }

    func testBareLatexFormatRendersWithoutRawSource() async throws {
        let web = makeWeb()
        web.navigationDelegate = self
        let expectation = expectation(description: "Local renderer loads for latex")
        loaded = expectation
        let url = try XCTUnwrap(Bundle.main.url(forResource: "reader", withExtension: "html", subdirectory: "Web"))
        web.loadFileURL(url, allowingReadAccessTo: url.deletingLastPathComponent())
        await fulfillment(of: [expectation], timeout: 15)
        let result = try await callRenderer(web,
            script: "return await window.renderNote(source, 16, 'latex');",
            arguments: ["source": "\\frac{a^2+b^2}{c^2}"])
        let dictionary = try XCTUnwrap(result as? [String: Any])
        XCTAssertEqual(dictionary["formulas"] as? Int, 1)
        let rawHTML = try await web.evaluateJavaScript("document.getElementById('content').innerHTML")
        let html = try XCTUnwrap(rawHTML as? String)
        XCTAssertTrue(html.contains("katex"))
        XCTAssertFalse(html.contains("<p>"))
        let renderedTextValue = try await web.evaluateJavaScript("document.querySelector('.katex-html')?.textContent || ''")
        XCTAssertFalse((renderedTextValue as? String)?.contains("\\frac") == true)
        let outsideTextValue = try await web.evaluateJavaScript("Array.from(document.getElementById('content').childNodes).filter(n => n.nodeType === Node.TEXT_NODE && n.textContent.trim()).length")
        XCTAssertEqual(outsideTextValue as? Int, 0)
    }

    func testPaperLayoutRemovesBlankParagraphsAndPreservesFormulaAndDiagramGeometry() async throws {
        let web = makeWeb()
        web.navigationDelegate = self
        let expectation = expectation(description: "Local renderer loads for paper geometry")
        loaded = expectation
        let url = try XCTUnwrap(Bundle.main.url(forResource: "reader", withExtension: "html", subdirectory: "Web"))
        web.loadFileURL(url, allowingReadAccessTo: url.deletingLastPathComponent())
        await fulfillment(of: [expectation], timeout: 15)
        let terms = (1...42).map { "x_{\($0)}" }.joined(separator: "+")
        let source = """
        # 几何检查



        正文之间的 Markdown 空行只用于分段。
        \\[\(terms)=S\\]
        ```mermaid
        flowchart LR
          A --> B --> C --> D --> E --> F --> G
        ```
        ```mermaid
        flowchart TD
          A --> B --> C --> D --> E --> F --> G
        ```
        ```swift
        func value() -> Int {

          return 1
        }
        ```
        """
        try await preparePaper(web, source: source)
        let rawMetrics = try await web.evaluateJavaScript("""
        JSON.stringify({
          blankParagraphs:Array.from(document.querySelectorAll('p')).filter(p => !p.textContent.trim()).length,
          codeBlankLine:document.querySelector('pre.code-block')?.textContent.includes('\\n\\n') || false,
          formula:(() => { const f=document.querySelector('.katex-display > .katex'), r=f.getBoundingClientRect(), root=document.getElementById('content').getBoundingClientRect(); return {left:r.left,right:r.right,rootLeft:root.left,rootRight:root.right,fitted:f.classList.contains('fitted-formula'),wrapped:f.classList.contains('wrapped-formula')}; })(),
          diagrams:Array.from(document.querySelectorAll('.diagram svg')).map(svg => { const r=svg.getBoundingClientRect(), v=svg.viewBox.baseVal, root=document.getElementById('content').getBoundingClientRect(); return {width:r.width,height:r.height,naturalWidth:v.width,naturalHeight:v.height,center:r.left+r.width/2,rootCenter:root.left+root.width/2}; })
        })
        """)
        let metrics = try XCTUnwrap((rawMetrics as? String)?.data(using: .utf8).flatMap { try? JSONSerialization.jsonObject(with: $0) } as? [String: Any])
        XCTAssertEqual(metrics["blankParagraphs"] as? Int, 0)
        XCTAssertEqual(metrics["codeBlankLine"] as? Bool, true)
        let formula = try XCTUnwrap(metrics["formula"] as? [String: Any])
        XCTAssertTrue(formula["wrapped"] as? Bool == true || formula["fitted"] as? Bool == true)
        XCTAssertGreaterThanOrEqual(formula["left"] as? Double ?? -.infinity, formula["rootLeft"] as? Double ?? .infinity)
        XCTAssertLessThanOrEqual(formula["right"] as? Double ?? .infinity, (formula["rootRight"] as? Double ?? -.infinity) + 1)
        let diagrams = try XCTUnwrap(metrics["diagrams"] as? [[String: Any]])
        XCTAssertEqual(diagrams.count, 2)
        for diagram in diagrams {
            let width = try XCTUnwrap(diagram["width"] as? Double)
            let height = try XCTUnwrap(diagram["height"] as? Double)
            let naturalWidth = try XCTUnwrap(diagram["naturalWidth"] as? Double)
            let naturalHeight = try XCTUnwrap(diagram["naturalHeight"] as? Double)
            XCTAssertLessThanOrEqual(width, naturalWidth + 1, "Mermaid must never be enlarged beyond its natural viewBox")
            XCTAssertEqual(width / height, naturalWidth / naturalHeight, accuracy: 0.02)
            XCTAssertEqual(diagram["center"] as? Double ?? 0, diagram["rootCenter"] as? Double ?? 1, accuracy: 1)
        }
        XCTAssertGreaterThan(diagrams[0]["width"] as? Double ?? 0, diagrams[0]["height"] as? Double ?? 1)
        XCTAssertGreaterThan(diagrams[1]["height"] as? Double ?? 0, diagrams[1]["width"] as? Double ?? 1)

        let rawHeight = try await web.evaluateJavaScript("Math.ceil(document.getElementById('content').scrollHeight)")
        let height = try XCTUnwrap(rawHeight as? Int)
        web.frame.size.height = CGFloat(min(4096, max(1, height)))
        web.setNeedsLayout()
        web.layoutIfNeeded()
        _ = try await callRenderer(web, script: "return await window.positionPaper(0);", arguments: [:])
        let config = WKSnapshotConfiguration()
        config.rect = CGRect(x: 0, y: 0, width: 520, height: web.frame.height)
        config.afterScreenUpdates = true
        let image = try await web.takeSnapshot(configuration: config)
        let attachment = XCTAttachment(image: image)
        attachment.name = "WKWebView paper geometry – long formula, landscape and portrait Mermaid"
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
