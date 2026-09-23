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
}
