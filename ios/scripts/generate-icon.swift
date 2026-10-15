#!/usr/bin/env swift
import AppKit
import Foundation

let script = URL(fileURLWithPath: #filePath).standardizedFileURL
guard script.pathComponents.count >= 3 else {
    fputs("Cannot resolve product root from script path.\n", stderr)
    exit(2)
}
let productRoot = script.deletingLastPathComponent().deletingLastPathComponent()
    .deletingLastPathComponent()
let source = productRoot.appendingPathComponent("assets/brand/PadNote-1024.png")
let destination = productRoot.appendingPathComponent(
    "ios/PadNote/Resources/Assets.xcassets/AppIcon.appiconset/AppIcon.png")

do {
    let data = try Data(contentsOf: source)
    guard let bitmap = NSBitmapImageRep(data: data),
          bitmap.pixelsWide == 1024, bitmap.pixelsHigh == 1024,
          !bitmap.hasAlpha else {
        throw NSError(domain: "PadNoteIcon", code: 1,
                      userInfo: [NSLocalizedDescriptionKey:
                        "Expected an opaque 1024x1024 PNG at \(source.path)"])
    }
    try FileManager.default.createDirectory(
        at: destination.deletingLastPathComponent(),
        withIntermediateDirectories: true)
    try data.write(to: destination, options: .atomic)
    print("Copied opaque 1024x1024 PadNote icon to \(destination.path)")
} catch {
    fputs("PadNote icon export failed: \(error.localizedDescription)\n", stderr)
    exit(1)
}
