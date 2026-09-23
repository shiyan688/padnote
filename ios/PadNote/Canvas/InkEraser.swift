import Foundation
import CoreGraphics

/// Exact vector eraser geometry. It computes capsule intersections in the
/// segment parameter domain; it does not sample the stroke at fixed steps.
public enum InkEraser {
    public static func erase(stroke: InkStroke, path: [CGPoint], radius: Double) -> [InkStroke] {
        guard !stroke.points.isEmpty, !path.isEmpty, radius.isFinite, radius > 0 else { return [stroke] }
        if stroke.points.count == 1 {
            let location = point(stroke.points[0])
            let hit = path.count == 1 ? !circleIntervals(location, location, path[0], radius).isEmpty :
                (0..<(path.count - 1)).contains { !capsuleIntervals(location, location, path[$0], path[$0 + 1], radius).isEmpty }
            return hit ? [] : [stroke]
        }
        var removedBySegment: [[ClosedRange<Double>]] = []
        var didRemove = false
        for index in 0..<(stroke.points.count - 1) {
            let a = point(stroke.points[index]), b = point(stroke.points[index + 1])
            var intervals: [ClosedRange<Double>] = []
            if path.count == 1 {
                intervals += capsuleIntervals(a, b, path[0], path[0], radius)
            } else {
                for j in 0..<(path.count - 1) { intervals += capsuleIntervals(a, b, path[j], path[j + 1], radius) }
            }
            let merged = merge(intervals)
            if !merged.isEmpty { didRemove = true }
            removedBySegment.append(merged)
        }
        guard didRemove else { return [stroke] }

        var result: [InkStroke] = []
        var current: [InkPoint] = []
        func flush() {
            guard !current.isEmpty else { return }
            var fragment = stroke
            fragment.id = result.isEmpty ? stroke.id : UUID().uuidString
            fragment.points = current
            result.append(fragment)
            current.removeAll(keepingCapacity: true)
        }
        for index in 0..<(stroke.points.count - 1) {
            let a = stroke.points[index], b = stroke.points[index + 1]
            let kept = complement(removedBySegment[index])
            for interval in kept {
                let start = interpolate(a, b, interval.lowerBound)
                let end = interpolate(a, b, interval.upperBound)
                if current.isEmpty { current.append(start) }
                else if !same(current[current.count - 1], start) { flush(); current.append(start) }
                if !same(current[current.count - 1], end) { current.append(end) }
                if interval.upperBound < 1 - 1e-10 { flush() }
            }
            if kept.last?.upperBound == 1 { continue }
            if kept.isEmpty || kept.last?.upperBound != 1 { flush() }
        }
        // A final point survives only when the last segment's complement ends
        // at its endpoint; the loop above already appended that endpoint.
        flush()
        return result
    }

    private static func capsuleIntervals(_ a: CGPoint, _ b: CGPoint, _ c: CGPoint, _ d: CGPoint, _ radius: Double) -> [ClosedRange<Double>] {
        var intervals: [ClosedRange<Double>] = []
        let vx = d.x - c.x, vy = d.y - c.y
        let length2 = vx * vx + vy * vy
        if length2 < 1e-12 {
            intervals.append(contentsOf: circleIntervals(a, b, c, radius))
            return intervals
        }
        // Infinite strip around CD.
        let crossA = (a.x - c.x) * vy - (a.y - c.y) * vx
        let crossV = (b.x - a.x) * vy - (b.y - a.y) * vx
        let limit = radius * sqrt(length2)
        let strip = linearAbsInterval(crossA, crossV, limit)
        let projectionA = ((a.x - c.x) * vx + (a.y - c.y) * vy) / length2
        let projectionV = (((b.x - a.x) * vx + (b.y - a.y) * vy) / length2)
        let projection = linearInterval(projectionA, projectionV, 0, 1)
        if let strip, let projection, let overlap = intersect(strip, projection) { intervals.append(overlap) }
        intervals += circleIntervals(a, b, c, radius)
        intervals += circleIntervals(a, b, d, radius)
        return intervals
    }

    private static func circleIntervals(_ a: CGPoint, _ b: CGPoint, _ center: CGPoint, _ r: Double) -> [ClosedRange<Double>] {
        let dx = b.x - a.x, dy = b.y - a.y
        let ox = a.x - center.x, oy = a.y - center.y
        let qa = dx * dx + dy * dy
        let qb = 2 * (ox * dx + oy * dy)
        let qc = ox * ox + oy * oy - r * r
        if qa < 1e-12 { return qc <= 0 ? [0...1] : [] }
        let discriminant = qb * qb - 4 * qa * qc
        if discriminant < 0 { return qc <= 0 ? [0...1] : [] }
        let root = sqrt(max(0, discriminant))
        let rawLo = (-qb - root) / (2 * qa), rawHi = (-qb + root) / (2 * qa)
        let lo = max(0, rawLo), hi = min(1, rawHi)
        return lo <= hi ? [lo...hi] : []
    }

    private static func linearAbsInterval(_ value: Double, _ slope: Double, _ limit: Double) -> ClosedRange<Double>? {
        if abs(slope) < 1e-12 { return abs(value) <= limit ? 0...1 : nil }
        let a = (-limit - value) / slope, b = (limit - value) / slope
        let lo = max(0, min(a, b)), hi = min(1, max(a, b))
        return lo <= hi ? lo...hi : nil
    }

    private static func linearInterval(_ value: Double, _ slope: Double, _ lo: Double, _ hi: Double) -> ClosedRange<Double>? {
        if abs(slope) < 1e-12 { return value >= lo && value <= hi ? 0...1 : nil }
        let a = (lo - value) / slope, b = (hi - value) / slope
        let lower = max(0, min(a, b)), upper = min(1, max(a, b))
        return lower <= upper ? lower...upper : nil
    }

    private static func intersect(_ a: ClosedRange<Double>, _ b: ClosedRange<Double>) -> ClosedRange<Double>? {
        let lo = max(a.lowerBound, b.lowerBound), hi = min(a.upperBound, b.upperBound)
        return lo <= hi ? lo...hi : nil
    }

    private static func merge(_ intervals: [ClosedRange<Double>]) -> [ClosedRange<Double>] {
        let sorted = intervals.filter { $0.lowerBound <= $0.upperBound }.sorted { $0.lowerBound < $1.lowerBound }
        var output: [ClosedRange<Double>] = []
        for range in sorted {
            if let last = output.last, range.lowerBound <= last.upperBound + 1e-10 {
                output[output.count - 1] = last.lowerBound...max(last.upperBound, range.upperBound)
            } else { output.append(range) }
        }
        return output
    }

    private static func complement(_ removed: [ClosedRange<Double>]) -> [ClosedRange<Double>] {
        guard !removed.isEmpty else { return [0...1] }
        var result: [ClosedRange<Double>] = []; var cursor = 0.0
        for range in removed {
            if cursor < range.lowerBound - 1e-10 { result.append(cursor...range.lowerBound) }
            cursor = max(cursor, range.upperBound)
        }
        if cursor < 1 - 1e-10 { result.append(cursor...1) }
        return result
    }

    private static func point(_ value: InkPoint) -> CGPoint { CGPoint(x: value.x, y: value.y) }
    private static func interpolate(_ a: InkPoint, _ b: InkPoint, _ t: Double) -> InkPoint {
        InkPoint(x: a.x + (b.x - a.x) * t, y: a.y + (b.y - a.y) * t,
                 pressure: a.pressure + (b.pressure - a.pressure) * t,
                 timestamp: a.timestamp + (b.timestamp - a.timestamp) * t)
    }
    private static func same(_ a: InkPoint, _ b: InkPoint) -> Bool { abs(a.x - b.x) < 1e-9 && abs(a.y - b.y) < 1e-9 }
}
