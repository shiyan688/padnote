import Foundation
import CoreGraphics

/// Ephemeral placement intent. Coordinates are paper points and are never serialized.
public struct AgentResultPlacement: Equatable {
    public enum Mode: Equatable { case append, point }
    public let mode: Mode
    public let pageIndex: Int
    public let xInPage: Double
    public let yInPage: Double
    public let width: Double

    public init(mode: Mode, pageIndex: Int, xInPage: Double, yInPage: Double, width: Double) {
        self.mode = mode; self.pageIndex = pageIndex; self.xInPage = xInPage; self.yInPage = yInPage; self.width = width
    }

    static func append(for document: NoteDocument) -> AgentResultPlacement {
        .init(mode: .append, pageIndex: document.pageCount, xInPage: 16, yInPage: 40,
              width: max(120, document.pageWidth - 32))
    }

    func validate(in document: NoteDocument) throws {
        guard xInPage.isFinite, yInPage.isFinite, width.isFinite, width >= 80,
              pageIndex >= 0, pageIndex < NotePageOperations.maximumPageCount,
              xInPage >= 0, yInPage >= 0, xInPage + width <= document.pageWidth,
              yInPage < document.pageHeight else {
            throw NoteDocumentError.malformed("所选纸面位置或宽度无效，请重新选择。")
        }
        if mode == .point {
            guard pageIndex < document.pageCount else {
                throw NoteDocumentError.malformed("请选择笔记中已经存在的页面。")
            }
        }
    }
}

enum AgentResultPlacementCheck {
    /// Finds only collisions introduced by a layout/profile change. Existing
    /// overlap between the selected flow and an unchanged obstacle is preserved
    /// so legacy annotation layouts do not become permanently uneditable.
    static func firstNewConflict(before: NoteDocument, candidate: NoteDocument,
                                 flowID: String, pdfURL: URL?) throws -> Int? {
        guard let oldFlow = before.textFlows.first(where: { $0.id == flowID }),
              let newFlow = candidate.textFlows.first(where: { $0.id == flowID }) else {
            throw NoteDocumentError.malformed("排版预览中的文字对象已不存在。")
        }
        let oldFragments = NoteTextLayout.fragments(oldFlow, pageHeight: before.pageHeight)
        let proposedFragments = NoteTextLayout.fragments(newFlow, pageHeight: candidate.pageHeight)
        var validatedPDFPages = Set<Int>()
        for fragment in proposedFragments {
            let proposed = fragment.rect.insetBy(dx: -4, dy: -4)
            let oldOnPage = oldFragments.filter { $0.page == fragment.page }
                .map { $0.rect.insetBy(dx: -4, dy: -4) }
            let newOnlyAreas = uncovered(proposed, by: oldOnPage)
            if fragment.page < candidate.pdfPageCount {
                // PDF occupancy expands its ROI by 8 points beyond the normal
                // 4-point collision margin. Subtract that same full footprint
                // from the old profile before querying the new-only areas.
                let paperBounds = CGRect(x: 0, y: 0, width: candidate.pageWidth, height: candidate.pageHeight)
                let proposedPDFROI = fragment.rect.insetBy(dx: -12, dy: -12).intersection(paperBounds)
                guard !proposedPDFROI.isNull, proposedPDFROI.width > 0, proposedPDFROI.height > 0 else {
                    throw NoteRenderer.PDFROIError.invalidROI
                }
                let oldPDFROIs = oldFragments.filter { $0.page == fragment.page }
                    .map { $0.rect.insetBy(dx: -12, dy: -12).intersection(paperBounds) }
                if validatedPDFPages.insert(fragment.page).inserted {
                    // Probe the captured PDF even when its pixels were already
                    // covered by the old layout; unavailable PDF backgrounds
                    // must still fail closed during a profile switch.
                    _ = try NoteRenderer.pdfHasInk(in: proposedPDFROI, document: candidate,
                                                   page: fragment.page, pdfURL: pdfURL, padding: 0)
                }
                for addedArea in uncovered(proposedPDFROI, by: oldPDFROIs) {
                    if try NoteRenderer.pdfHasInk(in: addedArea, document: candidate,
                                                  page: fragment.page, pdfURL: pdfURL, padding: 0) {
                        return fragment.page
                    }
                }
            }
            for flow in before.textFlows where flow.id != flowID {
                for existing in NoteTextLayout.fragments(flow, pageHeight: before.pageHeight)
                    where existing.page == fragment.page {
                    let obstacle = existing.rect.insetBy(dx: -4, dy: -4)
                    if newOnlyAreas.contains(where: { $0.intersects(obstacle) }) {
                        return fragment.page
                    }
                }
            }
            for image in before.images where image.page == fragment.page {
                let obstacle = CGRect(x: image.x, y: image.y, width: image.width, height: image.height)
                if newOnlyAreas.contains(where: { $0.intersects(obstacle) }) {
                    return fragment.page
                }
            }
            for obstacle in strokeBounds(before, page: fragment.page)
                where newOnlyAreas.contains(where: { $0.intersects(obstacle) }) {
                return fragment.page
            }
        }
        return nil
    }

    static func firstConflict(candidate: NoteTextFlow, in document: NoteDocument, pdfURL: URL?,
                              excludingFlowID: String? = nil) throws -> Int? {
        let proposed = NoteTextLayout.fragments(candidate, pageHeight: document.pageHeight)
        for fragment in proposed {
            let rect = fragment.rect.insetBy(dx: -4, dy: -4)
            if fragment.page < document.pdfPageCount,
               try NoteRenderer.pdfHasInk(in: rect, document: document, page: fragment.page, pdfURL: pdfURL) {
                return fragment.page
            }
            for flow in document.textFlows {
                if flow.id == excludingFlowID { continue }
                for existing in NoteTextLayout.fragments(flow, pageHeight: document.pageHeight) where existing.page == fragment.page {
                    if rect.intersects(existing.rect.insetBy(dx: -4, dy: -4)) { return fragment.page }
                }
            }
            for image in document.images where image.page == fragment.page {
                if rect.intersects(CGRect(x: image.x, y: image.y, width: image.width, height: image.height)) { return fragment.page }
            }
            let pageTop = Double(fragment.page) * (document.pageHeight + document.pageGap)
            for stroke in document.strokes {
                let points = stroke.points
                guard !points.isEmpty else { continue }
                // Renderer uses up to 1.4× baseWidth with pressure and round caps.
                let radius = max(0, stroke.baseWidth * 0.7)
                if points.count == 1 {
                    let p = points[0]
                    let dot = CGRect(x: p.x - radius, y: p.y - pageTop - radius, width: radius * 2, height: radius * 2)
                    if dot.intersects(rect) { return fragment.page }
                } else {
                    for pair in zip(points, points.dropFirst()) {
                        let lowY = min(pair.0.y, pair.1.y), highY = max(pair.0.y, pair.1.y)
                        guard highY + radius >= pageTop, lowY - radius <= pageTop + document.pageHeight else { continue }
                        let segment = CGRect(x: min(pair.0.x, pair.1.x) - radius,
                                             y: min(pair.0.y, pair.1.y) - pageTop - radius,
                                             width: abs(pair.1.x - pair.0.x) + radius * 2,
                                             height: abs(pair.1.y - pair.0.y) + radius * 2)
                        if rect.intersects(segment) { return fragment.page }
                    }
                }
            }
        }
        return nil
    }

    private static func strokeBounds(_ document: NoteDocument, page: Int) -> [CGRect] {
        let pageTop = Double(page) * (document.pageHeight + document.pageGap)
        var bounds = [CGRect]()
        for stroke in document.strokes {
            let points = stroke.points
            guard !points.isEmpty else { continue }
            // Keep the same conservative 1.4× baseWidth envelope used by the
            // original insertion collision check, including pressure/highlighter.
            let radius = max(0, stroke.baseWidth * 0.7)
            if points.count == 1 {
                let point = points[0]
                let dot = CGRect(x: point.x - radius, y: point.y - pageTop - radius,
                                 width: radius * 2, height: radius * 2)
                if dot.intersects(CGRect(x: 0, y: 0, width: document.pageWidth, height: document.pageHeight)) {
                    bounds.append(dot)
                }
            } else {
                for pair in zip(points, points.dropFirst()) {
                    let lowY = min(pair.0.y, pair.1.y), highY = max(pair.0.y, pair.1.y)
                    guard highY + radius >= pageTop, lowY - radius <= pageTop + document.pageHeight else { continue }
                    bounds.append(CGRect(x: min(pair.0.x, pair.1.x) - radius,
                                         y: min(pair.0.y, pair.1.y) - pageTop - radius,
                                         width: abs(pair.1.x - pair.0.x) + radius * 2,
                                         height: abs(pair.1.y - pair.0.y) + radius * 2))
                }
            }
        }
        return bounds
    }

    private static func uncovered(_ rect: CGRect, by covers: [CGRect]) -> [CGRect] {
        var pieces = [rect]
        for cover in covers {
            pieces = pieces.flatMap { piece -> [CGRect] in
                let cut = piece.intersection(cover)
                guard !cut.isNull, cut.width > 0, cut.height > 0 else { return [piece] }
                var result = [CGRect]()
                if cut.minY > piece.minY { result.append(CGRect(x: piece.minX, y: piece.minY, width: piece.width, height: cut.minY - piece.minY)) }
                if cut.maxY < piece.maxY { result.append(CGRect(x: piece.minX, y: cut.maxY, width: piece.width, height: piece.maxY - cut.maxY)) }
                if cut.minX > piece.minX { result.append(CGRect(x: piece.minX, y: cut.minY, width: cut.minX - piece.minX, height: cut.height)) }
                if cut.maxX < piece.maxX { result.append(CGRect(x: cut.maxX, y: cut.minY, width: piece.maxX - cut.maxX, height: cut.height)) }
                return result
            }
            if pieces.isEmpty { break }
        }
        return pieces
    }
}
