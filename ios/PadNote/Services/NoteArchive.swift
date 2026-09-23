import Foundation
import PDFKit
import zlib

public struct NoteArchivePayload {
    public let note: NoteDocument
    public let pdf: Data

    public init(note: NoteDocument, pdf: Data) {
        self.note = note
        self.pdf = pdf
    }
}

public enum NoteArchiveError: Error, LocalizedError, Equatable {
    case invalidArchive(String)
    case sizeLimit(String)
    case invalidPDF(String)

    public var errorDescription: String? {
        switch self {
        case .invalidArchive(let message), .sizeLimit(let message), .invalidPDF(let message): return message
        }
    }
}

/// Portable PDF-backed PadNote package. The archive deliberately accepts only
/// the two fixed entries used by Android's PdfNoteIO: note.json and source.pdf.
public enum NoteArchive {
    public static let maxJSONBytes = 50 * 1024 * 1024
    public static let maxPDFBytes = 100 * 1024 * 1024
    public static let maxArchiveBytes = maxJSONBytes + maxPDFBytes + 64 * 1024

    public static func decode(_ data: Data) throws -> NoteArchivePayload {
        guard data.count <= maxArchiveBytes else { throw NoteArchiveError.sizeLimit("笔记包超过 150 MB 限制") }
        let directory = try readCentralDirectory(data)
        let central = directory.entries
        let centralOffset = directory.offset
        var files: [String: Data] = [:]
        var ranges: [(start: Int, end: Int)] = []
        for entry in central.values.sorted(by: { $0.localOffset < $1.localOffset }) {
            let offset = entry.localOffset
            guard offset >= 0, offset + 30 <= data.count, u32(data, offset) == 0x04034b50 else {
                throw NoteArchiveError.invalidArchive("笔记包 local header 无效")
            }
            let flags = u16(data, offset + 6)
            let method = u16(data, offset + 8)
            let localCRC = u32(data, offset + 14)
            let localCompressedSize = Int(u32(data, offset + 18))
            let localUncompressedSize = Int(u32(data, offset + 22))
            let nameLength = Int(u16(data, offset + 26))
            let extraLength = Int(u16(data, offset + 28))
            let headerEnd = offset + 30 + nameLength + extraLength
            guard headerEnd <= data.count, flags & 0x0001 == 0 else { throw NoteArchiveError.invalidArchive("笔记包使用了不支持的加密") }
            guard method == entry.method else { throw NoteArchiveError.invalidArchive("笔记包压缩方式不一致") }
            let nameData = data[(offset + 30)..<(offset + 30 + nameLength)]
            guard let name = String(data: nameData, encoding: .utf8), name == "note.json" || name == "source.pdf" else {
                throw NoteArchiveError.invalidArchive("不是支持的 PadNote 包（未知或非法路径）")
            }
            guard name == entry.name else { throw NoteArchiveError.invalidArchive("笔记包中央目录路径不一致") }
            if flags & 0x0008 == 0 {
                guard localCRC == entry.crc, localCompressedSize == entry.compressedSize, localUncompressedSize == entry.uncompressedSize else {
                    throw NoteArchiveError.invalidArchive("笔记包 local header 校验失败")
                }
            }
            guard files[name] == nil else { throw NoteArchiveError.invalidArchive("笔记包包含重复文件") }
            if name == "note.json" {
                guard entry.uncompressedSize <= maxJSONBytes else { throw NoteArchiveError.sizeLimit("笔记数据超过 50 MB 限制") }
            } else {
                guard entry.uncompressedSize <= maxPDFBytes else { throw NoteArchiveError.sizeLimit("PDF 超过 100 MB 限制") }
            }
            let dataEnd = headerEnd + entry.compressedSize
            guard entry.compressedSize >= 0, entry.uncompressedSize >= 0, dataEnd >= headerEnd, dataEnd <= centralOffset else {
                throw NoteArchiveError.invalidArchive("笔记包条目长度无效")
            }
            let range = (offset, dataEnd)
            guard !ranges.contains(where: { range.0 < $0.end && $0.start < range.1 }) else {
                throw NoteArchiveError.invalidArchive("笔记包条目范围重叠")
            }
            ranges.append(range)
            let compressed = data[headerEnd..<dataEnd]
            let content: Data
            switch entry.method {
            case 0:
                guard entry.compressedSize == entry.uncompressedSize else { throw NoteArchiveError.invalidArchive("笔记包大小校验失败") }
                content = Data(compressed)
            case 8:
                content = try inflateRaw(Data(compressed), expectedSize: entry.uncompressedSize)
            default:
                throw NoteArchiveError.invalidArchive("笔记包压缩方式不受支持")
            }
            guard content.count == entry.uncompressedSize, crc32(content) == entry.crc else {
                throw NoteArchiveError.invalidArchive("笔记包 CRC 校验失败")
            }
            files[name] = content
        }
        guard let json = files["note.json"], let pdf = files["source.pdf"] else {
            throw NoteArchiveError.invalidArchive("笔记包必须包含 note.json 和 source.pdf")
        }
        guard files.count == central.count else { throw NoteArchiveError.invalidArchive("笔记包条目不完整") }
        let note: NoteDocument
        do { note = try NoteDocument.decode(json) }
        catch { throw NoteArchiveError.invalidArchive("笔记元数据无效：\(error.localizedDescription)") }
        guard note.pdfPageCount > 0 else { throw NoteArchiveError.invalidArchive("PDF 缺少对应的笔记元数据") }
        try validatePDF(pdf, expectedPageCount: note.pdfPageCount)
        return NoteArchivePayload(note: note, pdf: pdf)
    }

    public static func encode(note: NoteDocument, pdf: Data) throws -> Data {
        let checked = try note.validated()
        guard checked.pdfPageCount > 0 else { throw NoteArchiveError.invalidArchive("普通笔记不需要 PDF 包") }
        guard pdf.count <= maxPDFBytes else { throw NoteArchiveError.sizeLimit("PDF 超过 100 MB 限制") }
        try validatePDF(pdf, expectedPageCount: checked.pdfPageCount)
        let json = try checked.encoded()
        guard json.count <= maxJSONBytes else { throw NoteArchiveError.sizeLimit("笔记数据超过 50 MB 限制") }
        return makeStoredZip([("note.json", json), ("source.pdf", pdf)])
    }

    public static func importArchive(_ data: Data) throws -> NoteArchivePayload { try decode(data) }
    public static func exportArchive(note: NoteDocument, pdf: Data) throws -> Data { try encode(note: note, pdf: pdf) }

    public static func validatePDF(_ data: Data, expectedPageCount: Int) throws {
        guard data.count <= maxPDFBytes else { throw NoteArchiveError.sizeLimit("PDF 超过 100 MB 限制") }
        guard let document = PDFDocument(data: data), document.pageCount > 0 else {
            throw NoteArchiveError.invalidPDF("无法读取 PDF")
        }
        guard !document.isLocked else { throw NoteArchiveError.invalidPDF("PDF 受密码保护，请先解锁并另存") }
        guard document.pageCount <= 500, document.pageCount == expectedPageCount else {
            throw NoteArchiveError.invalidPDF("PDF 页数与笔记元数据不匹配")
        }
        for index in 0..<document.pageCount {
            guard let page = document.page(at: index) else { throw NoteArchiveError.invalidPDF("无法读取 PDF 页面") }
            let bounds = page.bounds(for: .mediaBox)
            guard bounds.width.isFinite, bounds.height.isFinite, bounds.width > 0, bounds.height > 0,
                  bounds.width <= 100_000, bounds.height <= 100_000 else {
                throw NoteArchiveError.invalidPDF("PDF 页面尺寸无效")
            }
        }
    }

    private static func makeStoredZip(_ entries: [(String, Data)]) -> Data {
        var output = Data()
        var central = Data()
        for (name, content) in entries {
            let nameBytes = Data(name.utf8)
            let crc = crc32(content)
            let start = UInt32(output.count)
            output.appendLE(UInt32(0x04034b50)); output.appendLE(UInt16(20)); output.appendLE(UInt16(0)); output.appendLE(UInt16(0))
            output.appendLE(UInt16(0)); output.appendLE(UInt16(0)); output.appendLE(crc); output.appendLE(UInt32(content.count)); output.appendLE(UInt32(content.count))
            output.appendLE(UInt16(nameBytes.count)); output.appendLE(UInt16(0)); output.append(nameBytes); output.append(content)
            central.appendLE(UInt32(0x02014b50)); central.appendLE(UInt16(20)); central.appendLE(UInt16(20)); central.appendLE(UInt16(0)); central.appendLE(UInt16(0))
            central.appendLE(UInt16(0)); central.appendLE(UInt16(0)); central.appendLE(crc); central.appendLE(UInt32(content.count)); central.appendLE(UInt32(content.count))
            central.appendLE(UInt16(nameBytes.count)); central.appendLE(UInt16(0)); central.appendLE(UInt16(0)); central.appendLE(UInt16(0)); central.appendLE(UInt16(0)); central.appendLE(UInt32(0)); central.appendLE(start)
            central.append(nameBytes)
        }
        let centralOffset = UInt32(output.count)
        output.append(central)
        output.appendLE(UInt32(0x06054b50)); output.appendLE(UInt16(0)); output.appendLE(UInt16(0)); output.appendLE(UInt16(entries.count)); output.appendLE(UInt16(entries.count))
        output.appendLE(UInt32(central.count)); output.appendLE(centralOffset); output.appendLE(UInt16(0))
        return output
    }

    private static func inflateRaw(_ data: Data, expectedSize: Int) throws -> Data {
        guard expectedSize <= maxPDFBytes else { throw NoteArchiveError.sizeLimit("解压数据超过大小限制") }
        var stream = z_stream()
        let initResult = inflateInit2_(&stream, -15, ZLIB_VERSION, Int32(MemoryLayout<z_stream>.size))
        guard initResult == Z_OK else { throw NoteArchiveError.invalidArchive("无法初始化 ZIP 解压") }
        defer { inflateEnd(&stream) }
        var result = Data(count: expectedSize)
        let status: Int32 = data.withUnsafeBytes { source in
            result.withUnsafeMutableBytes { destination in
                stream.next_in = UnsafeMutablePointer(mutating: source.bindMemory(to: Bytef.self).baseAddress)
                stream.avail_in = uInt(data.count)
                stream.next_out = destination.bindMemory(to: Bytef.self).baseAddress
                stream.avail_out = uInt(expectedSize)
                return inflate(&stream, Z_FINISH)
            }
        }
        guard status == Z_STREAM_END, stream.total_out == expectedSize else { throw NoteArchiveError.invalidArchive("ZIP 解压失败") }
        return result
    }

    private struct CentralEntry {
        let name: String
        let method: UInt16
        let crc: UInt32
        let compressedSize: Int
        let uncompressedSize: Int
        let localOffset: Int
    }

    private static func readCentralDirectory(_ data: Data) throws -> (entries: [Int: CentralEntry], offset: Int) {
        guard data.count >= 22 else { throw NoteArchiveError.invalidArchive("笔记包缺少中央目录") }
        let start = max(0, data.count - 65_557)
        var eocd = -1
        if data.count >= 22 {
            for index in stride(from: data.count - 22, through: start, by: -1) where u32(data, index) == 0x06054b50 {
                eocd = index
                break
            }
        }
        guard eocd >= 0 else { throw NoteArchiveError.invalidArchive("笔记包缺少结束目录") }
        guard u16(data, eocd + 4) == 0, u16(data, eocd + 6) == 0,
              u16(data, eocd + 8) == u16(data, eocd + 10),
              u16(data, eocd + 10) <= 2 else {
            throw NoteArchiveError.invalidArchive("笔记包磁盘或条目数量无效")
        }
        let commentLength = Int(u16(data, eocd + 20))
        guard eocd + 22 + commentLength == data.count else { throw NoteArchiveError.invalidArchive("笔记包结束目录后存在多余数据") }
        let count = Int(u16(data, eocd + 10))
        let centralSize = Int(u32(data, eocd + 12))
        let centralOffset = Int(u32(data, eocd + 16))
        guard centralOffset != Int(UInt32.max), centralSize != Int(UInt32.max), centralOffset >= 0, centralSize >= 0,
              centralOffset + centralSize <= data.count, centralOffset + centralSize == eocd else {
            throw NoteArchiveError.invalidArchive("笔记包中央目录长度无效或使用了 ZIP64")
        }
        var entries: [Int: CentralEntry] = [:]
        var names = Set<String>()
        var cursor = centralOffset
        for _ in 0..<count {
            guard cursor + 46 <= data.count, u32(data, cursor) == 0x02014b50 else { throw NoteArchiveError.invalidArchive("笔记包中央目录无效") }
            let method = u16(data, cursor + 10)
            let crc = u32(data, cursor + 16)
            let compressedSize = Int(u32(data, cursor + 20))
            let uncompressedSize = Int(u32(data, cursor + 24))
            let nameLength = Int(u16(data, cursor + 28))
            let extraLength = Int(u16(data, cursor + 30))
            let commentLength = Int(u16(data, cursor + 32))
            let localOffset = Int(u32(data, cursor + 42))
            let end = cursor + 46 + nameLength + extraLength + commentLength
            guard compressedSize != Int(UInt32.max), uncompressedSize != Int(UInt32.max), localOffset != Int(UInt32.max),
                  end <= data.count, let name = String(data: data[(cursor + 46)..<(cursor + 46 + nameLength)], encoding: .utf8), name == "note.json" || name == "source.pdf" else {
                throw NoteArchiveError.invalidArchive("不是支持的 PadNote 包（未知或非法路径）")
            }
            guard names.insert(name).inserted, entries[localOffset] == nil else { throw NoteArchiveError.invalidArchive("笔记包包含重复文件") }
            entries[localOffset] = CentralEntry(name: name, method: method, crc: crc, compressedSize: compressedSize, uncompressedSize: uncompressedSize, localOffset: localOffset)
            cursor = end
        }
        guard cursor == centralOffset + centralSize, entries.count == 2, names == ["note.json", "source.pdf"] else { throw NoteArchiveError.invalidArchive("笔记包必须包含 note.json 和 source.pdf") }
        return (entries, centralOffset)
    }

    private static func crc32(_ data: Data) -> UInt32 {
        data.withUnsafeBytes { bytes in UInt32(zlib.crc32(0, bytes.bindMemory(to: Bytef.self).baseAddress, uInt(data.count))) }
    }
    private static func u16(_ data: Data, _ offset: Int) -> UInt16 { UInt16(data[offset]) | UInt16(data[offset + 1]) << 8 }
    private static func u32(_ data: Data, _ offset: Int) -> UInt32 { UInt32(u16(data, offset)) | UInt32(u16(data, offset + 2)) << 16 }
}

private extension Data {
    mutating func appendLE(_ value: UInt16) { append(UInt8(value & 0xff)); append(UInt8(value >> 8)) }
    mutating func appendLE(_ value: UInt32) { append(UInt8(value & 0xff)); append(UInt8((value >> 8) & 0xff)); append(UInt8((value >> 16) & 0xff)); append(UInt8((value >> 24) & 0xff)) }
}
