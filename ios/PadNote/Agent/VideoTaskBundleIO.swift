import Foundation
import CryptoKit

public enum VideoTaskBundleError: Error, LocalizedError, Equatable { case invalidPath, tooLarge, invalidInput
    public var errorDescription: String? { switch self { case .invalidPath: return "任务包路径不安全"; case .tooLarge: return "任务包内容过大"; case .invalidInput: return "视频任务参数无效" } }
}

public enum VideoTaskBundleIO {
    public static func make(noteId: String, noteRevision: Int, title: String, markdown: String, audience: String, learningGoal: String, durationSeconds: Int, voiceProfile: String = "zh-CN-neutral", speed: Float = 1) throws -> Data {
        guard !noteId.isEmpty, !title.isEmpty, !markdown.isEmpty, !audience.isEmpty, !learningGoal.isEmpty else { throw VideoTaskBundleError.invalidInput }
        let content = Data(markdown.utf8); guard content.count <= 50 * 1024 * 1024 else { throw VideoTaskBundleError.tooLarge }
        let contentHash = SHA256.hash(data: content).hex
        let bundleHash = SHA256.hash(data: Data("input/content.md\0\(content.count)\0\(contentHash)\n".utf8)).hex
        func json(_ value: Any) throws -> Data { guard JSONSerialization.isValidJSONObject(value) else { throw VideoTaskBundleError.invalidInput }; return try JSONSerialization.data(withJSONObject: value, options: [.prettyPrinted, .sortedKeys]) }
        let request: [String: Any] = ["schema_version":"1.0", "task_type":"video.explain.v1", "task_id":"padnote-\(UUID().uuidString.replacingOccurrences(of: "-", with: ""))", "source":["note_id":noteId, "note_revision":max(1,noteRevision), "title":title, "language":"zh-CN", "entrypoint":"input/content.md", "bundle_sha256":bundleHash], "brief":["audience":audience, "learning_goal":learningGoal, "prerequisites":[], "target_duration_sec":max(1,durationSeconds), "aspect_ratio":"9:16", "style_preset":"clean-academic"], "research":["external_research":false], "review":["storyboard_required":true], "voice":["profile":voiceProfile, "speed":max(0.5,min(2,speed))]]
        let manifest: [String: Any] = ["schema_version":"1.0", "files":[["path":"input/content.md", "media_type":"text/markdown", "size_bytes":content.count, "sha256":contentHash]]]
        return try ZipWriter(entries: [("request.json", try json(request)), ("input/manifest.json", try json(manifest)), ("input/content.md", content), ("work/.keep", Data()), ("output/.keep", Data())]).data()
    }
}

public enum PaperTaskPreset: String, CaseIterable, Identifiable {
    case continueAsRequested = "continue"
    case shareableHandout = "shareable_handout"
    case findGapsAndPractice = "find_gaps_and_practice"
    case explainerVideo = "explain_video"

    public var id: String { rawValue }
    public var title: String {
        switch self {
        case .continueAsRequested: return "继续按我的要求工作"
        case .shareableHandout: return "整理成可分享讲义"
        case .findGapsAndPractice: return "找理解漏洞并出练习"
        case .explainerVideo: return "生成可分享讲解视频"
        }
    }
    public var instruction: String {
        switch self {
        case .continueAsRequested: return "结合整份纸面和上下文，继续按我的要求工作。"
        case .shareableHandout: return "基于整份纸面整理一份准确、便于分享的讲义，保留关键公式和步骤。"
        case .findGapsAndPractice: return "指出纸面中可能存在的理解漏洞，并据此设计有答案的练习。"
        case .explainerVideo: return "基于整份纸面制作可分享讲解视频；模型与配音工具由你或所选 Agent 决定，并按你已授权的范围和既有审阅流程完成。"
        }
    }
}

public enum PaperTaskVideoStyle: String, CaseIterable, Identifiable {
    case clearLecture = "清晰讲义"
    case handwrittenWhiteboard = "手写白板"
    case analogy = "类比讲解"
    case stepByStep = "逐步推导"
    case oneMinuteReview = "一分钟复习"
    public var id: String { rawValue }
    public var prompt: String {
        switch self {
        case .clearLecture: return "用清晰的讲义式画面讲解。每个画面聚焦一个概念，标题简短，公式、图和解释放在一起。例题按步骤展开，减少装饰和套话。"
        case .handwrittenWhiteboard: return "像老师在白板上边写边讲。从问题开始，逐步画出图、写出关键公式和批注，让观看者看清每一步怎样产生。避免一次铺满整张白板。"
        case .analogy: return "先用一个贴近日常的类比建立直觉，再回到材料中的准确概念。明确指出类比适用的范围和容易误解的地方。"
        case .stepByStep: return "围绕一个问题展开完整推导。每一步说明使用的前提、公式或变换，突出本步变化的符号，不跳过影响理解的步骤。"
        case .oneMinuteReview: return "做一个约一分钟的复习视频，选出最值得记住的三个要点，指出一个常见误区，最后留一道简短自测题。保留必要条件和单位，不为缩短时长改变结论。"
        }
    }
}

public enum PaperWorkflowExportError: Error, LocalizedError {
    case saveFailed
    case changedDocument
    public var errorDescription: String? {
        switch self {
        case .saveFailed: return "笔记尚未成功保存，当前版本没有导出或发送。"
        case .changedDocument: return "纸面在准备期间发生变化，请重新生成预览。"
        }
    }
}

public enum PaperTaskBundleError: Error, LocalizedError, Equatable {
    case invalidInput
    case tooLarge
    case invalidPDF
    public var errorDescription: String? {
        switch self {
        case .invalidInput: return "纸面任务内容无效。"
        case .tooLarge: return "完整纸面任务包超过 8 MiB 或内容上限，未发送。"
        case .invalidPDF: return "未能生成可验证的完整纸面 PDF。"
        }
    }
}

public enum PaperTaskBundleIO {
    public static let maximumArchiveBytes = 8 * 1024 * 1024
    public static let maximumExpandedBytes = 32 * 1024 * 1024

    public static func make(noteID: String, noteRevision: Int, title: String,
                            markdown: String, pdf: Data, instruction: String,
                            presetID: String, stylePrompt: String? = nil) throws -> Data {
        let cleanTitle = title.trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanInstruction = instruction.trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanPreset = presetID.trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanStyle = stylePrompt?.trimmingCharacters(in: .whitespacesAndNewlines)
        guard markdown.utf8.count <= maximumExpandedBytes, pdf.count <= maximumExpandedBytes else {
            throw PaperTaskBundleError.tooLarge
        }
        guard !noteID.isEmpty, noteID.utf8.count <= 120, noteRevision > 0,
              !cleanTitle.isEmpty, cleanTitle.count <= 200,
              !cleanInstruction.isEmpty, cleanInstruction.count <= 16_000,
              !cleanPreset.isEmpty, cleanPreset.count <= 120,
              (cleanStyle?.count ?? 0) <= 12_000,
              Data(cleanInstruction.utf8).count <= maximumExpandedBytes,
              (cleanStyle.map { Data($0.utf8).count } ?? 0) <= maximumExpandedBytes,
              !markdown.isEmpty,
              pdf.starts(with: Data("%PDF-".utf8)) else {
            if !pdf.starts(with: Data("%PDF-".utf8)) { throw PaperTaskBundleError.invalidPDF }
            throw PaperTaskBundleError.invalidInput
        }
        let content = Data(markdown.utf8)
        let contentHash = SHA256.hash(data: content).hex
        let pdfHash = SHA256.hash(data: pdf).hex
        let rows: [[String: Any]] = [
            ["path": "input/content.md", "media_type": "text/markdown", "size_bytes": content.count, "sha256": contentHash],
            ["path": "input/paper.pdf", "media_type": "application/pdf", "size_bytes": pdf.count, "sha256": pdfHash]
        ]
        let canonical = rows.map { row in
            "\(row["path"] as! String)\0\(row["size_bytes"] as! Int)\0\(row["sha256"] as! String)\n"
        }.joined()
        let bundleHash = SHA256.hash(data: Data(canonical.utf8)).hex
        var brief: [String: Any] = ["instruction": cleanInstruction, "preset_id": cleanPreset]
        if let cleanStyle, !cleanStyle.isEmpty { brief["style_prompt"] = cleanStyle }
        let request: [String: Any] = [
            "schema_version": "1.0", "task_type": "note.work.v1",
            "task_id": "padnote-\(UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased())",
            "source": ["note_id": noteID, "note_revision": noteRevision, "title": cleanTitle,
                       "entrypoint": "input/content.md", "bundle_sha256": bundleHash],
            "brief": brief
        ]
        let manifest: [String: Any] = ["schema_version": "1.0", "files": rows]
        func json(_ value: Any) throws -> Data {
            guard JSONSerialization.isValidJSONObject(value) else { throw PaperTaskBundleError.invalidInput }
            return try JSONSerialization.data(withJSONObject: value, options: [.prettyPrinted, .sortedKeys])
        }
        let entries: [(String, Data)] = [
            ("request.json", try json(request)),
            ("input/manifest.json", try json(manifest)),
            ("input/content.md", content),
            ("input/paper.pdf", pdf),
            ("work/.keep", Data()),
            ("output/.keep", Data())
        ]
        let expanded = entries.reduce(0) { $0 + $1.1.count }
        guard expanded <= maximumExpandedBytes else { throw PaperTaskBundleError.tooLarge }
        let archive = try ZipWriter(entries: entries).data()
        guard archive.count <= maximumArchiveBytes else { throw PaperTaskBundleError.tooLarge }
        return archive
    }
}

private extension SHA256.Digest { var hex: String { map { String(format: "%02x", $0) }.joined() } }

private struct ZipWriter {
    let entries: [(String, Data)]
    func data() throws -> Data { var out = Data(); var central = Data(); var offset: UInt32 = 0
        for (path, bytes) in entries { guard ZipWriter.safe(path) else { throw VideoTaskBundleError.invalidPath }; let name = Data(path.utf8); let crc = CRC32.checksum(bytes); let local = localHeader(name: name, size: bytes.count, crc: crc); out.append(local); out.append(bytes); central.append(centralHeader(name: name, size: bytes.count, crc: crc, offset: offset)); offset += UInt32(local.count + bytes.count) }
        out.append(central); out.append(u32(0x06054b50)); out.append(u16(0)); out.append(u16(0)); out.append(u16(UInt16(entries.count))); out.append(u16(UInt16(entries.count))); out.append(u32(UInt32(central.count))); out.append(u32(offset)); out.append(u16(0)); return out }
    static func safe(_ p: String) -> Bool { !p.isEmpty && !p.hasPrefix("/") && !p.contains("\\") && !p.contains("\0") && !p.split(separator: "/").contains(where: { $0 == "." || $0 == ".." }) }
    func localHeader(name: Data, size: Int, crc: UInt32) -> Data { var d = Data(); d.append(u32(0x04034b50)); d.append(u16(20)); d.append(u16(0)); d.append(u16(0)); d.append(u16(0)); d.append(u16(0)); d.append(u32(crc)); d.append(u32(UInt32(size))); d.append(u32(UInt32(size))); d.append(u16(UInt16(name.count))); d.append(u16(0)); d.append(name); return d }
    func centralHeader(name: Data, size: Int, crc: UInt32, offset: UInt32) -> Data { var d = Data(); d.append(u32(0x02014b50)); d.append(u16(20)); d.append(u16(20)); d.append(u16(0)); d.append(u16(0)); d.append(u16(0)); d.append(u16(0)); d.append(u32(crc)); d.append(u32(UInt32(size))); d.append(u32(UInt32(size))); d.append(u16(UInt16(name.count))); d.append(u16(0)); d.append(u16(0)); d.append(u16(0)); d.append(u16(0)); d.append(u32(0)); d.append(u32(offset)); d.append(name); return d }
}
private func u16(_ v: UInt16) -> Data { var x = v.littleEndian; return Data(bytes: &x, count: 2) }
private func u32(_ v: UInt32) -> Data { var x = v.littleEndian; return Data(bytes: &x, count: 4) }
private enum CRC32 { static func checksum(_ data: Data) -> UInt32 { var crc: UInt32 = 0xffffffff; for byte in data { crc ^= UInt32(byte); for _ in 0..<8 { crc = (crc >> 1) ^ ((crc & 1) != 0 ? 0xedb88320 : 0) } }; return ~crc } }
