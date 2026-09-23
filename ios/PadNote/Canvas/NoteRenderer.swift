import Foundation
import UIKit
import PDFKit

/// Rendering is intentionally stateless and uses the portable raw stroke
/// arrays. PencilKit is not used, so Android and iOS can exchange documents.
public enum NoteRenderer {
    private static let pdfLock = NSRecursiveLock()
    private static var cachedPDFURL: URL?
    private static var cachedPDF: PDFDocument?

    public static func renderPage(document: NoteDocument, page: Int, pdfURL: URL?, maxEdge: CGFloat = 1600) -> UIImage {
        guard page >= 0, page < max(1, document.pageCount) else { return UIImage() }
        let scale = min(1, Double(maxEdge) / max(document.pageWidth, document.pageHeight))
        let size = CGSize(width: CGFloat(max(1, document.pageWidth * Double(scale))), height: CGFloat(max(1, document.pageHeight * Double(scale))))
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        let renderer = UIGraphicsImageRenderer(size: size, format: format)
        return renderer.image { context in
            let cg = context.cgContext
            cg.scaleBy(x: CGFloat(scale), y: CGFloat(scale))
            drawPage(document: document, page: page, pdfURL: pdfURL, in: cg)
        }
    }

    static func renderSelection(document: NoteDocument, polygon: [CGPoint], pdfURL: URL?, maxEdge: CGFloat = 1600) -> UIImage? {
        guard polygon.count > 2 else { return nil }
        let worldHeight = Double(document.pageCount) * (document.pageHeight + document.pageGap) - document.pageGap
        let bounds = polygon.reduce(CGRect.null) { $0.union(CGRect(origin: $1, size: .zero)) }
            .insetBy(dx: -8, dy: -8).integral
            .intersection(CGRect(x: 0, y: 0, width: document.pageWidth, height: worldHeight))
        guard !bounds.isNull, bounds.width > 1, bounds.height > 1 else { return nil }
        let scale = min(1, maxEdge / max(bounds.width, bounds.height))
        let format = UIGraphicsImageRendererFormat(); format.scale = 1; format.opaque = true
        return UIGraphicsImageRenderer(size: CGSize(width: bounds.width * scale, height: bounds.height * scale), format: format).image { output in
            let context = output.cgContext
            context.setFillColor(UIColor.white.cgColor)
            context.fill(CGRect(x: 0, y: 0, width: bounds.width * scale, height: bounds.height * scale))
            context.scaleBy(x: scale, y: scale)
            context.translateBy(x: -bounds.minX, y: -bounds.minY)
            let path = CGMutablePath(); path.addLines(between: polygon); path.closeSubpath()
            context.addPath(path); context.clip()
            let stride = document.pageHeight + document.pageGap
            let first = max(0, Int(bounds.minY / stride))
            let last = min(document.pageCount - 1, Int(bounds.maxY / stride))
            for page in first...max(first, last) {
                context.saveGState()
                context.translateBy(x: 0, y: Double(page) * stride)
                drawPage(document: document, page: page, pdfURL: pdfURL, in: context)
                context.restoreGState()
            }
        }
    }

    public static func exportPDF(document: NoteDocument, pdfURL: URL?) -> Data {
        let data = NSMutableData()
        let mediaBox = CGRect(x: 0, y: 0, width: CGFloat(document.pageWidth), height: CGFloat(document.pageHeight))
        UIGraphicsBeginPDFContextToData(data, mediaBox, nil)
        for page in 0..<max(1, document.pageCount) {
            UIGraphicsBeginPDFPageWithInfo(mediaBox, nil)
            guard let context = UIGraphicsGetCurrentContext() else { continue }
            drawPage(document: document, page: page, pdfURL: pdfURL, in: context)
        }
        UIGraphicsEndPDFContext()
        return data as Data
    }

    static func drawPage(document: NoteDocument, page: Int, pdfURL: URL?, in context: CGContext) {
        let width = CGFloat(document.pageWidth)
        let height = CGFloat(document.pageHeight)
        context.saveGState()
        context.clip(to: CGRect(x: 0, y: 0, width: width, height: height))
        drawPaper(document: document, in: context, rect: CGRect(x: 0, y: 0, width: width, height: height))

        if let pdfURL { drawPDF(url: pdfURL, page: page, width: width, height: height, in: context) }

        for image in document.images where image.page == page {
            guard let data = Data(base64Encoded: image.png), let uiImage = UIImage(data: data),
                  uiImage.size.width > 0 else { continue }
            UIGraphicsPushContext(context)
            uiImage.draw(in: CGRect(x: image.x, y: image.y, width: image.width, height: image.height))
            UIGraphicsPopContext()
        }

        for flow in document.textFlows {
            for fragment in NoteTextLayout.fragments(flow, pageHeight: document.pageHeight) where fragment.page == page {
                NoteTextLayout.draw(fragment, in: context)
            }
        }

        let top = Double(page) * (document.pageHeight + document.pageGap)
        context.saveGState()
        context.translateBy(x: 0, y: -top)
        for stroke in document.strokes {
            let ys = stroke.points.map(\.y)
            guard let low = ys.min(), let high = ys.max(),
                  high + stroke.baseWidth >= top, low - stroke.baseWidth <= top + document.pageHeight else { continue }
            drawStroke(stroke, in: context)
        }
        context.restoreGState()
        context.restoreGState()
    }

    private static func drawPDF(url: URL, page: Int, width: CGFloat, height: CGFloat, in context: CGContext) {
        pdfLock.lock(); defer { pdfLock.unlock() }
        guard let pdf = pdfDocument(for: url), page < pdf.pageCount,
              let pageRef = pdf.page(at: page)?.pageRef else { return }
        context.saveGState()
        context.translateBy(x: 0, y: height)
        context.scaleBy(x: 1, y: -1)
        context.concatenate(pageRef.getDrawingTransform(.mediaBox,
            rect: CGRect(x: 0, y: 0, width: width, height: height), rotate: 0, preserveAspectRatio: true))
        context.drawPDFPage(pageRef)
        context.restoreGState()
    }

    private static func pdfDocument(for url: URL) -> PDFDocument? {
        pdfLock.lock(); defer { pdfLock.unlock() }
        if cachedPDFURL != url {
            cachedPDFURL = url
            cachedPDF = PDFDocument(url: url)
        }
        return cachedPDF
    }

    fileprivate static func drawPaper(document: NoteDocument, in context: CGContext, rect: CGRect) {
        let paper = document.pageStyle.paper.lowercased()
        context.setFillColor(UIColor(red: 0.98, green: 0.975, blue: 0.95, alpha: 1).cgColor)
        context.fill(rect)
        context.setStrokeColor(UIColor(red: 0.87, green: 0.86, blue: 0.82, alpha: 0.72).cgColor)
        context.setLineWidth(0.6)
        if paper.contains("grid") || paper.contains("graph") {
            stride(from: rect.minX, through: rect.maxX, by: 24).forEach { x in context.move(to: CGPoint(x: x, y: rect.minY)); context.addLine(to: CGPoint(x: x, y: rect.maxY)) }
            stride(from: rect.minY, through: rect.maxY, by: 24).forEach { y in context.move(to: CGPoint(x: rect.minX, y: y)); context.addLine(to: CGPoint(x: rect.maxX, y: y)) }
            context.strokePath()
        } else if paper.contains("rule") || paper.contains("line") {
            stride(from: rect.minY + 30, through: rect.maxY, by: 30).forEach { y in context.move(to: CGPoint(x: rect.minX, y: y)); context.addLine(to: CGPoint(x: rect.maxX, y: y)) }
            context.strokePath()
        } else if paper.contains("dot") {
            context.setFillColor(UIColor(red: 0.74, green: 0.73, blue: 0.68, alpha: 0.65).cgColor)
            stride(from: rect.minX + 12, through: rect.maxX, by: 24).forEach { x in
                stride(from: rect.minY + 12, through: rect.maxY, by: 24).forEach { y in context.fillEllipse(in: CGRect(x: x - 1, y: y - 1, width: 2, height: 2)) }
            }
        }
    }

    static func drawStroke(_ stroke: InkStroke, in context: CGContext) {
        guard !stroke.points.isEmpty else { return }
        var color = UIColor(padNoteHex: stroke.color)
        if stroke.highlighter { color = color.withAlphaComponent(min(color.cgColor.alpha, 0.42)) }
        context.setStrokeColor(color.cgColor)
        context.setFillColor(color.cgColor)
        context.setLineCap(.round)
        context.setLineJoin(.round)
        if stroke.points.count == 1, let point = stroke.points.first {
            let width = CGFloat(stroke.baseWidth) * CGFloat(0.45 + clamp(point.pressure) * 0.95)
            context.fillEllipse(in: CGRect(x: CGFloat(point.x) - width / 2, y: CGFloat(point.y) - width / 2, width: width, height: width))
            return
        }
        if stroke.highlighter {
            context.setLineWidth(CGFloat(stroke.baseWidth))
            context.beginPath()
            context.move(to: CGPoint(x: CGFloat(stroke.points[0].x), y: CGFloat(stroke.points[0].y)))
            for point in stroke.points.dropFirst() { context.addLine(to: CGPoint(x: CGFloat(point.x), y: CGFloat(point.y))) }
            context.strokePath()
            return
        }
        for index in 1..<stroke.points.count {
            let from = stroke.points[index - 1], to = stroke.points[index]
            context.setLineWidth(stroke.highlighter ? CGFloat(stroke.baseWidth) : CGFloat(stroke.baseWidth) * CGFloat(0.45 + (clamp(from.pressure) + clamp(to.pressure)) * 0.475))
            context.move(to: CGPoint(x: CGFloat(from.x), y: CGFloat(from.y)))
            context.addLine(to: CGPoint(x: CGFloat(to.x), y: CGFloat(to.y)))
            context.strokePath()
        }
    }

    private static func clamp(_ value: Double) -> Double { value > 0 ? min(1, value) : 0.5 }
}

extension UIColor {
    fileprivate convenience init(padNoteHex value: String) {
        var hex = value.trimmingCharacters(in: .whitespacesAndNewlines).replacingOccurrences(of: "#", with: "")
        if hex.count == 6 { hex = "FF" + hex }
        var number: UInt64 = 0
        Scanner(string: hex).scanHexInt64(&number)
        self.init(red: CGFloat((number >> 16) & 0xff) / 255, green: CGFloat((number >> 8) & 0xff) / 255,
                  blue: CGFloat(number & 0xff) / 255, alpha: CGFloat((number >> 24) & 0xff) / 255)
    }
}
