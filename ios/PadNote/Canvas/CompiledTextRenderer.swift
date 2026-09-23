import UIKit
import WebKit
import CryptoKit

/// Disposable display data only. The portable TextFlow source remains the
/// authoritative document; cached images are never written into note JSON.
enum CompiledTextCache {
    struct Unit {
        let image: UIImage
        let size: CGSize
    }
    final class Entry {
        let units: [Unit]
        init(_ units: [Unit]) { self.units = units }
    }
    private static let cache: NSCache<NSString, Entry> = {
        let cache = NSCache<NSString, Entry>()
        cache.totalCostLimit = 96 * 1024 * 1024
        return cache
    }()
    static func key(_ flow: NoteTextFlow) -> NSString {
        let value = "\(flow.format)|\(flow.fontSizeSp)|\(flow.lineHeight)|\(flow.width)|\(flow.source)"
        return SHA256.hash(data: Data(value.utf8)).map { String(format: "%02x", $0) }.joined() as NSString
    }
    static func entry(_ flow: NoteTextFlow) -> Entry? { cache.object(forKey: key(flow)) }
    static func put(_ units: [Unit], for flow: NoteTextFlow) {
        let bytes = units.reduce(0) { $0 + ($1.image.cgImage.map { $0.bytesPerRow * $0.height } ?? 0) }
        cache.setObject(Entry(units), forKey: key(flow), cost: bytes)
    }
}

/// A single offline WebKit worker compiles semantic blocks, then snapshots
/// them. Ink remains native UIKit; no web view intercepts paper gestures.
@MainActor
final class CompiledTextRenderer: NSObject, WKNavigationDelegate {
    static let shared = CompiledTextRenderer()
    private var web: WKWebView?
    private var loadContinuation: CheckedContinuation<Void, Error>?
    private var pending: [(NoteTextFlow, () -> Void)] = []
    private var queued = Set<NSString>()
    private var worker: Task<Void, Never>?
    private var root: URL?
    private var compiling = false

    func prepare(document: NoteDocument, completion: @escaping () -> Void) {
        for flow in document.textFlows where !flow.source.isEmpty && CompiledTextCache.entry(flow) == nil {
            let obsolete = pending.filter { $0.0.id == flow.id && CompiledTextCache.key($0.0) != CompiledTextCache.key(flow) }
            obsolete.forEach { queued.remove(CompiledTextCache.key($0.0)) }
            pending.removeAll { $0.0.id == flow.id && CompiledTextCache.key($0.0) != CompiledTextCache.key(flow) }
            let key = CompiledTextCache.key(flow)
            guard queued.insert(key).inserted else { continue }
            pending.append((flow, completion))
        }
        guard worker == nil, !pending.isEmpty else { return }
        worker = Task { [weak self] in
            guard let self else { return }
            while !self.pending.isEmpty {
                let (flow, callback) = self.pending.removeFirst()
                do {
                    let units = try await self.compile(flow)
                    CompiledTextCache.put(units, for: flow)
                    callback()
                } catch {
                    // CoreText remains available if WebKit cannot render a
                    // malformed/oversize object. Never discard source text.
                }
                self.queued.remove(CompiledTextCache.key(flow))
            }
            self.worker = nil
        }
    }

    func prepareAndWait(document: NoteDocument) async throws {
        for flow in document.textFlows where !flow.source.isEmpty && CompiledTextCache.entry(flow) == nil {
            // This API also serializes exports with the background worker.
            while worker != nil { try await Task.sleep(nanoseconds: 20_000_000) }
            if CompiledTextCache.entry(flow) == nil {
                let units = try await compile(flow)
                CompiledTextCache.put(units, for: flow)
            }
        }
    }

    private func readyWeb(width: Double) async throws -> WKWebView {
        if let web {
            web.frame.size = CGSize(width: min(4096, max(40, width)), height: 1024)
            return web
        }
        let config = WKWebViewConfiguration()
        config.websiteDataStore = .nonPersistent()
        config.preferences.javaScriptCanOpenWindowsAutomatically = false
        let view = WKWebView(frame: CGRect(x: 0, y: 0, width: min(4096, max(40, width)), height: 1024), configuration: config)
        view.isOpaque = false
        view.backgroundColor = .clear
        view.scrollView.backgroundColor = .clear
        view.scrollView.isScrollEnabled = false
        view.scrollView.contentInsetAdjustmentBehavior = .never
        view.scrollView.contentInset = .zero
        view.isUserInteractionEnabled = false
        view.navigationDelegate = self
        guard let url = Bundle.main.url(forResource: "reader", withExtension: "html", subdirectory: "Web") else {
            throw CocoaError(.fileNoSuchFile)
        }
        root = url.deletingLastPathComponent()
        // Mounted outside the visible viewport so WebKit has a live rendering
        // context, without adding a touch target or changing visible content.
        if let window = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene })
            .flatMap(\.windows).first(where: \.isKeyWindow) {
            view.frame.origin = CGPoint(x: -8192, y: 0)
            window.addSubview(view)
        }
        web = view
        try await withCheckedThrowingContinuation { continuation in
            loadContinuation = continuation
            view.loadFileURL(url, allowingReadAccessTo: url.deletingLastPathComponent())
        }
        return view
    }

    private func compile(_ flow: NoteTextFlow) async throws -> [CompiledTextCache.Unit] {
        while compiling { try await Task.sleep(nanoseconds: 20_000_000) }
        compiling = true
        defer { compiling = false }
        try Task.checkCancellation()
        let web = try await readyWeb(width: flow.width)
        let width = web.bounds.width
        let value = try await web.callAsyncJavaScript("return await window.preparePaper(source, size, spacing, format);",
            arguments: ["source": flow.source, "size": flow.fontSizeSp, "spacing": flow.lineHeight, "format": flow.format],
            in: nil, contentWorld: .page)
        guard let metrics = value as? [String: Any], let chunks = metrics["chunks"] as? [[String: Any]], chunks.count <= 4096 else {
            throw CocoaError(.coderInvalidValue)
        }
        var units: [CompiledTextCache.Unit] = []
        for chunk in chunks {
            try Task.checkCancellation()
            guard let top = chunk["top"] as? Double, let height = chunk["height"] as? Double,
                  height > 0, height <= 4096, top.isFinite else { throw CocoaError(.coderInvalidValue) }
            web.frame.size.height = ceil(height)
            _ = try await web.callAsyncJavaScript("return await window.positionPaper(top);", arguments: ["top": top], in: nil, contentWorld: .page)
            let config = WKSnapshotConfiguration()
            config.rect = CGRect(x: 0, y: 0, width: width, height: ceil(height))
            config.snapshotWidth = NSNumber(value: Double(width))
            config.afterScreenUpdates = true
            let image = try await web.takeSnapshot(configuration: config)
            units.append(.init(image: image, size: CGSize(width: flow.width, height: height * flow.width / width)))
        }
        return units
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        loadContinuation?.resume(); loadContinuation = nil
    }
    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        loadContinuation?.resume(throwing: error); loadContinuation = nil
        web?.removeFromSuperview(); web = nil
    }
    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        self.webView(webView, didFail: navigation, withError: error)
    }
    func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        guard let url = action.request.url, let root, url.isFileURL,
              url.standardizedFileURL.path.hasPrefix(root.path + "/"), action.navigationType != .linkActivated else {
            decisionHandler(.cancel); return
        }
        decisionHandler(.allow)
    }
}
