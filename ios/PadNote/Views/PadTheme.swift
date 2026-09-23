import SwiftUI
import UIKit

enum PadTheme {
    static let surface = Color(hex: 0xF4F1E8)
    static let accent = Color(hex: 0x285EA8)
    static let accentLight = Color(hex: 0xE8F0FA)
    static let ink = Color(hex: 0x1F2933)
    static let secondary = Color(hex: 0x6B7684)
    static let faint = Color(hex: 0x89939B)
    static let border = Color(hex: 0xECE9E2)
    static let vault = Color(hex: 0x2F805B)
}

extension Color {
    init(hex: UInt32) {
        self.init(.sRGB, red: Double((hex >> 16) & 255) / 255,
                  green: Double((hex >> 8) & 255) / 255,
                  blue: Double(hex & 255) / 255, opacity: 1)
    }
}

struct ShareArtifact: Identifiable {
    let id = UUID()
    let url: URL
}

struct ShareSheet: UIViewControllerRepresentable {
    var url: URL
    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: [url], applicationActivities: nil)
    }
    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}

struct PaperPreview: View {
    var style: PageStyle
    var body: some View {
        Canvas { context, size in
            let step: CGFloat = 16
            let line = Color(hex: 0xD9E2EC)
            if style.paper == "grid" || style.paper == "ruled" {
                var path = Path()
                for y in stride(from: step, to: size.height, by: step) {
                    path.move(to: CGPoint(x: 0, y: y)); path.addLine(to: CGPoint(x: size.width, y: y))
                }
                if style.paper == "grid" {
                    for x in stride(from: step, to: size.width, by: step) {
                        path.move(to: CGPoint(x: x, y: 0)); path.addLine(to: CGPoint(x: x, y: size.height))
                    }
                }
                context.stroke(path, with: .color(line), lineWidth: 0.5)
            } else if style.paper == "dotted" {
                for x in stride(from: step, to: size.width, by: step) {
                    for y in stride(from: step, to: size.height, by: step) {
                        context.fill(Path(ellipseIn: CGRect(x: x, y: y, width: 1.5, height: 1.5)), with: .color(line))
                    }
                }
            }
        }
        .background(.white)
    }
}
