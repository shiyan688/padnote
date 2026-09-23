import Foundation
import UIKit
import ImageIO

public enum NoteCoverError: Error, LocalizedError {
    case invalidImage, tooLarge, dimensions
    public var errorDescription: String? {
        switch self {
        case .invalidImage: return "封面图片无法读取"
        case .tooLarge: return "封面文件不能超过 8 MB"
        case .dimensions: return "封面图片最多支持 1200 万像素"
        }
    }
}

public final class NoteCoverStore {
    public static let maxBytes = 8 * 1024 * 1024
    public static let maxPixels = 12_000_000
    private let directory: URL

    public init(directory: URL? = nil) {
        let root = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
        let suffix = ProcessInfo.processInfo.arguments.contains("--uitesting") ? "PadNoteUITest/covers" : "PadNote/covers"
        self.directory = directory ?? root.appendingPathComponent(suffix, isDirectory: true)
    }

    private func url(_ id: String) -> URL? {
        guard id.count <= 120, !id.isEmpty,
              id.allSatisfy({ $0.isLetter || $0.isNumber || $0 == "-" || $0 == "_" }) else { return nil }
        return directory.appendingPathComponent("\(id).cover.png")
    }

    public func load(noteID: String, maxEdge: CGFloat = 640) -> UIImage? {
        guard let url = url(noteID), let data = try? Data(contentsOf: url) else { return nil }
        return try? Self.decode(data, maxEdge: Int(maxEdge))
    }

    public func assign(noteID: String, image: UIImage) throws {
        guard let url = url(noteID), let cg = image.cgImage else { throw NoteCoverError.invalidImage }
        guard cg.width > 0, cg.height > 0, cg.width <= Self.maxPixels / cg.height else { throw NoteCoverError.dimensions }
        let factor = min(1, 720 / max(image.size.width, image.size.height))
        let size = CGSize(width: max(1, image.size.width * factor), height: max(1, image.size.height * factor))
        let scaled = UIGraphicsImageRenderer(size: size, format: Self.rendererFormat()).image { _ in
            UIColor.white.setFill(); UIRectFill(CGRect(origin: .zero, size: size))
            image.draw(in: CGRect(origin: .zero, size: size))
        }
        guard let data = scaled.pngData(), data.count <= Self.maxBytes else { throw NoteCoverError.tooLarge }
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try data.write(to: url, options: .atomic)
    }

    public func remove(noteID: String) { if let url = url(noteID) { try? FileManager.default.removeItem(at: url) } }

    public static func decode(_ data: Data, maxEdge: Int = 720) throws -> UIImage {
        guard data.count <= maxBytes else { throw NoteCoverError.tooLarge }
        guard let source = CGImageSourceCreateWithData(data as CFData, [kCGImageSourceShouldCache: false] as CFDictionary),
              let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
              let width = properties[kCGImagePropertyPixelWidth] as? Int,
              let height = properties[kCGImagePropertyPixelHeight] as? Int else { throw NoteCoverError.invalidImage }
        guard width > 0, height > 0, width <= maxPixels / height else { throw NoteCoverError.dimensions }
        let options: [CFString: Any] = [kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true, kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceThumbnailMaxPixelSize: max(1, min(720, maxEdge))]
        guard let cg = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else { throw NoteCoverError.invalidImage }
        return UIImage(cgImage: cg)
    }

    public static func builtins() -> [(String, String, UIImage)] {
        [("ruled", "米白横线", cover(0xF4F1E8, pattern: 1)),
         ("grid", "浅蓝方格", cover(0xEAF1F8, pattern: 2)),
         ("dots", "墨绿点阵", cover(0xE8F0EA, pattern: 3)),
         ("blue", "书写蓝", cover(0x285EA8)),
         ("green", "知识库绿", cover(0x2F805B)),
         ("ochre", "赭石", cover(0xA46A2A))]
    }

    private static func cover(_ rgb: UInt32, pattern: Int = 0) -> UIImage {
        UIGraphicsImageRenderer(size: CGSize(width: 640, height: 400), format: rendererFormat()).image { output in
            let ctx = output.cgContext
            UIColor(red: CGFloat((rgb >> 16) & 255) / 255, green: CGFloat((rgb >> 8) & 255) / 255,
                    blue: CGFloat(rgb & 255) / 255, alpha: 1).setFill()
            output.fill(CGRect(x: 0, y: 0, width: 640, height: 400))
            UIColor(red: 0.35, green: 0.48, blue: 0.53, alpha: 0.25).setStroke()
            ctx.setLineWidth(1)
            if pattern == 1 || pattern == 2 {
                for y in stride(from: 28, to: 400, by: 28) {
                    ctx.move(to: CGPoint(x: 0, y: y)); ctx.addLine(to: CGPoint(x: 640, y: y))
                }
            }
            if pattern == 2 {
                for x in stride(from: 28, to: 640, by: 28) {
                    ctx.move(to: CGPoint(x: x, y: 0)); ctx.addLine(to: CGPoint(x: x, y: 400))
                }
            }
            ctx.strokePath()
            if pattern == 3 {
                UIColor(red: 0.18, green: 0.45, blue: 0.30, alpha: 0.35).setFill()
                for x in stride(from: 24, to: 640, by: 32) {
                    for y in stride(from: 24, to: 400, by: 32) {
                        ctx.fillEllipse(in: CGRect(x: x - 1, y: y - 1, width: 2, height: 2))
                    }
                }
            }
        }
    }

    private static func rendererFormat() -> UIGraphicsImageRendererFormat {
        let format = UIGraphicsImageRendererFormat(); format.scale = 1; format.opaque = true
        return format
    }
}
