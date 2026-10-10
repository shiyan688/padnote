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
        try store.remove(noteID: "note-1"); XCTAssertNil(store.load(noteID: "note-1"))
    }

    func testLegacyCoverWritersRejectDuringCatalogCapture() throws {
        let store = NoteCoverStore(directory: FileManager.default.temporaryDirectory.appendingPathComponent("cover-fence-\(UUID().uuidString)"))
        let image = UIGraphicsImageRenderer(size: CGSize(width: 16, height: 16)).image { UIColor.systemGreen.setFill(); $0.fill(CGRect(x: 0, y: 0, width: 16, height: 16)) }
        try store.assign(noteID: "note", image: image)
        XCTAssertThrowsError(try NoteGroupCatalogFence.withCapture {
            try store.assign(noteID: "note", image: image)
        }) { XCTAssertEqual($0 as? NoteGroupCatalogFenceError, .snapshotInProgress) }
        XCTAssertThrowsError(try NoteGroupCatalogFence.withCapture {
            try store.remove(noteID: "note")
        }) { XCTAssertEqual($0 as? NoteGroupCatalogFenceError, .snapshotInProgress) }
        XCTAssertNotNil(store.load(noteID: "note"), "A rejected capture-time writer must leave the prior cover intact")
    }

    func testRejectsOversizedPixelImage() {
        let store = NoteCoverStore(directory: FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString))
        let image = UIGraphicsImageRenderer(size: CGSize(width: 4000, height: 4000)).image { _ in }
        XCTAssertThrowsError(try store.assign(noteID: "large", image: image))
    }
}
