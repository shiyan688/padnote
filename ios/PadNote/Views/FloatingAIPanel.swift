import SwiftUI

struct FloatingAIPanel<Content: View>: View {
    let content: Content
    let onClose: () -> Void
    @State private var center: CGPoint?
    @State private var size = CGSize(width: 440, height: 580)
    @State private var minimized = false
    @GestureState private var translation = CGSize.zero
    @GestureState private var resizing = CGSize.zero

    init(onClose: @escaping () -> Void, @ViewBuilder content: () -> Content) {
        self.onClose = onClose; self.content = content()
    }
    var body: some View {
        GeometryReader { geometry in
            let width = min(max(300, size.width + resizing.width), max(300, geometry.size.width - 24))
            let height = min(max(320, size.height + resizing.height), max(320, geometry.size.height - 24))
            let visibleHeight = minimized ? 44.0 : height
            let origin = center ?? CGPoint(x: geometry.size.width - width / 2 - 12, y: height / 2 + 12)
            let x = min(max(width / 2, origin.x + translation.width), geometry.size.width - width / 2)
            let y = min(max(visibleHeight / 2, origin.y + translation.height), geometry.size.height - visibleHeight / 2)
            VStack(spacing: 0) {
                HStack {
                    Text("AI 助手").font(.system(size: 15, weight: .semibold))
                    Spacer()
                    Button { minimized.toggle() } label: { Image(systemName: minimized ? "arrow.up.left.and.arrow.down.right" : "minus") }
                        .accessibilityLabel(minimized ? "展开 AI" : "最小化 AI").frame(width: 44, height: 44)
                    Button(action: onClose) { Image(systemName: "xmark") }.accessibilityLabel("关闭 AI").frame(width: 44, height: 44)
                }.padding(.leading, 16).background(PadTheme.accentLight)
                    .contentShape(Rectangle())
                    .gesture(DragGesture().updating($translation) { value, state, _ in state = value.translation }
                        .onEnded { value in
                            center = CGPoint(x: min(max(width / 2, origin.x + value.translation.width), geometry.size.width - width / 2),
                                y: min(max(visibleHeight / 2, origin.y + value.translation.height), geometry.size.height - visibleHeight / 2))
                        })
                // Retain the view and its request task while minimized.
                content.frame(height: minimized ? 0 : height - 68)
                    .clipped().opacity(minimized ? 0 : 1).allowsHitTesting(!minimized)
                if !minimized {
                    HStack {
                        Spacer()
                        Image(systemName: "line.3.horizontal.decrease").rotationEffect(.degrees(-45))
                            .frame(width: 44, height: 24).contentShape(Rectangle())
                            .gesture(DragGesture().updating($resizing) { value, state, _ in state = value.translation }
                                .onEnded { value in
                                    size = CGSize(width: min(max(300, size.width + value.translation.width), max(300, geometry.size.width - 24)),
                                        height: min(max(320, size.height + value.translation.height), max(320, geometry.size.height - 24)))
                                })
                            .accessibilityLabel("调整 AI 面板大小")
                    }.background(.white)
                }
            }.frame(width: width, height: visibleHeight)
                .background(.white).clipShape(RoundedRectangle(cornerRadius: 18))
                .overlay(RoundedRectangle(cornerRadius: 18).stroke(PadTheme.border, lineWidth: 1))
                .position(x: x, y: y)
        }
    }
}
