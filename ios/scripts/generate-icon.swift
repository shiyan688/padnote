import AppKit
import Foundation

// Reuses the original PadNote app_icon.svg geometry at App Store resolution.
let size = 1024
let bitmap = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: size, pixelsHigh: size,
    bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false,
    colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
let context = NSGraphicsContext(bitmapImageRep: bitmap)!
NSGraphicsContext.saveGraphicsState()
NSGraphicsContext.current = context
let cg = context.cgContext
cg.scaleBy(x: CGFloat(size) / 216, y: CGFloat(size) / 216)
cg.translateBy(x: 0, y: 216)
cg.scaleBy(x: 1, y: -1)
func color(_ rgb: UInt32) -> CGColor {
    CGColor(red: CGFloat((rgb >> 16) & 255) / 255, green: CGFloat((rgb >> 8) & 255) / 255,
            blue: CGFloat(rgb & 255) / 255, alpha: 1)
}
cg.setFillColor(color(0x17212B)); cg.fill(CGRect(x: 0, y: 0, width: 216, height: 216))
cg.setFillColor(color(0xF5F0E5))
cg.addPath(CGPath(roundedRect: CGRect(x: 57, y: 35, width: 102, height: 146), cornerWidth: 12, cornerHeight: 12, transform: nil)); cg.fillPath()
cg.setStrokeColor(color(0x6A7D8E)); cg.setLineWidth(8); cg.setLineCap(.round)
cg.move(to: CGPoint(x: 79, y: 77)); cg.addLine(to: CGPoint(x: 137, y: 77)); cg.strokePath()
cg.move(to: CGPoint(x: 79, y: 105)); cg.addLine(to: CGPoint(x: 128, y: 105)); cg.strokePath()
cg.setStrokeColor(color(0xD96A4B)); cg.setLineWidth(9)
cg.move(to: CGPoint(x: 78, y: 143))
cg.addCurve(to: CGPoint(x: 139, y: 126), control1: CGPoint(x: 94, y: 125), control2: CGPoint(x: 112, y: 159)); cg.strokePath()
NSGraphicsContext.restoreGraphicsState()
let url = URL(fileURLWithPath: CommandLine.arguments[1])
try bitmap.representation(using: .png, properties: [:])!.write(to: url)
