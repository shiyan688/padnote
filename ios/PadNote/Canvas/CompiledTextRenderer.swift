import UIKit
import WebKit
import CryptoKit
import Combine

enum CompiledTextRenderFailureKind: String, Equatable {
    case resourceMissing, syntax, timeout, tooLarge, webProcessTerminated, cancelled, invalidResponse
}

struct CompiledTextRenderFailure: Error, LocalizedError, Equatable {
    let kind: CompiledTextRenderFailureKind
    let stage: String

    var errorDescription: String? {
        switch kind {
        case .resourceMissing: return "本地显示资源缺失。源码仍已保留，可修复应用后重试。"
        case .syntax: return "公式或图表语法无法显示。请查看并编辑源码后重试。"
        case .timeout: return "本地排版超时。源码仍已保留，可稍后重试。"
        case .tooLarge: return "内容超过本地排版限制。请编辑或拆分源码后重试。"
        case .webProcessTerminated: return "本地显示进程已退出。源码仍已保留，可重试。"
        case .cancelled: return "本地排版已取消。"
        case .invalidResponse: return "本地排版没有生成完整结果。请查看源码后重试。"
        }
    }
}

struct CompiledTextRenderState: Equatable {
    enum Phase: Equatable { case queued, rendering, succeeded, failed }
    let digest: String
    let generation: Int
    let phase: Phase
    let failure: CompiledTextRenderFailure?
}

/// Disposable display data only. The portable TextFlow source remains the
/// authoritative document; cached images are never written into note JSON.
enum CompiledTextCache {
    static let rendererVersion = "paper-v3-recovery"
    struct Unit { let image: UIImage; let size: CGSize }
    final class Entry {
        let units: [Unit]
        init(_ units: [Unit]) { self.units = units }
    }
    private static let cache: NSCache<NSString, Entry> = {
        let cache = NSCache<NSString, Entry>()
        cache.totalCostLimit = 96 * 1024 * 1024
        return cache
    }()
    private struct Signature: Equatable {
        let format: String, source: String
        let fontSize, lineHeight, width: Double
        init(_ flow: NoteTextFlow) {
            format = flow.format; source = flow.source; fontSize = flow.fontSizeSp
            lineHeight = flow.lineHeight; width = flow.width
        }
    }
    private static let memoLock = NSLock()
    private static var digestMemo: [String: (Signature, String)] = [:]
    static func digest(_ flow: NoteTextFlow) -> String {
        let signature = Signature(flow)
        memoLock.lock()
        if let memo = digestMemo[flow.id], memo.0 == signature { memoLock.unlock(); return memo.1 }
        memoLock.unlock()
        var hash = SHA256()
        hash.update(data: Data("\(rendererVersion)|\(flow.format)|\(flow.fontSizeSp)|\(flow.lineHeight)|\(flow.width)|".utf8))
        hash.update(data: Data(flow.source.utf8))
        let value = hash.finalize().map { String(format: "%02x", $0) }.joined()
        memoLock.lock()
        if digestMemo.count >= 512 { digestMemo.removeValue(forKey: digestMemo.keys.first ?? "") }
        digestMemo[flow.id] = (signature, value)
        memoLock.unlock()
        return value
    }
    static func key(_ flow: NoteTextFlow) -> NSString { digest(flow) as NSString }
    static func entry(_ flow: NoteTextFlow) -> Entry? {
        guard flow.source.utf8.count <= 2 * 1024 * 1024 else { return nil }
        return cache.object(forKey: key(flow))
    }
    static func put(_ units: [Unit], for flow: NoteTextFlow) {
        let bytes = units.reduce(0) { $0 + ($1.image.cgImage.map { $0.bytesPerRow * $0.height } ?? 0) }
        cache.setObject(Entry(units), forKey: key(flow), cost: bytes)
    }
    static func remove(_ flow: NoteTextFlow) { cache.removeObject(forKey: key(flow)) }
}

private final class RenderContinuationGate<Value>: @unchecked Sendable {
    private let lock = NSLock()
    private var continuation: CheckedContinuation<Value, Error>?
    private var result: Result<Value, Error>?

    func install(_ continuation: CheckedContinuation<Value, Error>) {
        lock.lock()
        if let result {
            lock.unlock()
            continuation.resume(with: result)
        } else {
            self.continuation = continuation
            lock.unlock()
        }
    }

    @discardableResult
    func resolve(_ result: Result<Value, Error>) -> Bool {
        lock.lock()
        guard self.result == nil else { lock.unlock(); return false }
        self.result = result
        let continuation = self.continuation
        self.continuation = nil
        lock.unlock()
        continuation?.resume(with: result)
        return true
    }
}

/// One bounded offline WebKit worker serves paper, previews, and PDF export.
/// Callers share pure render work while retaining independent cancellation and
/// generation tokens, so one view cannot invalidate another view's result.
@MainActor
final class CompiledTextRenderer: NSObject, ObservableObject, WKNavigationDelegate {
    struct Configuration {
        var loadTimeout: TimeInterval = 8
        var javaScriptTimeout: TimeInterval = 12
        var snapshotTimeout: TimeInterval = 8
        var maximumSourceBytes = 2 * 1024 * 1024
        var maximumQueueSourceBytes = 8 * 1024 * 1024
        var maximumJobs = 128
        var maximumChunks = 128
        var maximumChunkHeight = 4096
        var maximumSinglePixels = 8_000_000
        var maximumTotalPixels = 24_000_000
        var maximumExportPixels = 24_000_000
        var maximumExportBytes = 96 * 1024 * 1024
    }

    static let shared = CompiledTextRenderer()
    @Published private(set) var changeToken = 0

    private struct Waiter {
        let id: UUID
        let owner: String
        let generation: Int
        let completion: (Result<[CompiledTextCache.Unit], Error>) -> Void
    }
    private final class Job {
        let digest: String
        let flow: NoteTextFlow
        var waiters: [UUID: Waiter]
        var accepting = true
        init(digest: String, flow: NoteTextFlow, waiter: Waiter) {
            self.digest = digest; self.flow = flow; waiters = [waiter.id: waiter]
        }
    }
    private struct ActiveStage { let id: UUID; let abort: (Error) -> Void }
    private struct RenderInput: Equatable {
        let format: String, source: String
        let fontSize, lineHeight, width: Double
        init(_ flow: NoteTextFlow) {
            format = flow.format; source = flow.source; fontSize = flow.fontSizeSp
            lineHeight = flow.lineHeight; width = flow.width
        }
    }

    private let configuration: Configuration
    private let resourceURLProvider: () -> URL?
    private var web: WKWebView?
    private var resourceRoot: URL?
    private var navigationCompletion: ((Result<Void, Error>) -> Void)?
    private var activeStage: ActiveStage?
    private var queue: [Job] = []
    private var activeJob: Job?
    private var worker: Task<Void, Never>?
    private var states: [String: CompiledTextRenderState] = [:]
    private var generations: [String: Int] = [:]
    private var inputs: [String: RenderInput] = [:]
    private var digests: [String: String] = [:]

    override convenience init() {
        self.init(configuration: Configuration()) {
            Bundle.main.url(forResource: "reader", withExtension: "html", subdirectory: "Web")
        }
    }

    init(configuration: Configuration, resourceURLProvider: @escaping () -> URL?) {
        self.configuration = configuration
        self.resourceURLProvider = resourceURLProvider
        super.init()
    }

    func state(documentID: String, flow: NoteTextFlow) -> CompiledTextRenderState? {
        states[owner(documentID: documentID, flowID: flow.id)]
    }

    func failure(documentID: String, flow: NoteTextFlow) -> CompiledTextRenderFailure? {
        let owner = owner(documentID: documentID, flowID: flow.id)
        guard let state = states[owner], inputs[owner] == RenderInput(flow),
              state.phase == .failed else { return nil }
        return state.failure
    }

    func failures(document: NoteDocument) -> [(NoteTextFlow, CompiledTextRenderFailure)] {
        document.textFlows.compactMap { flow in failure(documentID: document.id, flow: flow).map { (flow, $0) } }
    }

    func prepare(document: NoteDocument, completion: @escaping () -> Void) {
        synchronizeOwners(with: document)
        for flow in document.textFlows where !flow.source.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            let owner = owner(documentID: document.id, flowID: flow.id)
            let digest: String
            do { digest = try resolvedDigest(flow, owner: owner) }
            catch {
                recordInputFailure(error, flow: flow, owner: owner, completion: completion)
                continue
            }
            guard CompiledTextCache.entry(flow) == nil else {
                record(.succeeded, owner: owner, digest: digest, failure: nil)
                continue
            }
            if hasWaiter(owner: owner, digest: digest) { continue }
            enqueue(flow: flow, owner: owner, knownDigest: digest, retry: false) { _ in completion() }
        }
    }

    func prepareAndWait(document: NoteDocument) async throws {
        synchronizeOwners(with: document)
        for flow in document.textFlows where !flow.source.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && CompiledTextCache.entry(flow) == nil {
            _ = try await render(flow: flow, owner: owner(documentID: document.id, flowID: flow.id), retry: false)
        }
    }

    /// Returns strong references for a complete PDF operation. The ordinary
    /// cache is deliberately not the export contract because NSCache may evict
    /// an earlier flow while later flows are still being compiled.
    func prepareExportSnapshot(document: NoteDocument) async throws -> [String: [CompiledTextCache.Unit]] {
        synchronizeOwners(with: document)
        var output: [String: [CompiledTextCache.Unit]] = [:]
        var firstError: Error?
        var totalPixels = 0
        var totalBytes = 0
        for flow in document.textFlows where !flow.source.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            try Task.checkCancellation()
            do {
                let units = try await render(flow: flow, owner: owner(documentID: document.id, flowID: flow.id), retry: false)
                try Task.checkCancellation()
                guard !units.isEmpty else {
                    throw CompiledTextRenderFailure(kind: .invalidResponse, stage: "export")
                }
                for unit in units {
                    guard let image = unit.image.cgImage else {
                        throw CompiledTextRenderFailure(kind: .invalidResponse, stage: "export")
                    }
                    let pixels = image.width.multipliedReportingOverflow(by: image.height)
                    let bytes = image.bytesPerRow.multipliedReportingOverflow(by: image.height)
                    guard !pixels.overflow, !bytes.overflow,
                          totalPixels <= configuration.maximumExportPixels - pixels.partialValue,
                          totalBytes <= configuration.maximumExportBytes - bytes.partialValue else {
                        throw CompiledTextRenderFailure(kind: .tooLarge, stage: "export-budget")
                    }
                    totalPixels += pixels.partialValue
                    totalBytes += bytes.partialValue
                }
                output[flow.id] = units
            } catch is CancellationError {
                throw CancellationError()
            } catch let failure as CompiledTextRenderFailure where failure.stage == "export-budget" {
                throw failure
            } catch {
                if firstError == nil { firstError = error }
            }
        }
        if let firstError { throw firstError }
        return output
    }

    func retry(documentID: String, flow: NoteTextFlow, completion: @escaping (Result<Void, Error>) -> Void) {
        enqueue(flow: flow, owner: owner(documentID: documentID, flowID: flow.id), retry: true) {
            completion($0.map { _ in () })
        }
    }

    func renderPreview(flow: NoteTextFlow, owner: String, retry: Bool = false) async throws -> [CompiledTextCache.Unit] {
        try await render(flow: flow, owner: "preview:\(owner)", retry: retry)
    }

    func cancelPreview(owner: String) { cancel(owner: "preview:\(owner)", clearState: true) }

    func release(documentID: String) {
        let prefix = "note:\(documentID):flow:"
        let owners = Set(states.keys.filter { $0.hasPrefix(prefix) })
            .union(queue.flatMap { $0.waiters.values.map(\.owner).filter { $0.hasPrefix(prefix) } })
            .union(activeJob?.waiters.values.map(\.owner).filter { $0.hasPrefix(prefix) } ?? [])
        owners.forEach { cancel(owner: $0, clearState: true) }
        generations = generations.filter { !$0.key.hasPrefix(prefix) }
    }

    func shutdown() {
        let owners = Set(states.keys)
            .union(queue.flatMap { $0.waiters.values.map(\.owner) })
            .union(activeJob?.waiters.values.map(\.owner) ?? [])
        owners.forEach { cancel(owner: $0, clearState: true) }
        queue.removeAll(); activeJob = nil; worker?.cancel(); worker = nil
        activeStage?.abort(CompiledTextRenderFailure(kind: .cancelled, stage: "shutdown"))
        activeStage = nil; navigationCompletion = nil; resetWeb()
    }

    private func render(flow: NoteTextFlow, owner: String, retry: Bool) async throws -> [CompiledTextCache.Unit] {
        let waiterID = UUID()
        let gate = RenderContinuationGate<[CompiledTextCache.Unit]>()
        return try await withTaskCancellationHandler(operation: {
            try await withCheckedThrowingContinuation { continuation in
                gate.install(continuation)
                enqueue(flow: flow, owner: owner, waiterID: waiterID, retry: retry) { gate.resolve($0) }
            }
        }, onCancel: {
            gate.resolve(.failure(CancellationError()))
            Task { @MainActor [weak self] in self?.cancelWaiter(waiterID, owner: owner) }
        })
    }

    private func enqueue(flow: NoteTextFlow, owner: String, waiterID: UUID = UUID(), knownDigest: String? = nil, retry: Bool,
                         completion: @escaping (Result<[CompiledTextCache.Unit], Error>) -> Void) {
        let digest: String
        do { digest = try knownDigest ?? resolvedDigest(flow, owner: owner) }
        catch { recordInputFailure(error, flow: flow, owner: owner, completion: nil); completion(.failure(normalized(error))); return }
        if let cached = CompiledTextCache.entry(flow) {
            record(.succeeded, owner: owner, digest: digest, failure: nil)
            completion(.success(cached.units)); return
        }
        if let state = states[owner], state.digest == digest, state.phase == .failed, !retry,
           let failure = state.failure { completion(.failure(failure)); return }
        if states[owner]?.digest != digest || retry {
            cancel(owner: owner, clearState: false)
        }
        let generation = max(1, generations[owner, default: 1])
        generations[owner] = generation
        let waiter = Waiter(id: waiterID, owner: owner, generation: generation, completion: completion)
        if let activeJob, activeJob.accepting, activeJob.digest == digest {
            activeJob.waiters[waiter.id] = waiter
            record(.rendering, owner: owner, digest: digest, failure: nil, generation: generation)
            return
        }
        if let existing = queue.first(where: { $0.digest == digest }) {
            existing.waiters[waiter.id] = waiter
            record(.queued, owner: owner, digest: digest, failure: nil, generation: generation)
            return
        }
        let queuedBytes = queue.reduce(0) { $0 + $1.flow.source.utf8.count } + (activeJob?.flow.source.utf8.count ?? 0)
        guard queue.count + (activeJob == nil ? 0 : 1) < configuration.maximumJobs,
              queuedBytes + flow.source.utf8.count <= configuration.maximumQueueSourceBytes else {
            let failure = CompiledTextRenderFailure(kind: .tooLarge, stage: "queue")
            record(.failed, owner: owner, digest: digest, failure: failure, generation: generation)
            completion(.failure(failure)); return
        }
        queue.append(Job(digest: digest, flow: flow, waiter: waiter))
        record(.queued, owner: owner, digest: digest, failure: nil, generation: generation)
        startWorker()
    }

    private func startWorker() {
        guard worker == nil else { return }
        worker = Task { [weak self] in await self?.runWorker() }
    }

    private func runWorker() async {
        while !queue.isEmpty {
            let job = queue.removeFirst()
            activeJob = job
            for waiter in job.waiters.values where isCurrent(waiter, digest: job.digest) {
                record(.rendering, owner: waiter.owner, digest: job.digest, failure: nil, generation: waiter.generation)
            }
            do {
                guard !job.waiters.isEmpty else { throw CancellationError() }
                let units = try await compile(job.flow)
                guard !job.waiters.isEmpty else { throw CancellationError() }
                CompiledTextCache.put(units, for: job.flow)
                job.accepting = false
                activeJob = nil
                finish(job: job, result: .success(units))
            } catch {
                job.accepting = false
                activeJob = nil
                finish(job: job, result: .failure(normalized(error)))
            }
        }
        worker = nil
    }

    private func finish(job: Job, result: Result<[CompiledTextCache.Unit], Error>) {
        for waiter in job.waiters.values {
            guard isCurrent(waiter, digest: job.digest) else {
                waiter.completion(.failure(CancellationError())); continue
            }
            switch result {
            case .success:
                record(.succeeded, owner: waiter.owner, digest: job.digest, failure: nil, generation: waiter.generation)
            case .failure(let error):
                let failure = normalized(error)
                if failure.kind != .cancelled {
                    record(.failed, owner: waiter.owner, digest: job.digest, failure: failure, generation: waiter.generation)
                }
            }
            waiter.completion(result)
        }
        job.waiters.removeAll()
    }

    private func isCurrent(_ waiter: Waiter, digest: String) -> Bool {
        generations[waiter.owner] == waiter.generation && states[waiter.owner]?.digest == digest
    }

    private func hasWaiter(owner: String, digest: String) -> Bool {
        if let activeJob, activeJob.digest == digest,
           activeJob.waiters.values.contains(where: { $0.owner == owner }) { return true }
        return queue.contains { $0.digest == digest && $0.waiters.values.contains(where: { $0.owner == owner }) }
    }

    private func cancelWaiter(_ id: UUID, owner: String) {
        if let activeJob, let waiter = activeJob.waiters.removeValue(forKey: id) {
            waiter.completion(.failure(CancellationError()))
            if activeJob.waiters.isEmpty {
                activeJob.accepting = false
                abortActiveWork(CompiledTextRenderFailure(kind: .cancelled, stage: "request"))
            }
            return
        }
        for job in queue {
            if let waiter = job.waiters.removeValue(forKey: id) { waiter.completion(.failure(CancellationError())); break }
        }
        queue.removeAll { $0.waiters.isEmpty }
    }

    private func cancel(owner: String, clearState: Bool) {
        advanceGeneration(owner)
        if let activeJob {
            let removed = activeJob.waiters.values.filter { $0.owner == owner }
            activeJob.waiters = activeJob.waiters.filter { $0.value.owner != owner }
            removed.forEach { $0.completion(.failure(CancellationError())) }
            if activeJob.waiters.isEmpty && !removed.isEmpty {
                activeJob.accepting = false
                abortActiveWork(CompiledTextRenderFailure(kind: .cancelled, stage: "generation"))
            }
        }
        for job in queue {
            let removed = job.waiters.values.filter { $0.owner == owner }
            job.waiters = job.waiters.filter { $0.value.owner != owner }
            removed.forEach { $0.completion(.failure(CancellationError())) }
        }
        queue.removeAll { $0.waiters.isEmpty }
        if clearState { states.removeValue(forKey: owner); changeToken += 1 }
        if clearState { inputs.removeValue(forKey: owner); digests.removeValue(forKey: owner); generations.removeValue(forKey: owner) }
    }

    private func advanceGeneration(_ owner: String) {
        let current = generations[owner, default: 0]
        generations[owner] = current >= 1_000_000_000 ? 1 : current + 1
    }

    private func synchronizeOwners(with document: NoteDocument) {
        let prefix = "note:\(document.id):flow:"
        let current = Set(document.textFlows.map { owner(documentID: document.id, flowID: $0.id) })
        for key in states.keys where key.hasPrefix(prefix) && !current.contains(key) { cancel(owner: key, clearState: true) }
    }

    private func owner(documentID: String, flowID: String) -> String { "note:\(documentID):flow:\(flowID)" }

    private func record(_ phase: CompiledTextRenderState.Phase, owner: String, digest: String,
                        failure: CompiledTextRenderFailure?, generation: Int? = nil) {
        let value = CompiledTextRenderState(digest: digest, generation: generation ?? generations[owner, default: 1],
                                            phase: phase, failure: failure)
        guard states[owner] != value else { return }
        states[owner] = value; changeToken += 1
    }

    private func validateInput(_ flow: NoteTextFlow) throws {
        guard flow.source.utf8.count <= configuration.maximumSourceBytes,
              flow.width.isFinite, flow.width >= 40, flow.width <= 4096,
              flow.fontSizeSp.isFinite, flow.lineHeight.isFinite else {
            throw CompiledTextRenderFailure(kind: .tooLarge, stage: "input")
        }
    }

    private func resolvedDigest(_ flow: NoteTextFlow, owner: String) throws -> String {
        let input = RenderInput(flow)
        if inputs[owner] == input, let digest = digests[owner] { return digest }
        try validateInput(flow)
        let digest = CompiledTextCache.digest(flow)
        inputs[owner] = input; digests[owner] = digest
        return digest
    }

    private func recordInputFailure(_ error: Error, flow: NoteTextFlow, owner: String, completion: (() -> Void)?) {
        let input = RenderInput(flow)
        let sameInput = inputs[owner] == input
        if !sameInput { cancel(owner: owner, clearState: false) }
        inputs[owner] = input
        // Invalid identities are useful for stable UI failure state, but must
        // never enter the validated digest memo. Otherwise retry:true can use
        // resolvedDigest's memo fast path and bypass validateInput entirely.
        digests.removeValue(forKey: owner)
        let digest = sameInput ? (states[owner]?.digest ?? "invalid:\(UUID().uuidString)") : "invalid:\(UUID().uuidString)"
        let prior = states[owner]
        record(.failed, owner: owner, digest: digest, failure: normalized(error))
        // Canvas prepare is called for every pencil update. An unchanged local
        // input error has no new render work and must not schedule setDocument
        // again through its completion callback.
        if states[owner] != prior { completion?() }
    }

    private func compile(_ flow: NoteTextFlow) async throws -> [CompiledTextCache.Unit] {
        let web = try await readyWeb(width: flow.width)
        let width = Double(web.bounds.width)
        let raw = try await callJavaScript(web,
            script: "return await window.preparePaper(source, size, spacing, format);",
            arguments: ["source": flow.source, "size": flow.fontSizeSp, "spacing": flow.lineHeight, "format": flow.format])
        guard let metrics = raw as? [String: Any] else {
            throw CompiledTextRenderFailure(kind: .invalidResponse, stage: "javascript")
        }
        try validateDiagnostics(metrics["diagnostics"])
        guard let chunks = metrics["chunks"] as? [[String: Any]], !chunks.isEmpty,
              chunks.count <= configuration.maximumChunks else {
            throw CompiledTextRenderFailure(kind: chunksCount(metrics) > configuration.maximumChunks ? .tooLarge : .invalidResponse,
                                            stage: "layout")
        }
        var descriptors: [(top: Double, height: Double)] = []
        var totalPixels = 0
        for chunk in chunks {
            guard let top = number(chunk["top"]), let height = number(chunk["height"]),
                  top.isFinite, top >= 0, height.isFinite, height > 0,
                  height <= Double(configuration.maximumChunkHeight) else {
                throw CompiledTextRenderFailure(kind: .tooLarge, stage: "layout")
            }
            let scale = max(1, Double(web.window?.screen.scale ?? UIScreen.main.scale))
            let pixels = try pixelCount(width: width * scale, height: height * scale)
            guard pixels <= configuration.maximumSinglePixels,
                  totalPixels <= configuration.maximumTotalPixels - pixels else {
                throw CompiledTextRenderFailure(kind: .tooLarge, stage: "snapshot")
            }
            totalPixels += pixels; descriptors.append((top, height))
        }
        var units: [CompiledTextCache.Unit] = []
        units.reserveCapacity(descriptors.count)
        var actualPixels = 0
        for descriptor in descriptors {
            guard activeJob?.waiters.isEmpty == false else { throw CancellationError() }
            web.frame.size.height = ceil(descriptor.height)
            _ = try await callJavaScript(web, script: "return await window.positionPaper(top);", arguments: ["top": descriptor.top])
            let snapshot = WKSnapshotConfiguration()
            snapshot.rect = CGRect(x: 0, y: 0, width: width, height: ceil(descriptor.height))
            snapshot.snapshotWidth = NSNumber(value: width)
            snapshot.afterScreenUpdates = true
            let image = try await takeSnapshot(web, configuration: snapshot)
            guard let cgImage = image.cgImage else {
                throw CompiledTextRenderFailure(kind: .invalidResponse, stage: "snapshot")
            }
            let pixels = cgImage.width.multipliedReportingOverflow(by: cgImage.height)
            guard !pixels.overflow, pixels.partialValue <= configuration.maximumSinglePixels,
                  actualPixels <= configuration.maximumTotalPixels - pixels.partialValue else {
                throw CompiledTextRenderFailure(kind: .tooLarge, stage: "snapshot")
            }
            actualPixels += pixels.partialValue
            units.append(.init(image: image, size: CGSize(width: flow.width, height: descriptor.height * flow.width / width)))
        }
        return units
    }

    private func pixelCount(width: Double, height: Double) throws -> Int {
        let value = Int(ceil(width)).multipliedReportingOverflow(by: Int(ceil(height)))
        guard !value.overflow, value.partialValue > 0 else {
            throw CompiledTextRenderFailure(kind: .tooLarge, stage: "snapshot")
        }
        return value.partialValue
    }

    private func chunksCount(_ metrics: [String: Any]) -> Int { (metrics["chunks"] as? [Any])?.count ?? 0 }
    private func number(_ value: Any?) -> Double? { (value as? NSNumber)?.doubleValue ?? value as? Double }

    private func validateDiagnostics(_ raw: Any?) throws {
        guard let values = raw as? [[String: Any]] else { return }
        if values.contains(where: { ($0["kind"] as? String) == "resource" }) {
            throw CompiledTextRenderFailure(kind: .resourceMissing, stage: "resources")
        }
        if values.contains(where: { ($0["kind"] as? String) == "syntax" }) {
            throw CompiledTextRenderFailure(kind: .syntax, stage: "javascript")
        }
    }

    private func readyWeb(width: Double) async throws -> WKWebView {
        if let web {
            web.frame.size = CGSize(width: min(4096, max(40, width)), height: 1024)
            return web
        }
        guard let url = resourceURLProvider() else {
            throw CompiledTextRenderFailure(kind: .resourceMissing, stage: "load")
        }
        let config = WKWebViewConfiguration()
        config.websiteDataStore = .nonPersistent()
        config.preferences.javaScriptCanOpenWindowsAutomatically = false
        let view = WKWebView(frame: CGRect(x: -8192, y: 0, width: min(4096, max(40, width)), height: 1024), configuration: config)
        view.isOpaque = false; view.backgroundColor = .clear
        view.scrollView.backgroundColor = .clear; view.scrollView.isScrollEnabled = false
        view.scrollView.contentInsetAdjustmentBehavior = .never; view.scrollView.contentInset = .zero
        view.isUserInteractionEnabled = false; view.navigationDelegate = self
        resourceRoot = url.deletingLastPathComponent()
        if let window = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene })
            .flatMap(\.windows).first(where: \.isKeyWindow) { window.addSubview(view) }
        web = view
        try await runStage(name: "load", timeout: configuration.loadTimeout) { [weak self] completion in
            self?.navigationCompletion = completion
            view.loadFileURL(url, allowingReadAccessTo: url.deletingLastPathComponent())
        }
        return view
    }

    private func callJavaScript(_ web: WKWebView, script: String, arguments: [String: Any]) async throws -> Any? {
        try await runStage(name: "javascript", timeout: configuration.javaScriptTimeout) { completion in
            web.callAsyncJavaScript(script, arguments: arguments, in: nil, in: .page, completionHandler: completion)
        }
    }

    private func takeSnapshot(_ web: WKWebView, configuration: WKSnapshotConfiguration) async throws -> UIImage {
        try await runStage(name: "snapshot", timeout: self.configuration.snapshotTimeout) { completion in
            web.takeSnapshot(with: configuration) { image, error in
                if let image { completion(.success(image)) }
                else { completion(.failure(error ?? CompiledTextRenderFailure(kind: .invalidResponse, stage: "snapshot"))) }
            }
        }
    }

    private func runStage<Value>(name: String, timeout: TimeInterval,
                                 start: (@escaping (Result<Value, Error>) -> Void) -> Void) async throws -> Value {
        let id = UUID(), gate = RenderContinuationGate<Value>()
        defer { if activeStage?.id == id { activeStage = nil } }
        return try await withTaskCancellationHandler(operation: {
            try await withCheckedThrowingContinuation { continuation in
                gate.install(continuation)
                activeStage = ActiveStage(id: id, abort: { error in gate.resolve(.failure(error)) })
                DispatchQueue.main.asyncAfter(deadline: .now() + timeout) { [weak self] in
                    guard let self, self.activeStage?.id == id else { return }
                    let failure = CompiledTextRenderFailure(kind: .timeout, stage: name)
                    self.activeStage = nil; self.navigationCompletion = nil
                    gate.resolve(.failure(failure)); self.resetWeb()
                }
                start { result in
                    DispatchQueue.main.async { [weak self] in
                        if self?.activeStage?.id == id { self?.activeStage = nil }
                        gate.resolve(result)
                    }
                }
            }
        }, onCancel: {
            gate.resolve(.failure(CancellationError()))
            Task { @MainActor [weak self] in
                guard self?.activeStage?.id == id else { return }
                self?.activeStage = nil; self?.navigationCompletion = nil; self?.resetWeb()
            }
        })
    }

    private func abortActiveWork(_ error: Error) {
        activeStage?.abort(error); activeStage = nil; navigationCompletion = nil; resetWeb()
    }

    private func resetWeb() {
        web?.stopLoading(); web?.navigationDelegate = nil; web?.removeFromSuperview()
        web = nil; resourceRoot = nil
    }

    private func normalized(_ error: Error) -> CompiledTextRenderFailure {
        if let failure = error as? CompiledTextRenderFailure { return failure }
        if error is CancellationError { return .init(kind: .cancelled, stage: "request") }
        return .init(kind: .invalidResponse, stage: "render")
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        guard webView === web else { return }
        navigationCompletion?(.success(())); navigationCompletion = nil
    }
    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        guard webView === web else { return }
        navigationCompletion?(.failure(error)); navigationCompletion = nil; resetWeb()
    }
    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        self.webView(webView, didFail: navigation, withError: error)
    }
    func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
        guard webView === web else { return }
        let failure = CompiledTextRenderFailure(kind: .webProcessTerminated, stage: "webkit")
        navigationCompletion?(.failure(failure)); navigationCompletion = nil
        activeStage?.abort(failure); activeStage = nil; resetWeb()
    }
    func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction,
                 decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        guard webView === web, let url = action.request.url, let resourceRoot, url.isFileURL,
              url.standardizedFileURL.path.hasPrefix(resourceRoot.path + "/"),
              action.navigationType != .linkActivated else { decisionHandler(.cancel); return }
        decisionHandler(.allow)
    }
}
