import XCTest
import CoreGraphics
@testable import PadNote

final class NoteToolTests: XCTestCase {
    private func engine(note: NoteDocument = NoteDocument(title: "工具测试"), selection: CGRect? = nil) -> NoteToolEngine {
        NoteToolEngine(note: note, currentPage: 0, selectionBounds: selection)
    }

    func testReadOnlyDefaultAndCreatePermission() throws {
        let e = engine()
        let denied = e.invoke(name: "write_text", callID: "a", argumentsJSON: #"{"content":"hello","placement":{"page":1}}"#)
        XCTAssertFalse(denied.mutated)
        XCTAssertTrue(denied.jsonString.contains("更高权限"))
        let allowed = e.invoke(name: "write_text", callID: "b", argumentsJSON: #"{"content":"hello","placement":{"page":1}}"#, authorization: .createInFreeSpace)
        XCTAssertTrue(allowed.mutated)
        XCTAssertEqual(allowed.proposedNote?.textFlows.count, 1)
    }

    func testExistingFlowNeedsExplicitModifyPermission() throws {
        var note = NoteDocument(title: "已有")
        note.textFlows = [NoteTextFlow(source: "old", anchorPageIndex: 0, anchorXInPage: 16, anchorYInPage: 16)]
        let e = engine(note: note)
        let id = note.textFlows[0].id
        let args = "{\"flowId\":\"\(id)\",\"fontSizeSp\":24}"
        XCTAssertFalse(e.invoke(name: "set_text_flow_style", callID: "s", argumentsJSON: args, authorization: .createInFreeSpace).mutated)
        XCTAssertTrue(e.invoke(name: "set_text_flow_style", callID: "s2", argumentsJSON: args, authorization: .modifyExisting).mutated)
    }

    func testRawCoordinatesRejectedAndDuplicateCallIsIdempotent() throws {
        let e = engine()
        let raw = e.invoke(name: "write_text", callID: "raw", argumentsJSON: #"{"content":"x","placement":{"page":1,"x":12}}"#, authorization: .createInFreeSpace)
        XCTAssertFalse(raw.mutated)
        let args = #"{"content":"x","placement":{"page":1,"bands":"3"}}"#
        XCTAssertTrue(e.invoke(name: "write_text", callID: "same", argumentsJSON: args, authorization: .createInFreeSpace).mutated)
        let duplicate = e.invoke(name: "write_text", callID: "same", argumentsJSON: args, authorization: .createInFreeSpace)
        XCTAssertFalse(duplicate.mutated)
        XCTAssertEqual(e.proposedNote.textFlows.count, 1)
    }

    func testPlacementAvoidsInkAndReportsPDFBackground() throws {
        var note = NoteDocument(title: "PDF", pageCount: 1, pdfPageCount: 1)
        note.strokes = [InkStroke(points: [InkPoint(x: 16, y: 16), InkPoint(x: 300, y: 80)])]
        let e = engine(note: note, selection: CGRect(x: 16, y: 16, width: 284, height: 64))
        let result = e.invoke(name: "write_text", callID: "pdf", argumentsJSON: #"{"content":"answer","placement":{"relativeTo":"selection","position":"below"}}"#, authorization: .createInFreeSpace)
        XCTAssertTrue(result.mutated)
        XCTAssertTrue(result.jsonString.contains("pdfBackground"))
        XCTAssertEqual(result.proposedNote?.textFlows.first?.anchorPageIndex, 1)
        XCTAssertEqual(result.proposedNote?.pageCount, 2)
    }

    func testVaultIsSnapshotOnlyAndDiagramValidation() throws {
        let vault = [NoteToolVaultEntry(id: "n1", title: "数学", markdown: "## 第 1 页\n积分公式")]
        let e = NoteToolEngine(note: NoteDocument(title: "工具"), vault: vault)
        let search = e.invoke(name: "search_vault", callID: "v", argumentsJSON: #"{"query":"积分"}"#)
        XCTAssertTrue(search.jsonString.contains("数学"))
        let bad = e.invoke(name: "draw_diagram", callID: "d", argumentsJSON: #"{"code":"```flowchart TD A-->B```","placement":{"page":1}}"#, authorization: .createInFreeSpace)
        XCTAssertFalse(bad.mutated)
    }

    func testOpenAIFunctionSchemaAndPageScopedVaultRead() throws {
        let vault = [NoteToolVaultEntry(id: "n1", title: "数学", markdown: "# 数学\n\n## 第 1 页\n第一页\n\n## 第 2 页\n第二页")]
        let e = NoteToolEngine(note: NoteDocument(title: "工具"), vault: vault)
        let schema = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(e.describeToolsJSON().utf8)) as? [[String: Any]])
        XCTAssertEqual(schema.first?["type"] as? String, "function")
        XCTAssertNotNil((schema.first?["function"] as? [String: Any])?["parameters"])
        let result = e.invoke(name: "read_vault_note", callID: "page", argumentsJSON: #"{"id":"n1","page":2}"#)
        XCTAssertTrue(result.jsonString.contains("第二页")); XCTAssertFalse(result.jsonString.contains("第一页"))
    }

    func testFailedPlacementDoesNotMutateAndSecondPageInkIsAvoided() throws {
        var note = NoteDocument(title: "分页", pageCount: 2)
        let y = note.pageHeight + note.pageGap + 20
        note.strokes = [InkStroke(points: [InkPoint(x: 16, y: y), InkPoint(x: 300, y: y + 30)])]
        let e = engine(note: note)
        let failed = e.invoke(name: "write_text", callID: "bad", argumentsJSON: #"{"content":"x","placement":{"page":1,"x":12}}"#, authorization: .createInFreeSpace)
        XCTAssertFalse(failed.mutated); XCTAssertEqual(e.proposedNote.textFlows.count, 0); XCTAssertEqual(e.proposedNote.pageCount, 2)
        let placed = e.invoke(name: "write_text", callID: "good", argumentsJSON: #"{"content":"answer","placement":{"page":2,"slot":"free.largest"}}"#, authorization: .createInFreeSpace)
        XCTAssertTrue(placed.mutated); XCTAssertEqual(placed.proposedNote?.textFlows.first?.anchorPageIndex, 1)
    }
}
