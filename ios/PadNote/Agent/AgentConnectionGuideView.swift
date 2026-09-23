import SwiftUI
import UIKit

private struct AgentConnectionGuide: Decodable {
    struct Section: Decodable, Identifiable {
        let title: String
        let body: String
        let code: String?
        var id: String { title }
    }

    struct Link: Decodable, Identifiable {
        let title: String
        let url: String
        var id: String { url }
    }

    let title: String
    let summary: String
    let sections: [Section]
    let links: [Link]

    var shareText: String {
        var parts = [title, summary]
        for section in sections {
            parts.append(section.title)
            parts.append(section.body)
            if let code = section.code { parts.append(code) }
        }
        if !links.isEmpty {
            parts.append("相关链接")
            parts.append(contentsOf: links.map { "\($0.title)：\($0.url)" })
        }
        return parts.joined(separator: "\n\n")
    }
}

public struct AgentConnectionGuideView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL
    @State private var result: Result<AgentConnectionGuide, Error>?

    public init() {}

    public var body: some View {
        NavigationStack {
            Group {
                switch result {
                case .success(let guide):
                    ScrollView {
                        VStack(alignment: .leading, spacing: 24) {
                            VStack(alignment: .leading, spacing: 8) {
                                Text(guide.summary).font(.title3)
                                Label("当前仅支持连接测试；任务发送与状态回传尚未实现。", systemImage: "info.circle.fill")
                                    .font(.callout.weight(.semibold))
                                    .foregroundStyle(.secondary)
                            }
                            ForEach(guide.sections) { section in
                                VStack(alignment: .leading, spacing: 10) {
                                    Text(section.title).font(.headline)
                                    CopyableTextBlock(text: section.body)
                                    if let code = section.code { CopyableCodeBlock(code: code) }
                                }
                            }
                            if !guide.links.isEmpty {
                                VStack(alignment: .leading, spacing: 12) {
                                    Text("相关链接").font(.headline)
                                    ForEach(guide.links) { link in
                                        Button {
                                            guard let url = URL(string: link.url), url.scheme?.lowercased() == "https" else { return }
                                            openURL(url)
                                        } label: {
                                            Label(link.title, systemImage: "arrow.up.right.square")
                                        }
                                    }
                                }
                            }
                        }
                        .frame(maxWidth: 760, alignment: .leading)
                        .padding()
                    }
                    .navigationTitle(guide.title)
                    .toolbar {
                        ToolbarItem(placement: .topBarLeading) { ShareLink(item: guide.shareText) }
                        ToolbarItem(placement: .confirmationAction) { Button("完成") { dismiss() } }
                    }
                case .failure:
                    ContentUnavailableView(
                        "无法读取连接教程",
                        systemImage: "exclamationmark.triangle",
                        description: Text("教程文件缺失或格式无效。请重新安装或更新 PadNote 后再试。")
                    )
                    .navigationTitle("连接教程")
                    .toolbar { ToolbarItem(placement: .confirmationAction) { Button("完成") { dismiss() } } }
                case nil:
                    ProgressView("正在读取教程…")
                }
            }
        }
        .task { loadGuideIfNeeded() }
    }

    private func loadGuideIfNeeded() {
        guard result == nil else { return }
        do {
            guard let url = Bundle.main.url(forResource: "agent-connection-guide", withExtension: "json") else {
                throw CocoaError(.fileNoSuchFile)
            }
            result = .success(try JSONDecoder().decode(AgentConnectionGuide.self, from: Data(contentsOf: url)))
        } catch {
            result = .failure(error)
        }
    }
}

private struct CopyableTextBlock: View {
    let text: String

    var body: some View {
        VStack(alignment: .trailing, spacing: 8) {
            Text(text).frame(maxWidth: .infinity, alignment: .leading).textSelection(.enabled)
            Button("复制正文", systemImage: "doc.on.doc") { UIPasteboard.general.string = text }
                .font(.caption)
        }
    }
}

private struct CopyableCodeBlock: View {
    let code: String

    var body: some View {
        VStack(alignment: .trailing, spacing: 8) {
            ScrollView(.horizontal) {
                Text(code)
                    .font(.system(.callout, design: .monospaced))
                    .textSelection(.enabled)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(12)
            }
            .background(.quaternary, in: RoundedRectangle(cornerRadius: 10))
            Button("复制命令", systemImage: "doc.on.doc") { UIPasteboard.general.string = code }
                .font(.caption)
        }
    }
}
