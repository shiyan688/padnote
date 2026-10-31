import Foundation
import ImageIO
import UniformTypeIdentifiers
import UIKit
import CryptoKit

enum AgentImageArtifactError: LocalizedError {
    case unsupportedFormat
    case sourceTooLarge
    case invalidImage
    case imageTooLarge
    case pngTooLarge

    var errorDescription: String? {
        switch self {
        case .unsupportedFormat: return "仅支持静态 PNG 或 JPEG 图片。此文件仍可下载或分享。"
        case .sourceTooLarge: return "图片文件超过 20 MB，无法放到纸面。"
        case .invalidImage: return "图片无法解码，或文件内容与声明格式不一致。"
        case .imageTooLarge: return "图片尺寸过大，无法安全生成纸面预览。"
        case .pngTooLarge: return "处理后的图片超过笔记可用空间。"
        }
    }
}

struct NormalizedAgentImage: Sendable {
    let png: Data
    let width: Int
    let height: Int
}

enum AgentImageArtifactCodec {
    static let maximumSourceBytes = 20 * 1024 * 1024
    static let maximumDimension = 16_384
    static let maximumPixels = 64 * 1024 * 1024
    static let maximumEdge = 1_600

    static func normalize(fileURL: URL, declaredMediaType: String, expectedSHA256: String) throws -> NormalizedAgentImage {
        let mediaType = declaredMediaType.lowercased()
        guard mediaType == "image/png" || mediaType == "image/jpeg" else {
            throw AgentImageArtifactError.unsupportedFormat
        }
        let values = try fileURL.resourceValues(forKeys: [.fileSizeKey, .isRegularFileKey])
        guard values.isRegularFile == true, let size = values.fileSize, size > 0 else {
            throw AgentImageArtifactError.invalidImage
        }
        guard size <= maximumSourceBytes else { throw AgentImageArtifactError.sourceTooLarge }
        // The transport already verified this stream. Recheck the staged bytes immediately
        // before ImageIO consumes them so a replaced temporary file cannot be decoded.
        let source = try Data(contentsOf: fileURL, options: [.mappedIfSafe])
        guard Int64(source.count) == Int64(size), sha256(source) == expectedSHA256.lowercased() else {
            throw AgentImageArtifactError.invalidImage
        }
        let pngSignature = Data([137, 80, 78, 71, 13, 10, 26, 10])
        let isPNG = source.starts(with: pngSignature)
        let isJPEG = source.count >= 3 && source[0] == 0xff && source[1] == 0xd8 && source[2] == 0xff
        guard (mediaType == "image/png" && isPNG) || (mediaType == "image/jpeg" && isJPEG) else {
            throw AgentImageArtifactError.invalidImage
        }
        guard let sourceRef = CGImageSourceCreateWithURL(fileURL as CFURL, nil),
              CGImageSourceGetCount(sourceRef) == 1,
              let type = CGImageSourceGetType(sourceRef) else {
            throw AgentImageArtifactError.invalidImage
        }
        if mediaType == "image/png" {
            guard UTType(type as String)?.conforms(to: .png) == true else { throw AgentImageArtifactError.invalidImage }
        } else {
            guard UTType(type as String)?.conforms(to: .jpeg) == true else { throw AgentImageArtifactError.invalidImage }
        }
        guard let properties = CGImageSourceCopyPropertiesAtIndex(sourceRef, 0, nil) as? [CFString: Any],
              let widthNumber = properties[kCGImagePropertyPixelWidth] as? NSNumber,
              let heightNumber = properties[kCGImagePropertyPixelHeight] as? NSNumber else {
            throw AgentImageArtifactError.invalidImage
        }
        let width = widthNumber.intValue, height = heightNumber.intValue
        guard width > 0, height > 0, width <= maximumDimension, height <= maximumDimension,
              width <= maximumPixels / height else { throw AgentImageArtifactError.imageTooLarge }

        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: maximumEdge,
            kCGImageSourceShouldCacheImmediately: true
        ]
        guard let image = CGImageSourceCreateThumbnailAtIndex(sourceRef, 0, options as CFDictionary) else {
            throw AgentImageArtifactError.invalidImage
        }
        guard CGImageSourceGetStatus(sourceRef) == .statusComplete,
              CGImageSourceGetStatusAtIndex(sourceRef, 0) == .statusComplete else {
            throw AgentImageArtifactError.invalidImage
        }
        let normalized = UIImage(cgImage: image, scale: 1, orientation: .up)
        guard let png = normalized.pngData(), !png.isEmpty else { throw AgentImageArtifactError.invalidImage }
        guard png.count <= maximumSourceBytes else { throw AgentImageArtifactError.pngTooLarge }
        return NormalizedAgentImage(png: png, width: image.width, height: image.height)
    }

    static func sha256(_ data: Data) -> String {
        SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }
}
