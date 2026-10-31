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
    static func firstConflict(candidate: NoteTextFlow, in document: NoteDocument, pdfURL: URL?) throws -> Int? {
        let proposed = NoteTextLayout.fragments(candidate, pageHeight: document.pageHeight)
        for fragment in proposed {
            let rect = fragment.rect.insetBy(dx: -4, dy: -4)
            if fragment.page < document.pdfPageCount,
               try NoteRenderer.pdfHasInk(in: rect, document: document, page: fragment.page, pdfURL: pdfURL) {
                return fragment.page
            }
            for flow in document.textFlows {
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
}
