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

public final class NoteCoverStore: @unchecked Sendable {
    public static let maxBytes = 8 * 1024 * 1024
    public static let maxPixels = 12_000_000
    private let directory: URL
    private let groupRootURL: URL

    public init(directory: URL? = nil, groupRootURL: URL? = nil) {
        let root = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
        let suffix = ProcessInfo.processInfo.arguments.contains("--uitesting") ? "PadNoteUITest/covers" : "PadNote/covers"
        self.directory = directory ?? root.appendingPathComponent(suffix, isDirectory: true)
        self.groupRootURL = groupRootURL ?? NoteGroupStore.defaultRootURL()
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
        guard let url = url(noteID) else { throw NoteCoverError.invalidImage }
        let data = try Self.encodedPNG(image)
        try NoteGroupCatalogFence.withWriter {
            try requireLegacyMutationAllowed(noteID: noteID)
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            try data.write(to: url, options: .atomic)
        }
    }

    public static func encodedPNG(_ image: UIImage) throws -> Data {
        guard let cg = image.cgImage else { throw NoteCoverError.invalidImage }
        guard cg.width > 0, cg.height > 0, cg.width <= Self.maxPixels / cg.height else { throw NoteCoverError.dimensions }
        let factor = min(1, 720 / max(image.size.width, image.size.height))
        let size = CGSize(width: max(1, image.size.width * factor), height: max(1, image.size.height * factor))
        let scaled = UIGraphicsImageRenderer(size: size, format: Self.rendererFormat()).image { _ in
            UIColor.white.setFill(); UIRectFill(CGRect(origin: .zero, size: size))
            image.draw(in: CGRect(origin: .zero, size: size))
        }
        guard let data = scaled.pngData(), data.count <= Self.maxBytes else { throw NoteCoverError.tooLarge }
        return data
    }

    public func backupPNGURL(noteID: String) throws -> URL? {
        guard let source = url(noteID), FileManager.default.fileExists(atPath: source.path) else { return nil }
        _ = try LibraryBackupArchive.validateRegularSource(source, maximumBytes: Int64(Self.maxBytes))
        return source
    }

    public func restorePNG(noteID: String, from staged: URL, expectedSize: Int64? = nil, expectedSHA256: String? = nil, cancellation: LibraryBackupCancellationToken? = nil) throws {
        try NoteGroupCatalogFence.withWriter {
            guard let target = url(noteID), !FileManager.default.fileExists(atPath: target.path) else { throw LibraryBackupError.transaction("封面目标已存在或 ID 无效") }
            _ = try Self.validatePNGFile(staged)
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            do {
                let copied = try LibraryBackupArchive.copyVerified(staged, to: target, maximumBytes: Int64(Self.maxBytes), cancellation: cancellation)
                if let expectedSize, copied.size != expectedSize { throw LibraryBackupError.sourceChanged }
                if let expectedSHA256, copied.sha256 != expectedSHA256 { throw LibraryBackupError.sourceChanged }
                _ = try Self.validatePNGFile(target)
            } catch { try? FileManager.default.removeItem(at: target); throw error }
        }
    }

    public static func validatePNGFile(_ url: URL) throws -> (width: Int, height: Int) {
        _ = try LibraryBackupArchive.validateRegularSource(url, maximumBytes: Int64(maxBytes))
        guard let source = CGImageSourceCreateWithURL(url as CFURL, [kCGImageSourceShouldCache: false] as CFDictionary),
              CGImageSourceGetCount(source) == 1,
              CGImageSourceGetType(source) as String? == "public.png",
              let props = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
              let width = props[kCGImagePropertyPixelWidth] as? Int,
              let height = props[kCGImagePropertyPixelHeight] as? Int,
              width > 0, height > 0, width <= maxPixels / height,
              CGImageSourceCreateThumbnailAtIndex(source, 0, [kCGImageSourceCreateThumbnailFromImageAlways: true,
                  kCGImageSourceShouldCacheImmediately: true, kCGImageSourceThumbnailMaxPixelSize: 720] as CFDictionary) != nil else {
            throw NoteCoverError.invalidImage
        }
        return (width, height)
    }

    func rollbackRestoredPNG(noteID: String, expectedSHA256: String) throws {
        try NoteGroupCatalogFence.withWriter {
            guard let target = url(noteID), expectedSHA256.range(of: "^[0-9a-f]{64}$", options: .regularExpression) != nil else {
                throw LibraryBackupError.unsafeFile
            }
            guard FileManager.default.fileExists(atPath: target.path) else { return }
            _ = try LibraryBackupArchive.validateRegularSource(target, maximumBytes: Int64(Self.maxBytes))
            guard try LibraryBackupArchive.hashFile(target).sha256 == expectedSHA256 else { throw LibraryBackupError.sourceChanged }
            try FileManager.default.removeItem(at: target)
        }
    }

    public func remove(noteID: String) throws {
        try NoteGroupCatalogFence.withWriter {
            try requireLegacyMutationAllowed(noteID: noteID)
            if let url = url(noteID), FileManager.default.fileExists(atPath: url.path) {
                try FileManager.default.removeItem(at: url)
            }
        }
    }

    private func requireLegacyMutationAllowed(noteID: String) throws {
        // Historical opaque note IDs cannot address UUID-keyed groups. UUID IDs must consult
        // the current marker while the catalog writer lease is held; any corrupt marker fails closed.
        guard (try? NoteGroupFacade.foundationUUID(noteID)) != nil else { return }
        try NoteGroupStore.requireLegacyMutationAllowed(noteID: noteID, rootURL: groupRootURL)
    }

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
