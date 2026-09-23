import SwiftUI
import WebKit

/// All rendering resources are bundled. Source text is passed as a JavaScript
/// argument, never concatenated into executable HTML or script.
public struct MathTextView: UIViewRepresentable {
    public var source: String
    public var fontSize: Double
    public var format: String

    public init(source: String, fontSize: Double = 16, format: String = "markdown") {
        self.source = source
        self.fontSize = fontSize
        self.format = format
    }

    public func makeCoordinator() -> Coordinator { Coordinator() }

    public func makeUIView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = .nonPersistent()
        configuration.preferences.javaScriptCanOpenWindowsAutomatically = false
        let web = WKWebView(frame: .zero, configuration: configuration)
        web.navigationDelegate = context.coordinator
        web.isOpaque = false
        web.backgroundColor = .clear
        context.coordinator.web = web
        context.coordinator.source = source
        context.coordinator.fontSize = fontSize
        context.coordinator.format = format
        if let url = Bundle.main.url(forResource: "reader", withExtension: "html", subdirectory: "Web") {
            context.coordinator.resourceRoot = url.deletingLastPathComponent()
            web.loadFileURL(url, allowingReadAccessTo: url.deletingLastPathComponent())
        } else {
            web.loadHTMLString("<p>离线显示资源缺失，请重新构建应用。</p>", baseURL: nil)
        }
        return web
    }

    public func updateUIView(_ web: WKWebView, context: Context) {
        guard context.coordinator.source != source || context.coordinator.fontSize != fontSize || context.coordinator.format != format else { return }
        context.coordinator.source = source
        context.coordinator.fontSize = fontSize
        context.coordinator.format = format
        context.coordinator.render()
    }

    public final class Coordinator: NSObject, WKNavigationDelegate {
        weak var web: WKWebView?
        var source = ""
        var fontSize = 16.0
        var format = "markdown"
        var loaded = false
        var resourceRoot: URL?

        public func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            loaded = true
            render()
        }

        func render() {
            guard loaded else { return }
            web?.callAsyncJavaScript("return window.renderNote(source, size, format);",
                                     arguments: ["source": source, "size": max(10, min(32, fontSize)), "format": format],
                                     in: nil, in: .page, completionHandler: nil)
        }

        public func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction,
                            decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
            guard let url = action.request.url, let root = resourceRoot,
                  url.isFileURL, url.standardizedFileURL.path.hasPrefix(root.path + "/"),
                  action.navigationType != .linkActivated else {
                decisionHandler(.cancel)
                return
            }
            decisionHandler(.allow)
        }
    }
}
