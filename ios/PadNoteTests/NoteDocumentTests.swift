import XCTest
import PDFKit
import zlib
@testable import PadNote

@MainActor
final class NoteDocumentTests: XCTestCase {
    func testSchemaEightRoundTripPreservesInkPressureAndTime() throws {
        var note = NoteDocument(title: "Calculus", pageStyle: PageStyle(paper: "grid", ratio: "a4", landscape: true))
        note.updatedAt = 1_725_000_000_123
        note.strokes = [InkStroke(id: "stroke-1", color: "#80123456", baseWidth: 3.25, createdAt: 42,
                                  highlighter: true, points: [InkPoint(x: 1.5, y: 2.5, pressure: 0.17, timestamp: 99)])]
        note.textFlows = [NoteTextFlow(id: "flow-1", format: "markdown", source: "# title", fontSizeSp: 18,
                                       lineHeight: 1.4, width: 500, anchorPageIndex: 0, anchorXInPage: 20, anchorYInPage: 30)]
        let decoded = try NoteDocument.decode(try note.encoded())
        XCTAssertEqual(decoded, note)
        XCTAssertEqual(decoded.strokes[0].points[0].pressure, 0.17)
        XCTAssertEqual(decoded.strokes[0].points[0].timestamp, 99)

        note.strokes[0].points[0].pressure = 2.5
        XCTAssertEqual(try NoteDocument.decode(try note.encoded()).strokes[0].points[0].pressure, 2.5)
    }

    func testLegacyTextBoxesMigrateToOneFlowPerFlowID() throws {
        let json = #"{"schemaVersion":4,"id":"old","title":"Legacy","updatedAt":1,"pageWidth":768,"pageHeight":1086,"pageGap":24,"pageCount":2,"strokes":[],"textBoxes":[{"id":"fragment-2","flowId":"flow","flowIndex":1,"source":"ignored duplicate","x":10,"y":1120,"width":300},{"id":"fragment-1","flowId":"flow","flowIndex":0,"format":"markdown","source":"kept source","x":12,"y":1130,"width":320}]}"#.data(using: .utf8)!
        let note = try NoteDocument.decode(json)
        XCTAssertEqual(note.textFlows.count, 1)
        XCTAssertEqual(note.textFlows[0].id, "flow")
        XCTAssertEqual(note.textFlows[0].source, "kept source")
        XCTAssertEqual(note.textFlows[0].format, "markdown")
        XCTAssertEqual(note.textFlows[0].anchorPageIndex, 1)
    }

    func testRejectsUnknownSchemaCorruptionAndLimits() throws {
        let unknown = #"{"schemaVersion":9,"strokes":[]}"#.data(using: .utf8)!
        XCTAssertThrowsError(try NoteDocument.decode(unknown)) { error in
            XCTAssertEqual(error as? NoteDocumentError, .unsupportedSchema(9))
        }
        let missingStrokes = #"{"schemaVersion":8,"id":"x"}"#.data(using: .utf8)!
        XCTAssertThrowsError(try NoteDocument.decode(missingStrokes))
        var tooManyPages = NoteDocument()
        tooManyPages.pageCount = 501
        XCTAssertThrowsError(try tooManyPages.encoded())
    }

    func testImportUsesUniqueIDsAndRecoversBackup() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let source = root.appendingPathComponent("source.json")
        let sourceNote = NoteDocument(title: "Imported")
        try sourceNote.encoded().write(to: source)
        let library = NoteLibrary(directory: root.appendingPathComponent("notes"))
        let first = try library.importNote(from: source)
        let second = try library.importNote(from: source)
        XCTAssertNotEqual(first.id, second.id)
        XCTAssertEqual(library.notes.count, 2)

        var replacement = first
        replacement.title = "Recovered"
        try library.save(replacement)
        let target = root.appendingPathComponent("notes/\(first.id).json")
        let backup = URL(fileURLWithPath: target.path + ".bak")
        try FileManager.default.copyItem(at: target, to: backup)
        try Data("not json".utf8).write(to: target)
        let recovered = NoteLibrary(directory: root.appendingPathComponent("notes"))
        XCTAssertEqual(recovered.notes.first(where: { $0.id == first.id })?.title, "Recovered")
    }

    func testPDFArchiveStoredRoundTripAndCRCProtection() throws {
        let pdf = PDFDocument()
        pdf.insert(PDFPage(), at: 0)
        let pdfData = try XCTUnwrap(pdf.dataRepresentation())
        var note = NoteDocument(title: "PDF note")
        note.pdfPageCount = 1
        note.pageCount = 1
        let archive = try NoteArchive.encode(note: note, pdf: pdfData)
        let payload = try NoteArchive.decode(archive)
        XCTAssertEqual(payload.note, note)
        XCTAssertEqual(payload.pdf, pdfData)
        var damaged = archive
        damaged[damaged.count - 1] ^= 1
        XCTAssertThrowsError(try NoteArchive.decode(damaged))
    }

    func testPDFJSONExportUsesPortableArchiveExtension() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let pdf = PDFDocument(); pdf.insert(PDFPage(), at: 0)
        let source = try XCTUnwrap(pdf.dataRepresentation())
        var note = NoteDocument(title: "Archive")
        note.pdfPageCount = 1
        let library = NoteLibrary(directory: root)
        try library.save(note)
        try source.write(to: root.appendingPathComponent("\(note.id).pdf"))
        let output = try library.exportURL(for: note)
        XCTAssertTrue(output.lastPathComponent.hasSuffix(".padnote.zip"))
        XCTAssertEqual(try NoteArchive.decode(Data(contentsOf: output)).note.pdfPageCount, 1)
    }

    func testAndroidStyleDeflateDescriptorArchiveRoundTrip() throws {
        let pdf = PDFDocument(); pdf.insert(PDFPage(), at: 0)
        let source = try XCTUnwrap(pdf.dataRepresentation())
        var note = NoteDocument(title: "Deflated")
        note.pdfPageCount = 1
        let json = try note.encoded()
        let archive = try makeDescriptorArchive([("note.json", json), ("source.pdf", source)])
        XCTAssertEqual(try NoteArchive.decode(archive).note.title, "Deflated")
    }

    func testArchiveCentralDirectoryTamperingAndUnknownPathAreRejected() throws {
        let pdf = PDFDocument(); pdf.insert(PDFPage(), at: 0)
        var note = NoteDocument(title: "Checks"); note.pdfPageCount = 1
        var archive = try NoteArchive.encode(note: note, pdf: try XCTUnwrap(pdf.dataRepresentation()))
        let centralOffset = Int(UInt32(archive[archive.count - 6]) | UInt32(archive[archive.count - 5]) << 8 |
                                UInt32(archive[archive.count - 4]) << 16 | UInt32(archive[archive.count - 3]) << 24)
        // The first central entry's compressed size is at +20.
        archive[centralOffset + 20] = 0xff
        archive[centralOffset + 21] = 0xff
        archive[centralOffset + 22] = 0xff
        archive[centralOffset + 23] = 0xff
        XCTAssertThrowsError(try NoteArchive.decode(archive))

        var unknown = try NoteArchive.encode(note: note, pdf: try XCTUnwrap(pdf.dataRepresentation()))
        let firstNameLength = Int(UInt16(unknown[26]) | UInt16(unknown[27]) << 8)
        XCTAssertEqual(String(data: unknown[30..<(30 + firstNameLength)], encoding: .utf8), "note.json")
        unknown[30] = Character("x").asciiValue!
        XCTAssertThrowsError(try NoteArchive.decode(unknown))
    }

    private func makeDescriptorArchive(_ entries: [(String, Data)]) throws -> Data {
        var output = Data(); var central = Data()
        for (name, content) in entries {
            let nameData = Data(name.utf8)
            let compressed = try rawDeflate(content)
            let crc = crc32(content)
            let localOffset = UInt32(output.count)
            output.appendLE(UInt32(0x04034b50)); output.appendLE(UInt16(20)); output.appendLE(UInt16(0x0008)); output.appendLE(UInt16(8))
            output.appendLE(UInt16(0)); output.appendLE(UInt16(0)); output.appendLE(UInt32(0)); output.appendLE(UInt32(0)); output.appendLE(UInt32(0))
            output.appendLE(UInt16(nameData.count)); output.appendLE(UInt16(0)); output.append(nameData); output.append(compressed)
            output.appendLE(UInt32(0x08074b50)); output.appendLE(crc); output.appendLE(UInt32(compressed.count)); output.appendLE(UInt32(content.count))
            central.appendLE(UInt32(0x02014b50)); central.appendLE(UInt16(20)); central.appendLE(UInt16(20)); central.appendLE(UInt16(0x0008)); central.appendLE(UInt16(8))
            central.appendLE(UInt16(0)); central.appendLE(UInt16(0)); central.appendLE(crc); central.appendLE(UInt32(compressed.count)); central.appendLE(UInt32(content.count))
            central.appendLE(UInt16(nameData.count)); central.appendLE(UInt16(0)); central.appendLE(UInt16(0)); central.appendLE(UInt16(0)); central.appendLE(UInt16(0)); central.appendLE(UInt32(0)); central.appendLE(localOffset); central.append(nameData)
        }
        let centralOffset = UInt32(output.count); output.append(central)
        output.appendLE(UInt32(0x06054b50)); output.appendLE(UInt16(0)); output.appendLE(UInt16(0)); output.appendLE(UInt16(entries.count)); output.appendLE(UInt16(entries.count)); output.appendLE(UInt32(central.count)); output.appendLE(centralOffset); output.appendLE(UInt16(0))
        return output
    }

    private func rawDeflate(_ input: Data) throws -> Data {
        var stream = z_stream()
        guard deflateInit2_(&stream, Z_DEFAULT_COMPRESSION, Z_DEFLATED, -15, 8, Z_DEFAULT_STRATEGY,
                            ZLIB_VERSION, Int32(MemoryLayout<z_stream>.size)) == Z_OK else { throw NSError(domain: "zlib", code: 1) }
        defer { deflateEnd(&stream) }
        var output = Data(count: max(1024, input.count + 1024))
        let status: Int32 = input.withUnsafeBytes { source in
            output.withUnsafeMutableBytes { destination in
                stream.next_in = UnsafeMutablePointer(mutating: source.bindMemory(to: Bytef.self).baseAddress)
                stream.avail_in = uInt(input.count)
                stream.next_out = destination.bindMemory(to: Bytef.self).baseAddress
                stream.avail_out = uInt(destination.count)
                return deflate(&stream, Z_FINISH)
            }
        }
        guard status == Z_STREAM_END else { throw NSError(domain: "zlib", code: Int(status)) }
        output.count = Int(stream.total_out)
        return output
    }

    private func crc32(_ data: Data) -> UInt32 {
        data.withUnsafeBytes { UInt32(zlib.crc32(0, $0.bindMemory(to: Bytef.self).baseAddress, uInt(data.count))) }
    }
}

private extension Data {
    mutating func appendLE(_ value: UInt16) { append(UInt8(value & 0xff)); append(UInt8(value >> 8)) }
    mutating func appendLE(_ value: UInt32) { append(UInt8(value & 0xff)); append(UInt8((value >> 8) & 0xff)); append(UInt8((value >> 16) & 0xff)); append(UInt8((value >> 24) & 0xff)) }
}
