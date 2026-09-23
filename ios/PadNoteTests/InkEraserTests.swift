import XCTest
@testable import PadNote

final class InkEraserTests: XCTestCase {
    private func line(_ points: [InkPoint]) -> InkStroke {
        InkStroke(id: "original", color: "#FF285EA8", baseWidth: 3.5, createdAt: 42,
                  highlighter: false, points: points)
    }

    func testMiddleOfTwoPointLineLeavesTwoInterpolatedFragments() {
        let stroke = line([InkPoint(x: 0, y: 0, pressure: 0.2, timestamp: 10),
                           InkPoint(x: 1000, y: 0, pressure: 0.8, timestamp: 110)])
        let result = InkEraser.erase(stroke: stroke, path: [CGPoint(x: 500, y: 0)], radius: 10)
        XCTAssertEqual(result.count, 2)
        XCTAssertEqual(result[0].id, "original")
        XCTAssertNotEqual(result[1].id, "original")
        XCTAssertEqual(result[0].points.last?.x ?? -1, 490, accuracy: 0.01)
        XCTAssertEqual(result[1].points.first?.x ?? -1, 510, accuracy: 0.01)
    }

    func testContinuousDiagonalSweepCutsDiagonalStroke() {
        let points = stride(from: 0.0, through: 1000.0, by: 250).map { InkPoint(x: $0, y: $0) }
        let stroke = line(points)
        let result = InkEraser.erase(stroke: stroke,
                                     path: [CGPoint(x: 0, y: 1000), CGPoint(x: 1000, y: 0)], radius: 12)
        XCTAssertGreaterThanOrEqual(result.count, 1)
        XCTAssertLessThan(result.reduce(0) { $0 + $1.points.count }, stroke.points.count + 6)
    }

    func testHeadFullAndMissCases() {
        let stroke = line([InkPoint(x: 0, y: 0), InkPoint(x: 100, y: 0)])
        let miss = InkEraser.erase(stroke: stroke, path: [CGPoint(x: 50, y: 100)], radius: 2)
        XCTAssertEqual(miss, [stroke])
        let head = InkEraser.erase(stroke: stroke, path: [CGPoint(x: 0, y: 0)], radius: 8)
        XCTAssertEqual(head.count, 1)
        XCTAssertGreaterThan(head[0].points.first?.x ?? -1, 0)
        let all = InkEraser.erase(stroke: stroke, path: [CGPoint(x: 50, y: 0)], radius: 100)
        XCTAssertTrue(all.isEmpty)
    }

    func testPressureAndTimestampAreInterpolatedAtCut() {
        let stroke = line([InkPoint(x: 0, y: 0, pressure: 0, timestamp: 0),
                           InkPoint(x: 100, y: 0, pressure: 1, timestamp: 100)])
        let result = InkEraser.erase(stroke: stroke, path: [CGPoint(x: 50, y: 0)], radius: 10)
        XCTAssertEqual(result[0].points.last?.pressure ?? -1, 0.4, accuracy: 0.02)
        XCTAssertEqual(result[0].points.last?.timestamp ?? -1, 40, accuracy: 2)
        XCTAssertEqual(result[1].points.first?.pressure ?? -1, 0.6, accuracy: 0.02)
        XCTAssertEqual(result[1].points.first?.timestamp ?? -1, 60, accuracy: 2)
    }

    func testSinglePointStrokeInsideEraserIsRemoved() {
        let stroke = line([InkPoint(x: 40, y: 40, pressure: 0.7, timestamp: 9)])
        let result = InkEraser.erase(stroke: stroke, path: [CGPoint(x: 40, y: 40)], radius: 6)
        XCTAssertTrue(result.isEmpty, "A dot stroke is ink and should be erased by a covering capsule")
    }

    func testParallelSeparatedSweepLeavesStrokeByteEquivalent() {
        let stroke = line([InkPoint(x: 0, y: 100), InkPoint(x: 1000, y: 100)])
        let result = InkEraser.erase(stroke: stroke,
                                     path: [CGPoint(x: 0, y: 120), CGPoint(x: 1000, y: 120)], radius: 5)
        XCTAssertEqual(result, [stroke])
    }
}
