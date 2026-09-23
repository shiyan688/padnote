import XCTest
@testable import PadNote

@MainActor
final class CanvasSettingsTests: XCTestCase {
    func testToolSettingsPersistWithoutHistoryOrToolSelection() {
        let defaults = UserDefaults(suiteName: "CanvasSettingsTests.\(UUID().uuidString)")!
        let first = CanvasController(defaults: defaults)
        first.strokeWidth = 3
        first.tool = .highlighter
        first.strokeWidth = 20
        first.inkColor = "#FF285EA8"
        first.highlighterColor = "#69FFCC33"
        first.pencilOnly = false

        let second = CanvasController(defaults: defaults)
        XCTAssertEqual(second.penWidth, 3, accuracy: 0.001)
        XCTAssertEqual(second.highlighterWidth, 20, accuracy: 0.001)
        XCTAssertEqual(second.inkColor, "#FF285EA8")
        XCTAssertEqual(second.highlighterColor, "#69FFCC33")
        XCTAssertFalse(second.pencilOnly)
        XCTAssertEqual(second.tool, .pen, "Android keeps selected tool as session state")
    }

    func testInvalidPersistedSettingsUseSafeRangesAndColors() {
        let defaults = UserDefaults(suiteName: "CanvasSettingsTests.\(UUID().uuidString)")!
        defaults.set(999, forKey: "penWidth")
        defaults.set(-2, forKey: "highlighterWidth")
        defaults.set(0, forKey: "eraserWidth")
        defaults.set("blue", forKey: "inkColor")
        defaults.set("#123", forKey: "highlighterColor")
        let controller = CanvasController(defaults: defaults)
        XCTAssertEqual(controller.penWidth, 8, accuracy: 0.001)
        XCTAssertEqual(controller.highlighterWidth, 12, accuracy: 0.001)
        XCTAssertEqual(controller.eraserWidth, 16, accuracy: 0.001)
        XCTAssertEqual(controller.inkColor, "#FF1F2933")
        XCTAssertEqual(controller.highlighterColor, "#69FFCC33")
    }
}
