import XCTest

final class PadNoteUITests: XCTestCase {
    private var app: XCUIApplication!
    private let noteTitle = "UI Note " + String(UUID().uuidString.prefix(6))

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launchArguments = ["--uitesting"]
        app.launch()
    }

    override func tearDownWithError() throws {
        app?.terminate()
        app = nil
    }

    func testCreateAddPageReturnReopenAndPersist() throws {
        let newNote = app.buttons["newNoteButton"]
        XCTAssertTrue(newNote.waitForExistence(timeout: 5))
        newNote.tap()
        let title = app.textFields["noteTitleField"]
        XCTAssertTrue(title.waitForExistence(timeout: 5))
        title.tap()
        title.typeText(noteTitle)
        app.buttons["createNoteConfirm"].tap()
        XCTAssertTrue(app.descendants(matching: .any).matching(identifier: "noteCanvas").firstMatch.waitForExistence(timeout: 5))
        let pencilOnly = app.descendants(matching: .any).matching(identifier: "pencilOnlyToggle").firstMatch
        if pencilOnly.value as? String == "1" { pencilOnly.tap() }
        let canvas = app.descendants(matching: .any).matching(identifier: "noteCanvas").firstMatch
        let start = canvas.coordinate(withNormalizedOffset: CGVector(dx: 0.2, dy: 0.2))
        let end = canvas.coordinate(withNormalizedOffset: CGVector(dx: 0.7, dy: 0.4))
        start.press(forDuration: 0.05, thenDragTo: end)
        XCTAssertTrue(app.buttons["undoButton"].isEnabled)
        app.buttons["insertTextButton"].tap()
        let canvasForText = app.descendants(matching: .any).matching(identifier: "noteCanvas").firstMatch
        canvasForText.coordinate(withNormalizedOffset: CGVector(dx: 0.35, dy: 0.3)).tap()
        let source = app.textViews["inlineTextSource"]
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.lifetime = .keepAlways
        add(attachment)
        XCTAssertTrue(source.waitForExistence(timeout: 5))
        source.tap()
        source.typeText("E = mc^2\n\nPersistence check: a + b = c")
        XCTAssertTrue(app.buttons["finishInlineText"].waitForExistence(timeout: 3))
        app.buttons["finishInlineText"].tap()
        XCTAssertFalse(source.exists)
        app.buttons["addPageButton"].tap()
        app.buttons["backToShelf"].tap()
        XCTAssertTrue(app.buttons["note-\(noteTitle)"].waitForExistence(timeout: 5))
        app.buttons["note-\(noteTitle)"].tap()
        XCTAssertTrue(app.descendants(matching: .any).matching(identifier: "noteCanvas").firstMatch.waitForExistence(timeout: 5))
        app.terminate()
        app.launch()
        XCTAssertTrue(app.buttons["note-\(noteTitle)"].waitForExistence(timeout: 5))
    }

    func testPageManagerContextMenuCopyDeleteAndUndo() throws {
        let newNote = app.buttons["newNoteButton"]
        XCTAssertTrue(newNote.waitForExistence(timeout: 5))
        newNote.tap()
        let title = app.textFields["noteTitleField"]
        XCTAssertTrue(title.waitForExistence(timeout: 5))
        title.tap(); title.typeText("Page UI " + String(UUID().uuidString.prefix(5)))
        app.buttons["createNoteConfirm"].tap()
        XCTAssertTrue(app.buttons["pageManagerButton"].waitForExistence(timeout: 5))
        app.buttons["addPageButton"].tap()
        app.buttons["pageManagerButton"].tap()
        let secondPage = app.buttons["第 2 页"]
        XCTAssertTrue(secondPage.waitForExistence(timeout: 5))
        secondPage.press(forDuration: 0.8)
        XCTAssertTrue(app.buttons["删除页面"].waitForExistence(timeout: 3))
        app.buttons["删除页面"].tap()
        app.buttons["完成"].tap()
        XCTAssertTrue(app.buttons["undoButton"].isEnabled)
        app.buttons["undoButton"].tap()
        app.buttons["pageManagerButton"].tap()
        XCTAssertTrue(app.buttons["第 2 页"].waitForExistence(timeout: 5))
    }

    func testFloatingAIKeepsDraftWhenMinimized() throws {
        XCTAssertTrue(app.buttons["newNoteButton"].waitForExistence(timeout: 5))
        app.buttons["newNoteButton"].tap()
        let title = app.textFields["noteTitleField"]
        XCTAssertTrue(title.waitForExistence(timeout: 5))
        title.tap(); title.typeText("AI UI " + String(UUID().uuidString.prefix(5)))
        app.buttons["createNoteConfirm"].tap()
        XCTAssertTrue(app.buttons["AI 助手"].waitForExistence(timeout: 5))
        app.buttons["AI 助手"].tap()
        let question = app.textViews["给 AI 的问题"]
        XCTAssertTrue(question.waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["backToShelf"].exists, "Floating card must preserve the editor navigation")
        question.tap(); question.typeText("Draft remains local")
        app.buttons["最小化 AI"].tap()
        XCTAssertTrue(app.buttons["展开 AI"].waitForExistence(timeout: 3))
        app.buttons["展开 AI"].tap()
        XCTAssertEqual(question.value as? String, "Draft remains local")
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = "Floating AI draft and paper"
        attachment.lifetime = .keepAlways
        add(attachment)
        app.buttons["关闭 AI"].tap()
        XCTAssertFalse(app.buttons["最小化 AI"].exists)
        XCTAssertTrue(app.buttons["backToShelf"].exists)
    }

    func testRenderFailureCanBeEditedWithoutDuplicatingFlowAndPersistsAfterReopen() throws {
        let titleValue = "Render UI " + String(UUID().uuidString.prefix(6))
        XCTAssertTrue(app.buttons["newNoteButton"].waitForExistence(timeout: 5))
        app.buttons["newNoteButton"].tap()
        let title = app.textFields["noteTitleField"]
        XCTAssertTrue(title.waitForExistence(timeout: 5))
        title.tap(); title.typeText(titleValue)
        app.buttons["createNoteConfirm"].tap()
        let canvas = app.descendants(matching: .any).matching(identifier: "noteCanvas").firstMatch
        XCTAssertTrue(canvas.waitForExistence(timeout: 5))

        app.buttons["insertTextButton"].tap()
        canvas.coordinate(withNormalizedOffset: CGVector(dx: 0.35, dy: 0.25)).tap()
        let inlineSource = app.textViews["inlineTextSource"]
        XCTAssertTrue(inlineSource.waitForExistence(timeout: 5))
        inlineSource.tap(); inlineSource.typeText("\\frac{")
        app.buttons["finishInlineText"].tap()

        let failureEntry = app.buttons["renderFailureEntry"]
        XCTAssertTrue(failureEntry.waitForExistence(timeout: 15), "invalid LaTeX must expose the paper recovery entry")
        let failureShot = XCTAttachment(screenshot: app.screenshot())
        failureShot.name = "Actual paper render failure entry"
        failureShot.lifetime = .keepAlways
        add(failureShot)
        failureEntry.tap()

        let rows = app.buttons.matching(identifier: "textFlowEditButton")
        XCTAssertTrue(rows.firstMatch.waitForExistence(timeout: 5))
        XCTAssertEqual(rows.count, 1)
        let edit = app.buttons["renderFailureEditSource"]
        XCTAssertTrue(edit.waitForExistence(timeout: 5))
        edit.tap()
        let editor = app.textViews["textSourceEditor"]
        XCTAssertTrue(editor.waitForExistence(timeout: 5))
        XCTAssertEqual(editor.value as? String, "\\frac{")
        replaceText(in: editor, with: "x^2+y^2=z^2")
        app.buttons["saveTextButton"].tap()

        let gone = expectation(for: NSPredicate(format: "exists == false"), evaluatedWith: failureEntry)
        wait(for: [gone], timeout: 15)
        openTextManager()
        XCTAssertTrue(rows.firstMatch.waitForExistence(timeout: 5))
        XCTAssertEqual(rows.count, 1, "repair must update the existing flow rather than insert a second object")
        let repairedSource = app.descendants(matching: .any).matching(identifier: "textFlowSource").firstMatch
        XCTAssertTrue(repairedSource.waitForExistence(timeout: 5))
        XCTAssertEqual(repairedSource.label, "x^2+y^2=z^2")
        let repairedShot = XCTAttachment(screenshot: app.screenshot())
        repairedShot.name = "Repaired source with one text flow"
        repairedShot.lifetime = .keepAlways
        add(repairedShot)
        app.navigationBars["页面文字"].buttons["完成"].tap()

        let saveStatus = app.descendants(matching: .any).matching(identifier: "saveStatus").firstMatch
        let saved = expectation(for: NSPredicate(format: "label BEGINSWITH '已保存'"), evaluatedWith: saveStatus)
        wait(for: [saved], timeout: 10)
        app.buttons["backToShelf"].tap()
        let note = app.buttons["note-\(titleValue)"]
        XCTAssertTrue(note.waitForExistence(timeout: 5))
        note.tap()
        XCTAssertTrue(canvas.waitForExistence(timeout: 5))
        XCTAssertFalse(failureEntry.waitForExistence(timeout: 2))
        openTextManager()
        XCTAssertTrue(rows.firstMatch.waitForExistence(timeout: 5))
        XCTAssertEqual(rows.count, 1)
        XCTAssertEqual(app.descendants(matching: .any).matching(identifier: "textFlowSource").firstMatch.label,
                       "x^2+y^2=z^2", "the repaired source must survive closing and reopening the note")
        let reopenShot = XCTAttachment(screenshot: app.screenshot())
        reopenShot.name = "Reopened note keeps repaired render source"
        reopenShot.lifetime = .keepAlways
        add(reopenShot)
    }

    private func openTextManager() {
        let more = app.buttons["moreActionsButton"]
        XCTAssertTrue(more.waitForExistence(timeout: 5))
        more.tap()
        let manage = app.buttons["管理文字"]
        XCTAssertTrue(manage.waitForExistence(timeout: 3))
        manage.tap()
    }

    private func replaceText(in element: XCUIElement, with value: String) {
        for _ in 0..<3 {
            if element.value as? String == value { return }
            element.tap()
            element.typeKey("a", modifierFlags: .command)
            element.typeText(value)
        }
        XCTAssertEqual(element.value as? String, value, "keyboard replacement must be confirmed before saving")
    }
}
