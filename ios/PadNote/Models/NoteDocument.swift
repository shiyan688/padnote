import Foundation
import CryptoKit
import CoreFoundation

private struct NoteJSONKey: CodingKey, Hashable {
    let stringValue: String
    let intValue: Int? = nil
    init?(stringValue: String) { self.stringValue = stringValue }
    init?(intValue: Int) { return nil }
}

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

public struct AgentResultReceipt: Codable, Equatable {
    public let schemaVersion: Int
    public let applicationId: String
    public let taskId: String
    public let connectionId: String
    public let instanceId: String?
    public let agentKind: String
    public let transport: String
    public let sourceNoteId: String?
    public let sourceNoteRevision: Int64
    public let resultSha256: String
    public let addedAt: Int64

    public init(applicationId: String, taskId: String, connectionId: String, instanceId: String?,
                agentKind: String, transport: String, sourceNoteId: String?, sourceNoteRevision: Int64,
                resultSha256: String, addedAt: Int64) {
        self.schemaVersion = 1
        self.applicationId = applicationId
        self.taskId = taskId
        self.connectionId = connectionId
        self.instanceId = instanceId
        self.agentKind = agentKind
        self.transport = transport
        self.sourceNoteId = sourceNoteId
        self.sourceNoteRevision = sourceNoteRevision
        self.resultSha256 = resultSha256
        self.addedAt = addedAt
    }

    private enum CodingKeys: String, CodingKey, CaseIterable {
        case schemaVersion, applicationId, taskId, connectionId, instanceId, agentKind, transport
        case sourceNoteId, sourceNoteRevision, resultSha256, addedAt
    }

    public init(from decoder: Decoder) throws {
        let raw = try decoder.container(keyedBy: NoteJSONKey.self)
        guard Set(raw.allKeys.map(\.stringValue)) == Set(CodingKeys.allCases.map(\.rawValue)) else {
            throw DecodingError.dataCorrupted(.init(codingPath: decoder.codingPath, debugDescription: "Invalid agent result receipt fields"))
        }
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(applicationId: try c.decode(String.self, forKey: .applicationId),
                  taskId: try c.decode(String.self, forKey: .taskId),
                  connectionId: try c.decode(String.self, forKey: .connectionId),
                  instanceId: try c.decodeIfPresent(String.self, forKey: .instanceId),
                  agentKind: try c.decode(String.self, forKey: .agentKind),
                  transport: try c.decode(String.self, forKey: .transport),
                  sourceNoteId: try c.decodeIfPresent(String.self, forKey: .sourceNoteId),
                  sourceNoteRevision: try c.decode(Int64.self, forKey: .sourceNoteRevision),
                  resultSha256: try c.decode(String.self, forKey: .resultSha256),
                  addedAt: try c.decode(Int64.self, forKey: .addedAt))
        guard try c.decode(Int.self, forKey: .schemaVersion) == 1 else {
            throw DecodingError.dataCorruptedError(forKey: .schemaVersion, in: c, debugDescription: "Unsupported agent result receipt version")
        }
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(schemaVersion, forKey: .schemaVersion)
        try c.encode(applicationId, forKey: .applicationId)
        try c.encode(taskId, forKey: .taskId)
        try c.encode(connectionId, forKey: .connectionId)
        try c.encode(instanceId, forKey: .instanceId)
        try c.encode(agentKind, forKey: .agentKind)
        try c.encode(transport, forKey: .transport)
        try c.encode(sourceNoteId, forKey: .sourceNoteId)
        try c.encode(sourceNoteRevision, forKey: .sourceNoteRevision)
        try c.encode(resultSha256, forKey: .resultSha256)
        try c.encode(addedAt, forKey: .addedAt)
    }

    public func validate() throws {
        guard schemaVersion == 1,
              applicationId.range(of: "^[0-9a-f]{64}$", options: .regularExpression) != nil,
              resultSha256.range(of: "^[0-9a-f]{64}$", options: .regularExpression) != nil,
              Self.validTaskID(taskId),
              sourceNoteRevision >= 0, addedAt >= 0,
              Self.validIdentifier(connectionId), Self.validIdentifier(instanceId), Self.validIdentifier(sourceNoteId),
              Self.validToken(agentKind), Self.validToken(transport) else {
            throw NoteDocumentError.malformed("Invalid agent result provenance")
        }
        let material = ["padnote.agent-result.v1", taskId, connectionId, instanceId ?? "", agentKind, transport, resultSha256].joined(separator: "\n")
        let expected = SHA256.hash(data: Data(material.utf8)).map { String(format: "%02x", $0) }.joined()
        guard expected == applicationId else { throw NoteDocumentError.malformed("Agent result application identity mismatch") }
    }

    public func hasSameStableMetadata(as other: AgentResultReceipt) -> Bool {
        schemaVersion == other.schemaVersion && applicationId == other.applicationId && taskId == other.taskId &&
            connectionId == other.connectionId && instanceId == other.instanceId && agentKind == other.agentKind &&
            transport == other.transport && sourceNoteId == other.sourceNoteId &&
            sourceNoteRevision == other.sourceNoteRevision && resultSha256 == other.resultSha256
    }

    private static func validTaskID(_ value: String) -> Bool {
        if value.range(of: "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", options: .regularExpression) != nil {
            return UUID(uuidString: value)?.uuidString.lowercased() == value
        }
        return value.range(of: "^padnote-[0-9a-f]{32}$", options: .regularExpression) != nil
    }

    private static func validIdentifier(_ value: String?) -> Bool {
        guard let value else { return true }
        return !value.isEmpty && value.utf8.count <= 256 && !value.unicodeScalars.contains { $0.value <= 0x1f || (0x7f...0x9f).contains($0.value) }
    }

    private static func validToken(_ value: String) -> Bool {
        !value.isEmpty && value == value.lowercased() && value.utf8.count <= 64 &&
            value.range(of: "^[a-z0-9._-]+$", options: .regularExpression) != nil
    }
}

public struct AgentImageReceipt: Codable, Equatable, Sendable {
    public let schemaVersion: Int
    public let applicationId: String
    public let taskId: String
    public let connectionId: String
    public let instanceId: String?
    public let agentKind: String
    public let transport: String
    public let sourceNoteId: String?
    public let sourceNoteRevision: Int64
    public let artifactId: String
    public let artifactName: String
    public let mediaType: String
    public let sizeBytes: Int64
    public let artifactSha256: String
    public let pngSha256: String
    public let addedAt: Int64

    public init(taskId: String, connectionId: String, instanceId: String?, agentKind: String,
                transport: String, sourceNoteId: String?, sourceNoteRevision: Int64,
                artifactId: String, artifactName: String, mediaType: String, sizeBytes: Int64,
                artifactSha256: String, pngSha256: String, addedAt: Int64) {
        self.schemaVersion = 1
        self.taskId = taskId; self.connectionId = connectionId; self.instanceId = instanceId
        self.agentKind = agentKind; self.transport = transport; self.sourceNoteId = sourceNoteId
        self.sourceNoteRevision = sourceNoteRevision; self.artifactId = artifactId
        self.artifactName = artifactName; self.mediaType = mediaType; self.sizeBytes = sizeBytes
        self.artifactSha256 = artifactSha256.lowercased(); self.pngSha256 = pngSha256.lowercased()
        self.addedAt = addedAt
        self.applicationId = Self.applicationIdentity(taskId: taskId, connectionId: connectionId,
            instanceId: instanceId, agentKind: agentKind, transport: transport, artifactId: artifactId,
            artifactName: artifactName, mediaType: mediaType, sizeBytes: sizeBytes,
            artifactSha256: artifactSha256.lowercased())
    }

    private enum CodingKeys: String, CodingKey, CaseIterable {
        case schemaVersion, applicationId, taskId, connectionId, instanceId, agentKind, transport
        case sourceNoteId, sourceNoteRevision, artifactId, artifactName, mediaType, sizeBytes
        case artifactSha256, pngSha256, addedAt
    }

    public init(from decoder: Decoder) throws {
        let raw = try decoder.container(keyedBy: NoteJSONKey.self)
        guard Set(raw.allKeys.map(\.stringValue)) == Set(CodingKeys.allCases.map(\.rawValue)) else {
            throw DecodingError.dataCorrupted(.init(codingPath: decoder.codingPath,
                debugDescription: "Invalid agent image receipt fields"))
        }
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(taskId: try c.decode(String.self, forKey: .taskId),
            connectionId: try c.decode(String.self, forKey: .connectionId),
            instanceId: try c.decodeIfPresent(String.self, forKey: .instanceId),
            agentKind: try c.decode(String.self, forKey: .agentKind),
            transport: try c.decode(String.self, forKey: .transport),
            sourceNoteId: try c.decodeIfPresent(String.self, forKey: .sourceNoteId),
            sourceNoteRevision: try c.decode(Int64.self, forKey: .sourceNoteRevision),
            artifactId: try c.decode(String.self, forKey: .artifactId),
            artifactName: try c.decode(String.self, forKey: .artifactName),
            mediaType: try c.decode(String.self, forKey: .mediaType),
            sizeBytes: try c.decode(Int64.self, forKey: .sizeBytes),
            artifactSha256: try c.decode(String.self, forKey: .artifactSha256),
            pngSha256: try c.decode(String.self, forKey: .pngSha256),
            addedAt: try c.decode(Int64.self, forKey: .addedAt))
        guard try c.decode(Int.self, forKey: .schemaVersion) == 1,
              try c.decode(String.self, forKey: .applicationId) == applicationId else {
            throw DecodingError.dataCorruptedError(forKey: .applicationId, in: c,
                debugDescription: "Agent image application identity mismatch")
        }
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(schemaVersion, forKey: .schemaVersion); try c.encode(applicationId, forKey: .applicationId)
        try c.encode(taskId, forKey: .taskId); try c.encode(connectionId, forKey: .connectionId)
        try Self.encodeNullable(instanceId, to: &c, forKey: .instanceId)
        try c.encode(agentKind, forKey: .agentKind); try c.encode(transport, forKey: .transport)
        try Self.encodeNullable(sourceNoteId, to: &c, forKey: .sourceNoteId)
        try c.encode(sourceNoteRevision, forKey: .sourceNoteRevision); try c.encode(artifactId, forKey: .artifactId)
        try c.encode(artifactName, forKey: .artifactName); try c.encode(mediaType, forKey: .mediaType)
        try c.encode(sizeBytes, forKey: .sizeBytes); try c.encode(artifactSha256, forKey: .artifactSha256)
        try c.encode(pngSha256, forKey: .pngSha256); try c.encode(addedAt, forKey: .addedAt)
    }

    private static func encodeNullable(_ value: String?, to c: inout KeyedEncodingContainer<CodingKeys>,
                                       forKey key: CodingKeys) throws {
        if let value { try c.encode(value, forKey: key) } else { try c.encodeNil(forKey: key) }
    }

    public static func applicationIdentity(taskId: String, connectionId: String, instanceId: String?,
                                           agentKind: String, transport: String, artifactId: String,
                                           artifactName: String, mediaType: String, sizeBytes: Int64,
                                           artifactSha256: String) -> String {
        guard let data = applicationIdentityBytes(taskId: taskId, connectionId: connectionId,
            instanceId: instanceId, agentKind: agentKind, transport: transport, artifactId: artifactId,
            artifactName: artifactName, mediaType: mediaType, sizeBytes: sizeBytes,
            artifactSha256: artifactSha256) else { return "" }
        return Self.sha256(data)
    }

    static func applicationIdentityBytes(taskId: String, connectionId: String, instanceId: String?,
                                         agentKind: String, transport: String, artifactId: String,
                                         artifactName: String, mediaType: String, sizeBytes: Int64,
                                         artifactSha256: String) -> Data? {
        var data = Data("padnote.agent-image.v1\n".utf8)
        for value in [taskId, connectionId, instanceId ?? "", agentKind, transport, artifactId,
                      artifactName, mediaType, String(sizeBytes), artifactSha256.lowercased()] {
            let bytes = Data(value.utf8)
            guard bytes.count <= Int(UInt32.max) else { return nil }
            var length = UInt32(bytes.count).bigEndian
            withUnsafeBytes(of: &length) { data.append(contentsOf: $0) }
            data.append(bytes)
        }
        return data
    }

    public func canonicalBytes() -> Data {
        var data = Data("PadNote/AgentImage/receipt-v1\0".utf8)
        Self.appendI64(Int64(schemaVersion), to: &data)
        Self.appendLP(applicationId, to: &data); Self.appendLP(taskId, to: &data)
        Self.appendLP(connectionId, to: &data); Self.appendNullable(instanceId, to: &data)
        Self.appendLP(agentKind, to: &data); Self.appendLP(transport, to: &data)
        Self.appendNullable(sourceNoteId, to: &data); Self.appendI64(sourceNoteRevision, to: &data)
        Self.appendLP(artifactId, to: &data); Self.appendLP(artifactName, to: &data)
        Self.appendLP(mediaType, to: &data); Self.appendI64(sizeBytes, to: &data)
        Self.appendLP(artifactSha256, to: &data); Self.appendLP(pngSha256, to: &data)
        Self.appendI64(addedAt, to: &data)
        return data
    }

    public func validate(pngBytes: Data? = nil) throws {
        guard schemaVersion == 1,
              Self.isSHA(applicationId), Self.validTaskID(taskId),
              Self.validIdentifier(connectionId), Self.validIdentifier(instanceId),
              Self.validIdentifier(sourceNoteId), Self.validToken(agentKind), Self.validToken(transport),
              Self.validIdentifier(artifactId), Self.validArtifactName(artifactName),
              ["image/png", "image/jpeg"].contains(mediaType), (1...20*1024*1024).contains(sizeBytes),
              Self.isSHA(artifactSha256), Self.isSHA(pngSha256), sourceNoteRevision >= 0, addedAt >= 0,
              Self.applicationIdentity(taskId: taskId, connectionId: connectionId, instanceId: instanceId,
                agentKind: agentKind, transport: transport, artifactId: artifactId, artifactName: artifactName,
                mediaType: mediaType, sizeBytes: sizeBytes, artifactSha256: artifactSha256) == applicationId else {
            throw NoteDocumentError.malformed("Invalid agent image provenance")
        }
        if let pngBytes, Self.sha256(pngBytes) != pngSha256 {
            throw NoteDocumentError.malformed("Agent image PNG digest mismatch")
        }
    }

    private static func validTaskID(_ value: String) -> Bool {
        if value.range(of: "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", options: .regularExpression) != nil {
            return UUID(uuidString: value)?.uuidString.lowercased() == value
        }
        return value.range(of: "^padnote-[0-9a-f]{32}$", options: .regularExpression) != nil
    }
    private static func validIdentifier(_ value: String?) -> Bool {
        guard let value else { return true }
        return !value.isEmpty && value.utf8.count <= 256 && !value.unicodeScalars.contains {
            $0.value <= 0x1f || (0x7f...0x9f).contains($0.value)
        }
    }
    private static func validToken(_ value: String) -> Bool {
        !value.isEmpty && value == value.lowercased() && value.utf8.count <= 64 &&
            value.range(of: "^[a-z0-9._-]+$", options: .regularExpression) != nil
    }
    private static func validArtifactName(_ value: String) -> Bool {
        validIdentifier(value) && !value.contains("/") && !value.contains("\\")
    }
    private static func isSHA(_ value: String) -> Bool {
        value.range(of: "^[0-9a-f]{64}$", options: .regularExpression) != nil
    }
    private static func sha256(_ data: Data) -> String {
        SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }
    private static func appendLP(_ value: String, to data: inout Data) {
        let bytes = Data(value.utf8)
        var length = UInt32(bytes.count).bigEndian
        withUnsafeBytes(of: &length) { data.append(contentsOf: $0) }
        data.append(bytes)
    }
    private static func appendNullable(_ value: String?, to data: inout Data) {
        guard let value else { data.append(0); return }
        data.append(1); appendLP(value, to: &data)
    }
    private static func appendI64(_ value: Int64, to data: inout Data) {
        var big = value.bigEndian
        withUnsafeBytes(of: &big) { data.append(contentsOf: $0) }
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
    public var agentResult: AgentResultReceipt?
    /// Absent means the legacy Markdown renderer profile (v1). Only Markdown
    /// flows in schema 10 may persist the corrected profile value 2.
    public var markdownProfileVersion: Int?
    public var effectiveMarkdownProfileVersion: Int { markdownProfileVersion ?? 1 }

    public init(id: String = UUID().uuidString, format: String = "latex", source: String = "",
                fontSizeSp: Double = 16, lineHeight: Double = 1.35, width: Double = 360,
                anchorPageIndex: Int = 0, anchorXInPage: Double = 0,
                anchorYInPage: Double = 0, agentResult: AgentResultReceipt? = nil,
                markdownProfileVersion: Int? = nil) {
        self.id = id
        self.format = format
        self.source = source
        self.fontSizeSp = fontSizeSp
        self.lineHeight = lineHeight
        self.width = width
        self.anchorPageIndex = anchorPageIndex
        self.anchorXInPage = anchorXInPage
        self.anchorYInPage = anchorYInPage
        self.agentResult = agentResult
        self.markdownProfileVersion = markdownProfileVersion
    }

    private enum CodingKeys: String, CodingKey, CaseIterable {
        case id, format, source, fontSizeSp, lineHeight, width, anchorPageIndex
        case anchorXInPage, anchorYInPage, agentResult, markdownProfileVersion
    }
    public init(from decoder: Decoder) throws {
        let raw = try decoder.container(keyedBy: NoteJSONKey.self)
        let allKeys = Set(CodingKeys.allCases.map(\.rawValue))
        let found = Set(raw.allKeys.map(\.stringValue))
        guard found.isSubset(of: allKeys) else {
            throw DecodingError.dataCorrupted(.init(codingPath: decoder.codingPath, debugDescription: "Unknown or incomplete text flow fields"))
        }
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(id: try c.decode(String.self, forKey: .id), format: try c.decodeIfPresent(String.self, forKey: .format) ?? "latex",
                  source: try c.decodeIfPresent(String.self, forKey: .source) ?? "", fontSizeSp: try c.decodeIfPresent(Double.self, forKey: .fontSizeSp) ?? 16,
                  lineHeight: try c.decodeIfPresent(Double.self, forKey: .lineHeight) ?? 1.35, width: try c.decodeIfPresent(Double.self, forKey: .width) ?? 360,
                  anchorPageIndex: try c.decodeIfPresent(Int.self, forKey: .anchorPageIndex) ?? 0,
                  anchorXInPage: try c.decodeIfPresent(Double.self, forKey: .anchorXInPage) ?? 0,
                  anchorYInPage: try c.decodeIfPresent(Double.self, forKey: .anchorYInPage) ?? 0,
                  agentResult: c.contains(.agentResult) ? try c.decode(AgentResultReceipt.self, forKey: .agentResult) : nil,
                  markdownProfileVersion: c.contains(.markdownProfileVersion)
                    ? try c.decode(Int.self, forKey: .markdownProfileVersion) : nil)
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
    public var agentImage: AgentImageReceipt?
    // Remember a field supplied by JSON even when it decodes as an absent optional.
    // Legacy schemas must reject an attempted receipt instead of silently dropping it.
    var agentImageFieldPresent: Bool
    var unknownImageFieldsPresent: Bool

    public init(id: String = UUID().uuidString, png: String = "", page: Int = 0,
                x: Double = 0, y: Double = 0, width: Double = 0, height: Double = 0,
                agentImage: AgentImageReceipt? = nil) {
        self.id = id
        self.png = png
        self.page = page
        self.x = x
        self.y = y
        self.width = width
        self.height = height
        self.agentImage = agentImage
        self.agentImageFieldPresent = agentImage != nil
        self.unknownImageFieldsPresent = false
    }

    private enum CodingKeys: String, CodingKey, CaseIterable { case id, png, page, x, y, width, height, agentImage }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let raw = try decoder.container(keyedBy: NoteJSONKey.self)
        let hasAgentImage = raw.allKeys.contains { $0.stringValue == CodingKeys.agentImage.rawValue }
        let known = Set(CodingKeys.allCases.map(\.rawValue))
        let hasUnknownFields = raw.allKeys.contains { !known.contains($0.stringValue) }
        self.init(id: try c.decode(String.self, forKey: .id), png: try c.decode(String.self, forKey: .png),
                  page: try c.decodeIfPresent(Int.self, forKey: .page) ?? 0,
                  x: try c.decode(Double.self, forKey: .x), y: try c.decode(Double.self, forKey: .y),
                  width: try c.decode(Double.self, forKey: .width), height: try c.decode(Double.self, forKey: .height),
                  agentImage: hasAgentImage ? try c.decode(AgentImageReceipt.self, forKey: .agentImage) : nil)
        agentImageFieldPresent = hasAgentImage
        unknownImageFieldsPresent = hasUnknownFields
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(id, forKey: .id); try c.encode(png, forKey: .png); try c.encode(page, forKey: .page)
        try c.encode(x, forKey: .x); try c.encode(y, forKey: .y)
        try c.encode(width, forKey: .width); try c.encode(height, forKey: .height)
        if let agentImage { try c.encode(agentImage, forKey: .agentImage) }
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
    public static let maximumEncodedBytes = 50 * 1024 * 1024
    /// Java serializes this Float boundary as its exact Double value (0.44999998807907104).
    private static let minimumAndroidViewportScale = Double(Float(0.45))
    public var schemaVersion: Int
    public var id: String
    public var title: String
    public var updatedAt: Double
    public var pageWidth: Double
    public var pageHeight: Double
    public var pageGap: Double
    public var pageCount: Int
    public var pdfPageCount: Int
    public var pageTopologyRevision: Int
    public var strokes: [InkStroke]
    public var textFlows: [NoteTextFlow]
    public var images: [NoteImage]
    public var pageStyle: PageStyle
    public var viewportZoom: Double
    public var viewportCenterX: Double
    public var viewportCenterY: Double
    /// Android's absolute canvas scale, kept separately from `viewportZoom`.
    /// iPad continues to use viewportZoom for its own presentation; Android clamps
    /// this source value to `Double(Float(0.45))...4` when restoring the canvas; persisted output stays in that range.
    public var viewportScale: Double?
    /// Android's persisted author-edit counters. They are content-history metadata,
    /// not authority for cross-device update or compare-and-swap decisions.
    public var authorPageEditSerial: Int64?
    public var authorPageTopologySerial: Int64?
    /// Android shelf system metadata carried by the portable note JSON. This does not
    /// grant retirement authority or change ordinary iPad visibility.
    public var shelfState: String?
    public var shelfRetiredAt: Int64?

    public init(schemaVersion: Int = 10, id: String = UUID().uuidString,
                title: String = "未命名笔记", updatedAt: Double = NoteDocument.nowMillis(),
                pageWidth: Double = 768, pageHeight: Double = 1086, pageGap: Double = 24,
                pageCount: Int = 1, pdfPageCount: Int = 0, strokes: [InkStroke] = [],
                pageTopologyRevision: Int = 0,
                textFlows: [NoteTextFlow] = [], images: [NoteImage] = [],
                pageStyle: PageStyle = PageStyle(), viewportZoom: Double = 1,
                viewportCenterX: Double = 384, viewportCenterY: Double = 543,
                viewportScale: Double? = nil,
                authorPageEditSerial: Int64? = nil, authorPageTopologySerial: Int64? = nil,
                shelfState: String? = nil, shelfRetiredAt: Int64? = nil) {
        self.schemaVersion = schemaVersion
        self.id = id
        self.title = title
        self.updatedAt = updatedAt
        self.pageWidth = pageWidth
        self.pageHeight = pageHeight
        self.pageGap = pageGap
        self.pageCount = pageCount
        self.pdfPageCount = pdfPageCount
        self.pageTopologyRevision = pageTopologyRevision
        self.strokes = strokes
        self.textFlows = textFlows
        self.images = images
        self.pageStyle = pageStyle
        self.viewportZoom = viewportZoom
        self.viewportCenterX = viewportCenterX
        self.viewportCenterY = viewportCenterY
        self.viewportScale = viewportScale
        self.authorPageEditSerial = authorPageEditSerial
        self.authorPageTopologySerial = authorPageTopologySerial
        self.shelfState = shelfState
        self.shelfRetiredAt = shelfRetiredAt
    }

    public init(title: String, pageStyle: PageStyle = PageStyle()) {
        self.init(id: UUID().uuidString, title: title, pageStyle: pageStyle)
    }

    public static func nowMillis() -> Double {
        Date().timeIntervalSince1970 * 1000
    }

    public func validated() throws -> NoteDocument {
        guard (1...11).contains(schemaVersion) else { throw NoteDocumentError.unsupportedSchema(schemaVersion) }
        guard !id.isEmpty, id.count <= 120, safeIdentifier(id) else { throw NoteDocumentError.malformed("Invalid note id") }
        guard title.count <= 200 else { throw NoteDocumentError.malformed("Note title is too long") }
        try checkFinite(updatedAt, "updatedAt")
        guard pageCount >= 1 && pageCount <= 500 else { throw NoteDocumentError.malformed("Page count must be 1–500") }
        guard pdfPageCount >= 0 && pdfPageCount <= pageCount else { throw NoteDocumentError.malformed("Invalid PDF page count") }
        guard (0...1_000_000_000).contains(pageTopologyRevision) else {
            throw NoteDocumentError.malformed("Invalid page topology revision")
        }
        if shelfState == nil && shelfRetiredAt == nil {
            // Older notes have no shelf system metadata.
        } else if shelfState == "retired", let shelfRetiredAt, shelfRetiredAt > 0 {
            // Preserve the recognized state without interpreting it as iPad group authority.
        } else {
            throw NoteDocumentError.malformed("Invalid shelf retirement metadata")
        }
        if pdfPageCount > 0 && schemaVersion < 7 { throw NoteDocumentError.malformed("PDF metadata requires schema version 7 or newer") }
        try checkPositive(pageWidth, "pageWidth")
        try checkPositive(pageHeight, "pageHeight")
        guard pageGap >= 0 && pageGap <= 10_000 else { throw NoteDocumentError.malformed("Invalid page gap") }
        try checkFinite(viewportZoom, "viewportZoom")
        guard viewportZoom > 0 && viewportZoom <= 100 else { throw NoteDocumentError.malformed("Invalid viewport zoom") }
        try checkFinite(viewportCenterX, "viewportCenterX")
        try checkFinite(viewportCenterY, "viewportCenterY")
        if let viewportScale, !viewportScale.isFinite || !(Self.minimumAndroidViewportScale...4.0).contains(viewportScale) {
            throw NoteDocumentError.malformed("Invalid viewportScale")
        }
        for (name, serial) in [("authorPageEditSerial", authorPageEditSerial),
                               ("authorPageTopologySerial", authorPageTopologySerial)] {
            if let serial, serial < 0 || serial > Int64.max - 1024 {
                throw NoteDocumentError.malformed("Invalid \(name)")
            }
        }
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
        let hasAgentResults = textFlows.contains { $0.agentResult != nil }
        guard !hasAgentResults || (9...11).contains(schemaVersion) else {
            throw NoteDocumentError.malformed("Agent result provenance requires schema version 9 or newer")
        }
        for flow in textFlows {
            guard !flow.id.isEmpty && flow.id.count <= 120 && safeIdentifier(flow.id) else { throw NoteDocumentError.malformed("Invalid text flow id") }
            guard flowIDs.insert(flow.id).inserted else { throw NoteDocumentError.malformed("Duplicate text flow id") }
            guard flow.source.count <= 100_000 else { throw NoteDocumentError.tooLarge("Text flow source is too large") }
            guard ["latex", "markdown"].contains(flow.format.lowercased()) else { throw NoteDocumentError.malformed("Invalid text flow format") }
            if let receipt = flow.agentResult {
                guard flow.source.utf16.count <= 100_000,
                      Data(flow.source.utf8).count <= 400_000 else {
                    throw NoteDocumentError.tooLarge("Agent result text exceeds the shared size limit")
                }
                try receipt.validate()
                guard (9...11).contains(schemaVersion) else {
                    throw NoteDocumentError.malformed("Agent result provenance requires schema version 9 or newer")
                }
            }
            if let profile = flow.markdownProfileVersion {
                guard schemaVersion >= 10, profile == 2, flow.format.lowercased() == "markdown" else {
                    throw NoteDocumentError.malformed("markdownProfileVersion is only valid as integer 2 on Markdown flows in schema 10 or newer")
                }
            }
            try checkFinite(flow.fontSizeSp, "font size"); try checkFinite(flow.lineHeight, "line height"); try checkFinite(flow.width, "text width")
            guard flow.fontSizeSp >= 1 && flow.fontSizeSp <= 200, flow.lineHeight > 0 && flow.lineHeight <= 10,
                  flow.width > 0 && flow.width <= pageWidth * 4 else { throw NoteDocumentError.malformed("Invalid text flow geometry") }
            guard flow.anchorPageIndex >= 0 && flow.anchorPageIndex < pageCount else { throw NoteDocumentError.malformed("Text flow page is out of range") }
            try checkFinite(flow.anchorXInPage, "text anchor x"); try checkFinite(flow.anchorYInPage, "text anchor y")
        }
        guard images.count <= 2_000 else { throw NoteDocumentError.tooLarge("Too many images") }
        var encodedImageBytes = 0
        var encodedImageBase64Bytes = 0
        var imagePixels = 0
        var imageIDs = Set<String>()
        var agentImageApplicationIDs = Set<String>()
        for image in images {
            guard !image.id.isEmpty && image.id.count <= 120 && safeIdentifier(image.id) else { throw NoteDocumentError.malformed("Invalid image id") }
            guard imageIDs.insert(image.id).inserted else { throw NoteDocumentError.malformed("Duplicate image id") }
            guard image.page >= 0 && image.page < pageCount else { throw NoteDocumentError.malformed("Image page is out of range") }
            for value in [image.x, image.y, image.width, image.height] { try checkFinite(value, "image geometry") }
            guard image.x >= 0, image.y >= 0, image.width > 0, image.height > 0,
                  image.x + image.width <= pageWidth + 1, image.y + image.height <= pageHeight + 1 else { throw NoteDocumentError.malformed("Image is outside page bounds") }
            guard let bytes = strictBase64(image.png), bytes.count <= 20 * 1024 * 1024,
                  isPNG(bytes), let dimensions = pngDimensions(bytes), dimensions.width <= 1_600, dimensions.height <= 1_600 else { throw NoteDocumentError.malformed("Invalid or oversized PNG image") }
            if schemaVersion <= 10 && (image.agentImageFieldPresent || image.agentImage != nil) {
                throw NoteDocumentError.malformed("Agent image provenance requires schema version 11")
            }
            if schemaVersion == 11 && image.unknownImageFieldsPresent {
                throw NoteDocumentError.malformed("Unknown image fields are not supported in schema 11")
            }
            if let receipt = image.agentImage {
                guard schemaVersion == 11, image.id == "agent-image-\(receipt.applicationId)",
                      agentImageApplicationIDs.insert(receipt.applicationId).inserted else {
                    throw NoteDocumentError.malformed("Agent image identity is invalid or duplicated")
                }
                try receipt.validate(pngBytes: bytes)
            }
            encodedImageBytes += bytes.count
            encodedImageBase64Bytes += image.png.utf8.count
            imagePixels += dimensions.width * dimensions.height
            guard encodedImageBytes <= 20 * 1024 * 1024, imagePixels <= 12 * 1024 * 1024 else { throw NoteDocumentError.tooLarge("Image data exceeds document limits") }
            if schemaVersion == 11 && encodedImageBase64Bytes > 20 * 1024 * 1024 {
                throw NoteDocumentError.tooLarge("Encoded image data exceeds the schema-11 document limit")
            }
        }
        return self
    }

    public static func decode(_ data: Data, maximumBytes: Int = NoteDocument.maximumEncodedBytes) throws -> NoteDocument {
        guard maximumBytes > 0, data.count <= maximumBytes else { throw NoteDocumentError.tooLarge("Note file exceeds 50 MB") }
        do {
            try validateAgentImageIntegerTokens(data)
            return try JSONDecoder().decode(NoteDocument.self, from: data).validated()
        } catch let error as NoteDocumentError {
            throw error
        } catch {
            throw NoteDocumentError.malformed(error.localizedDescription)
        }
    }

    private static func validateAgentImageIntegerTokens(_ data: Data) throws {
        // Parse the actual JSON keys before checking numeric token kinds. Searching
        // the byte stream for `"agentImage"` misses equivalent escaped JSON keys.
        guard let root = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let images = root["images"] as? [[String: Any]] else { return }
        for image in images {
            guard let rawReceipt = image["agentImage"] else { continue }
            guard let receipt = rawReceipt as? [String: Any] else {
                throw NoteDocumentError.malformed("Agent image receipt must be an object")
            }
            for field in ["schemaVersion", "sourceNoteRevision", "sizeBytes", "addedAt"] {
                guard let number = receipt[field] as? NSNumber,
                      CFGetTypeID(number) != CFBooleanGetTypeID(),
                      !CFNumberIsFloatType(number as CFNumber),
                      Int64(number.stringValue) != nil else {
                    throw NoteDocumentError.malformed("Agent image receipt \(field) must be an exact signed integer")
                }
            }
        }
    }

    public func encoded(maximumBytes: Int = NoteDocument.maximumEncodedBytes) throws -> Data {
        let checked = try validated()
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        do {
            let data = try encoder.encode(checked)
            guard maximumBytes > 0, data.count <= maximumBytes else {
                throw NoteDocumentError.tooLarge("Note file exceeds 50 MB")
            }
            return data
        }
        catch let error as NoteDocumentError { throw error }
        catch { throw NoteDocumentError.malformed("Unable to encode note: \(error.localizedDescription)") }
    }

    private enum CodingKeys: String, CodingKey {
        case schemaVersion, id, title, updatedAt, canvasWidth, canvasHeight, pageWidth, pageHeight, pageGap, pageCount, pdfPageCount, pageTopologyRevision
        case viewportScale, viewportZoom, viewportCenterX, viewportCenterY, strokes, textFlows, textBoxes, images, pageStyle
        case authorPageEditSerial, authorPageTopologySerial
        case _padnoteShelfState, _padnoteShelfRetiredAt
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
        guard (1...11).contains(schemaVersion) else { throw NoteDocumentError.unsupportedSchema(schemaVersion) }
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
        pageTopologyRevision = try c.decodeIfPresent(Int.self, forKey: .pageTopologyRevision) ?? 0
        strokes = try c.decodeIfPresent([InkStroke].self, forKey: .strokes) ?? []
        images = try c.decodeIfPresent([NoteImage].self, forKey: .images) ?? []
        pageStyle = try c.decodeIfPresent(PageStyle.self, forKey: .pageStyle) ?? PageStyle()
        let decodedZoom = try c.decodeIfPresent(Double.self, forKey: .viewportZoom)
        let decodedScale = try c.decodeIfPresent(Double.self, forKey: .viewportScale)
        viewportZoom = decodedZoom ?? decodedScale ?? 1
        viewportCenterX = try c.decodeIfPresent(Double.self, forKey: .viewportCenterX) ?? pageWidth / 2
        viewportCenterY = try c.decodeIfPresent(Double.self, forKey: .viewportCenterY) ?? pageHeight / 2
        viewportScale = c.contains(.viewportScale) ? try c.decode(Double.self, forKey: .viewportScale) : nil
        if let viewportScale, !viewportScale.isFinite || !(Self.minimumAndroidViewportScale...4.0).contains(viewportScale) {
            throw NoteDocumentError.malformed("Invalid viewportScale")
        }
        authorPageEditSerial = c.contains(.authorPageEditSerial)
            ? try c.decode(Int64.self, forKey: .authorPageEditSerial) : nil
        authorPageTopologySerial = c.contains(.authorPageTopologySerial)
            ? try c.decode(Int64.self, forKey: .authorPageTopologySerial) : nil
        for (name, serial) in [("authorPageEditSerial", authorPageEditSerial),
                               ("authorPageTopologySerial", authorPageTopologySerial)] {
            if let serial, serial < 0 || serial > Int64.max - 1024 {
                throw NoteDocumentError.malformed("Invalid \(name)")
            }
        }
        let hasShelfState = c.contains(._padnoteShelfState)
        let hasShelfRetiredAt = c.contains(._padnoteShelfRetiredAt)
        guard hasShelfState == hasShelfRetiredAt else {
            throw DecodingError.dataCorrupted(.init(codingPath: c.codingPath,
                debugDescription: "Shelf retirement metadata must contain both fields"))
        }
        if hasShelfState {
            // `decode`, rather than `decodeIfPresent`, deliberately rejects JSON null.
            shelfState = try c.decode(String.self, forKey: ._padnoteShelfState)
            shelfRetiredAt = try c.decode(Int64.self, forKey: ._padnoteShelfRetiredAt)
        } else {
            shelfState = nil
            shelfRetiredAt = nil
        }
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
        try c.encode(pageTopologyRevision, forKey: .pageTopologyRevision)
        try c.encode(strokes, forKey: .strokes); try c.encode(textFlows, forKey: .textFlows); try c.encode(images, forKey: .images)
        try c.encode(pageStyle, forKey: .pageStyle); try c.encode(viewportZoom, forKey: .viewportZoom)
        try c.encode(viewportCenterX, forKey: .viewportCenterX); try c.encode(viewportCenterY, forKey: .viewportCenterY)
        try c.encodeIfPresent(viewportScale, forKey: .viewportScale)
        try c.encodeIfPresent(authorPageEditSerial, forKey: .authorPageEditSerial)
        try c.encodeIfPresent(authorPageTopologySerial, forKey: .authorPageTopologySerial)
        try c.encodeIfPresent(shelfState, forKey: ._padnoteShelfState)
        try c.encodeIfPresent(shelfRetiredAt, forKey: ._padnoteShelfRetiredAt)
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
