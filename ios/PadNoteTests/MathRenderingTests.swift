import XCTest
import WebKit
@testable import PadNote

@MainActor
final class MathRenderingTests: XCTestCase, WKNavigationDelegate {
    private var loaded: XCTestExpectation?
    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) { loaded?.fulfill() }

    func testOfflineMathAndUntrustedText() async throws {
        let web = WKWebView()
        web.navigationDelegate = self
        let expectation = expectation(description: "Local renderer loads")
        loaded = expectation
        let url = try XCTUnwrap(Bundle.main.url(forResource: "reader", withExtension: "html", subdirectory: "Web"))
        web.loadFileURL(url, allowingReadAccessTo: url.deletingLastPathComponent())
        await fulfillment(of: [expectation], timeout: 15)
        let source = "# 测试\n\\[x^2\\]\n\\(y^2\\)\n$$a+b$$\n$c=d$\n<script>window.injected=true</script>"
        let result = try await web.callAsyncJavaScript("return await window.renderNote(source, 16);", arguments: ["source": source], in: nil, contentWorld: .page)
        let dictionary = try XCTUnwrap(result as? [String: Any])
        XCTAssertEqual(dictionary["formulas"] as? Int, 4)
        let injected = try await web.evaluateJavaScript("typeof window.injected")
        XCTAssertEqual(injected as? String, "undefined")
        let style = try await web.evaluateJavaScript("getComputedStyle(document.querySelector('.katex')).fontFamily")
        XCTAssertTrue((style as? String)?.contains("KaTeX") == true)
    }

    func testMarkdownSemanticsStayEscapedAndLinksDoNotNavigate() async throws {
        let web = WKWebView()
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
        _ = try await web.callAsyncJavaScript("return await window.renderNote(source, 16);", arguments: ["source": source], in: nil, contentWorld: .page)
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

    func testBareLatexFormatRendersWithoutRawSource() async throws {
        let web = WKWebView()
        web.navigationDelegate = self
        let expectation = expectation(description: "Local renderer loads for latex")
        loaded = expectation
        let url = try XCTUnwrap(Bundle.main.url(forResource: "reader", withExtension: "html", subdirectory: "Web"))
        web.loadFileURL(url, allowingReadAccessTo: url.deletingLastPathComponent())
        await fulfillment(of: [expectation], timeout: 15)
        let result = try await web.callAsyncJavaScript(
            "return await window.renderNote(source, 16, 'latex');",
            arguments: ["source": "\\frac{a^2+b^2}{c^2}"], in: nil, contentWorld: .page)
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
}
