import Foundation
import CryptoKit

/// Vault-only portion of the cross-platform material descriptor format.
/// Video descriptors remain owned by the separate application-wide integration.
enum MaterialCodec {
    static let magic = Data([0x50, 0x4e, 0x4d, 0x44, 0, 1])
    private static let domain = Data("PadNote/MaterialDescriptor/v1\0".utf8)
    private static let null: UInt8 = 0
    private static let utf8: UInt8 = 1
    private static let i64: UInt8 = 2
    private static let bytes: UInt8 = 3
    private static let recordType: UInt8 = 5

    struct Field { let tag: UInt16; let type: UInt8; let data: Data }
    enum Timestamp { case integer(String, Int64) }

    struct Vault {
        var materialID: String
        var sourceState: String
        var sourceNoteID: String
        var title: String
        var ownerLineageID: String?
        var sourceLineageID: String?
        var sourceRevision: Timestamp
        var createdAt: Timestamp
        var pageCount: Int?
        var markdownUTF8: Data
    }

    struct VaultDescriptorSummary {
        let materialID: String
        let sourceState: String
        let sourceNoteID: String
        let title: String
        let ownerLineageID: String?
        let sourceLineageID: String?
        let sourceRevision: Timestamp
        let createdAt: Timestamp
        let pageCount: Int?
        let bodyByteLength: Int64
        let bodySHA256: String
    }

    enum Error: Swift.Error { case invalid(String) }

    static func vaultBytes(_ value: Vault) throws -> Data {
        try validateIdentity(value.materialID, owner: value.ownerLineageID,
                             source: value.sourceLineageID, state: value.sourceState)
        try require(!value.sourceNoteID.isEmpty, "source_note_id")
        try require(!value.title.isEmpty, "title")
        guard case .integer(let revisionKind, let revisionMS) = value.sourceRevision,
              case .integer(let createdKind, let createdMS) = value.createdAt else {
            throw Error.invalid("timestamp_type")
        }
        try require(revisionKind == "unix_ms_i64" && revisionMS >= 0, "vault_revision")
        try require(createdKind == "unix_ms_i64" && createdMS >= 0, "created_at")
        try require(value.pageCount == nil || value.pageCount! >= 0, "page_count")
        try require(value.markdownUTF8.count <= 16 * 1024 * 1024, "vault_bounds")
        try require(String(data: value.markdownUTF8, encoding: .utf8) != nil, "invalid_utf8")
        return try vaultDescriptor(value, bodyHash: Data(SHA256.hash(data: value.markdownUTF8)),
                                  bodyLength: Int64(value.markdownUTF8.count))
    }

    static func inspectVaultDescriptor(_ descriptor: Data) throws -> VaultDescriptorSummary {
        try require(descriptor.count >= 11 && descriptor.count <= 16 * 1024 * 1024,
                    "descriptor_size")
        let fields = try parseRecord(descriptor, kind: 2, count: 12)
        let pageField = fields[11]
        try require(pageField.tag == 12 && (pageField.type == null || pageField.type == i64),
                    "descriptor_page_type")
        let pageCount = pageField.type == null ? nil : Int(try number(pageField))
        let value = Vault(materialID: try text(fields[0]), sourceState: try text(fields[2]),
            sourceNoteID: try text(fields[4]), title: try text(fields[7]),
            ownerLineageID: try optionalText(fields[1]), sourceLineageID: try optionalText(fields[3]),
            sourceRevision: try timestamp(fields[5]), createdAt: try timestamp(fields[6]),
            pageCount: pageCount, markdownUTF8: Data())
        let bodyLength = try number(fields[8])
        let bodyHash = try field(fields[9], tag: 10, type: bytes).data
        try require(bodyLength >= 0 && bodyLength <= 16 * 1024 * 1024 && bodyHash.count == 32,
                    "descriptor_body_bounds")
        try require(try vaultDescriptor(value, bodyHash: bodyHash, bodyLength: bodyLength) == descriptor,
                    "descriptor_noncanonical")
        return VaultDescriptorSummary(materialID: value.materialID, sourceState: value.sourceState,
            sourceNoteID: value.sourceNoteID, title: value.title, ownerLineageID: value.ownerLineageID,
            sourceLineageID: value.sourceLineageID, sourceRevision: value.sourceRevision,
            createdAt: value.createdAt, pageCount: value.pageCount, bodyByteLength: bodyLength,
            bodySHA256: bodyHash.map { String(format: "%02x", $0) }.joined())
    }

    static func digest(_ descriptor: Data) throws -> Data {
        _ = try inspectVaultDescriptor(descriptor)
        return Data(SHA256.hash(data: domain + descriptor))
    }

    private static func vaultDescriptor(_ value: Vault, bodyHash: Data, bodyLength: Int64) throws -> Data {
        try require(bodyHash.count == 32 && bodyLength >= 0 && bodyLength <= 16 * 1024 * 1024,
                    "descriptor_body_bounds")
        try validateIdentity(value.materialID, owner: value.ownerLineageID,
                             source: value.sourceLineageID, state: value.sourceState)
        try require(!value.sourceNoteID.isEmpty && !value.title.isEmpty, "descriptor_required_text")
        guard case .integer(let revisionKind, let revisionMS) = value.sourceRevision,
              case .integer(let createdKind, let createdMS) = value.createdAt else {
            throw Error.invalid("timestamp_type")
        }
        try require(revisionKind == "unix_ms_i64" && revisionMS >= 0, "vault_revision")
        try require(createdKind == "unix_ms_i64" && createdMS >= 0, "created_at")
        try require(value.pageCount == nil || value.pageCount! >= 0, "page_count")
        let page = value.pageCount.map { integer(12, Int64($0)) }
            ?? Field(tag: 12, type: null, data: Data())
        return try record(kind: 2, fields: [
            string(1, value.materialID), optionalString(2, value.ownerLineageID),
            string(3, value.sourceState), optionalString(4, value.sourceLineageID),
            string(5, value.sourceNoteID), timestamp(6, value.sourceRevision),
            timestamp(7, value.createdAt), string(8, value.title), integer(9, bodyLength),
            binary(10, bodyHash), integer(11, 1), page
        ])
    }

    private static func record(kind: UInt8, fields: [Field]) throws -> Data {
        var output = magic
        output.append(kind)
        append32(&output, UInt32(fields.count))
        var previous: UInt16 = 0
        for field in fields {
            try require(field.tag > previous, "tag_order")
            previous = field.tag
            output.append(UInt8(field.tag >> 8)); output.append(UInt8(field.tag & 255))
            output.append(field.type); append32(&output, UInt32(field.data.count)); output.append(field.data)
        }
        return output
    }

    private static func parseRecord(_ data: Data, kind: UInt8, count: Int) throws -> [Field] {
        let raw = Array(data)
        try require(raw.count >= 11 && Data(raw.prefix(6)) == magic && raw[6] == kind,
                    "descriptor_record")
        let fieldCount = Int(raw[7]) << 24 | Int(raw[8]) << 16 | Int(raw[9]) << 8 | Int(raw[10])
        try require(fieldCount == count, "descriptor_field_count")
        var cursor = 11
        var result = [Field]()
        for index in 0..<count {
            try require(cursor + 7 <= raw.count, "descriptor_truncated")
            let tag = UInt16(raw[cursor]) << 8 | UInt16(raw[cursor + 1])
            let type = raw[cursor + 2]
            let length = Int(raw[cursor + 3]) << 24 | Int(raw[cursor + 4]) << 16
                | Int(raw[cursor + 5]) << 8 | Int(raw[cursor + 6])
            try require(tag == index + 1 && length >= 0 && cursor + 7 + length <= raw.count,
                        "descriptor_field_shape")
            cursor += 7
            let payload = Data(raw[cursor..<(cursor + length)])
            cursor += length
            if type == null { try require(length == 0, "descriptor_null_length") }
            if type == i64 { try require(length == 8, "descriptor_number_length") }
            result.append(Field(tag: tag, type: type, data: payload))
        }
        try require(cursor == raw.count, "descriptor_trailing_bytes")
        return result
    }

    private static func field(_ value: Field, tag: UInt16, type: UInt8) throws -> Field {
        try require(value.tag == tag && value.type == type, "descriptor_field_type")
        return value
    }

    private static func text(_ value: Field) throws -> String {
        let payload = try field(value, tag: value.tag, type: utf8).data
        guard let result = String(data: payload, encoding: .utf8) else { throw Error.invalid("descriptor_utf8") }
        return result
    }

    private static func optionalText(_ value: Field) throws -> String? {
        try require(value.type == null || value.type == utf8, "descriptor_field_type")
        return value.type == null ? nil : try text(value)
    }

    private static func number(_ value: Field) throws -> Int64 {
        let payload = try field(value, tag: value.tag, type: i64).data
        var bits: UInt64 = 0
        for byte in payload { bits = (bits << 8) | UInt64(byte) }
        return Int64(bitPattern: bits)
    }

    private static func timestamp(_ value: Field) throws -> Timestamp {
        let payload = try field(value, tag: value.tag, type: recordType).data
        let nested = try parseRecord(payload, kind: 5, count: 2)
        let representation = try text(nested[0])
        return .integer(representation, try number(nested[1]))
    }

    private static func string(_ tag: UInt16, _ value: String) -> Field {
        Field(tag: tag, type: utf8, data: Data(value.utf8))
    }

    private static func optionalString(_ tag: UInt16, _ value: String?) -> Field {
        value.map { string(tag, $0) } ?? Field(tag: tag, type: null, data: Data())
    }

    private static func integer(_ tag: UInt16, _ value: Int64) -> Field {
        var bigEndian = value.bigEndian
        return Field(tag: tag, type: i64, data: withUnsafeBytes(of: &bigEndian) { Data($0) })
    }

    private static func binary(_ tag: UInt16, _ value: Data) -> Field {
        Field(tag: tag, type: bytes, data: value)
    }

    private static func timestamp(_ tag: UInt16, _ value: Timestamp) throws -> Field {
        guard case .integer(let representation, let number) = value else { throw Error.invalid("timestamp_type") }
        let nested = try record(kind: 5, fields: [string(1, representation), integer(2, number)])
        return Field(tag: tag, type: recordType, data: nested)
    }

    private static func validateIdentity(_ materialID: String, owner: String?, source: String?, state: String) throws {
        try validUUID(materialID)
        if let owner { try validUUID(owner) }
        if let source { try validUUID(source) }
        try require(["linked_note", "source_deleted", "source_not_selected", "independent"].contains(state), "source_state")
        try require(state == "independent" ? owner == nil && source == nil : owner != nil, "owner_lineage_state")
        try require(state != "linked_note" || owner == source, "source_lineage_mismatch")
        try require(state == "independent" || source == nil || owner == source, "source_lineage_mismatch")
    }

    private static func validUUID(_ value: String) throws {
        try require(value.range(of: "^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
                                options: .regularExpression) != nil, "id_format")
    }

    private static func append32(_ data: inout Data, _ value: UInt32) {
        data.append(UInt8((value >> 24) & 255)); data.append(UInt8((value >> 16) & 255))
        data.append(UInt8((value >> 8) & 255)); data.append(UInt8(value & 255))
    }

    private static func require(_ condition: Bool, _ code: String) throws {
        if !condition { throw Error.invalid(code) }
    }
}
