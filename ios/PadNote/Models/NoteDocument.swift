import Foundation

public enum NoteDocumentError: Error, LocalizedError, Equatable {
    case unsupportedSchema(Int)
    case malformed(String)
    case tooLarge(String)
    case unsupportedExport(String)

    public var errorDescription: String? {
        switch self {
        case .unsupportedSchema(let version):
            return "Unsupported PadNote schema version: \(version)"
        case .malformed(let message), .tooLarge(let message), .unsupportedExport(let message):
            return message
        }
    }
}

public struct InkPoint: Codable, Equatable {
    public var x: Double
    public var y: Double
    public var pressure: Double
    public var timestamp: Double

    public init(x: Double = 0, y: Double = 0, pressure: Double = 0.5, timestamp: Double = 0) {
        self.x = x
        self.y = y
        self.pressure = pressure
        self.timestamp = timestamp
    }

    private enum CodingKeys: String, CodingKey { case x, y, pressure, timestamp }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(x: try c.decode(Double.self, forKey: .x), y: try c.decode(Double.self, forKey: .y),
                  pressure: try c.decodeIfPresent(Double.self, forKey: .pressure) ?? 0.5,
                  timestamp: try c.decode(Double.self, forKey: .timestamp))
    }
}

public struct InkStroke: Codable, Identifiable, Equatable {
    public var id: String
    public var color: String
    public var baseWidth: Double
    public var createdAt: Double
    public var highlighter: Bool
    public var points: [InkPoint]

    public init(id: String = UUID().uuidString, color: String = "#FF1F2933",
                baseWidth: Double = 2, createdAt: Double = 0,
                highlighter: Bool = false, points: [InkPoint] = []) {
        self.id = id
        self.color = color
        self.baseWidth = baseWidth
        self.createdAt = createdAt
        self.highlighter = highlighter
        self.points = points
    }

    private enum CodingKeys: String, CodingKey { case id, color, baseWidth, createdAt, highlighter, points }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(id: try c.decode(String.self, forKey: .id), color: try c.decode(String.self, forKey: .color),
                  baseWidth: try c.decode(Double.self, forKey: .baseWidth), createdAt: try c.decode(Double.self, forKey: .createdAt),
                  highlighter: try c.decodeIfPresent(Bool.self, forKey: .highlighter) ?? false,
                  points: try c.decodeIfPresent([InkPoint].self, forKey: .points) ?? [])
    }
}

public struct NoteTextFlow: Codable, Identifiable, Equatable {
    public var id: String
    public var format: String
    public var source: String
    public var fontSizeSp: Double
    public var lineHeight: Double
    public var width: Double
    public var anchorPageIndex: Int
    public var anchorXInPage: Double
    public var anchorYInPage: Double

    public init(id: String = UUID().uuidString, format: String = "latex", source: String = "",
                fontSizeSp: Double = 16, lineHeight: Double = 1.35, width: Double = 360,
                anchorPageIndex: Int = 0, anchorXInPage: Double = 0,
                anchorYInPage: Double = 0) {
        self.id = id
        self.format = format
        self.source = source
        self.fontSizeSp = fontSizeSp
        self.lineHeight = lineHeight
        self.width = width
        self.anchorPageIndex = anchorPageIndex
        self.anchorXInPage = anchorXInPage
        self.anchorYInPage = anchorYInPage
    }

    private enum CodingKeys: String, CodingKey { case id, format, source, fontSizeSp, lineHeight, width, anchorPageIndex, anchorXInPage, anchorYInPage }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(id: try c.decode(String.self, forKey: .id), format: try c.decodeIfPresent(String.self, forKey: .format) ?? "latex",
                  source: try c.decodeIfPresent(String.self, forKey: .source) ?? "", fontSizeSp: try c.decodeIfPresent(Double.self, forKey: .fontSizeSp) ?? 16,
                  lineHeight: try c.decodeIfPresent(Double.self, forKey: .lineHeight) ?? 1.35, width: try c.decodeIfPresent(Double.self, forKey: .width) ?? 360,
                  anchorPageIndex: try c.decodeIfPresent(Int.self, forKey: .anchorPageIndex) ?? 0,
                  anchorXInPage: try c.decodeIfPresent(Double.self, forKey: .anchorXInPage) ?? 0,
                  anchorYInPage: try c.decodeIfPresent(Double.self, forKey: .anchorYInPage) ?? 0)
    }
}

public struct NoteImage: Codable, Identifiable, Equatable {
    public var id: String
    public var png: String
    public var page: Int
    public var x: Double
    public var y: Double
    public var width: Double
    public var height: Double

    public init(id: String = UUID().uuidString, png: String = "", page: Int = 0,
                x: Double = 0, y: Double = 0, width: Double = 0, height: Double = 0) {
        self.id = id
        self.png = png
        self.page = page
        self.x = x
        self.y = y
        self.width = width
        self.height = height
    }

    private enum CodingKeys: String, CodingKey { case id, png, page, x, y, width, height }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(id: try c.decode(String.self, forKey: .id), png: try c.decode(String.self, forKey: .png),
                  page: try c.decodeIfPresent(Int.self, forKey: .page) ?? 0,
                  x: try c.decode(Double.self, forKey: .x), y: try c.decode(Double.self, forKey: .y),
                  width: try c.decode(Double.self, forKey: .width), height: try c.decode(Double.self, forKey: .height))
    }
}

public struct PageStyle: Codable, Equatable {
    public var paper: String
    public var ratio: String
    public var landscape: Bool

    public init(paper: String = "ruled", ratio: String = "screen", landscape: Bool = false) {
        self.paper = paper
        self.ratio = ratio
        self.landscape = landscape
    }

    private enum CodingKeys: String, CodingKey { case paper, ratio, landscape }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(paper: try c.decodeIfPresent(String.self, forKey: .paper) ?? "ruled",
                  ratio: try c.decodeIfPresent(String.self, forKey: .ratio) ?? "screen",
                  landscape: try c.decodeIfPresent(Bool.self, forKey: .landscape) ?? false)
    }
}

public struct NoteDocument: Codable, Identifiable, Equatable {
    public var schemaVersion: Int
    public var id: String
    public var title: String
    public var updatedAt: Double
    public var pageWidth: Double
    public var pageHeight: Double
    public var pageGap: Double
    public var pageCount: Int
    public var pdfPageCount: Int
    public var strokes: [InkStroke]
    public var textFlows: [NoteTextFlow]
    public var images: [NoteImage]
    public var pageStyle: PageStyle
    public var viewportZoom: Double
    public var viewportCenterX: Double
    public var viewportCenterY: Double

    public init(schemaVersion: Int = 8, id: String = UUID().uuidString,
                title: String = "未命名笔记", updatedAt: Double = NoteDocument.nowMillis(),
                pageWidth: Double = 768, pageHeight: Double = 1086, pageGap: Double = 24,
                pageCount: Int = 1, pdfPageCount: Int = 0, strokes: [InkStroke] = [],
                textFlows: [NoteTextFlow] = [], images: [NoteImage] = [],
                pageStyle: PageStyle = PageStyle(), viewportZoom: Double = 1,
                viewportCenterX: Double = 384, viewportCenterY: Double = 543) {
        self.schemaVersion = schemaVersion
        self.id = id
        self.title = title
        self.updatedAt = updatedAt
        self.pageWidth = pageWidth
        self.pageHeight = pageHeight
        self.pageGap = pageGap
        self.pageCount = pageCount
        self.pdfPageCount = pdfPageCount
        self.strokes = strokes
        self.textFlows = textFlows
        self.images = images
        self.pageStyle = pageStyle
        self.viewportZoom = viewportZoom
        self.viewportCenterX = viewportCenterX
        self.viewportCenterY = viewportCenterY
    }

    public init(title: String, pageStyle: PageStyle = PageStyle()) {
        self.init(id: UUID().uuidString, title: title, pageStyle: pageStyle)
    }

    public static func nowMillis() -> Double {
        Date().timeIntervalSince1970 * 1000
    }

    public func validated() throws -> NoteDocument {
        guard (1...8).contains(schemaVersion) else { throw NoteDocumentError.unsupportedSchema(schemaVersion) }
        guard !id.isEmpty, id.count <= 120, safeIdentifier(id) else { throw NoteDocumentError.malformed("Invalid note id") }
        guard title.count <= 200 else { throw NoteDocumentError.malformed("Note title is too long") }
        try checkFinite(updatedAt, "updatedAt")
        guard pageCount >= 1 && pageCount <= 500 else { throw NoteDocumentError.malformed("Page count must be 1–500") }
        guard pdfPageCount >= 0 && pdfPageCount <= pageCount else { throw NoteDocumentError.malformed("Invalid PDF page count") }
        if pdfPageCount > 0 && schemaVersion < 7 { throw NoteDocumentError.malformed("PDF metadata requires schema version 7 or newer") }
        try checkPositive(pageWidth, "pageWidth")
        try checkPositive(pageHeight, "pageHeight")
        guard pageGap >= 0 && pageGap <= 10_000 else { throw NoteDocumentError.malformed("Invalid page gap") }
        try checkFinite(viewportZoom, "viewportZoom")
        guard viewportZoom > 0 && viewportZoom <= 100 else { throw NoteDocumentError.malformed("Invalid viewport zoom") }
        try checkFinite(viewportCenterX, "viewportCenterX")
        try checkFinite(viewportCenterY, "viewportCenterY")
        guard ["blank", "ruled", "grid", "dotted"].contains(pageStyle.paper.lowercased()) else { throw NoteDocumentError.malformed("Invalid paper style") }
        guard ["screen", "a4"].contains(pageStyle.ratio.lowercased()) else { throw NoteDocumentError.malformed("Invalid page ratio") }

        guard strokes.count <= 20_000 else { throw NoteDocumentError.tooLarge("Too many ink strokes") }
        var pointCount = 0
        var strokeIDs = Set<String>()
        for stroke in strokes {
            guard !stroke.id.isEmpty && stroke.id.count <= 120 && safeIdentifier(stroke.id) else { throw NoteDocumentError.malformed("Invalid stroke id") }
            guard strokeIDs.insert(stroke.id).inserted else { throw NoteDocumentError.malformed("Duplicate stroke id") }
            guard stroke.color.range(of: #"^#[0-9A-Fa-f]{8}$"#, options: .regularExpression) != nil else { throw NoteDocumentError.malformed("Stroke color must be #AARRGGBB") }
            try checkFinite(stroke.baseWidth, "stroke width")
            guard stroke.baseWidth > 0 && stroke.baseWidth <= 1_000 else { throw NoteDocumentError.malformed("Invalid stroke width") }
            try checkFinite(stroke.createdAt, "stroke createdAt")
            pointCount += stroke.points.count
            guard pointCount <= 1_000_000 else { throw NoteDocumentError.tooLarge("Too many ink points") }
            for point in stroke.points {
                try checkFinite(point.x, "point x"); try checkFinite(point.y, "point y")
                try checkFinite(point.pressure, "point pressure"); try checkFinite(point.timestamp, "point timestamp")
                // Android historically persisted the raw stylus value; some
                // devices report values above one. Keep finite non-negative
                // samples intact and let rendering clamp them when needed.
                guard point.pressure >= 0 && point.pressure <= 100 else { throw NoteDocumentError.malformed("Invalid point pressure") }
            }
        }
        guard textFlows.count <= 2_000 else { throw NoteDocumentError.tooLarge("Too many text flows") }
        var flowIDs = Set<String>()
        for flow in textFlows {
            guard !flow.id.isEmpty && flow.id.count <= 120 && safeIdentifier(flow.id) else { throw NoteDocumentError.malformed("Invalid text flow id") }
            guard flowIDs.insert(flow.id).inserted else { throw NoteDocumentError.malformed("Duplicate text flow id") }
            guard flow.source.count <= 100_000 else { throw NoteDocumentError.tooLarge("Text flow source is too large") }
            guard ["latex", "markdown"].contains(flow.format.lowercased()) else { throw NoteDocumentError.malformed("Invalid text flow format") }
            try checkFinite(flow.fontSizeSp, "font size"); try checkFinite(flow.lineHeight, "line height"); try checkFinite(flow.width, "text width")
            guard flow.fontSizeSp >= 1 && flow.fontSizeSp <= 200, flow.lineHeight > 0 && flow.lineHeight <= 10,
                  flow.width > 0 && flow.width <= pageWidth * 4 else { throw NoteDocumentError.malformed("Invalid text flow geometry") }
            guard flow.anchorPageIndex >= 0 && flow.anchorPageIndex < pageCount else { throw NoteDocumentError.malformed("Text flow page is out of range") }
            try checkFinite(flow.anchorXInPage, "text anchor x"); try checkFinite(flow.anchorYInPage, "text anchor y")
        }
        guard images.count <= 2_000 else { throw NoteDocumentError.tooLarge("Too many images") }
        var encodedImageBytes = 0
        var imagePixels = 0
        var imageIDs = Set<String>()
        for image in images {
            guard !image.id.isEmpty && image.id.count <= 120 && safeIdentifier(image.id) else { throw NoteDocumentError.malformed("Invalid image id") }
            guard imageIDs.insert(image.id).inserted else { throw NoteDocumentError.malformed("Duplicate image id") }
            guard image.page >= 0 && image.page < pageCount else { throw NoteDocumentError.malformed("Image page is out of range") }
            for value in [image.x, image.y, image.width, image.height] { try checkFinite(value, "image geometry") }
            guard image.x >= 0, image.y >= 0, image.width > 0, image.height > 0,
                  image.x + image.width <= pageWidth + 1, image.y + image.height <= pageHeight + 1 else { throw NoteDocumentError.malformed("Image is outside page bounds") }
            guard let bytes = strictBase64(image.png), bytes.count <= 20 * 1024 * 1024,
                  isPNG(bytes), let dimensions = pngDimensions(bytes), dimensions.width <= 1_600, dimensions.height <= 1_600 else { throw NoteDocumentError.malformed("Invalid or oversized PNG image") }
            encodedImageBytes += bytes.count
            imagePixels += dimensions.width * dimensions.height
            guard encodedImageBytes <= 20 * 1024 * 1024, imagePixels <= 12 * 1024 * 1024 else { throw NoteDocumentError.tooLarge("Image data exceeds document limits") }
        }
        return self
    }

    public static func decode(_ data: Data) throws -> NoteDocument {
        guard data.count <= 50 * 1024 * 1024 else { throw NoteDocumentError.tooLarge("Note file exceeds 50 MB") }
        do {
            return try JSONDecoder().decode(NoteDocument.self, from: data).validated()
        } catch let error as NoteDocumentError {
            throw error
        } catch {
            throw NoteDocumentError.malformed(error.localizedDescription)
        }
    }

    public func encoded() throws -> Data {
        let checked = try validated()
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        do { return try encoder.encode(checked) }
        catch { throw NoteDocumentError.malformed("Unable to encode note: \(error.localizedDescription)") }
    }

    private enum CodingKeys: String, CodingKey {
        case schemaVersion, id, title, updatedAt, canvasWidth, canvasHeight, pageWidth, pageHeight, pageGap, pageCount, pdfPageCount
        case viewportScale, viewportZoom, viewportCenterX, viewportCenterY, strokes, textFlows, textBoxes, images, pageStyle
    }

    private struct LegacyTextBox: Decodable {
        var id: String = ""
        var flowId: String = ""
        var flowIndex: Int = 0
        var format: String = "latex"
        var source: String = ""
        var fontSizeSp: Double = 16
        var width: Double = 360
        var x: Double = 0
        var y: Double = 0
        var anchorPageIndex: Int?
        var anchorXInPage: Double?
        var anchorYInPage: Double?

        private enum CodingKeys: String, CodingKey { case id, flowId, flowIndex, format, source, fontSizeSp, width, x, y, anchorPageIndex, anchorXInPage, anchorYInPage }
        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            id = try c.decodeIfPresent(String.self, forKey: .id) ?? ""
            flowId = try c.decodeIfPresent(String.self, forKey: .flowId) ?? id
            flowIndex = try c.decodeIfPresent(Int.self, forKey: .flowIndex) ?? 0
            format = try c.decodeIfPresent(String.self, forKey: .format) ?? "latex"
            source = try c.decodeIfPresent(String.self, forKey: .source) ?? ""
            fontSizeSp = try c.decodeIfPresent(Double.self, forKey: .fontSizeSp) ?? 16
            width = try c.decodeIfPresent(Double.self, forKey: .width) ?? 360
            x = try c.decodeIfPresent(Double.self, forKey: .x) ?? 0
            y = try c.decodeIfPresent(Double.self, forKey: .y) ?? 0
            anchorPageIndex = try c.decodeIfPresent(Int.self, forKey: .anchorPageIndex)
            anchorXInPage = try c.decodeIfPresent(Double.self, forKey: .anchorXInPage)
            anchorYInPage = try c.decodeIfPresent(Double.self, forKey: .anchorYInPage)
        }
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        schemaVersion = try c.decodeIfPresent(Int.self, forKey: .schemaVersion) ?? 0
        guard (1...8).contains(schemaVersion) else { throw NoteDocumentError.unsupportedSchema(schemaVersion) }
        guard c.contains(.strokes) else { throw NoteDocumentError.malformed("Note is missing strokes data") }
        id = try c.decodeIfPresent(String.self, forKey: .id) ?? ""
        title = try c.decodeIfPresent(String.self, forKey: .title) ?? "未命名笔记"
        updatedAt = try c.decodeIfPresent(Double.self, forKey: .updatedAt) ?? 0
        let decodedWidth = try c.decodeIfPresent(Double.self, forKey: .pageWidth) ?? c.decodeIfPresent(Double.self, forKey: .canvasWidth) ?? 768
        let decodedHeight = try c.decodeIfPresent(Double.self, forKey: .pageHeight) ?? c.decodeIfPresent(Double.self, forKey: .canvasHeight) ?? 1086
        // Android's empty schema 6 document used zero until the first layout pass.
        pageWidth = decodedWidth > 0 ? decodedWidth : 768
        pageHeight = decodedHeight > 0 ? decodedHeight : 1086
        pageGap = try c.decodeIfPresent(Double.self, forKey: .pageGap) ?? 24
        pageCount = try c.decodeIfPresent(Int.self, forKey: .pageCount) ?? 1
        pdfPageCount = try c.decodeIfPresent(Int.self, forKey: .pdfPageCount) ?? 0
        strokes = try c.decodeIfPresent([InkStroke].self, forKey: .strokes) ?? []
        images = try c.decodeIfPresent([NoteImage].self, forKey: .images) ?? []
        pageStyle = try c.decodeIfPresent(PageStyle.self, forKey: .pageStyle) ?? PageStyle()
        let decodedZoom = try c.decodeIfPresent(Double.self, forKey: .viewportZoom)
        let decodedScale = try c.decodeIfPresent(Double.self, forKey: .viewportScale)
        viewportZoom = decodedZoom ?? decodedScale ?? 1
        viewportCenterX = try c.decodeIfPresent(Double.self, forKey: .viewportCenterX) ?? pageWidth / 2
        viewportCenterY = try c.decodeIfPresent(Double.self, forKey: .viewportCenterY) ?? pageHeight / 2
        if schemaVersion >= 5 {
            guard c.contains(.textFlows) else { throw NoteDocumentError.malformed("Note is missing textFlows data") }
            textFlows = try c.decodeIfPresent([NoteTextFlow].self, forKey: .textFlows) ?? []
        } else {
            if (3...4).contains(schemaVersion) && !c.contains(.textBoxes) { throw NoteDocumentError.malformed("Note is missing textBoxes data") }
            let boxes = try c.decodeIfPresent([LegacyTextBox].self, forKey: .textBoxes) ?? []
            var heads: [String: LegacyTextBox] = [:]
            for box in boxes where !box.id.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                let key = box.flowId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? box.id : box.flowId
                if heads[key] == nil || box.flowIndex < heads[key]!.flowIndex { heads[key] = box }
            }
            let stride = pageHeight + max(0, pageGap)
            let documentPageCount = pageCount
            let migratedFlows = heads.sorted { $0.key < $1.key }.map { key, box in
                let legacyPage: Int
                if stride > 0, box.y.isFinite {
                    let quotient = box.y / stride
                    legacyPage = quotient.isFinite ? Int(max(0, min(499, quotient))) : 0
                } else {
                    legacyPage = 0
                }
                let derivedPage = min(max(0, documentPageCount - 1), box.anchorPageIndex ?? legacyPage)
                let derivedY = box.anchorYInPage ?? (stride > 0 ? box.y - Double(derivedPage) * stride : box.y)
                return NoteTextFlow(id: key, format: box.format.lowercased(), source: box.source,
                                    fontSizeSp: box.fontSizeSp, lineHeight: 1.35,
                                    width: max(80, box.width), anchorPageIndex: derivedPage,
                                    anchorXInPage: box.anchorXInPage ?? box.x, anchorYInPage: derivedY)
            }
            textFlows = migratedFlows
            // The in-memory representation is now the current flow-based model.
            // Encoding it with an old schema would write an empty textBoxes array.
            schemaVersion = 8
        }
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(schemaVersion, forKey: .schemaVersion)
        try c.encode(id, forKey: .id); try c.encode(title, forKey: .title); try c.encode(updatedAt, forKey: .updatedAt)
        try c.encode(pageWidth, forKey: .pageWidth); try c.encode(pageHeight, forKey: .pageHeight); try c.encode(pageGap, forKey: .pageGap)
        try c.encode(pageCount, forKey: .pageCount); try c.encode(pdfPageCount, forKey: .pdfPageCount)
        try c.encode(strokes, forKey: .strokes); try c.encode(textFlows, forKey: .textFlows); try c.encode(images, forKey: .images)
        try c.encode(pageStyle, forKey: .pageStyle); try c.encode(viewportZoom, forKey: .viewportZoom)
        try c.encode(viewportCenterX, forKey: .viewportCenterX); try c.encode(viewportCenterY, forKey: .viewportCenterY)
        // Android's older readers use these aliases while migrating to pages.
        try c.encode(pageWidth, forKey: .canvasWidth); try c.encode(pageHeight, forKey: .canvasHeight)
        try c.encode([String](), forKey: .textBoxes)
    }

    private func checkFinite(_ value: Double, _ name: String) throws {
        guard value.isFinite else { throw NoteDocumentError.malformed("Invalid \(name)") }
    }
    private func checkPositive(_ value: Double, _ name: String) throws {
        guard value.isFinite, value > 0, value <= 100_000 else { throw NoteDocumentError.malformed("Invalid \(name)") }
    }
    private func safeIdentifier(_ value: String) -> Bool { value.range(of: #"^[A-Za-z0-9_.:-]+$"#, options: .regularExpression) != nil }
    private func strictBase64(_ value: String) -> Data? {
        guard value.count % 4 == 0, value.range(of: #"^[A-Za-z0-9+/]*={0,2}$"#, options: .regularExpression) != nil else { return nil }
        return Data(base64Encoded: value, options: [])
    }
    private func isPNG(_ data: Data) -> Bool { data.count >= 24 && Array(data.prefix(8)) == [137,80,78,71,13,10,26,10] }
    private func pngDimensions(_ data: Data) -> (width: Int, height: Int)? {
        guard data.count >= 24, String(data: data[12..<16], encoding: .ascii) == "IHDR" else { return nil }
        let w = data[16..<20].reduce(0) { ($0 << 8) | Int($1) }
        let h = data[20..<24].reduce(0) { ($0 << 8) | Int($1) }
        return w > 0 && h > 0 ? (w, h) : nil
    }
}
