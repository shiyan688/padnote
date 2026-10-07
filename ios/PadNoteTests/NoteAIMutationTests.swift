import XCTest
import SwiftUI
import UIKit
@testable import PadNote

@MainActor
final class NoteAIMutationTests: XCTestCase {
    @MainActor
    private final class DocumentBox {
        var value: NoteDocument
        init(_ value: NoteDocument) { self.value = value }
    }

    private func flow(_ id: String, _ source: String, page: Int = 0,
                      x: Double = 40, y: Double = 80, width: Double = 320) -> NoteTextFlow {
        NoteTextFlow(id: id, format: "markdown", source: source, fontSizeSp: 16,
                     lineHeight: 1.35, width: width, anchorPageIndex: page,
                     anchorXInPage: x, anchorYInPage: y)
    }

    func testNoOpAndPresentationOnlyChangesDoNotCreateMutation() throws {
        var original = NoteDocument(id: "semantic", title: "语义")
        original.textFlows = [flow("existing", "正文")]
        var proposed = original
        proposed.updatedAt += 100
        proposed.viewportZoom = 2
        proposed.viewportCenterX = 100
        proposed.pageCount = 2

        XCTAssertNil(try NoteAIMutation(original: original, proposed: proposed))
        XCTAssertTrue(NoteAIMutation.sourceCompatible(original, proposed))
        proposed.pageCount = 1
        proposed.strokes.append(InkStroke(id: "later", points: [InkPoint(x: 2, y: 2)]))
        XCTAssertFalse(NoteAIMutation.sourceCompatible(original, proposed))
    }

    func testGroupedApplyUndoAndReapplyKeepStableIDsAndLongFormula() throws {
        let existing = flow("existing", "原正文")
        var original = NoteDocument(id: "group", title: "组", pageCount: 1, textFlows: [existing])
        var proposed = original
        proposed.textFlows[0].source = "修改后的正文"
        proposed.textFlows.append(flow("formula", #"\[\sum_{i=1}^{120} i^2 = \frac{120\cdot121\cdot241}{6}\]"#, page: 1))
        proposed.textFlows.append(flow("diagram", "```mermaid\nflowchart LR\nA --> B\n```", page: 2))
        proposed.pageCount = 3

        let mutation = try XCTUnwrap(try NoteAIMutation(id: UUID(), original: original, proposed: proposed))
        let applied = try mutation.applying(to: original, recordedApplied: false)
        XCTAssertEqual(mutation.state(in: applied, recordedApplied: true), .applied)
        XCTAssertEqual(Set(applied.textFlows.map(\.id)), ["existing", "formula", "diagram"])

        let undone = try mutation.undoing(in: applied, recordedApplied: true)
        XCTAssertEqual(undone.textFlows, [existing])
        XCTAssertEqual(undone.pageCount, 1)
        XCTAssertEqual(mutation.state(in: undone, recordedApplied: false), .undone)

        let reapplied = try mutation.applying(to: undone, recordedApplied: false)
        XCTAssertEqual(reapplied.textFlows, applied.textFlows)
        XCTAssertEqual(reapplied.textFlows.first(where: { $0.id == "formula" })?.source,
                       proposed.textFlows.first(where: { $0.id == "formula" })?.source)
    }

    func testAnyEditedTargetMakesGroupedUndoAtomicConflict() throws {
        let original = NoteDocument(id: "conflict", title: "冲突")
        var proposed = original
        proposed.textFlows = [flow("one", "一"), flow("two", "二", y: 180)]
        let mutation = try XCTUnwrap(try NoteAIMutation(original: original, proposed: proposed))
        var current = try mutation.applying(to: original, recordedApplied: false)
        current.textFlows[1].source = "用户继续编辑"
        let unchanged = current

        XCTAssertEqual(mutation.state(in: current, recordedApplied: true), .conflict)
        XCTAssertThrowsError(try mutation.undoing(in: current, recordedApplied: true))
        XCTAssertEqual(current, unchanged)
    }

    func testRequestDependencyRequiresUndoingLaterEditFirst() throws {
        let original = NoteDocument(id: "dependency", title: "依赖")
        var afterA = original
        afterA.textFlows = [flow("x", "A 创建")]
        let mutationA = try XCTUnwrap(try NoteAIMutation(original: original, proposed: afterA))
        let appliedA = try mutationA.applying(to: original, recordedApplied: false)

        var afterB = appliedA
        afterB.textFlows[0].source = "B 修改"
        let mutationB = try XCTUnwrap(try NoteAIMutation(original: appliedA, proposed: afterB))
        let appliedB = try mutationB.applying(to: appliedA, recordedApplied: false)

        XCTAssertThrowsError(try mutationA.undoing(in: appliedB, recordedApplied: true))
        let restoredA = try mutationB.undoing(in: appliedB, recordedApplied: true)
        XCTAssertEqual(restoredA, appliedA)
        XCTAssertEqual(try mutationA.undoing(in: restoredA, recordedApplied: true).textFlows, [])
    }

    func testManualRemovalAndExactBeforeRestorationAreReapplicableButDeletedUpdateConflicts() throws {
        let insertedOriginal = NoteDocument(id: "manual-insert", title: "创建")
        var insertedAfter = insertedOriginal
        insertedAfter.textFlows = [flow("created", "AI 创建")]
        let insertion = try XCTUnwrap(try NoteAIMutation(original: insertedOriginal, proposed: insertedAfter))
        XCTAssertEqual(insertion.state(in: insertedOriginal, recordedApplied: true), .undone)
        XCTAssertEqual(insertion.targetPages(in: insertedOriginal), [], "不存在的 AI 新建对象不能伪装成可定位结果")
        XCTAssertEqual(try insertion.applying(to: insertedOriginal, recordedApplied: true).textFlows.map(\.id), ["created"])

        var updateOriginal = NoteDocument(id: "manual-update", title: "修改")
        updateOriginal.textFlows = [flow("existing", "原样")]
        var updateAfter = updateOriginal
        updateAfter.textFlows[0].source = "AI 样式"
        let update = try XCTUnwrap(try NoteAIMutation(original: updateOriginal, proposed: updateAfter))
        XCTAssertEqual(update.state(in: updateOriginal, recordedApplied: true), .undone,
                       "用户精确恢复 before 时，卡片应跟随实际文档允许显式重应用")
        XCTAssertEqual(update.targetPages(in: updateOriginal), [0], "既有目标仍可定位为原位置")
        var deleted = updateAfter
        deleted.textFlows = []
        XCTAssertEqual(update.state(in: deleted, recordedApplied: true), .conflict)
        XCTAssertThrowsError(try update.applying(to: deleted, recordedApplied: true))
    }

    func testReapplyRejectsNewContentInTargetRegionButAllowsOtherPageInk() throws {
        let original = NoteDocument(id: "occupancy", title: "占位", pageCount: 2)
        var proposed = original
        proposed.textFlows = [flow("created", "AI 内容", page: 0, x: 40, y: 80, width: 300)]
        let mutation = try XCTUnwrap(try NoteAIMutation(original: original, proposed: proposed))

        var occupied = original
        occupied.strokes = [InkStroke(id: "local", baseWidth: 5,
            points: [InkPoint(x: 60, y: 100), InkPoint(x: 180, y: 100)])]
        XCTAssertThrowsError(try mutation.applying(to: occupied, recordedApplied: false))

        var otherPage = original
        let pageTwoY = original.pageHeight + original.pageGap + 100
        otherPage.strokes = [InkStroke(id: "other-page", points: [InkPoint(x: 60, y: pageTwoY), InkPoint(x: 180, y: pageTwoY)])]
        let reapplied = try mutation.applying(to: otherPage, recordedApplied: false)
        XCTAssertEqual(reapplied.strokes, otherPage.strokes)
        XCTAssertEqual(reapplied.textFlows.map(\.id), ["created"])
    }

    func testUndoKeepsUserContentOnAIAddedTailPage() throws {
        let original = NoteDocument(id: "tail", title: "尾页")
        var proposed = original
        proposed.pageCount = 3
        proposed.textFlows = [flow("tail-ai", "尾页 AI", page: 2)]
        let mutation = try XCTUnwrap(try NoteAIMutation(original: original, proposed: proposed))
        var current = try mutation.applying(to: original, recordedApplied: false)
        let y = 2 * (current.pageHeight + current.pageGap) + 300
        current.strokes.append(InkStroke(id: "user-tail", points: [InkPoint(x: 30, y: y), InkPoint(x: 80, y: y)]))

        let undone = try mutation.undoing(in: current, recordedApplied: true)
        XCTAssertTrue(undone.textFlows.isEmpty)
        XCTAssertEqual(undone.strokes.map(\.id), ["user-tail"])
        XCTAssertEqual(undone.pageCount, 3)
    }

    func testCanvasHistoryKeepsLaterStrokeAndSynchronizesCardAcrossUndoRedo() throws {
        let original = NoteDocument(id: "canvas", title: "画布")
        var proposed = original
        proposed.textFlows = [flow("ai", "AI")]
        let mutation = try XCTUnwrap(try NoteAIMutation(original: original, proposed: proposed))
        let box = DocumentBox(original)
        let controller = CanvasController(defaults: UserDefaults(suiteName: "NoteAIMutationTests.\(UUID().uuidString)")!)
        let canvas = NoteCanvas(document: Binding(get: { box.value }, set: { box.value = $0 }), controller: controller)
        let coordinator = canvas.makeCoordinator()
        let scrollView = CanvasScrollView(frame: CGRect(x: 0, y: 0, width: 768, height: 900))
        coordinator.install(on: scrollView, document: box.value, pdfURL: nil)

        let applied = try mutation.applying(to: box.value, recordedApplied: false)
        controller.performAIEdit(id: mutation.id, applied: true) { note in
            note.textFlows = applied.textFlows
            note.pageCount = applied.pageCount
        }
        controller.performEdit { note in
            note.strokes.append(InkStroke(id: "later-handwriting", points: [InkPoint(x: 400, y: 500)]))
        }
        let cardUndone = try mutation.undoing(in: box.value,
            recordedApplied: controller.aiApplicationState(for: mutation.id))
        controller.performAIEdit(id: mutation.id, applied: false) { note in
            note.textFlows = cardUndone.textFlows
            note.pageCount = cardUndone.pageCount
        }
        XCTAssertEqual(box.value.strokes.map(\.id), ["later-handwriting"])
        XCTAssertEqual(mutation.state(in: box.value,
            recordedApplied: controller.aiApplicationState(for: mutation.id)), .undone)

        controller.undo()
        XCTAssertEqual(box.value.textFlows.map(\.id), ["ai"])
        XCTAssertEqual(box.value.strokes.map(\.id), ["later-handwriting"])
        XCTAssertEqual(controller.aiApplicationState(for: mutation.id), true)
        XCTAssertEqual(mutation.state(in: box.value, recordedApplied: true), .applied)

        controller.redo()
        XCTAssertTrue(box.value.textFlows.isEmpty)
        XCTAssertEqual(box.value.strokes.map(\.id), ["later-handwriting"])
        XCTAssertEqual(controller.aiApplicationState(for: mutation.id), false)
        XCTAssertEqual(mutation.state(in: box.value, recordedApplied: false), .undone)
    }

    func testMovedPageTopologyBlocksStaleReapply() throws {
        var original = NoteDocument(id: "topology", title: "页序", pageCount: 2)
        let markerPNG = UIGraphicsImageRenderer(size: CGSize(width: 2, height: 2)).image { context in
            UIColor.black.setFill(); context.fill(CGRect(x: 0, y: 0, width: 2, height: 2))
        }.pngData()!.base64EncodedString()
        original.images = [NoteImage(id: "marker", png: markerPNG, page: 0, x: 600, y: 800, width: 20, height: 20)]
        var proposed = original
        proposed.textFlows = [flow("ai", "AI", page: 0)]
        let mutation = try XCTUnwrap(try NoteAIMutation(original: original, proposed: proposed))
        var moved = original
        moved.images[0].page = 1
        XCTAssertThrowsError(try mutation.applying(to: moved, recordedApplied: false))
        var resized = original
        resized.pageGap += 10
        XCTAssertThrowsError(try mutation.applying(to: resized, recordedApplied: false))
    }

    func testDeleteAndReaddEmptyPageBlocksColdReceiptAndPendingSource() throws {
        let original = NoteDocument(id: "empty-page-identity", title: "空页", pageCount: 2)
        var proposed = original
        proposed.textFlows = [flow("ai-empty-page", "AI", page: 1)]
        let mutation = try XCTUnwrap(try NoteAIMutation(original: original, proposed: proposed))
        var replaced = try mutation.undoing(in: proposed, recordedApplied: true)
        XCTAssertTrue(NotePageOperations.delete(page: 1, in: &replaced))
        XCTAssertTrue(NotePageOperations.appendBlankPage(in: &replaced))
        XCTAssertEqual(replaced.pageCount, original.pageCount)
        XCTAssertNotEqual(replaced.pageTopologyRevision, original.pageTopologyRevision)
        XCTAssertFalse(NoteAIMutation.sourceCompatible(original, replaced))
        XCTAssertThrowsError(try mutation.applying(to: replaced, recordedApplied: false))

        let encoded = try JSONEncoder().encode(mutation)
        let cold = try JSONDecoder().decode(NoteAIMutation.self, from: encoded)
        XCTAssertThrowsError(try cold.applying(to: replaced, recordedApplied: false))
    }
}
