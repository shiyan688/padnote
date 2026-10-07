import Foundation
import CryptoKit

enum NoteAIMutationError: LocalizedError, Equatable {
    case unsupportedChange
    case changedSource
    case changedTarget
    case activeCanvasInput

    var errorDescription: String? {
        switch self {
        case .unsupportedChange:
            return "本次 AI 结果包含不支持的笔记变更，未应用。"
        case .changedSource:
            return "等待回答时笔记内容已发生编辑，本次 AI 写入没有应用。请根据当前页面重新发送。"
        case .changedTarget:
            return "AI 修改的目标已被继续编辑。为保护当前内容，无法撤销或重新应用。"
        case .activeCanvasInput:
            return "请先结束当前书写或拖动，再应用 AI 修改。"
        }
    }
}

public struct NoteAIMutation: Identifiable, Equatable, Codable {
    public enum State: Equatable {
        case applied
        case undone
        case partial
        case conflict
        case differentNote
    }

    public enum FlowChange: Equatable, Codable {
        case inserted(NoteTextFlow)
        case updated(before: NoteTextFlow, after: NoteTextFlow)

        var before: NoteTextFlow? {
            if case .updated(let before, _) = self { return before }
            return nil
        }
        var after: NoteTextFlow {
            switch self {
            case .inserted(let flow), .updated(_, let flow): return flow
            }
        }

        private enum CodingKeys: String, CodingKey { case kind, before, after }
        private enum Kind: String, Codable { case inserted, updated }
        public init(from decoder: Decoder) throws {
            let container = try decoder.container(keyedBy: CodingKeys.self)
            switch try container.decode(Kind.self, forKey: .kind) {
            case .inserted:
                self = .inserted(try container.decode(NoteTextFlow.self, forKey: .after))
            case .updated:
                self = .updated(before: try container.decode(NoteTextFlow.self, forKey: .before),
                                after: try container.decode(NoteTextFlow.self, forKey: .after))
            }
        }
        public func encode(to encoder: Encoder) throws {
            var container = encoder.container(keyedBy: CodingKeys.self)
            switch self {
            case .inserted(let flow):
                try container.encode(Kind.inserted, forKey: .kind)
                try container.encode(flow, forKey: .after)
            case .updated(let before, let after):
                try container.encode(Kind.updated, forKey: .kind)
                try container.encode(before, forKey: .before)
                try container.encode(after, forKey: .after)
            }
        }
    }

    public let id: UUID
    public let noteID: String
    public let originalPageCount: Int
    public let appliedPageCount: Int
    public let changes: [FlowChange]
    private let sourceLayout: LayoutMarker
    private let sourceTopology: [TopologyMarker]
    private let targetRegions: [TargetRegion]
    private let baselineOccupants: [Occupant]

    public init?(id: UUID = UUID(), original: NoteDocument, proposed: NoteDocument) throws {
        let original = try original.validated()
        let proposed = try proposed.validated()
        guard Self.supportedFieldsMatch(original, proposed),
              proposed.pageCount >= original.pageCount else {
            throw NoteAIMutationError.unsupportedChange
        }
        let beforeByID = Dictionary(uniqueKeysWithValues: original.textFlows.map { ($0.id, $0) })
        let afterByID = Dictionary(uniqueKeysWithValues: proposed.textFlows.map { ($0.id, $0) })
        guard beforeByID.keys.allSatisfy({ afterByID[$0] != nil }) else {
            throw NoteAIMutationError.unsupportedChange
        }
        var changes: [FlowChange] = []
        for flow in proposed.textFlows {
            if let before = beforeByID[flow.id] {
                if before != flow { changes.append(.updated(before: before, after: flow)) }
            } else {
                changes.append(.inserted(flow))
            }
        }
        guard !changes.isEmpty else { return nil }
        self.id = id
        noteID = original.id
        originalPageCount = original.pageCount
        appliedPageCount = proposed.pageCount
        self.changes = changes
        sourceLayout = LayoutMarker(document: original)
        sourceTopology = Self.topology(of: original)
        targetRegions = Self.regions(for: changes, pageHeight: original.pageHeight)
        baselineOccupants = Self.occupants(in: original,
            regions: Self.regions(for: changes, pageHeight: original.pageHeight),
            excludingFlowIDs: Set(changes.map { $0.after.id }))
    }

    public static func sourceCompatible(_ original: NoteDocument, _ current: NoteDocument) -> Bool {
        guard current.pageCount >= original.pageCount else { return false }
        return original.schemaVersion == current.schemaVersion
            && original.id == current.id
            && original.title == current.title
            && original.pageWidth == current.pageWidth
            && original.pageHeight == current.pageHeight
            && original.pageGap == current.pageGap
            && original.pdfPageCount == current.pdfPageCount
            && original.pageTopologyRevision == current.pageTopologyRevision
            && original.strokes == current.strokes
            && original.textFlows == current.textFlows
            && original.images == current.images
            && original.pageStyle == current.pageStyle
    }

    public func state(in document: NoteDocument, recordedApplied _: Bool?) -> State {
        guard document.id == noteID else { return .differentNote }
        let states = changes.map { changeState($0, in: document) }
        if states.allSatisfy({ $0 == .after }) { return .applied }
        if states.allSatisfy({ $0 == .before }) { return .undone }
        if states.allSatisfy({ $0 == .before || $0 == .after }) { return .partial }
        return .conflict
    }

    public func applying(to document: NoteDocument, recordedApplied: Bool?) throws -> NoteDocument {
        let state = state(in: document, recordedApplied: recordedApplied)
        if state == .applied { return document }
        guard state == .undone, topologyMatches(document), occupancyMatches(document) else {
            throw NoteAIMutationError.changedTarget
        }
        var result = document
        for change in changes {
            switch change {
            case .inserted(let flow):
                guard !result.textFlows.contains(where: { $0.id == flow.id }) else {
                    throw NoteAIMutationError.changedTarget
                }
                result.textFlows.append(flow)
            case .updated(let before, let after):
                guard let index = result.textFlows.firstIndex(where: { $0.id == before.id }),
                      result.textFlows[index] == before else { throw NoteAIMutationError.changedTarget }
                result.textFlows[index] = after
            }
        }
        result.pageCount = max(result.pageCount, appliedPageCount, minimumUsedPageCount(result))
        return try result.validated()
    }

    public func undoing(in document: NoteDocument, recordedApplied: Bool?) throws -> NoteDocument {
        let state = state(in: document, recordedApplied: recordedApplied)
        if state == .undone { return document }
        guard state == .applied else { throw NoteAIMutationError.changedTarget }
        var result = document
        for change in changes {
            switch change {
            case .inserted(let flow):
                guard let index = result.textFlows.firstIndex(where: { $0.id == flow.id }),
                      result.textFlows[index] == flow else { throw NoteAIMutationError.changedTarget }
                result.textFlows.remove(at: index)
            case .updated(let before, let after):
                guard let index = result.textFlows.firstIndex(where: { $0.id == after.id }),
                      result.textFlows[index] == after else { throw NoteAIMutationError.changedTarget }
                result.textFlows[index] = before
            }
        }
        if result.pageCount <= appliedPageCount {
            result.pageCount = max(originalPageCount, minimumUsedPageCount(result))
        }
        return try result.validated()
    }

    public func targetPages(in document: NoteDocument) -> [Int] {
        let current = Dictionary(uniqueKeysWithValues: document.textFlows.map { ($0.id, $0) })
        let pages = changes.compactMap { current[$0.after.id] }.flatMap { flow -> [Int] in
            let fragments = NoteTextLayout.fragments(flow, pageHeight: document.pageHeight)
            return fragments.isEmpty ? [flow.anchorPageIndex] : fragments.map(\.page)
        }
        return Array(Set(pages)).sorted()
    }

    private enum ObjectState { case before, after, other }

    private struct TopologyMarker: Equatable, Codable {
        enum Kind: Int, Codable { case text, image, stroke }
        let kind: Kind
        let id: String
        let pages: [Int]
    }

    private struct LayoutMarker: Equatable, Codable {
        let pageWidth: Double
        let pageHeight: Double
        let pageGap: Double
        let pdfPageCount: Int
        let pageTopologyRevision: Int
        let pageStyle: PageStyle

        init(document: NoteDocument) {
            pageWidth = document.pageWidth
            pageHeight = document.pageHeight
            pageGap = document.pageGap
            pdfPageCount = document.pdfPageCount
            pageTopologyRevision = document.pageTopologyRevision
            pageStyle = document.pageStyle
        }

        private enum CodingKeys: String, CodingKey {
            case pageWidth, pageHeight, pageGap, pdfPageCount, pageTopologyRevision, pageStyle
        }

        init(from decoder: Decoder) throws {
            let container = try decoder.container(keyedBy: CodingKeys.self)
            pageWidth = try container.decode(Double.self, forKey: .pageWidth)
            pageHeight = try container.decode(Double.self, forKey: .pageHeight)
            pageGap = try container.decode(Double.self, forKey: .pageGap)
            pdfPageCount = try container.decode(Int.self, forKey: .pdfPageCount)
            pageTopologyRevision = try container.decodeIfPresent(Int.self, forKey: .pageTopologyRevision) ?? 0
            pageStyle = try container.decode(PageStyle.self, forKey: .pageStyle)
        }
    }

    private struct TargetRegion: Equatable, Codable {
        let page: Int
        let rect: CGRect
    }

    private struct Occupant: Equatable, Codable {
        let kind: TopologyMarker.Kind
        let id: String
        let digest: String
        var key: String { "\(kind.rawValue):\(id)" }
    }

    func validatePersistedReceipt() throws {
        guard !noteID.isEmpty, noteID.count <= 120,
              noteID.unicodeScalars.allSatisfy({ CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "-_" )).contains($0) }),
              (1...500).contains(originalPageCount),
              (originalPageCount...500).contains(appliedPageCount),
              !changes.isEmpty, changes.count <= 200,
              sourceTopology.count <= 20_000,
              targetRegions.count <= 2_000,
              baselineOccupants.count <= 2_000,
              baselineOccupants.allSatisfy({ !$0.id.isEmpty && $0.id.count <= 160 && $0.digest.count == 64 }) else {
            throw NoteDocumentError.malformed("Invalid AI mutation receipt")
        }
        var ids = Set<String>()
        for change in changes {
            let after = change.after
            guard !after.id.isEmpty, after.id.count <= 160, ids.insert(after.id).inserted,
                  ["markdown", "latex"].contains(after.format), after.source.utf8.count <= 100_000,
                  after.fontSizeSp.isFinite, after.lineHeight.isFinite, after.width.isFinite,
                  after.anchorXInPage.isFinite, after.anchorYInPage.isFinite,
                  (0..<500).contains(after.anchorPageIndex) else {
                throw NoteDocumentError.malformed("Invalid AI mutation flow")
            }
            if let before = change.before {
                guard before.id == after.id, before.source.utf8.count <= 100_000 else {
                    throw NoteDocumentError.malformed("Invalid AI mutation before value")
                }
            }
        }
        guard sourceLayout.pageWidth.isFinite, sourceLayout.pageHeight.isFinite,
              sourceLayout.pageGap.isFinite, sourceLayout.pageWidth > 0,
              sourceLayout.pageHeight > 0, sourceLayout.pageGap >= 0,
              (0...1_000_000_000).contains(sourceLayout.pageTopologyRevision),
              targetRegions.allSatisfy({ (0..<500).contains($0.page) &&
                  $0.rect.origin.x.isFinite && $0.rect.origin.y.isFinite &&
                  $0.rect.width.isFinite && $0.rect.height.isFinite &&
                  $0.rect.width >= 0 && $0.rect.height >= 0 }) else {
            throw NoteDocumentError.malformed("Invalid AI mutation geometry")
        }
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(UUID.self, forKey: .id)
        noteID = try container.decode(String.self, forKey: .noteID)
        originalPageCount = try container.decode(Int.self, forKey: .originalPageCount)
        appliedPageCount = try container.decode(Int.self, forKey: .appliedPageCount)
        changes = try container.decode([FlowChange].self, forKey: .changes)
        sourceLayout = try container.decode(LayoutMarker.self, forKey: .sourceLayout)
        sourceTopology = try container.decode([TopologyMarker].self, forKey: .sourceTopology)
        targetRegions = try container.decode([TargetRegion].self, forKey: .targetRegions)
        baselineOccupants = try container.decode([Occupant].self, forKey: .baselineOccupants)
        try validatePersistedReceipt()
    }

    private enum CodingKeys: String, CodingKey {
        case id, noteID, originalPageCount, appliedPageCount, changes, sourceLayout,
             sourceTopology, targetRegions, baselineOccupants
    }

    private func changeState(_ change: FlowChange, in document: NoteDocument) -> ObjectState {
        let current = document.textFlows.first { $0.id == change.after.id }
        switch change {
        case .inserted(let after):
            if current == after { return .after }
            if current == nil { return .before }
            return .other
        case .updated(let before, let after):
            if current == after { return .after }
            if current == before { return .before }
            return .other
        }
    }

    private static func supportedFieldsMatch(_ lhs: NoteDocument, _ rhs: NoteDocument) -> Bool {
        lhs.schemaVersion == rhs.schemaVersion
            && lhs.id == rhs.id
            && lhs.title == rhs.title
            && lhs.pageWidth == rhs.pageWidth
            && lhs.pageHeight == rhs.pageHeight
            && lhs.pageGap == rhs.pageGap
            && lhs.pdfPageCount == rhs.pdfPageCount
            && lhs.pageTopologyRevision == rhs.pageTopologyRevision
            && lhs.strokes == rhs.strokes
            && lhs.images == rhs.images
            && lhs.pageStyle == rhs.pageStyle
    }

    private static func topology(of document: NoteDocument) -> [TopologyMarker] {
        let stride = max(1, document.pageHeight + document.pageGap)
        let text = document.textFlows.map {
            TopologyMarker(kind: .text, id: $0.id, pages: [$0.anchorPageIndex])
        }
        let images = document.images.map {
            TopologyMarker(kind: .image, id: $0.id, pages: [$0.page])
        }
        let strokes = document.strokes.map { stroke in
            let pages = Set(stroke.points.compactMap { point -> Int? in
                guard point.y.isFinite, point.y >= 0 else { return nil }
                return Int(floor(point.y / stride))
            })
            return TopologyMarker(kind: .stroke, id: stroke.id, pages: pages.sorted())
        }
        return (text + images + strokes).sorted {
            if $0.kind.rawValue != $1.kind.rawValue { return $0.kind.rawValue < $1.kind.rawValue }
            return $0.id < $1.id
        }
    }

    private func topologyMatches(_ document: NoteDocument) -> Bool {
        guard document.pageCount >= originalPageCount,
              LayoutMarker(document: document) == sourceLayout else { return false }
        let current = Self.topology(of: document)
        let currentByKey = Dictionary(uniqueKeysWithValues: current.map { ("\($0.kind.rawValue):\($0.id)", $0.pages) })
        return sourceTopology.allSatisfy {
            currentByKey["\($0.kind.rawValue):\($0.id)"] == $0.pages
        }
    }

    private func occupancyMatches(_ document: NoteDocument) -> Bool {
        Self.occupants(in: document, regions: targetRegions,
                       excludingFlowIDs: Set(changes.map { $0.after.id })) == baselineOccupants
    }

    private static func regions(for changes: [FlowChange], pageHeight: Double) -> [TargetRegion] {
        changes.flatMap { change in
            let flow = change.after
            let fragments = NoteTextLayout.fragments(flow, pageHeight: pageHeight)
            if fragments.isEmpty {
                return [TargetRegion(page: flow.anchorPageIndex,
                    rect: CGRect(x: flow.anchorXInPage, y: flow.anchorYInPage,
                                 width: flow.width, height: max(24, flow.fontSizeSp * flow.lineHeight)).insetBy(dx: -6, dy: -6))]
            }
            return fragments.map { TargetRegion(page: $0.page, rect: $0.rect.insetBy(dx: -6, dy: -6)) }
        }
    }

    private static func occupants(in document: NoteDocument, regions: [TargetRegion],
                                  excludingFlowIDs: Set<String>) -> [Occupant] {
        var result: [Occupant] = []
        for stroke in document.strokes where strokeIntersects(stroke, regions: regions, document: document) {
            result.append(Occupant(kind: .stroke, id: stroke.id, digest: digest(stroke)))
        }
        for flow in document.textFlows where !excludingFlowIDs.contains(flow.id) {
            let intersects = NoteTextLayout.fragments(flow, pageHeight: document.pageHeight).contains { fragment in
                regions.contains { $0.page == fragment.page && $0.rect.intersects(fragment.rect) }
            }
            if intersects { result.append(Occupant(kind: .text, id: flow.id, digest: digest(flow))) }
        }
        for image in document.images {
            let rect = CGRect(x: image.x, y: image.y, width: image.width, height: image.height)
            if regions.contains(where: { $0.page == image.page && $0.rect.intersects(rect) }) {
                result.append(Occupant(kind: .image, id: image.id, digest: digest(image)))
            }
        }
        return result.sorted { $0.key < $1.key }
    }

    private static func digest<T: Encodable>(_ value: T) -> String {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        let bytes = (try? encoder.encode(value)) ?? Data()
        return SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined()
    }

    private static func strokeIntersects(_ stroke: InkStroke, regions: [TargetRegion],
                                         document: NoteDocument) -> Bool {
        let stride = max(1, document.pageHeight + document.pageGap)
        for region in regions {
            let points = stroke.points.filter { Int(floor($0.y / stride)) == region.page }
            guard !points.isEmpty else { continue }
            let xs = points.map(\.x), ys = points.map { $0.y - Double(region.page) * stride }
            guard let minX = xs.min(), let maxX = xs.max(), let minY = ys.min(), let maxY = ys.max() else { continue }
            let width = max(stroke.baseWidth, maxX - minX)
            let height = max(stroke.baseWidth, maxY - minY)
            let rect = CGRect(x: minX - stroke.baseWidth / 2, y: minY - stroke.baseWidth / 2,
                              width: width + stroke.baseWidth, height: height + stroke.baseWidth)
            if region.rect.intersects(rect) { return true }
        }
        return false
    }

    private func minimumUsedPageCount(_ document: NoteDocument) -> Int {
        var count = max(1, document.pdfPageCount)
        for image in document.images { count = max(count, image.page + 1) }
        let stride = document.pageHeight + document.pageGap
        for stroke in document.strokes {
            for point in stroke.points where point.y.isFinite && point.y >= 0 {
                count = max(count, Int(floor(point.y / max(1, stride))) + 1)
            }
        }
        for flow in document.textFlows {
            count = max(count, flow.anchorPageIndex + 1)
            if let last = NoteTextLayout.fragments(flow, pageHeight: document.pageHeight).last {
                count = max(count, last.page + 1)
            }
        }
        return min(500, count)
    }
}
