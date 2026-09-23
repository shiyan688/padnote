import XCTest
import UIKit
@testable import PadNote

final class NoteCoverTests: XCTestCase {
    func testAssignLoadReplaceRemoveAndSafeID() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("cover-test-\(UUID().uuidString)")
        let store = NoteCoverStore(directory: dir)
        let image = UIGraphicsImageRenderer(size: CGSize(width: 100, height: 60)).image { UIColor.systemBlue.setFill(); $0.fill(CGRect(x: 0, y: 0, width: 100, height: 60)) }
        try store.assign(noteID: "note-1", image: image)
        XCTAssertNotNil(store.load(noteID: "note-1")); XCTAssertNil(store.load(noteID: "../escape"))
        try store.assign(noteID: "note-1", image: UIImage(data: image.pngData()!)!)
        store.remove(noteID: "note-1"); XCTAssertNil(store.load(noteID: "note-1"))
    }

    func testRejectsOversizedPixelImage() {
        let store = NoteCoverStore(directory: FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString))
        let image = UIGraphicsImageRenderer(size: CGSize(width: 4000, height: 4000)).image { _ in }
        XCTAssertThrowsError(try store.assign(noteID: "large", image: image))
    }
}
