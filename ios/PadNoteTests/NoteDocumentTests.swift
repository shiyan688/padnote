import XCTest
import PDFKit
import zlib
@testable import PadNote

@MainActor
final class NoteDocumentTests: XCTestCase {
    private var legacyFixtureDirectory: URL {
        Bundle(for: Self.self).resourceURL!.appendingPathComponent("legacy-notes", isDirectory: true)
    }

    func testSchemaEightRoundTripPreservesInkPressureAndTime() throws {
        var note = NoteDocument(title: "Calculus", pageStyle: PageStyle(paper: "grid", ratio: "a4", landscape: true))
        note.updatedAt = 1_725_000_000_123
        note.viewportZoom = 1.0
        note.viewportScale = 0.8700000047683716
        note.authorPageEditSerial = 9_007_199_254_740_993
        note.authorPageTopologySerial = 9_007_199_254_741_127
        note.strokes = [InkStroke(id: "stroke-1", color: "#80123456", baseWidth: 3.25, createdAt: 42,
                                  highlighter: true, points: [InkPoint(x: 1.5, y: 2.5, pressure: 0.17, timestamp: 99)])]
        note.textFlows = [NoteTextFlow(id: "flow-1", format: "markdown", source: "# title", fontSizeSp: 18,
                                       lineHeight: 1.4, width: 500, anchorPageIndex: 0, anchorXInPage: 20, anchorYInPage: 30)]
        let decoded = try NoteDocument.decode(try note.encoded())
        XCTAssertEqual(decoded, note)
        XCTAssertEqual(decoded.strokes[0].points[0].pressure, 0.17)
        XCTAssertEqual(decoded.strokes[0].points[0].timestamp, 99)
        XCTAssertEqual(decoded.viewportScale, 0.8700000047683716)
        XCTAssertEqual(decoded.viewportZoom, 1.0, "Preserving Android absolute scale must not change iPad's zoom interpretation")

        note.strokes[0].points[0].pressure = 2.5
        XCTAssertEqual(try NoteDocument.decode(try note.encoded()).strokes[0].points[0].pressure, 2.5)
    }

    func testAndroidAuthorPageSerialsSurviveCanonicalRoundTripWithoutBecomingAuthority() throws {
        let json = #"{"schemaVersion":8,"id":"android-note","title":"synthetic","updatedAt":123,"pageWidth":800,"pageHeight":1066,"pageGap":24,"pageCount":1,"pdfPageCount":0,"strokes":[],"textFlows":[],"authorPageEditSerial":9007199254740993,"authorPageTopologySerial":9007199254741127}"#.data(using: .utf8)!
        let decoded = try NoteDocument.decode(json)
        XCTAssertEqual(decoded.authorPageEditSerial, 9_007_199_254_740_993)
        XCTAssertEqual(decoded.authorPageTopologySerial, 9_007_199_254_741_127)

        let encoded = try decoded.encoded()
        let encodedText = String(decoding: encoded, as: UTF8.self)
        XCTAssertTrue(encodedText.contains("\"authorPageEditSerial\":9007199254740993"))
        XCTAssertTrue(encodedText.contains("\"authorPageTopologySerial\":9007199254741127"))
        XCTAssertEqual(try NoteDocument.decode(encoded), decoded)

        let legacy = try NoteDocument.decode(#"{"schemaVersion":8,"id":"legacy","title":"legacy","updatedAt":1,"pageWidth":800,"pageHeight":1066,"pageGap":24,"pageCount":1,"pdfPageCount":0,"strokes":[],"textFlows":[]}"#.data(using: .utf8)!)
        XCTAssertNil(legacy.authorPageEditSerial, "Absent source fields remain absent for older documents")
        XCTAssertNil(legacy.authorPageTopologySerial)
        XCTAssertNil(legacy.viewportScale)
        let legacyEncoded = String(decoding: try legacy.encoded(), as: UTF8.self)
        XCTAssertFalse(legacyEncoded.contains("authorPageEditSerial"))
        XCTAssertFalse(legacyEncoded.contains("authorPageTopologySerial"))
        XCTAssertFalse(legacyEncoded.contains("viewportScale"))

        for invalidValue in ["-1", "1.5", "9223372036854774784"] {
            let malformed = #"{"schemaVersion":8,"id":"bad","title":"bad","updatedAt":1,"pageWidth":800,"pageHeight":1066,"pageGap":24,"pageCount":1,"pdfPageCount":0,"strokes":[],"textFlows":[],"authorPageEditSerial":__VALUE__}"#.replacingOccurrences(of: "__VALUE__", with: invalidValue).data(using: .utf8)!
            XCTAssertThrowsError(try NoteDocument.decode(malformed), "Invalid serial must not be silently reset or rounded")
        }
        for invalidScale in ["null", "\"0.87\"", "0.44", "4.01", "1e400"] {
            let malformed = #"{"schemaVersion":8,"id":"bad","title":"bad","updatedAt":1,"pageWidth":800,"pageHeight":1066,"pageGap":24,"pageCount":1,"pdfPageCount":0,"strokes":[],"textFlows":[],"viewportScale":__VALUE__}"#.replacingOccurrences(of: "__VALUE__", with: invalidScale).data(using: .utf8)!
            XCTAssertThrowsError(try NoteDocument.decode(malformed), "Present scale must be a finite canonical Android Float writer value in [Double(Float(0.45)), 4]")
        }
        let minimumAndroidWireScale = Double(Float(0.45))
        XCTAssertEqual(minimumAndroidWireScale, 0.44999998807907104, "Pin the exact Float-to-Double JSON lower-bound value")
        let minimumJSON = #"{"schemaVersion":8,"id":"min-scale","title":"min-scale","updatedAt":1,"pageWidth":800,"pageHeight":1066,"pageGap":24,"pageCount":1,"pdfPageCount":0,"strokes":[],"textFlows":[],"viewportZoom":1.25,"viewportScale":\#(minimumAndroidWireScale)}"#.data(using: .utf8)!
        let minimumDecoded = try NoteDocument.decode(minimumJSON)
        XCTAssertEqual(minimumDecoded.viewportScale, minimumAndroidWireScale,
                       "Android's canonical Float minimum must survive its Double JSON representation")
        XCTAssertEqual(minimumDecoded.viewportZoom, 1.25,
                       "Keeping viewportScale must not change iPad's existing viewportZoom precedence")
        XCTAssertNoThrow(try minimumDecoded.encoded())

        let maximumJSON = #"{"schemaVersion":8,"id":"max-scale","title":"max-scale","updatedAt":1,"pageWidth":800,"pageHeight":1066,"pageGap":24,"pageCount":1,"pdfPageCount":0,"strokes":[],"textFlows":[],"viewportScale":4}"#.data(using: .utf8)!
        XCTAssertEqual(try NoteDocument.decode(maximumJSON).viewportScale, 4.0)

        let belowMinimum = minimumAndroidWireScale.nextDown
        let belowMinimumJSON = #"{"schemaVersion":8,"id":"below-min-scale","title":"below-min-scale","updatedAt":1,"pageWidth":800,"pageHeight":1066,"pageGap":24,"pageCount":1,"pdfPageCount":0,"strokes":[],"textFlows":[],"viewportScale":\#(belowMinimum)}"#.data(using: .utf8)!
        XCTAssertThrowsError(try NoteDocument.decode(belowMinimumJSON),
                             "The immediately lower Double is not a value produced by Android's Float clamp")
        var belowMinimumDocument = minimumDecoded
        belowMinimumDocument.viewportScale = belowMinimum
        XCTAssertThrowsError(try belowMinimumDocument.encoded(),
                             "Encoding must apply the same exact lower boundary as decoding")
    }

    func testAndroidShelfRetirementMetadataSurvivesOrdinarySaveAndColdReopen() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        var note = NoteDocument(title: "Imported retired note")
        note.shelfState = "retired"
        note.shelfRetiredAt = 9_007_199_254_740_993 // 2^53 + 1 must remain an exact Int64.
        let encoded = try note.encoded()
        XCTAssertTrue(String(decoding: encoded, as: UTF8.self).contains("\"_padnoteShelfRetiredAt\":9007199254740993"))
        let decoded = try NoteDocument.decode(encoded)
        XCTAssertEqual(decoded.shelfState, "retired")
        XCTAssertEqual(decoded.shelfRetiredAt, .some(Int64(9_007_199_254_740_993)))

        let library = NoteLibrary(directory: root)
        try library.save(decoded)
        let reopened = NoteLibrary(directory: root)
        guard let saved = reopened.notes.first else { return XCTFail("saved note missing") }
        XCTAssertEqual(reopened.notes.map(\.id), [note.id], "portable Android shelf metadata does not hide an iPad note")
        XCTAssertEqual(saved.shelfState, "retired")
        XCTAssertEqual(saved.shelfRetiredAt, .some(Int64(9_007_199_254_740_993)))
        let session = try reopened.openEditorSession(noteID: saved.id)
        var edited = session.document
        edited.title = "Edited while retaining imported system metadata"
        _ = try reopened.save(edited, basedOn: session)
        let cold = NoteLibrary(directory: root).notes.first
        XCTAssertEqual(cold?.shelfState, "retired")
        XCTAssertEqual(cold?.shelfRetiredAt, .some(Int64(9_007_199_254_740_993)))

        let ordinary = try NoteDocument(title: "No shelf metadata").encoded()
        let ordinaryDecoded = try NoteDocument.decode(ordinary)
        XCTAssertNil(ordinaryDecoded.shelfState, "both fields absent remains backward compatible")
        XCTAssertNil(ordinaryDecoded.shelfRetiredAt)

        let baseObject = try XCTUnwrap(JSONSerialization.jsonObject(with: ordinary) as? [String: Any])
        func rejects(_ mutate: ([String: Any]) -> [String: Any], file: StaticString = #filePath, line: UInt = #line) throws {
            let malformed = try JSONSerialization.data(withJSONObject: mutate(baseObject))
            XCTAssertThrowsError(try NoteDocument.decode(malformed), file: file, line: line)
        }
        try rejects { object in
            var value = object; value["_padnoteShelfState"] = NSNull(); value["_padnoteShelfRetiredAt"] = NSNull(); return value
        }
        try rejects { object in
            var value = object; value["_padnoteShelfState"] = NSNull(); return value
        }
        try rejects { object in
            var value = object; value["_padnoteShelfState"] = "retired"; return value
        }
        try rejects { object in
            var value = object; value["_padnoteShelfRetiredAt"] = 123; return value
        }
        try rejects { object in
            var value = object; value["_padnoteShelfState"] = 7; value["_padnoteShelfRetiredAt"] = 123; return value
        }
        try rejects { object in
            var value = object; value["_padnoteShelfState"] = "retired"; value["_padnoteShelfRetiredAt"] = "123"; return value
        }
        try rejects { object in
            var value = object; value["_padnoteShelfState"] = "retired"; value["_padnoteShelfRetiredAt"] = 123.5; return value
        }
        try rejects { object in
            var value = object; value["_padnoteShelfState"] = "active"; value["_padnoteShelfRetiredAt"] = 123; return value
        }
        try rejects { object in
            var value = object; value["_padnoteShelfState"] = "retired"; value["_padnoteShelfRetiredAt"] = 0; return value
        }
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

    func testSharedSchemaOneThroughEightFixturesMigrateAndColdReopenWithoutLoss() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        for version in 1...8 {
            let fixture = legacyFixtureDirectory.appendingPathComponent("schema\(version).json")
            var note = try NoteDocument.decode(Data(contentsOf: fixture))
            XCTAssertEqual(note.strokes.first?.id, "stroke-v\(version)", "schema \(version) ink")
            XCTAssertEqual(note.pageWidth, 600, "schema \(version) width")
            XCTAssertEqual(note.pageHeight, 800, "schema \(version) height")

            switch version {
            case 1:
                XCTAssertEqual(note.pageCount, 1); XCTAssertEqual(note.pageStyle.paper, "ruled")
            case 2:
                XCTAssertEqual(note.pageCount, 2); XCTAssertGreaterThan(note.strokes[0].points[0].y, note.pageHeight)
                XCTAssertEqual(note.viewportZoom, 1.05)
            case 3:
                XCTAssertEqual(note.textFlows.first?.source, "x_3^2 + y_3^2 = 1")
                XCTAssertEqual(note.textFlows.first?.anchorXInPage, 48)
            case 4:
                XCTAssertEqual(note.textFlows.count, 1)
                XCTAssertEqual(note.textFlows.first?.id, "legacy-flow-v4")
                XCTAssertEqual(note.textFlows.first?.anchorPageIndex, 1)
                XCTAssertEqual(note.textFlows.first?.anchorYInPage, 100)
            case 5:
                XCTAssertEqual(note.textFlows.first?.lineHeight, 1.35)
                XCTAssertEqual(note.textFlows.first?.anchorPageIndex, 1)
            case 6:
                XCTAssertEqual(note.pageStyle, PageStyle(paper: "grid", ratio: "a4", landscape: true))
                XCTAssertEqual(note.textFlows.first?.source, #"\frac{6}{2}=3"#)
            case 7:
                XCTAssertEqual(note.pdfPageCount, 1)
                XCTAssertEqual(note.textFlows.first?.anchorPageIndex, 1)
            case 8:
                XCTAssertEqual(note.images.first?.id, "image-v8")
                XCTAssertNotNil(note.images.first.flatMap { Data(base64Encoded: $0.png) })
            default: break
            }

            let noteRoot = root.appendingPathComponent("schema\(version)", isDirectory: true)
            let library = NoteLibrary(directory: noteRoot)
            if version == 7 {
                try FileManager.default.createDirectory(at: noteRoot, withIntermediateDirectories: true)
                try FileManager.default.copyItem(
                    at: legacyFixtureDirectory.appendingPathComponent("schema7-source.pdf"),
                    to: noteRoot.appendingPathComponent("\(note.id).pdf"))
            }
            try library.save(note)
            // Legacy fixture identities are intentionally non-UUID strings. They must remain
            // editable through the ordinary editor session while foundation group IDs stay UUID-only.
            let legacySession = try library.openEditorSession(noteID: note.id)
            XCTAssertNil(legacySession.groupToken, "A legacy ID must not create or claim a group")
            var edited = legacySession.document
            edited.title += " edited"
            _ = try library.save(edited, basedOn: legacySession)
            note = edited
            note.schemaVersion = 8
            let reopened = NoteLibrary(directory: noteRoot)
            XCTAssertEqual(reopened.notes.first, note, "schema \(version) must survive canonical save and cold reopen")
            XCTAssertEqual(reopened.notes.first?.schemaVersion, 8)
        }
    }

    func testSharedCorruptTailFixtureDoesNotPartiallyReplaceLiveLibrary() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let library = NoteLibrary(directory: root)
        let live = NoteDocument(title: "仍在的活文档")
        try library.save(live)
        let damaged = legacyFixtureDirectory.appendingPathComponent("schema8-corrupt-tail.json")
        let copied = root.appendingPathComponent("corrupt-fixture.json")
        try FileManager.default.copyItem(at: damaged, to: copied)

        library.reload()
        XCTAssertEqual(library.notes, [live])
        XCTAssertTrue(library.recoveryItems.contains { $0.filename == copied.lastPathComponent })
        XCTAssertThrowsError(try NoteDocument.decode(Data(contentsOf: damaged)))
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

        let budgetNote = NoteDocument(title: "预算一致")
        let encoded = try budgetNote.encoded()
        XCTAssertThrowsError(try budgetNote.encoded(maximumBytes: encoded.count - 1))
        XCTAssertThrowsError(try NoteDocument.decode(encoded, maximumBytes: encoded.count - 1))
    }

    func testSameContentSaveRepairsMissingOrCorruptCanonicalBeforeReportingSuccess() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let library = NoteLibrary(directory: root)
        let note = NoteDocument(title: "Cold reopen")
        try library.save(note)
        let target = root.appendingPathComponent("\(note.id).json")

        try Data("damaged".utf8).write(to: target)
        try library.save(note)
        XCTAssertEqual(NoteLibrary(directory: root).notes.first, note)

        try FileManager.default.removeItem(at: target)
        try library.save(note)
        XCTAssertEqual(NoteLibrary(directory: root).notes.first, note)
    }

    func testInterruptedCanonicalSaveKeepsOldFileAndDraftSurvivesColdRestart() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        var failCanonical = false
        let injected = NSError(domain: "NoteLibraryTests", code: 91)
        let library = NoteLibrary(directory: root) { point, url in
            failCanonical && point == .afterOriginalMoved && !url.path.contains("/.pending-edits/") ? injected : nil
        }
        var original = NoteDocument(title: "Original")
        original.updatedAt = 100
        try library.save(original)
        let target = root.appendingPathComponent("\(original.id).json")
        let originalBytes = try Data(contentsOf: target)

        var changed = original
        changed.title = "Unsaved newest"
        changed.updatedAt = 200
        let revision = library.nextDraftRevision(noteID: changed.id)
        let draftPersisted = try await library.persistRegisteredDraft(changed, revision: revision)
        XCTAssertTrue(draftPersisted)
        failCanonical = true
        XCTAssertThrowsError(try library.save(changed))
        XCTAssertEqual(try Data(contentsOf: target), originalBytes)

        let reopened = NoteLibrary(directory: root)
        XCTAssertEqual(reopened.notes.first, original)
        XCTAssertEqual(reopened.pendingDraft(noteID: changed.id)?.document, changed)
    }

    func testHandledFailureAfterTemporaryWriteCannotBePromotedOnColdRestart() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        var fail = false
        let injected = NSError(domain: "NoteLibraryTests", code: 94)
        let library = NoteLibrary(directory: root) { point, url in
            fail && point == .afterTemporaryWrite && !url.path.contains("/.pending-edits/") ? injected : nil
        }
        var original = NoteDocument(title: "最后成功版本")
        original.updatedAt = 100
        try library.save(original)
        var rejected = original
        rejected.title = "已报告失败的版本"
        rejected.updatedAt = 200
        fail = true

        XCTAssertThrowsError(try library.save(rejected))
        let target = root.appendingPathComponent("\(original.id).json")
        XCTAssertFalse(FileManager.default.fileExists(atPath: target.path + ".tmp"))
        XCTAssertEqual(NoteLibrary(directory: root).notes.first, original)
    }

    func testCanonicalRecoveryPromotionFailurePreservesOnlyValidTemporaryForNextLaunch() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        var note = NoteDocument(title: "仅存临时候选")
        note.updatedAt = 420
        let target = root.appendingPathComponent("\(note.id).json")
        let temporary = URL(fileURLWithPath: target.path + ".tmp")
        let bytes = try note.encoded()
        try bytes.write(to: temporary)
        let failure = NSError(domain: "NoteLibraryTests", code: 92)

        let failedPromotion = NoteLibrary(directory: root) { point, url in
            point == .beforeVerification && url == target ? failure : nil
        }
        XCTAssertEqual(failedPromotion.notes.first, note, "a verified candidate remains usable in memory")
        XCTAssertEqual(try Data(contentsOf: temporary), bytes, "promotion failure must retain the only durable source")
        let recovery = try XCTUnwrap(failedPromotion.recoveryItems.first { $0.filename == temporary.lastPathComponent })
        XCTAssertEqual(try Data(contentsOf: failedPromotion.exportRecoveryURL(for: recovery)), bytes)

        let nextLaunch = NoteLibrary(directory: root)
        XCTAssertEqual(nextLaunch.notes.first, note)
        XCTAssertEqual(try NoteDocument.decode(Data(contentsOf: target)), note)
        XCTAssertFalse(FileManager.default.fileExists(atPath: temporary.path))
    }

    func testDraftRecoveryPromotionFailurePreservesOnlyValidBackupForNextLaunch() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let drafts = root.appendingPathComponent(".pending-edits", isDirectory: true)
        try FileManager.default.createDirectory(at: drafts, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let note = NoteDocument(title: "仅存草稿备份")
        let target = drafts.appendingPathComponent("\(note.id).draft.json")
        let backup = URL(fileURLWithPath: target.path + ".bak")
        let bytes = try PersistedNoteDraft(revision: 9, document: note).encoded()
        try bytes.write(to: backup)
        let failure = NSError(domain: "NoteLibraryTests", code: 93)

        let failedPromotion = NoteLibrary(directory: root) { point, url in
            point == .beforeVerification && url == target ? failure : nil
        }
        XCTAssertEqual(failedPromotion.pendingDraft(noteID: note.id)?.revision, 9)
        XCTAssertEqual(try Data(contentsOf: backup), bytes)
        XCTAssertTrue(failedPromotion.recoveryItems.contains { $0.filename == backup.lastPathComponent })

        let nextLaunch = NoteLibrary(directory: root)
        XCTAssertEqual(nextLaunch.pendingDraft(noteID: note.id)?.revision, 9)
        XCTAssertEqual(try PersistedNoteDraft.decode(Data(contentsOf: target)).document, note)
        XCTAssertFalse(FileManager.default.fileExists(atPath: backup.path))
    }

    func testCorruptFileRemainsVisibleAndExportsOriginalBytes() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let damaged = Data(#"{"schemaVersion":8,"id":"broken","strokes":[],"textFlows":["#.utf8)
        let target = root.appendingPathComponent("broken.json")
        try damaged.write(to: target)

        let library = NoteLibrary(directory: root)
        XCTAssertTrue(library.notes.isEmpty)
        let recovery = try XCTUnwrap(library.recoveryItems.first)
        XCTAssertEqual(try Data(contentsOf: target), damaged, "failed loading must not rewrite the original")
        XCTAssertEqual(try Data(contentsOf: library.exportRecoveryURL(for: recovery)), damaged)
    }

    func testDraftRevisionsSurviveRestartAndOldCallbacksCannotReplaceOrClearNewerDraft() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let first = NoteLibrary(directory: root)
        var note = NoteDocument(title: "Revision 7")
        for revision in 1...7 { first.registerDraftRevision(noteID: note.id, revision: revision) }
        let seventhPersisted = try await first.persistRegisteredDraft(note, revision: 7)
        XCTAssertTrue(seventhPersisted)

        let reopened = NoteLibrary(directory: root)
        XCTAssertEqual(reopened.pendingDraft(noteID: note.id)?.revision, 7)
        let next = reopened.nextDraftRevision(noteID: note.id)
        XCTAssertEqual(next, 8)
        let stale = note
        note.title = "Revision 8"
        note.updatedAt += 1
        let stalePersisted = try await reopened.persistRegisteredDraft(stale, revision: 7)
        XCTAssertFalse(stalePersisted)
        let newestPersisted = try await reopened.persistRegisteredDraft(note, revision: next)
        XCTAssertTrue(newestPersisted)
        try await reopened.markCanonicalSaved(noteID: note.id, revision: 7)
        XCTAssertEqual(reopened.pendingDraft(noteID: note.id)?.revision, 8)
        XCTAssertEqual(NoteLibrary(directory: root).pendingDraft(noteID: note.id)?.document.title, "Revision 8")
    }

    func testDeletingNoteInvalidatesInFlightDraftAndCannotResurrectIt() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let library = NoteLibrary(directory: root)
        let note = NoteDocument(title: "Delete")
        try library.save(note)
        let revision = library.nextDraftRevision(noteID: note.id)
        let writer = Task { try await library.persistRegisteredDraft(note, revision: revision) }
        try library.delete(note)
        _ = try? await writer.value
        let reopened = NoteLibrary(directory: root)
        XCTAssertTrue(reopened.notes.isEmpty)
        XCTAssertNil(reopened.pendingDraft(noteID: note.id))
    }

    func testCancelledCaptureReleasesLeaseAndPreservesPersistedEditorDraftForRetry() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("capture-draft-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: root) }
        let library = NoteLibrary(directory: root)
        var canonical = NoteDocument(title: "Original canonical note")
        try library.save(canonical)

        let firstRevision = library.nextDraftRevision(noteID: canonical.id)
        var firstDraft = canonical; firstDraft.title = "Persisted pending edit"; firstDraft.updatedAt += 1
        let firstSaved = try await library.persistRegisteredDraft(firstDraft, revision: firstRevision)
        XCTAssertTrue(firstSaved)
        let secondRevision = library.nextDraftRevision(noteID: canonical.id)
        var secondDraft = firstDraft; secondDraft.title = "Edit attempted during capture"; secondDraft.updatedAt += 1

        do {
            try await NoteGroupCatalogFence.withCapture { () async throws -> Void in
                do {
                    _ = try await library.persistRegisteredDraft(secondDraft, revision: secondRevision)
                    XCTFail("A draft writer must reject while catalog capture holds its lease")
                } catch {
                    XCTAssertEqual(error as? NoteGroupCatalogFenceError, .snapshotInProgress)
                }
                throw CancellationError()
            }
            XCTFail("The capture cancellation should propagate")
        } catch is CancellationError { }

        let coldDraft = try XCTUnwrap(NoteLibrary(directory: root).pendingDraft(noteID: canonical.id))
        XCTAssertEqual(coldDraft.revision, firstRevision)
        XCTAssertEqual(coldDraft.document.title, "Persisted pending edit",
                       "The rejected newer write must not overwrite or discard the cold-readable draft")
        let secondSaved = try await library.persistRegisteredDraft(secondDraft, revision: secondRevision)
        XCTAssertTrue(secondSaved, "Cancellation must release the capture lease so the user can retry")
        let retried = try XCTUnwrap(NoteLibrary(directory: root).pendingDraft(noteID: canonical.id))
        XCTAssertEqual(retried.revision, secondRevision)
        XCTAssertEqual(retried.document.title, "Edit attempted during capture")
        canonical.title = "Original canonical note"
        XCTAssertEqual(NoteLibrary(directory: root).notes.first?.title, canonical.title,
                       "Draft retry must not silently commit over the canonical note")
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

@MainActor
extension NoteDocumentTests {
    func testContentOutlineGroupsLongFlowAtAnchorAndPreservesLaTeXAndAssetLimits() {
        var note = NoteDocument(title: "大纲", pageHeight: 100, pageGap: 20, pageCount: 3, pdfPageCount: 1)
        let source = "x ``` + y"
        note.textFlows = [NoteTextFlow(id: "long-flow", format: "latex", source: source,
            anchorPageIndex: 0, anchorYInPage: 80)]
        note.images = [NoteImage(id: "image-1", png: "must-not-export", page: 1)]
        note.strokes = [InkStroke(id: "ink", points: [InkPoint(x: 10, y: 50), InkPoint(x: 10, y: 250)])]

        let snapshot = ContentOutlineSnapshot(document: note)
        XCTAssertEqual(snapshot.pages[0].textFlows.map(\.id), ["long-flow"], "a long flow appears once at its start page")
        XCTAssertTrue(snapshot.pages[0].hasPDFBackground)
        XCTAssertEqual(snapshot.pages[1].imageIDs, ["image-1"])
        XCTAssertTrue(snapshot.pages[0].hasHandwriting)
        XCTAssertTrue(snapshot.pages[1].hasHandwriting)
        XCTAssertTrue(snapshot.pages[2].hasHandwriting)
        XCTAssertTrue(snapshot.markdown.contains("文字对象按起始页归组；长对象内容可能延伸到后续页面。"))
        XCTAssertTrue(snapshot.markdown.contains("````latex\n\(source)\n````"))
        XCTAssertFalse(snapshot.markdown.contains("must-not-export"), "image bytes and invented image descriptions are excluded")
        XCTAssertTrue(snapshot.markdown.contains("图片（未添加说明；图片文件未包含在此 Markdown 中）"))
        XCTAssertTrue(snapshot.markdown.contains("手写笔迹尚未识别"))
    }

    func testContentOutlineDoesNotMarkPageGapsOrBridgeInvalidStrokePoints() {
        let gapOnly = InkStroke(id: "gap", points: [InkPoint(x: 10, y: 104), InkPoint(x: 10, y: 116)])
        let invalidBridge = InkStroke(id: "invalid", points: [InkPoint(x: 10, y: 20),
            InkPoint(x: .nan, y: 110), InkPoint(x: 10, y: 140)])
        let outside = InkStroke(id: "outside", points: [InkPoint(x: 1e308, y: 1e308), InkPoint(x: 1e308, y: 1e308)])
        let pages = ContentOutlineSnapshot.handwritingPages(strokes: [gapOnly, invalidBridge, outside], pageCount: 3,
            pageWidth: 100, pageHeight: 100, pageGap: 20)
        XCTAssertTrue(pages.isEmpty, "gap-only ink and segments with non-finite endpoints do not create page hits")
    }
}
