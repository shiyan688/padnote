import SwiftUI
import UIKit

struct InkToolOptions: View {
    @ObservedObject var canvas: CanvasController
    private var range: ClosedRange<Double> { canvas.tool == .eraser ? 8...64 : canvas.tool == .highlighter ? 4...32 : 0.3...8 }
    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(canvas.tool == .eraser ? "橡皮大小" : canvas.tool == .highlighter ? "高亮粗细" : "画笔粗细")
                .font(.system(size: 17, weight: .semibold))
            Canvas { context, size in
                var path = Path()
                path.move(to: CGPoint(x: 12, y: size.height * 0.7))
                path.addCurve(to: CGPoint(x: size.width - 12, y: size.height * 0.3),
                    control1: CGPoint(x: size.width * 0.3, y: 0), control2: CGPoint(x: size.width * 0.7, y: size.height))
                context.stroke(path, with: .color(canvas.tool == .eraser ? PadTheme.secondary : currentColor),
                    style: StrokeStyle(lineWidth: canvas.strokeWidth, lineCap: .round))
            }.frame(height: 72).background(PadTheme.surface)
            HStack {
                Slider(value: $canvas.strokeWidth, in: range, step: 0.1)
                Text("\(canvas.strokeWidth, specifier: "%.1f") pt").monospacedDigit().frame(width: 64)
            }.font(.system(size: 13))
        }.padding(24).frame(width: 320)
    }
    private var currentColor: Color {
        Color(hex: UInt32((canvas.tool == .highlighter ? canvas.highlighterColor : canvas.inkColor).suffix(6), radix: 16) ?? 0x1F2933)
    }
}

struct InkColorPicker: View {
    @Binding var hex: String
    @State private var hue = 0.0
    @State private var saturation = 0.0
    @State private var brightness = 1.0
    private let diameter = 224.0
    var body: some View {
        VStack(spacing: 16) {
            Text("自定义墨水").font(.system(size: 17, weight: .semibold))
            ZStack {
                Circle().fill(AngularGradient(colors: (0...12).map { Color(hue: Double($0) / 12, saturation: 1, brightness: brightness) }, center: .center))
                Circle().fill(RadialGradient(colors: [Color(white: brightness), .clear], center: .center, startRadius: 0, endRadius: diameter / 2))
                Circle().stroke(.white, lineWidth: 3).background(Circle().stroke(PadTheme.ink, lineWidth: 1))
                    .frame(width: 20, height: 20)
                    .offset(x: cos(hue * .pi * 2) * saturation * diameter / 2,
                            y: sin(hue * .pi * 2) * saturation * diameter / 2)
            }.frame(width: diameter, height: diameter)
                .contentShape(Circle())
                .gesture(DragGesture(minimumDistance: 0).onChanged { value in
                    let x = value.location.x - diameter / 2, y = value.location.y - diameter / 2
                    hue = (atan2(y, x) / (2 * .pi) + 1).truncatingRemainder(dividingBy: 1)
                    saturation = min(1, hypot(x, y) / (diameter / 2)); update()
                }).accessibilityLabel("色相和饱和度")
            HStack {
                Text("亮度").font(.system(size: 13))
                Slider(value: $brightness, in: 0...1).onChange(of: brightness) { _, _ in update() }
            }
            HStack {
                Circle().fill(Color(hue: hue, saturation: saturation, brightness: brightness)).frame(width: 28, height: 28)
                Text("#" + hex.suffix(6)).font(.system(size: 13, design: .monospaced))
            }
        }.padding(24).frame(width: 288)
            .onAppear {
                let value = UInt32(hex.suffix(6), radix: 16) ?? 0
                let color = UIColor(red: Double((value >> 16) & 255) / 255, green: Double((value >> 8) & 255) / 255, blue: Double(value & 255) / 255, alpha: 1)
                var h: CGFloat = 0, s: CGFloat = 0, b: CGFloat = 0
                color.getHue(&h, saturation: &s, brightness: &b, alpha: nil)
                hue = h; saturation = s; brightness = b
            }
    }
    private func update() {
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0
        UIColor(hue: hue, saturation: saturation, brightness: brightness, alpha: 1).getRed(&r, green: &g, blue: &b, alpha: nil)
        hex = String(format: "#FF%02X%02X%02X", Int((r * 255).rounded()), Int((g * 255).rounded()), Int((b * 255).rounded()))
    }
}
